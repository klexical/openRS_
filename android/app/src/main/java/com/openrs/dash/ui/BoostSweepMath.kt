package com.openrs.dash.ui

import com.openrs.dash.data.BoostSessionLog

/**
 * Pure binning for [BoostSweepChart] (rc.6, #175) — the live window's samples averaged per
 * RPM bin, so a sweep reads as one line per channel across the rev range regardless of how
 * many pulls are in the window. Empty bins are null and draw as gaps.
 */
internal object BoostSweepMath {

    const val RPM_MIN = 1_500.0
    const val RPM_MAX = 7_000.0
    const val BIN_RPM = 250.0
    val BIN_COUNT = ((RPM_MAX - RPM_MIN) / BIN_RPM).toInt()

    /** Minimum Y span (kPa) so an idle-only window doesn't zoom into noise. */
    const val MIN_SPAN_KPA = 40.0

    data class Bin(
        val rpmMid: Double,
        val actualKpa: Double,
        val targetKpa: Double?,     // null = no fresh target in this bin
        val wgdcPct: Double?,       // null = no fresh WGDC in this bin
        val samples: Int
    )

    /** Bin index for [rpm], or null outside [RPM_MIN, RPM_MAX). */
    fun binIndex(rpm: Double): Int? =
        if (rpm < RPM_MIN || rpm >= RPM_MAX) null else ((rpm - RPM_MIN) / BIN_RPM).toInt()

    /** [BIN_COUNT] slots, ascending RPM; null where the window has no sample. */
    fun bins(samples: List<BoostSessionLog.Sample>): List<Bin?> {
        val grouped = samples.groupBy { binIndex(it.rpm) }
        return List(BIN_COUNT) { i ->
            val s = grouped[i] ?: return@List null
            Bin(
                rpmMid = RPM_MIN + (i + 0.5) * BIN_RPM,
                actualKpa = s.sumOf { it.actualKpa } / s.size,
                targetKpa = s.mapNotNull { it.targetKpa }.takeIf { it.isNotEmpty() }?.average(),
                wgdcPct = s.mapNotNull { it.wgdcPct }.takeIf { it.isNotEmpty() }?.average(),
                samples = s.size
            )
        }
    }

    /** Y range (absolute kPa) covering actual + target, at least [MIN_SPAN_KPA] tall. */
    fun kpaRange(bins: List<Bin?>): Pair<Double, Double> {
        val values = bins.filterNotNull().flatMap { listOfNotNull(it.actualKpa, it.targetKpa) }
        if (values.isEmpty()) return 90.0 to 90.0 + MIN_SPAN_KPA
        var lo = values.min()
        var hi = values.max()
        if (hi - lo < MIN_SPAN_KPA) {
            val mid = (hi + lo) / 2
            lo = mid - MIN_SPAN_KPA / 2
            hi = mid + MIN_SPAN_KPA / 2
        }
        return lo to hi
    }
}
