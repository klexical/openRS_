<div align="center">
  <img src="assets/images/openrs-wordmark.svg" width="320" alt="openRS_"><br/>
  <sub>Open-source real-time telemetry dashboard for the Ford Focus RS MK3</sub>
</div>

<p align="center">
  <a href="https://klexical.github.io/openRS_/">🔷 Sapphire Dashboard</a> •
  <a href="https://klexical.github.io/openRS_/emulator/">🚀 Live Emulator</a> •
  <a href="#screenshots">Screenshots</a> •
  <a href="#features">Features</a> •
  <a href="#hardware">Hardware</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#roadmap">Roadmap</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-2.2.6-blue" alt="Version">
  <img src="https://img.shields.io/badge/platform-Android-brightgreen?logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?logo=jetpackcompose" alt="Compose">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="License">
  <img src="https://img.shields.io/badge/CAN-HS--CAN_500k-orange" alt="CAN Bus">
</p>

<p align="center">
  <b>"Built for the car Ford should have given us an app for."</b>
</p>

---

## What is openRS_?

**openRS_** is a native Android app that turns your phone into a full telemetry dashboard for the Ford Focus RS MK3. It connects wirelessly to a [MeatPi WiCAN](https://www.mouser.com/ProductDetail/MeatPi/WICAN-USB-C3?qs=rQFj71Wb1eVDX2eEy0FC7A%3D%3D) adapter over Wi-Fi and passively monitors the full CAN bus at ~2100 fps — decoding every parameter the car broadcasts in real time.

Unlike generic OBD apps, openRS_ is purpose-built for the Focus RS. It understands the GKN Twinster AWD system, polls TPMS tire pressures and temperatures from the BCM, decodes Ford-specific parameters across HS-CAN and MS-CAN, and presents everything in a dark, glanceable interface tuned for track days. A bundled **FORScan PID catalog** covers 1,149 PIDs across 8 ECU modules (PCM, OBDII, BCM, ABS, AWD, HVAC, IPC, PSCM) — and new parameters can be added from the catalog via JSON without touching code.

> **Try it now:** [klexical.github.io/openRS_/emulator](https://klexical.github.io/openRS_/emulator/) — live browser emulator with animated demo data, no hardware required.
>
> **Analyse your data:** [klexical.github.io/openRS_](https://klexical.github.io/openRS_/) — **Sapphire**, the post-session analytics dashboard. Drop an export ZIP to explore charts, CAN data, and diagnostics in your browser.

---

## Screenshots

<div align="center">
  <img src="assets/screenshots/dash.png" width="160" alt="DASH tab">
  <img src="assets/screenshots/power.png" width="160" alt="POWER tab">
  <img src="assets/screenshots/chassis.png" width="160" alt="CHASSIS tab">
  <img src="assets/screenshots/temps.png" width="160" alt="TEMPS tab">
  <img src="assets/screenshots/diag.png" width="160" alt="DIAG tab">
  <img src="assets/screenshots/more.png" width="160" alt="MORE tab">
</div>

<p align="center">
  <sub>DASH &nbsp;·&nbsp; POWER &nbsp;·&nbsp; CHASSIS &nbsp;·&nbsp; TEMPS &nbsp;·&nbsp; DIAG &nbsp;·&nbsp; MORE</sub>
</p>

---

## Features

### 6 Tabs

| Screen | Description |
|--------|-------------|
| **DASH** | Hero boost/RPM/speed gauges (with session peak values), 0-60 / 0-100 performance timer, real-time fuel economy (idle L/hr, instant/average MPG or L/100km, DTE), throttle/brake/clutch/fuel/battery, Temps Quick (oil, coolant, intake, oil life), G-forces (lat, lon, torque), animated AWD split bar, IPC warning lamp banner (CEL, ABS, BRK, CHRG, OIL, TEMP) |
| **POWER** | AFR hero cards (actual/desired/lambda), commanded AFR (CMD AFR), ETC actual/desired, WGDC, TIP, HP fuel rail PSI, low-pressure fuel rail (LP FUEL), timing, engine load, OAR, per-cylinder knock correction (KR C1–C4, colour-coded), VCT intake/exhaust, fuel trims |
| **CHASSIS** | AWD detail (4 wheel speeds, wheel rotation counts, torque bar, F/R delta, L/R delta, rear bias, clutch temps L/R, trans oil temp), G-force + yaw + steering with peak reset, TPMS Focus RS wireframe (pressure + temp, 3-tier colour-coded Low/Warn/High thresholds), pressure spread warning (⚠ PRESSURE IMBALANCE when spread ≥ 4 PSI), temperature range legend |
| **TEMPS** | Animated Ready-to-Race banner, 14 temperature cards each with a colour indicator bar (oil, coolant, intake, ambient, RDU, PTU, charge air, manifold charge, catalytic, cabin, battery, clutch L, clutch R, trans oil) |
| **DIAG** | DTC Scanner (full-module scan, count badges, freeze-frame, clear), DID Prober (scan any ECU for valid Mode 22 DIDs), PID Browser (1,149 FORScan PIDs across 8 modules, searchable), frame inventory with per-ID change tracking, SLCAN raw log, one-tap ZIP export (SavvyCAN/Kayak compatible) |
| **MORE** | Drive mode (N/S/T/D, tap-to-change with openRS_ firmware), ESC status, firmware version display, firmware-gated features (Launch Control, Auto S/S Kill), Module Status (RDU/PDC/FENG live OBD), passive VIN decode (CAN 0x40A), connection & snapshot, Trip GPS recording |

### Ready-to-Race Thresholds

The TEMPS tab shows a warming-up / race-ready banner based on **oil**, **coolant**, **RDU**, **PTU**, and **clutch** temperatures. Thresholds are preset-based — configurable via Street / Track / Race in Settings:

| Sensor | Street | Track | Race |
|--------|--------|-------|------|
| Engine Oil | ≥ 70 °C | ≥ 80 °C | ≥ 85 °C |
| Coolant | ≥ 70 °C | ≥ 75 °C | ≥ 80 °C |
| RDU | ≥ 30 °C | ≥ 30 °C | ≥ 30 °C |
| PTU | ≥ 40 °C | ≥ 40 °C | ≥ 40 °C |

The banner shows which sensors are still below threshold with live °C values. A value of −99 °C (not yet received) is treated as passing to avoid blocking warm cars on reconnect. Clutch temperatures are also factored into the race-ready check (cold threshold: < 25 °C).

### Settings

| Setting | Options | Default |
|---------|---------|---------|
| Speed unit | MPH / KPH | MPH |
| Temperature | °F / °C | °F |
| Boost pressure | PSI / BAR / kPa | PSI |
| Tire pressure | PSI / BAR | PSI |
| Low tire threshold | PSI (any value) | 30 PSI |
| Threshold preset | Street / Track / Race | Street |
| Theme | RS paint colours (Nitrous Blue, Frozen White, etc.) | Nitrous Blue |
| Keep screen on | on / off | on |
| Auto-reconnect | on / off | on |
| Reconnect interval | seconds | 10 s |
| Adapter | WiCAN / MeatPi Pro | WiCAN |
| MicroSD logging reminder | on / off | off (MeatPi Pro only) |
| Max saved ZIP exports | count | 5 |
| Odometer display | Miles / km | Miles |
| Edge shift light | on / off | off |
| Shift light colour | Accent / White / Progressive | Accent |
| Shift light intensity | Low / Medium / High | High |
| Shift light RPM | RPM threshold | 6800 |
| Tire warn threshold | PSI | 32 PSI |
| Tire high threshold | PSI | 40 PSI |

<details>
<summary><strong>Live CAN Parameters — WebSocket SLCAN (passive at full bus speed)</strong></summary>

All data is received passively from the CAN bus via WebSocket SLCAN at ~2100 fps. No OBD polling windows or header switching required for primary gauges.

| CAN ID | Parameters | Source |
|--------|-----------|--------|
| 0x010 | Steering wheel angle (°) with direction sign | RS_HS.dbc SASMmsg01 |
| 0x070 | Torque at transmission (Nm) | RS_HS.dbc |
| 0x076 | Throttle % | RS_HS.dbc |
| 0x080 | Accelerator pedal %, brake pedal, reverse | RS_HS.dbc |
| 0x090 | RPM, barometric pressure | RS_HS.dbc |
| 0x0C8 | Gauge brightness, e-brake, **ignition status** | RS_HS.dbc |
| 0x0F8 | Engine oil temp, boost pressure (gauge + baro), PTU temp | RS_HS.dbc PCMmsg07 |
| 0x130 | Vehicle speed kph | RS_HS.dbc |
| 0x138 | Clutch pedal position (%) — 10-bit Motorola × 0.1% | RS_HS.dbc PCMmsg10 |
| 0x160 | Longitudinal G-force | RS_HS.dbc |
| 0x180 | Lateral G-force, **yaw rate**, **vertical G** | RS_HS.dbc ABSmsg02 |
| 0x1E0 | Wheel rotation counts (4 × 8-bit rolling FL/FR/RL/RR) + average front wheel speed | RS_HS.dbc ABSmsg06 |
| 0x190 | 4-corner wheel speeds (15-bit Motorola × 0.011343 km/h) | RS_HS.dbc ABSmsg03 |
| 0x1A4 | Ambient temperature (MS-CAN bridged) | community research |
| 0x1B0 | Drive mode (Normal/Sport+Track/Drift) — byte 6 upper nibble, steady-state frames only (byte 4 == 0). Combined with 0x420 to resolve Sport vs Track. | RS_HS.dbc AWDmsg01 |
| 0x1C0 | ESC mode status (On/Off/Sport/**Launch**) | RS_HS.dbc |
| 0x420 | Track mode indicator, **launch control status** — byte 6: `0x10` = Normal/Sport, `0x11` = Track; bit 50 = LC active (~600 ms) | RS_HS.dbc + empirical |
| 0x252 | Brake pressure (0–100% normalised, raw 0–4095 ADC counts) | RS_HS.dbc ABSmsg10 |
| 0x2C0 | AWD L/R rear torque (Nm) | RS_HS.dbc |
| 0x2F0 | Coolant temp, Intake Air Temp (IAT) | RS_HS.dbc PCMmsg16 |
| 0x340 | Ambient temperature only (byte 7 signed × 0.25 °C) — **not** TPMS | RS_HS.dbc PCMmsg17 |
| 0x360 | Odometer, **engine status** — odo: bytes [3:5] BE, 24-bit, 1 km/bit (~5 Hz); engine: byte 0 (0=Idle, 2=Off, 183=Running, 186=Kill, 191=RecentStart, 196=Warmup) | RS_HS.dbc + community [#102](https://github.com/klexical/openRS_/discussions/102) |
| 0x380 | Fuel level % (FuelLevelFiltered — Motorola 10-bit, factor 0.4 %) | RS_HS.dbc PCMmsg30 |
| 0x40A | Multiplexed VIN decode (3 pages × 6 bytes → 17-char VIN) + odometer | community (@adamsouthern) |

> **Note:** `0x230` (gear position) and `0x3C0` (battery voltage) do not broadcast on this vehicle. Battery voltage is polled via OBD. Gear display has been removed.

**Polled via OBD Mode 22 (periodic, low-frequency):**

| ECU | Request | Response | PIDs / Function | Interval |
|-----|---------|----------|-----------------|----------|
| PCM | 0x7E0 | 0x7E8 | ETC actual (0x093C), ETC desired (0x091A), WGDC (0x0462), **KR cyl 1–4** (0x03EC–0x03EF), OAR (0x03E8), Charge Air Temp (0x0461), Catalyst Temp (0xF43C), AFR actual (0xF434), AFR desired (0xF444), TIP actual (0x033E), TIP desired (0x0466), VCT intake (0x0318), VCT exhaust (0x0319), Oil Life (0x054B), HP Fuel Rail (0xF422), Fuel Level (0xF42F), Battery Voltage (0x0304) | 30 s |
| BCM | 0x726 | 0x72E | Battery SOC (0x4028), Battery temp (0x4029), Cabin temp (0xDD04), **TPMS pressure LF/RF/LR/RR** (0x2813–0x2816) `(((256*A)+B)/3 + 22/3) * 0.145 PSI`, **TPMS last sensor** (0x280B) `temp = raw - 40 °C, pressure = (A*256+B)/20 PSI` (multi-frame ISO-TP, matched by sensor ID) | 30 s |
| BCM (once) | 0x726 | 0x72E | Odometer (0xDD01) — extended session, **TPMS sensor IDs** (0x280F LF, 0x2810 RF, 0x2811 RR, 0x2812 LR) — 4-byte IDs for 0x280B matching | once |
| AWD module | 0x703 | 0x70B | RDU oil temp (0x1E8A), **clutch temp L/R** (0x1E8B/0x1E8C), trans oil temp (0x1E80), **req torque L/R** (0x1E90/0x1E91), demanded pressure (0x1E92), pump current (0x1E93) — all `A − 40 °C` or raw | 60 s |
| HVAC (candidate) | 0x733 | 0x73B | Blower %, interior temp, discharge air temp, blend doors L/R, defrost door — response handler wired, **DIDs unconfirmed** (use DID prober) | — |
| IPC (candidate) | 0x720 | 0x728 | Warning lamps (MIL/CEL, ABS, brake, charge, oil pressure, high temp) — response handler wired, **DIDs unconfirmed** (use DID prober) | — |

**FORScan PID Catalog — data-driven decode:** A bundled JSON catalog covers **1,149 PIDs across 8 ECU modules** (PCM, OBDII, BCM, ABS, AWD, HVAC, IPC, PSCM). `PidRegistry` evaluates formula expressions from the catalog at runtime — new PIDs can be added via JSON without modifying Kotlin code. Decoded values accumulate in `VehicleState.genericValues`.

</details>

---

## Hardware

### Required

| Component | Details |
|-----------|---------|
| **MeatPi WiCAN** | [Mouser](https://www.mouser.com/ProductDetail/MeatPi/WICAN-USB-C3?qs=rQFj71Wb1eVDX2eEy0FC7A%3D%3D) — Wi-Fi ELM327-compatible OBD-II adapter |
| **MeatPi WiCAN Pro** (optional) | [MeatPi](https://www.meatpi.com/) — Wi-Fi + GPS + MicroSD, raw TCP SLCAN |
| **Ford Focus RS MK3** | 2016–2018 (LZ platform, EcoBoost 2.3L) |
| **Android phone** | Android 9+ (API 28) with Wi-Fi |

### Setup

1. Plug the WiCAN into the OBD-II port (under the steering column)
2. Connect your phone to the WiCAN's Wi-Fi network:

| Firmware | SSID | Password |
|----------|------|----------|
| **Stock WiCAN** | `WiCAN_XXXXXX` | `@meatpi#` |
| **openrs-fw** | `openRS_XXXXXX` | `openrs_2026` |

3. Install openRS_ and tap the connection dot in the header

> **Stock firmware** works out of the box — openRS_ connects via WebSocket SLCAN (`ws://192.168.80.1:80/ws`) with no configuration changes needed. For full functionality (drive mode write, Launch Control, Auto S/S Kill, ESC control), flash **openrs-fw** to the WiCAN. See the [firmware update guide](android/docs/firmware-update.md) for instructions.

> **WiCAN Pro users:** The Pro defaults to ELM327 mode. You **must** open the Pro's web UI (`http://192.168.0.10`), set the protocol to **SLCAN**, CAN speed to **500 kbps**, and TCP port to **35000**, then reboot. In the openRS_ app, switch the adapter to **MeatPi Pro** in Settings. See the [hardware setup guide](android/docs/hardware-setup.md#wican-pro-adapter) for full instructions.

---

## Quick Start

### Prerequisites

- Android Studio Ladybug (2024.2) or newer
- JDK 17+
- Android SDK 35

### Build

```bash
git clone https://github.com/klexical/openRS_.git
cd openRS_/android
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/openRS_v2.2.6.apk
# (Requires keystore — see android/docs/signing-setup.md)
```

### Browser Emulator (no hardware required)

Open `android/browser-emulator/index.html` in any browser, or visit the live version:

**[klexical.github.io/openRS_/emulator](https://klexical.github.io/openRS_/emulator/)**

- All tabs animate with simulated Focus RS data (RPM, boost, speed, AWD, temps, TPMS)
- MORE tab shows drive mode, ESC, features, diagnostics
- ⚙ Settings button demonstrates the settings dialog

### Sapphire — Post-Session Analytics

Drop an export ZIP from the app into the web dashboard to explore your session data:

**[klexical.github.io/openRS_](https://klexical.github.io/openRS_/)**

- Dashboard KPIs, session peaks, drive mode breakdown
- 8 time-series charts (RPM, boost, speed, temps, G-force, fuel, wheel speeds, AWD torque)
- CAN frame inventory, session events, DID probe results, decode trace
- Persistent session library in your browser (IndexedDB)

---

## Architecture

<details>
<summary><strong>System diagram</strong></summary>

```
┌──────────────────────────────────────────────────────────────────────┐
│  Phone UI — Jetpack Compose + Material 3                             │
│  ┌──────┬───────┬─────────┬───────┬──────┬──────┐                   │
│  │ DASH │ POWER │ CHASSIS │ TEMPS │ DIAG │ MORE │                   │
│  └──────┴───────┴─────────┴───────┴──────┴──────┘                   │
│  Header: logo · connection pill · TRIP · ⚙                            │
│  F1 Telemetry Strip: MODE · ESC · CONN/FPS · IGN · E-BRK             │
├──────────────────────────────────────────────────────────────────────┤
│  UserPrefsStore (StateFlow) — units, thresholds, reconnect settings  │
├──────────────────────────────────────────────────────────────────────┤
│                  VehicleState (StateFlow)                            │
│    Immutable data class · 95+ fields · genericValues map · RTR       │
├──────────────────────────────────────────────────────────────────────┤
│                  CanDataService (Background)                         │
│   Decodes CAN → VehicleState → notifies UI                           │
│   Hooks DiagnosticLogger, TripRecorder, WeatherRepository            │
├──────────────────────┬───────────────────────────────────────────────┤
│  CanDecoder          │  DiagnosticLogger / Exporter / DtcScanner     │
│  25 CAN frame IDs    │  Per-ID first/last/Δ tracking                 │
│  RS_HS.dbc-verified  │  Periodic samples (30 s), SLCAN candump log   │
│  Motorola extraction │  Validation engine, ZIP export via FileProvider│
├──────────────────────┼───────────────────────────────────────────────┤
│  CrashReporter       │  FirmwareApi (REST POST /api/frs)             │
│  100-snapshot ring   │  Drive mode, ESC, WiFi-forced socket          │
├──────────────────────┴───────────────────────────────────────────────┤
│  PidRegistry — data-driven decode from FORScan catalog (JSON)        │
│  1,149 PIDs · 8 modules · formula evaluator · genericValues output   │
├──────────────────────────────────────────────────────────────────────┤
│  ObdConstants / ObdResponseParser / SlcanParser / IsoTpBuffer        │
├──────────────────────────────────────────────────────────────────────┤
│  WiCanConnection (WebSocket)  │  MeatPiConnection (TCP)              │
│  ws://192.168.80.1:80/ws      │  tcp://192.168.0.10:35000            │
│  SLCAN: C / S6 / O · ~2100 fps│  Raw SLCAN + OBD polling            │
│  Firmware probe (OPENRS?)     │  sendRawQuery() for DID prober       │
├──────────────────────────────────────────────────────────────────────┤
│  PCM (0x7E0/30s) · BCM (0x726/30s) · AWD (0x703/60s)                │
│  HVAC (0x733, candidate) · IPC (0x720, candidate)                    │
├──────────────────────┬───────────────────────────────────────────────┤
│  MeatPi WiCAN USB-C3 │  MeatPi WiCAN Pro (optional)                 │
│  Wi-Fi AP · WS :80/ws│  Wi-Fi AP · TCP :35000 · GPS · MicroSD       │
├──────────────────────┴───────────────────────────────────────────────┤
│  HS-CAN 500k         │  MS-CAN 125k (bridged via GWM)               │
│  0x010–0x3C0 frames  │  Ambient 0x340/0x1A4 (GWM bridged)            │
└──────────────────────┴───────────────────────────────────────────────┘
```

</details>

<details>
<summary><strong>Key design decisions</strong></summary>

**Why WebSocket SLCAN instead of ELM327 TCP?** ELM327's `ATMA` command is not fully implemented in WiCAN firmware. WebSocket SLCAN bypasses ELM327 entirely — the app does a manual HTTP 101 Upgrade handshake, sends `C` / `S6` / `O` (close/500kbps/open), and receives raw SLCAN frames. This delivers the full HS-CAN bus at ~2100 fps vs ~12 fps with polled OBD.

**How does TPMS work?** Tire pressures are polled from the BCM via Mode 22 (PIDs 0x2813–0x2816) every 30 seconds using the formula `(((256*A)+B)/3 + 22/3) * 0.145 PSI`. Tire temperatures use a two-step approach: on connect, the app polls sensor IDs from PIDs 0x280F–0x2812 (4-byte ID per tire position). Then every 30s it polls PID 0x280B ("last received TPMS sensor") — a 12-byte multi-frame ISO-TP response containing sensor ID, pressure (`(A*256+B)/20` PSI), and temperature (`raw − 40` °C). The sensor ID is matched against the stored map to assign the temperature to the correct tire. Per-tire temperature PIDs 0x2823–0x2826 were confirmed unsupported by the BCM (`7F 22 31`).

**How does firmware detection work?** After SLCAN initialisation, the app sends `OPENRS?\r`. openRS_ firmware responds with `OPENRS:<version>`; stock WiCAN ignores it. Every incoming CAN frame for the first **3 seconds** is scanned for the probe response — this time-based window ensures the probe reply is not missed even on high-throughput buses (~1700 fps). After 3 seconds without a response, firmware latches as "WiCAN stock" for the session. MORE tab feature buttons unlock when openRS_ firmware is confirmed.

**How does the FORScan PID catalog work?** `PidRegistry` lazy-loads `forscan_modules.json` at first use and indexes 1,149 PIDs by `(ecuResponseId, did)` tuple. Each PID entry carries a formula expression string (e.g. `"signed(A*256+B)/-512.0"`). A custom recursive-descent parser evaluates the formula at runtime using bytes from the OBD response. All four OBD parsers (PCM, AWD, HVAC, IPC) fall through to `PidRegistry` for unrecognised DIDs, storing results in `VehicleState.genericValues`. New PIDs can be onboarded via JSON without modifying Kotlin code.

**How does the DID prober work?** `DidProberSection` (DIAG tab) sends Mode 22 UDS queries across module-specific DID ranges using `sendRawQuery()` — a new infrastructure method on both `WiCanConnection` and `MeatPiConnection`. Responses are classified as FOUND (0x62 positive), NRC (0x7F negative, NRC code shown), or TIMEOUT. A live progress bar, counters, and expandable result list with raw hex data make it easy to discover new ECU parameters.

**How does the diagnostic system work?** `DiagnosticLogger` (singleton) accumulates three layers throughout the session: (1) a per-ID frame inventory with `firstRawHex`, `lastRawHex`, a `hasChanged` flag, and up to 10 periodic raw-hex snapshots per ID sampled every 30 s; (2) a rolling 10 000-entry decode trace; (3) a real-time SLCAN log in standard candump format. On export, `DiagnosticExporter` bundles all three artefacts into a ZIP via FileProvider, compatible with SavvyCAN, Kayak, and python-can.

</details>

---

## Technical Reference

<details>
<summary><strong>Project structure</strong></summary>

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
│   │   └── forscan_modules.json          # Bundled FORScan catalog (1,149 PIDs, 8 modules)
│   ├── java/com/openrs/dash/
│   │   ├── OpenRSDashApp.kt              # Application singleton + isOpenRsFirmware flag
│   │   ├── can/
│   │   │   ├── AdapterState.kt           # Shared connection state sealed class
│   │   │   ├── CanDecoder.kt             # 25 CAN frame decoders (RS_HS.dbc-verified)
│   │   │   ├── FirmwareApi.kt            # REST POST to openRS_ firmware /api/frs
│   │   │   ├── IsoTpBuffer.kt            # ISO-TP SF/FF/CF reassembly
│   │   │   ├── MeatPiConnection.kt       # MeatPi Pro raw TCP SLCAN + OBD polling
│   │   │   ├── ObdConstants.kt           # Shared OBD query strings + CAN IDs + timing
│   │   │   ├── ObdResponseParser.kt      # Shared OBD Mode 22 response parsers
│   │   │   ├── PidRegistry.kt            # Data-driven PID decode via JSON catalog + formula evaluator
│   │   │   ├── SlcanParser.kt            # Shared SLCAN frame parser
│   │   │   └── WiCanConnection.kt        # WiCAN WebSocket SLCAN + firmware probe
│   │   ├── data/
│   │   │   ├── DtcModuleSpec.kt          # ECU module descriptor for DTC operations
│   │   │   ├── DtcResult.kt              # DTC result + status enum
│   │   │   ├── ForscanCatalog.kt         # Data classes for JSON catalog deserialization
│   │   │   ├── TripPoint.kt              # GPS waypoint with telemetry
│   │   │   ├── TripState.kt              # Trip accumulator
│   │   │   └── VehicleState.kt           # Immutable state (95+ fields, genericValues, peaks, RTR)
│   │   ├── diagnostics/
│   │   │   ├── CrashReporter.kt          # UncaughtExceptionHandler + telemetry persistence
│   │   │   ├── CrashTelemetryBuffer.kt   # 100-snapshot VehicleState ring buffer
│   │   │   ├── DiagnosticExporter.kt     # ZIP builder + FileProvider share + CSV
│   │   │   ├── DiagnosticLogger.kt       # Session-scoped collector + SLCAN log
│   │   │   ├── DtcDatabase.kt            # Bundled 873-code Ford DTC lookup
│   │   │   └── DtcScanner.kt             # DTC scan/clear orchestrator
│   │   ├── service/
│   │   │   ├── CanDataService.kt         # Background service + DiagnosticLogger hooks
│   │   │   ├── TripRecorder.kt           # FusedLocationProviderClient @ 1 Hz GPS
│   │   │   └── WeatherRepository.kt      # OpenWeatherMap API integration
│   │   └── ui/
│   │       ├── MainActivity.kt           # Compose entry (6 tabs + header)
│   │       ├── DashPage.kt               # DASH tab
│   │       ├── PowerPage.kt              # POWER tab
│   │       ├── ChassisPage.kt            # CHASSIS tab
│   │       ├── TempsPage.kt              # TEMPS tab
│   │       ├── DiagPage.kt               # DIAG tab (DTC scanner + DID prober + PID browser)
│   │       ├── DidProberSection.kt       # DID prober composable
│   │       ├── PidBrowserSection.kt      # FORScan PID browser composable
│   │       ├── MorePage.kt               # MORE tab
│   │       ├── Theme.kt                  # Design tokens, fonts, colors
│   │       ├── Components.kt             # Shared composables
│   │       ├── AppSettings.kt            # SharedPreferences wrapper
│   │       ├── UserPrefs.kt              # Observable preferences (StateFlow)
│   │       ├── SettingsSheet.kt          # Settings dialog
│   │       ├── WhatsNewDialog.kt         # Version changelog dialog
│   │       └── anim/
│   │           └── EdgeShiftLight.kt     # Peripheral shift light overlay
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

</details>

<details>
<summary><strong>Brand tokens</strong></summary>

| Token | Hex | Usage |
|-------|-----|-------|
| **Nitrous Blue** | `#0091EA` | Accent colour — gauges, highlights, active states, "RS" in logo |
| **Frost White** | `#E8F4FF` | Primary text — labels, readouts, "open" and "_" in logo |
| **Bg** | `#05070A` | Background |
| **Surface** | `#0A0D12` | Cards, tab bar |
| **Surface 2** | `#0F141C` | Inset cards, hero RPM gauge |
| **Surface 3** | `#141B26` | Elevated surfaces |
| **Dim** | `#547A96` | Muted text (WCAG AA ≥ 4.5:1) |
| **Mid** | `#7A9AB8` | Medium emphasis text |
| **Border** | `#162030` | Card/section borders |
| **Ok** | `#00FF88` | Neon green — good/ready |
| **Warn** | `#FFCC00` | Gold — attention/warm |
| **Orange** | `#FF4D00` | Orange-red — hot/aggressive |

**Fonts** (offline-embedded):
- **Orbitron** — hero gauge values (RPM, speed, boost)
- **JetBrains Mono** — secondary numeric readouts
- **Share Tech Mono** — raw data values and diagnostic output
- **Barlow Condensed** — all UI labels, section headers, and button text

</details>

---

## Full PID Reference

Complete decode formulas, byte-level breakdowns, and all Mode 22 PIDs: [`android/docs/pid-reference.md`](android/docs/pid-reference.md)

---

## Roadmap

### Planned

- [ ] Phase 8.5 — Polish and sensor gaps: BLE transport in app, ESC write fix ([#125](https://github.com/klexical/openRS_/issues/125)), brake pressure calibration (v2.3.x)
- [ ] Phase 9 — Track day intelligence: lap timer with geofence, track map overlay enhancements, trip comparison (v2.4.x)
- [ ] Phase 10 — Hardware expansion: MeatPi Pro GPS integration, MS-CAN support (v2.5.x)
- [ ] Phase 11 — High-frequency telemetry: UDS Fast Rate Session via DDDI 0x2C (~100 Hz) (v3.x)

### Future / Exploratory

- Android Auto — official AndroidX Car App, unofficial aauto-sdk, or hybrid (see `android/docs/android-auto-custom-ui-research.md`)
- Data streaming to external apps (RaceChrono, Harry's LapTimer) via local broadcast or content provider
- Video overlay export — combine trip data with dashcam footage

> **Full vision:** See [Feature Roadmap](docs/feature-roadmap.md) for 35+ planned features beyond the current release phases.

<details>
<summary><strong>Completed phases</strong></summary>

- [x] Phase 1 — CAN sniffing + basic OBD (v1.0)
- [x] Phase 2 — Hybrid ATMA+OBD (v2.0)
- [x] Phase 2.5 — TPMS+, AFR, ETC/TIP/WGDC, VCT, multi-ECU polling
- [x] Phase 2.6 — Nitrous Blue/Frost White theme, openRS_ branding, live browser emulator
- [x] Phase 2.7 — WebSocket SLCAN rewrite (~2100 fps), user settings, diagnostics export, firmware detection (fw-v1.1.0)
- [x] Phase 2.8 — DBC-verified signal corrections, BCM/AWD polling, IAT, ambient, wheel speeds, SLCAN raw log + per-ID sampling (v1.1.1–v1.1.5)
- [x] Phase 2.9 — TPMS formula fix, firmware detection timing fix, app version in logs (v1.1.6)
- [x] Phase 3 — Full UI redesign: 6-tab layout, new fonts, new CAN signals (steering, yaw, brake), PCM Mode 22 polling, updated RTR thresholds (v1.2.0)
- [x] Phase 4 — Trip recording: GPS trip page with OSM map, weather overlay, peak markers, live HUD, trip summary (v2.1.0)
- [x] Phase 5 — UI architecture split, per-tab composables, share trip export (v2.2.0)
- [x] Phase 6 — DTC scanning: 873-code Ford DTC database, full-module scan + clear via UDS 0x19/0x14 (v2.2.1)
- [x] Phase 7 — Data export + MeatPi Pro: trip ZIP (GPX/CSV/TXT), diagnostics ZIP, raw TCP SLCAN adapter support (v2.2.1)
- [x] Phase 7.5 — Sensor data + polish: GPS permission fix, Module Status/LC/ASS live OBD, full diagnostic export (~24 new fields), SLCAN OBD frame capture, code review fixes (v2.2.3)
- [x] Phase 8.0 — Car test fixes + signal expansion: ESC decode fix, throttle fallback, battery voltage OBD, crash telemetry ring buffer, passive odometer (CAN 0x360), tap-to-change drive mode/ESC, RS MK3 theme colour correction, MeatPi default fix, free CAN signal extraction (vertical G, launch control, engine status, ignition status, ESC Launch mode), full repo audit hardening (v2.2.4)
- [x] Phase 8.1 — FORScan PID catalog (1,149 PIDs, 8 modules), data-driven decode (PidRegistry + formula evaluator), DID prober, PID browser, AWD expansion (clutch temps L/R, trans oil temp, req torques, demanded pressure, pump current), per-cylinder knock correction (KR C1–C4), HVAC/IPC scaffolding, warning lamp banner (v2.2.5)
- [x] Phase 8.2 — Drive mode reliability (pre-flight logging, auto-correction on overshoot), 0-60/0-100 performance timer, real-time fuel economy (idle/instant/average/DTE), clutch pedal (CAN 0x138), passive VIN decode (CAN 0x40A), wheel rotation counts (CAN 0x1E0), 3-tier TPMS thresholds, TPMS + temps in trip CSV exports, peripheral edge shift light, crash telemetry ring buffer (v2.2.6)

</details>

---

## Contributing

Pull requests welcome. See [CONTRIBUTING.md](android/CONTRIBUTING.md) for guidelines.

If you have a Focus RS and FORScan/OBDLink, we'd love help verifying:
- Brake pressure bar calibration (raw ADC 0–4095 from `0x252`, need known-pressure reference)
- HVAC / IPC ECU address and DID confirmation (use built-in DID prober)
- MS-CAN parameters (requires second adapter)

---

## License

MIT — see [LICENSE](LICENSE) for details.

---

## Acknowledgments

- **FORScan** — Ford enhanced PID discovery
- **MeatPi** — WiCAN hardware
- **Focus RS community** — Testing and feedback
