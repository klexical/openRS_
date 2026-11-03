package com.openrs.dash

import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.tpmsFooterText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #232 — THERMAL's TPMS footer reads the real tyre sample time through the production path (0x72E reply → parser →
 * ObdMerge → footer): a new reply refreshes it (equal pressure included), an unrelated or older one doesn't.
 */
class TpmsFooterTest {

    /** BCM 0x2813..0x2816 reply: raw 0x0200 → ((512 / 3) + 22 / 3) × 0.145 ≈ 25.8 psi. */
    private fun tpms(did: Int, raw: Int = 0x0200) =
        byteArrayOf(0x05, 0x62, (did shr 8).toByte(), did.toByte(), (raw shr 8).toByte(), raw.toByte(), 0, 0)

    private fun apply(current: VehicleState, frame: ByteArray, snapshot: VehicleState = current): VehicleState {
        var merged = current
        ObdResponseParser.parseBcmResponse(frame, snapshot) { merged = ObdMerge.merge(current, it) }
        return merged
    }

    @Test fun `a TPMS reply's sample time reaches the merged state and the footer`() {
        val merged = apply(VehicleState(isConnected = true), tpms(0x2813))
        assertTrue(merged.tirePressLF > 0)
        assertTrue("sample time lost in the merge: ${merged.tpmsLastUpdate}", merged.tpmsLastUpdate > 0)
        assertEquals("TPMS just now", tpmsFooterText(merged.tpmsLastUpdate, System.currentTimeMillis()))
    }

    @Test fun `an equal pressure received later refreshes the time`() {
        val first = apply(VehicleState(isConnected = true), tpms(0x2813))
        val aged = first.copy(tpmsLastUpdate = first.tpmsLastUpdate - 30_000L)       // that reply was 30 s ago
        assertEquals("TPMS 30s ago", tpmsFooterText(aged.tpmsLastUpdate, first.tpmsLastUpdate))
        val again = apply(aged, tpms(0x2813))                                        // same pressure, new reply
        assertEquals(aged.tirePressLF, again.tirePressLF, 0.0)
        assertTrue(again.tpmsLastUpdate > aged.tpmsLastUpdate)
        assertEquals("TPMS just now", tpmsFooterText(again.tpmsLastUpdate, System.currentTimeMillis()))
    }

    @Test fun `an unrelated or older reply never moves the time`() {
        val live = apply(VehicleState(isConnected = true), tpms(0x2813))
        // Unrelated BCM reply (battery current 0x4028-style DID, decoded from the live state) — time unchanged
        val unrelated = apply(live, byteArrayOf(0x04, 0x62, 0x40, 0x28, 0x05, 0, 0, 0))
        assertEquals(live.tpmsLastUpdate, unrelated.tpmsLastUpdate)
        // A reply decoded from an older snapshot carrying an older sample time can't move it back
        val older = VehicleState(isConnected = true, tpmsLastUpdate = live.tpmsLastUpdate - 60_000L)
        var merged = live
        ObdMerge.merge(live, older).let { merged = it }
        assertEquals(live.tpmsLastUpdate, merged.tpmsLastUpdate)
    }

    @Test fun `never received stays awaiting data`() {
        assertEquals("TPMS awaiting data", tpmsFooterText(0L, System.currentTimeMillis()))
    }
}
