package com.openrs.dash.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
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
//   [DEMO 0:42] · · · [DRIVE ▾] [❚❚] [↺] [✕]
// Every control is a ≥ 48 × 48 dp touch target (strip is 48 dp tall); the four
// scenarios live in one menu so the row still fits a 360 dp phone.
// Amber = "not real data". The strip is the always-visible DEMO indicator;
// the header connection pill also reads DEMO.
// ═══════════════════════════════════════════════════════════════════════════

/** Text colour for amber demo labels — the shared [WarnInk] (DAY-legible amber). */
fun demoInk(): Color = WarnInk

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
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth()
            .height(48.dp)
            .background(Warn.copy(alpha = pillBgAlpha(0.07f)))
            .drawBehind {
                drawLine(lineColor, androidx.compose.ui.geometry.Offset(0f, size.height),
                    androidx.compose.ui.geometry.Offset(size.width, size.height), 1.dp.toPx())
            }
            .padding(start = 10.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        DemoBadge()
        Spacer(Modifier.width(6.dp))
        MonoLabel(
            when {
                status.paused                 -> "$clock \u275A\u275A"
                status.link == DemoLink.LOST  -> "$clock LOST"
                else                          -> clock
            },
            9.sp, if (status.paused) demoInk() else Mid, FontWeight.Bold, 0.05.sp
        )
        Spacer(Modifier.weight(1f))
        Box {
            DemoButton("${status.scenario.chip} \u25BE", "Demo scenario: ${status.scenario.label}",
                selected = true, minWidth = 88.dp) { menuOpen = true }
            DropdownMenu(
                expanded = menuOpen,
                onDismissRequest = { menuOpen = false },
                containerColor = SurfUp,
            ) {
                DemoScenario.entries.forEach { sc ->
                    val current = sc == status.scenario
                    DropdownMenuItem(
                        text = {
                            Column {
                                MonoLabel(sc.label.uppercase(), 11.sp, if (current) demoInk() else Frost, FontWeight.Bold)
                                MonoLabel(sc.blurb, 8.sp, Dim)
                            }
                        },
                        onClick = { menuOpen = false; onScenario(sc) },
                        modifier = Modifier.widthIn(max = 300.dp),
                    )
                }
            }
        }
        DemoButton(if (status.paused) "\u25B6" else "\u275A\u275A",
            if (status.paused) "Resume demo" else "Pause demo", selected = status.paused, onClick = onTogglePause)
        DemoButton("\u21BA", "Restart scenario", selected = false, onClick = onReset)
        DemoButton("\u2715", "Exit demo mode", selected = false, tint = Orange, onClick = onExit)
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

/** 48 × 48 dp touch target around a compact pill — the visual stays strip-sized. */
@Composable
private fun DemoButton(
    text: String,
    description: String,
    selected: Boolean,
    tint: Color = Warn,
    minWidth: androidx.compose.ui.unit.Dp = 48.dp,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        Modifier
            .sizeIn(minWidth = minWidth, minHeight = 48.dp)
            .semantics { contentDescription = description; role = Role.Button }
            .pressClick(pressedScale = 0.94f) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Box(
            Modifier
                .padding(horizontal = 4.dp)
                .heightIn(min = 30.dp)
                .widthIn(min = minWidth - 12.dp)
                .background(if (selected) tint.copy(alpha = pillBgAlpha(0.22f)) else Surf2, shape)
                .border(CardBorder, tint.copy(alpha = borderAlpha(if (selected) 0.7f else 0.25f)), shape)
                .padding(horizontal = 6.dp),
            contentAlignment = Alignment.Center
        ) {
            val ink = if (tint == Warn) demoInk() else tint
            MonoLabel(text, 10.sp, if (selected) ink else Mid, FontWeight.Bold, 0.06.sp)
        }
    }
}
