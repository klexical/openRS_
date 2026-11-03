package com.openrs.dash

import com.openrs.dash.can.DeviceStatus
import com.openrs.dash.diagnostics.AdapterStatusRecord
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #233 follow-up: one export reads the adapter status ONCE (text and JSON from the same snapshot), and a session's
 * adapter status only accepts writes carrying its own token — an older connection's late reply can't land on a newer
 * (e.g. Bluetooth) session. Deterministic: the "reply mid-export" is applied between the snapshot and the builders.
 */
class AdapterStatusSnapshotTest {
    private fun status() = DeviceStatus("4.45", "WiCAN-OBD-PRO", "aaaaaaaaaaaa", "500K", "normal", "slcan", "disable",
        14.9, "enable", "13.1", "AP")

    @Test fun `a reply arriving mid-export never gives one export two different adapter states`() {
        val s = DiagnosticLogger.sessionStart("192.168.0.10", 35000, prefs = null, transport = "TCP SLCAN")
        DiagnosticLogger.adapterStatusRequested(s)
        val snap = DiagnosticLogger.snapshotAdapterStatus()                 // the exporter reads once …
        DiagnosticLogger.adapterStatusFetched(s, status())                  // … the reply lands while it writes …
        val (text, jsonText) = DiagnosticReportBuilder.buildSummaryAndDetail("t", snap)
        val json = JSONObject(jsonText)
        assertEquals("text and JSON of one export disagree", text.contains("Device FW"), json.has("adapterStatus"))
        assertTrue(text.contains("no answer when exported"))
        assertFalse("no-answer line beside populated fields", text.contains("Device ID"))
        assertEquals("NO_ANSWER", json.getJSONObject("adapterStatusSource").getString("state"))

        val (later, laterJson) = DiagnosticReportBuilder.buildSummaryAndDetail("t")   // next export sees the reply
        assertTrue(later.contains("Device FW: v4.45"))
        assertEquals("FETCHED", JSONObject(laterJson).getJSONObject("adapterStatusSource").getString("state"))
    }

    @Test fun `an older connection's late status never lands on a Bluetooth session`() {
        val wifi = DiagnosticLogger.sessionStart("192.168.0.10", 35000, prefs = null, transport = "TCP SLCAN")
        DiagnosticLogger.adapterStatusRequested(wifi)
        val ble = DiagnosticLogger.sessionStart("AA:BB", 0, prefs = null, transport = "Bluetooth",
            adapterStatusReason = AdapterStatusRecord.BLUETOOTH)
        DiagnosticLogger.adapterStatusFetched(wifi, status())               // the Wi-Fi session's late reply
        DiagnosticLogger.adapterStatusFailed(wifi)
        assertFalse("stale Wi-Fi status on the Bluetooth session", DiagnosticLogger.adapterStatus is AdapterStatusRecord.Fetched)
        assertEquals(AdapterStatusRecord.NotRequested(AdapterStatusRecord.BLUETOOTH), DiagnosticLogger.adapterStatus)
        DiagnosticLogger.adapterStatusFailed(ble)                           // the current session's own write still applies
        assertEquals(AdapterStatusRecord.Failed, DiagnosticLogger.adapterStatus)
    }
}
