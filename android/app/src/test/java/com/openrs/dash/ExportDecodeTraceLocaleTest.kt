package com.openrs.dash

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.util.Locale

/**
 * #564: the decode-trace texts in the diagnostic detail JSON (`decodeTrace[].decoded`, `canFrameInventory.*.lastDecoded`)
 * are machine-readable like the rest of the export since #211, so their numbers must not follow the phone's locale: "."
 * decimals and ASCII digits under de_DE and pl_PL (decimal comma) and ar_EG (Arabic-Indic digits). Each frame goes
 * through the path `LinkSession.processCanFrame` takes: decode, describe, `DiagnosticLogger.logFrame`, then the export.
 */
class ExportDecodeTraceLocaleTest {

    private val saved = Locale.getDefault()
    private val locales = listOf(Locale.GERMANY, Locale("pl", "PL"), Locale("ar", "EG"))

    @Before fun setUp() { Locale.setDefault(Locale.US) }
    @After fun tearDown() { Locale.setDefault(saved) }

    /** One received frame, logged the way the link session logs it. */
    private fun receive(id: Int, data: ByteArray, current: VehicleState = VehicleState()) {
        val decoded = CanDecoder.decode(id, data, current)
        if (decoded === current) {
            val (desc, issue) = CanDecoder.describeUnchanged(id, data)
            DiagnosticLogger.logFrame(id, data, current, desc, issue)
        } else {
            val updated = CanDecoder.stampFresh(id, current, decoded!!, System.currentTimeMillis())
            DiagnosticLogger.logFrame(id, data, updated, CanDecoder.describeDecoded(id, updated, data),
                CanDecoder.validateDecoded(id, updated))
        }
    }

    private fun exportTexts(): Pair<Map<String, String>, Map<String, String>> {
        val json = JSONObject(DiagnosticReportBuilder.buildDetailJson("t"))
        val trace = json.getJSONArray("decodeTrace")
        val traceById = (0 until trace.length()).map { trace.getJSONObject(it) }
            .associate { it.getString("id") to it.getString("decoded") }
        val inv = json.getJSONObject("canFrameInventory")
        val invById = inv.keys().asSequence().associateWith { inv.getJSONObject(it).getString("lastDecoded") }
        return traceById to invById
    }

    @Test fun `decode-trace texts use a decimal point and ASCII digits in every locale`() = locales.forEach { loc ->
        Locale.setDefault(loc)
        try {
            DiagnosticLogger.sessionStart("test", 0, prefs = null)
            // 0x130 speed: bytes 6-7 BE × 0.01 → 100.05 km/h (decoded path)
            receive(0x130, byteArrayOf(0, 0, 0, 0, 0, 0, 0x27, 0x15))
            // 0x1A4 ambient: byte 4 = 0xF0 → 60.00 °C, above the plausibility ceiling, so state stays unchanged
            receive(0x1A4, byteArrayOf(0, 0, 0, 0, 0xF0.toByte(), 0, 0, 0))

            val (trace, inv) = exportTexts()
            val speed = "speedKph=100.05"
            val ambient = "ambient 0x1A4 raw=0xF0 (60.00°C unsigned) rejected"
            assertEquals("$loc decodeTrace", speed, trace.getValue("0x130"))
            assertEquals("$loc decodeTrace", ambient, trace.getValue("0x1A4"))
            assertEquals("$loc lastDecoded", speed, inv.getValue("0x130"))
            assertEquals("$loc lastDecoded", ambient, inv.getValue("0x1A4"))
        } finally { Locale.setDefault(Locale.US) }
    }
}
