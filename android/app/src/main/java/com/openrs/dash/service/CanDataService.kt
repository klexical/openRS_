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
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.IntentFilter
import com.openrs.dash.can.AdapterState
import com.openrs.dash.can.BleFirmwareApi
import com.openrs.dash.can.BleSlcanTransport
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.PidRegistry
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.TcpSlcanTransport
import com.openrs.dash.can.WebSocketSlcanTransport
import com.openrs.dash.can.WiFiFirmwareApi
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.SessionEntity
import com.openrs.dash.data.SnapshotEntity
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

    private var connection: SlcanConnection? = null

    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    // ── Session History ──────────────────────────────────────────────────────
    private val sessionDb by lazy { DriveDatabase.getInstance(this) }
    private var currentSessionId: Long = -1L
    private var snapshotJob: Job? = null
    private var sessionFrameCount: Long = 0L

    /** The active firmware command sender — WiFi REST or BLE AT+FRS. */
    var firmwareApi: FirmwareCommandSender? = null
        private set

    private fun buildConnection(): SlcanConnection {
        val s = AppSettings
        val host = s.getHost(this)
        val port = s.getPort(this)
        val autoReconnect = s.getAutoReconnect(this)
        val maxRetries = if (autoReconnect) 3 else 1
        val reconnectDelayMs = s.getReconnectInterval(this) * 1_000L
        val adapterType = s.getAdapterType(this)
        val connMethod = s.getConnectionMethod(this)

        val conn = SlcanConnection(
            transportFactory = {
                if (connMethod == "BLUETOOTH") {
                    val mac = s.getBleDeviceAddress(this)
                        ?: throw RuntimeException("No BLE device saved")
                    val name = s.getBleDeviceName(this) ?: "WiCAN"
                    BleSlcanTransport(this, mac, name)
                } else {
                    // WiFi: adapter type determines protocol
                    if (adapterType == "MEATPI_PRO") TcpSlcanTransport(host, port)
                    else WebSocketSlcanTransport(host, port)
                }
            },
            maxRetries       = maxRetries,
            reconnectDelayMs = reconnectDelayMs
        )

        // Create the appropriate firmware command sender
        firmwareApi = if (connMethod == "BLUETOOTH") BleFirmwareApi(conn)
                      else WiFiFirmwareApi(this, host)

        return conn
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
        val conn = synchronized(this) { connection } ?: return emptyList()
        return try {
            DtcScanner(this).scan(conn)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("CAN", "DTC scan failed", e)
            throw e
        }
    }

    /**
     * Sends UDS Service 0x14 to clear all DTCs from every supported ECU.
     * Suspends for up to ~12 seconds while waiting for acknowledgements.
     *
     * Returns a map of module name → true if that ECU confirmed the clear.
     * An empty map means the adapter is not connected or the clear failed.
     */
    suspend fun clearDtcs(): Map<String, Boolean> {
        val conn = synchronized(this) { connection } ?: return emptyMap()
        return try {
            DtcScanner(this).clearDtcs(conn)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("CAN", "DTC clear failed", e)
            emptyMap()
        }
    }

    /**
     * Send a single Mode 22 (or arbitrary) SLCAN frame and wait for a response.
     * Used by the DID prober to test individual DIDs against an ECU.
     */
    suspend fun sendRawQuery(responseId: Int, frame: String, timeoutMs: Long = 1_500L): ByteArray? {
        val conn = synchronized(this) { connection } ?: return null
        return conn.sendRawQuery(responseId, frame, timeoutMs)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        goForeground()   // Must be first — Android kills the service if startForeground() not called within 5s
        PidRegistry.ensureLoaded(this)
        registerWifiCallback()
        registerBluetoothCallback()
        if (isTransportReady()) startConnection()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        unregisterWifiCallback()
        unregisterBluetoothCallback()
        stopConnection()   // flush sessionEnd() before scope is torn down
        scope.cancel()
        super.onDestroy()
    }

    // ── Transport gating (WiFi + Bluetooth) ────────────────────────────────

    private var bluetoothReceiver: BroadcastReceiver? = null

    private fun isBluetooth(): Boolean =
        AppSettings.getConnectionMethod(this) == "BLUETOOTH"

    private fun isBluetoothEnabled(): Boolean {
        val btManager = getSystemService(BluetoothManager::class.java) ?: return false
        return btManager.adapter?.isEnabled == true
    }

    private fun isOnWifi(): Boolean {
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    /** True if the selected transport is ready (WiFi connected or Bluetooth enabled). */
    private fun isTransportReady(): Boolean =
        if (isBluetooth()) isBluetoothEnabled() else isOnWifi()

    private fun registerWifiCallback() {
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .build()
        wifiCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (isBluetooth()) return  // WiFi events irrelevant in BLE mode
                if (connectionJob?.isActive != true) {
                    try {
                        startConnection()
                    } catch (e: Exception) {
                        android.util.Log.w("CAN", "Cannot start foreground service from background", e)
                    }
                }
            }
            override fun onLost(network: Network) {
                if (!isBluetooth()) stopConnection()
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

    @Suppress("MissingPermission")
    private fun registerBluetoothCallback() {
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context, intent: android.content.Intent) {
                if (intent.action != BluetoothAdapter.ACTION_STATE_CHANGED) return
                if (!isBluetooth()) return
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                when (state) {
                    BluetoothAdapter.STATE_ON -> {
                        if (connectionJob?.isActive != true) {
                            try { startConnection() } catch (e: Exception) {
                                android.util.Log.w("CAN", "Cannot start service from BT callback", e)
                            }
                        }
                    }
                    BluetoothAdapter.STATE_OFF,
                    BluetoothAdapter.STATE_TURNING_OFF -> stopConnection()
                }
            }
        }
        val filter = IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED)
        registerReceiver(bluetoothReceiver, filter)
    }

    private fun unregisterBluetoothCallback() {
        bluetoothReceiver?.let { try { unregisterReceiver(it) } catch (_: Exception) { } }
        bluetoothReceiver = null
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

    // ── Session History helpers ─────────────────────────────────────────────

    private fun startSessionRecording() {
        scope.launch(Dispatchers.IO) {
            // Prune sessions older than 30 days
            val cutoff = System.currentTimeMillis() - 30L * 24 * 60 * 60 * 1000
            try {
                sessionDb.driveDao().deleteOldSnapshots(cutoff)
                sessionDb.driveDao().deleteOldSessions(cutoff)
            } catch (e: Exception) {
                android.util.Log.w("CAN", "Session prune failed", e)
            }

            // Create a new session
            val session = SessionEntity(startTime = System.currentTimeMillis())
            currentSessionId = sessionDb.driveDao().insertSession(session)
            sessionFrameCount = 0L
            android.util.Log.d("CAN", "Session $currentSessionId started")

            // Start periodic snapshots every 5 seconds
            snapshotJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    delay(5_000L)
                    val sid = currentSessionId
                    if (sid < 0) continue
                    val vs = OpenRSDashApp.instance.vehicleState.value
                    if (!vs.isConnected) continue
                    try {
                        sessionDb.driveDao().insertSnapshot(
                            SnapshotEntity(
                                sessionId  = sid,
                                timestamp  = System.currentTimeMillis(),
                                rpm        = vs.rpm,
                                speedKph   = vs.speedKph,
                                boostKpa   = vs.boostKpa,
                                oilTempC   = vs.oilTempC,
                                coolantTempC = vs.coolantTempC,
                                throttlePct = vs.throttlePct
                            )
                        )
                    } catch (e: Exception) {
                        android.util.Log.w("CAN", "Snapshot insert failed", e)
                    }
                }
            }
        }
    }

    private fun stopSessionRecording() {
        snapshotJob?.cancel()
        snapshotJob = null
        val sid = currentSessionId
        if (sid < 0) return
        currentSessionId = -1L
        scope.launch(Dispatchers.IO) {
            try {
                val vs = OpenRSDashApp.instance.vehicleState.value
                val existing = sessionDb.driveDao().getSession(sid) ?: return@launch
                sessionDb.driveDao().updateSession(
                    existing.copy(
                        endTime        = System.currentTimeMillis(),
                        peakRpm        = vs.peakRpm,
                        peakBoostPsi   = vs.peakBoostPsi,
                        peakOilTempC   = vs.peakOilTempC,
                        peakCoolantTempC = vs.peakCoolantTempC,
                        peakSpeedKph   = vs.peakSpeedKph,
                        totalFrames    = sessionFrameCount
                    )
                )
                android.util.Log.d("CAN", "Session $sid ended (${sessionFrameCount} frames)")
            } catch (e: Exception) {
                android.util.Log.w("CAN", "Session end failed", e)
            }
        }
    }

    // ── Connection control ───────────────────────────────────────────────────

    @Synchronized fun startConnection() {
        if (!isTransportReady()) return
        if (connectionJob?.isActive == true) return

        try {
            goForeground()
        } catch (e: Exception) {
            android.util.Log.w("CAN", "Cannot go foreground — skipping connection start", e)
            return
        }

        val s = AppSettings
        val connMethod = s.getConnectionMethod(this)
        val adapterType = s.getAdapterType(this)
        val isBle = connMethod == "BLUETOOTH"
        val transportLabel = if (isBle) {
            "Bluetooth (${s.getBleDeviceName(this) ?: "BLE"} / ${s.getBleDeviceAddress(this) ?: "?"})"
        } else {
            val protocol = if (adapterType == "MEATPI_PRO") "TCP SLCAN" else "WebSocket SLCAN"
            "$protocol (${s.getHost(this)}:${s.getPort(this)})"
        }
        DiagnosticLogger.sessionStart(
            host      = if (isBle) s.getBleDeviceAddress(this) ?: "BLE" else s.getHost(this),
            port      = if (isBle) 0 else s.getPort(this),
            transport = transportLabel,
            prefs  = UserPrefsStore.prefs.value,
            logDir = java.io.File(filesDir, "diagnostics")
        )
        CanDecoder.resetSessionState()
        startSessionRecording()

        // Auto-record drive if enabled
        if (AppSettings.getAutoRecordDrives(this)) {
            OpenRSDashApp.instance.driveRecorder.startDrive(sessionId = currentSessionId)
        }

        val conn = buildConnection()
        connection = conn

        connectionJob = scope.launch {
            launch {
                conn.state.collect { state ->
                    OpenRSDashApp.instance.vehicleState.update {
                        it.copy(
                            isConnected = state is AdapterState.Connected,
                            isIdle      = state is AdapterState.Idle
                        )
                    }
                    val btLabel = if (isBluetooth()) "via Bluetooth" else "to vehicle"
                    when (state) {
                        is AdapterState.Connected -> updateNotification("Connected $btLabel")
                        is AdapterState.Idle      -> updateNotification("Disconnected — tap to retry")
                        else                      -> updateNotification("Connecting $btLabel…")
                    }
                    DiagnosticLogger.event("STATE", state::class.simpleName ?: "Unknown")
                }
            }

            conn.connectHybrid(
                onObdUpdate = { obdState -> mergeObdState(obdState) },
                getCurrentState = { OpenRSDashApp.instance.vehicleState.value }
            ).collect { (canId, data) ->
                processCanFrame(canId, data, conn.fps)
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
                ignCorrCyl2 = obdState.ignCorrCyl2,
                ignCorrCyl3 = obdState.ignCorrCyl3,
                ignCorrCyl4 = obdState.ignCorrCyl4,
                tirePressLF = if (obdState.tirePressLF >= 0) obdState.tirePressLF else current.tirePressLF,
                tirePressRF = if (obdState.tirePressRF >= 0) obdState.tirePressRF else current.tirePressRF,
                tirePressLR = if (obdState.tirePressLR >= 0) obdState.tirePressLR else current.tirePressLR,
                tirePressRR = if (obdState.tirePressRR >= 0) obdState.tirePressRR else current.tirePressRR,
                tireTempLF = if (obdState.tireTempLF > -90) obdState.tireTempLF else current.tireTempLF,
                tireTempRF = if (obdState.tireTempRF > -90) obdState.tireTempRF else current.tireTempRF,
                tireTempLR = if (obdState.tireTempLR > -90) obdState.tireTempLR else current.tireTempLR,
                tireTempRR = if (obdState.tireTempRR > -90) obdState.tireTempRR else current.tireTempRR,
                tpmsSensorIdLF = if (obdState.tpmsSensorIdLF >= 0) obdState.tpmsSensorIdLF else current.tpmsSensorIdLF,
                tpmsSensorIdRF = if (obdState.tpmsSensorIdRF >= 0) obdState.tpmsSensorIdRF else current.tpmsSensorIdRF,
                tpmsSensorIdRR = if (obdState.tpmsSensorIdRR >= 0) obdState.tpmsSensorIdRR else current.tpmsSensorIdRR,
                tpmsSensorIdLR = if (obdState.tpmsSensorIdLR >= 0) obdState.tpmsSensorIdLR else current.tpmsSensorIdLR,
                odometerKm   = if (obdState.odometerKm  >= 0)   obdState.odometerKm   else current.odometerKm,
                odometerRolloverOffset = if (obdState.odometerRolloverOffset > 0) obdState.odometerRolloverOffset else current.odometerRolloverOffset,
                batterySoc   = if (obdState.batterySoc  >= 0)   obdState.batterySoc   else current.batterySoc,
                batteryTempC = if (obdState.batteryTempC > -90) obdState.batteryTempC else current.batteryTempC,
                cabinTempC   = if (obdState.cabinTempC  > -90)  obdState.cabinTempC   else current.cabinTempC,
                rduTempC     = if (obdState.rduTempC > -90)     obdState.rduTempC     else current.rduTempC,
                awdClutchTempL = if (obdState.awdClutchTempL > -90) obdState.awdClutchTempL else current.awdClutchTempL,
                awdClutchTempR = if (obdState.awdClutchTempR > -90) obdState.awdClutchTempR else current.awdClutchTempR,
                awdReqTorqueL  = if (obdState.awdReqTorqueL > 0) obdState.awdReqTorqueL else current.awdReqTorqueL,
                awdReqTorqueR  = if (obdState.awdReqTorqueR > 0) obdState.awdReqTorqueR else current.awdReqTorqueR,
                awdDmdPressure = if (obdState.awdDmdPressure > 0) obdState.awdDmdPressure else current.awdDmdPressure,
                awdPumpCurrent = if (obdState.awdPumpCurrent > 0) obdState.awdPumpCurrent else current.awdPumpCurrent,
                transOilTempC  = if (obdState.transOilTempC > -90) obdState.transOilTempC else current.transOilTempC,
                hpFuelRailPsi = if (obdState.hpFuelRailPsi >= 0) obdState.hpFuelRailPsi else current.hpFuelRailPsi,
                batteryVoltage = if (obdState.batteryVoltage > 0) obdState.batteryVoltage else current.batteryVoltage,
                rduEnabled   = obdState.rduEnabled   ?: current.rduEnabled,
                pdcEnabled   = obdState.pdcEnabled   ?: current.pdcEnabled,
                fengEnabled  = obdState.fengEnabled  ?: current.fengEnabled,
                fengTimedOut = obdState.fengTimedOut || current.fengTimedOut,
                lcArmed      = obdState.lcArmed      ?: current.lcArmed,
                lcRpmTarget  = if (obdState.lcRpmTarget >= 0) obdState.lcRpmTarget else current.lcRpmTarget,
                assEnabled   = obdState.assEnabled   ?: current.assEnabled,
                rsprotTimedOut = obdState.rsprotTimedOut || current.rsprotTimedOut,
                genericValues = if (obdState.genericValues.isNotEmpty())
                    current.genericValues + obdState.genericValues
                else current.genericValues,
                hvacBlowerPct       = if (obdState.hvacBlowerPct >= 0) obdState.hvacBlowerPct else current.hvacBlowerPct,
                hvacInteriorTempC   = if (obdState.hvacInteriorTempC > -90) obdState.hvacInteriorTempC else current.hvacInteriorTempC,
                hvacDischargeRfTempC= if (obdState.hvacDischargeRfTempC > -90) obdState.hvacDischargeRfTempC else current.hvacDischargeRfTempC,
                hvacBlendDoorL      = if (obdState.hvacBlendDoorL >= 0) obdState.hvacBlendDoorL else current.hvacBlendDoorL,
                hvacBlendDoorR      = if (obdState.hvacBlendDoorR >= 0) obdState.hvacBlendDoorR else current.hvacBlendDoorR,
                hvacDefrostDoor     = if (obdState.hvacDefrostDoor >= 0) obdState.hvacDefrostDoor else current.hvacDefrostDoor,
                warnMil         = obdState.warnMil         ?: current.warnMil,
                warnAbs         = obdState.warnAbs         ?: current.warnAbs,
                warnBrake       = obdState.warnBrake       ?: current.warnBrake,
                warnCharge      = obdState.warnCharge      ?: current.warnCharge,
                warnOilPressure = obdState.warnOilPressure ?: current.warnOilPressure,
                warnTempHigh    = obdState.warnTempHigh    ?: current.warnTempHigh,
            )
        }
    }

    private fun processCanFrame(canId: Int, data: ByteArray, fps: Double) {
        sessionFrameCount++
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
        // Auto-stop drive if recording
        val recorder = OpenRSDashApp.instance.driveRecorder
        if (recorder.driveState.value.isRecording && AppSettings.getAutoRecordDrives(this)) {
            recorder.stopDrive()
        }
        stopSessionRecording()
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
                odometerKm   = -1L,   odometerRolloverOffset = 0,
                batterySoc   = -1.0,
                batteryTempC = -99.0, cabinTempC   = -99.0
            )
        }
        startConnection()
    }

    fun resetPeaks() {
        OpenRSDashApp.instance.vehicleState.update { it.withPeaksReset() }
    }

    /** Reset the current session without disconnecting from the adapter. */
    fun resetSession() {
        stopSessionRecording()
        CanDecoder.resetSessionState()
        val connected = OpenRSDashApp.instance.vehicleState.value.isConnected
        OpenRSDashApp.instance.vehicleState.update { VehicleState(isConnected = connected) }
        startSessionRecording()
    }

}
