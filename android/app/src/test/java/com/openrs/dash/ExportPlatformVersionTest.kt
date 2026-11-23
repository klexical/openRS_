package com.openrs.dash

import android.app.Application
import android.content.ContextWrapper
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.EXPORT_SCHEMA_VERSION
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowBuild
import java.io.File
import java.util.zip.ZipFile

/**
 * #373 — every export names the Android release and API level, and nothing else about the phone. Production exporter on
 * Robolectric files: a ZIP each of the four kinds (diagnostic snapshot, drive with and without diagnostics, batch), read back
 * as a reader would. The release is set to a value no real phone reports, so a hard-coded or cached value fails; the device
 * and build identifiers (maker, model, fingerprint, build ID, incremental, security patch, hardware, serial …) are set to
 * sentinels that must not reach any member. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
class ExportPlatformVersionTest {
    private lateinit var root: File
    private val ctx by lazy {
        val app = ApplicationProvider.getApplicationContext<Application>()
        object : ContextWrapper(app) { override fun getFilesDir(): File = root }
    }

    private companion object {
        const val RELEASE = "93.7"   // no Android release is called this: only Build.VERSION.RELEASE can put it in the export
        val SENTINELS = mapOf(
            "MANUFACTURER" to "SentinelMaker",
            "MODEL" to "SentinelModelX9",
            "DEVICE" to "sentineldevicecodename",
            "PRODUCT" to "sentinelproductname",
            "BRAND" to "SentinelBrandName",
            "FINGERPRINT" to "sentinel/fingerprint/value:15/AP0A.000000.000/1:user/release-keys",
            "ID" to "SENTINELBUILDID.4242",
            "VERSION.INCREMENTAL" to "sentinelincremental4242",
            "VERSION.SECURITY_PATCH" to "2093-07-05",
            "HARDWARE" to "sentinelhardware",
            "BOARD" to "sentinelboard",
            "SERIAL" to "SENTINELSERIAL4242",
            "SOC_MODEL" to "SentinelSocModel",
        )
    }

    @Before fun setUp() {
        root = File(ApplicationProvider.getApplicationContext<Application>().cacheDir, "platform-${System.nanoTime()}").also { it.mkdirs() }
        ShadowBuild.setVersionRelease(RELEASE)
        ShadowBuild.setManufacturer(SENTINELS.getValue("MANUFACTURER"))
        ShadowBuild.setModel(SENTINELS.getValue("MODEL"))
        ShadowBuild.setDevice(SENTINELS.getValue("DEVICE"))
        ShadowBuild.setProduct(SENTINELS.getValue("PRODUCT"))
        ShadowBuild.setBrand(SENTINELS.getValue("BRAND"))
        ShadowBuild.setFingerprint(SENTINELS.getValue("FINGERPRINT"))
        ShadowBuild.setId(SENTINELS.getValue("ID"))
        ShadowBuild.setVersionIncremental(SENTINELS.getValue("VERSION.INCREMENTAL"))
        ShadowBuild.setVersionSecurityPatch(SENTINELS.getValue("VERSION.SECURITY_PATCH"))
        ShadowBuild.setHardware(SENTINELS.getValue("HARDWARE"))
        ShadowBuild.setBoard(SENTINELS.getValue("BOARD"))
        ShadowBuild.setSerial(SENTINELS.getValue("SERIAL"))
        ShadowBuild.setSystemOnChipModel(SENTINELS.getValue("SOC_MODEL"))
        DiagnosticLogger.sessionStart("test", 0, prefs = null)
    }

    private fun written(prefix: String): Map<String, String> {
        val zips = File(root, "diagnostics").listFiles { f -> f.name.startsWith(prefix) }.orEmpty()
        assertEquals("one $prefix ZIP (events: ${DiagnosticLogger.sessionEvents.filter { it.type.endsWith("EXPORT_ERROR") }})", 1, zips.size)
        return ZipFile(zips.single()).use { z ->
            z.entries().toList().associate { it.name to z.getInputStream(it).readBytes().toString(Charsets.ISO_8859_1) }
        }
    }

    private fun Map<String, String>.named(prefix: String): String = entries.single { it.key.startsWith(prefix) }.value

    private fun assertPlatform(where: String, o: JSONObject) {
        assertEquals("$where androidRelease", RELEASE, o.get("androidRelease"))
        assertEquals("$where androidApiLevel", Build.VERSION.SDK_INT, o.get("androidApiLevel"))   // an integer, not text
    }

    /** No member of the ZIP names the phone: the sentinels set above, in any case. */
    private fun assertNoDeviceFields(zip: Map<String, String>) {
        val hits = zip.flatMap { (name, text) ->
            SENTINELS.filter { (_, v) -> text.contains(v, ignoreCase = true) }.map { (k, _) -> "$name: Build.$k" }
        }
        assertEquals(hits.toString(), emptyList<String>(), hits)
    }

    private val drive = DriveEntity(id = 7, startTime = 1_700_000_000_000L, endTime = 1_700_000_060_000L, distanceKm = 1.0)
    private val points = listOf(
        DrivePointEntity(driveId = 7, timestamp = 1_700_000_001_000L, lat = 51.5, lng = -0.12, speedKph = 30.0, rpm = 1800),
        DrivePointEntity(driveId = 7, timestamp = 1_700_000_002_000L, lat = 51.6, lng = -0.13, speedKph = 40.0, rpm = 2200),
    )

    @Test fun `a diagnostic export names the Android release and API level in its manifest, detail JSON and summary`() {
        DiagnosticExporter.export(ctx)
        val zip = written("openrs_diag_")
        assertPlatform("manifest", JSONObject(zip.getValue("manifest.json")))
        assertPlatform("detail meta", JSONObject(zip.named("diagnostic_detail_")).getJSONObject("meta"))
        val summary = zip.named("diagnostic_summary_")
        assertTrue(summary, summary.lines().any { it == "  Platform  : Android $RELEASE (API ${Build.VERSION.SDK_INT})" })
        assertNoDeviceFields(zip)
    }

    @Test fun `a drive export names them with or without its diagnostics`() {
        DiagnosticExporter.shareDrive(ctx, drive, points, includeDiagnostics = true)
        val withDiag = written("openrs_export_")
        assertPlatform("manifest", JSONObject(withDiag.getValue("manifest.json")))
        assertPlatform("detail meta", JSONObject(withDiag.named("diagnostic_detail_")).getJSONObject("meta"))
        assertNoDeviceFields(withDiag)
        File(root, "diagnostics").listFiles()!!.forEach { it.delete() }

        DiagnosticExporter.shareDrive(ctx, drive, points, includeDiagnostics = false)
        val plain = written("openrs_export_")
        assertFalse(plain.keys.toString(), plain.keys.any { it.startsWith("diagnostic_detail_") })
        assertPlatform("manifest without diagnostics", JSONObject(plain.getValue("manifest.json")))
        assertNoDeviceFields(plain)
    }

    @Test fun `a batch export names them in its manifest`() {
        DiagnosticExporter.shareDrives(ctx, listOf(drive to points, drive.copy(id = 8, startTime = 1_700_000_100_000L) to points))
        val batch = written("openrs_batch_")
        assertPlatform("batch manifest", JSONObject(batch.getValue("manifest.json")))
        assertNoDeviceFields(batch)
    }

    @Test fun `the fields are additive - the schema stays 1 and the existing build identity is unchanged`() {
        DiagnosticExporter.export(ctx)
        val manifest = JSONObject(written("openrs_diag_").getValue("manifest.json"))
        assertEquals(1, EXPORT_SCHEMA_VERSION)
        assertEquals(EXPORT_SCHEMA_VERSION, manifest.get("exportSchemaVersion"))
        assertEquals(BuildConfig.RELEASE_NAME, manifest.get("releaseVersion"))
        assertEquals(BuildConfig.VERSION_CODE, manifest.get("versionCode"))
        assertEquals(BuildConfig.SOURCE_COMMIT, manifest.get("sourceCommit"))
        assertEquals(BuildConfig.BUILD_TYPE, manifest.get("buildType"))
    }
}
