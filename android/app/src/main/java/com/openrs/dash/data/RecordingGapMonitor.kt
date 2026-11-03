package com.openrs.dash.data

/**
 * Explains gaps in a recorded drive (#221). DriveRecorder writes a point only when a location update arrives while
 * recording, not paused and connected — so a gap can come from three places, and the export couldn't tell which (a
 * 50 s gap on the 2026-10-28 PM drive had CAN data throughout). This tracker turns the recorder's own observations into
 * diagnostic events; it never creates samples, coordinates or distance — gaps stay gaps.
 *
 * Reasons: NO_LOCATION (connected, recording, not paused, but no location update arrived), DISCONNECTED (updates
 * arrived while the adapter was down — skipped), PAUSED (updates arrived while paused — skipped).
 */
class RecordingGapMonitor(private val gapAfterMs: Long = GAP_AFTER_MS) {
    enum class Reason { NO_LOCATION, DISCONNECTED, PAUSED }

    /** A finished gap: no point was written from [fromMs] to [toMs] for [reason]. */
    data class Gap(val reason: Reason, val fromMs: Long, val toMs: Long) {
        val seconds: Double get() = (toMs - fromMs) / 1000.0
        fun describe() = "%s gap %.1f s (%s)".format(java.util.Locale.ROOT, reason.name, seconds, when (reason) {
            Reason.NO_LOCATION -> "no location update while connected — telemetry not recorded"
            Reason.DISCONNECTED -> "adapter disconnected — samples skipped"
            Reason.PAUSED -> "recording paused"
        })
    }

    private var lastPointMs = 0L
    private var lastLocationMs = 0L
    private var skipReason: Reason? = null
    private var skipFromMs = 0L
    private var noLocationReported = false

    /** Recording (re)started at [nowMs]. */
    fun start(nowMs: Long) { lastPointMs = nowMs; lastLocationMs = nowMs; skipReason = null; noLocationReported = false }

    /** A location update arrived; [written] = a point was stored, else [skipped] says why. Returns a gap that ended. */
    fun onLocation(nowMs: Long, written: Boolean, skipped: Reason? = null): Gap? {
        val silent = nowMs - lastLocationMs
        lastLocationMs = nowMs
        var ended: Gap? = null
        if (written) {
            ended = when {
                skipReason != null -> Gap(skipReason!!, skipFromMs, nowMs)
                silent > gapAfterMs -> Gap(Reason.NO_LOCATION, nowMs - silent, nowMs)
                else -> null
            }
            skipReason = null
            noLocationReported = false
            lastPointMs = nowMs
        } else if (skipped != null && skipReason != skipped) {
            ended = skipReason?.let { Gap(it, skipFromMs, nowMs) }
            skipReason = skipped; skipFromMs = nowMs
        }
        return ended
    }

    /**
     * Periodic check while recording: reports once (as it starts) that location updates have stopped although the
     * adapter is connected and recording isn't paused. Null when nothing new to say.
     */
    fun tick(nowMs: Long, connected: Boolean, paused: Boolean): String? {
        if (!connected || paused || noLocationReported) return null
        val silent = nowMs - lastLocationMs
        if (silent <= gapAfterMs) return null
        noLocationReported = true
        return "no location update for %.0f s while connected — drive points paused until location returns".format(
            java.util.Locale.ROOT, silent / 1000.0)
    }

    companion object { const val GAP_AFTER_MS = 5_000L }
}
