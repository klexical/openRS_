package com.openrs.dash.ui

import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import com.openrs.dash.ui.Tokens.CardBorder
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.data.ForscanCatalog
import com.openrs.dash.data.ForscanCatalogData
import com.openrs.dash.data.ForscanModule

@Composable
fun PidBrowserSection() {
    val ctx = LocalContext.current
    val accent = LocalThemeAccent.current

    var catalog by remember { mutableStateOf(ForscanCatalog.catalog) }
    LaunchedEffect(Unit) {
        ForscanCatalog.load(ctx)
        catalog = ForscanCatalog.catalog
    }
    val data = catalog ?: return

    var query by remember { mutableStateOf("") }
    var expandedModule by remember { mutableStateOf<String?>(null) }

    SectionLabel("PID CATALOG")
    Spacer(Modifier.height(4.dp))

    CoverageBanner(data, accent)
    Spacer(Modifier.height(8.dp))

    SearchField(query, onQueryChange = { query = it })
    Spacer(Modifier.height(8.dp))

    val lowerQuery = query.trim().lowercase()
    val filteredModules = if (lowerQuery.isEmpty()) {
        data.modules
    } else {
        data.modules.mapNotNull { mod ->
            val filtered = mod.pids.filter { pid ->
                pid.name.lowercase().contains(lowerQuery) ||
                    pid.description.lowercase().contains(lowerQuery)
            }
            if (filtered.isEmpty()) null
            else mod.copy(pids = filtered)
        }
    }

    Column(
        Modifier
            .fillMaxWidth()
            .background(Surf2, RoundedCornerShape(10.dp))
            .border(CardBorder, Brd, RoundedCornerShape(10.dp))
            .padding(6.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        if (filteredModules.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                MonoLabel("No PIDs matching \"$query\"", 10.sp, Dim)
            }
        } else {
            filteredModules.forEach { mod ->
                val isExpanded = expandedModule == mod.id || lowerQuery.isNotEmpty()
                ModuleRow(mod, isExpanded, accent) {
                    expandedModule = if (expandedModule == mod.id) null else mod.id
                }
            }
        }
    }

    Spacer(Modifier.height(4.dp))
    val withUnits = data.modules.sumOf { m -> m.pids.count { it.unit.isNotEmpty() } }
    val statusOnly = data.totalPids - withUnits
    MonoLabel(
        "FORScan Extended PID List · ${data.modules.size} modules · " +
            "$withUnits numeric ▥ · $statusOnly boolean ◻",
        9.sp, Dim, modifier = Modifier.padding(bottom = 12.dp)
    )
}

@Composable
private fun CoverageBanner(catalog: ForscanCatalogData, accent: androidx.compose.ui.graphics.Color) {
    val pct = if (catalog.totalPids > 0) catalog.monitoredPids.toFloat() / catalog.totalPids else 0f

    Column(
        Modifier
            .fillMaxWidth()
            .background(accent.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .border(CardBorder, accent.copy(alpha = 0.25f), RoundedCornerShape(10.dp))
            .padding(12.dp)
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonoLabel("MONITORED", 9.sp, Dim)
            MonoLabel(
                "${catalog.monitoredPids} / ${catalog.totalPids}  (${"%.1f".format(pct * 100)}%)",
                11.sp, accent, fontWeight = FontWeight.Medium
            )
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .background(Brd, RoundedCornerShape(2.dp))
        ) {
            Box(
                Modifier
                    .fillMaxWidth(pct)
                    .height(4.dp)
                    .background(accent, RoundedCornerShape(2.dp))
            )
        }
    }
}

@Composable
private fun SearchField(query: String, onQueryChange: (String) -> Unit) {
    BasicTextField(
        value = query,
        onValueChange = onQueryChange,
        singleLine = true,
        textStyle = TextStyle(
            fontFamily = ShareTechMono,
            fontSize = 11.sp,
            color = Frost
        ),
        cursorBrush = SolidColor(LocalThemeAccent.current),
        decorationBox = { inner ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(8.dp))
                    .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                    .padding(horizontal = 10.dp, vertical = 9.dp)
            ) {
                if (query.isEmpty()) {
                    MonoLabel("Search PIDs by name or description...", 11.sp, Dim.copy(0.6f))
                }
                inner()
            }
        }
    )
}

@Composable
private fun ModuleRow(
    mod: ForscanModule,
    isExpanded: Boolean,
    accent: androidx.compose.ui.graphics.Color,
    onToggle: () -> Unit
) {
    val monitored = mod.monitoredCount
    val total = mod.pids.size
    val coverageFraction = if (total > 0) monitored.toFloat() / total else 0f

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onToggle)
                .padding(horizontal = 8.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            MonoLabel(if (isExpanded) "▾" else "▸", 10.sp, Dim)
            Spacer(Modifier.width(6.dp))
            MonoLabel(mod.id, 10.sp, accent, fontWeight = FontWeight.Medium)
            if (mod.canRequestId.isNotEmpty()) {
                Spacer(Modifier.width(6.dp))
                MonoLabel(
                    "${mod.canRequestId}→${mod.canResponseId}",
                    7.sp, Dim.copy(0.6f)
                )
            }
            Spacer(Modifier.width(8.dp))
            MonoText(mod.name, 9.sp, Mid, modifier = Modifier.weight(1f))
            if (monitored > 0) {
                MonoLabel("$monitored/$total", 9.sp, Ok.copy(0.8f))
            } else {
                MonoLabel("$total", 9.sp, Dim)
            }
        }

        if (monitored > 0) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
                    .height(2.dp)
                    .background(Brd, RoundedCornerShape(1.dp))
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(coverageFraction)
                        .height(2.dp)
                        .background(accent.copy(alpha = 0.6f), RoundedCornerShape(1.dp))
                )
            }
        }

        AnimatedVisibility(
            visible = isExpanded,
            enter = expandVertically(),
            exit = shrinkVertically()
        ) {
            val sorted = mod.pids.sortedByDescending { it.status == "monitored" }
            val monitoredPids = sorted.takeWhile { it.status == "monitored" }
            val availablePids = sorted.dropWhile { it.status == "monitored" }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 4.dp, bottom = 4.dp),
                verticalArrangement = Arrangement.spacedBy(1.dp)
            ) {
                monitoredPids.forEach { pid -> PidRow(pid, accent) }
                if (monitoredPids.isNotEmpty() && availablePids.isNotEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .height(1.dp)
                            .background(Brd.copy(alpha = 0.5f))
                    )
                }
                availablePids.forEach { pid -> PidRow(pid, accent) }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PidRow(
    pid: com.openrs.dash.data.ForscanPid,
    accent: androidx.compose.ui.graphics.Color
) {
    val isMonitored = pid.status == "monitored"
    val hasUnit = pid.unit.isNotEmpty()
    val hasDid = pid.did.isNotEmpty()
    val clipboardManager = LocalClipboardManager.current
    val context = LocalContext.current
    val rowModifier = if (hasDid) {
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = {},
                onLongClick = {
                    clipboardManager.setText(AnnotatedString(pid.did))
                    Toast.makeText(context, "Copied ${pid.did}", Toast.LENGTH_SHORT).show()
                }
            )
            .padding(vertical = 3.dp)
    } else {
        Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp)
    }
    Row(
        rowModifier,
        verticalAlignment = Alignment.Top
    ) {
        MonoLabel(
            if (hasUnit) "▥" else "◻",
            8.sp,
            if (isMonitored) Ok else Dim.copy(0.35f),
            modifier = Modifier.padding(top = 2.dp)
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MonoLabel(
                    pid.name,
                    10.sp,
                    if (isMonitored) Frost else Mid,
                    fontWeight = if (isMonitored) FontWeight.Medium else FontWeight.Normal
                )
                if (hasUnit) {
                    Spacer(Modifier.width(6.dp))
                    MonoLabel(pid.unit, 9.sp, Dim)
                }
                if (pid.did.isNotEmpty()) {
                    Spacer(Modifier.width(6.dp))
                    MonoLabel(pid.did, 8.sp, accent.copy(0.6f))
                }
            }
            MonoText(pid.description, 9.sp, Dim)
        }
    }
}
