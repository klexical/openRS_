package com.openrs.dash.can

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Thin HTTP client for the openrs-fw REST API (`POST /api/frs`).
 *
 * Uses a raw TCP socket instead of OkHttp so the request always goes
 * over the WiCAN WiFi network. Android routes OkHttp/URLConnection
 * through cellular when the active WiFi has no internet — which is
 * always the case with the WiCAN AP.
 */
object FirmwareApi {

    suspend fun setDriveMode(host: String, mode: Int): Result<Unit> =
        post(host, """{"token":"openrs","driveMode":$mode}""")

    suspend fun setEscMode(host: String, mode: Int): Result<Unit> =
        post(host, """{"token":"openrs","escMode":$mode}""")

    private suspend fun post(host: String, json: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            try {
                val port = if (':' in host) host.substringAfter(':').toInt() else 80
                val hostname = host.substringBefore(':')

                Socket().use { socket ->
                    socket.connect(InetSocketAddress(hostname, port), 3_000)
                    socket.soTimeout = 5_000

                    val request = "POST /api/frs HTTP/1.1\r\n" +
                        "Host: $host\r\n" +
                        "Content-Type: application/json\r\n" +
                        "Content-Length: ${json.length}\r\n" +
                        "Connection: close\r\n" +
                        "\r\n" +
                        json

                    socket.getOutputStream().apply {
                        write(request.toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }

                    val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                    val statusLine = reader.readLine() ?: ""
                    if (statusLine.contains("200")) Result.success(Unit)
                    else Result.failure(RuntimeException(statusLine))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
