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

class AwdDetailScreen(carContext: CarContext) : Screen(carContext) {
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
        val avgF = (v.wheelSpeedFL + v.wheelSpeedFR) / 2.0
        val avgR = (v.wheelSpeedRL + v.wheelSpeedRR) / 2.0

        val row1 = Row.Builder()
            .setTitle("SPLIT: ${v.frontRearSplit}  │  REAR: ${v.totalRearTorque.roundToInt()} Nm  │  MAX: ${v.awdMaxTorque.roundToInt()} Nm")
            .build()
        val row2 = Row.Builder()
            .setTitle("LEFT: ${v.awdLeftTorque.roundToInt()} Nm  │  RIGHT: ${v.awdRightTorque.roundToInt()} Nm  │  BIAS: ${v.rearLeftRightBias}")
            .build()
        val row3 = Row.Builder()
            .setTitle("FL ${"%.1f".format(v.wheelSpeedFL)}  FR ${"%.1f".format(v.wheelSpeedFR)}  │  RL ${"%.1f".format(v.wheelSpeedRL)}  RR ${"%.1f".format(v.wheelSpeedRR)} km/h")
            .build()
        val row4 = Row.Builder()
            .setTitle("RDU ${tF(v.rduTempC)}°F  PTU ${tF(v.ptuTempC)}°F  │  F/R Δ${"%.1f".format(avgR - avgF)}  L/R Δ${"%.1f".format(v.wheelSpeedRR - v.wheelSpeedRL)}")
            .build()

        return PaneTemplate.Builder(Pane.Builder().addRow(row1).addRow(row2).addRow(row3).addRow(row4).build())
            .setTitle("AWD — GKN Twinster")
            .setHeaderAction(Action.BACK).build()
    }

    private fun changed(o: VehicleState, n: VehicleState): Boolean {
        if (abs(o.awdLeftTorque - n.awdLeftTorque) > 3) return true
        if (abs(o.awdRightTorque - n.awdRightTorque) > 3) return true
        if (abs(o.wheelSpeedFL - n.wheelSpeedFL) > 0.5) return true
        if (abs(o.rduTempC - n.rduTempC) > 1) return true
        return false
    }
}
