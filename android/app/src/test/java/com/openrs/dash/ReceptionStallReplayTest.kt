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
 * #356 — a reception stall on an open connection is named in the session log, once, with its length. SYNTHETIC traffic
 * through the production path ([ReplayRun]: SlcanConnection's frame loop on virtual time). The shapes copy the one capture
 * in #356 at a lower rate (100 frames/s here, about 2,000 there): a clean rate, then a trickle of single frames about
 * 1.5 s apart — so a no-frame timer would not fire (the longest silence there was 3.0 s) — then a catch-up burst and the
 * normal rate again. Also: the 0.55–0.6 s delays with bursts (about 90 % of the frames due) are not a stall, a silence
 * shorter than the stall length is not one, and a connection that ends inside a quiet span says it never came back.
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

    private fun stallEvents() = DiagnosticLogger.sessionEvents.filter { it.type == "RECEPTION_STALL" }

    private fun TestScope.replay(rows: List<ReplayRow>, runMs: Long): ReplayRun =
        ReplayRun(this, rows.sortedBy { it.atMs }).also { advanceTimeBy(runMs) }

    @Test fun `a 10 s trickle between a clean rate and its burst is one stall event with its length`() = runTest {
        val trickle = (0 until 7).map { ReplayRow(30_000L + 300 + it * 1_500L, "RX", "t09080000000000000000") }
        replay(clean(0, 30_000) + trickle + frames(40_000, 40_050, 200) + clean(40_050, 50_000), 55_000)

        val events = stallEvents()
        assertEquals(events.joinToString { it.message }, 1, events.size)
        val msg = events.single().message
        assertTrue(msg, msg.contains("10 s"))
        assertTrue(msg, msg.contains("the rate came back"))
        assertTrue(msg, msg.contains("7 frames"))
    }

    @Test fun `control - delays of 0,6 s with catch-up bursts at 90 percent of the rate are not a stall`() = runTest {
        val bursts = (0 until 50).flatMap { k -> frames(30_000L + k * 600, 30_000L + k * 600 + 5, 54) }
        replay(clean(0, 30_000) + bursts + clean(60_000, 70_000), 75_000)
        assertEquals(stallEvents().joinToString { it.message }, 0, stallEvents().size)
    }

    @Test fun `control - a 3 s silence is shorter than a stall`() = runTest {
        replay(clean(0, 30_000) + clean(33_000, 50_000), 55_000)
        assertEquals(stallEvents().joinToString { it.message }, 0, stallEvents().size)
    }

    @Test fun `a connection that ends inside a quiet span says the rate never came back`() = runTest {
        val run = replay(clean(0, 30_000), 38_000)
        run.transport.close()
        advanceTimeBy(1_000)

        val events = stallEvents()
        assertEquals(events.joinToString { it.message }, 1, events.size)
        assertTrue(events.single().message, events.single().message.contains("the connection ended before the rate came back"))
    }

    @Test fun `control - no stall is named before the connection has a rate of its own`() = runTest {
        replay(clean(0, 4_000) + clean(14_000, 30_000), 35_000)
        assertEquals(stallEvents().joinToString { it.message }, 0, stallEvents().size)
    }
}
