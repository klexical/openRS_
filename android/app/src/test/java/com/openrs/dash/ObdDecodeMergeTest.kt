package com.openrs.dash

import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Test

/** Decode → merge as CanDataService applies a PCM reply (#205): a negative spark advance is a value, not "missing". */
class ObdDecodeMergeTest {
    private fun reply(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun apply(current: VehicleState, frame: ByteArray): VehicleState {
        var merged = current
        ObdResponseParser.parsePcmResponse(frame, current) { merged = ObdMerge.merge(current, it) }
        return merged
    }

    @Test fun `negative spark replaces the previous value`() {
        val before = VehicleState(sparkAdvance = 14.75)
        val after = apply(before, reply(0x05, 0x62, 0x11, 0x6B, 0xFF, 0xEA))
        assertEquals(-5.5, after.sparkAdvance, 0.001)
    }

    @Test fun `a reply for another DID keeps spark, and never-received stays the sentinel`() {
        val after = apply(VehicleState(sparkAdvance = -5.5), reply(0x05, 0x62, 0x03, 0x3E, 0x36, 0x10))
        assertEquals(-5.5, after.sparkAdvance, 0.001)
        assertEquals(-99.0, VehicleState().sparkAdvance, 0.0)
    }
}
