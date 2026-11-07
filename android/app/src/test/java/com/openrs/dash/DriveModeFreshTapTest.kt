package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.DRIVE_MODE_OFFER_TIMEOUT_MS
import com.openrs.dash.can.DriveCommandResult
import com.openrs.dash.can.DriveModeOffer
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.askDriver
import com.openrs.dash.can.executeDriveModeChange
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #252 S5: nothing but a fresh driver tap sends a further drive-mode command. Real decoder and stamps, real wait, on a virtual clock,
 * 0x1B0 every 100 ms and 0x420 every 600 ms at every 50 ms phase. A command moves the scripted car after the settle, as the fake's
 * via-sport does, so a command the app shouldn't have sent shows as the car leaving the mode the driver chose. SYNTHETIC.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveModeFreshTapTest {
    private class Car(var mode: DriveMode) { var vs = VehicleState(isConnected = true, driveMode = mode); var link = 1 }

    private inner class Sender(val car: Car, val scope: TestScope, val moves: (Int, DriveMode) -> DriveMode?) : FirmwareCommandSender {
        var calls = 0
        override suspend fun setDriveMode(mode: Int): Result<Unit> {
            val n = ++calls
            val target = DriveMode.values().first { it.toFirmwareInt() == mode && it != DriveMode.UNKNOWN }
            scope.backgroundScope.launch { delay(SETTLE_MS); moves(n, target)?.let { car.mode = it } }
            return Result.success(Unit)
        }
        override suspend fun setEscMode(mode: Int): Result<Unit> = Result.success(Unit)
    }

    private companion object {
        const val REQUEST_MS = 3_000L
        const val SETTLE_MS = 2_500L
        val PHASES = 0L until 600L step 50L
        fun detail(m: DriveMode) = when (m) { DriveMode.NORMAL -> 0x10C4; DriveMode.SPORT -> 0x11CC; DriveMode.TRACK -> 0x11CD; else -> 0x12C4 }
        fun nibble(m: DriveMode) = when (m) { DriveMode.NORMAL -> 0; DriveMode.DRIFT -> 2; else -> 1 }
    }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null); CanDecoder.resetSessionState() }

    private fun TestScope.feed(car: Car, id: Int, data: ByteArray) {
        val prev = car.vs
        val d = CanDecoder.decode(id, data, prev)
        if (d != null && d !== prev) car.vs = CanDecoder.stampFresh(id, prev, d, testScheduler.currentTime)
    }

    private fun TestScope.bus(car: Car, phase: Long) {
        backgroundScope.launch { while (true) { feed(car, 0x1B0, ByteArray(8).also { it[6] = (nibble(car.mode) shl 4).toByte() }); delay(100) } }
        backgroundScope.launch {
            delay(phase)
            while (true) { val v = detail(car.mode); feed(car, 0x420, ByteArray(8).also { it[6] = (v ushr 8).toByte(); it[7] = v.toByte() }); delay(600) }
        }
    }

    private class Out(val result: DriveCommandResult, val calls: Int, val finalMode: DriveMode)

    /** [script]: what the car does after the Nth command. [manual]: the driver selects that mode by hand at that time after the request. */
    private fun overshoot(phase: Long, script: (Int, DriveMode) -> DriveMode?, manual: Pair<Long, DriveMode>? = null, tap: Boolean = false): Out {
        var out: Out? = null
        runTest {
            CanDecoder.resetSessionState()
            val car = Car(DriveMode.NORMAL)
            bus(car, phase)
            delay(REQUEST_MS)
            manual?.let { (at, m) -> backgroundScope.launch { delay(at); car.mode = m } }
            val sender = Sender(car, this, script)
            var r = executeDriveModeChange(sender, DriveMode.SPORT, car.vs.driveMode, state = { car.vs }, connectionId = { car.link },
                clock = { testScheduler.currentTime }, firmwareLabel = { "t" })
            val offer = (r as? DriveCommandResult.LandedElsewhere)?.offer
            if (tap && offer != null && askDriver(offer, ask = { delay(2_000); true }, state = { car.vs }, connectionId = { car.link })) {
                r = executeDriveModeChange(sender, offer.requested, offer.observed, state = { car.vs }, connectionId = { car.link },
                    clock = { testScheduler.currentTime }, firmwareLabel = { "t" })
            }
            delay(10_000)
            out = Out(r, sender.calls, car.mode)
        }
        return out!!
    }

    @Test fun `overshoot, no tap - one command, the car stays where it landed, at every phase`() {
        for (p in PHASES) {
            val o = overshoot(p, { n, t -> if (n == 1) DriveMode.TRACK else t })
            assertEquals("phase $p: commands", 1, o.calls)
            assertEquals("phase $p: car", DriveMode.TRACK, o.finalMode)
        }
    }

    @Test fun `a third mode selected by hand during the wait stands, at every phase`() {
        for (p in PHASES) {
            val o = overshoot(p, { n, t -> if (n == 1) null else t }, manual = 8_000L to DriveMode.DRIFT)
            assertEquals("phase $p: commands", 1, o.calls)
            assertEquals("phase $p: car", DriveMode.DRIFT, o.finalMode)
        }
    }

    @Test fun `control - a real landing confirms on one command, at every phase`() {
        for (p in PHASES) {
            val o = overshoot(p, { _, t -> t })
            assertEquals("phase $p", DriveCommandResult.Success, o.result)
            assertEquals("phase $p: commands", 1, o.calls)
            assertEquals("phase $p: car", DriveMode.SPORT, o.finalMode)
        }
    }

    // ── No false question: the offer's own gate (QA, S5 review) ──

    @Test fun `a single stray frame of another mode at the window's end reads NoConfirmation, with no question`() = runTest {
        CanDecoder.resetSessionState()
        val car = Car(DriveMode.NORMAL)
        backgroundScope.launch {                                                  // the last poll is at R + 17 s = 20 s
            while (testScheduler.currentTime < 19_500L) { feed(car, 0x1B0, ByteArray(8).also { it[6] = 0 }); delay(100) }
            delay(19_950L - testScheduler.currentTime)
            feed(car, 0x1B0, ByteArray(8).also { it[6] = 0x20 })                  // one Drift report, then nothing
        }
        backgroundScope.launch { while (true) { feed(car, 0x420, ByteArray(8).also { it[6] = 0x10; it[7] = 0xC4.toByte() }); delay(600) } }
        delay(REQUEST_MS)
        val sender = Sender(car, this) { _, _ -> null }
        val r = executeDriveModeChange(sender, DriveMode.SPORT, DriveMode.NORMAL, state = { car.vs }, connectionId = { car.link },
            clock = { testScheduler.currentTime }, firmwareLabel = { "t" })
        assertEquals("premise: the stray is what the app reads", DriveMode.DRIFT, car.vs.driveMode)
        assertEquals(DriveCommandResult.NoConfirmation, r)
        assertEquals(1, sender.calls)
    }

    @Test fun `the car staying on the current mode reads NoConfirmation, with no question, at every phase`() {
        for (p in PHASES) {
            val o = overshoot(p, { _, _ -> null })
            assertEquals("phase $p", DriveCommandResult.NoConfirmation, o.result)
            assertEquals("phase $p: commands", 1, o.calls)
            assertEquals("phase $p: car", DriveMode.NORMAL, o.finalMode)
        }
    }

    // ── The offer ──

    private fun landed(requested: DriveMode = DriveMode.SPORT, observed: DriveMode = DriveMode.TRACK, run: Long = 5L, link: Int = 7) =
        DriveModeOffer(requested, observed, link, run)
    private fun on(mode: DriveMode, run: Long = 5L, connected: Boolean = true) = VehicleState(isConnected = connected, driveMode = mode,
        fieldLastUpdateMs = mapOf(CanDecoder.modeRunTag(mode) to run))
    private fun events() = DiagnosticLogger.sessionEvents.filter { it.type == "DM_CMD" }.map { it.message }

    @Test fun `overshoot, then the driver's tap - exactly one further command, confirmed by the normal rule, at every phase`() {
        for (p in PHASES) {
            val o = overshoot(p, { n, t -> if (n == 1) DriveMode.TRACK else t }, tap = true)
            assertEquals("phase $p: commands", 2, o.calls)
            assertEquals("phase $p: car", DriveMode.SPORT, o.finalMode)
            assertEquals("phase $p", DriveCommandResult.Success, o.result)
        }
    }

    @Test fun `a further landing elsewhere after the tap asks again, sending nothing by itself`() {
        val o = overshoot(0, { n, _ -> if (n == 1) DriveMode.TRACK else DriveMode.DRIFT }, tap = true)
        assertEquals(2, o.calls)
        assertEquals(DriveMode.DRIFT, o.finalMode)
        val offer = (o.result as DriveCommandResult.LandedElsewhere).offer
        assertEquals(DriveMode.SPORT to DriveMode.DRIFT, offer.requested to offer.observed)
    }

    @Test fun `the offer for a mode selected by hand names that mode`() {
        val o = overshoot(0, { n, t -> if (n == 1) null else t }, manual = 8_000L to DriveMode.DRIFT)
        val offer = (o.result as DriveCommandResult.LandedElsewhere).offer
        assertEquals(DriveMode.SPORT to DriveMode.DRIFT, offer.requested to offer.observed)
    }

    @Test fun `a tap on a standing offer sends`() = runTest {
        assertTrue(askDriver(landed(), ask = { delay(1_000); true }, state = { on(DriveMode.TRACK) }, connectionId = { 7 }))
        assertTrue(events().toString(), events().last().startsWith("Driver asked for Sport with the car on Track"))
    }

    @Test fun `dismissed - nothing sent`() = runTest {
        assertFalse(askDriver(landed(), ask = { delay(1_000); false }, state = { on(DriveMode.TRACK) }, connectionId = { 7 }))
        assertEquals("Offer dismissed, nothing sent", events().last())
    }

    @Test fun `no response - taken away at the timeout, nothing sent, and a tap later has nothing to act on`() = runTest {
        var gone = false
        val sent = askDriver(landed(), ask = {
            try { delay(3_600_000); true } finally { gone = true }
        }, state = { on(DriveMode.TRACK) }, connectionId = { 7 })
        assertFalse(sent)
        assertTrue("the question was taken away", gone)
        assertEquals(DRIVE_MODE_OFFER_TIMEOUT_MS, testScheduler.currentTime)
        assertEquals("Offer timed out, nothing sent", events().last())
    }

    @Test fun `a drop and return takes the offer away, though the car still reads the same mode`() = runTest {
        var link = 7
        var gone = false
        backgroundScope.launch { delay(5_000); link = 9 }
        assertFalse(askDriver(landed(), ask = { try { delay(3_600_000); true } finally { gone = true } }, state = { on(DriveMode.TRACK) }, connectionId = { link }))
        assertTrue(gone)
        assertTrue("well before the timeout: ${testScheduler.currentTime}", testScheduler.currentTime < 6_000)
        assertEquals("Offer withdrawn, nothing sent: connection or mode changed", events().last())
    }

    @Test fun `a tap that lands in the instant after a reconnect sends nothing`() = runTest {
        var link = 7
        assertFalse(askDriver(landed(), ask = { delay(50); link = 9; true }, state = { on(DriveMode.TRACK) }, connectionId = { link }))
    }

    @Test fun `disconnected - taken away`() = runTest {
        assertFalse(askDriver(landed(), ask = { true }, state = { on(DriveMode.TRACK, connected = false) }, connectionId = { 7 }))
    }

    @Test fun `the car moving on takes the offer away, also back to the same mode in a new run`() = runTest {
        var vs = on(DriveMode.TRACK)
        backgroundScope.launch { delay(2_000); vs = on(DriveMode.DRIFT, run = 2_000) }
        assertFalse(askDriver(landed(), ask = { delay(3_600_000); true }, state = { vs }, connectionId = { 7 }))
        assertFalse(landed().stands(on(DriveMode.TRACK, run = 9_000), 7))
        assertTrue(landed().stands(on(DriveMode.TRACK), 7))
    }

    @Test fun `closing the dock cancels the question, and the cancellation isn't swallowed`() = runTest {
        var gone = false
        val job = launch { askDriver(landed(), ask = { try { delay(3_600_000); true } finally { gone = true } }, state = { on(DriveMode.TRACK) }, connectionId = { 7 }) }
        testScheduler.advanceTimeBy(1_000)
        job.cancel()
        job.join()
        assertTrue(gone)
        assertTrue(job.isCancelled)
    }
}
