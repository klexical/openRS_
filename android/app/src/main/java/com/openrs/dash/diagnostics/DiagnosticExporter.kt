package com.openrs.dash.diagnostics

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import androidx.core.content.FileProvider
import com.openrs.dash.BuildConfig
import com.openrs.dash.data.DriveBookmarkEntity
import com.openrs.dash.data.DriveDatabase
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DtcResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
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
/** Export progress state for UI feedback. */
sealed class ExportProgress {
    data object Idle : ExportProgress()
    data class Packaging(val label: String = "Packaging...") : ExportProgress()
    data object Sharing : ExportProgress()
    data object Done : ExportProgress()
    data class Error(val message: String) : ExportProgress()
}

/** Components that can be toggled in the selective export sheet. */
enum class ExportComponent(val label: String) {
    DRIVE_CSV("Drive CSV"),
    DRIVE_GPX("Drive GPX"),
    SUMMARY("Drive Summary"),
    PROFILE_JSON("Profile JSON"),
    DIAGNOSTICS("Diagnostics"),
    SLCAN_LOG("SLCAN Log"),
    DTC_REPORT("DTC Report"),
    RACECHRONO_CSV("RaceChrono CSV"),
    TRACKADDICT_CSV("TrackAddict CSV")
}

object DiagnosticExporter {

    private const val AUTHORITY = "com.openrs.dash.provider"

    private val appVersion: String get() = BuildConfig.VERSION_NAME ?: ""
    private val appBuild: Int get() = BuildConfig.VERSION_CODE

    private val _exportProgress = MutableStateFlow<ExportProgress>(ExportProgress.Idle)
    val exportProgress: StateFlow<ExportProgress> = _exportProgress.asStateFlow()

    /**
     * Export the current diagnostic session to a ZIP file in the app's
     * internal files directory, then return a shareable URI via FileProvider.
     *
     * If [driveSnapshot] is non-null, `drive_*.csv` / `drive_*.gpx` /
     * `drive_summary_*.txt` are folded into the same ZIP so Sapphire can parse
     * the trip out of a diagnostic-tab export.
     */
    fun export(
        ctx: Context,
        driveSnapshot: Pair<DriveEntity, List<DrivePointEntity>>? = null
    ): android.net.Uri? {
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
                val entries = mutableListOf<Pair<String, String>>()

                fun addEntry(name: String, content: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                    entries += name to inferType(name)
                }

                addEntry("diagnostic_summary_$ts.txt",
                    DiagnosticReportBuilder.buildSummaryText(ts).toByteArray(Charsets.UTF_8))
                addEntry("diagnostic_detail_$ts.json",
                    DiagnosticReportBuilder.buildDetailJson(ts).toByteArray(Charsets.UTF_8))

                // SLCAN raw log if one was recorded this session
                val slcanFile = DiagnosticLogger.slcanLogFile
                if (slcanFile != null && slcanFile.exists() && slcanFile.length() > 0) {
                    val name = "slcan_log_$ts.log"
                    zip.putNextEntry(ZipEntry(name))
                    slcanFile.inputStream().buffered().use { it.copyTo(zip) }
                    zip.closeEntry()
                    entries += name to inferType(name)
                }

                // Active drive (when a drive is being recorded at export time).
                // Sapphire's import expects drive_*.csv / drive_*.gpx filenames.
                if (driveSnapshot != null) {
                    val (drive, points) = driveSnapshot
                    if (points.isNotEmpty()) {
                        if (drive.hasGps) {
                            addEntry("drive_$ts.gpx",
                                DriveExportBuilder.buildGpx(drive, points, ts).toByteArray(Charsets.UTF_8))
                        }
                        addEntry("drive_$ts.csv",
                            DriveExportBuilder.buildCsv(points).toByteArray(Charsets.UTF_8))
                    }
                    addEntry("drive_summary_$ts.txt",
                        DriveExportBuilder.buildSummary(drive, points).toByteArray(Charsets.UTF_8))
                }

                // Crash telemetry files
                addCrashFiles(ctx, zip)

                // DTC scan results (if available from this session)
                val dtcResults = DiagnosticLogger.lastDtcResults
                if (!dtcResults.isNullOrEmpty()) {
                    addEntry("dtc_scan_$ts.txt",
                        DriveExportBuilder.buildDtcText(dtcResults).toByteArray(Charsets.UTF_8))
                    addEntry("dtc_scan_$ts.json",
                        DriveExportBuilder.buildDtcJson(dtcResults).toByteArray(Charsets.UTF_8))
                }

                // DID probe sessions: one CSV per scanned module
                addProbeFiles(zip)

                // Manifest (last entry — lists all files above)
                addEntry("manifest.json", buildManifest(entries).toByteArray(Charsets.UTF_8))
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
        includeDiagnostics: Boolean = true,
        bookmarks: List<DriveBookmarkEntity>? = null,
        components: Set<ExportComponent> = ExportComponent.entries.toSet()
    ) {
        _exportProgress.value = ExportProgress.Packaging()
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
                val entries = mutableListOf<Pair<String, String>>()

                fun addEntry(name: String, content: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                    entries += name to inferType(name)
                }

                // Drive GPX (if GPS data available)
                if (drive.hasGps && points.isNotEmpty()) {
                    if (ExportComponent.DRIVE_GPX in components) {
                        addEntry("drive_$ts.gpx",
                            DriveExportBuilder.buildGpx(drive, points, ts).toByteArray(Charsets.UTF_8))
                    }
                    if (ExportComponent.DRIVE_CSV in components) {
                        addEntry("drive_$ts.csv",
                            DriveExportBuilder.buildCsv(points).toByteArray(Charsets.UTF_8))
                    }
                }

                // Drive summary
                if (ExportComponent.SUMMARY in components) {
                    addEntry("drive_summary_$ts.txt",
                        DriveExportBuilder.buildSummary(drive, points).toByteArray(Charsets.UTF_8))
                }

                // Sapphire profile JSON (structured import for web dashboard)
                if (ExportComponent.PROFILE_JSON in components && points.isNotEmpty()) {
                    val dao = DriveDatabase.getInstance(ctx).driveDao()
                    val bm = bookmarks ?: try { dao.getBookmarks(drive.id) } catch (_: Exception) { emptyList() }
                    val lapSessions = try { dao.getLapSessionsForDrive(drive.id) } catch (_: Exception) { emptyList() }
                    val laps = lapSessions.flatMap { try { dao.getLaps(it.id) } catch (_: Exception) { emptyList() } }
                    val runs = try { dao.getRecentPerfRuns(50).filter { it.driveId == drive.id } } catch (_: Exception) { emptyList() }
                    addEntry("drive_profile_$ts.json",
                        DriveExportBuilder.buildProfileJson(drive, points, bm, laps, runs).toByteArray(Charsets.UTF_8))
                }

                // RaceChrono CSV
                if (ExportComponent.RACECHRONO_CSV in components && points.isNotEmpty()) {
                    addEntry("drive_racechrono_$ts.csv",
                        DriveExportBuilder.buildRaceChronoCsv(points).toByteArray(Charsets.UTF_8))
                }

                // TrackAddict CSV
                if (ExportComponent.TRACKADDICT_CSV in components && points.isNotEmpty()) {
                    addEntry("drive_trackaddict_$ts.csv",
                        DriveExportBuilder.buildTrackAddictCsv(points).toByteArray(Charsets.UTF_8))
                }

                // Diagnostic data (if requested and available)
                if (includeDiagnostics && ExportComponent.DIAGNOSTICS in components) {
                    DiagnosticLogger.flushSlcan()

                    val summary = DiagnosticReportBuilder.buildSummaryText(ts)
                    if (summary.isNotEmpty()) {
                        addEntry("diagnostic_summary_$ts.txt", summary.toByteArray(Charsets.UTF_8))
                    }

                    val detail = DiagnosticReportBuilder.buildDetailJson(ts)
                    if (detail.isNotEmpty()) {
                        addEntry("diagnostic_detail_$ts.json", detail.toByteArray(Charsets.UTF_8))
                    }

                    addCrashFiles(ctx, zip)
                    addProbeFiles(zip)
                }

                // SLCAN log (separate toggle from diagnostics — can be large)
                if (includeDiagnostics && ExportComponent.SLCAN_LOG in components) {
                    DiagnosticLogger.flushSlcan()
                    val slcanFile = DiagnosticLogger.slcanLogFile
                    if (slcanFile?.exists() == true && slcanFile.length() > 0) {
                        val name = "slcan_log_$ts.log"
                        zip.putNextEntry(ZipEntry(name))
                        slcanFile.inputStream().buffered().use { it.copyTo(zip) }
                        zip.closeEntry()
                        entries += name to inferType(name)
                    }
                }

                // DTC results (optional)
                if (ExportComponent.DTC_REPORT in components && !dtcResults.isNullOrEmpty()) {
                    addEntry("dtc_scan_$ts.txt",
                        DriveExportBuilder.buildDtcText(dtcResults).toByteArray(Charsets.UTF_8))
                }

                // Manifest (last entry — lists all files above)
                addEntry("manifest.json", buildManifest(entries).toByteArray(Charsets.UTF_8))
            }

            _exportProgress.value = ExportProgress.Sharing
            val uri = FileProvider.getUriForFile(ctx, AUTHORITY, zipFile)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "openRS_ Drive Export")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "openRS_ v${appVersion} drive export.\n" +
                    "${points.size} waypoints, ${"%.1f".format(drive.distanceKm)} km.\n\n" +
                    "View in Sapphire → https://klexical.github.io/openRS_/"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Handler(Looper.getMainLooper()).post {
                ctx.startActivity(Intent.createChooser(intent, "Share Drive Data"))
            }
            _exportProgress.value = ExportProgress.Done
            _exportProgress.value = ExportProgress.Idle
        } catch (e: Exception) {
            _exportProgress.value = ExportProgress.Error(e.message ?: "unknown")
            DiagnosticLogger.event("DRIVE_EXPORT_ERROR", e.message ?: "unknown")
            _exportProgress.value = ExportProgress.Idle
        }
    }

    /**
     * Batch export multiple drives into a single ZIP.
     * Each drive gets per-drive-prefixed filenames: drive_{id}_{ts}.csv etc.
     */
    fun shareDrives(
        ctx: Context,
        drives: List<Pair<DriveEntity, List<DrivePointEntity>>>,
        components: Set<ExportComponent> = ExportComponent.entries.toSet()
    ) {
        _exportProgress.value = ExportProgress.Packaging("Packaging ${drives.size} drives...")
        try {
            val dir = File(ctx.filesDir, "diagnostics").also { it.mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(dir, "openrs_batch_$ts.zip")

            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                val entries = mutableListOf<Pair<String, String>>()

                fun addEntry(name: String, content: ByteArray) {
                    zip.putNextEntry(ZipEntry(name))
                    zip.write(content)
                    zip.closeEntry()
                    entries += name to inferType(name)
                }

                drives.forEach { (drive, points) ->
                    val driveTs = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
                        .format(Date(drive.startTime))
                    val prefix = "drive_${drive.id}_$driveTs"

                    if (drive.hasGps && points.isNotEmpty()) {
                        if (ExportComponent.DRIVE_GPX in components) {
                            addEntry("$prefix.gpx",
                                DriveExportBuilder.buildGpx(drive, points, driveTs).toByteArray(Charsets.UTF_8))
                        }
                        if (ExportComponent.DRIVE_CSV in components) {
                            addEntry("$prefix.csv",
                                DriveExportBuilder.buildCsv(points).toByteArray(Charsets.UTF_8))
                        }
                    }
                    if (ExportComponent.SUMMARY in components) {
                        addEntry("${prefix}_summary.txt",
                            DriveExportBuilder.buildSummary(drive, points).toByteArray(Charsets.UTF_8))
                    }
                    if (ExportComponent.PROFILE_JSON in components && points.isNotEmpty()) {
                        val dao = DriveDatabase.getInstance(ctx).driveDao()
                        val bm = try { dao.getBookmarks(drive.id) } catch (_: Exception) { emptyList() }
                        val lapSessions = try { dao.getLapSessionsForDrive(drive.id) } catch (_: Exception) { emptyList() }
                        val laps = lapSessions.flatMap { try { dao.getLaps(it.id) } catch (_: Exception) { emptyList() } }
                        val runs = try { dao.getRecentPerfRuns(50).filter { it.driveId == drive.id } } catch (_: Exception) { emptyList() }
                        addEntry("${prefix}_profile.json",
                            DriveExportBuilder.buildProfileJson(drive, points, bm, laps, runs).toByteArray(Charsets.UTF_8))
                    }
                }

                addEntry("manifest.json", buildManifest(entries).toByteArray(Charsets.UTF_8))
            }

            _exportProgress.value = ExportProgress.Sharing
            val uri = FileProvider.getUriForFile(ctx, AUTHORITY, zipFile)
            val totalDist = drives.sumOf { it.first.distanceKm }
            val totalPts = drives.sumOf { it.second.size }
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/zip"
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, "openRS_ Batch Export (${drives.size} drives)")
                putExtra(
                    Intent.EXTRA_TEXT,
                    "openRS_ v${appVersion} batch export.\n" +
                    "${drives.size} drives, $totalPts waypoints, ${"%.1f".format(totalDist)} km total.\n\n" +
                    "View in Sapphire → https://klexical.github.io/openRS_/"
                )
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            Handler(Looper.getMainLooper()).post {
                ctx.startActivity(Intent.createChooser(intent, "Share Batch Drive Data"))
            }
            _exportProgress.value = ExportProgress.Done
            _exportProgress.value = ExportProgress.Idle
        } catch (e: Exception) {
            _exportProgress.value = ExportProgress.Error(e.message ?: "unknown")
            DiagnosticLogger.event("BATCH_EXPORT_ERROR", e.message ?: "unknown")
            _exportProgress.value = ExportProgress.Idle
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

    /**
     * Create and fire an Android share intent for the diagnostic ZIP.
     *
     * Suspending because it may fetch an active drive snapshot from Room so
     * Sapphire-compatible trip files can be folded into the same ZIP when a
     * drive is recording at export time.
     */
    suspend fun share(ctx: Context) {
        val driveSnapshot = try {
            com.openrs.dash.OpenRSDashApp.instance.driveRecorder.snapshotActiveDrive()
        } catch (_: Exception) { null }

        val uri = export(ctx, driveSnapshot) ?: run {
            DiagnosticLogger.event("SHARE", "Export failed — nothing to share")
            return
        }
        val slcanLines = DiagnosticLogger.slcanLineCount
        val slcanNote  = if (slcanLines > 0) "\n• slcan_log_*.log   — raw CAN frames ($slcanLines lines, SavvyCAN/Kayak compatible)" else ""
        val driveNote  = if (driveSnapshot != null)
            "\n• drive_*.csv / .gpx / _summary  — active drive recording (Sapphire-compatible)" else ""
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "openRS_ Diagnostic Report")
            putExtra(
                Intent.EXTRA_TEXT,
                "openRS_ v${appVersion} diagnostic bundle.\n" +
                "• diagnostic_summary_*.txt  — human-readable report\n" +
                "• diagnostic_detail_*.json  — full machine-readable data$slcanNote$driveNote\n\n" +
                "App      : v${appVersion} (build ${appBuild})\n" +
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

    /** Build manifest.json listing all files in the ZIP with typed metadata. */
    private fun buildManifest(files: List<Pair<String, String>>): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("app", "openRS_")
        root.put("appVersion", appVersion)
        root.put("appBuild", appBuild)
        root.put("exportedAt", System.currentTimeMillis())
        val arr = JSONArray()
        for ((name, type) in files) {
            arr.put(JSONObject().apply {
                put("name", name)
                put("type", type)
            })
        }
        root.put("files", arr)
        return root.toString(2)
    }

    /** Infer the manifest type string from a ZIP entry filename. */
    private fun inferType(name: String): String = when {
        name == "manifest.json" -> "manifest"
        name.startsWith("diagnostic_summary_") -> "diagnostic_summary"
        name.startsWith("diagnostic_detail_") -> "diagnostic_detail"
        name.startsWith("slcan_log_") -> "slcan_log"
        name.startsWith("drive_profile_") -> "drive_profile"
        name.startsWith("drive_summary_") -> "drive_summary"
        name.startsWith("drive_") && name.endsWith(".gpx") -> "drive_gpx"
        name.startsWith("drive_") && name.endsWith(".csv") -> "drive_csv"
        name.startsWith("dtc_scan_") && name.endsWith(".json") -> "dtc_json"
        name.startsWith("dtc_scan_") && name.endsWith(".txt") -> "dtc_text"
        name.startsWith("did_probe_") -> "did_probe"
        name.startsWith("crash_telemetry_") -> "crash_telemetry"
        else -> "unknown"
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
