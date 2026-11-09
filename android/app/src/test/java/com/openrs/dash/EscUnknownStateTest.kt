package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.EscCommand
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-rc8-esc-owner-wording (#125, #283). Ford's ESC settings are Normal / Sport / Off. CAN 0x1C0 ESCMode 0 / 1 / 2 map
 * to Normal / Off / Sport (RS_HS.dbc VAL_ 448, CAN-mapped only); 3 is unrecognized (the community DBC's "Launch" has no
 * Ford basis and was never seen). Owner decision: while the ESC state is unknown (no 0x1C0 yet, or raw 3) nothing is
 * sent and the GARAGE buttons are disabled.
 * - D1: before any 0x1C0 frame the state is unknown, not Normal;
 * - D2: no command is sent for an unknown target or from an unknown starting state (the firmware's press plan depends
 *   on the start) — 0 sends;
 * - D3: raw 3 is shown as unknown, never "Launch"; diagnostics keep "unrecognized (raw 3)".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EscUnknownStateTest {
    private class Api : FirmwareCommandSender {
        val sent = mutableListOf<Int>()
        override suspend fun setDriveMode(mode: Int) = Result.success(Unit)
        override suspend fun setEscMode(mode: Int): Result<Unit> { sent += mode; return Result.success(Unit) }
    }
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    /** VehicleState after one production decode + stamp of a 0x1C0 frame with ESCMode [raw] (byte 1 bits 5–4). */
    private fun after1C0(raw: Int, base: VehicleState = VehicleState(isConnected = true)): VehicleState {
        val decoded = CanDecoder.decode(0x1C0, bytes("00%02X000000000000".format(raw shl 4)), base)!!
        return CanDecoder.stampFresh(0x1C0, base, decoded, 1_000L)
    }

    // ── D1 ────────────────────────────────────────────────────────────────────
    @Test fun `D1 before any 0x1C0 frame the ESC state is unknown, not Normal`() {
        val vs = VehicleState(isConnected = true)
        assertEquals(EscStatus.UNKNOWN, vs.escStatus)
        assertEquals(EscStatus.UNKNOWN.label, vs.escStatus.label)
    }

    // ── D2 ────────────────────────────────────────────────────────────────────
    @Test fun `D2 an unknown target is never sent (was escMode 0, a Normal request)`() = runTest {
        val api = Api()
        val o = EscCommand.execute(api, EscStatus.UNKNOWN, { after1C0(2) }, connectionId = { 1 }, clock = { testScheduler.currentTime })
        assertEquals("sends", emptyList<Int>(), api.sent)
        assertFalse("$o", o is EscCommand.Outcome.Confirmed)
    }

    @Test fun `D2 a raw-3 target is never sent`() = runTest {
        val api = Api()
        val o = EscCommand.execute(api, EscStatus.fromInt(3), { after1C0(0) }, connectionId = { 1 }, clock = { testScheduler.currentTime })
        assertEquals("sends", emptyList<Int>(), api.sent)
        assertFalse("$o", o is EscCommand.Outcome.Confirmed)
    }

    @Test fun `D2 nothing is sent while the starting state is unknown (no frame yet)`() = runTest {
        val api = Api()
        val o = EscCommand.execute(api, EscStatus.OFF, { VehicleState(isConnected = true) }, connectionId = { 1 },
            clock = { testScheduler.currentTime })
        assertEquals("sends", emptyList<Int>(), api.sent)
        assertFalse("$o", o is EscCommand.Outcome.Confirmed)
    }

    @Test fun `D2 nothing is sent while the car reports raw 3`() = runTest {
        val api = Api()
        val o = EscCommand.execute(api, EscStatus.PARTIAL, { after1C0(3) }, connectionId = { 1 }, clock = { testScheduler.currentTime })
        assertEquals("sends", emptyList<Int>(), api.sent)
        assertFalse("$o", o is EscCommand.Outcome.Confirmed)
    }

    // ── D3 ────────────────────────────────────────────────────────────────────
    @Test fun `D3 raw 3 reads as unknown to the driver, never Launch`() {
        val vs = after1C0(3)
        assertFalse(vs.escStatus.label, vs.escStatus.label.contains("aunch", ignoreCase = true))
        assertEquals(EscStatus.UNKNOWN.label, vs.escStatus.label)
    }

    @Test fun `D3 the decode trace keeps the raw value - unrecognized (raw 3)`() {
        val trace = CanDecoder.describeDecoded(0x1C0, after1C0(3))
        assertTrue(trace, trace.contains("unrecognized (raw 3)"))
        assertFalse(trace, trace.contains("aunch", ignoreCase = true))
    }

    // ── Ford's names ──────────────────────────────────────────────────────────
    @Test fun `the driver labels are Ford's Normal, Sport and Off (0x1C0 0, 2, 1 - CAN-mapped)`() {
        assertEquals("Normal", after1C0(0).escStatus.label)
        assertEquals("Sport", after1C0(2).escStatus.label)
        assertEquals("Off", after1C0(1).escStatus.label)
    }
}
