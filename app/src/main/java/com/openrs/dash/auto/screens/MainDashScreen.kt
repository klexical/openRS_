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

class MainDashScreen(carContext: CarContext) : Screen(carContext) {
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
            .setTitle("${v.rpm.roundToInt()} RPM  │  ${"%.1f".format(v.boostPsi)} PSI  │  ${v.speedMph.roundToInt()} MPH  │  ${v.gearDisplay}")
            .build()

        val row2 = Row.Builder()
            .setTitle("OIL ${tF(v.oilTempC)}°  CLT ${tF(v.coolantTempC)}°  IAT ${tF(v.intakeTempC)}°  │  RDU ${tF(v.rduTempC)}°  PTU ${tF(v.ptuTempC)}°")
            .build()

        val row3 = Row.Builder()
            .setTitle("AWD ${v.frontRearSplit}  L${v.awdLeftTorque.roundToInt()} R${v.awdRightTorque.roundToInt()}  │  THR ${v.throttlePct.roundToInt()}%  BRK ${"%.1f".format(v.brakePressure)}")
            .build()

        val row4 = Row.Builder()
            .setTitle("${v.driveMode.label}  ${v.escStatus.label}  │  LOAD ${"%.0f".format(v.calcLoad)}%  AFR ${"%.2f".format(v.commandedAfr)}  ADV ${"%.1f".format(v.timingAdvance)}°")
            .build()

        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("AWD")
                .setOnClickListener { screenManager.push(AwdDetailScreen(carContext)) }.build())
            .addAction(Action.Builder().setTitle("PERF")
                .setOnClickListener { screenManager.push(PerformanceScreen(carContext)) }.build())
            .addAction(Action.Builder().setTitle("TUNE")
                .setOnClickListener { screenManager.push(TuneScreen(carContext)) }.build())
            .addAction(Action.Builder().setTitle("TPMS")
                .setOnClickListener { screenManager.push(TpmsScreen(carContext)) }.build())
            .build()

        return PaneTemplate.Builder(Pane.Builder().addRow(row1).addRow(row2).addRow(row3).addRow(row4).build())
            .setTitle(if (v.isConnected) "RS DASH ● ${v.framesPerSecond.roundToInt()}fps" else "RS DASH ○ OFFLINE")
            .setActionStrip(strip).build()
    }

    private fun changed(o: VehicleState, n: VehicleState): Boolean {
        if (o.isConnected != n.isConnected) return true
        if (abs(o.rpm - n.rpm) > 50) return true
        if (abs(o.boostPsi - n.boostPsi) > 0.3) return true
        if (abs(o.speedKph - n.speedKph) > 1) return true
        if (o.gear != n.gear) return true
        if (o.driveMode != n.driveMode) return true
        if (abs(o.oilTempC - n.oilTempC) > 1) return true
        if (abs(o.throttlePct - n.throttlePct) > 2) return true
        if (abs(o.calcLoad - n.calcLoad) > 1) return true
        return false
    }
}
