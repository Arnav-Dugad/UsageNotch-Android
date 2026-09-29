package io.github.arnavdugad.usagenotch

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Window periods, the time-left arc, budgets, the best time to start, the weekly recap and budget alerts. */
class PlanningTest {
    private val zone: ZoneId = ZoneId.of("Asia/Kolkata")
    private val hour = 3_600_000L
    /** Tuesday, September 29, 2026 at the given local time. */
    private fun at(h: Int, m: Int = 0) = ZonedDateTime.of(2026, 9, 29, h, m, 0, 0, zone).toInstant().toEpochMilli()
    private fun window(id: String, label: String, used: Double, now: Long, reset: Long?, rate: Double? = null, projected: Double? = null) =
        UsageWindow(id, label, used, now, reset, emptyList(), forecast = rate?.let { Forecast("", it, projected, null, "Consistent pace") })

    @Test fun periodsComeFromIdsAndDesktopLabels() {
        assertEquals(5 * hour, window("five_hour", "Current session", .1, 0, null).periodMillis())
        assertEquals(168 * hour, window("seven_day", "All models", .1, 0, null).periodMillis())
        assertEquals(5 * hour, window("codex-primary", "5-hour window", .1, 0, null).periodMillis())
        assertEquals(168 * hour, window("gpt-reserve-secondary", "gpt-reserve · Weekly window", .1, 0, null).periodMillis())
        assertEquals(5 * hour, window("codex-primary", "Primary window", .1, 0, null).periodMillis())
        assertNull(window("gemini-2.5-pro", "Gemini 2.5 Pro", .1, 0, null).periodMillis())
    }

    @Test fun timeLeftArcNeverShowsAnImpossibleValue() {
        val now = at(10)
        assertEquals(.5f, window("five_hour", "Current session", .1, now, now + 150 * 60_000L).timeLeft(now)!!, .001f)
        assertEquals(0f, window("five_hour", "Current session", .1, now, now - 1).timeLeft(now)!!, 0f)
        assertNull("a reset further away than the period is inconsistent", window("five_hour", "Current session", .1, now, now + 6 * hour).timeLeft(now))
        assertNull(window("five_hour", "Current session", .1, now, null).timeLeft(now))
    }

    @Test fun budgetStates() {
        val now = at(14)
        val b = Budget("claude", "five_hour", 60, 18 * 60)
        assertEquals(Budgets.State.Over, Budgets.status(b, window("five_hour", "S", .65, now, now + 2 * hour), now, false, zone).state)
        // 40% now, +10 points an hour, 4 hours to 6 PM: 80% by then.
        val pace = Budgets.status(b, window("five_hour", "S", .40, now, now + 2 * hour, rate = 10.0), now, false, zone)
        assertEquals(Budgets.State.Pace, pace.state); assertEquals("On pace for 80% by 6 PM", pace.text)
        assertEquals(Budgets.State.Near, Budgets.status(b, window("five_hour", "S", .55, now, now + 2 * hour), now, false, zone).state)
        assertEquals(Budgets.State.Within, Budgets.status(b, window("five_hour", "S", .20, now, now + 2 * hour, rate = 1.0), now, false, zone).state)
        // Without a time, pace is judged at the reset from the PC's projection.
        val atReset = Budgets.status(b.copy(byMinute = null), window("five_hour", "S", .30, now, now + 2 * hour, rate = 20.0, projected = 70.0), now, false, zone)
        assertEquals("On pace for 70% by the reset", atReset.text)
        // After 6 PM, a "by 6 PM" budget only compares the current reading.
        assertEquals(Budgets.State.Within, Budgets.status(b, window("five_hour", "S", .30, at(19), at(20), rate = 50.0), at(19), false, zone).state)
        assertEquals("60% by 18:00", b.label(true)); assertEquals("60% by 6 PM", b.label(false))
    }

    private fun history(busyFrom: Int, busyTo: Int, weekday: Int): History {
        val heat = MutableList(168) { 0.0 }; val observed = MutableList(168) { 1 }
        for (h in busyFrom until busyTo) heat[weekday * 24 + h] = .2
        heat[weekday * 24 + 9] = .02
        return History("five_hour", "Current session", emptyList(), heat, observed, 0)
    }

    @Test fun bestStartRenewsInTheMiddleOfBusyHours() {
        // Tuesday is weekday 2. Busy 2 PM to 7 PM: start at 11:30 AM so the 5-hour session renews at 4:30 PM.
        val now = at(9)
        val p = Provider("claude", "Claude", "Ok", listOf(window("five_hour", "Current session", .1, now, now - 1)), session = "five_hour", history = listOf(history(14, 19, 2)))
        val hint = BestStart.hint(p, now, zone)!!
        assertEquals(at(11, 30), hint.startAt); assertEquals(at(16, 30), hint.renewAt)
        assertEquals(14 to 19, hint.busyFrom to hint.busyTo); assertFalse(hint.now); assertFalse(hint.tomorrow)
        // An active session can't restart before its reset.
        val active = p.copy(windows = listOf(window("five_hour", "Current session", .5, now, at(12))))
        assertEquals(at(12), BestStart.hint(active, now, zone)!!.startAt)
        // Starting now when the ideal time has passed but the renewal still lands in the busy hours.
        assertTrue(BestStart.hint(p, at(12), zone)!!.now)
        // No history, no hint; weekly windows are too long to plan this way.
        assertNull(BestStart.hint(p.copy(history = emptyList()), now, zone))
        assertNull(BestStart.hint(p.copy(windows = listOf(window("seven_day", "All models", .1, now, null)), session = "seven_day", history = listOf(history(14, 19, 2).copy(window = "seven_day"))), now, zone))
    }

    @Test fun busyBlockIgnoresUnobservedHours() {
        val h = history(14, 19, 2)
        assertEquals(14 to 19, BestStart.busyBlock(h, 2))
        assertNull(BestStart.busyBlock(h, 3))
        assertEquals("2 PM–7 PM", BestStart.hours(14, 19, false))
    }

    @Test fun weeklyRecapRunsSundayEvening() {
        assertEquals(ZonedDateTime.of(2026, 10, 4, 19, 0, 0, 0, zone).toInstant().toEpochMilli(), WeeklyRecap.nextAt(at(10), zone))
        val sundayEvening = ZonedDateTime.of(2026, 10, 4, 19, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(ZonedDateTime.of(2026, 10, 11, 19, 0, 0, 0, zone).toInstant().toEpochMilli(), WeeklyRecap.nextAt(sundayEvening, zone))
    }

    @Test fun recapSummarisesTheLastSevenDays() {
        val today = LocalDate.of(2026, 9, 29)
        val days = (0 until 30).map { back -> val d = today.minusDays(back.toLong()); Day(d.toString(), if (back == 2) .8 else if (back > 6) 5.0 else .1) }.reversed()
        val h = History("five_hour", "Current session", days, List(168) { 0.0 }, List(168) { 0 }, 4)
        val s = Snapshot(0, listOf(Provider("claude", "Claude", "Ok", listOf(window("five_hour", "S", .1, 0, null)), session = "five_hour", history = listOf(h))))
        val text = Recap.compose(s, today)!!
        // Six days at 10% and one at 80%, older days excluded; the busiest was Sunday the 27th.
        assertEquals("Claude 140% · busiest Sunday · 4-day streak", text.body)
        assertNull(Recap.compose(Snapshot(0, emptyList()), today))
    }

    @Test fun budgetAlertsFireOnceForPaceAndOnceForOver() {
        val now = at(14); val reset = now + 3 * hour
        val b = Budget("claude", "five_hour", 60, 18 * 60)
        fun snap(used: Double, rate: Double?) = Snapshot(now, listOf(Provider("claude", "Claude", "Ok", listOf(window("five_hour", "Current session", used, now, reset, rate)))))
        val pace = UsageAlerts.due(snap(.40, 10.0), emptySet(), now, false, listOf(b), zone).single()
        assertTrue(pace.key, pace.key.endsWith("|pace|2026-09-29")); assertEquals("Claude: on pace for 80% by 6 PM", pace.title)
        assertTrue(UsageAlerts.due(snap(.40, 10.0), setOf(pace.key), now, false, listOf(b), zone).isEmpty())
        val over = UsageAlerts.due(snap(.65, null), setOf(pace.key), now, false, listOf(b), zone).single()
        assertEquals("Claude: over your 60% budget", over.title)
        assertTrue(UsageAlerts.due(snap(.66, null), setOf(pace.key, over.key), now, false, listOf(b), zone).isEmpty())
    }

    @Test fun dayInsightRanksAgainstRecordedDays() {
        val days = listOf(Day("2026-09-27", .8), Day("2026-09-28", null), Day("2026-09-29", .2))
        val h = History("five_hour", "Current session", days, List(168) { 0.0 }, List(168) { 0 }, 1)
        val other = History("codex-primary", "5-hour window", listOf(Day("2026-09-27", .3)), List(168) { 0.0 }, List(168) { 0 }, 0)
        val providers = listOf(Provider("claude", "Claude", "Ok", emptyList(), history = listOf(h)), Provider("codex", "Codex", "Ok", emptyList(), history = listOf(other)))
        val insight = Days.insight(providers, "claude", "five_hour", "2026-09-27")!!
        assertEquals(1, insight.rank); assertEquals(2, insight.recorded); assertEquals(.5, insight.average!!, 1e-9)
        assertEquals(listOf(Triple("Codex", "5-hour window", .3)), insight.others)
        assertNull(Days.insight(providers, "claude", "five_hour", "2026-01-01"))
    }
}
