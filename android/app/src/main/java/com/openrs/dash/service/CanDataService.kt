package com.openrs.dash.service

import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.IBinder
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.WiCanConnection
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.ui.UserPrefsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CanDataService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectionJob: Job? = null

    private var wican: WiCanConnection = WiCanConnection()

    val connectionState: StateFlow<WiCanConnection.State> get() = wican.state

    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    private fun buildWiCan(): WiCanConnection {
        val s = com.openrs.dash.ui.AppSettings
        val autoReconnect = s.getAutoReconnect(this)
        return WiCanConnection(
            host             = s.getHost(this),
            port             = s.getPort(this),
            maxRetries       = if (autoReconnect) 3 else 1,
            reconnectDelayMs = s.getReconnectInterval(this) * 1_000L
        )
    }

    inner class LocalBinder : Binder() {
        fun getService(): CanDataService = this@CanDataService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        wican = buildWiCan()
        registerWifiCallback()
        if (isOnWifi()) startConnection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterWifiCallback()
        stopConnection()   // flush sessionEnd() before scope is torn down
        scope.cancel()
        super.onDestroy()
    }

    // ── WiFi gating ─────────────────────────────────────────────────────────

    private fun isOnWifi(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    private fun registerWifiCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // WiFi gained — reset and attempt connection if not already active
                if (connectionJob?.isActive != true) startConnection()
            }
            override fun onLost(network: Network) {
                stopConnection()
            }
        }
        try { cm.registerNetworkCallback(request, wifiCallback!!) } catch (_: Exception) {}
    }

    private fun unregisterWifiCallback() {
        wifiCallback?.let { cb ->
            try { cm.unregisterNetworkCallback(cb) } catch (_: Exception) {}
        }
        wifiCallback = null
    }

    // ── Connection control ───────────────────────────────────────────────────

    @Synchronized fun startConnection() {
        if (!isOnWifi()) return
        if (connectionJob?.isActive == true) return
        wican = buildWiCan()

        // Start diagnostic session (logDir enables Option C SLCAN raw log)
        val s = com.openrs.dash.ui.AppSettings
        DiagnosticLogger.sessionStart(
            host   = s.getHost(this),
            port   = s.getPort(this),
            prefs  = UserPrefsStore.prefs.value,
            logDir = java.io.File(filesDir, "diagnostics")
        )
        CanDecoder.resetSessionState()

        connectionJob = scope.launch {
            launch {
                wican.state.collect { state ->
                    OpenRSDashApp.instance.vehicleState.update {
                        it.copy(
                            isConnected = state is WiCanConnection.State.Connected,
                            isIdle      = state is WiCanConnection.State.Idle
                        )
                    }
                    DiagnosticLogger.event("STATE", state::class.simpleName ?: "Unknown")
                }
            }

            // Use hybrid mode: ATMA + OBD PID queries
            wican.connectHybrid(
                onObdUpdate = { obdState ->
                    // Merge OBD fields into current state (don't overwrite CAN fields)
                    OpenRSDashApp.instance.vehicleState.update { current ->
                        current.copy(
                            // Mode 1
                            calcLoad = obdState.calcLoad,
                            shortFuelTrim = obdState.shortFuelTrim,
                            longFuelTrim = obdState.longFuelTrim,
                            timingAdvance = obdState.timingAdvance,
                            fuelRailPressure = obdState.fuelRailPressure,
                            barometricPressure = obdState.barometricPressure,
                            commandedAfr = obdState.commandedAfr,
                            o2Voltage = obdState.o2Voltage,
                            afrSensor1 = obdState.afrSensor1,
                            // Mode 22 — PCM temps
                            chargeAirTempC = obdState.chargeAirTempC,
                            manifoldChargeTempC = obdState.manifoldChargeTempC,
                            octaneAdjustRatio = obdState.octaneAdjustRatio,
                            catalyticTempC = obdState.catalyticTempC,
                            // Mode 22 — AFR
                            afrActual = obdState.afrActual,
                            afrDesired = obdState.afrDesired,
                            lambdaActual = obdState.lambdaActual,
                            // Mode 22 — ETC / TIP / WGDC
                            etcAngleActual = obdState.etcAngleActual,
                            etcAngleDesired = obdState.etcAngleDesired,
                            tipActualKpa = obdState.tipActualKpa,
                            tipDesiredKpa = obdState.tipDesiredKpa,
                            wgdcDesired = obdState.wgdcDesired,
                            // Mode 22 — VCT
                            vctIntakeAngle = obdState.vctIntakeAngle,
                            vctExhaustAngle = obdState.vctExhaustAngle,
                            // Mode 22 — Oil / Knock
                            oilLifePct = obdState.oilLifePct,
                            ignCorrCyl1 = obdState.ignCorrCyl1,
                            // Mode 22 — TPMS (BCM). Use sentinel guard: only overwrite if
                            // the OBD value is valid (≥ 0). This prevents a BCM response
                            // captured before the first passive 0x340 frame from resetting
                            // tire pressures back to the -1.0 default.
                            tirePressLF = if (obdState.tirePressLF >= 0) obdState.tirePressLF else current.tirePressLF,
                            tirePressRF = if (obdState.tirePressRF >= 0) obdState.tirePressRF else current.tirePressRF,
                            tirePressLR = if (obdState.tirePressLR >= 0) obdState.tirePressLR else current.tirePressLR,
                            tirePressRR = if (obdState.tirePressRR >= 0) obdState.tirePressRR else current.tirePressRR,
                            tireTempLF = if (obdState.tireTempLF > -90) obdState.tireTempLF else current.tireTempLF,
                            tireTempRF = if (obdState.tireTempRF > -90) obdState.tireTempRF else current.tireTempRF,
                            tireTempLR = if (obdState.tireTempLR > -90) obdState.tireTempLR else current.tireTempLR,
                            tireTempRR = if (obdState.tireTempRR > -90) obdState.tireTempRR else current.tireTempRR,
                            // BCM OBD Mode 22 — new PIDs (sentinel check: only overwrite if valid)
                            odometerKm   = if (obdState.odometerKm  >= 0)   obdState.odometerKm   else current.odometerKm,
                            batterySoc   = if (obdState.batterySoc  >= 0)   obdState.batterySoc   else current.batterySoc,
                            batteryTempC = if (obdState.batteryTempC > -90) obdState.batteryTempC else current.batteryTempC,
                            cabinTempC   = if (obdState.cabinTempC  > -90)  obdState.cabinTempC   else current.cabinTempC,
                            // AWD module Mode 22 — RDU oil temp (default −99.0 sentinel)
                            rduTempC     = if (obdState.rduTempC > -90)     obdState.rduTempC     else current.rduTempC,
                        )
                    }
                },
                getCurrentState = { OpenRSDashApp.instance.vehicleState.value }
            ).collect { (canId, data) ->
                // C-3 fix: use .update{} so concurrent OBD writes are never clobbered
                OpenRSDashApp.instance.vehicleState.update { current ->
                    val updated = CanDecoder.decode(canId, data, current)
                    if (updated != null) {
                        val desc  = CanDecoder.describeDecoded(canId, updated)
                        val issue = CanDecoder.validateDecoded(canId, updated)
                        DiagnosticLogger.logFrame(canId, data, updated, desc, issue)
                        updated.withPeaksUpdated().copy(framesPerSecond = wican.fps)
                    } else {
                        DiagnosticLogger.logUnknownFrame(canId, data)
                        current
                    }
                }
            }
        }
    }

    @Synchronized fun stopConnection() {
        DiagnosticLogger.sessionEnd()
        connectionJob?.cancel()
        connectionJob = null
        OpenRSDashApp.instance.vehicleState.update { it.copy(isConnected = false, isIdle = false) }
    }

    /** Called from UI when user taps the RETRY badge — fresh WiCanConnection + retry. */
    fun reconnect() {
        connectionJob?.cancel()
        connectionJob = null
        // L-6 fix: reset OBD-polled fields to sentinels so stale values from the
        // previous session don't persist for up to 30 s while new polls complete.
        OpenRSDashApp.instance.vehicleState.update {
            it.copy(
                isConnected  = false, isIdle = false,
                tirePressLF  = -1.0,  tirePressRF  = -1.0,
                tirePressLR  = -1.0,  tirePressRR  = -1.0,
                rduTempC     = -99.0, ptuTempC     = -99.0,
                odometerKm   = -1L,   batterySoc   = -1.0,
                batteryTempC = -99.0, cabinTempC   = -99.0
            )
        }
        startConnection()
    }

    fun resetPeaks() {
        OpenRSDashApp.instance.vehicleState.update { it.withPeaksReset() }
    }

}
