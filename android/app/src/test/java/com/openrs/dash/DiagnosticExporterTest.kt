package com.openrs.dash

import com.openrs.dash.diagnostics.DiagnosticExporter
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.lang.reflect.Method

/**
 * Tests for [DiagnosticExporter] helper methods — manifest generation and
 * file type inference. Uses reflection to access private helpers since they
 * are tightly coupled to the export pipeline and worth testing directly.
 */
class DiagnosticExporterTest {

    // Access private methods via reflection
    private val inferTypeMethod: Method = DiagnosticExporter::class.java
        .getDeclaredMethod("inferType", String::class.java)
        .apply { isAccessible = true }

    private val buildManifestMethod: Method = DiagnosticExporter::class.java
        .getDeclaredMethod("buildManifest", List::class.java)
        .apply { isAccessible = true }

    private fun inferType(name: String): String =
        inferTypeMethod.invoke(DiagnosticExporter, name) as String

    @Suppress("UNCHECKED_CAST")
    private fun buildManifest(files: List<Pair<String, String>>): String =
        buildManifestMethod.invoke(DiagnosticExporter, files) as String

    // ═══════════════════════════════════════════════════════════════════════
    // inferType tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test fun `inferType diagnostic summary`() =
        assertEquals("diagnostic_summary", inferType("diagnostic_summary_20240101.txt"))

    @Test fun `inferType diagnostic detail`() =
        assertEquals("diagnostic_detail", inferType("diagnostic_detail_20240101.json"))

    @Test fun `inferType slcan log`() =
        assertEquals("slcan_log", inferType("slcan_log_20240101.log"))

    @Test fun `inferType drive profile`() =
        assertEquals("drive_profile", inferType("drive_profile_20240101.json"))

    @Test fun `inferType drive summary`() =
        assertEquals("drive_summary", inferType("drive_summary_20240101.txt"))

    @Test fun `inferType drive gpx`() =
        assertEquals("drive_gpx", inferType("drive_20240101.gpx"))

    @Test fun `inferType drive csv`() =
        assertEquals("drive_csv", inferType("drive_20240101.csv"))

    @Test fun `inferType dtc json`() =
        assertEquals("dtc_json", inferType("dtc_scan_20240101.json"))

    @Test fun `inferType dtc text`() =
        assertEquals("dtc_text", inferType("dtc_scan_20240101.txt"))

    @Test fun `inferType did probe`() =
        assertEquals("did_probe", inferType("did_probe_pcm_1.csv"))

    @Test fun `inferType crash telemetry`() =
        assertEquals("crash_telemetry", inferType("crash_telemetry_20240101.json"))

    @Test fun `inferType manifest`() =
        assertEquals("manifest", inferType("manifest.json"))

    @Test fun `inferType unknown file`() =
        assertEquals("unknown", inferType("readme.txt"))

    @Test fun `inferType unknown prefix`() =
        assertEquals("unknown", inferType("notes_20240101.txt"))

    // ═══════════════════════════════════════════════════════════════════════
    // buildManifest tests
    // ═══════════════════════════════════════════════════════════════════════

    @Test fun `manifest has correct version`() {
        val json = JSONObject(buildManifest(emptyList()))
        assertEquals(1, json.getInt("version"))
    }

    @Test fun `manifest has app identifier`() {
        val json = JSONObject(buildManifest(emptyList()))
        assertEquals("openRS_", json.getString("app"))
    }

    @Test fun `manifest has exportedAt timestamp`() {
        val before = System.currentTimeMillis()
        val json = JSONObject(buildManifest(emptyList()))
        val after = System.currentTimeMillis()
        val exportedAt = json.getLong("exportedAt")
        assertTrue(exportedAt in before..after)
    }

    @Test fun `manifest files array matches input`() {
        val files = listOf(
            "drive_20240101.csv" to "drive_csv",
            "drive_20240101.gpx" to "drive_gpx",
            "diagnostic_summary_20240101.txt" to "diagnostic_summary"
        )
        val json = JSONObject(buildManifest(files))
        val arr = json.getJSONArray("files")
        assertEquals(3, arr.length())

        assertEquals("drive_20240101.csv", arr.getJSONObject(0).getString("name"))
        assertEquals("drive_csv", arr.getJSONObject(0).getString("type"))
        assertEquals("drive_20240101.gpx", arr.getJSONObject(1).getString("name"))
        assertEquals("drive_gpx", arr.getJSONObject(1).getString("type"))
    }

    @Test fun `manifest empty files array`() {
        val json = JSONObject(buildManifest(emptyList()))
        assertEquals(0, json.getJSONArray("files").length())
    }

    @Test fun `manifest is valid JSON`() {
        val files = listOf(
            "a.csv" to "drive_csv",
            "b.json" to "diagnostic_detail"
        )
        // Should not throw
        val json = JSONObject(buildManifest(files))
        assertTrue(json.has("version"))
        assertTrue(json.has("files"))
    }

    @Test fun `manifest has appVersion and appBuild`() {
        val json = JSONObject(buildManifest(emptyList()))
        assertTrue(json.has("appVersion"))
        assertTrue(json.has("appBuild"))
    }
}
