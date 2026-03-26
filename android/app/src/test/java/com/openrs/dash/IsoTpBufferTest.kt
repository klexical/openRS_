package com.openrs.dash

import com.openrs.dash.can.IsoTpBuffer
import org.junit.Assert.*
import org.junit.Test

class IsoTpBufferTest {

    // ── Single Frame ──────────────────────────────────────────────────────────

    @Test
    fun `single frame returns payload immediately`() {
        val buf = IsoTpBuffer()
        // SF: PCI high nibble = 0, low nibble = length (5 bytes)
        val data = byteArrayOf(0x05, 0x62, 0x28, 0x13, 0xAA.toByte(), 0xBB.toByte(), 0x00, 0x00)
        val (payload, isFF, isSF) = buf.feed(data)
        assertNotNull(payload)
        assertTrue(isSF)
        assertFalse(isFF)
        assertEquals(5, payload!!.size)
        assertEquals(0x62, payload[0].toInt() and 0xFF)
        assertEquals(0x28, payload[1].toInt() and 0xFF)
        assertEquals(0x13, payload[2].toInt() and 0xFF)
        assertEquals(0xAA, payload[3].toInt() and 0xFF)
        assertEquals(0xBB, payload[4].toInt() and 0xFF)
    }

    @Test
    fun `single frame with 1 byte payload`() {
        val buf = IsoTpBuffer()
        val data = byteArrayOf(0x01, 0x7F.toByte(), 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val (payload, isFF, isSF) = buf.feed(data)
        assertNotNull(payload)
        assertTrue(isSF)
        assertFalse(isFF)
        assertEquals(1, payload!!.size)
        assertEquals(0x7F, payload[0].toInt() and 0xFF)
    }

    // ── First Frame + Consecutive Frame ───────────────────────────────────────

    @Test
    fun `first frame signals need for flow control`() {
        val buf = IsoTpBuffer()
        // FF: PCI high nibble = 1, total length = 10 bytes
        // (0x10 | high nibble of length) = 0x10, low byte of length = 0x0A
        val ff = byteArrayOf(0x10, 0x0A, 0x62, 0x28, 0x0B, 0x01, 0x02, 0x03)
        val (payload, isFF, isSF) = buf.feed(ff)
        assertNull(payload)
        assertTrue(isFF)
        assertFalse(isSF)
    }

    @Test
    fun `first frame plus one CF assembles correct payload`() {
        val buf = IsoTpBuffer()
        // Total payload = 10 bytes
        // FF carries 6 data bytes (bytes 2-7)
        val ff = byteArrayOf(0x10, 0x0A, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        val (p1, isFF, _) = buf.feed(ff)
        assertNull(p1)
        assertTrue(isFF)

        // CF seq=1 carries remaining 4 bytes
        val cf = byteArrayOf(0x21, 0x07, 0x08, 0x09, 0x0A, 0x00, 0x00, 0x00)
        val (payload, isFF2, isSF2) = buf.feed(cf)
        assertNotNull(payload)
        assertFalse(isFF2)
        assertFalse(isSF2)
        assertEquals(10, payload!!.size)
        // Verify assembled payload: bytes 0-5 from FF, bytes 6-9 from CF
        for (i in 0 until 10) {
            assertEquals(i + 1, payload[i].toInt() and 0xFF)
        }
    }

    @Test
    fun `first frame plus multiple CFs assembles 20-byte payload`() {
        val buf = IsoTpBuffer()
        // Total payload = 20 bytes
        // FF: 6 data bytes (indices 0-5)
        val ff = byteArrayOf(0x10, 0x14, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        buf.feed(ff)

        // CF seq=1: 7 data bytes (indices 6-12)
        val cf1 = byteArrayOf(0x21, 0x07, 0x08, 0x09, 0x0A, 0x0B, 0x0C, 0x0D)
        val (p1, _, _) = buf.feed(cf1)
        assertNull(p1) // not complete yet

        // CF seq=2: remaining 7 data bytes (indices 13-19)
        val cf2 = byteArrayOf(0x22, 0x0E, 0x0F, 0x10, 0x11, 0x12, 0x13, 0x14)
        val (payload, _, _) = buf.feed(cf2)
        assertNotNull(payload)
        assertEquals(20, payload!!.size)
        for (i in 0 until 20) {
            assertEquals(i + 1, payload[i].toInt() and 0xFF)
        }
    }

    // ── Out-of-order CF ───────────────────────────────────────────────────────

    @Test
    fun `out-of-order CF resets buffer and returns null`() {
        val buf = IsoTpBuffer()
        // FF: 10-byte payload
        val ff = byteArrayOf(0x10, 0x0A, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        buf.feed(ff)

        // CF with wrong sequence (2 instead of 1)
        val cf = byteArrayOf(0x22, 0x07, 0x08, 0x09, 0x0A, 0x00, 0x00, 0x00)
        val (payload, _, _) = buf.feed(cf)
        assertNull(payload)

        // Subsequent correct CF should also fail (buffer was reset)
        val cf2 = byteArrayOf(0x21, 0x07, 0x08, 0x09, 0x0A, 0x00, 0x00, 0x00)
        val (payload2, _, _) = buf.feed(cf2)
        assertNull(payload2)
    }

    // ── Duplicate FF ──────────────────────────────────────────────────────────

    @Test
    fun `duplicate FF resets buffer for new message`() {
        val buf = IsoTpBuffer()
        // First FF
        val ff1 = byteArrayOf(0x10, 0x0A, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06)
        buf.feed(ff1)

        // Second FF (replaces first)
        val ff2 = byteArrayOf(0x10, 0x0A, 0x0A, 0x0B, 0x0C, 0x0D, 0x0E, 0x0F)
        val (p, isFF, _) = buf.feed(ff2)
        assertNull(p)
        assertTrue(isFF)

        // CF seq=1 completes the second FF
        val cf = byteArrayOf(0x21, 0x10, 0x11, 0x12, 0x13, 0x00, 0x00, 0x00)
        val (payload, _, _) = buf.feed(cf)
        assertNotNull(payload)
        assertEquals(10, payload!!.size)
        // First 6 bytes should be from ff2, not ff1
        assertEquals(0x0A, payload[0].toInt() and 0xFF)
        assertEquals(0x0B, payload[1].toInt() and 0xFF)
    }

    // ── Short input ───────────────────────────────────────────────────────────

    @Test
    fun `1-byte input returns null`() {
        val buf = IsoTpBuffer()
        val data = byteArrayOf(0x05)
        val (payload, isFF, isSF) = buf.feed(data)
        assertNull(payload)
        assertFalse(isFF)
        assertFalse(isSF)
    }

    @Test
    fun `empty input returns null`() {
        val buf = IsoTpBuffer()
        val data = byteArrayOf()
        val (payload, isFF, isSF) = buf.feed(data)
        assertNull(payload)
        assertFalse(isFF)
        assertFalse(isSF)
    }

    // ── CF without prior FF ───────────────────────────────────────────────────

    @Test
    fun `CF without prior FF returns null`() {
        val buf = IsoTpBuffer()
        val cf = byteArrayOf(0x21, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07)
        val (payload, isFF, isSF) = buf.feed(cf)
        assertNull(payload)
        assertFalse(isFF)
        assertFalse(isSF)
    }

    // ── Sequence wrap (0xF → 0x0) ─────────────────────────────────────────────

    @Test
    fun `sequence number wraps from F to 0`() {
        val buf = IsoTpBuffer()
        // Payload that needs 16 CFs + FF = 6 + 16*7 = 118 bytes total
        // Use exactly 118 bytes so the 16th CF completes the message
        val totalLen = 118
        val ff = byteArrayOf(
            (0x10 or (totalLen shr 8)).toByte(),
            (totalLen and 0xFF).toByte(),
            0x01, 0x02, 0x03, 0x04, 0x05, 0x06
        )
        buf.feed(ff)

        // Send CFs with seq 1..F (15 frames), then seq 0 (16th frame)
        for (seq in 1..15) {
            val cf = ByteArray(8)
            cf[0] = (0x20 or seq).toByte()
            for (j in 1..7) cf[j] = ((seq * 7 + j + 5) and 0xFF).toByte()
            val (p, _, _) = buf.feed(cf)
            assertNull(p) // not complete yet
        }

        // 16th CF: seq wraps to 0
        val cf16 = ByteArray(8)
        cf16[0] = 0x20.toByte() // seq = 0
        for (j in 1..7) cf16[j] = 0x42
        val (payload, _, _) = buf.feed(cf16)
        assertNotNull(payload)
        assertEquals(totalLen, payload!!.size)
    }

    // ── Unknown PCI type ──────────────────────────────────────────────────────

    @Test
    fun `unknown PCI type returns null`() {
        val buf = IsoTpBuffer()
        // PCI type 3 (Flow Control) — not handled by this buffer
        val data = byteArrayOf(0x30, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00)
        val (payload, isFF, isSF) = buf.feed(data)
        assertNull(payload)
        assertFalse(isFF)
        assertFalse(isSF)
    }
}
