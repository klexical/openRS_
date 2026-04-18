package com.openrs.dash.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import com.openrs.dash.R
import java.util.Calendar

// ═══════════════════════════════════════════════════════════════════════════
// DESIGN SYSTEM — v3.0 "Daylight" theme architecture
// ═══════════════════════════════════════════════════════════════════════════
// Two hand-tuned palettes (NIGHT / DAY) + AUTO clock-based switch.
// Replaces the v2.x brightness-lerp system (Night → Sun interpolation).
// Colour roles are @Composable getters — Compose snapshot system tracks reads
// and recomposes all consumers when `setThemeMode()` is called.

enum class ThemeMode { NIGHT, DAY, AUTO, ULTRA }

private val _themeMode = mutableStateOf(ThemeMode.NIGHT)
fun setThemeMode(mode: ThemeMode) { _themeMode.value = mode }
fun getThemeMode(): ThemeMode = _themeMode.value

/** Resolves AUTO → concrete DAY/NIGHT using the device clock (06:00-19:00 → DAY). */
@Composable
fun effectiveThemeIsDay(): Boolean = isDayModeNow()

/** Non-composable accessor — safe to call from draw phases that track snapshot reads. */
fun isDayModeNow(): Boolean = when (_themeMode.value) {
    ThemeMode.DAY -> true
    ThemeMode.NIGHT, ThemeMode.ULTRA -> false
    ThemeMode.AUTO -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 6..18
}

/** True when the theme is AMOLED-black variant — surfaces clamp to pure black. */
fun isUltraNightNow(): Boolean = _themeMode.value == ThemeMode.ULTRA

/**
 * Theme-aware border alpha scaler. Borders tuned for the dark NIGHT palette
 * render near-invisible on the white DAY surface, so we roughly double the
 * alpha in DAY mode. Floor of 0.25 keeps any border legible regardless.
 */
fun borderAlpha(base: Float): Float =
    if (isDayModeNow()) (base * 2f).coerceAtMost(0.85f).coerceAtLeast(0.25f) else base

/**
 * Theme-aware muted-text alpha. Low-alpha Dim labels (placeholders, axis
 * ticks, disabled hints) stay legible on the dark NIGHT palette but drop
 * below WCAG contrast against the white DAY surface. Floor higher in DAY.
 */
fun textMutedAlpha(base: Float): Float =
    if (isDayModeNow()) base.coerceAtLeast(0.55f) else base

/**
 * Theme-aware status-pill background alpha. A translucent color fill at 0.08-0.14
 * reads fine on NIGHT (dark) surfaces but disappears on DAY (white) surfaces,
 * so the RECONNECTING / IDLE / CONN pills fail AA in sunlight. Bumps the bg
 * alpha ~2.5x on DAY while leaving NIGHT untouched.
 */
fun pillBgAlpha(base: Float): Float =
    if (isDayModeNow()) (base * 2.5f).coerceAtMost(0.45f) else base

// ── Deprecated brightness API (kept as no-ops for back-compat) ─────────────
// Old brightness slider is gone; mode is discrete. Retained so existing
// callers don't need to change in one go.
@Deprecated("Use setThemeMode() instead", ReplaceWith("setThemeMode(ThemeMode.NIGHT)"))
fun setBrightness(@Suppress("UNUSED_PARAMETER") v: Float) { /* no-op */ }
@Deprecated("Brightness is gone; use getThemeMode()", ReplaceWith("0f"))
fun getBrightness(): Float = 0f

// ── NIGHT palette (current, largely unchanged) ─────────────────────────────
private val NightBg    = Color(0xFF05070A)
private val NightSurf  = Color(0xFF0A0D12)
private val NightSurf2 = Color(0xFF0F141C)
private val NightSurf3 = Color(0xFF141B26)
private val NightBrd   = Color(0xFF162030)
private val NightDim   = Color(0xFF7A9AB8)
private val NightMid   = Color(0xFFB0D0E8)
private val NightInk   = Color(0xFFE8F4FF)

// ── DAY palette (cooler, blue-leaning for legibility across all 6 paints) ──
// rc.2 pass: the previous warmer neutrals clashed with Race Red / Deep Orange
// (muddy) and flattened the cooler paints. Shifted ~4-6° toward blue.
private val DayBg    = Color(0xFFEFF3F8)
private val DaySurf  = Color(0xFFFFFFFF)
private val DaySurf2 = Color(0xFFE4EAF2)
private val DaySurf3 = Color(0xFFD4DCE8)
private val DayBrd   = Color(0xFFB8C4D4)
private val DayDim   = Color(0xFF556374)
private val DayMid   = Color(0xFF293748)
private val DayInk   = Color(0xFF080D16)

// ── Effective colour roles (mode-aware getters) ────────────────────────────
// These are composable-context getters. Compose snapshot reads track them.
private val _isDay: Boolean
    get() = isDayModeNow()

// ── ULTRA palette — pure-black AMOLED variant of NIGHT ────────────────────
private val UltraBg    = Color(0xFF000000)
private val UltraSurf  = Color(0xFF050505)
private val UltraSurf2 = Color(0xFF080808)
private val UltraSurf3 = Color(0xFF0C0C0C)
private val UltraBrd   = Color(0xFF1A2230)

val Bg:    Color get() = when { _isDay -> DayBg;    isUltraNightNow() -> UltraBg;    else -> NightBg }
val Surf:  Color get() = when { _isDay -> DaySurf;  isUltraNightNow() -> UltraSurf;  else -> NightSurf }
val Surf2: Color get() = when { _isDay -> DaySurf2; isUltraNightNow() -> UltraSurf2; else -> NightSurf2 }
val Surf3: Color get() = when { _isDay -> DaySurf3; isUltraNightNow() -> UltraSurf3; else -> NightSurf3 }
val Brd:   Color get() = when { _isDay -> DayBrd;   isUltraNightNow() -> UltraBrd;   else -> NightBrd }
val Dim:   Color get() = if (_isDay) DayDim   else NightDim
val Mid:   Color get() = if (_isDay) DayMid   else NightMid

/** Primary text — adaptive. Legacy name retained for ~200 call sites. */
val Frost: Color get() = if (_isDay) DayInk else NightInk

// ── Elevated surfaces and on-accent contrast ───────────────────────────────
val SurfUp:   Color get() = when { _isDay -> Color(0xFFFFFFFF); isUltraNightNow() -> Color(0xFF0A0A0A); else -> Color(0xFF141414) }
val OnAccent: Color get() = if (_isDay) Color(0xFFFFFFFF) else Color(0xFF0A0A0A)

// ── Accent + semantic colours (mode-aware where contrast matters) ─────────
val Accent:  Color get() = if (_isDay) Color(0xFF0064B0) else Color(0xFF0091EA)
val AccentD: Color get() = if (_isDay) Color(0xFF004F8A) else Color(0xFF006DB3)
val Orange:  Color get() = if (_isDay) Color(0xFFD93900) else Color(0xFFFF4D00)
val Ok:      Color get() = if (_isDay) Color(0xFF00A85A) else Color(0xFF00FF88)
val Warn:    Color get() = if (_isDay) Color(0xFFE09500) else Color(0xFFFFCC00)

val Grn   get() = Ok
val Amber get() = Warn

// ── RS MK3 paint theme palette (accent override only) ──────────────────────
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

fun rsPaintAccent(id: String): Color = paintMap[id]?.accent ?: paintMap["cyan"]!!.accent
fun rsPaintName(id: String): String  = paintMap[id]?.name  ?: "Nitrous Blue"

val LocalThemeAccent = staticCompositionLocalOf { Color(0xFF0091EA) }

// ═══════════════════════════════════════════════════════════════════════════
// ACCENT TIERS — rc.2 hierarchy system
// ═══════════════════════════════════════════════════════════════════════════
// Every previous "Accent" usage was binary: either the full paint color or
// nothing. That flattens the UI — a section label and a disconnection warning
// shout with equal volume. We split the accent into three tiers so page
// authors can pick intent:
//
//   accentFull()  = the paint, full saturation. Reserve for peaks, warnings,
//                   incrementing hero values, and primary CTAs.
//   accentMid()   = active state, selected chip, active-tab underline.
//   accentDim()   = borders, section label dividers, inactive rails.
//
// Two paints need *structural* overrides (alpha-derivation fails): Shadow
// Black on NIGHT disappears into the dark surface; Frozen White on DAY
// disappears into the white surface. Other paints derive via alpha.

data class AccentTriad(val full: Color, val mid: Color, val dim: Color)

private val ShadowBlackNightTriad = AccentTriad(
    full = Color(0xFF8A92A0),   // lifted gray-blue so it reads on dark surf
    mid  = Color(0xFF5E6774),
    dim  = Color(0xFF3A424E),
)

private val FrozenWhiteDayTriad = AccentTriad(
    full = Color(0xFF4A5464),   // darkened cool gray so it reads on white surf
    mid  = Color(0xFF6E7886),
    dim  = Color(0xFF95A0B0),
)

private val paintByAccent: Map<Color, RsPaint> = RsPaints.associateBy { it.accent }

/** Derive the accent triad for a given paint color + theme mode. */
fun accentTriadFor(paint: Color, isDay: Boolean): AccentTriad {
    val id = paintByAccent[paint]?.id
    return when {
        id == "black" && !isDay -> ShadowBlackNightTriad
        id == "white" && isDay  -> FrozenWhiteDayTriad
        isDay -> AccentTriad(
            full = paint,
            mid  = paint.copy(alpha = 0.72f),
            dim  = paint.copy(alpha = 0.38f),
        )
        else -> AccentTriad(
            full = paint,
            mid  = paint.copy(alpha = 0.62f),
            dim  = paint.copy(alpha = 0.28f),
        )
    }
}

/** Full-saturation accent. Same as `LocalThemeAccent.current` — kept for symmetry. */
@Composable fun accentFull(): Color = LocalThemeAccent.current

/** Mid-tier accent: active state, selected chip, active-tab underline. */
@Composable fun accentMid(): Color = accentTriadFor(LocalThemeAccent.current, isDayModeNow()).mid

/** Dim-tier accent: borders, section dividers, inactive rails. */
@Composable fun accentDim(): Color = accentTriadFor(LocalThemeAccent.current, isDayModeNow()).dim

// ── OEM polish helpers (rc.2) ─────────────────────────────────────────────

/** Per-tab identity accent for bottom nav. Gated behind prefs.navTabIdentity. */
@Composable fun tabIdentityColor(tabIndex: Int): Color = when (tabIndex) {
    0    -> accentMid()       // DRIVE  (paint accent)
    1    -> accentFull()      // PERF   (brighter paint — avoids Orange ESC-OFF conflict)
    2    -> Warn              // THERMAL
    3    -> Ok                // TRIP
    4    -> Mid               // GARAGE
    else -> accentMid()
}

/**
 * Card-edge specular tint for cardGlow v2. Returns a mode-aware tint:
 * Night = cool-blue accent edge, Day = white machined-aluminum sheen,
 * Ultra = slightly stronger cool-blue on pure-black surfaces.
 * Uses the global [Accent] getter (snapshot-tracked) — safe from non-composable draw phases.
 */
fun cardSpecularTint(): Color = when {
    isDayModeNow()    -> Color.White.copy(alpha = 0.35f)
    isUltraNightNow() -> Accent.copy(alpha = 0.08f)
    else              -> Accent.copy(alpha = 0.06f)
}

// ═══════════════════════════════════════════════════════════════════════════
// FONTS
// ═══════════════════════════════════════════════════════════════════════════

/** Rajdhani — new primary numeric family (rounder zero, clearer 1/l/I). */
val RajdhaniFamily = FontFamily(
    Font(R.font.rajdhani_medium,   FontWeight.Medium),
    Font(R.font.rajdhani_semibold, FontWeight.SemiBold),
    Font(R.font.rajdhani_bold,     FontWeight.Bold),
)

/** Orbitron — retired from defaults, retained for optional "classic" toggle. */
val OrbitronFamily = FontFamily(
    Font(R.font.orbitron_regular, FontWeight.Normal),
    Font(R.font.orbitron_bold,    FontWeight.Bold),
)

val JetBrainsMonoFamily = FontFamily(
    Font(R.font.jetbrains_mono_regular, FontWeight.Normal),
    Font(R.font.jetbrains_mono_bold,    FontWeight.Bold),
)

// Typography retirement: Share Tech Mono + Barlow Condensed TTFs removed
// in the "Daylight" pass. These aliases retain the original Kotlin symbols
// so external call sites compile and render in the unified voice
// (JetBrains Mono + Rajdhani).
val ShareTechMono = JetBrainsMonoFamily
val BarlowCond    = RajdhaniFamily

// ═══════════════════════════════════════════════════════════════════════════
// TYPOGRAPHY — v3.0 helpers (preferred) + v2.x legacy names (aliased)
// ═══════════════════════════════════════════════════════════════════════════
// Legacy helpers (HeroNum/AggressiveNum/MonoLabel/UIText) retain their
// signatures for back-compat but internally switch to Rajdhani/Barlow.
// New code should prefer DriveNum/DataNum/Label/Body/MonoData.

private val _classicFonts = mutableStateOf(false)
/** Optional "classic" toggle: when true, DriveNum/DataNum fall back to Orbitron. */
fun setClassicFonts(on: Boolean) { _classicFonts.value = on }
fun getClassicFonts(): Boolean = _classicFonts.value

private val numericFamily: FontFamily
    get() = if (_classicFonts.value) OrbitronFamily else RajdhaniFamily

/**
 * Shared TextStyle enabling tabular-figures (`tnum`) on numeric displays —
 * keeps digit widths fixed so fast-changing values (RPM, speed, temps) don't
 * shimmy laterally. Hoisted to avoid per-composition TextStyle allocation.
 */
private val TabularNumericStyle = TextStyle(fontFeatureSettings = "tnum")

/** Hero numerics (48-120 sp): gear, boost, RPM, speed. Rajdhani SemiBold. */
@Composable fun DriveNum(
    text: String,
    fontSize: TextUnit,
    color: Color = Frost,
    modifier: Modifier = Modifier,
) = Text(
    text, fontSize = fontSize, fontFamily = numericFamily, color = color,
    fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
    lineHeight = fontSize * 1.0f, style = TabularNumericStyle, modifier = modifier
)

/** Cell numerics (14-24 sp): temps, trims, AFR. Rajdhani Medium. */
@Composable fun DataNum(
    text: String,
    fontSize: TextUnit,
    color: Color = Frost,
    modifier: Modifier = Modifier,
) = Text(
    text, fontSize = fontSize, fontFamily = numericFamily, color = color,
    fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
    letterSpacing = 0.2.sp, lineHeight = fontSize * 1.1f,
    style = TabularNumericStyle, modifier = modifier
)

/** Monospace hex / CAN IDs / firmware strings. JetBrains Mono. */
@Composable fun MonoData(
    text: String,
    fontSize: TextUnit,
    color: Color = Frost,
    fontWeight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier,
) = Text(
    text, fontSize = fontSize, fontFamily = JetBrainsMonoFamily, color = color,
    fontWeight = fontWeight, letterSpacing = 0.2.sp,
    style = TabularNumericStyle, modifier = modifier
)

/** Card labels, section headings (UPPERCASE). Barlow Condensed Medium. */
@Composable fun Label(
    text: String,
    fontSize: TextUnit,
    color: Color = Dim,
    fontWeight: FontWeight = FontWeight.Medium,
    letterSpacing: TextUnit = 0.4.sp,
    modifier: Modifier = Modifier,
) = Text(
    text, fontSize = fontSize, fontFamily = BarlowCond, color = color,
    fontWeight = fontWeight, letterSpacing = letterSpacing, modifier = modifier
)

/** Body prose (changelog, dialogs). Barlow Condensed Regular. */
@Composable fun Body(
    text: String,
    fontSize: TextUnit,
    color: Color = Frost,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier,
) = Text(
    text, fontSize = fontSize, fontFamily = BarlowCond, color = color,
    fontWeight = fontWeight, textAlign = textAlign, modifier = modifier
)

// ── Legacy aliases (delegate to v3.0 helpers) ──────────────────────────────

/** Deprecated: use DriveNum. */
@Composable fun HeroNum(
    text: String,
    fontSize: TextUnit,
    color: Color = Frost,
    modifier: Modifier = Modifier,
) = DriveNum(text, fontSize, color, modifier)

/** Deprecated: use DataNum. */
@Composable fun AggressiveNum(
    text: String,
    fontSize: TextUnit,
    color: Color = Frost,
    modifier: Modifier = Modifier,
) = DataNum(text, fontSize, color, modifier)

/** Deprecated: use MonoData. */
@Composable fun MonoLabel(
    text: String,
    fontSize: TextUnit,
    color: Color = Dim,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = 0.2.sp,
    modifier: Modifier = Modifier,
) = Text(
    text, fontSize = fontSize, fontFamily = JetBrainsMonoFamily, color = color,
    fontWeight = fontWeight, letterSpacing = letterSpacing, modifier = modifier
)

/** Monospace readouts (frame console, raw values) — Share Tech Mono. Unchanged. */
@Composable fun MonoText(
    text: String,
    fontSize: TextUnit,
    color: Color = Frost,
    fontWeight: FontWeight = FontWeight.Normal,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier,
) = Text(
    text, fontSize = fontSize, fontFamily = ShareTechMono, color = color,
    fontWeight = fontWeight, textAlign = textAlign, modifier = modifier
)

/** Deprecated: use Body. */
@Composable fun UIText(
    text: String,
    fontSize: TextUnit,
    color: Color = Frost,
    fontWeight: FontWeight = FontWeight.Normal,
    letterSpacing: TextUnit = 0.sp,
    textAlign: TextAlign = TextAlign.Start,
    modifier: Modifier = Modifier,
) = Text(
    text, fontSize = fontSize, fontFamily = BarlowCond, color = color,
    fontWeight = fontWeight, letterSpacing = letterSpacing, textAlign = textAlign,
    modifier = modifier
)

// ═══════════════════════════════════════════════════════════════════════════
// RESPONSIVE LAYOUT HELPER
// ═══════════════════════════════════════════════════════════════════════════

@Composable fun isWideLayout(): Boolean {
    val config = LocalConfiguration.current
    return config.orientation == Configuration.ORIENTATION_LANDSCAPE || config.screenWidthDp > 600
}
