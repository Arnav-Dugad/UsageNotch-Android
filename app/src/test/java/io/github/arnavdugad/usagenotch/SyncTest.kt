package io.github.arnavdugad.usagenotch

import android.app.Application
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Two-way settings sync: last writer wins, PC wins ties, tombstones reset, and nothing loops. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = Application::class)
class SyncTest {
    private lateinit var prefs: SharedPreferences
    @Before fun clear() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        listOf("usage", "display", "pairing-vault").forEach { context.getSharedPreferences(it, 0).edit().clear().commit() }
        prefs = context.getSharedPreferences("display", 0)
    }
    private fun budget(limit: Int, by: Int? = null) = JSONObject().put("limit", limit).put("by", by ?: JSONObject.NULL)

    @Test fun localEditsArePendingUntilThePcHasThem() {
        Budgets.set(prefs, Budget("claude", "five_hour", 60, 1080))
        assertEquals(listOf(Budget("claude", "five_hour", 60, 1080)), Budgets.all(prefs))
        assertEquals(setOf("budget:claude|five_hour"), SharedSettings.pending(prefs))
        val body = JSONObject(SharedSettings.payload(prefs)!!).getJSONObject("shared")
        assertEquals(60, body.getJSONObject("budget:claude|five_hour").getJSONObject("v").getInt("limit"))
        val mine = SharedSettings.entries(prefs).getValue("budget:claude|five_hour")
        // The PC answers with the same entry: nothing changes and it stops being pending.
        assertTrue(SharedSettings.merge(prefs, mapOf("budget:claude|five_hour" to mine)).isEmpty())
        assertTrue(SharedSettings.pending(prefs).isEmpty()); assertNull(SharedSettings.payload(prefs))
    }
    @Test fun newerWinsOlderIsIgnoredAndThePcWinsTies() {
        SharedSettings.local(prefs, "budget:claude|five_hour", budget(60), now = 1_000)
        assertTrue(SharedSettings.merge(prefs, mapOf("budget:claude|five_hour" to SharedEntry(budget(30), 500))).isEmpty())
        assertEquals(60, Budgets.all(prefs).single().limit)
        assertEquals(setOf("budget:claude|five_hour"), SharedSettings.merge(prefs, mapOf("budget:claude|five_hour" to SharedEntry(budget(40), 1_000))))
        assertEquals(40, Budgets.all(prefs).single().limit)
        assertTrue(SharedSettings.pending(prefs).isEmpty())
        // A tombstone from the PC removes it here too.
        SharedSettings.merge(prefs, mapOf("budget:claude|five_hour" to SharedEntry(null, 2_000)))
        assertTrue(Budgets.all(prefs).isEmpty())
    }
    @Test fun syncedSettingsChangeWhatThePhoneShows() {
        SharedSettings.merge(prefs, mapOf(
            "accent:claude" to SharedEntry("#7A5CFF", 10), "name:codex" to SharedEntry("Work", 10),
            "order" to SharedEntry(JSONArray(listOf("codex", "claude")), 10),
            "alerts:thresholds" to SharedEntry(JSONObject().put("warn", 70).put("critical", 90), 10),
            "alerts:quiet" to SharedEntry(JSONObject().put("on", true).put("start", 23).put("end", 6), 10)))
        assertEquals(0xFF7A5CFF.toInt(), Accents.of(prefs, "claude"))
        assertEquals(listOf(90, 70), AlertPrefs.thresholds(prefs)); assertTrue(AlertPrefs.quietOn(prefs)); assertEquals(23, AlertPrefs.quietStart(prefs))
        val s = SharedSettings.present(Snapshot(0, listOf(Provider("claude", "Claude", "Ok", emptyList()), Provider("gemini", "Gemini", "Ok", emptyList()), Provider("codex", "Codex", "Ok", emptyList()))), prefs)
        assertEquals(listOf("codex", "claude", "gemini"), s.providers.map { it.id })
        assertEquals("Work", s.providers.first().name)
        assertTrue("applying the PC's settings records nothing to send back", SharedSettings.pending(prefs).isEmpty())
    }
    @Test fun phoneColoursUseThePcsProviderIds() {
        Accents.set(prefs, "openai_api", 0xFF34C759.toInt())
        assertEquals(setOf("accent:openai_api"), SharedSettings.pending(prefs))
        assertEquals(0xFF34C759.toInt(), Accents.of(prefs, "openai-api"))
        Accents.set(prefs, "claude:work", 0xFFFF2D92.toInt())
        assertEquals(0xFFFF2D92.toInt(), Accents.of(prefs, "claude:work")); assertEquals(Accents.default("claude"), Accents.of(prefs, "claude"))
        Accents.set(prefs, "claude:work", null)
        assertEquals("an extra account without its own colour uses its kind's", Accents.of(prefs, "claude"), Accents.of(prefs, "claude:work"))
    }
    @Test fun settingsFromBeforeSyncAreCarriedOverOnce() {
        // 1.4 wrote budgets and colours straight into preferences.
        Budgets.store(prefs, listOf(Budget("claude", "five_hour", 70, null))); prefs.edit().putInt("accent-codex", 0xFF32ADE6.toInt()).commit()
        SharedSettings.seed(prefs, now = 5)
        assertEquals(setOf("budget:claude|five_hour", "accent:codex"), SharedSettings.pending(prefs))
        assertEquals("#32ADE6", SharedSettings.entries(prefs).getValue("accent:codex").v)
        SharedSettings.merge(prefs, SharedSettings.entries(prefs))
        SharedSettings.seed(prefs, now = 6)
        assertTrue("seeded only once", SharedSettings.pending(prefs).isEmpty())
    }
}
