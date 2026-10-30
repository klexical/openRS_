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
 * peaks, knock events, perf runs, diagnostics). A fixed order with no inference about the car — the two channels are
 * never judged against each other and nothing is decided from throttle or pedal position:
 *  1. TIP − measured baro while TIP was decoded ≤ [TIP_FRESH_MS] ago, the engine runs (rpm > 400 on a CAN frame
 *     ≤ [LINK_FRESH_MS] old) and baro was measured (0x090, 60–115 kPa) — labelled TIP (pre-throttle pressure, not
 *     proven manifold boost);
 *  2. else CAN 0x0F8 while its last receipt (freshness stamp `boostKpa`) is ≤ [CAN_FRESH_MS] old — labelled BOOST
 *     (the DBC's name; what byte 5 measures on this car is unverified);
 *  3. else nothing ("—"; not recorded as a reading).
 * Every reading carries its source; peaks, recorded points and exports keep them apart. Freshness is judged against a
 * wall clock [nowMs] that keeps running when CAN stalls, never against `lastUpdate`. Never received = not available:
 * the 0x0F8 default (101.325) never reaches a gauge or a peak.
 */
object BoostRule {
    /** Stored on each drive recorded under this rule; older drives have no policy (null) and no per-point source. */
    const val POLICY = "rc7-boost-v3"
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

    /**
     * The live pressure gauge (DRIVE, HUD, TRIP live readout, Custom Dashboard): TIP − measured baro only, labelled TIP,
     * "—" when TIP isn't fresh. It never falls back to CAN 0x0F8 — a brief TIP polling gap must not flip the gauge to a
     * different measurement — and freshness isn't stretched to hide gaps. CAN 0x0F8 stays in recordings ([read]) and
     * in the diagnostic report.
     */
    fun gauge(vs: VehicleState, nowMs: Long): BoostReading = BoostReading(BoostSource.TIP_033E, tipGaugeKpa(vs, nowMs))

    /** The recorded reading (DriveRecorder points, knock events, perf runs): TIP, else CAN 0x0F8, with its source. */
    fun read(vs: VehicleState, nowMs: Long): BoostReading {
        tipGaugeKpa(vs, nowMs)?.let { return BoostReading(BoostSource.TIP_033E, it) }
        canGaugeKpa(vs, nowMs)?.let { return BoostReading(BoostSource.CAN_0F8, it) }
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
 * reset and demo change: the peak of each measurement (gauge kPa, whenever it is fresh), so a caption shows the peak
 * of the source it names — never one another source produced. Source changes of the shown reading are logged
 * (BOOST_SOURCE, at most one line per [LOG_INTERVAL_MS], with the count of changes in between). Never persisted.
 */
object BoostSession {
    const val LOG_INTERVAL_MS = 10_000L

    private var shown: BoostSource? = null
    private var lastLogMs: Long? = null
    private var unlogged = 0

    private val _peaks = MutableStateFlow<Map<BoostSource, Double>>(emptyMap())
    /** Session peak gauge kPa per source (no entry = that source gave no reading). */
    val peaks: StateFlow<Map<BoostSource, Double>> = _peaks.asStateFlow()

    /** Event sink (DiagnosticLogger.event in the app; tests capture). */
    @Volatile var log: (String) -> Unit = {}

    fun read(vs: VehicleState, nowMs: Long): BoostReading = BoostRule.read(vs, nowMs)

    /** The live gauge: TIP only ([BoostRule.gauge]). */
    fun gauge(vs: VehicleState, nowMs: Long): BoostReading = BoostRule.gauge(vs, nowMs)

    /** Peak gauge kPa for [source] this session, or null. */
    fun peakKpa(source: BoostSource): Double? = _peaks.value[source]

    /** Feed every live state (CAN frame side effects, OBD merges, demo ticks). Returns the reading shown now. */
    @Synchronized fun onState(vs: VehicleState, nowMs: Long): BoostReading {
        // Each measurement's own peak, whenever it is fresh — whichever one the gauge shows
        val can = BoostRule.canGaugeKpa(vs, nowMs)
        val tip = BoostRule.tipGaugeKpa(vs, nowMs)
        peak(BoostSource.CAN_0F8, can)
        peak(BoostSource.TIP_033E, tip)
        val r = read(vs, nowMs)
        noteSource(r.source, can, tip, nowMs)
        return r
    }

    private fun noteSource(source: BoostSource, can: Double?, tip: Double?, nowMs: Long) {
        val previous = shown
        if (source == previous) return
        shown = source
        if (previous == null) return                                    // first reading of the session
        if (lastLogMs?.let { nowMs - it < LOG_INTERVAL_MS } == true) { unlogged++; return }
        fun kpa(v: Double?) = v?.let { "%.1f kPa".format(java.util.Locale.ROOT, it) } ?: "not fresh"
        log("shown ${previous.name} → ${source.name} (TIP ${kpa(tip)}, CAN 0x0F8 ${kpa(can)})" +
            (if (unlogged > 0) " · $unlogged earlier change(s) not logged" else ""))
        lastLogMs = nowMs; unlogged = 0
    }

    private fun peak(source: BoostSource, g: Double?) {
        if (g == null) return
        val cur = _peaks.value[source]
        if (cur == null || g > cur) _peaks.value = _peaks.value + (source to g)
    }

    /** PEAK reset (header long-press etc.). */
    @Synchronized fun resetPeaks() { _peaks.value = emptyMap() }

    @Synchronized fun reset() {
        shown = null; lastLogMs = null; unlogged = 0
        _peaks.value = emptyMap()
    }
}

/**
 * What a stored boost value is (#207) — for every reader of recorded drives (TRIP, exports): a reading with its source,
 * a value recorded before sources were stored (legacy: shown as recorded, "source not recorded", never given today's
 * source), or no reading. A genuine 0 from a source is a reading; "no reading" is never shown as 0.
 */
sealed interface StoredBoost {
    data class Reading(val psi: Double, val source: BoostSource) : StoredBoost
    data class Legacy(val psi: Double) : StoredBoost
    data object Unavailable : StoredBoost

    val psiOrNull: Double? get() = when (this) { is Reading -> psi; is Legacy -> psi; Unavailable -> null }
    /** TIP / BOOST (CAN 0x0F8) / BOOST for legacy values. */
    val label: String get() = when (this) { is Reading -> source.label; is Legacy -> "BOOST"; Unavailable -> "BOOST" }
    /** Readings of one kind: same source, or both legacy. */
    fun sameKind(other: StoredBoost): Boolean = when (this) {
        is Reading -> other is Reading && other.source == source
        is Legacy -> other is Legacy
        Unavailable -> false
    }

    companion object {
        /** A drive's peak. Drives recorded under a boost policy store the peak's source (none = no reading); older
         *  drives keep their value as recorded — 0 there meant no reading too, so only a positive value is shown. */
        fun drivePeak(d: DriveEntity): StoredBoost = when {
            d.boostPolicy == null -> if (d.peakBoostPsi > 0) Legacy(d.peakBoostPsi) else Unavailable
            else -> BoostSource.parse(d.peakBoostSource)?.takeIf { it != BoostSource.NONE }
                ?.let { Reading(d.peakBoostPsi, it) } ?: Unavailable
        }

        /** A recorded point: CAN / TIP = reading, "NONE" = no reading, no source column value = legacy, as recorded. */
        fun point(p: DrivePointEntity): StoredBoost = when (val s = p.boostSource) {
            null -> Legacy(p.boostPsi)
            else -> BoostSource.parse(s)?.takeIf { it != BoostSource.NONE }?.let { Reading(p.boostPsi, it) } ?: Unavailable
        }

        /**
         * The PEAK trend over [drives] (oldest first): the latest drive's kind of peak and the peaks of every drive of
         * that kind (same source, or all from before sources were stored) — drives without a reading are left out,
         * never plotted as 0. Null when the latest drive has no peak.
         */
        fun peakTrend(drives: List<DriveEntity>): Pair<StoredBoost, List<Double>>? {
            val latest = drives.lastOrNull()?.let { drivePeak(it) }?.takeIf { it != Unavailable } ?: return null
            return latest to drives.map { drivePeak(it) }.filter { it.sameKind(latest) }.map { it.psiOrNull!! }
        }

        /**
         * The one source a single-scale view of a drive's points uses (map colouring, third-party track files that
         * have one boost column): the source of the drive's highest reading — the drive peak's source. Null when
         * the points are legacy or have no reading. Points of any other source are left out, never merged in.
         */
        fun primarySource(points: List<DrivePointEntity>): BoostSource? =
            points.mapNotNull { point(it) as? Reading }.maxByOrNull { it.psi }?.source
    }
}
