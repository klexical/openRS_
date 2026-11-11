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
        /** Connects, then the read ends [afterMs] later (virtual time): the moment the app finds the link dropped. */
        data class ConnectsThenDropsAfter(val afterMs: Long) : Attempt
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
            is Attempt.ConnectsThenDropsAfter -> { kotlinx.coroutines.delay(attempt.afterMs); null }
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
        onLinkLost: (Long) -> Unit = {},
    ) = SlcanConnection(
        transportFactory = { h.next() }, maxRetries = maxRetries, reconnectDelayMs = 2_000L,
        keepRetrying = keepRetrying, ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {},
        clock = { testScheduler.currentTime }, onLinkLost = onLinkLost,
    )

    /** Each "connection lost" the connection reports: (virtual time it was reported, the drop time it carries). */
    private fun kotlinx.coroutines.test.TestScope.losses() = mutableListOf<Pair<Long, Long>>()
    private fun kotlinx.coroutines.test.TestScope.into(l: MutableList<Pair<Long, Long>>): (Long) -> Unit =
        { at -> l += testScheduler.currentTime to at }

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

    // ── #234: a transport factory that throws (Bluetooth selected, no device saved) ─────────────────────────────

    @Test fun `a factory that throws is a failed attempt — no exception escapes, the usual backoff, then Idle`() = runTest {
        var built = 0
        val conn = SlcanConnection(
            transportFactory = { built++; throw RuntimeException("No BLE device saved") }, maxRetries = 3,
            reconnectDelayMs = 2_000L, ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {},
        )
        val seen = record(conn)
        conn.run().collect {}                                           // used to throw out of the flow (app crash)
        assertEquals(3, built)
        assertEquals(5_000L + 15_000L, testScheduler.currentTime)       // same BACKOFF_MS as a failing open()
        assertTrue(seen.any { it is AdapterState.Error })
        assertEquals(AdapterState.Idle, conn.state.value)
    }

    @Test fun `a factory that fails once, then builds, connects on the ordinary retry`() = runTest {
        val h = Harness(listOf(Attempt.ConnectsAndHolds))
        var calls = 0
        val conn = SlcanConnection(
            transportFactory = { if (calls++ == 0) throw IllegalStateException("not ready") else h.next() },
            maxRetries = 3, reconnectDelayMs = 2_000L, ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {},
        )
        val job = launch { conn.run().collect {} }
        advanceTimeBy(6_000L)                                           // first backoff is 5 s
        assertEquals(AdapterState.Connected, conn.state.value)
        assertEquals(2, calls)
        job.cancel(); advanceUntilIdle()
        assertTrue(h.transports.single().closed)
    }

    @Test fun `a factory cancelled while building propagates the cancellation — not counted, not retried`() = runTest {
        var built = 0
        val conn = SlcanConnection(
            transportFactory = { built++; throw kotlinx.coroutines.CancellationException("stopped") }, maxRetries = 3,
            reconnectDelayMs = 2_000L, ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {},
        )
        val seen = record(conn)
        val job = launch { conn.run().collect {} }
        advanceUntilIdle()
        assertTrue(job.isCancelled)
        assertEquals(1, built)
        assertFalse(seen.any { it is AdapterState.Error })
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

    // ── #316: "connection lost" (seam 4). Reported once per dropped connection, after the normal attempts fail, carrying
    // the moment the app found the established connection dropped (the read ended), never the time of the attempts ─────

    @Test fun `auto-reconnect on - a drop is reported after the three attempts fail, with the drop's time`() = runTest {
        val l = losses()
        val h = Harness(listOf(Attempt.ConnectsThenDropsAfter(7_000L), Attempt.Fails, Attempt.Fails, Attempt.Fails))
        val conn = connection(h, maxRetries = 3, onLinkLost = into(l))
        conn.run().collect {}
        // drop at 7 s; reconnect backoff 2 s; attempts at 9 s, 14 s (+5 s), 29 s (+15 s): the third fails at 29 s
        assertEquals(listOf(29_000L to 7_000L), l)
        assertEquals(4, h.transports.size)
        assertEquals("handed over once", null, conn.takeUnreportedDrop())
    }

    @Test fun `auto-reconnect off - a drop is reported after its one attempt fails, with the drop's time`() = runTest {
        val l = losses()
        val h = Harness(listOf(Attempt.ConnectsThenDropsAfter(7_000L), Attempt.Fails))
        val conn = connection(h, maxRetries = 1, onLinkLost = into(l))
        conn.run().collect {}
        assertEquals(listOf(9_000L to 7_000L), l)
        assertEquals(2, h.transports.size)
    }

    @Test fun `nothing is reported before the attempts have failed`() = runTest {
        val l = losses()
        val h = Harness(listOf(Attempt.ConnectsThenDropsAfter(7_000L), Attempt.Fails, Attempt.Fails, Attempt.Fails))
        val conn = connection(h, maxRetries = 3, onLinkLost = into(l))
        val job = launch { conn.run().collect {} }
        advanceTimeBy(28_999L)
        assertEquals(emptyList<Pair<Long, Long>>(), l)
        advanceUntilIdle()
        assertEquals(1, l.size)
        assertTrue(job.isCompleted)
    }

    @Test fun `a connection job that never connects reports no loss - with each auto-reconnect setting`() = runTest {
        for (retries in listOf(3, 1)) {
            val l = losses()
            val conn = connection(Harness(List(retries) { Attempt.Fails }), maxRetries = retries, onLinkLost = into(l))
            conn.run().collect {}
            assertEquals("maxRetries $retries", emptyList<Pair<Long, Long>>(), l)
            assertEquals(null, conn.takeUnreportedDrop())
        }
    }

    @Test fun `a drop followed by a reconnect reports nothing - the held drop is cleared`() = runTest {
        val l = losses()
        val h = Harness(listOf(Attempt.ConnectsThenDropsAfter(7_000L), Attempt.Fails, Attempt.ConnectsAndHolds))
        val conn = connection(h, maxRetries = 3, onLinkLost = into(l))
        val job = launch { conn.run().collect {} }
        advanceTimeBy(10 * 60_000L)
        assertEquals(AdapterState.Connected, conn.state.value)
        assertEquals(emptyList<Pair<Long, Long>>(), l)
        assertEquals(null, conn.takeUnreportedDrop())
        job.cancel(); advanceUntilIdle()
        assertEquals(emptyList<Pair<Long, Long>>(), l)
    }

    @Test fun `a closure during the attempts takes the held drop once - the connection then reports nothing`() = runTest {
        val l = losses()
        val h = Harness(listOf(Attempt.ConnectsThenDropsAfter(7_000L), Attempt.Fails, Attempt.Fails, Attempt.Fails))
        val conn = connection(h, maxRetries = 3, onLinkLost = into(l))
        val job = launch { conn.run().collect {} }
        advanceTimeBy(10_000L)                                         // dropped at 7 s, the first attempt failed at 9 s
        assertEquals(AdapterState.Reconnecting, conn.state.value)
        assertEquals(7_000L, conn.takeUnreportedDrop())
        assertEquals("once", null, conn.takeUnreportedDrop())
        job.cancel(); advanceUntilIdle()                               // the service closes the connection
        assertEquals(emptyList<Pair<Long, Long>>(), l)
    }

    @Test fun `while recording the retries go on - one report per dropped connection, never one per extra attempt`() = runTest {
        val l = losses()
        val h = Harness(listOf(Attempt.ConnectsThenDropsAfter(7_000L), Attempt.Fails, Attempt.Fails, Attempt.Fails,
            Attempt.Fails, Attempt.Fails, Attempt.ConnectsThenDropsAfter(1_000L)))      // then Fails, forever
        val conn = connection(h, maxRetries = 3, keepRetrying = { true }, onLinkLost = into(l))
        val job = launch { conn.run().collect {} }
        advanceTimeBy(10 * 60_000L)
        assertTrue("many attempts: ${h.transports.size}", h.transports.size > 12)
        assertEquals(listOf(7_000L, 120_000L), l.map { it.second })   // the second connection: 119 s + 1 s
        assertEquals(29_000L, l.first().first)
        job.cancel(); advanceUntilIdle()
    }
}
