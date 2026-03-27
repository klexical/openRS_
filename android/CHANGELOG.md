# Changelog

All notable changes to the openRS_ Android app are documented here.
Firmware changes are tracked separately in [firmware releases](https://github.com/klexical/openRS_/releases).

> **Note on tab names:** The app's tab structure has evolved over time. v1.0–v1.1 used DASH/PERF/TEMPS/TUNE/TPMS/CTRL/DIAG. v1.2.0 redesigned to DASH/POWER/CHASSIS/TEMPS/DIAG + System Drawer. v2.0.0 replaced the drawer with a MORE tab, giving the current 6-tab layout: DASH/POWER/CHASSIS/TEMPS/DIAG/MORE. Historical entries below use the tab names that were current at the time of each release.

---

## [v2.2.5] — 2026-03-27

### Added (rc.1 — FORScan PID catalog, data-driven decode, DID prober)
- **FORScan PID catalog integrated as JSON asset** — 1,149 PIDs across 8 ECU modules (PCM, OBDII, BCM, ABS, AWD, HVAC, IPC, PSCM) loaded from `forscan_modules.json` at runtime via `ForscanCatalog.kt`. Generator script at `android/scripts/gen_forscan_catalog.py`.
- **PID Browser on DIAG tab** — expandable section listing all 1,149 FORScan PIDs grouped by ECU module with coverage mini-bars, CAN ID badges, and aggregate stats.
- **DID Prober on DIAG tab** — interactive Mode 22 scanner that probes candidate DID ranges on any ECU. Classifies each DID as FOUND / NRC / TIMEOUT with progress bar and hex response data. Requires active adapter connection.
- **Data-driven PID decode system (PidRegistry)** — `PidRegistry.kt` loads the FORScan catalog at startup, decodes responses via formula evaluation, and stores results in `VehicleState.genericValues`. New PIDs can be added via JSON without modifying Kotlin code.
- **Per-cylinder knock correction (cylinders 2–4)** — PCM now polls DIDs 0x03ED–0x03EF alongside existing cylinder 1 (0x03EC). Displayed on POWER tab in the knock correction section.
- **AWD module expansion — 7 new PIDs** — clutch temp L/R (0x1E8B/0x1E8C), requested torque L/R (0x1E90/0x1E91), demanded pressure (0x1E92), pump motor current (0x1E93), transmission oil temp (0x1E80). Shown on CHASSIS and TEMPS tabs.
- **HVAC module scaffolding** — response handler for ECU 0x733→0x73B with 6 new `VehicleState` fields. ECU address is a candidate — use the DID prober to confirm.
- **IPC warning lamps scaffolding** — response handler for ECU 0x720→0x728 with 6 warning lamp fields. Summary banner on DASH tab shows active warnings when populated. ECU address is a candidate — use the DID prober to confirm.
- **`buildSlcanQuery()` helper in ObdConstants** — generates Mode 22 SLCAN query frames for any ECU request ID + DID, replacing hardcoded frame strings.

### Fixed (rc.1)
- **PidRegistry never initialized** — `PidRegistry.ensureLoaded()` was never called, so all data-driven PID decoding was silently non-functional. Added call in `CanDataService.onCreate()`.
- **`mergeObdState` dropped `genericValues`** — PidRegistry-decoded values were computed but silently lost during state merge. Now merges correctly.
- **`mergeObdState` missing HVAC and IPC fields** — all 12 new fields were parsed but dropped during state merge.
- **Dead code removed** — `DidProber.kt` was never referenced; deleted. `DidProberSection.kt` inlines all probing logic.
- **`MeatPiConnection.readSlcanLine` could hang indefinitely** — continuous bytes without a `\r` terminator caused an infinite loop. Added a 32-byte max-line guard. ([#127](https://github.com/klexical/openRS_/issues/127))
- **`IsoTpBuffer` CF sequence numbers not validated** — duplicate or out-of-order Consecutive Frames silently corrupted TPMS readings. Sequence tracking added. ([#128](https://github.com/klexical/openRS_/issues/128))
- **ISO-TP First Frame `firstBytes` not capped by `totalLen`** — bytes to copy from FF ignored `totalLen`. Changed to `minOf(6, totalLen, data.size - 2)`. ([#129](https://github.com/klexical/openRS_/issues/129))

### Changed (rc.1)
- **Release checklist and emulator workflow rules updated** — browser emulator updates deferred to stable releases only, skipped for staging/RC builds.

### Fixed (rc.2)
- **DtcScanner exceptions now caught at service layer** — mid-scan adapter disconnect propagated uncaught to callers. `scanDtcs()` now logs and rethrows; `clearDtcs()` returns `emptyMap()`. (addresses [#83](https://github.com/klexical/openRS_/issues/83))
- **DTC results blank during dismiss animation** — `AnimatedVisibility` exit rendered blank content because `results` was already null. Fixed with a `remember`-backed `lastResults` variable. (addresses [#81](https://github.com/klexical/openRS_/issues/81))
- **Race-ready RTR check inconsistent between TEMPS banner and TripRecorder** — `UserPrefs.isRaceReady()` used different thresholds than `VehicleState.rtrStatus`. Removed `isRaceReady()`; `TripRecorder` now uses `vs.isReadyToRace` — one truth source. (addresses [#81](https://github.com/klexical/openRS_/issues/81))
- **`AppSettings.save()` vs `saveAll()` undocumented field split** — `save()` persists host/port, `saveAll()` persists everything else. The intentional split was undocumented. Both methods now have KDoc explaining the separation. (addresses [#83](https://github.com/klexical/openRS_/issues/83))

### Fixed (rc.3 — code review umbrella cleanup)
- **Misleading `Red` color alias removed** — `val Red get() = Orange` in `Theme.kt` returned orange, not red. Alias removed; 35 call sites now reference `Orange` directly. (addresses [#82](https://github.com/klexical/openRS_/issues/82))
- **TempsPage RTR dot animation ticked as no-op when race-ready** — animation oscillated between `1f` and `1f` when ready, running every frame with no visual effect. Now gated on warmup state. (addresses [#82](https://github.com/klexical/openRS_/issues/82))
- **MainActivity connection dot animation ran when disconnected** — `rememberInfiniteTransition` ticked continuously regardless of connection state. Now only runs when connected. (addresses [#82](https://github.com/klexical/openRS_/issues/82))
- **Odometer unit toggle reset on tab switch** — `remember { mutableStateOf(false) }` hardcoded km default. Initial value now derives from `prefs.speedUnit == "MPH"`. (addresses [#82](https://github.com/klexical/openRS_/issues/82))
- **UserPrefs.update race with SharedPreferences** — `saveAll()` ran inside the `StateFlow.update` CAS lambda, persisting stale data on retry. Now runs after CAS succeeds. (addresses [#83](https://github.com/klexical/openRS_/issues/83))
- **Redundant `themeAccentColor()` wrapper removed** — `MorePage.kt` one-liner delegating to `rsPaintAccent()`. Call sites now invoke `rsPaintAccent()` directly. (addresses [#81](https://github.com/klexical/openRS_/issues/81))
- **Conversion magic numbers extracted to named constants** — `KM_TO_MI`, `STD_ATM_KPA`, `KPA_TO_PSI`, `PSI_TO_BAR` consolidated into `UnitConversions` object in `UserPrefs.kt`. 10 occurrences replaced across 4 files. (addresses [#82](https://github.com/klexical/openRS_/issues/82))

### Added (rc.4 — visual polish)
- **Neon glow effects on hero values** — soft radial gradient bloom behind Orbitron values in each hero card's accent color. Same treatment on `GfCard` values. Works on all API levels ≥ 28 (no `RenderEffect`).
- **Neon glow on progress bars** — `BarCard` gains optional `barGlowColor` for a soft light bleed above and below the bar. Applied to THROTTLE, BRAKE, FUEL, and BATTERY bars.
- **Connection dot glow ring** — semi-transparent backing circle on the connection status dot, synced to pulse animation. Same treatment on the RTR banner dot.
- **Active tab indicator glow** — vertical gradient glow beneath the accent underline on the selected tab.
- **Animated value transitions** — all DASH tab numeric displays use `animateFloatAsState` with spring physics. Fast values (RPM, speed) use stiff springs; slow values (fuel, battery) use tween. Sentinel values bypass animation.
- **Sparkline mini-charts in HeroCards** — 22dp Canvas sparklines showing ~15s of value history via a 60-sample ring buffer at ~4 Hz. Applied to BOOST, RPM, and SPEED hero cards.
- **Sparkline mini-charts in BarCards** — 28dp sparklines below progress bars. Applied to THROTTLE and BRAKE bars.
- **G-Force dot plot on CHASSIS tab** — 2D visualization (`GForcePlot.kt`) with concentric G rings, live dot with glow halo, and a 30-sample comet trail. Replaces the six `GfCard` text boxes while preserving all numeric readouts.
- **RPM shift light bar on DASH tab** — 18 LED-style segments (`ShiftLightBar.kt`) filling left-to-right by RPM: green 0–4000, yellow 4000–5500, red 5500–6800. All segments flash at redline (≥6500 RPM).
- **Shared animation utilities** (`ui/anim/` subpackage) — `RingBuffer.kt` (generic ring buffer), `GlowModifiers.kt` (radial and rectangular glow modifiers), `Sparkline.kt` (sparkline data + Canvas composable).

### Fixed (rc.4 — visual polish)
- **GForcePlot Paint objects allocated every frame** — `textPaint` and `axisPaint` were created inside the Canvas lambda, producing ~120 allocations/sec at 60 Hz. Extracted to `remember(density)` outside the draw block.
- **Odometer mi/km toggle lost on tab switch** — the rc.3 fix improved the initial value but the toggle still used ephemeral `remember` state destroyed on recomposition. Now persisted to SharedPreferences via a new `odomInMiles` field in `UserPrefs`/`AppSettings`. Survives tab switches and app restarts. (addresses [#82](https://github.com/klexical/openRS_/issues/82))
- **Drive mode commands silently dropped with HTTP 200** — firmware returned `{"ok":true}` even when `s_pending_mode` rejected a command, so the app showed success when nothing happened. `FirmwareApi.kt` now parses the response body for `"busy":true` and shows "Mode change in progress — please wait". After a successful command, the app watches `VehicleState.driveMode` for up to 5 seconds and warns if the mode didn't take effect.

### Added (rc.4 — visual polish)
- **RingBuffer unit tests** — 9 test cases covering empty state, insertion order, capacity wraparound, clear/re-push, and generic type support.

### Added (rc.5 — Mission Control dashboard)
- **Mission Control HTML dashboard in exports** — trip and diagnostic ZIP exports now include a self-contained `mission_control_<ts>.html` file. Open in any browser for an interactive post-session analysis dashboard with no network or dependencies required. `MissionControlHtmlBuilder.kt` reads the HTML template from `res/raw`, inlines uPlot CSS/JS from assets, and serializes trip + diagnostic data into embedded JSON.
- **Trip dashboard tab** — 8 summary cards (distance, duration, fuel, avg speed, peak RPM/boost/lat G, efficiency), 7 cursor-synced uPlot time-series charts (RPM, boost, speed, temperatures, lateral G, fuel, wheel speeds), SVG Mercator GPS map with speed/drive-mode coloring and peak event markers, stats panel with drive mode breakdown bar.
- **Diagnostics dashboard tab** — sortable CAN frame inventory table with expandable detail rows (first/last hex, periodic samples, validation issues), FPS timeline chart, session events table with type badges, filterable decode trace table (last 500 of up to 10,000 entries).
- **File loader tab** — drag-and-drop or browse to load additional `trip_*.csv` or `diagnostic_detail_*.json` files from other sessions into the same viewer. CSV parser computes summary stats (distance, fuel economy, peaks, mode breakdown) on the fly.

### Fixed (rc.5)
- **Sport/Track 0x420 bit0 polarity inverted** — `CanDecoder` had bit0=1→Track and bit0=0→Sport, but the actual car button cycle (Normal→Sport→Track→Drift) shows bit0=1 is Sport (0x11CD) and bit0=0 is Track (0x11CC). This caused the app to display the wrong mode and the firmware's closed-loop controller to overshoot by one step (tap Sport → car goes to Track). Swapped polarity in both 0x1B0 and 0x420 handlers. Default `modeDetail420` changed from `0x10C4` to `0x10CD`.
- **Every drive mode command timed out with "Read timed out"** — `FirmwareApi.post()` read the HTTP response body in a `readLine()` loop until EOF, but the ESP-IDF HTTP server doesn't close the connection within the 5s `soTimeout`. Now parses `Content-Length` from headers and reads exactly that many bytes.
- **TPMS displayed −40°C during sensor initialisation** — `ObdResponseParser.parseBcmReassembled()` accepted raw temp byte `0x00` as valid, which decoded to −40°C via the standard offset. Now discards `0x00` as an uninitialised sensor reading. Also removed the `< -40` floor from the range check since the offset formula cannot produce values below −40°C. ([#130](https://github.com/klexical/openRS_/issues/130))
- **BCM 0x280B tire temperature unit tests** — 5 new tests covering valid temp decode, uninitialised `0x00` discard, stale status discard, unknown sensor ID rejection, and short payload handling.

### Fixed (rc.6 — polarity re-correction + probe export)
- **Sport/Track 0x420 polarity re-corrected** — rc.5's polarity fix was still backwards. SLCAN log analysis (2026-03-25 drive session) definitively proves `0x11CC` (bit0=0) = Sport and `0x11CD` (bit0=1) = Track. The app now displays the correct mode. Default `modeDetail420` changed from `0x10CD` to `0x10CC`. Five drive mode unit tests updated to match.
- **Drive mode CAN confirmation timeout bumped to 8s** — real-world SLCAN logs show the mode change CAN frame (`0x420`) arrives ~5.3s after the HTTP command, just exceeding the previous 5s window. Increased to 80 × 100ms = 8s in `MorePage.kt`.

### Added (rc.6)
- **DID probe results included in diagnostic ZIP export** — `DiagnosticLogger` now stores probe sessions; `DidProberSection` logs results after each scan completes. `DiagnosticExporter` writes a "DID PROBE RESULTS" section in the summary text and a `probeResults` array in the JSON detail file. Previously, probe results were ephemeral Compose state lost on navigation.

### Fixed (rc.7 — full release audit)
- **Drive mode confirmation read stale state** — `MorePage` confirmation loop captured `vs` at composition time; inside the coroutine it never saw CAN updates. Now reads `OpenRSDashApp.instance.vehicleState.value` each poll iteration. ([#82](https://github.com/klexical/openRS_/issues/82))
- **DID prober mutated Compose state from IO thread** — `DidProberSection` launched the probe loop on `Dispatchers.IO`, then called `results.add()` on `mutableStateListOf` off the Main thread. Restructured to run the coroutine on Main, wrapping only the network call in `withContext(IO)`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **DiagnosticExporter JSON escaped quotes incorrectly** — five `.replace("\"", "'")` calls in JSON serialization converted double-quotes to single-quotes instead of properly escaping them. Replaced with a `.jsonEscape()` extension that handles `\`, `"`, `\n`, `\r`, `\t`, and `</` (script breakout prevention). ([#82](https://github.com/klexical/openRS_/issues/82))
- **WebSocket 64-bit length path ignored mask bytes** — `WiCanConnection` large-frame path read the 8-byte extended length but skipped the 4-byte mask, causing the payload to be read from the wrong offset and unmasked incorrectly. Now reads and applies the mask per RFC 6455. ([#82](https://github.com/klexical/openRS_/issues/82))
- **DashPage sparkline push during composition** — `boostHistory.push()` was called inside the composable body, a side effect during composition. Moved into a `SideEffect {}` block. ([#82](https://github.com/klexical/openRS_/issues/82))
- **ShiftLightBar infinite animation ran when not at redline** — `rememberInfiniteTransition` ticked continuously regardless of RPM. Now only instantiated when `isRedline` is true. ([#82](https://github.com/klexical/openRS_/issues/82))
- **`intakeTempC` and `ambientTempC` defaulted to 0.0 instead of sentinel** — zero is a valid temperature reading, masking "not yet received" as 0°C/32°F. Changed defaults to `-99.0` matching all other temperature fields. ([#82](https://github.com/klexical/openRS_/issues/82))
- **PidRegistry formula evaluator returned Infinity/NaN** — division by zero in catalog formulas produced `Infinity` which propagated to `genericValues`. Now returns `null` for infinite or NaN results. ([#82](https://github.com/klexical/openRS_/issues/82))
- **RingBuffer accepted capacity=0** — would cause division-by-zero in modular index arithmetic. Added `require(capacity > 0)` in `init`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **Sparkline Path objects allocated every frame** — `Path()` was created inside the `Canvas` lambda (~60 alloc/sec). Hoisted to `remember` with `.reset()` at draw time. ([#82](https://github.com/klexical/openRS_/issues/82))
- **WiCanConnection socket not closed on cancellation** — coroutine cancellation didn't close the TCP socket, leaving it open until GC. Added a `cancelWatcher` coroutine that closes the socket in its `finally` block. ([#82](https://github.com/klexical/openRS_/issues/82))
- **MorePage host didn't update when settings changed** — `AppSettings.getHost()` was called once and cached. Now keyed on `UserPrefs` via `remember(prefs)`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **SLCAN write failures silently swallowed** — `DiagnosticLogger.writeSlcanLine()` caught all exceptions with no logging. Now logs the first write failure as a session event. ([#82](https://github.com/klexical/openRS_/issues/82))
- **Probe sessions had no cap** — `DiagnosticLogger.probeSessionsList` could grow unbounded. Capped at 50 sessions with LRU eviction. ([#82](https://github.com/klexical/openRS_/issues/82))
- **TPMS 0x280B missing pressure alongside temperature** — `ObdResponseParser.parseBcmReassembled()` extracted temperature from the multi-frame payload but ignored the pressure bytes. Now reads pressure from `payload[7..8]` and updates the corresponding tire pressure field. ([#82](https://github.com/klexical/openRS_/issues/82))
- **Flat tire (0.0 PSI) not flagged as low** — `anyTireLow()` range started at `0.01`, excluding exactly 0.0 PSI. Changed to `0.0..<threshold`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **FirmwareApi Content-Length byte count incorrect** — `json.length` (char count) was used instead of `json.toByteArray().size` (byte count), which diverges for non-ASCII characters. ([#82](https://github.com/klexical/openRS_/issues/82))
- **ForscanCatalog load failure silently swallowed** — exception was caught with no logging. Now logs with `Log.e`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **`SlcanParser.parseDataBytes` returned empty array on hex error** — callers couldn't distinguish "no data" from "parse failure". Now returns `null` on error; both callers updated to skip with `?.let`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **Mission Control JSON missing probe results** — `MissionControlHtmlBuilder.buildDiagJson()` didn't include DID probe sessions. Added `probeResults` array. ([#82](https://github.com/klexical/openRS_/issues/82))
- **Odometer CAN ID missing from diagnostic known-IDs set** — `DiagnosticExporter.knownIds` didn't include `ID_ODOMETER`, so odometer frames appeared as "unknown" in the frame inventory. ([#82](https://github.com/klexical/openRS_/issues/82))
- **CanDecoder stale header comment** — top-of-file comments still referenced old pre-rc.6 polarity. Updated to match corrected `bit0=0→Sport (0xCC)`.
- **Deprecated `CarOutlinePlaceholder` alias removed** — dead code in `Components.kt`, replaced by `FocusRsOutline` in rc.4.

### Added (rc.7)
- **IsoTpBuffer unit tests** — 12 tests covering single-frame, multi-frame reassembly, out-of-order CF rejection, sequence wrap, FF reset, and edge cases (`IsoTpBufferTest.kt`).
- **PidRegistry formula evaluator tests** — 16 tests covering arithmetic, `signed()` two's-complement, unary minus, operator precedence, parentheses, and edge cases (`PidRegistryTest.kt`).
- **RingBuffer capacity=0 test** — verifies `IllegalArgumentException` on zero capacity.

### Added (rc.8 — UI/UX overhaul)
- **Swipe tab navigation via HorizontalPager** — replaced raw `when(tab)` with `HorizontalPager` in `MainActivity.kt`. Users can swipe left/right between all 6 tabs. Tab bar syncs bidirectionally with pager state via `animateScrollToPage`. ([#131](https://github.com/klexical/openRS_/issues/131), [#138](https://github.com/klexical/openRS_/issues/138))
- **Animated gear transition** — gear display on DASH tab now uses `AnimatedContent` with `slideInVertically + fadeIn` / `slideOutVertically + fadeOut` for smooth gear change animations at 72sp. ([#132](https://github.com/klexical/openRS_/issues/132))
- **Haptic feedback on interactive controls** — `HapticFeedbackType.Confirm` added to tab selection (`MainActivity.kt`), drive mode and ESC buttons (`MorePage.kt`), temperature preset pills (`TempsPage.kt`), and settings segmented pickers (`SettingsSheet.kt`). ([#133](https://github.com/klexical/openRS_/issues/133))
- **Placeholder pulse animation** — `DataCell` and `AfrCard` in `Components.kt` auto-detect `"— —"` placeholder values and animate alpha 0.3↔0.7 via `rememberInfiniteTransition`. Distinguishes "waiting for data" from "value is zero." ([#135](https://github.com/klexical/openRS_/issues/135))
- **NRC code decoding in DID Prober** — `nrcLabel()` function in `DidProberSection.kt` maps 11 common UDS NRC codes (0x10–0x78) to human-readable descriptions. "NRC 0x31" now shows "request out of range." ([#136](https://github.com/klexical/openRS_/issues/136))
- **Long-press copy DID code in PID Browser** — `combinedClickable` with `onLongClick` in `PidBrowserSection.kt` copies DID hex codes to clipboard with Toast confirmation. ([#137](https://github.com/klexical/openRS_/issues/137))
- **Knock retard flash animation** — `PowerPage.kt` KR C1-C4 cells animate background color via `animateColorAsState` to a warm `Warn` glow (0.08 alpha → transparent over 400ms) when knock correction crosses -1.0°. ([#139](https://github.com/klexical/openRS_/issues/139))
- **Connection status banner** — contextual banner below tab bar shows adapter type, IP:port, and "DISCONNECTED" status with dismiss button. Auto-resets on successful connection. ([#140](https://github.com/klexical/openRS_/issues/140))
- **Temperature session peaks** — 5 new peak fields in `VehicleState` (oil, coolant, RDU, PTU, charge air) tracked via `withPeaksUpdated()`. Displayed as "▲" annotations in `TempsPage.kt`. ([#141](https://github.com/klexical/openRS_/issues/141))
- **DID Prober ETA and export** — ETA display during probe ("~4 min remaining") based on average probe time. EXPORT button copies results as TSV to clipboard. ([#142](https://github.com/klexical/openRS_/issues/142))
- **Settings reset-to-defaults** — RESET button in `SettingsSheet.kt` restores all fields to `AppSettings.DEFAULT_*` values with inline "Defaults restored" confirmation. ([#143](https://github.com/klexical/openRS_/issues/143))
- **Collapsible sections on Power page** — `SectionLabel` extended with `collapsible`/`expanded`/`onToggle` params. Three Power page sections wrapped in `AnimatedVisibility` with expand/collapse chevrons. ([#144](https://github.com/klexical/openRS_/issues/144))
- **Pinned dash metrics strip** — `MiniMetricStrip` composable in `DashPage.kt` appears when scroll position exceeds 220px, showing compact BOOST/RPM/SPEED values for persistent context. ([#145](https://github.com/klexical/openRS_/issues/145))
- **TPMS delta arrows** — `tpmsDeltaText()` helper in `ChassisPage.kt` computes pressure trend vs session start. `TireCard` extended with `deltaText` param showing ▲/▼ arrows color-coded green (rising) or orange (falling). ([#146](https://github.com/klexical/openRS_/issues/146))
- **Landscape and split-screen layout support** — `isWideLayout()` composable helper in `Theme.kt` detects landscape/wide screens. Responsive grids across `DashPage`, `TempsPage`, `ChassisPage`, `TripPage`, and `CustomDashPage`. ([#147](https://github.com/klexical/openRS_/issues/147))
- **Floating HUD overlay** — `HudOverlayService.kt` using `TYPE_APPLICATION_OVERLAY` displays 2-3 key metrics on top of any app. Draggable, close button, collects `vehicleState` flow at 4 Hz. Toggle in `MorePage`. `SYSTEM_ALERT_WINDOW` permission added. ([#148](https://github.com/klexical/openRS_/issues/148))
- **Animated car diagram** — `CarDiagram.kt` Canvas-based top-down car visualization with live tire pressure, wheel speed, and AWD torque distribution overlays. Replaces `FocusRsOutline` in TPMS section of `ChassisPage`. ([#149](https://github.com/klexical/openRS_/issues/149))
- **Session history browser** — Room database (`SessionDatabase.kt`) with `SessionEntity` and `SnapshotEntity`. `CanDataService` records sessions automatically. `SessionHistorySection` in `MorePage` shows expandable session cards with 30-day auto-prune. ([#150](https://github.com/klexical/openRS_/issues/150))
- **Custom dashboard builder** — `DashLayout.kt` data model with 31 available metrics. `CustomDashPage.kt` full-screen overlay with responsive gauge grid and `DashBuilderSheet` editor. Layouts persisted via `AppSettings.saveCustomDash()`/`loadCustomDash()`. ([#151](https://github.com/klexical/openRS_/issues/151))

### Changed (rc.8)
- **Dim label contrast improved** — `Dim` color bumped from `#3D5A72` to `#547A96` in `Theme.kt` for WCAG AA compliance (≥4.5:1 contrast ratio on Surf2 background). ([#134](https://github.com/klexical/openRS_/issues/134))
- **Compose BOM upgraded** — `2024.11.00` → `2025.11.00` (Compose UI 1.7.5 → 1.9.4). Enables `HapticFeedbackType.Confirm` and `HorizontalPager` from stable Foundation APIs.
- **KSP annotation processing added** — Room compiler uses KSP (`com.google.devtools.ksp` v2.0.21-1.0.27) instead of KAPT for faster builds.
- **Unified Chassis section ("Neon Connect" layout)** — merged the separate TPMS and AWD sections into a single `UnifiedChassisSection` in `ChassisPage.kt`. Tire cards (FL/RL left, FR/RR right) flank the RS wireframe with colored accent edge bars matching tire status. Cards show PSI, delta arrows, wheel speed, temp, and a temperature fill bar. Diamond-shaped wheel markers replace dots on the wireframe. AWD metrics (torque bar, bias, deltas, temps, clutch data) sit below in the same card, eliminating duplicate wheel speed displays. ([#149](https://github.com/klexical/openRS_/issues/149))

### Added (rc.9 — chassis redesign, Sapphire dashboard)
- **Chassis TPMS redesign** — `NeonTireCard` rebuilt with full position names ("Front Left"), hero PSI + unit side-by-side (22sp), prominent temperature (14sp, color-coded via `tireTempColor()`), temperature progress bar, and session delta arrows. Warning banner identifies specific low tire(s) by name. Always shows full layout (no static wireframe placeholder for waiting state). TPMS footer row with recommended threshold + "Updated Xs ago" timestamp. ([#153](https://github.com/klexical/openRS_/issues/153))
- **AWD rear axle diagram** — Canvas-drawn drivetrain in `ChassisPage.kt`: wheels (accent L / green R), clutch packs with temps, RDU box with temp, PTU box with F/R split label, propshaft line, torque flow arrows scaled to L/R distribution. Replaces old plain torque bar. ([#154](https://github.com/klexical/openRS_/issues/154))
- **Chassis lead lines** — Canvas overlay in `UnifiedChassisSection` draws connector lines from tire card edges to wheel positions on car wireframe using `onGloballyPositioned` + `positionInRoot()`. ([#155](https://github.com/klexical/openRS_/issues/155))
- **Crash history UI** — `CrashHistorySection` in `DiagPage.kt` shows crash file count, timestamps, and exception types. Crash files retained after export (not deleted). ([#161](https://github.com/klexical/openRS_/issues/161))
- **DID prober export** — probe sessions included as CSV files in diagnostic ZIP exports via `DiagnosticExporter`. ([#160](https://github.com/klexical/openRS_/issues/160))
- **What's New dialog** — `WhatsNewDialog.kt` with version-keyed highlights. Shown on first launch after update, version tracking via `AppSettings.lastSeenVersion`. ([#159](https://github.com/klexical/openRS_/issues/159))
- **Sapphire web dashboard integration** — `MorePage` "WEB DASHBOARD" section with Sapphire button (opens URL in browser). Sapphire link added to trip export share text in `DiagnosticExporter`. Sapphire highlight in What's New v2.2.5 list. ([#162](https://github.com/klexical/openRS_/issues/162))

### Fixed (rc.9)
- **Drive mode cold-start race condition** — added `has420Arrived` gate in `CanDecoder.kt` that prevents Sport/Track resolution until the first `0x420` frame is received. Confirmation loop in `MorePage` now waits 2s settling delay after HTTP 200 and uses 15s timeout (was 8s). Diagnostic logging of `modeDetail420` on failure. Root cause: firmware closed-loop controller overshoots on cold ECU (SLCAN-proven). ([#153](https://github.com/klexical/openRS_/issues/153))

### Changed (rc.9)
- **More tab cleanup** — moved theme picker and HUD toggle to `SettingsSheet`. Removed duplicate snapshot button, settings button, and entire CONNECTION & DIAGNOSTICS section from `MorePage`. ([#156](https://github.com/klexical/openRS_/issues/156), [#157](https://github.com/klexical/openRS_/issues/157), [#158](https://github.com/klexical/openRS_/issues/158))
- **Settings visual overhaul** — replaced `SurfUp` (neutral grey `#141414`) with blue-tinted palette (`Bg`, `Surf2`, `Surf3`) matching the cockpit aesthetic throughout `SettingsSheet.kt`. ([#158](https://github.com/klexical/openRS_/issues/158))
- **Tire font sizes** — temperature increased to 14sp with color coding, hero PSI to 22sp. ([#153](https://github.com/klexical/openRS_/issues/153))

---

## [v2.2.4] — 2026-03-19

### Added (rc.10.1 -- TPMS tire temperature polling + UI, Focus RS wireframe)
- **TPMS tire temperature polling via PID 0x280B** -- implements the "last received sensor" approach discovered by @adamsouthern on issue #119. Per-tire temperature PIDs 0x2823-0x2826 were confirmed unsupported (BCM returns `7F 22 31`). Instead, the app now: (1) polls sensor IDs from PIDs `0x280F` (LF), `0x2810` (RF), `0x2811` (RR), `0x2812` (LR) once on connect to build an ID-to-position map; (2) polls PID `0x280B` ("last received TPMS sensor") every 30s BCM cycle. The 0x280B response contains the 4-byte sensor ID, pressure (`(A*256+B)/20` PSI), temperature (`raw - 40` C), status byte, and checksum. The sensor ID is matched against the stored map to assign temperature to the correct tire position. New constants added to `ObdConstants.kt`: `BCM_QUERY_TPMS_LAST`, `BCM_QUERY_TPMS_ID_LF/RF/RR/LR`, `BCM_TPMS_ID_QUERIES`, `BCM_FLOW_CONTROL`.
- **ISO-TP multi-frame reassembly for BCM responses** -- new `IsoTpBuffer` class (`IsoTpBuffer.kt`) handles the 12-byte 0x280B response which spans two CAN frames (First Frame + Consecutive Frame). When a First Frame (PCI high nibble `0x1`) is detected on CAN ID 0x72E, the buffer stores the partial payload and triggers a Flow Control frame (`30 00 00...` on 0x726). The subsequent Consecutive Frame (PCI `0x2x`) is appended and the complete payload is passed to `ObdResponseParser.parseBcmReassembled()`. Single-frame responses continue through the existing `parseBcmResponse()` path unchanged. Both `WiCanConnection.kt` and `MeatPiConnection.kt` updated with this handling.
- **`ObdResponseParser.parseBcmReassembled()`** -- new method for multi-frame BCM payloads. Decodes DID 0x280B: extracts the 4-byte sensor ID, 2-byte pressure, 1-byte temperature (raw - 40 C), validates range (-40 to 120 C), matches against `VehicleState.tpmsSensorId{LF,RF,RR,LR}`, and updates the corresponding `tireTemp{LF,RF,LR,RR}` field. Unknown sensor IDs are logged at debug level.
- **TPMS sensor ID parsing (0x280F-0x2812)** -- added to `parseBcmResponse()` alongside existing pressure DIDs. Each returns a 4-byte sensor ID in a single-frame response. Stored in new `VehicleState` fields: `tpmsSensorIdLF`, `tpmsSensorIdRF`, `tpmsSensorIdRR`, `tpmsSensorIdLR` (Long, default -1L). Merged in `CanDataService.kt` with sentinel-based guards matching the existing tire pressure pattern.
- **Tire temperature display in TPMS section** -- each `TireCard` composable in `Components.kt` now accepts an optional `tempC` parameter (default -99.0 = no data). When temp data is available (> -90C sentinel), it renders below the pressure value separated by a thin `Brd`-colored divider: `displayTemp(tempC) + tempLabel` in `MonoText` at 10sp, color-coded by `tireTempColor()`. Color ranges: `Mid` blue-grey (<15C cold), `Ok` green (15-27C good), `Warn` gold (28-40C warm), `Orange` (>40C hot). Respects the global C/F temperature unit setting via `UserPrefs.displayTemp()`.
- **Focus RS MK3 wireframe replaces car outline placeholder** -- the old rectangle-and-dot `CarOutlinePlaceholder` composable is replaced with `FocusRsOutline` in `Components.kt`. Renders a detailed top-down wireframe of the Focus RS from a transparent PNG asset (`res/drawable-nodpi/focus_rs_wireframe.png`, 276x570px, pre-rotated to portrait). Uses `ColorFilter.tint(accent, BlendMode.SrcIn)` with `LocalThemeAccent.current` so the wireframe automatically recolors to match whichever RS paint theme the user selects (Nitrous Blue, Race Red, Deep Orange, Stealth Grey, Shadow Black, Frozen White). `CarOutlinePlaceholder` retained as a `@Deprecated` alias for backward compatibility.
- **Temperature range legend** -- new `TireTempLegend` composable in `ChassisPage.kt`. Compact `Surf2`-background row with four `LegendDot` composables (5dp `CircleShape` dot + 7sp `MonoLabel`), laid out with `SpaceEvenly` arrangement. Thresholds adapt to the user's C/F unit preference via inline conversion (`(c * 9/5) + 32` for Fahrenheit). Only visible when `VehicleState.hasTireTempData` is true.
- **`VehicleState.hasTireTempData`** -- new computed property: `true` when any of `tireTempLF/RF/LR/RR > -90`. Used in `TpmsSection` to conditionally show the temp legend, update the section label to "TPMS -- PRESSURE & TEMPERATURE", and pass temp values to `TireCard`.

### Fixed (rc.10 -- WiFi routing, diagnostic log bug fixes)
- **REST API commands never reached firmware** — `FirmwareApi.kt` used a bare `Socket()` for HTTP POST to the WiCAN AP (192.168.80.1). Android 10+ detects the WiCAN WiFi as "no internet" and silently routes new sockets through cellular, causing all drive mode and ESC commands to time out. Now resolves the WiFi `Network` via `ConnectivityManager` and creates sockets through `Network.socketFactory`, guaranteeing traffic stays on the WiCAN WiFi regardless of internet validation. This was the root cause of both drive mode and ESC buttons being completely non-functional from the app.
- **Ignition status showed raw number "30" instead of "RUN"** — `CanDecoder.kt` extracted ignition from 0x0C8 byte2 using wrong bit mask (`and 0x1F`, bits 0-4). Correct field is bits 3-6 (`(shr 3) and 0x0F`). Verified against SLCAN diagnostic: byte2=`0x3E` now correctly decodes to 7="Run". All 10 periodic samples validate.
- **Odometer showed 109,756 km instead of correct 67,500 km** — `CanDecoder.kt` read 0x360 bytes[5:6] as a 16-bit wrapped odometer (44,220), then DID 0xDD01 added a +65,536 rollover offset → 109,756. The actual odometer is at bytes[3:5] as a full 24-bit value (frame `C4 C0 3F 01 07 AC BC B2` → `01 07 AC` = 67,500 km, matching the physical odometer exactly). Fixed to read 24-bit from bytes[3:5], eliminating the need for rollover offset entirely. DID 0xDD01 handler simplified to a straight 24-bit read (was already correct). Verified against real car diagnostic session (2026-03-21).
- **Engine status 196 (0xC4) shown as hex instead of label** — added cold-start engine state to the incomplete value table (0x360 byte0). Display now correctly handles this uncatalogued state.

### Added (rc.9 — F1 telemetry header, hero card polish, build system)
- **F1 telemetry header replaces single-row app bar**: The old `AppHeader` crammed logo, drive mode badge, ESC badge, connection dot, settings gear, and TRIP button into one horizontal row. Replaced with a two-part F1 pit-wall layout in `MainActivity.kt`. **Top row**: `openRS_` logo (Orbitron Bold 18sp) on the left; right side groups a tappable connection pill, TRIP button, and settings gear with consistent 8dp spacing. **Bottom row**: full-width `Surf2` telemetry strip with five `TeleCell` composables separated by `TeleDivider` (1dp × 22dp, `Brd` at 40% alpha). Each cell shows a 7sp JetBrains Mono label above a 10sp Share Tech Mono bold value.
- **Connection pill with state-aware coloring**: Replaces the standalone 8dp connection dot. Now a bordered `RoundedCornerShape(4.dp)` pill showing a 6dp `CircleShape` dot + text label. Three states: green "LIVE" (connected, dot pulses via `infiniteRepeatable` 1s `EaseInOut`), amber "IDLE" (retries exhausted), red "OFF" (disconnected). Tapping cycles `onConnect` / `onDisconnect` / `onReconnect` — same actions as the old dot, but with a visible tap target and readable status.
- **Telemetry strip — five real-time cells**: **MODE** — `VehicleState.driveMode.label.uppercase()`, color-coded: SPORT=`Ok` (green), TRACK=`Warn` (gold), DRIFT=`Red` (orange-red), NORMAL/CUSTOM=theme accent. **ESC** — `VehicleState.escStatus.label.uppercase()`, color-coded: OFF=`Red`, SPORT/LAUNCH=`Warn`, ON=accent. **CONN** — shows `framesPerSecond.toInt()` + " FPS" when connected (green), "—" when disconnected (dim). **IGN** — reuses `ignitionStatusLabel()` from `DiagPage.kt` (maps 0x0C8 byte 2 bits 0-4: KEY OUT / ACC / RUN / CRANK). **E-BRK** — `VehicleState.eBrake` boolean: ON=`Warn`, OFF=`Ok`. Previously these signals were only visible on DIAG/MORE tabs — now always visible at a glance.
- **Session peak speed on DASH hero card**: New `VehicleState.peakSpeedKph` field (default 0.0) tracked in `withPeaksUpdated()` via `max(peakSpeedKph, speedKph)` alongside existing `peakBoostPsi` and `peakRpm`. Reset to 0.0 in `withPeaksReset()`. SPEED hero card on DASH now passes `peak = "▲ ${p.displaySpeed(vs.peakSpeedKph)}"` to `HeroCard`, rendering the peak in the user's selected unit (MPH or KPH) as accent-colored text below the main value — matching the BOOST and RPM cards. Unit tests updated in `VehicleStateTest.kt`: `peak tracking updates higher values` (180 kph > 150 kph), `peak tracking does not lower peaks` (200 kph preserved), `peak reset clears all peaks` (peakSpeedKph → 0.0).
- **RC version suffix in Settings**: New `BuildConfig.RC_SUFFIX` string field added via `buildConfigField` in `build.gradle.kts`, reading from `project.findProperty("rcSuffix")`. Value set in `android/gradle.properties` (`rcSuffix=rc.9` for pre-release, empty for stable). `SettingsSheet.kt` version label uses `buildString { append("openRS_ v${BuildConfig.VERSION_NAME}"); if (BuildConfig.RC_SUFFIX.isNotEmpty()) append("-${BuildConfig.RC_SUFFIX}") }` — displays "openRS_ v2.2.4-rc.9" for RC builds, "openRS_ v2.2.4" for stable. No app code changes needed when promoting to stable — just clear the property.

### Changed (rc.9)
- **Rolling RC release workflow**: Updated `.cursor/rules/release-lifecycle.mdc`, `release-checklist.mdc`, and `release-artifacts.mdc`. Each version stream (android, fw-usb, fw-pro) now maintains **one pre-release** on GitHub that is updated in place as RCs progress — tag is moved, APK/binary is swapped, release notes are updated. Eliminates the pattern of creating a new release per RC that cluttered the releases page and required periodic manual cleanup.
- **Drive mode and ESC badges removed from header row**: Previously rendered as standalone `Box` composables with `RoundedCornerShape(4.dp)` backgrounds in the top row. Now surfaced in the telemetry strip's MODE and ESC cells, freeing horizontal space in the top row and making room for the connection pill.

### Fixed (rc.9)
- **SPEED hero card shorter than BOOST and RPM cards**: BOOST and RPM `HeroCard` instances rendered a `MonoText` peak line (9sp, ~13dp height) while SPEED had `Spacer(Modifier.height(4.dp))` in its place. Added `Modifier.height(IntrinsicSize.Max)` to the hero `Row` in `DashPage.kt` so the row constrains to the tallest child's intrinsic height, and added `.fillMaxHeight()` to each `HeroCard` modifier so shorter cards stretch to match. All three hero cards now render at identical height regardless of whether they have a peak value.

### Added (rc.8 — surface undisplayed signals, UX fixes)
- **Rally-style gear indicator on DASH tab**: Full-width hero card below the BOOST/RPM/SPEED row with a large 72sp Orbitron gear number and theme accent border glow. `VehicleState.gearDisplay` uses CAN 0x230 when available, falling back to the `derivedGear` algorithm (calibrated from live 16-minute log with known gear ratios for the Focus RS 6-speed manual: 1st≈3.79, 2nd≈2.18, 3rd≈1.89, 4th≈1.30, 5th≈0.85).
- **Session peak boost & peak RPM embedded in hero cards**: `VehicleState.peakBoostPsi` and `VehicleState.peakRpm` now shown as "▲" accent text directly inside the BOOST and RPM hero cards (GfCard pattern from CHASSIS tab), replacing the separate peak cards. `HeroCard` component gains optional `peak` parameter.
- **Battery SoC, voltage, and temperature on DIAG tab**: New data row shows `VehicleState.batteryVoltage` (OBD Mode 22 DID 0x0304), `VehicleState.batterySoc` (BCM 0x726 DID 0x4028, start/stop SoC %), and `VehicleState.batteryTempC` (BCM 0x726 DID 0x4029, A−40 °C). SoC cell color-coded: red < 50%, amber < 70%, green ≥ 70%.
- **Commanded AFR (lambda) on POWER tab**: `VehicleState.commandedAfr` (OBD Mode 01 PID 0x44, 0-2 lambda) now shown as "CMD AFR" in the FUEL TRIMS & AFR section. Complements the Mode 22 wideband AFR fields already present.
- **Low-pressure fuel rail on POWER tab**: `VehicleState.fuelRailPsi` (OBD Mode 01 PID 0x22, kPa→PSI) now shown as "LP FUEL" alongside the existing "HP FUEL" (Mode 22 DID 0xF422). Provides the low-pressure supply rail reading as a comparison to the direct-injection HP rail.
- **Manifold charge temperature on TEMPS tab**: `VehicleState.manifoldChargeTempC` (Mode 22 DID, °C) added to the temperature grid between CHARGE AIR and CATALYTIC. Warn at 60°C, critical at 90°C.
- **AWD max torque, PTU/RDU temps on CHASSIS tab**: When `VehicleState.awdMaxTorque > 0`, a new row appears in the AWD section showing AWD MAX (Nm), PTU TEMP, and RDU TEMP. Consolidates drivetrain thermal data alongside the torque split visualization.
- **TPMS pressure spread warning on CHASSIS tab**: When `VehicleState.maxTirePressSpread ≥ 4.0 PSI` and no tire is individually low, a "⚠ PRESSURE IMBALANCE — X.X PSI spread" amber banner appears. Alerts to uneven pressure across corners without triggering the more severe "LOW TIRE PRESSURE" warning.
- **No-internet hint on Trip page**: When `VehicleState.isConnected == true`, a subtle floating label ("No internet on adapter WiFi — tiles may be cached") appears at the bottom-right of the map. Addresses [#95](https://github.com/klexical/openRS_/issues/95) with a UX hint while a proper offline tile solution is developed.

### Fixed (rc.8)
- **Race-Ready banner ignored RDU and PTU temps**: `RtrBanner` on TEMPS tab used `UserPrefs.isRaceReady(oilTempC, coolantTempC)` which only checked oil and coolant. Now uses `VehicleState.rtrStatus` which also factors in RDU ≥ 30°C and PTU ≥ 40°C — matching the full warmup logic already defined in VehicleState. Banner now shows the complete breakdown (e.g. "Oil 72°C < 80°C · RDU 22°C < 30°C") instead of hardcoded threshold text.

### Added (rc.7 — surface rc.6 signals in UI)
- **Vertical G readout on CHASSIS tab** ([#104](https://github.com/klexical/openRS_/issues/104)): `VehicleState.verticalG` (CAN 0x180 bytes 0-1, decoded in rc.6) now displayed as VERT G card in G-Force row 1 alongside LAT G and LON G. COMBINED G moved to row 2 with YAW and STEER. RESET PEAKS button refactored to full-width bar below the cards.
- **Launch Control active banner on DASH tab** ([#105](https://github.com/klexical/openRS_/issues/105)): `VehicleState.launchControlActive` (CAN 0x420 bit 50, decoded in rc.6) now surfaces as a conditional "⚡ LAUNCH CONTROL ACTIVE" banner below the hero cards (BOOST / RPM / SPEED). Visible only when LC is engaged — applies to any drive mode (user puts car in 1st gear and holds at LC RPM set by tune). Banner hidden when `launchControlActive == false` to avoid clutter.
- **Engine status, ignition status, e-brake on DIAG tab** ([#106](https://github.com/klexical/openRS_/issues/106), [#107](https://github.com/klexical/openRS_/issues/107)): New data row in DIAGNOSTICS section displays `VehicleState.engineStatus` (CAN 0x360 byte 0: Idle/Off/Running/Kill/Start), `VehicleState.ignitionStatus` (CAN 0x0C8 byte 2 bits 0-4: Key Out/Acc/Run/Crank), and `VehicleState.eBrake` (CAN 0x0C8 byte 3 bit 6). Helper functions `engineStatusLabel()` and `ignitionStatusLabel()` map raw int values to human-readable labels.
- **ESC Launch mode indicator on MORE tab** ([#108](https://github.com/klexical/openRS_/issues/108)): When `VehicleState.escStatus == EscStatus.LAUNCH` (CAN 0x1C0 value 3), a "⚡ ESC LAUNCH MODE" banner appears below the ESC ON / SPORT / ESC OFF chips. The three main chips don't include LAUNCH as a tappable option (it's a car-initiated state, not user-selectable via CAN write).
- **CAN-based LC active on MORE tab** ([#105](https://github.com/klexical/openRS_/issues/105)): Launch Control card in the openRS-FW section now shows "⚡ ACTIVE" from passive CAN 0x420 (`launchControlActive`) when LC is engaged, taking priority over the firmware OBD probe state (`lcArmed`). This means LC engagement is visible even on stock WiCAN firmware (no openrs-fw required for the passive CAN signal).

### Added (rc.6 — free CAN signals from RS_HS.dbc)
- **Vertical G from CAN 0x180** ([#104](https://github.com/klexical/openRS_/issues/104)): Extracted `VertAccelMeasured` (bytes 0-1, 10-bit × 0.00390625 − 2.0 g) from the same ABSmsg02 frame that already provides lateral G and yaw rate. Completes the 3-axis accelerometer picture. Zero new CAN bus traffic.
- **Launch control status from CAN 0x420** ([#105](https://github.com/klexical/openRS_/issues/105)): Extracted `LaunchControlStatus` (bit 50) from the same drive mode detail frame. New `launchControlActive` boolean in VehicleState. Zero new CAN bus traffic.
- **Engine status from CAN 0x360** ([#106](https://github.com/klexical/openRS_/issues/106)): Extracted `engine_status` (byte 0) from the same frame that provides odometer. Values: 0=Idling, 2=Off, 183=Running, 186=Kill, 191=RecentlyStarted. Foundation for smart auto-disconnect and trip boundary detection.
- **Ignition status from CAN 0x0C8** ([#107](https://github.com/klexical/openRS_/issues/107)): Extracted `IgnitionStatus` (byte 2, bits 0-4) from the same frame that provides gauge brightness and e-brake. Values: 0=KeyOut, 4=Accessory, 6=IgnOn, 7=Running, 9=Cranking.

### Fixed (rc.6)
- **ESC "Launch" mode showed as "--"** ([#108](https://github.com/klexical/openRS_/issues/108)): `EscStatus.fromInt()` only handled values 0-2. DBC defines value 3 = Launch (RS_HS.dbc `VAL_ 448`). Added `LAUNCH("Launch")` to the enum. ESC chip on MORE page now correctly displays "Launch" when launch control is active.

### Fixed (rc.5 — full repo audit)
- **MeatPi users got WiCAN defaults on first run** — `AppSettings.getHost()` / `getPort()` always returned WiCAN defaults (192.168.80.1:80) even when adapter was set to MeatPi. Now checks adapter type and returns MeatPi defaults (192.168.0.10:35000) when no user-configured value exists.
- **Hardcoded colors outside theme system** — replaced 6 raw `Color(0xFF...)` in `SettingsSheet.kt` and `DiagPage.kt` with new `SurfUp` and `OnAccent` theme tokens.
- **Theme color duplication** — RS paint accent colors were defined in two separate `when` blocks (`UserPrefs.kt` and `MorePage.kt`). Centralized into `RsPaints` list and `rsPaintAccent()` / `rsPaintName()` in `Theme.kt`.
- **Stale CAN ID comments** in `VehicleState.kt` (referenced 0x430/0x3E8 instead of 0x0F8/0x2F0), stale `TODO(M-5)` in `CanDecoder.kt` (battery voltage now handled via Mode 22 DID 0x0304), hex case inconsistency in `ObdConstants.kt` (033e→033E), unused `kotlinx.coroutines.launch` import in `MainActivity.kt`.
- **`VehicleState.dataMode`** deprecated — unused field, always "CAN".

### Fixed (rc.4 — correct RS MK3 paint theme colours)
- **Theme colours didn't match actual Focus RS MK3 paint catalogue**: 4 of 6 themes were paints never offered on the MK3 (Tangerine Scream = Focus ST, Mean Green = never RS, Stealth = purple instead of grey, Moondust Silver = not on MK3). Nitrous Blue (`#00D2FF`) was too cyan; Race Red (`#FF2233`) was too neon-pink.
- **New palette uses verified MK3 paint options**: Nitrous Blue `#0091EA`, Race Red `#D62828`, Deep Orange `#D45500` (Heritage Edition), Stealth Grey `#6B7580`, Shadow Black `#3A3D44`, Frozen White `#E8ECF0`.
- Updated `UserPrefs.kt`, `MorePage.kt`, `Theme.kt`, `colors.xml`, `TripPage.kt` map marker, and browser emulator (CSS variables, 17 hardcoded rgba refs, JS theme map, theme chip HTML).

### Added (rc.3 — passive odometer from CAN 0x360)
- **Passive odometer decode from CAN 0x360** ([#103](https://github.com/klexical/openRS_/issues/103)): Community contributor @adamsouthern [discovered](https://github.com/klexical/openRS_/discussions/102) that CAN ID 0x360 passively broadcasts the odometer at ~5 Hz. Bytes [5:6] big-endian, 16-bit unsigned, 1 km per bit. Added as the 22nd decoder in `CanDecoder.kt`. Replaces the Mode 22 extended-session poll (DID 0xDD01) as the primary real-time odometer source. Cross-verified against our own diagnostic logs from car tests on 2026-03-16 and 2026-03-18 (~42K km car).
- **Mode 22 odometer reduced to once-on-connect** ([#103](https://github.com/klexical/openRS_/issues/103)): The UDS extended diagnostic session for odometer (BCM 0x726 → `10 03` + `22 DD 01`) was the heaviest OBD operation, running every 60 seconds. Now polls **once on connect** to get the full 24-bit value (up to 16.7M km), then uses the passive 0x360 broadcast for real-time updates. This also sets `odometerRolloverOffset` for cars past 65,535 km where the 16-bit value has rolled over.

### Changed (rc.3)
- `VehicleState.odometerKm` now primarily sourced from passive CAN 0x360 instead of Mode 22 polling. Added `odometerRolloverOffset` field (set from Mode 22 on connect) to correct for 16-bit rollover on high-mileage cars.
- `ObdResponseParser.parseBcmResponse` for DID 0xDD01 now computes and stores `odometerRolloverOffset = (km / 65536) * 65536`.
- `WiCanConnection` and `MeatPiConnection` extended session pollers now skip the odometer query after the first successful cycle.

### Fixed (rc.2 — tap-to-change modes)
- **Tap-to-change drive mode & ESC** ([#99](https://github.com/klexical/openRS_/issues/99)): Existing chips on MORE tab are now tappable to send commands via REST API. Drive mode works end-to-end with firmware v1.4+. ESC pre-wired for firmware v1.5 ([#98](https://github.com/klexical/openRS_/issues/98)).

### Added (rc.2)
- **App version in Settings** ([#90](https://github.com/klexical/openRS_/issues/90)): Shows `openRS_ vX.Y.Z` in the settings dialog footer using `BuildConfig.VERSION_NAME`.
- **Crash telemetry capture** ([#97](https://github.com/klexical/openRS_/issues/97)): `CrashTelemetryBuffer` maintains a rolling ring of the last 100 VehicleState snapshots. On uncaught exception, `CrashReporter` flushes the buffer + stack trace to `crash_telemetry_<ts>.json`. Crash files are automatically bundled into the next diagnostic ZIP export.

### Fixed (rc.1)
- **ESC decode — wrong bit position and swapped mapping** ([#96](https://github.com/klexical/openRS_/issues/96)): CAN 0x1C0 ESC mode was extracted at `bits(13,2)` but the actual signal lives at `bits(10,2)` (byte1 bits 5-4). The enum mapping also had 1=Sport/2=Off when SLCAN data proves 1=Off/2=Sport. SLCAN-verified: 0xC0=On, 0xD0=Off, 0xE0=Sport.
- **Throttle stuck at zero when CAN 0x076 absent** ([#93](https://github.com/klexical/openRS_/issues/93)): Some Focus RS variants don't broadcast 0x076 (throttle position). DASH now falls back to `accelPedalPct` from 0x080, showing "PEDAL" instead of a stuck-at-zero "THROTTLE" bar.
- **Battery voltage always 0.0V** ([#92](https://github.com/klexical/openRS_/issues/92)): No OBD query existed for battery voltage. Added PCM Mode 22 DID 0x0304 with formula `(A*256 + B) / 2048` V to the polling loop.
- **LC/ASS/FENG stuck on "PROBING" forever** ([#94](https://github.com/klexical/openRS_/issues/94)): FENG (0x727) and RSProt (0x731) ECUs don't respond on some cars. After 3 ext-session probe cycles (~3 min), the app stops probing and shows "N/A" instead.

---

## [v2.2.3] — 2026-03-15

### Fixed

- **Trip GPS permission broken** (#85): `ACCESS_FINE_LOCATION` was missing from `AndroidManifest.xml`. The "Grant Access" button on the Trip page fired the runtime request, but Android silently ignored it because the permission was undeclared.
- **Module Status / LC / ASS never populate** (#86): `mergeObdState()` omitted 7 fields — `rduEnabled`, `pdcEnabled`, `fengEnabled`, `lcArmed`, `lcRpmTarget`, `assEnabled`, `hpFuelRailPsi` — so OBD-parsed extended session data never reached the UI.
- **Diagnostic export missing ~24 OBD fields** (#87): `DiagnosticExporter.toJsonFields()` and the text summary excluded most Mode 22 parameters (ETC, WGDC, KR, OAR, TIP, VCT, oil life, HP fuel rail, charge air, catalyst, odometer, battery SOC/temp, cabin temp, module status flags). All now included in both JSON and text reports.
- **OBD response frames excluded from SLCAN log** (#88): OBD responses (0x7E8, 0x72E, 0x70B, 0x738, 0x72F, 0x739) were consumed by `ObdResponseParser` before reaching `DiagnosticLogger`. Added `logObdFrame()` in both `WiCanConnection` and `MeatPiConnection` so OBD traffic appears in the SLCAN export.
- **`reconnect()` not `@Synchronized`** (#81): Concurrent calls from the WiFi callback and UI could race on `connectionJob`, causing orphaned connections.
- **SLCAN writer never closed on `sessionEnd()`** (#81): The `BufferedWriter` leaked until the next `sessionStart()`. Now properly closed inside a synchronized block.
- **Connection dot untappable in a car** (#81): The 8dp circle had no touch padding. Wrapped in a 48×48dp tap target (Material minimum).
- **`TripRecorder.startTrip()` double-start** (#77): If called while already recording, a second coroutine would be launched, orphaning the first. Now guarded by `recorderJob?.isActive` check.
- **Trip recording stuck on permission revocation** (#83): If location permission was revoked mid-trip, the coroutine failed silently but `isRecording` stayed `true`. Wrapped location collection in `try/finally` that resets state.
- **DTC scan/clear adapter reference unsynchronized** (#83): `scanDtcs()` and `clearDtcs()` read `wican`/`meatpi` references without holding the lock. Captured inside `synchronized(this)` to prevent use of stale references during reconnect.
- **DiagPage swallows `CancellationException`** (#83): Generic `catch (_: Exception)` blocks caught and discarded `CancellationException`, breaking structured concurrency. Now rethrown.
- **PowerPage `!= 0.0` sentinel hides real zero readings** (#82): `timingAdvance`, `ignCorrCyl1`, `vctIntakeAngle`, `vctExhaustAngle`, `octaneAdjustRatio`, and fuel trims used `!= 0.0` as "has data" — hiding legitimate zero values. Now gate on `calcLoad > 0` (OBD data is present).
- **Inconsistent temperature sentinels** (#82): `chargeAirTempC` and `catalyticTempC` used `0.0` as default, meaning a real 0°C reading showed as "no data". Changed defaults to `-99.0` and guards to `> -90`, matching all other temperature fields.
- **ChassisPage wheel speed delta ignores unit preference** (#82): L/R and F/R delta values always showed "km/h" regardless of the user's speed unit setting. Now respects `prefs.speedUnit`.

### Improved

- **SettingsSheet respects theme accent** (#82): Title bar, SAVE button, Switch tracks, SegmentedPicker, and text field focus colors now use `LocalThemeAccent.current` instead of hardcoded cyan.
- **`LocalThemeAccent` → `staticCompositionLocalOf`** (#82): Theme accent changes only on settings save. `staticCompositionLocalOf` avoids unnecessary per-slot recomposition tracking.
- **MeatPiConnection TCP buffering** (#83): Wrapped raw `InputStream` in `BufferedInputStream`, reducing ~50,000 kernel syscalls/sec (byte-by-byte reads at 2100 fps) to buffered block reads.
- **Foreground notification** now shows "Connecting…" while attempting connection, "Connected to vehicle" once live, and "Disconnected — tap to retry" when idle. Previously always said "Connected" even during connection attempts.
- **Removed double inset padding** — `statusBarsPadding()`/`navigationBarsPadding()` was redundant with Scaffold's `innerPadding`, causing a gap at the top on some devices.
- **`install-debug.sh`** (#10): Now resolves the project directory from the script's location, so it works when invoked from any working directory.
- Compose BOM update (#14) deferred — the jump to 2026.02.01 caused ANR crashes; staying on 2024.11.00 until a safe incremental update path is validated.

---

## [v2.2.2] — 2026-03-12

### Fixed

- **Drive mode Sport/Track disambiguation** (#74): Sport mode incorrectly displayed as Track. Root cause: both modes produce `0x420 byte6=0x11` — the decoder only checked byte6. The real discriminator is byte7 bit 0 (`0=Sport, 1=Track`). Confirmed via live SLCAN capture (344k frames, WiCAN Pro session). `modeDetail420` now stores `byte6<<8|byte7`.
- **MeatPi Pro TCP socket timeout** increased from 5s to 20s, matching the WiCAN WebSocket timeout. The aggressive 5s timeout caused unnecessary disconnect/reconnect cycles during brief CAN traffic pauses.
- **Reconnect loop hammering** (#37): when a connection succeeded but dropped quickly (< 30 s), the reconnect delay was a flat 5 s and `failedAttempts` was reset every time — creating an infinite rapid-reconnect loop. Consecutive short-lived connections now apply escalating backoff (5 s → 10 s → ... → 60 s cap). Stable connections (> 30 s uptime) still use the configured reconnect delay. Applied to both `WiCanConnection` and `MeatPiConnection`.
- **Stale APK version comment** (#30): `build.gradle.kts` APK rename comment referenced `v2.0.1`; updated to use `{version}` placeholder since the version is dynamic.
- **Unused imports** (#29, #16): removed `WindowManager`, `DiagnosticLogger`, `DiagnosticExporter` imports from `MainActivity.kt`.

### Added

- **Foreground service with persistent notification** (#33): `CanDataService` now calls `startForeground()` with a low-priority "Connected to vehicle" notification while a CAN session is active. This prevents Android from killing the service when the app is backgrounded. Notification channel created in `OpenRSDashApp`, `foregroundServiceType="connectedDevice"` declared in manifest. Notification is removed when the connection stops.
- **OBD response malformed-frame logging** (#38): all six `ObdResponseParser` methods (`BCM`, `AWD`, `PCM`, `PSCM`, `FENG`, `RSProt`) now `Log.w` when a frame is too short to parse, including the raw hex bytes. Previously these were silent early returns.
- **Silent catch-block logging** (#4): critical `catch` blocks in `SlcanParser`, `MeatPiConnection` (readSlcanLine), and `CanDataService` (network callback registration) now log the exception instead of swallowing it. OBD polling send catches remain silent (expected failures during disconnect).
- **WiCAN Pro setup documentation**: hardware-setup.md now includes a full WiCAN Pro section with first-time setup instructions. Prominently documents the critical requirement to set the Pro's protocol to **SLCAN** in its web UI (the Pro defaults to ELM327 mode).
- **OPENRS? firmware probe for TCP**: `MeatPiConnection` now sends the `OPENRS?` probe after SLCAN init, enabling firmware detection on the WiCAN Pro (stock vs openrs-fw).

### Changed

- **SecureRandom reuse** (#17): `WiCanConnection.sendHttpUpgrade()` was creating a new `SecureRandom` instance per connection. Now reuses the existing `rng` field.

---

## [v2.2.1] — 2026-03-08

### Added

- **DTC Scanner**: New section at the top of the DIAG tab — tap "SCAN ALL MODULES" to query PCM, BCM, ABS, AWD, and PSCM for active, pending, and permanent fault codes via UDS Service 0x19. Results show each code, its description (from a bundled 873-code database), and fault status. Multi-module ISO-TP assembly with flow-control handles responses of any length.
- **DTC Clear Fault Codes**: "CLEAR FAULT CODES (0x14)" button appears in the DIAG tab after a scan returns results. Sends UDS Service 0x14 (ClearDiagnosticInformation, group 0xFFFFFF) to all five ECUs and reports per-module acknowledgement (✓/✗). Results are auto-dismissed after a successful clear.
- **DTC database**: 873 Ford-specific DTC descriptions bundled as `res/raw/dtc_database.json`. Loaded once on first scan.
- **Trip CSV export**: Trip ZIPs now include a `trip_<ts>.csv` alongside the existing GPX and summary text. The CSV has one row per GPS waypoint with 20 columns: timestamp, lat/lng, speed, RPM, gear, boost, temps (coolant, oil, ambient, RDU, PTU), fuel %, all four wheel speeds, lateral G, drive mode, and race-ready flag.
- **MeatPi Pro adapter support**: `MeatPiConnection.kt` is now fully implemented — raw TCP SLCAN (default `192.168.0.10:35000`), identical OBD polling as WiCAN (BCM, PCM, AWD, extended session), DTC scan and DTC clear support. Adapter selection (WiCAN / MeatPi Pro) added to Settings under a new ADAPTER section.
- **MeatPi microSD logging reminder**: Settings → ADAPTER → "MicroSD logging reminder" switch — a local note that directs users to enable SD logging via the WiCAN Pro web UI at `http://192.168.0.10/`.

### Changed

- **`CanDataService`**: OBD merge logic extracted to `mergeObdState()` and CAN frame processing to `processCanFrame()` — eliminates duplication between WiCAN and MeatPi paths. Added `clearDtcs()` suspend function (mirrors `scanDtcs()`).
- **`DiagPage`**: receives `onScanDtcs` and `onClearDtcs` lambdas from `MainActivity`, keeping the composable decoupled from the service.
- **MeatPi Pro defaults**: corrected default connection to `192.168.0.10:35000` (official WiCAN Pro AP address and SLCAN TCP port). The SLCAN port is user-configurable in the WiCAN Pro web UI; `35000` matches official MeatPi documentation.
- **MicroSD toggle clarified**: labelled "MicroSD logging reminder" — SD logging is managed in the WiCAN Pro web UI, no SLCAN command exists to enable it remotely.
- **Settings adapter switch**: switching the adapter picker now auto-populates host/port with the correct defaults for the selected adapter (only when the current value still matches the other adapter's default — custom IPs are preserved).
- **`DtcModuleSpec`**: moved from nested class in `WiCanConnection` to `com.openrs.dash.data.DtcModuleSpec`, removing the `WiCanConnection` import dependency from `MeatPiConnection` and `DtcScanner`.

### Refactored

- **Shared `AdapterState`**: connection lifecycle sealed class (`Disconnected`, `Connecting`, `Connected`, `Idle`, `Error`) extracted from both `WiCanConnection` and `MeatPiConnection` into `com.openrs.dash.can.AdapterState`. `CanDataService` now references `AdapterState` uniformly instead of adapter-specific inner classes.
- **Shared `ObdConstants`**: ~120 lines of duplicated OBD query strings, response CAN IDs, and polling intervals extracted into `com.openrs.dash.can.ObdConstants`. Both adapters now reference the single source of truth.
- **Shared `ObdResponseParser`**: ~150 lines of duplicated OBD response parsers (`parseBcmResponse`, `parseAwdResponse`, `parsePcmResponse`, `parsePscmResponse`, `parseFengResponse`, `parseRsprotResponse`) extracted into `com.openrs.dash.can.ObdResponseParser`. Zero behavioral change — identical formulas, single copy.
- **Shared `SlcanParser`**: SLCAN frame parsing (`parseStdFrame`, `parseExtFrame`, `parseDataBytes`) extracted into `com.openrs.dash.can.SlcanParser`. Both adapters now call `SlcanParser.parse()`.
- **Dead `connectionState` removed**: `CanDataService.connectionState` (which only returned WiCAN state regardless of adapter) was unused by any caller — removed.
- **DTC clear confirmation dialog**: tapping "CLEAR FAULT CODES" now shows a Material 3 `AlertDialog` explaining the action before proceeding. Prevents accidental clears.

### Removed

- **`HardwareAdapter.kt`**: unused interface and `AdapterType` enum (never implemented). `GpsData` data class was also only referenced within this file.

### Fixed

- **`MeatPiConnection` cancel latency**: TCP socket now closes immediately when the coroutine is cancelled (via a cancel-watcher child job), instead of waiting up to 20 s for `soTimeout`. `soTimeout` also reduced from 20 s → 5 s as a secondary safeguard.
- **DTC clear false-success banner**: "Cleared (0/0)" no longer shown when the `ack` map is empty (all sends failed). Empty or null `ack` now shows the "Clear failed" error.
- **DTC scan error message**: no longer reads stale captured `vs.isConnected` — now always shows `"Scan failed — check adapter connection"`.
- **`DiagPage` ghost spacing**: empty `MonoLabel("")` removed; the hint label is now conditionally omitted when the clear-status confirmation box is already visible.

---

## [v2.2.0] — 2026-03-06

### Added

- **Share Trip button**: Trip Summary sheet now has a "↑ SHARE TRIP DATA" button that exports the trip as a ZIP and triggers the system share sheet — wires up `DiagnosticExporter.shareTrip()` from the UI.

### Improved

- **UI architecture**: `MainActivity.kt` has been split into dedicated files — `Theme.kt` (design tokens, fonts, typography), `Components.kt` (shared composables), and one file per tab: `DashPage.kt`, `PowerPage.kt`, `ChassisPage.kt`, `TempsPage.kt`, `DiagPage.kt`, `MorePage.kt`. Significantly reduces `MainActivity.kt` size and improves maintainability.
- **Performance — `TripRecorder`**: eliminated O(n²) list copies on every GPS waypoint by introducing an `ArrayList` buffer. Average and mode breakdown now use O(1) incremental accumulators (`speedSum`, `speedSamples`, `modeCounts`) instead of re-scanning the full point list on each update.
- **Performance — `DiagPage`**: `DiagnosticLogger.frameInventorySnapshot` is now called once per composition pass instead of once per rendered row, eliminating redundant deep-copies.
- **`DiagnosticExporter`**: JSON string values are now properly escaped (backslash, quote, control characters) including `firmwareVersion` and `sessionHost` in the meta block. Old trip ZIPs are pruned on share to match the existing diagnostic ZIP pruning behaviour.
- **`DiagnosticLogger`**: `frameInventory` is now private; `frameInventorySnapshot` returns a proper deep-copy of all `FrameInfo` fields including `validationIssues` and `periodicSamples`, preventing external mutation.

### Fixed

- **Keep screen on**: "Keep screen on" setting now activates whenever the setting is enabled, regardless of connection state. Previously a brief WiFi dropout mid-drive could let the screen time out and lock.
- **PTU temperature in Trip HUD**: PTU cell now shows "—" before the first 0x0F8 CAN frame arrives instead of displaying the raw −99°C sentinel value.
- **Coolant / oil temp before first CAN frame**: defaults changed from `0.0` to `−99` sentinel. The RTR banner no longer incorrectly shows "Warming Up — Oil 0°C < 80°C" before any engine data arrives. Dashboard and Temps page show "—" for these fields while data is pending. `isRaceReady()` skips the check at sentinel so a car that's already warm isn't penalised on first connect.
- **Tire low warning false positive**: `anyTireLow()` lower bound changed from `0.0` to `0.01` to match `isTireLow()` — prevents a theoretical false trigger at exactly 0.0 PSI.
- **Trip recorder reset race**: `pointsBuffer` is now `@Volatile` and reset by reference assignment instead of `clear()`, eliminating a narrow race where a cancelled coroutine could still be mid-`add()` when the main thread cleared the list.
- **`CanDecoder`**: `ID_TPMS` constant renamed to `ID_PCM_AMBIENT` (0x340 carries ambient temperature, not TPMS). `describeDecoded` now shows the resolved drive mode string for `ID_DRIVE_MODE_EXT` (0x420). Dead TPMS validation branch removed.
- **`WiCanConnection`**: removed unused `RECONNECT_DELAY_MS` constant; replaced fully-qualified type names with short names after adding imports.
- **`TripRecorder`**: RTR (race-ready) gate now uses `UserPrefs.isRaceReady()` consistently, matching the Temps page.
- **`ThemePicker`**: accent colours read from `UserPrefs(themeId = id).themeAccent` — single source of truth, no duplicated hex map.

### Removed

- Legacy `VehicleState` fields `lambdaValue`, `launchControl`, and `driftFury` (unused; carried forward from prototype).
- Redundant `debugLines` pass-through property on `WiCanConnection` — callers now use `OpenRSDashApp.instance.debugLines` directly.

### Stability / Threading

- **`CanDataService.startConnection()` / `stopConnection()`**: marked `@Synchronized` — prevents a race condition where the WiFi callback thread and the main thread could both enter `startConnection()` simultaneously, initialising two `WiCanConnection` instances and leaking the first.
- **`onDestroy()`**: now calls `stopConnection()` before cancelling the coroutine scope, ensuring `DiagnosticLogger.sessionEnd()` is always flushed when the service is torn down (previously the final frames of a session could be lost).
- **`startActivity()` from background thread**: `DiagnosticExporter.share()` and `shareTrip()` now post the `startActivity(Intent.createChooser(...))` call through `Handler(Looper.getMainLooper())` — prevents an `android.view.ViewRootImpl$CalledFromWrongThreadException` crash on Android 10+ when the share sheet is triggered from `Dispatchers.IO`.
- **OBD query burst on send failure**: removed `return@forEach` from the BCM, AWD, and PCM query loops — the inter-query `delay()` now always fires even when a frame fails to send, preventing a rapid burst of retries when the connection is flapping.

### Performance

- **`sessionDurationMs` memoised**: `DiagPage` and `MorePage` now read `sessionDurationMs` at most once per 1 Hz FPS tick (via `remember(vs.framesPerSecond)`), cutting per-frame string allocations from `formatDuration`.
- **`ThemePicker`**: replaced 6 throwaway `UserPrefs(themeId = id)` allocations per recomposition with a lightweight private `themeAccentColor(id)` helper function.

### Correctness

- **PTU temperature thresholds**: `TempsPage` PTU card now uses dedicated `UserPrefs.ptuWarnC` / `ptuCritC` thresholds (street 95/110°C, track 85/100°C, race 75/90°C) rather than sharing the RDU values — the PTU (transfer case) is a different assembly with higher operating limits.
- **WebSocket handshake key**: `WiCanConnection` now generates a fresh 16-byte random key per connection (RFC 6455 compliant) instead of reusing the static RFC example key.
- **64-bit WebSocket frames**: oversized frames (len=127) that are discarded now emit a `DiagnosticLogger.event("WS", ...)` entry so they appear in the diagnostic export.

---

## [v2.1.0] — 2026-03-06

### Added

- **Trip page**: new TRIP overlay accessible from the app header with a live OSMDroid map (CartoDB Dark Matter tiles), GPS track recording, and weather data via OpenWeatherMap.

### Fixed

- **Notification permanently removed**: the persistent "Idle — tap openRS_ to retry" banner no longer appears. The foreground service has been replaced with a plain background service — no notification permission is requested at first launch and nothing ever appears in the notification shade.
- **Track drive mode**: the app now correctly identifies Track mode. `0x1B0` alone cannot distinguish Sport from Track; the fix adds `0x420` byte 6 as a second signal (`0x10` = Sport, `0x11` = Track), cross-validated against DBC and three live sessions.
- **Drift drive mode**: corrected to map to `0x1B0` nibble 2 (DBC `VAL_ 432` confirmed).
- **RPM hero card**: was showing a decimal (e.g. `0.85`) instead of a whole number. Now displays an integer (e.g. `3200 RPM`).
- **G-sensor stuck at ±1.992 g**: the invalid-frame guard was rejecting `0xFF` but not `0xFE`. Both are now treated as invalid and ignored.

### Removed

- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`, and `POST_NOTIFICATIONS` permissions stripped from the manifest.

---

## [v2.0.1] — 2026-03-06

### Stability

- **WebSocket connection leak fixed**: The TCP socket was not always closed when a connection error occurred during setup. All exit paths now properly close the socket.
- **Background poller kept running after disconnect**: The extended diagnostic session poller was missing from the disconnect cleanup block, causing it to silently keep running after a disconnection. Now cancelled alongside all other pollers.
- **Concurrent state update race conditions fixed**: Live CAN data and OBD poll responses could clobber each other when written simultaneously, silently discarding one update. Debug line writes and settings saves had the same issue. All state writes are now atomic.

### Data & Sensors

- **False tire pressure warnings on missing/dead sensors**: Low-pressure alerts now only fire for tires with valid sensor data. Tires with a dead TPMS sensor battery or no sensor at all are excluded from the warning.
- **Stale sensor readings shown after reconnect**: Tire pressures, temperatures, and odometer from a previous session could persist on-screen for up to 30 seconds after reconnecting. All polled fields now reset to "pending" the moment a new connection starts.
- **PTU temperature showed 0 °C before first data arrived**: The transfer case temp defaulted to 0, which displays as a valid −60 °C reading and caused the Ready-to-Race warm-up banner to always flag PTU as cold at startup. Now shows "— —" until the first real reading arrives, matching RDU behavior.
- **Oil life % could exceed 100%**: Raw PCM byte was stored without range clamping. Now clamped to 0–100%.
- **CAN ID discovery stopped too early**: New CAN IDs stopped being logged after 40 unique IDs were seen in a session. Limit raised to 200, covering a full drive.

### UI & Display

- **Settings Save button permanently blocked when auto-reconnect is off**: The reconnect interval field was validated even when auto-reconnect was disabled and hidden. An empty or invalid value locked the Save button with no way to clear it. Validation is now skipped when auto-reconnect is off.
- **Diagnostic validation warnings could grow indefinitely**: The validation log embedded live sensor readings in warning keys, causing a new entry to be created per frame instead of deduplicating. Keys are now static category strings.

### Diagnostics

- **Sharing a diagnostic export could freeze the UI**: Building and zipping a large log file ran on the main thread. On long sessions this could trigger an ANR ("App Not Responding") dialog. Moved to a background thread.
- **Last SLCAN log lines could be lost on session end**: The log writer buffer was not flushed when a session ended, losing up to 999 buffered lines. Buffer is now flushed on every session end.
- **Diagnostic ZIPs accumulated on-device without limit**: Export files were never cleaned up and could fill device storage over time. A new **"Max saved ZIP exports"** setting in Settings → Diagnostics controls the limit (default: 5 — oldest are deleted automatically).
- **Steering angle and brake pressure missing from diagnostic frame report**: Both CAN IDs were decoded and shown in the app but absent from the exported frame inventory. Both are now included.

### Removed

- **Android Auto removed**: All Android Auto and aauto-sdk code has been stripped from the app. This feature was explored in an earlier build but never shipped. Removing it eliminates an unresolvable binary dependency and keeps the codebase clean. The phone app is completely unaffected.

### Added

- **Max saved ZIP exports setting**: New field in Settings → Diagnostics. Controls how many diagnostic ZIP files are kept on-device. Default: 5.

### Tracked (pending data)

- **12V battery voltage PID unconfirmed**: The battery voltage field is wired up in the app but not yet populated — the correct PID for this vehicle has not been confirmed from a live log. Tracked for a future release.
- **Drive mode Track/Drift byte order needs live confirmation**: The Track and Drift byte values in the drive mode decoder need to be verified against a live SLCAN log with known modes to rule out a swap. Tracked for a future release.

---

## [v2.0.0] — 2026-03-06

### Redesign — F1-Style Telemetry Interface

Complete visual and structural overhaul of the openRS_ Android app.

#### Design System — F1 Palette
- **New color palette**: Deep navy-black backgrounds (`#05070A`/`#0A0D12`/`#0F141C`), cyan primary (`#00D2FF`), neon green (`#00FF88`), gold (`#FFCC00`), orange-red (`#FF4D00`)
- **Orbitron font** added — hero numeric values (boost, RPM, speed, temperatures) now display in F1-inspired Orbitron Bold
- **JetBrains Mono** added — all labels and keys now use JetBrains Mono for a clean monospace look
- **Section labels** redesigned: small caps text with an extending horizontal rule

#### Tab Structure — 6 Tabs (drawer → MORE tab)
- Previous hamburger drawer replaced by a **MORE tab** (6th tab) — content is always visible without an overlay
- Tab bar: `⚡ DASH` / `◈ POWER` / `◎ CHASSIS` / `△ TEMPS` / `≡ DIAG` / `☰ MORE` — each with icon and label
- Tab height increased to 52dp; active tab highlighted with themed underline

#### DASH Tab — Hero Cards + Bar Grid
- **Hero cards** redesigned: Orbitron large number, unit label above, name below — BOOST (gold), RPM (orange-red), SPEED (cyan)
- **Bar cards** added: THROTTLE / BRAKE / FUEL / BATTERY each show a gradient progress bar beneath the value
- AWD split bar redesigned with percentage hero numbers on each side
- Quick temps (Oil, Coolant, Intake, Oil Life) + G-force mini row moved below AWD

#### TEMPS Tab — Configurable Thresholds
- **Threshold preset picker** added: STREET / TRACK / RACE modes. Each preset adjusts warn and critical thresholds for oil, coolant, intake, and RDU/PTU
- **Warm-up banner** now computes readiness using the selected preset (race = tighter, street = relaxed)
- Temperature cards show a proportional threshold bar at the bottom
- RTR banner uses gradient background (green = ready, gold = warming)

#### MORE Tab — All Drawer Content
- Drive mode, ESC status, openRS-FW features (LC / ASS), Module Status (RDU/PDC/FENG) — all previously in the drawer, now in the MORE tab
- **RS Theme Picker**: 6 RS factory paint colour themes — Nitrous Blue, Race Red, Tangerine Scream, Mean Green, Stealth, Moondust Silver. Theme changes the primary accent colour throughout the app in real-time
- Settings shortcut button at the bottom of MORE tab

#### MeatPi Pro Groundwork (v2.1+)
- `HardwareAdapter` interface created — generic contract for CAN-over-WiFi adapters with `connect`, `send`, `disconnect`, `frames`, and `gpsData` flows
- `MeatPiConnection` stub created — ready to implement TCP + NMEA GPS in v2.1 when hardware is available
- `GpsData` data class defined for GPS position, speed, heading, altitude

#### Emulators Synced
- `docs/index.html` fully rebuilt to match v2.0 design (new fonts, 6 tabs, hero cards, bar grid, AWD split, TPMS, TEMPS with preset picker, DIAG console, MORE with theme picker)
- `android/browser-emulator/index.html` synced to match

---

## [v1.2.9] — 2026-03-05

### Fixed — Odometer always visible; moved to extended diagnostic session

- **Odometer row always shown on Dash tab** — previously hidden until BCM responded; now shows `—` placeholder immediately on connect, populates when data arrives.
- **BCM DID 0xDD01 moved to extended session loop** — odometer query now opens a UDS extended session (`10 03`) on BCM (0x726) before issuing the Mode 22 read, matching the same pattern used for AWD/PSCM/FENG. Polled every 60 s (extJob) instead of the 30 s default-session BCM cycle.
- **Emulators synced** — both `docs/index.html` and `android/browser-emulator/index.html` updated to match the v1.2.8 UI (was left on v1.2.7 layout in the v1.2.8 release).

---

## [v1.2.8] — 2026-03-05

### Added — Extended diagnostic session polling (UDS 10 03 + Mode 22)

New polling loop (T+15 s after connect, 60 s cycle) opens a UDS extended diagnostic session (service `10 03`) per module before issuing Mode 22 reads. Required for confirmed Daft Racing DIDs.

| Feature | Module | Address | DID | Source |
|---------|--------|---------|-----|--------|
| RDU status (rear drive unit on/off) | AWD | `0x703 → 0x70B` | `0xEE0B` | Daft Racing `rset.py` ✓ |
| PDC status (pull drift compensation) | PSCM | `0x730 → 0x738` | `0xFD07` | Daft Racing `rset.py` ✓ |
| FENG status (fake engine noise generator) | `0x727 → 0x72F` | `0x72F` | `0xEE03` | Daft Racing `rset.py` ✓ |
| LC/ASS status (launch control & auto start-stop) | RSProt | `0x731 → 0x739` | Probed | Runtime DID discovery |

All three confirmed DIDs display live on/off status in the new **Module Status** section of the hamburger menu.

RSProt LC and Auto Start-Stop DIDs are currently probed at runtime (candidate DIDs `0xDE00`–`0xDE02`). Any positive response is logged to the diagnostic debug buffer for confirmation. Once verified, they will be hardcoded in a future release.

### Added — New PCM Mode 22 PIDs (9 additional)

All polled on the existing PCM header (`0x7E0 → 0x7E8`), 30 s cycle:

| Parameter | DID | Formula | Displayed in |
|-----------|-----|---------|--------------|
| AFR Actual (wideband) | `0xF434` | `((A×256)+B) × 0.0004486` | Power tab |
| AFR Desired | `0xF444` | `A × 0.1144` | Power tab |
| TIP Actual | `0x033E` | `((A×256)+B) / 903.81` kPa | Power tab |
| TIP Desired | `0x0466` | same | Power tab |
| VCT Intake Angle | `0x0318` | `(signed(A)×256+B) / 16` ° | Power tab |
| VCT Exhaust Angle | `0x0319` | same | Power tab |
| Oil Life | `0x054B` | `A` % | Power tab |
| HP Fuel Rail Pressure | `0xF422` | `((A×256)+B) × 1.45038` PSI | Power tab |
| Fuel Level (Mode 22) | `0xF42F` | `A × 100/255` % | Dash tab |

### Fixed — Fuel level reading

Passive CAN frame `0x380` (`FuelLevelFiltered`) can encode up to 102% per the DBC spec, causing the display to show "101%" on a full tank. Two fixes applied:
1. Passive decode now clamps to 100% max.
2. PCM Mode 22 DID `0xF42F` (clean 0–100% range) overwrites the passive value every 30 s.

### Added — HP Fuel Rail Pressure

Power tab "FUEL RAIL" cell now shows high-pressure direct-injection fuel rail pressure in PSI (DID `0xF422`, up to 3,500 PSI), replacing the legacy Mode 01 PID 22 low-pressure value.

### Added — Odometer with km / mi toggle

Odometer (from BCM DID `0xDD01`, already polled since v1.2.x) now displays in the Dash tab below the G-force row. Tap the cell to toggle between kilometres and miles.

### Changed — Hamburger Features section

Launch Control and Auto Start-Stop cards now show live RSProt probe status:
- **ARMED / STANDBY / PROBING** for Launch Control
- **ACTIVE / OFF / PROBING** for Auto Start-Stop
- LC RPM target displayed when known (variable per user tune)

---

## [v1.2.7] — 2026-03-04

### Fixed — Drive mode Track/Drift corrected (requires firmware v1.3)

DBC cross-reference (`VAL_ 432 DriveMode`) and live log confirmation proved the nibble encoding is:

| Byte 6 (upper nibble) | Mode   |
|-----------------------|--------|
| 0x0_                  | Normal |
| 0x1_                  | Sport  |
| 0x2_                  | **Drift** |
| 0x3_                  | **Track** |

Previous releases had Track and Drift swapped. This caused "Track" to display as Drift and "Drift" to display as Track when either mode was active.

### Fixed — Connection stability (frequent red-light disconnects)

Root cause: `BCM_POLL_INTERVAL_MS` was lowered from 30 s to 10 s in v1.2.6, tripling OBD TX frequency. Combined with concurrent PCM polling, the WiCAN's TWAI TX queue was saturating at ~1882 fps passive CAN load, causing the passive stream to stall and triggering the 10 s read timeout repeatedly.

Changes:
- `BCM_POLL_INTERVAL_MS` reverted to **30 s**
- `PCM_POLL_INTERVAL_MS` set to **30 s**, initial delay offset to **T+20 s** so BCM and PCM cycles never overlap
- `soTimeout` increased from 10 s to **20 s** as a safety margin for WiCAN startup noise
- `PCM_QUERY_GAP_MS` increased from 150 ms to **200 ms**

### Removed — Battery voltage OBD query

Mode 01 PID 0x42 ("Control module voltage") is not supported by the Focus RS PCM. The query has been removed. The battery voltage UI field is retained for future investigation.

---

## [v1.2.6] — 2026-03-01

### Changed — TPMS polling interval reduced to 10 seconds

BCM Mode 22 TPMS polling (`BCM_POLL_INTERVAL_MS`) reduced from 30 s to 10 s. No bus load implications — Ford diagnostic tools poll far more aggressively. Tire pressure readings now refresh every ~12 seconds (10 s interval + ~2.4 s to cycle through all 8 BCM queries at 300 ms gaps).

---

## [v1.2.5] — 2026-03-01

### Fixed — Drive mode Track/Drift now display correctly

Reverted drive mode source from `0x17E` back to `0x1B0`, but with a correctly targeted extraction. Deep log analysis of a 20-minute session (including a brief Track mode segment) proved `0x17E` byte 0 lower nibble **stays at 1 (Sport) even while the car is in Track** — making it unreliable beyond Normal/Sport. The correct source is `0x1B0` byte 6 upper nibble, read only when byte 4 == `0x00` (steady-state frames). Button-event frames (byte 4 ≠ 0) are ignored to prevent transient flicker.

| Byte 6 (upper nibble) | Mode   |
|-----------------------|--------|
| 0x0_                  | Normal |
| 0x1_                  | Sport  |
| 0x2_                  | Drift  |
| 0x3_                  | Track  |

### Fixed — Gear indicator removed

The gear position field (CAN ID `0x230`) does not broadcast on this vehicle. The gear display widget has been removed from the UI.

### Fixed — TPMS formula switched to exportedPIDs source of truth

Updated BCM Mode 22 TPMS formula from the DigiCluster `can0_hs.json` formula to the community-validated Focus RS formula from `exportedPIDs.txt`:

> PSI = `(((256×A)+B) / 3.0 + 22.0/3.0) × 0.145`

TPMS now displays one decimal place (e.g. `42.3 PSI`) to reveal variance between tires and aid in validating sensor readings.

Passive TPMS decoding from CAN `0x340` bytes 2–5 has been removed entirely. `0x340` is `PCMmsg17` on HS-CAN — those bytes are PCM engine signals, not tire pressure. TPMS comes exclusively from BCM Mode 22 polling (PIDs `0x2813`–`0x2816`).

### Fixed — Fuel level now reads from correct CAN ID

`0x34A` was never present in logs. The correct source is `0x380` (`PCMmsg30`, `FuelLevelFiltered`), a 10-bit Motorola big-endian signal with factor 0.4 %. Live log confirmed: raw=254 → 101.6 % (full tank).

### Fixed — Battery voltage now polled via OBD-II

12V battery voltage does not broadcast on HS-CAN. Removed the stale `0x3C0` passive decoder. Battery voltage is now polled from the PCM via standard OBD-II **Mode 01 PID `0x42`** ("Control module voltage"), formula: `(A×256+B) / 1000 V`.

### Note — Firmware v1.2 required

Firmware v1.2 corrects the same `0x17E` vs `0x1B0` drive mode bug on the hardware side. See [firmware release fw-v1.2.0](https://github.com/klexical/openRS_/releases/tag/fw-v1.2.0).

---

## [v1.2.4] — 2026-03-01

### Fixed — TPMS formula aligned to DigiCluster reference

Replaced the `exportedPIDs.txt` formula `(((A×256)+B)/3 + 22/3) × 0.145` with the DigiCluster `can0_hs.json` formula `((A×256)+B) / 2.9 × 0.145038`. Both produce the same result at real-world pressures (< 0.1 PSI difference at 35 PSI) but the DigiCluster formula is the explicit reference implementation for this vehicle with no offset term.

### Note — Firmware v1.1

Firmware v1.1 is required for the firmware badge in the app to show `openRS_ v1.1` instead of `WiCAN stock`. The firmware now:
- Responds to the Android app's `OPENRS?` WebSocket probe with `OPENRS:v1.1`
- Reads steady-state drive mode from `0x17E` byte 0 lower nibble (was incorrectly reading from `0x1B0` button event frames)

See [firmware release fw-v1.1.0](https://github.com/klexical/openRS_/releases/tag/fw-v1.1.0) for flash instructions.

---

## [v1.2.3] — 2026-03-04

### Fixed — Drive mode always showing Normal

The drive mode badge was reading from CAN ID `0x1B0` byte 6 lower nibble. Analysis of a 16-minute, 1.9M-frame live log revealed this is wrong: `0x1B0` byte 6 only changes during dial-turn **transition events** (lasting 50–200 ms), not while a mode is held. Frames counted by mode: Normal 30,642 vs Sport 546 — but separately, `0x17E` showed Normal 547 / Sport 8,824, matching the actual 16-minute session where the driver was in Sport for ~15 minutes.

**Root cause:** `0x1B0` carries AWD/mode transition data; it reverts to Normal between mode-dial clicks. The RS_HS.dbc contains `BO_ 382 x17E` with `SG_ DriveModeRequest : 3|4@0+ (1,0) [0|15]` — the lower nibble of byte 0 is the steady-state selected mode.

**Fix:** Replaced `ID_AWD_MSG = 0x1B0` with `ID_DRIVE_MODE = 0x17E`. Decoder: `data[0].toInt() and 0x0F`.

| Value | Mode   |
|-------|--------|
| 0     | Normal |
| 1     | Sport  |
| 2     | Track  |
| 3     | Drift  |

### Fixed — TPMS only showing one tire (LR)

The passive CAN `0x340` frame (bytes 2–5, per RSdash/DigiCluster `can1_ms.json`) is bridged by the Gateway Module to HS-CAN, but the GWM on this car only populates byte 4 (LR). The other three tire pressures remain on MS-CAN only and are not accessible via the OBD port passively.

**Fix:** Added BCM Mode 22 polling for all four tire pressures using PIDs 0x2813 (LF), 0x2814 (RF), 0x2816 (LR), 0x2815 (RR). Formula from `exportedPIDs.txt`:

```
PSI = (((256×A)+B)/3 + 22/3) × 0.145
```

Validated: LR via BCM should agree with the passive CAN `0x340` byte 4 reading (35 PSI in the log). Polls run every 30 s alongside the existing BCM cycle. Passive CAN `0x340` continues to provide real-time LR updates between polls.

Also added sentinel guards in `CanDataService.onObdUpdate` to prevent a BCM response captured before the first passive CAN frame from resetting tire pressures back to the `−1.0` default.

---

## [v1.2.2] — 2026-03-04

### Fixed — Gear display always showing Neutral

The Focus RS does not broadcast gear position on the passive HS-CAN bus (`0x230` is absent from every observed log — 1.9 M frames over 16.8 minutes). The `gear` field in `VehicleState` therefore never updated from its default of `0` (Neutral).

**Fix:** Added `derivedGear: Int` as a computed property on `VehicleState`. It calculates the current gear from RPM ÷ vehicle speed using the known Focus RS Getrag MT-82 final-drive ratio and 235/35R19 tire circumference:

```
ratio = rpm × 0.03194 / speedKph
  where 0.03194 = circumferenceM(2.033) × 3.6 / (60 × finalDrive(3.82))
```

Gear thresholds (midpoints between empirically measured values from a live log):

| Gear | Measured ratio | Threshold |
|------|---------------|-----------|
| 1st  | ≈ 3.79        | ≥ 2.99    |
| 2nd  | ≈ 2.18        | ≥ 2.03    |
| 3rd  | ≈ 1.89        | ≥ 1.60    |
| 4th  | ≈ 1.30        | ≥ 1.00    |
| 5th  | ≈ 0.85        | ≥ 0.74    |
| 6th  | < 0.74        | —         |

Returns `N` when speed < 3 kph or RPM < 400, and `R` when `reverseStatus` is set.

`gearDisplay` prefers the CAN-sourced `gear` field (for future compatibility if `0x230` ever appears) and falls back to `derivedGear`.

---

## [v1.2.1] — 2026-03-04

### Fixed — Decoder Bug: Yaw Rate, Steering Angle, Brake Pressure

Three CAN decoder signals were producing physically impossible values due to a mismatch between the `bits()` helper function and the bit numbering convention used for these specific signals in the RS_HS.dbc.

**Root cause:** The `bits()` helper uses MSB-first network bit addressing (bit 0 = MSB of byte 0), which is correct for signals like torque, ESC mode, and AWD torque. However, yaw rate (`35|12`), steering angle (`54|15`), steering sign (`39|1`), and brake pressure (`11|12`) were authored with the standard DBC LSB-first Motorola convention. Using the wrong extractor produced:
- **Yaw rate:** +37.5 °/s on a stationary vehicle (correct value: ≈ 0 °/s)
- **Steering angle:** ±239° while parked straight (correct value: ≈ 7.5° off-center)
- **Brake pressure:** underreported by ~50% at equivalent pedal application

**Fix:** Replaced `bits()` calls with manual `(byteN & mask) << shift | byteM` extraction for the three affected signals, matching the pattern already used for lateral G, longitudinal G, coolant temp, IAT, RPM, and wheel speeds. All corrected formulas validated against a 16-minute live log (1,911,479 frames):
- Yaw rate now reads ≈ 0 °/s at rest, rising to +45 °/s during a documented U-turn at 17 kph — cross-checked against the lat-G/speed/yaw triangle (≈ 4% error)
- Steering angle shows ≈ −360° during full-lock left turn and ≈ +83° during parking manoeuvre — both physically consistent
- Brake pressure confirmed at 22.3% for an initial brake application captured at session start

Updated `bits()` docstring to clarify its MSB-first convention and which signals require it vs manual extraction.

---

## [v1.2.0] — 2026-03-04

### Redesign — Full UI/UX Overhaul

Complete visual and structural redesign of the app. The previous 8-tab layout has been consolidated into a more focused, information-dense 5-tab design with a System Drawer for controls.

**Tab structure:**
- **DASH** — Hero boost/RPM/speed gauges, 8-cell data grid, AWD split bar with animated gradient, G-force row
- **POWER** — AFR hero cards, throttle & boost grid (ETC, WGDC, TIP), engine management (timing, load, OAR, KR, VCT), fuel trims & misc
- **CHASSIS** — AWD section (4 wheel speeds + torque bar + deltas), G-Force section (yaw, steering, peaks + inline reset), TPMS section with car outline graphic
- **TEMPS** — RTR banner (warming up vs race ready with animated dot), 10 temp cards in a 2-col grid, each with a color indicator bar at base
- **DIAG** — Diagnostic snapshot, live CAN output, frame inventory (unchanged from v1.1.6)
- **☰ System Drawer** — Drive mode (read-only), ESC status (read-only), Features (firmware-gated), Connection & Diagnostics with snapshot button

### Added — New Font System
- Embedded **Share Tech Mono** (numeric readouts, data values, technical labels)
- Embedded **Barlow Condensed** (UI labels, section headers, button text)
- All fonts offline-embedded — no network dependency

### Added — New CAN Signals
- **Steering wheel angle** from `0x010` (`SASMmsg01`) — DBC-verified: `(byte6&0x7F)<<8|byte7 × 0.04395°`, signed via `byte4 MSB` *(formula corrected in v1.2.1)*
- **Yaw rate** from `0x180` (`ABSmsg02`) — decoded alongside existing lateral G: `(byte4&0x0F)<<8|byte5 × 0.03663 − 75 °/s` *(formula corrected in v1.2.1)*
- **Brake pressure** from `0x252` (`ABSmsg10`) — DBC-verified: `(byte1&0x0F)<<8|byte2` raw 0–4095, displayed 0–100% (bar calibration pending from live log) *(formula corrected in v1.2.1)*

### Added — PCM Mode 22 Polling
New ISO-TP polling cycle to PCM (`0x7E0` → response `0x7E8`) every 10 seconds:
- **ETC Actual** (`0x093C`) — electronic throttle actual angle
- **ETC Desired** (`0x091A`) — electronic throttle desired angle
- **Wastegate DC** (`0x0462`) — turbo wastegate duty cycle %
- **Knock Retard Cyl 1** (`0x03EC`) — ignition correction cyl 1 (°)
- **Octane Adjust Ratio** (`0x03E8`) — knock learning OAR
- **Charge Air Temp** (`0x0461`) — charge air cooler outlet (°C) — verified via DigiCluster can0_hs.json
- **Catalyst Temp** (`0xF43C`) — catalytic converter temp (°C) — verified via DigiCluster can0_hs.json

### Updated — Ready-to-Race Thresholds
RTR banner now uses safe conservative warm-up thresholds with status text showing which sensors are still cold:
- Engine Oil ≥ 80 °C (was 65 °C)
- Coolant ≥ 85 °C (new)
- RDU ≥ 30 °C (was 20 °C)
- PTU ≥ 40 °C (was 50 °C)

### Updated — Header
- **Pulsing connection dot** replaces text-based LIVE/OFFLINE indicator
- **Drive mode badge**, **gear number**, **ESC status** all visible at a glance
- Speed removed from header (shown prominently on DASH tab hero row)
- ⚙ Settings gear retained

---

## [v1.1.6] — 2026-03-01

### Fixed — TPMS formula (tires showing no data)
- The TPMS pressure bytes in CAN frame `0x340` use a **3.6 kPa per unit** encoding, not direct PSI.
- Formula corrected to: **PSI = raw × 3.6 ÷ 6.895** (e.g. raw `0x43` = 67 → 35.0 PSI).
- The previous decoder treated raw bytes as PSI directly and capped acceptance at 60, causing a raw value of 67 (which represents a perfectly normal ~35 PSI tire in winter) to be rejected entirely — all four tires showed blank.
- Valid result range updated to **5–80 PSI** (converted, not raw). Sleeping sensors (raw = 0) correctly display no data and retain last known pressure.

### Fixed — Firmware detection always showing "WiCAN stock" despite openRS_ firmware
- The previous probe used a **20-frame grace window** to wait for the `OPENRS:` response. At ~1,700 fps, those 20 frames are consumed in approximately **12 milliseconds** — before the firmware can even process the probe command and reply.
- Replaced the frame-count check with a **3-second elapsed-time window**. The app now continues checking every incoming frame for the `OPENRS:` identifier for up to 3 seconds before falling back to "WiCAN stock."
- Users with openRS_ firmware will now see correct firmware detection in the DIAG tab and diagnostic logs.

---

## [v1.1.5] — 2026-03-01

### Added — SLCAN raw frame log (Option C)
- Every CAN frame received during a session is now written in real-time to a `slcan_log_*.log` file inside the diagnostic ZIP.
- Format is standard **candump** (compatible with SavvyCAN, Kayak, python-can): `(relative_seconds) can0 ID#DATA`.
- This makes it possible to import the log into SavvyCAN or Kayak after a drive and replay or decode frames offline — no more relying on a single end-of-session snapshot.
- Logging is capped at **2 000 000 lines** (~83 min at 400 fps average). A session event is emitted when the cap is reached.
- The ZIP share email now mentions the SLCAN file and its frame count.

### Added — Per-ID first/last/changed tracking + periodic samples (Option B)
- `FrameInfo` in the diagnostic logger now stores:
  - `firstRawHex` — the raw bytes seen on the **very first** observation of each CAN ID.
  - `hasChanged` — a `true/false` flag indicating whether the frame bytes **ever changed** during the session. Static IDs (e.g. configuration frames that never move) are clearly distinguished from dynamic signals (RPM, boost, temperatures).
  - `periodicSamples` — up to **10 raw-hex snapshots**, taken once every **30 seconds** per ID, across the full session. These show how a signal's bytes evolved over time during a drive.
- The human-readable summary now includes a **PERIODIC SAMPLES** section listing all dynamic (changed) IDs with their timestamped snapshots, making it possible to see values from mid-drive rather than only the parked end-state.
- The JSON detail file now includes `firstRawHex`, `hasChanged`, and a `periodicSamples` array per frame ID.

### Increased — Diagnostic capacity limits (previously caused data loss on longer drives)
| Buffer | Old limit | New limit | Coverage at typical rates |
|---|---|---|---|
| Decode trace | 500 events | **10 000 events** | ~100 s of decoded frames |
| Session events | 300 | **1 000** | Full-session event history |
| FPS timeline | 200 samples | **7 200 samples** | **2 hours** at 1 sample/s |

---

## [v1.1.4] — 2026-03-04

### Fixed — Boost formula (critical: was reading engine oil temp as boost)
- CAN frame `0x0F8` (DBC: `PCMmsg07`) carries **three** signals, not two.
  - `byte[1]` = **EngineOilTemp** — `raw − 50 °C` ← was being decoded as boost (wrong!)
  - `byte[5]` = **Boost** — `raw × 0.01 bar gauge`; stored as absolute kPa = `raw + barometricPressure`
  - `byte[7]` = **PTUTemp** — `raw − 60 °C` ← was labelled "oilTemp" (wrong!)
- v1.1.3 accidentally moved the boost read to `byte[1]`, which is actually engine oil temp. This update restores `byte[5]` as boost (now gauge + baro = absolute) and correctly separates all three signals per the RS_HS.dbc.
- At idle `byte[5] = 0x00` = 0 kPa gauge = 0 PSI boost (no turbo activity) — this is now correctly displayed as ~0 PSI instead of −14.7 PSI.

### Fixed — Engine oil temp was wrong (was showing PTU temp)
- Oil temp field was reading `byte[7] − 60`, which is the **PTU (transfer case) temperature**.
- Corrected to `byte[1] − 50` (engine oil temp per DBC). PTU temp now correctly reads `byte[7] − 60`.
- Before: "OIL 60°C" was actually PTU temperature. Now both values are correctly separated.

### Fixed — Wheel speed CAN ID wrong (0x215 → 0x190)
- Wheel speeds were mapped to CAN ID `0x215`, which does not exist on the Focus RS HS-CAN bus.
- RS_HS.dbc `ABSmsg03` at ID `0x190` carries all four wheel speeds. This ID was visible in diagnostics (67 743 frames logged) but never decoded.
- Formula corrected to 15-bit Motorola MSB-first, `× 0.011343006 km/h` per DBC (was: 16-bit `− 10 000 × 0.01`).
- FL/FR/RL/RR now correctly decoded from `((data[N] & 0x7F) << 8) | data[N+1]`.

### Added — Intake Air Temperature (IAT) from CAN 0x2F0
- `IntakeAirTemp : 49|10@0+ (0.25,−127)` added alongside coolant in the `0x2F0` decoder.
- Formula: `((data[6] & 0x03) << 8 | data[7]) × 0.25 − 127 °C` (RS_HS.dbc PCMmsg16 verified).
- Displayed in the TEMPS tab alongside coolant.

### Added — Ambient temperature from CAN 0x340 (PCMmsg17)
- RS_HS.dbc `PCMmsg17` at `0x340` carries `AmbientAirTemp : 63|8@0−` in byte 7 (`signed × 0.25 °C`).
- The ambient temp is now decoded alongside the existing TPMS bytes 2-5 decode from the same frame.
- This provides ambient temp data without needing the MS-CAN bus.

### Added — RDU temperature via AWD module Mode 22 polling
- Active ISO-TP query now sent to AWD module (`0x703`) every 30 s, polling PID `0x1E8A` (RDU oil temp).
- Formula: `B4 − 40 °C` (source: research/exportedPIDs.txt + DaftRS log_awd_temp.py).
- Response intercepted on CAN ID `0x70B`. RDU temp was previously read from `0x2C0` byte 3 (always 0 at idle = −40°C); that was wrong — the signal is not in any passive broadcast.
- `rduTempC` default changed from `0.0` to `−99.0` (consistent with other "not yet polled" fields).

### Removed — Dead PTU decoder on 0x2C2
- `ID_PTU_TEMP = 0x2C2` never appears on the HS-CAN bus. PTU temperature is now correctly sourced from `0x0F8` byte 7. The dead decoder and constant have been removed.

---

## [v1.1.3] — 2026-03-04

### Fixed — Boost sensor reading always −14.7 PSI
- Boost (MAP kPa absolute) was being decoded from byte 5 of CAN frame `0x0F8`, which is always `0x00` on the Focus RS MK3. Diagnostic log analysis confirmed byte 1 carries the manifold absolute pressure and tracks correctly across sessions: `0x3D` (61 kPa → −5.8 PSI at cold idle) and `0x57` (87 kPa → −2.1 PSI at warm idle). Formula corrected to `ubyte(data, 1)`.

### Fixed — Firmware detection showing "WiCAN stock" when openRS_ firmware is installed
- The probe (`OPENRS?\r`) response was checked only on the **very first** WebSocket frame. At ~2000 frames/sec, the first frame is almost always a CAN frame, not the probe reply, so it was immediately classified as stock. Now checks every incoming frame until the probe response is received OR 20 normal SLCAN frames pass without a response, at which point it falls back to "WiCAN stock".

### Fixed — 34 duplicate boost validation warnings in diagnostic log
- The validation issue string included the specific kPa value (`boostKpa=37 — too low`), causing the `LinkedHashSet` to grow to 34 entries for each unique vacuum reading at idle. Changed to a single fixed string (`boostKpa=0 — MAP sensor may be disconnected`) that only fires when MAP reads exactly 0, which is the only truly impossible value for a running engine.

### Added — App version in diagnostic log and share email
- Diagnostic text summary now shows `App: vX.Y.Z (build N)` in the header.
- JSON `meta` block now includes `appVersion` and `appBuild` fields.
- Share email body now includes `App: vX.Y.Z (build N)` for easy version identification when reporting issues.

---

## [v1.1.2] — 2026-03-04

### Fixed — Drive mode off-by-one
- Drive mode was decoded from the **upper nibble** of CAN frame `0x1B0` byte 6 (`ushr 4`), which carries the **previous/transitioning** mode. The correct signal is in the **lower nibble** (`and 0x0F`).
- Root cause confirmed via diagnostic dump: in Track mode the car sends byte 6 = `0x12` — upper nibble 1 (Sport, wrong) / lower nibble 2 (Track, correct). Selecting Drift sent `0x23` — showing Track (upper) instead of Drift (lower).
- Symptom: every mode change showed one mode behind (Normal→Normal ✓, Sport→Normal ✗, Track→Sport ✗, Drift→Track ✗).

### Fixed — TPMS invalid readings at standstill
- TPMS sensors sleep when the vehicle is stationary; stale or noise CAN frames could appear with obviously wrong values (e.g. 67 PSI on a single sensor).
- Added per-sensor valid-range check: only accept readings in **5–60 PSI**. Out-of-range bytes are discarded and the previous stored value is retained for that sensor.
- An all-zero frame (all sensors outside range) no longer resets displayed pressures; last known good values are preserved until the sensors wake up.

### Fixed — Browser emulator not updated alongside v1.1.1 app changes
- DASH tab (phone + AA): new info row added showing **ODO** (odometer km) and **SOC** (battery %) alongside 12V voltage and drive mode — matching the Android app's layout.
- TEMPS tab (phone + AA): two new BCM temperature gauges added — **CABIN** and **BATT TEMP** — with `(BCM)` source labels, showing `--` until the first 30 s poll completes, matching the Android app display.
- AWD tab: PTU and RDU temperatures now use `displayTemp()` + `tempLabel()` to respect the user's °C/°F setting (were hardcoded `toF()°F`).
- Demo state seed updated with representative values for `odometerKm`, `batterySoc`, `cabinTempC`, `batteryTempC`.
- `tempGauge()` helper updated to accept an optional subtitle argument for source labels like `(BCM)` and `(BCM — polling)`.

---

## [v1.1.1] — 2026-03-01

### Added — BCM OBD Mode 22 polling
- Active ISO-TP queries now sent to BCM (CAN address `0x726`) every 30 s via SLCAN, unlocking 4 new data points from the MeatPi Focus RS MK3 vehicle profile:
  - **Odometer** (`0xDD01`) — displayed on DASH tab as `ODO km`
  - **Battery SOC** (`0x4028`) — displayed on DASH tab as `SOC %` (12V start/stop battery)
  - **Battery Temp** (`0x4029`) — displayed on TEMPS tab with BCM label
  - **Cabin Temp** (`0xDD04`) — displayed on TEMPS tab with BCM label
- `WiCanConnection` changed from `flow {}` to `channelFlow {}` to allow OBD poller coroutine to run concurrently with the passive CAN receive loop
- `sendWsText` / `sendWsPong` now use `synchronized(out)` to prevent stream interleaving between OBD poller and keep-alive pong responses
- New `parseBcmResponse()` function decodes ISO-TP single-frame responses on CAN ID `0x72E` using MeatPi-verified formulas

### Fixed — Browser emulator
- Settings overlay moved outside `#phone` container so it renders correctly when the Android Auto tab is active (was previously trapped inside the hidden phone div)
- Android Auto DIAG screen added with full parity to phone DIAG tab (session stats, frame inventory, snapshot button)
- Android Auto `⚙` settings gear icon wired to the shared settings overlay
- Firmware-locked feature toggles in AA CTRL panel now flash red and refuse to toggle when Stock firmware is simulated

---

## [v1.1.0] — 2026-03-04

### Changed — Architecture (breaking)
- **WebSocket SLCAN replaces ELM327 TCP** — the app now connects to the WiCAN via WebSocket on port 80 (`ws://192.168.80.1:80/ws`) using the SLCAN protocol instead of ELM327 TCP on port 3333. This provides passive monitoring of the full CAN bus at ~2100 fps vs ~12 fps previously. No WiCAN configuration change is required; the WebSocket endpoint is available in stock WiCAN firmware.
- **Preference key renamed** — the saved port preference key changed from `wican_port` to `wican_port_ws` to prevent old cached ELM327 port (3333) from being used after upgrade.

### Added — Settings
- Full settings dialog accessible via gear icon in the header
- **Speed unit** — MPH or KPH
- **Temperature unit** — °F or °C
- **Boost pressure unit** — PSI, BAR, or kPa
- **Tire pressure unit** — PSI or BAR
- **Low tire pressure warning threshold** — user-defined PSI (default 30 PSI)
- **Keep screen on** — prevents screen sleep while connected (default: on)
- **Auto-reconnect** — automatically reconnects after a dropped connection (default: on)
- **Reconnect interval** — configurable delay in seconds (default: 10s)
- All settings persist across app restarts via SharedPreferences

### Added — CAN Decoders
- **TPMS (0x340)** — tire pressures LF/RF/LR/RR decoded directly from bytes 2-5 in PSI; this frame is broadcast on MS-CAN and bridged to HS-CAN by the Gateway Module (GWM). No OBD queries required.
- **Ambient temperature (0x1A4)** — byte 4 signed × 0.25 °C, MS-CAN bridged
- **Barometric pressure** — added to existing `0x090` frame (byte 2 × 0.5 kPa)

### Fixed — CAN Decoders
- **Drive mode (0x1B0)** — corrected bit extraction to `(byte6 ushr 4) & 0x0F`; was reading wrong bit positions causing drive mode to always show Normal regardless of actual mode
- **E-brake (0x0C8)** — corrected bit mask to `(byte3 & 0x40) != 0`
- **AWD max torque (0x2C0)** — fixed formula to prevent negative values
- Removed `0x0B0` (was producing impossible G-force values — not a confirmed dynamics frame)
- Removed steering angle and brake pressure from `0x080` (not present in this frame per DigiCluster)
- All formulas re-validated against DigiCluster `can0_hs.json` and `can1_ms.json`

### Added — openRS_ firmware detection
- On connection, the app sends `OPENRS?\r` over WebSocket and checks the first response for `OPENRS:<version>`. Stock WiCAN firmware produces no response; openRS_ firmware confirms itself with its version string.
- CTRL tab feature buttons (Launch Control, Auto S/S Kill) are unlocked when openRS_ firmware is detected
- "Coming soon" snackbar only shown when running stock WiCAN firmware

### Added — Diagnostics (DIAG tab)
- New **DIAG** tab (renamed from DEBUG) with full session diagnostics
- `DiagnosticLogger` — collects frame inventory, decode trace (last 500 events), session events, and FPS timeline
- `DiagnosticExporter` — packages all data into a ZIP (human-readable `summary.txt` + machine-readable `detail.json`) and shares via Android share sheet
- **⬆ CAPTURE & SHARE SNAPSHOT** button for one-tap export
- Frame inventory shows every CAN ID seen, frame count, last raw hex, decoded values, and any validation warnings
- Validation engine flags physically impossible values (e.g. RPM > 9000, oil temp < −50°C)

### Added — Unit-aware UI
- All pages (DASH, PERF, TEMPS, TUNE, TPMS) now display values in the user's preferred units
- TPMS tire pressure low-alert threshold is now the user-configured value (not hardcoded 30 PSI)

### Added — Screen management
- `view.keepScreenOn` tied to `prefs.screenOn && vs.isConnected` — screen stays on while driving when enabled in settings

### Removed
- `ObdPids.kt` — dead code; OBD polling path fully replaced by passive WebSocket SLCAN

---

## [v1.0.2] — 2026-03-03

### Fixed
- **ATMA frame parsing** — WiCAN ELM327 outputs CAN frames with spaces (e.g. `1B0 00 11 22 33 44 55 66 77`); the parser now strips spaces before hex validation so all gauge telemetry is received and decoded correctly

---

## [v1.0.1] — 2026-03-03

### Added
- **Smart auto-connect** — service auto-starts on launch when already on WiFi; `ConnectivityManager.NetworkCallback` triggers a fresh connection attempt whenever WiFi is (re)gained
- **Exponential backoff with max 3 attempts** — on failure the app waits 5 s → 15 s → 30 s between retries then gives up (was: infinite retry loop)
- **Idle state** — after 3 consecutive failed TCP connections the service stops retrying; `VehicleState.isIdle` propagates this to the UI
- **WiFi gating** — connection attempts are skipped when on mobile data only; shows "No WiFi" notification
- **`reconnect()` method** on `CanDataService` — resets attempt counter and starts fresh; called from the header RETRY badge
- **Three-state header badge** — `● LIVE` (green) when connected, `⊙ RETRY` (gold) when idle, `○ OFFLINE` (red) otherwise; tapping any badge performs the correct action

### Fixed
- Continuous connect/disconnect notification spam when MeatPi is not present or phone is not on the WiCAN network

---

## [v1.0.0] — 2026-03-01

### Added
- **TPMS screen** — Real tire pressure (PSI) and temperature via BCM Mode 22
- **AFR actual/desired** — Wideband lambda from PCM with AFR display
- **Electronic Throttle Control** — ETC actual vs desired angle
- **Throttle Inlet Pressure** — TIP actual vs desired (kPa → PSI)
- **Wastegate Duty Cycle** — WGDC desired percentage
- **Variable Cam Timing** — VCT intake and exhaust angles
- **Oil life percentage** and **knock correction** via PCM
- **Multi-ECU header management** — Automatic ATSH switching (PCM `0x7E0`, BCM `0x726`)
- **CTRL screen** — Drive mode (N/S/T/D), ESC toggle, Launch Control, ASS kill, connection info
- **Settings dialog** — WiCAN host/port configurable from within the app
- **Android Auto support** — full Compose UI mirroring the phone app (7 screens)
- **openRS_ branding** — Nitrous Blue / Frost White theme, launcher icon, app name
- **Edge-to-edge layout** — proper status bar and navigation bar inset handling (Android 15+)
- Foreground service with persistent notification and peak tracking (boost, RPM, G-force)
- 33 OBD PIDs across Mode 1 and Mode 22 (PCM + BCM)
- 16+ real-time CAN frame decoders (RPM, boost, speed, AWD split, G-forces, wheel speeds, drive mode, ESC, gear, TPMS)

### Architecture
- Hybrid ATMA + OBD time-sliced polling — continuous CAN sniffing with 4 Hz OBD queries
- Single WiCAN-USB-C3 adapter via ELM327 TCP on port 3333
