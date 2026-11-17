package com.openrs.dash

import com.openrs.dash.can.BleDelivery
import com.openrs.dash.can.BleDeliveryCounts
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.TimingCounts
import com.openrs.dash.diagnostics.UiTimingRecord
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #421: the `uiTiming` block of the diagnostic export, read from the two files the real builder writes — the JSON fields,
 * the "UI TIMING" summary section, the block absent before the first connection and the Bluetooth part absent for a
 * session that is not Bluetooth — and the logger's one snapshot behind both (reset at session start, Bluetooth counts
 * summed over a session's attempts). Synthetic counts only.
 */
@RunWith(RobolectricTestRunner::class)
class UiTimingExportTest {
    private val ms = 1_000_000L

    private fun counts(n: Long, over: List<Long>, edges: List<Int>, longestNanos: Long) =
        TimingCounts(n, edges.zip(over).toMap(), longestNanos)

    private fun record(ble: BleDelivery?) = UiTimingRecord(
        mainThread = counts(1200, listOf(3, 1, 0), listOf(50, 200, 700), 250 * ms),
        frames = linkedMapOf(
            "DRIVE" to counts(900, listOf(12, 2, 0), listOf(32, 100, 700), 120_500_000L),
            "PERF" to counts(40, listOf(0, 0, 0), listOf(32, 100, 700), 16 * ms),
            "THERMAL" to counts(0, listOf(0, 0, 0), listOf(32, 100, 700), 0),
            "TRIP" to counts(0, listOf(0, 0, 0), listOf(32, 100, 700), 0),
            "GARAGE" to counts(5, listOf(1, 1, 1), listOf(32, 100, 700), 900 * ms),
        ),
        mergeLockWait = counts(300, listOf(4, 0, 0), listOf(5, 50, 200), 7_200_000L),
        bluetooth = ble,
    )

    private fun export(r: UiTimingRecord?): Pair<List<String>, JSONObject> {
        val c = DiagnosticLogger.ExportCounters(DiagnosticLogger.LinkIntegrity(), 0, DiagnosticLogger.LogLimits(), uiTiming = r)
        val (text, detail) = DiagnosticReportBuilder.buildSummaryAndDetail("t", counters = c)
        return section(text) to JSONObject(detail)
    }

    /** The UI TIMING section's lines, header to the blank line after it. */
    private fun section(text: String): List<String> {
        val lines = text.lines()
        val start = lines.indexOfFirst { it.startsWith("─── UI TIMING") }
        if (start < 0) return emptyList()
        return lines.drop(start).takeWhile { it.isNotBlank() }
    }

    @Test fun `summary - one line per measure, five tabs, Bluetooth delivery`() {
        val (s, _) = export(record(BleDelivery(5000, 9000, 6)))
        assertEquals(listOf(
            "─── UI TIMING ─────────────────────────────────────────────",
            "  Main thread   : 1200 wakes of 100 ms · late > 50 ms 3 · > 200 ms 1 · > 700 ms 0 · longest 250.0 ms",
            "  Frames DRIVE  : 900 · > 32 ms 12 · > 100 ms 2 · > 700 ms 0 · longest 120.5 ms",
            "  Frames PERF   : 40 · > 32 ms 0 · > 100 ms 0 · > 700 ms 0 · longest 16.0 ms",
            "  Frames THERMAL: 0 · > 32 ms 0 · > 100 ms 0 · > 700 ms 0 · longest 0.0 ms",
            "  Frames TRIP   : 0 · > 32 ms 0 · > 100 ms 0 · > 700 ms 0 · longest 0.0 ms",
            "  Frames GARAGE : 5 · > 32 ms 1 · > 100 ms 1 · > 700 ms 1 · longest 900.0 ms",
            "  OBD merges    : 300 · lock wait > 5 ms 4 · > 50 ms 0 · > 200 ms 0 · longest 7.2 ms",
            "  Bluetooth     : 5000 notifications · 9000 SLCAN lines · most in one notification 6",
            "  Counts since this connection started (frames by the settled tab); no clock times",
        ), s)
    }

    @Test fun `detail - the uiTiming block carries the same numbers`() {
        val (_, j) = export(record(BleDelivery(5000, 9000, 6)))
        assertTrue("the detail carries a uiTiming block", j.has("uiTiming"))
        val u = j.getJSONObject("uiTiming")
        val main = u.getJSONObject("mainThread")
        assertEquals(100, main.getInt("periodMs")); assertEquals(1200L, main.getLong("samples"))
        assertEquals(listOf(3L, 1L, 0L), listOf("50", "200", "700").map { main.getJSONObject("overMs").getLong(it) })
        assertEquals(250.0, main.getDouble("longestMs"), 0.0)
        val frames = u.getJSONObject("frames")
        assertEquals(listOf("DRIVE", "PERF", "THERMAL", "TRIP", "GARAGE"), frames.keys().asSequence().toList())
        val drive = frames.getJSONObject("DRIVE")
        assertEquals(900L, drive.getLong("frames"))
        assertEquals(listOf(12L, 2L, 0L), listOf("32", "100", "700").map { drive.getJSONObject("overMs").getLong(it) })
        assertEquals(120.5, drive.getDouble("longestMs"), 0.0)
        val lock = u.getJSONObject("mergeLockWait")
        assertEquals(300L, lock.getLong("merges"))
        assertEquals(listOf(4L, 0L, 0L), listOf("5", "50", "200").map { lock.getJSONObject("overMs").getLong(it) })
        assertEquals(7.2, lock.getDouble("longestMs"), 0.0)
        val bt = u.getJSONObject("bluetooth")
        assertEquals(listOf(5000L, 9000L, 6L),
            listOf("notifications", "slcanLines", "mostLinesInOneNotification").map { bt.getLong(it) })
    }

    @Test fun `a session that is not Bluetooth has no Bluetooth part in either file`() {
        val (s, j) = export(record(null))
        assertTrue("the detail carries a uiTiming block", j.has("uiTiming"))
        assertFalse(s.any { it.trimStart().startsWith("Bluetooth") })
        assertTrue(s.any { it.trimStart().startsWith("Main thread") })
        val u = j.getJSONObject("uiTiming")
        assertFalse(u.has("bluetooth")); assertTrue(u.has("mainThread"))
    }

    @Test fun `before the first connection the detail has no block and the summary says nothing was counted`() {
        val (s, j) = export(null)
        assertFalse(j.has("uiTiming"))
        assertEquals(listOf(
            "─── UI TIMING ─────────────────────────────────────────────",
            "  no connection yet in this app run: nothing counted",
        ), s)
    }

    // ── The logger's snapshot (the live export path) ─────────────────────────

    @Test fun `live export - a new session starts every counter at zero and both files agree`() {
        DiagnosticLogger.sessionStart("Bluetooth", 0, prefs = null, transport = "Bluetooth (MeatPi Pro)")
        DiagnosticLogger.linkLockWait.waited(0, 60 * ms)
        DiagnosticLogger.frameTimes.frame(0, 40 * ms)
        DiagnosticLogger.mainThreadLag.asking(); DiagnosticLogger.mainThreadLag.woke()
        val first = BleDeliveryCounts().apply { notified(3) }
        DiagnosticLogger.connectionAttempt(null, first)
        assertNotNull(DiagnosticLogger.exportCounters.uiTiming)
        assertEquals(1L, DiagnosticLogger.exportCounters.uiTiming!!.mergeLockWait.count)

        DiagnosticLogger.sessionStart("Bluetooth", 0, prefs = null, transport = "Bluetooth (MeatPi Pro)")
        val u = DiagnosticLogger.exportCounters.uiTiming!!
        assertEquals(0L, u.mergeLockWait.count); assertEquals(0L, u.mainThread.count)
        assertEquals(0L, u.frames.values.sumOf { it.count })
        assertNull("no attempt yet in this session", u.bluetooth)

        DiagnosticLogger.linkLockWait.waited(0, 60 * ms)
        val (text, detail) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
        val line = section(text).first { it.trimStart().startsWith("OBD merges") }
        assertEquals("  OBD merges    : 1 · lock wait > 5 ms 1 · > 50 ms 1 · > 200 ms 0 · longest 60.0 ms", line)
        val lock = JSONObject(detail).getJSONObject("uiTiming").getJSONObject("mergeLockWait")
        assertEquals(1L, lock.getLong("merges")); assertEquals(60.0, lock.getDouble("longestMs"), 0.0)
    }

    @Test fun `live export - Bluetooth counts add up over a session's attempts, the most lines is the largest`() {
        DiagnosticLogger.sessionStart("Bluetooth", 0, prefs = null, transport = "Bluetooth (MeatPi Pro)")
        val a1 = BleDeliveryCounts().apply { notified(2); notified(5) }
        DiagnosticLogger.connectionAttempt(null, a1)
        val a2 = BleDeliveryCounts().apply { notified(1) }
        DiagnosticLogger.connectionAttempt(null, a2)
        a2.notified(3)                                   // still counting: read at export, like the MTU trace
        assertEquals(BleDelivery(4, 11, 5), DiagnosticLogger.exportCounters.uiTiming!!.bluetooth)
    }

    @Test fun `live export - a Wi-Fi session's attempts carry no Bluetooth counts`() {
        DiagnosticLogger.sessionStart("192.0.2.1", 35000, prefs = null, transport = "TCP SLCAN (MeatPi Pro)")
        DiagnosticLogger.connectionAttempt(null, null)
        DiagnosticLogger.connectionAttempt(null, null)
        assertNull(DiagnosticLogger.exportCounters.uiTiming!!.bluetooth)
    }

    /**
     * The logger is process-wide and an earlier test class has started a session, so "before the first connection of
     * this app run" is reached by setting its session start back to 0 (restored after), as a fresh process has it.
     */
    @Test fun `live export - before the first connection the logger gives no block and the summary says nothing was counted`() {
        DiagnosticLogger.sessionStart("Bluetooth", 0, prefs = null, transport = "Bluetooth (MeatPi Pro)")
        DiagnosticLogger.linkLockWait.waited(0, 60 * ms)                 // counts exist: only the gate can hide them
        val f = DiagnosticLogger::class.java.getDeclaredField("sessionStartMs").apply { isAccessible = true }
        val saved = f.getLong(null)
        try {
            f.setLong(null, 0L)
            assertNull(DiagnosticLogger.exportCounters.uiTiming)
            val (text, detail) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
            assertFalse(JSONObject(detail).has("uiTiming"))
            assertEquals(listOf(
                "─── UI TIMING ─────────────────────────────────────────────",
                "  no connection yet in this app run: nothing counted",
            ), section(text))
        } finally {
            f.setLong(null, saved)
        }
        assertNotNull("control: the same counters export once a session has started", DiagnosticLogger.exportCounters.uiTiming)
    }

    @Test fun `frames are counted only inside a session - the flag is set at sessionStart and cleared at sessionEnd`() {
        DiagnosticLogger.sessionEnd()
        assertFalse(DiagnosticLogger.uiTimingCounting)
        DiagnosticLogger.sessionStart("Bluetooth", 0, prefs = null, transport = "Bluetooth (MeatPi Pro)")
        assertTrue(DiagnosticLogger.uiTimingCounting)
        DiagnosticLogger.frameTimes.frame(0, 40 * ms)
        DiagnosticLogger.sessionEnd()
        assertFalse(DiagnosticLogger.uiTimingCounting)
        assertEquals("the ended session's counts stay for the export", 1L,
            DiagnosticLogger.exportCounters.uiTiming!!.frames.getValue("DRIVE").count)
    }
}
