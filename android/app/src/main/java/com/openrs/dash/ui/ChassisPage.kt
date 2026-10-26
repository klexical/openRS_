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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import com.openrs.dash.data.VehicleState
import com.openrs.dash.ui.anim.CarDiagram
import com.openrs.dash.ui.anim.GForcePlot
import com.openrs.dash.ui.anim.RingBuffer
import com.openrs.dash.ui.anim.SteeringArc
import com.openrs.dash.ui.anim.TOPDOWN_ASPECT
import com.openrs.dash.ui.anim.WHEEL_ANCHORS
import com.openrs.dash.data.thermalBandForTire
import com.openrs.dash.data.ThermalBand
import com.openrs.dash.R
import com.openrs.dash.ui.anim.tireStatusColor
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.Tokens.PagePad
import kotlin.math.abs
import kotlin.math.roundToInt

// ═══════════════════════════════════════════════════════════════════════════
// Chassis composables (v3.0: CHASSIS tab dropped — UnifiedChassisSection now
// rendered by TempsPage (THERMAL tab); GForceSection rendered by PerfPage).
// ═══════════════════════════════════════════════════════════════════════════

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
    // The user's TPMS thresholds (Settings → UNITS) — Low / Warn / High, all of them (Warn and High were 34 / 50 psi here).
    val lowThreshold = p.tireLowPsi.toDouble()
    val warnThreshold = p.tireWarnPsi.toDouble()
    val highThreshold = p.tireHighPsi.toDouble()

    // Animated tire status colors for the lead lines (same rule as before)
    val flColor by animateColorAsState(
        tireStatusColor(vs.tirePressLF, lowThreshold, warnThreshold, highThreshold), tween(400), label = "flC"
    )
    val frColor by animateColorAsState(
        tireStatusColor(vs.tirePressRF, lowThreshold, warnThreshold, highThreshold), tween(400), label = "frC"
    )
    val rlColor by animateColorAsState(
        tireStatusColor(vs.tirePressLR, lowThreshold, warnThreshold, highThreshold), tween(400), label = "rlC"
    )
    val rrColor by animateColorAsState(
        tireStatusColor(vs.tirePressRR, lowThreshold, warnThreshold, highThreshold), tween(400), label = "rrC"
    )

    // Deltas
    val deltaLF = tpmsDeltaText(vs.tirePressLF, vs.tireStartLF)
    val deltaRF = tpmsDeltaText(vs.tirePressRF, vs.tireStartRF)
    val deltaLR = tpmsDeltaText(vs.tirePressLR, vs.tireStartLR)
    val deltaRR = tpmsDeltaText(vs.tirePressRR, vs.tireStartRR)

    // Tire TPMS is polled by an OBD session, so when CAN is offline there's
    // nothing to warm up. Collapse the WARMING state to AVAILABLE (→ blank sub
    // label) so we don't shout "WARMING" four times on every offline view.
    fun tireAvail(hasReading: Boolean, lastMs: Long?): TempAvail {
        val a = scalarAvailFor(hasReading, lastMs)
        // Offline: no reading → quiet placeholder (not WARMING); held reading → STALE (last-known)
        return if (!vs.isConnected && a == TempAvail.WARMING) TempAvail.AVAILABLE else a.whenDisconnected(vs.isConnected)
    }
    val panelLevel = worstLevel(listOf(vs.tirePressLF, vs.tirePressRF, vs.tirePressLR, vs.tirePressRR)
        .map { tireLevel(it, lowThreshold, warnThreshold, highThreshold) })

    SectionLabel("TYRES")
    Column(
        Modifier.fillMaxWidth()
            .instrumentSurface(panelLevel)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        // ── Cards flanking the top-down RS artwork; each tyre is tinted by its own state ──
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
                            TyreCell("FL", vs.tirePressLF, p, lowThreshold,
                                vs.wheelSpeedFL, vs.tireTempLF, deltaLF,
                                avail = tireAvail(vs.tirePressLF >= 0, vs.fieldLastUpdateMs["tirePressLF"]),
                                live = vs.isConnected)
                        }
                        Box(Modifier.onGloballyPositioned {
                            val r = it.positionInRoot()
                            rlEdge = Offset(r.x - rowRoot.x + it.size.width, r.y - rowRoot.y + it.size.height / 2f)
                        }) {
                            TyreCell("RL", vs.tirePressLR, p, lowThreshold,
                                vs.wheelSpeedRL, vs.tireTempLR, deltaLR,
                                avail = tireAvail(vs.tirePressLR >= 0, vs.fieldLastUpdateMs["tirePressLR"]),
                                live = vs.isConnected)
                        }
                    }

                    // Center: RS wireframe with diamond markers + F/R bar
                    CarDiagram(
                        vs = vs,
                        prefs = p,
                        modifier = Modifier.width(120.dp).aspectRatio(TOPDOWN_ASPECT)
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
                            TyreCell("FR", vs.tirePressRF, p, lowThreshold,
                                vs.wheelSpeedFR, vs.tireTempRF, deltaRF,
                                avail = tireAvail(vs.tirePressRF >= 0, vs.fieldLastUpdateMs["tirePressRF"]),
                                live = vs.isConnected)
                        }
                        Box(Modifier.onGloballyPositioned {
                            val r = it.positionInRoot()
                            rrEdge = Offset(r.x - rowRoot.x, r.y - rowRoot.y + it.size.height / 2f)
                        }) {
                            TyreCell("RR", vs.tirePressRR, p, lowThreshold,
                                vs.wheelSpeedRR, vs.tireTempRR, deltaRR,
                                avail = tireAvail(vs.tirePressRR >= 0, vs.fieldLastUpdateMs["tirePressRR"]),
                                live = vs.isConnected)
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

        // ── Low pressure / imbalance warnings (same rules as before) ──
        val lowTires = buildList {
            if (vs.tirePressLF in 0.0..(lowThreshold - 0.001)) add("Front Left")
            if (vs.tirePressRF in 0.0..(lowThreshold - 0.001)) add("Front Right")
            if (vs.tirePressLR in 0.0..(lowThreshold - 0.001)) add("Rear Left")
            if (vs.tirePressRR in 0.0..(lowThreshold - 0.001)) add("Rear Right")
        }
        val spread = vs.maxTirePressSpread
        if (lowTires.isNotEmpty() || spread >= 4.0) {
            Spacer(Modifier.height(10.dp))
            if (lowTires.isNotEmpty())
                AlertChip("${lowTires.joinToString(", ").uppercase()} LOW", Level.CRIT, R.drawable.ic_tt_tyre)
            else
                AlertChip("PRESSURE IMBALANCE ${"%.1f".format(spread)} ${p.tireLabel}", Level.WARN, R.drawable.ic_tt_tyre)
        }

        // ── TPMS footer: low-pressure alert threshold + last updated ──
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // This is the low-pressure alert (UserPrefs.tireLowPsi), not a recommended pressure.
            InstrumentMeta("LOW ALERT < ${p.displayTire(lowThreshold)} ${p.tireLabel}")
            Spacer(Modifier.weight(1f))
            val updatedText = if (vs.tpmsLastUpdate > 0L) {
                val ago = (System.currentTimeMillis() - vs.tpmsLastUpdate) / 1000
                when {
                    ago < 5   -> "TPMS just now"
                    ago < 60  -> "TPMS ${ago}s ago"
                    ago < 3600 -> "TPMS ${ago / 60}m ago"
                    else       -> "TPMS ${ago / 3600}h ago"
                }
            } else "TPMS awaiting data"
            InstrumentMeta(updatedText)
        }

        // ── Wheel Rotation Counts (CAN 0x1E0) ──
        val hasRotation = vs.wheelRotFL > 0 || vs.wheelRotFR > 0 || vs.wheelRotRL > 0 || vs.wheelRotRR > 0
        if (hasRotation) {
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().height(1.dp).background(Brd.copy(alpha = borderAlpha(0.4f))))
            Spacer(Modifier.height(8.dp))
            InstrumentLabel("WHEEL ROTATION")
            Spacer(Modifier.height(4.dp))
            Row(Modifier.fillMaxWidth()) {
                MiniReadout("FL", "${vs.wheelRotFL}", Modifier.weight(1f))
                MiniReadout("FR", "${vs.wheelRotFR}", Modifier.weight(1f))
                MiniReadout("RL", "${vs.wheelRotRL}", Modifier.weight(1f))
                MiniReadout("RR", "${vs.wheelRotRR}", Modifier.weight(1f))
            }
        }
    }

    // ── AWD Drivetrain Section (always visible) ──
    AwdMetrics(vs, p)
}

/** Pressure level from the tyre card's existing rule ([tireStatusColor] defaults: warn 34, high 50 psi). */

/** Tyre temperature colour from the existing [thermalBandForTire] bands; in-range reads in ink. */
private fun tyreTempColor(tempC: Double): Color = when (thermalBandForTire(tempC)) {
    ThermalBand.COLD     -> Mid
    ThermalBand.NOMINAL  -> Frost
    ThermalBand.HOT      -> Warn
    ThermalBand.CRITICAL -> Orange
}

// ═══════════════════════════════════════════════════════════════════════════
// TYRE CELL — pressure (hero), temperature, wheel speed, pressure change
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun TyreCell(
    label: String,
    psi: Double,
    p: UserPrefs,
    lowThreshold: Double,
    wheelSpeedKph: Double,
    tempC: Double,
    deltaText: String,
    avail: TempAvail = TempAvail.AVAILABLE,
    /** Adapter connected — wheel speed is a per-frame reading, so offline it shows "—" (pressure / temp go STALE). */
    live: Boolean = true,
) {
    val level = tireLevel(psi, lowThreshold, p.tireWarnPsi.toDouble(), p.tireHighPsi.toDouble())
    val hasTemp = tempC > -90
    val availSub = when (avail) {
        TempAvail.WARMING     -> "WARMING"
        TempAvail.STALE       -> "STALE"
        TempAvail.UNAVAILABLE -> "N/A"
        TempAvail.AVAILABLE   -> ""
    }
    val speedStr = if (live) com.openrs.dash.ui.anim.formatWheelSpeed(wheelSpeedKph, p)
                   else "— " + (if (p.speedUnit == "MPH") "mph" else "kph")
    val animTireTemp by animateColorAsState(tyreTempColor(tempC), tween(400), label = "tireTC")
    val stale = avail == TempAvail.STALE

    BoxWithConstraints(Modifier.fillMaxWidth()) {
    // Narrow columns (360 dp phones) use the short form so every card keeps a one-line caption.
    val short = maxWidth < 116.dp
    val positionName = when (label) {
        "FL" -> if (short) "FRONT L" else "FRONT LEFT"; "FR" -> if (short) "FRONT R" else "FRONT RIGHT"
        "RL" -> if (short) "REAR L" else "REAR LEFT";   "RR" -> if (short) "REAR R" else "REAR RIGHT"
        else -> label
    }
    Column(
        Modifier.fillMaxWidth()
            .background(if (level.isAlert) levelColor(level).copy(alpha = 0.08f) else Surf3.copy(alpha = if (isDayModeNow()) 0.45f else 0.6f),
                RoundedCornerShape(10.dp))
            .border(if (level.isAlert) 1.dp else CardBorder,
                if (level.isAlert) levelColor(level).copy(alpha = borderAlpha(0.5f)) else Brd.copy(alpha = borderAlpha(0.2f)),
                RoundedCornerShape(10.dp))
            .padding(horizontal = 10.dp, vertical = 8.dp)
    ) {
        InstrumentLabel(positionName, color = if (level.isAlert) levelColor(level) else Dim)
        Row(verticalAlignment = Alignment.Bottom) {
            DriveNum(if (psi < 0) "—" else p.displayTire(psi), 26.sp, if (stale) Dim else levelColor(level))
            Spacer(Modifier.width(3.dp))
            InstrumentMeta(p.tireLabel.lowercase(), Modifier.padding(bottom = 4.dp))
        }
        // Always one status line (STALE / WARMING / pressure change / blank) so all four cards match in height.
        InstrumentMeta(availSub.ifEmpty { deltaText }, color = if (stale) WarnInk else Dim)
        if (hasTemp) DataNum(p.displayTemp(tempC) + p.tempLabel, 16.sp, if (stale) Dim else animTireTemp)
        InstrumentMeta(speedStr)
    }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// AWD METRICS — rear axle diagram + data cells
// ═══════════════════════════════════════════════════════════════════════════
@Composable
private fun AwdMetrics(held: VehicleState, p: UserPrefs) {
    // Disconnected: torque / pump / clutch / wheel-speed dynamics render at rest; temps stay, marked STALE.
    val vs = held.liveOrRest()
    val accent = LocalThemeAccent.current
    // Left = paint accent, right = its mid tier (green reads as "OK" everywhere else in the app).
    val rightColor = accentMid()
    val density = LocalDensity.current
    val textMeasurer = rememberTextMeasurer()
    val total = vs.totalRearTorque
    val avgF = (vs.wheelSpeedFL + vs.wheelSpeedFR) / 2
    val avgR = (vs.wheelSpeedRL + vs.wheelSpeedRR) / 2
    val frDelta = avgR - avgF
    val lrDelta = vs.wheelSpeedRR - vs.wheelSpeedRL

    // Precompute labels for the diagram
    // Offline: no live torque — dashes, not an at-rest "F 100 / R 0 · 0 Nm".
    val live = vs.isConnected
    val leftNm = if (live) "${vs.awdLeftTorque.roundToInt()} Nm" else "— Nm"
    val rightNm = if (live) "${vs.awdRightTorque.roundToInt()} Nm" else "— Nm"
    val rearPct = vs.rearTorquePct.roundToInt()
    val frontPct = 100 - rearPct
    val splitLabel = if (live) "F $frontPct / R $rearPct" else "F — / R —"
    // PTU / RDU / clutch temperatures live in THERMAL's DRIVETRAIN panel — the diagram shows torque only.

    // Clutch engagement: scale delivered torque against a realistic reference.
    // GKN Twinster can deliver up to ~2500 Nm per side, but 1000 Nm saturates
    // the visual range for aggressive driving (launch, hard cornering).
    val clutchRefNm = 1000.0
    val leftEngageTarget = (vs.awdLeftTorque / clutchRefNm).toFloat().coerceIn(0f, 1f)
    val rightEngageTarget = (vs.awdRightTorque / clutchRefNm).toFloat().coerceIn(0f, 1f)
    val leftEngage by animateFloatAsState(
        targetValue = leftEngageTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "leftEngage"
    )
    val rightEngage by animateFloatAsState(
        targetValue = rightEngageTarget,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "rightEngage"
    )

    val labelStyle = remember(density) {
        TextStyle(
            fontFamily = ShareTechMono, fontSize = with(density) { 10.sp },
            color = Dim, fontWeight = FontWeight.Normal, textAlign = TextAlign.Center
        )
    }
    val valueStyle = remember(density) {
        TextStyle(
            fontFamily = ShareTechMono, fontSize = with(density) { 13.sp },
            color = Frost, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
        )
    }
    val tempStyle = remember(density) {
        TextStyle(
            fontFamily = ShareTechMono, fontSize = with(density) { 11.sp },
            color = Frost.copy(alpha = 0.8f), fontWeight = FontWeight.Normal, textAlign = TextAlign.Center
        )
    }

    SectionLabel("AWD — GKN TWINSTER")
    Column(Modifier.fillMaxWidth().instrumentSurface().padding(horizontal = 12.dp, vertical = 12.dp)) {

    // ── Rear axle diagram ──
    Canvas(
        Modifier.fillMaxWidth().height(150.dp)
    ) {
        val w = size.width
        val h = size.height
        val midY = h * 0.54f
        val shaftStroke = 3.dp.toPx()
        val propStroke = 2.dp.toPx()

        // ── Component positions ──
        val wheelR = 14.dp.toPx()
        val rduW = 54.dp.toPx()
        val rduH = 34.dp.toPx()
        val cltW = 16.dp.toPx()
        val cltH = 22.dp.toPx()
        val ptuW = 42.dp.toPx()
        val ptuH = 22.dp.toPx()

        val leftWheelX = wheelR + 6.dp.toPx()
        val rightWheelX = w - wheelR - 6.dp.toPx()
        val rduX = w / 2f
        val rduLeft = rduX - rduW / 2f
        val rduRight = rduX + rduW / 2f
        val cltLX = rduLeft - 30.dp.toPx()
        val cltRX = rduRight + 30.dp.toPx()
        val ptuCenterX = rduX
        val ptuY = midY - rduH / 2f - 36.dp.toPx()

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
            color = rightColor.copy(alpha = 0.25f),
            topLeft = Offset(rduRight, midY - rightArrowW / 2f),
            size = androidx.compose.ui.geometry.Size(
                cltRX - cltW / 2f - rduRight, rightArrowW
            )
        )
        drawRect(
            color = rightColor.copy(alpha = 0.25f),
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

        // ── Propshaft (vertical: PTU bottom-center → RDU top-center) ──
        drawLine(Dim,
            Offset(ptuCenterX, ptuY + ptuH / 2f),
            Offset(rduX, midY - rduH / 2f),
            strokeWidth = propStroke)

        // ── PTU box ──
        drawRoundRect(
            color = Surf3,
            topLeft = Offset(ptuCenterX - ptuW / 2f, ptuY - ptuH / 2f),
            size = androidx.compose.ui.geometry.Size(ptuW, ptuH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx())
        )
        drawRoundRect(
            color = Brd,
            topLeft = Offset(ptuCenterX - ptuW / 2f, ptuY - ptuH / 2f),
            size = androidx.compose.ui.geometry.Size(ptuW, ptuH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(4.dp.toPx()),
            style = Stroke(width = 1.dp.toPx())
        )
        val ptuLabel = textMeasurer.measure("PTU", labelStyle)
        drawText(ptuLabel, topLeft = Offset(ptuCenterX - ptuLabel.size.width / 2f,
            ptuY - ptuLabel.size.height / 2f))

        // F/R split label above PTU
        val splitM = textMeasurer.measure(splitLabel, tempStyle.copy(color = Frost.copy(alpha = 0.7f)))
        drawText(splitM, topLeft = Offset(
            ptuCenterX - splitM.size.width / 2f,
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
            midY - rduLabel.size.height / 2f))

        // ── Clutch packs ──
        // Engagement-driven: fill alpha + stroke intensity scale with delivered torque.
        // At rest: dim Surf2 fill with faint stroke; under load: bright colored fill
        // showing active torque vectoring on that side.
        val cltCornerR = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx())

        // Left clutch — base (neutral) layer + engagement overlay
        drawRoundRect(
            color = Surf2,
            topLeft = Offset(cltLX - cltW / 2f, midY - cltH / 2f),
            size = androidx.compose.ui.geometry.Size(cltW, cltH),
            cornerRadius = cltCornerR
        )
        if (leftEngage > 0.01f) {
            drawRoundRect(
                color = accent.copy(alpha = 0.15f + 0.70f * leftEngage),
                topLeft = Offset(cltLX - cltW / 2f, midY - cltH / 2f),
                size = androidx.compose.ui.geometry.Size(cltW, cltH),
                cornerRadius = cltCornerR
            )
        }
        drawRoundRect(
            color = accent.copy(alpha = 0.35f + 0.50f * leftEngage),
            topLeft = Offset(cltLX - cltW / 2f, midY - cltH / 2f),
            size = androidx.compose.ui.geometry.Size(cltW, cltH),
            cornerRadius = cltCornerR,
            style = Stroke(width = (1f + leftEngage).dp.toPx())
        )

        // Right clutch — base (neutral) layer + engagement overlay
        drawRoundRect(
            color = Surf2,
            topLeft = Offset(cltRX - cltW / 2f, midY - cltH / 2f),
            size = androidx.compose.ui.geometry.Size(cltW, cltH),
            cornerRadius = cltCornerR
        )
        if (rightEngage > 0.01f) {
            drawRoundRect(
                color = rightColor.copy(alpha = 0.15f + 0.70f * rightEngage),
                topLeft = Offset(cltRX - cltW / 2f, midY - cltH / 2f),
                size = androidx.compose.ui.geometry.Size(cltW, cltH),
                cornerRadius = cltCornerR
            )
        }
        drawRoundRect(
            color = rightColor.copy(alpha = 0.35f + 0.50f * rightEngage),
            topLeft = Offset(cltRX - cltW / 2f, midY - cltH / 2f),
            size = androidx.compose.ui.geometry.Size(cltW, cltH),
            cornerRadius = cltCornerR,
            style = Stroke(width = (1f + rightEngage).dp.toPx())
        )

        // ── Wheels ──
        // Left wheel
        drawCircle(color = Surf3, radius = wheelR, center = Offset(leftWheelX, midY))
        drawCircle(color = accent.copy(alpha = 0.5f), radius = wheelR,
            center = Offset(leftWheelX, midY), style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = accent.copy(alpha = 0.3f), radius = wheelR * 0.4f,
            center = Offset(leftWheelX, midY))

        // Right wheel
        drawCircle(color = Surf3, radius = wheelR, center = Offset(rightWheelX, midY))
        drawCircle(color = rightColor.copy(alpha = 0.5f), radius = wheelR,
            center = Offset(rightWheelX, midY), style = Stroke(width = 2.dp.toPx()))
        drawCircle(color = rightColor.copy(alpha = 0.3f), radius = wheelR * 0.4f,
            center = Offset(rightWheelX, midY))

        // ── Torque value labels below wheels ──
        val leftNmM = textMeasurer.measure(leftNm, valueStyle.copy(color = accent))
        drawText(leftNmM, topLeft = Offset(leftWheelX - leftNmM.size.width / 2f,
            midY + wheelR + 6.dp.toPx()))

        val rightNmM = textMeasurer.measure(rightNm, valueStyle.copy(color = rightColor))
        drawText(rightNmM, topLeft = Offset(rightWheelX - rightNmM.size.width / 2f,
            midY + wheelR + 6.dp.toPx()))
    }

    // ── Readouts below the diagram (temps + F/R split are shown above / in DRIVETRAIN) ──
    val spdLabel = if (p.speedUnit == "MPH") "mph" else "km/h"
    val lrDisp = if (p.speedUnit == "MPH") lrDelta * UnitConversions.KM_TO_MI else lrDelta
    val frDisp = if (p.speedUnit == "MPH") frDelta * UnitConversions.KM_TO_MI else frDelta
    fun dyn(v: String) = if (live) v else "—"   // per-frame dynamics: no rest values offline
    val rows = buildList {
        add(listOf(
            Triple("REAR BIAS", dyn(vs.rearLeftRightBias), ""),
            Triple("L/R Δ", dyn("%.1f".format(lrDisp)), spdLabel),
            Triple("F/R Δ", dyn("%.1f".format(frDisp)), spdLabel),
        ))
        if (vs.awdMaxTorque > 0) add(listOf(
            Triple("REQ L", dyn("${vs.awdReqTorqueL.roundToInt()}"), "Nm"),
            Triple("REQ R", dyn("${vs.awdReqTorqueR.roundToInt()}"), "Nm"),
            // 0x2C0 bits 43–55, decoded every frame (live), so it gets the offline dash too.
            Triple("AWD MAX", dyn("${vs.awdMaxTorque.roundToInt()}"), "Nm"),
        ))
        // Per-clutch hydraulic actuators (0x1E9E/9F current, 0x1ED1/D2 pressure) + pump
        val hasClutchCur  = vs.awdClutchCurL > -900 || vs.awdClutchCurR > -900
        val hasClutchPres = vs.awdClutchPressureL >= 0 || vs.awdClutchPressureR >= 0
        if (hasClutchCur || vs.awdPumpCurrent > 0) add(listOf(
            Triple("CURRENT L", if (vs.awdClutchCurL > -900) "%.1f".format(vs.awdClutchCurL) else "—", "A"),
            Triple("CURRENT R", if (vs.awdClutchCurR > -900) "%.1f".format(vs.awdClutchCurR) else "—", "A"),
            Triple("PUMP", if (live && vs.awdPumpCurrent > 0) "%.1f".format(vs.awdPumpCurrent) else "—", "A"),
        ))
        if (hasClutchPres) add(listOf(
            Triple("PRESSURE L", if (vs.awdClutchPressureL >= 0) "%.1f".format(vs.awdClutchPressureL / 1000.0) else "—", "bar"),
            Triple("PRESSURE R", if (vs.awdClutchPressureR >= 0) "%.1f".format(vs.awdClutchPressureR / 1000.0) else "—", "bar"),
            Triple("", "", ""),
        ))
    }
    // Index of the first hydraulics row (clutch current / pressure + pump) — gets its own caption.
    val hydraulicsFrom = if (vs.awdMaxTorque > 0) 2 else 1
    Spacer(Modifier.height(4.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Brd.copy(alpha = borderAlpha(0.4f))))
    Spacer(Modifier.height(10.dp))
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEachIndexed { i, row ->
            if (i == hydraulicsFrom) InstrumentLabel("CLUTCH HYDRAULICS", color = Mid)
            Row(Modifier.fillMaxWidth()) {
                row.forEach { (label, value, unit) ->
                    Column(Modifier.weight(1f)) {
                        if (label.isNotEmpty()) {
                            InstrumentLabel(label)
                            Row(verticalAlignment = Alignment.Bottom) {
                                DataNum(value, 18.sp, if (value.startsWith("—")) Dim else Frost)
                                if (unit.isNotEmpty() && !value.startsWith("—")) {
                                    Spacer(Modifier.width(3.dp))
                                    InstrumentMeta(unit, Modifier.padding(bottom = 2.dp))
                                }
                            }
                        }
                    }
                }
            }
        }
    }
    }
}

// ═══════════════════════════════════════════════════════════════════════════
// G-FORCE SECTION (unchanged)
// ═══════════════════════════════════════════════════════════════════════════
@Composable fun GForceSection(held: VehicleState, onReset: () -> Unit) {
    val vs = held.liveOrRest()   // held last-frame G / yaw / steer must not read as live
    val accent = LocalThemeAccent.current
    val animLatG by animateFloatAsState(vs.lateralG.toFloat(), spring(stiffness = Spring.StiffnessHigh), label = "latG")
    val animLonG by animateFloatAsState(vs.longitudinalG.toFloat(), spring(stiffness = Spring.StiffnessHigh), label = "lonG")

    // G-force trail (sampled at ~10 Hz)
    val gTrail = remember(telemetrySessionKey()) { RingBuffer<Pair<Float, Float>>(120) }   // demo start/reset/exit drops simulated trail
    val lastTrailTime = remember { mutableLongStateOf(0L) }
    val now = vs.lastUpdate
    if (now - lastTrailTime.longValue >= 100L) {
        lastTrailTime.longValue = now
        gTrail.push(vs.lateralG.toFloat() to vs.longitudinalG.toFloat())
    }

    SectionLabel("G-FORCE & DYNAMICS")
    Column(Modifier.fillMaxWidth().instrumentSurface().padding(horizontal = 12.dp, vertical = 12.dp)) {
        // G-Force dot plot — always square (equal g per dp on both axes). Its side comes from the screen
        // alone: full width on tall phones, 34 % of the height under 800 dp so a 360 × 720 dp phone keeps the
        // current LAT / LON / VERT readings above the nav bar, 280 dp on wide layouts. Never resizes with readings.
        val screenH = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp().value }
        val gPlotSide = when {
            isWideLayout() -> 280.dp
            screenH < 800  -> (screenH * 0.34f).dp
            else           -> Dp.Infinity
        }
        val gPlotModifier = Modifier.widthIn(max = gPlotSide).fillMaxWidth().aspectRatio(1f)
            .align(Alignment.CenterHorizontally).padding(4.dp)
        GForcePlot(
            lateralG = animLatG,
            longitudinalG = animLonG,
            trail = gTrail.toList(),
            peakLatG = vs.peakLateralG.toFloat(),
            peakLonG = vs.peakLongitudinalG.toFloat(),
            modifier = gPlotModifier,
            dotColor = accent,
            isConnected = vs.isConnected
        )

        // V2: Steering angle arc — semicircle below G-force plot
        SteeringArc(
            angle = vs.steeringAngle.toFloat(),
            modifier = Modifier.fillMaxWidth(0.4f).height(28.dp)
                .align(Alignment.CenterHorizontally),
            isConnected = vs.isConnected
        )

        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(1.dp).background(Brd.copy(alpha = borderAlpha(0.4f))))
        Spacer(Modifier.height(8.dp))
        // Readouts — one grid inside the panel (was six separate cards). Offline → dashes.
        fun d(v: String) = if (vs.isConnected) v else "— —"
        Row(Modifier.fillMaxWidth()) {
            MiniReadout("LAT G",  d("%.2f".format(animLatG)),  Modifier.weight(1f))
            MiniReadout("LON G",  d("%.2f".format(animLonG)),  Modifier.weight(1f))
            MiniReadout("VERT G", d("%.2f".format(vs.verticalG)), Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth()) {
            MiniReadout("YAW",      d("%.1f°/s".format(vs.yawRate)),    Modifier.weight(1f))
            MiniReadout("STEER",    d("%.1f°".format(vs.steeringAngle)), Modifier.weight(1f))
            MiniReadout("COMBINED", d("%.2f".format(vs.combinedG)),     Modifier.weight(1f))
        }
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier.fillMaxWidth().heightIn(min = 44.dp)
                .background(Surf3.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                .clickable(onClickLabel = "Reset session peaks") { onReset() },
            contentAlignment = Alignment.Center
        ) {
            InstrumentLabel("↺  RESET PEAKS", color = Mid)
        }
    }
}

private fun tpmsDeltaText(currentPsi: Double, startPsi: Double): String {
    if (startPsi < 0 || currentPsi <= 0) return ""
    val delta = currentPsi - startPsi
    if (abs(delta) <= 0.5) return ""
    return if (delta > 0) "\u25B2 +${"%.1f".format(delta)}"
    else "\u25BC ${"%.1f".format(delta)}"
}
