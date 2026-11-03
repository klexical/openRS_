package com.openrs.dash

import com.openrs.dash.data.DtcModuleRetry
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.DtcStatus
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.DriveExportBuilder
import com.openrs.dash.diagnostics.DtcScanLedger
import com.openrs.dash.diagnostics.DtcScanView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #215 (source review 2026-10-31) — a module retry's explanation travels with it: the retry outcome, the session's
 * export record and DIAG's displayed result take the latest attempt's note (replaced on another failure, cleared on a
 * valid reply or a genuine timeout), and DIAG's NOT SCANNED row reads the displayed result, never the global record —
 * a newer scan / connection can't label an older row, and an obsolete retry changes nothing. Session ledger
 * (DiagnosticLogger.dtcScans) + DtcScanView, the same objects DIAG uses; exports through the real builders.
 */
class DtcRetryExplainTest {
    private val modules = listOf("PCM", "BCM")
    private val noteA = "0x19 02 request not sent — IOException: A"
    private val noteB = "flow control not sent — IOException: B"
    private val reason: (ModuleScanStatus) -> String = { "status ${it.name}" }
    private lateinit var view: DtcScanView
    private lateinit var ticket: DtcScanLedger.Ticket

    private fun failed(note: String) = DtcScanResult(emptyList(),
        mapOf("PCM" to ModuleScanStatus.ERROR, "BCM" to ModuleScanStatus.OK), moduleNotes = mapOf("PCM" to note))

    @Before fun scanFailed() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        view = DtcScanView(DiagnosticLogger.dtcScans)
        ticket = DiagnosticLogger.dtcScans.begin("manual", modules, 1L)
        view.begin(ticket)
        assertTrue(view.finish(ticket, failed(noteA), 2L))
    }

    /** What the screen row, the export record, its JSON and the DTC text file say about PCM. */
    private fun everywhere(): List<String?> {
        val rec = DiagnosticLogger.lastDtcScan!!
        val json = DiagnosticReportBuilder.dtcScanJson(rec).optJSONObject("moduleNotes")?.optString("PCM")?.takeIf { it.isNotEmpty() }
        val text = DriveExportBuilder.buildDtcText(rec.codes, rec).lines().firstOrNull { it.trim().startsWith("PCM: ") }
            ?.substringAfter("PCM: ")
        return listOf(view.result!!.moduleNotes["PCM"], rec.notes["PCM"], json, text)
    }

    @Test fun `retry fails for a different reason — the new reason replaces the old one everywhere`() {
        assertTrue(view.retryFinished(ticket, DtcModuleRetry("PCM", emptyList(), ModuleScanStatus.ERROR, noteB), 3L))
        assertEquals(listOf(noteB, noteB, noteB, noteB), everywhere())
        assertEquals("PCM — $noteB", DtcScanView.notScannedText(view.result!!, "PCM", reason))
        assertEquals(ModuleScanStatus.ERROR, DiagnosticLogger.lastDtcScan!!.modules["PCM"])
    }

    @Test fun `a successful retry clears the failure text everywhere`() {
        val code = DtcResult("PCM", "P0123", "", DtcStatus.ACTIVE, failureType = 0x01)
        assertTrue(view.retryFinished(ticket, DtcModuleRetry("PCM", listOf(code), ModuleScanStatus.OK), 3L))
        assertEquals(listOf<String?>(null, null, null, null), everywhere())
        assertEquals(ModuleScanStatus.OK, view.result!!.moduleStatuses["PCM"])
        assertEquals(listOf("P0123-01"), DiagnosticLogger.lastDtcScan!!.codes.map { it.displayCode })
    }

    @Test fun `a genuine timeout on retry clears the obsolete failure text — the row says no reply`() {
        assertTrue(view.retryFinished(ticket, DtcModuleRetry("PCM", emptyList(), ModuleScanStatus.TIMEOUT), 3L))
        assertEquals(listOf<String?>(null, null, null, null), everywhere())
        assertEquals("PCM — status TIMEOUT", DtcScanView.notScannedText(view.result!!, "PCM", reason))
        assertEquals(ModuleScanStatus.TIMEOUT, DiagnosticLogger.lastDtcScan!!.modules["PCM"])
    }

    @Test fun `a newer auto-scan replacing the global record doesn't relabel the older displayed row`() {
        val auto = DiagnosticLogger.dtcScans.begin("auto", modules, 3L)
        DiagnosticLogger.dtcScans.complete(auto, failed(noteB), 4L)
        assertEquals(noteB, DiagnosticLogger.lastDtcScan!!.notes["PCM"])          // the export now reports the newer scan
        assertEquals("PCM — $noteA", DtcScanView.notScannedText(view.result!!, "PCM", reason))
    }

    @Test fun `an obsolete retry finishing after a newer scan or a new connection changes neither screen nor export`() {
        val auto = DiagnosticLogger.dtcScans.begin("auto", modules, 3L)
        DiagnosticLogger.dtcScans.complete(auto, failed(noteB), 4L)
        assertFalse(view.retryFinished(ticket, DtcModuleRetry("PCM", emptyList(), ModuleScanStatus.ERROR, "late C"), 5L))
        assertEquals(noteA, view.result!!.moduleNotes["PCM"])
        assertEquals(noteB, DiagnosticLogger.lastDtcScan!!.notes["PCM"])

        DiagnosticLogger.sessionStart("test", 0, prefs = null)                   // the next connection's session
        assertFalse(view.retryFinished(ticket, DtcModuleRetry("PCM", emptyList(), ModuleScanStatus.OK), 6L))
        assertNull("the new session has no scan", DiagnosticLogger.lastDtcScan)
        assertEquals(noteA, view.result!!.moduleNotes["PCM"])
    }
}
