package com.openrs.dash.can

import android.util.Log

/**
 * Stateless SLCAN frame parser shared by all adapter implementations.
 *
 * SLCAN frame format (as emitted by MeatPi / WiCAN firmware):
 *   t{ID3}{DLC}{DATA}   — standard 11-bit frame
 *   T{ID8}{DLC}{DATA}   — extended 29-bit frame (rare on HS-CAN)
 *
 * Index-based hex parse — 500 kbps HS-CAN can emit hundreds of frames/sec,
 * so this path avoids substring / toIntOrNull allocations entirely.
 *
 * Strict length (#251): the data field is exactly DLC × 2 hex characters. A line with characters after it (two lines
 * spliced together, a coalesced fragment) is refused instead of decoding its first DLC bytes. DLC 0 is a legal empty
 * frame (decoders that need bytes ignore it). SLCAN's optional 4-hex timestamp suffix appears only after a `Z1` command:
 * the app never sends one, but another client may have left it on (the adapter's flag lasts its uptime), so a
 * connection whose frames all carry it is parsed with [parse]`(timestamped = true)` — exactly DLC × 2 + 4, suffix
 * dropped. Which shape a connection uses is decided once per connection by SlcanConnection ([shape]); the parser itself
 * stays stateless, and in either mode every other length is refused (a stray +4 line in a plain stream included).
 *
 * IDs (#253): an ID that can't exist on CAN is refused (above 0x7FF on `t`, above 0x1FFFFFFF on `T`). So is a `T` frame numbered
 * 0x7FF or below: it is a different identifier from the `t` frame with that number, and [parse] returns only the number, so it
 * would reach the 11-bit decoders and module replies. Case doesn't matter: hex reads by value.
 */
object SlcanParser {

    /** A data frame's shape: exactly DLC × 2 data characters, or that plus SLCAN's 4-hex timestamp suffix. */
    enum class Shape { PLAIN, TIMESTAMPED }

    /** [timestamped]: the connection's frames carry the 4-hex timestamp suffix (detected per connection, see [shape]). */
    fun parse(msg: String, timestamped: Boolean = false): Pair<Int, ByteArray>? {
        if (msg.isEmpty()) return null
        val suffix = if (timestamped) TIMESTAMP_CHARS else 0
        return when (msg[0]) {
            't' -> parseFrame(msg, idLen = 3, suffix)
            'T' -> parseFrame(msg, idLen = 8, suffix)
            else -> null
        }
    }

    /** The shape of a well-formed data-frame line, or null for anything else (no decode, no allocation of a frame). */
    fun shape(msg: String): Shape? = when {
        parse(msg, timestamped = false) != null -> Shape.PLAIN
        parse(msg, timestamped = true) != null -> Shape.TIMESTAMPED
        else -> null
    }

    private const val TIMESTAMP_CHARS = 4
    private const val TIMESTAMP_LIMIT = 60_000
    private const val MAX_STANDARD_ID = 0x7FF
    private const val MAX_EXTENDED_ID = 0x1FFFFFFF

    private fun parseFrame(msg: String, idLen: Int, suffix: Int): Pair<Int, ByteArray>? {
        val dataStart = 1 + idLen + 1
        if (msg.length < dataStart) return null
        val id = parseHex(msg, 1, idLen); if (id < 0) return null
        if (idLen == 3 && id > MAX_STANDARD_ID) return null
        if (idLen == 8 && (id <= MAX_STANDARD_ID || id > MAX_EXTENDED_ID)) return null
        val dlc = hexDigit(msg[idLen + 1]); if (dlc < 0 || dlc > 8) return null
        if (msg.length != dataStart + dlc * 2 + suffix) return null
        // The adapter's timestamp is milliseconds modulo 60 000 (WiCAN v4.48p slcan_get_time): 0000..EA5F
        if (suffix > 0) { val t = parseHex(msg, dataStart + dlc * 2, suffix); if (t < 0 || t >= TIMESTAMP_LIMIT) return null }
        return parseDataBytes(msg, dataStart, dlc)?.let { Pair(id, it) }
    }

    private fun parseDataBytes(msg: String, start: Int, dlc: Int): ByteArray? {
        val out = ByteArray(dlc)
        for (i in 0 until dlc) {
            val hi = hexDigit(msg[start + i * 2])
            val lo = hexDigit(msg[start + i * 2 + 1])
            if (hi < 0 || lo < 0) {
                Log.w("SLCAN", "parseDataBytes invalid hex at byte $i")      // never the line: logcat ships in release
                return null
            }
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun parseHex(msg: String, start: Int, len: Int): Int {
        var acc = 0
        for (i in 0 until len) {
            val d = hexDigit(msg[start + i])
            if (d < 0) return -1
            acc = (acc shl 4) or d
        }
        return acc
    }

    private fun hexDigit(c: Char): Int = when (c) {
        in '0'..'9' -> c.code - '0'.code
        in 'a'..'f' -> c.code - 'a'.code + 10
        in 'A'..'F' -> c.code - 'A'.code + 10
        else -> -1
    }
}
