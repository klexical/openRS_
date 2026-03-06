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
 * Tune screen — matches phone TunePage.
 * AFR ACT/DES, LAMBDA; ETC, TIP, WGDC; LOAD, TIMING, OAR; STFT/LTFT, FUEL RAIL; VCT, CHG AIR; CAT, OIL LIFE, BARO.
 */
class TuneScreen(carContext: CarContext) : Screen(carContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null
    private var last = VehicleState()

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onStart(owner: LifecycleOwner) {
                collectJob = scope.launch {
                    OpenRSDashApp.instance.vehicleState.collectLatest { s ->
                        if (changed(last, s)) { last = s; invalidate() }
                    }
                }
            }
            override fun onStop(owner: LifecycleOwner) { collectJob?.cancel() }
        })
    }

    private fun tF(c: Double): Int = (c * 9.0 / 5.0 + 32).roundToInt()

    override fun onGetTemplate(): Template {
        val v = last
        val pane = Pane.Builder()
            .addRow(Row.Builder()
                .setTitle("AFR ACT  ${"%.2f".format(v.afrActual)}  DES  ${"%.1f".format(v.afrDesired)}  │  λ  ${"%.3f".format(v.lambdaActual)}")
                .build())
            .addRow(Row.Builder()
                .setTitle("ETC  ${"%.1f".format(v.etcAngleActual)}→${"%.1f".format(v.etcAngleDesired)}°  │  TIP  ${"%.1f".format(v.tipActualPsi)}→${"%.1f".format(v.tipDesiredPsi)} PSI  │  WGDC  ${"%.0f".format(v.wgdcDesired)}%")
                .build())
            .addRow(Row.Builder()
                .setTitle("LOAD  ${"%.0f".format(v.calcLoad)}%  │  TIMING  ${"%.1f".format(v.timingAdvance)}°  │  OAR  ${"%.0f".format(v.octaneAdjustRatio * 100)}%")
                .build())
            .addRow(Row.Builder()
                .setTitle("STFT ${"%.1f".format(v.shortFuelTrim)}%  LTFT ${"%.1f".format(v.longFuelTrim)}%  │  RAIL ${"%.0f".format(v.fuelRailPsi)} PSI")
                .addText("VCT ${"%.1f".format(v.vctIntakeAngle)}/${"%.1f".format(v.vctExhaustAngle)}°  CHG ${tF(v.chargeAirTempC)}°  CAT ${tF(v.catalyticTempC)}°  BARO ${v.barometricPressure.roundToInt()} kPa  OIL ${if (v.oilLifePct >= 0) "${v.oilLifePct.roundToInt()}%" else "--"}")
                .build())

        return PaneTemplate.Builder(pane.build())
            .setTitle("TUNE — OBD")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun changed(o: VehicleState, n: VehicleState): Boolean {
        if (abs(o.calcLoad - n.calcLoad) > 1) return true
        if (abs(o.timingAdvance - n.timingAdvance) > 0.5) return true
        if (abs(o.shortFuelTrim - n.shortFuelTrim) > 0.5) return true
        if (abs(o.afrActual - n.afrActual) > 0.1) return true
        if (abs(o.etcAngleActual - n.etcAngleActual) > 0.5) return true
        if (abs(o.tipActualKpa - n.tipActualKpa) > 1) return true
        if (abs(o.wgdcDesired - n.wgdcDesired) > 1) return true
        return false
    }
}
