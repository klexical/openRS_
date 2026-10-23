package com.openrs.dash.ui

import com.openrs.dash.data.VehicleState

/**
 * Race-ready verdict for the THERMAL banner and the drive recorder's RTR marker.
 *
 * Precedence: OVERHEAT > NO_DATA > WARMING > READY. Uses only existing rules:
 *  - critical limits = the active THERMAL preset ([UserPrefs] oil/cool/rdu/ptuCritC),
 *    the same values that turn those cards orange;
 *  - data validity = [tempAvailFor] (fresh decode within 60 s), the THERMAL cell rule;
 *  - warm-up minimums = [VehicleState.rtrStatus].
 */
enum class RtrState { READY, WARMING, OVERHEAT, NO_DATA }

data class RaceReadiness(val state: RtrState, val detail: String?) {
    val isReady: Boolean get() = state == RtrState.READY
}

/** Critical temperatures from the active THERMAL preset. */
data class RtrLimits(val oilCritC: Double, val coolCritC: Double, val rduCritC: Double, val ptuCritC: Double) {
    companion object {
        fun from(p: UserPrefs) = RtrLimits(p.oilCritC, p.coolCritC, p.rduCritC, p.ptuCritC)
    }
}

private class RtrSensor(val label: String, val valueC: Double, val tag: String, val critC: Double?, val required: Boolean)

/**
 * Oil and coolant are required — both are passive CAN broadcasts (0x0F8 / 0x2F0) on every car,
 * so missing or stale readings mean readiness can't be judged. RDU / PTU / clutch temps count
 * once the car has reported them (some modules never answer); after that, a stale reading
 * blocks readiness instead of silently passing.
 */
fun raceReadiness(vs: VehicleState, limits: RtrLimits, nowMs: Long = System.currentTimeMillis()): RaceReadiness {
    val sensors = listOf(
        RtrSensor("Oil",     vs.oilTempC,       "oilTempC",       limits.oilCritC,  required = true),
        RtrSensor("Coolant", vs.coolantTempC,   "coolantTempC",   limits.coolCritC, required = true),
        RtrSensor("RDU",     vs.rduTempC,       "rduTempC",       limits.rduCritC,  required = false),
        RtrSensor("PTU",     vs.ptuTempC,       "ptuTempC",       limits.ptuCritC,  required = false),
        RtrSensor("CLT-L",   vs.awdClutchTempL, "awdClutchTempL", null,             required = false),
        RtrSensor("CLT-R",   vs.awdClutchTempR, "awdClutchTempR", null,             required = false),
    )
    val avail = sensors.associateWith { tempAvailFor(it.valueC, vs.fieldLastUpdateMs[it.tag], now = nowMs) }

    val hot = sensors.filter { s ->
        avail[s] == TempAvail.AVAILABLE && s.critC != null && s.valueC >= s.critC
    }
    if (hot.isNotEmpty()) {
        return RaceReadiness(RtrState.OVERHEAT,
            hot.joinToString(" · ") { "${it.label} ${it.valueC.toInt()}°C ≥ ${it.critC!!.toInt()}°C" })
    }

    val gaps = sensors.mapNotNull { s ->
        when (avail[s]) {
            TempAvail.STALE -> "${s.label} stale"
            TempAvail.WARMING, TempAvail.UNAVAILABLE -> if (s.required) "${s.label} no data" else null
            else -> null
        }
    }
    if (gaps.isNotEmpty()) return RaceReadiness(RtrState.NO_DATA, gaps.joinToString(" · "))

    vs.rtrStatus?.let { return RaceReadiness(RtrState.WARMING, it) }
    return RaceReadiness(RtrState.READY, null)
}
