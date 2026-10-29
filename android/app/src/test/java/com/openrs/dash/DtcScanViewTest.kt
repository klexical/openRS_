package com.openrs.dash

import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.DtcScanStatus
import com.openrs.dash.data.DtcStatus
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.diagnostics.DtcScanLedger
import com.openrs.dash.diagnostics.DtcScanView
import com.openrs.dash.diagnostics.DtcScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** #215 — the DIAG screen's scan state and the exported record under concurrent retries, new scans and sessions. */
class DtcScanViewTest {
    private val modules = listOf("PCM", "BCM", "ABS")
    private val ok = ModuleScanStatus.OK
    private fun code(m: String, c: String) = DtcResult(m, c, "", DtcStatus.ACTIVE)

    private fun scanned(): Triple<DtcScanLedger, DtcScanView, DtcScanLedger.Ticket> {
        val ledger = DtcScanLedger().apply { newSession() }
        val view = DtcScanView(ledger)
        val t = ledger.begin("manual", modules, 1L)
        view.begin(t)
        view.finish(t, DtcScanResult(emptyList(), mapOf("PCM" to ok, "BCM" to ModuleScanStatus.TIMEOUT, "ABS" to ModuleScanStatus.TIMEOUT)), 2L)
        return Triple(ledger, view, t)
    }

    @Test fun `two retries finishing in reverse order both land, on screen and in the export`() {
        val (ledger, view, t) = scanned()
        // Both retries started from the same result; ABS (started second) finishes first
        assertTrue(view.retryFinished(t, "ABS", listOf(code("ABS", "C1234")), ok, 3L))
        assertTrue(view.retryFinished(t, "BCM", listOf(code("BCM", "B1234")), ok, 4L))
        val r = view.result!!
        assertEquals(setOf("C1234", "B1234"), r.codes.map { it.code }.toSet())
        assertTrue(r.moduleStatuses.values.all { it == ok })
        val rec = ledger.current!!
        assertEquals(DtcScanStatus.COMPLETE, rec.status)
        assertEquals("screen and export agree", r.codes.toSet(), rec.codes.toSet())
        assertEquals(r.moduleStatuses, rec.modules)
    }

    @Test fun `a retry from the previous scan finishing after a new scan started changes nothing`() {
        val (ledger, view, old) = scanned()
        val fresh = ledger.begin("manual", modules, 10L)
        view.begin(fresh)
        assertFalse(view.retryFinished(old, "BCM", listOf(code("BCM", "B1234")), ok, 11L))
        assertEquals(null, view.result)
        assertEquals(DtcScanStatus.RUNNING, ledger.current?.status)
        assertTrue(ledger.current!!.codes.isEmpty())
    }

    @Test fun `a retry finishing after a new diagnostic session changes neither screen nor export`() {
        val (ledger, view, t) = scanned()
        val before = view.result
        ledger.newSession()
        assertFalse(view.retryFinished(t, "BCM", listOf(code("BCM", "B1234")), ok, 5L))
        assertEquals(before, view.result)
        assertEquals(null, ledger.current)
    }

    @Test fun `a late completion of an older scan doesn't replace the newer one`() {
        val ledger = DtcScanLedger().apply { newSession() }
        val view = DtcScanView(ledger)
        val first = ledger.begin("manual", modules, 1L); view.begin(first)
        val second = ledger.begin("manual", modules, 2L); view.begin(second)
        assertFalse(view.finish(first, DtcScanResult(listOf(code("PCM", "P0001")), modules.associateWith { ok }), 3L))
        assertEquals(null, view.result)
    }

    @Test fun `a previous code is resolved only if its module was scanned OK`() {
        val previous = setOf("BCM" to "B1234", "PCM" to "P0420")
        val statuses = mapOf("PCM" to ok, "BCM" to ModuleScanStatus.TIMEOUT, "ABS" to ModuleScanStatus.REJECTED)
        val diff = DtcScanner.computeDiff(current = emptyList(), previous = previous, moduleStatuses = statuses)
        assertEquals("BCM timed out — B1234 is unknown, not resolved", listOf("P0420"), diff.goneCodes)
        // Same code in a different module isn't "persistent"
        val d2 = DtcScanner.computeDiff(listOf(code("ABS", "B1234")), previous, statuses)
        assertEquals(listOf("B1234"), d2.newCodes.map { it.code })
    }
}
