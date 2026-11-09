package io.github.arnavdugad.usagenotch

import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The combined usage score: the PC's (Windows 2.9), or worked out here the same way for older PCs. Each provider counts
 * once, with its weekly limit when it has one and its main limit otherwise.
 */
object UsageScores {
    fun of(snapshot: Snapshot?): UsageScore? = snapshot?.score ?: local(snapshot?.providers.orEmpty())
    fun local(providers: List<Provider>): UsageScore? {
        val rows = providers.filter { it.status == "Ok" || it.status == "Stale" }.mapNotNull { p ->
            val weekly = p.windows.firstOrNull { it.id == p.weekly } ?: if (p.kind in setOf("claude", "codex")) p.weeklyWindow() else null
            val w = weekly ?: p.sessionWindow() ?: return@mapNotNull null
            ScoreRow(p.id, p.name, w.id, w.label, w.used.coerceIn(0.0, 1.0))
        }
        if (rows.isEmpty()) return null
        // Half away from zero, as on Windows (50.5 → 51).
        return UsageScore(kotlin.math.floor(rows.map { it.used }.average() * 100 + .5).toInt().coerceIn(0, 100), null, rows, onPhone = true)
    }
    /** As on Windows: "▲ 8 from 38 last week", "▼ 3 from 41 last week" or "Same as last week (38)". */
    fun trend(score: UsageScore): String = score.previous?.let { p ->
        val d = score.value - p
        when { d > 0 -> "▲ $d from $p last week"; d < 0 -> "▼ ${-d} from $p last week"; else -> "Same as last week ($p)" }
    } ?: "Last week's score appears after a full week of readings."
    /** The word beside the score, as on Windows. */
    fun word(value: Int) = when { value >= 80 -> "Near your limits"; value >= 50 -> "Busy"; value >= 20 -> "Moderate"; else -> "Light" }
    const val EXPLAIN = "The average of how much of each provider's weekly limit you've used (its main limit when it has no weekly one)."
}

/** A number that counts up from zero the first time it's shown (per [key]); the final value at once with Reduce motion. */
@Composable fun countUp(target: Double, key: Any, reduced: Boolean, delayMillis: Int = 0, duration: Int = 900): Double {
    val a = remember(key) { Animatable(if (reduced) 1f else 0f) }
    LaunchedEffect(key, reduced) { if (reduced) a.snapTo(1f) else a.animateTo(1f, tween(duration, delayMillis, FastOutSlowInEasing)) }
    return target * a.value
}

/**
 * The usage score at the top of Overview: one ring for all your providers that fills as the number counts up, how it
 * moved since last week, and each provider's part. Tap for what it means.
 */
@Composable fun ScoreCard(score: UsageScore, reduced: Boolean, onExplain: () -> Unit) {
    val t = LocalTokens.current; val accentOf = LocalAccent.current
    val shown = countUp(score.value.toDouble(), "score-${score.value}-${score.previous}", reduced)
    val ink = Color(Palette.usage(score.value / 100.0))
    Panel(Modifier.fillMaxWidth().clickable(onClickLabel = "What the usage score means") { onExplain() }) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(92.dp).semantics { contentDescription = "Usage score ${score.value} out of 100" }, contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 9.dp.toPx(); val inset = stroke / 2
                    drawArc(t.track, 0f, 360f, false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke))
                    drawArc(ink, -90f, (360 * shown / 100).toFloat(), false, Offset(inset, inset), Size(size.width - stroke, size.height - stroke), style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Text("${shown.roundToInt()}", fontSize = 30.sp, fontWeight = FontWeight.Light, color = t.text)
            }
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Usage score", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                    Spacer(Modifier.width(8.dp))
                    Text(UsageScores.word(score.value), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(Palette.readable(Palette.usage(score.value / 100.0), if (t.dark) 0xFF151922.toInt() else 0xFFFFFFFF.toInt())),
                        maxLines = 1, modifier = Modifier.clip(com.kyant.shapes.RoundedRectangle(7.dp)).background(ink.copy(alpha = .16f)).padding(horizontal = 8.dp, vertical = 2.dp))
                }
                Text(UsageScores.trend(score), fontSize = 13.sp, fontWeight = if (score.previous != null) FontWeight.SemiBold else FontWeight.Normal, color = t.muted, modifier = Modifier.padding(top = 3.dp))
                Text(UsageScores.EXPLAIN, fontSize = 12.sp, lineHeight = 16.sp, color = t.faint, modifier = Modifier.padding(top = 4.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        score.providers.forEachIndexed { i, row ->
            val fill = countUp(row.used, "score-row-${row.id}-${row.used}", reduced, 120 + i * 120, 700)
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically) {
                Logo(row.id, Modifier.size(16.dp)); Spacer(Modifier.width(8.dp))
                Text(row.name, fontSize = 13.sp, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(96.dp))
                Box(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(t.track)) {
                    Box(Modifier.fillMaxWidth(fill.toFloat().coerceIn(0f, 1f)).fillMaxHeight().clip(CircleShape).background(accentOf(row.id)))
                }
                Text("${(row.used * 100).roundToInt()}%", fontSize = 12.5.sp, color = t.muted, modifier = Modifier.padding(start = 10.dp).width(40.dp), maxLines = 1)
            }
        }
    } }
}

/** What the score means, in a few plain sentences, with each provider's part. */
@Composable fun ScoreSheet(score: UsageScore?, onClose: () -> Unit) {
    if (score == null) return
    AlertDialog(onDismissRequest = onClose, icon = { Icon(Icons.Outlined.DonutLarge, null) }, title = { Text("Usage score ${score.value}") },
        text = { Column {
            Text("One number for all your providers. ${UsageScores.EXPLAIN} 0 means nothing used, 100 means every limit is full.", fontSize = 14.sp, lineHeight = 20.sp)
            score.previous?.let { Text("Last week it finished at $it, measured the same way when each weekly limit renewed.", fontSize = 14.sp, lineHeight = 20.sp, modifier = Modifier.padding(top = 10.dp)) }
            Spacer(Modifier.height(10.dp))
            score.providers.forEach { r -> Text("${r.name} · ${r.label}: ${(r.used * 100).roundToInt()}%", fontSize = 13.sp, modifier = Modifier.padding(vertical = 2.dp)) }
            if (score.onPhone) Text("Worked out on this phone. UsageNotch for Windows 2.9 also compares it with last week.", fontSize = 12.sp, lineHeight = 17.sp, modifier = Modifier.padding(top = 10.dp))
        } },
        confirmButton = { TextButton(onClick = onClose) { Text("Done") } })
}

/**
 * A provider's logo inside a ring of this week against its typical week: a full ring is a typical week, and a second,
 * brighter lap shows how far past it. The rings fill one after another, [index] apart.
 */
@Composable fun TypicalRing(row: ReportRow, accent: Color, reduced: Boolean, index: Int, key: Any, size: Dp = 46.dp) {
    val t = LocalTokens.current
    // This week ÷ the typical week, or ÷ the week before when there's no typical week yet, as on Windows.
    val fraction = (row.typical ?: row.previous)?.takeIf { it > .0001 }?.let { (row.used ?: 0.0) / it }
    val p = countUp(1.0, "ring-$key-${row.id}", reduced, 180 + index * 160, 950).toFloat()
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        if (fraction != null) Canvas(Modifier.fillMaxSize()) {
            val stroke = 3.5.dp.toPx(); val inset = stroke / 2; val box = Size(this.size.width - stroke, this.size.height - stroke)
            drawArc(t.track, 0f, 360f, false, Offset(inset, inset), box, style = Stroke(stroke))
            val first = (fraction.coerceIn(0.0, 1.0) * p).toFloat(); val second = ((fraction - 1).coerceIn(0.0, 1.0) * p).toFloat()
            if (first > 0f) drawArc(accent, -90f, 360f * first, false, Offset(inset, inset), box, style = Stroke(stroke, cap = StrokeCap.Round))
            if (second > 0f) drawArc(lerp(accent, Color.White, .45f), -90f, 360f * second, false, Offset(inset, inset), box, style = Stroke(stroke * .7f, cap = StrokeCap.Round))
        }
        Box(Modifier.size(size - 12.dp).clip(com.kyant.shapes.RoundedRectangle(10.dp)).background(innerSurface()), contentAlignment = Alignment.Center) { Logo(row.id, Modifier.size(18.dp)) }
    }
}

/** "since Sep 26, 2026", "from Oct 3, 2026". */
private fun planDate(at: Long) = Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()).toLocalDate().format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH))

/** Your plans over time, as the PC first saw each one; the newest is the current plan. */
@Composable fun PlanHistoryCard(p: Provider) {
    if (p.plans.isEmpty()) return
    val t = LocalTokens.current; val accent = LocalAccent.current(p.id)
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.WorkspacePremium, null, tint = t.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text("Plan history", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
            Text(if (p.plans.size == 1) "no changes seen" else "${p.plans.size - 1} change${if (p.plans.size == 2) "" else "s"}", fontSize = 12.sp, color = t.faint)
        }
        Spacer(Modifier.height(8.dp))
        val list = p.plans.reversed()
        list.forEachIndexed { i, c ->
            Row(Modifier.height(IntrinsicSize.Min).semantics(mergeDescendants = true) {}) {
                Box(Modifier.width(18.dp).fillMaxHeight()) {
                    if (i < list.lastIndex) Box(Modifier.align(Alignment.TopCenter).padding(top = 14.dp).width(2.dp).fillMaxHeight().background(t.hairline))
                    Box(Modifier.align(Alignment.TopCenter).padding(top = 6.dp).size(10.dp).clip(CircleShape).background(if (i == 0) accent else t.track))
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.padding(bottom = 12.dp)) {
                    Text(c.plan + if (i == 0) " · now" else "", fontSize = 14.sp, fontWeight = if (i == 0) FontWeight.SemiBold else FontWeight.Normal, color = t.text)
                    Text(if (i == list.lastIndex) "since ${planDate(c.at)} (when UsageNotch first saw it)" else "from ${planDate(c.at)}", fontSize = 12.sp, color = t.faint)
                }
            }
        }
    } }
}

/**
 * This month's tokens at API prices against the plan's price ("$412 at API prices · your Max 5× plan is $100 a month ·
 * 4.1× its price"), and how many tokens each full limit gave you this week.
 */
@Composable fun TokenValueTiles(source: TokenSource) {
    val t = LocalTokens.current
    val month = source.month; val per = source.perLimit
    if (month == null && per == null) return
    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        month?.let { m ->
            Panel(Modifier.weight(1f), 18.dp, innerSurface()) { Column(Modifier.padding(14.dp).semantics(mergeDescendants = true) {}) {
                Text("This month", fontSize = 12.sp, color = t.muted)
                Text(m.cost?.let { dollars(it) } ?: tokens(m.total), fontSize = 24.sp, fontWeight = FontWeight.Light, color = t.text, maxLines = 1)
                Text(monthValue(m, source.plan), fontSize = 11.sp, lineHeight = 14.sp, color = t.faint, maxLines = 4)
            } }
        }
        per?.let { pl ->
            Panel(Modifier.weight(1f), 18.dp, innerSurface()) { Column(Modifier.padding(14.dp).semantics(mergeDescendants = true) {}) {
                Text("Per ${pl.label} limit", fontSize = 12.sp, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(tokens(pl.tokens), fontSize = 24.sp, fontWeight = FontWeight.Light, color = t.text, maxLines = 1)
                Text("tokens for each full ${pl.label} limit used this week", fontSize = 11.sp, lineHeight = 14.sp, color = t.faint, maxLines = 3)
            } }
        }
    }
}

/** "4.1× its price" or "38% of its price", for the value tile. */
internal fun valueRatio(cost: Double, price: Double): String {
    val r = cost / price
    return if (r >= 1.05) "%.1f".format(Locale.ENGLISH, r).removeSuffix(".0") + "× its price" else "${(r * 100).roundToInt()}% of its price"
}
/** As on Windows: "at API prices in November · your Max 5× plan is $100 a month (4.1× its price)". */
internal fun monthValue(m: TokenMonth, plan: PlanPrice?): String {
    val month = runCatching { LocalDate.parse(m.from).month.getDisplayName(java.time.format.TextStyle.FULL, Locale.ENGLISH) }.getOrDefault("this month")
    return when {
        m.cost != null && plan != null -> "at API prices in $month · your ${plan.name} plan is $${"%,.0f".format(Locale.ENGLISH, plan.price)} a month (${valueRatio(m.cost, plan.price)})"
        m.cost != null -> "at API prices in $month · ${tokens(m.total)} tokens"
        else -> "tokens in $month"
    }
}

/** The colours of the model mix, from the accent choices so they stay distinct in light and dark. */
private fun mixColor(i: Int, accent: Color): Color = if (i == 0) accent else Color(DesignTokens.accentChoices[(i * 3 + 1) % DesignTokens.accentChoices.size])

/** Eight weeks of tokens by model as stacked bars, newest on the right, with a legend. */
@Composable fun ModelMixChart(source: TokenSource, accent: Color, reduced: Boolean) {
    val weeks = source.modelWeeks.takeLast(8); if (weeks.size < 2 || weeks.all { w -> w.models.sumOf { it.second } == 0L }) return
    val t = LocalTokens.current
    val names = weeks.flatMap { it.models }.groupBy { it.first }.mapValues { (_, v) -> v.sumOf { it.second } }.entries.sortedByDescending { it.value }.map { it.key }
    val max = weeks.maxOf { w -> w.models.sumOf { it.second } }.coerceAtLeast(1)
    val grow = rise("mix-${source.id}-${weeks.lastOrNull()?.week}", reduced, 800)
    Text("Model mix · 8 weeks", fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))
    Canvas(Modifier.fillMaxWidth().height(80.dp).semantics { contentDescription = "Tokens by model for ${weeks.size} weeks: " + names.take(4).joinToString(", ") }) {
        val gap = 6.dp.toPx(); val bw = (size.width - gap * (weeks.size - 1)) / weeks.size
        weeks.forEachIndexed { i, w ->
            var top = size.height
            names.forEachIndexed { n, name ->
                val v = w.models.firstOrNull { it.first == name }?.second ?: 0L
                if (v <= 0) return@forEachIndexed
                val h = size.height * v / max * grow
                top -= h
                drawRoundRect(mixColor(n, accent), Offset(i * (bw + gap), top), Size(bw, h), CornerRadius(2.dp.toPx()))
            }
        }
    }
    val fmt = DateTimeFormatter.ofPattern("MMM d", Locale.ENGLISH)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(runCatching { LocalDate.parse(weeks.first().week).format(fmt) }.getOrDefault(""), fontSize = 10.5.sp, color = t.faint, modifier = Modifier.weight(1f))
        Text("this week", fontSize = 10.5.sp, color = t.faint)
    }
    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        names.take(6).forEachIndexed { n, name ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(mixColor(n, accent))); Spacer(Modifier.width(5.dp))
                Text(name, fontSize = 11.5.sp, color = t.muted, maxLines = 1)
            }
        }
    }
}

/** Presets for a project's weekly token budget. */
val PROJECT_BUDGETS = listOf(10_000_000L, 25_000_000L, 50_000_000L, 100_000_000L, 250_000_000L, 500_000_000L, 1_000_000_000L)
/** A budget typed in millions of tokens, as on Windows (0.1–1,000,000); null when it isn't a number in that range. */
fun parseMillions(text: String): Long? = text.trim().replace(",", "").toDoubleOrNull()?.takeIf { it.isFinite() && it >= .1 && it <= 1_000_000 }?.let { kotlin.math.round(it * 1_000_000).toLong() }
/** 50000000 → "50", 2500000 → "2.5". */
fun millions(tokens: Long): String = "%.1f".format(Locale.ENGLISH, tokens / 1e6).removeSuffix(".0")

/** Choose a weekly token budget for one project; your PC alerts at 80% and 100%. */
@OptIn(ExperimentalLayoutApi::class)
@Composable fun ProjectBudgetDialog(project: String, current: Long?, used: Long, onDone: (Long?) -> Unit, onClose: () -> Unit) {
    var text by remember(project) { mutableStateOf(current?.let { millions(it) } ?: "") }
    val parsed = parseMillions(text); val valid = text.isBlank() || parsed != null
    AlertDialog(onDismissRequest = onClose, icon = { Icon(Icons.Outlined.Savings, null) }, title = { Text("Weekly budget") },
        text = { Column {
            Text(project, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${tokens(used)} tokens in the last 7 days. Your PC alerts you at 80% and 100% of the budget. It syncs with your PC.", fontSize = 13.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 4.dp, bottom = 10.dp))
            OutlinedTextField(text, { text = it.take(12) }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Budget") }, suffix = { Text("M tokens / week") },
                isError = !valid, placeholder = { Text("No budget") },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Decimal),
                supportingText = { Text(if (valid) "Leave empty for no budget" else "Enter 0.1 to 1,000,000 million tokens", fontSize = 11.sp) })
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                PROJECT_BUDGETS.forEach { b -> GlassChip(tokens(b), parsed == b) { text = millions(b) } }
            }
        } },
        confirmButton = { TextButton(onClick = { onDone(if (text.isBlank()) null else parsed) }, enabled = valid) { Text("Save") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Cancel") } })
}

/**
 * Bar geometry for the morph between the 7, 30 and 90 day charts: dates in both ranges slide and resize, new dates grow
 * in place, and dates that leave shrink and fade. All in pixels, with heights as fractions of the plot.
 */
object ChartMorph {
    data class Bar(val date: String, val left: Float, val width: Float, val height: Float, val alpha: Float)
    fun layout(days: List<Day>, max: Double, width: Float, gap: Float): List<Bar> {
        val n = days.size; if (n == 0 || width <= 0f) return emptyList()
        val slot = ((width - gap * (n - 1)) / n).coerceAtLeast(0f)
        return days.mapIndexed { i, d -> Bar(d.date, i * (slot + gap), slot, ((d.used ?: 0.0) / max).toFloat().coerceIn(0f, 1f), if (d.used == null) 0f else 1f) }
    }
    fun frame(from: List<Bar>, to: List<Bar>, t: Float): List<Bar> {
        val a = from.associateBy { it.date }; val b = to.associateBy { it.date }
        fun mix(x: Float, y: Float) = x + (y - x) * t
        val p = t.coerceIn(0f, 1f)
        return (from.map { it.date } + to.map { it.date }).distinct().mapNotNull { date ->
            val x = a[date]; val y = b[date]
            when {
                x != null && y != null -> Bar(date, mix(x.left, y.left), mix(x.width, y.width).coerceAtLeast(0f), mix(x.height, y.height).coerceAtLeast(0f), mix(x.alpha, y.alpha).coerceIn(0f, 1f))
                y != null -> Bar(date, y.left, y.width, (y.height * t).coerceAtLeast(0f), y.alpha * p)
                x != null -> Bar(date, x.left, x.width, (x.height * (1 - t)).coerceAtLeast(0f), x.alpha * (1 - p))
                else -> null
            }
        }
    }
}
