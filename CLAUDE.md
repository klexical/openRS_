# openRS_

Native Android telemetry dashboard for the Ford Focus RS MK3.
Kotlin 2.0.21 + Jetpack Compose (Material3). Current: **stable v2.2.6 on main**; **v2.2.7-rc.6 published as a prerelease from staging** (tag `android-v2.2.7-rc.6` →
`d2f7dc6`, versionCode 35 — not on main, not stable). Sapphire (web/ only) deployed from main `0d64248`. Record: Branch / Release Workflow → rc.6.
**rc.7 = local development + hardware testing — NOT a published release (no `android-v2.2.7-rc.7` tag; unpushed).** Testing candidate
`a605b1c` frozen + owner-tested 2026-10-31; approved fixes + source-review corrections + #234 fix accepted and fast-forwarded
locally into `feature/rc7` (`931447a`), then docs-only `12b6e4f` (no rebuild). Owner-test APK = source `81baa76`, vc 36,
SHA-256 `efe622c4…7a98` — owner Bluetooth hardware testing on it **pending** (kept separate from the new candidate).
**Integration candidate — DO NOT PUSH** (branch `integration/rc7-candidate-DO-NOT-PUSH`, worktree `rc7-integration`; its
ancestry holds real adapter identifiers until the approved history cleanup): `12b6e4f` + #228 / #226 + sanitized test +
#235 CI keys + #228 edits-only-write + docs. **Tested source `7bb8a9a`** (1034 / 81 per variant, lint 0 / 45, APK
`7ee08484…`, vc 36, disposable-AVD cold launch); later commits docs-only. Not distributed. Record: Branch / Release
Workflow → rc.7 (integration). Issues #204–#235. Branch `feature/rc7` (from staging `d2f7dc6`) — 2.2.7-rc.7 / versionCode 36, milestone
v2.2.7-rc.7 (#211 locale exports, #212 connection lifecycle, #213 splash per task, #214 versioning, #215 DTC scan status);
Sapphire row validation #204 also on `fix/sapphire-row-validation` (from main). Record: Branch / Release Workflow → rc.7.
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
  BleSlcanTransport.kt    — BLE GATT transport: connects and forwards BluetoothGatt callbacks to BleGattCore; GattLink over
                            BluetoothGatt. autoConnect=true after a first success with that MAC (per process), MTU 247 w/ fallback
  BleGattCore.kt          — rc.7 — Android-free setup sequence / writes / framing (#218): layout from BleAdapterProfile (Pro
                            FFF0/FFF2/FFF1, USB FEE0/FEE1, legacy FFE0) chosen from discovered services + characteristic
                            properties (write type, notify vs indicate); bonds once; every wait bounded (write callback 2 s).
                            A write timeout closes the link (the callback names no write — a late one would ack the next)
                            BleTransportTest drives it with a fake GATT (JVM only)
  BleDeviceScanner.kt     — BLE device discovery filtered to FFF0 / FEE0 / FFE0 (BleAdapterProfile.SCAN_SERVICES)
                            WARNING: 0xFFE0 is shared by many cheap BLE devices (LED controllers, HM-10 clones)
  SlcanConnection.kt      — Shared connection: retry, SLCAN init, firmware probe, OBD pollers,
                            frame dispatch, DTC scan/clear, ISO-TP reassembly, FPS tracking.
                            SLCAN handshake: waits 3s for valid frame after init before declaring Connected
                            Constructor takes transportFactory: () -> SlcanTransport (+ rpmProvider for fast poll)
                            Single PCM sender — an NRC is attributed only when every outstanding request is one DID and no
                            earlier request is still unanswered (no time releases one — only its own positive reply); queue
                            eviction marks the connection unresolved; 300 ms = poll pacing only; failed writes withdrawn;
                            7F 22 78 keeps the request outstanding (PcmRequestLedger, #216). OPENRS? probe schedule
                            on its own coroutine (×3, give-up 13 s, late replies accepted, #220); DTC frames diverted by
                            service ID (#215)
                            Command response channel routes +FRS: lines separately from SLCAN frames
  AdapterState.kt         — Disconnected / Connecting / Connected / Reconnecting / Idle / Error (sealed, `label` = fixed
                            names for logs — R8 renames the classes) + LinkPhase (OFF/CONNECTING/CONNECTED/RECONNECTING/FAILED)
  SlcanParser.kt          — SLCAN frame tokenizer ('t'=standard, 'T'=extended)
  IsoTpBuffer.kt          — ISO-TP SF/FF/CF reassembly; caller sends BCM_FLOW_CONTROL after FF
  EscCommand.kt           — rc.7 — ESC request + confirmation (#125): window = firmware press plan + 3 s; only a 0x1C0 frame
                            stamped after the request on the same link generation confirms; one request at a time (Busy)
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
  BoostRule.kt            — rc.7 — BoostSource / BoostReading / BoostRule (policy rc7-boost-v3) / BoostSession / StoredBoost
                            (#207): the one boost rule for every consumer + what a recorded value is; see the boostKpa gotcha
  DriveDatabase.kt        — Room DB v13: DriveEntity (+ avgStftPct/avgLtftPct/trimSamples v11, pullCount/knockPullCount v12,
                            peakBoostSource/boostPolicy v13), DrivePointEntity (+ boostSource/baroKpa v13), KnockEventEntity
                            (+ cylinder v11, boostSource v13), DtcCodeEntity (+ failureType v13), DriveDao, migrations
                            v1→…→v13 (v12 rescales stored TIP ×903.81/128; v13 columns nullable, never backfilled — null =
                            recorded before the rule; checked on the emulator from a real v12 DB)
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
  DriveThermalRecord.kt   — rc.6 — Drive thermal record (thermalPeaksJson v2): DriveThermalTracker (recorder, profile segments),
                            DriveThermalReview (TRIP TEMP badge + summary; PROFILE / LEGACY array / NONE), 95 % of crit in °C
  TripState.kt            — PeakType enum (RPM/BOOST/LATERAL_G/SPEED) + PeakEvent data class
  WeatherData.kt          — OpenWeatherMap snapshot: temp, wind, humidity, icon-to-emoji mapping
diagnostics/
  DtcScanner.kt           — UDS 0x19/02 scan across PCM(7E0)/BCM(726)/ABS(760)/AWD(703)/PSCM(730)/GFM(7D2); runScan/interpret =
                            the one path (full scan, auto-scan, retry); only DtcReply.Positive → OK (#215)
  DtcScanLedger.kt        — rc.7 — the session's DTC export record (DiagnosticLogger.dtcScans): begin (RUNNING) / progress /
                            complete / fail / cancel / interrupt / retry; cleared by sessionStart, stale tickets can't publish
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
  LinkOwnership.kt        — rc.7 — process-wide connection ownership (#212): generation, phase, manual-connect block, cancel
                            policy; autoStart / publish / deliver run under one lock (LinkOwnershipTest)
  LinkPresentation.kt     — rc.7 — AdapterState → LinkPhase, notification text (+ recording / paused), offersCancel (the one rule)
  AutoRecordPolicy.kt     — rc.7 — auto-record on a new connection: still recording → keep (never split), paused ≤ 15 min → resume
  DriveRecorder.kt        — Room-backed drive recorder: start/stop/pause/resume, 1 Hz GPS + telemetry
                            FusedLocationProviderClient @ 1 Hz; batch writes (30 points/flush); peak tracking
                            Per-drive state = `DriveRun` (#236); STOP = one-transaction re-read + recorder columns; feeds
                            `LapTimer` every recorded fix (#237). Seams (geocode, LocationSource, onSummary) for tests only
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
                            alert chips, tap → THERMAL), chassis panel (AWD REAR TORQUE L / R Nm + G + torque). vehicleStatus() is
                            the single alert list for banner + panel. Disconnected → "—"
  PerfPage.kt              — PERF tab: G-force plot + dynamics numeric readout + PowerPageContent
                            (AFR, throttle/boost, engine mgmt, fuel trims, knock sensors). When offline,
                            sections force-collapse + a "CONNECT ADAPTER TO POPULATE" hint renders at top
  PowerPage.kt             — PowerPageContent composable shared by PERF + CustomDash
  ChassisPage.kt           — UnifiedChassisSection (THERMAL): TYRES panel (TyreCells around the original CarDiagram
                            top-down RS art, tyres tinted per state) and AWD panel (L/R torque diagram + `awdDetailRows`; REQ L/R
                            by their own stamps, 90 s; PTU/RDU/clutch/trans temps live in DRIVETRAIN, not here). GForceSection for PERF
  TempsPage.kt             — THERMAL tab: ReadinessCard (verdict headline + reason, STREET/TRACK/RACE chips, collapsed
                            CHECKLIST), one predictive-alert banner (soonest forecast, names the rest), ENGINE (full-width oil + coolant EngineTempCard, scale + WARN/CRIT legend),
                            DRIVETRAIN panel (PTU/RDU/clutch L-R/trans oil rows), AIR (compact intake/ambient), tires
                            & AWD, collapsible DETAIL (compact). Offline → "AWAITING CAN"
  Instrument.kt           — Design-pass building blocks: Level (NORMAL/COLD/WARN/CRIT/STALE/NONE), tempLevel(),
                            instrumentSurface() (white + hairline on DAY, no shadow), InstrumentLabel/InstrumentMeta
                            (10 sp), InstrumentHero, StatusCell, AlertChip, ThresholdBar (caution + redline zones) +
                            ThresholdLegend, MiniReadout, Telltale, ReadoutGrid/Readout (grouped readings panel, dense +
                            footer; POWER + TRIP). Symbols / car art / wordmark / launcher come from the owner-approved
                            concept sheet (BrandArt.kt, res/drawable*/) — never redraw or substitute them unasked
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
  BottomNavBar.kt         — Custom bottom nav: 5 Precision-family icons (nav_<tab>_whole|base|accent alpha layers — inactive
                            whole glyph in Dim, selected two-tone; DAY selected ink = actionInk), frosted glass via Haze (hazeEffect),
                            spring-animated indicator bar, one top hairline, pressClick + haptics
                            Height: Tokens.NavBarHeight (48 dp tabs) + sysNavPad (gesture area)
  BrandArt.kt             — Concept-sheet artwork in code: Wordmark (traced glyphs, per-glyph alpha for the header
                            typewriter), RsIcon + TelemetryIcon (NORMAL two-tone / MUTED / SELECTED / WARNING / CRITICAL /
                            UNAVAILABLE), OpeningPresentation (cold start; fades once the dashboard has drawn — no timer).
                            Raster art: drawable-nodpi/rs_topdown_body|tyres, rs_splash_car, rs_launch_art (ABOUT)
  ThermalProfile.kt       — Thermal profiles (°C): built-in STREET / TRACK / RACE (existing openRS_ defaults, no manufacturer
                            source) + optional Custom (JSON in prefs `thermal_custom`). UserPrefs warn/crit getters read
                            `p.thermalProfile`; ThermalCell keeps exact °C until edited (no unit drift); validateThermalProfile
  ThermalReadiness.kt     — ThermalReadinessSession (app-level, 1 Hz, owns the ThermalPredictor) → ThermalVerdict for DRIVE's
                            StatusLine, THERMAL ReadinessCard + checklist and the DriveRecorder RTR marker. States: NO LIVE DATA ›
                            TEMP CRITICAL › TEMP HIGH › HEAT RISING › CHECKING › ENGINE / DRIVETRAIN WARMING › TEMPS READY.
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
  BleDevicePickerDialog.kt — BLE device picker: scan filtered to FFF0 / FEE0 / FFE0 (BleAdapterProfile.SCAN_SERVICES), RSSI bars
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
    CarDiagram.kt           — TopDownCar: approved top-down RS art (body in ink, tyres tinted per state, TOPDOWN_ASPECT,
                            WHEEL_ANCHORS measured from the tyre layer)
  PidBrowserSection.kt    — DIAG tab: expandable FORScan catalog per module with coverage bar
  DidProberSection.kt     — DIAG tab: interactive Mode 22 scanner for any ECU+DID
  WhatsNewDialog.kt       — Version changelog dialog; shown on first launch after update
  trip/
    DrivePage.kt          — MAP tab: live mode (Google Maps + HUD + controls) + history mode (drive list)
                            Hoists cameraPositionState; floating controls: color mode, map type, weather,
                            zoom in (+), zoom out (−), locate/recenter (◎)
                            onMapTouched callback: signals parent pager to disable swipe while map is touched
    DriveHistory.kt       — rc.7 (#228) — TRIP history: observe (Room Flow, the list's only source) + delete / tags / rename
                            (writes only — never re-read the list by hand)
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
0x0F8  oil temp (byte1−50°C), boost (byte5, gauge — see the boost gotcha), PTU temp (byte7−60°C)
       — oil / PTU byte identity disputed by a contributor's DBC (#229): unconfirmed until a capture; never swap silently
       — 2026-11-01 offline review: no export holds a positive 0x6017 (ICM 0x78C) / 0x070D (PCM 0x7E8) reply, so no paired
         independent reference exists; the app polls neither (AWD trans oil = 0x1E80, not 0x1E3F). Oil and PTU identity
         each inconclusive; thermal acceptance unresolved; no swap / threshold change / history rewrite authorised
0x130  speed (bytes 6-7 BE × 0.01 kph)
0x138  clutch pedal % (17|10 × 0.1) — decoded for diagnostics only; NOT shown on DRIVE (#225)
0x160  longitudinal G (bits 48-57 LE)
0x180  lateral G + yaw rate + vertical G
0x190  wheel speeds FL/FR/RL/RR (4 × Motorola 15-bit × 0.011343 km/h)
0x1A4  ambient temp from MS-CAN bridge — byte4 **UNSIGNED** × 0.25°C (DigiCluster v1.0.46 fix); > 55°C rejected (#185)
0x1B0  drive mode nibble (byte6>>4); combine with 0x420 to distinguish Sport vs Track
0x1C0  ESC mode (2-bit at bit position 10)
0x225  launch control engaged (byte5 bit3)
0x230  gear (4-bit) — does NOT broadcast on this car
0x252  brake pressure (12-bit Motorola)
0x2C0  AWD actual left/right torque Nm — RS_HS.dbc AWDmsg02 19|12 / 51|12 Motorola (MSB-first 20 / 52); requested L/R
       at 3|12 / 35|12 not decoded; drivetrain reference point undocumented (#226). Until 2026-10-29 read bits 0|12 / 12|12.
       DBC excerpt + source: android/app/src/test/resources/dbc/ — a second decoder checks the extraction arithmetic only,
       not the physical meaning. Front/rear % (rear ÷ 0x070 engine torque) is WITHDRAWN everywhere — not a physical split;
       saved dashboard cells read "WITHDRAWN …" (`DashLayout.WITHDRAWN_FIELDS`), never deleted
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
- `sparkAdvance` = `-99.0` until received — negative advance is valid (#205)
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
- **Live pressure = `BoostSession.gauge(vs, nowMs)` = TIP only; recorded = `read()` (#207, policy `rc7-boost-v3`)** — DRIVE,
  HUD, TRIP live readouts and Custom Dashboard show TIP − measured baro or "—"; they never switch to CAN 0x0F8 in a TIP gap
  (no stretched freshness, no held value). Recording, knock events and perf runs keep `read()` with the source per point, so
  a drive can hold CAN stretches the gauge showed as "—" (by design, stated in What's New / #207). TRIP chart pressure
  channels break at every unavailable / other-source sample, also after downsampling (`TimeSeriesMath.bucketValidity`);
  knock scatter draws only events of the axis's source; RaceChrono / TrackAddict carry NO pressure column (their only one
  is "Boost"); Sapphire converts plotted values / axes / tooltips / peaks to the unit (`lib/boost.ts`). `read()`: `boostKpa` is CAN 0x0F8 byte 5 + 101.325 (gauge,
  std-atm form, independent of baro) and is a reading only once stamped (`boostKpa`, ≤ 3 s old); the 101.325 default is
  never a reading. Rule = a fixed order, **no inference between the channels** (the v2 "CAN not tracking at ≥ 80 % throttle"
  vote was removed 2026-10-29 — owner did not approve it; throttle there is pedal on cars without 0x076): TIP − measured
  baro (TIP ≤ 2.5 s, rpm > 400 on a CAN frame ≤ 2 s old, baro 60–115 kPa) labelled **TIP**; else CAN 0x0F8 (fresh)
  labelled **BOOST**; else none ("—", recorded as source NONE). BOOST_SOURCE logs source changes (≤ 1 line / 10 s). Every
  reader of recorded boost goes through `StoredBoost` (reading + source / legacy "source not recorded" / none): trend and
  compare only within one kind, chart lines per source, map / RaceChrono / TrackAddict use the drive peak's source only;
  bookmark / lap / perf-run boost has no source column → not shown or exported. Sapphire mirrors it (`web/src/lib/boost.ts`).
  Freshness uses a wall clock (`wallClockMs()` in composables, a 1 s tick in the HUD) — never `lastUpdate`. Peaks are per
  measurement (BoostSession), captions in the selected unit. TIP cells / sweep chart / TARGET read vs measured baro;
  by-gear stats use stored baro or say std atm. VehicleState has no boost getters; `withPeaksUpdated` doesn't touch
  `peakBoostPsi`. Drives store per-point source + baro and the peak's source; never infer them for old rows. Which
  pressure byte 5 measures and TIP ≈ MAP at WOT are unverified on the car
- TPMS PID `0x280B` is multi-frame ISO-TP — send `BCM_FLOW_CONTROL` after receiving First Frame
- Extended session (`0x10 0x03`) required before: RDU_STATUS (AWD 0x703), PDC (PSCM 0x730), FENG (0x727), RSProt (0x731)
- Firmware drive mode is **hybrid scroll-then-wait** (v1.61+) — pre-calculates scroll count, sends all presses open-loop, waits up to 6s for auto-confirm. Do NOT revert to closed-loop press-and-poll (v1.6 bug) or pure open-loop press counting (v1.5 bug). The Focus RS mode selector GUI requires ~4s of inactivity to auto-confirm — CAN does not update during scrolling
- Firmware REST POST `/api/frs` returns `{"ok":false,"busy":true}` when a drive mode change is in progress — app must handle this
- `fengTimedOut` / `rsprotTimedOut` = true after 3 failed probe cycles — do not suggest retrying indefinitely
- **Drive mode cold-start gate** — `has420Arrived` flag in CanDecoder prevents Sport/Track resolution until the first `0x420` frame is received. Without this gate, `0x1B0` nibble=1 resolves against stale `modeDetail420` default during the 0-600ms blind window after connection, causing wrong mode flashes. The drive mode confirmation loop in MorePage adds a 2s post-command settling delay, a 15s timeout, and auto-correction if the car lands on the wrong mode (sends a corrective command automatically).
- Android 10+ silently routes new sockets through cellular when WiFi has no internet — `WiFiFirmwareApi` uses `Network.socketFactory` via `ConnectivityManager` to force all traffic on WiFi. BLE transport avoids this entirely (not a "network" in Android's routing model)
- **BLE write timeout = link closed (#218)** — Android's `onCharacteristicWrite` names no write; after a 2 s timeout a late
  callback would complete the next write. `BleGattCore` invalidates + closes the link (reader EOF → SlcanConnection
  reconnects); later writes fail at once. `SlcanConnection` swallows write errors, so teardown must come from the reader.
- **BLE GATT layouts (#218)** — upstream WiCAN Pro v4.45p–v4.48p: FFF0, write FFF2, notify FFF1; USB v4.13u–v4.21u: FEE0, FEE1
  write + notify; both need an encrypted, MITM-authenticated (bonded) link (upstream source reading, not a capture). The
  FFE0/FFE1/FFE2 layout is a **planned, unimplemented** interface: `firmware/components/ble_transport/ble_transport.h` is a
  forward declaration for a future fw v2.x; openrs-fw v1.x keeps upstream wican-fw's `ble.c` at the pinned tag (USB
  v4.20u_beta-01, PRO v4.48p — `firmware/patches/` doesn't touch it). The app keeps FFE0 as an unverified fallback (v2.2.6
  scanned / connected FFE0 only). Which layout an openrs-fw adapter advertises is **unresolved** — no GATT trace yet; a
  successful BLE session (owner, `a605b1c`) doesn't name the layout. Never infer a shipped layout from that header. Main's
  firmware/README + hardware-setup are corrected on `docs/setup-guides-ble-fw` (`a388e90`). FFF0 / FFE0 are also used by unrelated cheap BLE peripherals; the scan filter passes them through. `BleDevicePickerDialog` shows an orange "not a known adapter" hint for unrecognized device names. `SlcanConnection` has a 3s SLCAN handshake that rejects non-SLCAN devices before declaring Connected
- **BLE auto-reconnect** — `autoConnect=true` on `connectGatt()` once setup has succeeded with that MAC in this process
  (`BleSlcanTransport.connectedBefore`; an instance flag was dead — every attempt builds a new transport). 15 s setup
  timeout; on timeout `SlcanConnection` retry logic takes over. Not yet seen on a phone with an adapter
- **BLE MTU** — `requestMtu(247)` requested after service discovery. If `requestMtu()` returns false (unsupported), proceeds with 23-byte default. SLCAN frames mostly fit; longer frames arrive as multiple BLE notifications and are reassembled by `lineBuffer`
- **BLE permissions must be requested at scan time** — `BleDevicePickerDialog` handles runtime permission requests (BLUETOOTH_SCAN + BLUETOOTH_CONNECT on API 31+). Do NOT rely solely on `MainActivity.onCreate()` permission requests — the user may switch to Bluetooth after app startup. `BleDeviceScanner.startScan()` also has a SecurityException safety net
- **Foreground service only while connected** — `MainActivity.startSvc()` only **binds** `CanDataService`. `startConnection()`
  promotes it via `ForegroundController.promote()` (startForegroundService(self) immediately followed by startForeground);
  `stopConnection()` demotes (stopForeground + stopSelf), so an idle service lives only while the activity is bound. Never
  call `startForegroundService()` without a guaranteed `startForeground()` — the old per-`onCreate` call ANR'd whenever the
  activity was recreated offline / in demo. `promote()` is in a try/catch because WiFi/Bluetooth callbacks can fire when the
  app is backgrounded (Android 12+ refuses the start). `manualConnectRequired` is process-wide (`LinkOwnership` in the companion) because the idle
  service instance is recreated with the activity
- **Connection ownership + Cancel (#212)** — every connection transition goes through `CanDataService.link`
  (`LinkOwnership`, process-wide): callback starts use `autoStart` (check + start in one step), a job publishes its phase
  with `publish(gen, …)` and applies late results (OBD merges posted to Main, CAN frames, WiCAN status, auto-scan counts)
  with `deliver(gen) { }` / `owns(gen)`. Never add a delayed write from a connection job without that check. Cancel has ONE
  rule — `LinkPresentation.offersCancel(phase, recording)`: initial attempt and no recording (paused counts) — enforced in
  `LinkOwnership.cancel` when the request is handled (stale notification actions are ignored) and mirrored by the pill /
  DRIVE (`cancelable`). An explicit Cancel is **persisted** (`LinkOwnership.CancelStore`, prefs file `openrs_link`, loaded
  first in `CanDataService.onCreate`) until Connect / Retry — Home, Back, Recents, process death and Force stop keep it;
  the demo hold is memory-only. Raw CAN frames and auto-record go through `LinkSession` (`deliver(gen)`); blocking work
  (perf-run insert) runs after the lock. `reconnect()` (RCN tap) builds a fresh SlcanConnection, so it reads CONN; while
  recording it can't be cancelled
- **Retry while recording** — `keepRetrying` (= recording) extends retries past `maxRetries` with a 30 s backoff polled every
  second; when recording ends the bounded policy applies at once (budget spent → Idle → service released). Notification
  text is re-published on recording start / pause / resume / stop. Auto-record never splits a drive still recording
  (`AutoRecordPolicy`)
- **Transport abort** — TCP / WebSocket assign their socket before `connect()` so `abort()` ends a blocked connect at once
  (emulator: ≤ 0.5 s). Without a cancel, an adapter that accepts but never answers (WS upgrade / SLCAN handshake) holds
  the attempt for the 20 s socket read timeout — the 3 s handshake `withTimeoutOrNull` can't interrupt a blocking read
- **DTC scan truth (#215)** — a module is OK only for a valid positive `59 02 …` reply (`DtcReply.classify`); 7F 19 NRC →
  REJECTED, malformed → MALFORMED, 7F 19 78 waits (P2* 5 s, cap 12 s) → else PENDING, none → TIMEOUT. The export record
  is published only through `DiagnosticLogger.dtcScans` (`DtcScanLedger`, session-scoped); never write a DtcScanRecord
  directly. Records are ISO 14229 4-byte (DTC high, mid, failure type, status — python-udsoncan), a reply must be whole
  records; only replies to the operation's service are diverted from the frame loop (poll / `50 03` replies take the normal
  path). DIAG goes through `DtcScanView` + ledger together — screen, export and the header count publish only when the
  ledger accepts the ticket (the auto-scan uses `DtcScanView.autoScan`, same gate). Fault identity = module + code +
  failure type (`displayCode` "P0123-1C"; saved v13; older rows match by base code, said on screen); NEW / "not found" only
  over modules read OK in *both* scans (the previous scan's own statuses). No real-module capture
  yet. Freeze-frame sub-function numbering (app 04 / 05 vs ISO 03 / 04) is unverified, best-effort
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
- **Freshness stamps (`fieldLastUpdateMs`)** — Mode 22 decoders stamp via `markFresh()`; `ObdMerge.merge()` (data/ObdMerge.kt, called by `CanDataService.mergeObdState()`) MUST merge them with
  `VehicleState.mergeStamps` (per-key max) or the UI reads every OBD field as STALE (pre-rc.5 bug). CAN-sourced THERMAL fields are stamped by `CanDecoder.stampFresh()` throttled to 1 s per ID — never `markFresh()` per CAN frame (map copy at hundreds of fps). A decoder returning the **same** `state` instance means "recognized, nothing changed": `processCanFrame` logs it and skips the copy/emit (0x040, 0x300, rejected 0x1A4). `ObdMerge` keeps a field through its "not received" sentinel; a field whose
  valid range includes its default (REQ L / R: 0 Nm) is merged by the reply's own stamp (`ObdMerge.carried`), never by
  value — a `> 0` guard keeps the old value under a fresh stamp (#226)
- **Recorder only stores fresh polled values** — `DriveRecorder` writes TIP/target/WGDC/KR/STFT/LTFT only when `FreshnessGate.takeIfFresh()` passes; otherwise sentinels (`tipActualKpa`/`boostTargetKpa` 0.0, `wgdcPct` −1.0). Never record held VehicleState values for slow DIDs — they repeat one reading many times
- **A drive's state belongs to its `DriveRun` (#236)** — `startDrive` creates a new `DriveRun` (points buffer, recent
  points, start temps, freshness gate, trims, pulls, thermal tracker); that drive's loop and STOP hold it, and the loop's
  live-state writes are gated on its own drive id. Never move per-drive state back into recorder fields: auto-record's
  finalize + start and START right after STOP overlap the old drive's finalization. STOP re-reads the row inside one Room
  transaction and writes only recorder-owned columns (the user's name / tags win; a deleted row gets no laps, thumbnail or
  summary). The loop feeds `LapTimer` every recorded fix (#237); STOP's `takeLaps()` re-arms the same S/F line. Known: a
  one-fix window where the old loop's last fix reaches the next drive's timer; a fix without bearing reads 0°; lap
  `peakBoostPsi` is a mixed-source `read()` value — never display or export it. Tests use the real Room database through
  test-only Robolectric 4.14.1 on SDK 35 (`src/test/resources/robolectric.properties`; the first run downloads android-all)
- **KR sign convention** — `ignCorrCyl1..4 = raw / −512` → retard is **POSITIVE**. Knock capture uses `≥ 0.1°`, the header counter and POWER KR cells `> 1°`. Cyl 2–4 DIDs (0x03ED–EF) are unverified — 0x03ED/0x03EE NRC 0x31 on two RS PCMs (EU tuned #182 + user's car), so not tune-specific; 0x03EF answers but read 0 for a whole drive. NrcTracker suppresses 2/3; polling left as is (user decision 2026-10-22)
- **TIP is `raw / 128` kPa absolute** — 0x033E/0x0466 (DigiCluster, on-car verified). rc.4/rc.5 used `/903.81`, which is the Ford-DID **psi** formula, hiding TIP and starving BOOST SWEEP ([#198](https://github.com/klexical/openRS_/issues/198)). Do not reintroduce 903.81
- **Mode 01 equivalents live in Mode 22 `22F4xx`** — load (F404), timing (F40E), O2 V (F415), STFT/LTFT (F406/07), λ (F434/F444). `ObdPids.kt` (the Mode 01 path) was deleted in a7b01d0 and silently took the load/timing/AFR decoders with it — if a VehicleState field has no writer in ObdResponseParser/CanDecoder, it is dead. HP fuel is **0xF423** (DI gauge, ×10 kPa); 0xF422 is the port-injection PID and is rejected. LP fuel = **0x0548** (`raw × 0.5` kPa)
- **Throttle may not broadcast** — CAN 0x076 is absent on some cars (`throttleHasSource` stays false). Anything keyed on throttle (PullTracker, knock snapshot, recorded throttle) must read `throttleOrPedalPct`, never `throttlePct` directly
- **PCM "no data" replies** — 0x0461 charge air answers `0x0000` on two RS PCMs; the decoder treats that as no data (stays −99). BCM 0x4028 battery SOC read raw 5 (= 5 %) at 14.7 V all session on the user's car — unverified, compare with FORScan BAT_ST_CHRG before trusting it
- **Adding a polled DID** — put the query in `ObdConstants` AND a `markFresh` decoder in `ObdResponseParser`; the coverage-guard tests in `ObdResponseParserTest` fail otherwise. Removing a decoder fails them too (that's the point — #198). Multi-frame ISO-TP DIDs (BCM 0x280B) are excluded explicitly
- **Polled values go stale (#217)** — POWER and Custom Dashboard use `PolledFreshness` (fast group 2 s, others 40 s, stale past
  2×): POWER dims with STALE Ns, the dashboard shows "— —". NRC suppression (#216) needs 2 attributed NRCs (attributed only
  when unambiguous — see SlcanConnection; a timeout or the 32-entry queue limit is a resource limit, never proof of ownership,
  so after one lost reply NRCs stay unattributed for that connection), recovers on a positive reply and re-probes after 60 s (provisional settings);
  the PCM poller always suspends. Emulator capture current → STALE → recovered: fake_ecu_v2.py `--stale DID:A:S`.
- **POWER cells are gated per field** — engine cells show only when `rpm > FAST_POLL_MIN_RPM` AND `fieldLastUpdateMs[tag] != null`; never gate on another field's value (the old `calcLoad > 0` gate hid everything once load lost its decoder)
- **Lint version is pinned above AGP** — `android/gradle.properties` sets `android.experimental.lint.version=8.13.0` because AGP 8.7.3's bundled lint crashes (`IncompatibleClassChangeError`) on the lint checks shipped in Compose BOM 2025.11 / Lifecycle. Don't remove it unless AGP is upgraded to ≥ 8.13. `abortOnError = false`, so lint findings never fail CI — only crashes do; check `app/build/reports/lint-results-debug.txt` for errors ([#196](https://github.com/klexical/openRS_/issues/196))
- **`MutableList.removeFirst()/removeLast()` crash below API 35** — with compileSdk 35 they resolve to `java.util.List.removeFirst` (Android 15+) and throw `NoSuchMethodError` on Android 9–14 (minSdk 28). Use `removeAt(0)` / `removeAt(lastIndex)`. `ArrayDeque.removeFirst()` is fine
- **Location permission** — always request `ACCESS_COARSE_LOCATION` together with `ACCESS_FINE_LOCATION` (Android 12+ requirement); recording still needs FINE granted
- **Release identity (#214)** — one definition at the top of `android/app/build.gradle.kts` (base / stage / candidate / versionCode) drives versionName (`2.2.7-rc.7`), the Settings label (`2.2.7-rc.7 · Build 36`), the APK name, the expected tag, `BuildConfig.RELEASE_NAME` / `SOURCE_COMMIT`, export and crash metadata, the updater (`AppVersion.current()`) and the What's New gate (once per release name; content by base version, never another version's notes). `-PrcSuffix` is gone (the build refuses it). CI `publish-release` runs `android/scripts/verify-release.sh` (tag = versionName = filename, versionCode above every earlier tag, release signer). Bump the versionCode for every distributed production build, stable included; debug builds carry `-dev+<commit>[.dirty]` — dirty = tracked changes or non-ignored untracked files under `android/`. `verify-release.sh` fails on an earlier tag whose versionCode it can't read (exception list `LEGACY_TAGS`, empty) or a checkout without tags. It rejects the rc.6 APK (embeds `2.2.7`, the convention then) — expected, not an rc.6 defect. rc.2–rc.6 all shipped as code 35 with versionName `2.2.7`. rc.6 was the first RC published by this job (the rc.3–rc.5 tag runs failed on the pre-#196 lint crash and were attached by hand). CI's keystore secrets sign with the same certificate as the published APKs (`c99b6f26…`)
- **Release API keys (#235, integration candidate)** — `apiKey(name)` in `app/build.gradle.kts`: `local.properties`, else the
  environment; optional for every build. `publish-release` maps the repository secrets `MAPS_API_KEY` /
  `OPENWEATHER_API_KEY` (not configured yet — owner step) and runs `scripts/check-release-config.sh` (Maps required, weather
  a notice); `verify-release.sh` step 5 (`check-apk-maps-key.sh`) fails an APK with an absent / empty / placeholder Maps
  key, so it now rejects keyless local builds and the published rc.6 asset. Scripts print lengths only — never a key value.
  The rc.6 asset has no Maps key (blank TRIP map) and no weather key
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
- **Drive thermal judgements come from the drive's own record** — `thermalPeaksJson` v2 (`DriveThermalRecord`) saves
  per-sensor peaks with the warn / crit of the profile in effect, one segment per profile (a mid-drive switch or saved
  Custom edit starts a new one). TRIP's TEMP badge and the summary read it through `DriveThermalReview` — never
  `prefs.*WarnC/CritC` for a recorded drive (the profile export's `thermalRecord` block reads it the same way). Start →
  peak → end (summary rows + export curves) go through `DriveThermalReview.progression()`: record first / last fresh
  readings, else stored start / stop readings; an endpoint missing or above the peak is null ("—" on the summary, no curve
  in the export) — never adjust the peak to fit. Older JSON arrays are LEGACY (original fixed-limit rule, no colours, no
  profile claimed); never rewrite them. TEMP = reading ≥ `thermalFlagThresholdC(crit)` (95 %, °C); explanations show that
  threshold converted ("· flag threshold 220.1°F"), never "95 % of" a °F value. The recorder feeds only `tempAvail`-fresh
  readings (`DriveThermalTracker.sample(vs, prefs, now)`); a peak is coloured by its own segment's limits
- **`UserPrefsStore` outside the activity** — `CanDataService` is START_STICKY: Android can recreate it (and it auto-connects /
  auto-records) with no MainActivity, so its `onCreate` calls `UserPrefsStore.ensureLoaded()` first. Anything service-side
  that reads `UserPrefsStore.prefs` relies on that
- **FileProvider authority = `${applicationId}.provider`** (release `com.openrs.dash.provider`, debug
  `com.openrs.dash.debug.provider`); code derives it from `ctx.packageName`. Never hard-code it — a fixed authority made
  any second build variant fail to install (`INSTALL_FAILED_CONFLICTING_PROVIDER`). Provider stays `exported=false`
- **Share intents set ClipData** (`DiagnosticExporter.shareClip`) beside `EXTRA_STREAM` — `createChooser` only passes the
  read grant to the share sheet through ClipData; without it the sheet's preview logs `Permission Denial … intentresolver`
- **`lap_sessions` has no FK to `drives`** — after any drive deletion call `deleteOrphanLapSessions()` (laps cascade from
  lap_sessions). Sessions older than 30 days are pruned at each connection start (by design)
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
- **E-brake / launch control never insert space** — shown as header chips in the wordmark's fixed slot (stacked
  full words when both are on — never abbreviate LC ENGAGED; DAY engaged fill = `actionInk(accent)`) and inside DRIVE's
  fixed-height gear module (tag + caption + border); AnomalyStrip now carries only update progress. Live flags only
  (`vs.isConnected`). LC states supported: on (0x420 bit) and engaged (0x225); `lcArmed` (RSProt probe) is GARAGE-only
- **Opening presentation** — system splash = background colour only (`splash_blank`). The composition draws alone at
  full opacity (no fade-in); the splash exit listener completes `OpeningState.splashGone`, and only then does the
  dashboard compose underneath (`composeDashboard`) and `CanDataService` bind — composing / binding earlier blocked the
  splash hand-off by up to 1.5 s. Hold 2 s from the first visible frame, 300 ms fade; once per **app task** (`OpeningState.shouldShow`, #213): cold launch, Force stop, or a new task after Recents dismissal (even if the process survived) — never Home / resume, rotation, tab changes or service rebinds. Back is the system's: task moved to the back → no replay; task removed (seen on the Android 16 emulator) → next launch is a new task → replay. `splashGone` is a fresh deferred per opening.
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
  comparing card edges across `at:2.5 / 9.5 / 23` frames
- **ESC confirmation (#125)** — the adapter's OK is acceptance only. `EscCommand` waits the firmware's own press plan
  (5 s long press for OFF, 300 ms presses, 1 s gap) + 3 s (unmeasured), needs a 0x1C0 frame stamped (`escStatus`) after the
  request on the same `CanDataService.link.generation`; reconnect → ConnectionLost; one request at a time. Firmware
  (`focusrs.c` / `focusrs_esc_plan.c`) saves boot ESC only for a CAN-confirmed change; only a 0x1C0 frame decoding to On /
  Off / Sport counts (raw 3 no longer advances `esc_frames`). `firmware/tests/run.sh` runs the real `focusrs.c` against
  host stubs (`tests/stubs/`). PRO built once in isolation 2026-10-29 (evidence dir), **never flashed** — adapters keep
  their old firmware. Firmware detection ≠ control capability
- **Drive-mode confirmation (fix branch, `4937cfb`)** — the adapter's OK is "ACCEPTED"; `executeDriveModeChange` confirms only
  from a resolved 0x1B0 report stamped `driveMode` AFTER the request on the same link generation (cached values never count;
  reconnect → `DriveCommandResult.ConnectionLost`, no correction). Timing (2 s + 15 s) and the one correction unchanged.
  Demo must stamp every CAN tag (`CanDecoder.canStampTags` ⊆ `DemoSimulator.CAN_TAGS`, DemoSimulatorTest) or confirmations
  time out in demo. Requesting the mode the car is already in confirms from the next re-broadcast
- **Diagnostic-export adapter status (#233, fix branch)** — `/check_status` belongs to the session: `sessionStart` returns a
  token + takes the initial reason (Bluetooth = not requested); `adapterStatusRequested/Fetched/Failed(token, …)` drop stale
  tokens; exports build text + JSON from ONE `AdapterStatusSnapshot` (`buildSummaryAndDetail`). The `Firmware :` line is
  the OPENRS? handshake — never derived from `/check_status` (`fw_version` there doesn't identify an openRS_ build: the
  owner's openRS_ PRO v1.2-rc.1 unit reported 4.45 over Wi-Fi)
- **Text from outside the app → `LogText.sanitize[Reason]`** (probe write failures #220, DTC write failures #215): printable,
  bounded, MAC / `WiC_…` / long hex masked. Probe events use `SlcanTransport.kind`, never `transport.label` (BLE label has
  name + MAC); non-frame adapter lines are counted, never logged. Probe 1 is logged when written / failed, before the
  handshake (a failed attempt still leaves it); a handshake timeout logs "No probe verdict". Replies stay "after probe N"
  (identical probes — no request id)
- **DTC scan writes (#215, fix branch)** — every scan write goes through `send(module, DtcStage, frame)`: failure kept as
  `DtcSendFailure` (stage + reason; one per module — request > flow control > extended session, `DtcRawScan.keeps`) →
  `moduleNotes` in export / DTC files / DIAG; `DtcRawScan.statusWithoutReply` is the one rule for progress AND result
  (request / FC failure → ERROR **before** PENDING; no session-write note beside PENDING); FC marked sent only on success;
  diverted replies are logged once before the scanner gets them; requests / FC are `# TX` comment lines
  (`DiagnosticLogger.logTxLine`)
- **DTC retries carry their own note (#215, fix branch)** — `scanSingleModule` / `retryScanModule` return `DtcModuleRetry`
  (codes, status, note); the ledger record and DIAG's result apply it with ONE rule (`DtcScanLedger.withNote`: replaced on
  failure, cleared on OK / genuine timeout). DIAG's NOT SCANNED row reads the DISPLAYED result
  (`DtcScanView.notScannedText`) — never `DiagnosticLogger.lastDtcScan` (a newer auto-scan / session would label an older row)
- **Every release build needs an on-device launch (R8)** — on 2026-10-29 R8 (AGP 8.7.3) emitted an unverifiable
  `DrivePageKt$DrivePage$7` after a harmless edit (removing the CLUTCH row): ART threw `VerifyError … register has type
  Conflict` at the first DRIVE frame; debug and every JVM test were fine. Worked around by moving ECONOMY into
  `DriveEconomy` (625c43b). Keep very large composable lambdas split; smoke the minified APK (launch + every tab) and grep
  logcat for `Verifier rejected` before calling a release build good — clear the buffer *before* the cold start, or the
  grep doesn't cover it (the rendered DRIVE page is the real evidence). Tracked in #227
- **Header is a priority layout (#224)** — `HeaderRow` measures wordmark, MODE/ESC pills, link pill and cog first; REC gets
  the rest and shows the longest of "REC time · distance" / "REC time" / "REC" / dot that fits (never cut mid-value).
  Don't put header chips back in a plain Row
- **Demo simulates only fields a real decoder writes** — `manifoldChargeTempC` and the IPC `warn*` lamps have no decoder,
  so they stay at their sentinels (DemoSimulatorTest guards this). Add a field to the simulator only with its decoder
- **Side system bars (#231, fix branch)** — the root Box pads `WindowInsets.safeDrawing.only(Horizontal)`; never a fixed
  margin (portrait gesture phones get 0, verified pixel-identical). Settings / BLE picker are separate dialog windows with
  the default `decorFitsSystemWindows` — Android insets them (emulator: both rotations + split screen, portrait identical
  to the frozen APK). Rotating portrait ↔ landscape closes Settings (state not saved, unchanged). Whether a head unit's
  sidebar is a reported system bar is unknown
- **BLE picker (#218, fix branch)** — never cache `bluetoothLeScanner` (null while the PHONE radio is off); `BleScanBackend`
  seam, `BleScanState.PhoneBluetoothOff` → enable prompt (`ACTION_REQUEST_ENABLE`), retry on accept / `STATE_ON`; the
  emulator AVD has a virtual Bluetooth adapter (`adb shell cmd bluetooth_manager enable|disable`) for picker checks.
  Every scan has an id (`activeScan`): stop / restart / Bluetooth-off invalidate it and callbacks of an old scan are dropped;
  the enable result only starts a scan while still `PhoneBluetoothOff` (broadcast + result in either order = one scan). The
  picker Column scrolls — in landscape its content is taller than the window (CANCEL was unreachable)
- **Bluetooth with no saved device (#234, fixed on feature/rc7 — verified on the emulator only; trigger: connection method
  Bluetooth saved with no device selected — on such installs `a605b1c` and earlier crash at every launch; Wi-Fi, or
  Bluetooth with a device, is unaffected)** — one rule,
  `ConnectionSetup.problem(method, address)`: Settings SAVE checks the draft (`BleDeviceDraft.saveProblem`, FORGET counts);
  `beginConnection` checks the saved prefs BEFORE `foreground.promote()` / `sessionStart` / `startSessionRecording` (no
  notification, session or retry — catching the exception would not have been enough); DRIVE reads SELECT A BLUETOOTH
  DEVICE and Connect opens Settings on CONNECTION; prefs never switched. `connectHybrid` guards `transportFactory()`
  (`UnbuiltTransport` → ordinary failed attempt; cancellation rethrown). Release logcat `Session N started` (autoGenerate
  ids) is how to count sessions on a non-debuggable build
- **Fresh Play-services AVDs kill the app mid-test** — the Maps SDK's Dynamite loader SIGKILLs its own process when GMS is
  replaced during first-boot updates; `pm disable-user com.android.vending` on the throwaway AVD before smoke tests

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
./gradlew assembleRelease                  # → openRS_v{releaseName}.apk (e.g. openRS_v2.2.7-rc.7.apk; set in app/build.gradle.kts)
./gradlew test                             # 1025 unit tests across 79 files, each variant (source 81baa76 = feature/rc7 code through 12b6e4f; integration 86e4af8: 1030 / 80; tested integration tip 7bb8a9a: 1034 / 81)
./gradlew lint                             # report: app/build/reports/lint-results-debug.txt (0 errors, 45 warnings rc.6)
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
# APK: app/build/outputs/apk/debug/openRS_v2.2.7-rc.7-staging-debug.apk   App ID: com.openrs.dash.debug

# Control a running debug app (onNewIntent needs --activity-single-top):
adb shell am start --activity-single-top -n com.openrs.dash.debug/com.openrs.dash.ui.MainActivity --es demo warning
#   demo = idle | driving | warning | loss | cooldown | launch | pause | reset | off
#   demo = at:<seconds> — replay the current scenario and pause at that time (repeatable screenshot frames;
#          DRIVING at:9.5 = mid-pull 3rd gear, at:2.5 = idle on the brake; WARNING at:50 = all alerts)
#   demo = collapse | expand — fold the control strip to a 2 dp amber line (header DEMO pill toggles it too)
#   trippreview = history | selected | recording | paused | summary | compare | replay | export | off — TRIP with in-memory sample drives
#          summary:N — sample drive N's summary (thermal records: 1 Custom · 2 STREET → RACE · 3 TRACK · 4 legacy · 5 none ·
#          6 early record: START missing, END above the peak → "—")
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
| LAUNCH | 40 s: e-brake 0–25 s, LC on (0x420) 5–33 s, link lost 10–15 s / recovering to 17 s, LC engaged (0x225, holding 4,500 rpm) 23–27 s, launch, cruise |
| COOL | Readiness walk-through (~215 s, real time): oil cools past warn (0–34 s, recovery hold) → READY → RDU forecast (~45 s) → RDU warn (~64 s), coolant invalid 85–100 s → RDU replies stop at 70 s, stale + warning dropped at 160 s → fresh poll 170 s → oil below 80 °C ≈ 205 s (ENGINE WARMING) → clutches past fixed warn ≈ 236 s / crit ≈ 248 s, cooling from 255 s |

Drive-mode dock / ESC buttons / DTC clear act on simulated state only (mode lands ~1.5 s after the command).

**Testing gotchas:**
- Android 17 relaunches an updated app's task from SystemUI ~1 s after `adb install` (no extras, CLEAR_TASK) — a
  launch sent immediately loses its extras. `install-debug.sh` waits 3 s then uses `am start -S`
- Mobile MCP's `uiautomator` dump fails whenever the app is open (infinite header animations — even with the demo
  paused) and each failed call blocks for minutes, delaying the next command. Tap by coordinates instead. Its
  screenshots can lag; `adb exec-out screencap -p` is always current. Check crops at full resolution — a 700 px
  downscale hid clipped digits once
- Debug builds report `2.2.7-rc.7-dev+<commit>[.dirty]` (Settings: `… · debug <commit>*`), so the source revision is
  visible; still hash `adb exec-out cat <pm path>` against the APK to prove which build is installed
- Settings categories animate in for ~2.5 s after the sheet opens — tapping a row sooner can land on the wrong one
- `--es demo at:N` replays the scenario **in real time** from 0 and pauses at N — wait N s before capturing
  (WARNING at:55 = oil + PTU past the STREET crit, coolant + RDU at WARN)
- Settings ignores taps in the margin around it — behaviour, not a bug: the dialog window spans the screen
  (`usePlatformDefaultWidth = false`), so those taps land inside it; ✕ / Back / CANCEL close and revert previews
- The BLE device picker is unverified on the emulator (no BLE, Bluetooth permissions not granted) — check on a phone
- Isolated DB / release checks: build throwaway variants with a different `applicationIdSuffix` (the FileProvider
  authority follows the id) and run them on a second AVD started with `-read-only` (nothing persists). The v11 → v12
  upgrade was verified that way on 2026-10-26 (real v11-created DB → current build; data, FKs, TIP rescale, hash)
- Real share-sheet exports (Sapphire fixtures in `web/src/lib/__fixtures__/android-exports/`): on the read-only AVD,
  install the debug APK (hash-check it) + the receiver app, seed the drive DB with
  `adb exec-in run-as com.openrs.dash.debug sh -c 'cat > databases/<f>' < <f>` (app stopped; a glob `cp` from
  /data/local/tmp fails under run-as), share from TRIP (card → SHARE, or long-press → multi-select → EXPORT), pick
  ShareTest; it logs `ShareTest: … RESULT=PASS` and keeps the ZIP in its `files/`. Seed DB (d1 GPS 5 pts, d2 no-GPS 0 pts,
  d3 no-GPS 3 pts + knock) and receiver source / APK: `~/Documents/openRS_-evidence/export-tooling-2026-10-26/`
- Sapphire tests touching `localStorage` (store / App): Node 25's own `localStorage` global (non-functional without
  `--localstorage-file`) shadows jsdom's — stub one per test (`vi.stubGlobal('localStorage', …)`, see `App.test.tsx`)
- Sapphire browser smoke (prod build, chart gaps / paints / remembered view): `node smoke.mjs <dist> <profile> <shots>
  <fixtures>` from `~/Documents/openRS_-evidence/sapphire-smoke-2026-10-27/` with Playwright (same setup as
  `web/scripts/idb-check/check.mjs`); build with `--outDir` outside the repo; IndexedDB is per origin, so each run
  (random port) starts empty
- Sapphire import contract: `web/src/lib/import.androidExport.test.tsx` imports complete Android exports from
  `__fixtures__/android-export-rc6/`; regenerate with `OPENRS_EXPORT_SAMPLES=<dir> ./gradlew test --tests '*DriveExportThermalTest'`
- Lifecycle tests without a car (rc.7): `~/Documents/openRS_-evidence/rc7-lifecycle-2026-10-28/fake_slcan.py` is a fake
  SLCAN TCP adapter on the host; on the disposable AVD set adapter MEATPI_PRO, host `10.0.2.2`, port 35000 (edit
  `shared_prefs/openrs_settings.xml` via `exec-out run-as … cat` / `exec-in run-as … sh -c 'cat > …'`). Kill / restart
  the script to drop / restore the link. Recents dismissal on the Android 16 launcher needs `input motionevent
  DOWN/MOVE…/UP` — `input swipe` is ignored. `probe.sh` there snapshots pid / service / notification / task
- The HUD overlay needs `adb shell appops set com.openrs.dash.debug SYSTEM_ALERT_WINDOW allow` on a fresh emulator
  (restore with `… default`); start it from ⚙ → DISPLAY → FLOATING HUD. A reinstall stops it

Keys: `android/local.properties` (`MAPS_API_KEY`, `OPENWEATHER_API_KEY`) or the same names in the environment — optional for
local builds (#235); `android/keystore.properties` for release signing.
Version display format: `openRS_ v2.2.7-rc.7 · Build 36` (Settings) — the release name comes from `app/build.gradle.kts`
(see the Release identity gotcha); there is no `-PrcSuffix` any more.

## Tests

```
android/app/src/test/java/com/openrs/dash/
  CanDecoderTest.kt           — 90+ tests: all passive-CAN decoders, 0x040/0x300 identity, 0x435 crosstalk guard, 0x1A4 unsigned + 55°C guard, CAN freshness stamps
  ObdResponseParserTest.kt    — 68 tests: PCM/BCM/AWD/PSCM/FENG/RSProt DIDs (rc.6: TIP /128, F404/F40E/F415/F423/0548,
                                decoder-coverage guards: every polled PCM/BCM/AWD DID must decode + stamp)
  DriveStateTest.kt           — 39 tests: fuel economy, averages, haversine, peaks, sentinels
  VehicleStateTest.kt         — 32 tests: conversions, peaks, TPMS, RTR (3 AWD split tests removed with the % — #226)
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
  DriveExportThermalTest.kt   — 7 tests: profile export `thermalRecord` + coherent curves per basis (built-in, Custom, mixed
                                peak vs flag, legacy driveEndC, absent vs unreadable, endpoints above peak → no curve);
                                `OPENRS_EXPORT_EXCERPTS=<dir>` writes review excerpts
  DriveExportBuilderTest.kt   — 64 tests: CSV/GPX/JSON builders, schema v2, fuelTrim export, knock pulls, boost sweep by gear,
                                track files (no-GPS CSV without coordinates, routes only with GPS, empty drive → none)
  DemoSimulatorTest.kt        — 8 tests (incl. LAUNCH states): determinism, LOSS freeze / recovery sentinels, freshness stamps, no-decoder sentinels,
                                WARNING thresholds, DRIVING plausibility + continuity
  DriveThermalRecordTest.kt   — 17 tests (incl. START → PEAK → END endpoint rule): saved profile limits (built-ins + Custom), 95 % boundary + levels, °C / °F + displayed
                                flag threshold, later edits, mid-drive segments, stale reading across a profile change, peak vs
                                flag reading, service-only prefs load, legacy array records, round trip
  ThermalProfileTest.kt       — 7 tests: built-ins unchanged, Custom selection, JSON round trip, no unit drift, validation,
                                custom threshold moves verdict + gauge together, no warm-up hold across a changed minimum
  ThermalReadinessTest.kt     — 32 tests: warm-up target + hold, delayed evaluation, drop below minimum, warn / crit recovery holds,
                                forecast + clearing hold, warning with missing data, stale-warning removal, preset change, offline /
                                reset, missed AWD polls (90 s, incl. trans oil), stamps never refreshed, shared freshness, units, clutch
                                limits / recovery / stale, checklist incomplete rows, VEHICLE advisory, banner wording, COOLDOWN sequence
  ReplaySeekTest.kt           — 2 tests: replay ±10s seeks by timestamp (1 Hz and sparse data), clamps, steps across gaps
  FuelEconomyConcurrencyTest.kt — 2 tests: concurrent onUpdate / reset never throw (demo-switch crash), reset is clean
  PendingSummaryTest.kt       — 2 tests: post-drive summary stays queued until consumed, newer drive survives an older close
  TyrePressureLevelTest.kt    — 6 tests: THERMAL tyre state + DRIVE tyre alerts follow the saved Low / Warn / High
                                (boundaries, custom, PSI = BAR, over-inflation alert, missing readings)
  LinkOwnershipTest.kt        — 10 tests (rc.7): callback vs cancel, stop vs publication, late merge / phase after reconnect,
                                cancel policy (stale after CONNECTED, recording → RCN → CONN, recording began, racing CONNECTED)
  SlcanConnectionLifecycleTest.kt — 8 tests (rc.7): Connecting vs Reconnecting, exhaustion, recording keeps retrying, recording
                                end mid-backoff → released ≤ 1 s, healthy link kept, budget left → bounded retries, abort
  LinkPresentationTest.kt     — 5 tests (rc.7): phases, notification text incl. recording / paused, Cancel rule, UI labels, demo
  OpeningStateTest.kt / DtcScanRecordTest.kt / VersionIdentityTest.kt / ExportLocaleTest.kt — 4 / 6 / 9 / 7 tests (rc.7: splash
                                per task, DTC record + report lines, release identity, en / pl / de exports)
  TransportAbortTest.kt       — 3 tests (rc.7): real TcpSlcanTransport closed before open / during connect / during read
  AutoRecordPolicyTest.kt     — 4 tests (rc.7): keep / resume / finalize + start / start
  DtcScanPathTest.kt          — 8 tests (rc.7): live SlcanConnection + scripted adapter — zero codes, codes (SF + multi-frame),
                                NRC, malformed, pending → final, pending only, timeout
  DtcScanLedgerTest.kt        — 10 tests (rc.7): session A → B without scan, retry finds codes / completes coverage / refused,
                                stale retry, interrupted keeps coverage, abort vs late interrupt, RUNNING export
  ForegroundControllerTest.kt — 6 tests: idle bound service never foreground, self-start always followed by startForeground,
                                demote leaves both, refused start / startForeground leave nothing started
  Drive-review pass (rc.7): LinkSessionRaceTest 2 · NrcAttributionTest 4 (+ NrcTrackerTest 16) · StalePolledValueTest 5 ·
  ObdDecodeMergeTest 2 · BoostSourceTest 3 · DtcScanPathTest 12 · DtcScanViewTest 5 · FirmwareProbeTest 4 · BleAdapterProfileTest 9 ·
  TyreThresholdTest 6 · EscCommandTest 4 · RecordingGapMonitorTest 3 · LinkOwnershipTest 14
  Acceptance corrections (rc.7): NrcAttributionTest 9 · NrcTrackerTest (ledger ownership) · DtcScanViewTest 18 · BoostSourceTest 15 ·
  EscCommandTest 8 · FirmwareProbeTest 6 · BleTransportTest 10 (fake GATT) · firmware/tests/run.sh (host C, 11 checks)
  Corrections 2026-10-29: BoostExportConsumerTest 8 (real export builders + map colour) · BoostProvenanceTest 5 (StoredBoost,
  trend, compare, legend, knock scatter) · NrcTrackerTest +4 / NrcAttributionTest +1 (lapsed hold, production poller) ·
  BleTransportTest 12 (late callback, production path through SlcanConnection) · CanDecoderTest AWD from recorded 0x2C0
  frames · PerformanceTimerTest per-source peaks · firmware/tests/test_esc_confirm.c (real focusrs.c on host stubs, 8)
  Sapphire: import.boostProvenance.test.ts 8 · import.androidExportRc7.test.ts 3 (the final release APK's own export,
  `__fixtures__/android-export-rc7/`)
  Follow-up 2026-10-29: AwdReadoutTest 5 (REQ L/R own freshness, withdrawn cell, JSON) · NrcTrackerTest ledger (no time
  release, repeated 0x78, eviction) · BoostSourceTest gauge · TimeSeriesMathTest +2 (gaps survive downsampling) ·
  BoostProvenanceTest knock axis · BoostExportConsumerTest track files without pressure · Sapphire pressureDisplay 5 +
  import.androidExportRc7Gaps 6 (release export with TIP → CAN 0 → none → TIP, `release-pressure-gaps-drive.zip`)
  REQ-zero 2026-10-30: AwdReqTorqueMergeTest 6 (real 0x70B frames → parser → ObdMerge → awdDetailRows: positive → 0 per
  side, unrelated / older-snapshot reply, never received, stale → recovery with 0)
  Fix branch 2026-10-31: AdapterStatusExportTest 5 · AdapterStatusSnapshotTest 2 · FirmwareProbeLogTest 6 · DriveModeConfirmTest 5 ·
  DtcScanExplainTest 7 · TpmsFooterTest 4 · TyreImbalanceTest 1 · NrcLogFloodTest 3 · BlePickerScanTest 6 (fake backend) ·
  DemoSimulatorTest +1 (demo stamps every CAN tag)
  Source review 2026-10-31: BlePickerScanTest +5 (old-scan callbacks, prompt / broadcast order) · DtcScanExplainTest +5 (FC
  failure after pending, live retry) · DtcRetryExplainTest 5 (retry notes screen = export = JSON = text, displayed-scan rows)
  · FirmwareProbeLogTest +2 (probe 1 write failure, handshake silence)
  #234 2026-10-31: ConnectionSetupTest 4 (SAVE draft rule, already-invalid prefs) · SlcanConnectionLifecycleTest +3 (factory
  throws / throws once / cancelled)
  Integration 2026-11-01: AwdDemandPumpMergeTest 5 (#226 decoded 0) · DriveHistoryTest 4 (#228 edits only write, fake DAO) ·
  scripts/test-release-config.sh 8 (#235, shell — not in the Gradle count)
  #236 / #237 2026-11-01 (real Room via Robolectric): DriveFinalizeTest 6 (48 named checks) · DriveDbForeignKeyTest 4 ·
  LapFeedTest 1
  (plus LapTimer, PerformanceTimer, FuelEconomy, CrashTelemetryBuffer, NrcTracker, ThermalPredictor, DiagnosticExporter)
```

Current count: **1025 tests across 79 files** (Android, `./gradlew test` at source `81baa76` — debug and release variants each;
`feature/rc7` code is unchanged through `12b6e4f`, later commits docs-only; unmerged work branches carry their own totals; integration candidate `86e4af8`: **1030 / 80** per
variant; tested integration tip `7bb8a9a`: **1034 / 81** per variant) + **282 vitest across 25 files** (Sapphire — run `web/node_modules/.bin/vitest run` and `tsc -b`).

## Branch / Release Workflow

```
feature/* → staging → main          (code, tests, android/CHANGELOG.md)
docs/*    → main    (direct)        (README.md, assets/, docs/, firmware/README.md)
Release tags: android-v*  (triggers CI signing + GitHub Release)
RCs: one pre-release per RC tag (android-v2.2.7-rc.4, -rc.5, -rc.6 …), tagged on staging; stable tags on main
```

Android RCs ship from **staging**; **main** carries stable Android (v2.2.6) and Sapphire. Sapphire may reach main web/-only
(as for rc.6, PR #203) without the Android RC.

**RC publication (as done for rc.6):** merge the feature PR into staging → wait for CI on the staging merge commit →
`gh release create android-v<ver>-rc.N --target <full staging SHA> --prerelease --title "openRS_ v<ver>-rc.N"
--notes-file <notes>` (creates the lightweight tag; the tag push runs `publish-release`, which attaches the signed APK
to that release) → download the asset and check name, `apksigner verify --print-certs` (cert `c99b6f26…`), versionCode /
RC label, SHA-256, `gh attestation verify`. Never force-move a tag or delete an older RC release.

**rc.7 work record (2026-10-28, local — nothing pushed / merged / tagged / deployed):**
- Issues: milestone **v2.2.7-rc.7** = #211, #212, #213, #214, #215 (status comments posted, none closed — close via merged
  PRs). Web #204 (no milestone). Investigations / proposals, no milestone and no formula changes until evidence supports
  one: #205 spark 0x116B, #206 ETC scale, #207 0x0F8 boost reference, #208 AWD NRCs, #209 TX logging, #210 auto-connect
  eligibility.
- `feature/rc7`: `5e6639c` (= `6906e52`) · `710b8ed` #211 · `894c8e8` + `8f9ef32` #204 · `f46100e` #212 · `2e940ee` #213 ·
  `e18df43` #215 · `bbc26b9` #214 · `39aab99` What's New · docs `2b91a2b` `fc65455` `7eacd9f`. `fix/sapphire-row-validation`
  `c012086` (web/ identical to feature/rc7).
- Checks: Android 779 / 47 tests, lint 0 / 45, debug + release builds; Sapphire 260 / 21, ESLint 0, tsc, idb-check 9/9.
  Local release APK `openRS_v2.2.7-rc.7.apk` vc 36, source `39aab99` clean, SHA-256 `e1706bb4…`, cert `c99b6f26…`;
  `android/scripts/verify-release.sh` passes it, rejects the rc.6 APK (embedded `2.2.7`) and a debug-signed copy.
- Emulator lifecycle / splash results: `~/Documents/openRS_-evidence/rc7-lifecycle-2026-10-28/RESULTS.md` (Android 16: Back
  removed the task → next launch new task → opening). Corrected diag report: `~/Documents/openRS_-evidence/diag-rc5-20261020/`.
- Correction pass (same day, #212 / #214 / #215): `6e35fec` transport abort · `a23547a` ownership · `8163a6c` Cancel policy ·
  `e82e6d7` recording retry + notification · `aa5b36b` DTC reply validation · `20664c7` DTC ledger · `ba79a00` provenance ·
  `b058673` What's New (SPARK) · `b09050a` auto-record split · `7a98c8d` notification body · `5fca692` DIAG footer; docs on
  top. Android 817 / 52 tests, lint 0 / 45. Release APK at `5fca692` (clean): SHA-256 `f7e3514f…1029c`, vc 36, cert
  `c99b6f26…`, verify-release passes. Evidence + revised behaviour table:
  `~/Documents/openRS_-evidence/rc7-corrections-2026-10-28/RESULTS.md` (release exports with readable states and DTC
  outcomes, cancel latency per transport, recording protection, notification body). That run showed the earlier
  "Wi-Fi off / on after cancel" row proves nothing (Wi-Fi cycling doesn't reconnect on that emulator either way).
- Drive-review pass (same day, from the user's two 2026-10-28 drive exports + review): issues #216–#224 created, #125 and
  #205–#208 updated, milestone re-prioritised (decoders now P1). Commits `e6c1d09`…`24c1d41` (+ docs) — #212 ownership
  window + persistent Cancel, #216 NRC, #217 stale, #205 spark, #206 throttle, #207 boost, #215 layout / interleaving /
  retries / resolution, #220 probe, #218 BLE, #219 TPMS units, #125 ESC confirmation, #221 gap diagnostics. The issue
  tracker is authoritative; acceptance matrix, failing-before / passing-after log, emulator evidence, final build identity
  and the parked hardware checklist: `~/Documents/openRS_-evidence/rc7-drive-review-2026-10-28/` (`ACCEPTANCE-MATRIX.md`,
  `HARDWARE-CHECKLIST.md`; private drive exports under `private/`, never published).
- Final local release APK (drive-review pass): source `24c1d41` clean, SHA-256 `1e297ccb…cf7b`, vc 36, signer `c99b6f26…`,
  verify-release OK (later commits docs-only). **Decisions then (superseded by the acceptance-corrections pass below):** boost source — TIP-first (current) vs CAN-first
  with TIP fallback (one getter, `VehicleState.boostGaugeKpa`); NRC re-probe every 60 s logs a suppress pair per minute
  for genuinely rejected DIDs (backoff / transition-only logging?); "previous scan" = latest saved scan; DTC failure-type
  byte parsed but not shown on DIAG / export.
- Acceptance-corrections pass (2026-10-28/29, from the user's acceptance review; nothing pushed): `b2a3c23` #216 NRC
  ownership · `405c9a1` #215 publication gate / failure type / coverage + Room v13 · `88bc6c4` `6cfef1e` `4087b5e` #207
  boost rule (rc7-boost-v2) · `20104b7` #125 ESC (Android) · `2f86462` #125 firmware source (not built / flashed) ·
  `aaedd7a` #220 quiet-adapter probe · `1ace464` #218 BLE core · `8b971d2` `85fe34d` #224 header · `3790846` DTC export
  test · `e0616b5` DTC rescan rows (found in release smoke) · `b5bd5d2` diagnostic JSON boost peaks. Final local release APK:
  source `b5bd5d2` clean, 6,524,204 B, SHA-256 `47e75313…c445`, vc 36, signer `c99b6f26…`, verify-release OK; 924 / 64
  tests (debug + release), lint 0 / 45 (minified smoke on the `e0616b5` release, not repeated for the JSON-only change).
  Matrix, regression log, emulator evidence, revised hardware checklist (app / adapter / vehicle-protocol):
  `~/Documents/openRS_-evidence/rc7-acceptance-corrections-2026-10-28/` (`ACCEPTANCE.md`, `REGRESSION-LOG.md`,
  `HARDWARE-CHECKLIST.md`, `fake_ecu_v2.py` with `--stale DID:A:S` / `--wot`). Issues updated; #215 retitled → P1.
  **Decisions then (superseded 2026-10-29 — the user rejected the inference):** boost — CAN trusted until contradicted at ≥ 80 % throttle, session-scoped (alternatives:
  trust nothing until proven / persist per adapter); evidence thresholds, ESC REACTION_MS 3 s and NRC 300 ms window
  unmeasured; #224 trade-off (411 dp full chips → "REC" only). Superseded: the drive-review "TIP-first vs CAN-first" and
  failure-type decisions (both resolved in this pass). Final report delivered 2026-10-29; the APK was **not** offered for
  hardware acceptance (user reviews first). Next steps each need the user's go-ahead: answers to the decisions above,
  hardware checklist, firmware build / flash, PRs, rc.7 tag.
- Corrections pass (2026-10-29, from the user's review + video + evidence ZIP; nothing pushed / flashed): `da7e298` #225
  CLUTCH row removed · `2182b6b` #216 lapsed NRC hold · `f67f7ac` #218 BLE write timeout closes the link · `f80f061` #125
  firmware raw-3 + host integration test · `e11d8c4` #207 inference removed (v3) · `38f5bbb` #207 Android consumers
  (StoredBoost) · `52fe6c2` #207 Sapphire provenance · `5854f08` #226 AWD 0x2C0 DBC fields · `625c43b` R8 VerifyError
  workaround (found in the release smoke) · `4941825` Sapphire release-export fixture. New issues #225, #226; #207 #216
  #218 #125 #224 commented. Final local release: source `625c43b` clean, 6,540,588 B, SHA-256 `1d155b35…97b9`, vc 36,
  signer `c99b6f26…`, verify-release OK. 945 / 66 tests (debug + release), lint 0 / 45; Sapphire 271 / 23, ESLint 0,
  tsc, build, idb-check pass; firmware run.sh pass; PRO firmware built once in isolation (not flashed, repo release/
  untouched). Evidence + matrix + regression log + revised checklist (routine vs investigation):
  `~/Documents/openRS_-evidence/rc7-corrections-2026-10-29/` (video frames under `private/`). Throwaway AVD
  `Rc7_Disposable` (the Medium_Phone AVD was running non-read-only on 5554 from Android Studio — never touched).
  **Decisions awaiting the user:** the AWD front/rear split (rear Nm ÷ engine torque is not a physical split — withdraw
  or define), the TIP-first order for the DRIVE gauge (label alternates when TIP freshness drops), unmeasured P2* hold /
  3 s ESC window. APK not offered for hardware acceptance. Follow-ups seen, not changed: TRIP history lists a just-stopped
  drive as ACTIVE until reload (list read before `stopDrive()` finishes); `fix/sapphire-row-validation` is behind the web/
  changes here — port before any Sapphire PR. Final report delivered 2026-10-29 (APK still not offered for hardware
  acceptance); next steps each need the user's go-ahead: the three decisions above, hardware checklist, firmware flash,
  PRs, rc.7 tag.
- Follow-up pass (2026-10-29, from the user's "focused follow-up"; nothing pushed / merged / released / flashed): `86cd683`
  #216 no time release (unresolved → unattributed) · `95e740e` #226 front/rear % withdrawn, REQ L/R freshness, MAX not
  exported · `3aac7ab` #207 TIP-only live gauge · `bb3d24f` #207 chart gaps / knock axis / track files · `618d7a5` #207
  Sapphire units / sparkline / source names · `0c7431a` What's New · `fd1ade3` Sapphire fixture from the final release's
  export (TIP → CAN 0 → none → TIP). New issues #227 (R8 VerifyError), #228 (ACTIVE after stop), #229 (0x0F8 oil / PTU
  identity — priority before thermal acceptance), #230 (0x138 PCM gear); #207 #216 #226 #125 #225 #227 commented. Final
  local release: source `0c7431a` clean, 6,540,588 B, SHA-256 `d88456d4…f6c3`, vc 36, signer `c99b6f26…`, verify-release OK,
  installed + smoked (DRIVE renders, crash buffer empty). 955 / 67 tests (debug + release), lint 0 / 45; Sapphire 282 / 25, ESLint 0, tsc, build,
  idb-check. Evidence, matrix, regression log, checklist: `~/Documents/openRS_-evidence/rc7-followup-2026-10-29/`. Throwaway
  AVD `Rc7_Followup` (deleted after). Resolved decisions from the corrections pass: AWD % withdrawn; gauge TIP-only; NRC
  without a time hold. Still unmeasured: ESC 3 s window. APK not offered for hardware acceptance. Close-out `d0af91e`
  (lint 0 / 45 rerun; fixture ZIP also carries the 5854f08 crash record — bundled crash history, Sapphire ignores it).
  Final report delivered 2026-10-30; next steps (hardware checklist, PRs, rc.7 tag, firmware flash) each need the
  user's go-ahead.
- REQ-zero correction + testing candidate (2026-10-30, nothing pushed / released / flashed): `a605b1c` #226 — `ObdMerge` took
  REQ L / R only when > 0, so a decoded, stamped 0 kept the old value under a fresh stamp (and a reply parsed from an older
  snapshot could put an old value back); now taken by the reply's own stamp (`ObdMerge.carried`). `awdDmdPressure` /
  `awdPumpCurrent` keep the `> 0` merge (deferred, on #226). Candidate: release from `a605b1c` clean, 6,540,588 B,
  SHA-256 `0fee0c82…f0e0`, vc 36, signer `c99b6f26…`, verify-release OK; 961 / 68 (debug + release), lint 0 / 45; Sapphire
  282 / 25 (web/ unchanged). Throwaway AVD `Rc7_ReqZero` (deleted): logcat cleared before the cold start → 0 verifier lines,
  DRIVE rendered, THERMAL REQ 412 / 388 → 0 / 388 → 0 / 0 → "—" offline → 0 / 0 on recovery, layout pixel-stable.
  Evidence, status table (software-fixed vs hardware-unverified), private handoff (APK copy, routine checks, signal
  investigations, drive-mode read-only conditions): `~/Documents/openRS_-evidence/rc7-reqzero-2026-10-30/` (`HANDOFF.md`).
  #226 + 11 candidate issues commented. The owner performs the hardware checks.
- Owner hardware tests on frozen `a605b1c` (2026-10-31; private triage `~/Documents/openRS_-evidence/rc7-hw-oct31/TRIAGE.md`):
  owner's WiCAN Pro over **Wi-Fi** — telemetry OK, OPENRS? never answered (also rc.6); over **BLE** (adapter BLE enabled) —
  connected ~9 s, `openRS_ PRO v1.2-rc.1` on probe 1, telemetry PASS, parked drive mode PASS (5 OK, raw CAN
  N→S→D→N→T→N), ESC NOT passed (#125), DTC PARTIAL (4 modules "ERROR" = write failed, reason unrecorded; GFM U1000-00 /
  U3000-49). My first conclusion "4.45 ⇒ not openRS_ firmware" was WRONG (corrected on #220); owner's assessment: Pro likely
  wasn't on openRS_ fw during the Wi-Fi test — Wi-Fi detection on current fw untested, not a regression. Second tester
  (DIY C3, build unconfirmed): splash / Cancel / TPMS BAR OK, phone BLE live, box BLE drops, SYNC sidebar (#231). New
  issues #231 (side insets), #232 (TPMS footer), #233 (stale adapter status in export).
- Approved fixes (2026-10-31, branch `fix/rc7-adapter-status-export` from `1bb0373`; not merged / pushed): `9cd8b50` +
  `b0c1c57` #233 · `4b2b253` #220 probe log · `4937cfb` drive mode accepted vs CAN-confirmed · `80d6dba` #215 · `a0a1eed`
  #232 · `d68f877` tyre imbalance unit (4 psi = 0.28 bar) · `8f93ad8` #216 NRC line folding · `24b70d8` #218 picker ·
  `613d2a0` #231 · `0960144` demo driveMode stamp. 1001 / 77 debug + release, lint 0 / 45. Final review APK `0960144`
  SHA-256 `0314960b…74fbd`, vc 36 (same name / vc as frozen — distinguish by SHA), cold-launched every tab, 0 verifier lines.
  Evidence + failing-before / passing-after: `~/Documents/openRS_-evidence/rc7-fixes-2026-10-31/RESULTS.md` (regression XML
  in `rc7-hw-oct31/regression/`, `fake_ecu_v6.py` with TPMS). All 31 issues open. Next (owner's go-ahead): review → merge
  into feature/rc7, new versionCode for any distributed build, PRs, rc.7 tag.
- Source-review corrections (2026-10-31, same branch, from the owner's source review; JVM-reproduced source findings, not
  seen on hardware): `d226274` #218 picker scan id + one scan on enable · `a7a20ea` + `2868ab9` #215 FC failure after
  pending stays ERROR · `4821e99` #215 retry notes (`DtcModuleRetry`) + rows from the displayed scan · `21fd915` + `5c7bca7`
  #220 probe 1 logged before the handshake · `23ed9f8` #231 picker scrolls (landscape CANCEL was unreachable — found by the
  emulator dialog check) · `33cbd87` imbalance fix linked to #219. 1018 / 78 debug + release, lint 0 / 45. Final review APK
  `23ed9f8` SHA-256 `3063ef08…aef5`, vc 36 (same name / vc as frozen), cold launch + every tab + DIAG + dialogs in both
  landscape rotations / split / portrait, 0 verifier lines. New issue #234 (Bluetooth saved without a device → crash at
  every launch, pre-existing, reproduced on the frozen APK; fix proposed, not implemented). Evidence:
  `~/Documents/openRS_-evidence/rc7-source-review-2026-10-31/RESULTS.md`.
- #234 fix (2026-10-31, same branch, owner-approved): `4e1d200` SAVE refused without a device · `e5b82a7` start gate before
  promote / session + DRIVE prompt · `81baa76` factory guard. 1025 / 79 debug + release, lint 0 / 45. Review APK
  `openRS_v2.2.7-rc.7_review-81baa76.apk` SHA-256 `efe622c4…7a98`, vc 36. Emulator: frozen APK → record drive + KPH → save
  Bluetooth / no device → crash (+ an empty session per crash) → `install -r` fix → 2 cold launches open, drive + KPH kept,
  no session (next id after recovery = last + 1), BT on → nothing, Wi-Fi recovery, every tab 0 verifier lines. Device
  selection only up to the picker (no BLE peripherals); sticky restart argued. Evidence:
  `~/Documents/openRS_-evidence/rc7-ble-no-device-2026-10-31/RESULTS.md`; #234 commented (left open). Owner accepted the fix
  + the six corrections; `feature/rc7` fast-forwarded locally `1bb0373` → `931447a` (`--ff-only`; both branches and both
  evidence APKs kept). Next: owner hardware testing on APK `81baa76` (`efe622c4…`, no rebuild, vc 36). Unverified on this
  APK: real BLE device selection, a service restart without the app open. Then (owner's go-ahead): versionCode, PRs, tag.
- #229 offline evidence review (2026-11-01, no repo / code change; the CLAUDE.md note was reconciled on
  `docs/rc7-reconcile`): every private export searched (26 ZIPs, 22 SLCAN logs) — no positive `62 60 17` (ICM 0x78C) or
  `62 07 0D` (PCM 0x7E8) reply anywhere, so **no paired independent oil / PTU reference reading exists**. The one
  `62 1E 3F` reply (AWD 0x1E3F, 76 °C, untimed — RDU sump per the PID sheet) is **not a PTU reference**. RS_HS.dbc agrees
  with openRS_ but is not independent. Oil identity and PTU identity are each **inconclusive**; thermal acceptance stays
  unresolved. No decoder swap, threshold change or history rewrite is authorised. Next evidence: one raw log on one clock
  from the #229 contributor — hot-soak (20–40 min) restart, ~3 min idle + ~10 min drive, passive 0x0F8 plus 0x6017 /
  0x070D replies polled every 1–2 s, each reply within 1 s of a 0x0F8 frame. If a swap were ever proven: values keep
  their offsets (they go with the bytes), names change — live DRIVE / THERMAL / HUD / Custom cells, readiness (oil
  required, min 80 °C; PTU optional, min 40 °C), alerts, forecasts, TRIP TEMP map; profiles are per component. Any
  reinterpretation of recorded drives needs known decoder provenance per drive (which mapping wrote it) — thermal-record
  format v2 alone does not identify the mapping. Shown alerts / readiness / RTR timing, drive-level oil start / peak / end
  columns, session snapshots, legacy arrays and shared exports cannot be re-derived. Never rewrite history.
- Integration candidate (2026-11-01, local only — DO NOT PUSH; nothing pushed / merged into feature/rc7 / tagged / rebuilt
  for the owner): branch `integration/rc7-candidate-DO-NOT-PUSH` (worktree `~/Documents/openRS_-worktrees/rc7-integration`,
  `pushRemote` = a non-remote + branch description) from `12b6e4f`: `6605808` merge of `918add4` + `690be8d` (maintenance),
  `121d7c4` merge of `24023ce` (sanitization), `86e4af8` merge of `bccf54c` + `f6600c4` + `cfb0f94` (docs). No conflicts;
  8 files, each identical to its one source; reviewer's independent merge-tree = tree `634d5db4c1` = `86e4af8^{tree}`.
  Feature/rc7 and the main checkout's uncommitted CLAUDE.md edit untouched (that note's facts are in `bccf54c`, minus the
  convergence claim, private readings, a name and the "derived view" overstatement).
  - Verification of `86e4af8` (developer, `~/Documents/openRS_-evidence/rc7-integration-2026-11-01/RESULTS.md`): clean
    `testDebugUnitTest testReleaseUnitTest lint assembleRelease` once — **1030 tests / 80 files per variant**, 0 failures;
    lint 0 errors / 45 warnings. APK `openRS_v2.2.7-rc.7_integration-86e4af8_DO-NOT-PUSH.apk` 6,573,356 B, SHA-256
    `557c5d2b…2b87`, vc 36 (same name / vc as `81baa76` — tell them apart by hash), signer `c99b6f26…`, dex names
    `86e4af867a4a`, verify-release OK (local keys — doesn't verify the CI path, #235). Disposable AVD `Rc7_Integ` (deleted):
    logcat cleared, cold start → DRIVE, fake Wi-Fi adapter, #228 manual STOP → card complete without restart, every tab
    incl. THERMAL AWD, 0 verifier / FATAL / ANR lines. 1025 / 79 stays `81baa76`'s count; 86e4af8's APK is superseded (not distributed).
  - Merged after review (openrs-9d accepted each, `rc7-integration-review-2026-11-01/REVIEW.md`): `d17411a` #235 release
    API keys (CI passes repository secrets as env, publication stops without a valid Maps key, `verify-release.sh` step 5)
    and `4a9f92b` #228 edits only write (`DriveHistory`; TripPage reads the list only from the observed query), via
    `--no-ff` `984a215`; docs-only `7bb8a9a` (CHANGELOG #228 / #235, this file). Reviewer notes, not blocking: TripPage's
    saved-state restore still does a one-off `getDrive` (pre-existing); DriveHistoryTest guards DriveHistory, not
    TripPage's wiring, and doesn't reproduce the race; `check-apk-maps-key.sh` has no automated test of its own.
  - **Final verification — tested source `7bb8a9a7db48e6d44955d15a58f943323c1f0c41`** (developer,
    `~/Documents/openRS_-evidence/rc7-integration-final-2026-11-01/RESULTS.md`; reviewer openrs-9d: consistent with its claims, qualifications correct — no rebuild of its own; 0 leaks of the Maps
    key or identifiers in 517 evidence files; the on-device install hash is stated but has no artifact this time): clean
    `testDebugUnitTest testReleaseUnitTest lint assembleRelease` once — **1034 tests / 81 files per variant**, 0 failures;
    lint 0 / 45; `test-release-config.sh` 8 / 8; verify-release OK incl. step 5 (Maps key from the worktree's
    local.properties — does NOT verify the CI secret path; that was tested in a keyless clone with placeholders,
    `rc7-dev-2026-11-01/RESULTS.md`). APK `openRS_v2.2.7-rc.7_integration-7bb8a9a_DO-NOT-PUSH.apk` 6,573,356 B, SHA-256
    `7ee0848493c299da9f3750fc1e3503d71d17e8a10116ff2ef500f944f13fa98f`, vc 36 (unchanged), signer `c99b6f26…`,
    SOURCE_COMMIT `7bb8a9a7db48`, SOURCE_DIRTY false — not distributed. New disposable AVD `Rc7_Final` (deleted): logcat
    cleared, COLD start → DRIVE, every tab, TRIP tiles, #228 START → STOP (also with the link down) → tag / rename / Back
    / delete — no card ACTIVE after STOP; 0 verifier / FATAL / ANR / Fatal signal / Maps "Authorization failure" lines.
    Later commits on the branch are documentation only (they don't change the tested Android source).
  - Seen in that run, pre-existing, not changed: TRIP keeps the last recording's map pins with nothing selected; header
    OFF instead of RCN while recording with Wi-Fi off; relaunch after a Back exit auto-connects (DISCONNECT is not a
    persisted Cancel); false "Point flush failed" at STOP. Code-path finding (not reproduced — the end was written
    ~0.55 s after STOP): `DriveRecorder.stopDrive` reads the row before the reverse geocode and writes the whole row
    after, so a rename / tag in that window can be overwritten and a drive deleted in it can still get lap sessions /
    a summary — open finding (reviewer confirmed the code path: `getDrive` → `reverseGeocode` → full-row `updateDrive`),
    not fixed; owner decision.
  - #235 owner setup (no values anywhere): repository secret `MAPS_API_KEY` (required for publication) — restrict the key
    to Android apps, package `com.openrs.dash` + release-cert **SHA-1** `5F:74:08:7D:B0:FE:B2:12:17:9B:EB:AA:AE:BC:95:A4:E8:B8:EB:CE`,
    API Maps SDK for Android (debug `com.openrs.dash.debug` needs its own key); `OPENWEATHER_API_KEY` optional (notice +
    no weather requests without it; OpenWeather restriction options unverified). Either key is extractable from a
    published APK. Unchanged: the main-push `release` job builds without keys (artifact only); the keystore step exits 0
    without its secrets (caught later by the signer check).
  - Reviewer (`~/Documents/openRS_-evidence/rc7-integration-review-2026-11-01/REVIEW.md`): provenance verified; **R1** minor,
    not fixed — TripPage delete / tags / rename handlers still reload `getRecentDrives` by hand on IO and can overwrite a
    newer Room emission (card ACTIVE until the next write). #226: `carried` keys match the `markFresh` tags; PUMP still "—"
    at 0 A (`> 0` gate, ChassisPage), demanded pressure has no screen consumer; hardware meaning / scale unverified. #228:
    only manual STOP exercised (emulator) — auto-record stop / orphan finalization expected, not tested. Docs D1–D4
    (CONTRIBUTING → `ObdMerge.merge` with CanDataService as caller; CHANGELOG #228 / #226 wording; this record): landed as
    docs-only `64256ba` + this record (merge of `0d52b7c`, then updates).
  - Privacy audit (`~/Documents/openRS_-evidence/repo-privacy-audit-2026-11-01/REPORT.md`, revised 10:30): the adapter
    identifiers were found only in `FirmwareProbeLogTest.kt` history (introduced `4b2b253`; local, unpublished when
    inspected — "not found" = inspected scope only, not proof of absence); fixed at the tip by `24023ce`; history not
    cleaned — tainted commits are counted by the cleanup plan's freeze step (the figure changes as refs move); refs feature/rc7, fix/rc7-adapter-status-export, docs/rc7-reconcile,
    fix/rc7-trip-active-awd-zero, fix/rc7-sanitize-adapter-ids, the integration branch (+ reflogs / worktree HEADs).
    Every one stays unpublished. F2: client API keys inside APKs published before rc.6 (to verify: Maps restriction by
    package + release-cert **SHA-1**; OpenWeather options / replacement consequences); rc.6 asset carries no keys (CI path
    — developer investigating, `rc7-ci-keys-2026-11-01/`). F3: personal email in published commit metadata — never rewrite
    published history. Never quote identifier values anywhere.
  - History cleanup (`~/Documents/openRS_-evidence/rc7-history-cleanup-plan-2026-11-01/PLAN.md`) — **proposal, not
    executed, awaiting the owner**: rewrite only `b0c1c57..86e4af8` (33 commits) of the candidate in an isolated clone
    (blob SHA → SHA swap, re-signed), published main / staging / tags byte-identical; private pre-cleanup bundle; old → new
    `commit-map.tsv`; publishable clone with only the new line; whole-object-DB + push-set checks; pre-push deny-list.
  - Main-route docs: `docs/setup-guides-ble-fw` (from origin/main `0d64248`): `d3757a7` Bluetooth manual + device (#234),
    v1.61 flash name, ESC #125; `a388e90` firmware/README + hardware-setup — GATT layout **unresolved** (FFE0 = planned
    fw v2.x interface in an unimplemented header; v1.x keeps upstream `ble.c`, unpatched; FFF0 / FEE0 from upstream source
    reading only; no GATT trace), v1.61 / v1.2 file names, BLE device name unsourced.
  - Open-issue triage (2026-11-01, 50 open issues read: labels, milestone, today's events): 28 label events on 14 issues
    (#125, #205–#208, #225–#230, #234) by the maintenance role, no milestone changes, comments on #226 / #228 / #234. Left
    for the owner: no milestone on #226–#234; no priority on #222 / #223 / #230; #206 lacks `investigation`; #174 / #175 /
    #198 shipped in prereleases but open (close via merged PRs per the release checklist).
- Evidence classes for rc.7 (keep them apart in reports): **owner-verified hardware** — frozen `a605b1c` only (2026-10-31:
  Pro over BLE telemetry, firmware probe, parked drive-mode confirmation over BLE; ESC not passed; DTC partial; Pro over
  Wi-Fi telemetry); **tester report, build unconfirmed** — second tester (DIY C3); **emulator / fake adapter** — every
  fix since `a605b1c`, incl. #234 (`81baa76`); **unit tests** — 1025 / 79 per variant at `81baa76`; **source reasoning
  only** — sticky service restart without the app (#234), BLE GATT layouts (#218); **untested** — real BLE device
  selection on `81baa76`, ESC change confirmation on a car (#125), openRS_ firmware Wi-Fi detection on current firmware
  (#220), unreleased firmware source (never flashed).
- Still open (2026-11-01):
  - **Gates before any rc.7 tag (owner's go-ahead for each):** owner hardware test on `81baa76` (pending — no result
    recorded); a new versionCode for any distributed build (every review APK so far is vc 36); minified-release smoke of
    the final build (R8 gotcha / #227); repository secrets for #235 (Maps required) before any tag; PRs (feature/rc7 → staging, fix/sapphire-row-validation → main); the tag.
  - **Hardware investigations:** hardware checklist (Samsung, BLE selection, firmware capability, boost signal identity
    #207, spark #205 / throttle #206, real DTC replies #215, TPMS BAR on the phone #219, 0304 / 03EF identity, button bit
    captures); ESC confirmation (#125); oil / PTU identity (#229) — blocks thermal acceptance; 0x138 gear (#230); AWD
    NRCs (#208); a real decimal-comma-phone export.
  - **Disclosed limitations (behaviour as built):** recording needs GPS — gaps are explained, not filled (#221); boost
    signal identities unconfirmed (#207); ESC 3 s confirmation window unmeasured (#125); BLE layouts source-verified only
    (#218).
  - **In the integration candidate only (emulator / JVM evidence at `86e4af8`), not in the owner-test APK `81baa76`:**
    `918add4` #228 ACTIVE after stop (manual STOP only), `690be8d` #226 demanded pressure / pump current take a decoded 0
    (JVM only), `24023ce` synthetic identifiers in `FirmwareProbeLogTest`, `d17411a` #235 release keys (CI path tested
    in a keyless clone; secrets not configured), `4a9f92b` #228 edits only write (emulator) — tested together at `7bb8a9a`. Publication hold: see the integration record below.
  - **Deferred enhancements / follow-ups seen, not changed:** GPS-independent recording (#221, schema change); false
    "Point flush failed" warning; 20 s silent-adapter hold; boost peak caption is psi in any unit; debug Settings label
    squeezes UPDATES. Separate: Sapphire map tiles, `docs/thermal-thresholds` PR, docs on main — local `docs/setup-guides-ble-fw` (from origin/main
    `0d64248`, `d3757a7`: android/docs Bluetooth note #234, v1.61 flash file name, ESC #125; android/docs guides go via
    main, as `1ec8dc7` did) and `a388e90` (firmware/README v1.61 / v1.2 file names; GATT layout unresolved, FFE0 = planned
    v2.x interface, #218) — both local, not pushed. At staging → main take main's
    `README.md` (feature/rc7 is only behind) and `docs/feature-roadmap.md` (both sides edited — `5606ac7` vs `b2fbe87`),
    cleanup, stable promotion.

**rc.6 publication record (2026-10-28 UTC; evidence `~/Documents/openRS_-evidence/rc6-release-2026-10-27/PUBLISHED.txt`):**
- Android: PR #202 (feature/design-pass `056556b`) → staging merge `d2f7dc6`; staging CI 40625379258 green; tag
  `android-v2.2.7-rc.6` → `d2f7dc6`; tag CI 40625789185 green (Build & Test, Lint, Verify Firmware, Attach APK).
  Prerelease https://github.com/klexical/openRS_/releases/tag/android-v2.2.7-rc.6 — `openRS_v2.2.7-rc.6.apk`,
  6,458,616 bytes, SHA-256 `1e7d692f80f736bc3eeb02d9c391e388a5f3e5b948e4adf70b2fbc4d3210d096`, APK Signature Scheme v2,
  cert SHA-256 `c99b6f263ee3b5bb610040431403d459e0e7b12aa941f114f0d9717d974ada74` (= rc.5), `com.openrs.dash` vc 35,
  2.2.7 + RC "rc.6", build-provenance attestation for the tag @ `d2f7dc6`. Notes = changelog-based highlights + "Known
  limitations / RC testing status".
- Sapphire: PR #203 (`release/sapphire-main` `d5a3107`, web/ only) → main merge `0d64248`; main CI 40624756338 green
  (its Lint job hides the pre-existing lint crash + detekt 92, both advisory); deploy-pages 40624756293 →
  https://klexical.github.io/openRS_/; live import of real rc.6 TRIP exports in a disposable profile 11/11, no app errors.
- **Hardware-testing limitations (rc.6, unverified — see the changelog section):** no real-phone or on-car run of rc.6;
  battery SoC (BCM 0x4028) unverified; KR cyl 2–4 rejected / zero on tested PCMs; charge air 0x0461 = `0x0000` on some
  PCMs; throttle 0x076 absent on some cars (pedal fallback); sub-zero ambient (#185); recording needs GPS; Bluetooth
  picker not checked on a phone; What's New not re-shown between RCs.
- **Sapphire known issue (separate, not from rc.6):** route map tiles (`GpsMap.tsx`, Carto `dark_all`, URL unchanged since
  April) now come back as "API KEY REQUIRED" placeholder images on the live site; the route line and markers still draw.
  Needs a tile-provider decision — tracked as its own fix, not part of rc.6.
- Still open: user's on-car / phone checks; `docs/thermal-thresholds` PR to main (merges clean); retiring
  `fix/sapphire-csv-import` (superseded, Sapphire now live); staging → main stable merge (plan under Sapphire →
  Integration / deployment). Local rehearsal branches `release/rc6-staging` (`b023d1a`, APK rehearsal source) and
  worktrees in `~/Documents/openRS_-worktrees/` can go once no longer needed.

Docs branches skip staging entirely — branch from main, PR to main.
Never route README.md, assets/, or docs/ through staging.

**Release checklist (staging → main):**
1. Scan open GitHub issues for any fixed by commits in the release (`gh issue list --state open` vs commit history)
2. Close standalone issues that shipped — comment with commit SHA
3. Update umbrella issue checklists — check off shipped sub-items with version annotation
4. Close umbrella issues only when ALL sub-items are checked off
5. Add/update the highlights entry in `WhatsNewDialog.kt` for the new base version — since #214 there is no fallback: a base
   version without highlights shows nothing (`shouldShowWhatsNew`). It auto-opens once per release name
   (`BuildInfo.releaseName`, e.g. rc.6 → rc.7 shows it), with content per base version — extend the entry per RC
   (Settings also opens it). Builds up to rc.6 all named "2.2.7" never re-showed it between RCs

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
      index.ts         — Zustand store (sessions, panels, compare, nav); panel + session remembered (`sapphire_view`)
      settings.ts      — Settings store (units, theme) — localStorage persisted
    lib/
      import.ts        — ZIP import pipeline (drive_*.csv + drive_summary_*)
      db.ts            — IndexedDB (idb) CRUD
      format.ts        — Formatters + useUnitFormatters() hook
      units.ts         — Metric/imperial conversion (speed, distance, temp, boost, tire, fuel)
      export.ts        — CSV/JSON session export + browser download
      compare.ts       — Multi-session KPI deltas + time normalization
      mapColors.ts     — GPS map color mode thresholds (fixed cyan, never the paint accent)
      readings.ts      — Chart values: sentinel / NaN → null (gap), genuine zeros kept; per-corner latest TPMS
      gforce.ts        — G-force scatter downsampling (keeps the lat-G peaks)
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

**Release merge checklist — Sapphire (staging ↔ main diverged; updated 2026-10-27):**

*Resolved on feature/design-pass — every main-only `web/` change (`e5080c8`, `e0c8b5e`, `e9cd565`, `dc32b8b`) is ported or
superseded, so **take this side for every `web/` file** when merging:*
- Importer structure: manifest-driven single / batch import (`importBatchZip`, `isBatchZip`), profile JSON merge
  (full drive export → 6,700 RPM from the profile, not the CSV's 4,600).
- Drive CSV choice by name — `isDriveCsvName` / `isDriveCsvEntry` skip RaceChrono / TrackAddict / knock CSVs (#183).
- `parseTripDataFromCsv`: BOM strip, then `trim()`, then skip `#` lines (`# openrs_csv_v2`), then the
  `< 2 lines` → no-trip return, then the required-columns `ImportError` (`REQUIRED_COLUMN_GROUPS`: timestamp + rpm,
  main's `e9cd565`; `abef459`). Only drive CSVs are checked. Main's BOM + ImportError tests are in `import.test.ts`
  (`5eace3d`, `abef459`).
- Batch import is all-or-nothing (`3f7302f`, `5bf6435`): one malformed drive CSV rejects the batch in either order,
  and a valid batch is stored with `putSessions` (one IndexedDB transaction, aborted if any `put` throws) before any
  session reaches the store — keep ImportPanel's `putSessions` call when merging. `ImportPanel.test.tsx` guards the
  panel; `web/scripts/idb-check/` checks the real database (Chromium via Playwright installed outside the repo — steps
  in `check.mjs`): reload / restart persistence, quota and clone failures roll back.
- Profile ↔ drive association (`e7f7416`): single and batch imports share `findProfileFor` — by filename identity
  (`drive_<ts>.csv` → `drive_profile_<ts>.json`, `drive_<id>_<ts>.csv` → `drive_<id>_<ts>_profile.json`), manifest
  type not needed; one-CSV + one-profile fallback only when names don't identify different drives; never by ZIP
  order. Android batch manifest now types these `drive_profile` / `drive_summary` (`535253e`; rc.6 batches said
  `unknown` and still import). `import.batchProfile.test.ts` (real TRIP batches, both manifest generations).
- Android half of `e5080c8` (drive CSV without GPS) — `9a52612`: `DriveExportBuilder.driveTrackFiles` / `hasRoute` used
  by the diagnostic export, `shareDrive` and `shareDrives`; no-GPS CSV has empty lat / lng, GPX / RaceChrono /
  TrackAddict only with GPS, empty drives get no track files. Real TRIP exports in `__fixtures__/android-exports/`
  (`drive-nogps-telemetry.zip`, `batch-gps-nogps-empty.zip`, `import.noGpsExport.test.tsx`). Keep this side's exporter.
- Hook order (main's `e5080c8` TripPanel, `e9cd565` DashboardPanel / ComparePanel / SessionsPanel, `e0c8b5e`
  Sparkline `useId`) — `aa79eb3`; whole-app ESLint 0 errors. `hookOrder.test.tsx` keeps each component mounted
  through empty → data → other drive → diagnostics-only → empty.
- Compare panel crash on mount: `useCompareSessions` built a new array per store read → "Maximum update depth
  exceeded" (zustand 5) — fixed with `useShallow` (`65fe314`). Main has the same selector + zustand 5, so it is expected
  there too — UNVERIFIED (not run on main; no hotfix requested). Keep the fix when merging main's ComparePanel.
- `dc32b8b` (v2.2.6 release merge) carries no Sapphire difference — its `web/` equals staging `94883ca`, already here.
- Chart gaps (`a913177`): `lib/readings.ts` maps temps ≤ −90, polled −1 and NaN to `null` (TripPanel, TempStackChart —
  main floored the stack at 0, Recharts 3.8 breaks a stacked layer at null; TimeSeriesChart `number | null`); genuine
  zeros stay. Per-corner Dashboard TPMS + "—" corners; TripPanel diagnostics-only hint (TRIP → SHARE wording).
- Paint accents (`c08b731`): `colors.accent` = `var(--color-accent)` written by App.tsx (+ `--color-accent-d`,
  `--color-accent-print` for paper); fixed `colors.cyan` for map buckets, multi-series legends, ModeTimeline, TPMS
  band and DiagnosticsPanel INFO / session (main left those on the accent — its `accent + '15'` chips are invalid CSS).
  Sapphire is dark-only (no DAY palette); `tokens.test.ts` checks every paint on the dark surfaces + print.
- Remembered panel / session (`e5f87bd`): `sapphire_view` in the store; App keeps the saved session if it still
  exists, else the newest import (or none); IndexedDB load `.catch`. `App.test.tsx`.
- Compare (`74f6cf6`): DeltaCard per-unit neutral floor (+ `L`, `/100`). **Not taken:** main's `pctChange =
  diff / baseValue` — it flips the sign for a negative baseline; this side's `diff / |baseValue|` is kept (main's
  tests pass here, plus a negative-baseline test). Overlays: `normalizePoints` maps missing coolant / oil / throttle
  through `lib/readings.ts` → null (a gap), genuine zeros kept — main clamped them to 0 (`compare.test.ts`,
  `ComparisonChart.test.tsx`).
- Perf / UX (`1c3e602`): GpsMap per-mode precompute (with bookmarks), ComparisonChart resample cache, GForceScatter
  keeps lat-G peaks (`lib/gforce.ts`), ModeTimeline case normalisation (CSV + profile timeline), SessionsPanel
  `allSettled` bulk delete, SettingsPanel copy. **Not taken:** main's 150 ms SessionsPanel debounce — SearchBar already
  debounces 200 ms (on main too).
- Browser smoke (2026-10-27, production build, Playwright Chromium; script + ZIP + screenshots in `~/Documents/openRS_-evidence/sapphire-smoke-2026-10-27/`): gaps vs zeros
  in the SVG, all six paints on screen + print, reload restore, missing / deleted saved session — 29/29 checks, no page errors.

*Separate follow-ups (documented, not release blockers for this checklist):*
- `DriveRecorder` stores points only from location updates, so a drive without GPS records no telemetry (the export
  fix above covers seeded / legacy data and any later recorder change).
- TRIP's map draws a marker at a no-GPS drive's stored coordinates — only reachable with seeded data (real no-GPS
  drives have no points).
- thermalRecord visualisation in Sapphire (the block imports and is ignored today).
- Thermal Soak is a stacked chart: while one layer is missing, the layers above it sit lower (stack total). Inherent to
  stacking (main's 0-floor shifts them the same way); a non-stacked view would be a design change.

*Integration / deployment steps (rc.6 done 2026-10-28 UTC — record under Branch / Release Workflow):*
- Android → staging: `release/rc6-staging` = `origin/staging` + `--no-ff` merge of feature/design-pass (tree identical to
  feature/design-pass; nothing behind staging). DONE: PR #202 → staging `d2f7dc6`, tagged `android-v2.2.7-rc.6`,
  published by `publish-release` as a prerelease.
- Sapphire → main: `release/sapphire-main` = `origin/main` + one commit taking `web/` whole from feature/design-pass
  (main has no `web/` change after `e9cd565`; `package.json` / `package-lock.json` identical, so deploy's `npm ci`
  is unchanged). Nothing outside `web/` — main's Android, version files, CHANGELOG, docs, CLAUDE.md and
  `android/browser-emulator` stay as they are. DONE: PR #203 → main `0d64248`, deployed and live-checked. Supersedes
  `fix/sapphire-csv-import` (`1d1991e`). The later staging → main merge sees identical `web/` trees (unless web/ changes again).
- Staging → main (stable, later): keep this side for `web/` and the Android exporter; don't take main's release-version
  files from `dc32b8b`. `ci.yml` by hand, never wholesale: keep staging's `publish-release` (tag RC suffix + `prerelease`
  — main's copy has neither); the release-job verify path fix (main `8bba37f`) is already here (`1720595`); take main's
  release-job keystore decode; decide `38fcf67` (lint advisory — staging's lint no longer crashes, #196) and `8905b1f`
  (detekt — baseline predates rc.6, not run against this code; firmware script tests pass on both). `firmware/release/`
  binaries differ between main and staging; each side passes `verify-release.sh` against its own manifests.
- After any merge, run all of: `vitest run` (incl. `import.appExports.test.ts` — real ZIPs in
  `__fixtures__/android-exports/`, `import.androidExport.test.tsx` — `__fixtures__/android-export-rc6/`,
  `import.noGpsExport.test.tsx`, `import.batchProfile.test.ts`, `hookOrder.test.tsx`, `ImportPanel.test.tsx`,
  `App.test.tsx`, `readings.test.tsx`, `ComparisonChart.test.tsx`), `eslint .` (0 errors), `tsc -b`, `vite build`, and
  `scripts/idb-check` (9/9 checks). deploy-pages runs Node 22 `npm ci` + `npm run build` + copies the emulator to
  `dist/emulator` — checked with Node 22 on `release/sapphire-main`.
- Deploy (`deploy-pages.yml`: push to main touching `web/**`, or manual dispatch) only from the merged result.
  Deployed Sapphire (main `0d64248`) imports rc.4+ / rc.6 exports. Known issue: map tiles show "API KEY REQUIRED"
  (provider, see the rc.6 record).

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
