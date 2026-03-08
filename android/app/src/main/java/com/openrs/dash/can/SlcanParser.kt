package com.openrs.dash.can

/**
 * Stateless SLCAN frame parser shared by all adapter implementations.
 *
 * SLCAN frame format (as emitted by MeatPi / WiCAN firmware):
 *   t{ID3}{DLC}{DATA}   — standard 11-bit frame
 *   T{ID8}{DLC}{DATA}   — extended 29-bit frame (rare on HS-CAN)
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
        val id  = msg.substring(1, 4).toIntOrNull(16) ?: return null
        val dlc = msg[4].digitToIntOrNull(10) ?: return null
        if (dlc < 0 || dlc > 8 || msg.length < 5 + dlc * 2) return null
        return Pair(id, parseDataBytes(msg, 5, dlc))
    }

    private fun parseExtFrame(msg: String): Pair<Int, ByteArray>? {
        if (msg.length < 10) return null
        val id  = msg.substring(1, 9).toLongOrNull(16)?.toInt() ?: return null
        val dlc = msg[9].digitToIntOrNull(10) ?: return null
        if (dlc < 0 || dlc > 8 || msg.length < 10 + dlc * 2) return null
        return Pair(id, parseDataBytes(msg, 10, dlc))
    }

    private fun parseDataBytes(msg: String, start: Int, dlc: Int): ByteArray =
        try {
            ByteArray(dlc) { i -> msg.substring(start + i * 2, start + i * 2 + 2).toInt(16).toByte() }
        } catch (_: Exception) { ByteArray(0) }
}
