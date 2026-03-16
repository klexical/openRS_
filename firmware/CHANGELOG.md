# openrs-fw Changelog

All notable changes to the openrs-fw firmware are documented here.

---

## v1.5 — 2026-03-16

### Fixed (Pro-specific)
- **Data race on dual-core ESP32-S3** — all `frs_set_*` functions, `s_pending_mode`, `s_pending_esc`, and CAN template reads now protected by `s_state_mutex`. On the Pro (ESP32-S3, dual-core), CAN RX on Core 0 and REST API handler on Core 1 could corrupt `s_state` simultaneously — worst case: wrong number of drive mode presses or torn template reads during CAN TX. Button send helpers (`frs_send_dm_button`, `frs_send_esc_short/long`, `frs_send_ass_button`) now copy templates under mutex before transmitting. ([#78](https://github.com/klexical/openRS_/issues/78))
- **NVS value clamping** — `boot_mode`, `esc_mode`, and `sleep_threshold_mv` are now range-checked on NVS load. If flash is corrupted, out-of-range values are rejected with a warning instead of causing undefined behavior (e.g. `boot_mode > 3` would index past `can_to_pos[4]`). ([#12](https://github.com/klexical/openRS_/issues/12))
- **REST API CORS wildcard removed** — `Access-Control-Allow-Origin: *` stripped from both GET and POST `/api/frs` handlers. The Android app uses direct HTTP (not a browser), so CORS headers were unnecessary and allowed any webpage on a device connected to the WiCAN WiFi to silently POST drive mode / ESC changes. ([#79](https://github.com/klexical/openRS_/issues/79))
- **Pro profile missing `frs_boot_apply()`** — added to `pro.py` `can_tx_register_replacement` so the Pro auto-applies NVS settings (drive mode, ESC, ASS kill) after CAN templates are captured at boot, matching USB behavior.

### Fixed
- **Drive mode button simulation uses correct CAN ID** — changed from 0x1B0 (status frame, ignored by car) to **0x305 byte 5 bit 2** (actual button input). 0x1B0 is the AWD status/torque output frame — the car's drive mode controller does not listen for button input on it. Confirmed via SLCAN log on 2018 Focus RS: steady-state byte 5 = `0x08`, pressed = `0x0C`. ([#101](https://github.com/klexical/openRS_/issues/101))
- **Drive mode activation press** — the Focus RS instrument cluster requires an activation press before cycling begins. First press opens the mode selector GUI on the cluster (no mode change); subsequent presses cycle through N→S→T→D→N. Firmware now sends **1 activation press + N cycle presses** automatically, so a single tap in the app reaches the desired mode without double-tapping. Confirmed via car test: user had to tap twice in rc.1, single tap works after this fix.
- **ESC Off requires long press, not multiple short presses** — the Focus RS ESC button behaviour is: short press (~240ms) toggles **On ↔ Sport**; long press (~5s hold) activates **ESC Off**. The v1.5-rc.1 implementation sent multiple short presses to cycle On→Sport→Off, which actually toggled On→Sport→On (no net change). SLCAN log confirmed: ESC briefly changed to Sport then reverted to On within 12 seconds. Now uses a 5-second sustained button hold for ESC Off.
- `build.sh` doc reference path corrected from `firmware/docs/firmware-update.md` to `android/docs/firmware-update.md` ([#11](https://github.com/klexical/openRS_/issues/11))

### Added
- **ESC mode write via CAN** — `frs_set_esc()` fully implemented (was a stub in v1.4). Simulates the ESC button on **CAN ID 0x260, byte 6 bit 4** (`data[5] |= 0x10`). Short press for On ↔ Sport toggle, long press (~5s) for Off. Cycle logic handles all transitions: On→Sport (short), On→Off (long), Sport→On (short), Sport→Off (long), Off→On (short), Off→Sport (short + short). ([#98](https://github.com/klexical/openRS_/issues/98))
- **Auto Start/Stop kill via CAN** — simulates the ASS button on **CAN ID 0x260, byte 1 bit 0** (`data[0] |= 0x01`). Sends a single short button press when `assKill` is enabled via REST API. Shares the 0x260 template with ESC. ([#100](https://github.com/klexical/openRS_/issues/100))
- **0x305 template capture** — captures drive mode button frame from the live CAN bus at runtime. Only captures when the button is NOT pressed (bit 2 clear) to ensure a clean template. Frame rate ~10 Hz, template valid within 1 second of CAN bus activity.
- **0x260 template capture** — captures body control frame from the live CAN bus at runtime. Only captures when neither ESC nor ASS button bits are set. Frame rate ~50 Hz, template valid almost immediately.
- **Boot apply task** (`frs_boot_apply()`) — spawns a background FreeRTOS task on startup that waits up to 10 seconds for CAN templates to be captured, then automatically applies persisted settings: drive mode (if different from current), ESC mode (if different from current), and ASS kill (if enabled). Called from `app_main` after CAN TX registration.
- **`boot_esc` field** in `frs_state_t` and REST API `GET /api/frs` response — tracks the desired ESC mode from NVS separately from the live CAN value (`esc_mode`), same pattern as `boot_mode` vs `drive_mode`. Prevents the boot apply task from comparing stale NVS data against CAN-overwritten state.
- **`frs_send_button_long()` helper** — generic long-press CAN button simulation. Holds a bit set in a template frame for a configurable duration, sending frames at 80ms intervals. Used for ESC Off (5000ms hold = ~62 frames).
- **Generic `frs_send_button()` helper** — reusable short-press CAN button simulation. Sends 3 frames at 80ms intervals (~240ms hold), matching the timing of physical button presses observed in SLCAN logs.

### Changed
- **Drive mode inter-press delay tightened to 150ms** — SLCAN log analysis showed the car processes consecutive mode changes with as little as 76ms between transitions. Reduced from 500ms for fast switching. Normal→Sport completes in ~0.9s, worst case (4 presses) in ~1.9s.
- Drive mode sends **1 activation + N cycle presses** (total = 1 + cycle distance). Physical button cycle: N→S→T→D→N. CAN values mapped to cycle positions via lookup table: `{0→0, 1→1, 2→3, 3→2}`.
- ESC mode uses **short press** (3 frames × 80ms = ~240ms) for On/Sport toggle and **long press** (5000ms continuous) for Off
- `frs_set_esc()` now creates an async FreeRTOS task with state-aware transition logic instead of being a stub that only saved to NVS
- `frs_set_ass_kill()` now sends a CAN button press when enabling ASS kill, in addition to persisting to NVS
- REST API `GET /api/frs` JSON response includes new `bootEsc` field (integer, 0=On 1=Sport 2=Off)
- Removed old 0x1B0 button simulation constants (`FRS_BUTTON_PRESSED`, `FRS_BUTTON_RELEASED`, `frame_1b0_template`) — replaced by 0x305 and 0x260 template infrastructure

---

## v1.4 — 2026-03-15

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
- WiFi password patch fixed — Python raw string `r'\"@meatpi#\"'` didn't match the escaped C string; corrected to proper escape sequence
- `GET /api/frs` now includes `sleepMv` field (sleep threshold in millivolts)
- `POST /api/frs` `sleepVoltage` persistence fixed — value was passed as millivolts but the setter expected volts, so the 10–15V range check silently rejected it; now converts mV→V before calling the setter
- `build.sh` resilience — `export.sh` failure from `ruamel.yaml` namespace issues on macOS no longer kills the build
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
