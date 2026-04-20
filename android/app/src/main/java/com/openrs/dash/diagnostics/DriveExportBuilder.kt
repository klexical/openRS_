package com.openrs.dash.diagnostics

import com.openrs.dash.BuildConfig
import com.openrs.dash.data.DriveBookmarkEntity
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.PeakType
import org.json.JSONArray
import org.json.JSONObject
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

    // BuildConfig fields are platform types (null in JVM unit tests)
    private val appVersion: String get() = BuildConfig.VERSION_NAME ?: ""
    private val appBuild: Int get() = BuildConfig.VERSION_CODE

    fun buildGpx(drive: DriveEntity, points: List<DrivePointEntity>, ts: String): String {
        val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        return buildString {
            appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
            appendLine("""<gpx version="1.1" creator="openRS_ v${appVersion}" """)
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
                appendLine("          <openrs:longitudinalG>${"%.3f".format(pt.longitudinalG)}</openrs:longitudinalG>")
                appendLine("          <openrs:brakePressure>${"%.1f".format(pt.brakePressure)}</openrs:brakePressure>")
                appendLine("          <openrs:steeringAngle>${"%.1f".format(pt.steeringAngle)}</openrs:steeringAngle>")
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
        appendLine("# openrs_csv_v1")
        appendLine(
            "timestamp_ms,lat,lng,speed_kph,rpm,gear,boost_psi," +
            "coolant_c,oil_c,ambient_c,rdu_c,ptu_c,fuel_pct," +
            "tire_press_lf_psi,tire_press_rf_psi,tire_press_lr_psi,tire_press_rr_psi," +
            "tire_temp_lf_c,tire_temp_rf_c,tire_temp_lr_c,tire_temp_rr_c," +
            "wheel_fl_kph,wheel_fr_kph,wheel_rl_kph,wheel_rr_kph," +
            "lateral_g,longitudinal_g,brake_pressure,steering_angle,throttle_pct,drive_mode,race_ready"
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
                "${"%.4f".format(pt.longitudinalG)}," +
                "${"%.1f".format(pt.brakePressure)}," +
                "${"%.1f".format(pt.steeringAngle)}," +
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
        appendLine("  App         : v${appVersion} (build ${appBuild})")
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
        appendLine("  App : v${appVersion} (build ${appBuild})")
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine()
        val grouped = dtcResults.groupBy { it.module }
        val order   = listOf("PCM", "BCM", "ABS", "AWD", "PSCM", "GFM")
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

    /**
     * Build a machine-readable JSON DTC scan report for Sapphire import.
     */
    fun buildDtcJson(dtcResults: List<DtcResult>): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("timestamp", System.currentTimeMillis())
        root.put("app", "openRS_ v${appVersion}")
        root.put("totalCodes", dtcResults.size)

        val codesArray = org.json.JSONArray()
        for (dtc in dtcResults) {
            val obj = JSONObject().apply {
                put("module", dtc.module)
                put("code", dtc.code)
                put("description", dtc.description)
                put("status", dtc.status.name)
                put("severity", com.openrs.dash.data.DtcSeverity.fromCode(dtc.code).name)
            }
            // Include freeze frame if available
            val ff = dtc.freezeFrame
            if (ff != null) {
                val ffObj = JSONObject().apply {
                    put("recordNumber", ff.recordNumber)
                    val entries = org.json.JSONArray()
                    for (e in ff.entries) {
                        entries.put(JSONObject().apply {
                            put("did", "0x%04X".format(e.did))
                            put("label", e.label)
                            put("value", e.value)
                        })
                    }
                    put("entries", entries)
                }
                obj.put("freezeFrame", ffObj)
            }
            codesArray.put(obj)
        }
        root.put("codes", codesArray)
        return root.toString(2)
    }

    /**
     * Build a structured JSON profile for Sapphire web dashboard import.
     * Includes drive summary, peak events with GPS, thermal progression,
     * and a mode timeline derived from point-level drive mode transitions.
     */
    fun buildProfileJson(
        drive: DriveEntity,
        points: List<DrivePointEntity>,
        bookmarks: List<DriveBookmarkEntity> = emptyList(),
        laps: List<com.openrs.dash.data.LapEntity> = emptyList(),
        perfRuns: List<com.openrs.dash.data.PerfRunEntity> = emptyList()
    ): String {
        val root = JSONObject()
        root.put("version", 2)

        // ── Drive summary ────────────────────────────────────────
        val driveObj = JSONObject().apply {
            put("startTime", drive.startTime)
            put("endTime", drive.endTime)
            put("distanceKm", drive.distanceKm)
            put("avgSpeedKph", drive.avgSpeedKph)
            put("maxSpeedKph", drive.maxSpeedKph)
            put("peakRpm", drive.peakRpm)
            put("peakBoostPsi", drive.peakBoostPsi)
            put("peakLateralG", drive.peakLateralG)
            put("peakOilTempC", drive.peakOilTempC)
            put("peakCoolantTempC", drive.peakCoolantTempC)
            put("fuelUsedL", drive.fuelUsedL)
            put("aggressionScore", drive.aggressionScore)
            put("tags", drive.tags)
            // T5A: fuel economy averages
            if (drive.avgFuelL100km > 0) put("avgFuelL100km", drive.avgFuelL100km)
            if (drive.avgFuelMpg > 0) put("avgFuelMpg", drive.avgFuelMpg)
        }
        root.put("drive", driveObj)

        // ── Peak events (best per type from recorded points) ─────
        val peaksArray = JSONArray()
        data class PeakCandidate(val type: PeakType, val value: Double, val lat: Double, val lng: Double, val ts: Long)
        val bestPeaks = mutableMapOf<PeakType, PeakCandidate>()

        for (pt in points) {
            val candidates = listOf(
                PeakCandidate(PeakType.RPM, pt.rpm.toDouble(), pt.lat, pt.lng, pt.timestamp),
                PeakCandidate(PeakType.BOOST, pt.boostPsi, pt.lat, pt.lng, pt.timestamp),
                PeakCandidate(PeakType.LATERAL_G, Math.abs(pt.lateralG), pt.lat, pt.lng, pt.timestamp),
                PeakCandidate(PeakType.SPEED, pt.speedKph, pt.lat, pt.lng, pt.timestamp)
            )
            for (c in candidates) {
                val existing = bestPeaks[c.type]
                if (existing == null || c.value > existing.value) {
                    bestPeaks[c.type] = c
                }
            }
        }
        for (peak in bestPeaks.values) {
            if (peak.value <= 0.0) continue
            peaksArray.put(JSONObject().apply {
                put("type", peak.type.name)
                put("value", peak.value)
                put("lat", peak.lat)
                put("lng", peak.lng)
                put("timestampMs", peak.ts)
            })
        }
        root.put("peaks", peaksArray)

        // ── Thermal progression ──────────────────────────────────
        val thermalObj = JSONObject()
        thermalObj.put("oil", JSONObject().apply {
            put("startC", drive.startOilTempC)
            put("peakC", drive.peakOilTempC)
            put("endC", drive.endOilTempC)
        })
        thermalObj.put("coolant", JSONObject().apply {
            put("startC", drive.startCoolantTempC)
            put("peakC", drive.peakCoolantTempC)
            put("endC", drive.endCoolantTempC)
        })
        // T5D: per-sensor thermal peaks (RDU/PTU) from thermalPeaksJson
        val thermalPeaks = drive.thermalPeaksJson
        if (!thermalPeaks.isNullOrEmpty()) {
            try {
                val peaksObj = JSONObject(thermalPeaks)
                // Extract RDU curve if present
                if (peaksObj.has("rduPeakC")) {
                    val rduStart = points.firstOrNull { it.rduTempC > -90 }?.rduTempC ?: -99.0
                    val rduEnd = points.lastOrNull { it.rduTempC > -90 }?.rduTempC ?: -99.0
                    thermalObj.put("rdu", JSONObject().apply {
                        put("startC", rduStart)
                        put("peakC", peaksObj.getDouble("rduPeakC"))
                        put("endC", rduEnd)
                    })
                }
                // Extract PTU curve if present
                if (peaksObj.has("ptuPeakC")) {
                    val ptuStart = points.firstOrNull { it.ptuTempC > -90 }?.ptuTempC ?: -99.0
                    val ptuEnd = points.lastOrNull { it.ptuTempC > -90 }?.ptuTempC ?: -99.0
                    thermalObj.put("ptu", JSONObject().apply {
                        put("startC", ptuStart)
                        put("peakC", peaksObj.getDouble("ptuPeakC"))
                        put("endC", ptuEnd)
                    })
                }
            } catch (_: Exception) { /* thermalPeaksJson parse failure is non-fatal */ }
        }
        root.put("thermalProgression", thermalObj)

        // ── Mode timeline (detect transitions in point sequence) ─
        val modeTimeline = JSONArray()
        if (points.isNotEmpty()) {
            val driveStart = drive.startTime
            var currentMode = points.first().driveMode
            var segmentStartMs = points.first().timestamp - driveStart

            for (i in 1 until points.size) {
                val pt = points[i]
                if (pt.driveMode != currentMode) {
                    val segmentEndMs = pt.timestamp - driveStart
                    modeTimeline.put(JSONObject().apply {
                        put("mode", currentMode)
                        put("startMs", segmentStartMs)
                        put("endMs", segmentEndMs)
                    })
                    currentMode = pt.driveMode
                    segmentStartMs = segmentEndMs
                }
            }
            // Close final segment
            val finalEndMs = if (drive.endTime > 0) drive.endTime - driveStart
                             else points.last().timestamp - driveStart
            modeTimeline.put(JSONObject().apply {
                put("mode", currentMode)
                put("startMs", segmentStartMs)
                put("endMs", finalEndMs)
            })
        }
        root.put("modeTimeline", modeTimeline)

        // ── Bookmarks ───────────────────────────────────────────
        if (bookmarks.isNotEmpty()) {
            val bmArray = JSONArray()
            bookmarks.forEach { bm ->
                bmArray.put(JSONObject().apply {
                    put("timestamp", bm.timestamp)
                    put("lat", bm.lat)
                    put("lng", bm.lng)
                    put("label", bm.label)
                    put("speedKph", bm.speedKph)
                    put("rpm", bm.rpm)
                    put("boostPsi", bm.boostPsi)
                })
            }
            root.put("bookmarks", bmArray)
        }

        // ── Laps (T5B) ─────────────────────────────────────────
        if (laps.isNotEmpty()) {
            val lapsArray = JSONArray()
            laps.forEach { lap ->
                lapsArray.put(JSONObject().apply {
                    put("lapNumber", lap.lapNumber)
                    put("lapTimeMs", lap.lapTimeMs)
                    if (lap.peakRpm > 0) put("peakRpm", lap.peakRpm)
                    if (lap.peakBoostPsi > 0) put("peakBoostPsi", lap.peakBoostPsi)
                    if (lap.peakLateralG > 0) put("peakLateralG", lap.peakLateralG)
                    if (lap.peakSpeedKph > 0) put("peakSpeedKph", lap.peakSpeedKph)
                })
            }
            root.put("laps", lapsArray)
        }

        // ── Performance runs (T5C) ─────────────────────────────
        if (perfRuns.isNotEmpty()) {
            val runsArray = JSONArray()
            perfRuns.forEach { run ->
                runsArray.put(JSONObject().apply {
                    put("type", if (run.zeroTo100Ms != null) "0-100" else "0-60")
                    put("timeMs", run.zeroTo60Ms)
                    if (run.zeroTo100Ms != null) put("timeTo100Ms", run.zeroTo100Ms)
                    if (run.launchRpm > 0) put("launchRpm", run.launchRpm)
                    if (run.peakBoostPsi > 0) put("peakBoostPsi", run.peakBoostPsi)
                    if (run.densityAltFt != null) put("densityAltFt", run.densityAltFt)
                    if (run.ambientTempC > -90) put("ambientTempC", run.ambientTempC)
                })
            }
            root.put("perfRuns", runsArray)
        }

        return root.toString(2)
    }

    /**
     * Build a RaceChrono-compatible CSV.
     * Columns follow RaceChrono CSV v3: timestamp in seconds, speed in m/s,
     * G-forces in g, temps in °C. Custom OBD channels use the rc_ prefix.
     */
    fun buildRaceChronoCsv(points: List<DrivePointEntity>): String = buildString {
        appendLine(
            "Timestamp (s),Latitude (deg),Longitude (deg),Speed (m/s),Speed (kph)," +
            "Lateral Acceleration (G),Longitudinal Acceleration (G)," +
            "RPM (rpm),Throttle Position (%),Gear,Boost Pressure (psi)," +
            "Coolant Temp (C),Oil Temp (C),Brake Pressure"
        )
        if (points.isEmpty()) return@buildString
        val t0 = points.first().timestamp
        points.forEach { pt ->
            val timeSec = (pt.timestamp - t0) / 1000.0
            val speedMs = pt.speedKph / 3.6
            appendLine(
                "${"%.3f".format(timeSec)}," +
                "${"%.6f".format(pt.lat)}," +
                "${"%.6f".format(pt.lng)}," +
                "${"%.2f".format(speedMs)}," +
                "${"%.2f".format(pt.speedKph)}," +
                "${"%.4f".format(pt.lateralG)}," +
                "${"%.4f".format(pt.longitudinalG)}," +
                "${pt.rpm}," +
                "${"%.1f".format(pt.throttlePct)}," +
                "${csvEscape(pt.gear)}," +
                "${"%.2f".format(pt.boostPsi)}," +
                "${"%.1f".format(pt.coolantTempC)}," +
                "${"%.1f".format(pt.oilTempC)}," +
                "${"%.1f".format(pt.brakePressure)}"
            )
        }
    }

    /**
     * Build a TrackAddict-compatible CSV.
     * Uses TrackAddict's "Time,Latitude,Longitude,Speed (MPH),..." header format.
     * Speed in MPH, G-forces in g, temps in °F (TrackAddict convention).
     */
    fun buildTrackAddictCsv(points: List<DrivePointEntity>): String = buildString {
        appendLine(
            "Time,Latitude,Longitude,Speed (MPH),Lateral Gs,Lineal Gs," +
            "RPM,Throttle (%),Gear,Boost (PSI)," +
            "Coolant Temp (F),Oil Temp (F),Brake"
        )
        if (points.isEmpty()) return@buildString
        val t0 = points.first().timestamp
        points.forEach { pt ->
            val timeSec = (pt.timestamp - t0) / 1000.0
            val speedMph = pt.speedKph * 0.621371
            val coolantF = if (pt.coolantTempC > -90) pt.coolantTempC * 9.0 / 5.0 + 32.0 else -99.0
            val oilF = if (pt.oilTempC > -90) pt.oilTempC * 9.0 / 5.0 + 32.0 else -99.0
            appendLine(
                "${"%.3f".format(timeSec)}," +
                "${"%.6f".format(pt.lat)}," +
                "${"%.6f".format(pt.lng)}," +
                "${"%.1f".format(speedMph)}," +
                "${"%.4f".format(pt.lateralG)}," +
                "${"%.4f".format(pt.longitudinalG)}," +
                "${pt.rpm}," +
                "${"%.1f".format(pt.throttlePct)}," +
                "${csvEscape(pt.gear)}," +
                "${"%.2f".format(pt.boostPsi)}," +
                "${"%.1f".format(coolantF)}," +
                "${"%.1f".format(oilF)}," +
                "${"%.1f".format(pt.brakePressure)}"
            )
        }
    }

    internal fun csvEscape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n'))
            "\"${value.replace("\"", "\"\"")}\"" else value
}
