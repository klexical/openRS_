package com.openrs.dash.diagnostics

import com.openrs.dash.BuildConfig
import com.openrs.dash.can.CanDecoder
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

    fun buildSummaryText(ts: String): String = buildString {
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
            appendLine("  Intake     : ${"%.0f".format(vs.intakeTempC)} °C")
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
            appendLine("  ETC Act/Des: ${"%.1f".format(vs.etcAngleActual)}° / ${"%.1f".format(vs.etcAngleDesired)}°")
            appendLine("  WGDC       : ${"%.1f".format(vs.wgdcDesired)} %")
            appendLine("  KR Cyl1    : ${"%.2f".format(vs.ignCorrCyl1)}°")
            appendLine("  OAR        : ${"%.4f".format(vs.octaneAdjustRatio)}")
            appendLine("  TIP Act/Des: ${"%.1f".format(vs.tipActualKpa)} / ${"%.1f".format(vs.tipDesiredKpa)} kPa")
            appendLine("  VCT I/E    : ${"%.1f".format(vs.vctIntakeAngle)}° / ${"%.1f".format(vs.vctExhaustAngle)}°")
            appendLine("  Oil Life   : ${"%.0f".format(vs.oilLifePct)} %")
            appendLine("  HP Fuel Rl : ${"%.0f".format(vs.hpFuelRailPsi)} PSI")
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
            appendLine("  Data Mode  : ${vs.dataMode}")
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

        val knownIds = setOf(
            CanDecoder.ID_TORQUE, CanDecoder.ID_THROTTLE, CanDecoder.ID_PEDALS,
            CanDecoder.ID_ENGINE_RPM, CanDecoder.ID_GAUGE_ILLUM, CanDecoder.ID_ENGINE_TEMPS,
            CanDecoder.ID_SPEED, CanDecoder.ID_LONG_ACCEL, CanDecoder.ID_LAT_ACCEL,
            CanDecoder.ID_DRIVE_MODE, CanDecoder.ID_DRIVE_MODE_EXT, CanDecoder.ID_ESC_ABS,
            CanDecoder.ID_WHEEL_SPEEDS, CanDecoder.ID_GEAR, CanDecoder.ID_AWD_TORQUE,
            CanDecoder.ID_COOLANT, CanDecoder.ID_PCM_AMBIENT, CanDecoder.ID_AMBIENT_TEMP,
            CanDecoder.ID_FUEL_LEVEL, CanDecoder.ID_ODOMETER, CanDecoder.ID_STEERING, CanDecoder.ID_BRAKE_PRESS
        )

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
                appendLine("  ${ps.module} (0x${"%03X".format(ps.requestId)}→0x${"%03X".format(ps.responseId)}) @ +${log.formatDuration(ps.relMs)}")
                appendLine("    ${ps.results.size} probed — $found found, $nrc rejected, $timeout timeout")
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

    fun buildDetailJson(ts: String): String {
        val log = DiagnosticLogger
        val vs  = log.lastVehicleState
        val p   = log.sessionPrefs

        val root = JSONObject()

        // meta
        root.put("meta", JSONObject().apply {
            put("app", "openRS_")
            put("appVersion", BuildConfig.VERSION_NAME)
            put("appBuild", BuildConfig.VERSION_CODE)
            put("generatedAt", ts)
            put("sessionDurationMs", log.sessionDurationMs)
            put("sessionDurationHuman", log.formatDuration(log.sessionDurationMs))
            put("firmware", log.firmwareVersion)
            put("isOpenRsFirmware", log.isOpenRsFirmware)
            put("host", log.sessionHost)
            put("port", log.sessionPort)
            put("slcanLinesLogged", log.slcanLineCount)
            put("slcanFileIncluded", log.slcanLogFile != null && log.slcanLineCount > 0)
        })

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

        // probeResults
        root.put("probeResults", JSONArray().apply {
            log.probeSessions.forEach { ps ->
                put(JSONObject().apply {
                    put("relMs", ps.relMs)
                    put("module", ps.module)
                    put("requestId", "0x%03X".format(ps.requestId))
                    put("responseId", "0x%03X".format(ps.responseId))
                    put("totalProbed", ps.results.size)
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
private fun VehicleState.toJsonObject(): JSONObject = JSONObject().apply {
    put("rpm", rpm)
    put("speedKph", speedKph)
    put("speedMph", speedKph * 0.621371)
    put("boostKpa", boostKpa)
    put("boostPsi", (boostKpa - 101.325) * 0.14503773)
    put("coolantTempC", coolantTempC)
    put("oilTempC", oilTempC)
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
    put("awdMaxTorque", awdMaxTorque)
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
    put("peakBoostPsi", peakBoostPsi)
    put("peakRpm", peakRpm)
    put("peakLateralG", peakLateralG)
    put("peakLongitudinalG", peakLongitudinalG)
    put("eBrake", eBrake)
    put("reverseStatus", reverseStatus)
    put("framesPerSecond", framesPerSecond)
    put("isConnected", isConnected)
    put("dataMode", dataMode)
}
