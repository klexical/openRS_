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

    /**
     * Whether the PCM poller sends MAP this batch (#271, owner's final decision F1 "Only while recording"): only while a drive
     * is recording and not paused — a paused drive stores no points, so MAP would only cost cycle time. Then MAP is in the fast
     * group (and in the engine-off sweep, which gives the key-on / engine-off reading the scale check needs); otherwise it isn't
     * polled at all.
     */
    fun pollActive(ds: DriveState): Boolean = ds.isRecording && !ds.isPaused
}

