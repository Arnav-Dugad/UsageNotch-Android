package io.github.arnavdugad.usagenotch

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Runs the real download path against the published 1.5.0 release: GitHub's API, the redirect to its download host,
 * the size check and the SHA256SUMS.txt comparison. Opt in with UPDATE_NETWORK_TEST=1 (it needs the internet).
 * The last step, the package check, refuses the file here because it isn't newer than this build, so nothing installs.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class UpdateNetworkTest {
    @Test fun realReleaseDownloadsAndMatchesItsChecksum() = runBlocking {
        assumeTrue(System.getenv("UPDATE_NETWORK_TEST") == "1")
        val context = ApplicationProvider.getApplicationContext<Application>()
        val release = Updates.check(context, force = true)
        assertNotNull("the latest release has an APK and checksums", release); release!!
        var progress = 0f
        val error = runCatching { Updates.download(context, release) { progress = it } }.exceptionOrNull()
        assertEquals("downloaded completely", 1f, progress, 0.0001f)
        // The checksum passed; only the package check (not newer, or not an installable archive here) stops it.
        assertNotNull(error); assertFalse(error!!.message, error.message!!.contains("checksum")); assertFalse(error.message!!.contains("size"))
        println("Update path for ${release.version}: downloaded, checksum matched, then refused: ${error.message}")
    }
}
