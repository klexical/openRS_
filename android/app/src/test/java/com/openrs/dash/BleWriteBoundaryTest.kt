package com.openrs.dash

import com.openrs.dash.can.BleAdapterProfile
import com.openrs.dash.can.BleFirmwareApi
import com.openrs.dash.can.BleGattCore
import com.openrs.dash.can.BleSetupPolicy
import com.openrs.dash.can.GattLink
import com.openrs.dash.can.ObdConstants
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanParser
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.can.WriteFailure
import com.openrs.dash.can.WriteOutcome
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DidProbeEnd
import com.openrs.dash.diagnostics.DtcScanner
import com.openrs.dash.diagnostics.runDidProbeScan
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.UUID

/**
 * #253 / #248 — BLE write boundaries, one byte either side of the split, through the real [BleGattCore.write] (fake GATT,
 * JVM, virtual time). Synthetic data only. At MTU 23 a write carries at most 20 bytes, so:
 *  - 19 (`AT+FRS=driveMode,N`, the longest firmware command) and 20 bytes are one ATT write; 21 is 20 + 1;
 *  - every part goes once, in order, each waiting for the previous part's callback; nothing is ever retransmitted;
 *  - outcome: GATT 0 on every part = SENT; 13 on any part, others 0 = POSSIBLY_DELIVERED; the first part refused = not
 *    sent; a later part refused, or any other status / no callback / link loss = uncertain.
 * What the app really writes is measured from production paths (first test): 7, 8, 17, 19 or 22 bytes. So on a real
 * adapter 19 and 22 occur, 20 and 21 are legal SLCAN the app doesn't emit, and nothing reaches the WiCAN USB's 64 bytes.
 * The 64-byte limit of FEE1 exists only in the upstream firmware source (see .claude/rules/ble.md); [BleAdapterProfile]
 * holds no limit and the core enforces none, so the 64 / 65 tests pin what the core does at that size, not a limit.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BleWriteBoundaryTest {
    private val pro = BleAdapterProfile.WICAN_PRO
    private val usb = BleAdapterProfile.WICAN_USB

    // ── Lines ───────────────────────────────────────────────────────────────────────────────────────────────────────

    private fun std(id: Int, hex: String) = "t%03X%d%s\r".format(id, hex.length / 2, hex)
    private fun ext(id: Int, hex: String) = "T%08X%d%s\r".format(id, hex.length / 2, hex)

    private val l19 = ext(0x18DA10F1, "03221234")                       // extended, DLC 4
    private val at19 = "AT+FRS=driveMode,3\r"                           // the real 19-byte line (measured below)
    private val l20 = std(0x7E0, "03221234000000")                      // standard, DLC 7
    private val l21 = ext(0x18DA10F1, "0322123400")                     // extended, DLC 5
    private val l22 = ObdConstants.buildSlcanQuery(0x7E0, 0x033E)       // a real PCM poll, standard, DLC 8
    /** Several SLCAN lines in one write, the form SLCAN_INIT uses; the only way a well-formed write gets this long. */
    private val l64 = l22 + ObdConstants.buildSlcanQuery(0x7E0, 0x0466) + l20
    private val l65 = l22 + ObdConstants.buildSlcanQuery(0x7E0, 0x0466) + l21

    private val singleAt23 = listOf(l19, at19, l20)
    private val allSizes = listOf(l19, at19, l20, l21, l22)

    private fun lineOf(did: Int) = ObdConstants.buildSlcanQuery(0x7E0, did)
    private fun nrc(code: Int) = "t7E88037F22%02X00000000".format(code)

    // ── Fake GATT ───────────────────────────────────────────────────────────────────────────────────────────────────

    private sealed interface Act {
        data class Status(val gatt: Int) : Act
        data object Refuse : Act
        data object Silent : Act
    }
    private val ok = Act.Status(BleSetupPolicy.GATT_SUCCESS)
    private val t13 = Act.Status(BleSetupPolicy.GATT_INVALID_ATTRIBUTE_LENGTH)
    private val e133 = Act.Status(133)

    /** One write the core handed to the stack: [index] over this link, [line] = what the adapter's parser holds with it. */
    private class Part(val index: Int, val text: String, val line: String, val characteristic: UUID, val writeType: Int)

    private class Gatt(
        val core: BleGattCore,
        val scope: CoroutineScope,
        val notifyChar: UUID,
        val negotiated: Int,
        val mtuStartRefused: Boolean = false,
        val mtuStatus: Int = BleSetupPolicy.GATT_SUCCESS,
    ) : GattLink {
        var act: (Part) -> Act = { Act.Status(BleSetupPolicy.GATT_SUCCESS) }
        var onLine: (String) -> Unit = {}
        /** Everything the core asked the stack to write, refused writes included. */
        val attempts = mutableListOf<Part>()
        private val partial = StringBuilder()
        var closed = false
        val parts get() = attempts.map { it.text }

        override fun discoverServices() = true
        override fun setNotify(service: UUID, characteristic: UUID, enable: Boolean) = true
        override fun writeCccd(service: UUID, characteristic: UUID, value: ByteArray): Boolean {
            scope.launch { delay(30); core.onDescriptorWrite(true, BleSetupPolicy.GATT_SUCCESS) }; return true
        }
        override fun write(service: UUID, characteristic: UUID, value: ByteArray, writeType: Int): Boolean {
            val text = String(value, Charsets.ISO_8859_1)
            val part = Part(attempts.size, text, partial.toString() + text, characteristic, writeType)
            attempts += part
            val action = act(part)
            if (action == Act.Refuse) { partial.clear(); return false }
            partial.append(text)
            if (part.line.endsWith("\r")) { partial.clear(); onLine(part.line) }
            if (action is Act.Status) scope.launch { delay(10); core.onWrite(action.gatt) }
            return true
        }
        override fun requestMtu(mtu: Int): Boolean {
            if (mtuStartRefused) return false
            scope.launch { delay(10); core.onMtuChanged(negotiated, mtuStatus) }
            return true
        }
        override fun bondState() = BleGattCore.BOND_BONDED
        override fun createBond() = true
        override fun disconnect() = Unit
        override fun close() { closed = true }
    }

    // ── Core alone: BleGattCore on a fake GATT, set up the way the transport does ──────────────────────────────────────

    private suspend fun TestScope.rig(
        mtu: Int,
        profile: BleAdapterProfile = pro,
        writeProps: Int = BleGattCore.PROPERTY_WRITE or BleGattCore.PROPERTY_WRITE_NO_RESPONSE,
        mtuStartRefused: Boolean = false,
        mtuStatus: Int = BleSetupPolicy.GATT_SUCCESS,
        act: (Part) -> Act = { ok },
    ): Gatt {
        val core = BleGattCore(if (profile == usb) "MEATPI_USB" else "MEATPI_PRO", writeTimeoutMs = 2_000L)
        val g = Gatt(core, backgroundScope, profile.notify, mtu, mtuStartRefused, mtuStatus)
        g.act = act
        core.onConnected(g)
        val chars = if (profile.write == profile.notify) mapOf(profile.write to (writeProps or BleGattCore.PROPERTY_NOTIFY))
            else mapOf(profile.write to writeProps, profile.notify to BleGattCore.PROPERTY_NOTIFY)
        core.onServicesDiscovered(0, mapOf(profile.service to chars))
        core.setup()
        assertEquals(if (mtuStartRefused || mtuStatus != 0) BleGattCore.DEFAULT_MTU else mtu, core.mtu)
        return g
    }

    private fun assertNotSent(what: String, e: Throwable?) {
        assertNotNull("$what: expected a failure", e)
        assertTrue("$what: $e", WriteFailure.isNotSent(e!!) && !WriteFailure.isUncertain(e))
    }
    private fun assertUncertain(what: String, e: Throwable?) {
        assertNotNull("$what: expected a failure", e)
        assertTrue("$what: $e", WriteFailure.isUncertain(e!!) && !WriteFailure.isNotSent(e))
    }
    private fun bytes(line: String) = "${line.length} bytes (${line.take(6)}…)"

    // ── Fixtures sit on what they claim ───────────────────────────────────────────────────────────────────────────

    @Test fun `the boundary lines are well-formed SLCAN of exactly the stated size`() {
        assertEquals(listOf(19, 20, 21, 22), listOf(l19, l20, l21, l22).map { it.length })
        for (line in listOf(l19, l20, l21, l22)) {
            assertNotNull("$line parses", SlcanParser.parse(line.trimEnd('\r')))
        }
        assertEquals(19, at19.length)
        assertEquals(64, l64.length)
        assertEquals(65, l65.length)
        for (line in (l64 + l65).split("\r").filter { it.isNotEmpty() }) {
            assertNotNull("$line parses", SlcanParser.parse(line))
        }
    }

    // ── One ATT write or several ─────────────────────────────────────────────────────────────────────────────────────

    @Test fun `MTU 23 - 19 and 20 bytes are one ATT write, 21 is 20 + 1 in order, 22 is 20 + 2, each once`() = runTest {
        for (line in singleAt23) {
            val g = rig(mtu = 23)
            g.core.write(line)
            assertEquals(bytes(line), listOf(line), g.parts)
        }
        for (line in listOf(l21, l22)) {
            val g = rig(mtu = 23)
            g.core.write(line)
            assertEquals(bytes(line), listOf(line.substring(0, 20), line.substring(20)), g.parts)
        }
    }

    @Test fun `MTU 247 - every size is one ATT write, once`() = runTest {
        for (line in allSizes) {
            val g = rig(mtu = 247)
            g.core.write(line)
            assertEquals(bytes(line), listOf(line), g.parts)
        }
    }

    @Test fun `the split moves with the negotiated MTU - payload is exactly MTU minus 3`() = runTest {
        for (line in listOf(l20, l21, l22)) {
            val fits = line.length + 3                               // the smallest MTU carrying the line whole
            val whole = rig(mtu = fits)
            whole.core.write(line)
            assertEquals("${bytes(line)} @ MTU $fits", listOf(line), whole.parts)
            if (fits - 1 >= BleGattCore.DEFAULT_MTU) {               // no legal MTU below 23
                val split = rig(mtu = fits - 1)
                split.core.write(line)
                assertEquals("${bytes(line)} @ MTU ${fits - 1}",
                    listOf(line.dropLast(1), line.takeLast(1)), split.parts)
            }
        }
    }

    @Test fun `an MTU request that is refused or fails leaves payload 20 - 20 stays whole, 21 splits`() = runTest {
        for (g in listOf(rig(mtu = 247, mtuStartRefused = true), rig(mtu = 247, mtuStatus = 133))) {
            g.core.write(l20)
            g.core.write(l21)
            assertEquals(listOf(l20, l21.substring(0, 20), l21.substring(20)), g.parts)
        }
    }

    @Test fun `the next part waits for the previous part's callback`() = runTest {
        val g = rig(mtu = 23) { Act.Silent }
        val write = async { g.core.write(l64) }
        runCurrent()
        assertEquals(listOf(l64.substring(0, 20)), g.parts)
        g.core.onWrite(BleSetupPolicy.GATT_SUCCESS)
        runCurrent()
        assertEquals(listOf(l64.substring(0, 20), l64.substring(20, 40)), g.parts)
        g.core.onWrite(BleSetupPolicy.GATT_SUCCESS); runCurrent()
        g.core.onWrite(BleSetupPolicy.GATT_SUCCESS); runCurrent()
        assertEquals(4, g.parts.size)
        assertFalse("still waiting for the last callback", write.isCompleted)
        g.core.onWrite(BleSetupPolicy.GATT_SUCCESS)
        assertEquals(WriteOutcome.SENT, write.await())
    }

    @Test fun `two writers at the split never interleave their parts`() = runTest {
        val a = ext(0x18DA10F1, "0322AAAA00")
        val b = ext(0x18DA10F2, "0322BBBB00")
        val g = rig(mtu = 23)
        val first = async { g.core.write(a) }
        val second = async { g.core.write(b) }
        first.await(); second.await()
        fun partsOf(line: String) = listOf(line.substring(0, 20), line.substring(20))
        assertTrue(g.parts.toString(), g.parts == partsOf(a) + partsOf(b) || g.parts == partsOf(b) + partsOf(a))
    }

    // ── Outcome at each size ────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `GATT 0 is SENT at every size and MTU`() = runTest {
        for (mtu in listOf(23, 247)) for (line in allSizes) {
            assertEquals("${bytes(line)} @ MTU $mtu", WriteOutcome.SENT, rig(mtu = mtu).core.write(line))
        }
    }

    @Test fun `GATT 13 on a one-part write is POSSIBLY_DELIVERED and is not resent`() = runTest {
        val cases = singleAt23.map { it to 23 } + allSizes.map { it to 247 }
        for ((line, mtu) in cases) {
            val g = rig(mtu = mtu) { t13 }
            assertEquals("${bytes(line)} @ MTU $mtu", WriteOutcome.POSSIBLY_DELIVERED, g.core.write(line))
            assertEquals("${bytes(line)} @ MTU $mtu", listOf(line), g.parts)
        }
    }

    @Test fun `GATT 13 on the first, second or both parts of a split is POSSIBLY_DELIVERED - the whole line goes, once`() = runTest {
        val scripts = mapOf<String, (Part) -> Act>(
            "first" to { p -> if (p.index == 0) t13 else ok },
            "second" to { p -> if (p.index == 1) t13 else ok },
            "both" to { t13 },
        )
        for (line in listOf(l21, l22)) for ((name, script) in scripts) {
            val g = rig(mtu = 23, act = script)
            assertEquals("${bytes(line)}, 13 on $name", WriteOutcome.POSSIBLY_DELIVERED, g.core.write(line))
            assertEquals("${bytes(line)}, 13 on $name", listOf(line.substring(0, 20), line.substring(20)), g.parts)
        }
    }

    @Test fun `POSSIBLY_DELIVERED belongs to one line - the next line on the link is SENT`() = runTest {
        val g = rig(mtu = 23) { p -> if (p.index == 1) t13 else ok }
        assertEquals(WriteOutcome.POSSIBLY_DELIVERED, g.core.write(l21))
        assertEquals(WriteOutcome.SENT, g.core.write(l21))
        assertEquals(WriteOutcome.SENT, g.core.write(l20))
    }

    @Test fun `a refused first part is not sent at every size - nothing further is attempted`() = runTest {
        for (mtu in listOf(23, 247)) for (line in allSizes) {
            val g = rig(mtu = mtu) { Act.Refuse }
            assertNotSent("${bytes(line)} @ MTU $mtu", runCatching { g.core.write(line) }.exceptionOrNull())
            assertEquals("${bytes(line)} @ MTU $mtu", 1, g.attempts.size)
        }
    }

    @Test fun `a refused later part is uncertain, also after 13 on the first - the first part is not resent`() = runTest {
        for (line in listOf(l21, l22)) for (head in listOf(ok, t13)) {
            val g = rig(mtu = 23) { p -> if (p.index == 0) head else Act.Refuse }
            val e = runCatching { g.core.write(line) }.exceptionOrNull()
            assertUncertain("${bytes(line)}, first part $head", e)
            assertTrue("$e", e!!.message!!.contains("after 20 of ${line.length} bytes"))
            assertEquals(listOf(line.substring(0, 20), line.substring(20)), g.parts)
        }
    }

    @Test fun `another status is uncertain at every size and is not resent`() = runTest {
        for (mtu in listOf(23, 247)) for (line in singleAt23) {
            val g = rig(mtu = mtu) { e133 }
            assertUncertain("${bytes(line)} @ MTU $mtu", runCatching { g.core.write(line) }.exceptionOrNull())
            assertEquals("${bytes(line)} @ MTU $mtu", listOf(line), g.parts)
        }
        val auth = rig(mtu = 247) { Act.Status(BleSetupPolicy.GATT_INSUFFICIENT_AUTHENTICATION) }
        assertUncertain("not paired", runCatching { auth.core.write(l22) }.exceptionOrNull())
        assertEquals(listOf(l22), auth.parts)
    }

    @Test fun `another status on the first part stops the line - the second part is never written`() = runTest {
        for (line in listOf(l21, l22)) {
            val g = rig(mtu = 23) { e133 }
            assertUncertain(bytes(line), runCatching { g.core.write(line) }.exceptionOrNull())
            assertEquals(bytes(line), listOf(line.substring(0, 20)), g.parts)
        }
    }

    @Test fun `another status on a later part is uncertain, and outranks 13 on the first`() = runTest {
        for (line in listOf(l21, l22)) for (head in listOf(ok, t13)) {
            val g = rig(mtu = 23) { p -> if (p.index == 0) head else e133 }
            assertUncertain("${bytes(line)}, first part $head", runCatching { g.core.write(line) }.exceptionOrNull())
            assertEquals(listOf(line.substring(0, 20), line.substring(20)), g.parts)
        }
    }

    @Test fun `no callback on a later part closes the link - uncertain, a late 13 changes nothing, later writes are not sent`() = runTest {
        val g = rig(mtu = 23) { p -> if (p.index == 0) ok else Act.Silent }
        assertUncertain("tail unacknowledged", runCatching { g.core.write(l21) }.exceptionOrNull())
        assertTrue("link closed", g.closed)
        assertEquals(2, g.attempts.size)
        g.core.onWrite(BleSetupPolicy.GATT_INVALID_ATTRIBUTE_LENGTH)
        assertNotSent("after the close", runCatching { g.core.write(l19) }.exceptionOrNull())
        assertEquals("nothing reached the stack after the close", 2, g.attempts.size)
    }

    @Test fun `the link dropping while a later part waits is uncertain, not resent`() = runTest {
        val g = rig(mtu = 23) { p -> if (p.index == 0) ok else Act.Silent }
        backgroundScope.launch { delay(100); g.core.onDisconnected(8) }
        assertUncertain("dropped mid-line", runCatching { g.core.write(l21) }.exceptionOrNull())
        assertEquals(listOf(l21.substring(0, 20), l21.substring(20)), g.parts)
    }

    @Test fun `a cancelled writer between parts stays a cancellation - the tail isn't written, the next line goes whole`() = runTest {
        val g = rig(mtu = 23) { p -> if (p.index == 0) Act.Silent else ok }
        var thrown: Throwable? = null
        val job = launch { try { g.core.write(l21) } catch (e: Throwable) { thrown = e; throw e } }
        runCurrent()
        job.cancelAndJoin()
        assertTrue("$thrown", thrown is CancellationException)
        assertEquals(listOf(l21.substring(0, 20)), g.parts)
        g.core.onWrite(BleSetupPolicy.GATT_SUCCESS)               // its callback, after the writer left
        assertEquals(WriteOutcome.SENT, g.core.write(l19))
        assertEquals(listOf(l21.substring(0, 20), l19), g.parts)
    }

    @Test fun `a failed line leaves no state behind - the next line starts at its own first byte`() = runTest {
        val refusedHead = rig(mtu = 23) { p -> if (p.index == 0) Act.Refuse else ok }
        runCatching { refusedHead.core.write(l21) }
        assertEquals(WriteOutcome.SENT, refusedHead.core.write(l22))
        assertEquals(listOf(l21.substring(0, 20), l22.substring(0, 20), l22.substring(20)), refusedHead.parts)

        val refusedTail = rig(mtu = 23) { p -> if (p.index == 1) Act.Refuse else ok }
        runCatching { refusedTail.core.write(l21) }
        assertEquals(WriteOutcome.SENT, refusedTail.core.write(l22))
        assertEquals(listOf(l21.substring(0, 20), l21.substring(20), l22.substring(0, 20), l22.substring(20)), refusedTail.parts)
    }

    // ── USB FEE1: 64 bytes, and what 65 would do ──────────────────────────────────────────────────────────────────────

    @Test fun `USB FEE1 - a 64-byte write is one ATT write at MTU 247 and 67, 20 + 20 + 20 + 4 at MTU 23, on FEE1`() = runTest {
        for (mtu in listOf(247, 67)) {
            val g = rig(mtu = mtu, profile = usb)
            assertEquals(WriteOutcome.SENT, g.core.write(l64))
            assertEquals("MTU $mtu", listOf(l64), g.parts)
            assertEquals(usb.write, g.attempts.single().characteristic)
            assertEquals(BleGattCore.WRITE_TYPE_DEFAULT, g.attempts.single().writeType)
        }
        val g = rig(mtu = 23, profile = usb)
        assertEquals(WriteOutcome.SENT, g.core.write(l64))
        assertEquals(l64.chunked(20), g.parts)
        assertEquals(listOf(20, 20, 20, 4), g.parts.map { it.length })
    }

    @Test fun `USB FEE1 - 65 bytes - the core knows no 64-byte limit, it goes whole at MTU 247, 64 + 1 at MTU 67`() = runTest {
        val whole = rig(mtu = 247, profile = usb)
        assertEquals(WriteOutcome.SENT, whole.core.write(l65))
        assertEquals(listOf(l65), whole.parts)

        val over = rig(mtu = 247, profile = usb) { t13 }           // an over-length answer, as the Pro's stack gives
        assertEquals(WriteOutcome.POSSIBLY_DELIVERED, over.core.write(l65))
        assertEquals(listOf(l65), over.parts)

        val split = rig(mtu = 67, profile = usb)
        assertEquals(WriteOutcome.SENT, split.core.write(l65))
        assertEquals(listOf(l65.substring(0, 64), l65.substring(64)), split.parts)
    }

    @Test fun `USB FEE1 - three-part outcomes - 13 mid-line, the first refused, the third refused, 133 on the second`() = runTest {
        val mid = rig(mtu = 23, profile = usb) { p -> if (p.index == 1) t13 else ok }
        assertEquals(WriteOutcome.POSSIBLY_DELIVERED, mid.core.write(l64))
        assertEquals(l64.chunked(20), mid.parts)

        val first = rig(mtu = 23, profile = usb) { Act.Refuse }
        assertNotSent("first", runCatching { first.core.write(l64) }.exceptionOrNull())
        assertEquals(1, first.attempts.size)

        val third = rig(mtu = 23, profile = usb) { p -> if (p.index == 2) Act.Refuse else ok }
        val e = runCatching { third.core.write(l64) }.exceptionOrNull()
        assertUncertain("third", e)
        assertTrue("$e", e!!.message!!.contains("after 40 of 64 bytes"))
        assertEquals(l64.chunked(20).take(3), third.parts)

        val second = rig(mtu = 23, profile = usb) { p -> if (p.index == 1) e133 else ok }
        assertUncertain("second", runCatching { second.core.write(l64) }.exceptionOrNull())
        assertEquals(l64.chunked(20).take(2), second.parts)
    }

    @Test fun `USB FEE1 - one characteristic both ways - a reply on FEE1 is read while the write path uses FEE1`() = runTest {
        val g = rig(mtu = 247, profile = usb)
        assertEquals(usb.write, usb.notify)
        g.core.write(l22)
        g.core.onNotify(usb.notify, (std(0x7E8, "0562033E00000000") + std(0x7E8, "0562046600000000")).toByteArray())
        assertEquals(std(0x7E8, "0562033E00000000").trimEnd('\r'), g.core.readLine())
        assertEquals(std(0x7E8, "0562046600000000").trimEnd('\r'), g.core.readLine())
        assertEquals(usb.write, g.attempts.single().characteristic)
    }

    @Test fun `a write-without-response characteristic splits and reports the same way`() = runTest {
        val g = rig(mtu = 23, profile = usb, writeProps = BleGattCore.PROPERTY_WRITE_NO_RESPONSE)
        assertEquals(WriteOutcome.SENT, g.core.write(l21))
        assertEquals(listOf(l21.substring(0, 20), l21.substring(20)), g.parts)
        assertTrue(g.attempts.all { it.writeType == BleGattCore.WRITE_TYPE_NO_RESPONSE })
    }

    // ── Over the real connection: SlcanConnection → BleGattCore ────────────────────────────────────────────────────────

    private class Link(val conn: SlcanConnection, val gatts: List<Gatt>) { val first get() = gatts.first() }

    private fun TestScope.connected(mtu: Int, rpm: () -> Double = { 1_500.0 }, setup: (Gatt) -> Unit = {}): Link {
        val gatts = mutableListOf<Gatt>()
        val factory = {
            object : SlcanTransport {
                val core = BleGattCore("MEATPI_PRO", writeTimeoutMs = 2_000L)
                val g = Gatt(core, backgroundScope, pro.notify, mtu).also { setup(it); gatts += it }
                override suspend fun open() {
                    backgroundScope.launch {
                        delay(50); core.onConnected(g); delay(50)
                        core.onServicesDiscovered(0, mapOf(pro.service to mapOf(
                            pro.write to (BleGattCore.PROPERTY_WRITE or BleGattCore.PROPERTY_WRITE_NO_RESPONSE),
                            pro.notify to BleGattCore.PROPERTY_NOTIFY)))
                    }
                    core.setup()
                    backgroundScope.launch {
                        while (isActive && !g.closed) { core.onNotify(pro.notify, "t0908${"0".repeat(16)}\r".toByteArray()); delay(20) }
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
            transportFactory = factory, maxRetries = 3, rpmProvider = rpm,
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        var waited = 0L
        while (conn.transport == null && waited < 10_000L) { advanceTimeBy(100L); waited += 100L }
        assertTrue("connected", conn.transport != null)
        return Link(conn, gatts)
    }

    /** ECU behind the adapter: a whole PCM Mode 22 line is answered per [reply]; a partial line reaches nobody. */
    private fun Gatt.answerPolls(reply: (Int) -> String? = { did -> "t7E880562%04X00000000".format(did) }) {
        onLine = { line ->
            if (line.startsWith("t7E08") && line.substring(5, 9) == "0322") {
                val did = line.substring(9, 13).toInt(16)
                reply(did)?.let { answer -> scope.launch { delay(40); core.onNotify(pro.notify, "$answer\r".toByteArray()) } }
            }
        }
    }

    private fun suppressed() = DiagnosticLogger.nrcSuppressed.keys
    private fun unattributed() = DiagnosticLogger.sessionEvents.filter { it.type == "NRC_UNATTRIBUTED" }.map { it.message }
    private fun counted(key: String) = DiagnosticLogger.linkIntegrity.writes[key] ?: 0
    private val kr23 = { did: Int ->
        if (did == 0x03ED || did == 0x03EE) nrc(0x31) else "t7E880562%04X00000000".format(did)
    }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @Test fun `every line the app writes measures 7, 8, 17, 19 or 22 bytes - none reaches a 64-byte limit`() = runTest {
        var rpm = 0.0
        val link = connected(mtu = 247, rpm = { rpm }) { g ->
            g.onLine = { line ->
                val answer: List<String> = when {
                    line.startsWith("AT+FRS") -> listOf("+FRS:OK")
                    line == "t7E08031902FF00000000\r" -> listOf("t7E88100B5902FF012345")      // FF: makes the app send FC
                    line == "t7E083000000000000000\r" -> listOf("t7E882108012346080000")
                    line.startsWith("t7E08") && line.substring(5, 9) == "0322" ->
                        listOf("t7E880562%04X00000000".format(line.substring(9, 13).toInt(16)))
                    else -> emptyList()
                }
                g.scope.launch { answer.forEach { delay(40); g.core.onNotify(pro.notify, "$it\r".toByteArray()) } }
            }
        }
        advanceTimeBy(60_000L)                                    // engine off: full sweep, firmware probes, every module poll
        rpm = 1_500.0
        advanceTimeBy(60_000L)                                    // engine running: fast group
        val api = BleFirmwareApi(link.conn)
        for (m in DriveMode.entries) api.setDriveMode(m.toFirmwareInt())
        for (e in EscStatus.entries) e.firmwareMode?.let { api.setEscMode(it) }   // only commandable states have a mode
        link.conn.performDtcScan(DtcScanner.MODULES)
        link.conn.performDtcClear(DtcScanner.MODULES)
        runDidProbeScan("PCM", 0x7E0, 0x7E8, listOf(0xABCD), link.conn::sendRawQuery, { false }) { }

        val lines = link.first.attempts.map { it.text }           // MTU 247: one ATT write per written string
        val byKind = lines.groupBy { line ->
            when {
                line.startsWith("t") -> "t"
                line == ObdConstants.SLCAN_INIT -> "init"
                line == "OPENRS?\r" -> "probe"
                line.startsWith("AT+FRS=driveMode,") -> "driveMode"
                line.startsWith("AT+FRS=escMode,") -> "escMode"
                else -> "other: $line"
            }
        }
        assertEquals(byKind.keys.toString(), setOf("t", "init", "probe", "driveMode", "escMode"), byKind.keys)
        assertEquals(setOf(7), byKind.getValue("init").map { it.length }.toSet())
        assertEquals(setOf(8), byKind.getValue("probe").map { it.length }.toSet())
        assertEquals(setOf(19), byKind.getValue("driveMode").map { it.length }.toSet())
        assertEquals(setOf(17), byKind.getValue("escMode").map { it.length }.toSet())
        assertEquals(setOf(22), byKind.getValue("t").map { it.length }.toSet())
        // every kind of `t` line ran: the coverage above isn't only the PCM poller
        val t = byKind.getValue("t")
        assertTrue("PCM poll", t.any { it.startsWith("t7E080322") })
        assertTrue("another module's poll", t.any { it.contains("0322") && !it.startsWith("t7E0") })
        assertTrue("extended session", t.any { it.contains("021003") })
        assertTrue("DTC read", t.contains("t7E08031902FF00000000\r"))
        assertTrue("DTC flow control", t.contains("t7E083000000000000000\r"))
        assertTrue("DTC clear", t.contains("t7E080414FFFFFF000000\r"))
        assertTrue("probe", t.contains(lineOf(0xABCD)))
        assertEquals(22, lines.maxOf { it.length })
    }

    @Test fun `MTU 23 - every real firmware command is one whole ATT write - also when the stack answers 13`() = runTest {
        for (status in listOf(ok, t13)) {
            val link = connected(mtu = 23) { g ->
                g.act = { p -> if (p.text.startsWith("AT+FRS")) status else ok }
                g.onLine = { line ->
                    if (line.startsWith("AT+FRS")) g.scope.launch { delay(40); g.core.onNotify(pro.notify, "+FRS:OK\r".toByteArray()) }
                }
            }
            advanceTimeBy(500L)
            val api = BleFirmwareApi(link.conn)
            val modes = listOf(DriveMode.NORMAL, DriveMode.SPORT, DriveMode.DRIFT, DriveMode.TRACK).map { it.toFirmwareInt() }
            val escs = listOf(EscStatus.ON, EscStatus.PARTIAL, EscStatus.OFF).map { it.firmwareMode!! }
            for (m in modes) assertTrue("driveMode $m", api.setDriveMode(m).isSuccess)
            for (e in escs) assertTrue("escMode $e", api.setEscMode(e).isSuccess)
            val expected = modes.map { "AT+FRS=driveMode,$it\r" } + escs.map { "AT+FRS=escMode,$it\r" }
            assertEquals("status $status", expected, link.first.parts.filter { it.startsWith("AT+FRS") })
            assertTrue(link.first.parts.none { it.startsWith("AT+FRS") && it.length > 20 })
        }
    }

    private fun TestScope.pollsWith13(on: (Part) -> Boolean) = connected(mtu = 23) { g ->
        g.answerPolls(kr23)
        g.act = { p -> if (p.line.startsWith("t7E08") && on(p)) t13 else ok }
    }

    private fun assertWholePollsAttributed(link: Link) {
        assertTrue(suppressed().toString(), 0x03ED in suppressed() && 0x03EE in suppressed())
        assertTrue(suppressed().toString(), suppressed().all { it == 0x03ED || it == 0x03EE })
        assertTrue(counted(DiagnosticLogger.WRITE_POSSIBLY_DELIVERED).toString(), counted(DiagnosticLogger.WRITE_POSSIBLY_DELIVERED) > 1)
        assertEquals(0, counted(DiagnosticLogger.WRITE_UNCERTAIN))
        assertEquals(0, counted(DiagnosticLogger.WRITE_NOT_SENT))
        // Every PCM poll: exactly one head + one tail, adjacent in the PCM's own stream (no resent part, no lost tail)
        val pcm = link.first.attempts.filter { it.line.startsWith("t7E08") }.map { it.text }
        assertTrue(pcm.size.toString(), pcm.size > 10 && pcm.size % 2 == 0)
        assertEquals(setOf(20, 2), pcm.map { it.length }.toSet())
    }

    @Test fun `MTU 23 poll - GATT 13 on the first part keeps the request - its NRC is attributed, nothing resent`() = runTest {
        val link = pollsWith13 { p -> p.text.length == 20 }
        advanceTimeBy(40_000L)
        assertWholePollsAttributed(link)
    }

    @Test fun `MTU 23 poll - GATT 13 on the second part keeps the request - its NRC is attributed, nothing resent`() = runTest {
        val link = pollsWith13 { p -> p.text.length == 2 }
        advanceTimeBy(40_000L)
        assertWholePollsAttributed(link)
    }

    @Test fun `MTU 23 poll - the first part refused is withdrawn - the next NRC is still attributed`() = runTest {
        val kr1 = lineOf(0x03EC)
        val link = connected(mtu = 23) { g ->
            g.answerPolls(kr23)
            g.act = { p -> if (p.line == kr1.substring(0, 20)) Act.Refuse else ok }
        }
        advanceTimeBy(40_000L)
        assertTrue("KR1 tried", link.first.attempts.any { it.line == kr1.substring(0, 20) })
        assertTrue("its tail never written", link.first.attempts.none { it.line == kr1 })
        assertTrue(suppressed().toString(), 0x03ED in suppressed())
        assertTrue(counted(DiagnosticLogger.WRITE_NOT_SENT) > 0)
        assertEquals(0, counted(DiagnosticLogger.WRITE_UNCERTAIN))
    }

    @Test fun `MTU 23 poll - the second part refused is kept - later NRCs are charged to nobody`() = runTest {
        // The head may have reached the adapter: a request whose delivery is unknown is never withdrawn (#248)
        val kr1 = lineOf(0x03EC)
        val link = connected(mtu = 23) { g ->
            g.answerPolls(kr23)
            g.act = { p -> if (p.line == kr1) Act.Refuse else ok }
        }
        advanceTimeBy(40_000L)
        val heads = link.first.attempts.count { it.line == kr1.substring(0, 20) }
        val tails = link.first.attempts.count { it.line == kr1 }
        assertTrue("KR1 tried", heads > 0)
        assertEquals("each head gets one tail attempt, no retransmit", heads, tails)
        assertFalse(suppressed().toString(), 0x03ED in suppressed())
        assertTrue(unattributed().toString(), unattributed().any { it.contains("still unanswered") && it.contains("03EC") })
        assertTrue(counted(DiagnosticLogger.WRITE_UNCERTAIN) > 0)
        assertEquals(0, counted(DiagnosticLogger.WRITE_NOT_SENT))
    }

    @Test fun `MTU 23 prober - GATT 13 on the first part of every probe completes DID by DID, each line once`() = runTest {
        val dids = listOf(0xABCD, 0xABCE, 0xABCF)
        val link = connected(mtu = 23, rpm = { 0.0 }) { g ->
            g.answerPolls { did -> "t7E880%s".format(if (did in dids) "462%04XBA000000".format(did) else "562%04X00000000".format(did)) }
            g.act = { p -> if (dids.any { lineOf(it).substring(0, 20) == p.line }) t13 else ok }
        }
        advanceTimeBy(25_000L)
        val shown = mutableListOf<DiagnosticLogger.ProbeResult>()
        val end = runDidProbeScan("PCM", 0x7E0, 0x7E8, dids, link.conn::sendRawQuery, { false }) { shown += it }
        assertEquals(DidProbeEnd.Complete, end)
        assertEquals(dids.map { DiagnosticLogger.ProbeResult(it, "FOUND", "BA 00 00 00") }, shown)
        for (did in dids) {
            assertEquals(1, link.first.attempts.count { it.line == lineOf(did).substring(0, 20) })
            assertEquals(1, link.first.attempts.count { it.line == lineOf(did) })
        }
    }
}
