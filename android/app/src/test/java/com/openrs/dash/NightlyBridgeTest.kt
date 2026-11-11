package com.openrs.dash

import com.openrs.dash.ui.shouldShowWhatsNew
import com.openrs.dash.update.AppVersion
import com.openrs.dash.update.UpdateChecker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * #322, the rc.8 updater bridge for nightly releases (`2.2.7-nightly.YYYYMMDD`): parse, order, channel, an installed
 * nightly, What's New. Seams as AppVersionTest / VersionIdentityTest: the three parsers, `parseReleases` with a literal
 * release list, `shouldShowWhatsNew`. Expected values are literals.
 */
class NightlyBridgeTest {
    private val nightly = "2.2.7-nightly.20261201"

    // ── 1. Parse ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a nightly tag, APK name and installed name parse as that nightly - not stable, its full name`() {
        for (v in listOf(
            AppVersion.fromTagName("android-v$nightly"),
            AppVersion.fromApkFilename("openRS_v$nightly.apk"),
            AppVersion.fromVersionName(nightly),
        )) {
            assertEquals(nightly, v?.displayName)
            assertFalse(v!!.isStable)
        }
    }

    @Test fun `a nightly name with the debug suffix parses as that nightly, hex or unknown commit`() {
        assertEquals(nightly, AppVersion.fromVersionName("$nightly-dev+1c3e602a9b1d.dirty")?.displayName)
        assertEquals(nightly, AppVersion.fromVersionName("$nightly-dev+unknown")?.displayName)
        assertEquals("2.2.7-rc.8", AppVersion.fromVersionName("2.2.7-rc.8-dev+unknown")?.displayName)
        assertEquals("2.2.7", AppVersion.fromVersionName("2.2.7-dev+1c3e602a9b1d")?.displayName)
    }

    @Test fun `seven or nine digits are not a nightly - in each parser`() {
        for (date in listOf("2026120", "202612011")) {
            assertNull(date, AppVersion.fromTagName("android-v2.2.7-nightly.$date"))
            assertNull(date, AppVersion.fromApkFilename("openRS_v2.2.7-nightly.$date.apk"))
            assertNull(date, AppVersion.fromVersionName("2.2.7-nightly.$date"))
        }
    }

    @Test fun `an installed name with any other suffix is not a version - never stable 2_2_7 by accident`() {
        assertNull(AppVersion.fromVersionName("2.2.7-anything"))
        assertNull(AppVersion.fromVersionName("2.2.7-nightly.20261201.2"))
        assertNull(AppVersion.fromVersionName("2.2.7-rc.8x"))
    }

    // ── 2. Order ─────────────────────────────────────────────────────────────────────────────────────────────────

    private fun v(name: String) = AppVersion.fromVersionName(name)!!

    @Test fun `within a base - every RC below a nightly below stable`() {
        assertTrue(v(nightly) > v("2.2.7-rc.8"))
        assertTrue(v(nightly) > v("2.2.7-rc.99"))
        assertTrue(v(nightly) < v("2.2.7"))
    }

    @Test fun `nightlies order by date, and two of one date are equal`() {
        assertTrue(v("2.2.7-nightly.20261202") > v(nightly))
        assertTrue(v("2.2.7-nightly.20270101") > v("2.2.7-nightly.20261231"))
        assertEquals(0, v(nightly).compareTo(v(nightly)))
        assertEquals(v(nightly), AppVersion.fromTagName("android-v$nightly"))
    }

    @Test fun `across bases the base decides`() {
        assertTrue(v("2.2.8-nightly.20270101") > v("2.2.7"))
        assertTrue(v("2.2.7-nightly.20261201") > v("2.2.6"))
        assertTrue(v("2.2.7-nightly.20261201") < v("2.2.8-rc.1"))
    }

    // ── 3. Channel, 4. Installed nightly ─────────────────────────────────────────────────────────────────────────

    private fun release(name: String, prerelease: Boolean) = JSONObject().apply {
        put("tag_name", "android-v$name"); put("prerelease", prerelease); put("draft", false); put("body", "notes $name")
        put("assets", JSONArray().put(JSONObject().apply {
            put("name", "openRS_v$name.apk"); put("browser_download_url", "https://example.invalid/$name"); put("size", 1)
        }))
    }
    private fun releases(vararg r: JSONObject) = JSONArray().apply { r.forEach { put(it) } }.toString()
    private fun offer(json: String, channel: String, installed: String) =
        UpdateChecker.parseReleases(json, channel, v(installed))?.version?.displayName

    @Test fun `rc_8 on Beta is offered the nightly, with GitHub's prerelease flag true or false`() {
        for (flag in listOf(true, false))
            assertEquals("prerelease $flag", nightly, offer(releases(release(nightly, flag)), "beta", "2.2.7-rc.8"))
    }

    @Test fun `rc_8 on Stable is never offered a nightly, with GitHub's prerelease flag true or false`() {
        for (flag in listOf(true, false))
            assertNull("prerelease $flag", offer(releases(release(nightly, flag)), "stable", "2.2.7-rc.8"))
    }

    @Test fun `an installed nightly on Beta is offered a later nightly`() {
        assertEquals("2.2.7-nightly.20261202",
            offer(releases(release("2.2.7-nightly.20261202", true), release(nightly, true)), "beta", nightly))
        assertNull(offer(releases(release(nightly, true)), "beta", nightly))                         // up to date
    }

    @Test fun `an installed nightly is offered stable 2_2_7 on both channels`() {
        for (channel in listOf("beta", "stable"))
            assertEquals(channel, "2.2.7", offer(releases(release("2.2.7", false), release(nightly, true)), channel, nightly))
    }

    @Test fun `an installed nightly is never offered an RC of its base`() {
        for (channel in listOf("beta", "stable"))
            assertNull(channel, offer(releases(release("2.2.7-rc.8", true), release("2.2.7-rc.9", true)), channel, nightly))
    }

    @Test fun `an installed nightly on Stable is not offered a later nightly`() {
        for (flag in listOf(true, false))
            assertNull("prerelease $flag", offer(releases(release("2.2.7-nightly.20261202", flag)), "stable", nightly))
    }

    // ── 5. What's New ────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `What's New does not open by itself on a nightly`() {
        assertFalse(shouldShowWhatsNew(lastSeen = "2.2.7-rc.8", releaseName = nightly, base = "2.2.7"))
        assertFalse(shouldShowWhatsNew(lastSeen = nightly, releaseName = "2.2.7-nightly.20261202", base = "2.2.7"))
    }

    @Test fun `What's New opens on stable after a nightly, and RCs are unchanged`() {
        assertTrue(shouldShowWhatsNew(lastSeen = "2.2.7-rc.8", releaseName = "2.2.7", base = "2.2.7"))
        assertTrue(shouldShowWhatsNew(lastSeen = nightly, releaseName = "2.2.7", base = "2.2.7"))
        assertTrue(shouldShowWhatsNew(lastSeen = "2.2.7-rc.7", releaseName = "2.2.7-rc.8", base = "2.2.7"))
    }
}
