package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanParser
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.PerfRunEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #551: a spliced line whose leftover length matches its DLC digit is well-formed to [com.openrs.dash.can.SlcanParser]
 * (the length check is all the parser has), e.g. a one-byte frame on 0x0F8, which always carries 8 bytes. Such a frame
 * is shorter than its ID's decoder needs: the frame loop refuses it and counts it with the refused frame lines, under
 * its own sub-count, so the export shows it instead of filing it as an unknown frame. A short frame on an ID with no
 * decoder (a module reply, an unknown ID) still parses and passes on.
 *
 * Production path: SlcanConnection → LinkSession over a line transport, then the export builders. JVM, virtual time.
 * SYNTHETIC lines only. A connection's first line goes to the SLCAN handshake, so each run opens with [SACRIFICE].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlcanShortFrameTest {
    private companion object {
        const val SACRIFICE = "t09080000C1FF617D0000"       // taken by the handshake, never decoded
        const val OIL_70 = "t0F88005A000000000082"          // byte 7 0x82 − 60 = oil 70
        const val OIL_115 = "t0F8800BE0000000000AF"         // oil 115
        /** The tail of a 0x0F8 line spliced onto the head of another: DLC digit 1, one byte left, so the length fits. */
        const val SPLICED_0F8 = "t0F81AF"
    }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private inner class Run(scope: TestScope, lines: List<String>, stepMs: Long = 50L) {
        val state = MutableStateFlow(VehicleState(isConnected = true))
        val frames = mutableListOf<Pair<Int, Int>>()                    // (id, size) of every frame the loop passed on
        init {
            val own = LinkOwnership()
            val session = LinkSession(own, own.begin(), com.openrs.dash.data.LiveTelemetry(state), NoRecorder,
                onFrameCounted = {}, perfRunSink = { _: PerfRunEntity -> })
            val transport = ReplayTransport(scope.backgroundScope, lines.mapIndexed { i, l -> ReplayRow(100L + i * stepMs, "RX", l) })
            val conn = SlcanConnection(
                transportFactory = { transport }, maxRetries = 3, rpmProvider = { 0.0 },
                ioDispatcher = StandardTestDispatcher(scope.testScheduler), debugSink = {},
                clock = { scope.testScheduler.currentTime },
            )
            scope.backgroundScope.launch {
                conn.connectHybrid({ obd -> state.value = ObdMerge.merge(state.value, obd) }, { state.value })
                    .collect { (id, data) -> frames += id to data.size; session.onCanFrame(id, data, 0.0) }
            }
            scope.advanceTimeBy(100L + lines.size * stepMs + 2_000L)
        }
    }

    private object NoRecorder : LinkSession.Recorder {
        override val activeDriveId: Long? = null
    }

    @Test fun `a spliced one-byte frame on an 8-byte ID is refused, counted and in the export's refused-line count`() = runTest {
        val run = Run(this, listOf(SACRIFICE, OIL_70, SPLICED_0F8, OIL_115))
        assertEquals("the good frames around it decode", 115.0, run.state.value.oilTempC, 0.0)
        assertEquals("never passed on", listOf(0x0F8 to 8, 0x0F8 to 8), run.frames)
        val li = DiagnosticLogger.linkIntegrity
        assertEquals(1, li.refusedFrameLines)
        assertEquals(1, li.refusedShortFrames)
        assertEquals(0, li.refusedOtherShape)
        val summary = DiagnosticReportBuilder.buildSummaryText("t")
        assertTrue(summary, "1 frame line(s) refused (0 of the other shape, 1 shorter than its ID's decoder needs)" in summary)
        val json = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("linkIntegrity")
        assertEquals(1, json.getInt("refusedFrameLines"))
        assertEquals(1, json.getInt("refusedShortFrames"))
        val row = DiagnosticLogger.frameInventorySnapshot[0x0F8]
        assertNotNull(row)
        assertEquals("the inventory holds the two good frames only", 2, row!!.totalReceived)
    }

    @Test fun `every decoded ID refuses a frame one byte shorter than its decoder needs, and passes one that long`() = runTest {
        val lines = CanDecoder.KNOWN_IDS.filter { (CanDecoder.minBytes(it) ?: 0) > 0 }.flatMap { id ->
            val need = CanDecoder.minBytes(id)!!
            listOf("t%03X%d%s".format(id, need - 1, "00".repeat(need - 1)), "t%03X%d%s".format(id, need, "00".repeat(need)))
        }
        assertTrue(lines.size > 40)
        val run = Run(this, listOf(SACRIFICE) + lines, stepMs = 5L)
        assertEquals(lines.size / 2, DiagnosticLogger.linkIntegrity.refusedShortFrames)
        assertEquals(lines.size / 2, DiagnosticLogger.linkIntegrity.refusedFrameLines)
        assertEquals("each ID's full-length frame passes", lines.size / 2, run.frames.size)
    }

    @Test fun `short frames on IDs with no decoder still parse and pass on - module replies, unknown IDs, 0x300`() = runTest {
        val lines = listOf("t7E83036203", "t72E110", "t1233ABCDEF", "t1230", "t3001FF")
        val run = Run(this, listOf(SACRIFICE) + lines)
        assertEquals("broadcasts pass on", listOf(0x123 to 3, 0x123 to 0, 0x300 to 1), run.frames)
        for (l in lines) assertNotNull(l, SlcanParser.parse(l))           // module replies parse, then take the OBD path
        assertEquals(0, DiagnosticLogger.linkIntegrity.refusedFrameLines)
        assertEquals(0, DiagnosticLogger.linkIntegrity.refusedShortFrames)
    }

    // ── The table agrees with the decoders: a refused frame is one its decoder would have ignored ───────────────────

    @Test fun `a frame one byte short of the table decodes to nothing - the table never refuses a usable frame`() {
        val base = VehicleState()
        for (id in CanDecoder.KNOWN_IDS) {
            val need = CanDecoder.minBytes(id) ?: continue
            for (fill in listOf(0x00, 0xFF, 0x5A)) {
                val decoded = CanDecoder.decode(id, ByteArray(need - 1) { fill.toByte() }, base)
                assertTrue("0x%03X with %d bytes changed state".format(id, need - 1), decoded == null || decoded === base)
            }
        }
    }

    @Test fun `every ID's decoder reads a zero frame of the table's length - the table is not set too high`() {
        val base = VehicleState()
        for (id in CanDecoder.KNOWN_IDS) {
            // 0x40A changes state only once all three VIN pages are in: its 7 is held by the test above alone
            if (id == CanDecoder.ID_VIN_MUX) continue
            val need = CanDecoder.minBytes(id) ?: continue
            assertNotNull("0x%03X with %d bytes".format(id, need), CanDecoder.decode(id, ByteArray(need), base))
        }
    }

    @Test fun `IDs with no length rule`() {
        assertNull("0x300 is reserved: no bytes read", CanDecoder.minBytes(CanDecoder.ID_CHARGING_STATUS))
        assertNull("a module reply is not a broadcast", CanDecoder.minBytes(0x7E8))
        assertNull(CanDecoder.minBytes(0x123))
    }
}
