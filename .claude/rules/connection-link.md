---
paths:
  - "**/com/openrs/dash/can/Slcan*.kt"
  - "**/com/openrs/dash/can/*Transport*.kt"
  - "**/com/openrs/dash/can/FirmwareApi.kt"
  - "**/com/openrs/dash/can/DriveCommand.kt"
  - "**/com/openrs/dash/can/EscCommand.kt"
  - "**/com/openrs/dash/can/Mode22Frames.kt"
  - "**/com/openrs/dash/can/WriteOutcome.kt"
  - "**/com/openrs/dash/can/AdapterState.kt"
  - "**/com/openrs/dash/can/IsoTpBuffer.kt"
  - "**/com/openrs/dash/service/CanDataService.kt"
  - "**/com/openrs/dash/service/Link*.kt"
  - "**/com/openrs/dash/service/AutoRecordPolicy.kt"
  - "**/com/openrs/dash/service/ConnectionSetup.kt"
  - "**/com/openrs/dash/can/CanDecoder.kt"
  - "**/com/openrs/dash/data/ObdMerge.kt"
  - "**/com/openrs/dash/demo/DemoSimulator.kt"
  - "**/com/openrs/dash/ui/BleDeviceDraft.kt"
  - "**/com/openrs/dash/ui/DriveModeDock.kt"
  - "**/com/openrs/dash/ui/MorePage.kt"
  - "**/com/openrs/dash/ui/SettingsSheet.kt"
  - "**/firmware/**"
  - "**/android/app/src/test/java/com/openrs/dash/*Slcan*"
  - "**/android/app/src/test/java/com/openrs/dash/*Link*"
  - "**/android/app/src/test/java/com/openrs/dash/*DriveMode*"
  - "**/android/app/src/test/java/com/openrs/dash/*ModeAttribution*"
  - "**/android/app/src/test/java/com/openrs/dash/*ModeProvenance*"
  - "**/android/app/src/test/java/com/openrs/dash/*Esc*"
  - "**/android/app/src/test/java/com/openrs/dash/*Connection*"
  - "**/android/app/src/test/java/com/openrs/dash/*Firmware*"
  - "**/android/app/src/test/java/com/openrs/dash/*Transport*"
---
# openRS_ Known Gotchas — Connection, SLCAN link, service ownership, firmware commands

Moved verbatim from the tracked `CLAUDE.md` (section Known Gotchas) on 2026-11-06. Add a new gotcha of this area at the end; never reword an existing one here.

- Firmware drive mode is **hybrid scroll-then-wait** (v1.61+) — pre-calculates scroll count, sends all presses open-loop, waits up to 6s for auto-confirm. Do NOT revert to closed-loop press-and-poll (v1.6 bug) or pure open-loop press counting (v1.5 bug). The Focus RS mode selector GUI requires ~4s of inactivity to auto-confirm — CAN does not update during scrolling
- Firmware REST POST `/api/frs` returns `{"ok":false,"busy":true}` when a drive mode change is in progress — app must handle this
- Android 10+ silently routes new sockets through cellular when WiFi has no internet — `WiFiFirmwareApi` uses `Network.socketFactory` via `ConnectivityManager` to force all traffic on WiFi. BLE transport avoids this entirely (not a "network" in Android's routing model)
- **Firmware commands never throw (#238 review F1, `2b59644`)** — `BleFirmwareApi.sendCommand` turns the write's
  IOException into `Result.failure` (cancellation still propagates). The UI launches (DriveModeDock, MorePage ESC) have
  no catch, so an escaped exception ended the process. Keep every `FirmwareCommandSender` inside its `Result` contract.
  `BleCommandSendFailureTest` (real SlcanConnection over BleGattCore, fake GATT)
- **SLCAN framing is exact, per connection (#251, rc.8)** — a `t` / `T` line's data field must be exactly DLC × 2 hex digits
  (`SlcanParser.parse(line, timestamped)`); anything else is refused and counted (LINK INTEGRITY in exports). `SlcanFraming`
  in `SlcanConnection` starts each connection plain and locks to timestamped (exactly DLC × 2 + 4 hex, suffix stripped) only if
  the first 8 well-formed frames all carry the suffix (adapter left in `Z1` by another client); no mid-connection switch;
  50 consecutive other-shape lines log one FRAMING event (no raw line). Never accept "+4" statelessly: a spliced line and a
  one-nibble DLC corruption both look like +4. `SLCAN_INIT` is unchanged (sending `Z0` is a reviewed follow-up, not done).
  - Mode 22: a PCI that claims more bytes than the frame holds (or no data byte) is refused
    (`ObdResponseParser.singleFrameDataBytes`); a decoder whose value needs a byte the reply doesn't carry updates nothing
    (it used to read the missing byte as 0). A one-byte value in a short reply still decodes. `PidRegistry.decode` refuses a two-byte formula without B.
  - Well-formed but implausible single frames (e.g. 0x130 = 0xFFFF → 655.35 km/h) are NOT filtered — an open owner decision
    (`#251 outlier policy`, the one @Ignore'd test). Don't invent physical limits.
- **Foreground service only while connected** — `MainActivity.startSvc()` only **binds** `CanDataService`. `startConnection()`
  promotes it via `ForegroundController.promote()` (startForegroundService(self) immediately followed by startForeground);
  `stopConnection()` demotes (stopForeground + stopSelf), so an idle service lives only while the activity is bound. Never
  call `startForegroundService()` without a guaranteed `startForeground()` — the old per-`onCreate` call ANR'd whenever the
  activity was recreated offline / in demo. `promote()` is in a try/catch because WiFi/Bluetooth callbacks can fire when the
  app is backgrounded (Android 12+ refuses the start). `manualConnectRequired` is process-wide (`LinkOwnership` in the companion) because the idle
  service instance is recreated with the activity
- **Connection ownership + Cancel (#212)** — every connection transition goes through `CanDataService.link`
  (`LinkOwnership`, process-wide): callback starts use `autoStart` (check + start in one step), a job publishes its phase
  with `publish(gen, …)` and applies late results (OBD merges posted to Main, CAN frames, WiCAN status, auto-scan counts)
  with `deliver(gen) { }` / `owns(gen)`. Never add a delayed write from a connection job without that check. Cancel has ONE
  rule — `LinkPresentation.offersCancel(phase, recording)`: initial attempt and no recording (paused counts) — enforced in
  `LinkOwnership.cancel` when the request is handled (stale notification actions are ignored) and mirrored by the pill /
  DRIVE (`cancelable`). An explicit Cancel is **persisted** (`LinkOwnership.CancelStore`, prefs file `openrs_link`, loaded
  first in `CanDataService.onCreate`) until Connect / Retry — Home, Back, Recents, process death and Force stop keep it;
  the demo hold is memory-only. Raw CAN frames and auto-record go through `LinkSession` (`deliver(gen)`); blocking work
  (perf-run insert) runs after the lock. `reconnect()` (RCN tap) builds a fresh SlcanConnection, so it reads CONN; while
  recording it can't be cancelled
- **Retry while recording** — `keepRetrying` (= recording) extends retries past `maxRetries` with a 30 s backoff polled every
  second; when recording ends the bounded policy applies at once (budget spent → Idle → service released). Notification
  text is re-published on recording start / pause / resume / stop. Auto-record never splits a drive still recording
  (`AutoRecordPolicy`)
- **Transport abort** — TCP / WebSocket assign their socket before `connect()` so `abort()` ends a blocked connect at once
  (emulator: ≤ 0.5 s). Without a cancel, an adapter that accepts but never answers (WS upgrade / SLCAN handshake) holds
  the attempt for the 20 s socket read timeout — the 3 s handshake `withTimeoutOrNull` can't interrupt a blocking read
- **`UserPrefsStore` outside the activity** — `CanDataService` is START_STICKY: Android can recreate it (and it auto-connects /
  auto-records) with no MainActivity, so its `onCreate` calls `UserPrefsStore.ensureLoaded()` first. Anything service-side
  that reads `UserPrefsStore.prefs` relies on that
- **ESC confirmation (#125)** — the adapter's OK is acceptance only. `EscCommand` waits the firmware's own press plan
  (5 s long press for OFF, 300 ms presses, 1 s gap) + 3 s (unmeasured), needs a 0x1C0 frame stamped (`escStatus`) after the
  request on the same `CanDataService.link.generation`; reconnect → ConnectionLost; one request at a time. Firmware
  (`focusrs.c` / `focusrs_esc_plan.c`) saves boot ESC only for a CAN-confirmed change; only a 0x1C0 frame decoding to On /
  Off / Sport counts (raw 3 no longer advances `esc_frames`). `firmware/tests/run.sh` runs the real `focusrs.c` against
  host stubs (`tests/stubs/`). PRO built once in isolation 2026-10-29 (evidence dir), **never flashed** — adapters keep
  their old firmware. Firmware detection ≠ control capability
- **Drive-mode confirmation (#252, rc.8; accepted-vs-confirmed since `4937cfb`)** — the adapter's OK is "ACCEPTED";
  `executeDriveModeChange` confirms only from CAN observed AFTER the request on the same link generation: two agreeing resolved
  0x1B0 reports (stamp `driveMode`), and for Sport / Track also two agreeing documented 0x420 details
  (byte 6 0x10 / 0x11 / 0x12 ± LC bit) received after the request, the second before the resolving report
  (`driveModeDetailPair` → copied to `driveModeResolvedPair`), and the current 0x1B0 run is the target (`driveModeRun.<MODE>`,
  `driveModeRunPrev`). `driveModeDetail` / `driveModeResolvedDetail` are provenance only (export text, `fieldAgesMs`). An undocumented 0x420 byte 6 changes nothing; a 0x1B0 that changes the resolved mode is stamped
  at once (the 1 s throttle applies only to unchanged reports). The one overshoot correction needs the same corroboration, so
  a single stray frame can never trigger it. OBD merges must not bring back drive-mode provenance keys from their snapshot
  (`ModeProvenanceMergeTest`). Requesting the current mode now confirms ~1 s later (two stamped reports). A confirmation states
  the car's mode after the request, not that the app caused it (a manual selection looks identical; in capture A the 0x305 button bit never set, 0 of 874 frames — QA analysis).
  Timing (2 s + 15 s), retries, ESC and firmware unchanged; reconnect → `DriveCommandResult.ConnectionLost`. Demo must stamp
  every CAN tag (`CanDecoder.canStampTags` ⊆ `DemoSimulator.CAN_TAGS`, DemoSimulatorTest) or confirmations time out in demo.
  ESC confirmation (#125) still reads `escStatus` under the 1 s throttle (can confirm up to ~1 s late; not changed)
- **Bluetooth with no saved device (#234, fixed on feature/rc7 — verified on the emulator only; trigger: connection method
  Bluetooth saved with no device selected — on such installs `a605b1c` and earlier crash at every launch; Wi-Fi, or
  Bluetooth with a device, is unaffected)** — one rule,
  `ConnectionSetup.problem(method, address)`: Settings SAVE checks the draft (`BleDeviceDraft.saveProblem`, FORGET counts);
  `beginConnection` checks the saved prefs BEFORE `foreground.promote()` / `sessionStart` / `startSessionRecording` (no
  notification, session or retry — catching the exception would not have been enough); DRIVE reads SELECT A BLUETOOTH
  DEVICE and Connect opens Settings on CONNECTION; prefs never switched. `connectHybrid` guards `transportFactory()`
  (`UnbuiltTransport` → ordinary failed attempt; cancellation rethrown). Release logcat `Session N started` (autoGenerate
  ids) is how to count sessions on a non-debuggable build
