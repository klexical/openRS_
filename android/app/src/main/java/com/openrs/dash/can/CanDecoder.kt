package com.openrs.dash.can

import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState

/**
 * Focus RS MK3 HS-CAN passive frame decoder.
 *
 * Sources:
 *  - RS_HS.dbc  — authoritative HS-CAN signal database (DBC format)
 *  - DigiCluster can0_hs.json  — HS-CAN @ 500 kbps confirmed signals
 *  - DigiCluster can1_ms.json  — MS-CAN @ 125 kbps signals
 *  - research/exportedPIDs.txt — Torque app Mode 22 PID export
 *  - research/Daft Racing/log_awd_temp.py — AWD temp decoding reference
 *
 * Notes on 0x340: the DBC identifies this as PCMmsg17 (HS-CAN) with AmbientAirTemp
 * in byte 7. DigiCluster can1_ms.json also maps TPMS PSI to bytes 2-5 of 0x340 on
 * MS-CAN. Both decodings are attempted; range filters discard invalid readings.
 * RDU temp is not in any passive broadcast — it arrives via AWD module Mode 22 polling.
 */
object CanDecoder {

    // ── HS-CAN engine / powertrain ──────────────────────────────────────────
    const val ID_TORQUE       = 0x070   // Torque at trans (Motorola bits 37-47)
    const val ID_THROTTLE     = 0x076   // Throttle % (byte 0 × 0.392) — may not broadcast on all tunes
    const val ID_PEDALS       = 0x080   // Accel pedal (bits 0-9 LE ×0.1 %), brake (bit 2), reverse (bit 5)
    const val ID_ENGINE_RPM   = 0x090   // RPM (byte4 low-nib|byte5 × 2), baro (byte2 × 0.5 kPa)
    const val ID_GAUGE_ILLUM  = 0x0C8   // Gauge brightness (bits 0-4), e-brake (byte3 bit 6)
    // RS_HS.dbc PCMmsg07 (0x0F8): EngineOilTemp byte1 −50°C, Boost byte5 ×0.01bar gauge, PTUTemp byte7 −60°C
    const val ID_ENGINE_TEMPS = 0x0F8
    const val ID_SPEED        = 0x130   // Speed bytes 6-7 BE × 0.01 kph
    const val ID_LONG_ACCEL   = 0x160   // Longitudinal G bits 48-57 LE × 0.00390625 − 2.0
    const val ID_LAT_ACCEL    = 0x180   // Lateral G bits 16-25 LE × 0.00390625 − 2.0
    const val ID_AWD_MSG      = 0x1B0   // DriveMode Motorola bit 55|4  — RS_HS.dbc confirmed
    const val ID_ESC_ABS      = 0x1C0   // ESCMode Motorola bit 13|2    — RS_HS.dbc confirmed
    // RS_HS.dbc ABSmsg03 (0x190): FL/FR/RL/RR wheel speeds — 15-bit Motorola × 0.011343006 km/h
    const val ID_WHEEL_SPEEDS = 0x190
    const val ID_GEAR         = 0x230   // Gear bits 0-3
    const val ID_AWD_TORQUE   = 0x2C0   // AWD left/right Nm (bits 0|12, 12|12)
    // RS_HS.dbc PCMmsg16 (0x2F0): CoolantTemp 10-bit Motorola −60°C, IntakeAirTemp 10-bit ×0.25−127°C
    const val ID_COOLANT      = 0x2F0

    // ── RS_HS.dbc PCMmsg17 (0x340): AmbientAirTemp byte7 signed ×0.25°C ──────
    // DigiCluster can1_ms.json also maps MS-CAN TPMS PSI to bytes 2-5 at 0x340.
    // Both signals are decoded; range filters discard invalid readings.
    const val ID_TPMS         = 0x340
    const val ID_AMBIENT_TEMP = 0x1A4   // Ambient temp byte4 signed × 0.25 °C (MS-CAN bridged)

    // ── RS_HS.dbc SASMmsg01 (0x010): Steering wheel angle ──────────────────
    // SteeringWheelAngle : 54|15@0+ (0.04395,0) → bits(54,15) × 0.04395 °
    // SteeringAngleSign  : 39|1@0+ (1,0)        → 1=CW(right) 0=CCW(left)
    const val ID_STEERING     = 0x010

    // ── RS_HS.dbc ABSmsg10 (0x252): Brake pressure ──────────────────────────
    // BrakePressureMeasured : 11|12@0+ (1,0) → bits(11,12) raw 0-4095 counts
    // Displayed as 0-100% (raw / 40.95) until bar calibration is confirmed.
    const val ID_BRAKE_PRESS  = 0x252

    // ── Speculative / may not be present on all variants ────────────────────
    const val ID_FUEL_LEVEL   = 0x34A   // Fuel % byte0 × 0.392 (unconfirmed ID, may need adjustment)
    const val ID_BATTERY      = 0x3C0   // Battery V byte0 × 0.1 (unconfirmed, may not broadcast)

    private val KNOWN_IDS = setOf(
        ID_TORQUE, ID_THROTTLE, ID_PEDALS, ID_ENGINE_RPM,
        ID_GAUGE_ILLUM, ID_ENGINE_TEMPS, ID_SPEED,
        ID_LONG_ACCEL, ID_LAT_ACCEL,
        ID_AWD_MSG, ID_ESC_ABS,
        ID_WHEEL_SPEEDS, ID_GEAR, ID_AWD_TORQUE, ID_COOLANT,
        ID_TPMS, ID_AMBIENT_TEMP,
        ID_FUEL_LEVEL, ID_BATTERY,
        ID_STEERING, ID_BRAKE_PRESS
    )

    fun decode(id: Int, data: ByteArray, state: VehicleState): VehicleState? {
        if (id !in KNOWN_IDS) return null
        val n   = data.size
        val now = System.currentTimeMillis()

        return when (id) {

            // ── 0x090: RPM + barometric pressure ──────────────────────────────
            // RPM:  (byte4 & 0x0F) << 8 | byte5  × 2   [DigiCluster bits 36-47 motorola]
            // Baro: byte2 × 0.5 kPa                      [DigiCluster bits 16-23 motorola]
            ID_ENGINE_RPM -> if (n >= 6) state.copy(
                rpm                = ((data[4].toInt() and 0x0F) shl 8 or (data[5].toInt() and 0xFF)) * 2.0,
                barometricPressure = ubyte(data, 2) * 0.5,
                lastUpdate         = now
            ) else null

            // ── 0x0F8: Engine oil temp + boost + PTU temp ─────────────────────
            // RS_HS.dbc PCMmsg07 (20 ms broadcast):
            //   EngineOilTemp : 15|8@0+ (1,−50)   → byte1, raw−50 °C
            //   Boost         : 47|8@0+ (0.01,0)   → byte5, raw×0.01 bar gauge;
            //                    stored as absolute kPa = raw + barometric pressure
            //   PTUTemp       : 63|8@0+ (1,−60)    → byte7, raw−60 °C
            // Baro fallback: use 101.325 kPa (std atmosphere) until 0x090 frame populates state.
            ID_ENGINE_TEMPS -> if (n >= 8) {
                val baro = if (state.barometricPressure > 50.0) state.barometricPressure else 101.325
                state.copy(
                    oilTempC   = (ubyte(data, 1) - 50).toDouble(),
                    boostKpa   = ubyte(data, 5).toDouble() + baro,
                    ptuTempC   = (ubyte(data, 7) - 60).toDouble(),
                    lastUpdate = now
                )
            } else null

            // ── 0x130: Vehicle speed ───────────────────────────────────────────
            // bytes 6-7 big-endian × 0.01 km/h  [DigiCluster verified]
            ID_SPEED -> if (n >= 8) state.copy(
                speedKph   = word(data, 6) * 0.01,
                lastUpdate = now
            ) else null

            // ── 0x2F0: Coolant + intake air temperature ───────────────────────
            // RS_HS.dbc PCMmsg16 (90 ms broadcast):
            //   EngineCoolantTemp : 33|10@0+ (1,−60)     → ((data[4]&0x03)<<8|data[5]) − 60 °C
            //   IntakeAirTemp     : 49|10@0+ (0.25,−127) → ((data[6]&0x03)<<8|data[7]) × 0.25 − 127 °C
            // The top 2 bits of each 10-bit Motorola signal live in the low 2 bits of
            // bytes 4 and 6 respectively; the lower 8 bits are bytes 5 and 7.
            ID_COOLANT -> if (n >= 8) state.copy(
                coolantTempC = (((data[4].toInt() and 0x03) shl 8) or ubyte(data, 5)).toDouble() - 60.0,
                intakeTempC  = (((data[6].toInt() and 0x03) shl 8) or ubyte(data, 7)).toDouble() * 0.25 - 127.0,
                lastUpdate   = now
            ) else null

            // ── 0x076: Throttle position ───────────────────────────────────────
            ID_THROTTLE -> if (n >= 1) state.copy(
                throttlePct = ubyte(data, 0) * 0.392,
                lastUpdate  = now
            ) else null

            // ── 0x080: Accelerator pedal + reverse ────────────────────────────
            // Accel pedal: bits 0-9 little-endian × 0.1 %  [DigiCluster verified]
            // Reverse:     bit 5 of byte0                   [DigiCluster verified]
            // NOTE: brake is boolean (bit 2) but brakePressure field not set here —
            //       DigiCluster has no brake pressure on HS-CAN 0x080.
            ID_PEDALS -> if (n >= 2) state.copy(
                accelPedalPct = ((data[0].toInt() and 0x03) shl 8 or (data[1].toInt() and 0xFF)) * 0.1,
                reverseStatus = (data[0].toInt() and 0x20) != 0,
                lastUpdate    = now
            ) else null

            // ── 0x160: Longitudinal acceleration ──────────────────────────────
            // bits 48-57 little-endian: (byte6 & 0x03) << 8 | byte7, × 0.00390625 − 2.0 g
            // Range: −2.0 to +1.996 g. Invalid pattern: byte6 & 0x03 == 0x03 && byte7 == 0xFF
            ID_LONG_ACCEL -> if (n >= 8) {
                val b6 = data[6].toInt() and 0xFF
                val b7 = data[7].toInt() and 0xFF
                if ((b6 and 0x03) == 0x03 && b7 == 0xFF) null
                else state.copy(
                    longitudinalG = ((b6 and 0x03) shl 8 or b7) * 0.00390625 - 2.0,
                    lastUpdate    = now
                )
            } else null

            // ── 0x180: Lateral acceleration + Yaw rate ─────────────────────────
            // RS_HS.dbc ABSmsg02 (10 ms broadcast):
            //   LatAccelMeasured  : 17|10@0+ (0.00390625,-2) → bits 17-26 Motorola
            //     = (byte2 & 0x03) << 8 | byte3, × 0.00390625 − 2.0 g
            //   YawRateMeasured   : 35|12@0+ (0.03663,-75)   → bits(data,35,12) × 0.03663 − 75 °/s
            // Invalid pattern for lat G: byte2 & 0x03 == 0x03 && byte3 == 0xFF
            ID_LAT_ACCEL -> if (n >= 8) {
                val b2 = data[2].toInt() and 0xFF
                val b3 = data[3].toInt() and 0xFF
                if ((b2 and 0x03) == 0x03 && b3 == 0xFF) null
                else state.copy(
                    lateralG   = ((b2 and 0x03) shl 8 or b3) * 0.00390625 - 2.0,
                    yawRate    = bits(data, 35, 12) * 0.03663 - 75.0,
                    lastUpdate = now
                )
            } else null

            // ── 0x010: Steering wheel angle ────────────────────────────────────
            // RS_HS.dbc SASMmsg01 (10 ms broadcast):
            //   SteeringWheelAngle : 54|15@0+ (0.04395,0) → bits(data,54,15) × 0.04395 °
            //   SteeringAngleSign  : 39|1@0+  (1,0)       → 1=CW/right 0=CCW/left
            // Range: 0 to 1440 °. Sign applied: positive = right (CW), negative = left.
            ID_STEERING -> if (n >= 8) {
                val angleMag = bits(data, 54, 15) * 0.04395
                val signBit  = bits(data, 39, 1)
                state.copy(
                    steeringAngle = if (signBit == 1) angleMag else -angleMag,
                    lastUpdate    = now
                )
            } else null

            // ── 0x252: Brake pressure ──────────────────────────────────────────
            // RS_HS.dbc ABSmsg10 (20 ms broadcast):
            //   BrakePressureMeasured : 11|12@0+ (1,0) → bits(data,11,12) raw 0-4095
            // Units unconfirmed — displayed as 0–100 scale (raw / 40.95) until bar
            // calibration is verified from a live log with known brake pressure.
            ID_BRAKE_PRESS -> if (n >= 4) state.copy(
                brakePressure = bits(data, 11, 12) / 40.95,
                lastUpdate    = now
            ) else null

            // ── 0x0C8: Gauge illumination + e-brake ───────────────────────────
            // Brightness: bits 0-4 of byte0  [DigiCluster verified]
            // E-brake:    bit 6 of byte3      [DigiCluster verified]
            ID_GAUGE_ILLUM -> if (n >= 4) state.copy(
                gaugeIllumination = data[0].toInt() and 0x1F,
                eBrake            = (data[3].toInt() and 0x40) != 0,
                lastUpdate        = now
            ) else null

            // ── 0x340: PCMmsg17 (HS-CAN) + MS-CAN TPMS (if bridged via GWM) ───
            // RS_HS.dbc PCMmsg17: AmbientAirTemp : 63|8@0− → byte7 signed × 0.25 °C
            // DigiCluster can1_ms.json: TPMS PSI bytes 2-5 (LF/RF/LR/RR).
            // Encoding: 1 raw unit = 3.6 kPa → PSI = raw × 3.6 / 6.895
            // Confirmed: 0x43 (67 raw) = 35.0 PSI (winter-adjusted from ~40 PSI).
            // Sensors sleep when stationary — raw 0 → null → retain last known pressure.
            // Valid result range: 5–80 PSI. Out-of-range → keep last known good value.
            ID_TPMS -> if (n >= 8) {
                fun tpmsPsi(raw: Int): Double? {
                    if (raw == 0) return null
                    val psi = raw * 3.6 / 6.895
                    return if (psi < 5.0 || psi > 80.0) null else psi
                }
                val lf = tpmsPsi(ubyte(data, 2))
                val rf = tpmsPsi(ubyte(data, 3))
                val lr = tpmsPsi(ubyte(data, 4))
                val rr = tpmsPsi(ubyte(data, 5))
                // Ambient from byte 7 — signed int8 × 0.25 °C (PCMmsg17)
                val ambient = data[7].toInt().toDouble() * 0.25
                val ambientValid = ambient in -50.0..60.0
                if (lf == null && rf == null && lr == null && rr == null && !ambientValid) null
                else state.copy(
                    tirePressLF  = lf ?: state.tirePressLF,
                    tirePressRF  = rf ?: state.tirePressRF,
                    tirePressLR  = lr ?: state.tirePressLR,
                    tirePressRR  = rr ?: state.tirePressRR,
                    ambientTempC = if (ambientValid) ambient else state.ambientTempC,
                    lastUpdate   = now
                )
            } else null

            // ── 0x1A4: Ambient temperature — MS-CAN bridged via GWM ──────────
            // DigiCluster can1_ms.json: byte4 signed int8 × 0.25 °C
            ID_AMBIENT_TEMP -> if (n >= 5) state.copy(
                ambientTempC = data[4].toInt().toDouble() * 0.25,
                lastUpdate   = now
            ) else null

            // ── 0x190: Wheel speeds — RS_HS.dbc ABSmsg03 (10 ms broadcast) ────
            // FrontLeftWheelSpeed  : 6|15@0+ (0.011343006,0)  → ((data[0]&0x7F)<<8)|data[1]
            // FrontRightWheelSpeed : 22|15@0+ (0.011343006,0) → ((data[2]&0x7F)<<8)|data[3]
            // RearLeftWheelSpeed   : 38|15@0+ (0.011343006,0) → ((data[4]&0x7F)<<8)|data[5]
            // RearRightWheelSpeed  : 54|15@0+ (0.011343006,0) → ((data[6]&0x7F)<<8)|data[7]
            // Each is a 15-bit Motorola MSB-first value; top 7 bits in byte N, lower 8 in N+1.
            // Scale: × 0.011343006 km/h. Stationary reads 0x0000 → 0.0 km/h.
            ID_WHEEL_SPEEDS -> if (n >= 8) state.copy(
                wheelSpeedFL = (((data[0].toInt() and 0x7F) shl 8) or ubyte(data, 1)) * 0.011343006,
                wheelSpeedFR = (((data[2].toInt() and 0x7F) shl 8) or ubyte(data, 3)) * 0.011343006,
                wheelSpeedRL = (((data[4].toInt() and 0x7F) shl 8) or ubyte(data, 5)) * 0.011343006,
                wheelSpeedRR = (((data[6].toInt() and 0x7F) shl 8) or ubyte(data, 7)) * 0.011343006,
                lastUpdate   = now
            ) else null

            // ── 0x2C0: AWD / GKN Twinster torque ─────────────────────────────────
            // Torque vectoring left/right Nm. RDU temp is NOT in this passive frame —
            // it is polled via AWD module Mode 22 PID 0x1E8A (see WiCanConnection).
            ID_AWD_TORQUE -> if (n >= 7) {
                var left  = bits(data, 0,  12).toDouble()
                var right = bits(data, 12, 12).toDouble()
                if (left  >= 0xFFE) left  = 0.0
                if (right >= 0xFFE) right = 0.0
                val maxRaw = bits(data, 43, 13)
                state.copy(
                    awdLeftTorque  = left,
                    awdRightTorque = right,
                    awdMaxTorque   = if (maxRaw > 0) maxRaw.toDouble() else 0.0,
                    lastUpdate     = now
                )
            } else null

            // ── 0x1B0: Drive mode ──────────────────────────────────────────────
            // The drive mode is encoded in the LOWER nibble of byte 6.
            // The upper nibble carries the previous/transitioning mode value,
            // which is why reading ushr 4 always shows one mode behind.
            // Verified against live CAN data: lower nibble (and 0x0F) is correct.
            // 0=Normal, 1=Sport, 2=Track, 3=Drift, 5=Custom
            ID_AWD_MSG -> if (n >= 7) state.copy(
                driveMode = DriveMode.fromInt(data[6].toInt() and 0x0F),
                lastUpdate = now
            ) else null

            // ── 0x1C0: ESC mode ────────────────────────────────────────────────
            ID_ESC_ABS -> if (n >= 2) state.copy(
                escStatus = EscStatus.fromInt(bits(data, 13, 2)), lastUpdate = now
            ) else null

            // ── 0x230: Current gear ────────────────────────────────────────────
            ID_GEAR -> if (n >= 1) state.copy(
                gear = bits(data, 0, 4), lastUpdate = now
            ) else null

            // ── 0x070: Torque at transmission ─────────────────────────────────
            ID_TORQUE -> if (n >= 6) state.copy(
                torqueAtTrans = (bits(data, 37, 11) - 500).toDouble(), lastUpdate = now
            ) else null

            // ── 0x34A: Fuel level (unconfirmed — may need ID correction) ───────
            ID_FUEL_LEVEL -> if (n >= 1) state.copy(
                fuelLevelPct = ubyte(data, 0) * 0.392, lastUpdate = now
            ) else null

            // ── 0x3C0: Battery voltage (unconfirmed — may not broadcast) ──────
            ID_BATTERY -> if (n >= 1) state.copy(
                batteryVoltage = ubyte(data, 0) * 0.1, lastUpdate = now
            ) else null

            else -> null
        }
    }

    // ── Diagnostic helpers ────────────────────────────────────────────────────

    /**
     * Returns a short human-readable summary of the fields decoded from [id] in [state].
     * Used by DiagnosticLogger to populate the decode trace and frame inventory.
     */
    fun describeDecoded(id: Int, state: VehicleState): String = when (id) {
        ID_ENGINE_RPM   -> "rpm=${state.rpm.toInt()}, baro=${"%.1f".format(state.barometricPressure)}kPa"
        ID_ENGINE_TEMPS -> "oilTempC=${"%.0f".format(state.oilTempC)}, boost=${state.boostKpa.toInt()}kPa, ptuTempC=${"%.0f".format(state.ptuTempC)}"
        ID_SPEED        -> "speedKph=${"%.2f".format(state.speedKph)}"
        ID_COOLANT      -> "coolantTempC=${"%.1f".format(state.coolantTempC)}, iatTempC=${"%.1f".format(state.intakeTempC)}"
        ID_THROTTLE     -> "throttlePct=${"%.1f".format(state.throttlePct)}"
        ID_PEDALS       -> "accelPct=${"%.1f".format(state.accelPedalPct)}, rev=${state.reverseStatus}"
        ID_LONG_ACCEL   -> "lonG=${"%.4f".format(state.longitudinalG)}g"
        ID_LAT_ACCEL    -> "latG=${"%.4f".format(state.lateralG)}g yaw=${"%.2f".format(state.yawRate)}°/s"
        ID_STEERING     -> "steer=${"%.1f".format(state.steeringAngle)}°"
        ID_BRAKE_PRESS  -> "brake=${"%.1f".format(state.brakePressure)}"
        ID_GAUGE_ILLUM  -> "illum=${state.gaugeIllumination}, eBrake=${state.eBrake}"
        ID_TPMS         -> "lf=${"%.0f".format(state.tirePressLF)} rf=${"%.0f".format(state.tirePressRF)} lr=${"%.0f".format(state.tirePressLR)} rr=${"%.0f".format(state.tirePressRR)} PSI, ambient=${"%.2f".format(state.ambientTempC)}°C"
        ID_AMBIENT_TEMP -> "ambientTempC=${"%.2f".format(state.ambientTempC)}"
        ID_WHEEL_SPEEDS -> "FL=${"%.1f".format(state.wheelSpeedFL)} FR=${"%.1f".format(state.wheelSpeedFR)} RL=${"%.1f".format(state.wheelSpeedRL)} RR=${"%.1f".format(state.wheelSpeedRR)} km/h"
        ID_AWD_TORQUE   -> "L=${"%.0f".format(state.awdLeftTorque)}Nm R=${"%.0f".format(state.awdRightTorque)}Nm"
        ID_AWD_MSG      -> "driveMode=${state.driveMode.label}"
        ID_ESC_ABS      -> "escStatus=${state.escStatus.label}"
        ID_GEAR         -> "gear=${state.gearDisplay}"
        ID_TORQUE       -> "torqueNm=${"%.0f".format(state.torqueAtTrans)}"
        ID_FUEL_LEVEL   -> "fuelPct=${"%.1f".format(state.fuelLevelPct)}"
        ID_BATTERY      -> "battV=${"%.2f".format(state.batteryVoltage)}"
        else            -> "(unknown id 0x%03X)".format(id)
    }

    /**
     * Returns a validation warning string if the decoded value appears physically impossible,
     * or null if everything looks reasonable. Flags are shown in the diagnostic report.
     */
    fun validateDecoded(id: Int, state: VehicleState): String? = when (id) {
        ID_ENGINE_RPM   -> when {
            state.rpm < 0          -> "rpm<0 (${state.rpm.toInt()}) — formula may extract wrong bytes"
            state.rpm > 9000       -> "rpm>9000 (${state.rpm.toInt()}) — formula may extract wrong bytes"
            state.barometricPressure < 60 || state.barometricPressure > 115
                                   -> "baro=${"%.1f".format(state.barometricPressure)}kPa outside 60-115 range"
            else -> null
        }
        ID_ENGINE_TEMPS -> when {
            state.oilTempC < -50   -> "oilTempC=${"%.0f".format(state.oilTempC)} — check byte1 formula (raw-50)"
            state.oilTempC > 160   -> "oilTempC=${"%.0f".format(state.oilTempC)} — suspiciously hot"
            state.ptuTempC < -60   -> "ptuTempC=${"%.0f".format(state.ptuTempC)} — check byte7 formula (raw-60)"
            state.ptuTempC > 200   -> "ptuTempC=${"%.0f".format(state.ptuTempC)} — suspiciously hot"
            // Boost stored as absolute kPa (gauge + baro); at idle ≈ baro (~96-103 kPa).
            // Only warn on implausible extremes — boostKpa=0 means baro not yet populated.
            state.boostKpa < 60.0  -> "boostKpa=${"%.0f".format(state.boostKpa)} — too low (baro not populated?)"
            state.boostKpa > 400.0 -> "boostKpa=${"%.0f".format(state.boostKpa)} — >43 PSI, check formula"
            else -> null
        }
        ID_SPEED        -> when {
            state.speedKph < 0     -> "speedKph<0 (${"%.2f".format(state.speedKph)}) — formula wrong"
            state.speedKph > 320   -> "speedKph>320 (${"%.1f".format(state.speedKph)}) — formula wrong"
            else -> null
        }
        ID_COOLANT      -> when {
            state.coolantTempC < -50  -> "coolantTempC=${"%.0f".format(state.coolantTempC)} — check 10-bit formula (raw-60)"
            state.coolantTempC > 135  -> "coolantTempC=${"%.0f".format(state.coolantTempC)} — critically hot"
            state.intakeTempC < -50   -> "iatTempC=${"%.0f".format(state.intakeTempC)} — check 10-bit formula (raw×0.25-127)"
            state.intakeTempC > 80    -> "iatTempC=${"%.0f".format(state.intakeTempC)} — suspiciously hot"
            else -> null
        }
        ID_LONG_ACCEL   -> when {
            state.longitudinalG < -4 || state.longitudinalG > 4
                -> "lonG=${"%.3f".format(state.longitudinalG)}g outside ±4g"
            else -> null
        }
        ID_LAT_ACCEL    -> when {
            state.lateralG < -4 || state.lateralG > 4
                -> "latG=${"%.3f".format(state.lateralG)}g outside ±4g"
            state.yawRate < -75 || state.yawRate > 75
                -> "yawRate=${"%.1f".format(state.yawRate)}°/s outside ±75"
            else -> null
        }
        ID_STEERING     -> when {
            state.steeringAngle < -1440 || state.steeringAngle > 1440
                -> "steer=${"%.0f".format(state.steeringAngle)}° outside ±1440"
            else -> null
        }
        ID_BRAKE_PRESS  -> when {
            state.brakePressure < 0 || state.brakePressure > 110
                -> "brakePct=${"%.1f".format(state.brakePressure)} outside 0-110 range"
            else -> null
        }
        ID_BATTERY      -> when {
            state.batteryVoltage in 0.01..9.9  -> "battV=${"%.2f".format(state.batteryVoltage)} — too low, formula may be wrong"
            state.batteryVoltage > 17.0         -> "battV=${"%.2f".format(state.batteryVoltage)} — too high"
            else -> null
        }
        ID_AMBIENT_TEMP -> when {
            state.ambientTempC < -50 -> "ambientTempC=${"%.1f".format(state.ambientTempC)} — check signed×0.25 formula"
            state.ambientTempC > 60  -> "ambientTempC=${"%.1f".format(state.ambientTempC)} — suspiciously hot"
            else -> null
        }
        ID_TPMS         -> when {
            state.tirePressLF > 0 && (state.tirePressLF > 80 || state.tirePressRF > 80 ||
             state.tirePressLR > 80 || state.tirePressRR > 80)
                -> "tire pressure > 80 PSI — formula may be wrong"
            else -> null
        }
        ID_PEDALS       -> when {
            state.accelPedalPct < 0   -> "accelPct<0 (${"%.1f".format(state.accelPedalPct)}) — formula wrong"
            state.accelPedalPct > 105 -> "accelPct>105 (${"%.1f".format(state.accelPedalPct)}) — formula wrong"
            else -> null
        }
        else -> null
    }

    // ── Bit / byte helpers ───────────────────────────────────────────────────

    private fun ubyte(data: ByteArray, offset: Int): Int =
        data[offset].toInt() and 0xFF

    private fun word(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    /** Motorola big-endian bit extraction — MSB at startBit, counting forward. */
    private fun bits(data: ByteArray, startBit: Int, length: Int): Int {
        var value = 0
        for (i in 0 until length) {
            val byteIdx = (startBit + i) shr 3
            val bitIdx  = 7 - ((startBit + i) and 7)
            if (byteIdx < data.size && ((data[byteIdx].toInt() shr bitIdx) and 1) == 1)
                value = value or (1 shl (length - 1 - i))
        }
        return value
    }
}
