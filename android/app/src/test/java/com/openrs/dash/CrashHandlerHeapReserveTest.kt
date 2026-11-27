package com.openrs.dash

import android.app.Application
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.CrashReporter
import com.openrs.dash.diagnostics.CrashTelemetryBuffer
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * #552: a crash caught when the Java heap is already full must still leave a crash file with the exception and the
 * build. `CrashReporter.install` keeps a small reserve that the installed handler lets go of before it allocates
 * anything, so the minimal record (#511) has room.
 *
 * The heap is filled for real, in a child JVM with a small fixed heap ([FullHeapChild]): the test JVM's own heap is
 * shared with Gradle's worker and other tests, so it cannot be filled reliably. The child installs the production
 * handler, fills its heap down to the last few bytes, then hands an OutOfMemoryError to the installed default handler.
 */
class CrashHandlerHeapReserveTest {

    @get:Rule val tmp = TemporaryFolder()

    @Test fun `a crash caught on a full heap still writes the minimal record`() {
        val java = File(System.getProperty("java.home"), "bin/java").path
        val proc = ProcessBuilder(
            java, "-Xmx24m", "-Xms24m", "-XX:+UseSerialGC", "-Xss512k",
            "-cp", System.getProperty("java.class.path"),
            FullHeapChild::class.java.name,
        ).directory(tmp.root).redirectErrorStream(true).start()
        val out = proc.inputStream.bufferedReader().readText()
        assertTrue("child finished in time", proc.waitFor(60, TimeUnit.SECONDS))
        assertEquals("child exit code; output:\n$out", 0, proc.exitValue())
        assertTrue("the handler returned on a full heap; output:\n$out", out.contains("heap full; handler returned"))

        // The child's Application has no files dir (android.jar stubs return null), so the handler's folder is
        // "diagnostics" under the child's working directory.
        val files = File(tmp.root, "diagnostics").listFiles { f -> f.name.startsWith("crash_telemetry_") }.orEmpty()
        assertEquals("one crash file; output:\n$out", 1, files.size)
        val text = files[0].readText()
        assertTrue("the crash file is not empty; output:\n$out", text.isNotEmpty())
        val j = JSONObject(text)
        assertEquals("java.lang.OutOfMemoryError", j.getString("exception"))
        assertEquals(BuildConfig.VERSION_NAME, j.getString("appVersion"))
        assertEquals(BuildConfig.VERSION_CODE, j.getInt("versionCode"))
    }
}

/** Run by [CrashHandlerHeapReserveTest] in its own JVM. Exit 0 once the handler has returned. */
object FullHeapChild {
    private class Node(@JvmField val next: Node?, @JvmField val payload: ByteArray?)

    /** A static field, so the filled heap stays reachable until it is let go of on purpose. */
    @JvmStatic private var head: Node? = null

    @JvmStatic fun main(args: Array<String>) {
        CrashTelemetryBuffer.push(VehicleState(rpm = 2500.0))   // loaded and in use at startup, as in the app
        // Run the handler's path once with heap to spare, so the classes it uses are loaded before the heap is full.
        // A JVM loads them from jar files, which takes far more heap than the app's dex; the test is about the heap
        // the record itself needs, not about class loading.
        val warm = File("warm-up").apply { mkdirs() }
        CrashTelemetryBuffer.flushToFile(CrashReporter.crashFile(warm, "00000000_000000"), OutOfMemoryError("w"), "w", "w")
        warm.deleteRecursively()
        CrashReporter.install(Application())
        val handler = Thread.getDefaultUncaughtExceptionHandler()!!
        val thread = Thread.currentThread()
        println("installed")
        System.out.flush()

        var oom: OutOfMemoryError? = null
        var size = 1 shl 20
        while (size >= 8) {
            try {
                while (true) head = Node(head, ByteArray(size))
            } catch (e: OutOfMemoryError) {
                if (oom == null) oom = e
                size /= 2
            }
        }
        // The last few bytes: nodes with no payload.
        try { while (true) head = Node(head, null) } catch (_: OutOfMemoryError) { }

        var failure: Throwable? = null
        try {
            handler.uncaughtException(thread, oom!!)
        } catch (t: Throwable) {
            failure = t   // the handler must not throw; reported once the heap is free again
        }

        head = null
        println(if (failure == null) "heap full; handler returned" else "heap full; handler threw $failure")
        System.out.flush()
    }
}
