package com.openrs.dash.ui.trip

/**
 * Pure geometry helpers for [DriveTimeSeries] (rc.5, #188) — kept free of Compose so
 * gap handling and shared-axis ranges are unit-testable.
 */
internal object TimeSeriesMath {

    /** The shortest spacing that can count as a gap: the floor of [gapThresholdMs], and [segments]' default bridge. */
    const val BREAK_GAP_MS = 5_000L

    /** A spacing longer than this many typical spacings is a recording gap (#285), never less than [BREAK_GAP_MS]. */
    const val GAP_CADENCE_FACTOR = 4

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
     * Elapsed ms of each sample from the first, never running backwards: a repeated or out-of-order timestamp takes the
     * time already reached, so every position derived from it stays in order.
     */
    fun elapsedMs(timestamps: List<Long>): List<Long> {
        if (timestamps.isEmpty()) return emptyList()
        val t0 = timestamps[0]
        var last = 0L
        return timestamps.map { ts -> last = maxOf(last, ts - t0); last }
    }

    /**
     * The spacing above which two consecutive samples have a gap between them: [GAP_CADENCE_FACTOR] × the typical
     * (lower median) positive spacing, and at least [BREAK_GAP_MS]. Relative to the drive's own cadence, so GPS that is
     * sparse throughout is not a row of gaps. One spacing (two samples) is its own median: no gap.
     */
    fun gapThresholdMs(elapsed: List<Long>): Long {
        val spacings = (1 until elapsed.size).map { elapsed[it] - elapsed[it - 1] }.filter { it > 0 }.sorted()
        if (spacings.isEmpty()) return BREAK_GAP_MS
        return maxOf(BREAK_GAP_MS, GAP_CADENCE_FACTOR * spacings[(spacings.size - 1) / 2])
    }

    /** Every pair of consecutive samples further apart than [thresholdMs]. */
    fun gaps(elapsed: List<Long>, thresholdMs: Long = gapThresholdMs(elapsed)): List<Gap> = buildList {
        for (i in 1 until elapsed.size) {
            if (elapsed[i] - elapsed[i - 1] > thresholdMs) add(Gap(i - 1, elapsed[i - 1], elapsed[i]))
        }
    }

    /**
     * Where the drawn samples of a drive sit on the chart's time axis.
     *
     * [buckets] / drawn sample j = the first sample of bucket j. [xFrac] is its position as a fraction of the width,
     * [totalMs] the span the axis and its ticks cover, [gaps] the recording gaps of the whole drive (full resolution,
     * not only the drawn samples) and [breakBefore] the drawn samples a line must not be joined to from the left.
     * [breakGapMs] is the longest stretch of drawn samples a line bridges over invalid ones: the gap rule applied to the
     * drawn samples' own spacing.
     * Without a time base ([hasTimeBase] false: every timestamp equal) samples are spread evenly and there are no ticks.
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

    /**
     * The time axis of a drive reduced to at most [maxPoints] drawn samples: each sample at elapsed ÷ total of the
     * width, so a tick sits under the sample recorded at that time whatever the spacing (#285).
     */
    fun timeAxis(timestamps: List<Long>, maxPoints: Int): TimeAxis {
        val elapsed = elapsedMs(timestamps)
        val buckets = buckets(timestamps.size, maxPoints)
        val drawn = buckets.map { elapsed[it.first] }
        val total = elapsed.lastOrNull() ?: 0L
        val gaps = if (total > 0L) gaps(elapsed) else emptyList()
        val xFrac = if (total > 0L) {
            drawn.map { (it.toDouble() / total).toFloat().coerceIn(0f, 1f) }
        } else {
            val lastIdx = (drawn.size - 1).coerceAtLeast(1)
            List(drawn.size) { it.toFloat() / lastIdx }
        }
        val breakBefore = BooleanArray(drawn.size)
        for (g in gaps) {
            // The first drawn sample recorded after the gap; a gap inside the last bucket has none
            val j = buckets.indexOfFirst { it.first > g.afterIndex }
            if (j > 0) breakBefore[j] = true
        }
        // Bridge between drawn samples relative to their own spacing: a fixed 5 s broke every pair once a 1 Hz drive was
        // longer than 36 min (360 drawn ≥ 6 s apart) or the GPS was sparse throughout — dots, no line. 5 s at 1 Hz up to
        // 360 points, as before; a genuine gap still breaks through breakBefore.
        return TimeAxis(buckets, drawn, total, xFrac, gapThresholdMs(drawn), breakBefore.toList(), gaps)
    }

    /**
     * Time-axis ticks ("m:ss" label, fraction of the width) at the smallest round step that keeps labels [minGapPx] apart.
     * No time base, no ticks.
     */
    fun timeTicks(totalMs: Long, widthPx: Float, minGapPx: Float): List<Pair<String, Float>> {
        if (totalMs <= 0L) return emptyList()
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
