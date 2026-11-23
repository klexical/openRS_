package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * #516: 0x1B0 reports Sport-or-Track (nibble 1) but no 0x420 has arrived on this connection, so the mode is not known. A
 * drive recorded meanwhile stores "--" for those points and its breakdown says so, never "Normal" (before the fix the state
 * kept its NORMAL default and the drive showed a Normal share and a Normal timeline segment). Once 0x420 arrives the next
 * 0x1B0 resolves Sport as before. The real decoder feeds the real DriveRecorder on an in-memory Room database.
 */
@RunWith(RobolectricTestRunner::class)
class DriveModeUnknownRecordTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    private lateinit var db: DriveDatabase
    private lateinit var rec: DriveRecorder
    private val vs = MutableStateFlow(VehicleState(isConnected = true, rpm = 2_500.0, speedKph = 50.0))
    private val gps = FakeGps()
    private var fixN = 0

    private val sportOrTrack1B0 = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x10, 0x00)
    private val sport420 = byteArrayOf(0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x11, 0xCC.toByte())

    @Before fun setUp() {
        CanDecoder.resetSessionState()                      // a new connection: no 0x420 yet
        val app: Application = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase
        rec = DriveRecorder(app, vs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, { })
    }

    @After fun tearDown() { rec.cancel(); db.close(); CanDecoder.resetSessionState() }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    /** The production path: decode, then stamp, as the frame dispatch does. */
    private fun frame(id: Int, data: ByteArray) {
        val before = vs.value
        val decoded = CanDecoder.decode(id, data, before) ?: return
        vs.value = CanDecoder.stampFresh(id, before, decoded, System.currentTimeMillis())
    }

    private fun fixes(n: Int) = repeat(n) {
        val target = rec.driveState.value.totalPointCount + 1
        val i = fixN++
        gps.current!!.trySend(Location("test").apply {
            latitude = 51.5 + i * 1e-5; longitude = -0.1; speed = 14f; time = System.currentTimeMillis()
        })
        check(waitUntil { rec.driveState.value.totalPointCount == target }) { "fix $i not recorded" }
    }

    @Test fun `points before the first 0x420 are stored as unknown, then Sport once it arrives`() {
        frame(0x1B0, sportOrTrack1B0)                       // Sport or Track, not yet told apart

        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 }) { "no start" }
        val id = rec.driveState.value.driveId
        fixes(6)

        frame(0x420, sport420)                              // the first 0x420 ...
        frame(0x1B0, sportOrTrack1B0)                       // ... and the next 0x1B0 resolves Sport
        assertEquals(DriveMode.SPORT, vs.value.driveMode)
        fixes(4)

        runBlocking { withTimeout(10_000) { rec.stopDrive().join() } }
        val dao = db.driveDao()
        assertEquals(List(6) { "--" } + List(4) { "Sport" }, dao.getPoints(id).map { it.driveMode })
        val breakdown = org.json.JSONObject(dao.getDrive(id)!!.driveModeBreakdown)
        assertEquals("no Normal share", setOf("--", "Sport"), breakdown.keys().asSequence().toSet())
        assertEquals(0.6, breakdown.getDouble("--"), 1e-9)
        assertEquals(0.4, breakdown.getDouble("Sport"), 1e-9)
    }
}
