package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.ObdMerge
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import com.openrs.dash.diagnostics.toJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * #289 (AUDIT.md r2 §3 items 3, 4, 8): where the launch-control readings come from and how old they are.
 * - Every documented 0x420 frame stamps `lcStatus420`; an undocumented one stamps nothing. Every 0x225 frame stamps `lc225`.
 * - Those stamps are CAN-only: an OBD reply's snapshot never brings one back (ObdMerge).
 * - The diagnostic export labels each source and gives its age; the 0x731 probe DIDs are labelled undocumented, with the
 *   keyless-module address. The JSON keeps `lcArmed` / `lcRpmTarget` / `assEnabled` (old readers) and adds the statuses.
 * Synthetic frames and states only.
 */
@RunWith(RobolectricTestRunner::class)
class LaunchControlSourceTest {
    @Before fun reset() { CanDecoder.resetSessionState() }

    private fun feed(vs: VehicleState, id: Int, data: ByteArray, at: Long): VehicleState {
        val decoded = CanDecoder.decode(id, data, vs) ?: return vs
        return if (decoded === vs) vs else CanDecoder.stampFresh(id, vs, decoded, at)
    }
    private fun f420(byte6: Int) = ByteArray(8).also { it[6] = byte6.toByte(); it[7] = 0xCC.toByte() }
    private fun f225(on: Boolean) = ByteArray(8).also { it[5] = (if (on) 0x08 else 0).toByte() }

    @Test fun `every documented 0x420 stamps lcStatus420, not throttled`() {
        var vs = VehicleState(isConnected = true)
        vs = feed(vs, CanDecoder.ID_DRIVE_MODE_EXT, f420(0x15), 10_000L)
        assertEquals(10_000L, vs.fieldLastUpdateMs["lcStatus420"])
        vs = feed(vs, CanDecoder.ID_DRIVE_MODE_EXT, f420(0x11), 10_600L)        // 600 ms later, inside a 1 s throttle
        assertEquals(10_600L, vs.fieldLastUpdateMs["lcStatus420"])
        assertFalse(vs.launchControlActive)
    }

    @Test fun `an undocumented 0x420 stamps nothing`() {
        val vs = feed(VehicleState(isConnected = true), CanDecoder.ID_DRIVE_MODE_EXT, f420(0x17), 10_000L)
        assertNull(vs.fieldLastUpdateMs["lcStatus420"])
        assertFalse(vs.launchControlActive)
    }

    @Test fun `every 0x225 frame stamps lc225`() {
        var vs = feed(VehicleState(isConnected = true), CanDecoder.ID_LAUNCH_CTRL, f225(true), 10_000L)
        assertEquals(10_000L, vs.fieldLastUpdateMs["lc225"])
        vs = feed(vs, CanDecoder.ID_LAUNCH_CTRL, f225(true), 10_100L)
        assertEquals(10_100L, vs.fieldLastUpdateMs["lc225"])
    }

    @Test fun `an OBD reply's snapshot never brings back a launch-control stamp`() {
        val current = VehicleState(isConnected = true)                       // the stamps were cleared (new connection)
        val reply = VehicleState(isConnected = true,
            fieldLastUpdateMs = mapOf("lcStatus420" to 9_000L, "lc225" to 9_000L, "lcArmed" to 9_500L))
        val merged = ObdMerge.merge(current, reply)
        assertNull(merged.fieldLastUpdateMs["lcStatus420"]); assertNull(merged.fieldLastUpdateMs["lc225"])
        assertEquals("a probe stamp still merges", 9_500L, merged.fieldLastUpdateMs["lcArmed"])
    }

    private fun summaryFor(vs: VehicleState): String {
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        DiagnosticLogger.lastVehicleState = vs
        return DiagnosticReportBuilder.buildSummaryText("t")
    }

    @Test fun `diagnostics label every source with its age - the probe DIDs as undocumented, keyless-module address`() {
        val t = System.currentTimeMillis()
        val vs = feed(VehicleState(isConnected = true), CanDecoder.ID_DRIVE_MODE_EXT, f420(0x15), t - 3_000L)
            .copy(lcArmed = true, lcRpmTarget = 4500, assEnabled = false)
            .let { it.copy(fieldLastUpdateMs = it.fieldLastUpdateMs + mapOf("lcArmed" to t - 5_000L, "lcRpmTarget" to t - 5_000L)) }
        val s = summaryFor(vs)
        val lines = s.lines()
        assertTrue(s, lines.none { it.trimStart().startsWith("LC Armed") || it.trimStart().startsWith("LC RPM") || it.trimStart().startsWith("ASS Active") })
        val l420 = lines.single { it.contains("0x420 bit 2") }
        assertTrue(l420, l420.contains("stale") && l420.contains("DBC LaunchControlStatus (unverified against the cluster)"))
        val l225 = lines.single { it.contains("0x225") && it.contains("MS-CAN") }
        assertTrue(l225, l225.contains("not received"))
        val de00 = lines.single { it.contains("0x731 DE00") }
        val fiveOrSix = Regex("""\b[56] s ago""")   // stamped 5 s before t; the summary reads the clock a moment later
        assertTrue(de00, de00.contains("true") && de00.contains("undocumented") && de00.contains("keyless") && fiveOrSix.containsMatchIn(de00))
        val de01 = lines.single { it.contains("0x731 DE01") }
        assertTrue(de01, de01.contains("4500") && fiveOrSix.containsMatchIn(de01) && !de01.contains("RPM"))
        val de02 = lines.single { it.contains("0x731 DE02") }
        assertTrue(de02, de02.contains("false") && de02.contains("never"))
    }

    @Test fun `the detail JSON keeps the old keys and adds the statuses, the probe source and the ages`() {
        val vs = feed(VehicleState(isConnected = true), CanDecoder.ID_DRIVE_MODE_EXT, f420(0x15), 10_000L)
            .copy(lcArmed = false, lcRpmTarget = -1)
        val j = vs.toJsonObject(nowMs = 10_500L)
        assertEquals(false, j.get("lcArmed")); assertEquals(-1, j.getInt("lcRpmTarget"))
        assertTrue("DE02 stays in the export (GARAGE's row is dropped, the probe still runs)", j.has("assEnabled"))
        val lc = j.getJSONObject("launchControl")
        assertEquals("on", lc.getString("status420"))
        assertEquals("not received", lc.getString("status225"))
        assertTrue(lc.getString("lcProbeSource"), lc.getString("lcProbeSource").contains("0x731"))
        assertEquals(500L, j.getJSONObject("fieldAgesMs").getLong("lcStatus420"))
    }
}
