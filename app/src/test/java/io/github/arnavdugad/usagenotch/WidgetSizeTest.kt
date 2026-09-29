package io.github.arnavdugad.usagenotch

import android.app.Application
import android.appwidget.AppWidgetManager
import android.os.Bundle
import android.util.SizeF
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Widget sizes on the Android versions whose Bundle APIs differ (12/12L lack the typed getter). */
@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class)
class WidgetSizeTest {
    private fun options() = Bundle().apply { putParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, arrayListOf(SizeF(180f, 110f), SizeF(250f, 90f))) }
    @Test @Config(sdk = [31]) fun android12ReadsExactSizes() = assertEquals(2, widgetSizes(ApplicationProvider.getApplicationContext(), options()).size)
    @Test @Config(sdk = [32]) fun android12LReadsExactSizes() = assertEquals(2, widgetSizes(ApplicationProvider.getApplicationContext(), options()).size)
    @Test @Config(sdk = [35]) fun android15ReadsExactSizes() = assertEquals(2, widgetSizes(ApplicationProvider.getApplicationContext(), options()).size)
    @Test @Config(sdk = [28]) fun android9UsesTheSizeRange() {
        val bundle = Bundle().apply { putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 180); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 250); putInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 90); putInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 110) }
        assertEquals(listOf(SizeF(180f, 110f)), widgetSizes(ApplicationProvider.getApplicationContext(), bundle))
    }
}
