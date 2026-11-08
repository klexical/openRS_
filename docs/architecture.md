# openRS_ architecture

> **Version:** this page describes the stable release **v2.2.6**. It was moved out of the README on 2026-11-08 without changes to its content. The v2.2.7 release candidates changed parts of it: see [android/CHANGELOG.md on staging](https://github.com/klexical/openRS_/blob/staging/android/CHANGELOG.md). Class names in this page are those of v2.2.6 and earlier documentation; some have changed since.

## System diagram

```
┌──────────────────────────────────────────────────────────────────────┐
│  Phone UI — Jetpack Compose + Material 3 + Haze (frosted glass)      │
│  ┌──────┬───────┬─────────┬───────┬─────┬──────┬──────┐             │
│  │ DASH │ POWER │ CHASSIS │ TEMPS │ MAP │ DIAG │ MORE │             │
│  └──────┴───────┴─────────┴───────┴─────┴──────┴──────┘             │
│  HorizontalPager swipe · BottomNavBar overlay · Quick Mode Dock      │
│  Compact status bar: MODE pill · ESC · conn dot · ⚙                  │
│  Visibility: Night/Day/Sun brightness lerp (live recompose)          │
├──────────────────────────────────────────────────────────────────────┤
│  UserPrefsStore (StateFlow) — units, thresholds, brightness, BLE    │
├──────────────────────────────────────────────────────────────────────┤
│                  VehicleState (StateFlow)                            │
│    Immutable data class · ~140 fields · genericValues map · RTR      │
│    + DriveState (live recording, peaks, fuel economy)                │
├──────────────────────────────────────────────────────────────────────┤
│              CanDataService (Foreground Service)                     │
│   Decodes CAN → VehicleState → notifies UI                           │
│   Auto-reconnect (Wi-Fi + BLE) · DriveRecorder · CrashReporter       │
│   Auto-record on connect (if enabled)                                │
├──────────────────────┬───────────────────────────────────────────────┤
│  CanDecoder          │  DiagnosticLogger / Exporter / DtcScanner     │
│  22+ HS-CAN decoders │  DriveExportBuilder · DiagnosticReportBuilder │
│  has420Arrived gate  │  Per-ID first/last/Δ tracking + JSON export   │
│  RS_HS.dbc-verified  │  GFM (0x7D2) DTC scan support                 │
├──────────────────────┼───────────────────────────────────────────────┤
│  DriveRecorder       │  FirmwareApi (Wi-Fi REST + BLE AT+FRS)        │
│  Room DB · 1 Hz GPS  │  DriveCommand: settle → poll → auto-correct   │
│  Peak tracking       │  Network.socketFactory pins Wi-Fi route       │
├──────────────────────┴───────────────────────────────────────────────┤
│  PidRegistry — data-driven decode from FORScan catalog (JSON)        │
│  1,148 catalog PIDs · 20 modules · formula evaluator · genericValues │
├──────────────────────────────────────────────────────────────────────┤
│  ObdConstants / ObdResponseParser / SlcanParser / IsoTpBuffer        │
│  PCM/BCM/AWD/PSCM/FENG/RSProt response parsers                       │
├──────────────────────────────────────────────────────────────────────┤
│  SlcanConnection — shared connection lifecycle                       │
│  Constructor: transportFactory: () -> SlcanTransport                 │
│  3s SLCAN handshake · firmware probe · ISO-TP reassembly             │
├─────────────────┬─────────────────────┬──────────────────────────────┤
│ WebSocketSlcan  │  TcpSlcanTransport  │  BleSlcanTransport           │
│ Transport       │  raw TCP            │  BLE GATT (FFE0/FFE1/FFE2)   │
│ ws://.80.1/ws   │  tcp://.0.10:35000  │  MTU 247 · auto-reconnect    │
│ MeatPi USB Wi-Fi│  MeatPi Pro Wi-Fi   │  Both adapters               │
├─────────────────┴─────────────────────┴──────────────────────────────┤
│  PCM (0x7E0/30s) · BCM (0x726/30s) · AWD (0x703/60s)                │
│  STFT/LTFT · AWD clutch hydraulics · battery current · spark adv     │
│  GFM (0x7D2) DTC scan · IPC/HVAC scaffolded                          │
├──────────────────────┬───────────────────────────────────────────────┤
│  MeatPi USB (C3)     │  MeatPi Pro (S3)                              │
│  ESP32-C3 · Wi-Fi+BLE│  ESP32-S3 · Wi-Fi+BLE+GPS+MicroSD             │
│  openrs-fw v1.61     │  openrs-fw v1.2                               │
├──────────────────────┴───────────────────────────────────────────────┤
│  HS-CAN 500k         │  MS-CAN 125k: not read by the app             │
│  0x010–0x420 frames  │  (0x1A4 ambient is bridged onto HS)           │
└──────────────────────┴───────────────────────────────────────────────┘
```


## Key design decisions

**Why WebSocket SLCAN instead of ELM327 TCP?** ELM327's `ATMA` command is not fully implemented in WiCAN firmware. WebSocket SLCAN bypasses ELM327 entirely — the app does a manual HTTP 101 Upgrade handshake, sends `C` / `S6` / `O` (close/500kbps/open), and receives raw SLCAN frames. This delivers the full HS-CAN bus at ~2100 fps vs ~12 fps with polled OBD.

**How does TPMS work?** Tire pressures are polled from the BCM via Mode 22 (PIDs 0x2813–0x2816) every 30 seconds using the formula `(((256*A)+B)/3 + 22/3) * 0.145 PSI`. Tire temperatures use a two-step approach: on connect, the app polls sensor IDs from PIDs 0x280F–0x2812 (4-byte ID per tire position). Then every 30s it polls PID 0x280B ("last received TPMS sensor") — a 12-byte multi-frame ISO-TP response containing sensor ID, pressure (`(A*256+B)/20` PSI), and temperature (`raw − 40` °C). The sensor ID is matched against the stored map to assign the temperature to the correct tire. Per-tire temperature PIDs 0x2823–0x2826 were confirmed unsupported by the BCM (`7F 22 31`).

**How does firmware detection work?** After SLCAN initialisation, the app sends `OPENRS?\r`. openRS_ firmware responds with `OPENRS:<version>`; stock WiCAN ignores it. Every incoming CAN frame for the first **3 seconds** is scanned for the probe response — this time-based window ensures the probe reply is not missed even on high-throughput buses (~1700 fps). After 3 seconds without a response, firmware latches as "WiCAN stock" for the session. MORE tab feature buttons unlock when openRS_ firmware is confirmed.

**How does the FORScan PID catalog work?** `PidRegistry` lazy-loads `forscan_modules.json` at first use and indexes the catalog entries that have both a DID and a formula (24 of the 1,148) by `(ecuResponseId, did)` tuple. Each indexed entry carries a formula expression string (e.g. `"signed(A*256+B)/-512.0"`). A custom recursive-descent parser evaluates the formula at runtime using bytes from the OBD response. All four OBD parsers (PCM, AWD, HVAC, IPC) fall through to `PidRegistry` for unrecognised DIDs, storing results in `VehicleState.genericValues`. Only DIDs the app already polls reach it: adding a catalog entry alone does not add a reading.

**How does the DID prober work?** `DidProberSection` (DIAG tab) sends Mode 22 UDS queries across module-specific DID ranges using `sendRawQuery()` — a new infrastructure method on both `WiCanConnection` and `MeatPiConnection`. Responses are classified as FOUND (0x62 positive), NRC (0x7F negative, NRC code shown), or TIMEOUT. A live progress bar, counters, and expandable result list with raw hex data make it easy to discover new ECU parameters.

**How does the diagnostic system work?** `DiagnosticLogger` (singleton) accumulates three layers throughout the session: (1) a per-ID frame inventory with `firstRawHex`, `lastRawHex`, a `hasChanged` flag, and up to 10 periodic raw-hex snapshots per ID sampled every 30 s; (2) a rolling 10 000-entry decode trace; (3) a real-time SLCAN log in standard candump format. On export, `DiagnosticExporter` bundles all three artefacts into a ZIP via FileProvider, compatible with SavvyCAN, Kayak, and python-can.


## Project structure

```
web/                                         # Sapphire — post-session analytics dashboard
├── src/
│   ├── components/
│   │   ├── layout/                          # Shell, NavRail, Header
│   │   ├── panels/                          # Dashboard, Trip, Diagnostics, Sessions, Import
│   │   ├── charts/                          # TimeSeriesChart (Recharts)
│   │   └── ui/                              # MetricCard, SectionLabel, DataCell, EmptyState
│   ├── store/                               # Zustand state (sessions, panels, UI)
│   ├── lib/                                 # ZIP import, IndexedDB, formatting
│   ├── styles/                              # Design tokens (openRS_ palette)
│   └── types/                               # Session, trip, diagnostic types
├── package.json                             # Vite + React 19 + Tailwind 4 + Recharts + Zustand
└── vite.config.ts                           # base: '/openRS_/'

android/
├── app/src/main/
│   ├── assets/pids/
│   │   ├── forscan_modules.json          # Bundled FORScan catalog (1,148 PIDs, 20 modules)
│   │   └── combined_catalog.json         # Merged PID catalog
│   ├── java/com/openrs/dash/
│   │   ├── OpenRSDashApp.kt              # App singleton + driveDb/Recorder/State
│   │   ├── can/
│   │   │   ├── AdapterState.kt           # Disconnected/Connecting/Connected/Idle/Error
│   │   │   ├── BleDeviceScanner.kt       # BLE discovery filtered to 0xFFE0
│   │   │   ├── BleSlcanTransport.kt      # BLE GATT transport (FFE0/FFE1/FFE2 + MTU 247)
│   │   │   ├── CanDecoder.kt             # 22+ CAN decoders + has420Arrived gate
│   │   │   ├── DriveCommand.kt           # Shared drive mode command flow (settle/poll/auto-correct)
│   │   │   ├── FirmwareApi.kt            # WiFiFirmwareApi (REST) + BleFirmwareApi (AT+FRS)
│   │   │   ├── IsoTpBuffer.kt            # ISO-TP SF/FF/CF reassembly
│   │   │   ├── ObdConstants.kt           # Shared OBD query strings + CAN IDs + timing
│   │   │   ├── ObdResponseParser.kt      # Mode 22 dispatch (PCM/BCM/AWD/PSCM/FENG/RSProt)
│   │   │   ├── PidRegistry.kt            # Data-driven catalog decoder (formula evaluator)
│   │   │   ├── SlcanConnection.kt        # Shared connection lifecycle (transportFactory)
│   │   │   ├── SlcanParser.kt            # Shared SLCAN frame parser
│   │   │   ├── SlcanTransport.kt         # Transport interface (open/read/write/close)
│   │   │   ├── TcpSlcanTransport.kt      # MeatPi Pro raw TCP transport
│   │   │   └── WebSocketSlcanTransport.kt # MeatPi USB WebSocket transport
│   │   ├── data/
│   │   │   ├── DashLayout.kt             # Custom dashboard cell grid
│   │   │   ├── DriveDatabase.kt          # Room DB v3: drives + drive points
│   │   │   ├── DriveState.kt             # Live drive state (recording/peaks/fuel)
│   │   │   ├── DtcModuleSpec.kt          # ECU descriptor for DTC scan
│   │   │   ├── DtcResult.kt              # DTC + status enum
│   │   │   ├── ForscanCatalog.kt         # JSON catalog data classes
│   │   │   ├── FuelEconomy.kt            # Real-time fuel economy (60s rolling window)
│   │   │   ├── PerformanceTimer.kt       # 0-60 / 0-100 timer state machine
│   │   │   ├── SessionDatabase.kt        # Room DB for session snapshots
│   │   │   ├── TripState.kt              # Peak event types
│   │   │   ├── VehicleState.kt           # Immutable state (~140 fields, genericValues)
│   │   │   └── WeatherData.kt            # OpenWeatherMap snapshot
│   │   ├── diagnostics/
│   │   │   ├── CrashReporter.kt          # UncaughtExceptionHandler + persistence
│   │   │   ├── CrashTelemetryBuffer.kt   # 100-snapshot VehicleState ring buffer
│   │   │   ├── DiagnosticExporter.kt     # ZIP orchestrator + share intents
│   │   │   ├── DiagnosticLogger.kt       # Session-scoped collector + SLCAN log
│   │   │   ├── DiagnosticReportBuilder.kt # Summary text + JSON detail (JSONObject)
│   │   │   ├── DriveExportBuilder.kt     # GPX / CSV / drive summary builders
│   │   │   ├── DtcDatabase.kt            # Bundled 873-code Ford DTC lookup
│   │   │   └── DtcScanner.kt             # DTC scan/clear (PCM/BCM/ABS/AWD/PSCM/GFM)
│   │   ├── service/
│   │   │   ├── CanDataService.kt         # Foreground service + auto-reconnect (Wi-Fi+BLE)
│   │   │   ├── DriveRecorder.kt          # Room-backed 1 Hz GPS + telemetry recorder
│   │   │   ├── HudOverlayService.kt      # Floating HUD overlay
│   │   │   └── WeatherRepository.kt      # OpenWeatherMap integration
│   │   ├── update/
│   │   │   ├── AppVersion.kt             # Semver + RC parsing (incl. APK filename)
│   │   │   ├── UpdateChecker.kt          # GitHub Releases API client
│   │   │   ├── UpdateManager.kt          # Check/download/install orchestrator
│   │   │   └── UpdateState.kt            # Sealed state class
│   │   └── ui/
│   │       ├── MainActivity.kt           # 7-tab Compose host (HorizontalPager + BottomNavBar)
│   │       ├── DashPage.kt               # DASH tab
│   │       ├── PowerPage.kt              # POWER tab (incl. STFT/LTFT)
│   │       ├── ChassisPage.kt            # CHASSIS tab (incl. clutch hydraulics)
│   │       ├── TempsPage.kt              # TEMPS tab
│   │       ├── DiagPage.kt               # DIAG tab
│   │       ├── MorePage.kt               # MORE tab
│   │       ├── CustomDashPage.kt         # Custom dashboard
│   │       ├── BottomNavBar.kt           # Frosted-glass nav (Haze + spring indicator)
│   │       ├── DriveModeDock.kt          # Quick Mode Dock dropdown
│   │       ├── PidBrowserSection.kt      # PID browser composable
│   │       ├── DidProberSection.kt       # DID prober composable
│   │       ├── BleDevicePickerDialog.kt  # BLE device picker (RSSI + permissions)
│   │       ├── SettingsSheet.kt          # Settings drawer (units/visibility/adapter)
│   │       ├── WhatsNewDialog.kt         # Version changelog dialog
│   │       ├── Theme.kt                  # Brightness-scaled color tokens
│   │       ├── DesignTokens.kt           # Spacing/shape tokens
│   │       ├── Components.kt             # HeroCard/DataCell/BarCard/TireCard/AfrCard
│   │       ├── AppSettings.kt            # SharedPreferences wrapper
│   │       ├── UserPrefs.kt              # Observable prefs + UserPrefsStore
│   │       ├── trip/
│   │       │   ├── DrivePage.kt          # MAP tab (live + history)
│   │       │   └── DriveMap.kt           # Google Maps Compose wrapper (6 color modes)
│   │       └── anim/
│   │           ├── CarDiagram.kt         # Focus RS wireframe (TPMS)
│   │           ├── EdgeShiftLight.kt     # Peripheral shift light overlay
│   │           ├── GForcePlot.kt         # 2D scatter w/ trail + peak labels
│   │           ├── GlowModifiers.kt      # cardGlow + bloomGlow modifiers
│   │           ├── InteractionModifiers.kt # pressClick (clickable + scale)
│   │           ├── RingBuffer.kt         # Generic fixed-capacity ring buffer
│   │           ├── ShiftLightBar.kt      # Horizontal shift LED indicator
│   │           ├── Sparkline.kt          # Inline trend chart
│   │           └── StaggeredEntrance.kt  # Staggered fade+slide-up
│   └── res/
│       ├── font/                          # Embedded fonts
│       │   ├── orbitron_regular.ttf      # Hero gauge values
│       │   ├── orbitron_bold.ttf
│       │   ├── jetbrains_mono_regular.ttf # Secondary numeric readouts
│       │   ├── jetbrains_mono_bold.ttf
│       │   ├── share_tech_mono.ttf       # Raw data / diagnostics
│       │   ├── barlow_condensed_regular.ttf # UI labels
│       │   ├── barlow_condensed_medium.ttf
│       │   ├── barlow_condensed_semibold.ttf
│       │   └── barlow_condensed_bold.ttf
│       ├── raw/dtc_database.json          # Bundled 873-code Ford DTC lookup
│       ├── values/strings.xml
│       ├── values/themes.xml
│       ├── xml/file_paths.xml            # FileProvider path config
│       └── mipmap-*/ic_launcher*.png     # App icon (all densities)
├── browser-emulator/
│   └── index.html                        # Standalone browser emulator
├── docs/
│   ├── images/focus-rs-a4.png             # Focus RS wireframe source (TPMS UI)
│   ├── hardware-setup.md
│   ├── firmware-update.md
│   ├── pid-reference.md
│   └── signing-setup.md
├── scripts/
│   └── gen_forscan_catalog.py            # Parses FORScan CSV export → forscan_modules.json
└── README.md
```

