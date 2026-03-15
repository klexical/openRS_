# Changelog

All notable changes to the openRS_ Android app are documented here.
Firmware changes are tracked separately in [firmware releases](https://github.com/klexical/openRS_/releases).

> **Note on tab names:** The app's tab structure has evolved over time. v1.0–v1.1 used DASH/PERF/TEMPS/TUNE/TPMS/CTRL/DIAG. v1.2.0 redesigned to DASH/POWER/CHASSIS/TEMPS/DIAG + System Drawer. v2.0.0 replaced the drawer with a MORE tab, giving the current 6-tab layout: DASH/POWER/CHASSIS/TEMPS/DIAG/MORE. Historical entries below use the tab names that were current at the time of each release.

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
