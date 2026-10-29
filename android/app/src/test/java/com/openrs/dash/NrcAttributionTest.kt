package com.openrs.dash

import com.openrs.dash.can.SlcanConnection
import com.openrs.dash.can.SlcanTransport
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #216 — PCM negative responses through the production poller and frame loop. Replays the 2026-10-28 PM pattern:
 * the ECU answers in request order, 0x03ED / 0x03EE (unsupported on that car) answer `7F 22 31` ~160 ms after their
 * request — after the next request went out — and 0x03EF answers positively. A delayed NRC must not suppress the DID
 * sent after it; genuinely rejected DIDs are still suppressed; a positive reply recovers a suppressed DID.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NrcAttributionTest {

    /** Mode 22 reply script per DID: (payload or NRC, delay). Unlisted DIDs answer `62 DID 00 00` after 40 ms. */
    private sealed interface R { val afterMs: Long }
    private data class Pos(val payload: String, override val afterMs: Long = 40L) : R
    private data class Nrc(val code: Int, override val afterMs: Long) : R
    private data object Silent : R { override val afterMs = 0L }
    /** Several replies to one request (e.g. `7F 22 78` then the final answer), each at its own delay. */
    private data class Seq(val replies: List<R>) : R { override val afterMs = 0L }
    /** The write itself fails — nothing reaches the bus. */
    private data object WriteFails : R { override val afterMs = 0L }

    private class Ecu(private val scope: CoroutineScope, private val script: (Int, Int) -> R) : SlcanTransport {
        private val lines = Channel<String>(Channel.UNLIMITED).apply { trySend("t09080000C1FF617D0000") }
        val sent = mutableMapOf<Int, Int>()
        override suspend fun open() = Unit
        override suspend fun readLine(): String? = lines.receive()
        override suspend fun writeLine(frame: String) {
            // PCM Mode 22: t7E0 8 03 22 DDDD …
            if (!frame.startsWith("t7E08") || frame.substring(5, 9) != "0322") return
            val did = frame.substring(9, 13).toInt(16)
            val n = (sent[did] ?: 0) + 1; sent[did] = n
            val r = script(did, n)
            if (r == WriteFails) throw java.io.IOException("scripted write failure")
            for (one in if (r is Seq) r.replies else listOf(r)) {
                val line = when (one) {
                    is Pos -> "t7E88" + "%02X62%04X%s".format(3 + one.payload.length / 2, did, one.payload).padEnd(16, '0')
                    is Nrc -> "t7E88037F22%02X0000000000".format(one.code).take(21)
                    else -> continue
                }
                scope.launch { delay(one.afterMs); lines.send(line) }
            }
        }
        override fun close() { lines.close() }
        override val label = "scripted ECU"
        override val stockFirmwareLabel = "scripted"
    }

    @Before fun freshSession() = DiagnosticLogger.sessionStart("test", 0, prefs = null)

    private fun TestScope.run(script: (Int, Int) -> R, seconds: Long): Ecu {
        val ecu = Ecu(backgroundScope, script)
        val conn = SlcanConnection(
            transportFactory = { ecu }, maxRetries = 1, rpmProvider = { 1_500.0 },
            ioDispatcher = StandardTestDispatcher(testScheduler), debugSink = {}, clock = { testScheduler.currentTime },
        )
        backgroundScope.launch { conn.connectHybrid({}, { VehicleState() }).collect {} }
        advanceTimeBy(seconds * 1_000L)
        return ecu
    }

    private val pmPattern: (Int, Int) -> R = { did, _ ->
        when (did) {
            0x03ED, 0x03EE -> Nrc(0x31, afterMs = 160L)          // rejected, reply lands after the next request
            0x03EF -> Pos("0000", afterMs = 50L)
            else -> Pos("0000")
        }
    }

    @Test fun `a delayed NRC is not charged to the next DID — 03EF keeps being polled`() = runTest {
        val ecu = run(pmPattern, seconds = 25)                         // 20 s poll start + the first fast cycles
        val suppressed = DiagnosticLogger.nrcSuppressed.keys
        assertFalse("03EF answered positively; it must not be suppressed (suppressed: ${suppressed.map { "%04X".format(it) }})",
            0x03EF in suppressed)
        assertTrue("03EF kept being polled: ${ecu.sent[0x03EF]}", (ecu.sent[0x03EF] ?: 0) >= 3)
        assertFalse(0x093C in suppressed); assertFalse(0x091A in suppressed)
    }

    @Test fun `DIDs that really answer NRC 0x31 are still suppressed`() = runTest {
        run(pmPattern, seconds = 60)
        val suppressed = DiagnosticLogger.nrcSuppressed.keys
        assertTrue(0x03ED in suppressed)
        assertTrue(0x03EE in suppressed)
    }

    @Test fun `a suppressed DID recovers when it answers positively on a re-probe`() = runTest {
        // 03EC rejects its first 3 requests (e.g. ECU still waking up), then answers normally
        val ecu = run({ did, n -> if (did == 0x03EC && n <= 3) Nrc(0x31, afterMs = 40L) else Pos("0000") }, seconds = 400)
        assertFalse("03EC recovered (sent ${ecu.sent[0x03EC]}, suppressed ${DiagnosticLogger.nrcSuppressed})", 0x03EC in DiagnosticLogger.nrcSuppressed.keys)
        assertTrue((ecu.sent[0x03EC] ?: 0) > 5)
    }

    @Test fun `response pending (0x78) never counts as a rejection`() = runTest {
        run({ did, _ -> if (did == 0x0466) Nrc(0x78, afterMs = 20L) else Pos("0000") }, seconds = 60)
        assertEquals(emptySet<Int>(), DiagnosticLogger.nrcSuppressed.keys)
    }

    // ── Request ownership (correction pass): an NRC is counted only when the request it answers is unambiguous ──

    @Test fun `a late NRC for an earlier request is never charged to a later one after a positive reply`() = runTest {
        // Fast group order: 033E, 0466, 0462, 03EC… 033E's NRC arrives 400 ms late; 0466 answers at once; 0462 gets
        // no reply. Before: 0466's positive dropped 033E from the ledger, so the late NRC was blamed on 0462 (sent
        // meanwhile, still waiting) and 0462 was suppressed for 033E's rejection.
        run({ did, _ -> when (did) { 0x033E -> Nrc(0x31, afterMs = 400L); 0x0462 -> Silent; else -> Pos("0000") } }, seconds = 40)
        val suppressed = DiagnosticLogger.nrcSuppressed.keys
        assertFalse("0462 never sent an NRC (suppressed: ${suppressed.map { "%04X".format(it) }})", 0x0462 in suppressed)
        assertFalse(0x0466 in suppressed)
    }

    @Test fun `a request whose write failed is never blamed for the next DID's NRC`() = runTest {
        // 03EC can't be written (transport error); 03ED, sent next, really answers NRC 0x31
        val ecu = run({ did, _ ->
            when (did) { 0x03EC -> WriteFails; 0x03ED -> Nrc(0x31, afterMs = 40L); else -> Pos("0000") }
        }, seconds = 40)
        val suppressed = DiagnosticLogger.nrcSuppressed.keys
        assertFalse("03EC never reached the bus — it can't own an NRC", 0x03EC in suppressed)
        assertTrue("03ED's own NRCs are attributable (sent ${ecu.sent[0x03ED]})", 0x03ED in suppressed)
    }

    @Test fun `a DID that never answers is not suppressed by its neighbour's NRC`() = runTest {
        // 03EC is silent (no reply at all); 03ED answers NRC 0x31 — which request it answers is ambiguous while
        // 03EC may still reply, so nothing is counted against 03EC
        run({ did, _ -> when (did) { 0x03EC -> Silent; 0x03ED -> Nrc(0x31, afterMs = 40L); else -> Pos("0000") } }, seconds = 40)
        assertFalse(0x03EC in DiagnosticLogger.nrcSuppressed.keys)
    }

    @Test fun `response pending then a positive final answer is never a rejection`() = runTest {
        val ecu = run({ did, _ ->
            if (did == 0x0466) Seq(listOf(Nrc(0x78, afterMs = 20L), Pos("4F1A", afterMs = 250L))) else Pos("0000")
        }, seconds = 40)
        assertEquals(emptySet<Int>(), DiagnosticLogger.nrcSuppressed.keys)
        assertTrue((ecu.sent[0x0466] ?: 0) >= 10)
    }

    @Test fun `response pending then a final NRC inside the reply window is attributed`() = runTest {
        run({ did, _ ->
            if (did == 0x03EE) Seq(listOf(Nrc(0x78, afterMs = 20L), Nrc(0x31, afterMs = 200L))) else Pos("0000")
        }, seconds = 40)
        assertEquals(setOf(0x03EE), DiagnosticLogger.nrcSuppressed.keys)
    }
}
