package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #216 — the owner's 2026-10-31 pattern through the production poller: after one lost reply every 0x31 is unattributable
 * and was logged one line each. Lines are now folded (first example + ≤ 1 summary / minute per kind) while the session
 * total stays exact; polling, attribution and suppression are unchanged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NrcLogFloodTest {
    /** PCM: 0462's first request is lost; 03ED / 03EE always answer 7F 22 31; everything else answers positively. */
    private class Ecu(private val scope: CoroutineScope) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        val sent = mutableMapOf<Int, Int>()
        var nrcs = 0
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun writeLine(frame: String) {
            if (!frame.startsWith("t7E08") || frame.substring(5, 9) != "0322") return
            val did = frame.substring(9, 13).toInt(16)
            val n = (sent[did] ?: 0) + 1; sent[did] = n
            val line = when {
                did == 0x0462 && n == 1 -> return
                did == 0x03ED || did == 0x03EE -> { nrcs++; "t7E88037F2231000000000".take(21) }
                else -> "t7E88" + "%02X62%04X%s".format(5, did, "0000").padEnd(16, '0')
            }
            scope.launch { delay(40L); lines.trySend(line) }
        }
        override fun close() { lines.close() }
        override val label = "scripted ECU"; override val stockFirmwareLabel = "scripted"
    }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun TestScope.run(seconds: Long): Ecu {
        val ecu = Ecu(backgroundScope)
        val conn = SlcanConnection(transportFactory = { ecu }, maxRetries = 1, rpmProvider = { 1_500.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime })
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(seconds * 1_000L)
        return ecu
    }

    @Test fun `five minutes of unattributed NRCs log a handful of lines, not one per NRC`() = runTest {
        val ecu = run(300)
        val lines = DiagnosticLogger.sessionEvents.count { it.type == "NRC_UNATTRIBUTED" }
        assertTrue("NRCs ${ecu.nrcs}", ecu.nrcs > 100)
        assertTrue("lines $lines for ${ecu.nrcs} NRCs", lines <= 12)
    }

    @Test fun `the first line is the full example, totals are exact, and policy is unchanged`() = runTest {
        val ecu = run(300)
        val ev = DiagnosticLogger.sessionEvents.filter { it.type == "NRC_UNATTRIBUTED" }.map { it.message }
        assertTrue(ev.first(), ev.first().startsWith("NRC 0x31 (requestOutOfRange) — "))
        // One full example per kind (ambiguous at first, then unresolved after the lost reply), then summaries
        val firsts = ev.filter { !it.contains("more like this") }
        assertTrue(firsts.toString(), firsts.size <= 3 && firsts.any { it.contains("an earlier request is still unanswered") })
        assertTrue("compact waiting list: $firsts", firsts.none { Regex("(03ED, ){3}").containsMatchIn(it) })
        assertEquals(ecu.nrcs, DiagnosticLogger.nrcUnattributedTotal)
        val folded = ev.filter { it.contains("more like this") }.sumOf { Regex("\\+(\\d+) more like this").find(it)!!.groupValues[1].toInt() }
        assertTrue("logged + folded ≤ total", ev.size + folded <= ecu.nrcs)
        val json = org.json.JSONObject(com.openrs.dash.diagnostics.DiagnosticReportBuilder.buildDetailJson("t"))
        assertEquals(ecu.nrcs, json.getInt("nrcUnattributedTotal"))
        assertTrue(com.openrs.dash.diagnostics.DiagnosticReportBuilder.buildSummaryText("t").contains("${ecu.nrcs} this session"))
        // Policy untouched (NrcAttributionTest): nothing suppressed after the lost reply, 03ED still polled
        assertEquals(emptySet<Int>(), DiagnosticLogger.nrcSuppressed.keys)
        assertTrue((ecu.sent[0x03ED] ?: 0) >= 50)
    }

    @Test fun `compact waiting lists count repeats and stay bounded`() {
        assertEquals("03ED ×3, 03EE ×2, 033E", com.openrs.dash.can.UnattributedNrcLog.compact(
            listOf(0x03ED, 0x03EE, 0x033E, 0x03ED, 0x03EE, 0x03ED)))
        assertTrue(com.openrs.dash.can.UnattributedNrcLog.compact((1..20).toList(), max = 6).endsWith("+14 more"))
    }
}
