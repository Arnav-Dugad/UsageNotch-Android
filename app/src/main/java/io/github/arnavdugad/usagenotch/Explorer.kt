package io.github.arnavdugad.usagenotch

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ShowChart
import androidx.compose.material.icons.outlined.ZoomOutMap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

private const val HOUR_MS = 3_600_000L

/**
 * The desktop's usage inspector on the phone: the last 24 hours, or 7 days when the PC sends them (protocol 2), with
 * the pace projection as a dashed line and a soft fan up to the reset, reset and event markers, your budget, and the
 * reading under your finger. Drag to scrub (with light haptic ticks); pinch with two fingers to zoom into a stretch.
 */
@Composable fun ChartExplorer(p: Provider, window: UsageWindow, clock24: Boolean, reduced: Boolean, now: Long, budget: Budget? = null) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current; val accent = LocalAccent.current(p.id)
    val history = p.history.firstOrNull { it.window == window.id }
    val trend = history?.trend.orEmpty()
    var week by rememberSaveable(window.id) { mutableStateOf(false) }
    val showWeek = week && trend.size >= 2
    val span = if (showWeek) 7 * 24 * HOUR_MS else 24 * HOUR_MS
    val points = remember(window, trend, showWeek) {
        if (showWeek) trend else window.points.ifEmpty { trend.filter { it.at >= window.at - span } }
    }
    val last = points.lastOrNull()
    // Room on the right for the projection when there is a pace: up to the reset, at most a third of the view.
    val rate = window.forecast?.rate?.takeIf { it > 0.0001 && !window.resetPassed(now) }
    val projectionEnd = if (last != null && rate != null) minOf(window.reset ?: (last.at + span / 3), last.at + span / 3) else null
    val fullStart = (last?.at ?: window.at) - span; val fullEnd = maxOf(projectionEnd ?: 0L, last?.at ?: window.at, minOf(now, (last?.at ?: window.at) + span / 8))
    var view by remember(window.id, showWeek) { mutableStateOf(fullStart to fullEnd) }
    val zoomed = view.first > fullStart + 60_000 || view.second < fullEnd - 60_000
    var selected by remember(window.id, showWeek) { mutableStateOf<Int?>(null) }
    val draw = remember(window.id, showWeek) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(window.id, showWeek, reduced) { if (reduced) draw.snapTo(1f) else draw.animateTo(1f, tween(760, easing = FastOutSlowInEasing)) }
    val forecastColor = Color(Palette.FORECAST)
    val line = Color(Palette.usage(window.used))
    val zone = ZoneId.systemDefault()
    fun label(at: Long) = if (showWeek) DateTimeFormatter.ofPattern(if (clock24) "EEE HH:mm" else "EEE h:mm a", Locale.ENGLISH).withZone(zone).format(Instant.ofEpochMilli(at)) else ClockText.time(at, clock24)
    val events = history?.events.orEmpty().filter { it.at in view.first..view.second }

    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ShowChart, null, tint = t.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text(if (showWeek) "Last 7 days" else "Last 24 hours", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
            AnimatedVisibility(zoomed, enter = fadeIn() + scaleIn(), exit = fadeOut() + scaleOut()) {
                GlassIconButton(Icons.Outlined.ZoomOutMap, "Show the whole range", { view = fullStart to fullEnd; selected = null }, Modifier.size(34.dp))
            }
            if (trend.size >= 2) { Spacer(Modifier.width(6.dp)); GlassSegmented(listOf("24h", "7d"), if (showWeek) 1 else 0, { week = it == 1; selected = null }, Modifier.width(104.dp), reduced, listOf("24 hours", "7 days")) }
        }
        if (points.size < 2 || last == null) {
            Text("Readings appear here as your PC records them.", fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(vertical = 12.dp)); return@Column
        }
        Spacer(Modifier.height(8.dp))
        Box(Modifier.fillMaxWidth().height(22.dp)) {
            val r = selected?.let { points.getOrNull(it) }
            val near = r?.let { sel -> events.minByOrNull { abs(it.at - sel.at) }?.takeIf { abs(it.at - sel.at) < (view.second - view.first) / 40 } }
            AnimatedContent(r?.let { "${(it.used * 100).roundToInt()}% used · ${label(it.at)}" + (near?.let { e -> " · ${e.kind}" } ?: "") }
                ?: ("${(window.used * 100).roundToInt()}% used now" + (window.forecast?.confidence?.takeIf { it.isNotBlank() && rate != null }?.let { " · $it" } ?: "")),
                transitionSpec = { if (reduced) fadeIn(snap()) togetherWith fadeOut(snap()) else fadeIn(tween(120)) togetherWith fadeOut(tween(90)) }, label = "Readout") { text ->
                Text(text, fontSize = 13.sp, fontWeight = if (r != null) FontWeight.SemiBold else FontWeight.Normal, color = if (r != null) t.text else t.muted, maxLines = 1)
            }
        }
        val dashed = remember { PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) }
        val dotted = remember { PathEffect.dashPathEffect(floatArrayOf(3f, 6f)) }
        Canvas(Modifier.fillMaxWidth().height(170.dp)
            .pointerInput(points, showWeek) {
                fun nearest(px: Float): Int { val at = view.first + (px / size.width).coerceIn(0f, 1f) * (view.second - view.first); return points.indices.minBy { abs(points[it].at - at) } }
                fun pick(px: Float) { val i = nearest(px); if (i != selected) { selected = i; haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) } }
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    pick(down.position.x)
                    do {
                        val event = awaitPointerEvent()
                        val pressed = event.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            // Pinch: zoom the time axis around the fingers, and pan with them.
                            val zoom = event.calculateZoom(); val pan = event.calculatePan().x; val c = event.calculateCentroid().x
                            val width = (view.second - view.first).toDouble()
                            val focus = view.first + c / size.width * width
                            val newWidth = (width / zoom).coerceIn(30.0 * 60_000, (fullEnd - fullStart).toDouble())
                            var start = focus - (c / size.width) * newWidth - pan / size.width * newWidth
                            start = start.coerceIn(fullStart.toDouble(), fullEnd - newWidth)
                            view = start.toLong() to (start + newWidth).toLong()
                            selected = null
                            event.changes.forEach { it.consume() }
                        } else pressed.firstOrNull()?.takeIf { it.positionChanged() }?.let { pick(it.position.x); it.consume() }
                    } while (event.changes.any { it.pressed })
                    selected = null
                }
            }
            .semantics { contentDescription = "${window.label} usage over ${if (showWeek) "7 days" else "24 hours"}, ${points.size} readings, from ${(points.first().used * 100).roundToInt()} to ${(last.used * 100).roundToInt()} percent used." + (rate?.let { ", pace ${"%.1f".format(Locale.ENGLISH, it)} percent an hour" } ?: "") }) {
            val w = size.width; val h = size.height - 14.dp.toPx()
            val vs = view.first; val ve = view.second; val vw = (ve - vs).toDouble().coerceAtLeast(1.0)
            fun x(at: Long) = ((at - vs) / vw * w).toFloat()
            fun y(used: Double) = h * (1 - used.toFloat().coerceIn(0f, 1f))
            for (i in 1..3) drawLine(t.hairline, Offset(0f, h * i / 4), Offset(w, h * i / 4), 1.dp.toPx())
            // Your budget, as a dashed level.
            budget?.let { b -> val by = y(b.limit / 100.0); drawLine(t.ink.copy(alpha = .45f), Offset(0f, by), Offset(w, by), 1.2.dp.toPx(), pathEffect = dashed) }
            // The reported reset.
            window.reset?.takeIf { it in vs..ve }?.let { r -> val rx = x(r); drawLine(accent.copy(alpha = .7f), Offset(rx, 0f), Offset(rx, h), 1.4.dp.toPx(), pathEffect = dotted) }
            clipRect(right = w * draw.value) {
                // The PC thins long ranges, so a gap is judged against the spacing it sent: three times the usual step, at least 20 minutes.
                val step = if (points.size > 1) (points.last().at - points.first().at) / (points.size - 1) else 0L
                val gap = maxOf(1_200_000L, step * 3)
                val segments = mutableListOf<MutableList<Reading>>()
                points.forEach { pt -> val prev = segments.lastOrNull()?.lastOrNull(); if (prev != null && prev.period == pt.period && pt.at - prev.at in 1..gap && pt.used >= prev.used - .0001) segments.last().add(pt) else segments.add(mutableListOf(pt)) }
                segments.forEach { seg ->
                    if (seg.size < 2) { drawCircle(line, 2.dp.toPx(), Offset(x(seg[0].at), y(seg[0].used))); return@forEach }
                    val area = Path().apply { moveTo(x(seg.first().at), h); seg.forEach { lineTo(x(it.at), y(it.used)) }; lineTo(x(seg.last().at), h); close() }
                    drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = .26f), Color.Transparent), endY = h))
                    val stroke = Path().apply { moveTo(x(seg[0].at), y(seg[0].used)); seg.drop(1).forEach { lineTo(x(it.at), y(it.used)) } }
                    drawPath(stroke, line, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                // The pace, as on the desktop: a dashed estimate and a soft fan widening toward the reset.
                if (rate != null && projectionEnd != null && projectionEnd > last.at) {
                    val endUsed = last.used + rate / 100.0 * (projectionEnd - last.at) / HOUR_MS
                    val a = Offset(x(last.at), y(last.used)); val b = Offset(x(projectionEnd), y(endUsed))
                    // The band spans the observed 10th–90th percentile paces when the PC sends them (Windows 2.6), else a soft ±35%.
                    val hours = (projectionEnd - last.at).toDouble() / HOUR_MS
                    val low = window.forecast?.lowRate; val high = window.forecast?.highRate
                    val spread = ((endUsed - last.used) * .35).coerceAtLeast(.01)
                    val top = if (high != null) last.used + high / 100.0 * hours else endUsed + spread
                    val bottom = if (low != null) last.used + low / 100.0 * hours else (endUsed - spread).coerceAtLeast(last.used)
                    val fan = Path().apply { moveTo(a.x, a.y); lineTo(b.x, y(maxOf(top, endUsed))); lineTo(b.x, y(minOf(bottom, endUsed).coerceAtLeast(last.used))); close() }
                    drawPath(fan, forecastColor.copy(alpha = .16f))
                    drawLine(forecastColor, a, b, 1.8.dp.toPx(), StrokeCap.Round, dashed)
                }
            }
            // Events along the bottom: confirmed resets in the provider's colour, others in the forecast tint.
            events.forEach { e -> drawCircle(if (e.kind == "Confirmed reset") accent else forecastColor.copy(alpha = .8f), 3.dp.toPx(), Offset(x(e.at), h + 8.dp.toPx())) }
            val focus = selected?.let { points.getOrNull(it) } ?: last
            val at = Offset(x(focus.at), y(focus.used))
            if (selected != null) drawLine(t.text.copy(alpha = .35f), Offset(at.x, 0f), Offset(at.x, h), 1.dp.toPx())
            if (at.x in -1f..w + 1f) { drawCircle(line.copy(alpha = .25f), 10.dp.toPx(), at); drawCircle(Color.White, 5.dp.toPx(), at); drawCircle(line, 3.5.dp.toPx(), at) }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            listOf(view.first, (view.first + view.second) / 2, view.second).forEachIndexed { i, at ->
                Text(if (i == 2 && abs(at - now) < 10 * 60_000) "Now" else label(at), fontSize = 10.5.sp, color = t.faint, textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End }, modifier = Modifier.weight(1f), maxLines = 1)
            }
        }
        if (rate != null || budget != null || window.reset != null) Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            @Composable fun Key(color: Color, text: String) { Row(verticalAlignment = Alignment.CenterVertically) { Box(Modifier.size(width = 14.dp, height = 3.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(6.dp)); Text(text, fontSize = 11.sp, color = t.muted) } }
            Key(line, "Recorded")
            if (rate != null) Key(forecastColor, "Estimate")
            if (window.reset != null) Key(accent, "Reset")
            if (budget != null) Key(t.ink.copy(alpha = .5f), "Budget ${budget.limit}%")
        }
    }
}
