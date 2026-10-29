package com.openrs.dash

import com.openrs.dash.can.NrcTracker
import com.openrs.dash.can.ObdConstants
import com.openrs.dash.can.PcmRequestLedger
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
    fun `NRC 0x31 suppresses after 2 consecutive attributed occurrences`() {
        assertFalse(tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 500L))   // one alone never suppresses (#216)
        val suppressed = tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 1000L)
        assertTrue(suppressed)
        assertTrue(tracker.isSuppressed(0x03ED))
    }

    // ── NRC 0x33 (securityAccessDenied) ─────────────────────────────────────

    @Test
    fun `NRC 0x33 suppresses after 2 consecutive attributed occurrences`() {
        assertFalse(tracker.recordNrc(0x1E8A, NrcTracker.NRC_SECURITY_ACCESS_DENIED, 1500L))
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
        repeat(2) { tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 1000L) }
        repeat(2) { tracker.recordNrc(0x03EE, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 2000L) }
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
        repeat(2) { tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 1000L) }
        repeat(2) { tracker.recordNrc(0xF422, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 2000L) }

        val map = tracker.getSuppressed()
        assertEquals(2, map.size)

        val rec = map[0x03ED]!!
        assertEquals(0x03ED, rec.did)
        assertEquals(NrcTracker.NRC_REQUEST_OUT_OF_RANGE, rec.nrcCode)
        assertEquals(1000L, rec.firstSeenMs)
        assertEquals(2, rec.suppressedAfterCount)
    }

    // ── Duplicate and idempotency ───────────────────────────────────────────

    @Test
    fun `recordNrc returns false for already-suppressed DID`() {
        assertFalse(tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 500L))
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

    @Test
    fun `a positive reply clears the count and recovers a suppressed DID`() {
        tracker.recordNrc(0x03EF, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 1000L)
        tracker.recordPositive(0x03EF)
        assertFalse("count reset by the positive reply", tracker.recordNrc(0x03EF, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 2000L))
        assertTrue(tracker.recordNrc(0x03EF, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 3000L))
        assertTrue(tracker.recordPositive(0x03EF))
        assertFalse(tracker.isSuppressed(0x03EF))
    }

    @Test
    fun `a suppressed DID is re-probed after REPROBE_MS and one more rejection suppresses it again`() {
        repeat(2) { tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 1000L) }
        assertFalse(tracker.shouldPoll(0x03ED, 1000L + NrcTracker.REPROBE_MS - 1))
        assertTrue(tracker.shouldPoll(0x03ED, 1000L + NrcTracker.REPROBE_MS))
        assertTrue(tracker.recordNrc(0x03ED, NrcTracker.NRC_REQUEST_OUT_OF_RANGE, 70_000L))
    }

    @Test
    fun `response pending 0x78 never counts`() {
        repeat(5) { assertFalse(tracker.recordNrc(0x0466, NrcTracker.NRC_RESPONSE_PENDING, 1000L)) }
        assertFalse(tracker.isSuppressed(0x0466))
    }

    @Test
    fun `ledger — the A, B, C counterexample — a late NRC for A is never attributed to C`() {
        val l = PcmRequestLedger()
        l.sent(0x0A0A, 0); l.sent(0x0B0B, 100)
        assertTrue(l.positive(0x0B0B))                                // B answered: only B is resolved
        l.sent(0x0C0C, 200)
        assertNull(l.negative(300))                                   // A or C? ambiguous — nobody is charged
    }

    @Test
    fun `ledger — a single outstanding request owns the NRC`() {
        val l = PcmRequestLedger()
        l.sent(0x03ED, 0)
        assertEquals(0x03ED, l.negative(160))
        assertNull(l.negative(170))                                   // nothing outstanding any more
    }

    @Test
    fun `ledger — repeated queries of one DID are unambiguous`() {
        val l = PcmRequestLedger()
        l.sent(0x03ED, 0); l.sent(0x03ED, 1_000)
        assertEquals(0x03ED, l.negative(1_100))
        assertEquals(0x03ED, l.negative(1_200))
        assertNull(l.negative(1_300))
    }

    @Test
    fun `ledger — a positive reply resolves only its own DID, unanswered requests age out`() {
        val l = PcmRequestLedger(maxAgeMs = 2_000L)
        l.sent(0x0466, 0); l.sent(0x0462, 100)
        assertTrue(l.positive(0x0462))
        assertEquals(0x0466, l.negative(500))                         // 0466 is still the only one waiting
        l.sent(0x0461, 1_000)
        assertFalse(l.positive(0x9999))                               // unsolicited — changes nothing
        assertEquals(0x0461, l.negative(3_500 - 600))
        l.sent(0x0318, 4_000)
        assertNull(l.negative(6_100))                                 // older than maxAge: no reply ever came
    }
}
