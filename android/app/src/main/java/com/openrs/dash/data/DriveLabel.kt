package com.openrs.dash.data

/** A recorder command TRIP offers as a button for the drive's state (#316). */
enum class DriveAction { PAUSE, RESUME, STOP }

/**
 * What TRIP, the header pill and the notification show for the drive (#316, seam 3): one [text] and the commands
 * offered beside it. The three surfaces read it from [DriveLabel.of], so they never disagree.
 */
data class DriveLabel(val text: String, val actions: List<DriveAction>) {
    companion object {
        /**
         * The label for a drive in [phase] (with [pauseReason] while paused). [connected] is the connection indicator's own
         * source (`VehicleState.isConnected` on screen, the published link phase in the service), so a pause label never
         * contradicts it. Null while idle: no drive, nothing to show.
         */
        fun of(phase: DrivePhase, pauseReason: PauseReason?, connected: Boolean): DriveLabel? = when (phase) {
            DrivePhase.IDLE -> null
            DrivePhase.STARTING -> DriveLabel("STARTING", emptyList())
            DrivePhase.RECORDING -> DriveLabel("REC", listOf(DriveAction.PAUSE, DriveAction.STOP))
            DrivePhase.PAUSED -> DriveLabel(
                when {
                    pauseReason != PauseReason.LINK -> "PAUSED"
                    // a link pause with the connection back: the engine is not running yet (it would have ended the pause)
                    connected -> "PAUSED · ENGINE OFF"
                    else -> "PAUSED · NO LINK"
                },
                listOf(DriveAction.RESUME, DriveAction.STOP),
            )
        }

        fun of(state: DriveState, connected: Boolean): DriveLabel? = of(state.phase, state.pauseReason, connected)
    }
}
