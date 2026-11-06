package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.can.SlcanParser
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

/**
 * rc.8 reliability pass, #252 — 9d's area 3 follow-up: the drive-mode corroboration rule relies on CAN-only stamp keys
 * being ABSENT after a change (the previous run's tag, the run's "previous report", the detail key / pair). An OBD reply
 * is parsed against a snapshot and merged later (CanDataService posts merges to Main); the stamp merge takes the union of
 * keys, so a reply parsed before a mode change brought the removed keys back. Drive-mode provenance keys now always come
 * from the live state — OBD never stamps them. Production CanDecoder.decode + stampFresh and ObdMerge.merge, fixed clock.
 */
class ModeProvenanceMergeTest {
    @Before fun session() { CanDecoder.resetSessionState() }

    private fun frame(vs: VehicleState, line: String, now: Long): VehicleState {
        val (id, data) = SlcanParser.parse(line)!!
        val decoded = CanDecoder.decode(id, data, vs) ?: return vs
        return if (decoded === vs) vs else CanDecoder.stampFresh(id, vs, decoded, now)
    }

    @Test fun `an OBD reply parsed before a mode change doesn't bring the old run's keys back`() {
        var vs = VehicleState(isConnected = true)
        vs = frame(vs, "t1B080000000000002000", 1_000)        // Drift run: two reports → run tag + "previous report"
        vs = frame(vs, "t1B080000000000002000", 1_050)
        val snapshot = vs                                       // an OBD reply is parsed against this snapshot …
        vs = frame(vs, "t1B080000000000000000", 2_000)        // … the car changes to Normal (one report: no "previous" yet)
        assertEquals(DriveMode.NORMAL, vs.driveMode)
        var reply: VehicleState? = null
        ObdResponseParser.parsePcmResponse(byteArrayOf(0x05, 0x62, 0x03, 0x04, 0x77, 0xAE.toByte(), 0, 0), snapshot) { reply = it }
        val merged = ObdMerge.merge(vs, reply!!)                // … and merged after the change
        val keys = merged.fieldLastUpdateMs.keys
        assertFalse(keys.toString(), CanDecoder.modeRunTag(DriveMode.DRIFT) in keys)
        assertFalse("the change's single report has no corroborating previous report: $keys", CanDecoder.MODE_RUN_PREV_TAG in keys)
        assertEquals(vs.fieldLastUpdateMs.filterKeys { it.startsWith("driveMode") },
            merged.fieldLastUpdateMs.filterKeys { it.startsWith("driveMode") })
        assertEquals("the reply's own value and stamp still merge", 0x77AE / 2048.0, merged.batteryVoltage, 0.0)
    }

    @Test fun `control - an OBD reply parsed after the change merges without touching drive-mode keys`() {
        var vs = VehicleState(isConnected = true)
        vs = frame(vs, "t1B080000000000000000", 1_000)
        vs = frame(vs, "t1B080000000000000000", 1_050)
        var reply: VehicleState? = null
        ObdResponseParser.parsePcmResponse(byteArrayOf(0x05, 0x62, 0x03, 0x04, 0x77, 0xAE.toByte(), 0, 0), vs) { reply = it }
        val merged = ObdMerge.merge(vs, reply!!)
        assertEquals(vs.fieldLastUpdateMs.filterKeys { it.startsWith("driveMode") },
            merged.fieldLastUpdateMs.filterKeys { it.startsWith("driveMode") })
    }
}
