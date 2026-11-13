package com.openrs.dash.can

import java.util.Locale

/**
 * Gaps on an open connection (#356). Frames are counted per quarter second of a monotonic clock and compared with this
 * connection's own rate: the median of its last [RATE_BUCKETS] quarter seconds that were not low, of any age. A quarter
 * second below [LOW_SHARE] of that rate is low; [STALL_MS] of low quarter seconds in a row are a gap. Each gap is
 * reported once, from its start, with its length, the rate before it and the frames received in it:
 *
 * - [TYPE_STALL], a reception stall, when a catch-up burst ends it: the quarter second that ends the span holds at least [BURST_FACTOR] times
 *   the rate. Frames held up on the way and released at once are what lost reception leaves behind. In #356's capture
 *   that quarter second held about 5 times the rate.
 * - [TYPE_QUIET], a quiet span, when it ends otherwise: the rate came back without a burst, the connection ended (a gap
 *   that was not silent), or it was still low after [MAX_LOW_MS], when the rate is measured afresh. The name claims no
 *   cause, and the text adds [NOT_TOLD_APART].
 * - Nothing when the connection ends after at least [STALL_MS] of total silence (an ordinary key-off over Wi-Fi ends that
 *   way), or when a gap that is silent reaches [MAX_LOW_MS]: the rate is then measured afresh without a report.
 *
 * Nothing is reported before the connection has a rate of its own, or while that rate is below [MIN_RATE_FPS]; until it
 * has one, only quarter seconds at or above that rate count. Pure: the caller passes a monotonic clock in ms; one
 * instance per connection. Per frame: one compare and one increment (the clock read is the caller's). Every threshold is
 * provisional until a measured drive sets it (#356).
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

        /** The rate is the median of this many quarter seconds that were not low (10 s of them). */
        const val RATE_BUCKETS = 40

        /** Below this rate (frames/s) nothing is reported. Chosen, not measured: #356's capture ran at about 2,000 frames/s. */
        const val MIN_RATE_FPS = 50

        /**
         * The quarter second that ends a gap marks a catch-up burst at this many times the rate or more. In #356's
         * capture it held about 5 times the rate; a bus waking at its normal rate holds about 1 time.
         */
        const val BURST_FACTOR = 2.0

        /** A gap still low after this long is closed and the rate measured afresh (a lasting drop, or a parked car). */
        const val MAX_LOW_MS = 300_000L

        const val CAME_BACK_WITH_BURST = "then %d frames in one quarter second (a catch-up burst)"
        const val CAME_BACK = "the rate came back without a catch-up burst"
        const val CONNECTION_ENDED = "the connection then ended"
        const val STILL_LOW = "still low after %d s; the rate is measured afresh"
        const val NOT_TOLD_APART = "A quiet bus and lost reception are not told apart."

        private const val BUCKETS_PER_S = 1_000L / BUCKET_MS
    }

    private var started = false
    private var bucketStart = 0L      // monotonic start of the quarter second being counted
    private var count = 0
    private var lastFrameMs = 0L
    private val rateBuckets = ArrayDeque<Int>()
    private var lowBuckets = 0
    private var lowFrames = 0
    private var lowRate = 0           // frames per quarter second before the span
    private var lowStart = 0L
    private var lowHasFrame = false   // a frame arrived inside the gap: it is not silent

    /** A frame was received at [nowMs] (monotonic). Returns the gap that just ended, if any. */
    fun onFrame(nowMs: Long): List<Report> {
        if (!started) {
            started = true; bucketStart = nowMs - Math.floorMod(nowMs, BUCKET_MS); count = 1; lastFrameMs = nowMs
            return emptyList()
        }
        val ended = if (nowMs >= bucketStart + BUCKET_MS) closeBucketsBefore(nowMs) else emptyList()
        count++
        lastFrameMs = nowMs
        return ended
    }

    /** The connection ended at [nowMs] (monotonic). Returns every gap not yet reported, including one under way. */
    fun onEnd(nowMs: Long): List<Report> {
        if (!started) return emptyList()
        val ended = closeBucketsBefore(nowMs).toMutableList()    // the quarter second under way is not judged
        if (lowBuckets * BUCKET_MS >= STALL_MS && nowMs - lastFrameMs < STALL_MS) ended += report(TYPE_QUIET, CONNECTION_ENDED)
        resetSpan()
        started = false
        return ended
    }

    private fun closeBucketsBefore(nowMs: Long): List<Report> {
        val ended = mutableListOf<Report>()
        while (nowMs >= bucketStart + BUCKET_MS) {
            closeBucket(count)?.let { ended += it }
            bucketStart += BUCKET_MS
            count = 0
        }
        return ended
    }

    private fun closeBucket(n: Int): Report? {
        val rate = if (rateBuckets.size >= RATE_BUCKETS) rateBuckets.sorted()[RATE_BUCKETS / 2] else -1
        if (rate * BUCKETS_PER_S >= MIN_RATE_FPS && n < LOW_SHARE * rate) {
            if (lowBuckets == 0) { lowRate = rate; lowStart = bucketStart; lowHasFrame = false }
            lowBuckets++
            lowFrames += n
            if (n > 0) lowHasFrame = true
            if (lowBuckets * BUCKET_MS >= MAX_LOW_MS) {
                val r = if (lowHasFrame) report(TYPE_QUIET, STILL_LOW.format(Locale.ROOT, MAX_LOW_MS / 1_000)) else null
                resetSpan()
                rateBuckets.clear()                          // measured afresh from the next traffic at the minimum rate
                return r
            }
            return null                                      // a low quarter second never enters the rate
        }
        val ended = if (lowBuckets * BUCKET_MS >= STALL_MS) {
            if (n >= BURST_FACTOR * lowRate) report(TYPE_STALL, CAME_BACK_WITH_BURST.format(Locale.ROOT, n))
            else report(TYPE_QUIET, CAME_BACK)
        } else null
        resetSpan()
        if (rateBuckets.size < RATE_BUCKETS && n * BUCKETS_PER_S < MIN_RATE_FPS) return ended   // a quiet start sets no rate
        rateBuckets.addLast(n)
        while (rateBuckets.size > RATE_BUCKETS) rateBuckets.removeFirst()
        return ended
    }

    private fun resetSpan() { lowBuckets = 0; lowFrames = 0; lowHasFrame = false }

    private fun report(type: String, how: String): Report {
        val facts = String.format(
            Locale.ROOT, "%.2f s below %d %% of this connection's rate (%d frames/s: the median of its last %d quarter " +
                "seconds that were not low); %d frame%s in it; %s",
            lowBuckets * BUCKET_MS / 1_000.0, (LOW_SHARE * 100).toInt(), lowRate * BUCKETS_PER_S, RATE_BUCKETS,
            lowFrames, if (lowFrames == 1) "" else "s", how,
        )
        return Report(type, lowStart, if (type == TYPE_QUIET) "$facts. $NOT_TOLD_APART" else facts)
    }
}
