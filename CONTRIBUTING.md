# Contributing to openRS_

Thanks for your interest in contributing! This project gives Focus RS MK3 owners the telemetry dashboard Ford never made.

## Where to Start

| Area | Guide |
|------|-------|
| **Android app** | [`android/CONTRIBUTING.md`](android/CONTRIBUTING.md) — build setup, architecture, adding PIDs/CAN signals, PR checklist |
| **Firmware** | [`firmware/README.md`](firmware/README.md) — build instructions, directory structure, CAN frame reference |
| **Documentation** | PRs against any `.md` file welcome — see the [docs source of truth](README.md) |

## Quick Links

- **Bug reports / feature requests:** [GitHub Issues](https://github.com/klexical/openRS_/issues)
- **PID reference:** [`android/docs/pid-reference.md`](android/docs/pid-reference.md)
- **Hardware setup:** [`android/docs/hardware-setup.md`](android/docs/hardware-setup.md)
- **Firmware flashing:** [`android/docs/firmware-update.md`](android/docs/firmware-update.md)

## PID Research

We especially need help verifying:

- **12V battery voltage** — CAN ID 0x3C0 does not broadcast; needs alternative source
- **Tire temperature PIDs** (0x2823–0x2826) — currently experimental
- **Brake pressure calibration** — CAN ID 0x252 raw ADC 0–4095, need known-pressure reference
- **MS-CAN parameters** — accessible at 125 kbps, requires second adapter
- **Additional BCM/IPC PIDs**

If you have a Focus RS with FORScan or an OBDLink MX+, your PID dumps are incredibly valuable.

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
