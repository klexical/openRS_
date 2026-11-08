package com.openrs.dash.data

import com.openrs.dash.can.CanDecoder
/**
 * Merge of a polled (Mode 22) decode into the live VehicleState — the update [com.openrs.dash.service.CanDataService]
 * applies for every OBD reply (moved here so the production merge is unit-tested, #205). Fields a reply didn't carry
 * keep the current value through their "not received" sentinel; freshness stamps merge per key. Fields whose valid range
 * includes their default (AWD REQ L / R, demanded pressure, pump current: a decoded 0 is a reading) are taken only when
 * the reply stamped them — see [carried]. Battery voltage (PCM 0x0304) too (#223): its value and its stamp always come
 * from the same reply — a decoded 0 is taken with its own stamp (never re-stamps the old value), and a reply parsed
 * from an older snapshot never puts an older voltage back. Generic values (catalog DIDs, MAP 0xF40B) the same way (#271):
 * only the keys this reply stamped are taken, so a reply's snapshot can't bring older generic values back.
 */
object ObdMerge {
    /** Prefix of every drive-mode stamp key — all written by CanDecoder.stampFresh only (#252); OBD never stamps one. */
    private const val DRIVE_MODE_KEYS = "driveMode"

    /**
     * The reply is a copy of the live-state snapshot with the decoded field stamped, so it carried [tag] when its stamp is
     * at least as new as the current one. An unrelated reply holds the snapshot's older stamp (or none) and leaves the
     * field alone — also when another reply for [tag] was merged after the snapshot. (Equal stamps would need two replies
     * for one DID in the same millisecond; the AWD poller asks one DID at a time.)
     */
    private fun carried(tag: String, current: VehicleState, obd: VehicleState): Boolean {
        val incoming = obd.fieldLastUpdateMs[tag] ?: return false
        val held = current.fieldLastUpdateMs[tag] ?: return true
        return incoming >= held
    }

    fun merge(current: VehicleState, obd: VehicleState): VehicleState =
        current.copy(
            calcLoad = obd.calcLoad,
            shortFuelTrim = obd.shortFuelTrim,
            longFuelTrim = obd.longFuelTrim,
            timingAdvance = obd.timingAdvance,
            fuelRailPressure = obd.fuelRailPressure,
            barometricPressure = obd.barometricPressure,
            commandedAfr = obd.commandedAfr,
            o2Voltage = obd.o2Voltage,
            afrSensor1 = obd.afrSensor1,
            chargeAirTempC = obd.chargeAirTempC,
            manifoldChargeTempC = obd.manifoldChargeTempC,
            octaneAdjustRatio = obd.octaneAdjustRatio,
            catalyticTempC = obd.catalyticTempC,
            afrActual = obd.afrActual,
            afrDesired = obd.afrDesired,
            lambdaActual = obd.lambdaActual,
            etcAngleActual = obd.etcAngleActual,
            etcAngleDesired = obd.etcAngleDesired,
            tipActualKpa = obd.tipActualKpa,
            tipDesiredKpa = obd.tipDesiredKpa,
            wgdcDesired = obd.wgdcDesired,
            vctIntakeAngle = obd.vctIntakeAngle,
            vctExhaustAngle = obd.vctExhaustAngle,
            oilLifePct = obd.oilLifePct,
            ignCorrCyl1 = obd.ignCorrCyl1,
            ignCorrCyl2 = obd.ignCorrCyl2,
            ignCorrCyl3 = obd.ignCorrCyl3,
            ignCorrCyl4 = obd.ignCorrCyl4,
            tirePressLF = if (obd.tirePressLF >= 0) obd.tirePressLF else current.tirePressLF,
            tirePressRF = if (obd.tirePressRF >= 0) obd.tirePressRF else current.tirePressRF,
            tirePressLR = if (obd.tirePressLR >= 0) obd.tirePressLR else current.tirePressLR,
            tirePressRR = if (obd.tirePressRR >= 0) obd.tirePressRR else current.tirePressRR,
            // The TPMS sample time (#232): a newly decoded reply refreshes it even for an equal pressure; an unrelated or
            // older reply carries a time ≤ the live one and can't move it back
            tpmsLastUpdate = maxOf(current.tpmsLastUpdate, obd.tpmsLastUpdate),
            tireTempLF = if (obd.tireTempLF > -90) obd.tireTempLF else current.tireTempLF,
            tireTempRF = if (obd.tireTempRF > -90) obd.tireTempRF else current.tireTempRF,
            tireTempLR = if (obd.tireTempLR > -90) obd.tireTempLR else current.tireTempLR,
            tireTempRR = if (obd.tireTempRR > -90) obd.tireTempRR else current.tireTempRR,
            tpmsSensorIdLF = if (obd.tpmsSensorIdLF >= 0) obd.tpmsSensorIdLF else current.tpmsSensorIdLF,
            tpmsSensorIdRF = if (obd.tpmsSensorIdRF >= 0) obd.tpmsSensorIdRF else current.tpmsSensorIdRF,
            tpmsSensorIdRR = if (obd.tpmsSensorIdRR >= 0) obd.tpmsSensorIdRR else current.tpmsSensorIdRR,
            tpmsSensorIdLR = if (obd.tpmsSensorIdLR >= 0) obd.tpmsSensorIdLR else current.tpmsSensorIdLR,
            odometerKm   = if (obd.odometerKm  >= 0)   obd.odometerKm   else current.odometerKm,
            batterySoc   = if (obd.batterySoc  >= 0)   obd.batterySoc   else current.batterySoc,
            batteryTempC = if (obd.batteryTempC > -90) obd.batteryTempC else current.batteryTempC,
            cabinTempC   = if (obd.cabinTempC  > -90)  obd.cabinTempC   else current.cabinTempC,
            batteryChargingVoltageDesired = if (obd.batteryChargingVoltageDesired >= 0) obd.batteryChargingVoltageDesired else current.batteryChargingVoltageDesired,
            batteryCurrentA = if (obd.batteryCurrentA > -900) obd.batteryCurrentA else current.batteryCurrentA,
            // Negative advance is valid (#205): only the −99 "not received" sentinel keeps the current value
            sparkAdvance = if (obd.sparkAdvance > -90) obd.sparkAdvance else current.sparkAdvance,
            rduTempC     = if (obd.rduTempC > -90)     obd.rduTempC     else current.rduTempC,
            awdClutchTempL = if (obd.awdClutchTempL > -90) obd.awdClutchTempL else current.awdClutchTempL,
            awdClutchTempR = if (obd.awdClutchTempR > -90) obd.awdClutchTempR else current.awdClutchTempR,
            // A decoded 0 is a reading (#226: 0 Nm, a zero demand, a pump at 0 A) — taken by stamp, never by value
            awdReqTorqueL  = if (carried("awdReqTorqueL", current, obd)) obd.awdReqTorqueL else current.awdReqTorqueL,
            awdReqTorqueR  = if (carried("awdReqTorqueR", current, obd)) obd.awdReqTorqueR else current.awdReqTorqueR,
            awdDmdPressure = if (carried("awdDmdPressure", current, obd)) obd.awdDmdPressure else current.awdDmdPressure,
            awdPumpCurrent = if (carried("awdPumpCurrent", current, obd)) obd.awdPumpCurrent else current.awdPumpCurrent,
            awdClutchCurL  = if (obd.awdClutchCurL > -900) obd.awdClutchCurL else current.awdClutchCurL,
            awdClutchCurR  = if (obd.awdClutchCurR > -900) obd.awdClutchCurR else current.awdClutchCurR,
            awdClutchPressureL = if (obd.awdClutchPressureL >= 0) obd.awdClutchPressureL else current.awdClutchPressureL,
            awdClutchPressureR = if (obd.awdClutchPressureR >= 0) obd.awdClutchPressureR else current.awdClutchPressureR,
            transOilTempC  = if (obd.transOilTempC > -90) obd.transOilTempC else current.transOilTempC,
            hpFuelRailPsi = if (obd.hpFuelRailPsi >= 0) obd.hpFuelRailPsi else current.hpFuelRailPsi,
            batteryVoltage = if (carried("batteryVoltage", current, obd)) obd.batteryVoltage else current.batteryVoltage,
            rduEnabled   = obd.rduEnabled   ?: current.rduEnabled,
            pdcEnabled   = obd.pdcEnabled   ?: current.pdcEnabled,
            fengEnabled  = obd.fengEnabled  ?: current.fengEnabled,
            fengTimedOut = obd.fengTimedOut || current.fengTimedOut,
            lcArmed      = obd.lcArmed      ?: current.lcArmed,
            lcRpmTarget  = if (obd.lcRpmTarget >= 0) obd.lcRpmTarget else current.lcRpmTarget,
            assEnabled   = obd.assEnabled   ?: current.assEnabled,
            rsprotTimedOut = obd.rsprotTimedOut || current.rsprotTimedOut,
            // Only the keys this reply stamped (putGeneric stamps its own key): the rest of the reply's map is its snapshot's
            // copy, which can be older than the live one (#271)
            genericValues = if (obd.genericValues.isNotEmpty())
                current.genericValues + obd.genericValues.filterKeys { carried(it, current, obd) }
            else current.genericValues,
            warnMil         = obd.warnMil         ?: current.warnMil,
            warnAbs         = obd.warnAbs         ?: current.warnAbs,
            warnBrake       = obd.warnBrake       ?: current.warnBrake,
            warnCharge      = obd.warnCharge      ?: current.warnCharge,
            warnOilPressure = obd.warnOilPressure ?: current.warnOilPressure,
            warnTempHigh    = obd.warnTempHigh    ?: current.warnTempHigh,
            // Freshness stamps from markFresh()/putGeneric() — previously dropped here,
            // which left every OBD field without a stamp (UI read them as STALE).
            // Drive-mode and ESC-frame provenance keys are CAN-only and partly signalled by ABSENCE (#252, #125): an OBD reply carries the
            // snapshot it was parsed against, so they are never taken from it — the live state's set is kept exactly
            fieldLastUpdateMs = VehicleState.mergeStamps(current.fieldLastUpdateMs,
                obd.fieldLastUpdateMs.filterKeys { !it.startsWith(DRIVE_MODE_KEYS) && !it.startsWith(CanDecoder.ESC_FRAME_PREFIX) }),
        )
}
