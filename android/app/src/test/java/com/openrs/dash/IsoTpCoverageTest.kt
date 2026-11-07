package com.openrs.dash

import com.openrs.dash.can.IsoTpBuffer
import com.openrs.dash.can.ObdConstants
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DtcScanner
import com.openrs.dash.diagnostics.FreezeFrameParser
import com.openrs.dash.diagnostics.runDidProbeScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * ISO-TP beyond the BCM happy path (#253 diagnostic and replay coverage, stream A4) — through the real [IsoTpBuffer] and the
 * production [SlcanConnection] paths that reassemble: the BCM frame-loop buffer, the DTC scan's per-module buffers and
 * `assembleIsotpResponse` (DTC clear, freeze frames). A scripted transport feeds SLCAN lines after the handshake line,
 * which the connection consumes itself. JVM, virtual time, SYNTHETIC frames only — no adapter, no module.
 *
 * The rule pinned throughout: a reply is delivered whole and byte-exact or not at all; a fault never delivers partial or
 * stitched bytes and never blocks the module's next reply. PCM / AWD replies have no reassembly and no flow control.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class IsoTpCoverageTest {

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    // ── Frame builders ───────────────────────────────────────────────────────

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun ints(b: ByteArray) = b.map { it.toInt() and 0xFF }

    /** A payload whose every byte differs from its neighbours and from padding, so a shift or a stitch can't go unseen. */
    private fun pattern(len: Int, salt: Int = 0) = ByteArray(len) { ((it * 7 + 1 + salt) and 0xFF).toByte() }

    private fun ff(len: Int, payload: ByteArray): ByteArray {
        val f = ByteArray(8) { PAD }
        f[0] = (0x10 or (len shr 8)).toByte()
        f[1] = (len and 0xFF).toByte()
        System.arraycopy(payload, 0, f, 2, minOf(6, payload.size))
        return f
    }

    /** ISO-TP segmentation: FF + CFs (sequence 1 … F, 0, 1 …), the last CF padded to 8 bytes. */
    private fun segment(payload: ByteArray): List<ByteArray> {
        val out = mutableListOf(ff(payload.size, payload))
        var off = 6
        var seq = 1
        while (off < payload.size) {
            val f = ByteArray(8) { PAD }
            f[0] = (0x20 or seq).toByte()
            val n = minOf(7, payload.size - off)
            System.arraycopy(payload, off, f, 1, n)
            out += f
            off += n
            seq = (seq + 1) and 0x0F
        }
        return out
    }

    private fun line(id: Int, f: ByteArray) = "t%03X%d".format(id, f.size) + f.joinToString("") { "%02X".format(it) }
    private fun lines(id: Int, frames: List<ByteArray>) = frames.map { line(id, it) }

    /** Feeds every frame, returning what was delivered (never expected to throw). */
    private fun IsoTpBuffer.feedAll(frames: List<ByteArray>) = frames.mapNotNull { feed(it).first }

    private fun assertWholeReplyStillWorks(buf: IsoTpBuffer, salt: Int) {
        val next = pattern(30, salt)
        assertArrayEquals(next, buf.feedAll(segment(next)).single())
    }

    // ── Buffer: sequence wrap ────────────────────────────────────────────────

    @Test fun `a 200-byte reply wraps the sequence F-0-1 and reassembles byte exact`() {
        val payload = pattern(200)
        val frames = segment(payload)
        assertTrue("sequence 0 and a second 1 are really used", frames.count { it[0].toInt() == 0x21 } >= 2)
        val buf = IsoTpBuffer()
        val delivered = frames.dropLast(1).mapNotNull { buf.feed(it).first }
        assertTrue("nothing delivered before the last consecutive frame", delivered.isEmpty())
        assertArrayEquals(payload, buf.feed(frames.last()).first)
    }

    @Test fun `the 12-bit maximum 4095 wraps repeatedly and stops at the declared length`() {
        val payload = pattern(4095)
        val frames = segment(payload)
        assertEquals(1 + 585, frames.size)                       // 6 + 584 × 7 + 1: the last CF carries one byte + padding
        val out = IsoTpBuffer().feedAll(frames).single()
        assertEquals(4095, out.size)
        assertArrayEquals(payload, out)
    }

    // ── Buffer: discontinuity ────────────────────────────────────────────────

    /** Runs a CF order fault: nothing delivered through the whole stream, and the next reply is whole. */
    private fun assertDropped(name: String, payload: ByteArray, order: (List<ByteArray>) -> List<ByteArray>) {
        val frames = segment(payload)
        val buf = IsoTpBuffer()
        val delivered = buf.feedAll(order(frames))
        assertTrue("$name delivered ${delivered.map { it.size }}", delivered.isEmpty())
        val next = pattern(30, salt = 3)
        assertArrayEquals("$name: next reply", next, buf.feedAll(segment(next)).single())
    }

    @Test fun `a skipped, duplicate or swapped CF in the middle drops the reply - no partial bytes - next reply whole`() {
        val p = pattern(60)
        assertDropped("skipped", p) { f -> f.filterIndexed { i, _ -> i != 3 } }
        assertDropped("duplicate", p) { f -> f.take(4) + f[3] + f.drop(4) }
        assertDropped("swapped", p) { f -> f.take(3) + f[4] + f[3] + f.drop(5) }
    }

    @Test fun `a skipped, duplicate or swapped CF at the F-0 wrap drops the reply - no partial bytes - next reply whole`() {
        val p = pattern(200)                                     // frames[15] = seq F, frames[16] = seq 0, frames[17] = seq 1
        assertDropped("seq 0 skipped (F then 1)", p) { f -> f.filterIndexed { i, _ -> i != 16 } }
        assertDropped("seq F repeated", p) { f -> f.take(16) + f[15] + f.drop(16) }
        assertDropped("seq 0 repeated", p) { f -> f.take(17) + f[16] + f.drop(17) }
        assertDropped("seq 0 before F", p) { f -> f.take(15) + f[16] + f[15] + f.drop(17) }
    }

    @Test fun `after a discontinuity later CFs are ignored, even a stream restarting at seq 1 - no stitch after the reset`() {
        val payload = pattern(30)
        val frames = segment(payload)
        val buf = IsoTpBuffer()
        assertNull(buf.feed(frames[0]).first)
        assertNull(buf.feed(frames[1]).first)
        assertNull("seq 3 where 2 was due", buf.feed(frames[3]).first)
        // seqNext is back at 1 with no payload: this is the frame a wrong acceptance would take
        for (seq in listOf(1, 2, 3, 4, 0)) {
            val cf = ByteArray(8) { 0x55 }; cf[0] = (0x20 or seq).toByte()
            val r = buf.feed(cf)
            assertNull("seq $seq after the reset", r.first)
            assertFalse(r.second)
        }
        assertWholeReplyStillWorks(buf, salt = 9)
    }

    // ── Buffer: frames that don't belong ─────────────────────────────────────

    @Test fun `a CF with no FF delivers nothing, for every sequence number`() {
        val buf = IsoTpBuffer()
        for (seq in 0..15) {
            val cf = ByteArray(8) { 0x44 }; cf[0] = (0x20 or seq).toByte()
            val (p, isFF, isSF) = buf.feed(cf)
            assertNull(p); assertFalse(isFF); assertFalse(isSF)
        }
        assertWholeReplyStillWorks(buf, salt = 1)
    }

    @Test fun `a new FF during a partial reply replaces it - no old byte survives, shorter or longer`() {
        for (second in listOf(pattern(10, salt = 40), pattern(200, salt = 40))) {
            val first = segment(pattern(60))
            val buf = IsoTpBuffer()
            assertNull(buf.feed(first[0]).first)
            assertNull(buf.feed(first[1]).first)
            assertNull(buf.feed(first[2]).first)
            assertArrayEquals(second, buf.feedAll(segment(second)).single())
        }
    }

    @Test fun `a late CF of the old reply after a new FF drops the new reply - nothing stitched`() {
        val old = segment(pattern(60))
        val new = segment(pattern(60, salt = 40))
        val buf = IsoTpBuffer()
        buf.feed(old[0]); buf.feed(old[1])
        buf.feed(new[0])
        assertNull("old seq 2 where 1 is due", buf.feed(old[2]).first)
        assertTrue("the rest of the new reply can't complete it", buf.feedAll(new.drop(1)).isEmpty())
    }

    @Test fun `an SF during a partial reply is delivered and ends the partial`() {
        val frames = segment(pattern(30))
        val buf = IsoTpBuffer()
        buf.feed(frames[0]); buf.feed(frames[1])
        val (sf, _, isSF) = buf.feed(bytes(0x03, 0x62, 0x11, 0x22, 0x55, 0x55, 0x55, 0x55))
        assertTrue(isSF)
        assertEquals(listOf(0x62, 0x11, 0x22), ints(sf!!))
        assertTrue("the old stream's CFs are ignored", buf.feedAll(frames.drop(2)).isEmpty())
    }

    @Test fun `flow-control and reserved PCI types don't disturb a partial reply`() {
        val payload = pattern(30)
        val frames = segment(payload)
        val buf = IsoTpBuffer()
        buf.feed(frames[0]); buf.feed(frames[1])
        for (pci in listOf(0x30, 0x40, 0xF0)) assertNull(buf.feed(bytes(pci, 0, 0, 0, 0, 0, 0, 0)).first)
        assertArrayEquals(payload, buf.feedAll(frames.drop(2)).single())
    }

    // ── Buffer: First Frame length field ─────────────────────────────────────

    @Test fun `FF lengths 0 to 7 are not a first frame - no flow control asked, a later CF delivers nothing`() {
        for (len in 0..7) {
            val buf = IsoTpBuffer()
            val (p, isFF, _) = buf.feed(ff(len, pattern(6)))
            assertNull("len $len", p)
            assertFalse("len $len must not ask for flow control", isFF)
            val cf = ByteArray(8) { 0x44 }; cf[0] = 0x21
            assertNull("len $len: a CF must not complete it", buf.feed(cf).first)
        }
    }

    @Test fun `an FF with an invalid length also ends a partial reply - its CFs can't join the old one`() {
        val frames = segment(pattern(30))
        val buf = IsoTpBuffer()
        buf.feed(frames[0])
        buf.feed(ff(5, pattern(6, salt = 40)))
        assertTrue(buf.feedAll(frames.drop(1)).isEmpty())
    }

    @Test fun `control - FF length 8, the smallest valid, delivers exactly 8 bytes`() {
        val p = pattern(8)
        val frames = segment(p)
        assertEquals(2, frames.size)
        assertArrayEquals(p, IsoTpBuffer().feedAll(frames).single())
    }

    // ── Buffer: truncated frames ─────────────────────────────────────────────

    @Test fun `a truncated FF is refused - later CFs can't shift into a wrong-length payload`() {
        val payload = pattern(16)
        val frames = segment(payload)
        for (size in 2..7) {
            val buf = IsoTpBuffer()
            val (p, isFF, _) = buf.feed(frames[0].copyOf(size))
            assertNull(p)
            assertFalse("DLC $size", isFF)
            // A later CF carries padding that would make up the missing bytes
            assertTrue("DLC $size", buf.feedAll(frames.drop(1) + segment(pattern(40, salt = 40)).drop(1)).isEmpty())
        }
    }

    @Test fun `a CF shorter than the remainder drops the reply - the padding of the last CF can't make up the bytes`() {
        val payload = pattern(25)                                // FF 6, CF1 7, CF2 7, CF3 5 + 2 padding
        val frames = segment(payload)
        val faulty = listOf(frames[0], frames[1].copyOf(7), frames[2], frames[3])   // CF1 lost its last byte
        val buf = IsoTpBuffer()
        val delivered = buf.feedAll(faulty)
        assertTrue("delivered ${delivered.map { ints(it) }}", delivered.isEmpty())
        val next = pattern(30, salt = 3)
        assertArrayEquals(next, buf.feedAll(segment(next)).single())
    }

    @Test fun `control - a last CF with no padding, carrying exactly the remainder, delivers`() {
        val payload = pattern(16)                                // FF 6, CF1 7, CF2 3
        val frames = segment(payload)
        val buf = IsoTpBuffer()
        buf.feed(frames[0]); buf.feed(frames[1])
        assertArrayEquals(payload, buf.feed(frames[2].copyOf(4)).first)
    }

    @Test fun `a truncated SF is refused - its PCI claims more bytes than the frame holds`() {
        val buf = IsoTpBuffer()
        val (p, isFF, isSF) = buf.feed(bytes(0x07, 0x59, 0x02, 0xFF))
        assertNull("three bytes of a claimed seven", p)
        assertFalse(isFF)
        assertTrue("still reported as a single frame, so a caller can count it", isSF)
        for (pci in 0x08..0x0F) assertNull("PCI $pci can't fit CAN", buf.feed(ByteArray(8) { 0x11 }.also { it[0] = pci.toByte() }).first)
    }

    @Test fun `control - an SF without padding delivers exactly its claim`() {
        assertEquals(listOf(0x59, 0x02, 0xFF), ints(IsoTpBuffer().feed(bytes(0x03, 0x59, 0x02, 0xFF)).first!!))
    }

    @Test fun `empty and one-byte frames change nothing - no delivery, the partial stays, its sequence rule holds`() {
        val payload = pattern(30)
        val frames = segment(payload)
        val buf = IsoTpBuffer()
        buf.feed(frames[0]); buf.feed(frames[1])
        assertNull(buf.feed(bytes()).first)
        assertNull(buf.feed(bytes(0x22)).first)                  // a CF truncated to its PCI byte: not taken as seq 2
        assertArrayEquals(payload, buf.feedAll(frames.drop(2)).single())
    }

    // ── Buffer: padding, overrun, stitching ──────────────────────────────────

    @Test fun `padding after the final CF is not delivered, and a CF after completion is ignored`() {
        val payload = pattern(8)
        val frames = segment(payload)
        val buf = IsoTpBuffer()
        buf.feed(frames[0])
        val out = buf.feed(frames[1]).first!!
        assertEquals(8, out.size)
        assertFalse("0x${"%02X".format(PAD)} padding present", ints(out).contains(PAD.toInt() and 0xFF))
        val extra = ByteArray(8) { 0x44 }; extra[0] = 0x22
        assertNull(buf.feed(extra).first)
        assertWholeReplyStillWorks(buf, salt = 2)
    }

    /**
     * Known limit, not a contract to keep: the buffer has no clock (ISO 15765-2 N_Cr), so a partial stays open until the
     * next FF / SF. If the rest of reply A is lost AND reply B's FF is lost, B's first CF is accepted onto A's head. It takes
     * two lost lines on one module and A's next due sequence to match; the discontinuity tests above show one lost line can't.
     */
    @Test fun `known limit - two lost lines can still join two replies because a partial has no timer`() {
        val a = segment(pattern(13))
        val b = segment(pattern(13, salt = 40))
        val buf = IsoTpBuffer()
        buf.feed(a[0])                                           // A's CF lost, B's FF lost
        val out = buf.feed(b[1]).first!!
        assertEquals(ints(pattern(6)) + ints(pattern(13, salt = 40)).drop(6), ints(out))
    }

    @Test fun `seeded fuzz against an independent model - no throw, no overrun, every delivery byte exact`() {
        val rnd = java.util.Random(0x15765L)
        val buf = IsoTpBuffer()
        var open: MutableList<Int>? = null
        var dl = 0
        var next = 1
        var delivered = 0
        repeat(200_000) { i ->
            val size = if (rnd.nextInt(20) == 0) rnd.nextInt(8) else 8
            val f = ByteArray(size).also { rnd.nextBytes(it) }
            if (size > 0) {
                when (rnd.nextInt(10)) {
                    0 -> f[0] = (0x00 or rnd.nextInt(16)).toByte()
                    1 -> {
                        f[0] = 0x10
                        if (size > 1) f[1] = (if (rnd.nextInt(8) == 0) rnd.nextInt(8) else 8 + rnd.nextInt(40)).toByte()
                    }
                    2 -> f[0] = (0x20 or rnd.nextInt(16)).toByte()
                    else -> f[0] = (0x20 or next).toByte()
                }
            }
            val (out, isFF, _) = buf.feed(f)
            var want: List<Int>? = null
            var wantFF = false
            if (size >= 2) {
                val d = ints(f)
                when (d[0] shr 4) {
                    0 -> { open = null; val n = d[0] and 0x0F; if (n <= size - 1) want = d.subList(1, 1 + n) }
                    1 -> {
                        open = null
                        val len = ((d[0] and 0x0F) shl 8) or d[1]
                        if (size == 8 && len >= 8) { open = d.subList(2, 8).toMutableList(); dl = len; next = 1; wantFF = true }
                    }
                    2 -> open?.let { o ->
                        val need = minOf(7, dl - o.size)
                        if ((d[0] and 0x0F) != next || size - 1 < need) open = null
                        else {
                            o += d.subList(1, 1 + need)
                            next = (next + 1) and 0x0F
                            if (o.size >= dl) { want = o.toList(); open = null }
                        }
                    }
                }
            }
            assertEquals("frame $i FF flag", wantFF, isFF)
            assertEquals("frame $i", want, out?.let { ints(it) })
            if (out != null && out.size >= 8) { delivered++; assertTrue(out.size <= 4095) }
        }
        assertTrue("not vacuous: $delivered multi-frame deliveries", delivered > 500)
    }

    // ── SlcanConnection harness ──────────────────────────────────────────────

    /** Adapter double: the handshake line, then what a test injects. [onWrite] scripts a module's reaction to a request. */
    private class Bus(private val scope: CoroutineScope, private val onWrite: (Bus, String) -> Unit = { _, _ -> }) : SlcanTransport {
        private val rx = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        val writes = mutableListOf<String>()
        fun rx(vararg l: String) { l.forEach { rx.trySend(it) } }
        /** One line every [gapMs] ms, the first after [gapMs]. */
        fun paced(l: List<String>, gapMs: Long = 2L) { scope.launch { for (x in l) { delay(gapMs); rx.trySend(x) } } }
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = rx.receiveCatching().getOrNull()
        override suspend fun writeLine(frame: String) { writes += frame; onWrite(this, frame) }
        override fun close() { rx.close() }
        override val label = "scripted"
        override val stockFirmwareLabel = "scripted"
    }

    private class Link(val conn: SlcanConnection, val bus: Bus, var state: VehicleState)

    private fun TestScope.link(bus: Bus, state: VehicleState = VehicleState(), rpm: Double = 0.0): Link {
        val out = Link(SlcanConnection(
            transportFactory = { bus }, maxRetries = 1, rpmProvider = { rpm },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        ), bus, state)
        backgroundScope.launch { out.conn.connectHybrid({ out.state = it }, { out.state }).collect {} }
        advanceTimeBy(100L)                                      // handshake line consumed; BCM polling starts at 5 s
        return out
    }

    private fun Bus.flowControls(requestId: Int): Int = writes.count { it.startsWith("t%03X830".format(requestId)) }

    // ── BCM path (bcmIsoTp in the frame loop) ────────────────────────────────

    private val sensor = 0x11223344L
    private fun tpmsState() = VehicleState(tpmsSensorIdLF = sensor)

    /** DID 0x280B: 62 28 0B | sensor ID | pressure hi lo | temp | status | checksum, optionally stretched by [tail] bytes. */
    private fun tpmsPayload(pressure: Int, tempRaw: Int, tail: Int = 0): ByteArray = bytes(
        0x62, 0x28, 0x0B, 0x11, 0x22, 0x33, 0x44, pressure shr 8, pressure and 0xFF, tempRaw, 0x06, 0xA5) + pattern(tail)

    private fun TestScope.bcm(l: Link, vararg line: String) { l.bus.rx(*line); runCurrent() }

    @Test fun `BCM path - control - a 0x280B reply updates the tyre exactly, one flow control`() = runTest {
        val l = link(Bus(backgroundScope), tpmsState())
        val f = lines(0x72E, segment(tpmsPayload(pressure = 660, tempRaw = 65)))
        bcm(l, f[0])
        assertEquals(listOf(ObdConstants.BCM_FLOW_CONTROL), l.bus.writes.filter { it == ObdConstants.BCM_FLOW_CONTROL })
        assertEquals("nothing before the last frame", -99.0, l.state.tireTempLF, 0.0)
        bcm(l, f[1])
        assertEquals(25.0, l.state.tireTempLF, 0.0)
        assertEquals(33.0, l.state.tirePressLF, 0.0)
    }

    @Test fun `BCM path - a reply across the wrap updates only at its last frame`() = runTest {
        val l = link(Bus(backgroundScope), tpmsState())
        val f = lines(0x72E, segment(tpmsPayload(660, 65, tail = 190)))
        assertTrue(f.size > 17)
        bcm(l, *f.dropLast(1).toTypedArray())
        assertEquals(-99.0, l.state.tireTempLF, 0.0)
        bcm(l, f.last())
        assertEquals(25.0, l.state.tireTempLF, 0.0)
        assertEquals(1, l.bus.flowControls(0x726))
    }

    @Test fun `BCM path - a discontinuity mid reply or at the wrap updates nothing, and the module's next reply decodes`() = runTest {
        val l = link(Bus(backgroundScope), tpmsState())
        val short = lines(0x72E, segment(tpmsPayload(660, 65, tail = 14)))
        bcm(l, short[0], short[1], short[3])                     // CF 2 lost: every value bytes arrived in CF 1, still dropped
        bcm(l, short[2], short[3])                               // late frames of the same reply
        assertEquals(-99.0, l.state.tireTempLF, 0.0)
        val long = lines(0x72E, segment(tpmsPayload(660, 65, tail = 190)))
        bcm(l, *long.filterIndexed { i, _ -> i != 16 }.toTypedArray())   // sequence 0 lost
        assertEquals(-99.0, l.state.tireTempLF, 0.0)
        assertEquals("the partial reply's bytes never reached a decoder", -1.0, l.state.tirePressLF, 0.0)
        bcm(l, *lines(0x72E, segment(tpmsPayload(700, 70))).toTypedArray())
        assertEquals(30.0, l.state.tireTempLF, 0.0)
        assertEquals(35.0, l.state.tirePressLF, 0.0)
        assertEquals(3, l.bus.flowControls(0x726))
    }

    /** Pins what the export says today, for the #253 report: a dropped reply reaches no counter (see the raw SLCAN log). */
    @Test fun `BCM path - known gap - a dropped reply reaches no link-integrity counter`() = runTest {
        val l = link(Bus(backgroundScope), tpmsState())
        val f = lines(0x72E, segment(tpmsPayload(660, 65, tail = 14)))
        bcm(l, f[0], f[2])
        val li = DiagnosticLogger.linkIntegrity
        assertEquals(0, li.refusedReplies)
        assertEquals(0, li.refusedFrameLines)
        assertTrue(DiagnosticLogger.sessionEvents.none { it.type == "FRAMING" || it.message.contains("ISO", ignoreCase = true) })
    }

    @Test fun `BCM path - a CF with no FF does nothing and asks for no flow control`() = runTest {
        val l = link(Bus(backgroundScope), tpmsState())
        bcm(l, line(0x72E, segment(tpmsPayload(660, 65))[1]))
        assertEquals(-99.0, l.state.tireTempLF, 0.0)
        assertEquals(0, l.bus.flowControls(0x726))
    }

    @Test fun `BCM path - a new FF during a partial reply wins - a flow control for each, value from the new reply`() = runTest {
        val l = link(Bus(backgroundScope), tpmsState())
        val a = lines(0x72E, segment(tpmsPayload(660, 65, tail = 14)))
        val b = lines(0x72E, segment(tpmsPayload(700, 70)))
        bcm(l, a[0], b[0], b[1])
        assertEquals(30.0, l.state.tireTempLF, 0.0)
        assertEquals(2, l.bus.flowControls(0x726))
    }

    @Test fun `BCM path - an SF during a partial reply decodes, and the old stream's CFs are ignored`() = runTest {
        val l = link(Bus(backgroundScope), tpmsState())
        val a = lines(0x72E, segment(tpmsPayload(660, 65)))
        // 0x2813 = LF pressure: ((A × 256 + B) / 3 + 22 / 3) × 0.145
        bcm(l, a[0], line(0x72E, bytes(0x05, 0x62, 0x28, 0x13, 0x01, 0x00, 0x00, 0x00)))
        val psi = ((256.0) / 3.0 + 22.0 / 3.0) * 0.145
        assertEquals(psi, l.state.tirePressLF, 1e-9)
        bcm(l, a[1])
        assertEquals("the partial is gone", psi, l.state.tirePressLF, 1e-9)
        assertEquals(-99.0, l.state.tireTempLF, 0.0)
    }

    @Test fun `BCM path - FF length 0 or 5 asks for no flow control, and a stray CF decodes nothing`() = runTest {
        val l = link(Bus(backgroundScope), tpmsState())
        val p = tpmsPayload(660, 65)
        for (len in listOf(0, 5)) {
            bcm(l, line(0x72E, ff(len, p)), line(0x72E, bytes(0x21, 0x44, 0x65, 0x06, 0, 0, 0, 0)))
        }
        assertEquals(0, l.bus.flowControls(0x726))
        assertEquals(-99.0, l.state.tireTempLF, 0.0)
    }

    @Test fun `BCM path - a truncated SF is still counted by the parser as refused, never decoded`() = runTest {
        val l = link(Bus(backgroundScope), tpmsState())
        bcm(l, line(0x72E, bytes(0x07, 0x62, 0x28, 0x13, 0x01, 0x00)))
        assertEquals(-1.0, l.state.tirePressLF, 0.0)
        assertEquals(1, DiagnosticLogger.linkIntegrity.refusedReplies)
    }

    @Test fun `BCM path - other IDs interleaved between its frames don't disturb the reply, and decode themselves`() = runTest {
        val l = link(Bus(backgroundScope), tpmsState())
        val f = lines(0x72E, segment(tpmsPayload(660, 65, tail = 14)))
        bcm(l, f[0],
            line(0x70B, bytes(0x04, 0x62, 0x1E, 0x8A, 100, 0, 0, 0)),   // RDU temp 60 °C
            f[1],
            line(0x090, bytes(0, 0, 0xC1, 0xFF, 0x61, 0x7D, 0, 0)),
            line(0x7E8, bytes(0x05, 0x62, 0x03, 0x3E, 0x40, 0x00, 0, 0)),
            f[2], f[3])
        assertEquals(25.0, l.state.tireTempLF, 0.0)
        assertEquals(60.0, l.state.rduTempC, 0.0)
        assertEquals(128.0, l.state.tipActualKpa, 0.0)
    }

    // ── DTC scan path (per-module IsoTpBuffer in performDtcScan) ─────────────

    private fun dtcRecord(i: Int) = bytes(0x01 + (i shr 8), i and 0xFF, 0x00, 0x09)
    private fun dtcReply(n: Int, sub: Int = 0x02): ByteArray =
        (0 until n).fold(bytes(0x59, sub, 0xFF)) { acc, i -> acc + dtcRecord(i) }
    private fun codes(n: Int) = (0 until n).map { "P%04X".format(0x0100 + it) }

    private val pcmSpec = DtcScanner.MODULES.first { it.name == "PCM" }
    private val bcmSpec = DtcScanner.MODULES.first { it.name == "BCM" }

    private fun isScanRequest(frame: String) = frame.length >= 13 && frame.substring(5, 13) == "031902FF"

    /** Module double: every scan request answers with the next script for that request ID, paced. */
    private fun scanBus(scope: CoroutineScope, scripts: Map<Int, List<List<String>>>, gapMs: Long = 2L): Bus {
        val used = mutableMapOf<Int, Int>()
        return Bus(scope) { bus, frame ->
            if (isScanRequest(frame)) {
                val req = frame.substring(1, 4).toInt(16)
                val n = used.getOrPut(req) { 0 }
                used[req] = n + 1
                scripts[req]?.getOrNull(n)?.let { bus.paced(it, gapMs) }
            }
        }
    }

    private suspend fun scan(l: Link, vararg modules: com.openrs.dash.data.DtcModuleSpec): DtcScanResult =
        DtcScanner.runScan(l.conn, modules.toList())

    @Test fun `DTC scan - a reply long enough to wrap reads every code in order`() = runTest {
        val f = lines(0x7E8, segment(dtcReply(30)))              // 123 bytes
        assertTrue(f.size > 17)
        val l = link(scanBus(backgroundScope, mapOf(0x7E0 to listOf(f))))
        val r = scan(l, pcmSpec)
        assertEquals(ModuleScanStatus.OK, r.moduleStatuses["PCM"])
        assertEquals(codes(30), r.codes.map { it.code })
        assertEquals(1, l.bus.flowControls(0x7E0))
    }

    @Test fun `DTC scan - the 12-bit maximum reply, 1023 records, reads every code`() = runTest {
        val f = lines(0x7E8, segment(dtcReply(1023)))            // 3 + 4092 = 4095 bytes
        val l = link(scanBus(backgroundScope, mapOf(0x7E0 to listOf(f))))
        val r = scan(l, pcmSpec)
        assertEquals(ModuleScanStatus.OK, r.moduleStatuses["PCM"])
        assertEquals(codes(1023), r.codes.map { it.code })
    }

    @Test fun `DTC scan - a discontinuity at the wrap ends TIMEOUT with no codes, and a retry on the connection reads the module`() = runTest {
        val f = lines(0x7E8, segment(dtcReply(30)))
        val faulty = f.filterIndexed { i, _ -> i != 16 }         // sequence 0 lost
        val l = link(scanBus(backgroundScope, mapOf(0x7E0 to listOf(faulty, f))))
        val first = scan(l, pcmSpec)
        assertEquals(ModuleScanStatus.TIMEOUT, first.moduleStatuses["PCM"])
        assertTrue(first.codes.isEmpty())
        val second = scan(l, pcmSpec)
        assertEquals(ModuleScanStatus.OK, second.moduleStatuses["PCM"])
        assertEquals(codes(30), second.codes.map { it.code })
    }

    @Test fun `DTC scan - a truncated single frame is never a clean scan`() = runTest {
        // 07 59 02 FF in a 4-byte frame: PCI claims seven bytes, three arrived. As `59 02 FF` it would read "no faults"
        val l = link(scanBus(backgroundScope, mapOf(0x7E0 to listOf(listOf(line(0x7E8, bytes(0x07, 0x59, 0x02, 0xFF)))))))
        val r = scan(l, pcmSpec)
        assertEquals(ModuleScanStatus.TIMEOUT, r.moduleStatuses["PCM"])
    }

    @Test fun `DTC scan - a truncated single frame doesn't block the module's real reply behind it`() = runTest {
        val l = link(scanBus(backgroundScope, mapOf(0x7E0 to listOf(listOf(
            line(0x7E8, bytes(0x07, 0x59, 0x02, 0xFF)),
            line(0x7E8, bytes(0x07, 0x59, 0x02, 0xFF, 0x01, 0x00, 0x00, 0x09)))))))
        val r = scan(l, pcmSpec)
        assertEquals(ModuleScanStatus.OK, r.moduleStatuses["PCM"])
        assertEquals(codes(1), r.codes.map { it.code })
    }

    @Test fun `DTC scan - an FF that claims only its own header bytes is never a clean scan and gets no flow control`() = runTest {
        // FF length 3 carrying 59 02 FF, then any CF: delivered, it would read "no faults"
        val l = link(scanBus(backgroundScope, mapOf(0x7E0 to listOf(listOf(
            line(0x7E8, ff(3, bytes(0x59, 0x02, 0xFF))),
            line(0x7E8, bytes(0x21, 0, 0, 0, 0, 0, 0, 0)))))))
        val r = scan(l, pcmSpec)
        assertEquals(ModuleScanStatus.TIMEOUT, r.moduleStatuses["PCM"])
        assertEquals(0, l.bus.flowControls(0x7E0))
    }

    @Test fun `DTC scan - a CF that lost a byte can't shift the records into false codes`() = runTest {
        val f = segment(dtcReply(4))                             // 19 bytes: FF 6, CF1 7, CF2 6 + 1 padding
        assertEquals(3, f.size)
        val faulty = listOf(line(0x7E8, f[0]), line(0x7E8, f[1].copyOf(7)), line(0x7E8, f[2]))
        val l = link(scanBus(backgroundScope, mapOf(0x7E0 to listOf(faulty))))
        val r = scan(l, pcmSpec)
        assertEquals(ModuleScanStatus.TIMEOUT, r.moduleStatuses["PCM"])
        assertTrue(r.codes.map { it.code }.toString(), r.codes.isEmpty())
    }

    @Test fun `DTC scan - a truncated FF can't shift the records into false codes`() = runTest {
        val f = segment(dtcReply(4))
        val faulty = listOf(line(0x7E8, f[0].copyOf(7)), line(0x7E8, f[1]), line(0x7E8, f[2]))
        val l = link(scanBus(backgroundScope, mapOf(0x7E0 to listOf(faulty))))
        val r = scan(l, pcmSpec)
        assertEquals(ModuleScanStatus.TIMEOUT, r.moduleStatuses["PCM"])
        assertTrue(r.codes.isEmpty())
        assertEquals(0, l.bus.flowControls(0x7E0))
    }

    @Test fun `DTC scan - two modules' multi-frame replies interleaved frame by frame stay separate`() = runTest {
        val pcm = lines(0x7E8, segment(dtcReply(30)))
        val bcm = lines(0x72E, segment((0 until 25).fold(bytes(0x59, 0x02, 0xFF)) { acc, i -> acc + bytes(0x02, i, 0x00, 0x09) }))
        val mixed = (0 until maxOf(pcm.size, bcm.size)).flatMap { listOfNotNull(pcm.getOrNull(it), bcm.getOrNull(it)) }
        val bus = Bus(backgroundScope) { b, frame ->
            if (isScanRequest(frame) && frame.startsWith("t726")) b.paced(mixed)
        }
        val l = link(bus)
        val r = scan(l, pcmSpec, bcmSpec)
        assertEquals(ModuleScanStatus.OK, r.moduleStatuses["PCM"])
        assertEquals(ModuleScanStatus.OK, r.moduleStatuses["BCM"])
        assertEquals(codes(30), r.codes.filter { it.module == "PCM" }.map { it.code })
        assertEquals((0 until 25).map { "P%04X".format(0x0200 + it) }, r.codes.filter { it.module == "BCM" }.map { it.code })
        assertEquals("each flow control to its own module", 1, l.bus.flowControls(0x7E0))
        assertEquals(1, l.bus.flowControls(0x726))
    }

    @Test fun `DTC scan - a new FF during a partial reply replaces it - old bytes gone, no second flow control today`() = runTest {
        val old = lines(0x7E8, segment(dtcReply(10)))
        val new = lines(0x7E8, segment(dtcReply(4)))
        val l = link(scanBus(backgroundScope, mapOf(0x7E0 to listOf(old.take(2) + new))))
        val r = scan(l, pcmSpec)
        assertEquals(ModuleScanStatus.OK, r.moduleStatuses["PCM"])
        assertEquals(codes(4), r.codes.map { it.code })
        assertEquals("fcSent is per scan: see the report (a restart that waits for flow control ends TIMEOUT)",
            1, l.bus.flowControls(0x7E0))
    }

    @Test fun `DTC scan - a restarted reply that waits for its flow control ends TIMEOUT, never partial`() = runTest {
        val old = lines(0x7E8, segment(dtcReply(10)))
        val new = lines(0x7E8, segment(dtcReply(4)))
        var flowControls = 0
        val bus = Bus(backgroundScope) { b, frame ->
            if (isScanRequest(frame)) b.rx(old[0])
            if (frame.startsWith("t7E0830")) {
                flowControls++
                if (flowControls == 1) b.paced(listOf(old[1], new[0]))
                else b.paced(new.drop(1))                        // a second flow control would release these
            }
        }
        val r = scan(link(bus), pcmSpec)
        assertEquals(ModuleScanStatus.TIMEOUT, r.moduleStatuses["PCM"])
        assertTrue(r.codes.isEmpty())
    }

    @Test fun `DTC scan - a poll reply between FF and CFs ends TIMEOUT - the CFs reach no parser and charge no NRC`() = runTest {
        // Record status 0x7F then 22 31 …: consecutive frame 1 reads 21 7F 22 31 — the shape of a PCM NRC if its PCI is ignored
        val payload = bytes(0x59, 0x02, 0xFF, 0x01, 0x23, 0x00, 0x7F, 0x22, 0x31, 0x00, 0x09)
        val f = lines(0x7E8, segment(payload))
        val poll = line(0x7E8, bytes(0x05, 0x62, 0x03, 0x3E, 0x40, 0x00, 0, 0))
        val l = link(scanBus(backgroundScope, mapOf(0x7E0 to listOf(listOf(f[0], poll, f[1])))))
        val r = scan(l, pcmSpec)
        assertEquals(ModuleScanStatus.TIMEOUT, r.moduleStatuses["PCM"])
        assertTrue(r.codes.isEmpty())
        assertEquals("the poll reply still decodes", 128.0, l.state.tipActualKpa, 0.0)
        assertEquals(0, DiagnosticLogger.nrcUnattributedTotal)
        assertEquals(0, DiagnosticLogger.linkIntegrity.nrcAttributed)
    }

    /**
     * `_dtcChannel` (128) is written with trySend. With the frame loop and scan receiver on one test thread a burst overflows
     * it deterministically (production has real threads). Lost frames must end the module, never stitch.
     */
    @Test fun `DTC scan - frames lost to the scan channel's capacity end TIMEOUT, never a stitched reply`() = runTest {
        val f = lines(0x7E8, segment(dtcReply(300)))
        assertTrue(f.size > 128)
        val bus = Bus(backgroundScope) { b, frame -> if (isScanRequest(frame)) b.rx(*f.toTypedArray()) }
        val r = scan(link(bus), pcmSpec)
        assertEquals(ModuleScanStatus.TIMEOUT, r.moduleStatuses["PCM"])
        assertTrue(r.codes.isEmpty())
    }

    // ── assembleIsotpResponse (DTC clear, freeze frames) ─────────────────────

    private fun isFreezeIdRequest(frame: String) = frame.length >= 13 && frame.substring(5, 13) == "031904FF"
    private fun isClearRequest(frame: String) = frame.length >= 15 && frame.substring(5, 15) == "0414FFFFFF"

    private fun freezeBus(scope: CoroutineScope, script: List<String>) =
        Bus(scope) { b, frame -> if (isFreezeIdRequest(frame)) b.paced(script) }

    private fun clearBus(scope: CoroutineScope, script: List<String>) =
        Bus(scope) { b, frame -> if (isClearRequest(frame)) b.paced(script) }

    /** 59 04 + [n] records of DTC high, mid, low + record number. */
    private fun snapshotReply(n: Int, salt: Int = 0): ByteArray =
        (0 until n).fold(bytes(0x59, 0x04)) { acc, i -> acc + bytes(0x01 + salt, i and 0xFF, 0x00, 0x01) }

    @Test fun `freeze id - a reply across the wrap with padding and other IDs interleaved is byte exact`() = runTest {
        val payload = snapshotReply(30)                          // 122 bytes
        val f = lines(0x7E8, segment(payload))
        assertTrue(f.size > 17)
        val mixed = f.flatMap { listOf(it, line(0x72E, bytes(0x59, 0x02, 0xFF))) }
        val l = link(freezeBus(backgroundScope, mixed))
        val out = l.conn.performFreezeFrameIdentification(pcmSpec)
        assertArrayEquals(payload, out)
        assertEquals(30, FreezeFrameParser.parseSnapshotIdentification(out!!).size)
        assertEquals(1, l.bus.flowControls(0x7E0))
    }

    @Test fun `freeze id - a discontinuity returns nothing - no partial records`() = runTest {
        val f = lines(0x7E8, segment(snapshotReply(10)))
        val l = link(freezeBus(backgroundScope, f.filterIndexed { i, _ -> i != 3 }))
        val out = l.conn.performFreezeFrameIdentification(pcmSpec)
        assertNull(out?.let { "partial reply of ${it.size} bytes: ${ints(it)}" }, out)
    }

    @Test fun `freeze id - a reply cut short by the deadline returns nothing - no partial records`() = runTest {
        val f = lines(0x7E8, segment(snapshotReply(10)))
        val l = link(freezeBus(backgroundScope, f.take(3)))
        val out = l.conn.performFreezeFrameIdentification(pcmSpec)
        assertNull(out?.let { "partial reply of ${it.size} bytes" }, out)
    }

    @Test fun `freeze id - a new FF during a partial reply replaces it byte exact - no stitch, no overrun`() = runTest {
        val old = lines(0x7E8, segment(snapshotReply(10)))
        val newPayload = snapshotReply(6, salt = 1)              // 26 bytes
        val new = lines(0x7E8, segment(newPayload))
        val l = link(freezeBus(backgroundScope, old.take(2) + new))
        assertArrayEquals(newPayload, l.conn.performFreezeFrameIdentification(pcmSpec))
        assertEquals("a flow control for each first frame", 2, l.bus.flowControls(0x7E0))
    }

    @Test fun `freeze id - a new FF whose first CF is lost returns nothing - never more bytes than it declared`() = runTest {
        val old = lines(0x7E8, segment(snapshotReply(10)))
        val new = lines(0x7E8, segment(snapshotReply(6, salt = 1)))
        // old FF + CF1 leave sequence 2 due in the old code; the new reply's CF2 fits that if nothing was reset
        val l = link(freezeBus(backgroundScope, old.take(2) + new[0] + new.drop(2)))
        val out = l.conn.performFreezeFrameIdentification(pcmSpec)
        assertNull(out?.let { "${it.size} bytes against a declared 26" }, out)
    }

    @Test fun `freeze id - an SF during a partial reply is the answer`() = runTest {
        val old = lines(0x7E8, segment(snapshotReply(10)))
        val sf = line(0x7E8, bytes(0x06, 0x59, 0x04, 0x01, 0x00, 0x00, 0x01, 0x00))
        val l = link(freezeBus(backgroundScope, old.take(2) + sf))
        assertEquals(listOf(0x59, 0x04, 0x01, 0x00, 0x00, 0x01), ints(l.conn.performFreezeFrameIdentification(pcmSpec)!!))
    }

    @Test fun `freeze id - FF lengths 0 and 5 and a truncated FF get no flow control and return nothing`() = runTest {
        for (first in listOf(ff(0, snapshotReply(1)), ff(5, snapshotReply(1)), ff(26, snapshotReply(6)).copyOf(7))) {
            val l = link(freezeBus(backgroundScope, listOf(line(0x7E8, first), line(0x7E8, bytes(0x21, 1, 2, 3, 4, 5, 6, 7)))))
            val out = l.conn.performFreezeFrameIdentification(pcmSpec)
            assertNull(out?.let { "delivered ${ints(it)} for ${ints(first)}" }, out)
            assertEquals(0, l.bus.flowControls(0x7E0))
        }
    }

    @Test fun `freeze id - a CF shorter than the remainder returns nothing`() = runTest {
        val f = segment(snapshotReply(6))                        // 26 bytes: FF 6, CF 7, CF 7, CF 6 + 1 padding
        val faulty = listOf(line(0x7E8, f[0]), line(0x7E8, f[1].copyOf(7)), line(0x7E8, f[2]), line(0x7E8, f[3]))
        val l = link(freezeBus(backgroundScope, faulty))
        val out = l.conn.performFreezeFrameIdentification(pcmSpec)
        assertNull(out?.let { "shifted reply ${ints(it)}" }, out)
    }

    @Test fun `DTC clear - a positive SF is true, and a stray CF in front of it can't end the wait - it never reaches the operation`() = runTest {
        val l = link(clearBus(backgroundScope, listOf(line(0x7E8, bytes(0x21, 1, 2, 3, 4, 5, 6, 7)),
            line(0x7E8, bytes(0x01, 0x54, 0, 0, 0, 0, 0, 0)))))
        assertEquals(mapOf("PCM" to true), l.conn.performDtcClear(listOf(pcmSpec)))
    }

    @Test fun `DTC clear - a discontinuity in a multi-frame 54 reply is not an acknowledged clear`() = runTest {
        val f = lines(0x7E8, segment(bytes(0x54) + pattern(25)))
        val l = link(clearBus(backgroundScope, f.filterIndexed { i, _ -> i != 2 }))
        assertEquals(mapOf("PCM" to false), l.conn.performDtcClear(listOf(pcmSpec)))
    }

    @Test fun `DTC clear - a multi-frame 54 reply cut short by the deadline is not an acknowledged clear`() = runTest {
        val f = lines(0x7E8, segment(bytes(0x54) + pattern(25)))
        val l = link(clearBus(backgroundScope, f.take(2)))
        assertEquals(mapOf("PCM" to false), l.conn.performDtcClear(listOf(pcmSpec)))
    }

    // ── PCM / AWD: no reassembly, no flow control ────────────────────────────

    private fun positive(did: Int) = "t7E880562%04X00000000".format(did)
    private fun nrc(code: Int) = "t7E88037F22%02X0000000000".format(code).take(21)

    /** PCM behind 0x7E0: poll DIDs answer after 40 ms per [poll]. */
    private fun pcmEcu(scope: CoroutineScope, poll: (Int) -> String?) = Bus(scope) { b, frame ->
        if (frame.startsWith("t7E0") && frame.length >= 13 && frame.substring(4, 9) == "80322") {
            poll(frame.substring(9, 13).toInt(16))?.let { b.paced(listOf(it), 40L) }
        }
    }

    @Test fun `PCM - the first frame of a polled DID decodes nothing and gets no flow control, and its next single frame decodes`() = runTest {
        val tipFf = line(0x7E8, bytes(0x10, 0x0A, 0x62, 0x03, 0x3E, 0x40, 0x00, 0x00))
        var multi = true
        val l = link(pcmEcu(backgroundScope) { did -> if (did == 0x033E && multi) tipFf else positive(did) }, rpm = 1_500.0)
        advanceTimeBy(25_000L)
        assertEquals(0.0, l.state.tipActualKpa, 0.0)
        assertEquals(0, l.bus.flowControls(0x7E0))
        multi = false
        l.bus.rx(line(0x7E8, bytes(0x05, 0x62, 0x03, 0x3E, 0x40, 0x00, 0, 0)))
        runCurrent()
        assertEquals(128.0, l.state.tipActualKpa, 0.0)
    }

    @Test fun `PCM - a first-frame answer resolves its request, so a later rejection is still attributable`() = runTest {
        val tipFf = line(0x7E8, bytes(0x10, 0x0A, 0x62, 0x03, 0x3E, 0x40, 0x00, 0x00))
        var rejecting = false
        link(pcmEcu(backgroundScope) { did ->
            when {
                did == 0x033E -> tipFf
                rejecting && did == 0x03ED -> nrc(0x31)
                else -> positive(did)
            }
        }, rpm = 1_500.0)
        advanceTimeBy(25_000L)
        rejecting = true
        advanceTimeBy(30_000L)
        val unresolved = DiagnosticLogger.sessionEvents.filter { it.type == "NRC_UNATTRIBUTED" && it.message.contains("still unanswered") }
        assertTrue(unresolved.map { it.message }.toString(), unresolved.isEmpty())
        assertTrue(DiagnosticLogger.nrcSuppressed.keys.toString(), 0x03ED in DiagnosticLogger.nrcSuppressed.keys)
    }

    @Test fun `PCM - a consecutive frame shaped like an NRC is not an NRC`() = runTest {
        val l = link(pcmEcu(backgroundScope) { positive(it) })
        l.bus.rx(line(0x7E8, bytes(0x21, 0x7F, 0x22, 0x31, 0, 0, 0, 0)))
        l.bus.rx(line(0x7E8, bytes(0x10, 0x7F, 0x22, 0x31, 0, 0, 0, 0)))
        runCurrent()
        assertEquals(0, DiagnosticLogger.nrcUnattributedTotal)
        assertEquals(0, DiagnosticLogger.linkIntegrity.nrcAttributed)
    }

    @Test fun `PCM - a consecutive frame shaped like a positive reply doesn't recover a suppressed DID`() = runTest {
        var rejecting = false
        val l = link(pcmEcu(backgroundScope) { did -> if (rejecting && did == 0x03ED) nrc(0x31) else positive(did) }, rpm = 1_500.0)
        advanceTimeBy(25_000L)
        rejecting = true
        advanceTimeBy(30_000L)
        assertTrue(DiagnosticLogger.nrcSuppressed.keys.toString(), 0x03ED in DiagnosticLogger.nrcSuppressed.keys)
        l.bus.rx(line(0x7E8, bytes(0x21, 0x62, 0x03, 0xED, 0, 0, 0, 0)))
        runCurrent()
        assertTrue(DiagnosticLogger.sessionEvents.none { it.type == "NRC_RECOVER" })
    }

    @Test fun `AWD - a first frame decodes nothing and gets no flow control, then a single frame decodes`() = runTest {
        val l = link(Bus(backgroundScope))
        bcm(l, line(0x70B, bytes(0x10, 0x0A, 0x62, 0x1E, 0x8A, 100, 0, 0)))
        assertEquals(-99.0, l.state.rduTempC, 0.0)
        assertEquals(0, l.bus.flowControls(0x703))
        // A first frame whose length byte reads 0x62 reaches the parser's service check: refused for its PCI, not decoded
        bcm(l, line(0x70B, bytes(0x10, 0x62, 0x62, 0x1E, 0x8A, 100, 0, 0)))
        assertEquals(-99.0, l.state.rduTempC, 0.0)
        assertEquals(1, DiagnosticLogger.linkIntegrity.refusedReplies)
        bcm(l, line(0x70B, bytes(0x04, 0x62, 0x1E, 0x8A, 100, 0, 0, 0)))
        assertEquals(60.0, l.state.rduTempC, 0.0)
    }

    @Test fun `DID prober - a multi-frame positive shows its first-frame bytes only, no flow control, and BCM reassembly isn't touched`() = runTest {
        val probeDid = 0xABCD
        val bus = Bus(backgroundScope) { b, frame ->
            if (frame.startsWith("t726") && frame.length >= 13 && frame.substring(9, 13) == "ABCD") {
                b.paced(listOf(line(0x72E, bytes(0x10, 0x20, 0x62, 0xAB, 0xCD, 0x01, 0x02, 0x03)),
                    line(0x72E, bytes(0x21, 0x04, 0x05, 0x06, 0x07, 0x08, 0x09, 0x0A))))
            }
        }
        val l = link(bus, tpmsState())
        val shown = mutableListOf<DiagnosticLogger.ProbeResult>()
        runDidProbeScan("BCM", 0x726, 0x72E, listOf(probeDid), l.conn::sendRawQuery, { false }) { shown += it }
        assertEquals(listOf(DiagnosticLogger.ProbeResult(probeDid, "FOUND", "01 02 03")), shown)
        assertEquals(0, l.bus.flowControls(0x726))
        bcm(l, *lines(0x72E, segment(tpmsPayload(660, 65))).toTypedArray())
        assertEquals("a real reply afterwards is whole", 25.0, l.state.tireTempLF, 0.0)
    }

    private companion object {
        /** Padding a synthetic frame carries; pattern() bytes are chosen so a stitch shows regardless. */
        const val PAD: Byte = 0xCC.toByte()
    }
}
