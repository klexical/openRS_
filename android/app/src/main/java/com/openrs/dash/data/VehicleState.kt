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
    val coolantTempC: Double = -99.0,  // -99 = not yet received (CAN 0x2F0 / 0x0F8)
    val oilTempC: Double = -99.0,      // -99 = not yet received (CAN 0x0F8)
    val intakeTempC: Double = 0.0,
    val boostKpa: Double = 101.325,
    val throttlePct: Double = 0.0,
    val throttleHasSource: Boolean = false,
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
    val chargeAirTempC: Double = -99.0,     // Charge air cooler outlet temp; -99 = not yet polled
    val manifoldChargeTempC: Double = -99.0, // Manifold charge temperature; -99 = not yet polled
    val octaneAdjustRatio: Double = 0.0,   // Octane adjust ratio (knock learning)
    val catalyticTempC: Double = -99.0,    // Catalytic converter temperature; -99 = not yet polled

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
    val hpFuelRailPsi: Double = -1.0,      // 0xF422: HP fuel rail pressure (PSI, direct injection)

    // ── Ignition Correction (Mode 22 via PCM 0x7E0) ───────
    val ignCorrCyl1: Double = 0.0,         // 0x03EC: Knock correction cyl 1 (deg)

    // ── TPMS (Mode 22 via BCM 0x726) ──────────────────────
    val tirePressLF: Double = -1.0,        // 0x2813: LF pressure (PSI)
    val tirePressRF: Double = -1.0,        // 0x2814: RF pressure (PSI)
    val tirePressLR: Double = -1.0,        // 0x2816: LR pressure (PSI)
    val tirePressRR: Double = -1.0,        // 0x2815: RR pressure (PSI)
    val tireTempLF: Double = -99.0,        // TPMS sensor temp (°C); -99 = not yet received
    val tireTempRF: Double = -99.0,        // TPMS sensor temp (°C); -99 = not yet received
    val tireTempLR: Double = -99.0,        // TPMS sensor temp (°C); -99 = not yet received
    val tireTempRR: Double = -99.0,        // TPMS sensor temp (°C); -99 = not yet received
    val tpmsSensorIdLF: Long = -1L,        // 0x280F: 4-byte TPMS sensor ID for LF
    val tpmsSensorIdRF: Long = -1L,        // 0x2810: 4-byte TPMS sensor ID for RF
    val tpmsSensorIdRR: Long = -1L,        // 0x2811: 4-byte TPMS sensor ID for RR
    val tpmsSensorIdLR: Long = -1L,        // 0x2812: 4-byte TPMS sensor ID for LR

    // ── Dynamics (CAN Sniffed) ──────────────────────────────
    val speedKph: Double = 0.0,
    val steeringAngle: Double = 0.0,
    val brakePressure: Double = 0.0,
    val yawRate: Double = 0.0,
    val lateralG: Double = 0.0,
    val longitudinalG: Double = 0.0,
    val verticalG: Double = 0.0,

    // ── Wheel Speeds (CAN Sniffed) ──────────────────────────
    val wheelSpeedFL: Double = 0.0,
    val wheelSpeedFR: Double = 0.0,
    val wheelSpeedRL: Double = 0.0,
    val wheelSpeedRR: Double = 0.0,

    // ── AWD / GKN Twinster (CAN Sniffed / Mode 22 polled) ───────────────────
    val awdLeftTorque: Double = 0.0,
    val awdRightTorque: Double = 0.0,
    val rduTempC: Double = -99.0,   // AWD module Mode 22 PID 0x1E8A; −99 = not yet polled
    val awdMaxTorque: Double = 0.0,
    val ptuTempC: Double = -99.0,   // 0x0F8 byte7 − 60 °C; −99 = not yet received (M-8)

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
    val launchControlActive: Boolean = false, // 0x420 bit 50: launch control armed
    val engineStatus: Int = -1,            // 0x360 byte 0: 0=Idle, 2=Off, 183=Running, 186=Kill, 191=RecentStart
    val ignitionStatus: Int = -1,          // 0x0C8 byte2 bits 3-6: 0=KeyOut..7=Running..9=Cranking

    // ── BCM OBD (Mode 22 via BCM 0x726) ────────────────────
    val odometerKm: Long = -1L,            // 0x360 bytes[3:5] 24-bit, or 0x22DD01 24-bit (once on connect)
    val odometerRolloverOffset: Long = 0,  // legacy — kept for CanDataService merge compat
    val batterySoc: Double = -1.0,         // 0x224028: B4 % (start/stop SoC)
    val batteryTempC: Double = -99.0,      // 0x224029: B4-40 °C (12V battery)
    val cabinTempC: Double = -99.0,        // 0x22DD04: (B4×10/9)-45 °C (interior)

    // ── Extended Session OBD (Mode 22 + extended session 0x03) ─
    // Sources: Daft Racing rset.py (confirmed DIDs), RSProt (probed)
    val rduEnabled: Boolean? = null,       // AWD 0x703 DID 0xEE0B: rear drive unit active
    val pdcEnabled: Boolean? = null,       // PSCM 0x730 DID 0xFD07: pull drift compensation
    val fengEnabled: Boolean? = null,      // 0x727  DID 0xEE03: fake engine noise generator
    val fengTimedOut: Boolean = false,     // true after 3 probe cycles with no FENG response
    val lcArmed: Boolean? = null,          // RSProt 0x731 probe: launch control armed
    val lcRpmTarget: Int = -1,             // RSProt 0x731 probe: LC RPM setpoint (-1 = unknown)
    val assEnabled: Boolean? = null,       // RSProt 0x731 probe: auto start-stop status
    val rsprotTimedOut: Boolean = false,   // true after 3 probe cycles with no RSProt response

    // ── Peaks ───────────────────────────────────────────────
    val peakBoostPsi: Double = 0.0,
    val peakRpm: Double = 0.0,
    val peakSpeedKph: Double = 0.0,
    val peakLateralG: Double = 0.0,
    val peakLongitudinalG: Double = 0.0,

    // ── Connection ──────────────────────────────────────────
    val isConnected: Boolean = false,
    /** True when all retry attempts exhausted — waiting for manual reconnect. */
    val isIdle: Boolean = false,
    val framesPerSecond: Double = 0.0,
    val lastUpdate: Long = 0L,
    @Deprecated("Unused — always CAN. Remove in next major version.")
    val dataMode: String = "CAN"
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

    /**
     * Gear estimated from RPM ÷ vehicle speed.
     * The Focus RS does not broadcast gear position on passive HS-CAN (0x230 is absent
     * from every observed log). This calculation uses empirically calibrated thresholds
     * derived from a live 16-minute log, with known gear ratios confirmed via the
     * RPM/speed/ratio triangle at multiple speed points.
     *
     * Formula:  ratio = rpm × GEAR_FACTOR / speedKph
     *   where GEAR_FACTOR = tireCircumferenceM(235/35R19=2.033) × 3.6 / (60 × finalDrive(3.82))
     *                      = 0.03194
     *
     * Measured ratios from live log: 1st≈3.79  2nd≈2.18  3rd≈1.89  4th≈1.30  5th≈0.85
     * Thresholds sit at midpoints between adjacent measured values.
     * Returns 0 (N) when speed < 3 kph or RPM < 400. Returns 7 for reverse.
     */
    val derivedGear: Int get() {
        if (reverseStatus) return 7
        if (speedKph < 3.0 || rpm < 400) return 0
        val ratio = rpm * 0.03194 / speedKph
        return when {
            ratio >= 2.99 -> 1
            ratio >= 2.03 -> 2
            ratio >= 1.60 -> 3
            ratio >= 1.00 -> 4
            ratio >= 0.74 -> 5
            else          -> 6
        }
    }

    /** Prefer CAN-decoded gear (0x230) when available; fall back to RPM/speed estimate. */
    val gearDisplay: String get() {
        val g = if (gear > 0) gear else derivedGear
        return when (g) {
            0 -> "N"; 1 -> "1"; 2 -> "2"; 3 -> "3"
            4 -> "4"; 5 -> "5"; 6 -> "6"; 7 -> "R"; else -> "?"
        }
    }

    /**
     * Returns null when warming up (with a description of what's still cold),
     * or an empty string when all thresholds are met (race ready).
     * Thresholds are conservative safe-margin values:
     *   Engine Oil  ≥ 80 °C  (viscosity stable for hard use)
     *   Coolant     ≥ 85 °C  (thermostat fully open, stable operating temp)
     *   RDU         ≥ 30 °C  (AWD module warm, fluid circulating)
     *   PTU         ≥ 40 °C  (transfer case warm)
     */
    val rtrStatus: String? get() {
        val cold = mutableListOf<String>()
        if (oilTempC > -90 && oilTempC < 80)           cold += "Oil ${oilTempC.toInt()}°C < 80°C"
        if (coolantTempC > -90 && coolantTempC < 85)   cold += "Coolant ${coolantTempC.toInt()}°C < 85°C"
        if (rduTempC > -90 && rduTempC < 30)        cold += "RDU ${rduTempC.toInt()}°C < 30°C"
        // M-8 fix: guard sentinel −99 so PTU doesn't show as cold before first 0x0F8 frame
        if (ptuTempC > -90 && ptuTempC < 40)        cold += "PTU ${ptuTempC.toInt()}°C < 40°C"
        return if (cold.isEmpty()) null else cold.joinToString(" · ")
    }

    val isReadyToRace: Boolean get() = rtrStatus == null

    /** Fuel rail pressure in PSI */
    val fuelRailPsi: Double get() = fuelRailPressure * 0.14503773

    /** TIP in PSI (relative to atmosphere) */
    val tipActualPsi: Double get() = (tipActualKpa - 101.325) * 0.14503773
    val tipDesiredPsi: Double get() = (tipDesiredKpa - 101.325) * 0.14503773

    /** TPMS valid checks */
    val hasTpmsData: Boolean get() = tirePressLF >= 0
    val hasTireTempData: Boolean get() =
        tireTempLF > -90 || tireTempRF > -90 || tireTempLR > -90 || tireTempRR > -90
    /**
     * M-3 fix: warn only on tires that have valid data (≥ 0).
     * Tires still carrying their −1.0 sentinel (no sensor, dead battery, or not yet polled)
     * are excluded so we don't fire false warnings before all four have been read.
     * This also handles edge cases like missing/aftermarket sensors on one or more corners.
     */
    fun anyTireLow(thresholdPsi: Double = 30.0): Boolean =
        listOf(tirePressLF, tirePressRF, tirePressLR, tirePressRR)
            .any { it in 0.01..<thresholdPsi }
    val maxTirePressSpread: Double get() {
        val valid = listOf(tirePressLF, tirePressRF, tirePressLR, tirePressRR).filter { it >= 0 }
        if (valid.size < 2) return 0.0
        return valid.max() - valid.min()
    }

    fun withPeaksUpdated(): VehicleState {
        val psi = boostPsi
        return copy(
            peakBoostPsi = max(peakBoostPsi, psi),
            peakRpm = max(peakRpm, rpm),
            peakSpeedKph = max(peakSpeedKph, speedKph),
            peakLateralG = max(peakLateralG, abs(lateralG)),
            peakLongitudinalG = max(peakLongitudinalG, abs(longitudinalG))
        )
    }

    fun withPeaksReset(): VehicleState = copy(
        peakBoostPsi = 0.0, peakRpm = 0.0, peakSpeedKph = 0.0,
        peakLateralG = 0.0, peakLongitudinalG = 0.0
    )
}

enum class DriveMode(val label: String) {
    NORMAL("Normal"), SPORT("Sport"), TRACK("Track"),
    DRIFT("Drift"), UNKNOWN("--"), CUSTOM("Custom");

    /** Maps to firmware REST API `driveMode` field (0=N, 1=S, 2=D, 3=T). */
    fun toFirmwareInt(): Int = when (this) {
        NORMAL -> 0; SPORT -> 1; DRIFT -> 2; TRACK -> 3; else -> 0
    }

    companion object {
        // DBC VAL_ 432 DriveMode: 0=Normal, 1=Sport, 2=Drift.
        // Track is not distinct on 0x1B0 (AWD treats Sport+Track identically).
        // Track is resolved in CanDecoder via 0x420 b6=0x11.
        fun fromInt(v: Int): DriveMode = when (v) {
            0 -> NORMAL; 1 -> SPORT; 2 -> DRIFT; else -> UNKNOWN
        }
    }
}

enum class EscStatus(val label: String) {
    ON("ESC On"), PARTIAL("ESC Sport"), OFF("ESC Off"), LAUNCH("Launch"), UNKNOWN("--");

    /** Maps to firmware REST API `escMode` field (0=On, 1=Sport, 2=Off). */
    fun toFirmwareInt(): Int = when (this) {
        ON -> 0; PARTIAL -> 1; OFF -> 2; else -> 0
    }

    companion object {
        // CAN 0x1C0 ESCMode: 0=Normal, 1=ESC Off, 2=Sport, 3=Launch (RS_HS.dbc VAL_ 448)
        fun fromInt(v: Int): EscStatus = when (v) {
            0 -> ON; 1 -> OFF; 2 -> PARTIAL; 3 -> LAUNCH; else -> UNKNOWN
        }
    }
}
