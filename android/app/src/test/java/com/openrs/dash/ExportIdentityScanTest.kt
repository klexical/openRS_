package com.openrs.dash

import android.bluetooth.BluetoothAdapter
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.DeviceStatus
import com.openrs.dash.can.EscCommand
import com.openrs.dash.data.EscStatus
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.CanDataService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.util.zip.ZipFile

/**
 * #263: a full export and the share text carry no adapter identifier. The real CanDataService starts a real session with a
 * Bluetooth device saved; the real exporter writes the ZIP; every member is scanned. Synthetic identifiers only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class ExportIdentityScanTest {
    private val ctx: Context get() = ApplicationProvider.getApplicationContext()
    private companion object {
        const val MAC = "DA:7A:00:11:22:33"
        const val NAME = "Garage Adapter Seven"            // not shaped like a WiCAN name: only the source can keep it out
        const val DEVICE_ID = "c0ffee42beef"
    }
    private val forbidden = listOf(MAC, MAC.lowercase(), MAC.replace(":", ""), MAC.replace(":", "-"), NAME, "Garage Adapter", DEVICE_ID)

    private fun settings(method: String) {
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", method)
            .putString("adapter_type", "MEATPI_PRO")
            .commit()
        com.openrs.dash.ui.AppSettings.saveBleDevice(ctx, MAC, NAME)
    }

    private fun zipText(): Map<String, String> {
        val dir = File(ctx.filesDir, "diagnostics")
        val zip = dir.listFiles { f -> f.name.startsWith("openrs_diag_") }.orEmpty().maxByOrNull { it.lastModified() }!!
        return ZipFile(zip).use { z -> z.entries().asSequence().associate { it.name to z.getInputStream(it).readBytes().toString(Charsets.ISO_8859_1) } }
    }

    private fun assertClean(texts: Map<String, String>) {
        val hits = texts.flatMap { (name, text) -> forbidden.filter { text.contains(it, ignoreCase = true) }.map { "$name: $it" } }
        assertEquals(hits.toString(), emptyList<String>(), hits)
    }

    @org.junit.After fun destroyServices() { TestServices.destroyAll() }

    @Before fun bluetoothOn() { Shadows.shadowOf(BluetoothAdapter.getDefaultAdapter()).setEnabled(true) }

    @Test fun `a Bluetooth session's export and share text hold no MAC and no adapter name`() = runBlocking {
        settings("BLUETOOTH")
        val service = TestServices.create()
        service.startConnection()
        assertTrue("the session started", DiagnosticLogger.sessionEvents.any { it.type == "SESSION" })
        DiagnosticExporter.export(ctx)
        val texts = zipText()
        assertTrue(texts.keys.toString(), texts.keys.any { it.startsWith("diagnostic_summary_") } && texts.keys.any { it.startsWith("diagnostic_detail_") })
        assertClean(texts + ("share text" to DiagnosticExporter.diagnosticShareText()))
        val share = DiagnosticExporter.diagnosticShareText()
        assertTrue(share, share.contains("Bluetooth (MeatPi Pro)"))
        service.stopConnection(com.openrs.dash.service.LinkStopReason.USER_DISCONNECT)
    }

    @Test fun `a Wi-Fi session's status keeps its diagnostic fields and holds no device ID`() = runBlocking {
        settings("WIFI")
        val token = DiagnosticLogger.sessionStart("192.168.0.10", 35000, transport = "Wi-Fi TCP (MeatPi Pro)", prefs = null)
        DiagnosticLogger.adapterStatusRequested(token)
        DiagnosticLogger.adapterStatusFetched(token, DeviceStatus(
            firmwareVersion = "4.48p", hardwareVersion = "WiCAN-PRO", deviceId = DEVICE_ID, canDatarate = "500K",
            canMode = "normal", protocol = "slcan", bleStatus = "disabled", obdPortVoltage = 14.2, sleepStatus = "off",
            sleepVolt = "13.1", wifiMode = "AP"))
        DiagnosticExporter.export(ctx)
        val texts = zipText()
        assertClean(texts + ("share text" to DiagnosticExporter.diagnosticShareText()))
        val summary = texts.entries.first { it.key.startsWith("diagnostic_summary_") }.value
        assertTrue(summary, summary.contains("WiCAN-PRO") && summary.contains("500K"))
        assertTrue("the Host line a Wi-Fi diagnosis needs", summary.contains("192.168.0.10:35000"))
    }

    private fun wifiTransportLabel(adapter: String, host: String): String {
        ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE).edit()
            .putString("connection_method", "WIFI").putString("adapter_type", adapter).putString("wican_host", host).commit()
        val cm = ctx.getSystemService(android.net.ConnectivityManager::class.java)
        val wifi = org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        Shadows.shadowOf(wifi).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        Shadows.shadowOf(cm).setNetworkCapabilities(cm.activeNetwork, wifi)
        DiagnosticLogger.sessionStart("before", 0, transport = "before", prefs = null)
        val service = TestServices.create()
        service.startConnection()
        val label = DiagnosticLogger.sessionTransport
        service.stopConnection(com.openrs.dash.service.LinkStopReason.USER_DISCONNECT)
        return label
    }

    @Test fun `the transport line the service builds for Wi-Fi names kind and profile, not the address`() {
        assertEquals("TCP SLCAN (MeatPi Pro)", wifiTransportLabel("MEATPI_PRO", "192.168.0.77"))
        assertEquals("WebSocket SLCAN (MeatPi USB)", wifiTransportLabel("MEATPI_USB", "192.168.0.77"))
    }

    @Test fun `error text keeps its diagnostic tail and loses its identifiers`() {
        val api = object : com.openrs.dash.can.FirmwareCommandSender {
            override suspend fun setDriveMode(mode: Int) = Result.failure<Unit>(java.io.IOException("BLE write failed (GATT 133) to $MAC"))
            override suspend fun setEscMode(mode: Int) = Result.failure<Unit>(java.io.IOException("BLE write failed (GATT 133) to $MAC"))
        }
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        runBlocking { com.openrs.dash.can.executeDriveModeChange(api, com.openrs.dash.data.DriveMode.SPORT, com.openrs.dash.data.DriveMode.NORMAL,
            state = { com.openrs.dash.data.VehicleState() }, connectionId = { 0 }, firmwareLabel = { "x" }) }
        val line = DiagnosticLogger.sessionEvents.last { it.type == "DM_CMD" }.message
        assertTrue(line, line.contains("GATT 133"))
        assertFalse(line, line.contains(MAC))
        val esc = runBlocking { EscCommand.execute(api, EscStatus.OFF, { com.openrs.dash.data.VehicleState(isConnected = true) }) }
        val text = EscCommand.describe(EscStatus.OFF, esc)
        assertTrue(text, text.contains("GATT 133"))
        assertFalse(text, text.contains(MAC))
    }
}
