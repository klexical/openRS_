package com.openrs.dash.can

/**
 * The longest adapter line any transport passes on (A4 #253). Above every legal line: an SLCAN data frame is at most 30
 * characters (`T` + 8 ID + DLC + 16 data + 4 timestamp) and the longest documented adapter line is the ~84-character
 * `AT+FRS?` state reply. A longer line is refused whole and counted, never delivered cut short: `OPENRS:` / `+FRS:` lines
 * are matched by prefix, so a truncated one would still read as a reply.
 */
internal const val MAX_SLCAN_LINE_CHARS = 256

/**
 * Abstraction over the physical transport used to exchange SLCAN frames
 * with a WiCAN / MeatPi adapter.
 *
 * Implementations: [TcpSlcanTransport] (raw TCP), [WebSocketSlcanTransport]
 * (WebSocket over TCP), and — in future — BLE GATT.
 *
 * All SLCAN lines are `\r`-delimited (0x0D). The transport handles any
 * framing overhead (WebSocket masking, BLE packet boundaries, etc.)
 * transparently.
 */
interface SlcanTransport {

    /** Open the underlying connection. Throws on failure. */
    suspend fun open()

    /**
     * Read one `\r`-delimited SLCAN line.
     * Returns `null` on EOF / disconnect.
     */
    suspend fun readLine(): String?

    /**
     * Write a raw SLCAN frame string.
     * The transport handles any framing (WebSocket wrapping, etc.).
     * Must be thread-safe — callers may write from multiple coroutines.
     */
    suspend fun writeLine(frame: String)

    /**
     * [writeLine] with what it established about delivery (#248). A transport that can only succeed or throw keeps this
     * default; a failure is still thrown (see [WriteFailure] for the not-sent / uncertain prefixes).
     */
    suspend fun write(frame: String): WriteOutcome { writeLine(frame); return WriteOutcome.SENT }

    /** Close the connection and release resources. */
    fun close()

    /** Human-readable label for debug output (e.g. "TCP 192.168.0.10:35000"). */
    val label: String

    /** Default firmware label when the openRS_ probe times out. */
    val stockFirmwareLabel: String

    /** Transport kind for diagnostic events — never an address or device name (#220). */
    val kind: String get() = "adapter"
}
