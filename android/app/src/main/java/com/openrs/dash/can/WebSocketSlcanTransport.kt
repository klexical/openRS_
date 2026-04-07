package com.openrs.dash.can

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom

/**
 * WebSocket SLCAN transport for the WiCAN (USB-C3) adapter.
 *
 * Connects to ws://host:port/ws, performs the HTTP upgrade handshake,
 * then exchanges SLCAN frames inside WebSocket text frames.
 * Ping/pong handling is transparent to the caller.
 */
class WebSocketSlcanTransport(
    private val host: String = "192.168.80.1",
    private val port: Int = 80
) : SlcanTransport {

    private companion object {
        const val WS_PATH = "/ws"
    }

    private val rng = SecureRandom()
    private var socket: Socket? = null
    private var inp: InputStream? = null
    private var out: OutputStream? = null

    override val label: String get() = "WebSocket ws://$host:$port$WS_PATH"
    override val stockFirmwareLabel: String get() = "WiCAN stock"

    override suspend fun open() = withContext(Dispatchers.IO) {
        val s = Socket()
        s.connect(InetSocketAddress(host, port), 5_000)
        s.soTimeout = 20_000
        socket = s
        inp = s.getInputStream()
        out = s.getOutputStream()

        sendHttpUpgrade(out!!)
        val headers = readHttpHeaders(inp!!)
        if (!headers.contains("101")) {
            throw IOException("Upgrade failed: ${headers.take(80).replace('\n', ' ')}")
        }
    }

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) {
        val input = inp ?: return@withContext null
        val output = out ?: return@withContext null
        val result = readWsFrame(input, output) ?: return@withContext null
        val (opcode, payload) = result
        if (opcode == 0x8) return@withContext null  // close frame
        payload.toString(Charsets.UTF_8).trimEnd()
    }

    override suspend fun writeLine(frame: String) = withContext(Dispatchers.IO) {
        val output = out ?: return@withContext
        sendWsText(output, frame)
    }

    override fun close() {
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        inp = null
        out = null
    }

    // ── WebSocket helpers ───────────────────────────────────────────────────

    private fun sendHttpUpgrade(out: OutputStream) {
        val keyBytes = ByteArray(16).also { rng.nextBytes(it) }
        val key = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
        val req = "GET $WS_PATH HTTP/1.1\r\n" +
            "Host: $host\r\n" +
            "Upgrade: websocket\r\n" +
            "Connection: Upgrade\r\n" +
            "Sec-WebSocket-Key: $key\r\n" +
            "Sec-WebSocket-Version: 13\r\n" +
            "\r\n"
        out.write(req.toByteArray(Charsets.ISO_8859_1))
        out.flush()
    }

    private fun readHttpHeaders(inp: InputStream): String {
        val sb = StringBuilder()
        while (true) {
            val b = inp.read()
            if (b == -1) break
            sb.append(b.toChar())
            if (sb.endsWith("\r\n\r\n")) break
        }
        return sb.toString()
    }

    private fun sendWsText(out: OutputStream, text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        check(payload.size <= 125) { "Payload too large for single-frame send" }
        synchronized(out) {
            val mask  = ByteArray(4).also { rng.nextBytes(it) }
            val frame = ByteArray(6 + payload.size)
            frame[0] = 0x81.toByte()
            frame[1] = (0x80 or payload.size).toByte()
            mask.copyInto(frame, 2)
            for (i in payload.indices) frame[6 + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            out.write(frame); out.flush()
        }
    }

    private fun sendWsPong(out: OutputStream, payload: ByteArray) {
        synchronized(out) {
            val mask  = ByteArray(4).also { rng.nextBytes(it) }
            val frame = ByteArray(6 + payload.size)
            frame[0] = 0x8A.toByte()
            frame[1] = (0x80 or payload.size).toByte()
            mask.copyInto(frame, 2)
            for (i in payload.indices) frame[6 + i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            out.write(frame); out.flush()
        }
    }

    private fun readExactly(inp: InputStream, n: Int): ByteArray {
        if (n == 0) return ByteArray(0)
        val buf = ByteArray(n); var off = 0
        while (off < n) {
            val r = inp.read(buf, off, n - off)
            if (r == -1) throw IOException("Stream closed")
            off += r
        }
        return buf
    }

    private fun readWsFrame(inp: InputStream, out: OutputStream): Pair<Int, ByteArray>? {
        while (true) {
            val b0 = inp.read(); if (b0 == -1) return null
            val b1 = inp.read(); if (b1 == -1) return null

            val opcode = b0 and 0x0F
            val masked = (b1 and 0x80) != 0
            var len    = (b1 and 0x7F)

            len = when {
                len == 126 -> {
                    val ext = readExactly(inp, 2)
                    ((ext[0].toInt() and 0xFF) shl 8) or (ext[1].toInt() and 0xFF)
                }
                len == 127 -> {
                    val lenBytes = readExactly(inp, 8)
                    val bigLen = lenBytes.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xFF) }
                    if (bigLen < 0 || bigLen > 1_048_576L) throw IOException("WS frame too large: $bigLen bytes")
                    val mask64 = if (masked) readExactly(inp, 4) else null
                    val payload64 = readExactly(inp, bigLen.toInt())
                    if (mask64 != null) {
                        for (i in payload64.indices) payload64[i] = (payload64[i].toInt() xor mask64[i % 4].toInt()).toByte()
                    }
                    when (opcode) {
                        0x9 -> { sendWsPong(out, payload64); continue }
                        0xA -> continue
                        else -> return Pair(opcode, payload64)
                    }
                }
                else -> len
            }

            val mask    = if (masked) readExactly(inp, 4) else null
            val payload = readExactly(inp, len)
            if (mask != null) {
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
            }

            when (opcode) {
                0x9 -> { sendWsPong(out, payload); continue }
                0xA -> continue
                else -> return Pair(opcode, payload)
            }
        }
    }
}
