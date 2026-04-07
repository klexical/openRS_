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
import com.openrs.dash.ui.trip.DrivePage
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge(
            navigationBarStyle = androidx.activity.SystemBarStyle.dark(
                android.graphics.Color.TRANSPARENT
            )
        )
        UserPrefsStore.load(this)
        setBrightness(AppSettings.getBrightness(this))

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
            val pagerState  = rememberPagerState(pageCount = { 7 })
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

            // Apply brightness to theme color system
            LaunchedEffect(prefs.brightness) {
                setBrightness(prefs.brightness)
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
                                        derivedStateOf { !(selectedTab == 4 && mapTouched) }
                                    }
                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.weight(1f),
                                        beyondViewportPageCount = 1,
                                        userScrollEnabled = pagerScrollEnabled, // disable pager swipe while touching map
                                        key = { it }
                                    ) { page ->
                                        Box(Modifier.fillMaxSize()) {
                                            when (page) {
                                                0 -> DashPage(vs, prefs)
                                                1 -> PowerPage(vs, prefs)
                                                2 -> ChassisPage(vs, prefs, onReset = { service?.resetPeaks() })
                                                3 -> TempsPage(vs, prefs)
                                                4 -> DrivePage(driveState, vs, prefs, onMapTouched = { mapTouched = it })
                                                5 -> DiagPage(
                                                    debugLines,
                                                    vs,
                                                    onScanDtcs  = service?.let { svc -> { svc.scanDtcs() } },
                                                    onClearDtcs = service?.let { svc -> { svc.clearDtcs() } },
                                                    onSendRawQuery = service?.let { svc ->
                                                        val q: suspend (Int, String, Long) -> ByteArray? =
                                                            { r, f, t -> svc.sendRawQuery(r, f, t) }
                                                        q
                                                    },
                                                    onResetSession = { service?.resetSession() }
                                                )
                                                6 -> MorePage(vs, prefs, snackbarHostState, onSettings = { settingsOpen = true }, onCustomDash = { showCustomDash = true }, firmwareApi = service?.firmwareApi)
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
                        BottomNavBar(
                            selected = selectedTab,
                            onSelect = onSelectNav,
                            hazeState = hazeState,
                            modifier = Modifier.align(Alignment.BottomCenter)
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
// HEADER — Compact Status Bar
// ═══════════════════════════════════════════════════════════════════════════
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

    // Connection state
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

    // Mode / ESC colors
    val modeColor = when (vs.driveMode) {
        DriveMode.SPORT -> Ok; DriveMode.TRACK -> Warn; DriveMode.DRIFT -> Orange; else -> accent
    }
    val escColor = when (vs.escStatus) {
        EscStatus.OFF -> Orange; EscStatus.PARTIAL -> Warn; EscStatus.LAUNCH -> Warn; else -> accent
    }

    Row(
        Modifier.fillMaxWidth()
            .height(Tokens.StatusBarHeight)
            .background(Surf)
            .drawBehind {
                // Bottom border
                drawLine(
                    color = Brd.copy(alpha = 0.3f),
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

        // ── Center: MODE pill + ESC pill ────────────────────────
        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
            // LC pill — flashing when launch control engaged
            AnimatedVisibility(
                visible = vs.launchControlEngaged,
                enter = expandHorizontally() + fadeIn(),
                exit = shrinkHorizontally() + fadeOut()
            ) {
                val flashAlpha by rememberInfiniteTransition(label = "lcFlash").animateFloat(
                    initialValue = 1f, targetValue = 0f,
                    animationSpec = infiniteRepeatable(tween(200), RepeatMode.Reverse),
                    label = "lcFlashA"
                )
                Row {
                    Box(
                        Modifier
                            .alpha(flashAlpha)
                            .background(Warn, RoundedCornerShape(4.dp))
                            .border(CardBorder, Warn, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        MonoLabel("LC", 8.sp, Bg, FontWeight.Bold, 0.05.sp)
                    }
                    Spacer(Modifier.width(6.dp))
                }
            }

            // MODE pill
            val pulseT = rememberInfiniteTransition(label = "modeBar")
            val barAlpha by pulseT.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(2000, easing = EaseInOut), RepeatMode.Reverse
                ), label = "modeBarA"
            )
            StatusPill(
                label = "MODE",
                value = vs.driveMode.label.uppercase(),
                valueColor = modeColor,
                onClick = onModeClick,
                pulseBarColor = modeColor,
                pulseBarAlpha = barAlpha
            )

            Spacer(Modifier.width(6.dp))

            // ESC pill (onClick = null for now, future dock)
            StatusPill(
                label = "ESC",
                value = vs.escStatus.label.uppercase(),
                valueColor = escColor,
                onClick = null
            )
        }

        Spacer(Modifier.weight(1f))

        // ── Right: REC dot + connection pill + settings gear ────
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            // REC dot (pulsing, no text)
            if (driveState.isRecording && !driveState.isPaused) {
                val recAlpha by rememberInfiniteTransition(label = "headerRec").animateFloat(
                    initialValue = 1f, targetValue = 0.2f,
                    animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                    label = "headerRecAlpha"
                )
                Box(Modifier.size(6.dp).clip(CircleShape).background(Orange.copy(alpha = recAlpha)))
            }

            // Connection pill (compact)
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

            // Settings gear (matches pill height)
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
        val name = AppSettings.getBleDeviceName(ctx) ?: "BLE"
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
