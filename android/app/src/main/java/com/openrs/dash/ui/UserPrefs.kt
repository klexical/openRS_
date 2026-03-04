package com.openrs.dash.ui

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
    val reconnectIntervalSec: Int   = AppSettings.DEFAULT_RECONNECT_INTERVAL
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
        val newPrefs = block(_prefs.value)
        _prefs.value = newPrefs
        AppSettings.saveAll(ctx, newPrefs)
    }
}
