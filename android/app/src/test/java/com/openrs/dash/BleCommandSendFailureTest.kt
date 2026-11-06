package com.openrs.dash

import com.openrs.dash.can.BleAdapterProfile
import com.openrs.dash.can.BleFirmwareApi
import com.openrs.dash.can.BleGattCore
import com.openrs.dash.can.BleSetupPolicy
import com.openrs.dash.can.DriveCommandResult
import com.openrs.dash.can.EscCommand
import com.openrs.dash.can.GattLink
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.can.executeDriveModeChange
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DidProbeEnd
import com.openrs.dash.diagnostics.runDidProbeScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
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
import java.util.UUID

/**
 * #238 review finding 1 — a failed `AT+FRS` send over BLE. [BleGattCore.writeLine] throws IOException when a write is
 * refused, fails its callback or is never acknowledged (#218: the link is then closed). [BleFirmwareApi] must turn that
 * into a `Result.failure` for both commands — the UI launches (DriveModeDock, MorePage ESC) have no catch, so an
 * escaped exception reaches the thread's uncaught handler. Production path: SlcanConnection over the same core
 * BleSlcanTransport uses (fake GATT, JVM, virtual time) → BleFirmwareApi → executeDriveModeChange / EscCommand.execute.
 * Cancellation of the caller must still propagate. No adapter or phone.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleCommandSendFailureTest {
    private val pro = BleAdapterProfile.WICAN_PRO

    private enum class FrsWrite { OK, NO_CALLBACK, CALLBACK_FAILS, REFUSED }

    /** Fake GATT: poll / init writes succeed; `AT+FRS` writes behave per [frs]; OK answers `+FRS:OK`. */
    private inner class FakeGatt(val core: BleGattCore, val scope: CoroutineScope) : GattLink {
        var frs = FrsWrite.OK
        val frsWrites = mutableListOf<String>()
        var slcanWriteStatus: (String) -> Int = { BleSetupPolicy.GATT_SUCCESS }
        var closed = false
        override fun discoverServices() = true
        override fun setNotify(service: UUID, characteristic: UUID, enable: Boolean) = true
        override fun writeCccd(service: UUID, characteristic: UUID, value: ByteArray): Boolean {
            scope.launch { delay(30); core.onDescriptorWrite(true, BleSetupPolicy.GATT_SUCCESS) }; return true
        }
        override fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean {
            val text = String(value, Charsets.ISO_8859_1)
            if (!text.startsWith("AT+FRS")) {
                val status = slcanWriteStatus(text)
                scope.launch { delay(10); core.onWrite(status) }
                return true
            }
            frsWrites += text
            when (frs) {
                FrsWrite.OK -> scope.launch {
                    delay(10); core.onWrite(BleSetupPolicy.GATT_SUCCESS)
                    delay(50); core.onNotify(pro.notify, "+FRS:OK\r".toByteArray())
                }
                FrsWrite.NO_CALLBACK -> Unit
                FrsWrite.CALLBACK_FAILS -> scope.launch { delay(10); core.onWrite(BleSetupPolicy.GATT_INSUFFICIENT_AUTHENTICATION) }
                FrsWrite.REFUSED -> return false
            }
            return true
        }
        override fun requestMtu(mtu: Int): Boolean { scope.launch { delay(10); core.onMtuChanged(mtu, 0) }; return true }
        override fun bondState() = BleGattCore.BOND_BONDED
        override fun createBond() = true
        override fun disconnect() = Unit
        override fun close() { closed = true }
    }

    private class Link(val conn: SlcanConnection, val gatts: List<FakeGatt>) {
        val first get() = gatts.first()
        val frsWrites get() = gatts.sumOf { it.frsWrites.size }
    }

    /** A connected SlcanConnection (handshake done, transport published) over the fake BLE core. */
    private fun TestScope.connected(frs: FrsWrite): Link {
        val gatts = mutableListOf<FakeGatt>()
        val factory = {
            object : SlcanTransport {
                val core = BleGattCore("MEATPI_PRO", writeTimeoutMs = 2_000L)
                val g = FakeGatt(core, backgroundScope).also { it.frs = frs; gatts += it }
                override suspend fun open() {
                    backgroundScope.launch {
                        delay(50); core.onConnected(g); delay(50)
                        core.onServicesDiscovered(0, mapOf(pro.service to mapOf(
                            pro.write to (BleGattCore.PROPERTY_WRITE or BleGattCore.PROPERTY_WRITE_NO_RESPONSE),
                            pro.notify to BleGattCore.PROPERTY_NOTIFY)))
                    }
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
        var waited = 0L
        while (conn.transport == null && waited < 10_000L) { advanceTimeBy(100L); waited += 100L }
        assertTrue("connected", conn.transport != null)
        advanceTimeBy(500L)
        return Link(conn, gatts)
    }

    private var car = VehicleState(isConnected = true, driveMode = DriveMode.NORMAL)
    private fun dmEvents() = DiagnosticLogger.sessionEvents.filter { it.type == "DM_CMD" }.map { it.message }

    private suspend fun TestScope.driveMode(api: BleFirmwareApi, target: DriveMode = DriveMode.SPORT) =
        executeDriveModeChange(api, target, DriveMode.NORMAL, state = { car }, connectionId = { 1 },
            clock = { testScheduler.currentTime }, firmwareLabel = { "openRS_ test" })

    private suspend fun TestScope.esc(api: BleFirmwareApi, target: EscStatus = EscStatus.PARTIAL) =
        EscCommand.execute(api, target, { car }, connectionId = { 1 }, clock = { testScheduler.currentTime })

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    // rc.8 reliability pass (#248): GATT 13 is now "possibly delivered" and no longer ends a scan (BleWriteOutcomeTest).
    // These two containment tests used 13 as their failing status; they now use 133 (GATT_ERROR after sending), which is
    // still a write failure — same assertions otherwise. Their passing runs with 13 are kept in the kickoff evidence.
    @Test fun `DID prober - a write failure after sending (GATT 133) produces no result or completed session`() = runTest {
        val link = connected(FrsWrite.OK)
        val frames = listOf("t7E080322ABCD00000000\r", "t7E080322ABCE00000000\r")
        val sent = mutableListOf<String>()
        link.first.slcanWriteStatus = { frame ->
            if (frame in frames) { sent.add(frame); 133 } else BleSetupPolicy.GATT_SUCCESS
        }
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, listOf(0xABCD, 0xABCE),
            link.conn::sendRawQuery, { false }) { error("unacknowledged write produced a DID result") }
        assertEquals(DidProbeEnd.Failed(0xABCD, "IOException: Delivery unknown: BLE write failed (GATT 133)"), end)
        assertEquals(frames.take(1), sent)
        assertTrue(DiagnosticLogger.probeSessions.isEmpty())
        val event = DiagnosticLogger.sessionEvents.single { it.type == "PROBE" }
        assertTrue(event.message.contains("0/2 probed"))
    }

    @Test fun `DID prober - GATT 133 stops after one failed write and a fresh scan can complete`() = runTest {
        val link = connected(FrsWrite.OK)
        val dids = (0xABCD..0xABCF).toList() // outside the ordinary poll set
        val frames = dids.map { "t7E080322%04X00000000\r".format(it) }
        val sent = mutableListOf<String>()
        val shown = mutableListOf<DiagnosticLogger.ProbeResult>()
        link.first.slcanWriteStatus = { frame ->
            if (frame in frames) sent.add(frame)
            if (frame == frames[0]) backgroundScope.launch {
                delay(30)
                link.first.core.onNotify(pro.notify, "t7E880462ABCDBA000000\r".toByteArray())
            }
            if (frame == frames[1]) 133 else BleSetupPolicy.GATT_SUCCESS
        }

        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, dids, link.conn::sendRawQuery, { false }) { shown.add(it) }
        val first = DiagnosticLogger.ProbeResult(dids.first(), "FOUND", "BA 00 00 00")
        assertEquals(DidProbeEnd.Failed(dids[1], "IOException: Delivery unknown: BLE write failed (GATT 133)"), end)
        assertEquals(frames.take(2), sent) // no retry and no third DID
        assertEquals(listOf(first), shown)
        // the result it has is kept, labelled FAILED with the planned count — never a whole scan
        val failed = DiagnosticLogger.probeSessions.single()
        assertEquals(DiagnosticLogger.PROBE_FAILED, failed.outcome)
        assertEquals(3, failed.planned)
        assertEquals(listOf(first), failed.results)
        val events = DiagnosticLogger.sessionEvents.filter { it.type == "PROBE" }
        assertEquals(1, events.size)
        assertTrue(events.single().message.contains("GATT 133"))
        assertFalse(events.single().message.contains("scan complete"))

        shown.clear()
        val next = runDidProbeScan("PCM", 0x7E0, 0x7E8, dids.take(1), link.conn::sendRawQuery, { false }) { shown.add(it) }
        assertEquals(DidProbeEnd.Complete, next)
        assertEquals(listOf(first), shown)
        val fresh = DiagnosticLogger.probeSessions.last()
        assertEquals(2, DiagnosticLogger.probeSessions.size)
        assertEquals(DiagnosticLogger.PROBE_COMPLETE, fresh.outcome)
        assertEquals(listOf(first), fresh.results)
        assertEquals(listOf(frames[0], frames[1], frames[0]), sent)
    }

    @Test fun `harness - an acknowledged AT+FRS with a CAN report after it confirms the drive mode`() = runTest {
        val link = connected(FrsWrite.OK)
        backgroundScope.launch {
            delay(3_000L)
            car = car.copy(driveMode = DriveMode.SPORT).markFresh("driveMode", now = testScheduler.currentTime)
        }
        val r = driveMode(BleFirmwareApi(link.conn))
        assertEquals(DriveCommandResult.Success, r)
        assertEquals(1, link.frsWrites)
    }

    @Test fun `drive mode - an unacknowledged AT+FRS write is Failed, sent once, never accepted or corrected`() = runTest {
        val link = connected(FrsWrite.NO_CALLBACK)
        val r = runCatching { driveMode(BleFirmwareApi(link.conn)) }
        assertTrue("returns a result, not an exception: $r", r.isSuccess)
        val res = r.getOrThrow()
        assertTrue("$res", res is DriveCommandResult.Failed && res.message.contains("not acknowledged"))
        assertEquals("one AT+FRS, no correction", 1, link.frsWrites)
        assertTrue("link closed (#218)", link.first.closed)
        assertFalse(dmEvents().any { it.startsWith("ACCEPTED") || it.startsWith("CONFIRMED") || it.startsWith("Overshoot") })
        assertTrue(dmEvents().any { it.startsWith("FAILED:") })
    }

    @Test fun `drive mode - a refused or failed AT+FRS write is Failed`() = runTest {
        for (mode in listOf(FrsWrite.REFUSED, FrsWrite.CALLBACK_FAILS)) {
            val link = connected(mode)
            val r = runCatching { driveMode(BleFirmwareApi(link.conn)) }
            assertTrue("$mode returns a result, not an exception: $r", r.isSuccess && r.getOrThrow() is DriveCommandResult.Failed)
            assertEquals(1, link.frsWrites)
        }
    }

    @Test fun `ESC - an unacknowledged AT+FRS write is Failed and releases the in-flight slot`() = runTest {
        val link = connected(FrsWrite.NO_CALLBACK)
        val api = BleFirmwareApi(link.conn)
        val o = runCatching { esc(api) }
        assertTrue("returns an outcome, not an exception: $o", o.isSuccess)
        assertTrue("${o.getOrNull()}", o.getOrThrow() is EscCommand.Outcome.Failed)
        assertEquals(1, link.frsWrites)
        // the next request is not Busy (in-flight released) and never confirms from the held state
        val again = runCatching { esc(api) }.getOrNull()
        assertTrue("$again", again != null && again !is EscCommand.Outcome.Busy && again !is EscCommand.Outcome.Confirmed)
    }

    @Test fun `ESC - a refused AT+FRS write is Failed`() = runTest {
        val link = connected(FrsWrite.REFUSED)
        val o = runCatching { esc(BleFirmwareApi(link.conn)) }
        assertTrue("returns an outcome, not an exception: $o", o.isSuccess && o.getOrThrow() is EscCommand.Outcome.Failed)
    }

    @Test fun `cancelling the caller during the AT+FRS write still cancels - both commands`() = runTest {
        // A fix that caught every exception would turn this cancellation into a returned Failed — [returned] would be set
        val link = connected(FrsWrite.NO_CALLBACK)
        val api = BleFirmwareApi(link.conn)
        var returned: Any? = null
        var cancelled = false
        val dm = launch { try { returned = driveMode(api) } catch (e: CancellationException) { cancelled = true; throw e } }
        advanceTimeBy(500L)                                             // inside the 2 s write wait
        dm.cancel()
        runCurrent()
        assertTrue("drive-mode caller saw the cancellation", cancelled)
        assertEquals("no result returned", null, returned)

        val link2 = connected(FrsWrite.NO_CALLBACK)
        val api2 = BleFirmwareApi(link2.conn)
        returned = null; cancelled = false
        val e = launch { try { returned = esc(api2) } catch (e: CancellationException) { cancelled = true; throw e } }
        advanceTimeBy(500L)
        e.cancel()
        runCurrent()
        assertTrue("ESC caller saw the cancellation", cancelled)
        assertEquals(null, returned)
        assertEquals(1, link2.frsWrites)
        // the in-flight slot was released by the cancellation
        link2.first.frs = FrsWrite.REFUSED
        val next = runCatching { esc(api2) }.getOrNull()
        assertTrue("$next", next !is EscCommand.Outcome.Busy)        // null (an escaped exception) is finding 1, not this check
    }
}
