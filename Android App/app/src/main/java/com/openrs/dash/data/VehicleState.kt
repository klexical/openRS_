package com.openrs.dash.data

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Complete Focus RS MK3 telemetry — all CAN sniffed + OBD Mode 1/22 parameters.
 */
data class VehicleState(
    // ── Engine (CAN Sniffed) ────────────────────────────────
    val rpm: Double = 0.0,
    val coolantTempC: Double = 0.0,
    val oilTempC: Double = 0.0,
    val intakeTempC: Double = 0.0,
    val boostKpa: Double = 101.325,
    val throttlePct: Double = 0.0,
    val accelPedalPct: Double = 0.0,
    val torqueAtTrans: Double = 0.0,

    // ── Engine (OBD Mode 1) ─────────────────────────────────
    val calcLoad: Double = 0.0,            // PID 04: 0-100%
    val shortFuelTrim: Double = 0.0,       // PID 06: -100 to +99.2%
    val longFuelTrim: Double = 0.0,        // PID 07: -100 to +99.2%
    val timingAdvance: Double = 0.0,       // PID 0E: -64 to +63.5 degrees
    val fuelRailPressure: Double = 0.0,    // PID 22: 0-5177.3 kPa
    val barometricPressure: Double = 0.0,  // PID 33: 0-255 kPa
    val commandedAfr: Double = 0.0,        // PID 44: 0-2 lambda
    val o2Voltage: Double = 0.0,           // PID 15: 0-1.275 V
    val afrSensor1: Double = 0.0,          // PID 24: air-fuel ratio

    // ── Engine (OBD Mode 22 — Ford Enhanced) ────────────────
    val chargeAirTempC: Double = 0.0,      // Charge air cooler outlet temp
    val manifoldChargeTempC: Double = 0.0, // Manifold charge temperature
    val octaneAdjustRatio: Double = 0.0,   // Octane adjust ratio (knock learning)
    val catalyticTempC: Double = 0.0,      // Catalytic converter temperature

    // ── AFR / Fueling (Mode 22 via PCM 0x7E0) ─────────────
    val afrActual: Double = 0.0,           // 0xF434: Wideband AFR actual
    val afrDesired: Double = 0.0,          // 0xF444: ECU target AFR
    val lambdaActual: Double = 0.0,        // 0xF434: Lambda equivalence ratio

    // ── Throttle / Boost Control (Mode 22 via PCM 0x7E0) ──
    val etcAngleActual: Double = 0.0,      // 0x093C: Electronic throttle actual (deg)
    val etcAngleDesired: Double = 0.0,     // 0x091A: Electronic throttle desired (deg)
    val tipActualKpa: Double = 0.0,        // 0x033E: Throttle inlet pressure actual
    val tipDesiredKpa: Double = 0.0,       // 0x0466: Throttle inlet pressure desired
    val wgdcDesired: Double = 0.0,         // 0x0462: Wastegate duty cycle desired (%)

    // ── Variable Cam Timing (Mode 22 via PCM 0x7E0) ───────
    val vctIntakeAngle: Double = 0.0,      // 0x0318: VCT-I actual angle (deg)
    val vctExhaustAngle: Double = 0.0,     // 0x0319: VCT-E actual angle (deg)

    // ── Oil (Mode 22 via PCM 0x7E0) ───────────────────────
    val oilLifePct: Double = -1.0,         // 0x054B: Oil life remaining (%)

    // ── Ignition Correction (Mode 22 via PCM 0x7E0) ───────
    val ignCorrCyl1: Double = 0.0,         // 0x03EC: Knock correction cyl 1 (deg)

    // ── TPMS (Mode 22 via BCM 0x726) ──────────────────────
    val tirePressLF: Double = -1.0,        // 0x2813: LF pressure (PSI)
    val tirePressRF: Double = -1.0,        // 0x2814: RF pressure (PSI)
    val tirePressLR: Double = -1.0,        // 0x2816: LR pressure (PSI)
    val tirePressRR: Double = -1.0,        // 0x2815: RR pressure (PSI)
    val tireTempLF: Double = -99.0,        // 0x2823: LF temp (°F) — experimental
    val tireTempRF: Double = -99.0,        // 0x2824: RF temp (°F) — experimental
    val tireTempLR: Double = -99.0,        // 0x2826: LR temp (°F) — experimental
    val tireTempRR: Double = -99.0,        // 0x2825: RR temp (°F) — experimental

    // ── Dynamics (CAN Sniffed) ──────────────────────────────
    val speedKph: Double = 0.0,
    val steeringAngle: Double = 0.0,
    val brakePressure: Double = 0.0,
    val yawRate: Double = 0.0,
    val lateralG: Double = 0.0,
    val longitudinalG: Double = 0.0,

    // ── Wheel Speeds (CAN Sniffed) ──────────────────────────
    val wheelSpeedFL: Double = 0.0,
    val wheelSpeedFR: Double = 0.0,
    val wheelSpeedRL: Double = 0.0,
    val wheelSpeedRR: Double = 0.0,

    // ── AWD / GKN Twinster (CAN Sniffed) ────────────────────
    val awdLeftTorque: Double = 0.0,
    val awdRightTorque: Double = 0.0,
    val rduTempC: Double = 0.0,
    val awdMaxTorque: Double = 0.0,
    val ptuTempC: Double = 0.0,

    // ── Vehicle Status (CAN Sniffed) ────────────────────────
    val driveMode: DriveMode = DriveMode.NORMAL,
    val escStatus: EscStatus = EscStatus.ON,
    val gear: Int = 0,
    val batteryVoltage: Double = 0.0,
    val fuelLevelPct: Double = 0.0,
    val ambientTempC: Double = 0.0,
    val gaugeIllumination: Int = 0,        // 0x0C8: gauge brightness level
    val eBrake: Boolean = false,           // Emergency brake status
    val reverseStatus: Boolean = false,    // Reverse gear engaged

    // ── Nutron-only ─────────────────────────────────────────
    val lambdaValue: Double = 0.0,
    val launchControl: Boolean = false,
    val driftFury: Boolean = false,

    // ── Peaks ───────────────────────────────────────────────
    val peakBoostPsi: Double = 0.0,
    val peakRpm: Double = 0.0,
    val peakLateralG: Double = 0.0,
    val peakLongitudinalG: Double = 0.0,

    // ── Connection ──────────────────────────────────────────
    val isConnected: Boolean = false,
    val framesPerSecond: Double = 0.0,
    val lastUpdate: Long = 0L,
    val dataMode: String = "ATMA"  // "ATMA" or "PID_QUERY" for UI indicator
) {
    // ── Computed ─────────────────────────────────────────────
    val boostPsi: Double get() = (boostKpa - 101.325) * 0.14503773
    val speedMph: Double get() = speedKph * 0.621371
    val combinedG: Double get() = sqrt(lateralG * lateralG + longitudinalG * longitudinalG)
    val totalRearTorque: Double get() = awdLeftTorque + awdRightTorque

    val rearTorquePct: Double get() {
        if (torqueAtTrans <= 0) return 0.0
        return min(100.0, totalRearTorque / torqueAtTrans * 100.0)
    }

    val frontRearSplit: String get() {
        val r = rearTorquePct.toInt()
        return "${100 - r}/$r"
    }

    val rearLeftRightBias: String get() {
        val total = totalRearTorque
        if (total <= 0) return "50/50"
        val l = ((awdLeftTorque / total) * 100).toInt()
        return "$l/${100 - l}"
    }

    val gearDisplay: String get() = when (gear) {
        0 -> "N"; 1 -> "1"; 2 -> "2"; 3 -> "3"
        4 -> "4"; 5 -> "5"; 6 -> "6"; 7 -> "R"; else -> "?"
    }

    val isReadyToRace: Boolean get() =
        oilTempC >= 65 && rduTempC >= 20 && ptuTempC >= 50

    /** Fuel rail pressure in PSI */
    val fuelRailPsi: Double get() = fuelRailPressure * 0.14503773

    /** TIP in PSI (relative to atmosphere) */
    val tipActualPsi: Double get() = (tipActualKpa - 101.325) * 0.14503773
    val tipDesiredPsi: Double get() = (tipDesiredKpa - 101.325) * 0.14503773

    /** TPMS valid checks */
    val hasTpmsData: Boolean get() = tirePressLF >= 0
    val anyTireLow: Boolean get() = hasTpmsData &&
        (tirePressLF < 30 || tirePressRF < 30 || tirePressLR < 30 || tirePressRR < 30)
    val maxTirePressSpread: Double get() {
        if (!hasTpmsData) return 0.0
        val all = listOf(tirePressLF, tirePressRF, tirePressLR, tirePressRR)
        return all.max() - all.min()
    }

    fun withPeaksUpdated(): VehicleState {
        val psi = boostPsi
        return copy(
            peakBoostPsi = max(peakBoostPsi, psi),
            peakRpm = max(peakRpm, rpm),
            peakLateralG = max(peakLateralG, abs(lateralG)),
            peakLongitudinalG = max(peakLongitudinalG, abs(longitudinalG))
        )
    }

    fun withPeaksReset(): VehicleState = copy(
        peakBoostPsi = 0.0, peakRpm = 0.0,
        peakLateralG = 0.0, peakLongitudinalG = 0.0
    )
}

enum class DriveMode(val label: String) {
    NORMAL("Normal"), SPORT("Sport"), TRACK("Track"),
    DRIFT("Drift"), UNKNOWN("--"), CUSTOM("Custom");
    companion object {
        fun fromInt(v: Int): DriveMode = when (v) {
            0 -> NORMAL; 1 -> SPORT; 2 -> TRACK; 3 -> DRIFT; 5 -> CUSTOM; else -> UNKNOWN
        }
    }
}

enum class EscStatus(val label: String) {
    ON("ESC On"), PARTIAL("ESC Sport"), OFF("ESC Off"), UNKNOWN("--");
    companion object {
        fun fromInt(v: Int): EscStatus = when (v) {
            0 -> ON; 1 -> PARTIAL; 2 -> OFF; else -> UNKNOWN
        }
    }
}
