package com.openrs.dash

import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.PeakEvent
import com.openrs.dash.data.PeakType
import org.junit.Assert.*
import org.junit.Test

class DriveStateTest {

    // ── Helper ───────────────────────────────────────────────────────────────

    private fun point(
        driveId: Long = 1L,
        timestamp: Long = 1000L,
        lat: Double = 0.0,
        lng: Double = 0.0,
        fuelLevelPct: Double = -1.0,
        speedKph: Double = 0.0,
        rpm: Int = 0,
        boostPsi: Double = 0.0,
        lateralG: Double = 0.0,
        driveMode: String = "Normal"
    ) = DrivePointEntity(
        driveId = driveId, timestamp = timestamp, lat = lat, lng = lng,
        fuelLevelPct = fuelLevelPct, speedKph = speedKph, rpm = rpm,
        boostPsi = boostPsi, lateralG = lateralG, driveMode = driveMode
    )

    // ═════════════════════════════════════════════════════════════════════════
    // latestFuelPct
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `latestFuelPct returns startFuelPct when no points`() {
        val s = DriveState(startFuelPct = 75.0, recentPoints = emptyList())
        assertEquals(75.0, s.latestFuelPct, 0.01)
    }

    @Test fun `latestFuelPct returns last point fuel level`() {
        val s = DriveState(
            startFuelPct = 75.0,
            recentPoints = listOf(
                point(fuelLevelPct = 70.0),
                point(fuelLevelPct = 65.0)
            )
        )
        assertEquals(65.0, s.latestFuelPct, 0.01)
    }

    @Test fun `latestFuelPct falls back when last point has sentinel`() {
        // If points exist but fuelLevelPct is still sentinel (-1.0), it returns -1.0
        val s = DriveState(startFuelPct = 75.0, recentPoints = listOf(point()))
        assertEquals(-1.0, s.latestFuelPct, 0.01)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // fuelUsedL
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `fuelUsedL calculates correctly`() {
        val s = DriveState(
            startFuelPct = 50.0,
            recentPoints = listOf(point(fuelLevelPct = 40.0))
        )
        // (50-40)/100 * 49.8 = 4.98 L
        assertEquals(4.98, s.fuelUsedL, 0.01)
    }

    @Test fun `fuelUsedL is zero when no fuel used`() {
        val s = DriveState(startFuelPct = 50.0, recentPoints = listOf(point(fuelLevelPct = 50.0)))
        assertEquals(0.0, s.fuelUsedL, 0.01)
    }

    @Test fun `fuelUsedL is clamped to zero when fuel increases`() {
        // Refueling during drive — should not go negative
        val s = DriveState(
            startFuelPct = 40.0,
            recentPoints = listOf(point(fuelLevelPct = 60.0))
        )
        assertEquals(0.0, s.fuelUsedL, 0.01)
    }

    @Test fun `fuelUsedL full tank to empty`() {
        val s = DriveState(
            startFuelPct = 100.0,
            recentPoints = listOf(point(fuelLevelPct = 0.0))
        )
        assertEquals(49.8, s.fuelUsedL, 0.01)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // avgFuelL100km
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `avgFuelL100km is zero with insufficient distance`() {
        val s = DriveState(
            startFuelPct = 50.0,
            recentPoints = listOf(point(fuelLevelPct = 40.0)),
            cumulativeDistanceKm = 0.05
        )
        assertEquals(0.0, s.avgFuelL100km, 0.01)
    }

    @Test fun `avgFuelL100km calculated correctly`() {
        val s = DriveState(
            startFuelPct = 50.0,
            recentPoints = listOf(point(fuelLevelPct = 40.0)),
            cumulativeDistanceKm = 10.0
        )
        // fuelUsed = 4.98 L, distance = 10 km → 4.98/10*100 = 49.8 L/100km
        assertEquals(49.8, s.avgFuelL100km, 0.01)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // avgFuelMpg
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `avgFuelMpg is zero with no fuel usage`() {
        val s = DriveState(cumulativeDistanceKm = 10.0)
        assertEquals(0.0, s.avgFuelMpg, 0.01)
    }

    @Test fun `avgFuelMpg calculated correctly`() {
        val s = DriveState(
            startFuelPct = 50.0,
            recentPoints = listOf(point(fuelLevelPct = 40.0)),
            cumulativeDistanceKm = 10.0
        )
        // L/100km = 49.8, MPG = 282.48 / 49.8 ≈ 5.67
        assertEquals(5.67, s.avgFuelMpg, 0.01)
    }

    @Test fun `avgFuelMpg with realistic highway driving`() {
        // ~8 L/100km is realistic highway for Focus RS
        val s = DriveState(
            startFuelPct = 50.0,
            recentPoints = listOf(point(fuelLevelPct = 46.8)),  // 3.2% used
            cumulativeDistanceKm = 20.0
        )
        // fuelUsed = 3.2/100*49.8 = 1.5936 L, L/100km = 1.5936/20*100 = 7.968
        // MPG = 282.48 / 7.968 ≈ 35.5
        assertEquals(35.5, s.avgFuelMpg, 0.2)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // avgRpm / avgSpeedKph
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `avgRpm is zero with no samples`() {
        assertEquals(0.0, DriveState().avgRpm, 0.01)
    }

    @Test fun `avgRpm calculated correctly`() {
        val s = DriveState(rpmSum = 15000.0, rpmSamples = 5)
        assertEquals(3000.0, s.avgRpm, 0.01)
    }

    @Test fun `avgSpeedKph is zero with no samples`() {
        assertEquals(0.0, DriveState().avgSpeedKph, 0.01)
    }

    @Test fun `avgSpeedKph calculated correctly`() {
        val s = DriveState(speedSum = 400.0, speedSamples = 8)
        assertEquals(50.0, s.avgSpeedKph, 0.01)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // elapsedMs
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `elapsedMs is zero when not started`() {
        assertEquals(0L, DriveState().elapsedMs)
    }

    @Test fun `elapsedMs is positive after start`() {
        val s = DriveState(startTime = System.currentTimeMillis() - 5000L)
        assertTrue(s.elapsedMs >= 4900L)  // allow small timing tolerance
        assertTrue(s.elapsedMs <= 6000L)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // driveModeBreakdown
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `driveModeBreakdown is empty with no samples`() {
        assertTrue(DriveState().driveModeBreakdown.isEmpty())
    }

    @Test fun `driveModeBreakdown single mode is 100 percent`() {
        val s = DriveState(modeCounts = mapOf(DriveMode.NORMAL to 100))
        val bd = s.driveModeBreakdown
        assertEquals(1, bd.size)
        assertEquals(1.0f, bd[DriveMode.NORMAL]!!, 0.001f)
    }

    @Test fun `driveModeBreakdown mixed modes`() {
        val s = DriveState(modeCounts = mapOf(
            DriveMode.NORMAL to 60,
            DriveMode.SPORT to 30,
            DriveMode.TRACK to 10
        ))
        val bd = s.driveModeBreakdown
        assertEquals(3, bd.size)
        assertEquals(0.6f, bd[DriveMode.NORMAL]!!, 0.001f)
        assertEquals(0.3f, bd[DriveMode.SPORT]!!, 0.001f)
        assertEquals(0.1f, bd[DriveMode.TRACK]!!, 0.001f)
    }

    @Test fun `driveModeBreakdown fractions sum to 1`() {
        val s = DriveState(modeCounts = mapOf(
            DriveMode.NORMAL to 33,
            DriveMode.SPORT to 33,
            DriveMode.TRACK to 34
        ))
        val total = s.driveModeBreakdown.values.sum()
        assertEquals(1.0f, total, 0.001f)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // haversineKm
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `haversine same point is zero`() {
        assertEquals(0.0, DriveState.haversineKm(51.5, -0.1, 51.5, -0.1), 0.001)
    }

    @Test fun `haversine London to Paris`() {
        // London (51.5074, -0.1278) → Paris (48.8566, 2.3522) ≈ 341 km
        val d = DriveState.haversineKm(51.5074, -0.1278, 48.8566, 2.3522)
        assertEquals(341.0, d, 3.0)  // within 3 km tolerance
    }

    @Test fun `haversine New York to Los Angeles`() {
        // NYC (40.7128, -74.006) → LAX (33.9425, -118.408) ≈ 3944 km
        val d = DriveState.haversineKm(40.7128, -74.006, 33.9425, -118.408)
        assertEquals(3944.0, d, 15.0)
    }

    @Test fun `haversine equator short segment`() {
        // 1 degree of longitude at equator ≈ 111.32 km
        val d = DriveState.haversineKm(0.0, 0.0, 0.0, 1.0)
        assertEquals(111.32, d, 0.5)
    }

    @Test fun `haversine pole to pole`() {
        // North pole to south pole ≈ 20015 km (half circumference)
        val d = DriveState.haversineKm(90.0, 0.0, -90.0, 0.0)
        assertEquals(20015.0, d, 20.0)
    }

    @Test fun `haversine symmetry`() {
        val d1 = DriveState.haversineKm(51.5, -0.1, 48.8, 2.3)
        val d2 = DriveState.haversineKm(48.8, 2.3, 51.5, -0.1)
        assertEquals(d1, d2, 0.001)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // PeakType / PeakEvent
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `PeakType labels are correct`() {
        assertEquals("Peak RPM", PeakType.RPM.label)
        assertEquals("Peak Boost", PeakType.BOOST.label)
        assertEquals("Peak Lat-G", PeakType.LATERAL_G.label)
    }

    @Test fun `PeakEvent construction and equality`() {
        val e1 = PeakEvent(PeakType.RPM, 6500.0, 51.5, -0.1, 1000L)
        val e2 = PeakEvent(PeakType.RPM, 6500.0, 51.5, -0.1, 1000L)
        assertEquals(e1, e2)
    }

    @Test fun `PeakEvent different types are not equal`() {
        val e1 = PeakEvent(PeakType.RPM, 6500.0, 51.5, -0.1, 1000L)
        val e2 = PeakEvent(PeakType.BOOST, 6500.0, 51.5, -0.1, 1000L)
        assertNotEquals(e1, e2)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DrivePointEntity sentinel defaults
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `DrivePointEntity temperature sentinels are minus 99`() {
        val p = point()
        assertEquals(-99.0, p.coolantTempC, 0.01)
        assertEquals(-99.0, p.oilTempC, 0.01)
        assertEquals(-99.0, p.ambientTempC, 0.01)
        assertEquals(-99.0, p.rduTempC, 0.01)
        assertEquals(-99.0, p.ptuTempC, 0.01)
        assertEquals(-99.0, p.tireTempLF, 0.01)
        assertEquals(-99.0, p.tireTempRF, 0.01)
        assertEquals(-99.0, p.tireTempLR, 0.01)
        assertEquals(-99.0, p.tireTempRR, 0.01)
    }

    @Test fun `DrivePointEntity fuel and tire pressure sentinels are minus 1`() {
        val p = point()
        assertEquals(-1.0, p.fuelLevelPct, 0.01)
        assertEquals(-1.0, p.tirePressLF, 0.01)
        assertEquals(-1.0, p.tirePressRF, 0.01)
        assertEquals(-1.0, p.tirePressLR, 0.01)
        assertEquals(-1.0, p.tirePressRR, 0.01)
    }

    @Test fun `DrivePointEntity dynamic defaults are zero`() {
        val p = point()
        assertEquals(0.0, p.speedKph, 0.01)
        assertEquals(0, p.rpm)
        assertEquals(0.0, p.boostPsi, 0.01)
        assertEquals(0.0, p.lateralG, 0.01)
        assertEquals(0.0, p.throttlePct, 0.01)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DriveEntity sentinel defaults
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `DriveEntity defaults are correct`() {
        val d = com.openrs.dash.data.DriveEntity(startTime = 1000L)
        assertEquals(0L, d.endTime)         // 0 = still active
        assertTrue(d.hasGps)                 // default true for new drives
        assertEquals(0.0, d.distanceKm, 0.01)
        assertEquals(-99.0, d.peakOilTempC, 0.01)
        assertEquals(-99.0, d.peakCoolantTempC, 0.01)
        assertEquals("{}", d.driveModeBreakdown)
        assertNull(d.weatherSummary)
    }

    // ═════════════════════════════════════════════════════════════════════════
    // DriveState copy immutability
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `DriveState copy does not mutate original`() {
        val original = DriveState(isRecording = true, peakRpm = 5000.0)
        val modified = original.copy(peakRpm = 6500.0)
        assertEquals(5000.0, original.peakRpm, 0.01)
        assertEquals(6500.0, modified.peakRpm, 0.01)
    }

    @Test fun `DriveState default is idle`() {
        val s = DriveState()
        assertFalse(s.isRecording)
        assertFalse(s.isPaused)
        assertEquals(0L, s.driveId)
        assertTrue(s.recentPoints.isEmpty())
        assertTrue(s.peakEvents.isEmpty())
    }

    // ═════════════════════════════════════════════════════════════════════════
    // Edge cases
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `tank capacity constant is 49 point 8 litres`() {
        assertEquals(49.8, DriveState.TANK_L, 0.01)
    }

    @Test fun `fuel economy with very short drive`() {
        // 0.5 km driven, 0.2% fuel used
        val s = DriveState(
            startFuelPct = 50.0,
            recentPoints = listOf(point(fuelLevelPct = 49.8)),
            cumulativeDistanceKm = 0.5
        )
        // fuelUsed = 0.2/100*49.8 = 0.0996 L
        // L/100km = 0.0996/0.5*100 = 19.92
        assertEquals(19.92, s.avgFuelL100km, 0.1)
    }
}
