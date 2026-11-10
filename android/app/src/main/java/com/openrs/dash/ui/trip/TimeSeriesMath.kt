package com.openrs.dash.ui.trip

/**
 * Pure geometry helpers for [DriveTimeSeries] (rc.5, #188) — kept free of Compose so
 * gap handling and shared-axis ranges are unit-testable.
 */
internal object TimeSeriesMath {

    /** Bridge over invalid samples shorter than this; break the line on longer gaps. */
    const val BREAK_GAP_MS = 5_000L

    /**
     * A stretch with no stored sample (#285): between sample [afterIndex] and the next one. Times are elapsed ms from the
     * first sample. Inferred from the timestamps alone: a stored drive does not say whether it was a pause, a link loss or
     * no position fix, so nothing here names a cause.
     */
    data class Gap(val afterIndex: Int, val fromMs: Long, val toMs: Long)

    /**
     * Split a series into drawable runs of sample indices. Invalid samples (absent /
     * sentinel) are skipped; consecutive valid samples further apart than [breakGapMs]
     * start a new run so the line doesn't imply data across a long hole.
     * [breakOnInvalid] (pressure channels, #207): any invalid sample — no reading, or another source's — ends the run,
     * however short; a line never joins readings across it.
     * [breakBefore] (#285): a true entry ends the run before that sample whatever the spacing — a recording gap found in
     * the full-resolution series, which the drawn samples' own spacing cannot show once a drive is downsampled.
     */
    fun segments(
        timestamps: List<Long>,
        valid: List<Boolean>,
        breakGapMs: Long = BREAK_GAP_MS,
        breakOnInvalid: Boolean = false,
        breakBefore: List<Boolean>? = null,
    ): List<List<Int>> {
        val runs = ArrayList<List<Int>>()
        var current = ArrayList<Int>()
        var lastTs = Long.MIN_VALUE
        var pendingBreak = false
        for (i in timestamps.indices) {
            if (breakBefore != null && breakBefore[i]) pendingBreak = true
            if (!valid[i]) {
                if (breakOnInvalid && current.isNotEmpty()) { runs += current; current = ArrayList() }
                continue
            }
            val ts = timestamps[i]
            if (current.isNotEmpty() && (pendingBreak || ts - lastTs > breakGapMs)) {
                runs += current
                current = ArrayList()
            }
            pendingBreak = false
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

    // ── Time axis (#285) ────────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Where the drawn samples of a drive sit on the chart's time axis.
     *
     * [buckets] / drawn sample j = the first sample of bucket j. [xFrac] is its position as a fraction of the width,
     * [totalMs] the span the axis and its ticks cover, [gaps] the recording gaps of the whole drive and [breakBefore] the
     * drawn samples a line must not be joined to from the left.
     */
    class TimeAxis(
        val buckets: List<IntRange>,
        val drawnElapsedMs: List<Long>,
        val totalMs: Long,
        val xFrac: List<Float>,
        val breakGapMs: Long,
        val breakBefore: List<Boolean>,
        val gaps: List<Gap>,
    ) {
        val hasTimeBase: Boolean get() = totalMs > 0L

        /** Drawable runs of drawn-sample indices for a channel whose per-sample validity (full resolution) is [validFull]. */
        fun runs(validFull: List<Boolean>, breakOnInvalid: Boolean): List<List<Int>> =
            segments(drawnElapsedMs, bucketValidity(buckets, validFull, breakOnInvalid), breakGapMs, breakOnInvalid, breakBefore)
    }

    /** The chart's axis as drawn up to rc.8 Build 39: drawn samples spread evenly by index (labels by elapsed time). */
    fun timeAxis(timestamps: List<Long>, maxPoints: Int): TimeAxis {
        val buckets = buckets(timestamps.size, maxPoints)
        val drawn = buckets.map { timestamps[it.first] }
        val total = ((drawn.lastOrNull() ?: 0L) - (drawn.firstOrNull() ?: 0L)).coerceAtLeast(1L)
        val lastIdx = (drawn.size - 1).coerceAtLeast(1)
        val xFrac = List(drawn.size) { it.toFloat() / lastIdx }
        return TimeAxis(buckets, drawn, total, xFrac, BREAK_GAP_MS, List(drawn.size) { false }, emptyList())
    }

    /**
     * Time ticks as (label, fraction of the drive): the smallest step (15 s … 1 h) that keeps ticks
     * [minGapPx] apart across [widthPx] — every 60 s overlapped on short charts.
     */
    fun timeTicks(totalMs: Long, widthPx: Float, minGapPx: Float): List<Pair<String, Float>> {
        val step = listOf(15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L, 3_600_000L)
            .firstOrNull { it.toFloat() / totalMs * widthPx >= minGapPx } ?: 3_600_000L
        return buildList {
            var t = 0L
            while (t <= totalMs) {
                add("%d:%02d".format((t / 60_000).toInt(), ((t % 60_000) / 1000).toInt()) to t.toFloat() / totalMs)
                t += step
            }
        }
    }
}
