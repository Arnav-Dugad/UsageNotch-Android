package io.github.arnavdugad.usagenotch

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertNull
import org.junit.Test
import org.robolectric.Robolectric
import org.robolectric.annotation.Config

/** Exercises the no-pairing startup path used by a freshly installed APK. */
class StartupTest {
    @Test fun freshInstallHasSafeRepositoryAndNoPairing() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val repository = Repository(app)
        assertNull(repository.pairing())
        assertNull(repository.snapshot())
    }

    @Test
    @Config(application = NotchApplication::class)
    fun actualActivityCanStartWithTheApplicationClass() {
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        assertNull(controller.get().intent?.getStringExtra("startup-error"))
        controller.pause().stop().destroy()
    }
}
