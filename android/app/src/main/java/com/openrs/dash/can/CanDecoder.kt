package com.openrs.dash.can

import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState

/**
 * Focus RS MK3 HS-CAN passive frame decoder.
 * Handles frames received during ATMA (Monitor All) mode.
 */
object CanDecoder {

    const val ID_TORQUE         = 0x070
    const val ID_THROTTLE_SPEED = 0x076
    const val ID_PEDALS_STEER   = 0x080
    const val ID_ENGINE_RPM     = 0x090
    const val ID_DYNAMICS       = 0x0B0
    const val ID_GAUGE_ILLUM    = 0x0C8
    const val ID_ENGINE_TEMPS   = 0x0F8
    // 0x1B0 (AWDmsg01): DriveMode in bits 55|4 — confirmed RS_HS.dbc (values: 0=Normal,1=Sport,2=Drift)
    const val ID_AWD_MSG        = 0x1B0
    // 0x1C0 (ABSmsg04): ESCMode in bits 13|2 — confirmed RS_HS.dbc (0=Normal,1=Off,2=Sport,3=Launch)
    const val ID_ESC_ABS        = 0x1C0
    const val ID_WHEEL_SPEEDS   = 0x215
    const val ID_GEAR           = 0x230
    const val ID_AWD_TORQUE     = 0x2C0
    const val ID_PTU_TEMP       = 0x2C2
    const val ID_FUEL_LEVEL     = 0x34A
    const val ID_AMBIENT_TEMP   = 0x34C
    const val ID_BATTERY        = 0x3C0

    private val KNOWN_IDS = setOf(
        ID_TORQUE, ID_THROTTLE_SPEED, ID_PEDALS_STEER, ID_ENGINE_RPM,
        ID_DYNAMICS, ID_GAUGE_ILLUM, ID_ENGINE_TEMPS,
        ID_AWD_MSG, ID_ESC_ABS,
        ID_WHEEL_SPEEDS, ID_GEAR, ID_AWD_TORQUE,
        ID_PTU_TEMP, ID_FUEL_LEVEL, ID_AMBIENT_TEMP, ID_BATTERY
    )

    fun decode(id: Int, data: ByteArray, state: VehicleState): VehicleState? {
        if (id !in KNOWN_IDS) return null
        val n = data.size
        val now = System.currentTimeMillis()

        return when (id) {
            ID_ENGINE_RPM -> if (n >= 3) state.copy(
                rpm = word(data, 0) * 0.25,
                coolantTempC = (ubyte(data, 2) - 40).toDouble(),
                lastUpdate = now
            ) else null

            ID_ENGINE_TEMPS -> if (n >= 8) state.copy(
                intakeTempC = (ubyte(data, 4) - 40).toDouble(),
                boostKpa = ubyte(data, 5).toDouble(),
                oilTempC = (ubyte(data, 7) - 60).toDouble(),
                lastUpdate = now
            ) else null

            ID_THROTTLE_SPEED -> if (n >= 4) state.copy(
                throttlePct = ubyte(data, 0) * 0.392,
                speedKph = word(data, 2) * 0.01,
                lastUpdate = now
            ) else null

            ID_PEDALS_STEER -> if (n >= 6) state.copy(
                accelPedalPct = ubyte(data, 0) * 0.392,
                steeringAngle = (word(data, 2) - 20000) * 0.1,
                brakePressure = word(data, 4) * 0.1,
                lastUpdate = now
            ) else null

            ID_DYNAMICS -> if (n >= 6) state.copy(
                yawRate = (word(data, 0) - 32768) * 0.01,
                lateralG = (word(data, 2) - 32768) * 0.001,
                longitudinalG = (word(data, 4) - 32768) * 0.001,
                lastUpdate = now
            ) else null

            ID_GAUGE_ILLUM -> if (n >= 1) state.copy(
                gaugeIllumination = ubyte(data, 0),
                lastUpdate = now
            ) else null

            ID_WHEEL_SPEEDS -> if (n >= 8) state.copy(
                wheelSpeedFL = (word(data, 0) - 10000) * 0.01,
                wheelSpeedFR = (word(data, 2) - 10000) * 0.01,
                wheelSpeedRL = (word(data, 4) - 10000) * 0.01,
                wheelSpeedRR = (word(data, 6) - 10000) * 0.01,
                lastUpdate = now
            ) else null

            ID_AWD_TORQUE -> if (n >= 7) {
                var left = bits(data, 0, 12).toDouble()
                var right = bits(data, 12, 12).toDouble()
                if (left >= 0xFFE) left = 0.0
                if (right >= 0xFFE) right = 0.0
                state.copy(
                    awdLeftTorque = left, awdRightTorque = right,
                    rduTempC = (ubyte(data, 3) - 40).toDouble(),
                    awdMaxTorque = (bits(data, 43, 13) - 1250).toDouble(),
                    lastUpdate = now
                )
            } else null

            ID_PTU_TEMP -> if (n >= 1) state.copy(
                ptuTempC = (ubyte(data, 0) - 40).toDouble(), lastUpdate = now
            ) else null

            // AWDmsg01 (0x1B0): DriveMode at Motorola bit 55, 4-bit — RS_HS.dbc confirmed
            // Also carries torque vectoring totals (requested/actual)
            ID_AWD_MSG -> if (n >= 7) state.copy(
                driveMode = DriveMode.fromInt(bits(data, 55, 4)),
                lastUpdate = now
            ) else null

            // ABSmsg04 (0x1C0): ESCMode at Motorola bit 13, 2-bit — RS_HS.dbc confirmed
            ID_ESC_ABS -> if (n >= 2) state.copy(
                escStatus = EscStatus.fromInt(bits(data, 13, 2)),
                lastUpdate = now
            ) else null

            ID_GEAR -> if (n >= 1) state.copy(
                gear = bits(data, 0, 4), lastUpdate = now
            ) else null

            ID_TORQUE -> if (n >= 6) state.copy(
                torqueAtTrans = (bits(data, 37, 11) - 500).toDouble(), lastUpdate = now
            ) else null

            ID_BATTERY -> if (n >= 1) state.copy(
                batteryVoltage = ubyte(data, 0) * 0.1, lastUpdate = now
            ) else null

            ID_FUEL_LEVEL -> if (n >= 1) state.copy(
                fuelLevelPct = ubyte(data, 0) * 0.392, lastUpdate = now
            ) else null

            ID_AMBIENT_TEMP -> if (n >= 1) state.copy(
                ambientTempC = (ubyte(data, 0) - 40).toDouble(), lastUpdate = now
            ) else null

            else -> null
        }
    }

    private fun ubyte(data: ByteArray, offset: Int): Int = data[offset].toInt() and 0xFF
    private fun word(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)
    private fun bits(data: ByteArray, startBit: Int, length: Int): Int {
        var value = 0
        for (i in 0 until length) {
            val byteIdx = (startBit + i) shr 3
            val bitIdx = 7 - ((startBit + i) and 7)
            if (byteIdx < data.size && ((data[byteIdx].toInt() shr bitIdx) and 1) == 1)
                value = value or (1 shl (length - 1 - i))
        }
        return value
    }
}
