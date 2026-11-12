package com.openrs.dash

import com.openrs.dash.ui.ignitionStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Test

class IgnitionStatusLabelTest {

    @Test
    fun `names every Ford power mode`() {
        val expected = mapOf(
            0 to "Key Out",
            1 to "Key Recently Out",
            2 to "Key Approved",
            3 to "Post Accessory",
            4 to "Accessory",
            5 to "Post Ignition",
            6 to "Ignition On",
            7 to "Running",
            9 to "Crank",
        )
        expected.forEach { (code, label) ->
            assertEquals("code $code", label, ignitionStatusLabel(code))
        }
    }

    @Test
    fun `keeps the not-received placeholder`() {
        assertEquals("—", ignitionStatusLabel(-1))
    }

    @Test
    fun `falls back to the raw code for unlisted values`() {
        assertEquals("8", ignitionStatusLabel(8))
        assertEquals("10", ignitionStatusLabel(10))
    }
}
