package io.github.arnavdugad.usagenotch

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.TrendingUp
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.*
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** "▲ 35%", "▼ 12%" or "steady" (within 10%, as the PC words it); null without a fair comparison. */
internal fun changeText(change: Double?): String? = change?.let { c ->
    when {
        c >= 3 -> "▲ " + "%.1f".format(Locale.ENGLISH, c + 1).removeSuffix(".0") + "×"
        abs(c) < .1 -> "steady"
        c > 0 -> "▲ ${(c * 100).roundToInt()}%"
        else -> "▼ ${(-c * 100).roundToInt()}%"
    }
}

/** Fades and lifts each part of a card in turn, like the rest of History; instant with Reduce motion. */
private fun Modifier.stagger(progress: Float, index: Int, count: Int): Modifier = graphicsLayer {
    val share = 1f / (count + 2)
    val p = ((progress - index * share * .8f) / (share * 2.2f)).coerceIn(0f, 1f)
    alpha = p; translationY = (1 - p) * 14.dp.toPx()
}

/**
 * "This week", written by your PC: a headline, each provider's main limit against the week before, and a few plain
 * sentences. Every number matches History, because both come from the same daily totals.
 */
@Composable fun ThisWeekCard(report: WeekReport, providers: List<Provider>, reduced: Boolean, notes: Map<String, String> = emptyMap()) {
    val t = LocalTokens.current; val accentOf = LocalAccent.current
    val key = "week-${report.from}-${report.generatedAt}"
    val grow = rise(key, reduced, 900)
    val parts = report.providers.size + report.insights.size + 1
    // Notes saved on this phone (the PC's included once synced) win over the copy in the report.
    val weekNotes = (report.notes.associate { it.date to it.text } + notes.filterKeys { it >= report.from && it <= report.to })
        .filterValues { it.isNotBlank() }.toSortedMap()
    Panel(Modifier.fillMaxWidth(), tint = t.accent.copy(alpha = .22f)) { Column(Modifier.padding(18.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = t.accent, modifier = Modifier.size(17.dp)); Spacer(Modifier.width(8.dp))
            Text("This week", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.muted, modifier = Modifier.weight(1f))
            Text(report.summary, fontSize = 12.sp, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(report.headline, fontSize = 23.sp, lineHeight = 28.sp, fontWeight = FontWeight.Normal, color = t.text, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp).stagger(grow, 0, parts))
        // How sure the PC is: shown when it saw less than half of the week.
        report.note?.let { note ->
            Row(Modifier.padding(bottom = 6.dp).stagger(grow, 0, parts), verticalAlignment = Alignment.Top) {
                Icon(Icons.Outlined.Info, null, tint = t.faint, modifier = Modifier.padding(top = 1.dp).size(14.dp)); Spacer(Modifier.width(6.dp))
                Text(note, fontSize = 12.sp, lineHeight = 16.sp, color = t.muted)
            }
        }
        report.providers.forEachIndexed { i, row ->
            ReportProviderRow(row, accentOf(row.id), reduced, i, key, Modifier.stagger(grow, i + 1, parts))
        }
        if (report.providers.any { it.typical != null || it.previous != null })
            Text("Rings: this week against your typical week (or the week before). A second lap means more than usual.", fontSize = 11.sp, lineHeight = 15.sp, color = t.faint, modifier = Modifier.padding(top = 2.dp))
        if (report.insights.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(top = 10.dp, bottom = 6.dp), color = t.hairline)
            report.insights.forEachIndexed { i, line ->
                Row(Modifier.padding(vertical = 5.dp).stagger(grow, report.providers.size + 1 + i, parts), verticalAlignment = Alignment.Top) {
                    Box(Modifier.padding(top = 6.dp).size(7.dp).clip(CircleShape).background(line.provider?.let { accentOf(it) } ?: t.accent))
                    Spacer(Modifier.width(10.dp))
                    Text(line.text, fontSize = 13.5.sp, lineHeight = 19.sp, color = t.text)
                }
            }
        }
        if (weekNotes.isNotEmpty()) {
            HorizontalDivider(Modifier.padding(top = 8.dp, bottom = 6.dp), color = t.hairline)
            Row(verticalAlignment = Alignment.Top, modifier = Modifier.semantics(mergeDescendants = true) {}) {
                Icon(Icons.Outlined.EditNote, null, tint = t.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
                Column {
                    Text("Your notes", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = t.muted)
                    weekNotes.forEach { (date, text) ->
                        val day = runCatching { LocalDate.parse(date).dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, Locale.ENGLISH) }.getOrDefault(date)
                        Text(buildAnnotatedString { withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append("$day  ") }; append(text) }, fontSize = 13.sp, lineHeight = 18.sp, color = t.text, modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    } }
}

@Composable private fun ReportProviderRow(row: ReportRow, accent: Color, reduced: Boolean, index: Int, key: Any, modifier: Modifier) {
    val t = LocalTokens.current
    val change = changeText(row.change)
    // Totals count up as the card opens, one provider after another.
    val shownUsed = row.used?.let { countUp(it, "$key-${row.id}-used", reduced, 200 + index * 160) }
    Row(modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {
        contentDescription = "${row.name}: ${LimitText.of(row.used)} this week" + (row.previous?.let { ", ${LimitText.of(it)} the week before" } ?: "") + (change?.let { ", $it" } ?: "") +
            (if (row.hits > 0) ", limit reached ${row.hits} times" else "") + (row.typical?.let { ", typical week ${LimitText.of(it)}" } ?: "")
    }, verticalAlignment = Alignment.CenterVertically) {
        TypicalRing(row, accent, reduced, index, key)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(row.name, fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (row.label.isNotBlank()) Text(row.label, fontSize = 12.sp, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val warn = Color(Palette.readable(Palette.WARNING, if (t.dark) 0xFF151922.toInt() else 0xFFFFFFFF.toInt()))
            Text(buildAnnotatedString {
                append("${row.activeDays} active day${if (row.activeDays == 1) "" else "s"}")
                if (row.hits > 0) { append(" · "); withStyle(SpanStyle(color = warn)) { append("limit reached ${if (row.hits == 1) "once" else if (row.hits == 2) "twice" else "${row.hits} times"}") } }
            }, fontSize = 12.sp, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.typical?.let { typical ->
                Text("Typical week: " + LimitText.of(typical) + (row.typicalChange?.let { c -> " · " + when { abs(c) < .1 -> "about usual"; c > 0 -> "${(c * 100).roundToInt()}% above"; else -> "${(-c * 100).roundToInt()}% below" } } ?: ""),
                    fontSize = 12.sp, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Column(Modifier.padding(start = 10.dp), horizontalAlignment = Alignment.End) {
            Text(LimitText.of(shownUsed), fontSize = 20.sp, fontWeight = FontWeight.Light, color = t.text)
            val tone = if (change != null && change != "steady") Color(Palette.readable(accent.toArgb(), if (t.dark) 0xFF151922.toInt() else 0xFFFFFFFF.toInt())) else t.muted
            // Stacked, so the left column keeps room for the limit name, active days and limits reached.
            when {
                change != null -> { Text(change, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = tone); Text("vs last week", fontSize = 11.sp, color = t.faint) }
                row.previous != null -> { Text(LimitText.of(row.previous), fontSize = 12.sp, color = t.muted); Text("last week", fontSize = 11.sp, color = t.faint) }
                else -> Text("this week", fontSize = 11.sp, color = t.faint)
            }
        }
    }
}

/**
 * Everything recorded for this limit: since when, the total in limits, days with usage, limits reached and the best day.
 * From the PC when it sends them, otherwise from the days saved on this phone.
 */
@Composable fun AllTimeCard(provider: Provider, history: History, all: AllTime, reduced: Boolean) {
    val t = LocalTokens.current; val accent = LocalAccent.current(provider.id)
    val grow = rise("all-${provider.id}-${history.window}", reduced, 700)
    val since = Instant.ofEpochMilli(all.since).atZone(ZoneId.systemDefault()).toLocalDate()
    val tiles = listOfNotNull(
        Triple("Total", LimitText.of(all.used), if (all.used >= LimitText.MULTIPLE) "%.1f".format(Locale.ENGLISH, all.used).removeSuffix(".0") + " full limits" else "of one limit"),
        Triple("Active days", "${all.activeDays}", "of ${all.recordedDays} recorded day${if (all.recordedDays == 1) "" else "s"}"),
        all.hits?.let { Triple("Limits reached", "$it", if (it == 1) "time" else "times") },
        Triple("Best day", all.bestDay?.let { d -> runCatching { Days.short(LocalDate.parse(d)) }.getOrDefault(d) } ?: "—", if (all.bestDay != null) LimitText.ofLimit(all.bestUsed) else "no usage yet"),
    )
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.AllInclusive, null, tint = accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp))
            Text("All time", fontSize = 14.5.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.weight(1f))
            Text("since ${since.format(DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.ENGLISH))}" + if (all.onPhone) " · on this phone" else "", fontSize = 12.sp, color = t.faint)
        }
        Text(history.label, fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(top = 2.dp, bottom = 10.dp))
        tiles.chunked(2).forEachIndexed { r, pair ->
            Row(Modifier.padding(top = if (r == 0) 0.dp else 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEachIndexed { c, (label, value, note) ->
                    Panel(Modifier.weight(1f).stagger(grow, r * 2 + c, tiles.size), 18.dp, innerSurface()) { Column(Modifier.padding(14.dp).semantics(mergeDescendants = true) {}) {
                        Text(label, fontSize = 12.sp, color = t.muted)
                        Text(value, fontSize = 24.sp, fontWeight = FontWeight.Light, color = t.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(note, fontSize = 11.sp, color = t.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } }
                }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
        if (all.onPhone) Text("Saved on this phone from your PC's daily totals. Limits reached appear when your PC runs UsageNotch 2.8 or later.", fontSize = 11.5.sp, lineHeight = 16.sp, color = t.faint, modifier = Modifier.padding(top = 10.dp))
    } }
}

/** "What's new" after an update: shown once per version that has notes, never on a fresh install. */
object WhatsNew {
    /** The versionCode whose notes [items] describes. */
    const val VERSION = 12
    const val TITLE = "What's new in 1.9"
    data class Item(val icon: ImageVector, val title: String, val text: String)
    val items = listOf(
        Item(Icons.Outlined.DonutLarge, "Usage score", "One number for all your providers on Overview, and how it moved since last week."),
        Item(Icons.Outlined.EditNote, "Notes on days", "Tap a day in History to write a note like \"Big refactor\". Notes sync with your PC and show in This week."),
        Item(Icons.Outlined.AutoAwesome, "Your typical week", "This week now compares with your usual week too, points out unusual days, and says when your PC saw too little to be sure."),
        Item(Icons.Outlined.Payments, "What your tokens are worth", "This month at API prices against your plan's price, tokens per limit, and your model mix over 8 weeks."),
        Item(Icons.Outlined.Savings, "Project budgets", "Give a project a weekly token budget; your PC alerts you at 80% and 100%."),
        Item(Icons.Outlined.WorkspacePremium, "Plan history", "See when your plan changed, such as Pro to Max, in each provider's view."),
        Item(Icons.Outlined.Animation, "Smoother motion", "Charts morph between 7, 30 and 90 days, and tabs glide like the Windows tab bar."),
    )
    private const val KEY = "whatsNewSeen"
    /** Show after an update to a version with notes; a fresh install starts with them marked as read. */
    fun shouldShow(seen: Int, current: Int, freshInstall: Boolean) = !freshInstall && seen < VERSION && current >= VERSION
    fun pending(context: Context): Boolean {
        val prefs = context.getSharedPreferences("display", Context.MODE_PRIVATE)
        val fresh = runCatching {
            val info = if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
                else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0)
            info.firstInstallTime == info.lastUpdateTime
        }.getOrDefault(false)
        val show = shouldShow(prefs.getInt(KEY, 0), BuildConfig.VERSION_CODE, fresh)
        if (!show && prefs.getInt(KEY, 0) < VERSION) markSeen(context)
        return show
    }
    fun markSeen(context: Context) { context.getSharedPreferences("display", Context.MODE_PRIVATE).edit().putInt(KEY, VERSION).apply() }
}

/**
 * The What's new card over a dimmed backdrop. Its surface is solid so the notes stay readable over any screen (Android 9
 * to 11 have no blur), and Continue stays pinned below the notes, which scroll on small screens.
 */
@Composable fun WhatsNewSheet(visible: Boolean, reduced: Boolean, onClose: () -> Unit) {
    val t = LocalTokens.current
    AnimatedVisibility(visible, enter = fadeIn(tween(220)), exit = fadeOut(tween(200))) {
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = if (t.dark) .5f else .28f)).clickable(remember { MutableInteractionSource() }, null, onClickLabel = "Close") { onClose() })
    }
    AnimatedVisibility(visible, enter = if (reduced) fadeIn(snap()) else fadeIn(tween(220)) + scaleIn(spring(dampingRatio = .78f, stiffness = 380f), initialScale = .9f),
        exit = fadeOut(tween(160)) + scaleOut(targetScale = .95f)) {
        Box(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 24.dp), contentAlignment = Alignment.Center) {
            val surface = if (t.dark) Color(0xFF181C24) else Color(0xFFFBFCFD)
            Box(Modifier.fillMaxWidth().widthIn(max = 520.dp).clip(GlassShapes.card).background(surface).background(t.accent.copy(alpha = .05f))) {
              Column {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 22.dp)) {
                    Text("UsageNotch ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = t.muted)
                    Text(WhatsNew.TITLE, fontSize = 26.sp, fontWeight = FontWeight.SemiBold, color = t.text, modifier = Modifier.padding(top = 2.dp, bottom = 12.dp).semantics { heading() })
                    WhatsNew.items.forEach { item ->
                        Row(Modifier.padding(vertical = 8.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(38.dp).clip(com.kyant.shapes.RoundedRectangle(12.dp)).background(t.accent.copy(alpha = .16f)), contentAlignment = Alignment.Center) {
                                Icon(item.icon, null, tint = t.accent, modifier = Modifier.size(20.dp))
                            }
                            Spacer(Modifier.width(14.dp))
                            Column(Modifier.weight(1f)) {
                                Text(item.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                                Text(item.text, fontSize = 13.sp, lineHeight = 18.sp, color = t.muted, modifier = Modifier.padding(top = 2.dp))
                            }
                        }
                    }
                    Text("The score's weekly comparison, typical weeks, unusual days, plan history and token values come from UsageNotch for Windows 2.9 or later.", fontSize = 12.sp, lineHeight = 16.sp, color = t.faint, modifier = Modifier.padding(top = 8.dp))
                }
                GlassButton(onClose, Modifier.fillMaxWidth().padding(22.dp), prominent = true) { Text("Continue", fontWeight = FontWeight.SemiBold) }
              }
            }
        }
    }
}
