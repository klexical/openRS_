<p align="center">
  <img src="../assets/images/openrs-wordmark.svg" width="320" alt="openRS_"><br/>
  <sub>Android app — build, develop, and contribute</sub>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/version-2.2.2-blue" alt="Version">
  <img src="https://img.shields.io/badge/Kotlin-2.0-purple?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/Jetpack_Compose-Material3-4285F4?logo=jetpackcompose" alt="Compose">
</p>

> For full project documentation — features, CAN tables, architecture, hardware setup, and roadmap — see the **[root README](../README.md)**.

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
# Output: app/build/outputs/apk/release/openRS_v2.2.2.apk
# (Requires keystore — see docs/signing-setup.md)
```

### Debug build (no keystore required)

```bash
./gradlew assembleDebug
# Output: app/build/outputs/apk/debug/openRS_v2.2.2-staging-debug.apk
```

### Browser Emulator

Open `browser-emulator/index.html` in any browser, or visit the live version:

**[klexical.github.io/openRS_](https://klexical.github.io/openRS_)**

---

## Project Structure

```
android/
├── app/src/main/
│   ├── java/com/openrs/dash/
│   │   ├── OpenRSDashApp.kt              # Application singleton + isOpenRsFirmware flag
│   │   ├── can/
│   │   │   ├── AdapterState.kt           # Shared connection state sealed class
│   │   │   ├── CanDecoder.kt             # 21 CAN frame decoders (RS_HS.dbc-verified)
│   │   │   ├── MeatPiConnection.kt       # MeatPi Pro raw TCP SLCAN + OBD polling
│   │   │   ├── ObdConstants.kt           # Shared OBD query strings + CAN IDs + timing
│   │   │   ├── ObdResponseParser.kt      # Shared OBD Mode 22 response parsers
│   │   │   ├── SlcanParser.kt            # Shared SLCAN frame parser
│   │   │   └── WiCanConnection.kt        # WiCAN WebSocket SLCAN + firmware probe
│   │   ├── data/
│   │   │   ├── DtcModuleSpec.kt          # ECU module descriptor for DTC operations
│   │   │   ├── DtcResult.kt              # DTC result + status enum
│   │   │   ├── TripPoint.kt              # GPS waypoint with telemetry
│   │   │   ├── TripState.kt              # Trip accumulator
│   │   │   └── VehicleState.kt           # Immutable state (90+ fields, peaks, RTR)
│   │   ├── diagnostics/
│   │   │   ├── DiagnosticExporter.kt     # ZIP builder + FileProvider share + CSV
│   │   │   ├── DiagnosticLogger.kt       # Session-scoped collector + SLCAN log
│   │   │   ├── DtcDatabase.kt            # Bundled 873-code Ford DTC lookup
│   │   │   └── DtcScanner.kt             # DTC scan/clear orchestrator
│   │   ├── service/
│   │   │   └── CanDataService.kt         # Background service + DiagnosticLogger hooks
│   │   └── ui/
│   │       ├── MainActivity.kt           # Compose entry (6 tabs + header)
│   │       ├── DashPage.kt               # DASH tab
│   │       ├── PowerPage.kt              # POWER tab
│   │       ├── ChassisPage.kt            # CHASSIS tab
│   │       ├── TempsPage.kt              # TEMPS tab
│   │       ├── DiagPage.kt               # DIAG tab (DTC scanner + diagnostics)
│   │       ├── MorePage.kt               # MORE tab
│   │       ├── Theme.kt                  # Design tokens, fonts, colors
│   │       ├── Components.kt             # Shared composables
│   │       ├── AppSettings.kt            # SharedPreferences wrapper
│   │       ├── UserPrefs.kt              # Observable preferences (StateFlow)
│   │       └── SettingsSheet.kt          # Settings dialog
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
├── docs/
│   ├── images/openrs-banner.svg          # Banner image
│   ├── hardware-setup.md
│   ├── firmware-update.md
│   ├── pid-reference.md
│   └── signing-setup.md                  # Release keystore setup guide
├── browser-emulator/
│   ├── index.html                        # Browser emulator (phone UI mirror)
│   └── README.md
└── README.md
```

---

## Development Guide

### Adding a New CAN Signal

1. Add the constant `ID_xxx` to `CanDecoder.kt`
2. Add it to the `KNOWN_IDS` set
3. Add the decode case in the `when` block
4. Add the field(s) to `VehicleState.kt`

### Adding a New OBD PID

1. Add the SLCAN transmit frame string to `ObdConstants.kt` (in the appropriate ECU query list)
2. Add the response parser to `ObdResponseParser.kt`
3. Add the field(s) to `VehicleState.kt`

### Connection Architecture

openRS_ connects to the WiCAN adapter via **WebSocket SLCAN** (`ws://192.168.80.1:80/ws`). The app sends SLCAN init commands (`C` / `S6` / `O`) on connect and receives passive CAN frames at ~2100 fps. OBD queries are sent as SLCAN transmit frames interleaved with the passive stream.

Two adapter backends exist:
- `WiCanConnection.kt` — WebSocket SLCAN (MeatPi WiCAN)
- `MeatPiConnection.kt` — Raw TCP SLCAN (MeatPi WiCAN Pro, `192.168.0.10:35000`)

Both share `ObdConstants.kt`, `ObdResponseParser.kt`, `SlcanParser.kt`, and `AdapterState.kt`.

---

## Docs

| Document | Description |
|----------|-------------|
| [pid-reference.md](docs/pid-reference.md) | Complete OBD PID reference with decode formulas |
| [hardware-setup.md](docs/hardware-setup.md) | WiCAN hardware setup and OBD-II port location |
| [firmware-update.md](docs/firmware-update.md) | Flashing openrs-fw to the WiCAN |
| [signing-setup.md](docs/signing-setup.md) | Release keystore configuration |
| [CONTRIBUTING.md](CONTRIBUTING.md) | Contribution guidelines and PR checklist |
| [CHANGELOG.md](CHANGELOG.md) | Version history |

---

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines and PR checklist.

---

## License

MIT — see [LICENSE](../LICENSE) for details.
