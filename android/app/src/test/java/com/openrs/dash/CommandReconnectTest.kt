package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.DriveCommandResult
import com.openrs.dash.can.EscCommand
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.LinkPhase
import com.openrs.dash.can.executeDriveModeChange
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.CanDataService
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #252 / #125: "the same connection" holds across SlcanConnection's own retry, which keeps the job and its generation. A drop and
 * a return inside a wait must read as lost, whoever is looking when. Production functions with their default identity, on the
 * service's real LinkOwnership, published as the job's state collector does; virtual time, a scripted car. The real connection's
 * retry against a fake adapter is the emulator's case 4b.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class CommandReconnectTest {
    private class Sender : FirmwareCommandSender {
        var calls = 0
        override suspend fun setDriveMode(mode: Int): Result<Unit> { calls++; return Result.success(Unit) }
        override suspend fun setEscMode(mode: Int): Result<Unit> { calls++; return Result.success(Unit) }
    }

    private val link get() = CanDataService.link
    private var gen = 0
    private var vs = VehicleState(isConnected = true, driveMode = DriveMode.NORMAL)

    @Before fun connected() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        gen = link.transition { link.begin() }
        link.publish(gen, LinkPhase.CONNECTED) { }
    }

    @After fun released() { link.transition { link.end(LinkPhase.OFF) } }

    private fun TestScope.at(ms: Long, block: () -> Unit) = backgroundScope.launch { delay(ms - testScheduler.currentTime); block() }

    private fun TestScope.dropAndReturn(dropMs: Long, backMs: Long) {
        at(dropMs) { vs = vs.copy(isConnected = false); link.publish(gen, LinkPhase.RECONNECTING) { } }
        at(backMs) { vs = vs.copy(isConnected = true); link.publish(gen, LinkPhase.CONNECTED) { } }
    }

    /** What the decoder's stamps say after two agreeing reports and, for Sport / Track, two agreeing details. */
    private fun TestScope.report(mode: DriveMode, atMs: Long) = at(atMs) {
        val now = testScheduler.currentTime
        vs = vs.copy(driveMode = mode, fieldLastUpdateMs = vs.fieldLastUpdateMs
            .filterKeys { !it.startsWith(CanDecoder.MODE_RUN_PREFIX) } + (CanDecoder.modeRunTag(mode) to now))
            .markFresh("driveMode", CanDecoder.MODE_RUN_PREV_TAG, CanDecoder.MODE_DETAIL_TAG,
                CanDecoder.MODE_DETAIL_PAIR_TAG, CanDecoder.MODE_RESOLVED_DETAIL_TAG, CanDecoder.MODE_RESOLVED_PAIR_TAG, now = now)
    }

    private suspend fun TestScope.drive(sender: Sender, target: DriveMode) =
        executeDriveModeChange(sender, target, DriveMode.NORMAL, state = { vs }, clock = { testScheduler.currentTime }, firmwareLabel = { "t" })

    private fun events() = DiagnosticLogger.sessionEvents.filter { it.type == "DM_CMD" }.map { it.message }

    @Test fun `drive mode - a drop and return inside the settle is lost, even though the new link reports the target`() = runTest {
        dropAndReturn(100, 1_600)
        report(DriveMode.TRACK, 1_700)
        val s = Sender()
        assertEquals(DriveCommandResult.ConnectionLost, drive(s, DriveMode.TRACK))
        assertEquals("nothing further is sent", 1, s.calls)
        assertTrue(events().toString(), events().none { it.startsWith("CONFIRMED") })
        assertTrue(events().toString(), events().any { it.startsWith("CONNECTION CHANGED") })
    }

    @Test fun `drive mode - a drop and return between two polls is lost`() = runTest {
        dropAndReturn(5_020, 5_060)                                     // polls land on 2 s + 100 ms steps
        report(DriveMode.TRACK, 5_070)
        assertEquals(DriveCommandResult.ConnectionLost, drive(Sender(), DriveMode.TRACK))
    }

    @Test fun `drive mode - a drop and return inside the correction's settle is lost, after the one correction`() = runTest {
        report(DriveMode.DRIFT, 4_000)                                  // overshoot: the correction goes out at 17 s
        dropAndReturn(17_200, 18_000)
        report(DriveMode.SPORT, 18_100)
        val s = Sender()
        assertEquals(DriveCommandResult.ConnectionLost, drive(s, DriveMode.SPORT))
        assertEquals("the request and the one correction", 2, s.calls)
    }

    @Test fun `control - with no drop the same report confirms`() = runTest {
        report(DriveMode.TRACK, 1_700)
        val s = Sender()
        assertEquals(DriveCommandResult.Success, drive(s, DriveMode.TRACK))
        assertEquals(1, s.calls)
    }

    @Test fun `control - a phase published again unchanged isn't a new connection`() = runTest {
        at(1_000) { link.publish(gen, LinkPhase.CONNECTED) { } }
        report(DriveMode.TRACK, 1_700)
        assertEquals(DriveCommandResult.Success, drive(Sender(), DriveMode.TRACK))
    }

    @Test fun `ESC - a drop and return between two polls is lost, even though the new link reports the target`() = runTest {
        vs = vs.copy(escStatus = EscStatus.ON)
        dropAndReturn(120, 160)                                         // polls at 100 and 200 ms: neither sees it disconnected
        at(170) { vs = vs.copy(escStatus = EscStatus.PARTIAL).markFresh(EscCommand.STAMP_TAG, now = testScheduler.currentTime) }
        val o = EscCommand.execute(Sender(), EscStatus.PARTIAL, { vs }, clock = { testScheduler.currentTime })
        assertEquals(EscCommand.Outcome.ConnectionLost, o)
    }

    @Test fun `ESC - control, with no drop the same report confirms`() = runTest {
        vs = vs.copy(escStatus = EscStatus.ON)
        at(170) { vs = vs.copy(escStatus = EscStatus.PARTIAL).markFresh(EscCommand.STAMP_TAG, now = testScheduler.currentTime) }
        val o = EscCommand.execute(Sender(), EscStatus.PARTIAL, { vs }, clock = { testScheduler.currentTime })
        assertEquals(EscCommand.Outcome.Confirmed(200L), o)
    }
}
