package com.openrs.dash

import com.openrs.dash.can.DriveCommandResult
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.executeDriveModeChange
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Drive-mode confirmation (#220 follow-up): the adapter's OK is "accepted"; only a resolved 0x1B0 report of the target
 * stamped after the request, on the same connection, is "confirmed". Production function, virtual time; the "car" is a
 * scripted VehicleState timeline (mode + `driveMode` stamp). Timing (2 s settle + 15 s) and the single correction are
 * unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DriveModeConfirmTest {

    private class Sender : FirmwareCommandSender {
        var calls = 0
        override suspend fun setDriveMode(mode: Int): Result<Unit> { calls++; return Result.success(Unit) }
        override suspend fun setEscMode(mode: Int): Result<Unit> = Result.success(Unit)
    }

    /** The car: [mode] reported at [atMs] (stamped then), on connection [link]. */
    private class Car(var vs: VehicleState, var link: Int = 1)

    private fun TestScope.report(car: Car, mode: DriveMode, atMs: Long) = backgroundScope.launch {
        kotlinx.coroutines.delay(atMs - testScheduler.currentTime)
        car.vs = car.vs.copy(driveMode = mode).markFresh("driveMode", now = testScheduler.currentTime)
    }

    private suspend fun TestScope.run(car: Car, sender: Sender, target: DriveMode, current: DriveMode) =
        executeDriveModeChange(sender, target, current, state = { car.vs }, connectionId = { car.link },
            clock = { testScheduler.currentTime }, firmwareLabel = { "openRS_ test" })

    private fun events() = DiagnosticLogger.sessionEvents.filter { it.type == "DM_CMD" }.map { it.message }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @Test fun `a cached matching mode with no frame after the request is not a confirmation`() = runTest {
        // SPORT is held from before the request (stamped at t = 0, request later) and never reported again
        val car = Car(VehicleState(isConnected = true, driveMode = DriveMode.SPORT).markFresh("driveMode", now = 0L))
        advanceTimeBy(500)
        val sender = Sender()
        val r = run(car, sender, DriveMode.SPORT, DriveMode.NORMAL)
        assertEquals(DriveCommandResult.NoConfirmation, r)
        assertEquals("no correction for a mode that was never reported", 1, sender.calls)
        assertTrue(events().toString(), events().any { it.startsWith("ACCEPTED by the adapter") })
        assertTrue(events().toString(), events().any { it.startsWith("NOT CONFIRMED") && it.contains("not reported since the request") })
    }

    @Test fun `a reconnect while waiting is ConnectionLost even if the new link then reports the target`() = runTest {
        val car = Car(VehicleState(isConnected = true, driveMode = DriveMode.NORMAL))
        backgroundScope.launch {
            kotlinx.coroutines.delay(3_000); car.link = 2                                        // reconnect
            car.vs = car.vs.copy(driveMode = DriveMode.SPORT).markFresh("driveMode", now = testScheduler.currentTime)
        }
        val sender = Sender()
        val r = run(car, sender, DriveMode.SPORT, DriveMode.NORMAL)
        assertEquals(DriveCommandResult.ConnectionLost, r)
        assertEquals("no correction sent", 1, sender.calls)
        assertTrue(events().any { it.startsWith("CONNECTION CHANGED") })
        assertTrue(events().none { it.startsWith("CONFIRMED") })
    }

    @Test fun `Track reached through a transient Sport report is confirmed without a correction`() = runTest {
        val car = Car(VehicleState(isConnected = true, driveMode = DriveMode.NORMAL))
        report(car, DriveMode.SPORT, 4_600)                                                    // 0x420 still says Sport
        report(car, DriveMode.TRACK, 7_000)                                                    // then Track
        val sender = Sender()
        val r = run(car, sender, DriveMode.TRACK, DriveMode.NORMAL)
        assertEquals(DriveCommandResult.Success, r)
        assertEquals(1, sender.calls)
        assertTrue(events().toString(), events().any { it.startsWith("CONFIRMED by CAN: Track reported 7") })
    }

    @Test fun `a fresh report of the target after the request confirms, with its time`() = runTest {
        val car = Car(VehicleState(isConnected = true, driveMode = DriveMode.NORMAL).markFresh("driveMode", now = 0L))
        report(car, DriveMode.SPORT, 4_500)
        val r = run(car, Sender(), DriveMode.SPORT, DriveMode.NORMAL)
        assertEquals(DriveCommandResult.Success, r)
        assertTrue(events().toString(), events().any { it == "CONFIRMED by CAN: Sport reported 4500 ms after the request" })
    }

    @Test fun `a fresh overshoot still gets the one correction, confirmed only by a later report`() = runTest {
        val car = Car(VehicleState(isConnected = true, driveMode = DriveMode.NORMAL))
        report(car, DriveMode.DRIFT, 4_000)                                                    // overshoot
        report(car, DriveMode.SPORT, 22_000)                                                   // after the correction
        val sender = Sender()
        val r = run(car, sender, DriveMode.SPORT, DriveMode.NORMAL)
        assertEquals(DriveCommandResult.Success, r)
        assertEquals(2, sender.calls)
        assertTrue(events().toString(), events().any { it.startsWith("Auto-correction succeeded — CONFIRMED by CAN") })
    }
}
