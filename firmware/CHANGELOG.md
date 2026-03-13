# openrs-fw Changelog

All notable changes to the openrs-fw firmware are documented here.

---

## v1.4 — 2026-03-08

### Added
- **Dual-device build system** — `build.sh --target usb|pro` supports both WiCAN USB-C3 (ESP32-C3) and WiCAN Pro (ESP32-S3) as separate build targets ([#73](https://github.com/klexical/openRS_/issues/73))
- **Device profiles** — `patches/profiles/usb.py` and `patches/profiles/pro.py` define per-device wican-fw tags, SoC targets, sdkconfig, partition tables, and patch anchors
- **Universal OPENRS? probe** — firmware identity probe in `slcan_parse_str()` works over all transports (TCP and WebSocket), replacing the WebSocket-only implementation
- **Pro build configuration** — `sdkconfig.defaults.pro` (ESP32-S3, 16MB flash, 8MB PSRAM) and `partitions_openrs_pro.csv` (dual OTA with rollback)
- REST API authentication — `POST /api/frs` now requires `{"token":"openrs"}` for basic access control ([#55](https://github.com/klexical/openRS_/issues/55))
- `sleepVoltage` parameter in `POST /api/frs` for configuring battery sleep threshold ([#57](https://github.com/klexical/openRS_/issues/57))
- ESC mode persisted to NVS on change ([#56](https://github.com/klexical/openRS_/issues/56))
- FreeRTOS mutex for thread-safe state access in `focusrs.c` ([#61](https://github.com/klexical/openRS_/issues/61))
- Drive mode task runs asynchronously to prevent HTTP server blocking ([#59](https://github.com/klexical/openRS_/issues/59))

### Changed
- `apply_patches.py` refactored from monolithic script to profile-driven architecture with `--target` flag
- `build.sh` now accepts `--target usb|pro` (default: `usb` for backward compatibility)
- Build directories separated per target: `.build/usb/` and `.build/pro/`
- Partition CSV renamed from `partitions_openrs.csv` to `partitions_openrs_usb.csv`
- sdkconfig renamed from `sdkconfig.defaults` to `sdkconfig.defaults.usb`
- `CONFIG_HARDWARE_VER` removed from sdkconfig — hardware version is set in wican-fw's CMakeLists.txt per tag

### Fixed
- Drive mode cycle math corrected to use CAN-to-position lookup table ([#53](https://github.com/klexical/openRS_/issues/53))
- WiFi AP password changed from stock `@meatpi#` to `openrs_2026` ([#54](https://github.com/klexical/openRS_/issues/54))
- Removed dead code: unused `frs_handle_settings_post()` and `CAN_TX_HOOK` lambda ([#62](https://github.com/klexical/openRS_/issues/62), [#63](https://github.com/klexical/openRS_/issues/63))
- `nvs_flash_init` patch now uses regex to target only the call inside `app_main()` ([#64](https://github.com/klexical/openRS_/issues/64))
- Python path detection uses `command -v` instead of hardcoded macOS path ([#65](https://github.com/klexical/openRS_/issues/65))
- Partition table comment corrected to "Single 2MB OTA slot" ([#66](https://github.com/klexical/openRS_/issues/66))

### Pro Target (Pending Hardware Test)
- Pro profile created targeting wican-fw `v4.48p` (ESP32-S3, 16MB flash, 8MB PSRAM)
- All patches (focusrs include, init, CAN RX, CAN TX, REST API, SLCAN probe) have matching anchors in v4.48p
- CAN TX registration uses the same `wc_mdns_init` anchor as USB (confirmed at line 1109 of Pro main.c)
- WebSocket probe skipped for Pro (universal SLCAN probe covers TCP transport)

---

## v1.3 — Initial release

- Drive mode read/write via CAN 0x1B0 button simulation
- ESC control (On / Sport / Off)
- Launch Control enable/disable
- Auto Start/Stop kill
- BLE GATT transport
- Battery protection with configurable voltage threshold
- REST API (`GET /api/frs`, `POST /api/frs`)
- NVS persistence for all settings
- AP branding: `openRS_XXXXXX`
