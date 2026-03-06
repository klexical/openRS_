package com.openrs.dash

import android.app.Application
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Application singleton.
 *
 * Holds the shared VehicleState flow that the CanDataService writes to
 * and both the phone UI and Android Auto screens observe.
 */
class OpenRSDashApp : Application() {

    companion object {
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

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
