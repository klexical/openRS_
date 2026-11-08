package com.openrs.dash.data

/**
 * Standard MAP, PCM DID 0xF40B (OBD PID 0x0B through the 22F4xx path), added for #271: TIP 0x033E's scale (/128 vs /131.07)
 * is settled by comparing TIP with an independent manifold pressure at wide-open throttle. One data byte, kPa absolute, so
 * the reading can't express more than 255 kPa: a value at the ceiling means "at least 255", not a measurement, and every
 * reader flags it ([isSaturated]). Held in [VehicleState.genericValues] under [TAG] with its own freshness stamp
 * (VehicleState is at the JVM slot ceiling, #199). Unverified on a car: whether this PCM answers 0xF40B is not established.
 */
object MapF40b {
    const val TAG = "mapKpa"
    /** The byte's ceiling: a reading here is saturated. */
    const val SATURATED_KPA = 255.0

    fun isSaturated(kpa: Double): Boolean = kpa >= SATURATED_KPA

    /** The current reading, or null before the first reply. Freshness is the caller's ([PolledFreshness] / [FreshnessGate]). */
    fun kpa(vs: VehicleState): Double? = vs.genericValues[TAG]
}

/**
 * The "MAP comparison" test mode (#271, owner decision F1): while on, MAP 0xF40B joins the PCM fast group (a TIP / MAP pair every
 * engine-running cycle, the cycle 1.35 s instead of 1.2 s); off, it is in the slow rotation. Toggled from GARAGE → DIAG →
 * DEVELOPER only. Memory only: off at every app start, never saved to preferences, so it can't become a hidden always-on
 * setting; it survives reconnects and drives within one run of the app. A drive records whether it was on at any of its points
 * (`DriveEntity.mapComparison`), and every change is a MAP_COMPARISON session event in the diagnostic export.
 */
object MapComparisonMode {
    const val EVENT = "MAP_COMPARISON"
    private val _on = kotlinx.coroutines.flow.MutableStateFlow(false)
    val on: kotlinx.coroutines.flow.StateFlow<Boolean> = _on

    val isOn: Boolean get() = _on.value

    /** Sets the mode; [log] receives one line per actual change (DiagnosticLogger.event in the app). */
    fun set(value: Boolean, log: (String, String) -> Unit = { _, _ -> }) {
        if (_on.value == value) return
        _on.value = value
        log(EVENT, if (value) "on — MAP 0xF40B in the PCM fast group" else "off — MAP 0xF40B in the slow rotation")
    }
}
