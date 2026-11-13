package com.openrs.dash.can

/**
 * Reception stall on an open connection (#356): frames still arrive, but far fewer than this connection's own rate, for
 * several seconds. In #356's capture the rate fell from about 2,000 frames/s to a trickle of single frames about 1.5 s
 * apart for 10.5 s, then a catch-up burst brought it back. A no-frame timer never fires on such a trickle (the longest
 * silence there was 3.0 s), and the transport's read timeout only ends a connection that receives nothing.
 *
 * Counts the frames received in each clock second. The connection's own rate is the median of its last [RATE_SECONDS]
 * seconds that were not low (until it has that many, only seconds of at least [MIN_RATE_FPS] count). A second below
 * [LOW_SHARE] of that rate is low; [STALL_SECONDS] low seconds in a row are a stall. Each stall is reported once, with its
 * length, at the first frame after the second the rate came back in, or when the connection ends.
 * Pure: the caller passes its clock; one instance per connection. The thresholds are named so the owner's measured
 * drives can set them (#356).
 */
class ReceptionStall {
    companion object {
        /**
         * A second is low below this share of the connection's rate. #356's capture: 2 % of the rate during the stall,
         * 90 % in the delays before it, which are not a stall. To be set from the owner's Wi-Fi drive (#356).
         */
        const val LOW_SHARE = 0.25

        /** Low seconds in a row that make a stall: #356's acceptance test names every gap over 5 s. */
        const val STALL_SECONDS = 5

        /** The connection's own rate is the median of this many seconds that were not low. */
        const val RATE_SECONDS = 10

        /**
         * Below this own rate (frames/s) no stall is named: the bus is too quiet to tell a stall from its own gaps. Chosen,
         * not measured: #356's capture ran at about 2,000 frames/s with the ignition on.
         */
        const val MIN_RATE_FPS = 50

        const val CAME_BACK = "the rate came back"
        const val CONNECTION_ENDED = "the connection ended before the rate came back (a quiet bus and a stall are not told apart)"
    }

    private var started = false
    private var second = 0L          // the clock second being counted (clock ms ÷ 1,000)
    private var count = 0            // frames counted in that second
    private val rateSeconds = ArrayDeque<Int>()
    private var lowSeconds = 0
    private var lowFrames = 0
    private var lowRate = 0

    /** A frame was received at [nowMs]. Returns the stall that just ended, if any (at most one). */
    fun onFrame(nowMs: Long): List<String> {
        if (!started) { started = true; second = Math.floorDiv(nowMs, 1_000L); count = 1; return emptyList() }
        val ended = closeSecondsBefore(nowMs)
        count++
        return ended
    }

    /** The connection ended at [nowMs]. Returns every stall not yet reported, including one still under way. */
    fun onEnd(nowMs: Long): List<String> {
        if (!started) return emptyList()
        val ended = closeSecondsBefore(nowMs).toMutableList()   // the second under way is not judged
        if (lowSeconds >= STALL_SECONDS) ended += describe(CONNECTION_ENDED)
        lowSeconds = 0; lowFrames = 0
        started = false
        return ended
    }

    private fun closeSecondsBefore(nowMs: Long): List<String> {
        val idx = Math.floorDiv(nowMs, 1_000L)
        var ended: String? = null
        while (second < idx) {
            closeSecond(count)?.let { ended = it }
            second++
            count = 0
        }
        return listOfNotNull(ended)
    }

    private fun closeSecond(n: Int): String? {
        val rate = if (rateSeconds.size >= RATE_SECONDS) rateSeconds.sorted()[RATE_SECONDS / 2] else -1
        if (rate >= MIN_RATE_FPS && n < LOW_SHARE * rate) {
            if (lowSeconds == 0) lowRate = rate
            lowSeconds++
            lowFrames += n
            return null               // a low second never enters the rate
        }
        val ended = if (lowSeconds >= STALL_SECONDS) describe(CAME_BACK) else null
        lowSeconds = 0; lowFrames = 0
        if (rateSeconds.size < RATE_SECONDS && n < MIN_RATE_FPS) return ended   // a quiet start sets no rate
        rateSeconds.addLast(n)
        while (rateSeconds.size > RATE_SECONDS) rateSeconds.removeFirst()
        return ended
    }

    private fun describe(how: String) =
        "about $lowSeconds s below ${(LOW_SHARE * 100).toInt()} % of this connection's rate ($lowRate frames/s, the median " +
            "of the $RATE_SECONDS seconds before); $lowFrames frame${if (lowFrames == 1) "" else "s"} in it; $how"
}
