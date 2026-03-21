package com.openrs.dash.can

/**
 * Minimal ISO-TP reassembly buffer for multi-frame OBD responses.
 *
 * Single-frame responses (PCI high nibble = 0) are returned immediately.
 * First Frames (high nibble = 1) are buffered; the caller must send a
 * Flow Control frame. Consecutive Frames (high nibble = 2) are appended
 * until the full payload is assembled.
 *
 * Thread-safety: instances are used within a single coroutine, no locking needed.
 */
class IsoTpBuffer {
    private var payload: ByteArray? = null
    private var expected = 0
    private var received = 0

    /**
     * Feed a raw 8-byte CAN frame.
     *
     * @return Triple of (reassembledPayload?, isFirstFrame, isSingleFrame).
     *   - SF: returns (strippedPayload, false, true)
     *   - FF: returns (null, true, false) -- caller must send FC
     *   - CF: returns (reassembledPayload, false, false) when complete, (null, false, false) otherwise
     */
    fun feed(data: ByteArray): Triple<ByteArray?, Boolean, Boolean> {
        if (data.size < 2) return Triple(null, false, false)
        val pciType = (data[0].toInt() and 0xF0) ushr 4
        return when (pciType) {
            0 -> {
                payload = null
                val len = (data[0].toInt() and 0x0F).coerceAtMost(data.size - 1)
                Triple(data.copyOfRange(1, 1 + len), false, true)
            }
            1 -> {
                expected = ((data[0].toInt() and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
                payload = ByteArray(expected)
                val n = minOf(6, expected, data.size - 2)
                System.arraycopy(data, 2, payload!!, 0, n)
                received = n
                Triple(null, true, false)
            }
            2 -> {
                val buf = payload ?: return Triple(null, false, false)
                val remaining = expected - received
                val n = minOf(7, remaining, data.size - 1)
                System.arraycopy(data, 1, buf, received, n)
                received += n
                if (received >= expected) {
                    payload = null
                    Triple(buf, false, false)
                } else Triple(null, false, false)
            }
            else -> Triple(null, false, false)
        }
    }
}
