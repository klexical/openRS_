package com.openrs.dash

import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.awdDetailRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * AWD demanded pressure (0x1E92, raw u16) and pump current (0x1E93, byte × 0.1 A) through the production path (#226):
 * the parser decodes a reply into a copy of the live-state snapshot and stamps the field, CanDataService merges it with
 * [ObdMerge.merge]. Both decodes can produce 0, so a stamped 0 must replace the previous value, and a reply for anything
 * else — or one parsed from an older snapshot — must not put an old value back. Same rule as REQ L / R
 * ([AwdReqTorqueMergeTest]). THERMAL's PUMP cell ([awdDetailRows]) still hides 0 A by value ("—"); what changes there is
 * that a pump that stopped no longer shows its last positive current. Neither DID has answered on a tested car (NRC
 * 0x31), so their meaning and scale are unverified.
 */
class AwdDemandPumpMergeTest {
    private fun reply(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun pressure(raw: Int) = reply(0x05, 0x62, 0x1E, 0x92, raw shr 8, raw and 0xFF)
    private fun pump(raw: Int) = reply(0x04, 0x62, 0x1E, 0x93, raw)
    private val rduTemp = reply(0x04, 0x62, 0x1E, 0x8A, 0x5A)

    /** Parse [frame] against [snapshot] (what SlcanConnection's getCurrentState returned), merge into [current]. */
    private fun apply(current: VehicleState, frame: ByteArray, snapshot: VehicleState = current): VehicleState {
        var merged = current
        ObdResponseParser.parseAwdResponse(frame, snapshot) { merged = ObdMerge.merge(current, it) }
        return merged
    }

    private val connected = VehicleState(isConnected = true)

    private fun pumpShown(vs: VehicleState): String? =
        awdDetailRows(vs, UserPrefs(), live = true, nowMs = System.currentTimeMillis()).first.flatten()
            .firstOrNull { it.first == "PUMP" }?.second

    @Test fun `THERMAL PUMP stops showing the last positive current once the pump reads 0 A`() {
        // Clutch current reported, so the actuator row is present either way
        var vs = apply(connected.copy(awdClutchCurL = 0.4, awdClutchCurR = 0.3), pump(25))
        assertEquals("2.5", pumpShown(vs))
        vs = apply(vs, pump(0))
        assertEquals("—", pumpShown(vs))
    }

    @Test fun `a decoded zero replaces the previous reading`() {
        var vs = apply(apply(connected, pressure(0x0123)), pump(25))
        assertEquals(291.0, vs.awdDmdPressure, 0.0); assertEquals(2.5, vs.awdPumpCurrent, 1e-9)

        val before = vs.fieldLastUpdateMs
        Thread.sleep(2)
        vs = apply(vs, pressure(0))
        assertEquals(0.0, vs.awdDmdPressure, 0.0)
        assertEquals(2.5, vs.awdPumpCurrent, 1e-9)
        assertTrue(vs.fieldLastUpdateMs.getValue("awdDmdPressure") > before.getValue("awdDmdPressure"))

        vs = apply(vs, pump(0))
        assertEquals(0.0, vs.awdPumpCurrent, 0.0)
        assertEquals(0.0, vs.awdDmdPressure, 0.0)
    }

    @Test fun `an unrelated reply leaves both values and stamps unchanged`() {
        val vs = apply(apply(connected, pressure(0x0123)), pump(0))
        val after = apply(vs, rduTemp)
        assertEquals(291.0, after.awdDmdPressure, 0.0); assertEquals(0.0, after.awdPumpCurrent, 0.0)
        assertEquals(vs.fieldLastUpdateMs["awdDmdPressure"], after.fieldLastUpdateMs["awdDmdPressure"])
        assertEquals(vs.fieldLastUpdateMs["awdPumpCurrent"], after.fieldLastUpdateMs["awdPumpCurrent"])
    }

    @Test fun `a reply decoded from an older snapshot never reverts a newer zero`() {
        // Snapshot taken while both read positive; pressure 0 and pump 0 are merged before this other reply's merge runs.
        val now = System.currentTimeMillis()
        val old = VehicleState(isConnected = true, awdDmdPressure = 291.0, awdPumpCurrent = 2.5,
            fieldLastUpdateMs = mapOf("awdDmdPressure" to now - 30_000, "awdPumpCurrent" to now - 30_000))
        val current = old.copy(awdDmdPressure = 0.0, awdPumpCurrent = 0.0,
            fieldLastUpdateMs = old.fieldLastUpdateMs + mapOf("awdDmdPressure" to now - 1_000, "awdPumpCurrent" to now - 1_000))
        val after = apply(current, rduTemp, snapshot = old)
        assertEquals(0.0, after.awdDmdPressure, 0.0)
        assertEquals(0.0, after.awdPumpCurrent, 0.0)
        assertEquals(current.fieldLastUpdateMs["awdDmdPressure"], after.fieldLastUpdateMs["awdDmdPressure"])
    }

    @Test fun `zero then positive is taken, and never received stays unstamped`() {
        val vs = apply(connected, rduTemp)
        assertNull(vs.fieldLastUpdateMs["awdDmdPressure"]); assertNull(vs.fieldLastUpdateMs["awdPumpCurrent"])
        val up = apply(apply(vs, pump(0)), pump(12))
        assertEquals(1.2, up.awdPumpCurrent, 1e-9)
        assertNull(up.fieldLastUpdateMs["awdDmdPressure"])
    }
}
