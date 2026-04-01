package com.openrs.dash.diagnostics

import com.openrs.dash.BuildConfig
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DtcResult
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Pure-function builders for drive export formats: GPX track, CSV telemetry,
 * text summary, and DTC scan report.
 *
 * Extracted from DiagnosticExporter to separate format generation from
 * ZIP packaging and Android share intents.
 */
internal object DriveExportBuilder {

    fun buildGpx(drive: DriveEntity, points: List<DrivePointEntity>, ts: String): String {
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<gpx version="1.1" creator="openRS_ v${BuildConfig.VERSION_NAME}" """)
            appendLine("""    xmlns="http://www.topografix.com/GPX/1/1" """)
            appendLine("""    xmlns:openrs="https://github.com/klex/openRS_">""")
            appendLine("  <metadata>")
            appendLine("    <name>openRS_ Drive $ts</name>")
            appendLine("    <time>${isoFmt.format(Date(drive.startTime))}</time>")
            appendLine("  </metadata>")
            appendLine("  <trk>")
            appendLine("    <name>Focus RS MK3 Drive</name>")

            // Split into segments at pause gaps (>5s between consecutive points)
            appendLine("    <trkseg>")
            var prevTimestamp = 0L
            points.forEach { pt ->
                if (prevTimestamp > 0 && pt.timestamp - prevTimestamp > 5000) {
                    // Pause gap — close segment and start new one
                    appendLine("    </trkseg>")
                    appendLine("    <trkseg>")
                }
                appendLine("""      <trkpt lat="${pt.lat}" lon="${pt.lng}">""")
                appendLine("        <ele>0</ele>")
                appendLine("        <time>${isoFmt.format(Date(pt.timestamp))}</time>")
                appendLine("        <extensions>")
                appendLine("          <openrs:speed>${"%.1f".format(pt.speedKph)}</openrs:speed>")
                appendLine("          <openrs:rpm>${pt.rpm}</openrs:rpm>")
                appendLine("          <openrs:gear>${pt.gear}</openrs:gear>")
                appendLine("          <openrs:boostPsi>${"%.2f".format(pt.boostPsi)}</openrs:boostPsi>")
                appendLine("          <openrs:coolantC>${"%.1f".format(pt.coolantTempC)}</openrs:coolantC>")
                appendLine("          <openrs:oilC>${"%.1f".format(pt.oilTempC)}</openrs:oilC>")
                appendLine("          <openrs:ambientC>${"%.1f".format(pt.ambientTempC)}</openrs:ambientC>")
                appendLine("          <openrs:rduC>${"%.1f".format(pt.rduTempC)}</openrs:rduC>")
                appendLine("          <openrs:ptuC>${"%.1f".format(pt.ptuTempC)}</openrs:ptuC>")
                appendLine("          <openrs:fuelPct>${"%.1f".format(pt.fuelLevelPct)}</openrs:fuelPct>")
                appendLine("          <openrs:lateralG>${"%.3f".format(pt.lateralG)}</openrs:lateralG>")
                appendLine("          <openrs:driveMode>${pt.driveMode}</openrs:driveMode>")
                appendLine("        </extensions>")
                appendLine("      </trkpt>")
                prevTimestamp = pt.timestamp
            }
            appendLine("    </trkseg>")
            appendLine("  </trk>")
            append("</gpx>")
        }
    }

    fun buildCsv(points: List<DrivePointEntity>): String = buildString {
        appendLine(
            "timestamp_ms,lat,lng,speed_kph,rpm,gear,boost_psi," +
            "coolant_c,oil_c,ambient_c,rdu_c,ptu_c,fuel_pct," +
            "tire_press_lf_psi,tire_press_rf_psi,tire_press_lr_psi,tire_press_rr_psi," +
            "tire_temp_lf_c,tire_temp_rf_c,tire_temp_lr_c,tire_temp_rr_c," +
            "wheel_fl_kph,wheel_fr_kph,wheel_rl_kph,wheel_rr_kph," +
            "lateral_g,throttle_pct,drive_mode,race_ready"
        )
        points.forEach { pt ->
            appendLine(
                "${pt.timestamp}," +
                "${"%.6f".format(pt.lat)}," +
                "${"%.6f".format(pt.lng)}," +
                "${"%.2f".format(pt.speedKph)}," +
                "${pt.rpm}," +
                "${csvEscape(pt.gear)}," +
                "${"%.3f".format(pt.boostPsi)}," +
                "${"%.1f".format(pt.coolantTempC)}," +
                "${"%.1f".format(pt.oilTempC)}," +
                "${"%.1f".format(pt.ambientTempC)}," +
                "${"%.1f".format(pt.rduTempC)}," +
                "${"%.1f".format(pt.ptuTempC)}," +
                "${"%.1f".format(pt.fuelLevelPct)}," +
                "${"%.1f".format(pt.tirePressLF)}," +
                "${"%.1f".format(pt.tirePressRF)}," +
                "${"%.1f".format(pt.tirePressLR)}," +
                "${"%.1f".format(pt.tirePressRR)}," +
                "${"%.1f".format(pt.tireTempLF)}," +
                "${"%.1f".format(pt.tireTempRF)}," +
                "${"%.1f".format(pt.tireTempLR)}," +
                "${"%.1f".format(pt.tireTempRR)}," +
                "${"%.2f".format(pt.wheelSpeedFL)}," +
                "${"%.2f".format(pt.wheelSpeedFR)}," +
                "${"%.2f".format(pt.wheelSpeedRL)}," +
                "${"%.2f".format(pt.wheelSpeedRR)}," +
                "${"%.4f".format(pt.lateralG)}," +
                "${"%.1f".format(pt.throttlePct)}," +
                "${csvEscape(pt.driveMode)}," +
                "${pt.isRaceReady}"
            )
        }
    }

    fun buildSummary(drive: DriveEntity, points: List<DrivePointEntity>): String = buildString {
        val durationMs = if (drive.endTime > 0) drive.endTime - drive.startTime else 0L
        val elapsedSec = durationMs / 1000L
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine("  openRS_ Drive Summary")
        appendLine("  App         : v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        appendLine("  Points      : ${points.size}")
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine()
        appendLine("  Distance    : ${"%.2f".format(drive.distanceKm)} km  (${"%.2f".format(drive.distanceKm * 0.621371)} mi)")
        appendLine("  Duration    : ${elapsedSec / 3600}h ${(elapsedSec % 3600) / 60}m ${elapsedSec % 60}s")
        appendLine("  Avg Speed   : ${"%.1f".format(drive.avgSpeedKph)} km/h")
        appendLine("  Max Speed   : ${"%.1f".format(drive.maxSpeedKph)} km/h")
        appendLine()
        appendLine("  Fuel Used   : ${"%.2f".format(drive.fuelUsedL)} L")
        appendLine("  Peak RPM    : ${drive.peakRpm}")
        appendLine("  Peak Boost  : ${"%.1f".format(drive.peakBoostPsi)} PSI")
        appendLine("  Peak Lat G  : ${"%.2f".format(drive.peakLateralG)} g")
        appendLine()
        if (drive.driveModeBreakdown != "{}") {
            appendLine("  Drive mode breakdown:")
            try {
                val json = org.json.JSONObject(drive.driveModeBreakdown)
                json.keys().forEach { key ->
                    val pct = json.getDouble(key) * 100
                    appendLine("    ${key.padEnd(10)} : ${"%.0f".format(pct)}%")
                }
            } catch (_: Exception) {}
        }
        appendLine()
        appendLine("═══════════════════════════════════════════════════════════")
    }

    fun buildDtcText(dtcResults: List<DtcResult>): String = buildString {
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine("  openRS_ DTC Scan Report")
        appendLine("  App : v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})")
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine()
        val grouped = dtcResults.groupBy { it.module }
        val order   = listOf("PCM", "BCM", "ABS", "AWD", "PSCM")
        for (mod in order) {
            val codes = grouped[mod] ?: continue
            appendLine("─── $mod ────────────────────────────────────────────────────")
            codes.forEach { dtc ->
                val desc = if (dtc.description.isNotEmpty()) dtc.description else "(no description)"
                appendLine("  ${dtc.code}  [${dtc.status.label}]  $desc")
            }
            appendLine()
        }
        appendLine("Total: ${dtcResults.size} fault code(s) across ${dtcResults.map { it.module }.distinct().size} module(s)")
        appendLine("═══════════════════════════════════════════════════════════")
    }

    internal fun csvEscape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n'))
            "\"${value.replace("\"", "\"\"")}\"" else value
}
