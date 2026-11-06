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
        // rc.8 #251: the line carried one extra hex digit after its DLC-8 data (17 characters) — only parsed while
        // trailing characters were ignored; the logged frame below always said the 16-character line was meant
        val (r, progress) = scan(listOf(spec("ABS")), mapOf(0x760 to listOf("t7688037F193100000000" to 20L)))
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

    // ── Source review 2026-10-31: a known flow-control failure after "response pending" ──

    private val pendingPcm = "t7E88037F197800000000"
    private val ffPcm = "t7E88" + "100B5902FF012300"

    @Test fun `pending, then a first frame whose flow control fails — ERROR with the reason in progress, result and exports`() = runTest {
        val mods = listOf(spec("PCM"))
        val (r, progress) = scan(mods, mapOf(0x7E0 to listOf(pendingPcm to 20L, ffPcm to 100L)), fail = { it.startsWith("t7E08300") })
        assertEquals(ModuleScanStatus.ERROR, r.moduleStatuses["PCM"])
        assertEquals("progress vs final", r.moduleStatuses["PCM"], progress["PCM"])
        val note = r.moduleNotes["PCM"]
        assertTrue("$note", note != null && note.startsWith("flow control not sent — IOException"))
        val rec = record(r, mods)
        val json = com.openrs.dash.diagnostics.DiagnosticReportBuilder.dtcScanJson(rec)
        assertEquals("ERROR", json.getJSONObject("modules").getString("PCM"))
        assertEquals(note, json.getJSONObject("moduleNotes").getString("PCM"))
        val text = com.openrs.dash.diagnostics.DriveExportBuilder.buildDtcText(rec.codes, rec)
        assertTrue(text, text.contains("PCM: $note"))
    }

    @Test fun `pending only stays PENDING with no note`() = runTest {
        val (r, progress) = scan(listOf(spec("PCM")), mapOf(0x7E0 to listOf(pendingPcm to 20L)))
        assertEquals(ModuleScanStatus.PENDING, r.moduleStatuses["PCM"])
        assertEquals(ModuleScanStatus.PENDING, progress["PCM"])
        assertTrue(r.moduleNotes.isEmpty())
    }

    @Test fun `pending, then a complete multi-frame reply is OK with its code and no note`() = runTest {
        val cf = "t7E88" + "2101C1000008" + "0000"
        val (r, progress) = scan(listOf(spec("PCM")), mapOf(0x7E0 to listOf(pendingPcm to 20L, ffPcm to 100L, cf to 30L)))
        assertEquals(ModuleScanStatus.OK, r.moduleStatuses["PCM"])
        assertEquals(ModuleScanStatus.OK, progress["PCM"])
        assertEquals("two 4-byte records (01 23 00 01, C1 00 00 08)", 2, r.codes.size)
        assertTrue(r.moduleNotes.isEmpty())
    }

    @Test fun `an extended-session failure followed by pending stays PENDING, and the later flow-control failure wins`() = runTest {
        // AWD needs the extended session: that write fails, the module still answers "pending" (the request reached it)
        val awdPending = "t70B8037F197800000000"
        val (onlyPending, _) = scan(listOf(spec("AWD")), mapOf(0x703 to listOf(awdPending to 20L)),
            fail = { it.startsWith("t70380210") })
        assertEquals(ModuleScanStatus.PENDING, onlyPending.moduleStatuses["AWD"])
        assertTrue("no session-write note beside PENDING", onlyPending.moduleNotes.isEmpty())
        session()
        val awdFf = "t70B8" + "100B5902FF012300"
        val (r, progress) = scan(listOf(spec("AWD")), mapOf(0x703 to listOf(awdPending to 20L, awdFf to 100L)),
            fail = { it.startsWith("t70380210") || it.startsWith("t7038300") })
        assertEquals(ModuleScanStatus.ERROR, r.moduleStatuses["AWD"])
        assertEquals(r.moduleStatuses["AWD"], progress["AWD"])
        assertTrue(r.moduleNotes["AWD"]!!, r.moduleNotes["AWD"]!!.startsWith("flow control not sent"))
    }

    @Test fun `a retry through the live scan path carries its own explanation into screen and export`() = runTest {
        val view = com.openrs.dash.diagnostics.DtcScanView(DiagnosticLogger.dtcScans)
        val t = DiagnosticLogger.dtcScans.begin("manual", listOf("PCM"), 1L); view.begin(t)
        val (first, _) = scan(listOf(spec("PCM")), emptyMap(), fail = { it.startsWith("t7E08031902FF") })
        assertTrue(view.finish(t, first, 2L))
        // Retry: the request goes out, the reply's flow control fails — a different reason
        val (again, _) = scan(listOf(spec("PCM")), mapOf(0x7E0 to listOf(ffPcm to 20L)), fail = { it.startsWith("t7E08300") })
        val retry = DtcScanner.retryOf("PCM", again)
        assertTrue(retry.note!!, retry.note!!.startsWith("flow control not sent"))
        assertTrue(view.retryFinished(t, retry, 3L))
        assertEquals(retry.note, view.result!!.moduleNotes["PCM"])
        assertEquals(retry.note, DiagnosticLogger.lastDtcScan!!.notes["PCM"])
    }
}
