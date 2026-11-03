package com.openrs.dash

import com.openrs.dash.can.TcpSlcanTransport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Production TCP transport + abort (#212): close() from another thread ends a blocked connect or read at once instead
 * of after the 5 s connect / 20 s read timeout. (The WebSocket transport opens its socket the same way; it needs
 * android.util.Base64, so it is checked on the emulator.)
 */
class TransportAbortTest {

    @Test fun `close before open makes open fail without connecting`() = runBlocking {
        ServerSocket(0).use { server ->
            val t = TcpSlcanTransport("127.0.0.1", server.localPort)
            t.close()
            try { t.open(); fail("open after close must throw") } catch (_: java.io.IOException) { }
        }
    }

    @Test fun `close during a blocked read returns at once`() {
        ServerSocket(0).use { server ->
            val t = TcpSlcanTransport("127.0.0.1", server.localPort)
            runBlocking { t.open() }
            server.accept().use {                                       // accepted, never sends a byte
                val done = CountDownLatch(1)
                var line: String? = "unset"
                Thread {
                    line = try { runBlocking { t.readLine() } } catch (_: Exception) { null }
                    done.countDown()
                }.start()
                Thread.sleep(200)
                val t0 = System.nanoTime()
                t.close()
                assertTrue("readLine still blocked 2 s after close", done.await(2, TimeUnit.SECONDS))
                assertNull(line)
                assertTrue((System.nanoTime() - t0) < 2_000_000_000L)
            }
        }
    }

    @Test fun `close during connect ends the attempt well before the 5 s connect timeout`() {
        // 192.0.2.1 (TEST-NET-1) is never routed: the SYN goes unanswered, so connect() blocks until closed / timed out.
        // Where the host refuses it outright the attempt also ends quickly — either way never the full timeout.
        val t = TcpSlcanTransport("192.0.2.1", 35000)
        val done = CountDownLatch(1)
        Thread {
            try { runBlocking { t.open() } } catch (_: Exception) { }
            done.countDown()
        }.start()
        Thread.sleep(300)
        t.close()
        assertTrue("connect still blocked 2 s after close", done.await(2, TimeUnit.SECONDS))
    }
}
