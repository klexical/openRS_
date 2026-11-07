package com.openrs.dash

import com.openrs.dash.can.Mode22
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/** #253 / #263: what QA's read of the coverage batch found in the counts and logs. Production code, synthetic frames. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class LinkCountsFollowUpTest {
    @get:Rule val tmp = TemporaryFolder()
    @Before fun session() { ShadowLog.clear(); DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    private fun summary() = DiagnosticReportBuilder.buildSummaryText("t")
    private fun limitsBlock() = summary().lines().dropWhile { !it.startsWith("─── LOG LIMITS") }.drop(1).takeWhile { it.isNotBlank() }
    private fun field(name: String) = DiagnosticLogger::class.java.getDeclaredField(name).apply { isAccessible = true }

    // (1) A first frame answers a request only if it could be one: 8 bytes, and a length a single frame couldn't carry
    @Test fun `a corrupt first frame names no DID - too short, or a length under 8`() {
        assertNull("DLC 5", Mode22.positiveDid(b(0x10, 0x0A, 0x62, 0x03, 0x3E)))
        assertNull("DLC 7", Mode22.positiveDid(b(0x10, 0x0A, 0x62, 0x03, 0x3E, 0x40, 0)))
        assertNull("FF_DL 7", Mode22.positiveDid(b(0x10, 0x07, 0x62, 0x03, 0x3E, 0x40, 0, 0)))
        assertNull("FF_DL 0", Mode22.positiveDid(b(0x10, 0x00, 0x62, 0x03, 0x3E, 0x40, 0, 0)))
    }

    @Test fun `a whole first frame keeps its DID, at the shortest and longest length`() {
        assertEquals(0x033E, Mode22.positiveDid(b(0x10, 0x08, 0x62, 0x03, 0x3E, 0x40, 0, 0)))
        assertEquals(0x033E, Mode22.positiveDid(b(0x1F, 0xFF, 0x62, 0x03, 0x3E, 0x40, 0, 0)))
    }

    // (2) "Nothing dropped" can't sit beside a count of drops
    @Test fun `LOG LIMITS doesn't say nothing was reached while Buffers reports drops`() {
        for (count in listOf<() -> Unit>({ DiagnosticLogger.countOverlongLine() }, { DiagnosticLogger.countCommandReplyDropped() },
            { DiagnosticLogger.countDtcFrameDropped() }, { DiagnosticLogger.countLineDropped() })) {
            DiagnosticLogger.sessionStart("test", 0, prefs = null)
            count()
            val block = limitsBlock().joinToString("\n")
            assertFalse(block, block.contains("no counted limit reached"))
            assertTrue(block, block.contains("see Buffers"))
        }
    }

    @Test fun `LOG LIMITS says nothing was reached when that is so`() {
        assertTrue(limitsBlock().toString(), limitsBlock().any { it.contains("no counted limit reached") })
    }

    // (3) A refused reply is counted, its bytes stay out of logcat, which ships in release
    @Test fun `a refused reply's bytes never reach logcat`() {
        ObdResponseParser.parsePcmResponse(b(0x07, 0x62, 0xC0, 0xFF, 0xEE), VehicleState()) { }
        ObdResponseParser.parsePcmResponse(b(0x03, 0x62, 0xC0), VehicleState()) { }
        val text = ShadowLog.getLogs().joinToString("\n") { it.msg }
        assertTrue("the path ran", text.contains("PCM"))
        for (marker in listOf("C0", "FF EE")) assertFalse(text, text.contains(marker))
    }

    // (4) A failed last flush loses lines too
    private fun writerFailingOnFlush() = object : java.io.BufferedWriter(java.io.StringWriter()) {
        override fun flush() { throw java.io.IOException("disk full") }
    }

    @Test fun `a failed flush is reported, from flushSlcan and from the session's end`() {
        for (end in listOf<() -> Unit>({ DiagnosticLogger.flushSlcan() }, { DiagnosticLogger.sessionEnd() })) {
            DiagnosticLogger.sessionStart("test", 0, prefs = null, logDir = tmp.newFolder())
            field("slcanWriter").set(null, writerFailingOnFlush())
            end()
            val limits = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("logLimits")
            assertTrue(limits.toString(), limits.getBoolean("slcanWriteFailed"))
            assertEquals(1, DiagnosticLogger.sessionEvents.count { it.type == "SLCAN" && it.message.contains("write failed") })
        }
    }

    // (5) The handshake reads lines before the frame loop; a refused frame line there is still a refused frame line
    @Test fun `frame lines refused during the handshake are counted, other lines aren't`() = runTest {
        val rows = listOf("t0F88C0", "T0F8", "noise", "t09080000C1FF617D0000", "t0F88005A000000000082")
            .mapIndexed { i, l -> ReplayRow(100L + i * 50L, "RX", l) }
        ReplayRun(this, rows)
        advanceTimeBy(5_000L)
        assertEquals(2, DiagnosticLogger.linkIntegrity.refusedFrameLines)
    }

    // (6) "Mode 22 reply(ies) refused" counts Mode 22 replies
    @Test fun `a short frame that isn't a Mode 22 reply isn't counted as a refused reply`() {
        val notReplies = listOf(b(0x03, 0x7F, 0x22, 0x31), b(0x21, 0x01), b(0x10), b(0x30, 0x00, 0x00))
        for (d in notReplies) {
            ObdResponseParser.parsePcmResponse(d, VehicleState()) { }
            ObdResponseParser.parseBcmResponse(d, VehicleState()) { }
            ObdResponseParser.parseAwdResponse(d, VehicleState()) { }
        }
        assertEquals(0, DiagnosticLogger.linkIntegrity.refusedReplies)
    }

    @Test fun `a short Mode 22 reply is still counted`() {
        ObdResponseParser.parsePcmResponse(b(0x03, 0x62, 0x03, 0x3E), VehicleState()) { }
        assertEquals(1, DiagnosticLogger.linkIntegrity.refusedReplies)
    }
}
