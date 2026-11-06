package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.DriveCommandResult
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.SlcanParser
import com.openrs.dash.can.executeDriveModeChange
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * rc.8 "mode-attribution" (#252) — drive-mode confirmation from DECODED frames, SYNTHETIC fixtures (test/resources/replay/mode-*).
 * RX lines go through the production SlcanParser, CanDecoder.decode and CanDecoder.stampFresh (what
 * LinkSession.processCanFrame does — called here with the virtual clock, because LinkSession stamps with the wall clock
 * and throttles stamps to 1 s, which a virtual-time replay can't drive); the request runs the production
 * executeDriveModeChange with a fake sender. NOTE rows are never fed.
 *
 * Invariant under test (no age number — that is a fix-time choice): a Sport / Track confirmation needs the 0x420 detail
 * AND the 0x1B0 that resolves the target both observed after the request on the same link — the resolving 0x1B0 after
 * that detail. A detail cached before the request can't disambiguate; a single outlier frame can't confirm; unresolved
 * evidence never triggers a correction. Red cases reproduce the defect and are @Ignore'd until the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModeAttributionReplayTest {
    private companion object { const val DEFECT = "#252 mode-attribution: reproduced, not fixed" }

    private class Sender : FirmwareCommandSender {
        var calls = 0
        override suspend fun setDriveMode(mode: Int): Result<Unit> { calls++; return Result.success(Unit) }
        override suspend fun setEscMode(mode: Int): Result<Unit> = Result.success(Unit)
    }

    private class Car { var vs = VehicleState(isConnected = true) }

    @Before fun session() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        CanDecoder.resetSessionState()
    }

    /** Plays the fixture's RX rows, sends its REQ at its time, returns the command result. */
    private fun TestScope.play(fixture: String, sender: Sender = Sender()): Pair<DriveCommandResult, Car> {
        val rows = ReplayFixture.load(fixture)
        val car = Car()
        for (r in rows.filter { it.kind == "RX" }) backgroundScope.launch {
            delay(r.atMs)
            val (id, data) = requireNotNull(SlcanParser.parse(r.text)) { "fixture line must parse: ${r.text}" }
            val prev = car.vs
            val decoded = CanDecoder.decode(id, data, prev)
            if (decoded != null && decoded !== prev) car.vs = CanDecoder.stampFresh(id, prev, decoded, testScheduler.currentTime)
        }
        val req = rows.single { it.kind == "REQ" }
        advanceTimeBy(req.atMs - testScheduler.currentTime)
        var result: DriveCommandResult? = null
        backgroundScope.launch {
            result = executeDriveModeChange(sender, DriveMode.valueOf(req.text), car.vs.driveMode, state = { car.vs },
                connectionId = { 1 }, clock = { testScheduler.currentTime }, firmwareLabel = { "replay" })
        }
        advanceTimeBy(40_000L)
        return requireNotNull(result) { "command never finished" } to car
    }

    @Test fun `control - Track detail then a resolving 0x1B0 after the request confirms`() = runTest {
        val sender = Sender()
        val (r, car) = play("mode-control-track.txt", sender)
        assertEquals(DriveCommandResult.Success, r)
        assertEquals(DriveMode.TRACK, car.vs.driveMode)
        assertEquals(1, sender.calls)
    }

    @Ignore(DEFECT) @Test fun `a 0x1B0 that resolved Sport can't confirm Track through a later 0x420 flip`() = runTest {
        val sender = Sender()
        val (r, _) = play("mode-detail-after-resolve.txt", sender)
        assertNotEquals("confirmed Track on a stamp that certified Sport", DriveCommandResult.Success, r)
        assertEquals("no correction from unresolved evidence", 1, sender.calls)
    }

    @Ignore(DEFECT) @Test fun `a detail cached before the request can't disambiguate a post-request 0x1B0`() = runTest {
        val sender = Sender()
        val (r, _) = play("mode-stale-detail.txt", sender)
        assertNotEquals("confirmed Track from pre-request 0x420 detail", DriveCommandResult.Success, r)
        assertEquals(1, sender.calls)
    }

    @Ignore(DEFECT) @Test fun `one outlier 0x420 frame can't confirm a command`() = runTest {
        val sender = Sender()
        val (r, car) = play("mode-outlier-420.txt", sender)
        assertEquals("the car ends in Track", DriveMode.TRACK, car.vs.driveMode)
        assertNotEquals("confirmed Sport from a single undocumented 0x420 frame", DriveCommandResult.Success, r)
    }
}
