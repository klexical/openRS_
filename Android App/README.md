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

**openRS_** is a native Android app that turns your phone or Android Auto head unit into a full telemetry dashboard for the Ford Focus RS MK3. It connects wirelessly to a [MeatPi WiCAN](https://www.mouser.com/ProductDetail/MeatPi/WICAN-USB-C3?qs=rQFj71Wb1eVDX2eEy0FC7A%3D%3D) adapter over Wi-Fi and decodes **33 live parameters** from the car's CAN bus and ECU — including data Ford never exposes.

Unlike generic OBD apps, openRS_ is purpose-built for the Focus RS. It understands the GKN Twinster AWD system, reads TPMS tire pressures directly from the BCM, decodes Ford-specific Mode 22 enhanced PIDs, and presents everything in a dark, glanceable interface tuned for track days.

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

### 6 Phone Tabs + 6 Android Auto Screens

| Screen | Description |
|--------|-------------|
| **DASH** | Primary gauges — boost, RPM, speed, gear, throttle, AWD split, temps, G-forces |
| **AWD** | GKN Twinster detail — L/R torque bars, 4-corner wheel speeds, RDU/PTU temps |
| **PERF** | G-force, yaw, steering, peak tracking with reset |
| **TEMPS** | All 8 temperature sensors with colour-coded warnings + Ready to Race indicator |
| **TUNE** | AFR actual/desired, ETC, TIP, WGDC, VCT, knock, fuel trims, timing advance |
| **TPMS** | 4-corner tire pressure (PSI) and temperature with low-pressure alerts |

The Android Auto UI is **visually identical** to the phone app — same gauge boxes, info cells, torque bars, and temp gauges — using the openRS_ custom Activity approach.

### 33 Live Parameters

**CAN Bus Sniffed (real-time at bus speed via ATMA):**
RPM, boost, speed, throttle, accel pedal, steering angle, brake pressure, yaw rate, lateral G, longitudinal G, 4× wheel speeds, AWD L/R torque, RDU temp, PTU temp, AWD max torque, drive mode, ESC status, gear, battery voltage, fuel level, ambient temp, gauge illumination

**OBD Mode 1 (standard):**
Calculated load, short/long fuel trims, timing advance, fuel rail pressure, commanded AFR, O2 voltage, barometric pressure

**OBD Mode 22 — Ford Enhanced via PCM (0x7E0):**
AFR actual, AFR desired, ETC actual/desired, TIP actual/desired, WGDC, VCT intake/exhaust angles, ignition correction (knock), octane adjust ratio, charge air temp, catalytic temp, oil life

**OBD Mode 22 — TPMS via BCM (0x726):**
4× tire pressure, 4× tire temperature (experimental)

### Hybrid Polling Architecture

openRS_ uses a novel **time-sliced ATMA + OBD** approach:

1. **ATMA window** (~150ms): Passively sniff all CAN frames at bus speed — AWD, G-force, wheel speeds update in real-time
2. **OBD window** (~100ms): Query 2-3 diagnostic PIDs from PCM/BCM with intelligent priority scheduling
3. **Repeat** at ~4 Hz for OBD data, continuous for sniffed data

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
2. Connect your phone to the WiCAN's Wi-Fi network (`192.168.80.1:3333` default)
3. Install openRS_ and tap **CONNECT**

> **Note:** The WiCAN must be in **ELM327 TCP mode** on port 3333. No Bluetooth pairing needed.

---

## Quick Start

### Prerequisites

- Android Studio Ladybug (2024.2) or newer
- JDK 17+
- Android SDK 35

### Build & Install

```bash
git clone https://github.com/klexical/openRS.git
cd openRS/"Android App"
./gradlew assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
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
┌─────────────────────────────────────────────────────┐
│  Android Auto (Custom Activity — identical to phone) │
│  ┌──────┬──────┬──────┬──────┬──────┬──────┐        │
│  │ DASH │ AWD  │ PERF │TEMPS │ TUNE │ TPMS │        │
│  └──────┴──────┴──────┴──────┴──────┴──────┘        │
├─────────────────────────────────────────────────────┤
│  Phone UI (Jetpack Compose + Material3)             │
│  ┌──────┬──────┬──────┬──────┬──────┬──────┐        │
│  │ DASH │ AWD  │ PERF │TEMPS │ TUNE │ TPMS │        │
│  └──────┴──────┴──────┴──────┴──────┴──────┘        │
├─────────────────────────────────────────────────────┤
│               VehicleState (StateFlow)              │
│      Immutable data class • 80+ fields • peaks      │
├─────────────────────────────────────────────────────┤
│             CanDataService (Foreground)              │
│  Merges CAN + OBD → VehicleState → notifies UIs     │
├──────────────────────┬──────────────────────────────┤
│     CanDecoder       │        ObdPids               │
│  16 CAN frame IDs    │  33 PIDs (Mode 1 + 22)       │
│  Passive decode      │  Multi-ECU (PCM/BCM)         │
│  ATMA monitor        │  Priority scheduling         │
├──────────────────────┴──────────────────────────────┤
│            WiCanConnection (TCP Socket)              │
│  Hybrid ATMA + PID │ Header mgmt │ Auto-reconnect   │
├─────────────────────────────────────────────────────┤
│                MeatPi WiCAN OBD-II                   │
│              Wi-Fi • ELM327 • TCP:3333               │
├──────────────────────┬──────────────────────────────┤
│     HS-CAN 500k      │     OBD-II (ISO 15765)       │
│  0x070–0x3C0 frames  │  PCM 0x7E0 / BCM 0x726       │
└──────────────────────┴──────────────────────────────┘
```

### Key Design Decisions

**Why ATMA + OBD hybrid?** Pure ATMA gives real-time CAN data but can't access diagnostic PIDs. Pure OBD polling is slow (~100ms per PID). Our hybrid time-slices between both: 150ms sniffing + 100ms querying = 4 Hz diagnostic data with zero lag on CAN data.

**Why multi-ECU header management?** TPMS data lives on the BCM (0x726), not the PCM (0x7E0). Switching ELM327 headers costs ~100ms, so we batch all BCM queries and only switch every 6th cycle.

**Why priority scheduling?** AFR and throttle change every combustion cycle. Tire pressure changes over minutes. Our 4-tier priority system (1/2/3/6) ensures fast data updates fast and slow data doesn't waste bandwidth.

---

## Project Structure

```
Android App/
├── app/src/main/
│   ├── java/com/openrs/dash/
│   │   ├── OpenRSDashApp.kt              # Application singleton + VehicleState
│   │   ├── auto/                          # Android Auto
│   │   │   ├── RSDashCarAppService.kt    # AA entry point (Car App Library)
│   │   │   ├── RSDashSession.kt          # AA session manager
│   │   │   └── screens/                  # 6 AA screens
│   │   │       ├── MainDashScreen.kt
│   │   │       ├── AwdDetailScreen.kt
│   │   │       ├── PerformanceScreen.kt
│   │   │       ├── TempsScreen.kt
│   │   │       ├── TuneScreen.kt
│   │   │       ├── TpmsScreen.kt
│   │   │       └── MenuScreen.kt
│   │   ├── can/                           # CAN bus layer
│   │   │   ├── CanDecoder.kt             # Passive CAN frame decoder
│   │   │   ├── ObdPids.kt                # 33 PID definitions + parsers
│   │   │   └── WiCanConnection.kt        # TCP connection + hybrid polling
│   │   ├── data/
│   │   │   └── VehicleState.kt           # Immutable state (80+ fields)
│   │   ├── service/
│   │   │   └── CanDataService.kt         # Foreground service + state merge
│   │   └── ui/
│   │       └── MainActivity.kt           # Compose UI (6 tabs)
│   └── res/
│       ├── values/colors.xml             # Nitrous Blue + Frost White palette
│       ├── values/themes.xml             # openRS_ dark theme
│       └── mipmap-*/ic_launcher*.png     # App icon (all densities)
├── browser-emulator/
│   └── index.html                        # Standalone browser emulator
├── docs/
│   ├── android-auto-setup.md
│   ├── android-auto-custom-ui-research.md
│   ├── hardware-setup.md
│   └── pid-reference.md
└── README.md
```

---

## OBD PID Reference

Full PID documentation: [`docs/pid-reference.md`](docs/pid-reference.md)

### ECU Addresses

| ECU | Header | Response | Function |
|-----|--------|----------|----------|
| PCM | 0x7E0 | 0x7E8 | Engine, transmission, fuel, ignition |
| BCM | 0x726 | 0x72E | Body control, TPMS |
| Broadcast | 0x7DF | varies | Standard Mode 1 PIDs |

### CAN Frame IDs (HS-CAN 500 kbps)

| ID | Description |
|----|-------------|
| 0x070 | Torque at transmission |
| 0x076 | Throttle position + vehicle speed |
| 0x080 | Pedals + steering angle |
| 0x090 | RPM + coolant temp |
| 0x0B0 | Yaw rate + G-forces |
| 0x0F8 | Engine temps (intake, boost, oil) |
| 0x215 | 4-corner wheel speeds |
| 0x2C0 | AWD L/R torque + RDU temp |
| 0x2C2 | PTU temperature |
| 0x34A | Fuel level |
| 0x3C0 | Battery voltage |

---

## Roadmap

- [x] Phase 1 — CAN sniffing + basic OBD (v1.0)
- [x] Phase 2 — Hybrid ATMA+OBD with Android Auto (v2.0)
- [x] Phase 2.5 — TPMS+, AFR, ETC/TIP/WGDC, VCT, multi-ECU (v2.5)
- [x] Phase 2.6 — Nitrous Blue/Frost White theme, openRS_ branding, live browser emulator
- [ ] Phase 3 — Custom Activity Android Auto UI (pixel-perfect match to phone)
- [ ] Phase 4 — UDS Fast Rate Session (~100 Hz via DDDI 0x2C)
- [ ] Phase 5 — DTC scanning (Service 0x19 + DTC database)
- [ ] Phase 6 — Data logging + CSV export
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
