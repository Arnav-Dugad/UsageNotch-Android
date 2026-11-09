package io.github.arnavdugad.usagenotch

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.FileProvider
import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.min

/**
 * "Your week", a 1080×1920 story image in the app's design: your rings, each provider's week, the busiest day, your
 * streak, and (when the PC sends them) the week's tokens and top project, under the UsageNotch mark. It holds no
 * account names or e-mail addresses, and is shared through a private cache file, so no storage permission is needed.
 */
object ShareCard {
    const val WIDTH = 1080; const val HEIGHT = 1920
    private const val AUTHORITY = "io.github.arnavdugad.usagenotch.share"

    data class Week(val provider: Provider, val label: String, val total: Double?, val busiest: Day?, val streak: Int)

    /** Each provider's main limit over the last 7 days, from the PC's history. */
    fun weeks(providers: List<Provider>): List<Week> = providers.mapNotNull { p ->
        val h = p.history.firstOrNull { it.window == p.sessionWindow()?.id } ?: p.history.firstOrNull() ?: return@mapNotNull null
        val days = h.last(7)
        val recorded = days.mapNotNull { it.used }
        Week(p, h.label, recorded.takeIf { it.isNotEmpty() }?.sum(), days.filter { (it.used ?: 0.0) > 0 }.maxByOrNull { it.used!! }, h.streak)
    }

    private fun paint(size: Float, color: Int, bold: Boolean = false, light: Boolean = false) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = size; this.color = color
        typeface = when { bold -> Typeface.create("sans-serif-medium", Typeface.BOLD); light -> Typeface.create("sans-serif-light", Typeface.NORMAL); else -> Typeface.create("sans-serif", Typeface.NORMAL) }
    }
    private fun Canvas.text(value: String, x: Float, baseline: Float, maxWidth: Float, p: TextPaint, align: Paint.Align = Paint.Align.LEFT) {
        p.textAlign = align; drawText(TextUtils.ellipsize(value, p, maxWidth, TextUtils.TruncateAt.END).toString(), x, baseline, p)
    }

    fun render(context: Context, providers: List<Provider>, usage: TokenUsage?, remaining: Boolean, now: Long, dark: Boolean, classic: Boolean, report: WeekReport? = null, accentOf: (String) -> Int): Bitmap {
        val bitmap = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val c = Canvas(bitmap); val w = WIDTH.toFloat(); val h = HEIGHT.toFloat()
        val text = if (dark) 0xFFF4F6FA.toInt() else 0xFF101318.toInt()
        val muted = if (dark) 0xFFA9B1BF.toInt() else 0xFF5B6371.toInt()
        val card = when { classic && dark -> 0xFF1C1C1E.toInt(); classic -> 0xFFFFFFFF.toInt(); dark -> 0x2EFFFFFF; else -> 0xB3FFFFFF.toInt() }
        // Background: the ambient light in Liquid glass, the grouped background in Classic.
        if (classic) c.drawColor(if (dark) 0xFF000000.toInt() else 0xFFF2F2F7.toInt())
        else {
            c.drawRect(0f, 0f, w, h, Paint().apply { shader = LinearGradient(0f, 0f, 0f, h, if (dark) 0xFF06080C.toInt() else 0xFFF4F6FA.toInt(), if (dark) 0xFF0C0F16.toInt() else 0xFFE6EAF2.toInt(), Shader.TileMode.CLAMP) })
            val colors = (providers.take(3).map { accentOf(it.id) } + DesignTokens.ambient).take(4)
            colors.forEachIndexed { i, color ->
                val cx = w * listOf(.15f, .85f, .3f, .75f)[i]; val cy = h * listOf(.12f, .3f, .62f, .88f)[i]; val r = w * .75f
                c.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = RadialGradient(cx, cy, r, intArrayOf((color and 0x00FFFFFF) or (if (dark) 0x66000000 else 0x55000000), 0x00000000), null, Shader.TileMode.CLAMP) })
            }
        }
        val pad = 84f
        // Header: the mark, the title and the dates.
        context.getDrawable(R.drawable.ic_notch)?.apply { setBounds(pad.toInt(), 120, (pad + 92).toInt(), 212); draw(c) }
        c.text("UsageNotch", pad + 116, 182f, 600f, paint(40f, text, bold = true))
        val today = LocalDate.now(); val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
        c.text("My week in AI", pad, 380f, w - pad * 2, paint(104f, text, bold = true))
        c.text(report?.summary?.ifBlank { null } ?: "${today.minusDays(6).format(fmt)} – ${today.format(fmt)}", pad, 450f, w - pad * 2, paint(42f, muted))

        // Rings, as on the dock.
        val shown = providers.filter { it.sessionWindow() != null }.take(4)
        val ringSize = if (shown.size <= 2) 300f else if (shown.size == 3) 250f else 210f
        val slot = (w - pad * 2) / shown.size.coerceAtLeast(1)
        val track = if (dark) 0x33FFFFFF else 0x1F0B0D12
        shown.forEachIndexed { i, p ->
            val cx = pad + slot * i + slot / 2; val cy = 520f + ringSize / 2
            RingPainter.draw(c, cx, cy, ringSize, 3f, RingPainter.specFor(p, remaining, now, track, Logos.drawable(context, p.id, text), ink = text))
            c.text(headlineOf(p, remaining, now), cx, cy + ringSize / 2 + 70, slot - 16, paint(52f, text, bold = true), Paint.Align.CENTER)
            c.text(p.name, cx, cy + ringSize / 2 + 116, slot - 16, paint(34f, muted), Paint.Align.CENTER)
        }

        // The week: one row per provider, then the busiest day, the streak and tokens.
        var y = 520f + ringSize + 190
        val weeks = weeks(providers).take(4)
        fun panel(top: Float, height: Float) = c.drawRoundRect(RectF(pad, top, w - pad, top + height), 44f, 44f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = card })
        if (weeks.isNotEmpty()) {
            val rowH = 96f; val height = 52f + rowH * weeks.size
            panel(y, height)
            weeks.forEachIndexed { i, wk ->
                val top = y + 30 + rowH * i
                Logos.drawable(context, wk.provider.id, text)?.apply { setBounds((pad + 40).toInt(), (top + 18).toInt(), (pad + 96).toInt(), (top + 74).toInt()); draw(c) }
                c.text(wk.provider.name, pad + 124, top + 46, 420f, paint(38f, text, bold = true))
                c.text(wk.label, pad + 124, top + 84, 420f, paint(30f, muted))
                val value = LimitText.of(wk.total)
                c.text(value, w - pad - 40, top + 56, 300f, paint(56f, accentOf(wk.provider.id).let { Palette.readable(it, if (dark) 0xFF151922.toInt() else 0xFFFFFFFF.toInt()) }, bold = true), Paint.Align.RIGHT)
                c.text(if ((wk.total ?: 0.0) >= LimitText.MULTIPLE) "limits' worth" else "of one limit", w - pad - 40, top + 88, 300f, paint(26f, muted), Paint.Align.RIGHT)
            }
            y += height + 36
        }
        // The PC's "This week": its headline and up to three of its sentences, as many as fit.
        if (report != null) {
            val inner = w - pad * 2 - 96
            val headline = layout(report.headline, paint(50f, text, bold = true), inner, 2)
            val lines = report.insights.take(3).map { layout(it.text, paint(34f, text), inner - 34, 3) to (it.provider?.let(accentOf) ?: accentOf(providers.firstOrNull()?.id ?: "other")) }
            var height = 56f + headline.height + 30f
            val shown = mutableListOf<Pair<android.text.StaticLayout, Int>>()
            for (line in lines) { if (y + height + line.first.height + 26f + 48f > h - 150) break; shown += line; height += line.first.height + 26f }
            if (y + height + 18f <= h - 150) {
                panel(y, height + 18f)
                c.save(); c.translate(pad + 48, y + 56f); headline.draw(c); c.restore()
                var top = y + 56f + headline.height + 30f
                shown.forEach { (layout, color) ->
                    c.drawCircle(pad + 56, top + 22f, 8f, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
                    c.save(); c.translate(pad + 48 + 34, top); layout.draw(c); c.restore()
                    top += layout.height + 26f
                }
                y += height + 18f + 36
            }
        }
        val busiest = weeks.mapNotNull { wk -> wk.busiest?.let { wk to it } }.maxByOrNull { it.second.used ?: 0.0 }
        val streak = weeks.maxOfOrNull { it.streak } ?: 0
        val source = usage?.sources?.firstOrNull()
        val tiles = listOfNotNull(
            busiest?.let { (wk, day) -> Triple("Busiest day", runCatching { LocalDate.parse(day.date).format(DateTimeFormatter.ofPattern("EEEE", Locale.ENGLISH)) }.getOrDefault(day.date), "${wk.provider.name} · ${LimitText.of(day.used)} of the limit") },
            Triple("Streak", if (streak > 0) "$streak day${if (streak == 1) "" else "s"}" else "—", "in a row with usage"),
            source?.week?.let { t -> Triple("Tokens this week", tokens(t.total), t.cost?.let { "≈ ${dollars(it)} at API prices" } ?: source.label) },
            source?.projects?.firstOrNull()?.let { pr -> Triple("Top project", pr.name, "${(pr.share * 100).toInt()}% of ${source.label}") },
        ).take(4)
        val tileW = (w - pad * 2 - 28) / 2; val tileH = 200f
        tiles.forEachIndexed { i, (label, value, note) ->
            val x = pad + (i % 2) * (tileW + 28); val top = y + (i / 2) * (tileH + 28)
            if (top + tileH > h - 150) return@forEachIndexed
            c.drawRoundRect(RectF(x, top, x + tileW, top + tileH), 40f, 40f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = card })
            c.text(label, x + 36, top + 58, tileW - 72, paint(28f, muted))
            c.text(value, x + 36, top + 124, tileW - 72, paint(min(58f, 58f * 9 / value.length.coerceAtLeast(9)), text, bold = true))
            c.text(note, x + 36, top + 168, tileW - 72, paint(26f, muted))
        }
        c.text("Made with UsageNotch", w / 2, h - 84, w - pad * 2, paint(30f, muted), Paint.Align.CENTER)
        return bitmap
    }

    private fun layout(value: String, p: TextPaint, width: Float, maxLines: Int): android.text.StaticLayout =
        android.text.StaticLayout.Builder.obtain(value, 0, value.length, p, width.toInt()).setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END).setLineSpacing(0f, 1.12f).build()

    /** Writes the image to a private cache file and opens the share sheet. */
    fun share(context: Context, bitmap: Bitmap) {
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        val file = File(dir, "usagenotch-week.png")
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val uri = FileProvider.getUriForFile(context, AUTHORITY, file)
        val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData = ClipData.newRawUri("My week in AI", uri) }
        context.startActivity(Intent.createChooser(send, "Share your week").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }
}
