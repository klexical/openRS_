package com.openrs.dash

import com.openrs.dash.data.DtcModuleRetry
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.DtcScanStatus
import com.openrs.dash.data.DtcStatus
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.DtcScanLedger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The exported DTC record stays current and scoped to its diagnostic session (#215). */
class DtcScanLedgerTest {

    private val modules = listOf("PCM", "BCM", "ABS")
    private fun code(m: String, c: String) = DtcResult(m, c, "", DtcStatus.ACTIVE)
    private fun result(vararg s: Pair<String, ModuleScanStatus>, codes: List<DtcResult> = emptyList()) =
        DtcScanResult(codes, mapOf(*s))
    private val ok = ModuleScanStatus.OK

    @Test fun `session A with codes, then session B without a scan — B exports no scan`() {
        val l = DtcScanLedger()
        l.newSession()
        val a = l.begin("manual", modules, 1L)
        l.complete(a, result("PCM" to ok, "BCM" to ok, "ABS" to ok, codes = listOf(code("PCM", "P0123"))), 2L)
        assertEquals(DtcScanStatus.COMPLETE, l.current?.status)
        l.newSession()
        assertNull(l.current)
        assertEquals("No scan this session", DiagnosticReportBuilder.dtcScanLine(l.current))
    }

    @Test fun `DiagnosticLogger sessionStart clears the previous session's scan`() {
        val t = DiagnosticLogger.dtcScans.begin("auto", modules, 1L)
        DiagnosticLogger.dtcScans.complete(t, result("PCM" to ok, "BCM" to ok, "ABS" to ok), 2L)
        assertEquals(DtcScanStatus.COMPLETE, DiagnosticLogger.lastDtcScan?.status)
        DiagnosticLogger.sessionStart("10.0.2.2", 35000, prefs = null)
        assertNull(DiagnosticLogger.lastDtcScan)
    }

    @Test fun `a scan from session A finishing after session B started can't publish into B`() {
        val l = DtcScanLedger(); l.newSession()
        val a = l.begin("auto", modules, 1L)
        l.newSession()                                                // reconnect → new diagnostic session
        assertFalse(l.complete(a, result("PCM" to ok, "BCM" to ok, "ABS" to ok), 5L))
        assertFalse(l.interrupt(a, 5L, "connection ended"))
        assertNull(l.current)
    }

    @Test fun `a successful retry that finds codes updates the exported record`() {
        val l = DtcScanLedger(); l.newSession()
        val t = l.begin("manual", modules, 1L)
        l.complete(t, result("PCM" to ok, "BCM" to ModuleScanStatus.TIMEOUT, "ABS" to ok), 2L)
        assertEquals(DtcScanStatus.PARTIAL, l.current?.status)
        assertTrue(l.retry(t, DtcModuleRetry("BCM", listOf(code("BCM", "B1234")), ok), 3L))
        val r = l.current!!
        assertEquals(DtcScanStatus.COMPLETE, r.status)
        assertEquals(listOf("B1234"), r.codes.map { it.code })
        assertEquals(ok, r.modules["BCM"])
        assertEquals("COMPLETE — 1 code(s) · 3/3 modules scanned · manual", DiagnosticReportBuilder.dtcScanLine(r))
    }

    @Test fun `a retry that completes coverage with zero codes turns PARTIAL into a clean COMPLETE`() {
        val l = DtcScanLedger(); l.newSession()
        val t = l.begin("manual", modules, 1L)
        l.complete(t, result("PCM" to ok, "BCM" to ok, "ABS" to ModuleScanStatus.PENDING), 2L)
        assertTrue(l.retry(t, DtcModuleRetry("ABS", emptyList(), ok), 3L))
        assertEquals(DtcScanStatus.COMPLETE, l.current?.status)
        assertTrue(l.current!!.codes.isEmpty())
    }

    @Test fun `a retry that is refused keeps the scan partial`() {
        val l = DtcScanLedger(); l.newSession()
        val t = l.begin("manual", modules, 1L)
        l.complete(t, result("PCM" to ok, "BCM" to ok, "ABS" to ModuleScanStatus.TIMEOUT), 2L)
        l.retry(t, DtcModuleRetry("ABS", emptyList(), ModuleScanStatus.REJECTED), 3L)
        assertEquals(DtcScanStatus.PARTIAL, l.current?.status)
        assertEquals(ModuleScanStatus.REJECTED, l.current?.modules?.get("ABS"))
    }

    @Test fun `a retry for an older scan or a previous session is ignored`() {
        val l = DtcScanLedger(); l.newSession()
        val first = l.begin("manual", modules, 1L)
        l.complete(first, result("PCM" to ok, "BCM" to ModuleScanStatus.TIMEOUT, "ABS" to ok), 2L)
        val second = l.begin("manual", modules, 3L)
        l.complete(second, result("PCM" to ok, "BCM" to ok, "ABS" to ok), 4L)
        assertFalse(l.retry(first, DtcModuleRetry("BCM", listOf(code("BCM", "B1234")), ok), 5L))
        assertTrue(l.current!!.codes.isEmpty())
        l.newSession()
        assertFalse(l.retry(second, DtcModuleRetry("BCM", listOf(code("BCM", "B1234")), ok), 6L))
        assertNull(l.current)
    }

    @Test fun `an interrupted scan replaces an older clean result and keeps the coverage it reached`() {
        val l = DtcScanLedger(); l.newSession()
        val clean = l.begin("auto", modules, 1L)
        l.complete(clean, result("PCM" to ok, "BCM" to ok, "ABS" to ok), 2L)
        val t = l.begin("manual", modules, 3L)
        assertEquals("visible from its start", DtcScanStatus.RUNNING, l.current?.status)
        l.progress(t, "PCM", ok)
        l.progress(t, "BCM", null)
        assertTrue(l.interrupt(t, 4L, "DIAG screen closed"))
        val r = l.current!!
        assertEquals(DtcScanStatus.INTERRUPTED, r.status)
        assertEquals(mapOf("PCM" to ok, "BCM" to null, "ABS" to null), r.modules)
        assertTrue(DiagnosticReportBuilder.dtcScanLine(r).startsWith("INTERRUPTED before it finished (DIAG screen closed)"))
        // An interrupted scan takes no retry, and a late completion can't overwrite the outcome
        assertFalse(l.retry(t, DtcModuleRetry("BCM", emptyList(), ok), 5L))
        assertFalse(l.complete(t, result("PCM" to ok, "BCM" to ok, "ABS" to ok), 6L))
        assertEquals(DtcScanStatus.INTERRUPTED, l.current?.status)
    }

    @Test fun `a user abort stays CANCELLED when the cancelled job reports its interruption afterwards`() {
        val l = DtcScanLedger(); l.newSession()
        val t = l.begin("manual", modules, 1L)
        l.progress(t, "PCM", ok)
        assertTrue(l.cancel(t, 2L))
        assertFalse(l.interrupt(t, 3L, "DIAG screen closed"))
        assertEquals(DtcScanStatus.CANCELLED, l.current?.status)
        assertEquals(ok, l.current?.modules?.get("PCM"))
    }

    @Test fun `an export taken mid-scan says RUNNING, never no scan or an older result`() {
        val l = DtcScanLedger(); l.newSession()
        l.begin("auto", modules, 1L)
        assertEquals("RUNNING — not finished when exported · 0/3 modules scanned · auto", DiagnosticReportBuilder.dtcScanLine(l.current))
    }
}
