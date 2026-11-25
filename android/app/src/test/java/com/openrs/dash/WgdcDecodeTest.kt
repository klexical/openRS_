package com.openrs.dash

import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.FreezeFrameParser
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * PCM DID 0x0462 (wastegate duty cycle) has one decode: byte 4 × 100 / 128 %.
 *
 * The live poll (ObdResponseParser) and the DTC freeze frame (FreezeFrameParser) once disagreed: the freeze frame
 * used × 100 / 255, so the same raw byte read about half the live value, and it took one data byte where the PCM
 * answers two (#528: byte 5 varies in every captured session), which shifted every DID after it in a snapshot.
 * The ×/128 scale is the one the FORScan catalog, the exported Focus PID list and the DigiCluster catalog
 * (UINT16 / 327.68, whose high byte is the same ×/128) all give.
 */
class WgdcDecodeTest {

    private fun livePct(b4: Int, b5: Int = 0): Double {
        var out = Double.NaN
        val frame = byteArrayOf(0x05, 0x62, 0x04, 0x62, b4.toByte(), b5.toByte(), 0, 0)
        ObdResponseParser.parsePcmResponse(frame, VehicleState()) { out = it.wgdcDesired }
        return out
    }

    private fun freezeFrame(vararg bytes: Int) =
        FreezeFrameParser.parse("P0234", 1, ByteArray(bytes.size) { bytes[it].toByte() })

    @Test
    fun `live poll reads byte 4 times 100 over 128`() {
        assertEquals(100.0, livePct(0x80), 1e-9)
        assertEquals(50.0, livePct(0x40, 0x1A), 1e-9)
    }

    @Test
    fun `freeze frame wastegate reads the same percent as the live poll`() {
        for (raw in listOf(0x00, 0x0A, 0x40, 0x64, 0x80)) {
            val entry = freezeFrame(0x04, 0x62, raw, 0x00).entries.single()
            assertEquals("raw 0x%02X".format(raw), "%.1f%%".format(livePct(raw)), entry.value)
        }
    }

    @Test
    fun `freeze frame wastegate takes two data bytes so the next DID stays aligned`() {
        // 0x0462 = 40 1A (50 %), then 0xF40D vehicle speed = 0x50 (80 km/h)
        val ff = freezeFrame(0x04, 0x62, 0x40, 0x1A, 0xF4, 0x0D, 0x50)
        assertEquals(listOf(0x0462, 0xF40D), ff.entries.map { it.did })
        assertEquals("50.0%", ff.entries[0].value)
        assertEquals("80 km/h", ff.entries[1].value)
    }
}
