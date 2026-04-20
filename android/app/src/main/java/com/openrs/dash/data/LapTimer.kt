package com.openrs.dash.data

data class LapResult(
    val lapNumber: Int,
    val lapTimeMs: Long,
    val peakRpm: Int = 0,
    val peakBoostPsi: Double = 0.0,
    val peakLateralG: Double = 0.0,
    val peakSpeedKph: Double = 0.0
)

data class StartFinishLine(
    val lat: Double,
    val lng: Double,
    val bearing: Float  // heading in degrees when S/F was set
)

enum class LapTimerState { IDLE, ARMED, TIMING }

class LapTimer {
    // Public state
    var state: LapTimerState = LapTimerState.IDLE
        private set
    var startFinish: StartFinishLine? = null
        private set
    var laps: List<LapResult> = emptyList()
        private set
    var currentLapStartMs: Long = 0L
        private set
    var bestLapMs: Long = Long.MAX_VALUE
        private set

    // Current lap peak tracking
    private var currentPeakRpm = 0
    private var currentPeakBoost = 0.0
    private var currentPeakLatG = 0.0
    private var currentPeakSpeed = 0.0

    /** Set/update the start/finish line at the given GPS position and heading. */
    fun setStartFinish(lat: Double, lng: Double, bearing: Float) {
        startFinish = StartFinishLine(lat, lng, bearing)
        state = LapTimerState.ARMED
        reset()
    }

    /** Clear the S/F line and stop timing. */
    fun clearStartFinish() {
        startFinish = null
        state = LapTimerState.IDLE
        reset()
    }

    /** Reset all laps but keep the S/F line. */
    fun reset() {
        laps = emptyList()
        currentLapStartMs = 0L
        bestLapMs = Long.MAX_VALUE
        currentPeakRpm = 0
        currentPeakBoost = 0.0
        currentPeakLatG = 0.0
        currentPeakSpeed = 0.0
    }

    /**
     * Feed a GPS + telemetry update. Call this on every DriveRecorder location update.
     * Returns a LapResult if a lap was just completed, null otherwise.
     */
    fun onLocationUpdate(
        lat: Double, lng: Double,
        bearing: Float,
        speedKph: Double,
        timestampMs: Long,
        rpm: Int,
        boostPsi: Double,
        lateralG: Double
    ): LapResult? {
        val sf = startFinish ?: return null
        if (state == LapTimerState.IDLE) return null

        // Update current lap peaks
        if (state == LapTimerState.TIMING) {
            if (rpm > currentPeakRpm) currentPeakRpm = rpm
            if (boostPsi > currentPeakBoost) currentPeakBoost = boostPsi
            if (kotlin.math.abs(lateralG) > currentPeakLatG) currentPeakLatG = kotlin.math.abs(lateralG)
            if (speedKph > currentPeakSpeed) currentPeakSpeed = speedKph
        }

        // Check geofence crossing
        val distToSF = haversineMeters(lat, lng, sf.lat, sf.lng)
        if (distToSF > 15.0) return null  // Not near S/F line
        if (speedKph < 20.0) return null  // Too slow (pit lane filter)

        // Heading filter: within +/-30 degrees of S/F bearing
        val headingDiff = angleDiff(bearing, sf.bearing)
        if (kotlin.math.abs(headingDiff) > 30f) return null

        return when (state) {
            LapTimerState.ARMED -> {
                // First crossing — start timing
                state = LapTimerState.TIMING
                currentLapStartMs = timestampMs
                currentPeakRpm = 0
                currentPeakBoost = 0.0
                currentPeakLatG = 0.0
                currentPeakSpeed = 0.0
                null
            }
            LapTimerState.TIMING -> {
                val elapsed = timestampMs - currentLapStartMs
                if (elapsed < 10_000L) return null  // Debounce: minimum 10s lap

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

                // Reset for next lap
                currentLapStartMs = timestampMs
                currentPeakRpm = 0
                currentPeakBoost = 0.0
                currentPeakLatG = 0.0
                currentPeakSpeed = 0.0
                result
            }
            else -> null
        }
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
        /** Haversine distance in meters. */
        fun haversineMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371000.0
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
