package com.openrs.dash

import android.content.Context
import androidx.core.content.edit
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.ui.AppSettings
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.units.Region
import com.openrs.dash.ui.units.UnitIds
import com.openrs.dash.ui.units.UnitSet
import com.openrs.dash.ui.units.region
import com.openrs.dash.ui.units.unitSet
import com.openrs.dash.ui.units.withUnits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Locale

/**
 * #464: Settings → UNITS → Region. The five presets, Region derived from the eleven units, a new install's units from
 * the phone's country setting, and the existing-user rule (the owner's): keep the four original choices, fill the seven
 * new units from the closest preset, run once.
 */
@RunWith(RobolectricTestRunner::class)
class UnitsRegionTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<Context>()
    private val prefs get() = ctx.getSharedPreferences("openrs_settings", Context.MODE_PRIVATE)
    private val savedLocale = Locale.getDefault()

    @Before fun clear() = prefs.edit(commit = true) { clear() }
    @After fun restore() { Locale.setDefault(savedLocale); prefs.edit(commit = true) { clear() } }

    private fun units(speed: String, temp: String, boost: String, tyre: String, engine: String, air: String,
                      altitude: String, fuel: String, economy: String, torque: String) =
        UnitSet(speed, temp, boost, tyre, engine, air, altitude, fuel, economy, torque, UnitIds.AFR)

    // ── Presets (the owner's table) ──────────────────────────────────────────────────────────────────────────────

    @Test fun `the five presets are the owner's table`() {
        assertEquals(units("KPH", "C", "BAR", "BAR", "BAR", "HPA", "M", "L", "L100KM", "NM"), Region.EUROPE.units)
        assertEquals(units("MPH", "C", "PSI", "PSI", "BAR", "HPA", "FT", "L", "MPG_UK", "NM"), Region.UK.units)
        assertEquals(units("MPH", "F", "PSI", "PSI", "PSI", "INHG", "FT", "US_GAL", "MPG_US", "LBFT"), Region.US.units)
        assertEquals(units("KPH", "C", "PSI", "PSI", "PSI", "KPA", "M", "L", "L100KM", "LBFT"), Region.CANADA.units)
        assertEquals(units("KPH", "C", "PSI", "KPA", "BAR", "HPA", "M", "L", "L100KM", "NM"), Region.AUS_NZ.units)
        assertEquals(null, Region.CUSTOM.units)
        assertEquals(listOf(Region.EUROPE, Region.UK, Region.US, Region.CANADA, Region.AUS_NZ), Region.PRESETS.keys.toList())
    }

    @Test fun `each preset reads back as itself, and a hand change makes Custom`() {
        for ((region, u) in Region.PRESETS) {
            assertEquals(region, Region.of(u))
            assertEquals(region, UserPrefs().withUnits(u).region)
        }
        val europe = Region.EUROPE.units!!
        assertEquals(Region.CUSTOM, Region.of(europe.copy(torque = UnitIds.LBFT)))
        // Back to the preset's value: the preset again (Region is recomputed on every change)
        assertEquals(Region.EUROPE, Region.of(europe.copy(torque = UnitIds.LBFT).copy(torque = UnitIds.NM)))
    }

    @Test fun `picking a region after Custom sets all eleven units`() {
        val custom = UserPrefs().withUnits(Region.EUROPE.units!!.copy(economy = UnitIds.MPG_US))
        assertEquals(Region.CUSTOM, custom.region)
        val uk = custom.withUnits(Region.UK.units!!)
        assertEquals(Region.UK.units, uk.unitSet)
        assertTrue("the odometer follows speed & distance", uk.odomInMiles)
        assertFalse(uk.withUnits(Region.EUROPE.units!!).odomInMiles)
    }

    // ── New installs: the phone's country setting ────────────────────────────────────────────────────────────────

    @Test fun `a new install's region comes from the phone's country`() {
        val expected = mapOf("GB" to Region.UK, "US" to Region.US, "CA" to Region.CANADA, "AU" to Region.AUS_NZ,
            "NZ" to Region.AUS_NZ, "IE" to Region.EUROPE, "DE" to Region.EUROPE, "FR" to Region.EUROPE)
        for ((country, region) in expected) assertEquals(country, region, Region.of(Region.unitsForCountry(country)))
    }

    @Test fun `Mexico and Chile start on Europe and international with km per litre, which reads Custom`() {
        for (country in listOf("MX", "CL")) {
            val u = Region.unitsForCountry(country)
            assertEquals(Region.EUROPE.units!!.copy(economy = UnitIds.KML), u)
            assertEquals(Region.CUSTOM, Region.of(u))
        }
    }

    @Test fun `an empty settings file takes the default locale's country on first load`() {
        Locale.setDefault(Locale.UK)
        assertEquals(Region.UK, AppSettings.load(ctx).region)
        prefs.edit(commit = true) { clear() }
        Locale.setDefault(Locale.GERMANY)
        assertEquals(Region.EUROPE, AppSettings.load(ctx).region)
    }

    // ── Existing installs: the owner's closest-preset rule ───────────────────────────────────────────────────────

    private fun existing(speed: String, temp: String, boost: String, tyre: String): UserPrefs {
        prefs.edit(commit = true) {
            putString(AppSettings.KEY_SPEED_UNIT, speed); putString(AppSettings.KEY_TEMP_UNIT, temp)
            putString(AppSettings.KEY_BOOST_UNIT, boost); putString(AppSettings.KEY_TIRE_UNIT, tyre)
        }
        Locale.setDefault(Locale.US)   // must not matter for an existing install
        return AppSettings.load(ctx)
    }

    /** The four are kept, the other six come from [preset], and economy is [economy] (the preset's unless given). */
    private fun assertMigrated(p: UserPrefs, four: List<String>, preset: Region, economy: String = preset.units!!.economy) {
        assertEquals("the four choices are kept", four, listOf(p.speedUnit, p.tempUnit, p.boostUnit, p.tireUnit))
        val u = preset.units!!
        assertEquals("the seven new units come from $preset, economy $economy",
            listOf(u.engine, u.air, u.altitude, u.fuel, economy, u.torque, u.mixture),
            listOf(p.engineUnit, p.airUnit, p.altitudeUnit, p.fuelUnit, p.economyUnit, p.torqueUnit, p.mixtureUnit))
    }

    @Test fun `km per h, C, bar, bar starts on Europe and international, HP fuel in bar`() {
        val p = existing("KPH", "C", "BAR", "BAR")
        assertMigrated(p, listOf("KPH", "C", "BAR", "BAR"), Region.EUROPE)
        assertEquals(Region.EUROPE, p.region)
        assertEquals(UnitIds.BAR, p.engineUnit)
    }

    @Test fun `km per h, C, psi, psi starts on Canada`() {
        val p = existing("KPH", "C", "PSI", "PSI")
        assertMigrated(p, listOf("KPH", "C", "PSI", "PSI"), Region.CANADA)
        assertEquals(Region.CANADA, p.region)
    }

    @Test fun `mph, C, psi, psi starts on UK with MPG UK unchanged`() {
        val p = existing("MPH", "C", "PSI", "PSI")
        assertMigrated(p, listOf("MPH", "C", "PSI", "PSI"), Region.UK)
        assertEquals(UnitIds.MPG_UK, p.economyUnit)
        assertEquals(Region.UK, p.region)
    }

    @Test fun `mph, F, psi, psi - the old defaults - starts on US, MPG in US gallons`() {
        val p = existing("MPH", "F", "PSI", "PSI")
        assertMigrated(p, listOf("MPH", "F", "PSI", "PSI"), Region.US)
        assertEquals(UnitIds.MPG_US, p.economyUnit)
        assertEquals(Region.US, p.region)
    }

    @Test fun `tie - mph, F, bar, bar goes to US, which matches speed and temperature`() {
        // Europe and US both match 2 of 4; US matches speed and temperature
        val p = existing("MPH", "F", "BAR", "BAR")
        assertMigrated(p, listOf("MPH", "F", "BAR", "BAR"), Region.US)
        assertEquals(Region.CUSTOM, p.region)
    }

    @Test fun `km per h, F, psi, psi - a tie of US and Canada - takes US's units with L per 100 km`() {
        // US and Canada both match 3 of 4 and neither matches speed and temperature: list order gives US; economy
        // follows km/h (owner), so L/100 km beside km/h
        val p = existing("KPH", "F", "PSI", "PSI")
        assertMigrated(p, listOf("KPH", "F", "PSI", "PSI"), Region.US, economy = UnitIds.L100KM)
        assertEquals(Region.CUSTOM, p.region)
    }

    @Test fun `mph, C, bar, bar takes Europe and international's units, 3 of 4, with MPG UK`() {
        val p = existing("MPH", "C", "BAR", "BAR")
        assertMigrated(p, listOf("MPH", "C", "BAR", "BAR"), Region.EUROPE, economy = UnitIds.MPG_UK)
        assertEquals(Region.CUSTOM, p.region)
    }

    // Economy follows the user's own speed and temperature after the preset fill (owner): one test per pair, each
    // over every boost and tyre unit
    private fun assertEconomy(speed: String, temp: String, economy: String) {
        for (boost in listOf("PSI", "BAR", "KPA")) for (tyre in listOf("PSI", "BAR", "KPA")) {
            prefs.edit(commit = true) { clear() }
            assertEquals("$speed $temp $boost $tyre", economy, existing(speed, temp, boost, tyre).economyUnit)
        }
    }

    @Test fun `economy - km per h with C reads L per 100 km`() = assertEconomy("KPH", "C", UnitIds.L100KM)
    @Test fun `economy - km per h with F reads L per 100 km`() = assertEconomy("KPH", "F", UnitIds.L100KM)
    @Test fun `economy - mph with C reads MPG UK`() = assertEconomy("MPH", "C", UnitIds.MPG_UK)
    @Test fun `economy - mph with F reads MPG US`() = assertEconomy("MPH", "F", UnitIds.MPG_US)

    @Test fun `tie on speed and temperature goes to the first preset in list order`() {
        // km/h, C, psi, bar: Europe 3 (speed, temp, tyre), Canada 3 (speed, temp, boost) → Europe (listed first)
        val p = existing("KPH", "C", "PSI", "BAR")
        assertMigrated(p, listOf("KPH", "C", "PSI", "BAR"), Region.EUROPE)
    }

    @Test fun `an existing install with only other keys keeps the old defaults and gets US`() {
        prefs.edit(commit = true) { putBoolean("screen_on", true) }
        Locale.setDefault(Locale.GERMANY)
        val p = AppSettings.load(ctx)
        assertEquals(Region.US, p.region)
    }

    @Test fun `running the migration twice changes nothing`() {
        existing("MPH", "C", "PSI", "PSI")
        val once = prefs.all.toMap()
        AppSettings.migrateUnits(ctx, "DE")
        AppSettings.load(ctx)
        assertEquals(once, prefs.all.toMap())
        // A unit the user changes after the migration is not migrated again
        prefs.edit(commit = true) { putString(AppSettings.KEY_ECONOMY_UNIT, UnitIds.KML) }
        assertEquals(UnitIds.KML, AppSettings.load(ctx).economyUnit)
    }
}
