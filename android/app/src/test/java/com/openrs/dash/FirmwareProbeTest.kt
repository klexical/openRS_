package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #220 — openRS_ firmware capability probe through the production connection. The 2026-10-28 exports show
 * "no openRS_ response in 3 s" at +3.0 / +3.3 s and nothing after: a late reply was ignored and the probe never
 * retried. Detection must accept a late reply, retry a bounded number of times, and say when it gave up.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirmwareProbeTest {

    /** Streams a 0x090 frame every 100 ms; answers the [answerProbe]-th OPENRS? after [replyAfterMs] (0 = never). */
    private class Adapter(private val scope: CoroutineScope, private val answerProbe: Int, private val replyAfterMs: Long) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED)
        var probes = 0
        override suspend fun open() {
            scope.launch { while (isActive) { lines.send("t09080000C1FF617D0000"); delay(100L) } }
        }
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun writeLine(frame: String) {
            if (frame == "OPENRS?\r") {
                probes++
                if (answerProbe > 0 && probes == answerProbe) scope.launch { delay(replyAfterMs); lines.send("OPENRS:v1.61") }
            }
        }
        override fun close() { lines.close() }
        override val label = "scripted"
        override val stockFirmwareLabel = "MeatPi Pro"
    }

    private fun TestScope.connect(answerProbe: Int, replyAfterMs: Long, seconds: Long): Pair<Adapter, List<SlcanConnection.FirmwareStatus>> {
        val adapter = Adapter(backgroundScope, answerProbe, replyAfterMs)
        val seen = mutableListOf<SlcanConnection.FirmwareStatus>()
        val conn = SlcanConnection(
            transportFactory = { adapter }, maxRetries = 1, ioDispatcher = StandardTestDispatcher(testScheduler),
            debugSink = {}, clock = { testScheduler.currentTime }, firmwareSink = { seen += it },
        )
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(seconds * 1_000L)
        return adapter to seen
    }

    @Test fun `a reply arriving after the first 3 s window still enables controls`() = runTest {
        val (adapter, seen) = connect(answerProbe = 1, replyAfterMs = 4_000L, seconds = 20)
        assertTrue("detected: $seen", seen.last().openRs)
        assertEquals("openRS_ v1.61", seen.last().label)
        assertTrue("the stock label showed meanwhile", seen.none { it.openRs && it.probing })
        assertTrue(adapter.probes in 2..3)
    }

    @Test fun `an adapter that only answers the second probe is detected`() = runTest {
        val (adapter, seen) = connect(answerProbe = 2, replyAfterMs = 200L, seconds = 20)
        assertTrue(seen.last().openRs)
        assertEquals(2, adapter.probes)
    }

    @Test fun `no reply — bounded probes, then not detected (not probing)`() = runTest {
        val (adapter, seen) = connect(answerProbe = 0, replyAfterMs = 0L, seconds = 60)
        assertEquals(3, adapter.probes)
        assertFalse(seen.last().openRs)
        assertFalse(seen.last().probing)
        assertEquals("MeatPi Pro", seen.last().label)
    }

    @Test fun `an immediate reply needs one probe`() = runTest {
        val (adapter, seen) = connect(answerProbe = 1, replyAfterMs = 50L, seconds = 20)
        assertTrue(seen.last().openRs)
        assertEquals(1, adapter.probes)
    }
}
