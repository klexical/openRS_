package com.openrs.dash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.LiveTelemetry
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import com.openrs.dash.BuildConfig
import com.openrs.dash.can.DeviceStatus
import com.openrs.dash.diagnostics.CrashReporter
import com.openrs.dash.diagnostics.CrashTelemetryBuffer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Application singleton.
 *
 * Holds the shared VehicleState flow that the CanDataService writes to
 * and all UI composables observe.
 */
class OpenRSDashApp : Application() {

    companion object {
        const val CHANNEL_CAN = "openrs_can"
        lateinit var instance: OpenRSDashApp
            private set

        /** The app, or null where it doesn't exist (JVM unit tests). */
        val instanceOrNull: OpenRSDashApp? get() = if (::instance.isInitialized) instance else null
    }

    /**
     * Live telemetry (GLOSSARY.md): the only writer of the live readings and the running figures
     * (android/docs/adr/0001-live-telemetry-single-writer.md). Thermal readiness hears its user reset.
     */
    val liveTelemetry = LiveTelemetry(thermal = object : LiveTelemetry.Thermal {
        override fun userReset() = com.openrs.dash.ui.ThermalReadinessSession.reset()
    })

    /** The live readings, read-only. Written only by [liveTelemetry]; observed by all UIs. */
    val vehicleState: StateFlow<VehicleState> = liveTelemetry.readings

    /** Real adapter link phase, owned by CanDataService (connecting / connected / reconnecting / failed / off). */
    val linkPhase = MutableStateFlow(com.openrs.dash.can.LinkPhase.OFF)

    /** DEBUG — raw WiCAN lines. Updated by CanDataService, observed by debug tab. */
    private val _debugLines = MutableStateFlow<List<String>>(emptyList())
    val debugLines: StateFlow<List<String>> = _debugLines.asStateFlow()

    fun pushDebugLine(line: String) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return
        // M-4 fix: use .update{} for atomic read-modify-write — prevents lost lines
        // when obdJob, pcmJob, and the main loop call this concurrently on IO threads.
        _debugLines.update { list ->
            val next = if (list.size >= 100) list.drop(1) else list
            next + trimmed
        }
    }

    /** Count of active DTCs from the most recent scan. Updated by DiagPage
     *  after a successful scan; cleared when the user clears codes. Feeds the
     *  GARAGE-tab badge in BottomNavBar. */
    val activeDtcCount = MutableStateFlow(0)

    /** Knock events this connection, read-only (counted by [liveTelemetry]: fresh KR > 1° retard on any cylinder).
     *  Feeds the ENGINE MANAGEMENT badge on the PERF tab. */
    val knockEventCount: StateFlow<Int> = liveTelemetry.knockCount

    /** True once openRS_ firmware is confirmed via WebSocket probe on connect. */
    val isOpenRsFirmware = MutableStateFlow(false)
    /** True while the OPENRS? capability probe is still outstanding on this connection (#220). */
    val firmwareProbing = MutableStateFlow(false)

    /** Human-readable firmware version label (e.g. "openRS_ v1.5-rc.5"). */
    val firmwareVersionLabel = MutableStateFlow("")

    /** WiCAN device status from /check_status — populated on WiFi connect, cleared on disconnect. */
    val deviceStatus = MutableStateFlow<DeviceStatus?>(null)

    /** Drive database — shared across DriveRecorder, CanDataService, and UI. */
    val driveDb: DriveDatabase by lazy { DriveDatabase.getInstance(this) }

    /** Drive recorder — owned by CanDataService, but accessible globally for UI. */
    val driveRecorder: DriveRecorder by lazy {
        DriveRecorder(
            context          = this,
            vehicleStateFlow = vehicleState,
            weatherRepo      = WeatherRepository(BuildConfig.OPENWEATHER_API_KEY),
            db               = driveDb
        )
    }

    /** Live drive state flow — observed by MAP tab. */
    val driveState: StateFlow<DriveState> get() = driveRecorder.driveState

    override fun onCreate() {
        super.onCreate()
        com.openrs.dash.ui.StartupTrace.mark("Application.onCreate start")
        instance = this
        CrashReporter.install(this)
        CrashTelemetryBuffer.startCollecting()
        com.openrs.dash.ui.ThermalReadinessSession.start()
        com.openrs.dash.data.BoostSession.log = { com.openrs.dash.diagnostics.DiagnosticLogger.event("BOOST_SOURCE", it) }
        createNotificationChannel()
        com.openrs.dash.ui.StartupTrace.mark("Application.onCreate end")
    }

    private fun createNotificationChannel() {
        // minSdk 28 — notification channels (API 26) always exist
        val channel = NotificationChannel(
            CHANNEL_CAN,
            "Vehicle Connection",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shown while openRS_ is connected to the vehicle"
            setShowBadge(false)
        }
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(channel)
    }
}
