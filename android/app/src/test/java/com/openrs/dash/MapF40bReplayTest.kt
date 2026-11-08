package com.openrs.dash

import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.FieldState
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.PolledFreshness
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #271 enabler — PCM DID 0xF40B (standard MAP, 1 byte, kPa absolute) through production code, SYNTHETIC replies: the replay
 * transport feeds the production SlcanConnection, OBD replies go through ObdResponseParser and ObdMerge.merge into one state
 * (what CanDataService does). The reading lives in genericValues under "mapKpa" (VehicleState has no free slot, #199), with
 * its own freshness stamp. Only existing APIs are used, so the same file runs on the base commit (red) and after the change.
 *
 * Also the generic-merge hazard this reading would hit: genericValues merged as `current + obd`, so a reply parsed against an
 * older snapshot put that snapshot's older generic values back under the newer stamps (the #226 / #223 class).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapF40bReplayTest {
    private companion object { const val TAG = "mapKpa" }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    /** The at-rest baseline, then [reply] at +1,000 ms; returns the state 1.5 s later. */
    private fun TestScope.after(reply: String?): VehicleState {
        val rows = ReplayFixture.load("at-rest-baseline.txt") + (reply?.let { ReplayFixture.parse("+1000 RX $it") } ?: emptyList())
        val run = ReplayRun(this, rows)
        advanceTimeBy(2_500L)
        return run.state.value
    }

    @Test fun `control - before any F40B reply there is no MAP value and no stamp`() = runTest {
        val vs = after(null)
        assertNull(vs.genericValues[TAG]); assertNull(vs.fieldLastUpdateMs[TAG])
    }

    @Test fun `a complete F40B reply (PCI 04) decodes to kPa absolute with its own stamp`() = runTest {
        val run = ReplayRun(this, ReplayFixture.load("at-rest-baseline.txt") + ReplayFixture.load("map-f40b.txt"))
        advanceTimeBy(2_500L)
        val vs = run.state.value
        assertEquals(100.0, vs.genericValues[TAG] ?: Double.NaN, 0.0)
        assertTrue("stamped", (vs.fieldLastUpdateMs[TAG] ?: 0L) > 0L)
    }

    @Test fun `the PCI 07 form with padding decodes the first data byte only`() = runTest {
        val vs = after("t7E880762F40B6E000000")                     // 07 62 F4 0B 6E 00 00 00 → A = 0x6E
        assertEquals(110.0, vs.genericValues[TAG] ?: Double.NaN, 0.0)
    }

    @Test fun `a reply with no data byte (PCI 03) decodes nothing and stamps nothing`() = runTest {
        val vs = after("t7E840362F40B")                             // 03 62 F4 0B — A absent
        assertNull(vs.genericValues[TAG]); assertNull(vs.fieldLastUpdateMs[TAG])
    }

    @Test fun `a decoded zero is a reading, not a no-data value`() = runTest {
        val vs = after("t7E880462F40B00000000")
        assertEquals(0.0, vs.genericValues[TAG] ?: Double.NaN, 0.0)
        assertTrue((vs.fieldLastUpdateMs[TAG] ?: 0L) > 0L)
    }

    @Test fun `the byte's ceiling 255 is stored as 255 kPa (flagged saturated by its readers)`() = runTest {
        val vs = after("t7E880462F40BFF000000")
        assertEquals(255.0, vs.genericValues[TAG] ?: Double.NaN, 0.0)
    }

    @Test fun `MAP is a fast-group reading - current within its budget, stale once replies stop`() = runTest {
        val vs = after("t7E880462F40B64000000").copy(rpm = 3_000.0)     // engine running: the fast-group rule applies
        val at = vs.fieldLastUpdateMs[TAG] ?: 0L
        assertTrue("decoded", at > 0L)
        assertTrue("current 1 s after the reply", PolledFreshness.state(vs, TAG, 100.0, at + 1_000L) is FieldState.Available)
        assertTrue("stale past twice the 2 s fast refresh", PolledFreshness.state(vs, TAG, 100.0, at + 5_000L) is FieldState.Stale)
    }

    @Test fun `a reply decoded from an older snapshot never puts an older MAP value back`() {
        // CanDataService posts each merge to Main, so a reply can be parsed against a snapshot older than the state it merges
        // into. Production parse + ObdMerge.merge; the unrelated reply is TIP 0x033E.
        val now = System.currentTimeMillis()
        val old = VehicleState(isConnected = true, genericValues = mapOf(TAG to 90.0), fieldLastUpdateMs = mapOf(TAG to now - 30_000))
        val current = old.copy(genericValues = mapOf(TAG to 120.0), fieldLastUpdateMs = mapOf(TAG to now - 1_000))
        var merged = current
        ObdResponseParser.parsePcmResponse(byteArrayOf(0x05, 0x62, 0x03, 0x3E, 0x4F, 0x00, 0x00, 0x00), old) { merged = ObdMerge.merge(current, it) }
        assertEquals("the newer value stays", 120.0, merged.genericValues[TAG] ?: Double.NaN, 0.0)
        assertEquals(current.fieldLastUpdateMs[TAG], merged.fieldLastUpdateMs[TAG])
    }
}
