# openRS\_ Feature Roadmap

> **Vision document** — this file captures long-horizon feature ideas beyond the
> [active release plan](../README.md#roadmap). Nothing here is committed to a
> release. When a feature moves to active development, a GitHub Issue is created
> and linked back to the relevant section.

*Generated from the v2.2.x baseline design document, March 2026.*

---

## 1. Overview

| # | Feature | Section | Horizon | Priority | Status |
|---|---------|---------|---------|----------|--------|
| 2.1 | Knock event logger | Data & Analysis | Near | High | `idea` |
| 2.2 | Fuel trim drift tracker | Data & Analysis | Near | High | `idea` |
| 2.3 | Boost target vs actual | Data & Analysis | Near | High | `idea` |
| 2.4 | Thermal soak modeling | Data & Analysis | Near | Medium | `idea` |
| 4.1 | Configurable DASH grid | UI / UX | Medium | High | `idea` |
| 4.2 | Portrait / landscape adaptive | UI / UX | Medium | Medium | `idea` |
| 4.3 | Night / track mode | UI / UX | Medium | Low | `idea` |
| 4.4 | Android home screen widget | UI / UX | Medium | Medium | `idea` |
| 5.1 | Lap timer + geofence | Track Day | Medium | High | `idea` |
| 5.2 | Sector timing | Track Day | Medium | High | `idea` |
| 5.3 | Oversteer / understeer detection | Track Day | Long | High | `idea` |
| 5.4 | Dyno power run analysis | Track Day | Long | High | `idea` |
| 6.x | DTC FORScan enrichment | DTC Enrichment | Near | High | `idea` |
| 7.1 | RaceChrono UDP bridge | Community | Medium | Medium | `idea` |
| 7.2 | Tune comparison mode | Community | Long | High | `idea` |
| 7.3 | iOS / web streaming | Community | Long | Medium | `idea` |
| 7.4 | Multi-vehicle profiles | Community | Long | Low | `idea` |
| 3.1 | Standalone HUD device | Hardware | Long | Medium | `idea` |
| 3.2 | Wideband AFR integration | Hardware | Long | Medium | `idea` |
| 3.3 | Dual CAN bus (MS-CAN) | Hardware | Long | Medium | `idea` |
| 3.4 | Steering wheel button mapping | Hardware | Long | Low | `idea` |
| 8.1 | Decoder regression tests | Dev & Reliability | Near | High | `idea` |
| 8.2 | Frame coverage heatmap | Dev & Reliability | Near | Medium | `idea` |
| 8.3 | Crash telemetry capture | Dev & Reliability | Near | Medium | `shipped` [#97](https://github.com/klexical/openRS_/issues/97) |
| 8.4 | DBC file export | Dev & Reliability | Medium | Medium | `idea` |
| 8.5 | WiCAN Pro GPS heatmap | Dev & Reliability | Long | High | `idea` |
| — | Brake pressure calibration | Data & Analysis | Near | Medium | `idea` |
| — | Tire temp PIDs (0x2823–26) | Data & Analysis | Near | Medium | `idea` |
| — | Predictive alerts | Data & Analysis | Medium | Medium | `idea` |
| — | Session replay | Data & Analysis | Medium | Medium | `idea` |
| — | Launch control stats | Track Day | Long | Medium | `idea` |
| — | Tire degradation proxy | Track Day | Long | Low | `idea` |
| — | UDS Fast Rate Session (DDDI) | Dev & Reliability | Long | High | `idea` |
| — | openRS\_ cloud sessions | Community | Long | Medium | `idea` |
| — | GPS sector heatmap per param | Dev & Reliability | Long | High | `idea` |

**Status key:** `idea` · `specified` · `in-progress` · `shipped`

---

## 2. Data & Analysis

Features that extend the core telemetry pipeline to provide longitudinal data,
fault correlation, and performance analysis beyond what real-time gauges show.

### 2.1 Knock Event Logger

**Near-term · High**

KR Cyl 1 (OBD PID 0x03EC via PCM) currently polls every 30 s — too slow to
catch transient knock events. A faster polling loop (~1-2 s) logging every
non-zero KR event with a full VehicleState snapshot creates a time-series knock
map. Displayed as a scatter plot of RPM vs boost, colour-coded by KR severity.
Critical for tune validation, E30/E85 flex tune comparison, and HPFP health
assessment.

- Log timestamp, RPM, boost PSI, IAT, throttle %, gear estimate, KR value
- Scatter plot on POWER tab — tap a point to see full snapshot
- Session aggregate: total knock events, worst-case RPM/boost cell, % of pulls
  with KR > 0
- Export knock map as CSV alongside existing diagnostic ZIP

<details><summary>Implementation notes</summary>

- Reduce PCM poll interval for 0x03EC from 30 s to 1-2 s (watch OBD bus load)
- `KnockEvent` data class: timestamp, rpm, boostPsi, iat, throttle, krValue
- `KnockLogger` singleton (similar to `DiagnosticLogger`) accumulates events per
  session
- Scatter plot: Compose Canvas or MPAndroidChart — circle radius = KR magnitude

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| 0x090 | RPM (passive CAN, ~2100 fps) |
| 0x0F8 | Boost pressure (passive CAN) |
| 0x2F0 | IAT (passive CAN) |
| 0x076 | Throttle % (passive CAN) |
| PCM 0x7E0 PID 0x03EC | KR Cyl 1 (polled — reduce to 1-2 s) |

**New files:** `diagnostics/KnockLogger.kt`

</details>

### 2.2 Fuel Trim Drift Tracker

**Near-term · High**

STFT and LTFT from the PCM reveal injector wear, MAF drift, vacuum leaks, and
fuelling compensation over time. Plotting as a line chart across multiple
sessions turns isolated snapshots into a longitudinal health indicator. A
consistent positive LTFT trend (>5 %) suggests the engine is running lean — a
leading indicator before DTCs appear.

- Poll STFT (PID 0x06) and LTFT (PID 0x07) via standard OBD Mode 01 or Mode 22
  equivalent
- Record average STFT/LTFT per session alongside session metadata (date,
  distance, fuel type if user-entered)
- Line chart on POWER tab: X = session number/date, Y = trim %, +/-10 % bands
  with colour zones
- Alert if LTFT exceeds +/-8 % for two consecutive sessions

<details><summary>Implementation notes</summary>

- Add to `ObdConstants`: standard Mode 01 PIDs 0x06 (STFT bank 1) and 0x07
  (LTFT bank 1)
- Session summary: record session-average trims to SharedPreferences or Room DB
- Chart: recharts-style approach in Compose using Canvas drawLine/drawPath
- `TripState` already has session metadata — extend with
  `fuelTrimAvgShort` / `fuelTrimAvgLong`

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| OBD Mode 01 PID 0x06 | Short Term Fuel Trim Bank 1 |
| OBD Mode 01 PID 0x07 | Long Term Fuel Trim Bank 1 |

Formula: `(value / 128 - 1) * 100 %` (standard OBD scaling)

**New files:** `data/FuelTrimHistory.kt`

</details>

### 2.3 Boost Target vs Actual Overlay

**Near-term · High**

ETC desired (0x091A), wastegate duty cycle (0x0462), TIP, and measured boost
(0x0F8) are all already decoded. Plotting commanded vs delivered boost across an
RPM sweep immediately reveals wastegate creep, boost leaks, or intercooler heat
soak. This is the highest signal-to-noise chart for diagnosing EcoBoost boost
system health.

- Dual-line chart: commanded boost (dashed, Nitrous Blue) vs actual boost
  (solid, white)
- X-axis: RPM bins (500 RPM wide), Y-axis: PSI
- Capture only WOT samples (throttle > 95 %) to avoid idle/cruise noise
- Delta band: shade the gap between commanded and actual
- Full-pull detector: auto-capture when throttle crosses 95 % threshold

<details><summary>Implementation notes</summary>

- `WotSession` data class: array of (rpm, boostActual, boostCommanded, wgdc)
  tuples sampled at ~10 Hz
- `WotDetector` in `CanDataService`: watches throttle % and gates recording
- POWER tab: add chart composable below existing hero AFR cards
- Rolling buffer: keep last 10 WOT pulls for comparison

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| 0x090 | RPM (passive) |
| 0x0F8 | Boost actual (passive) |
| PCM PID 0x091A | ETC desired (polled, currently 30 s — reduce for this feature) |
| PCM PID 0x0462 | WGDC (polled) |

**New files:** `data/WotSession.kt`, `ui/BoostOverlayChart.kt`

</details>

### 2.4 Thermal Soak Modeling

**Near-term · Medium**

After a hard session, oil, coolant, IAT, and RDU temps do not drop instantly.
Logging temps at engine-off and at startup intervals builds a track-specific
heat soak model: how long until temps return to safe levels. Displayed as a
"cooldown timer" on the TEMPS tab.

- On connection-drop or engine-off detection: snapshot all four RTR temps with
  timestamp
- On reconnect: compare current temps to decay model, show estimated time
  remaining
- Build decay curve from multiple sessions: exponential fit per sensor per
  ambient temp
- TEMPS tab: add a "Cooldown" row when car is reconnecting after a session

<details><summary>Implementation notes</summary>

- Engine-off detection: watch for RPM = 0 sustained for > 5 s on reconnect
- `ThermalDecayModel`: per-sensor exponential decay constants, fit from logged
  session data
- Ambient temperature (0x1A4 / 0x340) is critical for normalizing decay rate
- Store decay sessions in Room DB: sessionId, sensorId, tempAtEngineOff,
  ambientTemp, decaySamples[]

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| 0x0F8 | Oil temp + PTU temp (passive) |
| 0x2F0 | Coolant temp (passive) |
| 0x1A4 | Ambient temp (passive) |
| BCM PID 0x1E8A equiv | RDU temp (polled) |

**New files:** `data/ThermalDecayModel.kt`

</details>

---

## 3. Hardware Expansion

Features that extend openRS\_ beyond the phone screen into dedicated displays,
additional sensors, and custom CAN interaction.

### 3.1 Standalone HUD Device

**Long-term · Medium**

An ESP32-S3 with a small round LCD (GC9A01, 240x240) or HDMI display running a
minimal always-on HUD independently of the phone — showing RPM, boost, oil
temp, and AWD split. Connects to the WiCAN Pro's TCP SLCAN port directly. No
phone required during operation.

- Target display: 2.1" round GC9A01 for a rally-computer aesthetic, or 3.5"
  HDMI for dash mount
- Firmware: ESP32-S3 Arduino/ESP-IDF, connects to WiCAN Pro TCP port 35000
- Decode: 0x090 (RPM), 0x0F8 (boost + oil), 0x2C0 (AWD split), 0x130 (speed)
- Display: 4-quadrant layout
- Power: 5 V via USB from WiCAN Pro or fused direct to OBD pin 16

<details><summary>Implementation notes</summary>

- Repository: new top-level `hud/` directory alongside `android/` and
  `firmware/`
- Build system: PlatformIO
- WiCAN Pro SLCAN TCP: same `SlcanParser` logic as Android, ported to C
- GC9A01 library: Bodmer TFT_eSPI (proven on ESP32-S3)
- Font: monospaced bitmap font for numeric readouts, minimal RAM footprint

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| 0x090 | RPM (passive CAN) |
| 0x0F8 | Boost, oil temp (passive CAN) |
| 0x2C0 | AWD torque split (passive CAN) |
| 0x130 | Speed (passive CAN) |

**New files:** `hud/src/main.cpp`, `hud/platformio.ini`

</details>

### 3.2 Wideband AFR Integration

**Long-term · Medium**

The stock narrowband O2 via OBD gives a coarse AFR signal. A standalone
wideband controller (Innovate LC-2, AEM X-Series) connected to a second ESP32
acting as a WebSocket bridge would feed high-accuracy AFR into the existing AFR
composables with no UI changes — just a second data source populating
`VehicleState.afr`.

- Wideband bridge: ESP32 reads controller analog output (0-5 V) or serial
  (Innovate MTX-L protocol)
- ESP32 hosts a WebSocket server on a second SSID or joins the WiCAN AP network
- App discovers bridge by scanning local network for second WebSocket source
- `VehicleState`: add `afrSource` enum (NARROWBAND_OBD, WIDEBAND_EXTERNAL)
- POWER tab: show wideband badge when external source is active

<details><summary>Implementation notes</summary>

- Analog: 0-5 V -> 10-bit ADC on ESP32 GPIO -> linear map to 10.0-20.0 AFR
- Digital: Innovate LC-2 serial at 38400 baud
- Alternative: AEM X-Series has CAN output — connect directly to WiCAN Pro
  second CAN port if available

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| OBD Mode 01 PID 0x24 | O2 sensor A/F ratio (narrowband fallback) |
| External | Innovate LC-2 serial protocol or 0-5 V analog |

**New files:** `can/WidebandConnection.kt`

</details>

### 3.3 Dual CAN Bus (MS-CAN)

**Long-term · Medium**

The Focus RS has both HS-CAN (500k, OBD pins 6/14) and MS-CAN (125k, OBD
pins 3/11). A custom Y-harness splits the OBD connector so one WiCAN handles
HS-CAN and a second handles MS-CAN. The app already has dual-adapter
architecture (`WiCanConnection` + `MeatPiConnection`) — a third connection type
would unlock HVAC state, factory display parameters, and the true MS-CAN
ambient temperature.

- Hardware: custom OBD-II Y-harness, 16-pin female to two 16-pin male
- Pin routing: both male connectors share pins 4, 5, 16 (grounds + battery)
- HS-CAN WiCAN: pins 6 (CAN-H) + 14 (CAN-L) on connector A
- MS-CAN WiCAN: pins 3 (MS-CAN-H) + 11 (MS-CAN-L) on connector B
- Software: `MsCanConnection.kt` — identical to `WiCanConnection` but S5
  (125 kbps) instead of S6
- App: settings toggle to enable second adapter, connection dot shows both
  adapter states

<details><summary>Implementation notes</summary>

- Y-harness sourcing: 16-pin OBD-II female socket (Amphenol 776163-1 or equiv)
  plus 2x male plugs
- Wire gauge: 22 AWG minimum for CAN signals, 18 AWG for power/ground
- Termination: MS-CAN requires 120 ohm across pins 3/11 — verify WiCAN internal
  termination can be disabled

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| MS-CAN 125k | HVAC, BCM comfort features, factory display sync |
| 0x1A4 | Ambient temp (MS-CAN source, vs GWM-bridged version) |
| Additional BCM PIDs | Accessible only from MS-CAN side |

**New files:** `can/MsCanConnection.kt`

</details>

### 3.4 Steering Wheel Button Mapping

**Long-term · Low**

The Focus RS steering wheel sends button state frames on the CAN bus. With
openRS-fw flashed, the firmware can intercept specific button combos and send a
synthetic CAN message the app translates into an action: lap split, start/stop
recording, or reset G-force peaks. Hands-free operation on track.

- Identify steering wheel button CAN ID and byte mapping (likely 0x0C0-0x0D0
  range — needs empirical logging)
- openRS-fw: button combo detection with configurable hold duration (500 ms
  default)
- App: receive synthetic `OPENRS:BTN:<action>` frame via existing firmware probe
  channel
- Configurable in Settings: assign combos to lap split / peak reset / recording
  toggle

<details><summary>Implementation notes</summary>

- Use DIAG tab frame inventory to identify button CAN ID empirically
- Avoid combos that trigger OEM functions (ACC, lane keep, etc.)
- openRS-fw: add `button_map[]` config array in firmware config struct

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| Unknown CAN ID | Requires empirical logging via DIAG tab |
| openRS-fw response | Existing `OPENRS:` string response mechanism |

**New files:** `firmware/src/button_map.c`, `can/FirmwareProtocol.kt` (extend)

</details>

---

## 4. UI / UX

Improvements focused on in-car usability, personalization, and glanceability.
All should maintain the existing Nitrous Blue / Frost White / Deep Black design
language.

### 4.1 Configurable DASH Grid

**Medium-term · High**

The 8-cell data grid on the DASH tab is currently fixed. VehicleState has 90+
fields. Letting users pick which parameters fill the grid makes the DASH tab
meaningful for different use cases — a tune session wants KR + WGDC + IAT + AFR;
a warmup lap wants oil + coolant + RDU + PTU.

- Long-press any grid cell to enter edit mode — parameter picker sheet slides up
- Parameter picker: grouped by category (Engine, Boost, Temps, Chassis,
  Electrical) with live preview value
- Persist selection in `UserPrefs` (SharedPreferences) as a `List<String>` of
  VehicleState field names
- Cell renderer: generic `DashCell` composable takes a label + value lambda
- Preset slots: "Tune day", "Track day", "Road" — one-tap switch between saved
  layouts

<details><summary>Implementation notes</summary>

- `DashGridConfig`: `List<DashCell>` (8 entries), each cell references a
  VehicleState field by enum
- `SettingsSheet`: add "Customize Grid" entry that opens the picker
- All 90+ fields already have units from `UserPrefs` (MPH/KPH, PSI/BAR, F/C)
- Backwards compatibility: default config matches current hardcoded layout

**New files:** `ui/DashGridConfig.kt`, `ui/ParameterPicker.kt`

</details>

### 4.2 Portrait / Landscape Adaptive Layout

**Medium-term · Medium**

Current layout is portrait-first. In landscape with a phone mounted horizontally
on the dash, portrait tabs waste screen real estate. A landscape-adaptive DASH
layout places the hero RPM/boost gauge left and the 8-cell grid right.

- DASH landscape: 60/40 split — left is circular RPM/boost gauge, right is 2x4
  data grid + AWD bar at bottom
- CHASSIS landscape: left is AWD wheel-speed quad, right is G-force + TPMS
  outline
- TEMPS landscape: 2x5 temp card grid with larger indicator bars
- Detect orientation via `LocalConfiguration.current.orientation`
- Tab bar: in landscape, consider a side-rail tab bar instead of bottom nav

<details><summary>Implementation notes</summary>

- Compose `WindowSizeClass` API (Accompanist) for clean breakpoint handling
- Each Page composable receives a `LandscapeMode: Boolean` param
- No new data or CAN changes required — purely layout

**New files:** `ui/MainActivity.kt` (WindowSizeClass) + per-page landscape
variants

</details>

### 4.3 Night / Track Mode

**Medium-term · Low**

A full red-on-black theme preserves dark adaptation between night runs.
Non-critical UI elements (tab labels, settings icon, header logo) are hidden.
Brightness is programmatically suppressed to minimum.

- Track mode palette: background #0A0A0A, accent #CC0000, text #FF4444, all
  blues replaced with reds
- Hide: tab labels (icons only), settings gear, DIAG and MORE tabs
- Brightness: `WindowManager.LayoutParams.screenBrightness = 0.05f`
- Double-tap anywhere to exit — large gesture area to avoid accidental exit
- Persist across reconnects

<details><summary>Implementation notes</summary>

- Add `NightMode` boolean to `UserPrefs` StateFlow
- `Theme.kt`: conditional MaterialTheme based on nightMode flag
- Screen brightness: `WindowInsetsController` or legacy LayoutParams approach
- Track mode toggle: long-press header logo

**New files:** `ui/Theme.kt` (extend), `ui/MainActivity.kt` (brightness control)

</details>

### 4.4 Android Home Screen Widget

**Medium-term · Medium**

A persistent home screen widget showing boost, oil temp, coolant temp, and
connection status — see car status without opening the app. Useful for warmup
monitoring from outside the car.

- Widget size: 4x2 cells (standard Android widget grid)
- Layout: 2x2 parameter grid + connection dot + adapter status text
- Parameters: user-configurable (same pool as DASH grid)
- Update interval: 30 s when connected, 5 min when disconnected
- Tap widget: opens app directly to DASH tab

<details><summary>Implementation notes</summary>

- `AppWidgetProvider` subclass: `OpenRSWidget.kt`
- Data source: `CanDataService` broadcasts VehicleState via
  `LocalBroadcastManager` to widget
- RemoteViews: widget layout in XML (no Compose on API < 31, use Glance API for
  31+)
- Background service must be running for live updates — widget shows
  "Disconnected" when service is stopped

**New files:** `ui/OpenRSWidget.kt`, `res/layout/widget_openrs.xml`

</details>

---

## 5. Track Day Intelligence

Features designed for track day and performance driving, leveraging the
combination of GPS (WiCAN Pro) and high-frequency CAN telemetry.

### 5.1 Lap Timer with Auto Start/Finish

**Medium-term · High**

The user drives the start/finish line once and the app auto-detects it on
subsequent passes via GPS geofence. No track database required. Lap time, peak
boost, peak RPM, peak lateral G, and fastest sector are overlaid on the trip
map.

- "Set S/F Line" button on TRIP tab — records current GPS coordinate
- Detection: GPS update checks distance to S/F point — crossing < 15 m triggers
  lap split
- Direction filter: only trigger if heading matches S/F heading +/- 30 degrees
  (prevents false triggers on pit lane)
- Lap summary card: time, peak RPM, peak boost, peak lateral G, min/max speed
- Lap list: scrollable on TRIP tab below the map, tap to highlight on track map

<details><summary>Implementation notes</summary>

- `LapTimer.kt`: state machine (IDLE -> ARMED -> TIMING -> SPLIT -> TIMING)
- GPS from WiCAN Pro (`TripPoint` already has lat/lon) — fallback to phone GPS
  via `LocationManager`
- Haversine distance formula for geofence check
- `TripState`: extend with `List<LapResult>` and `currentLapStartMs`
- Persist S/F line across sessions in SharedPreferences

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| WiCAN Pro GPS | USB LTE/GNSS dongle |
| Fallback | Android LocationManager (phone GPS) |
| 0x130 | Speed — sanity check on lap trigger (must be > 20 km/h) |
| 0x180 | Yaw rate — heading estimation if GPS heading unavailable |

**New files:** `data/LapTimer.kt`, `data/LapResult.kt`

</details>

### 5.2 Sector Timing

**Medium-term · High**

The user drops GPS sector markers during a reconnaissance lap. On subsequent
timed laps, the app records split times at each marker, enabling
sector-by-sector comparison. A sector delta view shows where time is gained or
lost vs the session best.

- Tap "Add Sector" during a lap — drops a pin at current GPS position
- Max 8 sectors per track, displayed as numbered pins on the trip map
- Sector delta: current lap sector time vs personal best — green/red colouring
- Theoretical best: sum of each sector's personal best (purple time display)
- Export: sector times included in trip ZIP as additional CSV columns

<details><summary>Implementation notes</summary>

- `SectorMarker`: lat, lon, heading, sectorIndex — List stored per track (keyed
  by S/F GPS coordinate)
- `SectorTimer`: watches GPS for approach to next sector marker geofence
- `TripState`: extend with `currentSectorSplits[]`, `bestSectorTimes[]`,
  `theoreticalBest`
- Map overlay: draw sector boundaries as arc segments on the OSM tile layer

**New files:** `data/SectorTimer.kt`, `data/SectorMarker.kt`

</details>

### 5.3 Oversteer / Understeer Detection

**Long-term · High**

The CAN bus provides everything needed for slip angle estimation: actual yaw
rate (0x180), steering wheel angle (0x010), vehicle speed (0x130), and all four
wheel speeds (0x190). The difference between actual and expected yaw is the slip
angle indicator — positive = oversteer, negative = understeer.

Expected yaw rate = `(speed * steering_angle) / (wheelbase * (1 + understeer_gradient * speed^2))`

- CHASSIS tab: oversteer/understeer gauge bar (centre = neutral)
- Event logging: oversteer events > 2 deg with GPS location, speed, steering
  angle, AWD split snapshot
- Heatmap: colour the track trace by slip angle
- Drift mode stat: max sustained oversteer angle per lap

<details><summary>Implementation notes</summary>

- Wheelbase Focus RS: 2.648 m (hardcoded constant)
- Understeer gradient: ~0.003 rad/m^2 — can be calibrated via steady-state
  circle test
- All signals are passive CAN — no additional polling required
- `SlipAngleCalculator.kt`: pure function, testable without hardware

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| 0x010 | Steering wheel angle (passive CAN, SASMmsg01) |
| 0x130 | Vehicle speed (passive CAN) |
| 0x180 | Yaw rate (passive CAN, ABSmsg02) |
| 0x190 | 4-corner wheel speeds (passive CAN, ABSmsg03) |

**New files:** `can/SlipAngleCalculator.kt`

</details>

### 5.4 Dyno Power Run Analysis

**Long-term · High**

CAN ID 0x070 provides torque at the transmission in Nm. Combined with RPM and
drivetrain loss estimates, this approximates a wheel torque and horsepower curve
across a full-throttle RPM sweep. Not a replacement for a real dyno, but a
repeatable relative measurement for pre/post-tune comparison.

- Full-pull detector: gate on throttle > 95 % sustained for > 2 s
- Resample torque + RPM to 50 RPM bins, smooth with 5-point moving average
- HP estimate: `HP = (Torque_Nm * RPM) / 9549 * drivetrain_efficiency`
  (default 0.85)
- Dual Y-axis chart: torque (Nm) left, HP right, RPM on X-axis
- Overlay up to 5 stored pulls colour-coded by date/tune
- Export pull data as CSV

<details><summary>Implementation notes</summary>

- Signal accuracy caveat: 0x070 is PCM-commanded torque, not measured —
  document clearly in UI
- Drivetrain loss: user-configurable in settings (default 15 %)
- `PullSession.kt`: captures RPM-binned torque array from a single WOT run
- Store up to 10 pulls in Room DB for session comparison

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| 0x070 | Torque at transmission (passive CAN) |
| 0x090 | RPM (passive CAN) |
| 0x076 | Throttle % (gating condition) |
| 0x0F8 | Boost pressure (annotate on chart) |
| 0x2F0 | IAT (annotate — shows heat soak across pulls) |

**New files:** `data/PullSession.kt`, `ui/DynoPullChart.kt`

</details>

---

## 6. DTC Enrichment

Extends the existing 873-code bundled DTC database with Ford-specific
descriptions, Focus RS-annotated causes, and community notes. Fully offline —
enrichment data ships as a bundled JSON updated on each release.

### 6.1 Scraper Pipeline

A two-step Python pipeline: scrape raw thread data, then transform into the
openRS\_ enrichment schema.

- `scrape_forscan.py` — crawls FORScan DTC subforum, extracts thread title,
  first post, and top 5 replies
- `build_enrichment_db.py` — parses causes from bullet patterns, detects Focus
  RS-specific content, outputs `dtc_enrichment.json`
- Rate limiting: 1.5 s between thread requests, 3 s between pages
- DTC pattern: regex `[PCBU][0-9A-F]{4}` extracts all codes from thread titles
- Focus RS detection: keyword list (hpfp, gkn, twinster, rdu, ptu, ecoboost
  2.3, lz platform)

### 6.2 Enrichment JSON Schema

Output file: `android/app/src/main/res/raw/dtc_enrichment.json` — keyed by DTC
code string.

```json
{
  "P0087": {
    "fordDescription": "PCM detected fuel rail pressure below commanded...",
    "possibleCauses": ["HPFP cam follower wear", "Clogged fuel filter"],
    "communityNotes": ["On the Focus RS this traces to HPFP follower..."],
    "focusRsSpecific": true,
    "sourceUrl": "https://forscan.org/forum/viewtopic.php?t=12345",
    "scrapedAt": "2026-03-12"
  }
}
```

### 6.3 Android Integration

`DtcDatabase.kt` gains an enrichment layer. Enrichment is always additive —
bundled description is the fallback if the code is absent from the enrichment
JSON.

- `EnrichedDtcInfo` data class: bundledDescription + fordDescription? +
  possibleCauses + communityNotes + focusRsSpecific + sourceUrl
- `DtcDatabase.lookup(code)`: checks enrichment map first, merges with bundled
  record
- Loading is lazy and cached — parsed once at first DTC scan, stored in memory
- `focusRsSpecific = true` -> show "RS" badge in DTC card header (Nitrous Blue
  chip)

### 6.4 Enriched DIAG Tab UX

DTC cards expand into a bottom sheet with full enrichment data. Related PIDs
link directly to live gauges.

- DTC card: tap -> bottom sheet with fordDescription, causes list, community
  notes
- Related PIDs row: tappable chips navigating to the relevant tab (e.g. "Fuel
  Rail PSI" -> POWER tab)
- "RS" badge on codes where `focusRsSpecific = true`
- Source line: "Community DB - Cached Mar 2026" with link to source thread
- Community contribution: JSON format is simple enough for PR-based additions

<details><summary>Implementation notes (scraper + integration)</summary>

**New files:**

| Path | Description |
|------|-------------|
| `tools/dtc_scraper/scrape_forscan.py` | Forum crawler |
| `tools/dtc_scraper/build_enrichment_db.py` | Transform to enrichment JSON |
| `android/app/src/main/res/raw/dtc_enrichment.json` | Bundled enrichment data |
| `diagnostics/EnrichedDtcInfo.kt` | Enriched DTC data class |

</details>

---

## 7. Community & Ecosystem

Features extending openRS\_ beyond a single-user app into a platform for the
Focus RS community and the broader automotive software ecosystem.

### 7.1 RaceChrono / Harry's LapTimer UDP Bridge

**Medium-term · Medium**

Broadcasting decoded VehicleState fields as a local UDP stream in RaceChrono's
custom OBD format. RaceChrono (and similar apps) see openRS\_ as a wireless OBD
adapter, instantly unlocking video overlay, lap analysis, and channel export for
Focus RS-specific data.

- UDP broadcast on port 35000 (loopback or local subnet) in RaceChrono custom
  OBD format
- Channel mapping: RPM -> rpm, boost -> MAP, wheel speeds ->
  wheelspeed_fl/fr/rl/rr, etc.
- Configurable in Settings: "External app bridge" toggle with port number
- No latency impact: broadcast on a separate coroutine after VehicleState update

<details><summary>Implementation notes</summary>

- RaceChrono custom OBD protocol:
  https://github.com/aolde/racechrono-custom-obd
- UDP socket: `DatagramSocket` bound to `0.0.0.0:35000`
- Channel update rate: match CAN frame rate for fast channels (RPM at ~100 Hz),
  10 Hz for polled values
- `UdpBridgeService.kt`: optional service started alongside `CanDataService`

**New files:** `can/UdpBridgeService.kt`

</details>

### 7.2 Tune Comparison Mode

**Long-term · High**

A built-in session comparison mode loads two saved SLCAN files and plays them
through the same `CanDecoder`, aligning by RPM and overlaying key channels on a
dual-trace chart. Before/after tune comparison without a laptop or SavvyCAN.

- Import: load any previously exported ZIP, extract SLCAN log
- Alignment: sync sessions by RPM at WOT onset (same throttle-crossing point)
- Channels: boost (actual vs target), AFR, KR, IAT, timing, torque
- Display: each channel as a dual-line chart (session A solid, session B dashed)
- Delta view: difference between sessions
- Summary card: peak boost delta, avg KR delta, peak torque delta, peak HP delta

<details><summary>Implementation notes</summary>

- `SlcanFilePlayer.kt`: reads candump file line by line, emits frames at
  original timestamps
- `ReplayCanDecoder.kt`: same decoder as live, sourced from `SlcanFilePlayer`
- Session pair loaded into memory — candump files are typically 10-50 MB for
  30 min
- No new CAN data required — purely playback + visualization

**New files:** `can/SlcanFilePlayer.kt`, `ui/TuneComparePage.kt`

</details>

### 7.3 iOS / Web Streaming

**Long-term · Medium**

The existing browser emulator uses simulated data. Extending it to accept a real
`ws://` address makes it a live dashboard for iOS users, co-drivers on a tablet,
or anyone without Android. No app install required.

- Settings panel in browser emulator: "Connect to live adapter" — input WS
  address (`ws://192.168.80.1:80/ws`)
- Same SLCAN parser as Android (already implemented in browser emulator)
- PWA manifest: add-to-home-screen on iOS for a near-native experience
- iOS limitation: WKWebView blocks `ws://` to non-localhost — test on Safari
  with `NSAppTransportSecurity` exception
- Fallback: local relay mode (openRS\_ Android acts as a WebSocket proxy)

<details><summary>Implementation notes</summary>

- Most SLCAN parsing JavaScript already exists in the browser emulator
- Add a connection state machine replacing the demo data generator
- PWA: add `manifest.json` + service worker for offline caching of the app shell
- WebSocket proxy in Android: expose a `ws://phone-ip:8080/ws` endpoint
  relaying WiCAN frames

**New files:** `android/browser-emulator/index.html` (extend),
`ui/WebSocketProxyServer.kt`

</details>

### 7.4 Multi-Vehicle Profile System

**Long-term · Low**

The architecture (WebSocket SLCAN + OBD Mode 22 + DBC-verified decoding) is
vehicle-agnostic. The only vehicle-specific layer is `CanDecoder.kt` and
`ObdConstants.kt`. A `VehicleProfile` system swaps the decoder and PID set based
on selected vehicle, enabling community ports to other Ford EcoBoost platforms
without forking.

- `VehicleProfile` data class: vehicleName, canBusSpeed, decoderClass, obdPids,
  rtRThresholds, dashGridDefaults
- Built-in profiles: Focus RS MK3 (current), Focus ST MK3, Mustang EcoBoost,
  Fiesta ST MK3
- Profile selector in Settings: shown on first launch if vehicle not yet
  configured
- Community profiles: JSON format allows PR-based contributions
- Per-profile CAN signal registry

<details><summary>Implementation notes</summary>

- Focus ST MK3: same EcoBoost 2.0, different boost targets, no AWD — large
  overlap with Focus RS decoder
- Mustang EcoBoost: same 2.3L engine family, different CAN IDs for some signals
- Fiesta ST: 1.6L EcoBoost, 3-cylinder, different RPM/boost/temp CAN mapping
- Abstract `CanDecoder` interface — each profile provides a concrete
  implementation

**New files:** `can/VehicleProfile.kt`, `can/decoders/FocusStDecoder.kt`

</details>

---

## 8. Developer & Reliability

Infrastructure improvements for maintainability, early regression catching, and
surfacing hard-to-reproduce bugs.

### 8.1 Decoder Regression Test Suite

**Near-term · High**

A JUnit test harness that replays known SLCAN frames through `CanDecoder.kt`
and asserts expected VehicleState output. Catches bit-level decoder regressions
instantly — critical as CAN IDs are added, DBC extractions corrected, and the
decoder refactored. Tests run in < 1 s on every build.

- Test fixtures: `.slcan` files captured from real sessions in
  `androidTest/assets/`
- `CanDecoderTest.kt`: loads fixture, feeds frames, asserts field values at
  known timestamps
- Parametrized tests: one test class per CAN ID to isolate failures
- Edge cases: roll-overs (RPM max), negative values (oil temp < 0 C at
  startup), missing frames
- CI: add to GitHub Actions workflow — PRs that break a decoder test are blocked

<details><summary>Implementation notes</summary>

- `CanDecoder.kt` is already a pure function (frame bytes -> VehicleState
  delta) — trivially testable
- Fixtures should cover: cold start, WOT pull, track session with AWD split
  changes
- Assert at minimum: rpm, speed, boost, oilTemp, coolantTemp, awd split, tpms
- JUnit5 + Kotlin coroutines test utilities

**New files:** `android/app/src/test/CanDecoderTest.kt`,
`androidTest/assets/*.slcan`

</details>

### 8.2 Frame Coverage Heatmap

**Near-term · Medium**

Visualize the DIAG tab's frame inventory as a colour-coded grid — green for
active, yellow for intermittent, red for expected-but-absent. Essential for
diagnosing partial bus failures, vehicle porting, and verifying openRS-fw
configuration.

- Grid layout: CAN IDs as tiles, 4 columns, sorted by hex value
- Colour states: green (seen in last 5 s), yellow (stale > 5 s), red (expected
  but never seen), grey (unknown)
- Tap a tile: expand to show firstRawHex, lastRawHex, frameCount, fps, decoded
  field names
- Expected ID list: defined per VehicleProfile
- "Unknown IDs" section: IDs on bus but not in decoder
- Export: frame coverage report included in diagnostic ZIP

<details><summary>Implementation notes</summary>

- `DiagnosticLogger` already tracks per-ID data — this is a visualization layer
- Tile composable: `FrameTile.kt` — takes frameId, status enum, lastSeen
  timestamp
- Status: compare lastSeen to `System.currentTimeMillis()` in a
  StateFlow-collected composable
- FPS per ID: add frameCount and derive fps over a rolling 1 s window

**New files:** `ui/FrameCoverageGrid.kt` (add to `DiagPage.kt`)

</details>

### 8.3 Crash Telemetry Capture

**Shipped in v2.2.4** — [#97](https://github.com/klexical/openRS_/issues/97)

A ring buffer of the last 100 VehicleState snapshots (~5 s at 20 Hz) captured
to a crash report file on uncaught exception. Correlates app crashes with
specific car states: high IAT, specific drive mode, AWD split at limit.

- Ring buffer: `VehicleStateRingBuffer` — fixed 100-slot circular array
- `UncaughtExceptionHandler`: on crash, flush ring buffer + current DTC list +
  adapter state to `crash_report.json`
- Crash report stored in internal storage, included in next diagnostic ZIP export
- Privacy: only vehicle telemetry — no user data, no GPS coordinates
- DIAG tab: "Last crash: [date] — [summary of car state]"

<details><summary>Implementation notes</summary>

- `Thread.setDefaultUncaughtExceptionHandler` in `OpenRSDashApp.kt`
- `VehicleStateRingBuffer.kt`: simple array + head pointer, thread-safe with
  synchronized or AtomicInteger
- Serialize with Gson/Kotlinx — VehicleState is already a data class
- After writing crash file, re-throw to let Android's default handler complete
  the crash

**New files:** `data/VehicleStateRingBuffer.kt`,
`diagnostics/CrashReporter.kt`

</details>

### 8.4 DBC File Export

**Medium-term · Medium**

Export the empirically verified Focus RS CAN signals as a proper `.dbc` file —
usable in SavvyCAN, Kayak, python-can, and any DBC-aware tool. The Focus RS DBC
is not publicly available in verified form; this fills a real gap for the
community.

- Static DBC definition: Kotlin data structure mirroring all verified signals
  with bit positions, scaling, units, and source
- `DbcExporter.kt` writes standard Vector `.dbc` format from the definition
- Export alongside existing diagnostic ZIP
- Include empirically-discovered signals (0x420 track mode) with clear DBC
  comments
- Version the DBC alongside the app: `RS_HS_openRS_v2.2.dbc`

<details><summary>Implementation notes</summary>

- `.dbc` format: standard Vector/PEAK format — well-documented
- Signal example:
  `BO_ 144 RPM_Frame: 8 Vector__XXX SG_ RPM : 16|16@1+ (0.25,0) [0|8000] "rpm" Vector__XXX`
- DBC definition and `CanDecoder.kt` are co-located — any decoder change should
  update both

**New files:** `diagnostics/DbcExporter.kt`, `assets/RS_HS_openRS.dbc`

</details>

### 8.5 WiCAN Pro GPS Parameter Heatmap

**Long-term · High**

With WiCAN Pro GPS, every VehicleState snapshot has a lat/lon. Painting the
track trace with a colour gradient keyed to any parameter — boost, KR, AWD
split, oil temp, lateral G — reveals where on track specific conditions occur.
Turns the trip map into a genuine debrief tool.

- Heatmap channel selector: dropdown above the trip map — pick any VehicleState
  numeric field
- Colour gradient: blue (low) -> green (mid) -> red (high)
- Track trace: polyline coloured per GPS segment based on parameter value
- Snap to GPS: correlate VehicleState timestamps with GPS timestamps
- Multi-lap average: option to average across all laps (reduces GPS noise)
- Export: heatmap data as GeoJSON with parameter value as a feature property

<details><summary>Implementation notes</summary>

- `TripPoint` already has lat/lon + timestamp — extend with a
  `Map<String, Float>` of parameter snapshots
- Heatmap renderer: custom Compose Canvas overlay on the OSM tile map
- GPS correlation: VehicleState at ~20 Hz, GPS at ~1 Hz (WiCAN Pro) —
  interpolate GPS between fixes
- Memory: 30-min session at 20 Hz = 36,000 points — manageable, use lazy
  rendering

**Data sources / CAN signals:**

| Signal | Source |
|--------|--------|
| WiCAN Pro GPS | NMEA stream via USB GNSS dongle |
| All passive CAN fields | Available for heatmap colouring |

**New files:** `data/TripPoint.kt` (extend),
`ui/ParameterHeatmapOverlay.kt`

</details>

---

## Additional Ideas (from overview table)

These items appear in the priority matrix but are not detailed in a dedicated
section. They are captured here for completeness.

| Feature | Horizon | Priority | Notes |
|---------|---------|----------|-------|
| Brake pressure calibration | Near | Medium | ADC mapping UI for raw 0-4095 from 0x252 |
| Tire temp PIDs (0x2823-26) | Near | Medium | Experimental BCM PIDs — needs hardware verification |
| Predictive alerts | Medium | Medium | Rules engine on live data (e.g. "oil temp rising fast") |
| Session replay | Medium | Medium | SLCAN playback transport through existing decoder |
| Launch control stats | Long | Medium | Per-launch table + correlation |
| Tire degradation proxy | Long | Low | Wheel speed delta over session |
| UDS Fast Rate Session (DDDI) | Long | High | ~100 Hz OBD via 0x2C for ETC/WGDC/KR |
| openRS\_ cloud sessions | Long | Medium | Anonymous telemetry leaderboard |
| GPS sector heatmap per param | Long | High | Boost/KR/AWD mapped to track position |

---

*When a feature moves to active development, create a GitHub Issue with the
appropriate labels (`enhancement`, priority, area) and link it back to this
document. Update the Status column in the overview table to `in-progress`.*

*Last updated: March 2026 — v2.2.x baseline*
