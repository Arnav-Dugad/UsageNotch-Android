package io.github.arnavdugad.usagenotch

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

private const val HOUR = 3_600_000L

/** How long this window runs between resets, from its id or the desktop's label; null when it can't be told. */
fun UsageWindow.periodMillis(): Long? {
    val key = "$id $label".lowercase(Locale.ROOT)
    return when {
        "five_hour" in key || "5-hour" in key || "5 hour" in key || "5h " in "$key " -> 5 * HOUR
        "seven_day" in key || "week" in key || "7-day" in key || "7 day" in key -> 7 * 24 * HOUR
        "daily" in key || "24-hour" in key || "24 hour" in key -> 24 * HOUR
        id == "codex-primary" -> 5 * HOUR
        id == "codex-secondary" -> 7 * 24 * HOUR
        else -> null
    }
}

/** Share of the window's period still to run before its reset (1 = just reset, 0 = resetting now); null when unknown. */
fun UsageWindow.timeLeft(now: Long): Float? {
    val reset = reset ?: return null; val period = periodMillis() ?: return null
    val left = reset - now
    if (left <= 0) return 0f
    if (left > period * 1.02) return null // The reported reset doesn't fit the period; don't draw a wrong arc.
    return (left.toDouble() / period).toFloat().coerceIn(0f, 1f)
}

/** A usage budget for one window: stay at or under [limit]% used, optionally by a time of day ([byMinute] after midnight). */
data class Budget(val provider: String, val window: String, val limit: Int, val byMinute: Int?) {
    fun label(use24: Boolean): String = "$limit%" + (byMinute?.let { " by " + clock(it, use24) } ?: "")
    companion object { fun clock(minute: Int, use24: Boolean): String = if (use24) "%02d:%02d".format(Locale.ROOT, minute / 60, minute % 60) else "${(minute / 60).let { if (it % 12 == 0) 12 else it % 12 }}${if (minute % 60 != 0) ":%02d".format(Locale.ROOT, minute % 60) else ""} ${if (minute < 720) "AM" else "PM"}" }
}

object Budgets {
    private const val KEY = "budgets"
    fun all(prefs: SharedPreferences): List<Budget> = runCatching {
        val array = JSONArray(prefs.getString(KEY, "[]"))
        (0 until array.length()).map { i -> val o = array.getJSONObject(i)
            Budget(o.getString("provider"), o.getString("window"), o.getInt("limit").coerceIn(1, 100), if (o.has("by") && !o.isNull("by")) o.getInt("by").coerceIn(0, 1439) else null) }
    }.getOrDefault(emptyList())
    fun of(prefs: SharedPreferences, provider: String, window: String) = all(prefs).firstOrNull { it.provider == provider && it.window == window }
    fun set(prefs: SharedPreferences, budget: Budget) = save(prefs, all(prefs).filterNot { it.provider == budget.provider && it.window == budget.window } + budget)
    fun remove(prefs: SharedPreferences, provider: String, window: String) = save(prefs, all(prefs).filterNot { it.provider == provider && it.window == window })
    private fun save(prefs: SharedPreferences, list: List<Budget>) {
        prefs.edit().putString(KEY, JSONArray(list.map { JSONObject().put("provider", it.provider).put("window", it.window).put("limit", it.limit).put("by", it.byMinute ?: JSONObject.NULL) }).toString()).apply()
    }

    enum class State { Within, Near, Pace, Over }
    data class Status(val state: State, val text: String)

    /**
     * Where a window stands against its budget. Pace uses the desktop's forecast (percentage points per hour) to project
     * usage at the budget's time, or at the reset when the budget has no time.
     */
    fun status(b: Budget, w: UsageWindow, now: Long, use24: Boolean, zone: ZoneId = ZoneId.systemDefault()): Status {
        if (w.resetPassed(now)) return Status(State.Within, "Renewed · budget ${b.label(use24)}")
        val used = w.used * 100
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val deadline = b.byMinute?.let { today.atStartOfDay(zone).plusMinutes(it.toLong()).toInstant().toEpochMilli() }?.takeIf { it > now }
        val rate = w.forecast?.rate?.takeIf { it > 0.0001 }
        val projected = when {
            rate == null -> null
            deadline != null -> used + rate * (deadline - now) / HOUR
            b.byMinute == null -> w.forecast?.projected
            else -> null
        }
        val by = if (deadline != null) " by ${Budget.clock(b.byMinute!!, use24)}" else if (b.byMinute == null) " by the reset" else ""
        return when {
            used > b.limit -> Status(State.Over, "Over your ${b.limit}% budget")
            projected != null && projected > b.limit -> Status(State.Pace, "On pace for ${projected.roundToInt().coerceAtMost(999)}%$by")
            used >= b.limit - 10 -> Status(State.Near, "Near your ${b.limit}% budget")
            else -> Status(State.Within, "Within your ${b.label(use24)} budget")
        }
    }
}

/**
 * When to start a session so it renews in the middle of your usual busy hours, giving you a fresh limit when you
 * need it most. Busy hours come from the PC's 30-day weekday × hour history; only windows up to 12 hours long qualify.
 */
data class StartHint(val startAt: Long, val renewAt: Long, val busyFrom: Int, val busyTo: Int, val now: Boolean, val tomorrow: Boolean)

object BestStart {
    fun hint(p: Provider, now: Long, zone: ZoneId = ZoneId.systemDefault()): StartHint? {
        val s = p.sessionWindow() ?: return null
        val period = s.periodMillis()?.takeIf { it <= 12 * HOUR } ?: return null
        val h = p.history.firstOrNull { it.window == s.id } ?: return null
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val earliest = if (s.reset != null && !s.resetPassed(now)) s.reset else now
        for (offset in 0..1) {
            val day = today.plusDays(offset.toLong())
            val (from, to) = busyBlock(h, day.dayOfWeek.value % 7) ?: continue
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val busyStart = dayStart + from * HOUR; val busyEnd = dayStart + to * HOUR
            if (busyEnd <= now) continue
            val ideal = (busyStart + busyEnd) / 2 - period
            val start = maxOf(ideal, earliest, now)
            if (start + period >= busyEnd) continue // Would renew after the busy hours: no benefit.
            return StartHint(start, start + period, from, to, start - now < 5 * 60_000, offset == 1)
        }
        return null
    }
    /** The busiest run of hours (at least half of the day's peak) on a weekday (0 = Sunday), as [from, to) hours. */
    internal fun busyBlock(h: History, weekday: Int): Pair<Int, Int>? {
        val row = (0 until 24).map { hr -> if (h.observed.getOrElse(weekday * 24 + hr) { 0 } > 0) h.heat.getOrElse(weekday * 24 + hr) { 0.0 } else 0.0 }
        val peak = row.maxOrNull() ?: return null
        if (peak <= 0.005) return null
        var best: Triple<Int, Int, Double>? = null; var start = -1
        for (hr in 0..24) {
            val busy = hr < 24 && row[hr] >= peak / 2
            if (busy && start < 0) start = hr
            if (!busy && start >= 0) { val total = row.subList(start, hr).sum(); if (best == null || total > best.third) best = Triple(start, hr, total); start = -1 }
        }
        return best?.let { it.first to it.second }
    }
    fun hours(from: Int, to: Int, use24: Boolean) = Budget.clock(from * 60, use24) + "–" + Budget.clock((to % 24) * 60, use24)
}

/** One day in context: its usage, how it compares with the average, and its rank among recorded days. */
data class DayInsight(val date: LocalDate, val used: Double?, val average: Double?, val rank: Int?, val recorded: Int, val others: List<Triple<String, String, Double?>>)

object Days {
    fun insight(providers: List<Provider>, provider: String, window: String, date: String): DayInsight? {
        val p = providers.firstOrNull { it.id == provider } ?: return null
        val h = p.history.firstOrNull { it.window == window } ?: return null
        val days = h.calendar.ifEmpty { h.days }
        val day = days.firstOrNull { it.date == date } ?: return null
        val recorded = days.mapNotNull { it.used }
        val rank = day.used?.let { u -> recorded.count { it > u } + 1 }
        val others = providers.flatMap { q -> q.history.filter { !(q.id == provider && it.window == window) }.map { oh -> Triple(q.name, oh.label, oh.calendar.ifEmpty { oh.days }.firstOrNull { it.date == date }?.used) } }
            .filter { it.third != null }
        return DayInsight(LocalDate.parse(date), day.used, recorded.takeIf { it.isNotEmpty() }?.average(), rank, recorded.size, others)
    }
    fun long(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEEE, MMMM d", Locale.ENGLISH))
    fun short(date: LocalDate): String = date.format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH))
}

/** The Sunday-evening summary of the last seven days, from the history already on this phone. */
object Recap {
    data class Text(val title: String, val body: String)
    fun compose(snapshot: Snapshot?, today: LocalDate): Text? {
        val lines = mutableListOf<String>(); var busiest: Pair<LocalDate, Double>? = null; var streak = 0
        for (p in snapshot?.providers.orEmpty()) {
            val h = p.sessionWindow()?.let { s -> p.history.firstOrNull { it.window == s.id } } ?: p.history.firstOrNull() ?: continue
            val week = h.days.filter { runCatching { LocalDate.parse(it.date) }.getOrNull()?.let { d -> !d.isAfter(today) && d.isAfter(today.minusDays(7)) } == true }
            val used = week.mapNotNull { it.used }
            if (used.isEmpty()) continue
            lines += "${p.name} ${(used.sum() * 100).roundToInt()}%"
            week.filter { it.used != null }.maxByOrNull { it.used!! }?.let { d -> if (busiest == null || d.used!! > busiest!!.second) busiest = LocalDate.parse(d.date) to d.used!! }
            streak = maxOf(streak, h.streak)
        }
        if (lines.isEmpty()) return null
        val busy = busiest?.let { " · busiest ${it.first.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)}" } ?: ""
        return Text("Your week", lines.joinToString(" · ") + busy + if (streak > 1) " · $streak-day streak" else "")
    }
}
