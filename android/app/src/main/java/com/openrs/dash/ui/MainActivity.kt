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
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.CanDataService
import com.openrs.dash.ui.anim.EdgeShiftLight
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.anim.bloomGlow

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

            CompositionLocalProvider(LocalThemeAccent provides prefs.themeAccent) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Bg,
                        surface    = Surf,
                        primary    = prefs.themeAccent
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
                                        onModeClick  = { dockOpen = !dockOpen }
                                    )

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

                                    ConnectionBanner(vs)
                                    WifiCoexistenceBanner()
                                    EBrakeWarningBanner(vs)
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
                                            Modifier.fillMaxSize().graphicsLayer { alpha = pageAlpha }
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
                                                    onCustomDash = { showCustomDash = true },
                                                    firmwareApi = service?.firmwareApi,
                                                    onScanDtcs = service?.let { svc ->
                                                        val fn: suspend () -> List<com.openrs.dash.data.DtcResult> = { svc.scanDtcs() }
                                                        fn
                                                    },
                                                    onClearDtcs = service?.let { svc ->
                                                        val fn: suspend () -> Map<String, Boolean> = { svc.clearDtcs() }
                                                        fn
                                                    },
                                                    onSendRawQuery = service?.let { svc ->
                                                        val q: suspend (Int, String, Long) -> ByteArray? =
                                                            { r, f, t -> svc.sendRawQuery(r, f, t) }
                                                        q
                                                    },
                                                    onResetSession = { service?.resetSession() },
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
                                    SettingsDialog(onDismiss = { settingsOpen = false })
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
                            badges = listOf(false, false, false, false, activeDtcs > 0)
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
// HEADER — Contextual status bar (v3.0 B3)
//
// Priority-driven single row. Anomalies (E-brake, launch control, firmware
// download, disconnect) take over the center region and render as a
// color-coded banner. Bookends (logo left, REC dot + connection pill + gear
// right) are always visible so the user never loses the ability to change
// connection or open Settings.
// ═══════════════════════════════════════════════════════════════════════════

private sealed class HeaderContext {
    data object Normal : HeaderContext()                     // → MODE + ESC pills
    data object EBrake : HeaderContext()                     // ⚠ E-BRAKE ACTIVE
    data object LaunchControl : HeaderContext()              // ⚡ LAUNCH CONTROL
    data class Updating(val progress: Float) : HeaderContext() // ↻ UPDATE xx%
    data object Reconnecting : HeaderContext()               // ○ RECONNECTING…
    data object Offline : HeaderContext()                    // TAP TO CONNECT
}

@Composable
private fun resolveHeaderContext(vs: VehicleState): HeaderContext {
    val updateState by UpdateManager.state.collectAsState()
    val dl = updateState as? com.openrs.dash.update.UpdateState.Downloading
    return when {
        vs.eBrake                                -> HeaderContext.EBrake
        vs.launchControlEngaged                  -> HeaderContext.LaunchControl
        dl != null                               -> HeaderContext.Updating(dl.progress)
        !vs.isConnected && vs.isIdle             -> HeaderContext.Reconnecting
        !vs.isConnected                          -> HeaderContext.Offline
        else                                     -> HeaderContext.Normal
    }
}

@Composable fun AppHeader(
    vs: VehicleState,
    prefs: UserPrefs,
    onSettings: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    driveState: com.openrs.dash.data.DriveState = com.openrs.dash.data.DriveState(),
    onModeClick: () -> Unit = {}
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
    val connColor = when {
        vs.isConnected -> Ok
        vs.isIdle      -> Warn
        else           -> Orange
    }
    val connLabel = when {
        vs.isConnected -> "LIVE"
        vs.isIdle      -> "IDLE"
        else           -> "OFF"
    }

    val ctx = resolveHeaderContext(vs)

    Row(
        Modifier.fillMaxWidth()
            .height(Tokens.StatusBarHeight)
            .background(Surf)
            .drawBehind {
                drawLine(
                    color = Brd.copy(alpha = borderAlpha(0.3f)),
                    start = androidx.compose.ui.geometry.Offset(0f, size.height),
                    end = androidx.compose.ui.geometry.Offset(size.width, size.height),
                    strokeWidth = 1.dp.toPx()
                )
            }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // ── Left: Logo ──────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("open", fontSize = 13.sp, fontFamily = OrbitronFamily,
                color = Frost, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp)
            Text("RS", fontSize = 13.sp, fontFamily = OrbitronFamily,
                color = accent, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp)
            Text("_", fontSize = 13.sp, fontFamily = OrbitronFamily,
                color = Frost, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp)
        }

        Spacer(Modifier.weight(1f))

        // ── Center: priority-driven content ─────────────────────
        AnimatedContent(
            targetState = ctx::class,
            transitionSpec = { (fadeIn() togetherWith fadeOut()) },
            label = "headerCtx",
            contentKey = { it }
        ) { _ ->
            when (ctx) {
                HeaderContext.EBrake        -> HeaderBanner("⚠  E-BRAKE ACTIVE", Orange)
                HeaderContext.LaunchControl -> HeaderBannerFlashing("⚡  LAUNCH CONTROL", Warn)
                is HeaderContext.Updating   -> HeaderBanner(
                    "↻  UPDATE  ${((ctx as HeaderContext.Updating).progress.coerceIn(0f, 1f) * 100).toInt()}%",
                    accent
                )
                HeaderContext.Reconnecting  -> HeaderBannerPulsing("○  RECONNECTING…", Warn)
                HeaderContext.Offline       -> Box(
                    Modifier.clickable { onConnect() }.padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) { MonoLabel("TAP TO CONNECT", 9.sp, Orange, FontWeight.Bold, 0.12.sp) }
                HeaderContext.Normal        -> NormalStatus(vs, accent, onModeClick)
            }
        }

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
                val label    = if (paused) "PAUSED" else "REC"
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

            Box(
                Modifier
                    .background(connColor.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                    .border(CardBorder, connColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                    .clickable {
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
                            .then(if (vs.isConnected) Modifier.bloomGlow(connColor, 8.dp, 0.3f * dotAlpha) else Modifier))
                        Box(Modifier.size(5.dp).clip(CircleShape)
                            .background(connColor.copy(alpha = dotAlpha)))
                    }
                    MonoLabel(connLabel, 7.sp, connColor, FontWeight.Bold, 0.08.sp)
                }
            }

            Box(
                Modifier
                    .background(Surf2, RoundedCornerShape(4.dp))
                    .border(CardBorder, Brd, RoundedCornerShape(4.dp))
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
private fun NormalStatus(vs: VehicleState, accent: Color, onModeClick: () -> Unit) {
    val modeColor = when (vs.driveMode) {
        DriveMode.SPORT -> Ok; DriveMode.TRACK -> Warn; DriveMode.DRIFT -> Orange; else -> accent
    }
    val escColor = when (vs.escStatus) {
        EscStatus.OFF -> Orange; EscStatus.PARTIAL -> Warn; EscStatus.LAUNCH -> Warn; else -> accent
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        // C7: pulse bar only animates while a mode change is in flight.
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
            value = vs.driveMode.label.uppercase(),
            valueColor = modeColor,
            onClick = onModeClick,
            pulseBarColor = if (pending) modeColor else null,
            pulseBarAlpha = barAlpha
        )
        Spacer(Modifier.width(6.dp))
        StatusPill(
            label = "ESC",
            value = vs.escStatus.label.uppercase(),
            valueColor = escColor,
            onClick = null
        )
    }
}

@Composable
private fun HeaderBanner(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
            .border(CardBorder, color.copy(alpha = 0.55f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        MonoLabel(text, 9.sp, color, FontWeight.Bold, 0.12.sp)
    }
}

@Composable
private fun HeaderBannerFlashing(text: String, color: Color) {
    val flash by rememberInfiniteTransition(label = "hdrFlash").animateFloat(
        initialValue = 1f, targetValue = 0.35f,
        animationSpec = infiniteRepeatable(tween(220), RepeatMode.Reverse),
        label = "hdrFlashA"
    )
    Box(
        Modifier
            .alpha(flash)
            .background(color.copy(alpha = 0.20f), RoundedCornerShape(4.dp))
            .border(CardBorder, color.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        MonoLabel(text, 9.sp, color, FontWeight.Bold, 0.12.sp)
    }
}

@Composable
private fun HeaderBannerPulsing(text: String, color: Color) {
    val pulse by rememberInfiniteTransition(label = "hdrPulse").animateFloat(
        initialValue = 0.4f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900, easing = EaseInOut), RepeatMode.Reverse),
        label = "hdrPulseA"
    )
    Box(
        Modifier
            .background(color.copy(alpha = 0.10f * pulse), RoundedCornerShape(4.dp))
            .border(CardBorder, color.copy(alpha = 0.45f * pulse), RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 3.dp),
        contentAlignment = Alignment.Center
    ) {
        MonoLabel(text, 9.sp, color, FontWeight.Bold, 0.12.sp)
    }
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
            .background(valueColor.copy(alpha = 0.10f), shape)
            .border(CardBorder, valueColor.copy(alpha = 0.25f), shape)
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
        enter = expandVertically() + fadeIn(),
        exit  = shrinkVertically() + fadeOut()
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

// ═══════════════════════════════════════════════════════════════════════════
// CONNECTION BANNER — contextual disconnected state
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun ConnectionBanner(vs: VehicleState) {
    val ctx = LocalContext.current
    var dismissed by remember { mutableStateOf(false) }

    // Reset dismissed state when connection succeeds
    LaunchedEffect(vs.isConnected) {
        if (vs.isConnected) dismissed = false
    }

    val adapterType = AppSettings.getAdapterType(ctx)
    val connMethod = AppSettings.getConnectionMethod(ctx)
    val adapterLabel = if (adapterType == "MEATPI_PRO") "MeatPi Pro" else "MeatPi USB"
    val addressLabel: String
    if (connMethod == "BLUETOOTH") {
        val raw = AppSettings.getBleDeviceName(ctx) ?: "BLE"
        val name = if (raw.length > 12) raw.take(12) + "\u2026" else raw
        addressLabel = "BT — $name"
    } else {
        addressLabel = "${AppSettings.getHost(ctx)}:${AppSettings.getPort(ctx)}"
    }

    AnimatedVisibility(
        visible = !vs.isConnected && !dismissed,
        enter = expandVertically() + fadeIn(),
        exit  = shrinkVertically() + fadeOut()
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp)
                .background(Orange.copy(alpha = 0.08f), RoundedCornerShape(8.dp))
                .border(CardBorder, Orange.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            MonoLabel(
                "$adapterLabel  —  $addressLabel  —  DISCONNECTED",
                9.sp, Orange, letterSpacing = 0.1.sp,
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

// ═══════════════════════════════════════════════════════════════════════════
// E-BRAKE WARNING BANNER — shown when e-brake is engaged
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun EBrakeWarningBanner(vs: VehicleState) {
    var dismissed by remember { mutableStateOf(false) }

    LaunchedEffect(vs.eBrake) {
        if (!vs.eBrake) dismissed = false
    }

    AnimatedVisibility(
        visible = vs.eBrake && !dismissed,
        enter = expandVertically() + fadeIn(),
        exit  = shrinkVertically() + fadeOut()
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
                "E-BRAKE ENGAGED",
                9.sp, Warn, letterSpacing = 0.1.sp,
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
