package com.openrs.dash

import android.app.Application
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.flow.MutableStateFlow

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

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
