package com.openrs.dash

import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.CrashTelemetryBuffer
import org.junit.Assert.*
import org.junit.Test
import java.io.File

/**
 * Tests for [CrashTelemetryBuffer] — ring buffer + JSON flush.
 */
class CrashTelemetryBufferTest {

    private fun pushStates(n: Int) {
        repeat(n) { i ->
            CrashTelemetryBuffer.push(
                VehicleState(rpm = (1000 + i * 100).toDouble(), speedKph = (i * 10).toDouble())
            )
        }
    }

    @Test fun `push single state succeeds`() {
        CrashTelemetryBuffer.push(VehicleState())
        // No exception = success
    }

    @Test fun `push many states succeeds`() {
        pushStates(500) // exceeds CAPACITY (300)
        // No exception = ring buffer handles overflow
    }

    @Test fun `flushToFile creates valid JSON`() {
        CrashTelemetryBuffer.push(VehicleState(rpm = 5000.0, speedKph = 120.0))
        CrashTelemetryBuffer.push(VehicleState(rpm = 5500.0, speedKph = 130.0))

        val file = File.createTempFile("crash_test_", ".json")
        try {
            CrashTelemetryBuffer.flushToFile(
                file = file,
                exception = RuntimeException("test crash"),
                threadName = "main",
                appVersion = "2.2.7-test"
            )
            val json = file.readText()
            assertTrue(json.contains("\"crashedAt\""))
            assertTrue(json.contains("\"exception\""))
            assertTrue(json.contains("\"snapshotCount\""))
            assertTrue(json.contains("\"snapshots\""))
        } finally {
            file.delete()
        }
    }

    @Test fun `flushToFile captures exception info`() {
        CrashTelemetryBuffer.push(VehicleState())
        val file = File.createTempFile("crash_test_", ".json")
        try {
            CrashTelemetryBuffer.flushToFile(
                file = file,
                exception = NullPointerException("null ref"),
                threadName = "worker-1",
                appVersion = "2.2.7"
            )
            val json = file.readText()
            assertTrue(json.contains("NullPointerException"))
            assertTrue(json.contains("null ref"))
            assertTrue(json.contains("worker-1"))
            assertTrue(json.contains("2.2.7"))
        } finally {
            file.delete()
        }
    }

    @Test fun `flushToFile includes vehicle state fields`() {
        CrashTelemetryBuffer.push(
            VehicleState(rpm = 6500.0, speedKph = 200.0, boostKpa = 250.0, throttlePct = 100.0)
        )
        val file = File.createTempFile("crash_test_", ".json")
        try {
            CrashTelemetryBuffer.flushToFile(
                file = file,
                exception = RuntimeException("test"),
                threadName = "main",
                appVersion = "test"
            )
            val json = file.readText()
            assertTrue(json.contains("\"rpm\":6500.0"))
            assertTrue(json.contains("\"speedKph\":200.0"))
            assertTrue(json.contains("\"throttle\":100.0"))
        } finally {
            file.delete()
        }
    }

    @Test fun `flushToFile escapes special characters`() {
        CrashTelemetryBuffer.push(VehicleState())
        val file = File.createTempFile("crash_test_", ".json")
        try {
            CrashTelemetryBuffer.flushToFile(
                file = file,
                exception = RuntimeException("line1\nline2\ttab\"quote"),
                threadName = "main",
                appVersion = "test"
            )
            val json = file.readText()
            // Should have escaped newlines, tabs, quotes
            assertFalse(json.contains("line1\nline2"))
            assertTrue(json.contains("\\n"))
            assertTrue(json.contains("\\t"))
            assertTrue(json.contains("\\\""))
        } finally {
            file.delete()
        }
    }

    @Test fun `flushToFile with empty buffer produces valid JSON`() {
        // Push nothing — buffer might have state from other tests
        // but test the shape is valid regardless
        val file = File.createTempFile("crash_test_", ".json")
        try {
            CrashTelemetryBuffer.flushToFile(
                file = file,
                exception = RuntimeException("empty"),
                threadName = "main",
                appVersion = "test"
            )
            val json = file.readText()
            assertTrue(json.startsWith("{"))
            assertTrue(json.trimEnd().endsWith("}"))
            assertTrue(json.contains("\"snapshots\""))
        } finally {
            file.delete()
        }
    }

    @Test fun `flushToFile does not throw on IO error`() {
        CrashTelemetryBuffer.push(VehicleState())
        // Non-writable path
        val file = File("/nonexistent/path/crash.json")
        // Should not throw — swallows exceptions
        CrashTelemetryBuffer.flushToFile(
            file = file,
            exception = RuntimeException("test"),
            threadName = "main",
            appVersion = "test"
        )
    }
}
