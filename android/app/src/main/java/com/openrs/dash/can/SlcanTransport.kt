package com.openrs.dash.can

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

    /** Close the connection and release resources. */
    fun close()

    /** Human-readable label for debug output (e.g. "TCP 192.168.0.10:35000"). */
    val label: String

    /** Default firmware label when the openRS_ probe times out. */
    val stockFirmwareLabel: String
}
