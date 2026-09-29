package io.github.arnavdugad.usagenotch

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * One provider, full screen: a large ring (morphed from its dock ring), the pace forecast, a scrubbable 24-hour chart
 * and every limit. Back, including the predictive back gesture, shrinks it toward the dashboard.
 */
@Composable fun ProviderDetail(
    p: Provider, now: Long, remaining: Boolean, clock24: Boolean, reduced: Boolean, offline: Boolean, preview: Boolean,
    ringModifier: Modifier, onRefresh: (() -> Unit)?, onDashboard: (String) -> Unit, onBack: () -> Unit,
) {
    val t = LocalTokens.current
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { progress ->
        try { progress.collect { backProgress = it.progress }; onBack() }
        catch (e: CancellationException) { backProgress = 0f; throw e }
    }
    val s = p.sessionWindow()
    val renewed = s?.resetPassed(now) == true
    Column(
        Modifier.fillMaxSize().graphicsLayer {
            val scale = 1f - backProgress * .08f
            scaleX = scale; scaleY = scale
            shape = RoundedCornerShape((backProgress * 36).dp); clip = backProgress > 0f
        }.background(Brush.verticalGradient(listOf(t.background, t.backgroundEnd)))
            .statusBarsPadding().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack, modifier = Modifier.clip(CircleShape).background(t.raised)) { Icon(Icons.AutoMirrored.Outlined.ArrowBack, "Back", tint = t.text) }
            Spacer(Modifier.width(12.dp))
            Logo(p.id, Modifier.size(22.dp)); Spacer(Modifier.width(9.dp))
            Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
            StatusPill(if (preview) "Preview" else statusPill(p, now, offline))
        }
        Column(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            UsageRing(p, remaining, now, reduced, 210.dp, ringModifier)
            Spacer(Modifier.height(16.dp))
            RollingText(headlineOf(p, remaining, now), 40.sp, t.text, FontWeight.Light, reduced)
            s?.let { w ->
                Text(if (renewed) "${w.label} renewed" else "${w.label} ${if (remaining) "remaining" else "used"}", fontSize = 13.sp, color = t.muted, modifier = Modifier.padding(top = 2.dp))
                if (!renewed) Text(if (w.reset == null) "Reset time not reported" else ClockText.resetsIn(w.reset, now) + " · " +ClockText.resetsAt(w.reset, now, clock24).removePrefix("Resets "), fontSize = 12.sp, color = t.faint, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            secondaryOf(p, remaining, now)?.let { Text(it, fontSize = 12.sp, color = t.faint, modifier = Modifier.padding(top = 2.dp)) }
        }
        s?.takeIf { !renewed }?.forecast?.takeIf { it.rate != null || it.summary.startsWith("Learning") }?.let { f -> ForecastCard(f, s, now, clock24); Spacer(Modifier.height(12.dp)) }
        s?.let { w ->
            Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
                Text("Last 24 hours", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                Text("Touch and drag to read any moment.", fontSize = 11.5.sp, lineHeight = 16.sp, color = t.faint, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
                ScrubChart(w, clock24)
            } }
            Spacer(Modifier.height(12.dp))
        }
        p.statusText?.let { Text(it, fontSize = 12.5.sp, lineHeight = 18.sp, color = t.muted, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)) }
        p.windows.forEachIndexed { index, w -> WindowCard(w, index, now, remaining, clock24, reduced); Spacer(Modifier.height(8.dp)) }
        if (onRefresh != null || p.manageUrl != null) Row(Modifier.padding(top = 6.dp, bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            onRefresh?.let { CardButton("Refresh", it) }
            p.manageUrl?.let { url -> CardButton("Open dashboard") { onDashboard(url) } }
        }
    }
}

@Composable private fun ForecastCard(f: Forecast, w: UsageWindow, now: Long, clock24: Boolean) {
    val t = LocalTokens.current
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Outlined.TrendingUp, null, tint = t.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text("Pace", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
            if (f.confidence.isNotBlank()) Text(f.confidence, fontSize = 11.sp, color = t.faint)
        }
        val headline = when {
            f.limitAt != null && f.limitAt <= now -> "Limit reached by now"
            f.limitAt != null -> "Runs out around ${ClockText.time(f.limitAt, clock24)}"
            // The PC sends the projection and pace in percentage points.
            f.projected != null -> "About ${f.projected.roundToInt().coerceAtMost(999)}% used by the reset"
            else -> f.summary.ifBlank { "Learning your pace" }
        }
        Text(headline, fontSize = 19.sp, fontWeight = FontWeight.Light, color = t.text, modifier = Modifier.padding(top = 8.dp))
        val parts = buildList {
            f.rate?.let { add("${if (it >= 0) "+" else ""}${"%.1f".format(java.util.Locale.ENGLISH, it)}% an hour") }
            if (f.limitAt != null && f.limitAt > now && w.reset != null) add("before it resets at ${ClockText.time(w.reset, clock24)}")
            else if (f.projected != null && f.projected < 100) add("the limit should hold until the reset")
        }
        if (parts.isNotEmpty()) Text(parts.joinToString(", ").replaceFirstChar { it.uppercase() } + ".", fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(top = 4.dp))
        Text("Estimated on your PC from recent readings.", fontSize = 11.sp, color = t.faint, modifier = Modifier.padding(top = 6.dp))
    } }
}

/** The 24-hour line with a crosshair: drag to read any reading, with a light haptic tick as it moves between readings. */
@Composable fun ScrubChart(window: UsageWindow, clock24: Boolean) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val points = window.points
    if (points.size < 2) { Text("Your history will appear as readings arrive.", fontSize = 11.5.sp, color = t.muted, modifier = Modifier.padding(vertical = 12.dp)); return }
    val line = Color(Palette.usage(window.used))
    var selected by remember(window.id) { mutableStateOf<Int?>(null) }
    val start = window.at - 86_400_000; val span = 86_400_000.0
    fun nearest(x: Float, width: Float): Int {
        val at = start + (x / width).coerceIn(0f, 1f) * span
        return points.indices.minBy { abs(points[it].at - at) }
    }
    fun pick(index: Int) { if (index != selected) { selected = index; haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) } }
    Column {
        Box(Modifier.fillMaxWidth().height(26.dp)) {
            selected?.let { i -> val r = points[i]
                Text("${(r.used * 100).roundToInt()}% used · ${ClockText.time(r.at, clock24)}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.text)
            } ?: Text("Now ${(window.used * 100).roundToInt()}% used", fontSize = 13.sp, color = t.muted)
        }
        Canvas(Modifier.fillMaxWidth().height(160.dp)
            .pointerInput(points) {
                detectDragGestures(onDragStart = { pick(nearest(it.x, size.width.toFloat())) }, onDragEnd = { selected = null }, onDragCancel = { selected = null }) { change, _ -> pick(nearest(change.position.x, size.width.toFloat())) }
            }
            .pointerInput(points) { detectTapGestures(onPress = { pick(nearest(it.x, size.width.toFloat())); tryAwaitRelease(); selected = null }) }
            .semantics { contentDescription = "${window.label} usage over 24 hours, ${points.size} readings, from ${(points.first().used * 100).roundToInt()} to ${(points.last().used * 100).roundToInt()} percent used." }) {
            for (i in 0..4) drawLine(t.hairline, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1.dp.toPx())
            fun xy(p: Reading) = Offset(((p.at - start) / span * size.width).toFloat().coerceIn(0f, size.width), size.height * (1 - p.used.toFloat().coerceIn(0f, 1f)))
            val segments = mutableListOf<MutableList<Reading>>()
            points.forEach { p -> val last = segments.lastOrNull()?.lastOrNull(); if (last != null && last.period == p.period && p.at - last.at in 1..1_200_000) segments.last().add(p) else segments.add(mutableListOf(p)) }
            segments.forEach { seg ->
                if (seg.size < 2) { drawCircle(line, 2.dp.toPx(), xy(seg[0])); return@forEach }
                val area = Path().apply { moveTo(xy(seg.first()).x, size.height); seg.forEach { lineTo(xy(it).x, xy(it).y) }; lineTo(xy(seg.last()).x, size.height); close() }
                drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = .24f), Color.Transparent)))
                val stroke = Path().apply { moveTo(xy(seg[0]).x, xy(seg[0]).y); seg.drop(1).forEach { lineTo(xy(it).x, xy(it).y) } }
                drawPath(stroke, line, style = Stroke(2.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val focus = selected?.let { points[it] } ?: points.last()
            val at = xy(focus)
            if (selected != null) drawLine(t.muted.copy(alpha = .6f), Offset(at.x, 0f), Offset(at.x, size.height), 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
            drawCircle(line.copy(alpha = .25f), 9.dp.toPx(), at); drawCircle(line, 4.dp.toPx(), at); drawCircle(t.card, 1.8.dp.toPx(), at)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf("24h ago", "12h", "Now").forEachIndexed { i, label -> Text(label, fontSize = 10.5.sp, color = t.faint, textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End }, modifier = Modifier.weight(1f)) }
        }
    }
}
