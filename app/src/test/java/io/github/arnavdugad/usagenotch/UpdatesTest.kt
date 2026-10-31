package io.github.arnavdugad.usagenotch

import org.junit.Assert.*
import org.junit.Test

/** The checks the self-updater relies on before anything is installed. */
class UpdatesTest {
    @Test fun versionsCompareNumerically() {
        assertTrue(Updates.newer("1.6.0", "1.5.0")); assertTrue(Updates.newer("1.10.0", "1.9.9")); assertTrue(Updates.newer("2.0.0", "1.99.99"))
        assertFalse(Updates.newer("1.5.0", "1.5.0")); assertFalse(Updates.newer("1.4.9", "1.5.0"))
        assertFalse("malformed tags are never newer", Updates.newer("1.6", "1.5.0")); assertFalse(Updates.newer("v1.6.0-beta", "1.5.0"))
    }
    @Test fun onlyGitHubHostsOverHttps() {
        assertTrue(Updates.allowedUrl("https://github.com/Arnav-Dugad/UsageNotch-Android/releases/download/v1.6.0/UsageNotch-1.6.0.apk"))
        assertTrue(Updates.allowedUrl("https://release-assets.githubusercontent.com/github-production-release-asset/1/abc?sp=r"))
        assertTrue(Updates.allowedUrl("https://objects.githubusercontent.com/github-production-release-asset-2e65be/1"))
        assertTrue(Updates.allowedUrl("https://api.github.com/repos/Arnav-Dugad/UsageNotch-Android/releases/latest"))
        assertFalse("plain HTTP", Updates.allowedUrl("http://github.com/x.apk"))
        assertFalse("look-alike host", Updates.allowedUrl("https://github.com.evil.example/x.apk"))
        assertFalse("other host", Updates.allowedUrl("https://example.com/UsageNotch-1.6.0.apk"))
        assertFalse("credentials", Updates.allowedUrl("https://user@github.com/x.apk"))
        assertFalse("odd port", Updates.allowedUrl("https://github.com:8443/x.apk"))
        assertFalse(Updates.allowedUrl(null)); assertFalse(Updates.allowedUrl("not a url"))
    }
    @Test fun checksumsReadBothSha256sumModes() {
        val sums = "ecc51c5c810d5d24bcbeff7544d2132b50e77bf4684fb32bc2e16acb44f4bf53 *UsageNotch-1.5.0.apk\n" +
            "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789  UsageNotch-1.6.0.apk\r\n"
        assertEquals("ecc51c5c810d5d24bcbeff7544d2132b50e77bf4684fb32bc2e16acb44f4bf53", Updates.checksumFor(sums, "UsageNotch-1.5.0.apk"))
        assertEquals("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", Updates.checksumFor(sums, "UsageNotch-1.6.0.apk"))
        assertNull(Updates.checksumFor(sums, "UsageNotch-1.7.0.apk"))
        assertNull("short hashes are refused", Updates.checksumFor("abc123 UsageNotch-1.6.0.apk", "UsageNotch-1.6.0.apk"))
    }
    @Test fun sha256MatchesKnownVector() {
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", Updates.sha256("abc".byteInputStream()))
    }
    @Test fun releaseJsonPicksTheVersionedApkAndChecksums() {
        val json = """{"tag_name":"v1.6.0","assets":[
            {"name":"SHA256SUMS.txt","size":87,"browser_download_url":"https://github.com/Arnav-Dugad/UsageNotch-Android/releases/download/v1.6.0/SHA256SUMS.txt"},
            {"name":"UsageNotch-1.6.0.apk","size":3836186,"browser_download_url":"https://github.com/Arnav-Dugad/UsageNotch-Android/releases/download/v1.6.0/UsageNotch-1.6.0.apk"},
            {"name":"UsageNotch-evil.apk","size":10,"browser_download_url":"https://example.com/evil.apk"}]}"""
        val r = Updates.parseRelease(json)!!
        assertEquals("1.6.0", r.version); assertEquals("UsageNotch-1.6.0.apk", r.apkName); assertEquals(3836186L, r.apkSize)
        assertTrue(r.sumsUrl!!.endsWith("SHA256SUMS.txt"))
        assertNull("an APK on another host is ignored", Updates.parseRelease(json.replace("https://github.com/Arnav-Dugad/UsageNotch-Android/releases/download/v1.6.0/UsageNotch-1.6.0.apk", "https://example.com/x.apk")))
        assertNull("oversized", Updates.parseRelease(json.replace("3836186", (Updates.MAX_APK_BYTES + 1).toString())))
        assertNull("no version", Updates.parseRelease("""{"tag_name":"latest","assets":[]}"""))
    }
}
