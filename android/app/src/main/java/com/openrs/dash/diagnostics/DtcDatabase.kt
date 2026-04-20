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

    // ── Known Focus RS issues (curated notes for common DTCs) ────────────

    private val knownIssues: Map<String, String> = mapOf(
        "P0234" to "Common after aftermarket FMIC install or boost leak. Usually benign if actual boost levels are within spec. Check intercooler piping and wastegate actuator.",
        "P0299" to "Underboost condition. Check wastegate actuator, boost control solenoid (BCS), and intercooler piping for leaks. Common after cold starts in some climates.",
        "P0401" to "EGR flow insufficient. Carbon buildup in EGR valve is common on direct-injection EcoBoost engines. Consider EGR cleaning or delete (off-road only).",
        "P0420" to "Catalyst efficiency below threshold. Often triggered by aftermarket downpipe. Can also indicate genuine catalyst degradation on high-mileage cars.",
        "P0456" to "Small EVAP leak. Check gas cap seal first. Common nuisance code on Focus RS — often intermittent and self-clearing.",
        "P0507" to "Idle speed high. Can occur after battery disconnect or ECU reset. Usually resolves after the idle relearn procedure (let engine idle 10+ min).",
        "P2BAF" to "Ford manufacturer-specific NOx sensor code. May appear after a flash/tune. Check NOx sensor wiring if on stock calibration.",
        "P0217" to "Engine overtemp. If seen on track days, check coolant level, thermostat, and radiator airflow. The RS is prone to overheating under sustained high-load driving.",
        "U0100" to "Lost communication with ECM/PCM. Common after battery disconnect. If persistent, check CAN bus wiring, especially if aftermarket accessories are installed.",
        "U0073" to "Control module communication bus off. Check CAN bus termination resistors and wiring. Can be caused by faulty aftermarket CAN devices.",
        "C0034" to "Wheel speed sensor — check wiring harness and connector at the affected wheel. Road debris damage is common.",
        "B1A85" to "Rear view camera. Common on cars with aftermarket head units. Check camera connector and APIM module.",
    )

    /** Returns a curated note for a known Focus RS DTC, or null. */
    fun knownIssue(code: String): String? = knownIssues[code]
}
