package com.openrs.dash

import com.openrs.dash.can.BleAdapterProfile
import com.openrs.dash.can.BleGattCore
import com.openrs.dash.can.BleSetupPolicy
import com.openrs.dash.can.GattLink
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanParser
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.PerfRunEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.LinkOwnership
import com.openrs.dash.service.LinkSession
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * rc.8 reliability pass, #251 — SLCAN framing. Parser: the data field is exactly DLC × 2 hex characters (no suffix — the
 * app never sends Z1). Transport: BLE notifications may split one line across several notifications (fragmented) or hold
 * several lines (coalesced); `BleGattCore` frames lines at CR. Production path for the BLE cases: fake GATT notifications →
 * BleGattCore → SlcanConnection → LinkSession (decode, stamps, peaks) — the same composition as ReplayHarness, BLE
 * transport instead of a line transport. SYNTHETIC frames; oil = 0x0F8 byte 7 − 60 (#229).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlcanFramingTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    // ── Parser boundaries ───────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `control - exact standard frames parse at every DLC`() {
        for (dlc in 0..8) {
            val line = "t123$dlc" + "AB".repeat(dlc)
            val f = SlcanParser.parse(line)
            assertNotNull(line, f)
            assertEquals(0x123, f!!.first)
            assertEquals(dlc, f.second.size)
        }
    }

    @Test fun `control - an exact extended frame parses`() {
        val f = SlcanParser.parse("T18DAF11021001")
        assertNotNull(f)
        assertEquals(0x18DAF110, f!!.first)
        assertEquals(listOf(0x10, 0x01), f.second.map { it.toInt() and 0xFF })
    }

    @Test fun `an extended frame with characters after its data is refused`() {
        assertNull(SlcanParser.parse("T18DAF110210012710"))
    }

    @Test fun `a DLC 0 frame with characters after it is refused`() {
        assertNull(SlcanParser.parse("t0F80FF"))
    }

    @Test fun `a frame carrying a 4-hex timestamp suffix is refused (the app never enables Z1)`() {
        assertNull(SlcanParser.parse("t0F88005A00000000008204D2"))
    }

    // ── BLE notification framing through the production connection ─────────────────────────────────────────────────

    private val pro = BleAdapterProfile.WICAN_PRO

    private inner class Link(val core: BleGattCore) : GattLink {
        override fun discoverServices() = true
        override fun setNotify(service: UUID, characteristic: UUID, enable: Boolean) = true
        override fun writeCccd(service: UUID, characteristic: UUID, value: ByteArray): Boolean {
            core.onDescriptorWrite(true, BleSetupPolicy.GATT_SUCCESS); return true
        }
        override fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean {
            core.onWrite(BleSetupPolicy.GATT_SUCCESS); return true
        }
        override fun requestMtu(mtu: Int): Boolean { core.onMtuChanged(mtu, 0); return true }
        override fun bondState() = BleGattCore.BOND_BONDED
        override fun createBond() = true
        override fun disconnect() = Unit
        override fun close() = Unit
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

    /** Connected over the fake BLE core; [notifications] are delivered at their times (ms after open), as raw bytes. */
    private fun TestScope.run(vararg notifications: Pair<Long, String>): MutableStateFlow<VehicleState> {
        val state = MutableStateFlow(VehicleState(isConnected = true))
        val own = LinkOwnership()
        val session = LinkSession(own, own.begin(), state, NoRecorder, onFrameCounted = {}, perfRunSink = { _: PerfRunEntity -> })
        val core = BleGattCore("MEATPI_PRO")
        val transport = object : SlcanTransport {
            override suspend fun open() {
                backgroundScope.launch {
                    delay(10); core.onConnected(Link(core)); delay(10)
                    core.onServicesDiscovered(0, mapOf(pro.service to mapOf(
                        pro.write to BleGattCore.PROPERTY_WRITE, pro.notify to BleGattCore.PROPERTY_NOTIFY)))
                }
                core.setup()
                for ((at, text) in notifications) backgroundScope.launch { delay(at); core.onNotify(pro.notify, text.toByteArray()) }
            }
            override suspend fun readLine() = core.readLine()
            override suspend fun writeLine(frame: String) = core.writeLine(frame)
            override fun close() = core.close()
            override val label = "BLE fake"
            override val stockFirmwareLabel = "fake"
        }
        val conn = SlcanConnection(
            transportFactory = { transport }, maxRetries = 1, rpmProvider = { 0.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch {
            conn.connectHybrid({ obd -> state.value = ObdMerge.merge(state.value, obd) }, { state.value })
                .collect { (id, data) -> session.onCanFrame(id, data, 0.0) }
        }
        advanceTimeBy(5_000L)
        return state
    }

    private val oil70 = "t0F88005A000000000082\r"           // oil 0x82 − 60 = 70 °C, PTU 0x5A − 50 = 40 °C
    private val oil80 = "t0F88005A00000000008C\r"           // oil 80 °C

    @Test fun `control - a line split across three notifications decodes once`() = runTest {
        val vs = run(100L to "t09080000C1FF617D0000\r", 1_500L to oil70.substring(0, 7), 1_510L to oil70.substring(7, 15),
            1_520L to oil70.substring(15)).value
        assertEquals(70.0, vs.oilTempC, 0.0)
    }

    @Test fun `control - two lines in one notification both decode`() = runTest {
        val vs = run(100L to "t09080000C1FF617D0000\r", 1_500L to oil70 + oil80).value
        assertEquals(80.0, vs.oilTempC, 0.0)
        assertEquals(80.0, vs.session.peakOilTempC, 0.0)
    }

    @Test fun `two lines spliced without a CR never reach the decoder`() = runTest {
        // the CR between them lost: "…00FFt0F88…" — the old parser read the first eight bytes and decoded oil 195 (0xFF − 60)
        // from a corrupt line into the peak; strict length refuses it, and the next clean line still decodes
        val vs = run(100L to "t09080000C1FF617D0000\r", 1_500L to "t0F88005A0000000000FF" + oil70,
            1_600L to oil80).value
        assertEquals(80.0, vs.oilTempC, 0.0)
        assertEquals("no reading from the spliced line", 80.0, vs.session.peakOilTempC, 0.0)
        assertTrue(vs.fieldLastUpdateMs.containsKey("oilTempC"))
    }
}
