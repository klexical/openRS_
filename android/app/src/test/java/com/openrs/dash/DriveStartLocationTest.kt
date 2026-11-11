package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.KnockSessionLog
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.service.CanDataService
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * #311: a new drive used the previous drive's last position until its own first fix (startDrive copied it, STOP never
 * cleared it). A position now belongs to a drive only if this drive received it, and a note or the start/finish line is
 * placed only with such a fix at most LapTimer.MAX_FIX_AGE_MS old. Session knock events outside a drive, or before its
 * first fix, carry no position. Real DriveRecorder on in-memory Room, synthetic GPS through the LocationSource seam; the
 * knock cases run the real CanDataService merge with this recorder installed as the app's. Synthetic data only.
 *
 * Test fixes leave elapsedRealtimeNanos at 0, so the recorder ages them by the wall clock against Location.time
 * (Robolectric's SystemClock is shadowed and would give a meaningless monotonic age).
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class DriveStartLocationTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        /**
         * The platform's last-known fix: none, unless a test holds one back. The held reply goes to the first caller only
         * (TRIP's idle fetch); the recorder's own start-up lookup (weather) gets none and is not blocked.
         */
        @Volatile var held: kotlinx.coroutines.CompletableDeferred<Location?>? = null
        override suspend fun last(): Location? = synchronized(this) { held.also { held = null } }?.await()
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val gps = FakeGps()
    private lateinit var rec: DriveRecorder
    private val appVs get() = OpenRSDashApp.instance.vehicleState
    private val dao get() = db.driveDao()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
        rec = DriveRecorder(app, appVs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, { })
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        KnockSessionLog.reset(0L)
    }

    @After fun tearDown() {
        TestServices.destroyAll()
        rec.cancel(); db.close(); appVs.value = VehicleState(); KnockSessionLog.reset()
    }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    private val first = 10.0 to 20.0        // drive 1's last fix

    private fun startDrive(): Long {
        val before = rec.driveState.value.driveId
        val opened = gps.opened.get()
        appVs.value = VehicleState(isConnected = true, speedKph = 62.0, rpm = 2_400.0)
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 && it.driveId != before } && gps.opened.get() > opened }) {
            "drive did not start"
        }
        return rec.driveState.value.driveId
    }

    private fun fix(lat: Double, lng: Double, ageMs: Long = 0) {
        val before = rec.driveState.value.totalPointCount
        gps.current!!.trySend(Location("test").apply { latitude = lat; longitude = lng; speed = 17f; time = System.currentTimeMillis() - ageMs })
        check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "fix not recorded" }
    }

    private fun stop() = runBlocking { withTimeout(10_000) { rec.stopDrive().join() } }

    /** Drive 1 with one fix, then STOP: the state a new drive used to start from. */
    private fun firstDriveThenStop(): Long {
        val id = startDrive(); fix(first.first, first.second); stop(); return id
    }

    private fun notes() = DiagnosticLogger.sessionEvents.filter { it.type == "NOTE" }

    // ── MARK / observer's note ────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a mark in a new drive before its first fix is not placed at the previous drive's position`() {
        firstDriveThenStop()
        val second = startDrive()
        assertNull("the new drive holds no position of its own yet", rec.driveState.value.currentLocation)
        rec.addBookmark("Cluster showed SPORT")
        Thread.sleep(100)
        assertEquals("no bookmark row in the new drive", 0, dao.getBookmarks(second).size)
        assertEquals("Observer note: Cluster showed SPORT (no GPS fix, not on the map)", notes().single().message)
    }

    @Test fun `once the new drive has its first fix, a mark is placed at that fix`() {
        firstDriveThenStop()
        val second = startDrive()
        fix(30.0, 40.0)
        rec.addBookmark("here")
        assertTrue(waitUntil { dao.getBookmarks(second).size == 1 })
        val b = dao.getBookmarks(second).single()
        assertEquals(listOf(30.0, 40.0), listOf(b.lat, b.lng))
        assertEquals("Observer note: here", notes().single().message)
    }

    @Test fun `after STOP the recorder holds no position - nothing reads the drive's last fix as current`() {
        firstDriveThenStop()
        assertNull(rec.driveState.value.currentLocation)
    }

    @Test fun `mid-drive, a fix older than the lap rule's limit places nothing - a GPS gap is no position`() {
        val id = startDrive()
        fix(30.0, 40.0, ageMs = com.openrs.dash.data.LapTimer.MAX_FIX_AGE_MS + 3_000)
        rec.addBookmark("in a gap")
        Thread.sleep(100)
        assertEquals(0, dao.getBookmarks(id).size)
        assertEquals("Observer note: in a gap (no GPS fix, not on the map)", notes().single().message)
    }

    // ── SET S/F (TRIP's button calls setStartFinishHere) ──────────────────────────────────────────────────────────

    private val line get() = rec.lapTimerForUi().startFinish

    @Test fun `SET S-F in a new drive before its first fix is refused - no line at the previous drive's position`() {
        firstDriveThenStop()
        startDrive()
        assertEquals(false, rec.setStartFinishHere())
        assertNull(line)
    }

    @Test fun `SET S-F during a mid-drive GPS gap is refused - a fresh fix sets the line there`() {
        startDrive()
        fix(30.0, 40.0, ageMs = com.openrs.dash.data.LapTimer.MAX_FIX_AGE_MS + 3_000)
        assertEquals(false, rec.setStartFinishHere())
        assertNull(line)
        fix(31.0, 41.0)
        assertEquals(true, rec.setStartFinishHere())
        assertEquals(listOf(31.0, 41.0), line!!.let { listOf(it.lat, it.lng) })
    }

    @Test fun `a refused SET S-F tap puts NO GPS on the chip for the window - a second refusal does not stack, a set line shows none`() {
        startDrive()
        val cue = com.openrs.dash.ui.trip.SfCue()
        val t0 = 1_000_000L
        assertEquals(false, com.openrs.dash.ui.trip.onSetStartFinish(rec, cue, t0))          // no fix yet
        assertNull(line)
        assertTrue("the chip reads NO GPS", cue.isShowing(t0))
        assertEquals(com.openrs.dash.ui.trip.SF_CUE_MS, cue.remainingMs(t0))
        assertEquals(false, com.openrs.dash.ui.trip.onSetStartFinish(rec, cue, t0 + 1_500))  // refused again inside the window
        assertEquals("not restarted or extended", com.openrs.dash.ui.trip.SF_CUE_MS - 1_500, cue.remainingMs(t0 + 1_500))
        assertTrue(!cue.isShowing(t0 + com.openrs.dash.ui.trip.SF_CUE_MS))                    // back to SET S/F after the window
        fix(31.0, 41.0)
        val t1 = t0 + 10_000
        assertEquals(true, com.openrs.dash.ui.trip.onSetStartFinish(rec, cue, t1))
        assertTrue("no cue when the line is set", !cue.isShowing(t1))
        assertEquals(listOf(31.0, 41.0), line!!.let { listOf(it.lat, it.lng) })
    }

    // ── TRIP's dot (tripDot, what the live map draws) ─────────────────────────────────────────────────────────────

    private fun loc(lat: Double) = Location("test").apply { latitude = lat; longitude = 0.0; time = System.currentTimeMillis() }

    @Test fun `TRIP draws no dot in a drive before its first fix - not the previous drive's, not an idle fetch from before it`() {
        val idleFetchedAt = System.currentTimeMillis()
        val idle = loc(1.0)
        Thread.sleep(2)
        firstDriveThenStop()
        startDrive()
        assertNull(com.openrs.dash.ui.trip.tripDot(rec.driveState.value, idle, idleFetchedAt))
        fix(30.0, 40.0)
        assertEquals(30.0, com.openrs.dash.ui.trip.tripDot(rec.driveState.value, idle, idleFetchedAt)!!.latitude, 0.0)
    }

    @Test fun `after STOP TRIP draws no dot from before the drive - an idle fetch made after the drive started is drawn`() {
        val before = System.currentTimeMillis()
        Thread.sleep(2)
        firstDriveThenStop()
        val state = rec.driveState.value
        assertNull("the drive's last fix is not drawn as current", com.openrs.dash.ui.trip.tripDot(state, null, 0L))
        assertNull("an idle fetch from before the drive is not drawn", com.openrs.dash.ui.trip.tripDot(state, loc(1.0), before))
        val fetchedNow = System.currentTimeMillis()
        assertEquals(2.0, com.openrs.dash.ui.trip.tripDot(state, loc(2.0), fetchedNow)!!.latitude, 0.0)
    }

    @Test fun `an idle fetch asked for in the drive's start millisecond is not drawn - only one asked for later is`() {
        firstDriveThenStop()
        val state = rec.driveState.value
        assertNull("a tie is not after the start", com.openrs.dash.ui.trip.tripDot(state, loc(1.0), state.startTime))
        assertEquals(1.0, com.openrs.dash.ui.trip.tripDot(state, loc(1.0), state.startTime + 1)!!.latitude, 0.0)
    }

    @Test fun `an idle fetch still in flight when a drive starts is not drawn after that drive's STOP`() {
        val reply = kotlinx.coroutines.CompletableDeferred<Location?>()
        gps.held = reply
        val fetch = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default).async {
            com.openrs.dash.ui.trip.idleFetch(rec)
        }
        check(waitUntil { gps.held == null }) { "the idle fetch did not ask" }   // the platform call is waiting
        firstDriveThenStop()                                                 // a drive starts and stops meanwhile
        reply.complete(loc(1.0))                                     // the reply is the pre-drive position
        val (idle, stamp) = runBlocking { withTimeout(5_000) { fetch.await() } }!!
        assertNull(com.openrs.dash.ui.trip.tripDot(rec.driveState.value, idle, stamp))
    }

    @Test fun `before any drive TRIP draws the idle fetch`() {
        assertEquals(1.0, com.openrs.dash.ui.trip.tripDot(rec.driveState.value, loc(1.0), System.currentTimeMillis())!!.latitude, 0.0)
    }

    // ── Session knock log (the real CanDataService merge) ────────────────────────────────────────────────────────

    /** The service reads `OpenRSDashApp.instance.driveRecorder` (a `by lazy`); point it at [rec]. */
    private fun install() {
        val f = OpenRSDashApp::class.java.getDeclaredField("driveRecorder\$delegate").apply { isAccessible = true }
        f.set(OpenRSDashApp.instance, lazyOf(rec))
    }

    /** One OBD reply with a fresh 2.5° KR on cylinder 1, through the service's own merge (the knock log's only feed). */
    private fun knockThroughService(svc: CanDataService) {
        Thread.sleep(2)                                                      // a new stamp: the gate takes each decode once
        val now = System.currentTimeMillis()
        val reply = appVs.value.copy(ignCorrCyl1 = 2.5, fieldLastUpdateMs = appVs.value.fieldLastUpdateMs + ("ignCorrCyl1" to now))
        val m = CanDataService::class.java.getDeclaredMethod("mergeObdState", VehicleState::class.java).apply { isAccessible = true }
        m.invoke(svc, reply)
    }

    private fun lastKnockPosition() = KnockSessionLog.events.value.last().let { it.lat to it.lng }

    @Test fun `a session knock in a mid-drive GPS gap carries no position - a fresh fix gives it one`() {
        install(); val svc = TestServices.create()
        startDrive()
        fix(30.0, 40.0, ageMs = com.openrs.dash.data.LapTimer.MAX_FIX_AGE_MS + 3_000)
        knockThroughService(svc)
        assertEquals(null to null, lastKnockPosition())
        fix(31.0, 41.0)
        knockThroughService(svc)
        assertEquals(31.0 to 41.0, lastKnockPosition())
    }

    @Test fun `the age limit is inclusive - a fix exactly MAX_FIX_AGE_MS old is fresh, one ms more is not`() {
        startDrive()
        fix(30.0, 40.0)
        val t = rec.driveState.value.currentLocation!!.time
        val limit = com.openrs.dash.data.LapTimer.MAX_FIX_AGE_MS
        assertEquals(30.0, rec.freshFix(t + limit)!!.latitude, 0.0)
        assertNull(rec.freshFix(t + limit + 1))
    }

    @Test fun `a session knock after STOP carries no position`() {
        install(); val svc = TestServices.create()
        firstDriveThenStop()
        knockThroughService(svc)
        assertEquals(null to null, lastKnockPosition())
    }

    @Test fun `a session knock in a new drive before its first fix carries no position - after it, that fix`() {
        install(); val svc = TestServices.create()
        firstDriveThenStop()
        startDrive()
        knockThroughService(svc)
        assertEquals(null to null, lastKnockPosition())
        fix(30.0, 40.0)
        knockThroughService(svc)
        assertEquals(30.0 to 40.0, lastKnockPosition())
    }
}
