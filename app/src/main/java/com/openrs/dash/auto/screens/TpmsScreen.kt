package com.openrs.dash.auto.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
import com.openrs.dash.OpenRSDashApp
import kotlin.math.roundToInt

/**
 * TPMS+ Android Auto screen — 4-corner tire pressure + temperature.
 *
 * Shows all 4 tires in a grid layout with:
 *   - Pressure in PSI (large)
 *   - Temperature in °F (if available)
 *   - Low-pressure warning highlighting
 *   - Pressure spread indicator
 */
class TpmsScreen(carContext: CarContext) : Screen(carContext) {

    companion object {
        const val LOW_PRESSURE_PSI = 30.0
        const val WARN_SPREAD_PSI = 5.0
    }

    override fun onGetTemplate(): Template {
        val s = OpenOpenRSDashApp.instance.vehicleState.value
        val hasTpms = s.tirePressLF >= 0

        val pane = Pane.Builder()

        if (!hasTpms) {
            pane.addRow(Row.Builder()
                .setTitle("TPMS")
                .addText("Waiting for TPMS data...")
                .addText("BCM header 0x726 • PIDs 0x2813–0x2816")
                .build())
        } else {
            // ── Front Tires ──
            pane.addRow(Row.Builder()
                .setTitle("FRONT")
                .addText(formatTire("LF", s.tirePressLF, s.tireTempLF) +
                        "   │   " +
                        formatTire("RF", s.tirePressRF, s.tireTempRF))
                .build())

            // ── Rear Tires ──
            pane.addRow(Row.Builder()
                .setTitle("REAR")
                .addText(formatTire("LR", s.tirePressLR, s.tireTempLR) +
                        "   │   " +
                        formatTire("RR", s.tirePressRR, s.tireTempRR))
                .build())

            // ── Status ──
            val spread = s.maxTirePressSpread
            val statusParts = mutableListOf<String>()

            if (s.anyTireLow) statusParts.add("⚠ LOW PRESSURE")
            statusParts.add("Spread: %.1f PSI".format(spread))
            if (spread > WARN_SPREAD_PSI) statusParts.add("(uneven)")
            if (s.oilLifePct >= 0) statusParts.add("Oil Life: ${s.oilLifePct.roundToInt()}%")

            pane.addRow(Row.Builder()
                .setTitle("STATUS")
                .addText(statusParts.joinToString("  •  "))
                .build())
        }

        pane.addAction(Action.Builder()
            .setTitle("← DASH")
            .setOnClickListener { screenManager.pop() }
            .build())

        return PaneTemplate.Builder(pane.build())
            .setTitle("TPMS+")
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun formatTire(label: String, pressurePsi: Double, tempC: Double): String {
        val p = if (pressurePsi >= 0) "${pressurePsi.roundToInt()} PSI" else "-- PSI"
        val low = if (pressurePsi in 0.0..LOW_PRESSURE_PSI) " ↓" else ""
        val t = if (tempC > -90) {
            val tempF = tempC * 9.0 / 5.0 + 32.0
            " ${tempF.roundToInt()}°"
        } else ""
        return "$label: $p$low$t"
    }
}
