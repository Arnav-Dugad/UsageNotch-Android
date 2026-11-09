package io.github.arnavdugad.usagenotch

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.StringWriter

/**
 * Windows 2.9 and Android 1.9: the usage score, typical weeks, unusual days, day notes and their sync, plan history,
 * token values and model mix, project budgets, and the chart morph.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class Windows29Test {
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")?.use { String(it.readBytes()) }
    private lateinit var context: Application

    @Before fun clean() {
        context = ApplicationProvider.getApplicationContext()
        LocalHistory.open(context) { it.clear() }
        context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun everyNewFieldDecodes() {
        val s = Snapshot.parse(resource("windows29-sample.json")!!)
        listOf("score", "notes", "plans", "tokens2").forEach { assertTrue(it, s.has(it)); assertTrue("this phone asks for $it", it in Snapshot.FEATURES.split(',')) }
        val score = s.score!!
        assertEquals(46, score.value); assertEquals(38, score.previous); assertFalse(score.onPhone)
        assertEquals("a row without an id is dropped", listOf("claude", "codex"), score.providers.map { it.id })
        assertEquals(ScoreRow("claude", "Claude", "seven_day", "All models", .71), score.providers[0])

        assertEquals("bad dates are dropped; text is one line", listOf(DayNote("2026-11-04", "Big refactor", 1790000000001), DayNote("2026-11-05", "", 1790000000002), DayNote("2026-11-06", "Line one line two end", 1790000000003)), s.notes)

        val r = s.report!!
        assertEquals(.34, r.coverage!!, 1e-9); assertTrue(r.note!!.startsWith("This PC saw 34%"))
        assertEquals("empty report notes are left out", listOf(DayNote("2026-11-04", "Big refactor")), r.notes)
        assertEquals(3.5, r.providers[0].typical!!, 1e-9); assertEquals(.2, r.providers[0].typicalChange!!, 1e-9)
        assertNull(r.providers[1].typical); assertNull(r.providers[1].typicalChange)
        assertEquals(listOf("change", "typical", "unusual", "efficiency", "value"), r.insights.map { it.kind })

        val p = s.providers.single()
        assertEquals("plans come oldest first; one without a name is dropped", listOf(PlanChange(1770000000000, "Pro"), PlanChange(1780000000000, "Max 5×")), p.plans)
        assertEquals("a damaged unusual day is dropped", listOf(UnusualDay("2026-11-04", 2.1, .45)), p.history.single().unusual)

        val claude = s.tokens!!.sources[0]
        assertEquals(TokenMonth("2026-11-01", 400_000_000, 412.3), claude.month)
        assertEquals(PlanPrice("Max 5×", 100.0), claude.plan)
        assertEquals(PerLimit(9_200_000, "session"), claude.perLimit)
        assertEquals(listOf(ModelWeek("2026-10-27", listOf("claude-opus-5-5" to 80L, "Other" to 20L)), ModelWeek("2026-11-03", listOf("claude-opus-5-5" to 120L))), claude.modelWeeks)
        assertEquals("budgets below 100k tokens are ignored", listOf(50_000_000L, null, null), claude.projects.map { it.budget })
        val codex = s.tokens!!.sources[1]
        assertNull("a negative price is ignored", codex.plan); assertNull("a bad month is ignored", codex.month); assertTrue(codex.modelWeeks.isEmpty())
    }

    @Test fun olderSnapshotsHaveNoneOfIt() {
        val old = Snapshot.parse(resource("phone-snapshot-v3-windows.json")!!)
        assertNull(old.score); assertTrue(old.notes.isEmpty())
        assertTrue(old.providers.all { p -> p.plans.isEmpty() && p.history.all { it.unusual.isEmpty() } })
        assertTrue(old.report?.providers.orEmpty().all { it.typical == null } && old.report?.note == null)
        // Older PCs still get a score worked out on this phone, the same way.
        val local = UsageScores.of(old)!!
        assertTrue(local.onPhone); assertNull(local.previous)
    }

    @Test fun theNewestWindowsFixtureDecodesWhenPresent() {
        val raw = resource("phone-snapshot-v4-windows.json") ?: return
        val s = Snapshot.parse(raw)
        assertTrue(s.has("score") && s.has("notes") && s.has("plans") && s.has("tokens2"))
        // Structure only: the PC regenerates this file, and its sample sentences may change.
        val score = s.score!!
        assertTrue(score.value in 0..100 && (score.previous == null || score.previous in 0..100) && score.providers.isNotEmpty())
        assertTrue(s.notes.isNotEmpty() && s.notes.all { it.date.length == 10 && it.at > 0 && it.text.length <= DayNotes.MAX })
        assertTrue(s.providers.any { it.plans.isNotEmpty() } && s.providers.all { p -> p.plans.zipWithNext().all { (a, b) -> a.at <= b.at } })
        val r = s.report!!
        assertTrue(r.headline.isNotBlank() && r.insights.size <= 8 && (r.coverage == null || r.coverage!! in 0.0..1.0))
        assertTrue(s.tokens!!.sources.isNotEmpty())
        // Merging the PC's notes into this phone keeps every note, deletions included.
        LocalHistory.open(context) { db -> db.mergeNotes(s.notes); assertEquals(s.notes.map { it.date }.toSet(), db.allNotes().keys); assertEquals(s.notes.filter { it.text.isNotEmpty() }.associate { it.date to it.text }, db.notes()) }
    }

    @Test fun scoreIsTheAverageOfWeeklyOrMainLimits() {
        fun w(id: String, used: Double) = UsageWindow(id, id, used, 1L, null, emptyList())
        val providers = listOf(
            Provider("claude", "Claude", "Ok", listOf(w("five_hour", .9), w("seven_day", .4)), session = "five_hour", weekly = "seven_day"),
            Provider("codex", "Codex", "Ok", listOf(w("codex-primary", .1), w("codex-secondary", .6))),
            Provider("gemini", "Gemini", "Ok", listOf(w("gemini-pro", .2), w("gemini-flash", .9))),
            Provider("cursor", "Cursor", "NeedsAuth", listOf(w("plan", 1.0))))
        val score = UsageScores.local(providers)!!
        assertEquals("weekly for Claude and Codex, the main limit for Gemini, no signed-out provider", listOf("seven_day", "codex-secondary", "gemini-pro"), score.providers.map { it.window })
        assertEquals(40, score.value)
        assertNull(UsageScores.local(emptyList()))
        assertEquals("▲ 8 from 38 last week", UsageScores.trend(UsageScore(46, 38, emptyList())))
        assertEquals("▼ 5 from 51 last week", UsageScores.trend(UsageScore(46, 51, emptyList())))
        assertEquals("Same as last week (46)", UsageScores.trend(UsageScore(46, 46, emptyList())))
        assertEquals("Last week's score appears after a full week of readings.", UsageScores.trend(UsageScore(46, null, emptyList())))
        assertEquals(listOf("Near your limits", "Busy", "Moderate", "Light"), listOf(80, 50, 20, 19).map { UsageScores.word(it) })
        // Half away from zero, as on Windows.
        fun one(used: Double) = Provider("x", "X", "Ok", listOf(w("main", used)))
        assertEquals(51, UsageScores.local(listOf(one(.505), one(.505)))!!.value)
    }

    @Test fun notesAreOneShortLine() {
        assertEquals("a b c", DayNotes.clean("  a\nb\t\tc "))
        assertEquals(140, DayNotes.clean("x".repeat(300)).length)
        assertEquals("", DayNotes.clean(" \n "))
    }

    @Test fun notesMergeLastWriterWinsAndTiesGoToThePc() {
        val mine = mapOf(
            "2026-11-01" to StoredNote("2026-11-01", "mine newer", 200, pending = true),
            "2026-11-02" to StoredNote("2026-11-02", "mine older", 100, pending = true),
            "2026-11-03" to StoredNote("2026-11-03", "tie mine", 300, pending = true))
        val (merged, changed) = DayNotes.merge(mine, listOf(
            DayNote("2026-11-01", "pc older", 150), DayNote("2026-11-02", "pc newer", 150), DayNote("2026-11-03", "tie pc", 300), DayNote("2026-11-04", "", 50)))
        assertEquals(StoredNote("2026-11-01", "mine newer", 200, pending = true), merged["2026-11-01"])
        assertEquals(StoredNote("2026-11-02", "pc newer", 150, pending = false), merged["2026-11-02"])
        assertEquals("on a tie the PC's copy wins", StoredNote("2026-11-03", "tie pc", 300, pending = false), merged["2026-11-03"])
        assertEquals("a deletion from the PC is kept as a tombstone", "", merged["2026-11-04"]!!.text)
        assertEquals(setOf("2026-11-02", "2026-11-03", "2026-11-04"), changed)
        assertEquals("a change never steps back behind a PC clock that runs ahead", 301L, DayNotes.stamp(StoredNote("d", "x", 300), 10))
    }

    @Test fun notesPayloadStaysUnderTheLimitAndAnswersParse() {
        assertNull(DayNotes.payload(emptyList()))
        val many = (1..900).map { StoredNote("2026-%02d-%02d".format(1 + it % 12, 1 + it % 28), "n".repeat(140), it.toLong(), true) }
        val body = DayNotes.payload(many)!!
        assertTrue(body.toByteArray().size <= DayNotes.MAX_BODY)
        val first = JSONObject(body).getJSONArray("notes").getJSONObject(0)
        assertEquals("newest first", 900L, first.getLong("at"))
        val answer = """{"notes":[{"date":"2026-11-04","text":"Big refactor","at":5},{"date":"bad","text":"x","at":1},{"date":"2026-11-05","text":"","at":6}]}"""
        assertEquals(listOf(DayNote("2026-11-04", "Big refactor", 5), DayNote("2026-11-05", "", 6)), DayNotes.parseResponse(answer))
    }

    @Test fun notesAreStoredSyncedExportedAndDeleted() {
        LocalHistory.open(context) { db ->
            db.setNote("2026-11-04", "Big refactor", 1_000)
            assertEquals(mapOf("2026-11-04" to "Big refactor"), db.notes())
            assertEquals(1, db.pendingNotes().size)
            // The PC echoes it back: no longer pending.
            db.mergeNotes(listOf(DayNote("2026-11-04", "Big refactor", 1_000)))
            assertTrue(db.pendingNotes().isEmpty())
            // Deleting here keeps a pending tombstone, so the deletion reaches the PC.
            db.setNote("2026-11-04", " ", 2_000)
            assertTrue(db.notes().isEmpty()); assertEquals("", db.pendingNotes().single().text)
            db.setNote("2026-11-05", "Launch, \"v2\"", 3_000)
            val out = StringWriter(); db.exportCsv(out)
            assertTrue(out.toString().endsWith("\ndate,note\n2026-11-05,\"Launch, \"\"v2\"\"\"\n"))
            db.clear(); assertTrue(db.allNotes().isEmpty())
        }
    }

    @Test fun projectBudgetsAreValidatedAndSynced() {
        assertTrue(ProjectBudgets.validKey("projectBudget:claude|UsageNotch Windows"))
        assertFalse(ProjectBudgets.validKey("projectBudget:claude|"))
        assertFalse(ProjectBudgets.validKey("projectBudget:|x"))
        assertFalse(ProjectBudgets.validKey("projectBudget:claude|a|b"))
        assertFalse(ProjectBudgets.validKey("projectBudget:clau de|x"))
        val parsed = SharedSettings.parseEntries(JSONObject("""{
            "projectBudget:claude|Web": {"v": {"tokens": 50000000}, "at": 5},
            "projectBudget:claude|Small": {"v": {"tokens": 10}, "at": 5},
            "projectBudget:claude|Gone": {"v": null, "at": 6}}"""))
        assertEquals(setOf("projectBudget:claude|Web", "projectBudget:claude|Gone"), parsed.keys)
        val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)
        assertEquals("the PC's budget until this phone sets one", 7_000_000L, ProjectBudgets.of(prefs, "claude", "Web", 7_000_000))
        ProjectBudgets.set(prefs, "claude", "Web", 25_000_000)
        assertEquals(25_000_000L, ProjectBudgets.of(prefs, "claude", "Web", 7_000_000))
        assertTrue("projectBudget:claude|Web" in SharedSettings.pending(prefs))
        ProjectBudgets.set(prefs, "claude", "Web", null)
        assertNull("removed here, even though the PC still sent one", ProjectBudgets.of(prefs, "claude", "Web", 7_000_000))
        assertEquals(1.2, ProjectBudgets.progress(60, 50), 1e-9)
    }

    @Test fun tokenValueReadsPlainly() {
        assertEquals("4.1× its price", valueRatio(412.3, 100.0))
        assertEquals("38% of its price", valueRatio(38.0, 100.0))
        assertEquals("at API prices in November · your Max 5× plan is $100 a month (4.1× its price)", monthValue(TokenMonth("2026-11-01", 1, 412.3), PlanPrice("Max 5×", 100.0)))
        assertEquals("tokens in November", monthValue(TokenMonth("2026-11-01", 1, null), null))
        assertEquals(50_000_000L, parseMillions("50")); assertEquals(100_000L, parseMillions("0.1")); assertEquals(1_000_000_000_000L, parseMillions("1,000,000"))
        assertNull(parseMillions("0.05")); assertNull(parseMillions("abc")); assertNull(parseMillions("2000000"))
        assertEquals("2.5", millions(2_500_000)); assertEquals("50", millions(50_000_000))
    }

    @Test fun chartMorphSlidesKeptDaysAndGrowsNewOnes() {
        val old = listOf(Day("2026-11-08", .5), Day("2026-11-09", 1.0))
        val new = listOf(Day("2026-11-07", .25), Day("2026-11-08", .5), Day("2026-11-09", 1.0))
        val from = ChartMorph.layout(old, 1.0, 210f, 10f); val to = ChartMorph.layout(new, 1.0, 210f, 10f)
        assertEquals(100f, from[0].width, 1e-4f); assertEquals(110f, from[1].left, 1e-4f)
        assertEquals(63.3333f, to[0].width, 1e-3f)
        val start = ChartMorph.frame(from, to, 0f).associateBy { it.date }
        assertEquals("a new day starts flat and clear", 0f, start.getValue("2026-11-07").height); assertEquals(0f, start.getValue("2026-11-07").alpha)
        assertEquals("a kept day starts where it was", 110f, start.getValue("2026-11-09").left, 1e-4f)
        val end = ChartMorph.frame(from, to, 1f).associateBy { it.date }
        assertEquals(to[2].left, end.getValue("2026-11-09").left, 1e-4f); assertEquals(.25f, end.getValue("2026-11-07").height, 1e-4f)
        val back = ChartMorph.frame(to, from, 1f).associateBy { it.date }
        assertEquals("a day that leaves shrinks and fades", 0f, back.getValue("2026-11-07").alpha)
        assertTrue(ChartMorph.layout(emptyList(), 1.0, 100f, 2f).isEmpty())
        assertEquals("a day without readings draws nothing", 0f, ChartMorph.layout(listOf(Day("2026-11-09", null)), 1.0, 100f, 2f)[0].alpha)
    }
}
