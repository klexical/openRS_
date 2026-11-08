package com.openrs.dash.can

/**
 * Minimal ISO-TP reassembly buffer for multi-frame OBD responses.
 *
 * Single-frame responses (PCI high nibble = 0) are returned immediately.
 * First Frames (high nibble = 1) are buffered; the caller must send a
 * Flow Control frame. Consecutive Frames (high nibble = 2) are appended
 * until the full payload is assembled.
 *
 * A reply is delivered whole or not at all (#253): a frame that can't be what its PCI says (a truncated SF / FF / CF, an FF
 * claiming fewer than [MIN_FF_LENGTH] bytes — it would fit an SF) ends the reply and is never read past its own bytes, so a
 * later frame's padding can't make up the missing ones. A partial reply has no timer: it stays open until the next FF or SF.
 *
 * Thread-safety: instances are used within a single coroutine, no locking needed.
 */
/** [onRefused] is told about every frame refused or reply abandoned (counted in the export, #253). */
class IsoTpBuffer(private val onRefused: () -> Unit = {}) {
    private var payload: ByteArray? = null
    private var expected = 0
    private var received = 0
    private var seqNext = 1

    /**
     * Feed a raw 8-byte CAN frame.
     *
     * @return Triple of (reassembledPayload?, isFirstFrame, isSingleFrame).
     *   - SF: returns (strippedPayload, false, true); (null, false, true) when its PCI claims more than the frame holds
     *   - FF: returns (null, true, false) -- caller must send FC; (null, false, false) when refused -- no FC
     *   - CF: returns (reassembledPayload, false, false) when complete, (null, false, false) otherwise
     */
    fun feed(data: ByteArray): Triple<ByteArray?, Boolean, Boolean> {
        if (data.size < 2) return Triple(null, false, false)
        val pciType = (data[0].toInt() and 0xF0) ushr 4
        return when (pciType) {
            0 -> {
                payload = null
                val len = data[0].toInt() and 0x0F
                if (len > data.size - 1) { onRefused(); return Triple(null, false, true) }
                Triple(data.copyOfRange(1, 1 + len), false, true)
            }
            1 -> {
                payload = null                         // valid or not, a new reply started: the old one is over
                val length = ((data[0].toInt() and 0x0F) shl 8) or (data[1].toInt() and 0xFF)
                if (data.size < CAN_FRAME_BYTES || length < MIN_FF_LENGTH) { onRefused(); return Triple(null, false, false) }
                expected = length
                payload = ByteArray(expected)
                System.arraycopy(data, 2, payload!!, 0, FF_DATA_BYTES)
                received = FF_DATA_BYTES
                seqNext = 1
                Triple(null, true, false)
            }
            2 -> {
                val buf = payload ?: return Triple(null, false, false)
                val seq = data[0].toInt() and 0x0F
                if (seq != seqNext) {  // out-of-order or duplicate CF (#128)
                    payload = null; received = 0; seqNext = 1; onRefused()
                    return Triple(null, false, false)
                }
                seqNext = (seqNext + 1) and 0x0F
                val n = minOf(CF_DATA_BYTES, expected - received)
                if (data.size - 1 < n) {               // shorter than the remainder: the next frame would land at the wrong offset
                    payload = null; received = 0; seqNext = 1; onRefused()
                    return Triple(null, false, false)
                }
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

    internal companion object {
        /** Classic CAN: an FF fills the frame. */
        const val CAN_FRAME_BYTES = 8
        const val FF_DATA_BYTES = 6
        const val CF_DATA_BYTES = 7
        /**
         * Fewer bytes fit a Single Frame, so such an FF is refused with no flow control. Unverified: this cites ISO 15765-2 from
         * memory (the standard isn't in the repository), and no real module has been seen sending one.
         */
        const val MIN_FF_LENGTH = 8
    }
}
