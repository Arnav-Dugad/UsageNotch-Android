package io.github.arnavdugad.usagenotch

import android.Manifest
import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource
import dev.chrisbanes.haze.rememberHazeState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
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
    var liveUpdate by remember { mutableStateOf(LiveUpdate.enabled(context) && ResetAlerts.canNotify(context)) }
    var usageAlerts by remember { mutableStateOf(UsageAlerts.enabled(context) && ResetAlerts.canNotify(context)) }
    var followed by remember { mutableStateOf(repo.prefs.getString("liveProvider", null)) }
    var askingFor by remember { mutableStateOf<String?>(null) }
    val historyState = rememberHistoryState()
    val hazeState = rememberHazeState()
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val listState = rememberLazyListState()
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
    // Items above the first provider card on Overview, so a dock tap can scroll straight to its card.
    val leading = listOf(true, crash != null, update != null, demo, pairError.isNotBlank() && !pasting, error.isNotBlank() && connected, true, offlineNotice, true).count { it }
    val openUrl: (String) -> Unit = { url -> runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

    // Back from History, Widgets or Settings returns to Overview; the page shrinks with the predictive back gesture.
    var pageBack by remember { mutableFloatStateOf(0f) }
    PredictiveBackHandler(enabled = detail == null && !scanning && page != 0) { progress ->
        try { progress.collect { pageBack = it.progress }; page = 0 } finally { pageBack = 0f }
    }
    val open = providers.firstOrNull { it.id == detail }
    LaunchedEffect(open == null) { if (open == null) detail = null }
    Box(Modifier.fillMaxSize()) {
    SharedTransitionLayout(Modifier.fillMaxSize()) {
    AnimatedContent(open?.id, transitionSpec = {
        if (reduced) fadeIn(snap()) togetherWith fadeOut(snap())
        else (fadeIn(tween(300, delayMillis = 60)) + scaleIn(tween(360, easing = FastOutSlowInEasing), initialScale = .96f)) togetherWith fadeOut(tween(200))
    }, label = "Provider detail") { openId ->
    val visibility = this
    // The dock ring morphs into the detail ring (and back) through a shared element.
    val share: @Composable (String) -> Modifier = { id -> if (reduced) Modifier else Modifier.sharedElement(rememberSharedContentState("ring-$id"), visibility) }
    val openProvider = providers.firstOrNull { it.id == openId }
    if (openProvider != null) ProviderDetail(openProvider, now, remaining, clock24, reduced, offline && !demo, demo, share(openProvider.id), if (connected) ({ refresh() }) else null, openUrl) { detail = null }
    else CompositionLocalProvider(LocalRingShare provides share) {
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.background, t.backgroundEnd)))) {
        if (t.dark) Canvas(Modifier.fillMaxSize()) {
            if (size.width <= 0f || size.height <= 0f) return@Canvas
            drawCircle(Brush.radialGradient(listOf(Color(0xFF2EE0A8).copy(alpha = .07f), Color.Transparent), center = Offset(size.width * .9f, size.height * .08f), radius = size.width * .8f), radius = size.width * .8f, center = Offset(size.width * .9f, size.height * .08f))
        }
        Scaffold(Modifier.hazeSource(hazeState), containerColor = Color.Transparent, contentColor = t.text, bottomBar = {
            Panel(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp), radius = 30.dp) {
                Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("Overview" to Icons.Outlined.DonutLarge, "History" to Icons.Outlined.Insights, "Widgets" to Icons.Outlined.Widgets, "Settings" to Icons.Outlined.Tune).forEachIndexed { index, item ->
                        val on = page == index
                        val color by animateColorAsState(if (on) t.accent else t.muted, tween(if (reduced) 0 else 220), label = "Navigation tint")
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(22.dp)).background(if (on) t.raised else Color.Transparent).clickable(role = Role.Tab) { page = index }.semantics { this.selected = on }.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(item.second, null, tint = color, modifier = Modifier.size(22.dp)); Spacer(Modifier.height(4.dp)); Text(item.first, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }) { padding ->
            AnimatedContent(targetState = page, transitionSpec = { fadeIn(tween(if (reduced) 0 else 200)) togetherWith fadeOut(tween(if (reduced) 0 else 120)) }, label = "Page transition", modifier = Modifier.padding(padding).graphicsLayer {
                val scale = 1f - pageBack * .06f; scaleX = scale; scaleY = scale; alpha = 1f - pageBack * .25f
            }) { current ->
                val pageState = if (current == 0) listState else rememberLazyListState()
                PullToRefreshBox(isRefreshing = refreshing, onRefresh = { if (connected) refresh() }, modifier = Modifier.fillMaxSize()) {
                LazyColumn(Modifier.fillMaxSize(), state = pageState, contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    item(key = "header") { Header(current, busy, { refresh() }, connected) }
                    crash?.let { report -> item(key = "crash") { Notice("UsageNotch closed unexpectedly", "A crash report was saved on this phone. Sharing it helps fix the problem; it contains the app version, phone model and error details, not your usage or pairing.", "Share report", "Dismiss", { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share crash report")) }) { CrashLog.clear(context); crash = null } } }
                    update?.let { version -> item(key = "update") { Notice("UsageNotch $version is available", "Download the new APK from GitHub and open it to update. Your pairing and readings are kept.", "Download", tone = t.accent, clicked = { openUrl(Updates.RELEASES) }) } }
                    if (demo) item(key = "demo") { Notice("Preview · sample data", "These readings are illustrative. Your widgets only use paired PC data.", "Exit preview", clicked = { demo = false }, tone = Color(0xFF8FB6FF)) }
                    if (pairError.isNotBlank() && !pasting) item(key = "pairError") { Notice("Pairing didn't finish", pairError, "Dismiss", clicked = { pairError = "" }) }
                    if (error.isNotBlank() && connected) item(key = "error") { Notice("Connection needs attention", error) }
                    when (current) {
                        0 -> {
                            item(key = "status") { StatusCard(demo, pairing, error, offline, source, repo.lastSync(), now) }
                            if (offlineNotice) item(key = "offline") {
                                Notice("Working offline", "Your PC isn't reachable right now. Saved readings, countdowns, widgets and reset alerts keep working." + if (pairing?.relay == null) " To get readings away from home, turn on internet sync in UsageNotch → Settings → Phone on your PC. This phone picks it up by itself the next time both are on the same Wi-Fi." else " New readings arrive once your PC is back online.", tone = t.muted)
                            }
                            if (providers.isEmpty()) item(key = "pair") { PairCard(busy, connected, { scan() }, { pasting = true }, { picker.launch(arrayOf("*/*")) }, { demo = true }) }
                            else item(key = "dock") {
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(4.dp), contentPadding = PaddingValues(horizontal = 2.dp)) {
                                    items(providers, key = { it.id }) { p ->
                                        DockCell(p, remaining, now, reduced, selected == p.id) { selected = p.id; detail = p.id }
                                    }
                                }
                            }
                            providers.forEach { p -> item(key = "p-${p.id}") {
                                ProviderCard(p, now, remaining, clock24, reduced, offline && !demo, demo, if (connected) ({ refresh() }) else null, openUrl, onOpen = { selected = p.id; detail = p.id })
                            } }
                            item(key = "footer") { Text("Each provider and limit stays separate. Readings come from UsageNotch on your PC; times are local to this phone.", color = t.faint, fontSize = 11.5.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
                        }
                        1 -> historyPage(if (demo) providers else snapshot?.providers.orEmpty(), historyState, clock24, reduced)
                        2 -> {
                            item { Text("The desktop dock, on your home screen. Resize freely: one ring, a row, a column or the full view.", color = t.muted, fontSize = 15.sp, lineHeight = 22.sp) }
                            item { WidgetPreviews(shown ?: demoSnapshot(), now, t.dark, remaining, clock24, offline && !demo) }
                            item { Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Button(onClick = { pinWidget(context, false) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Add overview") }
                                OutlinedButton(onClick = { pinWidget(context, true) }, modifier = Modifier.weight(1f).height(48.dp)) { Text("Add focus") }
                            } }
                            item { Text("If your launcher can't pin widgets, long-press Home → Widgets → UsageNotch. The focus widget follows one provider; long-press it to choose which.", color = t.faint, fontSize = 12.sp, lineHeight = 17.sp) }
                            item { QuickTileCard() }
                            item { Notice("On the lock screen", "Turn on Live countdown in Settings to see your percentage and reset countdown on the lock screen and, on Android 16, as a status-bar chip. Devices with lock-screen widgets can also add UsageNotch there.", tone = t.muted) }
                            item { Notice("Quiet in the background", "Android refreshes about every 15 minutes and may delay it to save battery. Tap ↻ on a large widget for a fresh sync. When your PC is off, widgets keep your saved readings and switch to Renewed when a limit resets.", tone = t.muted) }
                        }
                        3 -> {
                            item { SectionLabel("CONNECTION") }
                            item { Panel { Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Outlined.LaptopWindows, null, tint = t.accent); Spacer(Modifier.width(10.dp))
                                    Text(pairing?.name ?: "Pair your Windows PC", fontSize = 19.sp, fontWeight = FontWeight.Medium, color = t.text)
                                }
                                Text(if (pairing == null) "On your PC, open UsageNotch → Settings → Phone. Turn on sharing, then scan the QR code shown there." else "Readings come straight from your PC over Wi-Fi or a private VPN.", fontSize = 13.sp, lineHeight = 19.sp, color = t.muted)
                                if (pairing != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (pairing?.relay != null) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff, null, tint = if (pairing?.relay != null) t.accent else t.muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                                    Text(if (pairing?.relay != null) "Internet sync on · end-to-end encrypted. Readings reach this phone anywhere." else "Internet sync off. Turn it on in UsageNotch → Settings → Phone on your PC; this phone picks it up by itself over Wi-Fi. No new code needed.", fontSize = 12.sp, lineHeight = 17.sp, color = t.muted)
                                }
                                Button(onClick = { scan() }, enabled = !busy, modifier = Modifier.fillMaxWidth().height(50.dp)) { Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text(if (pairing == null) "Scan QR code" else "Scan a new code") }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    OutlinedButton(onClick = { pasting = true }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Paste code") }
                                    OutlinedButton(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Import file") }
                                }
                                if (pairing != null) TextButton(onClick = { disconnect = true }) { Text("Disconnect this phone") }
                            } } }
                            item { SectionLabel("APPEARANCE") }
                            item { Panel { Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
                                Text("Theme", fontSize = 15.sp, color = t.text)
                                Row(Modifier.padding(top = 10.dp).clip(RoundedCornerShape(14.dp)).background(t.raised).padding(4.dp)) {
                                    listOf("system" to "System", "light" to "Light", "dark" to "Dark").forEach { (key, label) ->
                                        val on = appearance == key
                                        Box(Modifier.weight(1f).clip(RoundedCornerShape(11.dp)).background(if (on) t.card else Color.Transparent).clickable(role = Role.RadioButton) {
                                            repo.prefs.edit().putString("appearance", key).apply(); appearanceChanged(key); runCatching { updateWidgets(context) }
                                        }.semantics { this.selected = on; contentDescription = "$label theme" }.padding(vertical = 9.dp), contentAlignment = Alignment.Center) {
                                            Text(label, fontSize = 13.sp, color = if (on) t.text else t.muted, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal)
                                        }
                                    }
                                }
                                Text("Widgets follow the same theme.", fontSize = 12.sp, color = t.muted, modifier = Modifier.padding(top = 8.dp))
                                if (Build.VERSION.SDK_INT >= 31) {
                                    HorizontalDivider(color = t.hairline, modifier = Modifier.padding(top = 8.dp))
                                    SettingToggle("Wallpaper colors", "Material You: surfaces follow your wallpaper. Rings keep their usage colors.", wallpaper) { repo.prefs.edit().putBoolean("wallpaperColors", it).apply(); wallpaperChanged(it) }
                                }
                            } } }
                            item { SectionLabel("DISPLAY & ALERTS") }
                            item { Panel { Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                                SettingToggle("Show remaining", "Switch between percentage left and used.", remaining) { remaining = it; save("remaining", it) }
                                HorizontalDivider(color = t.hairline)
                                SettingToggle("24-hour clock", "Off uses clean AM/PM times.", clock24) { clock24 = it; save("clock24", it) }
                                HorizontalDivider(color = t.hairline)
                                SettingToggle("Reduce motion", "Quiet transitions and immediate ring updates.", reduced) { reduced = it; save("reduceMotion", it) }
                                HorizontalDivider(color = t.hairline)
                                SettingToggle("Reset alerts", "Notify me when a limit resets. Works while your PC is off.", alerts) { setNotify("reset", it) }
                                HorizontalDivider(color = t.hairline)
                                SettingToggle("Usage alerts", "At 80% and 95% used, and when the pace says a limit runs out within 2 hours of use.", usageAlerts) { setNotify("usage", it) }
                                HorizontalDivider(color = t.hairline)
                                SettingToggle("Live countdown", "An ongoing notification with the percentage and a countdown to the reset, also on the lock screen." + if (Build.VERSION.SDK_INT >= 36) " Shows as a Live Update chip." else "", liveUpdate) { setNotify("live", it) }
                                val choices = snapshot?.providers.orEmpty().filter { it.sessionWindow() != null }
                                if (liveUpdate && choices.size > 1) Column(Modifier.padding(bottom = 12.dp)) {
                                    Text("Follow", fontSize = 12.sp, color = t.muted)
                                    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        val current = focusProvider(choices, followed)?.id
                                        choices.forEach { p ->
                                            val on = p.id == current
                                            Row(Modifier.clip(RoundedCornerShape(12.dp)).background(if (on) t.raised else Color.Transparent).border(1.dp, if (on) t.accent.copy(alpha = .5f) else t.hairline, RoundedCornerShape(12.dp))
                                                .clickable(role = Role.RadioButton) { followed = p.id; repo.prefs.edit().putString("liveProvider", p.id).apply(); LiveUpdate.update(context); UsageTileService.refresh(context) }
                                                .semantics { this.selected = on }.padding(horizontal = 11.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Logo(p.id, Modifier.size(15.dp)); Spacer(Modifier.width(6.dp)); Text(p.name, fontSize = 12.5.sp, color = if (on) t.text else t.muted)
                                            }
                                        }
                                    }
                                }
                                HorizontalDivider(color = t.hairline)
                                SettingToggle("Check for updates", "Ask GitHub Releases for a newer APK twice a day.", checkUpdates) { checkUpdates = it; save("checkUpdates", it); update = Updates.available(context) }
                            } } }
                            item { SectionLabel("PRIVATE BY DESIGN") }
                            item { Text("Your AI sign-ins stay on Windows. The pairing key is encrypted with Android Keystore. Optional internet sync is end-to-end encrypted; GitHub stores only ciphertext. No analytics, ads or tracking. Disconnecting clears this phone's cached readings; revoke pairing on your PC to invalidate its key.", fontSize = 13.sp, lineHeight = 20.sp, color = t.muted, modifier = Modifier.padding(horizontal = 8.dp)) }
                            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("UsageNotch · ${BuildConfig.VERSION_NAME}", color = t.faint, fontSize = 12.sp)
                                TextButton(onClick = { openUrl(Updates.RELEASES) }) { Text("Downloads") }
                            } }
                        }
                    }
                }
                }
            }
        }
        if (busy && !refreshing) LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding().align(Alignment.TopCenter), color = t.accent)
        // Once the dock scrolls away, a compact glass copy floats at the top; content blurs beneath it on Android 12+.
        val floating by remember { derivedStateOf { listState.firstVisibleItemIndex >= leading } }
        AnimatedVisibility(page == 0 && providers.isNotEmpty() && floating, Modifier.align(Alignment.TopCenter),
            enter = if (reduced) fadeIn(snap()) else slideInVertically(spring(dampingRatio = .75f, stiffness = 500f)) { -it } + fadeIn(),
            exit = if (reduced) fadeOut(snap()) else slideOutVertically(tween(180)) { -it } + fadeOut(tween(160))) {
            val shape = RoundedCornerShape(26.dp)
            Box(Modifier.statusBarsPadding().padding(horizontal = 14.dp, vertical = 8.dp).clip(shape)
                .hazeEffect(hazeState, HazeStyle(backgroundColor = t.background, tints = listOf(HazeTint(t.card.copy(alpha = if (t.dark) .55f else .62f))), blurRadius = 22.dp, noiseFactor = .04f, fallbackTint = HazeTint(t.card.copy(alpha = .96f))))
                .border(1.dp, t.hairline, shape)) {
                CompositionLocalProvider(LocalRingShare provides null) {
                    Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 6.dp, vertical = 4.dp)) {
                        providers.forEach { p -> DockCell(p, remaining, now, reduced, selected == p.id, compact = true) { selected = p.id; detail = p.id } }
                    }
                }
            }
        }
    }
    }
    }
    }
    if (scanning) ScannerScreen(reduced, { code -> scanning = false; importPairing { code } }) { scanning = false }
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

@Composable private fun Header(page: Int, busy: Boolean, refresh: () -> Unit, connected: Boolean) {
    val t = LocalTokens.current
    Row(Modifier.fillMaxWidth().padding(bottom = 2.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { Text("USAGENOTCH", fontSize = 10.sp, letterSpacing = 3.sp, color = t.accent, fontWeight = FontWeight.SemiBold); Text(listOf("Your AI, at a glance.", "Your patterns, in time.", "Any size. Your rings.", "Perfectly yours.")[page], fontSize = 27.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, color = t.text, modifier = Modifier.padding(top = 8.dp)) }
        if (page == 0) IconButton(onClick = refresh, enabled = connected && !busy, modifier = Modifier.clip(CircleShape).background(t.raised)) { Icon(Icons.Outlined.Refresh, "Refresh usage", tint = if (connected) t.accent else t.muted.copy(alpha = .4f)) }
    }
}
@Composable private fun StatusCard(demo: Boolean, pairing: Pairing?, error: String, offline: Boolean, source: Source, lastSync: Long, now: Long) {
    val t = LocalTokens.current
    val live = !demo && pairing != null && !offline && error.isBlank()
    val (title, detail, dot) = when {
        demo -> Triple("A little clarity. More flow.", "Sample data · not from your PC", Color(0xFF8FB6FF))
        pairing == null -> Triple("Your AIs. One quiet space.", "Pair with UsageNotch on Windows to bring your limits along.", Color(Palette.WARNING))
        error.isNotBlank() -> Triple(pairing.name, "Needs attention · synced ${ClockText.age(lastSync, now)}", Color(Palette.WARNING))
        offline -> Triple(pairing.name, "PC offline · showing reading synced ${ClockText.age(lastSync, now)}", t.faint)
        source == Source.Internet -> Triple(pairing.name, "Encrypted internet sync · ${ClockText.age(lastSync, now)}", Color(0xFF2EE0A8))
        else -> Triple(pairing.name, "Encrypted PC link · ${ClockText.age(lastSync, now)}", Color(0xFF2EE0A8))
    }
    val glow = if (live) rememberInfiniteTransition(label = "Live dot").animateFloat(.35f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "Glow").value else 1f
    Panel(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(9.dp).clip(CircleShape).background(dot.copy(alpha = glow))); Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = t.text)
                Text(detail, color = t.muted, fontSize = 12.sp, modifier = Modifier.padding(top = 3.dp))
            }
            Icon(if (!demo && pairing != null && offline) Icons.Outlined.CloudOff else Icons.Outlined.VerifiedUser, null, tint = t.accent.copy(alpha = .8f), modifier = Modifier.size(21.dp))
        }
    }
}
@Composable private fun PairCard(busy: Boolean, paired: Boolean, scan: () -> Unit, paste: () -> Unit, import: () -> Unit, preview: () -> Unit) {
    val t = LocalTokens.current
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp).background(Brush.radialGradient(listOf(t.accent.copy(alpha = .22f), Color.Transparent)), CircleShape), contentAlignment = Alignment.Center) { Icon(if (paired) Icons.Outlined.HourglassTop else Icons.Outlined.QrCodeScanner, null, tint = t.accent, modifier = Modifier.size(44.dp)) }
        Text(if (paired) "Waiting for a reading." else "Pair in one scan.", fontSize = 24.sp, fontWeight = FontWeight.Light, color = t.text, modifier = Modifier.padding(top = 16.dp))
        Text(if (paired) "Open UsageNotch on your PC and let it collect usage. It appears here on the next sync." else "On your PC, open UsageNotch → Settings → Phone and turn on sharing. Then scan the code it shows.", color = t.muted, lineHeight = 21.sp, fontSize = 14.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 14.dp))
        if (!paired) {
            Button(onClick = scan, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Outlined.QrCodeScanner, null, Modifier.size(20.dp)); Spacer(Modifier.width(8.dp)); Text("Scan QR code") }
            Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = paste, enabled = !busy, modifier = Modifier.weight(1f)) { Icon(Icons.Outlined.ContentPaste, null, Modifier.size(17.dp)); Spacer(Modifier.width(6.dp)); Text("Paste code") }
                OutlinedButton(onClick = import, enabled = !busy, modifier = Modifier.weight(1f)) { Text("Import PC pairing") }
            }
            TextButton(onClick = preview, modifier = Modifier.padding(top = 4.dp)) { Text("Explore with sample data", color = t.muted) }
        }
    } }
}
@Composable private fun PasteDialog(busy: Boolean, problem: String, dismiss: () -> Unit, pair: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }; val t = LocalTokens.current
    AlertDialog(onDismissRequest = dismiss, title = { Text("Paste pairing code") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("On your PC, open UsageNotch → Settings → Phone, choose Copy pairing code and send it to yourself privately. It starts with UN1.", fontSize = 13.sp, color = t.muted)
            OutlinedTextField(code, { code = it }, placeholder = { Text("UN1.…") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
            if (problem.isNotBlank()) Text(problem, fontSize = 12.sp, color = Color(Palette.readable(Palette.WARNING, t.card.toArgb())))
        } },
        confirmButton = { TextButton(onClick = { pair(code) }, enabled = code.isNotBlank() && !busy) { Text("Pair") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
/** Real renders of the widget at common sizes, using the same renderer as the home screen. */
@Composable private fun WidgetPreviews(snapshot: Snapshot, now: Long, dark: Boolean, remaining: Boolean, clock24: Boolean, offline: Boolean) {
    val context = LocalContext.current; val density = LocalDensity.current.density; val t = LocalTokens.current
    val minute = now / 60_000
    @Composable fun Preview(label: String, w: Float, h: Float, focus: Boolean) {
        val bitmap = remember(snapshot, dark, remaining, clock24, minute, w, h, offline) {
            WidgetRenderer.render(context, WidgetRenderer.Frame(w, h, density), WidgetRenderer.Input(snapshot, true, if (offline) "PC offline · saved readings" else "PC sync just now", remaining, clock24, now, dark, focus, null, offline)).bitmap.asImageBitmap()
        }
        Column {
            Box(Modifier.size(w.dp, h.dp).clip(RoundedCornerShape(24.dp)).background(if (dark) Color(0xF2121519) else Color(0xF7FFFFFF)).border(1.dp, t.hairline, RoundedCornerShape(24.dp))) {
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

/** Offers the Quick Settings tile. Android 13+ can add it in one tap; older versions add it from the tile editor. */
@Composable private fun QuickTileCard() {
    val context = LocalContext.current; val t = LocalTokens.current
    var result by remember { mutableStateOf<String?>(null) }
    Panel(Modifier.fillMaxWidth()) { Column(Modifier.padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.ToggleOn, null, tint = t.accent); Spacer(Modifier.width(10.dp))
            Text("Quick Settings tile", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = t.text)
        }
        Text("Your followed provider's percentage in the notification shade. Tap it to refresh from your PC.", fontSize = 12.5.sp, lineHeight = 18.sp, color = t.muted, modifier = Modifier.padding(top = 6.dp))
        if (Build.VERSION.SDK_INT >= 33) TextButton(onClick = {
            val bar = context.getSystemService(android.app.StatusBarManager::class.java)
            runCatching {
                bar.requestAddTileService(ComponentName(context, UsageTileService::class.java), "UsageNotch", android.graphics.drawable.Icon.createWithResource(context, R.drawable.ic_stat_notch), context.mainExecutor) { code ->
                    result = when (code) {
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ADDED -> "Added. Swipe down to see it."
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_ALREADY_ADDED -> "It's already in your Quick Settings."
                        android.app.StatusBarManager.TILE_ADD_REQUEST_RESULT_TILE_NOT_ADDED -> null
                        else -> "Swipe down twice, tap the pencil, then drag UsageNotch into your tiles."
                    }
                }
            }.onFailure { result = "Swipe down twice, tap the pencil, then drag UsageNotch into your tiles." }
        }) { Text("Add tile") }
        else Text("Swipe down twice, tap the pencil, then drag UsageNotch into your tiles.", fontSize = 12.sp, color = t.faint, modifier = Modifier.padding(top = 6.dp))
        result?.let { Text(it, fontSize = 12.sp, color = t.faint) }
    } }
}

/** Illustrative 30 days for the preview: weekday afternoons busiest, a few days without readings. */
internal fun demoHistory(window: String, label: String, scale: Double, seed: Int): History {
    val today = java.time.LocalDate.now()
    val random = java.util.Random(seed.toLong())
    val days = (29 downTo 0).map { back ->
        val date = today.minusDays(back.toLong()); val weekend = date.dayOfWeek.value >= 6
        Day(date.toString(), if (back in setOf(12, 13, 21)) null else scale * ((if (weekend) .15 else .55) + random.nextDouble() * .45))
    }
    val heat = (0 until 168).map { i -> val d = i / 24; val h = i % 24; val work = d in 1..5 && h in 9..18
        if (h in 1..6) 0.0 else scale * (if (work) .05 + .06 * kotlin.math.sin((h - 9) / 9.0 * Math.PI) else .012) * (.7 + random.nextDouble() * .6) }
    val observed = (0 until 168).map { i -> if (i % 24 in 2..5) 0 else 3 + random.nextInt(2) }
    return History(window, label, days, heat.mapIndexed { i, v -> if (observed[i] == 0) 0.0 else v }, observed, days.reversed().takeWhile { (it.used ?: 0.0) > 0 }.size)
}
