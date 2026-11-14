---
paths:
  - "**/com/openrs/dash/can/Obd*.kt"
  - "**/com/openrs/dash/can/PcmPollSchedule.kt"
  - "**/com/openrs/dash/can/PidRegistry.kt"
  - "**/com/openrs/dash/can/*Ledger*"
  - "**/com/openrs/dash/can/*Nrc*"
  - "**/com/openrs/dash/can/CanDecoder.kt"
  - "**/com/openrs/dash/can/SlcanConnection.kt"
  - "**/com/openrs/dash/service/CanDataService.kt"
  - "**/com/openrs/dash/data/ObdMerge.kt"
  - "**/com/openrs/dash/data/FreshnessGate.kt"
  - "**/com/openrs/dash/**/PolledFreshness*"
  - "**/com/openrs/dash/ui/PowerPage.kt"
  - "**/com/openrs/dash/ui/CustomDashPage.kt"
  - "**/android/app/src/test/java/com/openrs/dash/*Obd*"
  - "**/android/app/src/test/java/com/openrs/dash/*Pid*"
  - "**/android/app/src/test/java/com/openrs/dash/*Poll*"
  - "**/android/app/src/test/java/com/openrs/dash/*Nrc*"
  - "**/android/app/src/main/assets/pids/**"
  - "**/android/scripts/gen_forscan_catalog.py"
  - "**/com/openrs/dash/data/VehicleState.kt"
  - "**/com/openrs/dash/*ModeProvenance*"
---
# openRS_ Known Gotchas — OBD Mode 22 polling, decoders and freshness

- TPMS PID `0x280B` is multi-frame ISO-TP — send `BCM_FLOW_CONTROL` after receiving First Frame
- Extended session (`0x10 0x03`) required before: RDU_STATUS (AWD 0x703), PDC (PSCM 0x730), FENG (0x727), RSProt (0x731)
- `fengTimedOut` / `rsprotTimedOut` = true after 3 failed probe cycles — do not suggest retrying indefinitely
- **Freshness stamps (`fieldLastUpdateMs`)** — Mode 22 decoders stamp via `markFresh()`; `ObdMerge.merge()` (data/ObdMerge.kt, called by `CanDataService.mergeObdState()`) MUST merge them with
  `VehicleState.mergeStamps` (per-key max) or the UI reads every OBD field as STALE. CAN-sourced THERMAL fields are stamped by `CanDecoder.stampFresh()` throttled to 1 s per ID — never `markFresh()` per CAN frame (map copy at hundreds of fps). A decoder returning the **same** `state` instance means "recognized, nothing changed": `processCanFrame` logs it and skips the copy/emit (0x040, 0x300, rejected 0x1A4). `ObdMerge` keeps a field through its "not received" sentinel; a field whose
  valid range includes its default (REQ L / R: 0 Nm) is merged by the reply's own stamp (`ObdMerge.carried`), never by
  value — a `> 0` guard keeps the old value under a fresh stamp (#226). Battery voltage (PCM 0x0304) is merged the same way (#223): a decoded 0 or an older
  snapshot can't freshen or overwrite it; DRIVE BATT reads STALE past `PolledFreshness` 2 × 40 s and "— —" for a reported 0
  (GARAGE → DIAG keeps the raw value, "0.00 V" — owner decision: unavailable on DRIVE, raw on DIAG). 0x0304 is the PCM's reported value, not established as
  measured battery voltage
- **KR sign convention** — `ignCorrCyl1..4 = raw / −512` → retard is **POSITIVE**. Knock capture uses `≥ 0.1°`, the header counter and POWER KR cells `> 1°`. Cyl 2–4 DIDs (0x03ED–EF) are unverified — 0x03ED/0x03EE NRC 0x31 on two RS PCMs (EU tuned #182 + user's car), so not tune-specific; 0x03EF answers but read 0 for a whole drive. NrcTracker suppresses 2/3; polling left as is (owner decision)
- **Mode 01 equivalents live in Mode 22 `22F4xx`** — load (F404), timing (F40E), O2 V (F415), STFT/LTFT (F406/07), λ (F434/F444). `ObdPids.kt` (the Mode 01 path) was deleted in a7b01d0 and silently took the load/timing/AFR decoders with it — if a VehicleState field has no writer in ObdResponseParser/CanDecoder, it is dead. HP fuel is **0xF423** (DI gauge, ×10 kPa); 0xF422 is the port-injection PID and is rejected. LP fuel = **0x0548** (`raw × 0.5` kPa)
- **PCM "no data" replies** — 0x0461 charge air answers `0x0000` on two RS PCMs; the decoder treats that as no data (stays −99). BCM 0x4028 battery SOC read raw 5 (= 5 %) at 14.7 V all session on the user's car — unverified, compare with FORScan BAT_ST_CHRG before trusting it
- **Adding a polled DID** — put the query in `ObdConstants` AND a `markFresh` decoder in `ObdResponseParser`; the coverage-guard tests in `ObdResponseParserTest` fail otherwise. Removing a decoder fails them too (that's the point — #198). Multi-frame ISO-TP DIDs (BCM 0x280B) are excluded explicitly
- **Polled values go stale (#217)** — POWER and Custom Dashboard use `PolledFreshness` (fast group 2 s, others 40 s, stale past
  2×): POWER dims with STALE Ns, the dashboard shows "— —". NRC suppression (#216) needs 2 attributed NRCs (attributed only
  when unambiguous — see SlcanConnection; a timeout or the 32-entry queue limit is a resource limit, never proof of ownership,
  so after one lost reply NRCs stay unattributed for that connection), recovers on a positive reply and re-probes after 60 s;
  the PCM poller always suspends. While Request capture is on, suppression skips no DID and no rejection counts toward it
  (#209).
- **POWER cells are gated per field** — engine cells show only when `rpm > FAST_POLL_MIN_RPM` AND `fieldLastUpdateMs[tag] != null`; never gate on another field's value (the old `calcLoad > 0` gate hid everything once load lost its decoder)
- **An OBD merge can resurrect a stamp key the CAN side removed (#252)** — `ObdMerge.merge()` merges
  `fieldLastUpdateMs` by per-key max from the reply's SNAPSHOT of VehicleState; a key that CAN-side code deleted after the snapshot
  was taken comes back from the merge. Any key that must be CAN-only (today: every `driveMode*` tag, dropped by `ObdMerge`) needs
  the same explicit drop; `ModeProvenanceMergeTest` guards the drive-mode set. When you remove a stamp key from the state, add it
  to the drop set or a merge can bring it back.
- **ISO-TP replies are whole or nothing (#253)** — `IsoTpBuffer` and `assembleIsotpResponse` refuse a truncated SF /
  FF / CF and an FF that claims fewer than `MIN_FF_LENGTH` (8) bytes (no flow control is written for a refused FF; the "fewer bytes
  fit a single frame" rule cites ISO 15765-2 from memory — the standard is not in the repo, so it is marked unverified); a new FF
  discards a partial reply; a sequence break or the deadline returns nothing, never partial bytes; a partial reply has no timer and
  stays open until the next FF or SF. The PCM frame branch reads the PCI first, a PCM first-frame positive resolves its ledger
  request, and **a polled DID that answers with an FF releases the poller at the FF instead of after the 300 ms pacing window**
  (poll pacing, not command timing; hardware-unverified; pinned by `IsoTpCoverageTest`; tracked on #216).
  Not handled (#253): no N_Cr timer (two lost lines can join reply B to reply A's head), a restarted reply waiting for FC ends as TIMEOUT, no
  counter for dropped ISO-TP replies.
- **Request capture (#209 Step B, the owner's decisions of 2026-11-14)** — `can/RequestCapture.kt` (`RequestCapture.shared`),
  the GARAGE → DIAG switch beside the DID prober (`ui/RequestCaptureRow.kt`, the owner's text as `REQUEST_CAPTURE_TEXT`).
  - **While on, PCM only:** the poller sends its next request only after the previous one is answered or
    `RequestCapture.WAIT_MS` (2,000 ms) has passed, after a possibly delivered write too (the #333 pacing is the mode-off
    rule). An answer is the DID's positive reply or any final `7F 22` rejection, even one the ledger cannot charge (after a
    lapse every rejection is unattributable); `7F 22 78` is not. The wait signal (`CaptureWait`) is separate from the
    ledger, whose attribution is unchanged.
  - **One gate:** `captureGate` is held by the poller and by a PCM DID probe from the write to the answer or the set time,
    so neither writes while the other's request waits (a "not during a probe" check races). A probe takes it inside
    `_dtcMutex`; the poller never takes `_dtcMutex`. A PCM probe in the mode waits `max(its timeout, WAIT_MS)`. Every
    turn runs `awaitCaptureTurn` inside the gate before the write.
  - **Only its own answer ends a wait:** a positive reply for another DID (a late one) does not.
  - **The late-answer pause (C1, owner 2026-11-14):** after a capture request (poll or PCM probe) gets no answer in time,
    nothing goes to the PCM until `RequestCapture.LATE_WINDOW_MS` (5,000 ms) after that request's write, so a late
    answer lands before the next request and is that request's; the next request starts with nothing outstanding. 5,000 ms
    is a chosen value: the usual default for a response-pending wait (P2*) in diagnostic libraries; no standard was read.
    Counted in the export: any final answer inside the pause (`lateInWindow`), and a positive reply after it for a DID
    that timed out and is not the one being waited for (`lateAfterWindow`). A rejection after the pause names no DID: it
    is taken as the current request's (Reading 2), so an answer later than 5,000 ms can still end the next wait early.
  - **At switch-on** the first turn waits for any PCM request sent before the capture began (still outstanding in the
    ledger), up to `WAIT_MS` after its write; the capture's own requests are not waited for again (`RequestCapture.switchOns`
    marks a new capture).
  - **DTC operations (C2, owner 2026-11-14):** while the mode is on, the DTC scan, clear and both freeze-frame reads hold
    `captureGate` for their whole operation (`dtcOperation`, inside `_dtcMutex`), after their turn, so polling pauses for
    their length. With the mode off they start at once, as before.
  - **NRC suppression is off:** `shouldPoll` is not asked (it changes re-probe state) and `recordNrc` is not called, so no
    rejection counts toward suppression; it resumes from where it was after the mode. `recordPositive` still runs (a
    positive reply truly clears a suppression).
  - **No answer in time** is a `# CAPTURE-TIMEOUT (t) can0 ID#DATA no answer within 2000 ms` line (`response pending, no
    final answer within …` after a `7F 22 78`), from the poller and from a PCM probe, stamped at the wait's end, so a later
    answer reads as late. It does not start with `# TX`, so no reader picks it as a request.
  - **Off at every link up and down,** automatic reconnects included, and refused with no link or in demo mode (DIAG's
    snackbar says so). Each link has a token (`linkUp`); `linkDown(token, …)` acts only for the current link, so an old
    connection's late end (a manual reconnect cancels it without waiting) leaves the new link's switch alone. The service's
    `teardownJob` ends the capture itself before `sessionEnd`, so the event lands in the session it belongs to. Every
    change is recorded inside the switch's lock. A capture never ends silently: a `REQUEST_CAPTURE` session event says why,
    the switch reads off, and the export's REQUEST CAPTURE section / `requestCapture` JSON (spans with on, off and reason;
    timeouts; additive) say when (`RequestCaptureTest`, `RequestCaptureServiceTest`, `RequestCaptureRowTest`)
