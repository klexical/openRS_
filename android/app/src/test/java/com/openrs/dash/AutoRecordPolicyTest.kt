package com.openrs.dash

import com.openrs.dash.service.AutoRecordPolicy
import com.openrs.dash.service.AutoRecordPolicy.Action
import org.junit.Assert.assertEquals
import org.junit.Test

/** Auto-record on a new connection never silently finalizes a drive that is still recording (#212). */
class AutoRecordPolicyTest {
    private val window = 15 * 60_000L
    private fun decide(rec: Boolean, paused: Boolean, pausedMs: Long = 0L) =
        AutoRecordPolicy.onEngineRunning(rec, paused, pausedMs, window)

    @Test fun `a drive still recording across a reconnect or RCN retry is kept, not split`() =
        assertEquals(Action.KEEP, decide(rec = true, paused = false))

    @Test fun `a drive paused within the window resumes`() =
        assertEquals(Action.RESUME, decide(rec = true, paused = true, pausedMs = 60_000L))

    @Test fun `a drive paused past the window is finalized and a new one starts`() =
        assertEquals(Action.FINALIZE_AND_START, decide(rec = true, paused = true, pausedMs = window + 1))

    @Test fun `no drive — one starts`() = assertEquals(Action.START, decide(rec = false, paused = false))
}
