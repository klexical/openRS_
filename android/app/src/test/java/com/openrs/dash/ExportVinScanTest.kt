package com.openrs.dash

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * #260: an export holds no decoded VIN, in text or as the hex of its pages, outside the raw SLCAN log (whose handling is the owner's
 * open decision: this pins that it stays as it is). Real decode through LinkSession, real exporter, every ZIP member scanned.
 * A synthetic VIN; the in-app value (#126) stays.
 */
@RunWith(RobolectricTestRunner::class)
class ExportVinScanTest {
    private companion object { const val VIN = "SYN0TESTVIN123456" }

    private lateinit var root: File
    private val ctx by lazy {
        val app = ApplicationProvider.getApplicationContext<Application>()
        object : ContextWrapper(app) { override fun getFilesDir(): File = root }
    }
    private val state = MutableStateFlow(VehicleState(isConnected = true))

    private class Rec : LinkSession.Recorder {
        override val isRecording = false; override val isPaused = false; override val activeDriveId: Long? = null
        override fun pausedDurationMs() = 0L
        override fun resumeDrive() {}; override fun stopDrive() {}; override fun startDrive(sessionId: Long) {}
    }

    private fun page(mux: Int, text: String) =
        byteArrayOf(0xC1.toByte(), mux.toByte()) + text.toByteArray(Charsets.US_ASCII) + ByteArray(6 - text.length)

    @Before fun session() {
        root = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "vin-${System.nanoTime()}").also { it.mkdirs() }
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = File(root, "diagnostics"))
        com.openrs.dash.can.CanDecoder.resetSessionState()   // the first pages of a VIN decode to nothing: the path that kept their hex
        val own = LinkOwnership()
        val gen = own.transition { own.begin() }
        val s = LinkSession(own, gen, state, Rec(), onFrameCounted = {}, perfRunSink = {})
        repeat(2) {
            s.onCanFrame(0x40A, page(0, VIN.substring(0, 6)), 0.0)
            s.onCanFrame(0x40A, page(1, VIN.substring(6, 12)), 0.0)
            s.onCanFrame(0x40A, page(2, VIN.substring(12)), 0.0)
        }
        DiagnosticLogger.flushSlcan()
        assertEquals("the decode ran: the in-app value is there (#126)", VIN, state.value.vin)
    }

    private fun zip(prefix: String): Map<String, String> {
        val f = File(root, "diagnostics").listFiles { x -> x.name.startsWith(prefix) }.orEmpty().single()
        return ZipFile(f).use { z -> z.entries().toList().associate { it.name to z.getInputStream(it).bufferedReader().readText() } }
    }

    private fun spaced(s: String) = s.toByteArray().joinToString(" ") { "%02X".format(it) }
    private fun compact(s: String) = s.toByteArray().joinToString("") { "%02X".format(it) }
    private val segments = listOf(VIN.substring(0, 6), VIN.substring(6, 12), VIN.substring(12))
    private val forbidden = listOf(VIN) + segments.flatMap { listOf(spaced(it), compact(it)) }

    private fun assertNoVin(name: String, text: String) {
        val hits = forbidden.filter { text.contains(it, ignoreCase = true) }
        assertEquals("$name holds $hits", emptyList<String>(), hits)
    }

    @Test fun `a diagnostic export holds no VIN outside the raw log, whose frames are unchanged`() {
        DiagnosticExporter.export(ctx)
        val files = zip("openrs_diag_")
        assertTrue(files.keys.toString(), files.keys.any { it.startsWith("diagnostic_detail_") })
        for ((name, text) in files) if (!name.startsWith("slcan_log_")) assertNoVin(name, text)
        val raw = files.entries.single { it.key.startsWith("slcan_log_") }.value
        assertTrue("raw 0x40A lines stay until the owner decides", raw.contains(compact(segments[0])))
    }

    @Test fun `a drive export with diagnostics holds no VIN outside the raw log`() {
        val drive = DriveEntity(id = 7, startTime = 1_700_000_000_000L, endTime = 1_700_000_060_000L, distanceKm = 1.0)
        val points = listOf(DrivePointEntity(driveId = 7, timestamp = 1_700_000_001_000L, lat = 51.5, lng = -0.12, speedKph = 30.0, rpm = 1800))
        DiagnosticExporter.shareDrive(ctx, drive, points, includeDiagnostics = true)
        for ((name, text) in zip("openrs_export_")) if (!name.startsWith("slcan_log_")) assertNoVin(name, text)
    }

    @Test fun `the export says a VIN was seen, and the row stays useful, without the value`() {
        val detail = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))
        val row = detail.getJSONObject("canFrameInventory").let { inv -> inv.getJSONObject(inv.keys().asSequence().first { it.contains("40A", ignoreCase = true) }) }
        assertTrue(row.toString(), row.toString().contains("vin=<masked>"))
        assertTrue("the mux bytes stay for diagnosis", row.toString().contains("C1 02"))
        assertEquals(6, row.getInt("totalReceived"))
    }

    @Test fun `the chooser opens with the notice over it`() {
        // FileProvider has no root under plain Robolectric, so the shares' own URI step can't run here; all three call this
        val activity = org.robolectric.Robolectric.buildActivity(android.app.Activity::class.java).create().get()
        DiagnosticExporter.startChooser(activity, android.content.Intent(android.content.Intent.ACTION_SEND), "t")
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(DiagnosticExporter.SHARE_TOAST, org.robolectric.shadows.ShadowToast.getTextOfLatestToast())
        assertEquals(android.content.Intent.ACTION_CHOOSER, org.robolectric.Shadows.shadowOf(activity).nextStartedActivity?.action)
    }

    @Test fun `the notice is short enough to name the VIN in a Toast`() {
        // Android cuts a Toast at two lines. The old 122-character notice was cut before "VIN" on an emulator; the words a screen shows are the re-smoke's to check
        val t = DiagnosticExporter.SHARE_TOAST
        assertTrue("${t.length} characters: $t", t.length <= 54)
        assertTrue("the VIN comes first, in case a screen cuts the rest: $t", t.indexOf("VIN") in 0..20)
        assertTrue(t, t.contains("GPS route"))
    }

    @Test fun `the share text holds no VIN and names what the ZIP contains`() {
        val text = DiagnosticExporter.diagnosticShareText()
        assertFalse(text, text.contains(VIN))
        assertTrue(text, text.contains(DiagnosticExporter.SHARE_NOTICE))
        assertTrue(DiagnosticExporter.SHARE_NOTICE, DiagnosticExporter.SHARE_NOTICE.contains("VIN"))
    }
}
