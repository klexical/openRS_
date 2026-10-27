package com.openrs.dash.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.ui.draw.drawBehind
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.R
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.demo.DemoMode
import com.openrs.dash.demo.DemoScenario
import com.openrs.dash.service.CanDataService
import com.openrs.dash.ui.anim.EdgeShiftLight
import com.openrs.dash.ui.anim.LaunchControlEdgeGlow
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.anim.bloomGlow
import com.openrs.dash.ui.anim.connectionPulse

import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource
import com.openrs.dash.ui.anim.pressClick
import com.openrs.dash.ui.trip.TripPage
import com.openrs.dash.update.UpdateManager
import kotlinx.coroutines.launch
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.ui.platform.LocalContext

// ═══════════════════════════════════════════════════════════════════════════
// ACTIVITY
// ═══════════════════════════════════════════════════════════════════════════
class MainActivity : ComponentActivity() {
    private var service: CanDataService? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            service = (b as CanDataService.LocalBinder).getService()
            pendingDemo?.let { applyDemoExtra(it) }
            pendingDemo = null
        }
        override fun onServiceDisconnected(n: ComponentName) { service = null }
    }

    /**
     * Debug builds: `--es demo idle|driving|warning|loss|off` on the launch intent enters,
     * switches or leaves demo mode (root CLAUDE.md → Build). Held until the service binds.
     * `at:<seconds>` replays the current scenario and pauses at that time (screenshot frames);
     * `collapse` / `expand` fold the control strip into the header DEMO pill.
     */
    private var pendingDemo: String? = null

    private fun handleDemoIntent(intent: Intent?) {
        if (!DemoMode.AVAILABLE) return
        val arg = intent?.getStringExtra("demo")?.lowercase() ?: return
        intent.removeExtra("demo")
        if (service == null) {
            pendingDemo = arg
            // Service not bound yet — keep its startup auto-connect off the adapter.
            if (arg !in setOf("off", "exit", "pause", "reset", "collapse", "expand") && !arg.startsWith("at:"))
                DemoMode.launchRequested = true
        } else applyDemoExtra(arg)
    }

    private fun applyDemoExtra(arg: String) {
        val svc = service ?: return
        if (arg.startsWith("at:")) {
            arg.removePrefix("at:").toDoubleOrNull()?.let { DemoMode.playTo((it * 1000).toLong()) }
            return
        }
        val scenario = when (arg) {
            "idle"                -> DemoScenario.IDLE
            "driving", "drive"    -> DemoScenario.DRIVING
            "warning", "warn"     -> DemoScenario.WARNING
            "loss", "connection"  -> DemoScenario.CONNECTION_LOSS
            "cooldown", "cool"    -> DemoScenario.COOLDOWN
            "launch", "lc"        -> DemoScenario.LAUNCH
            "pause"               -> { DemoMode.togglePause(); return }
            "reset"               -> { DemoMode.reset(); return }
            "off", "exit"         -> { svc.exitDemoMode(); return }
            "collapse"            -> { DemoMode.setControlsCollapsed(true); return }
            "expand"              -> { DemoMode.setControlsCollapsed(false); return }
            else                  -> {
                DemoMode.launchRequested = false
                android.util.Log.w("DEMO", "Unknown demo extra '$arg'"); return
            }
        }
        svc.enterDemoMode(scenario)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDemoIntent(intent)
        handleTripPreviewIntent(intent)
    }

    /** Debug builds: `--es trippreview history|selected|recording|paused|summary|off` — TRIP sample states (TripPreview). */
    private fun handleTripPreviewIntent(intent: Intent?) {
        if (!com.openrs.dash.demo.TripPreview.AVAILABLE) return
        val arg = intent?.getStringExtra("trippreview")?.lowercase() ?: return
        intent.removeExtra("trippreview")
        if (!com.openrs.dash.demo.TripPreview.apply(arg)) android.util.Log.w("DEMO", "Unknown trippreview extra '$arg'")
    }
    // Lint's fragment-version check targets FragmentActivity (request-code collisions with
    // fragment < 1.3.0). MainActivity is a ComponentActivity, so it doesn't apply.
    @SuppressLint("InvalidFragmentVersionForActivityResult")
    private val permLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { startSvc() }

    /** Re-applies edge-to-edge with status/nav bar icon tint matching the current ThemeMode. */
    private fun applySystemBarStyle() {
        val transparent = android.graphics.Color.TRANSPARENT
        val style = if (isDayModeNow()) {
            androidx.activity.SystemBarStyle.light(transparent, transparent)
        } else {
            androidx.activity.SystemBarStyle.dark(transparent)
        }
        enableEdgeToEdge(statusBarStyle = style, navigationBarStyle = style)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        UserPrefsStore.load(this)
        setThemeMode(runCatching { ThemeMode.valueOf(AppSettings.getThemeMode(this)) }
            .getOrDefault(ThemeMode.NIGHT))
        applySystemBarStyle()
        setClassicFonts(AppSettings.getClassicFonts(this))

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        // Request location at startup for drive recording. Android 12+ requires COARSE
        // alongside FINE in the same request (lint CoarseFineLocation).
        perms.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        // BLE permissions when Bluetooth connection method is selected
        if (AppSettings.getConnectionMethod(this) == "BLUETOOTH") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        permLauncher.launch(perms.toTypedArray())
        handleDemoIntent(intent)
        handleTripPreviewIntent(intent)
        // Opening presentation on a cold start only (not after rotation / process recreation)
        val coldStart = savedInstanceState == null

        setContent {
            val vs          by OpenRSDashApp.instance.vehicleState.collectAsState()
            val prefs       by UserPrefsStore.prefs.collectAsState()
            val debugLines  by OpenRSDashApp.instance.debugLines.collectAsState()
            val driveState  by OpenRSDashApp.instance.driveState.collectAsState()
            // Debug TRIP preview: the header's REC / PAUSED indicator shows the sample recording too (nothing records)
            val tripPreview by com.openrs.dash.demo.TripPreview.mode.collectAsState()
            val headerDriveState = remember(tripPreview) {
                when (tripPreview) {
                    com.openrs.dash.demo.TripPreview.Mode.RECORDING -> com.openrs.dash.demo.TripPreview.driveState(paused = false)
                    com.openrs.dash.demo.TripPreview.Mode.PAUSED -> com.openrs.dash.demo.TripPreview.driveState(paused = true)
                    else -> null
                }
            } ?: driveState
            val pagerState  = rememberPagerState(pageCount = { 5 })
            val selectedTab by remember { derivedStateOf { pagerState.currentPage } }
            var mapTouched  by remember { mutableStateOf(false) }
            val hazeState   = remember { HazeState() }
            var settingsOpen    by remember { mutableStateOf(false) }
            var showCustomDash  by remember { mutableStateOf(false) }
            var dockOpen        by remember { mutableStateOf(false) }
            val isFw            by OpenRSDashApp.instance.isOpenRsFirmware.collectAsState()
            val demo            by DemoMode.status.collectAsState()
            val demoCollapsed   by DemoMode.controlsCollapsed.collectAsState()
            val pageScope       = rememberCoroutineScope()
            val snackbarHostState = remember { SnackbarHostState() }


            // What's New — show once after version update
            val whatsNewCtx = LocalContext.current
            var showWhatsNew by remember {
                val lastSeen = AppSettings.getLastSeenVersion(whatsNewCtx)
                val current = com.openrs.dash.BuildConfig.VERSION_NAME
                mutableStateOf(lastSeen != current)
            }

            // Background update check — silent, non-intrusive
            LaunchedEffect(Unit) {
                UpdateManager.cleanupOldDownloads(whatsNewCtx)
                UpdateManager.checkForUpdate(
                    whatsNewCtx,
                    channel = prefs.updateChannel,
                    silent = true
                )
            }

            val view = LocalView.current
            LaunchedEffect(prefs.screenOn) {
                view.keepScreenOn = prefs.screenOn
            }

            // Apply theme mode + classic fonts toggle to theme system
            LaunchedEffect(prefs.themeMode) {
                setThemeMode(runCatching { ThemeMode.valueOf(prefs.themeMode) }
                    .getOrDefault(ThemeMode.NIGHT))
                applySystemBarStyle()
            }
            LaunchedEffect(prefs.classicFonts) {
                setClassicFonts(prefs.classicFonts)
            }

            // Frozen White on DAY / Shadow Black on NIGHT swap to their legible structural tone.
            val activeAccent = legibleAccent(themeAccentPreview() ?: prefs.themeAccent, isDayModeNow())
            CompositionLocalProvider(LocalThemeAccent provides activeAccent) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Bg,
                        surface    = Surf,
                        primary    = activeAccent
                    )
                ) {
                    Box(Modifier.fillMaxSize()) {
                        Scaffold(
                            snackbarHost   = { SnackbarHost(snackbarHostState) },
                            containerColor = Bg,
                            contentWindowInsets = WindowInsets.statusBars
                        ) { innerPadding ->
                            Box(
                                Modifier.fillMaxSize()
                                    .padding(innerPadding)
                                    .background(Bg)
                                    .hazeSource(hazeState)
                            ) {
                                Column(Modifier.fillMaxSize()) {
                                    AppHeader(
                                        vs           = vs,
                                        prefs        = prefs,
                                        onSettings   = { settingsOpen = true },
                                        onConnect    = { service?.startConnection() },
                                        onDisconnect = { service?.stopConnection() },
                                        onReconnect  = { service?.reconnect() },
                                        driveState   = headerDriveState,
                                        onModeClick  = { dockOpen = !dockOpen },
                                        onPullDown   = { dockOpen = true },
                                        demoActive   = demo.active
                                    )

                                    if (demo.active && demoCollapsed) {
                                        // Collapsed: a hairline keeps "simulated" visible under the header;
                                        // the header DEMO pill expands the strip again.
                                        Box(Modifier.fillMaxWidth().height(2.dp).background(Warn.copy(alpha = 0.7f)))
                                    } else if (demo.active) {
                                        DemoControlStrip(
                                            onCollapse    = { DemoMode.setControlsCollapsed(true) },
                                            status        = demo,
                                            onScenario    = { service?.enterDemoMode(it) },
                                            onTogglePause = { DemoMode.togglePause() },
                                            onReset       = { DemoMode.reset() },
                                            onExit        = { service?.exitDemoMode() }
                                        )
                                    }

                                    // rc.2: anomaly surface sits below the header so it can't steal the
                                    // MODE/ESC pills. Priority: e-brake > LC engaged > update.
                                    AnomalyStrip(vs)

                                    // ── Quick Mode Dock ──────────────────
                                    AnimatedVisibility(
                                        visible = dockOpen,
                                        enter = expandVertically(
                                            animationSpec = spring(
                                                dampingRatio = Spring.DampingRatioMediumBouncy,
                                                stiffness = Spring.StiffnessMediumLow
                                            )
                                        ) + fadeIn(),
                                        exit = shrinkVertically(tween(200)) + fadeOut(tween(200))
                                    ) {
                                        DriveModeDock(
                                            vs = vs,
                                            canControl = isFw && vs.isConnected,
                                            firmwareApi = service?.firmwareApi,
                                            snackbarHostState = snackbarHostState,
                                            onDismiss = { dockOpen = false }
                                        )
                                    }

                                    // Back closes an open dock (like the scrim) and stays on this screen; the
                                    // mode is untouched. Disabled while closed, so Back works as usual again.
                                    BackHandler(enabled = dockOpen) { dockOpen = false }

                                    WifiCoexistenceBanner()
                                    // Auto-dismiss dock on tab change
                                    LaunchedEffect(selectedTab) { dockOpen = false }

                                    // TRIP on screen at all (settled or mid-swipe) — pinned off-screen pages don't count
                                    val tripVisible by remember {
                                        derivedStateOf { pagerState.layoutInfo.visiblePagesInfo.any { it.index == 3 } }
                                    }
                                    val pagerScrollEnabled by remember {
                                        derivedStateOf { !(selectedTab == 3 && mapTouched) }
                                    }
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.weight(1f),
                                        beyondViewportPageCount = 1,
                                        userScrollEnabled = pagerScrollEnabled, // disable pager swipe while touching map
                                        key = { it }
                                    ) { page ->
                                        // C4: crossfade content as pages settle.
                                        // currentPageOffsetFraction ∈ [-0.5, 0.5]; peak centre fades to full alpha.
                                        val pageAlpha = 1f - (kotlin.math.abs(
                                            (pagerState.currentPage - page) + pagerState.currentPageOffsetFraction
                                        ).coerceIn(0f, 1f) * 0.6f)
                                        Box(
                                            Modifier.fillMaxSize()
                                                .graphicsLayer { alpha = pageAlpha }
                                                .then(connectionPulse(vs.isConnected))
                                        ) {
                                            when (page) {
                                                0 -> DrivePage(vs, prefs, onOpenThermal = {
                                                    pageScope.launch { pagerState.animateScrollToPage(2) }
                                                }, onConnect = { service?.startConnection() })
                                                1 -> PerfPage(vs, prefs, onReset = { service?.resetPeaks() })
                                                2 -> TempsPage(vs, prefs)
                                                // settled: TRIP creates its map on the first visit; visible: off screen
                                                // the retained map is stopped and TRIP's inputs are held (TripPage)
                                                3 -> TripPage(driveState, vs, prefs, onMapTouched = { mapTouched = it },
                                                    settled = pagerState.settledPage == 3, visible = tripVisible)
                                                4 -> GaragePage(
                                                    debugLines = debugLines,
                                                    vs = vs,
                                                    p = prefs,
                                                    snackbarHostState = snackbarHostState,
                                                    firmwareApi = service?.firmwareApi,
                                                    onScanDtcs = service?.let { svc ->
                                                        val fn: suspend (com.openrs.dash.data.DtcProgressCallback?) -> com.openrs.dash.data.DtcScanResult =
                                                            { progress -> svc.scanDtcs(progress) }
                                                        fn
                                                    },
                                                    onClearDtcs = service?.let { svc ->
                                                        val fn: suspend () -> Map<String, Boolean> = { svc.clearDtcs() }
                                                        fn
                                                    },
                                                    onRetryScanModule = service?.let { svc ->
                                                        val fn: suspend (String) -> Pair<List<com.openrs.dash.data.DtcResult>, com.openrs.dash.data.ModuleScanStatus> =
                                                            { moduleName -> svc.retryScanModule(moduleName) }
                                                        fn
                                                    },
                                                    onFetchFreezeFrames = service?.let { svc ->
                                                        val fn: suspend (List<com.openrs.dash.data.DtcResult>) -> List<com.openrs.dash.data.DtcResult> =
                                                            { codes -> svc.fetchFreezeFrames(codes) }
                                                        fn
                                                    },
                                                    onSendRawQuery = service?.let { svc ->
                                                        val q: suspend (Int, String, Long) -> ByteArray? =
                                                            { r, f, t -> svc.sendRawQuery(r, f, t) }
                                                        q
                                                    },
                                                    onResetSession = { service?.resetSession() },
                                                    onOpenDock = { dockOpen = true },
                                                )
                                            }
                                            // Scrim overlay — tap to dismiss dock
                                            if (dockOpen) {
                                                Box(
                                                    Modifier.fillMaxSize()
                                                        .background(Color.Black.copy(alpha = 0.12f))
                                                        .clickable(
                                                            interactionSource = remember { MutableInteractionSource() },
                                                            indication = null
                                                        ) { dockOpen = false }
                                                )
                                            }
                                        }
                                    }
                                }

                                if (settingsOpen) {
                                    SettingsDialog(
                                        onDismiss = { settingsOpen = false },
                                        onCustomDash = { settingsOpen = false; showCustomDash = true },
                                        onStartDemo = { service?.enterDemoMode(it) },
                                        onExitDemo = { service?.exitDemoMode() }
                                    )
                                }

                                if (showWhatsNew) {
                                    WhatsNewDialog(onDismiss = {
                                        showWhatsNew = false
                                        AppSettings.setLastSeenVersion(whatsNewCtx, com.openrs.dash.BuildConfig.VERSION_NAME)
                                    })
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = showCustomDash,
                            enter   = slideInVertically(initialOffsetY = { it }),
                            exit    = slideOutVertically(targetOffsetY = { it })
                        ) {
                            CustomDashPage(
                                vehicleState = vs,
                                prefs        = prefs,
                                onDismiss    = { showCustomDash = false }
                            )
                        }

                        EdgeShiftLight(
                            // Last RPM freezes on an adapter drop — don't keep flashing on it.
                            rpm       = if (vs.isConnected) vs.rpm.toFloat() else 0f,
                            shiftRpm  = prefs.edgeShiftRpm.toFloat(),
                            enabled   = prefs.edgeShiftLight,
                            colorMode = prefs.edgeShiftColor,
                            intensity = when (prefs.edgeShiftIntensity) {
                                "low" -> 0.3f; "med" -> 0.65f; else -> 1.0f
                            }
                        )

                        // rc.2: peripheral glow while LC is actively engaged — unmissable in-cabin.
                        LaunchControlEdgeGlow(engaged = vs.isConnected && vs.launchControlEngaged)

                        // ── Bottom Nav Bar (overlay — content extends behind) ──
                        val navScope = rememberCoroutineScope()
                        val onSelectNav = remember<(Int) -> Unit> {
                            { page -> navScope.launch { pagerState.animateScrollToPage(page) } }
                        }
                        val activeDtcs by OpenRSDashApp.instance.activeDtcCount.collectAsState()
                        // Hidden under the full-screen Custom Dashboard: its tabs would switch pages out of sight.
                        if (!showCustomDash) BottomNavBar(
                            selected = selectedTab,
                            onSelect = onSelectNav,
                            hazeState = hazeState,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            badges = listOf(false, false, false, false, activeDtcs > 0),
                            navTabIdentity = prefs.navTabIdentity,
                        )

                    }
                }
                // Wordmark, tagline and front-view RS over the first frame, then a fade into the dashboard
                var opening by rememberSaveable { mutableStateOf(coldStart) }
                if (opening) OpeningPresentation { opening = false }
            }
        }
    }

    /**
     * Bind only. The service promotes itself to a started foreground service while a connection runs
     * (CanDataService → ForegroundController) and drops back when it ends, so it survives this activity
     * only when there is something to keep alive. Calling startForegroundService() here on every onCreate
     * broke Android's contract whenever no connection started (offline / demo) → ANR.
     */
    private fun startSvc() {
        bindService(Intent(this, CanDataService::class.java), conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// HEADER — v2.2.7-rc.2
//
// Geometry:
//   [logo] · · · [MODE pill] [ESC pill] · · · [REC | connPill | cog]
//
// MODE/ESC are always visible when connected; disconnected renders dim
// placeholder pills + "TAP TO CONNECT" gets surfaced in the AnomalyStrip
// below the header. Anomalies (e-brake / LC engaged / update / reconnect)
// slide down into AnomalyStrip — the header itself never changes shape.
//
// Pulling down ≥40dp on the header opens the DriveModeDock from any tab.
// ═══════════════════════════════════════════════════════════════════════════

@Composable fun AppHeader(
    vs: VehicleState,
    prefs: UserPrefs,
    onSettings: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    driveState: com.openrs.dash.data.DriveState = com.openrs.dash.data.DriveState(),
    onModeClick: () -> Unit = {},
    onPullDown: () -> Unit = {},
    demoActive: Boolean = false
) {
    val accent = LocalThemeAccent.current

    val dotAlpha = if (vs.isConnected) {
        val infiniteTransition = rememberInfiniteTransition(label = "conn")
        val anim by infiniteTransition.animateFloat(
            initialValue = 1f, targetValue = 0.3f, label = "dot",
            animationSpec = infiniteRepeatable(tween(1000, easing = EaseInOut), RepeatMode.Reverse)
        )
        anim
    } else 1f
    val updateStateNow by UpdateManager.state.collectAsState()
    val isUpdating = updateStateNow is com.openrs.dash.update.UpdateState.Downloading
    val isReconnecting = !vs.isConnected && vs.isIdle
    val connColor = when {
        demoActive && vs.isConnected -> demoInk()   // simulated link up; LOSS phase shows real RCN
        vs.isConnected -> Ok
        vs.isIdle      -> Warn
        else           -> Mid      // OFF: not an alarm — TAP TO CONNECT (accent) is the call to action
    }
    // Pill text: ink when live, the state colour only for degraded / simulated links
    val connInk = when {
        demoActive     -> demoInk()
        vs.isConnected -> Frost
        else           -> connColor
    }
    val linkLabel = when {
        isUpdating     -> "UPD"
        vs.isConnected -> "LIVE"
        isReconnecting -> "RCN"
        vs.isIdle      -> "IDLE"
        else           -> "OFF"
    }
    // Simulated data is always labelled: in demo the pill keeps DEMO and adds the (simulated) link
    // state, e.g. "DEMO · RCN" during the connection-loss scenario — never RCN alone.
    val connLabel = when {
        demoActive && vs.isConnected -> "DEMO"
        demoActive                   -> "DEMO · $linkLabel"
        else                         -> linkLabel
    }

    // Bloom intensity fades 0.3→0 over 400ms on disconnect (instead of popping off).
    val bloomIntensity by animateFloatAsState(
        if (vs.isConnected) 0.3f else 0f, tween(400), label = "bloomI")

    // Connected-quiet: pill fades to 0.6α after 2s stable, restores on state change.
    var connStable by remember { mutableStateOf(false) }
    LaunchedEffect(vs.isConnected) {
        connStable = false
        if (vs.isConnected) { kotlinx.coroutines.delay(2000); connStable = true }
    }
    val quietAlpha by animateFloatAsState(
        if (vs.isConnected && connStable && prefs.livePillQuiet && !demoActive) 0.6f else 1f,
        tween(600), label = "pillQuiet")

    val haptic = LocalHapticFeedback.current

    // Disconnect confirmation: Cancel, Back and tapping outside all just close it.
    var confirmDisconnect by remember { mutableStateOf(false) }
    // A drop or demo start while the dialog is open makes it moot.
    LaunchedEffect(vs.isConnected, demoActive) { if (!vs.isConnected || demoActive) confirmDisconnect = false }
    if (confirmDisconnect && vs.isConnected && !demoActive) {
        AlertDialog(
            onDismissRequest = { confirmDisconnect = false },
            containerColor = Surf,
            title = { UIText("Disconnect from vehicle?", 16.sp, Frost, FontWeight.Bold) },
            text = { MonoLabel("Live data stops until you connect again.", 11.sp, Mid) },
            dismissButton = {
                TextButton(onClick = { confirmDisconnect = false }, modifier = Modifier.heightIn(min = 48.dp)) {
                    MonoLabel("CANCEL", 12.sp, Mid, FontWeight.Bold, 0.5.sp)
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmDisconnect = false
                        haptic.performHapticFeedback(HapticFeedbackType.Reject)
                        onDisconnect()
                    },
                    modifier = Modifier.heightIn(min = 48.dp)
                ) {
                    MonoLabel("DISCONNECT", 12.sp, Orange, FontWeight.Bold, 0.5.sp)
                }
            }
        )
    }

    // rc.2: header underline stays a constant hairline — dock drops right
    // below it, so flashing it mode-color on mode-change reads as a stray
    // line. Mode-color breath is carried by the bottom nav indicator only.
    val underlineColor = Brd.copy(alpha = borderAlpha(0.3f))
    val underlineStroke = 1.dp

    // Pull-down gesture: accumulate drag distance; open dock on ≥40dp.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var dragAccum by remember { mutableStateOf(0f) }
    val dragState = rememberDraggableState { delta -> dragAccum += delta }

    // Narrow phones (< 400 dp) drop the trip distance from the REC label so the row never wraps.
    BoxWithConstraints(Modifier.fillMaxWidth()) {
    val compact = maxWidth < 400.dp
    Row(
        Modifier.fillMaxWidth()
            .height(Tokens.StatusBarHeight)
            .background(Surf)
            .draggable(
                state = dragState,
                orientation = Orientation.Vertical,
                onDragStarted = { dragAccum = 0f },
                onDragStopped = {
                    val thresholdPx = with(density) { 40.dp.toPx() }
                    if (dragAccum > thresholdPx) onPullDown()
                    dragAccum = 0f
                }
            )
            .drawBehind {
                drawLine(
                    color = underlineColor,
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = underlineStroke.toPx()
                )
            }
            // rc.2: asymmetric horizontal padding — keep the logo's breathing
            // room on the left but pull the REC/conn/cog cluster hard against
            // the right edge so it reads as a tight trio (was 4dp; dropped
            // to 0dp per user feedback that the cluster still looked floated).
            .padding(start = 10.dp, end = 0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Left: Logo ──────────────────────────────────────────
        // Brand colors: "open" + "_" = Frost, "RS" = Nitrous Blue (accent)
        val rsBlue = accent
        val connected = vs.isConnected

        // (3) Startup typewriter — letters reveal one by one on first composition
        var typewriterDone by remember { mutableStateOf(false) }
        val typewriterPhase by animateFloatAsState(
            targetValue = if (typewriterDone) 1f else 0f,
            animationSpec = tween(1800, easing = EaseOut),
            label = "typewriter"
        )
        LaunchedEffect(Unit) { typewriterDone = true }
        // 7 glyphs of the approved wordmark: o-p-e-n-R-S-_
        val glyphCount = WORDMARK_GLYPHS.size

        // (1) Underscore cursor blink — slow terminal pulse
        val cursorAlpha by rememberInfiniteTransition(label = "cursor").animateFloat(
            initialValue = 1f, targetValue = 0.15f,
            animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
            label = "cursorA"
        )

        // (2) Connection-aware glow on "RS" — breathes when live, dormant when offline
        val glowAlpha by rememberInfiniteTransition(label = "rsGlow").animateFloat(
            initialValue = 0.08f, targetValue = 0.25f,
            animationSpec = infiniteRepeatable(tween(2200, easing = EaseInOut), RepeatMode.Reverse),
            label = "rsGlowA"
        )
        val rsGlowActive by animateFloatAsState(
            targetValue = if (connected) 1f else 0f,
            animationSpec = tween(600),
            label = "rsGlowOn"
        )

        // E-brake / launch control take the wordmark's fixed slot while active (every tab, nothing below moves):
        // only live flags — held values from a dropped link are not shown.
        val ebrakeOn = connected && vs.eBrake
        val lcEngaged = connected && vs.launchControlEngaged
        val lcOn = connected && (vs.launchControlActive || vs.launchControlEngaged)
        Box(Modifier.width(98.dp).height(40.dp), contentAlignment = Alignment.CenterStart) {
        Crossfade(targetState = ebrakeOn || lcOn, animationSpec = tween(220), label = "brandStatus") { showStatus ->
        if (showStatus) HeaderVehicleStatus(ebrakeOn, lcOn, lcEngaged)
        else Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.drawBehind {
                // (2) Radial glow behind "RS" when connected. Drawn as a circle that fades out
                // inside its radius — a drawRect with a radius wider than the logo showed as a box.
                // DAY: a blue halo on the white header reads as a smudge — glow is a dark-theme cue.
                if (rsGlowActive > 0.01f && !isDayModeNow()) {
                    val c = Offset(size.width * 0.76f, size.height / 2f)   // centre of "RS" in the wordmark
                    val r = size.height * 0.9f   // stays inside the header bar
                    drawCircle(
                        Brush.radialGradient(
                            listOf(rsBlue.copy(alpha = glowAlpha * rsGlowActive), Color.Transparent),
                            center = c, radius = r
                        ),
                        radius = r, center = c
                    )
                }
            }
        ) {
            // The traced wordmark (sharp at any density) keeps the same three animations per glyph
            Wordmark(height = 17.dp, base = Frost, accent = rsBlue, glyphAlpha = { i ->
                // (3) Typewriter: each glyph fades in with stagger
                val charAlpha = ((typewriterPhase - i.toFloat() / glyphCount) * glyphCount).coerceIn(0f, 1f)
                // (1) Underscore blinks after the typewriter completes
                if (i == glyphCount - 1 && typewriterDone) charAlpha * cursorAlpha else charAlpha
            })
        }
        }
        }

        Spacer(Modifier.weight(1f))

        // ── Center: always MODE + ESC pills (placeholder when offline). ───
        HeaderModeEscPills(vs, accent, onModeClick)

        Spacer(Modifier.weight(1f))

        // ── Right: REC dot + connection pill + settings gear ────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            val ctx = LocalContext.current
            val autoRecOn = remember(ctx) { AppSettings.getAutoRecordDrives(ctx) }
            val autoRecArmed = autoRecOn && vs.isConnected &&
                !driveState.isRecording && vs.rpm <= 400.0
            if (driveState.isRecording) {
                val paused = driveState.isPaused
                val recAlpha by rememberInfiniteTransition(label = "headerRec").animateFloat(
                    initialValue = if (paused) 0.5f else 1f,
                    targetValue  = if (paused) 0.15f else 0.2f,
                    animationSpec = infiniteRepeatable(
                        tween(if (paused) 1400 else 700),
                        RepeatMode.Reverse
                    ),
                    label = "headerRecAlpha"
                )
                // Paused is a state, not a warning — neutral (was amber); recording keeps the red-orange dot.
                val dotColor = if (paused) Mid else Orange
                // V4: show elapsed time + distance alongside REC indicator
                val elapsed = driveState.elapsedMs
                val mm = (elapsed / 60_000).toInt()
                val ss = ((elapsed % 60_000) / 1_000).toInt()
                val distKm = driveState.cumulativeDistanceKm
                val distStr = if (prefs.speedUnit == "MPH") "%.1f mi".format(distKm * UnitConversions.KM_TO_MI)
                              else "%.1f km".format(distKm)
                val label = when {
                    paused  -> "PAUSED"
                    compact -> "REC %02d:%02d".format(mm, ss)
                    else    -> "REC %02d:%02d · %s".format(mm, ss, distStr)
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Box(Modifier.size(7.dp).clip(CircleShape).background(dotColor.copy(alpha = recAlpha)))
                    MonoLabel(label, 9.sp, dotColor, FontWeight.Bold, 0.2.sp)
                }
            } else if (autoRecArmed) {
                val armedAlpha by rememberInfiniteTransition(label = "headerArmed").animateFloat(
                    initialValue = 0.7f, targetValue = 0.25f,
                    animationSpec = infiniteRepeatable(tween(1600), RepeatMode.Reverse),
                    label = "headerArmedAlpha"
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape)
                            .border(CardBorder, Mid.copy(alpha = armedAlpha), CircleShape)
                    )
                    MonoLabel("ARMED", 9.sp, Mid.copy(alpha = armedAlpha), FontWeight.Bold, 0.2.sp)
                }
            }

            // rc.2: fixed-width connection pill. pressClick + haptic feedback.
            // Label crossfades on state change. Bloom fades 0→0.3 on connect,
            // 0.3→0 on disconnect. Quiet mode dims pill after 2s stable.
            // Neutral chip like MODE / ESC: the dot carries the link colour. Full-height tap area; a tap
            // while live only asks — the dialog's Disconnect is the sole path to stopConnection().
            Box(
                Modifier
                    .fillMaxHeight()
                    .alpha(quietAlpha)
                    .pressClick(pressedScale = 0.97f) {
                        // Demo: the pill never touches the adapter — it folds / unfolds the demo controls.
                        if (demoActive) { com.openrs.dash.demo.DemoMode.toggleControls(); return@pressClick }
                        when {
                            vs.isConnected -> confirmDisconnect = true
                            vs.isIdle      -> { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onReconnect() }
                            else           -> { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onConnect() }
                        }
                    }
                    .padding(horizontal = 4.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    Modifier.widthIn(min = 52.dp)
                        .background(Surf2, RoundedCornerShape(6.dp))
                        .border(CardBorder, Brd.copy(alpha = borderAlpha(0.6f)), RoundedCornerShape(6.dp))
                        .padding(horizontal = 7.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(10.dp).clip(CircleShape)
                            .background(connColor.copy(alpha = 0.25f * dotAlpha))
                            .then(if (bloomIntensity > 0.01f) Modifier.bloomGlow(connColor, 8.dp, bloomIntensity * dotAlpha) else Modifier))
                        // The sheet's CONNECTED symbol in the link colour (live / reconnecting / off)
                        Icon(painterResource(R.drawable.ic_tt_connected), contentDescription = null,
                            tint = connColor.copy(alpha = dotAlpha * (if (vs.isConnected || vs.isIdle) 1f else 0.6f)),
                            modifier = Modifier.size(12.dp))
                    }
                    AnimatedContent(
                        targetState = connLabel,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(180)) },
                        label = "connLbl"
                    ) { lbl ->
                        MonoLabel(lbl, 9.sp, connInk, FontWeight.Bold, 0.3.sp)
                    }
                }
            }

            // Settings: bare glyph (the filled box read as a button bezel), 44 dp target, glyph held
            // 12 dp off the edge so the right-hand cluster still sits tight to the screen edge.
            Box(
                Modifier
                    .fillMaxHeight()
                    .width(44.dp)
                    .clickable(onClickLabel = "Settings") { onSettings() }
                    .padding(end = 12.dp),
                contentAlignment = Alignment.CenterEnd
            ) {
                Box {
                    TelemetryIcon(RsIcon.SETTINGS, IconTone.NORMAL, 20.dp)
                    if (UpdateManager.hasUpdate) {
                        Box(
                            Modifier.align(Alignment.TopEnd)
                                .offset(x = 3.dp, y = 1.dp)
                                .size(6.dp)
                                .clip(CircleShape)
                                .background(Ok)
                        )
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun HeaderModeEscPills(vs: VehicleState, accent: Color, onModeClick: () -> Unit) {
    val connected = vs.isConnected
    // Calm cockpit: the selected mode is a reading (ink), not an alert — TRACK in amber read as a
    // warning. ESC keeps its existing colours for reduced stability control (Sport / Launch / Off).
    val modeColor = if (connected) Frost else Dim
    val escColor = when {
        !connected                          -> Dim
        vs.escStatus == EscStatus.OFF       -> Orange
        vs.escStatus == EscStatus.PARTIAL   -> Warn
        vs.escStatus == EscStatus.LAUNCH    -> Warn
        else                                -> Frost
    }
    val modeValue = if (connected) vs.driveMode.label.uppercase() else "\u2014"
    // The mode names are self-describing; the MODE caption only labels the offline placeholder.
    // The pill already says ESC — "ESC On" read as "ESC ESC ON".
    val escValue  = if (connected) vs.escStatus.label.removePrefix("ESC ").uppercase() else "\u2014"

    Row(verticalAlignment = Alignment.CenterVertically) {
        val pending = com.openrs.dash.can.driveModePending.value != null
        val pulseT = rememberInfiniteTransition(label = "modeBar")
        val animAlpha by pulseT.animateFloat(
            initialValue = 0.3f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2000, easing = EaseInOut), RepeatMode.Reverse),
            label = "modeBarA"
        )

        val barAlpha = if (pending) animAlpha else 0f
        StatusPill(
            label = if (connected) null else "MODE",
            value = modeValue,
            valueColor = modeColor,
            onClick = if (connected) onModeClick else null,
            pulseBarColor = if (pending) accent else null,
            pulseBarAlpha = barAlpha,
            chevron = connected
        )
        Spacer(Modifier.width(2.dp))
        // ESC = the sheet's TRACTION symbol (the dash telltale for stability control), in the ESC state colour
        StatusPill(
            label = null,
            value = escValue,
            valueColor = escColor,
            onClick = null,
            icon = RsIcon.TRACTION,
            iconTone = when {
                !connected -> IconTone.UNAVAILABLE
                vs.escStatus == EscStatus.OFF -> IconTone.CRITICAL
                vs.escStatus == EscStatus.PARTIAL || vs.escStatus == EscStatus.LAUNCH -> IconTone.WARNING
                else -> IconTone.NORMAL
            },
            description = "ESC $escValue",
        )
    }
}

// rc.2: unified anomaly surface — sits below the header so it never rewrites
// header geometry. One strip at a time, priority order:
//   eBrake > launchControlEngaged > updating.
// Connection state is not a strip: inserting / removing one moved every page. RECONNECTING and
// TAP TO CONNECT live in DRIVE's fixed-height StatusLine; the header pill (RCN / OFF) covers all tabs.
@Composable
private fun AnomalyStrip(vs: VehicleState) {
    val accent = LocalThemeAccent.current
    val updateState by UpdateManager.state.collectAsState()
    val dl = updateState as? com.openrs.dash.update.UpdateState.Downloading

    // E-brake and launch control moved to the header's fixed wordmark slot + DRIVE's gear module: this strip
    // expands under the header and pushed every page down. It keeps only the (rare) update progress.
    val kind: AnomalyKind? = if (dl != null) AnomalyKind.Updating(dl.progress) else null

    AnimatedVisibility(
        visible = kind != null,
        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
        exit  = shrinkVertically(tween(180)) + fadeOut(tween(180))
    ) {
        val current = kind ?: return@AnimatedVisibility
        val (text, color) = when (current) {
            is AnomalyKind.Updating   -> "\u21BB  UPDATE  ${((current.progress.coerceIn(0f, 1f)) * 100).toInt()}%" to accent
        }
        val effectiveAlpha = 1f
        Box(
            Modifier.fillMaxWidth()
                .height(Tokens.AnomalyStripHeight)
                .background(color.copy(alpha = pillBgAlpha(0.12f * effectiveAlpha)))
                .drawBehind {
                    // Bottom border (all states)
                    drawLine(
                        color = color.copy(alpha = borderAlpha(0.6f * effectiveAlpha)),
                        start = androidx.compose.ui.geometry.Offset(0f, size.height),
                        end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                        strokeWidth = 1.dp.toPx()
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            val stripTextStyle = remember {
                androidx.compose.ui.text.TextStyle(
                    fontSize = 10.sp, fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.3.sp, lineHeight = 10.sp,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                )
            }
            Text(text, style = stripTextStyle, color = textColor(color))
        }
    }
}

private sealed class AnomalyKind {
    data class Updating(val progress: Float) : AnomalyKind()
}

/**
 * Header chips in the wordmark's slot (98 × 40 dp): E-BRAKE (orange — the existing e-brake colour) and launch
 * control in the paint accent — outlined "LC ON" while switched on (0x420 LaunchControlStatus), filled "LC ENGAGED"
 * while engaged (0x225). Together they stack (18 dp each) so both keep their full words: engaged never shortens to
 * an ambiguous "LC". The filled chip uses [actionInk] on DAY — white on the bright paint accent was the weakest
 * header label — and the accent on NIGHT, each with [OnAccent] text.
 */
@Composable private fun HeaderVehicleStatus(ebrake: Boolean, lcOn: Boolean, lcEngaged: Boolean) {
    val accent = LocalThemeAccent.current
    val both = ebrake && lcOn
    val chipH = if (both) 18.dp else 22.dp
    Column(verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = Modifier.semantics(mergeDescendants = true) {
            contentDescription = listOfNotNull(if (ebrake) "E-brake on" else null,
                if (lcEngaged) "Launch control engaged" else if (lcOn) "Launch control on" else null).joinToString(", ")
        }) {
        if (ebrake) HeaderChip("E-BRAKE", textColor(Orange), Orange.copy(alpha = pillBgAlpha(0.14f)), Orange.copy(alpha = borderAlpha(0.7f)), chipH)
        if (lcOn) {
            if (lcEngaged) {
                val fill = if (isDayModeNow()) actionInk(accent) else accent
                HeaderChip("LC ENGAGED", OnAccent, fill, fill, chipH)
            } else HeaderChip("LC ON", actionInk(accent), accent.copy(alpha = pillBgAlpha(0.14f)), accent.copy(alpha = borderAlpha(0.8f)), chipH)
        }
    }
}

@Composable private fun HeaderChip(text: String, ink: Color, fill: Color, edge: Color, height: Dp = 22.dp) {
    val shape = RoundedCornerShape(5.dp)
    Box(Modifier.height(height).background(fill, shape).border(1.dp, edge, shape).padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center) {
        // No font padding: the label is optically centred even in the 18 dp stacked chips
        Text(text, color = ink, style = androidx.compose.ui.text.TextStyle(
            fontSize = 9.sp, lineHeight = 9.sp, fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold, letterSpacing = 0.3.sp,
            platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false),
            lineHeightStyle = androidx.compose.ui.text.style.LineHeightStyle(
                androidx.compose.ui.text.style.LineHeightStyle.Alignment.Center, androidx.compose.ui.text.style.LineHeightStyle.Trim.Both)))
    }
}

/**
 * Header status chip: neutral surface + hairline (the value carries any state colour), 9 / 10 sp text
 * (was 7 / 8). A tappable chip's target spans the full header height; [chevron] marks the MODE dock.
 */
@Composable private fun StatusPill(
    label: String?,
    value: String,
    valueColor: Color,
    onClick: (() -> Unit)?,
    pulseBarColor: Color? = null,
    pulseBarAlpha: Float = 1f,
    chevron: Boolean = false,
    icon: RsIcon? = null,
    iconTone: IconTone = IconTone.NORMAL,
    description: String? = null,
) {
    val shape = RoundedCornerShape(6.dp)
    Box(
        Modifier
            .fillMaxHeight()
            .then(if (onClick != null) Modifier.pressClick { onClick() }.padding(horizontal = 4.dp) else Modifier),
        contentAlignment = Alignment.Center
    ) {
    Box(
        Modifier
            .background(Surf2, shape)
            .border(CardBorder, Brd.copy(alpha = borderAlpha(0.6f)), shape)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            icon?.let { TelemetryIcon(it, iconTone, 13.dp, contentDescription = description) }
            label?.let { MonoLabel(it, 9.sp, Dim, letterSpacing = 0.3.sp) }
            MonoLabel(value, 10.sp, valueColor, FontWeight.Bold, 0.3.sp)
            if (chevron) MonoLabel("\u25BE", 9.sp, Dim)
        }
        // Pulsing accent bar at bottom (MODE pill)
        if (pulseBarColor != null) {
            Box(Modifier.matchParentSize()) {
                Box(
                    Modifier.align(Alignment.BottomCenter)
                        .fillMaxWidth(0.6f).height(1.5.dp)
                        .offset(y = 1.dp)
                        .background(
                            pulseBarColor.copy(alpha = pulseBarAlpha),
                            RoundedCornerShape(bottomStart = 2.dp, bottomEnd = 2.dp)
                        )
                )
            }
        }
    }
    }
}


// ═══════════════════════════════════════════════════════════════════════════
// WIFI COEXISTENCE BANNER — warns when BLE is active but phone is on WiFi
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun WifiCoexistenceBanner() {
    val ctx = LocalContext.current
    if (AppSettings.getConnectionMethod(ctx) != "BLUETOOTH") return

    val cm = remember { ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager }
    val wifiConnected = remember {
        val network = cm.activeNetwork
        val caps = if (network != null) cm.getNetworkCapabilities(network) else null
        caps?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
    }
    var dismissed by remember { mutableStateOf(false) }

    AnimatedVisibility(
        visible = wifiConnected && !dismissed,
        enter = expandVertically(spring(stiffness = Spring.StiffnessLow)) + fadeIn(),
        exit  = shrinkVertically(spring(stiffness = Spring.StiffnessMediumLow)) + fadeOut()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 4.dp)
                .background(Warn.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .border(CardBorder, Warn.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            MonoLabel(
                "WiFi connected — internet may be blocked. Forget adapter WiFi for best BLE experience.",
                8.sp, Warn, letterSpacing = 0.05.sp,
                modifier = Modifier.align(Alignment.CenterStart).padding(end = 24.dp)
            )
            MonoLabel(
                "\u2715", 12.sp, Dim,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .clickable { dismissed = true }
            )
        }
    }
}

