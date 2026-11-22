package com.openrs.dash.ui

import com.openrs.dash.ui.units.UnitIds
import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import com.openrs.dash.ui.units.boostGauge
import com.openrs.dash.ui.units.speed
import com.openrs.dash.ui.units.speedUnitText
import com.openrs.dash.ui.units.tempDeltaIn
import com.openrs.dash.ui.units.tempIn
import com.openrs.dash.ui.units.tempUnitText
import com.openrs.dash.ui.units.tyre
import com.openrs.dash.ui.units.tyreUnitText

/**
 * User-configurable display and connection preferences.
 * Observable via [UserPrefsStore.prefs] so all Compose UI reacts to changes instantly.
 */
/** The older names of four of [com.openrs.dash.ui.units.UnitFactors]' factors, kept for their callers; one set of numbers. */
object UnitConversions {
    const val KM_TO_MI = com.openrs.dash.ui.units.UnitFactors.KM_TO_MI
    const val STD_ATM_KPA = com.openrs.dash.ui.units.UnitFactors.STD_ATM_KPA
    const val KPA_TO_PSI = com.openrs.dash.ui.units.UnitFactors.KPA_TO_PSI
    const val PSI_TO_BAR = com.openrs.dash.ui.units.UnitFactors.PSI_TO_BAR
}

data class UserPrefs(
    val speedUnit: String           = AppSettings.DEFAULT_SPEED_UNIT,   // "MPH" | "KPH"
    val tempUnit: String            = AppSettings.DEFAULT_TEMP_UNIT,    // "F"   | "C"
    val boostUnit: String           = AppSettings.DEFAULT_BOOST_UNIT,   // "PSI" | "BAR" | "KPA"
    val tireUnit: String            = AppSettings.DEFAULT_TIRE_UNIT,    // "PSI" | "BAR" | "KPA"
    // #464: the seven units added with Region (ids in ui/units/Units.kt). Defaults = the US preset, matching the
    // four defaults above; AppSettings.load fills them by the migration rule.
    val engineUnit: String          = AppSettings.DEFAULT_ENGINE_UNIT,  // "BAR" | "PSI" | "MPA"
    val airUnit: String             = AppSettings.DEFAULT_AIR_UNIT,     // "HPA" | "KPA" | "INHG"
    val altitudeUnit: String        = AppSettings.DEFAULT_ALTITUDE_UNIT,// "M" | "FT"
    val fuelUnit: String            = AppSettings.DEFAULT_FUEL_UNIT,    // "L" | "US_GAL" | "UK_GAL"
    val economyUnit: String         = AppSettings.DEFAULT_ECONOMY_UNIT, // "L100KM" | "KML" | "MPG_US" | "MPG_UK"
    val torqueUnit: String          = AppSettings.DEFAULT_TORQUE_UNIT,  // "NM" | "LBFT"
    val mixtureUnit: String         = AppSettings.DEFAULT_MIXTURE_UNIT, // "AFR" | "LAMBDA"
    val tireLowPsi: Float           = AppSettings.DEFAULT_TIRE_LOW_PSI,
    val tireWarnPsi: Float          = AppSettings.DEFAULT_TIRE_WARN_PSI,
    val tireHighPsi: Float          = AppSettings.DEFAULT_TIRE_HIGH_PSI,
    val screenOn: Boolean           = AppSettings.DEFAULT_SCREEN_ON,
    val autoReconnect: Boolean      = AppSettings.DEFAULT_AUTO_RECONNECT,
    val reconnectIntervalSec: Int   = AppSettings.DEFAULT_RECONNECT_INTERVAL,
    val maxDiagZips: Int            = AppSettings.DEFAULT_MAX_DIAG_ZIPS, // max diagnostic ZIPs to keep
    val themeId: String             = AppSettings.DEFAULT_THEME_ID,      // RS paint color theme
    val tempPreset: String          = AppSettings.DEFAULT_TEMP_PRESET,   // "street"|"track"|"race"|"custom"
    /** Saved Custom thermal profile (°C) and the built-in it was copied from; null until one is created. */
    val customThermal: ThermalProfile? = null,
    val customThermalBase: String   = "street",
    val adapterType: String         = AppSettings.DEFAULT_ADAPTER_TYPE,  // "MEATPI_USB" | "MEATPI_PRO"
    val connectionMethod: String    = AppSettings.DEFAULT_CONNECTION_METHOD, // "WIFI" | "BLUETOOTH"
    val meatPiMicroSdLog: Boolean   = false,                              // MeatPi Pro microSD logging
    val odomInMiles: Boolean        = speedUnit == UnitIds.MPH,                 // Odometer display: true=mi, false=km
    val edgeShiftLight: Boolean     = AppSettings.DEFAULT_EDGE_SHIFT_LIGHT,  // peripheral edge glow
    val edgeShiftColor: String      = AppSettings.DEFAULT_EDGE_SHIFT_COLOR,  // "accent"|"white"|"progressive"
    val edgeShiftIntensity: String  = AppSettings.DEFAULT_EDGE_SHIFT_INTENSITY, // "low"|"med"|"high"
    val edgeShiftRpm: Int           = AppSettings.DEFAULT_EDGE_SHIFT_RPM,    // shift point RPM
    val autoRecordDrives: Boolean   = AppSettings.DEFAULT_AUTO_RECORD_DRIVES,  // auto-start on connect
    val maxSavedDrives: Int         = AppSettings.DEFAULT_MAX_SAVED_DRIVES,    // oldest pruned when exceeded
    val autoScanDtcs: Boolean       = AppSettings.DEFAULT_AUTO_SCAN_DTCS,      // auto-scan DTCs on connect
    val updateChannel: String       = AppSettings.DEFAULT_UPDATE_CHANNEL,      // "stable" | "beta"
    val brightness: Float           = 0f,                                      // deprecated v3.0 (kept for migration)
    val themeMode: String           = AppSettings.DEFAULT_THEME_MODE,          // "NIGHT" | "DAY" | "AUTO"
    val classicFonts: Boolean       = AppSettings.DEFAULT_CLASSIC_FONTS,       // Orbitron revival toggle
    val navTabIdentity: Boolean     = AppSettings.DEFAULT_NAV_TAB_IDENTITY,   // per-tab identity colors on bottom nav
    val livePillQuiet: Boolean      = AppSettings.DEFAULT_LIVE_PILL_QUIET,    // connected pill alpha fades after stable
    val gearModeTint: Boolean       = AppSettings.DEFAULT_GEAR_MODE_TINT,     // GEAR label tints to mode color
) {
    // ── Unit-conversion helpers used by UI ─────────────────────────────────
    // Each one goes through the units formatter (ui/units/Units.kt, #464) — the one place a unit is spelled.

    fun displayTemp(celsius: Double): String = "%.0f".format(tempIn(celsius))

    val tempLabel: String get() = tempUnitText

    /**
     * A limit or target: whole numbers stay whole, others keep one decimal (custom 104.44 °C → "104.4",
     * warn − 2 °C in °F → "244.4") — the same text on gauge legends, THERMAL's reason, the checklist and DRIVE.
     */
    fun displayLimit(celsius: Double): String {
        val x = tempIn(celsius)
        return (if (kotlin.math.abs(x - Math.round(x)) < 0.05) "%.0f" else "%.1f").format(x)
    }

    /** A temperature difference (headroom, margin): °F = Δ°C × 1.8 — no +32 offset. */
    fun displayTempDelta(deltaC: Double): String = "%.0f".format(tempDeltaIn(deltaC))

    /** A rate of change, °C/min or °F/min (× 1.8, no offset), one decimal. */
    fun displayTempRate(cPerMin: Double): String = "%.1f".format(tempDeltaIn(cPerMin))

    fun displaySpeed(kph: Double): String = speed(kph).value

    /** "km/h" or "mph" — the one spelling (#464). */
    val speedLabel: String get() = speedUnitText

    /** Gauge pressure (kPa relative to ambient, e.g. a [com.openrs.dash.data.BoostReading]) in the boost unit. */
    fun displayGauge(gaugeKpa: Double): Pair<String, String> = boostGauge(gaugeKpa).let { it.value to it.unit }

    /** Boost pressure from absolute kPa (std-atm form). Returns (value, label) pair. */
    fun displayBoost(boostKpa: Double): Pair<String, String> = displayGauge(boostKpa - UnitConversions.STD_ATM_KPA)

    /** Tire pressure in PSI → display value (psi 1, bar 2, kPa 0 decimals). */
    fun displayTire(psi: Double): String = tyre(psi).value

    val tireLabel: String get() = tyreUnitText

    /** Whether a raw PSI value is below the warning threshold. */
    fun isTireLow(psi: Double): Boolean = psi in 0.01..tireLowPsi.toDouble()

    // ── Theme system ────────────────────────────────────────────────────────

    /** Primary accent color for this RS paint theme (actual MK3 paint catalogue). */
    val themeAccent: Color get() = rsPaintAccent(themeId)

    /** Display name for the current theme. */
    val themeName: String get() = rsPaintName(themeId)

    // ── Temperature thresholds (active thermal profile) ─────────────────────

    /** The active profile: a built-in (STREET / TRACK / RACE) or the saved Custom one. */
    val thermalProfile: ThermalProfile get() =
        if (tempPreset == "custom") customThermal ?: ThermalProfile.STREET else ThermalProfile.builtIn(tempPreset)

    /** Oil warn / crit thresholds in °C (active profile). */
    val oilWarnC: Double  get() = thermalProfile.oilWarnC
    val oilCritC: Double  get() = thermalProfile.oilCritC

    /** Coolant warn / crit thresholds in °C. */
    val coolWarnC: Double get() = thermalProfile.coolWarnC
    val coolCritC: Double get() = thermalProfile.coolCritC

    /** Intake air warn / crit thresholds in °C. */
    val intakeWarnC: Double get() = thermalProfile.intakeWarnC
    val intakeCritC: Double get() = thermalProfile.intakeCritC

    /** RDU/PTU warn / crit thresholds in °C. */
    val rduWarnC: Double  get() = thermalProfile.rduWarnC
    val rduCritC: Double  get() = thermalProfile.rduCritC
    // PTU (transfer case) runs hotter than RDU — separate thresholds
    val ptuWarnC: Double  get() = thermalProfile.ptuWarnC
    val ptuCritC: Double  get() = thermalProfile.ptuCritC

    /** AWD clutch warn / crit in °C — fixed 90 / 120 in every built-in, editable in Custom. */
    val clutchWarnC: Double get() = thermalProfile.clutchWarnC
    val clutchCritC: Double get() = thermalProfile.clutchCritC

    /** Display name for the current temperature preset. */
    val tempPresetName: String get() = when (tempPreset) {
        "race"  -> "Race"
        "track" -> "Track"
        "custom" -> if (customThermal != null) "Custom" else "Street"
        else    -> "Street"
    }

}

/**
 * Singleton store — load once from SharedPreferences, then observe [prefs].
 * UI calls [update] and the change propagates to all composables immediately.
 */
object UserPrefsStore {
    private val _prefs = MutableStateFlow(UserPrefs())
    val prefs: StateFlow<UserPrefs> = _prefs.asStateFlow()

    private var loaded = false

    @Synchronized fun load(ctx: Context) {
        _prefs.value = AppSettings.load(ctx)
        loaded = true
    }

    /**
     * Loads the saved prefs unless already loaded — for a start without the activity: CanDataService is START_STICKY,
     * so Android can recreate it after the process dies and it auto-connects (and auto-records) with no MainActivity.
     * The recorder and readiness must then see the saved thermal profile (incl. Custom), not the defaults.
     * Returns true when it loaded.
     */
    fun ensureLoaded(ctx: Context): Boolean = ensureLoaded { AppSettings.load(ctx) }

    @Synchronized internal fun ensureLoaded(read: () -> UserPrefs): Boolean {
        if (loaded) return false
        _prefs.value = read()
        loaded = true
        return true
    }

    /** Tests only: back to the process-start state (defaults, not loaded). */
    @Synchronized internal fun resetForTest() { loaded = false; _prefs.value = UserPrefs() }

    fun update(ctx: Context, block: (UserPrefs) -> UserPrefs) {
        // M-7 fix: use .update{} for atomic read-modify-write — prevents one rapid
        // tap (e.g. theme + preset) from silently overwriting the other.
        // Persist AFTER CAS succeeds so a retry never writes stale prefs to disk.
        var winner: UserPrefs? = null
        _prefs.update { current ->
            block(current).also { winner = it }
        }
        winner?.let { AppSettings.saveAll(ctx, it) }
    }
}
