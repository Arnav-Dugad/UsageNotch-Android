package io.github.arnavdugad.usagenotch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Bundle
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** Everything that should follow a new reading or a reset, from the app, the background job or an alarm. */
fun afterNewData(context: Context) {
    runCatching { updateWidgets(context) }
    runCatching { ResetAlerts.schedule(context) }
    runCatching { LiveUpdate.update(context) }
    runCatching { UsageAlerts.evaluate(context) }
    runCatching { UsageTileService.refresh(context) }
    runCatching { WeeklyRecap.schedule(context) }
}

private fun openApp(context: Context, request: Int) = PendingIntent.getActivity(context, request,
    Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)

/** The provider the countdown notification and Quick Settings tile follow: the chosen one, else the first. */
internal fun followedProvider(context: Context): Provider? {
    val repo = Repository(context)
    return focusProvider(repo.snapshot()?.providers.orEmpty(), repo.prefs.getString("liveProvider", null))
}

/**
 * An ongoing notification whose clock counts down to the next reset of the followed provider's session window.
 * On Android 16 it asks to be shown as a Live Update (status-bar chip with the percentage). Readings come from the PC;
 * the countdown itself runs on the phone, so it stays right while offline.
 */
object LiveUpdate {
    private const val CHANNEL = "live"; private const val ID = 42
    fun enabled(context: Context) = Repository(context).prefs.getBoolean("liveUpdate", false)
    fun setEnabled(context: Context, on: Boolean) { Repository(context).prefs.edit().putBoolean("liveUpdate", on).apply(); update(context) }
    fun update(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val repo = Repository(context); val now = System.currentTimeMillis()
        val provider = followedProvider(context); val window = provider?.sessionWindow()
        if (!enabled(context) || !ResetAlerts.canNotify(context) || provider == null || window == null) { manager.cancel(ID); return }
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Live countdown", NotificationManager.IMPORTANCE_LOW).apply {
            description = "An ongoing countdown to your next limit reset."; setShowBadge(false)
        })
        val remaining = repo.remaining(); val renewed = window.resetPassed(now)
        val percent = window.percent(remaining)
        val value = if (renewed) "renewed" else "$percent% ${if (remaining) "left" else "used"}"
        val builder = Notification.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_notch)
            .setContentTitle("${provider.name} · $value")
            .setContentText(if (renewed) "${window.label} renewed. New usage appears after your PC's next reading." else "${window.label} · ${ClockText.resetsAt(window.reset, now, repo.use24()).replaceFirstChar { it.lowercase() }}")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            // Opted in by the person, and only a percentage and a countdown: readable on the lock screen.
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setContentIntent(openApp(context, 42))
        window.reset?.takeIf { it > now }?.let { builder.setWhen(it).setShowWhen(true).setUsesChronometer(true).setChronometerCountDown(true) }
        val shown = if (renewed) 0 else (window.shown(remaining) * 100).toInt()
        if (Build.VERSION.SDK_INT >= 36) {
            builder.setStyle(Notification.ProgressStyle().setStyledByProgress(false).setProgress(shown)
                .setProgressSegments(listOf(Notification.ProgressStyle.Segment(100).setColor(Palette.usage(window.used))))
                .setProgressTrackerIcon(Icon.createWithResource(context, Logos.resource(provider.id))))
            builder.setShortCriticalText(if (renewed) "New" else "$percent%")
            // Android 16 promotes it to a Live Update when the person allows it; otherwise it stays an ordinary ongoing notification.
            builder.addExtras(Bundle().apply { putBoolean("android.requestPromotedOngoing", true) })
        } else builder.setProgress(100, shown, false)
        manager.notify(ID, builder.build())
    }
}

/**
 * Usage alerts: when a window passes 80% or 95% used, and when the PC's pace forecast says a limit runs out within
 * two hours, before its reset. Each alert fires once per reset period; readings seen while alerts were off are
 * remembered too, so turning them on never replays old alerts.
 */
object UsageAlerts {
    private const val CHANNEL = "usage"
    val thresholds = listOf(95, 80)
    fun enabled(context: Context) = Repository(context).prefs.getBoolean("usageAlerts", false)
    fun setEnabled(context: Context, on: Boolean) { Repository(context).prefs.edit().putBoolean("usageAlerts", on).apply() }
    data class Alert(val key: String, val title: String, val body: String)
    /** Pure decision logic, tested without Android: which alerts are due that haven't been sent. */
    fun due(snapshot: Snapshot?, sent: Set<String>, now: Long, use24: Boolean, budgets: List<Budget> = emptyList(), zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Alert> {
        val out = mutableListOf<Alert>()
        val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        for (p in snapshot?.providers.orEmpty()) for (w in p.windows) {
            // Budgets: once per period and day when the pace would pass it, and once when it is passed.
            budgets.firstOrNull { it.provider == p.id && it.window == w.id }?.takeIf { !w.resetPassed(now) }?.let { b ->
                val status = Budgets.status(b, w, now, use24, zone)
                val base = "${p.id}|${w.id}|${w.reset ?: 0}|budget-${b.limit}-${b.byMinute ?: "reset"}"
                when (status.state) {
                    Budgets.State.Over -> "$base|over".takeIf { it !in sent }?.let { out += Alert(it, "${p.name}: over your ${b.limit}% budget", "${w.label} is ${(w.used * 100).toInt()}% used.") }
                    Budgets.State.Pace -> "$base|pace|$today".takeIf { it !in sent && "$base|over" !in sent }?.let { out += Alert(it, "${p.name}: ${status.text.replaceFirstChar { c -> c.lowercase() }}", "Your budget for ${w.label} is ${b.label(use24)}.") }
                    else -> Unit
                }
            }
            if (w.resetPassed(now)) continue
            val period = "${p.id}|${w.id}|${w.reset ?: 0}"
            val used = (w.used * 100).toInt()
            // The highest level reached, once per period (sending 95% also marks 80% as sent).
            thresholds.firstOrNull { used >= it }?.takeIf { "$period|$it" !in sent }?.let { t ->
                out += Alert("$period|$t", "${p.name}: ${w.label} is $used% used", ClockText.resetsAt(w.reset, now, use24) + ".")
            }
            val limitAt = w.forecast?.limitAt
            if (limitAt != null && limitAt > now && limitAt - now <= 2 * 3_600_000L && used < 95 && "$period|pace" !in sent) {
                val minutes = (limitAt - now) / 60_000
                val left = if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
                out += Alert("$period|pace", "${p.name} runs out in about $left at this pace",
                    "${w.label} would reach its limit around ${ClockText.time(limitAt, use24)}, before it resets at ${w.reset?.let { ClockText.time(it, use24) } ?: "an unreported time"}.")
            }
        }
        return out
    }
    fun evaluate(context: Context) {
        val repo = Repository(context); val prefs = repo.prefs; val now = System.currentTimeMillis()
        val sent = prefs.getStringSet("alertsSent", emptySet()).orEmpty()
        val alerts = due(repo.snapshot(), sent, now, repo.use24(), Budgets.all(prefs))
        if (alerts.isEmpty()) return
        // Passing a higher level marks the lower ones sent too.
        val keys = alerts.flatMap { a -> if (a.key.endsWith("|95")) listOf(a.key, a.key.removeSuffix("|95") + "|80") else listOf(a.key) }
        prefs.edit().putStringSet("alertsSent", (sent + keys).toList().takeLast(300).toSet()).apply()
        if (!enabled(context) || !ResetAlerts.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Usage alerts", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "When a limit is nearly used up or running out quickly."; enableVibration(true); vibrationPattern = longArrayOf(0, 40, 90, 40)
        })
        alerts.forEach { a ->
            manager.notify(1000 + (a.key.hashCode() and 0xFFFF), Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_notch)
                .setContentTitle(a.title).setContentText(a.body).setStyle(Notification.BigTextStyle().bigText(a.body))
                .setContentIntent(openApp(context, 43)).setAutoCancel(true).build())
        }
    }
}

/** Quick Settings tile: the followed provider's percentage; tap to refresh from the PC. */
class UsageTileService : TileService() {
    override fun onStartListening() { render() }
    @android.annotation.SuppressLint("StartActivityAndCollapseDeprecated") // The Intent form is only used below Android 14, where it is supported.
    override fun onClick() {
        if (Repository(this).pairing() == null) {
            val open = openApp(this, 44)
            if (Build.VERSION.SDK_INT >= 34) startActivityAndCollapse(open) else @Suppress("DEPRECATION") startActivityAndCollapse(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        }
        requestRefresh(this)
        qsTile?.apply { if (Build.VERSION.SDK_INT >= 29) subtitle = "Refreshing…"; updateTile() }
    }
    private fun render() {
        val tile = qsTile ?: return
        val repo = Repository(this); val now = System.currentTimeMillis()
        val provider = followedProvider(this); val window = provider?.sessionWindow()
        tile.label = provider?.name ?: "UsageNotch"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = when {
            repo.pairing() == null -> "Tap to pair"
            window == null -> "No reading yet"
            window.resetPassed(now) -> "Renewed"
            else -> "${window.percent(repo.remaining())}% ${if (repo.remaining()) "left" else "used"}"
        }
        tile.icon = Icon.createWithResource(this, provider?.let { Logos.resource(it.id) } ?: R.drawable.ic_stat_notch)
        tile.state = if (window != null) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.updateTile()
    }
    companion object {
        fun refresh(context: Context) { runCatching { requestListeningState(context, ComponentName(context, UsageTileService::class.java)) } }
    }
}

/** A Sunday-evening summary of the week, built from the history already on this phone, so it works with the PC off. */
object WeeklyRecap {
    private const val CHANNEL = "recap"
    internal const val ACTION = "io.github.arnavdugad.usagenotch.WEEKLY_RECAP"
    fun enabled(context: Context) = Repository(context).prefs.getBoolean("weeklyRecap", false)
    fun setEnabled(context: Context, on: Boolean) { Repository(context).prefs.edit().putBoolean("weeklyRecap", on).apply(); schedule(context) }
    /** The next Sunday at 7 PM local time, strictly after [now]. */
    fun nextAt(now: Long, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long {
        val local = java.time.Instant.ofEpochMilli(now).atZone(zone)
        var at = local.toLocalDate().with(java.time.temporal.TemporalAdjusters.nextOrSame(java.time.DayOfWeek.SUNDAY)).atTime(19, 0).atZone(zone)
        if (!at.isAfter(local)) at = at.plusWeeks(1)
        return at.toInstant().toEpochMilli()
    }
    private fun intent(context: Context) = PendingIntent.getBroadcast(context, 9, Intent(context, RecapReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun schedule(context: Context) {
        val alarms = context.getSystemService(android.app.AlarmManager::class.java) ?: return
        if (!enabled(context)) { alarms.cancel(intent(context)); return }
        alarms.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, nextAt(System.currentTimeMillis()), intent(context))
    }
    internal fun deliver(context: Context) {
        if (!enabled(context) || !ResetAlerts.canNotify(context)) return
        val text = Recap.compose(Repository(context).snapshot(), java.time.LocalDate.now()) ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Weekly recap", NotificationManager.IMPORTANCE_LOW).apply { description = "A summary of your week, on Sunday evenings." })
        manager.notify(44, Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_notch).setContentTitle(text.title).setContentText(text.body)
            .setStyle(Notification.BigTextStyle().bigText(text.body)).setContentIntent(openApp(context, 45)).setAutoCancel(true).build())
    }
}
class RecapReceiver : android.content.BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != WeeklyRecap.ACTION) return
        runCatching { WeeklyRecap.deliver(context) }
        runCatching { WeeklyRecap.schedule(context) }
    }
}
