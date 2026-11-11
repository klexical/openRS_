package com.openrs.dash

import com.openrs.dash.data.VehicleState
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #199: a JVM method has at most 255 parameter slots, and VehicleState's generated `copy$default` (the receiver, every
 * constructor parameter, the default-mask ints and a marker) is the method that hits it first: one more field and the
 * class no longer compiles. Long and double take two slots each.
 *
 * Measured: 255 on staging before #199 (the cap), 222 after it.
 */
class VehicleStateSlotsTest {

    private fun copyDefaultSlots(): Int {
        val m = VehicleState::class.java.declaredMethods.single { it.name == "copy\$default" }
        return m.parameterTypes.sumOf { if (it == java.lang.Long.TYPE || it == java.lang.Double.TYPE) 2 else 1 as Int }
    }

    @Test fun `VehicleState keeps at least 16 of copy's 255 parameter slots free`() {
        val slots = copyDefaultSlots()
        println("VehicleState copy\$default uses $slots of 255 JVM parameter slots")
        assertTrue(
            "VehicleState.copy\$default uses $slots of 255 parameter slots; keep at least 16 free. Group fields that change " +
                "together into a nested value instead of adding flat ones (#199, see the slot-ceiling rule in can-decoding.md)",
            slots <= 255 - 16
        )
    }
}
