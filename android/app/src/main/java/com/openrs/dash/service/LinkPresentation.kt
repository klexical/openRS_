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

    /**
     * Foreground notification text — only phases that keep the service in the foreground have one. Re-published on
     * recording start / pause / resume / stop as well as on phase changes. While the link is down a recording takes no
     * samples, so it reads "recording paused" whatever its own pause state.
     */
    fun notificationText(phase: LinkPhase, bluetooth: Boolean, recording: Boolean, paused: Boolean = false): String? {
        val target = if (bluetooth) "via Bluetooth" else "to vehicle"
        return when (phase) {
            LinkPhase.CONNECTING   -> if (recording) "Connecting $target… · recording paused" else "Connecting $target…"
            LinkPhase.CONNECTED    -> when {
                !recording -> "Connected $target"
                paused     -> "Connected $target · recording paused"
                else       -> "Connected $target · recording"
            }
            LinkPhase.RECONNECTING -> if (recording) "Link lost — reconnecting · recording paused" else "Link lost — reconnecting…"
            LinkPhase.OFF, LinkPhase.FAILED -> null
        }
    }

    /**
     * Cancel is offered only for an initial attempt, and never while a drive is recording — cancelling there would
     * end the foreground service the recording runs under. There is deliberately no generic Stop action.
     */
    fun offersCancel(phase: LinkPhase, recording: Boolean): Boolean = phase == LinkPhase.CONNECTING && !recording

    /**
     * The phase a foreground post (startForeground) describes (#296). A promote at a connection start runs while the previous
     * job's final phase (OFF / FAILED) is still published: it reads as the attempt that is starting. Every other post (a
     * location type change, #261) reads the published phase, so a re-type while connected can't put back "Connecting…" or
     * Cancel.
     */
    fun foregroundPhase(published: LinkPhase): LinkPhase = when (published) {
        LinkPhase.OFF, LinkPhase.FAILED -> LinkPhase.CONNECTING
        LinkPhase.CONNECTING, LinkPhase.CONNECTED, LinkPhase.RECONNECTING -> published
    }
}
