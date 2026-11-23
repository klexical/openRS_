package com.openrs.dash

import com.openrs.dash.diagnostics.CrashReporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * #511 review: on a full heap several threads can throw at once, and the crash file name has one-second resolution.
 * Each crash must get a file of its own, so one record never overwrites or garbles another, and every name keeps the
 * `crash_telemetry_*.json` form the crash list and the export select by.
 */
class CrashFileNameTest {

    @get:Rule val tmp = TemporaryFolder()

    private val ts = "20260101_000000"   // synthetic stamp

    private fun assertCrashName(name: String) {
        assertTrue(name, name.startsWith("crash_telemetry_$ts") && name.endsWith(".json"))
    }

    @Test fun `the first crash in a second keeps the plain name`() {
        assertEquals("crash_telemetry_$ts.json", CrashReporter.crashFile(tmp.root, ts).name)
    }

    @Test fun `a second crash in the same second gets its own file`() {
        val first = CrashReporter.crashFile(tmp.root, ts)
        first.writeText("{}")
        val second = CrashReporter.crashFile(tmp.root, ts)

        assertTrue("distinct: ${first.name} / ${second.name}", first.name != second.name)
        assertCrashName(second.name)
        assertEquals("the first record is untouched", "{}", first.readText())
    }

    @Test fun `threads crashing at once each get a distinct file`() {
        val n = 8
        val pool = Executors.newFixedThreadPool(n)
        val start = CountDownLatch(1)
        val names = (1..n).map {
            pool.submit<String> {
                start.await()
                CrashReporter.crashFile(tmp.root, ts).name
            }
        }
        start.countDown()
        val got = names.map { it.get(10, TimeUnit.SECONDS) }
        pool.shutdown()

        assertEquals("one file per crash: $got", n, got.toSet().size)
        got.forEach(::assertCrashName)
    }
}
