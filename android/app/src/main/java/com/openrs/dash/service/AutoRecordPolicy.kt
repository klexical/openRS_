package com.openrs.dash.service

/**
 * What auto-record does when a new connection first sees the engine running (#212). A drive that is still recording
 * — started by hand, or carried across a reconnect / RCN retry — is kept as it is: a new connection never silently
 * finalizes it. A drive paused within the resume window continues; a drive paused for longer is finalized and a new
 * one begins (the stale-drive rule); with no drive, one starts.
 */
internal object AutoRecordPolicy {
    enum class Action { KEEP, RESUME, FINALIZE_AND_START, START }

    fun onEngineRunning(isRecording: Boolean, isPaused: Boolean, pausedMs: Long, resumeWindowMs: Long): Action = when {
        isRecording && !isPaused -> Action.KEEP
        isRecording && pausedMs in 1L..resumeWindowMs -> Action.RESUME
        isRecording -> Action.FINALIZE_AND_START
        else -> Action.START
    }
}
