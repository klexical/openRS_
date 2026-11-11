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
 * #260: an export holds no VIN, as text, as hex, or as bytes that rebuild one from the raw log. Real decode through LinkSession, real
 * exporter, every ZIP member scanned. Pages on 0x40A and 0x405 and a 0xF190 reply are masked; every other frame stays. A synthetic VIN; the
 * in-app value (#126) stays.
 */
@RunWith(RobolectricTestRunner::class)
class ExportVinScanTest {
    private companion object {
        const val VIN = "SYN0TESTVIN123456"
        val OTHER_405 = byteArrayOf(0x02, 0x3D, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val OTHER_40A = byteArrayOf(0xC2.toByte(), 0x01, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66)
        val OTHER_0F8 = byteArrayOf(0x46, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x70)
    }

    private lateinit var root: File
    private val ctx by lazy {
        val app = ApplicationProvider.getApplicationContext<Application>()
        object : ContextWrapper(app) { override fun getFilesDir(): File = root }
    }
    private val state = MutableStateFlow(VehicleState(isConnected = true))

    private class Rec : LinkSession.Recorder {
        override val activeDriveId: Long? = null
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
            // The same VIN on 0x405, which has no decoder: real captures hold it in pages 10, 11 and 12 (bytes synthetic)
            s.onCanFrame(0x405, byteArrayOf(0x10, 0x0A, 0x0B, 0x0C, 0x0D) + VIN.substring(0, 3).toByteArray(), 0.0)
            s.onCanFrame(0x405, byteArrayOf(0x11) + VIN.substring(3, 10).toByteArray(), 0.0)
            s.onCanFrame(0x405, byteArrayOf(0x12) + VIN.substring(10, 17).toByteArray(), 0.0)
            s.onCanFrame(0x405, OTHER_405, 0.0)
            s.onCanFrame(0x40A, OTHER_40A, 0.0)
            s.onCanFrame(0x0F8, OTHER_0F8, 0.0)
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
    /** The whole VIN as text, and any 4 consecutive characters of it as hex, spaced or not: one page or a piece of one must not survive. */
    private val forbidden = listOf(VIN) + (0..VIN.length - 4).flatMap { listOf(spaced(VIN.substring(it, it + 4)), compact(VIN.substring(it, it + 4))) }

    private fun assertNoVin(name: String, text: String) {
        val hits = forbidden.filter { text.contains(it, ignoreCase = true) }
        assertEquals("$name holds $hits", emptyList<String>(), hits)
    }

    /** Every candump line's data bytes, joined per ID: what someone could rebuild from the log. Refuses a line candump couldn't parse. */
    private fun rawById(raw: String): Map<Int, ByteArray> {
        val out = linkedMapOf<Int, ByteArray>()
        for (l in raw.lines()) {
            if (l.isEmpty() || l.startsWith("#")) continue
            val m = Regex("""^\(\s*\d+\.\d{3}\) can0 ([0-9A-F]{3})#((?:[0-9A-F]{2})*)$""").matchEntire(l) ?: throw AssertionError("not a candump line: $l")
            val id = m.groupValues[1].toInt(16)
            out[id] = (out[id] ?: ByteArray(0)) + m.groupValues[2].chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        }
        return out
    }

    private fun assertNothingRebuilds(raw: String) {
        val wanted = (0..VIN.length - 4).map { VIN.substring(it, it + 4) }
        for ((id, bytes) in rawById(raw)) {
            val text = String(bytes, Charsets.ISO_8859_1)
            val hits = wanted.filter { text.contains(it) }
            assertEquals("0x%03X".format(id), emptyList<String>(), hits)
        }
    }

    private fun lines(raw: String, id: String) = raw.lines().filter { it.contains(" can0 $id#") }.map { it.substringAfter('#') }

    @Test fun `a diagnostic export holds no VIN in any member, raw log included, and nothing in it rebuilds one`() {
        DiagnosticExporter.export(ctx)
        val files = zip("openrs_diag_")
        assertTrue(files.keys.toString(), files.keys.any { it.startsWith("diagnostic_detail_") })
        assertTrue(files.keys.toString(), files.keys.any { it.startsWith("slcan_log_") })
        for ((name, text) in files) assertNoVin(name, text)
        assertNothingRebuilds(files.entries.single { it.key.startsWith("slcan_log_") }.value)
    }

    @Test fun `a drive export with diagnostics holds no VIN in any member`() {
        val drive = DriveEntity(id = 7, startTime = 1_700_000_000_000L, endTime = 1_700_000_060_000L, distanceKm = 1.0)
        val points = listOf(DrivePointEntity(driveId = 7, timestamp = 1_700_000_001_000L, lat = 51.5, lng = -0.12, speedKph = 30.0, rpm = 1800))
        DiagnosticExporter.shareDrive(ctx, drive, points, includeDiagnostics = true)
        val files = zip("openrs_export_")
        for ((name, text) in files) assertNoVin(name, text)
        files.entries.firstOrNull { it.key.startsWith("slcan_log_") }?.let { assertNothingRebuilds(it.value) }
    }

    @Test fun `a VIN frame keeps its ID, length, time and mux, and reads 00 in the raw log`() {
        DiagnosticExporter.export(ctx)
        val raw = zip("openrs_diag_").entries.single { it.key.startsWith("slcan_log_") }.value
        assertEquals(listOf("C100000000000000", "C101000000000000", "C102000000000000"), lines(raw, "40A").filter { it.startsWith("C1") }.take(3))
        assertEquals(listOf("100A0B0C0D000000", "1100000000000000", "1200000000000000"), lines(raw, "405").filter { !it.startsWith("02") }.take(3))
    }

    @Test fun `control - other frames reach the raw log byte for byte, and none is lost`() {
        DiagnosticExporter.export(ctx)
        val raw = zip("openrs_diag_").entries.single { it.key.startsWith("slcan_log_") }.value
        fun hex(b: ByteArray) = b.joinToString("") { "%02X".format(it) }
        assertEquals(listOf(hex(OTHER_405), hex(OTHER_405)), lines(raw, "405").filter { it.startsWith("02") })
        assertEquals(listOf(hex(OTHER_40A), hex(OTHER_40A)), lines(raw, "40A").filter { it.startsWith("C2") })
        assertEquals(listOf(hex(OTHER_0F8), hex(OTHER_0F8)), lines(raw, "0F8"))
        assertEquals("every frame has its line", 8, lines(raw, "40A").size)
        assertEquals(8, lines(raw, "405").size)
    }

    @Test fun `the inventory shows a 0x405 page's mux and no VIN`() {
        val detail = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("canFrameInventory")
        val row = detail.getJSONObject(detail.keys().asSequence().first { it.contains("405", ignoreCase = true) })
        assertEquals(8, row.getInt("totalReceived"))
        assertNoVin("0x405 row", row.toString())
    }

    @Test fun `a 0x405 page held as the row's first, last or sampled frame shows no VIN in the summary or detail`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = File(root, "diagnostics"))
        DiagnosticLogger.logUnknownFrame(0x405, byteArrayOf(0x11) + VIN.substring(3, 10).toByteArray())
        DiagnosticLogger.logUnknownFrame(0x405, byteArrayOf(0x12) + VIN.substring(10, 17).toByteArray())
        val (summary, detail) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
        assertNoVin("summary", summary)
        assertNoVin("detail", detail)
    }

    @Test fun `the summary reports how many VIN frames were masked`() {
        DiagnosticExporter.export(ctx)
        val summary = zip("openrs_diag_").entries.single { it.key.startsWith("diagnostic_summary_") }.value
        assertTrue(summary.substringAfter("LOG LIMITS").take(600), summary.contains("12 VIN-bearing frame(s) masked"))
    }

    // ── the content layer: another car's layout ─────────────────────────────────────────────────────────────────────

    private fun rawNow(): String { DiagnosticLogger.flushSlcan(); DiagnosticExporter.export(ctx); return zip("openrs_diag_").entries.single { it.key.startsWith("slcan_log_") }.value }

    @Test fun `once the VIN is known, 4 or more of its bytes on any other ID read 00 and only those, counted apart from the pages`() {
        val before = DiagnosticLogger.logLimits
        DiagnosticLogger.logUnknownFrame(0x333, byteArrayOf(0x7A) + VIN.substring(4, 9).toByteArray() + byteArrayOf(0x7B, 0x7C))
        val raw = rawNow()
        assertEquals(listOf("7A00000000007B7C"), lines(raw, "333"))
        assertEquals(before.vinFramesMaskedContent + 1, DiagnosticLogger.logLimits.vinFramesMaskedContent)
        assertEquals(before.vinFramesMaskedPage, DiagnosticLogger.logLimits.vinFramesMaskedPage)
    }

    @Test fun `collision control - a frame with 3 matching bytes is left alone, the cost of matching on 3 is measured here`() {
        val before = DiagnosticLogger.logLimits
        DiagnosticLogger.logUnknownFrame(0x334, byteArrayOf(0x7A) + VIN.substring(11, 14).toByteArray() + byteArrayOf(0x7B, 0x7C, 0x7D, 0x7E))
        val raw = rawNow()
        assertEquals(listOf("7A" + compact(VIN.substring(11, 14)) + "7B7C7D7E"), lines(raw, "334"))
        assertEquals(before.vinFramesMaskedContent, DiagnosticLogger.logLimits.vinFramesMaskedContent)
    }

    @Test fun `before the VIN is known the content layer has nothing to match, and the page rule still holds`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = File(root, "diagnostics"))
        DiagnosticLogger.logUnknownFrame(0x335, VIN.substring(0, 8).toByteArray())
        DiagnosticLogger.logUnknownFrame(0x405, byteArrayOf(0x11) + VIN.substring(3, 10).toByteArray())
        val raw = rawNow()
        assertEquals(listOf(compact(VIN.substring(0, 8))), lines(raw, "335"))
        assertEquals(listOf("1100000000000000"), lines(raw, "405"))
    }

    @Test fun `the count reaches the export as text and JSON`() {
        DiagnosticLogger.logUnknownFrame(0x333, VIN.substring(0, 8).toByteArray())
        DiagnosticExporter.export(ctx)
        val files = zip("openrs_diag_")
        val summary = files.entries.single { it.key.startsWith("diagnostic_summary_") }.value
        assertTrue(summary, summary.contains("1 further frame(s) masked for holding 4 or more consecutive VIN bytes"))
        val limits = JSONObject(files.entries.single { it.key.startsWith("diagnostic_detail_") }.value).getJSONObject("logLimits")
        assertEquals(1, limits.getInt("vinFramesMaskedContent"))
        assertEquals(12, limits.getInt("vinFramesMaskedPage"))
    }

    @Test fun `a new session forgets the VIN and the counts`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = File(root, "diagnostics"))
        assertEquals(0L, DiagnosticLogger.logLimits.vinFramesMaskedPage)
        DiagnosticLogger.logUnknownFrame(0x336, VIN.substring(0, 8).toByteArray())
        assertEquals(0L, DiagnosticLogger.logLimits.vinFramesMaskedContent)
    }

    // ── a DID 0xF190 reply ───────────────────────────────────────────────────────────────────────────────────────────

    private fun hexBytes(s: String) = s.toByteArray(Charsets.US_ASCII)

    private fun f190(): Map<String, String> {
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x10, 0x14, 0x62, 0xF1.toByte(), 0x90.toByte()) + hexBytes(VIN.substring(0, 3)))
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x21) + hexBytes(VIN.substring(3, 10)))
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x22) + hexBytes(VIN.substring(10, 17)))
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x10, 0x14, 0x62, 0xDE.toByte(), 0x00, 0x11, 0x22, 0x33))
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x21, 0x44, 0x55, 0x66, 0x77, 0x11, 0x22, 0x33))
        DiagnosticLogger.logObdFrame(0x7E8, byteArrayOf(0x04, 0x62, 0xF4.toByte(), 0x05, 0x70))
        DiagnosticLogger.recordProbeSession("BCM", 0x726, 0x72E, listOf(
            DiagnosticLogger.ProbeResult(0xF190, "FOUND", spaced(VIN.substring(0, 3))),
            DiagnosticLogger.ProbeResult(0xDE00, "FOUND", "11 22 33"),
        ))
        DiagnosticLogger.flushSlcan()
        DiagnosticExporter.export(ctx)
        return zip("openrs_diag_")
    }

    @Test fun `a 0xF190 reply's data reads 00 in the raw log, first frame and consecutive frames`() {
        val raw = f190().entries.single { it.key.startsWith("slcan_log_") }.value
        assertEquals(listOf("101462F190000000", "2100000000000000", "2200000000000000", "101462DE00112233", "2144556677112233"), lines(raw, "72E"))
        assertEquals("another DID's reply is untouched", listOf("0462F40570"), lines(raw, "7E8"))
    }

    @Test fun `before the VIN is known a 0xF190 reply's consecutive frames still read 00, and another reply's don't`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = File(root, "diagnostics"))
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x10, 0x14, 0x62, 0xF1.toByte(), 0x90.toByte()) + hexBytes(VIN.substring(0, 3)))
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x21) + hexBytes(VIN.substring(3, 10)))
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x22) + hexBytes(VIN.substring(10, 17)))
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x23, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07))    // past the announced length
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x10, 0x14, 0x62, 0xDE.toByte(), 0x00, 0x11, 0x22, 0x33))
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x21, 0x44, 0x55, 0x66, 0x77, 0x11, 0x22, 0x33))
        assertEquals(listOf("101462F190000000", "2100000000000000", "2200000000000000", "2301020304050607", "101462DE00112233", "2144556677112233"), lines(rawNow(), "72E"))
    }

    @Test fun `a 0xF190 reply in a single frame reads 00 past the DID, which a whole VIN can't fit but a short reply can`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = File(root, "diagnostics"))
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x06, 0x62, 0xF1.toByte(), 0x90.toByte()) + hexBytes(VIN.substring(0, 3)))
        DiagnosticLogger.logObdFrame(0x72E, byteArrayOf(0x06, 0x62, 0xDE.toByte(), 0x00, 0x11, 0x22, 0x33))
        assertEquals(listOf("0662F190000000", "0662DE00112233"), lines(rawNow(), "72E"))
    }

    // ── a stored probe reply on any DID: QA found it bypassed the content layer on a minified build ──────────────────

    private fun probeFiles(vararg results: DiagnosticLogger.ProbeResult): Map<String, String> {
        DiagnosticLogger.recordProbeSession("BCM", 0x726, 0x72E, results.toList())
        DiagnosticExporter.export(ctx)
        return zip("openrs_diag_")
    }

    @Test fun `4 VIN bytes in a probe reply on any DID read 00 in the CSV, the summary and the detail, once the VIN is known`() {
        val files = probeFiles(DiagnosticLogger.ProbeResult(0xFD10, "FOUND", spaced(VIN.substring(4, 8))))
        val csv = files.entries.single { it.key.startsWith("did_probe_") }.value
        assertTrue(csv, csv.contains("0xFD10,FOUND,00 00 00 00"))
        for ((name, text) in files) assertFalse(name, text.contains(spaced(VIN.substring(4, 8))))
    }

    @Test fun `only the run reads 00, and a reply stored before the VIN was known is covered at export`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = File(root, "diagnostics"))
        DiagnosticLogger.recordProbeSession("BCM", 0x726, 0x72E, listOf(DiagnosticLogger.ProbeResult(0xFD11, "FOUND", "7A " + spaced(VIN.substring(4, 8)) + " 7B")))
        val own = LinkOwnership(); val gen = own.transition { own.begin() }
        val s = LinkSession(own, gen, state, Rec(), onFrameCounted = {}, perfRunSink = {})
        s.onCanFrame(0x40A, page(0, VIN.substring(0, 6)), 0.0); s.onCanFrame(0x40A, page(1, VIN.substring(6, 12)), 0.0); s.onCanFrame(0x40A, page(2, VIN.substring(12)), 0.0)
        DiagnosticExporter.export(ctx)
        val csv = zip("openrs_diag_").entries.single { it.key.startsWith("did_probe_") }.value
        assertTrue(csv, csv.contains("0xFD11,FOUND,7A 00 00 00 00 7B"))
    }

    /**
     * The limit, held rather than inferred: probe replies are masked at read only once the VIN is known, and session start forgets it until a state carrying
     * it arrives. An export written in between keeps a reply's VIN bytes in every member that shows probe results (cold start only).
     */
    @Test fun `limit - a probe reply exported before the VIN is decoded keeps its bytes in the CSV, the summary and the detail, and the next export masks them`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = File(root, "diagnostics"))
        val run = spaced(VIN.substring(4, 8))
        DiagnosticLogger.recordProbeSession("BCM", 0x726, 0x72E, listOf(DiagnosticLogger.ProbeResult(0xFD15, "FOUND", run)))
        DiagnosticExporter.export(ctx)
        val before = zip("openrs_diag_")
        for (prefix in listOf("did_probe_", "diagnostic_summary_", "diagnostic_detail_"))
            assertTrue(prefix, before.entries.single { it.key.startsWith(prefix) }.value.contains(run))
        val own = LinkOwnership(); val gen = own.transition { own.begin() }
        val s = LinkSession(own, gen, state, Rec(), onFrameCounted = {}, perfRunSink = {})
        s.onCanFrame(0x40A, page(0, VIN.substring(0, 6)), 0.0); s.onCanFrame(0x40A, page(1, VIN.substring(6, 12)), 0.0); s.onCanFrame(0x40A, page(2, VIN.substring(12)), 0.0)
        File(root, "diagnostics").listFiles { x -> x.name.startsWith("openrs_diag_") }.orEmpty().forEach { it.delete() }   // zip() reads the one export
        DiagnosticExporter.export(ctx)
        for ((name, text) in zip("openrs_diag_")) assertFalse(name, text.contains(run))
    }

    @Test fun `control - 3 VIN bytes in a probe reply, another DID's bytes and a rejection's text stay`() {
        val files = probeFiles(
            DiagnosticLogger.ProbeResult(0xFD12, "FOUND", spaced(VIN.substring(11, 14))),
            DiagnosticLogger.ProbeResult(0xFD13, "FOUND", "11 22 33 44"),
            DiagnosticLogger.ProbeResult(0xFD14, "NRC", "NRC 0x31 — request out of range"),
        )
        val csv = files.entries.single { it.key.startsWith("did_probe_") }.value
        assertTrue(csv, csv.contains("0xFD12,FOUND," + spaced(VIN.substring(11, 14))))
        assertTrue(csv, csv.contains("0xFD13,FOUND,11 22 33 44"))
        assertTrue(csv, csv.contains("NRC 0x31"))
    }

    @Test fun `a 0xF190 result's bytes are masked in the probe CSV and the reports, another DID's aren't`() {
        val files = f190()
        val csv = files.entries.single { it.key.startsWith("did_probe_") }.value
        assertTrue(csv, csv.contains("0xF190,FOUND,00 00 00"))
        assertTrue(csv, csv.contains("0xDE00,FOUND,11 22 33"))
        for ((name, text) in files) assertFalse("$name holds the first frame's characters", text.contains(spaced(VIN.substring(0, 3))))
        val summary = files.entries.single { it.key.startsWith("diagnostic_summary_") }.value
        assertTrue(summary.substringAfter("0xF190").take(40), summary.substringAfter("0xF190").take(40).contains("00 00 00"))
    }

    @Test fun `the export says a VIN was seen, and the row stays useful, without the value`() {
        val detail = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))
        val row = detail.getJSONObject("canFrameInventory").let { inv -> inv.getJSONObject(inv.keys().asSequence().first { it.contains("40A", ignoreCase = true) }) }
        assertTrue(row.toString(), row.toString().contains("vin=<masked>"))
        assertTrue("the mux bytes stay for diagnosis", row.toString().contains("C1 00 ** ** ** ** ** **"))
        assertEquals(8, row.getInt("totalReceived"))
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
