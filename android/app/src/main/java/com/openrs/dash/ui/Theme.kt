package com.openrs.dash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.openrs.dash.R

// ═══════════════════════════════════════════════════════════════════════════
// DESIGN SYSTEM — F1 PALETTE
// ═══════════════════════════════════════════════════════════════════════════

// Core backgrounds (deep navy-black)
val Bg      = Color(0xFF05070A)
val Surf    = Color(0xFF0A0D12)
val Surf2   = Color(0xFF0F141C)
val Surf3   = Color(0xFF141B26)

// Borders and text
val Brd     = Color(0xFF162030)     // Default border (dark navy)
val Frost   = Color(0xFFE8F4FF)     // Primary text (near-white, blue tint)
val Dim     = Color(0xFF3D5A72)     // Muted / dim text
val Mid     = Color(0xFF7A9AB8)     // Medium emphasis

// Elevated surfaces and on-accent contrast
val SurfUp  = Color(0xFF141414)     // Elevated surface (modal headers, cards)
val OnAccent = Color(0xFF0A0A0A)    // Dark text/content on accent-colored buttons

// Accent colors
val Accent  = Color(0xFF0091EA)     // Nitrous Blue — primary interactive
val AccentD = Color(0xFF006DB3)     // Darker Nitrous Blue
val Orange  = Color(0xFFFF4D00)     // Orange-red — aggressive/hot
val Ok      = Color(0xFF00FF88)     // Neon green — good/ok
val Warn    = Color(0xFFFFCC00)     // Gold — attention/warm

// Aliases (backwards-compat with composables using old names)
val Grn   get() = Ok
val Amber get() = Warn
val Red   get() = Orange

// ── RS MK3 paint theme palette ────────────────────────────────────────────
data class RsPaint(val id: String, val name: String, val accent: Color)

val RsPaints = listOf(
    RsPaint("cyan",   "Nitrous Blue",  Color(0xFF0091EA)),
    RsPaint("red",    "Race Red",      Color(0xFFD62828)),
    RsPaint("orange", "Deep Orange",   Color(0xFFD45500)),
    RsPaint("grey",   "Stealth Grey",  Color(0xFF6B7580)),
    RsPaint("black",  "Shadow Black",  Color(0xFF3A3D44)),
    RsPaint("white",  "Frozen White",  Color(0xFFE8ECF0)),
)

private val paintMap = RsPaints.associateBy { it.id }

fun rsPaintAccent(id: String): Color = paintMap[id]?.accent ?: Accent
fun rsPaintName(id: String): String  = paintMap[id]?.name  ?: "Nitrous Blue"

// ── CompositionLocal for theme accent ─────────────────────────────────────
val LocalThemeAccent = staticCompositionLocalOf { Accent }

// ═══════════════════════════════════════════════════════════════════════════
// FONTS
// ═══════════════════════════════════════════════════════════════════════════

val OrbitronFamily = FontFamily(
    Font(R.font.orbitron_regular, FontWeight.Normal),
    Font(R.font.orbitron_bold,    FontWeight.Bold)
)
val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold,    FontWeight.Bold)
)
val ShareTechMono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))
val BarlowCond    = FontFamily(
    Font(R.font.barlow_condensed_regular,  FontWeight.Normal),
    Font(R.font.barlow_condensed_medium,   FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold,     FontWeight.Bold)
)

// ═══════════════════════════════════════════════════════════════════════════
// TYPOGRAPHY HELPERS
// ═══════════════════════════════════════════════════════════════════════════

/** Large hero numeric values — Orbitron Bold */
@Composable fun HeroNum(
    text: String,
    fontSize: TextUnit,
    color: Color = Frost,
    modifier: Modifier = Modifier
) = Text(
    text, fontSize = fontSize, fontFamily = OrbitronFamily, color = color,
    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
    lineHeight = fontSize * 1.1f, modifier = modifier
)

/** Small monospace labels — JetBrains Mono */
@Composable fun MonoLabel(
    text: String,
    fontSize: TextUnit,
    color: Color = Dim,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = 0.15.sp,
    modifier: Modifier = Modifier
) = Text(
    text, fontSize = fontSize, fontFamily = JetBrainsMonoFamily, color = color,
    fontWeight = fontWeight, letterSpacing = letterSpacing, modifier = modifier
)

/** Monospace readouts (frame console, raw values) — Share Tech Mono */
@Composable fun MonoText(
    text: String,
    fontSize: TextUnit,
    color: Color = Frost,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier
) = Text(
    text, fontSize = fontSize, fontFamily = ShareTechMono, color = color,
    fontWeight = fontWeight, textAlign = textAlign, modifier = modifier
)

/** Body / label text — Barlow Condensed */
@Composable fun UIText(
    text: String,
    fontSize: TextUnit,
    color: Color = Frost,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = 0.sp,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier
) = Text(
    text, fontSize = fontSize, fontFamily = BarlowCond, color = color,
    fontWeight = fontWeight, letterSpacing = letterSpacing, textAlign = textAlign,
    modifier = modifier
)
