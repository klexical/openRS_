package com.openrs.dash

import com.openrs.dash.can.BleAdapterProfile
import com.openrs.dash.can.BleGattCore
import com.openrs.dash.can.BleSetupPolicy
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.GattLink
import com.openrs.dash.can.Mode22
import com.openrs.dash.can.ObdConstants
import com.openrs.dash.can.RawReply
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanParser
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.PerfRunEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.runDidProbeScan
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * rc.8 build 39, #253: what the SLCAN line rule does with hex case and field edges.
 * The rule these tests pin: hex is read by VALUE in the ID, DLC, data and timestamp suffix, whatever its case (the
 * adapter's slcan_parse_frame only emits upper case, WiCAN PRO + USB source); the prefix's case is not hex — it is the
 * frame type (`t` 11-bit, `T` 29-bit). Everything else is refused and counted, never printed.
 *
 * Production path: SlcanParser; SlcanConnection → LinkSession (decode, stamps, peaks) over a line transport, or over the
 * real BleGattCore with a fake GATT where the bytes between lines matter; runDidProbeScan + the module ledgers for the
 * Mode 22 cases. JVM, virtual time. SYNTHETIC lines only. A connection's first line goes to the SLCAN handshake, so every
 * connection-level case opens with [SACRIFICE]; decode runs in a CAS update, so assertions read the final state.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlcanHexAndParserEdgesTest {
    private companion object {
        const val SACRIFICE = "t09080000C1FF617D0000"       // taken by the handshake, never decoded
        const val OIL_115 = "t0F8800BE0000000000AF"         // byte 1 0xBE − 50 = PTU 140, byte 7 0xAF − 60 = oil 115
        const val OIL_70 = "t0F88005A000000000082"          // oil 70, PTU 40
        val BAD_CHARS = listOf('G', 'g', 'x', ' ', '\t', '\n', '\r', '-', '+', '.', '٣', 'Ａ')
    }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    /** Every hex letter after the prefix lower-cased: the prefix is the frame type, not hex. */
    private fun lower(line: String) = line.take(1) + line.drop(1).lowercase()
    /** Hex letters alternate case, by position. */
    private fun mixed(line: String) = line.take(1) + line.drop(1).mapIndexed { i, c ->
        if (i % 2 == 0) c.lowercaseChar() else c.uppercaseChar() }.joinToString("")
    private fun ts(line: String, tick: Int) = line + "%04X".format(tick % 60_000)

    private fun events(type: String) = DiagnosticLogger.sessionEvents.filter { it.type == type }.map { it.message }
    private fun unattributed() = events("NRC_UNATTRIBUTED")
    private fun suppressed() = events("NRC_SUPPRESS")

    /** Real connection over a line transport: [lines] fed 50 ms apart. */
    private inner class Run(scope: TestScope, lines: List<String>, stepMs: Long = 50L) {
        val debug = mutableListOf<String>()
        var factories = 0
        val state = MutableStateFlow(VehicleState(isConnected = true))
        init {
            val own = LinkOwnership()
            val session = LinkSession(own, own.begin(), state, NoRecorder, onFrameCounted = {}, perfRunSink = { _: PerfRunEntity -> })
            val transport = ReplayTransport(scope.backgroundScope, lines.mapIndexed { i, l -> ReplayRow(100L + i * stepMs, "RX", l) })
            val conn = SlcanConnection(
                transportFactory = { factories++; transport }, maxRetries = 3, rpmProvider = { 0.0 },
                ioDispatcher = StandardTestDispatcher(scope.testScheduler), debugSink = { debug += it },
                clock = { scope.testScheduler.currentTime },
            )
            scope.backgroundScope.launch {
                conn.connectHybrid({ obd -> state.value = ObdMerge.merge(state.value, obd) }, { state.value })
                    .collect { (id, data) -> session.onCanFrame(id, data, 0.0) }
            }
            scope.advanceTimeBy(100L + lines.size * stepMs + 2_000L)
        }
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

    // ── Parser: the same bytes whatever the case ────────────────────────────────────────────────────────────────────

    @Test fun `every 11-bit ID reads by value in upper, lower and mixed case`() {
        for (id in 0..0x7FF) for (line in listOf("t%03X0".format(id), "t%03x0".format(id), mixed("t%03X0".format(id)))) {
            assertEquals(line, id, SlcanParser.parse(line)?.first)
        }
    }

    @Test fun `29-bit IDs read by value in upper, lower and mixed case`() {
        for (id in listOf(0x0ABCDEF0, 0x1ABCDEF0, 0x18DAF110, 0x1FFFFFFF, 0x00000800)) {
            for (line in listOf("T%08X0".format(id), "T%08x0".format(id), mixed("T%08X0".format(id)))) {
                assertEquals(line, id, SlcanParser.parse(line)?.first)
            }
        }
    }

    @Test fun `every data byte value reads the same in upper and lower case - standard, extended, both shapes`() {
        for (v in 0..0xFF) {
            val want = byteArrayOf(0x00, v.toByte())
            val cases = listOf("%02X".format(v), "%02x".format(v), "%02X".format(v).mapIndexed { i, c ->
                if (i == 0) c.lowercaseChar() else c }.joinToString(""))
            for (hex in cases) {
                assertArrayEquals(hex, want, SlcanParser.parse("t0F8200$hex")!!.second)
                assertArrayEquals(hex, want, SlcanParser.parse("T18DAF110200$hex")!!.second)
                assertArrayEquals(hex, want, SlcanParser.parse("t0F8200${hex}EA5F", timestamped = true)!!.second)
                assertArrayEquals(hex, want, SlcanParser.parse("T18DAF110200${hex}ea5f", timestamped = true)!!.second)
            }
        }
    }

    @Test fun `the timestamp suffix reads by value too - the 60 000 ms limit holds in every case`() {
        for (ok in listOf("EA5F", "ea5f", "Ea5F", "eA5f", "0a0b")) {
            assertNotNull(ok, SlcanParser.parse(OIL_70 + ok, timestamped = true))
            assertEquals(ok, SlcanParser.Shape.TIMESTAMPED, SlcanParser.shape(OIL_70 + ok))
        }
        for (bad in listOf("EA60", "ea60", "Ea60", "ffff", "FFFF", "fFfF")) {
            assertNull(bad, SlcanParser.parse(OIL_70 + bad, timestamped = true))
            assertNull(bad, SlcanParser.shape(OIL_70 + bad))
        }
    }

    @Test fun `case never changes a line's shape`() {
        for (dlc in 0..8) for (base in listOf("t7EB$dlc", "T18DAF110$dlc")) {
            val plain = base + "AF".repeat(dlc)
            for (line in listOf(plain, lower(plain), mixed(plain))) {
                assertEquals(line, SlcanParser.Shape.PLAIN, SlcanParser.shape(line))
                assertEquals(line, SlcanParser.Shape.TIMESTAMPED, SlcanParser.shape(line + "0a5f"))
                assertEquals(line, SlcanParser.Shape.TIMESTAMPED, SlcanParser.shape(line + "0A5F"))
            }
        }
    }

    @Test fun `the prefix is the frame type, not hex - a flipped prefix is never a valid line`() {
        // t lines have an odd length in both shapes, T lines an even one, so no line can change type by a flipped bit
        for (dlc in 0..8) {
            val std = "t7EB$dlc" + "00".repeat(dlc)
            val ext = "T18DAF110$dlc" + "00".repeat(dlc)
            for (flipped in listOf("T" + std.drop(1), "T" + std.drop(1) + "0A5F", "t" + ext.drop(1), "t" + ext.drop(1) + "0A5F")) {
                assertNull(flipped, SlcanParser.shape(flipped))
            }
        }
        assertNull("the RTR form the adapter can emit", SlcanParser.shape("r7EB0"))
        assertNull("the RTR form the adapter can emit", SlcanParser.shape("R18DAF1100"))
    }

    // ── Parser: field edges ─────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a DLC of 9 to F is refused in either case, with any data length, in both shapes`() {
        for (d in 9..15) for (dlcChar in listOf("%X".format(d), "%x".format(d))) for (dataLen in listOf(0, 8, d)) {
            val data = "AB".repeat(dataLen)
            for (head in listOf("t0F8", "T18DAF110")) {
                val line = "$head$dlcChar$data"
                assertNull(line, SlcanParser.parse(line, timestamped = false))
                assertNull(line, SlcanParser.parse(line + "0A5F", timestamped = true))
                assertNull(line, SlcanParser.shape(line))
                assertNull(line, SlcanParser.shape(line + "0A5F"))
            }
        }
    }

    @Test fun `any bad character at any position is refused - ID, DLC, data and suffix - and never read as a digit`() {
        // '+' and '-' because toIntOrNull(16) would take them; U+0663 and U+FF21 because Character.digit would
        val bases = listOf("t7E18AB00000000000000" to false, "T18DAF1108AB00000000000000" to false,
            "t7E18AB000000000000000A5F" to true, "T18DAF1108AB000000000000000A5F" to true, "t7E10" to false)
        for ((line, tsMode) in bases) for (at in 1 until line.length) for (c in BAD_CHARS) {
            val bad = line.substring(0, at) + c + line.substring(at + 1)
            assertNull("[$at]=$c in $line", SlcanParser.parse(bad, tsMode))
            assertNull("[$at]=$c in $line", SlcanParser.shape(bad))
        }
    }

    @Test fun `whitespace or a line ending anywhere around a line is refused by the parser`() {
        for (line in listOf(OIL_70, lower(OIL_70), "t7E10", "T18DAF1100")) {
            for (bad in listOf("$line\n", "$line\r", "$line\r\n", "$line ", " $line", "\t$line", "\n$line", "\r$line")) {
                assertNull(bad.replace("\n", "<LF>"), SlcanParser.parse(bad))
                assertNull(bad.replace("\n", "<LF>"), SlcanParser.shape(bad))
            }
        }
    }

    @Test fun `DLC 0 is an empty frame in both frame types, and any character after it is refused`() {
        for (line in listOf("t7e80", "t7E80", "T18daf1100", "T18DAF1100")) {
            assertEquals(line, 0, SlcanParser.parse(line)!!.second.size)
            assertEquals(line, 0, SlcanParser.parse(line + "0a5f", timestamped = true)!!.second.size)
            assertNull(line, SlcanParser.parse(line + "F"))
            assertNull(line, SlcanParser.parse(line + "FF"))
            assertNull("no suffix on a timestamped connection", SlcanParser.parse(line, timestamped = true))
        }
    }

    @Test fun `an 11-bit ID above 0x7FF does not exist on CAN and is refused`() {
        assertEquals(0x7FF, SlcanParser.parse("t7FF0")?.first)
        for (line in listOf("t8000", "tFFF0", "tfff0", "t8F88005A000000000082", "tABC0", "tabc0")) {
            assertNull(line, SlcanParser.parse(line))
            assertNull(line, SlcanParser.shape(line))
            assertNull(line, SlcanParser.shape(line + "0A5F"))
        }
    }

    @Test fun `a 29-bit ID above 0x1FFFFFFF does not exist on CAN and is refused - the whole range, not only by sign`() {
        assertEquals(0x1FFFFFFF, SlcanParser.parse("T1FFFFFFF0")?.first)
        for (line in listOf("T200000000", "T7FFFFFFF0", "T7fffffff0", "T800000000", "TFFFFFFFF0", "Tffffffff0")) {
            assertNull(line, SlcanParser.parse(line))
            assertNull(line, SlcanParser.shape(line))
            assertNull(line, SlcanParser.shape(line + "0A5F"))
        }
    }

    @Test fun `Mode22 request reads the ID and DID by value in any case`() {
        val want = 0x7E0 to 0xABCD
        assertEquals(want, Mode22.request("t7E080322ABCD00000000\r"))
        assertEquals(want, Mode22.request("t7e080322abcd00000000\r"))
        assertEquals(want, Mode22.request("t7e080322AbCd00000000\r"))
        assertNull("service 19, not a Mode 22 read", Mode22.request("t7E08031902FF00000000\r"))
    }

    // ── Connection: identical bytes, identical state ────────────────────────────────────────────────────────────────

    /** Frames whose IDs and data hold hex letters, across decoders. */
    private val stream = listOf(OIL_115, "t1308000000000000" + "1A2B", "t2F080AB3CD0000000000", "t1A480000000082000000", "t09080000C1FF617D0000")

    private fun comparable(vs: VehicleState) = vs.copy(lastUpdate = 0L, fieldLastUpdateMs = emptyMap())

    @Test fun `lower and mixed case streams produce the same state as upper case, stamps included`() = runTest {
        val upper = Run(this, listOf(SACRIFICE) + stream).state.value
        assertEquals("oil decoded, not the default", 115.0, upper.oilTempC, 0.0)
        assertEquals(140.0, upper.ptuTempC, 0.0)
        assertEquals(0x1A2B * 0.01, upper.speedKph, 1e-9)
        val low = Run(this, listOf(SACRIFICE) + stream.map(::lower)).state.value
        val mix = Run(this, listOf(SACRIFICE) + stream.map(::mixed)).state.value
        assertEquals(comparable(upper), comparable(low))
        assertEquals(comparable(upper), comparable(mix))
        assertEquals(upper.fieldLastUpdateMs.keys, low.fieldLastUpdateMs.keys)
        assertEquals(upper.fieldLastUpdateMs.keys, mix.fieldLastUpdateMs.keys)
        assertEquals("nothing was refused", 0, DiagnosticLogger.linkIntegrity.refusedFrameLines)
    }

    @Test fun `a 29-bit frame is one identifier whatever its case - same inventory row, same bytes`() = runTest {
        Run(this, listOf(SACRIFICE, "T18DAF1102AB10", "T18daf1102ab10", "T18dAf1102aB10"))
        val row = DiagnosticLogger.frameInventorySnapshot[0x18DAF110]
        assertNotNull(DiagnosticLogger.frameInventorySnapshot.keys.toString(), row)
        assertEquals(3, row!!.totalReceived)
        assertFalse("the three cases are the same bytes", row.hasChanged)
        assertEquals(0, DiagnosticLogger.linkIntegrity.refusedFrameLines)
    }

    // ── Connection: timestamp detection with mixed case ─────────────────────────────────────────────────────────────

    @Test fun `lower and mixed case timestamped frames lock the connection to timestamped and decode`() = runTest {
        val lines = (0 until 12).map { i ->
            val l = ts(OIL_115, i * 2_731)                                   // suffixes hold hex letters
            when (i % 3) { 0 -> lower(l); 1 -> mixed(l); else -> l }
        }
        val vs = Run(this, lines).state.value
        assertEquals(115.0, vs.oilTempC, 0.0)
        assertEquals(true, DiagnosticLogger.linkIntegrity.framingTimestamped)
        assertEquals(events("FRAMING").toString(), 1, events("FRAMING").size)
        // By design: until the lock a connection is plain, so the seven +4 lines before the 8th are refused and counted
        assertEquals("only the frames before the lock", 7, DiagnosticLogger.linkIntegrity.refusedFrameLines)
    }

    @Test fun `control - exactly 8 timestamped frames after the handshake lock it, 7 do not - lower case`() = runTest {
        Run(this, listOf(lower(ts(OIL_70, 0))) + (1..7).map { lower(ts(OIL_115, it)) })
        assertEquals("7 seen: still undecided", null, DiagnosticLogger.linkIntegrity.framingTimestamped)
        DiagnosticLogger.sessionStart("next", 0, prefs = null)
        val vs = Run(this, listOf(lower(ts(OIL_70, 0))) + (1..8).map { lower(ts(OIL_115, it)) }).state.value
        assertEquals(true, DiagnosticLogger.linkIntegrity.framingTimestamped)
        assertEquals("the frame that locked it decodes", 115.0, vs.oilTempC, 0.0)
    }

    @Test fun `one lower-case plain frame inside the detection window locks plain, like an upper-case one`() = runTest {
        val tsLines = (0 until 3).map { lower(ts(OIL_115, it)) }
        val vs = Run(this, listOf(SACRIFICE) + tsLines + lower(OIL_70) + (3 until 20).map { lower(ts(OIL_115, it)) }).state.value
        assertEquals("the plain line decodes, nothing +4 does", 70.0, vs.oilTempC, 0.0)
        assertEquals(70.0, vs.peakOilTempC, 0.0)
        val li = DiagnosticLogger.linkIntegrity
        assertEquals(false, li.framingTimestamped)
        assertEquals("all +4 lines refused", 20, li.refusedFrameLines)
        assertEquals("those after the lock are the other shape", 17, li.refusedOtherShape)
    }

    @Test fun `a refused line inside the detection window neither counts nor breaks detection`() = runTest {
        val good = (0 until 8).map { mixed(ts(OIL_70, it)) }
        val noise = listOf("t0F89005A000000000082", "t0f8a005a000000000082", "t0F88005A00000000G082")
        val vs = Run(this, listOf(lower(ts(OIL_70, 99))) + good.take(4) + noise + good.drop(4)).state.value
        assertEquals(true, DiagnosticLogger.linkIntegrity.framingTimestamped)
        assertEquals(70.0, vs.oilTempC, 0.0)
        assertEquals("3 malformed + the 7 frames before the lock", 10, DiagnosticLogger.linkIntegrity.refusedFrameLines)
        assertEquals("nothing counted as the other shape", 0, DiagnosticLogger.linkIntegrity.refusedOtherShape)
    }

    @Test fun `50 lower-case lines of the other shape count once as an event, every line as refused`() = runTest {
        val vs = Run(this, listOf(SACRIFICE) + (0 until 3).map { lower(OIL_70) } + (0 until 60).map { lower(ts(OIL_115, it)) },
            stepMs = 20L).state.value
        assertEquals(70.0, vs.oilTempC, 0.0)
        assertEquals(events("FRAMING").toString(), 1, events("FRAMING").size)
        assertEquals(60, DiagnosticLogger.linkIntegrity.refusedOtherShape)
        assertEquals(60, DiagnosticLogger.linkIntegrity.refusedFrameLines)
    }

    // ── Connection: edges reach no decoder, are counted, are never printed ──────────────────────────────────────────

    private val refusedFrameLines = listOf(
        "t0F89C0FFEE00000000AF", "t0F8AC0FFEE00000000AFAF", "t0f8fC0FFEE",                    // DLC 9, A, f
        "t0F88C0FFEEG00000000AF".take(21), "t0F8GC0FFEE",                                     // non-hex data, DLC
        "tG0F8C0FFEE00000000AF", "t0F88C0FFEE00000000",                                       // non-hex ID; short
        "$OIL_115\n", "$OIL_115 ",                                                            // trailing LF; trailing space
        "t8F88C0FFEE000000AF00", "tFFF8C0FFEE000000AF00",                                     // ID above 0x7FF
        "T20000000" + "8C0FFEE000000AF00", "T7FFFFFFF" + "8C0FFEE000000AF00",                 // ID above 0x1FFFFFFF
        "T80000000" + "8C0FFEE000000AF00", "TFFFFFFFF" + "8C0FFEE000000AF00",
    )

    @Test fun `every refused frame line is counted once, changes nothing, and the next good line decodes`() = runTest {
        val run = Run(this, listOf(SACRIFICE, OIL_70) + refusedFrameLines + OIL_115)
        val vs = run.state.value
        assertEquals(115.0, vs.oilTempC, 0.0)
        val li = DiagnosticLogger.linkIntegrity
        assertEquals(refusedFrameLines.size, li.refusedFrameLines)
        assertEquals(0, li.refusedOtherShape)
        assertEquals("one connection, no reconnect", 1, run.factories)
        assertEquals("only the good IDs in the inventory, no impossible ID", setOf(0x0F8), DiagnosticLogger.frameInventorySnapshot.keys)
    }

    @Test fun `no sink prints a refused line - debug, events, export summary, export JSON, inventory`() = runTest {
        val run = Run(this, listOf(SACRIFICE, OIL_70) + refusedFrameLines + (0 until 60).map { ts(OIL_115, it) })
        val li = DiagnosticLogger.linkIntegrity
        assertEquals("the refusals are there to print", refusedFrameLines.size + 60, li.refusedFrameLines)
        assertEquals(1, events("FRAMING").size)
        val sinks = mapOf(
            "debug" to run.debug.joinToString("\n"),
            "events" to DiagnosticLogger.sessionEvents.joinToString("\n") { it.message },
            "summary" to DiagnosticReportBuilder.buildSummaryText("t"),
            "json" to DiagnosticReportBuilder.buildDetailJson("t"),
            "inventory" to DiagnosticLogger.frameInventorySnapshot.values.joinToString("\n") { it.firstRawHex + it.lastRawHex },
        )
        for ((name, text) in sinks) for (marker in listOf("C0FFEE", "C0 FF EE", "c0ffee", "00BE", "00 BE")) {
            assertFalse("$name holds $marker:\n$text", marker in text)
        }
        assertTrue(sinks["summary"]!!, "${li.refusedFrameLines} frame line(s) refused" in sinks["summary"]!!)
    }

    @Test fun `lines that are not frame lines are not counted as refused frames - RTR, text, blank, leading space`() = runTest {
        val vs = Run(this, listOf(SACRIFICE, OIL_70, "r0F80", "R18DAF1100", "OK", "", " $OIL_115", "x")).state.value
        assertEquals(70.0, vs.oilTempC, 0.0)
        assertEquals(0, DiagnosticLogger.linkIntegrity.refusedFrameLines)
    }

    @Test fun `short and empty frames on every response ID reach the parsers safely - connection stays up`() = runTest {
        val shorts = listOf("", "62", "0362", "046203", "05620304", "7F", "037F", "037F22", "10", "100A", "21")
        val lines = (ObdConstants.OBD_RESPONSE_IDS + setOf(ObdConstants.PCM_RESPONSE_ID)).flatMap { id ->
            shorts.flatMap { hex -> listOf("t%03X%d%s".format(id, hex.length / 2, hex), lower("t%03X%d%s".format(id, hex.length / 2, hex))) }
        }
        assertTrue(lines.size > 100)
        val run = Run(this, listOf(SACRIFICE) + lines + OIL_115, stepMs = 5L)
        assertEquals(115.0, run.state.value.oilTempC, 0.0)
        assertEquals("no exception ended the connection", 1, run.factories)
        assertEquals("all well-formed", 0, DiagnosticLogger.linkIntegrity.refusedFrameLines)
    }

    @Test fun `every known CAN ID at every short DLC decodes safely in both cases`() = runTest {
        val lines = CanDecoder.KNOWN_IDS.flatMap { id ->
            (0..7).flatMap { dlc -> listOf("t%03X%d%s".format(id, dlc, "AF".repeat(dlc)), lower("t%03X%d%s".format(id, dlc, "AF".repeat(dlc)))) }
        }
        val run = Run(this, listOf(SACRIFICE) + lines + OIL_70, stepMs = 2L)
        assertEquals(70.0, run.state.value.oilTempC, 0.0)
        assertEquals(1, run.factories)
    }

    @Test fun `a 29-bit frame whose number fits 11 bits is a different identifier and never reaches the 11-bit decoders`() = runTest {
        // Byte for byte OIL_115 under the 29-bit identifier 0x000000F8. Lost distinction: parse() returns only the number
        val vs = Run(this, listOf(SACRIFICE, OIL_70, "T000000F8800BE0000000000AF", "T000000f8800be0000000000af")).state.value
        assertEquals(70.0, vs.oilTempC, 0.0)
        assertEquals("no peak from another identifier", 70.0, vs.peakOilTempC, 0.0)
        assertEquals(2, DiagnosticLogger.linkIntegrity.refusedFrameLines)
    }

    @Test fun `a 29-bit reply numbered like the PCM's is not the PCM's - its request stays unanswered`() = runTest {
        val ecu = CasedEcu(backgroundScope, { it }, probeReply = { _, _ -> null }) { positive(0x7E8, it) }
        val conn = connect(ecu)
        val shown = mutableListOf<DiagnosticLogger.ProbeResult>()
        ecu.inject("T000007E880462ABCDBA000000", 100L)
        runDidProbeScan("PCM", 0x7E0, 0x7E8, listOf(0xABCD), conn::sendRawQuery, { false }) { shown += it }
        assertEquals(listOf(DiagnosticLogger.ProbeResult(0xABCD, "TIMEOUT", "")), shown)
    }

    // ── Mode 22: a reply matches by value, whatever its case ────────────────────────────────────────────────────────

    /** Mode 22 ECU behind 0x7E0 / 0x726, every line it sends passing through [case]. Requests are read by value. */
    private class CasedEcu(
        private val scope: CoroutineScope,
        private val case: (String) -> String,
        private val probeReply: (id: Int, did: Int) -> String? = { id, did -> "t%03X80462%04XBA000000".format(id + 8, did) },
        private val bcm: (Int) -> String? = { did -> "t72E80562%04X00000000".format(did) },
        private val poll: (Int) -> String?,
    ) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED).apply { trySend(case(SACRIFICE)) }
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receiveCatching().getOrNull()
        override suspend fun writeLine(frame: String) {
            val (id, did) = Mode22.request(frame) ?: return
            if (id != 0x7E0 && id != 0x726) return
            val (line, afterMs) = if (did in 0xAB00..0xABFF) probeReply(id, did) to 300L
                else if (id == 0x7E0) poll(did) to 40L else bcm(did) to 40L
            if (line != null) inject(line, afterMs)
        }
        fun inject(line: String, afterMs: Long) { scope.launch { delay(afterMs); lines.trySend(case(line)) } }
        override fun close() { lines.close() }
        override val label = "cased ECU"
        override val stockFirmwareLabel = "cased"
    }

    private fun positive(resp: Int, did: Int) = "t%03X80562%04X00000000".format(resp, did)
    private fun nrc(resp: Int, code: Int) = "t%03X8037F22%02X00000000".format(resp, code)

    private fun TestScope.connect(ecu: CasedEcu, rpm: Double = 1_500.0): SlcanConnection {
        val conn = SlcanConnection(
            transportFactory = { ecu }, maxRetries = 1, rpmProvider = { rpm },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(25_000L)
        return conn
    }

    private suspend fun probe(conn: SlcanConnection, req: Int = 0x7E0): List<DiagnosticLogger.ProbeResult> {
        val shown = mutableListOf<DiagnosticLogger.ProbeResult>()
        runDidProbeScan(if (req == 0x7E0) "PCM" else "BCM", req, req + 8, listOf(0xABCD), conn::sendRawQuery, { false }) { shown += it }
        return shown
    }

    private fun TestScope.suppressionWorks(case: (String) -> String) {
        var rejecting = false
        connect(CasedEcu(backgroundScope, case) { did -> if (rejecting && did == 0x03ED) nrc(0x7E8, 0x31) else positive(0x7E8, did) })
        rejecting = true
        advanceTimeBy(30_000L)
        // Only possible when every earlier reply resolved its request by value (#216: one unanswered request ends it)
        assertTrue(unattributed().toString(), unattributed().none { it.contains("still unanswered") })
        assertTrue(DiagnosticLogger.nrcSuppressed.keys.toString(), 0x03ED in DiagnosticLogger.nrcSuppressed.keys)
    }

    @Test fun `lower-case poll replies resolve their requests - a lower-case NRC is attributed and suppressed`() = runTest { suppressionWorks(::lower) }
    @Test fun `mixed-case poll replies resolve their requests - a mixed-case NRC is attributed and suppressed`() = runTest { suppressionWorks(::mixed) }

    private val found = DiagnosticLogger.ProbeResult(0xABCD, "FOUND", "BA 00 00 00")

    @Test fun `a lower-case probe reply is the probe's - the result prints the bytes, not the line`() = runTest {
        val conn = connect(CasedEcu(backgroundScope, ::lower) { positive(0x7E8, it) })
        assertEquals(listOf(found), probe(conn))
    }

    @Test fun `a mixed-case probe reply is the probe's`() = runTest {
        val conn = connect(CasedEcu(backgroundScope, ::mixed) { positive(0x7E8, it) })
        assertEquals(listOf(found), probe(conn))
    }

    @Test fun `a lower-case first frame of a multi-frame reply is matched by its DID`() = runTest {
        val ecu = CasedEcu(backgroundScope, ::lower, probeReply = { _, _ -> "t7E88100A62ABCD0A0B0C" }) { positive(0x7E8, it) }
        assertEquals(listOf(DiagnosticLogger.ProbeResult(0xABCD, "FOUND", "0A 0B 0C")), probe(connect(ecu)))
    }

    @Test fun `a lower-case reply to another DID is not the probe's`() = runTest {
        val ecu = CasedEcu(backgroundScope, ::lower, probeReply = { _, _ -> "t7E880462ABCEBA000000" }) { positive(0x7E8, it) }
        assertEquals(listOf(DiagnosticLogger.ProbeResult(0xABCD, "TIMEOUT", "")), probe(connect(ecu)))
    }

    @Test fun `a lower-case NRC with only the probe waiting is the probe's NRC`() = runTest {
        val ecu = CasedEcu(backgroundScope, ::lower, probeReply = { _, _ -> nrc(0x7E8, 0x31) }) { positive(0x7E8, it) }
        assertEquals(listOf(DiagnosticLogger.ProbeResult(0xABCD, "NRC", "NRC 0x31 — request out of range")),
            probe(connect(ecu, rpm = 0.0)))
        assertTrue(suppressed().toString(), suppressed().isEmpty())
    }

    @Test fun `a mixed-case NRC another request could own is ambiguous, charged to nobody`() = runTest {
        val ecu = CasedEcu(backgroundScope, ::mixed, probeReply = { _, _ -> nrc(0x7E8, 0x31) }) { if (it == 0x0462) null else positive(0x7E8, it) }
        assertEquals(listOf(DiagnosticLogger.ProbeResult(0xABCD, "NRC_AMBIGUOUS",
            "NRC 0x31 — request out of range; another request could own it")), probe(connect(ecu)))
        assertTrue(suppressed().toString(), suppressed().isEmpty())
    }

    @Test fun `a BCM probe with lower-case replies - the module ledger matches by value`() = runTest {
        val ecu = CasedEcu(backgroundScope, ::lower, probeReply = { id, _ -> "t%03X80462ABCDBA000000".format(id + 8) }) { positive(0x7E8, it) }
        assertEquals(listOf(found), probe(connect(ecu), req = 0x726))
    }

    @Test fun `a lower-case raw request is the same request as its upper-case form`() = runTest {
        val conn = connect(CasedEcu(backgroundScope, { it }) { positive(0x7E8, it) })
        val reply = conn.sendRawQuery(0x7E8, "t7e080322abcd00000000\r", 1_000L)
        assertTrue(reply.toString(), reply is RawReply.Frame)
        assertEquals(0xABCD, Mode22.positiveDid((reply as RawReply.Frame).data))
    }

    // ── The adapter's real bytes through the BLE core: `OPENRS:…\r\n` (slcan.c) leaves its LF on the next line ─────────

    private val pro = BleAdapterProfile.WICAN_PRO
    private val probeReplyBytes = "OPENRS:PRO v1.2-rc.1\r\n"          // format from slcan_parse_str, PRO and USB

    private inner class FakeGatt(val core: BleGattCore, val scope: CoroutineScope, val poll: (Int) -> String?) : GattLink {
        override fun discoverServices() = true
        override fun setNotify(service: UUID, characteristic: UUID, enable: Boolean) = true
        override fun writeCccd(service: UUID, characteristic: UUID, value: ByteArray): Boolean {
            core.onDescriptorWrite(true, BleSetupPolicy.GATT_SUCCESS); return true
        }
        override fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean {
            core.onWrite(BleSetupPolicy.GATT_SUCCESS)
            val text = String(value, Charsets.ISO_8859_1)
            val request = Mode22.request(text)
            when {
                text.contains("OPENRS?") -> scope.launch { delay(20); core.onNotify(pro.notify, probeReplyBytes.toByteArray()) }
                request != null && request.first == 0x7E0 -> poll(request.second)?.let { reply ->
                    scope.launch { delay(40); core.onNotify(pro.notify, "$reply\r".toByteArray()) }
                }
            }
            return true
        }
        override fun requestMtu(mtu: Int): Boolean { core.onMtuChanged(mtu, 0); return true }
        override fun bondState() = BleGattCore.BOND_BONDED
        override fun createBond() = true
        override fun disconnect() = Unit
        override fun close() = Unit
    }

    private inner class Ble(scope: TestScope, rpm: Double = 0.0, poll: (Int) -> String? = { null }) {
        val state = MutableStateFlow(VehicleState(isConnected = true))
        val core = BleGattCore("MEATPI_PRO")
        val conn: SlcanConnection
        fun notifyAt(atMs: Long, text: String, scope: TestScope) { scope.backgroundScope.launch { delay(atMs); core.onNotify(pro.notify, text.toByteArray()) } }
        init {
            val own = LinkOwnership()
            val session = LinkSession(own, own.begin(), state, NoRecorder, onFrameCounted = {}, perfRunSink = { _: PerfRunEntity -> })
            val gatt = FakeGatt(core, scope.backgroundScope, poll)
            val transport = object : SlcanTransport {
                override suspend fun open() {
                    scope.backgroundScope.launch {
                        delay(10); core.onConnected(gatt); delay(10)
                        core.onServicesDiscovered(0, mapOf(pro.service to mapOf(
                            pro.write to BleGattCore.PROPERTY_WRITE, pro.notify to BleGattCore.PROPERTY_NOTIFY)))
                    }
                    core.setup()
                }
                override suspend fun readLine() = core.readLine()
                override suspend fun writeLine(frame: String) = core.writeLine(frame)
                override fun close() = core.close()
                override val label = "BLE fake"
                override val stockFirmwareLabel = "fake"
            }
            conn = SlcanConnection(
                transportFactory = { transport }, maxRetries = 1, rpmProvider = { rpm },
                ioDispatcher = StandardTestDispatcher(scope.testScheduler), debugSink = {}, clock = { scope.testScheduler.currentTime },
            )
            scope.backgroundScope.launch {
                conn.connectHybrid({ obd -> state.value = ObdMerge.merge(state.value, obd) }, { state.value })
                    .collect { (id, data) -> session.onCanFrame(id, data, 0.0) }
            }
        }
    }

    @Test fun `the frame right after the adapter's CR LF probe reply decodes - the LF belongs to the reply`() = runTest {
        val ble = Ble(this)
        ble.notifyAt(1_000L, "$OIL_70\r", this)
        advanceTimeBy(3_000L)
        assertTrue("the reply was recognized", DiagnosticLogger.isOpenRsFirmware)
        assertEquals(70.0, ble.state.value.oilTempC, 0.0)
    }

    @Test fun `a poll reply right after the probe reply resolves its request - the LF can't end NRC attribution`() = runTest {
        var rejecting = false
        Ble(this, rpm = 1_500.0) { did -> if (rejecting && did == 0x03ED) nrc(0x7E8, 0x31) else positive(0x7E8, did) }
        advanceTimeBy(25_000L)                                  // PCM poller starts at 20 s; the adapter stays quiet till then
        rejecting = true
        advanceTimeBy(30_000L)
        assertTrue(unattributed().toString(), unattributed().none { it.contains("still unanswered") })
        assertTrue(DiagnosticLogger.nrcSuppressed.keys.toString(), 0x03ED in DiagnosticLogger.nrcSuppressed.keys)
    }

    @Test fun `only the adapter's one leading LF is dropped - a trailing LF, two LFs and a leading space stay refused`() = runTest {
        val ble = Ble(this)
        ble.notifyAt(1_000L, "$OIL_70\r", this)                 // takes the probe reply's LF
        ble.notifyAt(1_100L, "$OIL_115\n\r", this)
        ble.notifyAt(1_200L, "\n\n$OIL_115\r", this)
        ble.notifyAt(1_300L, " $OIL_115\r", this)
        ble.notifyAt(1_400L, "\t$OIL_115\r", this)
        advanceTimeBy(3_000L)
        assertEquals(70.0, ble.state.value.oilTempC, 0.0)
        assertEquals(70.0, ble.state.value.peakOilTempC, 0.0)
        assertEquals("the trailing LF line starts with t, the others don't", 1, DiagnosticLogger.linkIntegrity.refusedFrameLines)
    }

    @Test fun `an +FRS reply right after the probe reply reaches the command channel`() = runTest {
        val ble = Ble(this)
        ble.notifyAt(1_000L, "+FRS:OK\r", this)
        advanceTimeBy(3_000L)
        assertEquals("+FRS:OK", ble.conn.commandResponseChannel.tryReceive().getOrNull())
    }
}
