package com.openrs.dash.data

/**
 * Rolling-window temperature predictor for time-to-critical extrapolation.
 *
 * Maintains per-sensor sample windows (120 s at ~1 Hz), computes linear
 * regression slope (°C/min), and extrapolates time remaining before a
 * critical threshold is reached. Pure Kotlin — no Android dependencies.
 */
class ThermalPredictor(
    private val windowMs: Long = 120_000L,
    private val minSamples: Int = 10
) {

    data class SensorPrediction(
        val sensorLabel: String,
        val currentC: Double,
        val climbRateCPerMin: Double,
        val timeToCriticalMin: Double?,
        val critThresholdC: Double
    )

    enum class ClimbTrend { RISING, COOLING, STABLE }

    private val windows = mutableMapOf<String, ArrayDeque<Pair<Long, Double>>>()
    private val dismissedUntil = mutableMapOf<String, Long>()

    /** Append a temperature sample. Sentinel values (-99.0) are ignored. */
    fun recordSample(label: String, timestampMs: Long, tempC: Double) {
        if (tempC <= -90.0) return
        val window = windows.getOrPut(label) { ArrayDeque() }
        window.addLast(timestampMs to tempC)
        // Trim samples older than the window
        val cutoff = timestampMs - windowMs
        while (window.isNotEmpty() && window.first().first < cutoff) window.removeFirst()
    }

    /**
     * Predict time-to-critical for a sensor.
     *
     * @param operatingFloorC minimum temp before predictions activate (avoids
     *        false alerts during warm-up)
     * @return null if insufficient data, below operating floor, or not approaching critical
     */
    fun predict(
        label: String,
        critThresholdC: Double,
        operatingFloorC: Double
    ): SensorPrediction? {
        val window = windows[label] ?: return null
        if (window.size < minSamples) return null

        val currentC = window.last().second
        if (currentC < operatingFloorC) return null
        if (currentC >= critThresholdC) {
            return SensorPrediction(label, currentC, 0.0, 0.0, critThresholdC)
        }

        val slopeCPerMin = linearSlopeCPerMin(window) ?: return null

        val timeToCrit = if (slopeCPerMin > 0.1) {
            (critThresholdC - currentC) / slopeCPerMin
        } else null

        return SensorPrediction(label, currentC, slopeCPerMin, timeToCrit, critThresholdC)
    }

    /** Classify the current trend for a sensor. */
    fun trend(label: String): ClimbTrend {
        val window = windows[label] ?: return ClimbTrend.STABLE
        if (window.size < minSamples) return ClimbTrend.STABLE
        val slope = linearSlopeCPerMin(window) ?: return ClimbTrend.STABLE
        return when {
            slope > 0.5  -> ClimbTrend.RISING
            slope < -0.5 -> ClimbTrend.COOLING
            else         -> ClimbTrend.STABLE
        }
    }

    /** Snooze alerts for [label] for 2 minutes. */
    fun dismiss(label: String) {
        dismissedUntil[label] = System.currentTimeMillis() + 120_000L
    }

    /** Whether an alert should be shown for this sensor right now. */
    fun shouldAlert(
        label: String,
        critThresholdC: Double,
        operatingFloorC: Double,
        horizonMin: Double = 5.0
    ): Boolean {
        val snoozed = dismissedUntil[label]
        if (snoozed != null && System.currentTimeMillis() < snoozed) return false
        val pred = predict(label, critThresholdC, operatingFloorC) ?: return false
        val ttc = pred.timeToCriticalMin ?: return false
        return ttc <= horizonMin
    }

    /** Clear all data (e.g. on session reset). */
    fun reset() {
        windows.clear()
        dismissedUntil.clear()
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    /**
     * Least-squares linear regression slope in °C per minute.
     * t values are relative seconds from the window start.
     */
    internal fun linearSlopeCPerMin(window: ArrayDeque<Pair<Long, Double>>): Double? {
        val n = window.size
        if (n < 2) return null
        val t0 = window.first().first
        var sumT  = 0.0
        var sumT2 = 0.0
        var sumY  = 0.0
        var sumTY = 0.0
        for ((ms, temp) in window) {
            val t = (ms - t0) / 1000.0  // relative seconds
            sumT  += t
            sumT2 += t * t
            sumY  += temp
            sumTY += t * temp
        }
        val denom = n * sumT2 - sumT * sumT
        if (denom == 0.0) return null
        val slopePerSec = (n * sumTY - sumT * sumY) / denom
        return slopePerSec * 60.0  // convert to °C/min
    }
}
