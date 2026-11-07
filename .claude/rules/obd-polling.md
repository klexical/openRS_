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

Moved verbatim from the tracked `CLAUDE.md` (section Known Gotchas) on 2026-11-06. Add a new gotcha of this area at the end; never reword an existing one here.

- TPMS PID `0x280B` is multi-frame ISO-TP — send `BCM_FLOW_CONTROL` after receiving First Frame
- Extended session (`0x10 0x03`) required before: RDU_STATUS (AWD 0x703), PDC (PSCM 0x730), FENG (0x727), RSProt (0x731)
- `fengTimedOut` / `rsprotTimedOut` = true after 3 failed probe cycles — do not suggest retrying indefinitely
- **Freshness stamps (`fieldLastUpdateMs`)** — Mode 22 decoders stamp via `markFresh()`; `ObdMerge.merge()` (data/ObdMerge.kt, called by `CanDataService.mergeObdState()`) MUST merge them with
  `VehicleState.mergeStamps` (per-key max) or the UI reads every OBD field as STALE (pre-rc.5 bug). CAN-sourced THERMAL fields are stamped by `CanDecoder.stampFresh()` throttled to 1 s per ID — never `markFresh()` per CAN frame (map copy at hundreds of fps). A decoder returning the **same** `state` instance means "recognized, nothing changed": `processCanFrame` logs it and skips the copy/emit (0x040, 0x300, rejected 0x1A4). `ObdMerge` keeps a field through its "not received" sentinel; a field whose
  valid range includes its default (REQ L / R: 0 Nm) is merged by the reply's own stamp (`ObdMerge.carried`), never by
  value — a `> 0` guard keeps the old value under a fresh stamp (#226). Battery voltage (PCM 0x0304) is merged the same way since rc.8 (#223): a decoded 0 or an older
  snapshot can't freshen or overwrite it; DRIVE BATT reads STALE past `PolledFreshness` 2 × 40 s and "— —" for a reported 0
  (GARAGE → DIAG keeps the raw value, "0.00 V" — decided 2026-11-06: unavailable on DRIVE, raw on DIAG). 0x0304 is the PCM's reported value, not established as
  measured battery voltage
- **KR sign convention** — `ignCorrCyl1..4 = raw / −512` → retard is **POSITIVE**. Knock capture uses `≥ 0.1°`, the header counter and POWER KR cells `> 1°`. Cyl 2–4 DIDs (0x03ED–EF) are unverified — 0x03ED/0x03EE NRC 0x31 on two RS PCMs (EU tuned #182 + user's car), so not tune-specific; 0x03EF answers but read 0 for a whole drive. NrcTracker suppresses 2/3; polling left as is (user decision 2026-10-22)
- **Mode 01 equivalents live in Mode 22 `22F4xx`** — load (F404), timing (F40E), O2 V (F415), STFT/LTFT (F406/07), λ (F434/F444). `ObdPids.kt` (the Mode 01 path) was deleted in a7b01d0 and silently took the load/timing/AFR decoders with it — if a VehicleState field has no writer in ObdResponseParser/CanDecoder, it is dead. HP fuel is **0xF423** (DI gauge, ×10 kPa); 0xF422 is the port-injection PID and is rejected. LP fuel = **0x0548** (`raw × 0.5` kPa)
- **PCM "no data" replies** — 0x0461 charge air answers `0x0000` on two RS PCMs; the decoder treats that as no data (stays −99). BCM 0x4028 battery SOC read raw 5 (= 5 %) at 14.7 V all session on the user's car — unverified, compare with FORScan BAT_ST_CHRG before trusting it
- **Adding a polled DID** — put the query in `ObdConstants` AND a `markFresh` decoder in `ObdResponseParser`; the coverage-guard tests in `ObdResponseParserTest` fail otherwise. Removing a decoder fails them too (that's the point — #198). Multi-frame ISO-TP DIDs (BCM 0x280B) are excluded explicitly
- **Polled values go stale (#217)** — POWER and Custom Dashboard use `PolledFreshness` (fast group 2 s, others 40 s, stale past
  2×): POWER dims with STALE Ns, the dashboard shows "— —". NRC suppression (#216) needs 2 attributed NRCs (attributed only
  when unambiguous — see SlcanConnection; a timeout or the 32-entry queue limit is a resource limit, never proof of ownership,
  so after one lost reply NRCs stay unattributed for that connection), recovers on a positive reply and re-probes after 60 s (provisional settings);
  the PCM poller always suspends. Emulator capture current → STALE → recovered: fake_ecu_v2.py `--stale DID:A:S`.
- **POWER cells are gated per field** — engine cells show only when `rpm > FAST_POLL_MIN_RPM` AND `fieldLastUpdateMs[tag] != null`; never gate on another field's value (the old `calcLoad > 0` gate hid everything once load lost its decoder)
- **An OBD merge can resurrect a stamp key the CAN side removed (#252 review, fix `134bee3`)** — `ObdMerge.merge()` merges
  `fieldLastUpdateMs` by per-key max from the reply's SNAPSHOT of VehicleState; a key that CAN-side code deleted after the snapshot
  was taken comes back from the merge. Any key that must be CAN-only (today: every `driveMode*` tag, dropped by `ObdMerge`) needs
  the same explicit drop; `ModeProvenanceMergeTest` guards the drive-mode set. When you remove a stamp key from the state, add it
  to the drop set or a merge can bring it back.
- **ISO-TP replies are whole or nothing (#253, rc.8 Build 39)** — `IsoTpBuffer` and `assembleIsotpResponse` refuse a truncated SF /
  FF / CF and an FF that claims fewer than `MIN_FF_LENGTH` (8) bytes (no flow control is written for a refused FF; the "fewer bytes
  fit a single frame" rule cites ISO 15765-2 from memory — the standard is not in the repo, so it is marked unverified); a new FF
  discards a partial reply; a sequence break or the deadline returns nothing, never partial bytes; a partial reply has no timer and
  stays open until the next FF or SF. The PCM frame branch reads the PCI first, a PCM first-frame positive resolves its ledger
  request, and **a polled DID that answers with an FF releases the poller at the FF instead of after the 300 ms pacing window**
  (poll pacing, not command timing; accepted provisionally by the integrator, pinned by `IsoTpCoverageTest`, owner may reject).
  Open: no N_Cr timer (two lost lines can join reply B to reply A's head), a restarted reply waiting for FC ends as TIMEOUT, no
  counter for dropped ISO-TP replies.
