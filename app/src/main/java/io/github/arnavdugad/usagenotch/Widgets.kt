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
import android.os.Build
import android.os.Bundle
import android.util.SizeF
import android.content.res.Configuration
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
            afterNewData(applicationContext)
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
        afterNewData(context)
    }
}
open class UsageWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) { ids.forEach { renderWidget(context, manager, it, this is FocusWidget) }; requestRefresh(context) }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) { renderWidget(context, manager, id, this is FocusWidget) }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != REFRESH_ACTION) return
        // Refresh right away while the widget shows a spinner; the background job takes over if the PC is slow.
        val repo = Repository(context)
        repo.prefs.edit().putLong(REFRESHING, System.currentTimeMillis() + 12_000).apply()
        updateWidgets(context)
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            val started = System.currentTimeMillis()
            try {
                val done = kotlinx.coroutines.withTimeoutOrNull(9_000) { runCatching { repo.refresh() }; true }
                if (done == null) requestRefresh(context)
                // A fast Wi-Fi refresh would flash the spinner too briefly to notice.
                kotlinx.coroutines.delay((700 - (System.currentTimeMillis() - started)).coerceAtLeast(0))
            } finally {
                repo.prefs.edit().remove(REFRESHING).apply()
                afterNewData(context)
                pending.finish()
            }
        }
    }
    override fun onDeleted(context: Context, ids: IntArray) { val edit = Repository(context).prefs.edit(); ids.forEach { edit.remove("widget-$it") }; edit.apply() }
    companion object { const val REFRESH_ACTION = "io.github.arnavdugad.usagenotch.REFRESH"; internal const val REFRESHING = "widgetRefreshingUntil" }
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
/** Widget sizes the launcher can show: exact sizes on Android 12+, otherwise portrait and landscape from the size range. */
internal fun widgetSizes(context: Context, options: Bundle): List<SizeF> {
    if (Build.VERSION.SDK_INT >= 31) {
        // The typed overload is Android 13+; Android 12 and 12L only have the untyped one.
        val sizes = (if (Build.VERSION.SDK_INT >= 33) options.getParcelableArrayList(AppWidgetManager.OPTION_APPWIDGET_SIZES, SizeF::class.java)
            else @Suppress("DEPRECATION") options.getParcelableArrayList<SizeF>(AppWidgetManager.OPTION_APPWIDGET_SIZES))?.filter { it.width > 0 && it.height > 0 }
        if (!sizes.isNullOrEmpty()) return sizes.distinct().take(4)
    }
    val minW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH); val maxW = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
    val minH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT); val maxH = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT)
    if (minW <= 0 || minH <= 0) return listOf(SizeF(250f, 170f))
    val portrait = context.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
    return listOf(if (portrait) SizeF(minW.toFloat(), maxOf(minH, maxH).toFloat()) else SizeF(maxOf(minW, maxW).toFloat(), minH.toFloat()))
}
internal fun widgetInput(context: Context, id: Int, focus: Boolean): WidgetRenderer.Input {
    val repo = Repository(context); val snapshot = repo.snapshot(); val now = System.currentTimeMillis()
    return WidgetRenderer.Input(snapshot, repo.pairing() != null, widgetStatus(repo, snapshot, now), repo.remaining(), repo.use24(), now,
        darkFor(context, repo.appearance()), focus, repo.prefs.getString("widget-$id", null), repo.offline(), Budgets.all(repo.prefs))
}
internal fun buildWidgetViews(context: Context, id: Int, focus: Boolean, options: Bundle = Bundle()): RemoteViews {
    val input = widgetInput(context, id, focus)
    val refreshing = Repository(context).prefs.getLong(UsageWidget.REFRESHING, 0L) > System.currentTimeMillis()
    val density = context.resources.displayMetrics.density
    val open = PendingIntent.getActivity(context, id, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val refresh = PendingIntent.getBroadcast(context, id, Intent(context, if (focus) FocusWidget::class.java else UsageWidget::class.java).setAction(UsageWidget.REFRESH_ACTION), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    val sizes = widgetSizes(context, options)
    // Launchers cap a widget's total bitmap memory at about 1.5 screens; share a safe part of that across sizes.
    val metrics = context.resources.displayMetrics
    val budget = ((metrics.widthPixels.toLong() * metrics.heightPixels * 4 * 3 / 5) / sizes.size).toInt().coerceIn(400_000, WidgetRenderer.MAX_BITMAP_BYTES)
    fun views(size: SizeF): RemoteViews {
        val out = WidgetRenderer.render(context, WidgetRenderer.Frame(size.width, size.height, density, budget), input)
        return RemoteViews(context.packageName, R.layout.usage_widget).apply {
            setInt(R.id.widget_root, "setBackgroundResource", if (input.dark) R.drawable.widget_background else R.drawable.widget_background_light)
            setImageViewBitmap(R.id.widget_image, out.bitmap)
            setContentDescription(R.id.widget_image, out.description)
            setViewVisibility(R.id.widget_refresh, if (out.refresh && !refreshing) View.VISIBLE else View.GONE)
            setViewVisibility(R.id.widget_progress, if (out.refresh && refreshing) View.VISIBLE else View.GONE)
            setOnClickPendingIntent(R.id.widget_root, open); setOnClickPendingIntent(R.id.widget_image, open); setOnClickPendingIntent(R.id.widget_refresh, refresh)
        }
    }
    return if (Build.VERSION.SDK_INT >= 31 && sizes.size > 1) RemoteViews(sizes.associateWith { views(it) }) else views(sizes.first())
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
        setContent { NotchTheme(Repository(this).appearance()) {
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
