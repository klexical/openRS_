# Changelog

All notable changes to the openRS_ Android app are documented here.
Firmware changes are tracked separately in [firmware releases](https://github.com/klexical/openRS_/releases/tag/fw-v1.0.0).

---

## [v1.1.5] — 2026-03-01

### Added — SLCAN raw frame log (Option C)
- Every CAN frame received during a session is now written in real-time to a `slcan_log_*.log` file inside the diagnostic ZIP.
- Format is standard **candump** (compatible with SavvyCAN, Kayak, python-can): `(relative_seconds) can0 ID#DATA`.
- This makes it possible to import the log into SavvyCAN or Kayak after a drive and replay or decode frames offline — no more relying on a single end-of-session snapshot.
- Logging is capped at **2 000 000 lines** (~83 min at 400 fps average). A session event is emitted when the cap is reached.
- The ZIP share email now mentions the SLCAN file and its frame count.

### Added — Per-ID first/last/changed tracking + periodic samples (Option B)
- `FrameInfo` in the diagnostic logger now stores:
  - `firstRawHex` — the raw bytes seen on the **very first** observation of each CAN ID.
  - `hasChanged` — a `true/false` flag indicating whether the frame bytes **ever changed** during the session. Static IDs (e.g. configuration frames that never move) are clearly distinguished from dynamic signals (RPM, boost, temperatures).
  - `periodicSamples` — up to **10 raw-hex snapshots**, taken once every **30 seconds** per ID, across the full session. These show how a signal's bytes evolved over time during a drive.
- The human-readable summary now includes a **PERIODIC SAMPLES** section listing all dynamic (changed) IDs with their timestamped snapshots, making it possible to see values from mid-drive rather than only the parked end-state.
- The JSON detail file now includes `firstRawHex`, `hasChanged`, and a `periodicSamples` array per frame ID.

### Increased — Diagnostic capacity limits (previously caused data loss on longer drives)
| Buffer | Old limit | New limit | Coverage at typical rates |
|---|---|---|---|
| Decode trace | 500 events | **10 000 events** | ~100 s of decoded frames |
| Session events | 300 | **1 000** | Full-session event history |
| FPS timeline | 200 samples | **7 200 samples** | **2 hours** at 1 sample/s |

---

## [v1.1.4] — 2026-03-04

### Fixed — Boost formula (critical: was reading engine oil temp as boost)
- CAN frame `0x0F8` (DBC: `PCMmsg07`) carries **three** signals, not two.
  - `byte[1]` = **EngineOilTemp** — `raw − 50 °C` ← was being decoded as boost (wrong!)
  - `byte[5]` = **Boost** — `raw × 0.01 bar gauge`; stored as absolute kPa = `raw + barometricPressure`
  - `byte[7]` = **PTUTemp** — `raw − 60 °C` ← was labelled "oilTemp" (wrong!)
- v1.1.3 accidentally moved the boost read to `byte[1]`, which is actually engine oil temp. This update restores `byte[5]` as boost (now gauge + baro = absolute) and correctly separates all three signals per the RS_HS.dbc.
- At idle `byte[5] = 0x00` = 0 kPa gauge = 0 PSI boost (no turbo activity) — this is now correctly displayed as ~0 PSI instead of −14.7 PSI.

### Fixed — Engine oil temp was wrong (was showing PTU temp)
- Oil temp field was reading `byte[7] − 60`, which is the **PTU (transfer case) temperature**.
- Corrected to `byte[1] − 50` (engine oil temp per DBC). PTU temp now correctly reads `byte[7] − 60`.
- Before: "OIL 60°C" was actually PTU temperature. Now both values are correctly separated.

### Fixed — Wheel speed CAN ID wrong (0x215 → 0x190)
- Wheel speeds were mapped to CAN ID `0x215`, which does not exist on the Focus RS HS-CAN bus.
- RS_HS.dbc `ABSmsg03` at ID `0x190` carries all four wheel speeds. This ID was visible in diagnostics (67 743 frames logged) but never decoded.
- Formula corrected to 15-bit Motorola MSB-first, `× 0.011343006 km/h` per DBC (was: 16-bit `− 10 000 × 0.01`).
- FL/FR/RL/RR now correctly decoded from `((data[N] & 0x7F) << 8) | data[N+1]`.

### Added — Intake Air Temperature (IAT) from CAN 0x2F0
- `IntakeAirTemp : 49|10@0+ (0.25,−127)` added alongside coolant in the `0x2F0` decoder.
- Formula: `((data[6] & 0x03) << 8 | data[7]) × 0.25 − 127 °C` (RS_HS.dbc PCMmsg16 verified).
- Displayed in the TEMPS tab alongside coolant.

### Added — Ambient temperature from CAN 0x340 (PCMmsg17)
- RS_HS.dbc `PCMmsg17` at `0x340` carries `AmbientAirTemp : 63|8@0−` in byte 7 (`signed × 0.25 °C`).
- The ambient temp is now decoded alongside the existing TPMS bytes 2-5 decode from the same frame.
- This provides ambient temp data without needing the MS-CAN bus.

### Added — RDU temperature via AWD module Mode 22 polling
- Active ISO-TP query now sent to AWD module (`0x703`) every 30 s, polling PID `0x1E8A` (RDU oil temp).
- Formula: `B4 − 40 °C` (source: research/exportedPIDs.txt + DaftRS log_awd_temp.py).
- Response intercepted on CAN ID `0x70B`. RDU temp was previously read from `0x2C0` byte 3 (always 0 at idle = −40°C); that was wrong — the signal is not in any passive broadcast.
- `rduTempC` default changed from `0.0` to `−99.0` (consistent with other "not yet polled" fields).

### Removed — Dead PTU decoder on 0x2C2
- `ID_PTU_TEMP = 0x2C2` never appears on the HS-CAN bus. PTU temperature is now correctly sourced from `0x0F8` byte 7. The dead decoder and constant have been removed.

---

## [v1.1.3] — 2026-03-04

### Fixed — Boost sensor reading always −14.7 PSI
- Boost (MAP kPa absolute) was being decoded from byte 5 of CAN frame `0x0F8`, which is always `0x00` on the Focus RS MK3. Diagnostic log analysis confirmed byte 1 carries the manifold absolute pressure and tracks correctly across sessions: `0x3D` (61 kPa → −5.8 PSI at cold idle) and `0x57` (87 kPa → −2.1 PSI at warm idle). Formula corrected to `ubyte(data, 1)`.

### Fixed — Firmware detection showing "WiCAN stock" when openRS_ firmware is installed
- The probe (`OPENRS?\r`) response was checked only on the **very first** WebSocket frame. At ~2000 frames/sec, the first frame is almost always a CAN frame, not the probe reply, so it was immediately classified as stock. Now checks every incoming frame until the probe response is received OR 20 normal SLCAN frames pass without a response, at which point it falls back to "WiCAN stock".

### Fixed — 34 duplicate boost validation warnings in diagnostic log
- The validation issue string included the specific kPa value (`boostKpa=37 — too low`), causing the `LinkedHashSet` to grow to 34 entries for each unique vacuum reading at idle. Changed to a single fixed string (`boostKpa=0 — MAP sensor may be disconnected`) that only fires when MAP reads exactly 0, which is the only truly impossible value for a running engine.

### Added — App version in diagnostic log and share email
- Diagnostic text summary now shows `App: vX.Y.Z (build N)` in the header.
- JSON `meta` block now includes `appVersion` and `appBuild` fields.
- Share email body now includes `App: vX.Y.Z (build N)` for easy version identification when reporting issues.

---

## [v1.1.2] — 2026-03-04

### Fixed — Drive mode off-by-one
- Drive mode was decoded from the **upper nibble** of CAN frame `0x1B0` byte 6 (`ushr 4`), which carries the **previous/transitioning** mode. The correct signal is in the **lower nibble** (`and 0x0F`).
- Root cause confirmed via diagnostic dump: in Track mode the car sends byte 6 = `0x12` — upper nibble 1 (Sport, wrong) / lower nibble 2 (Track, correct). Selecting Drift sent `0x23` — showing Track (upper) instead of Drift (lower).
- Symptom: every mode change showed one mode behind (Normal→Normal ✓, Sport→Normal ✗, Track→Sport ✗, Drift→Track ✗).

### Fixed — TPMS invalid readings at standstill
- TPMS sensors sleep when the vehicle is stationary; stale or noise CAN frames could appear with obviously wrong values (e.g. 67 PSI on a single sensor).
- Added per-sensor valid-range check: only accept readings in **5–60 PSI**. Out-of-range bytes are discarded and the previous stored value is retained for that sensor.
- An all-zero frame (all sensors outside range) no longer resets displayed pressures; last known good values are preserved until the sensors wake up.

### Fixed — Browser emulator not updated alongside v1.1.1 app changes
- DASH tab (phone + AA): new info row added showing **ODO** (odometer km) and **SOC** (battery %) alongside 12V voltage and drive mode — matching the Android app's layout.
- TEMPS tab (phone + AA): two new BCM temperature gauges added — **CABIN** and **BATT TEMP** — with `(BCM)` source labels, showing `--` until the first 30 s poll completes, matching the Android app display.
- AWD tab: PTU and RDU temperatures now use `displayTemp()` + `tempLabel()` to respect the user's °C/°F setting (were hardcoded `toF()°F`).
- Demo state seed updated with representative values for `odometerKm`, `batterySoc`, `cabinTempC`, `batteryTempC`.
- `tempGauge()` helper updated to accept an optional subtitle argument for source labels like `(BCM)` and `(BCM — polling)`.

---

## [v1.1.1] — 2026-03-01

### Added — BCM OBD Mode 22 polling
- Active ISO-TP queries now sent to BCM (CAN address `0x726`) every 30 s via SLCAN, unlocking 4 new data points from the MeatPi Focus RS MK3 vehicle profile:
  - **Odometer** (`0xDD01`) — displayed on DASH tab as `ODO km`
  - **Battery SOC** (`0x4028`) — displayed on DASH tab as `SOC %` (12V start/stop battery)
  - **Battery Temp** (`0x4029`) — displayed on TEMPS tab with BCM label
  - **Cabin Temp** (`0xDD04`) — displayed on TEMPS tab with BCM label
- `WiCanConnection` changed from `flow {}` to `channelFlow {}` to allow OBD poller coroutine to run concurrently with the passive CAN receive loop
- `sendWsText` / `sendWsPong` now use `synchronized(out)` to prevent stream interleaving between OBD poller and keep-alive pong responses
- New `parseBcmResponse()` function decodes ISO-TP single-frame responses on CAN ID `0x72E` using MeatPi-verified formulas

### Fixed — Browser emulator
- Settings overlay moved outside `#phone` container so it renders correctly when the Android Auto tab is active (was previously trapped inside the hidden phone div)
- Android Auto DIAG screen added with full parity to phone DIAG tab (session stats, frame inventory, snapshot button)
- Android Auto `⚙` settings gear icon wired to the shared settings overlay
- Firmware-locked feature toggles in AA CTRL panel now flash red and refuse to toggle when Stock firmware is simulated

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
