package com.openrs.dash

import com.openrs.dash.data.CommandResult
import com.openrs.dash.data.DrivePhase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * SPEC-316, "Drives still being saved: they are counted apart from the current drive's phase, with one wait that covers all
 * of them". The recorder's `isFinalizing` (the task-removal guard) and `durabilityBarrier()` (the service keeps its
 * foreground until it completes) must cover every save still running — not only the latest one. A single slot let a later
 * job (a cancelled start's cleanup, or the next drive's quicker save) replace an earlier drive's save that was still writing
 * (QA's repro at staging 475ded0). Seam 1: the recorder on the harness database, DAO calls held.
 */
@RunWith(RobolectricTestRunner::class)
class SavingCoverageTest {
    private val h = RecorderHarness()
    @After fun tearDown() = h.close()

    /** Drive A's save is under way and held at its first row read; asserts the baseline coverage. */
    private fun stopHeldInSave(rec: com.openrs.dash.service.DriveRecorder, saveRead: RecorderHarness.Hold) {
        saveRead.armed = true
        rec.stopDrive()
        saveRead.awaitEntered()
        assertTrue("baseline: A's save is covered", rec.isFinalizing)
    }

    /** While A's save is held: both waits still cover it; after it is let go, both clear. */
    private fun assertCoversUntilReleased(rec: com.openrs.dash.service.DriveRecorder, saveRead: RecorderHarness.Hold) {
        assertTrue("isFinalizing must stay true while A is being saved", rec.isFinalizing)
        val barrier = rec.durabilityBarrier()
        assertTrue("the barrier must wait for A's save", barrier != null)
        h.settle()
        assertTrue("the barrier must not complete before A's save", barrier!!.isActive)
        saveRead.release.countDown()
        assertTrue("the barrier completes once A is saved", h.waitUntil { barrier.isCompleted })
        assertTrue("nothing left to wait for", h.waitUntil { !rec.isFinalizing })
    }

    @Test fun `a STOP during the next drive's start keeps the earlier drive's save covered`() {
        val saveRead = RecorderHarness.Hold("getDrive", armed = false)
        val bInsert = RecorderHarness.Hold("insertDrive", nth = 2)        // A's insert is the first
        val rec = with(h) { recorder(holds = listOf(saveRead, bInsert)) }
        bInsert.armed = true
        rec.startDrive()                                                  // A
        assertTrue(h.waitUntil { rec.driveState.value.phase == DrivePhase.RECORDING })
        stopHeldInSave(rec, saveRead)
        assertEquals(CommandResult.Done, rec.startDrive())                // B, allowed while A is being saved
        bInsert.awaitEntered()
        assertEquals("premise", DrivePhase.STARTING, rec.driveState.value.phase)
        rec.stopDrive()                                                   // during B's start: B's start is cancelled
        bInsert.release.countDown()
        assertTrue("B's row removed: only A is left", h.waitUntil { h.dao.getDriveCount() == 1 })
        h.settle()                                                        // B's cleanup has finished
        assertCoversUntilReleased(rec, saveRead)
    }

    /** Unchanged behaviour, pinned (mutation S3): a cancelled start's cleanup is itself covered while it removes the row. */
    @Test fun `a cancelled start's cleanup is covered while it runs`() {
        val bInsert = RecorderHarness.Hold("insertDrive")
        val cleanupDelete = RecorderHarness.Hold("deleteDrive")
        val rec = with(h) { recorder(holds = listOf(bInsert, cleanupDelete)) }
        rec.startDrive()
        bInsert.awaitEntered()
        rec.stopDrive()                                                   // during the start: the start is cancelled
        bInsert.release.countDown()
        cleanupDelete.awaitEntered()                                      // the cleanup is removing the row
        assertTrue("the cleanup is covered", rec.isFinalizing)
        val barrier = rec.durabilityBarrier()
        assertTrue("the barrier waits for the cleanup", barrier != null && barrier.isActive)
        cleanupDelete.release.countDown()
        assertTrue(h.waitUntil { barrier!!.isCompleted && !rec.isFinalizing })
        assertEquals("no row left", 0, h.dao.getDriveCount())
    }

    @Test fun `the next drive's quicker save keeps the earlier drive's save covered`() {
        val saveRead = RecorderHarness.Hold("getDrive", armed = false)
        val rec = with(h) { recorder(holds = listOf(saveRead)) }.also { it.startDrive() }
        assertTrue(h.waitUntil { rec.driveState.value.phase == DrivePhase.RECORDING })
        val a = rec.driveState.value.driveId
        stopHeldInSave(rec, saveRead)
        assertEquals(CommandResult.Done, rec.startDrive())                // B
        assertTrue(h.waitUntil { rec.driveState.value.phase == DrivePhase.RECORDING && rec.driveState.value.driveId != a })
        val bSave = rec.stopDrive()                                       // B's save is not held: it finishes first
        h.join(bSave)
        assertCoversUntilReleased(rec, saveRead)
        assertTrue("A is saved", h.waitUntil { h.dao.getDrive(a)!!.endTime != 0L })
    }
}
