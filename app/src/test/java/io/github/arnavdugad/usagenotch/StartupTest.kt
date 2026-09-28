package io.github.arnavdugad.usagenotch

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Exercises the no-pairing startup path used by a freshly installed APK. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = NotchApplication::class)
class StartupTest {
    @Before
    fun clearFreshInstallState() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf("usage", "display", "pairing-vault").forEach { name ->
            context.getSharedPreferences(name, 0).edit().clear().commit()
        }
    }

    @Test fun freshInstallHasSafeRepositoryAndNoPairing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = Repository(app)
        assertNull(repository.pairing())
        assertNull(repository.snapshot())
    }

    @Test
    fun actualActivityCanStartWithTheApplicationClass() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNull(controller.get().intent?.getStringExtra("startup-error"))
        controller.pause().stop().destroy()
    }
}
