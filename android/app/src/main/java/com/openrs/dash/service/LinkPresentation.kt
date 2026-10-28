package com.openrs.dash.service

import com.openrs.dash.can.AdapterState
import com.openrs.dash.can.LinkPhase

/**
 * Pure mapping from the adapter connection state to what [CanDataService] publishes (#212): the link phase, the
 * foreground notification's text and whether it offers Cancel. Kept free of Android types so it is unit-tested.
 */
internal object LinkPresentation {

    /** Phase for an adapter state; null = no change (a transient error between an attempt and its backoff). */
    fun phaseFor(state: AdapterState): LinkPhase? = when (state) {
        // The connection's initial value: the job has started its first attempt
        AdapterState.Disconnected, AdapterState.Connecting -> LinkPhase.CONNECTING
        AdapterState.Connected    -> LinkPhase.CONNECTED
        AdapterState.Reconnecting -> LinkPhase.RECONNECTING
        AdapterState.Idle         -> LinkPhase.FAILED
        is AdapterState.Error     -> null
    }

    /** Foreground notification text — only phases that keep the service in the foreground have one. */
    fun notificationText(phase: LinkPhase, bluetooth: Boolean, recording: Boolean): String? {
        val target = if (bluetooth) "via Bluetooth" else "to vehicle"
        return when (phase) {
            LinkPhase.CONNECTING   -> "Connecting $target…"
            LinkPhase.CONNECTED    -> if (recording) "Connected $target · recording" else "Connected $target"
            LinkPhase.RECONNECTING -> if (recording) "Link lost — reconnecting · recording paused" else "Link lost — reconnecting…"
            LinkPhase.OFF, LinkPhase.FAILED -> null
        }
    }

    /**
     * Cancel is offered only for an initial attempt, and never while a drive is recording — cancelling there would
     * end the foreground service the recording runs under. There is deliberately no generic Stop action.
     */
    fun offersCancel(phase: LinkPhase, recording: Boolean): Boolean = phase == LinkPhase.CONNECTING && !recording
}
