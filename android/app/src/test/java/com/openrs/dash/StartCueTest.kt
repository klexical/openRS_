package com.openrs.dash

import com.openrs.dash.data.CommandResult
import com.openrs.dash.data.Refusal
import com.openrs.dash.ui.trip.START_CUE_MS
import com.openrs.dash.ui.trip.StartCue
import com.openrs.dash.ui.trip.onStartTapped
import com.openrs.dash.ui.trip.startRefusalText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #316 (SPEC story 9, DESIGN-SUMMARY "Commands"): a refused START shows its reason on TRIP's START button for 2 seconds,
 * as the start/finish chip's cue does (#311). Pure: the cue holds no Compose state (android-general.md); TRIP mirrors it.
 */
class StartCueTest {
    private val t0 = 1_000_000L

    @Test fun `a refused START shows its reason for 2 seconds, then the button reads START again`() {
        val cue = StartCue()
        assertEquals(CommandResult.Refused(Refusal.DRIVE_OPEN),
            onStartTapped({ CommandResult.Refused(Refusal.DRIVE_OPEN) }, cue, t0))
        assertEquals(startRefusalText(Refusal.DRIVE_OPEN), cue.text(t0))
        assertEquals(START_CUE_MS, cue.remainingMs(t0))
        assertEquals(startRefusalText(Refusal.DRIVE_OPEN), cue.text(t0 + START_CUE_MS - 1))
        assertNull(cue.text(t0 + START_CUE_MS))
    }

    @Test fun `a START that is done shows nothing`() {
        val cue = StartCue()
        assertEquals(CommandResult.Done, onStartTapped({ CommandResult.Done }, cue, t0))
        assertNull(cue.text(t0))
        assertEquals(0L, cue.remainingMs(t0))
    }

    @Test fun `a second refusal inside the window neither restarts nor extends it, and keeps the first reason`() {
        val cue = StartCue()
        onStartTapped({ CommandResult.Refused(Refusal.STARTING) }, cue, t0)
        onStartTapped({ CommandResult.Refused(Refusal.DRIVE_OPEN) }, cue, t0 + 1_500)
        assertEquals(startRefusalText(Refusal.STARTING), cue.text(t0 + 1_500))
        assertEquals(START_CUE_MS - 1_500, cue.remainingMs(t0 + 1_500))
    }

    @Test fun `every START refusal has its own words`() {
        val texts = listOf(Refusal.DEMO, Refusal.DRIVE_OPEN, Refusal.STARTING).map { startRefusalText(it) }
        assertEquals(texts.distinct(), texts)
        assertTrue(texts.none { it.isBlank() })
        assertFalse(texts.contains("START"))
    }
}
