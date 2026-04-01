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
import java.util.concurrent.TimeUnit

/**
 * Checks GitHub Releases for available updates.
 * Network-aware: skips checks when only connected to the car's WiFi (no internet).
 */
object UpdateChecker {

    private const val RELEASES_URL =
        "https://api.github.com/repos/klexical/openRS_/releases"

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

                val request = Request.Builder()
                    .url(RELEASES_URL)
                    .header("Accept", "application/vnd.github+json")
                    .build()

                httpClient.newCall(request).execute().use { response ->
                    if (response.code == 403 || response.code == 429) {
                        throw RateLimitException()
                    }
                    if (!response.isSuccessful) return@withContext null

                    val body = response.body?.string() ?: return@withContext null
                    parseReleases(body, channel)
                }
            } catch (e: RateLimitException) {
                throw e
            } catch (_: Exception) {
                null
            }
        }
    }

    private fun parseReleases(json: String, channel: String): UpdateResult? {
        val releases = JSONArray(json)
        val current = AppVersion.current()
        var best: UpdateResult? = null

        for (i in 0 until releases.length()) {
            val rel = releases.getJSONObject(i)
            if (rel.optBoolean("draft", false)) continue

            val isPrerelease = rel.optBoolean("prerelease", false)
            // Stable channel: skip pre-releases. Beta channel: consider all.
            if (channel == "stable" && isPrerelease) continue

            val tagName = rel.optString("tag_name", "")
            val version = AppVersion.fromTagName(tagName) ?: continue

            // Only consider versions newer than what's installed
            if (version <= current) continue

            // Find the APK asset
            val assets = rel.optJSONArray("assets") ?: continue
            var apkUrl: String? = null
            var apkSize = 0L
            for (j in 0 until assets.length()) {
                val asset = assets.getJSONObject(j)
                val name = asset.optString("name", "")
                if (name.startsWith("openRS_") && name.endsWith(".apk")) {
                    apkUrl = asset.optString("browser_download_url", "")
                    apkSize = asset.optLong("size", 0)
                    break
                }
            }
            if (apkUrl.isNullOrEmpty()) continue

            val releaseNotes = rel.optString("body", "").trim()

            val candidate = UpdateResult(
                version = version,
                downloadUrl = apkUrl,
                releaseNotes = releaseNotes,
                isPrerelease = isPrerelease,
                fileSizeBytes = apkSize
            )

            if (best == null || version > best.version) {
                best = candidate
            }
        }
        return best
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
