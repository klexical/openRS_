package com.openrs.dash.can

import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.LogText
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicBoolean

/**
 * ESC mode request with vehicle confirmation (#125). The adapter's OK only means the request was accepted — the
 * firmware queues the button injection and answers at once — so success is the car reporting the requested mode on
 * CAN 0x1C0 *after* the request, on the *same* connection:
 *  - the wait is sized from the firmware's own button timing ([pressMs]: a 5 s long press for OFF, 300 ms short
 *    presses, 1 s between two presses) plus [REACTION_MS] for the ABS module to report it — the old fixed 4 s ended
 *    before a long press had even finished;
 *  - rule C: the last two 0x1C0 frames must both report the requested mode, both received after the request, read at a poll
 *    ([CanDecoder.ESC_FRAME_TAG], [CanDecoder.ESC_FRAME_PREV_TAG]). A held or earlier matching state and a single stray frame
 *    never confirm; a two-frame blip seen by a poll still can. A real change costs one frame interval, and one whose second
 *    frame doesn't arrive inside the window reads NOT CONFIRMED with the car on the target;
 *  - the connection identity ([connectionId], the link's connection epoch) must not change while waiting: a reconnect is
 *    ConnectionLost even if the new link then reports the requested mode;
 *  - one request at a time in the app: a second one while a request is waiting is Busy, nothing sent.
 * The observed CAN state stays authoritative; nothing here is persisted. The injection protocol itself is unchanged
 * — no guessed sequences until parked captures of the factory button exist.
 */
object EscCommand {
    sealed interface Outcome {
        /** The last two 0x1C0 frames, both after the request, report the requested mode. */
        data class Confirmed(val afterMs: Long) : Outcome
        /** The adapter accepted the request but two post-request frames didn't report it within [waitedMs]; [observed] = last state. */
        data class NotConfirmed(val observed: EscStatus, val waitedMs: Long) : Outcome
        /** The adapter (or another request in this app) is busy with an ESC change. */
        data object Busy : Outcome
        /** The adapter refused or didn't answer. */
        data class Failed(val message: String) : Outcome
        /** The link dropped or was replaced while waiting for the car. */
        data object ConnectionLost : Outcome
        /** No command path (no openRS_ firmware detected). */
        data object Unsupported : Outcome
    }

    /** Firmware button timing (firmware/components/focusrs: FRS_ESC_SHORT_PRESS_MS / _LONG_PRESS_MS, 1 s between presses). */
    const val SHORT_PRESS_MS = 300L
    const val LONG_PRESS_MS = 5_000L
    const val PRESS_GAP_MS = 1_000L
    /**
     * After the injection ends: ABS reacting + the next 0x1C0 frame + its stamp. **Provisional** — not
     * measured on a car. Reaching the window's end is NOT CONFIRMED, never inferred success.
     */
    const val REACTION_MS = 3_000L
    const val STAMP_TAG = "escStatus"
    private const val POLL_MS = 100L

    /** How long the firmware's injection for [from] → [to] takes (its own plan in frs_esc_mode_task). */
    fun pressMs(from: EscStatus, to: EscStatus): Long = when {
        from == to -> 0L
        to == EscStatus.OFF -> LONG_PRESS_MS
        to == EscStatus.PARTIAL && from == EscStatus.OFF -> SHORT_PRESS_MS + PRESS_GAP_MS + SHORT_PRESS_MS
        else -> SHORT_PRESS_MS
    }

    /** Total confirmation window for [from] → [to]. */
    fun windowMs(from: EscStatus, to: EscStatus): Long = pressMs(from, to) + REACTION_MS

    private val inFlight = AtomicBoolean(false)

    suspend fun execute(
        api: FirmwareCommandSender?,
        target: EscStatus,
        state: () -> VehicleState,
        connectionId: () -> Int = { com.openrs.dash.service.CanDataService.link.connectionEpoch },
        clock: () -> Long = System::currentTimeMillis,
    ): Outcome {
        if (api == null) return Outcome.Unsupported
        if (!inFlight.compareAndSet(false, true)) return Outcome.Busy
        try {
            val from = state().escStatus
            val link = connectionId()
            val requestedAt = clock()
            val result = api.setEscMode(target.toFirmwareInt())
            result.exceptionOrNull()?.let { e ->
                return if (e is BusyException) Outcome.Busy else Outcome.Failed(e.message ?: "no reply")
            }
            val window = windowMs(from, target)
            while (true) {
                val vs = state()
                val elapsed = clock() - requestedAt
                if (!vs.isConnected || connectionId() != link) return Outcome.ConnectionLost
                val last = vs.fieldLastUpdateMs[CanDecoder.ESC_FRAME_TAG]
                val prev = vs.fieldLastUpdateMs[CanDecoder.ESC_FRAME_PREV_TAG]
                if (vs.escStatus == target && last != null && prev != null && last > requestedAt && prev > requestedAt) {
                    return Outcome.Confirmed(elapsed)
                }
                if (elapsed >= window) return Outcome.NotConfirmed(vs.escStatus, elapsed)
                delay(POLL_MS)
            }
        } finally {
            inFlight.set(false)
        }
    }

    /** One line for the diagnostic log. */
    fun describe(target: EscStatus, o: Outcome): String = when (o) {
        is Outcome.Confirmed -> "CONFIRMED ${target.label} by two CAN 0x1C0 frames after the request (${o.afterMs} ms)"
        is Outcome.NotConfirmed -> "NOT CONFIRMED — adapter accepted ${target.label}, two 0x1C0 frames after the request didn't report it " +
            "in ${o.waitedMs} ms (car reports ${o.observed.label})"
        Outcome.Busy -> "BUSY — an ESC change is already in progress"
        is Outcome.Failed -> "FAILED — ${LogText.sanitize(o.message)}"
        Outcome.ConnectionLost -> "CONNECTION LOST or replaced while waiting for the car"
        Outcome.Unsupported -> "UNSUPPORTED — no openRS_ firmware command path"
    }
}
