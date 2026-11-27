package com.openrs.dash.can

import java.util.Locale

/**
 * The session log's one line for a frame loop that ended by itself (#374): why the read ended and how long nothing had
 * arrived before it, so an export tells a key-off (the bus goes quiet, then the read times out) from an adapter that
 * closed the connection, a Bluetooth link loss or a read error. Never an address, a device name or an exception's
 * message: a read error is named by its class only. A stop by the user or the service writes LINK_STOP instead.
 */
object LinkEnd {
    const val TYPE = "LINK_END"

    /**
     * [cause] = what the read threw (null when it returned end of stream); [transportNote] = [SlcanTransport.readEnd];
     * [silenceMs] = since the last line the adapter sent (or the connection's start); [kind] = [SlcanTransport.kind].
     */
    fun line(cause: Throwable?, transportNote: String?, silenceMs: Long, kind: String): String =
        String.format(Locale.ROOT, "%s; %.1f s with no line from the adapter before it (%s)",
            reason(cause, transportNote), silenceMs.coerceAtLeast(0L) / 1_000.0, kind)

    fun reason(cause: Throwable?, transportNote: String?): String = when {
        cause is java.net.SocketTimeoutException -> "read timed out with no frame"
        cause != null -> "read error (${cause.javaClass.simpleName})"
        transportNote != null -> transportNote
        else -> "the adapter closed the connection"
    }
}
