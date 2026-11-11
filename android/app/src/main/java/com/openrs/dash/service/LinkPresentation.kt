package com.openrs.dash.service

import com.openrs.dash.can.AdapterState
import com.openrs.dash.can.LinkPhase
import com.openrs.dash.data.DriveLabel
import com.openrs.dash.data.DriveState

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
     * Foreground notification text — only phases that keep the service in the foreground have one. Re-published on every
     * change of the drive's phase or pause reason as well as on phase changes. With a drive, it carries the drive's label
     * (#316, [DriveLabel], the same as TRIP and the header pill), its connection read from [phase]: a recording drive with
     * the link down reads REC (a recording gap), a link pause reads PAUSED · NO LINK or PAUSED · ENGINE OFF.
     */
    fun notificationText(phase: LinkPhase, bluetooth: Boolean, drive: DriveState = DriveState()): String? {
        val target = if (bluetooth) "via Bluetooth" else "to vehicle"
        val label = DriveLabel.of(drive, connected = phase == LinkPhase.CONNECTED)?.text
        val link = when (phase) {
            LinkPhase.CONNECTING   -> "Connecting $target…"
            LinkPhase.CONNECTED    -> "Connected $target"
            LinkPhase.RECONNECTING -> if (label == null) "Link lost — reconnecting…" else "Link lost — reconnecting"
            LinkPhase.OFF, LinkPhase.FAILED -> return null
        }
        return if (label == null) link else "$link · $label"
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
