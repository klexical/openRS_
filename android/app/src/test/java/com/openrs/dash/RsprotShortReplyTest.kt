package com.openrs.dash

import com.openrs.dash.can.AdapterState
import com.openrs.dash.can.ObdResponseParser
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
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * REQ-rc8-launch-control-reference (PM lead): `parseRsprotResponse` formats B5 for its debug line BEFORE the ShortReply
 * guard, and production always passes that debug callback (SlcanConnection: `{ addDebugLine(it) }`). A reply with ONE
 * data byte — a complete 0xDE00 ("04 62 DE 00 01"), or a 0xDE01 cut short — then throws ShortReply out of the parser.
 * Synthetic frames only; what DE00 / DE01 mean on the car is undocumented (0x731 is the keyless module's address).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RsprotShortReplyTest {
    private fun bytes(hex: String) = ByteArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    private val debugLines = mutableListOf<String>()
    private val productionDebug: (String) -> Unit = { debugLines += it }    // what SlcanConnection passes

    @Test fun `(a) a valid one-byte DE00 reply decodes with the production debug callback`() {
        var result: VehicleState? = null
        ObdResponseParser.parseRsprotResponse(bytes("0462DE0001000000"), VehicleState(), { result = it }, productionDebug)
        assertTrue("decoded: $result", result?.lcArmed == true)
    }

    @Test fun `(b) a DE01 reply cut to one data byte is refused, not thrown, with the production debug callback`() {
        var result: VehicleState? = null
        ObdResponseParser.parseRsprotResponse(bytes("0462DE0113000000"), VehicleState(), { result = it }, productionDebug)
        assertNull("a value needing B5 must not be decoded from B4 alone: $result", result)
    }

    /** Streams 0x090 every 100 ms; after 5 s sends one RSProt 0x739 reply (synthetic). */
    private class Adapter(private val scope: CoroutineScope, private val reply: String) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED)
        override suspend fun open() {
            scope.launch { while (isActive && lines.trySend("t09080000C1FF617D0000").isSuccess) delay(100L) }
            scope.launch { delay(5_000L); lines.trySend(reply) }
        }
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun writeLine(frame: String) {}
        override fun close() { lines.close() }
        override val label = "TCP test"
        override val stockFirmwareLabel = "WiCAN"
        override val kind = "TCP"
    }

    @Test fun `(c) end to end - one valid DE00 reply does not end the connection`() = runTest {
        val states = mutableListOf<AdapterState>()
        val conn = SlcanConnection(
            transportFactory = { Adapter(backgroundScope, "t73980462DE0001000000") }, maxRetries = 1,
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { conn.state.collect { states += it } }
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(4_000L)
        assertTrue("connected before the reply: ${conn.state.value}", conn.state.value is AdapterState.Connected)
        advanceTimeBy(3_000L)
        assertEquals("states after a valid DE00 reply: ${states.map { it.label }}", false,
            states.any { it is AdapterState.Error })
        assertTrue("still connected: ${conn.state.value.label}", conn.state.value is AdapterState.Connected)
    }
}
