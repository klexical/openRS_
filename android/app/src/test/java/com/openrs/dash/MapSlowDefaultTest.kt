package com.openrs.dash

import com.openrs.dash.data.FieldState
import com.openrs.dash.data.PolledFreshness
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #271: a MAP reading is a pressure snapshot, so the live rule never treats a 27 s-old one as current, engine running or off
 * (red on 4d5a5a3: engine off, the 40 s budget applied). The cadence case that stood here ("MAP once per slow rotation") pinned
 * the superseded "slow + test mode" decision; the owner's final decision is "Only while recording" — see MapRecordingOnlyTest
 * and MapRecordingPollTest.
 */
class MapSlowDefaultTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @Test fun `a 27 s-old MAP reading is not current - engine running or off`() {
        val now = 10_000_000L
        for (rpm in listOf(3_000.0, 0.0)) {
            val vs = VehicleState(isConnected = true, rpm = rpm, genericValues = mapOf("mapKpa" to 101.0),
                fieldLastUpdateMs = mapOf("mapKpa" to now - 27_000L))
            val st = PolledFreshness.state(vs, "mapKpa", 101.0, now)
            assertTrue("rpm $rpm: a 27 s-old MAP reads $st", st is FieldState.Stale)
            assertEquals("control - a reading 1 s old is current (rpm $rpm)", true,
                PolledFreshness.state(vs, "mapKpa", 101.0, now - 26_000L) is FieldState.Available)
        }
    }
}
