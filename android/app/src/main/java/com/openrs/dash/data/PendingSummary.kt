package com.openrs.dash.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * The post-drive summary waiting to be shown on TRIP. [post]ed by DriveRecorder when a drive is finalized,
 * [consume]d once when the user closes the sheet — so it survives tab changes, TRIP not having been opened
 * yet and activity recreation (DriveRecorder is app-scoped), and is never shown twice. A newer drive
 * replaces an unseen older one.
 */
class PendingSummary {
    private val _drive = MutableStateFlow<DriveEntity?>(null)
    val drive: StateFlow<DriveEntity?> = _drive.asStateFlow()

    fun post(drive: DriveEntity) { _drive.value = drive }

    /** Clears [driveId] if it is still the pending summary; a drive posted since stays queued. */
    fun consume(driveId: Long) { _drive.update { if (it?.id == driveId) null else it } }
}
