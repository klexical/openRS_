package com.openrs.dash

import android.app.Application
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

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
        val current = _debugLines.value.toMutableList()
        if (current.size >= 100) current.removeAt(0)
        current.add(trimmed)
        _debugLines.value = current
    }

    /** True once openRS_ firmware is confirmed via WebSocket probe on connect. */
    val isOpenRsFirmware = MutableStateFlow(false)

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
