package com.openrs.dash

import com.openrs.dash.can.ObdConstants
import com.openrs.dash.can.PcmPollSchedule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * PcmPollSchedule — rc.5 (#184): fast boost/knock group every cycle while the engine
 * runs, slow DIDs rotated one per cycle; engine off keeps the full-sweep + idle cadence.
 */
class PcmPollScheduleTest {

    private val fast = ObdConstants.PCM_FAST_QUERIES
    private val slow = ObdConstants.PCM_SLOW_QUERIES

    @Test
    fun `fast and slow groups cover every PCM DID exactly once`() {
        val all = fast + slow + ObdConstants.PCM_QUERY_MAP                 // MAP: only while recording (#271)
        assertEquals(all.size, all.toSet().size)
        assertEquals(28, all.size)
        assertEquals(ObdConstants.PCM_QUERIES.toSet(), all.toSet())
    }

    @Test
    fun `fast group holds the boost sweep and knock DIDs`() {
        val dids = fast.mapNotNull { ObdConstants.extractDid(it) }.toSet()
        assertEquals(setOf(0x033E, 0x0466, 0x0462, 0x03EC, 0x03ED, 0x03EE, 0x03EF), dids)
    }

    @Test
    fun `not recording - no MAP, the pre-#271 cycle and slow rotation (owner decision F1, only while recording)`() {
        // One cycle = the fast group + one slow DID at the engine-running gap (reply time not included)
        val f = ObdConstants.pcmFastQueries(mapPoll = false)
        assertEquals(fast, f)
        assertTrue(0xF40B !in (f + slow).mapNotNull { ObdConstants.extractDid(it) })
        val cycleMs = (f.size + 1) * ObdConstants.PCM_FAST_GAP_MS
        assertEquals(1_200L, cycleMs)                                     // as before #271
        assertEquals(24_000L, slow.size * cycleMs)                        // 20 slow DIDs, as before #271
        assertTrue(slow.size * cycleMs <= ObdConstants.PCM_SLOW_REFRESH_MS)
        val b = PcmPollSchedule.nextBatch(slowIndex = 0, engineRunning = true, mapPoll = false)
        assertEquals(f.size + 1, b.queries.size)
        assertTrue(ObdConstants.PCM_QUERY_MAP !in b.queries)
    }

    @Test
    fun `recording - MAP joins the fast group and the engine-off sweep (#271)`() {
        val f = ObdConstants.pcmFastQueries(mapPoll = true)
        // MAP directly after TIP actual: a TIP / MAP pair is one request apart (#271)
        assertEquals(listOf(ObdConstants.PCM_QUERY_TIP_ACTUAL, ObdConstants.PCM_QUERY_MAP) + (fast - ObdConstants.PCM_QUERY_TIP_ACTUAL), f)
        assertEquals(fast.toSet() + ObdConstants.PCM_QUERY_MAP, f.toSet())
        val cycleMs = (f.size + 1) * ObdConstants.PCM_FAST_GAP_MS
        assertEquals(1_350L, cycleMs)
        assertEquals(27_000L, slow.size * cycleMs)
        assertTrue(cycleMs < com.openrs.dash.data.PolledFreshness.FAST_REFRESH_MS)
        val run = PcmPollSchedule.nextBatch(slowIndex = 0, engineRunning = true, mapPoll = true)
        assertEquals(ObdConstants.PCM_QUERY_MAP, run.queries[run.queries.indexOf(ObdConstants.PCM_QUERY_TIP_ACTUAL) + 1])
        // Engine off while recording: the sweep carries MAP too (the key-on / engine-off reading the scale check needs)
        val off = PcmPollSchedule.nextBatch(slowIndex = 0, engineRunning = false, mapPoll = true)
        assertTrue(ObdConstants.PCM_QUERY_MAP in off.queries)
        val sweepMs = off.queries.size * ObdConstants.PCM_QUERY_GAP_MS + ObdConstants.PCM_POLL_INTERVAL_MS
        assertTrue(sweepMs <= ObdConstants.PCM_SLOW_REFRESH_MS)
    }

    @Test
    fun `slow group carries the restored engine DIDs and DI fuel rail, not 0xF422`() {
        val dids = slow.mapNotNull { ObdConstants.extractDid(it) }.toSet()
        assertTrue(dids.containsAll(setOf(0xF404, 0xF40E, 0xF415, 0xF423, 0x0548)))
        assertTrue(0xF422 !in dids)
    }

    @Test
    fun `engine-off sweep plus idle fits inside the slow refresh window`() {
        val sweepMs = (fast.size + slow.size) * ObdConstants.PCM_QUERY_GAP_MS + ObdConstants.PCM_POLL_INTERVAL_MS
        assertTrue(sweepMs <= ObdConstants.PCM_SLOW_REFRESH_MS)
    }

    @Test
    fun `engine running sends fast group then one slow DID`() {
        val b = PcmPollSchedule.nextBatch(slowIndex = 0, engineRunning = true)
        assertEquals(fast + slow[0], b.queries)
        assertEquals(ObdConstants.PCM_FAST_GAP_MS, b.gapMs)
        assertEquals(0L, b.idleAfterMs)
        assertEquals(1, b.nextSlowIndex)
    }

    @Test
    fun `engine running rotates through every slow DID`() {
        var idx = 0
        val seen = mutableListOf<String>()
        repeat(slow.size) {
            val b = PcmPollSchedule.nextBatch(idx, engineRunning = true)
            seen += b.queries.last()
            idx = b.nextSlowIndex
        }
        assertEquals(slow, seen)
        assertEquals(0, idx)   // wraps back to the start
    }

    @Test
    fun `engine off sends a full sweep then idles`() {
        val b = PcmPollSchedule.nextBatch(slowIndex = 5, engineRunning = false)
        assertEquals(fast + slow, b.queries)
        assertEquals(ObdConstants.PCM_QUERY_GAP_MS, b.gapMs)
        assertEquals(ObdConstants.PCM_POLL_INTERVAL_MS, b.idleAfterMs)
        assertEquals(5, b.nextSlowIndex)   // rotation position preserved
    }

    @Test
    fun `out-of-range slow index wraps safely`() {
        val b = PcmPollSchedule.nextBatch(slowIndex = slow.size + 2, engineRunning = true)
        assertTrue(b.queries.last() == slow[2])
    }
}
