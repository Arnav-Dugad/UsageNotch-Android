package io.github.arnavdugad.usagenotch

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
        compose.onAllNodesWithText("Import file").onFirst().assertExists()
        screenshot("01-onboarding")
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onNodeWithText("Sample data").assertIsDisplayed()
        compose.onAllNodesWithText("Claude").onFirst().assertExists()
        compose.onAllNodesWithText("Current session").onFirst().assertExists()
        screenshot("02-overview")
        // No taglines: the screen starts with content.
        compose.onNodeWithText("Your AI, at a glance.").assertDoesNotExist()
        compose.onNodeWithContentDescription("History").performClick()
        compose.onNodeWithText("Daily usage").assertExists()
        compose.onNodeWithText("Streak").assertExists()
        screenshot("03-history")
        compose.onNodeWithText("30d").performClick()
        scrollToNode(hasText("Calendar")); compose.onNodeWithText("Calendar").assertExists()
        scrollToNode(hasText("Side by side")); compose.onNodeWithText("Side by side").assertExists()
        screenshot("03b-history-calendar")
        scrollToNode(hasText("Busiest hours")); compose.onNodeWithText("Busiest hours").assertExists()
        compose.onNodeWithContentDescription("Widgets").performClick()
        compose.onNodeWithText("Quick Settings tile").assertExists()
        screenshot("03-widgets")
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithText("Pair your Windows PC").assertExists()
        screenshot("04-settings")
        // Scroll past it so the floating tab bar doesn't cover the row.
        scrollTo("Check for updates"); compose.onNodeWithContentDescription("Show remaining").performClick()
        compose.onNodeWithContentDescription("Show remaining").assertIsOff()
    }
    @Test fun dockCellsShowPercentagesAndWeeklyFigures() {
        compose.setContent { NotchTheme("dark") { NotchApp() } }
        compose.onNodeWithText("Explore with sample data").performClick()
        // Sample: Claude session 27% used, weekly 41% used.
        compose.onNodeWithContentDescription("Claude, 73% left, 7d 59%. Open details").assertExists()
        compose.onAllNodesWithText("73% left").onFirst().assertExists()
        compose.onNodeWithText("At this pace: 61.4% used at reset").assertExists()
    }
    @Test fun dockRingOpensTheProviderViewAndBackReturns() {
        compose.setContent { NotchTheme("dark") { NotchApp() } }
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onNodeWithContentDescription("Claude, 73% left, 7d 59%. Open details").performClick()
        compose.onNodeWithText("Pace").assertExists()
        compose.onNodeWithText("About 61% used by the reset").assertExists()
        compose.onNodeWithText("Last 24 hours").assertExists()
        screenshot("07-provider-detail")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Sample data").assertIsDisplayed()
        compose.onNodeWithText("Pace").assertDoesNotExist()
    }
    @Test fun notificationAndThemeSettingsArePresent() {
        compose.setContent { NotchTheme("dark") { NotchApp() } }
        compose.onNodeWithContentDescription("Settings").performClick()
        scrollTo("Wallpaper colors"); compose.onNodeWithContentDescription("Wallpaper colors").assertIsOff()
        scrollTo("Usage alerts"); compose.onNodeWithContentDescription("Usage alerts").assertIsOff()
        scrollTo("Live countdown"); compose.onNodeWithContentDescription("Live countdown").assertIsOff()
        scrollTo("Weekly recap"); compose.onNodeWithContentDescription("Weekly recap").assertIsOff()
        screenshot("04b-settings-notifications")
    }
    @Test fun wallpaperColorsKeepTheCurrentPage() {
        var wallpaper by androidx.compose.runtime.mutableStateOf(false)
        compose.setContent { NotchTheme("dark", wallpaper) { NotchApp(wallpaper = wallpaper, wallpaperChanged = { wallpaper = it }) } }
        compose.onNodeWithContentDescription("Settings").performClick()
        scrollTo("Wallpaper colors"); compose.onNodeWithContentDescription("Wallpaper colors").performClick()
        compose.waitForIdle()
        assertTrue(wallpaper)
        compose.onNodeWithContentDescription("Wallpaper colors").assertIsOn()
        compose.onNodeWithContentDescription("Dark theme").assertExists()
    }
    @Test fun budgetFromTheProviderViewShowsOnTheCard() {
        compose.setContent { NotchTheme("dark") { NotchApp() } }
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onNodeWithContentDescription("Claude, 73% left, 7d 59%. Open details").performClick()
        compose.onNodeWithContentDescription("Budget").performScrollTo().performClick()
        compose.onNodeWithText("Stay under").assertExists()
        compose.onAllNodesWithText("60%").onFirst().assertExists()
        // 27% used, +15.6 points an hour, projected 61.4% at the reset: just over a 60% budget.
        compose.onAllNodesWithText("On pace for 61% by the reset").onFirst().assertExists()
        screenshot("07b-budget")
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onAllNodesWithText("On pace for 61% by the reset").onFirst().assertExists()
        // Sample data never changes your real settings.
        assertTrue(Budgets.all(ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("display", 0)).isEmpty())
    }
    @Test fun historyBarOpensThatDay() {
        compose.setContent { NotchTheme("dark") { NotchApp() } }
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onNodeWithContentDescription("History").performClick()
        val today = java.time.LocalDate.now().toString()
        compose.onNode(SemanticsMatcher("click label Open $today") { it.config.getOrElseNullable(androidx.compose.ui.semantics.SemanticsActions.OnClick) { null }?.label == "Open $today" }).performClick()
        compose.onNodeWithText("of the limit used that day").assertExists()
        compose.onNodeWithText("Your average").assertExists()
        screenshot("03c-history-day")
        compose.onNodeWithContentDescription("Close").performClick()
        compose.onNodeWithText("of the limit used that day").assertDoesNotExist()
    }
    @Test fun tabBarSelectsTabs() {
        compose.setContent { NotchTheme("dark") { NotchApp() } }
        compose.onNodeWithContentDescription("Settings").performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Overview").assertIsNotSelected()
        compose.onNodeWithContentDescription("Overview").performClick().assertIsSelected()
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

    @Test fun widgetShowsASpinnerWhileRefreshing() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val now = System.currentTimeMillis()
        context.getSharedPreferences("usage", 0).edit().putString("snapshot", fixture(now, now + 7_200_000)).putLong("sync", now).commit()
        context.getSharedPreferences("pairing-vault", 0).edit().commit()
        val options = android.os.Bundle().apply { putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH, 330); putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT, 250); putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 250); putInt(android.appwidget.AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH, 330) }
        val input = widgetInput(context, 7, false)
        val out = WidgetRenderer.render(context, WidgetRenderer.Frame(330f, 250f, 2f), input.copy(paired = true))
        assertTrue("a full widget keeps the refresh corner", out.refresh)
        // Small 1×1 widgets open the app instead.
        assertFalse(WidgetRenderer.render(context, WidgetRenderer.Frame(70f, 70f, 2f), input.copy(paired = true)).refresh)
        context.getSharedPreferences("display", 0).edit().putLong(UsageWidget.REFRESHING, now + 10_000).commit()
        val views = buildWidgetViews(context, 7, false, options).apply(context, android.widget.FrameLayout(context))
        assertEquals(android.view.View.GONE, views.findViewById<android.view.View>(R.id.widget_refresh).visibility)
        context.getSharedPreferences("display", 0).edit().remove(UsageWidget.REFRESHING).commit()
        val idle = buildWidgetViews(context, 7, false, options).apply(context, android.widget.FrameLayout(context))
        assertEquals(android.view.View.GONE, idle.findViewById<android.view.View>(R.id.widget_progress).visibility)
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
        // Transparent renders for the README, composited over a wallpaper there.
        for ((name, focus, size) in listOf(Triple("full", false, 330f to 250f), Triple("focus", true, 170f to 170f), Triple("row", false, 330f to 96f), Triple("one", true, 80f to 80f), Triple("column", false, 90f to 260f), Triple("focus-wide", true, 330f to 250f))) {
            for (dark in listOf(true, false)) {
                val out = WidgetRenderer.render(context, WidgetRenderer.Frame(size.first, size.second, 3f), WidgetRenderer.Input(sample, true, "PC sync just now", true, false, sample.generatedAt, dark, focus, "claude", false))
                val file = File("build/previews/raw-$name-${if (dark) "dark" else "light"}.png"); file.outputStream().use { out.bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            }
        }
    }
    /** Settings is a lazy list: items off screen are not composed until the list scrolls to them. */
    private fun scrollTo(description: String, substring: Boolean = false) = scrollToNode(hasContentDescription(description, substring = substring))
    private fun scrollToNode(matcher: SemanticsMatcher) = compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(matcher)
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
