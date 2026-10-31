package io.github.arnavdugad.usagenotch

import android.content.Context
import android.content.res.Configuration
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.drawable.Drawable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/** Usage colours, identical to the desktop Palette so rings, bars and text agree on every device. */
object Palette {
    private val ramp = DesignTokens.usageRamp
    const val WARNING = DesignTokens.WARNING
    const val DANGER = DesignTokens.DANGER
    const val LOADING = DesignTokens.LOADING
    const val UNKNOWN = DesignTokens.UNKNOWN
    const val LIVE = DesignTokens.LIVE
    const val FORECAST = DesignTokens.FORECAST
    fun usage(used: Double): Int {
        val f = used.coerceIn(0.0, 1.0)
        for (i in 1 until ramp.size) {
            if (f > ramp[i].first) continue
            val span = ramp[i].first - ramp[i - 1].first
            return lerp(ramp[i - 1].second, ramp[i].second, if (span <= 0) 0.0 else (f - ramp[i - 1].first) / span)
        }
        return ramp.last().second
    }
    fun lerp(a: Int, b: Int, t: Double): Int {
        val k = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) + (((b shr shift) and 0xFF) - ((a shr shift) and 0xFF)) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
    private fun luminance(c: Int): Double {
        fun lin(v: Int) = (v / 255.0).let { if (it <= 0.04045) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
        return 0.2126 * lin((c shr 16) and 0xFF) + 0.7152 * lin((c shr 8) and 0xFF) + 0.0722 * lin(c and 0xFF)
    }
    fun contrast(a: Int, b: Int) = (max(luminance(a), luminance(b)) + .05) / (min(luminance(a), luminance(b)) + .05)
    /** The same colour, darkened or lightened just enough to read on the surface (WCAG 4.5:1), as on the desktop. */
    fun readable(ink: Int, surface: Int, minimum: Double = 4.5): Int {
        if (contrast(ink, surface) >= minimum) return ink
        val target = if (contrast(0xFF000000.toInt(), surface) >= contrast(0xFFFFFFFF.toInt(), surface)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        var low = 0.0; var high = 1.0
        repeat(16) { val mid = (low + high) / 2; if (contrast(lerp(ink, target, mid), surface) < minimum) low = mid else high = mid }
        return lerp(ink, target, high)
    }
    fun forStatus(status: String, used: Double?): Int = when (status) {
        "Error" -> DANGER
        "NeedsAuth", "Unsupported" -> WARNING
        "Loading" -> LOADING
        else -> used?.let { usage(it) } ?: UNKNOWN
    }
}

@Immutable
data class Tokens(
    val dark: Boolean, val background: Color, val backgroundEnd: Color, val card: Color, val raised: Color, val hairline: Color,
    val text: Color, val muted: Color, val faint: Color, val accent: Color, val onAccent: Color, val track: Color, val ink: Color,
)
private fun c(argb: Int) = Color(argb)
val DarkTokens = DesignTokens.Dark.let { d -> Tokens(true, c(d.background), c(d.backgroundEnd), c(d.card), c(d.raised), c(d.hairline),
    c(d.text), c(d.muted), c(d.faint), c(d.accent), c(d.onAccent), c(d.track), c(d.ink)) }
val LightTokens = DesignTokens.Light.let { l -> Tokens(false, c(l.background), c(l.backgroundEnd), c(l.card), c(l.raised), c(l.hairline),
    c(l.text), c(l.muted), c(l.faint), c(l.accent), c(l.onAccent), c(l.track), c(l.ink)) }
val LocalTokens = staticCompositionLocalOf { DarkTokens }

/** Appearance preference: "system" follows Android, or a fixed "light"/"dark". */
fun darkFor(context: Context, appearance: String): Boolean = when (appearance) {
    "light" -> false
    "dark" -> true
    else -> (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
}

/** Surfaces taken from the wallpaper (Android 12+). Rings keep the brand logos and the usage colour ramp. */
fun dynamicTokens(scheme: androidx.compose.material3.ColorScheme, dark: Boolean) = Tokens(dark, scheme.surface, scheme.surfaceContainerLow, scheme.surfaceContainer,
    scheme.surfaceContainerHighest, scheme.outlineVariant.copy(alpha = .6f), scheme.onSurface, scheme.onSurfaceVariant, scheme.outline, scheme.primary, scheme.onPrimary,
    scheme.onSurface.copy(alpha = .14f), scheme.onSurface)

@Composable fun NotchTheme(appearance: String = "system", wallpaper: Boolean = false, content: @Composable () -> Unit) {
    val dark = when (appearance) { "light" -> false; "dark" -> true; else -> isSystemInDarkTheme() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val t: Tokens; val scheme: androidx.compose.material3.ColorScheme
    if (wallpaper && android.os.Build.VERSION.SDK_INT >= 31) {
        scheme = if (dark) androidx.compose.material3.dynamicDarkColorScheme(context) else androidx.compose.material3.dynamicLightColorScheme(context)
        t = dynamicTokens(scheme, dark)
    } else {
        t = if (dark) DarkTokens else LightTokens
        scheme = if (dark) darkColorScheme(primary = t.accent, onPrimary = t.onAccent, background = t.background, surface = t.card, surfaceContainerHigh = t.raised, onSurface = t.text, onSurfaceVariant = t.muted, outline = t.hairline)
            else lightColorScheme(primary = t.accent, onPrimary = t.onAccent, background = t.background, surface = t.card, surfaceContainerHigh = t.raised, onSurface = t.text, onSurfaceVariant = t.muted, outline = t.hairline)
    }
    // One call site for the content, so switching wallpaper colors keeps the current tab and scroll position.
    CompositionLocalProvider(LocalTokens provides t) { MaterialTheme(colorScheme = scheme, content = content) }
}

/** Brand marks: Claude and Gemini keep their own colours; OpenAI and Cursor use the theme's ink, as on the desktop. */
object Logos {
    fun resource(id: String) = when (providerKind(id)) {
        "claude", "anthropic-api" -> R.drawable.logo_claude
        "codex", "openai-api" -> R.drawable.logo_codex
        "gemini" -> R.drawable.logo_gemini
        "cursor" -> R.drawable.logo_cursor
        else -> R.drawable.ic_notch
    }
    fun colored(id: String) = providerKind(id) in setOf("claude", "anthropic-api", "gemini") || resource(id) == R.drawable.ic_notch
    fun drawable(context: Context, id: String, ink: Int): Drawable? = context.getDrawable(resource(id))?.mutate()?.also { if (!colored(id)) it.setTint(ink) }
}

/**
 * Provider accent colours: each provider's brand colour until you pick another. They tint the ambient light,
 * charts and glass; rings keep the usage ramp so their colour always means how much is used.
 */
object Accents {
    val choices = DesignTokens.accentChoices
    fun default(id: String) = DesignTokens.brand[providerKind(id)] ?: DesignTokens.brand.getValue("other")
    /** Extra accounts (`claude:work`) keep their own colour; others share their provider type's, as in 1.4. */
    private fun prefKey(id: String) = "accent-" + if (':' in id) id else providerKind(id)
    fun of(prefs: android.content.SharedPreferences, id: String) =
        prefs.getInt(prefKey(id), if (':' in id) prefs.getInt("accent-${providerKind(id)}", default(id)) else default(id))
    /** A colour chosen on this phone: saved and shared with the PC as `accent:<providerId>`. */
    fun set(prefs: android.content.SharedPreferences, id: String, color: Int?) = SharedSettings.local(prefs, "accent:${syncId(id)}", color?.let { SharedSettings.hex(it) })
    /** Writes the colour without recording a sync change; used when applying the PC's settings. */
    internal fun store(prefs: android.content.SharedPreferences, id: String, color: Int?) {
        prefs.edit().apply { if (color == null) remove(prefKey(id)) else putInt(prefKey(id), color) }.apply()
    }
    /** The provider id the PC uses: API providers are written with underscores on Windows. */
    fun syncId(id: String) = if (':' in id) id else when (providerKind(id)) { "openai-api" -> "openai_api"; "anthropic-api" -> "anthropic_api"; else -> providerKind(id) }
}
/** The accent for a provider id, supplied by the app so a change recolours everything at once. */
val LocalAccent = staticCompositionLocalOf<(String) -> Color> { { Color(Accents.default(it)) } }

/**
 * The desktop dock ring: a quiet track, a usage arc from 12 o'clock coloured by how much is used, an optional
 * inner ring for the weekly window, and the provider's logo in the centre. Drawn on a platform Canvas so the
 * app and the home-screen widgets share exactly the same rendering.
 *
 * The arc is a comet: it brightens from a faint tail to its head. A hairline arc outside the ring shows how much of
 * the window's time is left before it resets, and a short tick marks your budget.
 */
object RingPainter {
    class Spec(
        val shown: Float?, val used: Double?, val secondaryShown: Float?, val secondaryUsed: Double?,
        val status: String, val renewed: Boolean, val track: Int, val logo: Drawable?, val logoScale: Float = 0.43f,
        /** 0..1: a soft glow under the arc, pulsed by the app above 90% used (static images use 0). */
        val glow: Float = 0f,
        /** A bright head on the arc while a new reading sweeps in. */
        val head: Boolean = false,
        /** 0..1 of the window's period still to run before its reset, or null when unknown. */
        val timeLeft: Float? = null,
        /** Budget position on the arc (0..1, in the same terms as [shown]), or null. */
        val budget: Float? = null,
        val ink: Int = 0xFFFFFFFF.toInt(),
        /** Budget position on the inner (weekly) ring, or null. */
        val secondaryBudget: Float? = null,
    )
    fun draw(canvas: Canvas, cx: Float, cy: Float, size: Float, density: Float, spec: Spec) {
        if (size < 8f) return
        // Per call: widgets render on background threads while the app draws on the main thread.
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
        val stroke = (size * 0.052f).coerceIn(1.8f * density, 4.2f * density)
        val timeLeft = spec.timeLeft?.takeIf { size >= 36f * density }
        var radius = size / 2 - stroke / 2 - size * 0.045f
        if (timeLeft != null) radius -= stroke * .9f
        val shownPrimary = if (spec.renewed) null else spec.shown
        if (timeLeft != null) {
            val timeRadius = radius + stroke * 1.25f
            val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = max(1f * density, stroke * .28f) }
            timePaint.color = (spec.ink and 0x00FFFFFF) or 0x1F000000
            canvas.drawCircle(cx, cy, timeRadius, timePaint)
            val left = timeLeft.coerceIn(0f, 1f)
            if (left > 0.004f) {
                timePaint.color = (spec.ink and 0x00FFFFFF) or 0x8C000000.toInt()
                canvas.drawArc(RectF(cx - timeRadius, cy - timeRadius, cx + timeRadius, cy + timeRadius), -90f, left * 360f, false, timePaint)
            }
        }
        if (spec.glow > 0f && shownPrimary != null && shownPrimary > 0f) {
            val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = stroke * 2.2f
                color = Palette.usage(spec.used ?: 0.0); alpha = (spec.glow.coerceIn(0f, 1f) * 110).toInt()
                maskFilter = android.graphics.BlurMaskFilter(stroke * 1.8f, android.graphics.BlurMaskFilter.Blur.NORMAL)
            }
            canvas.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), -90f, shownPrimary.coerceIn(0f, 1f) * 360f, false, glow)
        }
        ring(canvas, paint, cx, cy, radius, stroke, spec.track, shownPrimary, spec.used)
        spec.budget?.takeIf { it in 0f..1f && !spec.renewed }?.let { b ->
            val a = Math.toRadians(-90.0 + b * 360.0)
            val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = max(1.2f * density, stroke * .34f); color = spec.ink }
            val r0 = radius - stroke * .95f; val r1 = radius + stroke * .95f
            canvas.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), tick)
        }
        if (spec.head && shownPrimary != null && shownPrimary > 0.01f) {
            val a = Math.toRadians(-90.0 + shownPrimary.coerceIn(0f, 1f) * 360.0)
            val dot = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xE6FFFFFF.toInt() }
            canvas.drawCircle(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat(), stroke * .42f, dot)
        }
        if (spec.secondaryShown != null || spec.secondaryUsed != null) {
            val innerStroke = max(1.3f * density, stroke * .62f)
            val innerRadius = radius - stroke - size * 0.05f
            ring(canvas, paint, cx, cy, innerRadius, innerStroke, spec.track, spec.secondaryShown, spec.secondaryUsed)
            spec.secondaryBudget?.takeIf { it in 0f..1f }?.let { b ->
                val a = Math.toRadians(-90.0 + b * 360.0)
                val tick = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeWidth = max(1f * density, innerStroke * .4f); color = spec.ink }
                val r0 = innerRadius - innerStroke; val r1 = innerRadius + innerStroke
                canvas.drawLine(cx + r0 * cos(a).toFloat(), cy + r0 * sin(a).toFloat(), cx + r1 * cos(a).toFloat(), cy + r1 * sin(a).toFloat(), tick)
            }
        }
        if (spec.shown == null && spec.status in setOf("Error", "NeedsAuth", "Unsupported")) {
            // One restrained status mark, never an invented usage sweep.
            val a = Math.toRadians(-45.0)
            paint.style = Paint.Style.FILL; paint.color = if (spec.status == "Error") Palette.DANGER else Palette.WARNING
            canvas.drawCircle(cx + radius * cos(a).toFloat(), cy + radius * sin(a).toFloat(), 2.1f * density, paint)
            paint.style = Paint.Style.STROKE
        }
        spec.logo?.let { logo ->
            val half = (size * spec.logoScale / 2).toInt()
            logo.setBounds((cx - half).toInt(), (cy - half).toInt(), (cx + half).toInt(), (cy + half).toInt())
            logo.draw(canvas)
        }
    }
    private fun ring(canvas: Canvas, paint: Paint, cx: Float, cy: Float, radius: Float, stroke: Float, track: Int, shown: Float?, used: Double?) {
        paint.shader = null; paint.strokeWidth = stroke; paint.color = track
        canvas.drawCircle(cx, cy, radius, paint)
        val fraction = shown ?: return
        if (!fraction.isFinite() || fraction <= 0f) return
        val color = Palette.usage(used ?: 0.0)
        paint.color = color
        if (fraction >= 0.985f) { canvas.drawCircle(cx, cy, radius, paint); return }
        paint.shader = comet(cx, cy, radius, stroke, fraction.coerceIn(0f, 1f), color)
        canvas.drawArc(RectF(cx - radius, cy - radius, cx + radius, cy + radius), -90f, fraction.coerceIn(0f, 1f) * 360f, false, paint)
        paint.shader = null
    }
    /** Faint tail to full head. The gradient starts half a stroke before 12 o'clock so the tail's rounded cap stays faint. */
    internal fun comet(cx: Float, cy: Float, radius: Float, stroke: Float, fraction: Float, color: Int): android.graphics.SweepGradient {
        val cap = Math.toDegrees((stroke / 2 / radius).toDouble()).toFloat()
        val head = ((cap + fraction * 360f) / 360f).coerceIn(0.02f, 0.97f)
        val tail = (color and 0x00FFFFFF) or 0x40000000
        return android.graphics.SweepGradient(cx, cy, intArrayOf(tail, color, color, tail), floatArrayOf(0f, head, (head + .02f).coerceAtMost(.99f), 1f)).apply {
            setLocalMatrix(android.graphics.Matrix().apply { setRotate(-90f - cap, cx, cy) })
        }
    }
    /** Ring inputs for a provider, following the dock: outer ring the session window, inner ring the weekly one. */
    fun specFor(p: Provider, remaining: Boolean, now: Long, track: Int, logo: Drawable?, primary: Float? = null, secondary: Float? = null, glow: Float = 0f, head: Boolean = false,
                ink: Int = 0xFFFFFFFF.toInt(), budget: Budget? = null, showTime: Boolean = true, budgets: List<Budget> = emptyList()): Spec {
        val s = p.sessionWindow(); val w = p.weeklyWindow()?.takeIf { it.id != s?.id }
        fun mark(b: Budget?) = b?.let { (if (remaining) 1f - it.limit / 100f else it.limit / 100f).coerceIn(0f, 1f) }
        val sessionBudget = budget?.takeIf { s != null && it.window == s.id } ?: budgets.firstOrNull { s != null && it.provider == p.id && it.window == s.id }
        val weeklyBudget = budgets.firstOrNull { w != null && it.provider == p.id && it.window == w.id }
        return Spec(primary ?: s?.shown(remaining)?.toFloat(), s?.used, w?.let { secondary ?: it.shown(remaining).toFloat() }, w?.used,
            p.status, s?.resetPassed(now) == true, track, logo, glow = glow, head = head,
            timeLeft = if (showTime) s?.timeLeft(now) else null, budget = mark(sessionBudget), ink = ink,
            secondaryBudget = mark(weeklyBudget)?.takeIf { w?.resetPassed(now) != true })
    }
}
