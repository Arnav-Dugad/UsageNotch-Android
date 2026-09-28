package io.github.arnavdugad.usagenotch

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Keeps the last crash on this phone so it can be shared from the app. Nothing is uploaded.
 * Repeated crashes shortly after launch switch the app into a plain safe mode.
 */
object CrashLog {
    private const val FILE = "last-crash.txt"
    private fun state(context: Context) = context.getSharedPreferences("crash", Context.MODE_PRIVATE)
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching { record(app, thread.name, error) }
            previous?.uncaughtException(thread, error)
        }
    }
    internal fun record(context: Context, thread: String, error: Throwable) {
        val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString().take(12_000)
        val report = buildString {
            appendLine("UsageNotch ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Time ${System.currentTimeMillis()} · thread $thread")
            appendLine(); append(trace)
        }
        File(context.filesDir, FILE).writeText(report)
        val prefs = state(context)
        // commit(): the process is about to die, so the write must finish now.
        prefs.edit().putInt("startupCrashes", prefs.getInt("startupCrashes", 0) + if (prefs.getBoolean("starting", true)) 1 else 0).commit()
    }
    fun report(context: Context): String? = runCatching { File(context.filesDir, FILE).takeIf { it.exists() }?.readText() }.getOrNull()
    fun clear(context: Context) { runCatching { File(context.filesDir, FILE).delete() }; state(context).edit().putInt("startupCrashes", 0).apply() }
    /** Two consecutive crashes before the dashboard settled: show the safe screen instead. */
    fun inSafeMode(context: Context) = state(context).getInt("startupCrashes", 0) >= 2
    fun launching(context: Context) { state(context).edit().putBoolean("starting", true).apply() }
    fun settled(context: Context) { state(context).edit().putBoolean("starting", false).putInt("startupCrashes", 0).apply() }
}
