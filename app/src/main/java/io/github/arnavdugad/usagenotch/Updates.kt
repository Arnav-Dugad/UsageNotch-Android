package io.github.arnavdugad.usagenotch

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/** Sideloaded APKs get no store updates, so the app asks GitHub Releases at most twice a day. */
object Updates {
    const val RELEASES = "https://github.com/Arnav-Dugad/UsageNotch-Android/releases/latest"
    private const val API = "https://api.github.com/repos/Arnav-Dugad/UsageNotch-Android/releases/latest"
    fun enabled(context: Context) = Repository(context).prefs.getBoolean("checkUpdates", true)
    /** The newer version name, or null when this build is current or checking is off. */
    fun available(context: Context): String? {
        val latest = Repository(context).prefs.getString("latestVersion", null) ?: return null
        return latest.takeIf { enabled(context) && newer(it, BuildConfig.VERSION_NAME) }
    }
    suspend fun check(context: Context) = withContext(Dispatchers.IO) {
        val prefs = Repository(context).prefs; val now = System.currentTimeMillis()
        if (!enabled(context) || now - prefs.getLong("updateChecked", 0) in 0..43_200_000) return@withContext
        prefs.edit().putLong("updateChecked", now).apply()
        runCatching {
            val connection = URL(API).openConnection() as HttpsURLConnection
            try {
                connection.connectTimeout = 8_000; connection.readTimeout = 8_000; connection.instanceFollowRedirects = false
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                if (connection.responseCode != 200) return@runCatching
                val tag = JSONObject(connection.inputStream.use { String(readBounded(it, 262_144), Charsets.UTF_8) }).getString("tag_name").removePrefix("v")
                if (version(tag) != null) prefs.edit().putString("latestVersion", tag).apply()
            } finally { connection.disconnect() }
        }
    }
    internal fun version(text: String) = Regex("(\\d{1,4})\\.(\\d{1,4})\\.(\\d{1,4})").matchEntire(text)?.groupValues?.drop(1)?.map { it.toInt() }
    internal fun newer(candidate: String, current: String): Boolean {
        val a = version(candidate) ?: return false; val b = version(current) ?: return false
        for (i in 0..2) if (a[i] != b[i]) return a[i] > b[i]
        return false
    }
}
