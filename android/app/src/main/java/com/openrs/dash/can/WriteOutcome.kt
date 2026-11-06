package com.openrs.dash.can

import com.openrs.dash.diagnostics.QUERY_NOT_CONNECTED
import java.io.IOException

/**
 * What a completed write established about delivery (#248). Neither value confirms that a module received or acted on
 * the request — replies and CAN observations do that.
 */
enum class WriteOutcome {
    /** The transport reported success. */
    SENT,
    /**
     * The adapter answered the write with GATT 13 (invalid attribute length). The WiCAN Pro declares its write
     * characteristic with a 20-byte maximum, and its stack (ESP-IDF 5.2.3) delivers a longer write to the firmware
     * before answering 13 — so the line may well have reached CAN. Treated like a sent request for ownership (kept until
     * its own reply) and never retransmitted; never treated as confirmation.
     */
    POSSIBLY_DELIVERED,
}

/**
 * Why a write threw, as a fixed message prefix rather than an exception class — R8 renames classes and
 * `LogText.sanitizeReason` prints the simple name (the same reason as [QUERY_NOT_CONNECTED]).
 *  - [NOT_SENT]: nothing left the phone (no link, link invalidated, the stack refused the write) — a request can be
 *    withdrawn;
 *  - [UNCERTAIN]: the write was handed to the stack and then failed (an error status, no acknowledgement, the link closing
 *    mid-write) — it may have reached CAN, so a request is kept (it lapses, #216) and never retransmitted.
 * An exception with neither prefix keeps its transport's existing handling (TCP / WebSocket: withdrawn, as before).
 */
object WriteFailure {
    const val NOT_SENT = "Not sent"
    const val UNCERTAIN = "Delivery unknown"

    fun notSent(why: String) = IOException("$NOT_SENT: $why")
    fun uncertain(why: String, cause: Throwable? = null) = IOException("$UNCERTAIN: $why", cause)

    fun isNotSent(e: Throwable): Boolean = e.message?.startsWith(NOT_SENT) == true || e.message == QUERY_NOT_CONNECTED
    fun isUncertain(e: Throwable): Boolean = e.message?.startsWith(UNCERTAIN) == true
}
