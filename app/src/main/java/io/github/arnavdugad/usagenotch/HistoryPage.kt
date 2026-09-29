package io.github.arnavdugad.usagenotch

import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private fun pct(v: Double) = "${(v * 100).roundToInt()}%"
private fun hourText(h: Int, clock24: Boolean) = if (clock24) "%02d:00".format(Locale.ROOT, h) else "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "AM" else "PM"}"

/** History tab: 7 or 30 days of consumption, the current streak and the busiest hours, all recorded on the PC. */
fun LazyListScope.historyPage(providers: List<Provider>, state: HistoryState, clock24: Boolean, reduced: Boolean) {
    val withHistory = providers.filter { it.history.isNotEmpty() }
    if (withHistory.isEmpty()) {
        item(key = "h-empty") {
            Notice(if (providers.isEmpty()) "No history yet" else "History comes from your PC",
                if (providers.isEmpty()) "Pair with UsageNotch on Windows. Daily usage, streaks and your busiest hours appear here."
                else "Update UsageNotch on Windows to 2.4 or later. It sends 30 days of history from the readings it has already saved.", tone = LocalTokens.current.muted)
        }
        return
    }
    val provider = withHistory.firstOrNull { it.id == state.provider } ?: withHistory.first()
    val history = provider.history.firstOrNull { it.window == state.window } ?: provider.history.first()
    item(key = "h-pickers") { HistoryPickers(withHistory, provider, history, state) }
    item(key = "h-summary") { SummaryTiles(history, state.days) }
    item(key = "h-bars") { DailyBars(history, state.days, reduced) }
    item(key = "h-heat") { Heatmap(history, clock24) }
    item(key = "h-note") {
        Text("Consumption is the rise in ${history.label.lowercase()} between continuous readings on your PC, as a share of the limit. Days and hours without readings are shown as no data, never as zero.",
            fontSize = 11.5.sp, lineHeight = 17.sp, color = LocalTokens.current.faint, modifier = Modifier.padding(horizontal = 8.dp))
    }
}

@Stable class HistoryState(provider: String?, window: String?, days: Int) {
    var provider by mutableStateOf(provider); var window by mutableStateOf(window); var days by mutableIntStateOf(days)
    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<HistoryState, Any?>({ listOf(it.provider, it.window, it.days) }, { HistoryState(it[0] as String?, it[1] as String?, it[2] as Int) })
    }
}
@Composable fun rememberHistoryState() = rememberSaveable(saver = HistoryState.Saver) { HistoryState(null, null, 7) }

@Composable private fun Chip(label: String, on: Boolean, leading: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    val t = LocalTokens.current
    Row(Modifier.clip(RoundedCornerShape(14.dp)).background(if (on) t.raised else Color.Transparent).border(1.dp, if (on) t.accent.copy(alpha = .5f) else t.hairline, RoundedCornerShape(14.dp))
        .clickable(role = Role.RadioButton) { onClick() }.semantics { selected = on }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        leading?.let { it(); Spacer(Modifier.width(7.dp)) }
        Text(label, fontSize = 13.sp, color = if (on) t.text else t.muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
    }
}

@Composable private fun HistoryPickers(providers: List<Provider>, provider: Provider, history: History, state: HistoryState) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (providers.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { p -> Chip(p.name, p.id == provider.id, { Logo(p.id, Modifier.size(16.dp)) }) { state.provider = p.id; state.window = null } }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                provider.history.forEach { h -> Chip(h.label, h.window == history.window) { state.window = h.window } }
            }
            Spacer(Modifier.width(8.dp))
            val t = LocalTokens.current
            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(t.raised).padding(3.dp)) {
                listOf(7, 30).forEach { n ->
                    val on = state.days == n
                    Box(Modifier.clip(RoundedCornerShape(9.dp)).background(if (on) t.card else Color.Transparent).clickable(role = Role.RadioButton) { state.days = n }
                        .semantics { selected = on; contentDescription = "$n days" }.padding(horizontal = 11.dp, vertical = 6.dp)) {
                        Text("${n}d", fontSize = 12.sp, color = if (on) t.text else t.muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                    }
                }
            }
        }
    }
}

@Composable private fun SummaryTiles(history: History, days: Int) {
    val range = history.last(days); val observed = range.mapNotNull { it.used }
    val busiest = range.filter { it.used != null }.maxByOrNull { it.used!! }
    val tiles = listOf(
        Triple("Total", if (observed.isEmpty()) "—" else pct(observed.sum()), "summed over $days days"),
        Triple("Daily average", if (observed.isEmpty()) "—" else pct(observed.average()), "${observed.size} of ${range.size} days recorded"),
        Triple("Busiest day", busiest?.let { pct(it.used!!) } ?: "—", busiest?.let { runCatching { LocalDate.parse(it.date).format(DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH)) }.getOrDefault(it.date) } ?: "No usage yet"),
        Triple("Streak", "${history.streak} ${if (history.streak == 1) "day" else "days"}", if (history.streak > 0) "in a row with usage" else "starts with your next use"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value, detail) ->
                    val t = LocalTokens.current
                    Panel(Modifier.weight(1f)) { Column(Modifier.padding(14.dp).semantics(mergeDescendants = true) {}) {
                        Text(label, fontSize = 11.5.sp, color = t.muted)
                        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Light, color = t.text, modifier = Modifier.padding(top = 4.dp))
                        Text(detail, fontSize = 11.sp, color = t.faint, maxLines = 1)
                    } }
                }
            }
        }
    }
}

@Composable private fun DailyBars(history: History, days: Int, reduced: Boolean) {
    val t = LocalTokens.current
    val range = history.last(days)
    val max = (range.mapNotNull { it.used }.maxOrNull() ?: 0.0).coerceAtLeast(.05)
    val grow = remember(history.window, days) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(history.window, days) { if (!reduced) grow.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
    val dates = range.map { runCatching { LocalDate.parse(it.date) }.getOrNull() }
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text("Daily usage", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
        Text("Peak ${pct(max)} of the limit", fontSize = 11.5.sp, color = t.faint, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        Canvas(Modifier.fillMaxWidth().height(140.dp).semantics {
            contentDescription = "Daily usage for ${range.size} days: " + range.joinToString("; ") { d -> "${d.date} " + (d.used?.let { pct(it) } ?: "no data") }
        }) {
            if (range.isEmpty()) return@Canvas
            val slot = size.width / range.size; val bar = (slot * .62f).coerceAtMost(28.dp.toPx())
            for (i in 1..3) drawLine(t.hairline, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1.dp.toPx())
            range.forEachIndexed { i, d ->
                val x = slot * i + (slot - bar) / 2
                val used = d.used
                if (used == null) {
                    drawRoundRect(t.hairline, Offset(x, size.height - 3.dp.toPx()), Size(bar, 3.dp.toPx()), CornerRadius(2.dp.toPx()))
                } else {
                    val h = ((used / max).toFloat() * size.height * grow.value).coerceAtLeast(if (used > 0) 2.dp.toPx() else 1.dp.toPx())
                    val color = Color(Palette.usage((used / max).coerceIn(0.0, 1.0) * .9))
                    drawRoundRect(Brush.verticalGradient(listOf(color, color.copy(alpha = .55f)), startY = size.height - h, endY = size.height), Offset(x, size.height - h), Size(bar, h), CornerRadius((bar / 3).coerceAtMost(6.dp.toPx())))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (days <= 7) dates.forEach { d -> Text(d?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.NARROW, Locale.ENGLISH) ?: "", fontSize = 10.5.sp, color = t.faint, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            else listOf(dates.firstOrNull(), dates.getOrNull(dates.size / 2), dates.lastOrNull()).forEachIndexed { i, d ->
                Text(d?.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)) ?: "", fontSize = 10.5.sp, color = t.faint, textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End }, modifier = Modifier.weight(1f))
            }
        }
    } }
}

@Composable private fun Heatmap(history: History, clock24: Boolean) {
    val t = LocalTokens.current
    val max = (history.heat.maxOrNull() ?: 0.0).coerceAtLeast(.0001)
    val busiest = history.busiest(3)
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text("Busiest hours", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
        Text("Last 30 days by weekday and hour. Outlined cells had no readings.", fontSize = 11.5.sp, lineHeight = 16.sp, color = t.faint, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp))
        Row {
            Column(Modifier.padding(end = 6.dp)) { WEEKDAYS.forEach { Box(Modifier.height(15.dp), contentAlignment = Alignment.CenterStart) { Text(it.take(1), fontSize = 9.5.sp, color = t.faint) }; Spacer(Modifier.height(3.dp)) } }
            Column(Modifier.weight(1f)) {
                Canvas(Modifier.fillMaxWidth().height((7 * 18 - 3).dp).semantics {
                    contentDescription = if (busiest.isEmpty()) "No busy hours recorded yet." else "Busiest: " + busiest.joinToString("; ") { (d, h, v) -> "${WEEKDAYS[d]} ${hourText(h, clock24)}, ${pct(v)}" }
                }) {
                    val gap = 2.dp.toPx(); val cell = (size.width - gap * 23) / 24; val rowH = 15.dp.toPx(); val rowGap = 3.dp.toPx()
                    for (d in 0 until 7) for (h in 0 until 24) {
                        val i = d * 24 + h; val topLeft = Offset(h * (cell + gap), d * (rowH + rowGap)); val cellSize = Size(cell, rowH); val corner = CornerRadius(3.dp.toPx())
                        if (history.observed.getOrElse(i) { 0 } == 0) drawRoundRect(t.hairline, topLeft, cellSize, corner, style = Stroke(1.dp.toPx()))
                        else {
                            val v = (history.heat.getOrElse(i) { 0.0 } / max).toFloat()
                            drawRoundRect(t.track, topLeft, cellSize, corner)
                            if (v > 0f) drawRoundRect(t.accent.copy(alpha = .18f + .82f * v), topLeft, cellSize, corner)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    listOf(0, 6, 12, 18).forEach { h -> Text(hourText(h, clock24), fontSize = 9.5.sp, color = t.faint, modifier = Modifier.weight(1f)) }
                }
            }
        }
        if (busiest.isNotEmpty()) {
            HorizontalDivider(color = t.hairline, modifier = Modifier.padding(vertical = 12.dp))
            busiest.forEachIndexed { index, (d, h, v) ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", fontSize = 12.sp, color = t.accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(18.dp))
                    Text("${WEEKDAYS[d]} · ${hourText(h, clock24)}–${hourText((h + 1) % 24, clock24)}", fontSize = 13.sp, color = t.text, modifier = Modifier.weight(1f))
                    Text("${pct(v)} of the limit", fontSize = 12.sp, color = t.muted)
                }
            }
        }
    } }
}
