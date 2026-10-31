package io.github.arnavdugad.usagenotch

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The tokens widget: what Claude Code (or Codex) used today on your PC. The source's mark and name, today's total in
 * large type, the API-price equivalent, then (with room) the top project and a 7-day sparkline with today highlighted.
 * From 2×1 it shows the number and cost; when the PC sends no token counts it says where they come from.
 */
object TokenWidget {
    /** The source a tokens widget shows: the chosen one, or the first (Claude Code) until one is chosen. */
    fun source(input: WidgetRenderer.Input): TokenSource? = input.snapshot?.tokens?.sources?.let { s -> s.firstOrNull { it.id == input.focusId } ?: s.firstOrNull() }

    private fun paint(size: Float, color: Int, d: Float, bold: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size * d; this.color = color
        typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private fun text(canvas: Canvas, value: String, x: Float, baseline: Float, maxWidth: Float, p: TextPaint, align: Paint.Align = Paint.Align.LEFT) {
        if (maxWidth <= 0) return
        p.textAlign = align
        canvas.drawText(TextUtils.ellipsize(value, p, maxWidth, TextUtils.TruncateAt.END).toString(), x, baseline, p)
    }
    private fun cost(v: Double) = "≈ " + dollars(v) + " at API prices"

    fun draw(context: Context, canvas: Canvas, bitmap: Bitmap, w: Float, h: Float, d: Float, input: WidgetRenderer.Input): WidgetRenderer.Output {
        val dark = input.dark
        val ink = if (dark) 0xFFF2F4F8.toInt() else 0xFF12151B.toInt()
        val muted = if (dark) 0xFFA9B1BF.toInt() else 0xFF5B6371.toInt()
        val faint = if (dark) 0xFF7D8595.toInt() else 0xFF858D9B.toInt()
        val raised = if (input.classic) (if (dark) 0xFF2C2C2E.toInt() else 0xFFF2F2F7.toInt()) else if (dark) 0xFF1E232D.toInt() else 0xFFF1F3F6.toInt()
        val src = source(input)
        val refresh = input.paired && w >= 130 && h >= 60
        val pad = if (min(w, h) < 90) 10f else 14f
        if (src == null) {
            val logo = context.getDrawable(R.drawable.ic_notch); val size = min(min(w, h) * .36f, 40f)
            val compact = h < 100
            val cx = if (compact) pad + size / 2 else w / 2; val cy = if (compact) h / 2 else pad + 6 + size / 2
            logo?.setBounds(((cx - size / 2) * d).toInt(), ((cy - size / 2) * d).toInt(), ((cx + size / 2) * d).toInt(), ((cy + size / 2) * d).toInt()); logo?.draw(canvas)
            val title = if (!input.paired) "Pair with your PC" else "Tokens appear here"
            val note = if (!input.paired) "Open UsageNotch to scan" else "From UsageNotch for Windows 2.6"
            if (compact) {
                text(canvas, title, (pad + size + 10) * d, (h / 2 - 2) * d, (w - pad * 2 - size - 10) * d, paint(12.5f, ink, d, true))
                text(canvas, note, (pad + size + 10) * d, (h / 2 + 13) * d, (w - pad * 2 - size - 10) * d, paint(10f, muted, d))
            } else {
                text(canvas, title, w / 2 * d, (cy + size / 2 + 20) * d, (w - pad * 2) * d, paint(13f, ink, d, true), Paint.Align.CENTER)
                text(canvas, note, w / 2 * d, (cy + size / 2 + 36) * d, (w - pad * 2) * d, paint(10.5f, muted, d), Paint.Align.CENTER)
            }
            return WidgetRenderer.Output(bitmap, "UsageNotch tokens. " + if (!input.paired) "Not paired." else "Token counts appear when your PC sends them.", false, refresh)
        }
        val today = src.today; val total = today?.total ?: 0L
        val kind = if (src.id.startsWith("codex")) "codex" else "claude"
        val accent = DesignTokens.brand[kind] ?: DesignTokens.LIVE
        val tile = if (h < 90) 24f else 30f
        canvas.drawRoundRect(RectF(pad * d, pad * d, (pad + tile) * d, (pad + tile) * d), tile * .33f * d, tile * .33f * d, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = raised })
        Logos.drawable(context, kind, ink)?.apply { val inset = tile * .22f; setBounds(((pad + inset) * d).toInt(), ((pad + inset) * d).toInt(), ((pad + tile - inset) * d).toInt(), ((pad + tile - inset) * d).toInt()); draw(canvas) }
        val right = w - pad - (if (refresh) WidgetRenderer.REFRESH_DP - 6 else 0f)
        val x = pad + tile + 9
        text(canvas, src.label, x * d, (pad + tile / 2 - 1) * d, (right - x) * d, paint(12.5f, ink, d, true))
        text(canvas, "Today", x * d, (pad + tile / 2 + 12) * d, (right - x) * d, paint(10f, muted, d))
        val top = src.projects.firstOrNull()
        if (h < 100) {
            // 2×1 and wide rows: the number and the cost under the header.
            text(canvas, tokens(total), pad * d, (h - pad) * d, (w * .5f) * d, paint(22f, ink, d, true))
            (today?.cost?.let { "≈ " + dollars(it) } ?: "all devices".takeIf { src.allDevices })?.let { text(canvas, it, (w - pad) * d, (h - pad - 2) * d, (w * .5f - pad) * d, paint(11f, muted, d), Paint.Align.RIGHT) }
        } else {
            val big = if (w < 170) 28f else 36f
            val base = pad + tile + 6 + big
            text(canvas, tokens(total), pad * d, base * d, (w - pad * 2) * d, paint(big, ink, d, true))
            var y = base
            (today?.cost?.let { cost(it) } ?: "tokens, all devices".takeIf { src.allDevices })?.let { y += 16; text(canvas, it, pad * d, y * d, (w - pad * 2) * d, paint(10.5f, muted, d)) }
            // Account-wide figures when the provider sends them (Codex): lifetime, busiest day, longest streak.
            if (src.allDevices && h >= 170) tokenFacts(src)?.let { y += 15; text(canvas, it, pad * d, y * d, (w - pad * 2) * d, paint(9.5f, faint, d)) }
            y += 8
            if (top != null && h - y - pad >= 30) {
                text(canvas, "Top project · 7 days", pad * d, (y + 11) * d, (w - pad * 2) * d, paint(10f, faint, d))
                text(canvas, top.name + " · " + (top.share * 100).roundToInt() + "%", pad * d, (y + 26) * d, (w - pad * 2) * d, paint(12.5f, ink, d, true))
                y += 34
            }
            val week = src.days.takeLast(7)
            val spark = h - y - pad - 12
            if (week.size >= 2 && spark >= 18) {
                val peak = max(1L, week.maxOf { it.total }).toFloat()
                val slot = (w - pad * 2) / week.size; val barW = min(slot * .56f, 22f)
                val p = Paint(Paint.ANTI_ALIAS_FLAG)
                week.forEachIndexed { i, day ->
                    val bh = max(3f, spark * day.total / peak)
                    val bx = pad + slot * i + (slot - barW) / 2; val by = y + 2 + spark - bh
                    p.color = if (i == week.lastIndex) accent else (accent and 0x00FFFFFF) or 0x59000000
                    canvas.drawRoundRect(RectF(bx * d, by * d, (bx + barW) * d, (by + bh) * d), min(4f, barW / 2) * d, min(4f, barW / 2) * d, p)
                }
                text(canvas, "Last 7 days", pad * d, (h - pad + 2) * d, (w - pad * 2) * d, paint(9.5f, faint, d))
            }
        }
        val description = "UsageNotch tokens. ${src.label} today: ${tokens(total)} tokens" + (today?.cost?.let { ", about ${dollars(it)} at API prices" } ?: "") +
            (top?.let { ". Top project ${it.name}" } ?: "") + ". " + input.status
        return WidgetRenderer.Output(bitmap, description, false, refresh)
    }
}
