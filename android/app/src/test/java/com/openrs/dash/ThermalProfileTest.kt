package com.openrs.dash

import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.Level
import com.openrs.dash.ui.TempAvail
import com.openrs.dash.ui.ThermalCell
import com.openrs.dash.ui.ThermalLimits
import com.openrs.dash.ui.ThermalProfile
import com.openrs.dash.ui.ThermalReadinessEngine
import com.openrs.dash.ui.ThermalState
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.tempLevel
import com.openrs.dash.ui.validateThermalProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Thermal profiles — the built-ins keep their existing values, Custom is a validated copy, units
 * never drift, and one effective profile drives readiness and the gauge colour alike.
 */
class ThermalProfileTest {

    private val t0 = 1_700_000_000_000L

    @Test
    fun `built-in profiles keep the existing values`() {
        val s = UserPrefs(tempPreset = "street")
        assertEquals(120.0, s.oilWarnC, 0.0); assertEquals(130.0, s.oilCritC, 0.0)
        assertEquals(105.0, s.coolWarnC, 0.0); assertEquals(115.0, s.coolCritC, 0.0)
        assertEquals(90.0, s.rduWarnC, 0.0); assertEquals(100.0, s.rduCritC, 0.0)
        assertEquals(95.0, s.ptuWarnC, 0.0); assertEquals(110.0, s.ptuCritC, 0.0)
        assertEquals(65.0, s.intakeWarnC, 0.0); assertEquals(80.0, s.intakeCritC, 0.0)
        val r = UserPrefs(tempPreset = "race")
        assertEquals(100.0, r.oilWarnC, 0.0); assertEquals(85.0, r.rduCritC, 0.0); assertEquals(45.0, r.intakeWarnC, 0.0)
        val t = UserPrefs(tempPreset = "track")
        assertEquals(110.0, t.oilWarnC, 0.0); assertEquals(100.0, t.ptuCritC, 0.0)
        // Clutch limits and warm-up minimums are the same in all three
        for (p in listOf(s, r, t)) {
            assertEquals(90.0, p.clutchWarnC, 0.0); assertEquals(120.0, p.clutchCritC, 0.0)
            assertEquals(80.0, p.thermalProfile.oilMinC, 0.0); assertEquals(25.0, p.thermalProfile.clutchMinC, 0.0)
        }
    }

    @Test
    fun `custom is used only when selected and saved`() {
        val custom = ThermalProfile.STREET.copy(oilWarnC = 112.0)
        assertEquals(112.0, UserPrefs(tempPreset = "custom", customThermal = custom).oilWarnC, 0.0)
        assertEquals(120.0, UserPrefs(tempPreset = "street", customThermal = custom).oilWarnC, 0.0)
        assertEquals(120.0, UserPrefs(tempPreset = "custom", customThermal = null).oilWarnC, 0.0)   // nothing saved → STREET
        assertEquals("street", ThermalLimits.from(UserPrefs(tempPreset = "custom")).preset)
    }

    @Test
    fun `saved custom profile round-trips exactly`() {
        val custom = ThermalProfile.TRACK.copy(oilWarnC = (250.0 - 32) * 5 / 9, clutchCritC = 118.5)
        val (back, base) = ThermalProfile.fromJson(custom.toJson("track"))!!
        assertEquals(custom, back)
        assertEquals("track", base)
        assertNull(ThermalProfile.fromJson("{\"base\":\"street\"}"))   // incomplete → ignored, built-ins used
        assertNull(ThermalProfile.fromJson("not json"))
    }

    @Test
    fun `units never drift - untouched cells keep the exact stored value`() {
        val c = (250.0 - 32) * 5 / 9                       // typed as 250 °F once
        var cell = ThermalCell.of(c, "F")
        assertEquals("250", cell.text)
        repeat(5) { cell = cell.withUnit("C").withUnit("F") }  // switch units back and forth
        assertEquals(c, cell.resolvedC!!, 0.0)
        assertEquals("121.1", cell.withUnit("C").text)
        assertEquals(c, cell.withUnit("C").resolvedC!!, 0.0)  // shown rounded, saved exact
        // An edit is parsed in the unit it was typed in
        assertEquals(110.0, cell.copy(text = "230").resolvedC!!, 1e-9)
        assertEquals(110.0, ThermalCell.of(c, "C").copy(text = "110,0").resolvedC!!, 1e-9)   // comma decimal
        assertNull(cell.copy(text = "hot").resolvedC)
    }

    @Test
    fun `validation - numbers, order and the recovery margins`() {
        fun cells(p: ThermalProfile) = ThermalProfile.FIELDS.associate { it.key to it.get(p) as Double? }
        assertTrue(validateThermalProfile(cells(ThermalProfile.STREET), "C").isEmpty())
        assertTrue(validateThermalProfile(cells(ThermalProfile.RACE), "F").isEmpty())

        val notNumber = cells(ThermalProfile.STREET) + ("oilWarnC" to null)
        assertEquals("enter a number", validateThermalProfile(notNumber, "C")["oilWarnC"])

        val reversed = cells(ThermalProfile.STREET.copy(oilWarnC = 131.0))
        assertTrue(validateThermalProfile(reversed, "C").containsKey("oilCritC"))

        val tightCrit = cells(ThermalProfile.STREET.copy(oilCritC = 122.0))      // crit − warn = 2 °C: not more than the margin
        assertTrue(validateThermalProfile(tightCrit, "C").containsKey("oilCritC"))
        assertFalse(validateThermalProfile(cells(ThermalProfile.STREET.copy(oilCritC = 122.5)), "C").containsKey("oilCritC"))

        val tightWarn = cells(ThermalProfile.STREET.copy(clutchMinC = 87.0))      // warn − warm-up = 3 °C
        assertTrue(validateThermalProfile(tightWarn, "F").containsKey("clutchWarnC"))

        val unreachable = cells(ThermalProfile.STREET.copy(oilCritC = 250.0))     // oil decodes up to 205 °C
        assertTrue(validateThermalProfile(unreachable, "C").containsKey("oilCritC"))
    }

    @Test
    fun `a custom threshold moves the verdict and the gauge colour together`() {
        val custom = UserPrefs(tempPreset = "custom", customThermal = ThermalProfile.STREET.copy(oilWarnC = 100.0, oilCritC = 110.0))
        val street = UserPrefs(tempPreset = "street")
        val vs = VehicleState(isConnected = true, oilTempC = 105.0, coolantTempC = 90.0,
            fieldLastUpdateMs = mapOf("oilTempC" to t0, "coolantTempC" to t0))
        val engine = ThermalReadinessEngine()
        assertEquals(ThermalState.TEMPS_READY, engine.evaluate(vs, ThermalLimits.from(street), t0, 0).state)
        assertEquals(Level.NORMAL, tempLevel(105.0, street.oilWarnC, street.oilCritC, TempAvail.AVAILABLE))
        // Same reading, custom profile: verdict and gauge both say warning, at once (no hold across profiles)
        assertEquals(ThermalState.TEMPERATURE_HIGH, engine.evaluate(vs, ThermalLimits.from(custom), t0 + 1000, 1000).state)
        assertEquals(Level.WARN, tempLevel(105.0, custom.oilWarnC, custom.oilCritC, TempAvail.AVAILABLE))
        // Back to STREET: re-derived immediately, not a 10 s recovery
        assertEquals(ThermalState.TEMPS_READY, engine.evaluate(vs, ThermalLimits.from(street), t0 + 2000, 2000).state)
    }

    @Test
    fun `a warm-up hold does not complete across a changed minimum`() {
        val engine = ThermalReadinessEngine()
        fun vs(oil: Double, s: Int) = VehicleState(isConnected = true, oilTempC = oil, coolantTempC = 90.0,
            fieldLastUpdateMs = mapOf("oilTempC" to t0 + s * 1000L, "coolantTempC" to t0 + s * 1000L))
        val street = ThermalLimits.from(UserPrefs(tempPreset = "street"))
        val higherMin = ThermalLimits.from(UserPrefs(tempPreset = "custom", customThermal = ThermalProfile.STREET.copy(oilMinC = 90.0)))
        engine.evaluate(vs(79.0, 0), street, t0, 0)
        for (s in 1..9) engine.evaluate(vs(82.0, s), street, t0 + s * 1000L, s * 1000L)       // 8 s into the hold
        // New minimum 90 °C: 82 °C is cold under it — warming, and the old hold is gone
        assertEquals(ThermalState.ENGINE_WARMING, engine.evaluate(vs(82.0, 10), higherMin, t0 + 10_000, 10_000).state)
        assertEquals(ThermalState.ENGINE_WARMING, engine.evaluate(vs(82.0, 11), higherMin, t0 + 11_000, 11_000).state)
    }
}
