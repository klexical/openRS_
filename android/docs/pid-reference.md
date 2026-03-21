# OBD PID Reference — Ford Focus RS MK3

Complete reference for all OBD-II parameters used by openRS_ (current as of v2.2.4).

## ECU Address Map

| ECU | Name | Request Header | Response Header | Bus |
|-----|------|----------------|-----------------|-----|
| PCM | Powertrain Control Module | 0x7E0 | 0x7E8 | HS-CAN 500k |
| BCM | Body Control Module | 0x726 | 0x72E | HS-CAN 500k |
| AWD | AWD / RDU Module | 0x703 | 0x70B | HS-CAN 500k |

## Mode 22 — PCM (Header: 0x7E0)

The following PCM Mode 22 PIDs are polled by the app every 30 seconds via ISO-TP over SLCAN (request `0x7E0` → response `0x7E8`). Additional passive CAN alternatives are noted where available.

> **Reference PIDs:** Some PIDs below are documented for reference but are **not actively polled** — they have passive CAN equivalents that provide the same data at full bus speed.

| PID | Name | Request | Formula | Unit | Polled? | Passive CAN alternative |
|-----|------|---------|---------|------|---------|------------------------|
| 0xF405 | Coolant Temp (PCM) | `22F405` | `B4 − 40` | °C | No (reference) | CAN 0x2F0 |
| 0xF40F | Intake Air Temp (PCM) | `22F40F` | `B4 − 40` | °C | No (reference) | CAN 0x2F0 |
| 0x03CA | Intake Air Temp 2 | `2203CA` | `B4 − 40` | °C | No (reference) | Likely post-intercooler charge air |
| 0xF42F | Fuel Level (PCM) | `22F42F` | `(B4 / 255) × 100` | % | ✅ 30 s | CAN 0x380 (also decoded) |
| 0x0304 | Battery Voltage | `220304` | `(B4×256 + B5) / 2048` | V | ✅ 30 s | — |

## Mode 1 — Standard OBD-II (Reference Only)

> **Not polled by the app.** These Mode 1 PIDs are documented for reference. openRS_ uses Ford-enhanced Mode 22 PIDs via PCM header 0x7E0 instead.

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
| 0x093C | ETC Angle Actual | `22093C` | 2 | (A×256+B) × (100/8192) | % | 1 | DigiCluster |
| 0x091A | ETC Angle Desired | `22091A` | 2 | (A×256+B) × (100/8192) | % | 2 | DigiCluster |
| 0x033E | TIP Actual | `22033E` | 2 | (A×256+B) / 903.81 | kPa | 1 | DigiCluster |
| 0x0466 | TIP Desired | `220466` | 2 | (A×256+B) / 903.81 | kPa | 2 | DigiCluster |
| 0x0462 | WGDC Desired | `220462` | 1 | B4 × 100/128 | % | 2 | DigiCluster |
| 0x0318 | VCT Intake Angle | `220318` | 2 | (signed(A)×256+B) / 16 | ° | 3 | DigiCluster |
| 0x0319 | VCT Exhaust Angle | `220319` | 2 | (signed(A)×256+B) / 16 | ° | 3 | DigiCluster |
| 0x03EC | Ign Correction Cyl1 | `2203EC` | 2 | (signed(A)×256+B) / −512 | ° | 2 | DigiCluster |
| 0x054B | Oil Life | `22054B` | 1 | A | % | 3 | DigiCluster |
| 0x03E8 | Octane Adjust Ratio | `2203E8` | 2 | (signed(A)×256+B) / 16384 | ratio | 3 | DigiCluster |
| 0x0461 | Charge Air Temp | `220461` | 2 | (signed(A)×256+B) / 64 | °C | 2 | DigiCluster |
| 0xF422 | HP Fuel Rail Pressure | `22F422` | 2 | (A×256+B) × 1.45038 | PSI | 2 | DigiCluster |
| 0xF43C | Catalytic Temp | `22F43C` | 2 | (A×256+B) / 10 − 40 | °C | 3 | DigiCluster |

## TPMS — BCM Mode 22 (current method, v1.1.6+)

> **Architecture note:** TPMS data is polled from the BCM via Mode 22 every 30 seconds. Tire pressures use per-tire PIDs 0x2813-0x2816. Tire temperatures use PID 0x280B ("last received TPMS sensor") which returns the most recently heard sensor's ID, pressure, temp, status, and checksum in a 12-byte multi-frame ISO-TP response. On connect, sensor IDs are polled once from 0x280F-0x2812 to build a position map. Per-tire temperature PIDs 0x2823-0x2826 were confirmed unsupported (`7F 22 31` -- see issue #119). Earlier versions (v1.1.0-v1.1.5) attempted to decode TPMS from passive CAN frame `0x340`, but that carries PCM ambient temperature only.

### Tire pressure (per-tire, single-frame)

| PID | Name | Request | Formula | Unit |
|-----|------|---------|---------|------|
| 0x2813 | Tire Pressure LF | `222813` | `(((256*A)+B)/3 + 22/3) * 0.145` | PSI |
| 0x2814 | Tire Pressure RF | `222814` | `(((256*A)+B)/3 + 22/3) * 0.145` | PSI |
| 0x2815 | Tire Pressure RR | `222815` | `(((256*A)+B)/3 + 22/3) * 0.145` | PSI |
| 0x2816 | Tire Pressure LR | `222816` | `(((256*A)+B)/3 + 22/3) * 0.145` | PSI |

### Sensor IDs (per-tire, polled once on connect, single-frame)

| PID | Position | Request | Bytes | Notes |
|-----|----------|---------|-------|-------|
| 0x280F | LF | `22280F` | 4 | 4-byte unique sensor ID |
| 0x2810 | RF | `222810` | 4 | 4-byte unique sensor ID |
| 0x2811 | RR | `222811` | 4 | 4-byte unique sensor ID |
| 0x2812 | LR | `222812` | 4 | 4-byte unique sensor ID |

### Last received sensor (polled every 30s cycle, multi-frame ISO-TP)

| PID | Name | Request | Response layout | Notes |
|-----|------|---------|-----------------|-------|
| 0x280B | Last received TPMS sensor | `22280B` | `62 280B [ID0..ID3] [press_hi press_lo] [temp] [status] [checksum]` | 12-byte payload, requires FC frame after FF |

Decoding `0x280B` data bytes (after `62 280B`):
- **Sensor ID** (4 bytes): matched against 0x280F-0x2812 map to determine tire position
- **Pressure** (2 bytes): `(A*256+B) / 20` PSI
- **Temperature** (1 byte): `raw - 40` C
- **Status** (1 byte): sensor status flags
- **Checksum** (1 byte): validation byte

## Mode 22 — BCM (Header: 0x726)

> These PIDs are polled every 30 seconds via ISO-TP over SLCAN (request `0x726` → response `0x72E`).

| PID | Name | Request | Bytes | Formula | Unit | Notes |
|-----|------|---------|-------|---------|------|-------|
| 0xDD01 | **Odometer** | `22DD01` | 3 | `(B4×65536 + B5×256 + B6)` | km | 3-byte direct |
| 0x4028 | **Battery SOC** | `224028` | 1 | `B4` | % | Start/stop system SoC |
| 0x4029 | **Battery Temp** | `224029` | 1 | `B4 − 40` | °C | 12V battery temperature |
| 0xDD04 | **Cabin Temp** | `22DD04` | 1 | `(B4 × 10/9) − 45` | °C | Interior cabin sensor |

### TPMS PIDs -- alternative/legacy formula reference

> **Formula note:** MeatPi's official Focus RS vehicle profile uses `([B4:B5]/10)/2.036`. The app uses `(((256*A)+B)/3 + 22/3) * 0.145`, which was validated against known tire pressures on the Focus RS MK3.

| PID | Name | Request | Bytes | Formula (MeatPi) | Unit | Status |
|-----|------|---------|-------|-----------------|------|--------|
| 0x2813 | Tire Pressure LF | `222813` | 2 | `([B4:B5] / 10) / 2.036` | PSI | Legacy |
| 0x2814 | Tire Pressure RF | `222814` | 2 | `([B4:B5] / 10) / 2.036` | PSI | Legacy |
| 0x2815 | Tire Pressure RR | `222815` | 2 | `([B4:B5] / 10) / 2.036` | PSI | Legacy |
| 0x2816 | Tire Pressure LR | `222816` | 2 | `([B4:B5] / 10) / 2.036` | PSI | Legacy |
| 0x2823 | Tire Temp LF | `222823` | 1 | `A - 40` | C | **Unsupported** (`7F 22 31`) |
| 0x2824 | Tire Temp RF | `222824` | 1 | `A - 40` | C | **Unsupported** (`7F 22 31`) |
| 0x2825 | Tire Temp RR | `222825` | 1 | `A - 40` | C | **Unsupported** (`7F 22 31`) |
| 0x2826 | Tire Temp LR | `222826` | 1 | `A - 40` | C | **Unsupported** (`7F 22 31`) |

## CAN Frame IDs (HS-CAN 500 kbps) — DigiCluster verified

All formulas re-validated against DigiCluster `can0_hs.json` and `can1_ms.json`.

| ID | Description | Decode Formula | Source |
|----|-------------|----------------|--------|
| 0x010 | Steering wheel angle | `((B6&0x7F)<<8\|B7)×0.04395`; sign: `B4 bit 7` (1=right, 0=left) | RS_HS.dbc |
| 0x070 | Torque at transmission | Motorola `bits(37,11) − 500` Nm | RS_HS.dbc |
| 0x076 | Throttle % (ECU) | `B0 × 0.392` — may not broadcast on all tunes | RS_HS.dbc |
| 0x080 | Throttle %, accel pedal % | bytes 2-3 / 2.55 | DigiCluster HS-CAN |
| 0x090 | RPM, baro pressure | RPM: `((B4&0x0F)<<8\|B5)×2`; baro: `B2×0.5 kPa` | DigiCluster HS-CAN |
| 0x0C8 | Gauge brightness, e-brake, ignition status | brightness: `B0&0x1F`; e-brake: `B3&0x40`; ignition: `B2&0x1F` (0=KeyOut..7=Running..9=Cranking) | DigiCluster HS-CAN + RS_HS.dbc |
| 0x0F8 | Oil temp, boost, PTU temp | oil: `B1−50 °C`; boost: B5 absolute kPa; PTU: `B7−60 °C` | RS_HS.dbc PCMmsg07 |
| 0x130 | Speed kph | `word(B6-B7)×0.01` | DigiCluster HS-CAN |
| 0x160 | Longitudinal G | `((B6&0x03)<<8\|B7)×0.00390625−2.0` | DigiCluster HS-CAN |
| 0x180 | Lateral G, yaw rate, vertical G | latG: `((B2&0x03)<<8\|B3)×0.00390625−2.0`; yaw: `((B4&0x0F)<<8\|B5)×0.03663−75 °/s`; vertG: `((B0&0x03)<<8\|B1)×0.00390625−2.0` | RS_HS.dbc ABSmsg02 |
| 0x1A4 | Ambient temp | `B4 signed × 0.25 °C` | DigiCluster MS-CAN bridged |
| 0x1B0 | Drive mode (coarse) | `(B6>>4)&0x0F` — 0=Normal, 1=Sport/Track, 2=Drift | RS_HS.dbc AWDmsg01 |
| 0x420 | Drive mode (detail), launch control | B6: 0x10=Normal, 0x11=Sport/Track, 0x12=Drift; B7 bit0: 0=Sport, 1=Track; LC: `(B6>>2)&1` | RS_HS.dbc + empirical (2026-03-11) |
| 0x1C0 | ESC mode status | `bits(10,2)`: 0=On, 1=Off, 2=Sport, 3=Launch (byte1 bits 5–4) | RS_HS.dbc VAL_ 448 + SLCAN-verified |
| 0x190 | 4× wheel speeds | FL/FR/RL/RR: 15-bit Motorola × 0.011343006 kph | RS_HS.dbc ABSmsg03 |
| 0x230 | Gear position | `bits(0,4)` — 0=Park/Neutral, 1–6=gears, 7=Reverse | RS_HS.dbc |
| 0x252 | Brake pressure | `((B1&0x0F)<<8\|B2) / 40.95` — 12-bit ADC, normalised 0–100% | RS_HS.dbc ABSmsg10 |
| 0x2C0 | AWD L/R torque | 12-bit words scaled | DigiCluster HS-CAN |
| 0x2F0 | Coolant temp, IAT | coolant: `((B4&0x03)<<8\|B5) − 60 °C`; IAT: `((B6&0x03)<<8\|B7) × 0.25 − 127 °C` | RS_HS.dbc PCMmsg16 |
| 0x340 | Ambient temp (PCMmsg17) | `byte7 signed × 0.25 °C` — **not** TPMS | RS_HS.dbc PCMmsg17 |
| 0x360 | Odometer (km), engine status | odo: `(data[3]<<16\|data[4]<<8\|data[5])` — 24-bit BE, 1 km/bit; engine: `data[0]` (0=Idle, 2=Off, 183=Running, 186=Kill, 191=RecentStart, 196=Warmup) | RS_HS.dbc + community [#102](https://github.com/klexical/openRS_/discussions/102) |
| 0x380 | Fuel level % (FuelLevelFiltered) | Motorola 10-bit: `((data[2]&0x03)<<8\|data[3]) × 0.4` | RS_HS.dbc PCMmsg30 |
