package io.github.arnavdugad.usagenotch

import org.json.JSONArray
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
data class Forecast(val summary: String, val rate: Double?, val projected: Double?, val limitAt: Long?, val confidence: String,
    /** The 10th and 90th percentile observed paces (percentage points per hour) from Windows 2.6: the explorer's band. */
    val lowRate: Double? = null, val highRate: Double? = null)
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
/**
 * One local date of consumption as a fraction of the limit; null when the PC recorded nothing that day. From Windows 2.7
 * [used] includes usage that happened while the PC was off or asleep (counted on the day it was next read), and [coverage]
 * says how much of the day the PC observed (0..1), or null from older PCs.
 */
data class Day(val date: String, val used: Double?, val coverage: Double? = null)
/** A part of the week's usage as the provider reports it, such as "Claude Code" at 100%. */
data class Share(val name: String, val percent: Double)
/**
 * 30 days of one window from the PC: daily consumption, a weekday x hour heatmap (index weekday*24+hour, Sunday first),
 * the number of distinct observed hours per cell (so missing data is never shown as quiet), and the current streak.
 */
/** A finished limit period and the highest reading in it; unused capacity is 1 - [peak]. */
data class UnusedPeriod(val end: Long, val peak: Double)
/** When a limit was reached (≥ 99.5%) and when it then reset. */
data class LimitHit(val at: Long, val reset: Long?)
/** How steady the provider's reported reset times were over the last 30 days. */
data class Reliability(val periods: Int, val fixed: Int, val moved: Int, val averageShiftMinutes: Double, val confirmed: Int, val changed: Int)
data class HistoryEvent(val at: Long, val kind: String, val text: String)
/** A run of continuous readings on the PC: [used] percentage points consumed, [peak] the fastest pace in points an hour. */
data class WorkSession(val start: Long, val end: Long, val used: Double, val peak: Double)
/**
 * Everything recorded for one window: from the PC (feature "alltime", Windows 2.8), or worked out from the days saved on
 * this phone when the PC doesn't send it ([onPhone]). [used] and [bestUsed] are in limits: 2.5 = two and a half full
 * limits. [hits] counts limit periods that reached 99.5%; null when only this phone's daily totals are known.
 */
data class AllTime(val since: Long, val used: Double, val activeDays: Int, val recordedDays: Int, val hits: Int?, val bestDay: String?, val bestUsed: Double, val onPhone: Boolean = false)
/** "This week" for one provider's main limit (feature "report"): the last 7 local days against the 7 before. */
data class ReportRow(val id: String, val name: String, val window: String, val label: String, val used: Double?, val previous: Double?,
    /** (used - previous) / previous, or null when the PC couldn't compare the weeks fairly. */
    val change: Double?, val activeDays: Int, val hits: Int, val busiestDay: String?, val busiestUsed: Double?, val streak: Int)
/** A sentence about the week, written on the PC so both apps say the same thing. */
data class ReportLine(val kind: String, val provider: String?, val text: String)
data class WeekReport(val from: String, val to: String, val generatedAt: Long, val headline: String, val summary: String, val providers: List<ReportRow>, val insights: List<ReportLine>)
data class History(
    val window: String, val label: String, val days: List<Day>, val heat: List<Double>, val observed: List<Int>, val streak: Int, val calendar: List<Day> = emptyList(),
    /** Protocol 2: seven days of readings, oldest first. */
    val trend: List<Reading> = emptyList(), val unused: List<UnusedPeriod> = emptyList(), val hits: List<LimitHit> = emptyList(),
    val reliability: Reliability? = null, val events: List<HistoryEvent> = emptyList(), val sessions: List<WorkSession> = emptyList(),
    val allTime: AllTime? = null,
) {
    fun last(n: Int) = days.takeLast(n)
    /** The last [n] days for the 7, 30 and 90 day ranges: from the calendar (90 days, or more with this phone's saved days), else the 30 days. */
    fun range(n: Int) = calendar.ifEmpty { days }.takeLast(n)
    /** The busiest observed cells, most first, as (weekday 0=Sunday, hour, consumption). */
    fun busiest(count: Int = 3) = heat.indices.filter { observed.getOrElse(it) { 0 } > 0 && heat[it] > .0001 }.sortedByDescending { heat[it] }.take(count).map { Triple(it / 24, it % 24, heat[it]) }
}
data class Provider(
    val id: String, val name: String, val status: String, val windows: List<UsageWindow>,
    val statusText: String? = null, val account: String? = null, val updatedAt: Long? = null, val manageUrl: String? = null,
    val session: String? = null, val weekly: String? = null, val extras: List<Extra> = emptyList(), val history: List<History> = emptyList(),
    /** Protocol 2 "accounts": the provider type behind an id such as `claude:work`, and the label you gave that account. */
    val kind: String = providerKind(id), val accountLabel: String? = null,
    /** The subscription the provider reports ("Max 5×", "Plus", "Edu"…), from Windows 2.7. */
    val plan: String? = null,
    /** Where this week's usage went, from Windows 2.7 (Claude). */
    val breakdown: List<Share> = emptyList(),
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
/** One synced setting: last writer wins by [at]; a null [v] means "back to the default" and is kept as a tombstone. */
data class SharedEntry(val v: Any?, val at: Long)
/** A provider status-page incident relayed by the PC. */
data class Incident(val provider: String, val title: String, val impact: String, val status: String, val url: String?, val updatedAt: Long?)
data class TokenTotals(val input: Long, val output: Long, val cacheRead: Long, val cacheWrite: Long, val total: Long, val cost: Double?)
data class TokenDay(val date: String, val total: Long, val cost: Double?)
data class TokenShare(val name: String, val total: Long, val cost: Double?, val share: Double)
/** Tokens a coding tool recorded on the PC (Claude Code, Codex), with an API-price equivalent cost. */
data class TokenSource(val id: String, val label: String, val today: TokenTotals?, val week: TokenTotals?, val days: List<TokenDay>, val projects: List<TokenShare>, val models: List<TokenShare>,
    /** Totals from the provider for the whole account, across every device (Codex), rather than this PC's transcripts. Costs are null then. */
    val allDevices: Boolean = false, val lifetime: Long? = null, val peakDaily: Long? = null, val longestStreak: Int? = null)
data class TokenUsage(val updatedAt: Long, val sources: List<TokenSource>)

data class Snapshot(
    val generatedAt: Long, val providers: List<Provider>, val source: String? = null, val appVersion: String? = null,
    /** Negotiated protocol: absent (1) from UsageNotch for Windows 2.3–2.5, 2 from 2.6. Capabilities are read from [features], never inferred. */
    val protocol: Int = 1, val features: Set<String> = emptySet(),
    val shared: Map<String, SharedEntry> = emptyMap(), val incidents: List<Incident> = emptyList(), val tokens: TokenUsage? = null,
    /** "This week", from Windows 2.8 (feature "report"). */
    val report: WeekReport? = null,
) {
    /** Time of the most recent reading the PC observed, used to never replace newer data with older data. */
    fun newestReading() = providers.flatMap { it.windows }.maxOfOrNull { it.at } ?: 0L
    fun has(feature: String) = feature in features
    companion object {
        /** What this app understands, sent to the PC so it can include the heavier protocol 2 fields. */
        const val FEATURES = "refresh,settings,trend,insights,tokens,incidents,accounts,report,alltime"
        private fun <T> JSONObject.list(key: String, max: Int, item: (JSONObject) -> T?): List<T> {
            val a = optJSONArray(key) ?: return emptyList()
            require(a.length() <= max) { "Too many $key" }
            return (0 until a.length()).mapNotNull { i -> runCatching { item(a.getJSONObject(i)) }.getOrNull() }
        }
        private fun JSONObject.long(key: String): Long = getLong(key).also { require(it in 0..4_102_444_800_000L) }
        private fun JSONObject.count(key: String): Long = if (has(key) && !isNull(key)) getLong(key).coerceIn(0, 1_000_000_000_000L) else 0
        private fun JSONObject.fraction(key: String): Double = getDouble(key).also { require(it.isFinite() && it >= 0 && it <= 1000) }
        private fun totals(j: JSONObject?): TokenTotals? = j?.let { TokenTotals(it.count("input"), it.count("output"), it.count("cacheRead"), it.count("cacheWrite"), it.count("total"), it.number("cost")?.takeIf { c -> c >= 0 && c < 1e9 }) }
        private fun parseTokens(root: JSONObject): TokenUsage? = root.optJSONObject("tokens")?.let { t -> runCatching {
            TokenUsage(t.optLong("updatedAt", 0), t.list("sources", 8) { s ->
                fun shares(key: String, max: Int) = s.list(key, max) { TokenShare(it.getString("name").filterNot { c -> c.isISOControl() }.take(80), it.count("total"), it.number("cost"), (it.number("share") ?: 0.0).coerceIn(0.0, 1.0)) }
                TokenSource(s.getString("id").take(80), s.getString("label").take(60), totals(s.optJSONObject("today")), totals(s.optJSONObject("week")),
                    s.list("days", 60) { TokenDay(it.getString("date").take(10), it.count("total"), it.number("cost")) }, shares("projects", 8), shares("models", 6),
                    s.optBoolean("allDevices", false), s.time("lifetime")?.coerceIn(0, 1_000_000_000_000_000L), s.time("peakDaily")?.coerceIn(0, 1_000_000_000_000_000L),
                    if (s.has("longestStreak") && !s.isNull("longestStreak")) s.getInt("longestStreak").coerceIn(0, 100_000) else null)
            })
        }.getOrNull() }
        private fun JSONObject.clean(key: String, max: Int): String? = text(key, max * 2)?.filterNot { it.isISOControl() }?.trim()?.take(max)?.ifBlank { null }
        private fun JSONObject.limits(key: String): Double? = number(key)?.takeIf { it >= 0 && it <= 100_000 }
        private val isoDate = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        private fun JSONObject.date(key: String): String? = text(key, 10)?.takeIf { isoDate.matches(it) }
        private fun parseReport(root: JSONObject): WeekReport? = root.optJSONObject("report")?.let { r -> runCatching {
            WeekReport(r.date("from")!!, r.date("to")!!, r.optLong("generatedAt", 0), r.clean("headline", 80)!!, r.clean("summary", 120) ?: "",
                r.list("providers", 16) { p ->
                    ReportRow(p.getString("id").take(80), p.clean("name", 60) ?: p.getString("id").take(60), p.text("window", 100) ?: "", p.clean("label", 80) ?: "",
                        p.limits("used"), p.limits("previous"), p.number("change")?.takeIf { it >= -1 && it <= 100_000 },
                        p.optInt("activeDays").coerceIn(0, 7), p.optInt("hits").coerceIn(0, 1_000), p.date("busiestDay"), p.limits("busiestUsed"), p.optInt("streak").coerceIn(0, 100_000))
                },
                r.list("insights", 16) { i -> i.clean("text", 240)?.let { ReportLine(i.text("kind", 20) ?: "", i.text("provider", 80), it) } }.take(7))
        }.getOrNull() }
        private fun parseAllTime(h: JSONObject): AllTime? = h.optJSONObject("allTime")?.let { a -> runCatching {
            AllTime(a.long("since"), (a.limits("used") ?: 0.0), a.optInt("activeDays").coerceIn(0, 1_000_000), a.optInt("recordedDays").coerceIn(0, 1_000_000),
                a.optInt("hits").coerceIn(0, 1_000_000), a.date("bestDay"), a.limits("bestUsed") ?: 0.0)
        }.getOrNull() }
        private fun parseShared(root: JSONObject): Map<String, SharedEntry> = root.optJSONObject("shared")?.let { SharedSettings.parseEntries(it) } ?: emptyMap()
        private fun parseIncidents(root: JSONObject): List<Incident> = runCatching { root.list("incidents", 16) { i ->
            Incident(i.getString("provider").take(40), i.getString("title").filterNot { it.isISOControl() }.take(160), i.optString("impact", "minor").take(20),
                i.optString("status", "").take(30), https(i.text("url", 300)), i.time("updatedAt"))
        } }.getOrDefault(emptyList())
        private fun parseInsights(h: JSONObject) = InsightParts(
            h.list("trend", 512) { r -> Reading(r.long("at"), r.fraction("used"), r.optString("period").take(64)) },
            h.list("unused", 24) { UnusedPeriod(it.long("end"), it.fraction("peak")) },
            h.list("hits", 40) { LimitHit(it.long("at"), it.time("reset")) },
            h.optJSONObject("reliability")?.let { r -> runCatching { Reliability(r.optInt("periods").coerceIn(0, 100_000), r.optInt("fixed").coerceIn(0, 100_000), r.optInt("moved").coerceIn(0, 100_000),
                (r.number("averageShiftMinutes") ?: 0.0).coerceIn(0.0, 100_000.0), r.optInt("confirmed").coerceIn(0, 100_000), r.optInt("changed").coerceIn(0, 100_000)) }.getOrNull() },
            h.list("events", 60) { HistoryEvent(it.long("at"), it.getString("kind").take(40), it.optString("text").filterNot { c -> c.isISOControl() }.take(300)) },
            h.list("sessions", 12) { WorkSession(it.long("start"), it.long("end"), (it.number("used") ?: 0.0).coerceIn(0.0, 10_000.0), (it.number("peak") ?: 0.0).coerceIn(0.0, 10_000.0)) },
        )
        private class InsightParts(val trend: List<Reading>, val unused: List<UnusedPeriod>, val hits: List<LimitHit>, val reliability: Reliability?, val events: List<HistoryEvent>, val sessions: List<WorkSession>)
        private fun parseHistory(p: JSONObject): List<History> {
            val list = p.optJSONArray("history") ?: return emptyList()
            return (0 until minOf(list.length(), 4)).mapNotNull { i -> runCatching {
                val h = list.getJSONObject(i); val days = h.getJSONArray("days"); val heat = h.getJSONArray("heat"); val observed = h.getJSONArray("observed")
                val calendar = h.optJSONArray("calendar")
                require(days.length() <= 60 && heat.length() == 168 && observed.length() == 168 && (calendar?.length() ?: 0) <= 120)
                fun daysOf(a: JSONArray) = (0 until a.length()).map { d -> val day = a.getJSONObject(d); Day(day.getString("date").take(10), if (day.isNull("used") || !day.has("used")) null else day.getDouble("used").takeIf { it.isFinite() && it >= 0 },
                    day.number("coverage")?.takeIf { it >= 0 }?.coerceAtMost(1.0)) }
                // Protocol 2 extras are optional and individually bounded; a damaged one is dropped, never the whole history.
                val extra = runCatching { parseInsights(h) }.getOrNull()
                History(h.getString("window").take(100), h.getString("label").take(80), daysOf(days),
                    (0 until 168).map { heat.getDouble(it).takeIf { v -> v.isFinite() && v >= 0 } ?: 0.0 }, (0 until 168).map { observed.getInt(it).coerceAtLeast(0) }, h.optInt("streak", 0).coerceIn(0, 10_000),
                    calendar?.let { daysOf(it) } ?: emptyList(),
                    extra?.trend.orEmpty(), extra?.unused.orEmpty(), extra?.hits.orEmpty(), extra?.reliability, extra?.events.orEmpty(), extra?.sessions.orEmpty(),
                    parseAllTime(h))
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
                val id = p.getString("id").take(80)
                val accountLabel = p.text("accountLabel", 40)?.filterNot { it.isISOControl() }
                val baseName = p.getString("name").take(60)
                // Extra accounts read as "Claude · Work", unless the PC already put the label in the name.
                val name = if (accountLabel != null && !baseName.contains(accountLabel, ignoreCase = true)) "$baseName · $accountLabel" else baseName
                val parsedExtras = (0 until (extras?.length() ?: 0)).map { n -> val e = extras!!.getJSONObject(n)
                    Extra(e.getString("id").take(100), e.getString("label").take(80), e.text("detail", 160), e.time("reset"), e.number("usedAmount"), e.number("limitAmount"), e.text("unit", 12)) }
                Provider(id, name, p.getString("status").take(40), (0 until windows.length()).map { j ->
                    val w = windows.getJSONObject(j); val used = w.getDouble("used")
                    require(used.isFinite() && used >= 0 && used <= 1000)
                    val points = w.optJSONArray("points")
                    require((points?.length() ?: 0) <= 512)
                    UsageWindow(w.getString("id").take(100), w.getString("label").take(80), used, w.getLong("at"), w.time("reset"),
                        (0 until (points?.length() ?: 0)).map { n -> val r = points!!.getJSONObject(n); val u = r.getDouble("used"); require(u.isFinite() && u >= 0 && u <= 1000); Reading(r.getLong("at"), u, r.getString("period").take(80)) }.sortedBy { it.at },
                        w.text("detail", 160), w.number("usedAmount"), w.number("limitAmount"), w.text("unit", 12),
                        w.optJSONObject("forecast")?.let { f -> Forecast(f.optString("summary").take(120), f.number("rate"), f.number("projected"), f.time("limitAt"), f.optString("confidence").take(60),
                            f.number("lowRate")?.takeIf { it >= 0 }, f.number("highRate")?.takeIf { it >= 0 }) })
                }, p.text("statusText", 400), p.text("account", 80), p.time("updatedAt"), https(p.text("manageUrl", 300)),
                    p.text("session", 100), p.text("weekly", 100),
                    parsedExtras,
                    parseHistory(p), p.text("kind", 40)?.let { providerKind(it) } ?: providerKind(id), accountLabel,
                    p.text("plan", 30)?.filterNot { it.isISOControl() },
                    runCatching { p.list("breakdown", 16) { b -> Share(b.getString("name").filterNot { it.isISOControl() }.take(40), (b.number("percent") ?: 0.0).coerceIn(0.0, 100.0)) }.filter { it.percent > 0 }.take(8) }.getOrDefault(emptyList()))
            }, root.optString("source").take(40).ifBlank { null }, root.optString("appVersion").take(20).ifBlank { null },
                root.optInt("protocol", 1).coerceIn(1, 100),
                root.optJSONArray("features")?.let { f -> require(f.length() <= 64); (0 until f.length()).map { f.getString(it).take(40) }.toSet() } ?: emptySet(),
                parseShared(root), parseIncidents(root), parseTokens(root), parseReport(root))
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
/**
 * Desktop amount line: "$3.50 of $20", "30 of 150 credits", "$3.50 used", or the provider's own detail. Whole numbers drop
 * their decimals; the unit is said once.
 */
fun amountText(usedAmount: Double?, limitAmount: Double?, unit: String?, detail: String?): String {
    val usd = unit.equals("USD", true)
    fun num(v: Double) = if (kotlin.math.abs(v - kotlin.math.round(v)) < .005) "%,.0f".format(Locale.US, v) else "%,.2f".format(Locale.US, v)
    fun money(v: Double) = if (usd) "$" + num(v) else num(v)
    val suffix = if (usd || unit.isNullOrBlank()) "" else " $unit"
    return when {
        usedAmount != null && limitAmount != null && limitAmount > 0 -> "${money(usedAmount)} of ${money(limitAmount)}$suffix"
        usedAmount != null -> "${money(usedAmount)}$suffix used"
        else -> detail ?: ""
    }
}

/**
 * Usage in "limits", worded as on the desktop: under one full limit as a percentage ("48%"), from one up as a multiple
 * ("1.6×", "2×"), so a week of 561% reads as "5.6×" — five and a half limits' worth.
 */
object LimitText {
    /** Percentages up to 105% ("100%", never "1×"), multiples from there ("1.6×"), as on Windows 2.8. */
    const val MULTIPLE = 1.05
    fun of(fraction: Double?): String = when {
        fraction == null -> "—"
        fraction >= MULTIPLE -> "%.1f".format(Locale.US, fraction).removeSuffix(".0") + "×"
        else -> "${(fraction * 100).roundToInt()}%"
    }
    /** "5.6 full current session limits' worth" or "of one current session limit". */
    fun worth(fraction: Double?, label: String): String {
        val name = label.lowercase(Locale.ENGLISH)
        return if (fraction != null && fraction >= MULTIPLE) "%.1f".format(Locale.US, fraction).removeSuffix(".0") + " full $name limits' worth" else "of one $name limit"
    }
    /** "1.6× more" or "30% less" than the average. */
    fun compared(used: Double, average: Double): String = when {
        average <= .0001 -> "—"
        used >= average -> "%.1f".format(Locale.US, used / average).removeSuffix(".0") + "× more"
        else -> "${((1 - used / average) * 100).roundToInt()}% less"
    }
    fun coverage(c: Double?) = c?.let { "${(it * 100).roundToInt()}% of the day observed" }
    /** "48% of the limit", or "1.3× the limit" from one full limit up. */
    fun ofLimit(fraction: Double?): String = if (fraction != null && fraction >= MULTIPLE) "${of(fraction)} the limit" else "${of(fraction)} of the limit"
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
/** Also strips an account suffix: `claude:work` is a Claude account. */
fun providerKind(id: String) = when (val base = id.substringBefore(':')) { "anthropic_api", "anthropic-api" -> "anthropic-api"; "openai_api", "openai-api" -> "openai-api"; else -> base }
