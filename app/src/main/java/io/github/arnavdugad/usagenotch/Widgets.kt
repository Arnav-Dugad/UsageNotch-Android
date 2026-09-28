package io.github.arnavdugad.usagenotch

import android.app.Application
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

class NotchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // Background refresh is best effort; the dashboard never depends on it.
        runCatching { scheduleRefresh(this) }
        runCatching { ResetAlerts.schedule(this) }
    }
}
private const val PERIODIC_JOB = 1
private const val REFRESH_JOB = 2
/** Uses the platform JobScheduler directly: no reflection, no generated database, nothing for R8 to strip. */
fun scheduleRefresh(context: Context) {
    val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
    if (scheduler.getPendingJob(PERIODIC_JOB) != null) return
    scheduler.schedule(JobInfo.Builder(PERIODIC_JOB, ComponentName(context, SyncJobService::class.java))
        .setPeriodic(15 * 60_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build())
}
fun requestRefresh(context: Context) {
    runCatching {
        context.getSystemService(JobScheduler::class.java)?.schedule(JobInfo.Builder(REFRESH_JOB, ComponentName(context, SyncJobService::class.java))
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).build())
    }
}
class SyncJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            try { Repository(applicationContext).refresh() } catch (_: Throwable) { }
            runCatching { updateWidgets(applicationContext) }
            runCatching { ResetAlerts.schedule(applicationContext) }
            jobFinished(params, false) // Periodic work retries next interval; avoid waking an unreachable PC repeatedly.
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = false
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
/** Restores background refresh and reset alarms after a reboot or app update. Only the system sends these broadcasts. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED && intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        runCatching { scheduleRefresh(context) }
        runCatching { ResetAlerts.schedule(context) }
        runCatching { updateWidgets(context) }
    }
}
open class UsageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { renderWidget(context, manager, it, this is FocusWidget) }; requestRefresh(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { renderWidget(context, manager, id, this is FocusWidget) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == REFRESH_ACTION) { requestRefresh(context); updateWidgets(context) }
    }
    override fun onDeleted(context: Context, ids: IntArray) { val edit = Repository(context).prefs.edit(); ids.forEach { edit.remove("widget-$it") }; edit.apply() }
    companion object { const val REFRESH_ACTION = "io.github.arnavdugad.usagenotch.REFRESH" }
}
class FocusWidget : UsageWidget()
fun updateWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context) ?: return
    for (cls in listOf(UsageWidget::class.java, FocusWidget::class.java)) {
        manager.getAppWidgetIds(ComponentName(context, cls)).forEach { renderWidget(context, manager, it, cls == FocusWidget::class.java) }
    }
}
fun renderWidget(context: Context, manager: AppWidgetManager, id: Int, focus: Boolean) {
    // One unreadable widget must never take down the app or the other widgets.
    runCatching { manager.updateAppWidget(id, buildWidgetViews(context, id, focus, manager.getAppWidgetOptions(id) ?: Bundle())) }
}
/** The provider a focus widget shows: the chosen one, or the first reported provider until one is chosen. */
internal fun focusProvider(providers: List<Provider>, selected: String?) = providers.firstOrNull { it.id == selected } ?: providers.firstOrNull()
internal fun widgetStatus(repo: Repository, snapshot: Snapshot?, now: Long) = when {
    repo.pairing() == null -> "Tap to pair your Windows PC"
    snapshot == null -> "Waiting for a PC reading"
    repo.error().isNotEmpty() -> "Needs attention · saved readings"
    repo.offline() -> "PC offline · synced ${ClockText.age(repo.lastSync(), now)}"
    repo.source() == Source.Internet -> "Internet sync ${ClockText.age(repo.lastSync(), now)}"
    else -> "PC sync ${ClockText.age(repo.lastSync(), now)}"
}
internal fun buildWidgetViews(context: Context, id: Int, focus: Boolean, options: Bundle = Bundle()): RemoteViews {
    val repo = Repository(context); val snapshot = repo.snapshot(); val now = System.currentTimeMillis()
    val all = snapshot?.providers.orEmpty()
    val providers = if (focus) listOfNotNull(focusProvider(all, repo.prefs.getString("widget-$id", null))) else all.take(2)
    val views = RemoteViews(context.packageName, R.layout.usage_widget)
    views.setTextViewText(R.id.widget_title, if (focus) providers.firstOrNull()?.name ?: "AI usage" else "UsageNotch")
    views.setTextViewText(R.id.widget_status, widgetStatus(repo, snapshot, now))
    views.removeAllViews(R.id.widget_rows)
    val tall = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160) >= 260
    val rows = providers.flatMap { p -> p.windows.take(if (focus || tall) 2 else 1).map { p to it } }
    val remaining = repo.remaining(); val use24 = repo.use24()
    for ((p, w) in rows) {
        val row = RemoteViews(context.packageName, R.layout.widget_row)
        val prefix = if (focus) "" else p.name + " · "
        if (w.resetPassed(now)) {
            row.setTextViewText(R.id.row_title, "${prefix}Renewed")
            row.setViewVisibility(R.id.row_bar, View.GONE)
            row.setTextViewText(R.id.row_detail, "${w.label} · reset ${ClockText.time(w.reset!!, use24)}\nNew usage appears after the next PC reading")
        } else {
            row.setTextViewText(R.id.row_title, "$prefix${w.percent(remaining)}% ${if (remaining) "left" else "used"}")
            row.setProgressBar(R.id.row_bar, 100, w.percent(remaining).coerceIn(0, 100), false)
            val state = readingState(p, w, now)
            row.setTextViewText(R.id.row_detail, "${w.label} · ${ClockText.age(w.at, now)}\n" + (if (state != "Observed on PC") state else w.reset?.let { "Resets ${ClockText.stamp(it, use24)}" } ?: "Reset time not reported"))
        }
        views.addView(R.id.widget_rows, row)
    }
    if (rows.isEmpty()) {
        val row = RemoteViews(context.packageName, R.layout.widget_row)
        row.setTextViewText(R.id.row_title, "No usage reading yet")
        row.setTextViewText(R.id.row_detail, "Open the app to check your connection.")
        row.setViewVisibility(R.id.row_bar, View.GONE); views.addView(R.id.widget_rows, row)
    }
    val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val refresh = PendingIntent.getBroadcast(context, id, Intent(context, if (focus) FocusWidget::class.java else UsageWidget::class.java).setAction(UsageWidget.REFRESH_ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    views.setOnClickPendingIntent(R.id.widget_root, open); views.setOnClickPendingIntent(R.id.widget_refresh, refresh)
    return views
}
class WidgetConfigurationActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        val id = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
        if (id == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }
        val providers = Repository(this).snapshot()?.providers.orEmpty()
        fun choose(providerId: String?) {
            Repository(this).prefs.edit().apply { if (providerId == null) remove("widget-$id") else putString("widget-$id", providerId) }.apply()
            renderWidget(this, AppWidgetManager.getInstance(this), id, true)
            setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
        }
        setContent { NotchTheme {
            Surface { Column(Modifier.fillMaxSize().systemBarsPadding().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Your focus widget", style = MaterialTheme.typography.headlineMedium)
                Text("Choose one provider. Its usage windows stay separate.")
                if (providers.isEmpty()) {
                    Text("No readings yet. The widget will show your first provider once your PC is paired.")
                    Button(onClick = { choose(null) }, modifier = Modifier.fillMaxWidth()) { Text("Add widget") }
                }
                providers.forEach { p -> Button(onClick = { choose(p.id) }, modifier = Modifier.fillMaxWidth()) { Text(p.name) } }
                TextButton(onClick = { finish() }) { Text("Cancel") }
            } }
        } }
    }
}
