package com.ryderdavid.voicebar

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * The last [KEEP] dictations, both as heard and as typed: when cleanup goes wrong (or the text went
 * missing, or was held or copied instead), what you said can be pasted again from the menu.
 * On the phone only; never exported.
 */
class DictationHistory(private val store: Store) {
    interface Store { fun read(): String?; fun write(json: String) }

    data class Entry(val time: Long, val heard: String, val typed: String, val app: String = "")

    private val entries = ArrayList<Entry>()

    init {
        runCatching {
            val arr = JSONArray(store.read() ?: "[]")
            for (i in 0 until arr.length()) arr.getJSONObject(i).let { entries += Entry(it.optLong("w"), it.optString("h"), it.optString("t"), it.optString("a")) }
        }
    }

    /** Newest first. */
    @Synchronized fun all(): List<Entry> = entries.sortedByDescending { it.time }

    @Synchronized fun add(heard: String, typed: String, app: String = "", now: Long = System.currentTimeMillis()) {
        if (heard.isBlank() && typed.isBlank()) return
        entries += Entry(now, heard.take(MAX_CHARS), typed.take(MAX_CHARS), app)
        while (entries.size > KEEP) entries.remove(entries.minBy { it.time })
        save()
    }

    @Synchronized fun remove(time: Long) { entries.removeAll { it.time == time }; save() }

    /** A new cleanup of dictation [time]: what's typed for it from now on (the last-dictation key). */
    @Synchronized fun retype(time: Long, typed: String) {
        val i = entries.indexOfFirst { it.time == time }
        if (i < 0) return
        entries[i] = entries[i].copy(typed = typed.take(MAX_CHARS))
        save()
    }

    private fun save() {
        val arr = JSONArray()
        for (e in entries) arr.put(JSONObject().put("w", e.time).put("h", e.heard).put("t", e.typed).put("a", e.app))
        store.write(arr.toString())
    }

    companion object {
        const val KEEP = 10
        const val MAX_CHARS = 8000

        @Volatile private var instance: DictationHistory? = null
        fun of(context: Context): DictationHistory = instance ?: synchronized(this) {
            instance ?: run {
                val p = context.applicationContext.getSharedPreferences("dictation_history", Context.MODE_PRIVATE)
                DictationHistory(object : Store {
                    override fun read() = p.getString("entries", null)
                    override fun write(json: String) { p.edit().putString("entries", json).apply() }
                })
            }.also { instance = it }
        }
    }
}
