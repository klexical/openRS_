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
    DashCell("rearTorquePct", "number", "REAR BIAS"),
    DashCell("torqueAtTrans", "number", "TORQUE"),
    DashCell("chargeAirTempC", "number", "CHARGE AIR"),
    DashCell("steeringAngle", "number", "STEER"),
    DashCell("yawRate", "number", "YAW RATE"),
    DashCell("wgdcDesired", "bar", "WGDC"),
    DashCell("tipActualKpa", "number", "TIP ACTUAL"),
    DashCell("tipDesiredKpa", "number", "TIP DESIRED")
)

private val fieldIndex = AVAILABLE_FIELDS.associateBy { it.fieldKey }

/** Look up the default DashCell definition for a field key. */
fun defaultCellFor(key: String): DashCell? = fieldIndex[key]

/**
 * Extract a display-formatted value and raw numeric value from VehicleState for a given cell.
 * Temperature fields respect the user's unit preference; boost/speed respect their units too.
 */
fun resolveValue(vs: VehicleState, prefs: UserPrefs, cell: DashCell): Pair<String, Double> {
    return when (cell.fieldKey) {
        "rpm" -> "%.0f".format(vs.rpm) to vs.rpm
        "boostKpa" -> {
            val (v, _) = prefs.displayBoost(vs.boostKpa)
            v to vs.boostKpa
        }
        "speedKph" -> prefs.displaySpeed(vs.speedKph) to vs.speedKph
        "oilTempC" -> tempOrPlaceholder(vs.oilTempC, prefs)
        "coolantTempC" -> tempOrPlaceholder(vs.coolantTempC, prefs)
        "intakeTempC" -> tempOrPlaceholder(vs.intakeTempC, prefs)
        "ambientTempC" -> tempOrPlaceholder(vs.ambientTempC, prefs)
        "rduTempC" -> tempOrPlaceholder(vs.rduTempC, prefs)
        "ptuTempC" -> tempOrPlaceholder(vs.ptuTempC, prefs)
        "chargeAirTempC" -> tempOrPlaceholder(vs.chargeAirTempC, prefs)
        "throttlePct" -> {
            val t = if (vs.throttleHasSource) vs.throttlePct else vs.accelPedalPct
            "%.0f%%".format(t) to t
        }
        "brakePressure" -> "%.0f".format(vs.brakePressure.coerceIn(0.0, 100.0)) to vs.brakePressure
        "fuelLevelPct" -> "%.0f%%".format(vs.fuelLevelPct) to vs.fuelLevelPct
        "batteryVoltage" -> {
            if (vs.batteryVoltage > 0) "%.1fV".format(vs.batteryVoltage) to vs.batteryVoltage
            else "\u2014 \u2014" to 0.0
        }
        "lateralG" -> "%.2f".format(vs.lateralG) to vs.lateralG
        "longitudinalG" -> "%.2f".format(vs.longitudinalG) to vs.longitudinalG
        "ignCorrCyl1" -> "%.1f\u00B0".format(vs.ignCorrCyl1) to vs.ignCorrCyl1
        "ignCorrCyl2" -> "%.1f\u00B0".format(vs.ignCorrCyl2) to vs.ignCorrCyl2
        "ignCorrCyl3" -> "%.1f\u00B0".format(vs.ignCorrCyl3) to vs.ignCorrCyl3
        "ignCorrCyl4" -> "%.1f\u00B0".format(vs.ignCorrCyl4) to vs.ignCorrCyl4
        "afrActual" -> {
            if (vs.afrActual > 0) "%.2f".format(vs.afrActual) to vs.afrActual
            else "\u2014 \u2014" to 0.0
        }
        "shortFuelTrim" -> "%.1f%%".format(vs.shortFuelTrim) to vs.shortFuelTrim
        "longFuelTrim" -> "%.1f%%".format(vs.longFuelTrim) to vs.longFuelTrim
        "oilLifePct" -> {
            if (vs.oilLifePct >= 0) "%.0f%%".format(vs.oilLifePct) to vs.oilLifePct
            else "\u2014 \u2014" to 0.0
        }
        "rearTorquePct" -> "%.0f%%".format(vs.rearTorquePct) to vs.rearTorquePct
        "torqueAtTrans" -> "%.0f Nm".format(vs.torqueAtTrans) to vs.torqueAtTrans
        "steeringAngle" -> "%.1f\u00B0".format(vs.steeringAngle) to vs.steeringAngle
        "yawRate" -> "%.1f".format(vs.yawRate) to vs.yawRate
        "wgdcDesired" -> "%.0f%%".format(vs.wgdcDesired) to vs.wgdcDesired
        "tipActualKpa" -> "%.0f".format(vs.tipActualKpa) to vs.tipActualKpa
        "tipDesiredKpa" -> "%.0f".format(vs.tipDesiredKpa) to vs.tipDesiredKpa
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
