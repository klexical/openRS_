# Changelog

All notable changes to the openRS_ Android app are documented here.
Firmware changes are tracked separately in [firmware releases](https://github.com/klexical/openRS_/releases/tag/fw-v1.0.0).

---

## [v1.1.0] — 2026-03-04

### Changed — Architecture (breaking)
- **WebSocket SLCAN replaces ELM327 TCP** — the app now connects to the WiCAN via WebSocket on port 80 (`ws://192.168.80.1:80/ws`) using the SLCAN protocol instead of ELM327 TCP on port 3333. This provides passive monitoring of the full CAN bus at ~2100 fps vs ~12 fps previously. No WiCAN configuration change is required; the WebSocket endpoint is available in stock WiCAN firmware.
- **Preference key renamed** — the saved port preference key changed from `wican_port` to `wican_port_ws` to prevent old cached ELM327 port (3333) from being used after upgrade.

### Added — Settings
- Full settings dialog accessible via gear icon in the header
- **Speed unit** — MPH or KPH
- **Temperature unit** — °F or °C
- **Boost pressure unit** — PSI, BAR, or kPa
- **Tire pressure unit** — PSI or BAR
- **Low tire pressure warning threshold** — user-defined PSI (default 30 PSI)
- **Keep screen on** — prevents screen sleep while connected (default: on)
- **Auto-reconnect** — automatically reconnects after a dropped connection (default: on)
- **Reconnect interval** — configurable delay in seconds (default: 10s)
- All settings persist across app restarts via SharedPreferences

### Added — CAN Decoders
- **TPMS (0x340)** — tire pressures LF/RF/LR/RR decoded directly from bytes 2-5 in PSI; this frame is broadcast on MS-CAN and bridged to HS-CAN by the Gateway Module (GWM). No OBD queries required.
- **Ambient temperature (0x1A4)** — byte 4 signed × 0.25 °C, MS-CAN bridged
- **Barometric pressure** — added to existing `0x090` frame (byte 2 × 0.5 kPa)

### Fixed — CAN Decoders
- **Drive mode (0x1B0)** — corrected bit extraction to `(byte6 ushr 4) & 0x0F`; was reading wrong bit positions causing drive mode to always show Normal regardless of actual mode
- **E-brake (0x0C8)** — corrected bit mask to `(byte3 & 0x40) != 0`
- **AWD max torque (0x2C0)** — fixed formula to prevent negative values
- Removed `0x0B0` (was producing impossible G-force values — not a confirmed dynamics frame)
- Removed steering angle and brake pressure from `0x080` (not present in this frame per DigiCluster)
- All formulas re-validated against DigiCluster `can0_hs.json` and `can1_ms.json`

### Added — openRS_ firmware detection
- On connection, the app sends `OPENRS?\r` over WebSocket and checks the first response for `OPENRS:<version>`. Stock WiCAN firmware produces no response; openRS_ firmware confirms itself with its version string.
- CTRL tab feature buttons (Launch Control, Auto S/S Kill) are unlocked when openRS_ firmware is detected
- "Coming soon" snackbar only shown when running stock WiCAN firmware

### Added — Diagnostics (DIAG tab)
- New **DIAG** tab (renamed from DEBUG) with full session diagnostics
- `DiagnosticLogger` — collects frame inventory, decode trace (last 500 events), session events, and FPS timeline
- `DiagnosticExporter` — packages all data into a ZIP (human-readable `summary.txt` + machine-readable `detail.json`) and shares via Android share sheet
- **⬆ CAPTURE & SHARE SNAPSHOT** button for one-tap export
- Frame inventory shows every CAN ID seen, frame count, last raw hex, decoded values, and any validation warnings
- Validation engine flags physically impossible values (e.g. RPM > 9000, oil temp < −50°C)

### Added — Unit-aware UI
- All pages (DASH, PERF, TEMPS, TUNE, TPMS) now display values in the user's preferred units
- TPMS tire pressure low-alert threshold is now the user-configured value (not hardcoded 30 PSI)

### Added — Screen management
- `view.keepScreenOn` tied to `prefs.screenOn && vs.isConnected` — screen stays on while driving when enabled in settings

### Removed
- `ObdPids.kt` — dead code; OBD polling path fully replaced by passive WebSocket SLCAN

---

## [v1.0.2] — 2026-03-03

### Fixed
- **ATMA frame parsing** — WiCAN ELM327 outputs CAN frames with spaces (e.g. `1B0 00 11 22 33 44 55 66 77`); the parser now strips spaces before hex validation so all gauge telemetry is received and decoded correctly

---

## [v1.0.1] — 2026-03-03

### Added
- **Smart auto-connect** — service auto-starts on launch when already on WiFi; `ConnectivityManager.NetworkCallback` triggers a fresh connection attempt whenever WiFi is (re)gained
- **Exponential backoff with max 3 attempts** — on failure the app waits 5 s → 15 s → 30 s between retries then gives up (was: infinite retry loop)
- **Idle state** — after 3 consecutive failed TCP connections the service stops retrying; `VehicleState.isIdle` propagates this to the UI
- **WiFi gating** — connection attempts are skipped when on mobile data only; shows "No WiFi" notification
- **`reconnect()` method** on `CanDataService` — resets attempt counter and starts fresh; called from the header RETRY badge
- **Three-state header badge** — `● LIVE` (green) when connected, `⊙ RETRY` (gold) when idle, `○ OFFLINE` (red) otherwise; tapping any badge performs the correct action

### Fixed
- Continuous connect/disconnect notification spam when MeatPi is not present or phone is not on the WiCAN network

---

## [v1.0.0] — 2026-03-01

### Added
- **TPMS screen** — Real tire pressure (PSI) and temperature via BCM Mode 22
- **AFR actual/desired** — Wideband lambda from PCM with AFR display
- **Electronic Throttle Control** — ETC actual vs desired angle
- **Throttle Inlet Pressure** — TIP actual vs desired (kPa → PSI)
- **Wastegate Duty Cycle** — WGDC desired percentage
- **Variable Cam Timing** — VCT intake and exhaust angles
- **Oil life percentage** and **knock correction** via PCM
- **Multi-ECU header management** — Automatic ATSH switching (PCM `0x7E0`, BCM `0x726`)
- **CTRL screen** — Drive mode (N/S/T/D), ESC toggle, Launch Control, ASS kill, connection info
- **Settings dialog** — WiCAN host/port configurable from within the app
- **Android Auto support** — full Compose UI mirroring the phone app (7 screens)
- **openRS_ branding** — Nitrous Blue / Frost White theme, launcher icon, app name
- **Edge-to-edge layout** — proper status bar and navigation bar inset handling (Android 15+)
- Foreground service with persistent notification and peak tracking (boost, RPM, G-force)
- 33 OBD PIDs across Mode 1 and Mode 22 (PCM + BCM)
- 16+ real-time CAN frame decoders (RPM, boost, speed, AWD split, G-forces, wheel speeds, drive mode, ESC, gear, TPMS)

### Architecture
- Hybrid ATMA + OBD time-sliced polling — continuous CAN sniffing with 4 Hz OBD queries
- Single WiCAN-USB-C3 adapter via ELM327 TCP on port 3333
