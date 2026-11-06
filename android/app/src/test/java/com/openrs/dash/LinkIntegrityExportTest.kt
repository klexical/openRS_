package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * rc.8 reliability pass, #253 — the export's link-integrity diagnostics: the LINK INTEGRITY summary section and the
 * additive `linkIntegrity` / `vehicleState.fieldAgesMs` JSON (old fields unchanged). Counts only — never a raw line, an
 * identifier or an absolute time. The counters are fed by the production paths (BleWriteOutcomeTest, SlcanFramingModeTest,
 * Mode22LengthTest, ProberAttributionTest assert them there); here: formatting, session reset, ages and mode provenance.
 */
class LinkIntegrityExportTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun summary() = DiagnosticReportBuilder.buildSummaryText("t")
    private fun json() = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))

    @Test fun `counters appear in the summary and the JSON block, and reset with the session`() {
        repeat(3) { DiagnosticLogger.countWrite(DiagnosticLogger.WRITE_SENT) }
        repeat(2) { DiagnosticLogger.countWrite(DiagnosticLogger.WRITE_POSSIBLY_DELIVERED) }
        DiagnosticLogger.countWrite(DiagnosticLogger.WRITE_NOT_SENT)
        DiagnosticLogger.countWrite(DiagnosticLogger.WRITE_UNCERTAIN)
        DiagnosticLogger.framingDecided(timestamped = true)
        DiagnosticLogger.countRefusedFrameLine(otherShape = true)
        DiagnosticLogger.countRefusedFrameLine(otherShape = false)
        DiagnosticLogger.countRefusedReply()
        repeat(4) { DiagnosticLogger.countAttributedNrc() }
        DiagnosticLogger.countUnattributedNrc()

        val text = summary()
        assertTrue(text, text.contains("─── LINK INTEGRITY"))
        assertTrue(text, text.contains("3 sent · 2 possibly delivered (GATT 13) · 1 not sent · 1 delivery unknown · 0 other failures"))
        assertTrue(text, text.contains("Framing  : timestamped (Z1 set on the adapter) · 2 frame line(s) refused (1 of the other shape)"))
        assertTrue(text, text.contains("Replies  : 1 Mode 22 reply(ies) refused"))
        assertTrue(text, text.contains("NRC      : 4 attributed · 1 unattributed (PCM)"))
        assertFalse("the summary's Firmware line is what Sapphire parses — untouched", text.contains("Firmware: timestamped"))

        val li = json().getJSONObject("linkIntegrity")
        assertEquals(3, li.getJSONObject("writes").getInt("sent"))
        assertEquals(2, li.getJSONObject("writes").getInt("possiblyDelivered"))
        assertEquals(1, li.getJSONObject("writes").getInt("notSent"))
        assertEquals(1, li.getJSONObject("writes").getInt("deliveryUnknown"))
        assertEquals("timestamped", li.getString("framing"))
        assertEquals(2, li.getInt("refusedFrameLines"))
        assertEquals(1, li.getInt("refusedOtherShape"))
        assertEquals(1, li.getInt("refusedReplies"))
        assertEquals(4, li.getInt("nrcAttributed"))
        assertEquals(1, li.getInt("nrcUnattributed"))
        assertEquals("the existing field is unchanged", 1, json().getInt("nrcUnattributedTotal"))

        DiagnosticLogger.sessionStart("next", 0, prefs = null)
        val fresh = json().getJSONObject("linkIntegrity")
        assertEquals(0, fresh.getJSONObject("writes").getInt("sent"))
        assertEquals("undecided", fresh.getString("framing"))
        assertTrue(summary().contains("Framing  : not decided · 0 frame line(s) refused"))
    }

    @Test fun `the snapshot says what the drive mode rests on and how old the battery reading is`() {
        val now = System.currentTimeMillis()
        DiagnosticLogger.lastVehicleState = VehicleState(isConnected = true, driveMode = DriveMode.TRACK, batteryVoltage = 14.96,
            fieldLastUpdateMs = mapOf("driveMode" to now - 3_400, CanDecoder.MODE_RESOLVED_DETAIL_TAG to now - 4_400,
                CanDecoder.MODE_DETAIL_TAG to now - 1_400, "batteryVoltage" to now - 12_400))
        val text = summary()
        assertTrue(text, text.contains("Drive Mode : Track  (0x1B0 3 s ago, its 0x420 detail 4 s ago)"))
        assertTrue(text, text.contains("Battery    : 14.96 V  (PCM 0x0304 as reported; last reply 12 s ago)"))
        val ages = json().getJSONObject("vehicleState").getJSONObject("fieldAgesMs")
        assertTrue(ages.toString(), ages.getLong("batteryVoltage") in 12_400L..13_400L)
        assertTrue(ages.toString(), ages.getLong("driveMode") in 3_400L..4_400L)
        assertTrue(ages.toString(), ages.has(CanDecoder.MODE_DETAIL_TAG) && ages.has(CanDecoder.MODE_RESOLVED_DETAIL_TAG))
        assertEquals("existing field unchanged", 14.96, json().getJSONObject("vehicleState").getDouble("batteryVoltage"), 0.0)
    }

    @Test fun `control - no report yet - no provenance text, no battery reply`() {
        DiagnosticLogger.lastVehicleState = VehicleState(isConnected = true)
        val text = summary()
        assertTrue(text, text.contains("Drive Mode : ${DriveMode.UNKNOWN.label}\n") || text.contains("Drive Mode : ${VehicleState().driveMode.label}\n"))
        assertTrue(text, text.contains("last reply never"))
    }
}
