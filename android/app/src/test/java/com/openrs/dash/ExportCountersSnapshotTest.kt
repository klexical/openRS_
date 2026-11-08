package com.openrs.dash

import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.concurrent.thread

/**
 * L12: one export reports one reading of its counters. The text and the JSON read them separately, so a live export's summary and detail
 * disagreed, which the hardware checker reads as li_disagree. Real builders. A thread moves the counters while they run: red is a matter of
 * timing, but with one snapshot the two can't differ at all, so green is certain.
 */
@RunWith(RobolectricTestRunner::class)
class ExportCountersSnapshotTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun sentInText(text: String) = Regex("""Writes\s*:\s*(\d+) sent""").find(text)!!.groupValues[1].toInt()

    @Test fun `the summary and the detail read the same write count while the counters move`() {
        var stop = false
        val mover = thread { while (!stop) DiagnosticLogger.countWrite(DiagnosticLogger.WRITE_SENT) }
        val differ = try {
            (1..40).count {
                val (text, detail) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
                sentInText(text) != JSONObject(detail).getJSONObject("linkIntegrity").getJSONObject("writes").getInt("sent")
            }
        } finally { stop = true; mover.join() }
        assertEquals("exports whose summary and detail disagree, of 40", 0, differ)
    }

    @Test fun `the summary and the detail read the same masked count while frames arrive`() {
        var stop = false
        val mover = thread { while (!stop) DiagnosticLogger.logUnknownFrame(0x405, byteArrayOf(0x11, 1, 2, 3, 4, 5, 6, 7)) }
        val differ = try {
            (1..40).count {
                val (text, detail) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
                val t = Regex("""(\d+) VIN-bearing frame""").find(text)?.groupValues?.get(1)?.toLong() ?: 0L
                t != JSONObject(detail).getJSONObject("logLimits").getLong("vinFramesMaskedPage")
            }
        } finally { stop = true; mover.join() }
        assertEquals("exports whose summary and detail disagree, of 40", 0, differ)
    }

    @Test fun `control - a later export reads the newer count`() {
        DiagnosticLogger.countWrite(DiagnosticLogger.WRITE_SENT)
        val (a, _) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
        DiagnosticLogger.countWrite(DiagnosticLogger.WRITE_SENT)
        val (b, _) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
        assertEquals(sentInText(a) + 1, sentInText(b))
    }
}
