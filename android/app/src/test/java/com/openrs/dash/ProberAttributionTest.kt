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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * DID prober reply attribution (rc.8 "prober-attribution"). `sendRawQuery` opens a DTC-channel operation with no
 * expected service, so every frame from the response ID is diverted to it while it waits, and the first one is returned
 * whatever DID it answers. The PCM poller keeps polling the same ECU during a probe, so the probe can record a poll
 * reply as its own result, and that diverted poll reply never reaches the PcmRequestLedger — its request stays
 * outstanding, so later NRCs can't be attributed (#216 policy: no time releases a request).
 * Production SlcanConnection + poller, scripted ECU over a plain SlcanTransport (writes always succeed, so this is the
 * Wi-Fi path — over BLE on the WiCAN Pro the 22-byte probe write fails first, H-20). JVM, virtual time; no adapter.
 * The same diversion applies to any response ID the poller also queries (BCM / AWD); only the PCM is modelled here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProberAttributionTest {
    private companion object {
        /** Red at this commit (reproduces the defect; results in the rc.8 kickoff evidence) — remove with the fix. */
        const val DEFECT = "prober-attribution: defect reproduced, not fixed yet"
    }

    private val probeDid = 0xABCD

    /** Mode 22 ECU: poll DIDs answer after 40 ms per [poll]; the probed DID answers `62 ABCD BA` after 300 ms. */
    private class Ecu(private val scope: CoroutineScope, private val poll: (Int) -> String) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        val sent = mutableMapOf<Int, Int>()
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun writeLine(frame: String) {
            if (!frame.startsWith("t7E08") || frame.substring(5, 9) != "0322") return
            val did = frame.substring(9, 13).toInt(16)
            sent[did] = (sent[did] ?: 0) + 1
            val (line, afterMs) = if (did == 0xABCD) "t7E880462ABCDBA000000" to 300L else poll(did) to 40L
            scope.launch { delay(afterMs); lines.send(line) }
        }
        override fun close() { lines.close() }
        override val label = "scripted ECU"
        override val stockFirmwareLabel = "scripted"
    }

    private fun positive(did: Int) = "t7E880562%04X00000000".format(did).take(21)
    private fun nrc(code: Int) = "t7E88037F22%02X0000000000".format(code).take(21)

    @Before fun freshSession() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun TestScope.connect(ecu: Ecu): SlcanConnection {
        val conn = SlcanConnection(
            transportFactory = { ecu }, maxRetries = 1, rpmProvider = { 1_500.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(25_000L)                                   // connected, PCM fast poll running
        assertTrue("poller running", ecu.sent.keys.any { it != probeDid })
        return conn
    }

    private suspend fun probe(conn: SlcanConnection): List<DiagnosticLogger.ProbeResult> {
        val shown = mutableListOf<DiagnosticLogger.ProbeResult>()
        runDidProbeScan("PCM", 0x7E0, 0x7E8, listOf(probeDid), conn::sendRawQuery, { false }) { shown += it }
        return shown
    }

    @Ignore(DEFECT) @Test fun `a poll reply arriving during a probe is not recorded as the probed DID's answer`() = runTest {
        val conn = connect(Ecu(backgroundScope) { positive(it) })
        assertEquals(listOf(DiagnosticLogger.ProbeResult(probeDid, "FOUND", "BA 00 00 00")), probe(conn))
    }

    @Ignore(DEFECT) @Test fun `a poll NRC arriving during a probe is not recorded as the probed DID's rejection`() = runTest {
        var probing = false
        val conn = connect(Ecu(backgroundScope) { if (probing) nrc(0x22) else positive(it) })
        probing = true
        val shown = probe(conn)
        probing = false
        assertEquals(listOf(DiagnosticLogger.ProbeResult(probeDid, "FOUND", "BA 00 00 00")), shown)
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

    @Ignore(DEFECT) @Test fun `a probe leaves no poll request outstanding - a later rejection is still attributed`() = runTest {
        rejectionAfterProbe(withProbe = true)
        val unresolved = DiagnosticLogger.sessionEvents.filter { it.type == "NRC_UNATTRIBUTED" && it.message.contains("still unanswered") }
        assertEquals(unresolved.map { it.message }.toString(), 0, unresolved.size)
        assertTrue(DiagnosticLogger.nrcSuppressed.keys.toString(), 0x03ED in DiagnosticLogger.nrcSuppressed.keys)
    }
}
