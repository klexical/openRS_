package com.openrs.dash

import com.openrs.dash.can.BleAdapterProfile
import com.openrs.dash.can.BleGattCore
import com.openrs.dash.can.BleMtuTrace
import com.openrs.dash.can.BleSetupPolicy
import com.openrs.dash.can.GattLink
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.UUID

/**
 * #253, "BLE capture context": the diagnostic export says what the app asked for as ATT MTU, what the request call
 * returned, what the callback said and what the writes really use, as separate facts tied to the current connection
 * attempt. Read from the two export files the real builder writes, after the real [BleGattCore] setup (fake GATT, JVM,
 * virtual time) and, for the attempt cases, the real [SlcanConnection] retry loop. Synthetic data only.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class BleMtuContextExportTest {
    private val pro = BleAdapterProfile.WICAN_PRO

    @Before fun session() { DiagnosticLogger.sessionStart("Bluetooth", 0, prefs = null, transport = "Bluetooth (MeatPi Pro)") }

    /** [mtuAnswer]: null = the phone refuses the request; else (delay ms or null for never, mtu, status). */
    private class Mtu(val refuse: Boolean = false, val afterMs: Long? = 10, val mtu: Int = 185, val status: Int = 0)

    private class Gatt(val core: BleGattCore, val scope: CoroutineScope, val answer: Mtu) : GattLink {
        val writes = mutableListOf<String>()
        var closed = false
        override fun discoverServices() = true
        override fun setNotify(service: UUID, characteristic: UUID, enable: Boolean) = true
        override fun writeCccd(service: UUID, characteristic: UUID, value: ByteArray): Boolean {
            scope.launch { delay(30); core.onDescriptorWrite(true, BleSetupPolicy.GATT_SUCCESS) }; return true
        }
        override fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean {
            writes += String(value, Charsets.ISO_8859_1)
            scope.launch { delay(10); core.onWrite(BleSetupPolicy.GATT_SUCCESS) }; return true
        }
        override fun requestMtu(mtu: Int): Boolean {
            if (answer.refuse) return false
            answer.afterMs?.let { scope.launch { delay(it); core.onMtuChanged(answer.mtu, answer.status) } }
            return true
        }
        override fun bondState() = BleGattCore.BOND_BONDED
        override fun createBond() = true
        override fun disconnect() = Unit
        override fun close() { closed = true }
    }

    private fun services() = mapOf(pro.service to mapOf(
        pro.write to (BleGattCore.PROPERTY_WRITE or BleGattCore.PROPERTY_WRITE_NO_RESPONSE),
        pro.notify to BleGattCore.PROPERTY_NOTIFY))

    /** One attempt the way the connection starts it: the attempt is announced, then the core is set up. */
    private suspend fun TestScope.attempt(answer: Mtu): Pair<BleGattCore, Gatt> {
        val core = BleGattCore("MEATPI_PRO", writeTimeoutMs = 2_000L)
        val g = Gatt(core, backgroundScope, answer)
        DiagnosticLogger.connectionAttempt(core.mtuTrace)
        core.onConnected(g)
        core.onServicesDiscovered(0, services())
        core.setup()
        return core to g
    }

    private fun export(): Pair<String?, JSONObject?> {
        val (text, detail) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
        val line = text.lineSequence().firstOrNull { it.trimStart().startsWith("BLE MTU") }?.substringAfter(": ")
        return line to JSONObject(detail).optJSONObject("bleMtu")
    }

    private fun JSONObject.intOrNull(k: String): Int? = if (isNull(k)) null else getInt(k)

    @Test fun `negotiated - requested 247, callback GATT 0 with 185, writes use 185`() = runTest {
        val (core, _) = attempt(Mtu(mtu = 185))
        val (line, j) = export()
        assertEquals("attempt 1 · requested 247 · callback GATT 0, MTU 185 · in use 185, writes up to 182 bytes", line)
        j!!
        assertEquals(listOf(1, 247, 0, 185, 185, 182),
            listOf("attempt", "requestedMtu", "callbackStatus", "callbackMtu", "usedMtu", "usedPayloadBytes").map { j.getInt(it) })
        assertEquals(true, j.getBoolean("requestStarted"))
        assertFalse(j.getBoolean("usedIsFallback")); assertFalse(j.getBoolean("waitTimedOut"))
        assertEquals("negotiated", j.getString("outcome"))
        assertEquals("the exported size is the one the writes use", core.mtu, j.getInt("usedMtu"))
    }

    @Test fun `the phone refuses the request - no callback is claimed and 23 is named a fallback`() = runTest {
        val (core, _) = attempt(Mtu(refuse = true))
        val (line, j) = export()
        assertEquals("attempt 1 · requested 247, refused by the phone · no callback · in use 23 (fallback), writes up to 20 bytes", line)
        j!!
        assertEquals(247, j.getInt("requestedMtu")); assertEquals(false, j.getBoolean("requestStarted"))
        assertEquals(null, j.intOrNull("callbackStatus")); assertEquals(null, j.intOrNull("callbackMtu"))
        assertEquals(23, j.getInt("usedMtu")); assertTrue(j.getBoolean("usedIsFallback"))
        assertEquals("request_refused", j.getString("outcome"))
        assertEquals(23, core.mtu)
    }

    @Test fun `the callback reports a failure - its status and number are kept apart from the 23 in use`() = runTest {
        attempt(Mtu(mtu = 185, status = 133))
        val (line, j) = export()
        assertEquals("attempt 1 · requested 247 · callback GATT 133, MTU 185 (not applied) · in use 23 (fallback), writes up to 20 bytes", line)
        j!!
        assertEquals(133, j.getInt("callbackStatus")); assertEquals(185, j.getInt("callbackMtu"))
        assertEquals(23, j.getInt("usedMtu")); assertTrue(j.getBoolean("usedIsFallback"))
        assertEquals("callback_failed", j.getString("outcome"))
    }

    @Test fun `no callback in 5 s is a fallback - a late callback is then reported with the size it changed`() = runTest {
        val (core, g) = attempt(Mtu(afterMs = null))
        val (line, j) = export()
        assertEquals("attempt 1 · requested 247 · no callback within 5 s · in use 23 (fallback), writes up to 20 bytes", line)
        assertEquals("no_callback", j!!.getString("outcome")); assertTrue(j.getBoolean("waitTimedOut"))

        core.onMtuChanged(100, BleSetupPolicy.GATT_SUCCESS)             // the stack answers after the setup stopped waiting
        val (late, lj) = export()
        assertEquals("attempt 1 · requested 247 · callback GATT 0, MTU 100 (after the 5 s wait) · in use 100, writes up to 97 bytes", late)
        assertEquals(100, lj!!.getInt("usedMtu")); assertFalse(lj.getBoolean("usedIsFallback"))
        core.writeLine("t7E08" + "03221234".padEnd(16, '0') + "\r")     // 22 bytes: one write at 97, three at 20 would be wrong
        assertEquals("the write path uses the size the export names", 1, g.writes.size)
    }

    @Test fun `a new session starts with no MTU context`() = runTest {
        attempt(Mtu())
        DiagnosticLogger.sessionStart("192.168.0.10", 35000, prefs = null, transport = "TCP")
        assertEquals(null to null, export().let { it.first to it.second?.toString() })
    }

    // ── Through the connection's own attempt loop ─────────────────────────────────────────────────────────────────

    private inner class BleFake(val scope: TestScope, val answer: Mtu) : SlcanTransport {
        val core = BleGattCore("MEATPI_PRO", writeTimeoutMs = 2_000L)
        val g = Gatt(core, scope.backgroundScope, answer)
        override suspend fun open() {
            scope.backgroundScope.launch { delay(50); core.onConnected(g); delay(50); core.onServicesDiscovered(0, services()) }
            core.setup()
            scope.backgroundScope.launch {
                while (isActive && !g.closed) { core.onNotify(pro.notify, "t0908${"0".repeat(16)}\r".toByteArray()); delay(20) }
            }
        }
        override suspend fun readLine() = core.readLine()
        override suspend fun writeLine(frame: String) = core.writeLine(frame)
        override fun close() = core.close()
        override val label = "BLE fake"
        override val stockFirmwareLabel = "fake"
        override val kind = "Bluetooth"
        override val bleMtu: BleMtuTrace get() = core.mtuTrace
    }

    private class WifiFake(val scope: TestScope) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override suspend fun open() { scope.backgroundScope.launch { while (isActive) { lines.send("t0908${"0".repeat(16)}"); delay(20) } } }
        override suspend fun readLine(): String? = lines.receiveCatching().getOrNull()
        override suspend fun writeLine(frame: String) = Unit
        override fun close() { lines.close() }
        override val label = "TCP fake"
        override val stockFirmwareLabel = "fake"
    }

    private fun TestScope.connect(factory: () -> SlcanTransport): SlcanConnection {
        val conn = SlcanConnection(
            transportFactory = factory, maxRetries = 5, rpmProvider = { 0.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        return conn
    }

    private fun TestScope.waitFor(what: String, cond: () -> Boolean) {
        var waited = 0L
        while (!cond() && waited < 60_000L) { advanceTimeBy(100L); waited += 100L }
        assertTrue(what, cond())
    }

    @Test fun `reconnect - the export shows only the new attempt, numbered, never the old link's size`() = runTest {
        val built = mutableListOf<BleFake>()
        val conn = connect { BleFake(this, if (built.isEmpty()) Mtu(mtu = 185) else Mtu(mtu = 23, status = 133)).also { built += it } }
        waitFor("first link up") { conn.transport != null }
        assertEquals("attempt 1 · requested 247 · callback GATT 0, MTU 185 · in use 185, writes up to 182 bytes", export().first)

        built[0].core.onDisconnected(8)                                  // the link drops; the connection retries by itself
        waitFor("second attempt built") { built.size == 2 }
        val during = export().first!!
        assertTrue("the old attempt's 185 is gone as soon as the new attempt starts: $during",
            during.startsWith("attempt 2 · ") && "185" !in during && "in use 23 (fallback)" in during)

        waitFor("second link up") { conn.transport != null && built[1].core.mtuTrace.read().callbackStatus != null }
        val (line, j) = export()
        assertEquals("attempt 2 · requested 247 · callback GATT 133, MTU 23 (not applied) · in use 23 (fallback), writes up to 20 bytes", line)
        assertEquals(2, j!!.getInt("attempt"))
    }

    @Test fun `Wi-Fi - the export has no MTU line and no bleMtu block`() = runTest {
        DiagnosticLogger.sessionStart("192.168.0.10", 35000, prefs = null, transport = "TCP")
        val conn = connect { WifiFake(this) }
        waitFor("link up") { conn.transport != null }
        val (text, detail) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
        assertFalse(text, "MTU" in text.substringBefore("─── DTC SCAN"))
        assertFalse(JSONObject(detail).has("bleMtu"))
        assertTrue("control: the same export has its connection section", "Transport: TCP" in text)
    }

    @Test fun `Bluetooth then Wi-Fi in one process - the Wi-Fi attempt clears the Bluetooth context`() = runTest {
        attempt(Mtu(mtu = 185))
        assertTrue(export().first != null)
        val conn = connect { WifiFake(this) }
        waitFor("link up") { conn.transport != null }
        assertEquals(null to null, export().let { it.first to it.second?.toString() })
    }
}
