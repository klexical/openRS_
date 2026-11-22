package com.openrs.dash

import com.openrs.dash.data.DashCell
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.KnockEventEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.resolveValue
import com.openrs.dash.demo.DemoScenario
import com.openrs.dash.demo.TripPreview
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.DriveExportBuilder
import com.openrs.dash.service.HudOverlayService
import com.openrs.dash.ui.Level
import com.openrs.dash.ui.TempAvail
import com.openrs.dash.ui.TyreThreshold
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.UserPrefsStore
import com.openrs.dash.ui.anim.formatWheelSpeed
import com.openrs.dash.ui.awdDetailRows
import com.openrs.dash.ui.baroText
import com.openrs.dash.ui.dashCellUnit
import com.openrs.dash.ui.densityAltitudeText
import com.openrs.dash.ui.economyCells
import com.openrs.dash.ui.hpFuelText
import com.openrs.dash.ui.lpFuelText
import com.openrs.dash.ui.mixturePanel
import com.openrs.dash.ui.perfPeakBoostText
import com.openrs.dash.ui.tempLevel
import com.openrs.dash.ui.tipText
import com.openrs.dash.ui.trip.fixReadout
import com.openrs.dash.ui.trip.wheelSpeedCells
import com.openrs.dash.ui.units.Region
import com.openrs.dash.ui.units.UnitIds
import com.openrs.dash.ui.units.altitudeM
import com.openrs.dash.ui.units.boostGauge
import com.openrs.dash.ui.units.speedDelta
import com.openrs.dash.ui.units.temp
import com.openrs.dash.ui.units.tempDeltaText
import com.openrs.dash.ui.units.withUnits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * #464, one test per audit row on the production formatter or page path: a metric preset shows bar / hPa abs / m / °C /
 * L / Nm where chosen, US shows psi / inHg abs / ft / US gal / lb·ft, and what an imperial user saw stays where the unit
 * did not change. Warnings compare the stored unit; exports never read the units.
 */
@RunWith(RobolectricTestRunner::class)
class UnitsDisplayTest {

    private val eu = UserPrefs().withUnits(Region.EUROPE.units!!)
    private val us = UserPrefs().withUnits(Region.US.units!!)
    private val canada = UserPrefs().withUnits(Region.CANADA.units!!)
    private val uk = UserPrefs().withUnits(Region.UK.units!!)

    @After fun resetPrefs() = UserPrefsStore.resetForTest()

    // Row 1: POWER HP FUEL (stored psi) — the owner report: metric still showed PSI
    @Test fun `row 1 - HP fuel follows the engine-pressure unit`() {
        assertEquals("199.9 bar", hpFuelText(eu, 2900.0))
        assertEquals("2900 psi", hpFuelText(us, 2900.0))
        assertEquals("19.99 MPa", hpFuelText(eu.copy(engineUnit = UnitIds.MPA), 2900.0))
    }

    // Row 2: POWER DENS ALT (computed in feet) — the owner report: metric still showed feet
    @Test fun `row 2 - density altitude follows the altitude unit`() {
        assertEquals("305 m", densityAltitudeText(eu, 1000))
        assertEquals("1000 ft", densityAltitudeText(us, 1000))
        assertEquals(null, densityAltitudeText(eu, null))
    }

    // Row 3: POWER LP FUEL (stored kPa)
    @Test fun `row 3 - LP fuel follows the engine-pressure unit`() {
        assertEquals("4.5 bar", lpFuelText(eu, 450.0))
        assertEquals("65 psi", lpFuelText(us, 450.0))
    }

    // Row 4: POWER BARO (stored kPa absolute) — said absolute in every unit
    @Test fun `row 4 - baro follows the air-pressure unit and says abs`() {
        assertEquals("1013 hPa abs", baroText(eu, 101.3))
        assertEquals("101.3 kPa abs", baroText(canada, 101.3))
        assertEquals("29.91 inHg abs", baroText(us, 101.3))
    }

    // Row 5: TIP — gauge vs measured baro in the boost unit; absolute in the air unit while baro is unknown
    @Test fun `row 5 - TIP reads gauge in the boost unit, else absolute in the air unit`() {
        val withBaro = VehicleState(barometricPressure = 100.0)
        assertEquals("0.50 bar", tipText(withBaro, eu, 150.0))
        assertEquals("7.3 psi", tipText(withBaro, us, 150.0))
        val noBaro = VehicleState(barometricPressure = 0.0)
        assertEquals("1500 hPa abs", tipText(noBaro, eu, 150.0))
        assertEquals("44.29 inHg abs", tipText(noBaro, us, 150.0))
    }

    // Row 6: PERF perf-run peak (stored gauge psi) — was always PSI
    @Test fun `row 6 - the perf-run peak follows the boost unit`() {
        assertEquals("1.00 bar", perfPeakBoostText(eu, 14.5))
        assertEquals("14.5 psi", perfPeakBoostText(us, 14.5))
        assertEquals("— bar", perfPeakBoostText(eu, null))
    }

    // Row 7: DIAG battery temperature — was always °C
    @Test fun `row 7 - DIAG battery temperature follows the temperature unit`() {
        assertEquals("25°C", eu.temp(25.0))
        assertEquals("77°F", us.temp(25.0))
    }

    // Row 8: THERMAL AWD clutch pressure (stored mbar), REQ torque (Nm) and wheel-speed Δ
    @Test fun `row 8 - AWD clutch pressure, requested torque and speed delta follow their units`() {
        val now = 1_000_000L
        val vs = VehicleState(
            isConnected = true, awdClutchPressureL = 12_000.0, awdReqTorqueL = 500.0,
            wheelSpeedRL = 100.0, wheelSpeedRR = 103.0,
            fieldLastUpdateMs = mapOf("awdReqTorqueL" to now),
        )
        fun cells(p: UserPrefs) = awdDetailRows(vs, p, live = true, nowMs = now).first.flatten()
        assertTrue(cells(eu).toString(), Triple("PRESSURE L", "12.0", "bar") in cells(eu))
        assertTrue(cells(us).toString(), Triple("PRESSURE L", "174", "psi") in cells(us))
        assertTrue(Triple("REQ L", "500", "Nm") in cells(eu))
        assertTrue(Triple("REQ L", "369", "lb·ft") in cells(us))
        assertTrue(Triple("L/R Δ", "3.0", "km/h") in cells(eu))
        assertTrue(Triple("L/R Δ", "1.9", "mph") in cells(us))
    }

    // Rows 9, 10 and 16: DRIVE ECONOMY — fuel used, economy (was UK MPG for every mph user) and idle flow (was L/hr)
    @Test fun `rows 9, 10, 16 - DRIVE economy cells follow fuel, economy and distance units`() {
        val econ = FuelEconomy.EconomyState(instantL100km = 10.0, avgL100km = 10.0, distanceToEmptyKm = 100.0,
            fuelUsedL = 2.0, idleFuelLPerHr = 1.2, isValid = true)
        val moving = VehicleState(isConnected = true, rpm = 3000.0, speedKph = 80.0)
        assertEquals(listOf(
            Triple("INSTANT", "10.0", "L/100 km"), Triple("AVERAGE", "10.0", "L/100 km"),
            Triple("TO EMPTY", "100", "km"), Triple("FUEL USED", "2.0", "L"),
        ), economyCells(moving, econ, eu))
        assertEquals(listOf(
            Triple("INSTANT", "23.5", "MPG (US)"), Triple("AVERAGE", "23.5", "MPG (US)"),
            Triple("TO EMPTY", "62", "mi"), Triple("FUEL USED", "0.53", "US gal"),
        ), economyCells(moving, econ, us))
        assertEquals(Triple("AVERAGE", "28.2", "MPG (UK)"), economyCells(moving, econ, uk)[1])
        assertEquals(Triple("AVERAGE", "10.0", "km/L"), economyCells(moving, econ, eu.copy(economyUnit = UnitIds.KML))[1])
        val idle = VehicleState(isConnected = true, rpm = 800.0, speedKph = 0.0)
        assertEquals(Triple("IDLE", "1.2", "L/h"), economyCells(idle, econ, eu)[0])
        assertEquals(Triple("IDLE", "0.32", "US gal/h"), economyCells(idle, econ, us)[0])
        // No figure: a dash beside the unit, never a computed 0
        assertEquals(Triple("FUEL USED", "—", "L"), economyCells(moving, econ.copy(fuelUsedL = 0.0), eu)[3])
    }

    // Row 11: TRIP GPS altitude and accuracy (the fix is in metres)
    @Test fun `row 11 - TRIP altitude and GPS accuracy follow the altitude unit`() {
        val now = System.currentTimeMillis()
        val s = TripPreview.driveState(paused = false, now = now)
        val m = fixReadout(s, 60.0, eu, now)!!
        assertEquals(listOf("ALT 21 m", "±4m"), listOf(m.alt, m.accuracy))
        val ft = fixReadout(s, 60.0, us, now)!!
        assertEquals(listOf("ALT 69 ft", "±13ft"), listOf(ft.alt, ft.accuracy))
    }

    // Row 12: Custom Dashboard cells — units on number cells, torque, AFR / λ, temperature
    @Test fun `row 12 - Custom Dashboard cells follow the units`() {
        assertEquals("bar", dashCellUnit("boostKpa", eu))
        assertEquals("mph", dashCellUnit("speedKph", us))
        val vs = VehicleState(isConnected = true, torqueAtTrans = 500.0, afrActual = 14.7, oilTempC = 100.0)
        assertEquals("500 Nm", resolveValue(vs, eu, DashCell("torqueAtTrans", "number", "TORQUE")).first)
        assertEquals("369 lb·ft", resolveValue(vs, us, DashCell("torqueAtTrans", "number", "TORQUE")).first)
        assertEquals("14.70:1", resolveValue(vs, eu, DashCell("afrActual", "number", "AFR")).first)
        assertEquals("1.000 λ", resolveValue(vs, eu.copy(mixtureUnit = UnitIds.LAMBDA), DashCell("afrActual", "number", "AFR")).first)
    }

    // POWER's mixture panel: the chosen form leads, the other is the third cell
    @Test fun `mixture - the chosen unit is the main value, the other stays as the third cell`() {
        val vs = VehicleState(afrActual = 14.7, afrDesired = 14.7)
        assertEquals(listOf(Triple("AFR ACTUAL", "14.70", ":1"), Triple("AFR DESIRED", "14.70", ":1"), Triple("LAMBDA", "1.000", "λ")),
            mixturePanel(eu, vs, hasAfr = true, ph = "—"))
        assertEquals(listOf(Triple("λ ACTUAL", "1.000", "λ"), Triple("λ DESIRED", "1.000", "λ"), Triple("AFR", "14.70", ":1")),
            mixturePanel(eu.copy(mixtureUnit = UnitIds.LAMBDA), vs, hasAfr = true, ph = "—"))
    }

    // Row 14: THERMAL wheel-speed cells — was "kph"
    @Test fun `row 14 - wheel speeds say km per h, never kph`() {
        assertEquals("100 km/h", formatWheelSpeed(100.0, eu))
        assertEquals("62 mph", formatWheelSpeed(100.0, us))
    }

    // TRIP's slip Δ printed km/h values under an mph label (found with #464): it converts, and its colour bands keep
    // comparing km/h
    @Test fun `TRIP slip delta shows the speed unit and bands on km per h`() {
        val vs = VehicleState(isConnected = true, wheelSpeedFL = 100.0, wheelSpeedFR = 100.0,
            wheelSpeedRL = 90.0, wheelSpeedRR = 90.0)
        val mph = wheelSpeedCells(vs, us, live = true, showSlipDelta = true, placeholder = "—")
        assertEquals(listOf("F-R", "L-R", "FL-RL", "FR-RR"), mph.map { it.first })
        assertEquals(listOf("6.2", "0.0", "6.2", "6.2"), mph.map { it.second })
        assertEquals(listOf(10.0, 0.0, 10.0, 10.0), mph.map { it.third })
        val kmh = wheelSpeedCells(vs, eu, live = true, showSlipDelta = true, placeholder = "—")
        assertEquals(listOf("10.0", "0.0", "10.0", "10.0"), kmh.map { it.second })
        assertEquals(mph.map { it.third }, kmh.map { it.third })
        // Wheel speeds in the speed unit, no band; offline every cell is the placeholder
        assertEquals(listOf("62", "62", "56", "56"),
            wheelSpeedCells(vs, us, live = true, showSlipDelta = false, placeholder = "—").map { it.second })
        assertEquals(List(4) { "—" }, wheelSpeedCells(vs, us, live = false, showSlipDelta = true, placeholder = "—").map { it.second })
    }

    @Test fun `a value that rounds to zero never prints as -0`() {
        assertEquals("0 m", eu.altitudeM(-0.3).text)          // TRIP ALT just below sea level
        assertEquals("0 ft", us.altitudeM(-0.1).text)
        assertEquals("-1 m", eu.altitudeM(-0.6).text)
    }

    @Test fun `in a locale with its own digits, negatives keep their sign and zero is never -0`() {
        val saved = java.util.Locale.getDefault()
        try {
            for (tag in listOf("mr", "ar", "fa", "bn", "fa-IR", "ar-EG", "bn-BD")) {
                java.util.Locale.setDefault(java.util.Locale.forLanguageTag(tag))
                val minus5 = "%.0f".format(-5.0)                 // this locale's own "-5"
                assertEquals(tag, minus5 + "°C", eu.temp(-5.0))
                assertEquals(tag, "%.2f".format(-0.35) + " bar", eu.boostGauge(-35.0).text)   // vacuum stays vacuum
                assertEquals(tag, "%.0f".format(0.0) + " m", eu.altitudeM(-0.3).text)
                assertEquals(tag, "", com.openrs.dash.ui.trip.fuelUsedCompare(1.20, 1.23, eu).delta)
            }
        } finally {
            java.util.Locale.setDefault(saved)
        }
        // f() tests zero half-up, as the formatter rounds: a half stays a signed value
        assertEquals("-1 m", eu.altitudeM(-0.5).text)
        assertEquals("%.1f".format(-0.05), eu.speedDelta(-0.05).value)
    }

    @Test fun `a fuel difference that rounds to zero shows no text, else a signed difference`() {
        assertEquals("", com.openrs.dash.ui.trip.fuelUsedCompare(1.20, 1.23, eu).delta)
        assertEquals("+0.1 L", com.openrs.dash.ui.trip.fuelUsedCompare(1.2, 1.3, eu).delta)
        assertEquals("-0.1 L", com.openrs.dash.ui.trip.fuelUsedCompare(1.3, 1.2, eu).delta)
    }

    @Test fun `the thermal margins note reads in the chosen temperature unit`() {
        assertEquals(listOf("2 °C", "3 °C", "3.6 °F", "5.4 °F"),
            listOf(tempDeltaText("C", 2.0), tempDeltaText("C", 3.0), tempDeltaText("F", 2.0), tempDeltaText("F", 3.0)))
    }

    // Row 13: the HUD's first frame hard-coded "PSI" and a bare "°" before the first state arrived
    @Test fun `row 13 - the HUD first frame speaks the boost and temperature units`() {
        val res = RuntimeEnvironment.getApplication().resources
        assertEquals(Triple("BOOST: --- bar", "RPM: ----", "OIL: ---°C"), HudOverlayService.startLines(res, eu))
        assertEquals(Triple("BOOST: --- psi", "RPM: ----", "OIL: ---°F"), HudOverlayService.startLines(res, us))
        assertFalse(HudOverlayService.startLines(res, UserPrefs()).third.endsWith("°"))
    }

    // Row 15: the demo's scenario line named a speed in km/h for every user
    @Test fun `row 15 - the demo scenario line names no unit`() {
        assertFalse(DemoScenario.DRIVING.blurb.contains("km/h"))
    }

    // ── The diagnostic export's settings block (#464: additive, the one place the PR changes what leaves the phone) ──

    @Test fun `diagnostic export adds Region and the seven units, and nothing else`() {
        DiagnosticLogger.sessionStart("test", 0, prefs = uk)
        try {
            val settings = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("settings")
            val before = setOf("speedUnit", "tempUnit", "boostUnit", "tireUnit", "tireLowPsi", "screenOn",
                "autoReconnect", "reconnectIntervalSec")
            val added = setOf("unitRegion", "engineUnit", "airUnit", "altitudeUnit", "fuelUnit", "economyUnit",
                "torqueUnit", "mixtureUnit")
            assertEquals(before + added, settings.keys().asSequence().toSet())
            assertEquals(
                listOf("UK", "BAR", "HPA", "FT", "L", "MPG_UK", "NM", "AFR"),
                added.map { settings.getString(it) },
            )
            assertEquals(listOf("MPH", "C", "PSI", "PSI"),
                listOf("speedUnit", "tempUnit", "boostUnit", "tireUnit").map { settings.getString(it) })

            val text = DiagnosticReportBuilder.buildSummaryText("t").lines()
            val from = text.indexOfFirst { it.startsWith("─── SETTINGS") }
            assertEquals(
                listOf("  Speed     : MPH", "  Temp      : C", "  Boost     : PSI", "  Tires     : PSI",
                    "  Region    : UK", "  Engine    : BAR", "  Air       : HPA", "  Altitude  : FT", "  Fuel      : L",
                    "  Economy   : MPG_UK", "  Torque    : NM", "  Mixture   : AFR",
                    "  TireLow   : ${uk.tireLowPsi} PSI", "  AutoRecon : ${uk.autoReconnect} / ${uk.reconnectIntervalSec}s", ""),
                text.subList(from + 1, from + 16),
            )

            // A hand-changed unit reads Custom
            DiagnosticLogger.sessionStart("test", 0, prefs = uk.copy(torqueUnit = UnitIds.LBFT))
            assertEquals("CUSTOM",
                JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONObject("settings").getString("unitRegion"))
        } finally {
            DiagnosticLogger.sessionStart("test", 0, prefs = null)
        }
    }

    // ── Warnings fire at the same physical value in every unit ──────────────────────────────────────────────────

    @Test fun `tyre low fires at the same pressure in psi, bar and kPa`() {
        for (unit in listOf(UnitIds.PSI, UnitIds.BAR, UnitIds.KPA)) {
            val p = eu.copy(tireUnit = unit, tireLowPsi = 30f)
            assertTrue(unit, p.isTireLow(29.9))
            assertFalse(unit, p.isTireLow(30.1))
        }
    }

    @Test fun `temperature warn and crit fire at the same temperature in C and F`() {
        for (p in listOf(eu, us)) {
            assertEquals(Level.WARN, tempLevel(p.oilWarnC + 0.5, p.oilWarnC, p.oilCritC, TempAvail.AVAILABLE))
            assertEquals(Level.CRIT, tempLevel(p.oilCritC + 0.5, p.oilWarnC, p.oilCritC, TempAvail.AVAILABLE))
        }
        assertEquals(eu.oilWarnC, us.oilWarnC, 0.0)
    }

    // ── The TPMS editor in kPa (#219's exact-psi rule) ───────────────────────────────────────────────────────────

    @Test fun `TPMS editor in kPa - an unchanged SAVE keeps the exact psi, an edit converts once`() {
        val t = TyreThreshold.of(30f, UnitIds.KPA)
        assertEquals("207", t.text)
        assertEquals(30f, t.resolvePsi(UnitIds.KPA)!!, 0f)
        // psi → kPa → bar → psi without an edit: still exactly 30
        assertEquals(30f, TyreThreshold.of(30f, UnitIds.PSI).convert(UnitIds.PSI, UnitIds.KPA)
            .convert(UnitIds.KPA, UnitIds.BAR).resolvePsi(UnitIds.BAR)!!, 0f)
        assertEquals(29.0075f, t.edit("200").resolvePsi(UnitIds.KPA)!!, 0.0001f)
    }

    // ── Exports keep their units and bytes under every preset ────────────────────────────────────────────────────

    @Test fun `drive exports are byte-identical under every preset`() {
        val drive = DriveEntity(id = 1, startTime = 1_700_000_000_000L, endTime = 1_700_000_600_000L, hasGps = true,
            distanceKm = 12.5, avgSpeedKph = 75.0, maxSpeedKph = 160.0, peakRpm = 6500, peakBoostPsi = 18.0,
            peakLateralG = 0.9, fuelUsedL = 1.8, peakOilTempC = 110.0, peakCoolantTempC = 95.0)
        val points = (0 until 5).map { i ->
            DrivePointEntity(driveId = 1, timestamp = 1_700_000_000_000L + i * 1000L, lat = 52.52 + i * 1e-4, lng = 13.40,
                speedKph = 80.0 + i, rpm = 3000 + i * 100, gear = "3", boostPsi = 12.5, coolantTempC = 90.0,
                oilTempC = 105.0, ambientTempC = 20.0, rduTempC = 55.0, ptuTempC = 60.0, fuelLevelPct = 70.0,
                lateralG = 0.2, throttlePct = 60.0, driveMode = "Sport", longitudinalG = 0.1, brakePressure = 0.0,
                steeringAngle = 0.0, isRaceReady = true)
        }
        val knock = listOf(KnockEventEntity(driveId = 1, timestamp = 1_700_000_001_000L, krDeg = 2.0, rpm = 5000,
            boostPsi = 15.0, iatC = 30.0, throttlePct = 99.0, gearEst = 3, lat = 52.52, lng = 13.40, cylinder = 2))
        fun exports(): List<String> = listOf(
            DriveExportBuilder.buildCsv(points), DriveExportBuilder.buildKnockCsv(knock),
            DriveExportBuilder.buildGpx(drive, points, "20260101_000000"), DriveExportBuilder.buildRaceChronoCsv(points),
            DriveExportBuilder.buildTrackAddictCsv(points), DriveExportBuilder.buildSummary(drive, points),
        )
        UserPrefsStore.resetForTest()
        val baseline = exports()
        for ((region, units) in Region.PRESETS) {
            UserPrefsStore.resetForTest()
            UserPrefsStore.ensureLoaded { UserPrefs().withUnits(units) }
            assertEquals("$region", baseline, exports())
        }
    }
}
