package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.VehicleState
import org.junit.Assert.*
import org.junit.Test

class CanDecoderTest {

    private val blank = VehicleState()

    @Test
    fun `decode RPM frame`() {
        // 0x090: RPM = word(0,1) * 0.25, coolant = byte(2) - 40
        // RPM 6000 = 24000 raw = 0x5DC0, coolant 90°C = 130 = 0x82
        val data = byteArrayOf(0x5D.toByte(), 0xC0.toByte(), 0x82.toByte())
        val result = CanDecoder.decode(0x090, data, blank)
        assertNotNull(result)
        assertEquals(6000.0, result!!.rpm, 0.5)
        assertEquals(90.0, result.coolantTempC, 0.5)
    }

    @Test
    fun `decode speed frame`() {
        // 0x076: throttle = byte(0) * 0.392, speed = word(2,3) * 0.01
        // Speed 100 km/h = 10000 raw = 0x2710
        val data = byteArrayOf(0xFF.toByte(), 0x00, 0x27, 0x10)
        val result = CanDecoder.decode(0x076, data, blank)
        assertNotNull(result)
        assertEquals(100.0, result!!.speedKph, 0.1)
    }

    @Test
    fun `unknown CAN ID returns null`() {
        val data = byteArrayOf(0x00, 0x01, 0x02)
        val result = CanDecoder.decode(0x999, data, blank)
        assertNull(result)
    }

    @Test
    fun `decode dynamics frame`() {
        // 0x0B0: yaw = (word - 32768) * 0.01, latG = (word - 32768) * 0.001
        // Zero values at 32768 = 0x8000
        val data = byteArrayOf(
            0x80.toByte(), 0x00, // yaw = 0
            0x80.toByte(), 0x00, // latG = 0
            0x80.toByte(), 0x00  // lonG = 0
        )
        val result = CanDecoder.decode(0x0B0, data, blank)
        assertNotNull(result)
        assertEquals(0.0, result!!.yawRate, 0.01)
        assertEquals(0.0, result.lateralG, 0.001)
    }

    @Test
    fun `short data returns null`() {
        // RPM frame needs at least 3 bytes
        val data = byteArrayOf(0x00, 0x01)
        val result = CanDecoder.decode(0x090, data, blank)
        assertNull(result)
    }

    @Test
    fun `drive mode decode`() {
        // 0x1E3: bits 0-4
        val sport = byteArrayOf(0x10.toByte()) // bit pattern 0001 xxxx = sport
        val result = CanDecoder.decode(0x1E3, sport, blank)
        assertNotNull(result)
        assertEquals(DriveMode.SPORT, result!!.driveMode)
    }
}
