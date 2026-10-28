package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.DtcModuleSpec
import com.openrs.dash.data.DtcReply
import com.openrs.dash.data.DtcScanRecord
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.DtcScanStatus
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DtcScanner
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DTC scan through the scan-producing path (#215): a live [SlcanConnection] over a scripted adapter, the real
 * [SlcanConnection.performDtcScan] receiver (ISO-TP, pending handling, deadlines) and [DtcScanner.runScan]'s
 * interpretation — the same path GARAGE → DIAG, the auto-scan and module retries use. Only a valid positive reply
 * makes a module OK; a refusal, malformed, pending-only or missing reply can never become a clean scan.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DtcScanPathTest {

    /** One scripted reply line, sent [afterMs] after the module's 0x19 02 request arrives. */
    private data class Reply(val line: String, val afterMs: Long = 20L)

    /** SLCAN single frame: id + PCI (payload length) + payload, padded to 8 bytes. */
    private fun sf(id: Int, vararg payload: Int): String {
        val bytes = (listOf(payload.size) + payload.toList()).let { it + List(8 - it.size) { 0 } }
        return "t%03X8".format(id) + bytes.joinToString("") { "%02X".format(it) }
    }

    /** Adapter that answers each module's scan request with its script; everything else (pollers) is ignored. */
    private class ScriptedAdapter(
        private val scope: CoroutineScope,
        private val scripts: Map<Int, List<Reply>>,          // request id → replies
    ) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        val requests = mutableListOf<Int>()
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun writeLine(frame: String) {
            if (frame.length >= 13 && frame.substring(5, 13) == "031902FF") {
                val req = frame.substring(1, 4).toInt(16)
                requests += req
                scripts[req]?.let { replies ->
                    scope.launch { for (r in replies) { delay(r.afterMs); lines.send(r.line) } }
                }
            }
        }
        override fun close() { lines.close() }
        override val label = "scripted"
        override val stockFirmwareLabel = "scripted fw"
    }

    private suspend fun TestScope.scan(
        modules: List<DtcModuleSpec>, scripts: Map<Int, List<Reply>>,
    ): Pair<DtcScanResult, Map<String, ModuleScanStatus?>> {
        val adapter = ScriptedAdapter(backgroundScope, scripts)
        val conn = SlcanConnection(
            transportFactory = { adapter }, maxRetries = 1, ioDispatcher = StandardTestDispatcher(testScheduler),
            debugSink = {},
        )
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(100L)                                            // handshake → connected
        val progress = LinkedHashMap<String, ModuleScanStatus?>()
        val result = DtcScanner.runScan(conn, modules) { m, s -> progress[m] = s }
        return result to progress
    }

    private val all = DtcScanner.MODULES
    private fun spec(name: String) = all.first { it.name == name }

    @Test fun `every module in one scan — only valid positive replies count as scanned`() = runTest {
        val (r, progress) = scan(all, mapOf(
            0x7E0 to listOf(Reply(sf(0x7E8, 0x59, 0x02, 0xFF))),                     // PCM: valid, zero codes
            0x726 to listOf(Reply(sf(0x72E, 0x59, 0x02, 0xFF, 0x01, 0x23, 0x01))),   // BCM: valid, P0123 active
            0x760 to listOf(Reply(sf(0x768, 0x7F, 0x19, 0x12))),                     // ABS: refused (NRC 0x12)
            0x703 to listOf(Reply(sf(0x70B, 0x59, 0x02))),                           // AWD: truncated
            0x730 to listOf(Reply(sf(0x738, 0x7F, 0x19, 0x78)),                      // PSCM: pending, then valid
                            Reply(sf(0x738, 0x59, 0x02, 0xFF), afterMs = 2_000L)),
            // GFM: no reply at all
        ))
        assertEquals(ModuleScanStatus.OK, r.moduleStatuses["PCM"])
        assertEquals(ModuleScanStatus.OK, r.moduleStatuses["BCM"])
        assertEquals(ModuleScanStatus.REJECTED, r.moduleStatuses["ABS"])
        assertEquals(ModuleScanStatus.MALFORMED, r.moduleStatuses["AWD"])
        assertEquals(ModuleScanStatus.OK, r.moduleStatuses["PSCM"])
        assertEquals(ModuleScanStatus.TIMEOUT, r.moduleStatuses["GFM"])
        assertEquals(listOf("P0123"), r.codes.map { it.code })
        // The live progress the screen shows agrees with the result
        assertEquals(r.moduleStatuses, progress)
        val record = DtcScanRecord.from(r, 1L, "manual", all.map { it.name })
        assertEquals(DtcScanStatus.PARTIAL, record.status)
        assertEquals(3, record.modulesAnswered)
    }

    @Test fun `all modules valid with zero records — COMPLETE, no codes`() = runTest {
        val (r, _) = scan(all, all.associate { it.requestId to listOf(Reply(sf(it.responseId, 0x59, 0x02, 0xFF))) })
        assertTrue(r.moduleStatuses.values.all { it == ModuleScanStatus.OK })
        assertTrue(r.codes.isEmpty())
        assertEquals(DtcScanStatus.COMPLETE, DtcScanRecord.from(r, 1L, "auto", all.map { it.name }).status)
    }

    @Test fun `one negative response (7F 19 12) among clean modules is not COMPLETE — no codes`() = runTest {
        val (r, _) = scan(all, all.associate {
            it.requestId to listOf(Reply(
                if (it.name == "ABS") sf(it.responseId, 0x7F, 0x19, 0x12) else sf(it.responseId, 0x59, 0x02, 0xFF)))
        })
        assertEquals(ModuleScanStatus.REJECTED, r.moduleStatuses["ABS"])
        val record = DtcScanRecord.from(r, 1L, "manual", all.map { it.name })
        assertNotEquals(DtcScanStatus.COMPLETE, record.status)
        assertEquals(DtcScanStatus.PARTIAL, record.status)
    }

    @Test fun `codes found across a multi-frame reply are parsed`() = runTest {
        // 59 02 FF + 3 records = 12 bytes → First Frame + Consecutive Frame (scanner sends flow control)
        val ff = "t7E88" + "100C5902FF012301"          // FF, length 12: 59 02 FF | 01 23 01
        val cf = "t7E88" + "21C10008012308" + "00"      // CF 1: C1 00 08 | 01 23 08
        val (r, _) = scan(listOf(spec("PCM")), mapOf(0x7E0 to listOf(Reply(ff), Reply(cf, afterMs = 30L))))
        assertEquals(ModuleScanStatus.OK, r.moduleStatuses["PCM"])
        assertEquals(listOf("P0123", "U0100", "P0123"), r.codes.map { it.code })
    }

    @Test fun `pending with no final reply ends PENDING, never OK`() = runTest {
        val (r, progress) = scan(listOf(spec("PCM")), mapOf(0x7E0 to listOf(Reply(sf(0x7E8, 0x7F, 0x19, 0x78)))))
        assertEquals(ModuleScanStatus.PENDING, r.moduleStatuses["PCM"])
        assertEquals(ModuleScanStatus.PENDING, progress["PCM"])
        assertTrue(r.codes.isEmpty())
        assertEquals(DtcScanStatus.FAILED, DtcScanRecord.from(r, 1L, "manual", listOf("PCM")).status)
    }

    @Test fun `no reply ends TIMEOUT and the scan FAILED`() = runTest {
        val (r, _) = scan(listOf(spec("BCM")), emptyMap())
        assertEquals(ModuleScanStatus.TIMEOUT, r.moduleStatuses["BCM"])
        assertEquals(DtcScanStatus.FAILED, DtcScanRecord.from(r, 1L, "manual", listOf("BCM")).status)
    }

    @Test fun `malformed replies — wrong service, wrong sub-function, empty frame`() = runTest {
        val (r, _) = scan(listOf(spec("PCM"), spec("BCM"), spec("ABS")), mapOf(
            0x7E0 to listOf(Reply(sf(0x7E8, 0x62, 0x19, 0x02))),        // a Mode 22 reply
            0x726 to listOf(Reply(sf(0x72E, 0x59, 0x04, 0xFF))),        // positive, but for sub-function 04
            0x760 to listOf(Reply("t76880000000000000000")),            // SF length 0
        ))
        assertEquals(ModuleScanStatus.MALFORMED, r.moduleStatuses["PCM"])
        assertEquals(ModuleScanStatus.MALFORMED, r.moduleStatuses["BCM"])
        assertEquals(ModuleScanStatus.MALFORMED, r.moduleStatuses["ABS"])
        assertTrue(r.codes.isEmpty())
    }

    @Test fun `classifier — the reply forms`() {
        fun b(vararg x: Int) = ByteArray(x.size) { x[it].toByte() }
        assertEquals(DtcReply.Positive, DtcReply.classify(b(0x59, 0x02, 0xFF)))
        assertEquals(DtcReply.Negative(0x31), DtcReply.classify(b(0x7F, 0x19, 0x31)))
        assertEquals(DtcReply.Pending, DtcReply.classify(b(0x7F, 0x19, 0x78)))
        assertEquals(DtcReply.Malformed, DtcReply.classify(b(0x7F, 0x22, 0x31)))   // refusal of another service
        assertEquals(DtcReply.Malformed, DtcReply.classify(b()))
    }
}
