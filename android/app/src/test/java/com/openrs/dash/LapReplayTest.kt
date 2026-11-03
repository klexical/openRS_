package com.openrs.dash

import com.openrs.dash.data.FixVerdict
import com.openrs.dash.data.LapFix
import com.openrs.dash.data.LapTimer
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The shared LapReplay route (#237) on the timer: every fix the provider delivers, and what the timer did with it.
 * Prints one REPLAY line per fix that isn't an ordinary route fix (and every verdict change), then checks the named
 * expectations — exactly 3 laps of 28.000 / 28.000 / 43.200 s after the timing pass, with and without bearings.
 * `OPENRS_LAP_ROUTE=<file>` also writes the no-bearing route as the CSV the emulator script injects.
 */
class LapReplayTest {

    private fun replay(withBearings: Boolean) {
        val label = if (withBearings) "bearings" else "no-bearings"
        val failed = mutableListOf<String>()
        fun check(name: String, ok: Boolean, detail: Any? = null) {
            println("CHECK ${if (ok) "pass" else "FAIL"} [$label] $name${if (ok || detail == null) "" else " — got $detail"}")
            if (!ok) failed += name
        }
        val timer = LapTimer()
        val fixes = LapReplay.fixes(withBearings)
        val set = fixes.first()
        timer.setStartFinish(set.lat, set.lng, set.bearing)
        val verdicts = fixes.map { f ->
            val r = timer.onFix(1, LapFix(f.lat, f.lng, f.bearing, BASE + f.tMs, f.ageMs, BASE + f.deliveredMs, LapReplay.CAN_SPEED_KPH))
            if (f.kind != LapReplay.Kind.ROUTE || r.verdict !in setOf(FixVerdict.NO_CROSSING, FixVerdict.NO_SEGMENT))
                println("REPLAY [$label] #${f.n} ${f.kind} x=${"%.1f".format(f.x)} y=${"%.1f".format(f.y)} t=${f.tMs} age=${f.ageMs} " +
                    "${f.note} -> ${r.verdict}${r.lap?.let { " lap ${it.lapNumber} ${it.lapTimeMs} ms" } ?: ""}")
            f to r.verdict
        }
        fun of(kind: LapReplay.Kind) = verdicts.filter { it.first.kind == kind }.map { it.second }.toSet()

        check("delivered ${fixes.size} fixes", fixes.size > 100, fixes.size)
        check("dwell at the line: no timing", FixVerdict.TIMING_STARTED !in of(LapReplay.Kind.DWELL), of(LapReplay.Kind.DWELL))
        check("one timing pass", verdicts.count { it.second == FixVerdict.TIMING_STARTED } == 1)
        check("replayed fix refused (OUT_OF_ORDER)", of(LapReplay.Kind.REPLAYED) == setOf(FixVerdict.OUT_OF_ORDER), of(LapReplay.Kind.REPLAYED))
        check("stale fixes refused (STALE)", of(LapReplay.Kind.STALE) == setOf(FixVerdict.STALE), of(LapReplay.Kind.STALE))
        check("nearby pass: no crossing", of(LapReplay.Kind.NEARBY) == setOf(FixVerdict.NO_CROSSING), of(LapReplay.Kind.NEARBY))
        check("repeated position: no crossing", of(LapReplay.Kind.REPEAT) == setOf(FixVerdict.NO_CROSSING), of(LapReplay.Kind.REPEAT))
        check("3 laps, 28.000 / 28.000 / 43.200 s", timer.laps.map { it.lapTimeMs } == LapReplay.EXPECTED_LAPS_MS, timer.laps.map { it.lapTimeMs })
        check("pass times match the geometry", LapReplay.PASS_TIMES_MS.zipWithNext { a, b -> b - a } == LapReplay.EXPECTED_LAPS_MS)
        if (!withBearings) check("line took the departure course (90°)", timer.startFinish?.bearing == 90f, timer.startFinish?.bearing)
        assertTrue("[$label] failed: $failed", failed.isEmpty())
    }

    @Test fun `3 valid loops after the timing pass give exactly 3 laps - fixes with bearings`() = replay(true)
    @Test fun `3 valid loops after the timing pass give exactly 3 laps - fixes without bearings`() = replay(false)

    @Test fun `write the emulator route when asked`() {
        System.getenv("OPENRS_LAP_ROUTE")?.let { LapReplay.writeCsv(File(it), LapReplay.fixes(false)) }
    }

    private companion object { const val BASE = 1_700_000_000_000L }
}
