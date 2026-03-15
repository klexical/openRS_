package com.openrs.dash

import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.VehicleState
import org.junit.Assert.*
import org.junit.Test

/**
 * Tests for OBD-II Mode 22 response parsing.
 *
 * Response frame layout (Ford +8 offset):
 *   data[0] = PCI (SF length)
 *   data[1] = 0x62 (positive response)
 *   data[2] = DID high byte
 *   data[3] = DID low byte
 *   data[4..] = payload bytes (B4, B5, ...)
 */
class ObdResponseParserTest {

    private val blank = VehicleState()

    private fun makeResponse(didHi: Int, didLo: Int, vararg payload: Int): ByteArray {
        val pci = 3 + payload.size
        return byteArrayOf(
            pci.toByte(), 0x62,
            didHi.toByte(), didLo.toByte(),
            *payload.map { it.toByte() }.toByteArray()
        )
    }

    // ── PCM Responses (0x7E8) ───────────────────────────────────────────────

    @Test
    fun `PCM - ETC actual angle`() {
        // DID 0x093C: (B4 << 8 | B5) * (100/8192)
        // raw 4096 -> 4096 * 100/8192 = 50%
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x09, 0x3C, 0x10, 0x00),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(50.0, result!!.etcAngleActual, 0.1)
    }

    @Test
    fun `PCM - ETC desired angle`() {
        // DID 0x091A: same formula as actual
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x09, 0x1A, 0x10, 0x00),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(50.0, result!!.etcAngleDesired, 0.1)
    }

    @Test
    fun `PCM - wastegate duty cycle`() {
        // DID 0x0462: B4 * 100/128
        // 128 -> 100%
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x04, 0x62, 128),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(100.0, result!!.wgdcDesired, 0.1)
    }

    @Test
    fun `PCM - ignition correction cylinder 1`() {
        // DID 0x03EC: ((B4 signed << 8) | B5) / -512.0
        // raw = 0xFF00 (signed B4=-1, B5=0) -> (-1 << 8 | 0) = -256 -> -256 / -512 = 0.5
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x03, 0xEC, 0xFF, 0x00),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(0.5, result!!.ignCorrCyl1, 0.01)
    }

    @Test
    fun `PCM - octane adjust ratio`() {
        // DID 0x03E8: ((B4 signed << 8) | B5) / 16384.0
        // raw = (0, 0) -> 0/16384 = 0.0
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x03, 0xE8, 0x00, 0x00),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(0.0, result!!.octaneAdjustRatio, 0.001)
    }

    @Test
    fun `PCM - charge air temp`() {
        // DID 0x0461: ((B4 signed << 8) | B5) / 64.0
        // raw = (0x10, 0x00) -> 4096 / 64 = 64°C
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x04, 0x61, 0x10, 0x00),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(64.0, result!!.chargeAirTempC, 0.1)
    }

    @Test
    fun `PCM - catalytic temp`() {
        // DID 0xF43C: ((B4 << 8) | B5) / 10.0 - 40.0
        // raw = (0x01, 0xF4) = 500 -> 500/10 - 40 = 10°C
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0xF4, 0x3C, 0x01, 0xF4),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(10.0, result!!.catalyticTempC, 0.1)
    }

    @Test
    fun `PCM - AFR actual and lambda`() {
        // DID 0xF434: ((B4 << 8) | B5) * 0.0004486
        // Lambda = AFR / 14.7
        // raw = (0x80, 0x00) = 32768 -> 32768 * 0.0004486 = 14.7 (stoich)
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0xF4, 0x34, 0x80, 0x00),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(14.7, result!!.afrActual, 0.1)
        assertEquals(1.0, result!!.lambdaActual, 0.01)
    }

    @Test
    fun `PCM - AFR desired`() {
        // DID 0xF444: B4 * 0.1144
        // 128 -> 128 * 0.1144 = 14.64
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0xF4, 0x44, 128),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(14.64, result!!.afrDesired, 0.1)
    }

    @Test
    fun `PCM - TIP actual`() {
        // DID 0x033E: ((B4 << 8) | B5) / 903.81
        // raw = (0x03, 0x88) = 904 -> 904 / 903.81 ≈ 1.0 kPa
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x03, 0x3E, 0x03, 0x88),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(1.0, result!!.tipActualKpa, 0.05)
    }

    @Test
    fun `PCM - TIP desired`() {
        // DID 0x0466: same formula as TIP actual
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x04, 0x66, 0x03, 0x88),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(1.0, result!!.tipDesiredKpa, 0.05)
    }

    @Test
    fun `PCM - VCT intake angle`() {
        // DID 0x0318: ((B4 signed << 8) | B5) / 16.0
        // raw = (0x01, 0x00) = 256 -> 256 / 16 = 16.0 degrees
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x03, 0x18, 0x01, 0x00),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(16.0, result!!.vctIntakeAngle, 0.1)
    }

    @Test
    fun `PCM - VCT exhaust angle`() {
        // DID 0x0319: same formula as intake
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x03, 0x19, 0x01, 0x00),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(16.0, result!!.vctExhaustAngle, 0.1)
    }

    @Test
    fun `PCM - oil life percent`() {
        // DID 0x054B: B4 clamped 0-100
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x05, 0x4B, 75),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(75.0, result!!.oilLifePct, 0.1)
    }

    @Test
    fun `PCM - oil life clamped at 100`() {
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0x05, 0x4B, 150),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(100.0, result!!.oilLifePct, 0.1)
    }

    @Test
    fun `PCM - HP fuel rail pressure`() {
        // DID 0xF422: ((B4 << 8) | B5) * 1.45038
        // raw = (0x00, 0x64) = 100 -> 100 * 1.45038 = 145.038 PSI
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0xF4, 0x22, 0x00, 0x64),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(145.038, result!!.hpFuelRailPsi, 0.1)
    }

    @Test
    fun `PCM - fuel level (0xF42F)`() {
        // DID 0xF42F: (B4 * 100/255) clamped 0-100
        // 128 -> 128 * 100/255 = 50.2%
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            makeResponse(0xF4, 0x2F, 128),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(50.2, result!!.fuelLevelPct, 0.5)
    }

    @Test
    fun `PCM - short response ignored`() {
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            byteArrayOf(0x02, 0x62, 0x03, 0xEC.toByte()),
            blank
        ) { result = it }
        assertNull(result)
    }

    @Test
    fun `PCM - non-0x62 response ignored`() {
        var result: VehicleState? = null
        ObdResponseParser.parsePcmResponse(
            byteArrayOf(0x05, 0x7F, 0x03, 0xEC.toByte(), 0x00, 0x00),
            blank
        ) { result = it }
        assertNull(result)
    }

    // ── BCM Responses (0x72E) ───────────────────────────────────────────────

    @Test
    fun `BCM - odometer`() {
        // DID 0xDD01: 3-byte km = (B4 << 16) | (B5 << 8) | B6
        // 50000 km = 0x00C350 -> B4=0x00, B5=0xC3, B6=0x50
        var result: VehicleState? = null
        ObdResponseParser.parseBcmResponse(
            makeResponse(0xDD, 0x01, 0x00, 0xC3, 0x50),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(50000L, result!!.odometerKm)
    }

    @Test
    fun `BCM - battery SOC`() {
        // DID 0x4028: B4 as percent
        var result: VehicleState? = null
        ObdResponseParser.parseBcmResponse(
            makeResponse(0x40, 0x28, 85),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(85.0, result!!.batterySoc, 0.1)
    }

    @Test
    fun `BCM - battery temp`() {
        // DID 0x4029: B4 - 40 °C
        // 65 - 40 = 25°C
        var result: VehicleState? = null
        ObdResponseParser.parseBcmResponse(
            makeResponse(0x40, 0x29, 65),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(25.0, result!!.batteryTempC, 0.1)
    }

    @Test
    fun `BCM - cabin temp`() {
        // DID 0xDD04: (B4 * 10/9) - 45
        // 90 * 10/9 - 45 = 55°C
        var result: VehicleState? = null
        ObdResponseParser.parseBcmResponse(
            makeResponse(0xDD, 0x04, 90),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(55.0, result!!.cabinTempC, 0.5)
    }

    @Test
    fun `BCM - TPMS LF pressure`() {
        // DID 0x2813: PSI = ((B4*256 + B5) / 3 + 22/3) * 0.145
        // B4=0x00, B5=0xC8 (200) -> (200/3 + 22/3) * 0.145 = (74) * 0.145 = 10.73 PSI
        // More realistic: B4=0x02, B5=0xBB (699) -> (699/3 + 22/3) * 0.145 = (240.333) * 0.145 ≈ 34.85 PSI
        var result: VehicleState? = null
        ObdResponseParser.parseBcmResponse(
            makeResponse(0x28, 0x13, 0x02, 0xBB),
            blank
        ) { result = it }
        assertNotNull(result)
        assertTrue(result!!.tirePressLF > 30.0 && result.tirePressLF < 40.0)
    }

    @Test
    fun `BCM - TPMS RF pressure`() {
        var result: VehicleState? = null
        ObdResponseParser.parseBcmResponse(
            makeResponse(0x28, 0x14, 0x02, 0xBB),
            blank
        ) { result = it }
        assertNotNull(result)
        assertTrue(result!!.tirePressRF > 30.0)
    }

    @Test
    fun `BCM - TPMS LR pressure`() {
        var result: VehicleState? = null
        ObdResponseParser.parseBcmResponse(
            makeResponse(0x28, 0x16, 0x02, 0xBB),
            blank
        ) { result = it }
        assertNotNull(result)
        assertTrue(result!!.tirePressLR > 30.0)
    }

    @Test
    fun `BCM - TPMS RR pressure`() {
        var result: VehicleState? = null
        ObdResponseParser.parseBcmResponse(
            makeResponse(0x28, 0x15, 0x02, 0xBB),
            blank
        ) { result = it }
        assertNotNull(result)
        assertTrue(result!!.tirePressRR > 30.0)
    }

    @Test
    fun `BCM - TPMS out of range ignored`() {
        // PSI < 5 or > 70 should be ignored
        // B4=0x00, B5=0x01 -> (1/3 + 22/3) * 0.145 ≈ 1.11 PSI -> rejected
        var result: VehicleState? = null
        ObdResponseParser.parseBcmResponse(
            makeResponse(0x28, 0x13, 0x00, 0x01),
            blank
        ) { result = it }
        assertNull(result)
    }

    @Test
    fun `BCM - short response ignored`() {
        var result: VehicleState? = null
        ObdResponseParser.parseBcmResponse(
            byteArrayOf(0x02, 0x62, 0xDD.toByte(), 0x01),
            blank
        ) { result = it }
        assertNull(result)
    }

    // ── AWD Responses (0x70B) ───────────────────────────────────────────────

    @Test
    fun `AWD - RDU temp`() {
        // DID 0x1E8A: B4 - 40 °C
        // 80 - 40 = 40°C
        var result: VehicleState? = null
        ObdResponseParser.parseAwdResponse(
            makeResponse(0x1E, 0x8A, 80),
            blank
        ) { result = it }
        assertNotNull(result)
        assertEquals(40.0, result!!.rduTempC, 0.1)
    }

    @Test
    fun `AWD - RDU enabled`() {
        // DID 0xEE0B: B4 == 0x01 -> enabled
        var result: VehicleState? = null
        ObdResponseParser.parseAwdResponse(
            makeResponse(0xEE, 0x0B, 0x01),
            blank
        ) { result = it }
        assertNotNull(result)
        assertTrue(result!!.rduEnabled!!)
    }

    @Test
    fun `AWD - RDU disabled`() {
        var result: VehicleState? = null
        ObdResponseParser.parseAwdResponse(
            makeResponse(0xEE, 0x0B, 0x00),
            blank
        ) { result = it }
        assertNotNull(result)
        assertFalse(result!!.rduEnabled!!)
    }

    @Test
    fun `AWD - short response ignored`() {
        var result: VehicleState? = null
        ObdResponseParser.parseAwdResponse(
            byteArrayOf(0x02, 0x62, 0x1E, 0x8A.toByte()),
            blank
        ) { result = it }
        assertNull(result)
    }

    // ── PSCM Responses (0x738) ──────────────────────────────────────────────

    @Test
    fun `PSCM - pull drift compensation enabled`() {
        // DID 0xFD07: B4 == 0x01 -> enabled
        var result: VehicleState? = null
        ObdResponseParser.parsePscmResponse(
            makeResponse(0xFD, 0x07, 0x01),
            blank
        ) { result = it }
        assertNotNull(result)
        assertTrue(result!!.pdcEnabled!!)
    }

    @Test
    fun `PSCM - pull drift compensation disabled`() {
        var result: VehicleState? = null
        ObdResponseParser.parsePscmResponse(
            makeResponse(0xFD, 0x07, 0x00),
            blank
        ) { result = it }
        assertNotNull(result)
        assertFalse(result!!.pdcEnabled!!)
    }

    // ── FENG Responses (0x72F) ──────────────────────────────────────────────

    @Test
    fun `FENG - fake engine noise enabled`() {
        // DID 0xEE03: B4 == 0x01 -> enabled
        var result: VehicleState? = null
        ObdResponseParser.parseFengResponse(
            makeResponse(0xEE, 0x03, 0x01),
            blank
        ) { result = it }
        assertNotNull(result)
        assertTrue(result!!.fengEnabled!!)
    }

    @Test
    fun `FENG - fake engine noise disabled`() {
        var result: VehicleState? = null
        ObdResponseParser.parseFengResponse(
            makeResponse(0xEE, 0x03, 0x00),
            blank
        ) { result = it }
        assertNotNull(result)
        assertFalse(result!!.fengEnabled!!)
    }

    // ── RSProt Responses (0x739) ────────────────────────────────────────────

    @Test
    fun `RSProt - launch control armed`() {
        var result: VehicleState? = null
        ObdResponseParser.parseRsprotResponse(
            makeResponse(0xDE, 0x00, 0x01),
            blank,
            onObdUpdate = { result = it }
        )
        assertNotNull(result)
        assertTrue(result!!.lcArmed!!)
    }

    @Test
    fun `RSProt - launch control RPM target`() {
        // DID 0xDE01: (B4 << 8) | B5
        // 4500 RPM = 0x1194 -> B4=0x11, B5=0x94
        var result: VehicleState? = null
        ObdResponseParser.parseRsprotResponse(
            makeResponse(0xDE, 0x01, 0x11, 0x94),
            blank,
            onObdUpdate = { result = it }
        )
        assertNotNull(result)
        assertEquals(4500, result!!.lcRpmTarget)
    }

    @Test
    fun `RSProt - auto start-stop enabled`() {
        var result: VehicleState? = null
        ObdResponseParser.parseRsprotResponse(
            makeResponse(0xDE, 0x02, 0x01),
            blank,
            onObdUpdate = { result = it }
        )
        assertNotNull(result)
        assertTrue(result!!.assEnabled!!)
    }
}
