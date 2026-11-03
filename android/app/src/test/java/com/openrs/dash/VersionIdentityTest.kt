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

    @Test fun `this build is 2_2_7-rc_7, versionCode 37`() {
        assertEquals("2.2.7-rc.7", BuildConfig.RELEASE_NAME)
        assertEquals(37, BuildConfig.VERSION_CODE)
        assertEquals(AppVersion(2, 2, 7, 7), AppVersion.current())
        assertTrue(BuildConfig.VERSION_NAME.startsWith("2.2.7-rc.7"))           // debug adds -dev+<commit>
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
        assertEquals("android-v2.2.7-rc.7", "android-v${v.displayName}")
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

    @Test fun `Settings label — release, debug with revision and dirty marker`() {
        assertEquals("2.2.7-rc.7 · Build 36", BuildInfo.settingsLabel("2.2.7-rc.7", 36, "release", "1c3e602a9b1d", false))
        assertEquals("2.2.7-rc.7 · Build 36 · debug 1c3e602*", BuildInfo.settingsLabel("2.2.7-rc.7", 36, "debug", "1c3e602a9b1d", true))
        assertEquals("2.2.7 · Build 37", BuildInfo.settingsLabel("2.2.7", 37, "release", "abc", false))
    }

    @Test fun `What's New shows once per published release identity, never another version's notes`() {
        assertTrue(shouldShowWhatsNew(lastSeen = "2.2.7", releaseName = "2.2.7-rc.7", base = "2.2.7"))        // from rc.6 (stored base)
        assertTrue(shouldShowWhatsNew(lastSeen = "2.2.7-rc.6", releaseName = "2.2.7-rc.7", base = "2.2.7"))
        assertFalse(shouldShowWhatsNew(lastSeen = "2.2.7-rc.7", releaseName = "2.2.7-rc.7", base = "2.2.7"))  // already seen
        assertFalse(shouldShowWhatsNew(lastSeen = "2.2.7-rc.7", releaseName = "2.2.8-rc.1", base = "2.2.8"))  // no 2.2.8 notes
        assertNull(whatsNewHighlights("9.9.9"))
        assertTrue(whatsNewHighlights("2.2.7")!!.isNotEmpty())
    }
}
