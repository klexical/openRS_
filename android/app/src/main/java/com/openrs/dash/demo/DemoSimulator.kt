package com.openrs.dash.demo

import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/** Debug demo scenarios. Each is a pure function of scenario time — reset replays it exactly. */
enum class DemoScenario(val label: String, val chip: String, val blurb: String) {
    IDLE("Idle", "IDLE", "Cold start at standstill, e-brake on — engine + drivetrain warm up"),
    DRIVING("Driving", "DRIVE", "60 s loop: launch, pull to 125 km/h, braking, corner, cruise"),
    WARNING("Warnings", "WARN", "Hot lap: overheating, knock, low tyre, underboost, low fuel, charge fault"),
    CONNECTION_LOSS("Connection loss", "LOSS", "40 s loop: live 10 s → lost 12 s → OBD re-warm 4 s → live"),
    COOLDOWN("Cool-down", "COOL", "Readiness: oil cools past warn, RDU heats then its replies stop, coolant drops out, oil below warm-up, clutch heat"),
    LAUNCH("Launch control", "LAUNCH", "40 s: e-brake → + LC on → link drop + recovery → LC on → LC engaged → launch → cruise"),
}

/** Adapter link as the real CanDataService would report it. */
enum class DemoLink { LIVE, LOST, RECOVERING }

/**
 * Pure telemetry generator for demo mode. Produces the same [VehicleState] fields the CAN
 * decoders and Mode 22 parsers populate, so every screen renders through its real code path.
 * Fields with no real decoder (IPC warning lamps, manifoldChargeTempC) are left at their sentinels.
 *
 * No randomness: "noise" is a sum of incommensurate sines, so the same (scenario, t) always
 * gives the same frame.
 */
object DemoSimulator {

    const val DRIVE_CYCLE_S = 60.0
    const val LOSS_CYCLE_S = 40.0
    const val LOSS_START_S = 10.0
    const val LOSS_END_S = 22.0
    const val RECOVER_END_S = 26.0

    /** COOLDOWN: RDU replies stop (stamp withheld, value held) — stale 90 s later, then a fresh poll. */
    const val COOL_RDU_SILENT_S = 70.0
    const val COOL_RDU_BACK_S = 170.0
    /** COOLDOWN: coolant reads invalid (sentinel) while the RDU warning is fresh. */
    const val COOL_COOLANT_OUT_S = 85.0
    const val COOL_COOLANT_BACK_S = 100.0
    /** COOLDOWN: oil starts its second cool-down (below the 80 °C warm-up minimum ≈ 23 s later). */
    const val COOL_OIL_DROP_S = 180.0

    /** LAUNCH: e-brake 0–25 s, LC on (0x420) 5–33 s, LC engaged (0x225) 23–27 s (with the e-brake still on to 25 s),
     *  link lost 10–15 s, recovering to 17 s. */
    const val LAUNCH_CYCLE_S = 40.0
    const val LAUNCH_EBRAKE_OFF_S = 25.0
    const val LAUNCH_LC_ON_S = 5.0
    const val LAUNCH_LC_OFF_S = 33.0
    const val LAUNCH_ENGAGED_S = 23.0
    const val LAUNCH_GO_S = 27.0
    const val LAUNCH_LOST_S = 10.0
    const val LAUNCH_RECOVER_S = 15.0
    const val LAUNCH_LIVE_S = 17.0
    /** COOLDOWN: AWD clutches heat past their fixed 90 / 120 °C limits, then cool (recovery hold). */
    const val COOL_CLUTCH_HEAT_S = 230.0
    const val COOL_CLUTCH_COOL_S = 255.0

    /** Freshness tags the simulator writes — CAN-sourced THERMAL tags + every polled Mode 22 tag. */
    val CAN_TAGS = arrayOf("oilTempC", "ptuTempC", "coolantTempC", "intakeTempC", "ambientTempC")
    val OBD_TAGS = arrayOf(
        "calcLoad", "shortFuelTrim", "longFuelTrim", "timingAdvance", "fuelRailPressure",
        "o2Voltage", "afrActual", "lambdaActual", "afrSensor1", "afrDesired", "commandedAfr",
        "chargeAirTempC", "catalyticTempC", "octaneAdjustRatio",
        "etcAngleActual", "etcAngleDesired", "tipActualKpa", "tipDesiredKpa", "wgdcDesired",
        "vctIntakeAngle", "vctExhaustAngle", "oilLifePct", "hpFuelRailPsi", "sparkAdvance",
        "ignCorrCyl1", "ignCorrCyl2", "ignCorrCyl3", "ignCorrCyl4",
        "tirePressLF", "tirePressRF", "tirePressLR", "tirePressRR",
        "tireTempLF", "tireTempRF", "tireTempLR", "tireTempRR",
        "odometerKm", "batterySoc", "batteryTempC", "cabinTempC", "batteryVoltage",
        "batteryChargingVoltageDesired", "batteryCurrentA",
        "rduTempC", "awdClutchTempL", "awdClutchTempR", "transOilTempC",
        "awdReqTorqueL", "awdReqTorqueR", "awdDmdPressure", "awdPumpCurrent",
        "awdClutchCurL", "awdClutchCurR", "awdClutchPressureL", "awdClutchPressureR",
        "rduEnabled", "pdcEnabled", "fengEnabled", "lcArmed", "lcRpmTarget", "assEnabled",
    )

    fun link(scenario: DemoScenario, tSec: Double): DemoLink {
        if (scenario == DemoScenario.LAUNCH) {
            val c = tSec % LAUNCH_CYCLE_S
            return when {
                c >= LAUNCH_LOST_S && c < LAUNCH_RECOVER_S -> DemoLink.LOST
                c >= LAUNCH_RECOVER_S && c < LAUNCH_LIVE_S -> DemoLink.RECOVERING
                else -> DemoLink.LIVE
            }
        }
        if (scenario != DemoScenario.CONNECTION_LOSS) return DemoLink.LIVE
        val c = tSec % LOSS_CYCLE_S
        return when {
            c < LOSS_START_S    -> DemoLink.LIVE
            c < LOSS_END_S      -> DemoLink.LOST
            c < RECOVER_END_S   -> DemoLink.RECOVERING
            else                -> DemoLink.LIVE
        }
    }

    /**
     * Next state for [scenario] at scenario time [tMs]. Peaks, VIN and other session-held fields
     * carry over from [cur]. While the link is LOST telemetry freezes and only the connection
     * flags change — same as a real adapter drop. [stamp] re-stamps freshness tags at [nowMs]
     * (the controller throttles this like the real 1 s CAN / poll-cycle stamping).
     */
    fun apply(
        cur: VehicleState,
        scenario: DemoScenario,
        tMs: Long,
        driveMode: DriveMode,
        escStatus: EscStatus,
        nowMs: Long,
        stamp: Boolean,
    ): VehicleState {
        val t = tMs / 1000.0
        val link = link(scenario, t)
        if (link == DemoLink.LOST) {
            return if (!cur.isConnected && cur.isIdle) cur else cur.copy(isConnected = false, isIdle = true)
        }
        val m = when (scenario) {
            DemoScenario.IDLE -> idle(t)
            DemoScenario.DRIVING, DemoScenario.CONNECTION_LOSS -> driving(t, driveMode, hot = false)
            DemoScenario.WARNING -> driving(t, driveMode, hot = true)
            DemoScenario.COOLDOWN -> cooldown(t, driveMode)
            DemoScenario.LAUNCH -> launch(t, driveMode)
        }
        val recovering = link == DemoLink.RECOVERING
        var next = build(cur, m, driveMode, escStatus, recovering, nowMs).withPeaksUpdated()
        if (stamp) {
            next = when {
                recovering -> next.markFresh(*CAN_TAGS, now = nowMs)
                scenario == DemoScenario.COOLDOWN && t >= COOL_RDU_SILENT_S && t < COOL_RDU_BACK_S ->
                    next.markFresh(*CAN_TAGS, *OBD_TAGS.filter { it != "rduTempC" }.toTypedArray(), now = nowMs)
                else -> next.markFresh(*CAN_TAGS, *OBD_TAGS, now = nowMs)
            }
        }
        return next
    }

    // ── Motion model ─────────────────────────────────────────────────────────

    /** Intermediate physical model for one instant — mapped onto VehicleState by [build]. */
    internal data class Model(
        val rpm: Double, val speedKph: Double, val throttle: Double, val brakeBar: Double,
        val longG: Double, val latG: Double, val boostKpa: Double, val tipDesiredKpa: Double,
        val wgdc: Double, val torqueNm: Double, val rearShare: Double,
        val oilC: Double, val coolantC: Double, val intakeC: Double, val chargeAirC: Double,
        val ptuC: Double, val rduC: Double, val clutchC: Double, val transOilC: Double,
        val tireTempC: Double, val tirePsi: DoubleArray, val fuelPct: Double,
        val battV: Double, val battA: Double, val battSoc: Double,
        val kr: DoubleArray, val ltft: Double, val octane: Double,
        val afrLeanBias: Double, val eBrake: Boolean,
        /** 0x420 LaunchControlStatus / 0x225 LC engaged — only the LAUNCH scenario sets them. */
        val lcOn: Boolean = false, val lcEngaged: Boolean = false,
    )

    /** Smooth deterministic noise in roughly [-1, 1]. */
    internal fun noise(t: Double, seed: Double): Double =
        0.5 * sin(t * 7.31 + seed) + 0.3 * sin(t * 13.07 + seed * 2.1) + 0.2 * sin(t * 2.13 + seed * 3.7)

    private fun smoothstep(e0: Double, e1: Double, x: Double): Double {
        val k = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0)
        return k * k * (3 - 2 * k)
    }

    /** First-order approach from [from] to [to] with time constant [tau] seconds. */
    private fun approach(from: Double, to: Double, t: Double, tau: Double) = to + (from - to) * exp(-t / tau)

    // (cycle s, speed km/h, throttle %, brake bar, lateral g)
    private val DRIVE_KEYS = arrayOf(
        doubleArrayOf(0.0, 0.0, 0.0, 12.0, 0.0),
        doubleArrayOf(3.0, 0.0, 0.0, 12.0, 0.0),
        doubleArrayOf(3.6, 3.0, 100.0, 0.0, 0.0),
        doubleArrayOf(12.0, 125.0, 100.0, 0.0, 0.0),
        doubleArrayOf(13.0, 125.0, 22.0, 0.0, 0.0),
        doubleArrayOf(20.0, 113.0, 18.0, 0.0, 0.0),
        doubleArrayOf(21.0, 110.0, 0.0, 45.0, 0.0),
        doubleArrayOf(26.0, 55.0, 0.0, 8.0, 0.0),
        doubleArrayOf(28.0, 52.0, 35.0, 0.0, 0.85),
        doubleArrayOf(32.0, 56.0, 40.0, 0.0, -0.9),
        doubleArrayOf(37.0, 58.0, 35.0, 0.0, 0.6),
        doubleArrayOf(38.0, 58.0, 70.0, 0.0, 0.0),
        doubleArrayOf(46.0, 100.0, 60.0, 0.0, 0.0),
        doubleArrayOf(54.0, 100.0, 18.0, 0.0, 0.0),
        doubleArrayOf(55.0, 98.0, 0.0, 30.0, 0.0),
        doubleArrayOf(59.5, 0.0, 0.0, 20.0, 0.0),
        doubleArrayOf(60.0, 0.0, 0.0, 12.0, 0.0),
    )

    /** Keyframe column [col] at cycle time [c], smoothstep-eased between keys. */
    private fun key(c: Double, col: Int): Double {
        for (i in 0 until DRIVE_KEYS.size - 1) {
            val a = DRIVE_KEYS[i]; val b = DRIVE_KEYS[i + 1]
            if (c <= b[0]) {
                val k = smoothstep(a[0], b[0], c)
                return a[col] + (b[col] - a[col]) * k
            }
        }
        return DRIVE_KEYS.last()[col]
    }

    /** Code-equivalent gear ratios (VehicleState.derivedGear uses rpm × 0.03194 / kph). */
    private val GEAR_R = doubleArrayOf(3.43, 2.07, 1.40, 1.09, 0.87, 0.73)
    private const val GEAR_K = 0.03194

    private fun gearFor(speed: Double, throttle: Double): Int {
        if (throttle >= 50) {
            for (g in GEAR_R.indices) if (speed * GEAR_R[g] / GEAR_K <= 6300) return g
            return GEAR_R.lastIndex
        }
        for (g in GEAR_R.indices.reversed()) if (speed * GEAR_R[g] / GEAR_K >= 1600) return g
        return 0
    }

    private fun torqueCurve(rpm: Double): Double = when {
        rpm < 2000 -> 250 + (rpm - 1000).coerceAtLeast(0.0) * 0.19
        rpm < 4500 -> 440.0
        else       -> 440 - (rpm - 4500) * 0.026
    }

    private fun boostPsiFor(throttle: Double, rpm: Double, peakPsi: Double): Double {
        if (throttle < 60) return -12.0 + throttle * 0.2   // part throttle: vacuum
        val spool = smoothstep(2000.0, 3600.0, rpm)
        val taper = 1.0 - 0.2 * smoothstep(5000.0, 6800.0, rpm)
        return -0.5 + (peakPsi + 0.5) * spool * taper * ((throttle - 60) / 40)
    }

    private fun psiToKpa(psi: Double) = 101.325 + psi / 0.14503773

    internal fun driving(t: Double, mode: DriveMode, hot: Boolean): Model {
        val c = t % DRIVE_CYCLE_S
        val speed = key(c, 1).coerceAtLeast(0.0)
        val throttle = key(c, 2)
        val brake = key(c, 3)
        val latG = key(c, 4) + 0.03 * noise(t, 1.0)
        val dv = (key((c + 0.05).coerceAtMost(DRIVE_CYCLE_S), 1) -
                  key((c - 0.05).coerceAtLeast(0.0), 1)) / 0.1      // km/h per s
        val longG = (dv / 3.6) / 9.81

        val g = gearFor(speed, throttle)
        val wheelRpm = speed * GEAR_R[g] / GEAR_K
        val launching = speed < 12 && throttle > 50
        val rpm = when {
            speed < 3 && !launching -> 850.0 + 15 * noise(t, 2.0)
            launching               -> max(wheelRpm, 3800 + 200 * smoothstep(3.0, 3.6, c))
            else                    -> max(900.0, wheelRpm)
        }.coerceAtMost(6900.0)

        val modePsi = when (mode) { DriveMode.SPORT, DriveMode.TRACK -> 1.0; else -> 0.0 }
        val lagThrottle = key((c - 0.35).coerceAtLeast(0.0), 2)
        val targetPsi = boostPsiFor(lagThrottle, rpm, 23.0 + modePsi)
        val actualPsi = if (hot && targetPsi > 4) targetPsi * 0.78 else targetPsi   // underboost
        val boostKpa = psiToKpa(actualPsi + 0.15 * noise(t, 3.0))
        val wgdc = if (lagThrottle >= 60) (if (hot) 98.0 else 55 + 30 * smoothstep(2500.0, 6000.0, rpm)) else 0.0

        val torque = if (throttle < 2) (if (speed > 5) -35.0 else 18.0)
                     else torqueCurve(rpm) * (throttle / 100) * (if (hot) 0.85 else 1.0)
        val rearShare = when (mode) {
            DriveMode.DRIFT -> 0.70
            DriveMode.TRACK -> 0.50
            else            -> 0.15 + 0.30 * (throttle / 100)
        }

        val heat = if (hot) 1.0 else 0.0
        val pull = smoothstep(4500.0, 6500.0, rpm) * (if (throttle > 80) 1.0 else 0.0)
        // Warning: temperatures climb across the first 45 s, then hold in the critical band.
        val oil = if (hot) approach(118.0, 133.0, t, 25.0) else 98 + 4 * sin(t / 9)
        val coolant = if (hot) approach(103.0, 114.0, t, 20.0) else 91 + 2 * sin(t / 11)
        val tireLow = if (hot) approach(29.0, 24.0, t, 30.0) else 35.6 + 0.8 * (1 - exp(-t / 120))
        val tireBase = 36.0 + 1.4 * (1 - exp(-t / 120)) + 1.5 * heat
        val kr = if (hot) doubleArrayOf(3.4 * pull, 2.6 * pull, 1.2 * pull, 4.1 * pull)
                 else doubleArrayOf(0.6 * pull, 0.0, 0.0, 0.0)
        return Model(
            rpm = rpm, speedKph = speed, throttle = throttle, brakeBar = brake,
            longG = longG, latG = latG, boostKpa = boostKpa, tipDesiredKpa = psiToKpa(targetPsi),
            wgdc = wgdc, torqueNm = torque, rearShare = rearShare,
            oilC = oil, coolantC = coolant,
            intakeC = 31 + 8 * smoothstep(0.0, 20.0, actualPsi) + 6 * heat,
            chargeAirC = 36 + 16 * smoothstep(0.0, 20.0, actualPsi) + 18 * heat,
            ptuC = if (hot) approach(98.0, 118.0, t, 40.0) else 68 + 3 * sin(t / 15),
            rduC = if (hot) approach(80.0, 96.0, t, 40.0) else 52 + 2 * sin(t / 17),
            clutchC = (if (hot) 85.0 else 48.0) + 6 * pull + 4 * abs(latG),
            transOilC = if (hot) 92.0 else 58.0,
            tireTempC = if (hot) approach(38.0, 47.0, t, 30.0) else 27 + 4 * (1 - exp(-t / 90)),
            tirePsi = doubleArrayOf(tireBase, tireBase + 0.1, tireLow, tireBase - 0.4),
            fuelPct = if (hot) 6.5 - (t * 0.004).coerceAtMost(3.0) else 61.0 - (t * 0.004).coerceAtMost(20.0),
            battV = if (hot) 11.9 + 0.05 * noise(t, 4.0) else 14.3 + 0.04 * noise(t, 4.0),
            battA = if (hot) -14.0 else 6.5 + noise(t, 5.0),
            battSoc = if (hot) 38.0 else 82.0,
            kr = kr, ltft = if (hot) 9.4 else 2.3, octane = if (hot) 0.82 else 1.0,
            afrLeanBias = if (hot) 1.6 * pull else 0.0, eBrake = false,
        )
    }

    /**
     * Readiness walk-through on a gentle cruise (DRIVING motion, no heat): oil 126 → 108 °C (high,
     * cooling, recovery hold, ready) · RDU climbs 60 → 95 °C from 40 s (forecast, then high) ·
     * coolant invalid 85–100 s · RDU replies stop at 70 s (stale at 160 s, fresh again at 170 s at
     * 70 °C) · oil cools again from 180 s and drops below its 80 °C warm-up minimum · from 230 s the
     * AWD clutches climb past their fixed warn (≈ 236 s) and crit (≈ 248 s), then cool from 255 s.
     */
    internal fun cooldown(t: Double, mode: DriveMode): Model {
        val base = driving(t, mode, hot = false)
        val oil = if (t < COOL_OIL_DROP_S) approach(126.0, 108.0, t, 40.0)
                  else approach(108.0, 76.0, t - COOL_OIL_DROP_S, 12.0)
        val rdu = when {
            t < 40.0 -> 60.0
            t < COOL_RDU_BACK_S -> approach(60.0, 95.0, t - 40.0, 12.0)
            else -> 70.0
        }
        val coolantOut = t >= COOL_COOLANT_OUT_S && t < COOL_COOLANT_BACK_S
        val clutch = when {
            t < COOL_CLUTCH_HEAT_S -> 50.0
            t < COOL_CLUTCH_COOL_S -> approach(50.0, 128.0, t - COOL_CLUTCH_HEAT_S, 8.0)
            else -> approach(approach(50.0, 128.0, COOL_CLUTCH_COOL_S - COOL_CLUTCH_HEAT_S, 8.0), 60.0, t - COOL_CLUTCH_COOL_S, 6.0)
        }
        return base.copy(
            oilC = oil, coolantC = if (coolantOut) -99.0 else 92.0,
            ptuC = 70.0, rduC = rdu, clutchC = clutch, transOilC = 58.0,
            kr = doubleArrayOf(0.0, 0.0, 0.0, 0.0),
        )
    }

    /**
     * Launch-control walk-through at standstill, then the launch (DRIVING's motion from its launch point):
     * e-brake → e-brake + LC on → link lost / recovering with both on → LC engaged with the e-brake still on →
     * LC engaged, holding 4,500 rpm → launch (engaged clears; LC on alone) → LC off → cruise. Only flags a real decoder writes (0x0C8, 0x420, 0x225).
     */
    internal fun launch(t: Double, mode: DriveMode): Model {
        val c = t % LAUNCH_CYCLE_S
        val engaged = c >= LAUNCH_ENGAGED_S && c < LAUNCH_GO_S
        val base = when {
            c < LAUNCH_ENGAGED_S -> driving(1.0, mode, hot = false)                        // standstill, foot on the brake
            engaged -> driving(1.0, mode, hot = false).copy(                               // LC holding launch rpm
                rpm = 4500.0 + 25 * noise(t, 13.0), throttle = 100.0, brakeBar = 0.0,
                boostKpa = psiToKpa(4.0 + 0.3 * noise(t, 14.0)), torqueNm = 90.0)
            c < LAUNCH_GO_S + 9.0 -> driving(3.6 + (c - LAUNCH_GO_S), mode, hot = false)    // the launch and pull
            else -> driving(13.0 + (c - LAUNCH_GO_S - 9.0), mode, hot = false)             // cruise
        }
        return base.copy(eBrake = c < LAUNCH_EBRAKE_OFF_S,
            lcOn = c >= LAUNCH_LC_ON_S && c < LAUNCH_LC_OFF_S, lcEngaged = engaged)
    }

    internal fun idle(t: Double): Model {
        val rpm = 850 + 350 * exp(-t / 45) + 12 * noise(t, 6.0)
        return Model(
            rpm = rpm, speedKph = 0.0, throttle = 0.0, brakeBar = 0.0,
            longG = 0.01 * noise(t, 7.0), latG = 0.01 * noise(t, 8.0),
            boostKpa = 33 + 1.5 * noise(t, 9.0), tipDesiredKpa = 98.0, wgdc = 0.0,
            torqueNm = 18 + 3 * noise(t, 10.0), rearShare = 0.0,
            oilC = approach(18.0, 86.0, t, 110.0), coolantC = approach(19.0, 90.0, t, 75.0),
            intakeC = approach(19.0, 34.0, t, 120.0), chargeAirC = approach(19.0, 30.0, t, 150.0),
            ptuC = approach(17.0, 45.0, t, 220.0), rduC = approach(16.0, 34.0, t, 240.0),
            clutchC = approach(16.0, 30.0, t, 200.0), transOilC = approach(16.0, 32.0, t, 240.0),
            tireTempC = 19.0, tirePsi = doubleArrayOf(34.2, 34.0, 33.6, 33.7), fuelPct = 64.0,
            battV = 14.6 - 0.3 * (1 - exp(-t / 60)), battA = 4 + 16 * exp(-t / 40), battSoc = 79.0,
            kr = doubleArrayOf(0.0, 0.0, 0.0, 0.0), ltft = 1.6, octane = 1.0,
            afrLeanBias = 0.0, eBrake = true,
        )
    }

    // ── VehicleState mapping ─────────────────────────────────────────────────

    const val DEMO_VIN = "WF0DXXGCBDDEMO001"
    private const val ODOMETER_KM = 41_237L

    private fun build(
        cur: VehicleState, m: Model, mode: DriveMode, esc: EscStatus, recovering: Boolean, nowMs: Long,
    ): VehicleState {
        val load = (18 + 0.78 * m.throttle).coerceIn(0.0, 100.0)
        val wot = m.throttle >= 80
        val lambdaCmd = if (wot) 0.80 else 1.0
        val afrDes = lambdaCmd * 14.7
        val afrAct = afrDes * (1 + 0.01 * sin(m.rpm / 400)) + m.afrLeanBias
        val krMax = m.kr.max()
        val timing = (if (wot) 9.0 else 22.0 - load * 0.08) - krMax
        val speedMs = m.speedKph / 3.6
        val yaw = if (speedMs > 1) m.latG * 9.81 / speedMs * (180 / PI) else 0.0
        val rearTotal = max(0.0, m.torqueNm) * m.rearShare
        val bias = (0.5 + 0.35 * m.latG).coerceIn(0.1, 0.9)
        val wheel = { k: Double -> max(0.0, m.speedKph * (1 + k)) }
        val tps = m.throttle.coerceIn(0.0, 100.0)
        val pressure = if (recovering) doubleArrayOf(-1.0, -1.0, -1.0, -1.0) else m.tirePsi
        val tireT = if (recovering) -99.0 else m.tireTempC
        val obd = !recovering
        return cur.copy(
            rpm = m.rpm, coolantTempC = m.coolantC, oilTempC = m.oilC, intakeTempC = m.intakeC,
            boostKpa = m.boostKpa, throttlePct = tps, throttleHasSource = true,
            accelPedalPct = tps, torqueAtTrans = m.torqueNm,
            calcLoad = load, shortFuelTrim = 1.8 * sin(m.rpm / 300 + m.speedKph / 10),
            longFuelTrim = m.ltft, timingAdvance = timing, fuelRailPressure = 480 + 40 * load / 100,
            barometricPressure = 101.3, commandedAfr = lambdaCmd,
            o2Voltage = 0.45 + 0.38 * sin(m.rpm / 60), afrSensor1 = afrAct,
            chargeAirTempC = if (obd) m.chargeAirC else -99.0,
            octaneAdjustRatio = m.octane,
            catalyticTempC = if (obd) 480 + 3.2 * load else -99.0,
            afrActual = afrAct, afrDesired = afrDes, lambdaActual = afrAct / 14.7,
            etcAngleActual = tps * 0.82, etcAngleDesired = tps * 0.82 + 0.4,
            tipActualKpa = if (obd) m.boostKpa + 4 else 0.0,
            tipDesiredKpa = if (obd) max(m.tipDesiredKpa + 4, m.boostKpa + 2) else 0.0,
            wgdcDesired = m.wgdc,
            vctIntakeAngle = 18 + 14 * load / 100, vctExhaustAngle = 6 + 8 * load / 100,
            oilLifePct = if (obd) 68.0 else -1.0, hpFuelRailPsi = if (obd) 700 + 15 * load else -1.0,
            sparkAdvance = if (obd) timing.coerceAtLeast(0.0) else -1.0,
            ignCorrCyl1 = m.kr[0], ignCorrCyl2 = m.kr[1], ignCorrCyl3 = m.kr[2], ignCorrCyl4 = m.kr[3],
            tirePressLF = pressure[0], tirePressRF = pressure[1],
            tirePressLR = pressure[2], tirePressRR = pressure[3],
            tireTempLF = tireT, tireTempRF = tireT, tireTempLR = if (obd) tireT + 1 else -99.0,
            tireTempRR = if (obd) tireT + 1 else -99.0,
            tpmsSensorIdLF = 0x1A2B3C01, tpmsSensorIdRF = 0x1A2B3C02,
            tpmsSensorIdRR = 0x1A2B3C03, tpmsSensorIdLR = 0x1A2B3C04,
            tpmsLastUpdate = if (obd) nowMs else cur.tpmsLastUpdate,
            speedKph = m.speedKph, steeringAngle = m.latG * 160, brakePressure = m.brakeBar,
            yawRate = yaw, lateralG = m.latG, longitudinalG = m.longG,
            verticalG = 1.0 + 0.02 * noise(m.rpm / 100, 11.0),
            wheelSpeedFL = wheel(-0.03 * m.latG), wheelSpeedFR = wheel(0.03 * m.latG),
            wheelSpeedRL = wheel(-0.03 * m.latG), wheelSpeedRR = wheel(0.03 * m.latG),
            avgFrontWheelSpeedKph = m.speedKph,
            awdLeftTorque = rearTotal * (1 - bias), awdRightTorque = rearTotal * bias,
            rduTempC = if (obd) m.rduC else -99.0, awdMaxTorque = 1_200.0,
            ptuTempC = m.ptuC,
            awdClutchTempL = m.clutchC, awdClutchTempR = m.clutchC + 1.5,
            awdReqTorqueL = rearTotal * (1 - bias), awdReqTorqueR = rearTotal * bias,
            awdDmdPressure = rearTotal * 0.06, awdPumpCurrent = 1.2 + rearTotal * 0.004,
            transOilTempC = m.transOilC,
            awdClutchCurL = 0.4 + rearTotal * (1 - bias) * 0.004,
            awdClutchCurR = 0.4 + rearTotal * bias * 0.004,
            awdClutchPressureL = rearTotal * (1 - bias) * 25, awdClutchPressureR = rearTotal * bias * 25,
            vin = DEMO_VIN, driveMode = mode, escStatus = esc, gear = 0,
            batteryVoltage = m.battV, fuelLevelPct = m.fuelPct, ambientTempC = 21.0,
            gaugeIllumination = 12, eBrake = m.eBrake, reverseStatus = false,
            launchControlActive = m.lcOn, launchControlEngaged = m.lcEngaged,
            engineStatus = 183, ignitionStatus = 7,
            odometerKm = if (obd) ODOMETER_KM else -1L,
            batterySoc = if (obd) m.battSoc else -1.0, batteryTempC = if (obd) 27.0 else -99.0,
            cabinTempC = if (obd) 22.0 else -99.0,
            batteryChargingVoltageDesired = 14.4, batteryCurrentA = m.battA,
            rduEnabled = true, pdcEnabled = true, fengEnabled = true,
            lcArmed = false, lcRpmTarget = 4500, assEnabled = false,
            isConnected = true, isIdle = false,
            framesPerSecond = 400 + 12 * noise(m.rpm / 500, 12.0),
            lastUpdate = nowMs,
        )
    }
}
