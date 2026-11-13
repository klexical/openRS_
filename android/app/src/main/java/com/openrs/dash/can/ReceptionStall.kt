package com.openrs.dash.can

import java.util.Locale

/**
 * Gaps on an open connection (#356). Frames are counted per quarter second of a clock that keeps counting while the phone
 * sleeps, and compared with this connection's own rate: the frames of its last [RATE_BUCKETS] quarter seconds over their
 * time. Short dips count in that rate (delivery in delays and bursts averages out), a gap and the half second after it do
 * not. A quarter second below [LOW_SHARE] of the rate is low; [STALL_MS] of low quarter seconds in a row are a gap. Each
 * gap is reported once, from its start, with its length, the rate before it and the frames received in it:
 *
 * - [TYPE_STALL], a reception stall, when the half second after it holds at least [BURST_FACTOR] times the frames the
 *   rate gives in half a second: frames held up on the way and released at once. In #356's capture that half second held
 *   about 3 times them.
 * - [TYPE_QUIET], a quiet span, when it ends otherwise: the rate came back without a burst, the connection ended after a
 *   gap that had frames, or it was still low after [MAX_LOW_MS], when the rate is measured afresh. The name claims no
 *   cause, and the text adds [NOT_TOLD_APART].
 * - Nothing when the connection ends after a gap with no frame after its first quarter second (an ordinary key-off over
 *   Wi-Fi ends that way: the first quarter second holds the last of the traffic).
 *
 * Nothing is reported before the connection has a rate of its own, or while that rate is below [MIN_RATE_FPS]; until it
 * has one, only quarter seconds at or above that rate count. Pure: the caller passes the clock in ms; one instance per
 * connection. Per frame: one compare and one increment (the clock read is the caller's). Every threshold is provisional
 * until a measured drive sets it (#356).
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

        /** A gap still low after this long is reported and the rate measured afresh (a lasting drop, or a parked car). */
        const val MAX_LOW_MS = 300_000L

        const val CAME_BACK_WITH_BURST = "then %d frames in the half second after it (a catch-up burst)"
        const val CAME_BACK = "the rate came back without a catch-up burst"
        const val CONNECTION_ENDED = "the connection then ended"
        const val STILL_LOW = "still low after %d s; the rate is measured afresh"
        const val NOT_TOLD_APART = "A quiet bus and lost reception are not told apart."

        private const val BUCKETS_PER_S = 1_000L / BUCKET_MS
    }

    /** A gap's facts, fixed when it ends. */
    private class Gap(val startMs: Long, val buckets: Int, val frames: Int, val rate: Double)

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
            out += report(TYPE_QUIET, Gap(runStart, run.size, run.sum() + count, runRate), CONNECTION_ENDED)
        }
        run.clear()
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
        ended?.let { gap -> ended = null; return decide(gap, endedFrames + n) }   // the second quarter second after a gap
        val rate = if (window.size >= RATE_BUCKETS) windowFrames.toDouble() / window.size else -1.0
        if (rate * BUCKETS_PER_S >= MIN_RATE_FPS && n < LOW_SHARE * rate) {
            if (run.isEmpty()) { runRate = rate; runStart = bucketStart; runHasLaterFrame = false }
            else if (n > 0) runHasLaterFrame = true
            run += n
            if (run.size * BUCKET_MS >= MAX_LOW_MS) {
                val r = report(TYPE_QUIET, Gap(runStart, run.size, run.sum(), runRate), STILL_LOW.format(Locale.ROOT, MAX_LOW_MS / 1_000))
                run.clear()
                window.clear(); windowFrames = 0       // measured afresh from the next traffic at the minimum rate
                return r
            }
            return null
        }
        if (run.size * BUCKET_MS >= STALL_MS) {        // a gap ended: judge the burst over this and the next quarter second
            ended = Gap(runStart, run.size, run.sum(), runRate); endedFrames = n
            run.clear()
            return null
        }
        run.forEach(::addToWindow)                      // a short dip is part of how frames arrive: it counts in the rate
        run.clear()
        addToWindow(n)
        return null
    }

    private fun addToWindow(n: Int) {
        if (window.size < RATE_BUCKETS && n * BUCKETS_PER_S < MIN_RATE_FPS) return   // a quiet start sets no rate
        window.addLast(n); windowFrames += n
        while (window.size > RATE_BUCKETS) windowFrames -= window.removeFirst()
    }

    private fun decide(gap: Gap, after: Int): Report =
        if (after >= BURST_FACTOR * gap.rate * 2) report(TYPE_STALL, gap, CAME_BACK_WITH_BURST.format(Locale.ROOT, after))
        else report(TYPE_QUIET, gap, CAME_BACK)

    private fun report(type: String, gap: Gap, how: String): Report {
        val facts = String.format(
            Locale.ROOT, "%.2f s below %d %% of this connection's rate (%d frames/s: the frames of its last %d quarter " +
                "seconds outside gaps over their time); %d frame%s in it; %s",
            gap.buckets * BUCKET_MS / 1_000.0, (LOW_SHARE * 100).toInt(), Math.round(gap.rate * BUCKETS_PER_S),
            RATE_BUCKETS, gap.frames, if (gap.frames == 1) "" else "s", how,
        )
        return Report(type, gap.startMs, if (type == TYPE_QUIET) "$facts. $NOT_TOLD_APART" else facts)
    }
}
