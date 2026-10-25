package com.openrs.dash.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.openrs.dash.data.AVAILABLE_FIELDS
import com.openrs.dash.data.DashCell
import com.openrs.dash.data.DashLayout
import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.barFraction
import com.openrs.dash.data.resolveValue
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.anim.isShiftPoint

// ═══════════════════════════════════════════════════════════════════════════
// CUSTOM DASHBOARD PAGE — full-screen overlay (like TripPage)
// ═══════════════════════════════════════════════════════════════════════════
// Design pass: gauges are instrument panels like DRIVE's — readings in ink, BOOST in the paint
// accent, Warn / Orange only past an existing limit (THERMAL preset temps, fuel < 10 %, KR > 1°,
// the shift point). Every control is a 48 dp touch area.

@Composable
fun CustomDashPage(
    vehicleState: VehicleState,
    prefs: UserPrefs,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current
    val insets = remember(view) {
        ViewCompat.getRootWindowInsets(view)?.getInsets(WindowInsetsCompat.Type.systemBars())
    }
    val topPad = with(density) { (insets?.top ?: 0).toDp() }
    // The bottom nav is hidden while this overlay is open (MainActivity) — clear the system gesture bar only.
    val bottomPad = with(density) { (insets?.bottom ?: 0).toDp() } + Tokens.PagePad
    val wide = isWideLayout()
    val columns = if (wide) 3 else 2

    // Load persisted layout
    var layout by remember {
        mutableStateOf(AppSettings.loadCustomDash(ctx) ?: DashLayout())
    }
    var showEditor by remember { mutableStateOf(false) }

    // Android Back closes the overlay (the editor sheet handles its own Back first).
    BackHandler(onBack = onDismiss)

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
            // Hit-testable overlay: touches on empty areas must not reach the pager / page underneath
            // (a tap on the empty dashboard used to open THERMAL from DRIVE's status line behind it).
            .pointerInput(Unit) { awaitPointerEventScope { while (true) awaitPointerEvent() } }
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── Header bar ────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Surf)
                    .padding(top = topPad)
                    .padding(horizontal = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                HeaderAction("← BACK", tinted = false, onClick = onDismiss)
                Spacer(Modifier.width(4.dp))
                MonoLabel("CUSTOM DASHBOARD", 11.sp, Frost, FontWeight.Bold, 0.15.sp)
                // This overlay covers the header + demo strip, so it carries the header pill's wording:
                // DEMO (· RCN / · OFF) while simulating, RCN / OFF for a real link — the cells blank while
                // the link is down, and this says why.
                val demo by com.openrs.dash.demo.DemoMode.status.collectAsState()
                val link = when {
                    vehicleState.isConnected -> null
                    vehicleState.isIdle -> "RCN"
                    else -> "OFF"
                }
                if (demo.active) {
                    Spacer(Modifier.width(8.dp))
                    DemoBadge(if (link != null) "DEMO \u00B7 $link" else "DEMO")
                } else if (link != null) {
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier
                            .background(Surf2, RoundedCornerShape(3.dp))
                            .border(CardBorder, Brd, RoundedCornerShape(3.dp))
                            .padding(horizontal = 5.dp, vertical = 2.dp)
                    ) {
                        // RCN amber like the header pill; OFF neutral — it's the user's state, not an alarm.
                        MonoLabel(link, 8.sp, if (link == "RCN") WarnInk else Mid, FontWeight.Bold, 0.12.sp)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (layout.cells.isNotEmpty()) HeaderAction("EDIT", tinted = true) { showEditor = true }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Brd)
            )

            // ── Gauge grid or empty state ──────────────────────────────────
            if (layout.cells.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = bottomPad),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FocusRsOutline(compact = false)
                        Spacer(Modifier.height(20.dp))
                        InstrumentLabel("NO GAUGES YET", color = Mid)
                        Spacer(Modifier.height(6.dp))
                        InstrumentMeta("Pick from ${AVAILABLE_FIELDS.size} readings")
                        Spacer(Modifier.height(2.dp))
                        InstrumentMeta("Show each as a number, bar or hero")
                        Spacer(Modifier.height(18.dp))
                        AccentButton("+  ADD GAUGES", Modifier.width(220.dp)) { showEditor = true }
                    }
                }
            } else {
                // Rows of equal-height cells (like DRIVE's hero row) — a number beside a hero no longer
                // leaves a short card with a gap under it. ≤ 31 cells, so a plain scrolling column.
                Column(
                    Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .padding(start = Tokens.PagePad, end = Tokens.PagePad, top = Tokens.PagePad, bottom = bottomPad),
                    verticalArrangement = Arrangement.spacedBy(Tokens.CardGap)
                ) {
                    layout.cells.chunked(columns).forEach { row ->
                        Row(
                            Modifier.fillMaxWidth().height(IntrinsicSize.Max),
                            horizontalArrangement = Arrangement.spacedBy(Tokens.CardGap)
                        ) {
                            row.forEach { cell ->
                                key(cell.fieldKey) {
                                    CustomGaugeCell(vehicleState, prefs, cell, Modifier.weight(1f).fillMaxHeight())
                                }
                            }
                            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }
        }

        // ── Editor bottom sheet ────────────────────────────────────────
        if (showEditor) {
            DashBuilderSheet(
                layout = layout,
                onSave = { newLayout ->
                    layout = newLayout
                    AppSettings.saveCustomDash(ctx, newLayout)
                    showEditor = false
                },
                onDismiss = { showEditor = false }
            )
        }
    }
}

/** Header button: 48 dp touch height, visible chip inside. */
@Composable
private fun HeaderAction(text: String, tinted: Boolean, onClick: () -> Unit) {
    val accent = LocalThemeAccent.current
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .background(if (tinted) accent.copy(alpha = pillBgAlpha(0.12f)) else Surf2, RoundedCornerShape(6.dp))
                .border(CardBorder, if (tinted) accent.copy(alpha = borderAlpha(0.4f)) else Brd, RoundedCornerShape(6.dp))
                .padding(horizontal = 12.dp, vertical = 8.dp)
        ) {
            MonoLabel(text, 11.sp, if (tinted) actionInk(accent) else Mid, FontWeight.Bold, 0.1.sp)
        }
    }
}

/** Full-width primary action (accent tint, [actionInk] label — dark on DAY). */
@Composable
private fun AccentButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val accent = LocalThemeAccent.current
    val shape = RoundedCornerShape(10.dp)
    Box(
        modifier
            .heightIn(min = 48.dp)
            .background(accent.copy(alpha = pillBgAlpha(0.12f)), shape)
            .border(CardBorder, accent.copy(alpha = borderAlpha(0.5f)), shape)
            .clip(shape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        MonoLabel(text, 11.sp, actionInk(accent), FontWeight.Bold, 0.1.sp)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// GAUGE CELL — renders a single cell based on its displayType
// ═══════════════════════════════════════════════════════════════════════════

/** A temperature with THERMAL preset limits: reading (°C, −99 = none), warn, crit. */
private data class TempReading(val c: Double, val warnC: Double, val critC: Double)

private fun tempReading(vs: VehicleState, key: String, p: UserPrefs): TempReading? = when (key) {
    "oilTempC"     -> TempReading(vs.oilTempC, p.oilWarnC, p.oilCritC)
    "coolantTempC" -> TempReading(vs.coolantTempC, p.coolWarnC, p.coolCritC)
    "ptuTempC"     -> TempReading(vs.ptuTempC, p.ptuWarnC, p.ptuCritC)
    "rduTempC"     -> TempReading(vs.rduTempC, p.rduWarnC, p.rduCritC)
    else -> null
}

/** Limit state of one dashboard reading — only existing limits, nothing new. */
private fun dashLevel(vs: VehicleState, p: UserPrefs, key: String): Level {
    tempReading(vs, key, p)?.let { t ->
        return tempLevel(t.c, t.warnC, t.critC, tempAvailFor(t.c, vs.fieldLastUpdateMs[key]).whenDisconnected(vs.isConnected))
    }
    return when (key) {
        "fuelLevelPct" -> if (vs.fuelLevelPct > 0 && vs.fuelLevelPct < 10.0) Level.WARN else Level.NORMAL   // DRIVE LOW FUEL
        "ignCorrCyl1"  -> if (vs.ignCorrCyl1 > 1.0) Level.WARN else Level.NORMAL                          // POWER KR cells
        "ignCorrCyl2"  -> if (vs.ignCorrCyl2 > 1.0) Level.WARN else Level.NORMAL
        "ignCorrCyl3"  -> if (vs.ignCorrCyl3 > 1.0) Level.WARN else Level.NORMAL
        "ignCorrCyl4"  -> if (vs.ignCorrCyl4 > 1.0) Level.WARN else Level.NORMAL
        "rpm"          -> if (isShiftPoint(vs.rpm.toFloat())) Level.CRIT else Level.NORMAL             // shift-light flash
        else           -> Level.NORMAL
    }
}

@Composable
private fun CustomGaugeCell(
    vs: VehicleState,
    prefs: UserPrefs,
    cell: DashCell,
    modifier: Modifier = Modifier
) {
    val accent = LocalThemeAccent.current
    val (displayValue, rawValue) = resolveValue(vs, prefs, cell)
    // This overlay hides the header / RECONNECTING strip, so frozen last-frame values
    // would read as live after an adapter drop — show placeholders instead.
    val stale = !vs.isConnected
    val level = if (stale) Level.NORMAL else dashLevel(vs, prefs, cell.fieldKey)
    val alert = level.isAlert
    // The RPM shift point colours the digits only (like DRIVE), not the panel.
    val panelLevel = if (cell.fieldKey == "rpm") Level.NORMAL else level

    when (cell.displayType) {
        "hero" -> {
            val unit = when (cell.fieldKey) {
                "boostKpa" -> prefs.displayBoost(vs.boostKpa).second
                "speedKph" -> prefs.speedLabel
                else -> ""     // RPM: the caption already says it
            }
            val peak = when (cell.fieldKey) {
                "rpm" -> "PEAK ${vs.peakRpm.toInt()}"
                "boostKpa" -> "PEAK ${"%.1f".format(vs.peakBoostPsi)}"
                "speedKph" -> "PEAK ${prefs.displaySpeed(vs.peakSpeedKph)}"
                else -> null
            }
            // Raw value, 1:1 with the frame (no spring) — the same rule as DRIVE's heroes.
            InstrumentHero(
                label = cell.label,
                unit = unit,
                value = if (stale) "—" else displayValue,
                valueColor = when {
                    stale -> Dim
                    alert -> levelColor(level)
                    cell.fieldKey == "boostKpa" -> accent
                    else -> Frost
                },
                valueSize = 34.sp,
                peak = peak,
                level = panelLevel,
                modifier = modifier
            )
        }

        "bar" -> {
            val fraction = if (stale) 0f else barFraction(cell, rawValue)
            val fill = if (alert) levelColor(level) else if (cell.fieldKey == "throttlePct") accent else accentMid()
            val temp = tempReading(vs, cell.fieldKey, prefs)
            Column(
                modifier
                    .instrumentSurface(panelLevel)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                InstrumentLabel(cell.label)
                Spacer(Modifier.height(2.dp))
                val v = if (stale) "— —" else displayValue
                DataNum(v, 22.sp, if (v.startsWith("—")) Dim else if (alert) levelColor(level) else Frost)
                Spacer(Modifier.height(8.dp))
                // Temperatures with limits read on THERMAL's scale (faint caution / redline zones);
                // a 0–100 track would show 100 °C oil as a full bar.
                if (temp != null) ThresholdBar(if (stale) -99.0 else temp.c, temp.warnC, temp.critC, level)
                else Box(
                    Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(Surf3, RoundedCornerShape(3.dp))
                ) {
                    if (fraction > 0f) Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(fill, RoundedCornerShape(3.dp))
                    )
                }
            }
        }

        else -> { // "number"
            val v = if (stale) "— —" else displayValue
            Column(
                modifier
                    .instrumentSurface(panelLevel)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                InstrumentLabel(cell.label)
                Spacer(Modifier.height(2.dp))
                DataNum(v, 22.sp, if (v.startsWith("—")) Dim else if (alert) levelColor(level) else Frost)
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// DASH BUILDER SHEET — add / remove / reorder / change display type
// ═══════════════════════════════════════════════════════════════════════════

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DashBuilderSheet(
    layout: DashLayout,
    onSave: (DashLayout) -> Unit,
    onDismiss: () -> Unit
) {
    val accent = LocalThemeAccent.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val editCells = remember { mutableStateListOf<DashCell>().apply { addAll(layout.cells) } }
    // An empty layout opens straight on the picker — there is nothing else to edit yet.
    var showAddPicker by remember { mutableStateOf(layout.cells.isEmpty()) }

    var draggedKey by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    val density = LocalDensity.current
    val rowGap = 6.dp
    // Row pitch for drag-reorder, measured (rows are two lines tall).
    var rowPitchPx by remember { mutableFloatStateOf(with(density) { 102.dp.toPx() }) }
    val haptic = LocalHapticFeedback.current

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surf,
        tonalElevation = 0.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
        ) {
            // ── Title row ──────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                MonoLabel("DASH BUILDER", 12.sp, Frost, FontWeight.Bold, 0.15.sp)
                InstrumentMeta("${editCells.size} ${if (editCells.size == 1) "GAUGE" else "GAUGES"}")
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Brd)
            )

            Spacer(Modifier.height(12.dp))

            // ── Selected cells list ─────────────────────────────────────
            if (editCells.isEmpty()) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    InstrumentMeta("No gauges yet — add readings below")
                }
            } else {
                InstrumentMeta("Hold ⠇ and drag, or use ▲ ▼, to reorder")
                Spacer(Modifier.height(8.dp))
                Column(verticalArrangement = Arrangement.spacedBy(rowGap)) {
                    editCells.toList().forEachIndexed { index, cell ->
                        val isDragged = draggedKey == cell.fieldKey
                        key(cell.fieldKey) {
                            EditorCellRow(
                                cell = cell,
                                canMoveUp = index > 0,
                                canMoveDown = index < editCells.lastIndex,
                                isDragged = isDragged,
                                dragOffsetY = if (isDragged) dragOffsetY else 0f,
                                modifier = Modifier.onSizeChanged {
                                    rowPitchPx = it.height + with(density) { rowGap.toPx() }
                                },
                                onDragStart = {
                                    draggedKey = cell.fieldKey
                                    dragOffsetY = 0f
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                },
                                onDrag = { deltaY ->
                                    val i = editCells.indexOfFirst { it.fieldKey == draggedKey }
                                    if (i < 0) return@EditorCellRow
                                    dragOffsetY += deltaY
                                    if (dragOffsetY > rowPitchPx / 2f && i < editCells.lastIndex) {
                                        val item = editCells.removeAt(i)
                                        editCells.add(i + 1, item)
                                        dragOffsetY -= rowPitchPx
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    } else if (dragOffsetY < -rowPitchPx / 2f && i > 0) {
                                        val item = editCells.removeAt(i)
                                        editCells.add(i - 1, item)
                                        dragOffsetY += rowPitchPx
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                },
                                onDragEnd = {
                                    draggedKey = null
                                    dragOffsetY = 0f
                                },
                                onMoveUp = {
                                    val i = editCells.indexOfFirst { it.fieldKey == cell.fieldKey }
                                    if (i > 0) editCells.add(i - 1, editCells.removeAt(i))
                                },
                                onMoveDown = {
                                    val i = editCells.indexOfFirst { it.fieldKey == cell.fieldKey }
                                    if (i in 0 until editCells.lastIndex) editCells.add(i + 1, editCells.removeAt(i))
                                },
                                onChangeType = { newType ->
                                    val i = editCells.indexOfFirst { it.fieldKey == cell.fieldKey }
                                    if (i >= 0) editCells[i] = cell.copy(displayType = newType)
                                },
                                onRemove = { editCells.removeAll { it.fieldKey == cell.fieldKey } }
                            )
                        }
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Add gauge toggle ────────────────────────────────────────
            val toggleShape = RoundedCornerShape(10.dp)
            Box(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .background(Surf2, toggleShape)
                    .border(CardBorder, accent.copy(alpha = borderAlpha(0.3f)), toggleShape)
                    .clip(toggleShape)
                    .clickable { showAddPicker = !showAddPicker },
                contentAlignment = Alignment.Center
            ) {
                MonoLabel(
                    if (showAddPicker) "−  HIDE READINGS" else "+  ADD GAUGE",
                    11.sp, actionInk(accent), FontWeight.Bold, 0.1.sp
                )
            }

            // ── Available fields picker ──────────────────────────────────
            if (showAddPicker) {
                Spacer(Modifier.height(8.dp))
                val selectedKeys = editCells.map { it.fieldKey }.toSet()
                val available = AVAILABLE_FIELDS.filter { it.fieldKey !in selectedKeys }

                if (available.isEmpty()) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        InstrumentMeta("All readings added")
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .background(InstrumentFill, RoundedCornerShape(10.dp))
                            .border(CardBorder, Brd, RoundedCornerShape(10.dp))
                    ) {
                        available.forEachIndexed { i, field ->
                            if (i > 0) Box(
                                Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp)
                                    .height(1.dp)
                                    .background(Brd.copy(alpha = borderAlpha(0.4f)))
                            )
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .heightIn(min = 48.dp)
                                    .clickable {
                                        editCells.add(field)
                                        // Auto-close picker if all fields added
                                        if (editCells.size == AVAILABLE_FIELDS.size) {
                                            showAddPicker = false
                                        }
                                    }
                                    .padding(horizontal = 12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                MonoLabel(field.label, 11.sp, Frost, letterSpacing = 0.1.sp, modifier = Modifier.weight(1f))
                                DisplayTypeBadge(field.displayType)
                                Spacer(Modifier.width(12.dp))
                                MonoLabel("+", 16.sp, actionInk(accent), FontWeight.Bold)
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Cancel / save ───────────────────────────────────────────
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                val shape = RoundedCornerShape(10.dp)
                Box(
                    Modifier
                        .weight(1f)
                        .heightIn(min = 52.dp)
                        .background(Surf2, shape)
                        .border(CardBorder, Brd, shape)
                        .clip(shape)
                        .clickable(onClick = onDismiss),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("CANCEL", 12.sp, Mid, FontWeight.Bold, 0.15.sp)
                }
                Box(
                    Modifier
                        .weight(2f)
                        .heightIn(min = 52.dp)
                        .background(accent.copy(alpha = pillBgAlpha(0.14f)), shape)
                        .border(CardBorder, accent.copy(alpha = borderAlpha(0.5f)), shape)
                        .clip(shape)
                        .clickable { onSave(DashLayout(cells = editCells.toList())) },
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("SAVE LAYOUT", 12.sp, actionInk(accent), FontWeight.Bold, 0.15.sp)
                }
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// EDITOR CELL ROW — one selected gauge with reorder / type-switch / remove
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun EditorCellRow(
    cell: DashCell,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    modifier: Modifier = Modifier,
    isDragged: Boolean = false,
    dragOffsetY: Float = 0f,
    onDragStart: () -> Unit = {},
    onDrag: (Float) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onChangeType: (String) -> Unit,
    onRemove: () -> Unit
) {
    val accent = LocalThemeAccent.current
    val shape = RoundedCornerShape(10.dp)

    Column(
        modifier
            .fillMaxWidth()
            .zIndex(if (isDragged) 1f else 0f)
            .graphicsLayer {
                translationY = dragOffsetY
                alpha = if (isDragged) 0.92f else 1f
                scaleX = if (isDragged) 1.02f else 1f
                scaleY = if (isDragged) 1.02f else 1f
            }
            .background(if (isDragged) Surf3 else InstrumentFill, shape)
            .border(CardBorder, if (isDragged) accent.copy(alpha = 0.5f) else Brd, shape)
            .padding(end = 4.dp, bottom = 4.dp)
    ) {
        // Line 1: drag handle · label · remove
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(48.dp)
                    .pointerInput(cell.fieldKey) {
                        detectDragGesturesAfterLongPress(
                            onDragStart = { onDragStart() },
                            onDragEnd = { onDragEnd() },
                            onDragCancel = { onDragEnd() },
                            onDrag = { _, drag -> onDrag(drag.y) }
                        )
                    },
                contentAlignment = Alignment.Center
            ) {
                MonoLabel("⠇", 18.sp, Dim)
            }
            MonoLabel(cell.label, 12.sp, Frost, FontWeight.Bold, 0.1.sp, modifier = Modifier.weight(1f))
            Box(
                Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .clickable(onClickLabel = "Remove ${cell.label}") { onRemove() },
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(30.dp)
                        .background(Surf2, CircleShape)
                        .border(CardBorder, Brd, CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("✕", 12.sp, Mid)
                }
            }
        }

        // Line 2: display type · move up / down
        Row(Modifier.padding(start = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("number" to "NUM", "bar" to "BAR", "hero" to "HERO").forEach { (type, label) ->
                val isActive = cell.displayType == type
                Box(
                    Modifier
                        .heightIn(min = 48.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable { onChangeType(type) }
                        .padding(horizontal = 2.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        Modifier
                            .width(54.dp)
                            .background(
                                if (isActive) accent.copy(alpha = pillBgAlpha(0.15f)) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .border(
                                CardBorder,
                                if (isActive) accent.copy(alpha = borderAlpha(0.5f)) else Brd,
                                RoundedCornerShape(6.dp)
                            )
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        MonoLabel(
                            label, 10.sp,
                            if (isActive) actionInk(accent) else Mid,
                            if (isActive) FontWeight.Bold else FontWeight.Normal,
                            letterSpacing = 0.1.sp
                        )
                    }
                }
            }
            Spacer(Modifier.weight(1f))
            MoveButton("▲", "Move ${cell.label} up", canMoveUp, onMoveUp)
            MoveButton("▼", "Move ${cell.label} down", canMoveDown, onMoveDown)
        }
    }
}

@Composable
private fun MoveButton(glyph: String, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .clickable(enabled = enabled, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        MonoLabel(glyph, 13.sp, if (enabled) Mid else Brd)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// DISPLAY TYPE BADGE — the field's default type in the picker
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun DisplayTypeBadge(type: String) {
    val label = when (type) {
        "hero" -> "HERO"
        "bar" -> "BAR"
        else -> "NUM"
    }
    Box(
        Modifier
            .border(CardBorder, Brd, RoundedCornerShape(4.dp))
            .padding(horizontal = 6.dp, vertical = 2.dp)
    ) {
        MonoLabel(label, 10.sp, Dim, letterSpacing = 0.1.sp)
    }
}
