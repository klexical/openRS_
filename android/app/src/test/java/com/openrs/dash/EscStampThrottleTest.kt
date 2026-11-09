package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.EscCommand
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #125 — the 1 s stamp throttle on 0x1C0 vs ESC confirmation. Frames go through the production CanDecoder.decode +
 * stampFresh (what LinkSession.processCanFrame does, called here with the virtual clock) into the production
 * EscCommand.execute. The request is at R = 2005 ms, 5 ms after the unchanged stamp at 2000; polls fall on R + 100 k and
 * frames on a 20 ms grid, so no two events tie unless a test says so.
 *
 * The defect needs a change less than 1 s after a stamp taken at or before the request: the new value sat unstamped, so a
 * confirmation came up to 1 s late (red-a) or, with no later 0x1C0 before the window ended, not at all (red-b). A change
 * late in the window was never lost (the late-window controls pass before and after the fix). The stamp is the CAN report,
 * not proof the request caused it. A change adds one stamp and leaves the 1 s reports on their own rhythm, so a change just
 * before a request can't delay the report after it. Since rule C (#125, EscRuleCTest) a confirmation needs two frames, not one
 * stamp: the outcomes here that changed with it say so, and the stamps themselves are unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EscStampThrottleTest {
    private class Api : FirmwareCommandSender {
        override suspend fun setDriveMode(mode: Int) = Result.success(Unit)
        override suspend fun setEscMode(mode: Int) = Result.success(Unit)
    }

    private var vs = VehicleState(isConnected = true)
    private var gen = 1
    private val stamp get() = vs.fieldLastUpdateMs[EscCommand.STAMP_TAG]

    private fun frame(raw: Int, now: Long) {
        val data = byteArrayOf(0, (raw shl 4).toByte(), 0, 0, 0, 0, 0, 0)
        vs = CanDecoder.stampFresh(0x1C0, vs, CanDecoder.decode(0x1C0, data, vs)!!, now)
    }
    /** The ABS broadcasting every [periodMs] from 0; the reported value depends only on virtual time. */
    private fun TestScope.bus(periodMs: Long = 20L, raw: (Long) -> Int) = backgroundScope.launch {
        while (true) { frame(raw(testScheduler.currentTime), testScheduler.currentTime); delay(periodMs) }
    }
    /** Single frames at exact virtual times; nothing else on the bus. */
    private fun TestScope.frames(vararg rows: Pair<Long, Int>) {
        for ((at, raw) in rows) backgroundScope.launch { delay(at); frame(raw, at) }
    }
    private suspend fun TestScope.request(target: EscStatus, stampAtRequest: Long? = 2_000L): EscCommand.Outcome {
        testScheduler.advanceTimeBy(R - testScheduler.currentTime)
        if (stampAtRequest != null) assertEquals("premise: the last stamp at the request", stampAtRequest, stamp)
        return EscCommand.execute(Api(), target, { vs }, connectionId = { gen }, clock = { testScheduler.currentTime })
    }

    private companion object {
        const val R = 2_005L
        const val ON = 0; const val OFF = 1; const val SPORT = 2
    }

    // ── red before the fix: a change less than 1 s after a stamp taken before the request ───────────────────────────

    @Test fun `red-a - a change inside the throttle is stamped at its frame's time and confirms on its second frame, not at the next stamp`() = runTest {
        bus { t -> if (t >= 2_400L) SPORT else ON }
        val o = request(EscStatus.PARTIAL)
        assertEquals("first poll after the 2420 frame = R + 500", EscCommand.Outcome.Confirmed(500L), o)
        assertEquals(2_400L, stamp)
    }

    @Test fun `red-b - change inside the throttle, then no further 0x1C0 until the window ends, reads NOT CONFIRMED on one frame`() = runTest {
        frames(1_000L to ON, 2_000L to ON, 2_400L to SPORT)
        assertEquals(EscCommand.Outcome.NotConfirmed(EscStatus.PARTIAL, 3_300L), request(EscStatus.PARTIAL))
    }

    // ── a change just before the request must not delay the first report after it ──────────────────────────────────

    /** Request at [at] for the mode the car just reached; the last unchanged stamp is 2000, the change 2900. */
    private suspend fun TestScope.requestAt(at: Long, target: EscStatus): EscCommand.Outcome {
        testScheduler.advanceTimeBy(at - testScheduler.currentTime)
        return EscCommand.execute(Api(), target, { vs }, connectionId = { gen }, clock = { testScheduler.currentTime })
    }

    @Test fun `held-a - change just before the request, steady broadcast, confirms at the regular report`() = runTest {
        bus { t -> if (t >= 2_900L) SPORT else ON }
        assertEquals(EscCommand.Outcome.Confirmed(100L), requestAt(2_905L, EscStatus.PARTIAL))
    }

    @Test fun `held-b - change just before the request, sparse frames, still confirms`() = runTest {
        frames(1_000L to ON, 2_000L to ON, 2_900L to SPORT, 3_100L to SPORT, 3_180L to SPORT)
        assertEquals(EscCommand.Outcome.Confirmed(300L), requestAt(2_905L, EscStatus.PARTIAL))
    }

    // ── a change is stamped whatever the gap since the last stamp, and whichever values it joins ─────────────────────

    @Test fun `gap - a change 20 ms after the last stamp is stamped at once`() = runTest {
        frames(1_000L to ON, 2_000L to ON, 2_020L to SPORT, 2_040L to SPORT)
        assertEquals(EscCommand.Outcome.Confirmed(100L), request(EscStatus.PARTIAL))
        assertEquals(2_020L, stamp)
    }

    @Test fun `gap - a change 980 ms after the last stamp is stamped at once`() = runTest {
        frames(1_000L to ON, 2_000L to ON, 2_980L to SPORT, 3_000L to SPORT)
        assertEquals(EscCommand.Outcome.Confirmed(1_000L), request(EscStatus.PARTIAL))
    }

    @Test fun `value - every change between ON, OFF and SPORT is stamped at once`() {
        val raws = listOf(ON, OFF, SPORT)
        for (from in raws) for (to in raws) if (from != to) {
            vs = VehicleState(isConnected = true)
            frame(from, 1_000L); frame(from, 2_000L); frame(to, 2_400L)
            assertEquals("$from -> $to", 2_400L, stamp)
        }
    }

    // ── controls: a change late in the window was never lost ─────────────────────────────────────────────────────────

    @Test fun `late window - change in the last second, steady broadcast, confirms`() = runTest {
        bus { t -> if (t >= 5_020L) SPORT else ON }                      // window ends at R + 3300 = 5305
        assertEquals(EscCommand.Outcome.Confirmed(3_100L), request(EscStatus.PARTIAL))
    }

    @Test fun `late window - change at the very last poll, steady broadcast, reads NOT CONFIRMED on one frame`() = runTest {
        bus { t -> if (t >= 5_300L) SPORT else ON }
        assertEquals(EscCommand.Outcome.NotConfirmed(EscStatus.PARTIAL, 3_300L), request(EscStatus.PARTIAL))
    }

    @Test fun `late window - change in the last second, 1 Hz broadcast plus an off-cadence change frame, reads NOT CONFIRMED on one frame`() = runTest {
        frames(1_000L to ON, 2_000L to ON, 3_000L to ON, 4_000L to ON, 5_000L to ON, 5_020L to SPORT)
        assertEquals(EscCommand.Outcome.NotConfirmed(EscStatus.PARTIAL, 3_300L), request(EscStatus.PARTIAL))
    }

    @Test fun `late window - two frames in the last second after silence since the pre-request stamp confirm`() = runTest {
        frames(1_000L to ON, 2_000L to ON, 5_020L to SPORT, 5_040L to SPORT)
        assertEquals(EscCommand.Outcome.Confirmed(3_100L), request(EscStatus.PARTIAL))
    }

    // ── controls ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `c - an unchanged frame inside the throttle is not stamped, due again at 1 s`() {
        frame(ON, 1_000L); assertEquals(1_000L, stamp)
        frame(ON, 1_999L); assertEquals(1_000L, stamp)
        frame(ON, 2_000L); assertEquals(2_000L, stamp)
    }

    @Test fun `c - the unchanged frame right after a change does not move the stamp`() {
        frame(ON, 1_000L)
        frame(SPORT, 2_000L)                                             // due anyway: stamped before and after the fix
        frame(SPORT, 2_020L); assertEquals(2_000L, stamp)
    }

    @Test fun `c - a stamp equal to the request millisecond does not confirm`() = runTest {
        frames(1_000L to ON, 2_000L to ON, R to SPORT)
        assertEquals(EscCommand.Outcome.NotConfirmed(EscStatus.PARTIAL, 3_300L), request(EscStatus.PARTIAL))
    }

    @Test fun `c - other CAN IDs stay throttled even when their value changes`() {
        val first = CanDecoder.stampFresh(0x0F8, vs, vs.copy(oilTempC = 90.0), 5_000L)
        val second = CanDecoder.stampFresh(0x0F8, first, first.copy(oilTempC = 91.0), 5_500L)
        assertEquals(5_000L, second.fieldLastUpdateMs["oilTempC"])
    }

    @Test fun `d - a change before the request with no later report never confirms`() = runTest {
        frames(1_000L to ON, 2_000L to ON, 2_001L to SPORT)              // its stamp moves from 2000 to 2001 with the fix
        assertEquals(EscCommand.Outcome.NotConfirmed(EscStatus.PARTIAL, 3_000L), request(EscStatus.PARTIAL, stampAtRequest = null))
        assertEquals(2_001L, stamp)
    }

    @Test fun `e - the confirmation window is unchanged for each from - to`() {
        val all = listOf(EscStatus.ON, EscStatus.PARTIAL, EscStatus.OFF)
        val expected = mapOf(
            (EscStatus.ON to EscStatus.ON) to 3_000L, (EscStatus.ON to EscStatus.PARTIAL) to 3_300L,
            (EscStatus.ON to EscStatus.OFF) to 8_000L, (EscStatus.PARTIAL to EscStatus.ON) to 3_300L,
            (EscStatus.PARTIAL to EscStatus.PARTIAL) to 3_000L, (EscStatus.PARTIAL to EscStatus.OFF) to 8_000L,
            (EscStatus.OFF to EscStatus.ON) to 3_300L, (EscStatus.OFF to EscStatus.PARTIAL) to 4_600L,
            (EscStatus.OFF to EscStatus.OFF) to 3_000L,
        )
        for (from in all) for (to in all) assertEquals("$from -> $to", expected[from to to], EscCommand.windowMs(from, to))
        assertEquals(3_000L, EscCommand.REACTION_MS)
    }

    @Test fun `f - reconnect while waiting is connection lost, even with a change frame on the new link`() = runTest {
        bus { t -> if (t >= 2_400L) SPORT else ON }
        backgroundScope.launch { delay(2_390L); gen = 2 }
        assertEquals(EscCommand.Outcome.ConnectionLost, request(EscStatus.PARTIAL))
    }

    // ── fix-specific: fail before the fix, describe it after ─────────────────────────────────────────────────────────

    @Test fun `red-c - a change inside the throttle is stamped at once, and the 1 s reports keep their own rhythm`() {
        val stamps = mutableListOf<Long?>()
        var t = 1_000L
        while (t <= 4_500L) {
            frame(if (t >= 2_400L) SPORT else ON, t)
            if (stamps.lastOrNull() != stamp) stamps += stamp
            t += 20L
        }
        assertEquals(listOf<Long?>(1_000L, 2_000L, 2_400L, 3_000L, 4_000L), stamps)
    }

    // ── known exposure, pinned so it stays visible (one 0x1C0 report confirms; #252's two-report rule is drive mode only) ─

    @Test fun `stray red-d - one stray frame in the first second, seen by a poll, doesn't confirm`() = runTest {
        // 20 ms on the bus, reverted before the car ever changed. Closed by rule C (#125)
        bus { t -> if (t == 2_400L) SPORT else ON }
        assertEquals(EscCommand.Outcome.NotConfirmed(EscStatus.ON, 3_300L), request(EscStatus.PARTIAL))
    }

    @Test fun `stray - one stray frame after the first post-request stamp doesn't confirm`() = runTest {
        bus { t -> if (t == 3_400L) SPORT else ON }
        assertEquals(EscCommand.Outcome.NotConfirmed(EscStatus.ON, 3_300L), request(EscStatus.PARTIAL))
    }

    @Test fun `stray - a stray frame between two polls never confirms`() = runTest {
        bus { t -> if (t == 2_420L) SPORT else ON }                      // polls at 2405 and 2505; reverted at 2440
        assertEquals(EscCommand.Outcome.NotConfirmed(EscStatus.ON, 3_300L), request(EscStatus.PARTIAL))
    }

    @Test fun `test frames decode to the intended ESC value (bits 10-11, RS_HS VAL_ 448)`() {
        frame(ON, 1_000L); assertEquals(EscStatus.ON, vs.escStatus)
        frame(OFF, 1_020L); assertEquals(EscStatus.OFF, vs.escStatus)
        frame(SPORT, 1_040L); assertEquals(EscStatus.PARTIAL, vs.escStatus)
        frame(3, 1_060L); assertEquals(EscStatus.UNRECOGNIZED, vs.escStatus)   // raw 3: no name (REQ-rc8-esc-owner-wording)
    }
}
