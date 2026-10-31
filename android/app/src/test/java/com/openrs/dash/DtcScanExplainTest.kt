package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.DtcModuleSpec
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.IOException

/**
 * #215 — a partial DTC scan explains itself: a write that failed is recorded with module, stage and sanitized reason;
 * live progress, the result, the ledger record and the exports agree (one rule for "no final reply"); a failed flow
 * control is ERROR, never "sent"; every scan reply is in the private raw log exactly once and the app's own requests
 * are `# TX` lines. Live SlcanConnection over a scripted adapter (same path as GARAGE → DIAG). No clears.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DtcScanExplainTest {
    private class Adapter(private val scope: CoroutineScope, private val scripts: Map<Int, List<Pair<String, Long>>>,
                          private val failWrites: (String) -> Boolean) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun writeLine(frame: String) {
            if (failWrites(frame)) throw IOException("BLE write couldn't be sent (stack busy or not connected)")
            if (frame.length >= 13 && frame.substring(5, 13) == "031902FF") {
                val req = frame.substring(1, 4).toInt(16)
                scripts[req]?.let { r -> scope.launch { for ((l, after) in r) { delay(after); lines.trySend(l) } } }
            }
        }
        override fun close() { lines.close() }
        override val label = "scripted"; override val stockFirmwareLabel = "scripted fw"
    }
    private val dir = File(System.getProperty("java.io.tmpdir"), "dtc-explain-${System.nanoTime()}")
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = dir) }

    private suspend fun TestScope.scan(mods: List<DtcModuleSpec>, scripts: Map<Int, List<Pair<String, Long>>>,
                                       fail: (String) -> Boolean = { false }): Pair<DtcScanResult, Map<String, ModuleScanStatus?>> {
        val a = Adapter(backgroundScope, scripts, fail)
        val conn = SlcanConnection(transportFactory = { a }, maxRetries = 1, ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {})
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(100L)
        val progress = LinkedHashMap<String, ModuleScanStatus?>()
        val r = DtcScanner.runScan(conn, mods) { m, s -> progress[m] = s }
        return r to progress
    }
    private fun spec(n: String) = DtcScanner.MODULES.first { it.name == n }
    private fun log(): String { DiagnosticLogger.flushSlcan(); return DiagnosticLogger.slcanLogFile!!.readText() }

    @Test fun `a request write failure is ERROR in progress and in the result`() = runTest {
        val (r, progress) = scan(listOf(spec("PCM"), spec("BCM")), mapOf(0x726 to listOf("t72E8035902FF00000000" to 20L)),
            fail = { it.startsWith("t7E08031902FF") })
        assertEquals(ModuleScanStatus.ERROR, r.moduleStatuses["PCM"])
        assertEquals("progress vs final", r.moduleStatuses["PCM"], progress["PCM"])
    }

    @Test fun `a failed flow-control write is ERROR, not a timeout`() = runTest {
        val ff = "t7E88" + "100B5902FF012300"
        val (r, progress) = scan(listOf(spec("PCM")), mapOf(0x7E0 to listOf(ff to 20L)), fail = { it.startsWith("t7E08300") })
        assertEquals(ModuleScanStatus.ERROR, r.moduleStatuses["PCM"])
        assertEquals(r.moduleStatuses["PCM"], progress["PCM"])
    }

    @Test fun `scan replies reach the raw log once and requests are marked TX`() = runTest {
        val ff = "t7E88" + "100B5902FF012300"; val cf = "t7E88" + "2101C1000008" + "0000"
        scan(listOf(spec("PCM")), mapOf(0x7E0 to listOf(ff to 20L, cf to 30L)))
        val l = log()
        assertEquals(l, 1, Regex("7E8#100B5902FF012300").findAll(l).count())
        assertEquals(l, 1, Regex("7E8#2101C10000080000").findAll(l).count())
        assertTrue(l, l.contains("# TX") && l.contains("7E0#031902FF00000000"))
        assertTrue(l, Regex("# TX .*7E0#3000").containsMatchIn(l))
    }

    private fun record(r: DtcScanResult, mods: List<DtcModuleSpec>) =
        com.openrs.dash.data.DtcScanRecord.from(r, 1L, "manual", mods.map { it.name })

    @Test fun `the failed write is recorded with stage and reason, and every export says so`() = runTest {
        val mods = listOf(spec("PCM"), spec("BCM"))
        val (r, _) = scan(mods, mapOf(0x726 to listOf("t72E8035902FF00000000" to 20L)), fail = { it.startsWith("t7E08031902FF") })
        val note = r.moduleNotes["PCM"]!!
        assertTrue(note, note.startsWith("0x19 02 request not sent — IOException: BLE write couldn't be sent"))
        assertEquals("only a module without a valid reply gets a note", setOf("PCM"), r.moduleNotes.keys)
        val rec = record(r, mods)
        val json = com.openrs.dash.diagnostics.DiagnosticReportBuilder.dtcScanJson(rec)
        assertEquals("ERROR", json.getJSONObject("modules").getString("PCM"))
        assertEquals(note, json.getJSONObject("moduleNotes").getString("PCM"))
        val text = com.openrs.dash.diagnostics.DriveExportBuilder.buildDtcText(rec.codes, rec)
        assertTrue(text, text.contains("PCM: $note"))
        assertTrue(DiagnosticLogger.sessionEvents.any { it.type == "DTC" && it.message == "PCM: $note" })
    }

    @Test fun `a genuine timeout stays TIMEOUT with no note, in progress and result`() = runTest {
        val (r, progress) = scan(listOf(spec("ABS")), emptyMap())
        assertEquals(ModuleScanStatus.TIMEOUT, r.moduleStatuses["ABS"])
        assertEquals(ModuleScanStatus.TIMEOUT, progress["ABS"])
        assertTrue(r.moduleNotes.isEmpty())
    }

    @Test fun `a negative reply is REJECTED and logged once in the raw log`() = runTest {
        val (r, progress) = scan(listOf(spec("ABS")), mapOf(0x760 to listOf("t7688037F1931000000000" to 20L)))
        assertEquals(ModuleScanStatus.REJECTED, r.moduleStatuses["ABS"])
        assertEquals(ModuleScanStatus.REJECTED, progress["ABS"])
        assertEquals(1, Regex("768#037F193100000000").findAll(log()).count())
        assertTrue(r.moduleNotes.isEmpty())
    }

    @Test fun `a failed flow control is noted with its stage and never marked sent`() = runTest {
        val ff = "t7E88" + "100B5902FF012300"
        var fcTries = 0
        val (r, _) = scan(listOf(spec("PCM")), mapOf(0x7E0 to listOf(ff to 20L)),
            fail = { if (it.startsWith("t7E08300")) { fcTries++; true } else false })
        assertEquals(ModuleScanStatus.ERROR, r.moduleStatuses["PCM"])
        assertTrue(r.moduleNotes["PCM"]!!, r.moduleNotes["PCM"]!!.startsWith("flow control not sent — IOException"))
        assertEquals(1, fcTries)
        assertTrue("a failed write is not a TX line", !Regex("# TX .*7E0#3000").containsMatchIn(log()))
    }
}
