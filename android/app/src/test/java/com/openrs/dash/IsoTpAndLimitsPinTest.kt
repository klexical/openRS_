package com.openrs.dash

import com.openrs.dash.can.IsoTpBuffer
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.KnockCapture
import com.openrs.dash.data.KnockSessionLog
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.DtcScanner
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
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #253: what mutation review found unpinned in IsoTpCoverageTest and BufferBoundsTest. A swapped or duplicate CF that fills the
 * declared length, a truncated SF on the assemble path, a short CF ending the reply, every limit's reset and summary line, and
 * an SLCAN write failure. JVM, virtual time, SYNTHETIC frames only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IsoTpAndLimitsPinTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null); KnockSessionLog.reset(50_000L) }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun pattern(len: Int, salt: Int = 0) = ByteArray(len) { ((it * 7 + 1 + salt) and 0xFF).toByte() }
    private fun segment(payload: ByteArray): List<ByteArray> {
        val first = ByteArray(8) { 0xCC.toByte() }
        first[0] = (0x10 or (payload.size shr 8)).toByte(); first[1] = (payload.size and 0xFF).toByte()
        System.arraycopy(payload, 0, first, 2, 6)
        val out = mutableListOf(first); var off = 6; var seq = 1
        while (off < payload.size) {
            val f = ByteArray(8) { 0xCC.toByte() }; f[0] = (0x20 or seq).toByte()
            val n = minOf(7, payload.size - off); System.arraycopy(payload, off, f, 1, n)
            out += f; off += n; seq = (seq + 1) and 0x0F
        }
        return out
    }
    private fun line(id: Int, f: ByteArray) = "t%03X%d".format(id, f.size) + f.joinToString("") { "%02X".format(it) }

    private class Bus(private val scope: CoroutineScope, private val onWrite: (Bus, String) -> Unit) : SlcanTransport {
        private val rx = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        fun paced(l: List<String>, gapMs: Long = 2L) { scope.launch { for (x in l) { delay(gapMs); rx.trySend(x) } } }
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = rx.receiveCatching().getOrNull()
        override suspend fun writeLine(frame: String) { onWrite(this, frame) }
        override fun close() { rx.close() }
        override val label = "scripted"
        override val stockFirmwareLabel = "scripted"
    }

    private fun TestScope.conn(bus: Bus): SlcanConnection {
        val c = SlcanConnection(transportFactory = { bus }, maxRetries = 1, rpmProvider = { 0.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime })
        var st = VehicleState()
        backgroundScope.launch { c.connectHybrid({ st = it }, { st }).collect {} }
        advanceTimeBy(100L)
        return c
    }
    private val pcm = DtcScanner.MODULES.first { it.name == "PCM" }
    private fun snapshotReply(n: Int) = (0 until n).fold(bytes(0x59, 0x04)) { a, i -> a + bytes(0x01, i and 0xFF, 0x00, 0x01) }
    private fun freezeBus(s: CoroutineScope, l: List<String>) = Bus(s) { b, f -> if (f.length >= 13 && f.substring(5, 13) == "031904FF") b.paced(l) }
    private fun clearBus(s: CoroutineScope, l: List<String>) = Bus(s) { b, f -> if (f.length >= 15 && f.substring(5, 15) == "0414FFFFFF") b.paced(l) }

    // C4: seq check in assembleIsotpResponse. All frames arrive, so the byte count completes; only the sequence check can refuse.
    @Test fun `P1 freeze id - two swapped CFs of a whole reply return nothing`() = runTest {
        val f = segment(snapshotReply(10)).map { line(0x7E8, it) }
        val swapped = f.take(2) + f[3] + f[2] + f.drop(4)
        val out = conn(freezeBus(backgroundScope, swapped)).performFreezeFrameIdentification(pcm)
        assertNull(out?.let { "reordered reply of ${it.size} bytes" }, out)
    }
    @Test fun `P1b DTC clear - a duplicate CF that fills the length is not an acknowledged clear`() = runTest {
        val f = segment(bytes(0x54) + pattern(25)).map { line(0x7E8, it) }
        val dup = f.take(2) + f[1] + f[2]                       // FF, CF1, CF1 again, CF2: 6 + 7 + 7 + 6 = 26
        assertEquals(mapOf("PCM" to false), conn(clearBus(backgroundScope, dup)).performDtcClear(listOf(pcm)))
    }

    // C12: truncated SF on the assemble path
    @Test fun `P2 freeze id - a truncated SF returns nothing`() = runTest {
        val out = conn(freezeBus(backgroundScope, listOf(line(0x7E8, bytes(0x07, 0x59, 0x04, 0x01))))).performFreezeFrameIdentification(pcm)
        assertNull(out?.let { "${it.size} bytes" }, out)
    }
    @Test fun `P2b DTC clear - a truncated SF is not an acknowledged clear`() = runTest {
        assertEquals(mapOf("PCM" to false), conn(clearBus(backgroundScope, listOf(line(0x7E8, bytes(0x04, 0x54))))).performDtcClear(listOf(pcm)))
    }

    // I16: a short CF must END the reply, not just skip its own bytes
    @Test fun `P3 buffer - after a short CF, CFs continuing the sequence can't complete the reply`() {
        val f = segment(pattern(25))
        val buf = IsoTpBuffer()
        assertNull(buf.feed(f[0]).first)
        assertNull(buf.feed(f[1].copyOf(7)).first)              // short: the reply is over
        val more = listOf(f[2], f[3], bytes(0x24, 1, 2, 3, 4, 5, 6, 7), bytes(0x25, 1, 2, 3, 4, 5, 6, 7))
        val delivered = more.mapNotNull { buf.feed(it).first }
        assertTrue("delivered ${delivered.map { it.size }}", delivered.isEmpty())
    }

    private fun overflowEverything(): Unit = kotlinx.coroutines.runBlocking {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = tmp.newFolder())
        repeat(10_001) { DiagnosticLogger.logFrame(0x0F8, byteArrayOf(1), VehicleState(), "d", null) }
        repeat(7_201) { DiagnosticLogger.fps(1.0) }
        repeat(51) { n -> runDidProbeScan("M$n", 0x7E0, 0x7E8, listOf(1), { _, _, _ -> null }, { false }) { } }
        for (i in 0 until 4_100) DiagnosticLogger.logUnknownFrame(0x10000000 + i, byteArrayOf(1))
        DiagnosticLogger::class.java.getDeclaredField("slcanLinesWritten").apply { isAccessible = true }.setLong(null, 2_000_000L)
        DiagnosticLogger.logObdFrame(0x7E8, byteArrayOf(1))
        DiagnosticLogger.countOverlongLine(); DiagnosticLogger.countOverlongLine()
        repeat(3) { DiagnosticLogger.countCommandReplyDropped() }
        repeat(5) { DiagnosticLogger.countDtcFrameDropped() }
    }

    // D9, D11..D14: every count resets with the session, not only eventsDropped
    @Test fun `P4 every limit resets with the session`() {
        overflowEverything()
        val before = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("logLimits")
        for (k in listOf("decodeTraceDropped", "fpsPointsDropped", "probeSessionsDropped", "inventoryFramesNotListed")) assertTrue(k, before.getLong(k) > 0)
        assertTrue(before.getBoolean("slcanCapReached"))
        DiagnosticLogger.sessionStart("next", 0, prefs = null)
        val after = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("logLimits")
        for (k in listOf("decodeTraceDropped", "fpsPointsDropped", "probeSessionsDropped", "inventoryFramesNotListed")) assertEquals(k, 0L, after.getLong(k))
        assertFalse(after.getBoolean("slcanCapReached"))
    }

    // R1, R3..R7, R20: each LOG LIMITS line, and never "no limit reached" beside one
    @Test fun `P5 the LOG LIMITS block names every limit reached and doesn't also say none was`() {
        overflowEverything()
        repeat(KnockSessionLog.MAX_EVENTS + 1) { i ->
            val t = 51_000L + i
            KnockSessionLog.onState(VehicleState(rpm = 800.0, ignCorrCyl1 = 1.0, fieldLastUpdateMs = mapOf(KnockCapture.KR_TAGS[0] to t)), t)
        }
        val block = DiagnosticReportBuilder.buildSummaryText("t").lines().dropWhile { !it.startsWith("─── LOG LIMITS") }.drop(1).takeWhile { it.isNotBlank() }
        val text = block.joinToString("\n")
        for (s in listOf("decode trace entries rolled off", "FPS sample(s) rolled off", "DID probe session(s) rolled off",
            "frame(s) of IDs past the inventory limit", "SLCAN log cap reached", "session knock event(s) rolled off")) assertTrue("$s\n$text", text.contains(s))
        assertFalse(text, text.contains("no limit reached"))
    }

    // An SLCAN write failure loses lines: the export must say so, and the later cap must still be reported and marked
    private fun failingWriter() = object : java.io.BufferedWriter(java.io.StringWriter()) {
        var failing = true
        val written = StringBuilder()
        override fun write(str: String) { if (failing) throw java.io.IOException("disk full"); written.append(str) }
        override fun flush() = Unit
    }
    private fun field(name: String) = DiagnosticLogger::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test fun `an SLCAN write failure is reported, never "no limit reached"`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = tmp.newFolder())
        field("slcanWriter").set(null, failingWriter())
        DiagnosticLogger.logObdFrame(0x7E8, byteArrayOf(1))
        val limits = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("logLimits")
        assertTrue(limits.toString(), limits.getBoolean("slcanWriteFailed"))
        assertFalse(limits.getBoolean("slcanCapReached"))
        val summary = DiagnosticReportBuilder.buildSummaryText("t")
        assertTrue(summary, summary.contains("SLCAN log write failed"))
        assertFalse(summary, summary.contains("no counted limit reached"))
        DiagnosticLogger.sessionStart("next", 0, prefs = null)
        assertFalse(JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("logLimits").getBoolean("slcanWriteFailed"))
    }

    @Test fun `the SLCAN cap is reported and marked even after an earlier write failure`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = tmp.newFolder())
        val w = failingWriter()
        field("slcanWriter").set(null, w)
        DiagnosticLogger.logObdFrame(0x7E8, byteArrayOf(1))
        w.failing = false
        field("slcanLinesWritten").setLong(null, 2_000_000L)
        DiagnosticLogger.logObdFrame(0x7E8, byteArrayOf(2))
        DiagnosticLogger.logObdFrame(0x7E8, byteArrayOf(3))
        assertEquals("one marker", 1, w.written.split("# LOG CAP REACHED").size - 1)
        val limits = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("logLimits")
        assertTrue(limits.getBoolean("slcanCapReached"))
        assertTrue(limits.getBoolean("slcanWriteFailed"))
    }

    @Test fun `a session under every counted limit says so in those words`() {
        val summary = DiagnosticReportBuilder.buildSummaryText("t")
        assertTrue(summary, summary.contains("no counted limit reached"))
    }

    // R12..R14: the Buffers line's numbers
    @Test fun `P6 the Buffers line carries each count`() {
        overflowEverything()
        val line = DiagnosticReportBuilder.buildSummaryText("t").lines().single { it.startsWith("  Buffers") }
        assertTrue(line, line.contains("2 overlong"))
        assertTrue(line, line.contains("3 command"))
        assertTrue(line, line.contains("5 DTC"))
    }
}
