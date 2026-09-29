package io.github.arnavdugad.usagenotch

import com.google.zxing.BarcodeFormat
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.*
import org.junit.Test

/** Forecast, history, alerts, relay adoption and the in-app QR decoder. */
class InsightsTest {
    private fun resource(name: String) = javaClass.getResourceAsStream("/$name")!!.use { String(it.readBytes()) }
    private val hour = 3_600_000L

    @Test fun historyAndForecastFromTheWindowsAppParse() {
        val claude = Snapshot.parse(resource("phone-snapshot-fixture.json")).providers.first { it.id == "claude" }
        assertEquals(listOf("five_hour", "seven_day"), claude.history.map { it.window })
        val session = claude.history.first()
        assertEquals(30, session.days.size); assertEquals(168, session.heat.size); assertEquals(168, session.observed.size)
        // Days the PC never observed stay null (no data), never zero.
        assertTrue(session.days.dropLast(1).all { it.used == null })
        assertEquals(.01, session.days.last().used!!, 1e-9)
        assertEquals(1, session.streak)
        // The fixture's only reading hour depends on when it was generated; busiest() must return exactly that cell.
        val cell = session.heat.indexOfFirst { it > 0 }
        assertEquals(listOf(Triple(cell / 24, cell % 24, .01)), session.busiest())
        // Windows 2.5 adds a 90-day calendar next to the 30 days Android 1.3 reads.
        assertEquals(90, session.calendar.size)
        assertEquals(session.days.last(), session.calendar.last())
        assertEquals(session.days, session.calendar.takeLast(30))
        assertNotNull(claude.sessionWindow()!!.forecast)
        assertEquals("Limited history", claude.sessionWindow()!!.forecast!!.confidence)
        // Older PCs send neither.
        assertTrue(claude.windows.all { it.id.isNotEmpty() })
        val codex = Snapshot.parse(resource("phone-snapshot-fixture.json")).providers.first { it.id == "codex" }
        assertTrue(codex.history.isEmpty()); assertNull(codex.sessionWindow()!!.forecast)
    }

    @Test fun malformedHistoryIsIgnoredNotFatal() {
        val raw = """{"schema":1,"generatedAt":1,"providers":[{"id":"claude","name":"Claude","status":"Ok","windows":[],"history":[{"window":"w","label":"L","days":[],"heat":[1,2],"observed":[1,2],"streak":1}]}]}"""
        assertTrue(Snapshot.parse(raw).providers.single().history.isEmpty())
    }

    @Test fun busiestSkipsUnobservedAndQuietHours() {
        val heat = MutableList(168) { 0.0 }; val observed = MutableList(168) { 1 }
        heat[30] = .2; heat[100] = .5; heat[5] = .9; observed[5] = 0; heat[60] = .00001
        val h = History("w", "L", emptyList(), heat, observed, 0)
        assertEquals(listOf(Triple(4, 4, .5), Triple(1, 6, .2)), h.busiest())
    }

    @Test fun localLinkTurnsInternetSyncOnAndOff() {
        val key = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(32) { it.toByte() })
        val url = "https://gist.githubusercontent.com/test-owner/0123456789abcdef0123/raw/usagenotch-sync.json"
        val on = Snapshot.relayUpdate("""{"relayState":"on","relay":{"url":"$url","key":"$key"}}""")!!
        assertEquals("on", on.first); assertEquals(url, on.second!!.url)
        assertEquals("off" to null, Snapshot.relayUpdate("""{"relayState":"off"}"""))
        assertNull("older PCs don't say", Snapshot.relayUpdate("""{"schema":1}"""))
        assertNull("only the owner's gist is accepted", Snapshot.relayUpdate("""{"relayState":"on","relay":{"url":"https://example.com/x","key":"$key"}}"""))
    }

    private fun snapshot(now: Long, used: Double, reset: Long, limitAt: Long? = null) = Snapshot(now, listOf(Provider("claude", "Claude", "Ok",
        listOf(UsageWindow("five_hour", "Current session", used, now, reset, emptyList(), forecast = Forecast("", 20.0, 120.0, limitAt, "Consistent pace"))))))

    @Test fun usageAlertsFireOncePerThresholdAndPeriod() {
        val now = 1_800_000_000_000L; val reset = now + 3 * hour
        assertTrue(UsageAlerts.due(snapshot(now, .5, reset), emptySet(), now, false).isEmpty())
        val at80 = UsageAlerts.due(snapshot(now, .82, reset), emptySet(), now, false).single()
        assertEquals("claude|five_hour|$reset|80", at80.key); assertTrue(at80.title, at80.title.contains("82% used"))
        assertTrue(UsageAlerts.due(snapshot(now, .85, reset), setOf(at80.key), now, false).isEmpty())
        // Jumping past 95% sends only the higher alert.
        assertEquals(listOf("claude|five_hour|$reset|95"), UsageAlerts.due(snapshot(now, .97, reset), emptySet(), now, false).map { it.key })
        // A new period (new reset time) alerts again.
        assertEquals(1, UsageAlerts.due(snapshot(now, .82, reset + 5 * hour), setOf(at80.key), now, false).size)
        // Nothing for a window whose reset already passed.
        assertTrue(UsageAlerts.due(snapshot(now, .97, now - 1), emptySet(), now, false).isEmpty())
    }

    @Test fun paceAlertWhenTheLimitRunsOutWithinTwoHoursBeforeReset() {
        val now = 1_800_000_000_000L; val reset = now + 4 * hour
        val soon = UsageAlerts.due(snapshot(now, .6, reset, now + 90 * 60_000), emptySet(), now, true)
        assertEquals(listOf("claude|five_hour|$reset|pace"), soon.map { it.key })
        assertTrue(soon.single().title, soon.single().title.contains("1 h 30 min"))
        assertTrue(UsageAlerts.due(snapshot(now, .6, reset, now + 3 * hour), emptySet(), now, true).isEmpty())
        assertTrue(UsageAlerts.due(snapshot(now, .6, reset, now + hour), setOf("claude|five_hour|$reset|pace"), now, true).isEmpty())
        assertTrue("already at 95%", UsageAlerts.due(snapshot(now, .96, reset, now + 10 * 60_000), setOf("claude|five_hour|$reset|95"), now, true).isEmpty())
    }

    @Test fun forecastLineUsesTheDesktopEstimate() {
        val now = 1_800_000_000_000L
        assertNull(forecastLine(Forecast("Learning your usage pace", null, null, null, "Limited history"), now, false))
        assertNull(forecastLine(Forecast("Forecast unavailable", null, null, null, "Limited history"), now, false))
        assertEquals("At this pace: 61.4% used at reset", forecastLine(Forecast("Estimated 61.4% used at reset", 10.0, 61.4, null, "Consistent pace"), now, false))
        assertEquals("At this pace: limit reached by now", forecastLine(Forecast("Estimated limit reached by now", 10.0, 140.0, now - 1, "Consistent pace"), now, false))
        assertTrue(forecastLine(Forecast("Estimated limit: x", 10.0, 140.0, now + hour, "Consistent pace"), now, true)!!.startsWith("At this pace: limit around "))
    }

    private fun qr(text: String, inverted: Boolean = false): RGBLuminanceSource {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 400, 400)
        val pixels = IntArray(matrix.width * matrix.height) { i -> val dark = matrix.get(i % matrix.width, i / matrix.width) != inverted; if (dark) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
        return RGBLuminanceSource(matrix.width, matrix.height, pixels)
    }

    @Test fun scannerDecodesThePairingQrIncludingDarkModeCodes() {
        val code = "https://arnav-dugad.github.io/UsageNotch-Windows/pair/#" + resource("pairing-fixture.txt").trim().lines()[0]
        assertEquals(code, QrDecoder.decode(qr(code)))
        assertEquals("light-on-dark codes decode too", code, QrDecoder.decode(qr(code, inverted = true)))
        assertEquals(Pairing.parse(resource("pairing-fixture.txt").trim().lines()[0]).name, Pairing.parse(QrDecoder.decode(qr(code))!!).name)
        assertNull(QrDecoder.decode(RGBLuminanceSource(40, 40, IntArray(1600) { 0xFFFFFFFF.toInt() })))
    }
}
