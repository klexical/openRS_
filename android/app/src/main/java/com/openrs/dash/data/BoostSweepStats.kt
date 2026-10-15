package com.openrs.dash.data

/**
 * Boost target-vs-actual sweep statistics computed from a drive's DrivePointEntity series.
 *
 * Target DID polls (TIP desired 0x0466) only arrive while PCM is responsive and the ECU
 * has a valid target; many points on an idle/cruise segment carry 0 for both target and
 * actual. We filter to **loaded** samples (target > 110 kPa ≈ 1.3 PSI boost demanded) so
 * delta averages reflect boost-under-demand, not idle noise.
 *
 * rc.5 (#184): the recorder writes TIP/WGDC only on a fresh decode — otherwise
 * `tipActualKpa = 0.0` and `wgdcPct = -1.0`. A sample counts only when target AND
 * actual were both freshly polled; WGDC sentinels are ignored. v10 drives (which hold
 * repeated values) still compute as before.
 */
data class BoostSweepStats(
    val avgDeltaPsi: Double,         // (actual − target) averaged across loaded samples
    val worstDeltaPsi: Double,       // most-negative delta (ECU asking for more than engine delivers)
    val worstRpm: Int,               // RPM at the worst-delta sample
    val maxWgdc: Double,             // peak WGDC % observed on loaded samples
    val wgdcSaturated: Boolean,      // true if maxWgdc >= WGDC_SATURATION_PCT
    val sampleCount: Int             // number of loaded samples the stats are drawn from
) {
    companion object {
        /** Below this target boost, the sample is effectively idle/cruise — exclude. */
        const val LOADED_TARGET_KPA_MIN = 110.0

        /** WGDC at or above this is considered "wastegate saturation" (duty cycle floored). */
        const val WGDC_SATURATION_PCT = 95.0

        /** Compute stats from a drive's point stream. Returns null when no loaded samples exist. */
        fun from(points: List<DrivePointEntity>): BoostSweepStats? {
            val loaded = points.filter { it.boostTargetKpa >= LOADED_TARGET_KPA_MIN && it.tipActualKpa > 0.0 }
            if (loaded.isEmpty()) return null

            val kpaToPsi = 0.14503773
            var deltaSum = 0.0
            var worst = Double.POSITIVE_INFINITY
            var worstRpm = 0
            var maxW = 0.0

            for (pt in loaded) {
                val deltaPsi = (pt.tipActualKpa - pt.boostTargetKpa) * kpaToPsi
                deltaSum += deltaPsi
                if (deltaPsi < worst) {
                    worst = deltaPsi
                    worstRpm = pt.rpm
                }
                if (pt.wgdcPct >= 0.0 && pt.wgdcPct > maxW) maxW = pt.wgdcPct
            }
            val avg = deltaSum / loaded.size
            return BoostSweepStats(
                avgDeltaPsi = avg,
                worstDeltaPsi = worst,
                worstRpm = worstRpm,
                maxWgdc = maxW,
                wgdcSaturated = maxW >= WGDC_SATURATION_PCT,
                sampleCount = loaded.size
            )
        }
    }
}
