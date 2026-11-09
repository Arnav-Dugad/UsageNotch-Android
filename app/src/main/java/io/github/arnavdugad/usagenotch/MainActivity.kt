package io.github.arnavdugad.usagenotch

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.PredictiveBackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Uri?>(null)
    private var link by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.launching(this)
        if (CrashLog.inSafeMode(this)) { showSafeMode(); return }
        runCatching { enableEdgeToEdge() }
        if (savedInstanceState == null) accept(intent)
        addOnNewIntentListener { accept(it) }
        setContent {
            val repo = remember { Repository(this) }
            var appearance by remember { mutableStateOf(repo.appearance()) }
            var wallpaper by remember { mutableStateOf(repo.prefs.getBoolean("wallpaperColors", false)) }
            var style by remember { mutableStateOf(UiStyle.of(repo.prefs.getString("uiStyle", null))) }
            NotchTheme(appearance, wallpaper, style) { NotchApp(incoming, { incoming = null }, link, { link = null }, appearance, { appearance = it }, wallpaper, { wallpaper = it }, style) { style = it } }
        }
        // Surviving the first seconds clears the startup-crash counter used for safe mode.
        window.decorView.postDelayed({ CrashLog.settled(this) }, 8_000)
    }
    override fun onStart() { super.onStart(); Updates.foreground = true }
    override fun onStop() { Updates.foreground = false; super.onStop() }
    private fun accept(intent: Intent?) {
        val data = intent?.data
        when {
            intent?.action == Intent.ACTION_VIEW && data?.scheme == "usagenotch" -> link = data.toString()
            intent?.action == Intent.ACTION_VIEW -> incoming = data
            intent?.action == Intent.ACTION_SEND -> incoming = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        }
    }
    /** Plain platform views only, so this screen works even if the regular dashboard cannot start. */
    private fun showSafeMode() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val report = CrashLog.report(this) ?: "No report was saved."
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad); setBackgroundColor(0xFF0A0C11.toInt()) }
        fun text(value: String, size: Float, color: Int) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(0, pad / 2, 0, pad / 2) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
        column.addView(text("UsageNotch safe mode", 24f, 0xFFF2F4F8.toInt()))
        column.addView(text("UsageNotch closed unexpectedly twice while starting. You can share the report below with the developer, or clear this phone's UsageNotch data (your Windows history is untouched).", 15f, 0xFFA9B1BF.toInt()))
        column.addView(button("Share crash report") { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share crash report")) })
        column.addView(button("Try again") { CrashLog.clear(this); recreate() })
        column.addView(button("Clear app data and try again") {
            listOf("usage", "display", "pairing-vault").forEach { getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit() }
            CrashLog.clear(this); recreate()
        })
        column.addView(text(report, 11f, 0xFFA9B1BF.toInt()).apply { gravity = Gravity.START; setTextIsSelectable(true) })
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF0A0C11.toInt()); addView(column) })
    }
}
internal fun readBounded(stream: InputStream, max: Int): ByteArray {
    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = stream.read(buffer, 0, min(buffer.size, max + 1 - output.size())); if (count < 0) break; output.write(buffer, 0, count); require(output.size() <= max) { "File too large" } }
    return output.toByteArray()
}
private const val NOT_A_PAIRING = "That isn't a UsageNotch pairing. On your PC, open UsageNotch → Settings → Phone and scan the code shown there."
private const val UNREADABLE = "Couldn't open that file. Save it to your phone first (for example in Downloads), then try again, or scan the QR code instead."
private const val PC_UNREACHABLE = "Couldn't reach your PC. In UsageNotch on Windows, open Settings → Phone and turn on sharing. Keep both devices on the same Wi-Fi or private VPN, and allow UsageNotch through Windows Firewall on Private networks."

private val TABS = listOf(TabItem("Overview", Icons.Outlined.DonutLarge), TabItem("History", Icons.Outlined.Insights), TabItem("Widgets", Icons.Outlined.Widgets), TabItem("Settings", Icons.Outlined.Tune))

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@Composable fun NotchApp(
    incoming: Uri? = null, incomingHandled: () -> Unit = {}, link: String? = null, linkHandled: () -> Unit = {},
    appearance: String = "system", appearanceChanged: (String) -> Unit = {},
    wallpaper: Boolean = false, wallpaperChanged: (Boolean) -> Unit = {},
    style: UiStyle = UiStyle.Glass, styleChanged: (UiStyle) -> Unit = {},
) {
    val context = LocalContext.current; val repo = remember { Repository(context) }; val scope = rememberCoroutineScope(); val t = LocalTokens.current
    var snapshot by remember { mutableStateOf(repo.snapshot()) }; var pairing by remember { mutableStateOf(repo.pairing()) }
    var error by remember { mutableStateOf(repo.error()) }; var offline by remember { mutableStateOf(repo.offline()) }; var source by remember { mutableStateOf(repo.source()) }
    var pairError by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }; var refreshing by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }; var demo by rememberSaveable { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(repo.remaining()) }; var clock24 by remember { mutableStateOf(repo.use24()) }
    var reduced by remember { mutableStateOf(repo.reduceMotion()) }; var disconnect by remember { mutableStateOf(false) }
    var alerts by remember { mutableStateOf(ResetAlerts.enabled(context) && ResetAlerts.canNotify(context)) }
    var checkUpdates by remember { mutableStateOf(Updates.enabled(context)) }; var update by remember { mutableStateOf(Updates.available(context)) }
    var autoUpdate by remember { mutableStateOf(Updates.automatic(context)) }
    var crash by remember { mutableStateOf(CrashLog.report(context)) }; var pasting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pairing?>(null) }; var confirmText by remember { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }; var scanning by rememberSaveable { mutableStateOf(false) }
    var dayOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var liveUpdate by remember { mutableStateOf(LiveUpdate.enabled(context) && ResetAlerts.canNotify(context)) }
    var usageAlerts by remember { mutableStateOf(UsageAlerts.enabled(context) && ResetAlerts.canNotify(context)) }
    var recap by remember { mutableStateOf(WeeklyRecap.enabled(context) && ResetAlerts.canNotify(context)) }
    var preReset by remember { mutableStateOf(Reminders.preReset(context) && ResetAlerts.canNotify(context)) }
    var goodMoment by remember { mutableStateOf(Reminders.goodMoment(context) && ResetAlerts.canNotify(context)) }
    var levels by remember { mutableStateOf(AlertPrefs.warn(repo.prefs) to AlertPrefs.critical(repo.prefs)) }
    var quiet by remember { mutableStateOf(Triple(AlertPrefs.quietOn(repo.prefs), AlertPrefs.quietStart(repo.prefs), AlertPrefs.quietEnd(repo.prefs))) }
    var refreshNote by remember { mutableStateOf("") }
    var pendingSettings by remember { mutableIntStateOf(repo.pendingSettings()) }
    var followed by remember { mutableStateOf(repo.prefs.getString("liveProvider", null)) }
    var askingFor by remember { mutableStateOf<String?>(null) }
    var savedBudgets by remember { mutableStateOf(Budgets.all(repo.prefs)) }
    var accentVersion by remember { mutableIntStateOf(0) }
    // Budgets and colours chosen while exploring sample data stay in memory and never touch your settings.
    var demoBudgets by remember { mutableStateOf(emptyList<Budget>()) }
    var demoAccents by remember { mutableStateOf(emptyMap<String, Int?>()) }
    val historyState = rememberHistoryState()
    // This phone's permanent history, reloaded whenever a new snapshot arrives or you export or delete it.
    var stored by remember { mutableStateOf(StoredHistory.Empty) }; var storedStats by remember { mutableStateOf<StoredStats?>(null) }
    var storedVersion by remember { mutableIntStateOf(0) }; var deleteHistory by remember { mutableStateOf(false) }; var dataNote by remember { mutableStateOf("") }
    var whatsNew by remember { mutableStateOf(WhatsNew.pending(context)) }
    // Your day notes (this phone's, the PC's merged in), sample-mode notes kept in memory, and the score explanation.
    var notes by remember { mutableStateOf(emptyMap<String, String>()) }; var demoNotes by remember { mutableStateOf(mapOf(java.time.LocalDate.now().minusDays(2).toString() to "Big refactor")) }
    var scoreSheet by remember { mutableStateOf(false) }
    var projectBudgetVersion by remember { mutableIntStateOf(0) }; var demoProjectBudgets by remember { mutableStateOf(emptyMap<String, Long?>()) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val pageStates = List(TABS.size) { rememberLazyListState() }
    val listState = pageStates[0]
    fun reload() {
        snapshot = repo.snapshot(); pairing = repo.pairing(); error = repo.error(); offline = repo.offline(); source = repo.source(); update = Updates.available(context)
        // Settings synced from the PC can change budgets, colours, alert levels and quiet hours.
        savedBudgets = Budgets.all(repo.prefs); accentVersion++; pendingSettings = repo.pendingSettings()
        levels = AlertPrefs.warn(repo.prefs) to AlertPrefs.critical(repo.prefs)
        quiet = Triple(AlertPrefs.quietOn(repo.prefs), AlertPrefs.quietStart(repo.prefs), AlertPrefs.quietEnd(repo.prefs))
    }
    fun afterSync() { reload(); afterNewData(context) }
    val haptics = rememberHaptics(reduced)
    fun refresh() { if (!busy) scope.launch { busy = true; refreshing = true; var ok = false; try { repo.refresh(explicit = true); ok = true } catch (_: Exception) { } finally { afterSync(); refreshNote = repo.refreshNote(); busy = false; refreshing = false; if (ok) haptics.confirm() } } }
    /** After a change to a synced setting: send it to the PC now if it's on this network; otherwise it waits. */
    fun syncSoon() { reload(); scope.launch { try { repo.syncSettings() } catch (_: Exception) { }; pendingSettings = repo.pendingSettings() } }
    fun importPairing(read: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true; pairError = ""
            try {
                val raw = try { read() } catch (_: Exception) { pairError = UNREADABLE; return@launch }
                if (runCatching { Pairing.parse(raw) }.isFailure) pairError = NOT_A_PAIRING
                else { repo.pair(raw); demo = false; pasting = false; page = 0; afterSync() }
            } catch (_: Exception) { pairError = PC_UNREACHABLE }
            finally { busy = false }
        }
    }
    fun readUri(uri: Uri) = importPairing { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { String(readBounded(it, 16_384), Charsets.UTF_8) } ?: error("Could not open file") } }
    fun scan() { pairError = ""; scanning = true }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            reload()
            launch { if (repo.pairing() != null) { try { repo.refresh() } catch (_: Exception) { }; afterSync() } }
            launch { try { Updates.check(context) } catch (_: Exception) { }; update = Updates.available(context) }
            var seen = repo.lastCheck()
            while (isActive) {
                now = System.currentTimeMillis()
                val current = repo.lastCheck()
                if (current != seen) { seen = current; reload() }
                delay(1000)
            }
        }
    }
    LaunchedEffect(snapshot?.generatedAt, storedVersion) {
        withContext(Dispatchers.IO) { runCatching { LocalHistory.open(context) { Triple(it.load(), it.stats(), it.notes()) } }.getOrNull() }?.let { (h, st, n) -> stored = h; storedStats = st; notes = n }
    }
    val exportCsv = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        if (uri != null) scope.launch {
            dataNote = withContext(Dispatchers.IO) { runCatching {
                val rows = context.contentResolver.openOutputStream(uri)?.bufferedWriter(Charsets.UTF_8)?.use { out -> LocalHistory.open(context) { it.exportCsv(out) } } ?: error("Could not open file")
                "Exported $rows day${if (rows == 1) "" else "s"}"
            }.getOrDefault("Couldn't export. Try another location.") }
        }
    }
    LaunchedEffect(incoming) { incoming?.let { readUri(it); page = 0; incomingHandled() } }
    // Links can come from any web page, so they are confirmed before replacing a pairing.
    LaunchedEffect(link) { link?.let { text -> runCatching { Pairing.parse(text) }.onSuccess { confirm = it; confirmText = text }.onFailure { pairError = NOT_A_PAIRING }; linkHandled() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) readUri(uri) }
    fun applyNotify(key: String, on: Boolean) = when (key) {
        "reset" -> { alerts = on; ResetAlerts.setEnabled(context, on) }
        "live" -> { liveUpdate = on; LiveUpdate.setEnabled(context, on) }
        "recap" -> { recap = on; WeeklyRecap.setEnabled(context, on) }
        "pre" -> { preReset = on; Reminders.setPreReset(context, on) }
        "good" -> { goodMoment = on; Reminders.setGoodMoment(context, on) }
        else -> { usageAlerts = on; UsageAlerts.setEnabled(context, on); UsageAlerts.evaluate(context) }
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> askingFor?.let { applyNotify(it, granted) }; askingFor = null }
    // Notifications need permission on Android 13+; ask only when a notification setting is switched on.
    fun setNotify(key: String, on: Boolean) {
        if (on && !ResetAlerts.canNotify(context) && Build.VERSION.SDK_INT >= 33) { askingFor = key; notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        else applyNotify(key, on)
    }
    fun save(key: String, value: Boolean) { repo.prefs.edit().putBoolean(key, value).apply(); runCatching { updateWidgets(context) } }
    val shown = if (demo) remember { demoSnapshot() } else snapshot
    val providers = shown?.providers.orEmpty()
    // Feel it when something happens while you watch: a limit crossing your warning or urgent level, or renewing.
    val levelsNow = providers.associate { p -> p.id to (p.sessionWindow()?.takeIf { !it.resetPassed(now) }?.let { Haptics.level(it.used, levels.first, levels.second) } ?: 0) }
    val renewedNow = providers.filter { it.sessionWindow()?.resetPassed(now) == true }.map { it.id }.toSet()
    var seenLevels by remember { mutableStateOf<Map<String, Int>?>(null) }; var seenRenewed by remember { mutableStateOf<Set<String>?>(null) }
    LaunchedEffect(levelsNow) {
        val before = seenLevels
        if (before != null) levelsNow.entries.maxOfOrNull { (id, level) -> if (level > (before[id] ?: level)) level else 0 }?.takeIf { it > 0 }?.let { haptics.warning(urgent = it == 2) }
        seenLevels = levelsNow
    }
    LaunchedEffect(renewedNow) {
        if (seenRenewed?.let { (renewedNow - it).isNotEmpty() } == true) haptics.renewed()
        seenRenewed = renewedNow
    }
    val connected = pairing != null && !demo
    val offlineNotice = connected && offline && error.isBlank() && snapshot != null
    // Items above the dock on Overview, so the floating dock knows when the real one has scrolled away.
    val leading = listOf(crash != null, update != null, pairError.isNotBlank() && !pasting, error.isNotBlank() && connected, offlineNotice).count { it }
    val openUrl: (String) -> Unit = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }
    val budgets = if (demo) demoBudgets else savedBudgets
    val accentOf = remember(accentVersion, demo, demoAccents) { val cache = mutableMapOf<String, Color>(); { id: String -> cache.getOrPut(id) {
        Color(if (demo && providerKind(id) in demoAccents) demoAccents[providerKind(id)] ?: Accents.default(id) else Accents.of(repo.prefs, id)) } } }
    fun budgetsFor(id: String) = budgets.filter { it.provider == id }
    val day = DayKey.decode(dayOpen)
    val shownNotes = if (demo) demoNotes else notes
    /** Saves a note here at once, then sends it to the PC when it's on this network. Sample mode keeps notes in memory. */
    val onNote: (String, String) -> Unit = { date, text ->
        if (demo) demoNotes = (demoNotes + (date to DayNotes.clean(text))).filterValues { it.isNotEmpty() }
        else scope.launch {
            withContext(Dispatchers.IO) { runCatching { LocalHistory.open(context) { it.setNote(date, text) } } }
            storedVersion++
            try { repo.syncNotes() } catch (_: Exception) { }
            storedVersion++
        }
    }
    val projectBudgets = remember(demo, projectBudgetVersion, demoProjectBudgets, snapshot?.has("settings")) {
        ProjectBudgetHooks(
            of = { source, project, fromPc -> if (demo) (if (ProjectBudgets.key(source, project) in demoProjectBudgets) demoProjectBudgets[ProjectBudgets.key(source, project)] else fromPc) else ProjectBudgets.of(repo.prefs, source, project, fromPc) },
            set = when {
                demo -> { source, project, tokens -> demoProjectBudgets = demoProjectBudgets + (ProjectBudgets.key(source, project) to tokens) }
                snapshot?.has("settings") == true -> { source, project, tokens -> ProjectBudgets.set(repo.prefs, source, project, tokens); projectBudgetVersion++; syncSoon() }
                else -> null
            })
    }
    val historyProviders = remember(demo, providers, snapshot, stored) { if (demo) providers else LocalHistory.enrich(snapshot?.providers.orEmpty(), stored) }

    // Back from History, Widgets or Settings returns to Overview; the page shrinks with the predictive back gesture.
    var pageBack by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = detail == null && day == null && !scanning && page != 0) { progress ->
        try { progress.collect { pageBack = it.progress }; page = 0 } finally { pageBack = 0f }
    }
    BackHandler(enabled = day != null && !scanning) { dayOpen = null }
    val open = providers.firstOrNull { it.id == detail }
    LaunchedEffect(open == null) { if (open == null) detail = null }

    val ambient = rememberLayerBackdrop(); val screen = rememberLayerBackdrop()
    val floatingGlass = remember(ambient, screen) { GlassBackdrops(ambient, screen) }
    // Glass inside the scrolling content samples the ambient light only, never the layer that contains it.
    val contentGlass = remember(ambient) { GlassBackdrops(ambient, ambient) }
    val floating by remember { derivedStateOf { listState.firstVisibleItemIndex > leading } }
    val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    CompositionLocalProvider(LocalAccent provides accentOf, LocalHaptics provides haptics) {
    Box(Modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().layerBackdrop(screen)) {
            AmbientBackground(providers.take(3).map { accentOf(it.id) }, animate = !reduced, modifier = Modifier.layerBackdrop(ambient))
            CompositionLocalProvider(LocalGlass provides contentGlass) {
            SharedTransitionLayout(Modifier.fillMaxSize()) {
            CompositionLocalProvider(LocalSharedScope provides this) {
                AnimatedContent(open?.id, transitionSpec = {
                    if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                    else (fadeIn(tween(300, delayMillis = 60)) + scaleIn(tween(360, easing = FastOutSlowInEasing), initialScale = .96f)) togetherWith fadeOut(tween(200))
                }, label = "Provider detail") { openId ->
                    val visibility = this
                    // The dock ring morphs into the detail ring (and back) through a shared element.
                    val share: @Composable (String) -> Modifier = { id -> if (reduced) Modifier else Modifier.sharedElement(rememberSharedContentState("ring-$id"), visibility) }
                    val openProvider = providers.firstOrNull { it.id == openId }
                    if (openProvider != null) ProviderDetail(openProvider, now, remaining, clock24, reduced, offline && !demo, demo, share(openProvider.id), budgetsFor(openProvider.id),
                        onBudget = { window, b ->
                            val others = budgets.filterNot { it.provider == openProvider.id && it.window == window }
                            val next = if (b == null) others else others + b.copy(provider = openProvider.id, window = window)
                            if (demo) demoBudgets = next
                            else { if (b == null) Budgets.remove(repo.prefs, openProvider.id, window) else Budgets.set(repo.prefs, b.copy(provider = openProvider.id, window = window)); savedBudgets = Budgets.all(repo.prefs); afterNewData(context); syncSoon() }
                        },
                        onAccent = { color ->
                            if (demo) demoAccents = demoAccents + (providerKind(openProvider.id) to color)
                            else { Accents.set(repo.prefs, openProvider.id, color); accentVersion++; runCatching { updateWidgets(context) }; syncSoon() }
                        },
                        onDashboard = openUrl, incidents = shown?.incidents.orEmpty(),
                        customName = if (demo) null else SharedSettings.nameOf(repo.prefs, openProvider.id),
                        onName = { name -> SharedSettings.setName(repo.prefs, openProvider.id, name); runCatching { updateWidgets(context) }; syncSoon() }) { detail = null }
                    else CompositionLocalProvider(LocalRingShare provides share) {
                        AnimatedContent(targetState = page, transitionSpec = {
                            if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                            // The same spring as the glass tab bar's drop, on Windows and here, so the page follows the selection.
                            else {
                                val dir = if (targetState > initialState) 1 else -1
                                (fadeIn(tween(DesignTokens.Motion.fastMs)) + slideInHorizontally(spring(DesignTokens.Motion.drop.first, DesignTokens.Motion.drop.second)) { dir * it / 6 } +
                                    scaleIn(spring(DesignTokens.Motion.snappy.first, DesignTokens.Motion.snappy.second), initialScale = .985f)) togetherWith
                                    (fadeOut(tween(120)) + slideOutHorizontally(spring(DesignTokens.Motion.snappy.first, DesignTokens.Motion.snappy.second)) { -dir * it / 12 })
                            }
                        }, label = "Page transition", modifier = Modifier.graphicsLayer {
                            val scale = 1f - pageBack * .06f; scaleX = scale; scaleY = scale; alpha = 1f - pageBack * .25f
                        }) { current ->
                            val pullState = rememberPullToRefreshState()
                            val top = topInset + if (current == 0) 68.dp else 16.dp
                            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { if (connected) refresh() }, state = pullState, modifier = Modifier.fillMaxSize(),
                                indicator = { PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = top)) }) {
                                LazyColumn(Modifier.fillMaxSize(), state = pageStates[current], contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    crash?.let { report -> item(key = "crash") { Notice("UsageNotch closed unexpectedly", "Share the crash report to help fix it. It has no usage or pairing data.", "Share", "Dismiss", { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share crash report")) }) { CrashLog.clear(context); crash = null } } }
                                    update?.let { version -> item(key = "update") { UpdateNotice(version, openUrl) } }
                                    // In sample mode the top capsule says so and offers Exit; other tabs get a short notice.
                                    if (demo && current != 0) item(key = "demo") { Notice("Sample data", null, "Exit preview", clicked = { demo = false }, tone = Color(0xFF8FB6FF)) }
                                    if (pairError.isNotBlank() && !pasting) item(key = "pairError") { Notice("Pairing didn't finish", pairError, "Dismiss", clicked = { pairError = "" }) }
                                    if (error.isNotBlank() && connected) item(key = "error") { Notice("Connection needs attention", error) }
                                    when (current) {
                                        0 -> {
                                            if (offlineNotice) item(key = "offline") { Notice("PC offline", "Showing saved readings from ${ClockText.age(repo.lastSync(), now)}. Dashed bars estimate where usage is now.", tone = t.muted) }
                                            if (refreshNote == "throttled") item(key = "refreshNote") { Notice("Up to date", "Your PC read your usage moments ago, so this is its latest reading.", tone = Color(Palette.LIVE)) }
                                            shown?.incidents.orEmpty().forEachIndexed { i, incident -> item(key = "incident-$i") { IncidentBanner(incident, providers.firstOrNull { it.kind == providerKind(incident.provider) }?.name ?: PhoneNames.of(incident.provider), openUrl) } }
                                            if (providers.isEmpty()) item(key = "pair") { PairCard(busy, connected, { scan() }, { pasting = true }, { picker.launch(arrayOf("*/*")) }, { demo = true }) }
                                            else {
                                                item(key = "dock") {
                                                    Panel(Modifier.fillMaxWidth()) {
                                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                                            providers.forEach { p -> DockCell(p, remaining, now, reduced, selected == p.id, budget = budgetsFor(p.id).firstOrNull { it.window == p.sessionWindow()?.id }, budgets = budgetsFor(p.id)) { selected = p.id; detail = p.id } }
                                                        }
                                                    }
                                                }
                                                UsageScores.of(shown)?.let { score -> item(key = "score") { ScoreCard(score, reduced) { scoreSheet = true } } }
                                                providers.forEach { p -> item(key = "p-${p.id}") {
                                                    ProviderCard(p, now, remaining, clock24, reduced, offline && !demo, demo, openUrl, budgets = budgetsFor(p.id), onOpen = { selected = p.id; detail = p.id })
                                                } }
                                            }
                                        }
                                        1 -> historyPage(historyProviders, historyState, clock24, reduced, day, { dayOpen = it.encode() }, shown?.tokens, shown?.report, shownNotes, projectBudgets)
                                        2 -> {
                                            item { WidgetPreviews(shown ?: demoSnapshot(), now, t.dark, remaining, clock24, offline && !demo, budgets) }
                                            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                GlassButton({ pinWidget(context, false) }, Modifier.weight(1f), prominent = true) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Overview", fontWeight = FontWeight.SemiBold) }
                                                GlassButton({ pinWidget(context, true) }, Modifier.weight(1f)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Focus", fontWeight = FontWeight.SemiBold) }
                                                GlassButton({ pinWidget(context, false, tokens = true) }, Modifier.weight(1f)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Tokens", fontWeight = FontWeight.SemiBold) }
                                            } }
                                            item { QuickTileCard() }
                                        }
                                        3 -> settingsPage(
                                            pairing, busy, { scan() }, { pasting = true }, { picker.launch(arrayOf("*/*")) }, { disconnect = true },
                                            appearance, { key -> repo.prefs.edit().putString("appearance", key).apply(); appearanceChanged(key); runCatching { updateWidgets(context) } },
                                            wallpaper, { repo.prefs.edit().putBoolean("wallpaperColors", it).apply(); wallpaperChanged(it) },
                                            liveUpdate, usageAlerts, alerts, recap, { key, on -> setNotify(key, on) },
                                            snapshot?.providers.orEmpty(), followed, { id -> followed = id; repo.prefs.edit().putString("liveProvider", id).apply(); LiveUpdate.update(context); UsageTileService.refresh(context) },
                                            remaining, { remaining = it; save("remaining", it) }, clock24, { clock24 = it; save("clock24", it) }, reduced, { reduced = it; save("reduceMotion", it) },
                                            checkUpdates, { checkUpdates = it; save("checkUpdates", it); update = Updates.available(context); Updates.schedule(context) }, reducedMotion = reduced, openUrl = openUrl,
                                            style = style, setStyle = { next -> repo.prefs.edit().putString("uiStyle", next.key).apply(); styleChanged(next); runCatching { updateWidgets(context) } },
                                            autoUpdate = autoUpdate, setAutoUpdate = { autoUpdate = it; Updates.setAutomatic(context, it) }, update = update,
                                            data = DataExtras(storedStats, dataNote, { dataNote = ""; exportCsv.launch("usagenotch-history-${java.time.LocalDate.now()}.csv") }, { deleteHistory = true }, { whatsNew = true }),
                                            extras = SettingsExtras(preReset, goodMoment, levels.first, levels.second, quiet.first, quiet.second, quiet.third,
                                                syncLine = if (snapshot?.has("settings") == true && pairing != null) (if (pendingSettings > 0) "Settings sync · $pendingSettings ${if (pendingSettings == 1) "change waits" else "changes wait"} for your PC's Wi-Fi" else "Budgets, colours, names and alerts sync with ${pairing?.name}") else null,
                                                providers = snapshot?.providers.orEmpty(),
                                                setLevels = { w, c -> AlertPrefs.setThresholds(repo.prefs, w, c); syncSoon() },
                                                setQuiet = { on, s, e -> AlertPrefs.setQuiet(repo.prefs, on, s, e); syncSoon() },
                                                move = { id, delta ->
                                                    val ids = snapshot?.providers.orEmpty().map { it.id }.toMutableList(); val i = ids.indexOf(id); val j = i + delta
                                                    if (i >= 0 && j in ids.indices) { ids.add(j, ids.removeAt(i)); SharedSettings.setOrder(repo.prefs, ids); runCatching { updateWidgets(context) }; syncSoon() }
                                                }),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                DayOverlay(day, historyProviders, shownNotes, onNote) { dayOpen = null }
            }
            }
            }
        }
        // Content fades out under the status bar instead of colliding with the clock and icons.
        val fadeTo = topInset + 22.dp
        val solid = (topInset / fadeTo).coerceIn(0f, 1f)
        Box(Modifier.fillMaxWidth().height(fadeTo).align(Alignment.TopCenter)
            .background(Brush.verticalGradient(0f to t.background.copy(alpha = .97f), solid to t.background.copy(alpha = .9f), 1f to Color.Transparent)))
        // Floating glass chrome: it samples the whole screen, so content refracts through it as it scrolls beneath.
        CompositionLocalProvider(LocalGlass provides floatingGlass) {
            AnimatedVisibility(open == null && page == 0 && (providers.isNotEmpty() || connected), Modifier.align(Alignment.TopCenter), enter = fadeIn(), exit = fadeOut()) {
                TopBar(pairing, demo, error, offline, source, repo.lastSync(), now, busy, connected, { refresh() }, if (floating) providers else emptyList(), remaining, reduced, { demo = false }) { id -> selected = id; detail = id }
            }
            AnimatedVisibility(open == null && day == null, Modifier.align(Alignment.BottomCenter),
                enter = if (reduced) fadeIn(snap()) else slideInVertically(spring(dampingRatio = .8f, stiffness = 400f)) { it } + fadeIn(),
                exit = if (reduced) fadeOut(snap()) else slideOutVertically(tween(200)) { it } + fadeOut(tween(160))) {
                GlassTabBar(TABS, page, { page = it }, reduced, Modifier.navigationBarsPadding().padding(horizontal = 22.dp, vertical = 10.dp).fillMaxWidth())
            }
        }
        if (busy && !refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding().align(Alignment.TopCenter), color = t.accent, trackColor = Color.Transparent)
        WhatsNewSheet(whatsNew, reduced) { whatsNew = false; WhatsNew.markSeen(context) }
        if (scoreSheet) ScoreSheet(UsageScores.of(shown)) { scoreSheet = false }
        if (scanning) ScannerScreen(reduced, { code -> scanning = false; importPairing { code } }) { scanning = false }
    }
    }
    if (pasting) PasteDialog(busy, pairError, { pasting = false; pairError = "" }) { code -> importPairing { code } }
    confirm?.let { p ->
        AlertDialog(onDismissRequest = { confirm = null }, icon = { Icon(Icons.Outlined.Link, null) }, title = { Text("Pair with ${p.name}?") },
            text = { Text("This phone will read usage from ${p.name} at ${p.host()}" + (if (p.relay != null) ", with end-to-end encrypted internet sync" else "") + "." + (if (pairing != null) " It replaces the current pairing with ${pairing?.name}." else "")) },
            confirmButton = { TextButton(onClick = { val text = confirmText; confirm = null; importPairing { text } }) { Text("Pair") } },
            dismissButton = { TextButton(onClick = { confirm = null }) { Text("Cancel") } })
    }
    if (deleteHistory) AlertDialog(onDismissRequest = { deleteHistory = false }, icon = { Icon(Icons.Outlined.DeleteOutline, null) }, title = { Text("Delete history on this phone?") },
        text = { Text("This removes the ${storedStats?.days ?: 0} days and your notes saved on this phone. Your PC keeps its own history and notes, and they come back the next time it syncs.") },
        confirmButton = { TextButton(onClick = { deleteHistory = false; scope.launch { withContext(Dispatchers.IO) { runCatching { LocalHistory.open(context) { it.clear() } } }; dataNote = "History on this phone deleted"; storedVersion++ } }) { Text("Delete", color = Color(Palette.DANGER)) } },
        dismissButton = { TextButton(onClick = { deleteHistory = false }) { Text("Cancel") } })
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("Disconnect this phone?") }, text = { Text("This clears its pairing and latest readings. History saved on this phone stays until you delete it in Settings → Data, and your Windows history is untouched.") }, confirmButton = { TextButton(onClick = { scope.launch { repo.disconnect(); afterSync(); disconnect = false } }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Cancel") } })
}

/**
 * The floating glass capsule at the top of Overview: your PC and how fresh the reading is, and refresh. Once the dock
 * scrolls away, the capsule springs open into a compact dock of rings.
 */
@Composable private fun TopBar(pairing: Pairing?, demo: Boolean, error: String, offline: Boolean, source: Source, lastSync: Long, now: Long, busy: Boolean, connected: Boolean,
                               refresh: () -> Unit, dock: List<Provider>, remaining: Boolean, reduced: Boolean, exitPreview: () -> Unit, open: (String) -> Unit) {
    val t = LocalTokens.current
    val live = !demo && pairing != null && !offline && error.isBlank()
    val dot = when { demo -> Color(0xFF8FB6FF); pairing == null || error.isNotBlank() -> Color(Palette.WARNING); offline -> t.faint; else -> Color(0xFF2EE0A8) }
    val pulse = if (live && !reduced) rememberInfiniteTransition(label = "Live").animateFloat(.35f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Dot").value else 1f
    val snap = remember { Animatable(1f) }
    LaunchedEffect(dock.isNotEmpty()) { if (!reduced) { snap.snapTo(.9f); snap.animateTo(1f, spring(dampingRatio = .45f, stiffness = 420f)) } }
    Box(Modifier.statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth()) {
        Row(Modifier.align(Alignment.CenterStart).padding(end = 56.dp).graphicsLayer { scaleX = snap.value; scaleY = snap.value; transformOrigin = TransformOrigin(0f, .5f) }
            .glass(GlassShapes.capsule, GlassLevel.Bar).animateContentSize(if (reduced) snap() else spring(dampingRatio = .62f, stiffness = 380f))
            .padding(horizontal = if (dock.isEmpty()) 16.dp else 8.dp, vertical = if (dock.isEmpty()) 12.dp else 4.dp), verticalAlignment = Alignment.CenterVertically) {
            if (dock.isEmpty()) {
                Box(Modifier.size(8.dp).clip(CircleShape).background(dot.copy(alpha = pulse))); Spacer(Modifier.width(10.dp))
                Text(when { demo -> "Sample data"; pairing == null -> "Not paired"; else -> pairing.name }, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text, maxLines = 1)
                if (demo) Text("Exit preview", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.accent, modifier = Modifier.padding(start = 12.dp).clip(CircleShape).clickable(role = Role.Button) { exitPreview() }.padding(horizontal = 4.dp, vertical = 2.dp))
                if (!demo && pairing != null) Text("  " + when { offline -> "offline · ${ClockText.age(lastSync, now)}"; source == Source.Internet -> "internet · ${ClockText.age(lastSync, now)}"; else -> ClockText.age(lastSync, now) }, fontSize = 12.5.sp, color = t.muted, maxLines = 1)
            } else CompositionLocalProvider(LocalRingShare provides null) {
                Row(Modifier.horizontalScroll(rememberScrollState())) { dock.forEach { p -> DockCell(p, remaining, now, reduced, false, compact = true) { open(p.id) } } }
            }
        }
        GlassIconButton(Icons.Outlined.Refresh, "Refresh usage", refresh, Modifier.align(Alignment.CenterEnd), enabled = connected && !busy, tint = if (connected) t.accent else t.muted)
    }
}

@Composable private fun PairCard(busy: Boolean, paired: Boolean, scan: () -> Unit, paste: () -> Unit, import: () -> Unit, preview: () -> Unit) {
    val t = LocalTokens.current
    Panel(Modifier.fillMaxWidth().padding(top = 24.dp)) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(84.dp).glass(GlassShapes.capsule, GlassLevel.Control, t.accent), contentAlignment = Alignment.Center) { Icon(if (paired) Icons.Outlined.HourglassTop else Icons.Outlined.QrCodeScanner, null, tint = t.accent, modifier = Modifier.size(40.dp)) }
        Text(if (paired) "Waiting for a reading" else "Pair with your PC", fontSize = 24.sp, fontWeight = FontWeight.Light, color = t.text, modifier = Modifier.padding(top = 18.dp))
        Text(if (paired) "It appears after your PC's next reading." else "UsageNotch on Windows → Settings → Phone", color = t.muted, fontSize = 13.5.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, bottom = 18.dp))
        if (!paired) {
            GlassButton(scan, Modifier.fillMaxWidth(), prominent = true, enabled = !busy) { Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Scan QR code", fontWeight = FontWeight.SemiBold) }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(paste, Modifier.weight(1f), enabled = !busy) { Text("Paste code") }
                GlassButton(import, Modifier.weight(1f), enabled = !busy) { Text("Import file") }
            }
            TextButton(onClick = preview, modifier = Modifier.padding(top = 6.dp)) { Text("Explore with sample data", color = t.muted) }
        }
    } }
}

/** Settings as iOS-style grouped glass lists. */
private fun androidx.compose.foundation.lazy.LazyListScope.settingsPage(
    pairing: Pairing?, busy: Boolean, scan: () -> Unit, paste: () -> Unit, import: () -> Unit, disconnect: () -> Unit,
    appearance: String, setAppearance: (String) -> Unit, wallpaper: Boolean, setWallpaper: (Boolean) -> Unit,
    liveUpdate: Boolean, usageAlerts: Boolean, resetAlerts: Boolean, recap: Boolean, setNotify: (String, Boolean) -> Unit,
    providers: List<Provider>, followed: String?, follow: (String) -> Unit,
    remaining: Boolean, setRemaining: (Boolean) -> Unit, clock24: Boolean, setClock24: (Boolean) -> Unit, reduced: Boolean, setReduced: (Boolean) -> Unit,
    checkUpdates: Boolean, setCheckUpdates: (Boolean) -> Unit, reducedMotion: Boolean, openUrl: (String) -> Unit,
    style: UiStyle, setStyle: (UiStyle) -> Unit, autoUpdate: Boolean, setAutoUpdate: (Boolean) -> Unit, update: String?,
    extras: SettingsExtras, data: DataExtras,
) {
    item { Group {
        Column(Modifier.padding(vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            val t = LocalTokens.current
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.LaptopWindows, null, tint = t.accent); Spacer(Modifier.width(10.dp))
                Text(pairing?.name ?: "Pair your Windows PC", fontSize = 18.sp, fontWeight = FontWeight.Medium, color = t.text, modifier = Modifier.weight(1f))
                if (pairing != null) Icon(if (pairing.relay != null) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff, if (pairing.relay != null) "Internet sync on" else "Internet sync off", tint = if (pairing.relay != null) t.accent else t.faint, modifier = Modifier.size(18.dp))
            }
            if (pairing != null) Text(if (pairing.relay != null) "Internet sync on · end-to-end encrypted" else "Internet sync off · turn it on in Settings → Phone on your PC", fontSize = 12.sp, color = t.muted)
            extras.syncLine?.let { Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Outlined.Sync, null, tint = t.accent, modifier = Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(it, fontSize = 12.sp, color = t.muted) } }
            GlassButton(scan, Modifier.fillMaxWidth(), prominent = true, enabled = !busy) { Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(if (pairing == null) "Scan QR code" else "Scan a new code", fontWeight = FontWeight.SemiBold) }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                GlassButton(paste, Modifier.weight(1f), enabled = !busy) { Text("Paste code") }
                GlassButton(import, Modifier.weight(1f), enabled = !busy) { Text("Import file") }
            }
            if (pairing != null) TextButton(onClick = disconnect) { Text("Disconnect this phone", color = Color(Palette.DANGER)) }
        }
    } }
    item { SectionLabel("Appearance") }
    item { Group {
        Column(Modifier.padding(vertical = 12.dp)) {
            Text("Style", fontSize = 13.sp, color = LocalTokens.current.muted, modifier = Modifier.padding(bottom = 8.dp))
            GlassSegmented(listOf("Liquid glass", "Classic"), if (style == UiStyle.Classic) 1 else 0, { setStyle(if (it == 1) UiStyle.Classic else UiStyle.Glass) }, Modifier.fillMaxWidth(), reducedMotion,
                listOf("Liquid glass style", "Classic style"))
            Text(if (style == UiStyle.Classic) "Solid cards on a grouped background, frosted bars and no refraction." else "Surfaces sample, bend and blur what's behind them.", fontSize = 12.sp, color = LocalTokens.current.faint, modifier = Modifier.padding(top = 6.dp, bottom = 14.dp))
            Text("Theme", fontSize = 13.sp, color = LocalTokens.current.muted, modifier = Modifier.padding(bottom = 8.dp))
            GlassSegmented(listOf("System", "Light", "Dark"), listOf("system", "light", "dark").indexOf(appearance).coerceAtLeast(0), { setAppearance(listOf("system", "light", "dark")[it]) }, Modifier.fillMaxWidth(), reducedMotion,
                listOf("System theme", "Light theme", "Dark theme"))
            if (Build.VERSION.SDK_INT >= 31) SettingToggle("Wallpaper colors", null, wallpaper, setWallpaper)
        }
    } }
    item { SectionLabel("Notifications") }
    item { Group {
        SettingToggle("Live countdown", if (Build.VERSION.SDK_INT >= 36) "Live Update with a countdown to the reset" else "Countdown to the reset, also on the lock screen", liveUpdate) { setNotify("live", it) }
        val choices = providers.filter { it.sessionWindow() != null }
        if (liveUpdate && choices.size > 1) Row(Modifier.padding(bottom = 12.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val current = focusProvider(choices, followed)?.id
            choices.forEach { p -> GlassChip(p.name, p.id == current, leading = { Logo(p.id, Modifier.size(15.dp)) }) { follow(p.id) } }
        }
        Divider()
        SettingToggle("Usage alerts", "${extras.warn}%, ${extras.critical}%, budgets and pace", usageAlerts) { setNotify("usage", it) }
        if (usageAlerts) AlertLevels(extras.warn, extras.critical, extras.setLevels)
        Divider()
        SettingToggle("Reset alerts", "When a limit renews", resetAlerts) { setNotify("reset", it) }
        Divider()
        SettingToggle("Before a limit renews", "10 minutes ahead, for limits you've used half of", extras.preReset) { setNotify("pre", it) }
        Divider()
        SettingToggle("Good moment to start", "When starting now renews mid-way through your busy hours", extras.goodMoment) { setNotify("good", it) }
        Divider()
        SettingToggle("Weekly recap", "Sunday evening", recap) { setNotify("recap", it) }
        Divider()
        SettingToggle("Quiet hours", if (extras.quietOn) "No alert sounds or pop-ups ${Budget.clock(extras.quietStart * 60, false)}–${Budget.clock(extras.quietEnd * 60, false)}" else "Silence alerts overnight", extras.quietOn) { extras.setQuiet(it, extras.quietStart, extras.quietEnd) }
        if (extras.quietOn) QuietHours(extras.quietStart, extras.quietEnd) { s, e -> extras.setQuiet(true, s, e) }
    } }
    if (extras.providers.size > 1) {
        item { SectionLabel("Provider order") }
        item { Group { ProviderOrder(extras.providers, extras.move) } }
    }
    item { SectionLabel("Display") }
    item { Group {
        SettingToggle("Show remaining", null, remaining, setRemaining)
        Divider()
        SettingToggle("24-hour clock", null, clock24, setClock24)
        Divider()
        SettingToggle("Reduce motion", null, reduced, setReduced)
    } }
    item { SectionLabel("Data") }
    item { Group { DataRow(data) } }
    item { SectionLabel("Updates") }
    item { Group {
        SettingToggle("Check for updates", null, checkUpdates, setCheckUpdates)
        if (checkUpdates) { Divider(); SettingToggle("Install updates automatically", "Downloads on Wi-Fi, checks the published checksum and signature, then installs", autoUpdate, setAutoUpdate) }
        Divider()
        UpdateRow(update)
        Divider()
        Row(Modifier.fillMaxWidth().clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = data.whatsNew).padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(WhatsNew.TITLE, fontSize = 15.5.sp, color = LocalTokens.current.text, modifier = Modifier.weight(1f))
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = LocalTokens.current.faint)
        }
    } }
    item { Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("UsageNotch ${BuildConfig.VERSION_NAME}", color = LocalTokens.current.faint, fontSize = 12.sp)
        TextButton(onClick = { openUrl(Updates.RELEASES) }) { Text("Downloads", color = LocalTokens.current.muted) }
    } }
}
/** The Update button with download progress, or the current version and a manual check. */
@Composable private fun UpdateRow(update: String?) {
    val t = LocalTokens.current; val context = LocalContext.current; val scope = rememberCoroutineScope()
    Column(Modifier.padding(vertical = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (update != null) "UsageNotch $update is available" else "UsageNotch ${BuildConfig.VERSION_NAME}", fontSize = 15.5.sp, color = t.text)
                Text(Updates.message.ifBlank { if (update != null) "You have ${BuildConfig.VERSION_NAME}" else "Tap Check to look for a new version" }, fontSize = 12.sp, color = t.faint)
            }
            GlassButton({ Updates.updateNow(context, scope) }, prominent = update != null, enabled = Updates.progress == null, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) {
                Text(if (update != null) "Update" else "Check", fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            }
        }
        Updates.progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(top = 10.dp).clip(CircleShape), color = t.accent, trackColor = t.track) }
        if (update != null && !Updates.canInstall(context)) TextButton(onClick = { runCatching { context.startActivity(Updates.allowInstallsIntent(context)) } }, contentPadding = PaddingValues(0.dp)) {
            Text("Allow UsageNotch to install updates", color = t.accent, fontSize = 13.sp)
        }
    }
}

/** The Overview banner for a new version: install it here, with progress, or read about it. */
@Composable private fun UpdateNotice(version: String, openUrl: (String) -> Unit) {
    val t = LocalTokens.current; val context = LocalContext.current; val scope = rememberCoroutineScope()
    Panel(Modifier.fillMaxWidth(), tint = t.accent.copy(alpha = .4f)) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.SystemUpdate, null, tint = t.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("UsageNotch $version is available", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                Text(Updates.message.ifBlank { "Checked against the published release before it installs." }, fontSize = 12.sp, color = t.muted)
            }
        }
        Updates.progress?.let { LinearProgressIndicator({ it }, Modifier.fillMaxWidth().padding(top = 12.dp).clip(CircleShape), color = t.accent, trackColor = t.track) }
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton({ Updates.updateNow(context, scope) }, Modifier.weight(1f), prominent = true, enabled = Updates.progress == null) { Text(if (Updates.progress != null) "Updating…" else "Update to $version", fontWeight = FontWeight.SemiBold) }
            GlassButton({ openUrl(Updates.RELEASES) }) { Text("What's new") }
        }
    } }
}

/** Settings → Data: what's saved on this phone, and exporting or deleting it. */
class DataExtras(val stats: StoredStats?, val note: String, val export: () -> Unit, val delete: () -> Unit, val whatsNew: () -> Unit)

@Composable private fun DataRow(data: DataExtras) {
    val t = LocalTokens.current; val stats = data.stats
    Column(Modifier.padding(vertical = 14.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Storage, null, tint = t.accent, modifier = Modifier.size(20.dp)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text("History on this phone", fontSize = 15.5.sp, color = t.text)
                val since = stats?.since?.let { runCatching { java.time.LocalDate.parse(it).format(java.time.format.DateTimeFormatter.ofPattern("MMM d, yyyy", java.util.Locale.ENGLISH)) }.getOrDefault(it) }
                Text(when {
                    stats == null -> "Checking…"
                    stats.days == 0 -> "Nothing saved yet. Daily totals are saved each time your PC syncs."
                    else -> "${stats.days} day${if (stats.days == 1) "" else "s"} since $since · kept until you delete them"
                }, fontSize = 12.sp, lineHeight = 16.sp, color = t.faint)
            }
        }
        if (data.note.isNotBlank()) Text(data.note, fontSize = 12.sp, color = t.accent, modifier = Modifier.padding(top = 8.dp))
        Row(Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            GlassButton(data.export, Modifier.weight(1f), enabled = (stats?.days ?: 0) > 0) { Icon(Icons.Outlined.FileDownload, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Export CSV") }
            GlassButton(data.delete, Modifier.weight(1f), enabled = (stats?.days ?: 0) > 0) { Text("Delete…", color = if ((stats?.days ?: 0) > 0) Color(Palette.DANGER) else t.faint) }
        }
    }
}

/** Settings that arrived with protocol 2: reminders, alert levels, quiet hours and the provider order (all but reminders sync with the PC). */
class SettingsExtras(
    val preReset: Boolean, val goodMoment: Boolean, val warn: Int, val critical: Int, val quietOn: Boolean, val quietStart: Int, val quietEnd: Int,
    val syncLine: String?, val providers: List<Provider>,
    val setLevels: (Int, Int) -> Unit, val setQuiet: (Boolean, Int, Int) -> Unit, val move: (String, Int) -> Unit,
)

/** Warning and urgent levels for usage alerts, in steps of five. Saved when the slider is released. */
@Composable private fun AlertLevels(warn: Int, critical: Int, set: (Int, Int) -> Unit) {
    val t = LocalTokens.current
    var w by remember(warn) { mutableFloatStateOf(warn.toFloat()) }; var c by remember(critical) { mutableFloatStateOf(critical.toFloat()) }
    val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = t.accent, inactiveTrackColor = t.track, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
    Column(Modifier.padding(bottom = 10.dp)) {
        Row { Text("Warning", fontSize = 13.sp, color = t.muted, modifier = Modifier.weight(1f)); Text("${w.toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(Palette.readable(Palette.usage(w / 100.0), t.card.toArgb()))) }
        Slider(w, { w = (it / 5).toInt() * 5f; if (c < w) c = w }, valueRange = 50f..95f, steps = 8, onValueChangeFinished = { set(w.toInt(), c.toInt()) }, colors = colors, modifier = Modifier.semantics { contentDescription = "Warning level" })
        Row { Text("Urgent", fontSize = 13.sp, color = t.muted, modifier = Modifier.weight(1f)); Text("${c.toInt()}%", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(Palette.readable(Palette.usage(c / 100.0), t.card.toArgb()))) }
        Slider(c, { c = maxOf(w, (it / 5).toInt() * 5f) }, valueRange = 60f..100f, steps = 7, onValueChangeFinished = { set(w.toInt(), c.toInt()) }, colors = colors, modifier = Modifier.semantics { contentDescription = "Urgent level" })
    }
}

/** Start and end hours for quiet hours; equal hours mean all day, as on the PC. */
@Composable private fun QuietHours(start: Int, end: Int, set: (Int, Int) -> Unit) {
    val t = LocalTokens.current
    var s by remember(start) { mutableFloatStateOf(start.toFloat()) }; var e by remember(end) { mutableFloatStateOf(end.toFloat()) }
    val colors = SliderDefaults.colors(thumbColor = Color.White, activeTrackColor = t.accent, inactiveTrackColor = t.track, activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent)
    Column(Modifier.padding(bottom = 10.dp)) {
        Row { Text("From", fontSize = 13.sp, color = t.muted, modifier = Modifier.weight(1f)); Text(Budget.clock(s.toInt() * 60, false), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.text) }
        Slider(s, { s = it.roundToIntCompat().toFloat() }, valueRange = 0f..23f, steps = 22, onValueChangeFinished = { set(s.toInt(), e.toInt()) }, colors = colors, modifier = Modifier.semantics { contentDescription = "Quiet hours start" })
        Row { Text("Until", fontSize = 13.sp, color = t.muted, modifier = Modifier.weight(1f)); Text(Budget.clock(e.toInt() * 60, false), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = t.text) }
        Slider(e, { e = it.roundToIntCompat().toFloat() }, valueRange = 0f..23f, steps = 22, onValueChangeFinished = { set(s.toInt(), e.toInt()) }, colors = colors, modifier = Modifier.semantics { contentDescription = "Quiet hours end" })
    }
}
private fun Float.roundToIntCompat() = kotlin.math.round(this).toInt()

/** The order of your providers everywhere: dock, cards, widgets. Syncs with the PC's dock. */
@Composable private fun ProviderOrder(providers: List<Provider>, move: (String, Int) -> Unit) {
    val t = LocalTokens.current
    providers.forEachIndexed { i, p ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp).animateContentSize(), verticalAlignment = Alignment.CenterVertically) {
            Logo(p.id, Modifier.size(20.dp)); Spacer(Modifier.width(12.dp))
            Text(p.name, fontSize = 15.sp, color = t.text, modifier = Modifier.weight(1f))
            IconButton(onClick = { move(p.id, -1) }, enabled = i > 0) { Icon(Icons.Outlined.KeyboardArrowUp, "Move ${p.name} up", tint = if (i > 0) t.muted else t.hairline) }
            IconButton(onClick = { move(p.id, 1) }, enabled = i < providers.lastIndex) { Icon(Icons.Outlined.KeyboardArrowDown, "Move ${p.name} down", tint = if (i < providers.lastIndex) t.muted else t.hairline) }
        }
        if (i < providers.lastIndex) Divider()
    }
}

/** Names for providers that appear only in incidents. */
internal object PhoneNames { fun of(id: String) = when (providerKind(id)) { "claude" -> "Claude"; "codex", "openai-api" -> "OpenAI"; "gemini" -> "Gemini"; "cursor" -> "Cursor"; else -> id.replaceFirstChar { it.uppercase() } } }

@Composable private fun Group(content: @Composable ColumnScope.() -> Unit) { Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(horizontal = 18.dp, vertical = 2.dp), content = content) } }
@Composable private fun Divider() { HorizontalDivider(color = LocalTokens.current.hairline) }

@Composable private fun PasteDialog(busy: Boolean, problem: String, dismiss: () -> Unit, pair: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }; val t = LocalTokens.current
    AlertDialog(onDismissRequest = dismiss, title = { Text("Paste pairing code") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("From Copy pairing code in UsageNotch → Settings → Phone on your PC.", fontSize = 13.sp, color = t.muted)
            OutlinedTextField(code, { code = it }, placeholder = { Text("UN2.…") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
            if (problem.isNotBlank()) Text(problem, fontSize = 12.sp, color = Color(Palette.readable(Palette.WARNING, t.card.toArgb())))
        } },
        confirmButton = { TextButton(onClick = { pair(code) }, enabled = code.isNotBlank() && !busy) { Text("Pair") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
/** Real renders of the widget at common sizes, using the same renderer and background as the home screen. */
@Composable private fun WidgetPreviews(snapshot: Snapshot, now: Long, dark: Boolean, remaining: Boolean, clock24: Boolean, offline: Boolean, budgets: List<Budget>) {
    val context = LocalContext.current; val density = LocalDensity.current.density; val t = LocalTokens.current
    val minute = now / 60_000
    @Composable fun Preview(label: String, w: Float, h: Float, focus: Boolean) {
        val bitmap = remember(snapshot, dark, remaining, clock24, minute, w, h, offline, budgets) {
            WidgetRenderer.render(context, WidgetRenderer.Frame(w, h, density), WidgetRenderer.Input(snapshot, true, if (offline) "PC offline · saved readings" else "PC sync just now", remaining, clock24, now, dark, focus, null, offline, budgets = budgets)).bitmap.asImageBitmap()
        }
        Column {
            Box(Modifier.size(w.dp, h.dp).clip(com.kyant.shapes.RoundedRectangle(24.dp)).background(Color(if (dark) WidgetRenderer.GLASS_DARK else WidgetRenderer.GLASS_LIGHT))) {
                Image(bitmap, "$label widget preview", Modifier.fillMaxSize())
            }
            Text(label, fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(top = 6.dp, start = 4.dp))
        }
    }
    BoxWithConstraints {
        val full = (maxWidth.value - 4).coerceAtMost(360f)
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Preview("Full · 4×3", full, 250f, false)
            Preview("Dock row · 4×1", full, 96f, false)
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Preview("Focus · 2×2", 170f, 170f, true)
                Preview("1×1", 80f, 80f, true)
            }
        }
    }
}
private fun pinWidget(context: Context, focus: Boolean, tokens: Boolean = false) {
    val manager = AppWidgetManager.getInstance(context)
    val cls = when { tokens -> TokensWidget::class.java; focus -> FocusWidget::class.java; else -> UsageWidget::class.java }
    val pinned = runCatching { manager != null && manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, cls), null, null) }.getOrDefault(false)
    if (!pinned) android.widget.Toast.makeText(context, "Long-press Home → Widgets → UsageNotch", android.widget.Toast.LENGTH_LONG).show()
}
internal fun demoSnapshot(): Snapshot {
    val now = System.currentTimeMillis()
    fun window(id: String, label: String, used: Double, reset: Long, detail: String? = null, forecast: Forecast? = null) = UsageWindow(id, label, used, now, reset, (0..48).map { Reading(now - (48 - it) * 1_200_000L, used * (.25 + .75 * it / 48), "preview") }, detail, forecast = forecast)
    return Snapshot(now, listOf(
        Provider("claude", "Claude", "Ok", listOf(window("five_hour", "Current session", .27, now + 7_845_000, forecast = Forecast("Estimated 61.4% used at reset", 15.6, 61.4, null, "Consistent pace")), window("seven_day", "All models", .41, now + 231_845_000), window("seven_day_sonnet", "Sonnet weekly", .12, now + 231_845_000)),
            account = "Sample account", updatedAt = now, manageUrl = "https://claude.ai/settings/usage", session = "five_hour", weekly = "seven_day", plan = "Max 5×",
            breakdown = listOf(Share("Claude Code", 82.0), Share("Claude", 18.0)),
            plans = listOf(PlanChange(now - 160 * 86_400_000L, "Pro"), PlanChange(now - 41 * 86_400_000L, "Max 5×")),
            extras = listOf(Extra("extra_usage", "Usage credits", "Pay-as-you-go usage after plan limits", null, 3.5, null, "USD")),
            history = listOf(demoInsights(demoHistory("five_hour", "Current session", 1.0, 3), now, 5 * 3_600_000L, 3).withDemoAllTime(now, 61.4, 158, 171, 23).withDemoUnusual(),
                demoInsights(demoHistory("seven_day", "All models", .16, 5), now, 7 * 86_400_000L, 5).withDemoAllTime(now, 9.8, 160, 171, 2))),
        Provider("codex", "Codex", "Ok", listOf(window("codex-primary", "5-hour limit", .16, now + 11_400_000), window("codex-secondary", "Weekly limit", .49, now + 401_840_000)), updatedAt = now, manageUrl = "https://chatgpt.com/codex/settings/usage", session = "codex-primary", weekly = "codex-secondary",
            history = listOf(demoHistory("codex-primary", "5-hour limit", .7, 11).withDemoAllTime(now, 37.2, 120, 131, 9)), plan = "Plus"),
        Provider("gemini", "Gemini", "Ok", listOf(window("gemini-2.5-pro", "Gemini 2.5 Pro", .72, now + 50_000_000)), updatedAt = now),
        Provider("codex:work", "Codex · Work", "Ok", listOf(window("codex-primary", "5-hour limit", .58, now + 4_100_000, forecast = Forecast("Estimated 92.0% used at reset", 22.0, 92.0, null, "Consistent pace")), window("codex-secondary", "Weekly limit", .33, now + 301_840_000), UsageWindow("codex-monthly", "Monthly limit", .2, now, now + 20 * 86_400_000L, emptyList(), "Credits shared across Codex and other ChatGPT agents", 30.0, 150.0, "credits")),
            updatedAt = now, session = "codex-primary", weekly = "codex-secondary", kind = "codex", accountLabel = "Work", plan = "Edu",
            extras = listOf(Extra("codex-reset-credits", "Free limit reset", "Full reset (Weekly + 5 hr) · use by " + java.time.LocalDate.now().plusDays(6).format(java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)) + " · redeem it in Codex", null, null, null, null))),
    ), "sample", protocol = 2, features = Snapshot.FEATURES.split(',').toSet(),
        incidents = listOf(Incident("claude", "Elevated errors on Claude Opus", "minor", "monitoring", "https://status.claude.com", now - 1_800_000)),
        tokens = demoTokens(now)).let { it.copy(report = demoReport(it.providers), score = demoScore(it.providers)) }
}

/** A sample combined score: each provider's weekly limit (main limit without one), and last week's. */
internal fun demoScore(providers: List<Provider>): UsageScore? = UsageScores.local(providers)?.let { it.copy(previous = (it.value - 8).coerceAtLeast(0), onPhone = false) }

/** Two sample unusual days: the busiest recorded days, as about twice the usual for their weekday. */
private fun History.withDemoUnusual(): History {
    // The busiest day of this week and of the three before, so both the 7 and 30 day charts show one.
    val busy = listOfNotNull(calendar.takeLast(7).filter { it.used != null }.maxByOrNull { it.used!! }, calendar.takeLast(30).dropLast(7).filter { it.used != null }.maxByOrNull { it.used!! })
    return copy(unusual = busy.map { UnusualDay(it.date, 2.1, it.used!! / 2.1) })
}

/** Sample all-time totals: the busiest saved day and figures in the PC's units (limits). */
private fun History.withDemoAllTime(now: Long, used: Double, active: Int, recorded: Int, hits: Int): History {
    val best = calendar.filter { it.used != null }.maxByOrNull { it.used!! }
    return copy(allTime = AllTime(now - recorded * 86_400_000L, used, active, recorded, hits, best?.date, best?.used ?: 0.0))
}

/** A sample "This week" worked out from the sample history the way the PC does it, so its numbers match History. */
internal fun demoReport(providers: List<Provider>): WeekReport {
    val today = java.time.LocalDate.now(); val from = today.minusDays(6); val fmt = java.time.format.DateTimeFormatter.ofPattern("MMM d", java.util.Locale.ENGLISH)
    val rows = providers.filter { ':' !in it.id }.mapNotNull { p ->
        val h = p.history.firstOrNull { it.window == p.sessionWindow()?.id } ?: return@mapNotNull null
        val week = h.range(7); val before = h.range(14).take(7)
        val used = week.mapNotNull { it.used }.takeIf { it.isNotEmpty() }?.sum(); val previous = before.mapNotNull { it.used }.takeIf { it.isNotEmpty() }?.sum()
        val busiest = week.filter { (it.used ?: 0.0) > .0001 }.maxByOrNull { it.used!! }
        ReportRow(p.id, p.name, h.window, h.label, used, previous, if (used != null && previous != null && previous >= .05) (used - previous) / previous else null,
            week.count { (it.used ?: 0.0) > .0001 }, h.hits.count { ClockText.localDate(it.at) >= from }, busiest?.date, busiest?.used, h.streak)
    }
    val lines = mutableListOf<ReportLine>()
    rows.forEach { r -> r.change?.let { c -> lines += ReportLine("change", r.id, when {
        c >= .1 -> "You used ${r.name} ${kotlin.math.round(c * 100).toInt()}% more than the week before."
        c <= -.1 -> "You used ${r.name} ${kotlin.math.round(-c * 100).toInt()}% less than the week before."
        else -> "Your ${r.name} use was steady, within 10% of the week before."
    }) } }
    rows.filter { it.hits > 0 }.forEach { r -> lines += ReportLine("hits", r.id, "You reached the ${r.name} session limit ${if (r.hits == 1) "once" else if (r.hits == 2) "twice" else "${r.hits} times"} this week.") }
    rows.filter { it.busiestUsed != null }.maxByOrNull { it.busiestUsed!! }?.let { r ->
        val day = java.time.LocalDate.parse(r.busiestDay).dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, java.util.Locale.ENGLISH)
        lines += ReportLine("busiest", r.id, if (r.busiestUsed!! >= LimitText.MULTIPLE) "$day was your busiest day, with ${LimitText.of(r.busiestUsed).removeSuffix("×")} full ${r.name} session limits."
            else "$day was your busiest day, using ${kotlin.math.round(r.busiestUsed * 100).toInt()}% of a ${r.name} session limit.")
    }
    lines += ReportLine("hours", "claude", "You're usually busiest on Tuesdays around 2 PM.")
    lines += ReportLine("tokens", "claude", "Claude Code used 48.2M tokens this week (about $41.90 at API prices).")
    val changes = rows.mapNotNull { it.change }
    val headline = if (changes.isEmpty()) "Your week" else changes.average().let { if (it > .1) "A busier week than the last" else if (it < -.1) "A quieter week than the last" else "A steady week" }
    val active = rows.flatMap { p -> providers.first { it.id == p.id }.history.first { it.window == p.window }.range(7).filter { (it.used ?: 0.0) > .0001 }.map { it.date } }.toSet().size
    // Typical week: the median of the earlier weeks in the sample history, as the PC works it out.
    val typed = rows.map { r ->
        val h = providers.first { it.id == r.id }.history.first { it.window == r.window }
        val weeks = (1..8).mapNotNull { k -> h.range(7 * (k + 1)).take(7).mapNotNull { it.used }.takeIf { it.size >= 3 }?.sum() }
        val typical = if (weeks.size >= 3) weeks.sorted()[weeks.size / 2] else null
        r.copy(typical = typical, typicalChange = typical?.takeIf { it >= .05 }?.let { t -> r.used?.let { (it - t) / t } })
    }
    // As on Windows 2.9: the typical-week comparison joins the change sentence; on its own only without a week before.
    fun typicalText(c: Double) = if (kotlin.math.abs(c) < .1) "close to your typical week" else "${kotlin.math.round(kotlin.math.abs(c) * 100).toInt()}% ${if (c > 0) "above" else "below"} your typical week"
    typed.forEach { r ->
        val c = r.typicalChange ?: return@forEach
        val i = lines.indexOfFirst { it.kind == "change" && it.provider == r.id }
        if (i >= 0) lines[i] = lines[i].copy(text = lines[i].text.removeSuffix(".") + " (" + typicalText(c) + ").")
        else lines.add(0, ReportLine("typical", r.id, "Your ${r.name} week was ${typicalText(c)}."))
    }
    return WeekReport(from.toString(), today.toString(), System.currentTimeMillis(), headline, "${from.format(fmt)} – ${today.format(fmt)} · $active active day${if (active == 1) "" else "s"}", typed, lines.take(8),
        coverage = .42, note = "This PC saw 42% of this week, so which day and hour things happened is less certain. Totals are still complete.",
        notes = listOf(DayNote(today.minusDays(2).toString(), "Big refactor")))
}

/** Sample protocol 2 insights for a history: 7 days of readings, finished periods, limits reached, reliability, sessions and events. */
internal fun demoInsights(h: History, now: Long, period: Long, seed: Int): History {
    val random = java.util.Random(seed.toLong())
    val trend = mutableListOf<Reading>(); var at = now - 7 * 86_400_000L; var used = 0.0; var start = at; var periodId = 0
    val unused = mutableListOf<UnusedPeriod>(); val hits = mutableListOf<LimitHit>(); var peak = 0.0
    while (at <= now) {
        if (at - start >= period) { unused += UnusedPeriod(at, peak.coerceAtMost(1.0)); if (peak >= .995) hits += LimitHit(at - 3_000_000, at); start = at; used = 0.0; peak = 0.0; periodId++ }
        val hour = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).hour
        used = (used + if (hour in 9..19) random.nextDouble() * .035 else random.nextDouble() * .004).coerceAtMost(1.0); peak = maxOf(peak, used)
        trend += Reading(at, used, "p$periodId"); at += 25 * 60_000L
    }
    val events = unused.takeLast(4).map { HistoryEvent(it.end, "Confirmed reset", "A lower reading and an advanced reset time confirm a reset between observations.") } +
        listOf(HistoryEvent(now - 30 * 3_600_000L, "Sampling gap", "No observations for 46 minutes. Usage during this time is unknown."))
    val sessions = (1..4).map { i -> val s = now - i * 26 * 3_600_000L; WorkSession(s, s + (2 + i % 3) * 3_600_000L, 9.0 + i * 3.5, 18.0 + i * 4) }
    return h.copy(trend = trend.takeLast(400), unused = unused.takeLast(12), hits = hits.takeLast(5) + LimitHit(now - 50 * 3_600_000L, now - 48 * 3_600_000L),
        reliability = Reliability(unused.size, unused.size - 1, 1, 6.0, unused.size - 1, 1), events = events.sortedBy { it.at }, sessions = sessions)
}

internal fun demoTokens(now: Long): TokenUsage {
    val today = java.time.LocalDate.now()
    fun days(scale: Long, seed: Long): List<TokenDay> { val r = java.util.Random(seed); return (29 downTo 0).map { back -> val d = today.minusDays(back.toLong()); val weekend = d.dayOfWeek.value >= 6
        val total = if (weekend) (scale * r.nextDouble() * .2).toLong() else (scale * (.4 + r.nextDouble() * .6)).toLong(); TokenDay(d.toString(), total, total / 1_000_000.0 * 1.9) } }
    fun totals(total: Long, cost: Double) = TokenTotals(total / 50, total / 40, total * 8 / 10, total / 10, total, cost)
    val claude = days(42_000_000, 3); val codex = days(18_000_000, 5)
    return TokenUsage(now, listOf(
        TokenSource("claude", "Claude Code", totals(claude.last().total, claude.last().cost!!), totals(claude.takeLast(7).sumOf { it.total }, claude.takeLast(7).sumOf { it.cost!! }), claude,
            listOf(TokenShare("UsageNotch-Windows", 96_000_000, 182.0, .46, budget = 100_000_000), TokenShare("UsageNotch-Android", 61_000_000, 115.0, .29, budget = 50_000_000), TokenShare("Arnav-AI-Website", 31_000_000, 58.0, .15), TokenShare("Orbitarium Website", 21_000_000, 40.0, .10)),
            listOf(TokenShare("claude-opus-5-5", 176_000_000, 352.0, .84), TokenShare("claude-haiku-5-5", 33_000_000, 4.5, .16)),
            month = TokenMonth(today.withDayOfMonth(1).toString(), 214_000_000, 412.3),
            plan = PlanPrice("Max 5×", 100.0), perLimit = PerLimit(9_200_000, "session"),
            modelWeeks = (7 downTo 0).map { k -> val r = java.util.Random(k.toLong()); ModelWeek(today.minusDays(6L + 7 * k).toString(),
                listOf("claude-opus-5-5" to (120_000_000L + r.nextInt(80_000_000)) * (8 - k) / 8, "claude-sonnet-5-5" to (60_000_000L - k * 6_000_000L).coerceAtLeast(4_000_000L), "claude-haiku-5-5" to 20_000_000L + r.nextInt(15_000_000))) }),
        TokenSource("codex", "Codex", totals(codex.last().total, codex.last().cost!!), totals(codex.takeLast(7).sumOf { it.total }, codex.takeLast(7).sumOf { it.cost!! }), codex,
            listOf(TokenShare("FLD_ML-main", 52_000_000, null, .62), TokenShare("Orbitarium Website", 32_000_000, null, .38)), listOf(TokenShare("gpt-6.1-sol", 84_000_000, null, 1.0))),
    ))
}

/** Offers the Quick Settings tile. Android 13+ adds it in one tap; older versions add it from the tile editor. */
@Composable private fun QuickTileCard() {
    val context = LocalContext.current; val t = LocalTokens.current
    var result by remember { mutableStateOf<String?>(null) }
    val manual = "Swipe down twice, tap the pencil, then drag UsageNotch into your tiles."
    Panel(Modifier.fillMaxWidth()) { Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.ToggleOn, null, tint = t.accent); Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text("Quick Settings tile", fontSize = 15.sp, color = t.text)
            Text(result ?: if (Build.VERSION.SDK_INT >= 33) "Percentage in your notification shade" else manual, fontSize = 12.sp, color = t.faint, lineHeight = 16.sp)
        }
        if (Build.VERSION.SDK_INT >= 33) GlassButton({
            val bar = context.getSystemService(android.app.StatusBarManager::class.java)
            runCatching {
                bar.requestAddTileService(ComponentName(context, UsageTileService::class.java), "UsageNotch", android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_notch), context.mainExecutor) { code ->
                    result = when (code) {
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Added. Swipe down to see it."
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "It's already in your Quick Settings."
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                        else -> manual
                    }
                }
            }.onFailure { result = manual }
        }, contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)) { Text("Add tile", fontWeight = FontWeight.SemiBold) }
    } }
}

/** Illustrative 30 days for the preview: weekday afternoons busiest, a few days without readings. */
internal fun demoHistory(window: String, label: String, scale: Double, seed: Int): History {
    val today = java.time.LocalDate.now()
    val random = java.util.Random(seed.toLong())
    fun day(back: Int): Day {
        val date = today.minusDays(back.toLong()); val weekend = date.dayOfWeek.value >= 6
        return if (back in setOf(12, 13, 21, 44, 45, 67)) Day(date.toString(), null) else Day(date.toString(), scale * ((if (weekend) .15 else .55) + random.nextDouble() * .45), (if (back == 0) .9 else .55 + random.nextDouble() * .45))
    }
    val calendar = (89 downTo 0).map { day(it) }
    val days = calendar.takeLast(30)
    val heat = (0 until 168).map { i -> val d = i / 24; val h = i % 24; val work = d in 1..5 && h in 9..18
        if (h in 1..6) 0.0 else scale * (if (work) .05 + .06 * kotlin.math.sin((h - 9) / 9.0 * Math.PI) else .012) * (.7 + random.nextDouble() * .6) }
    val observed = (0 until 168).map { i -> if (i % 24 in 2..5) 0 else 3 + random.nextInt(2) }
    return History(window, label, days, heat.mapIndexed { i, v -> if (observed[i] == 0) 0.0 else v }, observed, days.reversed().takeWhile { (it.used ?: 0.0) > 0 }.size, calendar)
}
