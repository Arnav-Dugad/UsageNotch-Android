package io.github.arnavdugad.usagenotch

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.kyant.shapes.RoundedRectangle

/**
 * How surfaces are drawn. [Glass] is liquid glass: surfaces sample the ambient light, bend it and catch a specular rim.
 * [Classic] is the calm grouped style of iOS settings: solid cards on a grouped background, continuous corners, hairline
 * separators, and frosted (blur only) floating bars. Every component reads the style, so switching is instant.
 */
enum class UiStyle { Glass, Classic;
    companion object { fun of(key: String?) = if (key == "classic") Classic else Glass }
    val key get() = if (this == Classic) "classic" else "glass"
}
val LocalStyle = staticCompositionLocalOf { UiStyle.Glass }

/** The Classic palette: iOS system grouped backgrounds, elevated cards, labels and fills, with the system blue accent. */
val ClassicDarkTokens = Tokens(true, Color(0xFF000000), Color(0xFF000000), Color(0xFF1C1C1E), Color(0xFF2C2C2E), Color(0xFF38383A),
    Color(0xFFFFFFFF), Color(0x99EBEBF5), Color(0x4DEBEBF5), Color(0xFF0A84FF), Color(0xFFFFFFFF), Color(0x3D787880), Color(0xFFFFFFFF))
val ClassicLightTokens = Tokens(false, Color(0xFFF2F2F7), Color(0xFFF2F2F7), Color(0xFFFFFFFF), Color(0xFFF2F2F7), Color(0xFFC6C6C8),
    Color(0xFF000000), Color(0x993C3C43), Color(0x4D3C3C43), Color(0xFF007AFF), Color(0xFFFFFFFF), Color(0x29787880), Color(0xFF000000))

object ClassicShapes {
    const val CARD_DP = 18
    val card = RoundedRectangle(CARD_DP.dp)
    val control = RoundedRectangle(12.dp)
    val segment = RoundedRectangle(9.dp)
    val thumb = RoundedRectangle(7.dp)
    val bar = RoundedRectangle(28.dp)
}

/** Fills for controls and frosted bars in the Classic style (iOS secondary system fill and thin material). */
object ClassicFills {
    fun control(dark: Boolean) = if (dark) Color(0x3D787880) else Color(0x1F787880)
    fun thumb(dark: Boolean) = if (dark) Color(0xFF636366) else Color(0xFFFFFFFF)
    fun inner(dark: Boolean) = if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7)
    /** Frosted bar tint over a blurred backdrop (Android 12+), and the solid fallback below that. */
    fun frosted(dark: Boolean) = if (dark) Color(0xB31C1C1E) else Color(0xC7F9F9F9)
    fun frostedFallback(dark: Boolean) = if (dark) Color(0xF21C1C1E) else Color(0xF5F9F9F9)
}

/** Card radius for the current style when a caller doesn't choose one. */
fun cardRadius(style: UiStyle): Dp = if (style == UiStyle.Classic) ClassicShapes.CARD_DP.dp else 26.dp
