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
    /** An attempt (or its backoff) before this connection job has ever reached [Connected]. */
    data object Connecting   : AdapterState()
    data object Connected    : AdapterState()
    /** Retrying after this job's link was established and then dropped (attempt or backoff). */
    data object Reconnecting : AdapterState()
    /** Terminal: all retry attempts exhausted — the job ends; a new connect is needed. */
    data object Idle         : AdapterState()
    data class  Error(val message: String) : AdapterState()

    /** Readable name for diagnostics — fixed strings, so R8 class-name minification can't turn them into `a` / `b`. */
    val label: String get() = when (this) {
        Disconnected -> "Disconnected"
        Connecting   -> "Connecting"
        Connected    -> "Connected"
        Reconnecting -> "Reconnecting"
        Idle         -> "Attempts exhausted"
        is Error     -> "Error: $message"
    }
}

/**
 * The service's view of the real adapter link (demo mode never sets it). Lives outside [com.openrs.dash.data.VehicleState]
 * (JVM slot ceiling); connected / reconnecting are mirrored there so demo frames can simulate them.
 */
enum class LinkPhase {
    /** No connection job: never started, cancelled, stopped or lost Wi-Fi / Bluetooth. */
    OFF,
    /** Initial attempt or its backoff — cancellable. */
    CONNECTING,
    CONNECTED,
    /** An established link dropped and is being retried. */
    RECONNECTING,
    /** Attempts exhausted — the job and the foreground service have ended. */
    FAILED,
}
