package com.openrs.dash.ui

import androidx.compose.animation.core.EaseInOut
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import com.openrs.dash.ui.Tokens.CardBorder
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.openrs.dash.can.BleDeviceScanner

/**
 * BLE device picker dialog — scans for WiCAN adapters (filtered to 0xFFE0 service)
 * and lets the user tap one to save it.
 */
@Composable
fun BleDevicePickerDialog(
    onDeviceSelected: (address: String, name: String) -> Unit,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val accent = LocalThemeAccent.current
    val scanner = remember { BleDeviceScanner(ctx) }
    val devices by scanner.devices.collectAsState()
    val scanning by scanner.scanning.collectAsState()
    var permDenied by remember { mutableStateOf(false) }

    // BLE permissions needed at scan time (not just at app startup)
    val blePerms = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            arrayOf(Manifest.permission.BLUETOOTH, Manifest.permission.BLUETOOTH_ADMIN)
        }
    }

    fun hasPerms(): Boolean = blePerms.all {
        ContextCompat.checkSelfPermission(ctx, it) == PackageManager.PERMISSION_GRANTED
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        if (results.values.all { it }) {
            permDenied = false
            scanner.startScan()
        } else {
            permDenied = true
        }
    }

    // Start scanning when dialog opens (with permission check), stop when it closes
    DisposableEffect(Unit) {
        if (hasPerms()) {
            scanner.startScan()
        } else {
            permLauncher.launch(blePerms)
        }
        onDispose { scanner.stopScan() }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .background(Bg, RoundedCornerShape(14.dp))
                .border(CardBorder, Brd, RoundedCornerShape(14.dp))
                .padding(20.dp)
        ) {
            MonoLabel("SCAN FOR DEVICES", 11.sp, accent,
                fontWeight = FontWeight.Bold, letterSpacing = 0.15.sp)

            Spacer(Modifier.height(16.dp))

            if (permDenied) {
                // Permission denied — show message
                Box(
                    Modifier.fillMaxWidth()
                        .background(Surf2, RoundedCornerShape(10.dp))
                        .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MonoLabel("Bluetooth permission required", 11.sp, Orange)
                        Spacer(Modifier.height(8.dp))
                        MonoLabel("Grant permission in Settings > Apps > openRS_", 9.sp, Dim)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Box(
                    Modifier.fillMaxWidth()
                        .background(accent.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                        .border(CardBorder, accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .clickable { permLauncher.launch(blePerms) }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("REQUEST PERMISSION", 10.sp, accent,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp)
                }
            } else if (devices.isEmpty() && !scanning) {
                // No devices found after scan completed
                Box(
                    Modifier.fillMaxWidth()
                        .background(Surf2, RoundedCornerShape(10.dp))
                        .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                        .padding(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        MonoLabel("No devices found", 11.sp, Dim)
                        Spacer(Modifier.height(8.dp))
                        MonoLabel("Make sure your WiCAN is powered on", 9.sp, Dim)
                    }
                }
                Spacer(Modifier.height(12.dp))
                // Retry button
                Box(
                    Modifier.fillMaxWidth()
                        .background(accent.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                        .border(CardBorder, accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                        .clickable {
                            if (hasPerms()) scanner.startScan()
                            else permLauncher.launch(blePerms)
                        }
                        .padding(12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("SCAN AGAIN", 10.sp, accent,
                        fontWeight = FontWeight.Bold, letterSpacing = 0.1.sp)
                }
            } else {
                // Device list
                devices.forEach { device ->
                    DeviceRow(
                        name = device.name,
                        address = device.address,
                        rssi = device.rssi,
                        onClick = {
                            scanner.stopScan()
                            onDeviceSelected(device.address, device.name)
                        }
                    )
                    Spacer(Modifier.height(6.dp))
                }

                if (devices.isEmpty() && scanning) {
                    // Scanning but nothing found yet
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Surf2, RoundedCornerShape(10.dp))
                            .padding(20.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        MonoLabel("Searching for WiCAN devices...", 10.sp, Dim)
                    }
                }
            }

            // Scanning indicator
            if (scanning) {
                Spacer(Modifier.height(12.dp))
                ScanningIndicator()
            }

            Spacer(Modifier.height(16.dp))

            // Cancel button
            Box(
                Modifier.fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(10.dp))
                    .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                    .clickable { onDismiss() }
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel("CANCEL", 10.sp, Dim, letterSpacing = 0.1.sp)
            }
        }
    }
}

/** Known adapter name patterns — devices not matching these get a warning hint. */
private val KNOWN_ADAPTER_NAMES = listOf("wican", "meatpi", "openrs")

private fun isKnownAdapter(name: String): Boolean {
    val lower = name.lowercase()
    return KNOWN_ADAPTER_NAMES.any { lower.contains(it) }
}

@Composable
private fun DeviceRow(
    name: String,
    address: String,
    rssi: Int,
    onClick: () -> Unit
) {
    val accent = LocalThemeAccent.current
    val known = isKnownAdapter(name)
    val signalBars = when {
        rssi > -50  -> 5
        rssi > -60  -> 4
        rssi > -70  -> 3
        rssi > -80  -> 2
        else        -> 1
    }

    Row(
        Modifier.fillMaxWidth()
            .background(
                Brush.horizontalGradient(listOf(accent.copy(0.08f), accent.copy(0.03f))),
                RoundedCornerShape(10.dp)
            )
            .border(CardBorder, accent.copy(0.2f), RoundedCornerShape(10.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Signal bars
        Row(
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.Bottom,
            modifier = Modifier.width(28.dp)
        ) {
            for (i in 1..5) {
                Box(
                    Modifier
                        .width(4.dp)
                        .height((4 + i * 3).dp)
                        .clip(RoundedCornerShape(1.dp))
                        .background(
                            if (i <= signalBars) accent else Brd
                        )
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            MonoLabel(name, 11.sp, Frost, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            if (known) {
                MonoLabel(address, 9.sp, Dim, letterSpacing = 0.05.sp)
            } else {
                MonoLabel("$address  \u2022  not a known adapter", 9.sp, Orange, letterSpacing = 0.05.sp)
            }
        }

        MonoLabel("${rssi}dB", 9.sp, Dim)
    }
}

@Composable
private fun ScanningIndicator() {
    val accent = LocalThemeAccent.current
    val anim = rememberInfiniteTransition(label = "scan")
    val alpha by anim.animateFloat(
        initialValue = 0.3f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(800, easing = EaseInOut), RepeatMode.Reverse),
        label = "scanAlpha"
    )

    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            Modifier.size(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(accent.copy(alpha = alpha))
        )
        Spacer(Modifier.width(6.dp))
        MonoLabel("Scanning...", 9.sp, accent.copy(alpha = alpha))
    }
}
