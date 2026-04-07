package com.openrs.dash.ui

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import com.openrs.dash.ui.Tokens.CardBorder
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.openrs.dash.data.AVAILABLE_FIELDS
import com.openrs.dash.data.DashCell
import com.openrs.dash.data.DashLayout
import com.openrs.dash.data.VehicleState
import com.openrs.dash.data.barFraction
import com.openrs.dash.data.resolveValue

// ═══════════════════════════════════════════════════════════════════════════
// CUSTOM DASHBOARD PAGE — full-screen overlay (like TripPage)
// ═══════════════════════════════════════════════════════════════════════════

@Composable
fun CustomDashPage(
    vehicleState: VehicleState,
    prefs: UserPrefs,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val accent = LocalThemeAccent.current
    val view = LocalView.current
    val density = LocalDensity.current
    val topPad = remember(view) {
        val insets = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars())
        if (insets != null) with(density) { insets.top.toDp() } else 0.dp
    }
    val wide = isWideLayout()
    val columns = if (wide) 3 else 2

    // Load persisted layout
    var layout by remember {
        mutableStateOf(AppSettings.loadCustomDash(ctx) ?: DashLayout())
    }
    var showEditor by remember { mutableStateOf(false) }

    Box(
        Modifier
            .fillMaxSize()
            .background(Bg)
    ) {
        Column(Modifier.fillMaxSize()) {
            // ── Header bar ────────────────────────────────────────────────
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(Surf)
                    .padding(top = topPad + 10.dp, bottom = 10.dp)
                    .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .background(Surf2, RoundedCornerShape(6.dp))
                            .border(CardBorder, Brd, RoundedCornerShape(6.dp))
                            .clickable { onDismiss() }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        MonoLabel("\u2190 BACK", 10.sp, accent, FontWeight.Bold, 0.1.sp)
                    }
                    Spacer(Modifier.width(12.dp))
                    MonoLabel("CUSTOM DASHBOARD", 11.sp, Frost, FontWeight.Bold, 0.15.sp)
                }
                Box(
                    Modifier
                        .background(accent.copy(alpha = 0.10f), RoundedCornerShape(6.dp))
                        .border(CardBorder, accent.copy(alpha = 0.28f), RoundedCornerShape(6.dp))
                        .clickable { showEditor = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    MonoLabel("EDIT", 10.sp, accent, FontWeight.Bold, 0.1.sp)
                }
            }

            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(Brd)
            )

            // ── Gauge grid or empty state ──────────────────────────────────
            if (layout.cells.isEmpty()) {
                // Empty state
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        FocusRsOutline(compact = false)
                        Spacer(Modifier.height(20.dp))
                        MonoLabel(
                            "Tap EDIT to add gauges",
                            12.sp, Dim, letterSpacing = 0.1.sp
                        )
                        Spacer(Modifier.height(6.dp))
                        MonoLabel(
                            "Build your own layout from ${AVAILABLE_FIELDS.size} available metrics",
                            9.sp, Dim, letterSpacing = 0.05.sp
                        )
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(columns),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 16.dp)
                ) {
                    items(layout.cells, key = { it.fieldKey }) { cell ->
                        CustomGaugeCell(vehicleState, prefs, cell)
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

// ═══════════════════════════════════════════════════════════════════════════
// GAUGE CELL — renders a single cell based on its displayType
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun CustomGaugeCell(
    vs: VehicleState,
    prefs: UserPrefs,
    cell: DashCell
) {
    val accent = LocalThemeAccent.current
    val (displayValue, rawValue) = resolveValue(vs, prefs, cell)

    when (cell.displayType) {
        "hero" -> {
            val animVal by animateFloatAsState(
                rawValue.toFloat(),
                spring(stiffness = Spring.StiffnessHigh),
                label = "hero_${cell.fieldKey}"
            )
            // Re-resolve with animated value for smooth display
            val animDisplay = when (cell.fieldKey) {
                "rpm" -> "%.0f".format(animVal.toDouble())
                "boostKpa" -> prefs.displayBoost(animVal.toDouble()).first
                "speedKph" -> prefs.displaySpeed(animVal.toDouble())
                else -> displayValue
            }
            val unit = when (cell.fieldKey) {
                "boostKpa" -> prefs.displayBoost(vs.boostKpa).second
                "speedKph" -> prefs.speedLabel
                else -> ""
            }
            HeroCard(
                unit = unit,
                value = animDisplay,
                label = cell.label,
                valueColor = accent,
                modifier = Modifier.fillMaxWidth()
            )
        }

        "bar" -> {
            val fraction = barFraction(cell, rawValue)
            val animFrac by animateFloatAsState(
                fraction,
                spring(stiffness = Spring.StiffnessHigh),
                label = "bar_${cell.fieldKey}"
            )
            val barColor = when {
                fraction > 0.9f -> Orange
                fraction > 0.7f -> Warn
                else -> accent
            }
            BarCard(
                name = cell.label,
                value = displayValue,
                fraction = animFrac,
                barBrush = Brush.horizontalGradient(
                    listOf(barColor.copy(alpha = 0.6f), barColor)
                ),
                modifier = Modifier.fillMaxWidth(),
                barGlowColor = barColor
            )
        }

        else -> { // "number"
            DataCell(
                label = cell.label,
                value = displayValue,
                modifier = Modifier.fillMaxWidth()
            )
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
    var showAddPicker by remember { mutableStateOf(false) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Surf,
        tonalElevation = 0.dp
    ) {
        Column(
            Modifier
                .fillMaxWidth()
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
                MonoLabel("${editCells.size} gauges", 10.sp, Dim)
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
                        .padding(vertical = 24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    MonoLabel("No gauges added yet", 10.sp, Dim)
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height((editCells.size * 56).coerceAtMost(280).dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(editCells.toList(), key = { it.fieldKey }) { cell ->
                        val index = editCells.indexOf(cell)
                        EditorCellRow(
                            cell = cell,
                            canMoveUp = index > 0,
                            canMoveDown = index < editCells.lastIndex,
                            onMoveUp = {
                                if (index > 0) {
                                    val item = editCells.removeAt(index)
                                    editCells.add(index - 1, item)
                                }
                            },
                            onMoveDown = {
                                if (index < editCells.lastIndex) {
                                    val item = editCells.removeAt(index)
                                    editCells.add(index + 1, item)
                                }
                            },
                            onChangeType = { newType ->
                                editCells[index] = cell.copy(displayType = newType)
                            },
                            onRemove = { editCells.removeAt(index) }
                        )
                    }
                }
            }

            Spacer(Modifier.height(12.dp))

            // ── Add gauge button ────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Surf2, RoundedCornerShape(10.dp))
                    .border(CardBorder, accent.copy(alpha = 0.3f), RoundedCornerShape(10.dp))
                    .clickable { showAddPicker = !showAddPicker }
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel(
                    if (showAddPicker) "\u2212  CLOSE" else "+  ADD GAUGE",
                    11.sp, accent, FontWeight.Bold, 0.1.sp
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
                        MonoLabel("All gauges added", 10.sp, Dim)
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height((available.size * 44).coerceAtMost(220).dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        items(available, key = { it.fieldKey }) { field ->
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .background(Surf2, RoundedCornerShape(8.dp))
                                    .border(CardBorder, Brd, RoundedCornerShape(8.dp))
                                    .clickable {
                                        editCells.add(field)
                                        // Auto-close picker if all fields added
                                        if (editCells.size == AVAILABLE_FIELDS.size) {
                                            showAddPicker = false
                                        }
                                    }
                                    .padding(horizontal = 12.dp, vertical = 10.dp)
                            ) {
                                Row(
                                    Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    MonoLabel(field.label, 10.sp, Frost, letterSpacing = 0.1.sp)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        DisplayTypeBadge(field.displayType, accent)
                                        MonoLabel("+", 14.sp, accent)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            Spacer(Modifier.height(16.dp))

            // ── Save button ─────────────────────────────────────────────
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.horizontalGradient(
                            listOf(accent.copy(0.15f), accent.copy(0.08f))
                        ),
                        RoundedCornerShape(10.dp)
                    )
                    .border(CardBorder, accent.copy(0.4f), RoundedCornerShape(10.dp))
                    .clickable {
                        onSave(DashLayout(cells = editCells.toList()))
                    }
                    .padding(vertical = 14.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel("SAVE LAYOUT", 12.sp, accent, FontWeight.Bold, 0.15.sp)
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
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onChangeType: (String) -> Unit,
    onRemove: () -> Unit
) {
    val accent = LocalThemeAccent.current

    Row(
        Modifier
            .fillMaxWidth()
            .background(Surf2, RoundedCornerShape(8.dp))
            .border(CardBorder, Brd, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // Reorder arrows
        Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
            MonoLabel(
                "\u25B2", 10.sp,
                if (canMoveUp) Mid else Brd,
                modifier = Modifier
                    .clickable(enabled = canMoveUp) { onMoveUp() }
                    .padding(2.dp)
            )
            MonoLabel(
                "\u25BC", 10.sp,
                if (canMoveDown) Mid else Brd,
                modifier = Modifier
                    .clickable(enabled = canMoveDown) { onMoveDown() }
                    .padding(2.dp)
            )
        }

        // Label
        MonoLabel(cell.label, 10.sp, Frost, letterSpacing = 0.1.sp, modifier = Modifier.weight(1f))

        // Display type pills
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            listOf("number" to "NUM", "bar" to "BAR", "hero" to "HERO").forEach { (type, label) ->
                val isActive = cell.displayType == type
                Box(
                    Modifier
                        .background(
                            if (isActive) accent.copy(alpha = 0.15f) else Color.Transparent,
                            RoundedCornerShape(4.dp)
                        )
                        .border(
                            1.dp,
                            if (isActive) accent.copy(alpha = 0.5f) else Brd,
                            RoundedCornerShape(4.dp)
                        )
                        .clickable { onChangeType(type) }
                        .padding(horizontal = 6.dp, vertical = 3.dp)
                ) {
                    MonoLabel(
                        label, 7.sp,
                        if (isActive) accent else Dim,
                        letterSpacing = 0.05.sp
                    )
                }
            }
        }

        // Remove button
        Box(
            Modifier
                .size(24.dp)
                .clip(CircleShape)
                .background(Orange.copy(alpha = 0.1f))
                .border(CardBorder, Orange.copy(alpha = 0.3f), CircleShape)
                .clickable { onRemove() },
            contentAlignment = Alignment.Center
        ) {
            MonoLabel("\u2715", 10.sp, Orange)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// DISPLAY TYPE BADGE — small colored tag showing NUM/BAR/HERO
// ═══════════════════════════════════════════════════════════════════════════

@Composable
private fun DisplayTypeBadge(type: String, accent: Color) {
    val label = when (type) {
        "hero" -> "HERO"
        "bar" -> "BAR"
        else -> "NUM"
    }
    Box(
        Modifier
            .background(accent.copy(alpha = 0.08f), RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    ) {
        MonoLabel(label, 7.sp, accent.copy(alpha = 0.6f), letterSpacing = 0.05.sp)
    }
}
