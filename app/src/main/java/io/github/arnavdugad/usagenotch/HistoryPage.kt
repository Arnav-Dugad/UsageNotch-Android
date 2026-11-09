package io.github.arnavdugad.usagenotch

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.*
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.zIndex
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import com.kyant.shapes.RoundedRectangle
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

/** History ranges in days. */
internal val RANGES = listOf(7, 30, 90)
private val WEEKDAYS = listOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")
private fun pct(v: Double) = "${(v * 100).roundToInt()}%"
private fun hourText(h: Int, clock24: Boolean) = if (clock24) "%02d:00".format(Locale.ROOT, h) else "${if (h % 12 == 0) 12 else h % 12} ${if (h < 12) "AM" else "PM"}"

@OptIn(ExperimentalSharedTransitionApi::class)
val LocalSharedScope = staticCompositionLocalOf<SharedTransitionScope?> { null }

/** Identifies an opened day: provider, window, date, and where it was opened from ("bar" or "cal"). */
data class DayKey(val provider: String, val window: String, val date: String, val origin: String) {
    fun encode() = listOf(provider, window, date, origin).joinToString("|")
    companion object { fun decode(value: String?) = value?.split("|")?.takeIf { it.size == 4 }?.let { DayKey(it[0], it[1], it[2], it[3]) } }
}

/**
 * History tab ([providers] already carry this phone's saved days, see [LocalHistory.enrich]): "This week" from the PC,
 * daily usage over 7, 30 or 90 days, all-time totals, a 90-day calendar, providers side by side and your busiest hours, then (from UsageNotch for
 * Windows 2.6) tokens by project and model, unused capacity, limits reached, reset reliability, work sessions and events.
 * Everything is recorded on the PC; sections it doesn't send are left out.
 */
fun LazyListScope.historyPage(providers: List<Provider>, state: HistoryState, clock24: Boolean, reduced: Boolean, openDay: DayKey?, onDay: (DayKey) -> Unit, tokens: TokenUsage? = null,
    report: WeekReport? = null) {
    val withHistory = providers.filter { it.history.isNotEmpty() }
    report?.let { r -> item(key = "h-week") { ThisWeekCard(r, providers, reduced) } }
    if (withHistory.isEmpty()) {
        tokens?.let { usage -> item(key = "h-tokens") { TokensCard(usage, reduced) } }
        item(key = "h-empty") {
            Notice(if (providers.isEmpty()) "No history yet" else "History comes from your PC",
                if (providers.isEmpty()) null else "Update UsageNotch on Windows to 2.4 or later.", tone = LocalTokens.current.muted)
        }
        return
    }
    val provider = withHistory.firstOrNull { it.id == state.provider } ?: withHistory.first()
    val history = provider.history.firstOrNull { it.window == state.window } ?: provider.history.first()
    item(key = "h-pickers") { HistoryPickers(withHistory, provider, history, state, reduced) }
    item(key = "h-summary") { SummaryTiles(history, state.days) }
    item(key = "h-share") { ShareWeekButton(providers, tokens, report) }
    item(key = "h-bars") { DailyBars(provider, history, state.days, reduced, openDay, onDay) }
    item(key = "h-calendar") { CalendarCard(provider, history, openDay, onDay) }
    (history.allTime ?: LocalHistory.allTime(history.calendar.ifEmpty { history.days }))?.let { all -> item(key = "h-alltime-${provider.id}-${history.window}") { AllTimeCard(provider, history, all, reduced) } }
    if (withHistory.size > 1) item(key = "h-compare") { CompareCard(withHistory, state.days, reduced) }
    if (history.observed.any { it > 0 }) item(key = "h-heat") { Heatmap(provider, history, clock24) }
    tokens?.let { usage -> item(key = "h-tokens") { TokensCard(usage, reduced) } }
    if (history.unused.isNotEmpty()) item(key = "h-unused-${history.window}") { UnusedCard(provider, history, reduced) }
    if (history.hits.isNotEmpty() || history.reliability != null) item(key = "h-hits-${history.window}") { HitsCard(history, clock24) }
    if (history.reliability != null) item(key = "h-reliability-${history.window}") { ReliabilityCard(history) }
    if (history.sessions.isNotEmpty()) item(key = "h-sessions-${history.window}") { SessionsCard(history, clock24) }
    if (history.events.isNotEmpty()) item(key = "h-events-${history.window}") { EventsCard(history, clock24) }
}

@Stable class HistoryState(provider: String?, window: String?, days: Int) {
    var provider by mutableStateOf(provider); var window by mutableStateOf(window); var days by mutableIntStateOf(days)
    companion object {
        val Saver = androidx.compose.runtime.saveable.listSaver<HistoryState, Any?>({ listOf(it.provider, it.window, it.days) }, { HistoryState(it[0] as String?, it[1] as String?, it[2] as Int) })
    }
}
@Composable fun rememberHistoryState() = rememberSaveable(saver = HistoryState.Saver) { HistoryState(null, null, 7) }

@Composable private fun HistoryPickers(providers: List<Provider>, provider: Provider, history: History, state: HistoryState, reduced: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (providers.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            providers.forEach { p -> GlassChip(p.name, p.id == provider.id, leading = { Logo(p.id, Modifier.size(16.dp)) }) { state.provider = p.id; state.window = null } }
        }
        if (provider.history.size > 1) Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            provider.history.forEach { h -> GlassChip(h.label, h.window == history.window) { state.window = h.window } }
        }
        // The range gets its own row so limit names are never clipped.
        GlassSegmented(RANGES.map { "$it days" }, RANGES.indexOf(state.days).coerceAtLeast(0), { state.days = RANGES[it] }, Modifier.fillMaxWidth(), reduced, RANGES.map { "Last $it days" })
    }
}

/**
 * The week or month at a glance, worded as on the desktop: one full limit is 100%, more than that is a multiple
 * ("5.6×" = five and a half limits' worth).
 */
@Composable private fun SummaryTiles(history: History, days: Int) {
    val range = history.range(days); val observed = range.mapNotNull { it.used }
    val total = observed.takeIf { it.isNotEmpty() }?.sum()
    val busiest = range.filter { it.used != null }.maxByOrNull { it.used!! }
    val tiles = listOf(
        Triple("Used in $days days", LimitText.of(total), LimitText.worth(total, history.label)),
        Triple("On an average day", if (observed.isEmpty()) "—" else LimitText.of(observed.average()),
            if (observed.isEmpty()) "no readings yet" else "of the limit, over ${observed.size} day${if (observed.size == 1) "" else "s"} with readings"),
        Triple("Busiest day", busiest?.let { runCatching { Days.short(LocalDate.parse(it.date)) }.getOrDefault(it.date) } ?: "—", busiest?.let { LimitText.ofLimit(it.used) } ?: "no usage recorded"),
        Triple("Streak", if (history.streak > 0) "${history.streak} day${if (history.streak == 1) "" else "s"}" else "—", "in a row with usage"),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        tiles.chunked(2).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { (label, value, detail) ->
                    val t = LocalTokens.current
                    Panel(Modifier.weight(1f)) { Column(Modifier.padding(16.dp).semantics(mergeDescendants = true) {}) {
                        Text(label, fontSize = 12.sp, color = t.muted)
                        Text(value, fontSize = if (value.length > 10) 24.sp else 28.sp, fontWeight = FontWeight.Light, color = t.text, maxLines = 1, softWrap = false, modifier = Modifier.padding(top = 2.dp))
                        Text(detail, fontSize = 11.5.sp, lineHeight = 15.sp, color = t.faint, maxLines = 2)
                    } }
                }
            }
        }
    }
}

/**
 * Daily usage as individual bars that grow one after another when the card scrolls in. Tapping a bar morphs it into
 * that day's card. Days without readings are short grey marks and can't be opened.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable private fun DailyBars(provider: Provider, history: History, days: Int, reduced: Boolean, openDay: DayKey?, onDay: (DayKey) -> Unit) {
    val t = LocalTokens.current; val accent = LocalAccent.current(provider.id); val shared = LocalSharedScope.current; val haptics = LocalHapticFeedback.current
    val range = history.range(days)
    val recorded = range.mapNotNull { it.used }
    val max = ((recorded.maxOrNull() ?: 0.0) * 1.08).coerceAtLeast(.05)
    val average = recorded.takeIf { it.isNotEmpty() }?.average()
    val step = when { days > 30 -> 8; days > 7 -> 22; else -> 55 }
    val total = 520 + step * range.size
    val grow = remember(history.window, days) { Animatable(if (reduced) total.toFloat() else 0f) }
    LaunchedEffect(history.window, days, reduced) { if (!reduced) grow.animateTo(total.toFloat(), tween(total, easing = LinearEasing)) else grow.snapTo(total.toFloat()) }
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Daily usage", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
            Text("100% = one full limit", fontSize = 12.sp, color = t.faint)
        }
        Spacer(Modifier.height(14.dp))
        Box(Modifier.fillMaxWidth().height(140.dp)) {
        // Reference lines behind the bars: one full limit (when a day comes near it) and your average.
        Canvas(Modifier.matchParentSize()) {
            val dash = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))
            if (max >= .9) { val y = size.height * (1 - (1.0 / max).toFloat()); drawLine(t.text.copy(alpha = .22f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx()) }
            average?.let { val y = size.height * (1 - (it / max).toFloat()); drawLine(t.muted.copy(alpha = .6f), Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash) }
        }
        // Labels sit on a small plate so they stay readable over dense 30 and 90 day bars.
        val plate = Modifier.clip(RoundedRectangle(6.dp)).background(t.background.copy(alpha = .78f)).padding(horizontal = 5.dp, vertical = 1.dp)
        if (max >= .9) Text("1 full limit", fontSize = 10.sp, lineHeight = 12.sp, color = t.faint, modifier = Modifier.align(Alignment.TopStart).offset(y = (140 * (1 - 1.0 / max)).dp - 16.dp).zIndex(1f).then(plate))
        average?.let { Text("average ${LimitText.of(it)}", fontSize = 10.sp, lineHeight = 12.sp, color = t.muted, modifier = Modifier.align(Alignment.TopEnd).offset(y = ((140 * (1 - it / max)).dp - 16.dp).coerceAtLeast((-14).dp)).zIndex(1f).then(plate)) }
        Row(Modifier.fillMaxWidth().height(140.dp).semantics {
            contentDescription = "Daily usage for ${range.size} days, where 100% is one full limit: " + range.joinToString("; ") { d -> "${d.date} " + (d.used?.let { LimitText.of(it) } ?: "no data") }
        }, horizontalArrangement = Arrangement.spacedBy(when { days > 30 -> 1.dp; days > 7 -> 3.dp; else -> 10.dp }), verticalAlignment = Alignment.Bottom) {
            range.forEachIndexed { i, d ->
                val raw = ((grow.value - i * step) / 520f).coerceIn(0f, 1f)
                val progress = if (raw >= 1f) 1f else FastOutSlowInEasing.transform(raw)
                val key = DayKey(provider.id, history.window, d.date, "bar")
                Box(Modifier.weight(1f).fillMaxHeight(), contentAlignment = Alignment.BottomCenter) {
                    val used = d.used
                    if (used == null) Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(t.hairline))
                    else {
                        val color = androidx.compose.ui.graphics.lerp(Color(Palette.usage((used / max).coerceIn(0.0, 1.0) * .9)), accent, .25f)
                        val height = (140 * (used / max).toFloat() * progress).coerceAtLeast(3f).dp
                        androidx.compose.animation.AnimatedVisibility(openDay != key, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
                            val morph = if (shared != null) with(shared) { Modifier.sharedBounds(rememberSharedContentState("day-${key.origin}-${d.date}"), this@AnimatedVisibility) } else Modifier
                            Box(morph.fillMaxWidth().height(height).clip(RoundedRectangle(when { days > 30 -> 1.5.dp; days > 7 -> 3.dp; else -> 8.dp }))
                                .background(Brush.verticalGradient(listOf(color, color.copy(alpha = .5f))))
                                .clickable(role = Role.Button, onClickLabel = "Open ${d.date}") { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onDay(key) })
                        }
                    }
                }
            }
        }
        }
        val dates = range.map { runCatching { LocalDate.parse(it.date) }.getOrNull() }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            if (days <= 7) dates.forEach { d -> Text(d?.dayOfWeek?.getDisplayName(java.time.format.TextStyle.NARROW, Locale.ENGLISH) ?: "", fontSize = 11.sp, color = t.faint, textAlign = TextAlign.Center, modifier = Modifier.weight(1f)) }
            else listOf(dates.firstOrNull(), dates.getOrNull(dates.size / 2), dates.lastOrNull()).forEachIndexed { i, d ->
                Text(d?.format(DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)) ?: "", fontSize = 11.sp, color = t.faint, textAlign = when (i) { 0 -> TextAlign.Start; 1 -> TextAlign.Center; else -> TextAlign.End }, modifier = Modifier.weight(1f))
            }
        }
        // How complete the numbers are: usage while the PC was off still counts; only its timing is unknown.
        range.mapNotNull { it.coverage }.takeIf { it.isNotEmpty() && it.size == range.size }?.let { cov ->
            Text("This PC observed ${(cov.average() * 100).roundToInt()}% of these ${range.size} days. Usage while it was off or asleep is still counted, on the day it next read your usage, so totals stay complete; only the hour it happened is unknown.",
                fontSize = 11.5.sp, lineHeight = 16.sp, color = t.faint, modifier = Modifier.padding(top = 10.dp))
        }
    } }
}

/** 90 days as a calendar of weeks (columns) by weekday (rows). Outlined days had no readings. Tap a day to open it. */
@Composable private fun CalendarCard(provider: Provider, history: History, openDay: DayKey?, onDay: (DayKey) -> Unit) {
    val t = LocalTokens.current; val accent = LocalAccent.current(provider.id); val haptics = LocalHapticFeedback.current
    val days = history.calendar.ifEmpty { history.days }.takeLast(90).mapNotNull { d -> runCatching { LocalDate.parse(d.date) to d.used }.getOrNull() }
    if (days.isEmpty()) return
    val first = days.first().first; val last = days.last().first
    val firstWeek = first.minusDays((first.dayOfWeek.value % 7).toLong())
    val weeks = (ChronoUnit.DAYS.between(firstWeek, last) / 7 + 1).toInt()
    val max = (days.mapNotNull { it.second }.maxOrNull() ?: 0.0).coerceAtLeast(.0001)
    val byDate = days.toMap()
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Calendar", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
            Text("${days.size} days", fontSize = 12.sp, color = t.faint)
        }
        Spacer(Modifier.height(12.dp))
        // Month labels above the first week of each month.
        BoxWithConstraints(Modifier.fillMaxWidth().padding(start = 16.dp).height(16.dp)) {
            val cell = maxWidth / weeks
            var shownMonth = -1
            for (w in 0 until weeks) {
                val start = firstWeek.plusDays(w * 7L)
                // Only days that are shown count, so a week that runs into next month doesn't label it early.
                val month = (0..6).map { start.plusDays(it.toLong()) }.firstOrNull { it.dayOfMonth == 1 && !it.isAfter(last) && !it.isBefore(first) }?.monthValue ?: if (w == 0) first.monthValue else -1
                if (month > 0 && month != shownMonth) { shownMonth = month
                    Text(java.time.Month.of(month).getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH), fontSize = 10.sp, color = t.faint, modifier = Modifier.offset(x = cell * w))
                }
            }
        }
        Row {
            Column(Modifier.width(16.dp)) { listOf("S", "M", "T", "W", "T", "F", "S").forEach { Box(Modifier.height(19.dp), contentAlignment = Alignment.CenterStart) { Text(it, fontSize = 9.5.sp, color = t.faint) } } }
            Canvas(Modifier.weight(1f).height(133.dp)
                .pointerInput(days) {
                    detectTapGestures { pos ->
                        val cellW = size.width / weeks.toFloat(); val cellH = size.height / 7f
                        val date = firstWeek.plusDays((pos.x / cellW).toInt() * 7L + (pos.y / cellH).toInt().coerceIn(0, 6))
                        if (byDate[date] != null) { haptics.performHapticFeedback(HapticFeedbackType.ContextClick); onDay(DayKey(provider.id, history.window, date.toString(), "cal")) }
                    }
                }
                .semantics { contentDescription = "Calendar of ${days.size} days. Busiest: " + (days.filter { it.second != null }.maxByOrNull { it.second!! }?.let { "${Days.short(it.first)}, ${LimitText.of(it.second)} of the limit" } ?: "none") }) {
                val cellW = size.width / weeks; val cellH = size.height / 7f; val gap = 3.dp.toPx(); val corner = CornerRadius(4.dp.toPx())
                for (w in 0 until weeks) for (d in 0 until 7) {
                    val date = firstWeek.plusDays(w * 7L + d)
                    if (date.isAfter(last) || date.isBefore(first)) continue
                    val topLeft = Offset(w * cellW + gap / 2, d * cellH + gap / 2); val cellSize = Size(cellW - gap, cellH - gap)
                    val used = byDate[date]
                    if (used == null) drawRoundRect(t.hairline, topLeft, cellSize, corner, style = Stroke(1.dp.toPx()))
                    else {
                        drawRoundRect(t.track, topLeft, cellSize, corner)
                        if (used > 0.0001) drawRoundRect(accent.copy(alpha = .22f + .78f * (used / max).toFloat().coerceIn(0f, 1f)), topLeft, cellSize, corner)
                    }
                    if (openDay?.origin == "cal" && openDay.date == date.toString()) drawRoundRect(t.text, topLeft, cellSize, corner, style = Stroke(2.dp.toPx()))
                }
            }
        }
    } }
}

/** Every provider's session usage per day on one chart, in their accent colours. */
@Composable private fun CompareCard(providers: List<Provider>, days: Int, reduced: Boolean) {
    val t = LocalTokens.current; val accentOf = LocalAccent.current
    val series = providers.mapNotNull { p -> val h = p.sessionWindow()?.let { s -> p.history.firstOrNull { it.window == s.id } } ?: p.history.firstOrNull(); h?.let { p to it.range(days) } }
    if (series.size < 2) return
    val max = (series.flatMap { it.second.mapNotNull { d -> d.used } }.maxOrNull() ?: 0.0).coerceAtLeast(.05)
    val draw = remember(days) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(days, reduced) { if (!reduced) draw.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) else draw.snapTo(1f) }
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Text("Side by side", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.text)
        Spacer(Modifier.height(12.dp))
        Canvas(Modifier.fillMaxWidth().height(130.dp).semantics { contentDescription = "Daily session usage by provider: " + series.joinToString("; ") { (p, d) -> "${p.name} " + (d.mapNotNull { it.used }.takeIf { it.isNotEmpty() }?.let { pct(it.average()) + " a day" } ?: "no readings") } }) {
            for (i in 1..3) drawLine(t.hairline, Offset(0f, size.height * i / 4), Offset(size.width, size.height * i / 4), 1.dp.toPx())
            series.forEach { (p, range) ->
                val color = accentOf(p.id)
                val n = range.size; if (n < 2) return@forEach
                fun x(i: Int) = size.width * i / (n - 1)
                fun y(v: Double) = size.height * (1 - (v / max).toFloat().coerceIn(0f, 1f))
                val path = Path(); var drawing = false
                val limit = (n - 1) * draw.value
                range.forEachIndexed { i, d ->
                    if (i > limit + .001f) return@forEachIndexed
                    val v = d.used
                    if (v == null) { drawing = false; return@forEachIndexed }
                    if (!drawing) { path.moveTo(x(i), y(v)); drawing = true } else path.lineTo(x(i), y(v))
                }
                drawPath(path, color, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                range.forEachIndexed { i, d -> if (i <= limit + .001f) d.used?.let { drawCircle(color, 2.6.dp.toPx(), Offset(x(i), y(it))) } }
            }
        }
        Row(Modifier.padding(top = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            series.forEach { (p, range) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(9.dp).clip(CircleShape).background(accentOf(p.id))); Spacer(Modifier.width(6.dp))
                    Text("${p.name} · ${range.mapNotNull { it.used }.takeIf { it.isNotEmpty() }?.let { LimitText.of(it.average()) } ?: "—"} a day", fontSize = 12.sp, color = t.muted)
                }
            }
        }
    } }
}

@Composable private fun Heatmap(provider: Provider, history: History, clock24: Boolean) {
    val t = LocalTokens.current; val accent = LocalAccent.current(provider.id)
    val max = (history.heat.maxOrNull() ?: 0.0).coerceAtLeast(.0001)
    val busiest = history.busiest(3)
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Busiest hours", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
            Text("30 days", fontSize = 12.sp, color = t.faint)
        }
        Spacer(Modifier.height(12.dp))
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
                            if (v > 0f) drawRoundRect(accent.copy(alpha = .2f + .8f * v), topLeft, cellSize, corner)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth().padding(top = 5.dp)) {
                    listOf(0, 6, 12, 18).forEach { h -> Text(hourText(h, clock24), fontSize = 9.5.sp, color = t.faint, modifier = Modifier.weight(1f)) }
                }
            }
        }
        if (busiest.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            busiest.forEachIndexed { index, (d, h, v) ->
                Row(Modifier.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("${index + 1}", fontSize = 12.sp, color = accent, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(18.dp))
                    Text("${WEEKDAYS[d]} · ${hourText(h, clock24)}–${hourText((h + 1) % 24, clock24)}", fontSize = 13.sp, color = t.text, modifier = Modifier.weight(1f))
                    Text("${pct(v)} of the limit", fontSize = 13.sp, color = t.muted)
                }
            }
        }
    } }
}

/**
 * The opened day: a glass card the tapped bar morphs into, over a dimmed backdrop. It shows the day's usage, how it
 * compares with your average, its rank, and what the other limits used that day.
 */
@OptIn(ExperimentalSharedTransitionApi::class)
@Composable fun DayOverlay(key: DayKey?, providers: List<Provider>, onClose: () -> Unit) {
    val t = LocalTokens.current; val shared = LocalSharedScope.current
    var last by remember { mutableStateOf(key) }
    if (key != null) last = key
    AnimatedVisibility(key != null, enter = fadeIn(tween(220)), exit = fadeOut(tween(220))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (t.dark) .45f else .25f)).clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Close") { onClose() })
    }
    AnimatedVisibility(key != null, enter = fadeIn(tween(200)) + scaleIn(initialScale = .92f), exit = fadeOut(tween(180)) + scaleOut(targetScale = .94f)) {
        val k = last ?: return@AnimatedVisibility
        val insight = remember(k, providers) { Days.insight(providers, k.provider, k.window, k.date) }
        val provider = providers.firstOrNull { it.id == k.provider }
        val label = provider?.history?.firstOrNull { it.window == k.window }?.label ?: ""
        val accent = provider?.let { LocalAccent.current(it.id) } ?: Color.Unspecified
        Box(Modifier.fillMaxSize().padding(horizontal = 22.dp), contentAlignment = Alignment.Center) {
            val morph = if (shared != null) with(shared) { Modifier.sharedBounds(rememberSharedContentState("day-${k.origin}-${k.date}"), this@AnimatedVisibility) } else Modifier
            Box(morph.fillMaxWidth().glass(GlassShapes.card, GlassLevel.Bar, accent)) {
                Column(Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        provider?.let { Logo(it.id, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
                        Text(listOfNotNull(provider?.name, label.takeIf { it.isNotBlank() }).joinToString(" · "), fontSize = 13.sp, color = t.muted, modifier = Modifier.weight(1f))
                        IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) { Icon(Icons.Outlined.Close, "Close", tint = t.muted, modifier = Modifier.size(18.dp)) }
                    }
                    val date = runCatching { LocalDate.parse(k.date) }.getOrNull()
                    Text(date?.let { Days.long(it) } ?: k.date, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.padding(top = 6.dp))
                    Text(insight?.used?.let { LimitText.of(it) } ?: "No readings", fontSize = 48.sp, fontWeight = FontWeight.Light, color = t.text, modifier = Modifier.padding(top = 6.dp))
                    insight?.used?.let { used ->
                        Text(listOfNotNull("of the ${label.lowercase()} limit", LimitText.coverage(insight.coverage)).joinToString(" · "), fontSize = 12.sp, color = t.faint)
                        Spacer(Modifier.height(14.dp))
                        insight.average?.let { avg -> DayFact("Compared with usual", LimitText.compared(used, avg), "than your ${LimitText.of(avg)} average") }
                        insight.rank?.let { DayFact("Rank", "#$it", "of ${insight.recorded} days") }
                        insight.others.take(4).forEach { (name, window, value) -> DayFact("$name · $window", value?.let { LimitText.of(it) } ?: "—", null) }
                    }
                }
            }
        }
    }
}
@Composable private fun DayFact(label: String, value: String, note: String?) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 13.sp, color = t.muted, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, fontSize = 14.sp, color = t.text, fontWeight = FontWeight.SemiBold)
        note?.let { Text("  $it", fontSize = 12.sp, color = t.faint) }
    }
}

/** Renders "your week" as a story image in the current style and opens the share sheet. */
@Composable private fun ShareWeekButton(providers: List<Provider>, tokens: TokenUsage?, report: WeekReport?) {
    val context = androidx.compose.ui.platform.LocalContext.current; val t = LocalTokens.current
    val classic = LocalStyle.current == UiStyle.Classic; val accent = LocalAccent.current
    val scope = rememberCoroutineScope(); var busy by remember { mutableStateOf(false) }
    GlassButton({
        if (!busy) scope.launch {
            busy = true
            try {
                val remaining = Repository(context).remaining(); val now = System.currentTimeMillis()
                val image = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { ShareCard.render(context, providers, tokens, remaining, now, t.dark, classic, report) { accent(it).toArgb() } }
                ShareCard.share(context, image)
            } catch (_: Exception) { } finally { busy = false }
        }
    }, Modifier.fillMaxWidth(), enabled = !busy) {
        Icon(androidx.compose.material.icons.Icons.Outlined.IosShare, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
        Text(if (busy) "Preparing your week…" else "Share your week", fontWeight = FontWeight.SemiBold)
    }
}
