package io.github.arnavdugad.usagenotch

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The phone's history store on Android 9, the oldest supported version: SQLiteOpenHelper only became AutoCloseable in
 * Android 10, so the store must open and close itself without `use`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class LocalHistoryApi28Test {
    @Test fun savesLoadsAndDeletesOnAndroid9() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        val snapshot = Snapshot(1L, listOf(Provider("claude", "Claude", "Ok", emptyList(),
            history = listOf(History("five_hour", "Current session", listOf(Day("2026-11-08", .5, .8), Day("2026-11-09", .25)), List(168) { 0.0 }, List(168) { 0 }, 2)))))
        LocalHistory.open(context) { db ->
            db.clear()
            assertEquals(2, db.save(snapshot, 1L))
            assertEquals(StoredStats(2, 2, "2026-11-08"), db.stats())
            assertEquals(2, db.load().days("claude", "five_hour").size)
        }
        LocalHistory.open(context) { db -> db.clear(); assertEquals(0, db.stats().days) }
    }
}
