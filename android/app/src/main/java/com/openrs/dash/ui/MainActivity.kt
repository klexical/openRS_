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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.DriveMode
import com.openrs.dash.data.EscStatus
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.CanDataService
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ── Colors (matching Sync 3 HTML) ───────────────────────────
val Bg     = Color(0xFF0A0A0A)
val Surf   = Color(0xFF1A1A1A)
val Brd    = Color(0xFF2A2A2A)
val Txt    = Color(0xFFE0E0E0)
val Dim    = Color(0xFF666666)
val Accent = Color(0xFF00A8E8)
val Grn    = Color(0xFF00E676)
val Red    = Color(0xFFFF5252)
val Org    = Color(0xFFFF9800)
val Gold   = Color(0xFFF8E63C)
val Mono   = FontFamily.Monospace

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

        // Load saved preferences into the observable store
        UserPrefsStore.load(this)

        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray()) else startSvc()

        setContent {
            val vs    by OpenRSDashApp.instance.vehicleState.collectAsState()
            val prefs by UserPrefsStore.prefs.collectAsState()
            var tab   by remember { mutableIntStateOf(0) }
            val debugLines by OpenRSDashApp.instance.debugLines.collectAsState()
            val snackbarHostState = remember { SnackbarHostState() }

            // Screen-on controlled by the preference + connection state
            val view = LocalView.current
            LaunchedEffect(prefs.screenOn, vs.isConnected) {
                view.keepScreenOn = prefs.screenOn && vs.isConnected
            }

            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = Accent)) {
                Scaffold(
                    snackbarHost = { SnackbarHost(snackbarHostState) },
                    containerColor = Bg
                ) { innerPadding ->
                    Column(
                        Modifier.fillMaxSize().padding(innerPadding).background(Bg)
                            .statusBarsPadding().navigationBarsPadding()
                    ) {
                        Header(vs, prefs,
                            onConnect    = { service?.startConnection() },
                            onDisconnect = { service?.stopConnection() },
                            onReconnect  = { service?.reconnect() })
                        TabRow(tab) { tab = it }
                        Box(Modifier.weight(1f)) {
                            when (tab) {
                                0 -> DashPage(vs, prefs)
                                1 -> AwdPage(vs, prefs)
                                2 -> PerfPage(vs, prefs, onReset = { service?.resetPeaks() })
                                3 -> TempsPage(vs, prefs)
                                4 -> TunePage(vs, prefs)
                                5 -> TpmsPage(vs, prefs)
                                6 -> CtrlPage(vs, snackbarHostState)
                                7 -> DebugPage(debugLines, vs)
                            }
                        }
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

// ═══════════════════════════════════════════════════════════════
// UI COMPONENTS
// ═══════════════════════════════════════════════════════════════

@Composable fun Header(
    vs: VehicleState,
    prefs: UserPrefs,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onReconnect: () -> Unit = {}
) {
    var showSettings by remember { mutableStateOf(false) }
    if (showSettings) SettingsDialog(onDismiss = { showSettings = false })

    Row(
        Modifier.fillMaxWidth().background(Surf).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Brand + settings gear
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("open", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                fontFamily = Mono, color = Color(0xFFF5F6F4), letterSpacing = 1.sp)
            Text("RS", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                fontFamily = Mono, color = Accent, letterSpacing = 1.sp)
            Text("_", fontSize = 14.sp, fontWeight = FontWeight.Bold,
                fontFamily = Mono, color = Color(0xFFF5F6F4))
            Spacer(Modifier.width(6.dp))
            Text("⚙", fontSize = 12.sp, color = Dim,
                modifier = Modifier.clickable { showSettings = true })
        }
        // Mode badge
        val (modeColor, modeText) = when (vs.driveMode.label) {
            "Sport" -> Grn to "SPORT"; "Track" -> Org to "TRACK"
            "Drift" -> Red to "DRIFT"; else -> Accent to "NORMAL"
        }
        Text(modeText, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
            color = Color.Black, modifier = Modifier
                .background(modeColor, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp))
        // Gear
        Text(vs.gearDisplay, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = Accent)
        // ESC
        Text(vs.escStatus.label, fontSize = 10.sp, fontFamily = Mono, color = Dim)
        // Speed (unit-aware)
        val speedStr = "${prefs.displaySpeed(vs.speedKph)} ${prefs.speedLabel}"
        Text(speedStr, fontSize = 11.sp, fontFamily = Mono, color = Txt)
        // Connection
        val connLabel = when {
            vs.isConnected -> "● LIVE"
            vs.isIdle      -> "⊙ RETRY"
            else           -> "○ OFFLINE"
        }
        val connColor = when {
            vs.isConnected -> Grn
            vs.isIdle      -> Gold
            else           -> Red
        }
        Text(connLabel, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
            color = connColor,
            modifier = Modifier.clickable {
                when {
                    vs.isConnected -> onDisconnect()
                    vs.isIdle      -> onReconnect()
                    else           -> onConnect()
                }
            })
    }
}

@Composable fun TabRow(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("DASH", "AWD", "PERF", "TEMPS", "TUNE", "TPMS", "CTRL", "DIAG")
    Row(Modifier.fillMaxWidth().background(Surf).height(36.dp)) {
        tabs.forEachIndexed { i, label ->
            Box(Modifier.weight(1f).fillMaxHeight()
                .clickable { onSelect(i) }
                .then(if (i == selected) Modifier.background(Color(0x1500A8E8)) else Modifier),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = Mono, color = if (i == selected) Accent else Dim)
                    if (i == selected) Box(Modifier.width(28.dp).height(2.dp).background(Accent))
                }
            }
        }
    }
}

// ═══ DASH PAGE ═══════════════════════════════════════════════
@Composable fun DashPage(v: VehicleState, p: UserPrefs) {
    val (boostVal, boostLbl) = p.displayBoost(v.boostKpa)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GaugeBox("BOOST", boostVal, boostLbl, Modifier.weight(1f))
            GaugeBox("RPM", "${v.rpm.roundToInt()}", "", Modifier.weight(1.5f), large = true)
            GaugeBox("SPEED", p.displaySpeed(v.speedKph), p.speedLabel, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        InfoRow(listOf(
            "THROTTLE" to "${v.throttlePct.roundToInt()}%",
            "BRAKE"    to "%.1f".format(v.brakePressure),
            "AWD"      to v.frontRearSplit,
            "TORQUE"   to "${v.torqueAtTrans.roundToInt()} Nm"))
        InfoRow(listOf(
            "OIL"     to "${p.displayTemp(v.oilTempC)}${p.tempLabel}",
            "COOLANT" to "${p.displayTemp(v.coolantTempC)}${p.tempLabel}",
            "INTAKE"  to "${p.displayTemp(v.intakeTempC)}${p.tempLabel}",
            "BATT"    to "%.1f".format(v.batteryVoltage) + "V"))
        InfoRow(listOf(
            "LAT G"  to "%.2f".format(v.lateralG),
            "LON G"  to "%.2f".format(v.longitudinalG),
            "STEER"  to "%.1f".format(v.steeringAngle) + "°",
            "YAW"    to "%.1f".format(v.yawRate)))
        InfoRow(listOf(
            "FUEL"  to "${v.fuelLevelPct.roundToInt()}%",
            "LOAD"  to "%.0f".format(v.calcLoad) + "%",
            "AFR"   to "%.2f".format(v.commandedAfr),
            "FPS"   to "${v.framesPerSecond.roundToInt()}"))
        InfoRow(listOf(
            "ODO"   to if (v.odometerKm >= 0) "${v.odometerKm} km" else "--",
            "SOC"   to if (v.batterySoc >= 0) "${v.batterySoc.roundToInt()}%" else "--",
            "12V"   to "%.1f".format(v.batteryVoltage) + "V",
            "MODE"  to v.driveMode.label.uppercase()))
    }
}

// ═══ AWD PAGE ════════════════════════════════════════════════
@Composable fun AwdPage(v: VehicleState, p: UserPrefs) {
    Column(Modifier.fillMaxSize().padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("FRONT AXLE", fontSize = 11.sp, color = Dim, fontFamily = Mono)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WheelBox("FL", "%.1f".format(v.wheelSpeedFL))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(v.frontRearSplit, fontSize = 32.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = Accent)
                Text("FRONT / REAR", fontSize = 9.sp, color = Dim)
            }
            WheelBox("FR", "%.1f".format(v.wheelSpeedFR))
        }
        Spacer(Modifier.height(4.dp))
        Text("▼ PTU: ${p.displayTemp(v.ptuTempC)}${p.tempLabel} ▼", fontSize = 10.sp, color = Dim)
        Spacer(Modifier.height(4.dp))
        Row(Modifier.width(300.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("L ${v.awdLeftTorque.roundToInt()}Nm", fontSize = 10.sp, color = Dim, fontFamily = Mono)
            Text("${v.awdRightTorque.roundToInt()}Nm R", fontSize = 10.sp, color = Dim, fontFamily = Mono)
        }
        val total = v.totalRearTorque
        val leftPct = if (total > 0) (v.awdLeftTorque / total).toFloat() else 0.5f
        Row(Modifier.width(300.dp).height(18.dp).background(Brd, RoundedCornerShape(9.dp))) {
            Box(Modifier.weight(leftPct.coerceIn(0.01f, 0.99f)).fillMaxHeight()
                .background(Accent, RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp)))
            Box(Modifier.weight((1f - leftPct).coerceIn(0.01f, 0.99f)).fillMaxHeight()
                .background(Grn, RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp)))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WheelBox("RL", "%.1f".format(v.wheelSpeedRL))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("RDU: ${p.displayTemp(v.rduTempC)}${p.tempLabel}", fontSize = 11.sp, color = Dim, fontFamily = Mono)
                Text("Max: ${v.awdMaxTorque.roundToInt()}Nm", fontSize = 11.sp, color = Dim, fontFamily = Mono)
            }
            WheelBox("RR", "%.1f".format(v.wheelSpeedRR))
        }
        Text("REAR AXLE", fontSize = 11.sp, color = Dim, fontFamily = Mono)
        Spacer(Modifier.height(8.dp))
        val avgF = (v.wheelSpeedFL + v.wheelSpeedFR) / 2
        val avgR = (v.wheelSpeedRL + v.wheelSpeedRR) / 2
        InfoRow(listOf(
            "F/R DELTA"  to "%.1f".format(avgR - avgF),
            "L/R DELTA"  to "%.1f".format(v.wheelSpeedRR - v.wheelSpeedRL),
            "REAR BIAS"  to v.rearLeftRightBias))
    }
}

// ═══ PERF PAGE ═══════════════════════════════════════════════
@Composable fun PerfPage(v: VehicleState, p: UserPrefs, onReset: () -> Unit) {
    val (boostVal, boostLbl) = p.displayBoost(v.boostKpa)
    val (peakBoostVal, _) = p.displayBoost(v.peakBoostPsi * 0.06894757 + 101.325)
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Cell("LAT G",    "%.2f".format(v.lateralG) + " g")
                Cell("LON G",    "%.2f".format(v.longitudinalG) + " g")
                Cell("COMBINED", "%.2f".format(v.combinedG) + " g")
                Cell("STEER",    "%.1f".format(v.steeringAngle) + "°")
                Cell("YAW",      "%.1f".format(v.yawRate) + "°/s")
                Cell("SPEED",    "${p.displaySpeed(v.speedKph)} ${p.speedLabel}")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Cell("PEAK LAT",   "%.2f".format(v.peakLateralG) + " g", Gold)
                Cell("PEAK LON",   "%.2f".format(v.peakLongitudinalG) + " g", Gold)
                Cell("PEAK BOOST", "$peakBoostVal $boostLbl", Gold)
                Cell("PEAK RPM",   "${v.peakRpm.roundToInt()}", Gold)
                Cell("THROTTLE",   "${v.throttlePct.roundToInt()}%")
                Cell("BRAKE",      "%.1f".format(v.brakePressure) + " bar")
            }
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().clickable { onReset() }
            .background(Surf, RoundedCornerShape(6.dp))
            .border(1.dp, Red.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
            .padding(8.dp), horizontalArrangement = Arrangement.Center) {
            Text("RESET PEAKS ↺", color = Red, fontFamily = Mono, fontWeight = FontWeight.Bold, fontSize = 13.sp)
        }
    }
}

// ═══ TEMPS PAGE ══════════════════════════════════════════════
@Composable fun TempsPage(v: VehicleState, p: UserPrefs) {
    fun tempColor(c: Double, warnC: Double = 100.0, critC: Double = 110.0) =
        if (c > critC) Red else if (c > warnC) Org else Txt

    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TempGauge("ENGINE OIL", p.displayTemp(v.oilTempC), p.tempLabel,
                tempColor(v.oilTempC), "(INFERRED)", Modifier.weight(1f))
            TempGauge("COOLANT", p.displayTemp(v.coolantTempC), p.tempLabel,
                tempColor(v.coolantTempC), "", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TempGauge("INTAKE AIR",  p.displayTemp(v.intakeTempC),  p.tempLabel, Txt, "", Modifier.weight(1f))
            TempGauge("AMBIENT",     p.displayTemp(v.ambientTempC), p.tempLabel, Txt, "", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TempGauge("RDU (REAR DIFF)",  p.displayTemp(v.rduTempC), p.tempLabel,
                tempColor(v.rduTempC), "", Modifier.weight(1f))
            TempGauge("PTU (TRANSFER)",   p.displayTemp(v.ptuTempC), p.tempLabel,
                tempColor(v.ptuTempC), "", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TempGauge("CHARGE AIR", p.displayTemp(v.chargeAirTempC), p.tempLabel, Txt, "", Modifier.weight(1f))
            TempGauge("CATALYTIC",  p.displayTemp(v.catalyticTempC), p.tempLabel,
                tempColor(v.catalyticTempC, 500.0, 700.0), "", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (v.cabinTempC > -90)
                TempGauge("CABIN", p.displayTemp(v.cabinTempC), p.tempLabel, Txt, "(BCM)", Modifier.weight(1f))
            else
                TempGauge("CABIN", "--", p.tempLabel, Dim, "(BCM — polling)", Modifier.weight(1f))
            if (v.batteryTempC > -90)
                TempGauge("BATT TEMP", p.displayTemp(v.batteryTempC), p.tempLabel,
                    tempColor(v.batteryTempC, 40.0, 60.0), "(BCM)", Modifier.weight(1f))
            else
                TempGauge("BATT TEMP", "--", p.tempLabel, Dim, "(BCM — polling)", Modifier.weight(1f))
        }
        if (v.isReadyToRace) {
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().background(Grn.copy(alpha = 0.15f), RoundedCornerShape(8.dp))
                .border(1.dp, Grn, RoundedCornerShape(8.dp)).padding(12.dp),
                contentAlignment = Alignment.Center) {
                Text("READY TO RACE", fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    fontFamily = Mono, color = Grn)
            }
        }
    }
}

// ═══ TUNE PAGE ═══════════════════════════════════════════════
@Composable fun TunePage(v: VehicleState, p: UserPrefs) {
    val (boostVal, boostLbl) = p.displayBoost(v.boostKpa)
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GaugeBox("AFR ACT",  "%.2f".format(v.afrActual),   ":1", Modifier.weight(1f))
            GaugeBox("AFR DES",  "%.1f".format(v.afrDesired),  ":1", Modifier.weight(1f))
            GaugeBox("LAMBDA",   "%.3f".format(v.lambdaActual), "λ", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        InfoRow(listOf(
            "ETC ACT" to "%.1f".format(v.etcAngleActual) + "°",
            "ETC DES" to "%.1f".format(v.etcAngleDesired) + "°",
            "WGDC"    to "%.0f".format(v.wgdcDesired) + "%"))
        InfoRow(listOf(
            "TIP ACT" to "%.1f".format(v.tipActualPsi) + " PSI",
            "TIP DES" to "%.1f".format(v.tipDesiredPsi) + " PSI",
            "KR CYL1" to "%.2f".format(v.ignCorrCyl1) + "°"))
        InfoRow(listOf(
            "LOAD"   to "%.0f".format(v.calcLoad) + "%",
            "TIMING" to "%.1f".format(v.timingAdvance) + "°",
            "OAR"    to "%.0f".format(v.octaneAdjustRatio * 100) + "%"))
        InfoRow(listOf(
            "SHORT FT" to "%.1f".format(v.shortFuelTrim) + "%",
            "LONG FT"  to "%.1f".format(v.longFuelTrim) + "%",
            "FUEL RAIL" to "%.0f".format(v.fuelRailPsi) + " PSI"))
        InfoRow(listOf(
            "VCT-I"   to "%.1f".format(v.vctIntakeAngle) + "°",
            "VCT-E"   to "%.1f".format(v.vctExhaustAngle) + "°",
            "CHG AIR" to "${p.displayTemp(v.chargeAirTempC)}${p.tempLabel}"))
        InfoRow(listOf(
            "CAT TEMP" to "${p.displayTemp(v.catalyticTempC)}${p.tempLabel}",
            "OIL LIFE" to if (v.oilLifePct >= 0) "${v.oilLifePct.roundToInt()}%" else "--",
            "BARO"     to "${v.barometricPressure.roundToInt()} kPa"))
    }
}

// ═══ TPMS PAGE ═════════════════════════════════════════════
@Composable fun TpmsPage(v: VehicleState, p: UserPrefs) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        if (!v.hasTpmsData) {
            Text("Waiting for TPMS data...", fontSize = 16.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center)
            Text("Passive CAN 0x340 — MS-CAN bridged via GWM",
                fontSize = 12.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        } else {
            Text("TPMS+", fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
                color = Accent, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("Live tire pressures — passive CAN 0x340",
                fontSize = 11.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TireBox("LF", v.tirePressLF, v.tireTempLF, p, Modifier.weight(1f))
                Spacer(Modifier.weight(0.5f))
                TireBox("RF", v.tirePressRF, v.tireTempRF, p, Modifier.weight(1f))
            }
            Text("FRONT", fontSize = 10.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TireBox("LR", v.tirePressLR, v.tireTempLR, p, Modifier.weight(1f))
                Spacer(Modifier.weight(0.5f))
                TireBox("RR", v.tirePressRR, v.tireTempRR, p, Modifier.weight(1f))
            }
            Text("REAR", fontSize = 10.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))
            val spread = v.maxTirePressSpread
            val statusItems = mutableListOf<Pair<String, String>>()
            statusItems.add("SPREAD" to "${p.displayTire(spread)} ${p.tireLabel}")
            if (v.anyTireLow(p.tireLowPsi.toDouble())) statusItems.add("WARNING" to "LOW TIRE!")
            if (v.oilLifePct >= 0) statusItems.add("OIL LIFE" to "${v.oilLifePct.roundToInt()}%")
            InfoRow(statusItems)
        }
    }
}

@Composable fun TireBox(label: String, pressurePsi: Double, tempC: Double, p: UserPrefs, modifier: Modifier) {
    val isLow = p.isTireLow(pressurePsi)
    val borderColor = if (isLow) Red else Brd
    val pressColor  = if (isLow) Red else Txt

    Column(
        modifier
            .background(Surf, RoundedCornerShape(8.dp))
            .border(2.dp, borderColor, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 10.sp, color = Dim, fontFamily = Mono, letterSpacing = 1.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(p.displayTire(pressurePsi), fontSize = 38.sp, fontWeight = FontWeight.Bold,
                fontFamily = Mono, color = pressColor)
            Text(p.tireLabel, fontSize = 12.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
        }
        if (tempC > -90) {
            Text("${p.displayTemp(tempC)}${p.tempLabel}", fontSize = 14.sp, color = Dim, fontFamily = Mono)
        }
    }
}

// ═══ REUSABLE COMPONENTS ═════════════════════════════════════

@Composable fun GaugeBox(label: String, value: String, unit: String, modifier: Modifier, large: Boolean = false) {
    Column(
        modifier.background(Surf, RoundedCornerShape(8.dp)).border(1.dp, Brd, RoundedCornerShape(8.dp))
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 10.sp, color = Dim, letterSpacing = 1.sp, fontFamily = Mono)
        Text(value, fontSize = if (large) 42.sp else 32.sp, fontWeight = FontWeight.Bold,
            fontFamily = Mono, color = Txt, lineHeight = if (large) 44.sp else 34.sp)
        if (unit.isNotEmpty()) Text(unit, fontSize = 10.sp, color = Dim)
    }
}

@Composable fun TempGauge(label: String, value: String, unit: String, color: Color, sub: String, modifier: Modifier) {
    Column(
        modifier.background(Surf, RoundedCornerShape(8.dp)).border(1.dp, Brd, RoundedCornerShape(8.dp))
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 10.sp, color = Dim, letterSpacing = 1.sp, fontFamily = Mono)
        Text(value, fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = color)
        Text(unit + if (sub.isNotEmpty()) " $sub" else "", fontSize = 10.sp, color = Dim)
    }
}

@Composable fun WheelBox(label: String, speed: String) {
    Column(
        Modifier.width(90.dp).background(Surf, RoundedCornerShape(6.dp))
            .border(1.dp, Brd, RoundedCornerShape(6.dp)).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(label, fontSize = 9.sp, color = Dim, fontFamily = Mono)
        Text(speed, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = Txt)
    }
}

@Composable fun InfoRow(items: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, value) -> Cell(label, value, modifier = Modifier.weight(1f)) }
    }
}

@Composable fun Cell(label: String, value: String, valueColor: Color = Txt, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Surf, RoundedCornerShape(6.dp)).border(1.dp, Brd, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, fontSize = 9.sp, color = Dim, fontFamily = Mono)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = valueColor)
    }
}

// ═══ DIAG PAGE ════════════════════════════════════════════════
@Composable fun DebugPage(lines: List<String>, v: VehicleState) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var exporting by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
            Text("DIAGNOSTICS", fontSize = 12.sp, fontWeight = FontWeight.Bold,
                fontFamily = Mono, color = Org)
            Spacer(Modifier.height(6.dp))

            // ── Session info ──────────────────────────────────────────────
            val sessionMs = DiagnosticLogger.sessionDurationMs
            val frameCount = DiagnosticLogger.frameInventorySnapshot.values.sumOf { it.totalReceived }
            val issueCount = DiagnosticLogger.frameInventorySnapshot.values.sumOf { it.validationIssues.size }
            InfoRow(listOf(
                "FPS"   to "${v.framesPerSecond.roundToInt()}",
                "MODE"  to v.dataMode,
                "CONN"  to if (v.isConnected) "LIVE" else "OFF"))
            InfoRow(listOf(
                "SESSION" to DiagnosticLogger.formatDuration(sessionMs),
                "FRAMES"  to "$frameCount",
                "IDS"     to "${DiagnosticLogger.frameInventorySnapshot.size}"))
            if (issueCount > 0) {
                Spacer(Modifier.height(4.dp))
                Text("⚠ $issueCount validation issue(s) — capture snapshot to review",
                    fontSize = 10.sp, color = Org, fontFamily = Mono)
            }

            // ── Capture snapshot button ───────────────────────────────────
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier.weight(1f)
                        .background(if (!exporting) Accent else Dim, RoundedCornerShape(6.dp))
                        .clickable(enabled = !exporting) {
                            exporting = true
                            scope.launch {
                                DiagnosticExporter.share(ctx)
                                exporting = false
                            }
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        if (exporting) "BUILDING..." else "⬆ CAPTURE & SHARE SNAPSHOT",
                        fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
                        color = if (!exporting) Color(0xFF0A0A0A) else Txt
                    )
                }
            }
            Spacer(Modifier.height(4.dp))
            Text("Exports a ZIP (summary .txt + full .json) via the Android share sheet.\n" +
                 "Share via Gmail, Drive, WhatsApp, etc. ZIP contains: frame inventory,\n" +
                 "decode trace, FPS timeline, validation flags, full vehicle state.",
                fontSize = 9.sp, color = Dim, fontFamily = Mono)

            Spacer(Modifier.height(12.dp))
            HorizontalDivider(color = Brd)
            Spacer(Modifier.height(8.dp))

            // ── Live CAN debug output ─────────────────────────────────────
            Text("Live WiCAN output (last ${lines.size} lines, newest ↓):",
                fontSize = 9.sp, color = Dim, fontFamily = Mono)
            Spacer(Modifier.height(4.dp))
            lines.forEach { line ->
                Text(line, fontSize = 10.sp, fontFamily = Mono, color = Txt,
                    modifier = Modifier.padding(vertical = 1.dp))
            }
            if (lines.isEmpty() && v.isConnected) {
                Text("Connected — waiting for first CAN frame...",
                    fontSize = 10.sp, color = Org, fontFamily = Mono)
            } else if (lines.isEmpty()) {
                Text("Connect to WiCAN to see raw output.", fontSize = 10.sp, color = Dim, fontFamily = Mono)
            }

            // ── Frame inventory preview ───────────────────────────────────
            val inv = DiagnosticLogger.frameInventorySnapshot
            if (inv.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                HorizontalDivider(color = Brd)
                Spacer(Modifier.height(8.dp))
                Text("FRAME INVENTORY (${inv.size} IDs)", fontSize = 9.sp, color = Dim, fontFamily = Mono, letterSpacing = 1.sp)
                Spacer(Modifier.height(4.dp))
                inv.entries.sortedBy { it.key }.forEach { (id, info) ->
                    val decoded = if (info.lastDecoded.isEmpty()) "(no decoder)" else info.lastDecoded
                    val issColor = if (info.validationIssues.isNotEmpty()) Org else Dim
                    Text(
                        "0x%03X  ×%-6d  %s".format(id, info.totalReceived, decoded.take(45)),
                        fontSize = 9.sp, fontFamily = Mono, color = issColor,
                        modifier = Modifier.padding(vertical = 1.dp)
                    )
                    if (info.validationIssues.isNotEmpty()) {
                        info.validationIssues.forEach { issue ->
                            Text("         ⚠ $issue", fontSize = 9.sp, fontFamily = Mono, color = Org)
                        }
                    }
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }
}

// ═══ CTRL PAGE ═══════════════════════════════════════════════
@Composable fun CtrlPage(v: VehicleState, snackbarHostState: SnackbarHostState) {
    val scope = rememberCoroutineScope()
    val isFw  by OpenRSDashApp.instance.isOpenRsFirmware.collectAsState()
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {

        // ── Drive Mode ─────────────────────────────────────────────────────
        CtrlSection("DRIVE MODE") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                DriveModeBtn(v.driveMode == DriveMode.NORMAL, Accent, "N", "NORMAL", Modifier.weight(1f))
                DriveModeBtn(v.driveMode == DriveMode.SPORT,  Grn,   "S", "SPORT",  Modifier.weight(1f))
                DriveModeBtn(v.driveMode == DriveMode.TRACK,  Org,   "T", "TRACK",  Modifier.weight(1f))
                DriveModeBtn(v.driveMode == DriveMode.DRIFT,  Red,   "D", "DRIFT",  Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            val modeColor = when (v.driveMode) {
                DriveMode.SPORT -> Grn; DriveMode.TRACK -> Org; DriveMode.DRIFT -> Red; else -> Accent
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text("CURRENT MODE (from CAN 0x1B0)", fontSize = 9.sp, color = Dim, fontFamily = Mono)
                Text(v.driveMode.label.uppercase(), fontSize = 9.sp, color = modeColor,
                    fontFamily = Mono, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(4.dp))
            Text("Use the steering wheel MODE button to change drive mode. Buttons above reflect live CAN state.",
                fontSize = 9.sp, color = Dim, fontFamily = Mono)
        }

        // ── ESC ────────────────────────────────────────────────────────────
        CtrlSection("ESC — ELECTRONIC STABILITY CONTROL") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                EscBtn(v.escStatus == EscStatus.ON,      Grn, "ESC ON",  Modifier.weight(1f))
                EscBtn(v.escStatus == EscStatus.PARTIAL, Org, "SPORT",   Modifier.weight(1f))
                EscBtn(v.escStatus == EscStatus.OFF,     Red, "ESC OFF", Modifier.weight(1f))
            }
            Spacer(Modifier.height(6.dp))
            Text("Current: ${v.escStatus.label} (from CAN 0x1C0). Use ESC button in car to toggle.",
                fontSize = 9.sp, color = Dim, fontFamily = Mono)
        }

        // ── Features ───────────────────────────────────────────────────────
        CtrlSection(if (isFw) "FEATURES — openrs-fw active" else "FEATURES — requires openrs-fw v1.0") {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FeatureBtn(
                    label = "LAUNCH\nCONTROL\n${if (isFw) "● ENABLED" else "○ OFF"}",
                    color = if (isFw) Grn else Dim,
                    modifier = Modifier.weight(1f)
                ) {
                    if (!isFw) scope.launch { snackbarHostState.showSnackbar("Requires openrs-fw — flash to unlock") }
                    // else: actual LC toggle (future)
                }
                FeatureBtn(
                    label = "AUTO S/S\nKILL\n${if (isFw) "● ENABLED" else "○ OFF"}",
                    color = if (isFw) Grn else Dim,
                    modifier = Modifier.weight(1f)
                ) {
                    if (!isFw) scope.launch { snackbarHostState.showSnackbar("Requires openrs-fw — flash to unlock") }
                }
            }
            Spacer(Modifier.height(6.dp))
            if (isFw) {
                Text("✓ openRS_ firmware detected — features unlocked.", fontSize = 9.sp, color = Grn, fontFamily = Mono)
            } else {
                Text("⚡  Flash openrs-fw to enable CAN write, LC, Auto S/S kill & more.",
                    fontSize = 9.sp, color = Dim, fontFamily = Mono)
            }
        }

        // ── Connection ─────────────────────────────────────────────────────
        CtrlSection("CONNECTION") {
            InfoRow(listOf(
                "STATUS" to if (v.isConnected) "CONNECTED" else "OFFLINE",
                "MODE"   to v.dataMode))
            InfoRow(listOf(
                "FPS" to "${v.framesPerSecond.roundToInt()}",
                "12V" to if (v.batteryVoltage > 0) "%.1fV".format(v.batteryVoltage) else "--"))
        }
    }
}

@Composable fun CtrlSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(8.dp))
            .border(1.dp, Brd, RoundedCornerShape(8.dp))
            .padding(12.dp)
    ) {
        Text(title, fontSize = 9.sp, color = Dim, letterSpacing = 1.5.sp, fontFamily = Mono)
        Spacer(Modifier.height(10.dp))
        content()
    }
}

@Composable fun DriveModeBtn(isActive: Boolean, color: Color, short: String, full: String, modifier: Modifier) {
    Column(
        modifier
            .background(if (isActive) color.copy(alpha = 0.18f) else Color.Transparent, RoundedCornerShape(6.dp))
            .border(2.dp, if (isActive) color else Brd, RoundedCornerShape(6.dp))
            .padding(vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(short, fontSize = 14.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
            color = if (isActive) color else Dim)
        if (isActive) {
            Text(full, fontSize = 7.sp, fontFamily = Mono, color = color)
        }
    }
}

@Composable fun EscBtn(isActive: Boolean, color: Color, label: String, modifier: Modifier) {
    Box(
        modifier
            .background(if (isActive) color.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(6.dp))
            .border(1.dp, if (isActive) color else Brd, RoundedCornerShape(6.dp))
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
            color = if (isActive) color else Dim)
    }
}

@Composable fun FeatureBtn(label: String, modifier: Modifier, color: Color = Dim, onClick: () -> Unit = {}) {
    Box(
        modifier
            .background(if (color != Dim) color.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(6.dp))
            .border(1.dp, if (color != Dim) color else Brd, RoundedCornerShape(6.dp))
            .clickable { onClick() }
            .padding(vertical = 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
            color = color, textAlign = TextAlign.Center)
    }
}
