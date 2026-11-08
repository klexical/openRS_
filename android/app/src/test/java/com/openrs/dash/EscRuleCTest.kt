package com.openrs.dash

import com.openrs.dash.can.BusyException
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.EscCommand
import com.openrs.dash.can.EscCommand.Outcome
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #125 rule C: CONFIRMED needs the last two 0x1C0 frames to report the requested mode, both after the request, read at a poll. Real decode and
 * stamps on a virtual clock. Request at [R], polls at R + 100 k, frames on a 20 ms grid, EscStampThrottleTest's conventions. SYNTHETIC.
 * A two-frame blip seen by a poll can still pass: that's the rule as decided, and it's pinned below.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EscRuleCTest {
    private class Api(val result: Result<Unit> = Result.success(Unit)) : FirmwareCommandSender {
        var calls = 0
        override suspend fun setDriveMode(mode: Int) = Result.success(Unit)
        override suspend fun setEscMode(mode: Int): Result<Unit> { calls++; return result }
    }

    private var vs = VehicleState(isConnected = true)
    private var gen = 1

    private fun frame(raw: Int, now: Long) {
        val data = byteArrayOf(0, (raw shl 4).toByte(), 0, 0, 0, 0, 0, 0)
        vs = CanDecoder.stampFresh(0x1C0, vs, CanDecoder.decode(0x1C0, data, vs)!!, now)
    }
    private fun TestScope.bus(periodMs: Long = 20L, raw: (Long) -> Int) = backgroundScope.launch {
        while (true) { frame(raw(testScheduler.currentTime), testScheduler.currentTime); delay(periodMs) }
    }
    private fun TestScope.frames(vararg rows: Pair<Long, Int>) { for ((at, raw) in rows) backgroundScope.launch { delay(at); frame(raw, at) } }
    private suspend fun TestScope.request(target: EscStatus, api: Api = Api(), at: Long = R): Outcome {
        testScheduler.advanceTimeBy(at - testScheduler.currentTime)
        return EscCommand.execute(api, target, { vs }, connectionId = { gen }, clock = { testScheduler.currentTime })
    }

    private companion object { const val R = 2_005L; const val ON = 0; const val OFF = 1; const val SPORT = 2 }

    // ── solitary: one frame is never enough ─────────────────────────────────────────────────────────────────────────

    @Test fun `solitary - one frame in the first second, seen by a poll, doesn't confirm`() = runTest {
        bus { t -> if (t == 2_400L) SPORT else ON }
        assertEquals(Outcome.NotConfirmed(EscStatus.ON, 3_300L), request(EscStatus.PARTIAL))
    }
    @Test fun `solitary - one frame after the first post-request report doesn't confirm`() = runTest {
        bus { t -> if (t == 3_400L) SPORT else ON }
        assertEquals(Outcome.NotConfirmed(EscStatus.ON, 3_300L), request(EscStatus.PARTIAL))
    }
    @Test fun `solitary - two single frames with a revert between them don't confirm`() = runTest {
        bus { t -> if (t == 2_400L || t == 3_400L) SPORT else ON }
        assertEquals(Outcome.NotConfirmed(EscStatus.ON, 3_300L), request(EscStatus.PARTIAL))
    }
    @Test fun `solitary - ON to OFF, one frame 100 ms into the 5 s press doesn't confirm`() = runTest {
        bus { t -> if (t == 2_100L) OFF else ON }
        assertEquals(Outcome.NotConfirmed(EscStatus.ON, 8_000L), request(EscStatus.OFF))
    }

    // ── sparse: the cost, stated ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `sparse - a change, then silence, reads NOT CONFIRMED with the car on the target`() = runTest {
        frames(1_000L to ON, 2_000L to ON, 2_400L to SPORT)
        assertEquals(Outcome.NotConfirmed(EscStatus.PARTIAL, 3_300L), request(EscStatus.PARTIAL))
    }
    @Test fun `sparse - a change on the window's last frame reads NOT CONFIRMED`() = runTest {
        frames(1_000L to ON, 2_000L to ON, 3_000L to ON, 4_000L to ON, 5_000L to SPORT)
        assertEquals(Outcome.NotConfirmed(EscStatus.PARTIAL, 3_300L), request(EscStatus.PARTIAL))
    }
    @Test fun `sparse - at 1 Hz the second frame confirms a second later`() = runTest {
        frames(1_000L to ON, 2_000L to ON, 3_000L to SPORT, 4_000L to SPORT)
        assertEquals(Outcome.Confirmed(2_000L), request(EscStatus.PARTIAL))
    }

    // ── repeated: one frame interval on a steady broadcast ──────────────────────────────────────────────────────────

    @Test fun `repeated - a real change confirms at the poll after its second frame`() = runTest {
        bus { t -> if (t >= 2_400L) SPORT else ON }
        assertEquals(Outcome.Confirmed(500L), request(EscStatus.PARTIAL))      // frames 2400 and 2420; polls 2405, 2505
    }
    @Test fun `repeated - the mode already held confirms on two frames after the request, not on any before it`() = runTest {
        bus { SPORT }
        assertEquals(Outcome.Confirmed(100L), request(EscStatus.PARTIAL))
    }
    @Test fun `repeated - one frame before the request and one after don't count as two`() = runTest {
        frames(1_000L to SPORT, 2_000L to SPORT, 3_000L to SPORT)
        assertEquals(Outcome.NotConfirmed(EscStatus.PARTIAL, 3_000L), request(EscStatus.PARTIAL))
    }
    @Test fun `the rule as decided - a two-frame blip seen by a poll confirms`() = runTest {
        bus { t -> if (t == 2_380L || t == 2_400L) SPORT else ON }
        assertEquals(Outcome.Confirmed(400L), request(EscStatus.PARTIAL))
    }
    @Test fun `the window is unchanged`() {
        assertEquals(3_300L, EscCommand.windowMs(EscStatus.ON, EscStatus.PARTIAL))
        assertEquals(8_000L, EscCommand.windowMs(EscStatus.ON, EscStatus.OFF))
        assertEquals(4_600L, EscCommand.windowMs(EscStatus.OFF, EscStatus.PARTIAL))
    }

    // ── disconnected ────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `disconnected - lost even with two matching frames on the new link`() = runTest {
        bus { t -> if (t >= 2_400L) SPORT else ON }
        backgroundScope.launch { delay(2_390L); gen++ }
        assertEquals(Outcome.ConnectionLost, request(EscStatus.PARTIAL))
    }
    @Test fun `disconnected - the link down reads lost`() = runTest {
        bus { ON }
        backgroundScope.launch { delay(3_010L); vs = vs.copy(isConnected = false) }
        assertEquals(Outcome.ConnectionLost, request(EscStatus.PARTIAL))
    }

    // ── commands and the lock ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `each outcome sends one command and releases the lock`() = runTest {
        val cases: List<Pair<Api, (Outcome) -> Boolean>> = listOf(
            Api() to { o -> o is Outcome.NotConfirmed },
            Api(Result.failure(BusyException())) to { o -> o == Outcome.Busy },
            Api(Result.failure(java.io.IOException("x"))) to { o -> o is Outcome.Failed },
        )
        bus { ON }
        for ((api, check) in cases) {
            val o = EscCommand.execute(api, EscStatus.PARTIAL, { vs }, connectionId = { gen }, clock = { testScheduler.currentTime })
            assertTrue("$o", check(o))
            assertEquals("commands", 1, api.calls)
        }
        assertNotEquals("the lock was released", Outcome.Busy, EscCommand.execute(Api(), EscStatus.PARTIAL, { vs }, connectionId = { gen }, clock = { testScheduler.currentTime }))
    }
    @Test fun `a confirmed request sends one command and releases the lock`() = runTest {
        bus { t -> if (t >= 2_400L) SPORT else ON }
        val api = Api()
        assertTrue(request(EscStatus.PARTIAL, api) is Outcome.Confirmed)
        assertEquals(1, api.calls)
        assertTrue(EscCommand.execute(Api(), EscStatus.PARTIAL, { vs }, connectionId = { gen }, clock = { testScheduler.currentTime }) is Outcome.Confirmed)
    }
    @Test fun `a second request during the wait is busy and sends nothing`() = runTest {
        bus { ON }
        val a = Api(); val b = Api()
        val first = launch { EscCommand.execute(a, EscStatus.PARTIAL, { vs }, connectionId = { gen }, clock = { testScheduler.currentTime }) }
        testScheduler.advanceTimeBy(500)
        assertEquals(Outcome.Busy, EscCommand.execute(b, EscStatus.PARTIAL, { vs }, connectionId = { gen }, clock = { testScheduler.currentTime }))
        assertEquals(0, b.calls)
        first.cancel()
    }

    // ── the merge trap: a reply's snapshot must not bring back what the decoder removed ──────────────────────────────

    @Test fun `an older OBD snapshot doesn't resurrect a removed ESC frame tag`() {
        frame(SPORT, 3_000L); frame(SPORT, 3_020L)                       // the reply is parsed against this state: two agreeing frames
        val snapshot = vs
        frame(ON, 3_040L)                                                // the run ends: the decoder removes the previous-frame tag
        assertEquals(null, vs.fieldLastUpdateMs[CanDecoder.ESC_FRAME_PREV_TAG])
        frame(SPORT, 3_060L)                                             // a single stray frame
        assertEquals(null, vs.fieldLastUpdateMs[CanDecoder.ESC_FRAME_PREV_TAG])
        vs = com.openrs.dash.data.ObdMerge.merge(vs, snapshot)
        assertEquals("still one frame in the run", null, vs.fieldLastUpdateMs[CanDecoder.ESC_FRAME_PREV_TAG])
        assertEquals(3_060L, vs.fieldLastUpdateMs[CanDecoder.ESC_FRAME_TAG])
    }

    @Test fun `end to end - a single stray frame with that merge doesn't confirm`() = runTest {
        var snapshot: VehicleState? = null
        backgroundScope.launch { delay(3_000L); frame(SPORT, 3_000L); frame(SPORT, 3_020L); snapshot = vs; frame(ON, 3_040L); frame(SPORT, 3_060L); vs = com.openrs.dash.data.ObdMerge.merge(vs, snapshot!!) }
        // Request before those frames, so without the filter the resurrected tag would pass as a second frame after the request
        val o = request(EscStatus.PARTIAL)
        assertEquals(Outcome.NotConfirmed(EscStatus.PARTIAL, 3_300L), o)
    }

    @Test fun `a merge keeps the live tags when the reply carries none of them`() {
        val snapshot = vs
        frame(SPORT, 3_000L); frame(SPORT, 3_020L)
        vs = com.openrs.dash.data.ObdMerge.merge(vs, snapshot)
        assertEquals(3_020L, vs.fieldLastUpdateMs[CanDecoder.ESC_FRAME_TAG])
        assertEquals(3_000L, vs.fieldLastUpdateMs[CanDecoder.ESC_FRAME_PREV_TAG])
    }

    @Test fun `a frame's tags don't touch the 1 s report stamp`() {
        frame(ON, 1_000L); frame(ON, 1_020L); frame(ON, 1_040L)
        assertEquals(1_000L, vs.fieldLastUpdateMs[EscCommand.STAMP_TAG])
        assertEquals(1_040L, vs.fieldLastUpdateMs[CanDecoder.ESC_FRAME_TAG])
        assertEquals(1_020L, vs.fieldLastUpdateMs[CanDecoder.ESC_FRAME_PREV_TAG])
    }
}
