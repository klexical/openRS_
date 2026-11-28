package com.openrs.dash

import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.TimingCounts
import com.openrs.dash.diagnostics.UiSample
import com.openrs.dash.diagnostics.UiSampler
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #630: each FPS-timeline sample carries the UI timing since the previous sample — main-thread wakes and the late ones,
 * frames and the slow ones, the settled tab and whether the TRIP map exists — so an export can place lag in time and by
 * tab. Built across a tab change through the real logger and the real builder; the summary's roll-up from synthetic
 * samples. Synthetic counts only, offsets only.
 */
@RunWith(RobolectricTestRunner::class)
class UiSampleExportTest {
    private val ms = 1_000_000L

    @Before fun session() { reset(); DiagnosticLogger.sessionStart("test", 0, prefs = null) }
    @After fun endSession() { reset(); DiagnosticLogger.sessionStart("test", 0, prefs = null) }
    private fun reset() { DiagnosticLogger.settledPage = 0; DiagnosticLogger.tripMapCreated = false }

    /** One main-thread wake late by more than 50 ms (the lag counter reads System.nanoTime). */
    private fun lateWake() {
        DiagnosticLogger.mainThreadLag.asking(); Thread.sleep(170); DiagnosticLogger.mainThreadLag.woke()
    }

    private fun onTimeWake() { DiagnosticLogger.mainThreadLag.asking(); DiagnosticLogger.mainThreadLag.woke() }

    @Test fun `detail - per-sample counts across a tab change, the TRIP map named from its creation`() {
        // On DRIVE: 3 wakes (none late), 4 frames, one over 32 ms
        repeat(3) { onTimeWake() }
        listOf(10L, 16L, 40L, 20L).forEach { DiagnosticLogger.frameTimes.frame(0, it * ms) }
        DiagnosticLogger.fps(1400.0, heap = null)
        // To TRIP, map made: 2 wakes (one late), 3 frames: one over 32, one over 100
        DiagnosticLogger.settledPage = 3; DiagnosticLogger.tripMapCreated = true
        onTimeWake(); lateWake()
        listOf(12L, 50L, 150L).forEach { DiagnosticLogger.frameTimes.frame(3, it * ms) }
        DiagnosticLogger.fps(1300.0, heap = null)
        // Back on DRIVE with the map retained, nothing new
        DiagnosticLogger.settledPage = 0
        DiagnosticLogger.fps(1350.0, heap = null)

        val (text, detail) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
        val tl = JSONObject(detail).getJSONArray("fpsTimeline")
        assertEquals(3, tl.length())
        fun row(i: Int) = tl.getJSONObject(i).let { p ->
            listOf(p.getLong("uiMainWakes"), p.getLong("uiMainLateOver50"), p.getLong("uiFrames"),
                p.getLong("uiFramesOver32"), p.getLong("uiFramesOver100"), p.getString("uiTab"), p.getBoolean("uiTripMapCreated"))
        }
        assertEquals(listOf<Any>(3L, 0L, 4L, 1L, 0L, "DRIVE", false), row(0))
        assertEquals(listOf<Any>(2L, 1L, 3L, 2L, 1L, "TRIP", true), row(1))
        assertEquals(listOf<Any>(0L, 0L, 0L, 0L, 0L, "DRIVE", true), row(2))
        // The existing keys stay as they were, and a sample with no heap reading still has none
        assertEquals(1300.0, tl.getJSONObject(1).getDouble("fps"), 0.0)
        assertFalse(tl.getJSONObject(1).has("heapUsedKb"))

        // The summary's roll-up: one minute, both tabs, the map there at its last sample
        val lines = text.lines()
        val i = lines.indexOfFirst { it.startsWith("  UI per minute") }
        assertTrue("the FPS TIMELINE section has the UI roll-up", i >= 0)
        assertEquals("    +0:00  1/5 · 3/1/7 · DRIVE, TRIP · yes", lines[i + 1])
    }

    @Test fun `a new session starts the per-sample counts from zero`() {
        repeat(4) { onTimeWake() }
        DiagnosticLogger.frameTimes.frame(0, 200 * ms)
        DiagnosticLogger.fps(1400.0, heap = null)
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        onTimeWake()
        DiagnosticLogger.fps(1400.0, heap = null)
        val p = JSONObject(DiagnosticReportBuilder.buildSummaryAndDetail("t").second).getJSONArray("fpsTimeline").getJSONObject(0)
        assertEquals(1L, p.getLong("uiMainWakes"))
        assertEquals(0L, p.getLong("uiFrames"))
        assertEquals(0L, p.getLong("uiFramesOver100"))
    }

    @Test fun `sampler - a total that went down counts from zero, never negative`() {
        val s = UiSampler()
        fun main(n: Long, late: Long) = TimingCounts(n, mapOf(50 to late, 200 to 0L, 700 to 0L), 0)
        fun frames(n: Long) = mapOf("DRIVE" to TimingCounts(n, mapOf(32 to 1L, 100 to 0L, 700 to 0L), 0))
        s.take(main(10, 2), frames(20), 0, false)
        val after = s.take(main(3, 1), frames(5), 0, false)   // counters reset between two reads
        assertEquals(UiSample(3, 1, 5, 1, 0, "DRIVE", false), after)
        assertEquals(null, s.take(main(3, 1), frames(5), 9, false).tab)   // a page outside the five names no tab
    }

    private fun pt(relMs: Long, late: Long, tab: String?, map: Boolean) =
        DiagnosticLogger.FpsPoint(relMs, 1400.0, null, UiSample(600, late, 3000, 10, 2, tab, map))

    @Test fun `roll-up - one row per minute, tabs in pager order, the map at the minute's last sample`() {
        val lines = DiagnosticReportBuilder.uiRollUpLines(listOf(
            pt(3_000, 1, "TRIP", true), pt(6_000, 2, "DRIVE", false),
            pt(61_000, 5, "GARAGE", false),
            pt(185_000, 0, null, true),
            DiagnosticLogger.FpsPoint(190_000, 1400.0),   // no UI timing: left out
        ))
        assertEquals(listOf(
            "  UI per minute (late wakes > 50 ms / wakes · frames > 32 / > 100 ms / frames · tabs · TRIP map):",
            "    +0:00  3/1200 · 20/4/6000 · DRIVE, TRIP · no",
            "    +0:01  5/600 · 10/2/3000 · GARAGE · no",
            "    +0:03  0/600 · 10/2/3000 · — · yes",
        ), lines)
    }

    @Test fun `roll-up - a long session widens its rows so it prints at most 60`() {
        val lines = DiagnosticReportBuilder.uiRollUpLines((0 until 2400).map { pt(it * 3_000L, 0, "DRIVE", false) })   // 2 h
        assertEquals("  UI per 2 minutes (late wakes > 50 ms / wakes · frames > 32 / > 100 ms / frames · tabs · TRIP map):", lines[0])
        assertEquals(61, lines.size)
        assertEquals("    +0:02  0/24000 · 400/80/120000 · DRIVE · no", lines[2])
        assertEquals("    +1:58  0/24000 · 400/80/120000 · DRIVE · no", lines[60])
    }

    @Test fun `no sample with UI timing - no roll-up`() {
        assertEquals(emptyList<String>(), DiagnosticReportBuilder.uiRollUpLines(listOf(DiagnosticLogger.FpsPoint(0, 1400.0))))
    }
}
