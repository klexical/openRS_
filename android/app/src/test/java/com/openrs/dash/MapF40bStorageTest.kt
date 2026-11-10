package com.openrs.dash

import android.Manifest
import android.app.Application
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.location.Location
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.MapF40b
import com.openrs.dash.data.ObdMerge
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.atomic.AtomicInteger

/**
 * #271 enabler — where MAP 0xF40B goes after the decode (see MapF40bReplayTest for the decode itself): the drive recorder
 * stores it per point only from a fresh decode (never a held value), the drive CSV carries it beside TIP with its unit and a
 * saturation flag, Room v15 adds the nullable column without touching older rows, and the generic merge takes a stamped key.
 */
@RunWith(RobolectricTestRunner::class)
class MapF40bStorageTest {

    private class FakeGps : DriveRecorder.LocationSource {
        @Volatile var current: Channel<Location>? = null
        val opened = AtomicInteger()
        override fun updates(): Flow<Location> =
            Channel<Location>(Channel.UNLIMITED).also { current = it; opened.incrementAndGet() }.receiveAsFlow()
        override suspend fun last(): Location? = null
    }

    private lateinit var app: Application
    private lateinit var db: DriveDatabase
    private val recorders = mutableListOf<DriveRecorder>()

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION)
        db = Room.inMemoryDatabaseBuilder(app, DriveDatabase::class.java).allowMainThreadQueries().build()
        db.openHelper.writableDatabase      // open before any recorder (see ObserverNoteEvidenceTest)
    }

    @After fun tearDown() { recorders.forEach { it.cancel() }; db.close() }

    private fun waitUntil(timeoutMs: Long = 5_000, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) { if (cond()) return true; Thread.sleep(5) }
        return cond()
    }

    // ── saturation ─────────────────────────────────────────────────────────

    @Test fun `255 is the byte's ceiling - saturated - and 254 is a reading`() {
        assertTrue(MapF40b.isSaturated(255.0)); assertFalse(MapF40b.isSaturated(254.0)); assertFalse(MapF40b.isSaturated(0.0))
    }

    // ── drive CSV ───────────────────────────────────────────────────────────

    @Test fun `the drive CSV carries MAP beside TIP - value, saturated flag, empty when not recorded`() {
        val pts = listOf(180.0, 255.0, null).mapIndexed { i, map ->
            DrivePointEntity(driveId = 1, timestamp = 1_000L + i, lat = 0.0, lng = 0.0, tipActualKpa = 190.0, mapKpa = map)
        }
        val lines = DriveExportBuilder.buildCsv(pts).lines().filter { it.isNotBlank() }
        val header = lines[1].split(",")
        val tip = header.indexOf("tip_actual_kpa"); val map = header.indexOf("map_f40b_kpa_abs"); val sat = header.indexOf("map_f40b_saturated")
        assertTrue("columns present: $header", tip >= 0 && map > tip && sat == map + 1)
        val rows = lines.drop(2).map { it.split(",") }
        rows.forEach { assertEquals(header.size, it.size) }
        assertEquals(listOf("180", "255", ""), rows.map { it[map] })
        assertEquals(listOf("0", "1", ""), rows.map { it[sat] })
        assertTrue(lines[0], lines[0].startsWith("# openrs_csv_v2"))      // additive: the format line is unchanged
        // Sapphire fixture (web/src/lib/__fixtures__/android-export-rc8-map/): written by the production CSV builder, synthetic
        // times only (relative point times, the fixtures' 2026-01-01 stamp):
        // OPENRS_EXPORT_SAMPLES_MAP=<dir> ./gradlew testDebugUnitTest --tests '*MapF40bStorageTest*'
        System.getenv("OPENRS_EXPORT_SAMPLES_MAP")?.let { dir ->
            val out = java.io.File(dir).apply { mkdirs() }
            val csvName = "drive_20260101_000000.csv"
            java.io.File(out, csvName).writeText(DriveExportBuilder.buildCsv(pts))
            java.io.File(out, "manifest.json").writeText(org.json.JSONObject().apply {
                put("version", 1); put("app", "openRS_"); put("exportedAt", 1_767_225_600_000L)
                put("files", org.json.JSONArray().put(org.json.JSONObject().put("name", csvName).put("type", "drive_csv")))
            }.toString(2))
        }
    }

    // ── recorder ────────────────────────────────────────────────────────────

    /** Decode 150 kPa, point, point, decode 255 kPa on the stamp [secondStamp] picks, point: the three stored MAP values. */
    private fun threePoints(secondStamp: (first: Long, clock: DecodeClock) -> Long): List<Double?> {
        val vs = MutableStateFlow(VehicleState(isConnected = true, speedKph = 50.0))
        val gps = FakeGps()
        val rec = DriveRecorder(app, vs, WeatherRepository(""), db, db.driveDao(), { _, _ -> null }, gps, { }).also { recorders += it }
        rec.startDrive()
        check(waitUntil { rec.driveState.value.let { it.isRecording && it.driveId > 0 } && gps.opened.get() > 0 })
        fun point(i: Int) {
            val before = rec.driveState.value.totalPointCount
            gps.current!!.trySend(Location("t").apply { latitude = 51.5 + i * 1e-4; longitude = -0.1; time = System.currentTimeMillis() })
            check(waitUntil { rec.driveState.value.totalPointCount == before + 1 })
        }
        // A real F40B reply through the production parse + merge, stamped by the test (#305): the first after the drive
        // started (the gate refuses older decodes), the second later than the first
        val clock = DecodeClock()
        val first = clock.next()
        decode(vs, 0x96, first)
        point(0)                                                         // fresh decode → 150 kPa
        point(1)                                                         // no new decode → not a reading
        decode(vs, 0xFF, secondStamp(first, clock))
        point(2)                                                         // the second decode, at the byte's ceiling
        val id = rec.driveState.value.driveId
        runBlocking { withTimeout(10_000) { rec.stopDrive().join() } }
        return db.driveDao().getPoints(id).map { it.mapKpa }
    }

    private fun decode(vs: MutableStateFlow<VehicleState>, b4: Int, atMs: Long) =
        parsePcmAt(byteArrayOf(0x04, 0x62, 0xF4.toByte(), 0x0B, b4.toByte(), 0, 0, 0), vs.value, atMs) { vs.value = ObdMerge.merge(vs.value, it) }

    @Test fun `the recorder stores MAP only from a fresh decode, never a held value`() {
        assertEquals(listOf(150.0, null, 255.0), threePoints { _, clock -> clock.next() })      // 255 stored as is
    }

    /**
     * #305: what the test above did on CI when its two unpaced decodes fell inside one millisecond. The merge takes the
     * second value (an equal stamp is carried), the gate does not see a new decode, so the point has no reading. Kept on
     * purpose: it is the gate's rule, and the reason a test must choose its stamps. The gate refuses a stamp older than
     * the first (a backward clock step) the same way; this case stands for both.
     */
    @Test fun `two decodes on one stamp are one decode to the recorder - the second is not stored`() {
        assertEquals(listOf(150.0, null, null), threePoints { first, _ -> first })
    }

    // ── Room v15 ────────────────────────────────────────────────────────────

    @Test fun `a v13 database upgrades through v14 to v15 - existing points keep every value and read MAP not recorded`() {
        val file = app.getDatabasePath("map-v13.db").apply { parentFile?.mkdirs(); delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { old ->
            javaClass.classLoader!!.getResourceAsStream("db/drive-db-v13-schema.sql")!!.bufferedReader().readLines()
                .filter { it.isNotBlank() }.forEach { old.execSQL(it.trimEnd(';')) }
            fun required(table: String) = ContentValues().also { v ->
                old.rawQuery("PRAGMA table_info($table)", null).use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1); val type = c.getString(2)
                        if (name == "id" || c.getInt(3) == 0 || !c.isNull(4)) continue
                        if (type == "TEXT") v.put(name, "") else v.put(name, 0)
                    }
                }
            }
            val drive = required("drives").apply { put("startTime", 1_000L); put("endTime", 61_000L) }
            val driveId = old.insertOrThrow("drives", null, drive)
            val pt = required("drive_points").apply {
                put("driveId", driveId); put("timestamp", 2_000L); put("tipActualKpa", 187.5); put("baroKpa", 96.5); put("boostSource", "TIP_033E")
            }
            old.insertOrThrow("drive_points", null, pt)
            old.version = 13
        }
        val upgraded = Room.databaseBuilder(app, DriveDatabase::class.java, file.absolutePath)
            .addMigrations(DriveDatabase.MIGRATION_13_14, DriveDatabase.MIGRATION_14_15).allowMainThreadQueries().build()
        try {
            val row = upgraded.driveDao().getRecentDrives().single()                 // Room validates the v15 schema on open
            val id = row.id
            val p = upgraded.driveDao().getPoints(id).single()
            assertNull("never backfilled", p.mapKpa)
            assertEquals(187.5, p.tipActualKpa, 1e-9)
            assertEquals(96.5, p.baroKpa!!, 1e-9)
            assertEquals("TIP_033E", p.boostSource)
            assertEquals(15, upgraded.openHelper.readableDatabase.version)
        } finally { upgraded.close() }
    }

    // ── generic merge controls ──────────────────────────────────────────────

    @Test fun `control - a reply that stamped MAP is taken, also when it reads lower`() {
        val now = System.currentTimeMillis()
        val current = VehicleState(isConnected = true, genericValues = mapOf(MapF40b.TAG to 200.0), fieldLastUpdateMs = mapOf(MapF40b.TAG to now - 2_000))
        var merged = current
        ObdResponseParser.parsePcmResponse(byteArrayOf(0x04, 0x62, 0xF4.toByte(), 0x0B, 0x5A, 0, 0, 0), current) { merged = ObdMerge.merge(current, it) }
        assertEquals(90.0, MapF40b.kpa(merged)!!, 0.0)
        assertTrue((merged.fieldLastUpdateMs[MapF40b.TAG] ?: 0L) > now - 2_000)
    }

    @Test fun `control - other generic keys merge by their own stamp the same way`() {
        val now = System.currentTimeMillis()
        val current = VehicleState(genericValues = mapOf("FuelLevel_OBD" to 40.0, MapF40b.TAG to 100.0),
            fieldLastUpdateMs = mapOf("FuelLevel_OBD" to now - 1_000, MapF40b.TAG to now - 1_000))
        var merged = current
        ObdResponseParser.parsePcmResponse(byteArrayOf(0x04, 0x62, 0xF4.toByte(), 0x2F, 0x80.toByte(), 0, 0, 0), current) { merged = ObdMerge.merge(current, it) }
        assertEquals(128 * 100.0 / 255.0, merged.genericValues["FuelLevel_OBD"]!!, 1e-9)    // stamped by this reply → taken
        assertEquals(100.0, merged.genericValues[MapF40b.TAG]!!, 0.0)                        // not stamped by it → kept
    }
}
