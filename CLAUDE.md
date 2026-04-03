# openRS_

Native Android telemetry dashboard for the Ford Focus RS MK3.
Kotlin 2.0.21 + Jetpack Compose (Material3). Current: v2.2.6 (versionCode 32).
Android app → `android/`  |  Firmware → `firmware/`  |  Sapphire (web dashboard) → `web/`  |  Docs → `docs/`  |  Feature pipeline → `docs/feature-roadmap.md`

## Source Layout

All source under `android/app/src/main/java/com/openrs/dash/`:

```
OpenRSDashApp.kt          — App singleton: vehicleState: MutableStateFlow<VehicleState>,
                            debugLines, isOpenRsFirmware, firmwareVersionLabel, driveDb, driveRecorder, driveState
can/
  CanDecoder.kt           — 22 passive HS-CAN decoders. decode(id,data,state)→VehicleState?
                            Call resetSessionState() on EVERY new connection (resets modeDetail420 + has420Arrived)
                            has420Arrived gate: Sport/Track resolution blocked until first 0x420 frame received
                            modeDetail420Hex: public accessor for diagnostics logging
  ObdConstants.kt         — All SLCAN query strings, ECU request/response IDs, poll intervals
  ObdResponseParser.kt    — Mode 22 DID dispatch by module (PCM/BCM/AWD/PSCM/FENG/RSProt/HVAC/IPC)
  PidRegistry.kt          — Data-driven catalog decoder; builds index from ForscanCatalog on first load
                            Fallback for ObdResponseParser when DID not hardcoded; stores in genericValues
  SlcanTransport.kt       — Transport interface: open/readLine/writeLine/close
  TcpSlcanTransport.kt    — MeatPi Pro: raw TCP 192.168.0.10:35000
  WebSocketSlcanTransport.kt — MeatPi USB: WebSocket ws://192.168.80.1:80/ws
  BleSlcanTransport.kt    — BLE GATT transport: service 0xFFE0, RX char FFE1, TX char FFE2
                            Auto-reconnect (autoConnect=true after first success), MTU 247 w/ fallback
  BleDeviceScanner.kt     — BLE device discovery filtered to service UUID 0xFFE0
                            WARNING: 0xFFE0 is shared by many cheap BLE devices (LED controllers, HM-10 clones)
  SlcanConnection.kt      — Shared connection: retry, SLCAN init, firmware probe, OBD pollers,
                            frame dispatch, DTC scan/clear, ISO-TP reassembly, FPS tracking.
                            SLCAN handshake: waits 3s for valid frame after init before declaring Connected
                            Constructor takes transportFactory: () -> SlcanTransport
                            Command response channel routes +FRS: lines separately from SLCAN frames
  AdapterState.kt         — Disconnected / Connecting / Connected / Idle / Error (sealed class)
  SlcanParser.kt          — SLCAN frame tokenizer ('t'=standard, 'T'=extended)
  IsoTpBuffer.kt          — ISO-TP SF/FF/CF reassembly; caller sends BCM_FLOW_CONTROL after FF
  FirmwareApi.kt          — FirmwareCommandSender interface + WiFiFirmwareApi (REST /api/frs) +
                            BleFirmwareApi (AT+FRS= over SLCAN transport). Both throw BusyException
  DriveCommand.kt         — Shared drive mode command flow: executeDriveModeChange() suspend function
                            Accepts FirmwareCommandSender → 2s settle → 15s CAN poll → auto-correct
                            Returns DriveCommandResult sealed class; used by MorePage + DriveModeDock
data/
  VehicleState.kt         — Immutable data class ~95 fields. See sentinel rules below.
  ForscanCatalog.kt       — Lazy loader for assets/pids/forscan_modules.json (1,149 PIDs)
  TripState.kt            — PeakType enum (RPM/BOOST/LATERAL_G/SPEED) + PeakEvent data class
  DriveDatabase.kt        — Room DB v3: DriveEntity (with name column), DrivePointEntity, DriveDao, migrations v1→v2→v3
  DriveState.kt           — Live drive state flow: isRecording, isPaused, peaks, recentPoints, fuel
  DtcResult.kt            — module, code, description, status (ACTIVE/PENDING/PERMANENT/UNKNOWN)
  DtcModuleSpec.kt        — name, requestId, responseId for DTC scan targets
diagnostics/
  DtcScanner.kt           — UDS 0x19/02 scan across PCM(7E0)/BCM(726)/ABS(760)/AWD(703)/PSCM(730)
  DiagnosticLogger.kt     — Thread-safe singleton: frame inventory (opt B), decode trace, SLCAN log (opt C)
                            Pre-allocated hex lookup table; hex conversion outside lock for reduced contention
                            sessionTransport field: transport label for diagnostic reports and share intents
  DiagnosticExporter.kt   — ZIP orchestrator + share intents + crash history; delegates format generation
                            to DiagnosticReportBuilder and DriveExportBuilder
  DiagnosticReportBuilder.kt — Diagnostic summary text + JSON detail (JSONObject/JSONArray, no hand-rolled strings)
  DriveExportBuilder.kt   — Pure-function builders: GPX track, CSV telemetry, drive summary, DTC report
  DtcDatabase.kt          — 873-code bundled Ford DTC lookup (from res/raw/dtc_database.json)
  CrashReporter.kt        — Installs UncaughtExceptionHandler; persists CrashTelemetryBuffer to disk
  CrashTelemetryBuffer.kt — 100-snapshot ring buffer of VehicleState; flushed on crash to JSON
service/
  CanDataService.kt       — Foreground service; WiFi/BLE-gated auto-reconnect; owns connection lifecycle
                            Transport selected by connectionMethod (WiFi vs BLE) + adapterType (TCP vs WebSocket)
                            Auto-record integration: starts/stops DriveRecorder on connect/disconnect (if setting enabled)
  DriveRecorder.kt        — Room-backed drive recorder: start/stop/pause/resume, 1 Hz GPS + telemetry
                            FusedLocationProviderClient @ 1 Hz; batch writes (30 points/flush); peak tracking
  WeatherRepository.kt    — OpenWeatherMap /data/2.5/weather (BuildConfig.OPENWEATHER_API_KEY)
ui/
  MainActivity.kt         — 7-tab Compose host (DASH/POWER/CHASSIS/TEMPS/MAP/DIAG/MORE)
                            Binds CanDataService via LocalBinder; passes callbacks to child composables
                            Location + BLE permissions; REC indicator + BT indicator in AppHeader
                            Quick Mode Dock: tap MODE cell in telemetry strip → dropdown drive mode selector
                            WiFi coexistence banner: warns when BLE active but phone connected to adapter WiFi
  DriveModeDock.kt        — Quick-access drive mode dock (N/S/T/D) with staggered entrance animation
                            Drops down from header via AnimatedVisibility; auto-dismisses on success
  AppSettings.kt          — SharedPreferences wrapper; prefs file: "openrs_settings"
                            adapterType ("MEATPI_USB"/"MEATPI_PRO") + connectionMethod ("WIFI"/"BLUETOOTH")
                            Legacy migration: "WICAN"→"MEATPI_USB", "MEATPI"→"MEATPI_PRO", "BLUETOOTH"(adapter)→USB+BLE
                            BLE device prefs: saveBleDevice/getBleDeviceAddress/clearBleDevice
                            rememberSectionExpanded() composable for persistent collapsible sections
  UserPrefs.kt            — Observable prefs data class + unit-conversion helpers
                            UserPrefsStore object: MutableStateFlow<UserPrefs>
                            Fields: adapterType, connectionMethod, host, port, + all unit/UI prefs
  Components.kt           — Shared composables: HeroCard (valueFraction glow), DataCell, BarCard,
                            TireCard, GfCard, WheelCell, AfrCard, SectionLabel (animated chevron),
                            NeonDivider, FocusRsOutline, tireTempColor()
                            Glow is data-gated: neonBorder only renders when live data present.
                            HeroCard glow scales with valueFraction (0=dormant, no border/glow).
                            DataCell/BarCard/GfCard/AfrCard suppress glow on placeholder "— —".
                            HeroCard uses plain HeroNum (instant display, no animation)
  DesignTokens.kt         — Tokens object: PagePad, CardGap, SectionGap, InnerH/V,
                            CardShape(12dp), HeroShape(14dp), CardRadius, HeroRadius, CardBorder
  Theme.kt                — Color tokens (brightness-scaled, see below), typography (Orbitron/JetBrains/ShareTech/Barlow)
                            setBrightness(Float) / getBrightness() — 0.0=Night, 0.5=Day, 1.0=Sun
                            7 base colors (Bg/Surf/Surf2/Surf3/Brd/Dim/Mid) are computed getters via lerp
  BleDevicePickerDialog.kt — BLE device picker: scan filtered to 0xFFE0, RSSI bars, dark neon aesthetic
                            Runtime BLE permission request (BLUETOOTH_SCAN + BLUETOOTH_CONNECT on API 31+)
                            Unknown device hint: orange "not a known adapter" for non-WiCAN/MeatPi names
                            Permission-denied UI state with retry button
  SettingsSheet.kt        — Settings drawer: units, TPMS threshold, shift light, adapter, connection,
                            reconnect, drives (auto-record, max saved), diag, theme picker (RS paint colours)
                            Visibility section: NIGHT/DAY/SUN presets + continuous brightness slider
                            2-way adapter picker (MeatPi USB / Pro) + connection method toggle (WiFi / Bluetooth)
                            Accent left-bar titles, gradient section backgrounds, animated SegmentedPicker
  anim/
    EdgeShiftLight.kt     — Peripheral shift light: multi-zone edge glow overlay (breathing → fill → flash)
                            Three phases keyed to shiftRpm: 70% breathing, 81% progressive, 95.5% flash
    GlowModifiers.kt      — neonGlow, neonGlowRect, neonBorder (animated pulse), neonPulse,
                            bloomGlow (double-layer radial), scanLine (CRT sweep)
    Sparkline.kt          — Inline trend chart with glow line + live endpoint dot + gradient fill
    StaggeredEntrance.kt  — StaggeredColumn: fade+slide-up with 40ms stagger delay per child
    InteractionModifiers.kt — pressClick() modifier: clickable + scale-down press feedback combined
  PidBrowserSection.kt    — DIAG tab: expandable FORScan catalog per module with coverage bar
  DidProberSection.kt     — DIAG tab: interactive Mode 22 scanner for any ECU+DID
  WhatsNewDialog.kt       — Version changelog dialog; shown on first launch after update
  trip/
    DrivePage.kt          — MAP tab: live mode (Google Maps + HUD + controls) + history mode (drive list)
                            Hoists cameraPositionState; floating controls: color mode, map type, weather,
                            zoom in (+), zoom out (−), locate/recenter (◎)
    DriveMap.kt           — Google Maps Compose wrapper: 6 color modes (SPD/MODE/BOOST/THRTL/G-LAT/TEMP),
                            start/finish flags, pause markers, peak markers (RPM/boost/lat-G/speed),
                            zoom-to-fit, map type cycling, blue dot location, color legend
update/
  UpdateManager.kt        — In-app update orchestrator: check → download → install via GitHub Releases API
  UpdateChecker.kt        — GitHub Releases API client; compares installed vs latest version per channel
  AppVersion.kt           — Semver + RC/beta parsing and comparison for update eligibility
  UpdateState.kt          — Sealed class: Idle / Checking / Available / Downloading / ReadyToInstall / Error
```

## ECU Addresses

| ECU    | Request | Response | Session                         |
|--------|---------|----------|---------------------------------|
| PCM    | 0x7E0   | 0x7E8    | default                         |
| BCM    | 0x726   | 0x72E    | default                         |
| AWD    | 0x703   | 0x70B    | extended (0x10 0x03)            |
| ABS    | 0x760   | 0x768    | default                         |
| PSCM   | 0x730   | 0x738    | extended                        |
| FENG   | 0x727   | 0x72F    | extended                        |
| RSProt | 0x731   | 0x739    | extended                        |
| GFM    | 0x7D2   | 0x7DA    | default (DTC scanning only)     |
| IPC    | 0x720   | 0x728    | scaffolded — not yet active     |
| HVAC   | 0x733   | 0x73B    | scaffolded — not yet active     |

## Active CAN IDs (HS-CAN 500 kbps)

```
0x010  steering angle (Motorola 15-bit + sign)
0x070  torque at trans (Motorola 11-bit bits 37-47, offset −500 Nm) + RS suspension button (byte7 bit7)
0x076  throttle % (byte0 × 0.392)
0x080  accel pedal (bits 0-9 LE × 0.1%), reverse (bit 5)
0x090  RPM (bytes 4-5) + baro (byte2 × 0.5 kPa)
0x0C8  gauge brightness (bits 0-4), e-brake (byte3 bit 6), ignition (byte2 bits 3-6)
0x0F8  oil temp (byte1−50°C), boost kPa absolute (byte5+baro), PTU temp (byte7−60°C)
0x130  speed (bytes 6-7 BE × 0.01 kph)
0x160  longitudinal G (bits 48-57 LE)
0x180  lateral G + yaw rate + vertical G
0x190  wheel speeds FL/FR/RL/RR (4 × Motorola 15-bit × 0.011343 km/h)
0x1A4  ambient temp from MS-CAN bridge (byte4 signed × 0.25°C)
0x1B0  drive mode nibble (byte6>>4); combine with 0x420 to distinguish Sport vs Track
0x1C0  ESC mode (2-bit at bit position 10)
0x225  launch control engaged (byte5 bit3)
0x230  gear (4-bit) — does NOT broadcast on this car
0x252  brake pressure (12-bit Motorola)
0x2C0  AWD left/right torque (12-bit each at bits 0, 12)
0x2F0  coolant + intake air temp (10-bit each)
0x340  PCM ambient (byte7 signed × 0.25°C) — NOT TPMS
0x360  odometer (bytes [3:5] 24-bit BE) + engine status (byte0)
0x380  fuel level (Motorola 10-bit × 0.4%, clamped 0-100)
0x420  drive mode detail + launch control flag (byte6/7, ~600 ms broadcast; bit0=0→Sport, bit0=1→Track)
```

## VehicleState Rules

**Sentinel values:**
- `-99.0` = not yet received — temps (coolant, oil, tires, charge air, PTU, RDU, cabin, etc.)
- `-1.0` / `-1L` = not yet polled — OBD scalars (oilLife, tirePressures, odometer, batterySoc, etc.)
- `0L` = not yet received — timestamps (tpmsLastUpdate)
- `null` = unknown — Boolean? fields (rduEnabled, pdcEnabled, fengEnabled, lcArmed, assEnabled, IPC/HVAC warning lamps)

**Rules:**
- VehicleState is **immutable** — always `.copy(...)`, never mutate directly
- `CanDecoder.decode()` returns `null` if data is too short — callers must null-check
- `CanDecoder.resetSessionState()` MUST be called on every new connection (resets modeDetail420 + has420Arrived)
- `genericValues: Map<String, Double>` — populated by PidRegistry for catalog DIDs without a dedicated field
- HVAC/IPC fields in VehicleState are scaffolded (v2.2.5) but not yet reliably populated

## OBD Mode 22 Response Layout

```
data[0] = PCI  (0x0N, N = payload length)
data[1] = 0x62 (positive response SID)
data[2] = DID high byte
data[3] = DID low byte
data[4] = B4   ← first data byte
data[5] = B5,  data[6] = B6 …
```

## Known Gotchas

- `0x340` is PCMmsg17 (PCM ambient temp), **NOT** a TPMS broadcast — TPMS only via BCM Mode 22 DIDs 0x2813/14/15/16
- Drive mode needs **both** `0x1B0` (nibble) **AND** `0x420` (bytes 6-7) — `0x1B0` alone cannot distinguish Sport from Track. **Polarity: bit0=0→Sport (0xCC), bit0=1→Track (0xCD).** Button cycle order: Normal→Sport→Track→Drift
- Skip `0x1B0` frames where byte4 ≠ 0 (button-event transition frames; steady-state has byte4 == 0)
- `0x230` (gear) and `0x3C0` (battery voltage) do **NOT** broadcast on this car — battery from PCM DID 0x0304. Gear is estimated from RPM/speed in `VehicleState.derivedGear`
- **Gear detection dual final drive** — MMT6 has final drive 4.063 (gears 1-4) and 2.955 (gears 5-6). Official ratios from 2016 Ford Focus RS Owner's Manual p242. The `GEAR_FACTOR` constant uses 3.82 in the denominator but thresholds are calibrated against the true overall ratios — do not recalibrate with a single final drive
- `boostKpa` is **absolute** pressure (not gauge) — gauge pressure = `boostKpa − 101.325`
- TPMS PID `0x280B` is multi-frame ISO-TP — send `BCM_FLOW_CONTROL` after receiving First Frame
- Extended session (`0x10 0x03`) required before: RDU_STATUS (AWD 0x703), PDC (PSCM 0x730), FENG (0x727), RSProt (0x731)
- Firmware drive mode is **hybrid scroll-then-wait** (v1.61+) — pre-calculates scroll count, sends all presses open-loop, waits up to 6s for auto-confirm. Do NOT revert to closed-loop press-and-poll (v1.6 bug) or pure open-loop press counting (v1.5 bug). The Focus RS mode selector GUI requires ~4s of inactivity to auto-confirm — CAN does not update during scrolling
- Firmware REST POST `/api/frs` returns `{"ok":false,"busy":true}` when a drive mode change is in progress — app must handle this
- `fengTimedOut` / `rsprotTimedOut` = true after 3 failed probe cycles — do not suggest retrying indefinitely
- **Drive mode cold-start gate** — `has420Arrived` flag in CanDecoder prevents Sport/Track resolution until the first `0x420` frame is received. Without this gate, `0x1B0` nibble=1 resolves against stale `modeDetail420` default during the 0-600ms blind window after connection, causing wrong mode flashes. The drive mode confirmation loop in MorePage adds a 2s post-command settling delay, a 15s timeout, and auto-correction if the car lands on the wrong mode (sends a corrective command automatically).
- Android 10+ silently routes new sockets through cellular when WiFi has no internet — `WiFiFirmwareApi` uses `Network.socketFactory` via `ConnectivityManager` to force all traffic on WiFi. BLE transport avoids this entirely (not a "network" in Android's routing model)
- **BLE 0xFFE0 UUID is NOT unique to WiCAN** — many cheap BLE peripherals (SP105E LED controllers, HM-10 clone modules) advertise the same 0xFFE0 service with FFE1/FFE2 characteristics. The BLE scan filter passes them through. `BleDevicePickerDialog` shows an orange "not a known adapter" hint for unrecognized device names. `SlcanConnection` has a 3s SLCAN handshake that rejects non-SLCAN devices before declaring Connected
- **BLE auto-reconnect** — `autoConnect=true` on `connectGatt()` after first successful connection. Android handles reconnection when device returns to range. 15s timeout covers slow auto-reconnect; on timeout, `SlcanConnection` retry logic takes over
- **BLE MTU** — `requestMtu(247)` requested after service discovery. If `requestMtu()` returns false (unsupported), proceeds with 23-byte default. SLCAN frames mostly fit; longer frames arrive as multiple BLE notifications and are reassembled by `lineBuffer`
- **BLE permissions must be requested at scan time** — `BleDevicePickerDialog` handles runtime permission requests (BLUETOOTH_SCAN + BLUETOOTH_CONNECT on API 31+). Do NOT rely solely on `MainActivity.onCreate()` permission requests — the user may switch to Bluetooth after app startup. `BleDeviceScanner.startScan()` also has a SecurityException safety net
- **Foreground service start from background** — `CanDataService.startConnection()` wraps `goForeground()` in try/catch because WiFi/Bluetooth callbacks can fire when the app is backgrounded. `MainActivity.startSvc()` also catches `ForegroundServiceStartNotAllowedException`
- **Brightness colors are computed getters** — `Bg`, `Surf`, `Surf2`, `Surf3`, `Brd`, `Dim`, `Mid` in Theme.kt are `val ... get() = lerp(base, bright, brightness)` backed by `mutableFloatStateOf`. They are NOT static vals. Compose snapshot system tracks reads automatically. Call `setBrightness()` to change; all composables recompose. Do not cache these colors in non-composable contexts
- **MAP tab camera state is hoisted** — `cameraPositionState` lives in `DrivePage`, passed to `DriveMap` as a parameter. This allows DrivePage to control zoom and recenter. Google Maps native My Location button is disabled; custom `◎` button replaces it
- **MAP tab disables pager swipe** — `HorizontalPager` sets `userScrollEnabled = pagerState.currentPage != 4` so pinch-to-zoom and pan gestures don't conflict with tab swiping. Users navigate away via the tab bar. Google logo cannot be removed (Maps Platform ToS requirement)

## Theme Colors

Bg/Surf/Surf2/Surf3/Brd/Dim/Mid are brightness-scaled via `lerp(Night, Sun, brightness)`.
Frost, accents, and semantic colors (Ok/Warn/Orange) are fixed.

```
Night (0.0):  Bg #05070A  Surf #0A0D12  Surf2 #0F141C  Surf3 #141B26  Brd #162030  Dim #547A96  Mid #7A9AB8
Sun   (1.0):  Bg #1A2535  Surf #1F2A3A  Surf2 #253445  Surf3 #2B3A4E  Brd #2E4560  Dim #8AAABB  Mid #B0D0E8
Frost   #E8F4FF   Dim     #547A96   Mid    #7A9AB8   Brd    #162030
Accent  #0091EA   AccentD #006DB3   Orange #FF4D00   Ok     #00FF88   Warn #FFCC00
```

RS paint themes (`themeId`): `cyan`=Nitrous Blue, `red`=Race Red, `orange`=Deep Orange,
`grey`=Stealth Grey, `black`=Shadow Black, `white`=Frozen White

## AppSettings Defaults

```
Speed: MPH  |  Temp: °F  |  Boost: PSI  |  Tire: PSI  |  TireLowPsi: 30
MeatPi USB (C3): 192.168.80.1:80 (WebSocket)    MeatPi Pro (S3): 192.168.0.10:35000 (TCP)
ScreenOn: true  |  AutoReconnect: true  |  MaxDiagZips: 5
AdapterType: "MEATPI_USB"  (alt: "MEATPI_PRO")
ConnectionMethod: "WIFI"  (alt: "BLUETOOTH")
EdgeShiftLight: false  |  EdgeShiftColor: "accent"  |  EdgeShiftIntensity: "high"  |  EdgeShiftRpm: 6800
AutoRecordDrives: false  |  MaxSavedDrives: 50
UpdateChannel: "stable"  (alt: "beta")
Brightness: 0.0  (0.0=Night, 0.5=Day, 1.0=Sun)
Prefs file: "openrs_settings"
```

**Adapter vs connection method:** `adapterType` identifies the hardware (determines protocol: WebSocket for USB, TCP for Pro). `connectionMethod` selects the transport (WiFi or BLE GATT). Both adapters support both WiFi and Bluetooth.

## Assets

```
android/app/src/main/assets/pids/combined_catalog.json   — 136 KB merged PID catalog
android/app/src/main/assets/pids/forscan_modules.json    — 185 KB FORScan catalog (1,149 PIDs)
android/app/src/main/res/raw/dtc_database.json           — 873-code Ford DTC descriptions
android/app/src/main/res/raw/google_map_style_dark.json  — Google Maps dark style (openRS_ palette)
android/app/src/main/res/raw/map_style_dark.json         — Legacy OSMDroid dark map style (unused)
```

Regenerate catalogs: `python3 android/scripts/gen_forscan_catalog.py`

## Build

```bash
cd android
./gradlew assembleDebug                    # → openRS_v{ver}-staging-debug.apk
./gradlew assembleRelease                  # → openRS_v{ver}.apk  (main release)
./gradlew assembleRelease -PrcSuffix=rc.5  # → openRS_v{ver}-rc.5.apk
./gradlew test                             # 327 unit tests across 11 files
bash scripts/install-debug.sh              # quick build + ADB install + launch
```

Requires `android/local.properties` (`OPENWEATHER_API_KEY=...`) and `android/keystore.properties` for release signing.
Version display format: `openRS_ v2.2.6` or `openRS_ v2.2.6-rc.1` (set via `-PrcSuffix=rc.1`).
RC builds MUST use `-PrcSuffix` so the APK filename reflects the RC version.

## Tests

```
android/app/src/test/java/com/openrs/dash/
  CanDecoderTest.kt           — 75 tests: all 23 CAN ID decoders (incl. 0x225 LC engaged, 0x070 suspension button)
  ObdResponseParserTest.kt    — 48 tests: PCM/BCM/AWD/PSCM/FENG/RSProt DIDs (incl. spark advance, charging voltage)
  DriveStateTest.kt           — 39 tests: fuel economy, averages, haversine, peaks, sentinels
  VehicleStateTest.kt         — 31 tests: conversions, AWD split, peaks, TPMS, RTR
  AppVersionTest.kt           — 30 tests: version code/name, RC suffix, build config
  DtcScannerTest.kt           — 28 tests: SAE J2012 DTC decoding, UDS status bit priority, payload parsing
  SlcanParserTest.kt          — 20 tests: standard/extended frames, error cases
  PidRegistryTest.kt          — 18 tests: catalog loading, DID lookup, fallback decoding
  UserPrefsTest.kt            — 16 tests: conversion math (temp, speed, boost, tire), sentinel edge cases
  IsoTpBufferTest.kt          — 12 tests: SF/FF/CF reassembly, edge cases
  RingBufferTest.kt           — 10 tests: capacity, overflow, iteration
```

## Branch / Release Workflow

```
feature/* → staging → main          (code, tests, android/CHANGELOG.md)
docs/*    → main    (direct)        (README.md, assets/, docs/, firmware/README.md)
Release tags: android-v*  (triggers CI signing + GitHub Release)
Rolling RC: one pre-release per version stream; tag/APK updated in place as RCs progress
```

Docs branches skip staging entirely — branch from main, PR to main.
Never route README.md, assets/, or docs/ through staging.

**Release checklist (staging → main):**
1. Scan open GitHub issues for any fixed by commits in the release (`gh issue list --state open` vs commit history)
2. Close standalone issues that shipped — comment with commit SHA
3. Update umbrella issue checklists — check off shipped sub-items with version annotation
4. Close umbrella issues only when ALL sub-items are checked off
5. Add/update `versionHighlights` entry in `WhatsNewDialog.kt` for the new version — dialog falls back to the latest entry if missing, but every release should have its own highlights

## Changelog Format (`android/CHANGELOG.md`)

Every release follows the same structure. Consistency matters — do not deviate.

**Entry template:**
```
- **Short title** — Description of what changed and why. ([#N](https://github.com/klexical/openRS_/issues/N))
```

**Rules:**
1. **Sections** — use only these four, in this order: `Added`, `Fixed`, `Changed`, `Removed`. No synonyms (not "Improved", "Refactored", "Stability", "Performance").
2. **RC sub-sections** — append `(rc.N)` to the section header when a version has multiple RCs: `### Fixed (rc.3)`. Add a short label after the RC number for context: `### Added (rc.4 — visual polish)`.
3. **Issue links are mandatory** — every entry that originated from or addresses a GitHub issue MUST end with the issue link: `([#N](url))`. If it addresses a sub-item in an umbrella issue, use: `(addresses [#N](url))`. Entries for bugs found and fixed within the same RC (no issue exists) are exempt.
4. **One format for links** — always `[#N](https://github.com/klexical/openRS_/issues/N)`. Never bare `(#N)`, never `Fixes #N` or `Addresses sub-item in #N` inline.
5. **Entry length** — 1-3 sentences. Lead with what changed, then why. Include the file/class name when it helps locate the change. Do not write paragraphs — the commit message has the full detail.
6. **Never shorten or rewrite previous RC entries** — when adding a new RC section, append it. Do not edit, condense, or rephrase entries from earlier RCs.
7. **Every fix shipped in a commit gets a changelog entry** — if a commit touches code that fixes something, it goes in the changelog, even if the commit's primary purpose was a feature.

## GitHub Release Notes

Release notes are a **condensed highlight reel**, not a copy of the changelog.

**Format:**
- "What's new in rc.N" — bullet list of user-facing highlights (1 line each, no implementation details)
- "Previous RCs" — one-liner per prior RC summarizing its theme, with issue links
- "Testing notes" — quick checklist of what to verify
- Link back to `android/CHANGELOG.md` for full detail

**Rule:** `CHANGELOG.md` is the authoritative record. Release notes summarize it. Never put detail in release notes that isn't in the changelog.

## Sapphire — Web Analytics Dashboard (`web/`)

Post-session analysis dashboard replacing the old Mission Control HTML export.
Named after the Ford Sierra Sapphire RS Cosworth + Nitrous Blue gemstone connection.

**Tech stack:** Vite + React 19 + TypeScript + Tailwind CSS + Recharts + Zustand.
Pure client-side (no server). Deployed as static files to GitHub Pages.

**Data contract:** Android app exports ZIP → user imports into Sapphire via drag-and-drop.
ZIP contains: `trip_*.csv`, `diagnostic_detail_*.json`, `diagnostic_summary_*.txt`, `did_probe_*.csv`.
Sapphire parses these and stores sessions in browser IndexedDB.

**Design:** Dark "Void" aesthetic matching the openRS_ in-app palette. Nav rail + panel layout.

```
web/
  src/
    components/
      layout/          — Shell, NavRail, Header
      panels/          — DashboardPanel, TripPanel, DiagnosticsPanel, ComparePanel,
                         SessionsPanel, ImportPanel, SettingsPanel
      charts/          — TimeSeriesChart, GpsMap, ComparisonChart, GaugeChart, Sparkline,
                         ModeTimeline (Recharts + Leaflet)
      ui/              — MetricCard, SectionLabel, SearchBar, ExportDropdown, DeltaCard,
                         TpmsSummaryCard, EmptyState
    store/
      index.ts         — Zustand store (sessions, panels, compare, nav)
      settings.ts      — Settings store (units, theme) — localStorage persisted
    lib/
      import.ts        — ZIP import pipeline (drive_*.csv + drive_summary_*)
      db.ts            — IndexedDB (idb) CRUD
      format.ts        — Formatters + useUnitFormatters() hook
      units.ts         — Metric/imperial conversion (speed, distance, temp, boost, tire, fuel)
      export.ts        — CSV/JSON session export + browser download
      compare.ts       — Multi-session KPI deltas + time normalization
      mapColors.ts     — GPS map color mode thresholds
    styles/            — Design tokens (openRS_ palette)
    types/             — Session, trip, diagnostic, vehicle state types
```

```bash
cd web
pnpm install                  # install dependencies
pnpm dev                      # local dev server (Vite)
pnpm build                    # production build → dist/
pnpm preview                  # preview production build
```

Old `MissionControlHtmlBuilder.kt` + `mission_control.html` remain as a "lite" fallback
until Sapphire reaches feature parity.

## Near-Horizon Work

```
2.1  Knock event logger       — KR cyl 1-4 (0x03EC-EF) scatter plot: RPM vs boost
2.2  Fuel trim drift tracker  — STFT (PID 0x06) + LTFT (PID 0x07) per session, line chart
2.3  Boost target vs actual   — TIP desired/actual, WGDC (0x0462), boost across RPM sweep
```

See `docs/feature-roadmap.md` for full pipeline.
