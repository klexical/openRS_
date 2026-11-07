---
paths:
  - "**/com/openrs/dash/**"
---
# openRS_ Known Gotchas — Demo mode (debug builds)

Moved verbatim from the tracked `CLAUDE.md` (section Known Gotchas) on 2026-11-06. Add a new gotcha of this area at the end; never reword an existing one here.

- **Demo mode owns VehicleState while active** — `CanDataService.enterDemoMode()` finalizes any recording drive, calls
  `stopConnection()`, then `DemoMode.start()`. While `DemoMode.isActive`: `processCanFrame` / `mergeObdState` / the adapter
  state collector early-return, `startConnection()` / `reconnect()` are no-ops, `firmwareApi` returns `DemoFirmwareApi`,
  DTC scan/clear/retry and `sendRawQuery` are answered by `DemoMode`, and `resetSession()` replays the scenario. Never
  bypass these — demo frames would mix with real ones
- **Simulated data never persists** — guarded writers: `DriveRecorder.startDrive()` (TRIP START shows "DEMO — NOT
  RECORDED"), `PerfPage` perf-run insert, `CrashTelemetryBuffer` push, `DiagnosticExporter` session-knock CSV. Demo DTC
  scans skip `DtcScanner` (which inserts `dtc_scans`). Any new Room / export writer of live telemetry needs the same
  `DemoMode.isActive` guard
- **Leaving demo requires an explicit connect** — `exitDemoMode()` resets VehicleState + session logs and sets
  `manualConnectRequired`; WiFi/BT/onCreate callbacks go through `autoStartConnection()`, which honours it (and
  `DemoMode.launchRequested`, so a `--es demo` launch never opens the adapter). Only the UI's connect/reconnect clears it
- **UI-held histories must key on `telemetrySessionKey()`** — `remember { SparklineData(..) }` / `RingBuffer` in
  composables outlive VehicleState resets, so simulated traces would survive demo exit. DrivePage / TempsPage / PowerPage /
  ChassisPage buffers (+ their last-sample stamps, ThermalPredictor) use `remember(telemetrySessionKey())`
- **Demo simulates only fields a real decoder writes** — `manifoldChargeTempC` and the IPC `warn*` lamps have no decoder,
  so they stay at their sentinels (DemoSimulatorTest guards this). Add a field to the simulator only with its decoder
