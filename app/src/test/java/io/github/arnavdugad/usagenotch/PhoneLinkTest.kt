package io.github.arnavdugad.usagenotch

import org.junit.Assert.*
import org.junit.Test
import java.util.TimeZone

/** Formats produced by UsageNotch for Windows 2.3 (fixtures written by its diagnostics) must decode identically here. */
class PhoneLinkTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")!!.use { String(it.readBytes()) }

    @Test fun qrCodeFromWindowsMatchesItsPairingFile() {
        val (compact, json) = resource("pairing-fixture.txt").trim().lines()
        val fromQr = Pairing.parse(compact); val fromFile = Pairing.parse(json)
        assertEquals(fromFile, fromQr)
        assertEquals("https://192.168.1.8:43187", fromQr.endpoint)
        assertEquals("Test PC", fromQr.name)
        assertNotNull("the fixture includes internet sync", fromQr.relay)
        // The QR code holds a web address; phone cameras open it and the app receives the same code.
        assertEquals(fromFile, Pairing.parse("https://arnav-dugad.github.io/UsageNotch-Windows/pair/#$compact"))
        assertEquals(fromFile, Pairing.parse("usagenotch://pair/$compact"))
    }
    @Test fun damagedCompactCodesAreRejected() {
        val compact = resource("pairing-fixture.txt").trim().lines()[0]
        assertThrows(Exception::class.java) { Pairing.parse(compact.dropLast(6)) }
        assertThrows(Exception::class.java) { Pairing.parse("UN2.AAAA") }
        assertThrows(Exception::class.java) { Pairing.parse("hello world") }
        assertThrows(Exception::class.java) { Pairing.compactToJson(ByteArray(73)) }
    }
    @Test fun richSnapshotFromTheWindowsAppParses() {
        val snapshot = Snapshot.parse(resource("phone-snapshot-fixture.json"))
        assertEquals("windows-app", snapshot.source)
        val claude = snapshot.providers.first { it.id == "claude" }
        assertEquals("Current session", claude.sessionWindow()!!.label)
        assertEquals("All models", claude.weeklyWindow()!!.label)
        assertEquals("Sam Example", claude.account)
        assertEquals("https://claude.ai/settings/usage", claude.manageUrl)
        assertEquals(3.5, claude.extras.single().usedAmount!!, 1e-9)
        assertEquals(2, claude.sessionWindow()!!.points.size)
        val gemini = snapshot.providers.first { it.id == "gemini" }
        assertTrue(gemini.windows.isEmpty()); assertEquals("Sign in", statusPill(gemini, System.currentTimeMillis(), false))
        assertEquals("Saved", statusPill(snapshot.providers.first { it.id == "codex" }, System.currentTimeMillis(), false))
    }
    @Test fun olderLinkSnapshotsStillChooseSensibleDockWindows() {
        val w = { id: String -> UsageWindow(id, id, .1, 0, null, emptyList()) }
        val codex = Provider("codex", "Codex", "Ok", listOf(w("base_model_inference-primary"), w("base_model_inference-secondary")))
        assertEquals("base_model_inference-primary", codex.sessionWindow()!!.id)
        assertEquals("base_model_inference-secondary", codex.weeklyWindow()!!.id)
        assertEquals("7d", codex.secondaryTag())
        val single = Provider("gemini", "Gemini", "Ok", listOf(w("pro")))
        assertNull(single.weeklyWindow()?.takeIf { it.id != single.sessionWindow()?.id })
    }
    @Test fun untrustedDashboardLinksAreDropped() {
        val raw = resource("phone-snapshot-fixture.json").replace("https://claude.ai/settings/usage", "intent://evil#Intent;end")
        assertNull(Snapshot.parse(raw).providers.first { it.id == "claude" }.manageUrl)
    }
    @Test fun resetWordingMatchesTheDesktop() {
        val original = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            val now = 1_790_000_000_000L // Sun, 21 Sep 2026 14:13:20 UTC
            assertEquals("Resets in 02:00:00", ClockText.resetsIn(now + 7_200_000, now))
            assertEquals("Reset time not reported", ClockText.resetsIn(null, now))
            assertEquals("Resets today at 4:13 PM", ClockText.resetsAt(now + 7_200_000, now))
            assertEquals("Resets tomorrow at 2:13 PM", ClockText.resetsAt(now + 86_400_000, now))
            assertEquals("Updated just now", ClockText.updated(now - 10_000, now))
            assertEquals("Updated 4 min ago", ClockText.updated(now - 240_000, now))
            assertEquals("$3.50 of $20.00", amountText(3.5, 20.0, "USD", null))
            assertEquals("Pay-as-you-go", amountText(null, null, null, "Pay-as-you-go"))
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun usageColoursMatchTheDesktopRampAndStayReadable() {
        assertEquals(0xFF2EE0A8.toInt(), Palette.usage(0.0))
        assertEquals(0xFFFF3B30.toInt(), Palette.usage(1.0))
        assertEquals(0xFFF2D03C.toInt(), Palette.usage(0.60))
        for (used in listOf(0.0, .3, .6, .8, 1.0)) {
            assertTrue(Palette.contrast(Palette.readable(Palette.usage(used), 0xFFF1F3F6.toInt()), 0xFFF1F3F6.toInt()) >= 4.5)
            assertTrue(Palette.contrast(Palette.readable(Palette.usage(used), 0xFF1E232D.toInt()), 0xFF1E232D.toInt()) >= 4.5)
        }
    }
}
