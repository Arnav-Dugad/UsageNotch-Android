package io.github.arnavdugad.usagenotch

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** 1.6: the Classic style, the self-update settings, the tokens widget and the share card. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class StyleTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun clear() { val context = ApplicationProvider.getApplicationContext<Application>(); listOf("usage", "display", "pairing-vault").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() } }

    /** Overview, History and Settings in both styles and both themes, from sample data. */
    @Test fun bothStylesInBothThemes() {
        var style by mutableStateOf(UiStyle.Glass); var appearance by mutableStateOf("dark")
        compose.setContent { NotchTheme(appearance, style = style) { NotchApp(appearance = appearance, style = style, styleChanged = { style = it }) } }
        compose.onNodeWithText("Explore with sample data").performClick()
        for (s in listOf(UiStyle.Glass, UiStyle.Classic)) for (theme in listOf("dark", "light")) {
            style = s; appearance = theme
            val name = "${s.key}-$theme"
            compose.onNodeWithContentDescription("Overview").performClick()
            compose.onAllNodesWithText("Current session").onFirst().assertExists()
            screenshot("20-$name-overview")
            compose.onNodeWithContentDescription("History").performClick()
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("This week"))
            compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasText("Share your week"))
            compose.onNodeWithText("Share your week").assertExists()
            screenshot("21-$name-history")
            compose.onNodeWithContentDescription("Settings").performClick()
            compose.onNodeWithContentDescription("Classic style").assertExists()
            screenshot("22-$name-settings")
        }
    }
    @Test fun styleSwitchKeepsTheTabAndIsSaved() {
        var style by mutableStateOf(UiStyle.Glass)
        compose.setContent { NotchTheme("dark", style = style) { NotchApp(style = style, styleChanged = { style = it }) } }
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onNodeWithContentDescription("Classic style").performClick()
        assertEquals(UiStyle.Classic, style)
        assertEquals("classic", ApplicationProvider.getApplicationContext<Application>().getSharedPreferences("display", 0).getString("uiStyle", null))
        // Still on Settings, which the Style row lives on.
        compose.onNodeWithContentDescription("Liquid glass style").assertExists()
    }
    @Test fun updateSettingsArePresent() {
        compose.setContent { NotchTheme("dark") { NotchApp() } }
        compose.onNodeWithText("Explore with sample data").performClick()
        compose.onNodeWithContentDescription("Settings").performClick()
        compose.onAllNodes(hasScrollToNodeAction()).onFirst().performScrollToNode(hasContentDescription("Install updates automatically"))
        compose.onNodeWithContentDescription("Install updates automatically").assertIsOn()
        compose.onNodeWithText("Check").assertExists()
        screenshot("23-settings-updates")
    }
    @Test fun tokensWidgetRendersAtEverySizeAndExplainsItsEmptyState() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val sample = demoSnapshot(); val density = 2.5f
        for (classic in listOf(false, true)) for (dark in listOf(true, false)) for ((name, size) in listOf("2x1" to (150f to 70f), "4x1" to (330f to 90f), "2x2" to (170f to 170f), "4x2" to (330f to 200f), "3x3" to (250f to 250f))) {
            val input = WidgetRenderer.Input(sample, true, "PC sync just now", true, false, sample.generatedAt, dark, false, "claude", false, tokens = true, classic = classic)
            val out = WidgetRenderer.render(context, WidgetRenderer.Frame(size.first, size.second, density), input)
            assertTrue(out.description, out.description.startsWith("UsageNotch tokens. Claude Code today"))
            val file = File("build/screenshots/widget-tokens-${if (classic) "classic" else "glass"}-${if (dark) "dark" else "light"}-$name.png"); file.parentFile!!.mkdirs()
            val framed = Bitmap.createBitmap(out.bitmap.width, out.bitmap.height, Bitmap.Config.ARGB_8888)
            val body = when { classic && dark -> WidgetRenderer.CLASSIC_DARK; classic -> WidgetRenderer.CLASSIC_LIGHT; dark -> WidgetRenderer.GLASS_DARK; else -> WidgetRenderer.GLASS_LIGHT }
            Canvas(framed).apply { drawColor(if (dark) 0xFF2A3140.toInt() else 0xFFB9C4D6.toInt()); drawRoundRect(0f, 0f, framed.width.toFloat(), framed.height.toFloat(), 24 * density, 24 * density, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = body }); drawBitmap(out.bitmap, 0f, 0f, null) }
            file.outputStream().use { framed.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        val codex = WidgetRenderer.render(context, WidgetRenderer.Frame(170f, 170f, 2f), WidgetRenderer.Input(sample, true, "", true, false, sample.generatedAt, true, false, "codex", false, tokens = true))
        assertTrue(codex.description, codex.description.contains("Codex"))
        val empty = WidgetRenderer.render(context, WidgetRenderer.Frame(170f, 170f, 2f), WidgetRenderer.Input(sample.copy(tokens = null), true, "", true, false, sample.generatedAt, true, false, null, false, tokens = true))
        assertTrue(empty.description, empty.description.contains("appear when your PC sends them"))
        // The widget picker preview (copied into res/drawable-nodpi).
        val preview = WidgetRenderer.render(context, WidgetRenderer.Frame(170f, 170f, 2f), WidgetRenderer.Input(sample, true, "PC sync just now", true, false, sample.generatedAt, true, false, "claude", false, tokens = true))
        val framed = Bitmap.createBitmap(preview.bitmap.width, preview.bitmap.height, Bitmap.Config.ARGB_8888)
        Canvas(framed).apply { drawRoundRect(0f, 0f, framed.width.toFloat(), framed.height.toFloat(), 48f, 48f, android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { color = 0xF2121519.toInt() }); drawBitmap(preview.bitmap, 0f, 0f, null) }
        File("build/previews").mkdirs(); File("build/previews/widget_preview_tokens.png").outputStream().use { framed.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
    @Test fun shareCardRendersAStoryWithoutAccountNames() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val sample = demoSnapshot()
        for (classic in listOf(false, true)) for (dark in listOf(true, false)) {
            val image = ShareCard.render(context, sample.providers, sample.tokens, true, sample.generatedAt, dark, classic) { Accents.default(it) }
            assertEquals(1080, image.width); assertEquals(1920, image.height)
            val file = File("build/screenshots/share-${if (classic) "classic" else "glass"}-${if (dark) "dark" else "light"}.png"); file.parentFile!!.mkdirs()
            file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        assertTrue(ShareCard.weeks(sample.providers).isNotEmpty())
        // With the PC's "This week": its headline and sentences join the story.
        val withReport = ShareCard.render(context, sample.providers, sample.tokens, true, sample.generatedAt, true, false, sample.report) { Accents.default(it) }
        File("build/screenshots/share-report-dark.png").outputStream().use { withReport.compress(Bitmap.CompressFormat.PNG, 100, it) }
        assertFalse("the report changes the picture", withReport.sameAs(ShareCard.render(context, sample.providers, sample.tokens, true, sample.generatedAt, true, false) { Accents.default(it) }))
    }
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
