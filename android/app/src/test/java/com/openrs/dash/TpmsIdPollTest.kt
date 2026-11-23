package com.openrs.dash

import com.openrs.dash.can.ObdConstants
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.can.TpmsIdReads
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #518: the TPMS sensor-ID reads (BCM 0x280F–0x2812) went out once per connection, so a lost reply left that tyre's
 * position unmapped and every 0x280B report from its sensor was dropped for the whole drive. The BCM poller now re-reads
 * an unmapped position on later cycles, at most [ObdConstants.TPMS_ID_MAX_SENDS] times per connection, and never a
 * mapped one. Real SlcanConnection over a scripted BCM on the virtual clock; sensor IDs are synthetic, and no assertion
 * message prints one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TpmsIdPollTest {

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private fun line(id: Int, f: ByteArray) = "t%03X%d".format(id, f.size) + f.joinToString("") { "%02X".format(it) }

    /** Synthetic sensor IDs, one per read, in BCM_TPMS_ID_QUERIES order (LF, RF, RR, LR). */
    private val ids = listOf(0x0A0B0C01L, 0x0A0B0C02L, 0x0A0B0C03L, 0x0A0B0C04L)
    private val reads = ObdConstants.BCM_TPMS_ID_QUERIES

    /** 07 62 <DID> <ID0..ID3>: the BCM's single-frame positive reply to sensor-ID read [i]. */
    private fun idReply(i: Int): String {
        val did = ObdConstants.extractDid(reads[i])!!
        val id = ids[i]
        return line(0x72E, bytes(0x07, 0x62, did shr 8, did and 0xFF,
            (id shr 24).toInt() and 0xFF, (id shr 16).toInt() and 0xFF, (id shr 8).toInt() and 0xFF, id.toInt() and 0xFF))
    }

    /** A 0x280B "last received sensor" report from sensor [i]: first frame + consecutive frame, live status (6). */
    private fun lastReport(i: Int, pressure: Int, tempRaw: Int): List<String> {
        val id = ids[i]
        val p = bytes(0x62, 0x28, 0x0B, (id shr 24).toInt() and 0xFF, (id shr 16).toInt() and 0xFF,
            (id shr 8).toInt() and 0xFF, id.toInt() and 0xFF, pressure shr 8, pressure and 0xFF, tempRaw, 0x06, 0xA5)
        return listOf(line(0x72E, bytes(0x10, p.size) + p.copyOfRange(0, 6)),
            line(0x72E, bytes(0x21) + p.copyOfRange(6, p.size) + ByteArray(1)))
    }

    /**
     * The adapter: the handshake line, then the BCM's reaction to each write. A sensor-ID read [i] is answered from its
     * [answerFrom]-th send on (1 = the first; Int.MAX_VALUE = never), so a reply lost on the bus is a read never answered.
     */
    private class Bus(private val react: (Bus, String) -> Unit) : SlcanTransport {
        private val rx = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        val writes = mutableListOf<String>()
        fun rx(l: List<String>) { l.forEach { rx.trySend(it) } }
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = rx.receiveCatching().getOrNull()
        override suspend fun writeLine(frame: String) { writes += frame; react(this, frame) }
        override fun close() { rx.close() }
        override val label = "scripted"
        override val stockFirmwareLabel = "scripted"
    }

    private fun bcm(answerFrom: List<Int>) = Bus { bus, frame ->
        val i = reads.indexOf(frame)
        if (i >= 0 && bus.writes.count { it == frame } >= answerFrom[i]) bus.rx(listOf(idReply(i)))
    }

    private class Link(val bus: Bus, var state: VehicleState)

    /** Connects with the production merge (ObdMerge, as CanDataService.mergeObdState) feeding getCurrentState. */
    private fun TestScope.link(bus: Bus): Link = link(listOf(bus))

    /** As [link], with one adapter per connection attempt: a closed one ends its connection and the next attempt opens the next. */
    private fun TestScope.link(buses: List<Bus>): Link {
        val out = Link(buses.first(), VehicleState())
        var attempt = 0
        val conn = SlcanConnection(
            transportFactory = { buses[attempt++] }, maxRetries = 1, reconnectDelayMs = RECONNECT_MS,
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { conn.connectHybrid({ out.state = ObdMerge.merge(out.state, it) }, { out.state }).collect {} }
        runCurrent()
        return out
    }

    private companion object { const val RECONNECT_MS = 2_000L }

    private fun Bus.sends(i: Int) = writes.count { it == reads[i] }
    private fun mapped(s: VehicleState) = listOf(s.tpmsSensorIdLF, s.tpmsSensorIdRF, s.tpmsSensorIdRR, s.tpmsSensorIdLR)
        .map { it >= 0 }

    /** One BCM cycle: the reads, BCM_QUERIES and AWD_QUERIES at the 300 ms gap, then the 30 s interval. */
    private val cycleMs = (reads.size + ObdConstants.BCM_QUERIES.size + ObdConstants.AWD_QUERIES.size) *
        ObdConstants.BCM_QUERY_GAP_MS + ObdConstants.BCM_POLL_INTERVAL_MS

    @Test fun `two lost sensor-ID replies are read again on a later cycle and their tyres then get temperatures`() = runTest {
        // RF (index 1) and LR (index 3) lose their first reply; LF and RR answer at once
        val l = link(bcm(answerFrom = listOf(1, 2, 1, 2)))
        advanceTimeBy(ObdConstants.BCM_INITIAL_DELAY_MS + 2_000L)
        assertEquals("after the first cycle's four reads", listOf(true, false, true, false), mapped(l.state))
        assertEquals("first cycle: every position read once", listOf(1, 1, 1, 1), reads.indices.map { l.bus.sends(it) })

        advanceTimeBy(cycleMs)
        assertEquals("the unmapped positions are read again; the mapped ones are not",
            listOf(1, 2, 1, 2), reads.indices.map { l.bus.sends(it) })
        assertEquals("every position mapped after the second cycle", listOf(true, true, true, true), mapped(l.state))

        // The BCM now reports the two sensors that were unmapped: both tyres get a temperature
        l.bus.rx(lastReport(1, pressure = 660, tempRaw = 65)); runCurrent()
        l.bus.rx(lastReport(3, pressure = 700, tempRaw = 70)); runCurrent()
        assertEquals(25.0, l.state.tireTempRF, 0.0)
        assertEquals(30.0, l.state.tireTempLR, 0.0)

        advanceTimeBy(5 * cycleMs)
        assertEquals("a mapped position is never read again", listOf(1, 2, 1, 2), reads.indices.map { l.bus.sends(it) })
    }

    @Test fun `a sensor-ID read the BCM never answers stops after TPMS_ID_MAX_SENDS sends on one connection`() = runTest {
        val never = Int.MAX_VALUE
        val l = link(bcm(answerFrom = listOf(1, never, 1, 1)))
        advanceTimeBy(ObdConstants.BCM_INITIAL_DELAY_MS + (ObdConstants.TPMS_ID_MAX_SENDS + 5) * cycleMs)
        assertEquals("RF read up to the bound, then no more; the others once",
            listOf(1, ObdConstants.TPMS_ID_MAX_SENDS, 1, 1), reads.indices.map { l.bus.sends(it) })
        assertTrue("the BCM's ordinary polling goes on",
            l.bus.writes.count { it == ObdConstants.BCM_QUERIES.first() } >= ObdConstants.TPMS_ID_MAX_SENDS + 5)
    }

    @Test fun `when every reply arrives each sensor-ID read is sent once, the bus sequence of before`() = runTest {
        val l = link(bcm(answerFrom = listOf(1, 1, 1, 1)))
        advanceTimeBy(ObdConstants.BCM_INITIAL_DELAY_MS + 10 * cycleMs)
        assertEquals(listOf(1, 1, 1, 1), reads.indices.map { l.bus.sends(it) })
        assertEquals("the four reads come first on the first cycle, in their order, before the BCM's own polls",
            reads + ObdConstants.BCM_QUERIES.first(),
            l.bus.writes.filter { it in reads || it == ObdConstants.BCM_QUERIES.first() }.take(5))
    }

    @Test fun `a reconnect starts a new budget - all four read again and an unanswered one up to the bound again`() = runTest {
        val never = Int.MAX_VALUE
        val first = bcm(answerFrom = listOf(1, never, 1, 1))
        val second = bcm(answerFrom = listOf(1, never, 1, 1))
        val l = link(listOf(first, second))
        advanceTimeBy(ObdConstants.BCM_INITIAL_DELAY_MS + (ObdConstants.TPMS_ID_MAX_SENDS + 2) * cycleMs)
        assertEquals("first connection: RF up to the bound", listOf(1, ObdConstants.TPMS_ID_MAX_SENDS, 1, 1),
            reads.indices.map { first.sends(it) })
        assertEquals("three positions mapped from the first connection", listOf(true, false, true, true), mapped(l.state))

        first.close()                                     // the link drops; the next attempt opens the second adapter
        advanceTimeBy(RECONNECT_MS + ObdConstants.BCM_INITIAL_DELAY_MS + (ObdConstants.TPMS_ID_MAX_SENDS + 2) * cycleMs)
        assertEquals("the new connection re-reads all four once, though three are still mapped in the state, " +
            "and the unanswered one up to the bound again", listOf(1, ObdConstants.TPMS_ID_MAX_SENDS, 1, 1),
            reads.indices.map { second.sends(it) })
    }

    @Test fun `a session reset while connected clears the IDs and the next cycle reads all four again`() = runTest {
        val l = link(bcm(answerFrom = listOf(1, 1, 1, 1)))
        advanceTimeBy(ObdConstants.BCM_INITIAL_DELAY_MS + 2 * cycleMs)
        assertEquals(listOf(1, 1, 1, 1), reads.indices.map { l.bus.sends(it) })

        l.state = VehicleState()                          // GARAGE's reset: CanDataService.resetSession() replaces the state
        advanceTimeBy(cycleMs)
        assertEquals("every position unmapped by the reset is read once more", listOf(2, 2, 2, 2),
            reads.indices.map { l.bus.sends(it) })
        assertEquals(listOf(true, true, true, true), mapped(l.state))
        advanceTimeBy(5 * cycleMs)
        assertEquals("then mapped again: no more reads", listOf(2, 2, 2, 2), reads.indices.map { l.bus.sends(it) })
    }

    // ── TpmsIdReads, the rule alone ──────────────────────────────────────────

    @Test fun `the first cycle reads every position even when the state already holds IDs`() {
        val held = VehicleState(tpmsSensorIdLF = ids[0], tpmsSensorIdRF = ids[1], tpmsSensorIdRR = ids[2], tpmsSensorIdLR = ids[3])
        val r = TpmsIdReads()
        assertEquals("a new connection re-reads every position", reads, r.due(held))
        assertEquals("then only unmapped ones: none", emptyList<String>(), r.due(held))
    }

    @Test fun `each read maps to its own position field`() {
        // A read whose position's ID is set is not due; one per position, in BCM_TPMS_ID_QUERIES order (LF, RF, RR, LR)
        val one = listOf(
            VehicleState(tpmsSensorIdLF = 0L), VehicleState(tpmsSensorIdRF = 0L),
            VehicleState(tpmsSensorIdRR = 0L), VehicleState(tpmsSensorIdLR = 0L))
        reads.indices.forEach { i ->
            val r = TpmsIdReads()
            r.due(VehicleState())
            assertEquals("position $i set", reads - reads[i], r.due(one[i]))
        }
        // The DIDs behind that order (the decoder's own mapping: 0x280F LF, 0x2810 RF, 0x2811 RR, 0x2812 LR)
        assertEquals(listOf(0x280F, 0x2810, 0x2811, 0x2812), reads.map { ObdConstants.extractDid(it) })
    }
}
