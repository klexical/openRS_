package com.openrs.dash

import com.openrs.dash.can.SlcanParser
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * rc.8 "slcan-integrity" — SYNTHETIC replay through production code ([ReplayRun]: SlcanConnection → SlcanParser →
 * LinkSession / CanDecoder → peaks). Guards pass today; the red cases reproduce the defect and are @Ignore'd until the fix.
 * Agreed framing rule: a data payload of exactly DLC×2 hex characters (DLC×2 + 4 only if the app itself enabled SLCAN
 * timestamps on that connection — it never sends Z1 today); DLC 0 parses but can't feed a decoder that needs bytes.
 * Implausible but well-formed frames stay in the raw log, flagged, and must not refresh live state, stamps or peaks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlcanIntegrityReplayTest {
    private companion object { const val DEFECT = "slcan-integrity: reproduced, not fixed" }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun TestScope.replay(vararg fixtures: String): ReplayRun =
        ReplayRun(this, fixtures.flatMap { ReplayFixture.load(it) }).also { advanceTimeBy(5_000L) }

    @Test fun `baseline - the at-rest fixture decodes oil 70 and speed 0`() = runTest {
        val vs = replay("at-rest-baseline.txt").state.value
        assertEquals(70.0, vs.oilTempC, 0.0)
        assertEquals(70.0, vs.peakOilTempC, 0.0)
        assertEquals(0.0, vs.speedKph, 0.0)
    }

    @Test fun `guard - short, bad-hex, DLC 9 and DLC 0 lines change nothing`() = runTest {
        val vs = replay("at-rest-baseline.txt", "slcan-malformed-contained.txt").state.value
        assertEquals(70.0, vs.oilTempC, 0.0)
        assertEquals(70.0, vs.peakOilTempC, 0.0)
        assertEquals(40.0, vs.ptuTempC, 0.0)   // byte 1 0x5A − 50 (#229 mapping)
    }

    @Test fun `parser - DLC 0 is a legal empty frame, short and bad-hex lines are refused`() {
        val empty = SlcanParser.parse("t0F80")
        assertNotNull(empty)
        assertEquals(0, empty!!.second.size)
        assertNull(SlcanParser.parse("t0F88005A"))
        assertNull(SlcanParser.parse("t0F88005A00000000G082"))
    }

    @Ignore(DEFECT) @Test fun `parser - characters after the DLC payload make the line invalid`() {
        assertNull(SlcanParser.parse("t0F88005A0000000000FF2710"))
    }

    @Ignore(DEFECT) @Test fun `a spliced line never reaches the decoder - oil and its peak stay 70`() = runTest {
        val vs = replay("at-rest-baseline.txt", "slcan-spliced-line.txt").state.value
        assertEquals(70.0, vs.oilTempC, 0.0)
        assertEquals("peak from the spliced line's 195 °C", 70.0, vs.peakOilTempC, 0.0)
    }

    @Ignore(DEFECT) @Test fun `one impossible well-formed speed frame does not set the session peak`() = runTest {
        val vs = replay("at-rest-baseline.txt", "slcan-outlier-speed.txt").state.value
        assertEquals(0.0, vs.speedKph, 0.0)
        assertEquals("peak speed from one 655 km/h frame at rest", 0.0, vs.peakSpeedKph, 0.0)
    }
}
