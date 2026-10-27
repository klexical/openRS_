package com.openrs.dash.data

import org.json.JSONArray
import org.json.JSONObject

/**
 * The thermal record saved with each drive (`DriveEntity.thermalPeaksJson`): per-sensor peaks plus the
 * warning / critical limits they were judged against, so a drive's TRIP TEMP flag and its summary
 * colours never change when the thermal profile is switched or edited later.
 *
 * A profile change during a recording (another built-in, or a saved Custom edit) starts a new
 * segment: each part of the drive is judged against the profile in effect at the time — what DRIVE /
 * THERMAL were showing — never a later or earlier one.
 *
 * Drives recorded before this format hold a JSON array from the old fixed-limit snapshot
 * (oil 140 / coolant 115 / RDU 120 / PTU 120 °C, reading taken at drive end). They keep their
 * original TEMP rule and are marked [DriveThermalReview.Basis.LEGACY] — no profile is claimed.
 */
enum class DriveThermalSensor(val key: String) { OIL("Oil"), COOLANT("Coolant"), PTU("PTU"), RDU("RDU") }

data class TempLimits(val warnC: Double, val critC: Double)

/** One stretch of a drive under one profile: its limits and the highest valid reading per sensor (°C). */
data class ThermalSegment(
    val profile: String,                                   // "street" | "track" | "race" | "custom"
    val startMs: Long,
    val limits: Map<DriveThermalSensor, TempLimits>,
    val peaks: Map<DriveThermalSensor, Double> = emptyMap(),
)

/** TEMP flag: a peak at or above 95 % of the critical limit in effect (comparison in °C). */
const val THERMAL_FLAG_FRACTION = 0.95

/** The reading that raises a TEMP flag, °C — the check and its explanation both use this. */
fun thermalFlagThresholdC(critC: Double): Double = critC * THERMAL_FLAG_FRACTION

/** Recorder side: fed once per recorded sample, on the recorder's coroutine. */
class DriveThermalTracker {
    private val segments = mutableListOf<ThermalSegment>()
    /** First / last fresh reading per sensor — progression endpoints from the same readings as the peaks. */
    private val firstSeen = mutableMapOf<DriveThermalSensor, Double>()
    private val lastSeen = mutableMapOf<DriveThermalSensor, Double>()

    val segmentList: List<ThermalSegment> get() = segments.toList()

    /**
     * [readings]: current readings only — the caller passes a stale one as the −99 sentinel, so a held value is never
     * carried into a new (stricter) profile segment. Readings ≤ −90 °C are skipped.
     */
    fun sample(nowMs: Long, profile: String, limits: Map<DriveThermalSensor, TempLimits>,
               readings: Map<DriveThermalSensor, Double>) {
        val last = segments.lastOrNull()
        if (last == null || last.profile != profile || last.limits != limits) {
            segments += ThermalSegment(profile, nowMs, limits)
        }
        val valid = readings.filterValues { it > -90.0 }
        if (valid.isEmpty()) return
        for ((s, t) in valid) { firstSeen.putIfAbsent(s, t); lastSeen[s] = t }
        val seg = segments.last()
        val peaks = seg.peaks.toMutableMap()
        for ((s, t) in valid) peaks[s] = maxOf(peaks[s] ?: t, t)
        segments[segments.lastIndex] = seg.copy(peaks = peaks)
    }

    /** Null when no sample was taken. [climbRateCPerMin] = end-of-drive trend, informational only. */
    fun toJson(climbRateCPerMin: Map<DriveThermalSensor, Double> = emptyMap()): String? =
        if (segments.isEmpty()) null else DriveThermalRecord.encode(segments, climbRateCPerMin, firstSeen, lastSeen)
}

object DriveThermalRecord {
    const val VERSION = 2

    fun encode(segments: List<ThermalSegment>, climbRateCPerMin: Map<DriveThermalSensor, Double> = emptyMap(),
               firstC: Map<DriveThermalSensor, Double> = emptyMap(), lastC: Map<DriveThermalSensor, Double> = emptyMap()): String =
        JSONObject().apply {
            put("v", VERSION)
            put("segments", JSONArray().apply {
                for (seg in segments) put(JSONObject().apply {
                    put("profile", seg.profile)
                    put("startMs", seg.startMs)
                    put("limits", JSONObject().apply {
                        for ((s, l) in seg.limits) put(s.key, JSONObject().put("warnC", l.warnC).put("critC", l.critC))
                    })
                    put("peaks", JSONObject().apply { for ((s, t) in seg.peaks) put(s.key, t) })
                })
            })
            if (climbRateCPerMin.isNotEmpty()) {
                put("climbRateCPerMin", JSONObject().apply { for ((s, r) in climbRateCPerMin) put(s.key, r) })
            }
            if (firstC.isNotEmpty()) put("firstC", JSONObject().apply { for ((s, t) in firstC) put(s.key, t) })
            if (lastC.isNotEmpty()) put("lastC", JSONObject().apply { for ((s, t) in lastC) put(s.key, t) })
        }.toString()

    /**
     * First / last fresh readings per sensor; empty maps when the record predates them (early v2 records) or is
     * not a current-format record.
     */
    fun decodeEndpoints(json: String?): Pair<Map<DriveThermalSensor, Double>, Map<DriveThermalSensor, Double>> {
        if (decode(json) == null) return emptyMap<DriveThermalSensor, Double>() to emptyMap()
        val o = JSONObject(json!!)
        fun read(key: String) = o.optJSONObject(key)?.let { m ->
            DriveThermalSensor.entries.filter { m.has(it.key) }.associateWith { m.getDouble(it.key) }
        } ?: emptyMap()
        return read("firstC") to read("lastC")
    }

    /** Segments of a current-format record; null for legacy, missing or unreadable records. */
    fun decode(json: String?): List<ThermalSegment>? {
        if (json.isNullOrBlank() || !json.trimStart().startsWith("{")) return null
        return try {
            val o = JSONObject(json)
            if (o.optInt("v") != VERSION) return null
            val arr = o.getJSONArray("segments")
            (0 until arr.length()).map { i ->
                val seg = arr.getJSONObject(i)
                val lim = seg.getJSONObject("limits")
                val pk = seg.getJSONObject("peaks")
                ThermalSegment(
                    profile = seg.getString("profile"),
                    startMs = seg.getLong("startMs"),
                    limits = DriveThermalSensor.entries.filter { lim.has(it.key) }.associateWith {
                        val l = lim.getJSONObject(it.key); TempLimits(l.getDouble("warnC"), l.getDouble("critC"))
                    },
                    peaks = DriveThermalSensor.entries.filter { pk.has(it.key) }.associateWith { pk.getDouble(it.key) },
                )
            }
        } catch (_: Exception) { null }
    }
}

/**
 * How a recorded drive reads against its saved limits — the one evaluation the TRIP card's TEMP badge
 * and the drive summary share. It never looks at the current profile.
 */
data class DriveThermalReview(
    val basis: Basis,
    /** Profile id per segment, in order (PROFILE basis only). */
    val profiles: List<String>,
    val sensors: List<SensorReview>,
    /** The saved segments (PROFILE basis only) — limits and peaks per profile, for the export. */
    val segments: List<ThermalSegment> = emptyList(),
    /** First / last fresh recorded reading per sensor (PROFILE basis; absent in early records). */
    val firstC: Map<DriveThermalSensor, Double> = emptyMap(),
    val lastC: Map<DriveThermalSensor, Double> = emptyMap(),
    /** NONE basis: a record was saved but can't be read (damaged, or a format this version doesn't know). */
    val unreadable: Boolean = false,
) {
    enum class Basis {
        /** Saved with its profile limits. */
        PROFILE,
        /** Old fixed-limit snapshot: original TEMP rule kept, no limit levels, no profile claimed. */
        LEGACY,
        /** No thermal record. */
        NONE,
    }

    enum class PeakLevel { NORMAL, WARN, CRIT }

    data class SensorReview(
        val sensor: DriveThermalSensor,
        /** Highest recorded reading (legacy: the stored end-of-drive reading). */
        val peakC: Double,
        /**
         * Level of [peakC] against the limits of the segment it was recorded in — never a later or earlier
         * segment's. Null when the limits aren't known (legacy).
         */
        val peakLevel: PeakLevel?,
        val flagged: Boolean,
        /** The reading, critical limit and profile behind the flag (the closest to its limit) — may differ from the peak. */
        val flagReadingC: Double? = null,
        val flagCritC: Double? = null,
        val flagProfile: String? = null,
        /** Profile of the segment [peakLevel] comes from (PROFILE basis). */
        val peakProfile: String? = null,
        /** Legacy only: the old fixed limit stored with the drive-end reading, flagged or not. */
        val legacyLimitC: Double? = null,
    ) {
        /** The reading that raised the flag (°C): 95 % of [flagCritC]. */
        val flagThresholdC: Double? get() = flagCritC?.let(::thermalFlagThresholdC)
    }

    val flagged: Boolean get() = sensors.any { it.flagged }
    /** More than one segment: the profile was switched or a Custom profile was saved mid-drive. */
    val profileChanged: Boolean get() = profiles.size > 1
    fun sensor(s: DriveThermalSensor): SensorReview? = sensors.firstOrNull { it.sensor == s }

    /**
     * The peak to show / export for [s]: the record's judged peak for current-format drives, else [storedPeakC]
     * (DriveEntity's peak). Legacy records hold a drive-end reading, never used as a peak.
     */
    fun peakFor(s: DriveThermalSensor, storedPeakC: Double): Double =
        if (basis == Basis.PROFILE) sensor(s)?.peakC ?: storedPeakC else storedPeakC

    /** Start → peak → end; null = not available or not consistent with the peak. */
    data class Progression(val startC: Double?, val peakC: Double?, val endC: Double?) {
        val complete: Boolean get() = startC != null && peakC != null && endC != null
    }

    /**
     * The one endpoint rule for the summary rows and the export's curves. START / END are the record's first / last
     * fresh readings (the same readings as its peaks); without them, the [storedStartC] / [storedEndC] fallbacks
     * (DriveEntity's readings at recording start / stop, or the first / last point). Those aren't freshness-filtered and
     * are taken outside the sampled readings (before the first sample, at stop after a pause or link drop), so an
     * endpoint is kept only when it's a valid reading not above a valid peak. The peak itself is never raised,
     * clamped or dropped to fit; endpoints without a peak to check against are null.
     */
    fun progression(s: DriveThermalSensor, storedStartC: Double, storedPeakC: Double, storedEndC: Double): Progression {
        val peak = peakFor(s, storedPeakC).takeIf { it > -90.0 }
        fun endpoint(c: Double) = c.takeIf { peak != null && it > -90.0 && it <= peak }
        return Progression(endpoint(firstC[s] ?: storedStartC), peak, endpoint(lastC[s] ?: storedEndC))
    }

    companion object {
        fun from(json: String?): DriveThermalReview {
            DriveThermalRecord.decode(json)?.let { segs ->
                val (first, last) = DriveThermalRecord.decodeEndpoints(json)
                return fromSegments(segs).copy(firstC = first, lastC = last)
            }
            return legacy(json) ?: DriveThermalReview(Basis.NONE, emptyList(), emptyList(), unreadable = !json.isNullOrBlank())
        }

        fun fromSegments(segments: List<ThermalSegment>): DriveThermalReview {
            val sensors = DriveThermalSensor.entries.mapNotNull { s ->
                val judged = segments.mapNotNull { seg ->
                    val t = seg.peaks[s] ?: return@mapNotNull null
                    val l = seg.limits[s] ?: return@mapNotNull null
                    Triple(seg, t, l)
                }
                if (judged.isEmpty()) return@mapNotNull null
                val peak = judged.maxOf { it.second }
                // The peak's own segment(s) — a later, stricter profile never re-colours an earlier reading
                fun levelOf(t: Double, l: TempLimits) =
                    when { t >= l.critC -> PeakLevel.CRIT; t >= l.warnC -> PeakLevel.WARN; else -> PeakLevel.NORMAL }
                val peakSeg = judged.filter { it.second == peak }.maxBy { (_, t, l) -> levelOf(t, l) }
                val level = levelOf(peakSeg.second, peakSeg.third)
                val worst = judged.filter { (_, t, l) -> t >= thermalFlagThresholdC(l.critC) }
                    .maxByOrNull { (_, t, l) -> t / l.critC }
                SensorReview(s, peak, level, worst != null,
                    worst?.second, worst?.third?.critC, worst?.first?.profile, peakProfile = peakSeg.first.profile)
            }
            return DriveThermalReview(Basis.PROFILE, segments.map { it.profile }, sensors, segments)
        }

        /** The pre-profile array: [{"sensor":"Oil","peakC":…,"critThresholdC":…}, …] — evaluated exactly as before. */
        private fun legacy(json: String?): DriveThermalReview? {
            if (json.isNullOrBlank() || !json.trimStart().startsWith("[")) return null
            return try {
                val arr = JSONArray(json)
                val sensors = (0 until arr.length()).mapNotNull { i ->
                    val o = arr.getJSONObject(i)
                    val s = DriveThermalSensor.entries.firstOrNull { it.key == o.optString("sensor") } ?: return@mapNotNull null
                    val t = o.getDouble("peakC"); val crit = o.getDouble("critThresholdC")
                    val flagged = t >= thermalFlagThresholdC(crit)
                    SensorReview(s, t, null, flagged, if (flagged) t else null, if (flagged) crit else null, legacyLimitC = crit)
                }
                DriveThermalReview(Basis.LEGACY, emptyList(), sensors)
            } catch (_: Exception) { null }
        }
    }
}
