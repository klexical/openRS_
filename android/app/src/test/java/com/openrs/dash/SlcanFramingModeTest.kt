package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanParser
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.PerfRunEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * rc.8 reliability pass, #251 — the SLCAN timestamp suffix. The adapter appends 4 hex characters to every frame while
 * `Z1` is set — a flag another client can leave on for the adapter's uptime (the app never sends Z1). Strict framing
 * alone would then refuse every frame. Per connection (SlcanConnection + SlcanFraming): plain unless the first 8 data
 * frames are all timestamped; a mixed start locks plain; the mode never changes mid-connection (50 consecutive lines of
 * the other shape log one FRAMING event); a new connection detects again. Production path through ReplayRun's
 * composition (SlcanConnection → LinkSession: decode, stamps, peaks). SYNTHETIC lines; oil = 0x0F8 byte 7 − 60.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlcanFramingModeTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun oil(c: Int) = "t0F88005A0000000000%02X".format(c + 60)
    private fun ts(line: String, tick: Int) = line + "%04X".format(tick % 60_000)
    private fun framingEvents() = DiagnosticLogger.sessionEvents.filter { it.type == "FRAMING" }.map { it.message }

    private fun TestScope.replay(lines: List<String>, stepMs: Long = 50L): VehicleState {
        val rows = lines.mapIndexed { i, l -> ReplayRow(100L + i * stepMs, "RX", l) }
        val run = ReplayRun(this, rows)
        advanceTimeBy(100L + lines.size * stepMs + 2_000L)
        return run.state.value
    }

    // ── Parser: both modes refuse every other length ────────────────────────────────────────────────────────────────

    @Test fun `parser - +1, +2, +3 and +5 characters are refused in both modes, +4 only in timestamped mode`() {
        val base = oil(70)
        for (extra in listOf("A", "AB", "ABC", "ABCDE")) {
            assertNull(extra, SlcanParser.parse(base + extra, timestamped = false))
            assertNull(extra, SlcanParser.parse(base + extra, timestamped = true))
        }
        assertNull(SlcanParser.parse(base + "04D2", timestamped = false))
        assertNotNull(SlcanParser.parse(base + "04D2", timestamped = true))
        assertNull("a plain line in timestamped mode", SlcanParser.parse(base, timestamped = true))
        assertNull("a non-hex suffix", SlcanParser.parse(base + "04G2", timestamped = true))
        assertNotNull("59 999 ms", SlcanParser.parse(base + "EA5F", timestamped = true))
        assertNull("not a timestamp: the adapter's is ms mod 60 000", SlcanParser.parse(base + "EA60", timestamped = true))
        assertNull(SlcanParser.shape(base + "FFFF"))
        assertEquals(SlcanParser.Shape.TIMESTAMPED, SlcanParser.shape(base + "04D2"))
        assertEquals(SlcanParser.Shape.PLAIN, SlcanParser.shape(base))
        assertNull(SlcanParser.shape(base + "ABC"))
    }

    // ── Connection-level detection ──────────────────────────────────────────────────────────────────────────────────

    @Test fun `a Z1-style stream decodes normally once the connection locks to timestamped`() = runTest {
        val vs = replay((0 until 20).map { ts(oil(if (it < 19) 70 else 80), it * 50) })
        assertEquals(80.0, vs.oilTempC, 0.0)
        assertEquals(80.0, vs.session.peakOilTempC, 0.0)
        assertEquals(framingEvents().toString(), 1, framingEvents().size)
        assertTrue(framingEvents().single().contains("timestamp"))
        assertEquals(true, DiagnosticLogger.linkIntegrity.framingTimestamped)
    }

    @Test fun `control - a plain stream stays plain with no FRAMING event`() = runTest {
        val vs = replay((0 until 20).map { oil(70) })
        assertEquals(70.0, vs.oilTempC, 0.0)
        assertTrue(framingEvents().toString(), framingEvents().isEmpty())
    }

    @Test fun `one +4 line among the first plain lines can't win detection - it stays plain and is refused`() = runTest {
        // The handshake consumes the connection's first line, so a plain line goes first; the next line — the first the
        // frame loop sees, i.e. inside the detection window — is the kickoff splice vector shape (DLC 8 + 4 extra hex,
        // oil 195). (First version of this test put the +4 line first, where the handshake took it: it never reached
        // detection — corrected after review.)
        val vs = replay(listOf(oil(70), ts(oil(195), 0)) + (0 until 20).map { oil(70) })
        assertEquals(70.0, vs.oilTempC, 0.0)
        assertEquals("no peak from the +4 line", 70.0, vs.session.peakOilTempC, 0.0)
        assertTrue(framingEvents().toString(), framingEvents().isEmpty())
        val li = DiagnosticLogger.linkIntegrity
        assertEquals("locked plain", false, li.framingTimestamped)
        assertEquals("the +4 line was refused (it came before the plain lock, so not counted as other-shape)", 1,
            li.refusedFrameLines)
    }

    @Test fun `a plain line inside a timestamped stream is refused`() = runTest {
        val vs = replay((0 until 10).map { ts(oil(70), it) } + oil(195) + (10 until 15).map { ts(oil(70), it) })
        assertEquals(70.0, vs.oilTempC, 0.0)
        assertEquals(70.0, vs.session.peakOilTempC, 0.0)
    }

    @Test fun `50 consecutive lines of the other shape on a locked connection log one FRAMING event, no switch`() = runTest {
        val vs = replay((0 until 10).map { oil(70) } + (0 until 120).map { ts(oil(90), it) }, stepMs = 20L)
        assertEquals("never switched mid-connection", 70.0, vs.oilTempC, 0.0)
        assertEquals(framingEvents().toString(), 1, framingEvents().size)
        assertTrue(framingEvents().single().contains("50 consecutive timestamped"))
        val li = DiagnosticLogger.linkIntegrity
        assertEquals(120, li.refusedFrameLines)
        assertEquals("all counted, not only the first 50", 120, li.refusedOtherShape)
    }

    // ── A new connection detects again ──────────────────────────────────────────────────────────────────────────────

    private class Lines(private val scope: kotlinx.coroutines.CoroutineScope, private val lines: List<String>) : SlcanTransport {
        private val ch = Channel<String>(Channel.UNLIMITED)
        override suspend fun open() { scope.launch { for (l in lines) { delay(50); ch.trySend(l) }; delay(200); ch.close() } }
        override suspend fun readLine(): String? = ch.receiveCatching().getOrNull()
        override suspend fun writeLine(frame: String) = Unit
        override fun close() { ch.close() }
        override val label = "lines"
        override val stockFirmwareLabel = "lines"
    }

    private object NoRecorder : LinkSession.Recorder {
        override val isRecording = false
        override val isPaused = false
        override val activeDriveId: Long? = null
        override fun pausedDurationMs() = 0L
        override fun resumeDrive() = Unit
        override fun stopDrive() = Unit
        override fun startDrive(sessionId: Long) = Unit
    }

    @Test fun `a new connection detects its framing again`() = runTest {
        val first = Lines(backgroundScope, (0 until 12).map { ts(oil(70), it) })     // timestamped, then EOF
        val second = Lines(backgroundScope, (0 until 12).map { oil(85) })            // plain
        val queue = ArrayDeque(listOf<SlcanTransport>(first, second))
        val state = MutableStateFlow(VehicleState(isConnected = true))
        val own = LinkOwnership()
        val session = LinkSession(own, own.begin(), state, NoRecorder, onFrameCounted = {}, perfRunSink = { _: PerfRunEntity -> })
        val conn = SlcanConnection(
            transportFactory = { queue.removeFirst() }, maxRetries = 2, rpmProvider = { 0.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { conn.connectHybrid({}, { state.value }).collect { (id, d) -> session.onCanFrame(id, d, 0.0) } }
        advanceTimeBy(60_000L)
        assertTrue("reconnected", queue.isEmpty())
        assertEquals("the plain second connection decodes", 85.0, state.value.oilTempC, 0.0)
    }
}
