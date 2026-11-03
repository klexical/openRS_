package com.openrs.dash

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DtcModuleRetry
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.DtcStatus
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.DiagnosticLogger
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * #238 review finding 4 — one diagnostic ZIP must describe ONE DTC scan. [DiagnosticExporter.export] read
 * `DiagnosticLogger.lastDtcScan` separately for the summary text (twice), the detail JSON and the `dtc_scan_*` files,
 * and DIAG allows an export while a scan runs. Deterministic interleaving without touching production code: the export's
 * second `filesDir` call (crash files, written between the summary / detail and the DTC files) applies a ledger change —
 * a scan completing, a retry finding a code, a new session clearing the scan, a first scan finishing. Expected: summary
 * text, detail JSON and DTC files agree (status, code count, presence), including "no scan" in all of them.
 * Production exporter + ledger (Robolectric files); no device.
 */
@RunWith(RobolectricTestRunner::class)
class DiagnosticExportDtcSnapshotTest {
    private lateinit var app: Application
    private lateinit var root: File
    private val modules = listOf("PCM", "BCM", "ABS")
    private val ok = ModuleScanStatus.OK
    private fun code(m: String, c: String) = DtcResult(m, c, "", DtcStatus.ACTIVE)
    private val ledger get() = DiagnosticLogger.dtcScans

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        root = File(app.cacheDir, "dtc-snapshot-${System.nanoTime()}").also { it.mkdirs() }
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
    }

    /** Context whose 2nd `filesDir` call (the crash-files step) runs [between]. */
    private inner class Ctx(private val between: () -> Unit) : ContextWrapper(app) {
        var calls = 0
        var fired = false
        override fun getFilesDir(): File { if (++calls == 2) { between(); fired = true }; return root }
    }

    private data class Seen(val summary: String, val detail: String, val dtcText: String?, val dtcJson: String?) {
        fun consistent() = detail == (dtcJson ?: detail) && (dtcText == null) == (dtcJson == null) &&
            (detail == "NONE/0") == (dtcJson == null) && summaryAgrees()
        private fun summaryAgrees(): Boolean {
            val st = detail.substringBefore('/')
            return if (st == "NONE") summary.startsWith("No scan") else summary.startsWith(st)
        }
    }

    private fun exportWith(between: () -> Unit): Seen {
        val ctx = Ctx(between)
        DiagnosticExporter.export(ctx)                                  // the URI may be null under Robolectric; the ZIP is written
        val errors = DiagnosticLogger.sessionEvents.filter { it.type == "EXPORT_ERROR" }.map { it.message }
        assertTrue("the ledger change ran between the summary / detail and the DTC files (errors: $errors)", ctx.fired)
        val zip = File(root, "diagnostics").listFiles { f -> f.name.startsWith("openrs_diag_") }!!.maxBy { it.lastModified() }
        ZipFile(zip).use { z ->
            fun entry(prefix: String) = z.entries().toList().firstOrNull { it.name.startsWith(prefix) }
                ?.let { z.getInputStream(it).bufferedReader().readText() }
            val summary = entry("diagnostic_summary_")!!.lines()
                .dropWhile { !it.contains("DTC SCAN") }.drop(1).first().trim()
            val d = JSONObject(entry("diagnostic_detail_")!!).getJSONObject("dtcScan")
            val detail = "${d.getString("status")}/${d.optInt("codeCount", 0)}"
            val dtcJson = z.entries().toList().firstOrNull { it.name.startsWith("dtc_scan_") && it.name.endsWith(".json") }
                ?.let { JSONObject(z.getInputStream(it).bufferedReader().readText()) }
                ?.let { "${it.getJSONObject("scan").getString("status")}/${it.getInt("totalCodes")}" }
            val dtcText = z.entries().toList().firstOrNull { it.name.startsWith("dtc_scan_") && it.name.endsWith(".txt") }?.name
            return Seen(summary, detail, dtcText, dtcJson).also { println("SEEN $it") }
        }
    }

    private fun assertOneScan(case: String, s: Seen) =
        assertTrue("[$case] summary / detail / DTC files describe different scans: $s", s.consistent())

    @Test fun `a scan completing during the export - one scan in every file`() {
        val t = ledger.begin("manual", modules, 1L)                     // RUNNING when the export starts
        val s = exportWith { ledger.complete(t, DtcScanResult(listOf(code("PCM", "P0123")), modules.associateWith { ok }), 2L) }
        assertOneScan("complete", s)
    }

    @Test fun `a retry finding a code during the export - one scan in every file`() {
        val t = ledger.begin("manual", modules, 1L)
        ledger.complete(t, DtcScanResult(emptyList(), mapOf("PCM" to ok, "BCM" to ModuleScanStatus.TIMEOUT, "ABS" to ok)), 2L)
        val s = exportWith { ledger.retry(t, DtcModuleRetry("BCM", listOf(code("BCM", "B1234")), ok), 3L) }
        assertOneScan("retry", s)
    }

    @Test fun `a new session clearing the scan during the export - one scan in every file`() {
        val t = ledger.begin("auto", modules, 1L)
        ledger.complete(t, DtcScanResult(listOf(code("PCM", "P0123")), modules.associateWith { ok }), 2L)
        val s = exportWith { DiagnosticLogger.sessionStart("test", 0, prefs = null) }
        assertOneScan("new session", s)
    }

    @Test fun `no scan when the export starts, one finishing meanwhile - no scan in every file`() {
        val s = exportWith {
            val t = ledger.begin("auto", modules, 5L)
            ledger.complete(t, DtcScanResult(emptyList(), modules.associateWith { ok }), 6L)
        }
        assertOneScan("none then scan", s)
    }

    @Test fun `control - no change during the export - files agree`() {
        val t = ledger.begin("manual", modules, 1L)
        ledger.complete(t, DtcScanResult(listOf(code("PCM", "P0123")), modules.associateWith { ok }), 2L)
        assertOneScan("control", exportWith { })
    }
}
