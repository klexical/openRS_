package com.openrs.dash.ui.trip

/**
 * Pure geometry helpers for [DriveTimeSeries] (rc.5, #188) — kept free of Compose so
 * gap handling and shared-axis ranges are unit-testable.
 */
internal object TimeSeriesMath {

    /** Bridge over invalid samples shorter than this; break the line on longer gaps. */
    const val BREAK_GAP_MS = 5_000L

    /**
     * Split a series into drawable runs of sample indices. Invalid samples (absent /
     * sentinel) are skipped; consecutive valid samples further apart than [breakGapMs]
     * start a new run so the line doesn't imply data across a long hole.
     */
    fun segments(
        timestamps: List<Long>,
        valid: List<Boolean>,
        breakGapMs: Long = BREAK_GAP_MS
    ): List<List<Int>> {
        val runs = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var lastTs = Long.MIN_VALUE
        for (i in timestamps.indices) {
            if (!valid[i]) continue
            val ts = timestamps[i]
            if (current.isNotEmpty() && ts - lastTs > breakGapMs) {
                runs += current
                current = ArrayList()
            }
            current += i
            lastTs = ts
        }
        if (current.isNotEmpty()) runs += current
        return runs
    }

    /**
     * Min/max over the valid samples of every series sharing one axis, or null when
     * none of them has a valid sample. Overlaid channels (BOOST + TARGET) use this so
     * their lines are directly comparable.
     */
    fun sharedRange(series: List<Pair<List<Float>, List<Boolean>>>): Pair<Float, Float>? {
        var min = Float.POSITIVE_INFINITY
        var max = Float.NEGATIVE_INFINITY
        for ((values, valid) in series) {
            for (i in values.indices) {
                if (!valid[i]) continue
                val v = values[i]
                if (v < min) min = v
                if (v > max) max = v
            }
        }
        return if (min <= max) min to max else null
    }
}
