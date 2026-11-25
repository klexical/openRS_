package com.openrs.dash.data

import com.openrs.dash.can.CanDecoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Live telemetry (GLOSSARY.md): what the app knows about the car on the current connection — the latest readings
 * ([readings]) and the running figures worked out from them (fuel economy, knock count, boost sweep, performance timer).
 *
 * The only writer of the live readings, and the only code that feeds or resets the running figures
 * (android/docs/adr/0001-live-telemetry-single-writer.md; `LiveTelemetryWriterGuardTest` fails on any other writer).
 * Everyone else reads [readings] / [knockCount], which are read-only views.
 *
 * The seam is on the domain side of decoding: the connection's frame path decodes and logs a CAN frame and hands the
 * decoded change in ([onCanFrame]); an OBD reply comes in with the recording drive's GPS fix ([onObdReply]); demo mode
 * hands its simulated state in ([onSimulated], [feedSimulated]). Nothing here touches Room, GPS or the drive recorder: a finished
 * performance run is returned to the caller, which saves it. The wall clock is passed in; the running-figure objects keep
 * their own clocks while they stay objects.
 *
 * Reset events, one function each: [newConnection], [userReset], [resetPeaks], [demoReplay], [demoExit]
 * (demo start = [demoExit]'s clearing, then [demoReplay]).
 * Thermal readiness stays an app-level object and is told through [Thermal].
 */
class LiveTelemetry(
    /** The backing store. Production passes none; tests may hand in their own to watch it. */
    private val state: MutableStateFlow<VehicleState> = MutableStateFlow(VehicleState()),
    private val knocks: MutableStateFlow<Int> = MutableStateFlow(0),
    private val wallClock: () -> Long = System::currentTimeMillis,
    private val thermal: Thermal = Thermal.NONE,
) {
    /** Thermal readiness hears the two reset events it acts on; it keeps its own warm-up history otherwise. */
    interface Thermal {
        /** A new connection began (readiness keeps its warm-up history across a reconnect). */
        fun newConnection() {}
        /** The user reset live telemetry (DIAG): readiness starts over. */
        fun userReset() {}

        companion object { val NONE = object : Thermal {} }
    }

    /** The latest readings — read-only. */
    val readings: StateFlow<VehicleState> = state.asStateFlow()

    /** Knock events this connection (header / PERF badge): freshly polled KR showing > 1° retard on any cylinder. */
    val knockCount: StateFlow<Int> = knocks.asStateFlow()

    /** A 0–60 / 0–100 run that just finished; the caller decides where it goes (the demo guard stays at the save). */
    data class FinishedRun(val result: PerformanceTimer.TimerResult, val ambientTempC: Double, val atMs: Long)

    /** Stamp of the last KR reading already counted from a real OBD reply. */
    private var lastKrCountedMs = 0L
    /** The same for simulated replies (demo mode keeps its own, as it always did). */
    private var lastSimKrCountedMs = 0L

    // ── Feeds ────────────────────────────────────────────────────────────────

    /**
     * One CAN frame, already decoded by the connection's frame path. [decode] maps the current readings to: the same
     * instance (recognized, nothing changed), a new state (stamped), or null (not a frame this app decodes). It runs
     * inside a compare-and-set loop and may run more than once, so it holds no side effects beyond its logging.
     * Returns whether [decode] produced a new state, and a finished performance run, if this frame ended one.
     */
    fun onCanFrame(canId: Int, fps: Double, decode: (VehicleState) -> VehicleState?): FrameOutcome {
        var newState: VehicleState? = null
        var decodedHere = false                     // set inside the CAS lambda: idempotent across retries
        state.update { current ->
            newState = null
            val decoded = decode(current)
            decodedHere = decoded != null
            when {
                decoded == null || decoded === current -> current
                else -> decoded.withPeaksUpdated().copy(framesPerSecond = fps).also { newState = it }
            }
        }
        // Side effects run exactly once, after the CAS succeeded — never inside the update{} lambda
        val wp = newState ?: return FrameOutcome(decodedHere, null)
        val now = wallClock()
        val boost = BoostSession.onState(wp, now)                   // #207: source evidence + per-source peaks
        if (canId == ID_SPEED || canId == ID_FUEL) FuelEconomy.onUpdate(wp.fuelLevelPct, wp.speedKph)
        var finished: FinishedRun? = null
        if (canId == ID_SPEED) {                                    // speed from 0x130, ~100 Hz
            val (sixty, hundred) = PerformanceTimer.onSpeedUpdate(wp.speedKph, wp.rpm, boost)
            if (hundred || (sixty && PerformanceTimer.state.value == PerformanceTimer.State.FINISHED)) {
                PerformanceTimer.result.value?.let { finished = FinishedRun(it, wp.ambientTempC, now) }
            }
        }
        return FrameOutcome(decodedHere, finished)
    }

    /** What [onCanFrame] did with a frame. */
    data class FrameOutcome(val decoded: Boolean, val finishedRun: FinishedRun?)

    /**
     * One OBD reply ([obdState] is the poller's state with the reply decoded) merged into the readings, then the knock
     * count, session knock log (#174), boost rule (#207) and boost sweep (#175) fed from the merged state. [fixLat] /
     * [fixLng]: the recording drive's own fix, at most 2 s old (#311), else null.
     */
    fun onObdReply(obdState: VehicleState, fixLat: Double? = null, fixLng: Double? = null) {
        state.update { current -> ObdMerge.merge(current, obdState) }
        lastKrCountedMs = countKnock(obdState, lastKrCountedMs)
        val now = wallClock()
        KnockSessionLog.onState(state.value, now, fixLat, fixLng)
        BoostSession.onState(state.value, now)
        BoostSessionLog.onState(state.value, now)
    }

    /** The link's flags on the readings (the connection publishes its phase). */
    fun setLink(connected: Boolean, reconnecting: Boolean) {
        state.update { it.copy(isConnected = connected, isReconnecting = reconnecting) }
    }

    /**
     * Demo mode's tick, part 1: [simulate] maps the current readings to the simulated ones (it returns them unchanged
     * once demo mode is off).
     */
    fun onSimulated(simulate: (VehicleState) -> VehicleState) { state.update(simulate) }

    /**
     * Demo mode's tick, part 2 (a running, linked tick): the running figures are fed from the simulated readings as for
     * real frames; [feedLogs] adds the knock log, boost sweep and knock count (a stamped tick on a live simulated link).
     */
    fun feedSimulated(nowMs: Long, feedLogs: Boolean) {
        val vs = state.value
        FuelEconomy.onUpdate(vs.fuelLevelPct, vs.speedKph)
        val boost = BoostSession.onState(vs, nowMs)
        PerformanceTimer.onSpeedUpdate(vs.speedKph, vs.rpm, boost)
        if (feedLogs) {
            KnockSessionLog.onState(vs, nowMs)
            BoostSessionLog.onState(vs, nowMs)
            lastSimKrCountedMs = countKnock(vs, lastSimKrCountedMs)
        }
    }

    /** Counts a KR reading newer than [countedMs] showing > 1° retard (retard is POSITIVE, raw / −512); returns the new mark. */
    private fun countKnock(vs: VehicleState, countedMs: Long): Long {
        val krStamp = KnockCapture.KR_TAGS.maxOf { vs.fieldLastUpdateMs[it] ?: 0L }
        if (krStamp <= countedMs) return countedMs
        val kr = maxOf(vs.ignCorrCyl1, vs.ignCorrCyl2, vs.ignCorrCyl3, vs.ignCorrCyl4)
        if (kr > 1.0) knocks.update { it + 1 }
        return krStamp
    }

    // ── Reset events ─────────────────────────────────────────────────────────

    /**
     * A new connection began (every start path: launch, Connect, RETRY, an automatic start). Readings that need this
     * connection's own frame are forgotten, and the running figures restart.
     */
    fun newConnection() {
        CanDecoder.resetSessionState()
        state.update {
            LaunchControl.clearedForNewConnection(it).copy(
                // ESC is unknown until this connection's first 0x1C0 frame — a value held from an earlier link is not a reading
                escStatus = EscStatus.UNKNOWN,
                // SRS lamp (#199): unknown until this connection's first 0x040 frame. The IPC lamps are not reset here.
                lamps = it.lamps.copy(srs = null),
                // Engine state (0x360 byte 7, #328): not received until this connection's first 0x360 frame
                engineState360 = -1,
            ).let { s ->
                // The car's speed is known only once this connection's 0x130 arrives: a parked drive drops GPS steps (#335)
                s.copy(fieldLastUpdateMs = s.fieldLastUpdateMs - CanDecoder.SPEED_TAG)
            }
        }
        restartRunningFigures()
        thermal.newConnection()
    }

    /**
     * RETRY's own blanking, before its new connection: OBD-polled fields go back to their sentinels so stale values from
     * the previous connection don't persist for up to 30 s while new polls complete.
     */
    fun retryForgetPolled() {
        state.update {
            it.copy(
                isConnected  = false, isReconnecting = false,
                tirePressLF  = -1.0,  tirePressRF  = -1.0,
                tirePressLR  = -1.0,  tirePressRR  = -1.0,
                rduTempC     = -99.0, ptuTempC     = -99.0,
                odometerKm   = -1L,
                batterySoc   = -1.0,
                batteryTempC = -99.0, cabinTempC   = -99.0,
            )
        }
    }

    /** The user's reset (DIAG): every reading, the running figures, peaks and thermal readiness; the link is untouched. */
    fun userReset() {
        CanDecoder.resetSessionState()
        restartRunningFigures()
        state.update { VehicleState(isConnected = it.isConnected) }
        thermal.userReset()
    }

    /** Peaks only (the readings and running figures stay). */
    fun resetPeaks() {
        state.update { it.withPeaksReset() }
        BoostSession.resetPeaks()
    }

    /** Demo mode replays its scenario from t = 0 (a scenario switch, a reset, a replay to a time). */
    fun demoReplay() {
        knocks.value = 0
        restartLogsAndCalculators()
        state.value = VehicleState(isConnected = true)
    }

    /** Demo mode ends (and, first, demo mode starts): every simulated value is forgotten and the app shows nothing until a real connection. */
    fun demoExit() {
        CanDecoder.resetSessionState()
        state.value = VehicleState()
        knocks.value = 0
        restartLogsAndCalculators()
    }

    private fun restartRunningFigures() {
        FuelEconomy.reset()
        PerformanceTimer.resetSession()
        knocks.value = 0
        KnockSessionLog.reset()
        BoostSessionLog.reset()
        BoostSession.reset()
    }

    private fun restartLogsAndCalculators() {
        KnockSessionLog.reset()
        BoostSessionLog.reset()
        BoostSession.reset()
        FuelEconomy.reset()
        PerformanceTimer.resetSession()
    }

    /**
     * Test-only: shows [vs] as the readings (screenshot goldens, connection tests that need a held reading from "the
     * previous link"). Production never calls it.
     */
    @androidx.annotation.VisibleForTesting
    fun showForTest(vs: VehicleState) { state.value = vs }

    private companion object {
        const val ID_SPEED = 0x130
        const val ID_FUEL = 0x380
    }
}
