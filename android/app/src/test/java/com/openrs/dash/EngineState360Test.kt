package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.engineStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #328 — DIAG's ENGINE readout read 0x360 byte 0 with names for 0, 2, 183, 186, 191 and 196; on the car byte 0 reads 0xC0
 * with the engine off and 0xC0 / 0xC4 while it runs, so ENGINE did not tell off from running. Byte 7 follows the engine
 * (research on one car's wired captures): 0x02 at 0 rpm, top bit set while running. ENGINE now reads byte 7: "Off",
 * "Running", any other value raw and marked unidentified; not received "—". The odometer from the same frame is unchanged.
 */
class EngineState360Test {
    /** A real 0x360 frame (CanDecoderTest's 67,500 km frame): byte 0 0xC4, byte 7 0xB2. */
    private val running = byteArrayOf(0xC4.toByte(), 0xC0.toByte(), 0x3F, 0x01, 0x07, 0xAC.toByte(), 0xBC.toByte(), 0xB2.toByte())
    private fun withByte7(b: Int) = running.copyOf().also { it[7] = b.toByte() }

    @Test fun `byte 7 with its top bit set reads Running, the odometer unchanged`() {
        val vs = CanDecoder.decode(0x360, running, VehicleState())!!
        assertEquals(0xB2, vs.engineState360)
        assertEquals("Running", engineStatusLabel(vs.engineState360))
        assertEquals(67_500L, vs.odometerKm)
    }

    @Test fun `byte 7 at 0x02 reads Off`() {
        val vs = CanDecoder.decode(0x360, withByte7(0x02), VehicleState())!!
        assertEquals("Off", engineStatusLabel(vs.engineState360))
    }

    @Test fun `any other byte 7 value is shown raw and marked unidentified`() {
        assertEquals("0x00 · unidentified", engineStatusLabel(CanDecoder.decode(0x360, withByte7(0x00), VehicleState())!!.engineState360))
        assertEquals("0x7F · unidentified", engineStatusLabel(0x7F))
    }

    @Test fun `not received reads a dash, and a frame too short for byte 7 keeps the last reading`() {
        assertEquals("—", engineStatusLabel(VehicleState().engineState360))
        val short = CanDecoder.decode(0x360, running.copyOf(6), VehicleState(engineState360 = 0x02))!!
        assertEquals(0x02, short.engineState360)
    }

    @Test fun `byte 0, the old source, no longer decides the label - the decode trace prints it from the frame`() {
        // 0xC4 with the engine off (byte 7 = 0x02) used to read "0xC4"; the old names (183 Running…) never apply
        val frame = withByte7(0x02)
        val vs = CanDecoder.decode(0x360, frame, VehicleState())!!
        assertEquals("Off", engineStatusLabel(vs.engineState360))
        assertEquals(true, CanDecoder.describeDecoded(0x360, vs, frame).contains("byte0=196 engine=2"))
        assertEquals("Running", engineStatusLabel(0xBA))       // 186, seen after a start
    }
}
