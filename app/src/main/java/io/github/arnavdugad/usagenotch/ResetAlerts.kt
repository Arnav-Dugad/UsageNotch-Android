package io.github.arnavdugad.usagenotch

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * Works from saved readings alone, so it keeps working while the PC is off: at each reported
 * reset time the widgets refresh to "Renewed" and, when enabled, a notification is posted.
 */
object ResetAlerts {
    private const val CHANNEL = "resets"
    internal const val ACTION = "io.github.arnavdugad.usagenotch.RESET_DUE"
    fun enabled(context: Context) = Repository(context).prefs.getBoolean("resetAlerts", false)
    fun setEnabled(context: Context, on: Boolean) {
        // Only resets after this moment are announced; earlier ones were already visible in the app.
        Repository(context).prefs.edit().putBoolean("resetAlerts", on).putLong("alertedThrough", System.currentTimeMillis()).apply()
        schedule(context)
    }
    private fun intent(context: Context) = PendingIntent.getBroadcast(context, 7, Intent(context, ResetAlertReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    internal fun nextReset(snapshot: Snapshot?, now: Long) = snapshot?.providers.orEmpty().flatMap { it.windows }.mapNotNull { it.reset }.filter { it > now }.minOrNull()
    /** Windows whose reset fell in (after, now], grouped by provider name. */
    internal fun due(snapshot: Snapshot?, after: Long, now: Long): Map<String, List<UsageWindow>> =
        snapshot?.providers.orEmpty().associate { p -> p.name to p.windows.filter { w -> w.reset != null && w.at < w.reset && w.reset > after && w.reset <= now } }.filterValues { it.isNotEmpty() }
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val next = nextReset(Repository(context).snapshot(), System.currentTimeMillis())
        // Inexact and battery friendly; Android may deliver a few minutes late in Doze.
        if (next == null) alarms.cancel(intent(context)) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 2_000, intent(context))
    }
    internal fun deliver(context: Context) {
        val repo = Repository(context); val now = System.currentTimeMillis()
        val after = repo.prefs.getLong("alertedThrough", now)
        val due = due(repo.snapshot(), after, now)
        repo.prefs.edit().putLong("alertedThrough", now).apply()
        if (!enabled(context) || due.isEmpty() || !canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Limit resets", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "When a usage window reported by your PC resets." })
        val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        due.entries.forEachIndexed { index, (name, windows) ->
            val labels = windows.joinToString(" and ") { it.label }
            val notification = android.app.Notification.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat_notch)
                .setContentTitle("$name limit renewed")
                .setContentText("$labels reset at ${ClockText.time(windows.maxOf { it.reset!! }, repo.use24())}.")
                .setContentIntent(open).setAutoCancel(true).build()
            manager.notify(100 + (name.hashCode() and 0xFFFF) + index, notification)
        }
    }
    fun canNotify(context: Context) = Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
class ResetAlertReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ResetAlerts.ACTION) return
        runCatching { ResetAlerts.deliver(context) }
        runCatching { updateWidgets(context) }
        runCatching { ResetAlerts.schedule(context) }
    }
}
