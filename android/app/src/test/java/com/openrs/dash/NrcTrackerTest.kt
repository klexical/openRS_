package com.openrs.dash

import com.openrs.dash.can.NrcTracker
import com.openrs.dash.can.ObdConstants
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class NrcTrackerTest {

    private lateinit var tracker: NrcTracker

    @Before
    fun setUp() {
        tracker = NrcTracker()
    }

    // ── NRC 0x31 (requestOutOfRange) ────────────────────────────────────────

    @Test
    fun `NRC 0x31 suppresses after 1 occurrence`() {
        val suppressed = tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 1000L)
        assertTrue(suppressed)
        assertTrue(tracker.isSuppressed(0x03ED))
    }

    // ── NRC 0x33 (securityAccessDenied) ─────────────────────────────────────

    @Test
    fun `NRC 0x33 suppresses after 1 occurrence`() {
        val suppressed = tracker.recordNrc(0x1E8A, NrcTracker.NRC_SECURITY_ACCESS_DENIED, 2000L)
        assertTrue(suppressed)
        assertTrue(tracker.isSuppressed(0x1E8A))
    }

    // ── NRC 0x22 (conditionsNotCorrect) ─────────────────────────────────────

    @Test
    fun `NRC 0x22 suppresses after 3 consecutive occurrences`() {
        assertFalse(tracker.recordNrc(0x0462, NrcTracker.NRC_CONDITIONS_NOT_CORRECT, 1000L))
        assertFalse(tracker.isSuppressed(0x0462))

        assertFalse(tracker.recordNrc(0x0462, NrcTracker.NRC_CONDITIONS_NOT_CORRECT, 2000L))
        assertFalse(tracker.isSuppressed(0x0462))

        assertTrue(tracker.recordNrc(0x0462, NrcTracker.NRC_CONDITIONS_NOT_CORRECT, 3000L))
        assertTrue(tracker.isSuppressed(0x0462))
    }

    // ── Unknown NRC codes ───────────────────────────────────────────────────

    @Test
    fun `unknown NRC codes do not suppress`() {
        assertFalse(tracker.recordNrc(0x03EC, 0x12, 1000L))
        assertFalse(tracker.recordNrc(0x03EC, 0x14, 2000L))
        assertFalse(tracker.recordNrc(0x03EC, 0x72, 3000L))
        assertFalse(tracker.isSuppressed(0x03EC))
    }

    // ── isSuppressed edge cases ─────────────────────────────────────────────

    @Test
    fun `isSuppressed returns false for unknown DIDs`() {
        assertFalse(tracker.isSuppressed(0xFFFF))
        assertFalse(tracker.isSuppressed(0x0000))
    }

    // ── reset ───────────────────────────────────────────────────────────────

    @Test
    fun `reset clears all suppressed state`() {
        tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 1000L)
        tracker.recordNrc(0x03EE, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 2000L)
        assertTrue(tracker.isSuppressed(0x03ED))
        assertTrue(tracker.isSuppressed(0x03EE))

        tracker.reset()
        assertFalse(tracker.isSuppressed(0x03ED))
        assertFalse(tracker.isSuppressed(0x03EE))
        assertTrue(tracker.getSuppressed().isEmpty())
    }

    @Test
    fun `reset clears consecutive counts for NRC 0x22`() {
        // Accumulate 2 of 3 needed
        tracker.recordNrc(0x0462, NrcTracker.NRC_CONDITIONS_NOT_CORRECT, 1000L)
        tracker.recordNrc(0x0462, NrcTracker.NRC_CONDITIONS_NOT_CORRECT, 2000L)

        tracker.reset()

        // Should need 3 fresh occurrences again
        assertFalse(tracker.recordNrc(0x0462, NrcTracker.NRC_CONDITIONS_NOT_CORRECT, 3000L))
        assertFalse(tracker.recordNrc(0x0462, NrcTracker.NRC_CONDITIONS_NOT_CORRECT, 4000L))
        assertTrue(tracker.recordNrc(0x0462, NrcTracker.NRC_CONDITIONS_NOT_CORRECT, 5000L))
    }

    // ── getSuppressed snapshot ──────────────────────────────────────────────

    @Test
    fun `getSuppressed returns correct records`() {
        tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 1000L)
        tracker.recordNrc(0xF422, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 2000L)

        val map = tracker.getSuppressed()
        assertEquals(2, map.size)

        val rec = map[0x03ED]!!
        assertEquals(0x03ED, rec.did)
        assertEquals(NrcTracker.NRC_REQUEST_OUT_OF_RANGE, rec.nrcCode)
        assertEquals(1000L, rec.firstSeenMs)
        assertEquals(1, rec.suppressedAfterCount)
    }

    // ── Duplicate and idempotency ───────────────────────────────────────────

    @Test
    fun `recordNrc returns false for already-suppressed DID`() {
        assertTrue(tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 1000L))
        assertFalse(tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 2000L))

        // Record stays unchanged
        assertEquals(1000L, tracker.getSuppressed()[0x03ED]!!.firstSeenMs)
    }

    @Test
    fun `negative DID values are rejected`() {
        assertFalse(tracker.recordNrc(-1, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 1000L))
        assertFalse(tracker.isSuppressed(-1))
    }

    // ── extractDid (ObdConstants helper) ────────────────────────────────────

    @Test
    fun `extractDid parses SLCAN query strings`() {
        assertEquals(0x03ED, ObdConstants.extractDid("t7E08032203ED00000000\r"))
        assertEquals(0x03EE, ObdConstants.extractDid("t7E08032203EE00000000\r"))
        assertEquals(0xF422, ObdConstants.extractDid("t7E080322F42200000000\r"))
        assertEquals(0x4028, ObdConstants.extractDid("t72680322402800000000\r"))
    }

    @Test
    fun `extractDid returns null for short strings`() {
        assertNull(ObdConstants.extractDid("t7E0803"))
        assertNull(ObdConstants.extractDid(""))
    }
}
