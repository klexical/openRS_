# OBD PID Reference — Ford Focus RS MK3

Complete reference for all OBD-II parameters used by openRS_ (current as of v2.2.1).

## ECU Address Map

| ECU | Name | Request Header | Response Header | Bus |
|-----|------|----------------|-----------------|-----|
| PCM | Powertrain Control Module | 0x7E0 | 0x7E8 | HS-CAN 500k |
| BCM | Body Control Module | 0x726 | 0x72E | HS-CAN 500k |
| Broadcast | All ECUs | 0x7DF | varies | HS-CAN 500k |

## Mode 22 — PCM (Header: 0x7E0)

The following PCM Mode 22 PIDs are polled by the app every 10 seconds via ISO-TP over SLCAN (request `0x7E0` → response `0x7E8`). Additional passive CAN alternatives are noted where available.

| PID | Name | Request | Formula | Unit | Passive CAN alternative |
|-----|------|---------|---------|------|------------------------|
| 0xF405 | Coolant Temp (PCM) | `22F405` | `B4 − 40` | °C | CAN 0x2F0 |
| 0xF40F | Intake Air Temp (PCM) | `22F40F` | `B4 − 40` | °C | CAN 0x0F8 |
| 0x03CA | Intake Air Temp 2 | `2203CA` | `B4 − 40` | °C | Likely `manifoldChargeTempC` — post-intercooler |
| 0xF42F | Fuel Level (PCM) | `22F42F` | `(B4 / 255) × 100` | % | CAN 0x34A |

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

> **Architecture change in v1.1.0:** TPMS data is decoded from passive CAN frame `0x340`, not OBD Mode 22. The BCM broadcasts this frame on MS-CAN; the Gateway Module (GWM) bridges it to HS-CAN automatically. No OBD queries, no BCM header switching.

> **Formula update v1.1.6:** Raw bytes are not direct PSI. They are in units of 3.6 kPa each and must be converted: `PSI = raw × 3.6 / 6.895`. Validated against known tire pressures (e.g., raw `0x43` = 67 counts × 3.6 / 6.895 ≈ 35.0 PSI).

| CAN ID | Parameters | Decode |
|--------|-----------|--------|
| 0x340 | Tire pressure LF (byte 2), RF (byte 3), LR (byte 4), RR (byte 5) | `raw × 3.6 / 6.895 PSI`; raw 0 = sensor sleeping, ignored |

Sensors transmit only when wheels are rolling. If all four bytes are zero, the TPMS ECU has not sent data yet. The decoder ignores raw 0 values.

## Mode 22 — BCM (Header: 0x726)

> These PIDs are polled every 30 seconds via ISO-TP over SLCAN (request `0x726` → response `0x72E`).

| PID | Name | Request | Bytes | Formula | Unit | Notes |
|-----|------|---------|-------|---------|------|-------|
| 0xDD01 | **Odometer** | `22DD01` | 3 | `(B4×65536 + B5×256 + B6)` | km | 3-byte direct |
| 0x4028 | **Battery SOC** | `224028` | 1 | `B4` | % | Start/stop system SoC |
| 0x4029 | **Battery Temp** | `224029` | 1 | `B4 − 40` | °C | 12V battery temperature |
| 0xDD04 | **Cabin Temp** | `22DD04` | 1 | `(B4 × 10/9) − 45` | °C | Interior cabin sensor |

### TPMS PIDs — legacy OBD reference (replaced by passive CAN 0x340)

> **Formula correction (v1.1.0):** MeatPi's official Focus RS vehicle profile uses `([B4:B5]/10)/2.036`. Previous documentation used `(A×256+B)/2.9×0.145038`. The MeatPi formula (from real-world calibration) is approximately 1.7% lower for the same raw value. Moot for the app since TPMS is now decoded from passive CAN frame `0x340`.

| PID | Name | Request | Bytes | Formula (MeatPi) | Unit | Status |
|-----|------|---------|-------|-----------------|------|--------|
| 0x2813 | Tire Pressure LF | `222813` | 2 | `([B4:B5] / 10) / 2.036` | PSI | Legacy |
| 0x2814 | Tire Pressure RF | `222814` | 2 | `([B4:B5] / 10) / 2.036` | PSI | Legacy |
| 0x2815 | Tire Pressure RR | `222815` | 2 | `([B4:B5] / 10) / 2.036` | PSI | Legacy |
| 0x2816 | Tire Pressure LR | `222816` | 2 | `([B4:B5] / 10) / 2.036` | PSI | Legacy |
| 0x2823 | Tire Temp LF | `222823` | 1 | `A − 40` | °C | ⚠️ Unverified |
| 0x2824 | Tire Temp RF | `222824` | 1 | `A − 40` | °C | ⚠️ Unverified |
| 0x2825 | Tire Temp RR | `222825` | 1 | `A − 40` | °C | ⚠️ Unverified |
| 0x2826 | Tire Temp LR | `222826` | 1 | `A − 40` | °C | ⚠️ Unverified |

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
