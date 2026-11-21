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

- `0x340` is PCMmsg17 (PCM ambient temp), **NOT** a TPMS broadcast — TPMS only via BCM Mode 22 DIDs 0x2813/14/15/16
- Drive mode needs **both** `0x1B0` (nibble) **AND** `0x420` (bytes 6-7) — `0x1B0` alone cannot distinguish Sport from Track. **Polarity: bit0=0→Sport (0xCC), bit0=1→Track (0xCD).** Button cycle order: Normal→Sport→Track→Drift
- `0x230` (gear) and `0x3C0` (battery voltage) do **NOT** broadcast on this car — the PCM's polled DID 0x0304 is the charging system's target, not the battery's voltage (#365). Gear is estimated from RPM/speed in `VehicleState.derivedGear`
- **Gear detection dual final drive** — MMT6 has final drive 4.063 (gears 1-4) and 2.955 (gears 5-6). Official ratios from 2016 Ford Focus RS Owner's Manual p242. The `GEAR_FACTOR` constant uses 3.82 in the denominator but thresholds are calibrated against the true overall ratios — do not recalibrate with a single final drive
- **0x1A4 ambient temp is UNSIGNED** — `CanDecoder.kt:ID_AMBIENT_TEMP` forces unsigned with `(data[4].toInt() and 0xFF)`
  (signed int8 wrapped values above 32°C to negative). Unsigned ×0.25 with no offset cannot represent sub-zero ambient:
  readings above `AMBIENT_1A4_MAX_C` are rejected (state returned unchanged, raw byte logged) until a sub-zero capture
  pins the encoding (#185). 0x340 byte7 is still signed per RS_HS.dbc — if it wraps in hot weather, the same fix applies.
- **Oil / PTU byte mapping (#229, PR #238)** — `OilPtuMapping.CURRENT` (`0f8-oil-b7-ptu-b1`) is
  saved on every new drive (Room v14 `drives.oilPtuMapping`, nullable, never backfilled). NULL = recorded before the
  correction: never restamp, infer (date / version / thermal record v2 / re-export) or recompute; TRIP and exports say
  "not recorded" / "map n/r" (summary note, chart chip, legend, replay, CSV format line, GPX metadata, profile JSON).
  Trends and comparisons never join the two kinds. Thermal record stays v2 (decode rejects other versions). The Custom
  editor's ranges follow the bytes (OIL −60..195, PTU −50..205); saved limits are never changed on load. AWD 0x1E3F is NOT a
  separate rear reading: in the capture it equalled byte1−50 in 67/67 (labels conflict between sources); 0x1E8A is the rear one
- **Throttle may not broadcast** — CAN 0x076 is absent on some cars (`throttleHasSource` stays false). Anything keyed on throttle (PullTracker, knock snapshot, recorded throttle) must read `throttleOrPedalPct`, never `throttlePct` directly
- **VehicleState slot ceiling** — `copy$default` on the 125-param data class uses 222 of the JVM's 255 parameter slots (#199; it was at the limit). `VehicleStateSlotsTest` fails with fewer than 16 free. Add a field by grouping fields that change together into a nested value (`session: SessionMarks`, `lamps: Lamps`) or by storing it off-state (DiagnosticLogger / genericValues Map). The 0x040 SRS lamp is stored as `lamps.srs` and cleared to unknown at connection start
