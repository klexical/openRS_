package com.openrs.dash.data

import android.location.Location
import kotlin.math.*

/**
 * Live observable state of the active (or most recently completed) drive.
 *
 * Running totals are accumulated by [DriveRecorder] one GPS sample at a time.
 * Fuel economy uses the Focus RS MK3 tank capacity of [TANK_L] = 49.8 L.
 *
 * Unlike [TripState] (which this replaces), the full point list is persisted to
 * Room — [recentPoints] holds only the last N for live polyline rendering.
 */
data class DriveState(
    val isRecording: Boolean = false,
    val isPaused: Boolean = false,
    val driveId: Long = 0,
    val recentPoints: List<DrivePointEntity> = emptyList(),
    val totalPointCount: Int = 0,
    val startFuelPct: Double = 0.0,
    val startTime: Long = 0L,
    val currentWeather: WeatherData? = null,
    val currentLocation: Location? = null,
    // ── Running accumulators (updated incrementally) ─────────
    val cumulativeDistanceKm: Double = 0.0,
    val rpmSum: Double = 0.0,
    val rpmSamples: Long = 0L,
    val speedSum: Double = 0.0,
    val speedSamples: Long = 0L,
    val modeCounts: Map<DriveMode, Int> = emptyMap(),
    // ── Session peaks ────────────────────────────────────────
    val maxSpeedKph: Double = 0.0,
    val peakRpm: Double = 0.0,
    val peakBoostPsi: Double = 0.0,
    val peakLateralG: Double = 0.0,
    val peakEvents: List<PeakEvent> = emptyList(),
    val rtrAchievedPoint: DrivePointEntity? = null
) {
    // ── Computed properties ───────────────────────────────────────────────

    val latestFuelPct: Double
        get() = recentPoints.lastOrNull()?.fuelLevelPct ?: startFuelPct

    val fuelUsedL: Double
        get() = ((startFuelPct - latestFuelPct) / 100.0 * TANK_L).coerceAtLeast(0.0)

    val avgFuelL100km: Double
        get() = if (cumulativeDistanceKm < 0.1) 0.0
                else fuelUsedL / cumulativeDistanceKm * 100.0

    val avgFuelMpg: Double
        get() {
            val l100 = avgFuelL100km
            return if (l100 < 0.01) 0.0 else 282.48 / l100
        }

    val avgRpm: Double
        get() = if (rpmSamples > 0L) rpmSum / rpmSamples else 0.0

    val avgSpeedKph: Double
        get() = if (speedSamples > 0L) speedSum / speedSamples else 0.0

    val elapsedMs: Long
        get() = if (startTime > 0L) System.currentTimeMillis() - startTime else 0L

    val driveModeBreakdown: Map<DriveMode, Float>
        get() {
            val total = modeCounts.values.sum()
            if (total == 0) return emptyMap()
            return modeCounts.mapValues { (_, count) -> count.toFloat() / total }
        }

    companion object {
        const val TANK_L = 49.8

        fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
            val r = 6371.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLon = Math.toRadians(lon2 - lon1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
            return r * 2.0 * atan2(sqrt(a), sqrt(1.0 - a))
        }
    }
}
