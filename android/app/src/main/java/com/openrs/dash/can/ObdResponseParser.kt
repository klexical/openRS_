package com.openrs.dash.can

import android.util.Log
import com.openrs.dash.data.VehicleState

/**
 * Stateless OBD-II Mode 22 response parsers shared by all adapter implementations.
 *
 * Each function decodes an ISO-TP single-frame response from a specific ECU
 * and calls [onObdUpdate] with the relevant field updated in [currentState].
 *
 * Response frame layout (standard Ford +8 offset):
 *   data[0] = PCI (SF: high nibble 0x0, low nibble = payload length)
 *   data[1] = 0x62 (positive response to Mode 0x22)
 *   data[2] = DID high byte
 *   data[3] = DID low byte
 *   data[4..] = B4, B5, B6… (data bytes)
 */
object ObdResponseParser {

    private const val TAG = "OBD"

    private fun logMalformed(module: String, reason: String) {
        com.openrs.dash.diagnostics.DiagnosticLogger.countRefusedReply()            // counted for the export (#253)
        Log.w(TAG, "$module: $reason")     // never the bytes: logcat ships in release (#263)
    }

    /** Too short to decode: a refused reply only if it is one, not an NRC, a flow control or a consecutive frame (#253). */
    private fun logShort(module: String, data: ByteArray) {
        if (data.size >= 2 && (data[1].toInt() and 0xFF) == 0x62) logMalformed(module, "too short (${data.size} < 5)")
    }

    /**
     * Data bytes a single-frame positive reply carries per its own PCI (#251): `0N 62 DH DL d1 … d(N−3)` → N − 3. Null when
     * the PCI isn't a single frame, claims no data byte, or claims more bytes than the frame holds (a truncated line) —
     * bytes past the PCI length are padding and never read as data.
     */
    internal fun singleFrameDataBytes(data: ByteArray): Int? {
        val pci = data[0].toInt() and 0xFF
        if (pci shr 4 != 0) return null
        val n = pci and 0x0F
        if (n < 4 || n > data.size - 1) return null
        return n - 3
    }

    /** A decoder read a data byte its reply doesn't carry — nothing is updated (no value, no freshness stamp). */
    private class ShortReply : RuntimeException("reply shorter than its value", null, false, false)

    fun parseBcmResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (data.size < 5) { logShort("BCM", data); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val avail = singleFrameDataBytes(data) ?: run { logMalformed("BCM", "PCI and frame length disagree"); return }
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) {
            0xDD01 -> {
                if (avail < 3) return
                val km = ((b4 shl 16) or
                         ((data[5].toInt() and 0xFF) shl 8) or
                          (data[6].toInt() and 0xFF)).toLong()
                onObdUpdate(currentState.copy(odometerKm = km).markFresh("odometerKm"))
            }
            0x4028 -> onObdUpdate(currentState.copy(batterySoc = b4.toDouble()).markFresh("batterySoc"))
            0x4029 -> onObdUpdate(currentState.copy(batteryTempC = (b4 - 40).toDouble()).markFresh("batteryTempC"))
            0xDD04 -> onObdUpdate(currentState.copy(cabinTempC = (b4 * 10.0 / 9.0) - 45.0).markFresh("cabinTempC"))
            0x411D -> onObdUpdate(currentState.copy(batteryChargingVoltageDesired = b4 * 0.1).markFresh("batteryChargingVoltageDesired"))
            // Battery current: ((A*256+B)/16) - 511.7 amps; signed (+ charging, − discharging)
            0x4090 -> {
                if (avail < 2) return
                val b5 = data[5].toInt() and 0xFF
                onObdUpdate(currentState.copy(
                    batteryCurrentA = ((b4 * 256 + b5) / 16.0) - 511.7
                ).markFresh("batteryCurrentA"))
            }
            // TPMS: PSI = (((256*A)+B) / 3.0 + 22.0/3.0) * 0.145
            0x2813, 0x2814, 0x2816, 0x2815 -> {
                if (avail < 2) return
                val b5 = data[5].toInt() and 0xFF
                val psi = ((b4 * 256.0 + b5) / 3.0 + 22.0 / 3.0) * 0.145
                if (psi < 5.0 || psi > 70.0) return
                val now = System.currentTimeMillis()
                onObdUpdate(when (did) {
                    0x2813 -> currentState.copy(tirePressLF = psi, tpmsLastUpdate = now).markFresh("tirePressLF")
                    0x2814 -> currentState.copy(tirePressRF = psi, tpmsLastUpdate = now).markFresh("tirePressRF")
                    0x2816 -> currentState.copy(tirePressLR = psi, tpmsLastUpdate = now).markFresh("tirePressLR")
                    else   -> currentState.copy(tirePressRR = psi, tpmsLastUpdate = now).markFresh("tirePressRR")
                })
            }
            // TPMS sensor IDs — 4-byte ID per tire position, polled once on connect
            0x280F, 0x2810, 0x2811, 0x2812 -> {
                if (avail < 4) return
                val sensorId = ((b4.toLong() shl 24) or
                    ((data[5].toInt() and 0xFF).toLong() shl 16) or
                    ((data[6].toInt() and 0xFF).toLong() shl 8) or
                    (data[7].toInt() and 0xFF).toLong())
                onObdUpdate(when (did) {
                    0x280F -> currentState.copy(tpmsSensorIdLF = sensorId)
                    0x2810 -> currentState.copy(tpmsSensorIdRF = sensorId)
                    0x2811 -> currentState.copy(tpmsSensorIdRR = sensorId)
                    else   -> currentState.copy(tpmsSensorIdLR = sensorId)
                })
            }
        }
    }

    /**
     * Parse a reassembled multi-frame BCM response (ISO-TP payload without PCI byte).
     *
     * Used for DID 0x280B "last received TPMS sensor" which returns 12 bytes:
     *   {0x62} {DID hi} {DID lo} {ID0..ID3} {press_hi} {press_lo} {temp} {status} {checksum}
     *
     * The 4-byte sensor ID is matched against the stored per-tire IDs from
     * 0x280F-0x2812 to determine which tire position the data belongs to.
     * Pressure formula: (A*256+B) / 20  PSI
     * Temperature formula: raw - 40  °C
     *
     * Status byte: values < 6 indicate cached/stale data (discarded).
     * Live readings have status >= 6. See github.com/klexical/openRS_/issues/119.
     */
    fun parseBcmReassembled(
        payload: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (payload.size < 4) return
        if ((payload[0].toInt() and 0xFF) != 0x62) return
        val did = ((payload[1].toInt() and 0xFF) shl 8) or (payload[2].toInt() and 0xFF)
        when (did) {
            0x280B -> {
                if (payload.size < 11) {
                    logMalformed("BCM-MF", "0x280B too short (${payload.size} < 11)")
                    return
                }
                val status = payload[10].toInt() and 0xFF
                if (status < 6) {
                    Log.d(TAG, "0x280B status=$status (stale/cached), discarding")
                    return
                }
                val sensorId = ((payload[3].toInt() and 0xFF).toLong() shl 24) or
                    ((payload[4].toInt() and 0xFF).toLong() shl 16) or
                    ((payload[5].toInt() and 0xFF).toLong() shl 8) or
                    (payload[6].toInt() and 0xFF).toLong()
                val pressA = payload[7].toInt() and 0xFF
                val pressB = payload[8].toInt() and 0xFF
                val psi = (pressA * 256 + pressB) / 20.0
                val tempRaw = payload[9].toInt() and 0xFF
                if (tempRaw == 0) return   // sensor initializing — no valid temp yet (#130)
                val tempC = (tempRaw - 40).toDouble()
                if (tempC > 120) return

                val s = currentState
                val now = System.currentTimeMillis()
                val updated = when (sensorId) {
                    s.tpmsSensorIdLF -> s.copy(tireTempLF = tempC, tirePressLF = psi, tpmsLastUpdate = now)
                        .markFresh("tireTempLF", "tirePressLF")
                    s.tpmsSensorIdRF -> s.copy(tireTempRF = tempC, tirePressRF = psi, tpmsLastUpdate = now)
                        .markFresh("tireTempRF", "tirePressRF")
                    s.tpmsSensorIdRR -> s.copy(tireTempRR = tempC, tirePressRR = psi, tpmsLastUpdate = now)
                        .markFresh("tireTempRR", "tirePressRR")
                    s.tpmsSensorIdLR -> s.copy(tireTempLR = tempC, tirePressLR = psi, tpmsLastUpdate = now)
                        .markFresh("tireTempLR", "tirePressLR")
                    else -> {
                        Log.d(TAG, "0x280B unknown sensor ID %08X".format(sensorId))
                        null
                    }
                }
                if (updated != null) onObdUpdate(updated)
            }
        }
    }

    /**
     * AWD module (CAN ID 0x70B).
     * 0x1E8A → RDU oil temp (B4 − 40 °C)
     * 0xEE0B → RDU on/off (extended session)
     */
    fun parseAwdResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (data.size < 5) { logShort("AWD", data); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val avail = singleFrameDataBytes(data) ?: run { logMalformed("AWD", "PCI and frame length disagree"); return }
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        val b4s = data[4].toInt()
        val b5OrNull = if (avail >= 2) data[5].toInt() and 0xFF else null
        val b5 by lazy(LazyThreadSafetyMode.NONE) { b5OrNull ?: throw ShortReply() }
        try {
            when (did) {
                0x1E8A -> onObdUpdate(currentState.copy(rduTempC = (b4 - 40).toDouble()).markFresh("rduTempC"))
                0x1E8B -> onObdUpdate(currentState.copy(awdClutchTempL = (b4 - 40).toDouble()).markFresh("awdClutchTempL"))
                0x1E8C -> onObdUpdate(currentState.copy(awdClutchTempR = (b4 - 40).toDouble()).markFresh("awdClutchTempR"))
                0x1E90 -> onObdUpdate(currentState.copy(awdReqTorqueL = ((b4 shl 8) or b5).toDouble()).markFresh("awdReqTorqueL"))
                0x1E91 -> onObdUpdate(currentState.copy(awdReqTorqueR = ((b4 shl 8) or b5).toDouble()).markFresh("awdReqTorqueR"))
                0x1E92 -> onObdUpdate(currentState.copy(awdDmdPressure = ((b4 shl 8) or b5).toDouble()).markFresh("awdDmdPressure"))
                0x1E93 -> onObdUpdate(currentState.copy(awdPumpCurrent = b4 * 0.1).markFresh("awdPumpCurrent"))
                0x1E80 -> onObdUpdate(currentState.copy(transOilTempC = (b4 - 40).toDouble()).markFresh("transOilTempC"))
                // Per-clutch hydraulic actuator currents/pressures (focusrs.org RDU thread)
                0x1E9E -> onObdUpdate(currentState.copy(
                    awdClutchCurL = ((b4s.toByte().toInt() shl 8) or b5) / 128.0
                ).markFresh("awdClutchCurL"))
                0x1E9F -> onObdUpdate(currentState.copy(
                    awdClutchCurR = ((b4s.toByte().toInt() shl 8) or b5) / 128.0
                ).markFresh("awdClutchCurR"))
                0x1ED1 -> onObdUpdate(currentState.copy(
                    awdClutchPressureL = (((b4s.toByte().toInt() shl 8) or b5) * 2).toDouble()
                ).markFresh("awdClutchPressureL"))
                0x1ED2 -> onObdUpdate(currentState.copy(
                    awdClutchPressureR = (((b4s.toByte().toInt() shl 8) or b5) * 2).toDouble()
                ).markFresh("awdClutchPressureR"))
                0xEE0B -> onObdUpdate(currentState.copy(rduEnabled = b4 == 0x01).markFresh("rduEnabled"))
                else -> {
                    val decoded = PidRegistry.decode(ObdConstants.AWD_RESPONSE_ID, did, b4, b5OrNull)
                    if (decoded != null) {
                        val (field, value) = decoded
                        onObdUpdate(currentState.putGeneric(field, value))
                    }
                }
            }
        } catch (_: ShortReply) {
            logMalformed("AWD", "DID 0x%04X: $avail data byte(s), its value needs more".format(did))
        }
    }

    /**
     * PCM (CAN ID 0x7E8).
     * Sources: research/exportedPIDs.txt, Ford DID.csv
     */
    fun parsePcmResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (data.size < 5) { logShort("PCM", data); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val avail = singleFrameDataBytes(data) ?: run { logMalformed("PCM", "PCI and frame length disagree"); return }
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        val b4s = data[4].toInt()
        val b5OrNull = if (avail >= 2) data[5].toInt() and 0xFF else null
        val b5 by lazy(LazyThreadSafetyMode.NONE) { b5OrNull ?: throw ShortReply() }
        try {
            when (did) {
                // ETC throttle plate angle, unsigned 16-bit / 512 = degrees (#206 — DigiCluster on-car verified "1.71°
                // matched AP"; the FORScan-catalog ×100/8192 read 6.25× high: 111° desired). Not pedal / throttle %.
                0x093C -> onObdUpdate(currentState.copy(
                    etcAngleActual = ((b4 shl 8) or b5) / 512.0
                ).markFresh("etcAngleActual"))
                0x091A -> onObdUpdate(currentState.copy(
                    etcAngleDesired = ((b4 shl 8) or b5) / 512.0
                ).markFresh("etcAngleDesired"))
                0x0462 -> onObdUpdate(currentState.copy(
                    wgdcDesired = b4 * 100.0 / 128.0
                ).markFresh("wgdcDesired"))
                0x03EC -> onObdUpdate(currentState.copy(
                    ignCorrCyl1 = ((b4s.toByte().toInt() shl 8) or b5) / -512.0
                ).markFresh("ignCorrCyl1"))
                0x03ED -> onObdUpdate(currentState.copy(
                    ignCorrCyl2 = ((b4s.toByte().toInt() shl 8) or b5) / -512.0
                ).markFresh("ignCorrCyl2"))
                0x03EE -> onObdUpdate(currentState.copy(
                    ignCorrCyl3 = ((b4s.toByte().toInt() shl 8) or b5) / -512.0
                ).markFresh("ignCorrCyl3"))
                0x03EF -> onObdUpdate(currentState.copy(
                    ignCorrCyl4 = ((b4s.toByte().toInt() shl 8) or b5) / -512.0
                ).markFresh("ignCorrCyl4"))
                0x03E8 -> onObdUpdate(currentState.copy(
                    octaneAdjustRatio = ((b4s.toByte().toInt() shl 8) or b5) / 16384.0
                ).markFresh("octaneAdjustRatio"))
                // Charge air temp: signed /64 °C. Raw 0x0000 = no data — two RS PCMs answered 0x0000 for a whole
                // warming drive (ambient 3.5 °C, Discussion #182 + 2026-10-22 log); leave −99 rather than show 0.0 °C.
                0x0461 -> if (b4 != 0 || b5 != 0) onObdUpdate(currentState.copy(
                    chargeAirTempC = ((b4s.toByte().toInt() shl 8) or b5) / 64.0
                ).markFresh("chargeAirTempC"))
                0xF43C -> onObdUpdate(currentState.copy(
                    catalyticTempC = ((b4 shl 8) or b5) / 10.0 - 40.0
                ).markFresh("catalyticTempC"))
                0xF434 -> {
                    val afr = ((b4 shl 8) or b5) * 0.0004486
                    onObdUpdate(currentState.copy(afrActual = afr, lambdaActual = afr / 14.7, afrSensor1 = afr)
                        .markFresh("afrActual", "lambdaActual", "afrSensor1"))
                }
                // Commanded equivalence ratio (Mode 01 PID 0x44): 2 bytes, λ = raw / 32768
                0xF444 -> onObdUpdate(currentState.copy(
                    afrDesired   = b4 * 0.1144,
                    commandedAfr = ((b4 shl 8) or b5) / 32768.0
                ).markFresh("afrDesired", "commandedAfr"))
                // Calculated load (PID 0x04): A × 100/255 %
                0xF404 -> onObdUpdate(currentState.copy(calcLoad = b4 * 100.0 / 255.0).markFresh("calcLoad"))
                // Timing advance (PID 0x0E): A/2 − 64 °BTDC
                0xF40E -> onObdUpdate(currentState.copy(timingAdvance = b4 / 2.0 - 64.0).markFresh("timingAdvance"))
                // O2 bank 1 sensor 2 voltage (PID 0x15): A/200 V
                0xF415 -> onObdUpdate(currentState.copy(o2Voltage = b4 / 200.0).markFresh("o2Voltage"))
                // TIP absolute: raw / 128 kPa — FORScan TCBP / DigiCluster catalogs; replies on the owner's car read ≈ baro at
                // rest, not checked against an independent gauge (#198 / #207; rc.4–rc.5 used /903.81, a Ford DID-sheet psi formula)
                0x033E -> onObdUpdate(currentState.copy(tipActualKpa  = ((b4 shl 8) or b5) / 128.0).markFresh("tipActualKpa"))
                0x0466 -> onObdUpdate(currentState.copy(tipDesiredKpa = ((b4 shl 8) or b5) / 128.0).markFresh("tipDesiredKpa"))
                // Standard MAP (OBD PID 0x0B): A = kPa absolute, 0–255 (#271). Every byte is a reading, 0 included — no
                // "no data" value is established; 255 is the byte's ceiling (MapF40b.isSaturated). Stored off-state
                // (VehicleState has no free slot, #199). Unverified on a car.
                0xF40B -> onObdUpdate(currentState.putGeneric(com.openrs.dash.data.MapF40b.TAG, b4.toDouble()))
                // Low-side fuel pressure: raw × 0.5 kPa (DigiCluster dc_frs_0548, verified)
                0x0548 -> onObdUpdate(currentState.copy(
                    fuelRailPressure = ((b4 shl 8) or b5) * 0.5
                ).markFresh("fuelRailPressure"))
                0x0318 -> onObdUpdate(currentState.copy(
                    vctIntakeAngle = ((b4s.toByte().toInt() shl 8) or b5) / 16.0
                ).markFresh("vctIntakeAngle"))
                0x0319 -> onObdUpdate(currentState.copy(
                    vctExhaustAngle = ((b4s.toByte().toInt() shl 8) or b5) / 16.0
                ).markFresh("vctExhaustAngle"))
                0x054B -> onObdUpdate(currentState.copy(oilLifePct = b4.toDouble().coerceIn(0.0, 100.0)).markFresh("oilLifePct"))
                // HP fuel rail gauge pressure (PID 0x23, direct injection): raw × 10 kPa → psi.
                // 0xF422 (PID 0x22) is the port-injection relative pressure — NRC 0x31 on DI PCMs (#198).
                0xF423 -> onObdUpdate(currentState.copy(
                    hpFuelRailPsi = ((b4 shl 8) or b5) * 1.45038
                ).markFresh("hpFuelRailPsi"))
                0xF42F -> onObdUpdate(currentState.putGeneric(
                    "FuelLevel_OBD", (b4 * 100.0 / 255.0).coerceIn(0.0, 100.0)
                ))
                0x0304 -> onObdUpdate(currentState.copy(
                    batteryVoltage = ((b4 shl 8) or b5) / 2048.0
                ).markFresh("batteryVoltage"))
                // Spark advance: B4 × 0.25 deg
                // TODO: verify single-byte vs two-byte on real car
                // Spark advance, signed 16-bit / 4 = degrees (#205): the high byte alone read 0.00 for every positive
                // reply and 63.75 for −5.5°. Negative advance is valid.
                0x116B -> onObdUpdate(currentState.copy(
                    sparkAdvance = ((b4 shl 8) or b5).toShort() / 4.0
                ).markFresh("sparkAdvance"))
                // Fuel trims via Ford fast-path 22F4xx: A * 100/128 - 100 (%)
                0xF406 -> onObdUpdate(currentState.copy(shortFuelTrim = b4 * 100.0 / 128.0 - 100.0).markFresh("shortFuelTrim"))
                0xF407 -> onObdUpdate(currentState.copy(longFuelTrim  = b4 * 100.0 / 128.0 - 100.0).markFresh("longFuelTrim"))
                else -> {
                    val decoded = PidRegistry.decode(ObdConstants.PCM_RESPONSE_ID, did, b4, b5OrNull)
                    if (decoded != null) {
                        val (field, value) = decoded
                        onObdUpdate(currentState.putGeneric(field, value))
                    }
                }
            }
        } catch (_: ShortReply) {
            logMalformed("PCM", "DID 0x%04X: $avail data byte(s), its value needs more".format(did))
        }
    }

    /**
     * HVAC module (CAN ID 0x73B).
     * DIDs are candidate values — will be confirmed via DID prober.
     * Uses [PidRegistry] fallback for any formula-equipped PIDs in the catalog.
     */
    fun parseHvacResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (data.size < 5) { logShort("HVAC", data); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val avail = singleFrameDataBytes(data) ?: run { logMalformed("HVAC", "PCI and frame length disagree"); return }
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        val b5OrNull = if (avail >= 2) data[5].toInt() and 0xFF else null
        val decoded = PidRegistry.decode(ObdConstants.HVAC_RESPONSE_ID, did, b4, b5OrNull)
        if (decoded != null) {
            val (field, value) = decoded
            onObdUpdate(currentState.putGeneric(field, value))
        }
    }

    /**
     * IPC / Instrument Panel Cluster (CAN ID 0x728).
     * DIDs are candidate values — will be confirmed via DID prober.
     * Uses [PidRegistry] fallback for any formula-equipped PIDs in the catalog.
     */
    fun parseIpcResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (data.size < 5) { logShort("IPC", data); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val avail = singleFrameDataBytes(data) ?: run { logMalformed("IPC", "PCI and frame length disagree"); return }
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        val b5OrNull = if (avail >= 2) data[5].toInt() and 0xFF else null
        val decoded = PidRegistry.decode(ObdConstants.IPC_RESPONSE_ID, did, b4, b5OrNull)
        if (decoded != null) {
            val (field, value) = decoded
            onObdUpdate(currentState.putGeneric(field, value))
        }
    }

    /** PSCM (CAN ID 0x738) — Pull Drift Compensation (DID 0xFD07). */
    fun parsePscmResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (data.size < 5) { logShort("PSCM", data); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        singleFrameDataBytes(data) ?: run { logMalformed("PSCM", "PCI and frame length disagree"); return }
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) {
            0xFD07 -> onObdUpdate(currentState.copy(pdcEnabled = b4 == 0x01).markFresh("pdcEnabled"))
        }
    }

    /** FENG module (CAN ID 0x72F) — Fake Engine Noise Generator (DID 0xEE03). */
    fun parseFengResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (data.size < 5) { logShort("FENG", data); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        singleFrameDataBytes(data) ?: run { logMalformed("FENG", "PCI and frame length disagree"); return }
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) {
            0xEE03 -> onObdUpdate(currentState.copy(fengEnabled = b4 == 0x01).markFresh("fengEnabled"))
        }
    }

    /**
     * RSProt (CAN ID 0x739) — LC/ASS probe responses (unconfirmed DIDs).
     *
     * @param onDebug optional callback for DID discovery logging.
     */
    fun parseRsprotResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit,
        onDebug: ((String) -> Unit)? = null
    ) {
        if (data.size < 5) { logShort("RSProt", data); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val avail = singleFrameDataBytes(data) ?: run { logMalformed("RSProt", "PCI and frame length disagree"); return }
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        val b5OrNull = if (avail >= 2) data[5].toInt() and 0xFF else null
        val b5 by lazy(LazyThreadSafetyMode.NONE) { b5OrNull ?: throw ShortReply() }

        // The debug line reads B5 only if the reply carries it: reading the lazy [b5] here, outside the ShortReply guard,
        // threw for every one-byte reply (a valid DE00 / DE02, a truncated DE01) and ended the connection (#NEW_R)
        onDebug?.invoke("RSProt 0x%04X → B4=0x%02X B5=%s".format(did, b4, b5OrNull?.let { "0x%02X".format(it) } ?: "--"))

        try {
            when (did) {
                0xDE00 -> onObdUpdate(currentState.copy(lcArmed = b4 == 0x01).markFresh("lcArmed"))
                0xDE01 -> onObdUpdate(currentState.copy(lcRpmTarget = (b4 shl 8) or b5).markFresh("lcRpmTarget"))
                0xDE02 -> onObdUpdate(currentState.copy(assEnabled = b4 == 0x01).markFresh("assEnabled"))
                0xEE01 -> onDebug?.invoke("RSProt 0xEE01 → $b4 (candidate)")
                0xFD01 -> onDebug?.invoke("RSProt 0xFD01 → $b4 (candidate)")
            }
        } catch (_: ShortReply) {
            logMalformed("RSProt", "DID 0x%04X: $avail data byte(s), its value needs more".format(did))
        }
    }
}
