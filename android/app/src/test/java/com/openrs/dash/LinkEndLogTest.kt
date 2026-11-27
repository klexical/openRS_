package com.openrs.dash

import com.openrs.dash.can.BleAdapterProfile
import com.openrs.dash.can.BleGattCore
import com.openrs.dash.can.GattLink
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.UUID

/**
 * #374 — when the frame loop ends by itself, the session log gets one LINK_END line with the reason and the silence
 * before it, through the production [SlcanConnection] on virtual time. SYNTHETIC traffic: one broadcast frame every
 * 20 ms until the bus goes quiet, then the read ends the way each transport ends it. A stop by the user (abort, then
 * cancel, as `CanDataService.teardownJob` does) writes no LINK_END: its LINK_STOP line is the service's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LinkEndLogTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun linkEnds() = DiagnosticLogger.sessionEvents.filter { it.type == "LINK_END" }.map { it.message }

    /**
     * Frames every 20 ms over [0, quietAtMs), then nothing; at [endAtMs] the read ends: with [cause] thrown, or EOF
     * (`readLine()` returns null) when [cause] is null.
     */
    private class EndingTransport(
        private val scope: CoroutineScope, private val quietAtMs: Long, private val endAtMs: Long,
        private val cause: Throwable?, override val kind: String,
    ) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override suspend fun open() {
            scope.launch {
                var t = 0L
                while (t < quietAtMs && lines.trySend("t09080000C1FF617D0000").isSuccess) { delay(20); t += 20 }
            }
            scope.launch { delay(endAtMs); lines.close(cause) }
        }
        override suspend fun readLine(): String? =
            try { lines.receive() } catch (_: ClosedReceiveChannelException) { null }
        override suspend fun writeLine(frame: String) = Unit
        override fun close() { lines.close() }
        override val label = "fake"
        override val stockFirmwareLabel = "fake"
    }

    private fun TestScope.connection(transport: () -> SlcanTransport) = SlcanConnection(
        transportFactory = transport, maxRetries = 1,
        ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {},
        clock = { testScheduler.currentTime }, monotonicClock = { testScheduler.currentTime },
        gapLog = { _, _, _ -> },
    )

    private fun TestScope.run(quietAtMs: Long, endAtMs: Long, cause: Throwable?, kind: String = "Wi-Fi TCP") {
        val conn = connection { EndingTransport(backgroundScope, quietAtMs, endAtMs, cause, kind) }
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(endAtMs + 1_000)
    }

    @Test fun `a read that times out after the bus went quiet names the timeout and the silence`() = runTest {
        run(quietAtMs = 30_000, endAtMs = 50_000, cause = SocketTimeoutException("Read timed out"))
        val line = linkEnds().single()
        assertEquals("read timed out with no frame; 20.0 s with no line from the adapter before it (Wi-Fi TCP)", line)
    }

    @Test fun `an end of stream reads as the adapter closing the connection`() = runTest {
        run(quietAtMs = 30_000, endAtMs = 32_500, cause = null)
        assertEquals("the adapter closed the connection; 2.5 s with no line from the adapter before it (Wi-Fi TCP)",
            linkEnds().single())
    }

    @Test fun `a read error carries the exception's class name only, never its text`() = runTest {
        run(quietAtMs = 30_000, endAtMs = 30_000, cause = IOException("Connection reset by 192.168.0.10 WiC_ABCDEF"),
            kind = "Wi-Fi WebSocket")
        val line = linkEnds().single()
        assertTrue(line, line.startsWith("read error (IOException); "))
        assertTrue(line, line.endsWith(" (Wi-Fi WebSocket)"))
        assertFalse(line, line.contains("192.168") || line.contains("WiC_") || line.contains("reset"))
    }

    @Test fun `a stop by the user writes no LINK_END, also when the read ends before the cancel arrives`() = runTest {
        val conn = connection { EndingTransport(backgroundScope, quietAtMs = 60_000, endAtMs = 120_000, cause = null, kind = "Wi-Fi TCP") }
        val job = backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(10_000)
        conn.abort()                    // teardownJob's order: abort (closes the transport), then cancel
        runCurrent()                    // the frame loop sees the end of stream before the cancel lands
        job.cancel()
        advanceTimeBy(5_000)
        assertEquals(emptyList<String>(), linkEnds())
    }

    // ── Bluetooth: the real BleGattCore under the connection, as BleSlcanTransport forwards GATT callbacks to it ──────

    private val pro = BleAdapterProfile.WICAN_PRO

    /** A GATT that answers every setup step at once and acknowledges every write. */
    private class ReadyGatt(val core: BleGattCore, val scope: CoroutineScope) : GattLink {
        var closed = false
        override fun discoverServices() = true
        override fun setNotify(service: UUID, characteristic: UUID, enable: Boolean) = true
        override fun writeCccd(service: UUID, characteristic: UUID, value: ByteArray): Boolean {
            scope.launch { delay(10); core.onDescriptorWrite(true, 0) }; return true
        }
        override fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean {
            scope.launch { delay(5); core.onWrite(0) }; return true
        }
        override fun requestMtu(mtu: Int): Boolean { scope.launch { delay(10); core.onMtuChanged(mtu, 0) }; return true }
        override fun requestHighConnectionPriority() = true
        override fun bondState() = BleGattCore.BOND_BONDED
        override fun createBond() = true
        override fun disconnect() = Unit
        override fun close() { closed = true }
    }

    @Test fun `a Bluetooth disconnect names its GATT status and the silence`() = runTest {
        val core = BleGattCore("MEATPI_PRO")
        val gatt = ReadyGatt(core, backgroundScope)
        val transport = object : SlcanTransport by bleCore(core, gatt, quietAtMs = 30_000) {}
        val conn = connection { transport }
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(34_000)
        core.onDisconnected(8)                                          // the link-loss callback, 4 s into the silence
        advanceTimeBy(1_000)
        val line = linkEnds().single()
        assertTrue(line, line.startsWith("Bluetooth disconnected (GATT 8); "))
        assertTrue(line, line.endsWith(" s with no line from the adapter before it (Bluetooth)"))
        val silence = line.substringAfter("; ").substringBefore(" s ").toDouble()
        assertTrue(line, silence in 3.9..4.1)
    }

    @Test fun `a Bluetooth link the app closed after an unacknowledged write is not called the adapter's close`() = runTest {
        val core = BleGattCore("MEATPI_PRO", writeTimeoutMs = 2_000L)
        val gatt = object : GattLink by ReadyGatt(core, backgroundScope) {
            // From 31 s no write is acknowledged: the core closes the link itself (#218), the reader sees EOF
            override fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean {
                if (testScheduler.currentTime < 31_000) backgroundScope.launch { delay(5); core.onWrite(0) }
                return true
            }
        }
        val conn = connection { bleCore(core, gatt, quietAtMs = 30_000) }
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(120_000)                                          // the pollers' next write after 31 s (engine off: slow sweep)
        val line = linkEnds().single()
        assertTrue(line, line.startsWith("Bluetooth link closed after an unacknowledged write; "))
    }

    /** What BleSlcanTransport is, with [gatt] in place of Android's: notifications every 20 ms over [0, quietAtMs). */
    private fun TestScope.bleCore(core: BleGattCore, gatt: GattLink, quietAtMs: Long) = object : SlcanTransport {
        override suspend fun open() {
            backgroundScope.launch {
                delay(20); core.onConnected(gatt); delay(20)
                core.onServicesDiscovered(0, mapOf(pro.service to mapOf(
                    pro.write to (BleGattCore.PROPERTY_WRITE or BleGattCore.PROPERTY_WRITE_NO_RESPONSE),
                    pro.notify to BleGattCore.PROPERTY_NOTIFY)))
            }
            core.setup()
            backgroundScope.launch {
                while (testScheduler.currentTime < quietAtMs) { core.onNotify(pro.notify, "t09080000C1FF617D0000\r".toByteArray()); delay(20) }
            }
        }
        override suspend fun readLine() = core.readLine()
        override suspend fun writeLine(frame: String) = core.writeLine(frame)
        override suspend fun write(frame: String) = core.write(frame)
        override fun close() = core.close()
        override val readEnd: String? get() = core.readEnd
        override val kind = "Bluetooth"
        override val label = "BLE fake"
        override val stockFirmwareLabel = "fake"
    }
}
