package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.can.WriteOutcome
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.ReplyCount
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #514: the diagnostic export counts, per module, the Mode 22 requests that got no reply — through the production path:
 * SlcanConnection's pollers on virtual time (engine running) against a fake adapter shaped like the Bluetooth link (each
 * write waits 40 ms for its callback and the reply is already in before it returns; PCM writes come back possibly
 * delivered), then the real DiagnosticReportBuilder summary and detail JSON. The adapter loses a fixed share of replies,
 * so the expected counts are known. SYNTHETIC frames, counts only. Red on staging 7a8c5e4: no `mode22Replies`, no
 * MODE 22 REPLIES section.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Mode22RepliesExportTest {
    /**
     * Answers every Mode 22 read positively, KR2 (PCM 0x03ED) with a rejection, except every [pcmDropEvery]th PCM read and
     * every [bcmDropEvery]th BCM read, whose replies are lost (0 = none lost). Records each read as its write begins.
     */
    private class Adapter(private val pcmDropEvery: Int, private val bcmDropEvery: Int) : SlcanTransport {
        val lines = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }   // the handshake's line
        val reads = mutableListOf<Triple<String, Long, Boolean>>()                                    // (request ID, began, lost)
        private var pcm = 0; private var bcm = 0
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun write(frame: String): WriteOutcome {
            val began = DiagnosticLogger.txStamp()
            if (frame.length >= 13 && frame[0] == 't' && frame.substring(4, 9) == "80322") {
                val id = frame.substring(1, 4); val did = frame.substring(9, 13)
                val lost = when (id) {
                    "7E0" -> pcmDropEvery > 0 && ++pcm % pcmDropEvery == 0
                    "726" -> bcmDropEvery > 0 && ++bcm % bcmDropEvery == 0
                    else -> false
                }
                reads += Triple(id, began, lost)
                val resp = "%03X".format(id.toInt(16) + 8)
                if (!lost) lines.trySend(if (id == "7E0" && did == "03ED") "t${resp}8037F223100000000" else "t${resp}80462${did}12340000")
            }
            delay(40L)                                                 // the write's callback: the reply is in before it returns
            return if (frame.startsWith("t7E0")) WriteOutcome.POSSIBLY_DELIVERED else WriteOutcome.SENT
        }
        override suspend fun writeLine(frame: String) { write(frame) }
        override fun close() { lines.close() }
        override val label = "fake-ble"; override val stockFirmwareLabel = "fake-ble"
    }

    @After fun realClock() { DiagnosticLogger.sessionClock = System::currentTimeMillis }

    private fun TestScope.run(ms: Long, pcmDropEvery: Int, bcmDropEvery: Int): Adapter {
        DiagnosticLogger.sessionClock = { testScheduler.currentTime }
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        val a = Adapter(pcmDropEvery, bcmDropEvery)
        val state = MutableStateFlow(VehicleState(isConnected = true))
        val conn = SlcanConnection(transportFactory = { a }, maxRetries = 1, rpmProvider = { 3_000.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime })
        backgroundScope.launch { conn.connectHybrid({ obd -> state.value = ObdMerge.merge(state.value, obd) }, { state.value }).collect {} }
        advanceTimeBy(ms)
        return a
    }

    private fun module(json: JSONObject, name: String): JSONObject {
        val mods = json.getJSONObject("mode22Replies").getJSONArray("modules")
        return (0 until mods.length()).map { mods.getJSONObject(it) }.first { it.getString("module") == name }
    }

    @Test fun `lost replies are counted per module as no reply, answered ones by their time`() = runTest {
        val a = run(80_000L, pcmDropEvery = 5, bcmDropEvery = 3)
        val now = testScheduler.currentTime
        val json = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))
        for ((name, id) in listOf("PCM" to "7E0", "BCM" to "726")) {
            val reads = a.reads.filter { it.first == id }
            val lost = reads.filter { it.third }
            val lostLongAgo = lost.count { now - it.second > ReplyCount.NO_REPLY_MS }
            val m = module(json, name)
            assertTrue("$name: polled ($reads)", reads.size >= 10 && lost.isNotEmpty())
            assertEquals("$name requests = the reads written", reads.size, m.getInt("requests"))
            assertEquals("$name no reply = the lost replies older than the window", lostLongAgo, m.getInt("noReply"))
            assertEquals("$name answered = positive + rejected", reads.size - lost.size, m.getInt("positive") + m.getInt("negative"))
            assertEquals("$name: the rest still waiting", lost.size - lostLongAgo, m.getInt("waiting"))
            assertEquals("$name: a reply logged before its request's line is that request's answer", 0, m.getInt("unmatched"))
            val latency = m.getJSONArray("latency").let { l -> (0 until l.length()).map { l.getInt(it) } }
            assertEquals("$name: every answer has its time", m.getInt("positive") + m.getInt("negative"), latency.sum())
            assertTrue("$name: answers in the first bucket ($latency)", latency[0] >= m.getInt("positive") / 2)
        }
        assertTrue("KR2's rejections are replies", module(json, "PCM").getInt("negative") > 0)
        assertTrue("a run of misses is named", module(json, "PCM").getInt("longestMissRun") >= 1)
        assertTrue("KR2 always answers (a rejection in its own write's time), so it is never the DID named as missed",
            module(json, "PCM").getString("longestMissRunDid") != "03ED")
        assertEquals("additive: the schema version is unchanged", 1, json.getInt("exportSchemaVersion"))

        val pcm = module(json, "PCM")
        val text = DiagnosticReportBuilder.buildSummaryText("t")
        assertTrue(text, text.contains("─── MODE 22 REPLIES"))
        assertTrue(text, Regex("""PCM\s+: ${pcm.getInt("requests")} requests · ${pcm.getInt("positive")} answered · """ +
            """${pcm.getInt("negative")} rejected · ${pcm.getInt("noReply")} no reply \(\d+\.\d %\)""").containsMatchIn(text))
    }

    @Test fun `with every reply delivered, no module counts a request without a reply`() = runTest {
        run(80_000L, pcmDropEvery = 0, bcmDropEvery = 0)
        val mods = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("mode22Replies").getJSONArray("modules")
        assertTrue("PCM, BCM, AWD and the extended-session modules were asked", mods.length() >= 3)
        for (i in 0 until mods.length()) {
            val m = mods.getJSONObject(i)
            assertTrue(m.toString(), m.getInt("requests") > 0)
            assertEquals(m.toString(), 0, m.getInt("noReply"))
            assertEquals(m.toString(), 0, m.getInt("unmatched"))
        }
    }

    @Test fun `the count starts empty with every session`() = runTest {
        run(40_000L, pcmDropEvery = 2, bcmDropEvery = 2)
        DiagnosticLogger.sessionStart("next", 0, prefs = null)
        val json = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))
        assertEquals(0, json.getJSONObject("mode22Replies").getJSONArray("modules").length())
        assertTrue(DiagnosticReportBuilder.buildSummaryText("t").contains("no Mode 22 request written"))
    }
}
