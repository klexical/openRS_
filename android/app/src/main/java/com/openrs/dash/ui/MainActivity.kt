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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.CanDataService
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

// ── Colors (matching Sync 3 HTML) ───────────────────────────
val Bg = Color(0xFF0A0A0A)
val Surf = Color(0xFF1A1A1A)
val Brd = Color(0xFF2A2A2A)
val Txt = Color(0xFFE0E0E0)
val Dim = Color(0xFF666666)
val Accent = Color(0xFF00A8E8)
val Grn = Color(0xFF00E676)
val Red = Color(0xFFFF5252)
val Org = Color(0xFFFF9800)
val Gold = Color(0xFFF8E63C)
val Mono = FontFamily.Monospace

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
        val perms = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
            perms.add(Manifest.permission.POST_NOTIFICATIONS)
        if (perms.isNotEmpty()) permLauncher.launch(perms.toTypedArray()) else startSvc()

        setContent {
            val vs by OpenRSDashApp.instance.vehicleState.collectAsState()
            var tab by remember { mutableIntStateOf(0) }
            MaterialTheme(colorScheme = darkColorScheme(background = Bg, surface = Surf, primary = Accent)) {
                Column(Modifier.fillMaxSize().background(Bg)) {
                    Header(vs, onConnect = { service?.startConnection() },
                        onDisconnect = { service?.stopConnection() })
                    TabRow(tab) { tab = it }
                    Box(Modifier.weight(1f)) {
                        when (tab) {
                            0 -> DashPage(vs)
                            1 -> AwdPage(vs)
                            2 -> PerfPage(vs, onReset = { service?.resetPeaks() })
                            3 -> TempsPage(vs)
                            4 -> TunePage(vs)
                            5 -> TpmsPage(vs)
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

@Composable fun Header(vs: VehicleState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(Surf).padding(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically) {
        Text("RS DASH", fontSize = 14.sp, fontWeight = FontWeight.Bold,
            fontFamily = Mono, color = Accent, letterSpacing = 2.sp)
        // Mode badge
        val (modeColor, modeText) = when (vs.driveMode.label) {
            "Sport" -> Grn to "SPORT"; "Track" -> Org to "TRACK"
            "Drift" -> Red to "DRIFT"; else -> Accent to "NORMAL"
        }
        Text(modeText, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
            color = Color.Black, modifier = Modifier.background(modeColor, RoundedCornerShape(3.dp))
                .padding(horizontal = 8.dp, vertical = 2.dp))
        // Gear
        Text(vs.gearDisplay, fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = Accent)
        // ESC
        Text(vs.escStatus.label, fontSize = 10.sp, fontFamily = Mono, color = Dim)
        // Connect button
        val isConn = vs.isConnected
        Text(if (isConn) "● CONNECTED" else "● OFFLINE",
            fontSize = 10.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
            color = if (isConn) Grn else Red,
            modifier = Modifier.clickable { if (isConn) onDisconnect() else onConnect() })
    }
}

@Composable fun TabRow(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("DASH", "AWD", "PERF", "TEMPS", "TUNE", "TPMS")
    Row(Modifier.fillMaxWidth().background(Surf).height(36.dp)) {
        tabs.forEachIndexed { i, label ->
            Box(Modifier.weight(1f).fillMaxHeight()
                .clickable { onSelect(i) }
                .then(if (i == selected) Modifier.border(width = 0.dp, color = Color.Transparent)
                    .background(Color(0x1500A8E8)) else Modifier),
                contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = Mono, color = if (i == selected) Accent else Dim)
                    if (i == selected) Box(Modifier.width(32.dp).height(2.dp).background(Accent))
                }
            }
        }
    }
}

// ═══ DASH PAGE ═══════════════════════════════════════════════
@Composable fun DashPage(v: VehicleState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        // Top gauges: Boost / RPM / Speed
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GaugeBox("BOOST", "%.1f".format(v.boostPsi), "PSI", Modifier.weight(1f))
            GaugeBox("RPM", "${v.rpm.roundToInt()}", "", Modifier.weight(1.5f), large = true)
            GaugeBox("SPEED", "${v.speedMph.roundToInt()}", "MPH", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        // Info rows
        InfoRow(listOf("THROTTLE" to "${v.throttlePct.roundToInt()}%",
            "BRAKE" to "%.1f".format(v.brakePressure),
            "AWD" to v.frontRearSplit,
            "TORQUE" to "${v.torqueAtTrans.roundToInt()} Nm"))
        InfoRow(listOf("OIL" to "${toF(v.oilTempC)}°F",
            "COOLANT" to "${toF(v.coolantTempC)}°F",
            "INTAKE" to "${toF(v.intakeTempC)}°F",
            "BATT" to "%.1f".format(v.batteryVoltage) + "V"))
        InfoRow(listOf("LAT G" to "%.2f".format(v.lateralG),
            "LON G" to "%.2f".format(v.longitudinalG),
            "STEER" to "%.1f".format(v.steeringAngle) + "°",
            "YAW" to "%.1f".format(v.yawRate)))
        InfoRow(listOf("FUEL" to "${v.fuelLevelPct.roundToInt()}%",
            "LOAD" to "%.0f".format(v.calcLoad) + "%",
            "AFR" to "%.2f".format(v.commandedAfr),
            "FPS" to "${v.framesPerSecond.roundToInt()}"))
    }
}

// ═══ AWD PAGE ════════════════════════════════════════════════
@Composable fun AwdPage(v: VehicleState) {
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
        Text("▼ PTU: ${toF(v.ptuTempC)}°F ▼", fontSize = 10.sp, color = Dim)
        Spacer(Modifier.height(4.dp))
        // Torque bar
        Row(Modifier.width(300.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("L ${v.awdLeftTorque.roundToInt()}Nm", fontSize = 10.sp, color = Dim, fontFamily = Mono)
            Text("${v.awdRightTorque.roundToInt()}Nm R", fontSize = 10.sp, color = Dim, fontFamily = Mono)
        }
        val total = v.totalRearTorque
        val leftPct = if (total > 0) (v.awdLeftTorque / total).toFloat() else 0.5f
        Row(Modifier.width(300.dp).height(18.dp).background(Brd, RoundedCornerShape(9.dp))) {
            Box(Modifier.weight(leftPct.coerceIn(0.01f, 0.99f)).fillMaxHeight().background(Accent, RoundedCornerShape(topStart = 9.dp, bottomStart = 9.dp)))
            Box(Modifier.weight((1f - leftPct).coerceIn(0.01f, 0.99f)).fillMaxHeight().background(Grn, RoundedCornerShape(topEnd = 9.dp, bottomEnd = 9.dp)))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            WheelBox("RL", "%.1f".format(v.wheelSpeedRL))
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("RDU: ${toF(v.rduTempC)}°F", fontSize = 11.sp, color = Dim, fontFamily = Mono)
                Text("Max: ${v.awdMaxTorque.roundToInt()}Nm", fontSize = 11.sp, color = Dim, fontFamily = Mono)
            }
            WheelBox("RR", "%.1f".format(v.wheelSpeedRR))
        }
        Text("REAR AXLE", fontSize = 11.sp, color = Dim, fontFamily = Mono)
        Spacer(Modifier.height(8.dp))
        val avgF = (v.wheelSpeedFL + v.wheelSpeedFR) / 2; val avgR = (v.wheelSpeedRL + v.wheelSpeedRR) / 2
        InfoRow(listOf("F/R DELTA" to "%.1f".format(avgR - avgF),
            "L/R DELTA" to "%.1f".format(v.wheelSpeedRR - v.wheelSpeedRL),
            "REAR BIAS" to v.rearLeftRightBias))
    }
}

// ═══ PERF PAGE ═══════════════════════════════════════════════
@Composable fun PerfPage(v: VehicleState, onReset: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            // G-force readouts
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Cell("LAT G", "%.2f".format(v.lateralG) + " g")
                Cell("LON G", "%.2f".format(v.longitudinalG) + " g")
                Cell("COMBINED", "%.2f".format(v.combinedG) + " g")
                Cell("STEER", "%.1f".format(v.steeringAngle) + "°")
                Cell("YAW", "%.1f".format(v.yawRate) + "°/s")
                Cell("SPEED", "${v.speedMph.roundToInt()} MPH")
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Cell("PEAK LAT", "%.2f".format(v.peakLateralG) + " g", Gold)
                Cell("PEAK LON", "%.2f".format(v.peakLongitudinalG) + " g", Gold)
                Cell("PEAK BOOST", "%.1f".format(v.peakBoostPsi) + " PSI", Gold)
                Cell("PEAK RPM", "${v.peakRpm.roundToInt()}", Gold)
                Cell("THROTTLE", "${v.throttlePct.roundToInt()}%")
                Cell("BRAKE", "%.1f".format(v.brakePressure) + " bar")
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
@Composable fun TempsPage(v: VehicleState) {
    Column(Modifier.fillMaxSize().padding(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TempGauge("ENGINE OIL", toF(v.oilTempC), "°F (INFERRED)",
                if (v.oilTempC > 110) Red else if (v.oilTempC > 100) Org else Txt, Modifier.weight(1f))
            TempGauge("COOLANT", toF(v.coolantTempC), "°F",
                if (v.coolantTempC > 110) Red else if (v.coolantTempC > 100) Org else Txt, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TempGauge("INTAKE AIR", toF(v.intakeTempC), "°F", Txt, Modifier.weight(1f))
            TempGauge("AMBIENT", toF(v.ambientTempC), "°F", Txt, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TempGauge("RDU (REAR DIFF)", toF(v.rduTempC), "°F",
                if (v.rduTempC > 110) Red else if (v.rduTempC > 100) Org else Txt, Modifier.weight(1f))
            TempGauge("PTU (TRANSFER)", toF(v.ptuTempC), "°F",
                if (v.ptuTempC > 110) Red else if (v.ptuTempC > 100) Org else Txt, Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TempGauge("CHARGE AIR", toF(v.chargeAirTempC), "°F", Txt, Modifier.weight(1f))
            TempGauge("CATALYTIC", toF(v.catalyticTempC), "°F",
                if (v.catalyticTempC > 700) Red else if (v.catalyticTempC > 500) Org else Txt, Modifier.weight(1f))
        }
        // RTR status
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

// ═══ TUNE PAGE (new OBD data) ════════════════════════════════
@Composable fun TunePage(v: VehicleState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        // AFR — top priority for tuning
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            GaugeBox("AFR ACT", "%.2f".format(v.afrActual), ":1", Modifier.weight(1f))
            GaugeBox("AFR DES", "%.1f".format(v.afrDesired), ":1", Modifier.weight(1f))
            GaugeBox("LAMBDA", "%.3f".format(v.lambdaActual), "λ", Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))

        // Boost control
        InfoRow(listOf(
            "ETC ACT" to "%.1f".format(v.etcAngleActual) + "°",
            "ETC DES" to "%.1f".format(v.etcAngleDesired) + "°",
            "WGDC" to "%.0f".format(v.wgdcDesired) + "%"))
        InfoRow(listOf(
            "TIP ACT" to "%.1f".format(v.tipActualPsi) + " PSI",
            "TIP DES" to "%.1f".format(v.tipDesiredPsi) + " PSI",
            "KR CYL1" to "%.2f".format(v.ignCorrCyl1) + "°"))

        // Traditional tune data
        InfoRow(listOf(
            "LOAD" to "%.0f".format(v.calcLoad) + "%",
            "TIMING" to "%.1f".format(v.timingAdvance) + "°",
            "OAR" to "%.0f".format(v.octaneAdjustRatio * 100) + "%"))
        InfoRow(listOf(
            "SHORT FT" to "%.1f".format(v.shortFuelTrim) + "%",
            "LONG FT" to "%.1f".format(v.longFuelTrim) + "%",
            "FUEL RAIL" to "%.0f".format(v.fuelRailPsi) + " PSI"))

        // VCT + temps
        InfoRow(listOf(
            "VCT-I" to "%.1f".format(v.vctIntakeAngle) + "°",
            "VCT-E" to "%.1f".format(v.vctExhaustAngle) + "°",
            "CHG AIR" to "${toF(v.chargeAirTempC)}°F"))
        InfoRow(listOf(
            "CAT TEMP" to "${toF(v.catalyticTempC)}°F",
            "OIL LIFE" to if (v.oilLifePct >= 0) "${v.oilLifePct.roundToInt()}%" else "--",
            "BARO" to "${v.barometricPressure.roundToInt()} kPa"))
    }
}

// ═══ TPMS PAGE ═════════════════════════════════════════════
@Composable fun TpmsPage(v: VehicleState) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(8.dp)) {
        if (!v.hasTpmsData) {
            Text("Waiting for TPMS data...", fontSize = 16.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.fillMaxWidth().padding(32.dp), textAlign = TextAlign.Center)
            Text("BCM 0x726 • PIDs 0x2813–0x2816", fontSize = 12.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        } else {
            // ── Car top-down view with 4-corner pressures ──
            Text("TPMS+", fontSize = 20.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
                color = Accent, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(4.dp))
            Text("Real numbers, not just low tire pressure warnings.",
                fontSize = 11.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))

            // Front tires
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TireBox("LF", v.tirePressLF, v.tireTempLF, Modifier.weight(1f))
                Spacer(Modifier.weight(0.5f))
                TireBox("RF", v.tirePressRF, v.tireTempRF, Modifier.weight(1f))
            }
            Text("FRONT", fontSize = 10.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(8.dp))

            // Rear tires
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TireBox("LR", v.tirePressLR, v.tireTempLR, Modifier.weight(1f))
                Spacer(Modifier.weight(0.5f))
                TireBox("RR", v.tirePressRR, v.tireTempRR, Modifier.weight(1f))
            }
            Text("REAR", fontSize = 10.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
            Spacer(Modifier.height(12.dp))

            // Status row
            val spread = v.maxTirePressSpread
            val statusItems = mutableListOf<Pair<String, String>>()
            statusItems.add("SPREAD" to "%.1f PSI".format(spread))
            if (v.anyTireLow) statusItems.add("WARNING" to "LOW TIRE!")
            if (v.oilLifePct >= 0) statusItems.add("OIL LIFE" to "${v.oilLifePct.roundToInt()}%")
            InfoRow(statusItems)
        }
    }
}

@Composable fun TireBox(label: String, pressurePsi: Double, tempC: Double, modifier: Modifier) {
    val isLow = pressurePsi in 0.0..30.0
    val borderColor = if (isLow) Red else Brd
    val pressColor = if (isLow) Red else Txt

    Column(modifier.background(Surf, RoundedCornerShape(8.dp))
        .border(2.dp, borderColor, RoundedCornerShape(8.dp))
        .padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = Dim, fontFamily = Mono, letterSpacing = 1.sp)
        Row(verticalAlignment = Alignment.Bottom) {
            Text("${pressurePsi.roundToInt()}", fontSize = 38.sp, fontWeight = FontWeight.Bold,
                fontFamily = Mono, color = pressColor)
            Text("PSI", fontSize = 12.sp, color = Dim, fontFamily = Mono,
                modifier = Modifier.padding(bottom = 6.dp, start = 2.dp))
        }
        if (tempC > -90) {
            val tempF = (tempC * 9.0 / 5.0 + 32).roundToInt()
            Text("$tempF°", fontSize = 14.sp, color = Dim, fontFamily = Mono)
        }
    }
}

// ═══ REUSABLE COMPONENTS ═════════════════════════════════════

@Composable fun GaugeBox(label: String, value: String, unit: String, modifier: Modifier, large: Boolean = false) {
    Column(modifier.background(Surf, RoundedCornerShape(8.dp)).border(1.dp, Brd, RoundedCornerShape(8.dp))
        .padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = Dim, letterSpacing = 1.sp, fontFamily = Mono)
        Text(value, fontSize = if (large) 42.sp else 32.sp, fontWeight = FontWeight.Bold,
            fontFamily = Mono, color = Txt, lineHeight = if (large) 44.sp else 34.sp)
        if (unit.isNotEmpty()) Text(unit, fontSize = 10.sp, color = Dim)
    }
}

@Composable fun TempGauge(label: String, tempF: Int, unit: String, color: Color, modifier: Modifier) {
    Column(modifier.background(Surf, RoundedCornerShape(8.dp)).border(1.dp, Brd, RoundedCornerShape(8.dp))
        .padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = Dim, letterSpacing = 1.sp, fontFamily = Mono)
        Text("$tempF", fontSize = 36.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = color)
        Text(unit, fontSize = 10.sp, color = Dim)
    }
}

@Composable fun WheelBox(label: String, speed: String) {
    Column(Modifier.width(90.dp).background(Surf, RoundedCornerShape(6.dp))
        .border(1.dp, Brd, RoundedCornerShape(6.dp)).padding(6.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 9.sp, color = Dim, fontFamily = Mono)
        Text(speed, fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = Mono, color = Txt)
    }
}

@Composable fun InfoRow(items: List<Pair<String, String>>) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { (label, value) ->
            Cell(label, value, modifier = Modifier.weight(1f))
        }
    }
}

@Composable fun Cell(label: String, value: String, valueColor: Color = Txt, modifier: Modifier = Modifier) {
    Row(modifier.background(Surf, RoundedCornerShape(6.dp)).border(1.dp, Brd, RoundedCornerShape(6.dp))
        .padding(horizontal = 8.dp, vertical = 5.dp),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 9.sp, color = Dim, fontFamily = Mono)
        Text(value, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, fontFamily = Mono, color = valueColor)
    }
}

fun toF(c: Double): Int = (c * 9.0 / 5.0 + 32).roundToInt()
