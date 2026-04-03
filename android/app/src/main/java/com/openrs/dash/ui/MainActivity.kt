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
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.CanDataService
import com.openrs.dash.ui.anim.EdgeShiftLight
import com.openrs.dash.ui.anim.bloomGlow
import com.openrs.dash.ui.anim.pressClick
import com.openrs.dash.ui.trip.DrivePage
import com.openrs.dash.update.UpdateManager
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

        enableEdgeToEdge()
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
            val pagerScope  = rememberCoroutineScope()
            var settingsOpen    by remember { mutableStateOf(false) }
            var showCustomDash  by remember { mutableStateOf(false) }
            var dockOpen        by remember { mutableStateOf(false) }
            val isFw            by OpenRSDashApp.instance.isOpenRsFirmware.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }

            // "Going live" connection sweep
            var wasConnected by remember { mutableStateOf(vs.isConnected) }
            val sweepProgress = remember { Animatable(0f) }
            LaunchedEffect(vs.isConnected) {
                if (vs.isConnected && !wasConnected) {
                    sweepProgress.snapTo(0f)
                    sweepProgress.animateTo(1f, tween(800, easing = EaseInOut))
                }
                wasConnected = vs.isConnected
            }

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
                            containerColor = Bg
                        ) { innerPadding ->
                            Box(
                                Modifier.fillMaxSize().padding(innerPadding)
                                    .background(Bg)
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
                                    TabBar(pagerState.currentPage, onSelect = { page ->
                                        pagerScope.launch { pagerState.animateScrollToPage(page) }
                                    })

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
                                    // Auto-dismiss dock on tab change
                                    LaunchedEffect(pagerState.currentPage) { dockOpen = false }

                                    HorizontalPager(
                                        state = pagerState,
                                        modifier = Modifier.weight(1f),
                                        beyondViewportPageCount = 0,
                                        key = { it }
                                    ) { page ->
                                        val pageOffset = (pagerState.currentPage - page) +
                                            pagerState.currentPageOffsetFraction
                                        val alpha = 1f - (kotlin.math.abs(pageOffset) * 0.15f).coerceIn(0f, 0.15f)
                                        Box(Modifier.graphicsLayer { this.alpha = alpha }) {
                                        when (page) {
                                            0 -> DashPage(vs, prefs)
                                            1 -> PowerPage(vs, prefs)
                                            2 -> ChassisPage(vs, prefs, onReset = { service?.resetPeaks() })
                                            3 -> TempsPage(vs, prefs)
                                            4 -> DrivePage(driveState, vs, prefs)
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

                        // "Going live" sweep overlay
                        if (sweepProgress.value in 0.01f..0.99f) {
                            val sweepAccent = prefs.themeAccent
                            Box(
                                Modifier.fillMaxSize().drawBehind {
                                    val y = sweepProgress.value * size.height
                                    val band = 40.dp.toPx()
                                    drawRect(
                                        brush = Brush.verticalGradient(
                                            listOf(
                                                Color.Transparent,
                                                sweepAccent.copy(alpha = 0.12f),
                                                sweepAccent.copy(alpha = 0.06f),
                                                Color.Transparent
                                            ),
                                            startY = y - band,
                                            endY = y + band
                                        )
                                    )
                                }
                            )
                        }
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
// HEADER — F1 Telemetry Bar
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

    Column(Modifier.fillMaxWidth().background(Surf)) {
        // ── Top row: logo | connection pill + trip + settings ────────
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("open", fontSize = 18.sp, fontFamily = OrbitronFamily,
                    color = Frost, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp)
                Text("RS", fontSize = 18.sp, fontFamily = OrbitronFamily,
                    color = accent, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp)
                Text("_", fontSize = 18.sp, fontFamily = OrbitronFamily,
                    color = Frost, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp)
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .background(connColor.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                        .border(1.dp, connColor.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                        .clickable {
                            when {
                                vs.isConnected -> onDisconnect()
                                vs.isIdle      -> onReconnect()
                                else           -> onConnect()
                            }
                        }
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        Box(contentAlignment = Alignment.Center) {
                            Box(Modifier.size(14.dp).clip(CircleShape)
                                .background(connColor.copy(alpha = 0.25f * dotAlpha))
                                .then(if (vs.isConnected) Modifier.bloomGlow(connColor, 10.dp, 0.3f * dotAlpha) else Modifier))
                            Box(Modifier.size(6.dp).clip(CircleShape)
                                .background(connColor.copy(alpha = dotAlpha)))
                        }
                        MonoLabel(connLabel, 8.sp, connColor, FontWeight.Bold, 0.08.sp)
                        // Bluetooth indicator when connected via BLE
                        if (AppSettings.getConnectionMethod(LocalContext.current) == "BLUETOOTH") {
                            MonoLabel("BT", 7.sp, connColor.copy(alpha = 0.6f), FontWeight.Bold, 0.05.sp)
                        }
                    }
                }

                // REC indicator (visible when drive is recording, not paused)
                if (driveState.isRecording && !driveState.isPaused) {
                    val recAlpha by rememberInfiniteTransition(label = "headerRec").animateFloat(
                        initialValue = 1f, targetValue = 0.2f,
                        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse),
                        label = "headerRecAlpha"
                    )
                    Row(
                        Modifier.height(28.dp)
                            .background(Orange.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .border(1.dp, Orange.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(Orange.copy(alpha = recAlpha)))
                        MonoLabel("REC", 8.sp, Orange, FontWeight.Bold, 0.1.sp)
                    }
                }

                Box(
                    Modifier.size(28.dp)
                        .background(Surf2, RoundedCornerShape(6.dp))
                        .border(1.dp, Brd, RoundedCornerShape(6.dp))
                        .clickable { onSettings() },
                    contentAlignment = Alignment.Center
                ) {
                    UIText("⚙", 13.sp, Mid)
                    // Update-available badge dot
                    if (UpdateManager.hasUpdate) {
                        Box(
                            Modifier.align(Alignment.TopEnd)
                                .offset(x = 2.dp, y = (-2).dp)
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(Ok)
                        )
                    }
                }
            }
        }

        // ── Telemetry strip — F1 pit-wall style ─────────────────────
        Row(
            Modifier.fillMaxWidth()
                .background(Surf2)
                .border(width = 1.dp, color = Brd.copy(alpha = 0.5f)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val modeColor = when (vs.driveMode) {
                DriveMode.SPORT -> Ok; DriveMode.TRACK -> Warn; DriveMode.DRIFT -> Orange; else -> accent
            }
            val escColor = when (vs.escStatus) {
                EscStatus.OFF -> Orange; EscStatus.PARTIAL -> Warn; EscStatus.LAUNCH -> Warn; else -> accent
            }
            val eBrakeColor = if (vs.eBrake) Warn else Ok
            val fpsStr = if (vs.isConnected) "${vs.framesPerSecond.toInt()} FPS" else "—"

            // MODE cell — pulsing accent bar (matches tab indicator style)
            val pulseT = rememberInfiniteTransition(label = "modeBar")
            val barAlpha by pulseT.animateFloat(
                initialValue = 0.3f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(2000, easing = EaseInOut), RepeatMode.Reverse
                ), label = "modeBarA"
            )
            Box(
                Modifier.weight(1f)
                    .defaultMinSize(minHeight = 44.dp)
                    .pressClick { onModeClick() },
                contentAlignment = Alignment.Center
            ) {
                TeleCell("MODE", vs.driveMode.label.uppercase(), modeColor, Modifier.fillMaxWidth())
                // Accent bar + glow — same style as TabBar indicator, but pulsing
                Box(Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth(0.6f).height(2.dp)
                    .background(modeColor.copy(alpha = barAlpha),
                        RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                )
                Box(Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth(0.7f).height(6.dp)
                    .background(
                        Brush.verticalGradient(listOf(
                            modeColor.copy(alpha = 0.12f * barAlpha), Color.Transparent
                        )),
                        RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                    )
                )
            }
            TeleDivider()
            TeleCell("ESC", vs.escStatus.label.uppercase(), escColor, Modifier.weight(1f))
            TeleDivider()
            TeleCell("CONN", fpsStr, if (vs.isConnected) Ok else Dim, Modifier.weight(1f))
            TeleDivider()
            TeleCell("IGN", ignitionStatusLabel(vs.ignitionStatus).uppercase(), Frost, Modifier.weight(1f))
            TeleDivider()
            TeleCell("E-BRK", if (vs.eBrake) "ON" else "OFF", eBrakeColor, Modifier.weight(1f))
        }
    }
}

@Composable private fun TeleCell(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Column(
        modifier.padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 7.sp, Dim, letterSpacing = 0.08.sp)
        MonoText(value, 10.sp, valueColor, fontWeight = FontWeight.Bold,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }
}

@Composable private fun TeleDivider() {
    Box(Modifier.width(1.dp).height(22.dp).background(Brd.copy(alpha = 0.4f)))
}

// ═══════════════════════════════════════════════════════════════════════════
// TAB BAR — 6 tabs with icons
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val accent = LocalThemeAccent.current
    val haptic = LocalHapticFeedback.current
    val tabs = listOf(
        "⚡" to "DASH",
        "◈" to "POWER",
        "◎" to "CHASSIS",
        "△" to "TEMPS",
        "◉" to "MAP",
        "≡" to "DIAG",
        "☰" to "MORE"
    )
    val tabCount = tabs.size
    BoxWithConstraints(
        Modifier.fillMaxWidth()
            .background(Surf)
            .border(width = 1.dp, color = Brd, shape = RoundedCornerShape(0.dp))
            .height(52.dp)
    ) {
        val tabWidth = maxWidth / tabCount
        // Sliding neon indicator
        val indicatorOffset by animateDpAsState(
            targetValue = tabWidth * selected,
            animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            label = "tabSlide"
        )
        Row(Modifier.fillMaxSize()) {
            tabs.forEachIndexed { i, (icon, label) ->
                val isActive = i == selected
                Box(
                    Modifier.weight(1f).fillMaxHeight()
                        .clickable { haptic.performHapticFeedback(HapticFeedbackType.Confirm); onSelect(i) },
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                        MonoLabel(icon, 13.sp, if (isActive) accent else Dim)
                        Spacer(Modifier.height(2.dp))
                        MonoLabel(label, 8.sp, if (isActive) accent else Dim, letterSpacing = 0.12.sp)
                    }
                }
            }
        }
        // Sliding accent bar + glow
        Box(
            Modifier.offset(x = indicatorOffset)
                .width(tabWidth).align(Alignment.BottomStart)
        ) {
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth(0.6f).height(2.dp)
                    .background(accent, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
            )
            Box(
                Modifier.align(Alignment.BottomCenter)
                    .fillMaxWidth(0.7f).height(6.dp)
                    .background(
                        Brush.verticalGradient(listOf(accent.copy(alpha = 0.12f), Color.Transparent)),
                        RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp)
                    )
            )
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
                .border(1.dp, Warn.copy(alpha = 0.25f), RoundedCornerShape(8.dp))
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
                .border(1.dp, Orange.copy(alpha = 0.35f), RoundedCornerShape(8.dp))
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
