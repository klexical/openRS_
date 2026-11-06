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
 * byte 6 is not used; a resolved mode change is stamped at once (not held by the 1 s stamp throttle). Corroboration
 * (9d area 3 review F1): no single frame confirms or corrects — two agreeing 0x1B0 reports after the request, and for
 * Sport / Track two agreeing 0x420 details before them. Control fixtures carry a realistic cadence (0x420 ~600 ms,
 * repeated 0x1B0) for that reason.
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
            +10400  RX t1B080000000000000000   # Normal 600 ms after the last stamp (inside the 1 s throttle)
            +10450  RX t1B080000000000000000   # its second report: both after the request (corroborated, #252)
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
            +13600  RX t420800000000000011CD
            +13700  RX t1B080000000000001000
            +13800  RX t1B080000000000001000
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
            +10600  RX t420800000000000011CD   # re-broadcast: 0x420 ~600 ms, 0x1B0 every 100 ms (synthetic cadence)
            +10700  RX t1B080000000000001000
            +10800  RX t1B080000000000001000
            +10900  RX t1B080000000000001000
            +11000  RX t1B080000000000001000
            +11100  RX t1B080000000000001000
            +11200  RX t420800000000000011CD
            +11200  RX t1B080000000000001000
            +11300  RX t1B080000000000001000
            +11400  RX t1B080000000000001000
            +11500  RX t1B080000000000001000
            +11600  RX t1B080000000000001000
            +11700  RX t1B080000000000001000
            +11800  RX t420800000000000011CD
            +11800  RX t1B080000000000001000
            +11900  RX t1B080000000000001000
            +12000  RX t1B080000000000001000
            +12100  RX t1B080000000000001000
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
            +12900  RX t420800000000000015CD
            +13000  RX t1B080000000000001000
            +13100  RX t1B080000000000001000
        """.trimIndent()))
        assertEquals(DriveMode.TRACK, car.vs.driveMode)
        assertEquals(DriveCommandResult.Success, r)
    }

    // ── Corroboration (9d area 3 review F1): a single well-formed outlier never confirms or corrects ─────────────────

    @Test fun `SPEC - one documented but wrong 0x420 can't confirm Sport while the car stays in Track`() = runTest {
        // 9d's probe: byte 6 0x11 is documented, bit 0 says Sport — a single frame, then Track detail again
        val sender = Sender()
        val (r, car) = play(rows("""
            +0      RX t420800000000000011CD
            +300    RX t1B080000000000001000
            +10000  REQ SPORT
            +12900  RX t420800000000000011CC
            +13000  RX t1B080000000000001000
            +13500  RX t420800000000000011CD
            +13600  RX t1B080000000000001000
            +14100  RX t420800000000000011CD
            +14200  RX t1B080000000000001000
        """.trimIndent()), sender)
        assertEquals(DriveMode.TRACK, car.vs.driveMode)
        assertNotEquals("confirmed Sport from one 0x420 frame", DriveCommandResult.Success, r)
        assertEquals(1, sender.calls)
    }

    @Test fun `TRAFFIC - a single 0x1B0 blip to the target can't confirm Drift`() = runTest {
        val sender = Sender()
        val (r, car) = play(rows("""
            +0      RX t420800000000000011CC
            +300    RX t1B080000000000001000
            +10000  REQ DRIFT
            +12000  RX t1B080000000000002000   # one Drift report
            +12400  RX t1B080000000000001000   # back to Sport
            +13000  RX t420800000000000011CC
            +13100  RX t1B080000000000001000
        """.trimIndent()), sender)
        assertEquals(DriveMode.SPORT, car.vs.driveMode)
        assertNotEquals("confirmed Drift from one 0x1B0 frame", DriveCommandResult.Success, r)
        assertEquals(1, sender.calls)
    }

    @Test fun `TRAFFIC - a single wrong-mode blip at the timeout triggers no correction`() = runTest {
        val sender = Sender()
        val (r, _) = play(rows("""
            +0      RX t1B080000000000000000
            +300    RX t42080000000000001000
            +10000  REQ SPORT
            +26500  RX t1B080000000000002000   # one Drift report just before the window ends, nothing after
        """.trimIndent()), sender)
        assertNotEquals(DriveCommandResult.Success, r)
        assertEquals("no corrective command from a single frame", 1, sender.calls)
    }
}
