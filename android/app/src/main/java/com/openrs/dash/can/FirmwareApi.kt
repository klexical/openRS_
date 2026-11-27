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
 * The text of a failed firmware command (#604): fixed wording chosen by the app, shown in the drive mode dock and written
 * to the DM_CMD / ESC_CMD events and the export. Never the adapter's reply, its status line's reason text or another
 * exception's message — at most the HTTP status code's three digits ([http]).
 */
object FirmwareFailure {
    const val NOT_CONNECTED = "Not connected to the adapter"
    const val NOT_REACHED = "Couldn't reach the adapter"
    const val NO_REPLY = "The adapter didn't answer"
    const val NOT_SENT = "The command couldn't be sent"
    const val MAYBE_NOT_DELIVERED = "The adapter may not have received the command"
    const val ADAPTER_ERROR = "The adapter reported an error"
    const val UNEXPECTED_REPLY = "Unexpected reply from the adapter"
    const val FAILED = "The command failed"

    /** A reply whose status was not 200: the code's three digits only, or [UNEXPECTED_REPLY] for a line with none. */
    fun http(code: Int?): String = code?.let { "The adapter refused the command (HTTP $it)" } ?: UNEXPECTED_REPLY

    /** The drive mode dock's line for a failed request. */
    fun driveModeFailed(reason: String): String = "Mode change failed — $reason"

    /**
     * The text for a failed command's exception: a [FirmwareCommandException]'s fixed text, any other [FAILED] with its
     * [gattTail]. No other part of a foreign exception's message is read.
     */
    fun textOf(e: Throwable?): String = (e as? FirmwareCommandException)?.message ?: (FAILED + gattTail(e))

    /**
     * " (GATT 133)" when [e]'s message names a Bluetooth GATT status, else "": the status digits are the one part of a
     * write failure's text that the export keeps as its diagnostic tail (#263), never the rest of the message (#604).
     */
    fun gattTail(e: Throwable?): String =
        GATT_STATUS.find(e?.message.orEmpty())?.groupValues?.get(1)?.let { " (GATT $it)" } ?: ""

    private val GATT_STATUS = Regex("""\bGATT (\d{1,3})\b""")
}

/** A failed firmware command. [message] is always one of the [FirmwareFailure] texts (#604). */
class FirmwareCommandException(text: String) : RuntimeException(text)

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
            ?: return Result.failure(FirmwareCommandException(FirmwareFailure.NOT_CONNECTED))

        // Drain any stale responses
        while (connection.commandResponseChannel.tryReceive().isSuccess) { }

        // A failed send is a failed command, never an exception out of the Result contract: the UI launches have no
        // catch. BleGattCore throws IOException when a write is refused, fails or isn't acknowledged (#218 closes the
        // link then). Cancellation isn't an IOException — it still ends the caller.
        try {
            transport.writeLine("AT+FRS=$key,$value\r")
        } catch (e: java.io.IOException) {
            return Result.failure(FirmwareCommandException(
                (if (WriteFailure.isNotSent(e)) FirmwareFailure.NOT_SENT else FirmwareFailure.MAYBE_NOT_DELIVERED) +
                    FirmwareFailure.gattTail(e)))
        }

        // Wait up to 5 seconds for response
        val response = withTimeoutOrNull(5_000L) {
            connection.commandResponseChannel.receive()
        } ?: return Result.failure(FirmwareCommandException(FirmwareFailure.NO_REPLY))

        return classifyReply(response)
    }

    /**
     * A `+FRS:` reply by its status word alone (#607): `OK` accepted; `BUSY` busy and `ERROR` failed, each alone or
     * before a comma; anything else unexpected. Never by "OK" anywhere in the line: an error whose reason text holds the
     * letters is not an acceptance. The firmware's own text after `ERROR,`, and any other reply, stay out of the
     * failure (#604).
     */
    private fun classifyReply(response: String): Result<Unit> {
        val status = response.removePrefix("+FRS:").trimEnd()
        return when {
            status == "OK" -> Result.success(Unit)
            status == "BUSY" || status.startsWith("BUSY,") -> Result.failure(BusyException())
            status == "ERROR" || status.startsWith("ERROR,") ->
                Result.failure(FirmwareCommandException(FirmwareFailure.ADAPTER_ERROR))
            else -> Result.failure(FirmwareCommandException(FirmwareFailure.UNEXPECTED_REPLY))
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

    // Busy is reported like a drive-mode request (#125); OK is request acceptance only — callers confirm on CAN
    suspend fun setEscMode(ctx: Context, host: String, mode: Int): Result<Unit> =
        post(ctx, host, """{"token":"openrs","escMode":$mode}""", checkBusy = true)

    /** A failed request's fixed text by the exception's kind; its message is never read (#604). */
    private fun failureText(e: Exception): String = when (e) {
        is java.net.SocketTimeoutException -> FirmwareFailure.NO_REPLY
        is WicanApi.ReplyHeadTooLongException -> FirmwareFailure.UNEXPECTED_REPLY
        is java.io.IOException -> FirmwareFailure.NOT_REACHED
        else -> FirmwareFailure.FAILED
    }

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
                val head = WicanApi.HeadReader(reader)   // status line + headers: at most MAX_HEAD_CHARS, else it throws
                // Only the status code is read: the reason text after it is the adapter's (#604)
                val code = WicanApi.httpStatusCode(head.readLine() ?: "")
                if (code != 200)
                    return@withContext Result.failure(FirmwareCommandException(FirmwareFailure.http(code)))

                // Read response body to check for busy status.
                // Parse Content-Length from headers and read that many characters (the reader counts characters,
                // Content-Length counts bytes: the same for the firmware's ASCII reply) — do NOT loop until EOF,
                // because the ESP-IDF HTTP server may keep the connection open longer than our soTimeout.
                // The claimed length never sizes a buffer: at most MAX_REPLY_CHARS are read (an Int.MAX_VALUE claim was
                // an OutOfMemoryError, an Error this catch does not hold).
                if (checkBusy) {
                    var contentLength = -1
                    while (true) {
                        val hdr = head.readLine() ?: break
                        if (hdr.isBlank()) break
                        if (hdr.startsWith("Content-Length:", ignoreCase = true))
                            contentLength = hdr.substringAfter(":").trim().toIntOrNull() ?: -1
                    }
                    if (contentLength > 0) {
                        val body = WicanApi.readUpTo(reader, minOf(contentLength, WicanApi.MAX_REPLY_CHARS))
                        if (body.contains("\"busy\":true"))
                            return@withContext Result.failure(BusyException())
                    }
                }

                Result.success(Unit)
            }
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            Result.failure(FirmwareCommandException(failureText(e)))
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
     * The most of an adapter HTTP reply's body that is read into memory, in characters. The adapter's replies are a
     * few hundred characters. A reply's own `Content-Length` never sizes a buffer, so a body that claims or sends more
     * cannot ask for an arbitrary allocation (`FirmwareHttpBodyLimitTest`). The head is bounded by [MAX_HEAD_CHARS].
     */
    const val MAX_REPLY_CHARS = 65_536

    /**
     * The most of an adapter HTTP reply's head (status line, header lines and the blank line, line ends included) that
     * is read, in characters: the WebSocket upgrade's header bound, far above an ESP-IDF httpd head. Read through
     * [HeadReader], so one endless header line cannot grow a buffer for ever (`FirmwareHttpBodyLimitTest`).
     */
    const val MAX_HEAD_CHARS = 16 * 1024

    /** A reply's head ran past [MAX_HEAD_CHARS]. Its message is fixed text, never the adapter's. */
    class ReplyHeadTooLongException : java.io.IOException("reply head over $MAX_HEAD_CHARS characters")

    /**
     * Reads a reply's head line by line from [reader], [MAX_HEAD_CHARS] in all across every line it returns. A line
     * ends at LF; a CR before it is dropped. Leaves [reader] at the first body character after the blank line.
     */
    internal class HeadReader(private val reader: BufferedReader) {
        private var used = 0

        /** The next line without its line end; null at the end of the reply before any character. */
        fun readLine(): String? {
            val sb = StringBuilder()
            while (true) {
                val c = reader.read()
                if (c < 0) return if (sb.isEmpty()) null else sb.toString()
                if (++used > MAX_HEAD_CHARS) throw ReplyHeadTooLongException()
                if (c == '\n'.code) return sb.removeSuffix("\r").toString()
                sb.append(c.toChar())
            }
        }
    }

    /** Reads at most [limit] characters from [reader], fewer if the reply ends first. [limit] is at most [MAX_REPLY_CHARS] + 1. */
    internal fun readUpTo(reader: BufferedReader, limit: Int): String {
        require(limit in 0..MAX_REPLY_CHARS + 1)
        val buf = CharArray(limit)
        var read = 0
        while (read < limit) {
            val n = reader.read(buf, read, limit - read)
            if (n < 0) break
            read += n
        }
        return String(buf, 0, read)
    }

    private const val TOO_LONG = "reply over $MAX_REPLY_CHARS characters"
    private const val HEAD_TOO_LONG = "reply head over $MAX_HEAD_CHARS characters"

    /**
     * Query the device's `/check_status` endpoint.
     * Returns null on any failure (timeout, parse error, not on WiFi).
     *
     * The reply carries the adapter's Wi-Fi and MQTT passwords in plain text, so no part of it is logged (#370): a
     * failure logs the HTTP status code or the exception's class name, never the exception, its message or the status
     * line's reason text. Android's `org.json` repeats its whole input in a parse error's message.
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
                    val head = HeadReader(reader)
                    val statusLine = head.readLine() ?: return@withContext null
                    val code = httpStatusCode(statusLine)
                    if (code != 200) {
                        logFailure("check_status", httpStatusText(code))
                        return@withContext null
                    }

                    // Skip headers until blank line
                    var contentLength = -1
                    while (true) {
                        val hdr = head.readLine() ?: break
                        if (hdr.isBlank()) break
                        if (hdr.startsWith("Content-Length:", ignoreCase = true))
                            contentLength = hdr.substringAfter(":").trim().toIntOrNull() ?: -1
                    }

                    // Read body: never more than MAX_REPLY_CHARS, whatever the reply claims or sends
                    if (contentLength > MAX_REPLY_CHARS) {
                        logFailure("check_status", TOO_LONG)
                        return@withContext null
                    }
                    val body = if (contentLength > 0) {
                        readUpTo(reader, contentLength)
                    } else {
                        readUpTo(reader, MAX_REPLY_CHARS + 1).also {
                            if (it.length > MAX_REPLY_CHARS) {
                                logFailure("check_status", TOO_LONG)
                                return@withContext null
                            }
                        }
                    }

                    DeviceStatus.fromJson(JSONObject(body))
                }
            } catch (e: ReplyHeadTooLongException) {
                logFailure("check_status", HEAD_TOO_LONG)   // fixed text: R8 renames the app's own class names
                null
            } catch (e: Exception) {
                logFailure("check_status", e.javaClass.simpleName)
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
                    val statusLine = HeadReader(reader).readLine() ?: return@withContext false
                    val code = httpStatusCode(statusLine)
                    if (code != 200) logFailure("reboot", httpStatusText(code))
                    code == 200
                }
            } catch (e: ReplyHeadTooLongException) {
                logFailure("reboot", HEAD_TOO_LONG)
                false
            } catch (e: Exception) {
                logFailure("reboot", e.javaClass.simpleName)
                false
            }
        }

    /**
     * The one log line for a failed adapter request (#370): [what] is the HTTP status code's three digits, an
     * exception's class name or one of the fixed over-limit texts (body, head), never other text the adapter sent. No throwable: its
     * message can repeat the reply.
     */
    private fun logFailure(request: String, what: String) =
        android.util.Log.d("WicanApi", "$request failed: $what")

    /**
     * The status line's three-digit code, or null when the line has none. Only the code is read: the reason text after
     * it comes from the adapter, and a "200" inside it (`HTTP/1.1 500 x200`) is not a success.
     */
    internal fun httpStatusCode(statusLine: String): Int? =
        Regex("""^HTTP/\S+\s+(\d{3})(?:\s|$)""").find(statusLine)?.groupValues?.get(1)?.toInt()

    /** "HTTP 500", or "no HTTP status code" for a line that has none: the log text for [httpStatusCode]'s result. */
    private fun httpStatusText(code: Int?): String = code?.let { "HTTP $it" } ?: "no HTTP status code"
}
