package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * #207 — BOOST through the production decode path (CAN decoder + PCM parser + merge), from the 2026-10-28 AM drive:
 * 0x0F8 byte 5 stayed 0 in every frame while TIP (0x033E) read 158.20 kPa at 3,676 rpm. The recorded boost was
 * −0.63 psi (byte 5 + measured baro, minus standard atmosphere).
 */
class BoostSourceTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    @Before fun reset() = CanDecoder.resetSessionState()

    /** 0x090 with baro byte 0xC1 (96.5 kPa) and the engine running, then a 0x0F8 frame with the given byte 5. */
    private fun canState(byte5: Int): VehicleState {
        var vs = CanDecoder.decode(0x090, hex("0000C1FF617D0000"), VehicleState(isConnected = true))!!
        vs = CanDecoder.decode(0x0F8, hex("0042000000" + "%02X".format(byte5) + "1052"), vs)!!
        return vs
    }

    private fun withTip(vs: VehicleState, raw: Int): VehicleState {
        var out = vs
        ObdResponseParser.parsePcmResponse(hex("0562033E%04X".format(raw)), vs) { out = ObdMerge.merge(vs, it) }
        return out
    }

    @Test fun `fresh TIP under boost gives gauge boost, not the CAN zero`() {
        var vs = withTip(canState(0), 0x4F1A)                           // TIP 158.20 kPa abs
        vs = CanDecoder.decode(0x0F8, hex("0042000000001052"), vs)!!    // next CAN frame, byte 5 still 0
        assertEquals((158.203 - 96.5) * 0.14503773, vs.boostPsi, 0.05)  // ≈ 8.95 psi
    }

    @Test fun `CAN zero with no fresh TIP reads 0 psi gauge, not minus the baro offset`() {
        assertEquals(0.0, canState(0).boostPsi, 0.001)                  // was −0.70 psi
    }

    @Test fun `CAN byte 5 is kPa gauge relative to ambient`() {
        assertEquals(21 * 0.14503773, canState(21).boostPsi, 0.001)
    }
}
