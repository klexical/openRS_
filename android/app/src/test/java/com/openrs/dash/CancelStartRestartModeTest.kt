package com.openrs.dash

import android.app.Application
import android.app.Service
import android.content.Intent
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.LinkPhase
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * #550: Android restarts a killed service by the value its LAST onStartCommand returned. A Cancel the app refuses (the link
 * is up, or a drive records: `LinkPresentation.offersCancel`) used to return START_NOT_STICKY too, so a stale notification
 * Cancel delivered to a live link took its sticky restart away. Only an accepted Cancel ends the link and may return
 * START_NOT_STICKY. The real service (TestServices) gets the notification action's intent through its own onStartCommand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class CancelStartRestartModeTest {
    private lateinit var app: Application
    private lateinit var service: CanDataService
    private var db: DriveDatabase? = null
    private var rec: DriveRecorder? = null
    private val link get() = CanDataService.link

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
    }

    @After fun tearDown() {
        TestServices.destroyAll()
        rec?.cancel(); db?.close()
        if (link.phase != LinkPhase.OFF) link.end(LinkPhase.OFF)
        link.clearManualConnect()                                         // an accepted Cancel is persisted (#212)
    }

    private fun cancelIntent() = Intent(app, CanDataService::class.java).setAction(CanDataService.ACTION_CANCEL_CONNECT)

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    @Test fun `an accepted Cancel during the initial attempt returns START_NOT_STICKY`() {
        service = TestServices.create()
        link.begin()                                                      // an initial attempt: CONNECTING, nothing recording
        assertEquals(Service.START_NOT_STICKY, service.onStartCommand(cancelIntent(), 0, 1))
        assertTrue("the Cancel was accepted", link.userCancelled)
    }

    @Test fun `a Cancel refused while connected keeps START_STICKY`() {
        service = TestServices.create()
        val gen = link.begin()
        assertTrue(link.publish(gen, LinkPhase.CONNECTED) { })
        assertEquals(Service.START_STICKY, service.onStartCommand(cancelIntent(), 0, 1))
        assertFalse("the Cancel was refused", link.userCancelled)
        assertEquals(LinkPhase.CONNECTED, link.phase)
    }

    @Test fun `a Cancel refused while a drive records keeps START_STICKY`() {
        val d = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build().also { db = it }
        d.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
        val noGps = object : DriveRecorder.LocationSource {
            override fun updates(): Flow<Location> = emptyFlow()
            override suspend fun last(): Location? = null
        }
        val r = DriveRecorder(app, OpenRSDashApp.instance.vehicleState, WeatherRepository(""), d, d.driveDao(), { _, _ -> null }, noGps, { })
            .also { rec = it }
        CanDataService.recorderProvider = { r }                           // #316 seam 5; destroyAll puts the app's back
        service = TestServices.create()
        OpenRSDashApp.instance.vehicleState.value = VehicleState(isConnected = true)
        r.startDrive()
        assertTrue(waitUntil { r.driveState.value.isRecording })
        link.begin()                                                      // still the initial attempt, but a drive records
        assertEquals(Service.START_STICKY, service.onStartCommand(cancelIntent(), 0, 1))
        assertFalse("the Cancel was refused", link.userCancelled)
        assertEquals(LinkPhase.CONNECTING, link.phase)
        kotlinx.coroutines.runBlocking { r.stopDrive().join() }
    }
}
