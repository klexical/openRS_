package com.openrs.dash

import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The synthetic lap route shared by the lap tests (#237) and the emulator script (`OPENRS_LAP_ROUTE=<file>` writes it as
 * CSV). Coordinates are generated around a synthetic S/F point at sea; nothing here comes from a recording.
 *
 * Local metres: x east, y north, S/F at the origin, the line crossed eastbound (bearing 90°). The car runs at a constant
 * [SPEED_MS] and the fix clock is arc length / speed, so every true crossing time — and every expected lap — is exact.
 * Fixes are 1 s apart on a grid offset 10 m from the line (crossings fall between samples), except forced samples at
 * flagged waypoints.
 *
 *  1. SET S/F at the origin (the TRIP button, at the car's position), then 4 fixes dwelling there (jitter ±2 m, CAN still
 *     says 72 km/h — the T6 shape).
 *  2. Out east and round a 200 × 80 m rectangle; the first eastbound pass of x = 0 is the timing pass.
 *  3. Loop 1 (560 m, 28.000 s): its post-crossing fix is then delivered again (replayed, same fix time).
 *  4. Loop 2 (28.000 s): on the far side, a stale copy of loop 1's two crossing fixes arrives (fix age > 20 s); the two
 *     fixes within 15 m of the line on its crossing pass are missing (provider dropped them) — the crossing lies between
 *     samples 60 m apart, both outside the old 15 m circle.
 *  5. Loop 3 (864 m, 43.200 s): approaches the line to x = −8 m (inside the old 15 m circle, heading east) and turns
 *     south without crossing — a nearby pass; round a 92 × 60 m block, back up the west side, then crosses.
 *  6. Stops at x = +6 m; the provider keeps repeating that position for 12 s with advancing fix times (T6).
 * Expected: timing pass, then exactly 3 laps of 28.000 / 28.000 / 43.200 s.
 */
object LapReplay {
    /** The S/F point (synthetic, open sea). */
    const val SF_LAT = 0.5
    const val SF_LNG = 0.5
    const val SPEED_MS = 20.0
    const val CAN_SPEED_KPH = 72.0
    val EXPECTED_LAPS_MS = listOf(28_000L, 28_000L, 43_200L)

    enum class Kind { SET_SF, DWELL, ROUTE, NEARBY, REPLAYED, STALE, REPEAT }

    /**
     * One fix the provider delivers. [tMs] = the fix's own time (ms from the scenario start); [deliveredMs] = when the
     * provider hands it over (the age is the difference); [bearing] = the course of travel when the variant reports one.
     */
    data class Fix(
        val n: Int, val kind: Kind, val x: Double, val y: Double, val tMs: Long, val deliveredMs: Long,
        val bearing: Float?, val note: String,
    ) {
        val ageMs get() = deliveredMs - tMs
        val lat get() = SF_LAT + y / M_PER_DEG
        val lng get() = SF_LNG + x / (M_PER_DEG * cos(Math.toRadians(SF_LAT)))
    }

    /** Metres per degree on the sphere LapTimer uses (R = 6,371 km), so local metres round-trip exactly. */
    private val M_PER_DEG = Math.toRadians(1.0) * 6_371_000.0

    /** [withBearings]: every fix reports its course (a phone GPS); false: none does (emulator `geo fix`). */
    fun fixes(withBearings: Boolean): List<Fix> {
        val out = mutableListOf<Fix>()
        fun add(kind: Kind, x: Double, y: Double, t: Long, delivered: Long = t, course: Float?, note: String = "") {
            out += Fix(out.size, kind, x, y, t, delivered, if (withBearings) course else null, note)
        }
        add(Kind.SET_SF, 0.0, 0.0, 0, course = 90f, note = "SET S/F here")
        listOf(1.5 to 0.5, -1.0 to -0.5, 2.0 to 1.0, -0.5 to 0.0).forEachIndexed { i, (dx, dy) ->
            add(Kind.DWELL, dx, dy, 1_000L + i * 1_000L, course = 90f, note = "dwell at the line")
        }

        // Route legs from the origin; t0 = 5 s. A waypoint flagged `forced` gets a fix exactly there.
        data class Wp(val x: Double, val y: Double, val forced: Boolean = false, val kind: Kind = Kind.ROUTE, val note: String = "")
        val loop = listOf(Wp(100.0, 0.0), Wp(100.0, 80.0), Wp(-100.0, 80.0), Wp(-100.0, 0.0))
        val route = buildList {
            addAll(loop)                                                         // out east, round to the west end
            add(Wp(100.0, 0.0, note = "pass 0: timing")); addAll(loop.drop(1))   // timing pass, loop 1
            add(Wp(100.0, 0.0, note = "pass 1: lap 1")); addAll(loop.drop(1))    // lap 1, loop 2
            add(Wp(100.0, 0.0, note = "pass 2: lap 2")); addAll(loop.drop(1))    // lap 2 (dropped fixes), loop 3
            add(Wp(-8.0, 0.0, forced = true, kind = Kind.NEARBY, note = "nearby pass, turns away"))
            add(Wp(-8.0, -60.0)); add(Wp(-100.0, -60.0)); add(Wp(-100.0, 0.0))
            add(Wp(6.0, 0.0, forced = true, note = "pass 3: lap 3, stops here"))
        }

        val startT = 5_000L
        var px = 0.0; var py = 0.0; var arc = 0.0
        var nextSampleArc = 10.0                                                 // grid offset: crossings between samples
        var pass = 0                                                             // eastbound passes of x = 0 so far
        var replayedDone = false; var staleDone = false
        fun tAt(a: Double) = startT + Math.round(a / SPEED_MS * 1000.0)
        for (wp in route) {
            val len = hypot(wp.x - px, wp.y - py)
            val course = ((Math.toDegrees(atan2(wp.x - px, wp.y - py)) + 360.0) % 360.0).toFloat()
            while (nextSampleArc < arc + len - 1e-9) {
                val f = (nextSampleArc - arc) / len
                val x = px + f * (wp.x - px); val y = py + f * (wp.y - py)
                val t = tAt(nextSampleArc)
                nextSampleArc += SPEED_MS
                val onPassLeg = y == 0.0 && course == 90f
                if (onPassLeg && pass == 2 && kotlin.math.abs(x) < 15.0) continue  // loop 2: provider dropped these
                add(Kind.ROUTE, x, y, t, course = course)
                if (onPassLeg && x > 0 && x < 15.0 && pass == 1 && !replayedDone) {  // right after the lap-1 crossing
                    replayedDone = true
                    val last = out.last()
                    out += last.copy(n = out.size, kind = Kind.REPLAYED, deliveredMs = t + 300, note = "replay of fix ${last.n}")
                }
                if (pass == 2 && y == 80.0 && !staleDone) {                      // loop 2, far side: stale copies
                    staleDone = true
                    out.filter { it.kind == Kind.ROUTE && it.y == 0.0 && kotlin.math.abs(it.x) < 15.0 }
                        .takeLast(2).forEach { old ->
                            out += old.copy(n = out.size, kind = Kind.STALE, deliveredMs = t + 200, note = "stale copy of fix ${old.n}")
                        }
                }
            }
            if (py == 0.0 && wp.y == 0.0 && px < 0 && wp.x >= 0) pass++                  // this leg crossed x = 0 eastbound
            arc += len; px = wp.x; py = wp.y
            if (wp.forced) {
                add(wp.kind, wp.x, wp.y, tAt(arc), course = course, note = wp.note)
                nextSampleArc = arc + SPEED_MS                                    // the 1 s grid restarts here
            }
        }
        // Stopped at x = +6 m: the provider repeats the last position with advancing fix times (CAN still 72 km/h)
        val stop = out.last()
        for (i in 1..12) add(Kind.REPEAT, stop.x, stop.y, stop.tMs + i * 1_000L, course = 90f, note = "repeated position")
        return out
    }

    /** Fix-clock times of the four eastbound passes of the line (timing pass + 3 laps), from the route geometry. */
    val PASS_TIMES_MS: List<Long> by lazy {
        val loopM = 560.0; val toFirst = 100.0 + 80.0 + 200.0 + 80.0 + 100.0
        val p0 = toFirst; val p1 = p0 + loopM; val p2 = p1 + loopM; val p3 = p2 + loopM + 304.0
        listOf(p0, p1, p2, p3).map { 5_000L + Math.round(it / SPEED_MS * 1000.0) }
    }

    /** CSV for the emulator script: n,kind,lat,lng,t_ms,bearing,note (only fixes `geo fix` can inject: no replay / stale). */
    fun writeCsv(file: File, fixes: List<Fix>) = file.printWriter().use { w ->
        w.println("n,kind,lat,lng,x_m,y_m,t_ms,note")
        fixes.filter { it.kind != Kind.REPLAYED && it.kind != Kind.STALE }.forEach {
            w.println("${it.n},${it.kind},${"%.7f".format(java.util.Locale.ROOT, it.lat)},${"%.7f".format(java.util.Locale.ROOT, it.lng)}," +
                "${"%.1f".format(java.util.Locale.ROOT, it.x)},${"%.1f".format(java.util.Locale.ROOT, it.y)},${it.tMs},${it.note}")
        }
    }
}
