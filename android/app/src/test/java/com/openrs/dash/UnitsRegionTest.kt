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
 * the phone's country setting, and the existing-user rule (the owner's): the same phone-country region gives the seven
 * new units, the four original choices are kept, and it runs once.
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

    // ── Existing installs: the phone's region, the four choices kept (owner) ─────────────────────────────────────

    /** An existing settings file holding the four original choices, loaded on a phone set to [locale]. */
    private fun existing(speed: String, temp: String, boost: String, tyre: String, locale: Locale = Locale.US): UserPrefs {
        prefs.edit(commit = true) {
            putString(AppSettings.KEY_SPEED_UNIT, speed); putString(AppSettings.KEY_TEMP_UNIT, temp)
            putString(AppSettings.KEY_BOOST_UNIT, boost); putString(AppSettings.KEY_TIRE_UNIT, tyre)
        }
        Locale.setDefault(locale)
        return AppSettings.load(ctx)
    }

    /** The four are kept exactly, and the seven new units are [region]'s. */
    private fun assertMigrated(p: UserPrefs, four: List<String>, seven: UnitSet) {
        assertEquals("the four choices are kept", four, listOf(p.speedUnit, p.tempUnit, p.boostUnit, p.tireUnit))
        assertEquals("the seven new units come from the phone's region",
            listOf(seven.engine, seven.air, seven.altitude, seven.fuel, seven.economy, seven.torque, seven.mixture),
            listOf(p.engineUnit, p.airUnit, p.altitudeUnit, p.fuelUnit, p.economyUnit, p.torqueUnit, p.mixtureUnit))
    }

    @Test fun `an existing install takes the seven new units from the phone's region and keeps its four`() {
        val phones = mapOf(Locale.UK to Region.UK, Locale.US to Region.US, Locale.CANADA to Region.CANADA,
            Locale("en", "AU") to Region.AUS_NZ, Locale("en", "NZ") to Region.AUS_NZ, Locale.GERMANY to Region.EUROPE,
            Locale("en", "IE") to Region.EUROPE)
        val fours = listOf(listOf("KPH", "C", "BAR", "BAR"), listOf("KPH", "C", "PSI", "PSI"),
            listOf("MPH", "C", "PSI", "PSI"), listOf("MPH", "F", "PSI", "PSI"), listOf("KPH", "F", "PSI", "KPA"))
        for ((locale, region) in phones) for (four in fours) {
            prefs.edit(commit = true) { clear() }
            assertMigrated(existing(four[0], four[1], four[2], four[3], locale), four, region.units!!)
        }
    }

    @Test fun `where the four match the phone's region, Region reads it, else Custom`() {
        assertEquals(Region.UK, existing("MPH", "C", "PSI", "PSI", Locale.UK).region)
        prefs.edit(commit = true) { clear() }
        assertEquals(Region.US, existing("MPH", "F", "PSI", "PSI", Locale.US).region)
        prefs.edit(commit = true) { clear() }
        assertEquals(Region.EUROPE, existing("KPH", "C", "BAR", "BAR", Locale.GERMANY).region)
        prefs.edit(commit = true) { clear() }
        // The old defaults on a German phone: mph, °F and psi kept beside Europe & international's seven
        val p = existing("MPH", "F", "PSI", "PSI", Locale.GERMANY)
        assertEquals(Region.CUSTOM, p.region)
        assertEquals(UnitIds.L100KM, p.economyUnit)
    }

    @Test fun `the old defaults on a US phone read MPG in US gallons, and on a UK phone in UK gallons`() {
        assertEquals(UnitIds.MPG_US, existing("MPH", "F", "PSI", "PSI", Locale.US).economyUnit)
        prefs.edit(commit = true) { clear() }
        assertEquals(UnitIds.MPG_UK, existing("MPH", "F", "PSI", "PSI", Locale.UK).economyUnit)
    }

    @Test fun `an existing install on a Mexican phone takes Europe and international with km per litre`() {
        val p = existing("KPH", "C", "BAR", "BAR", Locale("es", "MX"))
        assertMigrated(p, listOf("KPH", "C", "BAR", "BAR"), Region.EUROPE.units!!.copy(economy = UnitIds.KML))
        assertEquals(Region.CUSTOM, p.region)
    }

    @Test fun `an existing install with only other keys keeps the old four defaults beside the phone's region`() {
        prefs.edit(commit = true) { putBoolean("screen_on", true) }
        Locale.setDefault(Locale.GERMANY)
        val p = AppSettings.load(ctx)
        assertMigrated(p, listOf("MPH", "F", "PSI", "PSI"), Region.EUROPE.units!!)
        prefs.edit(commit = true) { clear(); putBoolean("screen_on", true) }
        Locale.setDefault(Locale.US)
        assertEquals(Region.US, AppSettings.load(ctx).region)
    }

    @Test fun `running the migration twice changes nothing`() {
        existing("MPH", "C", "PSI", "PSI", Locale.UK)
        val once = prefs.all.toMap()
        AppSettings.migrateUnits(ctx, "DE")
        AppSettings.load(ctx)
        assertEquals(once, prefs.all.toMap())
        // A unit the user changes after the migration is not migrated again
        prefs.edit(commit = true) { putString(AppSettings.KEY_ECONOMY_UNIT, UnitIds.KML) }
        assertEquals(UnitIds.KML, AppSettings.load(ctx).economyUnit)
    }
}
