package io.github.arnavdugad.usagenotch

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringWriter
import java.time.LocalDate
import java.time.ZoneId

/** Windows 2.8's "This week" and all-time totals, forecast ranges, and the history this phone keeps. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class WeekAndAllTimeTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")?.use { String(it.readBytes()) }
    private val hour = 3_600_000L
    private lateinit var context: Application

    @Before fun clean() {
        context = ApplicationProvider.getApplicationContext()
        LocalHistory.open(context) { it.clear() }
    }

    @Test fun reportAndAllTimeDecode() {
        val s = Snapshot.parse(resource("report-alltime-sample.json")!!)
        assertTrue(s.has("report") && s.has("alltime"))
        val r = s.report!!
        assertEquals("2026-11-03", r.from); assertEquals("2026-11-09", r.to); assertEquals(1_790_000_000_000L, r.generatedAt)
        assertEquals("A busier week than the last", r.headline); assertEquals("Nov 3 – Nov 9 · 6 active days", r.summary)
        assertEquals("a row without an id is dropped, never the report", listOf("claude", "codex"), r.providers.map { it.id })
        val claude = r.providers[0]
        assertEquals(4.2, claude.used!!, 1e-9); assertEquals(3.1, claude.previous!!, 1e-9); assertEquals(.3548, claude.change!!, 1e-9)
        assertEquals(6, claude.activeDays); assertEquals(2, claude.hits); assertEquals("2026-11-04", claude.busiestDay); assertEquals(1.3, claude.busiestUsed!!, 1e-9); assertEquals(12, claude.streak)
        assertEquals("Current session", claude.label); assertEquals("five_hour", claude.window)
        val codex = r.providers[1]
        assertNull(codex.previous); assertNull(codex.change); assertNull(codex.busiestDay); assertNull(codex.busiestUsed); assertEquals(0, codex.hits)
        assertEquals("at most 8 sentences (Windows 2.9); blank ones are dropped", 8, r.insights.size)
        assertEquals("You used Claude 35% more than the week before.", r.insights[0].text); assertEquals("change", r.insights[0].kind); assertEquals("claude", r.insights[0].provider)
        assertNull("a JSON null provider is not the text \"null\"", r.insights[1].provider)
        assertEquals("Claude Code used 48.2M tokens this week (about $41.90 at API prices).", r.insights[6].text)
        val all = s.providers.single().history.single().allTime!!
        assertEquals(AllTime(1_700_000_000_000L, 37.4, 120, 131, 14, "2026-10-02", 3.2), all)
        assertFalse(all.onPhone)
    }

    @Test fun olderSnapshotsHaveNeitherAndDamagedOnesAreDropped() {
        val old = Snapshot.parse(resource("phone-snapshot-v2-fixture.json")!!)
        assertNull(old.report); assertTrue(old.providers.flatMap { it.history }.all { it.allTime == null })
        val raw = resource("report-alltime-sample.json")!!
        val bad = Snapshot.parse(raw.replace("\"from\": \"2026-11-03\"", "\"from\": \"last week\"").replace("\"since\": 1700000000000", "\"since\": -5"))
        assertNull(bad.report); assertNull(bad.providers.single().history.single().allTime)
        assertEquals("the rest of the snapshot still reads", 2, bad.providers.single().history.single().days.size)
        assertTrue(Snapshot.FEATURES.split(',').containsAll(listOf("report", "alltime")))
    }

    @Test fun windowsTwoEightFixtureDecodesWhenPresent() {
        val raw = resource("phone-snapshot-v3-windows.json")
        assertNotNull("phone-snapshot-v3-windows.json comes from the Windows diagnostics (Diagnostics/Artifacts/Report)", raw)
        val s = Snapshot.parse(raw!!)
        assertTrue(s.has("report") && s.has("alltime"))
        val r = s.report!!
        assertEquals("2026-11-03", r.from); assertEquals("2026-11-09", r.to)
        assertEquals("A busier week than the last", r.headline); assertEquals("Nov 3 – Nov 9 · 6 active days", r.summary)
        val claude = r.providers.single()
        assertEquals("claude", claude.id); assertEquals(3.25, claude.used!!, 1e-9); assertEquals(2.25, claude.previous!!, 1e-9); assertEquals(.4444, claude.change!!, 1e-9)
        assertEquals(6, claude.activeDays); assertEquals(1, claude.hits); assertEquals("2026-11-06", claude.busiestDay); assertEquals(14, claude.streak)
        assertEquals(listOf("change", "hits", "busiest", "hours", "streak"), r.insights.map { it.kind })
        assertEquals("You used Claude 44% more than the week before.", r.insights[0].text)
        assertEquals("▲ 44%", changeText(claude.change))
        val histories = s.providers.single { it.id == "claude" }.history
        assertEquals(AllTime(1_792_989_000_000L, 5.8, 14, 14, 1, "2026-11-06", 1.0), histories.single { it.window == "five_hour" }.allTime)
        assertEquals(AllTime(1_792_989_000_000L, 0.0, 0, 14, 0, null, 0.0), histories.single { it.window == "seven_day" }.allTime)
        assertEquals(90, histories[0].range(90).size)
        // Every day it carries can be saved on the phone.
        LocalHistory.open(context) { db -> assertTrue(db.save(s) >= 90); assertTrue(db.stats().days > 0) }
    }

    @Test fun forecastRangesFollowTheProtocol() {
        val now = 1_790_000_000_000L
        fun f(low: Double?, high: Double?) = Forecast("Estimated limit", 12.0, 140.0, now + 4 * hour, "Consistent pace", low, high)
        val t = { at: Long -> ClockText.time(at, false) }
        // 40% used: at 20 points an hour the limit is 3 hours away, at 8 points 7.5 hours.
        assertEquals("likely " + PaceRange.span(now + 3 * hour, now + 15 * hour / 2, false), PaceRange.of(f(8.0, 20.0), .4, now + 10 * hour, now, false))
        assertEquals(now + 3 * hour to now + 15 * hour / 2, PaceRange.bounds(f(8.0, 20.0), .4, now + 10 * hour, now))
        assertEquals("a zero low rate never arrives", "as early as ${t(now + 3 * hour)}", PaceRange.of(f(0.0, 20.0), .4, now + 10 * hour, now, false))
        assertEquals("the late end after the reset", "as early as ${t(now + 3 * hour)}", PaceRange.of(f(8.0, 20.0), .4, now + 5 * hour, now, false))
        assertNull("the early end after the reset", PaceRange.of(f(8.0, 20.0), .4, now + 2 * hour, now, false))
        assertNull("under 10 minutes apart", PaceRange.of(f(19.9, 20.0), .4, now + 10 * hour, now, false))
        assertNull("no high rate", PaceRange.of(f(8.0, null), .4, now + 10 * hour, now, false))
        assertNull("no limit time", PaceRange.of(f(8.0, 20.0).copy(limitAt = null), .4, now + 10 * hour, now, false))
        assertNull("no reset", PaceRange.of(f(8.0, 20.0), .4, null, now, false))
        assertNull("already at the limit", PaceRange.of(f(8.0, 20.0), 1.0, now + 10 * hour, now, false))
        // The card line carries the range after the limit time.
        val line = forecastLine(f(8.0, 20.0), now, false, .4, now + 10 * hour)!!
        assertEquals("At this pace: limit around ${t(now + 4 * hour)} (likely ${PaceRange.span(now + 3 * hour, now + 15 * hour / 2, false)})", line)
        assertEquals("At this pace: limit around ${t(now + 4 * hour)}", forecastLine(f(8.0, 20.0), now, false))
        assertTrue(forecastLine(f(8.0, 20.0), now, true, .4, now + 10 * hour)!!.contains(ClockText.time(now + 3 * hour, true) + "–" + ClockText.time(now + 15 * hour / 2, true)))
    }

    @Test fun spansShareTheirSuffix() {
        val zone = ZoneId.systemDefault()
        fun at(h: Int, m: Int) = LocalDate.of(2026, 11, 9).atTime(h, m).atZone(zone).toInstant().toEpochMilli()
        assertEquals("3:40–4:50 PM", PaceRange.span(at(15, 40), at(16, 50), false))
        assertEquals("11:50 AM–12:30 PM", PaceRange.span(at(11, 50), at(12, 30), false))
        assertEquals("15:40–16:50", PaceRange.span(at(15, 40), at(16, 50), true))
    }

    @Test fun changesReadPlainly() {
        assertEquals("▲ 35%", changeText(.3548)); assertEquals("▼ 12%", changeText(-.12)); assertEquals("steady", changeText(.05)); assertEquals("steady", changeText(-.099))
        assertEquals("▲ 4.2×", changeText(3.2)); assertNull(changeText(null))
    }

    @Test fun phoneHistoryKeepsEveryDayUntilDeleted() {
        val s = Snapshot.parse(resource("report-alltime-sample.json")!!)
        val rows = LocalHistory.rows(s)
        assertEquals("days and calendar share dates", listOf("2026-11-07", "2026-11-08", "2026-11-09"), rows.map { it.date }.sorted())
        LocalHistory.open(context) { db ->
            assertEquals(3, db.save(s, 1L))
            assertEquals(StoredStats(2, 2, "2026-11-08"), db.stats())
            // A later snapshot: a newer value wins, "no readings" never erases a saved day, and new days are added.
            val later = s.copy(providers = s.providers.map { p -> p.copy(history = p.history.map { h -> h.copy(days = emptyList(),
                calendar = listOf(Day("2026-11-08", null), Day("2026-11-09", .75, 1.0), Day("2026-11-10", .1, .2))) }) })
            db.save(later, 2L)
            val days = db.load().days("claude", "five_hour")
            assertEquals(listOf(Day("2026-11-07", null), Day("2026-11-08", .5, .8), Day("2026-11-09", .75, 1.0), Day("2026-11-10", .1, .2)), days)
            assertEquals(listOf(StoredSeries("claude", "five_hour", "Claude", "Current session")), db.load().series)
            val csv = StringWriter().also { db.exportCsv(it) }.toString()
            assertEquals("provider,window,date,used,coverage\nclaude,five_hour,2026-11-07,,\nclaude,five_hour,2026-11-08,0.5,0.8\nclaude,five_hour,2026-11-09,0.75,1\nclaude,five_hour,2026-11-10,0.1,0.2\n", csv)
            db.clear()
            assertEquals(StoredStats(0, 0, null), db.stats()); assertTrue(db.load().isEmpty)
        }
        assertTrue(LocalHistory.shouldWrite(LocalHistory.Existing.None, StoredDay("a", "b", "2026-01-01", null, null)))
        assertFalse(LocalHistory.shouldWrite(LocalHistory.Existing.Value, StoredDay("a", "b", "2026-01-01", null, null)))
        assertEquals("claude:work,\"odd,window\",2026-01-01,0.1234,\n", LocalHistory.csvRow(StoredDay("claude:work", "odd,window", "2026-01-01", .12341, null)))
    }

    @Test fun savedDaysExtendTheSnapshotAndCountAllTime() {
        val saved = listOf(Day("2026-01-01", 1.5), Day("2026-01-03", .25), Day("2026-01-05", .5))
        val merged = LocalHistory.merge(saved, listOf(Day("2026-01-05", null), Day("2026-01-06", .3, .9)))
        assertEquals(listOf("2026-01-01", "2026-01-02", "2026-01-03", "2026-01-04", "2026-01-05", "2026-01-06"), merged.map { it.date })
        assertNull("missing dates have no data", merged[1].used)
        assertEquals("a live day without readings keeps the saved value", .5, merged[4].used!!, 1e-9)
        assertEquals(.3, merged[5].used!!, 1e-9)
        val all = LocalHistory.allTime(merged, ZoneId.of("UTC"))!!
        assertEquals(LocalDate.of(2026, 1, 1).atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli(), all.since)
        assertEquals(2.55, all.used, 1e-9); assertEquals(4, all.activeDays); assertEquals(4, all.recordedDays); assertNull(all.hits)
        assertEquals("2026-01-01", all.bestDay); assertEquals(1.5, all.bestUsed, 1e-9); assertTrue(all.onPhone)
        assertNull(LocalHistory.allTime(listOf(Day("2026-01-01", null))))
        assertEquals(3, LocalHistory.streak(listOf(Day("2026-01-04", .1), Day("2026-01-05", .2), Day("2026-01-06", .3)), LocalDate.of(2026, 1, 7)))
        assertEquals(0, LocalHistory.streak(listOf(Day("2026-01-04", .1)), LocalDate.of(2026, 1, 7)))
    }

    @Test fun enrichBringsBackWindowsThePcNoLongerSends() {
        val stored = StoredHistory(listOf(StoredSeries("claude", "five_hour", "Claude", "Current session"), StoredSeries("gemini", "pro", "Gemini", "Gemini Pro")),
            mapOf(("claude" to "five_hour") to listOf(Day("2026-01-01", .4)), ("gemini" to "pro") to listOf(Day("2026-01-02", .2))))
        val live = listOf(Provider("claude", "Claude", "Ok", emptyList(), history = listOf(History("five_hour", "Current session", listOf(Day("2026-01-03", .1)), List(168) { 0.0 }, List(168) { 0 }, 1))),
            Provider("codex", "Codex", "Ok", emptyList()))
        val out = LocalHistory.enrich(live, stored)
        assertEquals(listOf("claude", "codex", "gemini"), out.map { it.id })
        assertEquals(listOf("2026-01-01", "2026-01-02", "2026-01-03"), out[0].history.single().calendar.map { it.date })
        assertTrue(out[1].history.isEmpty())
        assertEquals("Stale", out[2].status); assertEquals("Gemini Pro", out[2].history.single().label)
        assertSame("nothing saved changes nothing", live, LocalHistory.enrich(live, StoredHistory.Empty))
        assertEquals(listOf(.4, null, .1), out[0].history.single().range(90).map { it.used })
    }

    @Test fun everyProviderLogoInflatesAndDraws() {
        // Android's path parser rejects packed arc flags ("0 013.046"), which crashed the Cursor mark; every logo must load.
        for (id in listOf("claude", "anthropic_api", "codex", "codex:work", "openai_api", "gemini", "cursor", "something-else")) {
            val d = Logos.drawable(context, id, 0xFFFFFFFF.toInt())
            assertNotNull(id, d)
            val bitmap = android.graphics.Bitmap.createBitmap(48, 48, android.graphics.Bitmap.Config.ARGB_8888)
            d!!.setBounds(0, 0, 48, 48); d.draw(android.graphics.Canvas(bitmap))
        }
        assertTrue("Gemini keeps its own colours", Logos.colored("gemini"))
    }

    @Test fun whatsNewShowsOnceAfterAnUpdate() {
        assertTrue(WhatsNew.shouldShow(seen = 11, current = 12, freshInstall = false))
        assertFalse("fresh installs skip it", WhatsNew.shouldShow(seen = 0, current = 12, freshInstall = true))
        assertFalse("already read", WhatsNew.shouldShow(seen = 12, current = 12, freshInstall = false))
        assertTrue(WhatsNew.items.size in 3..7)
        assertEquals(12, BuildConfig.VERSION_CODE); assertEquals("1.9.0", BuildConfig.VERSION_NAME)
    }
}
