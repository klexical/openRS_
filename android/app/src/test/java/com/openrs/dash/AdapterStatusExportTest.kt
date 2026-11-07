package com.openrs.dash

import com.openrs.dash.can.DeviceStatus
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The diagnostic export's adapter status (#233): only the current session's `/check_status`, labelled with how it was
 * obtained; a Bluetooth session, another adapter or a failed refresh never shows an earlier session's fields, and the
 * live firmware handshake stays separate. Production path: DiagnosticLogger session state → DiagnosticReportBuilder
 * text + JSON, as the export writes them.
 */
class AdapterStatusExportTest {

    private fun status(fw: String, id: String, ble: String) = DeviceStatus(
        firmwareVersion = fw, hardwareVersion = "WiCAN-OBD-PRO", deviceId = id, canDatarate = "500K", canMode = "normal",
        protocol = "slcan", bleStatus = ble, obdPortVoltage = 14.9, sleepStatus = "enable", sleepVolt = "13.1", wifiMode = "AP"
    )

    private fun wifiSession(host: String): Long =
        DiagnosticLogger.sessionStart(host, 35000, prefs = null, transport = "TCP SLCAN ($host:35000)")

    private fun text() = DiagnosticReportBuilder.buildSummaryText("t")
    private fun json() = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))

    private fun assertNoStatusFields(text: String, json: JSONObject, id: String) {
        assertFalse("Device FW in text", text.contains("Device FW"))
        assertFalse("device ID in text", text.contains(id))
        assertFalse("BLE line in text", text.contains("BLE      :"))
        assertFalse("adapterStatus in JSON", json.has("adapterStatus"))
        assertFalse("device ID in JSON", json.toString().contains(id))
    }

    @Test fun `Wi-Fi status then a Bluetooth session on the same adapter exports no stale status`() {
        val a = wifiSession("192.168.0.10")
        DiagnosticLogger.adapterStatusFetched(a, status("4.45", "aaaaaaaaaaaa", "disable"))
        DiagnosticLogger.sessionEnd()
        DiagnosticLogger.sessionStart("Bluetooth", 0, prefs = null, transport = "Bluetooth (MeatPi Pro)",
            adapterStatusReason = com.openrs.dash.diagnostics.AdapterStatusRecord.BLUETOOTH)
        DiagnosticLogger.firmwareVersion = "openRS_ PRO v1.2-rc.1"      // live handshake on the Bluetooth session

        val text = text(); val json = json()
        assertNoStatusFields(text, json, "aaaaaaaaaaaa")
        assertTrue(text.contains("Transport: Bluetooth (MeatPi Pro)"))
        assertTrue(text.contains("Status   : not available — Bluetooth session"))
        assertTrue("handshake stays authoritative", text.contains("Firmware : openRS_ PRO v1.2-rc.1"))
        val src = json.getJSONObject("adapterStatusSource")
        assertEquals("NOT_REQUESTED", src.getString("state"))
        assertTrue(src.getString("detail").startsWith("Bluetooth session"))
        assertEquals("openRS_ PRO v1.2-rc.1", json.getJSONObject("meta").getString("firmware"))
    }

    @Test fun `adapter A's status never shows on adapter B whose status request failed`() {
        val a = wifiSession("192.168.0.10")
        DiagnosticLogger.adapterStatusFetched(a, status("4.45", "aaaaaaaaaaaa", "disable"))
        DiagnosticLogger.sessionEnd()
        val b = wifiSession("192.168.80.1")
        DiagnosticLogger.adapterStatusRequested(b)
        DiagnosticLogger.adapterStatusFailed(b)

        val text = text(); val json = json()
        assertNoStatusFields(text, json, "aaaaaaaaaaaa")
        assertTrue(text.contains("Status   : not available — /check_status request failed this session"))
        assertEquals("FAILED", json.getJSONObject("adapterStatusSource").getString("state"))
    }

    @Test fun `a failed refresh in the session says so and invents no firmware version`() {
        val a = wifiSession("192.168.0.10")
        DiagnosticLogger.adapterStatusRequested(a)
        val pending = text()
        assertTrue(pending.contains("/check_status requested, no answer when exported"))
        DiagnosticLogger.adapterStatusFailed(a)

        val text = text(); val json = json()
        assertFalse(text.contains("Device FW"))
        assertFalse(json.has("adapterStatus"))
        assertEquals("FAILED", json.getJSONObject("adapterStatusSource").getString("state"))
        assertEquals("WiCAN stock", json.getJSONObject("meta").getString("firmware"))   // handshake default, not a version
    }

    @Test fun `a status fetched this session is exported with where and when it came from`() {
        val a = wifiSession("192.168.0.10")
        DiagnosticLogger.adapterStatusFetched(a, status("4.45", "bbbbbbbbbbbb", "disable"), DiagnosticLogger.sessionStartMs + 2_000L)

        val text = text(); val json = json()
        assertTrue(text.contains("Status   : from /check_status over Wi-Fi at +00:02 this session"))
        assertTrue(text.contains("Device FW: v4.45"))
        assertTrue(text.contains("BLE      : disable"))
        assertEquals("4.45", json.getJSONObject("adapterStatus").getString("firmwareVersion"))
        val src = json.getJSONObject("adapterStatusSource")
        assertEquals("FETCHED", src.getString("state"))
        assertEquals(2_000L, src.getLong("sessionOffsetMs"))
        assertFalse("check_status never replaces the handshake label", json.getJSONObject("meta").getString("firmware").contains("4.45"))
    }

    @Test fun `a new session starts with no status at all`() {
        val a = wifiSession("192.168.0.10")
        DiagnosticLogger.adapterStatusFetched(a, status("4.45", "cccccccccccc", "enable"))
        wifiSession("192.168.0.10")                                       // reconnect = new session, nothing fetched yet

        val text = text(); val json = json()
        assertNoStatusFields(text, json, "cccccccccccc")
        assertTrue(text.contains("Status   : not available — no status request in this session"))
    }
}
