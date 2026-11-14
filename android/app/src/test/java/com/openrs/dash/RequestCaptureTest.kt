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

    // ── C1 (owner, 2026-11-14): the late-answer window after a timeout ───────────────────────────────────────────────

    @Test fun `after a timeout nothing more goes to the PCM until 5000 ms after the timed-out request`() = runTest {
        session()
        val p = pcm({ did -> if (did == "03EC") emptyList() else listOf(positive(did)) })
        connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(29_000L)
        val (at, next) = p.readAndNext("03EC")
        assertTrue("next PCM read ${next - at} ms after the timed-out one", next - at >= RequestCapture.LATE_WINDOW_MS)
    }

    @Test fun `a late answer inside the window is counted as the timed-out request's`() = runTest {
        session()
        val p = pcm({ did -> if (did == "03EC") listOf(positive(did, afterMs = 3_000L)) else listOf(positive(did)) })
        connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(29_000L)
        val (at, next) = p.readAndNext("03EC")
        assertTrue("the window held: ${next - at} ms", next - at >= RequestCapture.LATE_WINDOW_MS)
        val rec = DiagnosticLogger.exportCounters.capture
        assertTrue("late in the window: ${rec.lateInWindow}", rec.lateInWindow >= 1)
        assertEquals("none after it", 0, rec.lateAfterWindow)
    }

    @Test fun `a late rejection inside the window is counted as the timed-out request's`() = runTest {
        session()
        val p = pcm({ did -> if (did == "03EC") listOf(nrc("31", afterMs = 3_000L)) else listOf(positive(did)) })
        connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(29_000L)
        val rec = DiagnosticLogger.exportCounters.capture
        assertTrue("a late rejection in the window: ${rec.lateInWindow}", rec.lateInWindow >= 1)
    }

    @Test fun `a late positive after the window is counted, and does not end another DID's wait`() = runTest {
        session()
        val p = pcm({ did -> if (did == "03EC") listOf(positive(did, afterMs = 6_000L)) else listOf(positive(did, afterMs = 1_500L)) })
        connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(39_000L)   // the late reply comes 6 s after its write
        val rec = DiagnosticLogger.exportCounters.capture
        assertTrue("late after the window: ${rec.lateAfterWindow} (in the window: ${rec.lateInWindow})", rec.lateAfterWindow >= 1)
        p.pcmReads().filter { it.second >= 21_000L }.zipWithNext().forEach { (a, b) ->
            if (a.first != "03EC") assertTrue("${b.first} came ${b.second - a.second} ms after ${a.first}, before its 1500 ms answer",
                b.second - a.second >= 1_500L)
        }
    }

    @Test fun `at switch-on the first capture request waits for the requests sent before the switch`() = runTest {
        session()
        val p = pcm({ did -> listOf(positive(did, afterMs = 1_500L)) }, outcome = { WriteOutcome.POSSIBLY_DELIVERED })
        connect(p); advanceTimeBy(22_000L)                          // the poller runs, paced by the gap alone (#333)
        val on = testScheduler.currentTime
        capture.set(true); advanceTimeBy(8_000L)
        val before = p.pcmReads().last { it.second < on }.second
        val first = p.pcmReads().first { it.second >= on }.second
        assertTrue("first capture request ${first - before} ms after the last one before the switch", first - before >= 1_500L)
    }

    @Test fun `a PCM probe's timeout opens the pause too`() = runTest {
        session()
        val p = pcm({ did -> if (did == "A101") emptyList() else listOf(positive(did)) })
        val conn = connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(20_500L)
        backgroundScope.launch { conn.sendRawQuery(0x7E8, "t7E080322A10100000000\r", 1_000L) }
        advanceTimeBy(10_000L)
        val (at, next) = p.readAndNext("A101")
        assertTrue("next PCM read ${next - at} ms after the probe that timed out", next - at >= RequestCapture.LATE_WINDOW_MS)
    }

    @Test fun `a PCM probe waits out the pause after a timed-out poll`() = runTest {
        session()
        val p = pcm({ did -> if (did == "03EC") emptyList() else listOf(positive(did)) })
        val conn = connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(20_000L)
        val polled = p.pcmReads().first { it.first == "03EC" }.second
        advanceTimeBy(polled + 2_100L - testScheduler.currentTime)           // just after its timeout: inside the pause
        backgroundScope.launch { conn.sendRawQuery(0x7E8, "t7E080322A10100000000\r", 1_000L) }
        advanceTimeBy(8_000L)
        val probe = p.pcmReads().first { it.first == "A101" }.second
        assertTrue("the probe went out ${probe - polled} ms after the timed-out poll", probe - polled >= RequestCapture.LATE_WINDOW_MS)
    }

    /** With the poller idle (engine off, between sweeps), a probe's own turn is what holds it in the pause. */
    @Test fun `with the poller idle, a probe after a timed-out probe waits out the pause`() = runTest {
        session()
        val p = pcm({ did -> if (did == "A101") emptyList() else listOf(positive(did)) })
        val conn = connect(p, rpm = 0.0); advanceTimeBy(1_000L); capture.set(true)
        advanceTimeBy(34_000L)                                      // the engine-off sweep is done; the poller idles 30 s
        backgroundScope.launch {
            conn.sendRawQuery(0x7E8, "t7E080322A10100000000\r", 1_000L)
            conn.sendRawQuery(0x7E8, "t7E080322A10200000000\r", 1_000L)
        }
        advanceTimeBy(9_000L)
        val first = p.pcmReads().first { it.first == "A101" }.second
        val second = p.pcmReads().first { it.first == "A102" }.second
        assertTrue("premise: no poll in between", p.pcmReads().none { it.second in (first + 1) until second })
        assertTrue("the second probe went out ${second - first} ms after the first", second - first >= RequestCapture.LATE_WINDOW_MS)
    }

    @Test fun `a PCM probe that got a response pending says so on its timeout line`() = runTest {
        session()
        val p = pcm({ did -> if (did == "A101") listOf(nrc("78", afterMs = 100L)) else listOf(positive(did)) })
        val conn = connect(p, rpm = 0.0); advanceTimeBy(1_000L); capture.set(true)
        backgroundScope.launch { conn.sendRawQuery(0x7E8, "t7E080322A10100000000\r", 1_000L) }
        advanceTimeBy(3_000L)
        val t = timeouts().filter { it.contains(" can0 7E0#0322A101") }
        assertEquals(timeouts().toString(), 1, t.size)
        assertTrue(t[0], t[0].endsWith(" response pending, no final answer within 2000 ms"))
    }

    // ── C2 (owner, 2026-11-14): DTC operations hold the gate while the capture is on ─────────────────────────────────

    /** Runs [op] at once and returns the PCM reads written while it ran (after its first PCM write, before its end). */
    private fun TestScope.pollsDuring(p: Pcm, op: suspend () -> Unit): List<Pair<String, Long>> {
        var end = -1L
        val launched = testScheduler.currentTime
        backgroundScope.launch { op(); end = testScheduler.currentTime }
        advanceTimeBy(25_000L)
        assertTrue("the operation ended", end > 0)
        val opWrites = p.writes.toList().filter { (f, t) -> t >= launched && f.startsWith("t7E0") && !f.startsWith("t7E080322") }
        assertTrue("the operation wrote to the PCM: ${p.writes.toList().takeLast(5)}", opWrites.isNotEmpty())
        val from = opWrites.first().second
        return p.pcmReads().filter { it.second > from && it.second < end }
    }
    private fun TestScope.slowPcm() = pcm({ did -> listOf(positive(did, afterMs = 100L)) })
    private val pcmSpec get() = com.openrs.dash.diagnostics.DtcScanner.MODULES.first { it.name == "PCM" }

    @Test fun `while the capture is on, a DTC scan holds the gate - no poll while it runs`() = runTest {
        session(); val p = slowPcm(); val conn = connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(21_000L)
        val polls = pollsDuring(p) { com.openrs.dash.diagnostics.DtcScanner.runScan(conn, listOf(pcmSpec)) { _, _ -> } }
        assertTrue("polls during the scan: $polls", polls.isEmpty())
    }

    @Test fun `while the capture is on, a DTC clear holds the gate`() = runTest {
        session(); val p = slowPcm(); val conn = connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(21_000L)
        val polls = pollsDuring(p) { conn.performDtcClear(listOf(pcmSpec)) }
        assertTrue("polls during the clear: $polls", polls.isEmpty())
    }

    @Test fun `while the capture is on, a freeze-frame identification holds the gate`() = runTest {
        session(); val p = slowPcm(); val conn = connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(21_000L)
        val polls = pollsDuring(p) { conn.performFreezeFrameIdentification(pcmSpec) }
        assertTrue("polls during the identification: $polls", polls.isEmpty())
    }

    @Test fun `while the capture is on, a freeze-frame read holds the gate`() = runTest {
        session(); val p = slowPcm(); val conn = connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(21_000L)
        val polls = pollsDuring(p) { conn.performFreezeFrameRead(pcmSpec, byteArrayOf(0x01, 0x23, 0x45), 1) }
        assertTrue("polls during the read: $polls", polls.isEmpty())
    }

    @Test fun `with the capture off, polling goes on during a DTC scan, and the scan starts at once, as before`() = runTest {
        session(); val p = pcm({ did -> listOf(positive(did, afterMs = 250L)) }); val conn = connect(p); advanceTimeBy(22_000L)
        // Launch while a poll is waiting for its answer: a capture turn would wait for it (the premise of this check)
        val lastPoll = p.pcmReads().last().second
        while (p.pcmReads().last().second == lastPoll) advanceTimeBy(10L)
        advanceTimeBy(20L)
        val launched = testScheduler.currentTime
        val polls = pollsDuring(p) { com.openrs.dash.diagnostics.DtcScanner.runScan(conn, listOf(pcmSpec)) { _, _ -> } }
        assertTrue("polls during the scan with the mode off: ${polls.size}", polls.isNotEmpty())
        val first = p.writes.toList().first { (f, t) -> t >= launched && f.startsWith("t7E08031902") }.second
        assertEquals("the scan's request went out at once (no capture turn with the mode off)", launched, first)
    }

    /** QA's Q1: a late positive reply for ANOTHER DID during a capture wait is not this request's answer. */
    @Test fun `another DID's late positive reply does not end the wait`() = runTest {
        session()
        val p = pcm({ did -> when (did) {
            "03EC" -> listOf(Answer("0462033E00000000", 100L))     // no answer of its own; a stray 033E reply 100 ms in
            else -> listOf(positive(did))
        } })
        connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(29_000L)
        val (at, next) = p.readAndNext("03EC")
        assertTrue("the stray reply ended the wait: next read ${next - at} ms after", next - at >= RequestCapture.WAIT_MS)
        assertTrue("and 03EC timed out: ${timeouts()}", timeouts().any { it.contains("#032203EC") })
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
        connect(p); advanceTimeBy(1_000L); capture.set(true); advanceTimeBy(39_000L)   // two timeouts, two 5 s pauses (C1)
        val (lapse, _) = p.readAndNext("03EC")
        val (rej, afterRej) = p.readAndNext("03ED", lapse + 1)
        assertTrue("the rejection ended the wait: next read ${afterRej - rej} ms after", afterRej - rej < 1_000L)
        assertTrue("no timeout for the rejected DID: ${timeouts()}", timeouts().none { it.contains("#032203ED") })
        val (pend, afterPend) = p.readAndNext("03EE", lapse + 1)
        assertTrue("a response pending did not end the wait: ${afterPend - pend} ms", afterPend - pend >= RequestCapture.WAIT_MS)
        assertTrue("a timeout for the pending DID: ${timeouts()}", timeouts().any { it.contains("#032203EE") })
        assertTrue("the pending DID's line says it was pending (N3): ${timeouts()}",
            timeouts().filter { it.contains("#032203EE") }.all { it.endsWith(" response pending, no final answer within 2000 ms") })
        assertTrue("an unanswered DID's line says no answer: ${timeouts()}",
            timeouts().filter { it.contains("#032203EC") }.all { it.endsWith(" no answer within 2000 ms") && !it.contains("pending") })
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

    /** C3: a PCM DID probe in the capture waits max(its timeout, 2,000 ms) and marks no answer like the poller does. */
    @Test fun `with the capture on, an unanswered PCM probe waits 2000 ms and logs a CAPTURE-TIMEOUT line`() = runTest {
        session()
        val p = pcm({ did -> if (did == "A101") emptyList() else listOf(positive(did)) })
        val conn = connect(p, rpm = 0.0); advanceTimeBy(1_000L); capture.set(true)
        var doneAt = -1L
        backgroundScope.launch { conn.sendRawQuery(0x7E8, "t7E080322A10100000000\r", 1_000L); doneAt = testScheduler.currentTime }
        advanceTimeBy(3_000L)
        val at = p.pcmReads().first { it.first == "A101" }.second
        assertTrue("the probe returned ${doneAt - at} ms after its write", doneAt - at >= RequestCapture.WAIT_MS)
        val t = timeouts().filter { it.contains(" can0 7E0#0322A101") }
        assertEquals(timeouts().toString(), 1, t.size)
        assertEquals("stamped at the wait's end", at + RequestCapture.WAIT_MS, ms(t[0]))
    }

    @Test fun `with the capture off, a PCM probe keeps its own timeout and logs no timeout line`() = runTest {
        session()
        val p = pcm({ did -> if (did == "A101") emptyList() else listOf(positive(did)) })
        val conn = connect(p, rpm = 0.0); advanceTimeBy(1_000L)
        var doneAt = -1L
        backgroundScope.launch { conn.sendRawQuery(0x7E8, "t7E080322A10100000000\r", 1_000L); doneAt = testScheduler.currentTime }
        advanceTimeBy(3_000L)
        val at = p.pcmReads().first { it.first == "A101" }.second
        assertEquals(1_000L, doneAt - at)
        assertTrue(timeouts().toString(), timeouts().isEmpty())
    }

    /** C5, the owner's "suppression off in the mode": no rejection counts toward suppression while it is on. */
    @Test fun `rejections during a capture build no suppression, and the DID is still read after it`() = runTest {
        session()
        val p = pcm({ did -> if (did == "03EC") listOf(nrc("31")) else listOf(positive(did)) })
        connect(p); advanceTimeBy(1_000L); capture.set(true)
        advanceTimeBy(39_000L)
        assertTrue("rejections came: ${p.pcmReads().count { it.first == "03EC" }}", p.pcmReads().count { it.first == "03EC" } >= 3)
        val ev = DiagnosticLogger.sessionEvents.filter { it.type == "NRC_SUPPRESS" }
        assertTrue("no NRC_SUPPRESS while the capture is on: $ev", ev.isEmpty())
        capture.set(false)
        val before = p.pcmReads().count { it.first == "03EC" }
        advanceTimeBy(10_000L)
        assertTrue("03EC is still read after the capture", p.pcmReads().count { it.first == "03EC" } > before)
    }

    /** QA's pin: a DID suppressed on one connection is polled again on the next (the tracker is per connection). */
    @Test fun `a DID suppressed on one connection is read again on the next`() = runTest {
        session()
        val script = { did: String -> if (did == "03EC") listOf(nrc("31")) else listOf(positive(did)) }
        val first = pcm(script); val second = pcm(script)
        var built = 0
        val conn = SlcanConnection(transportFactory = { if (built++ == 0) first else second }, maxRetries = 3, reconnectDelayMs = 1_000L,
            rpmProvider = { 1_000.0 }, ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {},
            clock = { testScheduler.currentTime }, capture = capture)
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState(rpm = 1_000.0) }).collect {} }
        advanceTimeBy(30_000L)
        val n = first.pcmReads().count { it.first == "03EC" }
        advanceTimeBy(10_000L)
        assertEquals("premise: suppressed on the first connection", n, first.pcmReads().count { it.first == "03EC" })
        first.lines.close()                                         // the link drops; the connection reconnects
        advanceTimeBy(30_000L)
        assertTrue("read again on the second connection: ${second.pcmReads().size} reads", second.pcmReads().any { it.first == "03EC" })
    }

    /**
     * C6, a manual reconnect: the old connection's end can come after the new link is up. It must not turn the new link's
     * switch off or make it refuse. Two live connections share one switch; the old one's end is forced after the new link.
     */
    @Test fun `an old connection's late end leaves the new link's switch usable`() = runTest {
        session()
        val oldPcm = pcm({ did -> listOf(positive(did)) }); val newPcm = pcm({ did -> listOf(positive(did)) })
        connect(oldPcm); advanceTimeBy(1_000L)
        assertTrue(capture.set(true))
        capture.linkDown(null, RequestCapture.REASON_RECONNECTED)  // the service's teardown before the new session
        connect(newPcm); advanceTimeBy(1_000L)                      // the new link is up
        assertTrue("the new link's switch turns on", capture.set(true))
        oldPcm.lines.close(); advanceTimeBy(1_000L)                 // the old connection's end comes late
        assertTrue("still on after the old link's late end", capture.on.value)
        assertTrue("and usable", capture.set(false) && capture.set(true))
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
        c.linkUp(); assertTrue(c.set(true))
        c.linkUp()                                                  // a reconnect without a seen drop
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
