package com.openrs.dash.diagnostics

import com.openrs.dash.BuildConfig
import com.openrs.dash.data.DriveBookmarkEntity
import com.openrs.dash.data.DriveEntity
import com.openrs.dash.data.DrivePointEntity
import com.openrs.dash.data.DriveThermalReview
import com.openrs.dash.data.DriveThermalSensor
import com.openrs.dash.data.THERMAL_FLAG_FRACTION
import com.openrs.dash.data.thermalFlagThresholdC
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

    /** The export's value for a drive's oil / PTU mapping (#229): as saved, or `not_recorded` — never inferred. */
    private fun oilPtuMappingValue(mapping: String?): String = mapping ?: "not_recorded"
    private fun oilPtuMappingValue(drive: DriveEntity): String = oilPtuMappingValue(drive.oilPtuMapping)

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
            // #229: which 0x0F8 bytes oilC / ptuC came from — the drive's own record, "not_recorded" for older drives
            appendLine("    <extensions><openrs:oilPtuMapping>${oilPtuMappingValue(drive)}</openrs:oilPtuMapping></extensions>")
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
                appendLine("          <openrs:speed>${"%.1f".format(Locale.ROOT, pt.speedKph)}</openrs:speed>")
                appendLine("          <openrs:rpm>${pt.rpm}</openrs:rpm>")
                appendLine("          <openrs:gear>${pt.gear}</openrs:gear>")
                pt.boostReadingPsi()?.let { appendLine("          <openrs:boostPsi>${"%.2f".format(Locale.ROOT, it)}</openrs:boostPsi>") }
                pt.boostSource?.takeIf { it != "NONE" }?.let { appendLine("          <openrs:boostSource>$it</openrs:boostSource>") }
                appendLine("          <openrs:coolantC>${"%.1f".format(Locale.ROOT, pt.coolantTempC)}</openrs:coolantC>")
                appendLine("          <openrs:oilC>${"%.1f".format(Locale.ROOT, pt.oilTempC)}</openrs:oilC>")
                appendLine("          <openrs:ambientC>${"%.1f".format(Locale.ROOT, pt.ambientTempC)}</openrs:ambientC>")
                appendLine("          <openrs:rduC>${"%.1f".format(Locale.ROOT, pt.rduTempC)}</openrs:rduC>")
                appendLine("          <openrs:ptuC>${"%.1f".format(Locale.ROOT, pt.ptuTempC)}</openrs:ptuC>")
                appendLine("          <openrs:fuelPct>${"%.1f".format(Locale.ROOT, pt.fuelLevelPct)}</openrs:fuelPct>")
                appendLine("          <openrs:lateralG>${"%.3f".format(Locale.ROOT, pt.lateralG)}</openrs:lateralG>")
                appendLine("          <openrs:longitudinalG>${"%.3f".format(Locale.ROOT, pt.longitudinalG)}</openrs:longitudinalG>")
                appendLine("          <openrs:brakePressure>${"%.1f".format(Locale.ROOT, pt.brakePressure)}</openrs:brakePressure>")
                appendLine("          <openrs:steeringAngle>${"%.1f".format(Locale.ROOT, pt.steeringAngle)}</openrs:steeringAngle>")
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

    /**
     * True when the drive has a measured route — GPS was available and points were recorded.
     * GPX, RaceChrono and TrackAddict are routes, so only these drives get them.
     */
    fun hasRoute(drive: DriveEntity, points: List<DrivePointEntity>): Boolean =
        drive.hasGps && points.isNotEmpty()

    /**
     * Track files for one drive, in ZIP order: GPX (route only) then the drive CSV. Recorded
     * telemetry gets a CSV whether or not GPS was available (as on main, e5080c8); a no-GPS
     * drive leaves lat / lng empty. A drive with no points gets neither — nothing is made up.
     * A null name leaves that file out (export component off).
     */
    fun driveTrackFiles(
        drive: DriveEntity,
        points: List<DrivePointEntity>,
        gpxTs: String,
        gpxName: String?,
        csvName: String?,
    ): List<Pair<String, String>> = buildList {
        if (points.isEmpty()) return@buildList
        if (gpxName != null && hasRoute(drive, points)) add(gpxName to buildGpx(drive, points, gpxTs))
        if (csvName != null) add(csvName to buildCsv(points, hasGps = drive.hasGps, oilPtuMapping = drive.oilPtuMapping))
    }

    /**
     * Drive telemetry CSV. [hasGps] false writes empty lat / lng (the knock CSV's convention). The format line names the
     * drive's [oilPtuMapping] (#229) — `not_recorded` unless the drive was saved with one; the row count is unchanged.
     */
    fun buildCsv(points: List<DrivePointEntity>, hasGps: Boolean = true, oilPtuMapping: String? = null): String = buildString {
        appendLine("# openrs_csv_v2 oil_ptu_mapping=${oilPtuMappingValue(oilPtuMapping)}")
        appendLine(
            "timestamp_ms,lat,lng,speed_kph,rpm,gear,boost_psi," +
            "coolant_c,oil_c,ambient_c,rdu_c,ptu_c,fuel_pct," +
            "tire_press_lf_psi,tire_press_rf_psi,tire_press_lr_psi,tire_press_rr_psi," +
            "tire_temp_lf_c,tire_temp_rf_c,tire_temp_lr_c,tire_temp_rr_c," +
            "wheel_fl_kph,wheel_fr_kph,wheel_rl_kph,wheel_rr_kph," +
            "lateral_g,longitudinal_g,brake_pressure,steering_angle,throttle_pct,drive_mode,race_ready," +
            "tip_actual_kpa,boost_target_kpa,wgdc_pct," +
            // #207 provenance: empty for points recorded before the rule (never inferred); boost_psi empty when no source
            "boost_source,baro_kpa," +
            // #271: PCM 0xF40B MAP, kPa absolute, beside TIP; empty when no fresh decode at the point (or before v15);
            // saturated = 1 at the byte's 255 ceiling (at least 255 kPa, not a measurement)
            "map_f40b_kpa_abs,map_f40b_saturated"
        )
        points.forEach { pt ->
            appendLine(
                "${pt.timestamp}," +
                (if (hasGps) "${"%.6f".format(Locale.ROOT, pt.lat)},${"%.6f".format(Locale.ROOT, pt.lng)}," else ",,") +
                "${"%.2f".format(Locale.ROOT, pt.speedKph)}," +
                "${pt.rpm}," +
                "${csvEscape(pt.gear)}," +
                "${pt.boostReadingPsi()?.let { "%.3f".format(Locale.ROOT, it) } ?: ""}," +
                "${"%.1f".format(Locale.ROOT, pt.coolantTempC)}," +
                "${"%.1f".format(Locale.ROOT, pt.oilTempC)}," +
                "${"%.1f".format(Locale.ROOT, pt.ambientTempC)}," +
                "${"%.1f".format(Locale.ROOT, pt.rduTempC)}," +
                "${"%.1f".format(Locale.ROOT, pt.ptuTempC)}," +
                "${"%.1f".format(Locale.ROOT, pt.fuelLevelPct)}," +
                "${"%.1f".format(Locale.ROOT, pt.tirePressLF)}," +
                "${"%.1f".format(Locale.ROOT, pt.tirePressRF)}," +
                "${"%.1f".format(Locale.ROOT, pt.tirePressLR)}," +
                "${"%.1f".format(Locale.ROOT, pt.tirePressRR)}," +
                "${"%.1f".format(Locale.ROOT, pt.tireTempLF)}," +
                "${"%.1f".format(Locale.ROOT, pt.tireTempRF)}," +
                "${"%.1f".format(Locale.ROOT, pt.tireTempLR)}," +
                "${"%.1f".format(Locale.ROOT, pt.tireTempRR)}," +
                "${"%.2f".format(Locale.ROOT, pt.wheelSpeedFL)}," +
                "${"%.2f".format(Locale.ROOT, pt.wheelSpeedFR)}," +
                "${"%.2f".format(Locale.ROOT, pt.wheelSpeedRL)}," +
                "${"%.2f".format(Locale.ROOT, pt.wheelSpeedRR)}," +
                "${"%.4f".format(Locale.ROOT, pt.lateralG)}," +
                "${"%.4f".format(Locale.ROOT, pt.longitudinalG)}," +
                "${"%.1f".format(Locale.ROOT, pt.brakePressure)}," +
                "${"%.1f".format(Locale.ROOT, pt.steeringAngle)}," +
                "${"%.1f".format(Locale.ROOT, pt.throttlePct)}," +
                "${csvEscape(pt.driveMode)}," +
                "${pt.isRaceReady}," +
                "${"%.2f".format(Locale.ROOT, pt.tipActualKpa)}," +
                "${"%.2f".format(Locale.ROOT, pt.boostTargetKpa)}," +
                "${"%.1f".format(Locale.ROOT, pt.wgdcPct)}," +
                "${pt.boostSource ?: ""}," +
                "${pt.baroKpa?.let { "%.1f".format(Locale.ROOT, it) } ?: ""}," +
                "${pt.mapKpa?.let { "%.0f".format(Locale.ROOT, it) } ?: ""}," +
                (pt.mapKpa?.let { if (com.openrs.dash.data.MapF40b.isSaturated(it)) "1" else "0" } ?: "")
            )
        }
    }

    /**
     * Knock events table CSV. One row per captured KR retard event.
     * v2 (rc.5): trailing `cylinder` column (1-4; 0 = legacy row, cylinder 1 only).
     */
    fun buildKnockCsv(events: List<com.openrs.dash.data.KnockEventEntity>): String = buildString {
        appendLine("# openrs_knock_csv_v2")
        appendLine("timestamp_ms,kr_deg,rpm,boost_psi,iat_c,throttle_pct,gear_est,lat,lng,cylinder,boost_source")
        events.forEach { ev ->
            appendLine(
                "${ev.timestamp}," +
                "${"%.2f".format(Locale.ROOT, ev.krDeg)}," +
                "${ev.rpm}," +
                "${if (ev.boostSource == "NONE") "" else "%.2f".format(Locale.ROOT, ev.boostPsi)}," +
                "${"%.1f".format(Locale.ROOT, ev.iatC)}," +
                "${"%.1f".format(Locale.ROOT, ev.throttlePct)}," +
                "${ev.gearEst}," +
                "${ev.lat?.let { "%.6f".format(Locale.ROOT, it) } ?: ""}," +
                "${ev.lng?.let { "%.6f".format(Locale.ROOT, it) } ?: ""}," +
                "${ev.cylinder}," +
                (ev.boostSource ?: "")
            )
        }
    }

    fun buildSummary(drive: DriveEntity, points: List<DrivePointEntity>): String = buildString {
        // endTime = 0 while the drive is still recording (diag export mid-drive): measure to the
        // last point instead of printing 0 s; distance/fuel/peaks are only written at drive end.
        val inProgress = drive.endTime <= 0
        val endMs = if (!inProgress) drive.endTime else points.lastOrNull()?.timestamp ?: drive.startTime
        val durationMs = (endMs - drive.startTime).coerceAtLeast(0L)
        val elapsedSec = durationMs / 1000L
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine("  openRS_ Drive Summary")
        appendLine("  App         : v${appVersion} (build ${appBuild})")
        appendLine("  Points      : ${points.size}")
        if (inProgress) appendLine("  Status      : IN PROGRESS — totals (fuel, peaks) are written at drive end")
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine()
        appendLine("  Distance    : ${"%.2f".format(drive.distanceKm)} km  (${"%.2f".format(drive.distanceKm * 0.621371)} mi)")
        appendLine("  Duration    : ${elapsedSec / 3600}h ${(elapsedSec % 3600) / 60}m ${elapsedSec % 60}s")
        appendLine("  Avg Speed   : ${"%.1f".format(drive.avgSpeedKph)} km/h")
        appendLine("  Max Speed   : ${"%.1f".format(drive.maxSpeedKph)} km/h")
        appendLine()
        appendLine("  Fuel Used   : ${"%.2f".format(drive.fuelUsedL)} L")
        appendLine("  Peak RPM    : ${drive.peakRpm}")
        // #207: only a drive recorded under a rule says which source — an older drive's peak is as recorded, source
        // unknown; a drive with no reading says so (never "0.0")
        appendLine("  Peak Boost  : " + when (val peak = com.openrs.dash.data.StoredBoost.drivePeak(drive)) {
            is com.openrs.dash.data.StoredBoost.Reading -> "${"%.1f".format(peak.psi)} PSI (" +
                (if (peak.source == com.openrs.dash.data.BoostSource.TIP_033E) "TIP 0x033E − baro" else "CAN 0x0F8") + ")"
            else -> if (drive.boostPolicy == null) "${"%.1f".format(drive.peakBoostPsi)} PSI (source not recorded)"
                    else "— (no boost reading recorded)"
        })
        appendLine("  Track files : RaceChrono / TrackAddict CSVs carry no pressure column — their only one is named \"Boost\", " +
            "which can't say TIP or a missing source; the drive CSV has every reading with its source")
        appendLine("  Boost source: " + when (drive.boostPolicy) {
            null -> "not recorded — this drive predates per-reading boost provenance (values as recorded)"
            "rc7-boost-v2" -> "per reading (rc7-boost-v2, development builds only): CAN 0x0F8 while fresh unless judged " +
                "not tracking at full throttle, else TIP − measured baro; none → no reading"
            else -> "per reading (${drive.boostPolicy}): TIP 0x033E − measured baro while fresh, else CAN 0x0F8 while " +
                "fresh; none → no reading"
        })
        appendLine("  Oil / PTU   : " + com.openrs.dash.data.OilPtuMapping.describe(drive.oilPtuMapping))
        appendLine("  Peak Lat G  : ${"%.2f".format(drive.peakLateralG)} g")
        if (drive.avgStftPct != null || drive.avgLtftPct != null) {
            fun pct(v: Double?) = v?.let { "%+.1f%%".format(it) } ?: "—"
            appendLine("  Fuel Trims  : STFT ${pct(drive.avgStftPct)}  LTFT ${pct(drive.avgLtftPct)}  (${drive.trimSamples} warm samples)")
        }
        drive.pullCount?.let { total ->
            val knocked = drive.knockPullCount ?: 0
            val pct = if (total > 0) " (${"%.0f".format(knocked * 100.0 / total)}%)" else ""
            appendLine("  Knock Pulls : $knocked of $total WOT pulls with KR > 0$pct")
        }
        com.openrs.dash.data.BoostSweepStats.from(points)?.let { bs ->
            appendLine("  Boost Sweep : avg Δ ${"%+.1f".format(bs.avgDeltaPsi)} PSI, worst ${"%.1f".format(bs.worstDeltaPsi)} PSI @ ${bs.worstRpm} rpm, " +
                "WGDC max ${"%.0f".format(bs.maxWgdc)}%" +
                (bs.avgWgdcAtPeak?.let { ", ${"%.0f".format(it)}% at peak" } ?: ""))
            for (g in bs.byGear) {
                appendLine("    Gear ${g.gear}    : peak TIP ≈${"%.1f".format(g.peakBoostPsi)} PSI ${if (g.baroMeasured) "vs measured baro" else "vs std atmosphere"}, avg Δ ${"%+.1f".format(g.avgDeltaPsi)} PSI (${g.samples} samples)")
            }
        }
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

    fun buildDtcText(dtcResults: List<DtcResult>, scan: com.openrs.dash.data.DtcScanRecord? = null): String = buildString {
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine("  openRS_ DTC Scan Report")
        appendLine("  App : v${appVersion} (build ${appBuild})")
        if (scan != null) {
            appendLine("  Scan: ${DiagnosticReportBuilder.dtcScanLine(scan)}")
            appendLine("  Modules: " + scan.modules.entries.joinToString(", ") { (m, st) -> "$m ${st?.name ?: "NO_ANSWER"}" })
            scan.notes.forEach { (m, n) -> appendLine("  $m: $n") }    // a write that failed (#215)
        }
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine()
        val grouped = dtcResults.groupBy { it.module }
        val order   = listOf("PCM", "BCM", "ABS", "AWD", "PSCM", "GFM")
        for (mod in order) {
            val codes = grouped[mod] ?: continue
            appendLine("─── $mod ────────────────────────────────────────────────────")
            codes.forEach { dtc ->
                val desc = if (dtc.description.isNotEmpty()) dtc.description else "(no description)"
                appendLine("  ${dtc.displayCode}  [${dtc.status.label}]  $desc")
            }
            appendLine()
        }
        appendLine("Total: ${dtcResults.size} fault code(s) across ${dtcResults.map { it.module }.distinct().size} module(s)")
        appendLine("═══════════════════════════════════════════════════════════")
    }

    /**
     * Build a machine-readable JSON DTC scan report for Sapphire import.
     */
    fun buildDtcJson(dtcResults: List<DtcResult>, scan: com.openrs.dash.data.DtcScanRecord? = null): String {
        val root = JSONObject()
        root.put("version", 1)
        // Additive (#215): scan status + module coverage — Sapphire reads `codes` and ignores unknown keys
        if (scan != null) root.put("scan", DiagnosticReportBuilder.dtcScanJson(scan))
        root.put("timestamp", System.currentTimeMillis())
        root.put("app", "openRS_ v${appVersion}")
        root.put("totalCodes", dtcResults.size)

        val codesArray = org.json.JSONArray()
        for (dtc in dtcResults) {
            val obj = JSONObject().apply {
                put("module", dtc.module)
                put("code", dtc.code)
                // Full fault identity (#215): failure type byte when known; `code` stays the base code for lookups
                dtc.failureType?.let { put("failureType", String.format(java.util.Locale.ROOT, "%02X", it)); put("fullCode", dtc.displayCode) }
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
            // #207 provenance — present only for drives recorded under a rule; absent = unknown, never assumed. A drive
            // recorded under a rule with no boost reading has no peak (never 0); older drives: as recorded
            if (drive.boostPolicy == null || com.openrs.dash.data.StoredBoost.drivePeak(drive) != com.openrs.dash.data.StoredBoost.Unavailable)
                put("peakBoostPsi", drive.peakBoostPsi)
            drive.peakBoostSource?.let { put("peakBoostSource", it) }
            drive.boostPolicy?.let { put("boostPolicy", it) }
            put("peakLateralG", drive.peakLateralG)
            put("peakOilTempC", drive.peakOilTempC)
            put("peakCoolantTempC", drive.peakCoolantTempC)
            // #229: which 0x0F8 bytes oil / PTU came from — present only when saved with the drive; absent = unknown
            drive.oilPtuMapping?.let { put("oilPtuMapping", it) }
            put("fuelUsedL", drive.fuelUsedL)
            put("aggressionScore", drive.aggressionScore)
            put("tags", drive.tags)
            // T5A: fuel economy averages
            if (drive.avgFuelL100km > 0) put("avgFuelL100km", drive.avgFuelL100km)
            if (drive.avgFuelMpg > 0) put("avgFuelMpg", drive.avgFuelMpg)
        }
        root.put("drive", driveObj)

        // ── Fuel trim drift (rc.5) — warm-engine averages of fresh STFT/LTFT polls ──
        if (drive.avgStftPct != null || drive.avgLtftPct != null) {
            root.put("fuelTrim", JSONObject().apply {
                drive.avgStftPct?.let { put("avgStftPct", it) }
                drive.avgLtftPct?.let { put("avgLtftPct", it) }
                put("samples", drive.trimSamples)
            })
        }

        // ── Peak events (best per type from recorded points) ─────
        val peaksArray = JSONArray()
        data class PeakCandidate(val type: PeakType, val value: Double, val lat: Double, val lng: Double, val ts: Long,
                                 val source: String? = null)
        val bestPeaks = mutableMapOf<PeakType, PeakCandidate>()

        for (pt in points) {
            val candidates = listOf(
                PeakCandidate(PeakType.RPM, pt.rpm.toDouble(), pt.lat, pt.lng, pt.timestamp),
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
                peak.source?.let { put("source", it) }
            })
        }
        // #207: one BOOST peak per measurement (TIP, CAN 0x0F8, or legacy without a source) — never one max across
        // them; a genuine 0 is a peak, a point without a reading is none
        points.mapNotNull { pt -> (com.openrs.dash.data.StoredBoost.point(pt) as? com.openrs.dash.data.StoredBoost.Reading)?.let { pt to it.source.name }
                ?: (pt.boostSource == null).takeIf { it }?.let { pt to null } }
            .groupBy({ it.second }, { it.first })
            .forEach { (source, pts) ->
                val best = pts.maxBy { it.boostPsi }
                if (source == null && best.boostPsi <= 0.0) return@forEach        // legacy: as before, a positive peak only
                peaksArray.put(JSONObject().apply {
                    put("type", PeakType.BOOST.name)
                    put("value", best.boostPsi)
                    put("lat", best.lat)
                    put("lng", best.lng)
                    put("timestampMs", best.timestamp)
                    source?.let { put("source", it) }
                })
            }
        root.put("peaks", peaksArray)
        // #207: how many recorded points came from each boost source (only points that carry one)
        points.mapNotNull { it.boostSource }.groupingBy { it }.eachCount().takeIf { it.isNotEmpty() }?.let { counts ->
            root.put("boostSources", JSONObject().apply { counts.forEach { (k, v) -> put(k, v) } })
        }

        // ── Boost sweep (rc.4) ──────────────────────────────────
        com.openrs.dash.data.BoostSweepStats.from(points)?.let { bs ->
            root.put("boostSweep", JSONObject().apply {
                put("avgDeltaPsi", bs.avgDeltaPsi)
                put("worstDeltaPsi", bs.worstDeltaPsi)
                put("worstRpm", bs.worstRpm)
                put("maxWgdcPct", bs.maxWgdc)
                put("wgdcSaturated", bs.wgdcSaturated)
                put("loadedSamples", bs.sampleCount)
                bs.avgWgdcAtPeak?.let { put("avgWgdcAtPeakPct", it) }
                put("byGear", JSONArray().apply {
                    for (g in bs.byGear) put(JSONObject().apply {
                        put("gear", g.gear)
                        put("peakBoostPsi", g.peakBoostPsi)
                        put("avgDeltaPsi", g.avgDeltaPsi)
                        put("samples", g.samples)
                        put("reference", if (g.baroMeasured) "measuredBaro" else "standardAtmosphere")
                    })
                })
            })
        }

        // ── Thermal progression ──────────────────────────────────
        // Read through the same parser as TRIP's TEMP badge and the drive summary (DriveThermalReview): the limits
        // saved with the drive, never the current profile. °C throughout, like the rest of this file.
        val review = DriveThermalReview.from(drive.thermalPeaksJson)
        val thermalObj = JSONObject()
        // Each curve only when its start / peak / end are coherent valid readings (DriveThermalReview.progression):
        // endpoints from the record's first / last fresh readings, else the stored start / stop readings (oil, coolant)
        // or first / last point (PTU, RDU, current records only — a legacy drive-end value is not a peak). Otherwise the
        // curve is left out — consumers treat a missing curve as no data — and the peak stays in `drive` / `thermalRecord`.
        fun firstLast(f: (DrivePointEntity) -> Double) =
            (points.firstOrNull { f(it) > -90 }?.let(f) ?: -99.0) to (points.lastOrNull { f(it) > -90 }?.let(f) ?: -99.0)
        val ptuEnds = firstLast { it.ptuTempC }
        val rduEnds = firstLast { it.rduTempC }
        for ((s, stored) in listOf(
            DriveThermalSensor.OIL to Triple(drive.startOilTempC, drive.peakOilTempC, drive.endOilTempC),
            DriveThermalSensor.COOLANT to Triple(drive.startCoolantTempC, drive.peakCoolantTempC, drive.endCoolantTempC),
            DriveThermalSensor.PTU to Triple(ptuEnds.first, -99.0, ptuEnds.second),
            DriveThermalSensor.RDU to Triple(rduEnds.first, -99.0, rduEnds.second),
        )) {
            val p = review.progression(s, stored.first, stored.second, stored.third).takeIf { it.complete } ?: continue
            thermalObj.put(s.exportKey, JSONObject().apply {
                put("startC", p.startC!!)
                put("peakC", p.peakC!!)
                put("endC", p.endC!!)
            })
        }
        root.put("thermalProgression", thermalObj)
        root.put("thermalRecord", buildThermalRecordJson(review, drive.startTime, drive.oilPtuMapping))

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
                    // no boost: bookmarks store no source (#207), so a value couldn't be named
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
                    // no boost: a lap's peak mixes sources and has no source column (#207)
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
                    // no boost: perf_runs store no source (#207) — shown on PERF with its source, not exported
                    if (run.densityAltFt != null) put("densityAltFt", run.densityAltFt)
                    if (run.ambientTempC > -90) put("ambientTempC", run.ambientTempC)
                })
            }
            root.put("perfRuns", runsArray)
        }

        return root.toString(2)
    }

    /**
     * The drive's thermal record for the profile export: which basis it has, the saved limits per profile segment,
     * the true peaks and — separately — the reading behind each TEMP flag (a higher STREET peak and a lower RACE flag
     * stay distinct). All temperatures °C ("…C" keys). Legacy drives export their drive-end readings as `driveEndC`
     * against the old fixed limit, never as peaks or under a profile; drives without a record say so.
     */
    internal fun buildThermalRecordJson(review: DriveThermalReview, driveStartMs: Long, oilPtuMapping: String? = null): JSONObject = JSONObject().apply {
        put("basis", review.basis.name.lowercase())
        // #229: the drive's own mapping (DriveEntity.oilPtuMapping), never inferred from the basis / record format
        put("oilPtuMapping", oilPtuMappingValue(oilPtuMapping))
        put("oilPtuMappingNote", com.openrs.dash.data.OilPtuMapping.describe(oilPtuMapping))
        put("unit", "°C")
        put("flagged", review.flagged)
        when (review.basis) {
            DriveThermalReview.Basis.PROFILE -> {
                put("flagRule", "reading >= flagThresholdC = $THERMAL_FLAG_FRACTION x critC of the profile in effect when it was recorded")
                put("segments", JSONArray().apply {
                    for (seg in review.segments) put(JSONObject().apply {
                        put("profile", seg.profile)
                        put("startOffsetMs", (seg.startMs - driveStartMs).coerceAtLeast(0L))
                        put("limits", JSONObject().apply {
                            for ((s, l) in seg.limits.toSortedMap()) put(s.exportKey, JSONObject().apply {
                                put("warnC", l.warnC)
                                put("critC", l.critC)
                                put("flagThresholdC", thermalFlagThresholdC(l.critC))
                            })
                        })
                        put("peaksC", JSONObject().apply { for ((s, t) in seg.peaks.toSortedMap()) put(s.exportKey, t) })
                    })
                })
                put("sensors", JSONObject().apply {
                    for (r in review.sensors) put(r.sensor.exportKey, JSONObject().apply {
                        put("peakC", r.peakC)
                        put("peakProfile", r.peakProfile)
                        put("peakLevel", r.peakLevel?.name)
                        put("flagged", r.flagged)
                        if (r.flagged) put("flag", JSONObject().apply {
                            put("readingC", r.flagReadingC)
                            put("profile", r.flagProfile)
                            put("critC", r.flagCritC)
                            put("flagThresholdC", r.flagThresholdC)
                        })
                    })
                })
            }
            DriveThermalReview.Basis.LEGACY -> {
                put("note", "Recorded before thermal limits were saved with drives: drive-end readings against the old " +
                    "fixed limits. No profile applies and they are not peaks (see thermalProgression for peaks).")
                put("flagRule", "driveEndC >= flagThresholdC = $THERMAL_FLAG_FRACTION x fixedLimitC")
                put("sensors", JSONObject().apply {
                    for (r in review.sensors) put(r.sensor.exportKey, JSONObject().apply {
                        put("driveEndC", r.peakC)
                        r.legacyLimitC?.let {
                            put("fixedLimitC", it)
                            put("flagThresholdC", thermalFlagThresholdC(it))
                        }
                        put("flagged", r.flagged)
                    })
                })
            }
            DriveThermalReview.Basis.NONE -> if (review.unreadable) {
                put("reason", "unreadable")
                put("note", "A thermal record is saved with this drive but could not be read (damaged, or a format " +
                    "this version doesn't know): the limits it was judged against are unknown.")
            } else {
                put("reason", "absent")
                put("note", "No thermal record saved with this drive: the limits it was judged against are unknown.")
            }
        }
    }

    private val DriveThermalSensor.exportKey: String get() = key.lowercase()

    /**
     * Build a RaceChrono-compatible CSV.
     * Columns follow RaceChrono CSV v3: timestamp in seconds, speed in m/s,
     * G-forces in g, temps in °C. Custom OBD channels use the rc_ prefix.
     */
    fun buildRaceChronoCsv(points: List<DrivePointEntity>): String = buildString {
        // No pressure column (#207): the format's only one is "Boost Pressure", which can't name TIP or a missing
        // source — the drive CSV carries every reading with its source (see the drive summary note)
        appendLine(
            "Timestamp (s),Latitude (deg),Longitude (deg),Speed (m/s),Speed (kph)," +
            "Lateral Acceleration (G),Longitudinal Acceleration (G)," +
            "RPM (rpm),Throttle Position (%),Gear," +
            "Coolant Temp (C),Oil Temp (C),Brake Pressure"
        )
        if (points.isEmpty()) return@buildString
        val t0 = points.first().timestamp
        points.forEach { pt ->
            val timeSec = (pt.timestamp - t0) / 1000.0
            val speedMs = pt.speedKph / 3.6
            appendLine(
                "${"%.3f".format(Locale.ROOT, timeSec)}," +
                "${"%.6f".format(Locale.ROOT, pt.lat)}," +
                "${"%.6f".format(Locale.ROOT, pt.lng)}," +
                "${"%.2f".format(Locale.ROOT, speedMs)}," +
                "${"%.2f".format(Locale.ROOT, pt.speedKph)}," +
                "${"%.4f".format(Locale.ROOT, pt.lateralG)}," +
                "${"%.4f".format(Locale.ROOT, pt.longitudinalG)}," +
                "${pt.rpm}," +
                "${"%.1f".format(Locale.ROOT, pt.throttlePct)}," +
                "${csvEscape(pt.gear)}," +
                "${"%.1f".format(Locale.ROOT, pt.coolantTempC)}," +
                "${"%.1f".format(Locale.ROOT, pt.oilTempC)}," +
                "${"%.1f".format(Locale.ROOT, pt.brakePressure)}"
            )
        }
    }

    /**
     * Build a TrackAddict-compatible CSV.
     * Uses TrackAddict's "Time,Latitude,Longitude,Speed (MPH),..." header format.
     * Speed in MPH, G-forces in g, temps in °F (TrackAddict convention).
     */
    fun buildTrackAddictCsv(points: List<DrivePointEntity>): String = buildString {
        // No pressure column (#207) — as RaceChrono
        appendLine(
            "Time,Latitude,Longitude,Speed (MPH),Lateral Gs,Lineal Gs," +
            "RPM,Throttle (%),Gear," +
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
                "${"%.3f".format(Locale.ROOT, timeSec)}," +
                "${"%.6f".format(Locale.ROOT, pt.lat)}," +
                "${"%.6f".format(Locale.ROOT, pt.lng)}," +
                "${"%.1f".format(Locale.ROOT, speedMph)}," +
                "${"%.4f".format(Locale.ROOT, pt.lateralG)}," +
                "${"%.4f".format(Locale.ROOT, pt.longitudinalG)}," +
                "${pt.rpm}," +
                "${"%.1f".format(Locale.ROOT, pt.throttlePct)}," +
                "${csvEscape(pt.gear)}," +
                "${"%.1f".format(Locale.ROOT, coolantF)}," +
                "${"%.1f".format(Locale.ROOT, oilF)}," +
                "${"%.1f".format(Locale.ROOT, pt.brakePressure)}"
            )
        }
    }

    internal fun csvEscape(value: String): String =
        if (value.contains(',') || value.contains('"') || value.contains('\n'))
            "\"${value.replace("\"", "\"\"")}\"" else value
}

/** A point's boost as a reading (#207): null when it was recorded with no fresh source; older points are as recorded. */
internal fun DrivePointEntity.boostReadingPsi(): Double? = if (boostSource == "NONE") null else boostPsi
