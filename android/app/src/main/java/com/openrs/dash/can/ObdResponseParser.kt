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

    private fun logMalformed(module: String, data: ByteArray, reason: String) {
        Log.w(TAG, "$module: $reason [${data.joinToString(" ") { "%02X".format(it) }}]")
    }

    fun parseBcmResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (data.size < 5) { logMalformed("BCM", data, "too short (${data.size} < 5)"); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) {
            0xDD01 -> {
                if (data.size < 7) return
                val km = ((b4 shl 16) or
                         ((data[5].toInt() and 0xFF) shl 8) or
                          (data[6].toInt() and 0xFF)).toLong()
                onObdUpdate(currentState.copy(odometerKm = km))
            }
            0x4028 -> onObdUpdate(currentState.copy(batterySoc = b4.toDouble()))
            0x4029 -> onObdUpdate(currentState.copy(batteryTempC = (b4 - 40).toDouble()))
            0xDD04 -> onObdUpdate(currentState.copy(cabinTempC = (b4 * 10.0 / 9.0) - 45.0))
            // TPMS: PSI = (((256*A)+B) / 3.0 + 22.0/3.0) * 0.145
            0x2813, 0x2814, 0x2816, 0x2815 -> {
                if (data.size < 6) return
                val b5 = data[5].toInt() and 0xFF
                val psi = ((b4 * 256.0 + b5) / 3.0 + 22.0 / 3.0) * 0.145
                if (psi < 5.0 || psi > 70.0) return
                onObdUpdate(when (did) {
                    0x2813 -> currentState.copy(tirePressLF = psi)
                    0x2814 -> currentState.copy(tirePressRF = psi)
                    0x2816 -> currentState.copy(tirePressLR = psi)
                    else   -> currentState.copy(tirePressRR = psi)
                })
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
        if (data.size < 5) { logMalformed("AWD", data, "too short (${data.size} < 5)"); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) {
            0x1E8A -> onObdUpdate(currentState.copy(rduTempC = (b4 - 40).toDouble()))
            0xEE0B -> onObdUpdate(currentState.copy(rduEnabled = b4 == 0x01))
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
        if (data.size < 5) { logMalformed("PCM", data, "too short (${data.size} < 5)"); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        val b4s = data[4].toInt()
        val b5  = if (data.size > 5) data[5].toInt() and 0xFF else 0
        when (did) {
            0x093C -> onObdUpdate(currentState.copy(
                etcAngleActual = ((b4 shl 8) or b5) * (100.0 / 8192.0)
            ))
            0x091A -> onObdUpdate(currentState.copy(
                etcAngleDesired = ((b4 shl 8) or b5) * (100.0 / 8192.0)
            ))
            0x0462 -> onObdUpdate(currentState.copy(
                wgdcDesired = b4 * 100.0 / 128.0
            ))
            0x03EC -> onObdUpdate(currentState.copy(
                ignCorrCyl1 = ((b4s.toByte().toInt() shl 8) or b5) / -512.0
            ))
            0x03E8 -> onObdUpdate(currentState.copy(
                octaneAdjustRatio = ((b4s.toByte().toInt() shl 8) or b5) / 16384.0
            ))
            0x0461 -> onObdUpdate(currentState.copy(
                chargeAirTempC = ((b4s.toByte().toInt() shl 8) or b5) / 64.0
            ))
            0xF43C -> onObdUpdate(currentState.copy(
                catalyticTempC = ((b4 shl 8) or b5) / 10.0 - 40.0
            ))
            0xF434 -> {
                val afr = ((b4 shl 8) or b5) * 0.0004486
                onObdUpdate(currentState.copy(afrActual = afr, lambdaActual = afr / 14.7))
            }
            0xF444 -> onObdUpdate(currentState.copy(afrDesired = b4 * 0.1144))
            0x033E -> onObdUpdate(currentState.copy(tipActualKpa  = ((b4 shl 8) or b5) / 903.81))
            0x0466 -> onObdUpdate(currentState.copy(tipDesiredKpa = ((b4 shl 8) or b5) / 903.81))
            0x0318 -> onObdUpdate(currentState.copy(
                vctIntakeAngle = ((b4s.toByte().toInt() shl 8) or b5) / 16.0
            ))
            0x0319 -> onObdUpdate(currentState.copy(
                vctExhaustAngle = ((b4s.toByte().toInt() shl 8) or b5) / 16.0
            ))
            0x054B -> onObdUpdate(currentState.copy(oilLifePct = b4.toDouble().coerceIn(0.0, 100.0)))
            0xF422 -> onObdUpdate(currentState.copy(
                hpFuelRailPsi = ((b4 shl 8) or b5) * 1.45038
            ))
            0xF42F -> onObdUpdate(currentState.copy(
                fuelLevelPct = (b4 * 100.0 / 255.0).coerceIn(0.0, 100.0)
            ))
            0x0304 -> onObdUpdate(currentState.copy(
                batteryVoltage = ((b4 shl 8) or b5) / 2048.0
            ))
        }
    }

    /** PSCM (CAN ID 0x738) — Pull Drift Compensation (DID 0xFD07). */
    fun parsePscmResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (data.size < 5) { logMalformed("PSCM", data, "too short (${data.size} < 5)"); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) {
            0xFD07 -> onObdUpdate(currentState.copy(pdcEnabled = b4 == 0x01))
        }
    }

    /** FENG module (CAN ID 0x72F) — Fake Engine Noise Generator (DID 0xEE03). */
    fun parseFengResponse(
        data: ByteArray,
        currentState: VehicleState,
        onObdUpdate: (VehicleState) -> Unit
    ) {
        if (data.size < 5) { logMalformed("FENG", data, "too short (${data.size} < 5)"); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        when (did) {
            0xEE03 -> onObdUpdate(currentState.copy(fengEnabled = b4 == 0x01))
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
        if (data.size < 5) { logMalformed("RSProt", data, "too short (${data.size} < 5)"); return }
        if ((data[1].toInt() and 0xFF) != 0x62) return
        val did = ((data[2].toInt() and 0xFF) shl 8) or (data[3].toInt() and 0xFF)
        val b4  = data[4].toInt() and 0xFF
        val b5  = if (data.size > 5) data[5].toInt() and 0xFF else 0

        onDebug?.invoke("RSProt 0x%04X → B4=0x%02X B5=0x%02X".format(did, b4, b5))

        when (did) {
            0xDE00 -> onObdUpdate(currentState.copy(lcArmed = b4 == 0x01))
            0xDE01 -> onObdUpdate(currentState.copy(lcRpmTarget = (b4 shl 8) or b5))
            0xDE02 -> onObdUpdate(currentState.copy(assEnabled = b4 == 0x01))
            0xEE01 -> onDebug?.invoke("RSProt 0xEE01 → $b4 (candidate)")
            0xFD01 -> onDebug?.invoke("RSProt 0xFD01 → $b4 (candidate)")
        }
    }
}
