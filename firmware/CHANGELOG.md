# openrs-fw Changelog

All notable changes to the openrs-fw firmware are documented here.

---

## v1.5 — 2026-03-18

### Fixed (rc.5 — WiFi routing, drive mode bit, diagnostic traceability)
- **Drive mode button bit reverted to 0x04 (bit 2)** — re-analysis of three SLCAN diagnostic sessions (2026-03-21) with exclusively physical button presses confirmed the actual drive mode button is **bit 2 (`0x04`)** on 0x305 byte 4. Physical presses consistently produce `0x0C`/`0x0F` values with a ~4-5 s delay to mode change. Bit 4 (`0x10`) is a BCM status indicator for the mode selector GUI, not the button input. Reverts the incorrect rc.5 change.
- **Sport/Track disambiguation missing from firmware** — 0x1B0 reports nibble=1 for both Sport and Track. Added 0x420 parsing (`mode_420_detail` byte7 bit0: 0=Sport, 1=Track) to firmware CAN decoder, matching the Android app's two-frame resolution. Prevents wrong cycle-distance calculation when car is in Track but firmware thinks Sport.
- **ESC injection enhanced logging** — added diagnostic logging to `frs_set_esc()` entry point (pending state, frame validity, current mode) to trace why ESC injection was never observed in diagnostic logs.
- **Firmware version string now includes RC suffix** — `OPENRS_FW_VERSION` changed from `"v1.5"` to `"v1.5-rc.5"` so the Android app's diagnostic log can identify exactly which firmware build is running. Eliminates the ambiguity between rc.4 and rc.5 that complicated the 2026-03-21 diagnostic analysis.

### Fixed (rc.4 — full repo audit hardening)
- **CAN TX shim missing DLC bounds check** — `openrs_can_tx` in `apply_patches.py` did not validate `dlc <= 8` before `memcpy(msg.data, data, dlc)`. A malformed call could overflow `msg.data`. Now clamps to 8 and copies only `msg.data_length_code` bytes.
- **Mutex creation not guarded** — `configASSERT(s_state_mutex)` in `frs_init()` is a no-op in release builds. If `xSemaphoreCreateMutex()` returns NULL, subsequent operations would crash. Now uses explicit NULL check with `ESP_LOGE` + `abort()`.
- **NVS write failures silently ignored** — `frs_nvs_save_boot_mode()`, `frs_nvs_save_esc()`, `frs_nvs_save_lc()`, `frs_nvs_save_ass_kill()`, and `frs_nvs_save_sleep_threshold()` now log failures with `esp_err_to_name()`.
- **TWAI RECOVERING timeout not logged** — the RECOVERING branch in `frs_attempt_recovery()` exited the retry loop silently on timeout, unlike the BUS_OFF branch. Now logs `ESP_LOGE`.
- **UDS RX queue silently dropped responses** — `xQueueSend(..., 0)` used zero timeout; under heavy 0x768 traffic, responses were lost. Now uses `pdMS_TO_TICKS(50)` timeout and logs drops with CAN ID.
- **`frs_uds_send` missing NULL check** — added `!payload` guard alongside existing `!tx` check.
- **POST handler missing explicit null-termination** — `httpd_req_recv()` result was not explicitly null-terminated before `cJSON_Parse()`. Safe due to zero-init, but now explicit.

### Fixed (rc.3 — CAN TX robustness, ESC injection, drive mode timing)
- **Drive mode freezes permanently after button injection** — the ESP32 TWAI controller could enter bus-off state during 0x305 frame injection (same-ID data-phase collisions with the car's broadcast). Once in bus-off, ALL CAN TX and RX stopped with no recovery path. Added automatic bus-off detection via `twai_get_status_info()` and recovery via `twai_initiate_recovery()` + `twai_start()`. The CAN bus now self-heals within 500ms of a bus-off event.
- **ESC button presses never registered from the app** — the firmware injected 0x260 frames at 80ms intervals (12.5 Hz), but the BCM broadcasts 0x260 at 40 Hz. The ABS module saw the "pressed" bit in only ~24% of frames — too brief to register. Increased ESC/ASS injection rate to **10ms intervals (100 Hz)**, outpacing the BCM so the ABS module sees the pressed bit in >60% of frames on the bus. Matches the "spam" approach confirmed working by the focusrs.org CAN decoding project.
- **Track mode unreachable — car showed Sport instead** — the flat 150ms inter-press delay was too fast for the cluster's mode selector GUI. The activation press opens the GUI (~300ms render time), but press 2 arrived at 150ms before the GUI was ready, causing missed inputs. Replaced with two-stage timing: **500ms after activation press** (GUI open), **300ms between cycle presses** (mode transition animation). Normal→Track now completes in ~1.6s vs ~1.2s previously, but reliably hits the target mode.
- **CAN TX errors silently ignored** — `s_can_tx()` return value was never checked. Failed transmissions (error-passive, bus-off, timeout) were invisible. Now every TX attempt is checked; consecutive failures are counted and logged with CAN ID and error code. After 5 consecutive failures, the button sequence aborts early and bus-off recovery is triggered.

### Added (rc.3)
- **UDS ABS module probe** — on boot (after CAN templates captured), the firmware sends a `TesterPresent` (0x3E) request to the ABS/AdvanceTrac module at CAN ID 0x760 and probes ESC-related DIDs (0xDD01, 0xDD04, 0x4003) via `ReadDataByIdentifier` (0x22). Responses are logged to serial for offline analysis. This is Phase 1 discovery for future UDS-based ESC control as an alternative to button injection.
- **`focusrs_uds.c` / `focusrs_uds.h`** — minimal ISO-TP single-frame UDS transport layer. Supports send (max 7-byte payload), blocking receive with timeout, and a FreeRTOS queue for async response routing from the CAN RX callback.
- **CAN health in REST API** — `GET /api/frs` response now includes `canTxErrors` (consecutive failure count), `canBusOff` (boolean, true if TWAI is in bus-off), and `absReachable` (boolean, true if ABS module responded to UDS probe).

### Changed (rc.3)
- Drive mode inter-press timing changed from flat 150ms to two-stage: 500ms activation + 300ms cycle (`FRS_DM_ACTIVATION_DELAY_MS`, `FRS_DM_CYCLE_DELAY_MS`)
- ESC and ASS button injection interval changed from 80ms to 10ms (`FRS_ESC_BTN_TX_INTERVAL_MS`). Short press duration is 300ms (30 frames), long press remains 5000ms (500 frames).
- `frs_send_button()` and `frs_send_button_long()` now accept `interval_ms` and `count`/`duration_ms` parameters instead of using hardcoded constants, allowing different rates for 0x305 (80ms) and 0x260 (10ms).
- REST API `GET /api/frs` JSON buffer increased from 384 to 512 bytes to accommodate new fields.

### Fixed (rc.2 — Pro-specific)
- **Data race on dual-core ESP32-S3** — all `frs_set_*` functions, `s_pending_mode`, `s_pending_esc`, and CAN template reads now protected by `s_state_mutex`. On the Pro (ESP32-S3, dual-core), CAN RX on Core 0 and REST API handler on Core 1 could corrupt `s_state` simultaneously — worst case: wrong number of drive mode presses or torn template reads during CAN TX. Button send helpers (`frs_send_dm_button`, `frs_send_esc_short/long`, `frs_send_ass_button`) now copy templates under mutex before transmitting. ([#78](https://github.com/klexical/openRS_/issues/78))
- **NVS value clamping** — `boot_mode`, `esc_mode`, and `sleep_threshold_mv` are now range-checked on NVS load. If flash is corrupted, out-of-range values are rejected with a warning instead of causing undefined behavior (e.g. `boot_mode > 3` would index past `can_to_pos[4]`). ([#12](https://github.com/klexical/openRS_/issues/12))
- **REST API CORS wildcard removed** — `Access-Control-Allow-Origin: *` stripped from both GET and POST `/api/frs` handlers. The Android app uses direct HTTP (not a browser), so CORS headers were unnecessary and allowed any webpage on a device connected to the WiCAN WiFi to silently POST drive mode / ESC changes. ([#79](https://github.com/klexical/openRS_/issues/79))
- **Pro profile missing `frs_boot_apply()`** — added to `pro.py` `can_tx_register_replacement` so the Pro auto-applies NVS settings (drive mode, ESC, ASS kill) after CAN templates are captured at boot, matching USB behavior.

### Fixed (rc.1 — drive mode + ESC button simulation)
- **Drive mode button simulation uses correct CAN ID** — changed from 0x1B0 (status frame, ignored by car) to **0x305 byte 5 bit 2** (actual button input). 0x1B0 is the AWD status/torque output frame — the car's drive mode controller does not listen for button input on it. Confirmed via SLCAN log on 2018 Focus RS: steady-state byte 5 = `0x08`, pressed = `0x0C`. ([#101](https://github.com/klexical/openRS_/issues/101))
- **Drive mode activation press** — the Focus RS instrument cluster requires an activation press before cycling begins. First press opens the mode selector GUI on the cluster (no mode change); subsequent presses cycle through N→S→T→D→N. Firmware now sends **1 activation press + N cycle presses** automatically, so a single tap in the app reaches the desired mode without double-tapping. Confirmed via car test: user had to tap twice in rc.1, single tap works after this fix.
- **ESC Off requires long press, not multiple short presses** — the Focus RS ESC button behaviour is: short press (~240ms) toggles **On ↔ Sport**; long press (~5s hold) activates **ESC Off**. The v1.5-rc.1 implementation sent multiple short presses to cycle On→Sport→Off, which actually toggled On→Sport→On (no net change). SLCAN log confirmed: ESC briefly changed to Sport then reverted to On within 12 seconds. Now uses a 5-second sustained button hold for ESC Off.
- `build.sh` doc reference path corrected from `firmware/docs/firmware-update.md` to `android/docs/firmware-update.md` ([#11](https://github.com/klexical/openRS_/issues/11))

### Added (rc.1)
- **ESC mode write via CAN** — `frs_set_esc()` fully implemented (was a stub in v1.4). Simulates the ESC button on **CAN ID 0x260, byte 6 bit 4** (`data[5] |= 0x10`). Short press for On ↔ Sport toggle, long press (~5s) for Off. Cycle logic handles all transitions: On→Sport (short), On→Off (long), Sport→On (short), Sport→Off (long), Off→On (short), Off→Sport (short + short). ([#98](https://github.com/klexical/openRS_/issues/98))
- **Auto Start/Stop kill via CAN** — simulates the ASS button on **CAN ID 0x260, byte 1 bit 0** (`data[0] |= 0x01`). Sends a single short button press when `assKill` is enabled via REST API. Shares the 0x260 template with ESC. ([#100](https://github.com/klexical/openRS_/issues/100))
- **0x305 template capture** — captures drive mode button frame from the live CAN bus at runtime. Only captures when the button is NOT pressed (bit 2 clear) to ensure a clean template. Frame rate ~10 Hz, template valid within 1 second of CAN bus activity.
- **0x260 template capture** — captures body control frame from the live CAN bus at runtime. Only captures when neither ESC nor ASS button bits are set. Frame rate ~50 Hz, template valid almost immediately.
- **Boot apply task** (`frs_boot_apply()`) — spawns a background FreeRTOS task on startup that waits up to 10 seconds for CAN templates to be captured, then automatically applies persisted settings: drive mode (if different from current), ESC mode (if different from current), and ASS kill (if enabled). Called from `app_main` after CAN TX registration.
- **`boot_esc` field** in `frs_state_t` and REST API `GET /api/frs` response — tracks the desired ESC mode from NVS separately from the live CAN value (`esc_mode`), same pattern as `boot_mode` vs `drive_mode`. Prevents the boot apply task from comparing stale NVS data against CAN-overwritten state.
- **`frs_send_button_long()` helper** — generic long-press CAN button simulation. Holds a bit set in a template frame for a configurable duration, sending frames at 80ms intervals. Used for ESC Off (5000ms hold = ~62 frames).
- **Generic `frs_send_button()` helper** — reusable short-press CAN button simulation. Sends 3 frames at 80ms intervals (~240ms hold), matching the timing of physical button presses observed in SLCAN logs.

### Changed (rc.2)
- **Drive mode inter-press delay tightened to 150ms** — SLCAN log analysis showed the car processes consecutive mode changes with as little as 76ms between transitions. Reduced from 500ms for fast switching. Normal→Sport completes in ~0.9s, worst case (4 presses) in ~1.9s.

### Changed (rc.1)
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

## v1.3 — Initial public release

- Drive mode read/write via CAN 0x1B0 button simulation
- ESC control (On / Sport / Off)
- Launch Control enable/disable
- Auto Start/Stop kill
- BLE GATT transport
- Battery protection with configurable voltage threshold
- REST API (`GET /api/frs`, `POST /api/frs`)
- NVS persistence for all settings
- AP branding: `openRS_XXXXXX`

---

## v1.2 — 2026-03-03

### Fixed
- **Drive mode read used wrong CAN ID** — firmware read drive mode from `0x17E` (button event frames, only present during presses) instead of `0x1B0` (steady-state AWD status, always broadcasting). Corrected to `0x1B0` to match the Android app's passive decoder.

---

## v1.1 — 2026-03-01

### Added
- **`OPENRS?` probe response** — firmware responds to the Android app's `OPENRS?\r` SLCAN probe with `OPENRS:v1.1`, enabling the app to detect openRS_ firmware vs stock WiCAN and unlock the drive mode / ESC feature buttons on the MORE tab.
