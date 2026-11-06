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
 * evidence never triggers a correction. Fixed in the rc.8 reliability pass (#252): a resolved 0x1B0 records the receipt
 * time of the 0x420 detail it used, and a Sport / Track confirmation needs both after the request; an undocumented 0x420
 * byte 6 is not used; a resolved mode change is stamped at once (not held by the 1 s stamp throttle).
 * SPEC cases test the provenance rule on constructed sequences; TRAFFIC cases replay the ordinary sequences a car sends.
 * A mode reported after a request establishes the car's state — not that the request caused it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModeAttributionReplayTest {
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
    private fun TestScope.play(fixture: String, sender: Sender = Sender()): Pair<DriveCommandResult, Car> =
        play(ReplayFixture.load(fixture), sender)

    private fun TestScope.play(rows: List<ReplayRow>, sender: Sender = Sender()): Pair<DriveCommandResult, Car> {
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

    @Test fun `TRAFFIC control - Track detail then a resolving 0x1B0 after the request confirms`() = runTest {
        val sender = Sender()
        val (r, car) = play("mode-control-track.txt", sender)
        assertEquals(DriveCommandResult.Success, r)
        assertEquals(DriveMode.TRACK, car.vs.driveMode)
        assertEquals(1, sender.calls)
    }

    @Test fun `SPEC - a 0x1B0 that resolved Sport can't confirm Track through a later 0x420 flip`() = runTest {
        val sender = Sender()
        val (r, _) = play("mode-detail-after-resolve.txt", sender)
        assertNotEquals("confirmed Track on a stamp that certified Sport", DriveCommandResult.Success, r)
        assertEquals("no correction from unresolved evidence", 1, sender.calls)
    }

    @Test fun `SPEC - a detail cached before the request can't disambiguate a post-request 0x1B0`() = runTest {
        val sender = Sender()
        val (r, _) = play("mode-stale-detail.txt", sender)
        assertNotEquals("confirmed Track from pre-request 0x420 detail", DriveCommandResult.Success, r)
        assertEquals(1, sender.calls)
    }

    @Test fun `TRAFFIC - one outlier 0x420 frame can't confirm Sport while the car stays in Track`() = runTest {
        val sender = Sender()
        val (r, car) = play("mode-outlier-420.txt", sender)
        assertEquals("the car ends in Track", DriveMode.TRACK, car.vs.driveMode)
        assertNotEquals("confirmed Sport from a single undocumented 0x420 frame", DriveCommandResult.Success, r)
    }

    // ── rc.8 reliability pass additions ─────────────────────────────────────────────────────────────────────────────

    private fun rows(text: String) = ReplayFixture.parse(text)

    @Test fun `SPEC - a mode change inside the 1 s stamp throttle is stamped - Normal right after Sport confirms`() = runTest {
        val sender = Sender()
        val (r, car) = play(rows("""
            +0      RX t420800000000000011CC
            +300    RX t1B080000000000001000
            +9800   RX t1B080000000000001000   # Sport again: stamp refreshed just before the request
            +10000  REQ NORMAL
            +10400  RX t1B080000000000000000   # Normal 600 ms after the last stamp — the only report after the request
        """.trimIndent()), sender)
        assertEquals(DriveMode.NORMAL, car.vs.driveMode)
        assertEquals(DriveCommandResult.Success, r)
        assertEquals(1, sender.calls)
    }

    @Test fun `TRAFFIC control - Track passing through Sport confirms Track with no correction`() = runTest {
        val sender = Sender()
        val (r, car) = play(rows("""
            +0      RX t1B080000000000000000
            +300    RX t42080000000000001000
            +10000  REQ TRACK
            +11000  RX t420800000000000011CC   # scrolling: Sport first
            +11100  RX t1B080000000000001000
            +13000  RX t420800000000000011CD   # then Track
            +13100  RX t1B080000000000001000
        """.trimIndent()), sender)
        assertEquals(DriveMode.TRACK, car.vs.driveMode)
        assertEquals(DriveCommandResult.Success, r)
        assertEquals("no correction for a mode on the way", 1, sender.calls)
    }

    @Test fun `TRAFFIC control - requesting the mode the car is already in confirms from its re-broadcast`() = runTest {
        val (r, _) = play(rows("""
            +0      RX t420800000000000011CD
            +300    RX t1B080000000000001000
            +10000  REQ TRACK
            +10600  RX t420800000000000011CD
            +10700  RX t1B080000000000001000
        """.trimIndent()))
        assertEquals(DriveCommandResult.Success, r)
    }

    @Test fun `TRAFFIC control - a Track detail with the launch-control bit set still resolves Track`() = runTest {
        val (r, car) = play(rows("""
            +0      RX t1B080000000000000000
            +300    RX t42080000000000001000
            +10000  REQ TRACK
            +12300  RX t420800000000000015CD   # byte 6 0x15 = 0x11 | launch control (bit 2)
            +12500  RX t1B080000000000001000
        """.trimIndent()))
        assertEquals(DriveMode.TRACK, car.vs.driveMode)
        assertEquals(DriveCommandResult.Success, r)
    }
}
