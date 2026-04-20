package com.openrs.dash.can

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetSocketAddress
import java.net.Socket

/** Firmware rejected the command because a mode change is already in progress. */
class BusyException : RuntimeException("Drive mode change already in progress")

/**
 * Transport-agnostic interface for sending firmware commands.
 *
 * Two implementations:
 * - [FirmwareApi] (WiFi): REST POST to `/api/frs` over WiFi
 * - [BleFirmwareApi] (BLE): `AT+FRS=key,value\r` via SLCAN transport
 *
 * Both route to the same firmware handler functions — different entry points,
 * identical behaviour.
 */
interface FirmwareCommandSender {
    suspend fun setDriveMode(mode: Int): Result<Unit>
    suspend fun setEscMode(mode: Int): Result<Unit>
}

/**
 * BLE firmware command sender — sends `AT+FRS` commands via the SLCAN transport.
 *
 * Commands are written to the same transport (BLE GATT, TCP, or WebSocket) that
 * carries SLCAN frames. Responses (`+FRS:OK`, `+FRS:BUSY`, `+FRS:ERROR,msg`) are
 * routed to [SlcanConnection.commandResponseChannel] by the frame dispatch loop.
 */
class BleFirmwareApi(private val connection: SlcanConnection) : FirmwareCommandSender {

    override suspend fun setDriveMode(mode: Int): Result<Unit> =
        sendCommand("driveMode", mode.toString())

    override suspend fun setEscMode(mode: Int): Result<Unit> =
        sendCommand("escMode", mode.toString())

    private suspend fun sendCommand(key: String, value: String): Result<Unit> {
        val transport = connection.transport
            ?: return Result.failure(RuntimeException("Not connected"))

        // Drain any stale responses
        while (connection.commandResponseChannel.tryReceive().isSuccess) { }

        transport.writeLine("AT+FRS=$key,$value\r")

        // Wait up to 5 seconds for response
        val response = withTimeoutOrNull(5_000L) {
            connection.commandResponseChannel.receive()
        } ?: return Result.failure(RuntimeException("AT+FRS timeout"))

        return when {
            response.contains("OK") -> Result.success(Unit)
            response.contains("BUSY") -> Result.failure(BusyException())
            response.contains("ERROR") -> {
                val msg = response.substringAfter("ERROR,").trimEnd('\r')
                Result.failure(RuntimeException(msg))
            }
            else -> Result.failure(RuntimeException("Unexpected: $response"))
        }
    }
}

/**
 * WiFi firmware command sender — REST POST to `/api/frs`.
 * Wraps the existing [FirmwareApi] static methods.
 */
class WiFiFirmwareApi(
    private val ctx: Context,
    private val host: String
) : FirmwareCommandSender {
    override suspend fun setDriveMode(mode: Int): Result<Unit> =
        FirmwareApi.setDriveMode(ctx, host, mode)

    override suspend fun setEscMode(mode: Int): Result<Unit> =
        FirmwareApi.setEscMode(ctx, host, mode)
}

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

    internal fun findWifiNetwork(ctx: Context): Network? {
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

/**
 * Parsed response from the WiCAN `/check_status` endpoint.
 *
 * Fields map directly to the JSON keys returned by the device.
 * [obdPortVoltage] is the adapter's ADC reading of the OBD-II port supply —
 * this is NOT the car battery voltage from the PCM (DID 0x0304).
 */
data class DeviceStatus(
    val firmwareVersion: String,
    val hardwareVersion: String,
    val deviceId: String,
    val canDatarate: String,
    val canMode: String,
    val protocol: String,
    val bleStatus: String,
    val obdPortVoltage: Double,
    val sleepStatus: String,
    val sleepVolt: String,
    val wifiMode: String
) {
    companion object {
        fun fromJson(json: JSONObject): DeviceStatus {
            val voltStr = json.optString("batt_voltage", "0")
                .replace("V", "").trim()
            return DeviceStatus(
                firmwareVersion = json.optString("fw_version", ""),
                hardwareVersion = json.optString("hw_version", ""),
                deviceId        = json.optString("device_id", ""),
                canDatarate     = json.optString("can_datarate", ""),
                canMode         = json.optString("can_mode", ""),
                protocol        = json.optString("protocol", ""),
                bleStatus       = json.optString("ble_status", ""),
                obdPortVoltage  = voltStr.toDoubleOrNull() ?: 0.0,
                sleepStatus     = json.optString("sleep_status", ""),
                sleepVolt       = json.optString("sleep_volt", ""),
                wifiMode        = json.optString("wifi_mode", "")
            )
        }
    }
}

/**
 * Client for the stock WiCAN HTTP API (both USB and Pro).
 *
 * Uses the same WiFi network binding as [FirmwareApi] to ensure traffic
 * routes through the adapter's AP even when cellular is available.
 */
object WicanApi {

    /**
     * Query the device's `/check_status` endpoint.
     * Returns null on any failure (timeout, parse error, not on WiFi).
     */
    suspend fun checkStatus(ctx: Context, host: String): DeviceStatus? =
        withContext(Dispatchers.IO) {
            try {
                val port = if (':' in host) host.substringAfter(':').toInt() else 80
                val hostname = host.substringBefore(':')

                val wifi = FirmwareApi.findWifiNetwork(ctx)
                val socket = wifi?.socketFactory?.createSocket() ?: Socket()

                socket.use { s ->
                    s.connect(InetSocketAddress(hostname, port), 3_000)
                    s.soTimeout = 5_000

                    val request = "GET /check_status HTTP/1.1\r\n" +
                        "Host: $host\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"

                    s.getOutputStream().apply {
                        write(request.toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }

                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val statusLine = reader.readLine() ?: return@withContext null
                    if (!statusLine.contains("200")) return@withContext null

                    // Skip headers until blank line
                    var contentLength = -1
                    while (true) {
                        val hdr = reader.readLine() ?: break
                        if (hdr.isBlank()) break
                        if (hdr.startsWith("Content-Length:", ignoreCase = true))
                            contentLength = hdr.substringAfter(":").trim().toIntOrNull() ?: -1
                    }

                    // Read body
                    val body = if (contentLength > 0) {
                        val buf = CharArray(contentLength)
                        var read = 0
                        while (read < contentLength) {
                            val n = reader.read(buf, read, contentLength - read)
                            if (n < 0) break
                            read += n
                        }
                        String(buf, 0, read)
                    } else {
                        reader.readText()
                    }

                    DeviceStatus.fromJson(JSONObject(body))
                }
            } catch (e: Exception) {
                android.util.Log.d("WicanApi", "check_status failed", e)
                null
            }
        }

    /**
     * Reboot the WiCAN device via `POST /system_reboot`.
     * Returns true if the device acknowledged the reboot.
     */
    suspend fun reboot(ctx: Context, host: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val port = if (':' in host) host.substringAfter(':').toInt() else 80
                val hostname = host.substringBefore(':')

                val wifi = FirmwareApi.findWifiNetwork(ctx)
                val socket = wifi?.socketFactory?.createSocket() ?: Socket()

                socket.use { s ->
                    s.connect(InetSocketAddress(hostname, port), 3_000)
                    s.soTimeout = 5_000

                    val request = "POST /system_reboot HTTP/1.1\r\n" +
                        "Host: $host\r\n" +
                        "Content-Length: 0\r\n" +
                        "Connection: close\r\n" +
                        "\r\n"

                    s.getOutputStream().apply {
                        write(request.toByteArray(Charsets.ISO_8859_1))
                        flush()
                    }

                    val reader = BufferedReader(InputStreamReader(s.getInputStream()))
                    val statusLine = reader.readLine() ?: return@withContext false
                    statusLine.contains("200")
                }
            } catch (e: Exception) {
                android.util.Log.d("WicanApi", "reboot failed", e)
                false
            }
        }
}
