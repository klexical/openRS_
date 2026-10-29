package com.openrs.dash.service

import com.openrs.dash.can.CanDecoder
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.PerfRunEntity
import com.openrs.dash.data.PerformanceTimer
import com.openrs.dash.data.VehicleState
import com.openrs.dash.demo.DemoMode
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * The work one connection job ([gen]) does with live data (#212): raw CAN frames into VehicleState + the derived
 * calculations fed from them (fuel economy, performance timer, session frame count, frame logging), and auto-record's
 * start / resume / finalize decision. [CanDataService] calls it from the job's collectors; it holds no Android types,
 * so the production callers are unit-tested against stop / reconnect interleavings.
 *
 * Every apply runs inside [LinkOwnership.deliver] — the ownership check and the change are one step, so a stop or
 * reconnect can't land between them. Only in-memory work happens under that lock (the state CAS, the derived
 * calculators, the recorder calls, which just launch coroutines); the perf-run Room insert is handed to [perfRunSink]
 * after the lock is released.
 */
internal class LinkSession(
    private val link: LinkOwnership,
    val gen: Int,
    private val state: MutableStateFlow<VehicleState>,
    private val recorder: Recorder,
    /** Called once per frame applied to this session (the session record's frame count). */
    private val onFrameCounted: () -> Unit,
    /** Persists a finished 0–60 / 0–100 run. */
    private val perfRunSink: (PerfRunEntity) -> Unit,
    /** Test seam: runs right after a callback's ownership pre-check, before it applies anything. */
    private val afterOwnershipCheck: () -> Unit = {},
) {
    /** What auto-record needs from the drive recorder. */
    interface Recorder {
        val isRecording: Boolean
        val isPaused: Boolean
        val activeDriveId: Long?
        fun pausedDurationMs(): Long
        fun resumeDrive()
        fun stopDrive()
        fun startDrive(sessionId: Long)
    }

    private var autoStarted = false

    /** A raw CAN frame from this job's connection. */
    fun onCanFrame(canId: Int, data: ByteArray, fps: Double) {
        if (!link.owns(gen)) return                                   // cheap pre-check; the real one is in deliver
        afterOwnershipCheck()
        var finishedRun: PerfRunEntity? = null
        link.deliver(gen) { finishedRun = processCanFrame(canId, data, fps) }
        finishedRun?.let(perfRunSink)                                 // blocking write, outside the lock
    }

    /**
     * Auto-record: on the first connected frame with the engine running, start / resume / finalize per
     * [AutoRecordPolicy]. Only once per connection job.
     */
    fun onVehicleState(vs: VehicleState, sessionId: () -> Long, resumeWindowMs: Long) {
        if (autoStarted || !link.owns(gen)) return
        if (!vs.isConnected) return
        if (vs.rpm <= AUTO_START_MIN_RPM) return
        afterOwnershipCheck()
        link.deliver(gen) {
            autoStarted = true
            when (AutoRecordPolicy.onEngineRunning(recorder.isRecording, recorder.isPaused,
                recorder.pausedDurationMs(), resumeWindowMs)) {
                AutoRecordPolicy.Action.KEEP -> Unit
                AutoRecordPolicy.Action.RESUME -> recorder.resumeDrive()
                AutoRecordPolicy.Action.FINALIZE_AND_START -> {
                    recorder.stopDrive()
                    recorder.startDrive(sessionId())
                }
                AutoRecordPolicy.Action.START -> recorder.startDrive(sessionId())
            }
        }
    }

    /** Applies one frame (caller holds ownership); returns a finished perf run to persist, if any. */
    private fun processCanFrame(canId: Int, data: ByteArray, fps: Double): PerfRunEntity? {
        if (DemoMode.isActive) return null   // late frame after demo took over
        onFrameCounted()
        // Side effects (FuelEconomy, PerformanceTimer, knock counter) must run
        // OUTSIDE the update{} lambda — MutableStateFlow.update uses a CAS retry
        // loop, so anything inside the lambda can execute more than once on
        // contention with concurrent OBD merges.
        var newState: VehicleState? = null
        state.update { current ->
            val decoded = CanDecoder.decode(canId, data, current)
            if (decoded === current) {
                // Recognized frame, nothing changed (0x040, 0x300, rejected 0x1A4): log it as
                // known and skip peaks/fps copy + emit (#189/#192). Checked before stamping,
                // which would otherwise make a new copy.
                val (desc, issue) = CanDecoder.describeUnchanged(canId, data)
                DiagnosticLogger.logFrame(canId, data, current, desc, issue)
                return@update current
            }
            // #194 — stamp CAN-sourced fields (≤1/s per ID) so THERMAL availability works
            val updated = decoded?.let { CanDecoder.stampFresh(canId, current, it, System.currentTimeMillis()) }
            if (updated != null) {
                val desc  = CanDecoder.describeDecoded(canId, updated)
                val issue = CanDecoder.validateDecoded(canId, updated)
                DiagnosticLogger.logFrame(canId, data, updated, desc, issue)
                val withPeaks = updated.withPeaksUpdated().copy(framesPerSecond = fps)
                newState = withPeaks
                withPeaks
            } else {
                DiagnosticLogger.logUnknownFrame(canId, data)
                current
            }
        }

        // Side effects — guaranteed to run exactly once after CAS succeeds
        val wp = newState ?: return null

        // Feed fuel economy calculator
        if (canId == 0x130 || canId == 0x380) {
            FuelEconomy.onUpdate(wp.fuelLevelPct, wp.speedKph)
        }

        // Feed performance timer (speed from 0x130, ~100 Hz)
        if (canId == 0x130) {
            val (sixty, hundred) = PerformanceTimer.onSpeedUpdate(wp.speedKph, wp.rpm, wp.boostPsi)
            if (hundred || (sixty && PerformanceTimer.state.value == PerformanceTimer.State.FINISHED)) {
                // Persist completed run to Room
                val result = PerformanceTimer.result.value
                if (result != null) {
                    return PerfRunEntity(
                        driveId = recorder.activeDriveId,
                        timestamp = System.currentTimeMillis(),
                        zeroTo60Ms = result.zeroTo60Ms,
                        zeroTo100Ms = result.zeroTo100Ms,
                        peakRpm = result.peakRpm,
                        peakBoostPsi = result.peakBoostPsi,
                        launchRpm = result.launchRpm,
                        ambientTempC = wp.ambientTempC
                    )
                }
            }
        }
        return null
    }

    companion object {
        /** RPM threshold that signals the engine is actually running (not just ignition). */
        const val AUTO_START_MIN_RPM = 400.0
    }
}
