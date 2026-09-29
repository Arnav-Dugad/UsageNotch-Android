package io.github.arnavdugad.usagenotch

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import kotlin.math.cos
import kotlin.math.sin

/** A provider's brand mark: Claude and Gemini in their own colours, OpenAI and Cursor in the theme ink. */
@Composable fun Logo(id: String, modifier: Modifier = Modifier) {
    val t = LocalTokens.current
    if (Logos.colored(id)) Image(painterResource(Logos.resource(id)), null, modifier)
    else Icon(painterResource(Logos.resource(id)), null, modifier, tint = t.ink)
}

/** A glass card. A [color] gives a flat inner surface instead, for elements that sit inside another glass card. */
@Composable fun Panel(modifier: Modifier = Modifier, radius: Dp = 26.dp, color: Color? = null, tint: Color = Color.Unspecified, content: @Composable () -> Unit) {
    val shape = com.kyant.shapes.RoundedRectangle(radius)
    if (color != null) Box(modifier.clip(shape).background(color)) { content() }
    else GlassPanel(modifier, shape, tint = tint) { content() }
}
/** Surface for elements inside a glass card. */
@Composable fun innerSurface(): Color = if (LocalTokens.current.dark) Color(0x0FFFFFFF) else Color(0x0D000000)

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
 * bright head. Above 90% used it breathes with a soft glow. When a limit renews while you watch, particles burst from it.
 * Outer ring: the session window, with a hairline for the time left before it resets. Inner ring: the weekly window.
 */
@Composable fun UsageRing(p: Provider, remaining: Boolean, now: Long, reduced: Boolean, size: Dp = 58.dp, modifier: Modifier = Modifier, budget: Budget? = null) {
    val t = LocalTokens.current; val context = LocalContext.current; val haptics = LocalHapticFeedback.current
    val accent = LocalAccent.current(p.id)
    val s = p.sessionWindow(); val w = p.weeklyWindow()?.takeIf { it.id != s?.id }
    val renewed = s?.resetPassed(now) == true
    val target = s?.takeUnless { renewed }?.shown(remaining)?.toFloat() ?: 0f
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
    // Renewal burst: only for a reset that happens while the ring is on screen.
    var wasRenewed by remember { mutableStateOf(renewed) }
    val burst = remember { Animatable(1f) }
    LaunchedEffect(renewed) {
        if (renewed && !wasRenewed && !reduced) { haptics.performHapticFeedback(HapticFeedbackType.Confirm); burst.snapTo(0f); burst.animateTo(1f, tween(1150, easing = LinearOutSlowInEasing)) }
        wasRenewed = renewed
    }
    val hot = s != null && !renewed && s.used >= .9 && !reduced
    val glow = if (hot) rememberInfiniteTransition(label = "Glow").animateFloat(.25f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Breath").value else 0f
    val track = t.track.toArgb(); val ink = t.ink.toArgb()
    val logo = remember(p.id, ink) { Logos.drawable(context, p.id, ink) }
    Canvas(modifier.size(size)) {
        drawIntoCanvas { canvas ->
            RingPainter.draw(canvas.nativeCanvas, this.size.width / 2, this.size.height / 2, this.size.minDimension, density,
                RingPainter.specFor(p, remaining, now, track, logo, if (s != null) primary.value.coerceIn(0f, 1f) else null, if (w != null) inner.value.coerceIn(0f, 1f) else null,
                    glow = glow, head = sweeping && !reduced, ink = ink, budget = budget))
        }
        val progress = burst.value
        if (progress < 1f) {
            val c = center; val r = this.size.minDimension / 2
            val fade = 1f - progress
            drawCircle(Color(0xFF2EE0A8).copy(alpha = .55f * fade), r * (.82f + .3f * progress), c, style = Stroke(r * .05f * fade + 1f))
            val colors = listOf(Color(0xFF2EE0A8), accent, Color.White, Color(0xFF66E062))
            for (i in 0 until 26) {
                val jitter = ((i * 7919) % 97) / 97f
                val angle = Math.toRadians(i * (360.0 / 26) + jitter * 9 - 90)
                val travel = r * (.72f + (.55f + .35f * jitter) * FastOutSlowInEasing.transform(progress))
                val fall = r * .12f * progress * progress
                val pos = Offset(c.x + travel * cos(angle).toFloat(), c.y + travel * sin(angle).toFloat() + fall)
                drawCircle(colors[i % colors.size].copy(alpha = fade * (.65f + .35f * jitter)), r * (.045f + .03f * jitter) * (1f - .5f * progress), pos)
            }
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
@Composable fun DockCell(p: Provider, remaining: Boolean, now: Long, reduced: Boolean, selected: Boolean, compact: Boolean = false, budget: Budget? = null, onClick: () -> Unit) {
    val t = LocalTokens.current; val haptics = LocalHapticFeedback.current
    val source = remember { MutableInteractionSource() }; val pressed by source.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed && !reduced) .92f else 1f, spring(dampingRatio = .5f, stiffness = 600f), label = "Press")
    val s = p.sessionWindow()
    val headline = headlineOf(p, remaining, now)
    val second = secondaryOf(p, remaining, now)
    val share = LocalRingShare.current
    Column(
        Modifier.scale(scale).clip(RoundedCornerShape(22.dp))
            .clickable(source, null, role = Role.Button) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onClick() }
            .semantics(mergeDescendants = true) { contentDescription = "${p.name}, $headline${if (remaining && s != null && !s.resetPassed(now)) " left" else ""}" + (second?.let { ", $it" } ?: "") + ". Open details"; this.selected = selected }
            .padding(horizontal = if (compact) 6.dp else 8.dp, vertical = if (compact) 4.dp else 8.dp).widthIn(min = if (compact) 48.dp else 70.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        UsageRing(p, remaining, now, reduced, if (compact) 40.dp else 64.dp, share?.invoke(p.id) ?: Modifier, budget)
        Spacer(Modifier.height(if (compact) 3.dp else 8.dp))
        RollingText(headline, if (compact) 12.sp else 16.sp, t.text, FontWeight.SemiBold, reduced)
        if (!compact) Text(second ?: p.name, fontSize = 11.sp, color = t.muted, maxLines = 1)
    }
}

@Composable fun StatusPill(text: String) {
    val color = Color(when (text) { "Live" -> 0xFF2EE0A8.toInt(); "Error" -> Palette.DANGER; "Checking" -> Palette.LOADING; "Preview" -> 0xFF8FB6FF.toInt(); else -> Palette.WARNING })
    val t = LocalTokens.current
    Row(Modifier.clip(CircleShape).background(color.copy(alpha = .14f)).padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(6.dp).clip(CircleShape).background(color)); Spacer(Modifier.width(5.dp))
        Text(text, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(Palette.readable(color.toArgb(), if (t.dark) 0xFF1A1E27.toInt() else 0xFFF6F7FA.toInt())))
    }
}

/** A provider on glass: header, then each limit. Tap the header for the full-screen view. */
@Composable fun ProviderCard(p: Provider, now: Long, remaining: Boolean, clock24: Boolean, reduced: Boolean, offline: Boolean, preview: Boolean, onDashboard: (String) -> Unit, modifier: Modifier = Modifier, budgets: List<Budget> = emptyList(), onOpen: (() -> Unit)? = null) {
    val t = LocalTokens.current; val accent = LocalAccent.current(p.id)
    Panel(modifier.fillMaxWidth(), tint = accent.copy(alpha = .35f)) { Column(Modifier.padding(16.dp)) {
        Row(Modifier.clip(RoundedCornerShape(14.dp)).then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open ${p.name} details") { onOpen() } else Modifier), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(36.dp).clip(com.kyant.shapes.RoundedRectangle(11.dp)).background(innerSurface()), contentAlignment = Alignment.Center) { Logo(p.id, Modifier.size(20.dp)) }
            Column(Modifier.weight(1f).padding(horizontal = 11.dp)) {
                Text(p.name, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                val updated = p.updatedAt ?: p.windows.maxOfOrNull { it.at }
                Text(listOfNotNull(updated?.let { ClockText.updated(it, now, clock24) }, p.account).joinToString(" · "), fontSize = 11.5.sp, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            StatusPill(if (preview) "Preview" else statusPill(p, now, offline))
            p.manageUrl?.let { url -> IconButton(onClick = { onDashboard(url) }, modifier = Modifier.size(36.dp)) { Icon(Icons.AutoMirrored.Outlined.OpenInNew, "Open ${p.name} dashboard", tint = t.muted, modifier = Modifier.size(18.dp)) } }
        }
        p.statusText?.let { Text(it, fontSize = 12.5.sp, lineHeight = 18.sp, color = t.muted, modifier = Modifier.padding(top = 10.dp)) }
        if (p.windows.isNotEmpty() || p.extras.isNotEmpty()) Spacer(Modifier.height(12.dp))
        p.windows.forEachIndexed { index, w -> WindowRow(w, index, now, remaining, clock24, reduced, budgets.firstOrNull { it.window == w.id }); if (index < p.windows.lastIndex || p.extras.isNotEmpty()) Spacer(Modifier.height(8.dp)) }
        p.extras.forEachIndexed { index, e ->
            Panel(Modifier.fillMaxWidth(), 18.dp, innerSurface()) { Row(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(e.label, fontSize = 13.5.sp, color = t.text, modifier = Modifier.weight(1f))
                Text(amountText(e.usedAmount, e.limitAmount, e.unit, null).ifBlank { "No cap" }, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, color = t.muted)
            } }
            if (index < p.extras.lastIndex) Spacer(Modifier.height(8.dp))
        }
    } }
}

/** One limit: label and percentage, a colour-graded bar with the budget tick, then the reset and the pace. */
@Composable fun WindowRow(w: UsageWindow, index: Int, now: Long, remaining: Boolean, clock24: Boolean, reduced: Boolean, budget: Budget? = null) {
    val t = LocalTokens.current
    val renewed = w.resetPassed(now)
    val ramp = Color(Palette.usage(w.used))
    val surface = if (t.dark) 0xFF1B2029.toInt() else 0xFFF3F5F8.toInt()
    val valueColor = if (renewed) t.muted else Color(Palette.readable(ramp.toArgb(), surface))
    var entered by remember { mutableStateOf(false) }; LaunchedEffect(Unit) { entered = true }
    val fill by animateFloatAsState(if (entered && !renewed) w.shown(remaining).toFloat() else 0f, if (reduced) snap() else spring(dampingRatio = .75f, stiffness = 90f, visibilityThreshold = .001f), label = "Bar")
    val budgetShown = budget?.let { (if (remaining) 1f - it.limit / 100f else it.limit / 100f).coerceIn(0f, 1f) }
    Panel(Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}, 18.dp, innerSurface()) { Column(Modifier.padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(w.label, fontSize = 13.5.sp, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(end = 8.dp))
            Text(if (renewed) "Renewed" else "${w.percent(remaining)}% ${if (remaining) "left" else "used"}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = valueColor)
        }
        Canvas(Modifier.padding(top = 9.dp, bottom = 8.dp).fillMaxWidth().height(6.dp)) {
            val r = size.height / 2
            drawRoundRect(t.track, cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            if (fill > 0f) drawRoundRect(Brush.horizontalGradient(listOf(ramp.copy(alpha = .55f), ramp), endX = size.width * fill), size = size.copy(width = (size.width * fill).coerceAtLeast(size.height)), cornerRadius = androidx.compose.ui.geometry.CornerRadius(r))
            budgetShown?.takeIf { !renewed }?.let { b -> val x = size.width * b; drawLine(t.ink, Offset(x, -3.dp.toPx()), Offset(x, size.height + 3.dp.toPx()), 2.dp.toPx(), StrokeCap.Round) }
        }
        Text(if (renewed) "Awaiting a new reading" else if (w.reset == null) "Reset time not reported" else ClockText.resetsAt(w.reset, now, clock24) + " · " + ClockText.countdown(w.reset, now), fontSize = 11.5.sp, color = t.muted)
        if (!renewed) {
            budget?.let { Budgets.status(it, w, now, clock24) }?.let { st ->
                val color = when (st.state) { Budgets.State.Over -> Color(Palette.DANGER); Budgets.State.Pace, Budgets.State.Near -> Color(Palette.WARNING); else -> t.muted }
                Text(st.text, fontSize = 11.5.sp, color = Color(Palette.readable(color.toArgb(), surface)), modifier = Modifier.padding(top = 4.dp))
            }
            forecastLine(w.forecast, now, clock24)?.let { line ->
                Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Outlined.TrendingUp, null, tint = t.faint, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(5.dp))
                    Text(line, fontSize = 11.5.sp, color = t.muted)
                }
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

/** A short glass notice with optional actions. */
@Composable fun Notice(title: String, body: String? = null, action: String? = null, secondary: String? = null, clicked: () -> Unit = {}, tone: Color = Color(Palette.WARNING), secondaryClicked: () -> Unit = {}) {
    val t = LocalTokens.current
    Panel(Modifier.fillMaxWidth(), tint = tone.copy(alpha = .4f)) { Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = if (action != null || secondary != null) 6.dp else 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).clip(CircleShape).background(tone)); Spacer(Modifier.width(9.dp))
            Text(title, fontSize = 14.sp, color = t.text, fontWeight = FontWeight.SemiBold)
        }
        body?.let { Text(it, color = t.muted, fontSize = 12.5.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 5.dp, start = 17.dp)) }
        if (action != null || secondary != null) Row(Modifier.padding(start = 5.dp)) { if (action != null) TextButton(onClick = clicked) { Text(action, color = t.accent) }; if (secondary != null) TextButton(onClick = secondaryClicked) { Text(secondary, color = t.muted) } }
    } }
}
@Composable fun SectionLabel(text: String) { Text(text, fontSize = 12.sp, letterSpacing = .4.sp, color = LocalTokens.current.muted, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(start = 18.dp, top = 8.dp)) }

/** An iOS-style settings row: title, an optional short hint, and a switch. The whole row is the touch target. */
@Composable fun SettingToggle(title: String, detail: String?, value: Boolean, changed: (Boolean) -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().toggleableRow(value, changed).semantics { contentDescription = title }.padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, fontSize = 15.5.sp, color = t.text)
            detail?.let { Text(it, fontSize = 12.sp, color = t.faint, lineHeight = 16.sp, modifier = Modifier.padding(top = 2.dp)) }
        }
        Switch(checked = value, onCheckedChange = null, colors = SwitchDefaults.colors(
            checkedTrackColor = Color(0xFF34C759), checkedThumbColor = Color.White, checkedBorderColor = Color.Transparent,
            uncheckedTrackColor = t.track, uncheckedThumbColor = Color.White, uncheckedBorderColor = Color.Transparent))
    }
}
private fun Modifier.toggleableRow(value: Boolean, changed: (Boolean) -> Unit) = this.then(Modifier.clickable(role = Role.Switch) { changed(!value) }).semantics { toggleableState = androidx.compose.ui.state.ToggleableState(value) }
