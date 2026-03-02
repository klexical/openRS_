## What is openRS?

(https://github.com/klexical/openRS#what-is-openrs)

**openRS** is a native Android app that turns your phone or Android Auto head unit into a full telemetry dashboard for the Ford Focus RS MK3. It connects wirelessly to a [WiCAN OBD-II adapter](https://www.wican.io/) over Wi-Fi and decodes **33 live parameters** from the car's CAN bus and ECU, including data Ford never shows you.

Unlike generic OBD apps, openRS is purpose-built for the Focus RS. It understands the GKN Twinster AWD system, reads TPMS tire pressures directly from the BCM, decodes Ford-specific Mode 22 enhanced PIDs, and presents everything in a dark, glanceable interface designed for track days.

### What You Get

[](https://github.com/klexical/openRS#what-you-get)

- **Real AWD telemetry**: Left/right rear torque split, torque vectoring bias, RDU/PTU temperatures
- **TPMS+ with actual PSI values**: Per-tire pressure and temperature, not just a warning light
- **Tuning data**: Wideband AFR actual vs desired, ETC vs TIP, wastegate duty cycle, VCT angles, knock correction
- **Performance metrics**: Lateral/longitudinal G-force, peak tracking, yaw rate, steering angle
- **All temperatures**: Oil, coolant, intake, charge air, catalytic converter, ambient, RDU, PTU
- **Android Auto integration**: 5 screens purpose-built for the head unit

---

## Features

[](https://github.com/klexical/openRS#features)

### 6 Phone Tabs + 5 Android Auto Screens

[](https://github.com/klexical/openRS#6-phone-tabs--5-android-auto-screens)

|Screen|Description|
|---|---|
|**DASH**|Primary gauges — boost, RPM, speed, gear, throttle, AWD split, temps|
|**AWD**|GKN Twinster detail — L/R torque bars, 4-corner wheel speeds, differentials|
|**PERF**|G-force, yaw, steering, peak tracking with reset|
|**TEMPS**|All 8 temperature sensors with color-coded warnings + "Ready to Race" indicator|
|**TUNE**|AFR actual/desired, ETC, TIP, WGDC, VCT, knock, fuel trims, timing advance|
|**TPMS**|4-corner tire pressure (PSI) and temperature with low-pressure alerts|

### 33 Live Parameters

[](https://github.com/klexical/openRS#33-live-parameters)

**CAN Bus Sniffed (real-time at bus speed via ATMA):** RPM, boost, speed, throttle, accel pedal, steering angle, brake pressure, yaw rate, lateral G, longitudinal G, 4× wheel speeds, AWD L/R torque, RDU temp, PTU temp, AWD max torque, drive mode, ESC status, gear, battery voltage, fuel level, ambient temp, gauge illumination

**OBD Mode 1 (standard):** Calculated load, short/long fuel trims, timing advance, fuel rail pressure, commanded AFR, O2 voltage, barometric pressure

**OBD Mode 22 — Ford Enhanced via PCM (0x7E0):** AFR actual, AFR desired, ETC actual/desired, TIP actual/desired, WGDC, VCT intake/exhaust angles, ignition correction (knock), octane adjust ratio, charge air temp, catalytic temp, oil life

**OBD Mode 22 — TPMS via BCM (0x726):** 4× tire pressure, 4× tire temperature (experimental)

### Hybrid Polling Architecture

[](https://github.com/klexical/openRS#hybrid-polling-architecture)

openRS uses a novel **time-sliced ATMA + OBD** approach:

1. **ATMA window** (~150ms): Passively sniff all CAN frames at bus speed — AWD, G-force, wheel speeds update in real-time
2. **OBD window** (~100ms): Query 2-3 diagnostic PIDs from PCM/BCM with intelligent priority scheduling
3. **Repeat** at ~4 Hz for OBD data, continuous for sniffed data

This gives you the best of both worlds: real-time CAN data AND deep OBD diagnostics, without the lag of pure PID polling.

---

## Hardware

[](https://github.com/klexical/openRS#hardware)

### Required

[](https://github.com/klexical/openRS#required)

|Component|Details|
|---|---|
|**WiCAN OBD-II**|[MeatPi WiCAN](https://www.wican.io/) — Wi-Fi ELM327-compatible adapter|
|**Ford Focus RS MK3**|2016-2018 (LZ platform, EcoBoost 2.3L)|
|**Android phone**|Android 9+ (API 28) with Wi-Fi|

### Optional

[](https://github.com/klexical/openRS#optional)

|Component|Details|
|---|---|
|**Android Auto head unit**|Any AA-compatible unit (tested on Sync 3 + aftermarket)|
|**2nd WiCAN**|For MS-CAN (0x340 TPMS broadcast at 125 kbps) — not required, BCM method works|

### Setup

[](https://github.com/klexical/openRS#setup)

1. Plug the WiCAN into the OBD-II port (under the steering column)
2. Connect your phone to the WiCAN's Wi-Fi network (default: `192.168.80.1:3333`)
3. Install openRS and tap **CONNECT**

> **Note:** The WiCAN must be in **ELM327 TCP mode** on port 3333 (the default). No Bluetooth pairing needed.

---

## Quick Start

[](https://github.com/klexical/openRS#quick-start)

### Prerequisites

[](https://github.com/klexical/openRS#prerequisites)

- Android Studio Ladybug (2024.2) or newer
- JDK 17+
- Android SDK 35

### Build

[](https://github.com/klexical/openRS#build)

```shell
git clone https://github.com/openRS/openRS.git
cd openRS
./gradlew assembleDebug
```

### Install

[](https://github.com/klexical/openRS#install)

```shell
# Direct install to connected device
./gradlew installDebug

# Or via ADB
adb install app/build/outputs/apk/debug/app-debug.apk
```

### Android Auto Development

[](https://github.com/klexical/openRS#android-auto-development)

To test on a head unit or Desktop Head Unit (DHU):

```shell
# Enable developer mode in Android Auto settings
# Add openRS to the AA developer allow-list
adb shell dumpsys activity service com.google.android.projection.gearhead/.GearheadService
```

See [docs/android-auto-setup.md](https://github.com/klexical/openRS/blob/main/docs/android-auto-setup.md) for detailed AA development instructions.

---

## Architecture

[](https://github.com/klexical/openRS#architecture)

```
┌─────────────────────────────────────────────────────┐
│  Android Auto (Car App Library)                     │
│  ┌──────────┬──────────┬──────────┬──────┬───────┐  │
│  │MainDash  │AwdDetail │  Perf    │ Tune │ TPMS  │  │
│  │ Screen   │ Screen   │ Screen   │Screen│Screen │  │
│  └──────────┴──────────┴──────────┴──────┴───────┘  │
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
│  16 CAN frame IDs    │  33 PIDs (Mode 1 + 22)      │
│  Passive decode      │  Multi-ECU (PCM/BCM)         │
│  ATMA monitor        │  Priority scheduling          │
├──────────────────────┴──────────────────────────────┤
│            WiCanConnection (TCP Socket)              │
│  Hybrid ATMA + PID │ Header mgmt │ Auto-reconnect   │
├─────────────────────────────────────────────────────┤
│                    WiCAN OBD-II                      │
│              Wi-Fi • ELM327 • TCP:3333               │
├──────────────────────┬──────────────────────────────┤
│     HS-CAN 500k      │     OBD-II (ISO 15765)      │
│  0x070–0x3C0 frames  │  PCM 0x7E0 / BCM 0x726      │
└──────────────────────┴──────────────────────────────┘
```

### Key Design Decisions

[](https://github.com/klexical/openRS#key-design-decisions)

**Why ATMA + OBD hybrid?** Pure ATMA gives real-time CAN data but can't access diagnostic PIDs. Pure OBD polling is slow (~100ms per PID). Our hybrid approach time-slices between both: 150ms sniffing + 100ms querying = 4 Hz diagnostic data with zero lag on CAN data.

**Why multi-ECU header management?** TPMS data lives on the BCM (0x726), not the PCM (0x7E0). Switching ELM327 headers costs ~100ms, so we batch all BCM queries together and only switch every 6th cycle.

**Why priority scheduling?** AFR and throttle change every combustion cycle. Tire pressure changes over minutes. Our 4-tier priority system (1/2/3/6) ensures fast data updates fast and slow data doesn't waste bandwidth.

---

## Project Structure

[](https://github.com/klexical/openRS#project-structure)

```
openRS/
├── app/
│   └── src/
│       ├── main/
│       │   ├── java/com/openrs/dash/
│       │   │   ├── OpenRSDashApp.kt          # Application singleton + state
│       │   │   ├── auto/                      # Android Auto
│       │   │   │   ├── RSDashCarAppService.kt # AA entry point
│       │   │   │   ├── RSDashSession.kt       # AA session
│       │   │   │   └── screens/               # 5 AA screens
│       │   │   ├── can/                        # CAN bus layer
│       │   │   │   ├── CanDecoder.kt          # Passive CAN frame decoder
│       │   │   │   ├── ObdPids.kt             # 33 PID definitions + parsers
│       │   │   │   └── WiCanConnection.kt     # TCP connection + hybrid polling
│       │   │   ├── data/                       # Data models
│       │   │   │   └── VehicleState.kt        # Immutable state (80+ fields)
│       │   │   ├── service/                    # Background services
│       │   │   │   └── CanDataService.kt      # Foreground service + state merge
│       │   │   └── ui/                         # Phone UI
│       │   │       └── MainActivity.kt        # Compose UI (6 tabs)
│       │   └── res/                            # Android resources
│       └── test/                               # Unit tests
├── docs/                                       # Documentation
├── scripts/                                    # Build/deploy helpers
├── build.gradle.kts                            # Root build config
├── settings.gradle.kts                         # Project settings
├── gradle.properties                           # Gradle config
├── gradlew / gradlew.bat                       # Gradle wrapper
└── README.md
```

---

## OBD PID Reference

[](https://github.com/klexical/openRS#obd-pid-reference)

Full PID documentation is in [docs/pid-reference.md](https://github.com/klexical/openRS/blob/main/docs/pid-reference.md).

### ECU Addresses

[](https://github.com/klexical/openRS#ecu-addresses)

|ECU|Header|Response|Function|
|---|---|---|---|
|PCM|0x7E0|0x7E8|Engine, transmission, fuel, ignition|
|BCM|0x726|0x72E|Body control, TPMS|
|Broadcast|0x7DF|varies|Standard Mode 1 PIDs|

### CAN Frame IDs (HS-CAN 500 kbps)

[](https://github.com/klexical/openRS#can-frame-ids-hs-can-500-kbps)

|ID|Description|
|---|---|
|0x070|Torque at transmission|
|0x076|Throttle position + vehicle speed|
|0x080|Pedals + steering angle|
|0x090|RPM + coolant temp|
|0x0B0|Yaw rate + G-forces|
|0x0F8|Engine temps (intake, boost, oil)|
|0x215|4-corner wheel speeds|
|0x2C0|AWD L/R torque + RDU temp|
|0x2C2|PTU temperature|
|0x34A|Fuel level|
|0x3C0|Battery voltage|

---

## Roadmap

[](https://github.com/klexical/openRS#roadmap)

- [x]  Phase 1 — CAN sniffing + basic OBD (v1.0)
- [x]  Phase 2 — Hybrid ATMA+OBD with Android Auto (v2.0)
- [x]  Phase 2.5 — TPMS+, AFR, ETC/TIP/WGDC, VCT, multi-ECU (v2.5)
- [ ]  Phase 3 — UDS Fast Rate Session (~100 Hz via DDDI 0x2C)
- [ ]  Phase 4 — DTC scanning (Service 0x19 + DTC database)
- [ ]  Phase 5 — Data logging + CSV export
- [ ]  Phase 6 — Track map overlay with GPS correlation

---