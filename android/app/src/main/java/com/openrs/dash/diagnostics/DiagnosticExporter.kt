package com.openrs.dash.diagnostics

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import com.openrs.dash.BuildConfig
import com.openrs.dash.can.CanDecoder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds and shares the openRS_ diagnostic bundle.
 *
 * Output: a ZIP containing:
 *   diagnostic_summary_<timestamp>.txt   — human-readable report
 *   diagnostic_detail_<timestamp>.json   — full machine-readable data
 *
 * Upload either file (or the whole ZIP) when reporting issues.
 * The summary is readable in any text editor.
 * The JSON can be parsed/analyzed programmatically.
 */
object DiagnosticExporter {

    private const val AUTHORITY = "com.openrs.dash.provider"

    /**
     * Export the current diagnostic session to a ZIP file in the app's
     * internal files directory, then return a shareable URI via FileProvider.
     */
    fun export(ctx: Context): Uri? {
        return try {
            val dir = File(ctx.filesDir, "diagnostics").also { it.mkdirs() }
            val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
            val zipFile = File(dir, "openrs_diag_$ts.zip")

            ZipOutputStream(zipFile.outputStream().buffered()).use { zip ->
                zip.putNextEntry(ZipEntry("diagnostic_summary_$ts.txt"))
                zip.write(buildSummary(ts).toByteArray(Charsets.UTF_8))
                zip.closeEntry()

                zip.putNextEntry(ZipEntry("diagnostic_detail_$ts.json"))
                zip.write(buildJson(ts).toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }

            FileProvider.getUriForFile(ctx, AUTHORITY, zipFile)
        } catch (e: Exception) {
            DiagnosticLogger.event("EXPORT_ERROR", e.message ?: "unknown")
            null
        }
    }

    /** Create and fire an Android share intent for the diagnostic ZIP. */
    fun share(ctx: Context) {
        val uri = export(ctx) ?: run {
            DiagnosticLogger.event("SHARE", "Export failed — nothing to share")
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "openRS_ Diagnostic Report")
            putExtra(Intent.EXTRA_TEXT,
                "openRS_ v${BuildConfig.VERSION_NAME} diagnostic bundle.\n" +
                "• diagnostic_summary_*.txt — human-readable\n" +
                "• diagnostic_detail_*.json — raw data for analysis\n\n" +
                "App      : v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
                "Session  : ${DiagnosticLogger.formatDuration(DiagnosticLogger.sessionDurationMs)}\n" +
                "Firmware : ${DiagnosticLogger.firmwareVersion}\n" +
                "Host     : ${DiagnosticLogger.sessionHost}:${DiagnosticLogger.sessionPort}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(Intent.createChooser(intent, "Share openRS_ Diagnostics"))
    }

    // ── Text summary ─────────────────────────────────────────────────────────

    private fun buildSummary(ts: String): String = buildString {
        val log = DiagnosticLogger
        val vs  = log.lastVehicleState
        val p   = log.sessionPrefs

        appendLine("═══════════════════════════════════════════════════════════")
        appendLine("  openRS_ Diagnostic Report")
        appendLine("  App       : v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        appendLine("  Generated : $ts")
        appendLine("  Session   : ${log.formatDuration(log.sessionDurationMs)}")
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine()

        // ── Connection
        appendLine("─── CONNECTION ────────────────────────────────────────────")
        appendLine("  Host     : ${log.sessionHost}:${log.sessionPort}")
        appendLine("  Firmware : ${log.firmwareVersion}")
        val connEvent = log.sessionEvents.lastOrNull { it.type == "SESSION" }
        if (connEvent != null) appendLine("  Last evt : ${connEvent.message}")
        appendLine()

        // ── Settings
        if (p != null) {
            appendLine("─── SETTINGS ───────────────────────────────────────────────")
            appendLine("  Speed     : ${p.speedUnit}")
            appendLine("  Temp      : ${p.tempUnit}")
            appendLine("  Boost     : ${p.boostUnit}")
            appendLine("  Tires     : ${p.tireUnit}")
            appendLine("  TireLow   : ${p.tireLowPsi} PSI")
            appendLine("  AutoRecon : ${p.autoReconnect} / ${p.reconnectIntervalSec}s")
            appendLine()
        }

        // ── Vehicle state snapshot
        if (vs != null) {
            appendLine("─── VEHICLE STATE SNAPSHOT ─────────────────────────────────")
            appendLine("  RPM        : ${vs.rpm.toInt()}")
            appendLine("  Speed      : ${"%.1f".format(vs.speedKph)} kph / ${"%.1f".format(vs.speedKph * 0.621371)} mph")
            appendLine("  Boost      : ${"%.1f".format((vs.boostKpa - 101.325) * 0.14503773)} PSI  (${vs.boostKpa.toInt()} kPa abs)")
            appendLine("  Coolant    : ${"%.0f".format(vs.coolantTempC)} °C")
            appendLine("  Oil Temp   : ${"%.0f".format(vs.oilTempC)} °C")
            appendLine("  Ambient    : ${"%.1f".format(vs.ambientTempC)} °C")
            appendLine("  Baro       : ${"%.1f".format(vs.barometricPressure)} kPa")
            appendLine("  Lat G      : ${"%.3f".format(vs.lateralG)} g")
            appendLine("  Lon G      : ${"%.3f".format(vs.longitudinalG)} g")
            appendLine("  Drive Mode : ${vs.driveMode.label}")
            appendLine("  ESC        : ${vs.escStatus.label}")
            appendLine("  Gear       : ${vs.gearDisplay}")
            appendLine("  Battery    : ${"%.1f".format(vs.batteryVoltage)} V")
            appendLine("  Fuel       : ${"%.0f".format(vs.fuelLevelPct)} %")
            appendLine("  Throttle   : ${"%.1f".format(vs.throttlePct)} %")
            appendLine("  Torque     : ${"%.0f".format(vs.torqueAtTrans)} Nm")
            appendLine("  TPMS LF/RF : ${"%.0f".format(vs.tirePressLF)} / ${"%.0f".format(vs.tirePressRF)} PSI")
            appendLine("  TPMS LR/RR : ${"%.0f".format(vs.tirePressLR)} / ${"%.0f".format(vs.tirePressRR)} PSI")
            appendLine("  AWD L/R    : ${"%.0f".format(vs.awdLeftTorque)} / ${"%.0f".format(vs.awdRightTorque)} Nm")
            appendLine("  RDU Temp   : ${"%.0f".format(vs.rduTempC)} °C")
            appendLine("  PTU Temp   : ${"%.0f".format(vs.ptuTempC)} °C")
            appendLine("  FPS        : ${"%.0f".format(vs.framesPerSecond)}")
            appendLine("  Data Mode  : ${vs.dataMode}")
            appendLine()
        }

        // ── CAN frame inventory
        val inventory = log.frameInventorySnapshot
        appendLine("─── CAN FRAME INVENTORY (${inventory.size} unique IDs) ──────────────")
        appendLine("  Format: ID | count | last raw hex | decoded | issues")
        appendLine()

        val knownIds = setOf(
            CanDecoder.ID_TORQUE, CanDecoder.ID_THROTTLE, CanDecoder.ID_PEDALS,
            CanDecoder.ID_ENGINE_RPM, CanDecoder.ID_GAUGE_ILLUM, CanDecoder.ID_ENGINE_TEMPS,
            CanDecoder.ID_SPEED, CanDecoder.ID_LONG_ACCEL, CanDecoder.ID_LAT_ACCEL,
            CanDecoder.ID_AWD_MSG, CanDecoder.ID_ESC_ABS, CanDecoder.ID_WHEEL_SPEEDS,
            CanDecoder.ID_GEAR, CanDecoder.ID_AWD_TORQUE, CanDecoder.ID_PTU_TEMP,
            CanDecoder.ID_COOLANT, CanDecoder.ID_TPMS, CanDecoder.ID_AMBIENT_TEMP,
            CanDecoder.ID_FUEL_LEVEL, CanDecoder.ID_BATTERY
        )

        inventory.entries.sortedBy { it.key }.forEach { (id, info) ->
            val tag = if (id in knownIds) "✓" else "?"
            val issueStr = if (info.validationIssues.isEmpty()) ""
                           else "  ⚠ ${info.validationIssues.joinToString("; ")}"
            val decoded = if (info.lastDecoded.isEmpty()) "(no decoder)" else info.lastDecoded
            appendLine("  $tag 0x%03X | %6d | %-23s | %-40s$issueStr"
                .format(id, info.totalReceived, info.lastRawHex.take(23), decoded.take(40)))
        }
        appendLine()

        // ── Validation issues summary
        val issues = inventory.values.flatMap { info ->
            info.validationIssues.map { "0x%03X: $it".format(inventory.entries.first { it.value == info }.key) }
        }
        appendLine("─── VALIDATION ISSUES (${issues.size}) ────────────────────────────────")
        if (issues.isEmpty()) {
            appendLine("  None — all decoded values within expected physical ranges")
        } else {
            issues.forEach { appendLine("  ⚠ $it") }
        }
        appendLine()

        // ── FPS timeline
        val fps = log.fpsTimeline
        appendLine("─── FPS TIMELINE (${fps.size} samples) ────────────────────────────")
        if (fps.isEmpty()) {
            appendLine("  No FPS data recorded")
        } else {
            val minFps = fps.minOf { it.fps }
            val maxFps = fps.maxOf { it.fps }
            val avgFps = fps.map { it.fps }.average()
            appendLine("  Min: ${"%.0f".format(minFps)}  Max: ${"%.0f".format(maxFps)}  Avg: ${"%.0f".format(avgFps)}")
            appendLine("  Last 10 samples:")
            fps.takeLast(10).forEach { pt ->
                appendLine("    +${log.formatDuration(pt.relMs)} → ${"%.0f".format(pt.fps)} fps")
            }
        }
        appendLine()

        // ── Session events
        appendLine("─── SESSION EVENTS ─────────────────────────────────────────")
        if (log.sessionEvents.isEmpty()) {
            appendLine("  (none)")
        } else {
            log.sessionEvents.forEach { ev ->
                appendLine("  +${log.formatDuration(ev.relMs)} [${ev.type}] ${ev.message}")
            }
        }
        appendLine()

        // ── Last 30 decode trace entries
        val trace = log.decodeTrace.takeLast(30)
        appendLine("─── RECENT DECODE TRACE (last ${trace.size}) ─────────────────────────")
        appendLine("  Format: +time | ID | raw hex | decoded | ⚠issue")
        trace.forEach { t ->
            val issStr = if (t.issue != null) "  ⚠ ${t.issue}" else ""
            appendLine("  +${log.formatDuration(t.relMs)} | ${t.idHex} | ${t.rawHex.take(23).padEnd(23)} | ${t.decoded.take(35)}$issStr")
        }
        appendLine()
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine("  END OF REPORT — full data in diagnostic_detail_$ts.json")
        appendLine("═══════════════════════════════════════════════════════════")
    }

    // ── JSON detail ───────────────────────────────────────────────────────────

    private fun buildJson(ts: String): String = buildString {
        val log = DiagnosticLogger
        val vs  = log.lastVehicleState
        val p   = log.sessionPrefs

        appendLine("{")

        // meta
        appendLine("  \"meta\": {")
        appendLine("    \"app\": \"openRS_\",")
        appendLine("    \"appVersion\": \"${BuildConfig.VERSION_NAME}\",")
        appendLine("    \"appBuild\": ${BuildConfig.VERSION_CODE},")
        appendLine("    \"generatedAt\": \"$ts\",")
        appendLine("    \"sessionDurationMs\": ${log.sessionDurationMs},")
        appendLine("    \"sessionDurationHuman\": \"${log.formatDuration(log.sessionDurationMs)}\",")
        appendLine("    \"firmware\": \"${log.firmwareVersion}\",")
        appendLine("    \"isOpenRsFirmware\": ${log.isOpenRsFirmware},")
        appendLine("    \"host\": \"${log.sessionHost}\",")
        appendLine("    \"port\": ${log.sessionPort}")
        appendLine("  },")

        // settings
        if (p != null) {
            appendLine("  \"settings\": {")
            appendLine("    \"speedUnit\": \"${p.speedUnit}\",")
            appendLine("    \"tempUnit\": \"${p.tempUnit}\",")
            appendLine("    \"boostUnit\": \"${p.boostUnit}\",")
            appendLine("    \"tireUnit\": \"${p.tireUnit}\",")
            appendLine("    \"tireLowPsi\": ${p.tireLowPsi},")
            appendLine("    \"screenOn\": ${p.screenOn},")
            appendLine("    \"autoReconnect\": ${p.autoReconnect},")
            appendLine("    \"reconnectIntervalSec\": ${p.reconnectIntervalSec}")
            appendLine("  },")
        }

        // vehicleState
        appendLine("  \"vehicleState\": {")
        if (vs != null) {
            vs.toJsonFields().entries.forEachIndexed { i, (k, v) ->
                val comma = if (i < vs.toJsonFields().size - 1) "," else ""
                appendLine("    \"$k\": $v$comma")
            }
        }
        appendLine("  },")

        // canFrameInventory
        val inventory = log.frameInventorySnapshot
        appendLine("  \"canFrameInventory\": {")
        inventory.entries.sortedBy { it.key }.forEachIndexed { idx, (id, info) ->
            val comma = if (idx < inventory.size - 1) "," else ""
            val issuesJson = info.validationIssues.joinToString(",") { "\"${it.replace("\"", "'")}\"" }
            appendLine("    \"0x%03X\": {".format(id))
            appendLine("      \"totalReceived\": ${info.totalReceived},")
            appendLine("      \"lastRawHex\": \"${info.lastRawHex}\",")
            appendLine("      \"lastDecoded\": \"${info.lastDecoded.replace("\"", "'")}\",")
            appendLine("      \"validationIssues\": [$issuesJson]")
            appendLine("    }$comma")
        }
        appendLine("  },")

        // fpsTimeline
        appendLine("  \"fpsTimeline\": [")
        val fps = log.fpsTimeline
        fps.forEachIndexed { i, pt ->
            val c = if (i < fps.size - 1) "," else ""
            appendLine("    {\"relMs\": ${pt.relMs}, \"fps\": ${"%.1f".format(pt.fps)}}$c")
        }
        appendLine("  ],")

        // decodeTrace (all MAX_TRACE entries)
        appendLine("  \"decodeTrace\": [")
        val trace = log.decodeTrace
        trace.forEachIndexed { i, t ->
            val c = if (i < trace.size - 1) "," else ""
            val issJson = if (t.issue != null) "\"${t.issue.replace("\"", "'")}\"" else "null"
            appendLine("    {\"relMs\": ${t.relMs}, \"id\": \"${t.idHex}\", \"raw\": \"${t.rawHex}\", \"decoded\": \"${t.decoded.replace("\"", "'")}\", \"issue\": $issJson}$c")
        }
        appendLine("  ],")

        // sessionEvents
        appendLine("  \"sessionEvents\": [")
        val evts = log.sessionEvents
        evts.forEachIndexed { i, ev ->
            val c = if (i < evts.size - 1) "," else ""
            appendLine("    {\"relMs\": ${ev.relMs}, \"type\": \"${ev.type}\", \"message\": \"${ev.message.replace("\"", "'")}\"}$c")
        }
        appendLine("  ]")

        append("}")
    }
}

/** Serialize VehicleState to a Map<String, Any> for JSON output. */
private fun com.openrs.dash.data.VehicleState.toJsonFields(): Map<String, String> = linkedMapOf(
    "rpm"               to "${"%.1f".format(rpm)}",
    "speedKph"          to "${"%.2f".format(speedKph)}",
    "speedMph"          to "${"%.2f".format(speedKph * 0.621371)}",
    "boostKpa"          to "${"%.2f".format(boostKpa)}",
    "boostPsi"          to "${"%.2f".format((boostKpa - 101.325) * 0.14503773)}",
    "coolantTempC"      to "${"%.1f".format(coolantTempC)}",
    "oilTempC"          to "${"%.1f".format(oilTempC)}",
    "intakeTempC"       to "${"%.1f".format(intakeTempC)}",
    "ambientTempC"      to "${"%.2f".format(ambientTempC)}",
    "barometricPressure" to "${"%.1f".format(barometricPressure)}",
    "throttlePct"       to "${"%.1f".format(throttlePct)}",
    "accelPedalPct"     to "${"%.1f".format(accelPedalPct)}",
    "torqueAtTrans"     to "${"%.1f".format(torqueAtTrans)}",
    "lateralG"          to "${"%.4f".format(lateralG)}",
    "longitudinalG"     to "${"%.4f".format(longitudinalG)}",
    "steeringAngle"     to "${"%.1f".format(steeringAngle)}",
    "yawRate"           to "${"%.1f".format(yawRate)}",
    "brakePressure"     to "${"%.2f".format(brakePressure)}",
    "wheelSpeedFL"      to "${"%.2f".format(wheelSpeedFL)}",
    "wheelSpeedFR"      to "${"%.2f".format(wheelSpeedFR)}",
    "wheelSpeedRL"      to "${"%.2f".format(wheelSpeedRL)}",
    "wheelSpeedRR"      to "${"%.2f".format(wheelSpeedRR)}",
    "driveMode"         to "\"${driveMode.label}\"",
    "escStatus"         to "\"${escStatus.label}\"",
    "gear"              to "$gear",
    "gearDisplay"       to "\"$gearDisplay\"",
    "batteryVoltage"    to "${"%.2f".format(batteryVoltage)}",
    "fuelLevelPct"      to "${"%.1f".format(fuelLevelPct)}",
    "tirePressLF"       to "${"%.1f".format(tirePressLF)}",
    "tirePressRF"       to "${"%.1f".format(tirePressRF)}",
    "tirePressLR"       to "${"%.1f".format(tirePressLR)}",
    "tirePressRR"       to "${"%.1f".format(tirePressRR)}",
    "awdLeftTorque"     to "${"%.1f".format(awdLeftTorque)}",
    "awdRightTorque"    to "${"%.1f".format(awdRightTorque)}",
    "awdMaxTorque"      to "${"%.1f".format(awdMaxTorque)}",
    "rduTempC"          to "${"%.1f".format(rduTempC)}",
    "ptuTempC"          to "${"%.1f".format(ptuTempC)}",
    "calcLoad"          to "${"%.1f".format(calcLoad)}",
    "timingAdvance"     to "${"%.1f".format(timingAdvance)}",
    "shortFuelTrim"     to "${"%.2f".format(shortFuelTrim)}",
    "longFuelTrim"      to "${"%.2f".format(longFuelTrim)}",
    "afrActual"         to "${"%.3f".format(afrActual)}",
    "afrDesired"        to "${"%.2f".format(afrDesired)}",
    "lambdaActual"      to "${"%.4f".format(lambdaActual)}",
    "peakBoostPsi"      to "${"%.2f".format(peakBoostPsi)}",
    "peakRpm"           to "${"%.0f".format(peakRpm)}",
    "peakLateralG"      to "${"%.3f".format(peakLateralG)}",
    "peakLongitudinalG" to "${"%.3f".format(peakLongitudinalG)}",
    "eBrake"            to "$eBrake",
    "reverseStatus"     to "$reverseStatus",
    "framesPerSecond"   to "${"%.1f".format(framesPerSecond)}",
    "isConnected"       to "$isConnected",
    "dataMode"          to "\"$dataMode\""
)
