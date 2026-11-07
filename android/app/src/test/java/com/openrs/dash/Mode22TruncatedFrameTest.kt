package com.openrs.dash

import com.openrs.dash.can.Mode22
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * #253: a Mode 22 single frame that claims more bytes than it holds, or too few for its own service bytes, belongs to no
 * request. The parser already refuses those on the data path (#251); these helpers decide who owns a reply.
 */
class Mode22TruncatedFrameTest {
    private fun b(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }

    @Test fun `an NRC that claims more bytes than the frame holds has no code`() {
        assertNull(Mode22.negativeCode(b(0x07, 0x7F, 0x22, 0x31)))
        assertNull(Mode22.negativeCode(b(0x04, 0x7F, 0x22, 0x31)))
    }

    @Test fun `an NRC that claims too few bytes for its own service bytes has no code`() {
        assertNull(Mode22.negativeCode(b(0x01, 0x7F, 0x22, 0x31, 0, 0, 0, 0)))
        assertNull(Mode22.negativeCode(b(0x02, 0x7F, 0x22, 0x31, 0, 0, 0, 0)))
        assertNull(Mode22.negativeCode(b(0x00, 0x7F, 0x22, 0x31, 0, 0, 0, 0)))
    }

    @Test fun `a whole NRC keeps its code, padded or not`() {
        assertEquals(0x31, Mode22.negativeCode(b(0x03, 0x7F, 0x22, 0x31)))
        assertEquals(0x31, Mode22.negativeCode(b(0x03, 0x7F, 0x22, 0x31, 0xCC, 0xCC, 0xCC, 0xCC)))
    }

    @Test fun `a positive reply that claims more bytes than the frame holds names no DID`() {
        assertNull(Mode22.positiveDid(b(0x07, 0x62, 0x03, 0x3E)))
        assertNull(Mode22.positiveDid(b(0x05, 0x62, 0x03, 0x3E, 0x4F)))
    }

    @Test fun `a positive reply that claims too few bytes for its DID names none`() {
        assertNull(Mode22.positiveDid(b(0x02, 0x62, 0x03, 0x3E, 0, 0, 0, 0)))
    }

    @Test fun `whole positive replies and first frames keep their DID`() {
        assertEquals(0x033E, Mode22.positiveDid(b(0x05, 0x62, 0x03, 0x3E, 0x4F, 0x1A, 0, 0)))
        assertEquals(0x033E, Mode22.positiveDid(b(0x05, 0x62, 0x03, 0x3E, 0x4F, 0x1A)))
        assertEquals(0x033E, Mode22.positiveDid(b(0x10, 0x0A, 0x62, 0x03, 0x3E, 0x40, 0, 0)))
    }
}
