package com.openrs.dash

import com.openrs.dash.can.RequestCapture
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.can.WriteOutcome
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * #209 Step B, Request capture (owner's decisions of 2026-11-14): while it is on, the PCM poller sends its next request only
 * after the previous one is answered (a positive reply for its DID, or a final `7F 22` rejection) or 2,000 ms have passed;
 * a response pending (`7F 22 78`) does not end the wait; possibly delivered writes are waited for too; NRC suppression
 * does not skip a DID; a PCM DID probe and the poller never have requests waiting at once. A link up or down turns it off,
 * with a session event, and the export says when it was on and when it ended. Live SlcanConnection over a scripted PCM, on
 * the virtual clock (the raw log's times included, `DiagnosticLogger.sessionClock`).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestCaptureTest {
    /** One scripted answer: the reply's data (after the PCI-less `7E8` id) and how long after the write it comes. */
    private data class Answer(val data: String, val afterMs: Long)
    private fun positive(did: String, afterMs: Long = 20L) = Answer("0462${did}00000000", afterMs)
    private fun nrc(code: String, afterMs: Long = 20L) = Answer("037F22${code}00000000", afterMs)

    /** A PCM that answers each Mode 22 read as [script] says (none = no answer), the writes recorded with their time. */
    private class Pcm(
        private val scope: CoroutineScope,
        private val now: () -> Long,
        private val script: (did: String) -> List<Answer>,
        private val outcome: (String) -> WriteOutcome = { WriteOutcome.SENT },
    ) : SlcanTransport {
        val lines = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        val writes: MutableList<Pair<String, Long>> = java.util.Collections.synchronizedList(mutableListOf())
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receiveCatching().getOrNull()
        override suspend fun write(frame: String): WriteOutcome {
            val f = frame.trimEnd('\r')
            writes += f to now()
            if (f.length >= 13 && f.startsWith("t7E08") && f.substring(5, 9) == "0322") {
                val did = f.substring(9, 13)
                script(did).forEach { a -> scope.launch { delay(a.afterMs); lines.trySend("t7E88${a.data}") } }
            }
            return outcome(frame)
        }
        override suspend fun writeLine(frame: String) { write(frame) }
        override fun close() { lines.close() }
        override val label = "scripted"; override val stockFirmwareLabel = "scripted fw"
        /** The PCM Mode 22 reads written, oldest first: (DID, time). */
        fun pcmReads() = writes.toList().filter { it.first.startsWith("t7E080322") }.map { it.first.substring(9, 13) to it.second }
    }

    private val dir = File(System.getProperty("java.io.tmpdir"), "request-capture-${System.nanoTime()}")
    private lateinit var capture: RequestCapture

    @Before fun fresh() { capture = RequestCapture() }
    @After fun realClock() { DiagnosticLogger.sessionClock = System::currentTimeMillis }

    private fun TestScope.session() {
        DiagnosticLogger.sessionClock = { 1_000_000L + testScheduler.currentTime }
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = dir)
    }
    private fun log(): List<String> { DiagnosticLogger.flushSlcan(); return DiagnosticLogger.slcanLogFile!!.readLines() }
    private fun ms(l: String) = Math.round(Regex("""\(\s*([\d.]+)\)""").find(l)!!.groupValues[1].toDouble() * 1000)
    private fun timeouts() = log().filter { it.startsWith("# CAPTURE-TIMEOUT ") }

    private fun TestScope.connect(pcm: Pcm, rpm: Double = 1_000.0): SlcanConnection {
        val conn = SlcanConnection(transportFactory = { pcm }, maxRetries = 1, rpmProvider = { rpm },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
            capture = capture)
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState(rpm = rpm) }).collect {} }
        return conn
    }
    private fun TestScope.pcm(script: (String) -> List<Answer>, outcome: (String) -> WriteOutcome = { WriteOutcome.SENT }) =
        Pcm(backgroundScope, { testScheduler.currentTime }, script, outcome)

    /** The first read of [did] at or after [fromMs], and the next PCM read after it. */
    private fun Pcm.readAndNext(did: String, fromMs: Long = 0L): Pair<Long, Long> {
        val r = pcmReads()
        val i = r.indexOfFirst { it.first == did && it.second >= fromMs }
        assertTrue("no read of $did after $fromMs in $r", i >= 0 && i + 1 < r.size)
        return r[i].second to r[i + 1].second
    }

    @Test fun `with the capture on, an unanswered request holds the next one for 2000 ms and logs a CAPTURE-TIMEOUT line`() = runTest {
        session()
        val p = pcm({ did -> if (did == "03EC") emptyList() else listOf(positive(did)) })
        connect(p); advanceTimeBy(1_000L)
        assertTrue("the switch turns on with a link", capture.set(true))
        advanceTimeBy(29_000L)
        val (at, next) = p.readAndNext("03EC")
        assertTrue("next PCM read ${next - at} ms after the unanswered one", next - at >= RequestCapture.WAIT_MS)
        val t = timeouts().filter { it.contains(" can0 7E0#032203EC") }
        assertTrue("a timeout line for 03EC: ${timeouts()}", t.isNotEmpty())
        assertEquals("the line's time is the end of the wait", at + RequestCapture.WAIT_MS, ms(t.first()))
        assertTrue(t.first(), t.first().endsWith(" no answer within 2000 ms"))
        assertTrue("no request line starts like the timeout", log().none { it.startsWith("# TX ") && it.contains("CAPTURE") })
    }

    @Test fun `with the capture off, pacing is unchanged and no timeout line is written`() = runTest {
        session()
        val p = pcm({ did -> if (did == "03EC") emptyList() else listOf(positive(did)) })
        connect(p); advanceTimeBy(30_000L)
        val (at, next) = p.readAndNext("03EC")
        assertTrue("next read ${next - at} ms after (the 300 ms pacing window, #333)", next - at < 1_000L)
        assertTrue(timeouts().toString(), timeouts().isEmpty())
    }

    @Test fun `after a lapse a final rejection still ends the wait, and a response pending does not`() = runTest {
        session()
        val p = pcm({ did -> when (did) {
            "03EC" -> emptyList()                                   // never answers: lapses, the ledger is unresolved after it
            "03ED" -> listOf(nrc("31", afterMs = 100L))             // rejected: not attributable after the lapse
            "03EE" -> listOf(nrc("78", afterMs = 100L))             // response pending, never a final answer
            else -> listOf(positive(did))
        } })
        connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(29_000L)
        val (lapse, _) = p.readAndNext("03EC")
        val (rej, afterRej) = p.readAndNext("03ED", lapse + 1)
        assertTrue("the rejection ended the wait: next read ${afterRej - rej} ms after", afterRej - rej < 1_000L)
        assertTrue("no timeout for the rejected DID: ${timeouts()}", timeouts().none { it.contains("#032203ED") })
        val (pend, afterPend) = p.readAndNext("03EE", lapse + 1)
        assertTrue("a response pending did not end the wait: ${afterPend - pend} ms", afterPend - pend >= RequestCapture.WAIT_MS)
        assertTrue("a timeout for the pending DID: ${timeouts()}", timeouts().any { it.contains("#032203EE") })
    }

    @Test fun `a possibly delivered write is waited for while the capture is on`() = runTest {
        session()
        val p = pcm({ did -> listOf(positive(did, afterMs = 500L)) }, outcome = { WriteOutcome.POSSIBLY_DELIVERED })
        connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(29_000L)
        val r = p.pcmReads().filter { it.second >= 21_000L }
        assertTrue("PCM reads: $r", r.size >= 4)
        r.zipWithNext().forEach { (a, b) -> assertTrue("read ${b.first} came ${b.second - a.second} ms after ${a.first}", b.second - a.second >= 500L) }
    }

    @Test fun `with the capture off a possibly delivered write is paced by the gap alone, as before (#333)`() = runTest {
        session()
        val p = pcm({ did -> listOf(positive(did, afterMs = 500L)) }, outcome = { WriteOutcome.POSSIBLY_DELIVERED })
        connect(p); advanceTimeBy(30_000L)
        val r = p.pcmReads().filter { it.second >= 21_000L }
        assertTrue("some reads closer than the answer's 500 ms: $r", r.zipWithNext().any { (a, b) -> b.second - a.second < 500L })
    }

    @Test fun `a DID that NRC suppression skips is sent while the capture is on`() = runTest {
        session()
        val p = pcm({ did -> if (did == "03EC") listOf(nrc("31")) else listOf(positive(did)) })
        connect(p); advanceTimeBy(30_000L)
        val before = p.pcmReads().count { it.first == "03EC" }
        advanceTimeBy(10_000L)
        assertEquals("suppressed before the switch: no read of 03EC in 30-40 s", before, p.pcmReads().count { it.first == "03EC" })
        capture.set(true); advanceTimeBy(10_000L)
        assertTrue("03EC is read while the capture is on", p.pcmReads().count { it.first == "03EC" } > before)
    }

    @Test fun `while the capture is on, a PCM probe and the poller never have requests waiting at once`() = runTest {
        session()
        val delayFor = { did: String -> if (did == "A101") 1_000L else 1_500L }
        val p = pcm({ did -> listOf(positive(did, afterMs = delayFor(did))) })
        val conn = connect(p); advanceTimeBy(1_000L); capture.set(true)
        advanceTimeBy(20_500L)                                      // the poller has a request waiting
        backgroundScope.launch { conn.sendRawQuery(0x7E8, "t7E080322A10100000000\r", 1_500L) }
        advanceTimeBy(15_000L)
        val r = p.pcmReads().filter { it.second >= 20_000L }
        assertTrue("the probe was written: $r", r.any { it.first == "A101" })
        r.zipWithNext().forEach { (a, b) ->
            assertTrue("${b.first} written ${b.second - a.second} ms after ${a.first}, before its answer at ${delayFor(a.first)} ms",
                b.second - a.second >= delayFor(a.first))
        }
    }

    @Test fun `a link drop turns the capture off, says so, and the export says when it ended`() = runTest {
        session()
        val p = pcm({ did -> listOf(positive(did)) })
        connect(p); advanceTimeBy(1_000L)
        assertTrue(capture.set(true))
        advanceTimeBy(4_000L)
        p.lines.close()                                             // the adapter's link ends
        advanceTimeBy(1_000L)
        assertFalse("the switch reads off", capture.on.value)
        assertFalse("with no link it cannot turn on", capture.set(true))
        val ev = DiagnosticLogger.sessionEvents.filter { it.type == DiagnosticLogger.EVENT_REQUEST_CAPTURE }
        assertTrue(ev.toString(), ev.any { it.message == "Request capture off: ${RequestCapture.REASON_LINK_ENDED}" })
        val rec = DiagnosticLogger.exportCounters.capture
        assertEquals(rec.spans.toString(), 1, rec.spans.size)
        assertEquals(RequestCapture.REASON_LINK_ENDED, rec.spans[0].offReason)
        val text = DiagnosticReportBuilder.run { com.openrs.dash.diagnostics.requestCaptureText(rec) }
        assertTrue(text.toString(), text.any { it.contains("On 1.0 s → off ") && it.contains("(${RequestCapture.REASON_LINK_ENDED})") })
        val json = com.openrs.dash.diagnostics.requestCaptureJson(rec)
        assertTrue(json.toString(), json.getBoolean("used") && json.getJSONArray("spans").getJSONObject(0).getString("offReason") == RequestCapture.REASON_LINK_ENDED)
    }

    @Test fun `every new link starts with the capture off, and a capture on at export reads still on`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = dir)
        val c = RequestCapture()
        assertFalse("no link: refused", c.set(true))
        c.linkChanged(up = true); assertTrue(c.set(true))
        c.linkChanged(up = true)                                    // a reconnect without a seen drop
        assertFalse(c.on.value)
        val ev = DiagnosticLogger.sessionEvents.filter { it.type == DiagnosticLogger.EVENT_REQUEST_CAPTURE }.map { it.message }
        assertTrue(ev.toString(), ev.contains("Request capture off: ${RequestCapture.REASON_RECONNECTED}"))
        c.set(true)
        val text = com.openrs.dash.diagnostics.requestCaptureText(DiagnosticLogger.exportCounters.capture)
        assertTrue(text.toString(), text.last().endsWith("still on at export"))
    }

    @Test fun `a session without a capture says so`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = dir)
        val c = DiagnosticLogger.exportCounters.capture
        assertEquals(listOf("─── REQUEST CAPTURE ───────────────────────────────────────", "  Off for the whole session"),
            com.openrs.dash.diagnostics.requestCaptureText(c))
        assertFalse(com.openrs.dash.diagnostics.requestCaptureJson(c).getBoolean("used"))
    }

    @Test fun `the switch's name and text are the owner's`() {
        assertEquals("Request capture", com.openrs.dash.ui.REQUEST_CAPTURE_LABEL)
        assertEquals("Capture requests one at a time (this connection only): PCM readings update more slowly while it is on.",
            com.openrs.dash.ui.REQUEST_CAPTURE_TEXT)
    }
}
