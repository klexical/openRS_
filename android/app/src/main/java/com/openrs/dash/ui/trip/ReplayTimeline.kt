package com.openrs.dash.ui.trip

/**
 * The one time base of TRIP replay (#285): the playhead is elapsed drive time in ms, and the slider, its labels, the
 * ±10 s seeks and playback at 1× / 2× / 4× all move it. The sample on screen is the last one recorded at or before the
 * playhead, so a stretch with no samples takes as long to play as it took to drive, and the label never runs ahead of
 * the data. Pure (no Compose, no clock) so every rule is unit-tested.
 *
 * Without a time base (every timestamp equal — nothing a real recording produces) the playhead counts samples instead,
 * one per [SAMPLE_STEP_MS], and the labels say so.
 */
internal class ReplayTimeline(timestamps: List<Long>) {

    private val elapsed: List<Long> = TimeSeriesMath.elapsedMs(timestamps)

    val size: Int = elapsed.size

    /** False when the samples carry no usable time (all equal). */
    val hasTimeBase: Boolean = (elapsed.lastOrNull() ?: 0L) > 0L

    /** Length of the playhead's range. */
    val durationMs: Long =
        if (hasTimeBase) elapsed.last() else (size - 1).coerceAtLeast(0) * SAMPLE_STEP_MS

    /** Stretches with no stored sample (same rule as the chart: [TimeSeriesMath.gaps]). */
    val gaps: List<TimeSeriesMath.Gap> = if (hasTimeBase) TimeSeriesMath.gaps(elapsed) else emptyList()

    /** Playhead position of sample [index]. */
    fun positionOf(index: Int): Long {
        if (size == 0) return 0L
        val i = index.coerceIn(0, size - 1)
        return if (hasTimeBase) elapsed[i] else i * SAMPLE_STEP_MS
    }

    /** The sample shown at [positionMs]: the last one at or before it (the last of several with one timestamp). */
    fun indexAt(positionMs: Long): Int {
        if (size == 0) return 0
        if (!hasTimeBase) return (positionMs / SAMPLE_STEP_MS).toInt().coerceIn(0, size - 1)
        var lo = 0
        var hi = size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (elapsed[mid] <= positionMs) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** The playhead after [realDtMs] of wall time at [speed]×. */
    fun advance(positionMs: Long, realDtMs: Long, speed: Float): Long =
        (positionMs + (realDtMs * speed).toLong()).coerceIn(0L, durationMs)

    /** The playhead moved by [deltaMs], kept inside the drive. */
    fun seek(positionMs: Long, deltaMs: Long): Long = (positionMs + deltaMs).coerceIn(0L, durationMs)

    /** The gap the playhead is inside, or null. The sample before a gap is not in it; the one after ends it. */
    fun gapAt(positionMs: Long): TimeSeriesMath.Gap? = gaps.firstOrNull { positionMs > it.fromMs && positionMs < it.toMs }

    /** Left label: the playhead's time, or the sample number without a time base. */
    fun positionText(positionMs: Long): String =
        if (hasTimeBase) formatClock(positionMs) else "SAMPLE ${indexAt(positionMs) + 1} / $size"

    /** Right label: the drive's length; empty without a time base. */
    fun durationText(): String = if (hasTimeBase) formatClock(durationMs) else ""

    companion object {
        /** Playhead step per sample when the samples carry no time. */
        const val SAMPLE_STEP_MS = 1_000L

        /** "m:ss"; zero or less reads "0:00". */
        fun formatClock(ms: Long): String {
            if (ms <= 0L) return "0:00"
            val totalSec = ms / 1000
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }
}
