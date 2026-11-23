package com.openrs.dash

import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.CrashTelemetryBuffer
import com.openrs.dash.diagnostics.HeapSample
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.PrintStream
import java.io.PrintWriter

/**
 * #511: a crash on a full heap must still leave a crash file. The flush writes a minimal record (exception, message,
 * stack, thread, build, heap) before it formats any snapshot, then replaces it whole with the full record. When the
 * snapshots fail (an OutOfMemoryError while formatting them) the minimal record stays, marked
 * `"snapshotsWritten": false`; the full record is unchanged and carries no such field.
 *
 * The OutOfMemoryError is thrown by the snapshot seam, not by a real full heap: a JVM test cannot fill the heap
 * reliably, and the throw lands at the same point of the flush.
 */
class CrashRecordFullHeapTest {

    @get:Rule val tmp = TemporaryFolder()

    private val heap = HeapSample(usedKb = 262_000, totalKb = 262_144, maxKb = 262_144, nativeKb = 30_000)

    private fun crashFile(): File = File(tmp.root, "crash_telemetry_test.json")   // does not exist before the flush

    private fun flush(
        file: File,
        exception: Throwable = OutOfMemoryError("Failed to allocate"),
        appendSnapshot: (StringBuilder, Long, VehicleState) -> Unit = CrashTelemetryBuffer::appendSnapshotJson,
    ) = CrashTelemetryBuffer.flushToFile(
        file, exception, "binder:1", "2.2.7-test", versionCode = 2612010, sourceCommit = "abc1234",
        buildType = "release", heap = heap, appendSnapshot = appendSnapshot,
    )

    private fun assertMinimalRecord(j: JSONObject, withStack: Boolean = true) {
        assertEquals("java.lang.OutOfMemoryError", j.getString("exception"))
        assertEquals("Failed to allocate", j.getString("message"))
        if (withStack) {
            assertTrue("stack names the throwing test", j.getString("stackTrace").contains("CrashRecordFullHeapTest"))
        }
        assertEquals("binder:1", j.getString("thread"))
        assertEquals("2.2.7-test", j.getString("appVersion"))
        assertEquals(2612010, j.getInt("versionCode"))
        assertEquals("abc1234", j.getString("sourceCommit"))
        assertEquals("release", j.getString("buildType"))
        assertEquals(262_144L, j.getLong("heapMaxKb"))
        assertTrue(j.has("crashedAt"))
        assertFalse("says the snapshots are missing", j.getBoolean("snapshotsWritten"))
        assertFalse("no snapshot count that reads as zero snapshots", j.has("snapshotCount"))
        assertFalse(j.has("snapshots"))
    }

    @Test fun `snapshot formatting runs out of memory - the minimal record is still written`() {
        CrashTelemetryBuffer.push(VehicleState(rpm = 2500.0))
        val file = crashFile()

        flush(file, appendSnapshot = { _, _, _ -> throw OutOfMemoryError("Failed to allocate a 74 KB string") })

        assertTrue("a crash file exists", file.exists())
        assertMinimalRecord(JSONObject(file.readText()))
    }

    @Test fun `the minimal record is on disk before the first snapshot is formatted`() {
        CrashTelemetryBuffer.push(VehicleState(rpm = 2500.0))
        val file = crashFile()
        var seenAtFirstSnapshot: String? = null

        flush(file, appendSnapshot = { sb, ts, s ->
            if (seenAtFirstSnapshot == null) seenAtFirstSnapshot = if (file.exists()) file.readText() else ""
            CrashTelemetryBuffer.appendSnapshotJson(sb, ts, s)
        })

        assertMinimalRecord(JSONObject(seenAtFirstSnapshot!!))
        val full = JSONObject(file.readText())
        assertFalse("the full record replaced it", full.has("snapshotsWritten"))
        assertTrue(full.getJSONArray("snapshots").length() >= 1)
    }

    @Test fun `a failure part-way through the snapshots leaves the minimal record whole, and no part file`() {
        repeat(5) { CrashTelemetryBuffer.push(VehicleState(rpm = 1000.0 + it)) }
        val file = crashFile()
        var calls = 0

        flush(file, appendSnapshot = { sb, ts, s ->
            if (++calls == 3) throw OutOfMemoryError("Failed to allocate")
            CrashTelemetryBuffer.appendSnapshotJson(sb, ts, s)
        })

        assertMinimalRecord(JSONObject(file.readText()))
        assertEquals("only the crash file is left", listOf(file.name), tmp.root.list()!!.toList())
    }

    @Test fun `a stack trace that cannot be built - the record keeps the exception and the build without it`() {
        CrashTelemetryBuffer.push(VehicleState())
        val file = crashFile()
        val noStack = object : OutOfMemoryError("Failed to allocate") {
            override fun printStackTrace(s: PrintStream) = throw OutOfMemoryError("stack")
            override fun printStackTrace(s: PrintWriter) = throw OutOfMemoryError("stack")
        }

        flush(file, exception = noStack, appendSnapshot = { _, _, _ -> throw OutOfMemoryError("snapshots") })

        val j = JSONObject(file.readText())
        assertTrue(j.getString("exception").startsWith("com.openrs.dash.CrashRecordFullHeapTest"))
        assertEquals("Failed to allocate", j.getString("message"))
        assertFalse(j.has("stackTrace"))
        assertEquals("2.2.7-test", j.getString("appVersion"))
        assertEquals("abc1234", j.getString("sourceCommit"))
        assertFalse(j.getBoolean("snapshotsWritten"))
    }

    @Test fun `normal flush - the full record as before, no minimal marker, no part file`() {
        CrashTelemetryBuffer.push(VehicleState(rpm = 6500.0, speedKph = 200.0))
        val file = crashFile()

        flush(file)

        val text = file.readText()
        val j = JSONObject(text)
        assertFalse(j.has("snapshotsWritten"))
        assertEquals(j.getInt("snapshotCount"), j.getJSONArray("snapshots").length())
        val last = text.lines().last { it.startsWith("    {") }
        assertTrue(last, last.matches(Regex(
            """    \{"ts":\d+,"rpm":6500\.0,"speedKph":200\.0,"boostKpa":[-\d.]+,"throttle":[-\d.]+,"pedal":[-\d.]+,""" +
                """"brake":[-\d.]+,"coolant":[-\d.]+,"oilTemp":[-\d.]+,"gear":-?\d+,"latG":[-\d.]+,"lonG":[-\d.]+,""" +
                """"steer":[-\d.]+,"battV":[-\d.]+,"esc":"[^"]*","mode":"[^"]*"}"""
        )))
        assertEquals("only the crash file is left", listOf(file.name), tmp.root.list()!!.toList())
    }
}
