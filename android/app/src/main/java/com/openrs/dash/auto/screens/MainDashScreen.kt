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
 * Main DASH screen — matches phone DashPage.
 * Row 1: BOOST / RPM / SPEED. Row 2: THROTTLE, BRAKE, AWD, TORQUE.
 * Row 3: OIL, COOLANT, INTAKE, BATT. Row 4: LAT G, LON G, STEER, YAW; FUEL, LOAD, AFR, FPS.
 */
class MainDashScreen(carContext: CarContext) : Screen(carContext) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    // M-1 fix: cancel the collection job only — not the whole scope.
    // Cancelling the scope permanently prevents new coroutines after the first onStop.
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
        // Match phone: top gauges then info rows
        val row1 = Row.Builder()
            .setTitle("BOOST  ${"%.1f".format(v.boostPsi)} PSI  │  RPM  ${v.rpm.roundToInt()}  │  SPEED  ${v.speedMph.roundToInt()} MPH  │  ${v.gearDisplay}")
            .build()
        val row2 = Row.Builder()
            .setTitle("THR ${v.throttlePct.roundToInt()}%  BRK ${"%.1f".format(v.brakePressure)}  │  AWD ${v.frontRearSplit}  │  TORQUE ${v.torqueAtTrans.roundToInt()} Nm")
            .build()
        val row3 = Row.Builder()
            .setTitle("OIL ${tF(v.oilTempC)}°  CLT ${tF(v.coolantTempC)}°  IAT ${tF(v.intakeTempC)}°  │  BATT ${"%.1f".format(v.batteryVoltage)}V")
            .build()
        val row4 = Row.Builder()
            .setTitle("LAT ${"%.2f".format(v.lateralG)}g  LON ${"%.2f".format(v.longitudinalG)}g  │  STEER ${"%.1f".format(v.steeringAngle)}°  YAW ${"%.1f".format(v.yawRate)}  │  FUEL ${v.fuelLevelPct.roundToInt()}%  LOAD ${"%.0f".format(v.calcLoad)}%  AFR ${"%.2f".format(v.commandedAfr)}  ${v.framesPerSecond.roundToInt()} fps")
            .build()

        val strip = ActionStrip.Builder()
            .addAction(Action.Builder().setTitle("AWD")
                .setOnClickListener { screenManager.push(AwdDetailScreen(carContext)) }.build())
            .addAction(Action.Builder().setTitle("PERF")
                .setOnClickListener { screenManager.push(PerformanceScreen(carContext)) }.build())
            .addAction(Action.Builder().setTitle("TEMPS")
                .setOnClickListener { screenManager.push(TempsScreen(carContext)) }.build())
            .addAction(Action.Builder().setTitle("MENU")
                .setOnClickListener { screenManager.push(MenuScreen(carContext)) }.build())
            .build()

        val status = if (v.isConnected) "● ${v.framesPerSecond.roundToInt()} fps" else "○ OFFLINE"
        return PaneTemplate.Builder(
            Pane.Builder().addRow(row1).addRow(row2).addRow(row3).addRow(row4).build()
        )
            .setTitle("RS DASH  $status")
            .setActionStrip(strip)
            .build()
    }

    private fun changed(o: VehicleState, n: VehicleState): Boolean {
        if (o.isConnected != n.isConnected) return true
        if (abs(o.rpm - n.rpm) > 50) return true
        if (abs(o.boostPsi - n.boostPsi) > 0.3) return true
        if (abs(o.speedKph - n.speedKph) > 1) return true
        if (o.gear != n.gear) return true
        if (abs(o.oilTempC - n.oilTempC) > 1) return true
        if (abs(o.throttlePct - n.throttlePct) > 2) return true
        if (abs(o.calcLoad - n.calcLoad) > 1) return true
        return false
    }
}
