package com.openrs.dash.ui

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.view.WindowManager
import com.openrs.dash.diagnostics.DiagnosticExporter
import com.openrs.dash.diagnostics.DiagnosticLogger
import kotlinx.coroutines.launch
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.R
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.CanDataService
import com.openrs.dash.ui.trip.TripPage
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// DESIGN SYSTEM — F1 PALETTE
// ═══════════════════════════════════════════════════════════════════════════

// Core backgrounds (deep navy-black)
val Bg      = Color(0xFF05070A)
val Surf    = Color(0xFF0A0D12)
val Surf2   = Color(0xFF0F141C)
val Surf3   = Color(0xFF141B26)

// Borders and text
val Brd     = Color(0xFF162030)     // Default border (dark navy)
val Frost   = Color(0xFFE8F4FF)     // Primary text (near-white, blue tint)
val Dim     = Color(0xFF3D5A72)     // Muted / dim text
val Mid     = Color(0xFF7A9AB8)     // Medium emphasis

// Accent colors
val Accent  = Color(0xFF00D2FF)     // Cyan — primary interactive
val AccentD = Color(0xFF0099BB)     // Darker cyan
val Orange  = Color(0xFFFF4D00)     // Orange-red — aggressive/hot
val Ok      = Color(0xFF00FF88)     // Neon green — good/ok
val Warn    = Color(0xFFFFCC00)     // Gold — attention/warm

// Aliases (keep backwards-compat with composables using old names)
val Grn   get() = Ok
val Amber get() = Warn
val Red   get() = Orange

// ── CompositionLocal for theme accent ─────────────────────────────────────
val LocalThemeAccent = compositionLocalOf { Accent }

// ═══════════════════════════════════════════════════════════════════════════
// FONTS
// ═══════════════════════════════════════════════════════════════════════════

val OrbitronFamily = FontFamily(
    Font(R.font.orbitron_regular, FontWeight.Normal),
    Font(R.font.orbitron_bold,    FontWeight.Bold)
)
val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold,    FontWeight.Bold)
)
val ShareTechMono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))
val BarlowCond    = FontFamily(
    Font(R.font.barlow_condensed_regular,  FontWeight.Normal),
    Font(R.font.barlow_condensed_medium,   FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold,     FontWeight.Bold)
)

// ═══════════════════════════════════════════════════════════════════════════
// TYPOGRAPHY HELPERS
// ═══════════════════════════════════════════════════════════════════════════

/** Large hero numeric values — Orbitron Bold */
@Composable fun HeroNum(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color = Frost,
    modifier: Modifier = Modifier
) = Text(
    text, fontSize = fontSize, fontFamily = OrbitronFamily, color = color,
    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
    lineHeight = fontSize * 1.1f, modifier = modifier
)

/** Small monospace labels — JetBrains Mono */
@Composable fun MonoLabel(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color = Dim,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 0.15.sp,
    modifier: Modifier = Modifier
) = Text(
    text, fontSize = fontSize, fontFamily = JetBrainsMonoFamily, color = color,
    fontWeight = fontWeight, letterSpacing = letterSpacing, modifier = modifier
)

/** Monospace readouts (frame console, raw values) — Share Tech Mono */
@Composable fun MonoText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color = Frost,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier
) = Text(
    text, fontSize = fontSize, fontFamily = ShareTechMono, color = color,
    fontWeight = fontWeight, textAlign = textAlign, modifier = modifier
)

/** Body / label text — Barlow Condensed */
@Composable fun UIText(
    text: String,
    fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color = Frost,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 0.sp,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier
) = Text(
    text, fontSize = fontSize, fontFamily = BarlowCond, color = color,
    fontWeight = fontWeight, letterSpacing = letterSpacing, textAlign = textAlign,
    modifier = modifier
)

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
            LaunchedEffect(prefs.screenOn, vs.isConnected) {
                view.keepScreenOn = prefs.screenOn && vs.isConnected
            }

            // Provide theme accent to all child composables
            CompositionLocalProvider(LocalThemeAccent provides prefs.themeAccent) {
                MaterialTheme(
                    colorScheme = darkColorScheme(
                        background = Bg,
                        surface    = Surf,
                        primary    = prefs.themeAccent
                    )
                ) {
                    // Outer Box so TripPage can overlay the entire screen,
                    // outside Scaffold — Scaffold's inset consumption is scoped
                    // to its own content, not to siblings of the Scaffold.
                    Box(Modifier.fillMaxSize()) {
                        Scaffold(
                            snackbarHost  = { SnackbarHost(snackbarHostState) },
                            containerColor = Bg
                        ) { innerPadding ->
                            Box(
                                Modifier.fillMaxSize().padding(innerPadding)
                                    .background(Bg).statusBarsPadding().navigationBarsPadding()
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
                                            4 -> DiagPage(debugLines, vs)
                                            5 -> MorePage(vs, prefs, snackbarHostState, onSettings = { settingsOpen = true })
                                        }
                                    }
                                }

                                // Settings sheet (triggered from header ⚙ or MORE tab)
                                if (settingsOpen) {
                                    SettingsDialog(onDismiss = { settingsOpen = false })
                                }
                            }
                        }

                        // Trip Map overlay — sibling of Scaffold, outside its inset
                        // consumption scope, so TripPage's statusBarsPadding() is reliable.
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
        startService(i)
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

    // Pulsing connection dot
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
        // Logo
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(
                "openRS", fontSize = 18.sp, fontFamily = OrbitronFamily,
                color = Frost, fontWeight = FontWeight.Bold,
                letterSpacing = 0.05.sp
            )
            Text(
                "_", fontSize = 18.sp, fontFamily = OrbitronFamily,
                color = accent, fontWeight = FontWeight.Bold
            )
        }

        // Right side: drive mode badge, ESC, dot, ⚙
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Drive mode badge
            Box(
                Modifier
                    .background(modeColor.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                    .border(1.dp, modeColor.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                MonoLabel(vs.driveMode.label.uppercase(), 10.sp, modeColor, FontWeight.Bold, 0.1.sp)
            }

            // ESC badge
            Box(
                Modifier
                    .background(accent.copy(alpha = 0.08f), RoundedCornerShape(4.dp))
                    .border(1.dp, accent.copy(alpha = 0.25f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 3.dp)
            ) {
                MonoLabel(vs.escStatus.label, 9.sp, accent, letterSpacing = 0.08.sp)
            }

            // Connection dot (tap to connect/disconnect)
            Box(
                Modifier.size(8.dp).clip(CircleShape)
                    .background(connColor.copy(alpha = if (vs.isConnected) dotAlpha else 1f))
                    .clickable {
                        when {
                            vs.isConnected -> onDisconnect()
                            vs.isIdle      -> onReconnect()
                            else           -> onConnect()
                        }
                    }
            )

            // Settings
            Box(
                Modifier.size(28.dp)
                    .background(Surf2, RoundedCornerShape(6.dp))
                    .border(1.dp, Brd, RoundedCornerShape(6.dp))
                    .clickable { onSettings() },
                contentAlignment = Alignment.Center
            ) {
                UIText("⚙", 13.sp, Mid)
            }

            // Trip Map
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
                    .background(
                        if (isActive) accent.copy(alpha = 0.05f) else Color.Transparent
                    ),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    MonoLabel(icon, 13.sp, if (isActive) accent else Dim)
                    Spacer(Modifier.height(2.dp))
                    MonoLabel(label, 8.sp, if (isActive) accent else Dim, letterSpacing = 0.12.sp)
                }
                // Active underline
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

// ═══════════════════════════════════════════════════════════════════════════
// SHARED COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════

/** Section label: small text with extending horizontal rule */
@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(
        modifier.padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MonoLabel(text, 9.sp, Dim, letterSpacing = 0.2.sp)
        Box(Modifier.weight(1f).height(1.dp).background(Brd))
    }
}

/** Data cell — JetBrains Mono label + value */
@Composable fun DataCell(
    label: String,
    value: String,
    valueColor: Color = Frost,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, Brd, RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.15.sp)
        Spacer(Modifier.height(3.dp))
        MonoText(value, 14.sp, valueColor)
    }
}

/** Hero card — large Orbitron number for BOOST / SPEED / RPM */
@Composable fun HeroCard(
    unit: String,
    value: String,
    label: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
    borderAccent: Color? = null
) {
    val accent = LocalThemeAccent.current
    val brd = borderAccent ?: Brd
    Column(
        modifier
            .background(Surf2, RoundedCornerShape(16.dp))
            .border(1.dp, brd, RoundedCornerShape(16.dp))
            .padding(horizontal = 8.dp, vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(unit, 8.sp, Dim, letterSpacing = 0.18.sp)
        Spacer(Modifier.height(4.dp))
        HeroNum(value, 26.sp, valueColor, Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.15.sp)
    }
}

/** Bar card — label + value + gradient progress bar */
@Composable fun BarCard(
    name: String,
    value: String,
    fraction: Float,
    barBrush: Brush,
    modifier: Modifier = Modifier
) {
    Column(
        modifier
            .background(Surf2, RoundedCornerShape(12.dp))
            .border(1.dp, Brd, RoundedCornerShape(12.dp))
            .padding(12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            MonoLabel(name, 9.sp, Dim, letterSpacing = 0.15.sp)
            MonoText(value, 13.sp, Frost)
        }
        Spacer(Modifier.height(7.dp))
        Box(
            Modifier.fillMaxWidth().height(4.dp)
                .background(Surf3, RoundedCornerShape(2.dp))
        ) {
            Box(
                Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight()
                    .background(barBrush, RoundedCornerShape(2.dp))
            )
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// DASH PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun DashPage(vs: VehicleState, p: UserPrefs) {
    val accent = LocalThemeAccent.current
    val (boostVal, boostLbl) = p.displayBoost(vs.boostKpa)
    val brakeStr = "%.0f".format(vs.brakePressure.coerceIn(0.0, 100.0))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // ── Hero Row: BOOST | SPEED | RPM ──────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeroCard(
                unit = boostLbl, value = boostVal, label = "BOOST",
                valueColor = Warn,
                borderAccent = Warn.copy(alpha = 0.25f),
                modifier = Modifier.weight(1f)
            )
            HeroCard(
                unit = "RPM", value = "${vs.rpm.toInt()}", label = "ENGINE",
                valueColor = Red,
                borderAccent = Red.copy(alpha = 0.2f),
                modifier = Modifier.weight(1f)
            )
            HeroCard(
                unit = p.speedLabel, value = p.displaySpeed(vs.speedKph), label = "SPEED",
                valueColor = accent,
                borderAccent = accent.copy(alpha = 0.25f),
                modifier = Modifier.weight(1f)
            )
        }

        // ── Inputs & Resources bar grid ─────────────────────────────────────
        SectionLabel("INPUTS & RESOURCES")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarCard(
                name = "THROTTLE", value = "${vs.throttlePct.roundToInt()}%",
                fraction = (vs.throttlePct / 100.0).toFloat(),
                barBrush = Brush.horizontalGradient(listOf(accent.copy(0.4f), accent)),
                modifier = Modifier.weight(1f)
            )
            BarCard(
                name = "BRAKE", value = "$brakeStr%",
                fraction = (vs.brakePressure / 100.0).toFloat().coerceIn(0f, 1f),
                barBrush = Brush.horizontalGradient(listOf(Red.copy(0.4f), Red)),
                modifier = Modifier.weight(1f)
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            BarCard(
                name = "FUEL", value = "${vs.fuelLevelPct.roundToInt()}%",
                fraction = (vs.fuelLevelPct / 100.0).toFloat(),
                barBrush = Brush.horizontalGradient(listOf(Ok.copy(0.4f), Ok)),
                modifier = Modifier.weight(1f)
            )
            BarCard(
                name = "BATTERY", value = "${"%.1f".format(vs.batteryVoltage)}V",
                fraction = ((vs.batteryVoltage - 10.0) / 6.0).toFloat().coerceIn(0f, 1f),
                barBrush = Brush.horizontalGradient(listOf(Warn.copy(0.4f), Warn)),
                modifier = Modifier.weight(1f)
            )
        }

        // ── AWD Split ──────────────────────────────────────────────────────
        SectionLabel("AWD SPLIT")
        AwdSplitBar(vs)

        // ── Temps Quick ────────────────────────────────────────────────────
        SectionLabel("TEMPS QUICK")
        val oilColor  = tempColorShade(vs.oilTempC, p.oilWarnC, p.oilCritC)
        val coolColor = tempColorShade(vs.coolantTempC, p.coolWarnC, p.coolCritC)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("OIL",     "${p.displayTemp(vs.oilTempC)}${p.tempLabel}",     valueColor = oilColor,  modifier = Modifier.weight(1f))
            DataCell("COOLANT", "${p.displayTemp(vs.coolantTempC)}${p.tempLabel}", valueColor = coolColor, modifier = Modifier.weight(1f))
            DataCell("INTAKE",  "${p.displayTemp(vs.intakeTempC)}${p.tempLabel}",  modifier = Modifier.weight(1f))
            DataCell("OIL LIFE", if (vs.oilLifePct >= 0) "${vs.oilLifePct.roundToInt()}%" else "—", modifier = Modifier.weight(1f))
        }

        // ── G-Force mini ──────────────────────────────────────────────────
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("LAT G",  "${"%.2f".format(vs.lateralG)}g",      modifier = Modifier.weight(1f))
            DataCell("LON G",  "${"%.2f".format(vs.longitudinalG)}g", modifier = Modifier.weight(1f))
            DataCell("TORQUE", "${vs.torqueAtTrans.roundToInt()} Nm",  modifier = Modifier.weight(1f))
        }

        // ── Odometer toggle ───────────────────────────────────────────────
        var odomInMiles by remember { mutableStateOf(false) }
        val odomLabel = if (odomInMiles) "ODO (mi)" else "ODO (km)"
        val odomValue = when {
            vs.odometerKm < 0 -> "—"
            odomInMiles       -> "${"%.0f".format(vs.odometerKm * 0.621371)} mi"
            else              -> "${"%.0f".format(vs.odometerKm.toDouble())} km"
        }
        Box(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(12.dp))
                .border(1.dp, Brd, RoundedCornerShape(12.dp))
                .clickable(enabled = vs.odometerKm >= 0) { odomInMiles = !odomInMiles }
                .padding(horizontal = 14.dp, vertical = 10.dp)
        ) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoLabel(odomLabel, 9.sp, Dim, letterSpacing = 0.15.sp)
                MonoText(odomValue, 16.sp, if (vs.odometerKm >= 0) Frost else Dim)
            }
            if (vs.odometerKm >= 0) {
                MonoLabel("tap to toggle", 8.sp, Dim,
                    modifier = Modifier.align(Alignment.CenterEnd).padding(end = 74.dp))
            }
        }
    }
}

@Composable fun AwdSplitBar(vs: VehicleState) {
    val accent = LocalThemeAccent.current
    val rearPct   = vs.rearTorquePct.coerceIn(0.0, 100.0).toFloat()
    val frontPct  = (100f - rearPct).coerceIn(0.01f, 99.99f)
    val rearF     = rearPct.coerceIn(0.01f, 99.99f)

    Column(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(12.dp))
            .border(1.dp, Brd, RoundedCornerShape(12.dp))
            .padding(14.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                MonoLabel("FRONT", 8.sp, Dim, letterSpacing = 0.12.sp)
                HeroNum("${(100 - rearPct).roundToInt()}%", 18.sp, accent)
            }
            Row(
                Modifier.weight(1f).padding(horizontal = 12.dp).height(10.dp)
                    .background(Surf3, RoundedCornerShape(5.dp))
            ) {
                Box(Modifier.weight(frontPct).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(accent, accent.copy(0.5f))),
                        RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp)))
                Box(Modifier.weight(rearF).fillMaxHeight()
                    .background(Brush.horizontalGradient(listOf(Ok.copy(0.5f), Ok)),
                        RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp)))
            }
            Column(horizontalAlignment = Alignment.End) {
                MonoLabel("REAR", 8.sp, Dim, letterSpacing = 0.12.sp)
                HeroNum("${rearPct.roundToInt()}%", 18.sp, Ok)
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoText("L ${vs.awdLeftTorque.roundToInt()} Nm", 11.sp, accent)
            MonoLabel(vs.frontRearSplit, 10.sp, Mid)
            MonoText("${vs.awdRightTorque.roundToInt()} Nm R", 11.sp, Ok)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// POWER PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun PowerPage(vs: VehicleState, p: UserPrefs) {
    val accent = LocalThemeAccent.current
    val hasAfr = vs.afrActual > 0
    val ph = "— —"

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // AFR hero 3 cards
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfrCard("AFR ACT",  if (hasAfr) "%.2f".format(vs.afrActual)   else ph, ":1",
                if (hasAfr) accent else Dim, Modifier.weight(1f))
            AfrCard("AFR DES",  if (hasAfr) "%.2f".format(vs.afrDesired)  else ph, ":1",
                if (hasAfr) Frost else Dim,  Modifier.weight(1f))
            AfrCard("LAMBDA",   if (hasAfr) "%.3f".format(vs.lambdaActual) else ph, "λ",
                if (hasAfr) Ok else Dim,     Modifier.weight(1f))
        }

        SectionLabel("THROTTLE & BOOST")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("ETC ACT", if (vs.etcAngleActual > 0) "${"%.1f".format(vs.etcAngleActual)}°" else ph, modifier = Modifier.weight(1f))
            DataCell("ETC DES", if (vs.etcAngleDesired > 0) "${"%.1f".format(vs.etcAngleDesired)}°" else ph, modifier = Modifier.weight(1f))
            DataCell("WGDC",    if (vs.wgdcDesired > 0) "${"%.0f".format(vs.wgdcDesired)}%" else ph, valueColor = accent, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val (tipActVal, tipLbl) = p.displayBoost(vs.tipActualKpa)
            val (tipDesVal, _)      = p.displayBoost(vs.tipDesiredKpa)
            val hasTip = vs.tipActualKpa > 50
            DataCell("TIP ACT", if (hasTip) "$tipActVal $tipLbl" else ph, modifier = Modifier.weight(1f))
            DataCell("TIP DES", if (hasTip) "$tipDesVal $tipLbl" else ph, modifier = Modifier.weight(1f))
            DataCell("HP FUEL", if (vs.hpFuelRailPsi >= 0) "${"%.0f".format(vs.hpFuelRailPsi)} PSI" else ph, modifier = Modifier.weight(1f))
        }

        SectionLabel("ENGINE MANAGEMENT")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("TIMING", if (vs.timingAdvance != 0.0) "${"%.1f".format(vs.timingAdvance)}°" else ph, modifier = Modifier.weight(1f))
            DataCell("LOAD",   if (vs.calcLoad > 0) "${"%.0f".format(vs.calcLoad)}%" else ph,              modifier = Modifier.weight(1f))
            DataCell("OAR",    if (vs.octaneAdjustRatio != 0.0) "${"%.0f".format(vs.octaneAdjustRatio * 100)}%" else ph, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val krColor = if (vs.ignCorrCyl1 < -1.0) Warn else Ok
            DataCell("KR CYL1", if (vs.ignCorrCyl1 != 0.0) "${"%.2f".format(vs.ignCorrCyl1)}°" else ph, valueColor = krColor, modifier = Modifier.weight(1f))
            DataCell("VCT-I",   if (vs.vctIntakeAngle != 0.0) "${"%.1f".format(vs.vctIntakeAngle)}°" else ph, modifier = Modifier.weight(1f))
            DataCell("VCT-E",   if (vs.vctExhaustAngle != 0.0) "${"%.1f".format(vs.vctExhaustAngle)}°" else ph, modifier = Modifier.weight(1f))
        }

        SectionLabel("FUEL TRIMS & AFR")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val stftColor = fuelTrimColor(vs.shortFuelTrim)
            val ltftColor = fuelTrimColor(vs.longFuelTrim)
            DataCell("SHORT FT", if (vs.shortFuelTrim != 0.0) "${"%.1f".format(vs.shortFuelTrim)}%" else ph, valueColor = stftColor, modifier = Modifier.weight(1f))
            DataCell("LONG FT",  if (vs.longFuelTrim != 0.0) "${"%.1f".format(vs.longFuelTrim)}%" else ph,  valueColor = ltftColor, modifier = Modifier.weight(1f))
            DataCell("BARO",     "${vs.barometricPressure.roundToInt()} kPa", modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("OIL LIFE", if (vs.oilLifePct >= 0) "${vs.oilLifePct.roundToInt()}%" else ph, modifier = Modifier.weight(1f))
            DataCell("AFR SEN1", if (vs.afrSensor1 > 0) "${"%.2f".format(vs.afrSensor1)}" else ph, modifier = Modifier.weight(1f))
            DataCell("O2 VOLT",  if (vs.o2Voltage > 0) "${"%.3f".format(vs.o2Voltage)}V" else ph,  modifier = Modifier.weight(1f))
        }
    }
}

@Composable fun AfrCard(label: String, value: String, unit: String, valueColor: Color, modifier: Modifier) {
    Column(
        modifier
            .background(Surf2, RoundedCornerShape(12.dp))
            .border(1.dp, Brd, RoundedCornerShape(12.dp))
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(4.dp))
        HeroNum(value, 22.sp, valueColor, Modifier.fillMaxWidth())
        MonoLabel(unit, 9.sp, Dim)
    }
}

private fun fuelTrimColor(trim: Double) = when {
    trim > 10.0  -> Warn
    trim < -10.0 -> Red
    else         -> Ok
}

// ═══════════════════════════════════════════════════════════════════════════
// CHASSIS PAGE (AWD + G-Force + TPMS)
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun ChassisPage(vs: VehicleState, p: UserPrefs, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GForceSection(vs, onReset)
        AwdSection(vs, p)
        TpmsSection(vs, p)
    }
}

@Composable fun AwdSection(vs: VehicleState, p: UserPrefs) {
    val accent = LocalThemeAccent.current
    val total    = vs.totalRearTorque
    val leftPct  = if (total > 0) (vs.awdLeftTorque / total).toFloat() else 0.5f
    val rightPct = (1f - leftPct).coerceIn(0.01f, 0.99f)
    val leftPctC = leftPct.coerceIn(0.01f, 0.99f)
    val avgF = (vs.wheelSpeedFL + vs.wheelSpeedFR) / 2
    val avgR = (vs.wheelSpeedRL + vs.wheelSpeedRR) / 2
    val frDelta = avgR - avgF
    val lrDelta = vs.wheelSpeedRR - vs.wheelSpeedRL

    Column(
        Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(16.dp))
            .border(1.dp, Brd, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SectionLabel("AWD — GKN TWINSTER")

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WheelCell("FL", "${"%.1f".format(vs.wheelSpeedFL)}", front = true)
                WheelCell("RL", "${"%.1f".format(vs.wheelSpeedRL)}", front = false)
            }
            Column(Modifier.weight(0.8f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                HeroNum(vs.frontRearSplit, 20.sp, accent)
                MonoLabel("F / R", 8.sp, Dim, letterSpacing = 0.1.sp)
                Spacer(Modifier.height(4.dp))
                val rduStr = if (vs.rduTempC > -90) "${p.displayTemp(vs.rduTempC)}${p.tempLabel}" else "—"
                MonoLabel("RDU", 8.sp, Dim, letterSpacing = 0.1.sp)
                MonoText(rduStr, 12.sp, Mid)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                WheelCell("FR", "${"%.1f".format(vs.wheelSpeedFR)}", front = true)
                WheelCell("RR", "${"%.1f".format(vs.wheelSpeedRR)}", front = false)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoText("L ${vs.awdLeftTorque.roundToInt()} Nm", 10.sp, accent)
            MonoText("${vs.awdRightTorque.roundToInt()} Nm R", 10.sp, Ok)
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth().height(8.dp).background(Surf3, RoundedCornerShape(4.dp))) {
            Box(Modifier.weight(leftPctC).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(accent, accent.copy(0.4f))),
                    RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
            Box(Modifier.weight(rightPct).fillMaxHeight()
                .background(Brush.horizontalGradient(listOf(Ok.copy(0.4f), Ok)),
                    RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("REAR BIAS", vs.rearLeftRightBias, modifier = Modifier.weight(1f))
            DataCell("L/R DELTA", "${"%.1f".format(lrDelta)} km/h", modifier = Modifier.weight(1f))
            DataCell("F/R DELTA", "${"%.1f".format(frDelta)} km/h", modifier = Modifier.weight(1f))
        }
    }
}

@Composable fun WheelCell(label: String, speed: String, front: Boolean) {
    val accent = LocalThemeAccent.current
    val borderColor = if (front) accent.copy(alpha = 0.35f) else Ok.copy(alpha = 0.3f)
    Column(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, borderColor, RoundedCornerShape(10.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 9.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(2.dp))
        MonoText(speed, 16.sp, Frost)
    }
}

@Composable fun GForceSection(vs: VehicleState, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(16.dp))
            .border(1.dp, Brd, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SectionLabel("G-FORCE & DYNAMICS")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GfCard("LAT G",    "${"%.2f".format(vs.lateralG)}",      "▲ ${"%.2f".format(vs.peakLateralG)}",    Modifier.weight(1f))
            GfCard("LON G",    "${"%.2f".format(vs.longitudinalG)}", "▲ ${"%.2f".format(vs.peakLongitudinalG)}", Modifier.weight(1f))
            GfCard("COMBINED", "${"%.2f".format(vs.combinedG)}",     "", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GfCard("YAW",   "${"%.1f".format(vs.yawRate)}°/s", "", Modifier.weight(1f))
            GfCard("STEER", "${"%.1f".format(vs.steeringAngle)}°",  "", Modifier.weight(1f))
            Box(
                Modifier.weight(1f)
                    .background(Surf2, RoundedCornerShape(10.dp))
                    .border(1.dp, Brd, RoundedCornerShape(10.dp))
                    .clickable { onReset() }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel("↺ PEAKS", 10.sp, Dim, letterSpacing = 0.15.sp)
            }
        }
    }
}

@Composable fun GfCard(label: String, value: String, peak: String, modifier: Modifier) {
    val accent = LocalThemeAccent.current
    Column(
        modifier
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, Brd, RoundedCornerShape(10.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 8.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(3.dp))
        MonoText(value, 18.sp, Frost, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (peak.isNotEmpty()) {
            MonoText(peak, 9.sp, accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable fun TpmsSection(vs: VehicleState, p: UserPrefs) {
    Column(
        Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(16.dp))
            .border(1.dp, Brd, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SectionLabel("TPMS — TIRE PRESSURE")

        if (!vs.hasTpmsData) {
            Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CarOutlinePlaceholder()
                    Spacer(Modifier.height(12.dp))
                    MonoLabel("WAITING FOR SENSOR DATA", 10.sp, Dim, letterSpacing = 0.15.sp)
                    Spacer(Modifier.height(4.dp))
                    MonoLabel("Sensors transmit when wheels are rolling", 9.sp, Dim.copy(alpha = 0.7f))
                }
            }
        } else {
            val lowThreshold = p.tireLowPsi.toDouble()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TireCard("LF", vs.tirePressLF, p, lowThreshold)
                    TireCard("LR", vs.tirePressLR, p, lowThreshold)
                }
                Box(Modifier.width(56.dp), contentAlignment = Alignment.Center) {
                    CarOutlinePlaceholder(compact = true)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    TireCard("RF", vs.tirePressRF, p, lowThreshold)
                    TireCard("RR", vs.tirePressRR, p, lowThreshold)
                }
            }
            if (vs.anyTireLow(lowThreshold)) {
                Spacer(Modifier.height(10.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .background(Red.copy(alpha = 0.1f), RoundedCornerShape(8.dp))
                        .border(1.dp, Red.copy(alpha = 0.5f), RoundedCornerShape(8.dp))
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("⚠ LOW TIRE PRESSURE", 10.sp, Red, letterSpacing = 0.2.sp)
                }
            }
        }
    }
}

@Composable fun TireCard(label: String, psi: Double, p: UserPrefs, lowThreshold: Double) {
    val isLow     = psi in 0.0..(lowThreshold - 0.001)
    val isMissing = psi < 0
    val tireColor = when {
        isMissing -> Dim
        isLow     -> Red
        psi > 40.0 -> Warn
        else      -> Ok
    }
    Column(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, if (isLow) Red.copy(0.5f) else Brd, RoundedCornerShape(10.dp))
            .padding(8.dp, 7.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        MonoLabel(label, 9.sp, Dim, letterSpacing = 0.12.sp)
        Spacer(Modifier.height(4.dp))
        HeroNum(if (isMissing) "—" else p.displayTire(psi), 18.sp, tireColor)
        MonoLabel(p.tireLabel, 8.sp, Dim, letterSpacing = 0.1.sp)
    }
}

@Composable fun CarOutlinePlaceholder(compact: Boolean = false) {
    val w = if (compact) 48.dp else 72.dp
    val h = if (compact) 96.dp else 128.dp
    Box(Modifier.width(w).height(h), contentAlignment = Alignment.Center) {
        Box(Modifier.width(w * 0.7f).height(h * 0.7f)
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, Brd.copy(alpha = 0.7f), RoundedCornerShape(10.dp)))
        Box(Modifier.size(w * 0.18f, h * 0.14f).offset(x = -w * 0.4f, y = -h * 0.28f)
            .background(Surf3, RoundedCornerShape(3.dp)).border(1.dp, Dim, RoundedCornerShape(3.dp)))
        Box(Modifier.size(w * 0.18f, h * 0.14f).offset(x = w * 0.4f, y = -h * 0.28f)
            .background(Surf3, RoundedCornerShape(3.dp)).border(1.dp, Dim, RoundedCornerShape(3.dp)))
        Box(Modifier.size(w * 0.18f, h * 0.14f).offset(x = -w * 0.4f, y = h * 0.28f)
            .background(Surf3, RoundedCornerShape(3.dp)).border(1.dp, Dim, RoundedCornerShape(3.dp)))
        Box(Modifier.size(w * 0.18f, h * 0.14f).offset(x = w * 0.4f, y = h * 0.28f)
            .background(Surf3, RoundedCornerShape(3.dp)).border(1.dp, Dim, RoundedCornerShape(3.dp)))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TEMPS PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun TempsPage(vs: VehicleState, p: UserPrefs) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // Warm-up / RTR banner
        RtrBanner(vs, p)

        // Temperature preset indicator
        TempPresetBadge(p)

        // Temp grid 2-col
        SectionLabel("TEMPERATURES")
        val tempItems = listOf(
            TempSpec("ENGINE OIL",    p.displayTemp(vs.oilTempC),     p.tempLabel, vs.oilTempC,
                p.oilWarnC, p.oilCritC, "INFERRED"),
            TempSpec("COOLANT",       p.displayTemp(vs.coolantTempC), p.tempLabel, vs.coolantTempC,
                p.coolWarnC, p.coolCritC, ""),
            TempSpec("INTAKE AIR",    p.displayTemp(vs.intakeTempC),  p.tempLabel, vs.intakeTempC,
                p.intakeWarnC, p.intakeCritC, ""),
            TempSpec("AMBIENT",       p.displayTemp(vs.ambientTempC), p.tempLabel, vs.ambientTempC,
                40.0, 50.0, ""),
            TempSpec("RDU (REAR)",
                if (vs.rduTempC > -90) p.displayTemp(vs.rduTempC) else "— —", p.tempLabel,
                vs.rduTempC.takeIf { it > -90 } ?: 0.0,
                p.rduWarnC, p.rduCritC, if (vs.rduTempC <= -90) "POLLING" else ""),
            // M-8 fix: show "— —" and "POLLING" until first 0x0F8 frame sets the sentinel
            TempSpec("PTU (TRANSFER)",
                if (vs.ptuTempC > -90) p.displayTemp(vs.ptuTempC) else "— —", p.tempLabel,
                vs.ptuTempC.takeIf { it > -90 } ?: 0.0,
                p.rduWarnC, p.rduCritC, if (vs.ptuTempC <= -90) "POLLING" else ""),
            TempSpec("CHARGE AIR",
                if (vs.chargeAirTempC != 0.0) p.displayTemp(vs.chargeAirTempC) else "— —", p.tempLabel,
                vs.chargeAirTempC, 60.0, 80.0, if (vs.chargeAirTempC == 0.0) "POLLING" else ""),
            TempSpec("CATALYTIC",
                if (vs.catalyticTempC != 0.0) p.displayTemp(vs.catalyticTempC) else "— —", p.tempLabel,
                vs.catalyticTempC, 700.0, 800.0, if (vs.catalyticTempC == 0.0) "POLLING" else ""),
            TempSpec("CABIN",
                if (vs.cabinTempC > -90) p.displayTemp(vs.cabinTempC) else "— —", p.tempLabel,
                vs.cabinTempC.takeIf { it > -90 } ?: 0.0, 35.0, 45.0, "BCM"),
            TempSpec("BATT TEMP",
                if (vs.batteryTempC > -90) p.displayTemp(vs.batteryTempC) else "— —", p.tempLabel,
                vs.batteryTempC.takeIf { it > -90 } ?: 0.0, 40.0, 60.0, "BCM"),
        )
        tempItems.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                pair.forEach { spec -> TempCard(spec, Modifier.weight(1f)) }
                if (pair.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

data class TempSpec(
    val label: String, val value: String, val unit: String,
    val tempC: Double, val warnC: Double, val critC: Double, val sub: String
)

@Composable fun RtrBanner(vs: VehicleState, p: UserPrefs) {
    val isReady = p.isRaceReady(vs.oilTempC, vs.coolantTempC)
    val dotColor = if (isReady) Ok else Warn
    val infiniteTransition = rememberInfiniteTransition(label = "rtr")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = if (isReady) 1f else 0.3f, label = "rtrDot",
        animationSpec = infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse)
    )
    val bannerBrush = if (isReady)
        Brush.horizontalGradient(listOf(Ok.copy(alpha = 0.08f), Ok.copy(alpha = 0.04f)))
    else
        Brush.horizontalGradient(listOf(Warn.copy(alpha = 0.08f), Warn.copy(alpha = 0.04f)))
    val bannerBorder = if (isReady) Ok.copy(alpha = 0.2f) else Warn.copy(alpha = 0.2f)

    Row(
        Modifier.fillMaxWidth()
            .background(bannerBrush, RoundedCornerShape(12.dp))
            .border(1.dp, bannerBorder, RoundedCornerShape(12.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(dotColor.copy(alpha = dotAlpha)))
        Spacer(Modifier.width(12.dp))
        Column {
            UIText(
                if (isReady) "RACE READY" else "WARMING UP — NOT RACE READY",
                13.sp, Frost, FontWeight.SemiBold, 0.5.sp
            )
            if (!isReady) {
                val oilMin = when (p.tempPreset) { "race" -> 85.0; "track" -> 80.0; else -> 70.0 }
                val coolMin = when (p.tempPreset) { "race" -> 80.0; "track" -> 75.0; else -> 70.0 }
                MonoLabel(
                    "Oil ${p.displayTemp(vs.oilTempC)}${p.tempLabel} < ${p.displayTemp(oilMin)}  " +
                    "· Coolant ${p.displayTemp(vs.coolantTempC)}${p.tempLabel} < ${p.displayTemp(coolMin)}",
                    9.sp, Warn, modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable fun TempPresetBadge(p: UserPrefs) {
    val ctx = LocalContext.current
    Row(
        Modifier.fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, Brd, RoundedCornerShape(10.dp))
            .padding(12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            MonoLabel("THRESHOLD PRESET", 8.sp, Dim, letterSpacing = 0.15.sp)
            Spacer(Modifier.height(2.dp))
            UIText(p.tempPresetName, 14.sp, Frost, FontWeight.SemiBold)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("street" to "STREET", "track" to "TRACK", "race" to "RACE").forEach { (id, label) ->
                val isActive = p.tempPreset == id
                val color    = when (id) { "race" -> Red; "track" -> Warn; else -> Ok }
                Box(
                    Modifier
                        .background(
                            if (isActive) color.copy(alpha = 0.15f) else Surf3,
                            RoundedCornerShape(6.dp)
                        )
                        .border(1.dp, if (isActive) color.copy(alpha = 0.5f) else Brd, RoundedCornerShape(6.dp))
                        .clickable { UserPrefsStore.update(ctx) { it.copy(tempPreset = id) } }
                        .padding(horizontal = 10.dp, vertical = 5.dp)
                ) {
                    MonoLabel(label, 9.sp, if (isActive) color else Dim, letterSpacing = 0.1.sp)
                }
            }
        }
    }
}

@Composable fun TempCard(spec: TempSpec, modifier: Modifier) {
    val tempColor = tempColorShade(spec.tempC, spec.warnC, spec.critC)
    val barPct = if (spec.critC > 0) (spec.tempC / spec.critC).toFloat().coerceIn(0f, 1f) else 0f
    val barColor = when {
        spec.tempC <= 0         -> Surf3
        spec.tempC < spec.warnC -> Ok.copy(alpha = 0.6f)
        spec.tempC < spec.critC -> Warn.copy(alpha = 0.7f)
        else                    -> Red
    }
    val isPlaceholder = spec.value == "— —"

    Box(
        modifier
            .background(Surf2, RoundedCornerShape(14.dp))
            .border(1.dp, Brd, RoundedCornerShape(14.dp))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(spec.label, 8.sp, Dim, letterSpacing = 0.12.sp)
                if (spec.sub.isNotEmpty()) {
                    MonoLabel(spec.sub, 7.sp, Dim.copy(alpha = 0.7f))
                }
            }
            Spacer(Modifier.height(6.dp))
            if (isPlaceholder) {
                MonoText("— —", 24.sp, Dim)
            } else {
                HeroNum(spec.value, 24.sp, tempColor)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(spec.unit, 10.sp, Dim)
            }
            Spacer(Modifier.height(8.dp))
            // Threshold bar
            Box(
                Modifier.fillMaxWidth().height(3.dp)
                    .background(Surf3, RoundedCornerShape(2.dp))
            ) {
                if (!isPlaceholder && barPct > 0) {
                    Box(Modifier.fillMaxWidth(barPct).fillMaxHeight()
                        .background(barColor, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}

private fun tempColorShade(c: Double, warnC: Double, critC: Double) = when {
    c <= 0      -> Dim
    c >= critC  -> Red
    c >= warnC  -> Warn
    c >= warnC * 0.6 -> Ok
    else        -> Frost
}

// ═══════════════════════════════════════════════════════════════════════════
// DIAG PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun DiagPage(lines: List<String>, vs: VehicleState) {
    val ctx   = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = LocalThemeAccent.current
    var exporting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp)) {
        SectionLabel("DIAGNOSTICS")
        Spacer(Modifier.height(4.dp))

        val sessionMs  = DiagnosticLogger.sessionDurationMs
        val frameCount = DiagnosticLogger.frameInventorySnapshot.values.sumOf { it.totalReceived }
        val issueCount = DiagnosticLogger.frameInventorySnapshot.values.sumOf { it.validationIssues.size }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataCell("FPS",    "${vs.framesPerSecond.roundToInt()}", modifier = Modifier.weight(1f))
            DataCell("STATUS", if (vs.isConnected) "LIVE" else "OFF",
                valueColor = if (vs.isConnected) Ok else Red, modifier = Modifier.weight(1f))
            DataCell("MODE",   vs.dataMode, modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DataCell("SESSION", DiagnosticLogger.formatDuration(sessionMs), modifier = Modifier.weight(1f))
            DataCell("FRAMES",  "$frameCount",                              modifier = Modifier.weight(1f))
            DataCell("IDs",     "${DiagnosticLogger.frameInventorySnapshot.size}", modifier = Modifier.weight(1f))
        }

        if (issueCount > 0) {
            Spacer(Modifier.height(6.dp))
            MonoLabel("⚠ $issueCount validation issue(s) — capture snapshot to review", 9.sp, Warn)
        }

        Spacer(Modifier.height(12.dp))
        Box(
            Modifier.fillMaxWidth()
                .background(
                    if (!exporting) Brush.horizontalGradient(listOf(accent.copy(0.1f), accent.copy(0.05f)))
                    else Brush.horizontalGradient(listOf(Dim.copy(0.1f), Dim.copy(0.05f))),
                    RoundedCornerShape(10.dp)
                )
                .border(1.dp, if (!exporting) accent.copy(0.3f) else Dim.copy(0.3f), RoundedCornerShape(10.dp))
                .clickable(enabled = !exporting) {
                    exporting = true
                    // M-6 fix: run ZIP export on IO dispatcher to avoid ANR on large sessions
                    scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                        DiagnosticExporter.share(ctx)
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { exporting = false }
                    }
                }
                .padding(vertical = 13.dp),
            contentAlignment = Alignment.Center
        ) {
            MonoLabel(
                if (exporting) "BUILDING..." else "↑  CAPTURE & SHARE SNAPSHOT",
                12.sp, if (!exporting) accent else Dim, letterSpacing = 0.1.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        MonoLabel("Exports ZIP (summary + raw log + JSON) via share sheet.", 9.sp, Dim,
            modifier = Modifier.padding(bottom = 12.dp))

        HorizontalDivider(color = Brd)
        Spacer(Modifier.height(10.dp))
        SectionLabel("LIVE CAN OUTPUT")
        Spacer(Modifier.height(4.dp))

        // Console box
        Column(
            Modifier.fillMaxWidth()
                .background(Color(0xFF060810), RoundedCornerShape(10.dp))
                .border(1.dp, Brd, RoundedCornerShape(10.dp))
                .padding(10.dp)
        ) {
            val displayLines = lines.takeLast(20)
            if (displayLines.isEmpty() && vs.isConnected) {
                MonoLabel("Connected — waiting for first CAN frame...", 10.sp, Warn)
            } else if (displayLines.isEmpty()) {
                MonoLabel("Connect to WiCAN to see raw output.", 10.sp, Dim)
            } else {
                displayLines.forEach { line ->
                    val parts = line.trim().split(" ", limit = 2)
                    Row(Modifier.padding(vertical = 1.dp)) {
                        if (parts.size >= 2) {
                            MonoLabel(parts[0], 10.sp, Warn, letterSpacing = 0.05.sp)
                            Spacer(Modifier.width(12.dp))
                            MonoText(parts[1], 10.sp, Mid)
                        } else {
                            MonoText(line, 10.sp, Mid)
                        }
                    }
                }
            }
        }

        val inv = DiagnosticLogger.frameInventorySnapshot
        if (inv.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            SectionLabel("FRAME INVENTORY (${inv.size} IDs)")
            Spacer(Modifier.height(4.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(10.dp))
                    .border(1.dp, Brd, RoundedCornerShape(10.dp))
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                inv.entries.sortedBy { it.key }.forEach { (id, info) ->
                    val decoded  = if (info.lastDecoded.isEmpty()) "(no decoder)" else info.lastDecoded
                    val issColor = if (info.validationIssues.isNotEmpty()) Warn else Mid
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        MonoText("0x%03X".format(id), 9.sp, accent)
                        MonoText("×${info.totalReceived}", 9.sp, Dim)
                        MonoText(decoded.take(32), 9.sp, issColor, modifier = Modifier.weight(1f).padding(start = 8.dp))
                    }
                    info.validationIssues.forEach { issue ->
                        MonoLabel("  ⚠ $issue", 8.sp, Warn)
                    }
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// MORE PAGE (replaces drawer — Drive Mode, ESC, Features, Themes, Settings)
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun MorePage(
    vs: VehicleState,
    p: UserPrefs,
    snackbarHostState: SnackbarHostState,
    onSettings: () -> Unit
) {
    val isFw  by OpenRSDashApp.instance.isOpenRsFirmware.collectAsState()
    val scope = rememberCoroutineScope()
    val ctx   = LocalContext.current
    var exporting by remember { mutableStateOf(false) }
    val accent = LocalThemeAccent.current

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {

        // ── Drive Mode ───────────────────────────────────────────────────
        MoreSection("DRIVE MODE") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(DriveMode.NORMAL to "N", DriveMode.SPORT to "S",
                       DriveMode.TRACK to "T", DriveMode.DRIFT to "D")
                    .forEach { (mode, letter) ->
                        val isActive = vs.driveMode == mode
                        val modeAccent = when (mode) {
                            DriveMode.SPORT -> Ok
                            DriveMode.TRACK -> Warn
                            DriveMode.DRIFT -> Red
                            else            -> accent
                        }
                        Column(
                            Modifier.weight(1f)
                                .background(if (isActive) modeAccent.copy(0.1f) else Surf2, RoundedCornerShape(10.dp))
                                .border(1.dp, if (isActive) modeAccent else Brd, RoundedCornerShape(10.dp))
                                .padding(vertical = 12.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            HeroNum(letter, 20.sp, if (isActive) modeAccent else Frost)
                            Spacer(Modifier.height(2.dp))
                            MonoLabel(mode.label.uppercase(), 8.sp, if (isActive) modeAccent else Dim, letterSpacing = 0.1.sp)
                        }
                    }
            }
            Spacer(Modifier.height(6.dp))
            MonoLabel("Read-only mirror of CAN 0x1B0. Use steering wheel MODE button.", 9.sp, Dim)
        }

        HorizontalDivider(color = Brd)

        // ── ESC ──────────────────────────────────────────────────────────
        MoreSection("ELECTRONIC STABILITY CONTROL") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(EscStatus.ON to "ESC ON", EscStatus.PARTIAL to "SPORT", EscStatus.OFF to "ESC OFF")
                    .forEach { (status, label) ->
                        val isActive = vs.escStatus == status
                        val color = when (status) {
                            EscStatus.ON -> Ok; EscStatus.PARTIAL -> Warn; else -> Red
                        }
                        Box(
                            Modifier.weight(1f)
                                .background(if (isActive) color.copy(0.1f) else Surf2, RoundedCornerShape(10.dp))
                                .border(1.dp, if (isActive) color else Brd, RoundedCornerShape(10.dp))
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            MonoLabel(label, 10.sp, if (isActive) color else Dim, letterSpacing = 0.08.sp)
                        }
                    }
            }
            Spacer(Modifier.height(6.dp))
            MonoLabel("Current: ${vs.escStatus.label} (CAN 0x1C0). Use ESC button in car.", 9.sp, Dim)
        }

        HorizontalDivider(color = Brd)

        // ── OpenRS-FW Features ───────────────────────────────────────────
        MoreSection(if (isFw) "OPENRS-FW ACTIVE" else "FEATURES — REQUIRES openrs-fw v1.0") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                // Launch Control
                Column(
                    Modifier.weight(1f)
                        .background(Surf2, RoundedCornerShape(10.dp))
                        .border(1.dp, Brd, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    UIText("Launch Control", 12.sp, Frost, FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    val lcText = when {
                        vs.lcArmed == true  -> "● ARMED"
                        vs.lcArmed == false -> "○ STANDBY"
                        isFw                -> "… PROBING"
                        else                -> "○ N/A"
                    }
                    val lcColor = when {
                        vs.lcArmed == true -> Ok
                        isFw               -> Warn
                        else               -> Dim
                    }
                    MonoText(lcText, 10.sp, lcColor)
                    if (vs.lcRpmTarget > 0) {
                        Spacer(Modifier.height(2.dp))
                        MonoLabel("${vs.lcRpmTarget} RPM", 9.sp, Dim)
                    }
                }
                // Auto Start-Stop
                Column(
                    Modifier.weight(1f)
                        .background(Surf2, RoundedCornerShape(10.dp))
                        .border(1.dp, Brd, RoundedCornerShape(10.dp))
                        .padding(12.dp)
                ) {
                    UIText("Auto Start-Stop", 12.sp, Frost, FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    val assText = when {
                        vs.assEnabled == true  -> "● ACTIVE"
                        vs.assEnabled == false -> "○ OFF"
                        isFw                   -> "… PROBING"
                        else                   -> "○ N/A"
                    }
                    val assColor = when {
                        vs.assEnabled == true -> Ok
                        isFw                  -> Warn
                        else                  -> Dim
                    }
                    MonoText(assText, 10.sp, assColor)
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(
                Modifier.fillMaxWidth()
                    .background(
                        if (isFw) Ok.copy(alpha = 0.06f) else Red.copy(alpha = 0.06f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(1.dp, if (isFw) Ok.copy(0.2f) else Red.copy(0.2f), RoundedCornerShape(8.dp))
                    .padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                MonoLabel(
                    if (isFw) "✓  openRS_ firmware detected — features active."
                    else "⚡  Flash openrs-fw to unlock CAN write, LC, Auto Start-Stop & more.",
                    9.sp, if (isFw) Ok else Red, letterSpacing = 0.05.sp
                )
            }
        }

        HorizontalDivider(color = Brd)

        // ── Module Status ────────────────────────────────────────────────
        MoreSection("MODULE STATUS") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(
                    Triple("RDU",  vs.rduEnabled,  "Rear Drive Unit"),
                    Triple("PDC",  vs.pdcEnabled,  "Pull Drift Comp"),
                    Triple("FENG", vs.fengEnabled, "Engine Sound")
                ).forEach { (label, state, subtitle) ->
                    Column(
                        Modifier.weight(1f)
                            .background(Surf2, RoundedCornerShape(10.dp))
                            .border(1.dp, Brd, RoundedCornerShape(10.dp))
                            .padding(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        UIText(label, 12.sp, Frost, FontWeight.SemiBold)
                        Spacer(Modifier.height(4.dp))
                        val (dot, col) = when (state) {
                            true  -> "● ON"  to Ok
                            false -> "○ OFF" to Dim
                            null  -> "…"     to Warn
                        }
                        MonoText(dot, 10.sp, col)
                        Spacer(Modifier.height(2.dp))
                        MonoLabel(subtitle, 8.sp, Dim, letterSpacing = 0.08.sp)
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            MonoLabel("Polled via extended diagnostic session (60 s cycle).", 9.sp, Dim)
        }

        HorizontalDivider(color = Brd)

        // ── RS Theme ─────────────────────────────────────────────────────
        MoreSection("THEME — RS PAINT COLOUR") {
            ThemePicker(p)
        }

        HorizontalDivider(color = Brd)

        // ── Connection & Diagnostics ─────────────────────────────────────
        MoreSection("CONNECTION & DIAGNOSTICS") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DataCell("STATUS", if (vs.isConnected) "LIVE" else "OFFLINE",
                    valueColor = if (vs.isConnected) Ok else Red, modifier = Modifier.weight(1f))
                DataCell("MODE", vs.dataMode, modifier = Modifier.weight(1f))
                DataCell("FPS",  "${vs.framesPerSecond.roundToInt()}", modifier = Modifier.weight(1f))
                DataCell("SESSION", DiagnosticLogger.formatDuration(DiagnosticLogger.sessionDurationMs),
                    modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(Brush.horizontalGradient(listOf(accent.copy(0.1f), accent.copy(0.05f))), RoundedCornerShape(10.dp))
                    .border(1.dp, accent.copy(0.3f), RoundedCornerShape(10.dp))
                    .clickable(enabled = !exporting) {
                        exporting = true
                        // M-6 fix: run ZIP export on IO dispatcher to avoid ANR on large sessions
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            DiagnosticExporter.share(ctx)
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) { exporting = false }
                        }
                    }
                    .padding(vertical = 13.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel(
                    if (exporting) "BUILDING..." else "↑  CAPTURE & SHARE SNAPSHOT",
                    12.sp, accent, letterSpacing = 0.1.sp
                )
            }
        }

        HorizontalDivider(color = Brd)

        // ── Display Settings ─────────────────────────────────────────────
        MoreSection("DISPLAY SETTINGS") {
            Box(
                Modifier.fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(10.dp))
                    .border(1.dp, Brd, RoundedCornerShape(10.dp))
                    .clickable { onSettings() }
                    .padding(horizontal = 14.dp, vertical = 13.dp),
            ) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column {
                        MonoLabel("Units, TPMS threshold, connection settings", 9.sp, Dim)
                        Spacer(Modifier.height(2.dp))
                        UIText("${p.speedLabel} · ${p.tempLabel} · ${p.boostUnit}", 12.sp, Frost)
                    }
                    MonoLabel("⚙ OPEN", 10.sp, accent, letterSpacing = 0.1.sp)
                }
            }
        }

        Spacer(Modifier.height(4.dp))
    }
}

@Composable fun MoreSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column {
        MonoLabel(title, 9.sp, Dim, letterSpacing = 0.2.sp, modifier = Modifier.padding(bottom = 10.dp))
        content()
    }
}

// ── Theme Picker ──────────────────────────────────────────────────────────
@Composable fun ThemePicker(p: UserPrefs) {
    val ctx = LocalContext.current
    val themes = listOf(
        "cyan"   to "Nitrous Blue",
        "red"    to "Race Red",
        "orange" to "Tangerine",
        "green"  to "Mean Green",
        "purple" to "Stealth",
        "silver" to "Silver"
    )
    val themeColors = mapOf(
        "cyan"   to Color(0xFF00D2FF),
        "red"    to Color(0xFFFF2233),
        "orange" to Color(0xFFFF6600),
        "green"  to Color(0xFF00FF88),
        "purple" to Color(0xFF8C7AFF),
        "silver" to Color(0xFFAAC4DD)
    )

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Row 1: first 3 themes
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            themes.take(3).forEach { (id, name) ->
                val isActive = p.themeId == id
                val color    = themeColors[id] ?: Accent
                ThemeChip(id, name, color, isActive, ctx, p, Modifier.weight(1f))
            }
        }
        // Row 2: last 3 themes
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            themes.drop(3).forEach { (id, name) ->
                val isActive = p.themeId == id
                val color    = themeColors[id] ?: Accent
                ThemeChip(id, name, color, isActive, ctx, p, Modifier.weight(1f))
            }
        }
    }
}

@Composable fun ThemeChip(
    id: String, name: String, color: Color,
    isActive: Boolean, ctx: Context, p: UserPrefs, modifier: Modifier
) {
    Column(
        modifier
            .background(if (isActive) color.copy(alpha = 0.12f) else Surf2, RoundedCornerShape(10.dp))
            .border(2.dp, if (isActive) color else Brd, RoundedCornerShape(10.dp))
            .clickable { UserPrefsStore.update(ctx) { it.copy(themeId = id) } }
            .padding(vertical = 10.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier.size(14.dp).clip(CircleShape)
                .background(color)
                .border(if (isActive) 2.dp else 0.dp, Frost.copy(0.6f), CircleShape)
        )
        Spacer(Modifier.height(5.dp))
        MonoLabel(name, 8.sp, if (isActive) color else Dim, letterSpacing = 0.1.sp,
            modifier = Modifier.fillMaxWidth(), fontWeight = if (isActive) FontWeight.Bold else FontWeight.Normal)
    }
}
