package com.openrs.dash.data

import com.openrs.dash.ui.UserPrefs

/**
 * Custom dashboard layout — user-configurable gauge grid.
 * Persisted as JSON in SharedPreferences via AppSettings.
 */
data class DashCell(
    val fieldKey: String,       // VehicleState field name, e.g., "rpm", "boostKpa", "oilTempC"
    val displayType: String,    // "number", "bar", "hero"
    val label: String           // User-facing label, e.g., "RPM", "BOOST", "OIL TEMP"
)

data class DashLayout(
    val name: String = "Custom",
    val cells: List<DashCell> = emptyList()
)

/** All VehicleState fields available for the custom dashboard builder. */
val AVAILABLE_FIELDS = listOf(
    DashCell("rpm", "hero", "RPM"),
    DashCell("boostKpa", "hero", "BOOST"),
    DashCell("speedKph", "hero", "SPEED"),
    DashCell("oilTempC", "number", "OIL TEMP"),
    DashCell("coolantTempC", "number", "COOLANT"),
    DashCell("throttlePct", "bar", "THROTTLE"),
    DashCell("brakePressure", "bar", "BRAKE"),
    DashCell("fuelLevelPct", "bar", "FUEL"),
    DashCell("batteryVoltage", "number", "BATTERY"),
    DashCell("rduTempC", "number", "RDU TEMP"),
    DashCell("ptuTempC", "number", "PTU TEMP"),
    DashCell("intakeTempC", "number", "INTAKE"),
    DashCell("ambientTempC", "number", "AMBIENT"),
    DashCell("lateralG", "number", "LAT G"),
    DashCell("longitudinalG", "number", "LON G"),
    DashCell("ignCorrCyl1", "number", "KR C1"),
    DashCell("ignCorrCyl2", "number", "KR C2"),
    DashCell("ignCorrCyl3", "number", "KR C3"),
    DashCell("ignCorrCyl4", "number", "KR C4"),
    DashCell("afrActual", "number", "AFR"),
    DashCell("shortFuelTrim", "number", "SHORT FT"),
    DashCell("longFuelTrim", "number", "LONG FT"),
    DashCell("oilLifePct", "number", "OIL LIFE"),
    DashCell("torqueAtTrans", "number", "TORQUE"),
    DashCell("chargeAirTempC", "number", "CHARGE AIR"),
    DashCell("steeringAngle", "number", "STEER"),
    DashCell("yawRate", "number", "YAW RATE"),
    DashCell("wgdcDesired", "bar", "WGDC"),
    DashCell("tipActualKpa", "number", "TIP ACTUAL"),
    DashCell("tipDesiredKpa", "number", "TIP DESIRED")
)

private val fieldIndex = AVAILABLE_FIELDS.associateBy { it.fieldKey }

/**
 * Readings withdrawn from the picker but still in saved layouts: the cell stays (a user's configuration is never
 * deleted) and reads "— —" with this explanation.
 */
val WITHDRAWN_FIELDS: Map<String, String> = mapOf(
    "rearTorquePct" to "WITHDRAWN · rear ÷ engine torque isn't a physical split (#226)",
)

/** Look up the default DashCell definition for a field key. */
fun defaultCellFor(key: String): DashCell? = fieldIndex[key]

/**
 * Extract a display-formatted value and raw numeric value from VehicleState for a given cell.
 * Temperature fields respect the user's unit preference; boost/speed respect their units too.
 */
fun resolveValue(vs: VehicleState, prefs: UserPrefs, cell: DashCell, nowMs: Long = System.currentTimeMillis()): Pair<String, Double> {
    // PCM-polled fields show only while current (#217): a held reading is "— —", never a value that looks live
    fun polled(tag: String, value: Double, fmt: (Double) -> String): Pair<String, Double> =
        if (PolledFreshness.isFresh(vs, tag, nowMs)) fmt(value) to value else "\u2014 \u2014" to 0.0
    return when (cell.fieldKey) {
        "rpm" -> "%.0f".format(vs.rpm) to vs.rpm
        // By the boost source rule (#207): no fresh source → "— —", never a held or default value
        "boostKpa" -> BoostSession.read(vs, nowMs).gaugeKpa
            ?.let { g -> prefs.displayGauge(g).first to g + BoostReading.STD_ATM_KPA } ?: ("\u2014 \u2014" to 0.0)
        "speedKph" -> prefs.displaySpeed(vs.speedKph) to vs.speedKph
        "oilTempC" -> tempOrPlaceholder(vs.oilTempC, prefs)
        "coolantTempC" -> tempOrPlaceholder(vs.coolantTempC, prefs)
        "intakeTempC" -> tempOrPlaceholder(vs.intakeTempC, prefs)
        "ambientTempC" -> tempOrPlaceholder(vs.ambientTempC, prefs)
        "rduTempC" -> tempOrPlaceholder(vs.rduTempC, prefs)
        "ptuTempC" -> tempOrPlaceholder(vs.ptuTempC, prefs)
        "chargeAirTempC" -> tempOrPlaceholder(vs.chargeAirTempC, prefs)
        "throttlePct" -> {
            val t = vs.throttleOrPedalPct
            "%.0f%%".format(t) to t
        }
        "brakePressure" -> "%.0f".format(vs.brakePressure.coerceIn(0.0, 100.0)) to vs.brakePressure
        "fuelLevelPct" -> "%.0f%%".format(vs.fuelLevelPct) to vs.fuelLevelPct
        "batteryVoltage" -> {
            // 2 decimals so variation is visible — a voltage that sits at
            // 14.9X vs 15.00 tells you the alternator is still modulating.
            if (vs.batteryVoltage > 0) "%.2fV".format(vs.batteryVoltage) to vs.batteryVoltage
            else "\u2014 \u2014" to 0.0
        }
        "lateralG" -> "%.2f".format(vs.lateralG) to vs.lateralG
        "longitudinalG" -> "%.2f".format(vs.longitudinalG) to vs.longitudinalG
        "ignCorrCyl1" -> polled("ignCorrCyl1", vs.ignCorrCyl1) { "%.1f\u00B0".format(it) }
        "ignCorrCyl2" -> polled("ignCorrCyl2", vs.ignCorrCyl2) { "%.1f\u00B0".format(it) }
        "ignCorrCyl3" -> polled("ignCorrCyl3", vs.ignCorrCyl3) { "%.1f\u00B0".format(it) }
        "ignCorrCyl4" -> polled("ignCorrCyl4", vs.ignCorrCyl4) { "%.1f\u00B0".format(it) }
        "afrActual" -> {
            if (vs.afrActual > 0) "%.2f".format(vs.afrActual) to vs.afrActual
            else "\u2014 \u2014" to 0.0
        }
        "shortFuelTrim" -> polled("shortFuelTrim", vs.shortFuelTrim) { "%.1f%%".format(it) }
        "longFuelTrim" -> polled("longFuelTrim", vs.longFuelTrim) { "%.1f%%".format(it) }
        "oilLifePct" -> {
            if (vs.oilLifePct >= 0) "%.0f%%".format(vs.oilLifePct) to vs.oilLifePct
            else "\u2014 \u2014" to 0.0
        }
        "rearTorquePct" -> "\u2014 \u2014" to 0.0          // withdrawn (#226) — see WITHDRAWN_FIELDS
        "torqueAtTrans" -> "%.0f Nm".format(vs.torqueAtTrans) to vs.torqueAtTrans
        "steeringAngle" -> "%.1f\u00B0".format(vs.steeringAngle) to vs.steeringAngle
        "yawRate" -> "%.1f".format(vs.yawRate) to vs.yawRate
        "wgdcDesired" -> polled("wgdcDesired", vs.wgdcDesired) { "%.0f%%".format(it) }
        // Same reference as POWER and the TIP gauge (#207): against measured baro in the boost unit; absolute kPa
        // (labelled) only when baro isn't known — never a bare absolute number next to gauge readings
        "tipActualKpa" -> polled("tipActualKpa", vs.tipActualKpa) { tipText(vs, prefs, it) }
        "tipDesiredKpa" -> polled("tipDesiredKpa", vs.tipDesiredKpa) { tipText(vs, prefs, it) }
        else -> "\u2014" to 0.0
    }
}

/** Helper: format a temperature field, showing placeholder if sentinel (-99). */
private fun tempOrPlaceholder(tempC: Double, prefs: UserPrefs): Pair<String, Double> {
    return if (tempC > -90) {
        "${prefs.displayTemp(tempC)}${prefs.tempLabel}" to tempC
    } else {
        "\u2014 \u2014" to 0.0
    }
}

/**
 * Returns a normalized 0..1 fraction for bar-type display.
 * Each field has a sensible max scale for visual representation.
 */
fun barFraction(cell: DashCell, rawValue: Double): Float {
    val max = when (cell.fieldKey) {
        "throttlePct" -> 100.0
        "brakePressure" -> 100.0
        "fuelLevelPct" -> 100.0
        "wgdcDesired" -> 100.0
        "rpm" -> 7000.0
        "boostKpa" -> 280.0   // ~26 PSI gauge
        "speedKph" -> 260.0
        else -> 100.0
    }
    return (rawValue / max).coerceIn(0.0, 1.0).toFloat()
}

private fun tipText(vs: VehicleState, prefs: UserPrefs, absKpa: Double): String =
    BoostRule.gaugeVsBaro(vs, absKpa)?.let { g -> prefs.displayGauge(g).let { (v, u) -> "$v $u" } } ?: "${"%.0f".format(absKpa)} kPa abs"
