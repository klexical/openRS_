# Changelog

All notable changes to openRS are documented here.

## [2.6.0] — 2026-03-01

### Added
- **Smart auto-connect** — service auto-starts connection on launch when already on a WiFi network; `ConnectivityManager.NetworkCallback` also triggers a fresh attempt whenever WiFi is (re)gained
- **Exponential backoff with max 3 attempts** — on connection failure the app waits 5 s → 15 s → 30 s between retries before giving up, eliminating the infinite reconnect loop
- **Idle state** (`WiCanConnection.State.Idle`) — after 3 consecutive failed TCP connections the service stops retrying and emits Idle; `VehicleState.isIdle` propagates this to the UI
- **WiFi gating** — connection attempts are blocked when the device has no active WiFi transport (mobile data only); `startConnection()` returns early with a "No WiFi" notification
- **`reconnect()` method** on `CanDataService` — resets attempt counter and starts a fresh connection; called from the header retry button
- **Three-state header badge** — `● LIVE` (green) when connected, `⊙ RETRY` (gold) when idle, `○ OFFLINE` (red) otherwise; tapping any badge performs the correct action

### Changed
- `WiCanConnection` constructor parameter `reconnectDelaySec` replaced with `maxRetries: Int = 3` and internal backoff constants (`BACKOFF_MS`, `RECONNECT_DELAY_MS`)
- `CanDataService` now auto-starts on `onCreate` (WiFi-gated) instead of requiring a manual button tap every launch
- `stopConnection()` also clears `isIdle` on `VehicleState`
- Header connect button label changed from `○ OFFLINE` / `● CONNECTED` to the new three-state labels

### Fixed
- Continuous connect/disconnect notification spam when MeatPi is not present

## [2.5.0] — 2026-03-01

### Added
- **TPMS+ screen** — Real tire pressure (PSI) via BCM Mode 22 (PIDs 0x2813–0x2816)
- **Tire temperature** — Experimental support via PIDs 0x2823–0x2826
- **AFR actual/desired** — Wideband AFR from PCM (0xF434/0xF444) with lambda
- **Electronic Throttle Control** — ETC actual vs desired angle
- **Throttle Inlet Pressure** — TIP actual vs desired (kPa → PSI)
- **Wastegate Duty Cycle** — WGDC desired percentage
- **Variable Cam Timing** — VCT intake and exhaust angles
- **Oil life percentage** — via PCM PID 0x054B
- **Knock correction** — Ignition correction cylinder 1
- **Multi-ECU header management** — Automatic ATSH switching for PCM (0x7E0) and BCM (0x726)
- **Priority-based PID scheduling** — 4-tier system (1/2/3/6) for bandwidth optimization
- TPMS tab on phone UI with 4-corner layout and low-pressure warnings
- TPMS screen on Android Auto with pressure spread indicator
- Enhanced TUNE screen with all new parameters

### Changed
- `ObdPids.kt` completely rewritten — expanded from 13 to 33 PIDs
- `WiCanConnection.kt` now tracks current ECU header and batches by ECU
- `CanDataService.kt` merges 20 additional OBD fields into vehicle state
- `TuneScreen.kt` (AA) redesigned with AFR, ETC, TIP, WGDC, VCT rows

## [2.0.0] — 2026-02-28

### Added
- Hybrid ATMA + OBD polling architecture (150ms sniff + 100ms query)
- Android Auto support with 4 screens (DASH, AWD, PERF, TUNE)
- 13 OBD PIDs (Mode 1 + Mode 22)
- Phone UI with 5 tabs via Jetpack Compose
- Foreground service for persistent CAN connection
- Peak tracking for boost, RPM, G-force with reset

### Architecture
- Time-sliced approach gives 4 Hz OBD + continuous CAN data
- Single WiCAN adapter, no Bluetooth pairing required

## [1.0.0] — 2026-02-27

### Added
- Initial CAN bus sniffing via ATMA
- 16 CAN frame decoders (RPM, boost, speed, AWD, G-force, etc.)
- Basic phone UI
- WiCAN TCP connection with auto-reconnect
