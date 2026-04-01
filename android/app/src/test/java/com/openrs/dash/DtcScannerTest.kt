package com.openrs.dash

import com.openrs.dash.data.DtcStatus
import com.openrs.dash.diagnostics.DtcScanner
import org.junit.Assert.*
import org.junit.Test

class DtcScannerTest {

    // ═════════════════════════════════════════════════════════════════════════
    // decodeDtcCode — SAE J2012 two-byte → alphanumeric string
    //
    // Byte layout:
    //   high[7:6] = system prefix (00=P, 01=C, 10=B, 11=U)
    //   high[5:4] = first digit (0-3)
    //   high[3:0] = second hex digit
    //   mid[7:4]  = third hex digit
    //   mid[3:0]  = fourth hex digit
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `decodeDtcCode system prefix P`() {
        // high=0x00 → bits 7-6 = 00 → P, rest zeros
        assertEquals("P0000", DtcScanner.decodeDtcCode(0x00, 0x00))
    }

    @Test fun `decodeDtcCode system prefix C`() {
        // high=0x40 → bits 7-6 = 01 → C
        assertEquals("C0000", DtcScanner.decodeDtcCode(0x40, 0x00))
    }

    @Test fun `decodeDtcCode system prefix B`() {
        // high=0x80 → bits 7-6 = 10 → B
        assertEquals("B0000", DtcScanner.decodeDtcCode(0x80, 0x00))
    }

    @Test fun `decodeDtcCode system prefix U`() {
        // high=0xC0 → bits 7-6 = 11 → U
        assertEquals("U0000", DtcScanner.decodeDtcCode(0xC0, 0x00))
    }

    @Test fun `decodeDtcCode common powertrain code P0123`() {
        // P0123: high = 0b_00_01_0010 = 0x12, mid = 0x30 (d3=3, d4=0) → wait
        // P = 00, d1=0, d2=1, d3=2, d4=3
        // high = 00_00_0001 = 0x01, mid = 0x23
        assertEquals("P0123", DtcScanner.decodeDtcCode(0x01, 0x23))
    }

    @Test fun `decodeDtcCode Ford-specific P2BAF`() {
        // P2BAF: P=00, d1=2, d2=B(11), d3=A(10), d4=F(15)
        // high = 00_10_1011 = 0x2B, mid = 0xAF
        assertEquals("P2BAF", DtcScanner.decodeDtcCode(0x2B, 0xAF))
    }

    @Test fun `decodeDtcCode all hex digits max P3FFF`() {
        // P3FFF: P=00, d1=3, d2=F, d3=F, d4=F
        // high = 00_11_1111 = 0x3F, mid = 0xFF
        assertEquals("P3FFF", DtcScanner.decodeDtcCode(0x3F, 0xFF))
    }

    @Test fun `decodeDtcCode U-code network fault U0100`() {
        // U0100: U=11, d1=0, d2=1, d3=0, d4=0
        // high = 11_00_0001 = 0xC1, mid = 0x00
        assertEquals("U0100", DtcScanner.decodeDtcCode(0xC1, 0x00))
    }

    @Test fun `decodeDtcCode B-code body fault B1342`() {
        // B1342: B=10, d1=1, d2=3, d3=4, d4=2
        // high = 10_01_0011 = 0x93, mid = 0x42
        assertEquals("B1342", DtcScanner.decodeDtcCode(0x93, 0x42))
    }

    @Test fun `decodeDtcCode C-code chassis fault C0034`() {
        // C0034: C=01, d1=0, d2=0, d3=3, d4=4
        // high = 01_00_0000 = 0x40, mid = 0x34
        assertEquals("C0034", DtcScanner.decodeDtcCode(0x40, 0x34))
    }

    @Test fun `decodeDtcCode all bits set UFFFF`() {
        // high=0xFF → U (11), d1=3, d2=F; mid=0xFF → d3=F, d4=F
        assertEquals("U3FFF", DtcScanner.decodeDtcCode(0xFF, 0xFF))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // classifyStatus — UDS DTCStatusByte bit priority
    //
    // bit 0 (0x01) = testFailed    → ACTIVE
    // bit 2 (0x04) = pendingDTC    → PENDING
    // bit 3 (0x08) = confirmedDTC  → PERMANENT
    // Priority: ACTIVE > PERMANENT > PENDING > UNKNOWN
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `classifyStatus zero is UNKNOWN`() {
        assertEquals(DtcStatus.UNKNOWN, DtcScanner.classifyStatus(0x00))
    }

    @Test fun `classifyStatus bit 0 only is ACTIVE`() {
        assertEquals(DtcStatus.ACTIVE, DtcScanner.classifyStatus(0x01))
    }

    @Test fun `classifyStatus bit 2 only is PENDING`() {
        assertEquals(DtcStatus.PENDING, DtcScanner.classifyStatus(0x04))
    }

    @Test fun `classifyStatus bit 3 only is PERMANENT`() {
        assertEquals(DtcStatus.PERMANENT, DtcScanner.classifyStatus(0x08))
    }

    @Test fun `classifyStatus ACTIVE beats PERMANENT when both set`() {
        // bits 0+3 = 0x09 → ACTIVE wins
        assertEquals(DtcStatus.ACTIVE, DtcScanner.classifyStatus(0x09))
    }

    @Test fun `classifyStatus ACTIVE beats PENDING when both set`() {
        // bits 0+2 = 0x05 → ACTIVE wins
        assertEquals(DtcStatus.ACTIVE, DtcScanner.classifyStatus(0x05))
    }

    @Test fun `classifyStatus PERMANENT beats PENDING when both set`() {
        // bits 2+3 = 0x0C → PERMANENT wins
        assertEquals(DtcStatus.PERMANENT, DtcScanner.classifyStatus(0x0C))
    }

    @Test fun `classifyStatus all bits set is ACTIVE`() {
        assertEquals(DtcStatus.ACTIVE, DtcScanner.classifyStatus(0xFF))
    }

    @Test fun `classifyStatus irrelevant bits ignored`() {
        // 0x02 (bit 1) is testFailedThisOperation — not mapped, should be UNKNOWN
        assertEquals(DtcStatus.UNKNOWN, DtcScanner.classifyStatus(0x02))
    }

    // ═════════════════════════════════════════════════════════════════════════
    // parsePayload — UDS 0x19/02 response → DtcResult list
    //
    // Layout: [0x59, 0x02, availMask, DTC_high, DTC_mid, statusByte, ...]
    // ═════════════════════════════════════════════════════════════════════════

    @Test fun `parsePayload empty array returns empty`() {
        assertTrue(DtcScanner.parsePayload("PCM", byteArrayOf()).isEmpty())
    }

    @Test fun `parsePayload too short returns empty`() {
        assertTrue(DtcScanner.parsePayload("PCM", byteArrayOf(0x59, 0x02)).isEmpty())
    }

    @Test fun `parsePayload wrong service ID returns empty`() {
        // 0x7F = negative response, not 0x59
        assertTrue(DtcScanner.parsePayload("PCM", byteArrayOf(0x7F, 0x19, 0x12)).isEmpty())
    }

    @Test fun `parsePayload header only no DTCs`() {
        // Valid header (59 02 FF) but no DTC records
        val payload = byteArrayOf(0x59, 0x02, 0xFF.toByte())
        assertTrue(DtcScanner.parsePayload("PCM", payload).isEmpty())
    }

    @Test fun `parsePayload single DTC parsed correctly`() {
        // 59 02 FF [01 23 01] → P0123 ACTIVE
        val payload = byteArrayOf(0x59, 0x02, 0xFF.toByte(), 0x01, 0x23, 0x01)
        val results = DtcScanner.parsePayload("PCM", payload)
        assertEquals(1, results.size)
        assertEquals("PCM", results[0].module)
        assertEquals("P0123", results[0].code)
        assertEquals(DtcStatus.ACTIVE, results[0].status)
    }

    @Test fun `parsePayload multiple DTCs parsed`() {
        // Two DTCs: P0123 ACTIVE + U0100 PERMANENT
        val payload = byteArrayOf(
            0x59, 0x02, 0xFF.toByte(),
            0x01, 0x23, 0x01,          // P0123, status=ACTIVE
            0xC1.toByte(), 0x00, 0x08  // U0100, status=PERMANENT
        )
        val results = DtcScanner.parsePayload("BCM", payload)
        assertEquals(2, results.size)
        assertEquals("P0123", results[0].code)
        assertEquals(DtcStatus.ACTIVE, results[0].status)
        assertEquals("U0100", results[1].code)
        assertEquals(DtcStatus.PERMANENT, results[1].status)
        assertTrue(results.all { it.module == "BCM" })
    }

    @Test fun `parsePayload zero-padded DTCs skipped`() {
        // DTC with high=0, mid=0 should be skipped (padding)
        val payload = byteArrayOf(
            0x59, 0x02, 0xFF.toByte(),
            0x00, 0x00, 0x01,          // zero padding — skip
            0x01, 0x23, 0x04           // P0123, PENDING
        )
        val results = DtcScanner.parsePayload("ABS", payload)
        assertEquals(1, results.size)
        assertEquals("P0123", results[0].code)
    }

    @Test fun `parsePayload trailing incomplete record ignored`() {
        // Valid DTC followed by only 2 trailing bytes (incomplete record)
        val payload = byteArrayOf(
            0x59, 0x02, 0xFF.toByte(),
            0x01, 0x23, 0x01,          // P0123, ACTIVE
            0xAB.toByte(), 0xCD.toByte() // incomplete — only 2 bytes
        )
        val results = DtcScanner.parsePayload("PCM", payload)
        assertEquals(1, results.size)
    }
}
