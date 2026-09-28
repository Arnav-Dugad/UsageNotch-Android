package io.github.arnavdugad.usagenotch

import android.app.Application
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
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
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.work.*
import java.util.concurrent.TimeUnit

class NotchApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // A launcher must still be able to open the dashboard if WorkManager is
        // unavailable on a vendor build. Widget refresh is best effort.
        runCatching { scheduleRefresh(this) }
    }
}
fun scheduleRefresh(context: Context) {
    val request = PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()).build()
    WorkManager.getInstance(context).enqueueUniquePeriodicWork("usage-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
}
fun requestRefresh(context: Context) {
    runCatching { WorkManager.getInstance(context).enqueueUniqueWork("usage-refresh", ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<SyncWorker>().build()) }
}
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        Repository(applicationContext).refresh()
        updateWidgets(applicationContext)
        return Result.success() // Periodic work retries next interval; avoid waking an unreachable PC repeatedly.
    }
}
open class UsageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { renderWidget(context, manager, it, this is FocusWidget) }; requestRefresh(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { renderWidget(context, manager, id, this is FocusWidget) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == "io.github.arnavdugad.usagenotch.REFRESH") { requestRefresh(context); updateWidgets(context) }
    }
    override fun onDeleted(context: Context, ids: IntArray) { val edit = Repository(context).prefs.edit(); ids.forEach { edit.remove("widget-$it") }; edit.apply() }
}
class FocusWidget : UsageWidget()
fun updateWidgets(context: Context) {
    val manager = AppWidgetManager.getInstance(context)
    for (cls in listOf(UsageWidget::class.java, FocusWidget::class.java)) {
        manager.getAppWidgetIds(ComponentName(context, cls)).forEach { renderWidget(context, manager, it, cls == FocusWidget::class.java) }
    }
}
fun renderWidget(context: Context, manager: AppWidgetManager, id: Int, focus: Boolean) {
    manager.updateAppWidget(id, buildWidgetViews(context, id, focus, manager.getAppWidgetOptions(id)))
}
internal fun buildWidgetViews(context: Context, id: Int, focus: Boolean, options: Bundle = Bundle()): RemoteViews {
    val repo = Repository(context); val snapshot = repo.snapshot(); val now = System.currentTimeMillis()
    val selected = repo.prefs.getString("widget-$id", "claude")
    val providers = snapshot?.providers.orEmpty().let { list -> if (focus) list.filter { it.id == selected } else list.take(2) }
    val views = RemoteViews(context.packageName, R.layout.usage_widget)
    views.setTextViewText(R.id.widget_title, if (focus) providers.firstOrNull()?.name ?: "AI usage" else "UsageNotch")
    views.setTextViewText(R.id.widget_status, when { repo.pairing() == null -> "Tap to pair your Windows PC"; snapshot == null -> "Waiting for a PC reading"; repo.error().isNotEmpty() -> "PC unavailable · saved readings"; else -> "PC sync ${ClockText.age(repo.lastSync(), now)}" })
    views.removeAllViews(R.id.widget_rows)
    val tall = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT, 160) >= 260
    val rows = providers.flatMap { p -> p.windows.take(if (focus || tall) 2 else 1).map { p to it } }
    for ((p, w) in rows) {
        val row = RemoteViews(context.packageName, R.layout.widget_row)
        val suffix = if (repo.remaining()) "left" else "used"
        row.setTextViewText(R.id.row_title, "${if (focus) "" else p.name + " · "}${w.percent(repo.remaining())}% $suffix")
        row.setProgressBar(R.id.row_bar, 100, w.percent(repo.remaining()).coerceIn(0, 100), false)
        val state = readingState(p, w, now)
        row.setTextViewText(R.id.row_detail, "${w.label} · ${ClockText.age(w.at, now)}\n" + (if (state != "Observed on PC") state else w.reset?.let { "Resets ${ClockText.stamp(it, repo.use24())}" } ?: "Reset time not reported"))
        views.addView(R.id.widget_rows, row)
    }
    if (rows.isEmpty()) {
        val row = RemoteViews(context.packageName, R.layout.widget_row)
        row.setTextViewText(R.id.row_title, "No usage reading yet")
        row.setTextViewText(R.id.row_detail, "Open the app to check your connection.")
        row.setViewVisibility(R.id.row_bar, View.GONE); views.addView(R.id.widget_rows, row)
    }
    val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val refresh = PendingIntent.getBroadcast(context, id, Intent(context, if (focus) FocusWidget::class.java else UsageWidget::class.java).setAction("io.github.arnavdugad.usagenotch.REFRESH"), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
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
        setContent { NotchTheme {
            Surface { Column(Modifier.fillMaxSize().padding(28.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text("Your focus widget", style = MaterialTheme.typography.headlineMedium)
                Text("Choose one provider. Its usage windows stay separate.")
                if (providers.isEmpty()) Text("Pair your PC in UsageNotch first, then add this widget again.")
                providers.forEach { p -> Button(onClick = {
                    Repository(this@WidgetConfigurationActivity).prefs.edit().putString("widget-$id", p.id).apply()
                    renderWidget(this@WidgetConfigurationActivity, AppWidgetManager.getInstance(this@WidgetConfigurationActivity), id, true)
                    setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)); finish()
                }, modifier = Modifier.fillMaxWidth()) { Text(p.name) } }
                TextButton(onClick = { finish() }) { Text("Cancel") }
            } }
        } }
    }
}
