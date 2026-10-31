package io.github.arnavdugad.usagenotch

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Two gentle reminders that work from saved readings, so they keep working while the PC is off:
 * - Before a reset: ten minutes before a limit you've used at least half of renews ("Claude renews in 10 min").
 * - Good moment to start: when starting a session now makes it renew in the middle of your usual busy hours (from
 *   [BestStart]), at most once a day per provider.
 * Both respect quiet hours. Alarms are inexact (Doze may deliver them a few minutes late), like reset alerts.
 */
object Reminders {
    private const val CHANNEL = "reminders"
    internal const val ACTION = "io.github.arnavdugad.usagenotch.REMINDER"
    const val LEAD = 10 * 60_000L
    private const val HALF = .5
    fun preReset(context: Context) = Repository(context).prefs.getBoolean("preReset", false)
    fun goodMoment(context: Context) = Repository(context).prefs.getBoolean("goodMoment", false)
    fun setPreReset(context: Context, on: Boolean) { Repository(context).prefs.edit().putBoolean("preReset", on).apply(); schedule(context) }
    fun setGoodMoment(context: Context, on: Boolean) { Repository(context).prefs.edit().putBoolean("goodMoment", on).apply(); schedule(context) }

    data class Due(val key: String, val title: String, val body: String)

    /** Pure decision logic: the reminders due at [now] that haven't been sent. */
    fun due(snapshot: Snapshot?, now: Long, sent: Set<String>, preReset: Boolean, goodMoment: Boolean, use24: Boolean, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): List<Due> {
        val out = mutableListOf<Due>()
        for (p in snapshot?.providers.orEmpty()) {
            if (preReset) for (w in p.windows) {
                val reset = w.reset ?: continue
                if (w.resetPassed(now) || w.used < HALF || reset - now > LEAD + 60_000) continue
                val key = "pre|${p.id}|${w.id}|$reset"
                if (key in sent) continue
                val minutes = ((reset - now + 59_999) / 60_000).coerceAtLeast(1)
                out += Due(key, "${p.name} renews in $minutes min", "${w.label} is ${(w.used * 100).toInt()}% used. It renews at ${ClockText.time(reset, use24)}.")
            }
            if (goodMoment) BestStart.hint(p, now, zone)?.takeIf { it.now }?.let { h ->
                val key = "good|${p.id}|${java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()}"
                if (key !in sent) out += Due(key, "Good moment to start ${p.name}",
                    "Start now and your session renews at ${ClockText.time(h.renewAt, use24)}, in the middle of your busy hours (${BestStart.hours(h.busyFrom, h.busyTo, use24)}).")
            }
        }
        return out
    }

    /** When the next reminder could be due, or null when none is. */
    fun nextAt(snapshot: Snapshot?, now: Long, preReset: Boolean, goodMoment: Boolean, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
        val times = mutableListOf<Long>()
        for (p in snapshot?.providers.orEmpty()) {
            if (preReset) p.windows.filter { it.used >= HALF && !it.resetPassed(now) }.mapNotNull { it.reset?.minus(LEAD) }.filter { it > now }.forEach { times += it }
            if (goodMoment) BestStart.hint(p, now, zone)?.takeIf { !it.now && it.startAt > now }?.let { times += it.startAt }
        }
        return times.minOrNull()
    }

    private fun intent(context: Context) = PendingIntent.getBroadcast(context, 11, Intent(context, ReminderReceiver::class.java).setAction(ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    fun schedule(context: Context) {
        val alarms = context.getSystemService(AlarmManager::class.java) ?: return
        val repo = Repository(context); val on = preReset(context) to goodMoment(context)
        val next = if (on.first || on.second) nextAt(repo.snapshot(), System.currentTimeMillis(), on.first, on.second) else null
        if (next == null) alarms.cancel(intent(context)) else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next + 1_000, intent(context))
    }
    internal fun deliver(context: Context) {
        val repo = Repository(context); val prefs = repo.prefs; val now = System.currentTimeMillis()
        val sent = prefs.getStringSet("remindersSent", emptySet()).orEmpty()
        val due = due(repo.snapshot(), now, sent, preReset(context), goodMoment(context), repo.use24())
        if (due.isEmpty()) return
        prefs.edit().putStringSet("remindersSent", (sent + due.map { it.key }).toList().takeLast(200).toSet()).apply()
        if (!ResetAlerts.canNotify(context) || AlertPrefs.isQuiet(prefs, now)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_DEFAULT).apply {
            description = "Before a limit renews, and good moments to start a session."; enableVibration(true); vibrationPattern = longArrayOf(0, 25, 70, 25)
        })
        val open = PendingIntent.getActivity(context, 46, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_IMMUTABLE)
        due.forEach { d ->
            manager.notify(2000 + (d.key.hashCode() and 0xFFFF), Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_notch)
                .setContentTitle(d.title).setContentText(d.body).setStyle(Notification.BigTextStyle().bigText(d.body)).setContentIntent(open).setAutoCancel(true).build())
        }
    }
}
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Reminders.ACTION) return
        runCatching { Reminders.deliver(context) }
        runCatching { Reminders.schedule(context) }
    }
}
