package com.openrs.dash.ui.trip

/**
 * TRIP replay's playhead (#285 seam). As played up to rc.8 Build 39: the playhead counts stored points, one per
 * [SAMPLE_STEP_MS] at 1×, whatever time lies between them; the labels show the shown point's elapsed time and the
 * ±10 s seeks walk the timestamps, always moving at least one point.
 */
internal class ReplayTimeline(private val timestamps: List<Long>) {

    val size: Int = timestamps.size

    val hasTimeBase: Boolean = true

    /** Length of the playhead's range. */
    val durationMs: Long = (size - 1).coerceAtLeast(0) * SAMPLE_STEP_MS

    val gaps: List<TimeSeriesMath.Gap> = emptyList()

    /** Playhead position of sample [index]. */
    fun positionOf(index: Int): Long = if (size == 0) 0L else index.coerceIn(0, size - 1) * SAMPLE_STEP_MS

    /** The sample shown at [positionMs]. */
    fun indexAt(positionMs: Long): Int = if (size == 0) 0 else (positionMs / SAMPLE_STEP_MS).toInt().coerceIn(0, size - 1)

    /** The playhead after [realDtMs] of wall time at [speed]×. */
    fun advance(positionMs: Long, realDtMs: Long, speed: Float): Long =
        (positionMs + (realDtMs * speed).toLong()).coerceIn(0L, durationMs)

    /** The sample nearest [deltaMs] from the shown one by timestamp; always at least one sample away. */
    fun seek(positionMs: Long, deltaMs: Long): Long {
        if (size == 0) return 0L
        val from = indexAt(positionMs)
        val target = timestamps[from] + deltaMs
        var i = from
        if (deltaMs > 0) { while (i < size - 1 && timestamps[i + 1] <= target) i++ }
        else { while (i > 0 && timestamps[i - 1] >= target) i-- }
        val to = when {
            i == from && deltaMs > 0 -> (from + 1).coerceAtMost(size - 1)
            i == from && deltaMs < 0 -> (from - 1).coerceAtLeast(0)
            else -> i
        }
        return positionOf(to)
    }

    fun gapAt(positionMs: Long): TimeSeriesMath.Gap? = null

    /** Left label: the shown sample's elapsed time. */
    fun positionText(positionMs: Long): String =
        if (size == 0) formatClock(0L) else formatClock(timestamps[indexAt(positionMs)] - timestamps[0])

    /** Right label: the drive's length. */
    fun durationText(): String = if (size == 0) formatClock(0L) else formatClock(timestamps.last() - timestamps.first())

    companion object {
        /** Playhead step per sample. */
        const val SAMPLE_STEP_MS = 1_000L

        /** "m:ss"; zero or less reads "0:00". */
        fun formatClock(ms: Long): String {
            if (ms <= 0L) return "0:00"
            val totalSec = ms / 1000
            return "%d:%02d".format(totalSec / 60, totalSec % 60)
        }
    }
}
