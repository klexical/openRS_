# openRS_ app guide

> **Version:** this page describes the stable release **v2.2.6**. It was moved out of the README on 2026-11-08 without changes to its content. The v2.2.7 release candidates changed parts of it: see [android/CHANGELOG.md on staging](https://github.com/klexical/openRS_/blob/staging/android/CHANGELOG.md).

The screens, the Ready-to-Race thresholds and the settings of v2.2.6. For setting up the adapter, see the
[hardware setup guide](../android/docs/hardware-setup.md).

## Screens (v2.2.6)

<p align="center"><b>Stable v2.2.6</b></p>

<div align="center">
  <img src="../assets/screenshots/dash.png" width="160" alt="DASH tab">
  <img src="../assets/screenshots/power.png" width="160" alt="POWER tab">
  <img src="../assets/screenshots/chassis.png" width="160" alt="CHASSIS tab">
  <img src="../assets/screenshots/temps.png" width="160" alt="TEMPS tab">
  <img src="../assets/screenshots/diag.png" width="160" alt="DIAG tab">
  <img src="../assets/screenshots/more.png" width="160" alt="MORE tab">
</div>

<p align="center">
  <sub>DASH &nbsp;·&nbsp; POWER &nbsp;·&nbsp; CHASSIS &nbsp;·&nbsp; TEMPS &nbsp;·&nbsp; DIAG &nbsp;·&nbsp; MORE &nbsp;&nbsp;(v2.2.6; the MAP tab is not pictured)</sub>
</p>

### 7 Tabs (stable v2.2.6)

| Screen | Description |
|--------|-------------|
| **DASH** | Hero boost/RPM/speed gauges (with session peak values), 0-60 / 0-100 performance timer, real-time fuel economy (idle L/hr, instant/average MPG or L/100km, DTE), throttle/brake/clutch/fuel/battery, Temps Quick (oil, coolant, intake, oil life), G-forces (lat, lon, torque), animated AWD split bar, IPC warning lamp banner (CEL, ABS, BRK, CHRG, OIL, TEMP) |
| **POWER** | AFR hero cards (actual/desired/lambda), commanded AFR (CMD AFR), ETC actual/desired, WGDC, TIP, HP fuel rail PSI, low-pressure fuel rail (LP FUEL), timing, engine load, OAR, per-cylinder knock correction (KR C1–C4, colour-coded; the cylinder 2–4 DIDs are under investigation, [#222](https://github.com/klexical/openRS_/issues/222)), VCT intake/exhaust, **STFT/LTFT short/long-term fuel trims** |
| **CHASSIS** | AWD detail (4 wheel speeds, wheel rotation counts, torque bar, F/R delta, L/R delta, rear bias, clutch temps L/R, **clutch actuator current L/R**, **hydraulic pressure L/R**, trans oil temp), G-force + yaw + steering with peak reset, TPMS Focus RS wireframe (pressure + temp, 3-tier colour-coded Low/Warn/High thresholds), pressure spread warning (⚠ PRESSURE IMBALANCE when spread ≥ 4 PSI), temperature range legend |
| **TEMPS** | Animated Ready-to-Race banner, 14 temperature cards each with a colour indicator bar (oil, coolant, intake, ambient, RDU, PTU, charge air, manifold charge, catalytic, cabin, battery, clutch L, clutch R, trans oil) |
| **MAP** | Live drive tracking with **Google Maps** — 6 colour modes (speed, drive mode, boost, throttle, lateral G, temp), start/finish markers, peak event markers (RPM/boost/lat-G/speed), pause markers, route stats HUD, weather card, custom recenter / zoom controls. Drive history grouped by date with status badges, swipe-to-delete, per-drive export (GPX/CSV/summary). |
| **DIAG** | DTC Scanner (full-module scan including GFM 0x7D2, count badges, freeze-frame, clear), DID Prober (scan any ECU for valid Mode 22 DIDs), PID Browser (1,148 FORScan catalog PIDs across 20 modules, searchable), frame inventory with per-ID change tracking, SLCAN raw log, **live button input feedback** (drive mode toggle, suspension, ASS, ESC defeat), **battery row with charge/discharge current**, one-tap ZIP export (SavvyCAN/Kayak compatible) |
| **MORE** | Drive mode (N/S/T/D, tap-to-change with openrs-fw firmware), ESC status, firmware version display, firmware-gated features (Launch Control, Auto S/S Kill), Module Status (RDU/PDC/FENG live OBD), passive VIN decode (CAN 0x40A), connection & snapshot, in-app update checker (stable + beta channels) |

A frosted-glass **bottom nav bar** with vector icons + spring-animated indicator persists across all tabs. **Quick Mode Dock** drops down from the status bar's MODE pill from any tab for one-tap drive mode changes.

### Ready-to-Race Thresholds

The TEMPS tab shows a warming-up / race-ready banner based on **oil**, **coolant**, **RDU**, **PTU**, and **clutch** temperatures. Thresholds are preset-based — configurable via Street / Track / Race in Settings:

| Sensor | Street | Track | Race |
|--------|--------|-------|------|
| Engine Oil | ≥ 70 °C | ≥ 80 °C | ≥ 85 °C |
| Coolant | ≥ 70 °C | ≥ 75 °C | ≥ 80 °C |
| RDU | ≥ 30 °C | ≥ 30 °C | ≥ 30 °C |
| PTU | ≥ 40 °C | ≥ 40 °C | ≥ 40 °C |

The banner shows which sensors are still below threshold with live °C values. A value of −99 °C (not yet received) is treated as passing to avoid blocking warm cars on reconnect. Clutch temperatures are also factored into the race-ready check (cold threshold: < 25 °C).

### Settings

| Setting | Options | Default |
|---------|---------|---------|
| Speed unit | MPH / KPH | MPH |
| Temperature | °F / °C | °F |
| Boost pressure | PSI / BAR / kPa | PSI |
| Tire pressure | PSI / BAR | PSI |
| Low tire threshold | PSI (any value) | 30 PSI |
| Threshold preset | Street / Track / Race | Street |
| Theme | RS paint colours (Nitrous Blue, Frozen White, etc.) | Nitrous Blue |
| Keep screen on | on / off | on |
| Auto-reconnect | on / off | on |
| Reconnect interval | seconds | 10 s |
| Adapter | MeatPi USB (C3) / MeatPi Pro (S3) | MeatPi USB |
| Connection method | Wi-Fi / Bluetooth Low Energy | Wi-Fi |
| BLE device | scan + pick from in-app dialog | — |
| Update channel | Stable / Beta | Stable |
| Auto-record drives | on / off | off |
| Max saved drives | count | 50 |
| Visibility | Night / Day / Sun preset + brightness slider (0.0–1.0) | Night |
| MicroSD logging reminder | on / off | off (MeatPi Pro only) |
| Max saved ZIP exports | count | 5 |
| Odometer display | Miles / km | Miles |
| Edge shift light | on / off | off |
| Shift light colour | Accent / White / Progressive | Accent |
| Shift light intensity | Low / Medium / High | High |
| Shift light RPM | RPM threshold | 6800 |
| Tire warn threshold | PSI | 32 PSI |
| Tire high threshold | PSI | 40 PSI |

## What changes in the v2.2.7 release candidates

The v2.2.7 release candidates have a redesigned app ("Daylight"):
- **Five tabs:** DRIVE, PERF, THERMAL, TRIP and GARAGE; Settings sits behind the cog.
- **Themes:** NIGHT, DAY, AUTO and ULTRA.
- **New in the RCs:** a redesigned drive-mode dock, lap timing on TRIP, a knock logger, a boost target-vs-actual sweep and a fuel-trim drift tracker.

The AWD front / rear percentage shown in v2.2.6 is withdrawn in the v2.2.7 release candidates: it divided rear-unit
torque by engine torque, which is not a physical split ([#226](https://github.com/klexical/openRS_/issues/226)).
