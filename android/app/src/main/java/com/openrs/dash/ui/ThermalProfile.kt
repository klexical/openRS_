package com.openrs.dash.ui

import com.openrs.dash.data.DriveThermalSensor
import com.openrs.dash.data.DriveThermalTracker
import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.TempLimits
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.roundToLong

/**
 * One thermal profile: the warm-up minimums and warning / critical limits THERMAL, DRIVE, readiness
 * and the recorder's readiness marker use (canonical °C). STREET / TRACK / RACE are the built-in
 * openRS_ defaults — unchanged values, not manufacturer limits (see the rc.6 threshold inventory).
 * CUSTOM is a user copy of one of them, edited in Settings → THERMAL.
 *
 * Intake has limits only (no warm-up); the AWD clutch limits are the same in every built-in.
 */
data class ThermalProfile(
    val oilMinC: Double, val oilWarnC: Double, val oilCritC: Double,
    val coolMinC: Double, val coolWarnC: Double, val coolCritC: Double,
    val ptuMinC: Double, val ptuWarnC: Double, val ptuCritC: Double,
    val rduMinC: Double, val rduWarnC: Double, val rduCritC: Double,
    val clutchMinC: Double, val clutchWarnC: Double, val clutchCritC: Double,
    val intakeWarnC: Double, val intakeCritC: Double,
) {
    fun toJson(base: String): String = JSONObject().apply {
        put("base", base)
        FIELDS.forEach { f -> put(f.key, f.get(this@ThermalProfile)) }
    }.toString()

    companion object {
        // Warm-up minimums are the same in every built-in (VehicleState.rtrColdItems)
        private const val OIL_MIN = 80.0; private const val COOL_MIN = 85.0; private const val PTU_MIN = 40.0
        private const val RDU_MIN = 30.0; private const val CLUTCH_MIN = 25.0

        val STREET = ThermalProfile(
            OIL_MIN, 120.0, 130.0, COOL_MIN, 105.0, 115.0, PTU_MIN, 95.0, 110.0, RDU_MIN, 90.0, 100.0,
            CLUTCH_MIN, CLUTCH_WARN_C, CLUTCH_CRIT_C, 65.0, 80.0)
        val TRACK = ThermalProfile(
            OIL_MIN, 110.0, 120.0, COOL_MIN, 100.0, 110.0, PTU_MIN, 85.0, 100.0, RDU_MIN, 80.0, 95.0,
            CLUTCH_MIN, CLUTCH_WARN_C, CLUTCH_CRIT_C, 55.0, 65.0)
        val RACE = ThermalProfile(
            OIL_MIN, 100.0, 110.0, COOL_MIN, 95.0, 105.0, PTU_MIN, 75.0, 90.0, RDU_MIN, 70.0, 85.0,
            CLUTCH_MIN, CLUTCH_WARN_C, CLUTCH_CRIT_C, 45.0, 55.0)

        fun builtIn(id: String): ThermalProfile = when (id) { "race" -> RACE; "track" -> TRACK; else -> STREET }

        /** Saved custom profile + the built-in it was copied from; null when absent or unreadable. */
        fun fromJson(json: String?): Pair<ThermalProfile, String>? {
            if (json.isNullOrBlank()) return null
            return try {
                val o = JSONObject(json)
                val base = o.optString("base", "street")
                var p = builtIn(base)
                for (f in FIELDS) {
                    if (!o.has(f.key)) return null
                    p = f.set(p, o.getDouble(f.key))
                }
                p to base
            } catch (_: Exception) { null }
        }

        /** Every editable value, in editor order. */
        val FIELDS: List<ThermalField> = listOf(
            ThermalField("oilMinC", ThermalRow.OIL, ThermalKind.MIN, { it.oilMinC }) { p, v -> p.copy(oilMinC = v) },
            ThermalField("oilWarnC", ThermalRow.OIL, ThermalKind.WARN, { it.oilWarnC }) { p, v -> p.copy(oilWarnC = v) },
            ThermalField("oilCritC", ThermalRow.OIL, ThermalKind.CRIT, { it.oilCritC }) { p, v -> p.copy(oilCritC = v) },
            ThermalField("coolMinC", ThermalRow.COOLANT, ThermalKind.MIN, { it.coolMinC }) { p, v -> p.copy(coolMinC = v) },
            ThermalField("coolWarnC", ThermalRow.COOLANT, ThermalKind.WARN, { it.coolWarnC }) { p, v -> p.copy(coolWarnC = v) },
            ThermalField("coolCritC", ThermalRow.COOLANT, ThermalKind.CRIT, { it.coolCritC }) { p, v -> p.copy(coolCritC = v) },
            ThermalField("ptuMinC", ThermalRow.PTU, ThermalKind.MIN, { it.ptuMinC }) { p, v -> p.copy(ptuMinC = v) },
            ThermalField("ptuWarnC", ThermalRow.PTU, ThermalKind.WARN, { it.ptuWarnC }) { p, v -> p.copy(ptuWarnC = v) },
            ThermalField("ptuCritC", ThermalRow.PTU, ThermalKind.CRIT, { it.ptuCritC }) { p, v -> p.copy(ptuCritC = v) },
            ThermalField("rduMinC", ThermalRow.RDU, ThermalKind.MIN, { it.rduMinC }) { p, v -> p.copy(rduMinC = v) },
            ThermalField("rduWarnC", ThermalRow.RDU, ThermalKind.WARN, { it.rduWarnC }) { p, v -> p.copy(rduWarnC = v) },
            ThermalField("rduCritC", ThermalRow.RDU, ThermalKind.CRIT, { it.rduCritC }) { p, v -> p.copy(rduCritC = v) },
            ThermalField("clutchMinC", ThermalRow.CLUTCH, ThermalKind.MIN, { it.clutchMinC }) { p, v -> p.copy(clutchMinC = v) },
            ThermalField("clutchWarnC", ThermalRow.CLUTCH, ThermalKind.WARN, { it.clutchWarnC }) { p, v -> p.copy(clutchWarnC = v) },
            ThermalField("clutchCritC", ThermalRow.CLUTCH, ThermalKind.CRIT, { it.clutchCritC }) { p, v -> p.copy(clutchCritC = v) },
            ThermalField("intakeWarnC", ThermalRow.INTAKE, ThermalKind.WARN, { it.intakeWarnC }) { p, v -> p.copy(intakeWarnC = v) },
            ThermalField("intakeCritC", ThermalRow.INTAKE, ThermalKind.CRIT, { it.intakeCritC }) { p, v -> p.copy(intakeCritC = v) },
        )
    }
}

/**
 * One recorder sample into the drive's thermal record: the effective profile (Custom when saved, as readiness uses it)
 * and only readings that are current under the shared freshness rule ([tempAvail]) — a stale value is not carried into
 * a new, stricter profile segment, while one still fresh counts at once (no extra poll needed).
 */
fun DriveThermalTracker.sample(vs: VehicleState, prefs: UserPrefs, now: Long) {
    val limits = ThermalLimits.from(prefs)
    fun fresh(v: Double, tag: String) = if (vs.tempAvail(v, tag, now) == TempAvail.AVAILABLE) v else -99.0
    sample(now, limits.preset, limits.profile.driveLimits(), mapOf(
        DriveThermalSensor.OIL to fresh(vs.oilTempC, "oilTempC"),
        DriveThermalSensor.COOLANT to fresh(vs.coolantTempC, "coolantTempC"),
        DriveThermalSensor.PTU to fresh(vs.ptuTempC, "ptuTempC"),
        DriveThermalSensor.RDU to fresh(vs.rduTempC, "rduTempC")))
}

/** The limits a recorded drive is judged against (saved with it — see DriveThermalRecord). */
fun ThermalProfile.driveLimits(): Map<DriveThermalSensor, TempLimits> = mapOf(
    DriveThermalSensor.OIL to TempLimits(oilWarnC, oilCritC),
    DriveThermalSensor.COOLANT to TempLimits(coolWarnC, coolCritC),
    DriveThermalSensor.PTU to TempLimits(ptuWarnC, ptuCritC),
    DriveThermalSensor.RDU to TempLimits(rduWarnC, rduCritC),
)

/**
 * Editor rows. [minC]..[maxC] is what the signal's decoder can report (byte − offset, so a limit
 * outside it could never be reached) — a reporting range, not a judgement of a safe value.
 */
enum class ThermalRow(val label: String, val minC: Double, val maxC: Double) {
    OIL("Oil", -50.0, 205.0),          // 0x0F8 byte1 − 50
    COOLANT("Coolant", -60.0, 963.0),  // 0x2F0 10-bit − 60
    PTU("PTU", -60.0, 195.0),          // 0x0F8 byte7 − 60
    RDU("RDU", -40.0, 215.0),          // 0x1E8A byte − 40
    CLUTCH("AWD clutches", -40.0, 215.0), // 0x1E8B / 0x1E8C byte − 40
    INTAKE("Intake air", -127.0, 128.75), // 0x2F0 10-bit × 0.25 − 127
}

enum class ThermalKind(val label: String) { MIN("Warm-up"), WARN("Warning"), CRIT("Critical") }

class ThermalField(
    val key: String, val row: ThermalRow, val kind: ThermalKind,
    val get: (ThermalProfile) -> Double, val set: (ThermalProfile, Double) -> ThermalProfile,
)

// ── Units without drift ─────────────────────────────────────────────────────

fun cToUnit(c: Double, unit: String): Double = if (unit == "C") c else c * 9.0 / 5.0 + 32.0
fun unitToC(x: Double, unit: String): Double = if (unit == "C") x else (x - 32.0) * 5.0 / 9.0

/** Editor text for a °C value: whole numbers stay whole, otherwise one decimal (121.11 °C → "250" °F). */
fun thermalEditText(c: Double, unit: String): String {
    val x = cToUnit(c, unit)
    val r1 = (x * 10).roundToLong() / 10.0
    return if (abs(r1 - Math.round(r1)) < 1e-9) Math.round(r1).toString() else "%.1f".format(java.util.Locale.US, r1)
}

/** Parses editor text in [unit] ("," accepted as decimal mark); null when it isn't a number. */
fun parseThermalInput(text: String, unit: String): Double? =
    text.trim().replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }?.let { unitToC(it, unit) }

/**
 * An editor cell: keeps the exact stored °C until the text is actually changed, so opening,
 * switching units and saving never rounds a value (no conversion drift).
 */
data class ThermalCell(val valueC: Double, val text: String, val unit: String) {
    val edited: Boolean get() = text != thermalEditText(valueC, unit)
    /** The °C this cell would save: the untouched stored value, or the parsed edit (null = not a number). */
    val resolvedC: Double? get() = if (!edited) valueC else parseThermalInput(text, unit)
    fun withUnit(newUnit: String): ThermalCell {
        val c = resolvedC ?: return copy(unit = newUnit)   // keep unparseable text for the user to fix
        return ThermalCell(c, thermalEditText(c, newUnit), newUnit)
    }
    companion object {
        fun of(c: Double, unit: String) = ThermalCell(c, thermalEditText(c, unit), unit)
    }
}

/**
 * Validation for a custom profile (°C). Ordering: warm-up < warning < critical. Separation keeps
 * the existing recovery margins meaningful: critical − warning > [HEAT_MARGIN_C] (2 °C) so a reading
 * can step down from critical while still above warning, and warning − warm-up > [HEAT_MARGIN_C] +
 * [WARM_MARGIN_C] (3 °C) so recovering below warning − 2 °C can coexist with "warm" (≥ minimum + 1 °C).
 * Values must be inside what the sensor can report. Returns field key → message ("" key = general).
 */
fun validateThermalProfile(cells: Map<String, Double?>, unit: String): Map<String, String> {
    val errors = linkedMapOf<String, String>()
    fun d(dc: Double) = if (unit == "C") "%.0f°C".format(dc) else "%.1f°F".format(dc * 9.0 / 5.0)
    fun t(c: Double) = thermalEditText(c, unit) + (if (unit == "C") "°C" else "°F")
    for (f in ThermalProfile.FIELDS) {
        val v = cells[f.key]
        if (v == null) { errors[f.key] = "enter a number"; continue }
        if (v < f.row.minC || v > f.row.maxC) errors[f.key] = "outside what the sensor can report (${t(f.row.minC)} to ${t(f.row.maxC)})"
    }
    for (row in ThermalRow.entries) {
        val fs = ThermalProfile.FIELDS.filter { it.row == row }.associateBy { it.kind }
        val min = fs[ThermalKind.MIN]?.let { cells[it.key] }
        val warn = fs[ThermalKind.WARN]?.let { cells[it.key] }
        val crit = fs[ThermalKind.CRIT]?.let { cells[it.key] }
        val warnKey = fs.getValue(ThermalKind.WARN).key
        val critKey = fs.getValue(ThermalKind.CRIT).key
        if (warn != null && crit != null && critKey !in errors && crit - warn <= HEAT_MARGIN_C)
            errors[critKey] = "must be more than ${d(HEAT_MARGIN_C)} above warning"
        if (min != null && warn != null && warnKey !in errors && warn - min <= HEAT_MARGIN_C + WARM_MARGIN_C)
            errors[warnKey] = "must be more than ${d(HEAT_MARGIN_C + WARM_MARGIN_C)} above warm-up"
    }
    return errors
}
