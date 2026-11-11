---
paths:
  - "**/com/openrs/dash/**/*Boost*"
  - "**/com/openrs/dash/service/HudOverlayService.kt"
  - "**/com/openrs/dash/service/DriveRecorder.kt"
  - "**/com/openrs/dash/ui/DrivePage.kt"
  - "**/com/openrs/dash/ui/CustomDashPage.kt"
  - "**/com/openrs/dash/ui/KnockScatter.kt"
  - "**/com/openrs/dash/data/KnockCapture.kt"
  - "**/com/openrs/dash/ui/trip/**"
  - "**/web/src/lib/boost.ts"
---
# openRS_ Known Gotchas — Boost / TIP rule (#207)

- **Live pressure = `BoostSession.gauge(vs, nowMs)` = TIP only; recorded = `read()` (#207, policy `rc7-boost-v3`)**
  - **Live:** DRIVE, HUD, TRIP live readouts and Custom Dashboard show TIP − measured baro or "—"; they never switch to CAN
    0x0F8 in a TIP gap (no stretched freshness, no held value). Freshness uses a wall clock (`wallClockMs()` in composables,
    a 1 s tick in the HUD) — never `lastUpdate`. Peaks are per measurement (BoostSession), captions in the selected unit.
    TIP cells / sweep chart / TARGET read vs measured baro; by-gear stats use stored baro or say std atm.
  - **`read()` (recording, knock events, perf runs)** is a fixed order with **no inference between the channels** (the owner
    rejected a vote on throttle; throttle is pedal on cars without 0x076): TIP − measured baro (TIP ≤ 2.5 s, rpm > 400 on a
    CAN frame ≤ 2 s old, baro 60–115 kPa) labelled **TIP**; else CAN 0x0F8 (fresh) labelled **BOOST**; else none ("—",
    recorded as source NONE). `boostKpa` is CAN 0x0F8 byte 5 + 101.325 (gauge, std-atm form, independent of baro) and is a
    reading only once stamped (`boostKpa`, ≤ 3 s old); the 101.325 default is never a reading. The source is kept per point,
    so a drive can hold CAN stretches the gauge showed as "—" (by design, stated in What's New / #207). BOOST_SOURCE logs
    source changes (≤ 1 line / 10 s).
  - **Stored:** drives store per-point source + baro and the peak's source; never infer them for old rows. Every reader of
    recorded boost goes through `StoredBoost` (reading + source / legacy "source not recorded" / none): trend and compare
    only within one kind, chart lines per source, map / RaceChrono / TrackAddict use the drive peak's source only;
    bookmark / lap / perf-run boost has no source column → not shown or exported. VehicleState has no boost getters;
    `withPeaksUpdated` doesn't touch `peakBoostPsi`.
  - **Views:** TRIP chart pressure channels break at every unavailable / other-source sample, also after downsampling
    (`TimeSeriesMath.bucketValidity`); knock scatter draws only events of the axis's source; RaceChrono / TrackAddict carry
    NO pressure column (their only one is "Boost"); Sapphire mirrors the rule and converts plotted values / axes /
    tooltips / peaks to the unit (`web/src/lib/boost.ts`).
  - Which pressure byte 5 measures and TIP ≈ MAP at WOT are unverified on the car.
- **Live gauge name vs recorded source labels (#207)** — the live gauge (DRIVE hero, HUD, TRIP live readouts,
  Custom Dashboard `boostKpa`, incl. saved layouts that say "TIP") is labelled plain **BOOST** — no qualifier, subtitle or
  help text (owner's direction; a "THROTTLE INLET" line was rejected) — via `liveGaugeLabel()` /
  `dashCellLabel()` in `ui/BoostLabels.kt`. It names the *live* rule only: TIP 0x033E − measured baro,
  TIP ≤ 2.5 s, otherwise "—" — never CAN. Recorded / mixed-source views name each point's source (`read()`: **TIP** for
  0x033E, **BOOST** for CAN 0x0F8 when TIP isn't current), and exports keep the source IDs. So "BOOST" means TIP live but
  CAN 0x0F8 in a recorded drive — don't "unify" them by relabelling history or exports. Recorded views have no display-only
  "BOOST (CAN)" label. The label is display only: no decode, scale, baro, freshness,
  recording, export or schema change
- **TIP is `raw / 128` kPa absolute** — 0x033E/0x0466 (DigiCluster, on-car verified). `/903.81` is the Ford-DID **psi** formula; using it hid TIP and starved BOOST SWEEP ([#198](https://github.com/klexical/openRS_/issues/198)). Do not reintroduce 903.81
