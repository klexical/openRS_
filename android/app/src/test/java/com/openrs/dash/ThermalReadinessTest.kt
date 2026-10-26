package com.openrs.dash

import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.ThermalPredictor
import com.openrs.dash.data.VehicleState
import com.openrs.dash.demo.DemoScenario
import com.openrs.dash.demo.DemoSimulator
import com.openrs.dash.ui.HeatLevel
import com.openrs.dash.ui.TempAvail
import com.openrs.dash.ui.ThermalLimits
import com.openrs.dash.ui.ThermalReadinessEngine
import com.openrs.dash.ui.ThermalSensor
import com.openrs.dash.ui.ThermalState
import com.openrs.dash.ui.ThermalVerdict
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.CheckMark
import com.openrs.dash.ui.Level
import com.openrs.dash.ui.accentTriadFor
import com.openrs.dash.ui.checklist
import com.openrs.dash.ui.forecastBanner
import com.openrs.dash.ui.vehicleAdvisory
import com.openrs.dash.ui.legibleAccent
import com.openrs.dash.ui.reason
import com.openrs.dash.ui.rsPaintAccent
import com.openrs.dash.ui.shortReason
import com.openrs.dash.ui.tempAvail
import com.openrs.dash.ui.whenDisconnected
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Shared THERMAL readiness — DRIVE row, THERMAL card and the recorder marker all read this verdict.
 * The engine is driven with explicit clocks: wall `now` (freshness stamps, predictor samples) and
 * monotonic `mono` (holds), one evaluation per simulated second unless a test says otherwise.
 */
class ThermalReadinessTest {

    private val t0 = 1_700_000_000_000L
    private val street = ThermalLimits.from(UserPrefs(tempPreset = "street"))
    private val track = ThermalLimits.from(UserPrefs(tempPreset = "track"))
    private val fahrenheit = UserPrefs(tempUnit = "F")
    private val celsius = UserPrefs(tempUnit = "C")

    private lateinit var engine: ThermalReadinessEngine
    private var sec = 0L                     // simulated seconds since t0
    private val now get() = t0 + sec * 1000L

    @Before fun setUp() { engine = ThermalReadinessEngine(); sec = 0L }

    /** Readings stamped [ageMs] before `now` unless [ages] overrides per tag; -99 = never reported. */
    private fun car(
        oil: Double = 95.0, coolant: Double = 90.0, ptu: Double = 60.0, rdu: Double = 45.0,
        clutchL: Double = -99.0, clutchR: Double = -99.0,
        ageMs: Long = 500L, ages: Map<String, Long> = emptyMap(), connected: Boolean = true,
    ): VehicleState {
        val values = mapOf("oilTempC" to oil, "coolantTempC" to coolant, "ptuTempC" to ptu, "rduTempC" to rdu,
            "awdClutchTempL" to clutchL, "awdClutchTempR" to clutchR)
        val stamps = values.filter { it.value > -90 || it.key in ages }.mapValues { now - (ages[it.key] ?: ageMs) }
        return VehicleState(
            isConnected = connected, oilTempC = oil, coolantTempC = coolant, ptuTempC = ptu, rduTempC = rdu,
            awdClutchTempL = clutchL, awdClutchTempR = clutchR, fieldLastUpdateMs = stamps,
        )
    }

    private fun eval(vs: VehicleState, limits: ThermalLimits = street): ThermalVerdict =
        engine.evaluate(vs, limits, now, sec * 1000L)

    /** One evaluation per second for [n] seconds; returns the last verdict. */
    private fun run(n: Int, limits: ThermalLimits = street, vs: () -> VehicleState): ThermalVerdict {
        var v: ThermalVerdict? = null
        repeat(n) { sec++; v = eval(vs(), limits) }
        return v!!
    }

    // ── TEMPS READY ─────────────────────────────────────────────────────────

    @Test
    fun `warm on the first fresh sample is ready at once and names the preset`() {
        val v = eval(car())
        assertEquals(ThermalState.TEMPS_READY, v.state)
        assertTrue(v.isReady)
        assertTrue(v.reason(fahrenheit), v.reason(fahrenheit).startsWith("STREET limits"))
        assertEquals("TRACK limits", eval(car(), track).shortReason(fahrenheit))
    }

    // ── Warm-up ─────────────────────────────────────────────────────────────

    @Test
    fun `normal warm-up needs minimum plus 1 C held 10 s and the reason names that target`() {
        // Small steps: a 20 °C jump in seconds would (correctly) trip the existing heat forecast
        assertEquals(ThermalState.ENGINE_WARMING, eval(car(oil = 79.6)).state)
        val atMin = run(3) { car(oil = 80.4) }                     // at the minimum, under min + 1
        assertEquals(ThermalState.ENGINE_WARMING, atMin.state)
        assertTrue(atMin.reason(celsius), "Oil 80°C · ready at 81°C" in atMin.reason(celsius))
        val counting = run(9) { car(oil = 81.0) }                  // 0 … 8 s above the target
        assertEquals(ThermalState.ENGINE_WARMING, counting.state)
        assertTrue(counting.reason(celsius), "stabilising above 81°C, 8\u00A0of\u00A010\u00A0s" in counting.reason(celsius))
        assertEquals(ThermalState.ENGINE_WARMING, run(1) { car(oil = 81.0) }.state)   // 9 s
        assertEquals(ThermalState.TEMPS_READY, run(1) { car(oil = 81.0) }.state)      // 10 s
    }

    @Test
    fun `a delayed evaluation never counts as several passing seconds`() {
        eval(car(oil = 79.6))
        sec++; eval(car(oil = 82.0))                               // hold starts
        sec += 9; val late = eval(car(oil = 82.0))                 // one evaluation 9 s later: hold restarts
        assertEquals(ThermalState.ENGINE_WARMING, late.state)
        assertEquals(ThermalState.ENGINE_WARMING, run(9) { car(oil = 82.0) }.state)
        assertEquals(ThermalState.TEMPS_READY, run(1) { car(oil = 82.0) }.state)
    }

    @Test
    fun `dropping below a warm-up minimum ends TEMPS READY on the next evaluation`() {
        assertEquals(ThermalState.TEMPS_READY, eval(car()).state)
        sec++
        val v = eval(car(oil = 79.0))
        assertEquals(ThermalState.ENGINE_WARMING, v.state)
        assertTrue(v.reason(celsius), "Oil 79°C · ready at 81°C" in v.reason(celsius))
        // Polled sensor too — no margin, no delay
        engine.reset(); sec++; eval(car()); sec++
        assertEquals(ThermalState.DRIVETRAIN_WARMING, eval(car(rdu = 29.0)).state)
    }

    @Test
    fun `a polled drivetrain reading warms up on one fresh poll`() {
        assertEquals(ThermalState.DRIVETRAIN_WARMING, eval(car(rdu = 25.0)).state)
        sec++
        assertEquals(ThermalState.DRIVETRAIN_WARMING, eval(car(rdu = 30.5)).state)     // under min + 1
        sec++
        assertEquals(ThermalState.TEMPS_READY, eval(car(rdu = 31.0)).state)
    }

    // ── Heat ────────────────────────────────────────────────────────────────

    @Test
    fun `worsening is immediate and a fresh warn blocks ready`() {
        assertEquals(ThermalState.TEMPS_READY, eval(car()).state)
        sec++
        val high = eval(car(oil = 121.0))
        assertEquals(ThermalState.TEMPERATURE_HIGH, high.state)
        assertFalse(high.isReady)
        sec++
        assertEquals(ThermalState.OVERHEATING, eval(car(oil = 130.0)).state)            // at crit = critical
    }

    @Test
    fun `high but cooling holds until 2 C under warn for 10 s and says what it waits for`() {
        eval(car(oil = 125.0))
        val falling = run(12) { car(oil = 125.0 - sec * 0.4) }    // 120.2 °C after 12 s, cooling 24 °C/min
        assertEquals(ThermalState.TEMPERATURE_HIGH, falling.state)
        assertTrue(falling.reason(celsius), "cooling" in falling.reason(celsius))
        val margin = run(2) { car(oil = 119.0) }                   // under warn, not under warn − 2
        assertEquals(ThermalState.TEMPERATURE_HIGH, margin.state)
        assertTrue(margin.reading(ThermalSensor.OIL)!!.heatRecovering)
        assertTrue(margin.reason(celsius), "Oil 119°C · recovering, needs under 118°C for\u00A010\u00A0s" in margin.reason(celsius))
        assertFalse(margin.reason(celsius), "≥" in margin.reason(celsius))           // no comparison that's no longer true
        val holding = run(5) { car(oil = 117.0) }
        assertTrue(holding.reason(celsius), "under 118°C for 4\u00A0of\u00A010\u00A0s" in holding.reason(celsius))
        assertEquals(ThermalState.TEMPERATURE_HIGH, run(5) { car(oil = 117.0) }.state)   // 9 s
        assertEquals(ThermalState.TEMPS_READY, run(1) { car(oil = 117.0) }.state)        // 10 s
    }

    @Test
    fun `overheating steps down to temperature high only after crit minus 2 C for 10 s`() {
        eval(car(oil = 132.0))
        assertEquals(ThermalState.OVERHEATING, run(5) { car(oil = 129.0) }.state)        // under crit, inside margin
        assertEquals(ThermalState.OVERHEATING, run(10) { car(oil = 127.0) }.state)       // 0 … 9 s
        assertEquals(ThermalState.TEMPERATURE_HIGH, run(1) { car(oil = 127.0) }.state)   // still ≥ warn
        // Any new rise above the limit is immediate again
        sec++
        assertEquals(ThermalState.OVERHEATING, eval(car(oil = 131.0)).state)
    }

    @Test
    fun `predictive warning gives HEAT RISING below warn, then holds 10 s after it clears`() {
        // Coolant climbing 6 °C/min from 95 °C: STREET warn 105, crit 115 → crit in < 5 min
        val rising = run(30) { car(coolant = 95.0 + sec * 0.1) }
        assertEquals(ThermalState.HEAT_RISING, rising.state)
        val f = rising.reading(ThermalSensor.COOLANT)!!.forecast!!
        assertTrue(f.minutesToCrit <= 5.0)
        assertTrue(rising.reason(celsius), "crit 115°C in" in rising.reason(celsius))
        // Flat for the predictor's 120 s window: the forecast clears, held 10 s
        val flat = run(150) { car(coolant = 98.0) }
        assertEquals(ThermalState.TEMPS_READY, flat.state)
    }

    @Test
    fun `a fresh warning stays the headline while another reading is missing or stale`() {
        val noCoolant = eval(car(oil = 125.0, coolant = -99.0))
        assertEquals(ThermalState.TEMPERATURE_HIGH, noCoolant.state)
        assertTrue(noCoolant.reason(celsius), "+ Coolant no data" in noCoolant.reason(celsius))
        sec++
        val staleRdu = eval(car(oil = 131.0, ages = mapOf("rduTempC" to 120_000L)))
        assertEquals(ThermalState.OVERHEATING, staleRdu.state)
        assertTrue(staleRdu.reason(celsius), "RDU stale" in staleRdu.reason(celsius))
        assertEquals(1, staleRdu.extraCount)
    }

    @Test
    fun `missing data still blocks ready once the warning clears`() {
        val v = eval(car(coolant = -99.0))
        assertEquals(ThermalState.CHECKING, v.state)
        assertTrue(v.reason(celsius), "Coolant no data" in v.reason(celsius))
    }

    @Test
    fun `a warning on a reading that goes stale is dropped at once, not held as current`() {
        eval(car(oil = 125.0))
        sec++
        val stale = eval(car(oil = 125.0, ages = mapOf("oilTempC" to 61_000L)))   // CAN oil: 60 s window
        assertEquals(ThermalState.CHECKING, stale.state)
        assertEquals(HeatLevel.NONE, stale.reading(ThermalSensor.OIL)!!.heat)
        assertTrue(stale.reason(celsius), "Oil stale" in stale.reason(celsius))
        // Recovery mid-hold, then stale: same — no "recovering" warning from a stale value
        sec++; eval(car(oil = 125.0))
        sec++; eval(car(oil = 117.0))
        sec++
        assertEquals(ThermalState.CHECKING, eval(car(oil = 117.0, ages = mapOf("oilTempC" to 61_000L))).state)
    }

    @Test
    fun `invalid reading of a reported optional sensor blocks, never-reported is ignored`() {
        assertEquals(ThermalState.TEMPS_READY, eval(car(rdu = -99.0)).state)            // never reported
        sec++
        val invalid = eval(car(rdu = -99.0, ages = mapOf("rduTempC" to 5_000L)))       // stamped, but no value
        assertEquals(ThermalState.CHECKING, invalid.state)
        assertTrue(invalid.reason(celsius), "RDU unavailable" in invalid.reason(celsius))
    }

    // ── AWD clutches: the fixed card limits (90 / 120 °C), polled freshness ──

    @Test
    fun `clutch warn and crit use the fixed card limits and block ready`() {
        assertEquals(ThermalState.TEMPS_READY, eval(car(clutchL = 89.0, clutchR = 60.0)).state)
        sec++
        val warn = eval(car(clutchL = 95.0, clutchR = 60.0))
        assertEquals(ThermalState.TEMPERATURE_HIGH, warn.state)
        assertTrue(warn.reason(celsius), "Clutch L 95°C ≥ fixed warn 90°C" in warn.reason(celsius))
        sec++
        assertEquals(ThermalState.OVERHEATING, eval(car(clutchL = 121.0, clutchR = 60.0)).state)
        // Same limits on every preset
        sec++
        assertEquals(ThermalState.OVERHEATING, eval(car(clutchL = 121.0, clutchR = 60.0), track).state)
    }

    @Test
    fun `clutch recovery is held like the other heat states`() {
        eval(car(clutchL = 122.0, clutchR = 60.0))
        assertEquals(ThermalState.OVERHEATING, run(3) { car(clutchL = 119.0, clutchR = 60.0) }.state)   // inside the margin
        assertEquals(ThermalState.OVERHEATING, run(10) { car(clutchL = 100.0, clutchR = 60.0) }.state)  // 0 … 9 s
        assertEquals(ThermalState.TEMPERATURE_HIGH, run(1) { car(clutchL = 100.0, clutchR = 60.0) }.state)
        assertEquals(ThermalState.TEMPERATURE_HIGH, run(10) { car(clutchL = 85.0, clutchR = 60.0) }.state)  // 0 … 9 s under 88
        assertEquals(ThermalState.TEMPS_READY, run(1) { car(clutchL = 85.0, clutchR = 60.0) }.state)
    }

    @Test
    fun `stale clutch drops its warning and never-reported clutches stay excluded`() {
        eval(car(clutchL = 95.0, clutchR = 60.0))
        sec++
        val stale = eval(car(clutchL = 95.0, clutchR = 60.0, ages = mapOf("awdClutchTempL" to 91_000L)))
        assertEquals(ThermalState.CHECKING, stale.state)
        assertEquals(HeatLevel.NONE, stale.reading(ThermalSensor.CLUTCH_L)!!.heat)
        sec++
        assertEquals(ThermalState.TEMPS_READY, eval(car()).state)                      // clutches never reported
    }

    @Test
    fun `trans oil shares the AWD poll window - one missed poll fresh, two stale, then recovers`() {
        val cycle = 37_000L
        fun trans(age: Long) = car().let { it.copy(transOilTempC = 60.0, fieldLastUpdateMs = it.fieldLastUpdateMs + ("transOilTempC" to now - age)) }
        assertEquals(TempAvail.AVAILABLE, trans(2 * cycle).tempAvail(60.0, "transOilTempC", now))
        assertEquals(TempAvail.STALE, trans(3 * cycle).tempAvail(60.0, "transOilTempC", now))
        assertEquals(TempAvail.AVAILABLE, trans(300L).tempAvail(60.0, "transOilTempC", now))
        // Not a readiness input: a stale trans oil doesn't block TEMPS READY
        assertEquals(ThermalState.TEMPS_READY, eval(trans(3 * cycle)).state)
    }

    // ── Consistent wording ──────────────────────────────────────────────────

    @Test
    fun `checklist never certifies an unavailable reading below its limit`() {
        val v = eval(car(clutchL = 50.0, clutchR = 52.0, ages = mapOf("rduTempC" to 100_000L)))
        val rows = v.checklist(celsius).associateBy { it.label }
        val preset = rows.getValue("Below STREET warn")
        assertEquals(CheckMark.WAIT, preset.mark)
        assertTrue(preset.value, "others below · RDU stale" == preset.value)
        assertEquals(CheckMark.OK, rows.getValue("Clutches below 90°C").mark)
        assertEquals(CheckMark.WAIT, rows.getValue("No heat forecast").mark)
    }

    @Test
    fun `VEHICLE summary gets the verdict's advisory - forecast, recovery hold, missing data`() {
        eval(car(oil = 125.0)); sec++
        val holding = eval(car(oil = 117.0))                      // cards normal, verdict still held
        assertEquals("TEMPERATURE HIGH" to Level.WARN, holding.vehicleAdvisory())
        engine.reset(); sec++
        assertEquals("STALE DATA", eval(car(ages = mapOf("rduTempC" to 100_000L))).vehicleAdvisory()!!.first)
        engine.reset(); sec++
        assertNull(eval(car()).vehicleAdvisory())                  // TEMPS READY: nothing to add
    }

    @Test
    fun `banner names reached readings apart from genuine forecasts`() {
        fun pred(label: String, c: Double, ttc: Double?) = ThermalPredictor.SensorPrediction(label, c, 5.0, ttc, 110.0)
        val b = forecastBanner(listOf(pred("RDU", 100.0, 2.0), pred("OIL", 126.0, 0.0), pred("PTU", 112.0, 0.0)))!!
        assertEquals("OIL", b.lead.sensorLabel)
        assertEquals(listOf("PTU"), b.alsoReached)
        assertEquals(listOf("RDU"), b.alsoForecast)
    }

    @Test
    fun `DRIVE shows the same target text as THERMAL`() {
        val v = eval(car(oil = 60.0))
        assertEquals("oil 140 → 177.8°F", v.shortReason(fahrenheit))
        assertTrue(v.reason(fahrenheit), "ready at 177.8°F" in v.reason(fahrenheit))
        engine.reset(); sec++
        assertEquals("rdu → 87.8°F", eval(car(rdu = 20.0)).shortReason(fahrenheit))
        assertEquals("rdu → 31°C", eval(car(rdu = 20.0)).shortReason(celsius))
    }

    // ── Presets / resets ────────────────────────────────────────────────────

    @Test
    fun `preset change re-derives heat at once in both directions`() {
        val oil115 = car(oil = 115.0)                               // TRACK warn 110, STREET warn 120
        assertEquals(ThermalState.TEMPERATURE_HIGH, eval(oil115, track).state)
        sec++
        assertEquals(ThermalState.TEMPS_READY, eval(oil115, street).state)             // not "recovering"
        sec++
        assertEquals(ThermalState.TEMPERATURE_HIGH, eval(oil115, track).state)
        assertTrue(eval(oil115, track).reason(celsius).contains("TRACK warn 110°C"))
    }

    @Test
    fun `offline clears holds and predictor history`() {
        run(30) { car(coolant = 95.0 + sec * 0.1) }                // HEAT RISING
        eval(car(oil = 125.0))                                     // + TEMPERATURE HIGH latched
        sec++
        assertEquals(ThermalState.NO_LIVE_DATA, eval(car(connected = false)).state)
        sec++
        val back = eval(car(oil = 100.0, coolant = 98.0))
        assertEquals(ThermalState.TEMPS_READY, back.state)         // no hold, no forecast from the old history
        assertNull(back.reading(ThermalSensor.COOLANT)!!.forecast)
        assertNull(back.reading(ThermalSensor.COOLANT)!!.trend)
    }

    @Test
    fun `reset drops a warm-up hold and the next live state is computed from scratch`() {
        eval(car(oil = 60.0))
        run(5) { car(oil = 82.0) }
        engine.reset()
        sec++
        assertEquals(ThermalState.TEMPS_READY, eval(car(oil = 82.0)).state)
    }

    // ── Freshness (AWD-polled RDU / clutches: 90 s; CAN: 60 s) ──────────────

    @Test
    fun `one missed AWD poll stays fresh, two go stale, a fresh poll recovers`() {
        val cycle = 37_000L
        assertEquals(ThermalState.TEMPS_READY, eval(car(clutchL = 40.0, ages = mapOf("rduTempC" to cycle))).state)
        sec++
        assertEquals(ThermalState.TEMPS_READY, eval(car(ages = mapOf("rduTempC" to 2 * cycle))).state)   // one missed
        sec++
        val two = eval(car(ages = mapOf("rduTempC" to 3 * cycle)))                                        // two missed
        assertEquals(ThermalState.CHECKING, two.state)
        assertTrue(two.reason(celsius), "RDU stale, last 111\u00A0s ago" in two.reason(celsius))
        sec++
        assertEquals(ThermalState.TEMPS_READY, eval(car(ages = mapOf("rduTempC" to 200L))).state)
    }

    @Test
    fun `the evaluator tick never refreshes a measurement timestamp`() {
        val stampedAt = now - 30_000L
        val frozen = car().let { it.copy(fieldLastUpdateMs = it.fieldLastUpdateMs + ("rduTempC" to stampedAt)) }
        // Same frame re-evaluated every second; CAN stamps kept current by hand, RDU's not touched
        var last: ThermalVerdict? = null
        for (i in 1..70) {
            sec++
            val vs = frozen.copy(fieldLastUpdateMs = frozen.fieldLastUpdateMs.mapValues { (k, v) -> if (k == "rduTempC") v else now })
            last = eval(vs)
            if (now - stampedAt <= 90_000L) assertEquals("t+${now - stampedAt}", ThermalState.TEMPS_READY, last.state)
        }
        assertEquals(ThermalState.CHECKING, last!!.state)
        assertEquals(stampedAt, frozen.fieldLastUpdateMs["rduTempC"])
    }

    @Test
    fun `availability policy is shared - RDU and clutches 90 s, CAN temps 60 s`() {
        val vs = car(ages = mapOf("rduTempC" to 75_000L, "ptuTempC" to 75_000L))
        assertEquals(TempAvail.AVAILABLE, vs.tempAvail(vs.rduTempC, "rduTempC", now))
        assertEquals(TempAvail.STALE, vs.tempAvail(vs.ptuTempC, "ptuTempC", now))
        assertEquals(TempAvail.STALE, vs.copy(isConnected = false).tempAvail(vs.oilTempC, "oilTempC", now))
    }

    // ── Units ───────────────────────────────────────────────────────────────

    @Test
    fun `temperatures, differences and rates convert separately`() {
        assertEquals("248", fahrenheit.displayTemp(120.0))
        assertEquals("16", fahrenheit.displayTempDelta(8.9))      // Δ × 1.8, no +32
        assertEquals("9", celsius.displayTempDelta(8.9))
        assertEquals("7.2", fahrenheit.displayTempRate(4.0))
        val v = eval(car(oil = 121.1))
        assertTrue(v.reason(fahrenheit), "Oil 250°F ≥ STREET warn 248°F" in v.reason(fahrenheit))
        assertTrue(v.reason(fahrenheit), "16°F below crit" in v.reason(fahrenheit))
        assertEquals("oil 250°F", v.shortReason(fahrenheit))
        // Bounds that aren't whole in °F keep a decimal; the reading keeps enough to stay on its side of it
        eval(car(oil = 125.0)); sec++
        val atBound = eval(car(oil = 118.0))                       // 244.4 °F = warn − 2 °C: not under it yet
        assertTrue(atBound.reason(fahrenheit), "Oil 244.4°F · recovering, needs under 244.4°F" in atBound.reason(fahrenheit))
        sec++
        val under = eval(car(oil = 117.9))
        assertTrue(under.reason(fahrenheit), "Oil 244°F · under 244.4°F" in under.reason(fahrenheit))
    }

    // ── Demo walk-through (the emulator evidence scenario) ──────────────────

    @Test
    fun `COOLDOWN demo walks through cooling, forecast, missing data, stale removal and warm-up loss`() {
        var vs = VehicleState(isConnected = true)
        val seen = mutableListOf<ThermalState>()
        var ms = 0L
        var withCoolantGap = false
        while (ms <= 285_000L) {
            // DemoMode's tick: 40 ms frames, stamps every 500 ms, readiness once a second
            vs = DemoSimulator.apply(vs, DemoScenario.COOLDOWN, ms, DriveMode.NORMAL, EscStatus.ON, t0 + ms, ms % 500 == 0L)
            if (ms % 1000 == 0L) {
                val v = engine.evaluate(vs, street, t0 + ms, ms)
                if (seen.lastOrNull() != v.state) seen += v.state
                if (v.state == ThermalState.TEMPERATURE_HIGH && v.gaps.any { it.sensor == ThermalSensor.COOLANT }) withCoolantGap = true
            }
            ms += 40
        }
        assertEquals(listOf(
            ThermalState.TEMPERATURE_HIGH,   // oil 126 °C cooling, then its recovery hold
            ThermalState.TEMPS_READY,
            ThermalState.HEAT_RISING,        // RDU climbing below warn
            ThermalState.TEMPERATURE_HIGH,   // RDU ≥ warn — coolant invalid meanwhile
            ThermalState.CHECKING,           // RDU replies stopped 90 s ago: warning dropped
            ThermalState.TEMPS_READY,        // fresh RDU poll
            ThermalState.ENGINE_WARMING,     // oil below its 80 °C minimum
            ThermalState.TEMPERATURE_HIGH,   // clutches past their fixed 90 °C warn
            ThermalState.OVERHEATING,        // … and 120 °C crit
            ThermalState.TEMPERATURE_HIGH,   // cooling: crit cleared after its 10 s hold, warn still held
            ThermalState.ENGINE_WARMING,
        ), seen)
        assertTrue(withCoolantGap)
    }

    // ── Kept from the old RaceReadiness tests ───────────────────────────────

    @Test
    fun `predictor marks a reached threshold as current, not a forecast`() {
        val p = ThermalPredictor(minSamples = 3)
        listOf(128.0, 130.0, 132.0).forEachIndexed { i, c -> p.recordSample("OIL", t0 + i * 1_000L, c) }
        assertTrue(p.predict("OIL", 130.0, 80.0)!!.reached)
        val q = ThermalPredictor(minSamples = 3)
        listOf(120.0, 121.0, 122.0).forEachIndexed { i, c -> q.recordSample("OIL", t0 + i * 1_000L, c) }
        assertEquals(false, q.predict("OIL", 130.0, 80.0)!!.reached)
    }

    @Test
    fun `held readings read as stale while disconnected`() {
        assertEquals(TempAvail.STALE, TempAvail.AVAILABLE.whenDisconnected(connected = false))
        assertEquals(TempAvail.AVAILABLE, TempAvail.AVAILABLE.whenDisconnected(connected = true))
        assertEquals(TempAvail.WARMING, TempAvail.WARMING.whenDisconnected(connected = false))
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
        assertEquals(accentTriadFor(white, true), accentTriadFor(legibleAccent(white, true), true))
        assertEquals(accentTriadFor(black, false), accentTriadFor(legibleAccent(black, false), false))
    }
}
