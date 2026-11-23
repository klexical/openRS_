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
- **Freshness stamps (`fieldLastUpdateMs`)** — Mode 22 decoders stamp via `markFresh()`; `ObdMerge.merge()` MUST merge
  stamps with `VehicleState.mergeStamps` (per-key max) or the UI reads every OBD field as STALE. CAN-sourced THERMAL
  fields are stamped by `CanDecoder.stampFresh()`, throttled to 1 s per ID — never `markFresh()` per CAN frame (a map
  copy at hundreds of fps). A decoder returning the **same** `state` instance means "recognized, nothing changed".
  `ObdMerge` keeps a field through its "not received" sentinel; a field whose valid range includes its default (REQ L / R:
  0 Nm, PCM 0x0304) is merged by the reply's own stamp (`ObdMerge.carried`), never by value (#226, #223).
  - PCM 0x0304 is the charging system's target, not the battery's voltage (#365; with the engine off, on one car, it
    equalled BCM 0x411D and sat below a meter on the battery, #223). DRIVE and the Custom Dashboard label it CHG and show
    it only by `ChargeTarget.showable` (engine running, from a reply decoded while it ran), "— —" for a reported 0; GARAGE →
    DIAG keeps the raw value (owner decision: unavailable on DRIVE, raw on DIAG); never a warning level
    (`BatteryChargingTargetTest`)
- **KR sign convention** — `ignCorrCyl1..4 = raw / −512` → retard is **POSITIVE**. Knock capture uses `≥ 0.1°`, the header counter and POWER KR cells `> 1°`. Cyl 2–4 DIDs (0x03ED–EF) are unverified — 0x03ED/0x03EE NRC 0x31 on two RS PCMs (#182 and the owner's car), so not tune-specific; 0x03EF answers but read 0 for a whole drive. NrcTracker suppresses 2/3; polling left as is (owner decision)
- **Mode 01 equivalents live in Mode 22 `22F4xx`** — load (F404), timing (F40E), O2 V (F415), STFT/LTFT (F406/07), λ
  (F434/F444). If a VehicleState field has no writer in ObdResponseParser / CanDecoder, it is dead (deleting the Mode 01
  path once took load, timing and AFR with it). HP fuel is **0xF423** (DI gauge, ×10 kPa); 0xF422 is the port-injection
  PID and is rejected. LP fuel = **0x0548** (`raw × 0.5` kPa)
- **PCM "no data" replies** — 0x0461 charge air answers `0x0000` on two RS PCMs; the decoder treats that as no data (stays −99). BCM 0x4028 battery SOC read raw 5 (= 5 %) at 14.7 V all session on the user's car — unverified, compare with FORScan BAT_ST_CHRG before trusting it
- **Adding a polled DID** — put the query in `ObdConstants` AND a `markFresh` decoder in `ObdResponseParser`; the coverage-guard tests in `ObdResponseParserTest` fail otherwise. Removing a decoder fails them too (that's the point — #198). Multi-frame ISO-TP DIDs (BCM 0x280B) are excluded explicitly
- **Polled values go stale (#217), NRC ownership (#216)** — POWER and the Custom Dashboard read `PolledFreshness`, never a
  held value. An NRC is charged to a DID only when unambiguous: a timeout or the queue limit is a resource limit, never
  proof of ownership, so after one lost reply NRCs stay unattributed for that connection (`NrcTracker`, the PCM request
  ledger). While Request capture is on, suppression skips no DID and no rejection counts toward it (#209).
- **POWER cells are gated per field** — engine cells show only when `rpm > FAST_POLL_MIN_RPM` AND `fieldLastUpdateMs[tag] != null`; never gate on another field's value (the old `calcLoad > 0` gate hid everything once load lost its decoder)
- **An OBD merge can resurrect a stamp key the CAN side removed (#252)** — `ObdMerge.merge()` merges stamps from the
  reply's SNAPSHOT of VehicleState, so a key CAN-side code deleted after the snapshot comes back. A key that must be
  CAN-only needs an explicit drop in `ObdMerge` (today the drive-mode, ESC-frame and launch-control keys;
  `ModeProvenanceMergeTest`): when you remove a stamp key from the state, add it to the drop set.
- **ISO-TP replies are whole or nothing (#253)** — `IsoTpBuffer` and `assembleIsotpResponse` refuse a truncated or
  short reply and never return partial bytes (`IsoTpBufferTest`, `IsoTpCoverageTest`). Unverified: the "fewer bytes fit a
  single frame" rule cites ISO 15765-2 from memory (the standard is not in the repo), and a polled DID that answers with
  an FF releases the poller at the FF, unchecked on hardware (#216). Not handled (#253): no N_Cr timer (two lost lines
  can join reply B to reply A's head), a restarted reply waiting for FC ends as TIMEOUT, no counter for dropped ISO-TP
  replies.
- **Request capture (#209 Step B, the owner's decisions)** — `can/RequestCapture.kt`, the GARAGE → DIAG switch beside the
  DID prober (the owner's text as `REQUEST_CAPTURE_TEXT`). While on, PCM only:
  - the poller sends its next request only after the previous one's own answer (positive reply or final `7F 22`, not
    `78`) or `WAIT_MS` (2,000 ms), after a possibly delivered write too; one gate (`captureGate`) is shared with PCM DID
    probes, so neither writes while the other's request waits;
  - **C1:** after a request gets no answer in time, nothing goes to the PCM until `LATE_WINDOW_MS` (5,000 ms, a chosen
    value: no standard was read) after its write, so a late answer is that request's; late answers are counted;
  - **C2:** the DTC scan, clear and freeze-frame reads hold the gate for their whole operation, so polling pauses;
  - NRC suppression is off and resumes where it was afterwards;
  - it is off at every link up and down and refused with no link or in demo; it never ends silently: a
    `REQUEST_CAPTURE` session event says why, and the export's REQUEST CAPTURE section says when (`RequestCaptureTest`,
    `RequestCaptureServiceTest`, `RequestCaptureRowTest`)
