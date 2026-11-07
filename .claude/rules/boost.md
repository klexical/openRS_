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

Moved verbatim from the tracked `CLAUDE.md` (section Known Gotchas) on 2026-11-06. Add a new gotcha of this area at the end; never reword an existing one here.

- **Live pressure = `BoostSession.gauge(vs, nowMs)` = TIP only; recorded = `read()` (#207, policy `rc7-boost-v3`)** — DRIVE,
  HUD, TRIP live readouts and Custom Dashboard show TIP − measured baro or "—"; they never switch to CAN 0x0F8 in a TIP gap
  (no stretched freshness, no held value). Recording, knock events and perf runs keep `read()` with the source per point, so
  a drive can hold CAN stretches the gauge showed as "—" (by design, stated in What's New / #207). TRIP chart pressure
  channels break at every unavailable / other-source sample, also after downsampling (`TimeSeriesMath.bucketValidity`);
  knock scatter draws only events of the axis's source; RaceChrono / TrackAddict carry NO pressure column (their only one
  is "Boost"); Sapphire converts plotted values / axes / tooltips / peaks to the unit (`lib/boost.ts`). `read()`: `boostKpa` is CAN 0x0F8 byte 5 + 101.325 (gauge,
  std-atm form, independent of baro) and is a reading only once stamped (`boostKpa`, ≤ 3 s old); the 101.325 default is
  never a reading. Rule = a fixed order, **no inference between the channels** (the v2 "CAN not tracking at ≥ 80 % throttle"
  vote was removed 2026-10-29 — owner did not approve it; throttle there is pedal on cars without 0x076): TIP − measured
  baro (TIP ≤ 2.5 s, rpm > 400 on a CAN frame ≤ 2 s old, baro 60–115 kPa) labelled **TIP**; else CAN 0x0F8 (fresh)
  labelled **BOOST**; else none ("—", recorded as source NONE). BOOST_SOURCE logs source changes (≤ 1 line / 10 s). Every
  reader of recorded boost goes through `StoredBoost` (reading + source / legacy "source not recorded" / none): trend and
  compare only within one kind, chart lines per source, map / RaceChrono / TrackAddict use the drive peak's source only;
  bookmark / lap / perf-run boost has no source column → not shown or exported. Sapphire mirrors it (`web/src/lib/boost.ts`).
  Freshness uses a wall clock (`wallClockMs()` in composables, a 1 s tick in the HUD) — never `lastUpdate`. Peaks are per
  measurement (BoostSession), captions in the selected unit. TIP cells / sweep chart / TARGET read vs measured baro;
  by-gear stats use stored baro or say std atm. VehicleState has no boost getters; `withPeaksUpdated` doesn't touch
  `peakBoostPsi`. Drives store per-point source + baro and the peak's source; never infer them for old rows. Which
  pressure byte 5 measures and TIP ≈ MAP at WOT are unverified on the car
- **Live gauge name vs recorded source labels (#207, rc.7 Build 38)** — the live gauge (DRIVE hero, HUD, TRIP live readouts,
  Custom Dashboard `boostKpa`, incl. saved layouts that say "TIP") is labelled plain **BOOST** — no qualifier, subtitle or
  help text (owner's direction 2026-11-04, after rejecting a "THROTTLE INLET" line) — via `liveGaugeLabel()` /
  `dashCellLabel()` in `ui/BoostLabels.kt`. It names the *live* rule only: TIP 0x033E − measured baro,
  TIP ≤ 2.5 s, otherwise "—" — never CAN. Recorded / mixed-source views name each point's source (`read()`: **TIP** for
  0x033E, **BOOST** for CAN 0x0F8 when TIP isn't current), and exports keep the source IDs. So "BOOST" means TIP live but
  CAN 0x0F8 in a recorded drive — don't "unify" them by relabelling history or exports. A display-only "BOOST (CAN)" label
  for recorded views is a separate follow-up (not done). The label is display only: no decode, scale, baro, freshness,
  recording, export or schema change
- **TIP is `raw / 128` kPa absolute** — 0x033E/0x0466 (DigiCluster, on-car verified). rc.4/rc.5 used `/903.81`, which is the Ford-DID **psi** formula, hiding TIP and starving BOOST SWEEP ([#198](https://github.com/klexical/openRS_/issues/198)). Do not reintroduce 903.81
