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
import kotlin.math.roundToInt

// ── Brand colours (unchanged from design system) ────────────────────────────
val Bg      = Color(0xFF0A0A0A)
val Surf    = Color(0xFF141414)
val Surf2   = Color(0xFF1C1C1C)
val Surf3   = Color(0xFF242424)
val Brd     = Color(0xFF2A2A2A)
val Frost   = Color(0xFFF5F6F4)
val Dim     = Color(0xFF555555)
val Mid     = Color(0xFF888888)
val Accent  = Color(0xFF00AEEF)
val AccentD = Color(0xFF0077A8)
val Grn     = Color(0xFF22C55E)
val Amber   = Color(0xFFF59E0B)
val Red     = Color(0xFFEF4444)

// ── Fonts ──────────────────────────────────────────────────────────────────
val ShareTechMono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))
val BarlowCond    = FontFamily(
    Font(R.font.barlow_condensed_regular,  FontWeight.Normal),
    Font(R.font.barlow_condensed_medium,   FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold,     FontWeight.Bold)
)

// ── Typographic helpers ─────────────────────────────────────────────────────
/** All numeric readouts — Share Tech Mono */
@Composable fun MonoText(text: String, fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color = Frost, fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Start, modifier: Modifier = Modifier) =
    Text(text, fontSize = fontSize, fontFamily = ShareTechMono, color = color,
        fontWeight = fontWeight, textAlign = textAlign, modifier = modifier)

/** Labels and UI text — Barlow Condensed */
@Composable fun UIText(text: String, fontSize: androidx.compose.ui.unit.TextUnit,
    color: Color = Frost, fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: androidx.compose.ui.unit.TextUnit = 0.sp,
    textAlign: TextAlign = TextAlign.Start, modifier: Modifier = Modifier) =
    Text(text, fontSize = fontSize, fontFamily = BarlowCond, color = color,
        fontWeight = fontWeight, letterSpacing = letterSpacing, textAlign = textAlign, modifier = modifier)

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
            val vs    by OpenRSDashApp.instance.vehicleState.collectAsState()
            val prefs by UserPrefsStore.prefs.collectAsState()
            val debugLines by OpenRSDashApp.instance.debugLines.collectAsState()
            var tab   by remember { mutableIntStateOf(0) }
            var drawerOpen    by remember { mutableStateOf(false) }
            var settingsOpen  by remember { mutableStateOf(false) }
            val snackbarHostState = remember { SnackbarHostState() }

            val view = LocalView.current
            LaunchedEffect(prefs.screenOn, vs.isConnected) {
                view.keepScreenOn = prefs.screenOn && vs.isConnected
            }

            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = Accent)) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = Bg
                ) { innerPadding ->
                    Box(Modifier.fillMaxSize().padding(innerPadding).background(Bg)
                        .statusBarsPadding().navigationBarsPadding()) {

                        Column(Modifier.fillMaxSize()) {
                            AppHeader(
                                vs           = vs,
                                onSettings   = { settingsOpen = true },
                                onConnect    = { service?.startConnection() },
                                onDisconnect = { service?.stopConnection() },
                                onReconnect  = { service?.reconnect() }
                            )
                            NewTabBar(tab, onSelect = { tab = it }, onDrawer = { drawerOpen = true })
                            Box(Modifier.weight(1f)) {
                                when (tab) {
                                    0 -> DashPage(vs, prefs)
                                    1 -> PowerPage(vs, prefs)
                                    2 -> ChassisPage(vs, prefs, onReset = { service?.resetPeaks() })
                                    3 -> TempsPage(vs, prefs)
                                    4 -> DiagPage(debugLines, vs)
                                }
                            }
                        }

                        // System Drawer (☰)
                        if (drawerOpen) {
                            Box(Modifier.fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.7f))
                                .clickable { drawerOpen = false })
                            SystemDrawer(
                                vs = vs,
                                snackbarHostState = snackbarHostState,
                                onDismiss = { drawerOpen = false }
                            )
                        }

                        // Settings sheet
                        if (settingsOpen) {
                            SettingsDialog(onDismiss = { settingsOpen = false })
                        }

                        // Drawer open button — bottom right of tab bar
                        // Placed as floating action via the NewTabBar's menu slot
                    }
                }
            }
        }
    }

    private fun startSvc() {
        val i = Intent(this, CanDataService::class.java)
        try { startForegroundService(i) } catch (_: Exception) { startService(i) }
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
    onSettings: () -> Unit,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit
) {
    val modeColor = when (vs.driveMode) {
        DriveMode.SPORT  -> Grn
        DriveMode.TRACK  -> Amber
        DriveMode.DRIFT  -> Red
        else             -> Accent
    }

    // Pulsing connection dot animation
    val infiniteTransition = rememberInfiniteTransition(label = "conn")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = 0.3f, label = "dot",
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = EaseInOut),
            repeatMode = RepeatMode.Reverse
        )
    )
    val connColor = when {
        vs.isConnected -> Grn
        vs.isIdle      -> Amber
        else           -> Red
    }

    Row(
        Modifier.fillMaxWidth().background(Surf)
            .border(width = 1.dp, color = Brd, shape = RoundedCornerShape(0.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Logo
        Row(verticalAlignment = Alignment.CenterVertically) {
            UIText("open", 18.sp, Frost, FontWeight.Bold)
            UIText("RS", 18.sp, Accent, FontWeight.Bold)
            UIText("_", 18.sp, Frost, FontWeight.Bold)
        }

        // Status items
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // Drive mode badge
            Box(
                Modifier.background(modeColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                UIText(vs.driveMode.label.uppercase(), 11.sp, Color.Black, FontWeight.Bold, 1.sp)
            }

            // ESC
            UIText(vs.escStatus.label, 10.sp, Mid, FontWeight.Medium, 0.5.sp)

            // Connection dot
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

            // Settings gear
            Box(
                Modifier.size(28.dp)
                    .background(Surf2, RoundedCornerShape(6.dp))
                    .border(1.dp, Brd, RoundedCornerShape(6.dp))
                    .clickable { onSettings() },
                contentAlignment = Alignment.Center
            ) {
                UIText("⚙", 13.sp, Mid)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TAB BAR (4 tabs + ☰ menu)
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun NewTabBar(
    selected: Int,
    onSelect: (Int) -> Unit,
    onDrawer: (() -> Unit)? = null
) {
    val tabs = listOf("DASH", "POWER", "CHASSIS", "TEMPS", "DIAG")
    Row(
        Modifier.fillMaxWidth().background(Surf)
            .border(width = 1.dp, color = Brd, shape = RoundedCornerShape(0.dp))
            .height(40.dp)
    ) {
        tabs.forEachIndexed { i, label ->
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable { onSelect(i) }
                    .then(if (i == selected) Modifier.background(Accent.copy(alpha = 0.08f)) else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    UIText(label, 11.sp, if (i == selected) Accent else Dim, FontWeight.Bold, 1.sp)
                    if (i == selected) {
                        Box(Modifier.fillMaxWidth(0.6f).height(2.dp)
                            .background(Accent, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
                    }
                }
            }
        }
        // ☰ System Drawer button
        Box(
            Modifier.width(42.dp).fillMaxHeight()
                .clickable { onDrawer?.invoke() },
            contentAlignment = Alignment.Center
        ) {
            UIText("☰", 16.sp, Dim)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SHARED COMPONENTS
// ═══════════════════════════════════════════════════════════════════════════

/** Section label with left blue bar */
@Composable fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(2.dp).height(14.dp).background(Accent))
        Spacer(Modifier.width(6.dp))
        UIText(text, 10.sp, Dim, FontWeight.Bold, 2.sp)
    }
}

/** Small data cell — label + mono value */
@Composable fun DataCell(label: String, value: String, valueColor: Color = Frost, modifier: Modifier = Modifier) {
    Column(
        modifier.background(Surf, RoundedCornerShape(8.dp))
            .border(1.dp, Brd, RoundedCornerShape(8.dp))
            .padding(horizontal = 7.dp, vertical = 6.dp)
    ) {
        UIText(label, 9.sp, Dim, FontWeight.SemiBold, 1.2.sp)
        Spacer(Modifier.height(2.dp))
        MonoText(value, 14.sp, valueColor)
    }
}

/** Hero gauge — large reading in a card */
@Composable fun HeroGauge(
    label: String, value: String, unit: String,
    modifier: Modifier = Modifier,
    big: Boolean = false,
    valueColor: Color = Frost,
    accentTop: Boolean = false
) {
    Box(
        modifier.background(if (big) Surf2 else Surf, RoundedCornerShape(10.dp))
            .border(1.dp, if (big) AccentD else Brd, RoundedCornerShape(10.dp))
    ) {
        if (accentTop || big) {
            Box(Modifier.fillMaxWidth().height(2.dp)
                .background(if (big) Accent else Accent.copy(alpha = 0.5f),
                    RoundedCornerShape(topStart = 10.dp, topEnd = 10.dp)))
        }
        Column(
            Modifier.padding(10.dp, 12.dp, 10.dp, 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            UIText(label, 9.sp, Dim, FontWeight.SemiBold, 1.5.sp)
            MonoText(value, if (big) 44.sp else 34.sp, valueColor, textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth())
            if (unit.isNotEmpty()) UIText(unit, 10.sp, Dim, letterSpacing = 1.sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// DASH PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun DashPage(vs: VehicleState, p: UserPrefs) {
    val (boostVal, boostLbl) = p.displayBoost(vs.boostKpa)
    val brakeStr = "%.0f".format(vs.brakePressure.coerceIn(0.0, 100.0))

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {

        // Hero row: Boost | RPM (big, blue) | Speed
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            HeroGauge("BOOST", boostVal, boostLbl, Modifier.weight(1f), accentTop = true)
            HeroGauge("RPM", "${vs.rpm.roundToInt()}", "×1000",
                Modifier.weight(1.3f), big = true, valueColor = Accent)
            HeroGauge("SPEED", p.displaySpeed(vs.speedKph), p.speedLabel,
                Modifier.weight(1f), accentTop = true)
        }

        // Info grid 4×2
        val oilColor  = tempColorShade(vs.oilTempC, 120.0, 130.0)
        val coolColor = tempColorShade(vs.coolantTempC, 105.0, 110.0)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DataCell("THROTTLE", "${vs.throttlePct.roundToInt()}%", valueColor = Accent, modifier = Modifier.weight(1f))
            DataCell("BRAKE",    "$brakeStr%",                                       modifier = Modifier.weight(1f))
            DataCell("TORQUE",   "${vs.torqueAtTrans.roundToInt()} Nm",              modifier = Modifier.weight(1f))
            DataCell("OIL",      "${p.displayTemp(vs.oilTempC)}${p.tempLabel}", valueColor = oilColor, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DataCell("COOLANT",  "${p.displayTemp(vs.coolantTempC)}${p.tempLabel}", valueColor = coolColor, modifier = Modifier.weight(1f))
            DataCell("INTAKE",   "${p.displayTemp(vs.intakeTempC)}${p.tempLabel}",  modifier = Modifier.weight(1f))
            DataCell("FUEL",     "${vs.fuelLevelPct.roundToInt()}%",                modifier = Modifier.weight(1f))
            DataCell("BATT",     "${"%.1f".format(vs.batteryVoltage)}V",            modifier = Modifier.weight(1f))
        }

        // AWD split bar
        AwdSplitBar(vs)

        // G-force mini row
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DataCell("LAT G",  "${"%.2f".format(vs.lateralG)}g",   modifier = Modifier.weight(1f))
            DataCell("LON G",  "${"%.2f".format(vs.longitudinalG)}g", modifier = Modifier.weight(1f))
        }
    }
}

@Composable fun AwdSplitBar(vs: VehicleState) {
    val rearPct = vs.rearTorquePct.coerceIn(0.0, 100.0).toFloat()
    val frontPct = (100f - rearPct).coerceIn(0.01f, 99.99f)
    val rearF    = rearPct.coerceIn(0.01f, 99.99f)
    Column(
        Modifier.fillMaxWidth().background(Surf, RoundedCornerShape(10.dp))
            .border(1.dp, Brd, RoundedCornerShape(10.dp)).padding(10.dp, 10.dp, 10.dp, 8.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            UIText("AWD SPLIT — FRONT / REAR", 10.sp, Dim, FontWeight.SemiBold, 1.5.sp)
            MonoText(vs.frontRearSplit, 16.sp, Accent)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(8.dp).background(Surf3, RoundedCornerShape(4.dp))) {
            Box(Modifier.weight(frontPct).fillMaxHeight()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Accent, Accent.copy(alpha = 0.5f))),
                    RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp)))
            Box(Modifier.weight(rearF).fillMaxHeight()
                .background(
                    androidx.compose.ui.graphics.Brush.horizontalGradient(
                        listOf(Grn.copy(alpha = 0.5f), Grn)),
                    RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            UIText("← FRONT", 9.sp, Dim)
            UIText("REAR →", 9.sp, Dim)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// POWER PAGE (was TUNE)
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun PowerPage(vs: VehicleState, p: UserPrefs) {
    val hasAfr = vs.afrActual > 0
    val placeholder = "— —"

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {

        // AFR hero 3 cards
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            AfrCard("AFR ACT",  if (hasAfr) "%.2f".format(vs.afrActual) else placeholder, ":1",
                if (hasAfr) Accent else Dim, Modifier.weight(1f))
            AfrCard("AFR DES",  if (hasAfr) "%.2f".format(vs.afrDesired) else placeholder, ":1",
                if (hasAfr) Frost else Dim, Modifier.weight(1f))
            AfrCard("LAMBDA",   if (hasAfr) "%.3f".format(vs.lambdaActual) else placeholder, "λ",
                if (hasAfr) Grn else Dim, Modifier.weight(1f))
        }

        // Throttle & Boost
        SectionLabel("Throttle & Boost")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DataCell("ETC ACT", if (vs.etcAngleActual > 0) "${"%.1f".format(vs.etcAngleActual)}°" else placeholder, modifier = Modifier.weight(1f))
            DataCell("ETC DES", if (vs.etcAngleDesired > 0) "${"%.1f".format(vs.etcAngleDesired)}°" else placeholder, modifier = Modifier.weight(1f))
            DataCell("WGDC",    if (vs.wgdcDesired > 0) "${"%.0f".format(vs.wgdcDesired)}%" else placeholder, valueColor = Accent, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val (tipActVal, tipLbl) = p.displayBoost(vs.tipActualKpa)
            val (tipDesVal, _)      = p.displayBoost(vs.tipDesiredKpa)
            val hasTip = vs.tipActualKpa > 50
            DataCell("TIP ACT",   if (hasTip) "$tipActVal $tipLbl" else placeholder, modifier = Modifier.weight(1f))
            DataCell("TIP DES",   if (hasTip) "$tipDesVal $tipLbl" else placeholder, modifier = Modifier.weight(1f))
            DataCell("FUEL RAIL", if (vs.fuelRailPsi > 0) "${"%.0f".format(vs.fuelRailPsi)} PSI" else placeholder, modifier = Modifier.weight(1f))
        }

        // Engine Management
        SectionLabel("Engine Management")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DataCell("TIMING", if (vs.timingAdvance != 0.0) "${"%.1f".format(vs.timingAdvance)}°" else placeholder, modifier = Modifier.weight(1f))
            DataCell("LOAD",   if (vs.calcLoad > 0) "${"%.0f".format(vs.calcLoad)}%" else placeholder,             modifier = Modifier.weight(1f))
            DataCell("OAR",    if (vs.octaneAdjustRatio != 0.0) "${"%.0f".format(vs.octaneAdjustRatio * 100)}%" else placeholder, modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val krColor = if (vs.ignCorrCyl1 < -1.0) Amber else Grn
            DataCell("KR CYL1", if (vs.ignCorrCyl1 != 0.0) "${"%.2f".format(vs.ignCorrCyl1)}°" else placeholder, valueColor = krColor, modifier = Modifier.weight(1f))
            DataCell("VCT-I",   if (vs.vctIntakeAngle != 0.0) "${"%.1f".format(vs.vctIntakeAngle)}°" else placeholder, modifier = Modifier.weight(1f))
            DataCell("VCT-E",   if (vs.vctExhaustAngle != 0.0) "${"%.1f".format(vs.vctExhaustAngle)}°" else placeholder, modifier = Modifier.weight(1f))
        }

        // Fuel Trims & Misc
        SectionLabel("Fuel Trims & Misc")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            val stftColor = fuelTrimColor(vs.shortFuelTrim)
            val ltftColor = fuelTrimColor(vs.longFuelTrim)
            DataCell("SHORT FT",  if (vs.shortFuelTrim != 0.0) "${"%.1f".format(vs.shortFuelTrim)}%" else placeholder, valueColor = stftColor, modifier = Modifier.weight(1f))
            DataCell("LONG FT",   if (vs.longFuelTrim != 0.0) "${"%.1f".format(vs.longFuelTrim)}%" else placeholder, valueColor = ltftColor, modifier = Modifier.weight(1f))
            DataCell("BARO",      "${vs.barometricPressure.roundToInt()} kPa",                                         modifier = Modifier.weight(1f))
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DataCell("OIL LIFE", if (vs.oilLifePct >= 0) "${vs.oilLifePct.roundToInt()}%" else placeholder, modifier = Modifier.weight(1f))
            DataCell("AFR SEN1", if (vs.afrSensor1 > 0) "${"%.2f".format(vs.afrSensor1)}" else placeholder, modifier = Modifier.weight(1f))
            DataCell("O2 VOLT",  if (vs.o2Voltage > 0) "${"%.3f".format(vs.o2Voltage)}V" else placeholder,  modifier = Modifier.weight(1f))
        }
    }
}

@Composable fun AfrCard(label: String, value: String, unit: String, valueColor: Color, modifier: Modifier) {
    Column(
        modifier.background(Surf, RoundedCornerShape(10.dp))
            .border(1.dp, Brd, RoundedCornerShape(10.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        UIText(label, 9.sp, Dim, FontWeight.SemiBold, 1.sp)
        Spacer(Modifier.height(4.dp))
        MonoText(value, 26.sp, valueColor, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        UIText(unit, 10.sp, Dim)
    }
}

private fun fuelTrimColor(trim: Double) = when {
    trim > 10.0  -> Amber
    trim < -10.0 -> Red
    else         -> Grn
}

// ═══════════════════════════════════════════════════════════════════════════
// CHASSIS PAGE (AWD + G-Force + TPMS)
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun ChassisPage(vs: VehicleState, p: UserPrefs, onReset: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)) {
        AwdSection(vs, p)
        GForceSection(vs, onReset)
        TpmsSection(vs, p)
    }
}

@Composable fun AwdSection(vs: VehicleState, p: UserPrefs) {
    val total = vs.totalRearTorque
    val leftPct  = if (total > 0) (vs.awdLeftTorque / total).toFloat() else 0.5f
    val rightPct = (1f - leftPct).coerceIn(0.01f, 0.99f)
    val leftPctC = leftPct.coerceIn(0.01f, 0.99f)

    val avgF = (vs.wheelSpeedFL + vs.wheelSpeedFR) / 2
    val avgR = (vs.wheelSpeedRL + vs.wheelSpeedRR) / 2
    val frDelta = avgR - avgF
    val lrDelta = vs.wheelSpeedRR - vs.wheelSpeedRL

    Column(
        Modifier.fillMaxWidth().background(Surf, RoundedCornerShape(12.dp))
            .border(1.dp, Brd, RoundedCornerShape(12.dp)).padding(12.dp)
    ) {
        SectionLabel("AWD — GKN Twinster")

        // Wheel speed grid: FL [split/RDU] FR / RL [blank] RR
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // Left column: FL + RL
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WheelCell("FL", "${"%.1f".format(vs.wheelSpeedFL)}", front = true)
                WheelCell("RL", "${"%.1f".format(vs.wheelSpeedRL)}", front = false)
            }
            // Center column: split + RDU
            Column(Modifier.weight(0.7f), horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)) {
                MonoText(vs.frontRearSplit, 18.sp, Accent, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                UIText("F / R", 9.sp, Dim, FontWeight.SemiBold, 1.sp, TextAlign.Center, Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                val rduStr = if (vs.rduTempC > -90) "${p.displayTemp(vs.rduTempC)}${p.tempLabel}" else "—"
                UIText("RDU", 8.sp, Dim, FontWeight.SemiBold, 1.sp, TextAlign.Center, Modifier.fillMaxWidth())
                MonoText(rduStr, 12.sp, Mid, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            // Right column: FR + RR
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                WheelCell("FR", "${"%.1f".format(vs.wheelSpeedFR)}", front = true)
                WheelCell("RR", "${"%.1f".format(vs.wheelSpeedRR)}", front = false)
            }
        }

        Spacer(Modifier.height(10.dp))

        // Torque bar
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MonoText("L ${vs.awdLeftTorque.roundToInt()}Nm", 10.sp, Accent)
            MonoText("${vs.awdRightTorque.roundToInt()}Nm R", 10.sp, Grn)
        }
        Row(Modifier.fillMaxWidth().height(10.dp).background(Surf3, RoundedCornerShape(5.dp))) {
            Box(Modifier.weight(leftPctC).fillMaxHeight()
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Accent, Accent.copy(0.4f))),
                    RoundedCornerShape(topStart = 5.dp, bottomStart = 5.dp)))
            Box(Modifier.weight(rightPct).fillMaxHeight()
                .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Grn.copy(0.4f), Grn)),
                    RoundedCornerShape(topEnd = 5.dp, bottomEnd = 5.dp)))
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            UIText("REAR BIAS ${vs.rearLeftRightBias}", 9.sp, Dim)
            UIText("L/R DELTA ${"%.1f".format(lrDelta)}", 9.sp, Dim)
        }

        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DataCell("F/R DELTA", "${"%.1f".format(frDelta)} km/h", modifier = Modifier.weight(1f))
            DataCell("MAX TQ",    "${vs.awdMaxTorque.roundToInt()} Nm",  modifier = Modifier.weight(1f))
        }
    }
}

@Composable fun WheelCell(label: String, speed: String, front: Boolean) {
    val accentColor = if (front) Accent.copy(alpha = 0.4f) else Grn.copy(alpha = 0.3f)
    Column(
        Modifier.fillMaxWidth().background(Surf2, RoundedCornerShape(8.dp))
            .border(1.dp, accentColor, RoundedCornerShape(8.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        UIText(label, 9.sp, Dim, FontWeight.SemiBold, 1.sp)
        MonoText(speed, 16.sp, Frost)
    }
}

@Composable fun GForceSection(vs: VehicleState, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().background(Surf, RoundedCornerShape(12.dp))
            .border(1.dp, Brd, RoundedCornerShape(12.dp)).padding(12.dp)
    ) {
        SectionLabel("G-Force & Dynamics")
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GfCard("LAT G",    "${"%.2f".format(vs.lateralG)}",    "↑ ${"%.2f".format(vs.peakLateralG)}",    Modifier.weight(1f))
            GfCard("LON G",    "${"%.2f".format(vs.longitudinalG)}","↑ ${"%.2f".format(vs.peakLongitudinalG)}", Modifier.weight(1f))
            GfCard("COMBINED", "${"%.2f".format(vs.combinedG)}",   "",                                         Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GfCard("YAW",   "${"%.1f".format(vs.yawRate)}°/s", "",  Modifier.weight(1f))
            GfCard("STEER", "${"%.1f".format(vs.steeringAngle)}°",  "", Modifier.weight(1f))
            // Reset button inline
            Box(Modifier.weight(1f).background(Surf2, RoundedCornerShape(8.dp))
                .border(1.dp, Brd, RoundedCornerShape(8.dp))
                .clickable { onReset() }.padding(vertical = 12.dp),
                contentAlignment = Alignment.Center) {
                UIText("↺ PEAKS", 11.sp, Dim, FontWeight.SemiBold, 1.5.sp)
            }
        }
    }
}

@Composable fun GfCard(label: String, value: String, peak: String, modifier: Modifier) {
    Column(
        modifier.background(Surf2, RoundedCornerShape(8.dp))
            .border(1.dp, Brd, RoundedCornerShape(8.dp)).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        UIText(label, 9.sp, Dim, FontWeight.SemiBold, 1.sp)
        Spacer(Modifier.height(2.dp))
        MonoText(value, 20.sp, Frost, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        if (peak.isNotEmpty()) {
            MonoText(peak, 10.sp, Accent, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

@Composable fun TpmsSection(vs: VehicleState, p: UserPrefs) {
    Column(
        Modifier.fillMaxWidth().background(Surf, RoundedCornerShape(12.dp))
            .border(1.dp, Brd, RoundedCornerShape(12.dp)).padding(12.dp)
    ) {
        SectionLabel("TPMS — Tire Pressure")

        if (!vs.hasTpmsData) {
            // Ghost waiting state
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Simple car outline SVG representation using boxes
                    CarOutlinePlaceholder()
                    Spacer(Modifier.height(12.dp))
                    UIText("WAITING FOR SENSOR DATA", 11.sp, Dim, FontWeight.SemiBold, 2.sp, TextAlign.Center)
                    UIText("Sensors transmit when wheels are rolling", 10.sp, Dim.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
                }
            }
        } else {
            // Tires around car outline
            val lowThreshold = p.tireLowPsi.toDouble()
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically) {
                // Left tires
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TireCard("LF", vs.tirePressLF, p, lowThreshold)
                    TireCard("LR", vs.tirePressLR, p, lowThreshold)
                }
                // Car outline center
                Box(Modifier.width(52.dp), contentAlignment = Alignment.Center) {
                    CarOutlinePlaceholder(compact = true)
                }
                // Right tires
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    TireCard("RF", vs.tirePressRF, p, lowThreshold)
                    TireCard("RR", vs.tirePressRR, p, lowThreshold)
                }
            }
            if (vs.anyTireLow(lowThreshold)) {
                Spacer(Modifier.height(8.dp))
                Box(Modifier.fillMaxWidth().background(Red.copy(alpha = 0.1f), RoundedCornerShape(6.dp))
                    .border(1.dp, Red.copy(alpha = 0.5f), RoundedCornerShape(6.dp)).padding(8.dp),
                    contentAlignment = Alignment.Center) {
                    UIText("⚠ LOW TIRE PRESSURE", 11.sp, Red, FontWeight.Bold, 1.5.sp)
                }
            }
        }
    }
}

@Composable fun TireCard(label: String, psi: Double, p: UserPrefs, lowThreshold: Double) {
    val isLow = psi in 0.0..(lowThreshold - 0.001)
    val isMissing = psi < 0
    val tireColor = when {
        isMissing -> Dim
        isLow     -> Red
        psi > 40.0 -> Amber
        else      -> Grn
    }
    Column(
        Modifier.fillMaxWidth().background(Surf2, RoundedCornerShape(8.dp))
            .border(1.dp, if (isLow) Red.copy(0.5f) else Brd, RoundedCornerShape(8.dp))
            .padding(8.dp, 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        UIText(label, 9.sp, Dim, FontWeight.SemiBold, 1.sp)
        Spacer(Modifier.height(3.dp))
        MonoText(if (isMissing) "—" else p.displayTire(psi), 18.sp, tireColor, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth())
        UIText(p.tireLabel, 9.sp, Dim, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
    }
}

@Composable fun CarOutlinePlaceholder(compact: Boolean = false) {
    val w = if (compact) 48.dp else 72.dp
    val h = if (compact) 96.dp else 128.dp
    Box(Modifier.width(w).height(h), contentAlignment = Alignment.Center) {
        // Body
        Box(Modifier.width(w * 0.7f).height(h * 0.7f)
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, Brd.copy(alpha = 0.7f), RoundedCornerShape(10.dp)))
        // FL wheel
        Box(Modifier.size(w * 0.18f, h * 0.14f)
            .offset(x = -w * 0.4f, y = -h * 0.28f)
            .background(Surf3, RoundedCornerShape(3.dp))
            .border(1.dp, Dim, RoundedCornerShape(3.dp)))
        // FR wheel
        Box(Modifier.size(w * 0.18f, h * 0.14f)
            .offset(x = w * 0.4f, y = -h * 0.28f)
            .background(Surf3, RoundedCornerShape(3.dp))
            .border(1.dp, Dim, RoundedCornerShape(3.dp)))
        // RL wheel
        Box(Modifier.size(w * 0.18f, h * 0.14f)
            .offset(x = -w * 0.4f, y = h * 0.28f)
            .background(Surf3, RoundedCornerShape(3.dp))
            .border(1.dp, Dim, RoundedCornerShape(3.dp)))
        // RR wheel
        Box(Modifier.size(w * 0.18f, h * 0.14f)
            .offset(x = w * 0.4f, y = h * 0.28f)
            .background(Surf3, RoundedCornerShape(3.dp))
            .border(1.dp, Dim, RoundedCornerShape(3.dp)))
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TEMPS PAGE
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun TempsPage(vs: VehicleState, p: UserPrefs) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {

        // RTR Banner
        RtrBanner(vs, p)

        // Temp grid 2-col
        val tempItems = listOf(
            TempSpec("ENGINE OIL", p.displayTemp(vs.oilTempC),     p.tempLabel, vs.oilTempC,      120.0, 130.0, "INFERRED"),
            TempSpec("COOLANT",    p.displayTemp(vs.coolantTempC),  p.tempLabel, vs.coolantTempC,  105.0, 110.0, ""),
            TempSpec("INTAKE AIR", p.displayTemp(vs.intakeTempC),   p.tempLabel, vs.intakeTempC,   60.0,  80.0,  ""),
            TempSpec("AMBIENT",    p.displayTemp(vs.ambientTempC),  p.tempLabel, vs.ambientTempC,  40.0,  50.0,  ""),
            TempSpec("RDU (REAR DIFF)", p.displayTemp(vs.rduTempC.takeIf { it > -90 } ?: 0.0), p.tempLabel, vs.rduTempC.takeIf { it > -90 } ?: 0.0, 80.0, 100.0,
                if (vs.rduTempC <= -90) "POLLING" else ""),
            TempSpec("PTU (TRANSFER)",  p.displayTemp(vs.ptuTempC), p.tempLabel, vs.ptuTempC,      80.0, 100.0,  ""),
            TempSpec("CHARGE AIR",      if (vs.chargeAirTempC != 0.0) p.displayTemp(vs.chargeAirTempC) else "— —", p.tempLabel, vs.chargeAirTempC, 60.0, 80.0,
                if (vs.chargeAirTempC == 0.0) "POLLING" else ""),
            TempSpec("CATALYTIC",       if (vs.catalyticTempC != 0.0) p.displayTemp(vs.catalyticTempC) else "— —", p.tempLabel, vs.catalyticTempC, 700.0, 800.0,
                if (vs.catalyticTempC == 0.0) "POLLING" else ""),
            TempSpec("CABIN",      if (vs.cabinTempC > -90) p.displayTemp(vs.cabinTempC) else "— —", p.tempLabel, vs.cabinTempC.takeIf { it > -90 } ?: 0.0, 35.0, 45.0, "BCM"),
            TempSpec("BATT TEMP",  if (vs.batteryTempC > -90) p.displayTemp(vs.batteryTempC) else "— —", p.tempLabel, vs.batteryTempC.takeIf { it > -90 } ?: 0.0, 40.0, 60.0, "BCM"),
        )

        tempItems.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                pair.forEach { spec ->
                    TempCard(spec, Modifier.weight(1f))
                }
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
    val rtrMsg = vs.rtrStatus
    val isReady = rtrMsg == null
    val dotColor = if (isReady) Grn else Amber
    val infiniteTransition = rememberInfiniteTransition(label = "rtr")
    val dotAlpha by infiniteTransition.animateFloat(
        initialValue = 1f, targetValue = if (isReady) 1f else 0.3f, label = "rtrDot",
        animationSpec = infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse)
    )
    Row(
        Modifier.fillMaxWidth().background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, Brd, RoundedCornerShape(10.dp)).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(11.dp).clip(CircleShape).background(dotColor.copy(alpha = dotAlpha)))
        Spacer(Modifier.width(10.dp))
        Column {
            UIText(if (isReady) "RACE READY" else "WARMING UP — NOT RACE READY",
                13.sp, Frost, FontWeight.SemiBold, 1.sp)
            if (!isReady && rtrMsg != null) {
                UIText(rtrMsg, 10.sp, Dim, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
}

@Composable fun TempCard(spec: TempSpec, modifier: Modifier) {
    val tempColor = tempColorShade(spec.tempC, spec.warnC, spec.critC)
    val barColor = when {
        spec.tempC <= 0        -> Accent.copy(alpha = 0.3f)
        spec.tempC < spec.warnC -> Grn.copy(alpha = 0.5f)
        spec.tempC < spec.critC -> Amber.copy(alpha = 0.6f)
        else                   -> Red
    }
    Box(modifier.background(Surf, RoundedCornerShape(12.dp))
        .border(1.dp, Brd, RoundedCornerShape(12.dp))) {
        Column(Modifier.padding(12.dp)) {
            UIText(spec.label, 9.sp, Dim, FontWeight.SemiBold, 1.5.sp)
            Spacer(Modifier.height(5.dp))
            val displayVal = if (spec.value == "— —") spec.value else spec.value
            val valueColor = if (spec.value == "— —") Dim else tempColor
            val valueFontSize = if (spec.value == "— —") 22.sp else 34.sp
            MonoText(displayVal, valueFontSize, valueColor)
            Row(verticalAlignment = Alignment.CenterVertically) {
                UIText(spec.unit, 11.sp, Dim)
                if (spec.sub.isNotEmpty()) {
                    Spacer(Modifier.width(4.dp))
                    UIText("(${spec.sub})", 9.sp, Dim.copy(alpha = 0.7f))
                }
            }
        }
        // Color bar at bottom
        Box(Modifier.fillMaxWidth().height(3.dp)
            .background(barColor, RoundedCornerShape(bottomStart = 12.dp, bottomEnd = 12.dp))
            .align(Alignment.BottomCenter))
    }
}

private fun tempColorShade(c: Double, warnC: Double, critC: Double) = when {
    c <= 0      -> Dim
    c >= critC  -> Red
    c >= warnC  -> Amber
    c >= warnC * 0.6 -> Grn
    else        -> Frost
}

// ═══════════════════════════════════════════════════════════════════════════
// DIAG PAGE (restored)
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun DiagPage(lines: List<String>, vs: VehicleState) {
    val ctx   = LocalContext.current
    val scope = rememberCoroutineScope()
    var exporting by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(10.dp)) {
        UIText("DIAGNOSTICS", 12.sp, Amber, FontWeight.Bold, 2.sp)
        Spacer(Modifier.height(6.dp))

        val sessionMs  = DiagnosticLogger.sessionDurationMs
        val frameCount = DiagnosticLogger.frameInventorySnapshot.values.sumOf { it.totalReceived }
        val issueCount = DiagnosticLogger.frameInventorySnapshot.values.sumOf { it.validationIssues.size }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DataCell("FPS",   "${vs.framesPerSecond.roundToInt()}", modifier = Modifier.weight(1f))
            DataCell("MODE",  vs.dataMode,                          modifier = Modifier.weight(1f))
            DataCell("CONN",  if (vs.isConnected) "LIVE" else "OFF",
                valueColor = if (vs.isConnected) Grn else Red,    modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(5.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            DataCell("SESSION", DiagnosticLogger.formatDuration(sessionMs), modifier = Modifier.weight(1f))
            DataCell("FRAMES",  "$frameCount",                              modifier = Modifier.weight(1f))
            DataCell("IDs",     "${DiagnosticLogger.frameInventorySnapshot.size}", modifier = Modifier.weight(1f))
        }

        if (issueCount > 0) {
            Spacer(Modifier.height(4.dp))
            UIText("⚠ $issueCount validation issue(s) — capture snapshot to review", 10.sp, Amber)
        }

        Spacer(Modifier.height(10.dp))
        Box(Modifier.fillMaxWidth()
            .background(if (!exporting) Accent else Dim, RoundedCornerShape(6.dp))
            .clickable(enabled = !exporting) {
                exporting = true
                scope.launch { DiagnosticExporter.share(ctx); exporting = false }
            }.padding(vertical = 11.dp), contentAlignment = Alignment.Center) {
            UIText(
                if (exporting) "BUILDING..." else "↑ CAPTURE & SHARE SNAPSHOT",
                12.sp, if (!exporting) Color(0xFF0A0A0A) else Frost, FontWeight.Bold, 2.sp
            )
        }
        Spacer(Modifier.height(4.dp))
        UIText("Exports a ZIP (summary .txt + full .json + SLCAN raw log) via share sheet.",
            9.sp, Dim, modifier = Modifier.padding(bottom = 12.dp))

        HorizontalDivider(color = Brd)
        Spacer(Modifier.height(8.dp))
        UIText("Live WiCAN output (last ${lines.size} lines, newest ↓):", 9.sp, Dim)
        Spacer(Modifier.height(4.dp))
        lines.forEach { line ->
            MonoText(line, 10.sp, Frost, modifier = Modifier.padding(vertical = 1.dp))
        }
        if (lines.isEmpty() && vs.isConnected) {
            UIText("Connected — waiting for first CAN frame...", 10.sp, Amber)
        } else if (lines.isEmpty()) {
            UIText("Connect to WiCAN to see raw output.", 10.sp, Dim)
        }

        val inv = DiagnosticLogger.frameInventorySnapshot
        if (inv.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Brd)
            Spacer(Modifier.height(8.dp))
            UIText("FRAME INVENTORY (${inv.size} IDs)", 9.sp, Dim, FontWeight.Bold, 2.sp)
            Spacer(Modifier.height(4.dp))
            inv.entries.sortedBy { it.key }.forEach { (id, info) ->
                val decoded  = if (info.lastDecoded.isEmpty()) "(no decoder)" else info.lastDecoded
                val issColor = if (info.validationIssues.isNotEmpty()) Amber else Dim
                MonoText("0x%03X  ×%-6d  %s".format(id, info.totalReceived, decoded.take(45)),
                    9.sp, issColor, modifier = Modifier.padding(vertical = 1.dp))
                info.validationIssues.forEach { issue ->
                    MonoText("         ⚠ $issue", 9.sp, Amber)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// SYSTEM DRAWER (☰)
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun SystemDrawer(
    vs: VehicleState,
    snackbarHostState: SnackbarHostState,
    onDismiss: () -> Unit
) {
    val isFw  by OpenRSDashApp.instance.isOpenRsFirmware.collectAsState()
    val scope = rememberCoroutineScope()
    val sessionMs = DiagnosticLogger.sessionDurationMs
    val ctx = LocalContext.current
    var exporting by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        Column(
            Modifier.fillMaxWidth()
                .background(Surf, RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
                .border(width = 1.dp, color = Brd,
                    shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp))
        ) {
            // Handle bar
            Box(Modifier.fillMaxWidth().padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                Box(Modifier.width(40.dp).height(4.dp).background(Brd, RoundedCornerShape(2.dp)))
            }

            Column(Modifier.verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                // Drive Mode
                DrawerSection("Drive Mode") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(DriveMode.NORMAL to "N", DriveMode.SPORT to "S",
                               DriveMode.TRACK to "T", DriveMode.DRIFT to "D")
                            .forEach { (mode, letter) ->
                                val isActive = vs.driveMode == mode
                                Column(
                                    Modifier.weight(1f)
                                        .background(if (isActive) Accent.copy(0.1f) else Surf2, RoundedCornerShape(7.dp))
                                        .border(1.dp, if (isActive) Accent else Brd, RoundedCornerShape(7.dp))
                                        .padding(vertical = 9.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    MonoText(letter, 17.sp, if (isActive) Accent else Frost, FontWeight.Bold)
                                    UIText(mode.label.uppercase(), 9.sp, Dim, FontWeight.Medium, 0.5.sp, TextAlign.Center)
                                }
                            }
                    }
                    Spacer(Modifier.height(6.dp))
                    UIText("Read-only mirror of CAN 0x1B0. Use steering wheel MODE button.", 10.sp, Dim)
                }

                DrawerDivider()

                // ESC
                DrawerSection("Electronic Stability Control") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf(EscStatus.ON to "ESC ON", EscStatus.PARTIAL to "SPORT", EscStatus.OFF to "ESC OFF")
                            .forEach { (status, label) ->
                                val isActive = vs.escStatus == status
                                val color = when (status) {
                                    EscStatus.ON -> Grn; EscStatus.PARTIAL -> Amber; else -> Red
                                }
                                Box(
                                    Modifier.weight(1f)
                                        .background(if (isActive) color.copy(0.1f) else Surf2, RoundedCornerShape(7.dp))
                                        .border(1.dp, if (isActive) color else Brd, RoundedCornerShape(7.dp))
                                        .padding(vertical = 10.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    UIText(label, 12.sp, if (isActive) color else Dim, FontWeight.Bold, 0.5.sp)
                                }
                            }
                    }
                    Spacer(Modifier.height(6.dp))
                    UIText("Current: ${vs.escStatus.label} (CAN 0x1C0). Use ESC button in car.", 10.sp, Dim)
                }

                DrawerDivider()

                // Features
                DrawerSection(if (isFw) "Features — openrs-fw active" else "Features — requires openrs-fw v1.0") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        listOf("Launch Control", "Auto S/S Kill").forEach { feat ->
                            Box(
                                Modifier.weight(1f)
                                    .background(Surf2, RoundedCornerShape(7.dp))
                                    .border(1.dp, Brd, RoundedCornerShape(7.dp))
                                    .clickable {
                                        if (!isFw) scope.launch {
                                            snackbarHostState.showSnackbar("Requires openrs-fw — flash to unlock")
                                        }
                                    }
                                    .padding(10.dp)
                                    .then(if (!isFw) Modifier.then(Modifier) else Modifier)
                            ) {
                                Column {
                                    UIText(feat, 11.sp, if (isFw) Frost else Dim, FontWeight.SemiBold)
                                    MonoText(if (isFw) "● ON" else "○ OFF", 10.sp, if (isFw) Grn else Dim)
                                }
                                if (!isFw) Box(Modifier.matchParentSize().background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(7.dp)))
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    UIText(
                        if (isFw) "✓ openRS_ firmware detected — features unlocked."
                        else "⚡ Flash openrs-fw to unlock CAN write, LC, Auto S/S Kill & more.",
                        10.sp, if (isFw) Grn else Amber
                    )
                }

                DrawerDivider()

                // Connection & Diagnostics
                DrawerSection("Connection & Diagnostics") {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        DataCell("STATUS", if (vs.isConnected) "LIVE" else "OFFLINE",
                            valueColor = if (vs.isConnected) Grn else Red, modifier = Modifier.weight(1f))
                        DataCell("MODE", vs.dataMode,                           modifier = Modifier.weight(1f))
                        DataCell("FPS",  "${vs.framesPerSecond.roundToInt()}", modifier = Modifier.weight(1f))
                        DataCell("SESSION", DiagnosticLogger.formatDuration(sessionMs), modifier = Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(8.dp))
                    Box(Modifier.fillMaxWidth()
                        .background(Accent.copy(alpha = 0.1f), RoundedCornerShape(7.dp))
                        .border(1.dp, AccentD, RoundedCornerShape(7.dp))
                        .clickable(enabled = !exporting) {
                            exporting = true
                            scope.launch { DiagnosticExporter.share(ctx); exporting = false }
                        }.padding(vertical = 10.dp), contentAlignment = Alignment.Center) {
                        UIText(if (exporting) "BUILDING..." else "↑ CAPTURE & SHARE SNAPSHOT",
                            12.sp, Accent, FontWeight.Bold, 1.sp)
                    }
                }
            }
        }
    }
}

@Composable fun DrawerSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
        UIText(title.uppercase(), 10.sp, Dim, FontWeight.Bold, 2.sp)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable fun DrawerDivider() {
    HorizontalDivider(Modifier.padding(horizontal = 0.dp), color = Brd, thickness = 1.dp)
}
