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
  - "**/com/openrs/dash/can/ReceptionStall.kt"
  - "**/android/app/src/test/java/com/openrs/dash/*ReceptionStall*"
  - "**/com/openrs/dash/can/ObdResponseParser.kt"
  - "**/com/openrs/dash/ui/MainActivity.kt"
  - "**/com/openrs/dash/can/ObdConstants.kt"
  - "**/com/openrs/dash/service/ForegroundController.kt"
  - "**/com/openrs/dash/can/PidRegistry.kt"
  - "**/com/openrs/dash/service/CanDataService.kt"
  - "**/com/openrs/dash/service/Link*.kt"
  - "**/com/openrs/dash/can/AdapterProbe.kt"
  - "**/com/openrs/dash/service/AdapterNetworkFinder.kt"
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
  - "**/android/app/src/test/java/com/openrs/dash/AdapterNetwork*"
  - "**/android/app/src/test/java/com/openrs/dash/AdapterProbe*"
  - "**/android/app/src/test/java/com/openrs/dash/HandOver*"
  - "**/android/app/src/test/java/com/openrs/dash/*DriveMode*"
  - "**/android/app/src/test/java/com/openrs/dash/*ModeAttribution*"
  - "**/android/app/src/test/java/com/openrs/dash/*ModeProvenance*"
  - "**/android/app/src/test/java/com/openrs/dash/*Esc*"
  - "**/android/app/src/test/java/com/openrs/dash/*Connection*"
  - "**/android/app/src/test/java/com/openrs/dash/*Firmware*"
  - "**/android/app/src/test/java/com/openrs/dash/*Transport*"
---
# openRS_ Known Gotchas — Connection, SLCAN link, service ownership, firmware commands

- Firmware drive mode is **hybrid scroll-then-wait** (v1.61+) — pre-calculates scroll count, sends all presses open-loop, waits up to 6s for auto-confirm. Do NOT revert to closed-loop press-and-poll (v1.6 bug) or pure open-loop press counting (v1.5 bug). The Focus RS mode selector GUI requires ~4s of inactivity to auto-confirm — CAN does not update during scrolling
- Firmware REST POST `/api/frs` returns `{"ok":false,"busy":true}` when a drive mode change is in progress — app must handle this
- Android 10+ silently routes new sockets through cellular when WiFi has no internet — `WiFiFirmwareApi` uses `Network.socketFactory` via `ConnectivityManager` to force all traffic on WiFi, and the SLCAN transports (TCP and WebSocket) open their sockets and resolve the host through the adapter network's socket factory and lookup (#324). BLE transport avoids this entirely (not a "network" in Android's routing model)
- **Firmware commands never throw** — every `FirmwareCommandSender` keeps failures inside its `Result` (cancellation
  still propagates): the UI launches (DriveModeDock, MorePage ESC) have no catch, so an escaped exception ends the
  process (`BleCommandSendFailureTest`)
- **SLCAN framing is exact, per connection (#251)** — a frame line's data is exactly DLC × 2 hex digits, or DLC × 2 + 4
  only once `SlcanFraming` has locked the connection to timestamped; never accept "+4" statelessly: a spliced line and a
  one-nibble DLC corruption both look like +4. `SLCAN_INIT` is unchanged (owner decision: no `Z0` at init, the
  per-connection latch only). Well-formed but implausible frames (0x130 = 0xFFFF → 655.35 km/h) are NOT filtered (owner
  decision, `#251 outlier policy`, the one @Ignore'd test): don't invent physical limits
- **Foreground service only while connected** — `MainActivity` only binds `CanDataService`; `startConnection()` promotes
  it (`ForegroundController.promote()`) and `stopConnection()` demotes it. Never call `startForegroundService()` without
  a guaranteed `startForeground()`: the old per-`onCreate` call ANR'd whenever the activity was recreated offline or in
  demo. `promote()` stays in a try/catch because Wi-Fi / Bluetooth callbacks can fire with the app in the background
  (Android 12+ refuses the start). `manualConnectRequired` is process-wide because the idle service is recreated with the
  activity
- **Connection ownership + Cancel (#212)** — every connection transition goes through `CanDataService.link`
  (`LinkOwnership`, process-wide): never add a delayed write from a connection job without `deliver(gen) { }` /
  `owns(gen)` (`LinkOwnershipTest`). Cancel has one rule, `LinkPresentation.offersCancel` (initial attempt, no
  recording); an explicit Cancel persists until Connect / Retry. A system task removal (`onTaskRemoved`) ends only an
  attempt where Cancel is offered and saves nothing (`TaskRemovalTest`, `DriveFinalizeTest`). Device checks only:
  `onActivityBound` releases the task-removal hold (no JVM test reaches it), and whether Back delivers `onTaskRemoved` on
  Android 16 is not established (#212)
- **Transport abort** — a TCP / WebSocket transport assigns its socket before `connect()` so `abort()` ends a blocked
  connect at once (`TransportAbortTest`). The 3 s handshake timeout cannot interrupt a blocking read, so without a cancel
  an adapter that accepts but never answers holds the attempt for the 20 s socket read timeout
- **`UserPrefsStore` outside the activity** — `CanDataService` is START_STICKY: Android can recreate it (and it auto-connects /
  auto-records) with no MainActivity, so its `onCreate` calls `UserPrefsStore.ensureLoaded()` first. Anything service-side
  that reads `UserPrefsStore.prefs` relies on that
- **ESC confirmation (#125)** — the adapter's OK is acceptance only. `EscCommand` confirms only from the last two 0x1C0
  frames both reporting the requested mode, both after the request on the same `connectionEpoch` (`EscRuleCTest`,
  `EscStampThrottleTest`); the 3 s added to the firmware's press plan is unmeasured. OBD merges must not bring back a
  removed ESC frame key (the filter in `ObdMerge`). The firmware saves no ESC mode and restores none at start-up (#628;
  `firmware/tests/run.sh` runs the real `focusrs.c`). Firmware detection ≠ control capability, and the label is not to
  be relied on in a car until #125's hardware acceptance
- **ESC command while the state is unknown (#288)** — `EscCommand` sends nothing (Outcome.Refused) for an unknown target or
  while the car's ESC state is unknown: no 0x1C0 on this connection, or raw 3. GARAGE disables its ESC chips then.
- **Drive-mode confirmation (#252)** — the adapter's OK is "ACCEPTED". `executeDriveModeChange` confirms only from CAN
  observed after the request on the same link generation (0x1B0, and for Sport / Track also documented 0x420 details;
  `DriveModeConfirmTest`, `ModeAttributionReplayTest`, `CommandReconnectTest`).
  - No automatic correction (owner): when the wait ends with the car in another mode, the app asks ("Car is in Track. You
    asked for Sport.") and only the driver's tap sends again. The question comes only at the window's end (Track via
    Sport must not raise it); closing the dock during the wait ends it with no question and no verdict.
  - A confirmation states the car's mode after the request, not that the app caused it: a manual selection looks
    identical, and the mode button's 0x305 bit (byte 4, mask 0x04, seen set for one press only, parked, #304) does not
    show who caused a change, so `driveModeButtonPressed` proves nothing
- **Bluetooth with no saved device (#234; verified on the emulator only)** — one rule, `ConnectionSetup.problem(method,
  address)` (`ConnectionSetupTest`), checked by Settings SAVE and by `beginConnection` before `foreground.promote()` /
  `sessionStart` (no notification, session or retry); prefs are never switched. A new connection entry point checks it
  first too. On a non-debuggable build, release logcat `Session N started` is how to count sessions
- **The SLCAN handshake consumes every line up to and including the first well-formed frame or `OPENRS:` reply (#253)** —
  that line and anything before it never reach the frame loop, the framing latch or a test's expectations. A replay or
  fake-adapter test whose asserted line is the first one sent asserts nothing: send a handshake line first
- **Impossible CAN IDs and 29-bit frames numbered like 11-bit ones are refused (#253)** — `SlcanParser` refuses a `T`
  frame whose ID is ≤ 0x7FF and any ID outside its range (`SlcanParserTest`), and a non-hex data line is refused without
  printing it anywhere, logcat included (`SlcanRefusedLineLogcatTest`). The `T` ≤ 0x7FF rule rests on the firmware
  source; the SLCAN log records parsed IDs, never `t` vs `T`, so a real session's refused frame-line count is the
  hardware check
- **Launch control has one rule (#289)** — the only driver-facing indication is one "LC", `LaunchControl.shown(vs, nowMs)`
  (`LaunchControlRuleTest`), read by every surface on a wall-clock tick (`wallClockMs(500L)`) so it goes away by itself
  when 0x420 stops. Never show a state word ("ON", "ENGAGED", "ARMED"), and never take LC from 0x225 or the 0x731 probe
  DIDs: those are diagnostics only. The cluster check of bit 2 and the window's timing are #289
- **A gap on an open connection fires no timeout; `ReceptionStall` names it (#356)** — frames can keep arriving far below
  the connection's rate while the link stays up, so neither a no-frame timer nor the read timeout ends it.
  `ReceptionStall` names each gap and prevents none (`ReceptionStallTest`, `ReceptionStallReplayTest`). The app takes no
  WifiLock: in the issue's capture the missing frames never entered the TCP stream, the cause is not established, and the
  API 34+ lock is active only with the screen on and the app in front. Thresholds are named constants; their values and
  status belong to #356 and #513
- **The adapter network (#324)** — over Wi-Fi the app connects on a Wi-Fi network on which the adapter gave its own
  protocol reply (`AdapterProbe`); a TCP accept alone never counts (a captive portal or a router at the same address
  accepts too), and never the default network (with mobile data on, Android keeps a validated mobile network as the
  default). The probe's open transport is the first attempt's, and one that no connection takes is closed (`HandOver`;
  `AdapterProbeTest`, `AdapterNetworkFinderTest`, `AdapterNetworkServiceTest`, `HandOverCloseServiceTest`). Probe events
  never name the host. Whether the Pro acknowledges `C\r` with a CR: #324 (the owner's parked check)
- **Teardown comes from the reader (#218)** — `SlcanConnection` swallows write errors, so teardown must come from the
  reader: a transport that has to end the link after a write (BLE closes it on an unacknowledged write) closes its read
  side, the reader sees EOF and `SlcanConnection` reconnects. A transport that only throws from `write` ends nothing
- **No adapter reply text reaches a log (#370)** — the adapter's `/check_status` reply carries its Wi-Fi and MQTT passwords
  in plain text, and Android's `org.json` repeats its whole input in a parse error's message. `WicanApi` logs a failure
  through `logFailure` only: the HTTP status code's three digits, the exception's class name or a fixed over-limit
  line (body or head), never the exception, its message or the status line's reason text; success is the status code 200, never a "200" found in the line
  (`CheckStatusLogRedactionTest`). Any new request to the adapter's web pages follows the same rule: no body, no
  throwable, no `e.message` in `Log`, an event or an export; and it reads at most `WicanApi.MAX_REPLY_CHARS` of a reply's
  body, never a buffer sized by the reply's `Content-Length`, and its status line and headers through `WicanApi.HeadReader`
  (`MAX_HEAD_CHARS` in all), never a bare `readLine()` (`FirmwareHttpBodyLimitTest`, #535).
  - A failed firmware command (`FirmwareApi`, `BleFirmwareApi`) reads one of the fixed `FirmwareFailure` texts, with at
    most the HTTP status code's three digits or a Bluetooth GATT status's digits (the export's diagnostic tail, #263);
    `DriveCommand` and `EscCommand` show and log only that text (`FirmwareFailure.textOf`: any other exception reads
    `FAILED` plus that GATT tail). The WebSocket upgrade fails with
    `Upgrade failed: HTTP <code>` only; `AdapterProbe` reads that prefix (`FirmwareErrorTextTest`, #604)
- **The adapter sends no drive-mode, ESC or button command at start-up (#628, owner decision)** — `frs_boot_apply_task`
  restores no drive mode or ESC and presses no start-stop button (the read-only ABS UDS probe to 0x760 remains); the
  firmware saves neither mode (it erases the `rs_bootmode` / `rs_esc` keys older firmware left) and a press happens only
  on a request from the app. The start-stop kill setting stays, but
  its start-up press waits for a readable start-stop state: a blind press toggles, so it could turn start-stop back on.
  Any new start-up action that sends a frame to the car is the owner's decision first. Host test: `test_boot_apply.c`
  in `firmware/tests/run.sh`; the car check is #628's
