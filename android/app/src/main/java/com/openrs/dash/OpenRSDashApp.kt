package com.openrs.dash

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.openrs.dash.data.TripState
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.TripRecorder
import com.openrs.dash.service.WeatherRepository
import com.openrs.dash.BuildConfig
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

    /** Trip recorder — lazy so it initialises only when the trip overlay is first opened. */
    val tripRecorder: TripRecorder by lazy {
        TripRecorder(
            context          = this,
            vehicleStateFlow = vehicleState.asStateFlow(),
            weatherRepo      = WeatherRepository(BuildConfig.OPENWEATHER_API_KEY)
        )
    }

    /** Convenience accessor for the trip state flow. */
    val tripState: StateFlow<TripState> get() = tripRecorder.tripState

    override fun onCreate() {
        super.onCreate()
        instance = this
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
