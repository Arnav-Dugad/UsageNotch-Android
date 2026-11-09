package io.github.arnavdugad.usagenotch

import org.json.JSONArray
import org.json.JSONObject

/** A day note as kept on this phone: [pending] until the PC has it. An empty [text] is a deletion (a tombstone). */
data class StoredNote(val date: String, val text: String, val at: Long, val pending: Boolean = false)

/**
 * Notes on days ("Big refactor"), shared with the PC (feature "notes", Windows 2.9). Each date is last-writer-wins by its
 * time; on a tie the PC's copy wins. Deleting a note keeps an empty one, so the deletion reaches the other side.
 * Notes written on this phone wait as pending until the PC is reachable on the local link (PUT /v1/notes).
 */
object DayNotes {
    const val MAX = 140
    /** Bytes the PC accepts in one PUT /v1/notes. */
    const val MAX_BODY = 65_536

    /** One line, no control characters, at most 140 characters. */
    fun clean(text: String): String = text.map { if (it.isISOControl()) ' ' else it }.joinToString("").replace(Regex("\\s+"), " ").trim().take(MAX)

    /** The time a change made now gets: never behind the copy it replaces, even when the PC's clock runs ahead. */
    fun stamp(existing: StoredNote?, now: Long) = maxOf(now, (existing?.at ?: 0L) + 1)

    /**
     * Merges notes from the PC into [mine]: an incoming note wins when it is as new or newer, and is then no longer
     * pending here. Returns the merged notes and the dates whose text changed.
     */
    fun merge(mine: Map<String, StoredNote>, incoming: List<DayNote>): Pair<Map<String, StoredNote>, Set<String>> {
        val out = mine.toMutableMap(); val changed = mutableSetOf<String>()
        for (n in incoming) {
            val current = out[n.date]
            if (current != null && current.at > n.at) continue
            val text = clean(n.text)
            if (current == null || current.text != text) changed += n.date
            out[n.date] = StoredNote(n.date, text, n.at, pending = false)
        }
        return out to changed
    }

    /** The pending notes as the body of PUT /v1/notes, newest first until the PC's size limit; null with none. */
    fun payload(pending: List<StoredNote>): String? {
        if (pending.isEmpty()) return null
        val list = JSONArray(); var size = 16
        for (n in pending.sortedByDescending { it.at }) {
            val item = JSONObject().put("date", n.date).put("text", n.text).put("at", n.at)
            val bytes = item.toString().toByteArray(Charsets.UTF_8).size + 1
            if (size + bytes > MAX_BODY) break
            list.put(item); size += bytes
        }
        return JSONObject().put("notes", list).toString()
    }

    /** The notes in a PUT /v1/notes answer. */
    fun parseResponse(body: String): List<DayNote> {
        val a = JSONObject(body).getJSONArray("notes"); require(a.length() <= 2_000)
        val date = Regex("[0-9]{4}-[0-9]{2}-[0-9]{2}")
        return (0 until a.length()).mapNotNull { i -> runCatching {
            val n = a.getJSONObject(i); val d = n.getString("date"); require(date.matches(d))
            DayNote(d, clean(n.optString("text")), n.getLong("at").also { require(it in 0..4_102_444_800_000L) })
        }.getOrNull() }
    }
}
