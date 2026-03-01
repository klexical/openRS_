package com.openrs.dash.auto.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Tune screen — OBD Mode 1/22 diagnostic data.
 * Fuel trims, timing advance, octane adjust, AFR, fuel rail, barometric.
 */
class TuneScreen(carContext: CarContext) : Screen(carContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var last = VehicleState()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                scope.launch {
                    OpenOpenRSDashApp.instance.vehicleState.collectLatest { s ->
                        if (changed(last, s)) { last = s; invalidate() }
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) { scope.cancel() }
        })
    }

    override fun onGetTemplate(): Template {
        val v = last
        val tF = { c: Double -> (c * 9.0 / 5.0 + 32).roundToInt() }

        val row1 = Row.Builder()
            .setTitle("AFR: ${"%.2f".format(v.afrActual)}→${"%.1f".format(v.afrDesired)}  │  λ: ${"%.3f".format(v.lambdaActual)}  │  STFT/LTFT: ${"%.1f".format(v.shortFuelTrim)}/${"%.1f".format(v.longFuelTrim)}")
            .build()
        val row2 = Row.Builder()
            .setTitle("ETC: ${"%.1f".format(v.etcAngleActual)}→${"%.1f".format(v.etcAngleDesired)}°  │  TIP: ${"%.1f".format(v.tipActualPsi)}→${"%.1f".format(v.tipDesiredPsi)} PSI  │  WGDC: ${"%.0f".format(v.wgdcDesired)}%")
            .build()
        val row3 = Row.Builder()
            .setTitle("TIMING: ${"%.1f".format(v.timingAdvance)}°  │  KR: ${"%.2f".format(v.ignCorrCyl1)}°  │  OAR: ${"%.0f".format(v.octaneAdjustRatio * 100)}%  │  LOAD: ${"%.0f".format(v.calcLoad)}%")
            .build()
        val row4 = Row.Builder()
            .setTitle("VCT I/E: ${"%.1f".format(v.vctIntakeAngle)}/${"%.1f".format(v.vctExhaustAngle)}°  │  CHG: ${tF(v.chargeAirTempC)}°  │  CAT: ${tF(v.catalyticTempC)}°  │  RAIL: ${"%.0f".format(v.fuelRailPsi)} PSI")
            .build()

        return PaneTemplate.Builder(Pane.Builder().addRow(row1).addRow(row2).addRow(row3).addRow(row4).build())
            .setTitle("TUNE — OBD DIAGNOSTICS")
            .setHeaderAction(Action.BACK).build()
    }

    private fun changed(o: VehicleState, n: VehicleState): Boolean {
        if (abs(o.calcLoad - n.calcLoad) > 1) return true
        if (abs(o.timingAdvance - n.timingAdvance) > 0.5) return true
        if (abs(o.shortFuelTrim - n.shortFuelTrim) > 0.5) return true
        if (abs(o.afrActual - n.afrActual) > 0.1) return true
        if (abs(o.etcAngleActual - n.etcAngleActual) > 0.5) return true
        if (abs(o.tipActualKpa - n.tipActualKpa) > 1) return true
        if (abs(o.wgdcDesired - n.wgdcDesired) > 1) return true
        if (abs(o.ignCorrCyl1 - n.ignCorrCyl1) > 0.1) return true
        if (abs(o.vctIntakeAngle - n.vctIntakeAngle) > 0.5) return true
        return false
    }
}
