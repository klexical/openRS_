package com.openrs.dash.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder

import android.Manifest
import androidx.activity.ComponentActivity
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
import androidx.compose.runtime.*
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
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.R
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
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
        }
        override fun onServiceDisconnected(n: ComponentName) { service = null }
    }
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
        // Request location at startup for drive recording
        perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        // BLE permissions when Bluetooth connection method is selected
        if (AppSettings.getConnectionMethod(this) == "BLUETOOTH") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                perms.add(Manifest.permission.BLUETOOTH_SCAN)
                perms.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
        }
        permLauncher.launch(perms.toTypedArray())

        setContent {
            val vs          by OpenRSDashApp.instance.vehicleState.collectAsState()
            val prefs       by UserPrefsStore.prefs.collectAsState()
            val debugLines  by OpenRSDashApp.instance.debugLines.collectAsState()
            val driveState  by OpenRSDashApp.instance.driveState.collectAsState()
            val pagerState  = rememberPagerState(pageCount = { 5 })
            val selectedTab by remember { derivedStateOf { pagerState.currentPage } }
            var mapTouched  by remember { mutableStateOf(false) }
            val hazeState   = remember { HazeState() }
            var settingsOpen    by remember { mutableStateOf(false) }
            var showCustomDash  by remember { mutableStateOf(false) }
            var dockOpen        by remember { mutableStateOf(false) }
            val isFw            by OpenRSDashApp.instance.isOpenRsFirmware.collectAsState()
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

            val activeAccent = themeAccentPreview() ?: prefs.themeAccent
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
                                        driveState   = driveState,
                                        onModeClick  = { dockOpen = !dockOpen },
                                        onPullDown   = { dockOpen = true }
                                    )

                                    // rc.2: anomaly surface sits below the header so it can't steal the
                                    // MODE/ESC pills. Priority: e-brake > LC engaged > update > reconnect.
                                    AnomalyStrip(vs, onConnect = { service?.startConnection() })

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

                                    WifiCoexistenceBanner()
                                    // Auto-dismiss dock on tab change
                                    LaunchedEffect(selectedTab) { dockOpen = false }

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
                                                0 -> DrivePage(vs, prefs)
                                                1 -> PerfPage(vs, prefs, onReset = { service?.resetPeaks() })
                                                2 -> TempsPage(vs, prefs)
                                                3 -> TripPage(driveState, vs, prefs, onMapTouched = { mapTouched = it })
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
                                        onCustomDash = { settingsOpen = false; showCustomDash = true }
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
                            rpm       = vs.rpm.toFloat(),
                            shiftRpm  = prefs.edgeShiftRpm.toFloat(),
                            enabled   = prefs.edgeShiftLight,
                            colorMode = prefs.edgeShiftColor,
                            intensity = when (prefs.edgeShiftIntensity) {
                                "low" -> 0.3f; "med" -> 0.65f; else -> 1.0f
                            }
                        )

                        // rc.2: peripheral glow while LC is actively engaged — unmissable in-cabin.
                        LaunchControlEdgeGlow(engaged = vs.launchControlEngaged)

                        // ── Bottom Nav Bar (overlay — content extends behind) ──
                        val navScope = rememberCoroutineScope()
                        val onSelectNav = remember<(Int) -> Unit> {
                            { page -> navScope.launch { pagerState.animateScrollToPage(page) } }
                        }
                        val activeDtcs by OpenRSDashApp.instance.activeDtcCount.collectAsState()
                        BottomNavBar(
                            selected = selectedTab,
                            onSelect = onSelectNav,
                            hazeState = hazeState,
                            modifier = Modifier.align(Alignment.BottomCenter),
                            badges = listOf(false, false, false, false, activeDtcs > 0),
                            navTabIdentity = prefs.navTabIdentity,
                        )

                    }
                }
            }
        }
    }

    private fun startSvc() {
        val i = Intent(this, CanDataService::class.java)
        try {
            androidx.core.content.ContextCompat.startForegroundService(this, i)
        } catch (e: Exception) {
            android.util.Log.w("CAN", "startForegroundService failed — falling back to bind", e)
        }
        bindService(i, conn, Context.BIND_AUTO_CREATE)
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
    onPullDown: () -> Unit = {}
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
        vs.isConnected -> Ok
        vs.isIdle      -> Warn
        else           -> Orange
    }
    val connLabel = when {
        isUpdating     -> "UPD"
        vs.isConnected -> "LIVE"
        isReconnecting -> "RCN"
        vs.isIdle      -> "IDLE"
        else           -> "OFF"
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
        if (vs.isConnected && connStable && prefs.livePillQuiet) 0.6f else 1f,
        tween(600), label = "pillQuiet")

    val haptic = LocalHapticFeedback.current

    // rc.2: header underline stays a constant hairline — dock drops right
    // below it, so flashing it mode-color on mode-change reads as a stray
    // line. Mode-color breath is carried by the bottom nav indicator only.
    val underlineColor = Brd.copy(alpha = borderAlpha(0.3f))
    val underlineStroke = 1.dp

    // Pull-down gesture: accumulate drag distance; open dock on ≥40dp.
    val density = androidx.compose.ui.platform.LocalDensity.current
    var dragAccum by remember { mutableStateOf(0f) }
    val dragState = rememberDraggableState { delta -> dragAccum += delta }

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
        // 7 characters: o-p-e-n-R-S-_
        val chars = listOf(
            "o" to Frost, "p" to Frost, "e" to Frost, "n" to Frost,
            "R" to rsBlue, "S" to rsBlue, "_" to Frost
        )

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

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.drawBehind {
                // (2) Radial glow behind "RS" when connected
                if (rsGlowActive > 0.01f) {
                    val glowBrush = Brush.radialGradient(
                        listOf(
                            rsBlue.copy(alpha = glowAlpha * rsGlowActive),
                            Color.Transparent
                        ),
                        center = Offset(size.width * 0.62f, size.height / 2f),
                        radius = size.height * 1.8f
                    )
                    drawRect(glowBrush)
                }
            }
        ) {
            chars.forEachIndexed { i, (ch, baseColor) ->
                // (3) Typewriter: each char fades in with stagger
                val charThreshold = i.toFloat() / chars.size
                val charAlpha = ((typewriterPhase - charThreshold) * chars.size).coerceIn(0f, 1f)
                // (1) Underscore blinks after typewriter completes
                val finalAlpha = if (i == chars.lastIndex && typewriterDone)
                    charAlpha * cursorAlpha else charAlpha
                val isUnderscore = i == chars.lastIndex
                Text(
                    ch,
                    fontSize = if (isUnderscore) 14.sp else 13.sp,
                    fontFamily = OrbitronFamily,
                    color = baseColor.copy(alpha = finalAlpha),
                    fontWeight = if (isUnderscore) FontWeight.Black else FontWeight.Bold,
                    letterSpacing = 0.05.sp
                )
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
                val dotColor = if (paused) Warn else Orange
                // V4: show elapsed time + distance alongside REC indicator
                val elapsed = driveState.elapsedMs
                val mm = (elapsed / 60_000).toInt()
                val ss = ((elapsed % 60_000) / 1_000).toInt()
                val distKm = driveState.cumulativeDistanceKm
                val distStr = if (prefs.speedUnit == "MPH") "%.1f mi".format(distKm * UnitConversions.KM_TO_MI)
                              else "%.1f km".format(distKm)
                val label = if (paused) "PAUSED" else "REC %02d:%02d · %s".format(mm, ss, distStr)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Box(Modifier.size(6.dp).clip(CircleShape).background(dotColor.copy(alpha = recAlpha)))
                    MonoLabel(label, 7.sp, dotColor, FontWeight.Bold, 0.08.sp)
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
                        Modifier.size(6.dp).clip(CircleShape)
                            .border(CardBorder, Mid.copy(alpha = armedAlpha), CircleShape)
                    )
                    MonoLabel("ARMED", 7.sp, Mid.copy(alpha = armedAlpha), FontWeight.Bold, 0.08.sp)
                }
            }

            // rc.2: fixed-width connection pill. pressClick + haptic feedback.
            // Label crossfades on state change. Bloom fades 0→0.3 on connect,
            // 0.3→0 on disconnect. Quiet mode dims pill after 2s stable.
            Box(
                Modifier
                    .alpha(quietAlpha)
                    .widthIn(min = 48.dp)
                    .background(connColor.copy(alpha = pillBgAlpha(0.08f)), RoundedCornerShape(4.dp))
                    .border(CardBorder, connColor.copy(alpha = borderAlpha(0.2f)), RoundedCornerShape(4.dp))
                    .pressClick(pressedScale = 0.97f) {
                        haptic.performHapticFeedback(
                            if (vs.isConnected) HapticFeedbackType.Reject
                            else HapticFeedbackType.Confirm
                        )
                        when {
                            vs.isConnected -> onDisconnect()
                            vs.isIdle      -> onReconnect()
                            else           -> onConnect()
                        }
                    }
                    .padding(horizontal = 5.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Box(contentAlignment = Alignment.Center) {
                        Box(Modifier.size(10.dp).clip(CircleShape)
                            .background(connColor.copy(alpha = 0.25f * dotAlpha))
                            .then(if (bloomIntensity > 0.01f) Modifier.bloomGlow(connColor, 8.dp, bloomIntensity * dotAlpha) else Modifier))
                        when {
                            vs.isConnected -> Box(Modifier.size(5.dp).clip(CircleShape)
                                .background(connColor.copy(alpha = dotAlpha)))
                            vs.isIdle -> Box(Modifier.size(6.dp).clip(CircleShape)
                                .border(1.2.dp, connColor.copy(alpha = dotAlpha), CircleShape))
                            else -> MonoLabel("×", 10.sp, connColor.copy(alpha = dotAlpha), FontWeight.Bold)
                        }
                    }
                    AnimatedContent(
                        targetState = connLabel,
                        transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(180)) },
                        label = "connLbl"
                    ) { lbl ->
                        MonoLabel(lbl, 7.sp, connColor, FontWeight.Bold, 0.08.sp)
                    }
                }
            }

            Box(
                Modifier
                    .background(Mid.copy(alpha = pillBgAlpha(0.15f)), RoundedCornerShape(4.dp))
                    .border(CardBorder, Mid.copy(alpha = borderAlpha(0.35f)), RoundedCornerShape(4.dp))
                    .clickable { onSettings() }
                    .padding(horizontal = 6.dp, vertical = 2.dp),
                contentAlignment = Alignment.Center
            ) {
                UIText("⚙", 11.sp, Mid)
                if (UpdateManager.hasUpdate) {
                    Box(
                        Modifier.align(Alignment.TopEnd)
                            .offset(x = 2.dp, y = (-2).dp)
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Ok)
                    )
                }
            }
        }
    }
}

@Composable
private fun HeaderModeEscPills(vs: VehicleState, accent: Color, onModeClick: () -> Unit) {
    val connected = vs.isConnected
    val modeColor = when {
        !connected            -> Dim
        vs.driveMode == DriveMode.SPORT -> Ok
        vs.driveMode == DriveMode.TRACK -> Warn
        vs.driveMode == DriveMode.DRIFT -> Orange
        else                  -> accent
    }
    val escColor = when {
        !connected                          -> Dim
        vs.escStatus == EscStatus.OFF       -> Orange
        vs.escStatus == EscStatus.PARTIAL   -> Warn
        vs.escStatus == EscStatus.LAUNCH    -> Warn
        else                                -> accent
    }
    val modeValue = if (connected) vs.driveMode.label.uppercase() else "\u2014"
    val escValue  = if (connected) vs.escStatus.label.uppercase() else "\u2014"

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
            label = "MODE",
            value = modeValue,
            valueColor = modeColor,
            onClick = if (connected) onModeClick else null,
            pulseBarColor = if (pending) modeColor else null,
            pulseBarAlpha = barAlpha
        )
        Spacer(Modifier.width(6.dp))
        StatusPill(
            label = "ESC",
            value = escValue,
            valueColor = escColor,
            onClick = null
        )
    }
}

// rc.2: unified anomaly surface — sits below the header so it never rewrites
// header geometry. One strip at a time, priority order:
//   eBrake > launchControlEngaged > updating > reconnecting > offline.
@Composable
private fun AnomalyStrip(vs: VehicleState, onConnect: () -> Unit) {
    val accent = LocalThemeAccent.current
    val updateState by UpdateManager.state.collectAsState()
    val dl = updateState as? com.openrs.dash.update.UpdateState.Downloading

    val kind: AnomalyKind? = when {
        vs.eBrake                    -> AnomalyKind.EBrake
        vs.launchControlEngaged      -> AnomalyKind.LaunchControl
        dl != null                   -> AnomalyKind.Updating(dl.progress)
        !vs.isConnected && vs.isIdle -> AnomalyKind.Reconnecting
        !vs.isConnected              -> AnomalyKind.Offline
        else                         -> null
    }

    AnimatedVisibility(
        visible = kind != null,
        enter = expandVertically(tween(220)) + fadeIn(tween(220)),
        exit  = shrinkVertically(tween(180)) + fadeOut(tween(180))
    ) {
        val current = kind ?: return@AnimatedVisibility
        val (text, color) = when (current) {
            AnomalyKind.EBrake        -> "\u26A0  E-BRAKE ACTIVE" to Orange
            AnomalyKind.LaunchControl -> "\u26A1  LAUNCH CONTROL"  to Warn
            is AnomalyKind.Updating   -> "\u21BB  UPDATE  ${((current.progress.coerceIn(0f, 1f)) * 100).toInt()}%" to accent
            AnomalyKind.Reconnecting  -> "RECONNECTING" to Warn
            AnomalyKind.Offline       -> "TAP TO CONNECT" to Orange
        }
        val flashing = current is AnomalyKind.LaunchControl
        val pulsing  = current is AnomalyKind.Reconnecting || current is AnomalyKind.Offline

        val flashAlpha by rememberInfiniteTransition(label = "anoFlash").animateFloat(
            initialValue = 1f, targetValue = 0.35f,
            animationSpec = infiniteRepeatable(tween(220), RepeatMode.Reverse),
            label = "anoFlashA"
        )
        val pulseAlpha by rememberInfiniteTransition(label = "anoPulse").animateFloat(
            initialValue = 0.45f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
            label = "anoPulseA"
        )
        val effectiveAlpha = when {
            flashing -> flashAlpha
            pulsing  -> pulseAlpha
            else     -> 1f
        }
        val orbiting = current is AnomalyKind.Reconnecting || current is AnomalyKind.Offline
        // Orbiting phase for reconnecting top-border highlight
        val orbitPhase by rememberInfiniteTransition(label = "anoOrbit").animateFloat(
            initialValue = 0f, targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(2200, easing = LinearEasing), RepeatMode.Restart),
            label = "anoOrbitP"
        )
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
                    // Orbiting top-border highlight (reconnecting only)
                    if (orbiting) {
                        val spotCenter = orbitPhase * size.width
                        val spotRadius = size.width * 0.15f
                        val orbitBrush = Brush.horizontalGradient(
                            colorStops = arrayOf(
                                0f to Color.Transparent,
                                ((spotCenter - spotRadius) / size.width).coerceIn(0f, 1f) to Color.Transparent,
                                (spotCenter / size.width).coerceIn(0f, 1f) to color.copy(alpha = 0.8f),
                                ((spotCenter + spotRadius) / size.width).coerceIn(0f, 1f) to Color.Transparent,
                                1f to Color.Transparent
                            )
                        )
                        drawRect(
                            brush = orbitBrush,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, 0f),
                            size = androidx.compose.ui.geometry.Size(size.width, 1.dp.toPx())
                        )
                    }
                }
                .then(if (current is AnomalyKind.Offline)
                    Modifier.clickable { onConnect() } else Modifier),
            contentAlignment = Alignment.Center
        ) {
            val stripTextStyle = remember {
                androidx.compose.ui.text.TextStyle(
                    fontSize = 9.sp, fontFamily = JetBrainsMonoFamily, fontWeight = FontWeight.Bold,
                    letterSpacing = 0.14.sp, lineHeight = 9.sp,
                    platformStyle = androidx.compose.ui.text.PlatformTextStyle(includeFontPadding = false)
                )
            }
            if (current is AnomalyKind.Reconnecting) {
                // Animated breadcrumb dots: each dot fades in sequentially.
                // Fixed "..." width so the label doesn't shift as dots appear.
                val dotPhase by rememberInfiniteTransition(label = "dots").animateFloat(
                    initialValue = 0f, targetValue = 4f,
                    animationSpec = infiniteRepeatable(tween(3600, easing = LinearEasing)),
                    label = "dotsP"
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(text, style = stripTextStyle, color = color)
                    val phase = dotPhase.coerceIn(0f, 4f)
                    for (i in 0 until 3) {
                        val dotAlpha = ((phase - i).coerceIn(0f, 1f))
                        Text(".", style = stripTextStyle, color = color.copy(alpha = dotAlpha))
                    }
                }
            } else {
                Text(text, style = stripTextStyle, color = color)
            }
        }
    }
}

private sealed class AnomalyKind {
    data object EBrake : AnomalyKind()
    data object LaunchControl : AnomalyKind()
    data class Updating(val progress: Float) : AnomalyKind()
    data object Reconnecting : AnomalyKind()
    data object Offline : AnomalyKind()
}

@Composable private fun StatusPill(
    label: String,
    value: String,
    valueColor: Color,
    onClick: (() -> Unit)?,
    pulseBarColor: Color? = null,
    pulseBarAlpha: Float = 1f
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .background(valueColor.copy(alpha = pillBgAlpha(0.10f)), shape)
            .border(CardBorder, valueColor.copy(alpha = borderAlpha(0.25f)), shape)
            .then(if (onClick != null) Modifier.pressClick { onClick() } else Modifier)
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            MonoLabel(label, 7.sp, Dim, letterSpacing = 0.08.sp)
            MonoLabel(value, 8.sp, valueColor, FontWeight.Bold, 0.05.sp)
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

