package com.openrs.dash.diagnostics

import com.openrs.dash.BuildConfig
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.NrcTracker
import com.openrs.dash.data.VehicleState
import org.json.JSONArray
import org.json.JSONObject

/**
 * Builds the human-readable summary text and machine-readable JSON detail
 * for diagnostic export bundles.
 *
 * Extracted from DiagnosticExporter to separate report generation from
 * ZIP packaging and sharing. JSON output uses [JSONObject] for correct
 * escaping and structural safety (replaces hand-rolled string concatenation).
 */
internal object DiagnosticReportBuilder {

    /** Epoch [eventMs] as an offset into the session (NrcTracker stamps epoch ms); never negative. */
    internal fun sessionOffsetMs(eventMs: Long, sessionStartMs: Long): Long =
        if (sessionStartMs <= 0L) 0L else (eventMs - sessionStartMs).coerceAtLeast(0L)

    /** One summary line for the session's DTC scan (#215). */
    internal fun dtcScanLine(scan: com.openrs.dash.data.DtcScanRecord?): String {
        if (scan == null) return "No scan this session"
        val coverage = "${scan.modulesAnswered}/${scan.modules.size} modules scanned"
        val outcome = when (scan.status) {
            com.openrs.dash.data.DtcScanStatus.COMPLETE  -> if (scan.codes.isEmpty()) "COMPLETE — no codes" else "COMPLETE — ${scan.codes.size} code(s)"
            com.openrs.dash.data.DtcScanStatus.PARTIAL   -> "PARTIAL — ${scan.codes.size} code(s) from the modules scanned; not a clean scan"
            com.openrs.dash.data.DtcScanStatus.FAILED    -> "FAILED${scan.message?.let { " ($it)" } ?: ""}"
            com.openrs.dash.data.DtcScanStatus.CANCELLED -> "CANCELLED before it finished"
            com.openrs.dash.data.DtcScanStatus.INTERRUPTED -> "INTERRUPTED before it finished${scan.message?.let { " ($it)" } ?: ""}; not a clean scan"
            com.openrs.dash.data.DtcScanStatus.RUNNING   -> "RUNNING — not finished when exported"
        }
        return "$outcome · $coverage · ${scan.source}"
    }

    /** `dtcScan` block of the detail JSON — `{"status":"NONE"}` when no scan ran (#215). */
    internal fun dtcScanJson(scan: com.openrs.dash.data.DtcScanRecord?): JSONObject = JSONObject().apply {
        if (scan == null) { put("status", "NONE"); return@apply }
        put("status", scan.status.name)
        put("source", scan.source)
        put("finishedAtMs", scan.finishedAtMs)
        put("codeCount", scan.codes.size)
        put("modulesAnswered", scan.modulesAnswered)
        put("modulesTotal", scan.modules.size)
        put("modules", JSONObject().apply { scan.modules.forEach { (m, st) -> put(m, st?.name ?: "NO_ANSWER") } })
        // Why a module has no valid reply, where the app knows it (a write that failed — #215)
        if (scan.notes.isNotEmpty()) put("moduleNotes", JSONObject().apply { scan.notes.forEach { (m, n) -> put(m, n) } })
        scan.message?.let { put("message", it) }
    }

    /** How this session's adapter status was obtained — or why there is none (#233). */
    internal fun adapterStatusLine(record: AdapterStatusRecord, sessionStartMs: Long): String = when (record) {
        is AdapterStatusRecord.Fetched -> {
            val s = sessionOffsetMs(record.atMs, sessionStartMs) / 1000
            "from /check_status over Wi-Fi at +%02d:%02d this session".format(s / 60, s % 60)
        }
        is AdapterStatusRecord.NotRequested -> "not available — ${record.reason}"
        AdapterStatusRecord.Requested -> "not available — /check_status requested, no answer when exported"
        AdapterStatusRecord.Failed -> "not available — /check_status request failed this session"
    }

    /** `adapterStatusSource` block of the detail JSON: state + detail, always present (#233). */
    internal fun adapterStatusSourceJson(record: AdapterStatusRecord, sessionStartMs: Long): JSONObject = JSONObject().apply {
        when (record) {
            is AdapterStatusRecord.Fetched -> {
                put("state", "FETCHED"); put("via", "check_status_wifi")
                put("sessionOffsetMs", sessionOffsetMs(record.atMs, sessionStartMs))
            }
            is AdapterStatusRecord.NotRequested -> { put("state", "NOT_REQUESTED"); put("detail", record.reason) }
            AdapterStatusRecord.Requested -> put("state", "NO_ANSWER")
            AdapterStatusRecord.Failed -> put("state", "FAILED")
        }
    }

    /**
     * Text + JSON of one export from ONE adapter-status snapshot (#233): a reply landing while the export is written
     * can't make the two files (or two lines of one file) disagree. The exporter uses this at every call site.
     */
    fun buildSummaryAndDetail(
        ts: String,
        snap: AdapterStatusSnapshot = DiagnosticLogger.snapshotAdapterStatus(),
        // Likewise one DTC scan record (immutable) for both files and the export's dtc_scan_* files (#238 review)
        dtcScan: com.openrs.dash.data.DtcScanRecord? = DiagnosticLogger.lastDtcScan,
        // And one reading of the counters, or a live export's two files disagree (L12)
        counters: DiagnosticLogger.ExportCounters = DiagnosticLogger.exportCounters,
    ): Pair<String, String> =
        buildSummaryText(ts, snap, dtcScan, counters) to buildDetailJson(ts, snap, dtcScan, counters)

    fun buildSummaryText(
        ts: String,
        snap: AdapterStatusSnapshot = DiagnosticLogger.snapshotAdapterStatus(),
        dtcScan: com.openrs.dash.data.DtcScanRecord? = DiagnosticLogger.lastDtcScan,
        counters: DiagnosticLogger.ExportCounters = DiagnosticLogger.exportCounters,
    ): String = buildString {
        val log = DiagnosticLogger
        val vs  = log.lastVehicleState
        val p   = log.sessionPrefs

        appendLine("═══════════════════════════════════════════════════════════")
        appendLine("  openRS_ Diagnostic Report")
        appendLine("  App       : v${BuildConfig.RELEASE_NAME} (build ${BuildConfig.VERSION_CODE}, ${BuildConfig.BUILD_TYPE})")
        appendLine("  Source    : ${BuildConfig.SOURCE_COMMIT}${if (BuildConfig.SOURCE_DIRTY) " (uncommitted changes)" else ""}")
        appendLine("  Generated : $ts")
        appendLine("  Session   : ${log.formatDuration(log.sessionDurationMs)}")
        appendLine("  Schema    : export schema $EXPORT_SCHEMA_VERSION")
        appendLine("═══════════════════════════════════════════════════════════")
        appendLine()

        // ── Connection
        appendLine("─── CONNECTION ────────────────────────────────────────────")
        appendLine("  Host     : ${log.sessionHost}:${log.sessionPort}")
        if (log.sessionTransport.isNotEmpty())
            appendLine("  Transport: ${log.sessionTransport}")
        appendLine("  Firmware : ${log.firmwareVersion}")
        val connEvent = log.sessionEvents.lastOrNull { it.type == "SESSION" }
        if (connEvent != null) appendLine("  Last evt : ${connEvent.message}")
        appendLine()

        // ── DTC scan (#215): no scan, a clean scan and a partial / failed / cancelled one stay distinct
        appendLine("─── DTC SCAN ──────────────────────────────────────────────")
        appendLine("  ${dtcScanLine(dtcScan)}")
        dtcScan?.notes?.forEach { (m, n) -> appendLine("  $m: $n") }
        appendLine()

        // ── Adapter device status (/check_status) — this session's only; "Firmware :" above is the live handshake (#233)
        appendLine("─── ADAPTER ──────────────────────────────────────────────")
        appendLine("  Status   : ${adapterStatusLine(snap.record, snap.sessionStartMs)}")
        snap.deviceStatus?.let { ds ->
            if (ds.hardwareVersion.isNotBlank()) appendLine("  Hardware : ${LogText.sanitize(ds.hardwareVersion)}")
            if (ds.firmwareVersion.isNotBlank()) appendLine("  Device FW: v${LogText.sanitize(ds.firmwareVersion)}")
            if (ds.deviceId.isNotBlank())        appendLine("  Device ID: $DEVICE_ID_MASKED")
            if (ds.canDatarate.isNotBlank())     appendLine("  CAN Bus  : ${LogText.sanitize("${ds.canDatarate} ${ds.canMode}")}")
            if (ds.obdPortVoltage > 0)           appendLine("  OBD Port : ${"%.1f".format(ds.obdPortVoltage)}V")
            if (ds.bleStatus.isNotBlank())        appendLine("  BLE      : ${LogText.sanitize(ds.bleStatus)}")
            if (ds.protocol.isNotBlank())         appendLine("  Protocol : ${LogText.sanitize(ds.protocol)}")
            if (ds.sleepStatus.isNotBlank())      appendLine("  Sleep    : ${LogText.sanitize("${ds.sleepStatus} (${ds.sleepVolt}V)")}")
        }
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
            // Boost source rule (#207), judged at export time: what the gauge shows now and why
            val nowMs = System.currentTimeMillis()
            val boost = com.openrs.dash.data.BoostSession.read(vs, nowMs)
            fun age(tag: String) = vs.fieldLastUpdateMs[tag]?.let { "${(nowMs - it) / 1000} s ago" } ?: "never"
            appendLine("  Boost      : " + (boost.gaugeKpa?.let { "%.1f kPa gauge (%.1f PSI), source %s".format(it, it * 0.14503773, boost.source.name) }
                ?: "no fresh source") + " · policy ${com.openrs.dash.data.BoostRule.POLICY}")
            appendLine("  Boost src  : CAN 0x0F8 ${"%.0f".format(vs.boostKpa - 101.325)} kPa gauge (received ${age("boostKpa")}) · " +
                "TIP ${"%.1f".format(vs.tipActualKpa)} kPa abs (decoded ${age("tipActualKpa")}) · baro ${"%.1f".format(vs.barometricPressure)} kPa")
            appendLine("  Coolant    : ${"%.0f".format(vs.coolantTempC)} °C")
            appendLine("  Oil Temp   : ${"%.0f".format(vs.oilTempC)} °C  (0x0F8 byte 7 − 60, #229)")
            appendLine("  Intake     : ${"%.0f".format(vs.intakeTempC)} °C")
            appendLine("  Ambient    : ${"%.1f".format(vs.ambientTempC)} °C")
            appendLine("  Baro       : ${"%.1f".format(vs.barometricPressure)} kPa")
            appendLine("  Lat G      : ${"%.3f".format(vs.lateralG)} g")
            appendLine("  Lon G      : ${"%.3f".format(vs.longitudinalG)} g")
            appendLine("  Drive Mode : ${vs.driveMode.label}${modeProvenance(vs, nowMs)}")
            appendLine("  ESC        : ${vs.escStatus.diagnostic}")
            appendLine("  Gear       : ${vs.gearDisplay}")
            appendLine("  Battery    : ${"%.2f".format(vs.batteryVoltage)} V  (PCM 0x0304 as reported; last reply ${age("batteryVoltage")})")
            appendLine("  Fuel       : ${"%.0f".format(vs.fuelLevelPct)} %")
            appendLine("  Throttle   : ${"%.1f".format(vs.throttlePct)} %  (0x076 ${if (vs.throttleHasSource) "seen" else "absent — pedal ${"%.1f".format(vs.accelPedalPct)} % used"})")
            appendLine("  Torque     : ${"%.0f".format(vs.torqueAtTrans)} Nm")
            appendLine("  TPMS LF/RF : ${"%.0f".format(vs.tirePressLF)} / ${"%.0f".format(vs.tirePressRF)} PSI")
            appendLine("  TPMS LR/RR : ${"%.0f".format(vs.tirePressLR)} / ${"%.0f".format(vs.tirePressRR)} PSI")
            appendLine("  AWD L/R    : ${"%.0f".format(vs.awdLeftTorque)} / ${"%.0f".format(vs.awdRightTorque)} Nm")
            appendLine("  RDU Temp   : ${"%.0f".format(vs.rduTempC)} °C")
            appendLine("  PTU Temp   : ${"%.0f".format(vs.ptuTempC)} °C  (0x0F8 byte 1 − 50, #229)")
            appendLine("  ETC Act/Des: ${"%.1f".format(vs.etcAngleActual)}° / ${"%.1f".format(vs.etcAngleDesired)}°")
            appendLine("  WGDC       : ${"%.1f".format(vs.wgdcDesired)} %")
            appendLine("  KR Cyl1-4  : ${com.openrs.dash.ui.formatKr(vs.ignCorrCyl1)} / ${com.openrs.dash.ui.formatKr(vs.ignCorrCyl2)} / ${com.openrs.dash.ui.formatKr(vs.ignCorrCyl3)} / ${com.openrs.dash.ui.formatKr(vs.ignCorrCyl4)}")
            appendLine("  Load/Timing: ${"%.1f".format(vs.calcLoad)} % / ${"%.1f".format(vs.timingAdvance)}°")
            appendLine("  Spark Adv  : ${if (vs.sparkAdvance > -90) "%.1f°".format(vs.sparkAdvance) else "— (not received)"}")
            appendLine("  Cmd λ / O2 : ${"%.3f".format(vs.commandedAfr)} / ${"%.3f".format(vs.o2Voltage)} V")
            appendLine("  OAR        : ${"%.4f".format(vs.octaneAdjustRatio)}")
            appendLine("  TIP Act/Des: ${"%.1f".format(vs.tipActualKpa)} / ${"%.1f".format(vs.tipDesiredKpa)} kPa")
            appendLine("  VCT I/E    : ${"%.1f".format(vs.vctIntakeAngle)}° / ${"%.1f".format(vs.vctExhaustAngle)}°")
            appendLine("  Oil Life   : ${"%.0f".format(vs.oilLifePct)} %")
            appendLine("  HP Fuel Rl : ${"%.0f".format(vs.hpFuelRailPsi)} PSI")
            appendLine("  LP Fuel    : ${"%.0f".format(vs.fuelRailPressure)} kPa")
            appendLine("  Charge Air : ${"%.1f".format(vs.chargeAirTempC)} °C")
            appendLine("  Catalyst   : ${"%.0f".format(vs.catalyticTempC)} °C")
            appendLine("  Odometer   : ${vs.odometerKm} km")
            appendLine("  Batt SOC   : ${"%.0f".format(vs.batterySoc)} %")
            appendLine("  Batt Temp  : ${"%.1f".format(vs.batteryTempC)} °C")
            appendLine("  Cabin Temp : ${"%.1f".format(vs.cabinTempC)} °C")
            appendLine("  RDU Active : ${vs.rduEnabled ?: "—"}")
            appendLine("  PDC Active : ${vs.pdcEnabled ?: "—"}")
            appendLine("  FENG Active: ${vs.fengEnabled ?: "—"}")
            appendLine("  LC Armed   : ${vs.lcArmed ?: "—"}")
            appendLine("  LC RPM     : ${if (vs.lcRpmTarget >= 0) vs.lcRpmTarget.toString() else "—"}")
            appendLine("  ASS Active : ${vs.assEnabled ?: "—"}")
            appendLine("  FPS        : ${"%.0f".format(vs.framesPerSecond)}")
            appendLine("  Data Mode  : CAN")
            appendLine()
        }

        // ── SLCAN log info
        val slcanLines = log.slcanLineCount
        val slcanFile  = log.slcanLogFile
        appendLine("─── SLCAN RAW LOG (Option C) ───────────────────────────────")
        if (slcanFile != null && slcanLines > 0) {
            val sizeMb = slcanFile.length() / 1_048_576.0
            appendLine("  Lines written : $slcanLines")
            appendLine("  File size     : ${"%.2f".format(sizeMb)} MB (uncompressed)")
            appendLine("  File included : slcan_log_$ts.log in this ZIP")
            appendLine("  Compatible with SavvyCAN, Kayak, candump for offline analysis")
        } else {
            appendLine("  No SLCAN log recorded (log directory not available this session)")
        }
        appendLine()

        // ── CAN frame inventory (Option B)
        val inventory = log.frameInventorySnapshot
        val changedCount = inventory.values.count { it.hasChanged }
        appendLine("─── CAN FRAME INVENTORY  (${inventory.size} unique IDs, $changedCount dynamic) ──")
        appendLine("  Legend: ✓=decoded  ?=unknown  Δ=bytes changed during session")
        appendLine("  Format: TAG ID | count | Δ | last raw hex | decoded | issues")
        appendLine()

        val knownIds = CanDecoder.KNOWN_IDS   // single source — the old hand-copied set went stale

        inventory.entries.sortedBy { it.key }.forEach { (id, info) ->
            val tag      = if (id in knownIds) "✓" else "?"
            val changed  = if (info.hasChanged) "Δ" else " "
            val issueStr = if (info.validationIssues.isEmpty()) ""
                           else "  ⚠ ${info.validationIssues.joinToString("; ")}"
            val decoded  = if (info.lastDecoded.isEmpty()) "(no decoder)" else info.lastDecoded
            appendLine("  $tag 0x%03X | %6d | $changed | %-23s | %-40s$issueStr"
                .format(id, info.totalReceived, info.lastRawHex.take(23), decoded.take(40)))
        }
        appendLine()

        // ── Periodic samples for dynamic (changed) frames (Option B)
        val dynamicIds = inventory.entries.filter { it.value.hasChanged && it.value.periodicSamples.isNotEmpty() }
            .sortedBy { it.key }
        if (dynamicIds.isNotEmpty()) {
            appendLine("─── PERIODIC SAMPLES — dynamic IDs (Option B, 30 s intervals) ─")
            appendLine("  These show how each frame's bytes evolved during the drive.")
            appendLine()
            dynamicIds.forEach { (id, info) ->
                val tag     = if (id in knownIds) "✓" else "?"
                val sCount  = info.periodicSamples.size
                appendLine("  $tag 0x%03X  [first: ${info.firstRawHex}]  ($sCount samples)".format(id))
                info.periodicSamples.forEach { s ->
                    appendLine("    +${log.formatDuration(s.relMs)} → ${s.rawHex}")
                }
                appendLine("          last: ${info.lastRawHex}")
                appendLine()
            }
        }

        // ── Validation issues summary
        val issues = inventory.entries.flatMap { (id, info) ->
            info.validationIssues.map { "0x%03X: $it".format(id) }
        }
        appendLine("─── VALIDATION ISSUES (${issues.size}) ────────────────────────────────")
        if (issues.isEmpty()) {
            appendLine("  None — all decoded values within expected physical ranges")
        } else {
            issues.forEach { appendLine("  ⚠ $it") }
        }
        appendLine()

        // ── NRC suppression
        val nrc = log.nrcSuppressed
        if (nrc.isNotEmpty()) {
            appendLine("─── NRC SUPPRESSION (${nrc.size} DID${if (nrc.size > 1) "s" else ""} suppressed) ──────────────────")
            nrc.entries.sortedBy { it.value.firstSeenMs }.forEach { (did, rec) ->
                val nrcName = when (rec.nrcCode) {
                    NrcTracker.NRC_REQUEST_OUT_OF_RANGE -> "requestOutOfRange"
                    NrcTracker.NRC_CONDITIONS_NOT_CORRECT -> "conditionsNotCorrect"
                    NrcTracker.NRC_SECURITY_ACCESS_DENIED -> "securityAccessDenied"
                    else -> "0x%02X".format(rec.nrcCode)
                }
                appendLine("  DID 0x%04X: NRC 0x%02X (%s) — suppressed at +%s (after %d)".format(
                    did, rec.nrcCode, nrcName,
                    log.formatDuration(sessionOffsetMs(rec.firstSeenMs, log.sessionStartMs)),
                    rec.suppressedAfterCount
                ))
            }
            appendLine()
        }
        // Link integrity (rc.8 #253): counts only — what writes established, what framing / length checks refused
        linkIntegrityText(counters.integrity, counters.nrcUnattributed).forEach { appendLine(it) }
        appendLine()
        didInventoryText(counters.dids).forEach { appendLine(it) }   // rc.8 #253, additive
        appendLine()
        logLimitsText(counters.limits, knockSessionDropped(), counters.integrity).forEach { appendLine(it) }
        appendLine()

        // Unattributed NRCs: exact count; their event lines are folded to ≤ 1 per minute per kind (#216)
        if (counters.nrcUnattributed > 0) {
            appendLine("─── NRC UNATTRIBUTED ──────────────────────────────────────")
            appendLine("  ${counters.nrcUnattributed} this session (not counted toward suppression; event lines show the " +
                "first of each kind, then one summary per minute)")
            appendLine()
        }

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
        appendLine("─── RECENT DECODE TRACE (last ${trace.size} of up to 10,000) ──────────")
        appendLine("  Format: +time | ID | raw hex | decoded | ⚠issue")
        trace.forEach { t ->
            val issStr = if (t.issue != null) "  ⚠ ${t.issue}" else ""
            appendLine("  +${log.formatDuration(t.relMs)} | ${t.idHex} | ${t.rawHex.take(23).padEnd(23)} | ${t.decoded.take(35)}$issStr")
        }
        appendLine()

        // ── DID probe results
        val probes = log.probeSessions
        if (probes.isNotEmpty()) {
            appendLine("─── DID PROBE RESULTS (${probes.size} session${if (probes.size > 1) "s" else ""}) ──────────────────")
            probes.forEach { ps ->
                val found   = ps.results.count { it.status == "FOUND" }
                val nrc     = ps.results.count { it.status == "NRC" }
                val timeout = ps.results.count { it.status == "TIMEOUT" }
                val ambiguous = ps.results.count { it.status == PROBE_NRC_AMBIGUOUS }
                appendLine("  ${ps.module} (0x${"%03X".format(ps.requestId)}→0x${"%03X".format(ps.responseId)}) @ +${log.formatDuration(ps.relMs)}")
                val probed = if (ps.outcome == DiagnosticLogger.PROBE_COMPLETE) "${ps.results.size} probed"
                    else "${ps.outcome} — ${ps.results.size} of ${ps.planned} probed"
                appendLine("    $probed — $found found, $nrc rejected, $timeout timeout" +
                    if (ambiguous > 0) ", $ambiguous NRC owner ambiguous" else "")
                ps.results.filter { it.status == "FOUND" }.forEach { r ->
                    appendLine("    ✓ 0x${"%04X".format(r.did)}  ${r.responseHex}")
                }
            }
            appendLine()
        }

        appendLine("═══════════════════════════════════════════════════════════")
        appendLine("  END OF REPORT — full data in diagnostic_detail_$ts.json")
        if (slcanLines > 0) appendLine("  SLCAN log    — slcan_log_$ts.log  ($slcanLines frames)")
        appendLine("═══════════════════════════════════════════════════════════")
    }

    // ── JSON detail (built with JSONObject for safe escaping + structure) ─────

    fun buildDetailJson(
        ts: String,
        snap: AdapterStatusSnapshot = DiagnosticLogger.snapshotAdapterStatus(),
        dtcScan: com.openrs.dash.data.DtcScanRecord? = DiagnosticLogger.lastDtcScan,
        counters: DiagnosticLogger.ExportCounters = DiagnosticLogger.exportCounters,
    ): String {
        val log = DiagnosticLogger
        val vs  = log.lastVehicleState
        val p   = log.sessionPrefs

        val root = JSONObject()

        // meta
        root.put("meta", JSONObject().apply {
            put("app", "openRS_")
            put("appVersion", BuildConfig.VERSION_NAME)
            put("appBuild", BuildConfig.VERSION_CODE)
            DiagnosticExporter.putBuildIdentity(this)
            put("generatedAt", ts)
            put("sessionDurationMs", log.sessionDurationMs)
            put("sessionDurationHuman", log.formatDuration(log.sessionDurationMs))
            put("firmware", log.firmwareVersion)
            put("isOpenRsFirmware", log.isOpenRsFirmware)
            put("host", log.sessionHost)
            put("port", log.sessionPort)
            if (log.sessionTransport.isNotEmpty()) put("transport", log.sessionTransport)
            put("slcanLinesLogged", log.slcanLineCount)
            put("slcanFileIncluded", log.slcanLogFile != null && log.slcanLineCount > 0)
        })

        root.put("dtcScan", dtcScanJson(dtcScan))

        // settings
        if (p != null) {
            root.put("settings", JSONObject().apply {
                put("speedUnit", "${p.speedUnit}")
                put("tempUnit", "${p.tempUnit}")
                put("boostUnit", "${p.boostUnit}")
                put("tireUnit", "${p.tireUnit}")
                put("tireLowPsi", p.tireLowPsi)
                put("screenOn", p.screenOn)
                put("autoReconnect", p.autoReconnect)
                put("reconnectIntervalSec", p.reconnectIntervalSec)
            })
        }

        // adapter device status — this session's /check_status only; how it was obtained (or why not) always (#233)
        root.put("adapterStatusSource", adapterStatusSourceJson(snap.record, snap.sessionStartMs))
        snap.deviceStatus?.let { ds ->
            root.put("adapterStatus", JSONObject().apply {
                put("hardwareVersion", LogText.sanitize(ds.hardwareVersion))
                put("firmwareVersion", LogText.sanitize(ds.firmwareVersion))
                put("deviceId", if (ds.deviceId.isBlank()) "" else DEVICE_ID_MASKED)   // key kept: readers see no removed field
                put("canDatarate", LogText.sanitize(ds.canDatarate))
                put("canMode", LogText.sanitize(ds.canMode))
                put("protocol", LogText.sanitize(ds.protocol))
                put("bleStatus", LogText.sanitize(ds.bleStatus))
                put("obdPortVoltage", ds.obdPortVoltage)
                put("sleepStatus", LogText.sanitize(ds.sleepStatus))
                put("sleepVolt", LogText.sanitize(ds.sleepVolt))
                put("wifiMode", LogText.sanitize(ds.wifiMode))
            })
        }

        // vehicleState
        root.put("vehicleState", if (vs != null) vs.toJsonObject() else JSONObject())

        // canFrameInventory
        val inventory = log.frameInventorySnapshot
        root.put("canFrameInventory", JSONObject().apply {
            inventory.entries.sortedBy { it.key }.forEach { (id, info) ->
                put("0x%03X".format(id), JSONObject().apply {
                    put("totalReceived", info.totalReceived)
                    put("firstRawHex", info.firstRawHex)
                    put("lastRawHex", info.lastRawHex)
                    put("hasChanged", info.hasChanged)
                    put("lastDecoded", info.lastDecoded)
                    put("validationIssues", JSONArray(info.validationIssues.toList()))
                    put("periodicSamples", JSONArray().apply {
                        info.periodicSamples.forEach { s ->
                            put(JSONObject().apply {
                                put("relMs", s.relMs)
                                put("rawHex", s.rawHex)
                            })
                        }
                    })
                })
            }
        })

        // fpsTimeline
        root.put("fpsTimeline", JSONArray().apply {
            log.fpsTimeline.forEach { pt ->
                put(JSONObject().apply {
                    put("relMs", pt.relMs)
                    put("fps", pt.fps)
                })
            }
        })

        // decodeTrace
        root.put("decodeTrace", JSONArray().apply {
            log.decodeTrace.forEach { t ->
                put(JSONObject().apply {
                    put("relMs", t.relMs)
                    put("id", t.idHex)
                    put("raw", t.rawHex)
                    put("decoded", t.decoded)
                    put("issue", t.issue ?: JSONObject.NULL)
                })
            }
        })

        // sessionEvents
        root.put("sessionEvents", JSONArray().apply {
            log.sessionEvents.forEach { ev ->
                put(JSONObject().apply {
                    put("relMs", ev.relMs)
                    put("type", ev.type)
                    put("message", ev.message)
                })
            }
        })

        root.put("nrcUnattributedTotal", counters.nrcUnattributed)   // exact, even though lines are folded (#216)
        root.put("linkIntegrity", linkIntegrityJson(counters.integrity, counters.nrcUnattributed))   // rc.8 #253, additive
        root.put("didInventory", didInventoryJson(counters.dids))   // rc.8 #253, additive
        root.put("logLimits", logLimitsJson(counters.limits, knockSessionDropped()))  // additive
        root.put("exportSchemaVersion", EXPORT_SCHEMA_VERSION)   // rc.8 #253, additive; when it changes: see the constant

        // nrcSuppression
        val nrcMap = log.nrcSuppressed
        if (nrcMap.isNotEmpty()) {
            root.put("nrcSuppression", JSONArray().apply {
                nrcMap.entries.sortedBy { it.value.firstSeenMs }.forEach { (did, rec) ->
                    put(JSONObject().apply {
                        put("did", "0x%04X".format(did))
                        put("nrcCode", rec.nrcCode)
                        put("nrcName", when (rec.nrcCode) {
                            NrcTracker.NRC_REQUEST_OUT_OF_RANGE -> "requestOutOfRange"
                            NrcTracker.NRC_CONDITIONS_NOT_CORRECT -> "conditionsNotCorrect"
                            NrcTracker.NRC_SECURITY_ACCESS_DENIED -> "securityAccessDenied"
                            else -> "unknown"
                        })
                        put("firstSeenMs", rec.firstSeenMs)
                        put("suppressedAfterCount", rec.suppressedAfterCount)
                    })
                }
            })
        }

        // probeResults
        root.put("probeResults", JSONArray().apply {
            log.probeSessions.forEach { ps ->
                put(JSONObject().apply {
                    put("relMs", ps.relMs)
                    put("module", ps.module)
                    put("requestId", "0x%03X".format(ps.requestId))
                    put("responseId", "0x%03X".format(ps.responseId))
                    put("totalProbed", ps.results.size)
                    put("outcome", ps.outcome)
                    put("planned", ps.planned)
                    put("found", ps.results.count { it.status == "FOUND" })
                    put("results", JSONArray().apply {
                        ps.results.forEach { r ->
                            put(JSONObject().apply {
                                put("did", "0x%04X".format(r.did))
                                put("status", r.status)
                                put("response", r.responseHex)
                            })
                        }
                    })
                })
            }
        })

        return root.toString(2)
    }
}

/** Serialize VehicleState to a JSONObject with full-precision numeric values. */
/**
 * Contract version of an export ZIP (rc.8 #253), at the root of `manifest.json` and of the diagnostic detail JSON. Absent =
 * exported before this field existed (rc.7 and earlier). It is not `manifest.json`'s own `version`, which has always been 1
 * and so can't tell those exports apart.
 *
 * Bump it only when a reader of the current number could misread an export: a field or file removed or renamed, or a value
 * whose type, unit, scale or meaning changes. Additive changes keep the number (a new section such as `linkIntegrity`, a new
 * key, a new file): readers ignore what they don't know and detect an added section by its presence. Sapphire imports every
 * number and says so when one is higher than it knows (`web/src/lib/exportSchema.ts`).
 */
internal const val EXPORT_SCHEMA_VERSION = 1

/** LINK INTEGRITY summary lines (rc.8 #253) — counts only, never a raw line. */
internal fun linkIntegrityText(li: DiagnosticLogger.LinkIntegrity, nrcUnattributed: Int): List<String> = listOf(
    "─── LINK INTEGRITY ────────────────────────────────────────",
    "  Writes   : %d sent · %d possibly delivered (GATT 13) · %d not sent · %d delivery unknown · %d other failures".format(
        li.writes[DiagnosticLogger.WRITE_SENT], li.writes[DiagnosticLogger.WRITE_POSSIBLY_DELIVERED],
        li.writes[DiagnosticLogger.WRITE_NOT_SENT], li.writes[DiagnosticLogger.WRITE_UNCERTAIN],
        li.writes[DiagnosticLogger.WRITE_OTHER_FAILURE]) + "  (Mode 22 polls + DID probes)",
    "  Framing  : ${when (li.framingTimestamped) { null -> "not decided"; true -> "timestamped (Z1 set on the adapter)"; false -> "plain" }}" +
        " · ${li.refusedFrameLines} frame line(s) refused (${li.refusedOtherShape} of the other shape)",
    "  Replies  : ${li.refusedReplies} Mode 22 reply(ies) refused for their length / PCI",
    "  NRC      : ${li.nrcAttributed} attributed · $nrcUnattributed unattributed (PCM)",
    "  Buffers  : ${li.overlongLines} overlong line(s) refused · ${li.linesDropped} line(s) dropped (BLE queue full) · " +
        "${li.commandRepliesDropped} command reply(ies) dropped · ${li.dtcFramesDropped} DTC frame(s) dropped",
    "  ISO-TP   : ${li.isoTpRefused} frame(s) refused or reply(ies) abandoned (truncated, too short, sequence break, superseded by a new reply, open at a DTC / freeze-frame deadline)",
)

/** A module's name for its Mode 22 response ID (the ECU table), else the hex ID. */
internal fun moduleName(responseId: Int): String = when (responseId) {
    0x7E8 -> "PCM"; 0x72E -> "BCM"; 0x70B -> "AWD"; 0x768 -> "ABS"; 0x738 -> "PSCM"; 0x72F -> "FENG"; 0x739 -> "RSProt"
    0x7DA -> "GFM"; 0x728 -> "IPC"; 0x73B -> "HVAC"
    else -> "0x%03X".format(responseId)
}

/**
 * DID INVENTORY summary lines (rc.8 #253): per module and DID, what this session's pollers asked and what came back.
 * "sent" counts writes not certainly lost; "answered" positive single / first frames (DID-prober answers excluded);
 * "rejected" NRCs charged to that DID (unattributed ones are in LINK INTEGRITY). Times are relative to the session start.
 */
internal fun didInventoryText(inv: DiagnosticLogger.DidInventory): List<String> = buildList {
    add("─── DID INVENTORY (Mode 22, this session) ─────────────────")
    if (inv.rows.isEmpty()) add("  none")
    for (r in inv.rows.sortedWith(compareBy({ it.responseId }, { it.did }))) {
        val silent = r.sent > 0 && r.positive == 0 && r.nrcAttributed == 0
        add("  %-6s 0x%04X : %d sent · %d answered · %d rejected%s".format(moduleName(r.responseId), r.did, r.sent, r.positive,
            r.nrcAttributed, when {
                r.lastPositiveRelMs >= 0 -> " · last answer at +%.1f s".format(r.lastPositiveRelMs / 1000.0)
                silent -> " · never answered"
                else -> ""
            }))
    }
    if (inv.overflow > 0) add("  ${inv.overflow} further count(s) of DIDs past the ${DiagnosticLogger.MAX_INVENTORY_DIDS}-row limit: not listed")
}

internal fun didInventoryJson(inv: DiagnosticLogger.DidInventory): JSONObject = JSONObject().apply {
    put("rows", org.json.JSONArray().apply {
        inv.rows.sortedWith(compareBy({ it.responseId }, { it.did })).forEach { r ->
            put(JSONObject().apply {
                put("module", moduleName(r.responseId)); put("responseId", "%03X".format(r.responseId)); put("did", "%04X".format(r.did))
                put("sent", r.sent); put("positive", r.positive); put("nrcAttributed", r.nrcAttributed)
                if (r.lastPositiveRelMs >= 0) put("lastPositiveRelMs", r.lastPositiveRelMs)
            })
        }
    })
    put("overflow", inv.overflow)
}

/** Demo knock events are simulated: like the session-knock CSV, they never reach an export. */
private fun knockSessionDropped() =
    if (com.openrs.dash.demo.DemoMode.isActive) 0 else com.openrs.dash.data.KnockSessionLog.droppedEvents

/** The adapter's device ID never leaves the app in an export (#263); GARAGE still shows it. */
private const val DEVICE_ID_MASKED = "<masked>"

/** LOG LIMITS summary lines (#253): what this session's rolling / capped logs no longer hold. */
internal fun logLimitsText(l: DiagnosticLogger.LogLimits, knockDropped: Int, li: DiagnosticLogger.LinkIntegrity): List<String> = buildList {
    add("─── LOG LIMITS ────────────────────────────────────────────")
    if (l.decodeTraceDropped > 0) add("  ${l.decodeTraceDropped} older decode trace entries rolled off (per-ID totals stay exact)")
    if (l.eventsDropped > 0) add("  ${l.eventsDropped} older session event(s) rolled off: SESSION EVENTS below is not the whole session")
    if (l.fpsPointsDropped > 0) add("  ${l.fpsPointsDropped} older FPS sample(s) rolled off")
    if (l.probeSessionsDropped > 0) add("  ${l.probeSessionsDropped} older DID probe session(s) rolled off")
    if (l.inventoryFramesNotListed > 0) add("  ${l.inventoryFramesNotListed} frame(s) of IDs past the inventory limit: FRAME INVENTORY is not every ID")
    if (l.slcanCapReached) add("  SLCAN log cap reached: later frames are not in the raw log")
    if (l.slcanWriteFailed) add("  SLCAN log write failed: frames may be missing from the raw log")
    if (knockDropped > 0) add("  $knockDropped older session knock event(s) rolled off: drive_knock_session holds the newest only")
    if (size == 1) {
        val buffersHit = li.overlongLines + li.linesDropped + li.commandRepliesDropped + li.dtcFramesDropped > 0
        add(if (buffersHit) "  no log limit reached; lines were refused or dropped, see Buffers above" else "  no counted limit reached")
    }
    if (l.vinFramesMaskedPage > 0) add("  ${l.vinFramesMaskedPage} VIN-bearing frame(s) masked: data bytes read 00 in the raw log, ** in the inventory and trace")
    if (l.vinFramesMaskedContent > 0) add("  ${l.vinFramesMaskedContent} further frame(s) masked for holding ${DiagnosticLogger.VIN_RUN_MIN} or more consecutive VIN bytes: can be an unrelated frame")
}

internal fun logLimitsJson(l: DiagnosticLogger.LogLimits, knockDropped: Int): JSONObject = JSONObject().apply {
    put("decodeTraceDropped", l.decodeTraceDropped)
    put("eventsDropped", l.eventsDropped)
    put("fpsPointsDropped", l.fpsPointsDropped)
    put("probeSessionsDropped", l.probeSessionsDropped)
    put("inventoryFramesNotListed", l.inventoryFramesNotListed)
    put("slcanCapReached", l.slcanCapReached)
    put("slcanWriteFailed", l.slcanWriteFailed)
    put("vinFramesMaskedPage", l.vinFramesMaskedPage)
    put("vinFramesMaskedContent", l.vinFramesMaskedContent)
    put("knockSessionDropped", knockDropped)
}

internal fun linkIntegrityJson(li: DiagnosticLogger.LinkIntegrity, nrcUnattributed: Int): JSONObject = JSONObject().apply {
    put("writes", JSONObject().apply { li.writes.forEach { (k, v) -> put(k, v) } })
    put("framing", when (li.framingTimestamped) { null -> "undecided"; true -> "timestamped"; false -> "plain" })
    put("refusedFrameLines", li.refusedFrameLines)
    put("refusedOtherShape", li.refusedOtherShape)
    put("refusedReplies", li.refusedReplies)
    put("nrcAttributed", li.nrcAttributed)
    put("nrcUnattributed", nrcUnattributed)
    put("overlongLines", li.overlongLines)
    put("linesDropped", li.linesDropped)
    put("commandRepliesDropped", li.commandRepliesDropped)
    put("dtcFramesDropped", li.dtcFramesDropped)
    put("isoTpRefused", li.isoTpRefused)
}

/** " (0x1B0 Ns ago, its 0x420 detail Ms ago)" — what a Sport / Track reading rests on (#252); "" before any report. */
internal fun modeProvenance(vs: VehicleState, nowMs: Long): String {
    val resolved = vs.fieldLastUpdateMs["driveMode"] ?: return ""
    val detail = vs.fieldLastUpdateMs[com.openrs.dash.can.CanDecoder.MODE_RESOLVED_DETAIL_TAG]
    return "  (0x1B0 ${(nowMs - resolved) / 1000} s ago" + (detail?.let { ", its 0x420 detail ${(nowMs - it) / 1000} s ago" } ?: "") + ")"
}

/** Tags whose age the detail JSON reports (rc.8 #253): the polled voltage and the drive-mode provenance (#223, #252). */
private val AGE_TAGS = listOf("batteryVoltage", "driveMode", com.openrs.dash.can.CanDecoder.MODE_DETAIL_TAG,
    com.openrs.dash.can.CanDecoder.MODE_RESOLVED_DETAIL_TAG, com.openrs.dash.can.CanDecoder.MODE_RUN_PREV_TAG,
    com.openrs.dash.can.CanDecoder.MODE_RESOLVED_PAIR_TAG, com.openrs.dash.can.CanDecoder.ESC_FRAME_TAG, com.openrs.dash.can.CanDecoder.ESC_FRAME_PREV_TAG)

internal fun VehicleState.toJsonObject(nowMs: Long = System.currentTimeMillis()): JSONObject = JSONObject().apply {
    // Additive (rc.8 #253): ages at export time, never absolute times
    put("fieldAgesMs", JSONObject().apply {
        AGE_TAGS.forEach { t -> fieldLastUpdateMs[t]?.let { put(t, nowMs - it) } }
        // The current run's tag (#252): with the run's previous report and the resolved pair, what a confirmation rested on
        fieldLastUpdateMs.forEach { (t, at) -> if (t.startsWith(com.openrs.dash.can.CanDecoder.MODE_RUN_PREFIX)) put(t, nowMs - at) }
    })
    put("rpm", rpm)
    put("speedKph", speedKph)
    put("speedMph", speedKph * 0.621371)
    put("boostKpa", boostKpa)   // CAN 0x0F8 byte 5 + 101.325 (gauge, std-atm form) — a reading only if stamped "boostKpa"
    // Only once 0x0F8 was received: the 101.325 default would read as a measured 0
    if (fieldLastUpdateMs[com.openrs.dash.data.BoostRule.CAN_TAG] != null) put("boostCanGaugeKpa", boostKpa - 101.325)
    put("coolantTempC", coolantTempC)
    put("oilTempC", oilTempC)
    put("oilPtuMapping", com.openrs.dash.data.OilPtuMapping.CURRENT)   // this build's 0x0F8 decoder (#229)
    put("intakeTempC", intakeTempC)
    put("ambientTempC", ambientTempC)
    put("barometricPressure", barometricPressure)
    put("throttlePct", throttlePct)
    put("accelPedalPct", accelPedalPct)
    put("torqueAtTrans", torqueAtTrans)
    put("lateralG", lateralG)
    put("longitudinalG", longitudinalG)
    put("steeringAngle", steeringAngle)
    put("yawRate", yawRate)
    put("brakePressure", brakePressure)
    put("wheelSpeedFL", wheelSpeedFL)
    put("wheelSpeedFR", wheelSpeedFR)
    put("wheelSpeedRL", wheelSpeedRL)
    put("wheelSpeedRR", wheelSpeedRR)
    put("driveMode", driveMode.label)
    put("escStatus", escStatus.label)
    put("escStatusDetail", escStatus.diagnostic)          // Ford name + raw value, or "not received" / "unrecognized (raw 3)"
    put("gear", gear)
    put("gearDisplay", gearDisplay)
    put("batteryVoltage", batteryVoltage)
    put("fuelLevelPct", fuelLevelPct)
    put("tirePressLF", tirePressLF)
    put("tirePressRF", tirePressRF)
    put("tirePressLR", tirePressLR)
    put("tirePressRR", tirePressRR)
    put("awdLeftTorque", awdLeftTorque)
    put("awdRightTorque", awdRightTorque)
    // awdMaxTorque: retired (no DBC signal in 0x2C0, #226) — omitted, never exported as a measured 0
    put("rduTempC", rduTempC)
    put("ptuTempC", ptuTempC)
    put("calcLoad", calcLoad)
    put("timingAdvance", timingAdvance)
    put("shortFuelTrim", shortFuelTrim)
    put("longFuelTrim", longFuelTrim)
    put("afrActual", afrActual)
    put("afrDesired", afrDesired)
    put("lambdaActual", lambdaActual)
    put("etcAngleActual", etcAngleActual)
    put("etcAngleDesired", etcAngleDesired)
    put("wgdcDesired", wgdcDesired)
    put("ignCorrCyl1", ignCorrCyl1)
    put("ignCorrCyl2", ignCorrCyl2)
    put("ignCorrCyl3", ignCorrCyl3)
    put("ignCorrCyl4", ignCorrCyl4)
    put("sparkAdvance", sparkAdvance)
    put("commandedAfr", commandedAfr)
    put("o2Voltage", o2Voltage)
    put("afrSensor1", afrSensor1)
    put("fuelRailPressureKpa", fuelRailPressure)
    put("octaneAdjustRatio", octaneAdjustRatio)
    put("chargeAirTempC", chargeAirTempC)
    put("catalyticTempC", catalyticTempC)
    put("tipActualKpa", tipActualKpa)
    put("tipDesiredKpa", tipDesiredKpa)
    put("vctIntakeAngle", vctIntakeAngle)
    put("vctExhaustAngle", vctExhaustAngle)
    put("oilLifePct", oilLifePct)
    put("hpFuelRailPsi", hpFuelRailPsi)
    put("odometerKm", odometerKm)
    put("batterySoc", batterySoc)
    put("batteryTempC", batteryTempC)
    put("cabinTempC", cabinTempC)
    put("rduEnabled", rduEnabled ?: JSONObject.NULL)
    put("pdcEnabled", pdcEnabled ?: JSONObject.NULL)
    put("fengEnabled", fengEnabled ?: JSONObject.NULL)
    put("lcArmed", lcArmed ?: JSONObject.NULL)
    put("lcRpmTarget", lcRpmTarget)
    put("assEnabled", assEnabled ?: JSONObject.NULL)
    // #207: session boost peaks per measurement (gauge kPa) — VehicleState.peakBoostPsi is no longer updated
    com.openrs.dash.data.BoostSession.peakKpa(com.openrs.dash.data.BoostSource.CAN_0F8)?.let { put("boostPeakCanGaugeKpa", it) }
    com.openrs.dash.data.BoostSession.peakKpa(com.openrs.dash.data.BoostSource.TIP_033E)?.let { put("boostPeakTipGaugeKpa", it) }
    put("peakRpm", peakRpm)
    put("peakLateralG", peakLateralG)
    put("peakLongitudinalG", peakLongitudinalG)
    put("eBrake", eBrake)
    put("reverseStatus", reverseStatus)
    put("framesPerSecond", framesPerSecond)
    put("isConnected", isConnected)
    put("dataMode", "CAN")
}
