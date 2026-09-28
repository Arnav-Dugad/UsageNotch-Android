package io.github.arnavdugad.usagenotch

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
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
class Repository(val context: Context) {
    val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)
    private val cache = context.getSharedPreferences("usage", Context.MODE_PRIVATE)
    private val vault = Vault(context)
    fun pairing() = runCatching { vault.load() }.getOrNull()
    fun snapshot() = cache.getString("snapshot", null)?.let { runCatching { Snapshot.parse(it) }.getOrNull() }
    fun lastSync() = cache.getLong("sync", 0)
    fun error() = cache.getString("error", "") ?: ""
    fun remaining() = prefs.getBoolean("remaining", true)
    fun use24() = prefs.getBoolean("clock24", false)
    fun reduceMotion() = prefs.getBoolean("reduceMotion", false)
    suspend fun pair(raw: String) = withContext(Dispatchers.IO) { lock.withLock {
        val pair = Pairing.parse(raw)
        val response = fetch(pair) // Verify connection and certificate before replacing the working pairing.
        Snapshot.parse(response)
        vault.save(pair); check(cache.edit().clear().putString("snapshot", response).putLong("sync", System.currentTimeMillis()).commit())
    } }
    suspend fun disconnect() = withContext(Dispatchers.IO) { lock.withLock { vault.clear(); cache.edit().clear().commit() } }
    suspend fun refresh(): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        val pair = pairing() ?: return@withLock false
        val now = System.currentTimeMillis()
        if (now - cache.getLong("attempt", 0) in 0..14_999) return@withLock true
        cache.edit().putLong("attempt", now).commit()
        try {
            val raw = fetch(pair); Snapshot.parse(raw)
            check(cache.edit().putString("snapshot", raw).putLong("sync", now).putString("error", "").commit()); true
        } catch (_: CertificateException) { fail("PC identity changed. Export a new pairing file from your PC.") }
        catch (_: javax.net.ssl.SSLException) { fail("Secure connection failed. Check pairing and both device clocks.") }
        catch (e: BridgeException) { fail(e.message ?: "PC connection failed.") }
        catch (_: Exception) { fail("PC unreachable. Check Wi-Fi, Windows firewall and UsageNotch Link. Saved readings remain available.") }
    } }
    private fun fail(message: String): Boolean { cache.edit().putString("error", message).commit(); return false }
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
        connection.instanceFollowRedirects = false; connection.connectTimeout = 7_000; connection.readTimeout = 10_000
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
