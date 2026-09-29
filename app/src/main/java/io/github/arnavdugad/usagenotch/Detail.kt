package io.github.arnavdugad.usagenotch

import androidx.activity.compose.PredictiveBackHandler
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
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
 * One provider, full screen: a large ring (morphed from its dock ring), the pace, the best time to start, a budget,
 * a scrubbable 24-hour chart, every limit, and its accent colour. Back, including the predictive back gesture,
 * shrinks it toward the dashboard.
 */
@Composable fun ProviderDetail(
    p: Provider, now: Long, remaining: Boolean, clock24: Boolean, reduced: Boolean, offline: Boolean, preview: Boolean,
    ringModifier: Modifier, budgets: List<Budget>, onBudget: (String, Budget?) -> Unit, onAccent: (Int?) -> Unit,
    onDashboard: (String) -> Unit, onBack: () -> Unit,
) {
    val t = LocalTokens.current
    var backProgress by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler { progress ->
        try { progress.collect { backProgress = it.progress }; onBack() }
        catch (e: CancellationException) { backProgress = 0f; throw e }
    }
    val s = p.sessionWindow()
    val renewed = s?.resetPassed(now) == true
    val sessionBudget = s?.let { w -> budgets.firstOrNull { it.window == w.id } }
    Column(
        Modifier.fillMaxSize().graphicsLayer {
            val scale = 1f - backProgress * .08f
            scaleX = scale; scaleY = scale
            shape = com.kyant.shapes.RoundedRectangle((backProgress * 40).dp); clip = backProgress > 0f
        }.verticalScroll(rememberScrollState()).statusBarsPadding().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassIconButton(Icons.AutoMirrored.Outlined.ArrowBack, "Back", onBack)
            Spacer(Modifier.width(12.dp))
            Logo(p.id, Modifier.size(22.dp)); Spacer(Modifier.width(9.dp))
            Text(p.name, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
            StatusPill(if (preview) "Preview" else statusPill(p, now, offline))
            p.manageUrl?.let { url -> Spacer(Modifier.width(8.dp)); GlassIconButton(Icons.AutoMirrored.Outlined.OpenInNew, "Open ${p.name} dashboard", { onDashboard(url) }) }
        }
        Column(Modifier.fillMaxWidth().padding(top = 28.dp, bottom = 18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            UsageRing(p, remaining, now, reduced, 224.dp, ringModifier, sessionBudget)
            Spacer(Modifier.height(18.dp))
            RollingText(headlineOf(p, remaining, now), 44.sp, t.text, FontWeight.Light, reduced)
            s?.let { w ->
                Text(if (renewed) "${w.label} renewed" else "${w.label} ${if (remaining) "remaining" else "used"}", fontSize = 13.sp, color = t.muted, modifier = Modifier.padding(top = 2.dp))
                if (!renewed && w.reset != null) Text(ClockText.resetsAt(w.reset, now, clock24) + " · " + ClockText.countdown(w.reset, now), fontSize = 12.sp, color = t.faint, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
            }
            secondaryOf(p, remaining, now)?.let { Text(it, fontSize = 12.sp, color = t.faint, modifier = Modifier.padding(top = 2.dp)) }
        }
        val cards = mutableListOf<@Composable () -> Unit>()
        s?.takeIf { !renewed }?.forecast?.takeIf { it.rate != null || it.summary.startsWith("Learning") }?.let { f -> cards += { PaceCard(f, s, now, clock24) } }
        BestStart.hint(p, now)?.let { hint -> cards += { StartCard(hint, now, clock24) } }
        s?.let { w -> cards += { BudgetCard(w, sessionBudget, now, clock24, reduced) { onBudget(w.id, it) } } }
        s?.let { w -> cards += { Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) { CardTitle(Icons.Outlined.ShowChart, "Last 24 hours"); Spacer(Modifier.height(8.dp)); ScrubChart(w, clock24) } } } }
        if (p.windows.isNotEmpty() || p.statusText != null) cards += {
            Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(12.dp)) {
                p.statusText?.let { Text(it, fontSize = 12.5.sp, lineHeight = 18.sp, color = t.muted, modifier = Modifier.padding(6.dp)) }
                p.windows.forEachIndexed { index, w -> WindowRow(w, index, now, remaining, clock24, reduced, budgets.firstOrNull { it.window == w.id }); if (index < p.windows.lastIndex) Spacer(Modifier.height(8.dp)) }
            } }
        }
        cards += { AccentCard(p, onAccent) }
        cards.forEachIndexed { index, card ->
            // Cards rise in one after another as the view opens.
            var shown by remember { mutableStateOf(reduced) }
            LaunchedEffect(Unit) { shown = true }
            AnimatedVisibility(shown, enter = if (reduced) EnterTransition.None else fadeIn(tween(320, delayMillis = 90 + index * 55)) + slideInVertically(spring(dampingRatio = .8f, stiffness = 260f)) { it / 5 }) { card() }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable private fun CardTitle(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, trailing: String? = null) {
    val t = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = t.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 11.5.sp, color = t.faint) }
    }
}

@Composable private fun PaceCard(f: Forecast, w: UsageWindow, now: Long, clock24: Boolean) {
    val t = LocalTokens.current
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        CardTitle(Icons.AutoMirrored.Outlined.TrendingUp, "Pace", f.confidence.takeIf { it.isNotBlank() })
        val headline = when {
            f.limitAt != null && f.limitAt <= now -> "Limit reached by now"
            f.limitAt != null -> "Runs out around ${ClockText.time(f.limitAt, clock24)}"
            // The PC sends the projection and pace in percentage points.
            f.projected != null -> "About ${f.projected.roundToInt().coerceAtMost(999)}% used by the reset"
            else -> f.summary.ifBlank { "Learning your pace" }
        }
        Text(headline, fontSize = 20.sp, fontWeight = FontWeight.Light, color = t.text, modifier = Modifier.padding(top = 8.dp))
        val parts = buildList {
            f.rate?.let { add("${if (it >= 0) "+" else ""}${"%.1f".format(java.util.Locale.ENGLISH, it)}% an hour") }
            if (f.limitAt != null && f.limitAt > now && w.reset != null) add("resets at ${ClockText.time(w.reset, clock24)}")
        }
        if (parts.isNotEmpty()) Text(parts.joinToString(" · "), fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(top = 3.dp))
    } }
}

/** When to start so the session renews in the middle of your usual busy hours. */
@Composable private fun StartCard(hint: StartHint, now: Long, clock24: Boolean) {
    val t = LocalTokens.current
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        CardTitle(Icons.Outlined.Schedule, "Best time to start")
        Text(if (hint.now) "Now" else (if (hint.tomorrow) "Tomorrow, " else "") + ClockText.time(hint.startAt, clock24), fontSize = 20.sp, fontWeight = FontWeight.Light, color = t.text, modifier = Modifier.padding(top = 8.dp))
        Text("Renews at ${ClockText.time(hint.renewAt, clock24)}, during your busy hours (${BestStart.hours(hint.busyFrom, hint.busyTo, clock24)})", fontSize = 12.sp, lineHeight = 17.sp, color = t.muted, modifier = Modifier.padding(top = 3.dp))
    } }
}

/** A budget for the session window: stay under a percentage, optionally by a time of day. */
@Composable private fun BudgetCard(w: UsageWindow, budget: Budget?, now: Long, clock24: Boolean, reduced: Boolean, onChange: (Budget?) -> Unit) {
    val t = LocalTokens.current
    var limit by remember(budget?.limit) { mutableFloatStateOf((budget?.limit ?: 60).toFloat()) }
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = if (budget != null) 14.dp else 6.dp).animateContentSize(if (reduced) snap() else spring(dampingRatio = .8f, stiffness = 380f))) {
        SettingToggle("Budget", if (budget == null) "Keep ${w.label.lowercase()} under a limit you choose" else null, budget != null) { on ->
            onChange(if (on) Budget("", w.id, limit.roundToInt(), null) else null)
        }
        if (budget != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Stay under", fontSize = 13.sp, color = t.muted, modifier = Modifier.weight(1f))
                Text("${limit.roundToInt()}%", fontSize = 20.sp, fontWeight = FontWeight.Light, color = t.text)
            }
            Slider(limit, { limit = (it / 5).roundToInt() * 5f }, valueRange = 5f..100f, steps = 18,
                onValueChangeFinished = { onChange(budget.copy(limit = limit.roundToInt())) },
                colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = t.accent, inactiveTrackColor = t.track, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                modifier = Modifier.semantics { contentDescription = "Budget limit" })
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                (listOf<Int?>(null) + listOf(12, 15, 18, 21).map { it * 60 }).forEach { minute ->
                    GlassChip(minute?.let { "By " + Budget.clock(it, clock24) } ?: "By the reset", budget.byMinute == minute) { onChange(budget.copy(byMinute = minute)) }
                }
            }
            val status = Budgets.status(budget.copy(limit = limit.roundToInt()), w, now, clock24)
            val color = when (status.state) { Budgets.State.Over -> Color(Palette.DANGER); Budgets.State.Pace, Budgets.State.Near -> Color(Palette.WARNING); else -> Color(0xFF2EE0A8) }
            Row(Modifier.padding(top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(8.dp))
                Text(status.text, fontSize = 13.sp, color = t.text)
            }
        }
    } }
}

/** The provider's accent: tints its card, charts and the ambient light. */
@Composable private fun AccentCard(p: Provider, onAccent: (Int?) -> Unit) {
    val t = LocalTokens.current; val current = LocalAccent.current(p.id).toArgb(); val haptics = LocalHapticFeedback.current
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        CardTitle(Icons.Outlined.Palette, "Colour")
        Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            val defaultColor = Accents.default(p.id)
            (listOf(defaultColor) + Accents.choices.filter { it != defaultColor }).forEachIndexed { index, color ->
                val on = color == current
                val ring by animateDpAsState(if (on) 3.dp else 0.dp, spring(dampingRatio = .5f, stiffness = 500f), label = "Swatch")
                Box(Modifier.size(34.dp).clip(CircleShape).border(ring, t.text, CircleShape).padding(if (on) 5.dp else 0.dp).clip(CircleShape).background(Color(color))
                    .clickable(role = Role.RadioButton) { haptics.performHapticFeedback(HapticFeedbackType.SegmentTick); onAccent(if (index == 0) null else color) }
                    .semantics { selected = on; contentDescription = if (index == 0) "${p.name} brand colour" else "Colour ${index + 1}" })
            }
        }
    } }
}

/** The 24-hour line with a crosshair: drag to read any reading, with a light haptic tick as it moves between readings. */
@Composable fun ScrubChart(window: UsageWindow, clock24: Boolean) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val points = window.points
    if (points.size < 2) { Text("Readings appear here as your PC records them.", fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(vertical = 12.dp)); return }
    val line = Color(Palette.usage(window.used))
    var selected by remember(window.id) { mutableStateOf<Int?>(null) }
    val start = window.at - 86_400_000; val span = 86_400_000.0
    fun nearest(x: Float, width: Float): Int {
        val at = start + (x / width).coerceIn(0f, 1f) * span
        return points.indices.minBy { abs(points[it].at - at) }
    }
    fun pick(index: Int) { if (index != selected) { selected = index; haptics.performHapticFeedback(HapticFeedbackType.SegmentFrequentTick) } }
    Column {
        Box(Modifier.fillMaxWidth().height(22.dp)) {
            val r = selected?.let { points[it] }
            Text(if (r != null) "${(r.used * 100).roundToInt()}% used · ${ClockText.time(r.at, clock24)}" else "${(window.used * 100).roundToInt()}% used now",
                fontSize = 13.sp, fontWeight = if (r != null) FontWeight.SemiBold else FontWeight.Normal, color = if (r != null) t.text else t.muted)
        }
        Canvas(Modifier.fillMaxWidth().height(150.dp)
            .pointerInput(points) {
                detectDragGestures(onDragStart = { pick(nearest(it.x, size.width.toFloat())) }, onDragEnd = { selected = null }, onDragCancel = { selected = null }) { change, _ -> pick(nearest(change.position.x, size.width.toFloat())) }
            }
            .pointerInput(points) { detectTapGestures(onPress = { pick(nearest(it.x, size.width.toFloat())); tryAwaitRelease(); selected = null }) }
            .semantics { contentDescription = "${window.label} usage over 24 hours, ${points.size} readings, from ${(points.first().used * 100).roundToInt()} to ${(points.last().used * 100).roundToInt()} percent used." }) {
            for (i in 1..3) drawLine(t.hairline, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1.dp.toPx())
            fun xy(p: Reading) = Offset(((p.at - start) / span * size.width).toFloat().coerceIn(0f, size.width), size.height * (1 - p.used.toFloat().coerceIn(0f, 1f)))
            val segments = mutableListOf<MutableList<Reading>>()
            points.forEach { p -> val last = segments.lastOrNull()?.lastOrNull(); if (last != null && last.period == p.period && p.at - last.at in 1..1_200_000) segments.last().add(p) else segments.add(mutableListOf(p)) }
            segments.forEach { seg ->
                if (seg.size < 2) { drawCircle(line, 2.dp.toPx(), xy(seg[0])); return@forEach }
                val area = Path().apply { moveTo(xy(seg.first()).x, size.height); seg.forEach { lineTo(xy(it).x, xy(it).y) }; lineTo(xy(seg.last()).x, size.height); close() }
                drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = .28f), Color.Transparent)))
                val stroke = Path().apply { moveTo(xy(seg[0]).x, xy(seg[0]).y); seg.drop(1).forEach { lineTo(xy(it).x, xy(it).y) } }
                drawPath(stroke, line, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
            val focus = selected?.let { points[it] } ?: points.last()
            val at = xy(focus)
            if (selected != null) drawLine(t.text.copy(alpha = .35f), Offset(at.x, 0f), Offset(at.x, size.height), 1.dp.toPx())
            drawCircle(line.copy(alpha = .25f), 10.dp.toPx(), at); drawCircle(Color.White, 5.dp.toPx(), at); drawCircle(line, 3.5.dp.toPx(), at)
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            listOf("24h ago", "12h", "Now").forEachIndexed { i, label -> Text(label, fontSize = 10.5.sp, color = t.faint, textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End }, modifier = Modifier.weight(1f)) }
        }
    }
}
