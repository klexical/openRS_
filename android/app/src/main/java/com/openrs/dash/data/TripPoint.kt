package com.openrs.dash.data

/**
 * Immutable telemetry snapshot captured once per GPS fix (~1 Hz) during an active trip.
 * All fields are drawn directly from [VehicleState] at the moment the GPS location arrives.
 */
data class TripPoint(
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    // ── Drivetrain ────────────────────────────────────────────
    val speedKph: Double,
    val rpm: Double,
    val gear: String,
    val boostPsi: Double,
    // ── Temperatures ─────────────────────────────────────────
    val coolantTempC: Double,
    val oilTempC: Double,
    val ambientTempC: Double,
    val rduTempC: Double,
    val ptuTempC: Double,
    // ── Fuel ─────────────────────────────────────────────────
    val fuelLevelPct: Double,
    // ── Wheel speeds (km/h) ───────────────────────────────────
    val wheelSpeedFL: Double,
    val wheelSpeedFR: Double,
    val wheelSpeedRL: Double,
    val wheelSpeedRR: Double,
    // ── Dynamics ─────────────────────────────────────────────
    val lateralG: Double,
    // ── Context ──────────────────────────────────────────────
    val driveMode: DriveMode,
    val isRaceReady: Boolean
)
