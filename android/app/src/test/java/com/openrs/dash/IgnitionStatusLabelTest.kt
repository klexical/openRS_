package com.openrs.dash

import com.openrs.dash.ui.ignitionStatusLabel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * #327 — DIAG's IGNITION readout (0x0C8 byte 2 bits 3–6) names every state with the names of one list that three sources
 * agree on (the community DBC's value table for this frame, Ford's BCM power-mode list, FORScan's DID 0xDD06 list). Code 1
 * read "Key In" (the list's "Key Recently Out"; on the car it appears only after switch-off) and 2, 3 and 6, the common
 * engine-off states, showed as bare numbers. Not received still reads "—"; a code the list does not name shows its number.
 */
class IgnitionStatusLabelTest {
    @Test fun `every code on the list reads the list's name`() {
        val expected = mapOf(
            0 to "Key Out", 1 to "Key Recently Out", 2 to "Key Approved", 3 to "Post Accessory", 4 to "Accessory",
            5 to "Post Ignition", 6 to "Ignition On", 7 to "Running", 9 to "Crank",
        )
        val wrong = expected.filter { (code, name) -> ignitionStatusLabel(code) != name }
            .map { (code, name) -> "$code: \"${ignitionStatusLabel(code)}\", want \"$name\"" }
        assertEquals("codes whose label is not the list's name", emptyList<String>(), wrong)
    }

    @Test fun `not received reads a dash`() {
        assertEquals("—", ignitionStatusLabel(-1))
    }

    @Test fun `a code the list does not name shows its number`() {
        assertEquals("8", ignitionStatusLabel(8))
        assertEquals("10", ignitionStatusLabel(10))
        assertEquals("15", ignitionStatusLabel(15))
    }
}
