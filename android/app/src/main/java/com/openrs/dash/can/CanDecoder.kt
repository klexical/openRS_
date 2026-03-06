package com.openrs.dash.can

import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger

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
 * in byte 7. The GWM does NOT bridge MS-CAN TPMS data to HS-CAN 0x340 — those bytes
 * are PCM signals unrelated to tire pressure. TPMS comes from BCM Mode 22 polling only.
 * RDU temp is not in any passive broadcast — it arrives via AWD module Mode 22 polling.
 *
 * Drive mode source: 0x1B0 byte 6 upper nibble is the only reliable steady-state source.
 * 0x17E (DriveModeRequest) only reflects Normal/Sport — Track and Drift are never
 * encoded there. Confirmed by cross-referencing 19 s of Track frames in a live log
 * (0x1B0 byte6=0x20 at t=86.8-106s; 0x17E nibble stayed at 1=Sport throughout).
 * Steady-state frames have byte4=0x00; button-event frames have byte4 != 0.
 */
object CanDecoder {

    // ── Instrumentation state (reset each session via resetDebugState()) ─────
    @Volatile private var lastDriveModeNibble: Int = -1
    @Volatile private var peakBrakeRaw: Int = 0
    @Volatile private var lastBrakeRaw: Int = 0

    /** Call at session start so instrumentation state is fresh per session. */
    fun resetDebugState() {
        lastDriveModeNibble = -1
        peakBrakeRaw        = 0
        lastBrakeRaw        = 0
    }

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
    // 0x1B0: drive mode status + button event frame.
    // Steady-state mode is encoded in byte 6 upper nibble (0=Normal 1=Sport 2=Track 3=Drift).
    // Track=nibble 2 (0x20) confirmed from prior live-log reference at 86.8-106s.
    // Steady-state frames have byte 4 == 0x00; button-event transitions have byte 4 != 0.
    // 0x17E (DriveModeRequest) only reflects Normal/Sport — Track and Drift are absent.
    const val ID_DRIVE_MODE   = 0x1B0
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
    // SteeringWheelAngle : 54|15@0+ (0.04395,0) → (byte6&0x7F)<<8|byte7 × 0.04395 °
    // SteeringAngleSign  : 39|1@0+ (1,0)        → (byte4>>7)&1 → 1=CW(right) 0=CCW/left
    const val ID_STEERING     = 0x010

    // ── RS_HS.dbc ABSmsg10 (0x252): Brake pressure ──────────────────────────
    // BrakePressureMeasured : 11|12@0+ (1,0) → (byte1&0x0F)<<8|byte2 raw 0-4095
    // Displayed as 0-100% (raw / 40.95) until bar calibration is confirmed.
    const val ID_BRAKE_PRESS  = 0x252

    // RS_HS.dbc PCMmsg30 (0x380): FuelLevelFiltered : 17|10@0+ (0.4,0) [0|102] "%"
    // Motorola big-endian, 10-bit, start bit 17 (MSB). Extract: (data[2]&0x03)<<8|data[3], ×0.4 %
    // Confirmed from live log: raw=254 → 101.6 % (full tank). Range-filtered 0–110 %.
    // 12V battery voltage does NOT broadcast on HS-CAN — polled via Mode 01 PID 0x42 in WiCanConnection.
    // TODO(M-5): Find correct PID for 12V battery voltage on Focus RS MK3 and add to PCM_QUERIES/parsePcmResponse.
    const val ID_FUEL_LEVEL   = 0x380

    private val KNOWN_IDS = setOf(
        ID_TORQUE, ID_THROTTLE, ID_PEDALS, ID_ENGINE_RPM,
        ID_GAUGE_ILLUM, ID_ENGINE_TEMPS, ID_SPEED,
        ID_LONG_ACCEL, ID_LAT_ACCEL,
        ID_DRIVE_MODE, ID_ESC_ABS,
        ID_WHEEL_SPEEDS, ID_GEAR, ID_AWD_TORQUE, ID_COOLANT,
        ID_TPMS, ID_AMBIENT_TEMP,
        ID_FUEL_LEVEL,
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
                if ((b6 and 0x03) == 0x03 && b7 >= 0xFE) null
                else state.copy(
                    longitudinalG = ((b6 and 0x03) shl 8 or b7) * 0.00390625 - 2.0,
                    lastUpdate    = now
                )
            } else null

            // ── 0x180: Lateral acceleration + Yaw rate ─────────────────────────
            // RS_HS.dbc ABSmsg02 (10 ms broadcast):
            //   LatAccelMeasured  : 17|10@0+ (0.00390625,-2)
            //     = (byte2 & 0x03) << 8 | byte3, × 0.00390625 − 2.0 g
            //   YawRateMeasured   : 35|12@0+ (0.03663,-75)
            //     = (byte4 & 0x0F) << 8 | byte5, × 0.03663 − 75.0 °/s
            //     Range: ±75 °/s (raw 0=−75, raw 2048≈0, raw 4095=+75).
            //     NOTE: manual extraction required — the yaw signal uses standard DBC
            //     LSB-first Motorola bit numbering (bit 35 = byte4 pos3), while the
            //     bits() helper uses MSB-first network addressing, which gives ~37.5°/s
            //     for a stationary vehicle.  Validated against 0x213 cross-reference and
            //     physical lat-G / yaw / speed triangle from live log data.
            // Invalid pattern for lat G: byte2 & 0x03 == 0x03 && byte3 == 0xFF
            ID_LAT_ACCEL -> if (n >= 8) {
                val b2 = data[2].toInt() and 0xFF
                val b3 = data[3].toInt() and 0xFF
                if ((b2 and 0x03) == 0x03 && b3 >= 0xFE) null
                else state.copy(
                    lateralG   = ((b2 and 0x03) shl 8 or b3) * 0.00390625 - 2.0,
                    yawRate    = ((ubyte(data, 4) and 0x0F) shl 8 or ubyte(data, 5)) * 0.03663 - 75.0,
                    lastUpdate = now
                )
            } else null

            // ── 0x010: Steering wheel angle ────────────────────────────────────
            // RS_HS.dbc SASMmsg01 (10 ms broadcast):
            //   SteeringWheelAngle : 54|15@0+ (0.04395,0)
            //     = (byte6 & 0x7F) << 8 | byte7, × 0.04395 °
            //     Range: 0–1440 °.
            //   SteeringAngleSign  : 39|1@0+  (1,0)
            //     = (byte4 >> 7) & 1 → 1=CW/right 0=CCW/left
            //     NOTE: same DBC convention correction as yaw rate — LSB-first Motorola.
            //     Manual extraction confirmed correct: shows ≈−360° at full steering lock
            //     during a U-turn and plausible small angles (≤10°) while parked.
            ID_STEERING -> if (n >= 8) {
                val angleMag = ((ubyte(data, 6) and 0x7F) shl 8 or ubyte(data, 7)) * 0.04395
                val signBit  = (ubyte(data, 4) shr 7) and 1
                state.copy(
                    steeringAngle = if (signBit == 1) angleMag else -angleMag,
                    lastUpdate    = now
                )
            } else null

            // ── 0x252: Brake pressure ──────────────────────────────────────────
            // RS_HS.dbc ABSmsg10 (20 ms broadcast):
            //   BrakePressureMeasured : 11|12@0+ (1,0)
            //     = (byte1 & 0x0F) << 8 | byte2, raw 0–4095
            //     NOTE: same DBC convention correction — LSB-first Motorola.
            //     bit 11 = byte1 pos3; 4 bits from byte1 low-nibble + byte2 = 12 bits.
            // Units unconfirmed — displayed as 0–100 scale (raw / 40.95) until bar
            // calibration is verified from a live log with known brake pressure.
            // Live log confirmed: raw=912 at initial brake application → 22.3%.
            ID_BRAKE_PRESS -> if (n >= 3) {
                val brakeRaw = (ubyte(data, 1) and 0x0F) shl 8 or ubyte(data, 2)
                // #region agent log
                if (brakeRaw > 50 && brakeRaw > peakBrakeRaw) {
                    // New peak within this brake application
                    peakBrakeRaw = brakeRaw
                    DiagnosticLogger.event("DBG_BRAKE",
                        "0x252 peak raw=$brakeRaw pct=${"%.1f".format(brakeRaw/40.95)}% b1=0x${ubyte(data,1).toString(16).uppercase()} b2=0x${ubyte(data,2).toString(16).uppercase()}")
                } else if (brakeRaw == 0 && lastBrakeRaw > 0) {
                    // Brake released — reset peak for next application
                    peakBrakeRaw = 0
                }
                lastBrakeRaw = brakeRaw
                // #endregion
                state.copy(brakePressure = brakeRaw / 40.95, lastUpdate = now)
            } else null

            // ── 0x0C8: Gauge illumination + e-brake ───────────────────────────
            // Brightness: bits 0-4 of byte0  [DigiCluster verified]
            // E-brake:    bit 6 of byte3      [DigiCluster verified]
            ID_GAUGE_ILLUM -> if (n >= 4) state.copy(
                gaugeIllumination = data[0].toInt() and 0x1F,
                eBrake            = (data[3].toInt() and 0x40) != 0,
                lastUpdate        = now
            ) else null

            // ── 0x340: PCMmsg17 (HS-CAN) — AmbientAirTemp only ─────────────────
            // RS_HS.dbc PCMmsg17: AmbientAirTemp : 63|8@0− → byte7 signed × 0.25 °C
            // Bytes 2-5 are PCM engine signals (NOT TPMS — TPMS is BCM Mode 22 only).
            ID_TPMS -> if (n >= 8) {
                val ambient = data[7].toInt().toDouble() * 0.25
                if (ambient !in -50.0..60.0) null
                else state.copy(ambientTempC = ambient, lastUpdate = now)
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

            // ── 0x1B0: Drive mode steady-state ────────────────────────────────
            // Byte 6 upper nibble: DriveMode.fromInt mapping is 0=Normal 1=Sport 2=Track 3=Drift.
            // Track=nibble 2 (byte6=0x20) confirmed from prior live log; Drift=nibble 3 follows ordering.
            // Steady-state frames have byte 4 == 0x00.
            // Button-event transition frames have byte 4 != 0 → ignored for mode tracking.
            ID_DRIVE_MODE -> if (n >= 7) {
                val b4 = data[4].toInt() and 0xFF
                val b6 = data[6].toInt() and 0xFF
                val nibble = b6 ushr 4
                // #region agent log
                if (b4 == 0) {
                    // Log every steady-state decode; log every occurrence when nibble > 1 (Track/Drift)
                    if (nibble != lastDriveModeNibble || nibble > 1) {
                        lastDriveModeNibble = nibble
                        DiagnosticLogger.event("DBG_DRIVEMODE",
                            "0x1B0 STEADY nibble=$nibble(${DriveMode.fromInt(nibble).label}) b6=0x${b6.toString(16).uppercase()}")
                    }
                } else if (nibble > 1) {
                    // Always log event frames that carry Track/Drift nibble values
                    DiagnosticLogger.event("DBG_DRIVEMODE",
                        "0x1B0 EVENT nibble=$nibble(${DriveMode.fromInt(nibble).label}) b4=0x${b4.toString(16).uppercase()} b6=0x${b6.toString(16).uppercase()}")
                }
                // #endregion
                if (b4 != 0) null
                else state.copy(driveMode = DriveMode.fromInt(nibble), lastUpdate = now)
            } else null

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

            // ── 0x380: Fuel level filtered ────────────────────────────────────
            // RS_HS.dbc PCMmsg30: FuelLevelFiltered : 17|10@0+ (0.4,0) [0|102] "%"
            // Motorola 10-bit: MSB at DBC bit 17 → (data[2]&0x03)<<8 | data[3], × 0.4 %
            // L-2 fix: removed dead `if (pct < 0.0) null` — unreachable after coerceIn(0..100)
            ID_FUEL_LEVEL -> if (n >= 4) {
                val raw = ((data[2].toInt() and 0x03) shl 8) or (data[3].toInt() and 0xFF)
                val pct = (raw * 0.4).coerceIn(0.0, 100.0)  // clamp: DBC range [0|102], cap at 100
                state.copy(fuelLevelPct = pct, lastUpdate = now)
            } else null

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
        ID_TPMS         -> "ambient=${"%.2f".format(state.ambientTempC)}°C (TPMS from BCM Mode 22)"
        ID_AMBIENT_TEMP -> "ambientTempC=${"%.2f".format(state.ambientTempC)}"
        ID_WHEEL_SPEEDS -> "FL=${"%.1f".format(state.wheelSpeedFL)} FR=${"%.1f".format(state.wheelSpeedFR)} RL=${"%.1f".format(state.wheelSpeedRL)} RR=${"%.1f".format(state.wheelSpeedRR)} km/h"
        ID_AWD_TORQUE   -> "L=${"%.0f".format(state.awdLeftTorque)}Nm R=${"%.0f".format(state.awdRightTorque)}Nm"
        ID_DRIVE_MODE   -> "driveMode=${state.driveMode.label} (0x1B0 byte6)"
        ID_ESC_ABS      -> "escStatus=${state.escStatus.label}"
        ID_GEAR         -> "gear=${state.gearDisplay}"
        ID_TORQUE       -> "torqueNm=${"%.0f".format(state.torqueAtTrans)}"
        ID_FUEL_LEVEL   -> "fuelPct=${"%.1f".format(state.fuelLevelPct)} (0x380 Motorola)"
        else            -> "(unknown id 0x%03X)".format(id)
    }

    /**
     * Returns a validation warning string if the decoded value appears physically impossible,
     * or null if everything looks reasonable. Flags are shown in the diagnostic report.
     */
    /**
     * M-9 fix: issue keys are normalised (no dynamic values embedded) so LinkedHashSet
     * deduplication works correctly and the set doesn't grow unbounded over a session.
     * Dynamic readings are accessible in the decode trace; issues track categories only.
     */
    fun validateDecoded(id: Int, state: VehicleState): String? = when (id) {
        ID_ENGINE_RPM   -> when {
            state.rpm < 0          -> "rpm<0 — formula may extract wrong bytes"
            state.rpm > 9000       -> "rpm>9000 — formula may extract wrong bytes"
            state.barometricPressure < 60 || state.barometricPressure > 115
                                   -> "baro outside 60-115 kPa range"
            else -> null
        }
        ID_ENGINE_TEMPS -> when {
            state.oilTempC < -50   -> "oilTempC<-50 — check byte1 formula (raw-50)"
            state.oilTempC > 160   -> "oilTempC>160 — suspiciously hot"
            state.ptuTempC > -90 && state.ptuTempC < -60 -> "ptuTempC<-60 — check byte7 formula (raw-60)"
            state.ptuTempC > 200   -> "ptuTempC>200 — suspiciously hot"
            state.boostKpa < 60.0  -> "boostKpa<60 — too low (baro not populated?)"
            state.boostKpa > 400.0 -> "boostKpa>400 — >43 PSI, check formula"
            else -> null
        }
        ID_SPEED        -> when {
            state.speedKph < 0     -> "speedKph<0 — formula wrong"
            state.speedKph > 320   -> "speedKph>320 — formula wrong"
            else -> null
        }
        ID_COOLANT      -> when {
            state.coolantTempC < -50  -> "coolantTempC<-50 — check 10-bit formula (raw-60)"
            state.coolantTempC > 135  -> "coolantTempC>135 — critically hot"
            state.intakeTempC < -50   -> "iatTempC<-50 — check 10-bit formula (raw×0.25-127)"
            state.intakeTempC > 80    -> "iatTempC>80 — suspiciously hot"
            else -> null
        }
        ID_LONG_ACCEL   -> when {
            state.longitudinalG < -4 || state.longitudinalG > 4 -> "lonG outside ±4g"
            else -> null
        }
        ID_LAT_ACCEL    -> when {
            state.lateralG < -4 || state.lateralG > 4 -> "latG outside ±4g"
            state.yawRate < -75 || state.yawRate > 75  -> "yawRate outside ±75 °/s"
            else -> null
        }
        ID_STEERING     -> when {
            state.steeringAngle < -1440 || state.steeringAngle > 1440 -> "steer outside ±1440°"
            else -> null
        }
        ID_BRAKE_PRESS  -> when {
            state.brakePressure < 0 || state.brakePressure > 110 -> "brakePct outside 0-110 range"
            else -> null
        }
        ID_AMBIENT_TEMP -> when {
            state.ambientTempC < -50 -> "ambientTempC<-50 — check signed×0.25 formula"
            state.ambientTempC > 60  -> "ambientTempC>60 — suspiciously hot"
            else -> null
        }
        ID_TPMS         -> when {
            state.tirePressLF > 0 && (state.tirePressLF > 80 || state.tirePressRF > 80 ||
             state.tirePressLR > 80 || state.tirePressRR > 80)
                -> "tire pressure > 80 PSI — formula may be wrong"
            else -> null
        }
        ID_PEDALS       -> when {
            state.accelPedalPct < 0   -> "accelPct<0 — formula wrong"
            state.accelPedalPct > 105 -> "accelPct>105 — formula wrong"
            else -> null
        }
        else -> null
    }

    // ── Bit / byte helpers ───────────────────────────────────────────────────

    private fun ubyte(data: ByteArray, offset: Int): Int =
        data[offset].toInt() and 0xFF

    private fun word(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    /**
     * MSB-first network bit extraction (bit 0 = MSB of byte 0).
     * Correct for RS_HS.dbc signals written with this convention (torque, ESC, AWD torque).
     * NOT correct for signals using standard DBC LSB-first Motorola numbering (yaw, steer,
     * brake, coolant, latG) — those use manual byte extraction with the &-mask/shift pattern.
     */
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
