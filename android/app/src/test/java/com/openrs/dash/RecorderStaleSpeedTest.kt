package com.openrs.dash

import android.location.Location
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DriveExportBuilder
import com.openrs.dash.service.DriveRecorder
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #515 — while frames stopped arriving, a drive stored one held speed on dozens of consecutive points: VehicleState keeps
 * the last 0x130 decode, and the recorder wrote `vs.speedKph` with no age check, so the CSV, GPX, RaceChrono and
 * TrackAddict files and the average speed carried a value no frame had sent. A point now records the speed only while
 * 0x130 is current (stamp at most `BoostRule.LINK_FRESH_MS` old, `DriveState.freshSpeedKph`); otherwise it records no
 * speed (`NO_SPEED`), every export writes an empty field, and the drive's average and top speed leave it
 * out. The real DriveRecorder on an in-memory Room database, fixes through its own loop, the recorder's clock set by the
 * test (RecorderHarness).
 */
@RunWith(RobolectricTestRunner::class)
class RecorderStaleSpeedTest {

    private val h = RecorderHarness()
    @After fun tearDown() = h.close()

    private fun fix(i: Int) = Location("t").apply {
        latitude = 51.5 + i * 1e-4; longitude = -0.1; bearing = 0f; speed = 20f; time = System.currentTimeMillis()
    }

    /**
     * Records one fix per second for [seconds] s. At each second [stampAt] gives the time of the latest 0x130 frame (as
     * CanDecoder stamps it) and [speed] the decoded speed VehicleState holds. Returns the stopped drive and its points.
     */
    private fun record(seconds: Int, speed: (Int) -> Double, stampAt: (Int, Long) -> Long?): Pair<DriveEntity, List<DrivePointEntity>> {
        val base = h.now
        fun state(i: Int): VehicleState {
            val t = base + i * 1_000L
            return VehicleState(isConnected = true, rpm = 2_000.0, speedKph = speed(i),
                wheelSpeedFL = 10.0 + i, lastUpdate = t,
                fieldLastUpdateMs = stampAt(i, t)?.let { mapOf(CanDecoder.SPEED_TAG to it) } ?: emptyMap())
        }
        h.vs.value = state(0)
        val rec = with(h) { recorder().recording() }
        check(h.waitUntil { h.gps.opened.get() > 0 }) { "no location updates" }
        for (i in 0 until seconds) {
            h.now = base + i * 1_000L
            h.vs.value = state(i)
            val before = rec.driveState.value.totalPointCount
            h.gps.current!!.trySend(fix(i))
            check(h.waitUntil { rec.driveState.value.totalPointCount == before + 1 }) { "point $i not recorded" }
        }
        val id = rec.driveState.value.driveId
        h.join(rec.stopDrive())
        return h.dao.getDrive(id)!! to h.dao.getPoints(id)
    }

    @Test fun `a speed frame then 10 s with no new one - the later points record no speed, in every export`() {
        // One 0x130 frame at second 0 (85.46 km/h), then none: VehicleState keeps 85.46 for the rest of the drive
        val (drive, points) = record(11, speed = { 85.46 }) { _, _ -> START }
        assertEquals(11, points.size)
        // Fresh while the stamp is at most 2 s old (seconds 0, 1, 2), absent after
        assertEquals("speed while 0x130 is current", listOf(85.46, 85.46, 85.46), points.take(3).map { it.speedKph })
        points.drop(3).forEachIndexed { i, p ->
            assertEquals("point ${i + 3}: no current speed", NO_SPEED, p.speedKph, 0.0)
        }

        val csv = DriveExportBuilder.buildCsv(points).lines().drop(2).filter { it.isNotBlank() }
        assertEquals("CSV speed_kph", List(3) { "85.46" } + List(8) { "" }, csv.map { it.split(',')[3] })
        val gpx = DriveExportBuilder.buildGpx(drive, points, "20260101_000000")
        assertEquals("GPX speed elements", 3, Regex("<openrs:speed>").findAll(gpx).count())
        val rc = DriveExportBuilder.buildRaceChronoCsv(points).lines().drop(1).filter { it.isNotBlank() }
        assertEquals("RaceChrono speed (kph)", List(3) { "85.46" } + List(8) { "" }, rc.map { it.split(',')[4] })
        assertEquals("RaceChrono speed (m/s)", "", rc.last().split(',')[3])
        val ta = DriveExportBuilder.buildTrackAddictCsv(points).lines().drop(1).filter { it.isNotBlank() }
        assertEquals("TrackAddict speed (MPH)", "", ta.last().split(',')[3])

        assertEquals("average speed from current speeds only", 85.46, drive.avgSpeedKph, 1e-9)
        assertEquals("top speed", 85.46, drive.maxSpeedKph, 1e-9)
        assertTrue("distance still counts (absent is not parked): ${drive.distanceKm}", drive.distanceKm > 0.1)
    }

    @Test fun `control - a fresh speed frame at every fix stores every speed as before`() {
        val (drive, points) = record(11, speed = { i -> 40.0 + i }) { _, t -> t - 20 }
        assertEquals((0 until 11).map { 40.0 + it }, points.map { it.speedKph })
        val csv = DriveExportBuilder.buildCsv(points).lines().drop(2).filter { it.isNotBlank() }
        assertEquals((0 until 11).map { "%.2f".format(java.util.Locale.ROOT, 40.0 + it) }, csv.map { it.split(',')[3] })
        assertEquals(45.0, drive.avgSpeedKph, 1e-9)
        assertEquals(50.0, drive.maxSpeedKph, 1e-9)
    }

    @Test fun `no 0x130 on this connection - no point records a speed`() {
        val (drive, points) = record(5, speed = { 0.0 }) { _, _ -> null }
        assertTrue(points.all { it.speedKph == NO_SPEED })
        assertEquals(0.0, drive.avgSpeedKph, 0.0)
        assertEquals(0.0, drive.maxSpeedKph, 0.0)
        assertTrue("GPS distance kept: ${drive.distanceKm}", drive.distanceKm > 0.04)
    }

    @Test fun `closed at the next launch - points with no speed are left out of the average and are not parked`() {
        val id = h.dao.insertDrive(DriveEntity(startTime = 5_000L))
        val speeds = listOf(60.0, 62.0, NO_SPEED, NO_SPEED, 64.0)
        h.dao.insertPoints(speeds.mapIndexed { i, v ->
            DrivePointEntity(driveId = id, timestamp = 6_000L + i * 1_000L, lat = 51.5 + i * 1e-4, lng = -0.1, speedKph = v)
        })
        h.recorder()
        check(h.waitUntil { (h.dao.getDrive(id)?.endTime ?: 0L) != 0L }) { "the orphan was never closed" }
        val d = h.dao.getDrive(id)!!
        assertEquals(62.0, d.avgSpeedKph, 1e-9)
        assertEquals(64.0, d.maxSpeedKph, 1e-9)
        assertTrue("every step counts: ${d.distanceKm}", d.distanceKm in 0.042..0.047)
    }

    private companion object {
        const val START = 1_000_000L
        /** `DrivePointEntity.NO_SPEED`, spelled out so this test also compiles and fails on the code before #515. */
        const val NO_SPEED = -1.0
    }
}
