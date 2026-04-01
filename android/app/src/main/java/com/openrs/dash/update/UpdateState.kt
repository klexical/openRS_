package com.openrs.dash.update

import java.io.File

/** UI state for the in-app update flow. */
sealed class UpdateState {
    data object Idle : UpdateState()
    data object Checking : UpdateState()
    data class Available(
        val version: AppVersion,
        val downloadUrl: String,
        val releaseNotes: String,
        val isPrerelease: Boolean,
        val fileSizeBytes: Long
    ) : UpdateState()
    data class Downloading(
        val progress: Float,       // 0..1, or -1 if unknown
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : UpdateState()
    data class ReadyToInstall(val apkFile: File) : UpdateState()
    data class Error(val message: String) : UpdateState()
}
