package com.openrs.dash

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveThermalReview
import com.openrs.dash.data.DriveThermalSensor
import com.openrs.dash.data.OilPtuMapping
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DriveExportBuilder
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * #229 on the production DriveRecorder + Room database (Robolectric SQLite): a drive recorded from synthetic 0x0F8
 * frames stores byte 7 as oil and byte 1 as PTU and is saved with [OilPtuMapping.CURRENT]; a real v13 database (the
 * schema Room generated for v13) upgrades to v14 with every existing drive left as it was and its mapping null.
 */
@RunWith(RobolectricTestRunner::class)
class OilPtuRecorderTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val vs = MutableStateFlow(VehicleState())
    private val gps = FakeGps()
    private val recorders = mutableListOf<DriveRecorder>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
        File(app.filesDir, "route_thumbs").deleteRecursively()
    }

    @After fun tearDown() {
        recorders.forEach { it.cancel() }
        db.close()
    }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    /** A connected car whose oil / PTU come from one synthetic 0x0F8 frame (decoded + stamped like processCanFrame). */
    private fun car(byte1: Int, byte7: Int): VehicleState {
        val now = System.currentTimeMillis()
        val prev = VehicleState(isConnected = true, rpm = 3000.0, speedKph = 80.0, coolantTempC = 90.0)
            .markFresh("coolantTempC", now = now)
        val decoded = CanDecoder.decode(CanDecoder.ID_ENGINE_TEMPS, byteArrayOf(0, byte1.toByte(), 0, 0, 0, 0, 0, byte7.toByte()), prev)!!
        return CanDecoder.stampFresh(CanDecoder.ID_ENGINE_TEMPS, prev, decoded, now)
    }

    @Test
    fun `a recorded drive stores byte 7 as oil and byte 1 as PTU and is saved with this build's mapping`() {
        val rec = DriveRecorder(app, vs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, {})
            .also { recorders += it }
        vs.value = car(94, 127)
        rec.startDrive()
        assertTrue(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 })
        val id = rec.driveState.value.driveId
        for (i in 0 until 4) {
            val before = rec.driveState.value.totalPointCount
            vs.value = car(94 + i, 127 + i)                                  // PTU 44 → 47, oil 67 → 70
            gps.current!!.trySend(Location("test").apply {
                latitude = 51.5 + i * 1e-4; longitude = -0.1; speed = 22f; time = System.currentTimeMillis()
            })
            assertTrue("point $i", waitUntil { rec.driveState.value.totalPointCount == before + 1 })
        }
        runBlocking { withTimeout(15_000) { rec.stopDrive().join() } }

        val dao = db.driveDao()
        val row = dao.getDrive(id)!!
        assertEquals(OilPtuMapping.CURRENT, row.oilPtuMapping)
        assertEquals(70.0, row.peakOilTempC, 1e-9)
        val pts = dao.getPoints(id)
        assertEquals(listOf(67.0, 68.0, 69.0, 70.0), pts.map { it.oilTempC })
        assertEquals(listOf(44.0, 45.0, 46.0, 47.0), pts.map { it.ptuTempC })
        val review = DriveThermalReview.from(row.thermalPeaksJson)
        assertEquals(70.0, review.sensor(DriveThermalSensor.OIL)!!.peakC, 1e-9)
        assertEquals(47.0, review.sensor(DriveThermalSensor.PTU)!!.peakC, 1e-9)
        val csv = DriveExportBuilder.driveTrackFiles(row, pts, "t", null, "drive.csv").single().second
        assertEquals("# openrs_csv_v2 oil_ptu_mapping=${OilPtuMapping.CURRENT}", csv.lines()[0])
    }

    @Test
    fun `a v13 database upgrades to v14 - existing drives keep every value and get no mapping`() {
        val file = app.getDatabasePath("oilptu-v13.db").apply { parentFile?.mkdirs(); delete() }
        val thermal = """{"v":2,"segments":[{"profile":"street","startMs":1,"limits":{"Oil":{"warnC":120,"critC":130}},"peaks":{"Oil":125}}]}"""
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            javaClass.classLoader!!.getResourceAsStream("db/drive-db-v13-schema.sql")!!.bufferedReader().readLines()
                .filter { it.isNotBlank() }.forEach { old.execSQL(it.trimEnd(';')) }
            // Every NOT NULL column without a default needs a value; the ones under test are set explicitly
            val values = ContentValues()
            old.rawQuery("PRAGMA table_info(drives)", null).use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1); val type = c.getString(2)
                    if (name == "id" || c.getInt(3) == 0 || !c.isNull(4)) continue
                    if (type == "TEXT") values.put(name, "") else values.put(name, 0)
                }
            }
            values.put("startTime", 1_000L); values.put("endTime", 61_000L)
            values.put("startOilTempC", 60.0); values.put("peakOilTempC", 125.0); values.put("endOilTempC", 110.0)
            values.put("thermalPeaksJson", thermal); values.put("boostPolicy", "rc7-boost-v3")
            old.insertOrThrow("drives", null, values)
            old.version = 13
        }

        val upgraded = Room.databaseBuilder(app, DriveDatabase::class.java, file.absolutePath)
            .addMigrations(DriveDatabase.MIGRATION_13_14, DriveDatabase.MIGRATION_14_15).allowMainThreadQueries().build()   // v15 since #271
        try {
            val row = upgraded.driveDao().getRecentDrives().single()             // Room validates the v14 schema on open
            assertNull(row.oilPtuMapping)                                        // never backfilled
            assertEquals(60.0, row.startOilTempC, 1e-9)
            assertEquals(125.0, row.peakOilTempC, 1e-9)
            assertEquals(110.0, row.endOilTempC, 1e-9)
            assertEquals(thermal, row.thermalPeaksJson)                          // record untouched
            assertEquals("rc7-boost-v3", row.boostPolicy)
            assertEquals(15, upgraded.openHelper.readableDatabase.version)
        } finally { upgraded.close() }
    }
}
