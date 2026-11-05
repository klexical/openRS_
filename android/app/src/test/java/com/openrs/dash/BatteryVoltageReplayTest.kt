package com.openrs.dash

import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Ignore
import org.junit.Test

/**
 * rc.8 "battery" — PCM 0x0304 through production code, SYNTHETIC replies. The tested invariant is policy-independent:
 * the battery value, its validity and its stamp all describe the SAME reply. A truncated reply (missing low byte, or a
 * PCI length shorter than the payload) changes neither value nor stamp; a decoded zero never advances the stamp of an
 * old value it didn't replace; an older snapshot never supersedes a newer reply. Whether a raw 0 means "unavailable"
 * or a real 0 V is a fix-time decision (owner) and is NOT asserted here. Red cases are @Ignore'd until the fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BatteryVoltageReplayTest {
    private companion object {
        const val DEFECT = "battery: reproduced, not fixed"                  // the ObdMerge-by-value vectors (#223)
        const val TRUNCATED = "slcan-integrity: reproduced, not fixed"       // short / PCI-short Mode 22 replies
        const val FIRST = 0x77AE / 2048.0   // 14.9599609375 V, the fixture's complete reply
        const val BACKDATED = 1L
    }

    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    /**
     * Baseline + the complete 0x0304 reply, then the 0x0304 stamp is backdated (stamps use the wall clock, so a re-stamp
     * in the same millisecond would otherwise be invisible), then [nextReply] arrives at +2,000 ms.
     */
    private fun TestScope.afterFirstReply(nextReply: String): VehicleState {
        val rows = ReplayFixture.load("at-rest-baseline.txt") + ReplayFixture.load("battery-0304.txt") +
            ReplayFixture.parse("+2000 RX $nextReply")
        val run = ReplayRun(this, rows)
        advanceTimeBy(1_500L)
        assertEquals("the complete reply decoded", FIRST, run.state.value.batteryVoltage, 0.0)
        run.state.value = run.state.value.let { it.copy(fieldLastUpdateMs = it.fieldLastUpdateMs + ("batteryVoltage" to BACKDATED)) }
        advanceTimeBy(1_000L)
        return run.state.value
    }

    @Test fun `control - each complete reply keeps its exact raw value`() = runTest {
        for ((line, raw) in listOf("t7E880562030477F00000" to 0x77F0, "t7E880562030478000000" to 0x7800,
                                   "t7E880562030478080000" to 0x7808)) {
            val vs = afterFirstReply(line)
            assertEquals(line, raw / 2048.0, vs.batteryVoltage, 0.0)
            assertTrue("a valid reply refreshes its own stamp", (vs.fieldLastUpdateMs["batteryVoltage"] ?: 0L) > BACKDATED)
        }
    }

    @Ignore(TRUNCATED) @Test fun `a reply missing its low byte (DLC 5) changes neither value nor stamp`() = runTest {
        val vs = afterFirstReply("t7E850462030478")                  // 04 62 03 04 78 — B absent
        assertEquals("decoded with B = 0 → 15.00 V", FIRST, vs.batteryVoltage, 0.0)
        assertEquals(BACKDATED, vs.fieldLastUpdateMs["batteryVoltage"])
    }

    @Ignore(TRUNCATED) @Test fun `a PCI shorter than the payload (padding read as B) changes neither value nor stamp`() = runTest {
        val vs = afterFirstReply("t7E880462030478000000")            // PCI 04: one data byte; the 00 after it is padding
        assertEquals("padding read as B → 15.00 V", FIRST, vs.batteryVoltage, 0.0)
        assertEquals(BACKDATED, vs.fieldLastUpdateMs["batteryVoltage"])
    }

    @Ignore(DEFECT) @Test fun `a decoded zero never re-stamps the old value it did not replace`() = runTest {
        val vs = afterFirstReply("t7E880562030400000000")            // complete reply, raw 0x0000
        val stamp = vs.fieldLastUpdateMs["batteryVoltage"]
        val keptOldUnderOldStamp = vs.batteryVoltage == FIRST && stamp == BACKDATED
        val tookZeroUnderNewStamp = vs.batteryVoltage == 0.0 && (stamp ?: 0L) > BACKDATED
        assertTrue("value ${vs.batteryVoltage} with stamp $stamp describe different replies",
            keptOldUnderOldStamp || tookZeroUnderNewStamp)
    }

    @Ignore(DEFECT) @Test fun `a reply decoded from an older snapshot never reverts a newer voltage`() {
        // CanDataService posts each merge to Main, so a reply can be parsed against a snapshot older than the state it
        // merges into (the AwdReqTorqueMergeTest race). Production parse + ObdMerge.merge, as there. ObdMerge takes the
        // voltage by value > 0 — the pattern #226 fixed for REQ L / R with ObdMerge.carried.
        val now = System.currentTimeMillis()
        val old = VehicleState(isConnected = true, batteryVoltage = 12.5,
            fieldLastUpdateMs = mapOf("batteryVoltage" to now - 30_000))
        val current = old.copy(batteryVoltage = 14.0, fieldLastUpdateMs = mapOf("batteryVoltage" to now - 1_000))
        var merged = current
        val tip = byteArrayOf(0x05, 0x62, 0x03, 0x3E, 0x4F, 0x00, 0x00, 0x00)   // an unrelated PCM reply (TIP)
        ObdResponseParser.parsePcmResponse(tip, old) { merged = ObdMerge.merge(current, it) }
        assertEquals(14.0, merged.batteryVoltage, 0.0)
        assertEquals(current.fieldLastUpdateMs["batteryVoltage"], merged.fieldLastUpdateMs["batteryVoltage"])
    }
}
