package com.openrs.dash.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.openrs.dash.BuildConfig
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DtcResult
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Orchestrates diagnostic and drive data export: ZIP packaging, Android share
 * intents, and crash history management.
 *
 * Format generation is delegated to [DiagnosticReportBuilder] (summary text +
 * JSON detail) and [DriveExportBuilder] (GPX, CSV, drive summary, DTC report).
 */
object DiagnosticExporter {

    private const val AUTHORITY = "com.openrs.dash.provider"

    /**
     * Export the current diagnostic session to a ZIP file in the app's
     * internal files directory, then return a shareable URI via FileProvider.
     */
    fun export(ctx: Context): android.net.Uri? {
        return try {
            val dir = File(ctx.filesDir, "diagnostics").also { it.mkdirs() }
            val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(dir, "openrs_diag_$ts.zip")

            // Delete oldest ZIPs when over the user-configured limit
            val maxKeep = com.openrs.dash.ui.UserPrefsStore.prefs.value.maxDiagZips
            val existing = dir.listFiles { f -> f.name.startsWith("openrs_diag_") && f.name.endsWith(".zip") }
            if (existing != null && existing.size >= maxKeep) {
                existing.sortedBy { it.lastModified() }
                    .take((existing.size - maxKeep + 1).coerceAtLeast(0))
                    .forEach { it.delete() }
            }

            // Flush SLCAN log before bundling so we capture up-to-the-moment data
            DiagnosticLogger.flushSlcan()

            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostic_summary_$ts.txt"))
                zip.write(DiagnosticReportBuilder.buildSummaryText(ts).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("diagnostic_detail_$ts.json"))
                zip.write(DiagnosticReportBuilder.buildDetailJson(ts).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // SLCAN raw log if one was recorded this session
                val slcanFile = DiagnosticLogger.slcanLogFile
                if (slcanFile != null && slcanFile.exists() && slcanFile.length() > 0) {
                    zip.putNextEntry(ZipEntry("slcan_log_$ts.log"))
                    slcanFile.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                }

                // Crash telemetry files
                addCrashFiles(ctx, zip)

                // DID probe sessions: one CSV per scanned module
                addProbeFiles(zip)
            }

            FileProvider.getUriForFile(ctx, AUTHORITY, zipFile)
        } catch (e: Exception) {
            DiagnosticLogger.event("EXPORT_ERROR", e.message ?: "unknown")
            null
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Unified Drive Export (Room-backed DriveEntity + DrivePointEntity)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Exports a drive from Room as a unified ZIP containing drive data + diagnostics.
     * Called from MAP tab history or DIAG tab.
     */
    fun shareDrive(
        ctx: Context,
        drive: DriveEntity,
        points: List<DrivePointEntity>,
        dtcResults: List<DtcResult>? = null,
        includeDiagnostics: Boolean = true
    ) {
        try {
            val dir = File(ctx.filesDir, "diagnostics").also { it.mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

            // Prune old export ZIPs
            val maxKeep = com.openrs.dash.ui.UserPrefsStore.prefs.value.maxDiagZips
            val oldExports = dir.listFiles { f -> f.name.startsWith("openrs_export_") && f.name.endsWith(".zip") }
            if (oldExports != null && oldExports.size >= maxKeep) {
                oldExports.sortedBy { it.lastModified() }
                    .take((oldExports.size - maxKeep + 1).coerceAtLeast(0))
                    .forEach { it.delete() }
            }

            val zipFile = File(dir, "openrs_export_$ts.zip")

            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                // Drive GPX (if GPS data available)
                if (drive.hasGps && points.isNotEmpty()) {
                    zip.putNextEntry(ZipEntry("drive_$ts.gpx"))
                    zip.write(DriveExportBuilder.buildGpx(drive, points, ts).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()

                    zip.putNextEntry(ZipEntry("drive_$ts.csv"))
                    zip.write(DriveExportBuilder.buildCsv(points).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }

                // Drive summary (always included)
                zip.putNextEntry(ZipEntry("drive_summary_$ts.txt"))
                zip.write(DriveExportBuilder.buildSummary(drive, points).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                // Diagnostic data (if requested and available)
                if (includeDiagnostics) {
                    DiagnosticLogger.flushSlcan()

                    val summary = DiagnosticReportBuilder.buildSummaryText(ts)
                    if (summary.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry("diagnostic_summary_$ts.txt"))
                        zip.write(summary.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }

                    val detail = DiagnosticReportBuilder.buildDetailJson(ts)
                    if (detail.isNotEmpty()) {
                        zip.putNextEntry(ZipEntry("diagnostic_detail_$ts.json"))
                        zip.write(detail.toByteArray(Charsets.UTF_8))
                        zip.closeEntry()
                    }

                    val slcanFile = DiagnosticLogger.slcanLogFile
                    if (slcanFile?.exists() == true && slcanFile.length() > 0) {
                        zip.putNextEntry(ZipEntry("slcan_log_$ts.log"))
                        slcanFile.inputStream().buffered().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }

                    addCrashFiles(ctx, zip)
                    addProbeFiles(zip)
                }

                // DTC results (optional)
                if (!dtcResults.isNullOrEmpty()) {
                    zip.putNextEntry(ZipEntry("dtc_scan_$ts.txt"))
                    zip.write(DriveExportBuilder.buildDtcText(dtcResults).toByteArray(Charsets.UTF_8))
                    zip.closeEntry()
                }
            }

            val uri = FileProvider.getUriForFile(ctx, AUTHORITY, zipFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "openRS_ Drive Export")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "openRS_ v${BuildConfig.VERSION_NAME} drive export.\n" +
                    "${points.size} waypoints, ${"%.1f".format(drive.distanceKm)} km.\n\n" +
                    "View in Sapphire → https://klexical.github.io/openRS_/"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Handler(Looper.getMainLooper()).post {
                ctx.startActivity(Intent.createChooser(intent, "Share Drive Data"))
            }
        } catch (e: Exception) {
            DiagnosticLogger.event("DRIVE_EXPORT_ERROR", e.message ?: "unknown")
        }
    }

    /** Returns crash telemetry files sorted by newest first. */
    fun crashFiles(ctx: Context): List<File> {
        val dir = File(ctx.filesDir, "diagnostics")
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles { f -> f.name.startsWith("crash_telemetry_") && f.name.endsWith(".json") }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Delete all crash telemetry files. */
    fun clearCrashHistory(ctx: Context) {
        crashFiles(ctx).forEach { it.delete() }
    }

    /** Create and fire an Android share intent for the diagnostic ZIP. */
    fun share(ctx: Context) {
        val uri = export(ctx) ?: run {
            DiagnosticLogger.event("SHARE", "Export failed — nothing to share")
            return
        }
        val slcanLines = DiagnosticLogger.slcanLineCount
        val slcanNote  = if (slcanLines > 0) "\n• slcan_log_*.log   — raw CAN frames ($slcanLines lines, SavvyCAN/Kayak compatible)" else ""
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "openRS_ Diagnostic Report")
            putExtra(
                Intent.EXTRA_TEXT,
                "openRS_ v${BuildConfig.VERSION_NAME} diagnostic bundle.\n" +
                "• diagnostic_summary_*.txt  — human-readable report\n" +
                "• diagnostic_detail_*.json  — full machine-readable data$slcanNote\n\n" +
                "App      : v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
                "Session  : ${DiagnosticLogger.formatDuration(DiagnosticLogger.sessionDurationMs)}\n" +
                "Firmware : ${DiagnosticLogger.firmwareVersion}\n" +
                "Host     : ${DiagnosticLogger.sessionHost}:${DiagnosticLogger.sessionPort}" +
                if (DiagnosticLogger.sessionTransport.isNotEmpty())
                    "\nTransport: ${DiagnosticLogger.sessionTransport}" else ""
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        Handler(Looper.getMainLooper()).post {
            ctx.startActivity(Intent.createChooser(intent, "Share openRS_ Diagnostics"))
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Add crash telemetry JSON files to the ZIP. */
    private fun addCrashFiles(ctx: Context, zip: ZipOutputStream) {
        val diagDir = File(ctx.filesDir, "diagnostics")
        if (!diagDir.isDirectory) return
        diagDir.listFiles { f -> f.name.startsWith("crash_telemetry_") && f.name.endsWith(".json") }
            ?.forEach { crashFile ->
                zip.putNextEntry(ZipEntry(crashFile.name))
                crashFile.inputStream().buffered().use { it.copyTo(zip) }
                zip.closeEntry()
            }
    }

    /** Add DID probe session CSVs to the ZIP. */
    private fun addProbeFiles(zip: ZipOutputStream) {
        val probes = DiagnosticLogger.probeSessions
        if (probes.isEmpty()) return
        probes.forEachIndexed { idx, session ->
            val name = "did_probe_${session.module.lowercase()}_${idx + 1}.csv"
            zip.putNextEntry(ZipEntry(name))
            val csv = buildString {
                appendLine("DID,Status,ResponseHex")
                session.results.forEach { r ->
                    appendLine("0x${"%04X".format(r.did)},${r.status},${r.responseHex}")
                }
            }
            zip.write(csv.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
}
