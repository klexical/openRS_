package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.can.WriteFailure
import com.openrs.dash.can.WriteOutcome
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * #209: every request the app writes is in the private raw log as a `# TX` comment line, on the replies' clock, with the
 * write's result (the link-integrity counters' names), its time taken BEFORE the write (QA's T1: a reply that arrives while
 * the write is still returning must not read as earlier than its request). Live SlcanConnection over a scripted adapter
 * (the production path); every assertion reads the raw log file itself.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RequestLogTest {
    /** A scripted adapter: answers a Mode 22 read with a positive reply, writes with [outcome] or throws [fail]. */
    private class Adapter(
        private val outcome: (String) -> WriteOutcome = { WriteOutcome.SENT },
        private val fail: (String) -> Exception? = { null },
        private val replyInsideWrite: Boolean = false,
    ) : SlcanTransport {
        val lines = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun write(frame: String): WriteOutcome {
            fail(frame)?.let { throw it }
            answer(frame)
            if (replyInsideWrite) Thread.sleep(40)        // the reply is already in while this write is still returning
            return outcome(frame)
        }
        override suspend fun writeLine(frame: String) { write(frame) }
        private fun answer(frame: String) {
            // t<id><8>03 22 DH DL …  →  t<id+8>8 04 62 DH DL 00 00 00 00 (a positive reply)
            if (frame.length >= 13 && frame[0] == 't' && frame.substring(5, 9) == "0322") {
                val resp = frame.substring(1, 4).toInt(16) + 8
                lines.trySend("t%03X8%s".format(resp, "0462" + frame.substring(9, 13) + "00000000"))
            }
        }
        override fun close() { lines.close() }
        override val label = "scripted"; override val stockFirmwareLabel = "scripted fw"
    }

    private val dir = File(System.getProperty("java.io.tmpdir"), "request-log-${System.nanoTime()}")
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = dir) }
    private fun log(): List<String> { DiagnosticLogger.flushSlcan(); return DiagnosticLogger.slcanLogFile!!.readLines() }
    private fun tx(id: String) = log().filter { it.startsWith("# TX ") && it.contains(" can0 $id#") }

    private suspend fun TestScope.connect(a: Adapter, rpm: Double = 1_000.0): SlcanConnection {
        val conn = SlcanConnection(transportFactory = { a }, maxRetries = 1, rpmProvider = { rpm },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime })
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState(rpm = rpm) }).collect {} }
        return conn
    }

    @Test fun `every PCM poll is a TX line with its result, in the raw log`() = runTest {
        connect(Adapter())
        advanceTimeBy(30_000L)                       // the PCM poller starts 20 s after the connection
        val lines = tx("7E0")
        assertTrue("PCM poll lines: $lines", lines.size >= 5)
        assertTrue(lines.toString(), lines.all { Regex("""^# TX \(\s*\d+\.\d{3}\) can0 7E0#0322[0-9A-F]{4}00000000 sent$""").matches(it) })
    }

    @Test fun `BCM, AWD and extended-session writes are TX lines too`() = runTest {
        connect(Adapter(), rpm = 0.0)
        advanceTimeBy(20_000L)
        assertTrue("BCM: ${tx("726")}", tx("726").isNotEmpty())
        assertTrue("AWD: ${tx("703")}", tx("703").isNotEmpty())
        assertTrue("an extended session request (10 03): ${log().filter { it.startsWith("# TX") }}",
            log().any { it.startsWith("# TX ") && Regex("#021003").containsMatchIn(it) })
    }

    @Test fun `a probe request is a TX line`() = runTest {
        val conn = connect(Adapter())
        advanceTimeBy(1_000L)
        backgroundScope.launch { conn.sendRawQuery(0x7E8, "t7E080322ABCD00000000\r") }
        advanceTimeBy(2_000L)
        assertEquals(tx("7E0").filter { it.contains("#0322ABCD") }.toString(), 1, tx("7E0").count { it.contains("#0322ABCD") && it.endsWith(" sent") })
    }

    @Test fun `each write's result is named as the link-integrity counters name it`() = runTest {
        connect(Adapter(
            outcome = { if (it.contains("033E")) WriteOutcome.POSSIBLY_DELIVERED else WriteOutcome.SENT },
            fail = {
                when {
                    it.contains("0326") -> WriteFailure.notSent("test")
                    it.contains("03EC") -> WriteFailure.uncertain("test")
                    it.contains("0461") || it.contains("03ED") -> IOException("test: other")
                    else -> null
                }
            }))
        advanceTimeBy(30_000L)                       // the PCM poller starts 20 s after the connection
        val l = tx("7E0")
        assertTrue(l.toString(), l.any { it.contains("#0322033E") && it.endsWith(" possiblyDelivered") })
        assertTrue(l.toString(), l.any { it.contains("#032203EC") && it.endsWith(" deliveryUnknown") })
        assertTrue(l.toString(), l.any { it.contains("#032203ED") && it.endsWith(" otherFailure") })
        assertTrue("a failed write is logged too, never as sent: $l", l.none { (it.contains("#032203EC") || it.contains("#032203ED")) && it.endsWith(" sent") })
    }

    @Test fun `a write the adapter refuses outright reads notSent`() = runTest {
        connect(Adapter(fail = { if (it.contains("033E")) WriteFailure.notSent("test") else null }))
        advanceTimeBy(30_000L)                       // the PCM poller starts 20 s after the connection
        assertTrue(tx("7E0").toString(), tx("7E0").any { it.contains("#0322033E") && it.endsWith(" notSent") })
    }

    /**
     * A write the adapter reports as possibly delivered (Bluetooth GATT 13) reads so on every path, never `sent`: the
     * freeze-frame request goes through the untracked-write helper, the DTC scan request through the scan's own send.
     * On Bluetooth `writeLine` returns normally for such a write and drops its outcome, so a path that logs from
     * `writeLine` would call it sent.
     */
    @Test fun `a possibly delivered freeze-frame or DTC scan request is never logged as sent`() = runTest {
        val pd = { f: String -> if (f.contains("031904FF") || f.contains("031902FF")) WriteOutcome.POSSIBLY_DELIVERED else WriteOutcome.SENT }
        val conn = connect(Adapter(outcome = pd), rpm = 0.0)
        advanceTimeBy(100L)
        val pcm = com.openrs.dash.diagnostics.DtcScanner.MODULES.first { it.name == "PCM" }
        backgroundScope.launch { conn.performFreezeFrameIdentification(pcm) }
        advanceTimeBy(5_000L)
        backgroundScope.launch { com.openrs.dash.diagnostics.DtcScanner.runScan(conn, listOf(pcm)) { _, _ -> } }
        advanceTimeBy(10_000L)
        val l = tx("7E0")
        assertTrue("freeze-frame request: $l", l.any { it.contains("#031904FF") && it.endsWith(" possiblyDelivered") })
        assertTrue("DTC scan request: $l", l.any { it.contains("#031902FF") && it.endsWith(" possiblyDelivered") })
        assertTrue("never sent: $l", l.none { (it.contains("#031904FF") || it.contains("#031902FF")) && it.endsWith(" sent") })
    }

    /**
     * QA's T1, with real threads: the reply is read while the write is still returning (it sleeps 40 ms after the reply
     * is in); the request's time must not be later than its reply's. The DID prober's requests go through the same
     * logging as the polls, and start at once (the PCM poller waits 20 s).
     */
    @Test fun `a request's time is taken before its write, so its reply is never earlier`() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val a = Adapter(replyInsideWrite = true)
            val conn = SlcanConnection(transportFactory = { a }, maxRetries = 1, debugSink = {})
            scope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
            Thread.sleep(300)
            for (did in listOf("A101", "A102", "A103", "A104", "A105")) conn.sendRawQuery(0x7E8, "t7E080322${did}00000000\r")
            val lines = log()
            fun t(l: String) = Regex("""\(\s*([\d.]+)\)""").find(l)!!.groupValues[1].toDouble()
            var pairs = 0
            for (req in lines.filter { it.startsWith("# TX ") && it.contains(" can0 7E0#0322") }) {
                val did = req.substringAfter("#0322").take(4)
                val reply = lines.firstOrNull { it.startsWith("(") && it.contains(" can0 7E8#0462$did") && t(it) >= t(req) - 0.5 } ?: continue
                pairs++
                assertTrue("request $req is later than its reply $reply", t(req) <= t(reply))
            }
            assertTrue("pairs found: $pairs in ${lines.takeLast(20)}", pairs >= 3)
        } finally { scope.cancel() }
    }

    @Test fun `a request line goes through the VIN mask, and a normal request comes out unchanged`() {
        DiagnosticLogger.logTxLine("t7E080322F40D00000000\r")
        assertTrue(log().toString(), log().any { it.startsWith("# TX ") && it.contains("7E0#0322F40D00000000") })
    }

    /** The mask itself, on a request line: no request carries a VIN today, so this pins the defence, with a synthetic VIN. */
    @Test fun `a request line holding a run of the known VIN is masked like a received frame`() {
        val vin = "TESTVIN0123456789"
        DiagnosticLogger.logFrame(0x123, byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8), VehicleState(vin = vin), "", null)
        val run = vin.substring(4, 12).toByteArray(Charsets.US_ASCII).joinToString("") { "%02X".format(it) }   // "VIN01234"
        DiagnosticLogger.logTxLine("t7E08${run}\r")
        val l = log().filter { it.startsWith("# TX ") }
        assertEquals(l.toString(), 1, l.size)
        assertTrue("masked: $l", l[0].contains("7E0#0000000000000000 sent"))
        assertTrue("no VIN bytes: $l", !l[0].contains(run))
    }

    /** Only `t` frames: an SLCAN remote frame (`r`, no data) is the one other line whose ID and data would parse. */
    @Test fun `a line that is not a standard data frame (OPENRS?, AT+FRS, the SLCAN init, extended and remote frames) is never a TX line`() {
        listOf("OPENRS?\r", "AT+FRS=MODE,S\r", "C\rS6\rO\r", "T18DAF11080322F40D00000000\r", "r7E08\r").forEach { DiagnosticLogger.logTxLine(it) }
        assertEquals(log().toString(), 0, log().count { it.startsWith("# TX") })
    }
}
