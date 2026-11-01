package com.openrs.dash.service

/**
 * Settings a connection needs before anything starts (#234). Bluetooth selected with no saved device has nothing to
 * connect to: the transport factory threw inside the connection job and crashed the app at every launch. One rule for
 * Settings SAVE (the draft), the service start (the saved settings) and the dashboard's explanation — the saved choice
 * is reported, never switched to Wi-Fi behind the user's back.
 */
object ConnectionSetup {
    const val NO_BLE_DEVICE = "Select a Bluetooth device, or switch to Wi-Fi."

    /** Why a connection can't start with these settings, or null when it can. */
    fun problem(connectionMethod: String, bleAddress: String?): String? =
        if (connectionMethod == "BLUETOOTH" && bleAddress.isNullOrBlank()) NO_BLE_DEVICE else null
}
