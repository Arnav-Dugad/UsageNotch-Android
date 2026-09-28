package io.github.arnavdugad.usagenotch

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.TimeZone
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.cert.CertificateException

class ModelTest {
    private fun pairing(endpoint: String = "https://192.168.1.2:43187", token: String = "a".repeat(43), pin: String = "b".repeat(64)) = """{"schema":1,"endpoint":"$endpoint","token":"$token","certificateSha256":"$pin","name":"PC"}"""
    @Test fun acceptsOnlyPrivateHttpsPairing() {
        assertEquals("PC", Pairing.parse(pairing()).name)
        assertEquals("https://100.64.1.2:43187", Pairing.parse(pairing("https://100.64.1.2:43187")).endpoint)
        for (url in listOf("http://192.168.1.2:43187", "https://example.com:43187", "https://8.8.8.8:43187", "https://192.168.1.2:43187/v1", "https://192.168.1.2:43187?token=a", "https://user@192.168.1.2:43187", "https://192.168.999.2:43187")) assertThrows(Exception::class.java) { Pairing.parse(pairing(url)) }
        assertThrows(Exception::class.java) { Pairing.parse(pairing(token = "weak")) }
        assertThrows(Exception::class.java) { Pairing.parse(pairing(pin = "not-a-pin")) }
    }
    @Test fun clocksCoverNoonMidnightAndPassedReset() {
        val original = TimeZone.getDefault(); TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        try {
            assertTrue(ClockText.stamp(0).endsWith("12:00 AM"))
            assertTrue(ClockText.stamp(43_200_000).endsWith("12:00 PM"))
            assertTrue(ClockText.stamp(43_200_000, true).endsWith("12:00"))
            assertEquals("01:01:01", ClockText.countdown(3_661_000, 0))
            assertEquals("2d 01:01:01", ClockText.countdown(176_461_000, 0))
            assertTrue(ClockText.countdown(100, 101).contains("awaiting"))
            assertEquals("Reset time not reported", ClockText.countdown(null, 0))
        } finally { TimeZone.setDefault(original) }
    }
    @Test fun usageIsNeverCombinedOrResetLocally() {
        val raw = """{"schema":1,"generatedAt":1000,"providers":[{"id":"claude","name":"Claude","status":"Ok","windows":[{"id":"a","label":"5-hour","used":0.2,"at":1000,"reset":2000,"points":[]},{"id":"b","label":"Weekly","used":0.7,"at":1000,"reset":9000,"points":[]}]}]}"""
        val p = Snapshot.parse(raw).providers.single()
        assertEquals(listOf(80,30), p.windows.map { it.percent(true) })
        assertEquals(listOf(20,70), p.windows.map { it.percent(false) })
        assertEquals("Limit renewed", readingState(p, p.windows[0], 3000))
        assertEquals(20, p.windows[0].percent(false))
        assertEquals("Saved reading", readingState(p.copy(status = "NeedsAuth"), p.windows[1], 1500))
        assertThrows(Exception::class.java) { Snapshot.parse(raw.replace("0.2", "-0.2")) }
        assertThrows(Exception::class.java) { Snapshot.parse(raw.replace("\"schema\":1", "\"schema\":2")) }
    }
    @Test fun importAndNetworkReadsAreBounded() {
        assertEquals(32, readBounded(ByteArrayInputStream(ByteArray(32)), 32).size)
        assertThrows(IllegalArgumentException::class.java) { readBounded(ByteArrayInputStream(ByteArray(33)), 32) }
    }
    @Test fun certificatePinRejectsEveryOtherIdentity() {
        val cert = javaClass.getResourceAsStream("/test-certificate.cer")!!.use { CertificateFactory.getInstance("X.509").generateCertificate(it) as X509Certificate }
        pinnedTrustManager(certificatePin(cert)).checkServerTrusted(arrayOf(cert), "RSA")
        assertThrows(CertificateException::class.java) { pinnedTrustManager("0".repeat(64)).checkServerTrusted(arrayOf(cert), "RSA") }
        assertThrows(CertificateException::class.java) { pinnedTrustManager(certificatePin(cert)).checkServerTrusted(emptyArray(), "RSA") }
    }
}
