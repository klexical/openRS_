package com.openrs.dash

import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.AWD_POLL_STALE_MS
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.awdDetailRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * REQ L / REQ R (AWD 0x1E90 / 0x1E91) through the production path (#226): the parser decodes a reply into a copy of the
 * live-state snapshot and stamps the field, CanDataService merges it with [ObdMerge.merge], THERMAL reads it with
 * [awdDetailRows]. A genuine 0 Nm must replace the previous reading; a reply for anything else must not touch it.
 */
class AwdReqTorqueMergeTest {
    private val p = UserPrefs()
    private fun reply(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun reqL(nm: Int) = reply(0x05, 0x62, 0x1E, 0x90, nm shr 8, nm and 0xFF)
    private fun reqR(nm: Int) = reply(0x05, 0x62, 0x1E, 0x91, nm shr 8, nm and 0xFF)
    private val rduTemp = reply(0x04, 0x62, 0x1E, 0x8A, 0x5A)

    /** Parse [frame] against [snapshot] (what SlcanConnection's getCurrentState returned), merge into [current]. */
    private fun apply(current: VehicleState, frame: ByteArray, snapshot: VehicleState = current): VehicleState {
        var merged = current
        ObdResponseParser.parseAwdResponse(frame, snapshot) { merged = ObdMerge.merge(current, it) }
        return merged
    }

    private fun shown(vs: VehicleState, label: String, live: Boolean = true): String? =
        awdDetailRows(vs, p, live, System.currentTimeMillis()).first.flatten().firstOrNull { it.first == label }?.second

    private val connected = VehicleState(isConnected = true)

    @Test fun `positive then zero — each side independently shows the zero`() {
        var vs = apply(apply(connected, reqL(412)), reqR(388))
        assertEquals("412", shown(vs, "REQ L")); assertEquals("388", shown(vs, "REQ R"))

        vs = apply(vs, reqL(0))
        assertEquals(0.0, vs.awdReqTorqueL, 0.0)
        assertEquals("0", shown(vs, "REQ L"))
        assertEquals("388", shown(vs, "REQ R"))

        vs = apply(vs, reqR(0))
        assertEquals("0", shown(vs, "REQ R"))
        assertEquals("0", shown(vs, "REQ L"))
    }

    @Test fun `zero then positive on one side leaves the other side's zero`() {
        var vs = apply(apply(connected, reqL(0)), reqR(0))
        vs = apply(vs, reqR(120))
        assertEquals("0", shown(vs, "REQ L")); assertEquals("120", shown(vs, "REQ R"))
    }

    @Test fun `an unrelated reply leaves both values and stamps unchanged`() {
        val vs = apply(apply(connected, reqL(412)), reqR(0))
        val after = apply(vs, rduTemp)
        assertEquals(412.0, after.awdReqTorqueL, 0.0); assertEquals(0.0, after.awdReqTorqueR, 0.0)
        assertEquals(vs.fieldLastUpdateMs["awdReqTorqueL"], after.fieldLastUpdateMs["awdReqTorqueL"])
        assertEquals(vs.fieldLastUpdateMs["awdReqTorqueR"], after.fieldLastUpdateMs["awdReqTorqueR"])
    }

    @Test fun `a reply decoded from an older snapshot never reverts a newer reading`() {
        // Snapshot taken while L was 412; L = 0 is merged before this R reply's (posted) merge runs.
        val old = VehicleState(isConnected = true, awdReqTorqueL = 412.0, awdReqTorqueR = 388.0,
            fieldLastUpdateMs = mapOf("awdReqTorqueL" to System.currentTimeMillis() - 30_000,
                "awdReqTorqueR" to System.currentTimeMillis() - 30_000))
        val current = old.copy(awdReqTorqueL = 0.0,
            fieldLastUpdateMs = old.fieldLastUpdateMs + ("awdReqTorqueL" to System.currentTimeMillis() - 1_000))
        val after = apply(current, reqR(200), snapshot = old)
        assertEquals(0.0, after.awdReqTorqueL, 0.0)
        assertEquals(current.fieldLastUpdateMs["awdReqTorqueL"], after.fieldLastUpdateMs["awdReqTorqueL"])
        assertEquals(200.0, after.awdReqTorqueR, 0.0)
        assertEquals("0", shown(after, "REQ L")); assertEquals("200", shown(after, "REQ R"))
    }

    @Test fun `never received stays unavailable`() {
        val vs = apply(connected, rduTemp)
        assertNull(vs.fieldLastUpdateMs["awdReqTorqueL"]); assertNull(vs.fieldLastUpdateMs["awdReqTorqueR"])
        assertNull("no REQ row while neither side has reported", shown(vs, "REQ L"))
        val onlyL = apply(vs, reqL(0))
        assertEquals("0", shown(onlyL, "REQ L"))
        assertEquals("—", shown(onlyL, "REQ R"))               // default 0.0 is not a reading
    }

    @Test fun `stale or disconnected shows a dash, then recovers with a genuine zero`() {
        val longAgo = System.currentTimeMillis() - AWD_POLL_STALE_MS - 5_000
        val stale = VehicleState(isConnected = true, awdReqTorqueL = 412.0, awdReqTorqueR = 388.0,
            fieldLastUpdateMs = mapOf("awdReqTorqueL" to longAgo, "awdReqTorqueR" to longAgo))
        assertEquals("—", shown(stale, "REQ L")); assertEquals("—", shown(stale, "REQ R"))

        val fresh = apply(apply(connected, reqL(412)), reqR(388))
        assertEquals("—", shown(fresh, "REQ L", live = false))  // disconnected
        assertEquals("—", shown(fresh.copy(isConnected = false), "REQ R", live = false))

        val recovered = apply(apply(stale, reqL(0)), reqR(0))
        assertEquals("0", shown(recovered, "REQ L")); assertEquals("0", shown(recovered, "REQ R"))
    }
}
