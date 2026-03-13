# Contributing to openRS_

Thanks for your interest in contributing to openRS_! This project aims to give Focus RS owners the telemetry dashboard Ford never made.

## Getting Started

1. **Fork** the repository and clone your fork
2. Create a **feature branch** from `staging`: `git checkout -b feature/your-feature`
3. Make your changes
4. **Test** on a real device if possible (especially CAN/OBD changes)
5. Submit a **pull request** against `staging`

## Development Environment

- **Android Studio** Ladybug 2024.2+
- **JDK 17** (bundled with Android Studio)
- **Android SDK 35** + Build Tools 35.0.0
- **Kotlin 2.0.21** (managed by Gradle)

## Code Style

- Follow standard [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Follow [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html) for formatting
- Keep the monospace/dark theme aesthetic in UI code
- Document CAN frame IDs and PID formulas with source references

## Architecture Guidelines

### Adding a New OBD PID

1. Add the field to `VehicleState.kt` with a sensible default
2. Add the query string to `ObdConstants.kt` in the appropriate ECU query array (e.g. `PCM_QUERIES`, `BCM_QUERIES`)
3. Add the response parser in `ObdResponseParser.kt` (e.g. inside `parsePcmResponse()` or `parseBcmResponse()`)
4. Wire the merge in `CanDataService.kt` → `mergeObdState()`
5. Add UI display in the appropriate tab composable
6. Document the PID in `docs/pid-reference.md`

### Adding a New CAN Frame

1. Add the constant `ID_xxx` to `CanDecoder.kt`
2. Add it to the `KNOWN_IDS` set
3. Add the decode case in the `when` block
4. Add the field(s) to `VehicleState.kt`

### OBD Polling Intervals

OBD queries are grouped by ECU and sent at fixed intervals defined in `ObdConstants.kt`:

| ECU | Interval | Parameters |
|-----|----------|------------|
| PCM (Mode 22) | 30 s | ETC, WGDC, KR, OAR, AFR, TIP, VCT, charge air, catalyst temp, oil life, HP fuel rail, fuel level |
| BCM (Mode 22) | 30 s | Battery SOC, battery temp, cabin temp, **TPMS LF/RF/LR/RR** (0x2813–0x2816) |
| BCM extended session | 60 s | Odometer (0xDD01) |
| AWD module (ext session) | 60 s | RDU oil temp |

### Connection Architecture

openRS_ connects to the WiCAN adapter via **WebSocket SLCAN** (`ws://192.168.80.1:80/ws`). The app sends SLCAN init commands (`C` / `S6` / `O`) on connect and receives passive CAN frames at ~2100 fps. OBD queries are sent as SLCAN transmit frames interleaved with the passive stream.

Two adapter backends exist:
- `WiCanConnection.kt` — WebSocket SLCAN (MeatPi WiCAN)
- `MeatPiConnection.kt` — Raw TCP SLCAN (MeatPi WiCAN Pro, `192.168.0.10:35000`)

Both share `ObdConstants.kt`, `ObdResponseParser.kt`, `SlcanParser.kt`, and `AdapterState.kt`.

## Pull Request Checklist

- [ ] Code builds without warnings
- [ ] New PIDs are documented with formula source
- [ ] UI changes tested on phone
- [ ] No hardcoded strings (use `strings.xml` for user-visible text)
- [ ] Commit messages are descriptive

## Bug Reports

Please include:
- Device model and Android version
- WiCAN firmware version (stock or openrs-fw)
- Which screen/tab the issue occurs on
- Relevant logcat output (`adb logcat -s openRS`)

## PID Research

We especially need help with:
- **12V battery voltage** — CAN ID 0x3C0 does not broadcast on this vehicle; needs alternative source (BCM PID, or other CAN ID)
- **Tire temperature PIDs** (0x2823–0x2826): These are educated guesses. If you have FORScan, please verify!
- **Brake pressure calibration** — CAN ID 0x252 raw ADC 0–4095, need reference-pressure data
- **MS-CAN parameters**: Accessible on the medium-speed bus at 125 kbps
- **Additional BCM/IPC PIDs**: The BCM has hundreds of PIDs we haven't mapped

If you have a Focus RS with FORScan or an OBDLink MX+, your PID dumps are incredibly valuable.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
