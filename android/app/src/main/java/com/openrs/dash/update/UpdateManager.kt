package com.openrs.dash.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Orchestrates the full update lifecycle: check → download → install.
 * Exposes [state] as a StateFlow for reactive UI updates.
 */
object UpdateManager {

    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    /** Whether an update is available (for badge/dot display). */
    val hasUpdate: Boolean
        get() = _state.value is UpdateState.Available
            || _state.value is UpdateState.ReadyToInstall

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * Check GitHub for a newer version.
     * @param channel "stable" or "beta"
     * @param silent If true, don't emit Checking state (for background checks).
     */
    suspend fun checkForUpdate(ctx: Context, channel: String, silent: Boolean = false) {
        if (!silent) _state.value = UpdateState.Checking
        try {
            val result = UpdateChecker.check(ctx, channel)
            if (result != null) {
                _state.value = UpdateState.Available(
                    version = result.version,
                    downloadUrl = result.downloadUrl,
                    releaseNotes = result.releaseNotes,
                    isPrerelease = result.isPrerelease,
                    fileSizeBytes = result.fileSizeBytes
                )
            } else if (!silent) {
                _state.value = UpdateState.Idle
            }
        } catch (e: RateLimitException) {
            _state.value = UpdateState.Error(e.message ?: "Rate limited")
        } catch (_: Exception) {
            if (!silent) {
                _state.value = UpdateState.Error("Network error. Check your connection.")
            }
        }
    }

    /**
     * Download the APK from [url] to the app's cache directory.
     * Emits [UpdateState.Downloading] with progress updates.
     */
    suspend fun downloadUpdate(ctx: Context, url: String) {
        withContext(Dispatchers.IO) {
            try {
                // Route through an internet-capable network (avoids car WiFi)
                val network = UpdateChecker.findInternetNetwork(ctx)
                val client = if (network != null) {
                    downloadClient.newBuilder()
                        .socketFactory(network.socketFactory)
                        .build()
                } else {
                    downloadClient
                }

                val request = Request.Builder().url(url).build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        _state.value = UpdateState.Error("Download failed (HTTP ${response.code})")
                        return@withContext
                    }
                    val body = response.body
                        ?: throw IOException("Empty response body")
                    val total = body.contentLength()

                    val dir = File(ctx.cacheDir, "updates").also { it.mkdirs() }
                    val file = File(dir, "openRS_update.apk")

                    file.outputStream().buffered().use { output ->
                        val buffer = ByteArray(8192)
                        var downloaded = 0L
                        body.byteStream().use { input ->
                            while (true) {
                                val read = input.read(buffer)
                                if (read == -1) break
                                output.write(buffer, 0, read)
                                downloaded += read
                                _state.value = UpdateState.Downloading(
                                    progress = if (total > 0) downloaded.toFloat() / total else -1f,
                                    bytesDownloaded = downloaded,
                                    totalBytes = total
                                )
                            }
                        }
                    }
                    _state.value = UpdateState.ReadyToInstall(file)
                }
            } catch (_: Exception) {
                _state.value = UpdateState.Error("Download interrupted. Try again.")
            }
        }
    }

    /**
     * Launch the system package installer for the downloaded APK.
     * Requires [android.Manifest.permission.REQUEST_INSTALL_PACKAGES].
     */
    fun installApk(ctx: Context, apkFile: File) {
        val uri: Uri = FileProvider.getUriForFile(ctx, "com.openrs.dash.provider", apkFile)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        ctx.startActivity(intent)
    }

    /** Delete any previously downloaded update APKs. */
    fun cleanupOldDownloads(ctx: Context) {
        val dir = File(ctx.cacheDir, "updates")
        if (dir.exists()) {
            dir.listFiles()?.forEach { it.delete() }
        }
    }

    /** Reset state back to Idle (e.g., after dismissing an error). */
    fun reset() {
        _state.value = UpdateState.Idle
    }
}
