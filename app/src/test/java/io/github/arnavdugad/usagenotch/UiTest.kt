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
        compose.setContent { NotchTheme("dark") { NotchApp() } }
        compose.onNodeWithText("Scan QR code").assertIsDisplayed()
        compose.onNodeWithText("Import PC pairing").assertExists()
        screenshot("01-onboarding")
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onNodeWithText("Preview · sample data").assertIsDisplayed()
        compose.onAllNodesWithText("Claude").onFirst().assertExists()
        compose.onAllNodesWithText("Current session").onFirst().assertExists()
        screenshot("02-overview")
        compose.onNodeWithText("Widgets").performClick()
        compose.onNodeWithText("Any size. Your rings.").assertIsDisplayed()
        screenshot("03-widgets")
        compose.onNodeWithText("Settings").performClick()
        compose.onNodeWithText("Pair your Windows PC").assertExists()
        screenshot("04-settings")
        scrollTo("Show remaining"); compose.onNodeWithContentDescription("Show remaining").performClick()
        compose.onNodeWithContentDescription("Show remaining").assertIsOff()
    }
    @Test fun dockCellsShowPercentagesAndWeeklyFigures() {
        compose.setContent { NotchTheme("dark") { NotchApp() } }
        compose.onNodeWithText("Explore with sample data").performClick()
        // Sample: Claude session 27% used, weekly 41% used.
        compose.onNodeWithContentDescription("Claude, 73% left, 7d 59%").assertExists()
        compose.onAllNodesWithText("73% left").onFirst().assertExists()
        compose.onNodeWithText("Usage inspector", useUnmergedTree = true).assertExists()
    }
    @Test fun lightThemeRenders() {
        compose.setContent { NotchTheme("light") { NotchApp() } }
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onAllNodesWithText("Claude").onFirst().assertExists()
        screenshot("08-light-overview")
    }
    @Test @Config(qualifiers = "w320dp-h640dp-hdpi") fun smallScreenRemainsNavigable() {
        compose.setContent { NotchTheme("dark") { NotchApp() } }
        compose.onNodeWithText("Scan QR code").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Settings").performClick()
        scrollTo("24-hour clock"); compose.onNodeWithContentDescription("24-hour clock").performClick().assertIsOn()
        scrollTo("Reduce motion"); compose.onNodeWithContentDescription("Reduce motion").performClick().assertIsOn()
        scrollTo("Reset alerts"); compose.onNodeWithContentDescription("Reset alerts").assertIsOff()
        screenshot("05-small-screen-settings")
    }
    @Test @Config(qualifiers = "w840dp-h1100dp-xhdpi") fun tabletShowsProviders() {
        compose.setContent { NotchTheme("dark") { NotchApp() } }
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onAllNodesWithText("Claude").onFirst().assertIsDisplayed()
        screenshot("06-tablet")
    }

    private fun fixture(now: Long, claudeReset: Long, status: String = "Ok") =
        """{"schema":1,"generatedAt":$now,"providers":[{"id":"claude","name":"Claude","status":"$status","session":"five_hour","weekly":"seven_day","windows":[{"id":"five_hour","label":"Current session","used":0.27,"at":$now,"reset":$claudeReset,"points":[]},{"id":"seven_day","label":"All models","used":0.41,"at":$now,"reset":${now + 172800000},"points":[]}]},{"id":"codex","name":"Codex","status":"Ok","windows":[{"id":"codex-primary","label":"5-hour limit","used":0.16,"at":$now,"reset":${now + 7200000},"points":[]},{"id":"codex-secondary","label":"Weekly limit","used":0.49,"at":$now,"reset":${now + 400000000},"points":[]}]},{"id":"gemini","name":"Gemini","status":"NeedsAuth","windows":[],"statusText":"Sign in to Gemini CLI."}]}"""

    @Test fun widgetsRenderAtEverySize() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val now = System.currentTimeMillis()
        val snapshot = Snapshot.parse(fixture(now, now + 7_200_000))
        val density = context.resources.displayMetrics.density
        val sizes = listOf("1x1" to (70f to 70f), "2x1" to (150f to 70f), "4x1" to (330f to 90f), "1x3" to (80f to 260f), "2x2" to (160f to 160f), "4x2" to (330f to 170f), "4x3" to (330f to 250f), "5x5" to (420f to 460f))
        for (dark in listOf(true, false)) for (focus in listOf(false, true)) for ((name, size) in sizes) {
            val input = WidgetRenderer.Input(snapshot, true, "PC sync just now", true, false, now, dark, focus, "codex", false)
            val out = WidgetRenderer.render(context, WidgetRenderer.Frame(size.first, size.second, density), input)
            assertTrue("$name drew something", (0 until out.bitmap.width step 3).any { x -> (0 until out.bitmap.height step 3).any { y -> out.bitmap.getPixel(x, y) != 0 } })
            assertTrue(out.bitmap.byteCount <= WidgetRenderer.MAX_BITMAP_BYTES)
            assertTrue(out.description, out.description.contains(if (focus) "Codex" else "Claude"))
            if (!dark) continue
            val file = File("build/screenshots/widget-${if (focus) "focus" else "overview"}-$name.png"); file.parentFile!!.mkdirs()
            val framed = Bitmap.createBitmap(out.bitmap.width, out.bitmap.height, Bitmap.Config.ARGB_8888)
            Canvas(framed).apply { drawColor(0xFF121519.toInt()); drawBitmap(out.bitmap, 0f, 0f, null) }
            file.outputStream().use { framed.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
    @Test fun widgetSummaryUsesRemainingAndRenewedStates() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val now = System.currentTimeMillis()
        // Readings from 10 minutes ago: Claude's session reset 5 minutes ago; Codex resets later.
        val renewed = Snapshot.parse(fixture(now - 600_000, now - 300_000))
        val input = WidgetRenderer.Input(renewed, true, "PC offline · synced 2h ago", true, false, now, true, false, null, true)
        val out = WidgetRenderer.render(context, WidgetRenderer.Frame(330f, 250f, 2f), input)
        assertTrue(out.description, out.description.contains("Claude Current session: renewed"))
        assertTrue(out.description, out.description.contains("Codex 5-hour limit: 84 percent left"))
        assertTrue(out.description, out.description.contains("Gemini: Sign in to Gemini CLI. "))
        assertFalse(out.description, out.description.contains(".."))
        assertTrue(out.full)
    }
    @Test fun remoteViewsApplyOnTheLauncherSide() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val now = System.currentTimeMillis()
        context.getSharedPreferences("usage", 0).edit().putString("snapshot", fixture(now, now + 7_200_000)).putLong("sync", now).commit()
        val options = android.os.Bundle().apply { putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 250); putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 180); putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 150); putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 330) }
        val view = buildWidgetViews(context, 99, false, options).apply(context, android.widget.FrameLayout(context))
        val image = view.findViewById<android.widget.ImageView>(R.id.widget_image)
        assertNotNull(image.drawable)
        assertTrue(image.contentDescription.contains("Claude"))
        context.getSharedPreferences("display", 0).edit().putString("widget-100", "codex").commit()
        val focus = buildWidgetViews(context, 100, true, options).apply(context, android.widget.FrameLayout(context))
        assertTrue(focus.findViewById<android.widget.ImageView>(R.id.widget_image).contentDescription.contains("Codex"))
    }

    /** Writes the widget picker previews from sample data with the real renderer (copied into res/drawable-nodpi). */
    @Test fun widgetPickerPreviews() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val sample = demoSnapshot()
        for ((name, focus, size) in listOf(Triple("widget_preview", false, 330f to 200f), Triple("widget_preview_focus", true, 170f to 170f))) {
            val out = WidgetRenderer.render(context, WidgetRenderer.Frame(size.first, size.second, 2f), WidgetRenderer.Input(sample, true, "PC sync just now", true, false, sample.generatedAt, true, focus, "claude", false))
            val framed = Bitmap.createBitmap(out.bitmap.width, out.bitmap.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(framed); val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2121519.toInt() }
            canvas.drawRoundRect(0f, 0f, framed.width.toFloat(), framed.height.toFloat(), 48f, 48f, paint); canvas.drawBitmap(out.bitmap, 0f, 0f, null)
            val file = File("build/previews/$name.png"); file.parentFile!!.mkdirs(); file.outputStream().use { framed.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
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
