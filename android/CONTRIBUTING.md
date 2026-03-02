# Contributing to openRS

Thanks for your interest in contributing to openRS! This project aims to give Focus RS owners the telemetry dashboard Ford never made.

## Getting Started

1. **Fork** the repository and clone your fork
2. Create a **feature branch** from `main`: `git checkout -b feature/your-feature`
3. Make your changes
4. **Test** on a real device if possible (especially CAN/OBD changes)
5. Submit a **pull request** against `main`

## Development Environment

- **Android Studio** Ladybug 2024.2+
- **JDK 17** (bundled with Android Studio)
- **Android SDK 35** + Build Tools 35.0.0
- **Kotlin 2.0.21** (managed by Gradle)

## Code Style

- Follow standard [Kotlin coding conventions](https://kotlinlang.org/docs/coding-conventions.html)
- Use `ktlint` for formatting (CI will check)
- Keep the monospace/dark theme aesthetic in UI code
- Document CAN frame IDs and PID formulas with source references

## Architecture Guidelines

### Adding a New OBD PID

1. Add the field to `VehicleState.kt` with a sensible default
2. Add the `ObdPid` entry in `ObdPids.kt` with the correct header, priority, and parse function
3. Add the merge line in `CanDataService.kt` → `onObdUpdate`
4. Add UI display in the appropriate screen(s)
5. Document the PID in `docs/pid-reference.md`

### Adding a New CAN Frame

1. Add the constant `ID_xxx` to `CanDecoder.kt`
2. Add it to the `KNOWN_IDS` set
3. Add the decode case in the `when` block
4. Add the field(s) to `VehicleState.kt`

### Priority Levels for OBD PIDs

| Priority | Update Rate | Use For |
|----------|-------------|---------|
| 1 | Every cycle (~250ms) | Fast-changing: AFR, throttle, boost |
| 2 | Every 2nd cycle (~500ms) | Moderate: fuel trims, timing, WGDC |
| 3 | Every 3rd cycle (~750ms) | Slow: temperatures, O2, barometric |
| 6 | Every 6th cycle (~1.5s) | Very slow: TPMS, oil life |

### Multi-ECU Notes

Switching ELM327 headers (`ATSH`) costs ~100ms. Always group PIDs by ECU and avoid unnecessary header switches. BCM queries (TPMS) should be priority 6 to minimize switches.

## Pull Request Checklist

- [ ] Code builds without warnings
- [ ] New PIDs are documented with formula source
- [ ] UI changes work on both phone and Android Auto
- [ ] No hardcoded strings (use `strings.xml` for user-visible text)
- [ ] Commit messages are descriptive

## Bug Reports

Please include:
- Device model and Android version
- WiCAN firmware version
- Which screen/tab the issue occurs on
- Relevant logcat output (`adb logcat -s openRS`)

## PID Research

We especially need help with:
- **Tire temperature PIDs** (0x2823–0x2826): These are educated guesses. If you have FORScan, please verify!
- **MS-CAN parameters**: Accessible on the medium-speed bus at 125 kbps
- **Additional BCM/IPC PIDs**: The BCM has hundreds of PIDs we haven't mapped

If you have a Focus RS with FORScan or an OBDLink MX+, your PID dumps are incredibly valuable.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
