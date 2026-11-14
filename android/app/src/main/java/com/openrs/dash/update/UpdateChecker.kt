package com.openrs.dash.update

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for available updates.
 * Network-aware: skips checks when only connected to the car's WiFi (no internet).
 */
object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/klexical/openRS_/releases"
    /** GitHub's largest page; Stable's fallback reads at most 10 of them (11 requests of the 60 an hour, #364). */
    private const val PER_PAGE = 100
    private const val MAX_PAGES = 10

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    /**
     * Finds a network with validated internet access.
     * Returns null if the device has no internet (e.g., connected to car WiFi only).
     */
    fun findInternetNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
            ) {
                return net
            }
        }
        return null
    }

    /**
     * Fetches releases from GitHub and returns the newest version available
     * for the given [channel], or null if the user is already up to date.
     *
     * @param channel "stable" or "beta"
     */
    suspend fun check(ctx: Context, channel: String): UpdateResult? {
        val network = findInternetNetwork(ctx) ?: return null

        return withContext(Dispatchers.IO) {
            try {
                val httpClient = client.newBuilder()
                    .socketFactory(network.socketFactory)
                    .build()
                findUpdate(channel, AppVersion.current()) { url ->
                    val request = Request.Builder()
                        .url(url)
                        .header("Accept", "application/vnd.github+json")
                        .build()
                    httpClient.newCall(request).execute().use { Reply(it.code, it.body?.string(), it.header("Link")) }
                }
            } catch (e: RateLimitException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    /** One HTTP reply: its status code, body and `Link` header (GitHub's pagination: `rel="next"` names the next page). */
    internal data class Reply(val code: Int, val body: String?, val link: String? = null) {
        val hasNext: Boolean get() = link?.contains("rel=\"next\"") == true
    }

    /**
     * The update for [channel] above [current], reading the release list through [get] (one call per request, #364).
     * - Stable asks for the release marked Latest first. If that is an Android stable with its APK, it decides alone:
     *   the update if it is newer, else none. Otherwise (firmware, an RC or nothing marked Latest, or no APK) Stable pages
     *   the list, [PER_PAGE] at a time, while GitHub's `Link` header names a next page (its documented signal; a short
     *   page is not documented as the last), at most [MAX_PAGES] pages; an empty page also ends it.
     *   GitHub documents `/releases/latest` as the most recent full release by creation date, but it has been seen to
     *   return the release marked Latest; the reply is trusted only when it is an Android stable with its APK, so a
     *   newer firmware release under either reading makes Stable page. Named assumption: the release checklist keeps
     *   Latest on the newest Android stable. If Latest were left on an older one, Stable would offer that one (or
     *   nothing) and not see the newer stable; no fallback pages every time.
     * - Beta reads one page of [PER_PAGE] (the newest releases come first; GitHub's default page is 30).
     * Either way the choice is by version, never by position: GitHub does not document the list's order.
     * A 403 / 429 on any request throws [RateLimitException]; any other failed reply means no update.
     */
    internal fun findUpdate(channel: String, current: AppVersion, get: (String) -> Reply): UpdateResult? {
        fun reply(url: String): Reply? {
            val reply = get(url)
            if (reply.code == 403 || reply.code == 429) throw RateLimitException()
            return reply.takeIf { it.code in 200..299 && it.body != null }
        }
        fun body(url: String): String? = reply(url)?.body
        if (channel != "stable") return parseReleases(body("$RELEASES_URL?per_page=$PER_PAGE") ?: return null, channel, current)

        body("$RELEASES_URL/latest")?.let { json ->
            val latest = runCatching { JSONObject(json) }.getOrNull()?.let { offer(it, channel) }
            if (latest != null && latest.version.isStable) return latest.takeIf { it.version > current }
        }
        var best: UpdateResult? = null
        for (page in 1..MAX_PAGES) {
            val pageReply = reply("$RELEASES_URL?per_page=$PER_PAGE&page=$page") ?: return null
            parseReleases(pageReply.body!!, channel, current)?.let { if (best == null || it.version > best!!.version) best = it }
            if (!pageReply.hasNext || JSONArray(pageReply.body).length() == 0) break
        }
        return best
    }

    /** Pick the best newer release for [channel] from one page of the GitHub releases JSON. Pure — unit-tested (#214). */
    internal fun parseReleases(json: String, channel: String, current: AppVersion = AppVersion.current()): UpdateResult? {
        val releases = JSONArray(json)
        var best: UpdateResult? = null
        for (i in 0 until releases.length()) {
            val candidate = offer(releases.getJSONObject(i), channel) ?: continue
            if (candidate.version <= current) continue
            if (best == null || candidate.version > best.version) best = candidate
        }
        return best
    }

    /**
     * What one release offers on [channel], whatever is installed: null for a draft, a firmware release (a tag that is
     * not `android-v<name>`), a release without the asset named exactly `openRS_v<name>.apk` (#364: never another APK on
     * it), a pre-release on Stable, or a nightly on Stable (#322, whatever GitHub's prerelease flag says).
     */
    private fun offer(rel: JSONObject, channel: String): UpdateResult? {
        if (rel.optBoolean("draft", false)) return null
        val isPrerelease = rel.optBoolean("prerelease", false)
        if (channel == "stable" && isPrerelease) return null
        val tag = rel.optString("tag_name", "")
        val version = AppVersion.fromTagName(tag) ?: return null
        if (channel == "stable" && version.isNightly) return null
        val apkName = "openRS_v${tag.removePrefix("android-v")}.apk"
        val assets = rel.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }.firstOrNull { it.optString("name") == apkName }
            ?: return null
        val url = apk.optString("browser_download_url", "").ifEmpty { return null }
        return UpdateResult(
            version = version,
            downloadUrl = url,
            releaseNotes = rel.optString("body", "").trim(),
            isPrerelease = isPrerelease,
            fileSizeBytes = apk.optLong("size", 0)
        )
    }
}

data class UpdateResult(
    val version: AppVersion,
    val downloadUrl: String,
    val releaseNotes: String,
    val isPrerelease: Boolean,
    val fileSizeBytes: Long
)

class RateLimitException : Exception("GitHub API rate limit exceeded. Try again later.")
