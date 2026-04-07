package com.openrs.dash.can

/**
 * Connection state shared by all CAN adapter implementations.
 *
 * [SlcanConnection] exposes a `StateFlow<AdapterState>` so the service
 * and UI layer can observe connection lifecycle without knowing the
 * transport type.
 */
sealed class AdapterState {
    data object Disconnected : AdapterState()
    data object Connecting   : AdapterState()
    data object Connected    : AdapterState()
    /** All retry attempts exhausted — waiting for manual reconnect. */
    data object Idle         : AdapterState()
    data class  Error(val message: String) : AdapterState()
}
