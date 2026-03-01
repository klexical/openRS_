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
import kotlin.math.sqrt

class PerformanceScreen(carContext: CarContext) : Screen(carContext) {
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

        val row1 = Row.Builder()
            .setTitle("LAT: ${"%.2f".format(v.lateralG)}g  │  LON: ${"%.2f".format(v.longitudinalG)}g  │  COMBINED: ${"%.2f".format(v.combinedG)}g")
            .build()
        val row2 = Row.Builder()
            .setTitle("STEER: ${"%.1f".format(v.steeringAngle)}°  │  YAW: ${"%.1f".format(v.yawRate)}°/s  │  ${v.speedMph.roundToInt()} MPH")
            .build()
        val row3 = Row.Builder()
            .setTitle("PEAK: ${"%.1f".format(v.peakBoostPsi)} PSI  ${v.peakRpm.roundToInt()} RPM  ${"%.2f".format(v.peakLateralG)}g lat  ${"%.2f".format(v.peakLongitudinalG)}g lon")
            .build()
        val row4 = Row.Builder()
            .setTitle("THR ${v.throttlePct.roundToInt()}%  BRK ${"%.1f".format(v.brakePressure)}  │  ${"%.1f".format(v.boostPsi)} PSI  ${v.rpm.roundToInt()} RPM  ${v.gearDisplay}")
            .build()

        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("RESET")
                .setOnClickListener {
                    OpenOpenRSDashApp.instance.vehicleState.value =
                        OpenOpenRSDashApp.instance.vehicleState.value.withPeaksReset()
                    invalidate()
                }.build())
            .build()

        return PaneTemplate.Builder(Pane.Builder().addRow(row1).addRow(row2).addRow(row3).addRow(row4).build())
            .setTitle("PERFORMANCE")
            .setHeaderAction(Action.BACK)
            .setActionStrip(strip).build()
    }

    private fun changed(o: VehicleState, n: VehicleState): Boolean {
        if (abs(o.lateralG - n.lateralG) > 0.02) return true
        if (abs(o.longitudinalG - n.longitudinalG) > 0.02) return true
        if (abs(o.steeringAngle - n.steeringAngle) > 1) return true
        if (abs(o.speedKph - n.speedKph) > 1) return true
        if (abs(o.throttlePct - n.throttlePct) > 2) return true
        return false
    }
}
