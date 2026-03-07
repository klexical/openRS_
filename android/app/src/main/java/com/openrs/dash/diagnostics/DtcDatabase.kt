package com.openrs.dash.diagnostics

import android.content.Context
import com.openrs.dash.R
import org.json.JSONObject

/**
 * Loads and queries the bundled DTC description database.
 *
 * The database is stored as a compact JSON map in res/raw/dtc_database.json:
 *   { "P0234": "Turbocharger/Supercharger A Overboost Condition", ... }
 *
 * Load is deferred to the first [describe] call (lazy init via [load]).
 * Thread safety: load is called once under lock; reads are unsynchronized
 * after that (immutable map).
 */
object DtcDatabase {

    @Volatile private var codes: Map<String, String> = emptyMap()
    @Volatile private var loaded = false

    fun load(ctx: Context) {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            try {
                ctx.resources.openRawResource(R.raw.dtc_database).use { stream ->
                    val json = JSONObject(stream.bufferedReader().readText())
                    val map = HashMap<String, String>(json.length() * 2)
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val key = keys.next()
                        map[key] = json.getString(key)
                    }
                    codes = map
                }
            } catch (_: Exception) {
                codes = emptyMap()
            }
            loaded = true
        }
    }

    /**
     * Returns the description for [code] (e.g. "P0234"), or an empty string
     * if the code is not in the database.
     */
    fun describe(code: String): String = codes[code] ?: ""

    /** Total number of codes in the loaded database (for diagnostics). */
    val size: Int get() = codes.size
}
