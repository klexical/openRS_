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
import kotlin.math.roundToInt

/**
 * TPMS+ screen — matches phone TpmsPage.
 * 4-corner pressure & temp; FRONT / REAR; SPREAD; low-pressure warning.
 */
class TpmsScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        const val LOW_PRESSURE_PSI = 30.0
        const val WARN_SPREAD_PSI = 5.0
    }

    // M-2 fix: add lifecycle-aware collection so the screen refreshes on state changes.
    // Previously onGetTemplate() read a point-in-time snapshot with no invalidate() ever called.
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

    private fun changed(o: VehicleState, n: VehicleState): Boolean {
        if (o.tirePressLF != n.tirePressLF || o.tirePressRF != n.tirePressRF) return true
        if (o.tirePressLR != n.tirePressLR || o.tirePressRR != n.tirePressRR) return true
        if (o.hasTpmsData != n.hasTpmsData) return true
        if (o.oilLifePct != n.oilLifePct) return true
        return false
    }

    private fun tF(tempC: Double): String =
        if (tempC > -90) "${(tempC * 9.0 / 5.0 + 32).roundToInt()}°" else ""

    override fun onGetTemplate(): Template {
        val s = last
        val hasTpms = s.hasTpmsData
        val pane = Pane.Builder()

        if (!hasTpms) {
            pane.addRow(Row.Builder()
                .setTitle("TPMS+")
                .addText("Waiting for TPMS data…")
                .build())
            pane.addRow(Row.Builder()
                .setTitle("BCM 0x726  •  PIDs 0x2813–0x2816")
                .build())
        } else {
            pane.addRow(Row.Builder()
                .setTitle("TPMS+  —  Real numbers, not just low pressure warnings.")
                .build())
            pane.addRow(Row.Builder()
                .setTitle("FRONT")
                .addText("LF ${s.tirePressLF.roundToInt()} PSI${tF(s.tireTempLF)}  │  RF ${s.tirePressRF.roundToInt()} PSI${tF(s.tireTempRF)}")
                .build())
            pane.addRow(Row.Builder()
                .setTitle("REAR")
                .addText("LR ${s.tirePressLR.roundToInt()} PSI${tF(s.tireTempLR)}  │  RR ${s.tirePressRR.roundToInt()} PSI${tF(s.tireTempRR)}")
                .build())
            val parts = mutableListOf<String>()
            parts.add("SPREAD  ${"%.1f".format(s.maxTirePressSpread)} PSI")
            if (s.anyTireLow()) parts.add("⚠ LOW TIRE")
            if (s.oilLifePct >= 0) parts.add("OIL LIFE  ${s.oilLifePct.roundToInt()}%")
            pane.addRow(Row.Builder()
                .setTitle("STATUS")
                .addText(parts.joinToString("  •  "))
                .build())
        }

        return PaneTemplate.Builder(pane.build())
            .setTitle("TPMS+")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
