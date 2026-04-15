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
 */
object SlcanParser {

    fun parse(msg: String): Pair<Int, ByteArray>? {
        if (msg.isEmpty()) return null
        return when (msg[0]) {
            't' -> parseStdFrame(msg)
            'T' -> parseExtFrame(msg)
            else -> null
        }
    }

    private fun parseStdFrame(msg: String): Pair<Int, ByteArray>? {
        if (msg.length < 5) return null
        val id = parseHex(msg, 1, 3); if (id < 0) return null
        val dlc = hexDigit(msg[4]); if (dlc < 0 || dlc > 8) return null
        if (msg.length < 5 + dlc * 2) return null
        return parseDataBytes(msg, 5, dlc)?.let { Pair(id, it) }
    }

    private fun parseExtFrame(msg: String): Pair<Int, ByteArray>? {
        if (msg.length < 10) return null
        val id = parseHex(msg, 1, 8); if (id < 0) return null
        val dlc = hexDigit(msg[9]); if (dlc < 0 || dlc > 8) return null
        if (msg.length < 10 + dlc * 2) return null
        return parseDataBytes(msg, 10, dlc)?.let { Pair(id, it) }
    }

    private fun parseDataBytes(msg: String, start: Int, dlc: Int): ByteArray? {
        val out = ByteArray(dlc)
        for (i in 0 until dlc) {
            val hi = hexDigit(msg[start + i * 2])
            val lo = hexDigit(msg[start + i * 2 + 1])
            if (hi < 0 || lo < 0) {
                Log.w("SLCAN", "parseDataBytes invalid hex at byte $i: ${msg.take(30)}")
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
