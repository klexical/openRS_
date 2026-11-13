package com.openrs.dash

import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #356 — low reception on an open connection is named in the session log, once per span, through the production path
 * ([ReplayRun]: SlcanConnection's frame loop on virtual time). SYNTHETIC traffic at 100 frames/s, shaped like the issue's
 * capture: a clean rate, then frames in ones and twos about 1.5 s apart, then a catch-up burst and the normal rate. A span
 * that ends with a burst is a RECEPTION_STALL; one that ends otherwise is a RECEPTION_LOW; a connection that ends after
 * total silence (a key-off over Wi-Fi ends that way) writes neither. Also: delays with catch-up bursts at about 90 % of
 * the rate, a short silence and a connection with no rate yet name nothing, and a wall-clock jump neither makes nor hides
 * a span (spans are measured on the monotonic clock).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ReceptionStallReplayTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private val ids = listOf("090", "130", "0F8", "160", "180")

    /** [count] frames spread evenly over [fromMs, toMs), cycling through five broadcast IDs. */
    private fun frames(fromMs: Long, toMs: Long, count: Int): List<ReplayRow> = (0 until count).map { i ->
        ReplayRow(fromMs + (toMs - fromMs) * i / count, "RX", "t${ids[i % ids.size]}80000000000000000")
    }
    /** The clean rate: 100 frames/s. */
    private fun clean(fromMs: Long, toMs: Long) = frames(fromMs, toMs, ((toMs - fromMs) / 10).toInt())
    /** One frame every 1.5 s from [fromMs] while before [toMs]. */
    private fun trickle(fromMs: Long, toMs: Long) =
        generateSequence(fromMs) { it + 1_500 }.takeWhile { it < toMs }.map { ReplayRow(it, "RX", "t09080000000000000000") }.toList()

    private fun events(type: String) = DiagnosticLogger.sessionEvents.filter { it.type == type }
    private fun reception() = DiagnosticLogger.sessionEvents.filter { it.type.startsWith("RECEPTION_") }
    private fun shown() = reception().joinToString { "${it.type}: ${it.message}" }

    private fun TestScope.replay(rows: List<ReplayRow>, runMs: Long, wallClock: (Long) -> Long = { it }): ReplayRun =
        ReplayRun(this, rows.sortedBy { it.atMs }, wallClock = wallClock).also { advanceTimeBy(runMs) }

    @Test fun `a 10 s trickle ended by a catch-up burst is one stall with its length`() = runTest {
        replay(clean(0, 30_000) + trickle(30_300, 40_000) + frames(40_000, 40_050, 200) + clean(40_050, 50_000), 55_000)
        assertEquals(shown(), 1, reception().size)
        val msg = events("RECEPTION_STALL").single().message
        assertTrue(msg, msg.startsWith("10.00 s below 25 % of this connection's rate (100 frames/s"))
        assertTrue(msg, msg.contains("; 7 frames in it; then "))
        assertTrue(msg, msg.endsWith("frames in one quarter second (a catch-up burst)"))
    }

    @Test fun `a stall that starts inside a second is measured to the quarter second`() = runTest {
        replay(clean(0, 30_400) + trickle(30_800, 41_000) + frames(41_000, 41_050, 200) + clean(41_050, 50_000), 55_000)
        val msg = events("RECEPTION_STALL").single().message
        assertTrue(msg, msg.startsWith("10.50 s below"))
    }

    @Test fun `a connection that ends after total silence names nothing (a key-off over Wi-Fi)`() = runTest {
        val run = replay(clean(0, 30_000), 52_000)
        run.transport.close()
        advanceTimeBy(1_000)
        assertEquals(shown(), 0, reception().size)
    }

    @Test fun `a low span that was not silent and ends with the connection is a low span, not a stall`() = runTest {
        val run = replay(clean(0, 30_000) + trickle(30_300, 46_000), 46_000)
        run.transport.close()
        advanceTimeBy(1_000)
        assertEquals(shown(), 1, reception().size)
        val msg = events("RECEPTION_LOW").single().message
        assertTrue(msg, msg.endsWith("the connection then ended. A quiet bus and lost reception are not told apart."))
    }

    @Test fun `key-off then key-on on one connection is a low span, not a stall`() = runTest {
        replay(clean(0, 30_000) + clean(60_000, 75_000), 80_000)
        assertEquals(shown(), 1, reception().size)
        val msg = events("RECEPTION_LOW").single().message
        assertTrue(msg, msg.startsWith("30.00 s below"))
        assertTrue(msg, msg.contains("the rate came back without a catch-up burst."))
    }

    @Test fun `control - delays of 0,6 s with catch-up bursts at 90 percent of the rate name nothing`() = runTest {
        val bursts = (0 until 50).flatMap { k -> frames(30_000L + k * 600, 30_000L + k * 600 + 5, 54) }
        replay(clean(0, 30_000) + bursts + clean(60_000, 70_000), 75_000)
        assertEquals(shown(), 0, reception().size)
    }

    @Test fun `control - a 3 s silence is shorter than a span`() = runTest {
        replay(clean(0, 30_000) + clean(33_000, 50_000), 55_000)
        assertEquals(shown(), 0, reception().size)
    }

    @Test fun `control - nothing is named before the connection has a rate of its own`() = runTest {
        replay(clean(0, 4_000) + clean(14_000, 30_000), 35_000)
        assertEquals(shown(), 0, reception().size)
    }

    // ── The wall clock may jump (a time correction); spans are measured on the monotonic clock (#356 review C3) ─────────

    @Test fun `a wall clock jumping 8 s forward on a steady rate names nothing`() = runTest {
        replay(clean(0, 50_000), 55_000, wallClock = { t -> if (t >= 30_000) t + 8_000 else t })
        assertEquals(shown(), 0, reception().size)
    }

    @Test fun `a wall clock jumping 8 s back does not hide a stall`() = runTest {
        replay(clean(0, 31_000) + trickle(31_300, 41_000) + frames(41_000, 41_050, 200) + clean(41_050, 55_000), 60_000,
            wallClock = { t -> if (t >= 31_000) t - 8_000 else t })
        assertEquals(shown(), 1, events("RECEPTION_STALL").size)
    }
}
