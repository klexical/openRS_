package com.openrs.dash

import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * rc.8 reliability pass, #251 — Mode 22 payload length through the production module parsers. A single-frame positive
 * reply `0N 62 DH DL d1 … d(N−3)` carries N − 3 data bytes; bytes after that are padding. A decoder whose value needs
 * more bytes than the reply carries changes nothing (no value, no freshness stamp), and a PCI that claims more bytes than
 * the frame holds (a truncated line) is refused — the old parsers read a missing second byte as 0 and ignored the PCI.
 * One-byte values in a short reply and complete replies with padding are controls. SYNTHETIC frames.
 */
class Mode22LengthTest {
    @Before fun session() { com.openrs.dash.diagnostics.DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }
    private val base = VehicleState(isConnected = true)

    private fun pcm(vararg b: Int): VehicleState? {
        var out: VehicleState? = null
        ObdResponseParser.parsePcmResponse(bytes(*b), base) { out = it }
        return out
    }

    @Test fun `a two-byte value whose PCI carries one data byte changes nothing - TIP 0x033E`() {
        assertNull(pcm(0x04, 0x62, 0x03, 0x3E, 0x4F, 0x00, 0x00, 0x00))          // PCI 04: the 00 after 4F is padding
        assertEquals("counted for the export (#253)", 1, com.openrs.dash.diagnostics.DiagnosticLogger.linkIntegrity.refusedReplies)
    }

    @Test fun `a PCI that claims more bytes than the frame holds is refused - 0x0304`() {
        assertNull(pcm(0x07, 0x62, 0x03, 0x04, 0x77, 0xAE))                      // PCI 07 in a 6-byte frame (truncated)
    }

    @Test fun `a PCI of three (no data byte) is refused`() {
        assertNull(pcm(0x03, 0x62, 0x04, 0x62, 0x00, 0x00, 0x00, 0x00))
    }

    @Test fun `control - a one-byte value in a PCI 04 reply still decodes - WGDC 0x0462`() {
        val vs = pcm(0x04, 0x62, 0x04, 0x62, 0x40, 0x00, 0x00, 0x00)
        assertEquals(50.0, vs!!.wgdcDesired, 1e-9)                               // 0x40 × 100 / 128
        assertTrue(vs.fieldLastUpdateMs.containsKey("wgdcDesired"))
    }

    @Test fun `control - a complete two-byte reply with padding decodes exactly - 0x0304`() {
        assertEquals(0x77AE / 2048.0, pcm(0x05, 0x62, 0x03, 0x04, 0x77, 0xAE, 0x00, 0x00)!!.batteryVoltage, 0.0)
    }

    @Test fun `a BCM two-byte value whose PCI carries one data byte changes nothing - battery current 0x4090`() {
        var out: VehicleState? = null
        ObdResponseParser.parseBcmResponse(bytes(0x04, 0x62, 0x40, 0x90, 0x20, 0x00, 0x00, 0x00), base) { out = it }
        assertNull(out)
    }

    @Test fun `an AWD two-byte value whose PCI carries one data byte changes nothing`() {
        // 0x1E90 requested torque L (16-bit Nm); PCI 04 carries only its first byte
        var out: VehicleState? = null
        ObdResponseParser.parseAwdResponse(bytes(0x04, 0x62, 0x1E, 0x90, 0x01, 0x00, 0x00, 0x00), base) { out = it }
        assertNull(out)
    }
}
