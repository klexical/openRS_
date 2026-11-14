package com.openrs.dash

import com.openrs.dash.update.AppVersion
import com.openrs.dash.update.RateLimitException
import com.openrs.dash.update.UpdateChecker
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI

/**
 * How the updater finds a release (#364), through `UpdateChecker.findUpdate` with a fake GitHub that serves
 * `/releases/latest` (the release marked Latest, else 404) and `/releases?per_page=&page=` (newest first, default 30,
 * at most [FakeGitHub.maxPer], with a `Link: …; rel="next"` header while more follow), and records every request.
 * Stable asks for Latest first and pages (100 at a time while there is a next page, at most 10 pages) only when Latest
 * is not an Android stable; Beta reads one page of 100; the APK is the asset named exactly `openRS_v<name>.apk`; the choice is
 * by version, never by list position.
 */
class UpdateLookupTest {

    private class FakeGitHub(val releases: List<JSONObject>, val latestTag: String?, val maxPer: Int = 100) {
        val urls = mutableListOf<String>()
        var rateLimited: (String) -> Boolean = { false }
        var failing: (String) -> Boolean = { false }

        fun get(url: String): UpdateChecker.Reply {
            urls += url
            if (rateLimited(url)) return UpdateChecker.Reply(403, """{"message":"API rate limit exceeded"}""")
            if (failing(url)) return UpdateChecker.Reply(500, null)
            val uri = URI(url)
            val query = (uri.rawQuery ?: "").split("&").filter { "=" in it }
                .associate { it.substringBefore("=") to it.substringAfter("=") }
            return when {
                uri.path == "/repos/klexical/openRS_/releases/latest" -> {
                    val r = releases.firstOrNull { it.getString("tag_name") == latestTag }
                    if (r == null) UpdateChecker.Reply(404, """{"message":"Not Found"}""") else UpdateChecker.Reply(200, r.toString())
                }
                uri.path == "/repos/klexical/openRS_/releases" -> {
                    val per = minOf(query["per_page"]?.toInt() ?: 30, maxPer)
                    val page = query["page"]?.toInt() ?: 1
                    val slice = releases.drop((page - 1) * per).take(per)
                    val next = if (page * per < releases.size)
                        "<https://api.github.com/repositories/1/releases?per_page=$per&page=${page + 1}>; rel=\"next\"" else null
                    UpdateChecker.Reply(200, JSONArray().apply { slice.forEach { put(it) } }.toString(), next)
                }
                else -> UpdateChecker.Reply(404, null)
            }
        }
        val paths get() = urls.map { URI(it).let { u -> u.path.substringAfter("/releases").ifEmpty { "/" } + (u.rawQuery?.let { q -> "?$q" } ?: "") } }
    }

    private fun android(name: String, prerelease: Boolean, vararg assets: String = arrayOf("openRS_v$name.apk")) =
        JSONObject().apply {
            put("tag_name", "android-v$name"); put("prerelease", prerelease); put("draft", false); put("body", "notes $name")
            put("assets", JSONArray().apply {
                assets.forEach { a -> put(JSONObject().apply {
                    put("name", a); put("browser_download_url", "https://example.invalid/$name/$a"); put("size", 1)
                }) }
            })
        }
    private fun firmware(tag: String) = JSONObject().apply {
        put("tag_name", tag); put("prerelease", false); put("draft", false); put("body", "firmware")
        put("assets", JSONArray().put(JSONObject().apply {
            put("name", "openrs-fw-pro_v130.bin"); put("browser_download_url", "https://example.invalid/$tag.bin"); put("size", 1)
        }))
    }
    /** [n] 2.2.8 nightlies, newest first, dated from 2027-01-01 on. */
    private fun nightlies(n: Int) = (n downTo 1).map { i ->
        val d = java.time.LocalDate.of(2027, 1, 1).plusDays((i - 1).toLong())
        android("2.2.8-nightly.%04d%02d%02d".format(d.year, d.monthValue, d.dayOfMonth), true)
    }
    private val newestOf40 = "2.2.8-nightly.20270209"
    private fun v(name: String) = AppVersion.fromVersionName(name)!!
    private fun find(gh: FakeGitHub, channel: String, installed: String) =
        UpdateChecker.findUpdate(channel, v(installed), gh::get)

    // ── Stable: Latest first ─────────────────────────────────────────────────────────────────────────────────────

    @Test fun `40 nightlies ahead of a stable still yield the stable on Stable, from one request for Latest`() {
        val gh = FakeGitHub(nightlies(40) + android("2.2.7", false) + android("2.2.7-rc.8", true), latestTag = "android-v2.2.7")
        assertEquals("2.2.7", find(gh, "stable", "2.2.7-rc.8")?.version?.displayName)
        assertEquals(listOf("/latest"), gh.paths)
    }

    @Test fun `a firmware release marked not-Latest is ignored`() {
        val gh = FakeGitHub(listOf(firmware("fw-pro-v1.3"), android("2.2.7", false)), latestTag = "android-v2.2.7")
        assertEquals("2.2.7", find(gh, "stable", "2.2.6")?.version?.displayName)
        assertEquals(listOf("/latest"), gh.paths)
        val beta = FakeGitHub(listOf(firmware("fw-pro-v1.3"), android("2.2.7", false)), latestTag = "android-v2.2.7")
        assertEquals("2.2.7", find(beta, "beta", "2.2.6")?.version?.displayName)
    }

    @Test fun `named assumption - Latest left on an older Android stable decides alone, a newer unmarked stable is not seen`() {
        // The release checklist keeps Latest on the newest Android stable; this pins what Stable does when it is not
        val gh = FakeGitHub(listOf(android("2.2.8", false), android("2.2.7", false)), latestTag = "android-v2.2.7")
        assertEquals("2.2.7", find(gh, "stable", "2.2.6")?.version?.displayName)
        assertEquals(listOf("/latest"), gh.paths)
        assertNull(find(FakeGitHub(gh.releases, "android-v2.2.7"), "stable", "2.2.7"))
    }

    @Test fun `Latest at or below the installed version means no update, from that one request`() {
        val gh = FakeGitHub(nightlies(5) + android("2.2.7", false), latestTag = "android-v2.2.7")
        assertNull(find(gh, "stable", "2.2.7"))
        assertEquals(listOf("/latest"), gh.paths)
    }

    // ── Stable: the bounded fallback ─────────────────────────────────────────────────────────────────────────────

    @Test fun `with firmware marked Latest, Stable pages by 100 and finds the stable behind 150 nightlies`() {
        val gh = FakeGitHub(listOf(firmware("fw-pro-v1.3")) + nightlies(150) + android("2.2.7", false), latestTag = "fw-pro-v1.3")
        assertEquals("2.2.7", find(gh, "stable", "2.2.7-rc.8")?.version?.displayName)
        assertEquals(listOf("/latest", "/?per_page=100&page=1", "/?per_page=100&page=2"), gh.paths)
    }

    @Test fun `with no release marked Latest, Stable pages and finds the stable`() {
        val gh = FakeGitHub(nightlies(40) + android("2.2.7", false), latestTag = null)
        assertEquals("2.2.7", find(gh, "stable", "2.2.7-rc.8")?.version?.displayName)
        assertEquals(listOf("/latest", "/?per_page=100&page=1"), gh.paths)
    }

    @Test fun `a Latest stable without its exact APK makes Stable page`() {
        val gh = FakeGitHub(listOf(android("2.2.8", false, "openRS_v2.2.8-unsigned.apk")) + android("2.2.7", false),
            latestTag = "android-v2.2.8")
        assertEquals("2.2.7", find(gh, "stable", "2.2.6")?.version?.displayName)
        assertEquals(listOf("/latest", "/?per_page=100&page=1"), gh.paths)
    }

    @Test fun `an RC marked Latest is not an Android stable, so Stable pages`() {
        val gh = FakeGitHub(listOf(android("2.2.8-rc.1", false), android("2.2.7", false)), latestTag = "android-v2.2.8-rc.1")
        find(gh, "stable", "2.2.6")
        assertEquals(listOf("/latest", "/?per_page=100&page=1"), gh.paths)
    }

    @Test fun `a page that fails means no update`() {
        val gh = FakeGitHub(listOf(android("2.2.8", false)) + nightlies(150) + android("2.2.7", false), latestTag = null)
            .apply { failing = { "page=2" in it } }
        assertNull(find(gh, "stable", "2.2.6"))
    }

    @Test fun `paging follows GitHub's next link, not the page's length`() {
        // GitHub returns 50 a page here although 100 were asked: a page shorter than asked is not the end
        val gh = FakeGitHub(nightlies(70) + android("2.2.7", false), latestTag = null, maxPer = 50)
        assertEquals("2.2.7", find(gh, "stable", "2.2.6")?.version?.displayName)
        assertEquals(listOf("/latest", "/?per_page=100&page=1", "/?per_page=100&page=2"), gh.paths)
    }

    @Test fun `paging stops at 10 pages of 100`() {
        val gh = FakeGitHub(nightlies(2000), latestTag = null)
        assertNull(find(gh, "stable", "2.2.7-rc.8"))
        assertEquals(listOf("/latest") + (1..10).map { "/?per_page=100&page=$it" }, gh.paths)
    }

    @Test fun `in the fallback the choice is by version, not by list position`() {
        val gh = FakeGitHub(listOf(android("2.2.7", false)) + nightlies(3) + android("2.2.8", false), latestTag = null)
        assertEquals("2.2.8", find(gh, "stable", "2.2.6")?.version?.displayName)
    }

    // ── The APK asset by its exact name ──────────────────────────────────────────────────────────────────────────

    @Test fun `a release with two APK-like assets gives the one named exactly`() {
        for ((channel, latest) in listOf("stable" to "android-v2.2.8", "stable" to null, "beta" to null)) {
            for (order in listOf(arrayOf("openRS_v2.2.8-unsigned.apk", "openRS_v2.2.8.apk"), arrayOf("openRS_v2.2.8.apk", "openRS_v2.2.8-unsigned.apk"))) {
                val gh = FakeGitHub(listOf(android("2.2.8", false, *order)), latestTag = latest)
                val r = find(gh, channel, "2.2.7")
                assertEquals("$channel / $latest / ${order.toList()}", "https://example.invalid/2.2.8/openRS_v2.2.8.apk", r?.downloadUrl)
            }
        }
    }

    @Test fun `a release whose only APK is not named for its tag is not offered`() {
        for (channel in listOf("stable", "beta")) {
            val gh = FakeGitHub(listOf(android("2.2.8", false, "openRS_v2.2.7.apk")), latestTag = null)
            assertNull(channel, find(gh, channel, "2.2.6"))
        }
    }

    // ── Beta ─────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `Beta still receives the newest nightly`() {
        val gh = FakeGitHub(nightlies(40) + android("2.2.7", false), latestTag = "android-v2.2.7")
        assertEquals(newestOf40, find(gh, "beta", "2.2.7")?.version?.displayName)
        assertEquals(newestOf40, find(gh, "beta", "2.2.8-nightly.20270101")?.version?.displayName)
    }

    @Test fun `Beta reads one page of 100 and never asks for Latest`() {
        val gh = FakeGitHub(nightlies(40) + android("2.2.7", false), latestTag = "android-v2.2.7")
        find(gh, "beta", "2.2.7-rc.8")
        assertEquals(listOf("/?per_page=100"), gh.paths)
    }

    @Test fun `Beta sees a release behind 35 firmware releases, past GitHub's default page of 30`() {
        val gh = FakeGitHub((1..35).map { firmware("fw-pro-v1.$it") } + android("2.2.8", false), latestTag = null)
        assertEquals("2.2.8", find(gh, "beta", "2.2.7")?.version?.displayName)
    }

    // ── Rate limit ───────────────────────────────────────────────────────────────────────────────────────────────

    @Test fun `a rate limit on any request throws, on either channel`() {
        val onLatest = FakeGitHub(listOf(android("2.2.7", false)), latestTag = "android-v2.2.7").apply { rateLimited = { "/latest" in it } }
        assertThrows(RateLimitException::class.java) { find(onLatest, "stable", "2.2.6") }
        val onPage2 = FakeGitHub(nightlies(150) + android("2.2.7", false), latestTag = null).apply { rateLimited = { "page=2" in it } }
        assertThrows(RateLimitException::class.java) { find(onPage2, "stable", "2.2.6") }
        val beta = FakeGitHub(listOf(android("2.2.7", false)), latestTag = null).apply { rateLimited = { true } }
        assertThrows(RateLimitException::class.java) { find(beta, "beta", "2.2.6") }
        assertTrue(onPage2.paths.size == 3)
    }
}
