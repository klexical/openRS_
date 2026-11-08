package com.openrs.dash

import com.openrs.dash.data.FieldState
import com.openrs.dash.data.PolledFreshness
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #271, owner decision F1 ("slow + test mode"): by default MAP 0xF40B is a SLOW-group DID — the engine-running cycle is the
 * pre-#271 fast group (TIP, TIP desired, WGDC, KR1–4) plus one slow DID — and a MAP reading is a pressure snapshot, so the live
 * rule never treats a 27 s-old one as current. Production poller (SlcanConnection's PCM loop on virtual time, engine running),
 * counting the frames it writes. Only existing APIs, so it runs on the commit before the change (red there: MAP was fast).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapSlowDefaultTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @Test fun `by default the engine-running poller sends MAP once per slow rotation, not every cycle`() = runTest {
        // No ECU replies: each request waits its 300 ms reply window, so a cycle is ~8 × 300 ms. 120 s after the 20 s start
        // delay is ~50 cycles — about two slow rotations.
        val run = ReplayRun(this, ReplayFixture.load("at-rest-baseline.txt"), rpm = 3_000.0)
        advanceTimeBy(140_000L)
        val writes = run.transport.writes
        val tip = writes.count { "22033E" in it }; val map = writes.count { "22F40B" in it }
        assertTrue("the fast group ran ($tip TIP requests)", tip >= 30)
        assertTrue("MAP is not polled every cycle by default: $map MAP vs $tip TIP requests", map * 8 <= tip)
        assertTrue("MAP still comes round in the slow rotation ($map)", map >= 1)
    }

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
