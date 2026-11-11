package com.openrs.dash

import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.LinkStopReason
import com.openrs.dash.ui.UserPrefsStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #316 seam 5: the service reports "auto-record switched" (DIFFERENCES item 15). It tells the recorder the saved setting
 * when it is created and again at every change of the setting (Settings' SAVE goes through UserPrefsStore); it no
 * longer reads the setting at each connection start and loss report. What the recorder does with it is seam 1's
 * (AutoRecordSwitchedTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class ServiceAutoRecordSwitchTest {
    private val h = RecorderHarness()
    private val link get() = CanDataService.link
    private lateinit var rec: DriveRecorder

    /** The setting saved as [autoRecord], a recording drive whose recorder is told nothing, the service created. */
    private fun service(autoRecord: Boolean): CanDataService {
        UserPrefsStore.update(h.app) { it.copy(autoRecordDrives = autoRecord) }
        link.clearManualConnect()
        link.requireManualConnect()                                     // no connection: only the setting matters here
        rec = with(h) { recorder(autoRecord = !autoRecord, clock = System::currentTimeMillis).recording() }
        CanDataService.recorderProvider = { rec }
        return TestServices.create()
    }

    private val state get() = rec.driveState.value.phase to rec.driveState.value.pauseReason

    @Before fun fresh() { com.openrs.dash.diagnostics.DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @After fun tidy() {
        TestServices.destroyAll()
        link.clearManualConnect()
        h.close()
    }

    @Test fun `the service tells the recorder the saved setting when created - on, a closure link-pauses`() {
        val svc = service(autoRecord = true)                            // the recorder was told off
        svc.stopConnection(LinkStopReason.USER_DISCONNECT)
        assertEquals(DrivePhase.PAUSED to PauseReason.LINK, state)
    }

    @Test fun `the service tells the recorder the saved setting when created - off, a closure pauses nothing`() {
        val svc = service(autoRecord = false)                           // the recorder was told on
        svc.stopConnection(LinkStopReason.USER_DISCONNECT)
        h.settle()
        assertEquals(DrivePhase.RECORDING to null, state)
    }

    @Test fun `switched off in Settings during a link pause - the recorder makes it a hand pause`() {
        val svc = service(autoRecord = true)
        svc.stopConnection(LinkStopReason.USER_DISCONNECT)
        assertEquals("premise", DrivePhase.PAUSED to PauseReason.LINK, state)
        UserPrefsStore.update(h.app) { it.copy(autoRecordDrives = false) }
        assertTrue(h.waitUntil { state == (DrivePhase.PAUSED to PauseReason.HAND) })
    }

    @Test fun `switched on in Settings while recording - a later closure link-pauses`() {
        val svc = service(autoRecord = false)
        UserPrefsStore.update(h.app) { it.copy(autoRecordDrives = true) }
        // no wait: the change reaches the recorder in the call that saves it (DIFFERENCES item 24)
        svc.stopConnection(LinkStopReason.USER_DISCONNECT)
        assertEquals(DrivePhase.PAUSED to PauseReason.LINK, state)
    }
}
