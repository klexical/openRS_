package com.openrs.dash

import com.openrs.dash.data.CommandResult
import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
import com.openrs.dash.service.DriveRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #316, auto-record in the recorder (DESIGN-SUMMARY "Auto-record" and "Pauses"), at seams 1 and 2. A connection that sees
 * the engine running starts a drive unless one is open; after a hand STOP nothing restarts on the same connection, a new
 * connection does. A link pause ends on the engine running with the connection back: within 15 minutes of the loss the
 * same drive records on; older, the old drive is saved and a new one starts. A hand pause is never ended by it.
 */
@RunWith(RobolectricTestRunner::class)
class AutoRecordTest {
    private val h = RecorderHarness()
    @After fun tearDown() = h.close()

    private fun DriveRecorder.phase() = driveState.value.phase
    private fun DriveRecorder.id() = driveState.value.driveId
    private val window = 15 * 60_000L

    /** A new connection that sees the engine running. */
    private fun DriveRecorder.connectedEngineRunning(sessionId: Long = 0L) { onConnectionBack(); onEngineRunning(sessionId) }

    @Test fun `auto-record on - a new connection with the engine running starts a drive, with the session`() {
        val rec = h.recorder(autoRecord = true)
        rec.connectedEngineRunning(sessionId = 42L)
        assertTrue(h.waitUntil { rec.phase() == DrivePhase.RECORDING })
        assertEquals(1, h.dao.getDriveCount())
        assertEquals(42L, h.dao.getDrive(rec.id())!!.sessionId)
    }

    @Test fun `auto-record off - nothing starts`() {
        val rec = h.recorder(autoRecord = false)
        rec.connectedEngineRunning()
        h.settle()
        assertEquals(DrivePhase.IDLE, rec.phase())
        assertEquals(0, h.dao.getDriveCount())
    }

    @Test fun `the engine reported with the connection down starts nothing`() {
        val rec = h.recorder(autoRecord = true)
        rec.onConnectionLost(atMs = h.now)
        rec.onEngineRunning()
        h.settle()
        assertEquals(DrivePhase.IDLE, rec.phase())
    }

    @Test fun `a recording drive is kept - no second drive`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val id = rec.id()
        rec.connectedEngineRunning()
        h.settle()
        assertEquals(DrivePhase.RECORDING, rec.phase())
        assertEquals(id, rec.id())
        assertEquals(1, h.dao.getDriveCount())
    }

    @Test fun `after a hand STOP nothing restarts on the same connection - a new connection starts a drive`() {
        val rec = h.recorder(autoRecord = true)
        rec.connectedEngineRunning()
        assertTrue(h.waitUntil { rec.phase() == DrivePhase.RECORDING })
        val first = rec.id()
        h.join(rec.stopDrive())
        rec.onEngineRunning()                                          // the same connection, the engine still running
        h.settle()
        assertEquals("same connection", DrivePhase.IDLE, rec.phase())
        assertEquals(1, h.dao.getDriveCount())
        rec.connectedEngineRunning()                                   // a reconnect
        assertTrue(h.waitUntil { rec.phase() == DrivePhase.RECORDING && rec.id() != first })
        assertEquals(2, h.dao.getDriveCount())
    }

    @Test fun `a hand pause is never ended by the engine running - any age, no second drive`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        assertEquals(CommandResult.Done, rec.pauseDrive())
        h.now += window + 60_000L
        rec.connectedEngineRunning()
        h.settle()
        assertEquals(DrivePhase.PAUSED to PauseReason.HAND, rec.phase() to rec.driveState.value.pauseReason)
        assertEquals(1, h.dao.getDriveCount())
    }

    @Test fun `a link pause of exactly 15 minutes ends on the engine running - the same drive records`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val id = rec.id()
        rec.onConnectionLost(atMs = h.now)
        h.now += window
        rec.connectedEngineRunning()
        assertEquals(DrivePhase.RECORDING, rec.phase())
        assertEquals(id, rec.id())
        assertEquals(1, h.dao.getDriveCount())
    }

    @Test fun `a link pause older than 15 minutes - the old drive is saved and a new one starts`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val old = rec.id()
        rec.onConnectionLost(atMs = h.now)
        h.now += window + 1
        rec.connectedEngineRunning()
        assertTrue(h.waitUntil { rec.phase() == DrivePhase.RECORDING && rec.id() != old })
        assertTrue(h.waitUntil { (h.dao.getDrive(old)?.endTime ?: 0L) > 0L })
        assertEquals(2, h.dao.getDriveCount())
    }

    @Test fun `the 15 minutes count from the loss time the event carried, not its arrival`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val old = rec.id()
        val lostAt = h.now
        h.now += 10 * 60_000L                                          // the event arrives after the attempts
        rec.onConnectionLost(atMs = lostAt)
        h.now += 5 * 60_000L + 1                                       // 15 min + 1 ms after the loss, 5 min after the event
        rec.connectedEngineRunning()
        assertTrue(h.waitUntil { rec.phase() == DrivePhase.RECORDING && rec.id() != old })
        assertNotEquals(old, rec.id())
    }

    // ── the saved end of a drive closed by the 15-minute rule (DESIGN-SUMMARY "Connection lost") ─────────────────────

    @Test fun `a drive closed by the 15-minute rule is saved with its end at the moment of the loss`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val old = rec.id()
        h.now += 120_000L
        val lostAt = h.now
        h.now += 30_000L                                               // the event arrives after the attempts
        rec.onConnectionLost(atMs = lostAt)
        h.now += window + 60_000L
        rec.connectedEngineRunning()
        assertTrue(h.waitUntil { (h.dao.getDrive(old)?.endTime ?: 0L) > 0L })
        assertEquals(lostAt, h.dao.getDrive(old)!!.endTime)
    }

    @Test fun `a drive closed by the 15-minute rule never ends before its last stored point`() {
        val rec = with(h) { recorder(autoRecord = true).recording() }
        val old = rec.id()
        assertTrue(h.waitUntil { h.gps.current != null })
        h.now += 60_000L
        val pointAt = h.now
        h.gps.current!!.trySend(android.location.Location("test").apply {
            latitude = 51.5; longitude = -0.1; speed = 10f; time = pointAt
        })
        assertTrue("a point stored", h.waitUntil { rec.driveState.value.totalPointCount == 1 })
        rec.onConnectionLost(atMs = pointAt - 5_000L)                 // a loss moment before that point
        h.now += window + 60_000L
        rec.connectedEngineRunning()
        assertTrue(h.waitUntil { (h.dao.getDrive(old)?.endTime ?: 0L) > 0L })
        assertEquals(pointAt, h.dao.getDrive(old)!!.endTime)
    }
}
