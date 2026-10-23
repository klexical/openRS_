package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openrs.dash.demo.DemoLink
import com.openrs.dash.demo.DemoMode
import com.openrs.dash.demo.DemoScenario
import com.openrs.dash.ui.Tokens.CardBorder
import com.openrs.dash.ui.anim.pressClick

// ═══════════════════════════════════════════════════════════════════════════
// DEMO CONTROL STRIP — debug builds only
//
// Sits directly under AppHeader while demo mode runs, on every tab:
//   [DEMO 0:42] · · · [IDLE][DRIVE][WARN][LOSS] [❚❚] [↺] [✕]
// Amber = "not real data". The strip is the always-visible DEMO indicator;
// the header connection pill also reads DEMO.
// ═══════════════════════════════════════════════════════════════════════════

/**
 * Text colour for amber demo labels. DAY's Warn (#E09500) is ~2:1 on its own tint and on
 * the light header, so DAY uses a dark amber ink (≥ 6:1 on the strip background).
 */
fun demoInk(): Color = if (isDayModeNow()) Color(0xFF6B4700) else Warn

@Composable
fun DemoControlStrip(
    status: DemoMode.Status,
    onScenario: (DemoScenario) -> Unit,
    onTogglePause: () -> Unit,
    onReset: () -> Unit,
    onExit: () -> Unit,
) {
    val secs = status.elapsedMs / 1000
    val clock = "%d:%02d".format(secs / 60, secs % 60)
    val lineColor = Warn.copy(alpha = borderAlpha(0.5f))
    Row(
        Modifier.fillMaxWidth()
            .height(32.dp)
            .background(Warn.copy(alpha = pillBgAlpha(0.07f)))
            .drawBehind {
                drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, size.height),
                    androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(5.dp)
    ) {
        DemoBadge()
        MonoLabel(
            when {
                status.paused                 -> "$clock ❚❚"
                status.link == DemoLink.LOST  -> "$clock LOST"
                else                          -> clock
            },
            8.sp, if (status.paused) demoInk() else Mid, FontWeight.Bold, 0.05.sp
        )
        Spacer(Modifier.weight(1f))
        DemoScenario.entries.forEach { sc ->
            DemoChip(sc.chip, selected = sc == status.scenario) { onScenario(sc) }
        }
        Spacer(Modifier.width(2.dp))
        DemoChip(if (status.paused) "▶" else "❚❚", selected = status.paused, onClick = onTogglePause)
        DemoChip("↺", selected = false, onClick = onReset)
        DemoChip("✕", selected = false, tint = Orange, onClick = onExit)
    }
}

/** Key for UI-held telemetry histories — changes when a demo run starts, resets or ends. */
@Composable
fun telemetrySessionKey(): Int = DemoMode.sessionEpoch.collectAsState().value

/** Filled amber DEMO tag — also shown on full-screen overlays that cover the strip. */
@Composable
fun DemoBadge() {
    Box(
        Modifier.background(Warn, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 2.dp)
    ) {
        MonoLabel("DEMO", 8.sp, Color.Black, FontWeight.Bold, 0.12.sp)
    }
}

@Composable
private fun DemoChip(text: String, selected: Boolean, tint: Color = Warn, onClick: () -> Unit) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .heightIn(min = 24.dp)
            .widthIn(min = 24.dp)
            .background(if (selected) tint.copy(alpha = pillBgAlpha(0.22f)) else Surf2, shape)
            .border(CardBorder, tint.copy(alpha = borderAlpha(if (selected) 0.7f else 0.25f)), shape)
            .pressClick(pressedScale = 0.94f) { onClick() }
            .padding(horizontal = 5.dp),
        contentAlignment = Alignment.Center
    ) {
        val ink = if (tint == Warn) demoInk() else tint
        MonoLabel(text, 8.sp, if (selected) ink else Mid, FontWeight.Bold, 0.06.sp)
    }
}
