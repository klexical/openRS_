package com.openrs.dash.data

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

data class LapResult(
    val lapNumber: Int,
    val lapTimeMs: Long,
    val peakRpm: Int = 0,
    /** Mixed sources, no source column (#207) — stored, never shown or exported. */
    val peakBoostPsi: Double = 0.0,
    val peakLateralG: Double = 0.0,
    val peakSpeedKph: Double = 0.0
)

data class StartFinishLine(
    val lat: Double,
    val lng: Double,
    /** Direction of travel across the line (0° = north); null until known — see [LapTimer.setStartFinish]. */
    val bearing: Float?
)

enum class LapTimerState { IDLE, ARMED, TIMING }

/**
 * One GPS fix for the timer. [bearing] is null when the fix reports none (`Location.hasBearing()` false) — never 0°,
 * which is a real heading (north). [timeMs] is the fix's own time, used for ordering and lap times; [ageMs] is how old
 * the fix was when delivered; [receivedAtMs] is the wall clock then (the overlay's running time).
 */
data class LapFix(
    val lat: Double,
    val lng: Double,
    val bearing: Float?,
    val timeMs: Long,
    val ageMs: Long,
    val receivedAtMs: Long,
    val speedKph: Double,
    val rpm: Int = 0,
    val boostPsi: Double = 0.0,
    val lateralG: Double = 0.0
)

/** What the timer did with one fix — logged by DriveRecorder so a missed lap can be told from a fix that never came. */
enum class FixVerdict {
    NO_LINE,        // no S/F line set
    CLOSED_DRIVE,   // a fix of a drive already stopped (STOP → START handoff)
    STALE,          // older than MAX_FIX_AGE_MS when delivered
    OUT_OF_ORDER,   // fix time not after the last accepted fix (replayed / reordered)
    NO_SEGMENT,     // nothing to pair with: first fix, after a recording gap, or > MAX_SEGMENT_MS since the last
    LINE_DIRECTION, // a line set without a bearing took the course of the departure from it
    NOT_DEPARTED,   // near the line and not yet left it (DEPART_M) since it was set / last crossed
    NO_CROSSING,    // moved without crossing the line
    TOO_SLOW,       // crossed under MIN_SPEED_KPH
    WRONG_HEADING,  // crossed more than MAX_HEADING_DIFF from the line's direction
    TOO_SOON,       // crossed less than MIN_LAP_MS after the lap started
    TIMING_STARTED, // the timing pass
    LAP,            // a lap completed
}

data class FixReport(
    val verdict: FixVerdict,
    val lap: LapResult? = null,
    /** Position relative to the S/F point in metres (x east, y north) — logs carry these, never coordinates. */
    val xM: Double? = null,
    val yM: Double? = null,
)

/**
 * Lap timing from GPS fixes crossing a start/finish line (#237).
 *
 * The line is a gate [GATE_HALF_WIDTH_M] either side of the S/F point, perpendicular to its bearing. A lap counts when
 * the segment between two consecutive accepted fixes crosses that gate in the line's direction — not when a fix
 * happens to be near the point — and the crossing time is interpolated along the segment, so a crossing between 1 Hz
 * samples counts at the right time. A crossing also needs CAN speed ≥ [MIN_SPEED_KPH], a heading within
 * ±[MAX_HEADING_DIFF] of the line (the fix's bearing, else the segment's course), the car to have left the line by
 * [DEPART_M] since the previous counted crossing, and ≥ [MIN_LAP_MS] per lap.
 *
 * Fixes must be fresh (≤ [MAX_FIX_AGE_MS] old) and in fix-time order. Each fix belongs to a drive: [takeLaps] closes a
 * drive, and a later fix of it (still in flight at STOP) is refused inside the lock, so it can't start or advance the
 * next drive's timer. Drive ids only grow (Room AUTOINCREMENT).
 */
class LapTimer {
    // Public state
    var state: LapTimerState = LapTimerState.IDLE
        private set
    var startFinish: StartFinishLine? = null
        private set
    var laps: List<LapResult> = emptyList()
        private set
    /** Wall-clock start of the current lap (overlay). */
    var currentLapStartMs: Long = 0L
        private set
    var bestLapMs: Long = Long.MAX_VALUE
        private set

    // Current lap peak tracking
    private var currentPeakRpm = 0
    private var currentPeakBoost = 0.0
    private var currentPeakLatG = 0.0
    private var currentPeakSpeed = 0.0

    // Track state: the last accepted fix (local metres + fix time), departure, lap start on the fix clock
    private var prevX = 0.0
    private var prevY = 0.0
    private var prevTimeMs: Long? = null
    private var lastTimeMs: Long? = null
    private var departed = false
    private var lapStartFixMs = 0.0

    // Drive ownership: the drive feeding the timer, and every drive id up to closedThrough has been stopped
    private var ownerDriveId = 0L
    private var closedThrough = 0L

    /**
     * Set the line at the car's position. [bearing] = the fix's bearing, or null when it has none — then the line takes
     * the course from this point to the first fix [DEPART_M] away (the way the car leaves it), never 0° north.
     */
    @Synchronized fun setStartFinish(lat: Double, lng: Double, bearing: Float?) {
        startFinish = StartFinishLine(lat, lng, bearing)
        state = LapTimerState.ARMED
        reset()
    }

    /** Clear the S/F line and stop timing. */
    @Synchronized fun clearStartFinish() {
        startFinish = null
        state = LapTimerState.IDLE
        reset()
    }

    /**
     * Hands the laps [driveId] completed to it (DriveRecorder, at its STOP — #236), closes that drive and re-arms at the
     * same line, so the next drive's laps are its own and start at its own timing pass. Null when there are none, or
     * when the timer already belongs to a later drive.
     */
    @Synchronized fun takeLaps(driveId: Long): Pair<StartFinishLine, List<LapResult>>? {
        if (driveId > closedThrough) closedThrough = driveId
        if (ownerDriveId > driveId) return null
        ownerDriveId = 0L
        val sf = startFinish ?: return null
        val done = laps
        state = LapTimerState.ARMED
        reset()
        return if (done.isEmpty() || sf.bearing == null) null else sf to done
    }

    /** Recording skipped fixes (pause, adapter gap): no crossing is formed across them. */
    @Synchronized fun breakTrack(driveId: Long) {
        if (driveId == ownerDriveId) prevTimeMs = null
    }

    /** Reset all laps but keep the S/F line. */
    @Synchronized fun reset() {
        laps = emptyList()
        currentLapStartMs = 0L
        bestLapMs = Long.MAX_VALUE
        resetPeaks()
        prevTimeMs = null
        lastTimeMs = null
        departed = false
        lapStartFixMs = 0.0
    }

    private fun resetPeaks() {
        currentPeakRpm = 0
        currentPeakBoost = 0.0
        currentPeakLatG = 0.0
        currentPeakSpeed = 0.0
    }

    /**
     * Feed one recorded fix of [driveId] (DriveRecorder, every recorded fix). Synchronized: the recorder's loop feeds it
     * while TRIP sets / clears the line and STOP takes the laps.
     */
    @Synchronized fun onFix(driveId: Long, fix: LapFix): FixReport {
        val sf = startFinish ?: return FixReport(FixVerdict.NO_LINE)
        if (state == LapTimerState.IDLE) return FixReport(FixVerdict.NO_LINE)
        if (driveId <= closedThrough || driveId < ownerDriveId) return FixReport(FixVerdict.CLOSED_DRIVE)
        if (driveId > ownerDriveId) {
            if (ownerDriveId != 0L) { state = LapTimerState.ARMED; reset() }      // a drive never stopped here: start clean
            ownerDriveId = driveId
        }

        val (x, y) = localMeters(sf, fix.lat, fix.lng)
        fun report(v: FixVerdict, lap: LapResult? = null) = FixReport(v, lap, x, y)
        if (fix.ageMs > MAX_FIX_AGE_MS) return report(FixVerdict.STALE)
        lastTimeMs?.let { if (fix.timeMs <= it) return report(FixVerdict.OUT_OF_ORDER) }
        lastTimeMs = fix.timeMs

        if (state == LapTimerState.TIMING) {
            if (fix.rpm > currentPeakRpm) currentPeakRpm = fix.rpm
            if (fix.boostPsi > currentPeakBoost) currentPeakBoost = fix.boostPsi
            if (abs(fix.lateralG) > currentPeakLatG) currentPeakLatG = abs(fix.lateralG)
            if (fix.speedKph > currentPeakSpeed) currentPeakSpeed = fix.speedKph
        }

        val dist = hypot(x, y)
        val px = prevX; val py = prevY; val pt = prevTimeMs
        prevX = x; prevY = y; prevTimeMs = fix.timeMs

        val lineBearing = sf.bearing ?: run {
            if (dist < DEPART_M) return report(FixVerdict.NOT_DEPARTED)
            startFinish = sf.copy(bearing = courseDeg(0.0, 0.0, x, y))
            departed = true
            return report(FixVerdict.LINE_DIRECTION)
        }
        val wasDeparted = departed
        if (dist >= DEPART_M) departed = true
        if (pt == null || fix.timeMs - pt > MAX_SEGMENT_MS) return report(FixVerdict.NO_SEGMENT)

        // Along-track (in the line's direction) and cross-track distance of both ends; the gate is a = 0, |c| ≤ half-width
        val b = Math.toRadians(lineBearing.toDouble())
        val fx = sin(b); val fy = cos(b)
        val aPrev = px * fx + py * fy
        val aCur = x * fx + y * fy
        if (!(aPrev < 0.0 && aCur >= 0.0)) return report(FixVerdict.NO_CROSSING)
        val frac = -aPrev / (aCur - aPrev)
        val cAt = (px + frac * (x - px)) * fy - (py + frac * (y - py)) * fx
        if (abs(cAt) > GATE_HALF_WIDTH_M) return report(FixVerdict.NO_CROSSING)

        if (!wasDeparted) return report(FixVerdict.NOT_DEPARTED)
        if (fix.speedKph < MIN_SPEED_KPH) return report(FixVerdict.TOO_SLOW)
        val heading = fix.bearing ?: courseDeg(px, py, x, y)
        if (abs(angleDiff(heading, lineBearing)) > MAX_HEADING_DIFF) return report(FixVerdict.WRONG_HEADING)

        val crossFixMs = pt + frac * (fix.timeMs - pt)
        val crossWallMs = fix.receivedAtMs - Math.round(fix.timeMs - crossFixMs)
        return when (state) {
            LapTimerState.ARMED -> {
                state = LapTimerState.TIMING
                startLap(crossFixMs, crossWallMs, dist)
                report(FixVerdict.TIMING_STARTED)
            }
            LapTimerState.TIMING -> {
                val elapsed = Math.round(crossFixMs - lapStartFixMs)
                if (elapsed < MIN_LAP_MS) return report(FixVerdict.TOO_SOON)
                val result = LapResult(
                    lapNumber = laps.size + 1,
                    lapTimeMs = elapsed,
                    peakRpm = currentPeakRpm,
                    peakBoostPsi = currentPeakBoost,
                    peakLateralG = currentPeakLatG,
                    peakSpeedKph = currentPeakSpeed
                )
                laps = laps + result
                if (elapsed < bestLapMs) bestLapMs = elapsed
                startLap(crossFixMs, crossWallMs, dist)
                report(FixVerdict.LAP, result)
            }
            else -> report(FixVerdict.NO_LINE)
        }
    }

    private fun startLap(crossFixMs: Double, crossWallMs: Long, dist: Double) {
        lapStartFixMs = crossFixMs
        currentLapStartMs = crossWallMs
        departed = dist >= DEPART_M
        resetPeaks()
    }

    /** Current lap elapsed time in ms, or 0 if not timing. */
    fun currentLapElapsedMs(): Long =
        if (state == LapTimerState.TIMING && currentLapStartMs > 0L)
            System.currentTimeMillis() - currentLapStartMs
        else 0L

    /** Delta of current lap vs best lap, or null if no best yet. */
    fun deltaVsBestMs(): Long? {
        if (bestLapMs == Long.MAX_VALUE) return null
        val elapsed = currentLapElapsedMs()
        if (elapsed == 0L) return null
        return elapsed - bestLapMs
    }

    companion object {
        /** Gate half-width either side of the S/F point (was the 15 m geofence radius). */
        const val GATE_HALF_WIDTH_M = 15.0
        /** Leave the line by this much before the next crossing counts. */
        const val DEPART_M = 2 * GATE_HALF_WIDTH_M
        const val MIN_SPEED_KPH = 20.0
        const val MAX_HEADING_DIFF = 30f
        const val MIN_LAP_MS = 10_000L
        /** A fix older than this when delivered is not used (1 Hz requests). */
        const val MAX_FIX_AGE_MS = 2_000L
        /** No crossing is interpolated across a longer gap between fixes (up to 4 missed 1 Hz fixes). */
        const val MAX_SEGMENT_MS = 5_000L

        private const val EARTH_M = 6371000.0

        /** Equirectangular metres from the S/F point (x east, y north) — exact enough within a circuit. */
        fun localMeters(sf: StartFinishLine, lat: Double, lng: Double): Pair<Double, Double> {
            val y = Math.toRadians(lat - sf.lat) * EARTH_M
            val x = Math.toRadians(lng - sf.lng) * EARTH_M * cos(Math.toRadians(sf.lat))
            return x to y
        }

        /** Course from (x1, y1) to (x2, y2), degrees 0..360 (0 = north). */
        fun courseDeg(x1: Double, y1: Double, x2: Double, y2: Double): Float =
            ((Math.toDegrees(atan2(x2 - x1, y2 - y1)) + 360.0) % 360.0).toFloat()

        /** Haversine distance in meters. */
        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = EARTH_M
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = kotlin.math.sin(dLat / 2).let { it * it } +
                    kotlin.math.cos(Math.toRadians(lat1)) *
                    kotlin.math.cos(Math.toRadians(lat2)) *
                    kotlin.math.sin(dLon / 2).let { it * it }
            return r * 2.0 * kotlin.math.atan2(kotlin.math.sqrt(a), kotlin.math.sqrt(1 - a))
        }

        /** Angle difference normalized to [-180, 180]. */
        fun angleDiff(a: Float, b: Float): Float {
            var diff = a - b
            while (diff > 180f) diff -= 360f
            while (diff < -180f) diff += 360f
            return diff
        }
    }
}
