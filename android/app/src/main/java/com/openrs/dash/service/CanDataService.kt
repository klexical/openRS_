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
import com.openrs.dash.diagnostics.LogText
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
        override fun enterForeground(location: Boolean) = goForeground(location = location)
        override fun exitForeground() = leaveForeground()
        override fun stopSelf() = this@CanDataService.stopSelf()
    })

    /**
     * [onLinkLost]: the connection's "connection lost" report (#316) — a dropped connection whose attempts failed.
     * [heldDropAtMs]: a drop carried over from the connection a manual reconnect replaced (#316 (b)).
     */
    private fun buildConnection(onLinkLost: (Long) -> Unit, heldDropAtMs: Long? = null): SlcanConnection {
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
            keepRetrying     = { isRecording() },
            // #271: MAP 0xF40B is polled (fast group) only while a drive is recording, not paused
            mapPoll          = { com.openrs.dash.data.MapF40b.pollActive(recorder().driveState.value) },
            onLinkLost       = onLinkLost,
            heldDropAtMs     = heldDropAtMs,
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
            recorder().driveState
                .map { it.isRecording to it.isPaused }
                .distinctUntilChanged()
                .collect { link.transition { publishNotification(link.phase); retypeForeground() } }
        }
        // #261: the location type can be added only while the app is visible — a recording started behind gets it on return
        scope.launch {
            AppVisibility.visible.collect { visible -> if (visible) link.transition { retypeForeground() } }
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

    /**
     * Owner's decision on #212: a task removal ends only an initial attempt, where the app offers Cancel, and saves nothing. Established links, reconnects
     * and open or finalizing drives are untouched. It can't tell a Recents swipe from Clear all or Recents trimming: the same callback covers them.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        link.transition {
            DiagnosticLogger.event(DiagnosticLogger.EVENT_LINK_TASK_REMOVED, LinkStopLog.taskRemovedLine(
                link.phase, connectionJob?.isActive == true, recNow(), DemoMode.isActive))
            if (!DemoMode.isActive) {
                link.cancelOnTaskRemoved(recording = { isRecording() }, finalizing = { recorder().isFinalizing }) {
                    stopConnection(LinkStopReason.TASK_REMOVED)
                }
            }
        }
        super.onTaskRemoved(rootIntent)
    }

    /** MainActivity is bound: what a task removal held back can start, so the next launch auto-connects even if this service or process survived. */
    fun onActivityBound(): Unit = link.transition {
        if (link.releaseTaskRemovedHold() && isTransportReady()) autoStartConnection()
    }

    private fun recNow(): String {
        val ds = recorder().driveState.value
        return LinkStopLog.rec(ds.isRecording, ds.isPaused)
    }

    override fun onDestroy() {
        unregisterWifiCallback()
        unregisterBluetoothCallback()
        stopConnection(LinkStopReason.SERVICE_DESTROYED)   // flush sessionEnd() before scope is torn down
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
                        DiagnosticLogger.event(DiagnosticLogger.EVENT_LINK_START_REFUSED, "auto-start failed: ${LogText.sanitizeReason(e)}")
                        android.util.Log.w("CAN", "Cannot start foreground service from background", e)
                    }
                }
            }
            override fun onLost(network: Network) {
                if (!isBluetooth()) stopConnection(LinkStopReason.WIFI_LOST)
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
                                DiagnosticLogger.event(DiagnosticLogger.EVENT_LINK_START_REFUSED, "auto-start failed: ${LogText.sanitizeReason(e)}")
                                android.util.Log.w("CAN", "Cannot start service from BT callback", e)
                            }
                        }
                    }
                    BluetoothAdapter.STATE_OFF -> stopConnection(LinkStopReason.BLUETOOTH_OFF)
                    BluetoothAdapter.STATE_TURNING_OFF -> stopConnection(LinkStopReason.BLUETOOTH_TURNING_OFF)
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
        /** Longest the foreground waits at a link end for the drive's points to be stored (#262, N2). Tests shorten it. */
        @Volatile internal var flushDemoteWaitMs = 10_000L

        /** Notification action: cancel an initial connection attempt (#212). */
        const val ACTION_CANCEL_CONNECT = "com.openrs.dash.action.CANCEL_CONNECT"


        /** The app's drive recorder: what production uses. */
        internal val APP_RECORDER: () -> DriveRecorder = { OpenRSDashApp.instance.driveRecorder }

        /**
         * #316, test seam 5: where the service gets its drive recorder. Production keeps [APP_RECORDER]; a service test hands
         * in its own before creating the service (the service binds the recorder's state at onCreate). `TestServices`
         * puts [APP_RECORDER] back at teardown.
         */
        @Volatile internal var recorderProvider: () -> DriveRecorder = APP_RECORDER
    }

    private fun recorder(): DriveRecorder = recorderProvider()

    /**
     * #316: hands the recorder the auto-record setting where a loss is reported or a connection starts. Interim: the
     * "auto-record switched" event, when the setting changes, comes in its own commit.
     */
    private fun seedAutoRecord() = recorder().onAutoRecordSwitched(AppSettings.getAutoRecordDrives(this))

    private fun isRecording(): Boolean = recorder().driveState.value.isRecording

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

    /**
     * startForeground (a promote, or a location type change #261) with the notification [publishNotification] posts for the
     * same phase and recording state (#296: a re-type while connected posted "Connecting to vehicle…" until the next phase
     * change, and after a stop a Cancel #212 doesn't offer once connected). Runs under [link]'s transition.
     */
    private fun goForeground(location: Boolean = false) {
        val ds = recorder().driveState.value
        val phase = LinkPresentation.foregroundPhase(link.phase)
        val text = LinkPresentation.notificationText(phase, isBluetooth(), ds.isRecording, ds.isPaused) ?: "Connecting to vehicle…"
        val notification = buildNotification(text, offerCancel = LinkPresentation.offersCancel(phase, ds.isRecording))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            // #261: location only while a drive records with fine location and the app was visible (ForegroundTypes)
            val types = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or
                (if (location) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0)
            ServiceCompat.startForeground(this, NOTIF_ID, notification, types)
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

    /**
     * #261: give the foreground the location type while a drive records (not paused) with fine location granted, added only
     * while the app is visible; drop it on pause / stop. Call under [link]'s transition. A no-op when not foreground, so it
     * never promotes or demotes (the link end's demote stays N2's). Each change is one FGS_LOCATION event in the export.
     */
    private fun retypeForeground() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return     // no service types before Android 10
        val ds = recorder().driveState.value
        val fine = androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.ACCESS_FINE_LOCATION) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
        val want = ForegroundTypes.wantsLocation(ds.isRecording, ds.isPaused, fine)
        val text = when (val c = foreground.applyTypes(want, AppVisibility.visible.value)) {
            ForegroundController.TypeChange.None -> null
            ForegroundController.TypeChange.Added -> "location type added (recording, app visible)"
            ForegroundController.TypeChange.Dropped -> "location type dropped (" + (if (ds.isRecording) "paused" else "not recording") + ")"
            ForegroundController.TypeChange.Deferred ->
                "recording while the app is not visible: connectedDevice only, GPS may stop until the app is opened"
            is ForegroundController.TypeChange.Refused -> "location type refused: ${LogText.sanitizeReason(c.error)}; connectedDevice only"
            is ForegroundController.TypeChange.DropRefused -> "dropping the location type refused: ${LogText.sanitizeReason(c.error)}"
        }
        text?.let {
            DiagnosticLogger.event(DiagnosticLogger.EVENT_FGS_LOCATION, it)
            android.util.Log.i("CAN", "Foreground: $it")
        }
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
                        peakRpm        = vs.session.peakRpm,
                        // Session table predates #207's sources: the CAN 0x0F8 peak, its historical meaning
                        peakBoostPsi   = (com.openrs.dash.data.BoostSession.peakKpa(com.openrs.dash.data.BoostSource.CAN_0F8) ?: 0.0) *
                            com.openrs.dash.data.BoostReading.KPA_TO_PSI,
                        peakOilTempC   = vs.session.peakOilTempC,
                        peakCoolantTempC = vs.session.peakCoolantTempC,
                        peakSpeedKph   = vs.session.peakSpeedKph,
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
     * Transport-callback entry point — honours [LinkOwnership.manualConnectRequired] (after a cancel, demo mode or a task removal). The
     * check and the start are one step under the ownership lock: a callback can't pass the check, lose the race to a
     * cancel, then start anyway.
     */
    private fun autoStartConnection() {
        link.autoStart(
            blocked = { DemoMode.isActive || DemoMode.launchRequested },
            onHeld = { holds -> DiagnosticLogger.event(DiagnosticLogger.EVENT_LINK_START_REFUSED, "auto-start held: $holds") },
        ) { beginConnection() }
    }

    /** Explicit (user) connect. No-op while demo mode owns the vehicle state. */
    fun startConnection(): Unit = link.transition {
        if (DemoMode.isActive) return@transition
        link.clearManualConnect()
        beginConnection()
    }

    /**
     * Starts a connection job unless one is running; false when nothing could start (transport down / no foreground).
     * [heldDropAtMs]: a manual reconnect's carried drop (#316 (b)), held by the new connection; unused when nothing starts.
     */
    private fun beginConnection(heldDropAtMs: Long? = null): Boolean = link.transition {
        if (DemoMode.isActive) return@transition false
        if (!isTransportReady()) return@transition false
        if (connectionJob?.isActive == true) return@transition true
        // Bluetooth with no saved device (#234): nothing to connect to — checked before the service is promoted and a
        // session begins, so no notification, no session record, no retries. Every start path lands here (launch,
        // Connect, Bluetooth on, a sticky restart with no activity); the dashboard explains it, the choice is kept.
        ConnectionSetup.problem(AppSettings.getConnectionMethod(this), AppSettings.getBleDeviceAddress(this))?.let {
            DiagnosticLogger.event(DiagnosticLogger.EVENT_LINK_START_REFUSED, "no saved Bluetooth device")
            android.util.Log.i("CAN", "Not connecting — $it")
            return@transition false
        }

        try {
            foreground.promote()
        } catch (e: Exception) {
            DiagnosticLogger.event(DiagnosticLogger.EVENT_LINK_START_REFUSED, "foreground not allowed: ${LogText.sanitizeReason(e)}")
            android.util.Log.w("CAN", "Cannot go foreground — skipping connection start", e)
            return@transition false
        }
        retypeForeground()   // #261: a reconnect while a drive records keeps (or gains) the location type

        val s = AppSettings
        val connMethod = s.getConnectionMethod(this)
        val adapterType = s.getAdapterType(this)
        val isBle = connMethod == "BLUETOOTH"
        // Kind + profile only (#263): an export never carries the adapter's MAC or name, whatever the user or vendor called it
        val profile = if (adapterType == "MEATPI_PRO") "MeatPi Pro" else "MeatPi USB"
        val transportLabel = if (isBle) {
            "Bluetooth ($profile)"
        } else {
            val protocol = if (adapterType == "MEATPI_PRO") "TCP SLCAN" else "WebSocket SLCAN"
            "$protocol ($profile)"     // the Host line keeps the address a Wi-Fi diagnosis needs
        }
        // Bluetooth starts "not requested" together with the session; Wi-Fi status writes carry this session's token (#233)
        val diagSession = DiagnosticLogger.sessionStart(
            host      = if (isBle) "Bluetooth" else s.getHost(this),
            port      = if (isBle) 0 else s.getPort(this),
            transport = transportLabel,
            prefs  = UserPrefsStore.prefs.value,
            logDir = java.io.File(filesDir, "diagnostics"),
            adapterStatusReason = if (isBle) com.openrs.dash.diagnostics.AdapterStatusRecord.BLUETOOTH
                                  else com.openrs.dash.diagnostics.AdapterStatusRecord.NO_REQUEST,
        )
        CanDecoder.resetSessionState()
        // ESC is unknown until this connection's first 0x1C0 frame — a value held from an earlier link is not a reading
        OpenRSDashApp.instance.vehicleState.update { it.copy(escStatus = com.openrs.dash.data.EscStatus.UNKNOWN) }
        // Launch control (#289): no flag or stamp held from an earlier link — "LC" needs this connection's own 0x420
        OpenRSDashApp.instance.vehicleState.update { com.openrs.dash.data.LaunchControl.clearedForNewConnection(it) }
        // SRS lamp (#199): unknown until this connection's first 0x040 frame — a value held from an earlier link is not a
        // reading. The IPC lamps are not reset here.
        OpenRSDashApp.instance.vehicleState.update { it.copy(lamps = it.lamps.copy(srs = null)) }
        FuelEconomy.reset()
        PerformanceTimer.resetSession()
        OpenRSDashApp.instance.knockEventCount.value = 0
        com.openrs.dash.data.KnockSessionLog.reset()
        com.openrs.dash.data.BoostSessionLog.reset()
        com.openrs.dash.data.BoostSession.reset()
        startSessionRecording()
        seedAutoRecord()

        var reportTo: LinkSession? = null                 // the job's session, set below; the report goes through its ownership
        val conn = buildConnection(onLinkLost = { atMs -> reportTo?.onConnectionLost(atMs) }, heldDropAtMs = heldDropAtMs)
        connection = conn
        val gen = link.begin()
        val session = newLinkSession(gen)
        reportTo = session
        applyPhase(LinkPhase.CONNECTING)

        connectionJob = scope.launch {
            launch(Dispatchers.IO) {
                lastProcessExitLine()?.let { line ->
                    link.deliver(gen) { DiagnosticLogger.event(DiagnosticLogger.EVENT_LINK_PREV_EXIT, line) }
                }
            }
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
                            if (it == LinkPhase.CONNECTED) session.onConnectionBack()       // #316
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
                        DiagnosticLogger.event("DEVICE_STATUS", LogText.sanitize(
                            "${status.hardwareVersion} fw=${status.firmwareVersion} " +
                            "can=${status.canDatarate} vbatt=${status.obdPortVoltage}V"))
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

            // #316: "engine running" on each established connection of this job goes to the recorder, which applies
            // auto-record (a drive starts unless one is open; nothing after a hand STOP on this connection) and ends a link
            // pause (the same drive within 15 minutes of the loss, else a new one). Always collected: the recorder reads
            // the auto-record setting itself.
            launch {
                OpenRSDashApp.instance.vehicleState.collect { vs -> session.onVehicleState(vs) { currentSessionId } }
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
        // Session knock log (#174) — merged live state so pull detection sees CAN rpm/throttle. Position only from the
        // recording drive's own fix, at most 2 s old (#311): none outside a drive, before its first fix or in a GPS gap
        val loc = recorder().freshFix()
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
            private val r get() = recorder()
            override val activeDriveId get() = r.driveState.value.let { if (it.isRecording) it.driveId else null }
            override fun onEngineRunning(sessionId: Long) { r.onEngineRunning(sessionId) }
            override fun onConnectionLost(atMs: Long) { seedAutoRecord(); r.onConnectionLost(atMs) }
            override fun onConnectionBack() { r.onConnectionBack() }
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

    fun stopConnection(reason: LinkStopReason): Unit = link.transition {
        val rec = recNow()   // before the loss below, so a running recording doesn't read as paused
        // #316: a closure by the service is a "connection lost", always — also for a job that never connected (and, as the
        // pause here always was, with no job under way). Its moment is the held drop when an established connection dropped
        // and its attempts are still running (reported once: the connection hands it over), else the closure's. What the
        // drive does with it is the recorder's rule (a link pause with auto-record on; never a finalize here).
        val atMs = connection?.takeUnreportedDrop() ?: System.currentTimeMillis()
        seedAutoRecord()
        recorder().onConnectionLost(atMs)
        endConnection(LinkPhase.OFF, reason, rec)
    }

    /**
     * Ends the current job (if any) and releases the foreground service. [finalPhase] is OFF for a stop / cancel and
     * FAILED when the attempts ran out. Ending ownership first means a cancelled job that is still unwinding can't
     * publish state, a merge or a notification over a newer attempt.
     */
    private fun endConnection(finalPhase: LinkPhase, reason: LinkStopReason, rec: String): Unit = link.transition {
        // The drive's points must be durable before the foreground goes (#262, N2): the barrier stores the buffered ones and
        // waits for every insert under way (a 30-point batch the loop has taken too) and a STOP's finalize. Nothing here
        // waits: the demote below runs once the barrier completes, outside link.transition.
        val durable = recorder().durabilityBarrier()
        teardownJob(finalPhase, reason, rec)
        OpenRSDashApp.instance.linkPhase.value = finalPhase
        // Demo owns the connection flags (WiFi/BT-loss callbacks still land here).
        if (!DemoMode.isActive) {
            OpenRSDashApp.instance.vehicleState.update { it.copy(isConnected = false, isReconnecting = false) }
            OpenRSDashApp.instance.deviceStatus.value = null
        }
        // Leave the foreground and the started state: with no activity bound the service now stops. With points still being
        // stored, only once they are (bounded by FLUSH_DEMOTE_WAIT_MS, so a hung write can't hold the foreground), and only if
        // no newer attempt has taken the link meanwhile (it promoted the service for itself).
        if (durable == null) foreground.demote()
        else {
            val gen = link.generation
            scope.launch {
                withTimeoutOrNull(flushDemoteWaitMs) { durable.join() }
                link.transition { if (link.owns(gen)) foreground.demote() }
            }
        }
    }

    /** Ends ownership and the job: session records closed, transport aborted, job cancelled. */
    private fun teardownJob(phase: LinkPhase, reason: LinkStopReason, rec: String) {
        val stopLine = LinkStopLog.stopLine(reason, link.phase, link.generation, rec, DemoMode.isActive)
        val stopType = if (connectionJob?.isActive == true) DiagnosticLogger.EVENT_LINK_STOP else DiagnosticLogger.EVENT_LINK_STOP_IDLE
        link.end(phase)
        stopSessionRecording()
        DiagnosticLogger.event(stopType, stopLine)
        DiagnosticLogger.sessionEnd()
        connection?.abort()          // a blocking connect / read returns now instead of after its timeout
        connectionJob?.cancel()
        connectionJob = null
    }

    /** How this app's last process ended, as the system recorded it (#212): API 30+, never fails a connection. */
    private fun lastProcessExitLine(): String? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return try {
            getSystemService(android.app.ActivityManager::class.java)
                .getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull()?.let {
                    LinkStopLog.exitLine(it.reason, it.status, it.importance,
                        (System.currentTimeMillis() - it.timestamp).coerceAtLeast(0L), it.description)
                }
        } catch (e: Exception) {
            null
        }
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
        val ds = recorder().driveState.value
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
        val rec = recNow()
        // #316: no pause here — a dropped connection's loss was reported by the connection when its attempts failed
        // (before FAILED was published), and a job that never connected reports none
        endConnection(LinkPhase.FAILED, LinkStopReason.ATTEMPTS_EXHAUSTED, rec)
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
            stopConnection(LinkStopReason.USER_CANCEL)
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
        // #316 (b), owner "Yes, carry it": a drop the old connection holds (its attempts were still under way) is not
        // discarded with the old job. The new connection holds it: reported with its original moment if the new attempts
        // fail, cleared by a connect. The tap itself reports nothing. Taken before the teardown ends the old job.
        val heldDrop = connection?.takeUnreportedDrop()
        // Old job, its session record and diagnostic session end before the new ones begin
        val rec = recNow()
        teardownJob(LinkPhase.OFF, LinkStopReason.USER_RETRY, rec)
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
        if (!beginConnection(heldDropAtMs = heldDrop)) {
            // #316: nothing started, so this RCN closed the connection: a "connection lost", as every closure by the service
            // is (the owner's "Yes, pause it"), at the carried drop's moment when one was held, else the closure's
            seedAutoRecord()
            recorder().onConnectionLost(heldDrop ?: System.currentTimeMillis())
            endConnection(LinkPhase.OFF, LinkStopReason.RETRY_NOT_STARTED, rec)
        }
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
        val recorder = recorder()
        if (recorder.driveState.value.isRecording) recorder.stopDrive()
        stopConnection(LinkStopReason.DEMO_ENTRY)
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
