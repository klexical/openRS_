package com.openrs.dash

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.diagnostics.DiagnosticExporter
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * #511 review: "clear crash history" also removes a `crash_telemetry_*.json.part` the crash handler left when the
 * process was killed while it wrote a full record. Those are never listed or exported, and nothing else in the
 * diagnostics folder is touched.
 */
@RunWith(RobolectricTestRunner::class)
class CrashHistoryClearTest {
    private val ctx: Context = ApplicationProvider.getApplicationContext()
    private val dir = File(ctx.filesDir, "diagnostics")

    @After fun cleanUp() { dir.deleteRecursively() }

    @Test fun `clear removes crash records and their leftover part files, nothing else`() {
        dir.mkdirs()
        val record = File(dir, "crash_telemetry_20260101_000000.json").apply { writeText("{}") }
        val second = File(dir, "crash_telemetry_20260101_000000_2.json").apply { writeText("{}") }
        val part = File(dir, "crash_telemetry_20260101_000001.json.part").apply { writeText("{") }
        val other = File(dir, "diagnostic_summary_20260101_000000.txt").apply { writeText("keep") }

        val listed = DiagnosticExporter.crashFiles(ctx).map { it.name }.sorted()
        assertEquals("the part file is never listed", listOf(record.name, second.name).sorted(), listed)
        DiagnosticExporter.clearCrashHistory(ctx)

        assertFalse(record.exists())
        assertFalse(second.exists())
        assertFalse("the leftover part file is removed", part.exists())
        assertEquals("keep", other.readText())
    }
}
