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
data class UserPrefs(
    val speedUnit: String           = AppSettings.DEFAULT_SPEED_UNIT,   // "MPH" | "KPH"
    val tempUnit: String            = AppSettings.DEFAULT_TEMP_UNIT,    // "F"   | "C"
    val boostUnit: String           = AppSettings.DEFAULT_BOOST_UNIT,   // "PSI" | "BAR" | "KPA"
    val tireUnit: String            = AppSettings.DEFAULT_TIRE_UNIT,    // "PSI" | "BAR"
    val tireLowPsi: Float           = AppSettings.DEFAULT_TIRE_LOW_PSI,
    val screenOn: Boolean           = AppSettings.DEFAULT_SCREEN_ON,
    val autoReconnect: Boolean      = AppSettings.DEFAULT_AUTO_RECONNECT,
    val reconnectIntervalSec: Int   = AppSettings.DEFAULT_RECONNECT_INTERVAL,
    val maxDiagZips: Int            = AppSettings.DEFAULT_MAX_DIAG_ZIPS, // max diagnostic ZIPs to keep
    val themeId: String             = AppSettings.DEFAULT_THEME_ID,      // RS paint color theme
    val tempPreset: String          = AppSettings.DEFAULT_TEMP_PRESET,   // "street"|"track"|"race"
    val adapterType: String         = AppSettings.DEFAULT_ADAPTER_TYPE,  // "WICAN" | "MEATPI"
    val meatPiMicroSdLog: Boolean   = false                               // MeatPi Pro microSD logging
) {
    // ── Unit-conversion helpers used by UI ─────────────────────────────────

    fun displayTemp(celsius: Double): String {
        return if (tempUnit == "C") "%.0f".format(celsius)
        else "%.0f".format(celsius * 9.0 / 5.0 + 32)
    }

    val tempLabel: String get() = if (tempUnit == "C") "°C" else "°F"

    fun displaySpeed(kph: Double): String {
        return if (speedUnit == "KPH") "%.0f".format(kph)
        else "%.0f".format(kph * 0.621371)
    }

    val speedLabel: String get() = speedUnit  // "KPH" or "MPH"

    /** Boost pressure from absolute kPa. Returns (value, label) pair. */
    fun displayBoost(boostKpa: Double): Pair<String, String> {
        val psi = (boostKpa - 101.325) * 0.14503773
        return when (boostUnit) {
            "BAR" -> "%.2f".format(psi * 0.0689476) to "BAR"
            "KPA" -> "%.0f".format(boostKpa - 101.325) to "kPa"
            else  -> "%.1f".format(psi) to "PSI"
        }
    }

    /** Tire pressure in PSI → display value and label (1 decimal place for accuracy). */
    fun displayTire(psi: Double): String {
        return if (tireUnit == "BAR") "%.2f".format(psi * 0.0689476)
        else "%.1f".format(psi)
    }

    val tireLabel: String get() = tireUnit  // "PSI" or "BAR"

    /** Low-pressure threshold in whichever unit is selected. */
    val tireLowDisplay: Float
        get() = if (tireUnit == "BAR") (tireLowPsi * 0.0689476f) else tireLowPsi

    /** Whether a raw PSI value is below the warning threshold. */
    fun isTireLow(psi: Double): Boolean = psi in 0.01..tireLowPsi.toDouble()

    // ── Theme system ────────────────────────────────────────────────────────

    /** Primary accent color for this RS paint theme (actual MK3 paint catalogue). */
    val themeAccent: Color get() = rsPaintAccent(themeId)

    /** Display name for the current theme. */
    val themeName: String get() = rsPaintName(themeId)

    // ── Temperature thresholds (preset-based) ───────────────────────────────

    /** Oil warn / crit thresholds in °C based on selected preset. */
    val oilWarnC: Double  get() = when (tempPreset) { "race" -> 100.0; "track" -> 110.0; else -> 120.0 }
    val oilCritC: Double  get() = when (tempPreset) { "race" -> 110.0; "track" -> 120.0; else -> 130.0 }

    /** Coolant warn / crit thresholds in °C. */
    val coolWarnC: Double get() = when (tempPreset) { "race" -> 95.0;  "track" -> 100.0; else -> 105.0 }
    val coolCritC: Double get() = when (tempPreset) { "race" -> 105.0; "track" -> 110.0; else -> 115.0 }

    /** Intake air warn / crit thresholds in °C. */
    val intakeWarnC: Double get() = when (tempPreset) { "race" -> 45.0; "track" -> 55.0; else -> 65.0 }
    val intakeCritC: Double get() = when (tempPreset) { "race" -> 55.0; "track" -> 65.0; else -> 80.0 }

    /** RDU/PTU warn / crit thresholds in °C. */
    val rduWarnC: Double  get() = when (tempPreset) { "race" -> 70.0;  "track" -> 80.0;  else -> 90.0 }
    val rduCritC: Double  get() = when (tempPreset) { "race" -> 85.0;  "track" -> 95.0;  else -> 100.0 }
    // PTU (transfer case) runs hotter than RDU — separate thresholds
    val ptuWarnC: Double  get() = when (tempPreset) { "race" -> 75.0;  "track" -> 85.0;  else -> 95.0 }
    val ptuCritC: Double  get() = when (tempPreset) { "race" -> 90.0;  "track" -> 100.0; else -> 110.0 }

    /** Display name for the current temperature preset. */
    val tempPresetName: String get() = when (tempPreset) {
        "race"  -> "Race"
        "track" -> "Track"
        else    -> "Street"
    }

    /** RTR check: are all critical temps below their warm-up thresholds for the current preset? */
    fun isRaceReady(oilC: Double, coolantC: Double): Boolean {
        val oilMin     = when (tempPreset) { "race" -> 85.0; "track" -> 80.0; else -> 70.0 }
        val coolantMin = when (tempPreset) { "race" -> 80.0; "track" -> 75.0; else -> 70.0 }
        // Treat sentinel -99 as "not yet received" — skip check rather than blocking warm cars
        val oilOk     = oilC <= -90     || oilC >= oilMin
        val coolantOk = coolantC <= -90 || coolantC >= coolantMin
        return oilOk && coolantOk
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
        _prefs.update { current ->
            val newPrefs = block(current)
            AppSettings.saveAll(ctx, newPrefs)
            newPrefs
        }
    }
}
