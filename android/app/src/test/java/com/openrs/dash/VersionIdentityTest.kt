package com.openrs.dash

import com.openrs.dash.ui.shouldShowWhatsNew
import com.openrs.dash.ui.whatsNewHighlights
import com.openrs.dash.update.AppVersion
import com.openrs.dash.update.BuildInfo
import com.openrs.dash.update.UpdateChecker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** One release identity across build, updater, Settings and What's New (#214). */
class VersionIdentityTest {

    // The build's own stage (#362): without -PnightlyDate it is the file's literal release, 2.2.7-rc.8 with code 39;
    // with it, a nightly of the same base whose code is (YYYYMMDD − 20000000) × 10 of the date in its own name.
    private val nightlyName = Regex("""2\.2\.7-nightly\.(\d{8})""")

    @Test fun `this build is 2_2_7-rc_8 with code 39, or a nightly of 2_2_7 with the code of its date`() {
        val nightly = nightlyName.matchEntire(BuildConfig.RELEASE_NAME)
        if (nightly == null) {
            assertEquals("2.2.7-rc.8", BuildConfig.RELEASE_NAME)
            assertEquals(39, BuildConfig.VERSION_CODE)
            assertEquals(AppVersion(2, 2, 7, 8), AppVersion.current())
        } else {
            val date = nightly.groupValues[1].toInt()
            assertEquals((date - 20_000_000) * 10, BuildConfig.VERSION_CODE)
            assertEquals(AppVersion(2, 2, 7, nightly = date), AppVersion.current())
            assertTrue(AppVersion.current().isNightly)
        }
        assertTrue(BuildConfig.VERSION_NAME.startsWith(BuildConfig.RELEASE_NAME))   // debug adds -dev+<commit>
        assertEquals("2.2.7", BuildConfig.RELEASE_BASE)
    }

    @Test fun `version names parse with and without RC and dev suffix`() {
        assertEquals(AppVersion(2, 2, 7, 7), AppVersion.fromVersionName("2.2.7-rc.7"))
        assertEquals(AppVersion(2, 2, 7, 7), AppVersion.fromVersionName("2.2.7-rc.7-dev+1c3e602a9b1d.dirty"))
        assertEquals(AppVersion(2, 2, 7), AppVersion.fromVersionName("2.2.7"))
        assertEquals(AppVersion(2, 2, 7, 10), AppVersion.fromVersionName("2.2.7-rc.10"))
        assertNull(AppVersion.fromVersionName("unknown"))
    }

    @Test fun `tag, APK filename and version name agree`() {
        val v = AppVersion.fromVersionName(BuildConfig.RELEASE_NAME)!!
        assertEquals(v, AppVersion.fromTagName("android-v${BuildConfig.RELEASE_NAME}"))
        assertEquals(v, AppVersion.fromApkFilename("openRS_v${BuildConfig.RELEASE_NAME}.apk"))
        assertEquals("android-v${BuildConfig.RELEASE_NAME}", "android-v${v.displayName}")
    }

    private fun release(tag: String, apk: String, prerelease: Boolean) = JSONObject().apply {
        put("tag_name", tag); put("prerelease", prerelease); put("draft", false); put("body", "notes $tag")
        put("assets", JSONArray().put(JSONObject().apply {
            put("name", apk); put("browser_download_url", "https://example.invalid/$apk"); put("size", 1)
        }))
    }
    private fun releases(vararg r: JSONObject) = JSONArray().apply { r.forEach { put(it) } }.toString()

    @Test fun `rc_6 is offered rc_7 on the beta channel`() {
        val json = releases(release("android-v2.2.7-rc.6", "openRS_v2.2.7-rc.6.apk", true),
                            release("android-v2.2.7-rc.7", "openRS_v2.2.7-rc.7.apk", true))
        assertEquals(AppVersion(2, 2, 7, 7), UpdateChecker.parseReleases(json, "beta", AppVersion(2, 2, 7, 6))!!.version)
        assertNull(UpdateChecker.parseReleases(json, "beta", AppVersion(2, 2, 7, 7)))   // up to date
    }

    @Test fun `rc_9 is offered rc_10 (numeric, not text, order)`() {
        val json = releases(release("android-v2.2.7-rc.10", "openRS_v2.2.7-rc.10.apk", true))
        assertEquals(AppVersion(2, 2, 7, 10), UpdateChecker.parseReleases(json, "beta", AppVersion(2, 2, 7, 9))!!.version)
    }

    @Test fun `an RC is offered the stable release, and stable outranks every RC`() {
        val json = releases(release("android-v2.2.7-rc.7", "openRS_v2.2.7-rc.7.apk", true),
                            release("android-v2.2.7", "openRS_v2.2.7.apk", false))
        assertEquals(AppVersion(2, 2, 7), UpdateChecker.parseReleases(json, "beta", AppVersion(2, 2, 7, 7))!!.version)
        assertEquals(AppVersion(2, 2, 7), UpdateChecker.parseReleases(json, "stable", AppVersion(2, 2, 7, 7))!!.version)
    }

    @Test fun `the stable channel never offers a prerelease`() {
        val json = releases(release("android-v2.2.7-rc.7", "openRS_v2.2.7-rc.7.apk", true))
        assertNull(UpdateChecker.parseReleases(json, "stable", AppVersion(2, 2, 6)))
        assertEquals(AppVersion(2, 2, 7, 7), UpdateChecker.parseReleases(json, "beta", AppVersion(2, 2, 6))!!.version)
    }

    // rc.7 Build 38 ships as an extra asset on the existing rc.7 release under another name: the updater takes only the
    // asset named exactly openRS_v<name>.apk for the tag (#364), and Build 38 is a manual in-place upgrade.
    private fun releaseWith(tag: String, prerelease: Boolean, vararg apks: String) = JSONObject().apply {
        put("tag_name", tag); put("prerelease", prerelease); put("draft", false); put("body", "notes $tag")
        put("assets", JSONArray().apply {
            apks.forEach { apk -> put(JSONObject().apply {
                put("name", apk); put("browser_download_url", "https://example.invalid/$apk"); put("size", 1)
            }) }
        })
    }

    @Test fun `the Build 38 maintenance asset is not the release's exact name, the original asset still matches`() {
        val original = "openRS_v2.2.7-rc.7.apk"; val build38 = "openrs-2.2.7-rc.7-build38.apk"
        for (order in listOf(arrayOf(original, build38), arrayOf(build38, original))) {
            val json = releases(releaseWith("android-v2.2.7-rc.7", true, *order))
            val fromRc6 = UpdateChecker.parseReleases(json, "beta", AppVersion(2, 2, 7, 6))!!
            assertEquals(AppVersion(2, 2, 7, 7), fromRc6.version)
            assertEquals("https://example.invalid/$original", fromRc6.downloadUrl)                // never the Build 38 file
            assertNull(UpdateChecker.parseReleases(json, "beta", AppVersion(2, 2, 7, 7)))      // Build 37 / 38: up to date
        }
        // Alone, the maintenance asset is no update at all: its name is not openRS_v2.2.7-rc.7.apk
        assertNull(UpdateChecker.parseReleases(releases(releaseWith("android-v2.2.7-rc.7", true, build38)), "beta", AppVersion(2, 2, 7, 6)))
    }

    @Test fun `Settings label — release, debug with revision and dirty marker`() {
        assertEquals("2.2.7-rc.7 · Build 36", BuildInfo.settingsLabel("2.2.7-rc.7", 36, "release", "1c3e602a9b1d", false))
        assertEquals("2.2.7-rc.7 · Build 36 · debug 1c3e602*", BuildInfo.settingsLabel("2.2.7-rc.7", 36, "debug", "1c3e602a9b1d", true))
        assertEquals("2.2.7 · Build 37", BuildInfo.settingsLabel("2.2.7", 37, "release", "abc", false))
    }

    @Test fun `What's New shows once per published release identity, never another version's notes`() {
        assertTrue(shouldShowWhatsNew(lastSeen = "2.2.7", releaseName = "2.2.7-rc.7", base = "2.2.7"))        // from rc.6 (stored base)
        assertTrue(shouldShowWhatsNew(lastSeen = "2.2.7-rc.6", releaseName = "2.2.7-rc.7", base = "2.2.7"))
        assertFalse(shouldShowWhatsNew(lastSeen = "2.2.7-rc.7", releaseName = "2.2.7-rc.7", base = "2.2.7"))  // already seen
        // Build 37 → Build 38 keeps the release name, so the dialog does not reopen on that upgrade; its Build 38 line
        // shows on a fresh install or from Settings → What's New
        assertFalse(shouldShowWhatsNew(lastSeen = BuildConfig.RELEASE_NAME, releaseName = BuildConfig.RELEASE_NAME, base = "2.2.7"))
        assertTrue(shouldShowWhatsNew(lastSeen = "2.2.7-rc.7", releaseName = "2.2.7-rc.8", base = "2.2.7"))   // rc.7 → rc.8 shows it
        assertTrue(whatsNewHighlights("2.2.7")!!.first().startsWith("rc.8 · Build 39: "))   // rc.8's line, in a nightly too
        assertTrue(whatsNewHighlights("2.2.7")!!.any { it.startsWith("rc.7 · Build 38: ") })    // rc.7's maintenance line kept
        assertTrue(whatsNewHighlights("2.2.7")!!.any { it.contains("labelled TIP, and") })      // rc.7's published line kept
        assertFalse(shouldShowWhatsNew(lastSeen = "2.2.7-rc.7", releaseName = "2.2.8-rc.1", base = "2.2.8"))  // no 2.2.8 notes
        assertNull(whatsNewHighlights("9.9.9"))
        assertTrue(whatsNewHighlights("2.2.7")!!.isNotEmpty())
    }
}
