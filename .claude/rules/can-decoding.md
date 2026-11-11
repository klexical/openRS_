---
paths:
  - "**/android/app/src/main/java/com/openrs/dash/can/CanDecoder.kt"
  - "**/android/app/src/main/java/com/openrs/dash/data/VehicleState.kt"
  - "**/android/app/src/main/java/com/openrs/dash/demo/DemoSimulator.kt"
  - "**/com/openrs/dash/data/PullTracker.kt"
  - "**/com/openrs/dash/data/Knock*.kt"
  - "**/com/openrs/dash/data/Drive*.kt"
  - "**/com/openrs/dash/**/OilPtuMapping*"
  - "**/com/openrs/dash/service/DriveRecorder.kt"
  - "**/android/app/src/test/java/com/openrs/dash/*CanDecoder*"
  - "**/android/app/src/test/java/com/openrs/dash/*VehicleState*"
  - "**/android/app/src/test/resources/dbc/**"
---
# openRS_ Known Gotchas — CAN decoding and VehicleState

Moved verbatim from the tracked `CLAUDE.md` (section Known Gotchas) on 2026-11-06. Add a new gotcha of this area at the end; never reword an existing one here.

- `0x340` is PCMmsg17 (PCM ambient temp), **NOT** a TPMS broadcast — TPMS only via BCM Mode 22 DIDs 0x2813/14/15/16
- Drive mode needs **both** `0x1B0` (nibble) **AND** `0x420` (bytes 6-7) — `0x1B0` alone cannot distinguish Sport from Track. **Polarity: bit0=0→Sport (0xCC), bit0=1→Track (0xCD).** Button cycle order: Normal→Sport→Track→Drift
- Skip `0x1B0` frames where byte4 ≠ 0 (button-event transition frames; steady-state has byte4 == 0)
- `0x230` (gear) and `0x3C0` (battery voltage) do **NOT** broadcast on this car — battery from PCM DID 0x0304. Gear is estimated from RPM/speed in `VehicleState.derivedGear`
- **Gear detection dual final drive** — MMT6 has final drive 4.063 (gears 1-4) and 2.955 (gears 5-6). Official ratios from 2016 Ford Focus RS Owner's Manual p242. The `GEAR_FACTOR` constant uses 3.82 in the denominator but thresholds are calibrated against the true overall ratios — do not recalibrate with a single final drive
- **Drive mode cold-start gate** — `has420Arrived` flag in CanDecoder prevents Sport/Track resolution until the first `0x420` frame is received. Without this gate, `0x1B0` nibble=1 resolves against stale `modeDetail420` default during the 0-600ms blind window after connection, causing wrong mode flashes. The drive mode confirmation loop in MorePage adds a 2s post-command settling delay, a 15s timeout, and auto-correction if the car lands on the wrong mode (sends a corrective command automatically).
- **0x1A4 ambient temp is UNSIGNED** — DigiCluster v1.0.46 confirmed on-car that raw 0x82 (130 unsigned) × 0.25 = 32.5°C (90.5°F). Prior spec said signed int8, which wrapped values above 32°C to negative on hot days. Our decoder at `CanDecoder.kt:ID_AMBIENT_TEMP` uses `(data[4].toInt() and 0xFF)` to force unsigned. **But unsigned ×0.25 with no offset cannot represent sub-zero ambient** — readings above `AMBIENT_1A4_MAX_C` (55°C) are rejected (state returned unchanged, raw byte logged) until a sub-zero capture pins the true encoding ([#185](https://github.com/klexical/openRS_/issues/185)). 0x340 PCMmsg17 byte7 is still signed per RS_HS.dbc — if we see Phoenix-summer wrap on that signal in field testing, same fix applies.
- **0x435 airbag crosstalk** — DigiCluster v1.0.73 found that the per-zone airbag status bits on 0x435 share bits with passenger-occupancy detection and false-triggered the SRS lamp in-vehicle. We decode 0x040 bits 7:6 of byte2 only (`0xC0 == true` = lamp on), and explicitly keep 0x435 OUT of `KNOWN_IDS`. Do not add 0x435 decoding without a verified fault vs occupancy capture.
- **Oil / PTU byte mapping (#229, in #238 `2033fda`)** — `OilPtuMapping.CURRENT` (`0f8-oil-b7-ptu-b1`) is
  saved on every new drive (Room v14 `drives.oilPtuMapping`, nullable, never backfilled). NULL = recorded before the
  correction: never restamp, infer (date / version / thermal record v2 / re-export) or recompute; TRIP and exports say
  "not recorded" / "map n/r" (summary note, chart chip, legend, replay, CSV format line, GPX metadata, profile JSON).
  Trends and comparisons never join the two kinds. Thermal record stays v2 (decode rejects other versions). The Custom
  editor's ranges follow the bytes (OIL −60..195, PTU −50..205); saved limits are never changed on load. AWD 0x1E3F is NOT a
  separate rear reading: in the capture it equalled byte1−50 in 67/67 (labels conflict between sources); 0x1E8A is the rear one
- **Throttle may not broadcast** — CAN 0x076 is absent on some cars (`throttleHasSource` stays false). Anything keyed on throttle (PullTracker, knock snapshot, recorded throttle) must read `throttleOrPedalPct`, never `throttlePct` directly
- **VehicleState slot ceiling** — `copy$default` on the 125-param data class uses 222 of the JVM's 255 parameter slots (#199; it was at the limit). `VehicleStateSlotsTest` fails with fewer than 16 free. Add a field by grouping fields that change together into a nested value (`session: SessionMarks`, `lamps: Lamps`) or by storing it off-state (DiagnosticLogger / genericValues Map). The 0x040 SRS lamp is stored as `lamps.srs`
- **ESC names and raw 3 (#288)** — `EscStatus` labels are Ford's Normal / Sport / Off (2018 RS supplement); the raw 0x1C0
  value → name mapping is CAN-mapped only (RS_HS.dbc VAL_ 448, #125). Raw 3 is `UNRECOGNIZED` (reads "Unknown"; diagnostics
  "unrecognized (raw 3)"; the name "Launch" rests only on the community DBC); `UNKNOWN` is the default until this connection's
  first 0x1C0 frame (CanDataService resets it at connection start). Never show "Launch" for ESC, never infer launch control
  from ESC, and never map an unknown state to a firmware escMode (`firmwareMode` is null for both).
