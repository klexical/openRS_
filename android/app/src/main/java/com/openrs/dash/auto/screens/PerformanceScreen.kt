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
 * Performance screen — matches phone PerfPage.
 * LAT G, LON G, COMBINED, STEER, YAW, SPEED; PEAK LAT/LON/BOOST/RPM; THR, BRK. RESET PEAKS action.
 */
class PerformanceScreen(carContext: CarContext) : Screen(carContext) {
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

    override fun onGetTemplate(): Template {
        val v = last
        val pane = Pane.Builder()
            .addRow(Row.Builder()
                .setTitle("LAT G  ${"%.2f".format(v.lateralG)}  │  LON G  ${"%.2f".format(v.longitudinalG)}  │  COMBINED  ${"%.2f".format(v.combinedG)} g")
                .build())
            .addRow(Row.Builder()
                .setTitle("STEER  ${"%.1f".format(v.steeringAngle)}°  │  YAW  ${"%.1f".format(v.yawRate)}°/s  │  SPEED  ${v.speedMph.roundToInt()} MPH")
                .build())
            .addRow(Row.Builder()
                .setTitle("PEAK  LAT ${"%.2f".format(v.peakLateralG)}  LON ${"%.2f".format(v.peakLongitudinalG)}  BOOST ${"%.1f".format(v.peakBoostPsi)} PSI  RPM ${v.peakRpm.roundToInt()}")
                .build())
            .addRow(Row.Builder()
                .setTitle("THR ${v.throttlePct.roundToInt()}%  │  BRK ${"%.1f".format(v.brakePressure)} bar  │  ${"%.1f".format(v.boostPsi)} PSI  ${v.rpm.roundToInt()} RPM  ${v.gearDisplay}")
                .build())

        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("RESET PEAKS")
                .setOnClickListener {
                    OpenRSDashApp.instance.vehicleState.value =
                        OpenRSDashApp.instance.vehicleState.value.withPeaksReset()
                    invalidate()
                }.build())
            .build()

        return PaneTemplate.Builder(pane.build())
            .setTitle("PERF")
            .setHeaderAction(Action.BACK)
            .setActionStrip(strip)
            .build()
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
