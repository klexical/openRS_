package com.openrs.dash

import com.openrs.dash.data.DtcModuleRetry
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
        assertTrue(view.retryFinished(t, DtcModuleRetry("ABS", listOf(code("ABS", "C1234")), ok), 3L))
        assertTrue(view.retryFinished(t, DtcModuleRetry("BCM", listOf(code("BCM", "B1234")), ok), 4L))
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
        assertFalse(view.retryFinished(old, DtcModuleRetry("BCM", listOf(code("BCM", "B1234")), ok), 11L))
        assertEquals(null, view.result)
        assertEquals(DtcScanStatus.RUNNING, ledger.current?.status)
        assertTrue(ledger.current!!.codes.isEmpty())
    }

    @Test fun `a retry finishing after a new diagnostic session changes neither screen nor export`() {
        val (ledger, view, t) = scanned()
        val before = view.result
        ledger.newSession()
        assertFalse(view.retryFinished(t, DtcModuleRetry("BCM", listOf(code("BCM", "B1234")), ok), 5L))
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

    // ── Publication gate (acceptance corrections): screen, export record and header count move together ──

    private class Count { var value = 7; var writes = 0 }
    private fun gated(count: Count): Triple<DtcScanLedger, DtcScanView, DtcScanLedger.Ticket> {
        val ledger = DtcScanLedger().apply { newSession() }
        val view = DtcScanView(ledger) { count.value = it; count.writes++ }
        val t = ledger.begin("manual", modules, 1L)
        view.begin(t)
        return Triple(ledger, view, t)
    }
    private val found = DtcScanResult(listOf(code("PCM", "P0001"), code("BCM", "B1234")), modules.associateWith { ok })

    @Test fun `full scan finishing after a new connection session publishes nothing`() {
        val count = Count()
        val (ledger, view, t) = gated(count)
        ledger.newSession()                                           // reconnect while the scan was pending
        assertFalse(view.finish(t, found, 5L))
        assertEquals(null, view.result)
        assertEquals(null, ledger.current)
        assertEquals(0, count.writes)
    }

    @Test fun `cancelled or interrupted full scan — a late completion publishes nothing`() {
        for (close in listOf<(DtcScanLedger, DtcScanLedger.Ticket) -> Unit>(
            { l, t -> l.cancel(t, 3L) }, { l, t -> l.interrupt(t, 3L, "DIAG screen closed") })) {
            val count = Count()
            val (ledger, view, t) = gated(count)
            close(ledger, t)
            val before = ledger.current
            assertFalse(view.finish(t, found, 5L))
            assertEquals(null, view.result)
            assertEquals(before, ledger.current)
            assertEquals(0, count.writes)
        }
    }

    @Test fun `an auto-scan that took ownership without view begin keeps its result — the manual finish is rejected`() = kotlinx.coroutines.test.runTest {
        val count = Count()
        val (ledger, view, manual) = gated(count)
        val autoResult = DtcScanResult(listOf(code("ABS", "C0001")), modules.associateWith { ok })
        val out = DtcScanView.autoScan(ledger, modules, { 4L }, scan = { autoResult }, deliver = { it() },
            publishCount = { count.value = it; count.writes++ })
        assertEquals(true, out?.second)
        assertEquals(1, count.value)
        assertFalse("the manual scan no longer owns the record", view.finish(manual, found, 6L))
        assertEquals(null, view.result)
        assertEquals(listOf("C0001"), ledger.current!!.codes.map { it.code })
        assertEquals("count is the auto-scan's", 1, count.value)
        assertEquals(1, count.writes)
    }

    @Test fun `auto-scan completing after a new session publishes neither record nor count`() = kotlinx.coroutines.test.runTest {
        val count = Count()
        val ledger = DtcScanLedger().apply { newSession() }
        val out = DtcScanView.autoScan(ledger, modules, { 4L }, scan = { ledger.newSession(); found }, deliver = { it() },
            publishCount = { count.value = it; count.writes++ })
        assertEquals(false, out?.second)
        assertEquals(null, ledger.current)
        assertEquals(0, count.writes)
    }

    @Test fun `auto-scan delivered only while the connection still owns it`() = kotlinx.coroutines.test.runTest {
        val count = Count()
        val ledger = DtcScanLedger().apply { newSession() }
        DtcScanView.autoScan(ledger, modules, { 4L }, scan = { found }, deliver = { /* link moved on: dropped */ },
            publishCount = { count.value = it; count.writes++ })
        assertEquals(0, count.writes)
    }

    @Test fun `an accepted full scan publishes screen, export and count together`() {
        val count = Count()
        val (ledger, view, t) = gated(count)
        assertTrue(view.finish(t, found, 5L))
        assertEquals(found, view.result)
        assertEquals(found.codes, ledger.current!!.codes)
        assertEquals(2, count.value)
    }

    // ── NEW / NOT FOUND: full identity + coverage (acceptance corrections) ──

    private fun prev(codes: List<DtcResult>, statuses: Map<String, ModuleScanStatus>) =
        com.openrs.dash.data.PreviousDtcScan(0L, codes, statuses)
    private fun ft(m: String, c: String, f: Int?) = DtcResult(m, c, "", DtcStatus.ACTIVE, failureType = f)

    @Test fun `two failure types of one base code are two faults`() {
        val all = modules.associateWith { ok }
        val d = DtcScanner.computeDiff(listOf(ft("PCM", "P0123", 0x09)), prev(listOf(ft("PCM", "P0123", 0x1C)), all), all)
        assertEquals(listOf("P0123-09"), d.newCodes.map { it.displayCode })
        assertEquals(listOf("P0123-1C"), d.goneCodes.map { it.displayCode })
        assertTrue(d.persistent.isEmpty())
    }

    @Test fun `a module the previous scan failed to read can't make a fault NEW or RESOLVED`() {
        val now = modules.associateWith { ok }
        val before = mapOf("PCM" to ok, "BCM" to ModuleScanStatus.TIMEOUT, "ABS" to ok)
        val d = DtcScanner.computeDiff(listOf(ft("BCM", "B1234", 0x01)), prev(emptyList(), before), now)
        assertTrue("BCM wasn't read last time — B1234 isn't known to be new", d.newCodes.isEmpty())
        assertEquals(setOf("PCM", "ABS"), d.comparedModules)
        val d2 = DtcScanner.computeDiff(emptyList(), prev(listOf(ft("BCM", "B1234", 0x01)), now), before)
        assertTrue("BCM not read now — B1234 isn't resolved", d2.goneCodes.isEmpty())
    }

    @Test fun `after a clean previous scan a current fault is NEW`() {
        val all = modules.associateWith { ok }
        val d = DtcScanner.computeDiff(listOf(ft("PCM", "P0420", 0x00)), prev(emptyList(), all), all)
        assertEquals(listOf("P0420-00"), d.newCodes.map { it.displayCode })
    }

    @Test fun `an older saved code without failure type matches by base code and says so`() {
        val all = modules.associateWith { ok }
        val d = DtcScanner.computeDiff(listOf(ft("PCM", "P0123", 0x1C)), prev(listOf(ft("PCM", "P0123", null)), all), all)
        assertTrue(d.newCodes.isEmpty()); assertTrue(d.goneCodes.isEmpty())
        assertTrue(d.baseCodeOnly)
    }

    @Test fun `no previous scan — nothing is compared`() {
        val d = DtcScanner.computeDiff(listOf(ft("PCM", "P0123", 1)), null, modules.associateWith { ok })
        assertTrue(d.newCodes.isEmpty() && d.goneCodes.isEmpty() && d.comparedModules.isEmpty())
    }

    @Test fun `a saved scan reads back with its coverage and failure types`() {
        val scan = com.openrs.dash.data.DtcScanEntity(id = 3, timestamp = 9L, moduleCount = 3, totalCodes = 1,
            moduleStatuses = """{"PCM":"OK","BCM":"TIMEOUT","ABS":"BOGUS"}""")
        val p = DtcScanner.previousFrom(scan, listOf(com.openrs.dash.data.DtcCodeEntity(scanId = 3, module = "PCM",
            code = "P0123", description = "", status = "ACTIVE", failureType = 0x1C)))
        assertEquals(mapOf("PCM" to ok, "BCM" to ModuleScanStatus.TIMEOUT), p.moduleStatuses)
        assertEquals("P0123-1C", p.codes.single().displayCode)
    }

    @Test fun `export carries the full fault identity, base code kept for lookups`() {
        val j = org.json.JSONObject(com.openrs.dash.diagnostics.DriveExportBuilder.buildDtcJson(
            listOf(ft("BCM", "P0123", 0x1C), ft("PCM", "P0420", null))))
        val codes = j.getJSONArray("codes")
        assertEquals("P0123", codes.getJSONObject(0).getString("code"))
        assertEquals("1C", codes.getJSONObject(0).getString("failureType"))
        assertEquals("P0123-1C", codes.getJSONObject(0).getString("fullCode"))
        assertFalse("unknown failure type isn't invented", codes.getJSONObject(1).has("failureType"))
        assertTrue(com.openrs.dash.diagnostics.DriveExportBuilder.buildDtcText(listOf(ft("BCM", "P0123", 0x1C))).contains("P0123-1C"))
    }
}
