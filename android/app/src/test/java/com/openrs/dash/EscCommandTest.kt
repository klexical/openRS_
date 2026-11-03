package com.openrs.dash

import com.openrs.dash.can.BusyException
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.EscCommand
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #125 — an adapter OK is request acceptance; ESC success is a CAN 0x1C0 frame *after* the request, on the same
 * connection, reporting the requested mode. 0x1C0 frames go through the production decode + freshness stamp
 * ([CanDecoder.decode] + [CanDecoder.stampFresh], what LinkSession does) on the test clock.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EscCommandTest {
    private class Api(private val result: Result<Unit> = Result.success(Unit), private val ackDelayMs: Long = 0L) : FirmwareCommandSender {
        val sent = mutableListOf<Int>()
        override suspend fun setDriveMode(mode: Int) = Result.success(Unit)
        override suspend fun setEscMode(mode: Int): Result<Unit> { sent += mode; delay(ackDelayMs); return result }
    }

    private var vs = VehicleState(isConnected = true)
    private var gen = 1
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    /** One 0x1C0 frame (ESCMode at byte 1 bits 5–4: 0 ON, 1 OFF, 2 SPORT) at [now]. */
    private fun frame(raw: Int, now: Long) {
        val decoded = CanDecoder.decode(0x1C0, bytes("00%02X000000000000".format(raw shl 4)), vs)!!
        vs = CanDecoder.stampFresh(0x1C0, vs, decoded, now)
    }
    /** The ABS broadcasting [raw] every 20 ms from [fromMs] (virtual time) until [untilMs]. */
    private fun TestScope.broadcast(raw: () -> Int, fromMs: Long = 0L, untilMs: Long = 20_000L) = launch {
        delay(fromMs)
        while (testScheduler.currentTime < untilMs) { frame(raw(), testScheduler.currentTime); delay(20L) }
    }
    private suspend fun TestScope.run(api: Api, target: EscStatus) =
        EscCommand.execute(api, target, { vs }, connectionId = { gen }, clock = { testScheduler.currentTime })

    @Test fun `ESC OFF — confirmation after the 5 s long press is accepted (the old 4 s window ended first)`() = runTest {
        var raw = 0
        frame(0, 0L)
        val bus = broadcast({ raw })
        launch { delay(EscCommand.LONG_PRESS_MS + 600L); raw = 1 }      // car reports OFF 5.6 s after the request
        val o = run(Api(), EscStatus.OFF)
        assertTrue("$o", o is EscCommand.Outcome.Confirmed && o.afterMs in 5_600L..6_700L)
        assertEquals(8_000L, EscCommand.windowMs(EscStatus.ON, EscStatus.OFF))
        bus.cancel()
    }

    @Test fun `a matching state from before the request never confirms`() = runTest {
        frame(2, 0L)                                                     // held SPORT, no frame after the request
        vs = vs.copy(escStatus = EscStatus.PARTIAL)
        testScheduler.advanceTimeBy(10L)
        val o = run(Api(), EscStatus.PARTIAL)
        assertTrue("$o", o is EscCommand.Outcome.NotConfirmed)
    }

    @Test fun `delayed confirmation inside the window, then nothing after the window`() = runTest {
        var raw = 0
        frame(0, 0L)
        val bus = broadcast({ raw })
        launch { delay(2_500L); raw = 2 }                                // SPORT from ON: 300 ms press + reaction
        assertTrue(run(Api(), EscStatus.PARTIAL) is EscCommand.Outcome.Confirmed)
        raw = 0
        launch { delay(EscCommand.windowMs(EscStatus.ON, EscStatus.PARTIAL) + 500L); raw = 2 }   // after the window
        val late = run(Api(), EscStatus.PARTIAL)
        assertEquals(EscStatus.ON, (late as EscCommand.Outcome.NotConfirmed).observed)
        bus.cancel()
    }

    @Test fun `overlapping requests — the second is busy and sends nothing`() = runTest {
        frame(0, 0L)
        val api = Api()
        val first = async { run(api, EscStatus.OFF) }
        testScheduler.advanceTimeBy(500L)
        assertEquals(EscCommand.Outcome.Busy, run(api, EscStatus.PARTIAL))
        assertEquals(listOf(2), api.sent)
        first.await()
        assertTrue("released after the first finished", run(Api(), EscStatus.PARTIAL) !is EscCommand.Outcome.Busy)
    }

    @Test fun `reconnect while waiting — connection lost even if the new link reports the mode`() = runTest {
        frame(0, 0L)
        launch { delay(1_000L); gen = 2; frame(1, testScheduler.currentTime) }   // new link reports OFF
        assertEquals(EscCommand.Outcome.ConnectionLost, run(Api(), EscStatus.OFF))
    }

    @Test fun `busy, refused and lost link are distinct outcomes`() = runTest {
        assertEquals(EscCommand.Outcome.Busy, run(Api(Result.failure(BusyException())), EscStatus.OFF))
        assertTrue(run(Api(Result.failure(RuntimeException("AT+FRS timeout"))), EscStatus.OFF) is EscCommand.Outcome.Failed)
        launch { delay(500L); vs = vs.copy(isConnected = false) }
        assertEquals(EscCommand.Outcome.ConnectionLost, run(Api(), EscStatus.OFF))
    }

    @Test fun `no command path is unsupported, nothing sent`() = runTest {
        assertEquals(EscCommand.Outcome.Unsupported, EscCommand.execute(null, EscStatus.OFF, { vs }))
    }

    @Test fun `window follows the firmware's own press plan`() {
        assertEquals(EscCommand.LONG_PRESS_MS + EscCommand.REACTION_MS, EscCommand.windowMs(EscStatus.PARTIAL, EscStatus.OFF))
        assertEquals(1_600L + EscCommand.REACTION_MS, EscCommand.windowMs(EscStatus.OFF, EscStatus.PARTIAL))
        assertEquals(300L + EscCommand.REACTION_MS, EscCommand.windowMs(EscStatus.OFF, EscStatus.ON))
    }
}
