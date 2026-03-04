# OBD PID Reference — Ford Focus RS MK3

Complete reference for all OBD-II parameters used by openRS.

## ECU Address Map

| ECU | Name | Request Header | Response Header | Bus |
|-----|------|----------------|-----------------|-----|
| PCM | Powertrain Control Module | 0x7E0 | 0x7E8 | HS-CAN 500k |
| BCM | Body Control Module | 0x726 | 0x72E | HS-CAN 500k |
| Broadcast | All ECUs | 0x7DF | varies | HS-CAN 500k |

## Mode 1 — Standard OBD-II (Header: 0x7DF)

| PID | Name | Request | Bytes | Formula | Unit | Priority |
|-----|------|---------|-------|---------|------|----------|
| 0x04 | Calculated Load | `0104` | 1 | A × 100 / 255 | % | 1 |
| 0x06 | Short Fuel Trim | `0106` | 1 | A / 1.28 − 100 | % | 1 |
| 0x07 | Long Fuel Trim | `0107` | 1 | A / 1.28 − 100 | % | 2 |
| 0x0E | Timing Advance | `010E` | 1 | A / 2 − 64 | ° | 2 |
| 0x15 | O2 Voltage B1S2 | `0115` | 1 | A / 200 | V | 3 |
| 0x22 | Fuel Rail Pressure | `0122` | 2 | (A×256+B) × 0.079 | kPa | 2 |
| 0x24 | AFR Sensor 1 | `0124` | 2 | (A×256+B) / 32768 | λ | 3 |
| 0x33 | Barometric Pressure | `0133` | 1 | A | kPa | 3 |
| 0x44 | Commanded AFR | `0144` | 2 | (A×256+B) / 32768 | λ | 2 |

## Mode 22 — Ford Enhanced via PCM (Header: 0x7E0)

| PID | Name | Request | Bytes | Formula | Unit | Priority | Source |
|-----|------|---------|-------|---------|------|----------|--------|
| 0xF434 | AFR Actual | `22F434` | 2 | (A×256+B) × 0.0004486 | :1 | 1 | DigiCluster |
| 0xF444 | AFR Desired | `22F444` | 1 | A × 0.1144 | :1 | 2 | DigiCluster |
| 0x093C | ETC Angle Actual | `22093C` | 2 | (A×256+B) / 512 | ° | 1 | DigiCluster |
| 0x091A | ETC Angle Desired | `22091A` | 2 | (A×256+B) / 512 | ° | 2 | DigiCluster |
| 0x033E | TIP Actual | `22033E` | 2 | (A×256+B) / 903.81 | kPa | 1 | DigiCluster |
| 0x0466 | TIP Desired | `220466` | 2 | (A×256+B) / 903.81 | kPa | 2 | DigiCluster |
| 0x0462 | WGDC Desired | `220462` | 2 | (A×256+B) / 327.68 | % | 2 | DigiCluster |
| 0x0318 | VCT Intake Angle | `220318` | 2 | (signed(A)×256+B) / 16 | ° | 3 | DigiCluster |
| 0x0319 | VCT Exhaust Angle | `220319` | 2 | (signed(A)×256+B) / 16 | ° | 3 | DigiCluster |
| 0x03EC | Ign Correction Cyl1 | `2203EC` | 2 | (signed(A)×256+B) / −512 | ° | 2 | DigiCluster |
| 0x054B | Oil Life | `22054B` | 1 | A | % | 6 | DigiCluster |
| 0x0543 | Octane Adjust Ratio | `220543` | 1 | A / 255 | ratio | 3 | DigiCluster |
| 0x0461 | Charge Air Temp | `220461` | 2 | (signed(A)×256+B) / 64 | °C | 2 | DigiCluster |
| 0xF43C | Catalytic Temp | `22F43C` | 2 | (A×256+B) / 10 − 40 | °C | 3 | DigiCluster |

## TPMS — Passive CAN (preferred method, v1.1.0+)

> **Architecture change in v1.1.0:** TPMS data is now decoded from passive CAN frame `0x340`, not OBD Mode 22. The BCM broadcasts this frame on MS-CAN; the Gateway Module (GWM) bridges it to HS-CAN automatically. No OBD queries, no BCM header switching.

| CAN ID | Parameters | Decode |
|--------|-----------|--------|
| 0x340 | Tire pressure LF, RF, LR, RR | bytes 2-5 direct PSI (unsigned) |

If all four bytes are zero, the TPMS ECU has not sent data yet (engine just started or car is off). The decoder ignores all-zero frames.

## Mode 22 — TPMS via BCM (Header: 0x726) — legacy reference

> These OBD queries are no longer used by the app. Documented for reference / future use.

| PID | Name | Request | Bytes | Formula | Unit | Status |
|-----|------|---------|-------|---------|------|--------|
| 0x2813 | Tire Pressure LF | `222813` | 2 | (A×256+B) / 2.9 × 0.145038 | PSI | Legacy |
| 0x2814 | Tire Pressure RF | `222814` | 2 | (A×256+B) / 2.9 × 0.145038 | PSI | Legacy |
| 0x2815 | Tire Pressure RR | `222815` | 2 | (A×256+B) / 2.9 × 0.145038 | PSI | Legacy |
| 0x2816 | Tire Pressure LR | `222816` | 2 | (A×256+B) / 2.9 × 0.145038 | PSI | Legacy |
| 0x2823 | Tire Temp LF | `222823` | 1 | A − 40 | °C | ⚠️ Unverified |
| 0x2824 | Tire Temp RF | `222824` | 1 | A − 40 | °C | ⚠️ Unverified |
| 0x2825 | Tire Temp RR | `222825` | 1 | A − 40 | °C | ⚠️ Unverified |
| 0x2826 | Tire Temp LR | `222826` | 1 | A − 40 | °C | ⚠️ Unverified |

## CAN Frame IDs (HS-CAN 500 kbps) — DigiCluster verified

All formulas re-validated against DigiCluster `can0_hs.json` and `can1_ms.json`.

| ID | Description | Decode Formula | Source |
|----|-------------|----------------|--------|
| 0x080 | Throttle %, accel pedal % | bytes 2-3 / 2.55 | DigiCluster HS-CAN |
| 0x090 | RPM, baro pressure | RPM: `((B4&0x0F)<<8\|B5)×2`; baro: `B2×0.5 kPa` | DigiCluster HS-CAN |
| 0x0C8 | E-brake, ESC | e-brake: `B3&0x40` | DigiCluster HS-CAN |
| 0x0F8 | Boost kPa, oil temp | boost: B5 absolute kPa; oil: `B7−60 °C` | DigiCluster HS-CAN |
| 0x130 | Speed kph | `word(B6-B7)×0.01` | DigiCluster HS-CAN |
| 0x160 | Longitudinal G | `((B6&0x03)<<8\|B7)×0.00390625−2.0` | DigiCluster HS-CAN |
| 0x180 | Lateral G | `((B2&0x03)<<8\|B3)×0.00390625−2.0` | DigiCluster HS-CAN |
| 0x1A4 | Ambient temp | `B4 signed × 0.25 °C` | DigiCluster MS-CAN bridged |
| 0x1B0 | Drive mode | `(B6>>4)&0x0F` — 0=Normal,1=Sport,2=Track,3=Drift | DigiCluster HS-CAN |
| 0x215 | 4× wheel speeds, gear | wheel: `word×0.01 kph`; gear: B7 | DigiCluster HS-CAN |
| 0x2C0 | AWD L/R torque, RDU temp | 12-bit words scaled; RDU: `B6−40 °C` | DigiCluster HS-CAN |
| 0x2C2 | PTU temp | `B5−40 °C` | DigiCluster HS-CAN |
| 0x2F0 | Coolant temp | `B5−60 °C` | DigiCluster HS-CAN |
| 0x340 | TPMS LF/RF/LR/RR | bytes 2-5 direct PSI | DigiCluster MS-CAN bridged |
| 0x34A | Fuel level % | `B2×100/255` | DigiCluster HS-CAN |
| 0x3C0 | Battery voltage | `word(B2-B3)×0.001 V` | DigiCluster HS-CAN |
| 6 | Every 6th | ~1.5s / 0.67 Hz | TPMS (8 PIDs), Oil Life |

BCM PIDs (TPMS) are all priority 6 to minimize expensive header switches (~100ms per `ATSH` command).
