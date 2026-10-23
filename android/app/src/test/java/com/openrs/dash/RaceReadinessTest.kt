package com.openrs.dash

import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.ThermalPredictor
import com.openrs.dash.ui.RtrLimits
import com.openrs.dash.ui.RtrState
import com.openrs.dash.ui.TempAvail
import com.openrs.dash.ui.whenDisconnected
import com.openrs.dash.ui.accentTriadFor
import com.openrs.dash.ui.legibleAccent
import com.openrs.dash.ui.raceReadiness
import com.openrs.dash.ui.rsPaintAccent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * raceReadiness — RACE READY must not show while overheating or on missing / stale data.
 * Limits are the STREET preset (UserPrefs oil/cool/rdu/ptuCritC); validity is tempAvailFor (60 s).
 */
class RaceReadinessTest {

    private val now = 1_700_000_000_000L
    private val street = RtrLimits(oilCritC = 130.0, coolCritC = 115.0, rduCritC = 100.0, ptuCritC = 110.0)

    private fun warm(
        oil: Double = 95.0, coolant: Double = 90.0, rdu: Double = 45.0, ptu: Double = 60.0,
        stampAgeMs: Long = 1_000L, extraStamps: Map<String, Long> = emptyMap(),
    ) = VehicleState(
        oilTempC = oil, coolantTempC = coolant, rduTempC = rdu, ptuTempC = ptu,
        fieldLastUpdateMs = listOf("oilTempC", "coolantTempC", "rduTempC", "ptuTempC")
            .associateWith { now - stampAgeMs } + extraStamps,
    )

    @Test
    fun `warm and fresh is ready`() {
        assertEquals(RtrState.READY, raceReadiness(warm(), street, now).state)
    }

    @Test
    fun `critical temperature blocks readiness and names the sensor`() {
        val r = raceReadiness(warm(oil = 131.0), street, now)
        assertEquals(RtrState.OVERHEAT, r.state)
        assertTrue(r.detail!!, r.detail!!.startsWith("Oil 131°C ≥ 130°C"))
        assertEquals(RtrState.OVERHEAT, raceReadiness(warm(ptu = 110.0), street, now).state)  // at crit = critical
    }

    @Test
    fun `missing required reading is not ready`() {
        val r = raceReadiness(warm(oil = -99.0), street, now)
        assertEquals(RtrState.NO_DATA, r.state)
        assertTrue(r.detail!!, "Oil no data" in r.detail!!)
    }

    @Test
    fun `stale reading blocks readiness, even a critical one`() {
        val stale = warm(oil = 140.0, stampAgeMs = 61_000L)
        val r = raceReadiness(stale, street, now)
        assertEquals(RtrState.NO_DATA, r.state)      // can't confirm a value 61 s old either way
        assertTrue(r.detail!!, "Oil stale" in r.detail!!)
    }

    @Test
    fun `optional sensor never reported is ignored but a stale one blocks`() {
        val noRdu = warm().copy(rduTempC = -99.0, fieldLastUpdateMs = warm().fieldLastUpdateMs - "rduTempC")
        assertEquals(RtrState.READY, raceReadiness(noRdu, street, now).state)

        val staleRdu = warm().copy(fieldLastUpdateMs = warm().fieldLastUpdateMs + ("rduTempC" to now - 61_000L))
        val r = raceReadiness(staleRdu, street, now)
        assertEquals(RtrState.NO_DATA, r.state)
        assertTrue(r.detail!!, "RDU stale" in r.detail!!)
    }

    @Test
    fun `cold but fresh is still warming`() {
        val r = raceReadiness(warm(oil = 60.0), street, now)
        assertEquals(RtrState.WARMING, r.state)
        assertTrue(r.detail!!, "Oil 60°C < 80°C" in r.detail!!)
    }

    @Test
    fun `legible accent swaps only the paints that vanish on the mode surface`() {
        val white = rsPaintAccent("white")
        val black = rsPaintAccent("black")
        val blue  = rsPaintAccent("cyan")
        assertNotEquals(white, legibleAccent(white, isDay = true))
        assertEquals(white, legibleAccent(white, isDay = false))
        assertNotEquals(black, legibleAccent(black, isDay = false))
        assertEquals(blue, legibleAccent(blue, isDay = true))
        // Providing the swapped colour must keep the structural mid/dim tiers
        assertEquals(accentTriadFor(white, true), accentTriadFor(legibleAccent(white, true), true))
        assertEquals(accentTriadFor(black, false), accentTriadFor(legibleAccent(black, false), false))
    }

    // ── Delayed / missed AWD updates (RDU polled on the ~38 s BCM/AWD loop; stale after 60 s) ──

    private fun withRduAge(ageMs: Long) =
        warm().copy(fieldLastUpdateMs = warm().fieldLastUpdateMs + ("rduTempC" to now - ageMs))

    @Test
    fun `late AWD decode inside the window keeps readiness, one missed cycle drops it`() {
        assertEquals(RtrState.READY, raceReadiness(withRduAge(45_000L), street, now).state)    // late, ok
        assertEquals(RtrState.READY, raceReadiness(withRduAge(60_000L), street, now).state)    // boundary: fresh
        val missed = raceReadiness(withRduAge(76_000L), street, now)                           // one poll missed
        assertEquals(RtrState.NO_DATA, missed.state)
        assertTrue(missed.detail!!, "RDU stale" in missed.detail!!)
    }

    @Test
    fun `readiness recovers as soon as a fresh AWD decode lands`() {
        val stale = withRduAge(90_000L)
        assertEquals(RtrState.NO_DATA, raceReadiness(stale, street, now).state)
        val recovered = stale.copy(fieldLastUpdateMs = stale.fieldLastUpdateMs + ("rduTempC" to now - 500L))
        assertEquals(RtrState.READY, raceReadiness(recovered, street, now).state)
    }

    @Test
    fun `missing mandatory coolant is not ready even when everything else is warm`() {
        val noCoolant = warm(coolant = -99.0).copy(fieldLastUpdateMs = warm().fieldLastUpdateMs - "coolantTempC")
        val r = raceReadiness(noCoolant, street, now)
        assertEquals(RtrState.NO_DATA, r.state)
        assertTrue(r.detail!!, "Coolant no data" in r.detail!!)
    }

    @Test
    fun `a fresh critical reading wins over a stale optional sensor`() {
        val r = raceReadiness(withRduAge(90_000L).copy(oilTempC = 131.0), street, now)
        assertEquals(RtrState.OVERHEAT, r.state)
    }

    @Test
    fun `detail follows the display unit including thresholds, comparisons stay in C`() {
        val f: (Double) -> String = { "%.0f°F".format(it * 9.0 / 5.0 + 32) }
        val cold = raceReadiness(warm(oil = 60.0), street, now, fmtTemp = f)
        assertTrue(cold.detail!!, "Oil 140°F < 176°F" in cold.detail!!)
        val hot = raceReadiness(warm(oil = 131.0), street, now, fmtTemp = f)
        assertTrue(hot.detail!!, "Oil 268°F ≥ 266°F" in hot.detail!!)
    }

    @Test
    fun `predictor marks a reached threshold as current, not a forecast`() {
        val p = ThermalPredictor(minSamples = 3)
        listOf(128.0, 130.0, 132.0).forEachIndexed { i, c -> p.recordSample("OIL", now + i * 1_000L, c) }
        assertTrue(p.predict("OIL", 130.0, 80.0)!!.reached)
        val q = ThermalPredictor(minSamples = 3)
        listOf(120.0, 121.0, 122.0).forEachIndexed { i, c -> q.recordSample("OIL", now + i * 1_000L, c) }
        assertEquals(false, q.predict("OIL", 130.0, 80.0)!!.reached)
    }

    @Test
    fun `held readings read as stale while disconnected`() {
        assertEquals(TempAvail.STALE, TempAvail.AVAILABLE.whenDisconnected(connected = false))
        assertEquals(TempAvail.AVAILABLE, TempAvail.AVAILABLE.whenDisconnected(connected = true))
        assertEquals(TempAvail.WARMING, TempAvail.WARMING.whenDisconnected(connected = false))  // no reading → no STALE tag
    }
}
