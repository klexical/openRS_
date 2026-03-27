package com.openrs.dash.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.anim.CarDiagram
import com.openrs.dash.ui.anim.GForcePlot
import com.openrs.dash.ui.anim.RingBuffer
import com.openrs.dash.ui.anim.WHEEL_ANCHORS
import com.openrs.dash.ui.anim.tireStatusColor
import kotlin.math.abs
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// CHASSIS PAGE (G-Force + Unified Tires & AWD)
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun ChassisPage(vs: VehicleState, p: UserPrefs, onReset: () -> Unit) {
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        GForceSection(vs, onReset)
        UnifiedChassisSection(vs, p)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// UNIFIED CHASSIS SECTION — Tires & AWD merged ("Neon Connect" layout)
//
// Layout:  [FL card]          [RS wireframe]          [FR card]
//          [RL card]          (diamond markers)       [RR card]
//                    ⚠ LOW TIRE PRESSURE
//          ────────── AWD — GKN TWINSTER ──────────
//          [torque bar] [metrics] [temps] [clutch]
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun UnifiedChassisSection(vs: VehicleState, p: UserPrefs) {
    val accent = LocalThemeAccent.current
    val lowThreshold = p.tireLowPsi.toDouble()

    // Animated tire status colors for accent edge bars
    val flColor by animateColorAsState(
        tireStatusColor(vs.tirePressLF, lowThreshold), tween(400), label = "flC"
    )
    val frColor by animateColorAsState(
        tireStatusColor(vs.tirePressRF, lowThreshold), tween(400), label = "frC"
    )
    val rlColor by animateColorAsState(
        tireStatusColor(vs.tirePressLR, lowThreshold), tween(400), label = "rlC"
    )
    val rrColor by animateColorAsState(
        tireStatusColor(vs.tirePressRR, lowThreshold), tween(400), label = "rrC"
    )

    // Deltas
    val deltaLF = tpmsDeltaText(vs.tirePressLF, vs.tireStartLF)
    val deltaRF = tpmsDeltaText(vs.tirePressRF, vs.tireStartRF)
    val deltaLR = tpmsDeltaText(vs.tirePressLR, vs.tireStartLR)
    val deltaRR = tpmsDeltaText(vs.tirePressRR, vs.tireStartRR)

    Column(
        Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(16.dp))
            .border(1.dp, Brd, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SectionLabel("CHASSIS — TIRES & AWD")

        // ── Neon Connect layout: cards flanking wireframe ──
        // Position tracking for connector lead lines
        var rowRoot by remember { mutableStateOf(Offset.Zero) }
        var diagRoot by remember { mutableStateOf(Offset.Zero) }
        var diagSz by remember { mutableStateOf(IntSize.Zero) }
        var flEdge by remember { mutableStateOf(Offset.Zero) }
        var frEdge by remember { mutableStateOf(Offset.Zero) }
        var rlEdge by remember { mutableStateOf(Offset.Zero) }
        var rrEdge by remember { mutableStateOf(Offset.Zero) }

        Box {
                Row(
                    Modifier.fillMaxWidth()
                        .onGloballyPositioned { rowRoot = it.positionInRoot() },
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Left column: FL + RL
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.onGloballyPositioned {
                            val r = it.positionInRoot()
                            flEdge = Offset(r.x - rowRoot.x + it.size.width, r.y - rowRoot.y + it.size.height / 2f)
                        }) {
                            NeonTireCard("FL", vs.tirePressLF, p, lowThreshold, flColor,
                                vs.wheelSpeedFL, vs.tireTempLF, deltaLF)
                        }
                        Box(Modifier.onGloballyPositioned {
                            val r = it.positionInRoot()
                            rlEdge = Offset(r.x - rowRoot.x + it.size.width, r.y - rowRoot.y + it.size.height / 2f)
                        }) {
                            NeonTireCard("RL", vs.tirePressLR, p, lowThreshold, rlColor,
                                vs.wheelSpeedRL, vs.tireTempLR, deltaLR)
                        }
                    }

                    // Center: RS wireframe with diamond markers + F/R bar
                    CarDiagram(
                        vs = vs,
                        prefs = p,
                        modifier = Modifier.width(120.dp).aspectRatio(0.6f)
                            .onGloballyPositioned {
                                diagRoot = it.positionInRoot()
                                diagSz = it.size
                            }
                    )

                    // Right column: FR + RR
                    Column(
                        Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.onGloballyPositioned {
                            val r = it.positionInRoot()
                            frEdge = Offset(r.x - rowRoot.x, r.y - rowRoot.y + it.size.height / 2f)
                        }) {
                            NeonTireCard("FR", vs.tirePressRF, p, lowThreshold, frColor,
                                vs.wheelSpeedFR, vs.tireTempRF, deltaRF)
                        }
                        Box(Modifier.onGloballyPositioned {
                            val r = it.positionInRoot()
                            rrEdge = Offset(r.x - rowRoot.x, r.y - rowRoot.y + it.size.height / 2f)
                        }) {
                            NeonTireCard("RR", vs.tirePressRR, p, lowThreshold, rrColor,
                                vs.wheelSpeedRR, vs.tireTempRR, deltaRR)
                        }
                    }
                }

                // Connector lead lines: tire cards → diamond markers
                if (diagSz.width > 0) {
                    Canvas(Modifier.matchParentSize()) {
                        val diagOff = Offset(diagRoot.x - rowRoot.x, diagRoot.y - rowRoot.y)
                        val dW = diagSz.width.toFloat()
                        val dH = diagSz.height.toFloat()
                        val stroke = 1.dp.toPx()

                        fun connectLine(edge: Offset, anchorIdx: Int, color: Color) {
                            if (edge == Offset.Zero) return
                            val a = WHEEL_ANCHORS[anchorIdx]
                            val diamond = Offset(diagOff.x + dW * a.xFraction,
                                                  diagOff.y + dH * a.yFraction)
                            drawLine(color.copy(alpha = 0.3f), edge, diamond, strokeWidth = stroke)
                        }

                        connectLine(flEdge, 0, flColor)
                        connectLine(frEdge, 1, frColor)
                        connectLine(rlEdge, 2, rlColor)
                        connectLine(rrEdge, 3, rrColor)
                    }
                }
        }

        // ── Low pressure / imbalance warnings ──
        val lowTires = buildList {
            if (vs.tirePressLF in 0.0..(lowThreshold - 0.001)) add("Front Left")
            if (vs.tirePressRF in 0.0..(lowThreshold - 0.001)) add("Front Right")
            if (vs.tirePressLR in 0.0..(lowThreshold - 0.001)) add("Rear Left")
            if (vs.tirePressRR in 0.0..(lowThreshold - 0.001)) add("Rear Right")
        }
        if (lowTires.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Column(
                Modifier.fillMaxWidth()
                    .background(Orange.copy(alpha = 0.1f), RoundedCornerShape(10.dp))
                    .border(1.dp, Orange.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                val warningText = if (lowTires.size == 1)
                    "⚠  Warning: ${lowTires[0]} tire low!"
                else
                    "⚠  Warning: ${lowTires.joinToString(", ")} tires low!"
                MonoLabel(warningText, 10.sp, Orange, letterSpacing = 0.08.sp)
                Spacer(Modifier.height(6.dp))
                MonoLabel(
                    "Recommended: ${p.displayTire(lowThreshold)} ${p.tireLabel}",
                    9.sp, Dim
                )
            }
        }
        val spread = vs.maxTirePressSpread
        if (spread >= 4.0 && lowTires.isEmpty()) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier.fillMaxWidth()
                    .background(Warn.copy(alpha = 0.08f), RoundedCornerShape(10.dp))
                    .border(1.dp, Warn.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
                    .padding(12.dp),
                contentAlignment = Alignment.Center
            ) {
                MonoLabel("⚠  Pressure imbalance — ${"%.1f".format(spread)} ${p.tireLabel} spread", 10.sp, Warn, letterSpacing = 0.08.sp)
            }
        }

        // ── TPMS footer: recommended threshold + last updated ──
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MonoLabel(
                "Recommended: ${p.displayTire(lowThreshold)} ${p.tireLabel}",
                8.sp, Dim.copy(alpha = 0.7f)
            )
            val updatedText = if (vs.tpmsLastUpdate > 0L) {
                val ago = (System.currentTimeMillis() - vs.tpmsLastUpdate) / 1000
                when {
                    ago < 5   -> "Updated just now"
                    ago < 60  -> "Updated ${ago}s ago"
                    ago < 3600 -> "Updated ${ago / 60}m ago"
                    else       -> "Updated ${ago / 3600}h ago"
                }
            } else "Awaiting data"
            MonoLabel(updatedText, 8.sp, Dim.copy(alpha = 0.7f))
        }

        // ── AWD Drivetrain Section (always visible) ──
        Spacer(Modifier.height(12.dp))
        AwdMetrics(vs, p)
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// TIRE CARD — graphical design matching cockpit TPMS display
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun NeonTireCard(
    label: String,
    psi: Double,
    p: UserPrefs,
    lowThreshold: Double,
    statusColor: Color,
    wheelSpeedKph: Double,
    tempC: Double,
    deltaText: String
) {
    val isMissing = psi < 0
    val isLow = psi in 0.0..(lowThreshold - 0.001)
    val hasTemp = tempC > -90
    val positionName = when (label) {
        "FL" -> "Front Left"; "FR" -> "Front Right"
        "RL" -> "Rear Left";  "RR" -> "Rear Right"
        else -> label
    }
    val speedStr = com.openrs.dash.ui.anim.formatWheelSpeed(wheelSpeedKph, p)

    val bgColor = if (isLow) Orange.copy(alpha = 0.06f) else Surf2
    val borderColor = if (isLow) Orange.copy(alpha = 0.45f) else Brd

    Column(
        Modifier.fillMaxWidth()
            .background(bgColor, RoundedCornerShape(12.dp))
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .padding(horizontal = 10.dp, vertical = 10.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Position name
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            MonoLabel(positionName, 9.sp, if (isLow) Orange else Dim, letterSpacing = 0.08.sp)
            if (deltaText.isNotEmpty()) {
                val deltaColor = if (deltaText.startsWith("\u25B2")) Ok else Orange
                MonoText(deltaText, 8.sp, deltaColor)
            }
        }

        Spacer(Modifier.height(6.dp))

        // Hero PSI + unit label (side by side like TPMS display)
        Row(verticalAlignment = Alignment.Bottom) {
            HeroNum(
                if (isMissing) "—" else p.displayTire(psi),
                22.sp,
                statusColor
            )
            Spacer(Modifier.width(3.dp))
            MonoLabel(
                p.tireLabel.lowercase(),
                10.sp,
                statusColor.copy(alpha = 0.6f),
                modifier = Modifier.padding(bottom = 1.dp)
            )
        }

        // Temperature
        if (hasTemp) {
            Spacer(Modifier.height(3.dp))
            MonoText(
                p.displayTemp(tempC) + p.tempLabel,
                14.sp,
                tireTempColor(tempC)
            )
        }

        // Speed
        Spacer(Modifier.height(4.dp))
        MonoLabel(speedStr, 9.sp, Dim)

        // Temperature bar
        if (hasTemp) {
            Spacer(Modifier.height(5.dp))
            val tempFraction = ((tempC - 0.0) / 60.0).toFloat().coerceIn(0.05f, 1f)
            val tempColor = tireTempColor(tempC)
            Box(
                Modifier.fillMaxWidth()
                    .height(3.dp)
                    .background(Surf3, RoundedCornerShape(2.dp))
            ) {
                Box(
                    Modifier.fillMaxWidth(tempFraction)
                        .height(3.dp)
                        .background(tempColor.copy(alpha = 0.5f), RoundedCornerShape(2.dp))
                )
            }
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// AWD METRICS — rear axle diagram + data cells
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun AwdMetrics(vs: VehicleState, p: UserPrefs) {
    val accent = LocalThemeAccent.current
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val total = vs.totalRearTorque
    val avgF = (vs.wheelSpeedFL + vs.wheelSpeedFR) / 2
    val avgR = (vs.wheelSpeedRL + vs.wheelSpeedRR) / 2
    val frDelta = avgR - avgF
    val lrDelta = vs.wheelSpeedRR - vs.wheelSpeedRL

    // Precompute labels for the diagram
    val leftNm = "${vs.awdLeftTorque.roundToInt()} Nm"
    val rightNm = "${vs.awdRightTorque.roundToInt()} Nm"
    val rearPct = vs.rearTorquePct.roundToInt()
    val frontPct = 100 - rearPct
    val splitLabel = "F $frontPct / R $rearPct"
    val ptuTemp = if (vs.ptuTempC > -90) "${p.displayTemp(vs.ptuTempC)}${p.tempLabel}" else "—"
    val rduTemp = if (vs.rduTempC > -90) "${p.displayTemp(vs.rduTempC)}${p.tempLabel}" else "—"
    val cltLTemp = if (vs.awdClutchTempL > -90) "${p.displayTemp(vs.awdClutchTempL)}${p.tempLabel}" else ""
    val cltRTemp = if (vs.awdClutchTempR > -90) "${p.displayTemp(vs.awdClutchTempR)}${p.tempLabel}" else ""

    val labelStyle = remember(density) {
        TextStyle(
            fontFamily = ShareTechMono, fontSize = with(density) { 9.sp },
            color = Dim, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center
        )
    }
    val valueStyle = remember(density) {
        TextStyle(
            fontFamily = ShareTechMono, fontSize = with(density) { 11.sp },
            color = Frost, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
    }
    val tempStyle = remember(density) {
        TextStyle(
            fontFamily = ShareTechMono, fontSize = with(density) { 8.sp },
            color = Dim, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center
        )
    }

    SectionLabel("AWD — GKN TWINSTER")

    // ── Rear axle diagram ──
    Canvas(
        Modifier.fillMaxWidth().height(130.dp)
    ) {
        val w = size.width
        val h = size.height
        val midY = h * 0.48f
        val shaftStroke = 3.dp.toPx()
        val propStroke = 2.dp.toPx()

        // ── Component positions ──
        val wheelR = 14.dp.toPx()
        val rduW = 48.dp.toPx()
        val rduH = 28.dp.toPx()
        val cltW = 16.dp.toPx()
        val cltH = 22.dp.toPx()
        val ptuW = 36.dp.toPx()
        val ptuH = 18.dp.toPx()

        val leftWheelX = wheelR + 6.dp.toPx()
        val rightWheelX = w - wheelR - 6.dp.toPx()
        val rduX = w / 2f
        val rduLeft = rduX - rduW / 2f
        val rduRight = rduX + rduW / 2f
        val cltLX = rduLeft - 30.dp.toPx()
        val cltRX = rduRight + 30.dp.toPx()
        val ptuY = midY - rduH / 2f - 28.dp.toPx()

        // ── Torque arrows (behind components) ──
        val leftPct = if (total > 0) (vs.awdLeftTorque / total).toFloat() else 0.5f
        val maxArrowW = 8.dp.toPx()
        val leftArrowW = (maxArrowW * leftPct.coerceIn(0.1f, 1f))
        val rightArrowW = (maxArrowW * (1f - leftPct).coerceIn(0.1f, 1f))

        // Left torque arrow: RDU → left wheel
        drawRect(
            color = accent.copy(alpha = 0.25f),
            topLeft = Offset(leftWheelX + wheelR + 4.dp.toPx(), midY - leftArrowW / 2f),
            size = androidx.compose.ui.geometry.Size(
                cltLX - cltW / 2f - leftWheelX - wheelR - 4.dp.toPx(), leftArrowW
            )
        )
        drawRect(
            color = accent.copy(alpha = 0.25f),
            topLeft = Offset(cltLX + cltW / 2f, midY - leftArrowW / 2f),
            size = androidx.compose.ui.geometry.Size(
                rduLeft - cltLX - cltW / 2f, leftArrowW
            )
        )

        // Right torque arrow: RDU → right wheel
        drawRect(
            color = Ok.copy(alpha = 0.25f),
            topLeft = Offset(rduRight, midY - rightArrowW / 2f),
            size = androidx.compose.ui.geometry.Size(
                cltRX - cltW / 2f - rduRight, rightArrowW
            )
        )
        drawRect(
            color = Ok.copy(alpha = 0.25f),
            topLeft = Offset(cltRX + cltW / 2f, midY - rightArrowW / 2f),
            size = androidx.compose.ui.geometry.Size(
                rightWheelX - wheelR - 4.dp.toPx() - cltRX - cltW / 2f, rightArrowW
            )
        )

        // ── Half-shafts (axle lines) ──
        // Left shaft: wheel → clutch
        drawLine(Dim, Offset(leftWheelX + wheelR, midY),
            Offset(cltLX - cltW / 2f, midY), strokeWidth = shaftStroke)
        // Left shaft: clutch → RDU
        drawLine(Dim, Offset(cltLX + cltW / 2f, midY),
            Offset(rduLeft, midY), strokeWidth = shaftStroke)
        // Right shaft: RDU → clutch
        drawLine(Dim, Offset(rduRight, midY),
            Offset(cltRX - cltW / 2f, midY), strokeWidth = shaftStroke)
        // Right shaft: clutch → wheel
        drawLine(Dim, Offset(cltRX + cltW / 2f, midY),
            Offset(rightWheelX - wheelR, midY), strokeWidth = shaftStroke)

        // ── Propshaft (vertical: PTU → RDU) ──
        drawLine(Dim, Offset(rduX, ptuY + ptuH / 2f),
            Offset(rduX, midY - rduH / 2f), strokeWidth = propStroke)

        // ── PTU box ──
        drawRoundRect(
            color = Surf3,
            topLeft = Offset(rduX - ptuW / 2f, ptuY - ptuH / 2f),
            size = androidx.compose.ui.geometry.Size(ptuW, ptuH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        )
        drawRoundRect(
            color = Brd,
            topLeft = Offset(rduX - ptuW / 2f, ptuY - ptuH / 2f),
            size = androidx.compose.ui.geometry.Size(ptuW, ptuH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )
        val ptuLabel = textMeasurer.measure("PTU", labelStyle)
        drawText(ptuLabel, topLeft = Offset(rduX - ptuLabel.size.width / 2f,
            ptuY - ptuLabel.size.height / 2f))

        // PTU temp below PTU box
        if (ptuTemp != "—") {
            val ptuTempM = textMeasurer.measure(ptuTemp, tempStyle)
            drawText(ptuTempM, topLeft = Offset(rduX - ptuTempM.size.width / 2f,
                ptuY + ptuH / 2f + 1.dp.toPx()))
        }

        // F/R split label above PTU
        val splitM = textMeasurer.measure(splitLabel, tempStyle.copy(color = Frost.copy(alpha = 0.7f)))
        drawText(splitM, topLeft = Offset(rduX - splitM.size.width / 2f,
            ptuY - ptuH / 2f - splitM.size.height - 2.dp.toPx()))

        // ── RDU box ──
        drawRoundRect(
            color = Surf3,
            topLeft = Offset(rduLeft, midY - rduH / 2f),
            size = androidx.compose.ui.geometry.Size(rduW, rduH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx())
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.5f),
            topLeft = Offset(rduLeft, midY - rduH / 2f),
            size = androidx.compose.ui.geometry.Size(rduW, rduH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )
        val rduLabel = textMeasurer.measure("RDU", labelStyle)
        drawText(rduLabel, topLeft = Offset(rduX - rduLabel.size.width / 2f,
            midY - rduLabel.size.height / 2f - 2.dp.toPx()))
        // RDU temp inside box
        val rduTempM = textMeasurer.measure(rduTemp, tempStyle)
        drawText(rduTempM, topLeft = Offset(rduX - rduTempM.size.width / 2f,
            midY + rduLabel.size.height / 2f - 2.dp.toPx()))

        // ── Clutch packs ──
        // Left clutch
        drawRoundRect(
            color = Surf2,
            topLeft = Offset(cltLX - cltW / 2f, midY - cltH / 2f),
            size = androidx.compose.ui.geometry.Size(cltW, cltH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        )
        drawRoundRect(
            color = accent.copy(alpha = 0.4f),
            topLeft = Offset(cltLX - cltW / 2f, midY - cltH / 2f),
            size = androidx.compose.ui.geometry.Size(cltW, cltH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )
        if (cltLTemp.isNotEmpty()) {
            val cltLM = textMeasurer.measure(cltLTemp, tempStyle)
            drawText(cltLM, topLeft = Offset(cltLX - cltLM.size.width / 2f,
                midY + cltH / 2f + 2.dp.toPx()))
        }

        // Right clutch
        drawRoundRect(
            color = Surf2,
            topLeft = Offset(cltRX - cltW / 2f, midY - cltH / 2f),
            size = androidx.compose.ui.geometry.Size(cltW, cltH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())
        )
        drawRoundRect(
            color = Ok.copy(alpha = 0.4f),
            topLeft = Offset(cltRX - cltW / 2f, midY - cltH / 2f),
            size = androidx.compose.ui.geometry.Size(cltW, cltH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )
        if (cltRTemp.isNotEmpty()) {
            val cltRM = textMeasurer.measure(cltRTemp, tempStyle)
            drawText(cltRM, topLeft = Offset(cltRX - cltRM.size.width / 2f,
                midY + cltH / 2f + 2.dp.toPx()))
        }

        // ── Wheels ──
        // Left wheel
        drawCircle(color = Surf3, radius = wheelR, center = Offset(leftWheelX, midY))
        drawCircle(color = accent.copy(alpha = 0.5f), radius = wheelR,
            center = Offset(leftWheelX, midY), style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = accent.copy(alpha = 0.3f), radius = wheelR * 0.4f,
            center = Offset(leftWheelX, midY))

        // Right wheel
        drawCircle(color = Surf3, radius = wheelR, center = Offset(rightWheelX, midY))
        drawCircle(color = Ok.copy(alpha = 0.5f), radius = wheelR,
            center = Offset(rightWheelX, midY), style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = Ok.copy(alpha = 0.3f), radius = wheelR * 0.4f,
            center = Offset(rightWheelX, midY))

        // ── Torque value labels below wheels ──
        val leftNmM = textMeasurer.measure(leftNm, valueStyle.copy(color = accent))
        drawText(leftNmM, topLeft = Offset(leftWheelX - leftNmM.size.width / 2f,
            midY + wheelR + 6.dp.toPx()))

        val rightNmM = textMeasurer.measure(rightNm, valueStyle.copy(color = Ok))
        drawText(rightNmM, topLeft = Offset(rightWheelX - rightNmM.size.width / 2f,
            midY + wheelR + 6.dp.toPx()))
    }

    // ── Data cells below the diagram ──
    Spacer(Modifier.height(8.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        DataCell("REAR BIAS", vs.rearLeftRightBias, modifier = Modifier.weight(1f))
        val spdLabel = if (p.speedUnit == "MPH") "mph" else "km/h"
        val lrDisp = if (p.speedUnit == "MPH") lrDelta * UnitConversions.KM_TO_MI else lrDelta
        val frDisp = if (p.speedUnit == "MPH") frDelta * UnitConversions.KM_TO_MI else frDelta
        DataCell("L/R DELTA", "${"%.1f".format(lrDisp)} $spdLabel", modifier = Modifier.weight(1f))
        DataCell("F/R DELTA", "${"%.1f".format(frDisp)} $spdLabel", modifier = Modifier.weight(1f))
    }
    if (vs.awdMaxTorque > 0) {
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("AWD MAX", "${vs.awdMaxTorque.roundToInt()} Nm", modifier = Modifier.weight(1f))
            DataCell("REQ L", "${vs.awdReqTorqueL.roundToInt()} Nm", modifier = Modifier.weight(1f))
            DataCell("REQ R", "${vs.awdReqTorqueR.roundToInt()} Nm", modifier = Modifier.weight(1f))
        }
    }
    if (vs.awdPumpCurrent > 0 || vs.transOilTempC > -90) {
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("TRANS", if (vs.transOilTempC > -90) "${p.displayTemp(vs.transOilTempC)}${p.tempLabel}" else "—", modifier = Modifier.weight(1f))
            DataCell("PUMP", "${"%.1f".format(vs.awdPumpCurrent)} A", modifier = Modifier.weight(1f))
            DataCell("PEAK", "${vs.awdMaxTorque.roundToInt()} Nm", modifier = Modifier.weight(1f))
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// G-FORCE SECTION (unchanged)
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun GForceSection(vs: VehicleState, onReset: () -> Unit) {
    val accent = LocalThemeAccent.current
    val animLatG by animateFloatAsState(vs.lateralG.toFloat(), spring(stiffness = Spring.StiffnessHigh), label = "latG")
    val animLonG by animateFloatAsState(vs.longitudinalG.toFloat(), spring(stiffness = Spring.StiffnessHigh), label = "lonG")

    // G-force trail (sampled at ~10 Hz)
    val gTrail = remember { RingBuffer<Pair<Float, Float>>(30) }
    val lastTrailTime = remember { mutableLongStateOf(0L) }
    val now = vs.lastUpdate
    if (now - lastTrailTime.longValue >= 100L) {
        lastTrailTime.longValue = now
        gTrail.push(vs.lateralG.toFloat() to vs.longitudinalG.toFloat())
    }

    Column(
        Modifier.fillMaxWidth()
            .background(Surf, RoundedCornerShape(16.dp))
            .border(1.dp, Brd, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        SectionLabel("G-FORCE & DYNAMICS")

        // G-Force dot plot
        val gPlotModifier = if (isWideLayout())
            Modifier.fillMaxWidth().aspectRatio(1f).heightIn(max = 280.dp).padding(8.dp)
        else
            Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)
        GForcePlot(
            lateralG = animLatG,
            longitudinalG = animLonG,
            trail = gTrail.toList(),
            modifier = gPlotModifier,
            dotColor = accent
        )

        Spacer(Modifier.height(8.dp))

        // Condensed numeric readout
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("LAT G",  "${"%.2f".format(animLatG)}",  modifier = Modifier.weight(1f))
            DataCell("LON G",  "${"%.2f".format(animLonG)}",  modifier = Modifier.weight(1f))
            DataCell("VERT G", "${"%.2f".format(vs.verticalG)}", modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DataCell("YAW",      "${"%.1f".format(vs.yawRate)}°/s",    modifier = Modifier.weight(1f))
            DataCell("STEER",    "${"%.1f".format(vs.steeringAngle)}°", modifier = Modifier.weight(1f))
            DataCell("COMBINED", "${"%.2f".format(vs.combinedG)}",     modifier = Modifier.weight(1f))
        }
        Spacer(Modifier.height(6.dp))
        Box(
            Modifier.fillMaxWidth()
                .background(Surf2, RoundedCornerShape(8.dp))
                .border(1.dp, Brd, RoundedCornerShape(8.dp))
                .clickable { onReset() }
                .padding(vertical = 8.dp),
            contentAlignment = Alignment.Center
        ) {
            MonoLabel("↺ RESET PEAKS", 9.sp, Dim, letterSpacing = 0.15.sp)
        }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// Helpers
// ═══════════════════════════════════════════════════════════════════════════

/** Formats a TPMS delta string with arrow prefix. Returns "" when delta is below threshold or data is missing. */
private fun tpmsDeltaText(currentPsi: Double, startPsi: Double): String {
    if (startPsi < 0 || currentPsi <= 0) return ""
    val delta = currentPsi - startPsi
    if (abs(delta) <= 0.5) return ""
    return if (delta > 0) "\u25B2 +${"%.1f".format(delta)}"
    else "\u25BC ${"%.1f".format(delta)}"
}
