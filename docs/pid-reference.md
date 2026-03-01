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

## Mode 22 — TPMS via BCM (Header: 0x726)

| PID | Name | Request | Bytes | Formula | Unit | Priority | Status |
|-----|------|---------|-------|---------|------|----------|--------|
| 0x2813 | Tire Pressure LF | `222813` | 2 | (A×256+B) / 2.9 × 0.145038 | PSI | 6 | Verified |
| 0x2814 | Tire Pressure RF | `222814` | 2 | (A×256+B) / 2.9 × 0.145038 | PSI | 6 | Verified |
| 0x2815 | Tire Pressure RR | `222815` | 2 | (A×256+B) / 2.9 × 0.145038 | PSI | 6 | Verified |
| 0x2816 | Tire Pressure LR | `222816` | 2 | (A×256+B) / 2.9 × 0.145038 | PSI | 6 | Verified |
| 0x2823 | Tire Temp LF | `222823` | 1 | A − 40 | °C | 6 | ⚠️ Experimental |
| 0x2824 | Tire Temp RF | `222824` | 1 | A − 40 | °C | 6 | ⚠️ Experimental |
| 0x2825 | Tire Temp RR | `222825` | 1 | A − 40 | °C | 6 | ⚠️ Experimental |
| 0x2826 | Tire Temp LR | `222826` | 1 | A − 40 | °C | 6 | ⚠️ Experimental |

### Tire Temperature Notes

The temp PIDs (0x2823–0x2826) are educated guesses based on:
1. Schrader TPMS sensors confirmed to transmit temperature
2. OBD4RS app confirms tire temps work on Focus RS
3. Common Ford pattern: temp PIDs offset +0x10 from pressure PIDs
4. Standard Ford temp formula: `A − 40 = °C`

If these return NO DATA, try adjacent PIDs or check FORScan BCM PID list.

## CAN Frame IDs (HS-CAN 500 kbps, Passive ATMA)

| ID | Description | Bytes Used | Decode Formula |
|----|-------------|------------|----------------|
| 0x070 | Torque at trans | bits 37–48 | (bits − 500) Nm |
| 0x076 | Throttle + speed | B0, B2-3 | B0 × 0.392 %, (B2×256+B3) × 0.01 km/h |
| 0x080 | Pedals + steering | B0, B2-3, B4-5 | Accel %, (word − 20000) × 0.1 °, word × 0.1 bar |
| 0x090 | RPM + coolant | B0-1, B2 | word × 0.25 RPM, B2 − 40 °C |
| 0x0B0 | Dynamics | B0-1, B2-3, B4-5 | (word − 32768) × scale |
| 0x0C8 | Gauge illumination | B0 | Direct brightness level |
| 0x0F8 | Engine temps | B4, B5, B7 | IAT −40, boost kPa, oil −60 °C |
| 0x1E3 | Drive mode | bits 0–4 | 0=N, 1=S, 2=T, 3=D |
| 0x215 | Wheel speeds | B0-7 | 4 × (word − 10000) × 0.01 km/h |
| 0x217 | ESC status | bits 0–2 | 0=On, 1=Sport, 2=Off |
| 0x230 | Gear | bits 0–4 | 0=N, 1-6, 7=R |
| 0x2C0 | AWD torque | bits 0-24, B3 | L/R torque (12-bit each), RDU −40 °C |
| 0x2C2 | PTU temp | B0 | B0 − 40 °C |
| 0x34A | Fuel level | B0 | B0 × 0.392 % |
| 0x34C | Ambient temp | B0 | B0 − 40 °C |
| 0x3C0 | Battery voltage | B0 | B0 × 0.1 V |

## Priority Scheduling

The hybrid polling system schedules PIDs based on how fast they change:

| Priority | Cycle | Approx Rate | PIDs |
|----------|-------|-------------|------|
| 1 | Every cycle | ~250ms / 4 Hz | Calc Load, STFT, AFR Actual, ETC Actual, TIP Actual |
| 2 | Every 2nd | ~500ms / 2 Hz | AFR Desired, ETC Desired, TIP Desired, WGDC, Timing, LTFT, Fuel Rail, Ign Corr, Charge Air, Commanded AFR |
| 3 | Every 3rd | ~750ms / 1.3 Hz | VCT I/E, Octane Adjust, Cat Temp, O2, Baro, AFR Sensor |
| 6 | Every 6th | ~1.5s / 0.67 Hz | TPMS (8 PIDs), Oil Life |

BCM PIDs (TPMS) are all priority 6 to minimize expensive header switches (~100ms per `ATSH` command).
