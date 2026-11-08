package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #253 slice: the diagnostic export's per-module, per-DID Mode 22 inventory and the ISO-TP refused counter — through the
 * production path: SlcanConnection's pollers on virtual time (engine running) against a small reactive fake ECU, then the
 * real DiagnosticReportBuilder summary and detail JSON. SYNTHETIC replies, counts only. Additive: the export schema version
 * is unchanged. Red on 1f296b9 (no DID INVENTORY section, no didInventory / isoTpRefused fields).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DidInventoryExportTest {
    /** PCM: every request answered positively (2 data bytes) except KR2 0x03ED, rejected (7F 22 31), and the DIDs in [silent],
     *  which never answer; BCM 0x2813 answers with a truncated single frame (PCI claims 5 data bytes in a 4-byte frame). A
     *  silent PCM DID leaves its request unresolved for the connection, so later rejections are not attributed (#216). */
    private class Ecu(private val scope: CoroutineScope, private val silent: Set<String> = emptySet()) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED)
        val writes = mutableListOf<String>()
        override suspend fun open() { scope.launch { lines.send("t09080000C1FF617D0000") } }   // the handshake takes this line
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun writeLine(frame: String) {
            writes += frame
            val did = if (frame.length >= 13) frame.substring(9, 13) else ""   // t + ID(3) + DLC + PCI + 22 + DID
            when {
                frame.startsWith("t7E08") && did == "03ED" -> lines.send("t7E88037F223100000000")
                frame.startsWith("t7E08") && did == "033E" -> lines.send("t7E880562033E4F1A0000")
                frame.startsWith("t7E08") && did.length == 4 && did !in silent -> lines.send("t7E880562${did}12340000")
                frame.startsWith("t726") && did == "2813" -> lines.send("t72E405622813")
            }
        }
        override fun close() { lines.close() }
        override val label = "fake-ecu"
        override val stockFirmwareLabel = "fake-ecu"
    }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun TestScope.run(ms: Long, silent: Set<String> = emptySet()): Ecu {
        val ecu = Ecu(backgroundScope, silent)
        val state = MutableStateFlow(VehicleState(isConnected = true))
        val conn = SlcanConnection(transportFactory = { ecu }, maxRetries = 1, rpmProvider = { 3_000.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime })
        backgroundScope.launch { conn.connectHybrid({ obd -> state.value = ObdMerge.merge(state.value, obd) }, { state.value }).collect {} }
        advanceTimeBy(ms)
        return ecu
    }

    private fun row(rows: org.json.JSONArray, module: String, did: String): JSONObject? =
        (0 until rows.length()).map { rows.getJSONObject(it) }.firstOrNull { it.getString("module") == module && it.getString("did") == did }

    @Test fun `the export lists each polled DID with what was sent, answered and rejected, and the ISO-TP refusals`() = runTest {
        val ecu = run(70_000L)                                           // 20 s start delay, then ~25 PCM cycles; BCM sweeps
        val json = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))
        val rows = json.getJSONObject("didInventory").getJSONArray("rows")
        val tipSent = ecu.writes.count { it.startsWith("t7E0") && "22033E" in it }

        val tip = row(rows, "PCM", "033E")!!
        assertEquals("TIP sent = the requests written", tipSent, tip.getInt("sent"))
        assertEquals("every TIP request answered", tipSent, tip.getInt("positive"))
        assertTrue(tip.has("lastPositiveRelMs"))

        val kr2 = row(rows, "PCM", "03ED")!!
        assertTrue("KR2 rejections charged to it (${kr2.getInt("nrcAttributed")})", kr2.getInt("nrcAttributed") >= 2)
        assertEquals(0, kr2.getInt("positive"))

        assertTrue("the truncated BCM single frame is counted", json.getJSONObject("linkIntegrity").getInt("isoTpRefused") >= 1)
        assertEquals(0, json.getJSONObject("didInventory").getInt("overflow"))
        assertEquals("additive: the schema version is unchanged", 1, json.getInt("exportSchemaVersion"))

        val text = DiagnosticReportBuilder.buildSummaryText("t")
        assertTrue(text, text.contains("─── DID INVENTORY"))
        assertTrue(text, Regex("""PCM\s+0x033E : $tipSent sent · $tipSent answered · 0 rejected · last answer at \+""").containsMatchIn(text))
        assertTrue(text, Regex("""PCM\s+0x03ED : \d+ sent · 0 answered · [1-9]\d* rejected""").containsMatchIn(text))
        assertTrue(text, text.contains("ISO-TP   : "))
    }

    @Test fun `a DID that never answers reads never answered, and the rejections after it are not charged to any DID`() = runTest {
        run(70_000L, silent = setOf("0466"))                            // TIP desired silent: its request stays unresolved
        val json = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))
        val rows = json.getJSONObject("didInventory").getJSONArray("rows")
        val desired = row(rows, "PCM", "0466")!!
        assertTrue(desired.getInt("sent") > 0); assertEquals(0, desired.getInt("positive")); assertEquals(0, desired.getInt("nrcAttributed"))
        assertEquals("KR2's rejections are not attributed once a request is unresolved (#216)", 0, row(rows, "PCM", "03ED")!!.getInt("nrcAttributed"))
        assertTrue(json.getJSONObject("linkIntegrity").getInt("nrcUnattributed") >= 1)
        val text = DiagnosticReportBuilder.buildSummaryText("t")
        assertTrue(text, Regex("""PCM\s+0x0466 : \d+ sent · 0 answered · 0 rejected · never answered""").containsMatchIn(text))
    }

    @Test fun `a valid multi-frame reply counts no refusal while it assembles, a real refusal counts once`() {
        // The BCM buffer's own feed, as the frame loop calls it: FF (length 10) → CF 1 completes; then a CF out of sequence.
        var refused = 0
        val buf = com.openrs.dash.can.IsoTpBuffer { refused++ }
        fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
        assertEquals(null, buf.feed(hex("100A62280B010203")).first)            // FF: waiting, not refused
        assertEquals(0, refused)
        assertEquals(10, buf.feed(hex("2104050607080900")).first!!.size)       // CF 1: complete
        assertEquals("a reply that waited then completed is not a refusal", 0, refused)
        buf.feed(hex("100A62280B010203"))
        buf.feed(hex("2304050607080900"))                                      // CF 3 where 1 was due: abandoned
        assertEquals(1, refused)
    }

    @Test fun `the same DID from two modules is two rows - the inventory is per module address`() = runTest {
        DiagnosticLogger.countDidPositive(0x72E, 0x4028); DiagnosticLogger.countDidPositive(0x72E, 0x4028)
        DiagnosticLogger.countDidPositive(0x70B, 0x4028)
        val rows = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("didInventory").getJSONArray("rows")
        assertEquals(2, row(rows, "BCM", "4028")!!.getInt("positive"))
        assertEquals(1, row(rows, "AWD", "4028")!!.getInt("positive"))
    }

    @Test fun `the inventory starts empty with every session`() = runTest {
        run(40_000L)
        DiagnosticLogger.sessionStart("next", 0, prefs = null)
        val json = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))
        assertEquals(0, json.getJSONObject("didInventory").getJSONArray("rows").length())
        assertEquals(0, json.getJSONObject("linkIntegrity").getInt("isoTpRefused"))
    }
}
