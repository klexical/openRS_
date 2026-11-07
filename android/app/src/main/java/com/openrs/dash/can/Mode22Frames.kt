package com.openrs.dash.can

/** Mode 22 (ReadDataByIdentifier) frame shapes, for deciding which request a reply belongs to (#216, #250). */
internal object Mode22 {
    /** A single-frame `22 DH DL` request written as SLCAN `tIII8 03 22 DH DL …`: (request ID, DID), else null. */
    fun request(line: String): Pair<Int, Int>? {
        if (line.length < 13 || line[0] != 't' || line.substring(4, 9) != "80322") return null
        val id = line.substring(1, 4).toIntOrNull(16) ?: return null
        val did = line.substring(9, 13).toIntOrNull(16) ?: return null
        return id to did
    }

    /**
     * A single frame that holds what its PCI claims and at least a service, a second byte and a third: `0N` with 3 ≤ N ≤ what
     * follows it. A reply cut short, or claiming too little for its own bytes, belongs to no request (#253, as the parser, #251).
     */
    fun wholeSingleFrame(d: ByteArray): Boolean {
        if (d.isEmpty() || (d[0].toInt() and 0xF0) != 0) return false
        val n = d[0].toInt() and 0x0F
        return n >= 3 && n + 1 <= d.size
    }

    /** The DID a positive reply (`62 DH DL`) echoes — a single frame or the first frame of a multi-frame reply — else null. */
    fun positiveDid(d: ByteArray): Int? {
        if (d.isEmpty()) return null
        return when (d[0].toInt() and 0xF0) {
            0x00 -> if (wholeSingleFrame(d) && (d[1].toInt() and 0xFF) == 0x62) did(d, 2) else null
            0x10 -> if (d.size >= 5 && (d[2].toInt() and 0xFF) == 0x62) did(d, 3) else null
            else -> null
        }
    }

    /** A single-frame `7F 22 xx`: the NRC code, else null. It names no DID — its owner comes from a ledger. */
    fun negativeCode(d: ByteArray): Int? =
        if (wholeSingleFrame(d) && (d[1].toInt() and 0xFF) == 0x7F && (d[2].toInt() and 0xFF) == 0x22)
            d[3].toInt() and 0xFF
        else null

    private fun did(d: ByteArray, i: Int) = ((d[i].toInt() and 0xFF) shl 8) or (d[i + 1].toInt() and 0xFF)
}

/** What a raw (DID prober) query got back (#250); null from [SlcanConnection.sendRawQuery] = no reply in time. */
sealed interface RawReply {
    /** A reply matched to the query by module, service and DID (a positive), or an NRC its ledger attributed to it. */
    class Frame(val data: ByteArray) : RawReply
    /** No reply of its own, but a `7F 22` [code] arrived while another request could equally own it — charged to nobody. */
    data class AmbiguousNrc(val code: Int) : RawReply
}
