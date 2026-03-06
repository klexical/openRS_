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
 * Temps screen — matches phone TempsPage.
 * Engine oil, coolant, intake, ambient; RDU, PTU; charge air, catalytic; RTR when ready.
 */
class TempsScreen(carContext: CarContext) : Screen(carContext) {
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
                .setTitle("ENGINE OIL  ${tF(v.oilTempC)}°F  │  COOLANT  ${tF(v.coolantTempC)}°F")
                .build())
            .addRow(Row.Builder()
                .setTitle("INTAKE AIR  ${tF(v.intakeTempC)}°F  │  AMBIENT  ${tF(v.ambientTempC)}°F")
                .build())
            .addRow(Row.Builder()
                .setTitle("RDU (REAR DIFF)  ${tF(v.rduTempC)}°F  │  PTU (TRANSFER)  ${tF(v.ptuTempC)}°F")
                .build())
            .addRow(
                Row.Builder().apply {
                    setTitle("CHARGE AIR  ${tF(v.chargeAirTempC)}°F  │  CATALYTIC  ${tF(v.catalyticTempC)}°F")
                    if (v.isReadyToRace) addText("READY TO RACE — launch ready.")
                }.build()
            )
        return PaneTemplate.Builder(pane.build())
            .setTitle("TEMPS")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun changed(o: VehicleState, n: VehicleState): Boolean {
        if (abs(o.oilTempC - n.oilTempC) > 1) return true
        if (abs(o.coolantTempC - n.coolantTempC) > 1) return true
        if (abs(o.rduTempC - n.rduTempC) > 1) return true
        if (abs(o.ptuTempC - n.ptuTempC) > 1) return true
        if (o.isReadyToRace != n.isReadyToRace) return true
        return false
    }
}
