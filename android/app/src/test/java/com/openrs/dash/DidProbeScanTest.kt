package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DidProbeEnd
import com.openrs.dash.diagnostics.runDidProbeScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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
            if (++writes == 1) null else byteArrayOf(3, 0x7F, 0x22, 0x31)
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
        assertTrue(end is DidProbeEnd.Failed && end.did == 1)
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
}
