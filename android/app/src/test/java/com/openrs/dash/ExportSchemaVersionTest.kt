package com.openrs.dash

import android.app.Application
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.EXPORT_SCHEMA_VERSION
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.util.zip.ZipFile

/**
 * #253 — every export ZIP states which contract it was written under, so Sapphire can tell a legacy export (no field) from a
 * current or a newer one. Production exporter on Robolectric files: a ZIP each of the three kinds (diagnostic snapshot, drive,
 * batch), read back as a reader would. `web/src/lib/import.exportSchema.test.tsx` imports this test's sample (below).
 * Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
class ExportSchemaVersionTest {
    private lateinit var root: File
    private val ctx by lazy {
        val app = ApplicationProvider.getApplicationContext<Application>()
        object : ContextWrapper(app) { override fun getFilesDir(): File = root }
    }

    @Before fun setUp() {
        root = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "schema-${System.nanoTime()}").also { it.mkdirs() }
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
    }

    /** The one ZIP [prefix] the export wrote: entry name → text. The URI may be null under Robolectric; the ZIP is written. */
    private fun written(prefix: String): Map<String, String> {
        val zips = File(root, "diagnostics").listFiles { f -> f.name.startsWith(prefix) }.orEmpty()
        assertEquals("one $prefix ZIP (events: ${DiagnosticLogger.sessionEvents.filter { it.type == "EXPORT_ERROR" }})", 1, zips.size)
        return ZipFile(zips.single()).use { z ->
            z.entries().toList().associate { it.name to z.getInputStream(it).bufferedReader().readText() }
        }
    }

    private fun Map<String, String>.named(prefix: String): String =
        entries.single { it.key.startsWith(prefix) }.value

    private fun Map<String, String>.manifest() = JSONObject(getValue("manifest.json"))

    private val drive = DriveEntity(id = 7, startTime = 1_700_000_000_000L, endTime = 1_700_000_060_000L, distanceKm = 1.0)
    private val points = listOf(
        DrivePointEntity(driveId = 7, timestamp = 1_700_000_001_000L, lat = 51.5, lng = -0.12, speedKph = 30.0, rpm = 1800),
        DrivePointEntity(driveId = 7, timestamp = 1_700_000_002_000L, lat = 51.6, lng = -0.13, speedKph = 40.0, rpm = 2200),
    )

    @Test fun `a diagnostic export states one version in its manifest, detail JSON and summary`() {
        DiagnosticExporter.export(ctx)
        val zip = written("openrs_diag_")

        // An integer, not text: the reader refuses anything else (the app writes one type, on purpose)
        assertEquals(EXPORT_SCHEMA_VERSION, zip.manifest().get("exportSchemaVersion"))
        assertEquals(EXPORT_SCHEMA_VERSION, JSONObject(zip.named("diagnostic_detail_")).get("exportSchemaVersion"))
        val summary = zip.named("diagnostic_summary_")
        assertTrue(summary, summary.lines().any { it == "  Schema    : export schema $EXPORT_SCHEMA_VERSION" })

        // Samples for Sapphire's import check, as they leave the exporter (web/src/lib/__fixtures__/android-export-rc8/):
        // OPENRS_EXPORT_SCHEMA_SAMPLES=<dir> ./gradlew testDebugUnitTest --tests '*ExportSchemaVersionTest'
        System.getenv("OPENRS_EXPORT_SCHEMA_SAMPLES")?.let { dir ->
            val out = File(dir).apply { mkdirs() }
            // Checked-in samples carry a fixed time, never the run's own: names, generatedAt, exportedAt (2026-01-01 00:00 UTC)
            val stamp = Regex("""\d{8}_\d{6}""")
            val exportedAt = Regex(""""exportedAt": \d+""")
            fun fixed(t: String) = exportedAt.replace(stamp.replace(t, "20260101_000000"), "\"exportedAt\": 1767225600000")
            zip.forEach { (name, text) -> File(out, fixed(name)).writeText(fixed(text)) }
        }
    }

    @Test fun `the version is additive - what readers already use stays as it was`() {
        DiagnosticLogger.lastVehicleState = VehicleState(isConnected = true, batteryVoltage = 14.0,
            fieldLastUpdateMs = mapOf("batteryVoltage" to System.currentTimeMillis()))
        DiagnosticLogger.countWrite(DiagnosticLogger.WRITE_SENT)
        DiagnosticExporter.export(ctx)
        val zip = written("openrs_diag_")

        val manifest = zip.manifest()
        assertEquals("manifest.json's own format number can't tell legacy exports apart, so it never moves", 1, manifest.getInt("version"))
        assertEquals("openRS_", manifest.getString("app"))
        assertTrue(manifest.has("appVersion") && manifest.has("appBuild") && manifest.has("exportedAt"))
        val listed = (0 until manifest.getJSONArray("files").length()).map { manifest.getJSONArray("files").getJSONObject(it).getString("type") }
        assertTrue(listed.toString(), "diagnostic_detail" in listed && "diagnostic_summary" in listed)

        val detail = JSONObject(zip.named("diagnostic_detail_"))
        assertEquals("linkIntegrity beside it, unchanged", 1, detail.getJSONObject("linkIntegrity").getJSONObject("writes").getInt("sent"))
        assertTrue(detail.getJSONObject("vehicleState").getJSONObject("fieldAgesMs").has("batteryVoltage"))
        assertFalse("one place in the JSON: the root, not the meta block", detail.getJSONObject("meta").has("exportSchemaVersion"))
    }

    @Test fun `the export schema version is 1 until a bump is decided`() {
        // A bump is deliberate: see the rule beside the constant, and raise Sapphire's KNOWN_EXPORT_SCHEMA_VERSION with it
        assertEquals(1, EXPORT_SCHEMA_VERSION)
    }

    @Test fun `a drive export states it in the manifest and in its diagnostic detail`() {
        DiagnosticExporter.shareDrive(ctx, drive, points, includeDiagnostics = true)
        val zip = written("openrs_export_")
        assertNotNull("the drive CSV is in this ZIP", zip.keys.firstOrNull { it.startsWith("drive_") && it.endsWith(".csv") })
        assertEquals(EXPORT_SCHEMA_VERSION, zip.manifest().get("exportSchemaVersion"))
        assertEquals(EXPORT_SCHEMA_VERSION, JSONObject(zip.named("diagnostic_detail_")).get("exportSchemaVersion"))
    }

    @Test fun `a drive export without diagnostics and a batch export state it in the manifest`() {
        DiagnosticExporter.shareDrive(ctx, drive, points, includeDiagnostics = false)
        val single = written("openrs_export_")
        assertFalse(single.keys.toString(), single.keys.any { it.startsWith("diagnostic_detail_") })
        assertEquals(EXPORT_SCHEMA_VERSION, single.manifest().get("exportSchemaVersion"))

        DiagnosticExporter.shareDrives(ctx, listOf(drive to points, drive.copy(id = 8, startTime = 1_700_000_100_000L) to points))
        val batch = written("openrs_batch_")
        assertEquals(2, batch.keys.count { it.startsWith("drive_") && it.endsWith(".csv") && !it.contains("knock") })
        assertEquals(EXPORT_SCHEMA_VERSION, batch.manifest().get("exportSchemaVersion"))
    }
}
