package io.github.arnavdugad.usagenotch

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** "1.2M", "340k", "820": compact token counts. */
internal fun tokens(n: Long): String = when {
    n >= 1_000_000_000 -> "%.2f".format(Locale.ENGLISH, n / 1e9).trimEnd('0').trimEnd('.') + "B"
    n >= 10_000_000 -> "%.0fM".format(Locale.ENGLISH, n / 1e6)
    n >= 1_000_000 -> "%.1fM".format(Locale.ENGLISH, n / 1e6)
    n >= 10_000 -> "%.0fk".format(Locale.ENGLISH, n / 1e3)
    n >= 1_000 -> "%.1fk".format(Locale.ENGLISH, n / 1e3)
    else -> n.toString()
}
internal fun dollars(v: Double): String = if (v >= 100) "$" + v.roundToInt() else "$" + "%.2f".format(Locale.ENGLISH, v)
private fun duration(ms: Long): String { val m = (ms / 60_000).coerceAtLeast(0); return when { m < 60 -> "$m min"; m % 60 == 0L -> "${m / 60} h"; else -> "${m / 60} h ${m % 60} min" } }
private fun dayTime(at: Long, clock24: Boolean) = DateTimeFormatter.ofPattern(if (clock24) "EEE HH:mm" else "EEE h:mm a", Locale.ENGLISH).withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(at))

@Composable private fun Title(icon: ImageVector, title: String, trailing: String? = null) {
    val t = LocalTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = t.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(title, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
        trailing?.let { Text(it, fontSize = 12.sp, color = t.faint) }
    }
}

/** Grows from 0 to 1 once when shown, for bars that rise in; instant with Reduce motion. */
@Composable private fun rise(key: Any, reduced: Boolean, duration: Int = 820): Float {
    val a = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key, reduced) { if (reduced) a.snapTo(1f) else a.animateTo(1f, tween(duration, easing = FastOutSlowInEasing)) }
    return a.value
}

/**
 * Tokens the coding tools on your PC recorded (Claude Code, Codex): today and this week, what that would cost at API
 * prices (an equivalent, not what your plan charges), 30 days of totals, and where they went by project and model.
 */
@Composable fun TokensCard(usage: TokenUsage, reduced: Boolean) {
    val t = LocalTokens.current
    val sources = usage.sources.filter { it.today != null || it.week != null || it.days.isNotEmpty() }
    if (sources.isEmpty()) return
    var chosen by rememberSaveable { mutableStateOf<String?>(null) }
    val source = sources.firstOrNull { it.id == chosen } ?: sources.first()
    val accent = LocalAccent.current(source.id)
    val grow = rise(source.id, reduced)
    Panel(Modifier.fillMaxWidth(), tint = accent.copy(alpha = .25f)) { Column(Modifier.padding(16.dp)) {
        Title(Icons.Outlined.DataUsage, "Tokens", source.label)
        if (sources.size > 1) Row(Modifier.padding(top = 10.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            sources.forEach { s -> GlassChip(s.label, s.id == source.id, leading = { Logo(s.id, Modifier.size(15.dp)) }) { chosen = s.id } }
        }
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("Today" to source.today, "7 days" to source.week).forEach { (label, totals) ->
                Panel(Modifier.weight(1f), 18.dp, innerSurface()) { Column(Modifier.padding(14.dp).semantics(mergeDescendants = true) {}) {
                    Text(label, fontSize = 12.sp, color = t.muted)
                    Text(totals?.let { tokens(it.total) } ?: "—", fontSize = 26.sp, fontWeight = FontWeight.Light, color = t.text)
                    Text(totals?.cost?.let { "≈ ${dollars(it)} at API prices" } ?: if (source.allDevices) "tokens, all devices" else "tokens", fontSize = 11.sp, color = t.faint, maxLines = 1)
                } }
            }
        }
        if (source.allDevices) tokenFacts(source)?.let { Text(it, fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(top = 10.dp)) }
        val days = source.days.takeLast(30)
        if (days.isNotEmpty()) {
            val max = (days.maxOfOrNull { it.total } ?: 0L).coerceAtLeast(1L)
            Text("30 days", fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
            Canvas(Modifier.fillMaxWidth().height(64.dp).semantics { contentDescription = "Daily tokens for ${days.size} days, most ${tokens(max)}" }) {
                val gap = 2.dp.toPx(); val bw = (size.width - gap * (days.size - 1)) / days.size
                days.forEachIndexed { i, d ->
                    val h = (size.height * d.total / max * grow).coerceAtLeast(if (d.total > 0) 2.dp.toPx() else 1.dp.toPx())
                    drawRoundRect(if (d.total > 0) Brush.verticalGradient(listOf(accent, accent.copy(alpha = .45f)), startY = size.height - h, endY = size.height) else SolidColor(t.hairline),
                        Offset(i * (bw + gap), size.height - h), Size(bw, h), CornerRadius(2.dp.toPx()))
                }
            }
        }
        @Composable fun Shares(title: String, list: List<TokenShare>) {
            if (list.isEmpty()) return
            Text(title, fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(top = 14.dp, bottom = 2.dp))
            list.forEach { s ->
                Column(Modifier.padding(vertical = 5.dp).semantics(mergeDescendants = true) {}) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(s.name, fontSize = 13.sp, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                        Text("${(s.share * 100).roundToInt()}% · ${tokens(s.total)}" + (s.cost?.let { " · ${dollars(it)}" } ?: ""), fontSize = 12.sp, color = t.muted)
                    }
                    Box(Modifier.padding(top = 4.dp).fillMaxWidth().height(4.dp).clip(CircleShape).background(t.track)) {
                        Box(Modifier.fillMaxWidth((s.share * grow).toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(accent))
                    }
                }
            }
        }
        Shares("Projects · 7 days", source.projects)
        Shares("Models · 7 days", source.models)
        Text(if (source.allDevices) "Totals come from OpenAI for this account, across every device. Projects and models come from the Codex transcripts on this PC."
            else "From the transcripts Claude Code and Codex keep on your PC. API-price equivalent: what these tokens would cost on the pay-as-you-go API, not what your plan charges.", fontSize = 11.sp, color = t.faint, modifier = Modifier.padding(top = 10.dp))
    } }
}

/** "Lifetime 1.89B tokens · busiest day 218M · longest streak 7 days", for account-wide totals. */
fun tokenFacts(source: TokenSource): String? {
    val parts = listOfNotNull(source.lifetime?.let { "Lifetime ${tokens(it)} tokens" }, source.peakDaily?.let { "busiest day ${tokens(it)}" },
        source.longestStreak?.takeIf { it > 0 }?.let { "longest streak $it day${if (it == 1) "" else "s"}" })
    return parts.takeIf { it.isNotEmpty() }?.joinToString(" · ")
}


/** Where this week's usage went, as the provider reports it (Claude: Claude Code, chat…). */
@Composable fun BreakdownCard(provider: Provider, reduced: Boolean) {
    val shares = provider.breakdown; if (shares.isEmpty()) return
    val t = LocalTokens.current; val accent = LocalAccent.current(provider.id); val grow = rise(provider.id + "b", reduced)
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Title(Icons.Outlined.PieChartOutline, "Where this week went", provider.weeklyWindow()?.label)
        shares.forEach { s ->
            Column(Modifier.padding(top = 10.dp).semantics(mergeDescendants = true) {}) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, fontSize = 13.sp, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    Text("${"%.0f".format(Locale.ENGLISH, s.percent)}%", fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = t.muted)
                }
                Box(Modifier.padding(top = 5.dp).fillMaxWidth().height(5.dp).clip(CircleShape).background(t.track)) {
                    Box(Modifier.fillMaxWidth((s.percent / 100 * grow).toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(accent))
                }
            }
        }
        Text("Shares of this week's usage, as ${provider.name} reports them.", fontSize = 11.sp, color = t.faint, modifier = Modifier.padding(top = 10.dp))
    } }
}

/** Finished periods: how much of each limit you used at its peak, and what was left on the table when it reset. */
@Composable fun UnusedCard(provider: Provider, history: History, reduced: Boolean) {
    val periods = history.unused.takeLast(12); if (periods.isEmpty()) return
    val t = LocalTokens.current; val accent = LocalAccent.current(provider.id); val grow = rise(history.window + "u", reduced)
    val unused = periods.map { (1 - it.peak).coerceIn(0.0, 1.0) }.average()
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Title(Icons.Outlined.PieChartOutline, "Unused capacity", "${periods.size} periods")
        Text("${(unused * 100).roundToInt()}% left unused", fontSize = 26.sp, fontWeight = FontWeight.Light, color = t.text, modifier = Modifier.padding(top = 8.dp))
        Text("on average, when the ${history.label.lowercase()} limit renewed", fontSize = 12.sp, color = t.faint)
        Canvas(Modifier.padding(top = 12.dp).fillMaxWidth().height(78.dp).semantics { contentDescription = "Peak use of the last ${periods.size} periods: " + periods.joinToString { "${(it.peak * 100).roundToInt()}%" } }) {
            val gap = 5.dp.toPx(); val bw = (size.width - gap * (periods.size - 1)) / periods.size; val r = CornerRadius(5.dp.toPx())
            periods.forEachIndexed { i, period ->
                val x = i * (bw + gap)
                drawRoundRect(t.hairline, Offset(x, 0f), Size(bw, size.height), r, style = Stroke(1.dp.toPx()))
                val h = size.height * period.peak.toFloat().coerceIn(0f, 1f) * grow
                drawRoundRect(Brush.verticalGradient(listOf(Color(Palette.usage(period.peak)), accent.copy(alpha = .55f)), startY = size.height - h, endY = size.height), Offset(x, size.height - h), Size(bw, h), r)
            }
        }
        Text("Filled: the most you used. Outline: the whole limit.", fontSize = 11.sp, color = t.faint, modifier = Modifier.padding(top = 6.dp))
    } }
}

/** Times a limit ran out in the last 30 days, and how long you waited for it to renew. */
@Composable fun HitsCard(history: History, clock24: Boolean) {
    val hits = history.hits; if (hits.isEmpty() && history.reliability == null) return
    val t = LocalTokens.current
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Title(Icons.Outlined.Block, "Limit reached", "30 days")
        Text(if (hits.isEmpty()) "Never" else "${hits.size} ${if (hits.size == 1) "time" else "times"}", fontSize = 26.sp, fontWeight = FontWeight.Light, color = t.text, modifier = Modifier.padding(top = 8.dp))
        val waits = hits.mapNotNull { h -> h.reset?.let { it - h.at }?.takeIf { it > 0 } }
        if (waits.isNotEmpty()) Text("Waited ${duration(waits.average().toLong())} on average for the reset", fontSize = 12.sp, color = t.faint)
        hits.takeLast(5).reversed().forEach { h ->
            Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(Color(Palette.DANGER))); Spacer(Modifier.width(9.dp))
                Text(dayTime(h.at, clock24), fontSize = 13.sp, color = t.text, modifier = Modifier.weight(1f))
                Text(h.reset?.let { "renewed after ${duration(it - h.at)}" } ?: "reset not reported", fontSize = 12.sp, color = t.muted)
            }
        }
    } }
}

/** How steady the provider's reported reset times were: kept, or moved within a period. */
@Composable fun ReliabilityCard(history: History) {
    val r = history.reliability ?: return; if (r.periods == 0) return
    val t = LocalTokens.current
    val share = r.fixed.toFloat() / r.periods
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Title(Icons.Outlined.Verified, "Reset reliability", "${r.periods} periods")
        Text("Kept in ${(share * 100).roundToInt()}%", fontSize = 26.sp, fontWeight = FontWeight.Light, color = t.text, modifier = Modifier.padding(top = 8.dp))
        Text(if (r.moved == 0) "In all ${r.periods} periods the reported reset time held." else "${r.fixed} of ${r.periods} periods kept their reported reset time; ${r.moved} moved by about ${"%.0f".format(Locale.ENGLISH, r.averageShiftMinutes)} min.", fontSize = 12.sp, color = t.faint)
        Box(Modifier.padding(top = 12.dp).fillMaxWidth().height(8.dp).clip(CircleShape).background(Color(Palette.WARNING).copy(alpha = .35f))) {
            Box(Modifier.fillMaxWidth(share.coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(Color(Palette.LIVE)))
        }
        Text("${r.confirmed} confirmed ${if (r.confirmed == 1) "reset" else "resets"} · ${r.changed} window ${if (r.changed == 1) "change" else "changes"}", fontSize = 11.5.sp, color = t.muted, modifier = Modifier.padding(top = 8.dp))
    } }
}

/** Work sessions the PC observed: continuous readings, what they used, and their fastest pace. */
@Composable fun SessionsCard(history: History, clock24: Boolean) {
    val sessions = history.sessions; if (sessions.isEmpty()) return
    val t = LocalTokens.current
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Title(Icons.Outlined.Timelapse, "Work sessions")
        sessions.take(6).forEach { s ->
            Column(Modifier.padding(top = 10.dp).semantics(mergeDescendants = true) {}) {
                Text("${dayTime(s.start, clock24)} – ${ClockText.time(s.end, clock24)}", fontSize = 13.sp, color = t.text)
                Text("${"%.1f".format(Locale.ENGLISH, s.used)}% of the limit used · fastest ${"%.0f".format(Locale.ENGLISH, s.peak)}% an hour · ${duration(s.end - s.start)}", fontSize = 12.sp, color = t.muted)
            }
        }
        Text("Sessions are runs of readings without a gap over 20 minutes, not keyboard activity.", fontSize = 11.sp, color = t.faint, modifier = Modifier.padding(top = 10.dp))
    } }
}

/** Resets, gaps and changes the PC recorded this week. */
@Composable fun EventsCard(history: History, clock24: Boolean) {
    val events = history.events; if (events.isEmpty()) return
    val t = LocalTokens.current
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Title(Icons.Outlined.EventNote, "Events", "7 days")
        events.takeLast(8).reversed().forEach { e ->
            val (icon, tone) = when (e.kind) {
                "Confirmed reset" -> Icons.Outlined.RestartAlt to Color(Palette.LIVE)
                "Sampling gap" -> Icons.Outlined.HourglassEmpty to t.faint
                "Reading unavailable" -> Icons.Outlined.CloudOff to Color(Palette.WARNING)
                else -> Icons.Outlined.SwapHoriz to Color(Palette.FORECAST)
            }
            Row(Modifier.padding(top = 10.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                Icon(icon, null, tint = tone, modifier = Modifier.size(16.dp).padding(top = 1.dp)); Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text("${e.kind} · ${dayTime(e.at, clock24)}", fontSize = 13.sp, color = t.text)
                    if (e.text.isNotBlank()) Text(e.text, fontSize = 11.5.sp, lineHeight = 16.sp, color = t.muted, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    } }
}
