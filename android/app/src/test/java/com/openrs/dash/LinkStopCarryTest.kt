package com.openrs.dash

import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DiagnosticReportBuilder
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * #212: why the last connection stopped must survive the next session's start, which clears the event list; a tester's export
 * is taken after the app reconnected. Through the real logger and report builder. Synthetic.
 */
class LinkStopCarryTest {
    private fun prev() = DiagnosticLogger.sessionEvents.filter { it.type == "LINK_PREV" }.map { it.message }

    @Before fun fresh() { DiagnosticLogger.sessionStart("test", 0, prefs = null) }

    @Test fun `the real stop reason survives the next session, in text and JSON, and a later no-job stop doesn't mask it`() {
        DiagnosticLogger.event("LINK_STOP", "reason=WIFI_LOST phase=CONNECTED gen=3 rec=off demo=no")
        DiagnosticLogger.event("LINK_STOP_IDLE", "reason=SERVICE_DESTROYED phase=OFF gen=4 rec=off demo=no")
        DiagnosticLogger.sessionStart("test", 0, prefs = null)

        assertEquals(1, prev().size)
        assertTrue(prev().toString(), prev().single().startsWith("LINK_STOP +"))
        assertTrue(prev().toString(), prev().single().endsWith("ms reason=WIFI_LOST phase=CONNECTED gen=3 rec=off demo=no"))
        assertFalse("the no-job stop is not carried", prev().toString().contains("SERVICE_DESTROYED"))

        val text = DiagnosticReportBuilder.buildSummaryText("t")
        assertTrue(text, text.contains("[LINK_PREV]") && text.contains("reason=WIFI_LOST"))
        val events = JSONObject(DiagnosticReportBuilder.buildDetailJson("t")).getJSONArray("sessionEvents")
        val types = (0 until events.length()).map { events.getJSONObject(it).getString("type") }
        assertTrue(types.toString(), "LINK_PREV" in types)
    }

    @Test fun `it comes after the new session's start and its order is stable`() {
        DiagnosticLogger.event("LINK_TASK_REMOVED", "phase=CONNECTING job=yes rec=off demo=no")
        DiagnosticLogger.event("LINK_START_REFUSED", "foreground not allowed: x")
        DiagnosticLogger.event("LINK_STOP", "reason=USER_CANCEL phase=CONNECTING gen=1 rec=off demo=no")
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        val all = DiagnosticLogger.sessionEvents
        assertEquals("SESSION", all.first().type)
        assertEquals(listOf("LINK_STOP", "LINK_START_REFUSED", "LINK_TASK_REMOVED"), prev().map { it.substringBefore(" +") })
    }

    @Test fun `one hop only - a third session carries nothing`() {
        DiagnosticLogger.event("LINK_STOP", "reason=WIFI_LOST phase=CONNECTED gen=3 rec=off demo=no")
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        assertEquals(1, prev().size)
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        assertEquals(emptyList<String>(), prev())
    }

    @Test fun `only the last of each type is carried`() {
        DiagnosticLogger.event("LINK_STOP", "reason=WIFI_LOST")
        DiagnosticLogger.event("LINK_STOP", "reason=USER_DISCONNECT")
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        assertEquals(1, prev().size)
        assertTrue(prev().toString(), prev().single().contains("USER_DISCONNECT"))
    }

    @Test fun `other events are not carried`() {
        DiagnosticLogger.event("STATE", "Reconnecting")
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
        assertEquals(emptyList<String>(), prev())
    }
}
