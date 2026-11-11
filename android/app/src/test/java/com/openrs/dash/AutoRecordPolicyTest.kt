package com.openrs.dash

import com.openrs.dash.data.DrivePhase
import com.openrs.dash.data.PauseReason
import com.openrs.dash.service.AutoRecordPolicy
import com.openrs.dash.service.AutoRecordPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the recorder does when a connection sees the engine running (#212; #316). A recording drive is never silently
 * finalized; a link pause ends (resumed within the window from the loss, else finalized and a new drive starts); a hand
 * pause is never ended by a connection, whatever its age (#316 spec difference 1: staging resumed or finalized it too).
 */
class AutoRecordPolicyTest {
    private val window = 15 * 60_000L
    private fun decide(phase: DrivePhase, reason: PauseReason? = null, lostForMs: Long = 0L, mayStart: Boolean = true) =
        AutoRecordPolicy.onEngineRunning(phase, reason, lostForMs, mayStart, window)

    @Test fun `a drive still recording across a reconnect or RCN retry is kept, not split`() =
        assertEquals(Action.KEEP, decide(DrivePhase.RECORDING))

    @Test fun `a link pause within the window resumes - the window's end included`() {
        assertEquals(Action.RESUME, decide(DrivePhase.PAUSED, PauseReason.LINK, lostForMs = 60_000L))
        assertEquals(Action.RESUME, decide(DrivePhase.PAUSED, PauseReason.LINK, lostForMs = window))
    }

    @Test fun `a link pause past the window is finalized and a new one starts`() =
        assertEquals(Action.FINALIZE_AND_START, decide(DrivePhase.PAUSED, PauseReason.LINK, lostForMs = window + 1))

    @Test fun `a hand pause is kept whatever its age`() {
        assertEquals(Action.KEEP, decide(DrivePhase.PAUSED, PauseReason.HAND, lostForMs = 60_000L))
        assertEquals(Action.KEEP, decide(DrivePhase.PAUSED, PauseReason.HAND, lostForMs = window + 1))
    }

    @Test fun `no drive - one starts, unless auto-record may not start one`() {
        assertEquals(Action.START, decide(DrivePhase.IDLE))
        assertEquals(Action.KEEP, decide(DrivePhase.IDLE, mayStart = false))
    }

    @Test fun `a start under way is kept`() = assertEquals(Action.KEEP, decide(DrivePhase.STARTING))
}
