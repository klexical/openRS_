package com.openrs.dash

import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.ui.UserPrefs
import com.openrs.dash.ui.UserPrefsStore
import com.openrs.dash.ui.units.Region
import com.openrs.dash.ui.units.withUnits
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #506: a diagnostic export made with no adapter session (demo mode never starts one) carried no SETTINGS block and said
 * nothing about it. With no session's settings the export writes the current ones and says so: a `Source` line in the
 * summary's SETTINGS block and `settingsSource` = "current" beside `settings` in the detail JSON ("session" when a session's are written).
 */
class ExportSettingsWithoutSessionTest {

    private val uk = UserPrefs().withUnits(Region.UK.units!!)

    @After fun reset() {
        UserPrefsStore.resetForTest()
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
    }

    /** The lines of the summary's SETTINGS block, header excluded, up to its closing blank line. */
    private fun settingsLines(): List<String> {
        val text = DiagnosticReportBuilder.buildSummaryText("t").lines()
        val from = text.indexOfFirst { it.startsWith("─── SETTINGS") }
        assertTrue("SETTINGS block missing:\n${text.joinToString("\n")}", from >= 0)
        return text.drop(from + 1).takeWhile { it.isNotEmpty() }
    }

    @Test fun `with no adapter session the export carries the current settings and says where they come from`() {
        UserPrefsStore.resetForTest()
        UserPrefsStore.ensureLoaded { uk }
        DiagnosticLogger.sessionStart("test", 0, prefs = null)   // the state of an export before any connection

        val lines = settingsLines()
        assertTrue(lines.toString(), "  Speed     : MPH" in lines)
        assertTrue(lines.toString(), "  Region    : UK" in lines)
        assertEquals("  Source    : current settings (no adapter session)", lines.last())

        val json = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))
        val settings = json.getJSONObject("settings")
        assertEquals("current", json.getString("settingsSource"))
        assertEquals("MPH", settings.getString("speedUnit"))
        assertEquals("UK", settings.getString("unitRegion"))
    }

    @Test fun `a session's own settings are written as before, marked as the session's`() {
        UserPrefsStore.resetForTest()          // current = defaults; the session's = UK
        DiagnosticLogger.sessionStart("test", 0, prefs = uk)

        val lines = settingsLines()
        assertTrue(lines.toString(), "  Region    : UK" in lines)
        assertFalse(lines.toString(), lines.any { it.startsWith("  Source") })

        val json = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))
        val settings = json.getJSONObject("settings")
        assertEquals("session", json.getString("settingsSource"))
        assertEquals("UK", settings.getString("unitRegion"))
    }
}
