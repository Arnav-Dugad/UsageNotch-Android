package io.github.arnavdugad.usagenotch

import android.os.Build
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import com.kyant.backdrop.Backdrop
import com.kyant.backdrop.drawBackdrop
import com.kyant.backdrop.effects.blur
import com.kyant.backdrop.effects.lens
import com.kyant.backdrop.effects.vibrancy
import com.kyant.backdrop.shadow.Shadow
import com.kyant.shapes.Capsule
import com.kyant.shapes.RoundedRectangle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Liquid glass, after iOS: surfaces sample what is behind them, bend it at their edges (Android 13+), blur and
 * saturate it (Android 12+), and carry a specular rim. Cards sample the ambient background; floating bars and their
 * controls sample the whole screen, so content scrolling beneath them refracts through the glass. Older Android
 * versions get the same shapes with a more opaque tint, so text always stays readable.
 */
class GlassBackdrops(val ambient: Backdrop, val screen: Backdrop)
val LocalGlass = staticCompositionLocalOf<GlassBackdrops?> { null }

enum class GlassLevel { Card, Bar, Control }

object GlassShapes {
    val card = RoundedRectangle(26.dp)
    val inner = RoundedRectangle(18.dp)
    val capsule = Capsule()
}

// Robolectric's host graphics have no AGSL runtime; tests draw the tinted fallback.
private val effectsSupported = Build.VERSION.SDK_INT >= 31 && Build.FINGERPRINT != "robolectric"

/** Glass behind this element. Without a backdrop (for example in a dialog) it falls back to a tinted surface. */
@Composable fun Modifier.glass(shape: androidx.compose.ui.graphics.Shape = GlassShapes.card, level: GlassLevel = GlassLevel.Card, tint: Color = Color.Unspecified): Modifier {
    val t = LocalTokens.current; val glass = LocalGlass.current
    val surface = glassSurface(t, level, tint)
    if (glass == null) return this.clip(shape).background(fallbackSurface(t, level, tint))
    val backdrop = if (level == GlassLevel.Card) glass.ambient else glass.screen
    val density = LocalDensity.current
    val (blurDp, lensHeight, lensAmount) = when (level) { GlassLevel.Card -> Triple(14.dp, 10.dp, 16.dp); GlassLevel.Bar -> Triple(8.dp, 20.dp, 40.dp); GlassLevel.Control -> Triple(4.dp, 14.dp, 28.dp) }
    val highlightAlpha = if (t.dark) .55f else .95f
    val shadowColor = Color.Black.copy(alpha = if (t.dark) .30f else .10f)
    return this.drawBackdrop(
        backdrop = backdrop,
        shape = { shape },
        effects = {
            if (effectsSupported) {
                vibrancy()
                blur(with(density) { blurDp.toPx() })
                lens(with(density) { lensHeight.toPx() }, with(density) { lensAmount.toPx() }, depthEffect = level != GlassLevel.Card, chromaticAberration = level == GlassLevel.Bar)
            }
        },
        // The specular rim is drawn here with a plain gradient, so it renders on every canvas (the library's shader
        // highlight needs hardware rendering).
        highlight = null,
        shadow = { Shadow(radius = if (level == GlassLevel.Control) 10.dp else 26.dp, color = shadowColor) },
        onDrawSurface = { drawRect(if (effectsSupported) surface else fallbackSurface(t, level, tint)) },
        onDrawFront = { specularRim(shape, highlightAlpha, if (level == GlassLevel.Card) .8.dp.toPx() else 1.1.dp.toPx()) },
    )
}

/** A light catching the glass edge: bright at the top-left, fading along the sides, with a faint return at the bottom-right. */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.specularRim(shape: androidx.compose.ui.graphics.Shape, alpha: Float, width: Float) {
    val brush = Brush.linearGradient(
        0f to Color.White.copy(alpha = alpha), .32f to Color.White.copy(alpha = alpha * .18f), .68f to Color.White.copy(alpha = alpha * .06f), 1f to Color.White.copy(alpha = alpha * .42f),
        start = Offset.Zero, end = Offset(size.width, size.height),
    )
    // Inset by half the stroke so the rim sits inside the clip.
    val half = width / 2
    inset(half, half, half, half) {
        drawOutline(shape.createOutline(size, layoutDirection, this), brush, style = androidx.compose.ui.graphics.drawscope.Stroke(width))
    }
}

/** The tint laid over the sampled backdrop: light enough to stay glassy, dense enough for text. */
private fun glassSurface(t: Tokens, level: GlassLevel, tint: Color): Color {
    val base = when (level) {
        // Dark glass is smoked, as on iOS, so secondary text keeps its contrast over bright ambient light.
        GlassLevel.Card -> if (t.dark) Color(0x590D1016) else Color(0x99FFFFFF)
        GlassLevel.Bar -> if (t.dark) Color(0x590B0E14) else Color(0xA6FFFFFF)
        GlassLevel.Control -> if (t.dark) Color(0x1FFFFFFF) else Color(0x73FFFFFF)
    }
    return if (tint.isSpecified) tint.copy(alpha = tint.alpha * .22f).compositeOver(base) else base
}
private fun fallbackSurface(t: Tokens, level: GlassLevel, tint: Color): Color {
    val base = when (level) {
        GlassLevel.Card -> if (t.dark) Color(0xE6171B24) else Color(0xEBFFFFFF)
        GlassLevel.Bar -> if (t.dark) Color(0xF2121620) else Color(0xF5FFFFFF)
        GlassLevel.Control -> if (t.dark) Color(0xFF242A35) else Color(0xFFF1F3F7)
    }
    return if (tint.isSpecified) tint.copy(alpha = .14f).compositeOver(base) else base
}

/** A glass card. [content] is drawn above the glass. */
@Composable fun GlassPanel(modifier: Modifier = Modifier, shape: androidx.compose.ui.graphics.Shape = GlassShapes.card, level: GlassLevel = GlassLevel.Card, tint: Color = Color.Unspecified, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.glass(shape, level, tint), content = content)
}

/**
 * The ambient light behind the glass: soft coloured fields that drift slowly, tinted by your providers' accents.
 * Updated about 15 times a second, which is smooth for motion this slow and spares the battery.
 */
@Composable fun AmbientBackground(colors: List<Color>, animate: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    var phase by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect
        val start = System.nanoTime() - (phase * 1e9).toLong()
        while (isActive) { phase = ((System.nanoTime() - start) / 1e9).toFloat(); delay(66) }
    }
    val palette = (colors + listOf(t.accent, Color(0xFF7A5CFF), Color(0xFF2E9BFF))).distinct().take(4)
    Canvas(modifier.fillMaxSize()) {
        drawRect(Brush.verticalGradient(if (t.dark) listOf(Color(0xFF06080C), Color(0xFF0C0F16)) else listOf(Color(0xFFF4F6FA), Color(0xFFE6EAF2))))
        val w = size.width; val h = size.height
        if (w <= 0f || h <= 0f) return@Canvas
        palette.forEachIndexed { i, color ->
            val speed = .045f + i * .013f
            val cx = w * (.2f + .6f * ((i * .37f) % 1f)) + w * .16f * sin(phase * speed * 6.283f + i * 1.7f)
            val cy = h * (.12f + .22f * i) + h * .07f * cos(phase * speed * 5.1f + i * 2.3f)
            val radius = w * (.62f + .1f * ((i + 1) % 3))
            val alpha = if (t.dark) .32f else .30f
            drawCircle(Brush.radialGradient(listOf(color.copy(alpha = alpha), color.copy(alpha = alpha * .35f), Color.Transparent), Offset(cx, cy), radius), radius, Offset(cx, cy))
        }
    }
}

/** A pressable glass capsule that springs down under the finger, like iOS controls. */
@Composable fun GlassButton(onClick: () -> Unit, modifier: Modifier = Modifier, prominent: Boolean = false, enabled: Boolean = true, contentPadding: PaddingValues = PaddingValues(horizontal = 18.dp, vertical = 12.dp), content: @Composable RowScope.() -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }; val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) .94f else 1f, spring(dampingRatio = .55f, stiffness = 700f), label = "Press")
    Row(
        modifier.graphicsLayer { scaleX = scale; scaleY = scale; alpha = if (enabled) 1f else .45f }
            .glass(GlassShapes.capsule, GlassLevel.Control, if (prominent) t.accent else Color.Unspecified)
            .clickable(source, null, enabled = enabled, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }
            .padding(contentPadding),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center,
    ) { CompositionLocalProvider(androidx.compose.material3.LocalContentColor provides if (prominent) t.accent else t.text) { content() } }
}

@Composable fun GlassIconButton(icon: ImageVector, description: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, tint: Color = LocalTokens.current.text) {
    GlassButton(onClick, modifier.size(44.dp), enabled = enabled, contentPadding = PaddingValues(0.dp)) { Icon(icon, description, tint = tint, modifier = Modifier.size(21.dp)) }
}

/** A glass chip for filters and pickers. */
@Composable fun GlassChip(label: String, selected: Boolean, modifier: Modifier = Modifier, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val t = LocalTokens.current
    val color by animateColorAsState(if (selected) t.text else t.muted, label = "Chip")
    Row(
        modifier.clip(GlassShapes.capsule).then(if (selected) Modifier.glass(GlassShapes.capsule, GlassLevel.Control, t.accent) else Modifier.background(if (t.dark) Color(0x0DFFFFFF) else Color(0x0D000000)))
            .clickable(role = Role.RadioButton) { onClick() }.semantics { this.selected = selected }.padding(horizontal = 13.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        leading?.let { it(); Spacer(Modifier.width(7.dp)) }
        Text(label, fontSize = 13.sp, color = color, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
    }
}

/**
 * A segmented control whose selection is a drop of glass that slides between options with a spring and stretches
 * while it moves.
 */
@Composable fun GlassSegmented(options: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, reduced: Boolean = false, descriptions: List<String>? = null) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    var width by remember { mutableIntStateOf(0) }
    val position = remember { Animatable(selected.toFloat()) }
    LaunchedEffect(selected, reduced) { if (reduced) position.snapTo(selected.toFloat()) else position.animateTo(selected.toFloat(), spring(dampingRatio = .72f, stiffness = 420f)) }
    val stretch = (1f + abs(position.velocity) * .08f).coerceAtMost(1.35f)
    val density = LocalDensity.current
    Box(modifier.clip(GlassShapes.capsule).background(if (t.dark) Color(0x14FFFFFF) else Color(0x0F000000)).onSizeChanged { width = it.width }.padding(3.dp)) {
        if (width > 0) {
            val cell = with(density) { ((width - 6.dp.roundToPx()) / options.size.toFloat()).toDp() }
            Box(Modifier.width(cell).height(34.dp).offset { IntOffset((position.value * cell.toPx()).roundToInt(), 0) }
                .graphicsLayer { scaleX = stretch; scaleY = 1f / stretch.coerceAtMost(1.15f) }
                .glass(GlassShapes.capsule, GlassLevel.Control))
        }
        Row(Modifier.height(34.dp)) {
            options.forEachIndexed { i, label ->
                val on = i == selected
                Box(Modifier.weight(1f).fillMaxHeight().clip(GlassShapes.capsule).clickable(role = Role.RadioButton) { if (!on) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(i) } }
                    .semantics { this.selected = on; descriptions?.getOrNull(i)?.let { contentDescription = it } }, contentAlignment = Alignment.Center) {
                    Text(label, fontSize = 13.sp, color = if (on) t.text else t.muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
                }
            }
        }
    }
}

data class TabItem(val label: String, val icon: ImageVector)

/**
 * The floating tab bar: a glass capsule with a lens-like drop that marks the current tab. The drop glides with a spring,
 * stretches with its speed, swells while you touch the bar, and can be dragged across tabs, as on iOS 26.
 */
@Composable fun GlassTabBar(items: List<TabItem>, selected: Int, onSelect: (Int) -> Unit, reduced: Boolean, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    var width by remember { mutableIntStateOf(0) }
    val position = remember { Animatable(selected.toFloat()) }
    var touching by remember { mutableStateOf(false) }
    var dragging by remember { mutableStateOf(false) }
    LaunchedEffect(selected, reduced) { if (!dragging) { if (reduced) position.snapTo(selected.toFloat()) else position.animateTo(selected.toFloat(), spring(dampingRatio = .68f, stiffness = 380f)) } }
    val swell by animateFloatAsState(if (touching && !reduced) 1.14f else 1f, spring(dampingRatio = .5f, stiffness = 500f), label = "Swell")
    val stretch = (1f + abs(position.velocity) * .09f).coerceAtMost(1.4f)
    val inner = 6.dp
    Box(modifier.height(66.dp).glass(GlassShapes.capsule, GlassLevel.Bar).onSizeChanged { width = it.width }.padding(inner)
        .pointerInput(items.size) {
            val cell = (size.width.toFloat()) / items.size
            detectTapGestures(onPress = { touching = true; tryAwaitRelease(); touching = false })
        }
        .pointerInput(items.size) {
            val cell = size.width.toFloat() / items.size
            var last = selected
            detectHorizontalDragGestures(
                onDragStart = { dragging = true; touching = true },
                onDragEnd = {
                    val target = position.value.roundToInt().coerceIn(0, items.size - 1)
                    dragging = false; touching = false
                    scope.launch { position.animateTo(target.toFloat(), spring(dampingRatio = .68f, stiffness = 380f)) }
                    if (target != selected) onSelect(target)
                },
                onDragCancel = { dragging = false; touching = false; scope.launch { position.animateTo(selected.toFloat(), spring()) } },
            ) { change, amount ->
                change.consume()
                val next = (position.value + amount / cell).coerceIn(-.15f, items.size - .85f)
                scope.launch { position.snapTo(next) }
                val nearest = next.roundToInt().coerceIn(0, items.size - 1)
                if (nearest != last) { last = nearest; haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) }
            }
        }) {
        if (width > 0) {
            val cell = with(density) { ((width - (inner * 2).roundToPx()) / items.size.toFloat()).toDp() }
            Box(Modifier.width(cell).fillMaxHeight().offset { IntOffset((position.value * cell.toPx()).roundToInt(), 0) }
                .graphicsLayer { scaleX = stretch * swell; scaleY = swell / stretch.coerceAtMost(1.18f) }
                .glass(GlassShapes.capsule, GlassLevel.Control, t.accent))
        }
        Row(Modifier.fillMaxSize()) {
            items.forEachIndexed { index, item ->
                val near = (1f - abs(position.value - index)).coerceIn(0f, 1f)
                val color = androidx.compose.ui.graphics.lerp(t.muted, t.accent, near)
                Column(Modifier.weight(1f).fillMaxHeight().clip(GlassShapes.capsule)
                    .clickable(role = Role.Tab) { if (index != selected) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onSelect(index) } }
                    .semantics { this.selected = index == selected; contentDescription = item.label },
                    horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Icon(item.icon, null, tint = color, modifier = Modifier.size(22.dp).graphicsLayer { val s = 1f + .08f * near; scaleX = s; scaleY = s })
                    Spacer(Modifier.height(3.dp))
                    Text(item.label, color = color, fontSize = 10.5.sp, fontWeight = if (index == selected) FontWeight.SemiBold else FontWeight.Medium)
                }
            }
        }
    }
}
