package com.openrs.dash.ui

import com.openrs.dash.can.LinkPhase
import com.openrs.dash.data.VehicleState

/**
 * What the UI says about the adapter link (#212) — one source for the header pill, DRIVE's status row and the Custom
 * Dashboard title. Connected / reconnecting come from [VehicleState] so demo mode can simulate them; connecting and
 * failed come from the service's [LinkPhase] (a real link only).
 */
enum class LinkUi(val pill: String) {
    LIVE("LIVE"),
    /** An established link dropped and is being retried — tap retries now. */
    RECONNECTING("RCN"),
    /** Initial attempt or its backoff — tap cancels. */
    CONNECTING("CONN"),
    /** Not connected, nothing running — tap connects. */
    OFFLINE("OFF"),
    /** The last attempts ran out (nothing running now) — tap connects. */
    FAILED("OFF"),
}

fun linkUi(vs: VehicleState, phase: LinkPhase, demoActive: Boolean): LinkUi = when {
    vs.isConnected    -> LinkUi.LIVE
    vs.isReconnecting -> LinkUi.RECONNECTING
    demoActive        -> LinkUi.OFFLINE          // a real attempt can't run while demo owns the state
    phase == LinkPhase.CONNECTING -> LinkUi.CONNECTING
    phase == LinkPhase.FAILED     -> LinkUi.FAILED
    else -> LinkUi.OFFLINE
}
