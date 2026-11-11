package com.openrs.dash

import com.openrs.dash.data.DrivePhase
import com.openrs.dash.demo.DemoMode
import com.openrs.dash.demo.DemoScenario
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.DriveRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #316 seam 5: the service reports "demo mode entered" to its recorder (spec difference 3: a start under way is cancelled
 * too; staging stopped only a recording or paused drive). The rule itself is seam 1's (DemoModeEnteredTest). Release
 * builds have no demo mode (DemoMode.AVAILABLE is false there, enterDemoMode does nothing): in that variant the test
 * checks that nothing happens to the drive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class ServiceDemoEntryTest {
    private val h = RecorderHarness()
    private val link get() = CanDataService.link
    private lateinit var svc: CanDataService

    @Before fun fresh() {
        com.openrs.dash.diagnostics.DiagnosticLogger.sessionStart("test", 0, prefs = null)
        link.clearManualConnect()
        link.requireManualConnect()                                     // no connection: only the recorder matters here
    }

    @After fun tidy() {
        if (::svc.isInitialized) svc.exitDemoMode()
        TestServices.destroyAll()
        link.clearManualConnect()
        h.close()
    }

    private fun service(rec: DriveRecorder): CanDataService {
        CanDataService.recorderProvider = { rec }
        return TestServices.create().also { svc = it }
    }

    @Test fun `entering demo mode during a start cancels it - no drive begins`() {
        val hold = RecorderHarness.Hold("insertDrive")
        val rec = h.recorder(hold)
        rec.startDrive()
        hold.awaitEntered()
        service(rec).enterDemoMode(DemoScenario.IDLE)
        hold.release.countDown()
        if (DemoMode.AVAILABLE) {
            assertTrue(h.waitUntil { h.dao.getDriveCount() == 0 && rec.driveState.value.phase == DrivePhase.IDLE })
            h.settle()
            assertEquals(DrivePhase.IDLE, rec.driveState.value.phase)
            assertEquals(0, h.dao.getDriveCount())
        } else {
            assertTrue("release: no demo mode, the start completes",
                h.waitUntil { rec.driveState.value.phase == DrivePhase.RECORDING })
        }
    }

    @Test fun `entering demo mode stops and saves a recording drive`() {
        val rec = with(h) { recorder().recording() }
        val id = rec.driveState.value.driveId
        service(rec).enterDemoMode(DemoScenario.IDLE)
        if (DemoMode.AVAILABLE) {
            assertEquals(DrivePhase.IDLE, rec.driveState.value.phase)
            assertTrue(h.waitUntil { h.dao.getDrive(id)!!.endTime != 0L })
        } else {
            h.settle()
            assertEquals("release: no demo mode", DrivePhase.RECORDING, rec.driveState.value.phase)
        }
    }
}
