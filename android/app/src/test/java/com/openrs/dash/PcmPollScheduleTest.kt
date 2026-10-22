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
        val all = fast + slow
        assertEquals(all.size, all.toSet().size)
        assertEquals(27, all.size)
        assertEquals(ObdConstants.PCM_QUERIES.toSet(), all.toSet())
    }

    @Test
    fun `fast group holds the boost sweep and knock DIDs`() {
        val dids = fast.mapNotNull { ObdConstants.extractDid(it) }.toSet()
        assertEquals(setOf(0x033E, 0x0466, 0x0462, 0x03EC, 0x03ED, 0x03EE, 0x03EF), dids)
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
