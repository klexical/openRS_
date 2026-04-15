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

// ── DAY palette (new — high contrast for sunlight) ─────────────────────────
private val DayBg    = Color(0xFFF4F6F9)
private val DaySurf  = Color(0xFFFFFFFF)
private val DaySurf2 = Color(0xFFEAEEF3)
private val DaySurf3 = Color(0xFFDDE3EC)
private val DayBrd   = Color(0xFFC5CFDB)
private val DayDim   = Color(0xFF5C6B7E)
private val DayMid   = Color(0xFF2E3A4B)
private val DayInk   = Color(0xFF0A0F18)

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
val ShareTechMono = FontFamily(Font(R.font.share_tech_mono, FontWeight.Normal))
val BarlowCond    = FontFamily(
    Font(R.font.barlow_condensed_regular,  FontWeight.Normal),
    Font(R.font.barlow_condensed_medium,   FontWeight.Medium),
    Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    Font(R.font.barlow_condensed_bold,     FontWeight.Bold),
)

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
