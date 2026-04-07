package com.openrs.dash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveState
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.DriveRecorder
import com.openrs.dash.service.WeatherRepository
import com.openrs.dash.BuildConfig
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
    }

    /** Global vehicle state. Updated by CanDataService, observed by all UIs. */
    val vehicleState = MutableStateFlow(VehicleState())

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

    /** True once openRS_ firmware is confirmed via WebSocket probe on connect. */
    val isOpenRsFirmware = MutableStateFlow(false)

    /** Human-readable firmware version label (e.g. "openRS_ v1.5-rc.5"). */
    val firmwareVersionLabel = MutableStateFlow("")

    /** Drive database — shared across DriveRecorder, CanDataService, and UI. */
    val driveDb: DriveDatabase by lazy { DriveDatabase.getInstance(this) }

    /** Drive recorder — owned by CanDataService, but accessible globally for UI. */
    val driveRecorder: DriveRecorder by lazy {
        DriveRecorder(
            context          = this,
            vehicleStateFlow = vehicleState.asStateFlow(),
            weatherRepo      = WeatherRepository(BuildConfig.OPENWEATHER_API_KEY),
            db               = driveDb
        )
    }

    /** Live drive state flow — observed by MAP tab. */
    val driveState: StateFlow<DriveState> get() = driveRecorder.driveState

    override fun onCreate() {
        super.onCreate()
        instance = this
        CrashReporter.install(this)
        CrashTelemetryBuffer.startCollecting()
        createNotificationChannel()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
}
