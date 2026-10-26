package com.openrs.dash.ui

import android.content.Context
import androidx.compose.ui.graphics.Color
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * User-configurable display and connection preferences.
 * Observable via [UserPrefsStore.prefs] so all Compose UI reacts to changes instantly.
 */
/** Unit conversion constants — shared across UI. */
object UnitConversions {
    const val KM_TO_MI = 0.621371
    const val STD_ATM_KPA = 101.325
    const val KPA_TO_PSI = 0.14503773
    const val PSI_TO_BAR = 0.0689476
}

data class UserPrefs(
    val speedUnit: String           = AppSettings.DEFAULT_SPEED_UNIT,   // "MPH" | "KPH"
    val tempUnit: String            = AppSettings.DEFAULT_TEMP_UNIT,    // "F"   | "C"
    val boostUnit: String           = AppSettings.DEFAULT_BOOST_UNIT,   // "PSI" | "BAR" | "KPA"
    val tireUnit: String            = AppSettings.DEFAULT_TIRE_UNIT,    // "PSI" | "BAR"
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
    val odomInMiles: Boolean        = speedUnit == "MPH",                 // Odometer display: true=mi, false=km
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

    fun displayTemp(celsius: Double): String {
        return if (tempUnit == "C") "%.0f".format(celsius)
        else "%.0f".format(celsius * 9.0 / 5.0 + 32)
    }

    val tempLabel: String get() = if (tempUnit == "C") "°C" else "°F"

    /**
     * A limit or target: whole numbers stay whole, others keep one decimal (custom 104.44 °C → "104.4",
     * warn − 2 °C in °F → "244.4") — the same text on gauge legends, THERMAL's reason, the checklist and DRIVE.
     */
    fun displayLimit(celsius: Double): String {
        val x = if (tempUnit == "C") celsius else celsius * 9.0 / 5.0 + 32
        return (if (kotlin.math.abs(x - Math.round(x)) < 0.05) "%.0f" else "%.1f").format(x)
    }

    /** A temperature difference (headroom, margin): °F = Δ°C × 1.8 — no +32 offset. */
    fun displayTempDelta(deltaC: Double): String =
        "%.0f".format(if (tempUnit == "C") deltaC else deltaC * 9.0 / 5.0)

    /** A rate of change, °C/min or °F/min (× 1.8, no offset), one decimal. */
    fun displayTempRate(cPerMin: Double): String =
        "%.1f".format(if (tempUnit == "C") cPerMin else cPerMin * 9.0 / 5.0)

    fun displaySpeed(kph: Double): String {
        return if (speedUnit == "KPH") "%.0f".format(kph)
        else "%.0f".format(kph * UnitConversions.KM_TO_MI)
    }

    val speedLabel: String get() = speedUnit  // "KPH" or "MPH"

    /** Boost pressure from absolute kPa. Returns (value, label) pair. */
    fun displayBoost(boostKpa: Double): Pair<String, String> {
        val psi = (boostKpa - UnitConversions.STD_ATM_KPA) * UnitConversions.KPA_TO_PSI
        return when (boostUnit) {
            "BAR" -> "%.2f".format(psi * UnitConversions.PSI_TO_BAR) to "BAR"
            "KPA" -> "%.0f".format(boostKpa - UnitConversions.STD_ATM_KPA) to "kPa"
            else  -> "%.1f".format(psi) to "PSI"
        }
    }

    /** Tire pressure in PSI → display value and label (1 decimal place for accuracy). */
    fun displayTire(psi: Double): String {
        return if (tireUnit == "BAR") "%.2f".format(psi * UnitConversions.PSI_TO_BAR)
        else "%.1f".format(psi)
    }

    val tireLabel: String get() = tireUnit  // "PSI" or "BAR"

    /** Low-pressure threshold in whichever unit is selected. */
    val tireLowDisplay: Float
        get() = if (tireUnit == "BAR") (tireLowPsi * UnitConversions.PSI_TO_BAR.toFloat()) else tireLowPsi

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

    fun load(ctx: Context) {
        _prefs.value = AppSettings.load(ctx)
    }

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
