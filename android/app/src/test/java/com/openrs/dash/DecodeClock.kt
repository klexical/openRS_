package com.openrs.dash

import com.openrs.dash.can.ObdResponseParser
import com.openrs.dash.data.VehicleState

/**
 * Decode stamps a test chooses (#305). Production stamps a decode with `System.currentTimeMillis()`, and both
 * [com.openrs.dash.data.FreshnessGate] and [com.openrs.dash.data.ObdMerge] compare those stamps: two decodes inside one
 * millisecond are one decode to the gate. A test that parses twice and expects two readings must therefore not leave the
 * stamps to the wall clock (MapF40bStorageTest failed that way on CI).
 *
 * [next] is later than every stamp it gave before and than every wall-clock read made before the call, so it is also past a
 * floor the code under test read from the wall clock (a drive's start, a skipped fix). It waits for the clock to move on,
 * at most about a millisecond; it never relies on a fixed sleep being long enough.
 */
internal class DecodeClock {
    private var last = 0L

    fun next(): Long {
        val entered = System.currentTimeMillis()
        var now = entered
        while (now <= entered) { Thread.sleep(1); now = System.currentTimeMillis() }
        return maxOf(now, last + 1).also { last = it }
    }
}

/**
 * The production PCM parse of [data] against [current], with every field the reply stamped carrying [atMs] instead of the
 * wall-clock time of the parse. The reply is parsed against a copy without stamps only to learn which tags it stamps; values
 * and every other stamp are those of a parse against [current].
 */
internal fun parsePcmAt(data: ByteArray, current: VehicleState, atMs: Long, onObdUpdate: (VehicleState) -> Unit) {
    ObdResponseParser.parsePcmResponse(data, current.copy(fieldLastUpdateMs = emptyMap())) { obd ->
        onObdUpdate(obd.copy(fieldLastUpdateMs = current.fieldLastUpdateMs + obd.fieldLastUpdateMs.keys.associateWith { atMs }))
    }
}
