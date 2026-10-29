package com.openrs.dash.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The two pressure measurements the app can show as boost (#207). They are different quantities at different places
 * and are never presented under one name:
 *  - [CAN_0F8] — HS-CAN 0x0F8 byte 5, the car's "Boost" signal (RS_HS.dbc): gauge pressure, 0.01 bar (1 kPa) per bit,
 *    unsigned (reads 0 at or below ambient — no vacuum). Sensor location not documented (manifold side presumed,
 *    unverified). Broadcast ~50 Hz.
 *  - [TIP_033E] — PCM DID 0x033E throttle inlet pressure, absolute, raw / 128 kPa (DigiCluster, on-car). Upstream of
 *    the throttle plate: at part throttle it can sit well above manifold pressure; at wide-open throttle the two
 *    should be close (not verified on this car). Polled ~0.8 Hz while the engine runs. Shown as TIP − measured baro.
 */
enum class BoostSource(val label: String) {
    CAN_0F8("BOOST"), TIP_033E("TIP"), NONE("BOOST");

    companion object {
        /** A stored source name back; null / unknown = recorded before the rule (no provenance). */
        fun parse(name: String?): BoostSource? = entries.firstOrNull { it.name == name }
    }
}

/** One boost reading: gauge kPa (relative to ambient) from [source], or none ([gaugeKpa] null, source [BoostSource.NONE]). */
data class BoostReading(val source: BoostSource, val gaugeKpa: Double?) {
    val available: Boolean get() = gaugeKpa != null
    val psi: Double? get() = gaugeKpa?.let { it * KPA_TO_PSI }
    /** In the absolute-vs-standard-atmosphere form `UserPrefs.displayBoost` expects (it subtracts 101.325 again). */
    val displayKpa: Double? get() = gaugeKpa?.let { it + STD_ATM_KPA }

    companion object {
        const val STD_ATM_KPA = 101.325
        const val KPA_TO_PSI = 0.14503773
        val NONE = BoostReading(BoostSource.NONE, null)
    }
}

/**
 * The boost source rule ([POLICY]) — one function for every consumer (DRIVE, HUD, Custom Dashboard, TRIP, recorder,
 * peaks, knock events, perf runs, diagnostics). Freshness is judged against a wall clock [nowMs] that keeps running
 * when CAN stalls, never against `lastUpdate`:
 *  1. CAN 0x0F8 while its last receipt (freshness stamp `boostKpa`) is ≤ [CAN_FRESH_MS] old — unless this session
 *     showed it isn't tracking boost ([canTracking] false, see [BoostSession]);
 *  2. else TIP − measured baro while TIP was decoded ≤ [TIP_FRESH_MS] ago, the engine runs (rpm > 400 on a CAN frame
 *     ≤ [LINK_FRESH_MS] old) and baro was measured (0x090, 60–115 kPa) — labelled TIP, never BOOST;
 *  3. else nothing ("—"; not recorded as a reading).
 * Never received = not available: the 0x0F8 default (101.325) never reaches a gauge or a peak.
 */
object BoostRule {
    /** Stored on each drive recorded under this rule; older drives have no policy (null) and no per-point source. */
    const val POLICY = "rc7-boost-v2"
    const val CAN_FRESH_MS = 3_000L
    const val TIP_FRESH_MS = 2_500L
    const val LINK_FRESH_MS = 2_000L
    const val ENGINE_RUNNING_RPM = 400.0

    /** Measured baro (0x090 byte 2 × 0.5) when it has been received and is plausible; null otherwise. */
    fun baroKpa(vs: VehicleState): Double? = vs.barometricPressure.takeIf { it in 60.0..115.0 }

    /** Fresh CAN 0x0F8 gauge kPa, or null (never received / stale). */
    fun canGaugeKpa(vs: VehicleState, nowMs: Long): Double? {
        val at = vs.fieldLastUpdateMs[CAN_TAG] ?: return null
        if (nowMs - at !in 0L..CAN_FRESH_MS) return null
        return vs.boostKpa - BoostReading.STD_ATM_KPA
    }

    /** Fresh TIP − measured baro, or null (stale, engine off / unknown, no baro, implausible decode). */
    fun tipGaugeKpa(vs: VehicleState, nowMs: Long): Double? {
        val at = vs.fieldLastUpdateMs[TIP_TAG] ?: return null
        if (nowMs - at !in 0L..TIP_FRESH_MS) return null
        if (nowMs - vs.lastUpdate !in 0L..LINK_FRESH_MS || vs.rpm <= ENGINE_RUNNING_RPM) return null
        if (vs.tipActualKpa <= 50.0) return null
        val baro = baroKpa(vs) ?: return null
        return vs.tipActualKpa - baro
    }

    fun read(vs: VehicleState, nowMs: Long, canTracking: Boolean = true): BoostReading {
        if (canTracking) canGaugeKpa(vs, nowMs)?.let { return BoostReading(BoostSource.CAN_0F8, it) }
        tipGaugeKpa(vs, nowMs)?.let { return BoostReading(BoostSource.TIP_033E, it) }
        return BoostReading.NONE
    }

    /**
     * A recorded drive's boost peak after reading [r] (DriveRecorder): the new (psi, source), or null when [r] doesn't
     * set one. A NONE reading never does; the peak keeps the source of the reading that set it.
     */
    fun nextDrivePeak(peakPsi: Double, peakSource: BoostSource?, r: BoostReading): Pair<Double, BoostSource>? {
        val psi = r.psi ?: return null
        return if (peakSource == null || psi > peakPsi) psi to r.source else null
    }

    /** Any absolute pressure (e.g. TIP desired) relative to measured baro; null without baro. */
    fun gaugeVsBaro(vs: VehicleState, absKpa: Double): Double? = baroKpa(vs)?.let { absKpa - it }

    const val CAN_TAG = "boostKpa"
    const val TIP_TAG = "tipActualKpa"
}

/**
 * Session state of the boost rule (#207), app-level like [KnockSessionLog]; reset on every new connection, session
 * reset and demo change:
 *  - whether CAN 0x0F8 tracks boost on this car this session. It starts trusted; it is marked not tracking after
 *    [EVIDENCE_NEEDED] distinct TIP decodes, each at ≥ 80 % throttle with TIP ≥ [WOT_TIP_MIN_KPA] above baro while
 *    a fresh 0x0F8 read ≤ [WOT_CAN_MAX_KPA] (at wide-open throttle TIP and manifold pressure should be close). On the
 *    2026-10-28 drives byte 5 read 0 in all AM frames and 40 of 23,495 PM frames while TIP reached ~71 kPa above baro.
 *  - the peak of each measurement (gauge kPa, whenever it is fresh), so a caption shows the peak of the source it
 *    names — never one another source produced.
 * Decisions are logged (BOOST_SOURCE) with the readings that caused them. Never persisted.
 */
object BoostSession {
    const val EVIDENCE_NEEDED = 2
    const val WOT_THROTTLE_PCT = 80.0
    const val WOT_TIP_MIN_KPA = 30.0
    const val WOT_CAN_MAX_KPA = 5.0

    @Volatile var canTracking: Boolean = true
        private set
    private var evidence = 0
    private var lastTipStamp = 0L

    private val _peaks = MutableStateFlow<Map<BoostSource, Double>>(emptyMap())
    /** Session peak gauge kPa per source (no entry = that source gave no reading). */
    val peaks: StateFlow<Map<BoostSource, Double>> = _peaks.asStateFlow()

    /** Event sink (DiagnosticLogger.event in the app; tests capture). */
    @Volatile var log: (String) -> Unit = {}

    fun read(vs: VehicleState, nowMs: Long): BoostReading = BoostRule.read(vs, nowMs, canTracking)

    /** Peak gauge kPa for [source] this session, or null. */
    fun peakKpa(source: BoostSource): Double? = _peaks.value[source]

    /** Feed every live state (CAN frame side effects, OBD merges, demo ticks). Returns the reading shown now. */
    @Synchronized fun onState(vs: VehicleState, nowMs: Long): BoostReading {
        if (canTracking) checkEvidence(vs, nowMs)
        // Each measurement's own peak, whenever it is fresh — whichever one the gauge shows
        peak(BoostSource.CAN_0F8, BoostRule.canGaugeKpa(vs, nowMs))
        peak(BoostSource.TIP_033E, BoostRule.tipGaugeKpa(vs, nowMs))
        return read(vs, nowMs)
    }

    private fun peak(source: BoostSource, g: Double?) {
        if (g == null) return
        val cur = _peaks.value[source]
        if (cur == null || g > cur) _peaks.value = _peaks.value + (source to g)
    }

    private fun checkEvidence(vs: VehicleState, nowMs: Long) {
        val tipStamp = vs.fieldLastUpdateMs[BoostRule.TIP_TAG] ?: return
        if (tipStamp == lastTipStamp) return                      // one vote per TIP decode
        val tip = BoostRule.tipGaugeKpa(vs, nowMs) ?: return
        val can = BoostRule.canGaugeKpa(vs, nowMs) ?: return
        lastTipStamp = tipStamp
        if (vs.throttleOrPedalPct < WOT_THROTTLE_PCT || tip < WOT_TIP_MIN_KPA) return
        if (can > WOT_CAN_MAX_KPA) { evidence = 0; return }          // CAN follows under load — keep trusting it
        evidence++
        if (evidence >= EVIDENCE_NEEDED) {
            canTracking = false
            log("CAN 0x0F8 not tracking boost this session: at ≥ ${WOT_THROTTLE_PCT.toInt()} % throttle TIP read " +
                "%.1f kPa above baro while 0x0F8 read %.1f kPa (%d TIP readings) — BOOST now shows TIP, labelled TIP"
                    .format(java.util.Locale.ROOT, tip, can, evidence))
        }
    }

    /** PEAK reset (header long-press etc.) — the source decision stays. */
    @Synchronized fun resetPeaks() { _peaks.value = emptyMap() }

    @Synchronized fun reset() {
        canTracking = true; evidence = 0; lastTipStamp = 0L
        _peaks.value = emptyMap()
    }
}
