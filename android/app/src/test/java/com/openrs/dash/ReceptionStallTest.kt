package com.openrs.dash

import com.openrs.dash.can.ReceptionStall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #356 — [ReceptionStall] on its own, clock passed in. SYNTHETIC frame times. The connection-level behaviour (the event in
 * the session log) is ReceptionStallReplayTest.
 */
class ReceptionStallTest {
    /** Feeds [perSecond] frames evenly into each second of [fromS, toS); returns the stalls reported. */
    private fun ReceptionStall.feed(fromS: Int, toS: Int, perSecond: Int): List<String> =
        (fromS until toS).flatMap { s -> (0 until perSecond).flatMap { i -> onFrame(s * 1_000L + i * 1_000L / perSecond) } }

    @Test fun `a stall is reported once, when the rate comes back, with its length and frames`() {
        val d = ReceptionStall()
        assertEquals(emptyList<String>(), d.feed(0, 20, 100))
        assertEquals(emptyList<String>(), d.feed(20, 26, 10))   // 10 % of the rate for 6 s
        val ended = d.feed(26, 28, 100)                          // reported at the first frame after the second it came back in
        assertEquals(1, ended.size)
        assertTrue(ended.single(), ended.single().startsWith("about 6 s below 25 % of this connection's rate (100 frames/s"))
        assertTrue(ended.single(), ended.single().contains("60 frames in it; ${ReceptionStall.CAME_BACK}"))
        assertEquals(emptyList<String>(), d.feed(28, 40, 100))
    }

    @Test fun `a low span one second shorter than a stall is not reported`() {
        val d = ReceptionStall()
        d.feed(0, 20, 100)
        // no frame for STALL_SECONDS − 1 seconds
        assertEquals(emptyList<String>(), d.feed(20 + ReceptionStall.STALL_SECONDS - 1, 30, 100))
        assertEquals(emptyList<String>(), d.onEnd(30_000))
    }

    @Test fun `exactly the stall length of low seconds is a stall`() {
        val d = ReceptionStall()
        d.feed(0, 20, 100)
        // no frame for STALL_SECONDS seconds, then the rate again
        val ended = d.feed(20 + ReceptionStall.STALL_SECONDS, 30, 100)
        assertEquals(1, ended.size)
        assertTrue(ended.single(), ended.single().startsWith("about ${ReceptionStall.STALL_SECONDS} s below"))
    }

    @Test fun `a second just at the share is not low`() {
        val d = ReceptionStall()
        d.feed(0, 20, 100)
        d.feed(20, 30, 25)                                       // exactly 25 % of 100
        assertEquals(emptyList<String>(), d.feed(30, 32, 100))   // past the second a stall would be reported in
        assertEquals(emptyList<String>(), d.onEnd(32_000))
    }

    @Test fun `seconds with no frame at all count as low ones`() {
        val d = ReceptionStall()
        d.feed(0, 20, 100)
        assertEquals(emptyList<String>(), d.onFrame(28_500))     // after 8 silent seconds: still low, nothing ends yet
        val ended = d.feed(29, 31, 100)
        assertEquals(1, ended.size)
        assertTrue(ended.single(), ended.single().startsWith("about 9 s below"))
        assertTrue(ended.single(), ended.single().contains("; 1 frame in it;"))
    }

    @Test fun `two stalls are two reports, and a burst does not raise the rate`() {
        val d = ReceptionStall()
        d.feed(0, 20, 100)
        d.feed(20, 26, 0)
        d.feed(26, 27, 2_000)                                    // a catch-up burst ends the first…
        assertEquals(1, d.feed(27, 40, 100).size)                // …reported at the next second's first frame
        d.feed(40, 47, 0)
        val second = d.feed(47, 49, 100)
        assertEquals(1, second.size)
        assertTrue(second.single(), second.single().startsWith("about 7 s below 25 % of this connection's rate (100 frames/s"))
    }

    @Test fun `the end of the connection reports a stall still under way`() {
        val d = ReceptionStall()
        d.feed(0, 20, 100)
        d.feed(20, 21, 3)
        val ended = d.onEnd(30_000)
        assertEquals(1, ended.size)
        assertTrue(ended.single(), ended.single().endsWith(ReceptionStall.CONNECTION_ENDED))
    }

    @Test fun `a bus slower than the minimum rate never reports a stall`() {
        val d = ReceptionStall()
        d.feed(0, 20, ReceptionStall.MIN_RATE_FPS - 1)
        d.feed(20, 40, 0)
        assertEquals(emptyList<String>(), d.feed(40, 41, ReceptionStall.MIN_RATE_FPS - 1))
        assertEquals(emptyList<String>(), d.onEnd(60_000))
    }

    @Test fun `a rate that has sunk below the minimum names no stall`() {
        val d = ReceptionStall()
        d.feed(0, 20, 60)                                        // a rate of its own, above the minimum
        d.feed(20, 40, 40)                                       // not low (≥ 25 % of 60); the rate sinks to 40
        d.feed(40, 50, 0)
        assertEquals(emptyList<String>(), d.feed(50, 52, 40))
        assertEquals(emptyList<String>(), d.onEnd(52_000))
    }

    @Test fun `no stall is reported before the connection has a rate of its own`() {
        val d = ReceptionStall()
        d.feed(0, ReceptionStall.RATE_SECONDS - 1, 100)
        d.feed(ReceptionStall.RATE_SECONDS - 1, 30, 0)
        assertEquals(emptyList<String>(), d.onEnd(30_000))
    }
}
