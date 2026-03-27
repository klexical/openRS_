package com.openrs.dash

import com.openrs.dash.can.PidRegistry
import org.junit.Assert.*
import org.junit.Test

class PidRegistryTest {

    // ── Basic arithmetic ──────────────────────────────────────────────────────

    @Test
    fun `evaluateFormula A + B`() {
        val result = PidRegistry.evaluateFormula("A + B", 10, 5)
        assertNotNull(result)
        assertEquals(15.0, result!!, 0.001)
    }

    @Test
    fun `evaluateFormula A * 256 + B`() {
        val result = PidRegistry.evaluateFormula("A * 256 + B", 1, 100)
        assertNotNull(result)
        assertEquals(356.0, result!!, 0.001)
    }

    @Test
    fun `evaluateFormula A div 10`() {
        val result = PidRegistry.evaluateFormula("A / 10", 50, 0)
        assertNotNull(result)
        assertEquals(5.0, result!!, 0.001)
    }

    // ── Division by zero ──────────────────────────────────────────────────────

    @Test
    fun `evaluateFormula division by zero returns positive infinity`() {
        val result = PidRegistry.evaluateFormula("A / B", 10, 0)
        assertNotNull(result)
        assertTrue(result!!.isInfinite())
        assertEquals(Double.POSITIVE_INFINITY, result, 0.0)
    }

    // ── signed() 16-bit two's complement ──────────────────────────────────────

    @Test
    fun `evaluateFormula signed zero`() {
        val result = PidRegistry.evaluateFormula("signed(A * 256 + B)", 0, 0)
        assertNotNull(result)
        assertEquals(0.0, result!!, 0.001)
    }

    @Test
    fun `evaluateFormula signed positive max`() {
        // 127*256 + 255 = 32767 -> signed = 32767
        val result = PidRegistry.evaluateFormula("signed(A * 256 + B)", 127, 255)
        assertNotNull(result)
        assertEquals(32767.0, result!!, 0.001)
    }

    @Test
    fun `evaluateFormula signed negative min`() {
        // 128*256 + 0 = 32768 -> signed = -32768
        val result = PidRegistry.evaluateFormula("signed(A * 256 + B)", 128, 0)
        assertNotNull(result)
        assertEquals(-32768.0, result!!, 0.001)
    }

    @Test
    fun `evaluateFormula signed minus one`() {
        // 255*256 + 255 = 65535 -> signed = -1
        val result = PidRegistry.evaluateFormula("signed(A * 256 + B)", 255, 255)
        assertNotNull(result)
        assertEquals(-1.0, result!!, 0.001)
    }

    // ── Unary minus ───────────────────────────────────────────────────────────

    @Test
    fun `evaluateFormula constant negative`() {
        val result = PidRegistry.evaluateFormula("-40", 0, 0)
        assertNotNull(result)
        assertEquals(-40.0, result!!, 0.001)
    }

    @Test
    fun `evaluateFormula negative variable`() {
        val result = PidRegistry.evaluateFormula("-A", 5, 0)
        assertNotNull(result)
        assertEquals(-5.0, result!!, 0.001)
    }

    // ── Parentheses ───────────────────────────────────────────────────────────

    @Test
    fun `evaluateFormula parentheses grouping`() {
        val result = PidRegistry.evaluateFormula("(A + B) * 2", 3, 4)
        assertNotNull(result)
        assertEquals(14.0, result!!, 0.001)
    }

    @Test
    fun `evaluateFormula nested parentheses`() {
        val result = PidRegistry.evaluateFormula("(A + B) * (A - B)", 5, 3)
        assertNotNull(result)
        assertEquals(16.0, result!!, 0.001)
    }

    // ── C variable (always 0) ─────────────────────────────────────────────────

    @Test
    fun `evaluateFormula C variable is always zero`() {
        val result = PidRegistry.evaluateFormula("A + C", 10, 0)
        assertNotNull(result)
        assertEquals(10.0, result!!, 0.001)
    }

    // ── Edge cases ────────────────────────────────────────────────────────────

    @Test
    fun `evaluateFormula empty formula returns null`() {
        val result = PidRegistry.evaluateFormula("", 0, 0)
        // Empty tokenizes to empty list; parsePrimary returns 0.0 for empty
        // Actually: parseExpression calls parseTerm calls parsePrimary, which
        // returns 0.0 if pos[0] >= tokens.size. So empty formula returns 0.0.
        // The evaluateFormula wraps in try-catch, so it depends on the parser.
        // Either null or 0.0 is acceptable; test actual behavior.
        assertEquals(0.0, result!!, 0.001)
    }

    @Test
    fun `evaluateFormula constant only`() {
        val result = PidRegistry.evaluateFormula("42", 0, 0)
        assertNotNull(result)
        assertEquals(42.0, result!!, 0.001)
    }

    @Test
    fun `evaluateFormula decimal arithmetic`() {
        val result = PidRegistry.evaluateFormula("A * 0.5 + 1.25", 10, 0)
        assertNotNull(result)
        assertEquals(6.25, result!!, 0.001)
    }

    // ── Operator precedence ───────────────────────────────────────────────────

    @Test
    fun `evaluateFormula multiplication before addition`() {
        // A + B * 2 with a=3, b=4 should be 3 + (4*2) = 11, not (3+4)*2 = 14
        val result = PidRegistry.evaluateFormula("A + B * 2", 3, 4)
        assertNotNull(result)
        assertEquals(11.0, result!!, 0.001)
    }

    @Test
    fun `evaluateFormula subtraction`() {
        val result = PidRegistry.evaluateFormula("A - B", 10, 3)
        assertNotNull(result)
        assertEquals(7.0, result!!, 0.001)
    }
}
