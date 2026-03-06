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
 * AWD screen — matches phone AwdPage.
 * Front axle FL/split/FR, PTU temp, torque bar L/R, rear axle RL/RDU/RR, F/R and L/R deltas.
 */
class AwdDetailScreen(carContext: CarContext) : Screen(carContext) {
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
    private fun avgF(v: VehicleState) = (v.wheelSpeedFL + v.wheelSpeedFR) / 2.0
    private fun avgR(v: VehicleState) = (v.wheelSpeedRL + v.wheelSpeedRR) / 2.0

    override fun onGetTemplate(): Template {
        val v = last
        val pane = Pane.Builder()
            .addRow(Row.Builder()
                .setTitle("FRONT AXLE")
                .addText("FL ${"%.1f".format(v.wheelSpeedFL)}  │  ${v.frontRearSplit}  │  FR ${"%.1f".format(v.wheelSpeedFR)}")
                .build())
            .addRow(Row.Builder()
                .setTitle("PTU  ${tF(v.ptuTempC)}°F  │  L ${v.awdLeftTorque.roundToInt()} Nm  —  ${v.awdRightTorque.roundToInt()} Nm R  (bias ${v.rearLeftRightBias})")
                .build())
            .addRow(Row.Builder()
                .setTitle("REAR AXLE")
                .addText("RL ${"%.1f".format(v.wheelSpeedRL)}  │  RDU ${tF(v.rduTempC)}°F  Max ${v.awdMaxTorque.roundToInt()} Nm  │  RR ${"%.1f".format(v.wheelSpeedRR)}")
                .build())
            .addRow(Row.Builder()
                .setTitle("F/R Δ ${"%.1f".format(avgR(v) - avgF(v))}  │  L/R Δ ${"%.1f".format(v.wheelSpeedRR - v.wheelSpeedRL)}  │  REAR BIAS ${v.rearLeftRightBias}")
                .build())

        return PaneTemplate.Builder(pane.build())
            .setTitle("AWD — GKN Twinster")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun changed(o: VehicleState, n: VehicleState): Boolean {
        if (abs(o.awdLeftTorque - n.awdLeftTorque) > 3) return true
        if (abs(o.awdRightTorque - n.awdRightTorque) > 3) return true
        if (abs(o.wheelSpeedFL - n.wheelSpeedFL) > 0.5) return true
        if (abs(o.rduTempC - n.rduTempC) > 1) return true
        return false
    }
}
