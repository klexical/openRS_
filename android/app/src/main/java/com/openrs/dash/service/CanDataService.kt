package com.openrs.dash.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.openrs.dash.R
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.WiCanConnection
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.ui.UserPrefsStore
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

class CanDataService : Service() {

    companion object {
        const val CHANNEL_ID = "openrs_can_channel"
        const val NOTIFICATION_ID = 1
    }

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
        createNotificationChannel()
        try {
            startForeground(NOTIFICATION_ID, buildNotification("Ready"))
        } catch (e: Exception) { e.printStackTrace() }

        registerWifiCallback()
        if (isOnWifi()) startConnection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterWifiCallback()
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
                // WiFi lost — stop retrying; connection will fail and notify
                stopConnection()
                try { updateNotification("No WiFi") } catch (_: Exception) {}
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

    fun startConnection() {
        if (!isOnWifi()) {
            try { updateNotification("No WiFi — connect to openRS_ network") } catch (_: Exception) {}
            return
        }
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

        connectionJob = scope.launch {
            launch {
                wican.state.collect { state ->
                    val text = when (state) {
                        is WiCanConnection.State.Connected    -> "Connected"
                        is WiCanConnection.State.Connecting   -> "Connecting…"
                        is WiCanConnection.State.Disconnected -> "Disconnected"
                        is WiCanConnection.State.Idle         -> "Idle — tap openRS_ to retry"
                        is WiCanConnection.State.Error        -> "Error"
                    }
                    try { updateNotification(text) } catch (_: Exception) {}
                    OpenRSDashApp.instance.vehicleState.update {
                        it.copy(
                            isConnected = state is WiCanConnection.State.Connected,
                            isIdle      = state is WiCanConnection.State.Idle
                        )
                    }
                    DiagnosticLogger.event("STATE", text)
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
                            // Mode 22 — TPMS (BCM)
                            tirePressLF = obdState.tirePressLF,
                            tirePressRF = obdState.tirePressRF,
                            tirePressLR = obdState.tirePressLR,
                            tirePressRR = obdState.tirePressRR,
                            tireTempLF = obdState.tireTempLF,
                            tireTempRF = obdState.tireTempRF,
                            tireTempLR = obdState.tireTempLR,
                            tireTempRR = obdState.tireTempRR,
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
                val current = OpenRSDashApp.instance.vehicleState.value
                val updated = CanDecoder.decode(canId, data, current)
                if (updated != null) {
                    // Log decoded frame for diagnostics
                    val desc  = CanDecoder.describeDecoded(canId, updated)
                    val issue = CanDecoder.validateDecoded(canId, updated)
                    DiagnosticLogger.logFrame(canId, data, updated, desc, issue)

                    OpenRSDashApp.instance.vehicleState.value = updated
                        .withPeaksUpdated()
                        .copy(framesPerSecond = wican.fps)
                } else {
                    // Unknown / unimplemented ID — still track in inventory
                    DiagnosticLogger.logUnknownFrame(canId, data)
                }
            }
        }
    }

    fun stopConnection() {
        DiagnosticLogger.sessionEnd()
        connectionJob?.cancel()
        connectionJob = null
        OpenRSDashApp.instance.vehicleState.update { it.copy(isConnected = false, isIdle = false) }
        try { updateNotification("Disconnected") } catch (_: Exception) {}
    }

    /** Called from UI when user taps the RETRY badge — fresh WiCanConnection + retry. */
    fun reconnect() {
        connectionJob?.cancel()
        connectionJob = null
        OpenRSDashApp.instance.vehicleState.update { it.copy(isConnected = false, isIdle = false) }
        startConnection()
    }

    fun resetPeaks() {
        OpenRSDashApp.instance.vehicleState.update { it.withPeaksReset() }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "openRS_ CAN Data", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "WiCAN connection status" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("openRS_")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true).setSilent(true).build()

    private fun updateNotification(text: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(text))
    }
}
