package com.openrs.dash

import com.openrs.dash.can.BleAdapterProfile
import com.openrs.dash.can.BleGattCore
import com.openrs.dash.can.BleSetupPolicy
import com.openrs.dash.can.GattLink
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.isActive
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.util.UUID

/**
 * #218 — the BLE transport's callback sequence ([BleGattCore], what [com.openrs.dash.can.BleSlcanTransport] forwards
 * every BluetoothGatt callback to) against a scripted fake GATT on the JVM: bonding, descriptor completion, failed and
 * missing write callbacks, cancellation, reconnect and characteristic properties. JVM fake only — no adapter, no
 * Android Bluetooth stack (hardware acceptance pending).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleTransportTest {
    private val pro = BleAdapterProfile.WICAN_PRO
    private val W = BleGattCore.PROPERTY_WRITE; private val WNR = BleGattCore.PROPERTY_WRITE_NO_RESPONSE
    private val N = BleGattCore.PROPERTY_NOTIFY; private val I = BleGattCore.PROPERTY_INDICATE

    /** Scripted GATT: what each call returns and which callbacks it fires (through [core]). */
    private inner class FakeGatt(val core: BleGattCore, val scope: CoroutineScope) : GattLink {
        var subscribeStatuses = ArrayDeque(listOf(BleSetupPolicy.GATT_SUCCESS))
        var answerCccd = true
        var bond = BleGattCore.BOND_NONE
        var bondResult = BleGattCore.BOND_BONDED
        var writeCallback: Int? = BleSetupPolicy.GATT_SUCCESS          // null = the callback never comes
        /** Delay of the write callback per write (0-based index); default 10 ms. */
        var callbackDelay: (Int) -> Long = { 10L }
        val cccdWrites = mutableListOf<ByteArray>(); val writes = mutableListOf<Pair<ByteArray, Int>>()
        var bondRequests = 0; var closed = false
        override fun discoverServices() = true
        override fun setNotify(service: UUID, characteristic: UUID, enable: Boolean) = true
        override fun writeCccd(service: UUID, characteristic: UUID, value: ByteArray): Boolean {
            cccdWrites += value
            if (answerCccd) scope.launch { delay(30); core.onDescriptorWrite(true, subscribeStatuses.removeFirstOrNull() ?: 0) }
            return true
        }
        override fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean {
            val index = writes.size
            writes += value to writeType
            writeCallback?.let { st -> scope.launch { delay(callbackDelay(index)); core.onWrite(st) } }
            return true
        }
        override fun requestMtu(mtu: Int): Boolean { scope.launch { delay(10); core.onMtuChanged(mtu, 0) }; return true }
        override fun bondState() = bond
        override fun createBond(): Boolean {
            bondRequests++; bond = BleGattCore.BOND_BONDING
            scope.launch { delay(2_000); bond = bondResult }               // the user accepts (or refuses) the PIN
            return true
        }
        override fun disconnect() = Unit
        override fun close() { closed = true }
    }

    private fun services(write: Int = W or WNR, notify: Int = N) =
        mapOf(pro.service to mapOf(pro.write to write, pro.notify to notify))

    /** Connect + discovery callbacks as Android delivers them, then the transport's setup. */
    private suspend fun TestScope.connect(core: BleGattCore, gatt: FakeGatt, svc: Map<UUID, Map<UUID, Int>> = services()) {
        launch { delay(50); core.onConnected(gatt); delay(50); core.onServicesDiscovered(0, svc) }
        core.setup()
    }

    @Test fun `unbonded adapter — auth failure on subscribe pairs once, then subscribes`() = runTest {
        val core = BleGattCore("MEATPI_PRO"); val g = FakeGatt(core, this)
        g.subscribeStatuses = ArrayDeque(listOf(BleSetupPolicy.GATT_INSUFFICIENT_AUTHENTICATION, BleSetupPolicy.GATT_SUCCESS))
        connect(core, g)
        assertEquals(1, g.bondRequests)
        assertEquals(2, g.cccdWrites.size)
        assertEquals(247, core.mtu)
    }

    @Test fun `pairing refused fails setup with a specific message`() = runTest {
        val core = BleGattCore("MEATPI_PRO"); val g = FakeGatt(core, this)
        g.subscribeStatuses = ArrayDeque(listOf(BleSetupPolicy.GATT_INSUFFICIENT_ENCRYPTION))
        g.bondResult = BleGattCore.BOND_NONE
        val e = runCatching { connect(core, g) }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message!!.contains("Pairing was refused"))
    }

    @Test fun `descriptor write callback never comes — bounded, named failure`() = runTest {
        val core = BleGattCore("MEATPI_PRO", setupTimeoutMs = 5_000L); val g = FakeGatt(core, this)
        g.answerCccd = false
        val e = runCatching { connect(core, g) }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message!!.contains("subscription timed out"))
    }

    @Test fun `failed write callback surfaces as an error`() = runTest {
        val core = BleGattCore("MEATPI_PRO"); val g = FakeGatt(core, this)
        connect(core, g)
        g.writeCallback = BleSetupPolicy.GATT_INSUFFICIENT_AUTHENTICATION
        val e = runCatching { core.writeLine("C\r") }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message!!.contains("not paired"))
    }

    @Test fun `missing write callback — the write fails in bounded time and the link is not reused`() = runTest {
        val core = BleGattCore("MEATPI_PRO", writeTimeoutMs = 2_000L); val g = FakeGatt(core, this)
        connect(core, g)
        g.writeCallback = null
        val start = testScheduler.currentTime
        val e = runCatching { core.writeLine("S6\r") }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message!!.contains("not acknowledged"))
        assertEquals(2_000L, testScheduler.currentTime - start)
        // Android's write callback carries no request id: after a timeout the next callback can't be told apart, so
        // the link is closed rather than reused (SlcanConnection reconnects on the EOF)
        assertTrue(g.closed)
        assertEquals(null, core.readLine())
        g.writeCallback = 0
        assertTrue(runCatching { core.writeLine("O\r") }.exceptionOrNull() is IOException)
        assertEquals(1, g.writes.size)                                  // nothing more went to the old link
    }

    @Test fun `a late callback for a timed-out write never acknowledges the next write`() = runTest {
        // Write A gets no callback within 2 s; write B starts at once; A's callback arrives 500 ms later and B's own
        // never does. Before: onWrite completed whatever write was current, so B "succeeded" on A's acknowledgement.
        val core = BleGattCore("MEATPI_PRO", writeTimeoutMs = 2_000L); val g = FakeGatt(core, this)
        connect(core, g)
        g.writeCallback = 0
        g.callbackDelay = { i -> if (i == 0) 2_500L else 60_000L }
        val a = runCatching { core.writeLine("t7E0803220466000000000\r") }.exceptionOrNull()
        assertTrue("$a", a is IOException && a.message!!.contains("not acknowledged"))
        val b = async { runCatching { core.writeLine("t7E0803220462000000000\r") }.exceptionOrNull() }
        advanceTimeBy(1_000L)                                           // A's late callback has fired by now
        assertTrue("B must not complete on A's acknowledgement", b.isCompleted)
        assertTrue("${b.await()}", b.await() is IOException)
    }

    @Test fun `production path — a write timeout ends the connection and the next attempt uses a new link`() = runTest {
        // SlcanConnection over the same core BleSlcanTransport uses: the first write after 8 s of session time is
        // acknowledged 2.5 s late (after its 2 s timeout); SlcanConnection's pollers swallow write errors and keep
        // writing. Before: the link stayed up and every later write was acknowledged by the previous write's callback.
        val links = mutableListOf<FakeGatt>()
        var stalledIndex = -1
        val factory = {
            object : SlcanTransport {
                val core = BleGattCore("MEATPI_PRO", writeTimeoutMs = 2_000L)
                val g = FakeGatt(core, backgroundScope).also { gatt ->
                    val first = links.isEmpty(); links += gatt
                    if (first) gatt.callbackDelay = { i ->
                        if (stalledIndex < 0 && testScheduler.currentTime >= 8_000L) { stalledIndex = i; 2_500L } else 10L
                    }
                }
                override suspend fun open() {
                    backgroundScope.launch { delay(50); core.onConnected(g); delay(50); core.onServicesDiscovered(0, services()) }
                    core.setup()
                    backgroundScope.launch {                            // CAN traffic while the link is open
                        while (isActive && !g.closed) { core.onNotify(pro.notify, "t09080000C1FF617D0000\r".toByteArray()); delay(20) }
                    }
                }
                override suspend fun readLine() = core.readLine()
                override suspend fun writeLine(frame: String) = core.writeLine(frame)
                override fun close() = core.close()
                override val label = "BLE fake"
                override val stockFirmwareLabel = "fake"
            }
        }
        val conn = SlcanConnection(
            transportFactory = factory, maxRetries = 3, rpmProvider = { 1_500.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(20_000L)
        assertTrue("a write stalled on the first link", stalledIndex >= 0)
        assertEquals("nothing written to the first link after the unacknowledged write", stalledIndex + 1, links[0].writes.size)
        assertTrue("the first link was closed", links[0].closed)
        assertTrue("SlcanConnection reconnected with a new link (links: ${links.size})", links.size >= 2)
    }

    @Test fun `write type follows the characteristic — without response when that is all it supports`() = runTest {
        val core = BleGattCore("MEATPI_PRO"); val g = FakeGatt(core, this)
        connect(core, g, services(write = WNR))
        core.writeLine("O\r")
        assertEquals(BleGattCore.WRITE_TYPE_NO_RESPONSE, g.writes.single().second)
        val core2 = BleGattCore("MEATPI_PRO"); val g2 = FakeGatt(core2, this)
        connect(core2, g2, services(write = W or WNR))
        core2.writeLine("O\r")
        assertEquals(BleGattCore.WRITE_TYPE_DEFAULT, g2.writes.single().second)
    }

    @Test fun `indicate-only characteristic subscribes with the indication value, unusable ones fail`() = runTest {
        val core = BleGattCore("MEATPI_PRO"); val g = FakeGatt(core, this)
        connect(core, g, services(notify = I))
        assertArrayEquals(BleGattCore.ENABLE_INDICATION, g.cccdWrites.single())
        val core2 = BleGattCore("MEATPI_PRO"); val g2 = FakeGatt(core2, this)
        val e = runCatching { connect(core2, g2, services(write = N)) }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message!!.contains("isn't writable"))
    }

    @Test fun `cancel during setup ends it at once`() = runTest {
        val core = BleGattCore("MEATPI_PRO"); val g = FakeGatt(core, this)
        g.answerCccd = false
        val job = async { runCatching { connect(core, g) }.exceptionOrNull() }
        advanceTimeBy(500)
        core.close()
        val e = job.await()
        assertTrue("$e", e is IOException && e.message!!.contains("closed"))
        assertTrue(g.closed)
    }

    @Test fun `disconnect mid-session ends reads (EOF) and pending writes`() = runTest {
        val core = BleGattCore("MEATPI_PRO"); val g = FakeGatt(core, this)
        connect(core, g)
        core.onNotify(pro.notify, "t0908".toByteArray()); core.onNotify(pro.notify, "0000C1FF617D0000\rt0F8".toByteArray())
        assertEquals("t09080000C1FF617D0000", core.readLine())
        g.writeCallback = null
        val w = async { runCatching { core.writeLine("C\r") }.exceptionOrNull() }
        advanceTimeBy(100)
        core.onDisconnected(8)
        assertTrue(w.await() is IOException)
        assertEquals(null, core.readLine())                             // SlcanConnection retries with a new transport
    }

    @Test fun `no supported layout names what was found`() = runTest {
        val core = BleGattCore("MEATPI_PRO"); val g = FakeGatt(core, this)
        val other = UUID.fromString("0000180A-0000-1000-8000-00805F9B34FB")
        val e = runCatching { connect(core, g, mapOf(other to mapOf(other to N))) }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message!!.contains("180A"))
        if (e == null) fail()
    }
}
