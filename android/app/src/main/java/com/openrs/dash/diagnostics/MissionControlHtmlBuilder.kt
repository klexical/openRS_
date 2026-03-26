package com.openrs.dash.diagnostics

import android.content.Context
import com.openrs.dash.BuildConfig
import com.openrs.dash.R
import com.openrs.dash.data.TripState
import java.io.File

/**
 * Assembles a self-contained Mission Control HTML dashboard by reading the
 * template from [R.raw.mission_control], inlining uPlot CSS/JS from assets,
 * and injecting serialised trip + diagnostic JSON.
 *
 * The resulting HTML opens in any browser with zero external dependencies.
 *
 * Template tokens replaced:
 *  - `{{UPLOT_CSS}}`  → assets/css/uplot.min.css
 *  - `{{UPLOT_JS}}`   → assets/js/uplot.min.js
 *  - `{{TRIP_JSON}}`  → trip telemetry or literal `null`
 *  - `{{DIAG_JSON}}`  → diagnostic data or literal `null`
 *  - `{{META_JSON}}`  → version / session metadata
 */
object MissionControlHtmlBuilder {

    /**
     * Build a self-contained HTML file and write it to the app cache directory.
     *
     * @param ctx       Android context for reading resources and assets.
     * @param tripState Trip data to embed, or null for diagnostic-only exports.
     * @param ts        Timestamp string (`yyyyMMdd_HHmmss`) for the output filename.
     * @return The generated HTML [File], or null on error.
     */
    fun build(ctx: Context, tripState: TripState?, ts: String): File? {
        return try {
            val template = ctx.resources.openRawResource(R.raw.mission_control)
                .bufferedReader(Charsets.UTF_8).use { it.readText() }

            val uplotCss = ctx.assets.open("css/uplot.min.css")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }

            val uplotJs = ctx.assets.open("js/uplot.min.js")
                .bufferedReader(Charsets.UTF_8).use { it.readText() }

            val tripJson = if (tripState != null && tripState.points.isNotEmpty())
                buildTripJson(tripState) else "null"

            val diagJson = buildDiagJson()

            val metaJson = buildMetaJson(ts)

            val html = template
                .replace("{{UPLOT_CSS}}", uplotCss)
                .replace("{{UPLOT_JS}}", uplotJs)
                .replace("{{TRIP_JSON}}", tripJson)
                .replace("{{DIAG_JSON}}", diagJson)
                .replace("{{META_JSON}}", metaJson)

            val outDir = File(ctx.cacheDir, "mission_control").also { it.mkdirs() }
            val outFile = File(outDir, "mission_control_$ts.html")
            outFile.writeText(html, Charsets.UTF_8)

            // Prune old cached HTML files (keep at most 3)
            outDir.listFiles { f -> f.name.endsWith(".html") }
                ?.sortedBy { it.lastModified() }
                ?.dropLast(3)
                ?.forEach { it.delete() }

            outFile
        } catch (e: Exception) {
            DiagnosticLogger.event("MC_BUILD_ERROR", e.message ?: "unknown")
            null
        }
    }

    // ── Trip JSON ─────────────────────────────────────────────────────────────

    private fun buildTripJson(tripState: TripState): String = buildString {
        append("{\"points\":[")
        tripState.points.forEachIndexed { i, pt ->
            if (i > 0) append(',')
            append('{')
            append("\"t\":${pt.timestamp},")
            append("\"lat\":${"%.6f".format(pt.lat)},")
            append("\"lng\":${"%.6f".format(pt.lng)},")
            append("\"spd\":${"%.1f".format(pt.speedKph)},")
            append("\"rpm\":${pt.rpm.toInt()},")
            append("\"gear\":\"${pt.gear}\",")
            append("\"boost\":${"%.2f".format(pt.boostPsi)},")
            append("\"cool\":${nullableTemp(pt.coolantTempC)},")
            append("\"oil\":${nullableTemp(pt.oilTempC)},")
            append("\"amb\":${"%.1f".format(pt.ambientTempC)},")
            append("\"rdu\":${nullableTemp(pt.rduTempC)},")
            append("\"ptu\":${nullableTemp(pt.ptuTempC)},")
            append("\"fuel\":${"%.1f".format(pt.fuelLevelPct)},")
            append("\"wfl\":${"%.1f".format(pt.wheelSpeedFL)},")
            append("\"wfr\":${"%.1f".format(pt.wheelSpeedFR)},")
            append("\"wrl\":${"%.1f".format(pt.wheelSpeedRL)},")
            append("\"wrr\":${"%.1f".format(pt.wheelSpeedRR)},")
            append("\"latg\":${"%.3f".format(pt.lateralG)},")
            append("\"mode\":\"${pt.driveMode.label}\",")
            append("\"rtr\":${pt.isRaceReady}")
            append('}')
        }
        append("],\"summary\":")
        appendTripSummary(this, tripState)
        append('}')
    }

    private fun appendTripSummary(sb: StringBuilder, ts: TripState) = with(sb) {
        append('{')
        append("\"distanceKm\":${"%.3f".format(ts.cumulativeDistanceKm)},")
        append("\"durationMs\":${ts.elapsedMs},")
        append("\"startFuelPct\":${"%.1f".format(ts.startFuelPct)},")
        append("\"endFuelPct\":${"%.1f".format(ts.latestFuelPct)},")
        append("\"fuelUsedL\":${"%.2f".format(ts.fuelUsedL)},")
        append("\"avgFuelL100km\":${"%.1f".format(ts.avgFuelL100km)},")
        append("\"avgFuelMpg\":${"%.1f".format(ts.avgFuelMpg)},")
        append("\"avgSpeedKph\":${"%.1f".format(ts.avgSpeedKph)},")
        append("\"maxSpeedKph\":${"%.1f".format(ts.maxSpeedKph)},")
        append("\"peakRpm\":${"%.0f".format(ts.peakRpm)},")
        append("\"peakBoostPsi\":${"%.1f".format(ts.peakBoostPsi)},")
        append("\"peakLateralG\":${"%.2f".format(ts.peakLateralG)},")
        append("\"avgRpm\":${"%.0f".format(ts.avgRpm)},")

        // Mode breakdown
        append("\"modeBreakdown\":{")
        val breakdown = ts.driveModeBreakdown.entries.toList()
        breakdown.forEachIndexed { i, (mode, frac) ->
            if (i > 0) append(',')
            append("\"${mode.label}\":${"%.3f".format(frac)}")
        }
        append("},")

        // Peak events
        append("\"peakEvents\":[")
        ts.peakEvents.forEachIndexed { i, pe ->
            if (i > 0) append(',')
            append("{\"type\":\"${pe.type.label}\",")
            append("\"lat\":${"%.6f".format(pe.lat)},")
            append("\"lng\":${"%.6f".format(pe.lng)},")
            append("\"value\":${"%.2f".format(pe.value)}}")
        }
        append("]")

        append('}')
    }

    /** Emit `null` for temperature sentinels so uPlot shows gaps instead of spikes. */
    private fun nullableTemp(v: Double): String =
        if (v <= -90.0) "null" else "%.1f".format(v)

    // ── Diagnostic JSON ───────────────────────────────────────────────────────

    private fun buildDiagJson(): String {
        val log = DiagnosticLogger
        val inventory = log.frameInventorySnapshot
        val fps = log.fpsTimeline
        val events = log.sessionEvents
        val trace = log.decodeTrace
        val probes = log.probeSessions

        // If there's no diagnostic data at all, return null literal
        if (inventory.isEmpty() && fps.isEmpty() && events.isEmpty() && trace.isEmpty()) {
            return "null"
        }

        return buildString {
            append("{\"canFrameInventory\":{")
            val invEntries = inventory.entries.sortedBy { it.key }
            invEntries.forEachIndexed { idx, (id, info) ->
                if (idx > 0) append(',')
                append("\"0x%03X\":{".format(id))
                append("\"totalReceived\":${info.totalReceived},")
                append("\"firstRawHex\":\"${info.firstRawHex.jsonEscape()}\",")
                append("\"lastRawHex\":\"${info.lastRawHex.jsonEscape()}\",")
                append("\"hasChanged\":${info.hasChanged},")
                append("\"lastDecoded\":\"${info.lastDecoded.jsonEscape()}\",")

                // Validation issues
                append("\"validationIssues\":[")
                info.validationIssues.forEachIndexed { vi, issue ->
                    if (vi > 0) append(',')
                    append("\"${issue.jsonEscape()}\"")
                }
                append("],")

                // Periodic samples
                append("\"periodicSamples\":[")
                info.periodicSamples.forEachIndexed { si, s ->
                    if (si > 0) append(',')
                    append("{\"relMs\":${s.relMs},\"rawHex\":\"${s.rawHex}\"}")
                }
                append("]}")
            }
            append("},")

            // FPS timeline
            append("\"fpsTimeline\":[")
            fps.forEachIndexed { i, pt ->
                if (i > 0) append(',')
                append("{\"relMs\":${pt.relMs},\"fps\":${"%.1f".format(pt.fps)}}")
            }
            append("],")

            // Session events
            append("\"sessionEvents\":[")
            events.forEachIndexed { i, ev ->
                if (i > 0) append(',')
                append("{\"relMs\":${ev.relMs},")
                append("\"type\":\"${ev.type.jsonEscape()}\",")
                append("\"message\":\"${ev.message.jsonEscape()}\"}")
            }
            append("],")

            // Probe results
            append("\"probeResults\":[")
            probes.forEachIndexed { i, ps ->
                if (i > 0) append(',')
                append("{\"relMs\":${ps.relMs},")
                append("\"module\":\"${ps.module.jsonEscape()}\",")
                append("\"requestId\":\"0x%03X\",".format(ps.requestId))
                append("\"responseId\":\"0x%03X\",".format(ps.responseId))
                val found = ps.results.count { it.status == "FOUND" }
                append("\"totalProbed\":${ps.results.size},")
                append("\"found\":$found,")
                append("\"results\":[")
                ps.results.forEachIndexed { ri, r ->
                    if (ri > 0) append(',')
                    append("{\"did\":\"0x%04X\",".format(r.did))
                    append("\"status\":\"${r.status.jsonEscape()}\",")
                    append("\"response\":\"${r.responseHex.jsonEscape()}\"}")
                }
                append("]}")
            }
            append("],")

            // Decode trace
            append("\"decodeTrace\":[")
            trace.forEachIndexed { i, t ->
                if (i > 0) append(',')
                append("{\"relMs\":${t.relMs},")
                append("\"id\":\"${t.idHex}\",")
                append("\"raw\":\"${t.rawHex}\",")
                append("\"decoded\":\"${t.decoded.jsonEscape()}\",")
                val issueJson = if (t.issue != null) "\"${t.issue.jsonEscape()}\"" else "null"
                append("\"issue\":$issueJson}")
            }
            append("]}")
        }
    }

    // ── Meta JSON ─────────────────────────────────────────────────────────────

    private fun buildMetaJson(ts: String): String {
        val log = DiagnosticLogger
        return buildString {
            append('{')
            append("\"version\":\"${BuildConfig.VERSION_NAME}\",")
            append("\"generatedAt\":\"$ts\",")
            append("\"firmware\":\"${log.firmwareVersion.jsonEscape()}\",")
            append("\"sessionStartMs\":${log.sessionStartMs}")
            append('}')
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Minimal JSON string escaping: backslash, double-quote, newlines, tabs, script breakout. */
    private fun String.jsonEscape(): String = this
        .replace("\\", "\\\\")
        .replace("\"", "\\\"")
        .replace("\n", "\\n")
        .replace("\r", "\\r")
        .replace("\t", "\\t")
        .replace("</", "<\\/")
}
