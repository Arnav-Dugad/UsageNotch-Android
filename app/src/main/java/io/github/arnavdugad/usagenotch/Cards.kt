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
import androidx.compose.material.icons.automirrored.outlined.*
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
import androidx.compose.animation.core.Animatable

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
 * Numbers that roll digit by digit when they change: each changed digit slides up when the value grows and down when
 * it shrinks. Positions are matched from the right, so "9%" to "10%" rolls the units and slides in the tens.
 */
@Composable fun RollingText(text: String, fontSize: TextUnit, color: Color, weight: FontWeight = FontWeight.Normal, reduced: Boolean = false, modifier: Modifier = Modifier) {
    var previous by remember { mutableStateOf(text) }
    val grew = remember(text) { (text.filter { it.isDigit() }.toIntOrNull() ?: 0) >= (previous.filter { it.isDigit() }.toIntOrNull() ?: 0) }
    SideEffect { previous = text }
    // Read as one value ("78%"), not digit by digit.
    Row(modifier.clearAndSetSemantics { contentDescription = text }) {
        text.forEachIndexed { index, char ->
            key(text.length - index) {
                AnimatedContent(char, transitionSpec = {
                    if (reduced) (fadeIn(snap()) togetherWith fadeOut(snap())) else {
                        val dir = if (grew) 1 else -1
                        (slideInVertically(spring(dampingRatio = .8f, stiffness = 420f)) { it * dir } + fadeIn(tween(140))) togetherWith
                            (slideOutVertically(tween(160)) { -it * dir } + fadeOut(tween(120))) using SizeTransform(clip = true)
                    }
                }, label = "Digit") { c -> Text(c.toString(), fontSize = fontSize, color = color, fontWeight = weight) }
            }
        }
    }
}

/** Set while an expanded provider view can morph from a dock ring; see MainActivity. */
val LocalRingShare = staticCompositionLocalOf<(@Composable (String) -> Modifier)?> { null }

/**
 * The desktop dock ring, animated: sweeps in like the desktop's 460 ms ease-out, then springs to each new reading with a
 * bright head ("liquid" fill). Above 90% used it breathes with a soft glow, as the desktop does.
 * Outer ring: the session window. Inner ring: the weekly window. Logo in the centre.
 */
@Composable fun UsageRing(p: Provider, remaining: Boolean, now: Long, reduced: Boolean, size: Dp = 58.dp, modifier: Modifier = Modifier) {
    val t = LocalTokens.current; val context = LocalContext.current
    val s = p.sessionWindow(); val w = p.weeklyWindow()?.takeIf { it.id != s?.id }
    val target = s?.takeUnless { it.resetPassed(now) }?.shown(remaining)?.toFloat() ?: 0f
    val innerTarget = w?.shown(remaining)?.toFloat() ?: 0f
    val primary = remember { Animatable(0f) }; val inner = remember { Animatable(0f) }
    var sweeping by remember { mutableStateOf(false) }
    LaunchedEffect(target, reduced) {
        if (reduced) { primary.snapTo(target); return@LaunchedEffect }
        sweeping = true
        if (primary.value == 0f && target > 0f) primary.animateTo(target, tween(460, easing = CubicBezierEasing(0.33f, 1f, 0.68f, 1f)))
        else primary.animateTo(target, spring(dampingRatio = .5f, stiffness = 140f))
        sweeping = false
    }
    LaunchedEffect(innerTarget, reduced) { if (reduced) inner.snapTo(innerTarget) else inner.animateTo(innerTarget, spring(dampingRatio = .6f, stiffness = 120f)) }
    val hot = s != null && !s.resetPassed(now) && s.used >= .9 && !reduced
    val glow = if (hot) rememberInfiniteTransition(label = "Glow").animateFloat(.25f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Breath").value else 0f
    val track = t.track.toArgb(); val ink = t.ink.toArgb()
    val logo = remember(p.id, ink) { Logos.drawable(context, p.id, ink) }
    Canvas(modifier.size(size)) {
        drawIntoCanvas { canvas ->
            RingPainter.draw(canvas.nativeCanvas, this.size.width / 2, this.size.height / 2, this.size.minDimension, density,
                RingPainter.specFor(p, remaining, now, track, logo, if (s != null) primary.value.coerceIn(0f, 1f) else null, if (w != null) inner.value.coerceIn(0f, 1f) else null,
                    glow = glow, head = sweeping && !reduced))
        }
    }
}

internal fun headlineOf(p: Provider, remaining: Boolean, now: Long): String {
    val s = p.sessionWindow()
    return when { s == null -> when (p.status) { "NeedsAuth" -> "Sign in"; "Error" -> "Error"; else -> "—" }; s.resetPassed(now) -> "Renewed"; else -> "${s.percent(remaining)}%" }
}
internal fun secondaryOf(p: Provider, remaining: Boolean, now: Long): String? {
    val s = p.sessionWindow()
    return p.weeklyWindow()?.takeIf { it.id != s?.id }?.let { p.secondaryTag() + " " + if (it.resetPassed(now)) "new" else "${it.percent(remaining)}%" }
}

/** One dock cell: ring, the headline percentage and the desktop's "7d 98%" figure. */
@Composable fun DockCell(p: Provider, remaining: Boolean, now: Long, reduced: Boolean, selected: Boolean, compact: Boolean = false, onClick: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }; val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) .94f else 1f, spring(dampingRatio = .55f, stiffness = 600f), label = "Press")
    val s = p.sessionWindow()
    val headline = headlineOf(p, remaining, now)
    val second = secondaryOf(p, remaining, now)
    val share = LocalRingShare.current
    Column(
        Modifier.scale(scale).clip(RoundedCornerShape(20.dp)).background(if (selected) t.raised else Color.Transparent)
            .clickable(source, null, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = "${p.name}, $headline${if (remaining && s != null && !s.resetPassed(now)) " left" else ""}" + (second?.let { ", $it" } ?: "") + ". Open details"; this.selected = selected }
            .padding(horizontal = if (compact) 8.dp else 10.dp, vertical = if (compact) 6.dp else 10.dp).widthIn(min = if (compact) 52.dp else 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UsageRing(p, remaining, now, reduced, if (compact) 40.dp else 58.dp, share?.invoke(p.id) ?: Modifier)
        Spacer(Modifier.height(if (compact) 3.dp else 7.dp))
        RollingText(headline, if (compact) 12.sp else 15.sp, t.text, FontWeight.SemiBold, reduced)
        if (!compact) Text(second ?: p.name, fontSize = 11.sp, color = t.muted, maxLines = 1)
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
@Composable fun ProviderCard(p: Provider, now: Long, remaining: Boolean, clock24: Boolean, reduced: Boolean, offline: Boolean, preview: Boolean, onRefresh: (() -> Unit)?, onDashboard: (String) -> Unit, modifier: Modifier = Modifier, onOpen: (() -> Unit)? = null) {
    val t = LocalTokens.current
    var inspecting by rememberSaveable(p.id) { mutableStateOf(false) }
    Panel(modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(Modifier.clip(RoundedCornerShape(12.dp)).then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open ${p.name} details") { onOpen() } else Modifier), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(t.raised), contentAlignment = Alignment.Center) { Logo(p.id, Modifier.size(19.dp)) }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                val updated = p.updatedAt ?: p.windows.maxOfOrNull { it.at }
                Text(updated?.let { ClockText.updated(it, now, clock24) } ?: "Account status", fontSize = 11.5.sp, color = t.faint)
                p.account?.let { Text(it, fontSize = 11.sp, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp)) }
            }
            StatusPill(if (preview) "Preview" else statusPill(p, now, offline))
            if (onOpen != null) Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = t.faint, modifier = Modifier.padding(start = 4.dp).size(20.dp))
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
        if (!renewed) forecastLine(w.forecast, now, clock24)?.let { line ->
            Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Outlined.TrendingUp, null, tint = t.faint, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp))
                Text(line, fontSize = 11.5.sp, color = t.muted)
            }
        }
    } }
}

/** The desktop's pace estimate in one line, or null while it is still learning or unavailable. */
fun forecastLine(f: Forecast?, now: Long, clock24: Boolean): String? {
    f ?: return null
    f.limitAt?.let { return if (it <= now) "At this pace: limit reached by now" else "At this pace: limit around ${ClockText.time(it, clock24)}" }
    return f.summary.takeIf { it.startsWith("Estimated") || it == "No increase observed" || it == "Reported limit reached" }?.let { if (it.startsWith("Estimated")) "At this pace: ${it.removePrefix("Estimated ")}" else it }
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
