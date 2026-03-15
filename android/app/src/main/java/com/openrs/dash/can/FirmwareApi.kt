package com.openrs.dash.can

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Thin HTTP client for the openrs-fw REST API (`POST /api/frs`).
 * Used to send drive mode and ESC commands from the app to the firmware.
 */
object FirmwareApi {

    private val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .writeTimeout(5, TimeUnit.SECONDS)
        .build()

    suspend fun setDriveMode(host: String, mode: Int): Result<Unit> =
        post(host, """{"token":"openrs","driveMode":$mode}""")

    suspend fun setEscMode(host: String, mode: Int): Result<Unit> =
        post(host, """{"token":"openrs","escMode":$mode}""")

    private suspend fun post(host: String, json: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("http://$host/api/frs")
                    .post(json.toRequestBody(JSON_MEDIA))
                    .build()
                client.newCall(request).execute().use { response ->
                    if (response.isSuccessful) Result.success(Unit)
                    else Result.failure(RuntimeException("HTTP ${response.code}"))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
