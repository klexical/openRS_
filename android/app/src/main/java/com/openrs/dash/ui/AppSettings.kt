package com.openrs.dash.ui

import android.content.Context
import androidx.core.content.edit

/**
 * Thin SharedPreferences wrapper for all user-configurable app settings.
 * All reads/writes are synchronous and safe to call from any thread.
 */
object AppSettings {

    private const val PREFS = "openrs_settings"

    // ── Connection ──────────────────────────────────────────────────────────
    const val KEY_HOST = "wican_host"
    // Renamed from "wican_port" to discard cached ELM327 port (3333) on upgrade.
    const val KEY_PORT = "wican_port_ws"

    // WiCAN (stock / openRS_ firmware) defaults — WebSocket SLCAN on port 80
    const val DEFAULT_HOST = "192.168.80.1"
    const val DEFAULT_PORT = 80

    // MeatPi Pro defaults — raw TCP SLCAN.
    // IP:   192.168.0.10  (WiCAN Pro AP mode default, confirmed factory reset docs)
    // Port: 35000         (configurable in WiCAN Pro web UI; recommended default per
    //                      MeatPi examples — port 23 is also valid if set in web UI)
    const val DEFAULT_HOST_MEATPI = "192.168.0.10"
    const val DEFAULT_PORT_MEATPI = 35000

    // ── Units ───────────────────────────────────────────────────────────────
    const val KEY_SPEED_UNIT  = "speed_unit"    // "MPH" | "KPH"
    const val KEY_TEMP_UNIT   = "temp_unit"     // "F"   | "C"
    const val KEY_BOOST_UNIT  = "boost_unit"    // "PSI" | "BAR" | "KPA"
    const val KEY_TIRE_UNIT   = "tire_unit"     // "PSI" | "BAR"

    const val DEFAULT_SPEED_UNIT  = "MPH"
    const val DEFAULT_TEMP_UNIT   = "F"
    const val DEFAULT_BOOST_UNIT  = "PSI"
    const val DEFAULT_TIRE_UNIT   = "PSI"

    // ── TPMS ────────────────────────────────────────────────────────────────
    const val KEY_TIRE_LOW_PSI = "tire_low_psi"   // Low pressure warning threshold
    const val DEFAULT_TIRE_LOW_PSI = 30f

    // ── Display ─────────────────────────────────────────────────────────────
    const val KEY_SCREEN_ON = "screen_on"
    const val DEFAULT_SCREEN_ON = true

    // ── Auto-reconnect ──────────────────────────────────────────────────────
    const val KEY_AUTO_RECONNECT      = "auto_reconnect"
    const val KEY_RECONNECT_INTERVAL  = "reconnect_interval_sec"
    const val DEFAULT_AUTO_RECONNECT     = true
    const val DEFAULT_RECONNECT_INTERVAL = 10   // seconds

    // ── Diagnostics ──────────────────────────────────────────────────────────
    const val KEY_MAX_DIAG_ZIPS     = "max_diag_zips"
    const val DEFAULT_MAX_DIAG_ZIPS = 5          // keep last N ZIP exports

    // ── Theme ────────────────────────────────────────────────────────────────
    const val KEY_THEME_ID     = "theme_id"
    const val DEFAULT_THEME_ID = "cyan"          // RS Nitrous Blue default

    // ── Temperature preset ───────────────────────────────────────────────────
    const val KEY_TEMP_PRESET     = "temp_preset"
    const val DEFAULT_TEMP_PRESET = "street"     // "street" | "track" | "race"

    // ── Adapter type ─────────────────────────────────────────────────────────
    const val KEY_ADAPTER_TYPE     = "adapter_type"
    const val DEFAULT_ADAPTER_TYPE = "WICAN"     // "WICAN" | "MEATPI"

    // ── MeatPi microSD logging reminder ─────────────────────────────────────
    // SD logging on the WiCAN Pro is configured via its web UI (http://192.168.0.10/).
    // There is no SLCAN command to enable it remotely. This pref persists the user's
    // intent so we can surface a reminder/link in Settings.
    const val KEY_MEATPI_MICROSD     = "meatpi_microsd"
    const val DEFAULT_MEATPI_MICROSD = false

    // ── Read helpers ────────────────────────────────────────────────────────

    fun getHost(ctx: Context): String {
        val p = prefs(ctx)
        val stored = p.getString(KEY_HOST, null)
        if (stored != null) return stored
        return if (getAdapterType(ctx) == "MEATPI") DEFAULT_HOST_MEATPI else DEFAULT_HOST
    }

    fun getPort(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.contains(KEY_PORT)) return p.getInt(KEY_PORT, DEFAULT_PORT)
        return if (getAdapterType(ctx) == "MEATPI") DEFAULT_PORT_MEATPI else DEFAULT_PORT
    }

    fun getSpeedUnit(ctx: Context): String =
        prefs(ctx).getString(KEY_SPEED_UNIT, DEFAULT_SPEED_UNIT) ?: DEFAULT_SPEED_UNIT

    fun getTempUnit(ctx: Context): String =
        prefs(ctx).getString(KEY_TEMP_UNIT, DEFAULT_TEMP_UNIT) ?: DEFAULT_TEMP_UNIT

    fun getBoostUnit(ctx: Context): String =
        prefs(ctx).getString(KEY_BOOST_UNIT, DEFAULT_BOOST_UNIT) ?: DEFAULT_BOOST_UNIT

    fun getTireUnit(ctx: Context): String =
        prefs(ctx).getString(KEY_TIRE_UNIT, DEFAULT_TIRE_UNIT) ?: DEFAULT_TIRE_UNIT

    fun getTireLowPsi(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_TIRE_LOW_PSI, DEFAULT_TIRE_LOW_PSI)

    fun getScreenOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SCREEN_ON, DEFAULT_SCREEN_ON)

    fun getAutoReconnect(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT)

    fun getReconnectInterval(ctx: Context): Int =
        prefs(ctx).getInt(KEY_RECONNECT_INTERVAL, DEFAULT_RECONNECT_INTERVAL)

    fun getMaxDiagZips(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MAX_DIAG_ZIPS, DEFAULT_MAX_DIAG_ZIPS)

    fun getThemeId(ctx: Context): String =
        prefs(ctx).getString(KEY_THEME_ID, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID

    fun getTempPreset(ctx: Context): String =
        prefs(ctx).getString(KEY_TEMP_PRESET, DEFAULT_TEMP_PRESET) ?: DEFAULT_TEMP_PRESET

    fun getAdapterType(ctx: Context): String =
        prefs(ctx).getString(KEY_ADAPTER_TYPE, DEFAULT_ADAPTER_TYPE) ?: DEFAULT_ADAPTER_TYPE

    fun getMeatPiMicroSd(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MEATPI_MICROSD, DEFAULT_MEATPI_MICROSD)

    // ── Write helpers ────────────────────────────────────────────────────────

    fun save(ctx: Context, host: String, port: Int) {
        prefs(ctx).edit {
            putString(KEY_HOST, host.trim())
            putInt(KEY_PORT, port)
        }
    }

    fun saveAll(ctx: Context, p: UserPrefs) {
        prefs(ctx).edit {
            putString(KEY_SPEED_UNIT,  p.speedUnit)
            putString(KEY_TEMP_UNIT,   p.tempUnit)
            putString(KEY_BOOST_UNIT,  p.boostUnit)
            putString(KEY_TIRE_UNIT,   p.tireUnit)
            putFloat (KEY_TIRE_LOW_PSI, p.tireLowPsi)
            putBoolean(KEY_SCREEN_ON,  p.screenOn)
            putBoolean(KEY_AUTO_RECONNECT,    p.autoReconnect)
            putInt   (KEY_RECONNECT_INTERVAL, p.reconnectIntervalSec)
            putInt   (KEY_MAX_DIAG_ZIPS,      p.maxDiagZips)
            putString(KEY_THEME_ID,    p.themeId)
            putString(KEY_TEMP_PRESET, p.tempPreset)
            putString(KEY_ADAPTER_TYPE, p.adapterType)
            putBoolean(KEY_MEATPI_MICROSD, p.meatPiMicroSdLog)
        }
    }

    fun load(ctx: Context): UserPrefs = UserPrefs(
        speedUnit            = getSpeedUnit(ctx),
        tempUnit             = getTempUnit(ctx),
        boostUnit            = getBoostUnit(ctx),
        tireUnit             = getTireUnit(ctx),
        tireLowPsi           = getTireLowPsi(ctx),
        screenOn             = getScreenOn(ctx),
        autoReconnect        = getAutoReconnect(ctx),
        reconnectIntervalSec = getReconnectInterval(ctx),
        maxDiagZips          = getMaxDiagZips(ctx),
        themeId              = getThemeId(ctx),
        tempPreset           = getTempPreset(ctx),
        adapterType          = getAdapterType(ctx),
        meatPiMicroSdLog     = getMeatPiMicroSd(ctx)
    )

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
