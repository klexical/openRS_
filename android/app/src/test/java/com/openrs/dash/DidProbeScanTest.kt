package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.DidProbeEnd
import com.openrs.dash.diagnostics.probeCsv
import com.openrs.dash.diagnostics.runDidProbeScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DidProbeScanTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @Test fun `ordinary no reply and a negative ECU reply still finish a scan`() = runTest {
        val dids = listOf(0x0100, 0x0101)
        val shown = mutableListOf<DiagnosticLogger.ProbeResult>()
        var writes = 0
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, dids, { _, _, _ ->
            if (++writes == 1) null else com.openrs.dash.can.RawReply.Frame(byteArrayOf(3, 0x7F, 0x22, 0x31))
        }, { false }) { shown.add(it) }
        assertEquals(DidProbeEnd.Complete, end)
        assertEquals(listOf("TIMEOUT", "NRC"), shown.map { it.status })
        assertEquals(shown, DiagnosticLogger.probeSessions.single().results)
    }

    @Test fun `no active transport fails the first unsent DID instead of exporting timeouts`() = runTest {
        val conn = SlcanConnection(transportFactory = { error("must not connect") })
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, listOf(1, 2), conn::sendRawQuery, { false }) {
            error("unsent query must not produce a result")
        }
        assertEquals(DidProbeEnd.Failed(1, "IOException: Adapter disconnected", sent = false), end)
        assertTrue(DiagnosticLogger.probeSessions.isEmpty())
    }

    @Test fun `STOP during an in-flight read drops the late result and never reports completion`() = runTest {
        var stopped = false
        var writes = 0
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, listOf(1, 2), { _, _, _ ->
            writes++
            stopped = true
            null
        }, { stopped }) { error("late result") }
        assertEquals(DidProbeEnd.Stopped, end)
        assertEquals(1, writes)
        assertTrue(DiagnosticLogger.probeSessions.isEmpty())
    }

    @Test fun `cancelling an in-flight scan propagates instead of returning failure or completion`() = runTest {
        val writing = CompletableDeferred<Unit>()
        var cancelled = false
        var returned: DidProbeEnd? = null
        val scan = launch {
            try {
                returned = runDidProbeScan("PCM", 0x7E0, 0x7E8, listOf(1, 2), { _, _, _ ->
                    writing.complete(Unit)
                    awaitCancellation()
                }, { false }) { error("cancelled query produced a result") }
            } catch (e: CancellationException) {
                cancelled = true
                throw e
            }
        }
        writing.await()
        scan.cancelAndJoin()
        assertTrue(cancelled)
        assertEquals(null, returned)
        assertTrue(DiagnosticLogger.probeSessions.isEmpty())
        assertTrue(DiagnosticLogger.sessionEvents.none { it.type == "PROBE" })
    }

    @Test fun `STOP after one result keeps it as a STOPPED session that every export labels partial`() = runTest {
        var stop = false
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, listOf(0x0100, 0x0101, 0x0102), { _, _, _ ->
            com.openrs.dash.can.RawReply.Frame(byteArrayOf(5, 0x62, 0x01, 0x00, 0x2A))
        }, { stop }) { stop = true }   // the user taps STOP once the first result is shown
        assertEquals(DidProbeEnd.Stopped, end)
        val ps = DiagnosticLogger.probeSessions.single()
        assertEquals(DiagnosticLogger.PROBE_STOPPED, ps.outcome)
        assertEquals(3, ps.planned)
        assertEquals(listOf(DiagnosticLogger.ProbeResult(0x0100, "FOUND", "2A")), ps.results)
        assertTrue(DiagnosticLogger.sessionEvents.any { it.type == "PROBE" && it.message == "PCM scan stopped: 1 of 3 probed, 1 found" })
        assertTrue(DiagnosticLogger.sessionEvents.none { it.message.contains("scan complete") })

        val text = DiagnosticReportBuilder.buildSummaryText("t")
        assertTrue(text, text.contains("STOPPED — 1 of 3 probed — 1 found"))
        val json = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONArray("probeResults").getJSONObject(0)
        assertEquals("STOPPED", json.getString("outcome"))
        assertEquals(3, json.getInt("planned"))
        assertEquals(1, json.getInt("totalProbed"))   // the old field keeps its meaning
        assertEquals("# outcome=STOPPED probed=1 planned=3\nDID,Status,ResponseHex\n0x0100,FOUND,2A\n", probeCsv(ps))
    }

    @Test fun `a complete scan exports exactly as before`() = runTest {
        runDidProbeScan("PCM", 0x7E0, 0x7E8, listOf(0x0100), { _, _, _ -> com.openrs.dash.can.RawReply.Frame(byteArrayOf(5, 0x62, 0x01, 0x00, 0x2A)) }, { false }) {}
        val ps = DiagnosticLogger.probeSessions.single()
        assertEquals(DiagnosticLogger.PROBE_COMPLETE, ps.outcome)
        assertEquals("DID,Status,ResponseHex\n0x0100,FOUND,2A\n", probeCsv(ps))
        val text = DiagnosticReportBuilder.buildSummaryText("t")
        assertTrue(text, text.contains("    1 probed — 1 found, 0 rejected, 0 timeout"))
        assertTrue(DiagnosticLogger.sessionEvents.any { it.message == "PCM scan complete: 1 probed, 1 found" })
    }

    @Test fun `a non-IO failure from the query is a failed scan, not an exception out of the UI scope`() = runTest {
        var calls = 0
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, listOf(1, 2, 3), { _, _, _ ->
            if (++calls == 1) com.openrs.dash.can.RawReply.Frame(byteArrayOf(3, 0x7F, 0x22, 0x31)) else throw IllegalStateException("boom")
        }, { false }) {}
        assertEquals(DidProbeEnd.Failed(2, "IllegalStateException: boom"), end)
        assertEquals(2, calls)
        val ps = DiagnosticLogger.probeSessions.single()
        assertEquals(DiagnosticLogger.PROBE_FAILED, ps.outcome)
        assertEquals(1, ps.results.size)
        assertFalse(DiagnosticLogger.sessionEvents.any { it.message.contains("scan stopped:") })   // one failure event only
        assertEquals(1, DiagnosticLogger.sessionEvents.count { it.type == "PROBE" })
    }
}
