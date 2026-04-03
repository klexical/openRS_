package com.openrs.dash.ui

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.edit
import com.openrs.dash.data.DashCell
import com.openrs.dash.data.DashLayout
import org.json.JSONArray
import org.json.JSONObject

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
    const val KEY_TIRE_LOW_PSI  = "tire_low_psi"    // Critical low threshold
    const val DEFAULT_TIRE_LOW_PSI  = 30f
    const val KEY_TIRE_WARN_PSI = "tire_warn_psi"   // Warn (getting low) threshold
    const val DEFAULT_TIRE_WARN_PSI = 34f
    const val KEY_TIRE_HIGH_PSI = "tire_high_psi"   // Over-inflated threshold
    const val DEFAULT_TIRE_HIGH_PSI = 50f            // Ford track max (cold)

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

    // ── Drives ──────────────────────────────────────────────────────────────
    const val KEY_AUTO_RECORD_DRIVES     = "auto_record_drives"
    const val DEFAULT_AUTO_RECORD_DRIVES = false    // opt-in: auto-start on connect
    const val KEY_MAX_SAVED_DRIVES       = "max_saved_drives"
    const val DEFAULT_MAX_SAVED_DRIVES   = 50       // oldest pruned when exceeded

    // ── Theme ────────────────────────────────────────────────────────────────
    const val KEY_THEME_ID     = "theme_id"
    const val DEFAULT_THEME_ID = "cyan"          // RS Nitrous Blue default

    // ── Brightness ──────────────────────────────────────────────────────────
    const val KEY_BRIGHTNESS     = "brightness"
    const val DEFAULT_BRIGHTNESS = 0f            // 0.0=Night, 0.5=Day, 1.0=Sun

    // ── Temperature preset ───────────────────────────────────────────────────
    const val KEY_TEMP_PRESET     = "temp_preset"
    const val DEFAULT_TEMP_PRESET = "street"     // "street" | "track" | "race"

    // ── Adapter type ─────────────────────────────────────────────────────────
    const val KEY_ADAPTER_TYPE     = "adapter_type"
    const val DEFAULT_ADAPTER_TYPE = "MEATPI_USB"  // "MEATPI_USB" | "MEATPI_PRO"

    // ── Connection method ────────────────────────────────────────────────────
    const val KEY_CONNECTION_METHOD     = "connection_method"
    const val DEFAULT_CONNECTION_METHOD = "WIFI"   // "WIFI" | "BLUETOOTH"

    // ── BLE device ──────────────────────────────────────────────────────────
    const val KEY_BLE_DEVICE_ADDRESS = "ble_device_address"
    const val KEY_BLE_DEVICE_NAME    = "ble_device_name"

    // ── MeatPi microSD logging reminder ─────────────────────────────────────
    // SD logging on the WiCAN Pro is configured via its web UI (http://192.168.0.10/).
    // There is no SLCAN command to enable it remotely. This pref persists the user's
    // intent so we can surface a reminder/link in Settings.
    const val KEY_MEATPI_MICROSD     = "meatpi_microsd"
    const val DEFAULT_MEATPI_MICROSD = false

    // ── Peripheral shift light ──────────────────────────────────────────
    const val KEY_EDGE_SHIFT_LIGHT     = "edge_shift_light"
    const val DEFAULT_EDGE_SHIFT_LIGHT = false

    const val KEY_EDGE_SHIFT_COLOR     = "edge_shift_color"
    const val DEFAULT_EDGE_SHIFT_COLOR = "accent"    // "accent" | "white" | "progressive"

    const val KEY_EDGE_SHIFT_INTENSITY     = "edge_shift_intensity"
    const val DEFAULT_EDGE_SHIFT_INTENSITY = "high"  // "low" | "med" | "high"

    const val KEY_EDGE_SHIFT_RPM     = "edge_shift_rpm"
    const val DEFAULT_EDGE_SHIFT_RPM = 6800

    // ── App updates ────────────────────────────────────────────────────────
    const val KEY_UPDATE_CHANNEL     = "update_channel"
    const val DEFAULT_UPDATE_CHANNEL = "stable"    // "stable" | "beta"

    // ── Odometer display ──────────────────────────────────────────────────
    const val KEY_ODOM_IN_MILES = "odom_in_miles"

    // ── What's New ──────────────────────────────────────────────────────
    const val KEY_LAST_SEEN_VERSION = "last_seen_version"

    // ── Custom dashboard ────────────────────────────────────────────────
    const val KEY_CUSTOM_DASH = "custom_dash_json"

    // ── Read helpers ────────────────────────────────────────────────────────

    fun getHost(ctx: Context): String {
        val p = prefs(ctx)
        val stored = p.getString(KEY_HOST, null)
        if (stored != null) return stored
        return if (getAdapterType(ctx) == "MEATPI_PRO") DEFAULT_HOST_MEATPI else DEFAULT_HOST
    }

    fun getPort(ctx: Context): Int {
        val p = prefs(ctx)
        if (p.contains(KEY_PORT)) return p.getInt(KEY_PORT, DEFAULT_PORT)
        return if (getAdapterType(ctx) == "MEATPI_PRO") DEFAULT_PORT_MEATPI else DEFAULT_PORT
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

    fun getTireWarnPsi(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_TIRE_WARN_PSI, DEFAULT_TIRE_WARN_PSI)

    fun getTireHighPsi(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_TIRE_HIGH_PSI, DEFAULT_TIRE_HIGH_PSI)

    fun getScreenOn(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_SCREEN_ON, DEFAULT_SCREEN_ON)

    fun getAutoReconnect(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_RECONNECT, DEFAULT_AUTO_RECONNECT)

    fun getReconnectInterval(ctx: Context): Int =
        prefs(ctx).getInt(KEY_RECONNECT_INTERVAL, DEFAULT_RECONNECT_INTERVAL)

    fun getMaxDiagZips(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MAX_DIAG_ZIPS, DEFAULT_MAX_DIAG_ZIPS)

    fun getAutoRecordDrives(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_AUTO_RECORD_DRIVES, DEFAULT_AUTO_RECORD_DRIVES)

    fun getMaxSavedDrives(ctx: Context): Int =
        prefs(ctx).getInt(KEY_MAX_SAVED_DRIVES, DEFAULT_MAX_SAVED_DRIVES)

    fun getThemeId(ctx: Context): String =
        prefs(ctx).getString(KEY_THEME_ID, DEFAULT_THEME_ID) ?: DEFAULT_THEME_ID

    fun getBrightness(ctx: Context): Float =
        prefs(ctx).getFloat(KEY_BRIGHTNESS, DEFAULT_BRIGHTNESS)

    fun getTempPreset(ctx: Context): String =
        prefs(ctx).getString(KEY_TEMP_PRESET, DEFAULT_TEMP_PRESET) ?: DEFAULT_TEMP_PRESET

    fun getAdapterType(ctx: Context): String {
        val raw = prefs(ctx).getString(KEY_ADAPTER_TYPE, null) ?: return DEFAULT_ADAPTER_TYPE
        // Migrate legacy values from v2.2.6-rc.6 and earlier
        return when (raw) {
            "WICAN"     -> "MEATPI_USB"
            "MEATPI"    -> "MEATPI_PRO"
            "BLUETOOTH" -> "MEATPI_USB"   // legacy: was a separate adapter type
            else        -> raw
        }
    }

    fun getConnectionMethod(ctx: Context): String {
        val p = prefs(ctx)
        if (p.contains(KEY_CONNECTION_METHOD)) {
            return p.getString(KEY_CONNECTION_METHOD, DEFAULT_CONNECTION_METHOD) ?: DEFAULT_CONNECTION_METHOD
        }
        // Legacy migration: if adapter was "BLUETOOTH", infer BLUETOOTH connection
        val rawAdapter = p.getString(KEY_ADAPTER_TYPE, null)
        return if (rawAdapter == "BLUETOOTH") "BLUETOOTH" else DEFAULT_CONNECTION_METHOD
    }

    fun getMeatPiMicroSd(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_MEATPI_MICROSD, DEFAULT_MEATPI_MICROSD)

    fun getLastSeenVersion(ctx: Context): String =
        prefs(ctx).getString(KEY_LAST_SEEN_VERSION, "") ?: ""

    fun setLastSeenVersion(ctx: Context, version: String) {
        prefs(ctx).edit { putString(KEY_LAST_SEEN_VERSION, version) }
    }

    fun getEdgeShiftLight(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_EDGE_SHIFT_LIGHT, DEFAULT_EDGE_SHIFT_LIGHT)

    fun getEdgeShiftColor(ctx: Context): String =
        prefs(ctx).getString(KEY_EDGE_SHIFT_COLOR, DEFAULT_EDGE_SHIFT_COLOR) ?: DEFAULT_EDGE_SHIFT_COLOR

    fun getEdgeShiftIntensity(ctx: Context): String =
        prefs(ctx).getString(KEY_EDGE_SHIFT_INTENSITY, DEFAULT_EDGE_SHIFT_INTENSITY) ?: DEFAULT_EDGE_SHIFT_INTENSITY

    fun getEdgeShiftRpm(ctx: Context): Int =
        prefs(ctx).getInt(KEY_EDGE_SHIFT_RPM, DEFAULT_EDGE_SHIFT_RPM)

    fun getUpdateChannel(ctx: Context): String =
        prefs(ctx).getString(KEY_UPDATE_CHANNEL, DEFAULT_UPDATE_CHANNEL) ?: DEFAULT_UPDATE_CHANNEL

    fun getOdomInMiles(ctx: Context): Boolean {
        val p = prefs(ctx)
        // Backward compat: if key absent, derive from speed unit.
        return if (p.contains(KEY_ODOM_IN_MILES)) p.getBoolean(KEY_ODOM_IN_MILES, true)
        else getSpeedUnit(ctx) == "MPH"
    }

    // ── BLE device helpers ──────────────────────────────────────────────────

    fun getBleDeviceAddress(ctx: Context): String? =
        prefs(ctx).getString(KEY_BLE_DEVICE_ADDRESS, null)

    fun getBleDeviceName(ctx: Context): String? =
        prefs(ctx).getString(KEY_BLE_DEVICE_NAME, null)

    fun saveBleDevice(ctx: Context, address: String, name: String) {
        prefs(ctx).edit {
            putString(KEY_BLE_DEVICE_ADDRESS, address)
            putString(KEY_BLE_DEVICE_NAME, name)
        }
    }

    fun clearBleDevice(ctx: Context) {
        prefs(ctx).edit {
            remove(KEY_BLE_DEVICE_ADDRESS)
            remove(KEY_BLE_DEVICE_NAME)
        }
    }

    // ── Write helpers ────────────────────────────────────────────────────────

    /**
     * Saves connection endpoint only (host and port).
     * Called from SettingsSheet when the user edits the connection address.
     *
     * NOTE: intentionally does NOT write [UserPrefs] fields (units, theme, etc.).
     * Those are persisted by [saveAll]. The two methods cover disjoint key sets.
     */
    fun save(ctx: Context, host: String, port: Int) {
        prefs(ctx).edit {
            putString(KEY_HOST, host.trim())
            putInt(KEY_PORT, port)
        }
    }

    /**
     * Saves all [UserPrefs] display and behaviour fields.
     * Called from [UserPrefsStore.update] on every preference change.
     *
     * NOTE: intentionally does NOT write host/port — those are managed by [save].
     * The two methods cover disjoint key sets.
     */
    fun saveAll(ctx: Context, p: UserPrefs) {
        prefs(ctx).edit {
            putString(KEY_SPEED_UNIT,  p.speedUnit)
            putString(KEY_TEMP_UNIT,   p.tempUnit)
            putString(KEY_BOOST_UNIT,  p.boostUnit)
            putString(KEY_TIRE_UNIT,   p.tireUnit)
            putFloat (KEY_TIRE_LOW_PSI,  p.tireLowPsi)
            putFloat (KEY_TIRE_WARN_PSI, p.tireWarnPsi)
            putFloat (KEY_TIRE_HIGH_PSI, p.tireHighPsi)
            putBoolean(KEY_SCREEN_ON,  p.screenOn)
            putBoolean(KEY_AUTO_RECONNECT,    p.autoReconnect)
            putInt   (KEY_RECONNECT_INTERVAL, p.reconnectIntervalSec)
            putInt   (KEY_MAX_DIAG_ZIPS,      p.maxDiagZips)
            putString(KEY_THEME_ID,    p.themeId)
            putString(KEY_TEMP_PRESET, p.tempPreset)
            putString(KEY_ADAPTER_TYPE, p.adapterType)
            putString(KEY_CONNECTION_METHOD, p.connectionMethod)
            putBoolean(KEY_MEATPI_MICROSD, p.meatPiMicroSdLog)
            putBoolean(KEY_ODOM_IN_MILES, p.odomInMiles)
            putBoolean(KEY_EDGE_SHIFT_LIGHT, p.edgeShiftLight)
            putString (KEY_EDGE_SHIFT_COLOR, p.edgeShiftColor)
            putString (KEY_EDGE_SHIFT_INTENSITY, p.edgeShiftIntensity)
            putInt    (KEY_EDGE_SHIFT_RPM, p.edgeShiftRpm)
            putBoolean(KEY_AUTO_RECORD_DRIVES, p.autoRecordDrives)
            putInt    (KEY_MAX_SAVED_DRIVES, p.maxSavedDrives)
            putString (KEY_UPDATE_CHANNEL, p.updateChannel)
            putFloat  (KEY_BRIGHTNESS, p.brightness)
        }
    }

    fun load(ctx: Context): UserPrefs = UserPrefs(
        speedUnit            = getSpeedUnit(ctx),
        tempUnit             = getTempUnit(ctx),
        boostUnit            = getBoostUnit(ctx),
        tireUnit             = getTireUnit(ctx),
        tireLowPsi           = getTireLowPsi(ctx),
        tireWarnPsi          = getTireWarnPsi(ctx),
        tireHighPsi          = getTireHighPsi(ctx),
        screenOn             = getScreenOn(ctx),
        autoReconnect        = getAutoReconnect(ctx),
        reconnectIntervalSec = getReconnectInterval(ctx),
        maxDiagZips          = getMaxDiagZips(ctx),
        themeId              = getThemeId(ctx),
        tempPreset           = getTempPreset(ctx),
        adapterType          = getAdapterType(ctx),
        connectionMethod     = getConnectionMethod(ctx),
        meatPiMicroSdLog     = getMeatPiMicroSd(ctx),
        odomInMiles          = getOdomInMiles(ctx),
        edgeShiftLight       = getEdgeShiftLight(ctx),
        edgeShiftColor       = getEdgeShiftColor(ctx),
        edgeShiftIntensity   = getEdgeShiftIntensity(ctx),
        edgeShiftRpm         = getEdgeShiftRpm(ctx),
        autoRecordDrives     = getAutoRecordDrives(ctx),
        maxSavedDrives       = getMaxSavedDrives(ctx),
        updateChannel        = getUpdateChannel(ctx),
        brightness           = getBrightness(ctx)
    )

    // ── Custom dashboard persistence ────────────────────────────────────

    /** Serialize a [DashLayout] to JSON and persist to SharedPreferences. */
    fun saveCustomDash(ctx: Context, layout: DashLayout) {
        val arr = JSONArray()
        for (cell in layout.cells) {
            val obj = JSONObject()
            obj.put("fieldKey", cell.fieldKey)
            obj.put("displayType", cell.displayType)
            obj.put("label", cell.label)
            arr.put(obj)
        }
        val root = JSONObject()
        root.put("name", layout.name)
        root.put("cells", arr)
        prefs(ctx).edit { putString(KEY_CUSTOM_DASH, root.toString()) }
    }

    /** Load the persisted custom dashboard layout, or null if none saved. */
    fun loadCustomDash(ctx: Context): DashLayout? {
        val json = prefs(ctx).getString(KEY_CUSTOM_DASH, null) ?: return null
        return try {
            val root = JSONObject(json)
            val arr = root.getJSONArray("cells")
            val cells = mutableListOf<DashCell>()
            for (i in 0 until arr.length()) {
                val obj = arr.getJSONObject(i)
                cells += DashCell(
                    fieldKey = obj.getString("fieldKey"),
                    displayType = obj.getString("displayType"),
                    label = obj.getString("label")
                )
            }
            DashLayout(name = root.optString("name", "Custom"), cells = cells)
        } catch (_: Exception) {
            null
        }
    }

    // ── Collapsible section persistence ───────────────────────────────────
    fun isSectionExpanded(ctx: Context, key: String, default: Boolean = true): Boolean =
        prefs(ctx).getBoolean("section_$key", default)

    fun setSectionExpanded(ctx: Context, key: String, expanded: Boolean) =
        prefs(ctx).edit { putBoolean("section_$key", expanded) }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}

/** Returns a [MutableState] backed by SharedPreferences for collapsible section state. */
@Composable
fun rememberSectionExpanded(key: String, default: Boolean = true): MutableState<Boolean> {
    val ctx = LocalContext.current
    val state = remember { mutableStateOf(AppSettings.isSectionExpanded(ctx, key, default)) }
    LaunchedEffect(state.value) {
        AppSettings.setSectionExpanded(ctx, key, state.value)
    }
    return state
}
