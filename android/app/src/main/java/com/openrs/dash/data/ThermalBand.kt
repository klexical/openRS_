package com.openrs.dash.data

/**
 * Shared thermal/state bands used by temp cells, AFR cells, and any metric
 * that maps a continuous value to a "how healthy" category.
 *
 * Callers resolve a band, then ask for (color, patternKind) so values are
 * encoded by **both** color and shape — important for color-blind users.
 */
enum class ThermalBand { COLD, NOMINAL, HOT, CRITICAL }

enum class AfrBand { RICH, STOICH, LEAN }

/** Pattern hint for a band — rendered as a tiny glyph on critical states. */
enum class BandPattern { NONE, CHEVRON_UP, CHEVRON_DOWN }

/** Tire tread surface temperature bands (°C). */
fun thermalBandForTire(tempC: Double): ThermalBand = when {
    tempC < 15.0 -> ThermalBand.COLD
    tempC <= 27.0 -> ThermalBand.NOMINAL
    tempC <= 40.0 -> ThermalBand.HOT
    else -> ThermalBand.CRITICAL
}

/** Engine coolant bands (°C). OE redline ~112 °C; RS thermostat opens ~88. */
fun thermalBandForCoolant(tempC: Double): ThermalBand = when {
    tempC < 70.0 -> ThermalBand.COLD
    tempC <= 100.0 -> ThermalBand.NOMINAL
    tempC <= 108.0 -> ThermalBand.HOT
    else -> ThermalBand.CRITICAL
}

/** Engine oil bands (°C). Cold pressure high <60, optimal 90-115, >130 risky. */
fun thermalBandForOil(tempC: Double): ThermalBand = when {
    tempC < 70.0 -> ThermalBand.COLD
    tempC <= 115.0 -> ThermalBand.NOMINAL
    tempC <= 125.0 -> ThermalBand.HOT
    else -> ThermalBand.CRITICAL
}

/** AFR band around stoich (~14.64 for 87 gasoline). */
fun afrBandFor(afr: Double): AfrBand = when {
    afr < 13.5 -> AfrBand.RICH
    afr <= 15.5 -> AfrBand.STOICH
    else -> AfrBand.LEAN
}

/** Shape hint for a band — CRITICAL renders a chevron so the state is
 *  encoded by color + glyph (accessibility: color-blind safe). */
fun patternFor(band: ThermalBand): BandPattern = when (band) {
    ThermalBand.CRITICAL -> BandPattern.CHEVRON_UP
    else -> BandPattern.NONE
}
