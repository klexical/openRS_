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
import androidx.core.content.edit
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
import com.openrs.dash.can.LinkPhase
import com.openrs.dash.can.PidRegistry
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.TcpSlcanTransport
import com.openrs.dash.can.WebSocketSlcanTransport
import com.openrs.dash.can.WicanApi
import com.openrs.dash.can.WiFiFirmwareApi
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcScanRecord
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.PerformanceTimer
import com.openrs.dash.data.SessionEntity
import com.openrs.dash.data.SnapshotEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.demo.DemoFirmwareApi
import com.openrs.dash.demo.DemoMode
import com.openrs.dash.demo.DemoScenario
import com.openrs.dash.diagnostics.DtcDatabase
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

    @Volatile private var connection: SlcanConnection? = null

    private val cm by lazy { getSystemService(ConnectivityManager::class.java) }
    private var wifiCallback: ConnectivityManager.NetworkCallback? = null

    // ── Session History ──────────────────────────────────────────────────────
    private val sessionDb by lazy { DriveDatabase.getInstance(this) }
    private var currentSessionId: Long = -1L
    private var snapshotJob: Job? = null
    private var sessionFrameCount: Long = 0L

    /** Adapter firmware command sender — WiFi REST or BLE AT+FRS. */
    private var adapterFirmwareApi: FirmwareCommandSender? = null

    /** The active firmware command sender. Demo mode routes every command to simulated state. */
    val firmwareApi: FirmwareCommandSender?
        get() = if (DemoMode.isActive) DemoFirmwareApi else adapterFirmwareApi

    /** Started / foreground state — promoted only while a connection runs (see [ForegroundController]). */
    private val foreground = ForegroundController(object : ForegroundController.Host {
        override fun startSelf() {
            androidx.core.content.ContextCompat.startForegroundService(
                this@CanDataService, Intent(this@CanDataService, CanDataService::class.java))
        }
        override fun enterForeground() = goForeground()
        override fun exitForeground() = leaveForeground()
        override fun stopSelf() = this@CanDataService.stopSelf()
    })

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
                    BleSlcanTransport(this, mac, name, adapterType = adapterType)
                } else {
                    // WiFi: adapter type determines protocol
                    if (adapterType == "MEATPI_PRO") TcpSlcanTransport(host, port)
                    else WebSocketSlcanTransport(host, port)
                }
            },
            maxRetries       = maxRetries,
            reconnectDelayMs = reconnectDelayMs,
            rpmProvider      = { OpenRSDashApp.instance.vehicleState.value.rpm },
            keepRetrying     = { isRecording() }
        )

        // Create the appropriate firmware command sender
        adapterFirmwareApi = if (connMethod == "BLUETOOTH") BleFirmwareApi(conn)
                             else WiFiFirmwareApi(this, host)

        return conn
    }

    inner class LocalBinder : Binder() {
        fun getService(): CanDataService = this@CanDataService
    }

    /**
     * Performs a full DTC scan across all Focus RS ECUs.
     * Suspends for up to ~15 seconds while querying all modules.
     * Returns an empty [DtcScanResult] if the adapter is not connected.
     *
     * [onModuleProgress] is called on each module state change for live UI updates.
     * A null status means "querying"; non-null means the module is done.
     */
    suspend fun scanDtcs(
        onModuleProgress: ((String, com.openrs.dash.data.ModuleScanStatus?) -> Unit)? = null
    ): DtcScanResult {
        if (DemoMode.isActive) return DemoMode.scanDtcs(onModuleProgress)
        val conn = connection
            ?: return DtcScanResult(emptyList(), emptyMap())
        return try {
            DtcScanner(this).scan(conn, onModuleProgress)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("CAN", "DTC scan failed", e)
            throw e
        }
    }

    /**
     * Retry DTC scan for a single module that previously timed out.
     */
    suspend fun retryScanModule(moduleName: String): com.openrs.dash.data.DtcModuleRetry {
        if (DemoMode.isActive) return DemoMode.retryScanModule(moduleName)
        val conn = connection ?: return com.openrs.dash.data.DtcModuleRetry(moduleName, emptyList(),
            com.openrs.dash.data.ModuleScanStatus.ERROR, "no adapter connection")
        return DtcScanner(this).scanSingleModule(conn, moduleName)
    }

    /**
     * Fetch freeze frame data for DTCs that have snapshot records.
     */
    suspend fun fetchFreezeFrames(codes: List<DtcResult>): List<DtcResult> {
        if (DemoMode.isActive) return codes
        val conn = connection ?: return codes
        return try {
            DtcScanner(this).fetchFreezeFrames(conn, codes)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
        catch (_: Exception) { codes }
    }

    /**
     * Sends UDS Service 0x14 to clear all DTCs from every supported ECU.
     * Suspends for up to ~12 seconds while waiting for acknowledgements.
     *
     * Returns a map of module name → true if that ECU confirmed the clear.
     * An empty map means the adapter is not connected or the clear failed.
     */
    suspend fun clearDtcs(): Map<String, Boolean> {
        if (DemoMode.isActive) return DemoMode.clearDtcs()
        val conn = connection ?: return emptyMap()
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
    suspend fun sendRawQuery(responseId: Int, frame: String, timeoutMs: Long = 1_500L): com.openrs.dash.can.RawReply? {
        // Demo: no ECU on the other end — probes time out as they would with no response.
        if (DemoMode.isActive) { delay(timeoutMs.coerceAtMost(600L)); return null }
        val conn = connection ?: throw java.io.IOException(com.openrs.dash.diagnostics.QUERY_NOT_CONNECTED)
        return conn.sendRawQuery(responseId, frame, timeoutMs)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        // Not foreground here: the activity only binds. startConnection() promotes the service (start + startForeground
        // together) when there is a connection to keep alive.
        com.openrs.dash.ui.StartupTrace.mark("CanDataService.onCreate start")
        // The 185 KB FORScan catalog (fallback decoder for DIDs without a dedicated parser) loads off the main
        // thread — on the main thread it held the opening composition's first frames for ~250 ms. Until it is
        // ready, PidRegistry.decode() returns null, i.e. those catalog-only DIDs are skipped for a moment.
        scope.launch(Dispatchers.IO) {
            PidRegistry.ensureLoaded(this@CanDataService)
            com.openrs.dash.ui.StartupTrace.mark("PID catalog loaded (background)")
        }
        // An explicit Cancel from an earlier service instance or process holds until the user connects (#212) — load
        // it before any callback is registered or an automatic start can run.
        link.attachStore(object : LinkOwnership.CancelStore {
            private val p = getSharedPreferences("openrs_link", MODE_PRIVATE)
            override fun load() = p.getBoolean("user_cancelled", false)
            override fun save(cancelled: Boolean) { p.edit(commit = true) { putBoolean("user_cancelled", cancelled) } }
        })
        // A sticky restart recreates this service with no activity: load the saved prefs before any connection, so
        // auto-record and readiness use the saved thermal profile, not the defaults. No-op after MainActivity.onCreate.
        if (UserPrefsStore.ensureLoaded(this)) com.openrs.dash.ui.StartupTrace.mark("settings loaded by CanDataService (no activity)")
        registerWifiCallback()
        registerBluetoothCallback()
        // Recording start / pause / resume / stop changes the notification text and whether Cancel is offered (#212)
        scope.launch {
            OpenRSDashApp.instance.driveRecorder.driveState
                .map { it.isRecording to it.isPaused }
                .distinctUntilChanged()
                .collect { link.transition { publishNotification(link.phase) } }
        }
        if (isTransportReady()) autoStartConnection()
        com.openrs.dash.ui.StartupTrace.mark("CanDataService.onCreate end")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_CANCEL_CONNECT) {
            cancelConnection()   // refused (no-op) once connected or while recording — see cancelConnection
            // A cancel delivered to a service that wasn't already promoted must not leave it started
            if (!foreground.isForeground) stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

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
                        autoStartConnection()
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
                            try { autoStartConnection() } catch (e: Exception) {
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

    internal companion object {
        const val NOTIF_ID = 1

        /**
         * Connection ownership (#212): generation, published phase and the manual-connect block set by a cancel or by
         * leaving demo mode (WiFi / Bluetooth / service-start callbacks must not reconnect until an explicit connect).
         * Process-wide: an idle service stops when the activity unbinds, and the next instance must still honour it.
         */
        val link = LinkOwnership()

        /** Notification action: cancel an initial connection attempt (#212). */
        const val ACTION_CANCEL_CONNECT = "com.openrs.dash.action.CANCEL_CONNECT"

        /** Window within which a paused drive will be resumed on reconnect. Beyond this,
         *  the stale drive is finalized and a fresh one begins. */
        const val AUTO_RESUME_WINDOW_MS = 15 * 60_000L  // 15 minutes
    }

    private fun isRecording(): Boolean = OpenRSDashApp.instance.driveRecorder.driveState.value.isRecording

    /**
     * The foreground notification. Its body brings the existing MainActivity forward (SINGLE_TOP + CLEAR_TOP: never a
     * second dashboard on top of a task started by another intent) or starts one; while an initial attempt runs it
     * carries a Cancel action (#212). No generic Stop: that could end a recording.
     */
    private fun buildNotification(text: String, offerCancel: Boolean): android.app.Notification {
        val open = android.app.PendingIntent.getActivity(this, 0,
            Intent(this, com.openrs.dash.ui.MainActivity::class.java)
                .setAction(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, OpenRSDashApp.CHANNEL_CAN)
            .setContentTitle("openRS_")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .apply {
                if (offerCancel) addAction(0, "Cancel", android.app.PendingIntent.getService(
                    this@CanDataService, 1,
                    Intent(this@CanDataService, CanDataService::class.java).setAction(ACTION_CANCEL_CONNECT),
                    android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT))
            }
            .build()
    }

    private fun goForeground(text: String = "Connecting to vehicle…") {
        val notification = buildNotification(text, offerCancel = !isRecording())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this, NOTIF_ID, notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            )
        } else {
            startForeground(NOTIF_ID, notification)
        }
    }

    private fun updateNotification(text: String, offerCancel: Boolean = false) {
        if (!foreground.isForeground) return   // released (stopped / exhausted) — never resurrect the notification
        val notification = buildNotification(text, offerCancel)
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
                                throttlePct = vs.throttleOrPedalPct
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
                        // Session table predates #207's sources: the CAN 0x0F8 peak, its historical meaning
                        peakBoostPsi   = (com.openrs.dash.data.BoostSession.peakKpa(com.openrs.dash.data.BoostSource.CAN_0F8) ?: 0.0) *
                            com.openrs.dash.data.BoostReading.KPA_TO_PSI,
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

    /**
     * Transport-callback entry point — honours [LinkOwnership.manualConnectRequired] (after a cancel or demo mode). The
     * check and the start are one step under the ownership lock: a callback can't pass the check, lose the race to a
     * cancel, then start anyway.
     */
    private fun autoStartConnection() {
        link.autoStart(blocked = { DemoMode.isActive || DemoMode.launchRequested }) { beginConnection() }
    }

    /** Explicit (user) connect. No-op while demo mode owns the vehicle state. */
    fun startConnection(): Unit = link.transition {
        if (DemoMode.isActive) return@transition
        link.clearManualConnect()
        beginConnection()
    }

    /** Starts a connection job unless one is running; false when nothing could start (transport down / no foreground). */
    private fun beginConnection(): Boolean = link.transition {
        if (DemoMode.isActive) return@transition false
        if (!isTransportReady()) return@transition false
        if (connectionJob?.isActive == true) return@transition true
        // Bluetooth with no saved device (#234): nothing to connect to — checked before the service is promoted and a
        // session begins, so no notification, no session record, no retries. Every start path lands here (launch,
        // Connect, Bluetooth on, a sticky restart with no activity); the dashboard explains it, the choice is kept.
        ConnectionSetup.problem(AppSettings.getConnectionMethod(this), AppSettings.getBleDeviceAddress(this))?.let {
            android.util.Log.i("CAN", "Not connecting — $it")
            return@transition false
        }

        try {
            foreground.promote()
        } catch (e: Exception) {
            android.util.Log.w("CAN", "Cannot go foreground — skipping connection start", e)
            return@transition false
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
        // Bluetooth starts "not requested" together with the session; Wi-Fi status writes carry this session's token (#233)
        val diagSession = DiagnosticLogger.sessionStart(
            host      = if (isBle) s.getBleDeviceAddress(this) ?: "BLE" else s.getHost(this),
            port      = if (isBle) 0 else s.getPort(this),
            transport = transportLabel,
            prefs  = UserPrefsStore.prefs.value,
            logDir = java.io.File(filesDir, "diagnostics"),
            adapterStatusReason = if (isBle) com.openrs.dash.diagnostics.AdapterStatusRecord.BLUETOOTH
                                  else com.openrs.dash.diagnostics.AdapterStatusRecord.NO_REQUEST,
        )
        CanDecoder.resetSessionState()
        FuelEconomy.reset()
        PerformanceTimer.resetSession()
        OpenRSDashApp.instance.knockEventCount.value = 0
        com.openrs.dash.data.KnockSessionLog.reset()
        com.openrs.dash.data.BoostSessionLog.reset()
        com.openrs.dash.data.BoostSession.reset()
        startSessionRecording()

        val conn = buildConnection()
        connection = conn
        val gen = link.begin()
        val session = newLinkSession(gen)
        applyPhase(LinkPhase.CONNECTING)

        connectionJob = scope.launch {
            launch {
                conn.state.collect { state ->
                    if (DemoMode.isActive || !link.owns(gen)) return@collect
                    when (val phase = LinkPresentation.phaseFor(state)) {
                        null -> link.deliver(gen) { DiagnosticLogger.event("STATE", state.label) }
                        LinkPhase.FAILED -> onAttemptsExhausted(gen, state.label)
                        // Checked and published as one step: a stop can't land between them and be overwritten
                        else -> link.publish(gen, phase) {
                            DiagnosticLogger.event("STATE", state.label)
                            applyPhase(it)
                        }
                    }
                }
            }

            // Query WiCAN /check_status once after connection (WiFi only).
            // Populates device info for GARAGE display and diagnostic export.
            // The export records the outcome for this session only — never an earlier session's status (#233).
            if (!isBle) {
                launch {
                    conn.state.first { it is AdapterState.Connected }
                    link.deliver(gen) { DiagnosticLogger.adapterStatusRequested(diagSession) }
                    val host = s.getHost(this@CanDataService)
                    val status = WicanApi.checkStatus(this@CanDataService, host)
                    if (status != null) link.deliver(gen) {
                        OpenRSDashApp.instance.deviceStatus.value = status
                        DiagnosticLogger.adapterStatusFetched(diagSession, status)
                        DiagnosticLogger.event("DEVICE_STATUS",
                            "${status.hardwareVersion} fw=${status.firmwareVersion} " +
                            "can=${status.canDatarate} vbatt=${status.obdPortVoltage}V")
                    } else link.deliver(gen) {
                        DiagnosticLogger.adapterStatusFailed(diagSession)
                        DiagnosticLogger.event("DEVICE_STATUS", "/check_status request failed — adapter details unknown this session")
                    }
                }
            }

            // Auto-scan DTCs on connect (opt-in setting).
            // Runs after a 5s stabilization delay so OBD polling is active.
            if (AppSettings.getAutoScanDtcs(this@CanDataService)) {
                launch {
                    conn.state.first { it is AdapterState.Connected }
                    delay(5_000L)
                    // Only proceed if still connected
                    if (conn.state.value is AdapterState.Connected && link.owns(gen)) {
                        // Visible from the start (RUNNING + progress); export + count only if the ledger accepts (#215)
                        val names = DtcScanner.MODULES.map { it.name }
                        val out = com.openrs.dash.diagnostics.DtcScanView.autoScan(
                            DiagnosticLogger.dtcScans, names, System::currentTimeMillis,
                            scan = { progress -> DtcScanner(this@CanDataService).scan(conn, progress) },
                            deliver = { block -> link.deliver(gen) { block() } },
                            publishCount = { OpenRSDashApp.instance.activeDtcCount.value = it },
                        )
                        if (out != null) {
                            val (result, accepted) = out
                            val record = DtcScanRecord.from(result, 0L, "auto", names)
                            DiagnosticLogger.event("AUTO_DTC_SCAN",
                                "${record.status} · ${result.codes.size} code(s) · ${record.modulesAnswered}/${record.modules.size} modules scanned" +
                                    if (accepted) "" else " — superseded, not published")
                        }
                    }
                }
            }

            // Auto-record drive: gated on adapter connected + engine actually
            // running. Prevents premature recording when the phone is
            // on the adapter's WiFi but the car is off (was the
            // original bug — drive would start with no telemetry). On
            // reconnect within AUTO_RESUME_WINDOW_MS a paused drive is
            // resumed seamlessly; beyond that, the stale drive is
            // finalized and a fresh one begins. A drive still recording
            // is kept (AutoRecordPolicy).
            if (AppSettings.getAutoRecordDrives(this@CanDataService)) {
                launch {
                    // A drive still recording (manual, or across an RCN retry) is kept — never split (#212)
                    OpenRSDashApp.instance.vehicleState.collect { vs ->
                        session.onVehicleState(vs, { currentSessionId }, AUTO_RESUME_WINDOW_MS)
                    }
                }
            }

            conn.connectHybrid(
                onObdUpdate = { obdState ->
                    // Hop OBD state merges to Main.immediate: inline when already on
                    // Main, otherwise posts — keeps Compose collectors off the IO
                    // frame thread so no collector can block frame reads. Applied only if this
                    // job still owns the link when the merge runs (a posted merge can outlive it).
                    scope.launch(Dispatchers.Main.immediate) { link.deliver(gen) { mergeObdState(obdState) } }
                },
                getCurrentState = { OpenRSDashApp.instance.vehicleState.value }
            ).collect { (canId, data) ->
                session.onCanFrame(canId, data, conn.fps)
            }
        }
        true
    }

    private fun mergeObdState(obdState: VehicleState) {
        if (DemoMode.isActive) return   // late poll response after demo took over
        OpenRSDashApp.instance.vehicleState.update { current -> com.openrs.dash.data.ObdMerge.merge(current, obdState) }
        countKnockIfFresh(obdState)
        // Session knock log (#174) — merged live state so pull detection sees CAN rpm/throttle
        val loc = OpenRSDashApp.instance.driveState.value.currentLocation
        com.openrs.dash.data.KnockSessionLog.onState(
            OpenRSDashApp.instance.vehicleState.value, System.currentTimeMillis(),
            loc?.latitude, loc?.longitude
        )
        // Boost rule (#207): a fresh TIP decode can be source evidence / a TIP peak
        com.openrs.dash.data.BoostSession.onState(OpenRSDashApp.instance.vehicleState.value, System.currentTimeMillis())
        // Live boost sweep window for the POWER chart (#175)
        com.openrs.dash.data.BoostSessionLog.onState(
            OpenRSDashApp.instance.vehicleState.value, System.currentTimeMillis()
        )
    }

    /** Stamp of the last KR reading already counted — merges run on Main, so no locking. */
    private var lastKrCountedMs = 0L

    /**
     * Knock counter (header badge). Counts each freshly polled KR reading showing > 1° retard
     * on any cylinder. Sign convention: retard is POSITIVE (ObdResponseParser 0x03EC–EF use
     * raw / −512). Runs outside the update{} lambda — CAS retries must not double-count.
     */
    private fun countKnockIfFresh(obdState: VehicleState) {
        val stamps = obdState.fieldLastUpdateMs
        val krStamp = com.openrs.dash.data.KnockCapture.KR_TAGS.maxOf { stamps[it] ?: 0L }
        if (krStamp <= lastKrCountedMs) return
        lastKrCountedMs = krStamp
        val kr = maxOf(obdState.ignCorrCyl1, obdState.ignCorrCyl2, obdState.ignCorrCyl3, obdState.ignCorrCyl4)
        if (kr > 1.0) OpenRSDashApp.instance.knockEventCount.update { it + 1 }
    }

    /** The live-data work of connection job [gen] — frames, derived values, auto-record (see [LinkSession]). */
    private fun newLinkSession(gen: Int) = LinkSession(
        link = link,
        gen = gen,
        state = OpenRSDashApp.instance.vehicleState,
        recorder = object : LinkSession.Recorder {
            private val r get() = OpenRSDashApp.instance.driveRecorder
            override val isRecording get() = r.driveState.value.isRecording
            override val isPaused get() = r.driveState.value.isPaused
            override val activeDriveId get() = r.driveState.value.let { if (it.isRecording) it.driveId else null }
            override fun pausedDurationMs() = r.pausedDurationMs()
            override fun resumeDrive() = r.resumeDrive()
            override fun stopDrive() { r.stopDrive() }
            override fun startDrive(sessionId: Long) = r.startDrive(sessionId = sessionId)
        },
        onFrameCounted = { sessionFrameCount++ },
        perfRunSink = { run ->
            scope.launch(Dispatchers.IO) {                    // Room write off the frame thread and off the lock
                try {
                    OpenRSDashApp.instance.driveDb.driveDao().insertPerfRun(run)
                } catch (e: Exception) {
                    android.util.Log.w("CanDataService", "PerfRun persist failed", e)
                }
            }
        },
    )

    fun stopConnection(): Unit = link.transition {
        // Auto-pause drive if recording — do NOT finalize here. A brief
        // disconnect (WiFi drop, adapter reboot) should not end the
        // drive. On reconnect within AUTO_RESUME_WINDOW_MS the drive
        // resumes; beyond that, the next startConnection finalizes it
        // and begins a fresh drive. Orphans across app restarts are
        // cleaned up by DriveRecorder's init block.
        val recorder = OpenRSDashApp.instance.driveRecorder
        val ds = recorder.driveState.value
        if (ds.isRecording && !ds.isPaused && AppSettings.getAutoRecordDrives(this)) {
            recorder.pauseDrive()
        }
        endConnection(LinkPhase.OFF)
    }

    /**
     * Ends the current job (if any) and releases the foreground service. [finalPhase] is OFF for a stop / cancel and
     * FAILED when the attempts ran out. Ending ownership first means a cancelled job that is still unwinding can't
     * publish state, a merge or a notification over a newer attempt.
     */
    private fun endConnection(finalPhase: LinkPhase): Unit = link.transition {
        teardownJob(finalPhase)
        OpenRSDashApp.instance.linkPhase.value = finalPhase
        // Demo owns the connection flags (WiFi/BT-loss callbacks still land here).
        if (!DemoMode.isActive) {
            OpenRSDashApp.instance.vehicleState.update { it.copy(isConnected = false, isReconnecting = false) }
            OpenRSDashApp.instance.deviceStatus.value = null
        }
        // Leave the foreground and the started state: with no activity bound the service now stops.
        foreground.demote()
    }

    /** Ends ownership and the job: session records closed, transport aborted, job cancelled. */
    private fun teardownJob(phase: LinkPhase) {
        link.end(phase)
        stopSessionRecording()
        DiagnosticLogger.sessionEnd()
        connection?.abort()          // a blocking connect / read returns now instead of after its timeout
        connectionJob?.cancel()
        connectionJob = null
    }

    /** Publish a live phase (the job is current — called inside the ownership lock): link phase, VehicleState flags, notification. */
    private fun applyPhase(phase: LinkPhase) {
        OpenRSDashApp.instance.linkPhase.value = phase
        OpenRSDashApp.instance.vehicleState.update {
            it.copy(isConnected = phase == LinkPhase.CONNECTED, isReconnecting = phase == LinkPhase.RECONNECTING)
        }
        publishNotification(phase)
    }

    /** Notification text + Cancel for [phase] and the current recording state; no-op once released (not foreground). */
    private fun publishNotification(phase: LinkPhase) {
        val ds = OpenRSDashApp.instance.driveRecorder.driveState.value
        LinkPresentation.notificationText(phase, isBluetooth(), ds.isRecording, ds.isPaused)?.let {
            updateNotification(it, LinkPresentation.offersCancel(phase, ds.isRecording))
        }
    }

    /**
     * Attempts ran out (never while a drive is recording — see `keepRetrying`): end the job and the foreground service
     * instead of leaving a notification that can't do anything. A new network or an explicit connect starts again.
     */
    private fun onAttemptsExhausted(gen: Int, label: String): Unit = link.transition {
        if (!link.owns(gen)) return@transition
        DiagnosticLogger.event("STATE", label)
        android.util.Log.i("CAN", "Connection attempts exhausted — released")
        val recorder = OpenRSDashApp.instance.driveRecorder
        val ds = recorder.driveState.value
        if (ds.isRecording && !ds.isPaused && AppSettings.getAutoRecordDrives(this)) recorder.pauseDrive()
        endConnection(LinkPhase.FAILED)
    }

    /**
     * User cancel of an initial attempt (pill, DRIVE status row, notification action). Closes the transport, cancels
     * the attempt and its backoff, and blocks auto-connect (network callbacks, a rebind) until an explicit connect.
     * One policy for every entry point ([LinkPresentation.offersCancel]), checked here when the request is handled: a
     * Cancel while a drive is recording (incl. paused) or once the link is up is refused — it would stop the service the
     * recording runs under — and leaves the connection and the drive exactly as they were. Returns whether it cancelled.
     */
    fun cancelConnection(): Boolean = link.transition {
        if (DemoMode.isActive) return@transition false
        val cancelled = link.cancel(recording = { isRecording() }) {
            DiagnosticLogger.event("STATE", "Cancelled by user")
            stopConnection()
        }
        if (!cancelled) {
            val why = if (isRecording()) "a drive is recording" else "link ${link.phase.name.lowercase()}"
            DiagnosticLogger.event("STATE", "Cancel ignored — $why")
            android.util.Log.i("CAN", "Cancel ignored — $why")
        }
        cancelled
    }

    /** Called from UI when user taps the RETRY badge — fresh adapter + retry. */
    fun reconnect(): Unit = link.transition {
        if (DemoMode.isActive) return@transition
        link.clearManualConnect()
        // Old job, its session record and diagnostic session end before the new ones begin
        teardownJob(LinkPhase.OFF)
        // L-6 fix: reset OBD-polled fields to sentinels so stale values from the
        // previous session don't persist for up to 30 s while new polls complete.
        OpenRSDashApp.instance.vehicleState.update {
            it.copy(
                isConnected  = false, isReconnecting = false,
                tirePressLF  = -1.0,  tirePressRF  = -1.0,
                tirePressLR  = -1.0,  tirePressRR  = -1.0,
                rduTempC     = -99.0, ptuTempC     = -99.0,
                odometerKm   = -1L,
                batterySoc   = -1.0,
                batteryTempC = -99.0, cabinTempC   = -99.0
            )
        }
        // Transport down (or no foreground allowed): nothing started, so release instead of keeping a stale notification
        if (!beginConnection()) endConnection(LinkPhase.OFF)
    }

    fun resetPeaks() {
        OpenRSDashApp.instance.vehicleState.update { it.withPeaksReset() }
        com.openrs.dash.data.BoostSession.resetPeaks()
    }

    /** Reset the current session without disconnecting from the adapter. */
    fun resetSession() {
        // Demo: replay the scenario instead — never create a Room session for simulated data.
        if (DemoMode.isActive) { DemoMode.reset(); return }
        stopSessionRecording()
        CanDecoder.resetSessionState()
        FuelEconomy.reset()
        PerformanceTimer.resetSession()
        OpenRSDashApp.instance.knockEventCount.value = 0
        com.openrs.dash.data.KnockSessionLog.reset()
        com.openrs.dash.data.BoostSessionLog.reset()
        com.openrs.dash.data.BoostSession.reset()
        val connected = OpenRSDashApp.instance.vehicleState.value.isConnected
        OpenRSDashApp.instance.vehicleState.update { VehicleState(isConnected = connected) }
        com.openrs.dash.ui.ThermalReadinessSession.reset()
        startSessionRecording()
    }

    // ── Demo mode (debug builds) ─────────────────────────────────────────────

    /**
     * Stops all real vehicle communication, then hands [OpenRSDashApp.vehicleState] to the
     * simulator. A drive being recorded is finalized first so it keeps only real samples.
     * Called again while active, it just switches scenario.
     */
    fun enterDemoMode(scenario: DemoScenario): Unit = link.transition {
        if (!DemoMode.AVAILABLE) return@transition
        if (DemoMode.isActive) { DemoMode.select(scenario); return@transition }
        val recorder = OpenRSDashApp.instance.driveRecorder
        if (recorder.driveState.value.isRecording) recorder.stopDrive()
        stopConnection()
        connection = null
        adapterFirmwareApi = null
        link.requireManualConnect()
        DtcDatabase.load(this)
        DemoMode.start(scenario)
        android.util.Log.i("CAN", "Demo mode on (${scenario.name}) — adapter traffic stopped")
    }

    /** Clears simulated state and leaves the app disconnected until the user reconnects. */
    fun exitDemoMode(): Unit = link.transition {
        if (!DemoMode.isActive) return@transition
        DemoMode.stop()
        link.requireManualConnect()
        android.util.Log.i("CAN", "Demo mode off — waiting for explicit connect")
    }

}
