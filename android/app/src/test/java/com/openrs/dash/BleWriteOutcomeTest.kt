package com.openrs.dash

import com.openrs.dash.can.BleAdapterProfile
import com.openrs.dash.can.BleGattCore
import com.openrs.dash.can.BleSetupPolicy
import com.openrs.dash.can.GattLink
import com.openrs.dash.can.ObdConstants
import com.openrs.dash.can.PcmRequestLedger
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.can.WriteFailure
import com.openrs.dash.can.WriteOutcome
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.diagnostics.DtcScanner
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DidProbeEnd
import com.openrs.dash.diagnostics.runDidProbeScan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * rc.8 reliability pass, #248 / #249 — what a BLE write's outcome means for request ownership. Source evidence (no
 * hardware; dev/PLAN-area1.md): WiCAN Pro v4.48p declares FFF2 (write + write-no-response) with a 20-byte maximum; with
 * the MTU the app negotiates, a 22-byte SLCAN line goes as one ATT write, which ESP-IDF 5.2.3 delivers to the firmware and
 * then answers status 0x0D (13). The adapter's SLCAN parser streams across writes but drops a line whose continuation
 * comes > 100 ms after its previous byte, so the app does not chunk on purpose. Contract under test:
 *  - GATT 13 after a write = possibly delivered: the request stays outstanding (its reply resolves it), nothing retransmitted;
 *  - a definite pre-send failure (the stack refused the write) = not sent: withdrawn, the prober says nothing was sent;
 *  - any other failure after sending (another status, an unacknowledged write) = uncertain: kept — it lapses and later
 *    NRCs are charged to nobody (#216), never retransmitted;
 *  - MTU 23: the existing split into 20 + 2 ordered writes, one request.
 * Production SlcanConnection (pollers, ledger, NRC tracker, prober) over the same BleGattCore BleSlcanTransport uses,
 * fake GATT + scripted ECU behind it; JVM, virtual time. The fake answers every frame the adapter would forward.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleWriteOutcomeTest {
    private val pro = BleAdapterProfile.WICAN_PRO

    /**
     * Fake GATT + ECU. [status] picks the write callback per written chunk (null = no callback); [refuse] makes the stack
     * refuse the write outright. A PCM Mode 22 line is "forwarded" when the whole line has been written (whatever the
     * callback status, as the Pro's stack does) unless [delivered] says otherwise; the ECU then answers per [reply].
     */
    private inner class Fake(val core: BleGattCore, val scope: CoroutineScope, val mtu: Int, val now: () -> Long) : GattLink {
        var status: (String) -> Int? = { BleSetupPolicy.GATT_SUCCESS }
        var refuse: (String) -> Boolean = { false }
        var delivered: (String) -> Boolean = { true }
        var reply: (Int) -> String? = { did -> "t7E880562%04X00000000".format(did).take(21) }
        val chunks = mutableListOf<String>()
        /** Every completed line with the virtual time its last chunk was written. */
        val lines = mutableListOf<Pair<Long, String>>()
        /** How long after the line is written the ECU's reply arrives (a real Bluetooth link: often past the 150 ms gap). */
        var latencyMs = 40L
        private val partial = StringBuilder()
        var closed = false
        override fun discoverServices() = true
        override fun setNotify(service: UUID, characteristic: UUID, enable: Boolean) = true
        override fun writeCccd(service: UUID, characteristic: UUID, value: ByteArray): Boolean {
            scope.launch { delay(30); core.onDescriptorWrite(true, BleSetupPolicy.GATT_SUCCESS) }; return true
        }
        override fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean {
            val text = String(value, Charsets.ISO_8859_1)
            partial.append(text)
            val line = partial.toString()
            if (refuse(line)) { partial.clear(); return false }
            chunks += text
            if (line.endsWith("\r")) { partial.clear(); lines += now() to line; if (delivered(line)) forward(line) }
            status(line)?.let { s -> scope.launch { delay(10); core.onWrite(s) } }
            return true
        }
        private fun forward(line: String) {
            if (line.startsWith("t7E08") && line.substring(5, 13) == "031902FF") {     // DTC read: valid, zero codes
                scope.launch { delay(40); core.onNotify(pro.notify, "t7E88035902FF00000000\r".toByteArray()) }
                return
            }
            if (!line.startsWith("t7E08") || line.substring(5, 9) != "0322") return
            val did = line.substring(9, 13).toInt(16)
            val answer = reply(did) ?: return
            scope.launch { delay(latencyMs); core.onNotify(pro.notify, "$answer\r".toByteArray()) }
        }
        override fun requestMtu(mtu: Int): Boolean { scope.launch { delay(10); core.onMtuChanged(this@Fake.mtu, 0) }; return true }
        override fun bondState() = BleGattCore.BOND_BONDED
        override fun createBond() = true
        override fun disconnect() = Unit
        override fun close() { closed = true }
    }

    private fun lineOf(did: Int) = "t7E080322%04X00000000\r".format(did)
    private fun nrc(code: Int) = "t7E88037F22%02X0000000000".format(code).take(21)

    private class Link(val conn: SlcanConnection, val fakes: List<Fake>) { val first get() = fakes.first() }

    private fun TestScope.connected(mtu: Int = 247, rpm: Double = 1_500.0, recording: Boolean = false, setup: (Fake) -> Unit = {}): Link {
        val fakes = mutableListOf<Fake>()
        val factory = {
            object : SlcanTransport {
                val core = BleGattCore("MEATPI_PRO", writeTimeoutMs = 2_000L)
                val g = Fake(core, backgroundScope, mtu, { testScheduler.currentTime }).also { setup(it); fakes += it }
                override suspend fun open() {
                    backgroundScope.launch {
                        delay(50); core.onConnected(g); delay(50)
                        core.onServicesDiscovered(0, mapOf(pro.service to mapOf(
                            pro.write to (BleGattCore.PROPERTY_WRITE or BleGattCore.PROPERTY_WRITE_NO_RESPONSE),
                            pro.notify to BleGattCore.PROPERTY_NOTIFY)))
                    }
                    core.setup()
                    backgroundScope.launch {
                        while (isActive && !g.closed) { core.onNotify(pro.notify, "t09080000C1FF617D0000\r".toByteArray()); delay(20) }
                    }
                }
                override suspend fun readLine() = core.readLine()
                override suspend fun writeLine(frame: String) = core.writeLine(frame)
                override suspend fun write(frame: String) = core.write(frame)          // as BleSlcanTransport does
                override fun close() = core.close()
                override val label = "BLE fake"
                override val stockFirmwareLabel = "fake"
            }
        }
        val conn = SlcanConnection(
            transportFactory = factory, maxRetries = 3, rpmProvider = { rpm }, mapPoll = { recording },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        var waited = 0L
        while (conn.transport == null && waited < 10_000L) { advanceTimeBy(100L); waited += 100L }
        assertTrue("connected", conn.transport != null)
        return Link(conn, fakes)
    }

    private fun suppressed() = DiagnosticLogger.nrcSuppressed.keys
    private fun unattributed() = DiagnosticLogger.sessionEvents.filter { it.type == "NRC_UNATTRIBUTED" }.map { it.message }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    // ── PCM poller ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `GATT 13 on 22-byte polls - KR2 and KR3 rejections are attributed and suppressed (nov04 F2)`() = runTest {
        val link = connected {
            it.status = { line -> if (line.length > 20) 13 else BleSetupPolicy.GATT_SUCCESS }
            it.reply = { did -> if (did == 0x03ED || did == 0x03EE) nrc(0x31) else "t7E880562%04X00000000".format(did).take(21) }
        }
        advanceTimeBy(40_000L)
        assertTrue(link.first.chunks.any { it.startsWith("t7E08") })
        assertTrue(suppressed().toString(), 0x03ED in suppressed() && 0x03EE in suppressed())
        assertTrue(suppressed().toString(), suppressed().all { it == 0x03ED || it == 0x03EE })
        assertTrue(unattributed().toString(), unattributed().none { it.contains("no PCM request outstanding") })
    }

    @Test fun `control - GATT 13 is never retransmitted - each poll line is written once per poll`() = runTest {
        val link = connected { it.status = { line -> if (line.length > 20) 13 else BleSetupPolicy.GATT_SUCCESS } }
        advanceTimeBy(22_000L)                                    // PCM_INITIAL_DELAY_MS 20 s + a few batches
        val pcm = link.first.chunks.filter { it.startsWith("t7E08") }
        assertTrue(pcm.isNotEmpty())
        // consecutive identical PCM lines would be a retransmission (the schedule never repeats a DID back to back)
        assertTrue(pcm.zipWithNext().none { (a, b) -> a == b })
    }

    // ── #333: poll pacing after a possibly delivered write ──────────────────────────────────────────────────────────
    // The car (rc.8 over Bluetooth on the Pro): every poll write answered GATT 13, replies later than the 150 ms gap, KR2 /
    // KR3 (0x03ED / 0x03EE) never answering. rc.7 withdrew the request on the (then thrown) GATT 13 and paced at the gap
    // alone: a batch while running is the 7 fast DIDs + 1 slow DID (8 × 150 ms), or 9 while recording (MAP joins, #271).

    /** TIP-to-TIP windows of PCM lines after the first [skip] batches (engine running: one batch per window). */
    private fun batches(f: Fake, skip: Int = 2): List<List<Pair<Long, String>>> {
        val pcm = f.lines.filter { it.second.startsWith("t7E08") }
        val starts = pcm.indices.filter { pcm[it].second == ObdConstants.PCM_QUERY_TIP_ACTUAL }
        return starts.zipWithNext().drop(skip).map { (a, b) -> pcm.subList(a, b) }
    }

    /** Time from each TIP write to the next one, after the first [skip] batches. */
    private fun tipPeriods(f: Fake, skip: Int = 2): List<Long> =
        f.lines.filter { it.second == ObdConstants.PCM_QUERY_TIP_ACTUAL }.map { it.first }.zipWithNext { a, b -> b - a }.drop(skip)

    /**
     * Every PCM write GATT 13, replies 200 ms after the write, KR2 / KR3 silent: each batch takes its requests × the
     * 150 ms gap — the gap still paces (no gap: about 80 ms), nothing waits for a reply — and each batch is the fast group
     * in schedule order, then one slow DID, every line once. ± 25 ms: a TIP write can start one 10 ms write callback late
     * when another poller's write holds the link (the next period makes it up, seen 1 340 / 1 360); a wait for even one
     * 200 ms reply adds 50 ms and fails it.
     */
    private fun TestScope.assertRc7Pacing(recording: Boolean) {
        val link = connected(recording = recording) {
            it.status = { line -> if (line.length > 20) 13 else BleSetupPolicy.GATT_SUCCESS }
            it.latencyMs = 200L
            it.reply = { did -> if (did == 0x03ED || did == 0x03EE) null else "t7E880562%04X00000000".format(did).take(21) }
        }
        advanceTimeBy(60_000L)
        val fast = ObdConstants.pcmFastQueries(recording)
        val rc7BatchMs = (fast.size + 1) * ObdConstants.PCM_FAST_GAP_MS            // 8 × 150, or 9 × 150 recording
        val periods = tipPeriods(link.first)
        assertTrue("batches: ${periods.size}", periods.size >= 10)
        assertTrue("TIP-to-TIP periods (ms): $periods, rc.7 batch $rc7BatchMs", periods.all { it in rc7BatchMs - 25..rc7BatchMs + 25 })
        val windows = batches(link.first)
        windows.forEach { w ->
            val sent = w.map { it.second }
            assertEquals(sent.toString(), fast, sent.take(fast.size))
            assertEquals(sent.toString(), fast.size + 1, sent.size)
            assertTrue(sent.toString(), sent.last() in ObdConstants.PCM_SLOW_QUERIES)
        }
    }

    @Test fun `GATT 13 polls with replies later than the gap - a batch takes the rc_7 time and no line is written twice (#333)`() = runTest {
        assertRc7Pacing(recording = false)
    }

    @Test fun `GATT 13 polls while recording - nine requests (MAP joins) take the rc_7 time, no line is written twice (#333)`() = runTest {
        assertRc7Pacing(recording = true)
    }

    @Test fun `GATT 13 polls with replies later than the gap - NRCs are charged to nobody, never to the wrong DID (#333)`() = runTest {
        // The cost of not waiting: with replies later than the gap, two different DIDs are usually outstanding when an NRC
        // arrives, so the ledger (#216) cannot say whose it is. Suppression of KR2 / KR3 is lost on such a link; no other
        // DID is ever suppressed. Their requests, never resolved, lapse and keep later NRCs unattributed (the ledger's
        // documented consequence); positive replies still resolve their own requests, so no other DID lapses.
        val link = connected {
            it.status = { line -> if (line.length > 20) 13 else BleSetupPolicy.GATT_SUCCESS }
            it.latencyMs = 200L
            it.reply = { did -> if (did == 0x03ED || did == 0x03EE) nrc(0x31) else "t7E880562%04X00000000".format(did).take(21) }
        }
        advanceTimeBy(60_000L)
        assertTrue(suppressed().toString(), suppressed().all { it == 0x03ED || it == 0x03EE })
        assertTrue(suppressed().toString(), suppressed().isEmpty())
        assertTrue(unattributed().toString(), unattributed().any { it.contains("ambiguous") })
        val named = unattributed().flatMap { m -> Regex("\\b[0-9A-F]{4}\\b").findAll(m).map { it.value.toInt(16) }.toList() }
        assertTrue(unattributed().toString(), named.isNotEmpty() && named.all { it == 0x03ED || it == 0x03EE })
        assertTrue(DiagnosticLogger.linkIntegrity.toString(), DiagnosticLogger.linkIntegrity.nrcAttributed == 0)
    }

    @Test fun `control - a write reported as sent still waits for its reply - Wi-Fi pacing is unchanged (#333)`() = runTest {
        // Status 0 (as every TCP / WebSocket write): the poller waits up to REPLY_WINDOW_MS for each reply, as in rc.7.
        val link = connected {
            it.latencyMs = 200L
            it.reply = { did -> if (did == 0x03ED || did == 0x03EE) null else "t7E880562%04X00000000".format(did).take(21) }
        }
        advanceTimeBy(60_000L)
        val periods = batches(link.first).map { w -> w.last().first - w.first().first }
        assertTrue("batches: ${periods.size}", periods.size >= 10)
        // TIP to the slow DID spans the 7 fast requests: 5 wait their 200 ms reply, KR2 / KR3 the full window — about
        // 1 600 ms; pacing at the gap alone would be 7 × 150 = 1 050 ms
        val floor = 5 * 200L + 2 * PcmRequestLedger.REPLY_WINDOW_MS - 100L
        assertTrue("TIP-to-last-line spans (ms): $periods, floor $floor", periods.all { it >= floor })
    }

    @Test fun `another status after sending is uncertain - the request is kept, later NRCs are charged to nobody`() = runTest {
        // 03EC (KR1) writes fail with GATT 133 after being sent and the ECU never answers them (not delivered — unknowable
        // to the app); 03ED rejects. Withdrawing 03EC (assuming "not sent") would let 03ED's NRC be charged with certainty.
        val link = connected {
            it.status = { line -> if (line == lineOf(0x03EC)) 133 else BleSetupPolicy.GATT_SUCCESS }
            it.delivered = { line -> line != lineOf(0x03EC) }
            it.reply = { did -> if (did == 0x03ED) nrc(0x31) else "t7E880562%04X00000000".format(did).take(21) }
        }
        advanceTimeBy(40_000L)
        assertFalse(suppressed().toString(), 0x03ED in suppressed())
        assertTrue(unattributed().toString(), unattributed().any { it.contains("still unanswered") && it.contains("03EC") })
    }

    @Test fun `control - a write the stack refuses before sending is withdrawn, later NRCs are still attributed`() = runTest {
        val link = connected {
            it.refuse = { line -> line == lineOf(0x03EC) }
            it.reply = { did -> if (did == 0x03ED) nrc(0x31) else "t7E880562%04X00000000".format(did).take(21) }
        }
        advanceTimeBy(40_000L)
        assertTrue(link.first.chunks.none { it == lineOf(0x03EC) })
        assertTrue(suppressed().toString(), 0x03ED in suppressed())
    }

    @Test fun `control - MTU 23 sends a 22-byte line as 20 + 2 ordered writes, once`() = runTest {
        val link = connected(mtu = 23)
        advanceTimeBy(22_000L)
        val chunks = link.first.chunks
        assertTrue(chunks.all { it.length <= 20 })
        val lines = chunks.joinToString("").split("\r").filter { it.startsWith("t7E08") }
        assertTrue(lines.isNotEmpty())
        assertTrue(lines.all { it.length == 21 })
        assertTrue(lines.zipWithNext().none { (a, b) -> a == b })
    }

    // ── DID prober ──────────────────────────────────────────────────────────────────────────────────────────────────

    private val probeDids = listOf(0xABCD, 0xABCE, 0xABCF)
    private fun probeReply(did: Int) = "t7E880462%04XBA000000".format(did)

    @Test fun `prober over BLE - GATT 13 on every probe write still completes DID by DID`() = runTest {
        val link = connected(rpm = 0.0) {
            it.status = { line -> if (line.length > 20) 13 else BleSetupPolicy.GATT_SUCCESS }
            it.reply = { did -> if (did in probeDids) probeReply(did) else "t7E880562%04X00000000".format(did).take(21) }
        }
        advanceTimeBy(25_000L)
        val shown = mutableListOf<DiagnosticLogger.ProbeResult>()
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, probeDids, link.conn::sendRawQuery, { false }) { shown += it }
        assertEquals(DidProbeEnd.Complete, end)
        assertEquals(probeDids.map { DiagnosticLogger.ProbeResult(it, "FOUND", "BA 00 00 00") }, shown)
        assertEquals(probeDids.map { lineOf(it) }, link.first.chunks.filter { c -> probeDids.any { lineOf(it) == c } })
    }

    @Test fun `prober over BLE - a write the stack refuses ends the scan with nothing sent`() = runTest {
        val link = connected(rpm = 0.0) { it.refuse = { line -> line == lineOf(0xABCD) } }
        advanceTimeBy(25_000L)
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, probeDids, link.conn::sendRawQuery, { false }) {
            error("a refused write produced a result")
        }
        assertTrue(end.toString(), end is DidProbeEnd.Failed && end.did == 0xABCD && !end.sent)
        assertTrue(link.first.chunks.none { it == lineOf(0xABCD) })
    }

    @Test fun `control - prober over BLE - another failure after sending ends the scan as possibly received, never resent`() = runTest {
        val link = connected(rpm = 0.0) {
            it.status = { line -> if (line == lineOf(0xABCD)) 133 else BleSetupPolicy.GATT_SUCCESS }
            it.reply = { did -> if (did in probeDids) probeReply(did) else "t7E880562%04X00000000".format(did).take(21) }
        }
        advanceTimeBy(25_000L)
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, probeDids, link.conn::sendRawQuery, { false }) { }
        assertTrue(end.toString(), end is DidProbeEnd.Failed && end.did == 0xABCD && end.sent)
        assertEquals(1, link.first.chunks.count { it == lineOf(0xABCD) })
    }

    @Test fun `control - an unacknowledged probe write closes the link, the new link never resends it`() = runTest {
        val link = connected(rpm = 0.0) { it.status = { line -> if (line == lineOf(0xABCD)) null else BleSetupPolicy.GATT_SUCCESS } }
        advanceTimeBy(25_000L)
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, probeDids, link.conn::sendRawQuery, { false }) { }
        assertTrue(end.toString(), end is DidProbeEnd.Failed && end.did == 0xABCD && end.sent)
        // the late callback for the timed-out write lands on the closed core — it completes nothing
        link.first.core.onWrite(13)
        advanceTimeBy(30_000L)
        assertTrue("reconnected", link.fakes.size >= 2)
        assertTrue(link.fakes.drop(1).none { f -> f.chunks.any { it == lineOf(0xABCD) } })
        assertEquals(1, link.first.chunks.count { it == lineOf(0xABCD) })
    }

    // ── QA follow-ups (9d area 1 review F1 / F3) ─────────────────────────────────────────────────────────────────────

    private fun bleWriteEvents() = DiagnosticLogger.sessionEvents.filter { it.type == "BLE_WRITE" }

    @Test fun `core - GATT 13 is POSSIBLY_DELIVERED, status 0 is SENT`() = runTest {
        val marker = lineOf(0xABC0)
        val link = connected(rpm = 0.0) { it.status = { line -> if (line == marker) 13 else BleSetupPolicy.GATT_SUCCESS } }
        assertEquals(WriteOutcome.POSSIBLY_DELIVERED, link.first.core.write(marker))
        assertEquals(WriteOutcome.SENT, link.first.core.write(lineOf(0xABC1)))
    }

    @Test fun `core - a refusal after the first part at MTU 23 is uncertain, a refusal of the first part is not sent`() = runTest {
        val second = lineOf(0xABC2); val first = lineOf(0xABC3)
        val link = connected(mtu = 23, rpm = 0.0) {
            it.refuse = { line -> line == second || line == first.take(20) }   // second: its 2-byte tail; first: its head
        }
        val e = runCatching { link.first.core.write(second) }.exceptionOrNull()
        assertTrue("$e", e != null && WriteFailure.isUncertain(e) && !WriteFailure.isNotSent(e))
        val f = runCatching { link.first.core.write(first) }.exceptionOrNull()
        assertTrue("$f", f != null && WriteFailure.isNotSent(f) && !WriteFailure.isUncertain(f))
    }

    @Test fun `core - the link closing mid-write is uncertain`() = runTest {
        val marker = lineOf(0xABC4)
        val link = connected(rpm = 0.0) { it.status = { line -> if (line == marker) null else BleSetupPolicy.GATT_SUCCESS } }
        backgroundScope.launch { delay(100); link.first.core.close() }
        val e = runCatching { link.first.core.write(marker) }.exceptionOrNull()
        assertTrue("$e", e != null && WriteFailure.isUncertain(e))
    }

    @Test fun `BLE_WRITE is logged once per connection - and once more after a reconnect`() = runTest {
        val link = connected { it.status = { line -> if (line.length > 20) 13 else BleSetupPolicy.GATT_SUCCESS } }
        advanceTimeBy(30_000L)                                    // many possibly-delivered polls on this connection
        assertEquals(bleWriteEvents().map { it.message }.toString(), 1, bleWriteEvents().size)
        assertTrue(bleWriteEvents().single().message.contains("possibly delivered"))
        // every possibly-delivered write is counted for the export (#253), though logged once
        assertTrue(DiagnosticLogger.linkIntegrity.writes.toString(),
            (DiagnosticLogger.linkIntegrity.writes[DiagnosticLogger.WRITE_POSSIBLY_DELIVERED] ?: 0) > 1)
        link.first.core.onDisconnected(8)                         // link lost → EOF → SlcanConnection reconnects
        advanceTimeBy(60_000L)
        assertTrue("reconnected", link.fakes.size >= 2 && link.fakes.last().chunks.any { it.startsWith("t7E08") })
        assertEquals(bleWriteEvents().map { it.message }.toString(), 2, bleWriteEvents().size)
    }

    @Test fun `DTC scan over GATT 13 - the module waits for its reply and reads OK, no write-failed note`() = runTest {
        val link = connected(rpm = 0.0) { it.status = { line -> if (line.length > 20) 13 else BleSetupPolicy.GATT_SUCCESS } }
        advanceTimeBy(1_000L)
        val pcm = DtcScanner.MODULES.first { it.name == "PCM" }
        val result = DtcScanner.runScan(link.conn, listOf(pcm)) { _, _ -> }
        assertEquals(ModuleScanStatus.OK, result.moduleStatuses["PCM"])
        assertTrue(result.moduleNotes.toString(), result.moduleNotes.isEmpty())
    }
}
