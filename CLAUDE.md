# openRS_

Native Android telemetry dashboard for the Ford Focus RS MK3.
Kotlin 2.0.21 + Jetpack Compose (Material3). Current: v2.2.7-rc.5 staging (versionCode 35).
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
                            PCM_FAST_QUERIES (TIP act/des, WGDC, KR1–4) + PCM_SLOW_QUERIES (rc.5; 20 DIDs rc.6 —
                            incl. F404 load, F40E timing, F415 O2 V, F423 HP fuel, 0548 LP fuel)
  PcmPollSchedule.kt      — rc.5 — Pure PCM poll schedule: engine running (>400 RPM) = fast group + 1 slow DID
                            per cycle at 150 ms (~0.8 Hz fast); engine off = full sweep + 30 s idle
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
                            Constructor takes transportFactory: () -> SlcanTransport (+ rpmProvider for fast poll)
                            Single PCM sender — NrcTracker attribution relies on lastSentPcmDid
                            Command response channel routes +FRS: lines separately from SLCAN frames
  AdapterState.kt         — Disconnected / Connecting / Connected / Idle / Error (sealed class)
  SlcanParser.kt          — SLCAN frame tokenizer ('t'=standard, 'T'=extended)
  IsoTpBuffer.kt          — ISO-TP SF/FF/CF reassembly; caller sends BCM_FLOW_CONTROL after FF
  FirmwareApi.kt          — FirmwareCommandSender interface + WiFiFirmwareApi (REST /api/frs) +
                            BleFirmwareApi (AT+FRS= over SLCAN transport). Both throw BusyException
  DriveCommand.kt         — Shared drive mode command flow: executeDriveModeChange() suspend function
                            Accepts FirmwareCommandSender → 2s settle → 15s CAN poll → auto-correct
                            Returns DriveCommandResult sealed class; used by MorePage + DriveModeDock
demo/                     — DEBUG BUILDS ONLY (DemoMode.AVAILABLE = BuildConfig.DEBUG)
  DemoSimulator.kt        — Pure, deterministic VehicleState generator per DemoScenario (IDLE / DRIVING / WARNING /
                            CONNECTION_LOSS) — same (scenario, t) → same frame; stamps CAN + OBD freshness tags
  TripPreview.kt          — TRIP sample states (`--es trippreview …`): drives / route points / recording DriveState in memory;
                            ids < 0 — TripPage reads them instead of Room and skips every write for them
  DemoMode.kt             — Controller: 25 Hz tick into OpenRSDashApp.vehicleState, pause/reset/scenario, sessionEpoch,
                            DemoFirmwareApi (drive mode / ESC → simulated state), simulated DTC scan/clear
data/
  VehicleState.kt         — Immutable data class ~95 fields. See sentinel rules below.
  DashLayout.kt           — Custom dashboard layout: DashCell grid, AVAILABLE_FIELDS catalog; JSON via AppSettings
  DriveDatabase.kt        — Room DB v12: DriveEntity (+ avgStftPct/avgLtftPct/trimSamples v11, pullCount/knockPullCount v12),
                            DrivePointEntity, KnockEventEntity (+ cylinder v11), DriveDao (Flow queries for knock/trim cards),
                            migrations v1→…→v12 (v12 also rescales stored TIP ×903.81/128)
  DriveState.kt           — Live drive state flow: isRecording, isPaused, peaks, recentPoints, fuel
  PendingSummary.kt       — Post-drive summary queued (DriveRecorder.pendingSummary) until closed on TRIP — shown once
  DtcResult.kt            — module, code, description, status (ACTIVE/PENDING/PERMANENT/UNKNOWN)
  DtcModuleSpec.kt        — name, requestId, responseId for DTC scan targets
  ForscanCatalog.kt       — Lazy loader for assets/pids/forscan_modules.json (1,149 PIDs)
  FuelEconomy.kt          — Real-time fuel economy: 60s rolling window, instant/avg, distance-to-empty
  PerformanceTimer.kt     — 0-60 / 0-100 timer using CAN speed: IDLE → ARMED → RUNNING → FINISHED
  SessionDatabase.kt      — Room DB for session snapshots: SessionEntity, SnapshotEntity, SessionDao
  BoostSweepStats.kt      — rc.4 — Target-vs-actual boost delta analytics; rc.5 uses fresh-only samples;
                            rc.6 adds avgWgdcAtPeak (≥ 90 % of peak TIP) + byGear (gauge PSI vs std atm)
  BoostSessionLog.kt      — rc.6 — Live 90 s window of fresh TIP decodes (+ target/WGDC if ≤ 2.5 s old) for the POWER
                            BOOST SWEEP chart; fed from CanDataService.mergeObdState, reset with KnockSessionLog
  FreshnessGate.kt        — rc.5 — Per-drive gate: polled values recorded once per decode, never pre-drive
  KnockCapture.kt         — rc.5 — Per-cylinder knock events from fresh KR (retard POSITIVE, ≥ 0.1°)
  KnockStats.kt           — rc.5 — Shared knock aggregation: half-open buckets, per-cylinder split
  KnockSessionLog.kt      — rc.6 — In-memory session knock capture (no recording needed), cap 2,000, own FreshnessGate +
                            PullTracker; fed from CanDataService.mergeObdState, reset with knockEventCount
  PullTracker.kt          — rc.6 — Streaming WOT pull detector (≥80 % throttle, ≥2,500 RPM, ≥2 s; ≤2 s gaps) + knock attribution
  FuelTrim.kt             — rc.5 — FuelTrimAccumulator (fresh + warm STFT/LTFT avg) + FuelTrimDrift alert (±8 %)
  TripState.kt            — PeakType enum (RPM/BOOST/LATERAL_G/SPEED) + PeakEvent data class
  WeatherData.kt          — OpenWeatherMap snapshot: temp, wind, humidity, icon-to-emoji mapping
diagnostics/
  DtcScanner.kt           — UDS 0x19/02 scan across PCM(7E0)/BCM(726)/ABS(760)/AWD(703)/PSCM(730)
  DiagnosticLogger.kt     — Thread-safe singleton: frame inventory (opt B), decode trace, SLCAN log (opt C)
                            Pre-allocated hex lookup table; hex conversion outside lock for reduced contention
                            sessionTransport field: transport label for diagnostic reports and share intents
  DiagnosticExporter.kt   — ZIP orchestrator + share intents + crash history; delegates format generation
                            to DiagnosticReportBuilder and DriveExportBuilder; rc.6 adds drive_knock_session_*.csv (KnockSessionLog)
  DiagnosticReportBuilder.kt — Diagnostic summary text + JSON detail (JSONObject/JSONArray, no hand-rolled strings)
  DriveExportBuilder.kt   — Pure-function builders: GPX track, CSV telemetry, drive summary, DTC report
  DtcDatabase.kt          — 873-code bundled Ford DTC lookup (from res/raw/dtc_database.json)
  CrashReporter.kt        — Installs UncaughtExceptionHandler; persists CrashTelemetryBuffer to disk
  CrashTelemetryBuffer.kt — 100-snapshot ring buffer of VehicleState; flushed on crash to JSON
service/
  CanDataService.kt       — Bound service, foreground only while a connection runs (ForegroundController);
                            WiFi/BLE-gated auto-reconnect; owns connection lifecycle
                            Transport selected by connectionMethod (WiFi vs BLE) + adapterType (TCP vs WebSocket)
                            Auto-record integration: starts/stops DriveRecorder on connect/disconnect (if setting enabled)
  DriveRecorder.kt        — Room-backed drive recorder: start/stop/pause/resume, 1 Hz GPS + telemetry
                            FusedLocationProviderClient @ 1 Hz; batch writes (30 points/flush); peak tracking
  WeatherRepository.kt    — OpenWeatherMap /data/2.5/weather (BuildConfig.OPENWEATHER_API_KEY)
  HudOverlayService.kt    — Floating HUD overlay (boost/RPM/oil temp); piggybacks CanDataService notification
ui/
  MainActivity.kt         — 5-tab Compose host (DRIVE/PERF/THERMAL/TRIP/GARAGE) — v2.2.7 "Daylight" overhaul
                            Binds CanDataService via LocalBinder; passes callbacks to child composables
                            Location + BLE permissions; REC indicator + BT indicator in AppHeader
                            Quick Mode Dock: tap MODE pill in status bar → dropdown drive mode selector
                            WiFi coexistence banner: warns when BLE active but phone connected to adapter WiFi
                            Header pill is the single always-on disconnected indicator (ConnectionBanner removed);
                            tapping it while live opens a "Disconnect from vehicle?" confirmation (only DISCONNECT
                            calls stopConnection); in demo it folds / unfolds the demo controls instead
                            Navigation: HorizontalPager (swipe) + BottomNavBar overlay outside Scaffold
                            TRIP tab (page 3): pager swipe disabled while touching map (onMapTouched callback)
                            Scaffold uses contentWindowInsets=statusBars (content extends behind nav bar)
                            hazeSource on content Box inside Scaffold (NOT outer Box — see Haze gotcha)
                            enableEdgeToEdge with transparent navigation bar style
  DrivePage.kt             — DRIVE tab: StatusLine (permanent 48 dp row: OVERHEATING › critical tyre alert › TEMPERATURE
                            HIGH / HEAT RISING › other alerts (3 s hold) › rest of the ThermalVerdict; temps come only from
                            the verdict — "OVERHEATING  oil 268°F ↑  +4"),
                            InstrumentHero row (RPM/BOOST/MPH), gear module (shift lights, brake | gear | throttle;
                            fixed height per screen — never resizes with speed/gear), VEHICLE panel (oil/coolant/fuel/batt, 2×2 under 340 dp,
                            alert chips, tap → THERMAL), chassis panel (AWD split + G + torque). vehicleStatus() is
                            the single alert list for banner + panel. Disconnected → "—"
  PerfPage.kt              — PERF tab: G-force plot + dynamics numeric readout + PowerPageContent
                            (AFR, throttle/boost, engine mgmt, fuel trims, knock sensors). When offline,
                            sections force-collapse + a "CONNECT ADAPTER TO POPULATE" hint renders at top
  PowerPage.kt             — PowerPageContent composable shared by PERF + CustomDash
  ChassisPage.kt           — UnifiedChassisSection (THERMAL): TYRES panel (TyreCells around the original CarDiagram
                            wireframe + overlay — unchanged) and AWD panel (torque-only diagram + readouts; PTU/RDU/
                            clutch/trans temps live in DRIVETRAIN, not here). GForceSection for PERF
  TempsPage.kt             — THERMAL tab: ReadinessCard (verdict headline + reason, STREET/TRACK/RACE chips, collapsed
                            CHECKLIST), one predictive-alert banner (soonest forecast, names the rest), ENGINE (full-width oil + coolant EngineTempCard, scale + WARN/CRIT legend),
                            DRIVETRAIN panel (PTU/RDU/clutch L-R/trans oil rows), AIR (compact intake/ambient), tires
                            & AWD, collapsible DETAIL (compact). Offline → "AWAITING CAN"
  Instrument.kt           — Design-pass building blocks: Level (NORMAL/COLD/WARN/CRIT/STALE/NONE), tempLevel(),
                            instrumentSurface() (white + hairline on DAY, no shadow), InstrumentLabel/InstrumentMeta
                            (10 sp), InstrumentHero, StatusCell, AlertChip, ThresholdBar (caution + redline zones) +
                            ThresholdLegend, MiniReadout, Telltale (res/drawable/ic_tt_oil|coolant|fuel|battery|tyre|
                            alert — original stroke glyphs), ReadoutGrid/Readout (grouped readings panel, dense + footer;
                            POWER + TRIP). The Focus RS wireframe (drawable-nodpi/
                            focus_rs_wireframe.png) is the owner's branding art — never redraw/replace it unasked
  TripPage.kt (trip/)      — TRIP tab: live Google Map + drive history list; see trip/DriveMap.kt
  GaragePage.kt            — GARAGE tab: CONTROL/DIAG segmented picker → MorePage + DiagPage
  DiagPage.kt              — DTC scan/clear, PID browser, DID prober, diagnostic export (inside GARAGE)
  MorePage.kt              — Drive mode selector, ESC band, fw features, module status, custom dash entry
                            (inside GARAGE); offline branches added for ESC descriptor + MODULE STATUS
  CustomDashPage.kt        — Custom dashboard overlay: user-configurable gauge rows from DashLayout (instrument cells,
                            limit-only colours, temps on ThresholdBar); hides the bottom nav, takes every touch, Back
                            closes it; title carries the header pill's link state (DEMO · RCN / RCN / OFF)
  KnockScatter.kt         — rc.4 — RPM × boost scatter + aggregate card for PerfPage knock events section
                            rc.6: tap-to-select dot → KnockEventDetail; PULLS cell; KnockScatterMath = shared projection/hit-test
  BoostSweepChart.kt      — rc.6 — POWER THROTTLE & BOOST chart: RPM x-axis, target (dashed) / actual / WGDC; BoostSweepMath =
                            pure 250-rpm binning (1,500–7,000), null bins = gaps, min 40 kPa Y span
  FuelTrimDriftCard.kt    — rc.5 — PERF card: per-drive STFT/LTFT trend (last 20 drives), ±5/±10 % bands, drift alert
  trip/TimeSeriesMath.kt  — rc.5 — Pure gap segmentation + shared-axis ranges for DriveTimeSeries
  BottomNavBar.kt         — Custom bottom nav: 5 vector icons, frosted glass via Haze (hazeEffect),
                            spring-animated indicator bar, one top hairline, pressClick + haptics
                            Height: Tokens.NavBarHeight (48 dp tabs) + sysNavPad (gesture area)
  ThermalReadiness.kt     — ThermalReadinessSession (app-level, 1 Hz, owns the ThermalPredictor) → ThermalVerdict for DRIVE's
                            StatusLine, THERMAL ReadinessCard + checklist and the DriveRecorder RTR marker. States: NO LIVE DATA ›
                            OVERHEATING › TEMPERATURE HIGH › HEAT RISING › CHECKING › ENGINE / DRIVETRAIN WARMING › TEMPS READY.
                            ThermalReadinessEngine = pure, clock-injected (tests). Also the shared freshness rule
                            VehicleState.tempAvail / tempStaleAfterMs (RDU, clutch + trans-oil temps 90 s, everything else 60 s).
                            Clutch heat uses the fixed CLUTCH_WARN_C / CLUTCH_CRIT_C (90 / 120 °C, the card limits)
  DemoControls.kt         — Debug demo UI: DemoControlStrip (under header), DemoBadge, demoInk() (DAY-legible amber),
                            telemetrySessionKey() — key for UI-held sparkline/G-trail buffers
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
                            All cards use cardGlow() for ambient depth + CardBorder (0.5dp) borders.
                            HeroCard glow scales with valueFraction (0=dormant, no accent glow).
                            DataCell/BarCard/HeroCard numeric values use Rajdhani (tnum — tabular figures
                            for stable width). MonoLabel uses JetBrains Mono for micro-labels only.
  DesignTokens.kt         — Tokens object: PagePad, CardGap, SectionGap, InnerH/V, NavBarHeight(48dp), StatusBarHeight(44dp header),
                            CardShape(12dp), HeroShape(14dp), CardRadius, HeroRadius, CardBorder(0.5dp)
  Theme.kt                — v2.2.7 "Daylight": discrete NIGHT / DAY / ULTRA theme modes (no brightness slider).
                            themeMode: ThemeMode enum; AUTO resolves from the local clock (DAY when HOUR_OF_DAY in 6..18).
                            Each mode defines its own Bg/Surf/Surf2/Surf3/Brd/Dim/Mid/Ink palette as fixed
                            vals — NOT lerp-computed. setThemeMode(ThemeMode) snapshots the active palette.
                            DAY: #EFF3F8 bg / #080D16 ink (18.5:1). NIGHT: near-black. ULTRA: saturated high-contrast.
                            Typography: Rajdhani (primary, incl. HeroNum/AggressiveNum), JetBrains Mono (labels).
                            Old Orbitron/ShareTech/Barlow TTFs removed.
  BleDevicePickerDialog.kt — BLE device picker: scan filtered to 0xFFE0, RSSI bars, dark neon aesthetic
                            Runtime BLE permission request (BLUETOOTH_SCAN + BLUETOOTH_CONNECT on API 31+)
                            Unknown device hint: orange "not a known adapter" for non-WiCAN/MeatPi names
                            Permission-denied UI state with retry button
  SettingsSheet.kt        — Settings drawer (sole entry via cog icon in status bar): units, TPMS threshold,
                            shift light, adapter, connection, reconnect, drives (auto-record, max saved),
                            diag, theme picker (RS paint colours)
                            Visibility: discrete NIGHT / DAY / AUTO + ULTRA theme mode selector (no slider)
                            2-way adapter picker (MeatPi USB / Pro) + connection method toggle (WiFi / Bluetooth)
                            Design pass: instrument-panel sections, SettingsPicker (accent tint + actionInk; stacked full
                            width where options don't fit at 360 dp), 48 dp rows / actions; every exit except SAVE reverts
                            the live theme / font / paint preview (`cancel()`). Public SegmentedPicker is PERF's (unchanged)
  anim/
    EdgeShiftLight.kt     — Peripheral shift light: multi-zone edge glow overlay (breathing → fill → flash)
                            Three phases keyed to shiftRpm: 70% breathing, 81% progressive, 95.5% flash
    GlowModifiers.kt      — bloomGlow (double-layer radial) for connection dot,
                            cardGlow (outer shadow + top specular) for ambient card depth
    GForcePlot.kt         — 2D scatter plot: rectangular grid, corner brackets, auto-scaling axes,
                            120-dot trail (blue→white age gradient), peak labels, RingBuffer history
    Sparkline.kt          — Inline trend chart with glow line + live endpoint dot + gradient fill
    StaggeredEntrance.kt  — StaggeredColumn: fade+slide-up with 40ms stagger delay per child
    InteractionModifiers.kt — pressClick() modifier: clickable + scale-down press feedback combined
    RingBuffer.kt           — Fixed-capacity generic ring buffer: push/toList/clear; used by GForcePlot
    ShiftLightBar.kt        — Horizontal LED-style shift indicator: green→yellow→red fill, redline flash
    CarDiagram.kt           — Focus RS wireframe with tire highlights at wheel positions
  PidBrowserSection.kt    — DIAG tab: expandable FORScan catalog per module with coverage bar
  DidProberSection.kt     — DIAG tab: interactive Mode 22 scanner for any ECU+DID
  WhatsNewDialog.kt       — Version changelog dialog; shown on first launch after update
  trip/
    DrivePage.kt          — MAP tab: live mode (Google Maps + HUD + controls) + history mode (drive list)
                            Hoists cameraPositionState; floating controls: color mode, map type, weather,
                            zoom in (+), zoom out (−), locate/recenter (◎)
                            onMapTouched callback: signals parent pager to disable swipe while map is touched
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
0x040  SRS/airbag frame — byte2 bits 7:6 == 0xC0 → lamp ON (DigiCluster v1.0.73 reference)
0x070  torque at trans (Motorola 11-bit bits 37-47, offset −500 Nm) + RS suspension button (byte7 bit7)
0x076  throttle % (byte0 × 0.392) — ABSENT on some RS PCMs (user's car, 2026-10-22); use VehicleState.throttleOrPedalPct
0x080  accel pedal (bits 0-9 LE × 0.1%), reverse (bit 5)
0x090  RPM (bytes 4-5) + baro (byte2 × 0.5 kPa)
0x0C8  gauge brightness (bits 0-4), e-brake (byte3 bit 6), ignition (byte2 bits 3-6)
0x0F8  oil temp (byte1−50°C), boost kPa absolute (byte5+baro), PTU temp (byte7−60°C)
0x130  speed (bytes 6-7 BE × 0.01 kph)
0x160  longitudinal G (bits 48-57 LE)
0x180  lateral G + yaw rate + vertical G
0x190  wheel speeds FL/FR/RL/RR (4 × Motorola 15-bit × 0.011343 km/h)
0x1A4  ambient temp from MS-CAN bridge — byte4 **UNSIGNED** × 0.25°C (DigiCluster v1.0.46 fix); > 55°C rejected (#185)
0x1B0  drive mode nibble (byte6>>4); combine with 0x420 to distinguish Sport vs Track
0x1C0  ESC mode (2-bit at bit position 10)
0x225  launch control engaged (byte5 bit3)
0x230  gear (4-bit) — does NOT broadcast on this car
0x252  brake pressure (12-bit Motorola)
0x2C0  AWD left/right torque (12-bit each at bits 0, 12)
0x2F0  coolant + intake air temp (10-bit each)
0x300  battery/charging fault — reserved; bit mapping pending car-side capture
0x340  PCM ambient (byte7 signed × 0.25°C) — NOT TPMS
0x360  odometer (bytes [3:5] 24-bit BE) + engine status (byte0)
0x380  fuel level (Motorola 10-bit × 0.4%, clamped 0-100)
0x420  drive mode detail + launch control flag (byte6/7, ~600 ms broadcast; bit0=0→Sport, bit0=1→Track)
0x435  per-zone airbag status — **INTENTIONALLY NOT DECODED**: crosstalks with passenger-occupancy and false-triggers SRS lamp (DigiCluster v1.0.73)
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
- **Foreground service only while connected** — `MainActivity.startSvc()` only **binds** `CanDataService`. `startConnection()`
  promotes it via `ForegroundController.promote()` (startForegroundService(self) immediately followed by startForeground);
  `stopConnection()` demotes (stopForeground + stopSelf), so an idle service lives only while the activity is bound. Never
  call `startForegroundService()` without a guaranteed `startForeground()` — the old per-`onCreate` call ANR'd whenever the
  activity was recreated offline / in demo. `promote()` is in a try/catch because WiFi/Bluetooth callbacks can fire when the
  app is backgrounded (Android 12+ refuses the start). `manualConnectRequired` is process-wide (companion) because the idle
  service instance is recreated with the activity
- **Theme colours are mode-aware getters** — `Bg`, `Surf`, `Surf2`, `Surf3`, `Brd`, `Dim`, `Mid`, `Frost` in Theme.kt are `val ... get() = when { _isDay -> ...; isUltraNightNow() -> ...; else -> Night... }` backed by `mutableStateOf(ThemeMode)`. They are NOT static vals. Compose snapshot system tracks reads automatically. Call `setThemeMode(ThemeMode)` to change; all composables recompose. Do not cache these colors in non-composable contexts. `setBrightness()`/`getBrightness()` are deprecated no-ops retained for back-compat
- **TRIP tab camera state is hoisted** — `cameraPositionState` lives in `DrivePage` (trip/), passed to `DriveMap` as a parameter. This allows DrivePage to control zoom and recenter. Google Maps native My Location button is disabled; custom `◎` button replaces it
- **TRIP map: created on TRIP, then retained** — `HorizontalPager(beyondViewportPageCount = 1)` composes neighbours and
  prefetches the next page, so TRIP is composed from PERF / THERMAL / GARAGE. `TripPage(settled, visible)` creates
  `DriveMap` only once TRIP is the settled page, then pins itself (`LocalPinnableContainer.pin()`) so the pager keeps the
  page — one MapView for the activity's life, released with the composition. Off screen (`visible` = TRIP in
  `layoutInfo.visiblePagesInfo`) the subtree's `LocalLifecycleOwner` is capped at CREATED (AndroidView passes it to the
  MapView → onStop, EGL surface released) and its vehicle / drive inputs are held. Anything infinite-animated or
  modal inside TRIP must gate on `visible` (rec pulse, lap timer, post-drive summary sheet)
- **Navigation swipe + TRIP tab isolation** — `HorizontalPager` with swipe between all 5 tabs. TRIP tab (page 3): pager swipe is dynamically disabled while the user touches the map area via `onMapTouched` callback from `DrivePage` → `mapTouched` state → `userScrollEnabled = !(selectedTab == 3 && mapTouched)`. `nestedScroll` does NOT work for this — Google Maps is an `AndroidView` that doesn't dispatch Compose nested scroll events. The `pointerInput` on the map Box monitors (does NOT consume) touches. Google logo cannot be removed (Maps Platform ToS requirement)
- **Haze frosted glass placement** — `hazeSource` MUST be on the content composable (inside Scaffold), NOT on a parent Box that also contains the `hazeEffect` composable (BottomNavBar). If hazeEffect is inside the hazeSource tree, blur fails silently with no error. Also: Haze tint with `Bg.copy(alpha > 0.5f)` on a dark theme makes blur invisible — start at 0.0 and increase
- **Bottom nav bar insets** — `Scaffold(contentWindowInsets = WindowInsets.statusBars)` drops the bottom inset so content extends behind the nav bar overlay. `enableEdgeToEdge(navigationBarStyle = SystemBarStyle.dark(TRANSPARENT))` removes the system nav bar scrim. Pages add `Tokens.NavBarHeight` as bottom scroll padding
- **0x1A4 ambient temp is UNSIGNED** — DigiCluster v1.0.46 confirmed on-car that raw 0x82 (130 unsigned) × 0.25 = 32.5°C (90.5°F). Prior spec said signed int8, which wrapped values above 32°C to negative on hot days. Our decoder at `CanDecoder.kt:ID_AMBIENT_TEMP` uses `(data[4].toInt() and 0xFF)` to force unsigned. **But unsigned ×0.25 with no offset cannot represent sub-zero ambient** — readings above `AMBIENT_1A4_MAX_C` (55°C) are rejected (state returned unchanged, raw byte logged) until a sub-zero capture pins the true encoding ([#185](https://github.com/klexical/openRS_/issues/185)). 0x340 PCMmsg17 byte7 is still signed per RS_HS.dbc — if we see Phoenix-summer wrap on that signal in field testing, same fix applies.
- **0x435 airbag crosstalk** — DigiCluster v1.0.73 found that the per-zone airbag status bits on 0x435 share bits with passenger-occupancy detection and false-triggered the SRS lamp in-vehicle. We decode 0x040 bits 7:6 of byte2 only (`0xC0 == true` = lamp on), and explicitly keep 0x435 OUT of `KNOWN_IDS`. Do not add 0x435 decoding without a verified fault vs occupancy capture.
- **Freshness stamps (`fieldLastUpdateMs`)** — Mode 22 decoders stamp via `markFresh()`; `CanDataService.mergeObdState()` MUST merge them with `VehicleState.mergeStamps` (per-key max) or the UI reads every OBD field as STALE (pre-rc.5 bug). CAN-sourced THERMAL fields are stamped by `CanDecoder.stampFresh()` throttled to 1 s per ID — never `markFresh()` per CAN frame (map copy at hundreds of fps). A decoder returning the **same** `state` instance means "recognized, nothing changed": `processCanFrame` logs it and skips the copy/emit (0x040, 0x300, rejected 0x1A4)
- **Recorder only stores fresh polled values** — `DriveRecorder` writes TIP/target/WGDC/KR/STFT/LTFT only when `FreshnessGate.takeIfFresh()` passes; otherwise sentinels (`tipActualKpa`/`boostTargetKpa` 0.0, `wgdcPct` −1.0). Never record held VehicleState values for slow DIDs — they repeat one reading many times
- **KR sign convention** — `ignCorrCyl1..4 = raw / −512` → retard is **POSITIVE**. Knock capture uses `≥ 0.1°`, the header counter and POWER KR cells `> 1°`. Cyl 2–4 DIDs (0x03ED–EF) are unverified — 0x03ED/0x03EE NRC 0x31 on two RS PCMs (EU tuned #182 + user's car), so not tune-specific; 0x03EF answers but read 0 for a whole drive. NrcTracker suppresses 2/3; polling left as is (user decision 2026-10-22)
- **TIP is `raw / 128` kPa absolute** — 0x033E/0x0466 (DigiCluster, on-car verified). rc.4/rc.5 used `/903.81`, which is the Ford-DID **psi** formula, hiding TIP and starving BOOST SWEEP ([#198](https://github.com/klexical/openRS_/issues/198)). Do not reintroduce 903.81
- **Mode 01 equivalents live in Mode 22 `22F4xx`** — load (F404), timing (F40E), O2 V (F415), STFT/LTFT (F406/07), λ (F434/F444). `ObdPids.kt` (the Mode 01 path) was deleted in a7b01d0 and silently took the load/timing/AFR decoders with it — if a VehicleState field has no writer in ObdResponseParser/CanDecoder, it is dead. HP fuel is **0xF423** (DI gauge, ×10 kPa); 0xF422 is the port-injection PID and is rejected. LP fuel = **0x0548** (`raw × 0.5` kPa)
- **Throttle may not broadcast** — CAN 0x076 is absent on some cars (`throttleHasSource` stays false). Anything keyed on throttle (PullTracker, knock snapshot, recorded throttle) must read `throttleOrPedalPct`, never `throttlePct` directly
- **PCM "no data" replies** — 0x0461 charge air answers `0x0000` on two RS PCMs; the decoder treats that as no data (stays −99). BCM 0x4028 battery SOC read raw 5 (= 5 %) at 14.7 V all session on the user's car — unverified, compare with FORScan BAT_ST_CHRG before trusting it
- **Adding a polled DID** — put the query in `ObdConstants` AND a `markFresh` decoder in `ObdResponseParser`; the coverage-guard tests in `ObdResponseParserTest` fail otherwise. Removing a decoder fails them too (that's the point — #198). Multi-frame ISO-TP DIDs (BCM 0x280B) are excluded explicitly
- **POWER cells are gated per field** — engine cells show only when `rpm > FAST_POLL_MIN_RPM` AND `fieldLastUpdateMs[tag] != null`; never gate on another field's value (the old `calcLoad > 0` gate hid everything once load lost its decoder)
- **Lint version is pinned above AGP** — `android/gradle.properties` sets `android.experimental.lint.version=8.13.0` because AGP 8.7.3's bundled lint crashes (`IncompatibleClassChangeError`) on the lint checks shipped in Compose BOM 2025.11 / Lifecycle. Don't remove it unless AGP is upgraded to ≥ 8.13. `abortOnError = false`, so lint findings never fail CI — only crashes do; check `app/build/reports/lint-results-debug.txt` for errors ([#196](https://github.com/klexical/openRS_/issues/196))
- **`MutableList.removeFirst()/removeLast()` crash below API 35** — with compileSdk 35 they resolve to `java.util.List.removeFirst` (Android 15+) and throw `NoSuchMethodError` on Android 9–14 (minSdk 28). Use `removeAt(0)` / `removeAt(lastIndex)`. `ArrayDeque.removeFirst()` is fine
- **Location permission** — always request `ACCESS_COARSE_LOCATION` together with `ACCESS_FINE_LOCATION` (Android 12+ requirement); recording still needs FINE granted
- **CI tag builds** — `publish-release` derives `-PrcSuffix` from `android-v*-rc.N` tags so CI never attaches a plain `openRS_v{ver}.apk` to an RC pre-release. Manual RC releases still build with `-PrcSuffix=rc.N`
- **VehicleState slot ceiling** — `copy()` on the 162-param data class is at the JVM 255-slot limit. Adding any new field requires either collapsing an unused existing field (speculative AWD DIDs are candidates) or storing it off-state (DiagnosticLogger / genericValues Map). 0x040 lamp state is decoded + validated but not yet persisted on VehicleState for this reason
- **Demo mode owns VehicleState while active** — `CanDataService.enterDemoMode()` finalizes any recording drive, calls
  `stopConnection()`, then `DemoMode.start()`. While `DemoMode.isActive`: `processCanFrame` / `mergeObdState` / the adapter
  state collector early-return, `startConnection()` / `reconnect()` are no-ops, `firmwareApi` returns `DemoFirmwareApi`,
  DTC scan/clear/retry and `sendRawQuery` are answered by `DemoMode`, and `resetSession()` replays the scenario. Never
  bypass these — demo frames would mix with real ones
- **Simulated data never persists** — guarded writers: `DriveRecorder.startDrive()` (TRIP START shows "DEMO — NOT
  RECORDED"), `PerfPage` perf-run insert, `CrashTelemetryBuffer` push, `DiagnosticExporter` session-knock CSV. Demo DTC
  scans skip `DtcScanner` (which inserts `dtc_scans`). Any new Room / export writer of live telemetry needs the same
  `DemoMode.isActive` guard
- **Leaving demo requires an explicit connect** — `exitDemoMode()` resets VehicleState + session logs and sets
  `manualConnectRequired`; WiFi/BT/onCreate callbacks go through `autoStartConnection()`, which honours it (and
  `DemoMode.launchRequested`, so a `--es demo` launch never opens the adapter). Only the UI's connect/reconnect clears it
- **UI-held histories must key on `telemetrySessionKey()`** — `remember { SparklineData(..) }` / `RingBuffer` in
  composables outlive VehicleState resets, so simulated traces would survive demo exit. DrivePage / TempsPage / PowerPage /
  ChassisPage buffers (+ their last-sample stamps, ThermalPredictor) use `remember(telemetrySessionKey())`
- **Readiness = `ThermalReadinessSession.verdict`, never recomputed per surface** — DRIVE, THERMAL and `DriveRecorder`'s RTR
  marker read the one session verdict (composables via `thermalVerdict(vs)`, which voids it offline / on a demo epoch change).
  Worse is immediate; better is held 10 s on the monotonic clock (heat: 2 °C under warn / crit; warm-up: minimum + 1 °C, one
  fresh poll for RDU / clutches); a gap > 2 s between evaluations restarts holds; below a warm-up minimum ends readiness at
  once; a stale / invalid reading drops its own heat / forecast latch. Offline, `resetSession()` and demo epoch changes clear
  the engine and predictor; a preset change re-derives heat. RDU/PTU/clutch temps count once reported (then stale or invalid
  blocks). The evaluator only reads `fieldLastUpdateMs`. `isReadyToRace` remains only as the cold-check primitive. Reason text
  must say what a hold waits for — never a comparison that is no longer true. DRIVE's VEHICLE summary falls back to
  `vehicleAdvisory()` (never ALL NORMAL under a heat / forecast / recovery / CHECKING verdict); checklist limit and forecast
  rows are incomplete, not ticked, while a reading they cover is unavailable
- **Temperature freshness = `vs.tempAvail(value, tag)`** — RDU / AWD clutch / trans-oil temps (AWD poll, ~37 s cycle) are
  stale after 90 s, CAN temps after 60 s. Use it (or `tempStaleAfterMs(tag)`) wherever a temp's availability is shown or decided
- **Live-only readouts go blank when `!vs.isConnected`** — the last frame stays in VehicleState after a drop, so gear,
  shift LEDs (bar + edge glow), LC glow/banner, e-brake/LC anomaly priority, DRIVE throttle/brake/AWD/G/torque,
  the HUD overlay and Custom Dashboard cells all gate on connection. Fuel / battery / temps keep last value (THERMAL marks
  them STALE). Any new live readout needs the same gate
- **Don't animate fast values with `AnimatedContent`** — a crossfade keyed on the value restarts on every CAN frame and
  never becomes visible; keyed on placeholder-ness it freezes the container width and clips digits. `DataCell` fades only
  on a placeholder ↔ value switch (`Animatable` alpha)
- **Provide `legibleAccent()` as `LocalThemeAccent`** — raw Frozen White vanishes on DAY and Shadow Black on NIGHT/ULTRA;
  the structural `AccentTriad.full` replaces them. Only paint swatches (`rsPaintAccent`) show the raw paint
- **Held readings after a drop are last-known** — `TempAvail.whenDisconnected()` turns a present reading STALE while
  `!vs.isConnected` (stamps stay "fresh" for 60 s after a drop); `VehicleState.liveOrRest()` returns per-frame dynamics at
  rest for composables that would draw the held frame. `DriveRecorder` skips samples while disconnected (like a pause) so
  held values are never stored as fresh — distance during the gap isn't counted
- **Amber text uses `WarnInk`** — the text primitives in `Theme.kt` route DAY `Warn` through `textColor()`; raw Material
  `Text`/`TextStyle` must use `WarnInk` or `textColor(...)` explicitly. Fills/bars keep `Warn`
- **Thermal alerts: `SensorPrediction.reached`** — at/over critical show the current state, never "will reach … in <1 min".
  Readiness / alert temperatures render via `p.displayTemp` (user unit); comparisons stay in °C
- **Colour = state on DRIVE / THERMAL (design pass)** — readings render in ink, the paint accent marks live/brand
  elements (BOOST value, sparklines, throttle, selected chips), and Warn / Orange appear only when a reading crosses
  an existing limit (`tempLevel()` with the preset warn/crit, `isTireLow` / `tireWarnPsi`, fuel < 10 %, knock counter,
  `isShiftPoint`). There is no battery-voltage limit — don't colour it. ThresholdBar's caution / redline zones are
  scale markings (like a tach redline), kept faint and always visible (owner wants them as reference points); the fill
  and value carry the state. Warnings lead DRIVE via StatusLine — temperatures through the readiness verdict, then tyre → fuel → knock
  (CRIT before WARN; the VEHICLE panel keeps the instant oil → coolant → PTU → RDU order). THERMAL trims padding/gaps (not reading sizes) when the page viewport is < 760 dp tall
  (`LocalThermalDensity`)
- **Connection state never inserts space** — RECONNECTING / TAP TO CONNECT render inside DRIVE's fixed 48 dp
  StatusLine (`LinkState`), and the header pill (RCN / OFF) covers the other tabs. `AnomalyStrip` is only for
  e-brake, launch control and update progress — don't add connection states back to it (they moved every page)
- **Scrolling pages pad with `pageBottomPadding()`** — the bottom nav overlay is NavBarHeight + the system gesture
  inset tall and content runs behind it; padding only `Tokens.NavBarHeight` hid the last ~24 dp (DRIVE ECONOMY). New
  scrolling pages must use `pageBottomPadding()` (or `navBarClearance()` without the page margin)
- **DRIVE layout never moves with motion** — the gear module height comes only from the page viewport
  (`(viewportH × 0.22).coerceIn(124, 176) dp`, digit 84 sp / 72 sp under 680 dp); no auto-zoom, no gear pop/scale (the
  old `driveAutoZoom` setting is removed). Only data availability (ECONOMY, recording strip, VEHICLE alert chips)
  may add rows below the instruments; the StatusLine is permanent so alerts only change its content. Verify by
  comparing card edges across `at:2.5 / 9.5 / 23` frames
- **Demo simulates only fields a real decoder writes** — `manifoldChargeTempC` and the IPC `warn*` lamps have no decoder,
  so they stay at their sentinels (DemoSimulatorTest guards this). Add a field to the simulator only with its decoder

## Theme Colors

v2.2.7 "Daylight" ships three discrete palettes selected via `setThemeMode(ThemeMode)`.
NIGHT is the default; DAY is legibility-tuned for bright ambient light; ULTRA is pure-black AMOLED.
AUTO resolves to DAY from 06:00 to 18:59 local (`HOUR_OF_DAY in 6..18`), NIGHT otherwise — it does not follow the
system dark/light setting. `Frost` is `Ink` — adaptive primary text.
Accents and semantic colors (Ok/Warn/Orange) shift per mode to preserve contrast.

```
NIGHT:  Bg #05070A  Surf #0A0D12  Surf2 #0F141C  Surf3 #141B26  Brd #162030  Dim #7A9AB8  Mid #B0D0E8  Ink #E8F4FF
DAY:    Bg #EFF3F8  Surf #FFFFFF  Surf2 #E4EAF2  Surf3 #D4DCE8  Brd #B8C4D4  Dim #556374  Mid #293748  Ink #080D16
ULTRA:  Bg #000000  Surf #050505  Surf2 #080808  Surf3 #0C0C0C  Brd #1A2230  (Dim/Mid/Ink share NIGHT values)
Accent  NIGHT #0091EA / DAY #0064B0    Orange  NIGHT #FF4D00 / DAY #D93900
Ok      NIGHT #00FF88 / DAY #00A85A    Warn    NIGHT #FFCC00 / DAY #E09500
```

Adaptive alpha helpers (`borderAlpha()`, `textMutedAlpha()`, `pillBgAlpha()`) scale dim-on-dark tunings
up on DAY so borders, muted labels, and status-pill fills stay above WCAG contrast on the white surface.

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
ThemeMode: NIGHT  (alt: DAY / AUTO / ULTRA)
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

Regenerate the FORScan catalog: `python3 android/scripts/gen_forscan_catalog.py` (writes forscan_modules.json only)

## Build

```bash
cd android
./gradlew assembleDebug                    # → openRS_v{ver}-staging-debug.apk
./gradlew assembleRelease                  # → openRS_v{ver}.apk  (main release)
./gradlew assembleRelease -PrcSuffix=rc.5  # → openRS_v{ver}-rc.5.apk
./gradlew test                             # 705 unit tests across 38 files
./gradlew lint                             # report: app/build/reports/lint-results-debug.txt (0 errors, 48 warnings rc.6)
bash scripts/install-debug.sh              # quick build + ADB install + launch (extra args → am start)
```

### Emulator / demo-mode workflow (verified 2026-10-22, Pixel 9 Pro XL AVD, Android 17)

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"     # adb is not on PATH by default
adb devices -l                                                  # emulator-5554 (AVD id Pixel_9_Pro_XL)

cd android
bash scripts/install-debug.sh                     # build → install → cold-start (normal, auto-connects)
bash scripts/install-debug.sh --es demo driving   # same, but straight into demo mode
# APK: app/build/outputs/apk/debug/openRS_v2.2.7-staging-debug.apk   App ID: com.openrs.dash.debug

# Control a running debug app (onNewIntent needs --activity-single-top):
adb shell am start --activity-single-top -n com.openrs.dash.debug/com.openrs.dash.ui.MainActivity --es demo warning
#   demo = idle | driving | warning | loss | cooldown | pause | reset | off
#   demo = at:<seconds> — replay the current scenario and pause at that time (repeatable screenshot frames;
#          DRIVING at:9.5 = mid-pull 3rd gear, at:2.5 = idle on the brake; WARNING at:50 = all alerts)
#   demo = collapse | expand — fold the control strip to a 2 dp amber line (header DEMO pill toggles it too)
#   trippreview = history | selected | recording | paused | summary | compare | replay | export | off — TRIP with in-memory sample drives
#          (demo/TripPreview.kt; negative ids, every write path no-ops for them; nothing reaches Room)
# Smaller phone check: adb shell wm size 1080x2160 && adb shell wm density 480  (360×720 dp; `reset` both after)
# Cold start with an extra: am start -S -n … --es demo idle   (without -S the intent is only brought to front)
```

**In-app:** ⚙ → DEVELOPER (debug builds only) → pick a scenario. While active, an amber strip under the header shows
`DEMO`, scenario time, a scenario menu (IDLE/DRIVE/WARN/LOSS), pause ❚❚ / ▶, reset ↺, exit ✕ — every control a
≥ 48 dp touch target; the header pill reads DEMO and the floating HUD shows "DEMO · simulated". Tap `DEMO 0:42 ▴`
to fold the strip (design reviews); tap the header DEMO pill to unfold it. The pill always says DEMO in demo mode
(`DEMO · RCN` during LOSS) — never drop the simulation label.
Exit leaves the app OFF ("TAP TO CONNECT") until you tap connect.

| Scenario | What to check |
|---|---|
| IDLE | Cold start: fast idle ~1200→850 rpm, vacuum, E-BRAKE strip, THERMAL "WARMING UP — NOT RACE READY" |
| DRIVING | 60 s loop: launch, pull to 125 km/h (shift bar, gear 1→6, boost ~23 psi), braking, ±0.9 g corner, cruise; gear module + panels must not move |
| WARNING | Oil/coolant/PTU critical, RL tyre 29→24 psi banner, KR 3–4° (knock badge + scatter), underboost + WGDC 98 %, LOW FUEL, 11.9 V; DTC scan returns P0217/P0171/P0325/P0562 |
| LOSS | 40 s loop: live 0–10 s → RCN/RECONNECTING, frozen values 10–22 s → TPMS/RDU sentinels ("— psi N/A") 22–26 s → live |
| COOL | Readiness walk-through (~215 s, real time): oil cools past warn (0–34 s, recovery hold) → READY → RDU forecast (~45 s) → RDU warn (~64 s), coolant invalid 85–100 s → RDU replies stop at 70 s, stale + warning dropped at 160 s → fresh poll 170 s → oil below 80 °C ≈ 205 s (ENGINE WARMING) → clutches past fixed warn ≈ 236 s / crit ≈ 248 s, cooling from 255 s |

Drive-mode dock / ESC buttons / DTC clear act on simulated state only (mode lands ~1.5 s after the command).

**Testing gotchas:**
- Android 17 relaunches an updated app's task from SystemUI ~1 s after `adb install` (no extras, CLEAR_TASK) — a
  launch sent immediately loses its extras. `install-debug.sh` waits 3 s then uses `am start -S`
- Mobile MCP's `uiautomator` dump fails whenever the app is open (infinite header animations — even with the demo
  paused) and each failed call blocks for minutes, delaying the next command. Tap by coordinates instead. Its
  screenshots can lag; `adb exec-out screencap -p` is always current. Check crops at full resolution — a 700 px
  downscale hid clipped digits once
- Debug builds always show "v2.2.7" (no RC label — only `-PrcSuffix` adds one) and Settings appends the update
  channel, so the version string can't tell builds apart. Verify by hashing `adb exec-out cat <pm path>` against the APK
- Settings categories animate in for ~2.5 s after the sheet opens — tapping a row sooner can land on the wrong one
- `--es demo at:N` replays the scenario **in real time** from 0 and pauses at N — wait N s before capturing
  (WARNING at:55 = oil + PTU past the STREET crit, coolant + RDU at WARN)
- The HUD overlay needs `adb shell appops set com.openrs.dash.debug SYSTEM_ALERT_WINDOW allow` on a fresh emulator
  (restore with `… default`); start it from ⚙ → DISPLAY → FLOATING HUD. A reinstall stops it

Requires `android/local.properties` (`OPENWEATHER_API_KEY=...`) and `android/keystore.properties` for release signing.
Version display format: `openRS_ v2.2.6` or `openRS_ v2.2.6-rc.1` (set via `-PrcSuffix=rc.1`).
RC builds MUST use `-PrcSuffix` so the APK filename reflects the RC version.

## Tests

```
android/app/src/test/java/com/openrs/dash/
  CanDecoderTest.kt           — 90+ tests: all passive-CAN decoders, 0x040/0x300 identity, 0x435 crosstalk guard, 0x1A4 unsigned + 55°C guard, CAN freshness stamps
  ObdResponseParserTest.kt    — 68 tests: PCM/BCM/AWD/PSCM/FENG/RSProt DIDs (rc.6: TIP /128, F404/F40E/F415/F423/0548,
                                decoder-coverage guards: every polled PCM/BCM/AWD DID must decode + stamp)
  DriveStateTest.kt           — 39 tests: fuel economy, averages, haversine, peaks, sentinels
  VehicleStateTest.kt         — 31 tests: conversions, AWD split, peaks, TPMS, RTR
  AppVersionTest.kt           — 30 tests: version code/name, RC suffix, build config
  DtcScannerTest.kt           — 28 tests: SAE J2012 decoding, UDS status bit priority
  SlcanParserTest.kt          — 20 tests: standard/extended frames
  PidRegistryTest.kt          — 18 tests: catalog loading, DID lookup, fallback decoding
  UserPrefsTest.kt            — 16 tests: conversion math, sentinel edge cases
  IsoTpBufferTest.kt          — 12 tests: SF/FF/CF reassembly
  RingBufferTest.kt           — 10 tests: capacity, overflow, iteration
  KnockLoggerTest.kt          — 9 tests: KnockStats aggregation, buckets sum to count, per-cylinder, knock CSV v2
  KnockCaptureTest.kt         — 4 tests (rc.5): per-cylinder, pre-drive rejection, repeat values, threshold
  KnockSessionLogTest.kt      — 5 tests (rc.6): fresh-only session capture, pre-session rejection, pulls, cap, reset
  PullTrackerTest.kt          — 7 tests (rc.6): threshold, min duration, gap split, knock attribution
  KnockScatterMathTest.kt     — 4 tests (rc.6): tap hit-test, overlap → worst KR, boost text / gridlines follow the unit
  BoostSessionLogTest.kt      — 6 tests (rc.6): fresh-only TIP, stale target/WGDC → null, pre-session, 90 s window
  BoostSweepMathTest.kt       — 4 tests (rc.6): half-open bins, per-channel means, gaps, min Y span
  BoostSweepStatsTest.kt      — 16 tests: delta math, WGDC saturation, loaded-sample filter, fresh-only sentinels, WGDC at peak, by gear
  PcmPollScheduleTest.kt      — 6 tests (rc.5): fast/slow coverage, rotation, engine-off sweep
  FreshnessGateTest.kt        — 5 tests (rc.5): pre-drive stamps, consume-once, per-tag tracking
  TimeSeriesMathTest.kt       — 5 tests (rc.5): gap bridging/breaking, shared-axis range
  FuelTrimTest.kt             — 8 tests (rc.5): warm/fresh gating, min samples, LEAN/RICH drift alert
  DriveExportBuilderTest.kt   — 59 tests: CSV/GPX/JSON builders, schema v2, fuelTrim export, knock pulls, boost sweep by gear
  DemoSimulatorTest.kt        — 7 tests: determinism, LOSS freeze / recovery sentinels, freshness stamps, no-decoder sentinels,
                                WARNING thresholds, DRIVING plausibility + continuity
  ThermalReadinessTest.kt     — 32 tests: warm-up target + hold, delayed evaluation, drop below minimum, warn / crit recovery holds,
                                forecast + clearing hold, warning with missing data, stale-warning removal, preset change, offline /
                                reset, missed AWD polls (90 s, incl. trans oil), stamps never refreshed, shared freshness, units, clutch
                                limits / recovery / stale, checklist incomplete rows, VEHICLE advisory, banner wording, COOLDOWN sequence
  ReplaySeekTest.kt           — 2 tests: replay ±10s seeks by timestamp (1 Hz and sparse data), clamps, steps across gaps
  FuelEconomyConcurrencyTest.kt — 2 tests: concurrent onUpdate / reset never throw (demo-switch crash), reset is clean
  PendingSummaryTest.kt       — 2 tests: post-drive summary stays queued until consumed, newer drive survives an older close
  TyrePressureLevelTest.kt    — 6 tests: THERMAL tyre state + DRIVE tyre alerts follow the saved Low / Warn / High
                                (boundaries, custom, PSI = BAR, over-inflation alert, missing readings)
  ForegroundControllerTest.kt — 6 tests: idle bound service never foreground, self-start always followed by startForeground,
                                demote leaves both, refused start / startForeground leave nothing started
  (plus LapTimer, PerformanceTimer, FuelEconomy, CrashTelemetryBuffer, NrcTracker, ThermalPredictor, DiagnosticExporter)
```

Current count: **705 tests across 38 files** (Android) + **168 vitest across 6 files** (Sapphire — run `web/node_modules/.bin/vitest run` and `tsc -b`).

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
2.1  Knock event logger       — SHIPPED v2.2.7-rc.4 (0x03EC retard scatter + persistence)
2.2  Fuel trim drift tracker  — SHIPPED v2.2.7-rc.5 (warm-engine STFT/LTFT per drive, PERF trend card, ±8 % alert)
2.3  Boost target vs actual   — SHIPPED v2.2.7-rc.4 (TIP desired/actual + WGDC, DriveTimeSeries); TIP scale fixed +
                                live POWER sweep chart, WGDC @ peak, boost by gear in rc.6 (#175 complete)
```

See `docs/feature-roadmap.md` for full pipeline.
