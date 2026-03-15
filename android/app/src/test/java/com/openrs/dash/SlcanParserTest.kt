package com.openrs.dash

import com.openrs.dash.can.SlcanParser
import org.junit.Assert.*
import org.junit.Test

class SlcanParserTest {

    // ── Standard frames (t prefix) ──────────────────────────────────────────

    @Test
    fun `parse standard frame with 8 bytes`() {
        // t{ID3}{DLC}{DATA} -> t09060011BBCA000032
        val result = SlcanParser.parse("t09060011BBCA000032")
        assertNotNull(result)
        assertEquals(0x090, result!!.first)
        assertEquals(6, result.second.size)
    }

    @Test
    fun `parse standard frame zero data length`() {
        val result = SlcanParser.parse("t0900")
        assertNotNull(result)
        assertEquals(0x090, result!!.first)
        assertEquals(0, result.second.size)
    }

    @Test
    fun `parse standard frame ID 0x1B0`() {
        val result = SlcanParser.parse("t1B0800000000000020FF")
        assertNotNull(result)
        assertEquals(0x1B0, result!!.first)
        assertEquals(8, result.second.size)
        assertEquals(0x20.toByte(), result.second[6])
    }

    @Test
    fun `parse standard frame data bytes decoded correctly`() {
        // t0901FF -> ID=0x090, DLC=1, data=[0xFF]
        val result = SlcanParser.parse("t0901FF")
        assertNotNull(result)
        assertEquals(1, result!!.second.size)
        assertEquals(0xFF.toByte(), result.second[0])
    }

    // ── Extended frames (T prefix) ──────────────────────────────────────────

    @Test
    fun `parse extended frame`() {
        // T{ID8}{DLC}{DATA} -> T000001B08AABBCCDD00112233
        val result = SlcanParser.parse("T000001B08AABBCCDD00112233")
        assertNotNull(result)
        assertEquals(0x1B0, result!!.first)
        assertEquals(8, result.second.size)
    }

    @Test
    fun `parse extended frame zero data`() {
        val result = SlcanParser.parse("T000000900")
        assertNotNull(result)
        assertEquals(0x090, result!!.first)
        assertEquals(0, result.second.size)
    }

    @Test
    fun `parse extended frame max 29-bit ID`() {
        // 0x1FFFFFFF = max 29-bit CAN ID
        val result = SlcanParser.parse("T1FFFFFFF0")
        assertNotNull(result)
        assertEquals(0x1FFFFFFF, result!!.first)
    }

    // ── Malformed input ─────────────────────────────────────────────────────

    @Test
    fun `empty string returns null`() {
        assertNull(SlcanParser.parse(""))
    }

    @Test
    fun `unknown prefix returns null`() {
        assertNull(SlcanParser.parse("x09060011BBCA000032"))
    }

    @Test
    fun `standard frame too short returns null`() {
        assertNull(SlcanParser.parse("t09"))
    }

    @Test
    fun `extended frame too short returns null`() {
        assertNull(SlcanParser.parse("T0000009"))
    }

    @Test
    fun `standard frame invalid hex ID returns null`() {
        assertNull(SlcanParser.parse("tXYZ0"))
    }

    @Test
    fun `standard frame DLC greater than 8 returns null`() {
        assertNull(SlcanParser.parse("t0909"))
    }

    @Test
    fun `standard frame data shorter than DLC returns null`() {
        // DLC says 4 bytes (8 hex chars) but only 2 hex chars follow
        assertNull(SlcanParser.parse("t0904FF"))
    }

    @Test
    fun `extended frame invalid hex ID returns null`() {
        assertNull(SlcanParser.parse("TXXXXXXXX0"))
    }

    @Test
    fun `extended frame DLC greater than 8 returns null`() {
        assertNull(SlcanParser.parse("T000000909"))
    }

    @Test
    fun `extended frame data shorter than DLC returns null`() {
        assertNull(SlcanParser.parse("T000000904FF"))
    }

    // ── Edge cases ──────────────────────────────────────────────────────────

    @Test
    fun `standard frame with extra trailing data is ok`() {
        // DLC=1, data=FF, then extra "AABB" should be ignored
        val result = SlcanParser.parse("t0901FFAABB")
        assertNotNull(result)
        assertEquals(1, result!!.second.size)
        assertEquals(0xFF.toByte(), result.second[0])
    }

    @Test
    fun `standard frame ID 0x000`() {
        val result = SlcanParser.parse("t0000")
        assertNotNull(result)
        assertEquals(0, result!!.first)
    }

    @Test
    fun `standard frame max ID 0x7FF`() {
        val result = SlcanParser.parse("t7FF0")
        assertNotNull(result)
        assertEquals(0x7FF, result!!.first)
    }
}
