package com.openrs.dash.can

import com.openrs.dash.data.VehicleState

/**
 * OBD-II PID definitions for Focus RS MK3.
 *
 * Supports multiple ECU targets:
 *   - PCM (0x7E0/0x7E8): Engine parameters — Mode 1 + Mode 22
 *   - BCM (0x726/0x72E): Body control — TPMS pressures + temps
 *
 * Response parsing follows SAE J1979 / Ford IPC specifications.
 * Formulas verified against DigiCluster v1.0.6 protocol JSONs.
 */
data class ObdPid(
    val name: String,
    val mode: Int,           // 1 = standard, 22 = enhanced
    val pid: Int,            // PID number (or 16-bit for mode 22)
    val requestStr: String,  // ELM327 command string to send
    val header: String,      // ECU header: "7E0" = PCM, "726" = BCM, "7DF" = broadcast
    val priority: Int,       // Lower = requested more often (1=every cycle, 3=every 3rd, 6=every 6th)
    val parse: (ByteArray, VehicleState) -> VehicleState
)

object ObdPids {

    // ECU headers
    const val HDR_BROADCAST = "7DF"  // Standard Mode 1 broadcast
    const val HDR_PCM = "7E0"        // Powertrain control module
    const val HDR_BCM = "726"        // Body control module (TPMS)

    /**
     * All OBD PIDs grouped by priority.
     *
     * Priority 1: Every cycle (~250ms) — fast-changing engine data
     * Priority 2: Every 2nd cycle (~500ms) — moderate
     * Priority 3: Every 3rd cycle (~750ms) — slow-changing temps
     * Priority 6: Every 6th cycle (~1.5s) — TPMS (slow-updating), oil life
     *
     * We request 2-3 PIDs per cycle within a ~100ms window between ATMA phases.
     * BCM (TPMS) queries require a header switch (ATSH726/ATSH7E0) so they
     * are batched together to minimize header switches.
     */
    val ALL = listOf(
        // ═══════════════════════════════════════════════════════════
        // MODE 1 — Standard OBD (broadcast header 7DF)
        // ═══════════════════════════════════════════════════════════

        ObdPid("Calc Load", 1, 0x04, "0104", HDR_BROADCAST, 1) { data, state ->
            if (data.isNotEmpty()) state.copy(calcLoad = data[0].ub() * 100.0 / 255.0)
            else state
        },
        ObdPid("Short Fuel Trim", 1, 0x06, "0106", HDR_BROADCAST, 1) { data, state ->
            if (data.isNotEmpty()) state.copy(shortFuelTrim = data[0].ub() / 1.28 - 100.0)
            else state
        },
        ObdPid("Timing Advance", 1, 0x0E, "010E", HDR_BROADCAST, 2) { data, state ->
            if (data.isNotEmpty()) state.copy(timingAdvance = data[0].ub() / 2.0 - 64.0)
            else state
        },
        ObdPid("Long Fuel Trim", 1, 0x07, "0107", HDR_BROADCAST, 2) { data, state ->
            if (data.isNotEmpty()) state.copy(longFuelTrim = data[0].ub() / 1.28 - 100.0)
            else state
        },
        ObdPid("Fuel Rail Pressure", 1, 0x22, "0122", HDR_BROADCAST, 2) { data, state ->
            if (data.size >= 2) state.copy(fuelRailPressure = (data[0].ub() * 256 + data[1].ub()) * 0.079)
            else state
        },
        ObdPid("Commanded AFR", 1, 0x44, "0144", HDR_BROADCAST, 2) { data, state ->
            if (data.size >= 2) state.copy(commandedAfr = (data[0].ub() * 256 + data[1].ub()) / 32768.0)
            else state
        },
        ObdPid("O2 Voltage", 1, 0x15, "0115", HDR_BROADCAST, 3) { data, state ->
            if (data.isNotEmpty()) state.copy(o2Voltage = data[0].ub() / 200.0)
            else state
        },
        ObdPid("Barometric", 1, 0x33, "0133", HDR_BROADCAST, 3) { data, state ->
            if (data.isNotEmpty()) state.copy(barometricPressure = data[0].ub().toDouble())
            else state
        },
        ObdPid("AFR Sensor 1", 1, 0x24, "0124", HDR_BROADCAST, 3) { data, state ->
            if (data.size >= 2) state.copy(afrSensor1 = (data[0].ub() * 256 + data[1].ub()) / 32768.0)
            else state
        },

        // ═══════════════════════════════════════════════════════════
        // MODE 22 — Ford Enhanced via PCM (header 7E0)
        // ═══════════════════════════════════════════════════════════

        // ── Charge / Manifold Temps ──
        ObdPid("Charge Air Temp", 22, 0x0461, "220461", HDR_PCM, 2) { data, state ->
            // DigiCluster: 16-bit signed BE, (signed(A)*256+B)/64 = °C
            if (data.size >= 2) {
                val raw = (data[0].toInt() * 256) + data[1].ub()
                state.copy(chargeAirTempC = raw / 64.0)
            } else state
        },
        ObdPid("Octane Adjust", 22, 0x0543, "220543", HDR_PCM, 3) { data, state ->
            if (data.isNotEmpty()) state.copy(octaneAdjustRatio = data[0].ub() / 255.0)
            else state
        },
        ObdPid("Catalytic Temp", 22, 0xF43C, "22F43C", HDR_PCM, 3) { data, state ->
            // DigiCluster: 16-bit BE, ((A*256)+B)/10 - 40 = °C
            if (data.size >= 2) state.copy(
                catalyticTempC = (data[0].ub() * 256 + data[1].ub()) / 10.0 - 40.0
            ) else state
        },

        // ── AFR Actual / Desired ──
        ObdPid("AFR Actual", 22, 0xF434, "22F434", HDR_PCM, 1) { data, state ->
            if (data.size >= 2) {
                val raw = data[0].ub() * 256 + data[1].ub()
                state.copy(
                    afrActual = raw * 0.0004486,
                    lambdaActual = raw / 32768.0
                )
            } else state
        },
        ObdPid("AFR Desired", 22, 0xF444, "22F444", HDR_PCM, 2) { data, state ->
            if (data.isNotEmpty()) state.copy(afrDesired = data[0].ub() * 0.1144)
            else state
        },

        // ── Electronic Throttle Control ──
        ObdPid("ETC Actual", 22, 0x093C, "22093C", HDR_PCM, 1) { data, state ->
            if (data.size >= 2) state.copy(
                etcAngleActual = (data[0].ub() * 256 + data[1].ub()) / 512.0
            ) else state
        },
        ObdPid("ETC Desired", 22, 0x091A, "22091A", HDR_PCM, 2) { data, state ->
            if (data.size >= 2) state.copy(
                etcAngleDesired = (data[0].ub() * 256 + data[1].ub()) / 512.0
            ) else state
        },

        // ── Throttle Inlet Pressure ──
        ObdPid("TIP Actual", 22, 0x033E, "22033E", HDR_PCM, 1) { data, state ->
            if (data.size >= 2) state.copy(
                tipActualKpa = (data[0].ub() * 256 + data[1].ub()) / 903.81
            ) else state
        },
        ObdPid("TIP Desired", 22, 0x0466, "220466", HDR_PCM, 2) { data, state ->
            if (data.size >= 2) state.copy(
                tipDesiredKpa = (data[0].ub() * 256 + data[1].ub()) / 903.81
            ) else state
        },

        // ── Wastegate Duty Cycle ──
        ObdPid("WGDC Desired", 22, 0x0462, "220462", HDR_PCM, 2) { data, state ->
            if (data.size >= 2) state.copy(
                wgdcDesired = (data[0].ub() * 256 + data[1].ub()) / 327.68
            ) else state
        },

        // ── Variable Cam Timing ──
        ObdPid("VCT-I Angle", 22, 0x0318, "220318", HDR_PCM, 3) { data, state ->
            if (data.size >= 2) {
                val raw = (data[0].toInt() * 256) + data[1].ub()
                state.copy(vctIntakeAngle = raw / 16.0)
            } else state
        },
        ObdPid("VCT-E Angle", 22, 0x0319, "220319", HDR_PCM, 3) { data, state ->
            if (data.size >= 2) {
                val raw = (data[0].toInt() * 256) + data[1].ub()
                state.copy(vctExhaustAngle = raw / 16.0)
            } else state
        },

        // ── Oil Life ──
        ObdPid("Oil Life", 22, 0x054B, "22054B", HDR_PCM, 6) { data, state ->
            if (data.isNotEmpty()) state.copy(oilLifePct = data[0].ub().toDouble())
            else state
        },

        // ── Ignition Correction Cyl 1 ──
        ObdPid("Ign Corr Cyl1", 22, 0x03EC, "2203EC", HDR_PCM, 2) { data, state ->
            if (data.size >= 2) {
                val raw = (data[0].toInt() * 256) + data[1].ub()
                state.copy(ignCorrCyl1 = raw / -512.0)
            } else state
        },

        // ═══════════════════════════════════════════════════════════
        // MODE 22 — TPMS via BCM (header 726)
        // Pressure: ((A*256)+B)/2.9 = kPa, * 0.145038 = PSI
        // Temperature: A - 40 = °C (experimental, verify on car)
        // ═══════════════════════════════════════════════════════════

        ObdPid("TPMS LF Press", 22, 0x2813, "222813", HDR_BCM, 6) { data, state ->
            if (data.size >= 2) state.copy(
                tirePressLF = (data[0].ub() * 256 + data[1].ub()) / 2.9 * 0.145038
            ) else state
        },
        ObdPid("TPMS RF Press", 22, 0x2814, "222814", HDR_BCM, 6) { data, state ->
            if (data.size >= 2) state.copy(
                tirePressRF = (data[0].ub() * 256 + data[1].ub()) / 2.9 * 0.145038
            ) else state
        },
        ObdPid("TPMS RR Press", 22, 0x2815, "222815", HDR_BCM, 6) { data, state ->
            if (data.size >= 2) state.copy(
                tirePressRR = (data[0].ub() * 256 + data[1].ub()) / 2.9 * 0.145038
            ) else state
        },
        ObdPid("TPMS LR Press", 22, 0x2816, "222816", HDR_BCM, 6) { data, state ->
            if (data.size >= 2) state.copy(
                tirePressLR = (data[0].ub() * 256 + data[1].ub()) / 2.9 * 0.145038
            ) else state
        },

        // ── TPMS Temps (EXPERIMENTAL — verify PIDs on car) ──
        ObdPid("TPMS LF Temp", 22, 0x2823, "222823", HDR_BCM, 6) { data, state ->
            if (data.isNotEmpty()) state.copy(tireTempLF = data[0].ub() - 40.0)
            else state
        },
        ObdPid("TPMS RF Temp", 22, 0x2824, "222824", HDR_BCM, 6) { data, state ->
            if (data.isNotEmpty()) state.copy(tireTempRF = data[0].ub() - 40.0)
            else state
        },
        ObdPid("TPMS RR Temp", 22, 0x2825, "222825", HDR_BCM, 6) { data, state ->
            if (data.isNotEmpty()) state.copy(tireTempRR = data[0].ub() - 40.0)
            else state
        },
        ObdPid("TPMS LR Temp", 22, 0x2826, "222826", HDR_BCM, 6) { data, state ->
            if (data.isNotEmpty()) state.copy(tireTempLR = data[0].ub() - 40.0)
            else state
        },
    )

    /**
     * Get PIDs for a given cycle, grouped by header for efficient batching.
     * Returns map of (header -> PIDs to query).
     */
    fun getPidsForCycleGrouped(cycle: Int): Map<String, List<ObdPid>> {
        return ALL.filter { cycle % it.priority == 0 }
            .groupBy { it.header }
    }

    /** Flat list (legacy compat) */
    fun getPidsForCycle(cycle: Int): List<ObdPid> {
        return ALL.filter { cycle % it.priority == 0 }
    }

    /** Unsigned byte helper */
    private fun Byte.ub(): Int = this.toInt() and 0xFF
}
