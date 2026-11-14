package com.openrs.dash.can

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import java.net.ConnectException
import java.net.SocketTimeoutException

/** What one probe of the adapter over one Wi-Fi network found (#324). */
sealed class ProbeOutcome {
    /** The adapter gave its own protocol reply: [transport] is open and becomes the connection (no second connection). */
    data class Answered(val transport: SlcanTransport, val answer: String, val ms: Long) : ProbeOutcome()
    /** No protocol reply: [reason] is the refused-start reason (never the host, #263). The transport is closed. */
    data class NoAnswer(val reason: String, val ms: Long) : ProbeOutcome()
}

/**
 * Whether a Wi-Fi network is an adapter network: one on which the adapter gave its own protocol reply (#324, the owner's
 * rule A). A TCP accept is not enough: a captive portal, a transparent proxy or a home router at the same address accepts
 * the connection too.
 *  - MeatPi USB (WebSocket): the transport's open passes only on the `101` upgrade reply.
 *  - MeatPi Pro (TCP): the SLCAN init is written and the first line read. Only a CR acknowledgement (an empty line), a BEL
 *    (the SLCAN error reply, still the adapter answering), an `OPENRS:` reply or an SLCAN frame counts; any other line (a
 *    banner, a web page) does not. Whether the Pro acknowledges `C\r` with a CR is #324's (the owner's parked check); the
 *    probe's event names the kind of answer, so an export shows it. The connection then sends its own init: the Pro sees it
 *    twice on a hand-over.
 * The whole probe is bounded by [limitMs]: at the limit it returns, and the transport is closed, which ends a blocked
 * connect or read.
 */
object AdapterProbe {
    const val LIMIT_MS = 2_000L

    suspend fun probe(transport: SlcanTransport, slcanOverTcp: Boolean, clock: () -> Long, limitMs: Long = LIMIT_MS): ProbeOutcome {
        val start = clock()
        // Unstructured on purpose: a blocking connect or name lookup does not answer cancellation, so the probe returns at
        // [limitMs] and leaves that call to end on the closed transport. Its result is then dropped.
        val body = CoroutineScope(Dispatchers.IO).async {
            try {
                transport.open()
                if (slcanOverTcp) {
                    transport.writeLine(ObdConstants.SLCAN_INIT)
                    val line = transport.readLine() ?: throw NoProtocolAnswer("closed before an answer")
                    answerKind(line) ?: throw NoProtocolAnswer("a first line that is not the adapter's")
                } else "101"
            } catch (e: Throwable) { e }   // also an Error from a socket factory: a refused probe, never a silent death
        }
        val result = try {
            withTimeoutOrNull(limitMs) { body.await() }
        } catch (e: CancellationException) {
            body.cancel(); transport.close(); throw e
        }
        return when (result) {
            is String -> ProbeOutcome.Answered(transport, result, clock() - start)
            is Throwable -> { transport.close(); ProbeOutcome.NoAnswer(reasonFor(result, timedOut = false), clock() - start) }
            else -> { body.cancel(); transport.close(); ProbeOutcome.NoAnswer(reasonFor(null, timedOut = true), clock() - start) }
        }
    }

    /** The kind of the Pro's first line, for the probe event (never the line itself); null when it is not the adapter's. */
    fun answerKind(line: String): String? = when {
        line.isEmpty() -> "CR"
        '\u0007' in line -> "BEL"
        line.startsWith("OPENRS:") -> "OPENRS"
        SLCAN_FRAME.matches(line) -> "frame"
        else -> null
    }

    /** An SLCAN data or remote frame: t / r (11-bit) or T / R (29-bit), the ID, a DLC, and hex (a timestamp suffix allowed). */
    private val SLCAN_FRAME = Regex("[tr][0-9A-Fa-f]{3}[0-8][0-9A-Fa-f]{0,20}|[TR][0-9A-Fa-f]{8}[0-8][0-9A-Fa-f]{0,20}")

    /** The probe's own "no protocol answer": the line closed before one, or the first line was not the adapter's. */
    private class NoProtocolAnswer(what: String) : java.io.IOException(what)

    /**
     * The reason for a probe with no protocol reply. "Did not answer" only for a timeout and the no-protocol-answer errors
     * (the probe's own, the WebSocket's "Upgrade failed", a stream closed). A blocked connection is told apart from a silent
     * one (QA's W3: an always-on VPN in lockdown can block a socket bound to the Wi-Fi network; the error texts it produces
     * are not checked). Any other error names its class only (a host name the network cannot resolve:
     * UnknownHostException). An error's message is read to classify it, never printed.
     */
    fun reasonFor(e: Throwable?, timedOut: Boolean): String {
        val msg = e?.message.orEmpty()
        return when {
            timedOut || e is SocketTimeoutException || e is NoProtocolAnswer -> DID_NOT_ANSWER
            e is java.io.IOException && (msg.startsWith("Upgrade failed") || msg == "Stream closed") -> DID_NOT_ANSWER
            BLOCKED.any { it in msg } -> "the connection was blocked on this Wi-Fi network (a VPN or a network policy)"
            e is ConnectException || "ECONNREFUSED" in msg -> "the connection was refused on this Wi-Fi network"
            else -> "could not connect on this Wi-Fi network: ${e?.javaClass?.simpleName ?: "unknown"}"
        }
    }

    private const val DID_NOT_ANSWER = "the adapter did not answer on this Wi-Fi network"

    private val BLOCKED = listOf("EACCES", "EPERM", "ENETUNREACH", "Permission denied", "Network is unreachable")
}
