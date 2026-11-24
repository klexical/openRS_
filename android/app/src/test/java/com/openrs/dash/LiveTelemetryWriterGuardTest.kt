package com.openrs.dash

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Live telemetry is the only writer of the live readings and the only code that feeds or resets the running figures
 * (android/docs/adr/0001-live-telemetry-single-writer.md). Fails on any other writer in the app's sources: a new
 * MutableStateFlow of VehicleState, a write to the knock count, a feed or reset of FuelEconomy / PerformanceTimer /
 * BoostSession / BoostSessionLog / KnockSessionLog, a decoder session reset, or a call of the test-only setter.
 *
 * The performance timer's own controls (arm / cancel / finish at 60, the PERF tab's buttons) are the driver's commands,
 * not a feed or reset, and stay where they are.
 */
class LiveTelemetryWriterGuardTest {
    private val root = File("src/main/java/com/openrs/dash")          // the unit-test working directory is the module (app/)
    private val home = "data/LiveTelemetry.kt"

    /** A running figure's own file defines the calls it is fed and reset by. */
    private val definitions = setOf(
        "data/FuelEconomy.kt", "data/PerformanceTimer.kt", "data/BoostRule.kt", "data/BoostSessionLog.kt",
        "data/KnockSessionLog.kt", "can/CanDecoder.kt",
    )

    private val writes = listOf(
        Regex("""MutableStateFlow\s*(<\s*VehicleState\s*>)?\s*\(\s*VehicleState"""),
        Regex("""MutableStateFlow\s*<\s*VehicleState\s*>"""),
        Regex("""knockEventCount\s*\.\s*(value\s*=|update)"""),
        Regex("""\bFuelEconomy\s*\.\s*(onUpdate|reset)\s*\("""),
        Regex("""\bPerformanceTimer\s*\.\s*(onSpeedUpdate|resetSession)\s*\("""),
        Regex("""\bBoostSession\s*\.\s*(onState|reset|resetPeaks)\s*\("""),
        Regex("""\bBoostSessionLog\s*\.\s*(onState|reset)\s*\("""),
        Regex("""\bKnockSessionLog\s*\.\s*(onState|reset)\s*\("""),
        Regex("""\bCanDecoder\s*\.\s*resetSessionState\s*\("""),
        Regex("""\.showForTest\s*\("""),
    )

    private fun code(line: String): String {
        val t = line.trimStart()
        if (t.startsWith("*") || t.startsWith("/*") || t.startsWith("//")) return ""
        return line.substringBefore(" //")
    }

    @Test fun `only Live telemetry writes the live readings or feeds and resets the running figures`() {
        assertTrue("source root not found from ${File(".").absolutePath}", root.isDirectory)
        val found = root.walkTopDown().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            val path = f.relativeTo(root).path.replace(File.separatorChar, '/')
            if (path == home || path in definitions) emptySequence()
            else f.readLines().asSequence().mapIndexedNotNull { i, line ->
                val c = code(line)
                writes.firstOrNull { it.containsMatchIn(c) }?.let { "$path:${i + 1}: ${line.trim()}" }
            }
        }.toList()
        assertEquals("writers outside $home:\n" + found.joinToString("\n"), emptyList<String>(), found)
    }

    @Test fun `the guard sees a writer`() {
        // The patterns must match the calls they guard against (a guard that matches nothing passes vacuously)
        val samples = listOf(
            "val vehicleState = MutableStateFlow(VehicleState())", "app.knockEventCount.value = 0",
            "FuelEconomy.reset()", "PerformanceTimer.onSpeedUpdate(a, b, c)", "com.openrs.dash.data.BoostSession.resetPeaks()",
            "BoostSessionLog.onState(vs, now)", "KnockSessionLog.reset()", "CanDecoder.resetSessionState()",
            "x.showForTest(vs)", "private val state: MutableStateFlow<VehicleState>,",
        )
        samples.forEach { s -> assertTrue(s, writes.any { it.containsMatchIn(code(s)) }) }
        val text = File(root, home).readText()
        assertTrue("the home file feeds what the guard names", writes.count { it.containsMatchIn(text) } >= 7)
    }
}
