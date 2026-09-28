package io.github.arnavdugad.usagenotch

import org.junit.Assert.*
import org.junit.Test
import java.util.Base64
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class FeatureTest {
    private val key = ByteArray(32) { it.toByte() }
    private val keyText = Base64.getUrlEncoder().withoutPadding().encodeToString(key)
    private val relayUrl = "https://gist.githubusercontent.com/Arnav-Dugad/0123456789abcdef0123456789abcdef/raw/usagenotch-sync.json"
    private fun pairing(relay: String = """{"url":"$relayUrl","key":"$keyText"}""") =
        """{"schema":1,"endpoint":"https://192.168.1.2:43187","token":"${"a".repeat(43)}","certificateSha256":"${"b".repeat(64)}","name":"PC","relay":$relay}"""
    private fun seal(plain: String, nonce: ByteArray = ByteArray(12) { 7 }, aad: String = RELAY_AAD): String {
        val c = Cipher.getInstance("AES/GCM/NoPadding"); c.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, nonce)); c.updateAAD(aad.toByteArray())
        return """{"v":1,"nonce":"${Base64.getEncoder().encodeToString(nonce)}","data":"${Base64.getEncoder().encodeToString(c.doFinal(plain.toByteArray()))}"}"""
    }

    @Test fun pairingCarriesOptionalRelayAndRoundTrips() {
        val pair = Pairing.parse(pairing())
        assertEquals(relayUrl, pair.relay!!.url)
        assertArrayEquals(key, pair.relay!!.key)
        assertEquals(pair, Pairing.parse(pair.json()))
        val withoutRelay = Pairing.parse(pairing().replace(Regex(",\"relay\":.*}$"), "}"))
        assertNull(withoutRelay.relay)
    }
    @Test fun pairingCodeIsTheSameFileInOneLine() {
        val code = "UN1." + Base64.getUrlEncoder().withoutPadding().encodeToString(pairing().toByteArray())
        assertEquals(Pairing.parse(pairing()), Pairing.parse("  $code\n"))
        // Messaging apps may wrap long text.
        assertEquals(Pairing.parse(pairing()), Pairing.parse(code.chunked(40).joinToString("\n")))
        assertThrows(Exception::class.java) { Pairing.parse("UN1.not-base64!") }
    }
    @Test fun relayOnlyAcceptsTheOwnersGistRawFileAndFullKey() {
        for (bad in listOf("https://example.com/a/0123456789abcdef0123456789abcdef/raw/usagenotch-sync.json", "http://gist.githubusercontent.com/a/0123456789abcdef0123456789abcdef/raw/usagenotch-sync.json",
            "https://gist.githubusercontent.com/a/0123456789abcdef0123456789abcdef/raw/other.json", "https://gist.githubusercontent.com/a/0123456789abcdef0123456789abcdef/raw/usagenotch-sync.json?x=1",
            "https://gist.githubusercontent.com.evil.test/a/0123456789abcdef0123456789abcdef/raw/usagenotch-sync.json"))
            assertThrows(bad, Exception::class.java) { Pairing.parse(pairing("""{"url":"$bad","key":"$keyText"}""")) }
        assertThrows(Exception::class.java) { Pairing.parse(pairing("""{"url":"$relayUrl","key":"${keyText.take(20)}"}""")) }
    }
    @Test fun relayEnvelopeOpensOnlyWithTheRightKeyAndLabel() {
        val snapshot = """{"schema":1,"generatedAt":1,"providers":[]}"""
        assertEquals(snapshot, openRelay(seal(snapshot), key))
        assertThrows(AEADBadTagException::class.java) { openRelay(seal(snapshot), ByteArray(32)) }
        assertThrows(AEADBadTagException::class.java) { openRelay(seal(snapshot, aad = "other"), key) }
        assertThrows(Exception::class.java) { openRelay(seal(snapshot).replace("\"v\":1", "\"v\":2"), key) }
    }
    @Test fun relayEnvelopeFromWindowsLinkDecrypts() {
        // Produced by UsageNotch Link's RelayCrypto with the key bytes 0..31 (see companion self-test).
        val fixture = javaClass.getResourceAsStream("/relay-fixture.json")!!.use { String(it.readBytes()) }
        val snapshot = Snapshot.parse(openRelay(fixture, key))
        assertEquals("claude", snapshot.providers.single().id)
        assertEquals(0.42, snapshot.providers.single().windows.single().used, 1e-9)
    }
    private fun window(at: Long, reset: Long?) = UsageWindow("w$reset", "Window", .5, at, reset, emptyList())
    @Test fun resetAlertsAnnounceEachPassedResetOnce() {
        val snapshot = Snapshot(0, listOf(Provider("claude", "Claude", "Ok", listOf(window(100, 1_000), window(100, 5_000), window(100, null))), Provider("codex", "Codex", "Ok", listOf(window(100, 2_000)))))
        assertEquals(1_000L, ResetAlerts.nextReset(snapshot, 500))
        assertEquals(5_000L, ResetAlerts.nextReset(snapshot, 2_000))
        assertNull(ResetAlerts.nextReset(snapshot, 5_000))
        val due = ResetAlerts.due(snapshot, 500, 2_500)
        assertEquals(setOf("Claude", "Codex"), due.keys)
        assertEquals(1, due.getValue("Claude").size)
        assertTrue(ResetAlerts.due(snapshot, 2_500, 3_000).isEmpty())
        // A reading taken after its own reset time is not a pending reset.
        assertTrue(ResetAlerts.due(Snapshot(0, listOf(Provider("c", "C", "Ok", listOf(window(3_000, 1_000))))), 0, 4_000).isEmpty())
    }
    @Test fun updateCheckComparesNumericVersions() {
        assertTrue(Updates.newer("1.1.0", "1.0.1"))
        assertTrue(Updates.newer("1.10.0", "1.9.9"))
        assertFalse(Updates.newer("1.1.0", "1.1.0"))
        assertFalse(Updates.newer("1.0.9", "1.1.0"))
        assertFalse(Updates.newer("latest", "1.1.0"))
    }
    @Test fun focusWidgetFallsBackToFirstProvider() {
        val providers = listOf(Provider("codex", "Codex", "Ok", emptyList()), Provider("claude", "Claude", "Ok", emptyList()))
        assertEquals("claude", focusProvider(providers, "claude")!!.id)
        assertEquals("codex", focusProvider(providers, null)!!.id)
        assertEquals("codex", focusProvider(providers, "cursor")!!.id)
        assertNull(focusProvider(emptyList(), "claude"))
    }
    @Test fun windowsApiProviderIdsShareLogosAndColors() {
        assertEquals("openai-api", providerKind("openai_api"))
        assertEquals("anthropic-api", providerKind("anthropic_api"))
        assertEquals(providerColor("anthropic_api"), providerColor("claude"))
    }
    @Test fun renewedWindowsNeverShowAStalePercentageAsCurrent() {
        val p = Provider("claude", "Claude", "Ok", listOf(window(1_000, 2_000)))
        assertEquals("Limit renewed", readingState(p, p.windows[0], 2_000))
        assertEquals("Renewed · awaiting PC reading", ClockText.countdown(2_000, 2_001))
    }
}
