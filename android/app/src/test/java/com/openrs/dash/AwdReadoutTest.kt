package com.openrs.dash

import com.openrs.dash.data.AVAILABLE_FIELDS
import com.openrs.dash.data.DashCell
import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.WITHDRAWN_FIELDS
import com.openrs.dash.data.resolveValue
import com.openrs.dash.diagnostics.toJsonObject
import com.openrs.dash.ui.AWD_POLL_STALE_MS
import com.openrs.dash.ui.AppSettings
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.awdDetailRows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #226 follow-up (2026-10-29): the front/rear % is withdrawn everywhere; REQ L / REQ R show from their own freshness
 * (they were hidden behind the retired 0x2C0 "AWD MAX"); MAX is neither shown nor exported; a saved Custom Dashboard
 * rear-% cell is kept and reads unavailable with the reason.
 */
class AwdReadoutTest {
    private val p = UserPrefs()
    private val now = 1_000_000L
    private fun rows(vs: VehicleState, live: Boolean = true, at: Long = now) = awdDetailRows(vs, p, live, at)
    private fun cell(rows: List<List<Triple<String, String, String>>>, label: String) =
        rows.flatten().firstOrNull { it.first == label }?.second

    private val req = VehicleState(isConnected = true, awdReqTorqueL = 412.0, awdReqTorqueR = 388.0, awdMaxTorque = 0.0,
        fieldLastUpdateMs = mapOf("awdReqTorqueL" to now - 5_000, "awdReqTorqueR" to now - 5_000))

    @Test fun `requested torque shows with AWD MAX unavailable — it was gated on MAX before`() {
        val (r, hydraulicsFrom) = rows(req)
        assertEquals("412", cell(r, "REQ L")); assertEquals("388", cell(r, "REQ R"))
        assertNull("the retired MAX is not shown", cell(r, "AWD MAX"))
        assertEquals(2, hydraulicsFrom)
    }

    @Test fun `a reported zero is a reading, a missing reading is no row, stale or offline is a dash`() {
        val zero = req.copy(awdReqTorqueL = 0.0)
        assertEquals("0", cell(rows(zero).first, "REQ L"))
        val none = VehicleState(isConnected = true)
        assertNull(cell(rows(none).first, "REQ L"))                          // never reported (DID unsupported): no row
        assertEquals(1, rows(none).second)
        assertEquals("—", cell(rows(req, live = false).first, "REQ L"))      // disconnected
        assertEquals("—", cell(rows(req, at = now + AWD_POLL_STALE_MS).first, "REQ R"))   // older than the AWD poll rule
        val onlyR = VehicleState(isConnected = true, awdReqTorqueR = 50.0, fieldLastUpdateMs = mapOf("awdReqTorqueR" to now))
        assertEquals("—", cell(rows(onlyR).first, "REQ L")); assertEquals("50", cell(rows(onlyR).first, "REQ R"))
    }

    @Test fun `no front or rear percentage anywhere in the THERMAL readouts`() {
        val labels = rows(req).first.flatten().map { it.first }
        assertTrue(labels.none { it.contains("BIAS") || it.contains("F/R SPLIT") || it.contains("REAR %") })
        assertTrue("L vs R of the same rear signal stays", "REAR L/R" in labels)
    }

    @Test fun `diagnostic JSON omits the retired AWD MAX instead of exporting a measured 0`() {
        val vs = req.toJsonObject()
        assertFalse(vs.has("awdMaxTorque"))
        assertTrue(vs.has("awdLeftTorque"))
    }

    @Test fun `a saved rear-percentage dashboard cell is kept and reads unavailable with the reason`() {
        assertTrue(AVAILABLE_FIELDS.none { it.fieldKey == "rearTorquePct" })   // can't be added any more
        val saved = """{"name":"Custom","cells":[{"fieldKey":"rpm","displayType":"hero","label":"RPM"},""" +
            """{"fieldKey":"rearTorquePct","displayType":"number","label":"REAR BIAS"}]}"""
        val layout = AppSettings.parseCustomDash(saved)!!
        assertEquals(listOf("rpm", "rearTorquePct"), layout.cells.map { it.fieldKey })   // configuration not deleted
        val vs = VehicleState(isConnected = true, torqueAtTrans = 100.0, awdLeftTorque = 400.0, awdRightTorque = 400.0)
        assertEquals("— —", resolveValue(vs, p, DashCell("rearTorquePct", "number", "REAR BIAS"), now).first)
        assertNotNull(WITHDRAWN_FIELDS["rearTorquePct"])
    }
}
