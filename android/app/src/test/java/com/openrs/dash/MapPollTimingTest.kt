package com.openrs.dash

import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.MapF40b
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #271: while a drive records, the PCM poller asks MAP 0xF40B immediately after TIP 0x033E, so a TIP / MAP pair is one request
 * apart (before: MAP was the last fast DID, ≥ 7 slots after TIP); and the recording state is read again just before the MAP
 * request, so a STOP or PAUSE that lands mid-batch sends no further F40B (a late reply to an earlier request is fine).
 * Production poller (SlcanConnection's PCM loop on virtual time, engine running), checking the frames it writes, in order.
 * Only APIs that exist on 5bed73a (red there). Order is checked within the PCM requests (t7E0…): other modules' pollers
 * write on the same transport.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapPollTimingTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun isTip(w: String) = "22033E" in w
    private fun isMap(w: String) = "22F40B" in w

    @Test fun `while recording, every MAP request comes right after a TIP request`() = runTest {
        val run = ReplayRun(this, ReplayFixture.load("at-rest-baseline.txt"), rpm = 3_000.0, mapPoll = { true })
        advanceTimeBy(80_000L)
        // The PCM sender's own sequence (the BCM / AWD pollers share the transport, so their requests interleave)
        val w = run.transport.writes.filter { it.startsWith("t7E0") }
        val mapAt = w.indices.filter { isMap(w[it]) }
        assertTrue("MAP was requested (${mapAt.size})", mapAt.size >= 10)
        val notAfterTip = mapAt.filter { it == 0 || !isTip(w[it - 1]) }
        assertEquals("MAP requests not directly after a TIP request: ${notAfterTip.map { i -> w.getOrNull(i - 1)?.trim() }}",
            emptyList<Int>(), notAfterTip)
    }

    /** Recording until the first TIP request goes out, then [after] (STOP or PAUSE) — mid-batch, before that batch's MAP. */
    private fun midBatchFlip(after: DriveState) = runTest {
        lateinit var run: ReplayRun
        run = ReplayRun(this, ReplayFixture.load("at-rest-baseline.txt"), rpm = 3_000.0, mapPoll = {
            val state = if (run.transport.writes.any(::isTip)) after else DriveState(phase = DrivePhase.of(true, false))
            MapF40b.pollActive(state)
        })
        advanceTimeBy(60_000L)
        val w = run.transport.writes.toList()
        val firstTip = w.indexOfFirst(::isTip)
        assertTrue("the fast group ran", firstTip >= 0 && w.count(::isTip) >= 10)
        val mapAfterFlip = w.drop(firstTip + 1).count(::isMap)
        assertEquals("F40B requests after the mid-batch flip (the batch had been built while recording)", 0, mapAfterFlip)
    }

    @Test fun `a STOP mid-batch sends no further MAP request`() = midBatchFlip(DriveState(phase = DrivePhase.of(false, false)))

    @Test fun `a PAUSE mid-batch sends no further MAP request`() = midBatchFlip(DriveState(phase = DrivePhase.of(true, true)))
}
