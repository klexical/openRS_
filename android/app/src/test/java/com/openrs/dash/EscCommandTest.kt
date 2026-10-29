package com.openrs.dash

import com.openrs.dash.can.BusyException
import com.openrs.dash.can.EscCommand
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** #125 — an adapter OK is request acceptance; ESC success is CAN 0x1C0 reporting the requested mode. */
@OptIn(ExperimentalCoroutinesApi::class)
class EscCommandTest {
    private class Api(private val result: Result<Unit>) : FirmwareCommandSender {
        var sent = mutableListOf<Int>()
        override suspend fun setDriveMode(mode: Int) = Result.success(Unit)
        override suspend fun setEscMode(mode: Int): Result<Unit> { sent += mode; return result }
    }
    private var vs = VehicleState(isConnected = true, escStatus = EscStatus.ON)

    @Test fun `acknowledged but the car never changes — not confirmed, with the observed state`() = runTest {
        val api = Api(Result.success(Unit))
        val o = EscCommand.execute(api, EscStatus.OFF, { vs }, clock = { testScheduler.currentTime })
        assertEquals(EscCommand.Outcome.NotConfirmed(EscStatus.ON, EscCommand.CONFIRM_TIMEOUT_MS), o)
        assertEquals(listOf(2), api.sent)
        assertTrue(EscCommand.describe(EscStatus.OFF, o).startsWith("NOT CONFIRMED"))
    }

    @Test fun `confirmed when CAN 0x1C0 reports the requested mode`() = runTest {
        launch { delay(1_200L); vs = vs.copy(escStatus = EscStatus.PARTIAL) }
        val o = EscCommand.execute(Api(Result.success(Unit)), EscStatus.PARTIAL, { vs }, clock = { testScheduler.currentTime })
        assertEquals(EscCommand.Outcome.Confirmed(1_200L), o)
    }

    @Test fun `busy, refused and lost link are distinct outcomes`() = runTest {
        assertEquals(EscCommand.Outcome.Busy, EscCommand.execute(Api(Result.failure(BusyException())), EscStatus.OFF, { vs }))
        assertTrue(EscCommand.execute(Api(Result.failure(RuntimeException("AT+FRS timeout"))), EscStatus.OFF, { vs }) is EscCommand.Outcome.Failed)
        launch { delay(500L); vs = vs.copy(isConnected = false) }
        assertEquals(EscCommand.Outcome.ConnectionLost,
            EscCommand.execute(Api(Result.success(Unit)), EscStatus.OFF, { vs }, clock = { testScheduler.currentTime }))
    }

    @Test fun `no command path is unsupported, nothing sent`() = runTest {
        assertEquals(EscCommand.Outcome.Unsupported, EscCommand.execute(null, EscStatus.OFF, { vs }))
    }
}
