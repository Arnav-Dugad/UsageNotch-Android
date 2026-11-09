package io.github.arnavdugad.usagenotch

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import java.io.Writer
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale

/** One saved day: usage in limits (null when the PC had no readings that day) and how much of the day the PC observed. */
data class StoredDay(val provider: String, val window: String, val date: String, val used: Double?, val coverage: Double?)
/** A provider window this phone has saved days for, with the names the PC last used. */
data class StoredSeries(val provider: String, val window: String, val name: String, val label: String)
/** What's saved on this phone: days with data, rows, and the first date. */
data class StoredStats(val days: Int, val rows: Int, val since: String?)
/** Everything saved on this phone, loaded once for the History tab. */
class StoredHistory(val series: List<StoredSeries>, private val days: Map<Pair<String, String>, List<Day>>) {
    fun days(provider: String, window: String): List<Day> = days[provider to window].orEmpty()
    val isEmpty get() = days.isEmpty()
    companion object { val Empty = StoredHistory(emptyList(), emptyMap()) }
}

/**
 * This phone's own permanent copy of your daily totals. Every snapshot's days (the PC's 30-day `days` and 90-day
 * `calendar`) are saved here and kept until you delete them in Settings, so History reaches back further than the PC
 * sends and still works while the PC is off or after you disconnect. Only dates, window ids, provider names and
 * fractions are stored; no account names, tokens or readings.
 */
class LocalHistory(context: Context) : SQLiteOpenHelper(context.applicationContext, FILE, null, 2) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE days (provider TEXT NOT NULL, window TEXT NOT NULL, date TEXT NOT NULL, used REAL, coverage REAL, updated INTEGER NOT NULL, PRIMARY KEY(provider, window, date))")
        db.execSQL("CREATE TABLE series (provider TEXT NOT NULL, window TEXT NOT NULL, name TEXT NOT NULL, label TEXT NOT NULL, updated INTEGER NOT NULL, PRIMARY KEY(provider, window))")
        createNotes(db)
    }
    /** Version 2 (1.9) adds your day notes. Saved days are kept. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { if (oldVersion < 2) createNotes(db) }
    private fun createNotes(db: SQLiteDatabase) =
        db.execSQL("CREATE TABLE IF NOT EXISTS notes (date TEXT PRIMARY KEY, text TEXT NOT NULL, at INTEGER NOT NULL, pending INTEGER NOT NULL DEFAULT 0)")

    /** Every note, deleted ones included, by date. */
    fun allNotes(): Map<String, StoredNote> = readableDatabase.rawQuery("SELECT date, text, at, pending FROM notes", null).use { c ->
        buildMap { while (c.moveToNext()) put(c.getString(0), StoredNote(c.getString(0), c.getString(1), c.getLong(2), c.getInt(3) != 0)) }
    }
    /** Your notes by date, without deleted ones. */
    fun notes(): Map<String, String> = allNotes().values.filter { it.text.isNotEmpty() }.associate { it.date to it.text }
    fun pendingNotes(): List<StoredNote> = allNotes().values.filter { it.pending }

    /** Writes, edits or (with blank [text]) deletes the note for [date] on this phone; it waits as pending for the PC. */
    fun setNote(date: String, text: String, now: Long = System.currentTimeMillis()): StoredNote {
        require(validDate(date))
        val note = StoredNote(date, DayNotes.clean(text), DayNotes.stamp(allNotes()[date], now), pending = true)
        writeNotes(listOf(note))
        return note
    }

    /** Takes the PC's notes (last writer wins, ties to the PC). Returns the dates whose text changed. */
    fun mergeNotes(incoming: List<DayNote>): Set<String> {
        if (incoming.isEmpty()) return emptySet()
        val mine = allNotes()
        val (merged, changed) = DayNotes.merge(mine, incoming.filter { validDate(it.date) })
        writeNotes(merged.values.filter { it != mine[it.date] })
        return changed
    }

    private fun writeNotes(notes: List<StoredNote>) {
        if (notes.isEmpty()) return
        val db = writableDatabase
        db.beginTransaction()
        try {
            notes.forEach { n ->
                db.insertWithOnConflict("notes", null, ContentValues().apply { put("date", n.date); put("text", n.text); put("at", n.at); put("pending", if (n.pending) 1 else 0) }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
    }

    /** Saves every day in [snapshot]; returns how many rows were written. The PC's latest value for a date wins, but a day without readings never erases a saved one. */
    fun save(snapshot: Snapshot, now: Long = System.currentTimeMillis()): Int {
        val incoming = rows(snapshot)
        if (incoming.isEmpty()) return 0
        val db = writableDatabase; var written = 0
        db.beginTransaction()
        try {
            for (p in snapshot.providers) for (h in p.history) {
                db.insertWithOnConflict("series", null, ContentValues().apply {
                    put("provider", p.id); put("window", h.window); put("name", p.name.take(60)); put("label", h.label.take(80)); put("updated", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
            }
            for (day in incoming) {
                val existing = db.rawQuery("SELECT used FROM days WHERE provider=? AND window=? AND date=?", arrayOf(day.provider, day.window, day.date)).use { c ->
                    if (!c.moveToFirst()) Existing.None else if (c.isNull(0)) Existing.Empty else Existing.Value
                }
                if (!shouldWrite(existing, day)) continue
                db.insertWithOnConflict("days", null, ContentValues().apply {
                    put("provider", day.provider); put("window", day.window); put("date", day.date)
                    if (day.used == null) putNull("used") else put("used", day.used)
                    if (day.coverage == null) putNull("coverage") else put("coverage", day.coverage)
                    put("updated", now)
                }, SQLiteDatabase.CONFLICT_REPLACE)
                written++
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        return written
    }

    fun load(): StoredHistory {
        val db = readableDatabase
        val series = db.rawQuery("SELECT provider, window, name, label FROM series ORDER BY rowid", null).use { c ->
            buildList { while (c.moveToNext()) add(StoredSeries(c.getString(0), c.getString(1), c.getString(2), c.getString(3))) }
        }
        val days = mutableMapOf<Pair<String, String>, MutableList<Day>>()
        db.rawQuery("SELECT provider, window, date, used, coverage FROM days ORDER BY provider, window, date", null).use { c ->
            while (c.moveToNext()) days.getOrPut(c.getString(0) to c.getString(1)) { mutableListOf() }
                .add(Day(c.getString(2), if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4)))
        }
        return StoredHistory(series, days)
    }

    fun stats(): StoredStats = readableDatabase.rawQuery("SELECT COUNT(DISTINCT date), COUNT(*), MIN(date) FROM days WHERE used IS NOT NULL", null).use { c ->
        c.moveToFirst(); StoredStats(c.getInt(0), c.getInt(1), if (c.isNull(2)) null else c.getString(2))
    }

    /** Writes every saved day as CSV: provider,window,date,used,coverage, then your notes (date,note). Returns the number of day rows. */
    fun exportCsv(out: Writer): Int {
        out.write("provider,window,date,used,coverage\n")
        var count = 0
        readableDatabase.rawQuery("SELECT provider, window, date, used, coverage FROM days ORDER BY provider, window, date", null).use { c ->
            while (c.moveToNext()) {
                out.write(csvRow(StoredDay(c.getString(0), c.getString(1), c.getString(2), if (c.isNull(3)) null else c.getDouble(3), if (c.isNull(4)) null else c.getDouble(4))))
                count++
            }
        }
        val notes = notes().toSortedMap()
        if (notes.isNotEmpty()) {
            out.write("\ndate,note\n")
            notes.forEach { (date, text) -> out.write(date + "," + csvField(text) + "\n") }
        }
        out.flush()
        return count
    }

    /** Deletes everything saved on this phone, notes included. The PC's own history and notes are untouched. */
    fun clear() { writableDatabase.apply { delete("days", null, null); delete("series", null, null); delete("notes", null, null) } }

    enum class Existing { None, Empty, Value }

    companion object {
        const val FILE = "local-history.db"

        /** Opens the store, runs [block] and closes it. (SQLiteOpenHelper is only AutoCloseable from Android 10, so `use` can't be used on Android 9.) */
        inline fun <T> open(context: Context, block: (LocalHistory) -> T): T {
            val db = LocalHistory(context)
            try { return block(db) } finally { db.close() }
        }

        /** A saved value is never replaced by "no readings"; anything else from the PC is newer and wins. */
        fun shouldWrite(existing: Existing, incoming: StoredDay) = existing == Existing.None || incoming.used != null || existing == Existing.Empty

        /** The days a snapshot carries, one per provider, window and date; the 90-day calendar wins over the 30 days for the same date. */
        fun rows(snapshot: Snapshot): List<StoredDay> = snapshot.providers.flatMap { p ->
            p.history.flatMap { h ->
                val byDate = linkedMapOf<String, Day>()
                (h.days + h.calendar).forEach { d -> if (validDate(d.date)) byDate[d.date] = d }
                byDate.values.map { d -> StoredDay(p.id, h.window, d.date, d.used?.takeIf { it.isFinite() && it >= 0 }, d.coverage?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)) }
            }
        }

        fun csvField(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
        fun csvRow(d: StoredDay): String {
            fun field(v: String) = csvField(v)
            fun num(v: Double?) = v?.let { String.format(Locale.ROOT, "%.4f", it).trimEnd('0').trimEnd('.') } ?: ""
            return listOf(field(d.provider), field(d.window), d.date, num(d.used), num(d.coverage)).joinToString(",") + "\n"
        }

        internal fun validDate(date: String) = date.length == 10 && runCatching { LocalDate.parse(date) }.isSuccess

        /**
         * Saved days with the snapshot's on top, as one unbroken run of dates from the first to the last (missing dates
         * have no data), capped at about ten years.
         */
        fun merge(stored: List<Day>, live: List<Day>): List<Day> {
            if (stored.isEmpty()) return live
            val byDate = sortedMapOf<String, Day>()
            stored.forEach { if (validDate(it.date)) byDate[it.date] = it }
            live.forEach { d -> if (validDate(d.date)) { val saved = byDate[d.date]; byDate[d.date] = if (d.used == null && saved?.used != null) saved else d } }
            if (byDate.isEmpty()) return live
            val first = LocalDate.parse(byDate.firstKey()); val last = LocalDate.parse(byDate.lastKey())
            val start = maxOf(first, last.minusDays(3_650))
            return generateSequence(start) { it.plusDays(1) }.takeWhile { !it.isAfter(last) }.map { byDate[it.toString()] ?: Day(it.toString(), null) }.toList()
        }

        /** Totals from daily figures, for when the PC doesn't send its own all-time numbers. */
        fun allTime(days: List<Day>, zone: ZoneId = ZoneId.systemDefault()): AllTime? {
            val recorded = days.filter { it.used != null && validDate(it.date) }
            if (recorded.isEmpty()) return null
            val first = recorded.minOf { it.date }
            val best = recorded.filter { it.used!! > .0001 }.maxWithOrNull(compareBy<Day> { it.used!! }.thenByDescending { it.date })
            return AllTime(LocalDate.parse(first).atStartOfDay(zone).toInstant().toEpochMilli(), recorded.sumOf { it.used!! }, recorded.count { it.used!! > .0001 }, recorded.size,
                null, best?.date, best?.used ?: 0.0, onPhone = true)
        }

        /**
         * Providers with this phone's saved days merged into each history's calendar. Windows the snapshot no longer
         * carries (or every window, with no snapshot) come back from the saved days alone, marked "Saved".
         */
        fun enrich(providers: List<Provider>, stored: StoredHistory): List<Provider> {
            if (stored.isEmpty) return providers
            val merged = providers.map { p ->
                if (p.history.isEmpty()) p
                else p.copy(history = p.history.map { h -> h.copy(calendar = merge(stored.days(p.id, h.window), h.calendar.ifEmpty { h.days })) })
            }
            val known = merged.flatMap { p -> p.history.map { p.id to it.window } }.toSet()
            val extra = stored.series.filter { (it.provider to it.window) !in known && stored.days(it.provider, it.window).any { d -> d.used != null } }
                .groupBy { it.provider }.mapNotNull { (id, series) ->
                    if (merged.any { it.id == id && it.history.isNotEmpty() }) return@mapNotNull null
                    val histories = series.take(4).map { s ->
                        val days = merge(stored.days(id, s.window), emptyList())
                        History(s.window, s.label, days.takeLast(30), List(168) { 0.0 }, List(168) { 0 }, streak(days), days)
                    }
                    val live = merged.firstOrNull { it.id == id }
                    live?.copy(history = histories) ?: Provider(id, series.first().name, "Stale", emptyList(), history = histories)
                }
            return merged.map { p -> extra.firstOrNull { it.id == p.id } ?: p } + extra.filter { e -> merged.none { it.id == e.id } }
        }

        /** Days in a row with usage, ending today or yesterday. */
        fun streak(days: List<Day>, today: LocalDate = LocalDate.now()): Int {
            val used = days.filter { (it.used ?: 0.0) > .0001 }.map { it.date }.toSet()
            var day = if (today.toString() in used) today else today.minusDays(1)
            var n = 0
            while (day.toString() in used) { n++; day = day.minusDays(1) }
            return n
        }
    }
}
