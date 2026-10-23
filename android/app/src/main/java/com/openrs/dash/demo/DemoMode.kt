package com.openrs.dash.demo

import android.os.SystemClock
import com.openrs.dash.BuildConfig
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.can.BusyException
import com.openrs.dash.can.CanDecoder
import com.openrs.dash.can.FirmwareCommandSender
import com.openrs.dash.can.driveModePending
import com.openrs.dash.data.BoostSessionLog
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.DtcResult
import com.openrs.dash.data.DtcScanResult
import com.openrs.dash.data.DtcStatus
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.FuelEconomy
import com.openrs.dash.data.KnockCapture
import com.openrs.dash.data.KnockSessionLog
import com.openrs.dash.data.ModuleScanStatus
import com.openrs.dash.data.PerformanceTimer
import com.openrs.dash.data.VehicleState
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.diagnostics.DtcDatabase
import com.openrs.dash.diagnostics.DtcScanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * Debug-only demo mode: feeds [DemoSimulator] frames into the real
 * [OpenRSDashApp.vehicleState] so every screen renders through its normal code path.
 *
 * Entry/exit goes through CanDataService.enterDemoMode()/exitDemoMode(), which stop real
 * adapter traffic first and require an explicit reconnect afterwards. While active:
 *   - drive mode / ESC commands go to [DemoFirmwareApi] (simulated state only)
 *   - DTC scan/clear and DID probes are answered here, never by an ECU
 *   - DriveRecorder, perf-run, session-snapshot and crash-telemetry writes are skipped
 */
object DemoMode {

    /** Demo mode only exists in debug builds. */
    val AVAILABLE: Boolean = BuildConfig.DEBUG

    data class Status(
        val active: Boolean = false,
        val scenario: DemoScenario = DemoScenario.DRIVING,
        val paused: Boolean = false,
        val elapsedMs: Long = 0L,
        val link: DemoLink = DemoLink.LIVE,
    )

    private val _status = MutableStateFlow(Status())
    val status: StateFlow<Status> = _status.asStateFlow()
    val isActive: Boolean get() = _status.value.active

    /**
     * Set by MainActivity when launched with a demo extra, before the service exists, so the
     * service's startup auto-connect never opens the adapter. Cleared by [start].
     */
    @Volatile var launchRequested: Boolean = false

    /**
     * Bumped on demo start, scenario reset/switch and exit. UI-held histories (sparklines,
     * G-force trail) key their `remember` on it so simulated traces never outlive the run.
     */
    /**
     * Demo control strip collapsed to the header DEMO pill (UI preference for the process, kept
     * across demo runs) — design reviews capture screens without the 48 dp amber toolbar.
     */
    private val _controlsCollapsed = MutableStateFlow(false)
    val controlsCollapsed: StateFlow<Boolean> = _controlsCollapsed.asStateFlow()
    fun setControlsCollapsed(collapsed: Boolean) { _controlsCollapsed.value = collapsed }
    fun toggleControls() { _controlsCollapsed.update { !it } }

    private val _sessionEpoch = MutableStateFlow(0)
    val sessionEpoch: StateFlow<Int> = _sessionEpoch.asStateFlow()

    private const val TICK_MS = 40L          // 25 Hz UI feed
    private const val STAMP_MS = 500L        // freshness stamps + OBD-rate side effects

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var loopJob: Job? = null
    private var modeJob: Job? = null
    private val simTimeMs = AtomicLong(0L)
    @Volatile private var pauseAtMs = -1L
    private var lastStampMs = 0L
    private var lastKrCountedMs = 0L

    @Volatile private var simDriveMode = DriveMode.NORMAL
    @Volatile private var simEsc = EscStatus.ON
    @Volatile private var simDtcs: List<DtcResult> = emptyList()

    // ── Lifecycle (called by CanDataService) ─────────────────────────────────

    @Synchronized fun start(scenario: DemoScenario) {
        launchRequested = false
        if (!AVAILABLE || isActive) return
        simDriveMode = DriveMode.NORMAL
        simEsc = EscStatus.ON
        clearSessionState()
        OpenRSDashApp.instance.isOpenRsFirmware.value = true
        OpenRSDashApp.instance.firmwareVersionLabel.value = "DEMO (simulated)"
        _status.value = Status(active = true, scenario = scenario)
        restartScenario(scenario)
        DiagnosticLogger.event("DEMO", "start ${scenario.name}")
        loopJob = scope.launch { runLoop() }
    }

    /** Stops the simulator and clears every simulated value; leaves the app disconnected. */
    @Synchronized fun stop() {
        if (!isActive) return
        // Flag first: an in-flight tick's update{} sees inactive on CAS retry and leaves state alone.
        _status.value = Status(active = false)
        loopJob?.cancel(); loopJob = null
        modeJob?.cancel(); modeJob = null
        clearSessionState()
        _sessionEpoch.update { it + 1 }
        OpenRSDashApp.instance.isOpenRsFirmware.value = false
        OpenRSDashApp.instance.firmwareVersionLabel.value = ""
        DiagnosticLogger.event("DEMO", "stop")
    }

    fun select(scenario: DemoScenario) {
        if (!isActive) return
        _status.update { it.copy(scenario = scenario, paused = false) }
        restartScenario(scenario)
    }

    fun togglePause() {
        if (!isActive) return
        _status.update { it.copy(paused = !it.paused) }
    }

    /**
     * Replays the current scenario from t = 0 and pauses exactly at [tMs] — repeatable frames
     * (with real history behind sparklines) for before/after screenshots.
     */
    fun playTo(tMs: Long) {
        if (!isActive) return
        _status.update { it.copy(paused = false) }
        restartScenario(_status.value.scenario)
        pauseAtMs = tMs
    }

    /** Replays the current scenario from t = 0 with fresh peaks / session logs. */
    fun reset() {
        if (!isActive) return
        restartScenario(_status.value.scenario)
    }

    private fun restartScenario(scenario: DemoScenario) {
        simTimeMs.set(0L)
        pauseAtMs = -1L
        lastStampMs = 0L
        modeJob?.cancel(); modeJob = null
        driveModePending.value = null
        simDtcs = dtcsFor(scenario)
        OpenRSDashApp.instance.knockEventCount.value = 0
        OpenRSDashApp.instance.activeDtcCount.value = 0
        DiagnosticLogger.lastDtcResults = null
        KnockSessionLog.reset()
        BoostSessionLog.reset()
        FuelEconomy.reset()
        PerformanceTimer.resetSession()
        _status.update { it.copy(elapsedMs = 0L) }
        OpenRSDashApp.instance.vehicleState.value = VehicleState(isConnected = true)
        _sessionEpoch.update { it + 1 }
    }

    /** Clears simulated values from every app-level holder the simulator feeds. */
    private fun clearSessionState() {
        driveModePending.value = null
        simDtcs = emptyList()
        CanDecoder.resetSessionState()
        OpenRSDashApp.instance.vehicleState.value = VehicleState()
        OpenRSDashApp.instance.knockEventCount.value = 0
        OpenRSDashApp.instance.activeDtcCount.value = 0
        OpenRSDashApp.instance.deviceStatus.value = null
        DiagnosticLogger.lastDtcResults = null
        KnockSessionLog.reset()
        BoostSessionLog.reset()
        FuelEconomy.reset()
        PerformanceTimer.resetSession()
    }

    // ── Tick loop ────────────────────────────────────────────────────────────

    private suspend fun runLoop() {
        var last = SystemClock.elapsedRealtime()
        while (true) {
            delay(TICK_MS)   // cancellation point — stop() cancels loopJob
            val mono = SystemClock.elapsedRealtime()
            val dt = mono - last
            last = mono
            val st = _status.value
            if (!st.active) return
            var t = if (st.paused) simTimeMs.get() else simTimeMs.addAndGet(dt)
            val stopAt = pauseAtMs
            if (!st.paused && stopAt >= 0 && t >= stopAt) {
                t = stopAt; simTimeMs.set(stopAt); pauseAtMs = -1L
                _status.update { it.copy(paused = true) }
            }
            tick(st.scenario, t, st.paused)
        }
    }

    private fun tick(scenario: DemoScenario, tMs: Long, paused: Boolean) {
        val now = System.currentTimeMillis()
        val stamp = now - lastStampMs >= STAMP_MS
        if (stamp) lastStampMs = now
        val app = OpenRSDashApp.instance
        app.vehicleState.update { cur ->
            if (!isActive) cur
            else DemoSimulator.apply(cur, scenario, tMs, simDriveMode, simEsc, now, stamp)
        }
        val link = DemoSimulator.link(scenario, tMs / 1000.0)
        _status.update { if (it.active) it.copy(elapsedMs = tMs, link = link) else it }
        if (paused || link == DemoLink.LOST || !isActive) return

        // Same side effects CanDataService runs for real frames — in-memory only, cleared on exit.
        val vs = app.vehicleState.value
        FuelEconomy.onUpdate(vs.fuelLevelPct, vs.speedKph)
        PerformanceTimer.onSpeedUpdate(vs.speedKph, vs.rpm, vs.boostPsi)
        if (stamp && link == DemoLink.LIVE) {
            KnockSessionLog.onState(vs, now)
            BoostSessionLog.onState(vs, now)
            val krStamp = KnockCapture.KR_TAGS.maxOf { vs.fieldLastUpdateMs[it] ?: 0L }
            if (krStamp > lastKrCountedMs) {
                lastKrCountedMs = krStamp
                val kr = maxOf(vs.ignCorrCyl1, vs.ignCorrCyl2, vs.ignCorrCyl3, vs.ignCorrCyl4)
                if (kr > 1.0) app.knockEventCount.update { it + 1 }
            }
        }
    }

    // ── Simulated vehicle commands ───────────────────────────────────────────

    private fun linkUp(): Boolean = isActive && _status.value.link != DemoLink.LOST

    /** Mirrors firmware: OK immediately, mode lands after the selector's auto-confirm delay. */
    internal fun requestDriveMode(target: DriveMode): Result<Unit> {
        if (!linkUp()) return Result.failure(RuntimeException("Adapter offline (demo)"))
        if (modeJob?.isActive == true) return Result.failure(BusyException())
        modeJob = scope.launch {
            delay(1_500L)
            if (_status.value.active) simDriveMode = target
        }
        return Result.success(Unit)
    }

    internal fun requestEsc(target: EscStatus): Result<Unit> {
        if (!linkUp()) return Result.failure(RuntimeException("Adapter offline (demo)"))
        simEsc = target
        return Result.success(Unit)
    }

    private fun dtcsFor(scenario: DemoScenario): List<DtcResult> {
        fun dtc(module: String, code: String, status: DtcStatus) =
            DtcResult(module, code, DtcDatabase.describe(code).ifEmpty { "Simulated fault" }, status)
        return when (scenario) {
            DemoScenario.WARNING -> listOf(
                // Codes from the bundled DtcDatabase matching the WARN symptoms
                dtc("PCM", "P0217", DtcStatus.ACTIVE),    // coolant overtemp
                dtc("PCM", "P0171", DtcStatus.ACTIVE),    // lean under load
                dtc("PCM", "P0325", DtcStatus.PENDING),   // knock sensor
                dtc("PCM", "P0562", DtcStatus.ACTIVE),    // system voltage low
            )
            DemoScenario.CONNECTION_LOSS -> listOf(dtc("ABS", "U0100", DtcStatus.PENDING))
            else -> emptyList()
        }
    }

    suspend fun scanDtcs(onModuleProgress: ((String, ModuleScanStatus?) -> Unit)?): DtcScanResult {
        val startMs = SystemClock.elapsedRealtime()
        val up = linkUp()
        val statuses = LinkedHashMap<String, ModuleScanStatus>()
        for (m in DtcScanner.MODULES) {
            onModuleProgress?.invoke(m.name, null)
            delay(if (up) 180L else 600L)
            val s = if (up) ModuleScanStatus.OK else ModuleScanStatus.TIMEOUT
            statuses[m.name] = s
            onModuleProgress?.invoke(m.name, s)
        }
        val codes = if (up) simDtcs else emptyList()
        return DtcScanResult(codes, statuses, SystemClock.elapsedRealtime() - startMs)
    }

    suspend fun retryScanModule(moduleName: String): Pair<List<DtcResult>, ModuleScanStatus> {
        delay(300L)
        return if (linkUp()) simDtcs.filter { it.module == moduleName } to ModuleScanStatus.OK
               else emptyList<DtcResult>() to ModuleScanStatus.TIMEOUT
    }

    suspend fun clearDtcs(): Map<String, Boolean> {
        delay(800L)
        if (!linkUp()) return emptyMap()
        simDtcs = emptyList()
        return DtcScanner.MODULES.associate { it.name to true }
    }
}

/** Drive-mode / ESC sender used while demo mode is active — never touches the adapter. */
object DemoFirmwareApi : FirmwareCommandSender {
    override suspend fun setDriveMode(mode: Int): Result<Unit> = DemoMode.requestDriveMode(
        when (mode) { 1 -> DriveMode.SPORT; 2 -> DriveMode.DRIFT; 3 -> DriveMode.TRACK; else -> DriveMode.NORMAL }
    )

    override suspend fun setEscMode(mode: Int): Result<Unit> = DemoMode.requestEsc(
        when (mode) { 1 -> EscStatus.PARTIAL; 2 -> EscStatus.OFF; else -> EscStatus.ON }
    )
}
