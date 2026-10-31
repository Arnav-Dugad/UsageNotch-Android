package io.github.arnavdugad.usagenotch

import org.junit.Assert.*
import org.junit.Test

/** Protocol 2 (UsageNotch for Windows 2.6, docs/PHONE-PROTOCOL.md in the Windows repository) on the phone. */
class ProtocolTwoTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")!!.use { String(it.readBytes()) }
    private val hour = 3_600_000L

    @Test fun protocolTwoSnapshotParses() {
        val s = Snapshot.parse(resource("phone-snapshot-v2-fixture.json"))
        assertEquals(2, s.protocol)
        assertTrue(s.has("refresh") && s.has("settings") && s.has("tokens"))
        assertTrue("unknown features are kept harmlessly", s.has("future-thing"))
        val claude = s.providers.first { it.id == "claude" }
        val h = claude.history.single()
        assertEquals(3, h.trend.size); assertEquals(.42, h.trend.last().used, 1e-9)
        assertEquals(listOf(.54, 1.0), h.unused.map { it.peak })
        assertEquals(1_789_998_000_000L, h.hits.single().reset)
        assertEquals(19, h.reliability!!.fixed); assertEquals(4.0, h.reliability!!.averageShiftMinutes, 1e-9)
        assertEquals("Confirmed reset", h.events.single().kind)
        assertEquals(12.5, h.sessions.single().used, 1e-9)
        // Incidents keep only https links.
        assertEquals(2, s.incidents.size); assertEquals("https://status.claude.com/incidents/x", s.incidents[0].url); assertNull(s.incidents[1].url)
        val tokens = s.tokens!!
        assertEquals(listOf("claude", "codex"), tokens.sources.map { it.id })
        assertEquals(928_600L, tokens.sources[0].today!!.total); assertEquals(9.87, tokens.sources[0].week!!.cost!!, 1e-9)
        assertEquals("UsageNotch-Windows", tokens.sources[0].projects[0].name); assertNull(tokens.sources[0].models[1].cost)
        assertNull(tokens.sources[1].today)
    }
    @Test fun extraAccountsReadAsTheirKind() {
        val s = Snapshot.parse(resource("phone-snapshot-v2-fixture.json"))
        val work = s.providers.first { it.id == "claude:work" }
        assertEquals("claude", work.kind); assertEquals("claude", providerKind("claude:work")); assertEquals("openai-api", providerKind("openai_api"))
        assertEquals("Claude · Work", work.name); assertEquals("Work", work.accountLabel)
        assertEquals("five_hour", work.sessionWindow()!!.id); assertEquals("7d", work.secondaryTag())
        assertEquals(R.drawable.logo_claude, Logos.resource(work.id))
        assertEquals(Accents.default("claude"), Accents.default(work.id))
        // Codex without a kind falls back to the id.
        assertEquals("codex", s.providers.first { it.id == "codex" }.kind)
    }
    @Test fun sharedEntriesAreValidated() {
        val shared = Snapshot.parse(resource("phone-snapshot-v2-fixture.json")).shared
        assertTrue("budget:claude|five_hour" in shared)
        assertNull("a tombstone keeps a null value", shared.getValue("budget:codex|codex-secondary").v)
        assertEquals("#7A5CFF", shared.getValue("accent:claude").v)
        assertFalse("bad colour", "accent:codex" in shared)
        assertFalse("budget limit out of range", "budget:claude|seven_day" in shared)
        assertFalse("unknown keys are ignored", "unknown:key" in shared)
        assertEquals("Work Claude", shared.getValue("name:claude:work").v)
    }
    @Test fun olderPcsStayProtocolOne() {
        val s = Snapshot.parse(resource("phone-snapshot-fixture.json"))
        assertEquals(1, s.protocol); assertTrue(s.features.isEmpty()); assertFalse(s.has("refresh"))
        assertTrue(s.shared.isEmpty()); assertNull(s.tokens); assertTrue(s.incidents.isEmpty())
        assertTrue(s.providers.all { it.history.all { h -> h.trend.isEmpty() && h.reliability == null } })
    }
    @Test fun oversizedExtrasAreDroppedNotTheHistory() {
        val raw = resource("phone-snapshot-v2-fixture.json")
        val manyPoints = (0 until 600).joinToString(",") { """{"at":${1_789_000_000_000L + it},"used":0.1,"period":"x"}""" }
        val damaged = raw.replace(""""trend": [ { "at": 1789400000000""", """"trend": [ $manyPoints, { "at": 1789400000000""")
        val h = Snapshot.parse(damaged).providers.first { it.id == "claude" }.history.single()
        assertTrue(h.trend.isEmpty()); assertEquals(3, h.streak); assertEquals(1, h.days.size)
    }
    @Test fun offlineEstimateProjectsTheLastPace() {
        val now = 1_790_000_000_000L
        fun w(at: Long, rate: Double?, used: Double = .40, reset: Long = now + 2 * hour) = UsageWindow("five_hour", "S", used, at, reset, emptyList(), forecast = rate?.let { Forecast("", it, null, null, "") })
        assertEquals(.50, w(now - hour, 10.0).estimateNow(now, offline = true)!!, 1e-9)
        assertNull("a fresh reading isn't estimated", w(now - 5 * 60_000, 10.0).estimateNow(now, offline = false))
        assertEquals(.45, w(now - 30 * 60_000, 10.0).estimateNow(now, offline = false)!!, 1e-9)
        assertNull("no pace, no estimate", w(now - hour, null).estimateNow(now, true))
        assertNull("after the reset", w(now - hour, 10.0, reset = now - 1).estimateNow(now, true))
        assertNull("too long ago", w(now - 13 * hour, 10.0, reset = now + hour).estimateNow(now, true))
        assertEquals("never past the limit", 1.0, w(now - 2 * hour, 60.0, used = .9, reset = now + hour).estimateNow(now, true)!!, 1e-9)
    }
    @Test fun customAlertLevelsAndQuietHours() {
        val now = 1_790_000_000_000L; val reset = now + 2 * hour
        val s = Snapshot(now, listOf(Provider("claude", "Claude", "Ok", listOf(UsageWindow("five_hour", "Current session", .75, now, reset, emptyList())))))
        assertTrue(UsageAlerts.due(s, emptySet(), now, false).isEmpty())
        assertEquals(listOf("claude|five_hour|$reset|70"), UsageAlerts.due(s, emptySet(), now, false, levels = listOf(90, 70)).map { it.key })
        assertTrue(AlertPrefs.quiet(true, 22, 7, 23)); assertTrue(AlertPrefs.quiet(true, 22, 7, 3)); assertFalse(AlertPrefs.quiet(true, 22, 7, 12))
        assertTrue("equal hours mean all day", AlertPrefs.quiet(true, 9, 9, 15)); assertFalse(AlertPrefs.quiet(false, 9, 9, 15))
        assertTrue(AlertPrefs.quiet(true, 9, 17, 9)); assertFalse(AlertPrefs.quiet(true, 9, 17, 17))
    }
    @Test fun preResetRemindersAreDueTenMinutesAhead() {
        val now = 1_790_000_000_000L
        fun snap(used: Double, reset: Long) = Snapshot(now, listOf(Provider("claude", "Claude", "Ok", listOf(UsageWindow("five_hour", "Current session", used, now, reset, emptyList())))))
        val due = Reminders.due(snap(.87, now + 9 * 60_000), now, emptySet(), preReset = true, goodMoment = false, use24 = true)
        assertEquals("Claude renews in 9 min", due.single().title)
        assertTrue("already sent", Reminders.due(snap(.87, now + 9 * 60_000), now, setOf(due.single().key), true, false, true).isEmpty())
        assertTrue("barely used", Reminders.due(snap(.2, now + 9 * 60_000), now, emptySet(), true, false, true).isEmpty())
        assertTrue("not yet", Reminders.due(snap(.87, now + 30 * 60_000), now, emptySet(), true, false, true).isEmpty())
        assertTrue("off", Reminders.due(snap(.87, now + 9 * 60_000), now, emptySet(), false, false, true).isEmpty())
        assertEquals(now + 20 * 60_000, Reminders.nextAt(snap(.87, now + 30 * 60_000), now, true, false))
        assertNull(Reminders.nextAt(snap(.2, now + 30 * 60_000), now, true, false))
    }
}
