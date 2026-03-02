package com.openrs.dash.auto.screens

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.*
/**
 * Menu screen — lists all 6 sections to match phone tabs.
 * DASH, AWD, PERF, TEMPS, TUNE, TPMS. Choosing one navigates to that screen.
 */
class MenuScreen(carContext: CarContext) : Screen(carContext) {

    override fun onGetTemplate(): Template {
        val list = ItemList.Builder()
            .addItem(
                Row.Builder()
                    .setTitle("DASH")
                    .addText("Boost, RPM, speed, temps, AWD, tune data")
                    .setOnClickListener { screenManager.popToRoot() }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("AWD")
                    .addText("Front/rear split, wheel speeds, RDU/PTU temps")
                    .setOnClickListener { screenManager.push(AwdDetailScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("PERF")
                    .addText("G-forces, peaks, reset")
                    .setOnClickListener { screenManager.push(PerformanceScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("TEMPS")
                    .addText("Oil, coolant, RDU, PTU, charge, cat")
                    .setOnClickListener { screenManager.push(TempsScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("TUNE")
                    .addText("AFR, timing, trims, VCT, OBD")
                    .setOnClickListener { screenManager.push(TuneScreen(carContext)) }
                    .build()
            )
            .addItem(
                Row.Builder()
                    .setTitle("TPMS")
                    .addText("Tire pressure & temp, 4 corners")
                    .setOnClickListener { screenManager.push(TpmsScreen(carContext)) }
                    .build()
            )
            .build()

        return ListTemplate.Builder()
            .setSingleList(list)
            .setTitle("RS DASH — MENU")
            .setHeaderAction(Action.BACK)
            .build()
    }
}
