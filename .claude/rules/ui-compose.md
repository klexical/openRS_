---
paths:
  - "**/com/openrs/dash/ui/**"
  - "**/com/openrs/dash/service/HudOverlayService.kt"
  - "**/android/app/src/main/res/**"
---
# openRS_ Known Gotchas — Compose UI (layout, theme, navigation)

- **Theme colours are mode-aware getters** — `Bg`, `Surf`, `Surf2`, `Surf3`, `Brd`, `Dim`, `Mid`, `Frost` in Theme.kt
  are getters over the current `ThemeMode` (snapshot state), NOT static vals: read them in composition and never cache
  them in non-composable contexts. Change the mode with `setThemeMode(ThemeMode)`; `setBrightness()` is a deprecated
  no-op. The palettes, paint themes and AUTO's hours are in `Theme.kt`; the settings defaults in `AppSettings.kt`
- **Haze frosted glass placement** — `hazeSource` MUST be on the content composable (inside Scaffold), NOT on a parent Box that also contains the `hazeEffect` composable (BottomNavBar). If hazeEffect is inside the hazeSource tree, blur fails silently with no error. Also: Haze tint with `Bg.copy(alpha > 0.5f)` on a dark theme makes blur invisible — start at 0.0 and increase
- **Bottom nav bar insets and page padding** — `Scaffold(contentWindowInsets = WindowInsets.statusBars)` lets content
  run behind the nav bar overlay, which is NavBarHeight + the system gesture inset tall. Scrolling pages pad with
  `pageBottomPadding()` (or `navBarClearance()` without the page margin): padding only `Tokens.NavBarHeight` hid the last
  ~24 dp
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
  (CRIT before WARN; the VEHICLE panel keeps the instant oil → coolant → PTU → RDU order)
- **Nothing moves the page** — e-brake and launch control show as header chips in the wordmark's fixed slot and inside
  DRIVE's fixed-height gear module; RECONNECTING / TAP TO CONNECT render inside DRIVE's fixed 48 dp StatusLine and the
  header pill covers the other tabs; `AnomalyStrip` carries only update progress (connection states there moved every
  page). DRIVE's gear module height comes only from the viewport, with no auto-zoom or gear pop; only data availability
  (ECONOMY, the recording strip, VEHICLE alert chips) may add rows below the instruments, and a line under one hero value
  moves the whole page (the rejected "THROTTLE INLET" line). Verify by comparing card edges across demo `at:` frames
- **Opening presentation (#213)** — the dashboard composes and `CanDataService` binds only after the system splash has
  gone (`OpeningState.splashGone`): composing or binding earlier blocked the splash hand-off by up to 1.5 s. It plays
  once per app task (`OpeningState.shouldShow`): cold launch, Force stop, or a new task after Recents dismissal — never
  Home / resume, rotation, tab changes or service rebinds. Keep heavy work out of `onCreate` paths. Debug timing:
  `adb logcat -s openRS_Startup`
- **Header is a priority layout (#224)** — `HeaderRow` measures wordmark, MODE/ESC pills, link pill and cog first; REC gets
  the rest and shows the longest form that fits (never cut mid-value). Don't put header chips back in a plain Row
- **Header line count is fixed per layout (#283)** — `HeaderRow` decides one or two lines from the width, density, font scale
  and demo mode only: the worst-case MODE / ESC pills and link pill that can co-occur (compact, measured, never placed) plus
  the wordmark, the 44 dp cog and the 7 dp REC dot. Never feed live mode, ESC, REC or link state into that decision: a header
  that changes height moves every page. The measured-only copies must stay out of the merged semantics tree, so TalkBack reads
  each pill once (`EscHeaderPillTest` walks the merged tree; `HeaderFitMatrixTest` sweeps link and mode at the switch points).
- **Side system bars (#231)** — the root Box pads `WindowInsets.safeDrawing.only(Horizontal)`; never a fixed margin.
  Settings and the BLE picker are dialog windows that Android insets (emulator only). Rotating closes Settings (state not
  saved). Whether a head unit's sidebar is a reported system bar is unknown
- **Snackbars go above the bottom nav and the Custom Dashboard (#268)** — the `SnackbarHost` is the LAST child of
  MainActivity's root Box, not the Scaffold's `snackbarHost`: `BottomNavBar` and the Custom Dashboard overlay are drawn
  after the Scaffold, so a host inside it sits under them — invisible, its action untappable. A UI text dump reports a
  covered snackbar with bounds, so "shown" in a dump is not "visible" — a visibility claim needs a screenshot
- **Screenshot goldens** — `MainScreensScreenshotTest` (`android/app/src/testDebug/`) fails on any changed pixel of the
  main screens against `src/testDebug/screenshots/*.png` (diffs in `app/build/outputs/roborazzi/`). A change that moves
  one of those screens (a merge from staging included) re-records on Linux only — `./gradlew recordRoborazziDebug --tests
  '*MainScreensScreenshotTest*'`; macOS renders other pixels — looks at every changed PNG and commits them with the
  change. A PNG in a pull request needs the privacy scan's reviewer OK (`privacy-ok-compressed`). Goldens hold demo or
  synthetic data only: never a real drive, VIN, location or device name
