package io.github.arnavdugad.usagenotch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

class Vault(context: Context) {
    private val prefs = context.getSharedPreferences("pairing-vault", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey("UsageNotch.Pairing.v1", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("UsageNotch.Pairing.v1", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(pair: Pairing) {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, key())
        val data = c.iv + c.doFinal(pair.json().toByteArray(Charsets.UTF_8))
        check(prefs.edit().putString("encrypted", Base64.encodeToString(data, Base64.NO_WRAP)).commit())
    }
    fun load(): Pairing? = prefs.getString("encrypted", null)?.let {
        val data = Base64.decode(it, Base64.NO_WRAP); require(data.size > 28)
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12)))
        Pairing.parse(String(c.doFinal(data.copyOfRange(12, data.size)), Charsets.UTF_8))
    }
    fun clear() { check(prefs.edit().clear().commit()) }
}
/** Where the saved snapshot last came from. Offline means neither path answered, not that anything is wrong. */
enum class Source { Lan, Internet }
class Repository(val context: Context) {
    val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)
    private val cache = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    private val vault = Vault(context)
    fun pairing() = runCatching { vault.load() }.getOrNull()
    fun snapshot() = cache.getString("snapshot", null)?.let { runCatching { Snapshot.parse(it) }.getOrNull() }
    /** When this phone last received usage data, from either path. */
    fun lastSync() = cache.getLong("sync", 0)
    /** When this phone last tried to reach the PC or internet sync. */
    fun lastCheck() = cache.getLong("checked", 0)
    fun source() = if (cache.getString("source", "lan") == "internet") Source.Internet else Source.Lan
    fun offline() = cache.getBoolean("offline", false)
    /** A problem the person can fix, such as a revoked or replaced pairing. Empty when there is none. */
    fun error() = cache.getString("error", "") ?: ""
    fun remaining() = prefs.getBoolean("remaining", true)
    fun use24() = prefs.getBoolean("clock24", false)
    fun reduceMotion() = prefs.getBoolean("reduceMotion", false)
    suspend fun pair(raw: String) = withContext(Dispatchers.IO) { lock.withLock {
        val pair = Pairing.parse(raw)
        // Verify the PC (or its encrypted internet copy) before replacing the working pairing.
        val lan = runCatching { fetch(pair) }
        val (response, source) = lan.getOrNull()?.let { it to "lan" }
            ?: pair.relay?.let { relay -> runCatching { fetchRelay(relay) }.getOrNull()?.let { it to "internet" } }
            ?: throw lan.exceptionOrNull() ?: IOException("PC unreachable")
        Snapshot.parse(response)
        vault.save(pair)
        val now = System.currentTimeMillis()
        check(cache.edit().clear().putString("snapshot", response).putLong("sync", now).putLong("checked", now).putString("source", source).commit())
    } }
    suspend fun disconnect() = withContext(Dispatchers.IO) { lock.withLock { vault.clear(); cache.edit().clear().commit() } }
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        val pair = pairing() ?: return@withLock false
        val now = System.currentTimeMillis()
        if (now - cache.getLong("attempt", 0) in 0..14_999) return@withLock true
        cache.edit().putLong("attempt", now).apply()
        val lanProblem = try {
            val raw = fetch(pair); Snapshot.parse(raw)
            return@withLock accept(raw, Source.Lan, now)
        } catch (_: CertificateException) { "PC identity changed. Export a new pairing file from UsageNotch Link." }
        catch (e: javax.net.ssl.SSLException) {
            // The pinned trust manager's rejection normally arrives wrapped in a handshake failure.
            if (generateSequence<Throwable>(e) { it.cause }.take(8).any { it is CertificateException }) "PC identity changed. Export a new pairing file from UsageNotch Link."
            else "Secure connection to your PC failed. Check the pairing and both device clocks."
        }
        catch (e: BridgeException) { e.message }
        catch (_: Exception) { null } // Unreachable: the PC is off, asleep, or on another network.
        val relay = pair.relay
        if (relay != null) {
            try {
                val raw = fetchRelay(relay); val snapshot = Snapshot.parse(raw)
                val newest = snapshot()?.newestReading() ?: 0L
                // A cached copy can lag behind a reading already received over Wi-Fi; never step backwards.
                if (snapshot.newestReading() >= newest) return@withLock accept(raw, Source.Internet, now)
                cache.edit().putLong("checked", now).putBoolean("offline", false).putString("error", lanProblem ?: "").apply()
                return@withLock true
            } catch (_: AEADBadTagException) { return@withLock fail("Internet sync key changed on your PC. Export a new pairing file from UsageNotch Link.", now) }
            catch (e: BridgeException) { return@withLock fail(e.message ?: "Internet sync is unavailable.", now) }
            catch (_: Exception) { /* No internet or GitHub unavailable: fall through to offline. */ }
        }
        if (lanProblem != null) fail(lanProblem, now)
        else { cache.edit().putLong("checked", now).putBoolean("offline", true).putString("error", "").apply(); false }
    } }
    private fun accept(raw: String, source: Source, now: Long): Boolean {
        check(cache.edit().putString("snapshot", raw).putLong("sync", now).putLong("checked", now).putString("source", if (source == Source.Internet) "internet" else "lan").putBoolean("offline", false).putString("error", "").commit())
        return true
    }
    private fun fail(message: String, now: Long): Boolean { cache.edit().putString("error", message).putLong("checked", now).putBoolean("offline", false).apply(); return false }
    companion object { private val lock = Mutex() }
}
class BridgeException(message: String) : IOException(message)
fun certificatePin(cert: X509Certificate) = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02x".format(it) }
internal fun pinnedTrustManager(pin: String) = object : X509TrustManager {
    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = throw CertificateException("Client authentication unsupported")
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        if (chain.isEmpty() || !MessageDigest.isEqual(certificatePin(chain[0]).toByteArray(), pin.toByteArray())) throw CertificateException("Wrong PC identity")
        chain[0].checkValidity()
    }
}
internal fun fetch(pair: Pairing): String {
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinnedTrustManager(pair.certificateSha256)), SecureRandom()) }
    val connection = URL(pair.endpoint + "/v1/snapshot").openConnection() as HttpsURLConnection
    try {
        connection.sslSocketFactory = ssl.socketFactory
        // Identity is the exact out-of-band paired certificate, not a public DNS name.
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
            runCatching { certificatePin(session.peerCertificates[0] as X509Certificate) == pair.certificateSha256 }.getOrDefault(false)
        }
        connection.instanceFollowRedirects = false; connection.connectTimeout = 5_000; connection.readTimeout = 10_000
        connection.setRequestProperty("Authorization", "Bearer " + pair.token)
        connection.setRequestProperty("Accept", "application/json")
        when (connection.responseCode) {
            200 -> Unit
            401 -> throw BridgeException("Pairing was revoked on your PC. Import a new pairing file.")
            503 -> throw BridgeException("Open UsageNotch on your PC and let it collect a reading.")
            else -> throw BridgeException("PC could not supply usage. Try refreshing shortly.")
        }
        return connection.inputStream.use { stream -> String(readBounded(stream, 1_048_576), Charsets.UTF_8) }
    } finally { connection.disconnect() }
}
/** Downloads the encrypted snapshot from the owner's secret gist. GitHub only ever sees ciphertext. */
internal fun fetchRelay(relay: Relay): String {
    val connection = URL(relay.url).openConnection() as HttpsURLConnection
    try {
        connection.instanceFollowRedirects = false; connection.connectTimeout = 8_000; connection.readTimeout = 10_000
        connection.useCaches = false
        connection.setRequestProperty("Accept", "text/plain")
        when (connection.responseCode) {
            200 -> Unit
            404 -> throw BridgeException("Internet sync was turned off on your PC. Turn it on in UsageNotch Link and export a new pairing file.")
            else -> throw IOException("Internet sync unavailable")
        }
        return openRelay(connection.inputStream.use { String(readBounded(it, 2_097_152), Charsets.UTF_8) }, relay.key)
    } finally { connection.disconnect() }
}
internal const val RELAY_AAD = "UsageNotch relay v1"
/** Envelope: {"v":1,"nonce":base64,"data":base64(ciphertext+tag)}, AES-256-GCM with a fixed associated-data label. */
internal fun openRelay(envelope: String, key: ByteArray): String {
    val j = JSONObject(envelope); require(j.getInt("v") == 1)
    val nonce = java.util.Base64.getDecoder().decode(j.getString("nonce")); require(nonce.size == 12)
    val data = java.util.Base64.getDecoder().decode(j.getString("data")); require(data.size > 16)
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce))
    cipher.updateAAD(RELAY_AAD.toByteArray(Charsets.UTF_8))
    return String(cipher.doFinal(data), Charsets.UTF_8)
}
