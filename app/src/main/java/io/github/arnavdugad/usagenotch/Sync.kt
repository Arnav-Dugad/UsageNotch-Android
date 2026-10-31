package io.github.arnavdugad.usagenotch

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

/**
 * Settings shared with the PC (protocol 2, "settings"): budgets, accent colours, dock names, provider order, alert
 * levels and quiet hours. Each key is last-writer-wins by its timestamp; on a tie the PC's copy wins. A null value
 * means "back to the default" and stays as a tombstone so the reset reaches the other side.
 *
 * Local edits are recorded with the time they were made and marked pending. They are pushed to the PC whenever it is
 * reachable on the local link; while the phone only has internet sync they wait.
 */
object SharedSettings {
    private const val ENTRIES = "shared"; private const val PENDING = "sharedPending"; private const val SEEDED = "sharedSeeded"
    const val MAX_ENTRIES = 200
    private val slug = Regex("[A-Za-z0-9_.:-]{1,100}")
    private val color = Regex("#[0-9A-Fa-f]{6}")

    /** Keeps only well-formed entries for keys this app knows, so a damaged or hostile map can't reach settings. */
    fun parseEntries(o: JSONObject): Map<String, SharedEntry> {
        val out = linkedMapOf<String, SharedEntry>()
        val keys = o.keys().asSequence().take(MAX_ENTRIES * 2).toList()
        for (key in keys) {
            if (out.size >= MAX_ENTRIES || key.length > 220) break
            val e = o.optJSONObject(key) ?: continue
            val at = e.optLong("at", -1).takeIf { it in 0..4_102_444_800_000L } ?: continue
            val raw = if (!e.has("v") || e.isNull("v")) null else e.get("v")
            val v = runCatching { validate(key, raw) }.getOrNull() ?: continue
            out[key] = SharedEntry(v.value, at)
        }
        return out
    }
    private class Valid(val value: Any?)
    /** Null result: the key or value isn't acceptable. Valid(null) is a tombstone. */
    private fun validate(key: String, v: Any?): Valid? {
        if (v == null) return if (known(key)) Valid(null) else null
        return when {
            key.startsWith("budget:") -> {
                val o = v as? JSONObject ?: return null
                val limit = o.getInt("limit"); require(limit in 1..100)
                val by = if (!o.has("by") || o.isNull("by")) null else o.getInt("by").also { require(it in 0..1439) }
                require(key.removePrefix("budget:").split('|').let { it.size == 2 && it.all { part -> slug.matches(part) } })
                Valid(JSONObject().put("limit", limit).put("by", by ?: JSONObject.NULL))
            }
            key.startsWith("accent:") -> (v as? String)?.takeIf { color.matches(it) && slug.matches(key.removePrefix("accent:")) }?.let { Valid(it.uppercase(Locale.ROOT)) }
            key.startsWith("name:") -> (v as? String)?.filterNot { it.isISOControl() }?.trim()?.take(18)?.takeIf { slug.matches(key.removePrefix("name:")) }?.let { Valid(it.ifBlank { null }) }
            key == "order" -> (v as? JSONArray)?.takeIf { it.length() <= 16 }?.let { a -> Valid(JSONArray((0 until a.length()).map { a.getString(it) }.filter { slug.matches(it) }.distinct())) }
            key == "alerts:thresholds" -> (v as? JSONObject)?.let { o ->
                val warn = o.getInt("warn"); val critical = o.getInt("critical"); require(warn in 1..100 && critical in 1..100)
                Valid(JSONObject().put("warn", warn).put("critical", critical))
            }
            key == "alerts:quiet" -> (v as? JSONObject)?.let { o ->
                val start = o.getInt("start"); val end = o.getInt("end"); require(start in 0..23 && end in 0..23)
                Valid(JSONObject().put("on", o.optBoolean("on", false)).put("start", start).put("end", end))
            }
            else -> null
        }
    }
    private fun known(key: String) = key == "order" || key == "alerts:thresholds" || key == "alerts:quiet" || listOf("budget:", "accent:", "name:").any { key.startsWith(it) }

    fun entries(prefs: SharedPreferences): Map<String, SharedEntry> = runCatching { parseEntries(JSONObject(prefs.getString(ENTRIES, "{}") ?: "{}")) }.getOrDefault(emptyMap())
    fun pending(prefs: SharedPreferences): Set<String> = runCatching { JSONArray(prefs.getString(PENDING, "[]")).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }.getOrDefault(emptySet())
    private fun save(prefs: SharedPreferences, entries: Map<String, SharedEntry>, pending: Set<String>) {
        val o = JSONObject(); entries.entries.toList().takeLast(MAX_ENTRIES).forEach { (k, e) -> o.put(k, JSONObject().put("v", e.v ?: JSONObject.NULL).put("at", e.at)) }
        prefs.edit().putString(ENTRIES, o.toString()).putString(PENDING, JSONArray(pending.filter { it in entries }.toList()).toString()).apply()
    }

    /** Records a change made on this phone and applies it here right away. */
    fun local(prefs: SharedPreferences, key: String, value: Any?, now: Long = System.currentTimeMillis()) {
        val valid = validate(key, value) ?: return
        val all = entries(prefs).toMutableMap()
        // Never step back behind an entry from a PC whose clock runs ahead.
        val at = maxOf(now, (all[key]?.at ?: 0) + 1)
        all[key] = SharedEntry(valid.value, at)
        save(prefs, all, pending(prefs) + key)
        apply(prefs, key, valid.value)
    }

    /**
     * Merges entries from the PC: an incoming entry wins when it is as new or newer. Returns the keys whose value changed
     * on this phone. A pending local change that the PC now has (or has overruled) stops being pending.
     */
    fun merge(prefs: SharedPreferences, incoming: Map<String, SharedEntry>): Set<String> {
        if (incoming.isEmpty()) return emptySet()
        val all = entries(prefs).toMutableMap(); val pending = pending(prefs).toMutableSet(); val changed = mutableSetOf<String>()
        for ((key, e) in incoming) {
            val mine = all[key]
            if (mine != null && mine.at > e.at) continue
            if (mine == null || !same(mine.v, e.v)) changed += key
            all[key] = e; pending -= key
        }
        save(prefs, all, pending)
        changed.forEach { apply(prefs, it, all[it]?.v) }
        return changed
    }
    private fun same(a: Any?, b: Any?) = (a?.toString() ?: "null") == (b?.toString() ?: "null")

    /** The pending local entries, as the body of PUT /v1/settings. */
    fun payload(prefs: SharedPreferences): String? {
        val pending = pending(prefs); if (pending.isEmpty()) return null
        val all = entries(prefs); val o = JSONObject()
        pending.mapNotNull { k -> all[k]?.let { k to it } }.forEach { (k, e) -> o.put(k, JSONObject().put("v", e.v ?: JSONObject.NULL).put("at", e.at)) }
        return JSONObject().put("shared", o).toString()
    }

    /**
     * Once per install, settings chosen on this phone before sync existed (budgets and colours from 1.4) become pending
     * entries, so the first connection to a PC that syncs settings carries them over instead of dropping them.
     */
    fun seed(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) {
        if (prefs.getBoolean(SEEDED, false)) return
        val all = entries(prefs).toMutableMap(); val pending = pending(prefs).toMutableSet()
        Budgets.all(prefs).forEach { b ->
            val key = "budget:${b.provider}|${b.window}"
            if (key !in all) { all[key] = SharedEntry(JSONObject().put("limit", b.limit).put("by", b.byMinute ?: JSONObject.NULL), now); pending += key }
        }
        prefs.all.keys.filter { it.startsWith("accent-") }.forEach { pk ->
            val id = Accents.syncId(pk.removePrefix("accent-")); val key = "accent:$id"
            if (key !in all) { all[key] = SharedEntry(hex(prefs.getInt(pk, 0)), now); pending += key }
        }
        save(prefs, all, pending)
        prefs.edit().putBoolean(SEEDED, true).apply()
    }

    /** Writes one synced value into the phone's own settings. Never records a new entry, so merges can't loop. */
    internal fun apply(prefs: SharedPreferences, key: String, v: Any?) {
        when {
            key.startsWith("budget:") -> {
                val (provider, window) = key.removePrefix("budget:").split('|').let { it[0] to it[1] }
                val o = v as? JSONObject
                if (o == null) Budgets.store(prefs, Budgets.all(prefs).filterNot { it.provider == provider && it.window == window })
                else Budgets.store(prefs, Budgets.all(prefs).filterNot { it.provider == provider && it.window == window } +
                    Budget(provider, window, o.getInt("limit"), if (o.isNull("by")) null else o.getInt("by")))
            }
            key.startsWith("accent:") -> Accents.store(prefs, key.removePrefix("accent:"), (v as? String)?.let { parseHex(it) })
            key.startsWith("name:") -> prefs.edit().apply { val id = key.removePrefix("name:"); if (v is String && v.isNotBlank()) putString("name-$id", v) else remove("name-$id") }.apply()
            key == "order" -> prefs.edit().apply { if (v is JSONArray) putString("providerOrder", v.toString()) else remove("providerOrder") }.apply()
            key == "alerts:thresholds" -> prefs.edit().apply { val o = v as? JSONObject; if (o != null) putInt("alertWarn", o.getInt("warn")).putInt("alertCritical", o.getInt("critical")) else remove("alertWarn").remove("alertCritical") }.apply()
            key == "alerts:quiet" -> prefs.edit().apply { val o = v as? JSONObject; if (o != null) putBoolean("quietOn", o.getBoolean("on")).putInt("quietStart", o.getInt("start")).putInt("quietEnd", o.getInt("end")) else remove("quietOn").remove("quietStart").remove("quietEnd") }.apply()
        }
    }

    fun hex(color: Int) = "#%06X".format(Locale.ROOT, color and 0xFFFFFF)
    fun parseHex(text: String): Int? = if (color.matches(text)) (0xFF000000.toInt() or text.substring(1).toInt(16)) else null

    // ---- How the synced names and order change what the phone shows ----

    fun nameOf(prefs: SharedPreferences, id: String): String? = prefs.getString("name-$id", null)?.takeIf { it.isNotBlank() }
    fun order(prefs: SharedPreferences): List<String> = runCatching { JSONArray(prefs.getString("providerOrder", "[]")).let { a -> (0 until a.length()).map { a.getString(it) } } }.getOrDefault(emptyList())
    fun setName(prefs: SharedPreferences, id: String, name: String?) = local(prefs, "name:$id", name?.trim()?.ifBlank { null })
    fun setOrder(prefs: SharedPreferences, ids: List<String>) = local(prefs, "order", JSONArray(ids.take(16)))

    /** Your dock names and provider order, applied to a snapshot for everything the phone shows: app, widgets, tile and alerts. */
    fun present(snapshot: Snapshot, prefs: SharedPreferences): Snapshot {
        val order = order(prefs)
        val named = snapshot.providers.map { p -> nameOf(prefs, p.id)?.let { p.copy(name = it) } ?: p }
        if (order.isEmpty()) return snapshot.copy(providers = named)
        val rank = order.withIndex().associate { it.value to it.index }
        return snapshot.copy(providers = named.withIndex().sortedBy { (i, p) -> rank[p.id]?.toDouble() ?: (order.size + i).toDouble() }.map { it.value })
    }
}

/** Alert levels and quiet hours, shared with the PC. Defaults come from the design tokens (80% and 95%). */
object AlertPrefs {
    fun warn(prefs: SharedPreferences) = prefs.getInt("alertWarn", DesignTokens.WARN_PERCENT).coerceIn(1, 100)
    fun critical(prefs: SharedPreferences) = maxOf(warn(prefs), prefs.getInt("alertCritical", DesignTokens.CRITICAL_PERCENT).coerceIn(1, 100))
    fun thresholds(prefs: SharedPreferences) = listOf(critical(prefs), warn(prefs)).distinct()
    fun quietOn(prefs: SharedPreferences) = prefs.getBoolean("quietOn", false)
    fun quietStart(prefs: SharedPreferences) = prefs.getInt("quietStart", 22).coerceIn(0, 23)
    fun quietEnd(prefs: SharedPreferences) = prefs.getInt("quietEnd", 8).coerceIn(0, 23)
    /** Same rule as the desktop: overnight ranges work, and equal start and end means all day. */
    fun quiet(on: Boolean, start: Int, end: Int, hour: Int) = on && (start == end || (if (start < end) hour in start until end else hour >= start || hour < end))
    fun isQuiet(prefs: SharedPreferences, now: Long = System.currentTimeMillis()) =
        quiet(quietOn(prefs), quietStart(prefs), quietEnd(prefs), java.time.Instant.ofEpochMilli(now).atZone(java.time.ZoneId.systemDefault()).hour)
    fun setThresholds(prefs: SharedPreferences, warn: Int, critical: Int) =
        SharedSettings.local(prefs, "alerts:thresholds", JSONObject().put("warn", warn.coerceIn(1, 100)).put("critical", maxOf(warn, critical).coerceIn(1, 100)))
    fun setQuiet(prefs: SharedPreferences, on: Boolean, start: Int, end: Int) =
        SharedSettings.local(prefs, "alerts:quiet", JSONObject().put("on", on).put("start", start.coerceIn(0, 23)).put("end", end.coerceIn(0, 23)))
}
