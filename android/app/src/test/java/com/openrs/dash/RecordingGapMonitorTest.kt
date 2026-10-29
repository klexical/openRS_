package com.openrs.dash

import com.openrs.dash.data.RecordingGapMonitor
import com.openrs.dash.data.RecordingGapMonitor.Reason
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** #221 — the 2026-10-28 PM drive's gaps, explained instead of silent (and never filled in). */
class RecordingGapMonitorTest {

    @Test fun `50 s without location updates while connected is reported as it starts and when it ends`() {
        val m = RecordingGapMonitor(); m.start(0L)
        var t = 0L
        while (t <= 485_982L) { assertNull(m.onLocation(t, written = true)); t += 1_000L }
        val last = t - 1_000L
        assertNull(m.tick(last + 4_000L, connected = true, paused = false))          // within 5 s — not yet a gap
        assertNotNull(m.tick(last + 10_000L, connected = true, paused = false))      // reported once…
        assertNull(m.tick(last + 20_000L, connected = true, paused = false))         // …not repeated
        val gap = m.onLocation(last + 50_453L, written = true)!!
        assertEquals(Reason.NO_LOCATION, gap.reason)
        assertEquals(50.453, gap.seconds, 0.001)
    }

    @Test fun `updates skipped while the adapter is down end as a DISCONNECTED gap`() {
        val m = RecordingGapMonitor(); m.start(0L)
        m.onLocation(166_986L, written = true)
        var t = 167_986L
        while (t < 251_996L) { m.onLocation(t, written = false, skipped = Reason.DISCONNECTED); t += 1_000L }
        val gap = m.onLocation(251_996L, written = true)!!
        assertEquals(Reason.DISCONNECTED, gap.reason)
        assertEquals(84.010, gap.seconds, 0.001)                                    // first skipped update → first point
        assertNull("no 'no location' report while disconnected", m.tick(260_000L, connected = false, paused = false))
    }

    @Test fun `a pause is a PAUSED gap, and ordinary 1 Hz recording reports nothing`() {
        val m = RecordingGapMonitor(); m.start(0L)
        for (s in 0..10) assertNull(m.onLocation(s * 1_000L, written = true))
        m.onLocation(11_000L, written = false, skipped = Reason.PAUSED)
        assertNull(m.tick(30_000L, connected = true, paused = true))
        assertEquals(Reason.PAUSED, m.onLocation(40_000L, written = true)!!.reason)
    }
}
