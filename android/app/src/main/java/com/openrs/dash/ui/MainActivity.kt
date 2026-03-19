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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.CanDataService
import com.openrs.dash.ui.trip.TripPage

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

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray()) else startSvc()

        setContent {
            val vs          by OpenRSDashApp.instance.vehicleState.collectAsState()
            val prefs       by UserPrefsStore.prefs.collectAsState()
            val debugLines  by OpenRSDashApp.instance.debugLines.collectAsState()
            val tripState   by OpenRSDashApp.instance.tripState.collectAsState()
            var tab         by remember { mutableIntStateOf(0) }
            var settingsOpen    by remember { mutableStateOf(false) }
            var showTripOverlay by remember { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }

            val view = LocalView.current
            LaunchedEffect(prefs.screenOn) {
                view.keepScreenOn = prefs.screenOn
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
                                        onTrip       = { showTripOverlay = true }
                                    )
                                    TabBar(tab, onSelect = { tab = it })
                                    Box(Modifier.weight(1f)) {
                                        when (tab) {
                                            0 -> DashPage(vs, prefs)
                                            1 -> PowerPage(vs, prefs)
                                            2 -> ChassisPage(vs, prefs, onReset = { service?.resetPeaks() })
                                            3 -> TempsPage(vs, prefs)
                                            4 -> DiagPage(
                                                debugLines,
                                                vs,
                                                onScanDtcs  = service?.let { svc -> { svc.scanDtcs() } },
                                                onClearDtcs = service?.let { svc -> { svc.clearDtcs() } }
                                            )
                                            5 -> MorePage(vs, prefs, snackbarHostState, onSettings = { settingsOpen = true })
                                        }
                                    }
                                }

                                if (settingsOpen) {
                                    SettingsDialog(onDismiss = { settingsOpen = false })
                                }
                            }
                        }

                        AnimatedVisibility(
                            visible = showTripOverlay,
                            enter   = slideInVertically(initialOffsetY = { it }),
                            exit    = slideOutVertically(targetOffsetY = { it })
                        ) {
                            TripPage(
                                tripState    = tripState,
                                vehicleState = vs,
                                prefs        = prefs,
                                onStartTrip  = { OpenRSDashApp.instance.tripRecorder.startTrip() },
                                onEndTrip    = { OpenRSDashApp.instance.tripRecorder.stopTrip() },
                                onDismiss    = { showTripOverlay = false }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun startSvc() {
        val i = Intent(this, CanDataService::class.java)
        androidx.core.content.ContextCompat.startForegroundService(this, i)
        bindService(i, conn, Context.BIND_AUTO_CREATE)
    }

    override fun onDestroy() {
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// HEADER
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun AppHeader(
    vs: VehicleState,
    prefs: UserPrefs,
    onSettings: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit,
    onTrip: () -> Unit = {}
) {
    val accent = LocalThemeAccent.current
    val modeColor = when (vs.driveMode) {
        DriveMode.SPORT  -> Ok
        DriveMode.TRACK  -> Warn
        DriveMode.DRIFT  -> Red
        else             -> accent
    }

    val infiniteTransition = rememberInfiniteTransition(label = "conn")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f, label = "dot",
        animationSpec = infiniteRepeatable(tween(1000, easing = EaseInOut), RepeatMode.Reverse)
    )
    val connColor = when {
        vs.isConnected -> Ok
        vs.isIdle      -> Warn
        else           -> Red
    }

    Row(
        Modifier.fillMaxWidth()
            .background(Surf)
            .border(width = 1.dp, color = Brd, shape = RoundedCornerShape(0.dp))
            .padding(horizontal = 16.dp, vertical = 9.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            Text(
                "open", fontSize = 18.sp, fontFamily = OrbitronFamily,
                color = Frost, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp
            )
            Text(
                "RS", fontSize = 18.sp, fontFamily = OrbitronFamily,
                color = accent, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp
            )
            Text(
                "_", fontSize = 18.sp, fontFamily = OrbitronFamily,
                color = Frost, fontWeight = FontWeight.Bold, letterSpacing = 0.05.sp
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier
                    .background(modeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .border(1.dp, modeColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                MonoLabel(vs.driveMode.label.uppercase(), 10.sp, modeColor, FontWeight.Bold, 0.1.sp)
            }

            Box(
                Modifier
                    .background(accent.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                    .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                MonoLabel(vs.escStatus.label, 9.sp, accent, letterSpacing = 0.08.sp)
            }

            Box(
                Modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp)
                    .clickable {
                        when {
                            vs.isConnected -> onDisconnect()
                            vs.isIdle      -> onReconnect()
                            else           -> onConnect()
                        }
                    },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier.size(8.dp).clip(CircleShape)
                        .background(connColor.copy(alpha = if (vs.isConnected) dotAlpha else 1f))
                )
            }

            Box(
                Modifier.size(28.dp)
                    .background(Surf2, RoundedCornerShape(6.dp))
                    .border(1.dp, Brd, RoundedCornerShape(6.dp))
                    .clickable { onSettings() },
                contentAlignment = Alignment.Center
            ) {
                UIText("⚙", 13.sp, Mid)
            }

            Box(
                Modifier
                    .height(28.dp)
                    .background(accent.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                    .border(1.dp, accent.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
                    .clickable { onTrip() }
                    .padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel("TRIP", 9.sp, accent, FontWeight.Bold, 0.15.sp)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TAB BAR — 6 tabs with icons
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    val accent = LocalThemeAccent.current
    val tabs = listOf(
        "⚡" to "DASH",
        "◈" to "POWER",
        "◎" to "CHASSIS",
        "△" to "TEMPS",
        "≡" to "DIAG",
        "☰" to "MORE"
    )
    Row(
        Modifier.fillMaxWidth()
            .background(Surf)
            .border(width = 1.dp, color = Brd, shape = RoundedCornerShape(0.dp))
            .height(52.dp)
    ) {
        tabs.forEachIndexed { i, (icon, label) ->
            val isActive = i == selected
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable { onSelect(i) }
                    .background(if (isActive) accent.copy(alpha = 0.05f) else androidx.compose.ui.graphics.Color.Transparent),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    MonoLabel(icon, 13.sp, if (isActive) accent else Dim)
                    Spacer(Modifier.height(2.dp))
                    MonoLabel(label, 8.sp, if (isActive) accent else Dim, letterSpacing = 0.12.sp)
                }
                if (isActive) {
                    Box(
                        Modifier.align(Alignment.BottomCenter)
                            .fillMaxWidth(0.6f).height(2.dp)
                            .background(accent, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                    )
                }
            }
        }
    }
}
