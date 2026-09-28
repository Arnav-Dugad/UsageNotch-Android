package io.github.arnavdugad.usagenotch

import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import kotlin.math.roundToInt

data class Reading(val at: Long, val used: Double, val period: String)
data class UsageWindow(val id: String, val label: String, val used: Double, val at: Long, val reset: Long?, val points: List<Reading>) {
    fun percent(remaining: Boolean) = ((if (remaining) (1 - used).coerceIn(0.0, 1.0) else used) * 100).roundToInt()
    /** The provider's reported reset time has passed since this reading was taken. */
    fun resetPassed(now: Long) = reset != null && now >= reset
}
data class Provider(val id: String, val name: String, val status: String, val windows: List<UsageWindow>)
data class Snapshot(val generatedAt: Long, val providers: List<Provider>) {
    /** Time of the most recent reading the PC observed, used to never replace newer data with older data. */
    fun newestReading() = providers.flatMap { it.windows }.maxOfOrNull { it.at } ?: 0L
    companion object {
        fun parse(raw: String): Snapshot {
            val root = JSONObject(raw)
            require(root.getInt("schema") == 1) { "This PC companion needs a compatible app version." }
            val providers = root.getJSONArray("providers")
            require(providers.length() <= 16)
            return Snapshot(root.getLong("generatedAt"), (0 until providers.length()).map { i ->
                val p = providers.getJSONObject(i); val windows = p.getJSONArray("windows")
                require(windows.length() <= 32)
                Provider(p.getString("id").take(80), p.getString("name").take(60), p.getString("status").take(40), (0 until windows.length()).map { j ->
                    val w = windows.getJSONObject(j); val used = w.getDouble("used")
                    require(used.isFinite() && used >= 0 && used <= 1000)
                    val points = w.optJSONArray("points")
                    require((points?.length() ?: 0) <= 512)
                    UsageWindow(w.getString("id").take(100), w.getString("label").take(80), used, w.getLong("at"), if (w.isNull("reset")) null else w.getLong("reset"),
                        (0 until (points?.length() ?: 0)).map { n -> val r = points!!.getJSONObject(n); val u = r.getDouble("used"); require(u.isFinite() && u >= 0 && u <= 1000); Reading(r.getLong("at"), u, r.getString("period").take(80)) }.sortedBy { it.at })
                })
            })
        }
    }
}
/** Optional end-to-end encrypted copy of the PC's latest snapshot, stored in the owner's secret GitHub gist. */
data class Relay(val url: String, val key: ByteArray) {
    override fun equals(other: Any?) = other is Relay && url == other.url && key.contentEquals(other.key)
    override fun hashCode() = url.hashCode() * 31 + key.contentHashCode()
    companion object {
        private val url = Regex("https://gist\\.githubusercontent\\.com/[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[0-9a-f]{20,40}/raw/usagenotch-sync\\.json")
        fun parse(j: JSONObject): Relay {
            val address = j.getString("url"); require(url.matches(address)) { "Unsupported sync address." }
            val key = Base64.getUrlDecoder().decode(j.getString("key")); require(key.size == 32)
            return Relay(address, key)
        }
    }
}
data class Pairing(val endpoint: String, val token: String, val certificateSha256: String, val name: String, val relay: Relay? = null) {
    fun json(): String = JSONObject().put("schema", 1).put("endpoint", endpoint).put("token", token).put("certificateSha256", certificateSha256).put("name", name).apply {
        relay?.let { put("relay", JSONObject().put("url", it.url).put("key", Base64.getUrlEncoder().withoutPadding().encodeToString(it.key))) }
    }.toString()
    companion object {
        /** Accepts the exported pairing file, or the same content as a copyable `UN1.` pairing code. */
        fun parse(input: String): Pairing {
            val text = input.trim()
            val raw = if (text.startsWith("UN1.")) String(Base64.getUrlDecoder().decode(text.substring(4).filterNot { it.isWhitespace() }), Charsets.UTF_8) else text
            val j = JSONObject(raw); require(j.getInt("schema") == 1)
            val uri = URI(j.getString("endpoint"))
            require(uri.scheme == "https" && uri.port in 1024..65535 && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty())
            val octets = uri.host?.split('.')?.map { it.toIntOrNull() ?: -1 } ?: emptyList()
            require(octets.size == 4 && octets.all { it in 0..255 } && (octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 100 && octets[1] in 64..127))) { "Use a private Wi-Fi or VPN address." }
            val token = j.getString("token"); val pin = j.getString("certificateSha256").lowercase(Locale.ROOT)
            require(token.matches(Regex("[A-Za-z0-9_-]{43}")) && pin.matches(Regex("[a-f0-9]{64}")))
            val relay = j.optJSONObject("relay")?.let { Relay.parse(it) }
            return Pairing(uri.toString(), token, pin, j.optString("name", "Your PC").take(60), relay)
        }
    }
}
object ClockText {
    fun stamp(at: Long, use24: Boolean = false): String = DateTimeFormatter.ofPattern(if (use24) "EEE, d MMM · HH:mm" else "EEE, d MMM · h:mm a", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
    fun time(at: Long, use24: Boolean = false): String = DateTimeFormatter.ofPattern(if (use24) "HH:mm" else "h:mm a", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))
    fun age(at: Long, now: Long): String = when { at > now + 60_000 -> "PC clock ahead"; now - at < 60_000 -> "just now"; now - at < 3_600_000 -> "${(now-at)/60_000}m ago"; now - at < 86_400_000 -> "${(now-at)/3_600_000}h ago"; else -> "${(now-at)/86_400_000}d ago" }
    fun countdown(at: Long?, now: Long): String {
        if (at == null) return "Reset time not reported"
        if (at <= now) return "Renewed · awaiting PC reading"
        val s = (at - now) / 1000; val days = s / 86400
        return (if (days > 0) "${days}d " else "") + "%02d:%02d:%02d".format(Locale.ROOT, (s / 3600) % 24, (s / 60) % 60, s % 60)
    }
}
fun readingState(p: Provider, w: UsageWindow, now: Long): String = when {
    w.at > now + 60_000 -> "Check PC clock"
    w.resetPassed(now) -> "Limit renewed"
    p.status != "Ok" || now - w.at > 20 * 60_000 -> "Saved reading"
    else -> "Observed on PC"
}
/** Windows uses underscores for API providers; older Link builds forwarded them unchanged. */
fun providerKind(id: String) = when (id) { "anthropic_api", "anthropic-api" -> "anthropic-api"; "openai_api", "openai-api" -> "openai-api"; else -> id }
