package com.openrs.dash.data

/**
 * Fuel trim drift tracker (rc.5, roadmap 2.2).
 *
 * STFT/LTFT (PCM 0xF406 / 0xF407) are slow-group DIDs (~every 19 s with the engine running).
 * Per drive we average only **fresh** decodes taken on a **warm, running** engine, so
 * open-loop warm-up enrichment and held values don't skew the result. Positive trim = the
 * PCM is adding fuel (running lean); negative = pulling fuel (running rich).
 */
class FuelTrimAccumulator {
    private var stftSum = 0.0
    private var stftN = 0
    private var ltftSum = 0.0
    private var ltftN = 0

    /** Feed one recorder sample. `*Fresh` = the value was freshly polled (FreshnessGate). */
    fun add(stftFresh: Boolean, stft: Double, ltftFresh: Boolean, ltft: Double, coolantC: Double, rpm: Double) {
        if (coolantC < WARM_COOLANT_C || rpm < MIN_RPM) return
        if (stftFresh) { stftSum += stft; stftN++ }
        if (ltftFresh) { ltftSum += ltft; ltftN++ }
    }

    /** Averages (null below [MIN_SAMPLES]) and the qualifying sample count. */
    fun result(): Result = Result(
        avgStftPct = if (stftN >= MIN_SAMPLES) stftSum / stftN else null,
        avgLtftPct = if (ltftN >= MIN_SAMPLES) ltftSum / ltftN else null,
        samples = maxOf(stftN, ltftN)
    )

    data class Result(val avgStftPct: Double?, val avgLtftPct: Double?, val samples: Int)

    companion object {
        /** Closed-loop proxy: coolant at or above this (°C). */
        const val WARM_COOLANT_C = 70.0
        /** Engine running (not just ignition). */
        const val MIN_RPM = 500.0
        /** Fewer fresh warm samples than this → no average stored for the drive. */
        const val MIN_SAMPLES = 3
    }
}

/** Cross-drive LTFT trend check (roadmap 2.2: alert on ±8 % for two consecutive drives). */
object FuelTrimDrift {
    const val ALERT_LTFT_PCT = 8.0
    const val BAND_OK_PCT = 5.0
    const val BAND_WARN_PCT = 10.0

    enum class Direction(val label: String) { LEAN("LEAN"), RICH("RICH") }

    /**
     * [drivesNewestFirst] = drives with a stored LTFT average, newest first. Returns the drift
     * direction when the two most recent both exceed ±[ALERT_LTFT_PCT] in the same direction.
     */
    fun alert(drivesNewestFirst: List<DriveEntity>): Direction? {
        val recent = drivesNewestFirst.mapNotNull { it.avgLtftPct }.take(2)
        if (recent.size < 2) return null
        return when {
            recent.all { it > ALERT_LTFT_PCT }  -> Direction.LEAN
            recent.all { it < -ALERT_LTFT_PCT } -> Direction.RICH
            else -> null
        }
    }
}
