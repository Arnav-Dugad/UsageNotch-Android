package io.github.arnavdugad.usagenotch

import android.Manifest
import android.appwidget.AppWidgetManager
import android.content.ComponentName
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
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.io.InputStream
import kotlin.math.min

private val Ink = Color(0xFFEEF5FF)
private val Muted = Color(0xFFADBDD2)
private val Mint = Color(0xFFACE8DA)
private val Peach = Color(0xFFF1BD9A)
private val Lavender = Color(0xFFC9BDF5)
private val Backdrop = Color(0xFF09111F)
internal fun providerColor(id: String) = when (providerKind(id)) { "claude", "anthropic-api" -> Peach; "codex", "openai-api" -> Mint; "gemini" -> Lavender; else -> Color(0xFFA9CFF5) }

class MainActivity : ComponentActivity() {
    private var incoming by mutableStateOf<Uri?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CrashLog.launching(this)
        if (CrashLog.inSafeMode(this)) { showSafeMode(); return }
        runCatching { enableEdgeToEdge() }
        if (savedInstanceState == null) incoming = importUri(intent)
        addOnNewIntentListener { importUri(it)?.let { uri -> incoming = uri } }
        setContent { NotchTheme { NotchApp(incoming) { incoming = null } } }
        // Surviving the first seconds clears the startup-crash counter used for safe mode.
        window.decorView.postDelayed({ CrashLog.settled(this) }, 8_000)
    }
    private fun importUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
    /** Plain platform views only, so this screen works even if the regular dashboard cannot start. */
    private fun showSafeMode() {
        val pad = (20 * resources.displayMetrics.density).toInt()
        val report = CrashLog.report(this) ?: "No report was saved."
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(pad, pad * 2, pad, pad); setBackgroundColor(0xFF09111F.toInt()) }
        fun text(value: String, size: Float, color: Int) = TextView(this).apply { text = value; textSize = size; setTextColor(color); setPadding(0, pad / 2, 0, pad / 2) }
        fun button(label: String, action: () -> Unit) = Button(this).apply { text = label; setOnClickListener { action() } }
        column.addView(text("UsageNotch safe mode", 24f, 0xFFEEF5FF.toInt()))
        column.addView(text("UsageNotch closed unexpectedly twice while starting. You can share the report below with the developer, or clear this phone's UsageNotch data (your Windows history is untouched).", 15f, 0xFFADBDD2.toInt()))
        column.addView(button("Share crash report") { startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share crash report")) })
        column.addView(button("Try again") { CrashLog.clear(this); recreate() })
        column.addView(button("Clear app data and try again") {
            listOf("usage", "display", "pairing-vault").forEach { getSharedPreferences(it, MODE_PRIVATE).edit().clear().commit() }
            CrashLog.clear(this); recreate()
        })
        column.addView(text(report, 11f, 0xFFADBDD2.toInt()).apply { gravity = Gravity.START; setTextIsSelectable(true) })
        setContentView(ScrollView(this).apply { setBackgroundColor(0xFF09111F.toInt()); addView(column) })
    }
}
@Composable fun NotchTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(primary = Mint, onPrimary = Backdrop, primaryContainer = Color(0xFF22433F), onPrimaryContainer = Mint, secondary = Lavender, background = Backdrop, surface = Color(0xFF172336), onSurface = Ink, onSurfaceVariant = Muted), content = content)
}
internal fun readBounded(stream: InputStream, max: Int): ByteArray {
    val output = ByteArrayOutputStream(); val buffer = ByteArray(8192)
    while (true) { val count = stream.read(buffer, 0, min(buffer.size, max + 1 - output.size())); if (count < 0) break; output.write(buffer, 0, count); require(output.size() <= max) { "File too large" } }
    return output.toByteArray()
}
private const val NOT_A_PAIRING = "That isn't a UsageNotch pairing. In UsageNotch Link, choose Export pairing file or Copy pairing code, then try again."
private const val UNREADABLE = "Couldn't open that file. Save it to your phone first (for example in Downloads), then choose Import PC pairing again, or paste the pairing code instead."
private const val PC_UNREACHABLE = "Couldn't reach your PC. Start the secure link in UsageNotch Link, keep both devices on the same Wi-Fi or private VPN, and allow Link through Windows Firewall on Private networks."
@Composable fun NotchApp(incoming: Uri? = null, incomingHandled: () -> Unit = {}) {
    val context = LocalContext.current; val repo = remember { Repository(context) }; val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf(repo.snapshot()) }; var pairing by remember { mutableStateOf(repo.pairing()) }
    var error by remember { mutableStateOf(repo.error()) }; var offline by remember { mutableStateOf(repo.offline()) }; var source by remember { mutableStateOf(repo.source()) }
    var pairError by remember { mutableStateOf("") }; var busy by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }; var demo by rememberSaveable { mutableStateOf(false) }
    var remaining by remember { mutableStateOf(repo.remaining()) }; var clock24 by remember { mutableStateOf(repo.use24()) }
    var reduced by remember { mutableStateOf(repo.reduceMotion()) }; var disconnect by remember { mutableStateOf(false) }
    var alerts by remember { mutableStateOf(ResetAlerts.enabled(context) && ResetAlerts.canNotify(context)) }
    var checkUpdates by remember { mutableStateOf(Updates.enabled(context)) }; var update by remember { mutableStateOf(Updates.available(context)) }
    var crash by remember { mutableStateOf(CrashLog.report(context)) }; var pasting by remember { mutableStateOf(false) }
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    fun reload() { snapshot = repo.snapshot(); pairing = repo.pairing(); error = repo.error(); offline = repo.offline(); source = repo.source(); update = Updates.available(context) }
    fun afterSync() { reload(); runCatching { updateWidgets(context) }; runCatching { ResetAlerts.schedule(context) } }
    fun refresh() { if (!busy) scope.launch { busy = true; try { repo.refresh() } catch (_: Exception) { } finally { afterSync(); busy = false } } }
    fun importPairing(read: suspend () -> String) {
        if (busy) return
        scope.launch {
            busy = true; pairError = ""
            try {
                val raw = try { read() } catch (_: Exception) { pairError = UNREADABLE; return@launch }
                if (runCatching { Pairing.parse(raw) }.isFailure) pairError = NOT_A_PAIRING
                else { repo.pair(raw); demo = false; pasting = false; afterSync() }
            } catch (_: Exception) { pairError = PC_UNREACHABLE }
            finally { busy = false }
        }
    }
    fun readUri(uri: Uri) = importPairing { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { String(readBounded(it, 16_384), Charsets.UTF_8) } ?: error("Could not open file") } }
    val owner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    LaunchedEffect(owner) {
        owner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            reload()
            // Keep clocks responsive while the network is slow; reflect background updates while open.
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
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) readUri(uri) }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> alerts = granted; ResetAlerts.setEnabled(context, granted) }
    fun save(key: String, value: Boolean) { repo.prefs.edit().putBoolean(key, value).apply(); runCatching { updateWidgets(context) } }
    val shown = if (demo) remember { demoSnapshot() } else snapshot
    val connected = pairing != null && !demo
    Box(Modifier.fillMaxSize().background(Backdrop)) {
        Atmosphere()
        Scaffold(containerColor = Color.Transparent, contentColor = Ink, bottomBar = {
            Glass(Modifier.navigationBarsPadding().padding(horizontal = 24.dp, vertical = 10.dp), radius = 32.dp) {
                Row(Modifier.fillMaxWidth().padding(6.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                    listOf("Overview" to Icons.Outlined.DonutLarge, "Widgets" to Icons.Outlined.Widgets, "Settings" to Icons.Outlined.Tune).forEachIndexed { index, item ->
                        val selected = page == index
                        val color by animateColorAsState(if (selected) Mint else Muted, tween(if (reduced) 0 else 220), label = "Navigation tint")
                        Column(Modifier.weight(1f).clip(RoundedCornerShape(24.dp)).background(if (selected) Mint.copy(alpha = .09f) else Color.Transparent).clickable(role = Role.Tab) { page = index }.semantics { this.selected = selected }.padding(vertical = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(item.second, null, tint = color, modifier = Modifier.size(22.dp)); Spacer(Modifier.height(5.dp)); Text(item.first, color = color, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        }
                    }
                }
            }
        }) { padding ->
            AnimatedContent(targetState = page, transitionSpec = { fadeIn(tween(if (reduced) 0 else 200)) togetherWith fadeOut(tween(if (reduced) 0 else 120)) }, label = "Page transition", modifier = Modifier.padding(padding)) { current ->
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 18.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                    item { Header(current, busy, { refresh() }, connected) }
                    crash?.let { report -> item { Notice("UsageNotch closed unexpectedly", "A crash report was saved on this phone. Sharing it helps fix the problem; it contains the app version, phone model and error details, not your usage or pairing.", "Share report", "Dismiss", { context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, report), "Share crash report")) }) { CrashLog.clear(context); crash = null } } }
                    update?.let { version -> item { Notice("UsageNotch $version is available", "Download the new APK from GitHub and open it to update. Your pairing and readings are kept.", "Download", tone = Mint, clicked = { openLink(context, Updates.RELEASES) }) } }
                    if (demo) item { Notice("Preview · sample data", "These readings are illustrative. Your widgets only use paired PC data.", "Exit preview", clicked = { demo = false }) }
                    if (pairError.isNotBlank()) item { Notice("Pairing didn't finish", pairError, "Dismiss", clicked = { pairError = "" }) }
                    if (error.isNotBlank() && connected) item { Notice("Connection needs attention", error) }
                    when (current) {
                        0 -> {
                            item { StatusCard(demo, pairing, error, offline, source, repo.lastSync(), now) }
                            if (connected && offline && error.isBlank() && snapshot != null) item {
                                Notice("Working offline", "Your PC isn't reachable right now. Saved readings, countdowns, widgets and reset alerts keep working." + if (pairing?.relay == null) " To get new readings away from your home Wi-Fi, turn on internet sync in UsageNotch Link and import its new pairing file." else " New readings arrive once your PC is back online.", tone = Muted)
                            }
                            if (shown == null || shown.providers.isEmpty()) item { PairCard(busy, connected, { picker.launch(arrayOf("*/*")) }, { pasting = true }, { demo = true }) }
                            shown?.providers?.chunked(2)?.forEach { group -> item {
                                BoxWithConstraints {
                                    if (maxWidth >= 620.dp) Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) { group.forEach { p -> ProviderCard(p, now, remaining, clock24, reduced, Modifier.weight(1f)) }; if (group.size == 1) Spacer(Modifier.weight(1f)) }
                                    else Column(verticalArrangement = Arrangement.spacedBy(18.dp)) { group.forEach { p -> ProviderCard(p, now, remaining, clock24, reduced) } }
                                }
                            } }
                            item { Text("Each provider and usage window stays separate. Readings come from your Windows app; time shown is local to this phone.", color = Muted, fontSize = 11.sp, lineHeight = 17.sp, modifier = Modifier.padding(horizontal = 8.dp)) }
                        }
                        1 -> {
                            item { Text("A glance is enough.", fontSize = 29.sp, fontWeight = FontWeight.Light, letterSpacing = (-.8).sp); Text("Your limits, right where you need them.", color = Muted, modifier = Modifier.padding(top = 8.dp)) }
                            item { WidgetShowcase("The overview", "Claude and Codex together. Each quota stays distinct.", false, snapshot, remaining) { pinWidget(context, false) } }
                            item { WidgetShowcase("A single focus", "Choose a provider. See its first two reported windows.", true, snapshot, remaining) { pinWidget(context, true) } }
                            item { Notice("Quiet in the background", "Android refreshes about every 15 minutes and may delay it to save battery. Tap ↻ for a fresh sync. When your PC is off, widgets keep your saved readings and switch to Renewed when a limit resets.", tone = Muted) }
                        }
                        2 -> {
                            item { SectionLabel("CONNECTION") }
                            item { Glass { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                Icon(Icons.Outlined.LaptopMac, null, tint = Mint)
                                Text(pairing?.name ?: "Pair your Windows PC", fontSize = 21.sp, fontWeight = FontWeight.Medium)
                                Text(if (pairing == null) "Run UsageNotch Link beside your Windows app and start the secure link. Then import its pairing file, or paste its pairing code here." else "Readings come straight from your PC over Wi-Fi or a private VPN.", fontSize = 13.sp, lineHeight = 20.sp, color = Muted)
                                if (pairing != null) Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(if (pairing?.relay != null) Icons.Outlined.CloudDone else Icons.Outlined.CloudOff, null, tint = if (pairing?.relay != null) Mint else Muted, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(10.dp))
                                    Text(if (pairing?.relay != null) "Internet sync on · end-to-end encrypted. Readings reach this phone anywhere, and the last one stays available while your PC is off." else "Internet sync off. Turn it on in UsageNotch Link, then import the new pairing file to get readings away from home.", fontSize = 12.sp, lineHeight = 18.sp, color = Muted)
                                }
                                Button(onClick = { picker.launch(arrayOf("*/*")) }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text(if (pairing == null) "Import pairing file" else "Replace pairing") }
                                OutlinedButton(onClick = { pasting = true }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Text("Paste pairing code") }
                                if (pairing != null) TextButton(onClick = { disconnect = true }) { Text("Disconnect this phone") }
                            } } }
                            item { SectionLabel("MAKE IT YOURS") }
                            item { Glass { Column(Modifier.padding(horizontal = 20.dp, vertical = 6.dp)) {
                                SettingToggle("Show remaining", "Switch between percentage left and used.", remaining) { remaining = it; save("remaining", it) }
                                HorizontalDivider(color = Ink.copy(alpha = .07f))
                                SettingToggle("24-hour clock", "Off uses clean AM/PM times.", clock24) { clock24 = it; save("clock24", it) }
                                HorizontalDivider(color = Ink.copy(alpha = .07f))
                                SettingToggle("Reduce motion", "Quiet transitions and immediate ring updates.", reduced) { reduced = it; save("reduceMotion", it) }
                                HorizontalDivider(color = Ink.copy(alpha = .07f))
                                SettingToggle("Reset alerts", "Notify me when a limit resets. Works while your PC is off.", alerts) { on ->
                                    if (on && !ResetAlerts.canNotify(context) && Build.VERSION.SDK_INT >= 33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    else { alerts = on; ResetAlerts.setEnabled(context, on) }
                                }
                                HorizontalDivider(color = Ink.copy(alpha = .07f))
                                SettingToggle("Check for updates", "Ask GitHub Releases for a newer APK twice a day.", checkUpdates) { checkUpdates = it; save("checkUpdates", it); update = Updates.available(context) }
                            } } }
                            item { SectionLabel("PRIVATE BY DESIGN") }
                            item { Text("Your AI credentials stay on Windows. The pairing key is encrypted with Android Keystore. Optional internet sync is end-to-end encrypted; GitHub stores only ciphertext. No analytics, ads or tracking. Disconnecting clears this phone’s cached readings; revoke pairing on your PC to invalidate its key.", fontSize = 13.sp, lineHeight = 21.sp, color = Muted, modifier = Modifier.padding(horizontal = 8.dp)) }
                            item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                Text("UsageNotch · ${BuildConfig.VERSION_NAME}", color = Muted, fontSize = 12.sp)
                                TextButton(onClick = { openLink(context, Updates.RELEASES) }) { Text("Downloads") }
                            } }
                        }
                    }
                }
            }
        }
        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().statusBarsPadding().align(Alignment.TopCenter), color = Mint)
    }
    if (pasting) PasteDialog(busy, pairError, { pasting = false; pairError = "" }) { code -> importPairing { code } }
    if (disconnect) AlertDialog(onDismissRequest = { disconnect = false }, title = { Text("Disconnect this phone?") }, text = { Text("This clears its pairing and saved readings. Your Windows history stays untouched.") }, confirmButton = { TextButton(onClick = { scope.launch { repo.disconnect(); afterSync(); disconnect = false } }) { Text("Disconnect") } }, dismissButton = { TextButton(onClick = { disconnect = false }) { Text("Cancel") } })
}
private fun openLink(context: android.content.Context, url: String) { runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } }

@Composable private fun Atmosphere() {
    Canvas(Modifier.fillMaxSize()) {
        if (size.width <= 0f || size.height <= 0f) return@Canvas
        drawRect(Brush.verticalGradient(listOf(Color(0xFF17243A), Backdrop, Color(0xFF111B2F))))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF477A7B).copy(alpha = .24f), Color.Transparent), center = Offset(size.width * .96f, size.height * .23f), radius = size.width * .9f), radius = size.width * .9f, center = Offset(size.width * .96f, size.height * .23f))
        drawCircle(Brush.radialGradient(listOf(Color(0xFF77579A).copy(alpha = .18f), Color.Transparent), center = Offset(0f, size.height * .75f), radius = size.width), radius = size.width, center = Offset(0f, size.height * .75f))
    }
}
@Composable internal fun Glass(modifier: Modifier = Modifier, radius: Dp = 28.dp, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(radius)
    Box(modifier.clip(shape).background(Brush.linearGradient(listOf(Color.White.copy(alpha = .095f), Color(0xFFADC4EF).copy(alpha = .035f)))).border(1.dp, Brush.linearGradient(listOf(Color.White.copy(alpha = .20f), Color.White.copy(alpha = .035f), Color.White.copy(alpha = .09f))), shape)) { content() }
}
@Composable private fun Header(page: Int, busy: Boolean, refresh: () -> Unit, connected: Boolean) {
    Row(Modifier.fillMaxWidth().padding(bottom = 5.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Column { Text("USAGENOTCH", fontSize = 10.sp, letterSpacing = 3.sp, color = Mint, fontWeight = FontWeight.SemiBold); Text(listOf("Your AI, at a glance.", "Home, elevated.", "Perfectly yours.")[page], fontSize = 28.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp, modifier = Modifier.padding(top = 10.dp)) }
        if (page == 0) IconButton(onClick = refresh, enabled = connected && !busy, modifier = Modifier.background(Ink.copy(alpha = .05f), CircleShape)) { Icon(Icons.Outlined.Refresh, "Refresh usage", tint = if (connected) Mint else Muted.copy(alpha = .4f)) }
    }
}
@Composable private fun StatusCard(demo: Boolean, pairing: Pairing?, error: String, offline: Boolean, source: Source, lastSync: Long, now: Long) {
    val (title, detail, dot) = when {
        demo -> Triple("A little clarity. More flow.", "Sample data · not from your PC", Peach)
        pairing == null -> Triple("Your AIs. One quiet space.", "Pair Windows to bring your limits along.", Peach)
        error.isNotBlank() -> Triple(pairing.name, "Needs attention · synced ${ClockText.age(lastSync, now)}", Peach)
        offline -> Triple(pairing.name, "PC offline · showing reading synced ${ClockText.age(lastSync, now)}", Muted)
        source == Source.Internet -> Triple(pairing.name, "Encrypted internet sync · ${ClockText.age(lastSync, now)}", Mint)
        else -> Triple(pairing.name, "Encrypted PC link · ${ClockText.age(lastSync, now)}", Mint)
    }
    Glass(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(dot, CircleShape)); Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(detail, color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 4.dp))
            }
            Icon(if (!demo && pairing != null && offline) Icons.Outlined.CloudOff else Icons.Outlined.VerifiedUser, null, tint = Mint.copy(alpha = .75f), modifier = Modifier.size(22.dp))
        }
    }
}
@Composable private fun PairCard(busy: Boolean, paired: Boolean, import: () -> Unit, paste: () -> Unit, preview: () -> Unit) {
    Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(90.dp).background(Brush.radialGradient(listOf(Mint.copy(alpha = .2f), Color.Transparent)), CircleShape), contentAlignment = Alignment.Center) { Icon(Icons.Outlined.Phonelink, null, tint = Mint, modifier = Modifier.size(46.dp)) }
        Text(if (paired) "Waiting for a reading." else "Your flow, uninterrupted.", fontSize = 24.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 18.dp))
        Text(if (paired) "Open UsageNotch on your PC and let it collect usage. It appears here on the next sync." else "Bring your Windows usage to your phone. Add a widget, then get back to what matters.", color = Muted, lineHeight = 22.sp, fontSize = 14.sp, modifier = Modifier.padding(vertical = 16.dp))
        if (!paired) {
            Button(onClick = import, enabled = !busy, modifier = Modifier.fillMaxWidth().height(52.dp)) { Icon(Icons.Outlined.Link, null, Modifier.size(19.dp)); Spacer(Modifier.width(8.dp)); Text("Import PC pairing") }
            OutlinedButton(onClick = paste, enabled = !busy, modifier = Modifier.fillMaxWidth().padding(top = 10.dp).height(48.dp)) { Icon(Icons.Outlined.ContentPaste, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Paste pairing code") }
            TextButton(onClick = preview, modifier = Modifier.padding(top = 4.dp)) { Text("Explore with sample data", color = Muted) }
            Text("Get both from UsageNotch Link on Windows.", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
        }
    } }
}
@Composable private fun PasteDialog(busy: Boolean, problem: String, dismiss: () -> Unit, pair: (String) -> Unit) {
    var code by rememberSaveable { mutableStateOf("") }
    AlertDialog(onDismissRequest = dismiss, title = { Text("Paste pairing code") },
        text = { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("In UsageNotch Link, choose Copy pairing code and send it to yourself privately. It starts with UN1.", fontSize = 13.sp, color = Muted)
            OutlinedTextField(code, { code = it }, placeholder = { Text("UN1.…") }, minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth())
            if (problem.isNotBlank()) Text(problem, fontSize = 12.sp, color = Peach)
        } },
        confirmButton = { TextButton(onClick = { pair(code) }, enabled = code.isNotBlank() && !busy) { Text("Pair") } },
        dismissButton = { TextButton(onClick = dismiss) { Text("Cancel") } })
}
@Composable private fun ProviderCard(provider: Provider, now: Long, remaining: Boolean, clock24: Boolean, reduced: Boolean, modifier: Modifier = Modifier) {
    var expanded by rememberSaveable(provider.id) { mutableStateOf(false) }
    val color = providerColor(provider.id); val primary = provider.windows.firstOrNull()
    Glass(modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(39.dp).background(color.copy(alpha = .12f), RoundedCornerShape(13.dp)), contentAlignment = Alignment.Center) {
                val logo = when (providerKind(provider.id)) { "claude", "anthropic-api" -> R.drawable.logo_claude; "codex", "openai-api" -> R.drawable.logo_codex; "gemini" -> R.drawable.logo_gemini; "cursor" -> R.drawable.logo_cursor; else -> R.drawable.ic_notch }
                Icon(painterResource(logo), null, tint = color, modifier = Modifier.size(22.dp))
            }
            Column(Modifier.weight(1f).padding(start = 12.dp)) { Text(provider.name, fontSize = 19.sp, fontWeight = FontWeight.Medium); Text(primary?.let { readingState(provider, it, now) } ?: "Waiting for a reading", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 3.dp)) }
            Text("ON WINDOWS", color = Muted, fontSize = 8.sp, letterSpacing = 1.sp)
        }
        if (primary == null) Text("Open this provider in your Windows app to collect usage.", color = Muted, modifier = Modifier.padding(top = 20.dp))
        provider.windows.take(if (expanded) 32 else 2).forEachIndexed { index, w ->
            Spacer(Modifier.height(if (index == 0) 24.dp else 18.dp))
            val renewed = w.resetPassed(now)
            if (index == 0) Row(verticalAlignment = Alignment.CenterVertically) {
                UsageRing(w, remaining, renewed, color, reduced)
                Column(Modifier.weight(1f).padding(start = 20.dp)) {
                    Text(w.label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = color)
                    if (renewed) {
                        Text("Limit renewed", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                        Text("Reset at ${ClockText.time(w.reset!!, clock24)}", fontSize = 17.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 3.dp))
                        Text("New usage appears after your PC's next reading.", color = Muted, fontSize = 10.sp, lineHeight = 14.sp, modifier = Modifier.padding(top = 5.dp))
                    } else {
                        Text("Resets in", color = Muted, fontSize = 11.sp, modifier = Modifier.padding(top = 10.dp))
                        Text(ClockText.countdown(w.reset, now), fontSize = if (w.reset == null) 12.sp else 20.sp, fontWeight = FontWeight.Light, modifier = Modifier.padding(top = 3.dp))
                        w.reset?.let { Text(ClockText.stamp(it, clock24), color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp)) }
                    }
                }
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(w.label, color = Muted, fontSize = 12.sp, modifier = Modifier.weight(1f)); Text(if (renewed) "Renewed" else "${w.percent(remaining)}% ${if (remaining) "left" else "used"}", color = color, fontSize = 12.sp) }
                val progress by animateFloatAsState(if (renewed) 0f else (if (remaining) 1-w.used else w.used).toFloat().coerceIn(0f, 1f), tween(if (reduced) 0 else 500), label = "Window progress")
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(4.dp).clip(CircleShape), color = color, trackColor = color.copy(alpha = .1f))
                Text(w.reset?.let { if (renewed) "Reset at ${ClockText.stamp(it, clock24)} · awaiting a new PC reading" else "Resets ${ClockText.stamp(it, clock24)}" } ?: "Reset time not reported", fontSize = 10.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            }
        }
        if (primary != null) {
            Spacer(Modifier.height(22.dp)); HistoryChart(primary, color)
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) { Text("OBSERVED USAGE · 24H", fontSize = 8.sp, letterSpacing = 1.sp, color = Muted); Text(ClockText.age(primary.at, now), fontSize = 10.sp, color = Muted) }
            Row(Modifier.fillMaxWidth().padding(top = 9.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${primary.points.size} readings", fontSize = 10.sp, color = Muted)
                TextButton(onClick = { expanded = !expanded }) { Text(if (expanded) "Less detail" else "Reading details", fontSize = 11.sp, color = color); Icon(if (expanded) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, Modifier.size(17.dp), tint = color) }
            }
            AnimatedVisibility(expanded, enter = expandVertically(tween(if (reduced) 0 else 260)) + fadeIn(), exit = shrinkVertically(tween(if (reduced) 0 else 180)) + fadeOut()) {
                Column { HorizontalDivider(color = Ink.copy(alpha = .09f)); Text("Recorded ${ClockText.stamp(primary.at, clock24)}. Chart lines stop at missing intervals and reset boundaries. After a reset time passes, the window shows Renewed until your PC reports new usage.\n\nPC provider status: ${provider.status}. Percentages are observed quota usage, not request or token counts.", color = Muted, fontSize = 12.sp, lineHeight = 19.sp, modifier = Modifier.padding(top = 14.dp)) }
            }
        }
    } }
}
@Composable private fun UsageRing(window: UsageWindow, remaining: Boolean, renewed: Boolean, color: Color, reduced: Boolean) {
    var entered by remember { mutableStateOf(false) }; LaunchedEffect(Unit) { entered = true }
    val target = if (renewed) 0f else (if (remaining) 1-window.used else window.used).toFloat().coerceIn(0f, 1f)
    val progress by animateFloatAsState(if (entered) target else 0f, if (reduced) snap() else spring(dampingRatio = 1f, stiffness = 85f), label = "Usage ring")
    val spoken = if (renewed) "${window.label}: limit renewed, awaiting a new reading" else "${window.label}: ${window.percent(remaining)} percent ${if (remaining) "remaining" else "used"}"
    Box(Modifier.size(112.dp).semantics(mergeDescendants = true) { contentDescription = spoken }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(5.dp)) {
            val stroke = 6.dp.toPx(); drawArc(color.copy(alpha = if (renewed) .28f else .10f), 135f, 270f, false, style = Stroke(stroke, cap = StrokeCap.Round))
            if (progress > 0f) drawArc(Brush.sweepGradient(listOf(color.copy(alpha = .5f), color, color.copy(alpha = .7f))), 135f, progress * 270f, false, style = Stroke(stroke, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (renewed) { Icon(Icons.Outlined.Autorenew, null, tint = color, modifier = Modifier.size(26.dp)); Text("renewed", color = Muted, fontSize = 10.sp, modifier = Modifier.padding(top = 2.dp)) }
            else { Text("${window.percent(remaining)}%", fontSize = 29.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp); Text(if (remaining) "remaining" else "used", color = Muted, fontSize = 10.sp) }
        }
    }
}
@Composable private fun HistoryChart(window: UsageWindow, color: Color) {
    val points = window.points
    if (points.size < 2) { Box(Modifier.fillMaxWidth().height(48.dp), contentAlignment = Alignment.CenterStart) { Text("Your history will appear as readings arrive.", fontSize = 11.sp, color = Muted) }; return }
    Canvas(Modifier.fillMaxWidth().height(56.dp).semantics { contentDescription = "${window.label} recorded usage chart, ${points.size} readings. Gaps and resets are not connected." }) {
        val start = window.at - 86_400_000; val span = 86_400_000.0
        for (i in 0..2) drawLine(Ink.copy(alpha = .05f), Offset(0f, size.height * i / 2), Offset(size.width, size.height * i / 2), 1.dp.toPx())
        fun xy(p: Reading) = Offset(((p.at-start)/span * size.width).toFloat().coerceIn(0f, size.width), size.height * (1 - p.used.toFloat().coerceIn(0f, 1f)))
        points.zipWithNext().forEach { (a,b) -> if (a.period == b.period && b.at-a.at in 1..1_200_000) drawLine(color.copy(alpha = .8f), xy(a), xy(b), 2.dp.toPx(), StrokeCap.Round) }
        points.forEach { drawCircle(color.copy(alpha = .7f), 1.6.dp.toPx(), xy(it)) }
    }
}
@Composable private fun Notice(title: String, body: String, action: String? = null, secondary: String? = null, clicked: () -> Unit = {}, tone: Color = Peach, secondaryClicked: () -> Unit = {}) {
    Glass(Modifier.fillMaxWidth(), 20.dp) { Column(Modifier.padding(18.dp)) {
        Text(title, fontSize = 13.sp, color = tone, fontWeight = FontWeight.Medium); Text(body, color = Muted, fontSize = 12.sp, lineHeight = 18.sp, modifier = Modifier.padding(top = 6.dp))
        if (action != null || secondary != null) Row { if (action != null) TextButton(onClick = clicked) { Text(action) }; if (secondary != null) TextButton(onClick = secondaryClicked) { Text(secondary, color = Muted) } }
    } }
}
@Composable private fun SectionLabel(text: String) { Text(text, fontSize = 10.sp, letterSpacing = 2.sp, color = Muted, modifier = Modifier.padding(start = 6.dp, top = 4.dp)) }
@Composable private fun SettingToggle(title: String, detail: String, value: Boolean, changed: (Boolean) -> Unit) {
    // The whole row is the touch target, not only the switch.
    Row(Modifier.fillMaxWidth().toggleable(value, role = Role.Switch, onValueChange = changed).semantics { contentDescription = title }.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f).padding(end = 12.dp)) { Text(title, fontSize = 15.sp); Text(detail, fontSize = 11.sp, color = Muted, modifier = Modifier.padding(top = 4.dp)) }; Switch(checked = value, onCheckedChange = null) }
}
@Composable private fun WidgetShowcase(title: String, detail: String, focus: Boolean, snapshot: Snapshot?, remaining: Boolean, add: () -> Unit) {
    Glass(Modifier.fillMaxWidth()) { Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.Light)
        Glass(Modifier.fillMaxWidth().padding(horizontal = if (focus) 28.dp else 0.dp), 23.dp) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("UsageNotch", fontSize = 13.sp, fontWeight = FontWeight.Medium); Icon(Icons.Outlined.Refresh, null, tint = Mint, modifier = Modifier.size(17.dp)) }
            val providers = snapshot?.providers.orEmpty().take(if (focus) 1 else 2)
            if (providers.isEmpty()) Text("Your paired readings appear here.", color = Muted, fontSize = 12.sp)
            val now = System.currentTimeMillis()
            providers.forEach { p -> p.windows.take(if (focus) 2 else 1).forEach { w -> val renewed = w.resetPassed(now); Column { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text(p.name, fontSize = 12.sp); Text(if (renewed) "Renewed" else "${w.percent(remaining)}% ${if (remaining) "left" else "used"}", color = providerColor(p.id), fontSize = 12.sp) }; Text(w.label, color = Muted, fontSize = 10.sp); if (!renewed) LinearProgressIndicator(progress = { (if (remaining) 1-w.used else w.used).toFloat().coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp), color = providerColor(p.id)) } } }
        } }
        Text(detail, color = Muted, fontSize = 12.sp, lineHeight = 18.sp)
        OutlinedButton(onClick = add, modifier = Modifier.fillMaxWidth().height(48.dp)) { Icon(Icons.Outlined.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text("Add to home screen") }
        Text("If your launcher cannot pin widgets, long-press Home → Widgets → UsageNotch.", color = Muted, fontSize = 10.sp)
    } }
}
private fun pinWidget(context: android.content.Context, focus: Boolean) {
    val manager = AppWidgetManager.getInstance(context)
    val pinned = runCatching { manager != null && manager.isRequestPinAppWidgetSupported && manager.requestPinAppWidget(ComponentName(context, if (focus) FocusWidget::class.java else UsageWidget::class.java), null, null) }.getOrDefault(false)
    if (!pinned) android.widget.Toast.makeText(context, "Long-press Home → Widgets → UsageNotch", android.widget.Toast.LENGTH_LONG).show()
}
internal fun demoSnapshot(): Snapshot {
    val now = System.currentTimeMillis()
    fun window(id: String, label: String, used: Double, reset: Long) = UsageWindow(id, label, used, now, reset, (0..24).map { Reading(now - (24-it) * 600_000, used * (.35 + .65 * it/24), "preview") })
    return Snapshot(now, listOf(Provider("claude", "Claude", "Ok", listOf(window("five_hour", "5-hour window", .27, now+7_845_000), window("seven_day", "Weekly window", .41, now+231_845_000))), Provider("codex", "Codex", "Ok", listOf(window("primary", "5-hour window", .16, now+11_400_000), window("secondary", "Weekly window", .32, now+401_840_000)))))
}
