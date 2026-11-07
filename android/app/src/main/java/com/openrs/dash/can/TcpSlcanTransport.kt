package com.openrs.dash.can

import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Raw TCP SLCAN transport for the MeatPi Pro (WiCAN PRO) adapter.
 *
 * Connects to the device's TCP SLCAN socket (default 192.168.0.10:35000).
 * SLCAN frames are delimited by `\r` (0x0D) — no framing overhead.
 */
class TcpSlcanTransport(
    private val host: String = "192.168.0.10",
    private val port: Int = 35000
) : SlcanTransport {

    /** Assigned before connect(), so [close] from another thread (an abort) ends a blocking connect at once (#212). */
    @Volatile private var socket: Socket? = null
    @Volatile private var closed = false
    private var inp: InputStream? = null
    private var out: OutputStream? = null
    /** Reused per-line buffer — SLCAN frames cap at 30 chars, adapter lines at [MAX_SLCAN_LINE_CHARS]. */
    private val lineBuf = StringBuilder(32)

    override val label: String get() = "TCP $host:$port"
    override val kind: String get() = "Wi-Fi TCP"
    override val stockFirmwareLabel: String get() = "MeatPi Pro"

    override suspend fun open() = withContext(Dispatchers.IO) {
        val s = Socket()
        socket = s
        if (closed) { s.close(); throw IOException("Transport closed") }   // closed before the socket existed
        s.connect(InetSocketAddress(host, port), 5_000)
        s.soTimeout = 20_000
        inp = s.getInputStream().buffered()
        out = s.getOutputStream()
    }

    override suspend fun readLine(): String? = withContext(Dispatchers.IO) {
        val input = inp ?: return@withContext null
        lineBuf.setLength(0)
        while (true) {
            val b = input.read()
            if (b == -1) return@withContext null
            if (b == 0x0D) break
            // Guard runaway lines (#127): the attempt ends and SlcanConnection reconnects. Was 33 characters, which read the
            // adapter's legal AT+FRS? state reply as a lost link (#253).
            if (lineBuf.length >= MAX_SLCAN_LINE_CHARS) {
                DiagnosticLogger.countOverlongLine()
                return@withContext null
            }
            lineBuf.append(b.toChar())
        }
        lineBuf.toString()
    }

    override suspend fun writeLine(frame: String) = withContext(Dispatchers.IO) {
        val output = out ?: return@withContext
        synchronized(output) {
            output.write(frame.toByteArray(Charsets.ISO_8859_1))
            output.flush()
        }
    }

    override fun close() {
        closed = true
        try { socket?.close() } catch (_: Exception) { }
        socket = null
        inp = null
        out = null
    }
}
