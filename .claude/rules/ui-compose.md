---
paths:
  - "**/com/openrs/dash/ui/**"
  - "**/com/openrs/dash/service/HudOverlayService.kt"
  - "**/android/app/src/main/res/**"
---
# openRS_ Known Gotchas — Compose UI (layout, theme, navigation)

- **Theme colours are mode-aware getters** — `Bg`, `Surf`, `Surf2`, `Surf3`, `Brd`, `Dim`, `Mid`, `Frost` in Theme.kt are `val ... get() = when { _isDay -> ...; isUltraNightNow() -> ...; else -> Night... }` backed by `mutableStateOf(ThemeMode)`. They are NOT static vals. Compose snapshot system tracks reads automatically. Call `setThemeMode(ThemeMode)` to change; all composables recompose. Do not cache these colors in non-composable contexts. `setBrightness()`/`getBrightness()` are deprecated no-ops retained for back-compat
- **Haze frosted glass placement** — `hazeSource` MUST be on the content composable (inside Scaffold), NOT on a parent Box that also contains the `hazeEffect` composable (BottomNavBar). If hazeEffect is inside the hazeSource tree, blur fails silently with no error. Also: Haze tint with `Bg.copy(alpha > 0.5f)` on a dark theme makes blur invisible — start at 0.0 and increase
- **Bottom nav bar insets** — `Scaffold(contentWindowInsets = WindowInsets.statusBars)` drops the bottom inset so content extends behind the nav bar overlay. `enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(TRANSPARENT))` removes the system nav bar scrim. Pages add `Tokens.NavBarHeight` as bottom scroll padding
- **Live-only readouts go blank when `!vs.isConnected`** — the last frame stays in VehicleState after a drop, so gear,
  shift LEDs (bar + edge glow), e-brake and "LC" on the header and DRIVE, GARAGE's "LC", DRIVE throttle/brake/AWD/G/torque,
  the HUD overlay and Custom Dashboard cells all gate on connection. Fuel / battery / temps keep last value (THERMAL marks
  them STALE). Any new live readout needs the same gate
- **Don't animate fast values with `AnimatedContent`** — a crossfade keyed on the value restarts on every CAN frame and
  never becomes visible; keyed on placeholder-ness it freezes the container width and clips digits. `DataCell` fades only
  on a placeholder ↔ value switch (`Animatable` alpha)
- **Provide `legibleAccent()` as `LocalThemeAccent`** — raw Frozen White vanishes on DAY and Shadow Black on NIGHT/ULTRA;
  the structural `AccentTriad.full` replaces them. Only paint swatches (`rsPaintAccent`) show the raw paint
- **Amber text uses `WarnInk`** — the text primitives in `Theme.kt` route DAY `Warn` through `textColor()`; raw Material
  `Text`/`TextStyle` must use `WarnInk` or `textColor(...)` explicitly. Fills/bars keep `Warn`
- **Colour = state on DRIVE / THERMAL (design pass)** — readings render in ink, the paint accent marks live/brand
  elements (BOOST value, sparklines, throttle, selected chips), and Warn / Orange appear only when a reading crosses
  an existing limit (`tempLevel()` with the preset warn/crit, `isTireLow` / `tireWarnPsi`, fuel < 10 %, knock counter,
  `isShiftPoint`). There is no battery-voltage limit — don't colour it. ThresholdBar's caution / redline zones are
  scale markings (like a tach redline), kept faint and always visible (owner wants them as reference points); the fill
  and value carry the state. Warnings lead DRIVE via StatusLine — temperatures through the readiness verdict, then tyre → fuel → knock
  (CRIT before WARN; the VEHICLE panel keeps the instant oil → coolant → PTU → RDU order). THERMAL trims padding/gaps (not reading sizes) when the page viewport is < 760 dp tall
  (`LocalThermalDensity`)
- **E-brake / launch control never insert space** — shown as header chips in the wordmark's fixed slot (stacked
  when both are on; launch control is the cluster's own "LC", outlined in the paint accent, no state word) and inside DRIVE's
  fixed-height gear module (tag + caption + border); AnomalyStrip carries only update progress. Live flags only
  (`vs.isConnected`). Every surface shows only "LC", from `data/LaunchControl.kt`
  (`LaunchControl.shown`, #289); 0x225 and the 0x731 probe DIDs (`lcArmed`, `lcRpmTarget`, `assEnabled`) are diagnostics only
- **Opening presentation** — system splash = background colour only (`splash_blank`). The composition draws alone at
  full opacity (no fade-in); the splash exit listener completes `OpeningState.splashGone`, and only then does the
  dashboard compose underneath (`composeDashboard`) and `CanDataService` bind — composing / binding earlier blocked the
  splash hand-off by up to 1.5 s. Hold 2 s from the first visible frame, 300 ms fade; once per **app task**
  (`OpeningState.shouldShow`, #213): cold launch, Force stop, or a new task after Recents dismissal (even if the process
  survived) — never Home / resume, rotation, tab changes or service rebinds. Back is the system's: task moved to the
  back → no replay; task removed (seen on the Android 16 emulator) → next launch is a new task → replay. `splashGone` is
  a fresh deferred per opening.
  Keep heavy work out of `onCreate` paths (PID catalog loads on IO). Debug timing: `adb logcat -s openRS_Startup`
  (ms since process start, `ui/StartupTrace.kt`)
- **Connection state never inserts space** — RECONNECTING / TAP TO CONNECT render inside DRIVE's fixed 48 dp
  StatusLine (`LinkState`), and the header pill (RCN / OFF) covers the other tabs. `AnomalyStrip` is only for
  update progress — don't add connection states back to it (they moved every page)
- **Scrolling pages pad with `pageBottomPadding()`** — the bottom nav overlay is NavBarHeight + the system gesture
  inset tall and content runs behind it; padding only `Tokens.NavBarHeight` hid the last ~24 dp (DRIVE ECONOMY). New
  scrolling pages must use `pageBottomPadding()` (or `navBarClearance()` without the page margin)
- **DRIVE layout never moves with motion** — the gear module height comes only from the page viewport
  (`(viewportH × 0.22).coerceIn(124, 176) dp`, digit 84 sp / 72 sp under 680 dp); no auto-zoom, no gear pop/scale (the
  old `driveAutoZoom` setting is removed). Only data availability (ECONOMY, recording strip, VEHICLE alert chips)
  may add rows below the instruments; the StatusLine is permanent so alerts only change its content. Verify by
  comparing card edges across `at:2.5 / 9.5 / 23` frames. A line under one hero value moves the whole page (the rejected
  "THROTTLE INLET" line made the row ≈ 24 dp taller); the plain BOOST label costs 0 dp
- **Header is a priority layout (#224)** — `HeaderRow` measures wordmark, MODE/ESC pills, link pill and cog first; REC gets
  the rest and shows the longest of "REC time · distance" / "REC time" / "REC" / dot that fits (never cut mid-value).
  Don't put header chips back in a plain Row
- **Side system bars (#231)** — the root Box pads `WindowInsets.safeDrawing.only(Horizontal)`; never a fixed
  margin (portrait gesture phones get 0). Settings / BLE picker are separate dialog windows with the default
  `decorFitsSystemWindows` — Android insets them (emulator only: both rotations + split screen). Rotating portrait ↔
  landscape closes Settings (state not saved). Whether a head unit's sidebar is a reported system bar is unknown
- **Snackbars go above the bottom nav and the Custom Dashboard (#268)** — the `SnackbarHost` is the LAST child of
  MainActivity's root Box, not the Scaffold's `snackbarHost`: `BottomNavBar` and the Custom Dashboard overlay are drawn
  after the Scaffold, so a host inside it (even padded by `navBarClearance()`) sits under them — invisible, its action
  untappable (NOT CONFIRMED, Connection changed, Busy, ESC results). Bottom padding = `navBarClearance()` on the pages,
  the system navigation-bar inset over the dashboard. Settings is its own dialog window and still sits over a message.
  A UI text dump reports a covered snackbar with bounds, so "shown" in a dump is not "visible" — a visibility claim
  needs a screenshot
- **Header line count is fixed per layout (#283)** — `HeaderRow` decides one or two lines from the width, density, font scale
  and demo mode only: the worst-case MODE / ESC pills and link pill that can co-occur (compact, measured, never placed) plus
  the wordmark, the 44 dp cog and the 7 dp REC dot. Never feed live mode, ESC, REC or link state into that decision: a header
  that changes height moves every page. The measured-only copies must stay out of the merged semantics tree, so TalkBack reads
  each pill once (`EscHeaderPillTest` walks the merged tree; `HeaderFitMatrixTest` sweeps link and mode at the switch points).
