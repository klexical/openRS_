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
     * [breakOnInvalid] (pressure channels, #207): any invalid sample — no reading, or another source's — ends the run,
     * however short; a line never joins readings across it.
     */
    fun segments(
        timestamps: List<Long>,
        valid: List<Boolean>,
        breakGapMs: Long = BREAK_GAP_MS,
        breakOnInvalid: Boolean = false,
    ): List<List<Int>> {
        val runs = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var lastTs = Long.MIN_VALUE
        for (i in timestamps.indices) {
            if (!valid[i]) {
                if (breakOnInvalid && current.isNotEmpty()) { runs += current; current = ArrayList() }
                continue
            }
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
     * Index ranges of [n] samples reduced to at most [maxPoints] buckets (the first index of each is the drawn sample —
     * the same samples the stride sampler picked).
     */
    fun buckets(n: Int, maxPoints: Int): List<IntRange> {
        if (n <= maxPoints) return List(n) { it..it }
        val stride = n.toFloat() / maxPoints
        val starts = List(maxPoints) { i -> (i * stride).toInt().coerceAtMost(n - 1) }
        return starts.mapIndexed { i, a -> a..((starts.getOrNull(i + 1) ?: n) - 1).coerceAtLeast(a) }
    }

    /**
     * Validity of each bucket: [breakOnInvalid] channels are valid only if every sample in the bucket is (an unavailable
     * or other-source second inside a bucket must still break the line); other channels follow the drawn sample.
     */
    fun bucketValidity(buckets: List<IntRange>, valid: List<Boolean>, breakOnInvalid: Boolean): List<Boolean> =
        buckets.map { r -> if (breakOnInvalid) r.all { valid[it] } else valid[r.first] }

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
