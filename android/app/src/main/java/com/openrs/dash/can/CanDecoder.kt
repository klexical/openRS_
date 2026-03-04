package com.openrs.dash.can

import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState

/**
 * Focus RS MK3 HS-CAN passive frame decoder.
 *
 * Sources:
 *  - DigiCluster can0_hs.json  — HS-CAN @ 500 kbps confirmed signals
 *  - DigiCluster can1_ms.json  — MS-CAN @ 125 kbps signals bridged through
 *    the GWM (Gateway Module) to the OBD port (accessible via WebSocket SLCAN)
 *  - RS_HS.dbc references (via CanDecoder comments)
 *
 * TPMS note: RSdash/DigiCluster sourced tire pressures passively from CAN 0x340
 * (MS-CAN bridged to OBD port). OBD Mode 22 BCM queries are NOT required.
 */
object CanDecoder {

    // ── HS-CAN engine / powertrain ──────────────────────────────────────────
    const val ID_TORQUE       = 0x070   // Torque at trans (Motorola bits 37-47)
    const val ID_THROTTLE     = 0x076   // Throttle % (byte 0 × 0.392) — may not broadcast on all tunes
    const val ID_PEDALS       = 0x080   // Accel pedal (bits 0-9 LE ×0.1 %), brake (bit 2), reverse (bit 5)
    const val ID_ENGINE_RPM   = 0x090   // RPM (byte4 low-nib|byte5 × 2), baro (byte2 × 0.5 kPa)
    const val ID_GAUGE_ILLUM  = 0x0C8   // Gauge brightness (bits 0-4), e-brake (byte3 bit 6)
    const val ID_ENGINE_TEMPS = 0x0F8   // Boost kPa (byte5), oil temp (byte7 −60 °C)
    const val ID_SPEED        = 0x130   // Speed bytes 6-7 BE × 0.01 kph
    const val ID_LONG_ACCEL   = 0x160   // Longitudinal G bits 48-57 LE × 0.00390625 − 2.0
    const val ID_LAT_ACCEL    = 0x180   // Lateral G bits 16-25 LE × 0.00390625 − 2.0
    const val ID_AWD_MSG      = 0x1B0   // DriveMode Motorola bit 55|4  — RS_HS.dbc confirmed
    const val ID_ESC_ABS      = 0x1C0   // ESCMode Motorola bit 13|2    — RS_HS.dbc confirmed
    const val ID_WHEEL_SPEEDS = 0x215   // Wheel speeds FL/FR/RL/RR, word each, − 10 000 × 0.01 kph
    const val ID_GEAR         = 0x230   // Gear bits 0-3
    const val ID_AWD_TORQUE   = 0x2C0   // RDU left/right Nm (bits 0|12), RDU temp (byte3 −40)
    const val ID_PTU_TEMP     = 0x2C2   // PTU temp (byte0 −40 °C)
    const val ID_COOLANT      = 0x2F0   // Coolant byte5 −60 °C

    // ── MS-CAN signals bridged to OBD port via GWM ─────────────────────────
    // RSdash & DigiCluster both read these passively — no OBD queries needed.
    const val ID_TPMS         = 0x340   // Tire pressures LF/RF/LR/RR bytes 2-5 in PSI (direct)
    const val ID_AMBIENT_TEMP = 0x1A4   // Ambient temp byte4 signed × 0.25 °C (MS-CAN bridged)

    // ── Speculative / may not be present on all variants ────────────────────
    const val ID_FUEL_LEVEL   = 0x34A   // Fuel % byte0 × 0.392 (unconfirmed ID, may need adjustment)
    const val ID_BATTERY      = 0x3C0   // Battery V byte0 × 0.1 (unconfirmed, may not broadcast)

    private val KNOWN_IDS = setOf(
        ID_TORQUE, ID_THROTTLE, ID_PEDALS, ID_ENGINE_RPM,
        ID_GAUGE_ILLUM, ID_ENGINE_TEMPS, ID_SPEED,
        ID_LONG_ACCEL, ID_LAT_ACCEL,
        ID_AWD_MSG, ID_ESC_ABS,
        ID_WHEEL_SPEEDS, ID_GEAR, ID_AWD_TORQUE, ID_PTU_TEMP, ID_COOLANT,
        ID_TPMS, ID_AMBIENT_TEMP,
        ID_FUEL_LEVEL, ID_BATTERY
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

            // ── 0x0F8: Boost + oil temperature ────────────────────────────────
            // Boost kPa absolute: byte1   (confirmed via diagnostic analysis:
            //   byte5 is always 0x00 at any RPM; byte1 tracks manifold pressure
            //   correctly — 61 kPa at cold idle → −5.8 PSI, 87 kPa at warm idle → −2.1 PSI)
            // Oil temp °C:        byte7 − 60  (DigiCluster verified: 36°C cold, 60°C warm)
            ID_ENGINE_TEMPS -> if (n >= 8) state.copy(
                boostKpa  = ubyte(data, 1).toDouble(),
                oilTempC  = (ubyte(data, 7) - 60).toDouble(),
                lastUpdate = now
            ) else null

            // ── 0x130: Vehicle speed ───────────────────────────────────────────
            // bytes 6-7 big-endian × 0.01 km/h  [DigiCluster verified]
            ID_SPEED -> if (n >= 8) state.copy(
                speedKph   = word(data, 6) * 0.01,
                lastUpdate = now
            ) else null

            // ── 0x2F0: Coolant temperature ─────────────────────────────────────
            // byte5 − 60 °C  [DigiCluster verified]
            ID_COOLANT -> if (n >= 6) state.copy(
                coolantTempC = (ubyte(data, 5) - 60).toDouble(),
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

            // ── 0x180: Lateral acceleration ────────────────────────────────────
            // bits 16-25 little-endian: (byte2 & 0x03) << 8 | byte3, × 0.00390625 − 2.0 g
            // Range: −2.0 to +1.996 g. Invalid pattern: byte2 & 0x03 == 0x03 && byte3 == 0xFF
            ID_LAT_ACCEL -> if (n >= 4) {
                val b2 = data[2].toInt() and 0xFF
                val b3 = data[3].toInt() and 0xFF
                if ((b2 and 0x03) == 0x03 && b3 == 0xFF) null
                else state.copy(
                    lateralG   = ((b2 and 0x03) shl 8 or b3) * 0.00390625 - 2.0,
                    lastUpdate = now
                )
            } else null

            // ── 0x0C8: Gauge illumination + e-brake ───────────────────────────
            // Brightness: bits 0-4 of byte0  [DigiCluster verified]
            // E-brake:    bit 6 of byte3      [DigiCluster verified]
            ID_GAUGE_ILLUM -> if (n >= 4) state.copy(
                gaugeIllumination = data[0].toInt() and 0x1F,
                eBrake            = (data[3].toInt() and 0x40) != 0,
                lastUpdate        = now
            ) else null

            // ── 0x340: TPMS tire pressures — MS-CAN bridged via GWM ───────────
            // DigiCluster can1_ms.json confirmed: direct PSI, bytes 2-5
            //   byte2 = LF, byte3 = RF, byte4 = LR, byte5 = RR
            // Sensors sleep when stationary; stale/noise readings can appear.
            // Valid range: 5–60 PSI. Out-of-range → keep previous stored value.
            // All-zero → sensors sleeping, no update (retain last known pressure).
            ID_TPMS -> if (n >= 6) {
                fun validPsi(raw: Int): Double? =
                    if (raw in 5..60) raw.toDouble() else null
                val lf = validPsi(ubyte(data, 2))
                val rf = validPsi(ubyte(data, 3))
                val lr = validPsi(ubyte(data, 4))
                val rr = validPsi(ubyte(data, 5))
                // Only update if at least one sensor has a valid reading
                if (lf == null && rf == null && lr == null && rr == null) null
                else state.copy(
                    tirePressLF = lf ?: state.tirePressLF,
                    tirePressRF = rf ?: state.tirePressRF,
                    tirePressLR = lr ?: state.tirePressLR,
                    tirePressRR = rr ?: state.tirePressRR,
                    lastUpdate  = now
                )
            } else null

            // ── 0x1A4: Ambient temperature — MS-CAN bridged via GWM ──────────
            // DigiCluster can1_ms.json: byte4 signed int8 × 0.25 °C
            ID_AMBIENT_TEMP -> if (n >= 5) state.copy(
                ambientTempC = data[4].toInt().toDouble() * 0.25,
                lastUpdate   = now
            ) else null

            // ── 0x215: Wheel speeds ────────────────────────────────────────────
            // Each wheel: uint16 BE − 10 000, × 0.01 kph
            ID_WHEEL_SPEEDS -> if (n >= 8) state.copy(
                wheelSpeedFL = (word(data, 0) - 10_000) * 0.01,
                wheelSpeedFR = (word(data, 2) - 10_000) * 0.01,
                wheelSpeedRL = (word(data, 4) - 10_000) * 0.01,
                wheelSpeedRR = (word(data, 6) - 10_000) * 0.01,
                lastUpdate   = now
            ) else null

            // ── 0x2C0: AWD / GKN Twinster ─────────────────────────────────────
            ID_AWD_TORQUE -> if (n >= 7) {
                var left  = bits(data, 0,  12).toDouble()
                var right = bits(data, 12, 12).toDouble()
                if (left  >= 0xFFE) left  = 0.0
                if (right >= 0xFFE) right = 0.0
                val maxRaw = bits(data, 43, 13)
                state.copy(
                    awdLeftTorque  = left,
                    awdRightTorque = right,
                    rduTempC       = (ubyte(data, 3) - 40).toDouble(),
                    awdMaxTorque   = if (maxRaw > 0) maxRaw.toDouble() else 0.0,
                    lastUpdate     = now
                )
            } else null

            // ── 0x2C2: PTU temperature ─────────────────────────────────────────
            ID_PTU_TEMP -> if (n >= 1) state.copy(
                ptuTempC = (ubyte(data, 0) - 40).toDouble(), lastUpdate = now
            ) else null

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
        ID_ENGINE_TEMPS -> "boost=${state.boostKpa.toInt()}kPa, oilTempC=${"%.0f".format(state.oilTempC)}"
        ID_SPEED        -> "speedKph=${"%.2f".format(state.speedKph)}"
        ID_COOLANT      -> "coolantTempC=${"%.1f".format(state.coolantTempC)}"
        ID_THROTTLE     -> "throttlePct=${"%.1f".format(state.throttlePct)}"
        ID_PEDALS       -> "accelPct=${"%.1f".format(state.accelPedalPct)}, rev=${state.reverseStatus}"
        ID_LONG_ACCEL   -> "lonG=${"%.4f".format(state.longitudinalG)}g"
        ID_LAT_ACCEL    -> "latG=${"%.4f".format(state.lateralG)}g"
        ID_GAUGE_ILLUM  -> "illum=${state.gaugeIllumination}, eBrake=${state.eBrake}"
        ID_TPMS         -> "lf=${"%.0f".format(state.tirePressLF)} rf=${"%.0f".format(state.tirePressRF)} lr=${"%.0f".format(state.tirePressLR)} rr=${"%.0f".format(state.tirePressRR)} PSI"
        ID_AMBIENT_TEMP -> "ambientTempC=${"%.2f".format(state.ambientTempC)}"
        ID_WHEEL_SPEEDS -> "FL=${"%.1f".format(state.wheelSpeedFL)} FR=${"%.1f".format(state.wheelSpeedFR)} RL=${"%.1f".format(state.wheelSpeedRL)} RR=${"%.1f".format(state.wheelSpeedRR)}"
        ID_AWD_TORQUE   -> "L=${"%.0f".format(state.awdLeftTorque)}Nm R=${"%.0f".format(state.awdRightTorque)}Nm rdu=${"%.0f".format(state.rduTempC)}°C"
        ID_PTU_TEMP     -> "ptuTempC=${"%.0f".format(state.ptuTempC)}"
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
            state.oilTempC < -50   -> "oilTempC=${"%.0f".format(state.oilTempC)} — check byte7 formula (raw-60)"
            state.oilTempC > 160   -> "oilTempC=${"%.0f".format(state.oilTempC)} — suspiciously hot"
            // Use a fixed string so the LinkedHashSet deduplicates to one entry.
            // Low MAP kPa at idle is normal (manifold vacuum); only truly impossible
            // value is 0 (which would mean disconnected/failed MAP sensor).
            state.boostKpa == 0.0  -> "boostKpa=0 — MAP sensor may be disconnected"
            state.boostKpa > 280   -> "boostKpa>${state.boostKpa.toInt()} — >26 PSI, check formula"
            else -> null
        }
        ID_SPEED        -> when {
            state.speedKph < 0     -> "speedKph<0 (${"%.2f".format(state.speedKph)}) — formula wrong"
            state.speedKph > 320   -> "speedKph>320 (${"%.1f".format(state.speedKph)}) — formula wrong"
            else -> null
        }
        ID_COOLANT      -> when {
            state.coolantTempC < -50 -> "coolantTempC=${"%.0f".format(state.coolantTempC)} — check byte5 formula (raw-60)"
            state.coolantTempC > 135 -> "coolantTempC=${"%.0f".format(state.coolantTempC)} — critically hot"
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
