package com.openrs.dash.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.diagnostics.DiagnosticLogger
import com.openrs.dash.data.ForscanCatalog
import com.openrs.dash.data.ForscanModule
import com.openrs.dash.data.VehicleState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private data class ProbeEntry(
    val did: Int,
    val status: String,
    val responseHex: String
)

@Composable
fun DidProberSection(
    vs: VehicleState,
    onSendRawQuery: (suspend (responseId: Int, frame: String, timeoutMs: Long) -> ByteArray?)? = null
) {
    val ctx = LocalContext.current
    val accent = LocalThemeAccent.current
    val scope = rememberCoroutineScope()
    val clipboardManager = LocalClipboardManager.current

    var catalogData by remember { mutableStateOf(ForscanCatalog.catalog) }
    LaunchedEffect(Unit) {
        ForscanCatalog.load(ctx)
        catalogData = ForscanCatalog.catalog
    }
    val data = catalogData ?: return

    val probableModules = remember(data) {
        data.modules.filter { it.canRequestId.isNotEmpty() }
    }
    if (probableModules.isEmpty()) return

    var selectedIdx by remember { mutableIntStateOf(0) }
    var probing by remember { mutableStateOf(false) }
    var cancelled by remember { mutableStateOf(false) }
    var progress by remember { mutableIntStateOf(0) }
    var total by remember { mutableIntStateOf(0) }
    var probeStartMs by remember { mutableLongStateOf(0L) }
    val results = remember { mutableStateListOf<ProbeEntry>() }
    var expanded by remember { mutableStateOf(false) }

    val selectedModule = probableModules[selectedIdx.coerceIn(probableModules.indices)]
    val requestId = selectedModule.canRequestId.removePrefix("0x").toIntOrNull(16) ?: return
    val responseId = selectedModule.canResponseId.removePrefix("0x").toIntOrNull(16) ?: return

    SectionLabel("DID PROBER")
    Spacer(Modifier.height(4.dp))

    Column(
        Modifier
            .fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(1.dp, Brd, RoundedCornerShape(10.dp))
            .padding(10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        MonoLabel("SELECT ECU MODULE", 8.sp, Dim, letterSpacing = 0.15.sp)
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            probableModules.forEachIndexed { idx, mod ->
                val isSelected = idx == selectedIdx
                Box(
                    Modifier
                        .clip(RoundedCornerShape(6.dp))
                        .background(if (isSelected) accent.copy(0.15f) else Surf2)
                        .border(
                            1.dp,
                            if (isSelected) accent.copy(0.5f) else Brd,
                            RoundedCornerShape(6.dp)
                        )
                        .clickable(enabled = !probing) { selectedIdx = idx }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    MonoLabel(
                        mod.id, 10.sp,
                        if (isSelected) accent else Mid,
                        fontWeight = if (isSelected) FontWeight.Medium else FontWeight.Normal
                    )
                }
            }
        }

        MonoLabel(
            "${selectedModule.name} · ${selectedModule.canRequestId}→${selectedModule.canResponseId} · ${selectedModule.pids.size} PIDs",
            9.sp, Dim
        )

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val canProbe = !probing && vs.isConnected && onSendRawQuery != null
            Box(
                Modifier
                    .weight(1f)
                    .background(
                        if (canProbe) accent.copy(0.12f) else Dim.copy(0.08f),
                        RoundedCornerShape(8.dp)
                    )
                    .border(
                        1.dp,
                        if (canProbe) accent.copy(0.35f) else Dim.copy(0.2f),
                        RoundedCornerShape(8.dp)
                    )
                    .clickable(enabled = canProbe) {
                        probing = true
                        cancelled = false
                        results.clear()
                        progress = 0
                        probeStartMs = System.currentTimeMillis()

                        val dids = generateCandidateDids(selectedModule)
                        total = dids.size

                        scope.launch {
                            val sendFn = onSendRawQuery ?: return@launch
                            for (did in dids) {
                                if (cancelled) break
                                val frame = "t%03X80322%04X00000000\r".format(requestId, did)
                                val resp = kotlinx.coroutines.withContext(Dispatchers.IO) {
                                    sendFn(responseId, frame, 1_000L)
                                }
                                val entry = classifyResponse(did, resp)
                                // State mutations on Main thread (default dispatcher)
                                results.add(entry)
                                progress = results.size
                                kotlinx.coroutines.delay(100L)
                            }
                            probing = false
                            // Log probe results for diagnostic export
                            if (results.isNotEmpty()) {
                                DiagnosticLogger.recordProbeSession(
                                    module = selectedModule.id,
                                    requestId = requestId,
                                    responseId = responseId,
                                    results = results.map {
                                        DiagnosticLogger.ProbeResult(it.did, it.status, it.responseHex)
                                    }
                                )
                            }
                        }
                    }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel(
                    when {
                        probing -> {
                            val eta = if (progress > 0) {
                                val elapsed = System.currentTimeMillis() - probeStartMs
                                val avgPerProbe = elapsed / progress
                                val remainingMs = avgPerProbe * (total - progress)
                                val remainingSec = (remainingMs / 1000).toInt()
                                if (remainingSec >= 60) "~${remainingSec / 60} min"
                                else "~${remainingSec} sec"
                            } else ""
                            if (eta.isNotEmpty()) "PROBING $progress / $total — $eta"
                            else "PROBING $progress / $total ..."
                        }
                        !vs.isConnected -> "CONNECT TO PROBE"
                        else -> "▶  PROBE ${selectedModule.id}"
                    },
                    11.sp,
                    if (canProbe) accent else Dim,
                    letterSpacing = 0.08.sp
                )
            }

            if (probing) {
                Box(
                    Modifier
                        .width(72.dp)
                        .background(Orange.copy(0.08f), RoundedCornerShape(8.dp))
                        .border(1.dp, Orange.copy(0.3f), RoundedCornerShape(8.dp))
                        .clickable {
                            cancelled = true
                            probing = false
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("STOP", 10.sp, Orange)
                }
            }
        }

        if (probing && total > 0) {
            val frac = progress.toFloat() / total
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .background(Brd, RoundedCornerShape(1.5.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(frac)
                        .height(3.dp)
                        .background(accent, RoundedCornerShape(1.5.dp))
                )
            }
        }

        val found = results.count { it.status == "FOUND" }
        val rejected = results.count { it.status == "NRC" }
        val timeout = results.count { it.status == "TIMEOUT" }

        if (results.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                MonoLabel("$found found", 10.sp, Ok)
                MonoLabel("$rejected rejected", 10.sp, Orange.copy(0.7f))
                MonoLabel("$timeout timeout", 10.sp, Dim)
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .clickable { expanded = !expanded }
                    .padding(vertical = 4.dp)
            ) {
                MonoLabel(
                    if (expanded) "▾ HIDE RESULTS" else "▸ SHOW RESULTS (${results.size})",
                    9.sp, accent
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Surf2.copy(alpha = 0.5f), RoundedCornerShape(6.dp))
                        .padding(6.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp)
                ) {
                    val sortedResults = results.sortedWith(
                        compareByDescending<ProbeEntry> { it.status == "FOUND" }
                            .thenBy { it.did }
                    )
                    sortedResults.forEach { entry ->
                        ProbeResultRow(entry, accent)
                    }
                }
            }

            if (!probing) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .background(accent.copy(0.12f), RoundedCornerShape(8.dp))
                        .border(1.dp, accent.copy(0.35f), RoundedCornerShape(8.dp))
                        .clickable {
                            val tsv = buildString {
                                appendLine("DID\tSTATUS\tRESPONSE")
                                results.sortedWith(
                                    compareByDescending<ProbeEntry> { it.status == "FOUND" }
                                        .thenBy { it.did }
                                ).forEach { entry ->
                                    val didHex = "0x%04X".format(entry.did)
                                    val resp = entry.responseHex.ifEmpty { "\u2014" }
                                    appendLine("$didHex\t${entry.status}\t$resp")
                                }
                            }
                            clipboardManager.setText(AnnotatedString(tsv))
                            Toast
                                .makeText(ctx, "Copied ${results.size} results to clipboard", Toast.LENGTH_SHORT)
                                .show()
                        }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("EXPORT", 10.sp, accent, letterSpacing = 0.08.sp)
                }
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    MonoLabel(
        "Sends Mode 22 queries to discover valid DIDs on the selected ECU.",
        9.sp, Dim
    )
}

@Composable
private fun ProbeResultRow(
    entry: ProbeEntry,
    accent: androidx.compose.ui.graphics.Color
) {
    val color = when (entry.status) {
        "FOUND" -> Ok
        "NRC" -> Orange.copy(0.5f)
        else -> Dim.copy(0.4f)
    }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        MonoLabel("0x%04X".format(entry.did), 9.sp, if (entry.status == "FOUND") accent else Mid)
        MonoLabel(entry.status, 8.sp, color, fontWeight = FontWeight.Medium)
        if (entry.responseHex.isNotEmpty()) {
            MonoText(entry.responseHex, 8.sp, Dim, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Generate candidate DIDs to probe for a given module.
 * Uses common Ford DID ranges plus any known DIDs from the catalog.
 */
private fun generateCandidateDids(module: ForscanModule): List<Int> {
    val dids = mutableSetOf<Int>()

    val knownDids = module.pids
        .filter { it.did.isNotEmpty() }
        .mapNotNull { it.did.removePrefix("0x").toIntOrNull(16) }
    dids.addAll(knownDids)

    val ranges = when (module.id) {
        "PCM", "OBDII" -> listOf(0x0100..0x0FFF, 0xF400..0xF4FF)
        "BCM" -> listOf(0x2800..0x28FF, 0x4000..0x40FF, 0xDD00..0xDDFF)
        "AWD" -> listOf(0x1E00..0x1EFF, 0xEE00..0xEEFF)
        "PSCM" -> listOf(0xFD00..0xFDFF)
        else -> listOf(0x0100..0x01FF, 0xEE00..0xEEFF, 0xFD00..0xFDFF)
    }
    for (range in ranges) dids.addAll(range)

    return dids.sorted()
}

private fun classifyResponse(did: Int, data: ByteArray?): ProbeEntry {
    if (data == null || data.size < 2) return ProbeEntry(did, "TIMEOUT", "")
    val svc = data[1].toInt() and 0xFF
    return when (svc) {
        0x62 -> ProbeEntry(
            did, "FOUND",
            data.drop(4).joinToString(" ") { "%02X".format(it) }
        )
        0x7F -> {
            val code = if (data.size > 3) data[3].toInt() and 0xFF else null
            val label = if (code != null) "NRC 0x%02X — %s".format(code, nrcLabel(code)) else "NRC ??"
            ProbeEntry(did, "NRC", label)
        }
        else -> ProbeEntry(did, "TIMEOUT", data.joinToString(" ") { "%02X".format(it) })
    }
}

private fun nrcLabel(code: Int): String = when (code) {
    0x10 -> "general reject"
    0x11 -> "service not supported"
    0x12 -> "sub-function not supported"
    0x13 -> "incorrect message length"
    0x14 -> "response too long"
    0x22 -> "conditions not correct"
    0x31 -> "request out of range"
    0x33 -> "security access denied"
    0x35 -> "invalid key"
    0x72 -> "general programming failure"
    0x78 -> "request correctly received, response pending"
    else -> "NRC 0x%02X".format(code)
}
