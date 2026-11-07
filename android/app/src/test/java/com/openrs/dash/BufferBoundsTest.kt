package com.openrs.dash

import com.openrs.dash.can.BleAdapterProfile
import com.openrs.dash.can.BleFirmwareApi
import com.openrs.dash.can.BleGattCore
import com.openrs.dash.can.BusyException
import com.openrs.dash.can.GattLink
import com.openrs.dash.can.PcmRequestLedger
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanParser
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.can.TcpSlcanTransport
import com.openrs.dash.can.WebSocketSlcanTransport
import com.openrs.dash.data.BoostSessionLog
import com.openrs.dash.data.DtcModuleSpec
import com.openrs.dash.data.KnockCapture
import com.openrs.dash.data.KnockSessionLog
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.PerfRunEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.CrashTelemetryBuffer
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.DidProbeEnd
import com.openrs.dash.diagnostics.DriveExportBuilder
import com.openrs.dash.diagnostics.probeCsv
import com.openrs.dash.diagnostics.runDidProbeScan
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * rc.8 #253 stream A4, buffers — every bounded buffer between the adapter and the diagnostic export, through production
 * code: BleGattCore (fake GATT), TcpSlcanTransport / WebSocketSlcanTransport (a real loopback socket), SlcanConnection
 * (frame loop, channels, pollers), PcmRequestLedger, DiagnosticLogger + DiagnosticReportBuilder, KnockSessionLog,
 * BoostSessionLog, runDidProbeScan, CrashTelemetryBuffer. For each: the limit, what survives it, that the link keeps
 * working, and that the export says it was reached. SYNTHETIC lines only; no adapter, no capture. The WebSocket upgrade
 * key is null here (android.util.Base64 is a default-values stub under JVM tests); the fake server ignores it.
 *
 * Legal line lengths, from source: longest SLCAN data frame 30 chars (`T` + 8 ID + DLC + 16 data + 4 timestamp); longest
 * documented adapter line = the `AT+FRS?` state reply, ~84 chars (firmware/README.md); `OPENRS:<version>\r\n`
 * (firmware/patches/apply_patches.py). The first line of every connection is consumed by the SLCAN handshake, so frame
 * tests send a throwaway frame first.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BufferBoundsTest {
    @get:Rule val tmp = TemporaryFolder()
    private val pro = BleAdapterProfile.WICAN_PRO
    private val frsStateReply = "+FRS:driveMode=0,escMode=0,lcEnabled=false,assKill=false,battMv=12600,sleepMv=12200"

    @Before fun session() {
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        KnockSessionLog.reset(50_000L)
        BoostSessionLog.reset(100_000L)
    }

    /** Closes any SLCAN writer and leaves no over-limit state to the next class in this JVM. */
    @After fun endSession() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    // ── Helpers ─────────────────────────────────────────────────────────────────────────────────────────────────────

    /** CAN 0x123 — not a module response ID, so it reaches the collector; [i] sits in the data for order checks. */
    private fun frame(i: Int) = "t1238%016X".format(i)
    private fun index(f: Pair<Int, ByteArray>) = ByteBuffer.wrap(f.second).long.toInt()
    private fun oil(c: Int) = "t0F88005A0000000000%02X".format(c + 60)

    private fun detail() = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))
    private fun summary() = DiagnosticReportBuilder.buildSummaryText("t")
    /** A count from the export's `linkIntegrity` block; -1 = the export doesn't report it. */
    private fun linkCount(key: String) = detail().getJSONObject("linkIntegrity").optInt(key, -1)
    /** A count from the export's `logLimits` block; -1 = the export doesn't report it. */
    private fun limit(key: String) = detail().optJSONObject("logLimits")?.optInt(key, -1) ?: -1

    /** BleGattCore through the callbacks BleSlcanTransport forwards; writes acknowledge at once. */
    private inner class Ble(val onLine: (Ble, String) -> Unit = { _, _ -> }) : SlcanTransport {
        val core = BleGattCore("MEATPI_PRO")
        private val partial = StringBuilder()
        private val gatt = object : GattLink {
            override fun discoverServices() = true
            override fun setNotify(service: UUID, characteristic: UUID, enable: Boolean) = true
            override fun writeCccd(service: UUID, characteristic: UUID, value: ByteArray) = true
            override fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean {
                partial.append(String(value, Charsets.ISO_8859_1))
                val text = partial.toString()
                if (text.endsWith("\r")) { partial.clear(); onLine(this@Ble, text) }
                core.onWrite(0)
                return true
            }
            override fun requestMtu(mtu: Int) = false
            override fun bondState() = BleGattCore.BOND_BONDED
            override fun createBond() = true
            override fun disconnect() = Unit
            override fun close() = Unit
        }
        fun ready() {
            core.onConnected(gatt)
            core.onServicesDiscovered(0, mapOf(pro.service to mapOf(
                pro.write to BleGattCore.PROPERTY_WRITE, pro.notify to BleGattCore.PROPERTY_NOTIFY)))
        }
        fun notifyText(text: String) = core.onNotify(pro.notify, text.toByteArray(Charsets.ISO_8859_1))
        override suspend fun open() = ready()
        override suspend fun readLine() = core.readLine()
        override suspend fun writeLine(frame: String) = core.writeLine(frame)
        override suspend fun write(frame: String) = core.write(frame)
        override fun close() = core.close()
        override val label = "BLE fake"
        override val stockFirmwareLabel = "fake"
    }

    /** A plain line stream (the Wi-Fi shape) with no limit of its own, so only the connection's buffers are under test. */
    private class Script(var onWrite: (Script, String) -> Unit = { _, _ -> }) : SlcanTransport {
        private val ch = Channel<String>(Channel.UNLIMITED)
        var reads = 0
        val writes = mutableListOf<String>()
        fun feed(vararg lines: String) = lines.forEach { ch.trySend(it) }
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = ch.receiveCatching().getOrNull()?.also { reads++ }
        override suspend fun writeLine(frame: String) { writes += frame; onWrite(this, frame) }
        override fun close() { ch.close() }
        override val label = "script"
        override val stockFirmwareLabel = "script"
    }

    private fun TestScope.connect(
        t: SlcanTransport,
        rpm: Double = 0.0,
        firmware: (SlcanConnection.FirmwareStatus) -> Unit = {},
        onObd: (VehicleState) -> Unit = {},
        onFrame: suspend (Pair<Int, ByteArray>) -> Unit = {},
    ): SlcanConnection {
        val conn = SlcanConnection(
            transportFactory = { t }, maxRetries = 1, rpmProvider = { rpm },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
            firmwareSink = firmware,
        )
        backgroundScope.launch { conn.connectHybrid(onObd, { VehicleState() }).collect { onFrame(it) } }
        runCurrent()
        return conn
    }

    // ── BleGattCore: the line buffer (256) ──────────────────────────────────────────────────────────────────────────

    @Test fun `ble - a line of exactly 256 characters is delivered whole, CR-delimited lines around it unharmed`() = runTest {
        val b = Ble().apply { ready() }
        val line = "x".repeat(256)
        b.notifyText("${frame(1)}\r$line\r${frame(2)}\r")
        assertEquals(frame(1), b.readLine())
        assertEquals(line, b.readLine())
        assertEquals(frame(2), b.readLine())
        assertEquals("a legal length isn't overlong", 0, linkCount("overlongLines"))
    }

    @Test fun `ble - a line over the bound is refused whole, counted once, and the next line recovers`() = runTest {
        val b = Ble().apply { ready() }
        b.notifyText("x".repeat(257) + "\r" + frame(1) + "\r")
        b.notifyText("y".repeat(5_000) + "\r" + frame(2) + "\r")
        assertEquals("no truncated prefix is delivered", frame(1), b.readLine())
        assertEquals(frame(2), b.readLine())
        assertEquals(2, linkCount("overlongLines"))
    }

    @Test fun `ble - a stream with no CR stops growing at the bound, across many notifications`() = runTest {
        val b = Ble().apply { ready() }
        repeat(4_096) { b.notifyText("z".repeat(244)) }                  // 1 MiB in MTU-sized notifications
        val held = BleGattCore::class.java.getDeclaredField("lineBuffer").apply { isAccessible = true }.get(b.core) as StringBuilder
        assertTrue("line buffer holds ${held.length} chars", held.length <= 256)
        b.notifyText("\r${frame(1)}\r")
        assertEquals(frame(1), b.readLine())
        assertEquals("one line, however long", 1, linkCount("overlongLines"))
    }

    @Test fun `ble - an overlong OPENRS line is not a firmware reply`() = runTest {
        val b = Ble()
        val seen = mutableListOf<SlcanConnection.FirmwareStatus>()
        val got = mutableListOf<Int>()
        connect(b, firmware = { seen += it }) { got += index(it) }
        b.notifyText(frame(0) + "\r"); runCurrent()                       // the handshake takes this one
        b.notifyText("OPENRS:" + "A".repeat(300) + "\r" + frame(1) + "\r"); runCurrent()
        assertFalse("a truncated OPENRS: line unlocked firmware controls: ${seen.lastOrNull()?.label?.take(30)}",
            seen.any { it.openRs })
        assertEquals(listOf(1), got)
    }

    @Test fun `ble - an overlong +FRS line is not a command reply`() = runTest {
        val b = Ble { t, line -> if (line.startsWith("AT+FRS")) t.notifyText("+FRS:OK" + "A".repeat(300) + "\r") }
        val conn = connect(b)
        b.notifyText(frame(0) + "\r"); runCurrent()
        val result = BleFirmwareApi(conn).setDriveMode(1)
        assertTrue("a truncated +FRS: line read as accepted: $result", result.isFailure)
        assertEquals("AT+FRS timeout", result.exceptionOrNull()?.message)
    }

    @Test fun `ble - an overlong line that starts as a frame changes no state, the next frame does`() = runTest {
        val b = Ble()
        val state = MutableStateFlow(VehicleState(isConnected = true))
        val link = LinkOwnership()
        val session = LinkSession(link, link.begin(), state, NoRecorder, onFrameCounted = {}, perfRunSink = { _: PerfRunEntity -> })
        connect(b, onObd = { state.value = ObdMerge.merge(state.value, it) }) { (id, d) -> session.onCanFrame(id, d, 0.0) }
        b.notifyText(frame(0) + "\r"); runCurrent()
        b.notifyText(oil(195) + "0".repeat(300) + "\r"); runCurrent()
        assertEquals("nothing decoded from it", VehicleState().oilTempC, state.value.oilTempC, 0.0)
        b.notifyText(oil(70) + "\r"); runCurrent()
        assertEquals(70.0, state.value.oilTempC, 0.0)
        assertEquals("no peak from the overlong line", 70.0, state.value.peakOilTempC, 0.0)
    }

    @Test fun `ble - a partial line at disconnect is not delivered, buffered lines are, then EOF`() = runTest {
        val b = Ble().apply { ready() }
        b.notifyText(frame(1) + "\r" + frame(2).take(9))
        b.core.onDisconnected(8)
        assertEquals(frame(1), b.readLine())
        assertNull(b.readLine())
        b.notifyText(frame(3) + "\r")                                     // a late notification: nowhere to go, no exception
        assertEquals("a closed queue isn't a full one", 0, linkCount("linesDropped"))
    }

    // ── BleGattCore: the line queue (256) ───────────────────────────────────────────────────────────────────────────

    @Test fun `ble - a full line queue keeps the oldest 256, counts the rest, and accepts lines once drained`() = runTest {
        val b = Ble().apply { ready() }
        repeat(300) { b.notifyText(frame(it) + "\r") }
        repeat(256) { assertEquals(frame(it), b.readLine()) }
        b.notifyText(frame(1_000) + "\r")
        assertEquals("still working after the overflow", frame(1_000), b.readLine())
        assertEquals(44, linkCount("linesDropped"))
        assertTrue(summary(), summary().contains("44 line(s) dropped"))
    }

    @Test fun `ble - a stalled collector loses lines only by count - every line is delivered or counted`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val b = Ble()
        val got = mutableListOf<Int>()
        connect(b) { gate.await(); got += index(it) }
        val total = 2_000
        b.notifyText(frame(0) + "\r"); runCurrent()                       // the handshake takes this one
        for (i in 1 until total step 100) {
            for (j in i until minOf(i + 100, total)) b.notifyText(frame(j) + "\r")
            advanceTimeBy(10L); runCurrent()
        }
        assertTrue("the collector is stalled: nothing may be delivered yet", got.isEmpty())
        gate.complete(Unit)
        advanceTimeBy(1_000L)
        val dropped = linkCount("linesDropped")
        assertTrue("a flood past the queues must drop (dropped=$dropped)", dropped > 0)
        assertEquals("delivered ${got.size} + dropped $dropped + 1 handshake line", total, got.size + dropped + 1)
        assertEquals("delivered in order", got.sorted(), got)
        b.notifyText(frame(5_000) + "\r"); advanceTimeBy(10L)
        assertEquals("still working", 5_000, got.last())
    }

    // ── TcpSlcanTransport: real socket ──────────────────────────────────────────────────────────────────────────────

    /** Accepts connections in order, runs each one's script, holds the socket open until [close]. Extras are held silent. */
    private class Server(vararg scripts: (OutputStream) -> Unit) : AutoCloseable {
        private val ss = ServerSocket(0, 50, InetAddress.getLoopbackAddress())
        private val held = CopyOnWriteArrayList<Socket>()
        val accepted = AtomicInteger()
        val port: Int get() = ss.localPort
        init {
            val list = scripts.toList()
            Thread {
                try {
                    while (true) {
                        val s = ss.accept()
                        held += s
                        val script = list.getOrNull(accepted.getAndIncrement())
                        Thread { try { script?.invoke(s.getOutputStream()) } catch (_: IOException) { } }
                            .apply { isDaemon = true }.start()
                    }
                } catch (_: IOException) { }
            }.apply { isDaemon = true }.start()
        }
        override fun close() { ss.close(); held.forEach { try { it.close() } catch (_: IOException) { } } }
    }

    private fun send(out: OutputStream, text: String) { out.write(text.toByteArray(Charsets.ISO_8859_1)); out.flush() }

    private fun <T> tcpTransport(server: Server, block: suspend (TcpSlcanTransport) -> T): T = runBlocking {
        val t = TcpSlcanTransport("127.0.0.1", server.port)
        try { t.open(); withTimeout(10_000L) { block(t) } } finally { t.close() }
    }

    @Test fun `tcp - the longest frame and the documented AT+FRS state reply are delivered whole`() {
        val longest = "T12345678" + "8" + "0".repeat(16) + "EA5F"
        assertEquals(30, longest.length)
        Server({ out -> send(out, "$longest\r$frsStateReply\r${frame(1)}\r") }).use { server ->
            tcpTransport(server) { t ->
                assertEquals(longest, t.readLine())
                assertEquals("a legal adapter line must not read as EOF", frsStateReply, t.readLine())
                assertEquals(frame(1), t.readLine())
            }
        }
        assertEquals(0, linkCount("overlongLines"))
    }

    @Test fun `tcp - 256 characters are a line, the 257th ends the read and is counted`() {
        val ok = "x".repeat(256)
        Server({ out -> send(out, ok + "\r" + "y".repeat(257) + "\r") }).use { server ->
            tcpTransport(server) { t ->
                assertEquals(ok, t.readLine())
                assertNull("no partial line", t.readLine())
            }
        }
        assertEquals(1, linkCount("overlongLines"))
    }

    @Test fun `tcp - a stream with no CR ends the read at the bound instead of growing`() {
        Server({ out ->
            val chunk = ByteArray(64 * 1024) { 'z'.code.toByte() }
            while (true) out.write(chunk)
        }).use { server ->
            tcpTransport(server) { t -> assertNull(t.readLine()) }
        }
        assertEquals(1, linkCount("overlongLines"))
    }

    private fun tcpConnection(port: Int, frames: MutableList<Int>, firmware: (SlcanConnection.FirmwareStatus) -> Unit = {}) =
        SlcanConnection(
            transportFactory = { TcpSlcanTransport("127.0.0.1", port) }, maxRetries = 3, reconnectDelayMs = 20L,
            debugSink = {}, firmwareSink = firmware,
        ).also { conn ->
            scopes += CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scope ->
                scope.launch { conn.connectHybrid({}, { VehicleState() }).collect { frames += index(it) } }
            }
        }
    private val scopes = CopyOnWriteArrayList<CoroutineScope>()
    @After fun cancelScopes() = scopes.forEach { it.cancel() }

    private fun waitFor(what: () -> String, ms: Long = 8_000L, ok: () -> Boolean) {
        val end = System.currentTimeMillis() + ms
        while (!ok()) {
            if (System.currentTimeMillis() > end) fail("timed out waiting for: ${what()}")
            Thread.sleep(10)
        }
    }

    @Test fun `tcp connection - a legal long adapter line does not drop the link`() {
        val frames = CopyOnWriteArrayList<Int>()
        Server({ out -> send(out, "${frame(0)}\r${frame(1)}\r$frsStateReply\r${frame(2)}\r") }).use { server ->
            tcpConnection(server.port, frames)
            waitFor({ "frame 2 on the same connection; frames=$frames accepted=${server.accepted.get()}" }, 3_000L) { 2 in frames }
            assertEquals("no reconnect", 1, server.accepted.get())
        }
    }

    @Test fun `tcp connection - a runaway line ends the attempt, the next connection recovers, and it is counted`() {
        val frames = CopyOnWriteArrayList<Int>()
        Server(
            { out -> send(out, "${frame(0)}\r${frame(1)}\r" + "z".repeat(1_000)) },
            { out -> send(out, "${frame(2)}\r${frame(3)}\r") },
        ).use { server ->
            tcpConnection(server.port, frames)
            waitFor({ "frame 3 after the reconnect; frames=$frames" }) { 3 in frames }
            assertEquals(2, server.accepted.get())
            assertEquals("frame 0 and 2 go to each connection's handshake", listOf(1, 3), frames.toList())
        }
        assertEquals(1, linkCount("overlongLines"))
    }

    @Test fun `tcp connection - the frame after the firmware's CR LF reply is not lost`() {
        val frames = CopyOnWriteArrayList<Int>()
        val labels = CopyOnWriteArrayList<String>()
        Server({ out -> send(out, "OPENRS:TEST v0\r\n${frame(1)}\r${frame(2)}\r") }).use { server ->
            tcpConnection(server.port, frames) { labels += it.label }
            waitFor({ "frame 2; frames=$frames" }) { 2 in frames }
        }
        assertTrue(labels.toString(), labels.any { it == "openRS_ TEST v0" })
        assertEquals("the line after OPENRS:…\\r\\n starts with LF and used to be refused uncounted", listOf(1, 2), frames.toList())
    }

    @Test fun `ble connection - the frame after the firmware's CR LF reply is not lost`() = runTest {
        val b = Ble()
        val got = mutableListOf<Int>()
        connect(b) { got += index(it) }
        b.notifyText("OPENRS:TEST v0\r\n"); runCurrent()                  // the handshake takes the reply
        b.notifyText(frame(1) + "\r" + frame(2) + "\r"); runCurrent()
        assertEquals(listOf(1, 2), got)
    }

    // ── WebSocketSlcanTransport: real socket ────────────────────────────────────────────────────────────────────────

    private val upgrade = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n\r\n"

    private fun wsFrame(opcode: Int, payload: ByteArray): ByteArray {
        val head = java.io.ByteArrayOutputStream()
        head.write(0x80 or opcode)
        when {
            payload.size < 126 -> head.write(payload.size)
            payload.size <= 0xFFFF -> { head.write(126); head.write(payload.size ushr 8); head.write(payload.size and 0xFF) }
            else -> { head.write(127); head.write(ByteBuffer.allocate(8).putLong(payload.size.toLong()).array()) }
        }
        head.write(payload)
        return head.toByteArray()
    }
    private fun wsText(text: String) = wsFrame(0x1, text.toByteArray(Charsets.ISO_8859_1))

    private fun <T> wsTransport(server: Server, block: suspend (WebSocketSlcanTransport) -> T): T = runBlocking {
        val t = WebSocketSlcanTransport("127.0.0.1", server.port)
        try { t.open(); withTimeout(10_000L) { block(t) } } finally { t.close() }
    }

    @Test fun `ws - an upgrade response that never ends is refused at a bound, not read for ever`() {
        val sent = AtomicLong()
        Server({ out ->
            val chunk = ByteArray(4 * 1024) { 'h'.code.toByte() }
            while (sent.get() < 256L * 1024 * 1024) { out.write(chunk); sent.addAndGet(chunk.size.toLong()) }
        }).use { server ->
            val t = WebSocketSlcanTransport("127.0.0.1", server.port)
            val done = CountDownLatch(1)
            var error: Throwable? = null
            Thread {
                try { runBlocking { t.open() } } catch (e: Throwable) { error = e }
                done.countDown()
            }.apply { isDaemon = true }.start()
            val ended = done.await(3, TimeUnit.SECONDS)
            val read = sent.get()
            t.close()
            assertTrue("open() still reading headers after 3 s (${read / 1024} KiB sent so far)", ended)
            assertTrue("open() must fail: $error", error is IOException)
        }
    }

    @Test fun `ws - a long but legal upgrade response is accepted`() {
        val padded = "HTTP/1.1 101 Switching Protocols\r\nX-Pad: ${"p".repeat(4_000)}\r\nUpgrade: websocket\r\n\r\n"
        Server({ out -> send(out, padded); out.write(wsText(frame(1))); out.flush() }).use { server ->
            wsTransport(server) { t -> assertEquals(frame(1), t.readLine()) }
        }
    }

    @Test fun `ws - a message is one line - a legal long one is delivered, an overlong one is skipped and counted`() {
        Server({ out ->
            send(out, upgrade)
            out.write(wsText("$frsStateReply\r"))                         // the adapter's documented longest line
            out.write(wsText("x".repeat(256)))
            out.write(wsText(frame(1)))                                   // no CR: a WS message needs none
            out.write(wsText("y".repeat(257)))
            out.write(wsText("OPENRS:" + "A".repeat(60_000)))             // 16-bit length form
            out.write(wsFrame(0x1, ByteArray(1_048_576) { 'a'.code.toByte() }))   // 64-bit length form, at the frame limit
            out.write(wsText(frame(2)))
            out.flush()
        }).use { server ->
            wsTransport(server) { t ->
                assertEquals(frsStateReply, t.readLine())
                assertEquals("x".repeat(256), t.readLine())
                assertEquals(frame(1), t.readLine())
                assertEquals("overlong messages are not lines", frame(2), t.readLine())
            }
        }
        assertEquals(3, linkCount("overlongLines"))
    }

    @Test fun `ws - a frame declaring more than 1 MiB fails the read before its payload and is counted`() {
        Server({ out ->
            send(out, upgrade)
            out.write(byteArrayOf(0x81.toByte(), 127))
            out.write(ByteBuffer.allocate(8).putLong(1_048_577L).array())   // no payload follows
            out.flush()
        }).use { server ->
            wsTransport(server) { t ->
                try { t.readLine(); fail("must throw") } catch (e: IOException) { assertTrue(e.message!!, e.message!!.contains("too large")) }
            }
        }
        assertEquals(1, linkCount("overlongLines"))
    }

    @Test fun `ws - two frames in one message stay one line and are refused, never half decoded`() {
        // Pins today's behaviour. The WiCAN Pro source (ws_server.c) sends one queue item per WS message, each one frame, so
        // this is not expected from that firmware; the USB firmware's sender wasn't in hand (see the report).
        Server({ out -> send(out, upgrade); out.write(wsText("${frame(1)}\r${frame(2)}\r")); out.flush() }).use { server ->
            wsTransport(server) { t ->
                val line = t.readLine()!!
                assertEquals("${frame(1)}\r${frame(2)}", line)
                assertNull(SlcanParser.shape(line))
            }
        }
    }

    // ── SlcanConnection: command reply channel (16) ─────────────────────────────────────────────────────────────────

    @Test fun `connection - a +FRS flood keeps the oldest 16, counts the rest, and never blocks the frame loop`() = runTest {
        val s = Script()
        val got = mutableListOf<Int>()
        val conn = connect(s) { got += index(it) }
        s.feed(frame(0)); runCurrent()
        s.feed(*Array(40) { "+FRS:ERROR,$it" })
        s.feed(frame(1)); runCurrent()
        assertEquals("frames still flow past a full reply channel", listOf(1), got)
        val kept = generateSequence { conn.commandResponseChannel.tryReceive().getOrNull() }.toList()
        assertEquals((0 until 16).map { "+FRS:ERROR,$it" }, kept)
        assertEquals(24, linkCount("commandRepliesDropped"))
    }

    @Test fun `connection - stale replies from a full channel can't answer the next command`() = runTest {
        val s = Script { t, line -> if (line.startsWith("AT+FRS")) t.feed("+FRS:OK") }
        val conn = connect(s)
        s.feed(frame(0)); runCurrent()
        s.feed(*Array(40) { "+FRS:BUSY" }); runCurrent()
        val result = BleFirmwareApi(conn).setDriveMode(1)
        assertTrue("got ${result.exceptionOrNull()}", result.isSuccess)
        assertFalse(result.exceptionOrNull() is BusyException)
    }

    // ── SlcanConnection: frame channel (channelFlow, suspends) ──────────────────────────────────────────────────────

    @Test fun `connection - a stalled collector stops the read loop instead of dropping frames`() = runTest {
        val gate = CompletableDeferred<Unit>()
        val s = Script()
        val got = mutableListOf<Int>()
        connect(s) { gate.await(); got += index(it) }
        repeat(1_000) { s.feed(frame(it)) }
        advanceTimeBy(5_000L)
        assertTrue("stream back-pressure: only ${s.reads} of 1000 read while stalled", s.reads < 200)
        assertTrue(got.isEmpty())
        gate.complete(Unit)
        advanceTimeBy(1_000L)
        assertEquals("all but the handshake's line, in order, none lost", (1 until 1_000).toList(), got)
        assertEquals(0, linkCount("linesDropped"))
    }

    // ── SlcanConnection: DTC channel (128) ──────────────────────────────────────────────────────────────────────────

    private val pcm = DtcModuleSpec("PCM", 0x7E0, 0x7E8)
    private fun cf(i: Int) = "t7E88%02X00000000000000".format(0x20 or (i and 0x0F))

    @Test fun `dtc - a multi-frame reply that fits the channel completes`() = runTest {
        // 703 bytes = 59 02 FF + 175 records: first frame (6) + 100 consecutive frames, in one burst
        val s = Script { t, line ->
            if (line.startsWith("t7E08031902FF")) t.feed("t7E8812BF5902FF000000")
            if (line.startsWith("t7E083000")) t.feed(*Array(100) { cf(it + 1) })
        }
        val conn = connect(s)
        s.feed(frame(0)); runCurrent()
        var last: ModuleScanStatus? = null
        val raw = conn.performDtcScan(listOf(pcm)) { _, st -> if (st != null) last = st }
        assertEquals(703, raw.payloads["PCM"]?.size)
        assertEquals(ModuleScanStatus.OK, last)
        assertEquals(0, linkCount("dtcFramesDropped"))
    }

    @Test fun `dtc - frames lost to a full channel leave the module unread and counted, never OK`() = runTest {
        // 2106 bytes = first frame + 300 consecutive frames. A burst of 209 hands one to the waiting scanner, fills the
        // channel (128) and loses exactly 80 — a multiple of 16, so the sequence check can't see the gap — then the tail
        // arrives once the scanner has drained: only the byte count can tell the reply is short.
        val s = Script { t, line ->
            if (line.startsWith("t7E08031902FF")) t.feed("t7E88183A5902FF000000")
            if (line.startsWith("t7E083000")) {
                t.feed(*Array(209) { cf(it + 1) })
                backgroundScope.launch { delay(50L); t.feed(*Array(91) { cf(210 + it) }) }
            }
        }
        val conn = connect(s)
        s.feed(frame(0)); runCurrent()
        var last: ModuleScanStatus? = null
        val raw = conn.performDtcScan(listOf(pcm)) { _, st -> if (st != null) last = st }
        assertNull("a reply with a hole must not complete", raw.payloads["PCM"])
        assertEquals(ModuleScanStatus.TIMEOUT, last)
        assertEquals(80, linkCount("dtcFramesDropped"))
    }

    // ── PcmRequestLedger (32) ───────────────────────────────────────────────────────────────────────────────────────

    @Test fun `ledger - 32 requests are all tracked, the 33rd evicts the oldest and leaves it unresolved`() {
        val atLimit = PcmRequestLedger()
        repeat(32) { atLimit.sent(0x0B0B, 0) }
        assertFalse(atLimit.unresolved(1))
        assertEquals("all 32 wait for one DID: attributable", 0x0B0B, atLimit.negative(1))

        val over = PcmRequestLedger()
        repeat(33) { over.sent(0x0B0B, 0) }
        assertTrue(over.unresolved(1))
        assertEquals("the newest 32 remain", 32, over.waiting(1).size)
        assertNull("an evicted request could own this NRC", over.negative(1))
    }

    @Test fun `ledger - past the limit it keeps working - replies still resolve and new requests still complete`() {
        val l = PcmRequestLedger()
        repeat(40) { l.sent(0x1000 + it, 0) }                             // 0x1000..0x1007 evicted
        assertFalse("evicted: nothing left to resolve", l.positive(0x1000))
        assertTrue(l.positive(0x1020))
        val r = l.sent(0x2000, 1)
        assertTrue(l.positive(0x2000))
        assertTrue("the poller's wait ends", r.reply.isCompleted)
        assertTrue("unresolved until the connection ends", l.unresolved(1))
        l.clear()
        assertFalse(l.unresolved(1))
    }

    @Test fun `ledger - lapsed requests are capped too, and reaching that cap never clears the uncertainty`() {
        val l = PcmRequestLedger(maxAgeMs = 10L)
        repeat(20) { l.sent(0x1000 + it, 0) }
        assertEquals(20, l.waiting(100).size)                             // lapsed, none evicted yet
        repeat(20) { l.sent(0x2000 + it, 100) }
        assertEquals("oldest lapsed dropped at 32", 32, l.waiting(200).size)
        assertTrue("a surviving lapsed request answering late is resolved", l.positive(0x2000))
        assertFalse("a dropped one is gone", l.positive(0x1000))
        assertTrue(l.unresolved(200))
        repeat(32) { l.positive(0x1008 + it); l.positive(0x2000 + it) }
        assertTrue("all answered, an evicted request may still own a reply", l.unresolved(200))
        assertNull(l.negative(200))
    }

    @Test fun `connection - a silent PCM past the ledger limit keeps the poller and decoding alive and charges NRCs to nobody`() = runTest {
        val s = Script()
        val pcmWrites = { s.writes.count { it.startsWith("t7E080322") } }
        val updates = mutableListOf<VehicleState>()
        connect(s, rpm = 1_500.0, onObd = { updates += it })
        s.feed(frame(0))
        advanceTimeBy(40_000L)                                            // 20 s initial delay + ~66 unanswered requests
        assertTrue("past the limit: ${pcmWrites()} PCM requests", pcmWrites() > PcmRequestLedger.MAX_TRACKED)
        s.feed("t7E88037F223100000000")                                   // NRC: nothing can be charged any more
        s.feed("t7E8805620 33E80000000".replace(" ", ""))                 // TIP: raw 0x8000 / 128 = 256 kPa
        runCurrent()
        assertEquals(1, DiagnosticLogger.nrcUnattributedTotal)
        assertEquals(0, DiagnosticLogger.linkIntegrity.nrcAttributed)
        assertTrue(DiagnosticLogger.sessionEvents.none { it.type == "NRC_SUPPRESS" })
        assertEquals("positive replies still decode", 256.0, updates.last().tipActualKpa, 0.0)
        val before = pcmWrites()
        advanceTimeBy(3_000L)
        assertTrue("the poller still polls", pcmWrites() > before)
    }

    // ── DiagnosticLogger ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `logger - the decode trace keeps the newest 10000, per-ID totals stay exact, the export says how many went`() {
        repeat(10_005) { DiagnosticLogger.logFrame(0x0F8, byteArrayOf(it.toByte()), VehicleState(), "d$it", null) }
        val trace = DiagnosticLogger.decodeTrace
        assertEquals(10_000, trace.size)
        assertEquals("d5", trace.first().decoded)
        assertEquals("d10004", trace.last().decoded)
        assertEquals(10_005L, DiagnosticLogger.frameInventorySnapshot.getValue(0x0F8).totalReceived)
        assertEquals(5, limit("decodeTraceDropped"))
    }

    @Test fun `logger - events keep the newest 1000 and the summary says older ones are gone`() {
        repeat(1_005) { DiagnosticLogger.event("T", "e$it") }             // + sessionStart's own event
        val events = DiagnosticLogger.sessionEvents
        assertEquals(1_000, events.size)
        assertEquals("e5", events.first().message)
        assertEquals("e1004", events.last().message)
        assertEquals(6, limit("eventsDropped"))
        assertTrue(summary(), summary().contains("6 older session event(s)"))
    }

    @Test fun `logger - the FPS timeline keeps the newest 7200 and counts the rest`() {
        repeat(7_205) { DiagnosticLogger.fps(it.toDouble()) }
        val fps = DiagnosticLogger.fpsTimeline
        assertEquals(7_200, fps.size)
        assertEquals(5.0, fps.first().fps, 0.0)
        assertEquals(7_204.0, fps.last().fps, 0.0)
        assertEquals(5, limit("fpsPointsDropped"))
    }

    @Test fun `logger - a session under every limit claims nothing was dropped`() {
        repeat(3) { DiagnosticLogger.logFrame(0x0F8, byteArrayOf(1), VehicleState(), "d", null) }
        for (k in listOf("decodeTraceDropped", "eventsDropped", "fpsPointsDropped", "probeSessionsDropped",
            "inventoryFramesNotListed", "knockSessionDropped")) assertEquals(k, 0, limit(k))
        assertEquals(false, detail().optJSONObject("logLimits")?.optBoolean("slcanCapReached", true))
        assertTrue(summary(), summary().contains("─── LOG LIMITS"))
        assertTrue(summary(), summary().contains("no limit reached"))
    }

    @Test fun `logger - counts reset with the session`() {
        repeat(1_005) { DiagnosticLogger.event("T", "e$it") }
        DiagnosticLogger.sessionStart("next", 0, prefs = null)
        assertEquals(0, limit("eventsDropped"))
    }

    private fun staticField(name: String) = DiagnosticLogger::class.java.getDeclaredField(name).apply { isAccessible = true }

    @Test fun `logger - periodic samples keep the newest 10 per ID, the first bytes stay`() {
        // Two seams: the session began an hour ago, and the per-ID interval is cleared between frames (no 5-minute test)
        staticField("sessionStartMs").setLong(null, System.currentTimeMillis() - 3_600_000L)
        val lastSample = staticField("lastSampleTimeMs").get(null) as HashMap<*, *>
        repeat(15) {
            lastSample.clear()
            DiagnosticLogger.logUnknownFrame(0x123, byteArrayOf(it.toByte()))
        }
        val info = DiagnosticLogger.frameInventorySnapshot.getValue(0x123)
        assertEquals(listOf("05", "06", "07", "08", "09", "0A", "0B", "0C", "0D", "0E"), info.periodicSamples.map { it.rawHex })
        assertEquals("00", info.firstRawHex)
        assertEquals(15L, info.totalReceived)
    }

    @Test fun `logger - unique IDs in the inventory are bounded above every standard ID, and frames past it are counted`() {
        val dir = tmp.newFolder()
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = dir)
        for (id in 0..0x7FF) DiagnosticLogger.logUnknownFrame(id, byteArrayOf(1))
        for (i in 0 until 5_000) DiagnosticLogger.logUnknownFrame(0x10000000 + i, byteArrayOf(1))
        val inv = DiagnosticLogger.frameInventorySnapshot
        assertTrue("${inv.size} unique IDs held", inv.size <= 4_096)
        assertTrue("every standard ID has its row", (0..0x7FF).all { it in inv })
        DiagnosticLogger.logUnknownFrame(0x7FF, byteArrayOf(2))
        assertEquals("a listed ID keeps counting", 2L, DiagnosticLogger.frameInventorySnapshot.getValue(0x7FF).totalReceived)
        assertEquals(2_048 + 5_000 - 4_096, limit("inventoryFramesNotListed"))
        assertEquals("the raw log still has every frame", 2_048L + 5_000 + 1, DiagnosticLogger.slcanLineCount)
    }

    @Test fun `logger - the SLCAN log stops at its cap, says so once, and the export says it stopped`() {
        val dir = tmp.newFolder()
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = dir)
        staticField("slcanLinesWritten").setLong(null, 2_000_000L - 2)    // the cap, without writing 2 M lines
        repeat(5) { DiagnosticLogger.logObdFrame(0x7E8, byteArrayOf(it.toByte())) }
        DiagnosticLogger.logTxLine("t7E08031902FF00000000\r")
        DiagnosticLogger.logUnknownFrame(0x123, byteArrayOf(9))
        DiagnosticLogger.flushSlcan()
        val text = File(dir, "session_slcan.log").readLines()
        assertEquals("exactly up to the cap", 2, text.count { it.startsWith("(") })
        assertEquals(1, text.count { it.startsWith("# LOG CAP REACHED") })
        assertEquals(2_000_000L, DiagnosticLogger.slcanLineCount)
        assertEquals(1, DiagnosticLogger.sessionEvents.count { it.type == "SLCAN" })
        assertEquals("the inventory keeps counting past the cap", 1L, DiagnosticLogger.frameInventorySnapshot.getValue(0x123).totalReceived)
        assertEquals(true, detail().optJSONObject("logLimits")?.optBoolean("slcanCapReached", false))
        assertTrue(summary(), summary().contains("SLCAN log cap reached"))
    }

    @Test fun `logger - the SLCAN cap's own event can't push the event list past its limit`() {
        val dir = tmp.newFolder()
        DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = dir)
        repeat(1_000) { DiagnosticLogger.event("T", "e$it") }
        staticField("slcanLinesWritten").setLong(null, 2_000_000L)
        DiagnosticLogger.logObdFrame(0x7E8, byteArrayOf(1))
        assertEquals(1_000, DiagnosticLogger.sessionEvents.size)
        assertEquals("SLCAN", DiagnosticLogger.sessionEvents.last().type)
    }

    // ── DID probe sessions ──────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `probe - sessions keep the newest 50, each complete, and the export says how many went`() = runTest {
        repeat(53) { n ->
            val end = runDidProbeScan("M$n", 0x7E0, 0x7E8, listOf(1, 2), { _, _, _ -> null }, { false }) { }
            assertEquals(DidProbeEnd.Complete, end)
        }
        val sessions = DiagnosticLogger.probeSessions
        assertEquals(50, sessions.size)
        assertEquals("M3", sessions.first().module)
        assertEquals("M52", sessions.last().module)
        assertTrue(sessions.all { it.results.size == 2 && it.planned == 2 })
        assertEquals(50, detail().getJSONArray("probeResults").length())
        assertEquals(3, limit("probeSessionsDropped"))
    }

    @Test fun `probe - the largest generated scan is kept whole, and a stopped one says how far it got`() = runTest {
        // generateCandidateDids (PCM): 0x0100..0x0FFF + 0xF400..0xF4FF, the biggest range list
        val dids = (0x0100..0x0FFF).toList() + (0xF400..0xF4FF).toList()
        runDidProbeScan("PCM", 0x7E0, 0x7E8, dids, { _, _, _ -> null }, { false }) { }
        val whole = DiagnosticLogger.probeSessions.last()
        assertEquals(dids.size, whole.results.size)
        assertEquals(dids.size + 1, probeCsv(whole).lines().count { it.isNotEmpty() })
        var left = 1_000
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, dids, { _, _, _ -> left--; null }, { left <= 0 }) { }
        assertEquals(DidProbeEnd.Stopped, end)
        val stopped = DiagnosticLogger.probeSessions.last()
        assertEquals(DiagnosticLogger.PROBE_STOPPED, stopped.outcome)
        assertEquals(dids.size, stopped.planned)
        assertTrue(probeCsv(stopped).startsWith("# outcome=STOPPED probed=${stopped.results.size} planned=${dids.size}"))
    }

    // ── KnockSessionLog (2000) ──────────────────────────────────────────────────────────────────────────────────────

    private fun knock(kr: Double, stamp: Long, rpm: Double = 800.0, throttle: Double = 0.0) = VehicleState(
        rpm = rpm, throttlePct = throttle, throttleHasSource = true, ignCorrCyl1 = kr,
        fieldLastUpdateMs = mapOf(KnockCapture.KR_TAGS[0] to stamp))

    @Test fun `knock - past 2000 the newest stay, the CSV holds them, the export says how many went, capture continues`() {
        repeat(KnockSessionLog.MAX_EVENTS + 7) { i -> val t = 51_000L + i; KnockSessionLog.onState(knock(1.0, t), t) }
        val events = KnockSessionLog.events.value
        assertEquals(KnockSessionLog.MAX_EVENTS, events.size)
        assertEquals(51_007L, events.first().timestamp)
        val rows = DriveExportBuilder.buildKnockCsv(events).lines().count { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("timestamp") }
        assertEquals(KnockSessionLog.MAX_EVENTS, rows)
        assertEquals(7, limit("knockSessionDropped"))

        val t = 51_000L + KnockSessionLog.MAX_EVENTS + 7                  // still capturing, pulls still counted past the cap
        var pull = t
        while (pull <= t + 3_000) { KnockSessionLog.onState(knock(0.0, pull, rpm = 5_000.0, throttle = 100.0), pull); pull += 1_000 }
        KnockSessionLog.onState(knock(1.5, pull, rpm = 5_000.0, throttle = 100.0), pull)
        assertEquals(1.5, KnockSessionLog.events.value.last().krDeg, 0.0)
        assertEquals(1, KnockSessionLog.pulls.value.knocked)

        KnockSessionLog.reset(pull + 1)
        assertEquals(0, limit("knockSessionDropped"))
    }

    // ── BoostSessionLog (90 s window) ───────────────────────────────────────────────────────────────────────────────

    private fun tip(stamp: Long) = VehicleState(rpm = 4_500.0, tipActualKpa = 200.0, fieldLastUpdateMs = mapOf("tipActualKpa" to stamp))

    @Test fun `boost - the window is exact at 90 s - one ms inside stays, exactly 90 s old goes`() {
        BoostSessionLog.onState(tip(101_000), 101_000)
        BoostSessionLog.onState(tip(102_000), 102_000)
        BoostSessionLog.onState(tip(191_000), 191_000)                    // 90 000 and 89 000 old
        assertEquals(listOf(102_000L, 191_000L), BoostSessionLog.samples.value.map { it.tMs })
    }

    @Test fun `boost - a sustained feed stays inside the window, never its whole history`() {
        var t = 101_000L
        repeat(10_000) { BoostSessionLog.onState(tip(t), t); t += 100 }
        val s = BoostSessionLog.samples.value
        assertEquals((BoostSessionLog.WINDOW_MS / 100).toInt(), s.size)
        assertTrue(s.first().tMs > s.last().tMs - BoostSessionLog.WINDOW_MS)
    }

    @Test fun `boost - when TIP stops, the chart's last 90 s still ends - held samples age out on the next merge`() {
        BoostSessionLog.onState(tip(101_000), 101_000)
        BoostSessionLog.onState(tip(101_000), 150_000)                    // merges of other DIDs: same TIP stamp
        assertEquals(1, BoostSessionLog.samples.value.size)
        BoostSessionLog.onState(tip(101_000), 191_000)                    // exactly 90 s after the sample
        assertTrue("the chart says last 90 s, it held ${BoostSessionLog.samples.value.size} older sample(s)",
            BoostSessionLog.samples.value.isEmpty())
    }

    // ── CrashTelemetryBuffer (300) ──────────────────────────────────────────────────────────────────────────────────

    @Test fun `crash - the ring keeps the newest 300 in order and still accepts more`() {
        // The object outlives tests: 305 pushes overwrite whatever an earlier test left
        val file = tmp.newFile()
        repeat(305) { CrashTelemetryBuffer.push(VehicleState(rpm = it.toDouble())) }
        CrashTelemetryBuffer.flushToFile(file, RuntimeException("t"), "main", "test")
        fun rpms(f: File) = JSONObject(f.readText()).let { o ->
            assertEquals(o.getInt("snapshotCount"), o.getJSONArray("snapshots").length())
            val a = o.getJSONArray("snapshots")
            (0 until a.length()).map { a.getJSONObject(it).getDouble("rpm").toInt() }
        }
        assertEquals((5 until 305).toList(), rpms(file))
        CrashTelemetryBuffer.push(VehicleState(rpm = 305.0))
        CrashTelemetryBuffer.flushToFile(file, RuntimeException("t"), "main", "test")
        assertEquals((6..305).toList(), rpms(file))
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
}
