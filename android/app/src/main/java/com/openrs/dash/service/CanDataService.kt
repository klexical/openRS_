package com.openrs.dash.service

import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.R
import com.openrs.dash.can.AdapterState
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.MeatPiConnection
import com.openrs.dash.can.WiCanConnection
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DtcScanner
import com.openrs.dash.ui.AppSettings
import com.openrs.dash.ui.UserPrefsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CanDataService : Service() {

    private val binder = LocalBinder()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var connectionJob: Job? = null

    private var wican: WiCanConnection   = WiCanConnection()
    private var meatpi: MeatPiConnection = MeatPiConnection()

    /** True when the currently selected adapter is MeatPi Pro. */
    private val isMeatPi: Boolean
        get() = AppSettings.getAdapterType(this) == "MEATPI"

    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    private fun buildWiCan(): WiCanConnection {
        val s = AppSettings
        val autoReconnect = s.getAutoReconnect(this)
        return WiCanConnection(
            host             = s.getHost(this),
            port             = s.getPort(this),
            maxRetries       = if (autoReconnect) 3 else 1,
            reconnectDelayMs = s.getReconnectInterval(this) * 1_000L
        )
    }

    private fun buildMeatPi(): MeatPiConnection {
        val s = AppSettings
        val autoReconnect = s.getAutoReconnect(this)
        return MeatPiConnection(
            host             = s.getHost(this),
            port             = s.getPort(this),
            maxRetries       = if (autoReconnect) 3 else 1,
            reconnectDelayMs = s.getReconnectInterval(this) * 1_000L
        )
    }

    inner class LocalBinder : Binder() {
        fun getService(): CanDataService = this@CanDataService
    }

    /**
     * Performs a full DTC scan across all Focus RS ECUs.
     * Suspends for up to ~15 seconds while querying all modules.
     * Returns an empty list if the adapter is not connected.
     */
    suspend fun scanDtcs(): List<DtcResult> {
        val (useMeatPi, w, m) = synchronized(this) { Triple(isMeatPi, wican, meatpi) }
        return if (useMeatPi) DtcScanner(this).scanMeatPi(m)
               else           DtcScanner(this).scan(w)
    }

    /**
     * Sends UDS Service 0x14 to clear all DTCs from every supported ECU.
     * Suspends for up to ~12 seconds while waiting for acknowledgements.
     *
     * Returns a map of module name → true if that ECU confirmed the clear.
     * An empty map means the adapter is not connected.
     */
    suspend fun clearDtcs(): Map<String, Boolean> {
        val (useMeatPi, w, m) = synchronized(this) { Triple(isMeatPi, wican, meatpi) }
        return if (useMeatPi) DtcScanner(this).clearDtcsMeatPi(m)
               else           DtcScanner(this).clearDtcs(w)
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
        try { cm.registerNetworkCallback(request, wifiCallback!!) } catch (e: Exception) {
            android.util.Log.w("CAN", "registerNetworkCallback failed", e)
        }
    }

    private fun unregisterWifiCallback() {
        wifiCallback?.let { cb ->
            try { cm.unregisterNetworkCallback(cb) } catch (e: Exception) {
                android.util.Log.w("CAN", "unregisterNetworkCallback failed", e)
            }
        }
        wifiCallback = null
    }

    // ── Foreground notification ──────────────────────────────────────────────

    private companion object {
        const val NOTIF_ID = 1
    }

    private fun goForeground(text: String = "Connecting to vehicle…") {
        val notification = NotificationCompat.Builder(this, OpenRSDashApp.CHANNEL_CAN)
            .setContentTitle("openRS_")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, OpenRSDashApp.CHANNEL_CAN)
            .setContentTitle("openRS_")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .build()
        getSystemService(android.app.NotificationManager::class.java)
            .notify(NOTIF_ID, notification)
    }

    private fun leaveForeground() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
    }

    // ── Connection control ───────────────────────────────────────────────────

    @Synchronized fun startConnection() {
        if (!isOnWifi()) return
        if (connectionJob?.isActive == true) return

        goForeground()

        val s = AppSettings
        DiagnosticLogger.sessionStart(
            host   = s.getHost(this),
            port   = s.getPort(this),
            prefs  = UserPrefsStore.prefs.value,
            logDir = java.io.File(filesDir, "diagnostics")
        )
        CanDecoder.resetSessionState()

        if (isMeatPi) {
            meatpi = buildMeatPi()
            startMeatPiConnection()
        } else {
            wican = buildWiCan()
            startWiCanConnection()
        }
    }

    private fun startWiCanConnection() {
        connectionJob = scope.launch {
            launch {
                wican.state.collect { state ->
                    OpenRSDashApp.instance.vehicleState.update {
                        it.copy(
                            isConnected = state is AdapterState.Connected,
                            isIdle      = state is AdapterState.Idle
                        )
                    }
                    when (state) {
                        is AdapterState.Connected -> updateNotification("Connected to vehicle")
                        is AdapterState.Idle      -> updateNotification("Disconnected — tap to retry")
                        else                      -> updateNotification("Connecting to vehicle…")
                    }
                    DiagnosticLogger.event("STATE", state::class.simpleName ?: "Unknown")
                }
            }

            wican.connectHybrid(
                onObdUpdate = { obdState -> mergeObdState(obdState) },
                getCurrentState = { OpenRSDashApp.instance.vehicleState.value }
            ).collect { (canId, data) ->
                processCanFrame(canId, data, wican.fps)
            }
        }
    }

    private fun startMeatPiConnection() {
        connectionJob = scope.launch {
            launch {
                meatpi.state.collect { state ->
                    OpenRSDashApp.instance.vehicleState.update {
                        it.copy(
                            isConnected = state is AdapterState.Connected,
                            isIdle      = state is AdapterState.Idle
                        )
                    }
                    when (state) {
                        is AdapterState.Connected -> updateNotification("Connected to vehicle")
                        is AdapterState.Idle      -> updateNotification("Disconnected — tap to retry")
                        else                      -> updateNotification("Connecting to vehicle…")
                    }
                    DiagnosticLogger.event("STATE", state::class.simpleName ?: "Unknown")
                }
            }

            meatpi.connectHybrid(
                onObdUpdate = { obdState -> mergeObdState(obdState) },
                getCurrentState = { OpenRSDashApp.instance.vehicleState.value }
            ).collect { (canId, data) ->
                processCanFrame(canId, data, meatpi.fps)
            }
        }
    }

    private fun mergeObdState(obdState: VehicleState) {
        OpenRSDashApp.instance.vehicleState.update { current ->
            current.copy(
                calcLoad = obdState.calcLoad,
                shortFuelTrim = obdState.shortFuelTrim,
                longFuelTrim = obdState.longFuelTrim,
                timingAdvance = obdState.timingAdvance,
                fuelRailPressure = obdState.fuelRailPressure,
                barometricPressure = obdState.barometricPressure,
                commandedAfr = obdState.commandedAfr,
                o2Voltage = obdState.o2Voltage,
                afrSensor1 = obdState.afrSensor1,
                chargeAirTempC = obdState.chargeAirTempC,
                manifoldChargeTempC = obdState.manifoldChargeTempC,
                octaneAdjustRatio = obdState.octaneAdjustRatio,
                catalyticTempC = obdState.catalyticTempC,
                afrActual = obdState.afrActual,
                afrDesired = obdState.afrDesired,
                lambdaActual = obdState.lambdaActual,
                etcAngleActual = obdState.etcAngleActual,
                etcAngleDesired = obdState.etcAngleDesired,
                tipActualKpa = obdState.tipActualKpa,
                tipDesiredKpa = obdState.tipDesiredKpa,
                wgdcDesired = obdState.wgdcDesired,
                vctIntakeAngle = obdState.vctIntakeAngle,
                vctExhaustAngle = obdState.vctExhaustAngle,
                oilLifePct = obdState.oilLifePct,
                ignCorrCyl1 = obdState.ignCorrCyl1,
                tirePressLF = if (obdState.tirePressLF >= 0) obdState.tirePressLF else current.tirePressLF,
                tirePressRF = if (obdState.tirePressRF >= 0) obdState.tirePressRF else current.tirePressRF,
                tirePressLR = if (obdState.tirePressLR >= 0) obdState.tirePressLR else current.tirePressLR,
                tirePressRR = if (obdState.tirePressRR >= 0) obdState.tirePressRR else current.tirePressRR,
                tireTempLF = if (obdState.tireTempLF > -90) obdState.tireTempLF else current.tireTempLF,
                tireTempRF = if (obdState.tireTempRF > -90) obdState.tireTempRF else current.tireTempRF,
                tireTempLR = if (obdState.tireTempLR > -90) obdState.tireTempLR else current.tireTempLR,
                tireTempRR = if (obdState.tireTempRR > -90) obdState.tireTempRR else current.tireTempRR,
                odometerKm   = if (obdState.odometerKm  >= 0)   obdState.odometerKm   else current.odometerKm,
                batterySoc   = if (obdState.batterySoc  >= 0)   obdState.batterySoc   else current.batterySoc,
                batteryTempC = if (obdState.batteryTempC > -90) obdState.batteryTempC else current.batteryTempC,
                cabinTempC   = if (obdState.cabinTempC  > -90)  obdState.cabinTempC   else current.cabinTempC,
                rduTempC     = if (obdState.rduTempC > -90)     obdState.rduTempC     else current.rduTempC,
                hpFuelRailPsi = if (obdState.hpFuelRailPsi >= 0) obdState.hpFuelRailPsi else current.hpFuelRailPsi,
                rduEnabled   = obdState.rduEnabled   ?: current.rduEnabled,
                pdcEnabled   = obdState.pdcEnabled   ?: current.pdcEnabled,
                fengEnabled  = obdState.fengEnabled  ?: current.fengEnabled,
                lcArmed      = obdState.lcArmed      ?: current.lcArmed,
                lcRpmTarget  = if (obdState.lcRpmTarget >= 0) obdState.lcRpmTarget else current.lcRpmTarget,
                assEnabled   = obdState.assEnabled   ?: current.assEnabled,
            )
        }
    }

    private fun processCanFrame(canId: Int, data: ByteArray, fps: Double) {
        // C-3 fix: use .update{} so concurrent OBD writes are never clobbered
        OpenRSDashApp.instance.vehicleState.update { current ->
            val updated = CanDecoder.decode(canId, data, current)
            if (updated != null) {
                val desc  = CanDecoder.describeDecoded(canId, updated)
                val issue = CanDecoder.validateDecoded(canId, updated)
                DiagnosticLogger.logFrame(canId, data, updated, desc, issue)
                updated.withPeaksUpdated().copy(framesPerSecond = fps)
            } else {
                DiagnosticLogger.logUnknownFrame(canId, data)
                current
            }
        }
    }

    @Synchronized fun stopConnection() {
        DiagnosticLogger.sessionEnd()
        connectionJob?.cancel()
        connectionJob = null
        OpenRSDashApp.instance.vehicleState.update { it.copy(isConnected = false, isIdle = false) }
        leaveForeground()
    }

    /** Called from UI when user taps the RETRY badge — fresh adapter + retry. */
    @Synchronized fun reconnect() {
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
