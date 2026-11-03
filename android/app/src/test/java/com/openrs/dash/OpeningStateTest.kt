package com.openrs.dash

import com.openrs.dash.ui.OpeningState
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The opening presentation plays once per app task (#213). */
class OpeningStateTest {
    private val none = OpeningState.NO_TASK

    @Test fun `cold launch shows it`() =
        assertTrue(OpeningState.shouldShow(hasSavedState = false, taskId = 10, lastShownTask = none))

    @Test fun `a new task after Recents dismissal shows it, even in the same process`() =
        assertTrue(OpeningState.shouldShow(hasSavedState = false, taskId = 11, lastShownTask = 10))

    @Test fun `resuming or recreating the activity in the same task doesn't (Home, Back on Android 12+)`() =
        assertFalse(OpeningState.shouldShow(hasSavedState = false, taskId = 10, lastShownTask = 10))

    @Test fun `rotation (saved state) doesn't, even in another task`() {
        assertFalse(OpeningState.shouldShow(hasSavedState = true, taskId = 10, lastShownTask = 10))
        assertFalse(OpeningState.shouldShow(hasSavedState = true, taskId = 12, lastShownTask = 10))
    }
}
