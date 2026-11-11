package com.openrs.dash.service

import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason

/**
 * What the recorder does when a connection sees the engine running (#212, #316 DESIGN-SUMMARY "Auto-record" / "Pauses").
 * A drive that is recording is kept (a new connection never silently finalizes it). A link pause ends: within
 * [staleAfterMs] of the loss the same drive records on; older, it is finalized and a new drive begins. A hand pause is
 * never ended by a connection (only RESUME / STOP). With no drive open, one starts if [mayStart] (auto-record on, and no
 * hand STOP on this connection). A start under way is the recorder's own case (a held loss), never decided here.
 */
internal object AutoRecordPolicy {
    enum class Action { KEEP, RESUME, FINALIZE_AND_START, START }

    fun onEngineRunning(phase: DrivePhase, pauseReason: PauseReason?, lostForMs: Long, mayStart: Boolean, staleAfterMs: Long): Action =
        when (phase) {
            DrivePhase.IDLE -> if (mayStart) Action.START else Action.KEEP
            DrivePhase.PAUSED -> when {
                pauseReason != PauseReason.LINK -> Action.KEEP
                lostForMs > staleAfterMs -> Action.FINALIZE_AND_START
                else -> Action.RESUME
            }
            DrivePhase.RECORDING, DrivePhase.STARTING -> Action.KEEP
        }
}
