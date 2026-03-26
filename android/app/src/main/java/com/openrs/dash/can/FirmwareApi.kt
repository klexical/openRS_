package com.openrs.dash.can

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/** Firmware rejected the command because a mode change is already in progress. */
class BusyException : RuntimeException("Drive mode change already in progress")

/**
 * Thin HTTP client for the openrs-fw REST API (`POST /api/frs`).
 *
 * Creates sockets through the WiFi [Network]'s socket factory so
 * Android routes traffic to the WiCAN AP even when it has no internet.
 * Without explicit binding, Android 10+ silently routes new sockets
 * through cellular, causing all commands to time out.
 */
object FirmwareApi {

    suspend fun setDriveMode(ctx: Context, host: String, mode: Int): Result<Unit> =
        post(ctx, host, """{"token":"openrs","driveMode":$mode}""", checkBusy = true)

    suspend fun setEscMode(ctx: Context, host: String, mode: Int): Result<Unit> =
        post(ctx, host, """{"token":"openrs","escMode":$mode}""")

    private fun findWifiNetwork(ctx: Context): Network? {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val active = cm.activeNetwork
        if (active != null) {
            val caps = cm.getNetworkCapabilities(active)
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return active
        }
        @Suppress("DEPRECATION")
        for (net in cm.allNetworks) {
            val caps = cm.getNetworkCapabilities(net) ?: continue
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return net
        }
        return null
    }

    private suspend fun post(
        ctx: Context, host: String, json: String, checkBusy: Boolean = false
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val port = if (':' in host) host.substringAfter(':').toInt() else 80
            val hostname = host.substringBefore(':')

            val wifi = findWifiNetwork(ctx)
            val socket = wifi?.socketFactory?.createSocket() ?: Socket()

            socket.use { s ->
                s.connect(InetSocketAddress(hostname, port), 3_000)
                s.soTimeout = 5_000

                val request = "POST /api/frs HTTP/1.1\r\n" +
                    "Host: $host\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${json.toByteArray(Charsets.ISO_8859_1).size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n" +
                    json

                s.getOutputStream().apply {
                    write(request.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }

                val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                val statusLine = reader.readLine() ?: ""
                if (!statusLine.contains("200"))
                    return@withContext Result.failure(RuntimeException(statusLine))

                // Read response body to check for busy status.
                // Parse Content-Length from headers and read exactly that many
                // bytes — do NOT loop until EOF, because the ESP-IDF HTTP server
                // may keep the connection open longer than our soTimeout.
                if (checkBusy) {
                    var contentLength = -1
                    while (true) {
                        val hdr = reader.readLine() ?: break
                        if (hdr.isBlank()) break
                        if (hdr.startsWith("Content-Length:", ignoreCase = true))
                            contentLength = hdr.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                    if (contentLength > 0) {
                        val buf = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = reader.read(buf, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                        val body = String(buf, 0, read)
                        if (body.contains("\"busy\":true"))
                            return@withContext Result.failure(BusyException())
                    }
                }

                Result.success(Unit)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
