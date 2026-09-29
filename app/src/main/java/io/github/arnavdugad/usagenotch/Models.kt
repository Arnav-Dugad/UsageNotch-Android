package io.github.arnavdugad.usagenotch

import org.json.JSONObject
import java.net.URI
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.Locale
import kotlin.math.roundToInt

data class Reading(val at: Long, val used: Double, val period: String)
/** The desktop's usage-pace estimate: its own summary, the pace in percentage points per hour, and when the limit would be reached (if before the reset). */
data class Forecast(val summary: String, val rate: Double?, val projected: Double?, val limitAt: Long?, val confidence: String)
data class UsageWindow(
    val id: String, val label: String, val used: Double, val at: Long, val reset: Long?, val points: List<Reading>,
    val detail: String? = null, val usedAmount: Double? = null, val limitAmount: Double? = null, val unit: String? = null,
    val forecast: Forecast? = null,
) {
    fun percent(remaining: Boolean) = ((if (remaining) (1 - used).coerceIn(0.0, 1.0) else used) * 100).roundToInt()
    /** The provider's reported reset time has passed since this reading was taken. */
    fun resetPassed(now: Long) = reset != null && now >= reset
    /** Fraction the ring or bar shows: remaining or used, clamped. */
    fun shown(remaining: Boolean) = (if (remaining) 1 - used else used).coerceIn(0.0, 1.0)
}
/** A reported limit without a percentage, such as pay-as-you-go credits. */
data class Extra(val id: String, val label: String, val detail: String?, val reset: Long?, val usedAmount: Double?, val limitAmount: Double?, val unit: String?)
/** One local date of consumption as a fraction of the limit; null when the PC recorded nothing that day. */
data class Day(val date: String, val used: Double?)
/**
 * 30 days of one window from the PC: daily consumption, a weekday x hour heatmap (index weekday*24+hour, Sunday first),
 * the number of distinct observed hours per cell (so missing data is never shown as quiet), and the current streak.
 */
data class History(val window: String, val label: String, val days: List<Day>, val heat: List<Double>, val observed: List<Int>, val streak: Int) {
    fun last(n: Int) = days.takeLast(n)
    /** The busiest observed cells, most first, as (weekday 0=Sunday, hour, consumption). */
    fun busiest(count: Int = 3) = heat.indices.filter { observed.getOrElse(it) { 0 } > 0 && heat[it] > .0001 }.sortedByDescending { heat[it] }.take(count).map { Triple(it / 24, it % 24, heat[it]) }
}
data class Provider(
    val id: String, val name: String, val status: String, val windows: List<UsageWindow>,
    val statusText: String? = null, val account: String? = null, val updatedAt: Long? = null, val manageUrl: String? = null,
    val session: String? = null, val weekly: String? = null, val extras: List<Extra> = emptyList(), val history: List<History> = emptyList(),
) {
    /** The window the desktop dock's outer ring shows. */
    fun sessionWindow(): UsageWindow? = windows.firstOrNull { it.id == session } ?: when (providerKind(id)) {
        "claude" -> windows.firstOrNull { it.id == "five_hour" }
        "codex" -> windows.firstOrNull { it.id == "codex-primary" } ?: windows.firstOrNull { it.id.endsWith("-primary") && !it.id.contains("reserve") }
        else -> null
    } ?: windows.firstOrNull()
    /** The window the desktop dock's inner ring and "7d" figure show. */
    fun weeklyWindow(): UsageWindow? {
        val first = sessionWindow()
        return windows.firstOrNull { it.id == weekly } ?: when (providerKind(id)) {
            "claude" -> windows.firstOrNull { it.id == "seven_day" }
            "codex" -> first?.let { s -> windows.firstOrNull { it.id == s.id.replace("-primary", "-secondary") } }
            else -> null
        } ?: windows.firstOrNull { it.id != first?.id }
    }
    /** Short label for the secondary figure, as on the desktop dock. */
    fun secondaryTag() = if (providerKind(id) in setOf("claude", "codex")) "7d" else "2nd"
}
data class Snapshot(val generatedAt: Long, val providers: List<Provider>, val source: String? = null, val appVersion: String? = null) {
    /** Time of the most recent reading the PC observed, used to never replace newer data with older data. */
    fun newestReading() = providers.flatMap { it.windows }.maxOfOrNull { it.at } ?: 0L
    companion object {
        private fun parseHistory(p: JSONObject): List<History> {
            val list = p.optJSONArray("history") ?: return emptyList()
            return (0 until minOf(list.length(), 4)).mapNotNull { i -> runCatching {
                val h = list.getJSONObject(i); val days = h.getJSONArray("days"); val heat = h.getJSONArray("heat"); val observed = h.getJSONArray("observed")
                require(days.length() <= 60 && heat.length() == 168 && observed.length() == 168)
                History(h.getString("window").take(100), h.getString("label").take(80),
                    (0 until days.length()).map { d -> val day = days.getJSONObject(d); Day(day.getString("date").take(10), if (day.isNull("used") || !day.has("used")) null else day.getDouble("used").takeIf { it.isFinite() && it >= 0 }) },
                    (0 until 168).map { heat.getDouble(it).takeIf { v -> v.isFinite() && v >= 0 } ?: 0.0 }, (0 until 168).map { observed.getInt(it).coerceAtLeast(0) }, h.optInt("streak", 0).coerceIn(0, 10_000))
            }.getOrNull() }
        }
        /** Internet sync details handed over the pinned local link: the new relay, "off", or null when the PC doesn't say. */
        fun relayUpdate(raw: String): Pair<String, Relay?>? = runCatching {
            val root = JSONObject(raw)
            when (root.optString("relayState")) {
                "on" -> "on" to Relay.parse(root.getJSONObject("relay"))
                "off" -> "off" to null
                else -> null
            }
        }.getOrNull()
        private fun JSONObject.text(key: String, max: Int): String? = if (has(key) && !isNull(key)) getString(key).take(max).ifBlank { null } else null
        private fun JSONObject.number(key: String): Double? = if (has(key) && !isNull(key)) getDouble(key).takeIf { it.isFinite() } else null
        private fun JSONObject.time(key: String): Long? = if (has(key) && !isNull(key)) getLong(key) else null
        private fun https(url: String?) = url?.takeIf { runCatching { URI(it).scheme == "https" && URI(it).host != null }.getOrDefault(false) }
        fun parse(raw: String): Snapshot {
            val root = JSONObject(raw)
            require(root.getInt("schema") == 1) { "This PC needs a compatible app version." }
            val providers = root.getJSONArray("providers")
            require(providers.length() <= 16)
            return Snapshot(root.getLong("generatedAt"), (0 until providers.length()).map { i ->
                val p = providers.getJSONObject(i); val windows = p.getJSONArray("windows")
                require(windows.length() <= 32)
                val extras = p.optJSONArray("extras")
                require((extras?.length() ?: 0) <= 32)
                Provider(p.getString("id").take(80), p.getString("name").take(60), p.getString("status").take(40), (0 until windows.length()).map { j ->
                    val w = windows.getJSONObject(j); val used = w.getDouble("used")
                    require(used.isFinite() && used >= 0 && used <= 1000)
                    val points = w.optJSONArray("points")
                    require((points?.length() ?: 0) <= 512)
                    UsageWindow(w.getString("id").take(100), w.getString("label").take(80), used, w.getLong("at"), w.time("reset"),
                        (0 until (points?.length() ?: 0)).map { n -> val r = points!!.getJSONObject(n); val u = r.getDouble("used"); require(u.isFinite() && u >= 0 && u <= 1000); Reading(r.getLong("at"), u, r.getString("period").take(80)) }.sortedBy { it.at },
                        w.text("detail", 160), w.number("usedAmount"), w.number("limitAmount"), w.text("unit", 12),
                        w.optJSONObject("forecast")?.let { f -> Forecast(f.optString("summary").take(120), f.number("rate"), f.number("projected"), f.time("limitAt"), f.optString("confidence").take(60)) })
                }, p.text("statusText", 400), p.text("account", 80), p.time("updatedAt"), https(p.text("manageUrl", 300)),
                    p.text("session", 100), p.text("weekly", 100),
                    (0 until (extras?.length() ?: 0)).map { n -> val e = extras!!.getJSONObject(n)
                        Extra(e.getString("id").take(100), e.getString("label").take(80), e.text("detail", 160), e.time("reset"), e.number("usedAmount"), e.number("limitAmount"), e.text("unit", 12)) },
                    parseHistory(p))
            }, root.optString("source").take(40).ifBlank { null }, root.optString("appVersion").take(20).ifBlank { null })
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
    /** The PC's address for confirmation dialogs, without the port. */
    fun host(): String = runCatching { URI(endpoint).host }.getOrNull() ?: endpoint
    companion object {
        private val code = Regex("UN[12]\\.[A-Za-z0-9_-]+")
        /**
         * Accepts the pairing file (JSON), a `UN1.` pairing code, the `UN2.` code in the PC's QR code, or any
         * text containing one of those codes, such as the QR's web address or a `usagenotch://pair/` link.
         */
        fun parse(input: String): Pairing {
            val text = input.trim()
            if (text.startsWith("{")) return parseJson(text)
            val found = code.find(text.filterNot { it.isWhitespace() })?.value ?: throw IllegalArgumentException("No pairing code found")
            val bytes = Base64.getUrlDecoder().decode(found.substring(4))
            return if (found.startsWith("UN1.")) parseJson(String(bytes, Charsets.UTF_8)) else parseJson(compactToJson(bytes))
        }
        /** Compact QR format written by UsageNotch for Windows 2.3: see PhoneIdentity.CompactCode. */
        internal fun compactToJson(b: ByteArray): String {
            require(b.size >= 73 && b[0].toInt() == 2) { "Unsupported pairing code" }
            var pos = 72
            fun take(n: Int): ByteArray { require(n >= 0 && pos + n <= b.size); return b.copyOfRange(pos, pos + n).also { pos += n } }
            fun short(): String { val n = take(1)[0].toInt() and 0xFF; return String(take(n), Charsets.UTF_8) }
            val ip = (2..5).joinToString(".") { (b[it].toInt() and 0xFF).toString() }
            val port = ((b[6].toInt() and 0xFF) shl 8) or (b[7].toInt() and 0xFF)
            val json = JSONObject().put("schema", 1).put("endpoint", "https://$ip:$port")
                .put("token", Base64.getUrlEncoder().withoutPadding().encodeToString(b.copyOfRange(8, 40)))
                .put("certificateSha256", b.copyOfRange(40, 72).joinToString("") { "%02x".format(it) })
            if (b[1].toInt() and 1 == 1) {
                val key = take(32); val owner = short(); val gist = short()
                json.put("relay", JSONObject().put("url", "https://gist.githubusercontent.com/$owner/$gist/raw/usagenotch-sync.json").put("key", Base64.getUrlEncoder().withoutPadding().encodeToString(key)))
            }
            json.put("name", short())
            require(pos == b.size) { "Unexpected data in pairing code" }
            return json.toString()
        }
        private fun parseJson(raw: String): Pairing {
            val j = JSONObject(raw); require(j.getInt("schema") == 1)
            val uri = URI(j.getString("endpoint"))
            require(uri.scheme == "https" && uri.port in 1024..65535 && uri.userInfo == null && uri.query == null && uri.fragment == null && uri.path.isNullOrEmpty())
            val octets = uri.host?.split('.')?.map { it.toIntOrNull() ?: -1 } ?: emptyList()
            require(octets.size == 4 && octets.all { it in 0..255 } && (octets[0] == 10 || (octets[0] == 192 && octets[1] == 168) || (octets[0] == 172 && octets[1] in 16..31) || (octets[0] == 100 && octets[1] in 64..127))) { "Use a private Wi-Fi or VPN address." }
            val token = j.getString("token"); val pin = j.getString("certificateSha256").lowercase(Locale.ROOT)
            require(token.matches(Regex("[A-Za-z0-9_-]{43}")) && pin.matches(Regex("[a-f0-9]{64}")))
            val relay = j.optJSONObject("relay")?.let { Relay.parse(it) }
            return Pairing(uri.toString(), token, pin, j.optString("name", "Your PC").filterNot { it.isISOControl() }.take(60).ifBlank { "Your PC" }, relay)
        }
    }
}
object ClockText {
    private fun zone() = ZoneId.systemDefault()
    fun stamp(at: Long, use24: Boolean = false): String = DateTimeFormatter.ofPattern(if (use24) "EEE, d MMM · HH:mm" else "EEE, d MMM · h:mm a", Locale.ENGLISH).withZone(zone()).format(Instant.ofEpochMilli(at))
    fun time(at: Long, use24: Boolean = false): String = DateTimeFormatter.ofPattern(if (use24) "HH:mm" else "h:mm a", Locale.ENGLISH).withZone(zone()).format(Instant.ofEpochMilli(at))
    fun age(at: Long, now: Long): String = when { at > now + 60_000 -> "PC clock ahead"; now - at < 60_000 -> "just now"; now - at < 3_600_000 -> "${(now-at)/60_000}m ago"; now - at < 86_400_000 -> "${(now-at)/3_600_000}h ago"; else -> "${(now-at)/86_400_000}d ago" }
    /** Desktop wording: "Updated just now", "Updated 4 min ago", "Updated 3 h ago", then the clock time. */
    fun updated(at: Long, now: Long, use24: Boolean = false): String = when {
        now - at < 45_000 -> "Updated just now"
        now - at < 3_600_000 -> "Updated ${maxOf(1, (now - at) / 60_000)} min ago"
        now - at < 43_200_000 -> "Updated ${(now - at) / 3_600_000} h ago"
        else -> "Updated ${time(at, use24)}"
    }
    fun countdown(at: Long?, now: Long): String {
        if (at == null) return "Reset time not reported"
        if (at <= now) return "Renewed · awaiting PC reading"
        val s = (at - now + 999) / 1000; val days = s / 86400
        return (if (days > 0) "${days}d " else "") + "%02d:%02d:%02d".format(Locale.ROOT, (s / 3600) % 24, (s / 60) % 60, s % 60)
    }
    /** Desktop wording for the right-hand line: "Resets in 09:46:18". */
    fun resetsIn(at: Long?, now: Long): String = when {
        at == null -> "Reset time not reported"
        at <= now -> "Reset due"
        else -> "Resets in " + countdown(at, now)
    }
    /** Desktop wording for the local reset line: "Resets today at 3:29 PM". */
    fun resetsAt(at: Long?, now: Long, use24: Boolean = false): String {
        if (at == null) return "Reset time not reported"
        val day = Instant.ofEpochMilli(at).atZone(zone()).toLocalDate(); val today = Instant.ofEpochMilli(now).atZone(zone()).toLocalDate()
        val label = when (day) { today -> "today"; today.plusDays(1) -> "tomorrow"; else -> DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH).format(day) }
        return (if (at <= now) "Reset " else "Resets ") + "$label at ${time(at, use24)}"
    }
    internal fun localDate(at: Long): LocalDate = Instant.ofEpochMilli(at).atZone(zone()).toLocalDate()
}
/** Desktop amount line: "$3.50 of $20.00", "$3.50 used", or the provider's own detail. */
fun amountText(usedAmount: Double?, limitAmount: Double?, unit: String?, detail: String?): String {
    fun money(v: Double) = if (unit.equals("USD", true)) "$" + "%.2f".format(Locale.ROOT, v) else "%.2f".format(Locale.ROOT, v) + (unit?.let { " $it" } ?: "")
    return when {
        usedAmount != null && limitAmount != null && limitAmount > 0 -> "${money(usedAmount)} of ${money(limitAmount)}"
        usedAmount != null -> "${money(usedAmount)} used"
        else -> detail ?: ""
    }
}
fun readingState(p: Provider, w: UsageWindow, now: Long): String = when {
    w.at > now + 60_000 -> "Check PC clock"
    w.resetPassed(now) -> "Limit renewed"
    p.status != "Ok" || now - w.at > 20 * 60_000 -> "Saved reading"
    else -> "Observed on PC"
}
/** The desktop's status pill, plus Offline when this phone can't reach the PC. */
fun statusPill(p: Provider, now: Long, offline: Boolean): String = when (p.status) {
    "NeedsAuth" -> "Sign in"
    "Unsupported" -> "Unavailable"
    "Error" -> "Error"
    "Loading" -> "Checking"
    else -> {
        val at = p.updatedAt ?: p.windows.maxOfOrNull { it.at }
        if (p.status == "Stale" || offline || (at != null && now - at > 20 * 60_000)) "Saved" else "Live"
    }
}
/** Windows uses underscores for API providers; older Link builds forwarded them unchanged. */
fun providerKind(id: String) = when (id) { "anthropic_api", "anthropic-api" -> "anthropic-api"; "openai_api", "openai-api" -> "openai-api"; else -> id }
