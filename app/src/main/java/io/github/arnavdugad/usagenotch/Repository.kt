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
    /** The latest snapshot, with your synced dock names and provider order applied. */
    fun snapshot() = rawSnapshot()?.let { SharedSettings.present(it, prefs) }
    fun rawSnapshot() = cache.getString("snapshot", null)?.let { runCatching { Snapshot.parse(it) }.getOrNull() }
    /** "throttled" right after a refresh the PC answered with its latest reading instead of a new one; empty otherwise. */
    fun refreshNote(): String = cache.getString("refreshNote", "")?.takeIf { cache.getLong("refreshNoteAt", 0) > System.currentTimeMillis() - 8_000 } ?: ""
    /** Local settings changes that wait for the PC (it syncs them over Wi-Fi only). */
    fun pendingSettings() = SharedSettings.pending(prefs).size
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
    /** "system", "light" or "dark"; widgets follow the same choice. */
    fun appearance() = prefs.getString("appearance", "system") ?: "system"
    suspend fun pair(raw: String) = withContext(Dispatchers.IO) { lock.withLock {
        val pair = Pairing.parse(raw)
        // Verify the PC (or its encrypted internet copy) before replacing the working pairing.
        val lan = runCatching { fetch(pair) }
        val (response, source) = lan.getOrNull()?.let { it to "lan" }
            ?: pair.relay?.let { relay -> runCatching { fetchRelay(relay) }.getOrNull()?.let { it to "internet" } }
            ?: throw lan.exceptionOrNull() ?: IOException("PC unreachable")
        val parsed = Snapshot.parse(response)
        val adopted = Snapshot.relayUpdate(response)?.let { (state, relay) -> pair.copy(relay = if (state == "on") relay else null) } ?: pair
        vault.save(adopted)
        val now = System.currentTimeMillis()
        check(cache.edit().clear().putString("snapshot", response).putLong("sync", now).putLong("checked", now).putString("source", source).commit())
        keepHistory(parsed, now)
        // Pairing with a PC that syncs settings: bring this phone's earlier budgets and colours along, then take the PC's.
        SharedSettings.merge(prefs, parsed.shared)
        if (parsed.has("settings")) SharedSettings.seed(prefs)
        if (source == "lan" && parsed.has("settings")) runCatching { pushSettings(adopted) }
        if (source == "lan" && parsed.has("notes")) runCatching { pushNotes(adopted) }
    } }
    suspend fun disconnect() = withContext(Dispatchers.IO) { lock.withLock { vault.clear(); cache.edit().clear().commit() } }
    /**
     * Fetches new readings: over the pinned local link first, then internet sync. [explicit] is a refresh you asked for
     * (pull to refresh, the refresh button or a widget's refresh): when the PC offers it, the PC then reads your providers
     * again (POST /v1/refresh) instead of sending its last reading.
     */
    suspend fun refresh(explicit: Boolean = false): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        val pair = pairing() ?: return@withLock false
        val now = System.currentTimeMillis()
        if (now - cache.getLong("attempt", 0) in 0..(if (explicit) 2_999 else 14_999)) return@withLock true
        cache.edit().putLong("attempt", now).apply()
        val askPc = explicit && rawSnapshot()?.has("refresh") == true
        val lanProblem = try {
            val result = fetchResult(pair, askPc); val parsed = Snapshot.parse(result.body)
            adoptRelay(pair, result.body)
            cache.edit().putString("refreshNote", if (askPc && result.refresh == "throttled") "throttled" else "").putLong("refreshNoteAt", now).apply()
            val accepted = accept(result.body, Source.Lan, now)
            SharedSettings.merge(prefs, parsed.shared)
            if (parsed.has("settings")) { SharedSettings.seed(prefs); runCatching { pushSettings(pairing() ?: pair) } }
            if (parsed.has("notes")) runCatching { pushNotes(pairing() ?: pair) }
            return@withLock accepted
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
                // Internet sync is read-only for settings: the PC's choices arrive, the phone's wait for Wi-Fi.
                if (snapshot.newestReading() >= newest) return@withLock accept(raw, Source.Internet, now).also { SharedSettings.merge(prefs, snapshot.shared) }
                cache.edit().putLong("checked", now).putBoolean("offline", false).putString("error", lanProblem ?: "").apply()
                return@withLock true
            } catch (_: AEADBadTagException) { return@withLock fail("Internet sync key changed on your PC. Export a new pairing file from UsageNotch Link.", now) }
            catch (e: BridgeException) { return@withLock fail(e.message ?: "Internet sync is unavailable.", now) }
            catch (_: Exception) { /* No internet or GitHub unavailable: fall through to offline. */ }
        }
        if (lanProblem != null) fail(lanProblem, now)
        else { cache.edit().putLong("checked", now).putBoolean("offline", true).putString("error", "").apply(); false }
    } }
    /**
     * The PC says over the pinned, authenticated local link whether internet sync is on and with which key, so turning it
     * on or off on the PC needs nothing on the phone. A snapshot that doesn't mention it (older PCs) leaves the pairing as is.
     */
    private fun adoptRelay(pair: Pairing, raw: String) {
        val (state, relay) = Snapshot.relayUpdate(raw) ?: return
        val wanted = if (state == "on") relay else null
        if (wanted != pair.relay) runCatching { vault.save(pair.copy(relay = wanted)) }
    }
    /** Pushes settings changed on this phone to the PC, then takes its merged copy. Local link only. */
    private fun pushSettings(pair: Pairing) {
        val body = SharedSettings.payload(prefs) ?: return
        val response = putSettings(pair, body)
        SharedSettings.merge(prefs, SharedSettings.parseEntries(JSONObject(response).getJSONObject("shared")))
    }
    /** Sends notes written on this phone to the PC, then takes its merged copy. Local link only. */
    private fun pushNotes(pair: Pairing) {
        val body = LocalHistory.open(context) { DayNotes.payload(it.pendingNotes()) } ?: return
        val response = putNotes(pair, body)
        LocalHistory.open(context) { it.mergeNotes(DayNotes.parseResponse(response)) }
    }
    /** Notes written on this phone that wait for the PC's Wi-Fi. */
    fun pendingNotes(): Int = runCatching { LocalHistory.open(context) { it.pendingNotes().size } }.getOrDefault(0)
    /** Sends a note right after you write it, when the PC is on this network and keeps notes. Quietly waits otherwise. */
    suspend fun syncNotes(): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        val pair = pairing() ?: return@withLock false
        if (rawSnapshot()?.has("notes") != true) return@withLock false
        runCatching { pushNotes(pair) }.isSuccess
    } }
    /** Sends pending settings right after you change one, when the PC is on this network. Quietly waits otherwise. */
    suspend fun syncSettings(): Boolean = withContext(Dispatchers.IO) { lock.withLock {
        val pair = pairing() ?: return@withLock false
        if (rawSnapshot()?.has("settings") != true || SharedSettings.pending(prefs).isEmpty()) return@withLock false
        runCatching { pushSettings(pair) }.isSuccess
    } }
    private fun accept(raw: String, source: Source, now: Long): Boolean {
        check(cache.edit().putString("snapshot", raw).putLong("sync", now).putLong("checked", now).putString("source", if (source == Source.Internet) "internet" else "lan").putBoolean("offline", false).putString("error", "").commit())
        runCatching { keepHistory(Snapshot.parse(raw), now) }
        return true
    }
    /** Adds the snapshot's daily totals (and the PC's notes) to this phone's permanent history. A storage problem never fails a sync. */
    private fun keepHistory(snapshot: Snapshot, now: Long) {
        runCatching { LocalHistory.open(context) { it.save(snapshot, now); if (snapshot.has("notes")) it.mergeNotes(snapshot.notes) } }
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
internal fun fetch(pair: Pairing): String = fetchResult(pair, false).body
internal class LinkResult(val body: String, val refresh: String?)
/** GET /v1/snapshot, or POST /v1/refresh when [refresh] is set; both answer with a snapshot. An older PC without the route gets a GET. */
internal fun fetchResult(pair: Pairing, refresh: Boolean): LinkResult =
    if (!refresh) linkRequest(pair, "/v1/snapshot", "GET", null, 10_000)
    else try { linkRequest(pair, "/v1/refresh", "POST", null, 32_000) } catch (_: MissingRoute) { linkRequest(pair, "/v1/snapshot", "GET", null, 10_000) }
/** PUT /v1/settings with this phone's pending settings; returns the PC's merged map. */
internal fun putSettings(pair: Pairing, body: String): String = linkRequest(pair, "/v1/settings", "PUT", body, 10_000).body
/** PUT /v1/notes with this phone's pending notes (Windows 2.9); returns every note the PC has. */
internal fun putNotes(pair: Pairing, body: String): String = linkRequest(pair, "/v1/notes", "PUT", body, 10_000).body
private class MissingRoute : IOException("Route not offered by this PC")
private fun linkRequest(pair: Pairing, path: String, method: String, body: String?, readTimeout: Int): LinkResult {
    val ssl = SSLContext.getInstance("TLS").apply { init(null, arrayOf(pinnedTrustManager(pair.certificateSha256)), SecureRandom()) }
    val connection = URL(pair.endpoint + path).openConnection() as HttpsURLConnection
    try {
        connection.sslSocketFactory = ssl.socketFactory
        // Identity is the exact out-of-band paired certificate, not a public DNS name.
        connection.hostnameVerifier = javax.net.ssl.HostnameVerifier { _, session ->
            runCatching { certificatePin(session.peerCertificates[0] as X509Certificate) == pair.certificateSha256 }.getOrDefault(false)
        }
        connection.instanceFollowRedirects = false; connection.connectTimeout = 5_000; connection.readTimeout = readTimeout
        connection.requestMethod = method
        connection.setRequestProperty("Authorization", "Bearer " + pair.token)
        connection.setRequestProperty("Accept", "application/json")
        // Protocol 2: the PC adds the heavier fields (7-day trend, insights) only for phones that ask.
        connection.setRequestProperty("X-UsageNotch-Protocol", "2")
        connection.setRequestProperty("X-UsageNotch-Features", Snapshot.FEATURES)
        if (method != "GET") {
            val bytes = (body ?: "").toByteArray(Charsets.UTF_8); require(bytes.size <= if (path == "/v1/notes") DayNotes.MAX_BODY else 32_768) { "Request too large" }
            connection.doOutput = true; connection.setFixedLengthStreamingMode(bytes.size)
            if (body != null) connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(bytes) }
        }
        when (connection.responseCode) {
            200 -> Unit
            401 -> throw BridgeException("Pairing was revoked on your PC. Import a new pairing file.")
            503 -> throw BridgeException("Open UsageNotch on your PC and let it collect a reading.")
            404, 405 -> if (path != "/v1/snapshot") throw MissingRoute() else throw BridgeException("PC could not supply usage. Try refreshing shortly.")
            else -> throw BridgeException("PC could not supply usage. Try refreshing shortly.")
        }
        val text = connection.inputStream.use { stream -> String(readBounded(stream, 2_097_152), Charsets.UTF_8) }
        return LinkResult(text, connection.getHeaderField("X-UsageNotch-Refresh"))
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
