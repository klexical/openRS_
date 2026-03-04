<p align="center">
  <img src="docs/images/openrs-wordmark.svg" width="320" alt="openRS_"><br/>
  <sub>Open-source real-time telemetry dashboard for the Ford Focus RS MK3</sub>
</p>

<p align="center">
  <a href="https://klexical.github.io/openRS_">🚀 Live Emulator</a> •
  <a href="#features">Features</a> •
  <a href="#hardware">Hardware</a> •
  <a href="#quick-start">Quick Start</a> •
  <a href="#architecture">Architecture</a> •
  <a href="#android-auto">Android Auto</a> •
  <a href="#roadmap">Roadmap</a>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/platform-Android-brightgreen?logo=android" alt="Platform">
  <img src="https://img.shields.io/badge/Android_Auto-supported-blue?logo=google" alt="Android Auto">
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?logo=jetpackcompose" alt="Compose">
  <img src="https://img.shields.io/badge/license-MIT-green" alt="License">
  <img src="https://img.shields.io/badge/CAN-HS--CAN_500k-orange" alt="CAN Bus">
</p>

---

## What is openRS_?

**openRS_** is a native Android app that turns your phone or Android Auto head unit into a full telemetry dashboard for the Ford Focus RS MK3. It connects wirelessly to a [MeatPi WiCAN](https://www.mouser.com/ProductDetail/MeatPi/WICAN-USB-C3?qs=rQFj71Wb1eVDX2eEy0FC7A%3D%3D) adapter over Wi-Fi and passively monitors the full CAN bus at ~2100 fps — decoding every parameter the car broadcasts in real time.

Unlike generic OBD apps, openRS_ is purpose-built for the Focus RS. It understands the GKN Twinster AWD system, reads TPMS tire pressures directly from passive CAN frames, decodes Ford-specific parameters across HS-CAN and MS-CAN, and presents everything in a dark, glanceable interface tuned for track days.

> **Try it now:** [klexical.github.io/openRS_](https://klexical.github.io/openRS_) — live browser emulator with animated demo data, no hardware required.

---

## Brand

| Token | Hex | Usage |
|-------|-----|-------|
| **Nitrous Blue** | `#00AEEF` | Accent colour — gauges, highlights, active states, "RS" in logo |
| **Frost White** | `#F5F6F4` | Primary text — labels, readouts, "open" and "_" in logo |
| **Deep Black** | `#0A0A0A` | Background |
| **Surface** | `#1A1A1A` | Cards, gauge boxes |

---

## Features

### 8 Phone Tabs + 6 Android Auto Screens

| Screen | Description |
|--------|-------------|
| **DASH** | Primary gauges — boost, RPM, speed, gear, throttle, AWD split, temps, G-forces |
| **AWD** | GKN Twinster detail — L/R torque bars, 4-corner wheel speeds, RDU/PTU temps |
| **PERF** | G-force, yaw, steering, peak tracking with reset |
| **TEMPS** | All 8 temperature sensors with colour-coded warnings + Ready to Race indicator |
| **TUNE** | AFR actual/desired, ETC, TIP, WGDC, VCT, knock, fuel trims, timing advance |
| **TPMS** | 4-corner tire pressure with configurable low-pressure alerts (passive CAN 0x340) |
| **CTRL** | Live drive mode (N/S/T/D), ESC status, Launch Control + Auto S/S Kill (requires openRS_ fw) |
| **DIAG** | Session diagnostics — frame inventory, per-ID change tracking, periodic samples, SLCAN raw log, one-tap ZIP export (SavvyCAN/Kayak compatible) |

The Android Auto UI is **visually identical** to the phone app — same gauge boxes, info cells, torque bars, and temp gauges — using the openRS_ custom Activity approach.

### Live Parameters — WebSocket SLCAN (passive at full bus speed)

All data is received passively from the CAN bus via WebSocket SLCAN at ~2100 fps. No OBD polling windows or header switching required.

| CAN ID | Parameters | Source |
|--------|-----------|--------|
| 0x070 | Torque at transmission (Nm) | RS_HS.dbc |
| 0x076 | Throttle % | RS_HS.dbc |
| 0x080 | Accelerator pedal %, brake, reverse | RS_HS.dbc |
| 0x090 | RPM, barometric pressure | RS_HS.dbc |
| 0x0C8 | Gauge brightness, e-brake | RS_HS.dbc |
| 0x0F8 | Engine oil temp, boost pressure (gauge + baro), PTU temp | RS_HS.dbc PCMmsg07 |
| 0x130 | Vehicle speed kph | RS_HS.dbc |
| 0x160 | Longitudinal G-force | RS_HS.dbc |
| 0x180 | Lateral G-force | RS_HS.dbc |
| 0x190 | 4-corner wheel speeds (15-bit Motorola × 0.011343 km/h) | RS_HS.dbc ABSmsg03 |
| 0x1A4 | Ambient temperature (MS-CAN bridged) | DigiCluster |
| 0x1B0 | Drive mode (Normal/Sport/Track/Drift) | RS_HS.dbc |
| 0x1C0 | ESC mode status | RS_HS.dbc |
| 0x230 | Current gear | RS_HS.dbc |
| 0x2C0 | AWD L/R rear torque (Nm) | RS_HS.dbc |
| 0x2F0 | Coolant temp, Intake Air Temp (IAT) | RS_HS.dbc PCMmsg16 |
| 0x340 | TPMS — LF/RF/LR/RR (PSI, MS-CAN bridged via GWM) + Ambient temp (byte 7) | DigiCluster / RS_HS.dbc PCMmsg17 |
| 0x34A | Fuel level % | RS_HS.dbc |
| 0x3C0 | Battery voltage | RS_HS.dbc |

**Polled via OBD Mode 22 (periodic, low-frequency):**

| ECU | Address | PID | Parameter |
|-----|---------|-----|-----------|
| PCM | 0x7E0 | Mode 1 + Mode 22 | AFR, ETC, TIP, WGDC, VCT, knock, fuel trims, timing, charge air temp, oil life |
| BCM | 0x726 (resp. 0x72E) | 0xDD01, 0x4028, 0x4029, 0xDD04 | Odometer, battery SOC, battery temp, cabin temp |
| AWD module | 0x703 (resp. 0x70B) | 0x1E8A | RDU oil temperature |

### Settings

All display preferences are configurable and persist across restarts:

| Setting | Options | Default |
|---------|---------|---------|
| Speed unit | MPH / KPH | MPH |
| Temperature | °F / °C | °F |
| Boost pressure | PSI / BAR / kPa | PSI |
| Tire pressure | PSI / BAR | PSI |
| Low tire threshold | PSI (any value) | 30 PSI |
| Keep screen on | on / off | on |
| Auto-reconnect | on / off | on |
| Reconnect interval | seconds | 10s |

---

## Hardware

### Required

| Component | Details |
|-----------|---------|
| **MeatPi WiCAN** | [Mouser](https://www.mouser.com/ProductDetail/MeatPi/WICAN-USB-C3?qs=rQFj71Wb1eVDX2eEy0FC7A%3D%3D) — Wi-Fi ELM327-compatible OBD-II adapter |
| **Ford Focus RS MK3** | 2016–2018 (LZ platform, EcoBoost 2.3L) |
| **Android phone** | Android 9+ (API 28) with Wi-Fi |

### Optional

| Component | Details |
|-----------|---------|
| **Android Auto head unit** | Any AA-compatible unit (tested on Sync 3 APIM + AA Wireless) |

### Setup

1. Plug the WiCAN into the OBD-II port (under the steering column)
2. Connect your phone to the WiCAN's Wi-Fi network (`WiCAN_XXXXXX`, password `@meatpi#`)
3. Install openRS_ and tap **CONNECT**

> **Note:** openRS_ uses the WiCAN's **WebSocket endpoint** (`ws://192.168.80.1:80/ws`) in SLCAN mode — no ELM327 TCP mode required. The WebSocket interface is available in stock WiCAN firmware with no configuration change. To access openRS_ firmware features (Launch Control, Auto S/S Kill, CAN write), flash `openrs-fw` to the WiCAN.

---

## Quick Start

### Prerequisites

- Android Studio Ladybug (2024.2) or newer
- JDK 17+
- Android SDK 35

### Build

```bash
git clone https://github.com/klexical/openRS.git
cd openRS/android
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release-unsigned.apk
```

### Browser Emulator (no hardware required)

Open `browser-emulator/index.html` in any browser, or visit the live version:

**[klexical.github.io/openRS_](https://klexical.github.io/openRS_)**

- Toggle between **📱 Phone** and **🚗 Android Auto** views
- All gauges animate with simulated Focus RS data
- Navigate all 6 tabs / AA screens including AWD torque bars, TPMS, temps
- FPS counter shows live CAN frame rate (12 fps = 12 data packets/sec from WiCAN)

---

## Android Auto

### UI Approach

openRS_ targets a **custom Activity-based Android Auto UI** that renders the exact same gauge components as the phone app — full Nitrous Blue / Frost White theme, gauge boxes, torque bars, and tile grids — identical to what you see in the browser emulator.

> See [`docs/android-auto-custom-ui-research.md`](docs/android-auto-custom-ui-research.md) for a detailed comparison of the official Car App Library (template) approach vs the custom Activity approach used by projects like [aa-torque](https://github.com/agronick/aa-torque).

### AA Navigation

```
┌─────────────────────────────────────────────────────┐
│  openRS_   [SPORT]  [3]  [ESC On]     ● CONNECTED   │
├─────────────────────────────────────────────────────┤
│                                                     │
│              Screen content                         │
│     (identical layout to phone tab)                 │
│                                                     │
├─────────────────────────────────────────────────────┤
│   [AWD]      [PERF]      [TEMPS]      [MENU ☰]      │
└─────────────────────────────────────────────────────┘
```

The action strip gives direct access to AWD, PERF, and TEMPS. **MENU** opens a full list to reach TUNE and TPMS.

### Testing with DHU

```bash
# Enable AA developer mode, then:
adb shell dumpsys activity service com.google.android.projection.gearhead/.GearheadService
```

See [`docs/android-auto-setup.md`](docs/android-auto-setup.md) for full setup instructions.

---

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│  Android Auto (Custom Activity — identical to phone)            │
│  ┌──────┬──────┬──────┬──────┬──────┬──────┐                   │
│  │ DASH │ AWD  │ PERF │TEMPS │ TUNE │ TPMS │                   │
│  └──────┴──────┴──────┴──────┴──────┴──────┘                   │
├─────────────────────────────────────────────────────────────────┤
│  Phone UI (Jetpack Compose + Material3)                         │
│  ┌──────┬──────┬──────┬──────┬──────┬──────┬──────┬──────┐     │
│  │ DASH │ AWD  │ PERF │TEMPS │ TUNE │ TPMS │ CTRL │ DIAG │     │
│  └──────┴──────┴──────┴──────┴──────┴──────┴──────┴──────┘     │
├─────────────────────────────────────────────────────────────────┤
│  UserPrefsStore (StateFlow) — units, thresholds, reconnect      │
├─────────────────────────────────────────────────────────────────┤
│               VehicleState (StateFlow)                          │
│      Immutable data class • 80+ fields • peaks                  │
├─────────────────────────────────────────────────────────────────┤
│             CanDataService (Foreground)                         │
│  Decodes CAN → VehicleState → notifies UIs                      │
│  Hooks DiagnosticLogger (frame inventory, trace, FPS)           │
├──────────────────────────┬──────────────────────────────────────┤
│     CanDecoder           │   DiagnosticLogger / Exporter        │
│  19 CAN frame IDs        │   Frame inventory + first/last/Δ     │
│  RS_HS.dbc-verified      │   Periodic samples (30 s), SLCAN log │
│  formulas                │   Validation engine, ZIP export      │
├──────────────────────────┴──────────────────────────────────────┤
│            WiCanConnection (WebSocket)                          │
│  ws://192.168.80.1:80/ws │ SLCAN: C/S6/O │ ~2100 fps           │
│  Firmware probe (OPENRS?) │ Auto-reconnect │ WiFi gating        │
├─────────────────────────────────────────────────────────────────┤
│                MeatPi WiCAN USB-C3                              │
│           Wi-Fi AP • WebSocket :80/ws • SLCAN                   │
├──────────────────────────┬──────────────────────────────────────┤
│     HS-CAN 500k          │    MS-CAN 125k (bridged via GWM)     │
│  0x080–0x3C0 frames      │  TPMS 0x340, Ambient 0x1A4          │
└──────────────────────────┴──────────────────────────────────────┘
```

### Key Design Decisions

**Why WebSocket SLCAN instead of ELM327 TCP?** ELM327's `ATMA` command is not fully implemented in WiCAN firmware. WebSocket SLCAN bypasses ELM327 entirely — the app does a manual HTTP 101 Upgrade handshake, sends `C` / `S6` / `O` (close/500kbps/open), and receives raw SLCAN frames. This delivers the full HS-CAN bus at ~2100 fps vs ~12 fps with polled OBD.

**How does TPMS work without OBD queries?** Tire pressure data (CAN ID `0x340`) is broadcast on MS-CAN by the BCM. The Focus RS Gateway Module (GWM) bridges select MS-CAN frames to HS-CAN, so they appear on the bus the WiCAN monitors. No header switching or BCM OBD queries needed.

**How does firmware detection work?** After SLCAN initialisation, the app sends `OPENRS?\r`. openRS_ firmware responds with `OPENRS:<version>`. Stock WiCAN firmware ignores the unknown frame. The first 20 WebSocket messages after init are scanned for the probe response (allowing for queued frames), then the result latches for the session lifetime. CTRL tab feature buttons unlock when openRS_ firmware is confirmed.

**How does the diagnostic system work?** `DiagnosticLogger` (singleton) accumulates three layers of data throughout the session: (1) a per-ID frame inventory with `firstRawHex`, `lastRawHex`, a `hasChanged` flag, and up to 10 periodic raw-hex snapshots per ID sampled every 30 s; (2) a rolling 10 000-entry decode trace; (3) a real-time SLCAN log written to internal storage in standard candump format (`(seconds) can0 ID#DATA`). On export, `DiagnosticExporter` flushes the SLCAN writer and bundles all three artefacts into a ZIP via FileProvider. The SLCAN file is compatible with SavvyCAN, Kayak, and python-can for offline CAN analysis.

---

## Project Structure

```
android/
├── app/src/main/
│   ├── java/com/openrs/dash/
│   │   ├── OpenRSDashApp.kt              # Application singleton + isOpenRsFirmware flag
│   │   ├── auto/                          # Android Auto
│   │   │   ├── CarDashActivity.kt        # Custom AA Activity (full Compose UI)
│   │   │   ├── RSDashCarAppService.kt    # AA entry point (Car App Library)
│   │   │   ├── RSDashSession.kt          # AA session manager
│   │   │   └── screens/                  # 6 AA screens
│   │   │       ├── MainDashScreen.kt
│   │   │       ├── AwdDetailScreen.kt
│   │   │       ├── PerformanceScreen.kt
│   │   │       ├── TempsScreen.kt
│   │   │       ├── TuneScreen.kt
│   │   │       └── TpmsScreen.kt
│   │   ├── can/                           # CAN bus layer
│   │   │   ├── CanDecoder.kt             # 16 CAN frame decoders (DigiCluster-verified)
│   │   │   └── WiCanConnection.kt        # WebSocket SLCAN + firmware probe
│   │   ├── data/
│   │   │   └── VehicleState.kt           # Immutable state (80+ fields, peaks)
│   │   ├── diagnostics/
│   │   │   ├── DiagnosticLogger.kt       # Session-scoped frame/event collector
│   │   │   └── DiagnosticExporter.kt     # ZIP builder + FileProvider share
│   │   ├── service/
│   │   │   └── CanDataService.kt         # Foreground service + DiagnosticLogger hooks
│   │   └── ui/
│   │       ├── MainActivity.kt           # Compose UI (8 tabs)
│   │       ├── AppSettings.kt            # SharedPreferences wrapper
│   │       ├── UserPrefs.kt              # Observable preferences (StateFlow)
│   │       └── SettingsSheet.kt          # Full-screen settings dialog
│   └── res/
│       ├── values/strings.xml
│       ├── values/themes.xml
│       ├── xml/file_paths.xml            # FileProvider path config
│       └── mipmap-*/ic_launcher*.png     # App icon (all densities)
├── browser-emulator/
│   └── index.html                        # Standalone browser emulator (8 tabs + settings)
├── docs/
│   ├── android-auto-setup.md
│   ├── android-auto-custom-ui-research.md
│   ├── hardware-setup.md
│   ├── firmware-update.md
│   └── pid-reference.md
└── README.md
```

---

## CAN Frame Reference

Full PID documentation: [`docs/pid-reference.md`](docs/pid-reference.md)

### HS-CAN Frame IDs (500 kbps) — RS_HS.dbc verified

| ID | Parameters | Formula / Source |
|----|-----------|-----------------|
| 0x070 | Torque at trans (Nm) | Motorola bits 37–47 |
| 0x076 | Throttle % | `byte0 × 0.392` |
| 0x080 | Accel pedal %, brake, reverse | pedal: bits 0–9 LE × 0.1; brake: byte2 bit 1; rev: bit 5 |
| 0x090 | RPM, barometric pressure | RPM: `((byte4 & 0x0F) << 8 \| byte5) × 2`; baro: `byte2 × 0.5 kPa` |
| 0x0C8 | Gauge brightness, e-brake | e-brake: `byte3 & 0x40` |
| 0x0F8 | Engine oil temp, boost pressure, PTU temp | oil: `byte1 − 50 °C`; boost: `byte5 × 0.01 bar (gauge) + baro → kPa abs`; PTU: `byte7 − 60 °C` (RS_HS.dbc PCMmsg07) |
| 0x130 | Vehicle speed kph | `word(6-7) × 0.01` |
| 0x160 | Longitudinal G-force | `((byte6 & 0x03) << 8 \| byte7) × 0.00390625 − 2.0` |
| 0x180 | Lateral G-force | `((byte2 & 0x03) << 8 \| byte3) × 0.00390625 − 2.0` |
| 0x190 | 4-corner wheel speeds | 15-bit Motorola per wheel: `((data[N] & 0x7F) << 8 \| data[N+1]) × 0.011343006 km/h` (RS_HS.dbc ABSmsg03) |
| 0x1A4 | Ambient temp °C | `byte4 signed × 0.25` (MS-CAN bridged) |
| 0x1B0 | Drive mode | Motorola bit 55, 4-bit: 0=Normal 1=Sport 2=Track 3=Drift |
| 0x1C0 | ESC mode | Motorola bit 13, 2-bit |
| 0x230 | Current gear | bits 0–3 |
| 0x2C0 | AWD L/R rear torque (Nm) | bits 0\|12 and 12\|12 signed Motorola |
| 0x2F0 | Coolant temp, Intake Air Temp | coolant: `((data[4]&0x03)<<8\|data[5]) − 60 °C`; IAT: `((data[6]&0x03)<<8\|data[7]) × 0.25 − 127 °C` (RS_HS.dbc PCMmsg16) |
| 0x340 | TPMS LF/RF/LR/RR PSI + Ambient temp | TPMS: bytes 2–5 direct PSI (MS-CAN via GWM); ambient: `byte7 signed × 0.25 °C` (RS_HS.dbc PCMmsg17) |
| 0x34A | Fuel level % | `byte0 × 0.392` |
| 0x3C0 | Battery voltage | `byte0 × 0.1 V` |

### ECU Addresses (OBD — polled)

| ECU | Request | Response | PIDs / Function |
|-----|---------|----------|-----------------|
| PCM | 0x7E0 | 0x7E8 | Mode 22: AFR, ETC, TIP, WGDC, VCT, knock, charge air temp, oil life |
| Broadcast | 0x7DF | varies | Mode 1: calc load, fuel trims, timing, baro, O2 |
| BCM | 0x726 | 0x72E | Mode 22: odometer (0xDD01), battery SOC (0x4028), battery temp (0x4029), cabin temp (0xDD04) |
| AWD module | 0x703 | 0x70B | Mode 22: RDU oil temp (0x1E8A) — `B4 − 40 °C` (polled every 30 s) |

---

## Roadmap

- [x] Phase 1 — CAN sniffing + basic OBD (v1.0)
- [x] Phase 2 — Hybrid ATMA+OBD with Android Auto (v2.0)
- [x] Phase 2.5 — TPMS+, AFR, ETC/TIP/WGDC, VCT, multi-ECU (v2.5)
- [x] Phase 2.6 — Nitrous Blue/Frost White theme, openRS_ branding, live browser emulator
- [x] Phase 2.7 — WebSocket SLCAN rewrite (~2100 fps), user settings, diagnostics export, firmware detection (v1.1.0)
- [x] Phase 2.8 — DBC-verified signal corrections, BCM/AWD polling, IAT, ambient, wheel speeds, SLCAN raw log + per-ID sampling (v1.1.1–v1.1.5)
- [ ] Phase 3 — Custom Activity Android Auto UI (pixel-perfect match to phone)
- [ ] Phase 4 — UDS Fast Rate Session (~100 Hz via DDDI 0x2C)
- [ ] Phase 5 — DTC scanning (Service 0x19 + DTC database)
- [ ] Phase 6 — CSV data logging + lap timer
- [ ] Phase 7 — Track map overlay with GPS correlation

---

## Contributing

Pull requests welcome. See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines.

If you have a Focus RS and FORScan/OBDLink, we'd love help verifying:
- Tire temperature PIDs (0x2823–0x2826) — currently experimental
- Additional BCM PIDs
- MS-CAN parameters (requires 2nd adapter)

---

## License

MIT — see [LICENSE](LICENSE) for details.

---

## Acknowledgments

- **DigiCluster** — Protocol research and PID database reference
- **FORScan** — Ford enhanced PID discovery
- **MeatPi** — WiCAN hardware
- **aa-torque / agronick** — Custom Android Auto UI research
- **Focus RS community** — Testing and feedback

---

<p align="center">
  <sub>Built for the car Ford should have given us an app for.</sub>
</p>
