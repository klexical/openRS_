package com.openrs.dash.can

import java.util.Locale

/**
 * Gaps on an open connection (#356) and spans at a lasting lower rate (#513). Frames are counted per quarter second of a
 * clock that keeps counting while the phone sleeps, and compared with this connection's own rate: the frames of its last
 * [RATE_BUCKETS] quarter seconds over their time. Short dips count in that rate (delivery in delays and bursts averages
 * out), a gap and the half second after it do not. A quarter second below [LOW_SHARE] of the rate is low; [STALL_MS] of
 * low quarter seconds in a row are a gap. Each gap is reported once, from its start, with its length, the rate before it
 * and the frames received in it:
 *
 * - [TYPE_STALL], a reception stall, when the half second after it holds at least [BURST_FACTOR] times the frames the
 *   rate gives in half a second: frames held up on the way and released at once. In #356's capture that half second held
 *   about 3 times them.
 * - [TYPE_QUIET], a quiet span, when it ends otherwise: the rate came back without a burst, or the connection ended after a
 *   gap that had frames. The name claims no cause, and the text adds [NOT_TOLD_APART].
 * - Nothing when the connection ends after a gap with no frame after its first quarter second (an ordinary key-off over
 *   Wi-Fi ends that way: the first quarter second holds the last of the traffic).
 *
 * A gap still low after [MAX_LOW_MS] becomes a long span (#513): the rate is measured afresh from the traffic in it, so a
 * gap inside the span is still named against the rate it runs at, and the span itself is reported once, from its start
 * and with its full length, as a [TYPE_QUIET], when [STALL_MS] of quarter seconds at or above [LOW_SHARE] of the rate
 * before it come back ([RATE_CAME_BACK]) or when the connection ends ([CONNECTION_ENDED]).
 *
 * A connection can also start low (#513): it has no earlier rate to fall from. When [STALL_MS] of quarter seconds in a
 * row each hold at least [RISE_FACTOR] times the rate, the rate is measured afresh from them, and the time before them,
 * from the connection's first counted quarter second (or the last such rise), is reported as a [TYPE_QUIET] if it ran
 * below [LOW_SHARE] of their rate ([RATE_ROSE]). A connection that starts low and stays low is not named: nothing gives
 * it a rate to be low against.
 *
 * Nothing is reported before the connection has a rate of its own, or while that rate is below [MIN_RATE_FPS]; until it
 * has one, only quarter seconds at or above that rate count. Pure: the caller passes the clock in ms; one instance per
 * connection. Per frame: one compare and one increment (the clock read is the caller's). Every threshold is provisional
 * until a measured drive sets it (#356, #513).
 */
class ReceptionStall {
    data class Report(val type: String, val startMs: Long, val message: String)

    companion object {
        const val TYPE_STALL = "RECEPTION_STALL"
        const val TYPE_QUIET = "QUIET_SPAN"

        /** The counting interval. Lengths are reported to this precision. */
        const val BUCKET_MS = 250L

        /**
         * A quarter second is low below this share of the rate. #356's capture: about 2 % of the rate during the stall,
         * about 90 % during the delays before it, which are not a stall.
         */
        const val LOW_SHARE = 0.25

        /** Low time in a row that makes a gap: #356's acceptance test names every gap over 5 s. */
        const val STALL_MS = 5_000L

        /** The rate is the frames of this many quarter seconds over their time (10 s). */
        const val RATE_BUCKETS = 40

        /** Below this rate (frames/s) nothing is reported. Chosen, not measured: #356's capture ran at about 2,000 frames/s. */
        const val MIN_RATE_FPS = 50

        /**
         * The half second after a gap marks a catch-up burst when it holds this many times the frames the rate gives in half
         * a second, or more. In #356's capture it held about 3 times them; a bus waking at its normal rate holds about 1.
         */
        const val BURST_FACTOR = 2.0

        /** A gap still low after this long becomes a long span: the rate is measured afresh (a lasting drop, or a parked car). */
        const val MAX_LOW_MS = 300_000L

        /**
         * A quarter second holding this many times the rate, or more, is a rise; [STALL_MS] of them in a row measure the
         * rate afresh (#513). Chosen, not measured: #513's low start ran at about a fifth of the rate after it.
         */
        const val RISE_FACTOR = 2.0

        const val CAME_BACK_WITH_BURST = "then %d frames in the half second after it (a catch-up burst)"
        const val CAME_BACK = "the rate came back without a catch-up burst"
        const val CONNECTION_ENDED = "the connection then ended"
        const val RATE_CAME_BACK = "the rate came back"
        const val RATE_ROSE = "the rate then rose to the rate named"
        const val NOT_TOLD_APART = "A quiet bus and lost reception are not told apart."

        private const val BUCKETS_PER_S = 1_000L / BUCKET_MS
        private val RATE_BEFORE = "the frames of its last $RATE_BUCKETS quarter seconds outside gaps over their time"
        private val RATE_AFTER = "the frames of the ${STALL_MS / 1_000} s after it over their time"
    }

    /** A gap's facts, fixed when it ends. */
    private class Gap(val startMs: Long, val buckets: Int, val frames: Long, val rate: Double)

    private var started = false
    private var bucketStart = 0L      // start of the quarter second being counted
    private var count = 0
    private val window = ArrayDeque<Int>()     // the quarter seconds the rate is measured over
    private var windowFrames = 0L
    private val run = mutableListOf<Int>()     // the low quarter seconds in a row, not yet in the window
    private var runRate = 0.0         // frames per quarter second when the run began
    private var runStart = 0L
    private var runHasLaterFrame = false        // a frame after the run's first quarter second
    private var ended: Gap? = null    // a gap that ended, waiting for its second quarter second after it
    private var endedFrames = 0

    // The span being measured (#513): from the first counted quarter second, the last rise, or a gap past the bound
    private var spanOpen = false
    private var spanStart = 0L
    private var spanBuckets = 0
    private var spanFrames = 0L
    private var spanRate = -1.0       // > 0: a gap past the bound, at this rate (frames per quarter second) before it
    private val rise = mutableListOf<Int>()    // the rising quarter seconds in a row, not yet in the window
    private var riseStart = 0L

    /** A frame was received at [nowMs]. Returns the gap that was just decided, if any. */
    fun onFrame(nowMs: Long): List<Report> {
        if (!started) {
            started = true; bucketStart = nowMs - Math.floorMod(nowMs, BUCKET_MS); count = 1
            return emptyList()
        }
        val decided = if (nowMs >= bucketStart + BUCKET_MS) closeBucketsBefore(nowMs) else emptyList()
        count++
        return decided
    }

    /** The connection ended at [nowMs]. Returns every gap not yet reported, including one under way. */
    fun onEnd(nowMs: Long): List<Report> {
        if (!started) return emptyList()
        val out = closeBucketsBefore(nowMs).toMutableList()       // the quarter second under way is not judged …
        ended?.let { out += decide(it, endedFrames + count); ended = null }   // … except as part of a burst
        if (run.size * BUCKET_MS >= STALL_MS && (runHasLaterFrame || count > 0)) {
            out += report(TYPE_QUIET, Gap(runStart, run.size, run.sum() + count.toLong(), runRate), CONNECTION_ENDED)
        }
        if (spanOpen && spanRate > 0) {                // a span past the bound that never came back
            val n = spanBuckets - rise.size
            out += report(TYPE_QUIET, Gap(spanStart, n, spanFrames - rise.sum() + count, spanRate), CONNECTION_ENDED, inSpan = true)
        }
        run.clear(); rise.clear()
        spanOpen = false; spanRate = -1.0
        started = false
        return out
    }

    private fun closeBucketsBefore(nowMs: Long): List<Report> {
        val out = mutableListOf<Report>()
        while (nowMs >= bucketStart + BUCKET_MS) {
            closeBucket(count)?.let { out += it }
            bucketStart += BUCKET_MS
            count = 0
        }
        return out
    }

    private fun closeBucket(n: Int): Report? {
        if (spanOpen) { spanBuckets++; spanFrames += n }
        else if (n * BUCKETS_PER_S >= MIN_RATE_FPS) { spanOpen = true; spanStart = bucketStart; spanBuckets = 1; spanFrames = n.toLong() }
        ended?.let { gap -> ended = null; return decide(gap, endedFrames + n) }   // the second quarter second after a gap
        val rate = if (window.size >= RATE_BUCKETS) windowFrames.toDouble() / window.size else -1.0
        if (rate * BUCKETS_PER_S >= MIN_RATE_FPS && n < LOW_SHARE * rate) {
            flushRise()
            if (run.isEmpty()) { runRate = rate; runStart = bucketStart; runHasLaterFrame = false }
            else if (n > 0) runHasLaterFrame = true
            run += n
            if (run.size * BUCKET_MS >= MAX_LOW_MS) {
                if (spanRate <= 0) {                   // the span is now this gap, at the rate before it
                    spanStart = runStart; spanBuckets = run.size; spanFrames = run.sum().toLong(); spanRate = runRate
                }                                      // (a gap past the bound inside such a span stays part of it)
                run.clear()
                window.clear(); windowFrames = 0       // measured afresh from the next traffic at the minimum rate
            }
            return null
        }
        if (run.size * BUCKET_MS >= STALL_MS) {        // a gap ended: judge the burst over this and the next quarter second
            ended = Gap(runStart, run.size, run.sum().toLong(), runRate); endedFrames = n
            run.clear()
            return null
        }
        run.forEach(::addToWindow)                      // a short dip is part of how frames arrive: it counts in the rate
        run.clear()
        val rising = (rate * BUCKETS_PER_S >= MIN_RATE_FPS && n >= RISE_FACTOR * rate) ||
            (spanRate > 0 && n >= LOW_SHARE * spanRate)
        if (rising) {
            if (rise.isEmpty()) riseStart = bucketStart
            rise += n
            return if (rise.size * BUCKET_MS >= STALL_MS) rose() else null
        }
        flushRise()
        addToWindow(n)
        return null
    }

    /** [STALL_MS] of rising quarter seconds: the span before them may have been low; the rate is measured from them. */
    private fun rose(): Report? {
        val riseRate = rise.sum().toDouble() / rise.size
        val before = spanBuckets - rise.size
        val beforeFrames = spanFrames - rise.sum()
        val r: Report?
        if (spanRate > 0) {
            if (riseRate < LOW_SHARE * spanRate) {     // up, but still low against the rate before the span: it goes on
                restartWindow(); return null
            }
            r = report(TYPE_QUIET, Gap(spanStart, before, beforeFrames, spanRate), RATE_CAME_BACK, inSpan = true)
        } else {
            r = if (before * BUCKET_MS >= STALL_MS && riseRate * BUCKETS_PER_S >= MIN_RATE_FPS &&
                beforeFrames < LOW_SHARE * riseRate * before
            ) report(TYPE_QUIET, Gap(spanStart, before, beforeFrames, riseRate), RATE_ROSE, inSpan = true, after = true)
            else null
        }
        spanStart = riseStart; spanBuckets = rise.size; spanFrames = rise.sum().toLong(); spanRate = -1.0
        restartWindow()
        return r
    }

    /** The rate is measured afresh from the rising quarter seconds. */
    private fun restartWindow() {
        window.clear(); windowFrames = 0
        rise.forEach(::addToWindow)
        rise.clear()
    }

    /** A rise shorter than [STALL_MS] is part of how frames arrive: it counts in the rate. */
    private fun flushRise() {
        rise.forEach(::addToWindow)
        rise.clear()
    }

    private fun addToWindow(n: Int) {
        if (window.size < RATE_BUCKETS && n * BUCKETS_PER_S < MIN_RATE_FPS) return   // a quiet start sets no rate
        window.addLast(n); windowFrames += n
        while (window.size > RATE_BUCKETS) windowFrames -= window.removeFirst()
    }

    private fun decide(gap: Gap, after: Int): Report =
        if (after >= BURST_FACTOR * gap.rate * 2) report(TYPE_STALL, gap, CAME_BACK_WITH_BURST.format(Locale.ROOT, after))
        else report(TYPE_QUIET, gap, CAME_BACK)

    /**
     * [inSpan]: a long span or a low start (#513), which also names its own rate; [after]: [Gap.rate] is the rate after
     * it, not before.
     */
    private fun report(type: String, gap: Gap, how: String, inSpan: Boolean = false, after: Boolean = false): Report {
        val own = if (inSpan) String.format(
            Locale.ROOT, " (%d frames/s)", if (gap.buckets == 0) 0 else Math.round(gap.frames.toDouble() / gap.buckets * BUCKETS_PER_S),
        ) else ""
        val facts = String.format(
            Locale.ROOT, "%.2f s below %d %% of this connection's rate (%d frames/s: %s); %d frame%s in it%s; %s",
            gap.buckets * BUCKET_MS / 1_000.0, (LOW_SHARE * 100).toInt(), Math.round(gap.rate * BUCKETS_PER_S),
            if (after) RATE_AFTER else RATE_BEFORE, gap.frames, if (gap.frames == 1L) "" else "s", own, how,
        )
        return Report(type, gap.startMs, if (type == TYPE_QUIET) "$facts. $NOT_TOLD_APART" else facts)
    }
}
