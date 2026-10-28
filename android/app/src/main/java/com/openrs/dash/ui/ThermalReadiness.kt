package com.openrs.dash.ui

import android.os.SystemClock
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.ThermalPredictor
import com.openrs.dash.data.ThermalPredictor.ClimbTrend
import com.openrs.dash.data.VehicleState
import com.openrs.dash.demo.DemoMode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.pow

/**
 * THERMAL readiness — one verdict for DRIVE's status row, THERMAL's summary card and the drive
 * recorder's race-ready marker (rc.6, rules approved by the owner 2026-10-25).
 *
 * States, worst first (the worst true state is the headline): OVERHEATING (fresh reading ≥ preset
 * crit) › TEMPERATURE HIGH (≥ preset warn) › HEAT RISING (the predictor forecasts crit within
 * 5 min) › CHECKING (oil / coolant missing or stale, or a reported optional sensor stale / invalid)
 * › ENGINE WARMING › DRIVETRAIN WARMING › TEMPS READY. NO LIVE DATA replaces all of them while
 * offline / reconnecting. Heat states rank above CHECKING, so missing data adds to a fresh warning
 * instead of hiding it — and CHECKING still blocks TEMPS READY.
 *
 * Transitions: getting worse is immediate. Getting better is held, on the monotonic clock:
 *  - heat: a latched level clears once the reading has stayed under its limit − 2 °C for 10 s;
 *  - forecast: HEAT RISING clears once the forecast has been invalid for 10 s;
 *  - warm-up: a sensor seen cold becomes warm at minimum + 1 °C, held 10 s for CAN readings
 *    (oil, coolant, PTU) or on one fresh poll for the AWD-polled RDU / clutch temps. A reading
 *    already above that on its first fresh sample is warm at once (no transition to steady).
 *    Dropping below the minimum ends warm-up readiness on the next evaluation — no margin, no delay.
 * An evaluation more than [MAX_STEP_MS] after the previous one restarts every running hold, so a
 * stalled tick never counts as several passing seconds. A sensor that goes stale or invalid drops
 * its heat / forecast latch at once: a recovery hold never presents a stale reading as a current
 * warning. Offline, demo exit / reset / scenario change and a session reset clear everything,
 * including the predictor's history. Changing the preset re-derives the heat states from scratch.
 *
 * Comparisons stay in canonical °C; the format helpers at the bottom render the user's unit.
 */
enum class ThermalState(val headline: String) {
    NO_LIVE_DATA("NO LIVE DATA"),
    OVERHEATING("TEMP CRITICAL"),
    TEMPERATURE_HIGH("TEMP HIGH"),
    HEAT_RISING("HEAT RISING"),
    CHECKING("CHECKING"),
    ENGINE_WARMING("ENGINE WARMING"),
    DRIVETRAIN_WARMING("DRIVETRAIN WARMING"),
    TEMPS_READY("TEMPS READY");

    /** Heat states lead DRIVE's status row like a vehicle alert. */
    val isHeat: Boolean get() = this == OVERHEATING || this == TEMPERATURE_HIGH || this == HEAT_RISING
}

/**
 * The readings that decide readiness. Warm-up minimums and limits come from the active
 * [ThermalProfile]; forecast floors are the existing THERMAL alert floors. Oil / coolant are
 * required (CAN 0x0F8 / 0x2F0 on every car); the rest count once reported.
 */
enum class ThermalSensor(
    /** Compact name (DRIVE row, cards, checklist): "Clutch L". */
    val label: String,
    /** Name at the start of a sentence: "Left clutch above warning limit". */
    val spoken: String,
    val tag: String,
    val required: Boolean, val polled: Boolean,
    val forecastLabel: String? = null, val forecastFloorC: Double = 0.0,
) {
    OIL("Oil", "Oil", "oilTempC", required = true, polled = false, "OIL", 80.0),
    COOLANT("Coolant", "Coolant", "coolantTempC", required = true, polled = false, "COOLANT", 85.0),
    PTU("PTU", "PTU", "ptuTempC", required = false, polled = false, "PTU", 50.0),
    RDU("RDU", "RDU", "rduTempC", required = false, polled = true, "RDU", 40.0),
    CLUTCH_L("Clutch L", "Left clutch", "awdClutchTempL", required = false, polled = true),
    CLUTCH_R("Clutch R", "Right clutch", "awdClutchTempR", required = false, polled = true);

    val isClutch: Boolean get() = this == CLUTCH_L || this == CLUTCH_R

    fun valueC(vs: VehicleState): Double = when (this) {
        OIL -> vs.oilTempC; COOLANT -> vs.coolantTempC; PTU -> vs.ptuTempC; RDU -> vs.rduTempC
        CLUTCH_L -> vs.awdClutchTempL; CLUTCH_R -> vs.awdClutchTempR
    }
}

/** AWD clutch limits in every built-in profile: the existing values that colour THERMAL's clutch cards. */
const val CLUTCH_WARN_C = 90.0
const val CLUTCH_CRIT_C = 120.0

/** Warm-up minimum and warn / crit per sensor from the active profile (built-in or Custom). */
data class ThermalLimits(val preset: String, val profile: ThermalProfile) {
    fun minC(s: ThermalSensor): Double = when (s) {
        ThermalSensor.OIL -> profile.oilMinC; ThermalSensor.COOLANT -> profile.coolMinC
        ThermalSensor.PTU -> profile.ptuMinC; ThermalSensor.RDU -> profile.rduMinC
        ThermalSensor.CLUTCH_L, ThermalSensor.CLUTCH_R -> profile.clutchMinC
    }
    fun warnC(s: ThermalSensor): Double = when (s) {
        ThermalSensor.OIL -> profile.oilWarnC; ThermalSensor.COOLANT -> profile.coolWarnC
        ThermalSensor.PTU -> profile.ptuWarnC; ThermalSensor.RDU -> profile.rduWarnC
        ThermalSensor.CLUTCH_L, ThermalSensor.CLUTCH_R -> profile.clutchWarnC
    }
    fun critC(s: ThermalSensor): Double = when (s) {
        ThermalSensor.OIL -> profile.oilCritC; ThermalSensor.COOLANT -> profile.coolCritC
        ThermalSensor.PTU -> profile.ptuCritC; ThermalSensor.RDU -> profile.rduCritC
        ThermalSensor.CLUTCH_L, ThermalSensor.CLUTCH_R -> profile.clutchCritC
    }
    val presetName: String get() = when (preset) { "race" -> "RACE"; "track" -> "TRACK"; "custom" -> "CUSTOM"; else -> "STREET" }

    companion object {
        fun from(p: UserPrefs) = ThermalLimits(if (p.tempPreset == "custom" && p.customThermal == null) "street" else p.tempPreset, p.thermalProfile)
    }
}

enum class HeatLevel { NONE, WARN, CRIT }

/** A valid predictive warning: fresh, below warn, crit forecast within the 5 min horizon. */
data class HeatForecast(val minutesToCrit: Double, val rateCPerMin: Double, val critC: Double)

/** Everything the verdict knows about one sensor this evaluation (°C; null = no reading). */
data class SensorReadiness(
    val sensor: ThermalSensor,
    val valueC: Double?,
    val avail: TempAvail,
    /** Since the sensor's last decode (its own measurement stamp — never refreshed by the evaluator). */
    val ageMs: Long?,
    val warnC: Double?,
    val critC: Double?,
    /** Latched heat level — may lag the reading while it recovers. */
    val heat: HeatLevel,
    /** Recovering: the reading has to stay under this for [HOLD_MS] before [heat] steps down. */
    val heatClearBelowC: Double?,
    /** How long it has been under [heatClearBelowC] (null = not under it yet). */
    val heatClearHeldMs: Long?,
    /** Latched forecast (HEAT RISING); kept for [HOLD_MS] after it stops being valid. */
    val forecast: HeatForecast?,
    /** True while the forecast is valid now; false while its clearing hold runs. */
    val forecastLive: Boolean,
    val forecastClearHeldMs: Long?,
    /** Predictor trend (null until its 10-sample minimum, or when not fresh / not predicted). */
    val trend: ClimbTrend?,
    val rateCPerMin: Double?,
    /** Warm-up latch; [minC] = the profile's minimum, [warmTargetC] = minimum + 1 °C. */
    val warm: Boolean,
    val minC: Double,
    val warmTargetC: Double,
    /** CAN reading at / above [warmTargetC], counting to [HOLD_MS] (null = not counting). */
    val warmHeldMs: Long?,
) {
    val fresh: Boolean get() = avail == TempAvail.AVAILABLE
    /** Required sensors always count; optional ones once they have reported (stamp or reading). */
    val counts: Boolean get() = sensor.required || avail != TempAvail.WARMING
    /** A counted sensor with no current measurement — blocks TEMPS READY (CHECKING). */
    val isGap: Boolean get() = counts && !fresh
    val heatRaw: HeatLevel get() {
        val v = valueC ?: return HeatLevel.NONE
        if (!fresh) return HeatLevel.NONE
        return when {
            critC != null && v >= critC -> HeatLevel.CRIT
            warnC != null && v >= warnC -> HeatLevel.WARN
            else -> HeatLevel.NONE
        }
    }
    val heatRecovering: Boolean get() = heat > heatRaw
    val forecastRecovering: Boolean get() = forecast != null && !forecastLive
}

data class ThermalVerdict(
    val state: ThermalState,
    val limits: ThermalLimits?,
    val sensors: List<SensorReadiness>,
    /** Banner forecasts (time-to-crit ≤ 5 min, including reached) for fresh sensors — THERMAL alert banners. */
    val banners: List<ThermalPredictor.SensorPrediction> = emptyList(),
    /** [DemoMode.sessionEpoch] the verdict was computed in — a verdict from an older session is void. */
    val epoch: Int = 0,
) {
    val isReady: Boolean get() = state == ThermalState.TEMPS_READY
    fun reading(s: ThermalSensor): SensorReadiness? = sensors.firstOrNull { it.sensor == s }
    val gaps: List<SensorReadiness> get() = sensors.filter { it.isGap }
    /** Conditions beyond the first deciding reading — other deciding readings, plus missing ones under a heat state. */
    val extraCount: Int get() {
        // The clutch pair in the same state reads as one subject ("Clutches"), so it isn't a "+1"
        val l = deciding.firstOrNull { it.sensor == ThermalSensor.CLUTCH_L }
        val r = deciding.firstOrNull { it.sensor == ThermalSensor.CLUTCH_R }
        val pair = if (l != null && r != null && l.heatRecovering == r.heatRecovering && l.forecastLive == r.forecastLive) 1 else 0
        return (deciding.size - 1 - pair).coerceAtLeast(0) + (if (state.isHeat) gaps.size else 0)
    }

    /** The readings that decide the headline, in sensor order. */
    val deciding: List<SensorReadiness> get() = when (state) {
        ThermalState.OVERHEATING -> sensors.filter { it.heat == HeatLevel.CRIT }
        ThermalState.TEMPERATURE_HIGH -> sensors.filter { it.heat == HeatLevel.WARN }
        ThermalState.HEAT_RISING -> sensors.filter { it.forecast != null }
        ThermalState.CHECKING -> gaps
        ThermalState.ENGINE_WARMING -> sensors.filter { it.sensor.required && !it.warm }
        ThermalState.DRIVETRAIN_WARMING -> sensors.filter { !it.sensor.required && it.counts && !it.warm }
        else -> emptyList()
    }

    companion object {
        fun noLiveData(epoch: Int = 0) = ThermalVerdict(ThermalState.NO_LIVE_DATA, null, emptyList(), epoch = epoch)
    }
}

/** Heat recovery margin under warn / crit (3.6 °F). */
const val HEAT_MARGIN_C = 2.0
/** Warm-up margin above a minimum when gaining readiness (1.8 °F). */
const val WARM_MARGIN_C = 1.0
/** Improvement hold. */
const val HOLD_MS = 10_000L
/** Forecast horizon — the existing THERMAL alert horizon. */
const val FORECAST_HORIZON_MIN = 5.0
/** Evaluation cadence = the predictor's existing ~1 Hz sampling. */
const val READINESS_TICK_MS = 1_000L
/** A gap longer than this between evaluations restarts every hold. */
const val MAX_STEP_MS = 2_000L

/**
 * Stateful evaluator. Not thread-safe — [ThermalReadinessSession] serialises access; tests drive
 * it directly with explicit wall ([nowMs], for freshness and predictor samples) and monotonic
 * ([monoMs], for holds) clocks.
 */
class ThermalReadinessEngine(private val predictor: ThermalPredictor = ThermalPredictor()) {

    /** Hold timer on the monotonic clock. */
    private class Hold {
        var since = -1L
        fun reset() { since = -1L }
        /** Elapsed ms while [passing], restarting after a reset, a failing evaluation or a long gap. */
        fun run(passing: Boolean, mono: Long, continuous: Boolean): Long? {
            if (!passing) { since = -1L; return null }
            if (since < 0 || !continuous) since = mono
            return mono - since
        }
    }

    private class Latch {
        var heat = HeatLevel.NONE
        val underCrit = Hold()
        val underWarn = Hold()
        var forecast: HeatForecast? = null
        val forecastClear = Hold()
        var warm = false
        var seenBelowTarget = false
        val aboveTarget = Hold()
        fun clearHeat() { heat = HeatLevel.NONE; underCrit.reset(); underWarn.reset(); forecast = null; forecastClear.reset() }
    }

    private val latches = ThermalSensor.entries.associateWith { Latch() }
    private var lastMono = -1L
    private var lastFeedMono = -1L
    private var lastLimits: ThermalLimits? = null

    /** Drops every hold, latch and the predictor's history (offline, session reset, demo epoch). */
    fun reset() {
        latches.values.forEach { it.clearHeat(); it.warm = false; it.seenBelowTarget = false; it.aboveTarget.reset() }
        predictor.reset()
        lastMono = -1L
        lastFeedMono = -1L
        lastLimits = null
    }

    fun evaluate(vs: VehicleState, limits: ThermalLimits, nowMs: Long, monoMs: Long, epoch: Int = 0): ThermalVerdict {
        if (!vs.isConnected) { reset(); return ThermalVerdict.noLiveData(epoch) }
        val continuous = lastMono >= 0 && monoMs - lastMono in 0..MAX_STEP_MS
        lastMono = monoMs
        if (limits != lastLimits) {
            // A profile / threshold change is not a recovery or a warm-up: no hold (heat, forecast or
            // warm-up) carries across different thresholds — every reading is re-derived from scratch.
            latches.values.forEach { it.clearHeat(); it.warm = false; it.seenBelowTarget = false; it.aboveTarget.reset() }
            lastLimits = limits
        }

        val avail = ThermalSensor.entries.associateWith { s -> vs.tempAvail(s.valueC(vs), s.tag, nowMs) }

        // Predictor: fresh samples only, at its existing ~1 Hz cadence (wall-clock timestamps as before).
        if (lastFeedMono < 0 || monoMs - lastFeedMono >= READINESS_TICK_MS - 50) {
            lastFeedMono = monoMs
            ThermalSensor.entries.forEach { s ->
                val label = s.forecastLabel ?: return@forEach
                if (avail[s] == TempAvail.AVAILABLE) predictor.recordSample(label, nowMs, s.valueC(vs))
            }
        }

        val banners = mutableListOf<ThermalPredictor.SensorPrediction>()
        val readings = ThermalSensor.entries.map { s ->
            val a = avail.getValue(s)
            val raw = s.valueC(vs)
            val v = raw.takeIf { it > -90 }
            val fresh = a == TempAvail.AVAILABLE
            val stamp = vs.fieldLastUpdateMs[s.tag]?.takeIf { it > 0 }
            val warn = limits.warnC(s)
            val crit = limits.critC(s)
            val l = latches.getValue(s)

            // ── Heat (worse at once; better after HEAT_MARGIN_C held HOLD_MS) ──
            var clearBelow: Double? = null
            var clearHeld: Long? = null
            if (!fresh || v == null) {
                l.clearHeat()   // stale / invalid: no current measured warning
            } else {
                val rawHeat = when { v >= crit -> HeatLevel.CRIT; v >= warn -> HeatLevel.WARN; else -> HeatLevel.NONE }
                val critHeld = l.underCrit.run(v < crit - HEAT_MARGIN_C, monoMs, continuous)
                val warnHeld = l.underWarn.run(v < warn - HEAT_MARGIN_C, monoMs, continuous)
                if (rawHeat > l.heat) l.heat = rawHeat
                if (l.heat == HeatLevel.CRIT && (critHeld ?: -1) >= HOLD_MS) l.heat = HeatLevel.WARN
                if (l.heat == HeatLevel.WARN && (warnHeld ?: -1) >= HOLD_MS) l.heat = HeatLevel.NONE
                if (l.heat > rawHeat) {
                    clearBelow = (if (l.heat == HeatLevel.CRIT) crit else warn) - HEAT_MARGIN_C
                    clearHeld = if (l.heat == HeatLevel.CRIT) critHeld else warnHeld
                }
            }

            // ── Forecast (HEAT RISING) ──
            val label = s.forecastLabel
            var forecastLive = false
            var forecastHeld: Long? = null
            if (label != null && fresh && v != null) {
                val pred = predictor.predict(label, crit, s.forecastFloorC)
                val ttc = pred?.timeToCriticalMin
                if (pred != null && ttc != null && ttc <= FORECAST_HORIZON_MIN) banners += pred
                val valid = pred != null && !pred.reached && ttc != null && ttc <= FORECAST_HORIZON_MIN && v < warn
                if (valid) {
                    l.forecast = HeatForecast(ttc!!, pred!!.climbRateCPerMin, crit)
                    l.forecastClear.reset()
                    forecastLive = true
                } else if (l.forecast != null) {
                    forecastHeld = l.forecastClear.run(true, monoMs, continuous)
                    if ((forecastHeld ?: -1) >= HOLD_MS) { l.forecast = null; l.forecastClear.reset(); forecastHeld = null }
                }
            } else {
                l.forecast = null; l.forecastClear.reset()
            }

            // ── Warm-up (below the minimum ends it at once; gaining is held) ──
            val minC = limits.minC(s)
            val target = minC + WARM_MARGIN_C
            var warmHeld: Long? = null
            if (fresh && v != null) {
                when {
                    v < minC -> { l.warm = false; l.seenBelowTarget = true; l.aboveTarget.reset() }
                    l.warm -> Unit
                    v < target -> { l.seenBelowTarget = true; l.aboveTarget.reset() }
                    !l.seenBelowTarget || s.polled -> l.warm = true   // first sample already warm / one fresh poll
                    else -> {
                        warmHeld = l.aboveTarget.run(true, monoMs, continuous)
                        if ((warmHeld ?: -1) >= HOLD_MS) { l.warm = true; warmHeld = null }
                    }
                }
            } else {
                l.aboveTarget.reset()
            }

            val trend = if (fresh && label != null) predictor.trendOrNull(label) else null
            SensorReadiness(
                sensor = s, valueC = v, avail = a,
                ageMs = stamp?.let { (nowMs - it).coerceAtLeast(0L) },
                warnC = warn, critC = crit,
                heat = l.heat, heatClearBelowC = clearBelow, heatClearHeldMs = clearHeld,
                forecast = l.forecast, forecastLive = forecastLive, forecastClearHeldMs = forecastHeld,
                trend = trend, rateCPerMin = if (trend != null) predictor.slopeCPerMin(label!!) else null,
                warm = l.warm, minC = minC, warmTargetC = target, warmHeldMs = warmHeld,
            )
        }

        val state = when {
            readings.any { it.heat == HeatLevel.CRIT } -> ThermalState.OVERHEATING
            readings.any { it.heat == HeatLevel.WARN } -> ThermalState.TEMPERATURE_HIGH
            readings.any { it.forecast != null } -> ThermalState.HEAT_RISING
            readings.any { it.isGap } -> ThermalState.CHECKING
            readings.any { it.sensor.required && !it.warm } -> ThermalState.ENGINE_WARMING
            readings.any { !it.sensor.required && it.counts && !it.warm } -> ThermalState.DRIVETRAIN_WARMING
            else -> ThermalState.TEMPS_READY
        }
        return ThermalVerdict(state, limits, readings, banners, epoch)
    }
}

/**
 * Session-level readiness: evaluated at 1 Hz on its own coroutine, independent of which tab is
 * open (the predictor used to be fed only while THERMAL was composed). Started from
 * [OpenRSDashApp.onCreate]; a link change or demo epoch change re-evaluates at once.
 */
object ThermalReadinessSession {
    private val engine = ThermalReadinessEngine()
    private val _verdict = MutableStateFlow(ThermalVerdict.noLiveData())
    val verdict: StateFlow<ThermalVerdict> = _verdict.asStateFlow()

    /** THERMAL banner snoozes (label → wall ms). Snoozing hides the banner only, never HEAT RISING. */
    private val _snoozed = MutableStateFlow<Map<String, Long>>(emptyMap())
    val snoozed: StateFlow<Map<String, Long>> = _snoozed.asStateFlow()

    private var job: Job? = null
    private var lastEpoch = Int.MIN_VALUE

    fun start() {
        if (job != null) return
        val app = OpenRSDashApp.instance
        job = CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            launch {
                combine(
                    DemoMode.sessionEpoch,
                    app.vehicleState.map { it.isConnected }.distinctUntilChanged(),
                    UserPrefsStore.prefs.map { ThermalLimits.from(it) }.distinctUntilChanged(),
                ) { _, _, _ -> }.collect { evaluateNow() }
            }
            while (isActive) {
                evaluateNow()
                delay(READINESS_TICK_MS)
            }
        }
    }

    /** Session reset (GARAGE reset session): history and holds belong to the old session. */
    @Synchronized fun reset() {
        engine.reset()
        _snoozed.value = emptyMap()
        evaluateNow()
    }

    fun snooze(label: String) {
        _snoozed.update { it + (label to System.currentTimeMillis() + 120_000L) }
    }

    @Synchronized private fun evaluateNow() {
        val epoch = DemoMode.sessionEpoch.value
        if (epoch != lastEpoch) {
            lastEpoch = epoch
            engine.reset()
            _snoozed.value = emptyMap()
        }
        val vs = OpenRSDashApp.instance.vehicleState.value
        if (!vs.isConnected) _snoozed.value = emptyMap()
        _verdict.value = engine.evaluate(
            vs, ThermalLimits.from(UserPrefsStore.prefs.value),
            System.currentTimeMillis(), SystemClock.elapsedRealtime(), epoch,
        )
    }
}

// ── Availability policy ─────────────────────────────────────────────────────

/**
 * Freshness window per temperature: the AWD-polled RDU / clutch / trans-oil temps (0x703 DIDs
 * 0x1E8A–0x1E8C and 0x1E80, one reply per BCM/AWD poll cycle ≈ 37 s = 30 s + 22 queries × 0.3 s) go
 * stale after 90 s — one missed reply stays fresh, two in a row don't. CAN broadcasts (oil,
 * coolant, PTU, …) keep 60 s. Trans oil isn't a readiness input; it shares the window because it
 * shares the poll.
 */
fun tempStaleAfterMs(tag: String): Long = when (tag) {
    "rduTempC", "awdClutchTempL", "awdClutchTempR", "transOilTempC" -> 90_000L
    else -> 60_000L
}

/** The one availability rule for a temperature reading — every surface and readiness use it. */
fun VehicleState.tempAvail(valueC: Double, tag: String, now: Long = System.currentTimeMillis()): TempAvail =
    tempAvailFor(valueC, fieldLastUpdateMs[tag], tempStaleAfterMs(tag), now).whenDisconnected(isConnected)

// ── Text (user's unit; comparisons above stay in °C) ────────────────────────

private fun UserPrefs.t(c: Double) = "${displayTemp(c)}$tempLabel"
internal fun UserPrefs.inUnit(c: Double) = if (tempUnit == "C") c else c * 9.0 / 5.0 + 32
/** A limit, bound or target: whole numbers stay whole, others keep one decimal (warn − 2 °C = 244.4°F, not "244"). */
internal fun UserPrefs.lim(c: Double): String = displayLimit(c) + tempLabel
/** Reading [v] beside [limit]: the fewest decimals that keep it on the correct side ("244.4°F · needs under 244.4°F"). */
internal fun UserPrefs.tVs(v: Double, limit: Double): String {
    val x = inUnit(v); val l = inUnit(limit)
    for (d in 0..2) {
        val k = 10.0.pow(d)
        if ((x < l) == (Math.round(x * k) / k < l)) return "%.${d}f".format(x) + tempLabel
    }
    return "%.2f".format(x) + tempLabel
}
private fun minutes(m: Double) = if (m < 1.0) "<1 min" else "~${m.toInt()} min"
private fun UserPrefs.d(dc: Double) = "${displayTempDelta(dc)}$tempLabel"
private fun secs(ms: Long?) = ((ms ?: 0L) / 1000L).coerceIn(0L, HOLD_MS / 1000L)
private fun ageText(ms: Long?) = ms?.let { "${it / 1000L}\u00A0s ago" } ?: ""
/** "6 of 10 s", kept on one line (non-breaking spaces) so a wrap never strands the "s". */
private fun holdText(ms: Long?) = "${secs(ms)}\u00A0of\u00A0${HOLD_MS / 1000}\u00A0s"
private fun gapText(r: SensorReadiness) = when (r.avail) {
    TempAvail.STALE -> "${r.sensor.label} stale"
    TempAvail.UNAVAILABLE -> if (r.sensor.required) "${r.sensor.label} not reporting" else "${r.sensor.label} unavailable"
    else -> "${r.sensor.label} not reporting"
}
private fun gapWithAge(r: SensorReadiness) =
    gapText(r) + (if (r.avail == TempAvail.STALE) r.ageMs?.let { ", last ${ageText(it)}" } ?: "" else "")

private fun trendText(r: SensorReadiness, p: UserPrefs): String? = when (r.trend) {
    ClimbTrend.RISING -> "rising ${p.displayTempRate(r.rateCPerMin ?: 0.0)}${p.tempLabel}/min"
    ClimbTrend.COOLING -> "cooling ${p.displayTempRate(-(r.rateCPerMin ?: 0.0))}${p.tempLabel}/min"
    ClimbTrend.STABLE -> "steady"
    null -> null
}
private fun arrow(r: SensorReadiness) = when (r.trend) { ClimbTrend.RISING -> " ↑"; ClimbTrend.COOLING -> " ↓"; else -> "" }

/** "Oil", "Oil and coolant", "Oil, coolant and PTU" — first name as spoken, the rest lower-case. */
private fun names(rs: List<Subject>): String {
    val n = rs.mapIndexed { i, r -> if (i == 0) r.spoken else r.spoken.replaceFirstChar { it.lowercase() } }
    return when (n.size) { 0 -> ""; 1 -> n[0]; else -> n.dropLast(1).joinToString(", ") + " and " + n.last() }
}

/** The bound a recovering reading must stay under (warn / crit − 2 °C). */
private fun clearBound(r: SensorReadiness): Double? =
    r.heatClearBelowC ?: (if (r.heat == HeatLevel.CRIT) r.critC else r.warnC)?.minus(HEAT_MARGIN_C)

/** What a sentence is about: one reading, or both clutches when they're in the same state. */
private class Subject(val spoken: String, val label: String, val r: SensorReadiness)

/**
 * [ThermalVerdict.deciding] with the clutch pair merged ("Both clutches" / "Clutches") when both are
 * deciding in the same phase. The representative is the one the verdict waits on: the hotter while
 * past a limit, the one with less recovery progress while cooling.
 */
private fun ThermalVerdict.subjects(): List<Subject> {
    val d = deciding
    val l = d.firstOrNull { it.sensor == ThermalSensor.CLUTCH_L }
    val r = d.firstOrNull { it.sensor == ThermalSensor.CLUTCH_R }
    fun single(x: SensorReadiness) = Subject(x.sensor.spoken, x.sensor.label, x)
    if (l == null || r == null || l.heatRecovering != r.heatRecovering || l.forecastLive != r.forecastLive) return d.map { single(it) }
    val rep = if (l.heatRecovering) listOf(l, r).minBy { it.heatClearHeldMs ?: -1L } else listOf(l, r).maxBy { it.valueC ?: -999.0 }
    return d.filterNot { it.sensor.isClutch }.map { single(it) } + Subject("Both clutches", "Clutches", rep)
}

/** One reading's sentence for THERMAL's summary — plain words; exact comparisons live in the checklist. */
private fun line(subject: Subject, state: ThermalState, p: UserPrefs): String {
    val r = subject.r
    val name = subject.spoken
    val v = r.valueC ?: return gapText(r)
    return when (state) {
        ThermalState.OVERHEATING, ThermalState.TEMPERATURE_HIGH -> {
            val which = if (state == ThermalState.OVERHEATING) "critical" else "warning"
            if (r.heatRecovering) {
                // Severity stays until the hold completes; the reason says what it is waiting for.
                val bound = clearBound(r)
                "$name cooling — confirming recovery · " +
                    if (r.heatClearHeldMs == null) "needs below ${bound?.let { p.lim(it) } ?: "the limit"}" else holdText(r.heatClearHeldMs)
            } else "$name above $which limit · ${p.t(v)}" + (trendText(r, p)?.let { ", $it" } ?: "")
        }
        ThermalState.HEAT_RISING -> {
            val f = r.forecast ?: return "$name ${p.t(v)}"
            if (r.forecastLive) "$name rising — critical limit in ${minutes(f.minutesToCrit)}"
            else "$name rise easing — confirming · ${holdText(r.forecastClearHeldMs)}"
        }
        ThermalState.ENGINE_WARMING, ThermalState.DRIVETRAIN_WARMING ->
            if (r.warmHeldMs != null) "$name steady above ${p.lim(r.warmTargetC)} · ${holdText(r.warmHeldMs)}"
            else "$name warming · ready at ${p.lim(r.warmTargetC)}"
        else -> "$name ${p.t(v)}"
    }
}

/** THERMAL summary reason: what the headline is about, in plain words, then other blockers ("+ RDU stale"). */
fun ThermalVerdict.reason(p: UserPrefs): String {
    val lim = limits ?: return "Waiting for live data"
    if (state == ThermalState.TEMPS_READY) return "Monitored temperatures meet the ${lim.presetName} profile"
    if (state == ThermalState.CHECKING) return deciding.joinToString(" · ") { gapWithAge(it) }
    val subjects = subjects()
    val live = subjects.filterNot { it.r.heatRecovering }
    val main = if (state.isHeat && state != ThermalState.HEAT_RISING && live.size > 1) {
        // Several readings past the same limit: one sentence (each value is on its card and in the checklist)
        val which = if (state == ThermalState.OVERHEATING) "critical" else "warning"
        (listOf("${names(live)} above $which limit") + subjects.filter { it.r.heatRecovering }.map { line(it, state, p) })
            .joinToString(" · ")
    } else subjects.joinToString(" · ") { line(it, state, p) }
    val extra = if (state.isHeat) gaps.joinToString(" · ") { gapWithAge(it) } else ""
    val engineOk = if (state == ThermalState.DRIVETRAIN_WARMING) "Engine warm · " else ""
    return engineOk + main + if (extra.isNotEmpty()) " + $extra" else ""
}

/**
 * DRIVE's 48 dp row: one short phrase so the headline never has to give way; THERMAL carries the
 * sentence and the checklist the exact numbers. Targets use the same text as THERMAL (177.8°F).
 */
fun ThermalVerdict.shortReason(p: UserPrefs): String {
    val lim = limits ?: return ""
    if (state == ThermalState.TEMPS_READY) return "within ${lim.presetName} profile"
    val subject = subjects().firstOrNull() ?: return ""
    val first = subject.r
    val name = subject.label
    val v = first.valueC
    return when {
        state == ThermalState.CHECKING || v == null -> gapText(first)
        state == ThermalState.OVERHEATING || state == ThermalState.TEMPERATURE_HIGH ->
            // Progress as "n/10" — "Clutches cooling · 1/10 s" was cut off beside TEMP CRITICAL at 360 dp
            if (first.heatRecovering) "$name cooling" + (first.heatClearHeldMs?.let { " ${secs(it)}/${HOLD_MS / 1000}" } ?: "")
            else "$name ${p.t(v)}${arrow(first)}"
        state == ThermalState.HEAT_RISING -> first.forecast?.let { f ->
            if (!first.forecastLive) "$name easing" else "$name crit in ${minutes(f.minutesToCrit)}"
        } ?: name
        first.warmHeldMs != null -> "$name steady ${secs(first.warmHeldMs)}/${HOLD_MS / 1000}"
        // Beside the longer DRIVETRAIN headline the current value gives way so the target fits at 360 dp
        state == ThermalState.DRIVETRAIN_WARMING -> "$name → ${p.lim(first.warmTargetC)}"
        else -> "$name ${p.displayTemp(v)} → ${p.lim(first.warmTargetC)}"
    }
}

enum class CheckMark { OK, WAIT, ALERT }
data class CheckItem(val mark: CheckMark, val label: String, val value: String, val level: Level = Level.NORMAL)

/** THERMAL's collapsible checklist — the exact targets and comparisons behind the headline, one row per rule. */
fun ThermalVerdict.checklist(p: UserPrefs): List<CheckItem> {
    val lim = limits ?: return emptyList()
    fun warmRow(label: String, rs: List<SensorReadiness>): CheckItem? {
        val shown = rs.filter { it.counts }
        if (shown.isEmpty()) return null
        val values = shown.joinToString(" · ") { it.valueC?.let { v -> p.displayTemp(v) } ?: "—" }
        val ok = shown.all { it.fresh && it.warm }
        val held = shown.mapNotNull { it.warmHeldMs }.minOrNull()
        // Warm: the minimum that would end readiness. Not yet: the target it waits for (min + 1 °C, then the hold).
        val value = when {
            ok -> "$values / min ${p.lim(shown.first().minC)}"
            shown.any { !it.fresh } -> shown.filter { !it.fresh }.joinToString(" · ") { gapText(it) }
            held != null -> "$values${p.tempLabel} · ${holdText(held)}"
            else -> "$values → ${p.lim(shown.first().warmTargetC)}"
        }
        return CheckItem(if (ok) CheckMark.OK else CheckMark.WAIT, label, value)
    }
    fun one(s: ThermalSensor) = listOfNotNull(reading(s))
    /** Exact comparison for a reading at / past a limit, or recovering from one. */
    fun hotValue(r: SensorReadiness): String {
        val v = r.valueC ?: return gapText(r)
        val name = if (r.sensor.isClutch) r.sensor.label.removePrefix("Clutch ") else r.sensor.label
        if (r.heatRecovering) {
            val bound = clearBound(r) ?: return "$name ${p.t(v)}"
            return if (r.heatClearHeldMs == null) "$name ${p.tVs(v, bound)}, needs <${p.lim(bound)}"
                   else "$name ${p.tVs(v, bound)} <${p.lim(bound)} · ${holdText(r.heatClearHeldMs)}"
        }
        val crit = r.heat == HeatLevel.CRIT
        val limit = (if (crit) r.critC else r.warnC) ?: return "$name ${p.t(v)}"
        return "$name ${p.tVs(v, limit)} ≥ ${p.lim(limit)}" + if (crit) " crit" else ""
    }
    /** "Below <limit>" for [group]: alert past it, incomplete (WAIT) while any reading in it is unavailable. */
    fun limitRow(label: String, group: List<SensorReadiness>): CheckItem? {
        val shown = group.filter { it.counts }
        if (shown.isEmpty()) return null
        val hot = shown.filter { it.heat != HeatLevel.NONE }
        val missing = shown.filter { !it.fresh }
        return when {
            hot.isNotEmpty() -> CheckItem(CheckMark.ALERT, label, hot.joinToString(" · ") { hotValue(it) },
                if (hot.any { it.heat == HeatLevel.CRIT }) Level.CRIT else Level.WARN)
            missing.isNotEmpty() -> CheckItem(CheckMark.WAIT, label,
                (if (missing.size < shown.size) "others below · " else "") + missing.joinToString(" · ") { gapText(it) })
            else -> CheckItem(CheckMark.OK, label, "all")
        }
    }
    val forecastable = sensors.filter { it.sensor.forecastLabel != null && it.counts }
    val forecasts = sensors.filter { it.forecast != null }
    val forecastGaps = forecastable.filter { !it.fresh }
    val polled = sensors.filter { it.sensor.polled && it.fresh }.maxByOrNull { it.ageMs ?: 0L }
    val closest = sensors.filter { it.fresh && it.valueC != null && it.warnC != null && it.heat == HeatLevel.NONE }
        .minByOrNull { it.warnC!! - it.valueC!! }
    return listOfNotNull(
        warmRow("Oil warm-up", one(ThermalSensor.OIL)),
        warmRow("Coolant warm-up", one(ThermalSensor.COOLANT)),
        warmRow("PTU warm-up", one(ThermalSensor.PTU)),
        warmRow("RDU warm-up", one(ThermalSensor.RDU)),
        warmRow("Clutches warm-up", one(ThermalSensor.CLUTCH_L) + one(ThermalSensor.CLUTCH_R)),
        limitRow("Below ${lim.presetName} warning", sensors.filter { !it.sensor.isClutch }),
        limitRow("Clutches below ${p.lim(lim.profile.clutchWarnC)}", sensors.filter { it.sensor.isClutch }),
        when {
            forecasts.isNotEmpty() -> CheckItem(CheckMark.ALERT, "No heat forecast",
                forecasts.joinToString(" · ") { r ->
                    r.sensor.label + if (r.forecastLive) " ${p.lim(r.forecast!!.critC)} in ${minutes(r.forecast.minutesToCrit)}" else " easing · ${holdText(r.forecastClearHeldMs)}"
                }, Level.WARN)
            forecastGaps.isNotEmpty() -> CheckItem(CheckMark.WAIT, "No heat forecast",
                (if (forecastGaps.size < forecastable.size) "others none · " else "") + forecastGaps.joinToString(" · ") { gapText(it) })
            else -> CheckItem(CheckMark.OK, "No heat forecast", "—")
        },
        closest?.let { CheckItem(CheckMark.OK, "Closest to warning", "${it.sensor.label} ${p.d(it.warnC!! - it.valueC!!)} below") },
        if (gaps.isEmpty()) CheckItem(CheckMark.OK, "Readings current",
            polled?.let { "${it.sensor.label} ${ageText(it.ageMs)}" } ?: "all")
        else CheckItem(CheckMark.WAIT, "Readings current",
            gaps.joinToString(" · ") { gapText(it) + (if (it.avail == TempAvail.STALE) it.ageMs?.let { a -> " ${a / 1000} s" } ?: "" else "") }),
    )
}

/**
 * THERMAL's one alert banner: the soonest prediction leads; the others are named as already
 * at / above critical or as genuine forecasts — never a reached reading called "forecast".
 */
data class ForecastBanner(
    val lead: ThermalPredictor.SensorPrediction,
    val alsoReached: List<String>,
    val alsoForecast: List<String>,
)

fun forecastBanner(banners: List<ThermalPredictor.SensorPrediction>): ForecastBanner? {
    val sorted = banners.sortedBy { if (it.reached) -1.0 else it.timeToCriticalMin ?: 0.0 }
    val lead = sorted.firstOrNull() ?: return null
    val rest = sorted.drop(1)
    return ForecastBanner(lead, rest.filter { it.reached }.map { it.sensorLabel }, rest.filterNot { it.reached }.map { it.sensorLabel })
}

/**
 * The verdict's word for DRIVE's VEHICLE summary when no vehicle alert is active: a heat state
 * (including a forecast or a recovery hold) or missing / stale readiness data — so the panel never
 * says ALL NORMAL while the status row above it doesn't. Null when the verdict has nothing to add.
 */
fun ThermalVerdict.vehicleAdvisory(): Pair<String, Level>? = when (state) {
    ThermalState.OVERHEATING -> state.headline to Level.CRIT
    ThermalState.TEMPERATURE_HIGH, ThermalState.HEAT_RISING -> state.headline to Level.WARN
    ThermalState.CHECKING -> (if (gaps.any { it.avail == TempAvail.STALE }) "STALE DATA" else "CHECKING") to Level.STALE
    else -> null
}
