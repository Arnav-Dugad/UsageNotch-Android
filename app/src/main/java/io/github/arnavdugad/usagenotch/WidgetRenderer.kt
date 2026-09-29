package io.github.arnavdugad.usagenotch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws a widget at its exact size. Every size gets a layout that fits: a single ring (1×1), a dock row or column
 * of rings like the desktop dock, or the full view with rings and desktop-style limit rows. The widget's own rounded
 * drawable is a translucent glass body; this bitmap adds the glass's specular rim and sheen, then the content.
 */
object WidgetRenderer {
    data class Frame(val widthDp: Float, val heightDp: Float, val density: Float, val maxBytes: Int = MAX_BITMAP_BYTES)
    data class Input(
        val snapshot: Snapshot?, val paired: Boolean, val status: String, val remaining: Boolean, val use24: Boolean,
        val now: Long, val dark: Boolean, val focus: Boolean, val focusId: String?, val offline: Boolean,
        val budgets: List<Budget> = emptyList(),
    )
    /** [refresh] says whether this layout left the top-right corner free for the refresh button. */
    class Output(val bitmap: Bitmap, val description: String, val full: Boolean, val refresh: Boolean = full)

    const val MAX_BITMAP_BYTES = 5_000_000
    /** Glass body colours, matching res/drawable/widget_background*.xml, for previews in the app. */
    const val GLASS_DARK = 0xD6151922.toInt()
    const val GLASS_LIGHT = 0xE0FAFBFD.toInt()
    const val CORNER_DP = 24f
    /** The refresh button's corner, in dp, which layouts keep clear. */
    const val REFRESH_DP = 34f

    private class Ink(dark: Boolean) {
        val text = if (dark) 0xFFF2F4F8.toInt() else 0xFF12151B.toInt()
        val muted = if (dark) 0xFFA9B1BF.toInt() else 0xFF5B6371.toInt()
        val faint = if (dark) 0xFF7D8595.toInt() else 0xFF858D9B.toInt()
        val track = if (dark) 0x33FFFFFF else 0x220B0D12
        val raised = if (dark) 0xFF1E232D.toInt() else 0xFFF1F3F6.toInt()
        val surface = if (dark) 0xFF12151C.toInt() else 0xFFFFFFFF.toInt()
    }

    fun providers(input: Input): List<Provider> {
        val all = input.snapshot?.providers.orEmpty()
        return if (input.focus) listOfNotNull(focusProvider(all, input.focusId)) else all
    }

    fun render(context: Context, frame: Frame, input: Input): Output {
        val wDp = frame.widthDp.coerceIn(40f, 900f); val hDp = frame.heightDp.coerceIn(40f, 900f)
        // Keep each bitmap well inside the launcher's widget memory limit.
        var density = frame.density
        if (wDp * hDp * density * density * 4 > frame.maxBytes) density = sqrt(frame.maxBytes / (wDp * hDp * 4f))
        val bitmap = Bitmap.createBitmap(max(1, (wDp * density).roundToInt()), max(1, (hDp * density).roundToInt()), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val d = density; val ink = Ink(input.dark)
        val providers = providers(input)
        val full = wDp >= 180 && hDp >= 170
        glass(canvas, wDp, hDp, d, input.dark)
        // Small widgets open the app when tapped; from about 2×1 up, a refresh button sits in the top-right corner.
        val refresh = providers.isNotEmpty() && input.paired && (full || (wDp >= 130 && hDp >= 60) || (hDp >= 130 && wDp >= 60))
        when {
            providers.isEmpty() -> empty(context, canvas, wDp, hDp, d, ink, input)
            full && input.focus -> focusFull(context, canvas, wDp, hDp, d, ink, input, providers.first())
            full -> overviewFull(context, canvas, wDp, hDp, d, ink, input, providers)
            refresh && wDp >= hDp -> dock(context, canvas, 0f, 0f, wDp - REFRESH_DP + 6, hDp, d, ink, input, providers, pad = if (min(wDp, hDp) < 110) 5f else 10f)
            refresh -> dock(context, canvas, 0f, REFRESH_DP - 6, wDp, hDp - REFRESH_DP + 6, d, ink, input, providers, pad = if (min(wDp, hDp) < 110) 5f else 10f)
            else -> dock(context, canvas, 0f, 0f, wDp, hDp, d, ink, input, providers, pad = if (min(wDp, hDp) < 110) 5f else 10f)
        }
        return Output(bitmap, describe(input, providers), full, refresh)
    }

    /** The glass's light: a soft sheen across the top and a specular rim, bright at the top-left and fading around. */
    private fun glass(canvas: Canvas, w: Float, h: Float, d: Float, dark: Boolean) {
        val radius = CORNER_DP * d; val rect = RectF(.5f * d, .5f * d, w * d - .5f * d, h * d - .5f * d)
        val sheen = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.LinearGradient(0f, 0f, 0f, h * d * .55f, if (dark) 0x14FFFFFF else 0x59FFFFFF, 0x00FFFFFF, android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, radius, radius, sheen)
        val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE; strokeWidth = 1.1f * d
            shader = android.graphics.LinearGradient(0f, 0f, w * d, h * d, intArrayOf(if (dark) 0x8CFFFFFF.toInt() else 0xF2FFFFFF.toInt(), if (dark) 0x14FFFFFF else 0x40FFFFFF, if (dark) 0x0AFFFFFF else 0x1A000000, if (dark) 0x33FFFFFF else 0x66FFFFFF),
                floatArrayOf(0f, .35f, .7f, 1f), android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawRoundRect(rect, radius, radius, rim)
    }

    private fun paint(size: Float, color: Int, d: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * d; this.color = color
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private fun text(canvas: Canvas, value: String, x: Float, baseline: Float, maxWidth: Float, p: TextPaint, align: Paint.Align = Paint.Align.LEFT) {
        if (maxWidth <= 0) return
        val fitted = TextUtils.ellipsize(value, p, maxWidth, TextUtils.TruncateAt.END).toString()
        p.textAlign = align
        canvas.drawText(fitted, x, baseline, p)
    }
    private fun percent(p: Provider, remaining: Boolean, now: Long): String {
        val s = p.sessionWindow() ?: return when (p.status) { "NeedsAuth" -> "Sign in"; "Error" -> "Error"; "Unsupported" -> "N/A"; else -> "—" }
        return if (s.resetPassed(now)) "Renewed" else "${s.percent(remaining)}%"
    }
    private fun secondary(p: Provider, remaining: Boolean, now: Long): String? {
        val s = p.sessionWindow(); val w = p.weeklyWindow()?.takeIf { it.id != s?.id } ?: return null
        return p.secondaryTag() + " " + if (w.resetPassed(now)) "new" else "${w.percent(remaining)}%"
    }
    private fun ring(context: Context, canvas: Canvas, p: Provider, cx: Float, cy: Float, size: Float, d: Float, ink: Ink, input: Input, dual: Boolean = true) {
        val budget = input.budgets.firstOrNull { it.provider == p.id && it.window == p.sessionWindow()?.id }
        val spec = RingPainter.specFor(p, input.remaining, input.now, ink.track, Logos.drawable(context, p.id, ink.text), ink = ink.text, budget = budget)
        RingPainter.draw(canvas, cx, cy, size, d, if (dual) spec else RingPainter.Spec(spec.shown, spec.used, null, null, spec.status, spec.renewed, spec.track, spec.logo, timeLeft = spec.timeLeft, budget = spec.budget, ink = spec.ink))
    }

    private fun empty(context: Context, canvas: Canvas, w: Float, h: Float, d: Float, ink: Ink, input: Input) {
        val logo = context.getDrawable(R.drawable.ic_notch)
        val size = min(min(w, h) * 0.42f, 56f) * d
        val showText = h >= 90 && w >= 90
        val cy = if (showText) h * d * 0.40f else h * d / 2
        logo?.setBounds((w * d / 2 - size / 2).toInt(), (cy - size / 2).toInt(), (w * d / 2 + size / 2).toInt(), (cy + size / 2).toInt()); logo?.draw(canvas)
        if (showText) {
            text(canvas, if (!input.paired) "Pair with your PC" else "Waiting for a reading", w * d / 2, cy + size / 2 + 18 * d, (w - 16) * d, paint(12f, ink.text, d, true), Paint.Align.CENTER)
            if (h >= 130) text(canvas, if (!input.paired) "Open UsageNotch to scan" else input.status, w * d / 2, cy + size / 2 + 34 * d, (w - 16) * d, paint(10f, ink.muted, d), Paint.Align.CENTER)
        }
    }

    /** A row (wide) or column (tall) of dock cells: ring, percentage and the "7d" figure, as on the desktop dock. */
    private fun dock(context: Context, canvas: Canvas, x0: Float, y0: Float, w: Float, h: Float, d: Float, ink: Ink, input: Input, providers: List<Provider>, pad: Float, maxRing: Float = 84f) {
        val horizontal = w >= h
        val main = (if (horizontal) w else h) - pad * 2; val cross = (if (horizontal) h else w) - pad * 2
        val minCell = if (horizontal) 56f else 64f
        val count = min(providers.size, max(1, floor(main / minCell).toInt()))
        val cellMain = main / count
        val big = cross >= 76 && (if (horizontal) cellMain >= 60 else true)
        val textBlock = when { cross - 34 >= 30 && big -> 34f; cross - 17 >= 24 -> 17f; else -> 0f }
        val ringSize = min(min(if (horizontal) cellMain - 8 else cross - 8, cross - textBlock - 4), maxRing).coerceAtLeast(18f)
        for (i in 0 until count) {
            val p = providers[i]
            val cx: Float; val top: Float
            if (horizontal) { cx = x0 + pad + cellMain * i + cellMain / 2; top = y0 + pad + (cross - ringSize - textBlock) / 2 }
            else { cx = x0 + pad + cross / 2; top = y0 + pad + cellMain * i + (cellMain - ringSize - textBlock) / 2 }
            ring(context, canvas, p, cx * d, (top + ringSize / 2) * d, ringSize * d, d, ink, input)
            val width = (if (horizontal) cellMain else cross) - 2
            if (textBlock >= 17) text(canvas, percent(p, input.remaining, input.now), cx * d, (top + ringSize + 14) * d, width * d, paint(if (big) 13.5f else 11.5f, ink.text, d, true), Paint.Align.CENTER)
            if (textBlock >= 34) secondary(p, input.remaining, input.now)?.let { text(canvas, it, cx * d, (top + ringSize + 29) * d, width * d, paint(10f, ink.muted, d), Paint.Align.CENTER) }
        }
    }

    private fun header(canvas: Canvas, title: String, status: String, w: Float, d: Float, ink: Ink, pad: Float, logoOf: Provider? = null, context: Context? = null, pill: String? = null) {
        var x = pad
        if (logoOf != null && context != null) {
            val tile = 30f
            val paintTile = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.raised }
            canvas.drawRoundRect(RectF(x * d, pad * d, (x + tile) * d, (pad + tile) * d), 10 * d, 10 * d, paintTile)
            Logos.drawable(context, logoOf.id, ink.text)?.apply { setBounds(((x + 7) * d).toInt(), ((pad + 7) * d).toInt(), ((x + tile - 7) * d).toInt(), ((pad + tile - 7) * d).toInt()); draw(canvas) }
            x += tile + 10
        }
        val right = w - pad - 34 // refresh button
        text(canvas, title, x * d, (pad + 14) * d, (right - x - (if (pill != null) 56 else 0)) * d, paint(15f, ink.text, d, true))
        text(canvas, status, x * d, (pad + 29) * d, (right - x) * d, paint(10.5f, ink.muted, d))
        if (pill != null) {
            val color = when (pill) { "Live" -> 0xFF2EE0A8.toInt(); "Error" -> Palette.DANGER; else -> Palette.WARNING }
            val pp = paint(10f, Palette.readable(color, ink.surface), d, true); val tw = pp.measureText(pill) / d + 16
            val px = right - tw; val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = (color and 0x00FFFFFF) or 0x29000000 }
            canvas.drawRoundRect(RectF(px * d, (pad + 3) * d, (px + tw) * d, (pad + 21) * d), 7 * d, 7 * d, bg)
            text(canvas, pill, (px + tw / 2) * d, (pad + 16) * d, tw * d, pp, Paint.Align.CENTER)
        }
    }

    /** A desktop-style limit row: label and percentage, a colour-graded bar, and the local reset time. */
    private fun row(canvas: Canvas, label: String, w: UsageWindow, x: Float, y: Float, width: Float, d: Float, ink: Ink, input: Input): Float {
        val renewed = w.resetPassed(input.now)
        val value = if (renewed) "Renewed" else "${w.percent(input.remaining)}% ${if (input.remaining) "left" else "used"}"
        val vp = paint(12f, Palette.readable(Palette.usage(w.used), ink.surface), d, true)
        val vw = vp.measureText(value) / d
        text(canvas, label, x * d, (y + 12) * d, (width - vw - 8) * d, paint(12f, ink.text, d))
        text(canvas, value, (x + width) * d, (y + 12) * d, (vw + 2) * d, vp, Paint.Align.RIGHT)
        val bar = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ink.track }
        canvas.drawRoundRect(RectF(x * d, (y + 18) * d, (x + width) * d, (y + 23) * d), 3 * d, 3 * d, bar)
        val shown = if (renewed) 0.0 else w.shown(input.remaining)
        if (shown > 0) { bar.color = Palette.usage(w.used); canvas.drawRoundRect(RectF(x * d, (y + 18) * d, (x + max(5f, (width * shown).toFloat())) * d, (y + 23) * d), 3 * d, 3 * d, bar) }
        text(canvas, ClockText.resetsAt(w.reset, input.now, input.use24), x * d, (y + 36) * d, width * d, paint(10f, ink.faint, d))
        return 44f
    }

    private fun overviewFull(context: Context, canvas: Canvas, w: Float, h: Float, d: Float, ink: Ink, input: Input, providers: List<Provider>) {
        val pad = 14f
        // No title: the status line is all the header needs.
        text(canvas, input.status, pad * d, (pad + 12) * d, (w - pad * 2 - REFRESH_DP) * d, paint(11f, ink.muted, d))
        // Every provider's session limit first, then the weekly ones, so a short widget still covers each provider.
        val rows = providers.mapNotNull { p -> p.sessionWindow()?.let { p to it } } +
            providers.mapNotNull { p -> p.weeklyWindow()?.takeIf { it.id != p.sessionWindow()?.id }?.let { p to it } }
        // Tall widgets give the rings the room the rows don't need.
        val dockTop = pad + 18
        val spare = h - dockTop - pad - rows.size * 44f - 6
        val dockHeight = min(if (spare > 100f) min(spare, 170f) else 100f, h - dockTop - pad)
        dock(context, canvas, 0f, dockTop, w, dockHeight, d, ink, input, providers, pad = pad - 4, maxRing = if (dockHeight > 110f) min(dockHeight - 40f, 124f) else 84f)
        var y = dockTop + dockHeight + 6
        for ((p, window) in rows) {
            if (y + 40 > h - pad + 4) break
            y += row(canvas, "${p.name} · ${window.label}", window, pad, y, w - pad * 2, d, ink, input)
        }
    }

    private fun focusFull(context: Context, canvas: Canvas, w: Float, h: Float, d: Float, ink: Ink, input: Input, p: Provider) {
        val pad = 14f
        val updated = p.updatedAt ?: p.windows.maxOfOrNull { it.at }
        header(canvas, p.name, (updated?.let { ClockText.updated(it, input.now, input.use24) } ?: input.status) + (p.account?.let { " · $it" } ?: ""), w, d, ink, pad, p, context, statusPill(p, input.now, input.offline))
        val top = pad + 42
        val windows = p.windows
        if (w >= 260 && h >= 190) {
            val ringSize = min(min(w * 0.38f, h - top - pad - 30), 118f)
            val cx = pad + ringSize / 2; val cy = top + ringSize / 2
            ring(context, canvas, p, cx * d, cy * d, ringSize * d, d, ink, input)
            text(canvas, percent(p, input.remaining, input.now), cx * d, (cy + ringSize / 2 + 17) * d, ringSize * d, paint(15f, ink.text, d, true), Paint.Align.CENTER)
            secondary(p, input.remaining, input.now)?.let { text(canvas, it, cx * d, (cy + ringSize / 2 + 31) * d, ringSize * d, paint(10.5f, ink.muted, d), Paint.Align.CENTER) }
            var y = top; val x = pad + ringSize + 16; val width = w - x - pad
            for (window in windows) { if (y + 40 > h - pad + 4) break; y += row(canvas, window.label, window, x, y, width, d, ink, input) }
        } else {
            var y = top
            if (windows.isEmpty()) text(canvas, p.statusText ?: "No limits reported", pad * d, (y + 12) * d, (w - pad * 2) * d, paint(11f, ink.muted, d))
            for (window in windows) { if (y + 40 > h - pad + 4) break; y += row(canvas, window.label, window, pad, y, w - pad * 2, d, ink, input) }
        }
    }

    /** Spoken summary for TalkBack, since the widget body is an image. */
    fun describe(input: Input, providers: List<Provider>): String {
        if (providers.isEmpty()) return if (input.paired) "UsageNotch, waiting for a reading" else "UsageNotch, not paired. Open the app to pair with your PC."
        return "UsageNotch. " + providers.joinToString(". ") { p ->
            val s = p.sessionWindow()
            if (s == null) "${p.name}: ${(p.statusText ?: p.status).trimEnd('.')}" else {
                val value = if (s.resetPassed(input.now)) "renewed" else "${s.percent(input.remaining)} percent ${if (input.remaining) "left" else "used"}"
                "${p.name} ${s.label}: $value, ${ClockText.resetsAt(s.reset, input.now, input.use24).replaceFirstChar { it.lowercase() }}"
            }
        } + ". " + input.status
    }
}
