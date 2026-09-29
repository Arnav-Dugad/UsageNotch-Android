package io.github.arnavdugad.usagenotch

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*

/** A provider's brand mark: Claude and Gemini in their own colours, OpenAI and Cursor in the theme ink. */
@Composable fun Logo(id: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    if (Logos.colored(id)) Image(painterResource(Logos.resource(id)), null, modifier)
    else Icon(painterResource(Logos.resource(id)), null, modifier, tint = t.ink)
}

@Composable fun Panel(modifier: Modifier = Modifier, radius: Dp = 20.dp, color: Color = LocalTokens.current.card, content: @Composable () -> Unit) {
    val t = LocalTokens.current; val shape = RoundedCornerShape(radius)
    Box(modifier.clip(shape).background(color).border(1.dp, t.hairline, shape)) { content() }
}

/**
 * The desktop dock ring, animated: sweeps in like the desktop's 460 ms ease-out and follows new readings.
 * Outer ring: the session window. Inner ring: the weekly window. Logo in the centre.
 */
@Composable fun UsageRing(p: Provider, remaining: Boolean, now: Long, reduced: Boolean, size: Dp = 58.dp) {
    val t = LocalTokens.current; val context = LocalContext.current
    var entered by remember { mutableStateOf(false) }; LaunchedEffect(Unit) { entered = true }
    val s = p.sessionWindow(); val w = p.weeklyWindow()?.takeIf { it.id != s?.id }
    val spec = tween<Float>(if (reduced) 0 else 460, easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f))
    val primary by animateFloatAsState(if (entered) (s?.takeUnless { it.resetPassed(now) }?.shown(remaining)?.toFloat() ?: 0f) else 0f, spec, label = "Ring")
    val secondary by animateFloatAsState(if (entered) (w?.shown(remaining)?.toFloat() ?: 0f) else 0f, spec, label = "Inner ring")
    val track = t.track.toArgb(); val ink = t.ink.toArgb()
    val logo = remember(p.id, ink) { Logos.drawable(context, p.id, ink) }
    Canvas(Modifier.size(size)) {
        drawIntoCanvas { canvas ->
            RingPainter.draw(canvas.nativeCanvas, this.size.width / 2, this.size.height / 2, this.size.minDimension, density,
                RingPainter.specFor(p, remaining, now, track, logo, if (s != null) primary else null, if (w != null) secondary else null))
        }
    }
}

/** One dock cell: ring, the headline percentage and the desktop's "7d 98%" figure. */
@Composable fun DockCell(p: Provider, remaining: Boolean, now: Long, reduced: Boolean, selected: Boolean, onClick: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }; val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) .94f else 1f, spring(dampingRatio = .55f, stiffness = 600f), label = "Press")
    val s = p.sessionWindow()
    val headline = when { s == null -> when (p.status) { "NeedsAuth" -> "Sign in"; "Error" -> "Error"; else -> "—" }; s.resetPassed(now) -> "Renewed"; else -> "${s.percent(remaining)}%" }
    val second = p.weeklyWindow()?.takeIf { it.id != s?.id }?.let { p.secondaryTag() + " " + if (it.resetPassed(now)) "new" else "${it.percent(remaining)}%" }
    Column(
        Modifier.scale(scale).clip(RoundedCornerShape(20.dp)).background(if (selected) t.raised else Color.Transparent)
            .clickable(source, null, role = Role.Tab) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = "${p.name}, $headline${if (remaining && s != null && !s.resetPassed(now)) " left" else ""}" + (second?.let { ", $it" } ?: ""); this.selected = selected }
            .padding(horizontal = 10.dp, vertical = 10.dp).widthIn(min = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UsageRing(p, remaining, now, reduced)
        Spacer(Modifier.height(7.dp))
        AnimatedContent(headline, transitionSpec = { (fadeIn(tween(180)) + slideInVertically { it / 3 }) togetherWith fadeOut(tween(120)) }, label = "Percent") { value ->
            Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text)
        }
        Text(second ?: p.name, fontSize = 11.sp, color = t.muted, maxLines = 1)
    }
}

@Composable fun StatusPill(text: String) {
    val color = Color(when (text) { "Live" -> 0xFF2EE0A8.toInt(); "Error" -> Palette.DANGER; "Checking" -> Palette.LOADING; "Preview" -> 0xFF8FB6FF.toInt(); else -> Palette.WARNING })
    val t = LocalTokens.current
    Box(Modifier.clip(RoundedCornerShape(8.dp)).background(color.copy(alpha = .16f)).padding(horizontal = 9.dp, vertical = 3.dp)) {
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(Palette.readable(color.toArgb(), t.card.toArgb())))
    }
}

/** A provider card laid out like the desktop popup. */
@Composable fun ProviderCard(p: Provider, now: Long, remaining: Boolean, clock24: Boolean, reduced: Boolean, offline: Boolean, preview: Boolean, onRefresh: (() -> Unit)?, onDashboard: (String) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    var inspecting by rememberSaveable(p.id) { mutableStateOf(false) }
    Panel(modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(t.raised), contentAlignment = Alignment.Center) { Logo(p.id, Modifier.size(19.dp)) }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                val updated = p.updatedAt ?: p.windows.maxOfOrNull { it.at }
                Text(updated?.let { ClockText.updated(it, now, clock24) } ?: "Account status", fontSize = 11.5.sp, color = t.faint)
                p.account?.let { Text(it, fontSize = 11.sp, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            }
            StatusPill(if (preview) "Preview" else statusPill(p, now, offline))
        }
        p.statusText?.let { Text(it, fontSize = 12.5.sp, lineHeight = 18.sp, color = t.muted, modifier = Modifier.padding(top = 12.dp)) }
        Spacer(Modifier.height(12.dp))
        if (p.windows.isEmpty() && p.extras.isEmpty() && p.statusText == null) Text("No limits reported yet. Open this provider in UsageNotch on Windows.", fontSize = 12.5.sp, color = t.muted)
        p.windows.forEachIndexed { index, w -> WindowCard(w, index, now, remaining, clock24, reduced); Spacer(Modifier.height(8.dp)) }
        p.extras.forEach { e ->
            Panel(Modifier.fillMaxWidth(), 13.dp, t.raised) { Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                Row { Text(e.label, fontSize = 13.sp, color = t.text, modifier = Modifier.weight(1f)); Text(amountText(e.usedAmount, e.limitAmount, e.unit, null).ifBlank { "No cap" }, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.muted) }
                e.detail?.let { Text(it, fontSize = 11.sp, color = t.faint, modifier = Modifier.padding(top = 6.dp)) }
            } }
            Spacer(Modifier.height(8.dp))
        }
        p.sessionWindow()?.let { primary ->
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { inspecting = !inspecting }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                val turn by animateFloatAsState(if (inspecting) 180f else 0f, tween(if (reduced) 0 else 240), label = "Chevron")
                Icon(Icons.Outlined.ExpandCircleDown, null, tint = t.text, modifier = Modifier.size(20.dp).graphicsLayer { rotationZ = turn })
                Spacer(Modifier.width(8.dp)); Text("Usage inspector", fontSize = 13.5.sp, color = t.text)
            }
            AnimatedVisibility(inspecting, enter = expandVertically(tween(if (reduced) 0 else 260)) + fadeIn(), exit = shrinkVertically(tween(if (reduced) 0 else 180)) + fadeOut()) {
                Column(Modifier.padding(top = 4.dp, bottom = 6.dp)) {
                    HistoryChart(primary)
                    Text("${primary.label}: ${primary.points.size} readings in the last 24 hours, recorded on your PC. The line shows percentage used; gaps and resets are never joined.", fontSize = 11.sp, lineHeight = 16.sp, color = t.faint, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        if (onRefresh != null || p.manageUrl != null) {
            HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                onRefresh?.let { CardButton("Refresh", it) }
                p.manageUrl?.let { url -> CardButton("Open dashboard") { onDashboard(url) } }
            }
        }
    } }
}

@Composable fun CardButton(label: String, onClick: () -> Unit) {
    val t = LocalTokens.current
    Box(Modifier.clip(RoundedCornerShape(12.dp)).background(t.raised).clickable(role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 9.dp)) {
        Text(label, fontSize = 13.sp, color = t.text, fontWeight = FontWeight.Medium)
    }
}

/** Desktop limit card: label and percentage, a colour-graded bar, amount and live countdown, and the local reset time. */
@Composable fun WindowCard(w: UsageWindow, index: Int, now: Long, remaining: Boolean, clock24: Boolean, reduced: Boolean) {
    val t = LocalTokens.current
    val renewed = w.resetPassed(now)
    val ramp = Color(Palette.usage(w.used))
    val valueColor = if (renewed) t.muted else Color(Palette.readable(ramp.toArgb(), t.raised.toArgb()))
    var entered by remember { mutableStateOf(false) }; LaunchedEffect(Unit) { entered = true }
    val fill by animateFloatAsState(if (entered && !renewed) w.shown(remaining).toFloat() else 0f, tween(if (reduced) 0 else 520, delayMillis = if (reduced) 0 else 65 * index, easing = FastOutSlowInEasing), label = "Bar")
    Panel(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, 13.dp, t.raised) { Column(Modifier.padding(start = 12.dp, end = 12.dp, top = 10.dp, bottom = 11.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.label, fontSize = 13.sp, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(if (renewed) "Renewed" else "${w.percent(remaining)}% ${if (remaining) "left" else "used"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
        }
        Box(Modifier.padding(top = 8.dp, bottom = 7.dp).fillMaxWidth().height(6.dp).clip(CircleShape).background(t.track)) {
            Box(Modifier.fillMaxWidth(fill).fillMaxHeight().clip(CircleShape).background(ramp))
        }
        Row {
            Text(amountText(w.usedAmount, w.limitAmount, w.unit, w.detail), fontSize = 11.sp, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(if (renewed) "Awaiting new reading" else ClockText.resetsIn(w.reset, now), fontSize = 11.sp, color = t.faint)
        }
        Text(ClockText.resetsAt(w.reset, now, clock24), fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(top = 5.dp))
    } }
}

@Composable fun HistoryChart(window: UsageWindow) {
    val t = LocalTokens.current
    val points = window.points
    if (points.size < 2) { Text("Your history will appear as readings arrive.", fontSize = 11.5.sp, color = t.muted, modifier = Modifier.padding(vertical = 12.dp)); return }
    val line = Color(Palette.usage(window.used))
    Canvas(Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = "${window.label} usage over 24 hours, ${points.size} readings, latest ${(window.used * 100).toInt()} percent used." }) {
        val start = window.at - 86_400_000; val span = 86_400_000.0
        for (i in 0..4) drawLine(t.hairline, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1.dp.toPx())
        fun xy(p: Reading) = Offset(((p.at - start) / span * size.width).toFloat().coerceIn(0f, size.width), size.height * (1 - p.used.toFloat().coerceIn(0f, 1f)))
        val segments = mutableListOf<MutableList<Reading>>()
        points.forEach { p -> val last = segments.lastOrNull()?.lastOrNull(); if (last != null && last.period == p.period && p.at - last.at in 1..1_200_000) segments.last().add(p) else segments.add(mutableListOf(p)) }
        segments.forEach { seg ->
            if (seg.size < 2) { drawCircle(line, 2.dp.toPx(), xy(seg[0])); return@forEach }
            val area = Path().apply { moveTo(xy(seg.first()).x, size.height); seg.forEach { lineTo(xy(it).x, xy(it).y) }; lineTo(xy(seg.last()).x, size.height); close() }
            drawPath(area, Brush.verticalGradient(listOf(line.copy(alpha = .22f), Color.Transparent)))
            val stroke = Path().apply { moveTo(xy(seg[0]).x, xy(seg[0]).y); seg.drop(1).forEach { lineTo(xy(it).x, xy(it).y) } }
            drawPath(stroke, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        drawCircle(line, 3.5.dp.toPx(), xy(points.last())); drawCircle(t.card, 1.6.dp.toPx(), xy(points.last()))
    }
}

@Composable fun Notice(title: String, body: String, action: String? = null, secondary: String? = null, clicked: () -> Unit = {}, tone: Color = Color(Palette.WARNING), secondaryClicked: () -> Unit = {}) {
    val t = LocalTokens.current
    Panel(Modifier.fillMaxWidth(), 18.dp) { Column(Modifier.padding(16.dp)) {
        Text(title, fontSize = 13.5.sp, color = Color(Palette.readable(tone.toArgb(), t.card.toArgb())), fontWeight = FontWeight.SemiBold)
        Text(body, color = t.muted, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp))
        if (action != null || secondary != null) Row { if (action != null) TextButton(onClick = clicked) { Text(action) }; if (secondary != null) TextButton(onClick = secondaryClicked) { Text(secondary, color = t.muted) } }
    } }
}
@Composable fun SectionLabel(text: String) { Text(text, fontSize = 11.sp, letterSpacing = 1.6.sp, color = LocalTokens.current.muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 6.dp, top = 6.dp)) }
@Composable fun SettingToggle(title: String, detail: String, value: Boolean, changed: (Boolean) -> Unit) {
    val t = LocalTokens.current
    // The whole row is the touch target, not only the switch.
    Row(Modifier.fillMaxWidth().toggleableRow(value, changed).semantics { contentDescription = title }.padding(vertical = 13.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, fontSize = 15.sp, color = t.text); Text(detail, fontSize = 12.sp, color = t.muted, lineHeight = 17.sp, modifier = Modifier.padding(top = 3.dp)) }
        Switch(checked = value, onCheckedChange = null)
    }
}
private fun Modifier.toggleableRow(value: Boolean, changed: (Boolean) -> Unit) = this.then(Modifier.clickable(role = Role.Switch) { changed(!value) }).semantics { toggleableState = androidx.compose.ui.state.ToggleableState(value) }
