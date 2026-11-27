package com.openrs.dash

import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.CrashTelemetryBuffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * #521: the crash record covers a fixed time before the crash, whatever the state emission rate. The buffer keeps at
 * most one snapshot per [CrashTelemetryBuffer.SAMPLE_INTERVAL_MS] slot (the newest state in it), so its 300 snapshots
 * span the last 30 s at 10 Hz; before, every emission took a slot, and at this car's rate 300 covered under 6 s.
 */
class CrashBufferWindowTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun snapshots(): List<JSONObject> {
        val file = tmp.newFile()
        CrashTelemetryBuffer.flushToFile(file, RuntimeException("t"), "main", "test")
        val o = JSONObject(file.readText())
        val a = o.getJSONArray("snapshots")
        assertEquals(o.getInt("snapshotCount"), a.length())
        return (0 until a.length()).map { a.getJSONObject(it) }
    }

    @Test fun `2000 emissions a second for 60 s - the record covers the last 30 s at 10 Hz`() {
        val start = 1_000_000L
        val end = start + 60_000L
        var n = 0
        for (ms in start until end) {           // two emissions every millisecond
            repeat(2) { CrashTelemetryBuffer.push(VehicleState(rpm = (n++ % 7000).toDouble()), nowMs = ms) }
        }
        val last = end - 1

        val snaps = snapshots()
        val ts = snaps.map { it.getLong("ts") }
        assertEquals("30 s at 10 Hz", 300, snaps.size)
        assertEquals("the newest snapshot is the last state pushed", last, ts.last())
        assertEquals("its values are the last state's", ((n - 1) % 7000).toDouble(), snaps.last().getDouble("rpm"), 0.0)
        assertTrue("the oldest is within 30 s of the crash, was ${last - ts.first()} ms", last - ts.first() < 30_000)
        assertTrue("and it covers nearly all of them, was ${last - ts.first()} ms", last - ts.first() >= 29_900)
        ts.zipWithNext().forEach { (a, b) -> assertEquals("one snapshot every 100 ms", 100L, b - a) }
    }

    @Test fun `a slow emitter keeps every emission`() {
        val start = 5_000_000L
        repeat(300) { CrashTelemetryBuffer.push(VehicleState(rpm = it.toDouble()), nowMs = start + it * 250L) }

        val rpms = snapshots().map { it.getDouble("rpm").toInt() }
        assertEquals((0 until 300).toList(), rpms)
    }

    @Test fun `emissions within one slot keep the newest state`() {
        val t = 9_000_000L
        CrashTelemetryBuffer.push(VehicleState(rpm = 1.0), nowMs = t)          // its own slot
        CrashTelemetryBuffer.push(VehicleState(rpm = 2.0), nowMs = t + 100)
        CrashTelemetryBuffer.push(VehicleState(rpm = 3.0), nowMs = t + 150)
        CrashTelemetryBuffer.push(VehicleState(rpm = 4.0), nowMs = t + 199)

        val tail = snapshots().takeLast(2)
        assertEquals(listOf(1.0, 4.0), tail.map { it.getDouble("rpm") })
        assertEquals(listOf(t, t + 199), tail.map { it.getLong("ts") })
    }
}
