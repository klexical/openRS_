package com.openrs.dash.auto

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.IBinder
import androidx.activity.compose.setContent
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.car.CarActivity
import com.openrs.dash.OpenRSDashApp
import com.openrs.dash.data.VehicleState
import com.openrs.dash.service.CanDataService
import com.openrs.dash.ui.*
import kotlin.math.roundToInt

/**
 * CarDashActivity — full custom Android Auto UI via aauto-sdk.
 * Renders the exact same Compose UI as MainActivity in landscape orientation
 * on the vehicle head unit. Shares the same VehicleState StateFlow.
 */
class CarDashActivity : CarActivity() {

    private var service: CanDataService? = null
    private val conn = object : ServiceConnection {
        override fun onServiceConnected(n: ComponentName, b: IBinder) {
            service = (b as CanDataService.LocalBinder).getService()
        }
        override fun onServiceDisconnected(n: ComponentName) { service = null }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val i = Intent(this, CanDataService::class.java)
        try { startForegroundService(i) } catch (_: Exception) { startService(i) }
        bindService(i, conn, Context.BIND_AUTO_CREATE)

        setContent {
            val vs by OpenRSDashApp.instance.vehicleState.collectAsState()
            var tab by remember { mutableIntStateOf(0) }

            MaterialTheme(colorScheme = darkColorScheme(
                background = Bg, surface = Surf, primary = Accent
            )) {
                Column(Modifier.fillMaxSize().background(Bg)) {
                    AaHeader(vs,
                        onConnect    = { service?.startConnection() },
                        onDisconnect = { service?.stopConnection() }
                    )
                    AaTabRow(tab) { tab = it }
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

    override fun onDestroy() {
        try { unbindService(conn) } catch (_: Exception) {}
        super.onDestroy()
    }
}

// ── AA Header — openRS_ brand + mode + gear + ESC + connection ──────────────
@Composable
fun AaHeader(vs: VehicleState, onConnect: () -> Unit, onDisconnect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(Surf).padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Left — openRS_ brand
        Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
            Text("open", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                fontFamily = Mono, color = Color(0xFFF5F6F4))
            Text("RS", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                fontFamily = Mono, color = Accent)
            Text("_", fontSize = 18.sp, fontWeight = FontWeight.Bold,
                fontFamily = Mono, color = Color(0xFFF5F6F4))
        }

        // Centre — drive mode badge, gear, ESC
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            val (modeColor, modeText) = when (vs.driveMode.label) {
                "Sport" -> Grn to "SPORT"; "Track" -> Org to "TRACK"
                "Drift" -> Red to "DRIFT"; else -> Accent to "NORMAL"
            }
            Text(modeText, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
                color = Color.Black,
                modifier = Modifier.background(modeColor, RoundedCornerShape(3.dp))
                    .padding(horizontal = 10.dp, vertical = 3.dp))
            Spacer(Modifier.width(12.dp))
            Text(vs.gearDisplay, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                fontFamily = Mono, color = Accent)
            Spacer(Modifier.width(12.dp))
            Text(vs.escStatus.label, fontSize = 11.sp, fontFamily = Mono, color = Dim)
        }

        // Right — connection status + fps
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically) {
            val isConn = vs.isConnected
            Text(if (isConn) "● CONNECTED" else "○ OFFLINE",
                fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = Mono,
                color = if (isConn) Grn else Red,
                modifier = Modifier.clickable { if (isConn) onDisconnect() else onConnect() })
            if (isConn) {
                Spacer(Modifier.width(8.dp))
                Text("${vs.framesPerSecond.roundToInt()} fps",
                    fontSize = 10.sp, fontFamily = Mono, color = Dim)
            }
        }
    }
}

// ── AA Tab Row — same labels, slightly taller for head unit touch targets ────
@Composable
fun AaTabRow(selected: Int, onSelect: (Int) -> Unit) {
    val tabs = listOf("DASH", "AWD", "PERF", "TEMPS", "TUNE", "TPMS")
    Row(Modifier.fillMaxWidth().background(Surf).height(44.dp)) {
        tabs.forEachIndexed { i, label ->
            Box(
                Modifier.weight(1f).fillMaxHeight().clickable { onSelect(i) }
                    .then(if (i == selected)
                        Modifier.background(Color(0x1500AEEF))
                    else Modifier),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                        fontFamily = Mono, color = if (i == selected) Accent else Dim)
                    if (i == selected)
                        Box(Modifier.width(32.dp).height(2.dp).background(Accent))
                }
            }
        }
    }
}
