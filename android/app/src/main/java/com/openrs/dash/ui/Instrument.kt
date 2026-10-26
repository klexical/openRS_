package com.openrs.dash.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.ui.anim.Sparkline
import com.openrs.dash.ui.anim.cardGlow

// ═══════════════════════════════════════════════════════════════════════════
// INSTRUMENT PANEL — design-pass building blocks (DRIVE + THERMAL)
// ═══════════════════════════════════════════════════════════════════════════
// Colour rule: readings render in ink; the paint accent marks live / brand elements (sparklines,
// pedal fill, selected chips); Warn and Orange appear only when a reading crosses an existing
// threshold. A dashboard that is amber at rest can't make a real warning stand out.

/** Threshold state of one reading. COLD / STALE / NONE are informational, never alarms. */
enum class Level { NORMAL, COLD, WARN, CRIT, STALE, NONE }

val Level.isAlert: Boolean get() = this == Level.WARN || this == Level.CRIT

/** Value colour for a [Level]: ink when normal, semantic colours only for WARN / CRIT. */
fun levelColor(level: Level): Color = when (level) {
    Level.NORMAL -> Frost
    Level.COLD   -> Mid
    Level.WARN   -> Warn
    Level.CRIT   -> Orange
    Level.STALE, Level.NONE -> Dim
}

/** Worst alert across readings (CRIT > WARN > anything else → NORMAL). */
fun worstLevel(levels: Iterable<Level>): Level = when {
    levels.any { it == Level.CRIT } -> Level.CRIT
    levels.any { it == Level.WARN } -> Level.WARN
    else -> Level.NORMAL
}

/**
 * Temperature level from the preset's warn / crit limits (UserPrefs) and its availability.
 * COLD reuses the existing "below 60 % of warn" band from [tempColorShade].
 */
fun tempLevel(tempC: Double, warnC: Double, critC: Double, avail: TempAvail): Level = when {
    avail == TempAvail.STALE     -> Level.STALE
    avail != TempAvail.AVAILABLE -> Level.NONE
    tempC >= critC               -> Level.CRIT
    tempC >= warnC               -> Level.WARN
    tempC < warnC * 0.6          -> Level.COLD
    else                         -> Level.NORMAL
}

/**
 * Tyre pressure state (psi, whatever the display unit) from the user's Low / Warn / High thresholds — the one
 * classification THERMAL's tyre cards and DRIVE's alerts share: < Low critical, < Warn caution, > High critical.
 */
internal fun tireLevel(psi: Double, lowThreshold: Double, warnThreshold: Double, highThreshold: Double) = when {
    psi < 0              -> Level.NONE
    psi < lowThreshold   -> Level.CRIT
    psi < warnThreshold  -> Level.WARN
    psi > highThreshold  -> Level.CRIT
    else                 -> Level.NORMAL
}

/** A tyre DRIVE should mention. LOW / CAUTION = under-inflated, HIGH = over-inflated. */
internal enum class TyreIssue { LOW, CAUTION, HIGH }

/**
 * DRIVE's tyre alerts from [tireLevel]: the lowest tyre if it is under Warn / Low (as before), plus the highest
 * tyre if it is over High. Under-inflation is listed first; missing readings (< 0) are ignored.
 */
internal fun tyreIssues(
    pressures: List<Pair<String, Double>>, low: Double, warn: Double, high: Double,
): List<Triple<String, Double, TyreIssue>> {
    val known = pressures.filter { it.second >= 0 }
    return buildList {
        known.minByOrNull { it.second }?.let { (pos, psi) ->
            when (tireLevel(psi, low, warn, high)) {
                Level.CRIT -> if (psi < low) add(Triple(pos, psi, TyreIssue.LOW))
                Level.WARN -> add(Triple(pos, psi, TyreIssue.CAUTION))
                else -> {}
            }
        }
        known.maxByOrNull { it.second }?.let { (pos, psi) ->
            if (tireLevel(psi, low, warn, high) == Level.CRIT && psi > high) add(Triple(pos, psi, TyreIssue.HIGH))
        }
    }
}

/** Instrument card fill: flat white on DAY (separated by a hairline, not a shadow), Surf2 at night. */
val InstrumentFill: Color get() = if (isDayModeNow()) Surf else Surf2

/** Shared card surface. The edge tints (and thickens) only for WARN / CRIT. */
fun Modifier.instrumentSurface(level: Level = Level.NORMAL, radius: Dp = Tokens.CardRadius): Modifier {
    val shape = RoundedCornerShape(radius)
    val alert = level.isAlert
    val day = isDayModeNow()
    val edge = when (level) {
        Level.CRIT -> Orange.copy(alpha = borderAlpha(0.6f))
        Level.WARN -> Warn.copy(alpha = borderAlpha(0.45f))
        else       -> if (day) Brd.copy(alpha = 0.75f) else Brd.copy(alpha = 0.15f)
    }
    return this
        .then(if (day) Modifier else Modifier.cardGlow(cornerRadius = radius))
        .background(InstrumentFill, shape)
        // A light wash only — the edge, telltales and values carry the state (DAY used to hit 15 %).
        .then(if (alert) Modifier.background(levelColor(level).copy(alpha = if (day) 0.07f else 0.04f), shape) else Modifier)
        .border(if (alert) 1.dp else Tokens.CardBorder, edge, shape)
}

/** Card / section caption — one size and tracking everywhere on the instrument screens. */
@Composable fun InstrumentLabel(text: String, modifier: Modifier = Modifier, color: Color = Dim) =
    MonoLabel(text, 10.sp, color, letterSpacing = 0.5.sp, modifier = modifier)

/**
 * Supporting text (PEAK, LIMIT, units, torque detail). Same family as the captions, tighter
 * tracking and full-strength Dim — the old 8–9 sp, alpha-muted lines were unreadable at arm's length.
 */
@Composable fun InstrumentMeta(text: String, modifier: Modifier = Modifier, color: Color = Dim) =
    MonoLabel(text, 10.sp, color, letterSpacing = 0.2.sp, modifier = modifier)

/** Telltale glyph (res/drawable/ic_tt_*) tinted to its state. */
@Composable fun Telltale(@DrawableRes icon: Int, tint: Color, size: Dp = 14.dp, contentDescription: String? = null) =
    Icon(painterResource(icon), contentDescription, Modifier.size(size), tint = tint)

/**
 * Primary live readout (DRIVE hero row): caption + unit on one line, a value box of fixed
 * height so baselines line up across a row, trend line, peak. Long-press shows the session
 * peak for 3 s (same gesture as the old HeroCard).
 */
@Composable fun InstrumentHero(
    label: String,
    unit: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = Frost,
    valueSize: TextUnit = 32.sp,
    valueBox: Dp = 46.dp,
    peak: String? = null,
    peakHoldValue: String? = null,
    spark: List<Float>? = null,
    sparkColor: Color = accentMid(),
    level: Level = Level.NORMAL,
    /** Optional symbol before the caption (sheet family) and its treatment. */
    icon: RsIcon? = null,
    iconTone: IconTone = level.iconTone(),
) {
    val accent = LocalThemeAccent.current
    var hold by remember { mutableStateOf(false) }
    LaunchedEffect(hold) { if (hold) { kotlinx.coroutines.delay(3000); hold = false } }
    val haptic = LocalHapticFeedback.current
    val holdModifier = if (peakHoldValue != null) Modifier.pointerInput(Unit) {
        detectTapGestures(onLongPress = {
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); hold = true
        })
    } else Modifier
    Column(
        modifier.then(holdModifier)
            .instrumentSurface(level, Tokens.HeroRadius)
            .padding(horizontal = 12.dp, vertical = 12.dp)
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 12 dp: inside the caption's line height, so the value box doesn't move
                if (icon != null) { TelemetryIcon(icon, iconTone, 14.dp); Spacer(Modifier.width(4.dp)) }
                InstrumentLabel(if (hold) "PEAK" else label, color = if (hold) accent else Dim)
            }
            InstrumentMeta(unit)
        }
        Box(Modifier.fillMaxWidth().height(valueBox), contentAlignment = Alignment.BottomCenter) {
            DriveNum(if (hold && peakHoldValue != null) peakHoldValue else value,
                valueSize, valueColor, Modifier.fillMaxWidth())
        }
        Spacer(Modifier.height(8.dp))
        if (spark != null && spark.size >= 2) {
            Sparkline(spark, sparkColor, Modifier.fillMaxWidth().height(18.dp), strokeWidth = 1.5.dp, fillAlpha = 0.12f)
        } else Spacer(Modifier.height(18.dp))
        Spacer(Modifier.height(6.dp))
        InstrumentMeta(peak ?: "", Modifier.fillMaxWidth())
    }
}

/**
 * Compact reading inside a status panel: telltale + caption, value, optional sub-tag.
 * The telltale and value only take colour when the reading is in a WARN / CRIT state.
 */
@Composable fun StatusCell(
    icon: RsIcon,
    label: String,
    value: String,
    level: Level,
    modifier: Modifier = Modifier,
    /** Tag under the value (STALE / LOW / …); null = no tag line at all. */
    sub: String? = null,
) {
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // The sheet's two-tone symbol while normal; the whole glyph takes an alert / unavailable colour
            TelemetryIcon(icon, level.iconTone(), 16.dp, contentDescription = label)
            Spacer(Modifier.width(5.dp))
            InstrumentLabel(label)
        }
        Spacer(Modifier.height(4.dp))
        DataNum(value, 20.sp, levelColor(level))
        if (sub != null) MonoLabel(sub, 9.sp, if (level.isAlert) levelColor(level) else Dim,
            if (level.isAlert) FontWeight.Bold else FontWeight.Normal, letterSpacing = 0.3.sp)
    }
}

/** One alert in the DRIVE status panel — only rendered when something is actually wrong. */
@Composable fun AlertChip(text: String, level: Level, @DrawableRes icon: Int? = null) {
    val c = levelColor(level)
    Row(
        Modifier
            .background(c.copy(alpha = pillBgAlpha(0.12f)), RoundedCornerShape(6.dp))
            .border(Tokens.CardBorder, c.copy(alpha = borderAlpha(0.5f)), RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (icon != null) { Telltale(icon, c, 13.dp); Spacer(Modifier.width(5.dp)) }
        MonoLabel(text, 10.sp, c, FontWeight.Bold, letterSpacing = 0.3.sp)
    }
}

/**
 * Temperature scale, read like a tachometer: the track shows a faint caution zone (warn → crit) and
 * a redline zone (≥ crit) so the limits are recognisable before they're reached; the fill is the
 * reading. Warn mark = thin amber line, crit mark = taller orange line that rises above the bar.
 * Scale tops out at 115 % of crit; [peakC] adds a session-peak dot. [ThresholdLegend] explains it.
 */
@Composable fun ThresholdBar(
    tempC: Double,
    warnC: Double,
    critC: Double,
    level: Level,
    modifier: Modifier = Modifier,
    peakC: Double? = null,
) {
    val max = critC * 1.15
    val fill = when (level) {
        Level.WARN, Level.CRIT -> levelColor(level)
        Level.NORMAL -> accentMid()
        Level.COLD -> Mid.copy(alpha = 0.55f)
        else -> Color.Transparent
    }
    val track = Surf3
    val day = isDayModeNow()
    val cautionZone = Warn.copy(alpha = if (day) 0.20f else 0.12f)
    val redZone = Orange.copy(alpha = if (day) 0.22f else 0.20f)
    val warnMark = Warn.copy(alpha = 0.85f)
    val critMark = if (level == Level.CRIT) Orange else Orange.copy(alpha = 0.8f)
    val peakDot = Frost.copy(alpha = 0.8f)
    Canvas(modifier.fillMaxWidth().height(14.dp)) {
        val barH = 6.dp.toPx()
        val y = (size.height - barH) / 2f
        val r = CornerRadius(barH / 2f)
        fun x(c: Double) = (c / max).toFloat().coerceIn(0f, 1f) * size.width
        drawRoundRect(track, Offset(0f, y), Size(size.width, barH), r)
        drawRect(cautionZone, Offset(x(warnC), y), Size(x(critC) - x(warnC), barH))
        drawRoundRect(redZone, Offset(x(critC), y), Size(size.width - x(critC), barH), r)
        if (tempC > 0 && fill != Color.Transparent) drawRoundRect(fill, Offset(0f, y), Size(x(tempC), barH), r)
        val thin = 1.5.dp.toPx()
        drawRect(warnMark, Offset(x(warnC) - thin / 2f, y - 2.dp.toPx()), Size(thin, barH + 4.dp.toPx()))
        val thick = 2.5.dp.toPx()
        drawRect(critMark, Offset(x(critC) - thick / 2f, 0f), Size(thick, size.height))
        if (peakC != null && peakC > 0) drawCircle(peakDot, 2.dp.toPx(), Offset(x(peakC), size.height / 2f))
    }
}

/**
 * Key for [ThresholdBar] marks — with the active preset's values in the user's unit, or (null)
 * as a generic key under a group of bars that each have their own limits.
 */
@Composable fun ThresholdLegend(warn: String?, crit: String?, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (warn == null) {
            Box(Modifier.size(5.dp).background(Frost.copy(alpha = 0.8f), androidx.compose.foundation.shape.CircleShape))
            Spacer(Modifier.width(5.dp))
            InstrumentMeta("PEAK")
            Spacer(Modifier.width(12.dp))
        }
        Box(Modifier.size(width = 2.dp, height = 10.dp).background(Warn.copy(alpha = 0.85f)))
        Spacer(Modifier.width(5.dp))
        InstrumentMeta(if (warn != null) "WARN $warn" else "WARN")
        Spacer(Modifier.width(12.dp))
        Box(Modifier.size(width = 3.dp, height = 12.dp).background(Orange))
        Spacer(Modifier.width(5.dp))
        InstrumentMeta(if (crit != null) "CRIT $crit" else "CRIT")
    }
}

/** Centred caption + value used in the DRIVE chassis panel readout row. */
@Composable fun MiniReadout(label: String, value: String, modifier: Modifier = Modifier, valueColor: Color = Frost) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        InstrumentLabel(label)
        Spacer(Modifier.height(3.dp))
        DataNum(value, 16.sp, if (value.startsWith("—")) Dim else valueColor)
    }
}

/** Thin vertical divider between cells of an instrument panel. */
@Composable fun PanelDivider(modifier: Modifier = Modifier) =
    Box(modifier.width(1.dp).height(44.dp).background(Brd.copy(alpha = borderAlpha(0.5f))))

/** One reading in a [ReadoutGrid]: caption, value (with its unit), optional tag and trend line. */
internal data class Readout(
    val label: String,
    val value: String,
    val color: Color = Color.Unspecified,
    val sub: String? = null,
    val spark: List<Float>? = null,
)

/**
 * A section's readings as one instrument panel (was one card per value). Rows of equal-width
 * columns; null = empty slot. [captions] inserts a sub-heading before the given row index.
 * [dense] (TRIP's live panel under the map) tightens spacing and the value size, not the captions;
 * [footer] adds content inside the same panel after the rows.
 */
@Composable internal fun ReadoutGrid(
    rows: List<List<Readout?>>, level: Level = Level.NORMAL, captions: Map<Int, String> = emptyMap(),
    dense: Boolean = false, footer: (@Composable () -> Unit)? = null,
) {
    Column(
        Modifier.fillMaxWidth().instrumentSurface(level)
            .padding(horizontal = 14.dp, vertical = if (dense) 10.dp else 12.dp),
        verticalArrangement = Arrangement.spacedBy(if (dense) 8.dp else 12.dp)
    ) {
        rows.forEachIndexed { i, row ->
            captions[i]?.let { InstrumentLabel(it, color = Mid) }
            Row(Modifier.fillMaxWidth()) {
                row.forEach { r ->
                    Column(Modifier.weight(1f)) {
                        if (r != null) {
                            val placeholder = r.value.startsWith("—")
                            InstrumentLabel(r.label)
                            Spacer(Modifier.height(2.dp))
                            DataNum(r.value, if (dense) 16.sp else 18.sp,
                                if (placeholder) Dim else if (r.color == Color.Unspecified) Frost else r.color)
                            r.sub?.let { MonoLabel(it, 9.sp, WarnInk, letterSpacing = 0.2.sp) }
                            if (r.spark != null && !placeholder) {
                                Spacer(Modifier.height(3.dp))
                                com.openrs.dash.ui.anim.Sparkline(
                                    data = r.spark,
                                    lineColor = if (r.color == Color.Unspecified || r.color == Frost) accentMid() else r.color,
                                    modifier = Modifier.fillMaxWidth().padding(end = 10.dp).height(16.dp),
                                    strokeWidth = 1.dp, fillAlpha = 0.12f
                                )
                            }
                        }
                    }
                }
            }
        }
    footer?.invoke()
    }
}
