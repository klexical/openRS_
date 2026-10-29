package com.openrs.dash

import com.openrs.dash.can.BleAdapterProfile
import com.openrs.dash.can.BleGattCore
import com.openrs.dash.can.BleSetupPolicy
import com.openrs.dash.can.GattLink
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
    private inner class FakeGatt(val core: BleGattCore, val scope: TestScope) : GattLink {
        var subscribeStatuses = ArrayDeque(listOf(BleSetupPolicy.GATT_SUCCESS))
        var answerCccd = true
        var bond = BleGattCore.BOND_NONE
        var bondResult = BleGattCore.BOND_BONDED
        var writeCallback: Int? = BleSetupPolicy.GATT_SUCCESS          // null = the callback never comes
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
            writes += value to writeType
            writeCallback?.let { st -> scope.launch { delay(10); core.onWrite(st) } }
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

    @Test fun `missing write callback — the write fails in bounded time and the next write still works`() = runTest {
        val core = BleGattCore("MEATPI_PRO", writeTimeoutMs = 2_000L); val g = FakeGatt(core, this)
        connect(core, g)
        g.writeCallback = null
        val start = testScheduler.currentTime
        val e = runCatching { core.writeLine("S6\r") }.exceptionOrNull()
        assertTrue("$e", e is IOException && e.message!!.contains("not acknowledged"))
        assertEquals(2_000L, testScheduler.currentTime - start)
        g.writeCallback = 0
        core.writeLine("O\r")                                           // the write lock was released
        assertEquals(2, g.writes.size)
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
