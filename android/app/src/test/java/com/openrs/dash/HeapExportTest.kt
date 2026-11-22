package com.openrs.dash

import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.CrashTelemetryBuffer
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.HeapSample
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #510: heap use beside each FPS timeline sample, read from the two files the real builder writes (the JSON point
 * fields and the summary's heap line), a sample without a reading, and the crash record's heap fields. Synthetic
 * readings only, except the one live [HeapSample.read].
 */
@RunWith(RobolectricTestRunner::class)
class HeapExportTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }
    @After fun endSession() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun export(): Pair<String, JSONObject> {
        val (text, detail) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
        return text to JSONObject(detail)
    }

    private fun heapLines(text: String) = text.lines().filter { it.startsWith("  Heap:") }

    @Test fun `detail - each fps point carries its heap reading`() {
        DiagnosticLogger.fps(1400.0, HeapSample(usedKb = 150_000, totalKb = 200_000, maxKb = 262_144, nativeKb = 40_000))
        DiagnosticLogger.fps(1450.0, HeapSample(usedKb = 250_000, totalKb = 262_144, maxKb = 262_144, nativeKb = 41_000))
        val pts = export().second.getJSONArray("fpsTimeline")
        assertEquals(2, pts.length())
        val p = pts.getJSONObject(1)
        assertEquals(1450.0, p.getDouble("fps"), 0.0)
        assertEquals(250_000L, p.getLong("heapUsedKb"))
        assertEquals(262_144L, p.getLong("heapTotalKb"))
        assertEquals(262_144L, p.getLong("heapMaxKb"))
        assertEquals(41_000L, p.getLong("nativeHeapKb"))
    }

    @Test fun `summary - one heap line with the last, the peak and the native heap`() {
        DiagnosticLogger.fps(1400.0, HeapSample(usedKb = 150_000, totalKb = 200_000, maxKb = 262_144, nativeKb = 40_000))
        DiagnosticLogger.fps(1450.0, HeapSample(usedKb = 250_000, totalKb = 262_144, maxKb = 262_144, nativeKb = 41_000))
        DiagnosticLogger.fps(1300.0, HeapSample(usedKb = 120_000, totalKb = 262_144, maxKb = 262_144, nativeKb = 39_936))
        assertEquals(
            listOf("  Heap: now 117.2 of 256.0 MB · peak 244.1 MB · native now 39.0 MB · 3 of 3 samples"),
            heapLines(export().first),
        )
    }

    @Test fun `a sample without a reading has no heap fields, and no reading at all means no heap line`() {
        DiagnosticLogger.fps(1400.0, heap = null)
        val (text, j) = export()
        val p = j.getJSONArray("fpsTimeline").getJSONObject(0)
        for (k in listOf("heapUsedKb", "heapTotalKb", "heapMaxKb", "nativeHeapKb")) assertFalse(k, p.has(k))
        assertTrue(heapLines(text).isEmpty())
    }

    @Test fun `the heap line counts only samples that carry a reading`() {
        DiagnosticLogger.fps(1400.0, heap = null)
        DiagnosticLogger.fps(1400.0, HeapSample(usedKb = 1024, totalKb = 2048, maxKb = 4096, nativeKb = 512))
        assertEquals(listOf("  Heap: now 1.0 of 4.0 MB · peak 1.0 MB · native now 0.5 MB · 1 of 2 samples"),
            heapLines(export().first))
    }

    @Test fun `the default fps call reads the live heap`() {
        DiagnosticLogger.fps(1400.0)
        val h = DiagnosticLogger.fpsTimeline.single().heap
        assertNotNull(h); h!!
        assertTrue(h.maxKb > 0)
        assertTrue(h.usedKb in 1..h.totalKb)
        assertTrue(h.totalKb <= h.maxKb)
    }

    @Test fun `crash record - heap fields at the top level`() {
        CrashTelemetryBuffer.push(VehicleState())
        val file = File.createTempFile("crash_heap_", ".json")
        try {
            CrashTelemetryBuffer.flushToFile(file, OutOfMemoryError("test"), "main", "2.2.7-test")
            val j = JSONObject(file.readText())
            assertTrue(j.getLong("heapMaxKb") > 0)
            assertTrue(j.getLong("heapUsedKb") in 1..j.getLong("heapTotalKb"))
            assertTrue(j.has("nativeHeapKb"))
        } finally {
            file.delete()
        }
    }
}
