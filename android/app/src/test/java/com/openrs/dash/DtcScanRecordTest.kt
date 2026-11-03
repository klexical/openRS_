package com.openrs.dash

import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcScanRecord
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.DtcScanStatus
import com.openrs.dash.data.DtcStatus
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.DriveExportBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** No scan, a clean scan and a partial / failed / cancelled one must read differently in the export (#215). */
class DtcScanRecordTest {
    private val modules = listOf("PCM", "BCM", "ABS", "AWD", "PSCM", "GFM")
    private val allOk = modules.associateWith { ModuleScanStatus.OK }
    private fun scan(statuses: Map<String, ModuleScanStatus>, codes: List<DtcResult> = emptyList()) =
        DtcScanRecord.from(DtcScanResult(codes, statuses), 1_700_000_000_000L, "manual", modules)

    @Test fun `every module answering with zero codes is a COMPLETE clean scan`() {
        val r = scan(allOk)
        assertEquals(DtcScanStatus.COMPLETE, r.status)
        assertEquals("COMPLETE — no codes · 6/6 modules scanned · manual", DiagnosticReportBuilder.dtcScanLine(r))
    }

    @Test fun `zero codes with a module that timed out is PARTIAL, never clean`() {
        val r = scan(allOk + ("ABS" to ModuleScanStatus.TIMEOUT))
        assertEquals(DtcScanStatus.PARTIAL, r.status)
        assertTrue(DiagnosticReportBuilder.dtcScanLine(r).contains("not a clean scan"))
        assertTrue(DiagnosticReportBuilder.dtcScanLine(r).contains("5/6 modules scanned"))
    }

    @Test fun `a module missing from the result counts as not answered`() {
        val r = scan(allOk - "GFM")
        assertEquals(DtcScanStatus.PARTIAL, r.status)
        assertEquals(null, r.modules["GFM"])
    }

    @Test fun `no module answering is FAILED`() {
        assertEquals(DtcScanStatus.FAILED, scan(modules.associateWith { ModuleScanStatus.NO_RESPONSE }).status)
    }

    @Test fun `no scan, cancelled and complete-with-codes are distinct in the JSON`() {
        assertEquals("NONE", DiagnosticReportBuilder.dtcScanJson(null).getString("status"))
        assertEquals("No scan this session", DiagnosticReportBuilder.dtcScanLine(null))
        val cancelled = DtcScanRecord(DtcScanStatus.CANCELLED, 1L, "manual", mapOf("PCM" to ModuleScanStatus.OK, "BCM" to null))
        val cj = DiagnosticReportBuilder.dtcScanJson(cancelled)
        assertEquals("CANCELLED", cj.getString("status"))
        assertEquals("NO_ANSWER", cj.getJSONObject("modules").getString("BCM"))
        val code = DtcResult("PCM", "P0171", "System too lean", DtcStatus.ACTIVE)
        val withCodes = scan(allOk, listOf(code))
        val json = JSONObject(DriveExportBuilder.buildDtcJson(withCodes.codes, withCodes))
        assertEquals(1, json.getInt("totalCodes"))                              // existing shape kept for Sapphire
        assertEquals("P0171", json.getJSONArray("codes").getJSONObject(0).getString("code"))
        assertEquals("COMPLETE", json.getJSONObject("scan").getString("status"))
        assertEquals(6, json.getJSONObject("scan").getInt("modulesAnswered"))
    }

    @Test fun `a zero-code text report states the scan outcome`() {
        val text = DriveExportBuilder.buildDtcText(emptyList(), scan(allOk))
        assertTrue(text.contains("Scan: COMPLETE — no codes · 6/6 modules scanned"))
        assertTrue(text.contains("Total: 0 fault code(s)"))
    }
}
