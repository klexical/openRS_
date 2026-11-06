package com.openrs.dash.diagnostics

import com.openrs.dash.diagnostics.DiagnosticLogger.ProbeResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

internal sealed interface DidProbeEnd {
    data object Complete : DidProbeEnd
    data object Stopped : DidProbeEnd
    /** [sent] = false when the query never reached the adapter (no connection), so nothing went out for [did]. */
    data class Failed(val did: Int, val reason: String, val sent: Boolean = true) : DidProbeEnd
}

/** The one message for "no adapter to send through" — the scan tells it apart from a write the adapter refused. */
internal const val QUERY_NOT_CONNECTED = "Adapter disconnected"

/**
 * A null ECU reply is a DID timeout; a failed transport write ends the scan without a result for that DID. A stopped or
 * failed scan keeps the results it has, recorded as STOPPED / FAILED with the planned count — never as a whole scan.
 */
internal suspend fun runDidProbeScan(
    module: String,
    requestId: Int,
    responseId: Int,
    dids: List<Int>,
    sendRawQuery: suspend (Int, String, Long) -> ByteArray?,
    stopRequested: () -> Boolean,
    onResult: (ProbeResult) -> Unit,
): DidProbeEnd {
    val results = mutableListOf<ProbeResult>()
    fun end(outcome: String, end: DidProbeEnd): DidProbeEnd {
        if (results.isNotEmpty()) {
            DiagnosticLogger.recordProbeSession(module, requestId, responseId, results, outcome, dids.size)
        }
        return end
    }
    for (did in dids) {
        currentCoroutineContext().ensureActive()
        if (stopRequested()) return end(DiagnosticLogger.PROBE_STOPPED, DidProbeEnd.Stopped)
        val frame = "t%03X80322%04X00000000\r".format(requestId, did)
        val response = try {
            sendRawQuery(responseId, frame, 1_000L)
        } catch (e: Exception) {
            // Every transport write failure found is an IOException; anything else is still a failed query, not a
            // crash — the caller is a UI scope with no catch. Cancellation is not a failure (rethrown below).
            if (e is CancellationException) throw e
            currentCoroutineContext().ensureActive()
            val reason = LogText.sanitizeReason(e)
            DiagnosticLogger.event("PROBE", "%s scan stopped at 0x%04X (%d/%d probed): %s"
                .format(module, did, results.size, dids.size, reason))
            return end(DiagnosticLogger.PROBE_FAILED, DidProbeEnd.Failed(did, reason, sent = e.message != QUERY_NOT_CONNECTED))
        }
        currentCoroutineContext().ensureActive()
        if (stopRequested()) return end(DiagnosticLogger.PROBE_STOPPED, DidProbeEnd.Stopped)
        val entry = classifyResponse(did, response)
        results.add(entry)
        onResult(entry)
        if (results.size < dids.size) delay(100L)
    }
    return end(DiagnosticLogger.PROBE_COMPLETE, DidProbeEnd.Complete)
}

private fun classifyResponse(did: Int, data: ByteArray?): ProbeResult {
    if (data == null || data.size < 2) return ProbeResult(did, "TIMEOUT", "")
    val svc = data[1].toInt() and 0xFF
    return when (svc) {
        0x62 -> ProbeResult(
            did, "FOUND",
            data.drop(4).joinToString(" ") { "%02X".format(it) }
        )
        0x7F -> {
            val code = if (data.size > 3) data[3].toInt() and 0xFF else null
            val label = if (code != null) "NRC 0x%02X — %s".format(code, nrcLabel(code)) else "NRC ??"
            ProbeResult(did, "NRC", label)
        }
        else -> ProbeResult(did, "TIMEOUT", data.joinToString(" ") { "%02X".format(it) })
    }
}

private fun nrcLabel(code: Int): String = when (code) {
    0x10 -> "general reject"
    0x11 -> "service not supported"
    0x12 -> "sub-function not supported"
    0x13 -> "incorrect message length"
    0x14 -> "response too long"
    0x22 -> "conditions not correct"
    0x31 -> "request out of range"
    0x33 -> "security access denied"
    0x35 -> "invalid key"
    0x72 -> "general programming failure"
    0x78 -> "request correctly received, response pending"
    else -> "NRC 0x%02X".format(code)
}
