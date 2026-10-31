package io.github.arnavdugad.usagenotch

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URI
import java.net.URL
import java.security.MessageDigest
import javax.net.ssl.HttpsURLConnection

/**
 * Updates for a sideloaded app: it checks GitHub Releases at most twice a day and can install the new version itself.
 *
 * A download is accepted only when every check passes: HTTPS from GitHub's own hosts (redirects followed by hand), a size
 * within bounds that matches the release, the SHA-256 listed in the release's SHA256SUMS.txt, the same package, a
 * higher version code, and a signing certificate identical to this installed app's (and to UsageNotch's release key).
 * The install goes through Android's PackageInstaller: the first time Android asks you to allow UsageNotch to install
 * apps and to confirm; once UsageNotch is the app that installed itself, Android 12+ lets later updates install
 * without asking.
 */
object Updates {
    const val RELEASES = "https://github.com/Arnav-Dugad/UsageNotch-Android/releases/latest"
    private const val API = "https://api.github.com/repos/Arnav-Dugad/UsageNotch-Android/releases/latest"
    /** SHA-256 of the UsageNotch release signing certificate. */
    const val RELEASE_CERT = "ddafa1fb472f7ee28c91c7badab1930e5ba9b8a7e78b96ad33bec36798148091"
    const val MAX_APK_BYTES = 64L * 1024 * 1024
    private val HOSTS = setOf("github.com", "api.github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com")
    private const val JOB = 31
    const val ACTION_STATUS = "io.github.arnavdugad.usagenotch.UPDATE_STATUS"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION = 31

    /** What the Update button shows, observed by the UI. */
    var progress by mutableStateOf<Float?>(null)
    var message by mutableStateOf("")
    /** True while an activity is on screen, so the install confirmation can open directly instead of as a notification. */
    @Volatile var foreground = false

    fun enabled(context: Context) = Repository(context).prefs.getBoolean("checkUpdates", true)
    fun automatic(context: Context) = Repository(context).prefs.getBoolean("autoUpdate", true)
    fun setAutomatic(context: Context, on: Boolean) { Repository(context).prefs.edit().putBoolean("autoUpdate", on).apply(); schedule(context) }

    /** The newer version name, or null when this build is current or checking is off. */
    fun available(context: Context): String? {
        val latest = Repository(context).prefs.getString("latestVersion", null) ?: return null
        return latest.takeIf { enabled(context) && newer(it, BuildConfig.VERSION_NAME) }
    }

    data class Release(val version: String, val apkUrl: String, val apkSize: Long, val sumsUrl: String?, val apkName: String)

    /** The release's APK and checksum file, from GitHub's release JSON. Null when it has no usable APK. */
    internal fun parseRelease(json: String): Release? = runCatching {
        val root = JSONObject(json)
        val version = root.getString("tag_name").removePrefix("v").takeIf { version(it) != null } ?: return null
        val assets = root.optJSONArray("assets") ?: return null
        var apk: Triple<String, String, Long>? = null; var sums: String? = null
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i); val name = a.optString("name"); val url = a.optString("browser_download_url")
            if (!allowedUrl(url)) continue
            if (name == "UsageNotch-$version.apk") apk = Triple(name, url, a.optLong("size", -1))
            if (name == "SHA256SUMS.txt") sums = url
        }
        apk?.takeIf { it.third in 1..MAX_APK_BYTES }?.let { Release(version, it.second, it.third, sums, it.first) }
    }.getOrNull()

    /** Only HTTPS to GitHub and its release download hosts, on the default port, with no credentials in the URL. */
    internal fun allowedUrl(url: String?): Boolean = runCatching {
        val u = URI(url ?: return false)
        u.scheme == "https" && u.host?.lowercase() in HOSTS && (u.port == -1 || u.port == 443) && u.userInfo == null
    }.getOrDefault(false)

    /** The SHA-256 listed for [name] in a `sha256sum` file (text or binary mode), lower-case, or null. */
    internal fun checksumFor(sums: String, name: String): String? = sums.lineSequence().mapNotNull { line ->
        Regex("^([0-9a-fA-F]{64})\\s+\\*?(\\S+)\\s*$").matchEntire(line.trim())?.groupValues
    }.firstOrNull { it[2] == name }?.get(1)?.lowercase()

    internal fun sha256(input: InputStream): String {
        val digest = MessageDigest.getInstance("SHA-256"); val buffer = ByteArray(64 * 1024)
        while (true) { val n = input.read(buffer); if (n < 0) break; digest.update(buffer, 0, n) }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    internal fun version(text: String) = Regex("(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})").matchEntire(text)?.groupValues?.drop(1)?.map { it.toInt() }
    internal fun newer(candidate: String, current: String): Boolean {
        val a = version(candidate) ?: return false; val b = version(current) ?: return false
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }

    /** Opens [url] following at most five redirects, each of which must stay on an allowed host. */
    private fun open(url: String): HttpsURLConnection {
        var current = url
        repeat(6) {
            require(allowedUrl(current)) { "Updates come only from GitHub." }
            val c = URL(current).openConnection() as HttpsURLConnection
            c.connectTimeout = 10_000; c.readTimeout = 20_000; c.instanceFollowRedirects = false
            c.setRequestProperty("Accept", if (current.startsWith(API)) "application/vnd.github+json" else "application/octet-stream")
            val code = c.responseCode
            if (code in 300..399) { val next = c.getHeaderField("Location"); c.disconnect(); current = URI(current).resolve(next ?: throw IOException("Bad redirect")).toString(); return@repeat }
            if (code != 200) { c.disconnect(); throw IOException("GitHub answered $code") }
            return c
        }
        throw IOException("Too many redirects")
    }

    /** Thrown when GitHub couldn't be asked at all, so the app never claims to be up to date without knowing. */
    class CheckFailed(message: String) : IOException(message)

    /**
     * Asks GitHub for the latest release, at most twice a day unless [force]. The release API allows only 60 requests an
     * hour per network (a campus or office can use that up), so when it refuses, the release page's redirect gives the
     * version instead and the assets come from their standard download links. Checksum and signature checks are the same.
     */
    suspend fun check(context: Context, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        val prefs = Repository(context).prefs; val now = System.currentTimeMillis()
        if (!enabled(context)) return@withContext null
        if (!force && now - prefs.getLong("updateChecked", 0) in 0..43_200_000) return@withContext stored(context)
        val release = runCatching {
            val c = open(API)
            try { parseRelease(c.inputStream.use { String(readBounded(it, 262_144), Charsets.UTF_8) }) } finally { c.disconnect() }
        }.getOrNull() ?: runCatching { fromReleasePage() }.getOrNull()
            ?: throw CheckFailed("Couldn't reach GitHub to check for updates. Try again later.")
        prefs.edit().putLong("updateChecked", now).putString("latestVersion", release.version).putString("latestApk", release.apkUrl).putLong("latestSize", release.apkSize)
            .putString("latestSums", release.sumsUrl).putString("latestName", release.apkName).apply()
        release
    }

    /** The latest version from github.com/…/releases/latest, which redirects to …/releases/tag/vX.Y.Z and isn't rate-limited. */
    private fun fromReleasePage(): Release? {
        val c = URL(RELEASES).openConnection() as HttpsURLConnection
        c.connectTimeout = 10_000; c.readTimeout = 15_000; c.instanceFollowRedirects = false
        val location = try { c.responseCode; c.getHeaderField("Location") } finally { c.disconnect() }
        return releaseFromTagUrl(location)
    }

    internal fun releaseFromTagUrl(location: String?): Release? {
        val tag = Regex("/releases/tag/v(\\d{1,4}\\.\\d{1,4}\\.\\d{1,4})$").find(location ?: return null)?.groupValues?.get(1) ?: return null
        val base = "https://github.com/Arnav-Dugad/UsageNotch-Android/releases/download/v$tag/"
        return Release(tag, base + "UsageNotch-$tag.apk", -1, base + "SHA256SUMS.txt", "UsageNotch-$tag.apk")
    }
    private fun stored(context: Context): Release? {
        val p = Repository(context).prefs
        return Release(p.getString("latestVersion", null) ?: return null, p.getString("latestApk", null) ?: return null, p.getLong("latestSize", -1), p.getString("latestSums", null), p.getString("latestName", null) ?: return null)
    }

    /** The SHA-256 of a certificate, as GitHub's release notes and apksigner print it. */
    private fun certSha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Suppress("DEPRECATION")
    private fun signers(info: PackageInfo?): List<String> = when {
        info == null -> emptyList()
        Build.VERSION.SDK_INT >= 28 && info.signingInfo != null -> (if (info.signingInfo!!.hasMultipleSigners()) info.signingInfo!!.apkContentsSigners else info.signingInfo!!.signingCertificateHistory).map { certSha(it.toByteArray()) }
        else -> info.signatures.orEmpty().map { certSha(it.toByteArray()) }
    }

    /** Checks a downloaded APK against this installed app. Returns null when it may be installed, otherwise why not. */
    @Suppress("DEPRECATION")
    fun verify(context: Context, apk: File): String? {
        val pm = context.packageManager
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val candidate = runCatching { pm.getPackageArchiveInfo(apk.path, flags) }.getOrNull() ?: return "The download isn't a valid app."
        if (candidate.packageName != context.packageName) return "The download is a different app."
        val installed = pm.getPackageInfo(context.packageName, flags)
        val newCode = if (Build.VERSION.SDK_INT >= 28) candidate.longVersionCode else candidate.versionCode.toLong()
        val oldCode = if (Build.VERSION.SDK_INT >= 28) installed.longVersionCode else installed.versionCode.toLong()
        if (newCode <= oldCode) return "The download isn't newer than this version."
        // Archives don't always report signing details through the newer API; fall back to the older one.
        val newSigners = signers(candidate).ifEmpty { signers(runCatching { pm.getPackageArchiveInfo(apk.path, PackageManager.GET_SIGNATURES) }.getOrNull()) }
        val mine = signers(installed)
        if (newSigners.isEmpty() || newSigners.toSet() != mine.toSet()) return "The download isn't signed like this app, so it wasn't installed."
        if (RELEASE_CERT !in mine) return "This build of UsageNotch can't update itself; install the release from GitHub."
        return null
    }

    /** Downloads, checks and returns the APK, reporting progress. Throws with a readable message when a check fails. */
    suspend fun download(context: Context, release: Release, onProgress: (Float) -> Unit = {}): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { if (it.name != release.apkName) it.delete() }
        val expected = release.sumsUrl?.let { url ->
            val c = open(url)
            try { checksumFor(c.inputStream.use { String(readBounded(it, 16_384), Charsets.UTF_8) }, release.apkName) } finally { c.disconnect() }
        } ?: throw IOException("The release has no checksum for the download.")
        val file = File(dir, release.apkName)
        if (!(file.exists() && (release.apkSize <= 0 || file.length() == release.apkSize) && file.inputStream().use { sha256(it) } == expected)) {
            val c = open(release.apkUrl)
            try {
                val length = c.contentLengthLong
                if (length > MAX_APK_BYTES || (length > 0 && release.apkSize > 0 && length != release.apkSize)) throw IOException("The download has an unexpected size.")
                val size = if (release.apkSize > 0) release.apkSize else if (length > 0) length else MAX_APK_BYTES.toLong()
                val partial = File(dir, release.apkName + ".part")
                c.inputStream.use { input -> partial.outputStream().use { out ->
                    val buffer = ByteArray(64 * 1024); var total = 0L
                    while (true) {
                        val n = input.read(buffer); if (n < 0) break
                        total += n; if (total > MAX_APK_BYTES || total > size) throw IOException("The download is larger than the release.")
                        out.write(buffer, 0, n); onProgress((total.toFloat() / size).coerceIn(0f, 1f))
                    }
                } }
                if ((release.apkSize > 0 && partial.length() != release.apkSize) || (length > 0 && partial.length() != length)) { partial.delete(); throw IOException("The download was incomplete.") }
                partial.renameTo(file)
            } finally { c.disconnect() }
        }
        if (file.inputStream().use { sha256(it) } != expected) { file.delete(); throw IOException("The download didn't match its published checksum, so it was deleted.") }
        verify(context, file)?.let { file.delete(); throw IOException(it) }
        file
    }

    /** Hands a verified APK to Android's installer. The result arrives in [UpdateInstallReceiver]. */
    fun install(context: Context, apk: File) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName); setSize(apk.length())
            if (Build.VERSION.SDK_INT >= 26) setInstallReason(PackageManager.INSTALL_REASON_USER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            session.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; session.fsync(out) }
            // Mutable so the installer can add the status; explicit, so only this app's receiver gets it.
            val status = PendingIntent.getBroadcast(context, JOB, Intent(context, UpdateInstallReceiver::class.java).setAction(ACTION_STATUS),
                PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0))
            session.commit(status.intentSender)
        }
    }

    /** Whether Android already lets UsageNotch install apps (Settings → Install unknown apps). */
    fun canInstall(context: Context) = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()
    fun allowInstallsIntent(context: Context) = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + context.packageName))

    /** The Update button: check, download, verify and install, reporting progress in [progress] and [message]. */
    fun updateNow(context: Context, scope: CoroutineScope) {
        if (progress != null) return
        val app = context.applicationContext
        scope.launch {
            progress = 0f; message = "Checking the release…"
            try {
                val release = check(app, force = true)?.takeIf { newer(it.version, BuildConfig.VERSION_NAME) }
                if (release == null) { message = "UsageNotch is up to date."; return@launch }
                message = "Downloading ${release.version}…"
                val apk = download(app, release) { progress = it }
                message = "Installing ${release.version}…"
                install(app, apk)
            } catch (e: Exception) { message = e.message ?: "The update couldn't be installed." }
            finally { progress = null }
        }
    }

    /** Daily on unmetered networks while automatic updates are on; cancelled otherwise. */
    fun schedule(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (!enabled(context) || !automatic(context)) { scheduler.cancel(JOB); return }
        if (scheduler.getPendingJob(JOB) != null) return
        runCatching {
            scheduler.schedule(JobInfo.Builder(JOB, ComponentName(context, UpdateJobService::class.java))
                .setPeriodic(12 * 3_600_000L).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setRequiresBatteryNotLow(true).setPersisted(true).build())
        }
    }

    internal fun notifyReady(context: Context, confirm: Intent?, version: String?) {
        if (!ResetAlerts.canNotify(context)) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL, "App updates", NotificationManager.IMPORTANCE_DEFAULT).apply { description = "When a new version of UsageNotch is ready to install." })
        val open = confirm?.let { PendingIntent.getActivity(context, NOTIFICATION, it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT) }
            ?: PendingIntent.getActivity(context, NOTIFICATION, Intent(context, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        manager.notify(NOTIFICATION, android.app.Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_stat_notch)
            .setContentTitle("UsageNotch ${version ?: ""} is ready".replace("  ", " ")).setContentText("Tap to install the update. It was checked against the published release.")
            .setContentIntent(open).setAutoCancel(true).build())
    }
}

/** Results from Android's installer: asks you to confirm when needed, and records failures for Settings. */
class UpdateInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Updates.ACTION_STATUS) return
        val prefs = Repository(context).prefs
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java) else @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
                if (Updates.foreground && confirm != null) runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.onFailure { Updates.notifyReady(context, confirm, prefs.getString("latestVersion", null)) }
                else Updates.notifyReady(context, confirm, prefs.getString("latestVersion", null))
            }
            PackageInstaller.STATUS_SUCCESS -> prefs.edit().remove("updateError").apply()
            else -> {
                val text = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                prefs.edit().putString("updateError", if (status == PackageInstaller.STATUS_FAILURE_ABORTED) "The update was cancelled." else "The update couldn't be installed" + (text?.let { ": $it" } ?: ".")).apply()
                Updates.message = prefs.getString("updateError", "") ?: ""
            }
        }
    }
}

/** Background check and download on Wi-Fi; installs straight away when Android allows, otherwise posts "Update ready". */
class UpdateJobService : JobService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    override fun onStartJob(params: JobParameters): Boolean {
        scope.launch {
            try {
                val release = Updates.check(applicationContext)?.takeIf { Updates.newer(it.version, BuildConfig.VERSION_NAME) }
                if (release != null && Updates.automatic(applicationContext)) {
                    val apk = Updates.download(applicationContext, release)
                    if (Updates.canInstall(applicationContext)) Updates.install(applicationContext, apk) else Updates.notifyReady(applicationContext, null, release.version)
                }
            } catch (_: Throwable) { }
            jobFinished(params, false)
        }
        return true
    }
    override fun onStopJob(params: JobParameters) = true
    override fun onDestroy() { scope.cancel(); super.onDestroy() }
}
