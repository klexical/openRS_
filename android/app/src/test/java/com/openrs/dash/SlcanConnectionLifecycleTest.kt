package com.openrs.dash

import com.openrs.dash.can.AdapterState
import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.plus
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Connection lifecycle with a controlled transport on virtual time (#212): truthful Connecting / Reconnecting, attempts
 * that run out end the job, a recording keeps retrying, cancel and abort end attempts promptly and close transports.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SlcanConnectionLifecycleTest {

    /** One scripted attempt: open fails, or open succeeds and the adapter answers then drops / stays up. */
    private sealed interface Attempt {
        data object Fails : Attempt
        data object ConnectsThenDrops : Attempt
        data object ConnectsAndHolds : Attempt
        data object BlocksUntilClosed : Attempt
    }

    private class FakeTransport(private val attempt: Attempt) : SlcanTransport {
        var closed = false
        private val closedSignal = CompletableDeferred<Unit>()
        private var lines = ArrayDeque(listOf("t09080000C1FF617D0000"))   // a 0x090 frame: SLCAN handshake OK
        override suspend fun open() {
            when (attempt) {
                Attempt.Fails -> throw java.io.IOException("connect timed out")
                Attempt.BlocksUntilClosed -> { closedSignal.await(); throw java.io.IOException("socket closed") }
                else -> Unit
            }
        }
        override suspend fun readLine(): String? = lines.removeFirstOrNull() ?: when (attempt) {
            Attempt.ConnectsAndHolds -> awaitCancellation()
            else -> null                                                    // EOF — the link dropped
        }
        override suspend fun writeLine(frame: String) = Unit
        override fun close() { closed = true; closedSignal.complete(Unit) }
        override val label = "fake"
        override val stockFirmwareLabel = "fake fw"
    }

    private class Harness(script: List<SlcanConnectionLifecycleTest.Attempt>) {
        private val queue = ArrayDeque(script)
        val transports = mutableListOf<FakeTransport>()
        fun next(): FakeTransport = FakeTransport(queue.removeFirstOrNull() ?: Attempt.Fails).also { transports += it }
    }

    private fun kotlinx.coroutines.test.TestScope.connection(
        h: Harness, maxRetries: Int = 3, keepRetrying: () -> Boolean = { false },
    ) = SlcanConnection(
        transportFactory = { h.next() }, maxRetries = maxRetries, reconnectDelayMs = 2_000L,
        keepRetrying = keepRetrying, ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {},
    )

    private fun kotlinx.coroutines.test.TestScope.record(conn: SlcanConnection): MutableList<AdapterState> {
        val seen = mutableListOf<AdapterState>()
        conn.state.onEach { seen += it }.launchIn(backgroundScope + UnconfinedTestDispatcher(testScheduler))
        return seen
    }

    private fun SlcanConnection.run() = connectHybrid(onObdUpdate = {}, getCurrentState = { VehicleState() })

    @Test fun `initial attempts that all fail report Connecting, then Idle — never Reconnecting`() = runTest {
        val h = Harness(listOf(Attempt.Fails, Attempt.Fails, Attempt.Fails))
        val conn = connection(h)
        val seen = record(conn)
        conn.run().collect {}                                           // completes: attempts ran out
        assertEquals(3, h.transports.size)
        assertEquals(5_000L + 15_000L, testScheduler.currentTime)       // BACKOFF_MS between attempts
        assertTrue(seen.contains(AdapterState.Connecting))
        assertFalse(seen.contains(AdapterState.Reconnecting))
        assertEquals(AdapterState.Idle, conn.state.value)
        assertTrue(h.transports.all { it.closed })
    }

    @Test fun `a dropped established link reports Reconnecting, then Idle when its retries run out`() = runTest {
        val h = Harness(listOf(Attempt.ConnectsThenDrops, Attempt.Fails, Attempt.Fails))
        val conn = connection(h, maxRetries = 2)
        val seen = record(conn)
        conn.run().collect {}
        val firstConnected = seen.indexOf(AdapterState.Connected)
        assertTrue(firstConnected > 0)
        assertEquals(AdapterState.Connecting, seen.subList(0, firstConnected).last())
        val after = seen.subList(firstConnected + 1, seen.size)
        assertTrue("after the drop: $after", after.contains(AdapterState.Reconnecting))
        assertFalse(after.contains(AdapterState.Connecting))
        assertEquals(AdapterState.Idle, conn.state.value)
    }

    @Test fun `a recording keeps retrying past maxRetries until the link returns`() = runTest {
        val h = Harness(listOf(Attempt.ConnectsThenDrops, Attempt.Fails, Attempt.Fails, Attempt.Fails, Attempt.Fails,
            Attempt.ConnectsAndHolds))
        val conn = connection(h, maxRetries = 2, keepRetrying = { true })
        val seen = record(conn)
        val job = launch { conn.run().collect {} }
        advanceTimeBy(10 * 60_000L)
        assertEquals(6, h.transports.size)
        assertEquals(AdapterState.Connected, conn.state.value)
        assertFalse(seen.contains(AdapterState.Idle))
        job.cancel(); advanceUntilIdle()
        assertTrue(h.transports.all { it.closed })
    }

    @Test fun `recording ends during the 30 s recording backoff — released within a second, no further attempt`() = runTest {
        val h = Harness(listOf(Attempt.ConnectsThenDrops, Attempt.Fails, Attempt.Fails, Attempt.Fails, Attempt.Fails))
        var recording = true
        val conn = connection(h, maxRetries = 2, keepRetrying = { recording })
        val job = launch { conn.run().collect {} }
        advanceTimeBy(60_000L)                                         // budget (2) spent, now in a 30 s recording backoff
        val attempts = h.transports.size
        assertTrue("extended retries ran: $attempts", attempts >= 3)
        assertEquals(AdapterState.Reconnecting, conn.state.value)
        recording = false
        advanceTimeBy(1_001L)
        assertEquals(AdapterState.Idle, conn.state.value)
        assertTrue(job.isCompleted)
        advanceTimeBy(5 * 60_000L)
        assertEquals("no attempt after the recording ended", attempts, h.transports.size)
        assertTrue(h.transports.all { it.closed })
    }

    @Test fun `recording ending while connected leaves a healthy link alone`() = runTest {
        val h = Harness(listOf(Attempt.ConnectsAndHolds))
        var recording = true
        val conn = connection(h, maxRetries = 1, keepRetrying = { recording })
        val job = launch { conn.run().collect {} }
        advanceTimeBy(10_000L)
        assertEquals(AdapterState.Connected, conn.state.value)
        recording = false
        advanceTimeBy(5 * 60_000L)
        assertEquals(AdapterState.Connected, conn.state.value)
        assertEquals(1, h.transports.size)
        job.cancel(); advanceUntilIdle()
    }

    @Test fun `recording ending with retry budget left continues the ordinary bounded retries`() = runTest {
        val h = Harness(listOf(Attempt.ConnectsThenDrops, Attempt.Fails, Attempt.Fails, Attempt.Fails))
        var recording = true
        val conn = connection(h, maxRetries = 3, keepRetrying = { recording })
        val seen = record(conn)
        val job = launch { conn.run().collect {} }
        runCurrent()                                                   // connected, dropped, first reconnect backoff
        recording = false
        advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertEquals(4, h.transports.size)                             // 1 connection + the ordinary 3 retries
        assertEquals(AdapterState.Idle, conn.state.value)
        assertTrue(seen.contains(AdapterState.Reconnecting))
    }

    @Test fun `cancelling during backoff ends the job at once and starts no further attempt`() = runTest {
        val h = Harness(listOf(Attempt.Fails, Attempt.Fails, Attempt.Fails))
        val conn = connection(h)
        val job = launch { conn.run().collect {} }
        advanceTimeBy(1_000L)                                           // first attempt failed, 5 s backoff running
        assertEquals(1, h.transports.size)
        job.cancel(); advanceTimeBy(60_000L)
        assertTrue(job.isCompleted)
        assertEquals(1, h.transports.size)
        assertTrue(h.transports.single().closed)
    }

    @Test fun `abort closes an attempt blocked opening, so cancel doesn't wait for the connect timeout`() = runTest {
        val h = Harness(listOf(Attempt.BlocksUntilClosed))
        val conn = connection(h, maxRetries = 1)
        val job = launch { conn.run().collect {} }
        runCurrent()
        assertFalse(h.transports.single().closed)
        conn.abort()
        job.cancel(); advanceUntilIdle()
        assertTrue(job.isCompleted)
        assertTrue(h.transports.single().closed)
    }
}
