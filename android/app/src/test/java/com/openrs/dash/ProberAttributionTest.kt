package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.runDidProbeScan
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * DID prober reply attribution (rc.8 "prober-attribution", #250). Until the fix `sendRawQuery` opened a DTC-channel
 * operation with no expected service, so every frame from the response ID was diverted to it while it waited and the
 * first one was returned whatever DID it answered. The PCM poller keeps polling the same ECU during a probe, so the probe
 * recorded a poll reply / NRC as its own result, and the diverted poll reply never reached the PcmRequestLedger — its
 * request stayed outstanding, so later NRCs couldn't be attributed (#216 policy: no time releases a request).
 * Fixed contract: a probe reply is matched by module (response ID) + service (0x62) + DID; a `7F 22` goes to the probe
 * only when the ledger attributes it to the probe DID — while another request could own it, it is charged to nobody;
 * the probe request lives in the module's ledger (e1 decision "B"), so a probe DID that never answers lapses like any
 * unanswered request and NRC suppression stays off until the next connection — losing suppression, never misattributing.
 * Production SlcanConnection + pollers, scripted ECU over a plain SlcanTransport (writes always succeed — the Wi-Fi path;
 * BLE outcomes are BleWriteOutcomeTest). JVM, virtual time; no adapter.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProberAttributionTest {
    private val probeDid = 0xABCD
    private fun isProbe(did: Int) = did in 0xAB00..0xABFF

    /**
     * Mode 22 ECU behind 0x7E0 / 0x726: poll DIDs answer after 40 ms per [poll] (null = no reply); probe DIDs (0xABxx)
     * answer after 300 ms per [probeReply] (null = no reply). [onProbe] runs when a probe request arrives.
     */
    private class Ecu(
        private val scope: CoroutineScope,
        private val probeReply: (id: String, did: Int) -> String? = { id, _ -> "t${resp(id)}80462ABCDBA000000" },
        private val onProbe: (Ecu, Int) -> Unit = { _, _ -> },
        private val poll: (Int) -> String?,
    ) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        val sent = mutableMapOf<Int, Int>()
        val writes = mutableListOf<String>()
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receiveCatching().getOrNull()
        override suspend fun writeLine(frame: String) {
            writes += frame
            val id = frame.drop(1).take(3)
            if (id != "7E0" && id != "726") return
            if (frame.length < 13 || frame.substring(4, 9) != "80322") return
            val did = frame.substring(9, 13).toInt(16)
            sent[did] = (sent[did] ?: 0) + 1
            val (line, afterMs) = if (did in 0xAB00..0xABFF) { onProbe(this, did); probeReply(id, did) to 300L }
                else if (id == "7E0") poll(did) to 40L else "t72E80562%04X00000000".format(did).take(21) to 40L
            if (line != null) scope.launch { delay(afterMs); lines.trySend(line) }
        }
        fun inject(line: String, afterMs: Long) { scope.launch { delay(afterMs); lines.trySend(line) } }
        /** EOF: the connection drops and reconnects with the next transport. */
        fun drop() { lines.close() }
        override fun close() { lines.close() }
        override val label = "scripted ECU"
        override val stockFirmwareLabel = "scripted"
        companion object { fun resp(id: String) = if (id == "726") "72E" else "7E8" }
    }

    private fun positive(did: Int) = "t7E880562%04X00000000".format(did).take(21)
    private fun nrc(code: Int) = "t7E88037F22%02X0000000000".format(code).take(21)

    @Before fun freshSession() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun TestScope.connect(vararg ecus: Ecu, rpm: Double = 1_500.0): SlcanConnection {
        val queue = ArrayDeque(ecus.toList())
        val conn = SlcanConnection(
            transportFactory = { queue.removeFirst() }, maxRetries = 1, rpmProvider = { rpm },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(25_000L)                                   // connected, PCM poll running
        assertTrue("poller running", ecus.first().sent.keys.any { !isProbe(it) })
        return conn
    }

    private suspend fun probe(conn: SlcanConnection, dids: List<Int> = listOf(probeDid), req: Int = 0x7E0,
                              resp: Int = 0x7E8): List<DiagnosticLogger.ProbeResult> {
        val shown = mutableListOf<DiagnosticLogger.ProbeResult>()
        runDidProbeScan(if (req == 0x7E0) "PCM" else "BCM", req, resp, dids, conn::sendRawQuery, { false }) { shown += it }
        return shown
    }

    private val found = DiagnosticLogger.ProbeResult(0xABCD, "FOUND", "BA 00 00 00")
    private fun unattributed() = DiagnosticLogger.sessionEvents.filter { it.type == "NRC_UNATTRIBUTED" }.map { it.message }
    private fun suppressEvents() = DiagnosticLogger.sessionEvents.filter { it.type == "NRC_SUPPRESS" }.map { it.message }

    @Test fun `a poll reply arriving during a probe is not recorded as the probed DID's answer`() = runTest {
        val conn = connect(Ecu(backgroundScope) { positive(it) })
        assertEquals(listOf(found), probe(conn))
    }

    @Test fun `a poll NRC arriving during a probe is not recorded as the probed DID's rejection`() = runTest {
        var probing = false
        val conn = connect(Ecu(backgroundScope) { if (probing) nrc(0x22) else positive(it) })
        probing = true
        val shown = probe(conn)
        probing = false
        assertEquals(listOf(found), shown)
    }

    @Test fun `an ambiguous 7F 22 during a probe is charged to nobody - neither the probe nor a poll DID`() = runTest {
        var probing = false
        val conn = connect(Ecu(backgroundScope) { if (probing) nrc(0x22) else positive(it) })
        probing = true
        val shown = probe(conn)
        probing = false
        assertEquals(listOf(found), shown)
        assertTrue(suppressEvents().toString(), suppressEvents().isEmpty())
        assertTrue(unattributed().toString(), unattributed().any { it.contains("ambiguous") })
    }

    /** After the probe 03ED starts rejecting every request; with no other request unanswered it is suppressed (#216). */
    private fun TestScope.rejectionAfterProbe(withProbe: Boolean) {
        var rejecting = false
        val conn = connect(Ecu(backgroundScope) { if (rejecting && it == 0x03ED) nrc(0x31) else positive(it) })
        if (withProbe) backgroundScope.launch { probe(conn) }
        advanceTimeBy(2_000L)
        rejecting = true
        advanceTimeBy(30_000L)
    }

    @Test fun `control - without a probe a DID that starts rejecting is suppressed`() = runTest {
        rejectionAfterProbe(withProbe = false)
        assertTrue(DiagnosticLogger.nrcSuppressed.keys.toString(), 0x03ED in DiagnosticLogger.nrcSuppressed.keys)
    }

    // Case 3 split (rc.8 reliability pass): each invariant fails on its own — the first assertion no longer hides the second.
    @Test fun `a probe leaves no poll request outstanding - no later rejection is left unattributed`() = runTest {
        rejectionAfterProbe(withProbe = true)
        val unresolved = unattributed().filter { it.contains("still unanswered") }
        assertEquals(unresolved.toString(), 0, unresolved.size)
    }

    @Test fun `a DID that starts rejecting after a probe is still suppressed`() = runTest {
        rejectionAfterProbe(withProbe = true)
        assertTrue(DiagnosticLogger.nrcSuppressed.keys.toString(), 0x03ED in DiagnosticLogger.nrcSuppressed.keys)
    }

    @Test fun `a probe DID that never answers - suppression stays off until reconnect, no NRC charged to any DID`() = runTest {
        var rejecting = false
        val poll: (Int) -> String? = { if (rejecting && it == 0x03ED) nrc(0x31) else positive(it) }
        val first = Ecu(backgroundScope, probeReply = { _, _ -> null }, poll = poll)
        val second = Ecu(backgroundScope, poll = poll)
        val conn = connect(first, second)
        assertEquals(listOf(DiagnosticLogger.ProbeResult(0xABCD, "TIMEOUT", "")), probe(conn))
        rejecting = true
        advanceTimeBy(30_000L)
        // B: the unanswered probe request may still own any later NRC — nothing is charged, nothing suppressed
        assertTrue(suppressEvents().toString(), suppressEvents().isEmpty())
        assertFalse(DiagnosticLogger.nrcSuppressed.keys.toString(), 0x03ED in DiagnosticLogger.nrcSuppressed.keys)
        // until it lapses the probe request is "waiting" (ambiguous), afterwards "still unanswered" — ABCD is a possible
        // owner of every NRC either way
        assertTrue(unattributed().toString(), unattributed().isNotEmpty() &&
            unattributed().all { (it.contains("still unanswered") || it.contains("ambiguous")) && it.contains("ABCD") })
        assertTrue(unattributed().toString(), unattributed().any { it.contains("still unanswered") })
        // a new connection has a new ledger: attribution resumes
        first.drop()
        advanceTimeBy(60_000L)
        assertTrue("reconnected", second.sent.isNotEmpty())
        assertTrue(suppressEvents().toString(), suppressEvents().any { it.contains("0x03ED") })
        assertTrue(suppressEvents().toString(), suppressEvents().all { it.contains("0x03ED") })
    }

    @Test fun `a probe NRC is recorded when the probe is the only request waiting`() = runTest {
        // Engine off: after the sweep the PCM poller idles 30 s, so nothing else is waiting when the probe NRC arrives
        val conn = connect(Ecu(backgroundScope, probeReply = { _, _ -> nrc(0x31) }) { positive(it) }, rpm = 0.0)
        val shown = probe(conn)
        assertEquals(listOf(DiagnosticLogger.ProbeResult(0xABCD, "NRC", "NRC 0x31 — request out of range")), shown)
        assertTrue(suppressEvents().toString(), suppressEvents().isEmpty())     // a probe DID isn't a poll DID
    }

    @Test fun `a multi-frame probe reply is matched by its DID`() = runTest {
        val conn = connect(Ecu(backgroundScope, probeReply = { _, _ -> "t7E88100A62ABCD010203" }) { positive(it) })
        assertEquals(listOf(DiagnosticLogger.ProbeResult(0xABCD, "FOUND", "01 02 03")), probe(conn))
    }

    @Test fun `control - a cancelled probe leaves the ledger clean once its late reply arrives`() = runTest {
        var rejecting = false
        val conn = connect(Ecu(backgroundScope) { if (rejecting && it == 0x03ED) nrc(0x31) else positive(it) })
        val job = backgroundScope.launch { probe(conn) }
        advanceTimeBy(20L)
        job.cancel()                                             // the probe's own reply still arrives at +300 ms
        advanceTimeBy(2_000L)
        rejecting = true
        advanceTimeBy(30_000L)
        assertTrue(unattributed().toString(), unattributed().none { it.contains("still unanswered") })
        assertTrue(DiagnosticLogger.nrcSuppressed.keys.toString(), 0x03ED in DiagnosticLogger.nrcSuppressed.keys)
    }

    @Test fun `a BCM probe keeps its own reply while a BCM poll reply arrives`() = runTest {
        val bcmPollDid = com.openrs.dash.can.ObdConstants.extractDid(com.openrs.dash.can.ObdConstants.BCM_QUERIES.first())!!
        val ecu = Ecu(backgroundScope, onProbe = { e, _ -> e.inject("t72E80562%04X01000000".format(bcmPollDid).take(21), 100L) }) {
            positive(it)
        }
        val conn = connect(ecu)
        val shown = probe(conn, req = 0x726, resp = 0x72E)
        assertEquals(listOf(found), shown)
    }
}
