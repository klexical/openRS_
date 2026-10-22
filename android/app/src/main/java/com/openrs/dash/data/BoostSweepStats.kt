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
    val sampleCount: Int,            // number of loaded samples the stats are drawn from
    val avgWgdcAtPeak: Double? = null,       // mean fresh WGDC where actual ≥ 90 % of the drive's peak (#175)
    val byGear: List<GearBoost> = emptyList() // loaded samples per gear 1–6, ascending (#175)
) {
    /** Boost under demand in one gear. [peakBoostPsi] is gauge vs standard atmosphere (no baro per point). */
    data class GearBoost(val gear: Int, val peakBoostPsi: Double, val avgDeltaPsi: Double, val samples: Int)

    companion object {
        /** Samples within this fraction of the drive's peak actual TIP count as "at peak boost". */
        const val PEAK_FRACTION = 0.9

        private const val KPA_TO_PSI = 0.14503773
        private const val STD_ATM_KPA = 101.325

        /** Below this target boost, the sample is effectively idle/cruise — exclude. */
        const val LOADED_TARGET_KPA_MIN = 110.0

        /** WGDC at or above this is considered "wastegate saturation" (duty cycle floored). */
        const val WGDC_SATURATION_PCT = 95.0

        /** Compute stats from a drive's point stream. Returns null when no loaded samples exist. */
        fun from(points: List<DrivePointEntity>): BoostSweepStats? {
            val loaded = points.filter { it.boostTargetKpa >= LOADED_TARGET_KPA_MIN && it.tipActualKpa > 0.0 }
            if (loaded.isEmpty()) return null

            val kpaToPsi = KPA_TO_PSI
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

            val peakActual = loaded.maxOf { it.tipActualKpa }
            val atPeakWgdc = loaded
                .filter { it.tipActualKpa >= peakActual * PEAK_FRACTION && it.wgdcPct >= 0.0 }
                .map { it.wgdcPct }

            val byGear = loaded
                .mapNotNull { pt -> pt.gear.toIntOrNull()?.takeIf { it in 1..6 }?.let { it to pt } }
                .groupBy({ it.first }, { it.second })
                .toSortedMap()
                .map { (gear, pts) ->
                    GearBoost(
                        gear = gear,
                        peakBoostPsi = (pts.maxOf { it.tipActualKpa } - STD_ATM_KPA) * kpaToPsi,
                        avgDeltaPsi = pts.sumOf { (it.tipActualKpa - it.boostTargetKpa) * kpaToPsi } / pts.size,
                        samples = pts.size
                    )
                }

            return BoostSweepStats(
                avgDeltaPsi = avg,
                worstDeltaPsi = worst,
                worstRpm = worstRpm,
                maxWgdc = maxW,
                wgdcSaturated = maxW >= WGDC_SATURATION_PCT,
                sampleCount = loaded.size,
                avgWgdcAtPeak = atPeakWgdc.takeIf { it.isNotEmpty() }?.average(),
                byGear = byGear
            )
        }
    }
}
