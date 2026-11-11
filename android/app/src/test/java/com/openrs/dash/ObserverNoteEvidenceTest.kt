package com.openrs.dash

import android.Manifest
import android.app.Application
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveBookmarkEntity
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.DriveExportBuilder
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import com.openrs.dash.ui.trip.NoteFormState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * REQ-rc8-app-capture-improvements, "Bookmarks tied to evidence": a drive bookmark with a label ("Cluster showed SPORT") is
 * an observer's note. It is stored with the drive as before, and it is also a session event on the diagnostic timeline
 * (the clock that requests, link events and the FPS timeline use), so a reader can line it up with what the app sent and
 * received. The drive export gives each bookmark a time relative to the drive's start and names its kind.
 * Real DriveRecorder on in-memory Room with synthetic GPS; the real export builders. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = OpenRSDashApp::class)
class ObserverNoteEvidenceTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
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
        // Opened here, before a recorder exists. The recorder's start-up query otherwise makes the first open on its own
        // thread, and a teardown that reaches db.close() meanwhile deadlocks: close() holds Room's close lock and waits
        // for the open helper's lock; the open holds that lock and, in onOpen, waits for the close lock.
        db.openHelper.writableDatabase
        rec = DriveRecorder(app, appVs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, { })
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
    }

    @After fun tearDown() { rec.cancel(); db.close(); appVs.value = VehicleState() }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    private fun start(fixes: Int): Long {
        appVs.value = VehicleState(isConnected = true, speedKph = 62.0, rpm = 2_400.0)
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 }) { "drive did not start" }
        repeat(fixes) { i ->
            val before = rec.driveState.value.totalPointCount
            gps.current!!.trySend(Location("test").apply { latitude = 10.0 + i * 1e-4; longitude = 20.0; speed = 17f; time = System.currentTimeMillis() })
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
        }
        return rec.driveState.value.driveId
    }

    private fun notes() = DiagnosticLogger.sessionEvents.filter { it.type == "NOTE" }

    @Test fun `a labelled bookmark is stored with the drive and logged as an observer note on the session timeline`() {
        val id = start(fixes = 2)
        val before = DiagnosticLogger.sessionEvents.lastOrNull()?.relMs ?: 0L
        rec.addBookmark("Cluster showed SPORT")
        assertTrue(waitUntil { dao.getBookmarks(id).size == 1 })
        assertEquals("Cluster showed SPORT", dao.getBookmarks(id).single().label)
        val n = notes().single()
        assertEquals("Observer note: Cluster showed SPORT", n.message)
        assertTrue("on the session clock, after what came before it", n.relMs >= before)

        val (text, detail) = DiagnosticReportBuilder.buildSummaryAndDetail("t")
        assertTrue("the summary's event list carries it", "Observer note: Cluster showed SPORT" in text)
        val events = JSONObject(detail).getJSONArray("sessionEvents")
        assertTrue("the JSON detail carries it as a NOTE event",
            (0 until events.length()).map { events.getJSONObject(it) }.any { it.getString("type") == "NOTE" && it.getString("message") == n.message })
    }

    @Test fun `an unlabelled bookmark is still a marker on the timeline`() {
        val id = start(fixes = 1)
        rec.addBookmark()
        assertTrue(waitUntil { dao.getBookmarks(id).size == 1 })
        assertEquals("Observer note (no text)", notes().single().message)
    }

    @Test fun `before the first GPS fix the note still reaches the timeline, and no bookmark row is invented`() {
        val id = start(fixes = 0)
        rec.addBookmark("Cluster showed TRACK")
        assertEquals("Observer note: Cluster showed TRACK (no GPS fix, not on the map)", notes().single().message)
        Thread.sleep(100)
        assertEquals(0, dao.getBookmarks(id).size)
    }

    @Test fun `not recording - nothing is logged or stored`() {
        rec.addBookmark("Cluster showed SPORT")
        Thread.sleep(50)
        assertEquals(emptyList<Any>(), notes())
    }

    @Test fun `the event text is bounded printable ASCII with identifiers masked - the stored label is only trimmed and bounded`() {
        val id = start(fixes = 1)
        rec.addBookmark("  café\n AA:BB:CC:DD:EE:FF " + "x".repeat(200))
        assertTrue(waitUntil { dao.getBookmarks(id).size == 1 })
        val msg = notes().single().message
        assertTrue(msg, msg.all { it in ' '..'~' } && "AA:BB" !in msg && "<mac>" in msg && msg.length <= "Observer note: ".length + 60)
        val stored = dao.getBookmarks(id).single().label
        assertTrue(stored, stored.startsWith("café") && stored.length == 60)
    }

    // ── Stamped when asked for (the long-press), not when saved ────────────────────────────────────────────────

    /** TRIP's own wiring: the form state over the real recorder, as TripPage builds it. */
    private fun noteForm() = NoteFormState(rec::markNow, rec::addBookmark)

    @Test fun `a note typed in the form carries the moment the form opened - time, place and readings - not the save`() {
        val id = start(fixes = 1)                                           // fix 0: lat 10.0, 2,400 rpm
        val form = noteForm()
        val before = System.currentTimeMillis()
        form.open()                                                         // the long-press
        val after = System.currentTimeMillis()
        assertTrue(form.isOpen)

        Thread.sleep(120)                                                   // the driver types; the car moves on
        DiagnosticLogger.event("STATE", "something later")
        appVs.value = appVs.value.copy(rpm = 5_100.0, speedKph = 99.0)
        val n = rec.driveState.value.totalPointCount
        gps.current!!.trySend(Location("test").apply { latitude = 10.5; longitude = 20.0; time = System.currentTimeMillis() })
        check(waitUntil { rec.driveState.value.totalPointCount == n + 1 })
        form.save("Cluster showed SPORT")
        assertFalse(form.isOpen)

        assertTrue(waitUntil { dao.getBookmarks(id).size == 1 })
        val b = dao.getBookmarks(id).single()
        assertTrue("stored at the long-press (${b.timestamp} in $before..$after)", b.timestamp in before..after)
        assertEquals("where the car was then", 10.0, b.lat, 1e-9)
        assertEquals("what it read then", listOf(2_400, 62.0), listOf(b.rpm, b.speedKph))

        val events = DiagnosticLogger.sessionEvents
        val note = events.indexOfFirst { it.type == "NOTE" }; val later = events.indexOfFirst { it.message == "something later" }
        assertTrue("on the timeline before what happened while typing: $events", note in 0 until later)
        assertTrue("at least the typing time earlier", events[later].relMs - events[note].relMs >= 100)
        assertEquals("the timeline stays in time order", events.map { it.relMs }.sorted(), events.map { it.relMs })
    }

    @Test fun `a tap on MARK is still stamped now`() {
        val id = start(fixes = 1)
        val before = System.currentTimeMillis()
        rec.addBookmark()
        assertTrue(waitUntil { dao.getBookmarks(id).size == 1 })
        assertTrue(dao.getBookmarks(id).single().timestamp in before..System.currentTimeMillis())
    }

    @Test fun `a form opened with no drive recording, or saved after its drive ended, records nothing`() {
        val idle = noteForm().also { it.open() }
        val id = start(fixes = 1)
        idle.save("opened before the drive")                                // no mark was taken: nothing to stamp
        val form = noteForm().also { it.open() }
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { rec.stopDrive().join() } }
        form.save("typed after STOP")
        Thread.sleep(100)
        assertEquals(emptyList<Any>(), notes())
        assertEquals(0, dao.getBookmarks(id).size)
    }

    @Test fun `a note opened in one drive and saved after a new drive started records nothing - in neither drive`() {
        val first = start(fixes = 1)                                        // the mark has a place: a row would be written
        val form = noteForm().also { it.open() }
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withTimeout(10_000) { rec.stopDrive().join() } }
        val second = start(fixes = 0)
        assertTrue("a new drive is recording ($first → $second)", second > 0 && second != first)
        form.save("typed across STOP and START")
        Thread.sleep(100)
        assertEquals("no note on the new drive's timeline", emptyList<Any>(), notes())
        assertEquals(0, dao.getBookmarks(first).size)
        assertEquals(0, dao.getBookmarks(second).size)
        noteForm().also { it.open() }.save("opened in the new drive")      // control: a form of this drive records
        // Placed or not is not checked here: a new drive still starts with the last drive's position (kept for the TRIP
        // dot in #236), so before its first fix this note takes that position. That is a known defect with its own fix,
        // which tightens this line to "no GPS fix".
        assertTrue(notes().single().message.startsWith("Observer note: opened in the new drive"))
    }

    @Test fun `closing the form drops its mark - the next note is not stamped with an old moment`() {
        val id = start(fixes = 1)
        val form = noteForm()
        form.open(); form.close()
        Thread.sleep(60)
        val reopened = System.currentTimeMillis()
        form.open(); form.save("second try")
        assertTrue(waitUntil { dao.getBookmarks(id).size == 1 })
        assertTrue(dao.getBookmarks(id).single().timestamp >= reopened)
    }

    // ── Drive export ──────────────────────────────────────────────────────────────────────────────────────────────

    private fun profile(vararg b: DriveBookmarkEntity): JSONObject =
        JSONObject(DriveExportBuilder.buildProfileJson(DriveEntity(id = 1, startTime = 1_000_000, endTime = 1_060_000), emptyList(), b.toList()))

    @Test fun `the drive export gives each bookmark a time from the drive's start and names it an observer note`() {
        val j = profile(
            DriveBookmarkEntity(driveId = 1, timestamp = 1_012_500, lat = 10.0, lng = 20.0, label = "Cluster showed SPORT", speedKph = 62.0, rpm = 2400),
            DriveBookmarkEntity(driveId = 1, timestamp = 1_030_000, lat = 10.0, lng = 20.0))
        val a = j.getJSONArray("bookmarks")
        assertEquals(listOf(12_500L, 30_000L), (0 until 2).map { a.getJSONObject(it).getLong("relMs") })
        assertEquals(listOf("observer_note", "observer_note"), (0 until 2).map { a.getJSONObject(it).getString("kind") })
        val first = a.getJSONObject(0)
        assertEquals("the fields older readers use are unchanged",
            listOf(1_012_500L, "Cluster showed SPORT", 62.0, 2400),
            listOf(first.getLong("timestamp"), first.getString("label"), first.getDouble("speedKph"), first.getInt("rpm")))
    }

    @Test fun `a drive without bookmarks exports no bookmarks key, as before`() {
        assertFalse(profile().has("bookmarks"))
    }
}
