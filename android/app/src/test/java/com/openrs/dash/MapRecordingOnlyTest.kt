package com.openrs.dash

import com.openrs.dash.can.ObdConstants
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #271, owner's final decision F1 ("Only while recording"): MAP 0xF40B is polled only while a drive is recording. With no
 * recording the PCM poller never sends it and the groups are the pre-#271 ones (7 fast, 20 slow). Production poller
 * (SlcanConnection on virtual time, engine running, no drive). Only APIs that exist on ab4837d, so it runs there (red: MAP was
 * in the slow rotation). The recording side is in MapRecordingPollTest.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MapRecordingOnlyTest {
    @Before fun session() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @Test fun `with no drive recording, the engine-running poller never sends MAP`() = runTest {
        val run = ReplayRun(this, ReplayFixture.load("at-rest-baseline.txt"), rpm = 3_000.0)
        advanceTimeBy(140_000L)                                           // ~50 cycles, about two slow rotations
        val tip = run.transport.writes.count { "22033E" in it }; val map = run.transport.writes.count { "22F40B" in it }
        assertTrue("the fast group ran ($tip TIP requests)", tip >= 30)
        assertEquals("no MAP request without a recording", 0, map)
    }

    @Test fun `the fast and slow groups are the pre-271 ones - MAP in neither`() {
        val fast = ObdConstants.PCM_FAST_QUERIES.mapNotNull { ObdConstants.extractDid(it) }
        val slow = ObdConstants.PCM_SLOW_QUERIES.mapNotNull { ObdConstants.extractDid(it) }
        assertEquals(7, fast.size); assertEquals(20, slow.size)
        assertTrue(0xF40B !in fast && 0xF40B !in slow)
    }
}
