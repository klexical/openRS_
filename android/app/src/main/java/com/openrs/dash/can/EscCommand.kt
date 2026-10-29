package com.openrs.dash.can

import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.delay

/**
 * ESC mode request with vehicle confirmation (#125). The adapter's OK only means the request was accepted (the
 * firmware's frs_set_esc returns nothing): success is the car reporting the requested mode on CAN 0x1C0. The observed
 * CAN state stays authoritative; nothing here is persisted. The injection protocol itself is unchanged — no guessed
 * sequences until parked captures of the factory button exist.
 */
object EscCommand {
    sealed interface Outcome {
        /** CAN 0x1C0 reports the requested mode. */
        data class Confirmed(val afterMs: Long) : Outcome
        /** The adapter accepted the request but the car still reports [observed] after [waitedMs]. */
        data class NotConfirmed(val observed: EscStatus, val waitedMs: Long) : Outcome
        /** The adapter is busy with another command. */
        data object Busy : Outcome
        /** The adapter refused or didn't answer. */
        data class Failed(val message: String) : Outcome
        /** The link dropped while waiting for the car. */
        data object ConnectionLost : Outcome
        /** No command path (no openRS_ firmware detected). */
        data object Unsupported : Outcome
    }

    const val CONFIRM_TIMEOUT_MS = 4_000L
    private const val POLL_MS = 100L

    suspend fun execute(
        api: FirmwareCommandSender?,
        target: EscStatus,
        state: () -> VehicleState,
        clock: () -> Long = System::currentTimeMillis,
        timeoutMs: Long = CONFIRM_TIMEOUT_MS,
    ): Outcome {
        if (api == null) return Outcome.Unsupported
        val result = api.setEscMode(target.toFirmwareInt())
        result.exceptionOrNull()?.let { e ->
            return if (e is BusyException) Outcome.Busy else Outcome.Failed(e.message ?: "no reply")
        }
        val start = clock()
        while (true) {
            val vs = state()
            if (!vs.isConnected) return Outcome.ConnectionLost
            if (vs.escStatus == target) return Outcome.Confirmed(clock() - start)
            if (clock() - start >= timeoutMs) return Outcome.NotConfirmed(vs.escStatus, clock() - start)
            delay(POLL_MS)
        }
    }

    /** One line for the diagnostic log. */
    fun describe(target: EscStatus, o: Outcome): String = when (o) {
        is Outcome.Confirmed -> "CONFIRMED ${target.label} by CAN 0x1C0 after ${o.afterMs} ms"
        is Outcome.NotConfirmed -> "NOT CONFIRMED — adapter accepted ${target.label}, car still reports ${o.observed.label} after ${o.waitedMs} ms"
        Outcome.Busy -> "BUSY — adapter is handling another command"
        is Outcome.Failed -> "FAILED — ${o.message}"
        Outcome.ConnectionLost -> "CONNECTION LOST while waiting for the car"
        Outcome.Unsupported -> "UNSUPPORTED — no openRS_ firmware command path"
    }
}
