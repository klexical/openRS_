---
paths:
  - "**/android/**"
---
# openRS_ — Source Layout

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
  PcmPollSchedule.kt      — Pure PCM poll schedule: engine running (>400 RPM) = fast group + 1 slow DID
                            per cycle at 150 ms (~0.8 Hz fast); engine off = full sweep + 30 s idle
  ObdResponseParser.kt    — Mode 22 DID dispatch by module (PCM/BCM/AWD/PSCM/FENG/RSProt/HVAC/IPC)
  PidRegistry.kt          — Data-driven catalog decoder; builds index from ForscanCatalog on first load
                            Fallback for ObdResponseParser when DID not hardcoded; stores in genericValues
  SlcanTransport.kt       — Transport interface: open/readLine/writeLine/close
  TcpSlcanTransport.kt    — MeatPi Pro: raw TCP 192.168.0.10:35000
  WebSocketSlcanTransport.kt — MeatPi USB: WebSocket ws://192.168.80.1:80/ws
  BleSlcanTransport.kt    — BLE GATT transport: connects and forwards BluetoothGatt callbacks to BleGattCore; GattLink over
                            BluetoothGatt. autoConnect=true after a first success with that MAC (per process), MTU 247 w/ fallback
  BleGattCore.kt          — Android-free setup sequence / writes / framing (#218): layout from BleAdapterProfile (Pro
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
                            eviction marks the connection unresolved; 300 ms = poll pacing only, after a write reported sent
                            (none after a possibly delivered one, #333); certainly-not-sent writes withdrawn;
                            7F 22 78 keeps the request outstanding (PcmRequestLedger, #216). OPENRS? probe schedule
                            on its own coroutine (×3, give-up 13 s, late replies accepted, #220); DTC frames diverted by
                            service ID (#215)
                            Command response channel routes +FRS: lines separately from SLCAN frames
  AdapterState.kt         — Disconnected / Connecting / Connected / Reconnecting / Idle / Error (sealed, `label` = fixed
                            names for logs — R8 renames the classes) + LinkPhase (OFF/CONNECTING/CONNECTED/RECONNECTING/FAILED)
  SlcanParser.kt          — SLCAN frame tokenizer ('t'=standard, 'T'=extended); exact DLC × 2 data (+4 only when timestamped)
  SlcanFraming.kt         — per-connection framing mode (plain / timestamped, locked from the first 8 frames) + counters
  Mode22Frames.kt         — `Mode22`: request DID / positive DID / NRC helpers + `RawReply` (Frame / AmbiguousNrc) for probe matching
  WriteOutcome.kt         — transport write outcome: SENT / POSSIBLY_DELIVERED (GATT 13) / WriteFailure (not sent / uncertain)
  IsoTpBuffer.kt          — ISO-TP SF/FF/CF reassembly; caller sends BCM_FLOW_CONTROL after FF
  ReceptionStall.kt       — low reception on an open connection (#356): frames per quarter second of a monotonic clock
                            against the connection's own rate; a low span ending in a catch-up burst = RECEPTION_STALL,
                            any other = RECEPTION_LOW, silence then the connection's end = nothing; stamped at its start.
                            Pure, one per connection, fed by SlcanConnection's frame loop. Names spans; prevents nothing
  EscCommand.kt           — ESC request + confirmation (#125): window = firmware press plan + 3 s; only a 0x1C0 frame
                            stamped after the request on the same link generation confirms; one request at a time (Busy)
  FirmwareApi.kt          — FirmwareCommandSender interface + WiFiFirmwareApi (REST /api/frs) +
                            BleFirmwareApi (AT+FRS= over SLCAN transport). Both throw BusyException
  DriveCommand.kt         — Shared drive mode command flow: executeDriveModeChange() suspend function
                            Accepts FirmwareCommandSender → 2s settle → 15s CAN poll → offer (no further
                            command without the driver's tap, #252 S5)
                            Returns DriveCommandResult sealed class; used by MorePage + DriveModeDock
demo/                     — DEBUG BUILDS ONLY (DemoMode.AVAILABLE = BuildConfig.DEBUG)
  DemoSimulator.kt        — Pure, deterministic VehicleState generator per DemoScenario (IDLE / DRIVING / WARNING /
                            CONNECTION_LOSS) — same (scenario, t) → same frame; stamps CAN + OBD freshness tags
  TripPreview.kt          — TRIP sample states (`--es trippreview …`): drives / route points / recording DriveState in memory;
                            ids < 0 — TripPage reads them instead of Room and skips every write for them
  DemoMode.kt             — Controller: 25 Hz tick into OpenRSDashApp.vehicleState, pause/reset/scenario, sessionEpoch,
                            DemoFirmwareApi (drive mode / ESC → simulated state), simulated DTC scan/clear
data/
  VehicleState.kt         — Immutable data class; sentinel rules: CLAUDE.md, VehicleState Rules.
  DashLayout.kt           — Custom dashboard layout: DashCell grid, AVAILABLE_FIELDS catalog; JSON via AppSettings
  BoostRule.kt            — BoostSource / BoostReading / BoostRule (policy rc7-boost-v3) / BoostSession / StoredBoost
                            (#207): the one boost rule for every consumer + what a recorded value is; see the boostKpa gotcha
  DriveDatabase.kt        — Room DB v13: DriveEntity (+ avgStftPct/avgLtftPct/trimSamples v11, pullCount/knockPullCount v12,
                            peakBoostSource/boostPolicy v13), DrivePointEntity (+ boostSource/baroKpa v13), KnockEventEntity
                            (+ cylinder v11, boostSource v13), DtcCodeEntity (+ failureType v13), DriveDao, migrations
                            v1→…→v13 (v12 rescales stored TIP ×903.81/128; v13 columns nullable, never backfilled — null =
                            recorded before the rule; checked on the emulator from a real v12 DB)
  DriveState.kt           — Live drive state flow: `phase` (IDLE / STARTING / RECORDING / PAUSED) + `pauseReason` (HAND / LINK),
                            peaks, recentPoints, fuel; `isRecording` = open (recording or paused), `isPaused` (#316)
  DriveLabel.kt           — the one label for a drive (STARTING / REC / PAUSED / PAUSED · NO LINK / PAUSED · ENGINE OFF) and the
                            commands offered beside it; the notification, DRIVE's strip and TRIP's chip and buttons read it; the header
                            shows REC or plain PAUSED (#316)
  PendingSummary.kt       — Post-drive summary queued (DriveRecorder.pendingSummary) until closed on TRIP — shown once
  DtcResult.kt            — module, code, description, status (ACTIVE/PENDING/PERMANENT/UNKNOWN)
  DtcModuleSpec.kt        — name, requestId, responseId for DTC scan targets
  ForscanCatalog.kt       — Lazy loader for assets/pids/forscan_modules.json
  FuelEconomy.kt          — Real-time fuel economy: 60s rolling window, instant/avg, distance-to-empty
  PerformanceTimer.kt     — 0-60 / 0-100 timer using CAN speed: IDLE → ARMED → RUNNING → FINISHED
  SessionDatabase.kt      — Room DB for session snapshots: SessionEntity, SnapshotEntity, SessionDao
  BoostSweepStats.kt      — Target-vs-actual boost delta analytics; rc.5 uses fresh-only samples;
                            rc.6 adds avgWgdcAtPeak (≥ 90 % of peak TIP) + byGear (gauge PSI vs std atm)
  BoostSessionLog.kt      — Live 90 s window of fresh TIP decodes (+ target/WGDC if ≤ 2.5 s old) for the POWER
                            BOOST SWEEP chart; fed from CanDataService.mergeObdState, reset with KnockSessionLog
  FreshnessGate.kt        — Per-drive gate: polled values recorded once per decode, never pre-drive
  KnockCapture.kt         — Per-cylinder knock events from fresh KR (retard POSITIVE, ≥ 0.1°)
  KnockStats.kt           — Shared knock aggregation: half-open buckets, per-cylinder split
  KnockSessionLog.kt      — In-memory session knock capture (no recording needed), cap 2,000, own FreshnessGate +
                            PullTracker; fed from CanDataService.mergeObdState, reset with knockEventCount
  PullTracker.kt          — Streaming WOT pull detector (≥80 % throttle, ≥2,500 RPM, ≥2 s; ≤2 s gaps) + knock attribution
  FuelTrim.kt             — FuelTrimAccumulator (fresh + warm STFT/LTFT avg) + FuelTrimDrift alert (±8 %)
  DriveThermalRecord.kt   — Drive thermal record (thermalPeaksJson v2): DriveThermalTracker (recorder, profile segments),
                            DriveThermalReview (TRIP TEMP badge + summary; PROFILE / LEGACY array / NONE), 95 % of crit in °C
  TripState.kt            — PeakType enum (RPM/BOOST/LATERAL_G/SPEED) + PeakEvent data class
  WeatherData.kt          — OpenWeatherMap snapshot: temp, wind, humidity, icon-to-emoji mapping
diagnostics/
  DtcScanner.kt           — UDS 0x19/02 scan across PCM(7E0)/BCM(726)/ABS(760)/AWD(703)/PSCM(730)/GFM(7D2); runScan/interpret =
                            the one path (full scan, auto-scan, retry); only DtcReply.Positive → OK (#215)
  DtcScanLedger.kt        — the session's DTC export record (DiagnosticLogger.dtcScans): begin (RUNNING) / progress /
                            complete / fail / cancel / interrupt / retry; cleared by sessionStart, stale tickets can't publish
  DidProbeScan.kt         — DID prober scan loop (`runDidProbeScan` → `DidProbeEnd` Complete / Stopped /
                            Failed(did, reason, sent)); records the labelled ProbeSession (see the DID prober gotcha, #249)
  DiagnosticLogger.kt     — Thread-safe singleton: frame inventory (opt B), decode trace, SLCAN log (opt C)
                            Pre-allocated hex lookup table; hex conversion outside lock for reduced contention
                            VIN masking at one write-time choke point (#260): 0x40A C1 pages, 0x405 pages 10/11/12,
                            0xF190 replies incl. consecutive frames (page rule), plus any frame holding 4+ consecutive bytes of
                            the assembled VIN (content layer, `VIN_RUN_MIN = 4`); raw log data → 00 (ID/DLC/timing kept),
                            inventory/trace → **, and every stored probe reply at export read once the VIN is known (`probeSessions`); counted
                            separately in LOG LIMITS; `ExportCounters` = one locked reading for both files
                            sessionTransport field: transport label for diagnostic reports and share intents
  DiagnosticExporter.kt   — ZIP orchestrator + share intents + crash history; delegates format generation
                            to DiagnosticReportBuilder and DriveExportBuilder; rc.6 adds drive_knock_session_*.csv (KnockSessionLog)
  DiagnosticReportBuilder.kt — Diagnostic summary text + JSON detail (JSONObject/JSONArray, no hand-rolled strings);
                            `buildSummaryAndDetail` takes one `ExportCounters` snapshot (linkIntegrity / logLimits /
                            nrcUnattributedTotal agree between summary and detail on a live export)
  DriveExportBuilder.kt   — Pure-function builders: GPX track, CSV telemetry, drive summary, DTC report
  DtcDatabase.kt          — 873-code bundled Ford DTC lookup (from res/raw/dtc_database.json)
  CrashReporter.kt        — Installs UncaughtExceptionHandler; persists CrashTelemetryBuffer to disk
  CrashTelemetryBuffer.kt — 300-snapshot ring buffer of VehicleState (`CAPACITY`); flushed on crash to JSON
service/
  CanDataService.kt       — Bound service, foreground only while a connection runs (ForegroundController);
                            WiFi/BLE-gated auto-reconnect; owns connection lifecycle
                            Transport selected by connectionMethod (WiFi vs BLE) + adapterType (TCP vs WebSocket)
                            Reports link events to the recorder (connection lost / back, engine running, auto-record switched, demo
                            entered) and applies no recording rule itself (#316)
                            onTaskRemoved (#212 option 2): ends only an initial attempt where Cancel is offered; saves nothing
  LinkOwnership.kt        — process-wide connection ownership (#212): generation, phase, manual-connect block, cancel
                            policy; autoStart / publish / deliver run under one lock (LinkOwnershipTest); memory-only
                            task-removal hold released at the next bind (`onActivityBound`); `connectionEpoch` per connection
  LinkPresentation.kt     — AdapterState → LinkPhase, notification text (+ the drive's label, `DriveLabel`), offersCancel (the one rule)
  AutoRecordPolicy.kt     — what the recorder does when a connection sees the engine running (#316): recording or starting →
                            keep; a link pause → resume within 15 min of the loss, else save it and start a new drive; a hand
                            pause → keep (only RESUME / STOP end it); no drive open → start if auto-record allows
  DriveRecorder.kt        — Room-backed drive recorder: start/stop/pause/resume, 1 Hz GPS + telemetry
                            FusedLocationProviderClient @ 1 Hz; batch writes (30 points/flush); peak tracking
                            Per-drive state = `DriveRun` (#236); STOP = one-transaction re-read + recorder columns; feeds
                            `LapTimer` every recorded fix (#237). Seams (geocode, LocationSource, onSummary) for tests only
                            `isFinalizing` while any drive is still being saved, or a cancelled start's cleanup runs (#329; a STOP's
                            save is tracked before the state says not recording)
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
  KnockScatter.kt         — RPM × boost scatter + aggregate card for PerfPage knock events section
                            rc.6: tap-to-select dot → KnockEventDetail; PULLS cell; KnockScatterMath = shared projection/hit-test
  BoostSweepChart.kt      — POWER THROTTLE & BOOST chart: RPM x-axis, target (dashed) / actual / WGDC; BoostSweepMath =
                            pure 250-rpm binning (1,500–7,000), null bins = gaps, min 40 kPa Y span
  FuelTrimDriftCard.kt    — PERF card: per-drive STFT/LTFT trend (last 20 drives), ±5/±10 % bands, drift alert
  trip/TimeSeriesMath.kt  — Pure gap segmentation + shared-axis ranges for DriveTimeSeries
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
                            #252 S5: a request sends ONE command; when the wait ends on another mode the dock offers
                            "Car is in X. You asked for Y." + SWITCH (30 s default, cleared on dismiss / epoch change / observed
                            mode change / dock close); only the tap sends again, through the normal confirmation
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
  DidProberSection.kt     — DIAG tab: interactive Mode 22 scanner for any ECU+DID (UI only; the scan runs in
                            diagnostics/DidProbeScan.kt)
  WhatsNewDialog.kt       — Version changelog dialog; shown on first launch after update
  trip/
    DriveHistory.kt       — rc.7 (#228) — TRIP history: observe (Room Flow, the list's only source) + delete / tags / rename
                            (writes only — never re-read the list by hand)
    DriveMap.kt           — Google Maps Compose wrapper: 6 color modes (SPD/MODE/BOOST/THRTL/G-LAT/TEMP),
                            start/finish flags, pause markers, peak markers (RPM/boost/lat-G/speed),
                            zoom-to-fit, map type cycling, blue dot location, color legend
update/
  UpdateManager.kt        — In-app update orchestrator: check → download → install via GitHub Releases API
  UpdateChecker.kt        — GitHub Releases API client; compares installed vs latest version per channel
  AppVersion.kt           — version parsing and ordering for updates: stable, `-rc.N`, `-nightly.YYYYMMDD` (#322); within one
                            base RC < nightly < stable; a nightly is offered on Beta only
  UpdateState.kt          — Sealed class: Idle / Checking / Available / Downloading / ReadyToInstall / Error
```
