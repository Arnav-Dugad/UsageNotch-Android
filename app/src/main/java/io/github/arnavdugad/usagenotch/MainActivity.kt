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
            NotchTheme(appearance, wallpaper) { NotchApp(incoming, { incoming = null }, link, { link = null }, appearance, { appearance = it }, wallpaper) { wallpaper = it } }
        }
        // Surviving the first seconds clears the startup-crash counter used for safe mode.
        window.decorView.postDelayed({ CrashLog.settled(this) }, 8_000)
    }
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
    var crash by remember { mutableStateOf(CrashLog.report(context)) }; var pasting by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Pairing?>(null) }; var confirmText by remember { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var detail by rememberSaveable { mutableStateOf<String?>(null) }; var scanning by rememberSaveable { mutableStateOf(false) }
    var dayOpen by rememberSaveable { mutableStateOf<String?>(null) }
    var liveUpdate by remember { mutableStateOf(LiveUpdate.enabled(context) && ResetAlerts.canNotify(context)) }
    var usageAlerts by remember { mutableStateOf(UsageAlerts.enabled(context) && ResetAlerts.canNotify(context)) }
    var recap by remember { mutableStateOf(WeeklyRecap.enabled(context) && ResetAlerts.canNotify(context)) }
    var followed by remember { mutableStateOf(repo.prefs.getString("liveProvider", null)) }
    var askingFor by remember { mutableStateOf<String?>(null) }
    var savedBudgets by remember { mutableStateOf(Budgets.all(repo.prefs)) }
    var accentVersion by remember { mutableIntStateOf(0) }
    // Budgets and colours chosen while exploring sample data stay in memory and never touch your settings.
    var demoBudgets by remember { mutableStateOf(emptyList<Budget>()) }
    var demoAccents by remember { mutableStateOf(emptyMap<String, Int?>()) }
    val historyState = rememberHistoryState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val pageStates = List(TABS.size) { rememberLazyListState() }
    val listState = pageStates[0]
    fun reload() { snapshot = repo.snapshot(); pairing = repo.pairing(); error = repo.error(); offline = repo.offline(); source = repo.source(); update = Updates.available(context) }
    fun afterSync() { reload(); afterNewData(context) }
    fun refresh() { if (!busy) scope.launch { busy = true; refreshing = true; try { repo.refresh() } catch (_: Exception) { } finally { afterSync(); busy = false; refreshing = false } } }
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
    LaunchedEffect(incoming) { incoming?.let { readUri(it); page = 0; incomingHandled() } }
    // Links can come from any web page, so they are confirmed before replacing a pairing.
    LaunchedEffect(link) { link?.let { text -> runCatching { Pairing.parse(text) }.onSuccess { confirm = it; confirmText = text }.onFailure { pairError = NOT_A_PAIRING }; linkHandled() } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) readUri(uri) }
    fun applyNotify(key: String, on: Boolean) = when (key) {
        "reset" -> { alerts = on; ResetAlerts.setEnabled(context, on) }
        "live" -> { liveUpdate = on; LiveUpdate.setEnabled(context, on) }
        "recap" -> { recap = on; WeeklyRecap.setEnabled(context, on) }
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

    CompositionLocalProvider(LocalAccent provides accentOf) {
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
                            else { if (b == null) Budgets.remove(repo.prefs, openProvider.id, window) else Budgets.set(repo.prefs, b.copy(provider = openProvider.id, window = window)); savedBudgets = Budgets.all(repo.prefs); afterNewData(context) }
                        },
                        onAccent = { color ->
                            if (demo) demoAccents = demoAccents + (providerKind(openProvider.id) to color)
                            else { Accents.set(repo.prefs, openProvider.id, color); accentVersion++; runCatching { updateWidgets(context) } }
                        },
                        onDashboard = openUrl) { detail = null }
                    else CompositionLocalProvider(LocalRingShare provides share) {
                        AnimatedContent(targetState = page, transitionSpec = {
                            if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
                            else (fadeIn(tween(220)) + slideInHorizontally(spring(dampingRatio = .9f, stiffness = 500f)) { (if (targetState > initialState) 1 else -1) * it / 10 }) togetherWith fadeOut(tween(120))
                        }, label = "Page transition", modifier = Modifier.graphicsLayer {
                            val scale = 1f - pageBack * .06f; scaleX = scale; scaleY = scale; alpha = 1f - pageBack * .25f
                        }) { current ->
                            val pullState = rememberPullToRefreshState()
                            val top = topInset + if (current == 0) 68.dp else 16.dp
                            PullToRefreshBox(isRefreshing = refreshing, onRefresh = { if (connected) refresh() }, state = pullState, modifier = Modifier.fillMaxSize(),
                                indicator = { PullToRefreshDefaults.Indicator(state = pullState, isRefreshing = refreshing, modifier = Modifier.align(Alignment.TopCenter).padding(top = top)) }) {
                                LazyColumn(Modifier.fillMaxSize(), state = pageStates[current], contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = top, bottom = 120.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                    crash?.let { report -> item(key = "crash") { Notice("UsageNotch closed unexpectedly", "Share the crash report to help fix it. It has no usage or pairing data.", "Share", "Dismiss", { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share crash report")) }) { CrashLog.clear(context); crash = null } } }
                                    update?.let { version -> item(key = "update") { Notice("UsageNotch $version is available", null, "Download", tone = t.accent, clicked = { openUrl(Updates.RELEASES) }) } }
                                    // In sample mode the top capsule says so and offers Exit; other tabs get a short notice.
                                    if (demo && current != 0) item(key = "demo") { Notice("Sample data", null, "Exit preview", clicked = { demo = false }, tone = Color(0xFF8FB6FF)) }
                                    if (pairError.isNotBlank() && !pasting) item(key = "pairError") { Notice("Pairing didn't finish", pairError, "Dismiss", clicked = { pairError = "" }) }
                                    if (error.isNotBlank() && connected) item(key = "error") { Notice("Connection needs attention", error) }
                                    when (current) {
                                        0 -> {
                                            if (offlineNotice) item(key = "offline") { Notice("PC offline", "Showing saved readings from ${ClockText.age(repo.lastSync(), now)}.", tone = t.muted) }
                                            if (providers.isEmpty()) item(key = "pair") { PairCard(busy, connected, { scan() }, { pasting = true }, { picker.launch(arrayOf("*/*")) }, { demo = true }) }
                                            else {
                                                item(key = "dock") {
                                                    Panel(Modifier.fillMaxWidth()) {
                                                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 8.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                                                            providers.forEach { p -> DockCell(p, remaining, now, reduced, selected == p.id, budget = budgetsFor(p.id).firstOrNull { it.window == p.sessionWindow()?.id }) { selected = p.id; detail = p.id } }
                                                        }
                                                    }
                                                }
                                                providers.forEach { p -> item(key = "p-${p.id}") {
                                                    ProviderCard(p, now, remaining, clock24, reduced, offline && !demo, demo, openUrl, budgets = budgetsFor(p.id), onOpen = { selected = p.id; detail = p.id })
                                                } }
                                            }
                                        }
                                        1 -> historyPage(if (demo) providers else snapshot?.providers.orEmpty(), historyState, clock24, reduced, day) { dayOpen = it.encode() }
                                        2 -> {
                                            item { WidgetPreviews(shown ?: demoSnapshot(), now, t.dark, remaining, clock24, offline && !demo, budgets) }
                                            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                                GlassButton({ pinWidget(context, false) }, Modifier.weight(1f), prominent = true) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Overview", fontWeight = FontWeight.SemiBold) }
                                                GlassButton({ pinWidget(context, true) }, Modifier.weight(1f)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text("Focus", fontWeight = FontWeight.SemiBold) }
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
                                            checkUpdates, { checkUpdates = it; save("checkUpdates", it); update = Updates.available(context) }, reducedMotion = reduced, openUrl = openUrl,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
                DayOverlay(day, if (demo) providers else snapshot?.providers.orEmpty()) { dayOpen = null }
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
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("Disconnect this phone?") }, text = { Text("This clears its pairing and saved readings. Your Windows history stays untouched.") }, confirmButton = { TextButton(onClick = { scope.launch { repo.disconnect(); afterSync(); disconnect = false } }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Cancel") } })
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
        SettingToggle("Usage alerts", "80%, 95%, budgets and pace", usageAlerts) { setNotify("usage", it) }
        Divider()
        SettingToggle("Reset alerts", "When a limit renews", resetAlerts) { setNotify("reset", it) }
        Divider()
        SettingToggle("Weekly recap", "Sunday evening", recap) { setNotify("recap", it) }
    } }
    item { SectionLabel("Display") }
    item { Group {
        SettingToggle("Show remaining", null, remaining, setRemaining)
        Divider()
        SettingToggle("24-hour clock", null, clock24, setClock24)
        Divider()
        SettingToggle("Reduce motion", null, reduced, setReduced)
        Divider()
        SettingToggle("Check for updates", null, checkUpdates, setCheckUpdates)
    } }
    item { Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("UsageNotch ${BuildConfig.VERSION_NAME}", color = LocalTokens.current.faint, fontSize = 12.sp)
        TextButton(onClick = { openUrl(Updates.RELEASES) }) { Text("Downloads", color = LocalTokens.current.muted) }
    } }
}
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
private fun pinWidget(context: Context, focus: Boolean) {
    val manager = AppWidgetManager.getInstance(context)
    val pinned = runCatching { manager != null && manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, if (focus) FocusWidget::class.java else UsageWidget::class.java), null, null) }.getOrDefault(false)
    if (!pinned) android.widget.Toast.makeText(context, "Long-press Home → Widgets → UsageNotch", android.widget.Toast.LENGTH_LONG).show()
}
internal fun demoSnapshot(): Snapshot {
    val now = System.currentTimeMillis()
    fun window(id: String, label: String, used: Double, reset: Long, detail: String? = null, forecast: Forecast? = null) = UsageWindow(id, label, used, now, reset, (0..48).map { Reading(now - (48 - it) * 1_200_000L, used * (.25 + .75 * it / 48), "preview") }, detail, forecast = forecast)
    return Snapshot(now, listOf(
        Provider("claude", "Claude", "Ok", listOf(window("five_hour", "Current session", .27, now + 7_845_000, forecast = Forecast("Estimated 61.4% used at reset", 15.6, 61.4, null, "Consistent pace")), window("seven_day", "All models", .41, now + 231_845_000), window("seven_day_sonnet", "Sonnet weekly", .12, now + 231_845_000)),
            account = "Sample account", updatedAt = now, manageUrl = "https://claude.ai/settings/usage", session = "five_hour", weekly = "seven_day",
            extras = listOf(Extra("extra_usage", "Usage credits", "Pay-as-you-go usage after plan limits", null, 3.5, null, "USD")),
            history = listOf(demoHistory("five_hour", "Current session", 1.0, 3), demoHistory("seven_day", "All models", .16, 5))),
        Provider("codex", "Codex", "Ok", listOf(window("codex-primary", "5-hour limit", .16, now + 11_400_000), window("codex-secondary", "Weekly limit", .49, now + 401_840_000)), updatedAt = now, manageUrl = "https://chatgpt.com/codex/settings/usage", session = "codex-primary", weekly = "codex-secondary",
            history = listOf(demoHistory("codex-primary", "5-hour limit", .7, 11))),
        Provider("gemini", "Gemini", "Ok", listOf(window("gemini-2.5-pro", "Gemini 2.5 Pro", .72, now + 50_000_000)), updatedAt = now),
    ), "sample")
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
        return Day(date.toString(), if (back in setOf(12, 13, 21, 44, 45, 67)) null else scale * ((if (weekend) .15 else .55) + random.nextDouble() * .45))
    }
    val calendar = (89 downTo 0).map { day(it) }
    val days = calendar.takeLast(30)
    val heat = (0 until 168).map { i -> val d = i / 24; val h = i % 24; val work = d in 1..5 && h in 9..18
        if (h in 1..6) 0.0 else scale * (if (work) .05 + .06 * kotlin.math.sin((h - 9) / 9.0 * Math.PI) else .012) * (.7 + random.nextDouble() * .6) }
    val observed = (0 until 168).map { i -> if (i % 24 in 2..5) 0 else 3 + random.nextInt(2) }
    return History(window, label, days, heat.mapIndexed { i, v -> if (observed[i] == 0) 0.0 else v }, observed, days.reversed().takeWhile { (it.used ?: 0.0) > 0 }.size, calendar)
}
