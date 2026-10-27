# Changelog

All notable changes to the openRS_ Android app are documented here.
Firmware changes are tracked separately in [firmware releases](https://github.com/klexical/openRS_/releases).

> **Note on tab names:** The app's tab structure has evolved over time. v1.0–v1.1 used DASH/PERF/TEMPS/TUNE/TPMS/CTRL/DIAG. v1.2.0 redesigned to DASH/POWER/CHASSIS/TEMPS/DIAG + System Drawer. v2.0.0 replaced the drawer with a MORE tab (6-tab layout). v2.2.6-rc.5 added a MAP tab for drive tracking (7-tab layout: DASH/POWER/CHASSIS/TEMPS/MAP/DIAG/MORE). v2.2.7-rc.1 restructured to a 5-tab layout (DRIVE/PERF/THERMAL/TRIP/GARAGE) with the "Daylight" UI overhaul. Historical entries below use the tab names that were current at the time of each release.

---

## [v2.2.7] — unreleased

### Added (rc.6 — knock logger)
- **Session knock log** — knock events are now captured whenever the adapter is connected, not only while a drive is recording. Held in memory per session (cap 2,000), shown under a new SESSION scope on the PERF knock card (now SESSION / THIS DRIVE / ALL), and exported in the diagnostic ZIP as `drive_knock_session_*.csv`. Uses the same fresh-only capture as the recorder. (`data/KnockSessionLog.kt`) ([#174](https://github.com/klexical/openRS_/issues/174))
- **% of pulls with knock** — new `PullTracker` counts WOT pulls (throttle ≥ 80 % at ≥ 2,500 RPM for ≥ 2 s; gear-change lifts under 2 s don't split a pull) and the pulls that saw KR > 0. Shown as a PULLS cell on the knock card for every scope, stored per drive, and added to the drive summary. (`data/PullTracker.kt`) ([#174](https://github.com/klexical/openRS_/issues/174))
- **Tap a knock dot for its snapshot** — tapping a scatter point rings it and shows time, cylinder, retard, RPM, boost, IAT, throttle, gear and GPS; draw and hit-test share one projection. (`ui/KnockScatterMath.kt`) ([#174](https://github.com/klexical/openRS_/issues/174))

### Added (rc.6 — boost sweep)
- **Live BOOST SWEEP chart on POWER** — THROTTLE & BOOST now plots TIP target (dashed) vs actual plus WGDC % across RPM (1,500–7,000, 250-rpm bins) from the last 90 s of fresh TIP decodes, with the current sample marked; no recording needed. Target/WGDC join a sample only if decoded within 2.5 s of it. (`data/BoostSessionLog.kt`, `ui/BoostSweepChart.kt`, `ui/BoostSweepMath.kt`) ([#175](https://github.com/klexical/openRS_/issues/175))
- **WGDC at peak boost + boost by gear** — drive BOOST SWEEP summary adds mean WGDC over samples within 90 % of the drive's peak TIP and a per-gear row (peak gauge PSI vs standard atmosphere, avg Δ); both also go to the drive summary text and `boostSweep.avgWgdcAtPeakPct` / `byGear[]` in the profile JSON. ([#175](https://github.com/klexical/openRS_/issues/175))

### Added (rc.6 — debug demo mode)
- **Demo mode (debug builds only)** — ⚙ → DEVELOPER feeds simulated telemetry into the real `vehicleState`, so every tab renders through its normal code path without a car. Four repeatable scenarios (IDLE cold start, DRIVING 60 s loop, WARNING hot lap, CONNECTION LOSS drop → reconnect) with pause / reset / scenario chips and an amber DEMO strip + header pill. Also reachable with `adb shell am start … --es demo driving`. (`demo/DemoSimulator.kt`, `demo/DemoMode.kt`, `ui/DemoControls.kt`)
- **Demo isolation from the car and from saved data** — entering demo finalizes any recording drive and stops adapter traffic first; drive-mode / ESC commands, DTC scan / clear and DID probes go to simulated state; drives, perf runs, session snapshots, crash telemetry and the session-knock export skip simulated data. Exiting clears simulated values (incl. sparkline / G-trail history) and stays disconnected until the user taps connect. (`service/CanDataService.kt`)
- **TRIP preview (debug builds only)** — `--es trippreview history|selected|recording|paused|summary|off` shows TRIP with in-memory sample drives, a selected route, the recording / paused controls and the post-drive summary through the real components, labelled "PREVIEW · SAMPLE DATA — NOT SAVED" (also inside the summary sheet). Sample drives have negative ids; every write path (delete, rename, tags, export, share, bookmark, recorder) returns early for them. (`demo/TripPreview.kt`, `ui/trip/TripPage.kt`)
- **COOLDOWN demo scenario** — a fifth scenario (`--es demo cooldown`) walks the THERMAL readiness verdict through oil cooling past its warn limit (recovery hold), an RDU heat forecast then warning, a coolant dropout during that warning, RDU replies stopping until the reading goes stale, a fresh poll, oil dropping below its warm-up minimum, and the AWD clutches passing their fixed warn / crit limits then cooling. Simulated values only, nothing recorded. (`demo/DemoSimulator.kt`, `ui/MainActivity.kt`)

### Added (rc.6 — DRIVE / THERMAL design pass)
- **DRIVE status line + VEHICLE panel** — a permanent row at the top of DRIVE leads with the most severe condition ("OIL CRITICAL 268°F +6"), otherwise the THERMAL readiness verdict; the VEHICLE panel shows oil, coolant, fuel and battery with chips for PTU / RDU temps, tyre pressure and knock. Both use existing limits only (THERMAL preset, TPMS low/warn psi, fuel < 10 %, knock counter) and tap through to THERMAL. (`ui/DrivePage.kt`)
- **Telltale icons** — original stroke glyphs for oil, coolant, fuel, battery, tyre pressure and a general warning, tinted only when a reading is in a warn / critical state. (`res/drawable/ic_tt_*.xml`, `ui/Instrument.kt`)
- **Debug demo capture controls** — `--es demo at:<seconds>` replays the current scenario and pauses at that time (repeatable screenshot frames); the demo strip folds to a 2 dp line from its DEMO badge or `--es demo collapse`, and the header DEMO pill unfolds it. (`demo/DemoMode.kt`, `ui/DemoControls.kt`)
- **THERMAL readiness verdict** — one session-level verdict for DRIVE's status row, THERMAL's summary card and the drive recorder's race-ready marker: NO LIVE DATA, OVERHEATING (≥ crit), TEMPERATURE HIGH (≥ warn — the active preset for oil / coolant / PTU / RDU, the existing fixed 90 / 120 °C card limits for the AWD clutches), HEAT RISING (crit forecast within 5 min), CHECKING (missing / stale data), ENGINE WARMING, DRIVETRAIN WARMING, TEMPS READY with the active preset named. A fresh warn reading or forecast now blocks readiness, and missing data is added to a fresh warning instead of hiding it. Worse states apply at once; better ones are held 10 s on a monotonic clock (2 °C under warn / crit; warm-up at minimum + 1 °C, one fresh poll for RDU / clutches), and dropping below a warm-up minimum ends readiness on the next evaluation. Offline, demo reset / switch / exit and session reset clear holds and forecast history; a stale reading drops its warning at once. Existing limits, warm-up minimums and forecast criteria are unchanged. (`ui/ThermalReadiness.kt`, replaces `ui/RaceReadiness.kt`)
- **THERMAL summary card + checklist** — headline, one reason that names what a hold is waiting for ("Oil 242°F · under 244°F for 6 of 10 s"), the preset chips and a CHECKLIST (collapsed by default, not saved) with each warm-up, preset-limit, clutch-limit, forecast and freshness rule — a limit or forecast row is incomplete, never ticked, while a reading it covers is unavailable; tapping the summary also opens it. Several readings past one limit read as one compact line. Temperatures, differences (new `displayTempDelta`, no +32) and rates convert separately. (`ui/TempsPage.kt`, `ui/UserPrefs.kt`)
- **Custom thermal profile** — Settings → THERMAL shows the active profile and its warm-up, warning and critical values (oil, coolant, PTU, RDU, AWD clutches, intake). A built-in (STREET / TRACK / RACE, unchanged) can be copied into Custom and edited in °C or °F; untouched values keep their exact stored °C, so switching units never drifts them. Inputs are checked for numbers, the sensor's reporting range, warm-up < warning < critical, and enough separation for the existing recovery margins (critical > warning + 2 °C, warning > warm-up + 3 °C). SAVE applies, CANCEL / Back / outside tap discard, RESET CUSTOM restores the copied built-in. One effective profile drives readiness, forecasts, gauge colours and scale marks, DRIVE alerts, Custom Dashboard / TRIP colours and the recorder's readiness marker; a profile change re-derives every hold. THERMAL shows a CUSTOM chip once one exists. Freshness windows, sensor rules, holds and forecast criteria are unchanged; existing saved settings and drives are untouched. (`ui/ThermalProfile.kt`, `ui/SettingsSheet.kt`, `ui/UserPrefs.kt`, `ui/AppSettings.kt`)

### Fixed (rc.6 — POWER tab data)
- **Engine load, timing, commanded AFR, O2 voltage and AFR sensor 1 were never decoded** — their Mode 01 decoders were deleted with `ObdPids.kt` in March, so these fields stayed 0, and TIMING/LOAD/OAR/SPARK/VCT/KR cells (gated on load > 0) never showed for anyone. Restored as PCM Mode 22 DIDs 0xF404 (load), 0xF40E (timing) and 0xF415 (O2 V); 0xF444 now also sets commanded λ and 0xF434 sets AFR sensor 1. Found from a user's rc.5 diagnostic report (Discussion #182). ([#198](https://github.com/klexical/openRS_/issues/198))
- **TIP actual/desired decoded about 7× too low** — 0x033E/0x0466 used `raw / 903.81` (≈ psi) instead of `raw / 128` kPa (DigiCluster, on-car verified), so TIP was hidden and BOOST SWEEP never had loaded samples. Fixed in the parser and the PID catalog; existing drives are rescaled by the v12 migration. ([#198](https://github.com/klexical/openRS_/issues/198), addresses [#175](https://github.com/klexical/openRS_/issues/175))
- **HP fuel rail queried the port-injection PID** — 0xF422 (relative to manifold vacuum) is rejected by DI PCMs; now 0xF423 (gauge rail pressure, same formula). ([#198](https://github.com/klexical/openRS_/issues/198))
- **LP FUEL cell never had a source** — it read a field whose only decoder was removed before the cell was added. Now fed by PCM DID 0x0548 low-side fuel pressure (`raw × 0.5` kPa, DigiCluster verified). ([#198](https://github.com/klexical/openRS_/issues/198))
- **POWER cells showed 0.00 for DIDs the PCM rejects** — engine cells are now gated per field on engine running + a decode this session, so NRC-suppressed DIDs (e.g. KR cyl 2/3 on some PCMs) show "— —". ([#198](https://github.com/klexical/openRS_/issues/198))
- **KR warning colour used the old sign** — KR cells and flash compared `< −1°`, but retard has been positive since rc.5; they now warn above +1°, and zero prints `0.00°` instead of `-0.00°` (POWER cells and the diagnostic summary). ([#198](https://github.com/klexical/openRS_/issues/198))
- **Diagnostic report missing engine fields** — summary snapshot now lists KR cyl 1–4, load/timing, spark advance, commanded λ / O2 V and LP fuel; detail JSON adds the same keys. ([#198](https://github.com/klexical/openRS_/issues/198))

### Fixed (rc.6 — on-car log)
- **WOT pulls never counted on cars without the throttle frame** — CAN 0x076 doesn't broadcast on some RS PCMs (absent from a full 7 km on-car log), so `throttlePct` stayed 0 and `PullTracker` never armed. Pull detection, the knock-event throttle snapshot, recorded drive throttle and session snapshots now use `VehicleState.throttleOrPedalPct` (0x076 when seen, else accel pedal 0x080) — the fallback the DRIVE tab already used inline. (addresses [#174](https://github.com/klexical/openRS_/issues/174))
- **Charge Air showed 0.0 °C on cars that don't report it** — PCM DID 0x0461 answers `0x0000` for a whole warming drive on two RS PCMs; that reply is now treated as no data and the cell stays "— —". (Discussion #182) ([#198](https://github.com/klexical/openRS_/issues/198))
- **Diagnostic report NRC times printed as "+497965:15:14"** — the NRC suppression section formatted the epoch timestamp as a duration; it now shows the offset into the session (`DiagnosticReportBuilder.sessionOffsetMs`).
- **Frame inventory mislabelled decoded frames** — the report kept its own stale copy of the known-ID set, so 0x138, 0x1E0, 0x225, 0x260, 0x305, 0x40A, 0x040 and 0x300 were tagged "?"; it now uses `CanDecoder.KNOWN_IDS`, and 0x260 / 0x305 get decode summaries instead of "(unknown id …)".
- **Mid-drive export summary said "Duration 0h 0m 0s"** — a drive that is still recording has no end time yet; the summary now measures to the last recorded point and marks the drive IN PROGRESS (totals like fuel and peaks are written at drive end).

### Fixed (rc.6 — demo mode & UI)
- **`install-debug.sh` never installed anything** — it looked for `app-debug.apk`, but the build renames debug output to `openRS_v{ver}-staging-debug.apk`. It now finds that file, waits out Android 17's post-install task relaunch (which drops launch extras), and cold-starts the app, passing extra args to `am start`. (`android/scripts/install-debug.sh`)
- **RACE READY stayed green while overheating or with no data** — readiness only checked warm-up minimums and skipped missing or stale readings. It now reports OVERHEATING when a fresh oil / coolant / RDU / PTU reading reaches the active THERMAL preset's critical limit, CHECKING when oil or coolant is missing or any reported sensor is stale (> 60 s, the THERMAL cell rule), then WARMING / READY as before. The drive recorder's race-ready marker uses the same rule. (`ui/RaceReadiness.kt`)
- **Frozen readings after an adapter drop looked live** — the last frame stays in memory on disconnect, so gear, the shift-light bar and peripheral edge glow, DRIVE auto-zoom, throttle / brake / AWD split / G / torque, the floating HUD and Custom Dashboard kept showing it (an edge glow could keep flashing). They now show placeholders or rest values while disconnected, and a held e-brake / launch-control flag no longer outranks RECONNECTING in the status strip.
- **Fast-changing cells rendered blank** — `DataCell` restarted a 120 ms crossfade on every value change, so lat G, yaw, steering, battery volts and torque never became visible while live; the PERF 0–60 timer had the same per-frame fade. Values now update in place and only a placeholder ↔ value switch fades. (`ui/Components.kt`, `ui/PerfPage.kt`)
- **Frozen White on DAY and Shadow Black on NIGHT were unreadable** — accent text, buttons, toggles and the selected paint swatch used the raw paint, white-on-white or near-black-on-black. The app now provides the existing structural tone for those two paints (`legibleAccent()`); swatch dots still show the real paint. (`ui/Theme.kt`, `ui/MorePage.kt`)
- **LONG FT stayed green past the drift alert** — the live LTFT cell used the ±10 % trim colour; it now uses the fuel trim drift alert threshold (±8 %, `FuelTrimDrift.ALERT_LTFT_PCT`), so +9.4 % shows amber. (`ui/PowerPage.kt`)
- **Clear-codes dialog listed 5 of 6 modules, and scan vs clear counts read alike** — the dialog omitted GFM; the scan summary counts fault codes while the clear result counts modules that acknowledged UDS 0x14. The dialog now lists every module from `DtcScanner.MODULES`, the scan line adds "N module(s) scanned", and the clear result reads "Clear acknowledged by N/N modules — rescan to confirm". In demo mode the dialog and progress text say nothing is sent to the car. (`ui/DiagPage.kt`)
- **Drives recorded held values after an adapter drop** — a manual drive (or auto-record before the pause fired) kept writing the frozen last frame as new samples and feeding pulls / knock / trims. Samples are now skipped while disconnected, like a pause; distance driven during the gap isn't counted. The TRIP live view shows placeholders for per-frame values and marks held temps / fuel STALE. (`service/DriveRecorder.kt`, `ui/trip/TripPage.kt`)
- **Held readings looked current for up to 60 s after a drop** — temps, TPMS, fuel, battery and DIAG battery cells now carry the existing STALE tag while disconnected; THERMAL AWD dynamics and PERF G / yaw / steer render at rest, and the DIAG FPS cell shows a placeholder. (`ui/TempsPage.kt`, `ui/ChassisPage.kt`, `ui/DrivePage.kt`, `ui/DiagPage.kt`)
- **Thermal alert said "will reach" after the limit was passed** — at/above critical it now reads "OIL 269°F — at/above critical 266°F" with no rate; alert and readiness temperatures follow the selected unit (comparisons stay in °C). (`data/ThermalPredictor.kt`, `ui/RaceReadiness.kt`)
- **Amber warning text unreadable in DAY** — new shared `WarnInk` (≥ 5.2:1 on DAY surfaces); the text primitives use it for `Warn` automatically, fills keep `Warn`. (`ui/Theme.kt`)
- **Demo controls were too small to tap reliably** — demo strip buttons were 24 dp; every control is now a ≥ 48 dp target, with the four scenarios in one menu so the strip still fits a 360 dp screen. The floating HUD shows "DEMO · simulated" while demo mode runs. (`ui/DemoControls.kt`, `service/HudOverlayService.kt`)
- **Demo could crash on a scenario switch or reset** — `FuelEconomy` was updated from the CAN / demo-tick thread while `reset()` ran on the main thread (demo switch / reset) or the service's connection path; a reset between the window-size check and `samples.first()` threw `NoSuchElementException`, and an in-flight update could republish the old session after a reset. Its state is now guarded by one lock held across both `reset()` and `onUpdate()`. New FuelEconomyConcurrencyTest (2). (`data/FuelEconomy.kt`)

### Fixed (rc.6 — design pass)
- **Last rows hid behind the bottom nav** — scrolling pages padded only the nav row (45 dp), but the nav overlay also covers the system gesture inset, so DRIVE ECONOMY and the final rows of other pages could not scroll clear. Pages now pad nav + inset (`pageBottomPadding()` / `navBarClearance()`). (`ui/DesignTokens.kt`)
- **ECONOMY only appeared once the car had moved** — the panel was hidden until `FuelEconomy.isValid`, so it was missing at startup, while stationary and offline, and appeared mid-drive. It is now always present (collapse is the user's choice only); each metric shows "—" with its unit until it has a value, IDLE shows only with the engine running (> 400 rpm) and the car stopped, and session values dim while disconnected. Calculations unchanged. (`ui/DrivePage.kt`)
- **DAY cards looked like grey slabs** — the DAY card shadow was two un-blurred offset rectangles that merged between neighbours; it is now a faint blurred contact shadow, and the redesigned DAY cards are flat white with a hairline border. (`ui/anim/GlowModifiers.kt`)
- **THERMAL peak tick pinned to the end of the bar in °F** — the tick divided the displayed (°F) peak by the °C limit; peaks are now passed in °C. (`ui/TempsPage.kt`)
- **Header read "ESC ESC ON", and the logo glow drew a box** — the ESC pill drops the duplicate prefix; the "RS" glow is a circle that fades inside the header and is off on DAY. (`ui/MainActivity.kt`)
- **AWD split showed a fake FRONT 100 % / REAR 0 % offline** — the bar and percentages now show placeholders while disconnected. (`ui/DrivePage.kt`)
- **VEHICLE panel said ALL NORMAL over stale readings** — with the link down it presented the held oil / coolant / fuel / battery values as a current all-clear; it now reads OFFLINE · LAST KNOWN while disconnected and STALE DATA when a connected reading has aged out, returning to ALL NORMAL only when the existing freshness rules (`whenDisconnected`, 60 s `tempAvailFor`) mark every cell current. (`ui/DrivePage.kt`)
- **Heat forecasts only ran while THERMAL was on screen** — the trend predictor was built and fed inside THERMAL's page, so DRIVE never saw a forecast and the history restarted whenever the page left composition. It now lives with the readiness verdict for the whole session, fed once a second from fresh readings only. (`ui/ThermalReadiness.kt`, `ui/TempsPage.kt`)
- **DRIVE status reason cut short** — the reason beside the headline shared the free width with a spacer, so on a 360 dp phone "oil 169°F → 178°F" showed as "oil 169°…". It now takes all the width the headline leaves, with shorter phrases (trend arrows as on THERMAL) and the same target text as THERMAL (177.8°F, not 178°F). (`ui/DrivePage.kt`)
- **Four forecast banners pushed oil and coolant off small screens** — THERMAL now shows one banner for the soonest prediction and names the others, separating readings already at / above critical from genuine forecasts; DISMISS snoozes them together (banner only, never the verdict). (`ui/TempsPage.kt`)

### Fixed (rc.6 — chassis)
- **TPMS footer called the low-pressure alert "Recommended"** — it shows `tireLowPsi`, the low alert threshold; it now reads "LOW ALERT < 30.0 psi". (`ui/ChassisPage.kt`)
- **AWD diagram showed F 100 / R 0 and 0 Nm offline** — torque split, wheel torque, bias, wheel-speed deltas, requested torque, AWD MAX (decoded live from 0x2C0 each frame) and pump current show dashes while disconnected instead of rest or last values. (`ui/ChassisPage.kt`)
- **Tyre cards kept the last wheel speed while reconnecting** — wheel speed is per-frame data and now shows "— mph" offline; pressures and temperatures stay, marked STALE. (`ui/ChassisPage.kt`)
- **Header lost the DEMO label during simulated connection loss** — the pill switched from DEMO to RCN; in demo it now always reads DEMO, with the simulated link state beside it ("DEMO · RCN"). (`ui/MainActivity.kt`)

### Fixed (rc.6 — PERF)
- **G-force plot axes were labelled the wrong way round** — "LAT G" sat beside the vertical (accel / brake) axis and "ACCEL / BRAKE G" under the horizontal one; the plot now reads ACCEL ↑ / BRAKE ↓ vertically and LATERAL G horizontally, with PEAK LAT / PEAK LON values, and its plot area is square so both axes share one g scale. (`ui/anim/GForcePlot.kt`)
- **Current G readings fell below the fold on short phones** — on a 360 × 720 dp screen PERF opened with only the LAT / LON / VERT labels visible; under 800 dp of screen height the plot is sized to 34 % of the height (fixed per screen, never with the readings). (`ui/ChassisPage.kt`)
- **Knock and boost chart labels were barely visible** — tick values and axis titles were 8 sp at muted alpha; they are 9 sp full-strength Dim with the grid kept faint. The knock chart's PSI title no longer overlaps the +20 tick, and boost-chart gutters fit their tick labels instead of clipping long values. (`ui/KnockScatter.kt`, `ui/KnockScatterMath.kt`, `ui/BoostSweepChart.kt`)
- **PULLS wrapped on small screens** — "1/1 · 100%" squeezed into a quarter-width cell; it is now its own PULLS WITH KNOCK row with the count as the reading and the share as secondary text. Calculation unchanged. (`ui/KnockScatter.kt`)
- **Knock severity colours ignored the theme** — the fixed yellow was unreadable on DAY; ≥ 4° uses Orange, 2–4° Warn and minor retard neutral (KnockStats bands unchanged), and a bucket with no events is no longer coloured. (`ui/KnockScatter.kt`)
- **Timer heading didn't say both targets are mph** — it reads "0-60 MPH · 0-100 MPH TIMER"; with km/h selected a line notes the targets (0-97 / 0-161 km/h), the running speed shows both units and the button reads STOP AT 60 MPH. Timing logic unchanged. (`ui/PerfPage.kt`)
- **Knock chart ignored the boost unit** — the axis, the selected event's BOOST and "Worst cell" were always PSI while the rest of PERF used BAR / kPa; they now follow the boost-pressure setting with round gridlines in that unit (0 / 0.5 / 1.0 bar, 0 / 50 / 100 kPa). Stored events and knock statistics are unchanged (gauge psi). KnockScatterMathTest +2. (`ui/KnockScatter.kt`, `ui/PerfPage.kt`)
- **Knock chart RPM title sat on the 4k tick** — it now sits between 2k and 4k. (`ui/KnockScatter.kt`)
- **"NO KNOCK EVENTS — pulls running clean" in green with nothing measured** — offline or before the first pull it now says "no pulls recorded yet", in ink (an empty log isn't a pass). (`ui/KnockScatter.kt`)
- **Knock scope picker and chart text** — SESSION / THIS DRIVE / ALL uses the tinted picker Settings uses (`SegmentedPicker` is now that shared component); knock and boost-sweep axis / legend text 9 → 10 sp. (`ui/SettingsSheet.kt`, `ui/KnockScatter.kt`, `ui/BoostSweepChart.kt`)

### Fixed (rc.6 — Classic fonts)
- **CLASSIC fonts wrapped hero values** — Orbitron is much wider than Rajdhani, so a 4-digit RPM wrapped its last digit under the fixed-height value box ("240" + a clipped fragment) and on 360 dp phones RPM showed two digits and BOOST lost its decimal. Hero numerics (`DriveNum`) stay on one line and only a value that wouldn't fit is scaled down to fit (sized from a digits→8 template, so it doesn't jitter frame to frame). Rajdhani layouts are pixel-identical; the gear digit isn't affected. (`ui/Theme.kt`)

### Fixed (rc.6 — header & nav)
- **One tap on the connection pill disconnected the car** — tapping the live pill now asks "Disconnect from vehicle?"; only DISCONNECT stops the connection, and Cancel, Back or tapping outside leave it running. Connect / reconnect stay a single tap and the DEMO pill still folds the demo controls. (`ui/MainActivity.kt`)
- **Connection changes moved every page** — RECONNECTING and TAP TO CONNECT were a strip inserted under the header, pushing DRIVE's instruments (and every other tab) down 18 dp on each drop and back on recovery. DRIVE now shows them in its permanent 48 dp status row (connected: alerts / readiness as before); other tabs read the header pill (RCN / OFF). The strip keeps only e-brake, launch control and update progress. (`ui/DrivePage.kt`, `ui/MainActivity.kt`)
- **TAP TO CONNECT was hard to read on DAY** — the label is a darker shade of the paint accent on the blue action tint (`actionInk()`); offline is no longer orange — it's the user's own state, not an alarm. (`ui/Theme.kt`, `ui/DrivePage.kt`)
- **Header text and touch targets were tiny** — chip text was 7–8 sp in ~20 dp targets; the header is 44 dp with 9–10 sp text, and the MODE chip, connection pill and settings each take the full header height. Nav tabs are 48 dp (was 45). (`ui/DesignTokens.kt`, `ui/MainActivity.kt`, `ui/BottomNavBar.kt`)
- **Back with the drive-mode dock open left the app** — the dock (opened from CHANGE MODE, the MODE pill or the header pull-down) didn't handle Back, so it went to the launcher. Back now closes the dock like tapping outside it, stays on the same screen and leaves the mode alone; with the dock closed Back works as before. (`ui/MainActivity.kt`)

### Fixed (rc.6 — service)
- **ANR when reopening the app while disconnected or in demo** — `MainActivity` called `startForegroundService()` on every launch, but `CanDataService` only called `startForeground()` when a connection started, so recreating the activity offline (Back → reopen, rotation) ended in "did not then call Service.startForeground()". The activity now only binds; the service starts itself and goes foreground together when a connection starts and leaves both when it stops (`ForegroundController`), so it no longer sits in the foreground with "Connecting…" and nothing to do. Active connections keep running after the app is closed, as before. New ForegroundControllerTest (6). (`service/CanDataService.kt`, `service/ForegroundController.kt`, `ui/MainActivity.kt`)

### Fixed (rc.6 — performance)
- **Startup work held back the first visible frame** — the 1,149-PID FORScan catalog was parsed on the main thread in `CanDataService.onCreate` (~245 ms on the emulator) and the service bind ran between the opening's first draw and the splash hand-off (up to ~330 ms). The catalog now loads on an IO thread and the bind waits for the splash to go (adapter connection starts ~1 s later on cold start; UI not delayed). Debug builds log the startup milestones under `openRS_Startup` (`ui/StartupTrace.kt`). (`can/PidRegistry.kt`, `service/CanDataService.kt`, `ui/MainActivity.kt`)
- **Tab changes stalled while the TRIP map was built off-screen** — the pager composes the page either side of the current one and prefetches the next in the scroll direction, so the Google map was created on the first PERF / THERMAL visit (a ~70-frame main-thread stall on the emulator, the nav highlight lagging the page) and again after every DRIVE visit. The map is now created only once TRIP is the settled page; until then the map area is a plain placeholder. Map type, colour mode, the selected drive and the camera are saveable, so they survive the pager dropping TRIP. (`ui/trip/TripPage.kt`, `ui/MainActivity.kt`)

- **Post-drive summary could be lost** — it was a one-shot event collected by the TRIP page, so a drive that ended before TRIP had been opened (or while the app was recreated) never showed its summary. `DriveRecorder.pendingSummary` now holds it until the sheet is closed on TRIP: it waits for TRIP, survives tab changes and is shown once. New PendingSummaryTest (2). (`data/PendingSummary.kt`, `service/DriveRecorder.kt`, `ui/trip/TripPage.kt`)
- **Returning to TRIP rebuilt the map from scratch** — the pager dropped TRIP whenever DRIVE or PERF was open, destroying the map, so every return showed a blank map for ~3 s (and the nav highlight lagged) while it was rebuilt. After its first visit TRIP now pins itself in the pager and keeps its one map: off screen the map is stopped (no rendering) and the page's live inputs are held, and on return the same camera, zoom, map type, colour mode and selection reappear with tiles at once. The first visit still creates the map, and the post-drive summary sheet only opens on TRIP. (`ui/trip/TripPage.kt`, `ui/MainActivity.kt`)

### Fixed (rc.6 — TRIP)
- **Route colour legend ignored the unit setting** — the bands are stored as km/h (60 / 100 / 140), psi (8 / 16) and °C (90 / 110) and were printed raw beside mph readings; the legend now shows converted boundaries with the unit (<37 / 37–62 / 62–87 / 87+ mph). Route-colour thresholds unchanged; map marker snippets convert speed and boost too. (`ui/trip/DriveMap.kt`)
- **Summary trends showed raw stored units** — PEAK OIL was °C beside a °F thermal row, PEAK BOOST had no unit and economy was always L/100; trends now use the temperature, pressure and speed (MPG / L/100) settings. Stored data unchanged. (`ui/trip/DriveSummarySheet.kt`)
- **Telemetry chart time labels overlapped and were clipped** — a fixed 18 px strip with a label every 60 s; the strip now fits the label and the step (15 s – 1 h) comes from the width available. (`ui/trip/DriveTimeSeries.kt`)
- **Drive-mode legend had no swatches** — the colour boxes were `fillMaxWidth(0f)`, zero wide. (`ui/trip/DriveSummarySheet.kt`)
- **Route stats ran under the colour legend on 360 dp phones** — the stats box now sits above the legend. (`ui/trip/TripPage.kt`)
- **Drive replay was unusable** — its play controls sat under the bottom nav, Back didn't close it and it opened on a whole-continent view. Controls now clear the nav, Back closes it and it opens on the route start. ±10s seeks by time (it stepped 10 samples, which is 10 s only at 1 Hz). New ReplaySeekTest (2). (`ui/trip/DriveReplayView.kt`)
- **Export sheet showed km whatever the unit setting**, and boost-sweep pressures were always PSI — both follow the unit settings. (`ui/trip/ExportOptionsSheet.kt`, `ui/trip/DriveSummarySheet.kt`)
- **Telemetry chart clipped its last time label on 360 dp** — the chart assumed a 360 dp viewport, so it scrolled on a 360 dp phone and the label at the edge was cut. It now sizes to the real viewport, and time labels draw only where a whole label fits (edge ticks edge-aligned when there's room). Channel chips wrap instead of squeezing WGDC into a sliver, with 48 dp touch areas. (`ui/trip/DriveTimeSeries.kt`)
- **Drive TEMP flag and summary used different, unsaved limits** — the recorder judged each drive against fixed 140 / 115 / 120 / 120 °C limits that matched no profile (and stored the drive-end reading as the "peak"), while the summary coloured peaks by whatever profile was selected when it was opened. Each drive now saves its peaks with the warning / critical limits of the profile in effect (built-in or Custom), a mid-drive profile change starts a new segment judged by its own profile, and the TRIP TEMP badge and the summary (now also PTU / RDU rows, the limits used and the reading behind each flag, e.g. "OIL 223°F · flag threshold 220.1°F · RACE" — 95 % of the critical in °C, then converted) read that one record. Only fresh readings are recorded (shared freshness rule), so a held value can't raise a flag under a stricter profile chosen after it went stale; the peak keeps its own segment's colour even when a later, lower reading caused the flag. CanDataService loads the saved settings itself when Android restarts it without the activity (START_STICKY), so auto-recorded drives never fall back to default limits. Earlier drives are not rewritten: they keep their original TEMP result and are labelled as recorded before limits were saved, without limit colours. (`data/DriveThermalRecord.kt`, `service/DriveRecorder.kt`, `service/CanDataService.kt`, `ui/UserPrefs.kt`, `ui/trip/TripPage.kt`, `ui/trip/DriveSummarySheet.kt`)
- **Export never included RDU / PTU temperatures** — the profile export (`drive_profile_*.json`) looked for `rduPeakC` / `ptuPeakC` keys the recorder never wrote, so its RDU / PTU section was always empty. It now reads the drive's thermal record through the same parser as the TEMP badge and summary: `thermalProgression.ptu` / `.rdu` curves and a new `thermalRecord` block — basis (profile / legacy / none, with `reason` absent / unreadable), saved warn / crit / flag threshold per profile segment, true peaks with their profile and level, and the reading behind each flag kept separate from the peak. Legacy drives export `driveEndC` against `fixedLimitC` (never as peaks or under a profile); drives without a record say the limits are unknown. Every curve is coherent: the recorder now also saves each sensor's first / last fresh reading as the curve's endpoints, and a curve whose start or end lies above its peak (e.g. a reading at stop after a pause, above every recorded sample) or is missing is left out rather than raising the peak — the peak stays in `drive` / `thermalRecord`. All °C; additive, `version` stays 2 — Sapphire already reads the optional RDU / PTU curves, treats a missing curve as no data and ignores the new block; oil / coolant `peakC` is the record's judged peak, as on the summary. (`diagnostics/DriveExportBuilder.kt`, `data/DriveThermalRecord.kt`)
- **Drive summary could show an END above its PEAK** — START / END came from the readings at recording start / stop (and the first / last point for PTU / RDU) while PEAK is the record's fresh-reading peak, so after a pause or link drop the summary could read e.g. 40 → 100 → 110 °C. The rows now use the export's endpoint rule (`DriveThermalReview.progression`): the record's first / last fresh readings, else the stored readings, and "—" for an endpoint that is missing or above the peak — the row and its saved peak, colour and TEMP flag stay; the peak is never raised or clamped. A note says START / END are the first / last valid recorded readings. (`ui/trip/DriveSummarySheet.kt`)
- **Route marker said "Race Ready"** — the TRIP map marker for the point where the thermal verdict first read TEMPS READY now says "Temps ready" / "Thermal verdict: TEMPS READY": it marks temperatures inside the openRS_ advisory limits, not track readiness. (`ui/trip/DriveMap.kt`)
- **Deleting a drive left its lap sessions and laps behind** — `lap_sessions` has no foreign key to `drives`, so a deleted drive (from TRIP, or pruned by the saved-drives limit) kept its lap data as unreachable rows. Both paths now remove lap sessions whose drive is gone, and their laps follow through the existing cascade; no schema change. (`data/DriveDatabase.kt`, `ui/trip/TripPage.kt`, `service/DriveRecorder.kt`)

### Fixed (rc.6 — GARAGE)
- **DID PROBER module chips squeezed PSCM into a vertical sliver on 360 dp phones** — the seven chips shared one row; they now wrap onto a second row. (`ui/DidProberSection.kt`)
- **GARAGE action text was hard to read on DAY** — the selected CONTROL / DIAG label, SCAN ALL MODULES, PROBE, the MONITORED count, DTC filter chips and module headings, RETRY, SHOW RESULTS and found / PID DIDs were the bright paint blue on blue-tinted surfaces; they use the darker DAY blue (`actionInk()`) already used by CHANGE MODE and CAPTURE & SHARE SNAPSHOT. NIGHT is unchanged. (`ui/GaragePage.kt`, `ui/DiagPage.kt`, `ui/DidProberSection.kt`, `ui/PidBrowserSection.kt`)

### Fixed (rc.6 — Custom Dashboard)
- **Back left the app from the custom dashboard** — the full-screen overlay had no Back handling; Back now closes it (an open editor sheet closes first, discarding unsaved edits as before). (`ui/CustomDashPage.kt`)
- **Bar colours ignored the real limits** — every bar turned amber above 70 % and orange above 90 % of its scale, so a full tank or full throttle read as a warning while 6 % fuel or critical oil stayed blue. Cells now colour only past existing limits: THERMAL preset oil / coolant / PTU / RDU, fuel < 10 %, KR > 1° and the RPM shift point. (`ui/CustomDashPage.kt`)
- **Temperature bars used a 0–100 scale** — 100 °C oil drew a full bar; oil, coolant, PTU and RDU bars use THERMAL's threshold scale with the preset warn / crit marks. (`ui/CustomDashPage.kt`)
- **The bottom nav covered the dashboard** — it hid the last row, and its tabs switched pages out of sight behind the overlay; it's hidden while the dashboard is open. (`ui/MainActivity.kt`, `ui/CustomDashPage.kt`)
- **Taps on the dashboard reached the page underneath** — empty areas of the overlay passed touches through, so a tap could open THERMAL from DRIVE's status line out of sight; the overlay now takes every touch. (`ui/CustomDashPage.kt`)
- **Dashboard went blank with no reason on a link drop** — cells blank while disconnected (so held values never read as live), but the overlay hides the header pill, so nothing said why; its title now carries the pill's wording — `DEMO · RCN` / `DEMO · OFF` in demo, `RCN` / `OFF` for a real link. (`ui/CustomDashPage.kt`, `ui/DemoControls.kt`)

### Fixed (rc.6 — THERMAL tyres)
- **THERMAL ignored the saved tyre Warn / High pressures** — its tyre cards, panel edge and wireframe lead lines used fixed 34 / 50 psi for caution and over-inflation, so custom values changed DRIVE's alerts but not THERMAL. They now use the saved Low / Warn / High (defaults 30 / 34 / 50 and the boundary rules unchanged; PSI and BAR show the same state). New TyrePressureLevelTest (3). (`ui/ChassisPage.kt`, `ui/anim/CarDiagram.kt`)
- **DRIVE said ALL NORMAL with a tyre over the High pressure** — DRIVE only checked the lowest tyre against Low / Warn, while THERMAL showed an over-inflated tyre as critical. Both now share one classification (`tireLevel` / `tyreIssues`): DRIVE adds "FL TYRE HIGH" (critical, with the tyre's pressure in the user's unit) for the highest tyre past High; low and caution alerts, alert priority, the offline / no-TPMS handling and the fixed status row are unchanged. Two boundary points now match THERMAL: a tyre exactly at Low reads caution (was critical on DRIVE) and a decoded 0.0 psi reads critical (was caution — the not-polled sentinel is −1). TyrePressureLevelTest +3. (`ui/DrivePage.kt`, `ui/Instrument.kt`)

### Fixed (rc.6 — Settings)
- **Adapter and connection pickers were crushed on 360 dp phones** — "Hardware" was squeezed into a one-letter-wide column beside the MeatPi picker, and Theme mode / Connection method wrapped against theirs; those pickers now sit on their own full-width line under the label. (`ui/SettingsSheet.kt`)
- **Closing Settings without saving could keep a previewed theme** — only CANCEL reverted the live theme / font / paint preview; Back, tapping outside, ✕ and the Custom Dashboard / demo shortcuts left it applied (unsaved) until restart. Every exit except SAVE now reverts it. (`ui/SettingsSheet.kt`)
- **TPMS note named colours the app doesn't use** — "Red < 30 | Gold < 34 | Green | Red > 40" described the old tyre colours; it now says what each threshold means (critical / caution / over-inflated) and gives the defaults. (`ui/SettingsSheet.kt`)
- **A blocked SAVE didn't say why** — the validation message sat at the end of the scrolled page, out of sight; it now shows just above CANCEL / RESET / SAVE. (`ui/SettingsSheet.kt`)

### Changed (rc.6)
- **PCM slow poll list** — adds 0xF404, 0xF40E, 0xF415 and 0x0548 (16 → 20 slow DIDs, ~24 s each with the engine running); `PCM_SLOW_REFRESH_MS` 35 → 40 s so the engine-off sweep + idle still fits the staleness window. ([#198](https://github.com/klexical/openRS_/issues/198))
- **Room database v11→v12 migration** — adds nullable `drives.pullCount` / `knockPullCount` and multiplies stored `drive_points.tipActualKpa` / `boostTargetKpa` (> 0) by 903.81 / 128. (`data/DriveDatabase.kt`) ([#174](https://github.com/klexical/openRS_/issues/174), [#198](https://github.com/klexical/openRS_/issues/198))
- **Test coverage** — new PullTrackerTest (7), KnockSessionLogTest (5), KnockScatterMathTest (2); parser cases for TIP `/128`, 0xF404, 0xF40E, 0xF415, 0xF423, 0x0548, 0xF444 λ, 0xF434 AFR sensor 1 and 0xF422 no longer decoded; poll-list and refresh-window checks; boost sweep from parser-decoded TIP; knock pulls summary line; session knock CSV type; decoder-coverage guards that fail if any polled PCM / BCM / AWD DID lacks a decoder that stamps freshness (the gap that hid #198). BoostSessionLogTest (6), BoostSweepMathTest (4), BoostSweepStatsTest WGDC-at-peak / by-gear (+4), boost sweep export (+1); throttle/pedal fallback (VehicleState, KnockCapture, KnockSessionLog), charge air 0x0000, NRC session offset, in-progress summary, describeDecoded coverage for every known CAN ID. Total: **646 tests across 31 files** (Android).
- **Lint warning triage (87 → 48)** — `modifier` moved to the first optional parameter on `DataCell`, the tier cards, `AvailCell`, `EdgeShiftLight`, `ShiftLightBar`, `DriveMap` and the drive list (text primitives in `Theme.kt` left as-is — hundreds of positional call sites); KTX `toUri()` / `createBitmap()`; `isWideLayout()` reads `LocalWindowInfo` instead of `screenWidthDp`; HUD overlay text uses string resources; dropped the always-true API 26 notification-channel check and the redundant activity label. Remaining warnings are dependency/AGP upgrade notices and launcher-icon shape. (addresses [#196](https://github.com/klexical/openRS_/issues/196))
- **Demo-mode test coverage** — new DemoSimulatorTest (7): repeatable frames, LOSS freeze / recovery sentinels, freshness stamps, fields without a decoder left at sentinels, WARNING crosses the app's thresholds, DRIVING plausibility and continuity. Total: **653 tests across 32 files** (Android).
- **Race-readiness test coverage** — new RaceReadinessTest (7): overheat, missing and stale readings block RACE READY, optional sensors ignored until reported, warm-up, and the legible-accent mapping. Total: **660 tests across 33 files** (Android).
- **Freshness test coverage** — RaceReadinessTest +7: late / boundary / missed AWD updates and recovery, missing coolant, overheat over stale, °F detail, reached-threshold alerts, disconnected STALE. Total: **667 tests across 33 files** (Android).

- **DRIVE redesign** — readings in ink with the paint accent on BOOST and the trend lines; amber / orange only when a limit is crossed (RPM at the shift-light flash point). One gear module holds the shift lights with brake | gear | throttle bars at a fixed, screen-dependent size; AWD split, G and torque share one panel; ECONOMY is one grouped panel (instant, average, distance to empty, fuel used). (`ui/DrivePage.kt`, `ui/Instrument.kt`)
- **THERMAL hierarchy** — readiness and the STREET / TRACK / RACE limits share one card; ENGINE (oil, coolant) cards are full width with a scale showing faint caution / redline zones and a WARN / CRIT legend; PTU, RDU, AWD clutches and trans oil are one DRIVETRAIN panel; intake / ambient and the detail temps are compact. Short screens trim padding, not reading sizes. (`ui/TempsPage.kt`)
- **Readable secondary text** — captions, PEAK / LIMIT / units and AWD details on DRIVE and THERMAL are 10 sp at full-strength Dim (were 8–9 sp, alpha-muted); bottom nav labels 7 → 9 sp with the active tab bold. (`ui/Instrument.kt`, `ui/BottomNavBar.kt`)

- **AWD clutch hydraulics labelled by measurement** — the readouts under a CLUTCH HYDRAULICS caption read CURRENT L / R, PUMP and PRESSURE L / R instead of two CLUTCH L / R rows told apart only by A vs bar. (`ui/ChassisPage.kt`)
- **THERMAL tyres + AWD in the design-pass style** — tyre cards around the original Focus RS wireframe show pressure in ink unless past the existing limits, with temperature on the existing bands; the AWD diagram shows torque only and its readouts drop the readings now in DRIVETRAIN (PTU, RDU, clutch and trans-oil temps) plus the duplicate F/R split and PEAK (= AWD MAX) cells. (`ui/ChassisPage.kt`)
- **PERF in the design-pass style** — G-force readouts are one grid in the plot's panel (were six cards) with a 44 dp RESET PEAKS button and no decorative trail tints; the timer has a 48 dp ARM TIMER button and an ink running time; personal bests, knock, fuel-trim drift and POWER use instrument panels. POWER sections are grouped readout grids with readings in ink (AFR / λ, WGDC and in-range trims were accent / green); KR > 1° and trims past the existing bands still turn amber and set the panel edge. (`ui/PerfPage.kt`, `ui/PowerPage.kt`, `ui/ChassisPage.kt`, `ui/FuelTrimDriftCard.kt`)
- **Section headings** — `SectionLabel` is 10 sp full-strength Dim app-wide (was 8 sp at 65 %). (`ui/Components.kt`)
- **TRIP in the design-pass style** — the 16 live cards are one instrument panel (speed / RPM / gear / boost, vehicle temps and fuel, drivetrain temps and fuel used / economy) with wheel speeds and a visible SLIP Δ switch inside it. Readings are in ink; temps use the THERMAL preset limits and STALE rules, fuel the < 10 % rule, slip its existing > 2 / > 5 bands (the old boost colours at 0 / 8 / 16 psi weren't limits). BOOST shows the user's pressure unit; fuel used and economy show "—" until there is a value. Recording buttons are 48 dp (START / RESUME accent, PAUSE neutral, STOP orange outline); map controls are 48 × 48 dp touch areas around compact chips, zoom paired on short maps; overlay text 9–12 sp; the empty history is a readable panel. Map, recording, lap timer and history behaviour unchanged. (`ui/trip/TripPage.kt`; `ReadoutGrid` moved from `ui/PowerPage.kt` to `ui/Instrument.kt`)
- **Drive history + summary in the design-pass style** — drive cards: readings with units (DIST, TOP, RPM, BOOST in the pressure setting, LAT G), no COMPLETE badge on every drive, intensity as a neutral SCORE chip (was coloured on bands that weren't limits), wrapping 10 sp tags, 44 dp actions in 48 dp touch rows (REPLAY no longer amber). Summary: drive name + date, one readings panel, thermal peaks coloured only past the THERMAL preset limits, drive-mode bar on a neutral → paint scale (green / amber read as OK / caution), accent trend lines, 48 dp SHARE / DISMISS; map PAUSED chip in ink. (`ui/trip/TripPage.kt`, `ui/trip/DriveSummarySheet.kt`)
- **Compare, export, replay and header recording state** — compare reports neutral signed differences (green / orange called higher RPM, boost and score "better") with readable names and values; export rows and actions are 48 dp+; replay readings are one panel with labelled −10s / +10s; the header's PAUSED is neutral (was amber), matching the map chip. Debug TRIP preview adds compare / replay / export modes and sample boost-target data, and every sample sheet carries the PREVIEW notice. (`ui/trip/*`, `ui/MainActivity.kt`, `demo/TripPreview.kt`)
- **Readable DAY blue on TRIP** — BOOST and the map labels (colour mode, locate, legend) use `actionInk()` on DAY: 3.4 → 7.6:1 for BOOST and 2.6 → 6.1:1 for the colour-mode chip; NIGHT keeps the paint. (`ui/trip/TripPage.kt`)
- **Header + bottom nav in the design-pass style** — MODE / ESC / connection are neutral chips (surface + hairline) with the state on the value or dot: the drive mode reads in ink with a ▾ for the dock (TRACK / DRIFT were amber / orange), ESC keeps amber / orange for Sport, Launch and Off, the connection label is ink when live. Settings is a bare glyph. The nav drops its bevel gradient, specular line, inner shadow and indicator glow for one hairline. The logo and its animation are unchanged. (`ui/MainActivity.kt`, `ui/BottomNavBar.kt`)
- **GARAGE in the design-pass style** — CONTROL: the drive mode reads in ink (SPORT / TRACK / DRIFT were green / amber / orange), CHANGE MODE and the ESC chips are 48 dp with ESC keeping the header's amber / orange for SPORT and OFF; the five firmware-feature cards are one list with neutral STANDBY / OFF / PROBING (were amber), an accent dot for ON and amber only for engaged launch control; the firmware banner and adapter details are neutral. DIAG: the 15 health / vehicle / battery / button cards are four grouped panels coloured only past existing limits (DTCs found, SoC < 70 / 50 %, e-brake on, ESC off held — LIVE, charging current and SoC ≥ 70 % were green); developer tools are indented under DEVELOPER, the duplicate DID PROBER / PID CATALOG headings are gone, PID coverage counts are no longer green and actions are 48 dp. Vehicle-control behaviour unchanged. (`ui/MorePage.kt`, `ui/DiagPage.kt`, `ui/DidProberSection.kt`, `ui/PidBrowserSection.kt`, `ui/GaragePage.kt`)
- **Custom Dashboard in the design-pass style** — gauges are instrument panels (readings in ink, BOOST in the paint accent, heroes with PEAK like DRIVE, raw values without the spring) in equal-height rows; header BACK / EDIT are 48 dp; the empty state keeps the car outline and adds an ADD GAUGES action that opens the editor on the readings list. Editor: one scrolling sheet with two-line rows — 48 dp drag handle, remove, ▲ / ▼ and NUM / BAR / HERO chips (were 7 sp in ~20 dp targets) — 48 dp picker rows, and CANCEL beside SAVE LAYOUT. Saved layouts and the field list are unchanged. (`ui/CustomDashPage.kt`)
- **Settings in the design-pass style** — categories, sections and links are flat instrument panels (were gradient cards with accent bars) with names in ink; option pickers show the selection as an accent tint with dark-on-DAY text (was a solid fill) at 44 dp; every row, help "?", close, clear, breadcrumb and action is a 48 dp touch area; notes and help text are 11 sp. Actions read as actions — START HUD, INSTALL, OPEN INSTALL SETTINGS and the WiFi "USE" were green / orange status colours — while FORGET, STOP HUD and EXIT DEMO keep the orange outline. The footer drops the repeated version line (it's in the strip at the top), so short screens show more settings. The version strip's CHECK — which only opened the update panel — reads UPDATES. What's New uses the same title bar and 48 dp buttons. Every setting, default, validation and SAVE / RESET / CANCEL behaviour is unchanged. (`ui/SettingsSheet.kt`, `ui/MorePage.kt` paint chips, `ui/WhatsNewDialog.kt`)
- **Drive-mode dock in the design-pass style** — one accent for every mode (SPORT / TRACK / DRIFT were green / amber / orange, status colours), the selected mode as an accent tint with dark-on-DAY text, 64 dp buttons with 10 sp names (were 8 sp), a DRIVE MODE caption that says what's happening (current mode, switching to…, … confirmed, offline, view only), and a plain explanation line. Offline nothing is shown as selected (it highlighted the last frame's NORMAL and blamed missing firmware); the confirmed button is no longer dimmed while the command finishes; the switching shimmer is clipped to the rounded button. Mode switching, the three ways to open it and Back-to-close are unchanged. (`ui/DriveModeDock.kt`)
- **AWD-polled temperature freshness** — RDU, AWD clutch and trans-oil temps (one reply per ~37 s BCM / AWD poll cycle) are stale after 90 s instead of 60 s, so one missed reply no longer flips them STALE and readiness to CHECKING; two in a row still do. CAN-broadcast temps keep 60 s; trans oil is not a readiness input. One shared rule (`VehicleState.tempAvail`) for THERMAL, DRIVE, TRIP, Custom Dashboard and readiness. (`ui/ThermalReadiness.kt`)
- **Readiness on DRIVE** — temperature conditions on the status row come from the readiness verdict (OVERHEATING › critical tyre alerts › TEMPERATURE HIGH / HEAT RISING › other alerts › the rest of the verdict) with its holds; the VEHICLE panel cells and chips still show each reading's instant state, and its summary carries the verdict's forecast, recovery hold or missing data instead of ALL NORMAL. Warming states use the accent instead of amber (amber means a crossed limit). A previously reported optional sensor that turns invalid now blocks readiness (CHECKING) like a stale one. (`ui/DrivePage.kt`)
- **Thermal readiness test coverage** — RaceReadinessTest becomes ThermalReadinessTest (32): warm-up target and hold, delayed evaluation, drop below a minimum, polled warm-up, warn / crit and their recovery holds, forecast and its clearing hold, warning with missing data, stale-warning removal, invalid optional sensor, preset change, offline and reset clearing history, one / two missed AWD polls, the evaluator never refreshing stamps, the shared freshness rule, unit conversions, clutch warn / crit / recovery / stale, trans-oil missed polls, incomplete checklist rows, the VEHICLE advisory, banner wording, DRIVE / THERMAL target text, and the COOLDOWN demo sequence. Total: **705 tests across 38 files** (Android).
- **Thermal wording** — TEMP HIGH / TEMP CRITICAL replace TEMPERATURE HIGH / OVERHEATING (same states and rules). THERMAL's reason reads as a sentence ("Left clutch above warning limit · 95°C", "Both clutches above critical limit", "Oil cooling — confirming recovery · 4 of 10 s" while the severity holds), TEMPS READY says "Monitored temperatures meet the STREET profile" rather than implying the car is ready for track use, and the exact targets and comparisons ("Oil 250°F ≥ 248°F", "needs <244.4°F", closest headroom) live in the checklist. DRIVE keeps one short phrase ("Clutch L 95°C ↑", "within STREET profile"). (`ui/ThermalReadiness.kt`)
- **Thermal profile test coverage** — new ThermalProfileTest (7): built-in values unchanged, Custom only when selected and saved, exact JSON round trip, no unit drift, validation (numbers, order, margins, sensor range), a custom threshold moving verdict and gauge colour together, no warm-up hold across a changed minimum; ThermalReadinessTest wording / clutch-pair cases. Total: **712 tests across 39 files** (Android).
- **Approved artwork integrated** — from the openRS_ concept sheet: the launcher icon (dark tile, stylized blue RS and white underscore; adaptive + round + themed monochrome layers, legacy PNGs regenerated), the openRS_ wordmark (traced vector glyphs, used in the header with its typewriter / blinking-underscore / live-glow animation, and in the Settings title), the system splash (RS_ icon + wordmark, API 31+; wordmark window on older Android) and an opening presentation — wordmark, tagline and front-view RS — that fades into the dashboard as soon as it has drawn its first frame (cold start only, no timer). THERMAL's tyre diagram uses the new top-down Focus RS (line art in ink, each tyre tinted by its own pressure state; lead lines re-anchored to the new wheels). The symbol family — boost, coolant, oil, tyre pressure, battery, fuel, gear, trip, garage, settings, traction, connected — replaces the earlier telltales and is used on DRIVE's VEHICLE cells, BOOST and GEAR captions, the header ESC / connection / settings controls and the TRIP / GARAGE tabs, with normal (sheet two-tone), muted, selected, warning, critical and unavailable treatments. ABOUT shows the wide launch artwork. The battery symbol is rebuilt from the sheet's measured geometry (the plain trace had gaps and uneven edges), and the selected tab's icon and label (and the SELECTED icon tone) use the darker readable DAY blue, `actionInk` (the mid-tier chrome blue read as pale cyan on white). Replaces the previous car wireframe and launcher icon (owner's decision). (`ui/BrandArt.kt`, `ui/anim/CarDiagram.kt`, `res/drawable*/`, `res/mipmap*/`, `res/values-v31/themes.xml`)
- **Bottom navigation in the Precision icon family** — all five tabs (DRIVE, PERF, THERMAL, TRIP, GARAGE) use the supplied Precision icons (openRS_icons_round03), prepared as tintable alpha layers cut from the artwork (background removed, common source scale, centred on one canvas): inactive tabs show the whole glyph in the muted colour, the selected tab the design's two-tone (ink outlines, blue details) with the label in the same blue. Labels stay live text and each tab a real control; nav height, touch targets, selection behaviour and page spacing are unchanged. The previous DRIVE / PERF / THERMAL nav glyphs are removed. (`ui/BottomNavBar.kt`, `res/drawable-nodpi/nav_*`)
- **One opening experience** — the system splash is now only the opening composition's background colour (no RS_ icon, no wordmark), removed without an exit animation on the app's first frame. The approved composition (wordmark, tagline, front-view RS) is drawn alone and at full opacity from its first frame (no fade-in); the dashboard composes underneath only once the system splash has been removed, so the splash hands off on the first frame instead of waiting for the dashboard. The composition stays fully visible for 2 s, counted from that first visible frame (not added after loading), then a 300 ms fade. Never waits for the adapter or network. Tagline at ≥ 17 sp, content inside the system-bar insets, car at its 300 × 360 proportions. Shown once per process cold start — not on resume, rotation or reopening a live process. (`ui/BrandArt.kt`, `ui/MainActivity.kt`, `res/values*/themes.xml`)
- **E-brake and launch control** — the states the signals support: e-brake on (0x0C8), launch control on (0x420 LaunchControlStatus) and engaged (0x225). The header's wordmark slot shows them as full-word chips, stacked when both are on (E-BRAKE in orange; LC ON outlined / LC ENGAGED filled — on DAY the fill is the darker readable paint tone, `actionInk`, with white text: 7.6:1 instead of 3.4:1 for Nitrous Blue), on every tab, so engaged never reads as merely ON; DRIVE's gear module carries an E-BRAKE tag above the gear and "LAUNCH CONTROL · ON / · ENGAGED" in place of its caption, with an accent edge (stronger + tinted face while engaged); the existing edge glow while engaged now uses the accent (amber means a crossed limit). The strip under the header that pushed every page down, and DRIVE's inserted LC banner, are gone — appearing, changing and clearing move nothing. Held flags after a link drop aren't shown. GARAGE reads ON / ENGAGED. Detection, thresholds and commands unchanged. New debug LAUNCH demo scenario walks the states incl. a link drop. (`ui/MainActivity.kt`, `ui/DrivePage.kt`, `ui/MorePage.kt`, `ui/anim/LaunchControlEdgeGlow.kt`, `demo/DemoSimulator.kt`)
- **Larger THERMAL car diagram** — the top-down RS takes the width the tyre cards don't need (each card keeps ≥ 86 dp so "25.8 psi" stays on one line), up to the cards' column height (cap 200 dp): measured tyre-to-tyre span 103 → 113 dp on 360 dp and 103 → 144 dp on a full-size phone. Aspect ratio, overlays, lead-line anchors and tap targets follow the drawn size; the panel doesn't grow. (`ui/ChassisPage.kt`)
- **Demo test coverage** — DemoSimulatorTest +1: the LAUNCH scenario's e-brake / launch-control on / engaged phases, link drop and recovery. Total: **713 tests across 39 files** (Android).
- **Drive thermal record test coverage** — new DriveThermalRecordTest (14): built-in and Custom limits saved, 95 % boundary and warn / crit levels, peak rule, °C / °F entry and Celsius evaluation, the displayed flag threshold (incl. legacy), later profile edits, mid-drive changes (incl. a saved Custom edit), stale readings across a profile change, peak vs flag-causing reading, settings loaded on a service-only start, legacy records, exact round trip. Total: **727 tests across 40 files** (Android).
- **Export thermal test coverage** — new DriveExportThermalTest (7; every exported curve checked start ≤ peak ≥ end): built-in, Custom, STREET → RACE (peak vs flag reading), legacy, no record vs unreadable, endpoints above the saved peak / missing endpoints, record endpoints over a stale point value. Total: **734 tests across 41 files** (Android).
- **Summary progression test coverage** — DriveThermalRecordTest +3: saved first / last readings, fallback with END above PEAK / missing START (peak unchanged), legacy and no-record endpoints. Total: **737 tests across 41 files** (Android).
- **Sapphire import test for Android exports** — `web/src/lib/import.androidExport.test.tsx` (6) imports complete exports written by the app's builders (CSV, GPX, summary, profile JSON with `thermalRecord`, manifest; normal, mixed profiles, legacy, no / unreadable record, endpoints omitted) and renders the thermal chart: omitted curves read as no data, no `-99` / NaN drawn. DriveExportThermalTest writes the fixtures (`OPENRS_EXPORT_SAMPLES`). Sapphire: **174 tests across 7 files**.

### Removed (rc.6)
- **Unused resources** — `ic_nav_chassis` / `ic_nav_diag` drawables, the legacy OSMDroid `raw/map_style_dark.json`, and 12 unreferenced `colors.xml` entries.
- **Unused oil / coolant band helpers** — `thermalBandForCoolant` / `thermalBandForOil` had no callers (THERMAL uses the profile limits) and carried unsourced reference values in their comments; removed. The tyre bands (`thermalBandForTire`) are unchanged. (`data/ThermalBand.kt`)
- **DRIVE auto-zoom** — the gear module no longer grows (and the panels below no longer move) once the car is rolling; the gear-change scale pop and the DRIVE auto-zoom setting are gone. Gear changes keep their haptic tick. (`ui/DrivePage.kt`, `ui/SettingsSheet.kt`)

### Fixed (rc.6 — CI & lint)
- **CI Lint job crashed on every push since rc.1** — AGP 8.7.3's bundled lint is older than the lint checks shipped in Compose BOM 2025.11 / Lifecycle (`IncompatibleClassChangeError` in `RememberInCompositionDetector` / `NonNullableMutableLiveDataDetector`), so `publish-release` never ran. `gradle.properties` now pins `android.experimental.lint.version=8.13.0`; all checks still run. ([#196](https://github.com/klexical/openRS_/issues/196))
- **RC tag builds would have attached a plain-named APK** — `publish-release` built without `-PrcSuffix`; it now derives the suffix from the tag (`android-v2.2.7-rc.N` → `rc.N`) and keeps RC releases marked pre-release. ([#196](https://github.com/klexical/openRS_/issues/196))
- **DID probe history could crash on Android 9–14** — `probeSessionsList.removeFirst()` compiled to `java.util.List.removeFirst` (API 35+) and would throw `NoSuchMethodError` once probe sessions exceeded the cap; now `removeAt(0)`. Surfaced by the repaired lint. (`diagnostics/DiagnosticLogger.kt`) (addresses [#196](https://github.com/klexical/openRS_/issues/196))
- **Location permission requested without COARSE** — Android 12+ requires `ACCESS_COARSE_LOCATION` alongside FINE in the same request; added to the manifest, the startup request, and the TRIP tab prompt. (addresses [#196](https://github.com/klexical/openRS_/issues/196))
- **Map markers recreated state every recomposition** — start/finish, pause and current-position `MarkerState`s in `DriveMap` are now `remember`ed by position. (addresses [#196](https://github.com/klexical/openRS_/issues/196))
- **Bottom nav indicator recomposed every animation frame** — the sliding indicator used `Modifier.offset(x = …)` with an animated value; now the lambda overload, which reads it in the layout phase. Surfaced by the repaired lint. (`ui/BottomNavBar.kt`) (addresses [#196](https://github.com/klexical/openRS_/issues/196))
- **Debug and release builds couldn't be installed on the same device** — the FileProvider authority was hard-coded as `com.openrs.dash.provider`, so installing a second build variant failed with `INSTALL_FAILED_CONFLICTING_PROVIDER`. The authority is now `${applicationId}.provider` (release unchanged: `com.openrs.dash.provider`; debug `com.openrs.dash.debug.provider`), and the share / update code derives it from the package name. (`AndroidManifest.xml`, `diagnostics/DiagnosticExporter.kt`, `update/UpdateManager.kt`)
- **Share sheet couldn't read the shared ZIP** — exports put the file only in `EXTRA_STREAM`, which Android grants to the chosen app but never to the share sheet itself, so its preview logged `Permission Denial` against the FileProvider. The share intents now also carry the URI as ClipData, which `Intent.createChooser` passes on with the read grant; the provider stays non-exported. (`diagnostics/DiagnosticExporter.kt`)

### Added (rc.5 — fuel trim drift)
- **Fuel trim drift tracker** — each drive now stores warm-engine averages of freshly polled STFT/LTFT (PCM 0xF406/0xF407; coolant ≥ 70 °C, ≥ 500 RPM, ≥ 3 samples). A new PERF card charts the last 20 drives against ±5 % / ±10 % bands and warns when LTFT exceeds ±8 % in the same direction on two consecutive drives (lean = possible vacuum/boost leak or MAF drift; rich = injectors/purge/fuel pressure). Averages are included in the drive summary and the Sapphire profile JSON (`fuelTrim`). (`data/FuelTrim.kt`, `ui/FuelTrimDriftCard.kt`)

### Fixed (rc.5 — polling & recording)
- **Boost sweep + knock data no longer built from ~35 s-old readings** — TIP actual/desired, WGDC and KR were polled once per ~35 s PCM cycle but written into every 1 Hz drive point, so BOOST SWEEP stats mostly re-counted held values. The recorder now stores these DIDs only on a fresh decode (`data/FreshnessGate.kt`); otherwise `tipActualKpa`/`boostTargetKpa` = 0 and `wgdcPct` = −1, and `BoostSweepStats` only uses samples where target and actual were both fresh. ([#184](https://github.com/klexical/openRS_/issues/184))
- **Stale knock reading replayed at drive start** — the knock logger reset its last-value check at drive start while `ignCorrCyl1` still held a pre-drive reading, logging a phantom event. The freshness gate rejects any decode stamped before the drive began and replaces the value-equality dedup, so repeated identical retard on consecutive polls is now kept. ([#187](https://github.com/klexical/openRS_/issues/187))
- **OBD freshness stamps never reached live state** — `CanDataService.mergeObdState()` dropped `fieldLastUpdateMs`, so every Mode 22 field had no stamp and availability labels fell back to STALE. Stamps are now merged keeping the newer value per tag (`VehicleState.mergeStamps`); STFT/LTFT cells use the slow-DID refresh period for staleness.
- **Header knock counter never incremented and used the wrong sign** — it compared state across CAN frames (KR only changes on OBD merges) and counted `KR < −1°`, but the parser reports retard as positive. It now counts each freshly polled KR reading above +1° on any cylinder.

### Fixed (rc.5 — knock logger)
- **Knock logger only watched cylinder 1** — capture now emits one event per cylinder whose KR (0x03EC–EF) was freshly polled with ≥ 0.1° retard, stored in a new `cylinder` column. The PERF aggregate shows a per-cylinder split; cyl 2–4 stay empty if the PCM rejects those DIDs. (`data/KnockCapture.kt`) ([#186](https://github.com/klexical/openRS_/issues/186))
- **Knock card never refreshed** — the PERF knock card loaded once and showed the last 500 events across all drives. It now observes Room live and has a THIS DRIVE / ALL picker (THIS DRIVE = the recording drive, else the most recent one). ([#190](https://github.com/klexical/openRS_/issues/190))
- **Severity buckets didn't add up to COUNT** — events between 0° and 0.1° (and on the 3.9999°/1.9999° seams) fell into no bucket. Aggregation moved to a shared `KnockStats` with half-open <2° / 2–4° / ≥4° buckets. ([#191](https://github.com/klexical/openRS_/issues/191))
- **Knock insert could be dropped at drive stop** — the insert was a child of the recording job that `stopDrive()` cancels; it is now written inline under `NonCancellable`. ([#193](https://github.com/klexical/openRS_/issues/193))
- **THERMAL showed CAN-sourced temps as STALE** — passive CAN decoders never stamped freshness, so oil / coolant / intake / PTU / ambient read STALE and the oil/coolant/PTU climb trends never rendered. `CanDecoder.stampFresh()` now stamps those tags, throttled to once per second per CAN ID. ([#194](https://github.com/klexical/openRS_/issues/194))

### Fixed (rc.5 — export & Sapphire)
- **Sapphire imported the knock CSV as a phantom drive** — `DiagnosticExporter.inferType()` tagged `drive_knock_*.csv` / `*_knock.csv` as `drive_csv`, so a single-drive ZIP with knock events imported as a two-session batch and a no-GPS drive imported the knock table as the drive itself. The exporter now tags these `knock_csv`, and Sapphire's shared `isDriveCsvName` / `isDriveCsvEntry` checks (used by the importer and ImportPanel) exclude knock files even in rc.4 manifests that mislabel them. ([#183](https://github.com/klexical/openRS_/issues/183))
- **Sapphire production build failed type-checking** — `tsc -b` (the first half of `pnpm build`) errored on an unused `FreezeFrame` import and an incomplete `TripPoint` test fixture, blocking the GitHub Pages deploy.

### Fixed (rc.5 — drive chart)
- **TARGET / WGDC chart channels were misleading** — every channel was auto-scaled on its own, so BOOST and TARGET couldn't be compared; unpolled target plotted as −14.7 PSI dips; and TARGET/WGDC reused the G-LAT/OIL colours. TARGET now shares BOOST's axis and colour as a dashed line, WGDC uses `Mid`, absent/sentinel samples (target 0 kPa, WGDC −1 %, oil −99 °C) are gaps that bridge short holes and break after 5 s, and polled channels with no data in a drive are hidden. (`ui/trip/DriveTimeSeries.kt`, `ui/trip/TimeSeriesMath.kt`) ([#188](https://github.com/klexical/openRS_/issues/188))

### Fixed (rc.5 — CAN decoder)
- **0x1A4 ambient could read +55–64 °C on sub-zero days** — the unsigned ×0.25 decode (rc.4) can't represent temperatures below 0 °C, so a cold-day raw byte (e.g. 0xEC, previously −5 °C) would decode as 59 °C. Readings above 55 °C are now ignored so 0x340 PCM ambient keeps the value, and the raw byte is logged with an issue flag for a future capture. Interim guard — the true encoding is still pending. (addresses [#185](https://github.com/klexical/openRS_/issues/185))
- **0x300 charging-status frames logged as unknown** — the reserved decoder returned `null`, which routes frames to the unknown-frame log. It now returns state unchanged so the frame is logged as recognized. ([#189](https://github.com/klexical/openRS_/issues/189))
- **0x040 SRS frames emitted a new VehicleState per frame** — the decoder copied state just to bump `lastUpdate`, triggering peaks/fps copies and recomposition with no data change. Recognized frames that change nothing now return the same instance and `processCanFrame` skips the copy and emit. ([#192](https://github.com/klexical/openRS_/issues/192))

### Changed (rc.5)
- **Fast PCM poll group** — with the engine running (> 400 RPM), each PCM cycle sends TIP actual/desired, WGDC and KR cyl 1–4 at 150 ms gaps plus one rotating slow DID (~0.8 Hz fast, ~19 s per slow DID). Engine off keeps the full sweep + 30 s idle. Still a single PCM sender so NRC attribution is unchanged. (`can/PcmPollSchedule.kt`, `can/SlcanConnection.kt`) ([#184](https://github.com/klexical/openRS_/issues/184))
- **Room database v10→v11 migration** — adds `knock_events.cylinder` and per-drive fuel trim columns on `drives` (`avgStftPct`, `avgLtftPct`, `trimSamples`), so rc.5 needs a single migration. (`data/DriveDatabase.kt`)
- **Knock CSV schema v1 → v2** — trailing `cylinder` column on `drive_knock_*.csv`; marker bumped to `# openrs_knock_csv_v2`. (`diagnostics/DriveExportBuilder.kt`)
- **Test coverage** — new PcmPollScheduleTest (6), FreshnessGateTest (5), KnockCaptureTest (4), TimeSeriesMathTest (5), FuelTrimTest (8), plus `mergeStamps` (3), boost-sweep sentinel (4), CAN freshness (3), KnockStats (2) and decoder identity/guard (4 new, 2 rewritten) and fuel-trim export (3) cases; KnockLoggerTest now exercises the shared `KnockStats` instead of re-implementing the math; `inferType` knock_csv case. Total: **597 tests across 26 files** (Android) + **168 vitest across 6 files** (Sapphire, 5 new knock-exclusion cases).

### Added (rc.4 — Tune Analysis)
- **Knock Event Logger** — PCM knock-retard captures (DID 0x03EC) are now persisted per-drive to Room and surfaced on the PERF tab as an RPM × boost scatter plot, colored by severity (yellow <2°, orange 2-4°, red ≥4°). Aggregate card shows total count, worst cell, average, and per-band breakdown. Events include RPM, boost PSI, IAT, throttle %, estimated gear, and optional GPS fix. Deduped against the previous captured value so steady-state retard doesn't flood the table. Exports as `drive_knock_*.csv` in diagnostic ZIP. ([#174](https://github.com/klexical/openRS_/issues/174))
- **Boost Target vs Actual Sweep** — DrivePointEntity extended at Room v9→v10 with `tipActualKpa`, `boostTargetKpa` (DID 0x0466), and `wgdcPct` (DID 0x0462) columns captured at 1 Hz during recording. DriveTimeSeries chart adds TARGET and WGDC channels alongside existing BOOST. DriveSummarySheet displays a BOOST SWEEP section with average Δ, worst Δ at RPM, max WGDC with saturation flag (≥95% = wastegate floored). `BoostSweepStats` pure-function analytics (`data/BoostSweepStats.kt`) filters to loaded samples (target ≥ 110 kPa) so idle noise doesn't skew averages. Boost-sweep stats included in Sapphire profile JSON export. ([#175](https://github.com/klexical/openRS_/issues/175))
- **0x040 SRS airbag frame recognition** — passive HS-CAN frame added to `KNOWN_IDS` so FrameCoverageGrid shows it as live. Byte2 bits 7:6 pattern documented inline (0x80 empty / 0x40 occupied / 0xC0 bulb-check or fault / 0xD0 bulb-check sweep). Lamp state is validated at the decoder level but not yet surfaced on VehicleState — the 162-param data class is at the JVM 255-slot ceiling and will be migrated after a slot-freeing pass in a future RC.
- **0x300 charging-system fault frame reserved** — ID added to `KNOWN_IDS` so unknown-frame logger doesn't spam it; byte/bit mapping deferred pending a car-side capture of a known-fault vs clean-state comparison.

### Fixed (rc.4)
- **0x1A4 ambient temperature wrapped negative above 32°C (90°F)** — the ambient broadcast from the GWM MS-CAN bridge was being treated as a signed int8 (`data[4].toInt()`), so raw 0x82 (130) read as -126 → -31.5°C instead of +32.5°C. Formula switched to unsigned (`data[4].toInt() and 0xFF`), matching on-car verification. Three CanDecoderTest cases cover low-range, 0x82 canonical case, and 0xFF upper-ceiling.
- **0x435 per-zone airbag crosstalk explicitly guarded** — the six per-zone airbag status signals on 0x435 share bits with passenger-occupancy and are known to false-trigger the SRS lamp in-vehicle. We never decoded 0x435, but added an explicit test (`0x435 is NOT in KNOWN_IDS to avoid airbag lamp crosstalk`) so future contributors don't add it without a verified fault-vs-occupancy capture.

### Changed (rc.4)
- **Room database v9→v10 migration** — adds the `knock_events` table (driveId FK + CASCADE, indexed) and three columns on `drive_points` (`tipActualKpa`, `boostTargetKpa`, `wgdcPct`). Backward-compatible ALTER TABLE pattern matching the existing MIGRATION_8_9 shape. (`data/DriveDatabase.kt`)
- **Drive CSV schema v1 → v2** — three new trailing columns on `drive_*.csv` output (`tip_actual_kpa`, `boost_target_kpa`, `wgdc_pct`). Schema version comment bumped to `# openrs_csv_v2`. Sapphire and third-party importers that key off the schema marker continue to parse v1 files correctly. (`diagnostics/DriveExportBuilder.kt`)
- **Test coverage** — 7 new KnockLoggerTest cases, 7 new BoostSweepStatsTest cases, 7 new CanDecoderTest cases (ambient regression + SRS frame recognition + crosstalk guard + 0x300 reservation). Total: **550 tests across 20 files**, zero failures.

### Added (rc.1 — "Daylight" UI overhaul)
- **5-tab layout** — restructured navigation: DRIVE (curated live cockpit), PERF (POWER + G-force), THERMAL (powertrain temps + chassis/TPMS), TRIP (live HUD + history), GARAGE (settings + diagnostics via segmented picker). Replaces the 7-tab DASH/POWER/CHASSIS/TEMPS/MAP/DIAG/MORE layout.
- **ThemeMode (NIGHT / DAY / AUTO)** — true light/dark theme system replaces the brightness slider. Hand-tuned DAY palette; AUTO switches on a 06:00–19:00 schedule. (`ui/Theme.kt`, `ui/SettingsSheet.kt`)
- **ULTRA AMOLED theme** — pure-black `#000000` background option clamps surface tones for OLED panels; saves power and eliminates passenger side-eye bloom on night drives. Three-way `when` on the 6 surface colors only — semantic colors unchanged.
- **Rajdhani typography** — automotive-style numerics (medium/semibold/bold) for DRIVE/DataNum/Hero values. Orbitron retired from primary use.
- **Tabular numerals** — `fontFeatureSettings = "tnum"` on numeric text styles eliminates lateral shimmy on fast-changing values like RPM, speed, and temps.
- **FieldState availability tracking** — `VehicleState.fieldLastUpdateMs` map + `markFresh()` in OBD parsers. UI surfaces WARMING / AVAILABLE / STALE / UNAVAILABLE sub-labels on TempsPage cells, GARAGE/DIAG battery cells, and TPMS tire cards. (`data/FieldState.kt`, `data/VehicleState.kt`)
- **ThermalBand color/pattern encoding** — central `ThermalBand {COLD/NOMINAL/HOT/CRITICAL}` + `AfrBand {RICH/STOICH/LEAN}` helpers map values to theme-aware colors. DataCell renders a chevron pattern indicator so state is encoded by shape + color, not color alone (improves color-blind readability). (`data/ThermalBand.kt`)
- **Settings navigator overhaul** — categorized layout (DISPLAY / UNITS / CONNECTION / DRIVES / DIAGNOSTICS / ABOUT) with fuzzy search (exact → prefix → substring → subsequence ranking + match-count chip), breadcrumb back nav, per-setting `?` help toggles. Replaces the 1148-line flat scroll. Cog in the status bar is the sole entry point. (`ui/SettingsSheet.kt`)
- **SATELLITE map default** — TripPage opens to satellite imagery; DAY mode uses Google's default light basemap, NIGHT keeps the openRS_ dark style on NORMAL only.
- **Adaptive zoom on DRIVE** — gear label inflates 72→140sp and the page auto-zooms above 16 kph for 3s after each acceleration event. Setting `driveAutoZoom` defaults ON.
- **Diagnostic export bundles active drive** — exporting from DIAG with a recording in progress now folds `drive_*.csv` + `drive_*.gpx` + `drive_summary_*.txt` into the diagnostic ZIP. Sapphire imports gain trip context. (`diagnostics/DiagnosticExporter.kt`, `service/DriveRecorder.kt`)
- **Drive-mode change haptic feedback** — `HapticFeedbackType.Confirm` on success, `Reject` on Busy/Failed/CorrectionFailed/NoConfirmation. Confirms the transition without watching the MODE pill. (`ui/MorePage.kt`, `ui/DriveModeDock.kt`)
- **DTC badge on GARAGE nav icon** — small Warn dot appears when an active DTC is found; clears on visit/clear. Surfaced via `OpenRSDashApp.activeDtcCount: MutableStateFlow<Int>` so users on DRIVE see codes without needing to open GARAGE.
- **Peak-hold long-press on hero cards** — long-press BOOST/RPM/SPEED on DRIVE flips the value to the session peak for 3s with `LongPress` haptic; unit label changes to `PEAK · X`. Opt-in via nullable param so unrelated heroes keep normal touch behavior. (`ui/Components.kt`, `ui/DrivePage.kt`)
- **Drag-to-reorder on Custom Dashboard** — long-press the arrow column on any gauge in the dash builder to drag-reorder. Dragged item elevates with accent border + slight scale; threshold-based swap with `TextHandleMove` haptic per swap. Up/down arrows kept as accessibility fallback. (`ui/CustomDashPage.kt`)
- **DTC scan abort** — long scans now show `■ ABORT (Ns)` in Warn while running; tap to cancel the in-flight `dtcScanJob`. (`ui/DiagPage.kt`)
- **Auto-record ARMED indicator** — when auto-record is enabled, connected, not yet recording, and RPM ≤ 400, a Mid-color `ARMED` chip pulses every 1600ms so the user knows the recorder is waiting on the engine. (`ui/MainActivity.kt`)
- **DRIVE disconnected banner** — when CAN is disconnected, the page dims to 50% and shows `CAN disconnected — last update Xs ago` so stale values are obvious.
- **Fuel-critical pulse** — fuel cell background pulses Warn at 1.4s when level is in 0.01–9.99% range. Above 10% renders identically to today.
- **Gear-change pop** — DRIVE hero gear label gets a 1.05× spring bounce + `TextHandleMove` haptic on each shift. Skips initial composition.
- **REC pause indicator** — REC dot survives pause: changes to Warn color with 1400ms pulse and a `PAUSED` label.
- **Drive-mode pill pulse + dock dim during in-flight commands** — header MODE pill pulses while a drive-mode command is in flight; non-pending DriveModeDock buttons dim to 0.5α. Wired through new shared `driveModePending: MutableState<DriveMode?>`. (`can/DriveCommand.kt`)
- **DriveCard selection animation on TripPage** — selecting a drive now animates the border/bg via `animateColorAsState` and reveals SHARE/RENAME actions with `AnimatedVisibility` (fadeIn + expandVertically). (`ui/trip/TripPage.kt`)
- **Snackbar host replaces remaining Toasts** — PID catalog "Copied {DID}" now uses a Compose `SnackbarHostState` threaded from DiagPage. Themed and config-change-safe. (`ui/PidBrowserSection.kt`, `ui/DiagPage.kt`)
- **Card depth in DAY mode** — `cardGlow()` switches from a radial halo to a soft directional drop-shadow in DAY (NIGHT/ULTRA path unchanged). Reads as elevation, not bloom, against the lighter background. (`ui/anim/GlowModifiers.kt`)
- **Status bar icon tint follows theme** — `enableEdgeToEdge` now applies `SystemBarStyle.dark`/`light` based on `ThemeMode` so system clock/battery/signal stay legible in both DAY and NIGHT.
- **Bottom nav bar accessibility** — each tab gets `Role.Tab` semantics + `contentDescription`. TalkBack now announces tabs.
- **TripPage empty-state copy** — empty-state text adapts to whether AutoRecord is enabled, guiding users to the correct setting.
- **Settings help coverage expansion** — edge-shift Color/Intensity/ShiftRpm + Auto-reconnect toggle now have help strings via the `?` toggle.
- **DAY-mode muted-text contrast floor** — `textMutedAlpha(base)` floors alpha at 0.55 in DAY (vs 0.35 in NIGHT). Applied across placeholder pulse + GForcePlot axis labels for WCAG-friendlier contrast in bright conditions.
- **HeroCard valueFraction border glow** — border alpha lerps with valueFraction (dormant 0.15 → active 0.35).
- **Pager crossfade between tabs** — `currentPageOffsetFraction` drives `graphicsLayer.alpha` so swipes between tabs cross-fade rather than hard-cut.
- **Pre-allocated hex lookup in DiagnosticLogger** — `format()` + hex conversion moved outside the lock; lock-scope reduction is the real win for SLCAN log throughput.

### Fixed (rc.1)
- **Auto-record started drives without a CAN connection** — enabling Auto Record Drives previously began recording the moment the phone joined the adapter WiFi, even with the car off. Rewired to gate on `vs.isConnected && vs.rpm > 400`. `stopConnection()` now `pauseDrive()` (not `stopDrive()`); a 15-minute resume window seamlessly continues the same session across brief disconnects, with orphan cleanup on app start. (`service/CanDataService.kt`, `service/DriveRecorder.kt`, `data/DriveDatabase.kt`)
- **PTU visually stacked on RDU in chassis diagram** — PTU was drawn at canvas-center X like RDU, making them look like a single stacked component. Moved off-center left, raised the canvas, angled the propshaft from PTU right-bottom to RDU top-center. (`ui/ChassisPage.kt`)
- **BLE scan never found adapter** — scan timeout extended 10s → 18s (some adapters advertise at 2–5s intervals), added optional filterless mode (orange "SHOW ALL BLE DEVICES" button) and an empty-state hint with power-cycle/WiFi-coexistence guidance. (`can/BleDeviceScanner.kt`, `ui/BleDevicePickerDialog.kt`)
- **Battery voltage rounded to 1 decimal** — formula was correct but UI hid real variation (14.74 vs 15.30V). Now 2 decimals in DiagPage, DashLayout, and DiagnosticReportBuilder.
- **`ObdResponseParser.mergeObdState` map churn** — generic-PID decode was allocating a fresh `LinkedHashMap` per response. Switched to a delta-builder so a single `copy(genericValues = genericValues + delta)` runs per response.
- **`SlcanParser` per-frame substring allocations** — three `msg.substring()` calls plus a `ByteArray { msg.substring(...).toInt(16) }` builder per frame ran ~1k allocations/sec on 500 kbps HS-CAN. Replaced with index-based hex parse. Pure refactor; covered by existing 20-case `SlcanParserTest`. (`can/SlcanParser.kt`)
- **`ShiftLightBar` allocated infinite transition on every redline cross** — hoisted `rememberInfiniteTransition` to the composable root, gated the value with `if (isRedline) ... else 0f`. Mirrors the A3 fix in `EdgeShiftLight`.
- **`TcpSlcanTransport` / `BleSlcanTransport` line-buffer churn** — class-level `StringBuilder` reuse with reset after each frame instead of per-line allocation.
- **Sparkline `snapshot()` recomputed every frame** — wrapped in `remember(lastSample)` so the snapshot only re-runs when a new sample arrives. (`ui/DrivePage.kt`)
- **`onObdUpdate` fanned out off the main thread** — wrapped in `launch(Dispatchers.Main.immediate)` at the CanDataService binding site.
- **`TextStyle` allocations in GForcePlot/Sparkline** — hoisted out of `remember(density)` so they're not effectively per-composition.
- **`PidRegistry` init-once sanity** — added a single debug log behind the lazy builder + verified one print per process before removal.
- **Redline flash too aggressive** — peak alpha 0.35 → 0.20, rectangle clamped to top 40% of screen.
- **BLE device name overflow in connection banner** — truncated to 12 chars + ellipsis.
- **`TempCard` placeholder pulse jittery** — pulseMs varies by state (WARMING 800 / AVAILABLE 900 / STALE+UNAVAILABLE 1200).
- **DAY-mode card border alpha too thin** — `borderAlpha(base)` doubles alpha in DAY (floor 0.25, ceiling 0.85). Applied across status bar bottom + 7 Components.kt borders + 1 DrivePage border.

### Changed (rc.1)
- **Brightness slider replaced** — the linear lerp brightness slider is gone. Theme is now ThemeMode (NIGHT / DAY / AUTO / ULTRA). Color tokens stay snapshot-driven.
- **`@Immutable` annotation on `VehicleState`** — Compose treats the data class as stable, dropping recompositions when unrelated fields change.
- **`Brush.radialGradient` hoisted in glow modifiers** — reused via `remember` instead of allocated per-composition.
- **Hero numerics no longer animated** — RPM/speed/boost render raw values (springs were causing perceived lag on fast-changing readings). Throttle/brake BarCard springs kept since their rate of change is slower.
- **Trip live page** — TripPage replaces the live-mode pane with a LiveHud and surfaced controls for color mode/map type/weather/zoom/recenter directly on the map.
- **DiagPage developer sections collapsed** — CRASH HISTORY / DID PROBER / LIVE CAN OUTPUT / FRAME INVENTORY / PID BROWSER now live under a single collapsible `DEVELOPER` section (each sub-section still individually collapsible).

### Removed (rc.1)
- **`PerformanceTimer`** — class deleted along with TripPage `CompactPerfTimer` and MorePage `PerformanceTimerSection` references. Will return as a TripPage feature later if requested.
- **Prototype directory** — `ui/prototype/` (7 files: PrototypePreview + 5 tab previews + PrototypeChrome) plus the debug-only entry button in SettingsSheet.
- **Old ChassisPage** — file removed; the `UnifiedChassisSection` migrated into THERMAL alongside the powertrain temps.

### Added (rc.2 — features + visual)
- **Performance Timer** — 0-60 / 0-100 mph state machine (`IDLE → ARMED → RUNNING → FINISHED`) driven by CAN speed at ~100 Hz. UI card on PERF tab with haptics and session best tracking. (`data/PerformanceTimer.kt`, `ui/PerfPage.kt`)
- **Fuel economy section on DRIVE** — instant/average MPG, distance-to-empty, and fuel used via `FuelEconomy.kt` wired to CanDataService on CAN IDs 0x130 (speed) + 0x380 (fuel level). (`service/CanDataService.kt`, `ui/DrivePage.kt`)
- **Density altitude** — computed property on VehicleState using Koch chart from intake air temp + barometric pressure. Shown in THROTTLE & BOOST section on PERF tab. (`data/VehicleState.kt`, `ui/PowerPage.kt`)
- **Knock event counter** — `knockEventCount` StateFlow on OpenRSDashApp increments when any cylinder KR < −1.0 and value changed from previous reading. Badge renders on ENGINE MANAGEMENT section header via new `SectionLabel` badge params. (`OpenRSDashApp.kt`, `ui/Components.kt`, `ui/PowerPage.kt`)
- **Live session stats strip** — REC strip at the top of DRIVE page shows elapsed time, distance, average speed, and point count during active recording. (`ui/DrivePage.kt`)
- **Sparklines on DataCell + TempCard** — optional `sparklineData` parameter on DataCell and TempCard. Oil temp + coolant sparklines on THERMAL (1 Hz, 60-sample); AFR SEN1 + STFT/LTFT sparklines on PERF (4 Hz, 60-sample). (`ui/Components.kt`, `ui/TempsPage.kt`, `ui/PowerPage.kt`)
- **Steering angle arc** — semicircle arc indicator sweeps with steering angle in the G-Force section, with glow dot at the arc tip. (`ui/anim/SteeringArc.kt`, `ui/ChassisPage.kt`)
- **Launch-control peripheral edge glow** — `LaunchControlEdgeGlow` paints a warn-yellow frame at 700ms breath rhythm while `vs.launchControlEngaged` is true. Distinct cadence from `EdgeShiftLight` so the two cues never collide; mirrored in the anomaly strip as "⚡ LAUNCH CONTROL". (`ui/anim/LaunchControlEdgeGlow.kt`)
- **Idle breathing animation** — hero row pulses ±0.03 alpha at idle RPM (<800); rhythm tracks combustion cadence via RPM-derived period. (`ui/DrivePage.kt`)
- **Recording overlay in header** — REC indicator shows `REC MM:SS · X.X mi/km` with elapsed time and distance instead of just the red dot. (`ui/MainActivity.kt`)
- **G-force trail direction colors** — trail dots tint warm (left), cool (right), green (accel), red (brake) with 30% blend over the age gradient. (`ui/anim/GForcePlot.kt`)
- **AWD flow dots** — dot count scales 2→5 with torque magnitude; glow ring appears on high load (>500 Nm). L/R delta pulse (alpha 1.0↔0.6 at 1.2s) on torque imbalance >200 Nm. (`ui/DrivePage.kt`, `ui/ChassisPage.kt`)
- **Tab identity colors on bottom nav** — when `navTabIdentity` is enabled, active tab icon uses per-tab accent color; indicator color follows. (`ui/BottomNavBar.kt`, `ui/Theme.kt`)
- **Connection pill quiet mode** — pill dims to 0.6α after 2s of stable connection when `livePillQuiet` is enabled. Reverts on disconnect or state change. (`ui/MainActivity.kt`)
- **OEM polish prefs** — three new settings (navTabIdentity, livePillQuiet, gearModeTint) with toggles in VISUAL POLISH section of Settings. (`ui/AppSettings.kt`, `ui/UserPrefs.kt`, `ui/SettingsSheet.kt`)
- **Page entrance stagger** — `pageEntrance()` utility applies fade+slide-up with 40ms stagger delay on DrivePage, PowerPage, DiagPage, and MorePage. (`ui/anim/StaggeredEntrance.kt`)
- **WhatsNew dialog animation** — scale+fade entrance with 60ms stagger per item. (`ui/WhatsNewDialog.kt`)

### Fixed (rc.2)
- **Drive-mode dock cleanup on mid-flight dismiss** — `driveModePending` and `modeChangeBreath` now clear in a `finally {}` block so the header MODE pill pulse and bottom-nav indicator tint can't get stuck if the dock composition is disposed before the coroutine completes. (`ui/DriveModeDock.kt`)
- **BLE write deadlock on adapter disconnect** — `writeLine` blocked on `writeComplete.await()` forever when the adapter disconnected mid-write, deadlocking `writeMutex` and freezing all subsequent writes until app restart. `onConnectionStateChange(DISCONNECTED)` and `close()` now complete deferreds exceptionally; `writeCharacteristic` return value is checked. (`can/BleSlcanTransport.kt`)
- **DriveRecorder point loss on stop/crash** — `flushBuffer()` was fire-and-forget after clearing the buffer, so up to 30 points were lost when the scope cancelled mid-write. Now a suspend fn wrapped in `withContext(NonCancellable + Dispatchers.IO)`. `stopDrive()` awaits `job.join()` before writing the drive summary. (`service/DriveRecorder.kt`)
- **CAS side-effect bug in CanDataService** — `PerformanceTimer.onSpeedUpdate()`, `FuelEconomy.onUpdate()`, and `knockEventCount.update{}` were called inside `vehicleState.update{}` CAS lambda, causing double side effects on retry. Moved all three outside the update block. (`service/CanDataService.kt`)
- **PerformanceTimer `@Volatile` missing** — 6 shared primitives accessed from CAN thread + Main thread without memory visibility guarantees. Added `@Volatile`. (`data/PerformanceTimer.kt`)
- **Idle breathe animation dead code** — multiplier 4.0 produced 300–600ms durations always clamped to the 1800ms minimum. Changed to 30.0 so duration actually varies with RPM. (`ui/DrivePage.kt`)
- **DiagPage hardcoded dark backgrounds** — 5 `Color(0xFF06...)` backgrounds replaced with theme-aware `Surf3`, `Orange.copy()`, and `Ok.copy()` so LIVE CAN OUTPUT, DTC results, and clear confirmations follow DAY/NIGHT/ULTRA. (`ui/DiagPage.kt`)
- **Cog button invisible on NIGHT/ULTRA** — `Surf2`/`Brd` bg+border blended into `Bg` on dark palettes. Restored Mid-tinted pill styling matching MODE/ESC pill vocabulary. (`ui/MainActivity.kt`)
- **Settings RESET incomplete** — live-preview prefs (themeMode, classicFonts, driveAutoZoom, themeId) weren't restored; `tireWarnPsi`/`tireHighPsi` missing entirely. Rebuilt as stage-then-commit: local state vars back the pickers, RESET updates only locals, SAVE commits. (`ui/SettingsSheet.kt`)
- **DTC PERMANENT hardcoded color** — `Color(0xFFFF8C00)` replaced with theme-aware `Orange`. Was the only DTC status not using a theme token. (`ui/DiagPage.kt`)
- **AnomalyStrip TextStyle per-recomposition allocation** — wrapped in `remember {}` to avoid recreation during reconnecting dot animation. (`ui/MainActivity.kt`)
- **Theme.kt stale font docstring** — comment claimed retired TTFs still lived in `res/font/`; updated to describe Kotlin alias retention. (`ui/Theme.kt`)

### Changed (rc.2 — header + drive mode)
- **Header geometry stabilised** — unified center-pill shape across all connection states, fixed-width 3-char connection pill (LIVE/IDLE/OFF/RCN/UPD), compact settings cog with Mid-tinted pill styling. (`ui/MainActivity.kt`, `ui/DesignTokens.kt`)
- **MODE + ESC always visible when connected** — pills no longer gated behind "Normal" header context; anomalies (e-brake, launch control, reconnecting, updating, offline) slide into a new 18dp anomaly strip below the header. (`ui/MainActivity.kt`)
- **Drive-mode dock is the single source of truth** — MorePage's interactive N/S/T/D grid removed in favor of a display-only readout + "CHANGE MODE ▾" button that opens the dock. (`ui/MorePage.kt`, `ui/GaragePage.kt`)
- **Pull-down gesture on the header** — vertical drag ≥ 40dp on the status bar opens the drive-mode dock from any tab. (`ui/MainActivity.kt`)
- **Cinematic drive-mode change** — mode-color shimmer sweep on pending button; mode-specific haptic on success (Normal: VirtualKey / Sport: Confirm / Track: Reject / Drift: GestureEnd); ambient mode-color breath on bottom-nav indicator. (`ui/DriveModeDock.kt`, `ui/BottomNavBar.kt`, `can/DriveCommand.kt`)

### Changed (rc.2 — GARAGE + layout)
- **GARAGE CONTROL tab redesign** — MorePage restructured into two zones: VEHICLE CONTROLS (drive mode readout + ESC selector) and FIRMWARE STATUS (collapsible module cards + firmware banner). VIN readout moved to DiagPage. Custom Dashboard and Sapphire Web Dashboard moved to Settings nav entries. (`ui/MorePage.kt`, `ui/DiagPage.kt`, `ui/SettingsSheet.kt`)
- **DRIVE hero reorder** — top-3 hero row changed from BOOST|RPM|SPEED to RPM|BOOST|SPEED. BOOST keeps its 1.25× weight so it remains the visual center. (`ui/DrivePage.kt`)
- **Chassis PTU centered** — PTU position moved from off-center left to directly above RDU; propshaft now vertical. (`ui/ChassisPage.kt`)

### Changed (rc.2 — OEM polish + animation)
- **cardGlow v2** — DAY mode uses directional drop-shadow instead of radial halo; night specular intensity increased (0.07→0.10); `tint` parameter for per-card specular control. (`ui/anim/GlowModifiers.kt`)
- **DataCell value crossfade** — `AnimatedContent` with 120ms crossfade on value changes. (`ui/Components.kt`)
- **BarCard fill glide** — `animateFloatAsState` 240ms EaseOutCubic on bar fill. (`ui/Components.kt`)
- **SectionLabel quieted** — 8sp size, 0.5sp letter-spacing for less visual weight. (`ui/Components.kt`)
- **GEAR panel mode-color tint** — gear label tints to mode color (Sport=Ok, Track=Warn, Drift=Orange) when `gearModeTint` enabled; inner vignette on the gear panel. (`ui/DrivePage.kt`)
- **AWD clutch engagement spring** — clutch engagement animation changed from `tween(300)` to `spring(DampingRatioMediumBouncy, StiffnessMediumLow)`. (`ui/ChassisPage.kt`)
- **Spring physics on expandVertically** — all `expandVertically()` / `shrinkVertically()` transitions across 8 files switched to spring animations. (`ui/SettingsSheet.kt`, `ui/DiagPage.kt`, `ui/MorePage.kt`, and others)
- **Sparkline clipRect draw-on** — 400ms reveal animation on first render. (`ui/anim/Sparkline.kt`)
- **GForcePlot trail fade-in** — 600ms fade-in on trail appearance. (`ui/anim/GForcePlot.kt`)
- **Tire temp color crossfade** — 400ms crossfade in NeonTireCard. (`ui/Components.kt`)
- **Connection pill pressClick + crossfade** — `pressClick(0.97f)` + haptic + `AnimatedContent` label crossfade on state change. (`ui/MainActivity.kt`)
- **Trip floating controls stagger** — 50ms slide-in stagger; drive history list 50ms stagger capped at 400ms. (`ui/trip/TripPage.kt`)
- **Settings category stagger** — 30ms stagger on category and section reveals. (`ui/SettingsSheet.kt`)
- **DTC results stagger** — 60ms stagger on scan results. (`ui/DiagPage.kt`)

### Removed (rc.2)
- **Barlow Condensed + Share Tech Mono TTFs** — 5 font files deleted from `res/font/`. Kotlin aliases (`ShareTechMono`, `BarlowCond`) retained for compile compatibility. (`ui/Theme.kt`)
- **`EBrakeWarningBanner` composable** — 38-line function never called after e-brake moved to AnomalyStrip. (`ui/MainActivity.kt`)
- **`AnomalyStripCalmHeight` token** — unused after calm treatment removed. (`ui/DesignTokens.kt`)
- **MorePage interactive mode grid + VIN/Custom Dash/Sapphire sections** — replaced by dock readout card and Settings nav entries. (`ui/MorePage.kt`)

### Added (rc.3 — trip tab overhaul)
- **Post-drive summary sheet** — ModalBottomSheet with duration, distance, fuel used, economy, peaks (speed/RPM/boost/G), thermal progression (start→peak→end for oil + coolant), mode breakdown bar. Triggered automatically on drive completion or via SUMMARY button on history cards. (`ui/trip/DriveSummarySheet.kt`) ([#177](https://github.com/klexical/openRS_/issues/177))
- **Post-drive time-series charts** — 6-channel scrollable chart (speed, RPM, boost, oil temp, lateral G, throttle) with pill toggles and 360-point downsample. Embedded in drive summary sheet. (`ui/trip/DriveTimeSeries.kt`)
- **Session replay** — full-screen animated playback of recorded drives on the map with growing polyline, synced HUD, scrubber slider, 1×/2×/4× speed, and ±10s skip. (`ui/trip/DriveReplayView.kt`)
- **Route preview thumbnails** — 120×80 Canvas→Bitmap→PNG cached per drive, generated on drive stop, displayed in drive history cards. (`ui/trip/RouteThumbnail.kt`)
- **Drive comparison** — side-by-side ModalBottomSheet comparing 11 metrics between two drives with green/red deltas and unit-aware formatting. (`ui/trip/DriveComparisonSheet.kt`)
- **Lap timer with start/finish geofence** — state machine (IDLE→ARMED→TIMING) with 15m geofence, 20 kph min speed, ±30° heading filter, 10s debounce, per-lap peak tracking. SET S/F button in map floating controls. (`data/LapTimer.kt`, `ui/trip/LapTimerOverlay.kt`)
- **AWD torque vectoring color mode** — rear-vs-front wheel speed delta (0.5/2.0/4.0 km/h thresholds) on the drive map. (`ui/trip/DriveMap.kt`)
- **Braking & corner dynamics color mode** — brake zones (brakePressure >50 or longG <−0.2), cornering (|latG| >0.3), and cruise regions visualized on the map. (`ui/trip/DriveMap.kt`)
- **Selective export** — ExportOptionsSheet with per-component checkboxes (CSV, GPX, summary, profile JSON, DTC). (`ui/trip/ExportOptionsSheet.kt`)
- **Batch multi-drive export** — long-press multi-select in drive history packages all selected drives into a single ZIP with manifest. (`diagnostics/DiagnosticExporter.kt`, `ui/trip/TripPage.kt`)
- **Drive bookmarks** — tap MARK during recording to save GPS position with telemetry snapshot. Rendered as accent markers on the map, included in profile JSON and export ZIP. Room DB migration v8→v9. (`data/DriveDatabase.kt`, `service/DriveRecorder.kt`, `ui/trip/DriveMap.kt`)
- **Drive tags** — 5 tag chips (TRACK/COMMUTE/CRUISE/SPIRITED/RAIN) in expanded drive cards, persisted as CSV in DriveEntity.tags. (`data/DriveDatabase.kt`, `ui/trip/TripPage.kt`)
- **Aggression score** — 0–100 from weighted formula (30% throttle, 25% lateral G, 25% RPM fraction, 20% boost fraction). Colored badge on drive cards and summary sheet. (`service/DriveRecorder.kt`, `ui/trip/TripPage.kt`)
- **Auto-name drives via Geocoder** — reverse geocode on drive stop with thoroughfare→locality→subAdmin→admin fallback chain. (`service/DriveRecorder.kt`)
- **Boost gauge in live HUD** — replaced AVG RPM with BOOST PSI + inline bar (0–22 PSI scale) with color coding. (`ui/trip/TripPage.kt`)
- **DTE + altitude + heading in route overlay** — distance-to-empty, altitude (m/ft), heading (degrees, suppressed <5 kph), GPS accuracy dot (Ok <5m, Warn <15m, Orange). (`ui/trip/TripPage.kt`)
- **Wheel slip delta view** — tap Row 4 on the live HUD to toggle raw wheel speeds ↔ deltas (F-R, L-R, FL-RL, FR-RR) with Ok/Warn/Orange color coding. (`ui/trip/TripPage.kt`)
- **Brake pressure + steering angle in trip CSV** — new columns in drive exports. DrivePointEntity migration v4→v5 adds longitudinalG, brakePressure, steeringAngle. (`data/DriveDatabase.kt`, `diagnostics/DriveExportBuilder.kt`)
- **Profile JSON v2 in exports** — `buildProfileJson()` generates drive profile with summary, peak events with GPS, thermal progression, mode timeline, bookmarks, laps, performance runs, and RDU/PTU thermal peaks. Included in ZIP as `drive_profile_{ts}.json`. (`diagnostics/DriveExportBuilder.kt`, `diagnostics/DiagnosticExporter.kt`)

### Added (rc.3 — DTC scanner overhaul)
- **Parallel multi-module DTC scan** — burst-sends all 6 module request frames (50ms gaps) with per-module IsoTpBuffer instances and a single receiver loop. Scan time ~3–5s, down from ~15s sequential. (`diagnostics/DtcScanner.kt`, `can/SlcanConnection.kt`)
- **DTC Room persistence** — `dtc_scans` + `dtc_codes` tables with foreign keys and auto-prune to 50 most recent scans. Room DB migration v5→v6. (`data/DriveDatabase.kt`)
- **Freeze frame capture** — UDS 0x19/04 snapshot identification + 0x19/05 snapshot read with ~17 known Ford/SAE DIDs (RPM, speed, coolant, MAF, throttle, boost). Stored as JSON in Room. (`diagnostics/FreezeFrameParser.kt`, `can/SlcanConnection.kt`)
- **DTC detail sheet** — ModalBottomSheet with code/status/severity, description, RS-specific notes, freeze frame table, and occurrence history timeline. (`ui/DtcDetailSheet.kt`)
- **Scan diff** — NEW badges on codes that appeared since last scan; collapsed RESOLVED section for cleared codes. (`data/DtcResult.kt`, `ui/DiagPage.kt`)
- **DTC severity** — derived from SAE code prefix (P0xxx=HIGH, P2xxx=MEDIUM, Bxxxx=LOW, Cxxxx=MEDIUM, Uxxxx=LOW). Severity dot on DTC rows. (`data/DtcResult.kt`, `ui/DiagPage.kt`)
- **Auto-scan on connect** — `autoScanDtcs` setting triggers DTC scan 5s after connection. (`service/CanDataService.kt`, `ui/AppSettings.kt`)
- **DTC status filter chips** — ALL/ACTIVE/PENDING/PERMANENT filter with count badges. (`ui/DiagPage.kt`)
- **Extended session for DTC modules** — UDS 0x10/03 sent before scan for AWD/PSCM/GFM. Per-module RETRY button for timed-out modules. (`data/DtcModuleSpec.kt`, `diagnostics/DtcScanner.kt`)
- **Focus RS DTC notes** — 12 curated RS-specific notes for known DTCs (P0234, P0299, P0401, P0420, P0456, P0507, P2BAF, P0217, U0100, U0073, C0034, B1A85). (`diagnostics/DtcDatabase.kt`)
- **DTC JSON export** — machine-readable `dtc_scan_*.json` in diagnostic ZIP with severity and freeze frame data. (`diagnostics/DriveExportBuilder.kt`, `diagnostics/DiagnosticExporter.kt`)

### Added (rc.3 — data logging & export)
- **Lap results to Room** — `lap_sessions` + `laps` tables. Room DB migration v6→v7. (`data/DriveDatabase.kt`)
- **Performance timer results to Room** — `perf_runs` table. Room DB migration v6→v7. (`data/DriveDatabase.kt`)
- **Fuel economy snapshots** — `avgFuelL100km` and `avgFuelMpg` columns on drives. Room DB migration v7→v8. (`data/DriveDatabase.kt`, `service/DriveRecorder.kt`)
- **Thermal peak snapshots** — `thermalPeaksJson` column on drives with per-sensor peak data. Room DB migration v7→v8. (`data/DriveDatabase.kt`, `service/DriveRecorder.kt`)
- **Export manifest.json** — typed file inventory in every diagnostic and drive export ZIP. (`diagnostics/DiagnosticExporter.kt`)
- **CSV schema version header** — `# openrs_csv_v1` prepended to drive CSV exports. (`diagnostics/DriveExportBuilder.kt`)
- **Export progress indicator** — `ExportProgress` sealed class + StateFlow with animated banner during export. (`ui/trip/TripPage.kt`)
- **Personal bests dashboard** — PersonalBestsCard on PERF tab with DAO aggregate queries across all drives. (`ui/PerfPage.kt`)
- **Drive trend sparklines** — DriveTrendsSection in DriveSummarySheet, 4 metrics across up to 20 recent drives. (`ui/trip/DriveSummarySheet.kt`)
- **Post-drive thermal anomaly detection** — `hasThermalAnomaly` flag and TEMP badge on history cards when temps exceed safe thresholds. (`service/DriveRecorder.kt`, `ui/trip/TripPage.kt`)
- **RaceChrono CSV export** — relative seconds, m/s speed, G-force column naming for RaceChrono import compatibility. (`diagnostics/DriveExportBuilder.kt`)
- **TrackAddict CSV export** — MPH, °F, TrackAddict column naming for TrackAddict import compatibility. (`diagnostics/DriveExportBuilder.kt`)

### Added (rc.3 — diagnostics & adapters)
- **NRC polling optimization** — `NrcTracker` suppresses negative response codes per DID after configurable thresholds (0x31/0x33 after 1 hit, 0x22 after 3 hits). Pollers skip suppressed DIDs. Exported in diagnostic summary + JSON. (`can/NrcTracker.kt`, `can/SlcanConnection.kt`, `diagnostics/DiagnosticLogger.kt`)
- **WiCAN API integration** — `WicanApi` with `checkStatus()` and `reboot()`. Adapter info card on GARAGE tab shows hardware, firmware, device ID, CAN bus, OBD port voltage, and BLE status. REBOOT ADAPTER button with confirmation. (`can/FirmwareApi.kt`, `ui/MorePage.kt`)
- **WiCAN mDNS discovery** — NsdManager-based scanner discovers `_http._tcp` services with "wican" prefix. SCAN NETWORK button in WiFi settings. (`can/WicanDiscovery.kt`, `ui/SettingsSheet.kt`)
- **Frame coverage heatmap** — color-coded grid replacing the text frame inventory: LIVE (green), STALE (yellow), MISSING (red), UNKNOWN (grey). Expandable tiles with hex/decoded/fps detail. (`ui/FrameCoverageGrid.kt`, `diagnostics/DiagnosticLogger.kt`)
- **Predictive thermal alerts** — linear regression over 120s rolling window with time-to-critical extrapolation. Operating floors: Oil >80°C, Coolant >85°C, RDU >40°C, PTU >50°C. Trend arrows on TempCards, pulsing orange alert banner on THERMAL tab. (`data/ThermalPredictor.kt`, `ui/TempsPage.kt`) ([#181](https://github.com/klexical/openRS_/issues/181))

### Added (rc.3 — Sapphire web dashboard)
- **6 parser bug fixes** — CAN frame inventory (object→array), FPS timeline (`relMs` key), decode trace (`id` key), session events (`relMs` key), probe results (nested→flat), `longitudinal_g` CSV column. (`web/src/lib/import.ts`)
- **Profile JSON import** — `parseProfileJson()` with hybrid merge: profile values override CSV-computed summary (peaks, mode breakdown), CSV remains source for per-point time-series. Tags, thermal progression, bookmarks, aggression score, canonical mode timeline. (`web/src/lib/import.ts`, `web/src/types/session.ts`)
- **DTC JSON + freeze frames import** — freeze frames, severity levels, JSON-over-TXT precedence when both present. (`web/src/lib/import.ts`)
- **NRC suppression import** — parses `nrcSuppression` array from diagnostic JSON. (`web/src/lib/import.ts`)
- **Batch multi-drive ZIP import** — detects multiple drives via manifest or filename heuristic, produces one session per drive with per-drive progress bar. (`web/src/lib/import.ts`, `web/src/components/panels/ImportPanel.tsx`)
- **New CSV columns** — brake pressure, steering angle, race ready. (`web/src/lib/import.ts`)
- **Aggression gauge + thermal progression chart** — 0–100 aggression GaugeChart and waterfall chart showing start→peak→end for oil + coolant with delta annotations. (`web/src/components/panels/DashboardPanel.tsx`, `web/src/components/charts/ThermalProgressionChart.tsx`)
- **Bookmark markers on GPS map** — diamond markers with popup showing label + telemetry snapshot. (`web/src/components/charts/GpsMap.tsx`)
- **Brake pressure, steering angle, longitudinal G charts** — new time-series charts on Trip panel, gated on data availability. (`web/src/components/panels/TripPanel.tsx`)
- **Freeze frame expandable detail + severity badges + NRC suppression table** — DTC rows expand to show DID/value sub-table; color-coded severity pills; NRC table with DID, NRC code/name, suppressed count. (`web/src/components/panels/DiagnosticsPanel.tsx`)
- **Extended comparison** — aggression score + fuel used KPI deltas; coolant, oil temp, longitudinal G, brake pressure, steering angle comparison overlays. (`web/src/lib/compare.ts`, `web/src/components/panels/ComparePanel.tsx`)
- **163 vitest tests** — 34 new: profile JSON (8), DTC JSON (4), NRC (2), batch import (4), CSV columns (4), comparison (4), parser bugs (5), manifest (1). (`web/src/lib/import.test.ts`, `web/src/lib/compare.test.ts`)

### Added (rc.3 — tests)
- **531+ unit tests across 21 files** — 150 new Android tests. `DriveExportBuilderTest` (~75), `DiagnosticExporterTest` (~21), `NrcTrackerTest` (12), `ThermalPredictorTest` (17), `FuelEconomyTest` (11), `CrashTelemetryBufferTest` (8), `LapTimerTest`, `PerformanceTimerTest`. (`app/src/test/`)
- **`org.json:json` test dependency** — enables JSONObject/JSONArray assertions in export builder tests without Android runtime. (`app/build.gradle.kts`)

### Fixed (rc.3 — settings & polish)
- **Settings cancel didn't revert live-preview prefs** — theme mode, classic fonts, drive auto-zoom, and paint colour committed to UserPrefsStore immediately on toggle, bypassing cancel. Rebuilt as stage-then-commit: local state vars back pickers, cancel reverts side effects. (`ui/SettingsSheet.kt`, `ui/Theme.kt`)
- **Paint colour stopped previewing after settings fix** — accent preview no longer flowed through UserPrefsStore. Added `_themeAccentPreview` in Theme.kt with CompositionLocalProvider bridge. (`ui/Theme.kt`, `ui/MainActivity.kt`)
- **WARMING tags shown while disconnected** — TempCards displayed WARMING state when the adapter was offline, misleading users. Suppressed when not connected. (`ui/TempsPage.kt`)

### Changed (rc.3)
- **Room database v5→v9** — 4 migrations: v5→v6 (DTC scan/code tables), v6→v7 (lap sessions/laps/perf runs), v7→v8 (fuel economy/thermal peaks on drives), v8→v9 (drive bookmarks). (`data/DriveDatabase.kt`)
- **CrashTelemetryBuffer capacity 100→300** — larger window for pre-crash telemetry context. (`diagnostics/CrashTelemetryBuffer.kt`)
- **SLCAN flush interval 1000→200 lines** — more frequent diagnostic log flushes. (`diagnostics/DiagnosticLogger.kt`)

---

## [v2.2.6] — 2026-04-06

### Added (rc.1 — drive mode reliability)
- **Pre-flight diagnostic logging for drive mode commands** — logs current mode, modeDetail420 hex value, and firmware version before every drive mode command for traceability. (`MorePage.kt`)
- **Auto-correction on drive mode overshoot** — if CAN confirms a mode change but to the wrong mode (e.g. Track instead of Sport), the app automatically sends a corrective command and monitors for confirmation, with snackbar feedback throughout. (`MorePage.kt`)
- **`has420Received` accessor on CanDecoder** — exposes whether at least one 0x420 frame has been received, for diagnostic logging. (`CanDecoder.kt`)

### Added (rc.2 — CAN decoders, performance, economy)
- **Clutch pedal position from CAN 0x138** — decodes 10-bit clutch pedal percentage from PCMmsg10. Displayed on DASH tab below the throttle/brake bars (only visible when pedal is pressed). (`CanDecoder.kt`, `DashPage.kt`) ([#109](https://github.com/klexical/openRS_/issues/109))
- **Wheel rotation counts from CAN 0x1E0** — decodes 4 per-wheel rotation counters and average front wheel speed from ABSmsg06. Displayed in a WHEEL ROTATION row within the TPMS section on CHASSIS tab. (`CanDecoder.kt`, `ChassisPage.kt`) ([#110](https://github.com/klexical/openRS_/issues/110))
- **Passive VIN decode from CAN 0x40A** — assembles 17-character VIN from 3 multiplexed pages (mux bytes C1 00/01/02). Displayed on MORE tab in a VEHICLE IDENTIFICATION section. (`CanDecoder.kt`, `MorePage.kt`) ([#126](https://github.com/klexical/openRS_/issues/126))
- **0-60 / 0-100 performance timer** — singleton `PerformanceTimer` with IDLE/ARMED/RUNNING/FINISHED states, driven by CAN speed data at ~100 Hz. Tracks launch RPM, peak boost, elapsed time, and session best. Displayed on DASH tab with tap-to-toggle target and arm/reset controls. (`PerformanceTimer.kt`, `DashPage.kt`) ([#117](https://github.com/klexical/openRS_/issues/117))
- **Real-time fuel economy** — singleton `FuelEconomy` using a 60-second rolling window of fuel level + speed-integrated distance. Computes instant/average L/100km or MPG, idle fuel rate, and distance to empty. Displayed on DASH tab (appears after 10s of driving). (`FuelEconomy.kt`, `DashPage.kt`) ([#118](https://github.com/klexical/openRS_/issues/118))
- **Configurable TPMS thresholds** — low/warn/high pressure thresholds replace the single hardcoded 40 PSI cutoff. 4-zone color logic: orange (critically low), yellow (getting low), green (optimal), orange (over-inflated). Settings sheet has 3 input fields with validation. (`AppSettings.kt`, `UserPrefs.kt`, `Components.kt`, `CarDiagram.kt`, `SettingsSheet.kt`) ([#168](https://github.com/klexical/openRS_/issues/168))
- **Reset session button on DIAG tab** — 2-step confirmation (RESET SESSION then CONFIRM/CANCEL) below the Capture Snapshot button. Resets CanDecoder state, VehicleState, FuelEconomy, and PerformanceTimer. Only visible when connected. (`DiagPage.kt`, `CanDataService.kt`) ([#169](https://github.com/klexical/openRS_/issues/169))
- **12 new CAN decoder unit tests** — clutch pedal (3), wheel rotation (2), VIN assembly (4 including multi-page, non-C1 mux skip, short data, reset clears state), plus existing test updates. (`CanDecoderTest.kt`)

### Fixed (rc.2)
- **Trip buttons hidden on small devices** — HUD content on TripPage restructured from weight-based spacer push to scrollable content + fixed footer pattern. Button always visible regardless of screen size. PR #166 by @adamsouthern. (`TripPage.kt`) ([#165](https://github.com/klexical/openRS_/issues/165))
- **Battery voltage truncated to 1 decimal place** — changed format from `"%.1f"` to `"%.2f"` on both landscape and portrait BarCard layouts. (`DashPage.kt`) ([#172](https://github.com/klexical/openRS_/issues/172))
- **Custom Dashboard header behind system status bar** — added WindowInsets-based top padding calculation so the header row clears the status bar on all devices. (`CustomDashPage.kt`) ([#171](https://github.com/klexical/openRS_/issues/171))
- **ForegroundServiceStartNotAllowedException crash** — NetworkCallback.onAvailable wrapped startConnection() in try-catch for the Android 12+ restriction on starting foreground services from background context. (`CanDataService.kt`) ([#173](https://github.com/klexical/openRS_/issues/173))
- **Throttle label showed "PEDAL" before data arrived** — conditional label removed, now always displays "THROTTLE". (`DashPage.kt`)

### Changed (rc.2)
- **Session history collapsible** — session history section on MORE tab uses SectionLabel with collapsible toggle and AnimatedVisibility, collapsed by default. ([#170](https://github.com/klexical/openRS_/issues/170))
- **Mission Control HTML removed** — `MissionControlHtmlBuilder.kt`, `mission_control.html`, and uPlot assets (`uplot.min.css`, `uplot.min.js`) deleted. Sapphire web dashboard is the replacement. HTML generation blocks removed from `DiagnosticExporter`. ([#167](https://github.com/klexical/openRS_/issues/167))
- **Firmware updated** — openrs-fw-pro v1.2, openrs-fw-usb v1.61.

### Fixed (rc.2)
- **What's New dialog blank on v2.2.6** — `versionHighlights` map had no `"2.2.6"` entry, so the dialog immediately dismissed. Added v2.2.6 highlights and a fallback that shows the latest entry when no exact version match exists. (`WhatsNewDialog.kt`)

### Added (rc.3 — field-test fixes)
- **TPMS pressure + temperature in trip CSV exports** — `TripPoint` now captures all 8 TPMS fields (4 pressures + 4 temps) at each GPS fix. Exported as `tire_press_{lf,rf,lr,rr}_psi` and `tire_temp_{lf,rf,lr,rr}_c` columns. Enables post-session cold→hot tire pressure analysis. (`TripPoint.kt`, `TripRecorder.kt`, `DiagnosticExporter.kt`) ([#84](https://github.com/klexical/openRS_/issues/84))

### Fixed (rc.3 — field-test fixes)
- **Fuel economy never appeared** — OBD DID 0xF42F and CAN 0x380 both wrote to the same `fuelLevelPct` field. CAN fires at ~3 fps and OBD once per 30s, creating a sawtooth pattern that broke the 60-second rolling window calculation. OBD 0xF42F now stores its value in `genericValues["FuelLevel_OBD"]` for diagnostic visibility; CAN 0x380 is the sole source for fuel economy. (`ObdResponseParser.kt`) ([#118](https://github.com/klexical/openRS_/issues/118))
- **DID prober EXPORT button removed** — clipboard copy of 500+ TSV lines was impractical. Probe results are already included in the diagnostic ZIP as CSV files. Removed button and unused imports. (`DidProberSection.kt`)

### Added (rc.4 — visual polish)
- **Neon glow border system** — 4 new Compose modifiers (`neonBorder`, `neonPulse`, `bloomGlow`, `scanLine`) replace flat `border()` on all card components with gradient-based glow effects. Animated pulse on hero-tier cards, static glow on secondary cards. (`GlowModifiers.kt`) ([#82](https://github.com/klexical/openRS_/issues/82))
- **Design token consolidation** — new `DesignTokens.kt` provides single-source-of-truth spacing, shapes, and sizing constants (`Tokens.PagePad`, `CardGap`, `SectionGap`, `CardShape`, `HeroShape`, etc.) replacing hardcoded dp values across UI files.
- **NeonDivider composable** — accent-colored horizontal gradient divider replacing solid `Brd` rules in section headers. (`Components.kt`)
- **AnimatedHeroNum composable** — `AnimatedContent` wrapper with vertical slide transitions for hero value changes. Targets formatted string to avoid per-frame recomposition. (`Components.kt`)
- **Staggered card entrance animations** — `StaggeredColumn` composable with 40ms per-child fade+slide-up entrance effect. (`StaggeredEntrance.kt`)
- **Press feedback modifier** — `pressScale()` modifier provides tactile scale-down on press with spring-back physics for interactive elements. (`InteractionModifiers.kt`)
- **Peripheral edge shift light** — multi-zone screen-edge glow overlay with three phases keyed to RPM: breathing (70%), progressive fill (81%), flash (95.5%). Configurable color (accent/white/progressive), intensity (low/med/high), and RPM threshold. (`EdgeShiftLight.kt`, `AppSettings.kt`, `UserPrefs.kt`)
- **DiagPage summary strip** — 3-cell overview row (STATUS/FPS/DTCs) at top of DIAG tab for at-a-glance diagnostics without scrolling. (`DiagPage.kt`)
- **DiagPage collapsible sections** — CRASH HISTORY, DID PROBER, LIVE CAN OUTPUT, FRAME INVENTORY, and PID BROWSER sections now collapsed by default with animated expand/collapse. Frame inventory limited to 15 visible rows with "Show all" toggle. (`DiagPage.kt`)
- **HeroCard value-driven glow intensity** — `valueFraction` parameter (0.0–1.0) scales bloom glow and border alpha proportionally. Applied to RPM/6800, boost/180kPa, speed/250kph on DASH tab. (`Components.kt`, `DashPage.kt`)
- **CRT scan line on DASH tab** — faint horizontal light sweep across the dashboard when connected, 4-second cycle at 6% alpha. (`DashPage.kt`)
- **Connection "going live" sweep** — one-shot 800ms accent light band sweeping top-to-bottom across the entire app when adapter connects. (`MainActivity.kt`)
- **Tab crossfade during swipe** — subtle 15% opacity dip on pages during horizontal pager transitions. (`MainActivity.kt`)
- **Connection dot bloom** — `bloomGlow` halo behind the connection status dot when connected. (`MainActivity.kt`)
- **Sparkline glow enhancement** — polyline drawn with 2.5× width ghost layer for glow effect, plus live endpoint dot with bloom halo. (`Sparkline.kt`)
- **SettingsSheet visual upgrade** — accent left-bar section titles, gradient section backgrounds, animated `SegmentedPicker` with color transitions. (`SettingsSheet.kt`)
- **SectionLabel chevron animation** — smooth rotation animation on collapse/expand chevron via `animateFloatAsState` + `graphicsLayer`. (`Components.kt`)
- **AnimatedHeroNum applied to all hero values** — HeroCards, performance timer, and AWD split percentages now use animated vertical slide transitions on value changes. (`Components.kt`, `DashPage.kt`)
- **Press feedback on interactive buttons** — drive mode buttons, ESC buttons, DTC scan button, and performance timer controls now scale down on press with spring-back physics via `pressClick()`. (`MorePage.kt`, `DiagPage.kt`, `DashPage.kt`)
- **Tab bar sliding neon indicator** — active tab indicator converted from per-tab conditional to a single sliding `Box` with spring-animated position. (`MainActivity.kt`)
- **G-Force Plot Compose text rendering** — replaced Android Canvas `drawText` with Compose `rememberTextMeasurer` + JetBrains Mono for consistent typography. Trail points now have radial gradient halos, crosshairs use accent color. (`GForcePlot.kt`)
- **AWD torque flow animation** — animated flow dots travel along the torque split bar proportional to torque delta between left/right. Direction indicates dominant side. (`DashPage.kt`)
- **Enhanced TempCard** — neon glow border (color tracks temp status), radial bloom behind value when above warn threshold, progress bar gets `neonGlowRect` when in warn zone, peak tick mark on progress bar. (`TempsPage.kt`)
- **Staggered entrance on TempsPage** — temperature card grid rows animate in with staggered fade+slide-up on composition. (`TempsPage.kt`)
- **Design token migration** — `Tokens.PagePad` and `Tokens.CardGap` now used across DashPage, PowerPage, ChassisPage, TempsPage, and MorePage replacing hardcoded `12.dp` and `10.dp` values.

### Fixed (rc.4 — visual polish)
- **Gear detection thresholds recalibrated for dual final drive** — previous thresholds were derived from a single log with a 3.82 average final drive. Updated to use midpoints between official MMT6 overall ratios (dual final drive 4.063 gears 1-4, 2.955 gears 5-6 per 2016 Owner's Manual p242). (`VehicleState.kt`)

### Added (rc.5 — drive system overhaul)
- **MAP tab with Google Maps** — new first-class tab (DASH/POWER/CHASSIS/TEMPS/MAP/DIAG/MORE) replacing the old TripPage overlay. Live mode shows Google Maps with dark styling, color-segmented route polylines, and peak markers. History mode lists saved drives with tap-to-view on map. Swapped OSMDroid for `play-services-maps:19.0.0` + `maps-compose:6.4.1`. (`DrivePage.kt`, `DriveMap.kt`, `google_map_style_dark.json`)
- **Room-backed DriveRecorder** — replaces the in-memory TripRecorder with persistent drive tracking. Start/stop/pause/resume controls with 1 Hz GPS + telemetry capture. Batch writes (30 points/flush), peak tracking with GPS coordinates, weather refresh every 15 min. Room migration v1→v2 preserves existing sessions as drives with `hasGps=false`. (`DriveRecorder.kt`, `DriveDatabase.kt`, `DriveState.kt`)
- **Unified drive+diagnostic export** — single ZIP from both MAP tab history and DIAG tab containing GPX, CSV, drive summary, and diagnostic data. GPX uses `<trkseg>` breaks for pause gaps. `DriveEntity.sessionId` links drives to diagnostic sessions. (`DiagnosticExporter.kt`)
- **Auto-record drives setting** — opt-in toggle in Settings (OFF by default) that starts/stops drive recording on adapter connect/disconnect. Max saved drives configurable (default 50), oldest pruned automatically. (`SettingsSheet.kt`, `AppSettings.kt`, `CanDataService.kt`)
- **REC indicator in AppHeader** — pulsing orange dot with "REC" label next to the connection pill when actively recording. (`MainActivity.kt`)
- **Persistent collapsible section states** — section expanded/collapsed preferences saved to SharedPreferences via `rememberSectionExpanded()` composable. Survives tab switches, app kills, and updates. Applied to all 10 collapsible sections across POWER and DIAG tabs. (`AppSettings.kt`, `PowerPage.kt`, `DiagPage.kt`)
- **37 DriveState unit tests** — fuel economy calculations, averages, haversine distance, peak tracking, sentinel defaults, entity immutability. Total suite now 243 tests across 8 files. (`DriveStateTest.kt`)

### Fixed (rc.5 — drive system overhaul)
- **Temps tab completely blank** — `StaggeredColumn` used `visibleState.currentState` to drive animation targets, which lags behind on first composition and left all children at alpha 0. Changed to `visibleState.targetState` which is `true` immediately. DASH and POWER tabs may have been intermittently affected. (`StaggeredEntrance.kt`)

### Changed (rc.5 — drive system overhaul)
- **DiagPage reorganized** — removed duplicate STATUS/FPS summary strip from top. DIAGNOSTICS section moved to top position with DTC count merged in. DTC SCANNER moved below DIAGNOSTICS. Both sections now collapsible. Section order: DIAGNOSTICS → DTC SCANNER → CRASH HISTORY → DID PROBER → LIVE CAN OUTPUT → FRAME INVENTORY → PID BROWSER. (`DiagPage.kt`)
- **MAP pill removed from AppHeader** — redundant with the MAP tab in the tab bar. Connection pill, REC indicator, and settings gear shift to fill the gap. (`MainActivity.kt`)
- **Location permission at startup** — `ACCESS_FINE_LOCATION` requested in `MainActivity.onCreate()` alongside notification permission. Foreground service type updated to `connectedDevice|location`. (`MainActivity.kt`, `AndroidManifest.xml`)

### Removed (rc.5 — drive system overhaul)
- **TripRecorder, TripPage, TripPoint** — old in-memory trip system fully replaced by DriveRecorder/DrivePage. (`TripRecorder.kt`, `TripPage.kt`, `TripPoint.kt`)
- **TripState gutted** — reduced to `PeakType` enum + `PeakEvent` data class only (still used by DriveRecorder/DriveMap). (`TripState.kt`)
- **OSMDroid dependency** — removed from `build.gradle.kts`. (`build.gradle.kts`)
- **Session history from MorePage** — drive history now lives in the MAP tab. (`MorePage.kt`)

### Added (rc.6 — Quick Mode Dock, in-app updates, code review cleanup)
- **Quick Mode Dock** — tap the MODE cell in the telemetry strip to open a dropdown drive mode selector (N/S/T/D) from any tab. Staggered entrance animation, haptic feedback, auto-dismiss on success. (`DriveModeDock.kt`, `MainActivity.kt`)
- **Shared drive mode command flow** — `executeDriveModeChange()` suspend function extracted from MorePage. REST POST → 2s settle → 15s CAN poll → auto-correct on overshoot. Returns `DriveCommandResult` sealed class. Used by both MorePage and DriveModeDock. (`DriveCommand.kt`)
- **In-app update system** — background update checker against GitHub Releases API. Supports stable/beta channels, downloads APK to internal storage, prompts install via `ACTION_INSTALL_PACKAGE`. Settings section for channel picker, check/download/install controls with progress bar. (`update/UpdateManager.kt`, `update/UpdateChecker.kt`, `update/AppVersion.kt`, `update/UpdateState.kt`, `SettingsSheet.kt`, `AppSettings.kt`, `UserPrefs.kt`)
- **74 new unit tests** — `AppVersionTest` (30 tests: version parsing, comparison, RC/beta ordering), `DtcScannerTest` (28 tests: SAE J2012 DTC decoding, UDS status bits, payload parsing), `UserPrefsTest` (16 tests: unit conversions, sentinel edge cases). Total suite now 319 tests across 11 files.

### Changed (rc.6 — Quick Mode Dock, in-app updates, code review cleanup)
- **DiagnosticExporter split into 3 files** — 883-line god object broken into `DiagnosticExporter.kt` (ZIP orchestration + share intents, ~210 lines), `DiagnosticReportBuilder.kt` (summary text + JSON detail), and `DriveExportBuilder.kt` (GPX, CSV, drive summary, DTC report). Crash/probe file bundling extracted into private helpers. (addresses [#83](https://github.com/klexical/openRS_/issues/83))
- **Diagnostic JSON built with JSONObject** — hand-rolled string concatenation in `buildJson()` replaced with `org.json.JSONObject`/`JSONArray`. Eliminates manual comma tracking, bracket nesting, and custom `jsonEscape()` helper. Escaping handled automatically; full-precision numeric values in output. (addresses [#83](https://github.com/klexical/openRS_/issues/83))
- **DiagnosticLogger hex conversion optimized** — pre-allocated 256-entry hex lookup table replaces per-byte `"%02X".format()` calls. `toSpacedHex()`/`toCompactHex()` use `StringBuilder` with pre-known capacity. Hex strings built outside `synchronized(lock)` block, reducing lock hold time at ~2100 fps. (addresses [#83](https://github.com/klexical/openRS_/issues/83))
- **DtcScanner refactored for testability** — `decodeDtcCode()`, `classifyStatus()`, `parsePayload()` moved from instance `private` to `companion object internal`, enabling unit tests without Android Context. (`DtcScanner.kt`)
- **Drive mode command logic extracted from MorePage** — 60 lines of inline command/polling/auto-correct code replaced by a single `executeDriveModeChange()` call. (`MorePage.kt`, `DriveCommand.kt`)

### Fixed (rc.7 — MAP tab overhaul)
- **Foreground service crash on startup** — `goForeground()` was only called inside `startConnection()`, which is gated by `isOnWifi()`. When not on WiFi, `startForeground()` never fired within the 5-second Android deadline, causing `ForegroundServiceDidNotStartInTimeException`. Fixed by calling `goForeground()` unconditionally as the first call in `CanDataService.onCreate()`. Also fixes the missing notification bar. Regression introduced in rc.5 when `ACCESS_FINE_LOCATION` was added to always-requested permissions, removing the synchronous `else startSvc()` fallback. (`CanDataService.kt`)
- **MAP tab doesn't show location without car connection** — `driveState.currentLocation` only populated by DriveRecorder during active recording. Enabled `isMyLocationEnabled` + `myLocationButtonEnabled` on GoogleMap for native blue dot. Added one-shot `FusedLocationProviderClient.lastLocation` to center camera on user's position when MAP tab opens idle. (`DriveMap.kt`, `DrivePage.kt`)

### Added (rc.7 — MAP tab overhaul)
- **4 new map color modes** — BOOST (boostPsi thresholds: vacuum/low/mid/full), THRTL (throttlePct: coasting/light/moderate/full-send), G-LAT (lateralG: straight/gentle/spirited/high-G), TEMP (oilTempC: cold/warming/operating/hot). Color mode toggle now cycles through all 6 modes (SPD → MODE → BOOST → THRTL → G-LAT → TEMP). (`DriveMap.kt`, `DrivePage.kt`)
- **Color legend strip** — floating overlay at bottom-center of map showing what colors mean for the current mode. Updates when color mode changes. (`DrivePage.kt`, `DriveMap.kt`)
- **Map type toggle** — floating button below color mode to cycle Normal → Satellite → Terrain. Dark style applied only on Normal. (`DrivePage.kt`, `DriveMap.kt`)
- **Start / Finish markers** — green pin at first drive point, red pin at last point. Shows on both live recording and historic drive playback. (`DriveMap.kt`)
- **Peak speed marker** — new `PeakType.SPEED` alongside existing RPM, BOOST, LATERAL_G. Tracks and displays peak speed with value label on map. (`TripState.kt`, `DriveRecorder.kt`, `DriveMap.kt`)
- **Pause point markers** — small yellow markers with pause bars icon at locations where recording was paused. Detects 5-second timestamp gaps between consecutive points. (`DriveMap.kt`)
- **Zoom to fit route** — when loading a historic drive, camera auto-zooms to fit the entire route using `LatLngBounds`. (`DriveMap.kt`)
- **Route stats overlay** — floating HUD at bottom-left of map showing distance, duration, and average speed. Shows for both live recording and historic drive review. (`DrivePage.kt`)
- **Drive history grouped by date** — section headers: "Today", "Yesterday", "Mar 29". Drives sorted newest first within each group. (`DrivePage.kt`)
- **Status badges on drives** — color-coded: green "COMPLETE" (finished with GPS), orange "ACTIVE" (still recording), dim "NO GPS" (legacy migrated drive). (`DrivePage.kt`)
- **Summary stats per drive** — distance, peak speed, peak RPM, peak boost, peak lateral G displayed on each drive card. (`DrivePage.kt`)
- **Drive naming** — tap drive name to rename (e.g. "Tail of the Dragon"). Stored in `DriveEntity.name` column. Room migration v2→v3 adds the column. (`DriveDatabase.kt`, `DrivePage.kt`)
- **Swipe to delete** — swipe drive history items end-to-start to delete. Red background with DELETE label. (`DrivePage.kt`)
- **Export button per drive** — SHARE button wired to `DiagnosticExporter.shareDrive()` with GPX, CSV, and drive summary in a ZIP. (`DrivePage.kt`)
- **GPS indicator on drive cards** — small green dot with "GPS" label for drives with location data. (`DrivePage.kt`)
- **Empty state improvement** — "No drives recorded yet" replaced with hint: "Connect to your car and tap START to record your first drive". (`DrivePage.kt`)

### Changed (rc.7 — MAP tab overhaul)
- **Room database v3** — migration v2→v3 adds `name TEXT DEFAULT NULL` column to drives table for user-assigned drive names. (`DriveDatabase.kt`)

### Added (rc.7 — BLE transport)
- **BLE GATT transport** — connect to MeatPi adapters over Bluetooth Low Energy instead of WiFi, freeing cellular/WiFi for internet (weather, Google Maps, in-app updates). SLCAN over GATT service `0xFFE0` with write char `FFE1` and notify char `FFE2`. Auto-reconnect on subsequent connections (`autoConnect=true`), MTU 247 with 23-byte fallback. (`BleSlcanTransport.kt`, `BleDeviceScanner.kt`)
- **BLE device picker** — scan dialog filtered to service UUID `0xFFE0` with RSSI signal strength bars. Saves device MAC + name for automatic reconnection across app restarts. (`BleDevicePickerDialog.kt`, `AppSettings.kt`)
- **BT indicator in header** — "BT" label in connection pill when connected via Bluetooth. (`MainActivity.kt`)
- **WiFi coexistence banner** — dismissible warning when using Bluetooth and phone's WiFi is active: "WiFi connected — internet may be blocked. Forget adapter WiFi for best BLE experience." (`MainActivity.kt`)
- **Transport-aware diagnostic logging** — `sessionTransport` field in DiagnosticLogger. Transport label (e.g. "Bluetooth (WiCAN_ABC / AA:BB:CC:DD:EE:FF)" or "TCP SLCAN (192.168.0.10:35000)") included in text summary, JSON detail, SLCAN log header, and share intent. (`DiagnosticLogger.kt`, `DiagnosticReportBuilder.kt`, `DiagnosticExporter.kt`)

### Changed (rc.7 — BLE transport)
- **Transport interface extraction** — connection layer refactored to transport-agnostic architecture. `SlcanTransport` interface with `TcpSlcanTransport`, `WebSocketSlcanTransport`, and `BleSlcanTransport` implementations. `SlcanConnection` shared base class handles retry, SLCAN init, OBD pollers, DTC scan/clear, and ISO-TP reassembly. Deleted `MeatPiConnection.kt` (473 lines) and `WiCanConnection.kt` (629 lines). (`can/`)
- **Adapter naming refactor** — adapters renamed from "WiCAN"/"MeatPi" to "MeatPi USB (C3)"/"MeatPi Pro (S3)". Bluetooth separated from adapter type into its own `connectionMethod` field (`"WIFI"`/`"BLUETOOTH"`). Settings UI: 2-way adapter picker + separate connection method toggle. Legacy migration handles old `"WICAN"`, `"MEATPI"`, `"BLUETOOTH"` values. (`AppSettings.kt`, `UserPrefs.kt`, `SettingsSheet.kt`)
- **FirmwareApi abstracted for transport** — `FirmwareCommandSender` interface with `WiFiFirmwareApi` (REST `/api/frs`) and `BleFirmwareApi` (`AT+FRS=` over SLCAN transport). `DriveCommand`, `DriveModeDock`, and `MorePage` use the interface for transport-agnostic firmware commands. (`FirmwareApi.kt`, `DriveCommand.kt`)

### Fixed (rc.7.1 — field-test fixes)
- **BLE permission crash on scan** — `BleDevicePickerDialog` called `scanner.startScan()` without runtime BLE permissions. `MainActivity.onCreate()` only requested permissions if `connectionMethod == "BLUETOOTH"` at startup, but users start on WiFi and switch later. Dialog now uses `rememberLauncherForActivityResult(RequestMultiplePermissions)` to check/request BLUETOOTH_SCAN + BLUETOOTH_CONNECT before scanning. Permission-denied UI state added with retry button. (`BleDevicePickerDialog.kt`)
- **BLE SecurityException safety net** — `BleDeviceScanner.startScan()` wraps `scanner.startScan()` in try/catch for SecurityException as a fallback if permissions are revoked mid-scan. (`BleDeviceScanner.kt`)
- **Foreground service start from background** — `CanDataService.startConnection()` wraps `goForeground()` in try/catch because WiFi/BT callbacks can fire when the app is backgrounded. `MainActivity.startSvc()` also catches `ForegroundServiceStartNotAllowedException`. (`CanDataService.kt`, `MainActivity.kt`)
- **AnimatedHeroNum illegible at high update rates** — the vertical slide+fade animation (150ms/100ms) introduced in rc.4 made RPM, boost, and speed hero values unreadable when data updates at ~100 Hz. Reverted all hero cards and AWD split percentages to plain `HeroNum` for instant static display. (`Components.kt`, `DashPage.kt`)
- **MAP tab location button hidden** — Google Maps native My Location button was behind the floating controls column at `Alignment.TopEnd`. Disabled native button; custom locate button added (see Added below). (`DriveMap.kt`)

### Added (rc.7.1 — field-test fixes)
- **MAP tab zoom controls** — custom `+`/`−` buttons for zoom in/out and `◎` button to recenter on current location at zoom 15. `cameraPositionState` hoisted from `DriveMap` to `DrivePage` for shared control. Pinch-to-zoom and all native gestures retained. (`DrivePage.kt`, `DriveMap.kt`)
- **Brightness/visibility system** — 7 base color tokens (Bg/Surf/Surf2/Surf3/Brd/Dim/Mid) now computed via `lerp()` between Night and Sun endpoints, backed by `mutableFloatStateOf`. Presets: Night (0.0), Day (0.5), Sun (1.0) plus continuous slider in Settings. Accents, Frost, and semantic colors (Ok/Warn/Orange) unchanged. Zero consumer changes — Compose snapshot system tracks reads automatically. (`Theme.kt`, `AppSettings.kt`, `UserPrefs.kt`, `SettingsSheet.kt`, `MainActivity.kt`)

### Added (rc.8 — new signals, glow gates, Sapphire V2)
- **Launch control engaged from CAN 0x225** — decodes byte5 bit3 to `launchControlEngaged: Boolean`. Distinct from `launchControlActive` (0x420) and `lcArmed` (RSProt extended session). Shown in header telemetry strip as "LC ENGAGED" when active. (`CanDecoder.kt`, `MainActivity.kt`)
- **RS suspension button from CAN 0x070** — decodes byte7 bit7 to `suspensionButtonPressed: Boolean`. Extends existing torque decode on the same frame. Data-only for diagnostics, no dedicated UI element. (`CanDecoder.kt`)
- **Spark advance from PCM DID 0x116B** — `sparkAdvance: Double` (B4 × 0.25°). Displayed as "SPARK" in POWER tab Engine Management row alongside timing, load, and OAR. (`ObdResponseParser.kt`, `ObdConstants.kt`, `PowerPage.kt`)
- **Battery charging voltage target from BCM DID 0x411D** — `batteryChargingVoltageDesired: Double` (B4 × 0.1V). Displayed as "CHG TGT" in DIAG tab battery section. (`ObdResponseParser.kt`, `ObdConstants.kt`, `DiagPage.kt`)
- **GFM (Generic Function Module) DTC scanning** — 0x7D2→0x7DA added to DTC scanner module list for broader diagnostic coverage. (`DtcScanner.kt`)
- **Data-gated neon glow** — all card components (HeroCard, DataCell, BarCard, WheelCell, GfCard, AfrCard) now suppress glow borders and bloom effects when displaying placeholder values ("— —") or when disconnected. HeroCard glow scales proportionally with `valueFraction` (0 = dormant, no border/glow). Cards look inert until live data arrives. (`Components.kt`, `DashPage.kt`, `ChassisPage.kt`)
- **BLE unknown adapter hint** — `BleDevicePickerDialog` shows orange "not a known adapter" warning for BLE devices whose names don't match "wican", "meatpi", or "openrs" patterns. Helps avoid pairing with random 0xFFE0 devices. (`BleDevicePickerDialog.kt`)
- **Chassis page PTU/RDU temp row** — drivetrain temperature row added to car diagram showing PTU and RDU temps with sentinel-aware placeholders. Component sizes increased for readability (canvas 130→140dp, font 8→10sp). G-Force section moved above chassis diagram. (`ChassisPage.kt`)
- **SLCAN handshake logging** — `SlcanConnection` logs firmware probe results and transport type during SLCAN handshake for diagnostic traceability. (`SlcanConnection.kt`)
- **8 new unit tests** — launch control engaged (0x225: 2 tests), suspension button (0x070: 2 tests + short-frame safety), spark advance (0x116B: 1 test), charging voltage (0x411D: 1 test). 327 total across 11 files. (`CanDecoderTest.kt`, `ObdResponseParserTest.kt`)

### Fixed (rc.8)
- **sparkAdvance sentinel value** — default was `0.0`, indistinguishable from a valid 0° advance reading. Changed to `-1.0` to match the OBD scalar sentinel convention. (`VehicleState.kt`)
- **Boost hero glow fraction used wrong unit** — `valueFraction` divided kPa by 180 instead of PSI by 30, causing incorrect glow intensity on the boost HeroCard. (`DashPage.kt`)
- **Temps tab missing POLLING placeholder** — intake air and ambient temperature showed "0.0°" before first OBD response. Now show "— —" with "POLLING" status label when sentinel value present. (`TempsPage.kt`)
- **MAP tab swipe conflict** — horizontal pager swipe was enabled on the MAP tab, conflicting with pinch-to-zoom and pan gestures on Google Maps. Pager swipe now disabled when on page 4 (MAP). (`MainActivity.kt`)

### Changed (rc.8)
- **Deprecated VehicleState fields removed** — `dataMode` and `odometerRolloverOffset` removed to free JVM bytecode slots. `dataMode` hardcoded to "CAN" in diagnostic reports. (`VehicleState.kt`, `DiagnosticReportBuilder.kt`, `CanDataService.kt`)

### Added (rc.8 — Sapphire V2)
- **Sapphire web dashboard V2** — complete overhaul of the post-session analytics dashboard with 7 new panels, 7 new chart components, and 129 vitest tests. (`web/`)
- **Compare panel** — multi-session comparison with KPI delta cards (green=improvement, orange=regression), 5 overlaid time-series charts on normalized time axis, split GPS maps. (`ComparePanel.tsx`, `ComparisonChart.tsx`, `DeltaCard.tsx`, `compare.ts`)
- **Settings panel** — unit preferences (speed, temp, boost, tire pressure, fuel economy), RS paint color theme grid, data management. Persisted to localStorage via Zustand. (`SettingsPanel.tsx`, `settings.ts`, `units.ts`)
- **GPS map with 6 color modes** — Leaflet + CartoDB Dark Matter with color-segmented polylines matching Android DriveMap color modes (SPD/MODE/BOOST/THRTL/G-LAT/TEMP). Start/finish/pause/peak markers, color legend. (`GpsMap.tsx`, `mapColors.ts`)
- **Gauge charts** — semi-circular SVG gauges for peak RPM, boost, speed, and lateral G on the dashboard panel. (`GaugeChart.tsx`)
- **G-Force scatter plot** — lat-G vs speed colored by speed band or drive mode, downsampled to 2000 points. (`GForceScatter.tsx`)
- **Temperature stack chart** — stacked area chart showing thermal soak patterns across coolant, oil, RDU, and PTU. (`TempStackChart.tsx`)
- **RPM/Boost histograms** — equal-width bin distribution charts for RPM and boost on the trip panel. (`Histogram.tsx`)
- **Mode timeline** — segmented bar chart of drive mode transitions with hover tooltips and transition count. (`ModeTimeline.tsx`)
- **Sparkline metric cards** — inline trend charts with glow line and gradient fill for speed and RPM on dashboard. (`Sparkline.tsx`)
- **Session management overhaul** — inline rename, search/filter bar, sort by date/name/distance/peak speed, bulk select + delete, tag system. (`SessionsPanel.tsx`, `SearchBar.tsx`)
- **CSV/JSON export** — per-session data export with browser download. (`export.ts`, `ExportDropdown.tsx`)
- **TPMS summary card** — 4-corner tire visualization with color-coded pressure thresholds. (`TpmsSummaryCard.tsx`)
- **Error boundary** — class component wrapping all panels with retry button, resets on panel switch. (`ErrorBoundary.tsx`)
- **129 vitest tests** — import pipeline (54), unit conversions (27), map colors (20), comparisons (16), formatters (17), store operations (15). (`web/src/lib/*.test.ts`, `web/src/store/store.test.ts`)

### Added (rc.9 — UI overhaul)
- **Bottom nav bar with frosted glass** — new `BottomNavBar` using Haze backdrop blur, 7 custom vector icons (replacing emoji tab labels), spring-animated neon indicator, stamped bevel gradient, pressClick + haptics. Height: 38dp + system gesture bar padding. Content extends behind the nav bar for edge-to-edge feel. (`BottomNavBar.kt`, `MainActivity.kt`)
- **`cardGlow()` modifier** — subtle outer shadow + top specular highlight providing ambient depth on all card components, replacing decorative neon effects. (`GlowModifiers.kt`, `Components.kt`)
- **`AggressiveNum` composable** — Orbitron Bold with 0.5sp letter-spacing for DataCell, BarCard, and WheelCell numeric values, replacing `MonoText` for a sharper aesthetic. (`Theme.kt`, `Components.kt`)
- **G-Force Plot redesign** — rectangular grid with auto-scaling axes (`ceilToQuarter`), corner brackets, 120-dot age-graded trail (up from 30), peak labels (lateral G, accel G), dense grid lines, scale numbers, and "LAT G" / "ACCEL / BRAKE G" axis titles. (`GForcePlot.kt`, `ChassisPage.kt`)
- **E-Brake warning banner** — dismissible animated banner when electronic parking brake is engaged. Auto-reappears on re-engagement. (`MainActivity.kt`)
- **MAP tab touch isolation** — `pointerInput` on map Box tracks touch state; parent `HorizontalPager` disables swipe while map is touched, preventing conflicts with Google Maps pan/pinch gestures. (`DrivePage.kt`, `MainActivity.kt`)
- **StatusPill composable** — compact MODE and ESC pills in the status bar with value-colored backgrounds and pulsing accent bar on MODE. Replaces full-width telemetry strip. (`MainActivity.kt`)
- **LC flashing pill** — animated flashing "LC" badge in the status bar when launch control is engaged. (`MainActivity.kt`)
- **7 custom vector nav icons** — purpose-drawn vector drawables for DASH/POWER/CHASSIS/TEMPS/MAP/DIAG/MORE replacing Unicode emoji tab labels. (`res/drawable/ic_nav_*.xml`)

### Changed (rc.9 — UI overhaul)
- **AppHeader compacted to single-row status bar** — two-row header (logo row + full-width telemetry strip with IGN/CONN/E-BRK cells) replaced by a single 34dp row with logo, MODE/ESC pills, and connection controls. Frees ~50dp of vertical screen space. (`MainActivity.kt`)
- **Navigation moved to bottom overlay** — tab bar relocated from below header to bottom-of-screen overlay outside Scaffold. Scaffold uses `contentWindowInsets = statusBars`; all pages add `Tokens.NavBarHeight` as bottom scroll padding. (`MainActivity.kt`, all page files)
- **Edge-to-edge with transparent system nav bar** — `enableEdgeToEdge` with `SystemBarStyle.dark(TRANSPARENT)` removes the system navigation bar scrim. (`MainActivity.kt`)
- **Card borders thinned to 0.5dp** — `Tokens.CardBorder` reduced from 1dp to 0.5dp across ~50 border declarations for lighter visual weight. (`DesignTokens.kt`, all UI files)
- **Performance timer moved to MORE tab** — `PerformanceTimerSection` relocated from DASH tab to MORE tab under its own section header, decluttering the main dashboard. (`DashPage.kt`, `MorePage.kt`)
- **Haze dependency added** — `dev.chrisbanes.haze:haze:1.5.1` for backdrop blur / glassmorphism on the bottom nav bar. (`build.gradle.kts`)
- **Drive mode hint text improved** — "Read-only mirror of CAN 0x1B0" replaced with user-friendly "Displays current Drive Mode — openRS_ firmware unlocks tap control". (`MorePage.kt`, `DriveModeDock.kt`)

### Removed (rc.9 — UI overhaul)
- **Decorative neon effects** — `neonBorder`, `neonGlow`, `neonGlowRect`, `neonPulse`, `scanLine` modifiers removed from `GlowModifiers.kt`. Replaced by `cardGlow()` for ambient depth and thin 0.5dp borders. (`GlowModifiers.kt`, `Components.kt`, `TempsPage.kt`, `DriveModeDock.kt`)
- **"Going live" connection sweep** — 800ms accent light band on adapter connect removed. (`MainActivity.kt`)
- **CRT scan line** — faint horizontal sweep effect on DASH tab removed. (`DashPage.kt`)
- **Tab crossfade** — 15% opacity dip during pager transitions removed. (`MainActivity.kt`)
- **TabBar + TeleCell composables** — inline emoji-based tab bar and full-width telemetry strip removed; replaced by `BottomNavBar` and `StatusPill`. (`MainActivity.kt`)

### Added (rc.10 — Tier 1 PIDs, button decoders, merge fix)
- **Fuel trim drift tracker (STFT/LTFT)** — short-term and long-term fuel trims decoded from PCM DIDs `0xF406` / `0xF407` (`A * 100/128 - 100`, signed %), surfaced in the POWER tab FUEL section. Implements roadmap item 2.2. (`ObdConstants.kt`, `ObdResponseParser.kt`, `VehicleState.kt`, `PowerPage.kt`, `WiCanConnection.kt`, `MeatPiConnection.kt`)
- **AWD clutch hydraulics** — left/right actuator current (DIDs `0x1E9E` / `0x1E9F`, signed Amps) and left/right hydraulic pressure (DIDs `0x1ED1` / `0x1ED2`, mBar) decoded from the AWD module under extended session. New row on the CHASSIS tab next to the existing clutch temp display. Sentinel `-999.0` for currents (so 0 A is a valid live reading) and `-1.0` for pressures. (`ObdConstants.kt`, `ObdResponseParser.kt`, `VehicleState.kt`, `ChassisPage.kt`)
- **Battery current monitoring** — live charging/discharging amps from BCM DID `0x4090` (`((A*256+B)/16) - 511.7`). Added to the DIAG tab battery row with charge/discharge color coding (green = charging, frost = discharging, dim mid = resting). (`ObdConstants.kt`, `ObdResponseParser.kt`, `VehicleState.kt`, `DiagPage.kt`, `BcmPoller`)
- **Live button input feedback** — new CAN decoders for `0x305` (drive mode toggle button, byte4 bit5) and `0x260` (Auto Start/Stop byte0 bit7, ESC defeat byte5 bit3). Surfaced as a 4-cell live HELD/idle row on the DIAG tab next to the existing suspension button indicator. Helps validate steering-wheel/console inputs without an oscilloscope. (`CanDecoder.kt`, `VehicleState.kt`, `DiagPage.kt`)
- **8 new CAN decoder unit tests** — `0x305` (pressed/released/short data) and `0x260` (ASS pressed, ESC pressed, both pressed, both released, short data). Total suite now 338 tests across 11 files. (`CanDecoderTest.kt`)

### Fixed (rc.10 — Tier 1 PIDs, button decoders, merge fix)
- **Critical: `mergeObdState` allowlist silently dropped 7 fields** — the OBD response merge in `CanDataService` enumerates fields explicitly to coalesce parser updates back into central `VehicleState`. The new Tier 1 PIDs (STFT/LTFT, AWD clutch current/pressure, battery current, charging voltage desired, spark advance) were parsed correctly but never reached the UI because they were missing from the merge. Added all 7 with sentinel-aware gating. Without this fix, none of the rc.10 PID work would have rendered. (`CanDataService.kt`)
- **`batteryCurrentA` default flipped from `0.0` to `-999.0`** — a battery at rest can legitimately read 0 A, so `0.0` could not distinguish "not yet polled" from "actually zero". Sentinel updated and DIAG tab gates display on `> -900`. (`VehicleState.kt`, `DiagPage.kt`)
- **AWD clutch sentinels** — `awdClutchCurL/R` defaulted to `0.0` (a valid value); changed to `-999.0`. `awdClutchPressureL/R` defaulted to `0.0`; changed to `-1.0`. CHASSIS tab cells now show `—` until first data arrives instead of misleading zeros. (`VehicleState.kt`, `ChassisPage.kt`)
- **POWER tab FUEL trims gated on wrong field** — STFT/LTFT cells were hidden until `calcLoad > 0`, but `calcLoad` is a separate Mode 22 PID that may arrive after STFT/LTFT. Replaced with `vs.isConnected && vs.rpm > 0` so trims display as soon as the engine is running. (`PowerPage.kt`)
- **MainActivity recomposition churn on pager swipe** — `userScrollEnabled` was a fresh expression on every recompose; wrapped in `derivedStateOf`. `BottomNavBar onSelect` lambda was also re-allocated each frame; hoisted to a `remember`-cached closure. (`MainActivity.kt`)

### Changed (rc.10 — Tier 1 PIDs, button decoders, merge fix)
- **DIAG battery row reorganized** — from 4 cells in one row to 2 rows of 3 cells (BATT V / BATT A / BATT SoC, then BATT TEMP / CHG TGT / spacer) to fit the new BATT A reading while matching the rest of the DIAG tab's 3-per-row convention. (`DiagPage.kt`)
- **`versionCode` 32 → 33** — required for OTA update detection. `versionName` stays `2.2.6`. (`build.gradle.kts`)
- **`firmware/build.sh --rc rc.N` flag** — output filename now accepts an RC suffix (`openrs-fw-usb_v1.61-rc.N.bin`, `openrs-fw-pro_v1.2-rc.N.bin`) so RC firmware builds can be cleanly versioned alongside stable. No firmware source changes for rc.10 — existing v1.61 / v1.2 binaries remain current. (`firmware/build.sh`)
- **BottomNavBar height tokenized** — hardcoded `38.dp` replaced with `Tokens.NavBarHeight` (now 45.dp) and a 6dp top padding added so icons sit further from the top edge of the frosted glass strip. (`BottomNavBar.kt`, `DesignTokens.kt`)
- **CI build provenance attestation** — release workflow now generates a SLSA build provenance attestation via `actions/attest-build-provenance@v2` for every signed release APK. Verify-firmware job restricted to tag pushes only. (`.github/workflows/ci.yml`)
- **Removed deleted firmware RC binaries** — `openrs-fw-pro_v1.2-rc.1.bin` and `openrs-fw-usb_v1.61-rc.1.bin` removed from `firmware/release/`; the stable `v1.2.bin` / `v1.61.bin` files in the same directory remain the current firmware.

### Fixed (stable — in-app updater rolling-RC bug)
- **UpdateChecker now reads version from APK filename, not git tag** — the rolling-RC release pattern reuses the same git tag (e.g. `android-v2.2.6-rc.7`) across multiple RCs while the APK filename advances (`openRS_v2.2.6-rc.10.apk`). `UpdateChecker.parseReleases` was reading the version from `tag_name`, so RCs newer than the tag's named RC were silently filtered out as "older than installed" for users on the beta channel. Added `AppVersion.fromApkFilename()` and switched the parser to prefer the APK filename, with tag parsing as a fallback. 6 new unit tests guard the rolling-RC scenario. Total suite now 344 tests across 11 files. (`AppVersion.kt`, `UpdateChecker.kt`, `AppVersionTest.kt`)

### Changed (stable)
- **`versionCode` 33 → 34** — required for OTA update detection. `versionName` stays `2.2.6`. (`build.gradle.kts`)

---

## [v2.2.5] — 2026-03-27

### Added (rc.1 — FORScan PID catalog, data-driven decode, DID prober)
- **FORScan PID catalog integrated as JSON asset** — 1,149 PIDs across 8 ECU modules (PCM, OBDII, BCM, ABS, AWD, HVAC, IPC, PSCM) loaded from `forscan_modules.json` at runtime via `ForscanCatalog.kt`. Generator script at `android/scripts/gen_forscan_catalog.py`.
- **PID Browser on DIAG tab** — expandable section listing all 1,149 FORScan PIDs grouped by ECU module with coverage mini-bars, CAN ID badges, and aggregate stats.
- **DID Prober on DIAG tab** — interactive Mode 22 scanner that probes candidate DID ranges on any ECU. Classifies each DID as FOUND / NRC / TIMEOUT with progress bar and hex response data. Requires active adapter connection.
- **Data-driven PID decode system (PidRegistry)** — `PidRegistry.kt` loads the FORScan catalog at startup, decodes responses via formula evaluation, and stores results in `VehicleState.genericValues`. New PIDs can be added via JSON without modifying Kotlin code.
- **Per-cylinder knock correction (cylinders 2–4)** — PCM now polls DIDs 0x03ED–0x03EF alongside existing cylinder 1 (0x03EC). Displayed on POWER tab in the knock correction section.
- **AWD module expansion — 7 new PIDs** — clutch temp L/R (0x1E8B/0x1E8C), requested torque L/R (0x1E90/0x1E91), demanded pressure (0x1E92), pump motor current (0x1E93), transmission oil temp (0x1E80). Shown on CHASSIS and TEMPS tabs.
- **HVAC module scaffolding** — response handler for ECU 0x733→0x73B with 6 new `VehicleState` fields. ECU address is a candidate — use the DID prober to confirm.
- **IPC warning lamps scaffolding** — response handler for ECU 0x720→0x728 with 6 warning lamp fields. Summary banner on DASH tab shows active warnings when populated. ECU address is a candidate — use the DID prober to confirm.
- **`buildSlcanQuery()` helper in ObdConstants** — generates Mode 22 SLCAN query frames for any ECU request ID + DID, replacing hardcoded frame strings.

### Fixed (rc.1)
- **PidRegistry never initialized** — `PidRegistry.ensureLoaded()` was never called, so all data-driven PID decoding was silently non-functional. Added call in `CanDataService.onCreate()`.
- **`mergeObdState` dropped `genericValues`** — PidRegistry-decoded values were computed but silently lost during state merge. Now merges correctly.
- **`mergeObdState` missing HVAC and IPC fields** — all 12 new fields were parsed but dropped during state merge.
- **Dead code removed** — `DidProber.kt` was never referenced; deleted. `DidProberSection.kt` inlines all probing logic.
- **`MeatPiConnection.readSlcanLine` could hang indefinitely** — continuous bytes without a `\r` terminator caused an infinite loop. Added a 32-byte max-line guard. ([#127](https://github.com/klexical/openRS_/issues/127))
- **`IsoTpBuffer` CF sequence numbers not validated** — duplicate or out-of-order Consecutive Frames silently corrupted TPMS readings. Sequence tracking added. ([#128](https://github.com/klexical/openRS_/issues/128))
- **ISO-TP First Frame `firstBytes` not capped by `totalLen`** — bytes to copy from FF ignored `totalLen`. Changed to `minOf(6, totalLen, data.size - 2)`. ([#129](https://github.com/klexical/openRS_/issues/129))

### Changed (rc.1)
- **Release checklist and emulator workflow rules updated** — browser emulator updates deferred to stable releases only, skipped for staging/RC builds.

### Fixed (rc.2)
- **DtcScanner exceptions now caught at service layer** — mid-scan adapter disconnect propagated uncaught to callers. `scanDtcs()` now logs and rethrows; `clearDtcs()` returns `emptyMap()`. (addresses [#83](https://github.com/klexical/openRS_/issues/83))
- **DTC results blank during dismiss animation** — `AnimatedVisibility` exit rendered blank content because `results` was already null. Fixed with a `remember`-backed `lastResults` variable. (addresses [#81](https://github.com/klexical/openRS_/issues/81))
- **Race-ready RTR check inconsistent between TEMPS banner and TripRecorder** — `UserPrefs.isRaceReady()` used different thresholds than `VehicleState.rtrStatus`. Removed `isRaceReady()`; `TripRecorder` now uses `vs.isReadyToRace` — one truth source. (addresses [#81](https://github.com/klexical/openRS_/issues/81))
- **`AppSettings.save()` vs `saveAll()` undocumented field split** — `save()` persists host/port, `saveAll()` persists everything else. The intentional split was undocumented. Both methods now have KDoc explaining the separation. (addresses [#83](https://github.com/klexical/openRS_/issues/83))

### Fixed (rc.3 — code review umbrella cleanup)
- **Misleading `Red` color alias removed** — `val Red get() = Orange` in `Theme.kt` returned orange, not red. Alias removed; 35 call sites now reference `Orange` directly. (addresses [#82](https://github.com/klexical/openRS_/issues/82))
- **TempsPage RTR dot animation ticked as no-op when race-ready** — animation oscillated between `1f` and `1f` when ready, running every frame with no visual effect. Now gated on warmup state. (addresses [#82](https://github.com/klexical/openRS_/issues/82))
- **MainActivity connection dot animation ran when disconnected** — `rememberInfiniteTransition` ticked continuously regardless of connection state. Now only runs when connected. (addresses [#82](https://github.com/klexical/openRS_/issues/82))
- **Odometer unit toggle reset on tab switch** — `remember { mutableStateOf(false) }` hardcoded km default. Initial value now derives from `prefs.speedUnit == "MPH"`. (addresses [#82](https://github.com/klexical/openRS_/issues/82))
- **UserPrefs.update race with SharedPreferences** — `saveAll()` ran inside the `StateFlow.update` CAS lambda, persisting stale data on retry. Now runs after CAS succeeds. (addresses [#83](https://github.com/klexical/openRS_/issues/83))
- **Redundant `themeAccentColor()` wrapper removed** — `MorePage.kt` one-liner delegating to `rsPaintAccent()`. Call sites now invoke `rsPaintAccent()` directly. (addresses [#81](https://github.com/klexical/openRS_/issues/81))
- **Conversion magic numbers extracted to named constants** — `KM_TO_MI`, `STD_ATM_KPA`, `KPA_TO_PSI`, `PSI_TO_BAR` consolidated into `UnitConversions` object in `UserPrefs.kt`. 10 occurrences replaced across 4 files. (addresses [#82](https://github.com/klexical/openRS_/issues/82))

### Added (rc.4 — visual polish)
- **Neon glow effects on hero values** — soft radial gradient bloom behind Orbitron values in each hero card's accent color. Same treatment on `GfCard` values. Works on all API levels ≥ 28 (no `RenderEffect`).
- **Neon glow on progress bars** — `BarCard` gains optional `barGlowColor` for a soft light bleed above and below the bar. Applied to THROTTLE, BRAKE, FUEL, and BATTERY bars.
- **Connection dot glow ring** — semi-transparent backing circle on the connection status dot, synced to pulse animation. Same treatment on the RTR banner dot.
- **Active tab indicator glow** — vertical gradient glow beneath the accent underline on the selected tab.
- **Animated value transitions** — all DASH tab numeric displays use `animateFloatAsState` with spring physics. Fast values (RPM, speed) use stiff springs; slow values (fuel, battery) use tween. Sentinel values bypass animation.
- **Sparkline mini-charts in HeroCards** — 22dp Canvas sparklines showing ~15s of value history via a 60-sample ring buffer at ~4 Hz. Applied to BOOST, RPM, and SPEED hero cards.
- **Sparkline mini-charts in BarCards** — 28dp sparklines below progress bars. Applied to THROTTLE and BRAKE bars.
- **G-Force dot plot on CHASSIS tab** — 2D visualization (`GForcePlot.kt`) with concentric G rings, live dot with glow halo, and a 30-sample comet trail. Replaces the six `GfCard` text boxes while preserving all numeric readouts.
- **RPM shift light bar on DASH tab** — 18 LED-style segments (`ShiftLightBar.kt`) filling left-to-right by RPM: green 0–4000, yellow 4000–5500, red 5500–6800. All segments flash at redline (≥6500 RPM).
- **Shared animation utilities** (`ui/anim/` subpackage) — `RingBuffer.kt` (generic ring buffer), `GlowModifiers.kt` (radial and rectangular glow modifiers), `Sparkline.kt` (sparkline data + Canvas composable).

### Fixed (rc.4 — visual polish)
- **GForcePlot Paint objects allocated every frame** — `textPaint` and `axisPaint` were created inside the Canvas lambda, producing ~120 allocations/sec at 60 Hz. Extracted to `remember(density)` outside the draw block.
- **Odometer mi/km toggle lost on tab switch** — the rc.3 fix improved the initial value but the toggle still used ephemeral `remember` state destroyed on recomposition. Now persisted to SharedPreferences via a new `odomInMiles` field in `UserPrefs`/`AppSettings`. Survives tab switches and app restarts. (addresses [#82](https://github.com/klexical/openRS_/issues/82))
- **Drive mode commands silently dropped with HTTP 200** — firmware returned `{"ok":true}` even when `s_pending_mode` rejected a command, so the app showed success when nothing happened. `FirmwareApi.kt` now parses the response body for `"busy":true` and shows "Mode change in progress — please wait". After a successful command, the app watches `VehicleState.driveMode` for up to 5 seconds and warns if the mode didn't take effect.

### Added (rc.4 — visual polish)
- **RingBuffer unit tests** — 9 test cases covering empty state, insertion order, capacity wraparound, clear/re-push, and generic type support.

### Added (rc.5 — Mission Control dashboard)
- **Mission Control HTML dashboard in exports** — trip and diagnostic ZIP exports now include a self-contained `mission_control_<ts>.html` file. Open in any browser for an interactive post-session analysis dashboard with no network or dependencies required. `MissionControlHtmlBuilder.kt` reads the HTML template from `res/raw`, inlines uPlot CSS/JS from assets, and serializes trip + diagnostic data into embedded JSON.
- **Trip dashboard tab** — 8 summary cards (distance, duration, fuel, avg speed, peak RPM/boost/lat G, efficiency), 7 cursor-synced uPlot time-series charts (RPM, boost, speed, temperatures, lateral G, fuel, wheel speeds), SVG Mercator GPS map with speed/drive-mode coloring and peak event markers, stats panel with drive mode breakdown bar.
- **Diagnostics dashboard tab** — sortable CAN frame inventory table with expandable detail rows (first/last hex, periodic samples, validation issues), FPS timeline chart, session events table with type badges, filterable decode trace table (last 500 of up to 10,000 entries).
- **File loader tab** — drag-and-drop or browse to load additional `trip_*.csv` or `diagnostic_detail_*.json` files from other sessions into the same viewer. CSV parser computes summary stats (distance, fuel economy, peaks, mode breakdown) on the fly.

### Fixed (rc.5)
- **Sport/Track 0x420 bit0 polarity inverted** — `CanDecoder` had bit0=1→Track and bit0=0→Sport, but the actual car button cycle (Normal→Sport→Track→Drift) shows bit0=1 is Sport (0x11CD) and bit0=0 is Track (0x11CC). This caused the app to display the wrong mode and the firmware's closed-loop controller to overshoot by one step (tap Sport → car goes to Track). Swapped polarity in both 0x1B0 and 0x420 handlers. Default `modeDetail420` changed from `0x10C4` to `0x10CD`.
- **Every drive mode command timed out with "Read timed out"** — `FirmwareApi.post()` read the HTTP response body in a `readLine()` loop until EOF, but the ESP-IDF HTTP server doesn't close the connection within the 5s `soTimeout`. Now parses `Content-Length` from headers and reads exactly that many bytes.
- **TPMS displayed −40°C during sensor initialisation** — `ObdResponseParser.parseBcmReassembled()` accepted raw temp byte `0x00` as valid, which decoded to −40°C via the standard offset. Now discards `0x00` as an uninitialised sensor reading. Also removed the `< -40` floor from the range check since the offset formula cannot produce values below −40°C. ([#130](https://github.com/klexical/openRS_/issues/130))
- **BCM 0x280B tire temperature unit tests** — 5 new tests covering valid temp decode, uninitialised `0x00` discard, stale status discard, unknown sensor ID rejection, and short payload handling.

### Fixed (rc.6 — polarity re-correction + probe export)
- **Sport/Track 0x420 polarity re-corrected** — rc.5's polarity fix was still backwards. SLCAN log analysis (2026-03-25 drive session) definitively proves `0x11CC` (bit0=0) = Sport and `0x11CD` (bit0=1) = Track. The app now displays the correct mode. Default `modeDetail420` changed from `0x10CD` to `0x10CC`. Five drive mode unit tests updated to match.
- **Drive mode CAN confirmation timeout bumped to 8s** — real-world SLCAN logs show the mode change CAN frame (`0x420`) arrives ~5.3s after the HTTP command, just exceeding the previous 5s window. Increased to 80 × 100ms = 8s in `MorePage.kt`.

### Added (rc.6)
- **DID probe results included in diagnostic ZIP export** — `DiagnosticLogger` now stores probe sessions; `DidProberSection` logs results after each scan completes. `DiagnosticExporter` writes a "DID PROBE RESULTS" section in the summary text and a `probeResults` array in the JSON detail file. Previously, probe results were ephemeral Compose state lost on navigation.

### Fixed (rc.7 — full release audit)
- **Drive mode confirmation read stale state** — `MorePage` confirmation loop captured `vs` at composition time; inside the coroutine it never saw CAN updates. Now reads `OpenRSDashApp.instance.vehicleState.value` each poll iteration. ([#82](https://github.com/klexical/openRS_/issues/82))
- **DID prober mutated Compose state from IO thread** — `DidProberSection` launched the probe loop on `Dispatchers.IO`, then called `results.add()` on `mutableStateListOf` off the Main thread. Restructured to run the coroutine on Main, wrapping only the network call in `withContext(IO)`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **DiagnosticExporter JSON escaped quotes incorrectly** — five `.replace("\"", "'")` calls in JSON serialization converted double-quotes to single-quotes instead of properly escaping them. Replaced with a `.jsonEscape()` extension that handles `\`, `"`, `\n`, `\r`, `\t`, and `</` (script breakout prevention). ([#82](https://github.com/klexical/openRS_/issues/82))
- **WebSocket 64-bit length path ignored mask bytes** — `WiCanConnection` large-frame path read the 8-byte extended length but skipped the 4-byte mask, causing the payload to be read from the wrong offset and unmasked incorrectly. Now reads and applies the mask per RFC 6455. ([#82](https://github.com/klexical/openRS_/issues/82))
- **DashPage sparkline push during composition** — `boostHistory.push()` was called inside the composable body, a side effect during composition. Moved into a `SideEffect {}` block. ([#82](https://github.com/klexical/openRS_/issues/82))
- **ShiftLightBar infinite animation ran when not at redline** — `rememberInfiniteTransition` ticked continuously regardless of RPM. Now only instantiated when `isRedline` is true. ([#82](https://github.com/klexical/openRS_/issues/82))
- **`intakeTempC` and `ambientTempC` defaulted to 0.0 instead of sentinel** — zero is a valid temperature reading, masking "not yet received" as 0°C/32°F. Changed defaults to `-99.0` matching all other temperature fields. ([#82](https://github.com/klexical/openRS_/issues/82))
- **PidRegistry formula evaluator returned Infinity/NaN** — division by zero in catalog formulas produced `Infinity` which propagated to `genericValues`. Now returns `null` for infinite or NaN results. ([#82](https://github.com/klexical/openRS_/issues/82))
- **RingBuffer accepted capacity=0** — would cause division-by-zero in modular index arithmetic. Added `require(capacity > 0)` in `init`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **Sparkline Path objects allocated every frame** — `Path()` was created inside the `Canvas` lambda (~60 alloc/sec). Hoisted to `remember` with `.reset()` at draw time. ([#82](https://github.com/klexical/openRS_/issues/82))
- **WiCanConnection socket not closed on cancellation** — coroutine cancellation didn't close the TCP socket, leaving it open until GC. Added a `cancelWatcher` coroutine that closes the socket in its `finally` block. ([#82](https://github.com/klexical/openRS_/issues/82))
- **MorePage host didn't update when settings changed** — `AppSettings.getHost()` was called once and cached. Now keyed on `UserPrefs` via `remember(prefs)`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **SLCAN write failures silently swallowed** — `DiagnosticLogger.writeSlcanLine()` caught all exceptions with no logging. Now logs the first write failure as a session event. ([#82](https://github.com/klexical/openRS_/issues/82))
- **Probe sessions had no cap** — `DiagnosticLogger.probeSessionsList` could grow unbounded. Capped at 50 sessions with LRU eviction. ([#82](https://github.com/klexical/openRS_/issues/82))
- **TPMS 0x280B missing pressure alongside temperature** — `ObdResponseParser.parseBcmReassembled()` extracted temperature from the multi-frame payload but ignored the pressure bytes. Now reads pressure from `payload[7..8]` and updates the corresponding tire pressure field. ([#82](https://github.com/klexical/openRS_/issues/82))
- **Flat tire (0.0 PSI) not flagged as low** — `anyTireLow()` range started at `0.01`, excluding exactly 0.0 PSI. Changed to `0.0..<threshold`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **FirmwareApi Content-Length byte count incorrect** — `json.length` (char count) was used instead of `json.toByteArray().size` (byte count), which diverges for non-ASCII characters. ([#82](https://github.com/klexical/openRS_/issues/82))
- **ForscanCatalog load failure silently swallowed** — exception was caught with no logging. Now logs with `Log.e`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **`SlcanParser.parseDataBytes` returned empty array on hex error** — callers couldn't distinguish "no data" from "parse failure". Now returns `null` on error; both callers updated to skip with `?.let`. ([#82](https://github.com/klexical/openRS_/issues/82))
- **Mission Control JSON missing probe results** — `MissionControlHtmlBuilder.buildDiagJson()` didn't include DID probe sessions. Added `probeResults` array. ([#82](https://github.com/klexical/openRS_/issues/82))
- **Odometer CAN ID missing from diagnostic known-IDs set** — `DiagnosticExporter.knownIds` didn't include `ID_ODOMETER`, so odometer frames appeared as "unknown" in the frame inventory. ([#82](https://github.com/klexical/openRS_/issues/82))
- **CanDecoder stale header comment** — top-of-file comments still referenced old pre-rc.6 polarity. Updated to match corrected `bit0=0→Sport (0xCC)`.
- **Deprecated `CarOutlinePlaceholder` alias removed** — dead code in `Components.kt`, replaced by `FocusRsOutline` in rc.4.

### Added (rc.7)
- **IsoTpBuffer unit tests** — 12 tests covering single-frame, multi-frame reassembly, out-of-order CF rejection, sequence wrap, FF reset, and edge cases (`IsoTpBufferTest.kt`).
- **PidRegistry formula evaluator tests** — 16 tests covering arithmetic, `signed()` two's-complement, unary minus, operator precedence, parentheses, and edge cases (`PidRegistryTest.kt`).
- **RingBuffer capacity=0 test** — verifies `IllegalArgumentException` on zero capacity.

### Added (rc.8 — UI/UX overhaul)
- **Swipe tab navigation via HorizontalPager** — replaced raw `when(tab)` with `HorizontalPager` in `MainActivity.kt`. Users can swipe left/right between all 6 tabs. Tab bar syncs bidirectionally with pager state via `animateScrollToPage`. ([#131](https://github.com/klexical/openRS_/issues/131), [#138](https://github.com/klexical/openRS_/issues/138))
- **Animated gear transition** — gear display on DASH tab now uses `AnimatedContent` with `slideInVertically + fadeIn` / `slideOutVertically + fadeOut` for smooth gear change animations at 72sp. ([#132](https://github.com/klexical/openRS_/issues/132))
- **Haptic feedback on interactive controls** — `HapticFeedbackType.Confirm` added to tab selection (`MainActivity.kt`), drive mode and ESC buttons (`MorePage.kt`), temperature preset pills (`TempsPage.kt`), and settings segmented pickers (`SettingsSheet.kt`). ([#133](https://github.com/klexical/openRS_/issues/133))
- **Placeholder pulse animation** — `DataCell` and `AfrCard` in `Components.kt` auto-detect `"— —"` placeholder values and animate alpha 0.3↔0.7 via `rememberInfiniteTransition`. Distinguishes "waiting for data" from "value is zero." ([#135](https://github.com/klexical/openRS_/issues/135))
- **NRC code decoding in DID Prober** — `nrcLabel()` function in `DidProberSection.kt` maps 11 common UDS NRC codes (0x10–0x78) to human-readable descriptions. "NRC 0x31" now shows "request out of range." ([#136](https://github.com/klexical/openRS_/issues/136))
- **Long-press copy DID code in PID Browser** — `combinedClickable` with `onLongClick` in `PidBrowserSection.kt` copies DID hex codes to clipboard with Toast confirmation. ([#137](https://github.com/klexical/openRS_/issues/137))
- **Knock retard flash animation** — `PowerPage.kt` KR C1-C4 cells animate background color via `animateColorAsState` to a warm `Warn` glow (0.08 alpha → transparent over 400ms) when knock correction crosses -1.0°. ([#139](https://github.com/klexical/openRS_/issues/139))
- **Connection status banner** — contextual banner below tab bar shows adapter type, IP:port, and "DISCONNECTED" status with dismiss button. Auto-resets on successful connection. ([#140](https://github.com/klexical/openRS_/issues/140))
- **Temperature session peaks** — 5 new peak fields in `VehicleState` (oil, coolant, RDU, PTU, charge air) tracked via `withPeaksUpdated()`. Displayed as "▲" annotations in `TempsPage.kt`. ([#141](https://github.com/klexical/openRS_/issues/141))
- **DID Prober ETA and export** — ETA display during probe ("~4 min remaining") based on average probe time. EXPORT button copies results as TSV to clipboard. ([#142](https://github.com/klexical/openRS_/issues/142))
- **Settings reset-to-defaults** — RESET button in `SettingsSheet.kt` restores all fields to `AppSettings.DEFAULT_*` values with inline "Defaults restored" confirmation. ([#143](https://github.com/klexical/openRS_/issues/143))
- **Collapsible sections on Power page** — `SectionLabel` extended with `collapsible`/`expanded`/`onToggle` params. Three Power page sections wrapped in `AnimatedVisibility` with expand/collapse chevrons. ([#144](https://github.com/klexical/openRS_/issues/144))
- **Pinned dash metrics strip** — `MiniMetricStrip` composable in `DashPage.kt` appears when scroll position exceeds 220px, showing compact BOOST/RPM/SPEED values for persistent context. ([#145](https://github.com/klexical/openRS_/issues/145))
- **TPMS delta arrows** — `tpmsDeltaText()` helper in `ChassisPage.kt` computes pressure trend vs session start. `TireCard` extended with `deltaText` param showing ▲/▼ arrows color-coded green (rising) or orange (falling). ([#146](https://github.com/klexical/openRS_/issues/146))
- **Landscape and split-screen layout support** — `isWideLayout()` composable helper in `Theme.kt` detects landscape/wide screens. Responsive grids across `DashPage`, `TempsPage`, `ChassisPage`, `TripPage`, and `CustomDashPage`. ([#147](https://github.com/klexical/openRS_/issues/147))
- **Floating HUD overlay** — `HudOverlayService.kt` using `TYPE_APPLICATION_OVERLAY` displays 2-3 key metrics on top of any app. Draggable, close button, collects `vehicleState` flow at 4 Hz. Toggle in `MorePage`. `SYSTEM_ALERT_WINDOW` permission added. ([#148](https://github.com/klexical/openRS_/issues/148))
- **Animated car diagram** — `CarDiagram.kt` Canvas-based top-down car visualization with live tire pressure, wheel speed, and AWD torque distribution overlays. Replaces `FocusRsOutline` in TPMS section of `ChassisPage`. ([#149](https://github.com/klexical/openRS_/issues/149))
- **Session history browser** — Room database (`SessionDatabase.kt`) with `SessionEntity` and `SnapshotEntity`. `CanDataService` records sessions automatically. `SessionHistorySection` in `MorePage` shows expandable session cards with 30-day auto-prune. ([#150](https://github.com/klexical/openRS_/issues/150))
- **Custom dashboard builder** — `DashLayout.kt` data model with 31 available metrics. `CustomDashPage.kt` full-screen overlay with responsive gauge grid and `DashBuilderSheet` editor. Layouts persisted via `AppSettings.saveCustomDash()`/`loadCustomDash()`. ([#151](https://github.com/klexical/openRS_/issues/151))

### Changed (rc.8)
- **Dim label contrast improved** — `Dim` color bumped from `#3D5A72` to `#547A96` in `Theme.kt` for WCAG AA compliance (≥4.5:1 contrast ratio on Surf2 background). ([#134](https://github.com/klexical/openRS_/issues/134))
- **Compose BOM upgraded** — `2024.11.00` → `2025.11.00` (Compose UI 1.7.5 → 1.9.4). Enables `HapticFeedbackType.Confirm` and `HorizontalPager` from stable Foundation APIs.
- **KSP annotation processing added** — Room compiler uses KSP (`com.google.devtools.ksp` v2.0.21-1.0.27) instead of KAPT for faster builds.
- **Unified Chassis section ("Neon Connect" layout)** — merged the separate TPMS and AWD sections into a single `UnifiedChassisSection` in `ChassisPage.kt`. Tire cards (FL/RL left, FR/RR right) flank the RS wireframe with colored accent edge bars matching tire status. Cards show PSI, delta arrows, wheel speed, temp, and a temperature fill bar. Diamond-shaped wheel markers replace dots on the wireframe. AWD metrics (torque bar, bias, deltas, temps, clutch data) sit below in the same card, eliminating duplicate wheel speed displays. ([#149](https://github.com/klexical/openRS_/issues/149))

### Added (rc.9 — chassis redesign, Sapphire dashboard)
- **Chassis TPMS redesign** — `NeonTireCard` rebuilt with full position names ("Front Left"), hero PSI + unit side-by-side (22sp), prominent temperature (14sp, color-coded via `tireTempColor()`), temperature progress bar, and session delta arrows. Warning banner identifies specific low tire(s) by name. Always shows full layout (no static wireframe placeholder for waiting state). TPMS footer row with recommended threshold + "Updated Xs ago" timestamp. ([#153](https://github.com/klexical/openRS_/issues/153))
- **AWD rear axle diagram** — Canvas-drawn drivetrain in `ChassisPage.kt`: wheels (accent L / green R), clutch packs with temps, RDU box with temp, PTU box with F/R split label, propshaft line, torque flow arrows scaled to L/R distribution. Replaces old plain torque bar. ([#154](https://github.com/klexical/openRS_/issues/154))
- **Chassis lead lines** — Canvas overlay in `UnifiedChassisSection` draws connector lines from tire card edges to wheel positions on car wireframe using `onGloballyPositioned` + `positionInRoot()`. ([#155](https://github.com/klexical/openRS_/issues/155))
- **Crash history UI** — `CrashHistorySection` in `DiagPage.kt` shows crash file count, timestamps, and exception types. Crash files retained after export (not deleted). ([#161](https://github.com/klexical/openRS_/issues/161))
- **DID prober export** — probe sessions included as CSV files in diagnostic ZIP exports via `DiagnosticExporter`. ([#160](https://github.com/klexical/openRS_/issues/160))
- **What's New dialog** — `WhatsNewDialog.kt` with version-keyed highlights. Shown on first launch after update, version tracking via `AppSettings.lastSeenVersion`. ([#159](https://github.com/klexical/openRS_/issues/159))
- **Sapphire web dashboard integration** — `MorePage` "WEB DASHBOARD" section with Sapphire button (opens URL in browser). Sapphire link added to trip export share text in `DiagnosticExporter`. Sapphire highlight in What's New v2.2.5 list. ([#162](https://github.com/klexical/openRS_/issues/162))

### Fixed (rc.9)
- **Drive mode cold-start race condition** — added `has420Arrived` gate in `CanDecoder.kt` that prevents Sport/Track resolution until the first `0x420` frame is received. Confirmation loop in `MorePage` now waits 2s settling delay after HTTP 200 and uses 15s timeout (was 8s). Diagnostic logging of `modeDetail420` on failure. Root cause: firmware closed-loop controller overshoots on cold ECU (SLCAN-proven). ([#153](https://github.com/klexical/openRS_/issues/153))

### Changed (rc.9)
- **More tab cleanup** — moved theme picker and HUD toggle to `SettingsSheet`. Removed duplicate snapshot button, settings button, and entire CONNECTION & DIAGNOSTICS section from `MorePage`. ([#156](https://github.com/klexical/openRS_/issues/156), [#157](https://github.com/klexical/openRS_/issues/157), [#158](https://github.com/klexical/openRS_/issues/158))
- **Settings visual overhaul** — replaced `SurfUp` (neutral grey `#141414`) with blue-tinted palette (`Bg`, `Surf2`, `Surf3`) matching the cockpit aesthetic throughout `SettingsSheet.kt`. ([#158](https://github.com/klexical/openRS_/issues/158))
- **Tire font sizes** — temperature increased to 14sp with color coding, hero PSI to 22sp. ([#153](https://github.com/klexical/openRS_/issues/153))

---

## [v2.2.4] — 2026-03-19

### Added (rc.10.1 -- TPMS tire temperature polling + UI, Focus RS wireframe)
- **TPMS tire temperature polling via PID 0x280B** -- implements the "last received sensor" approach discovered by @adamsouthern on issue #119. Per-tire temperature PIDs 0x2823-0x2826 were confirmed unsupported (BCM returns `7F 22 31`). Instead, the app now: (1) polls sensor IDs from PIDs `0x280F` (LF), `0x2810` (RF), `0x2811` (RR), `0x2812` (LR) once on connect to build an ID-to-position map; (2) polls PID `0x280B` ("last received TPMS sensor") every 30s BCM cycle. The 0x280B response contains the 4-byte sensor ID, pressure (`(A*256+B)/20` PSI), temperature (`raw - 40` C), status byte, and checksum. The sensor ID is matched against the stored map to assign temperature to the correct tire position. New constants added to `ObdConstants.kt`: `BCM_QUERY_TPMS_LAST`, `BCM_QUERY_TPMS_ID_LF/RF/RR/LR`, `BCM_TPMS_ID_QUERIES`, `BCM_FLOW_CONTROL`.
- **ISO-TP multi-frame reassembly for BCM responses** -- new `IsoTpBuffer` class (`IsoTpBuffer.kt`) handles the 12-byte 0x280B response which spans two CAN frames (First Frame + Consecutive Frame). When a First Frame (PCI high nibble `0x1`) is detected on CAN ID 0x72E, the buffer stores the partial payload and triggers a Flow Control frame (`30 00 00...` on 0x726). The subsequent Consecutive Frame (PCI `0x2x`) is appended and the complete payload is passed to `ObdResponseParser.parseBcmReassembled()`. Single-frame responses continue through the existing `parseBcmResponse()` path unchanged. Both `WiCanConnection.kt` and `MeatPiConnection.kt` updated with this handling.
- **`ObdResponseParser.parseBcmReassembled()`** -- new method for multi-frame BCM payloads. Decodes DID 0x280B: extracts the 4-byte sensor ID, 2-byte pressure, 1-byte temperature (raw - 40 C), validates range (-40 to 120 C), matches against `VehicleState.tpmsSensorId{LF,RF,RR,LR}`, and updates the corresponding `tireTemp{LF,RF,LR,RR}` field. Unknown sensor IDs are logged at debug level.
- **TPMS sensor ID parsing (0x280F-0x2812)** -- added to `parseBcmResponse()` alongside existing pressure DIDs. Each returns a 4-byte sensor ID in a single-frame response. Stored in new `VehicleState` fields: `tpmsSensorIdLF`, `tpmsSensorIdRF`, `tpmsSensorIdRR`, `tpmsSensorIdLR` (Long, default -1L). Merged in `CanDataService.kt` with sentinel-based guards matching the existing tire pressure pattern.
- **Tire temperature display in TPMS section** -- each `TireCard` composable in `Components.kt` now accepts an optional `tempC` parameter (default -99.0 = no data). When temp data is available (> -90C sentinel), it renders below the pressure value separated by a thin `Brd`-colored divider: `displayTemp(tempC) + tempLabel` in `MonoText` at 10sp, color-coded by `tireTempColor()`. Color ranges: `Mid` blue-grey (<15C cold), `Ok` green (15-27C good), `Warn` gold (28-40C warm), `Orange` (>40C hot). Respects the global C/F temperature unit setting via `UserPrefs.displayTemp()`.
- **Focus RS MK3 wireframe replaces car outline placeholder** -- the old rectangle-and-dot `CarOutlinePlaceholder` composable is replaced with `FocusRsOutline` in `Components.kt`. Renders a detailed top-down wireframe of the Focus RS from a transparent PNG asset (`res/drawable-nodpi/focus_rs_wireframe.png`, 276x570px, pre-rotated to portrait). Uses `ColorFilter.tint(accent, BlendMode.SrcIn)` with `LocalThemeAccent.current` so the wireframe automatically recolors to match whichever RS paint theme the user selects (Nitrous Blue, Race Red, Deep Orange, Stealth Grey, Shadow Black, Frozen White). `CarOutlinePlaceholder` retained as a `@Deprecated` alias for backward compatibility.
- **Temperature range legend** -- new `TireTempLegend` composable in `ChassisPage.kt`. Compact `Surf2`-background row with four `LegendDot` composables (5dp `CircleShape` dot + 7sp `MonoLabel`), laid out with `SpaceEvenly` arrangement. Thresholds adapt to the user's C/F unit preference via inline conversion (`(c * 9/5) + 32` for Fahrenheit). Only visible when `VehicleState.hasTireTempData` is true.
- **`VehicleState.hasTireTempData`** -- new computed property: `true` when any of `tireTempLF/RF/LR/RR > -90`. Used in `TpmsSection` to conditionally show the temp legend, update the section label to "TPMS -- PRESSURE & TEMPERATURE", and pass temp values to `TireCard`.

### Fixed (rc.10 -- WiFi routing, diagnostic log bug fixes)
- **REST API commands never reached firmware** — `FirmwareApi.kt` used a bare `Socket()` for HTTP POST to the WiCAN AP (192.168.80.1). Android 10+ detects the WiCAN WiFi as "no internet" and silently routes new sockets through cellular, causing all drive mode and ESC commands to time out. Now resolves the WiFi `Network` via `ConnectivityManager` and creates sockets through `Network.socketFactory`, guaranteeing traffic stays on the WiCAN WiFi regardless of internet validation. This was the root cause of both drive mode and ESC buttons being completely non-functional from the app.
- **Ignition status showed raw number "30" instead of "RUN"** — `CanDecoder.kt` extracted ignition from 0x0C8 byte2 using wrong bit mask (`and 0x1F`, bits 0-4). Correct field is bits 3-6 (`(shr 3) and 0x0F`). Verified against SLCAN diagnostic: byte2=`0x3E` now correctly decodes to 7="Run". All 10 periodic samples validate.
- **Odometer showed 109,756 km instead of correct 67,500 km** — `CanDecoder.kt` read 0x360 bytes[5:6] as a 16-bit wrapped odometer (44,220), then DID 0xDD01 added a +65,536 rollover offset → 109,756. The actual odometer is at bytes[3:5] as a full 24-bit value (frame `C4 C0 3F 01 07 AC BC B2` → `01 07 AC` = 67,500 km, matching the physical odometer exactly). Fixed to read 24-bit from bytes[3:5], eliminating the need for rollover offset entirely. DID 0xDD01 handler simplified to a straight 24-bit read (was already correct). Verified against real car diagnostic session (2026-03-21).
- **Engine status 196 (0xC4) shown as hex instead of label** — added cold-start engine state to the incomplete value table (0x360 byte0). Display now correctly handles this uncatalogued state.

### Added (rc.9 — F1 telemetry header, hero card polish, build system)
- **F1 telemetry header replaces single-row app bar**: The old `AppHeader` crammed logo, drive mode badge, ESC badge, connection dot, settings gear, and TRIP button into one horizontal row. Replaced with a two-part F1 pit-wall layout in `MainActivity.kt`. **Top row**: `openRS_` logo (Orbitron Bold 18sp) on the left; right side groups a tappable connection pill, TRIP button, and settings gear with consistent 8dp spacing. **Bottom row**: full-width `Surf2` telemetry strip with five `TeleCell` composables separated by `TeleDivider` (1dp × 22dp, `Brd` at 40% alpha). Each cell shows a 7sp JetBrains Mono label above a 10sp Share Tech Mono bold value.
- **Connection pill with state-aware coloring**: Replaces the standalone 8dp connection dot. Now a bordered `RoundedCornerShape(4.dp)` pill showing a 6dp `CircleShape` dot + text label. Three states: green "LIVE" (connected, dot pulses via `infiniteRepeatable` 1s `EaseInOut`), amber "IDLE" (retries exhausted), red "OFF" (disconnected). Tapping cycles `onConnect` / `onDisconnect` / `onReconnect` — same actions as the old dot, but with a visible tap target and readable status.
- **Telemetry strip — five real-time cells**: **MODE** — `VehicleState.driveMode.label.uppercase()`, color-coded: SPORT=`Ok` (green), TRACK=`Warn` (gold), DRIFT=`Red` (orange-red), NORMAL/CUSTOM=theme accent. **ESC** — `VehicleState.escStatus.label.uppercase()`, color-coded: OFF=`Red`, SPORT/LAUNCH=`Warn`, ON=accent. **CONN** — shows `framesPerSecond.toInt()` + " FPS" when connected (green), "—" when disconnected (dim). **IGN** — reuses `ignitionStatusLabel()` from `DiagPage.kt` (maps 0x0C8 byte 2 bits 0-4: KEY OUT / ACC / RUN / CRANK). **E-BRK** — `VehicleState.eBrake` boolean: ON=`Warn`, OFF=`Ok`. Previously these signals were only visible on DIAG/MORE tabs — now always visible at a glance.
- **Session peak speed on DASH hero card**: New `VehicleState.peakSpeedKph` field (default 0.0) tracked in `withPeaksUpdated()` via `max(peakSpeedKph, speedKph)` alongside existing `peakBoostPsi` and `peakRpm`. Reset to 0.0 in `withPeaksReset()`. SPEED hero card on DASH now passes `peak = "▲ ${p.displaySpeed(vs.peakSpeedKph)}"` to `HeroCard`, rendering the peak in the user's selected unit (MPH or KPH) as accent-colored text below the main value — matching the BOOST and RPM cards. Unit tests updated in `VehicleStateTest.kt`: `peak tracking updates higher values` (180 kph > 150 kph), `peak tracking does not lower peaks` (200 kph preserved), `peak reset clears all peaks` (peakSpeedKph → 0.0).
- **RC version suffix in Settings**: New `BuildConfig.RC_SUFFIX` string field added via `buildConfigField` in `build.gradle.kts`, reading from `project.findProperty("rcSuffix")`. Value set in `android/gradle.properties` (`rcSuffix=rc.9` for pre-release, empty for stable). `SettingsSheet.kt` version label uses `buildString { append("openRS_ v${BuildConfig.VERSION_NAME}"); if (BuildConfig.RC_SUFFIX.isNotEmpty()) append("-${BuildConfig.RC_SUFFIX}") }` — displays "openRS_ v2.2.4-rc.9" for RC builds, "openRS_ v2.2.4" for stable. No app code changes needed when promoting to stable — just clear the property.

### Changed (rc.9)
- **Rolling RC release workflow**: Updated `.cursor/rules/release-lifecycle.mdc`, `release-checklist.mdc`, and `release-artifacts.mdc`. Each version stream (android, fw-usb, fw-pro) now maintains **one pre-release** on GitHub that is updated in place as RCs progress — tag is moved, APK/binary is swapped, release notes are updated. Eliminates the pattern of creating a new release per RC that cluttered the releases page and required periodic manual cleanup.
- **Drive mode and ESC badges removed from header row**: Previously rendered as standalone `Box` composables with `RoundedCornerShape(4.dp)` backgrounds in the top row. Now surfaced in the telemetry strip's MODE and ESC cells, freeing horizontal space in the top row and making room for the connection pill.

### Fixed (rc.9)
- **SPEED hero card shorter than BOOST and RPM cards**: BOOST and RPM `HeroCard` instances rendered a `MonoText` peak line (9sp, ~13dp height) while SPEED had `Spacer(Modifier.height(4.dp))` in its place. Added `Modifier.height(IntrinsicSize.Max)` to the hero `Row` in `DashPage.kt` so the row constrains to the tallest child's intrinsic height, and added `.fillMaxHeight()` to each `HeroCard` modifier so shorter cards stretch to match. All three hero cards now render at identical height regardless of whether they have a peak value.

### Added (rc.8 — surface undisplayed signals, UX fixes)
- **Rally-style gear indicator on DASH tab**: Full-width hero card below the BOOST/RPM/SPEED row with a large 72sp Orbitron gear number and theme accent border glow. `VehicleState.gearDisplay` uses CAN 0x230 when available, falling back to the `derivedGear` algorithm (calibrated from live 16-minute log with known gear ratios for the Focus RS 6-speed manual: 1st≈3.79, 2nd≈2.18, 3rd≈1.89, 4th≈1.30, 5th≈0.85).
- **Session peak boost & peak RPM embedded in hero cards**: `VehicleState.peakBoostPsi` and `VehicleState.peakRpm` now shown as "▲" accent text directly inside the BOOST and RPM hero cards (GfCard pattern from CHASSIS tab), replacing the separate peak cards. `HeroCard` component gains optional `peak` parameter.
- **Battery SoC, voltage, and temperature on DIAG tab**: New data row shows `VehicleState.batteryVoltage` (OBD Mode 22 DID 0x0304), `VehicleState.batterySoc` (BCM 0x726 DID 0x4028, start/stop SoC %), and `VehicleState.batteryTempC` (BCM 0x726 DID 0x4029, A−40 °C). SoC cell color-coded: red < 50%, amber < 70%, green ≥ 70%.
- **Commanded AFR (lambda) on POWER tab**: `VehicleState.commandedAfr` (OBD Mode 01 PID 0x44, 0-2 lambda) now shown as "CMD AFR" in the FUEL TRIMS & AFR section. Complements the Mode 22 wideband AFR fields already present.
- **Low-pressure fuel rail on POWER tab**: `VehicleState.fuelRailPsi` (OBD Mode 01 PID 0x22, kPa→PSI) now shown as "LP FUEL" alongside the existing "HP FUEL" (Mode 22 DID 0xF422). Provides the low-pressure supply rail reading as a comparison to the direct-injection HP rail.
- **Manifold charge temperature on TEMPS tab**: `VehicleState.manifoldChargeTempC` (Mode 22 DID, °C) added to the temperature grid between CHARGE AIR and CATALYTIC. Warn at 60°C, critical at 90°C.
- **AWD max torque, PTU/RDU temps on CHASSIS tab**: When `VehicleState.awdMaxTorque > 0`, a new row appears in the AWD section showing AWD MAX (Nm), PTU TEMP, and RDU TEMP. Consolidates drivetrain thermal data alongside the torque split visualization.
- **TPMS pressure spread warning on CHASSIS tab**: When `VehicleState.maxTirePressSpread ≥ 4.0 PSI` and no tire is individually low, a "⚠ PRESSURE IMBALANCE — X.X PSI spread" amber banner appears. Alerts to uneven pressure across corners without triggering the more severe "LOW TIRE PRESSURE" warning.
- **No-internet hint on Trip page**: When `VehicleState.isConnected == true`, a subtle floating label ("No internet on adapter WiFi — tiles may be cached") appears at the bottom-right of the map. Addresses [#95](https://github.com/klexical/openRS_/issues/95) with a UX hint while a proper offline tile solution is developed.

### Fixed (rc.8)
- **Race-Ready banner ignored RDU and PTU temps**: `RtrBanner` on TEMPS tab used `UserPrefs.isRaceReady(oilTempC, coolantTempC)` which only checked oil and coolant. Now uses `VehicleState.rtrStatus` which also factors in RDU ≥ 30°C and PTU ≥ 40°C — matching the full warmup logic already defined in VehicleState. Banner now shows the complete breakdown (e.g. "Oil 72°C < 80°C · RDU 22°C < 30°C") instead of hardcoded threshold text.

### Added (rc.7 — surface rc.6 signals in UI)
- **Vertical G readout on CHASSIS tab** ([#104](https://github.com/klexical/openRS_/issues/104)): `VehicleState.verticalG` (CAN 0x180 bytes 0-1, decoded in rc.6) now displayed as VERT G card in G-Force row 1 alongside LAT G and LON G. COMBINED G moved to row 2 with YAW and STEER. RESET PEAKS button refactored to full-width bar below the cards.
- **Launch Control active banner on DASH tab** ([#105](https://github.com/klexical/openRS_/issues/105)): `VehicleState.launchControlActive` (CAN 0x420 bit 50, decoded in rc.6) now surfaces as a conditional "⚡ LAUNCH CONTROL ACTIVE" banner below the hero cards (BOOST / RPM / SPEED). Visible only when LC is engaged — applies to any drive mode (user puts car in 1st gear and holds at LC RPM set by tune). Banner hidden when `launchControlActive == false` to avoid clutter.
- **Engine status, ignition status, e-brake on DIAG tab** ([#106](https://github.com/klexical/openRS_/issues/106), [#107](https://github.com/klexical/openRS_/issues/107)): New data row in DIAGNOSTICS section displays `VehicleState.engineStatus` (CAN 0x360 byte 0: Idle/Off/Running/Kill/Start), `VehicleState.ignitionStatus` (CAN 0x0C8 byte 2 bits 0-4: Key Out/Acc/Run/Crank), and `VehicleState.eBrake` (CAN 0x0C8 byte 3 bit 6). Helper functions `engineStatusLabel()` and `ignitionStatusLabel()` map raw int values to human-readable labels.
- **ESC Launch mode indicator on MORE tab** ([#108](https://github.com/klexical/openRS_/issues/108)): When `VehicleState.escStatus == EscStatus.LAUNCH` (CAN 0x1C0 value 3), a "⚡ ESC LAUNCH MODE" banner appears below the ESC ON / SPORT / ESC OFF chips. The three main chips don't include LAUNCH as a tappable option (it's a car-initiated state, not user-selectable via CAN write).
- **CAN-based LC active on MORE tab** ([#105](https://github.com/klexical/openRS_/issues/105)): Launch Control card in the openRS-FW section now shows "⚡ ACTIVE" from passive CAN 0x420 (`launchControlActive`) when LC is engaged, taking priority over the firmware OBD probe state (`lcArmed`). This means LC engagement is visible even on stock WiCAN firmware (no openrs-fw required for the passive CAN signal).

### Added (rc.6 — free CAN signals from RS_HS.dbc)
- **Vertical G from CAN 0x180** ([#104](https://github.com/klexical/openRS_/issues/104)): Extracted `VertAccelMeasured` (bytes 0-1, 10-bit × 0.00390625 − 2.0 g) from the same ABSmsg02 frame that already provides lateral G and yaw rate. Completes the 3-axis accelerometer picture. Zero new CAN bus traffic.
- **Launch control status from CAN 0x420** ([#105](https://github.com/klexical/openRS_/issues/105)): Extracted `LaunchControlStatus` (bit 50) from the same drive mode detail frame. New `launchControlActive` boolean in VehicleState. Zero new CAN bus traffic.
- **Engine status from CAN 0x360** ([#106](https://github.com/klexical/openRS_/issues/106)): Extracted `engine_status` (byte 0) from the same frame that provides odometer. Values: 0=Idling, 2=Off, 183=Running, 186=Kill, 191=RecentlyStarted. Foundation for smart auto-disconnect and trip boundary detection.
- **Ignition status from CAN 0x0C8** ([#107](https://github.com/klexical/openRS_/issues/107)): Extracted `IgnitionStatus` (byte 2, bits 0-4) from the same frame that provides gauge brightness and e-brake. Values: 0=KeyOut, 4=Accessory, 6=IgnOn, 7=Running, 9=Cranking.

### Fixed (rc.6)
- **ESC "Launch" mode showed as "--"** ([#108](https://github.com/klexical/openRS_/issues/108)): `EscStatus.fromInt()` only handled values 0-2. DBC defines value 3 = Launch (RS_HS.dbc `VAL_ 448`). Added `LAUNCH("Launch")` to the enum. ESC chip on MORE page now correctly displays "Launch" when launch control is active.

### Fixed (rc.5 — full repo audit)
- **MeatPi users got WiCAN defaults on first run** — `AppSettings.getHost()` / `getPort()` always returned WiCAN defaults (192.168.80.1:80) even when adapter was set to MeatPi. Now checks adapter type and returns MeatPi defaults (192.168.0.10:35000) when no user-configured value exists.
- **Hardcoded colors outside theme system** — replaced 6 raw `Color(0xFF...)` in `SettingsSheet.kt` and `DiagPage.kt` with new `SurfUp` and `OnAccent` theme tokens.
- **Theme color duplication** — RS paint accent colors were defined in two separate `when` blocks (`UserPrefs.kt` and `MorePage.kt`). Centralized into `RsPaints` list and `rsPaintAccent()` / `rsPaintName()` in `Theme.kt`.
- **Stale CAN ID comments** in `VehicleState.kt` (referenced 0x430/0x3E8 instead of 0x0F8/0x2F0), stale `TODO(M-5)` in `CanDecoder.kt` (battery voltage now handled via Mode 22 DID 0x0304), hex case inconsistency in `ObdConstants.kt` (033e→033E), unused `kotlinx.coroutines.launch` import in `MainActivity.kt`.
- **`VehicleState.dataMode`** deprecated — unused field, always "CAN".

### Fixed (rc.4 — correct RS MK3 paint theme colours)
- **Theme colours didn't match actual Focus RS MK3 paint catalogue**: 4 of 6 themes were paints never offered on the MK3 (Tangerine Scream = Focus ST, Mean Green = never RS, Stealth = purple instead of grey, Moondust Silver = not on MK3). Nitrous Blue (`#00D2FF`) was too cyan; Race Red (`#FF2233`) was too neon-pink.
- **New palette uses verified MK3 paint options**: Nitrous Blue `#0091EA`, Race Red `#D62828`, Deep Orange `#D45500` (Heritage Edition), Stealth Grey `#6B7580`, Shadow Black `#3A3D44`, Frozen White `#E8ECF0`.
- Updated `UserPrefs.kt`, `MorePage.kt`, `Theme.kt`, `colors.xml`, `TripPage.kt` map marker, and browser emulator (CSS variables, 17 hardcoded rgba refs, JS theme map, theme chip HTML).

### Added (rc.3 — passive odometer from CAN 0x360)
- **Passive odometer decode from CAN 0x360** ([#103](https://github.com/klexical/openRS_/issues/103)): Community contributor @adamsouthern [discovered](https://github.com/klexical/openRS_/discussions/102) that CAN ID 0x360 passively broadcasts the odometer at ~5 Hz. Bytes [5:6] big-endian, 16-bit unsigned, 1 km per bit. Added as the 22nd decoder in `CanDecoder.kt`. Replaces the Mode 22 extended-session poll (DID 0xDD01) as the primary real-time odometer source. Cross-verified against our own diagnostic logs from car tests on 2026-03-16 and 2026-03-18 (~42K km car).
- **Mode 22 odometer reduced to once-on-connect** ([#103](https://github.com/klexical/openRS_/issues/103)): The UDS extended diagnostic session for odometer (BCM 0x726 → `10 03` + `22 DD 01`) was the heaviest OBD operation, running every 60 seconds. Now polls **once on connect** to get the full 24-bit value (up to 16.7M km), then uses the passive 0x360 broadcast for real-time updates. This also sets `odometerRolloverOffset` for cars past 65,535 km where the 16-bit value has rolled over.

### Changed (rc.3)
- `VehicleState.odometerKm` now primarily sourced from passive CAN 0x360 instead of Mode 22 polling. Added `odometerRolloverOffset` field (set from Mode 22 on connect) to correct for 16-bit rollover on high-mileage cars.
- `ObdResponseParser.parseBcmResponse` for DID 0xDD01 now computes and stores `odometerRolloverOffset = (km / 65536) * 65536`.
- `WiCanConnection` and `MeatPiConnection` extended session pollers now skip the odometer query after the first successful cycle.

### Fixed (rc.2 — tap-to-change modes)
- **Tap-to-change drive mode & ESC** ([#99](https://github.com/klexical/openRS_/issues/99)): Existing chips on MORE tab are now tappable to send commands via REST API. Drive mode works end-to-end with firmware v1.4+. ESC pre-wired for firmware v1.5 ([#98](https://github.com/klexical/openRS_/issues/98)).

### Added (rc.2)
- **App version in Settings** ([#90](https://github.com/klexical/openRS_/issues/90)): Shows `openRS_ vX.Y.Z` in the settings dialog footer using `BuildConfig.VERSION_NAME`.
- **Crash telemetry capture** ([#97](https://github.com/klexical/openRS_/issues/97)): `CrashTelemetryBuffer` maintains a rolling ring of the last 100 VehicleState snapshots. On uncaught exception, `CrashReporter` flushes the buffer + stack trace to `crash_telemetry_<ts>.json`. Crash files are automatically bundled into the next diagnostic ZIP export.

### Fixed (rc.1)
- **ESC decode — wrong bit position and swapped mapping** ([#96](https://github.com/klexical/openRS_/issues/96)): CAN 0x1C0 ESC mode was extracted at `bits(13,2)` but the actual signal lives at `bits(10,2)` (byte1 bits 5-4). The enum mapping also had 1=Sport/2=Off when SLCAN data proves 1=Off/2=Sport. SLCAN-verified: 0xC0=On, 0xD0=Off, 0xE0=Sport.
- **Throttle stuck at zero when CAN 0x076 absent** ([#93](https://github.com/klexical/openRS_/issues/93)): Some Focus RS variants don't broadcast 0x076 (throttle position). DASH now falls back to `accelPedalPct` from 0x080, showing "PEDAL" instead of a stuck-at-zero "THROTTLE" bar.
- **Battery voltage always 0.0V** ([#92](https://github.com/klexical/openRS_/issues/92)): No OBD query existed for battery voltage. Added PCM Mode 22 DID 0x0304 with formula `(A*256 + B) / 2048` V to the polling loop.
- **LC/ASS/FENG stuck on "PROBING" forever** ([#94](https://github.com/klexical/openRS_/issues/94)): FENG (0x727) and RSProt (0x731) ECUs don't respond on some cars. After 3 ext-session probe cycles (~3 min), the app stops probing and shows "N/A" instead.

---

## [v2.2.3] — 2026-03-15

### Fixed

- **Trip GPS permission broken** (#85): `ACCESS_FINE_LOCATION` was missing from `AndroidManifest.xml`. The "Grant Access" button on the Trip page fired the runtime request, but Android silently ignored it because the permission was undeclared.
- **Module Status / LC / ASS never populate** (#86): `mergeObdState()` omitted 7 fields — `rduEnabled`, `pdcEnabled`, `fengEnabled`, `lcArmed`, `lcRpmTarget`, `assEnabled`, `hpFuelRailPsi` — so OBD-parsed extended session data never reached the UI.
- **Diagnostic export missing ~24 OBD fields** (#87): `DiagnosticExporter.toJsonFields()` and the text summary excluded most Mode 22 parameters (ETC, WGDC, KR, OAR, TIP, VCT, oil life, HP fuel rail, charge air, catalyst, odometer, battery SOC/temp, cabin temp, module status flags). All now included in both JSON and text reports.
- **OBD response frames excluded from SLCAN log** (#88): OBD responses (0x7E8, 0x72E, 0x70B, 0x738, 0x72F, 0x739) were consumed by `ObdResponseParser` before reaching `DiagnosticLogger`. Added `logObdFrame()` in both `WiCanConnection` and `MeatPiConnection` so OBD traffic appears in the SLCAN export.
- **`reconnect()` not `@Synchronized`** (#81): Concurrent calls from the WiFi callback and UI could race on `connectionJob`, causing orphaned connections.
- **SLCAN writer never closed on `sessionEnd()`** (#81): The `BufferedWriter` leaked until the next `sessionStart()`. Now properly closed inside a synchronized block.
- **Connection dot untappable in a car** (#81): The 8dp circle had no touch padding. Wrapped in a 48×48dp tap target (Material minimum).
- **`TripRecorder.startTrip()` double-start** (#77): If called while already recording, a second coroutine would be launched, orphaning the first. Now guarded by `recorderJob?.isActive` check.
- **Trip recording stuck on permission revocation** (#83): If location permission was revoked mid-trip, the coroutine failed silently but `isRecording` stayed `true`. Wrapped location collection in `try/finally` that resets state.
- **DTC scan/clear adapter reference unsynchronized** (#83): `scanDtcs()` and `clearDtcs()` read `wican`/`meatpi` references without holding the lock. Captured inside `synchronized(this)` to prevent use of stale references during reconnect.
- **DiagPage swallows `CancellationException`** (#83): Generic `catch (_: Exception)` blocks caught and discarded `CancellationException`, breaking structured concurrency. Now rethrown.
- **PowerPage `!= 0.0` sentinel hides real zero readings** (#82): `timingAdvance`, `ignCorrCyl1`, `vctIntakeAngle`, `vctExhaustAngle`, `octaneAdjustRatio`, and fuel trims used `!= 0.0` as "has data" — hiding legitimate zero values. Now gate on `calcLoad > 0` (OBD data is present).
- **Inconsistent temperature sentinels** (#82): `chargeAirTempC` and `catalyticTempC` used `0.0` as default, meaning a real 0°C reading showed as "no data". Changed defaults to `-99.0` and guards to `> -90`, matching all other temperature fields.
- **ChassisPage wheel speed delta ignores unit preference** (#82): L/R and F/R delta values always showed "km/h" regardless of the user's speed unit setting. Now respects `prefs.speedUnit`.

### Improved

- **SettingsSheet respects theme accent** (#82): Title bar, SAVE button, Switch tracks, SegmentedPicker, and text field focus colors now use `LocalThemeAccent.current` instead of hardcoded cyan.
- **`LocalThemeAccent` → `staticCompositionLocalOf`** (#82): Theme accent changes only on settings save. `staticCompositionLocalOf` avoids unnecessary per-slot recomposition tracking.
- **MeatPiConnection TCP buffering** (#83): Wrapped raw `InputStream` in `BufferedInputStream`, reducing ~50,000 kernel syscalls/sec (byte-by-byte reads at 2100 fps) to buffered block reads.
- **Foreground notification** now shows "Connecting…" while attempting connection, "Connected to vehicle" once live, and "Disconnected — tap to retry" when idle. Previously always said "Connected" even during connection attempts.
- **Removed double inset padding** — `statusBarsPadding()`/`navigationBarsPadding()` was redundant with Scaffold's `innerPadding`, causing a gap at the top on some devices.
- **`install-debug.sh`** (#10): Now resolves the project directory from the script's location, so it works when invoked from any working directory.
- Compose BOM update (#14) deferred — the jump to 2026.02.01 caused ANR crashes; staying on 2024.11.00 until a safe incremental update path is validated.

---

## [v2.2.2] — 2026-03-12

### Fixed

- **Drive mode Sport/Track disambiguation** (#74): Sport mode incorrectly displayed as Track. Root cause: both modes produce `0x420 byte6=0x11` — the decoder only checked byte6. The real discriminator is byte7 bit 0 (`0=Sport, 1=Track`). Confirmed via live SLCAN capture (344k frames, WiCAN Pro session). `modeDetail420` now stores `byte6<<8|byte7`.
- **MeatPi Pro TCP socket timeout** increased from 5s to 20s, matching the WiCAN WebSocket timeout. The aggressive 5s timeout caused unnecessary disconnect/reconnect cycles during brief CAN traffic pauses.
- **Reconnect loop hammering** (#37): when a connection succeeded but dropped quickly (< 30 s), the reconnect delay was a flat 5 s and `failedAttempts` was reset every time — creating an infinite rapid-reconnect loop. Consecutive short-lived connections now apply escalating backoff (5 s → 10 s → ... → 60 s cap). Stable connections (> 30 s uptime) still use the configured reconnect delay. Applied to both `WiCanConnection` and `MeatPiConnection`.
- **Stale APK version comment** (#30): `build.gradle.kts` APK rename comment referenced `v2.0.1`; updated to use `{version}` placeholder since the version is dynamic.
- **Unused imports** (#29, #16): removed `WindowManager`, `DiagnosticLogger`, `DiagnosticExporter` imports from `MainActivity.kt`.

### Added

- **Foreground service with persistent notification** (#33): `CanDataService` now calls `startForeground()` with a low-priority "Connected to vehicle" notification while a CAN session is active. This prevents Android from killing the service when the app is backgrounded. Notification channel created in `OpenRSDashApp`, `foregroundServiceType="connectedDevice"` declared in manifest. Notification is removed when the connection stops.
- **OBD response malformed-frame logging** (#38): all six `ObdResponseParser` methods (`BCM`, `AWD`, `PCM`, `PSCM`, `FENG`, `RSProt`) now `Log.w` when a frame is too short to parse, including the raw hex bytes. Previously these were silent early returns.
- **Silent catch-block logging** (#4): critical `catch` blocks in `SlcanParser`, `MeatPiConnection` (readSlcanLine), and `CanDataService` (network callback registration) now log the exception instead of swallowing it. OBD polling send catches remain silent (expected failures during disconnect).
- **WiCAN Pro setup documentation**: hardware-setup.md now includes a full WiCAN Pro section with first-time setup instructions. Prominently documents the critical requirement to set the Pro's protocol to **SLCAN** in its web UI (the Pro defaults to ELM327 mode).
- **OPENRS? firmware probe for TCP**: `MeatPiConnection` now sends the `OPENRS?` probe after SLCAN init, enabling firmware detection on the WiCAN Pro (stock vs openrs-fw).

### Changed

- **SecureRandom reuse** (#17): `WiCanConnection.sendHttpUpgrade()` was creating a new `SecureRandom` instance per connection. Now reuses the existing `rng` field.

---

## [v2.2.1] — 2026-03-08

### Added

- **DTC Scanner**: New section at the top of the DIAG tab — tap "SCAN ALL MODULES" to query PCM, BCM, ABS, AWD, and PSCM for active, pending, and permanent fault codes via UDS Service 0x19. Results show each code, its description (from a bundled 873-code database), and fault status. Multi-module ISO-TP assembly with flow-control handles responses of any length.
- **DTC Clear Fault Codes**: "CLEAR FAULT CODES (0x14)" button appears in the DIAG tab after a scan returns results. Sends UDS Service 0x14 (ClearDiagnosticInformation, group 0xFFFFFF) to all five ECUs and reports per-module acknowledgement (✓/✗). Results are auto-dismissed after a successful clear.
- **DTC database**: 873 Ford-specific DTC descriptions bundled as `res/raw/dtc_database.json`. Loaded once on first scan.
- **Trip CSV export**: Trip ZIPs now include a `trip_<ts>.csv` alongside the existing GPX and summary text. The CSV has one row per GPS waypoint with 20 columns: timestamp, lat/lng, speed, RPM, gear, boost, temps (coolant, oil, ambient, RDU, PTU), fuel %, all four wheel speeds, lateral G, drive mode, and race-ready flag.
- **MeatPi Pro adapter support**: `MeatPiConnection.kt` is now fully implemented — raw TCP SLCAN (default `192.168.0.10:35000`), identical OBD polling as WiCAN (BCM, PCM, AWD, extended session), DTC scan and DTC clear support. Adapter selection (WiCAN / MeatPi Pro) added to Settings under a new ADAPTER section.
- **MeatPi microSD logging reminder**: Settings → ADAPTER → "MicroSD logging reminder" switch — a local note that directs users to enable SD logging via the WiCAN Pro web UI at `http://192.168.0.10/`.

### Changed

- **`CanDataService`**: OBD merge logic extracted to `mergeObdState()` and CAN frame processing to `processCanFrame()` — eliminates duplication between WiCAN and MeatPi paths. Added `clearDtcs()` suspend function (mirrors `scanDtcs()`).
- **`DiagPage`**: receives `onScanDtcs` and `onClearDtcs` lambdas from `MainActivity`, keeping the composable decoupled from the service.
- **MeatPi Pro defaults**: corrected default connection to `192.168.0.10:35000` (official WiCAN Pro AP address and SLCAN TCP port). The SLCAN port is user-configurable in the WiCAN Pro web UI; `35000` matches official MeatPi documentation.
- **MicroSD toggle clarified**: labelled "MicroSD logging reminder" — SD logging is managed in the WiCAN Pro web UI, no SLCAN command exists to enable it remotely.
- **Settings adapter switch**: switching the adapter picker now auto-populates host/port with the correct defaults for the selected adapter (only when the current value still matches the other adapter's default — custom IPs are preserved).
- **`DtcModuleSpec`**: moved from nested class in `WiCanConnection` to `com.openrs.dash.data.DtcModuleSpec`, removing the `WiCanConnection` import dependency from `MeatPiConnection` and `DtcScanner`.

### Refactored

- **Shared `AdapterState`**: connection lifecycle sealed class (`Disconnected`, `Connecting`, `Connected`, `Idle`, `Error`) extracted from both `WiCanConnection` and `MeatPiConnection` into `com.openrs.dash.can.AdapterState`. `CanDataService` now references `AdapterState` uniformly instead of adapter-specific inner classes.
- **Shared `ObdConstants`**: ~120 lines of duplicated OBD query strings, response CAN IDs, and polling intervals extracted into `com.openrs.dash.can.ObdConstants`. Both adapters now reference the single source of truth.
- **Shared `ObdResponseParser`**: ~150 lines of duplicated OBD response parsers (`parseBcmResponse`, `parseAwdResponse`, `parsePcmResponse`, `parsePscmResponse`, `parseFengResponse`, `parseRsprotResponse`) extracted into `com.openrs.dash.can.ObdResponseParser`. Zero behavioral change — identical formulas, single copy.
- **Shared `SlcanParser`**: SLCAN frame parsing (`parseStdFrame`, `parseExtFrame`, `parseDataBytes`) extracted into `com.openrs.dash.can.SlcanParser`. Both adapters now call `SlcanParser.parse()`.
- **Dead `connectionState` removed**: `CanDataService.connectionState` (which only returned WiCAN state regardless of adapter) was unused by any caller — removed.
- **DTC clear confirmation dialog**: tapping "CLEAR FAULT CODES" now shows a Material 3 `AlertDialog` explaining the action before proceeding. Prevents accidental clears.

### Removed

- **`HardwareAdapter.kt`**: unused interface and `AdapterType` enum (never implemented). `GpsData` data class was also only referenced within this file.

### Fixed

- **`MeatPiConnection` cancel latency**: TCP socket now closes immediately when the coroutine is cancelled (via a cancel-watcher child job), instead of waiting up to 20 s for `soTimeout`. `soTimeout` also reduced from 20 s → 5 s as a secondary safeguard.
- **DTC clear false-success banner**: "Cleared (0/0)" no longer shown when the `ack` map is empty (all sends failed). Empty or null `ack` now shows the "Clear failed" error.
- **DTC scan error message**: no longer reads stale captured `vs.isConnected` — now always shows `"Scan failed — check adapter connection"`.
- **`DiagPage` ghost spacing**: empty `MonoLabel("")` removed; the hint label is now conditionally omitted when the clear-status confirmation box is already visible.

---

## [v2.2.0] — 2026-03-06

### Added

- **Share Trip button**: Trip Summary sheet now has a "↑ SHARE TRIP DATA" button that exports the trip as a ZIP and triggers the system share sheet — wires up `DiagnosticExporter.shareTrip()` from the UI.

### Improved

- **UI architecture**: `MainActivity.kt` has been split into dedicated files — `Theme.kt` (design tokens, fonts, typography), `Components.kt` (shared composables), and one file per tab: `DashPage.kt`, `PowerPage.kt`, `ChassisPage.kt`, `TempsPage.kt`, `DiagPage.kt`, `MorePage.kt`. Significantly reduces `MainActivity.kt` size and improves maintainability.
- **Performance — `TripRecorder`**: eliminated O(n²) list copies on every GPS waypoint by introducing an `ArrayList` buffer. Average and mode breakdown now use O(1) incremental accumulators (`speedSum`, `speedSamples`, `modeCounts`) instead of re-scanning the full point list on each update.
- **Performance — `DiagPage`**: `DiagnosticLogger.frameInventorySnapshot` is now called once per composition pass instead of once per rendered row, eliminating redundant deep-copies.
- **`DiagnosticExporter`**: JSON string values are now properly escaped (backslash, quote, control characters) including `firmwareVersion` and `sessionHost` in the meta block. Old trip ZIPs are pruned on share to match the existing diagnostic ZIP pruning behaviour.
- **`DiagnosticLogger`**: `frameInventory` is now private; `frameInventorySnapshot` returns a proper deep-copy of all `FrameInfo` fields including `validationIssues` and `periodicSamples`, preventing external mutation.

### Fixed

- **Keep screen on**: "Keep screen on" setting now activates whenever the setting is enabled, regardless of connection state. Previously a brief WiFi dropout mid-drive could let the screen time out and lock.
- **PTU temperature in Trip HUD**: PTU cell now shows "—" before the first 0x0F8 CAN frame arrives instead of displaying the raw −99°C sentinel value.
- **Coolant / oil temp before first CAN frame**: defaults changed from `0.0` to `−99` sentinel. The RTR banner no longer incorrectly shows "Warming Up — Oil 0°C < 80°C" before any engine data arrives. Dashboard and Temps page show "—" for these fields while data is pending. `isRaceReady()` skips the check at sentinel so a car that's already warm isn't penalised on first connect.
- **Tire low warning false positive**: `anyTireLow()` lower bound changed from `0.0` to `0.01` to match `isTireLow()` — prevents a theoretical false trigger at exactly 0.0 PSI.
- **Trip recorder reset race**: `pointsBuffer` is now `@Volatile` and reset by reference assignment instead of `clear()`, eliminating a narrow race where a cancelled coroutine could still be mid-`add()` when the main thread cleared the list.
- **`CanDecoder`**: `ID_TPMS` constant renamed to `ID_PCM_AMBIENT` (0x340 carries ambient temperature, not TPMS). `describeDecoded` now shows the resolved drive mode string for `ID_DRIVE_MODE_EXT` (0x420). Dead TPMS validation branch removed.
- **`WiCanConnection`**: removed unused `RECONNECT_DELAY_MS` constant; replaced fully-qualified type names with short names after adding imports.
- **`TripRecorder`**: RTR (race-ready) gate now uses `UserPrefs.isRaceReady()` consistently, matching the Temps page.
- **`ThemePicker`**: accent colours read from `UserPrefs(themeId = id).themeAccent` — single source of truth, no duplicated hex map.

### Removed

- Legacy `VehicleState` fields `lambdaValue`, `launchControl`, and `driftFury` (unused; carried forward from prototype).
- Redundant `debugLines` pass-through property on `WiCanConnection` — callers now use `OpenRSDashApp.instance.debugLines` directly.

### Stability / Threading

- **`CanDataService.startConnection()` / `stopConnection()`**: marked `@Synchronized` — prevents a race condition where the WiFi callback thread and the main thread could both enter `startConnection()` simultaneously, initialising two `WiCanConnection` instances and leaking the first.
- **`onDestroy()`**: now calls `stopConnection()` before cancelling the coroutine scope, ensuring `DiagnosticLogger.sessionEnd()` is always flushed when the service is torn down (previously the final frames of a session could be lost).
- **`startActivity()` from background thread**: `DiagnosticExporter.share()` and `shareTrip()` now post the `startActivity(Intent.createChooser(...))` call through `Handler(Looper.getMainLooper())` — prevents an `android.view.ViewRootImpl$CalledFromWrongThreadException` crash on Android 10+ when the share sheet is triggered from `Dispatchers.IO`.
- **OBD query burst on send failure**: removed `return@forEach` from the BCM, AWD, and PCM query loops — the inter-query `delay()` now always fires even when a frame fails to send, preventing a rapid burst of retries when the connection is flapping.

### Performance

- **`sessionDurationMs` memoised**: `DiagPage` and `MorePage` now read `sessionDurationMs` at most once per 1 Hz FPS tick (via `remember(vs.framesPerSecond)`), cutting per-frame string allocations from `formatDuration`.
- **`ThemePicker`**: replaced 6 throwaway `UserPrefs(themeId = id)` allocations per recomposition with a lightweight private `themeAccentColor(id)` helper function.

### Correctness

- **PTU temperature thresholds**: `TempsPage` PTU card now uses dedicated `UserPrefs.ptuWarnC` / `ptuCritC` thresholds (street 95/110°C, track 85/100°C, race 75/90°C) rather than sharing the RDU values — the PTU (transfer case) is a different assembly with higher operating limits.
- **WebSocket handshake key**: `WiCanConnection` now generates a fresh 16-byte random key per connection (RFC 6455 compliant) instead of reusing the static RFC example key.
- **64-bit WebSocket frames**: oversized frames (len=127) that are discarded now emit a `DiagnosticLogger.event("WS", ...)` entry so they appear in the diagnostic export.

---

## [v2.1.0] — 2026-03-06

### Added

- **Trip page**: new TRIP overlay accessible from the app header with a live OSMDroid map (CartoDB Dark Matter tiles), GPS track recording, and weather data via OpenWeatherMap.

### Fixed

- **Notification permanently removed**: the persistent "Idle — tap openRS_ to retry" banner no longer appears. The foreground service has been replaced with a plain background service — no notification permission is requested at first launch and nothing ever appears in the notification shade.
- **Track drive mode**: the app now correctly identifies Track mode. `0x1B0` alone cannot distinguish Sport from Track; the fix adds `0x420` byte 6 as a second signal (`0x10` = Sport, `0x11` = Track), cross-validated against DBC and three live sessions.
- **Drift drive mode**: corrected to map to `0x1B0` nibble 2 (DBC `VAL_ 432` confirmed).
- **RPM hero card**: was showing a decimal (e.g. `0.85`) instead of a whole number. Now displays an integer (e.g. `3200 RPM`).
- **G-sensor stuck at ±1.992 g**: the invalid-frame guard was rejecting `0xFF` but not `0xFE`. Both are now treated as invalid and ignored.

### Removed

- `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_CONNECTED_DEVICE`, `FOREGROUND_SERVICE_LOCATION`, and `POST_NOTIFICATIONS` permissions stripped from the manifest.

---

## [v2.0.1] — 2026-03-06

### Stability

- **WebSocket connection leak fixed**: The TCP socket was not always closed when a connection error occurred during setup. All exit paths now properly close the socket.
- **Background poller kept running after disconnect**: The extended diagnostic session poller was missing from the disconnect cleanup block, causing it to silently keep running after a disconnection. Now cancelled alongside all other pollers.
- **Concurrent state update race conditions fixed**: Live CAN data and OBD poll responses could clobber each other when written simultaneously, silently discarding one update. Debug line writes and settings saves had the same issue. All state writes are now atomic.

### Data & Sensors

- **False tire pressure warnings on missing/dead sensors**: Low-pressure alerts now only fire for tires with valid sensor data. Tires with a dead TPMS sensor battery or no sensor at all are excluded from the warning.
- **Stale sensor readings shown after reconnect**: Tire pressures, temperatures, and odometer from a previous session could persist on-screen for up to 30 seconds after reconnecting. All polled fields now reset to "pending" the moment a new connection starts.
- **PTU temperature showed 0 °C before first data arrived**: The transfer case temp defaulted to 0, which displays as a valid −60 °C reading and caused the Ready-to-Race warm-up banner to always flag PTU as cold at startup. Now shows "— —" until the first real reading arrives, matching RDU behavior.
- **Oil life % could exceed 100%**: Raw PCM byte was stored without range clamping. Now clamped to 0–100%.
- **CAN ID discovery stopped too early**: New CAN IDs stopped being logged after 40 unique IDs were seen in a session. Limit raised to 200, covering a full drive.

### UI & Display

- **Settings Save button permanently blocked when auto-reconnect is off**: The reconnect interval field was validated even when auto-reconnect was disabled and hidden. An empty or invalid value locked the Save button with no way to clear it. Validation is now skipped when auto-reconnect is off.
- **Diagnostic validation warnings could grow indefinitely**: The validation log embedded live sensor readings in warning keys, causing a new entry to be created per frame instead of deduplicating. Keys are now static category strings.

### Diagnostics

- **Sharing a diagnostic export could freeze the UI**: Building and zipping a large log file ran on the main thread. On long sessions this could trigger an ANR ("App Not Responding") dialog. Moved to a background thread.
- **Last SLCAN log lines could be lost on session end**: The log writer buffer was not flushed when a session ended, losing up to 999 buffered lines. Buffer is now flushed on every session end.
- **Diagnostic ZIPs accumulated on-device without limit**: Export files were never cleaned up and could fill device storage over time. A new **"Max saved ZIP exports"** setting in Settings → Diagnostics controls the limit (default: 5 — oldest are deleted automatically).
- **Steering angle and brake pressure missing from diagnostic frame report**: Both CAN IDs were decoded and shown in the app but absent from the exported frame inventory. Both are now included.

### Removed

- **Android Auto removed**: All Android Auto and aauto-sdk code has been stripped from the app. This feature was explored in an earlier build but never shipped. Removing it eliminates an unresolvable binary dependency and keeps the codebase clean. The phone app is completely unaffected.

### Added

- **Max saved ZIP exports setting**: New field in Settings → Diagnostics. Controls how many diagnostic ZIP files are kept on-device. Default: 5.

### Tracked (pending data)

- **12V battery voltage PID unconfirmed**: The battery voltage field is wired up in the app but not yet populated — the correct PID for this vehicle has not been confirmed from a live log. Tracked for a future release.
- **Drive mode Track/Drift byte order needs live confirmation**: The Track and Drift byte values in the drive mode decoder need to be verified against a live SLCAN log with known modes to rule out a swap. Tracked for a future release.

---

## [v2.0.0] — 2026-03-06

### Redesign — F1-Style Telemetry Interface

Complete visual and structural overhaul of the openRS_ Android app.

#### Design System — F1 Palette
- **New color palette**: Deep navy-black backgrounds (`#05070A`/`#0A0D12`/`#0F141C`), cyan primary (`#00D2FF`), neon green (`#00FF88`), gold (`#FFCC00`), orange-red (`#FF4D00`)
- **Orbitron font** added — hero numeric values (boost, RPM, speed, temperatures) now display in F1-inspired Orbitron Bold
- **JetBrains Mono** added — all labels and keys now use JetBrains Mono for a clean monospace look
- **Section labels** redesigned: small caps text with an extending horizontal rule

#### Tab Structure — 6 Tabs (drawer → MORE tab)
- Previous hamburger drawer replaced by a **MORE tab** (6th tab) — content is always visible without an overlay
- Tab bar: `⚡ DASH` / `◈ POWER` / `◎ CHASSIS` / `△ TEMPS` / `≡ DIAG` / `☰ MORE` — each with icon and label
- Tab height increased to 52dp; active tab highlighted with themed underline

#### DASH Tab — Hero Cards + Bar Grid
- **Hero cards** redesigned: Orbitron large number, unit label above, name below — BOOST (gold), RPM (orange-red), SPEED (cyan)
- **Bar cards** added: THROTTLE / BRAKE / FUEL / BATTERY each show a gradient progress bar beneath the value
- AWD split bar redesigned with percentage hero numbers on each side
- Quick temps (Oil, Coolant, Intake, Oil Life) + G-force mini row moved below AWD

#### TEMPS Tab — Configurable Thresholds
- **Threshold preset picker** added: STREET / TRACK / RACE modes. Each preset adjusts warn and critical thresholds for oil, coolant, intake, and RDU/PTU
- **Warm-up banner** now computes readiness using the selected preset (race = tighter, street = relaxed)
- Temperature cards show a proportional threshold bar at the bottom
- RTR banner uses gradient background (green = ready, gold = warming)

#### MORE Tab — All Drawer Content
- Drive mode, ESC status, openRS-FW features (LC / ASS), Module Status (RDU/PDC/FENG) — all previously in the drawer, now in the MORE tab
- **RS Theme Picker**: 6 RS factory paint colour themes — Nitrous Blue, Race Red, Tangerine Scream, Mean Green, Stealth, Moondust Silver. Theme changes the primary accent colour throughout the app in real-time
- Settings shortcut button at the bottom of MORE tab

#### MeatPi Pro Groundwork (v2.1+)
- `HardwareAdapter` interface created — generic contract for CAN-over-WiFi adapters with `connect`, `send`, `disconnect`, `frames`, and `gpsData` flows
- `MeatPiConnection` stub created — ready to implement TCP + NMEA GPS in v2.1 when hardware is available
- `GpsData` data class defined for GPS position, speed, heading, altitude

#### Emulators Synced
- `docs/index.html` fully rebuilt to match v2.0 design (new fonts, 6 tabs, hero cards, bar grid, AWD split, TPMS, TEMPS with preset picker, DIAG console, MORE with theme picker)
- `android/browser-emulator/index.html` synced to match

---

## [v1.2.9] — 2026-03-05

### Fixed — Odometer always visible; moved to extended diagnostic session

- **Odometer row always shown on Dash tab** — previously hidden until BCM responded; now shows `—` placeholder immediately on connect, populates when data arrives.
- **BCM DID 0xDD01 moved to extended session loop** — odometer query now opens a UDS extended session (`10 03`) on BCM (0x726) before issuing the Mode 22 read, matching the same pattern used for AWD/PSCM/FENG. Polled every 60 s (extJob) instead of the 30 s default-session BCM cycle.
- **Emulators synced** — both `docs/index.html` and `android/browser-emulator/index.html` updated to match the v1.2.8 UI (was left on v1.2.7 layout in the v1.2.8 release).

---

## [v1.2.8] — 2026-03-05

### Added — Extended diagnostic session polling (UDS 10 03 + Mode 22)

New polling loop (T+15 s after connect, 60 s cycle) opens a UDS extended diagnostic session (service `10 03`) per module before issuing Mode 22 reads. Required for confirmed Daft Racing DIDs.

| Feature | Module | Address | DID | Source |
|---------|--------|---------|-----|--------|
| RDU status (rear drive unit on/off) | AWD | `0x703 → 0x70B` | `0xEE0B` | Daft Racing `rset.py` ✓ |
| PDC status (pull drift compensation) | PSCM | `0x730 → 0x738` | `0xFD07` | Daft Racing `rset.py` ✓ |
| FENG status (fake engine noise generator) | `0x727 → 0x72F` | `0x72F` | `0xEE03` | Daft Racing `rset.py` ✓ |
| LC/ASS status (launch control & auto start-stop) | RSProt | `0x731 → 0x739` | Probed | Runtime DID discovery |

All three confirmed DIDs display live on/off status in the new **Module Status** section of the hamburger menu.

RSProt LC and Auto Start-Stop DIDs are currently probed at runtime (candidate DIDs `0xDE00`–`0xDE02`). Any positive response is logged to the diagnostic debug buffer for confirmation. Once verified, they will be hardcoded in a future release.

### Added — New PCM Mode 22 PIDs (9 additional)

All polled on the existing PCM header (`0x7E0 → 0x7E8`), 30 s cycle:

| Parameter | DID | Formula | Displayed in |
|-----------|-----|---------|--------------|
| AFR Actual (wideband) | `0xF434` | `((A×256)+B) × 0.0004486` | Power tab |
| AFR Desired | `0xF444` | `A × 0.1144` | Power tab |
| TIP Actual | `0x033E` | `((A×256)+B) / 903.81` kPa | Power tab |
| TIP Desired | `0x0466` | same | Power tab |
| VCT Intake Angle | `0x0318` | `(signed(A)×256+B) / 16` ° | Power tab |
| VCT Exhaust Angle | `0x0319` | same | Power tab |
| Oil Life | `0x054B` | `A` % | Power tab |
| HP Fuel Rail Pressure | `0xF422` | `((A×256)+B) × 1.45038` PSI | Power tab |
| Fuel Level (Mode 22) | `0xF42F` | `A × 100/255` % | Dash tab |

### Fixed — Fuel level reading

Passive CAN frame `0x380` (`FuelLevelFiltered`) can encode up to 102% per the DBC spec, causing the display to show "101%" on a full tank. Two fixes applied:
1. Passive decode now clamps to 100% max.
2. PCM Mode 22 DID `0xF42F` (clean 0–100% range) overwrites the passive value every 30 s.

### Added — HP Fuel Rail Pressure

Power tab "FUEL RAIL" cell now shows high-pressure direct-injection fuel rail pressure in PSI (DID `0xF422`, up to 3,500 PSI), replacing the legacy Mode 01 PID 22 low-pressure value.

### Added — Odometer with km / mi toggle

Odometer (from BCM DID `0xDD01`, already polled since v1.2.x) now displays in the Dash tab below the G-force row. Tap the cell to toggle between kilometres and miles.

### Changed — Hamburger Features section

Launch Control and Auto Start-Stop cards now show live RSProt probe status:
- **ARMED / STANDBY / PROBING** for Launch Control
- **ACTIVE / OFF / PROBING** for Auto Start-Stop
- LC RPM target displayed when known (variable per user tune)

---

## [v1.2.7] — 2026-03-04

### Fixed — Drive mode Track/Drift corrected (requires firmware v1.3)

DBC cross-reference (`VAL_ 432 DriveMode`) and live log confirmation proved the nibble encoding is:

| Byte 6 (upper nibble) | Mode   |
|-----------------------|--------|
| 0x0_                  | Normal |
| 0x1_                  | Sport  |
| 0x2_                  | **Drift** |
| 0x3_                  | **Track** |

Previous releases had Track and Drift swapped. This caused "Track" to display as Drift and "Drift" to display as Track when either mode was active.

### Fixed — Connection stability (frequent red-light disconnects)

Root cause: `BCM_POLL_INTERVAL_MS` was lowered from 30 s to 10 s in v1.2.6, tripling OBD TX frequency. Combined with concurrent PCM polling, the WiCAN's TWAI TX queue was saturating at ~1882 fps passive CAN load, causing the passive stream to stall and triggering the 10 s read timeout repeatedly.

Changes:
- `BCM_POLL_INTERVAL_MS` reverted to **30 s**
- `PCM_POLL_INTERVAL_MS` set to **30 s**, initial delay offset to **T+20 s** so BCM and PCM cycles never overlap
- `soTimeout` increased from 10 s to **20 s** as a safety margin for WiCAN startup noise
- `PCM_QUERY_GAP_MS` increased from 150 ms to **200 ms**

### Removed — Battery voltage OBD query

Mode 01 PID 0x42 ("Control module voltage") is not supported by the Focus RS PCM. The query has been removed. The battery voltage UI field is retained for future investigation.

---

## [v1.2.6] — 2026-03-01

### Changed — TPMS polling interval reduced to 10 seconds

BCM Mode 22 TPMS polling (`BCM_POLL_INTERVAL_MS`) reduced from 30 s to 10 s. No bus load implications — Ford diagnostic tools poll far more aggressively. Tire pressure readings now refresh every ~12 seconds (10 s interval + ~2.4 s to cycle through all 8 BCM queries at 300 ms gaps).

---

## [v1.2.5] — 2026-03-01

### Fixed — Drive mode Track/Drift now display correctly

Reverted drive mode source from `0x17E` back to `0x1B0`, but with a correctly targeted extraction. Deep log analysis of a 20-minute session (including a brief Track mode segment) proved `0x17E` byte 0 lower nibble **stays at 1 (Sport) even while the car is in Track** — making it unreliable beyond Normal/Sport. The correct source is `0x1B0` byte 6 upper nibble, read only when byte 4 == `0x00` (steady-state frames). Button-event frames (byte 4 ≠ 0) are ignored to prevent transient flicker.

| Byte 6 (upper nibble) | Mode   |
|-----------------------|--------|
| 0x0_                  | Normal |
| 0x1_                  | Sport  |
| 0x2_                  | Drift  |
| 0x3_                  | Track  |

### Fixed — Gear indicator removed

The gear position field (CAN ID `0x230`) does not broadcast on this vehicle. The gear display widget has been removed from the UI.

### Fixed — TPMS formula switched to exportedPIDs source of truth

Updated BCM Mode 22 TPMS formula from the DigiCluster `can0_hs.json` formula to the community-validated Focus RS formula from `exportedPIDs.txt`:

> PSI = `(((256×A)+B) / 3.0 + 22.0/3.0) × 0.145`

TPMS now displays one decimal place (e.g. `42.3 PSI`) to reveal variance between tires and aid in validating sensor readings.

Passive TPMS decoding from CAN `0x340` bytes 2–5 has been removed entirely. `0x340` is `PCMmsg17` on HS-CAN — those bytes are PCM engine signals, not tire pressure. TPMS comes exclusively from BCM Mode 22 polling (PIDs `0x2813`–`0x2816`).

### Fixed — Fuel level now reads from correct CAN ID

`0x34A` was never present in logs. The correct source is `0x380` (`PCMmsg30`, `FuelLevelFiltered`), a 10-bit Motorola big-endian signal with factor 0.4 %. Live log confirmed: raw=254 → 101.6 % (full tank).

### Fixed — Battery voltage now polled via OBD-II

12V battery voltage does not broadcast on HS-CAN. Removed the stale `0x3C0` passive decoder. Battery voltage is now polled from the PCM via standard OBD-II **Mode 01 PID `0x42`** ("Control module voltage"), formula: `(A×256+B) / 1000 V`.

### Note — Firmware v1.2 required

Firmware v1.2 corrects the same `0x17E` vs `0x1B0` drive mode bug on the hardware side. See [firmware release fw-v1.2.0](https://github.com/klexical/openRS_/releases/tag/fw-v1.2.0).

---

## [v1.2.4] — 2026-03-01

### Fixed — TPMS formula aligned to DigiCluster reference

Replaced the `exportedPIDs.txt` formula `(((A×256)+B)/3 + 22/3) × 0.145` with the DigiCluster `can0_hs.json` formula `((A×256)+B) / 2.9 × 0.145038`. Both produce the same result at real-world pressures (< 0.1 PSI difference at 35 PSI) but the DigiCluster formula is the explicit reference implementation for this vehicle with no offset term.

### Note — Firmware v1.1

Firmware v1.1 is required for the firmware badge in the app to show `openRS_ v1.1` instead of `WiCAN stock`. The firmware now:
- Responds to the Android app's `OPENRS?` WebSocket probe with `OPENRS:v1.1`
- Reads steady-state drive mode from `0x17E` byte 0 lower nibble (was incorrectly reading from `0x1B0` button event frames)

See [firmware release fw-v1.1.0](https://github.com/klexical/openRS_/releases/tag/fw-v1.1.0) for flash instructions.

---

## [v1.2.3] — 2026-03-04

### Fixed — Drive mode always showing Normal

The drive mode badge was reading from CAN ID `0x1B0` byte 6 lower nibble. Analysis of a 16-minute, 1.9M-frame live log revealed this is wrong: `0x1B0` byte 6 only changes during dial-turn **transition events** (lasting 50–200 ms), not while a mode is held. Frames counted by mode: Normal 30,642 vs Sport 546 — but separately, `0x17E` showed Normal 547 / Sport 8,824, matching the actual 16-minute session where the driver was in Sport for ~15 minutes.

**Root cause:** `0x1B0` carries AWD/mode transition data; it reverts to Normal between mode-dial clicks. The RS_HS.dbc contains `BO_ 382 x17E` with `SG_ DriveModeRequest : 3|4@0+ (1,0) [0|15]` — the lower nibble of byte 0 is the steady-state selected mode.

**Fix:** Replaced `ID_AWD_MSG = 0x1B0` with `ID_DRIVE_MODE = 0x17E`. Decoder: `data[0].toInt() and 0x0F`.

| Value | Mode   |
|-------|--------|
| 0     | Normal |
| 1     | Sport  |
| 2     | Track  |
| 3     | Drift  |

### Fixed — TPMS only showing one tire (LR)

The passive CAN `0x340` frame (bytes 2–5, per RSdash/DigiCluster `can1_ms.json`) is bridged by the Gateway Module to HS-CAN, but the GWM on this car only populates byte 4 (LR). The other three tire pressures remain on MS-CAN only and are not accessible via the OBD port passively.

**Fix:** Added BCM Mode 22 polling for all four tire pressures using PIDs 0x2813 (LF), 0x2814 (RF), 0x2816 (LR), 0x2815 (RR). Formula from `exportedPIDs.txt`:

```
PSI = (((256×A)+B)/3 + 22/3) × 0.145
```

Validated: LR via BCM should agree with the passive CAN `0x340` byte 4 reading (35 PSI in the log). Polls run every 30 s alongside the existing BCM cycle. Passive CAN `0x340` continues to provide real-time LR updates between polls.

Also added sentinel guards in `CanDataService.onObdUpdate` to prevent a BCM response captured before the first passive CAN frame from resetting tire pressures back to the `−1.0` default.

---

## [v1.2.2] — 2026-03-04

### Fixed — Gear display always showing Neutral

The Focus RS does not broadcast gear position on the passive HS-CAN bus (`0x230` is absent from every observed log — 1.9 M frames over 16.8 minutes). The `gear` field in `VehicleState` therefore never updated from its default of `0` (Neutral).

**Fix:** Added `derivedGear: Int` as a computed property on `VehicleState`. It calculates the current gear from RPM ÷ vehicle speed using the known Focus RS Getrag MT-82 final-drive ratio and 235/35R19 tire circumference:

```
ratio = rpm × 0.03194 / speedKph
  where 0.03194 = circumferenceM(2.033) × 3.6 / (60 × finalDrive(3.82))
```

Gear thresholds (midpoints between empirically measured values from a live log):

| Gear | Measured ratio | Threshold |
|------|---------------|-----------|
| 1st  | ≈ 3.79        | ≥ 2.99    |
| 2nd  | ≈ 2.18        | ≥ 2.03    |
| 3rd  | ≈ 1.89        | ≥ 1.60    |
| 4th  | ≈ 1.30        | ≥ 1.00    |
| 5th  | ≈ 0.85        | ≥ 0.74    |
| 6th  | < 0.74        | —         |

Returns `N` when speed < 3 kph or RPM < 400, and `R` when `reverseStatus` is set.

`gearDisplay` prefers the CAN-sourced `gear` field (for future compatibility if `0x230` ever appears) and falls back to `derivedGear`.

---

## [v1.2.1] — 2026-03-04

### Fixed — Decoder Bug: Yaw Rate, Steering Angle, Brake Pressure

Three CAN decoder signals were producing physically impossible values due to a mismatch between the `bits()` helper function and the bit numbering convention used for these specific signals in the RS_HS.dbc.

**Root cause:** The `bits()` helper uses MSB-first network bit addressing (bit 0 = MSB of byte 0), which is correct for signals like torque, ESC mode, and AWD torque. However, yaw rate (`35|12`), steering angle (`54|15`), steering sign (`39|1`), and brake pressure (`11|12`) were authored with the standard DBC LSB-first Motorola convention. Using the wrong extractor produced:
- **Yaw rate:** +37.5 °/s on a stationary vehicle (correct value: ≈ 0 °/s)
- **Steering angle:** ±239° while parked straight (correct value: ≈ 7.5° off-center)
- **Brake pressure:** underreported by ~50% at equivalent pedal application

**Fix:** Replaced `bits()` calls with manual `(byteN & mask) << shift | byteM` extraction for the three affected signals, matching the pattern already used for lateral G, longitudinal G, coolant temp, IAT, RPM, and wheel speeds. All corrected formulas validated against a 16-minute live log (1,911,479 frames):
- Yaw rate now reads ≈ 0 °/s at rest, rising to +45 °/s during a documented U-turn at 17 kph — cross-checked against the lat-G/speed/yaw triangle (≈ 4% error)
- Steering angle shows ≈ −360° during full-lock left turn and ≈ +83° during parking manoeuvre — both physically consistent
- Brake pressure confirmed at 22.3% for an initial brake application captured at session start

Updated `bits()` docstring to clarify its MSB-first convention and which signals require it vs manual extraction.

---

## [v1.2.0] — 2026-03-04

### Redesign — Full UI/UX Overhaul

Complete visual and structural redesign of the app. The previous 8-tab layout has been consolidated into a more focused, information-dense 5-tab design with a System Drawer for controls.

**Tab structure:**
- **DASH** — Hero boost/RPM/speed gauges, 8-cell data grid, AWD split bar with animated gradient, G-force row
- **POWER** — AFR hero cards, throttle & boost grid (ETC, WGDC, TIP), engine management (timing, load, OAR, KR, VCT), fuel trims & misc
- **CHASSIS** — AWD section (4 wheel speeds + torque bar + deltas), G-Force section (yaw, steering, peaks + inline reset), TPMS section with car outline graphic
- **TEMPS** — RTR banner (warming up vs race ready with animated dot), 10 temp cards in a 2-col grid, each with a color indicator bar at base
- **DIAG** — Diagnostic snapshot, live CAN output, frame inventory (unchanged from v1.1.6)
- **☰ System Drawer** — Drive mode (read-only), ESC status (read-only), Features (firmware-gated), Connection & Diagnostics with snapshot button

### Added — New Font System
- Embedded **Share Tech Mono** (numeric readouts, data values, technical labels)
- Embedded **Barlow Condensed** (UI labels, section headers, button text)
- All fonts offline-embedded — no network dependency

### Added — New CAN Signals
- **Steering wheel angle** from `0x010` (`SASMmsg01`) — DBC-verified: `(byte6&0x7F)<<8|byte7 × 0.04395°`, signed via `byte4 MSB` *(formula corrected in v1.2.1)*
- **Yaw rate** from `0x180` (`ABSmsg02`) — decoded alongside existing lateral G: `(byte4&0x0F)<<8|byte5 × 0.03663 − 75 °/s` *(formula corrected in v1.2.1)*
- **Brake pressure** from `0x252` (`ABSmsg10`) — DBC-verified: `(byte1&0x0F)<<8|byte2` raw 0–4095, displayed 0–100% (bar calibration pending from live log) *(formula corrected in v1.2.1)*

### Added — PCM Mode 22 Polling
New ISO-TP polling cycle to PCM (`0x7E0` → response `0x7E8`) every 10 seconds:
- **ETC Actual** (`0x093C`) — electronic throttle actual angle
- **ETC Desired** (`0x091A`) — electronic throttle desired angle
- **Wastegate DC** (`0x0462`) — turbo wastegate duty cycle %
- **Knock Retard Cyl 1** (`0x03EC`) — ignition correction cyl 1 (°)
- **Octane Adjust Ratio** (`0x03E8`) — knock learning OAR
- **Charge Air Temp** (`0x0461`) — charge air cooler outlet (°C) — verified via DigiCluster can0_hs.json
- **Catalyst Temp** (`0xF43C`) — catalytic converter temp (°C) — verified via DigiCluster can0_hs.json

### Updated — Ready-to-Race Thresholds
RTR banner now uses safe conservative warm-up thresholds with status text showing which sensors are still cold:
- Engine Oil ≥ 80 °C (was 65 °C)
- Coolant ≥ 85 °C (new)
- RDU ≥ 30 °C (was 20 °C)
- PTU ≥ 40 °C (was 50 °C)

### Updated — Header
- **Pulsing connection dot** replaces text-based LIVE/OFFLINE indicator
- **Drive mode badge**, **gear number**, **ESC status** all visible at a glance
- Speed removed from header (shown prominently on DASH tab hero row)
- ⚙ Settings gear retained

---

## [v1.1.6] — 2026-03-01

### Fixed — TPMS formula (tires showing no data)
- The TPMS pressure bytes in CAN frame `0x340` use a **3.6 kPa per unit** encoding, not direct PSI.
- Formula corrected to: **PSI = raw × 3.6 ÷ 6.895** (e.g. raw `0x43` = 67 → 35.0 PSI).
- The previous decoder treated raw bytes as PSI directly and capped acceptance at 60, causing a raw value of 67 (which represents a perfectly normal ~35 PSI tire in winter) to be rejected entirely — all four tires showed blank.
- Valid result range updated to **5–80 PSI** (converted, not raw). Sleeping sensors (raw = 0) correctly display no data and retain last known pressure.

### Fixed — Firmware detection always showing "WiCAN stock" despite openRS_ firmware
- The previous probe used a **20-frame grace window** to wait for the `OPENRS:` response. At ~1,700 fps, those 20 frames are consumed in approximately **12 milliseconds** — before the firmware can even process the probe command and reply.
- Replaced the frame-count check with a **3-second elapsed-time window**. The app now continues checking every incoming frame for the `OPENRS:` identifier for up to 3 seconds before falling back to "WiCAN stock."
- Users with openRS_ firmware will now see correct firmware detection in the DIAG tab and diagnostic logs.

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
