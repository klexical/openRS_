package com.openrs.dash.ui.units

import com.openrs.dash.ui.UserPrefs

/**
 * The app's one units formatter (#464). Every screen, the HUD, the Custom Dashboard and TRIP's in-app summaries print a
 * unit through this file; `UnitLiteralGuardTest` fails on unit text written anywhere else under `ui/`.
 *
 * Display only: callers pass the stored value in its stored unit (km/h, °C, psi, kPa, L, L/100 km, Nm, AFR / λ) and get
 * back the value and the unit text separately. Limits, colours and warnings compare in the stored unit, so they fire at
 * the same physical value whatever is shown. Exports never use this file: they keep their stored units.
 *
 * The four original settings keep their stored ids ("MPH" / "KPH", "F" / "C", "PSI" / "BAR" / "KPA"); the seven added
 * in #464 use the ids in [UnitIds].
 */
object UnitIds {
    // Speed & distance
    const val KPH = "KPH"; const val MPH = "MPH"
    // Temperature
    const val C = "C"; const val F = "F"
    // Boost, tyres (and the kPa / psi / bar ids shared with engine pressures)
    const val PSI = "PSI"; const val BAR = "BAR"; const val KPA = "KPA"
    // Engine pressures
    const val MPA = "MPA"
    // Air pressure
    const val HPA = "HPA"; const val INHG = "INHG"
    // Altitude
    const val M = "M"; const val FT = "FT"
    // Fuel volume
    const val L = "L"; const val US_GAL = "US_GAL"; const val UK_GAL = "UK_GAL"
    // Economy
    const val L100KM = "L100KM"; const val KML = "KML"; const val MPG_US = "MPG_US"; const val MPG_UK = "MPG_UK"
    // Torque
    const val NM = "NM"; const val LBFT = "LBFT"
    // Mixture
    const val AFR = "AFR"; const val LAMBDA = "LAMBDA"
}

/** One choice per unit setting, in the order Settings lists them. */
data class UnitSet(
    val speed: String,
    val temp: String,
    val boost: String,
    val tyre: String,
    val engine: String,
    val air: String,
    val altitude: String,
    val fuel: String,
    val economy: String,
    val torque: String,
    val mixture: String,
)

/** Settings → UNITS → Region. A preset sets all eleven units; [CUSTOM] is any set that equals no preset. */
enum class Region(val label: String) {
    EUROPE("Europe & international"),
    UK("UK"),
    US("US"),
    CANADA("Canada"),
    AUS_NZ("Australia & NZ"),
    CUSTOM("Custom");

    /** The preset's units; null for [CUSTOM]. */
    val units: UnitSet? get() = PRESETS[this]

    companion object {
        /** The presets, in the list order that also breaks a migration tie. */
        val PRESETS: Map<Region, UnitSet> = linkedMapOf(
            EUROPE to UnitSet(UnitIds.KPH, UnitIds.C, UnitIds.BAR, UnitIds.BAR, UnitIds.BAR, UnitIds.HPA, UnitIds.M,
                UnitIds.L, UnitIds.L100KM, UnitIds.NM, UnitIds.AFR),
            UK to UnitSet(UnitIds.MPH, UnitIds.C, UnitIds.PSI, UnitIds.PSI, UnitIds.BAR, UnitIds.HPA, UnitIds.FT,
                UnitIds.L, UnitIds.MPG_UK, UnitIds.NM, UnitIds.AFR),
            US to UnitSet(UnitIds.MPH, UnitIds.F, UnitIds.PSI, UnitIds.PSI, UnitIds.PSI, UnitIds.INHG, UnitIds.FT,
                UnitIds.US_GAL, UnitIds.MPG_US, UnitIds.LBFT, UnitIds.AFR),
            CANADA to UnitSet(UnitIds.KPH, UnitIds.C, UnitIds.PSI, UnitIds.PSI, UnitIds.PSI, UnitIds.KPA, UnitIds.M,
                UnitIds.L, UnitIds.L100KM, UnitIds.LBFT, UnitIds.AFR),
            AUS_NZ to UnitSet(UnitIds.KPH, UnitIds.C, UnitIds.PSI, UnitIds.KPA, UnitIds.BAR, UnitIds.HPA, UnitIds.M,
                UnitIds.L, UnitIds.L100KM, UnitIds.NM, UnitIds.AFR),
        )

        /** The preset that equals all eleven units, else [CUSTOM]. */
        fun of(units: UnitSet): Region = PRESETS.entries.firstOrNull { it.value == units }?.key ?: CUSTOM

        /**
         * A new install's units, from the phone's own country setting (nothing is sent or looked up): GB → UK, US → US,
         * CA → Canada, AU / NZ → Australia & NZ, anything else (Ireland included) → Europe & international. Mexico and
         * Chile start on Europe & international with economy in km/L, which shows as Custom (owner, #464).
         */
        fun unitsForCountry(country: String): UnitSet = when (country.uppercase()) {
            "GB" -> PRESETS.getValue(UK)
            "US" -> PRESETS.getValue(US)
            "CA" -> PRESETS.getValue(CANADA)
            "AU", "NZ" -> PRESETS.getValue(AUS_NZ)
            "MX", "CL" -> PRESETS.getValue(EUROPE).copy(economy = UnitIds.KML)
            else -> PRESETS.getValue(EUROPE)
        }

        /**
         * An existing user's units after the update (owner's rule, #464): the four original choices are kept exactly;
         * the seven added units come from the preset that matches most of those four. A tie goes to the preset that
         * matches speed and temperature; a remaining tie to the first in [PRESETS]' order.
         */
        fun unitsForExisting(speed: String, temp: String, boost: String, tyre: String): UnitSet {
            fun score(u: UnitSet) = listOf(u.speed == speed, u.temp == temp, u.boost == boost, u.tyre == tyre).count { it }
            fun speedAndTemp(u: UnitSet) = u.speed == speed && u.temp == temp
            val best = PRESETS.values.maxWith(
                compareBy<UnitSet> { score(it) }.thenBy { if (speedAndTemp(it)) 1 else 0 }
                    // maxWith keeps the LAST of equal elements; reverse list order so the first preset wins a tie
                    .thenByDescending { PRESETS.values.indexOf(it) }
            )
            return best.copy(speed = speed, temp = temp, boost = boost, tyre = tyre)
        }
    }
}

/**
 * A value and its unit text, printed apart by cells that style the unit; [text] joins them with one space, or none
 * for a ratio (AFR "14.70:1").
 */
data class Shown(val value: String, val unit: String) {
    val text: String get() = when {
        unit.isEmpty() -> value
        unit.startsWith(":") -> value + unit
        else -> "$value $unit"
    }
    override fun toString() = text
}

/** The one spelling of each unit (#464): what Settings lists and what every screen prints. */
object UnitText {
    const val KMH = "km/h"; const val MPH = "mph"; const val KM = "km"; const val MI = "mi"
    const val DEG_C = "°C"; const val DEG_F = "°F"
    const val PSI = "psi"; const val BAR = "bar"; const val KPA = "kPa"; const val MPA = "MPa"
    const val HPA_ABS = "hPa abs"; const val KPA_ABS = "kPa abs"; const val INHG_ABS = "inHg abs"
    const val M = "m"; const val FT = "ft"
    const val L = "L"; const val US_GAL = "US gal"; const val UK_GAL = "UK gal"
    const val L_PER_H = "L/h"; const val US_GAL_PER_H = "US gal/h"; const val UK_GAL_PER_H = "UK gal/h"
    const val L100KM = "L/100 km"; const val KML = "km/L"; const val MPG_US = "MPG (US)"; const val MPG_UK = "MPG (UK)"
    const val NM = "Nm"; const val LBFT = "lb·ft"
    const val AFR = ":1"; const val LAMBDA = "λ"

    /** Settings labels for each choice, in the order Settings shows them. */
    val SPEED = listOf(UnitIds.KPH to KMH, UnitIds.MPH to MPH)
    val TEMP = listOf(UnitIds.C to DEG_C, UnitIds.F to DEG_F)
    val BOOST = listOf(UnitIds.PSI to PSI, UnitIds.BAR to BAR, UnitIds.KPA to KPA)
    val TYRE = listOf(UnitIds.PSI to PSI, UnitIds.BAR to BAR, UnitIds.KPA to KPA)
    val ENGINE = listOf(UnitIds.BAR to BAR, UnitIds.PSI to PSI, UnitIds.MPA to MPA)
    val AIR = listOf(UnitIds.HPA to "hPa", UnitIds.KPA to KPA, UnitIds.INHG to "inHg")
    val ALTITUDE = listOf(UnitIds.M to M, UnitIds.FT to FT)
    val FUEL = listOf(UnitIds.L to L, UnitIds.US_GAL to US_GAL, UnitIds.UK_GAL to UK_GAL)
    val ECONOMY = listOf(UnitIds.L100KM to L100KM, UnitIds.KML to KML, UnitIds.MPG_US to MPG_US, UnitIds.MPG_UK to MPG_UK)
    val TORQUE = listOf(UnitIds.NM to NM, UnitIds.LBFT to LBFT)
    val MIXTURE = listOf(UnitIds.AFR to "AFR", UnitIds.LAMBDA to LAMBDA)
}

/** Settings search words for the UNITS and TPMS sections — unit spellings live here, not in screen code (#464). */
object UnitSearch {
    val UNITS = listOf("region", "europe", "uk", "us", "canada", "australia", "new zealand", "custom",
        "mph", "km/h", "kph", "celsius", "fahrenheit", "psi", "bar", "kpa", "mpa", "hpa", "inhg",
        "speed", "distance", "temp", "temperature", "boost", "tire", "tyre", "pressure", "fuel", "rail",
        "baro", "altitude", "feet", "metres", "litres", "gallons", "economy", "mpg", "l/100",
        "torque", "nm", "lb-ft", "mixture", "afr", "lambda")
    val TPMS = listOf("tpms", "tire", "tyre", "pressure", "low", "warn", "high", "psi", "bar", "kpa")
}

/** Conversion factors from the stored units (#464 spec). */
object UnitFactors {
    const val KM_TO_MI = 0.621371
    const val KPA_TO_PSI = 0.14503773
    const val STD_ATM_KPA = 101.325
    const val PSI_TO_BAR = 0.0689476
    const val KPA_PER_INHG = 3.386389
    const val M_TO_FT = 3.28084
    const val L_PER_US_GAL = 3.785412
    const val L_PER_UK_GAL = 4.54609
    const val MPG_US_X_L100 = 235.215
    const val MPG_UK_X_L100 = 282.481
    const val NM_TO_LBFT = 0.737562
    /** The decoder's stoichiometric ratio (`ObdResponseParser`: λ = AFR / 14.7). */
    const val STOICH_AFR = 14.7
}

private fun f(decimals: Int, v: Double) = "%.${decimals}f".format(v)

// ── Speed & distance (stored km/h, km) ────────────────────────────────────────────────────────────────────────────

private val UserPrefs.mph get() = speedUnit == UnitIds.MPH

/** Speed unit text: km/h or mph. */
val UserPrefs.speedUnitText: String get() = if (mph) UnitText.MPH else UnitText.KMH

/** A speed, 0 decimals. */
fun UserPrefs.speed(kph: Double): Shown = Shown(f(0, if (mph) kph * UnitFactors.KM_TO_MI else kph), speedUnitText)

/** A speed difference (wheel L/R and F/R Δ), 1 decimal. */
fun UserPrefs.speedDelta(kph: Double): Shown = Shown(f(1, if (mph) kph * UnitFactors.KM_TO_MI else kph), speedUnitText)

/** Distance unit text: km or mi. */
val UserPrefs.distanceUnitText: String get() = if (mph) UnitText.MI else UnitText.KM

/** A distance in the speed unit's distance, 1 decimal. */
fun UserPrefs.distance(km: Double): Shown = Shown(f(1, distanceIn(km)), distanceUnitText)

/** Range (distance to empty), 0 decimals. */
fun UserPrefs.range(km: Double): Shown = Shown(f(0, distanceIn(km)), distanceUnitText)

/** A stored distance in the shown unit (charts, comparisons). */
fun UserPrefs.distanceIn(km: Double): Double = if (mph) km * UnitFactors.KM_TO_MI else km

/** A stored speed in the shown unit. */
fun UserPrefs.speedIn(kph: Double): Double = if (mph) kph * UnitFactors.KM_TO_MI else kph

// ── Temperature (stored °C) ───────────────────────────────────────────────────────────────────────────────────────

/** Temperature unit text: °C or °F. */
val UserPrefs.tempUnitText: String get() = if (tempUnit == UnitIds.C) UnitText.DEG_C else UnitText.DEG_F

/** A stored temperature in the shown unit. */
fun UserPrefs.tempIn(c: Double): Double = if (tempUnit == UnitIds.C) c else c * 9.0 / 5.0 + 32.0

/** A temperature difference in the shown unit (× 1.8, no offset). */
fun UserPrefs.tempDeltaIn(dc: Double): Double = if (tempUnit == UnitIds.C) dc else dc * 9.0 / 5.0

/** A temperature, 0 decimals; the value and unit join with no space ("90°C"), as every screen printed them. */
fun UserPrefs.temp(c: Double): String = f(0, tempIn(c)) + tempUnitText

// ── Boost (stored gauge: kPa live, psi recorded) ──────────────────────────────────────────────────────────────────

/** Boost unit text: psi, bar or kPa. */
val UserPrefs.boostUnitText: String get() = when (boostUnit) {
    UnitIds.BAR -> UnitText.BAR
    UnitIds.KPA -> UnitText.KPA
    else -> UnitText.PSI
}

/** Gauge pressure (kPa above ambient) in the boost unit: psi 1, bar 2, kPa 0 decimals. */
fun UserPrefs.boostGauge(gaugeKpa: Double): Shown = when (boostUnit) {
    UnitIds.BAR -> Shown(f(2, gaugeKpa / 100.0), UnitText.BAR)
    UnitIds.KPA -> Shown(f(0, gaugeKpa), UnitText.KPA)
    else -> Shown(f(1, gaugeKpa * UnitFactors.KPA_TO_PSI), UnitText.PSI)
}

/** Recorded gauge psi (drive points, peaks, stats) in the boost unit. */
fun UserPrefs.boostPsi(gaugePsi: Double): Shown = boostGauge(gaugePsi / UnitFactors.KPA_TO_PSI)

/** Recorded gauge psi as a number in the boost unit (trend lines, axes). */
fun UserPrefs.boostPsiIn(gaugePsi: Double): Double = when (boostUnit) {
    UnitIds.BAR -> gaugePsi / UnitFactors.KPA_TO_PSI / 100.0
    UnitIds.KPA -> gaugePsi / UnitFactors.KPA_TO_PSI
    else -> gaugePsi
}

/** Decimals of the boost unit (psi 1, bar 2, kPa 0). */
val UserPrefs.boostDecimals: Int get() = when (boostUnit) { UnitIds.BAR -> 2; UnitIds.KPA -> 0; else -> 1 }

// ── Tyres (stored psi) ────────────────────────────────────────────────────────────────────────────────────────────

/** Tyre unit text: psi, bar or kPa. */
val UserPrefs.tyreUnitText: String get() = tyreUnitText(tireUnit)

fun tyreUnitText(id: String): String = when (id) {
    UnitIds.BAR -> UnitText.BAR
    UnitIds.KPA -> UnitText.KPA
    else -> UnitText.PSI
}

/** Tyre pressure: psi 1, bar 2, kPa 0 decimals. */
fun UserPrefs.tyre(psi: Double): Shown = Shown(tyreValue(psi, tireUnit), tyreUnitText)

/** A tyre pressure in [unitId] — the Settings TPMS editor shows its staged unit with the same precision. */
fun tyreValue(psi: Double, unitId: String): String = when (unitId) {
    UnitIds.BAR -> f(2, psi * UnitFactors.PSI_TO_BAR)
    UnitIds.KPA -> f(0, psi / UnitFactors.KPA_TO_PSI)
    else -> f(1, psi)
}

/** A typed tyre value in [unitId] → psi (the TPMS editor's one conversion on SAVE). */
fun tyreToPsi(value: Double, unitId: String): Double = when (unitId) {
    UnitIds.BAR -> value / UnitFactors.PSI_TO_BAR
    UnitIds.KPA -> value * UnitFactors.KPA_TO_PSI
    else -> value
}

// ── Engine pressures (HP fuel psi, LP fuel kPa, AWD clutch mbar) ──────────────────────────────────────────────────

/** Engine-pressure unit text: bar, psi or MPa. */
val UserPrefs.engineUnitText: String get() = when (engineUnit) {
    UnitIds.PSI -> UnitText.PSI
    UnitIds.MPA -> UnitText.MPA
    else -> UnitText.BAR
}

/** Engine pressure (kPa) in the engine-pressure unit: bar 1, psi 0, MPa 2 decimals. */
fun UserPrefs.enginePressureKpa(kpa: Double): Shown = when (engineUnit) {
    UnitIds.PSI -> Shown(f(0, kpa * UnitFactors.KPA_TO_PSI), UnitText.PSI)
    UnitIds.MPA -> Shown(f(2, kpa / 1000.0), UnitText.MPA)
    else -> Shown(f(1, kpa / 100.0), UnitText.BAR)
}

/** HP fuel rail (stored psi). */
fun UserPrefs.enginePressurePsi(psi: Double): Shown = enginePressureKpa(psi / UnitFactors.KPA_TO_PSI)

/** AWD clutch pressure (stored mbar). */
fun UserPrefs.enginePressureMbar(mbar: Double): Shown = enginePressureKpa(mbar / 10.0)

// ── Air pressure (stored kPa absolute) ────────────────────────────────────────────────────────────────────────────

/** An absolute air pressure: hPa abs 0, kPa abs 1, inHg abs 2 decimals. */
fun UserPrefs.airAbs(kpaAbs: Double): Shown = when (airUnit) {
    UnitIds.KPA -> Shown(f(1, kpaAbs), UnitText.KPA_ABS)
    UnitIds.INHG -> Shown(f(2, kpaAbs / UnitFactors.KPA_PER_INHG), UnitText.INHG_ABS)
    else -> Shown(f(0, kpaAbs * 10.0), UnitText.HPA_ABS)
}

// ── Altitude (density altitude stored ft, GPS m) ──────────────────────────────────────────────────────────────────

val UserPrefs.altitudeUnitText: String get() = if (altitudeUnit == UnitIds.FT) UnitText.FT else UnitText.M

/** An altitude or a length given in metres, 0 decimals. */
fun UserPrefs.altitudeM(m: Double): Shown =
    Shown(f(0, if (altitudeUnit == UnitIds.FT) m * UnitFactors.M_TO_FT else m), altitudeUnitText)

/** An altitude given in feet, 0 decimals. */
fun UserPrefs.altitudeFt(ft: Double): Shown =
    Shown(f(0, if (altitudeUnit == UnitIds.FT) ft else ft / UnitFactors.M_TO_FT), altitudeUnitText)

// ── Fuel volume (stored L, L/h) ───────────────────────────────────────────────────────────────────────────────────

val UserPrefs.fuelUnitText: String get() = when (fuelUnit) {
    UnitIds.US_GAL -> UnitText.US_GAL
    UnitIds.UK_GAL -> UnitText.UK_GAL
    else -> UnitText.L
}

/** A fuel volume: L 1, US gal 2, UK gal 2 decimals. */
fun UserPrefs.fuelVolume(litres: Double): Shown = when (fuelUnit) {
    UnitIds.US_GAL -> Shown(f(2, litres / UnitFactors.L_PER_US_GAL), UnitText.US_GAL)
    UnitIds.UK_GAL -> Shown(f(2, litres / UnitFactors.L_PER_UK_GAL), UnitText.UK_GAL)
    else -> Shown(f(1, litres), UnitText.L)
}

val UserPrefs.fuelFlowUnitText: String get() = when (fuelUnit) {
    UnitIds.US_GAL -> UnitText.US_GAL_PER_H
    UnitIds.UK_GAL -> UnitText.UK_GAL_PER_H
    else -> UnitText.L_PER_H
}

/** A fuel flow: L/h 1, US gal/h 2, UK gal/h 2 decimals. */
fun UserPrefs.fuelFlow(litresPerHour: Double): Shown = when (fuelUnit) {
    UnitIds.US_GAL -> Shown(f(2, litresPerHour / UnitFactors.L_PER_US_GAL), UnitText.US_GAL_PER_H)
    UnitIds.UK_GAL -> Shown(f(2, litresPerHour / UnitFactors.L_PER_UK_GAL), UnitText.UK_GAL_PER_H)
    else -> Shown(f(1, litresPerHour), UnitText.L_PER_H)
}

// ── Economy (stored L/100 km) ─────────────────────────────────────────────────────────────────────────────────────

val UserPrefs.economyUnitText: String get() = when (economyUnit) {
    UnitIds.KML -> UnitText.KML
    UnitIds.MPG_US -> UnitText.MPG_US
    UnitIds.MPG_UK -> UnitText.MPG_UK
    else -> UnitText.L100KM
}

/** L/100 km in the economy unit as a number; 0 for no figure (≤ 0). */
fun UserPrefs.economyIn(l100km: Double): Double = if (l100km <= 0.0) 0.0 else when (economyUnit) {
    UnitIds.KML -> 100.0 / l100km
    UnitIds.MPG_US -> UnitFactors.MPG_US_X_L100 / l100km
    UnitIds.MPG_UK -> UnitFactors.MPG_UK_X_L100 / l100km
    else -> l100km
}

/** Economy, 1 decimal; null when there is no figure (L/100 km ≤ 0). */
fun UserPrefs.economy(l100km: Double): Shown? =
    if (l100km <= 0.0) null else Shown(f(1, economyIn(l100km)), economyUnitText)

// ── Torque (stored Nm) ────────────────────────────────────────────────────────────────────────────────────────────

val UserPrefs.torqueUnitText: String get() = if (torqueUnit == UnitIds.LBFT) UnitText.LBFT else UnitText.NM

/** A torque, 0 decimals. */
fun UserPrefs.torque(nm: Double): Shown = Shown(torqueValue(nm), torqueUnitText)

/** A torque's number alone (cells that print the unit once in their caption). */
fun UserPrefs.torqueValue(nm: Double): String = f(0, if (torqueUnit == UnitIds.LBFT) nm * UnitFactors.NM_TO_LBFT else nm)

// ── Mixture (stored AFR; commanded as λ) ──────────────────────────────────────────────────────────────────────────

val UserPrefs.lambdaLeads: Boolean get() = mixtureUnit == UnitIds.LAMBDA

/** A mixture given as AFR, in the mixture unit: AFR 2 decimals (":1"), λ 3 decimals. */
fun UserPrefs.mixtureFromAfr(afr: Double): Shown =
    if (lambdaLeads) Shown(f(3, afr / UnitFactors.STOICH_AFR), UnitText.LAMBDA) else Shown(f(2, afr), UnitText.AFR)

/** A mixture given as λ, in the mixture unit. */
fun UserPrefs.mixtureFromLambda(lambda: Double): Shown = mixtureFromAfr(lambda * UnitFactors.STOICH_AFR)

/** The other mixture form, for the POWER panel's third cell: λ when AFR leads, AFR when λ leads. */
fun UserPrefs.mixtureOtherFromAfr(afr: Double): Shown =
    if (lambdaLeads) Shown(f(2, afr), UnitText.AFR) else Shown(f(3, afr / UnitFactors.STOICH_AFR), UnitText.LAMBDA)

/** The label word for mixture in the leading form: "AFR" or "λ". */
val UserPrefs.mixtureWord: String get() = if (lambdaLeads) UnitText.LAMBDA else "AFR"

/** The label word for the other form. */
val UserPrefs.mixtureOtherWord: String get() = if (lambdaLeads) "AFR" else "LAMBDA"

// ── Region ────────────────────────────────────────────────────────────────────────────────────────────────────────

/** The eleven unit choices. */
val UserPrefs.unitSet: UnitSet get() =
    UnitSet(speedUnit, tempUnit, boostUnit, tireUnit, engineUnit, airUnit, altitudeUnit, fuelUnit, economyUnit,
        torqueUnit, mixtureUnit)

/** The region Settings shows: the preset that equals all eleven, else Custom. */
val UserPrefs.region: Region get() = Region.of(unitSet)

/** These prefs with all eleven units set from [units]. */
fun UserPrefs.withUnits(units: UnitSet): UserPrefs = copy(
    speedUnit = units.speed, tempUnit = units.temp, boostUnit = units.boost, tireUnit = units.tyre,
    engineUnit = units.engine, airUnit = units.air, altitudeUnit = units.altitude, fuelUnit = units.fuel,
    economyUnit = units.economy, torqueUnit = units.torque, mixtureUnit = units.mixture,
    odomInMiles = units.speed == UnitIds.MPH,
)
