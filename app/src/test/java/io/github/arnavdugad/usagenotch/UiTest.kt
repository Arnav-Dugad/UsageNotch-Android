package io.github.arnavdugad.usagenotch

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class UiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun clear() { val context = ApplicationProvider.getApplicationContext<Application>(); listOf("usage", "display", "pairing-vault").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() } }
    @Test fun onboardingPreviewNavigationAndSettings() {
        compose.setContent { NotchTheme { NotchApp() } }
        compose.onNodeWithText("Import PC pairing").assertIsDisplayed()
        screenshot("01-onboarding")
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onNodeWithText("Preview · sample data").assertIsDisplayed()
        compose.onNodeWithText("Claude").assertExists()
        screenshot("02-overview")
        compose.onNodeWithText("Widgets").performClick()
        compose.onNodeWithText("The overview").assertIsDisplayed()
        screenshot("03-widgets")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Pair your Windows PC").assertExists()
        screenshot("04-settings")
        scrollTo("Show remaining"); compose.onNodeWithContentDescription("Show remaining").performClick()
        compose.onNodeWithContentDescription("Show remaining").assertIsOff()
    }
    @Test @Config(qualifiers = "w320dp-h640dp-hdpi") fun smallScreenRemainsNavigable() {
        compose.setContent { NotchTheme { NotchApp() } }
        compose.onNodeWithText("Import PC pairing").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        scrollTo("24-hour clock"); compose.onNodeWithContentDescription("24-hour clock").performClick().assertIsOn()
        scrollTo("Reduce motion"); compose.onNodeWithContentDescription("Reduce motion").performClick().assertIsOn()
        scrollTo("Reset alerts"); compose.onNodeWithContentDescription("Reset alerts").assertIsOff()
        screenshot("05-small-screen-settings")
    }
    @Test @Config(qualifiers = "w840dp-h1100dp-xhdpi") fun tabletShowsBothProviders() {
        compose.setContent { NotchTheme { NotchApp() } }
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onNodeWithText("Claude").assertIsDisplayed()
        compose.onNodeWithText("Codex").assertIsDisplayed()
        screenshot("06-tablet")
    }
    @Test fun actualWidgetLayoutShowsSeparateAndStaleWindows() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val now = System.currentTimeMillis()
        val fixture = """{"schema":1,"generatedAt":$now,"providers":[{"id":"claude","name":"Claude","status":"Ok","windows":[{"id":"five_hour","label":"5-hour window","used":0.27,"at":$now,"reset":${now+7200000},"points":[]},{"id":"seven_day","label":"Weekly window","used":0.41,"at":$now,"reset":${now+172800000},"points":[]}]},{"id":"codex","name":"Codex","status":"NeedsAuth","windows":[{"id":"primary","label":"Primary window","used":0.16,"at":$now,"reset":${now+7200000},"points":[]}]}]}"""
        context.getSharedPreferences("usage", 0).edit().putString("snapshot", fixture).putLong("sync", now).commit()
        val view = buildWidgetViews(context, 99, false).apply(context, android.widget.FrameLayout(context))
        val rows = view.findViewById<android.widget.LinearLayout>(R.id.widget_rows)
        assertEquals(2, rows.childCount)
        assertTrue(rows.getChildAt(0).findViewById<android.widget.TextView>(R.id.row_title).text.contains("73%"))
        assertTrue(rows.getChildAt(1).findViewById<android.widget.TextView>(R.id.row_title).text.contains("84%"))
        assertTrue(rows.getChildAt(1).findViewById<android.widget.TextView>(R.id.row_detail).text.contains("Saved reading"))
        val density = context.resources.displayMetrics.density
        val width = (340*density).toInt(); val height = (220*density).toInt()
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(width, android.view.View.MeasureSpec.EXACTLY), android.view.View.MeasureSpec.makeMeasureSpec(height, android.view.View.MeasureSpec.EXACTLY)); view.layout(0,0,width,height)
        assertTrue("Both widget rows must fit without clipping", rows.getChildAt(rows.childCount-1).bottom <= rows.height)
        val bitmap = Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888); view.draw(Canvas(bitmap))
        val file=File("build/screenshots/07-widget.png"); file.parentFile!!.mkdirs(); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        context.getSharedPreferences("display", 0).edit().putString("widget-100", "claude").commit()
        val focus=buildWidgetViews(context,100,true).apply(context,android.widget.FrameLayout(context))
        assertEquals(2,focus.findViewById<android.widget.LinearLayout>(R.id.widget_rows).childCount)
    }
    @Test fun widgetShowsRenewedWindowsInsteadOfStalePercentages() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val now = System.currentTimeMillis()
        val fixture = """{"schema":1,"generatedAt":$now,"providers":[{"id":"claude","name":"Claude","status":"Ok","windows":[{"id":"five_hour","label":"5-hour window","used":0.93,"at":${now-7200000},"reset":${now-600000},"points":[]},{"id":"seven_day","label":"Weekly window","used":0.41,"at":${now-7200000},"reset":${now+172800000},"points":[]}]}]}"""
        context.getSharedPreferences("usage", 0).edit().putString("snapshot", fixture).putLong("sync", now - 7200000).putBoolean("offline", true).commit()
        val view = buildWidgetViews(context, 101, true).apply(context, android.widget.FrameLayout(context))
        val rows = view.findViewById<android.widget.LinearLayout>(R.id.widget_rows)
        assertEquals("Claude", view.findViewById<android.widget.TextView>(R.id.widget_title).text.toString())
        val first = rows.getChildAt(0)
        assertEquals("Renewed", first.findViewById<android.widget.TextView>(R.id.row_title).text.toString())
        assertFalse(first.findViewById<android.widget.TextView>(R.id.row_title).text.contains("7%"))
        assertEquals(android.view.View.GONE, first.findViewById<android.view.View>(R.id.row_bar).visibility)
        assertTrue(rows.getChildAt(1).findViewById<android.widget.TextView>(R.id.row_title).text.contains("59% left"))
    }
    /** Settings is a lazy list: items off screen are not composed until the list scrolls to them. */
    private fun scrollTo(description: String) = compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasContentDescription(description))
    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File("build/screenshots/$name.png"); file.parentFile!!.mkdirs()
        compose.runOnIdle {
            val view = compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
