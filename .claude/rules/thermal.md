---
paths:
  - "**/com/openrs/dash/**/*Thermal*"
  - "**/com/openrs/dash/ui/TempsPage.kt"
  - "**/com/openrs/dash/ui/ChassisPage.kt"
  - "**/com/openrs/dash/ui/DrivePage.kt"
  - "**/com/openrs/dash/service/DriveRecorder.kt"
  - "**/android/app/src/test/java/com/openrs/dash/*Readiness*"
  - "**/android/app/src/test/java/com/openrs/dash/*Thermal*"
---
# openRS_ Known Gotchas — Thermal readiness, temperature freshness, drive thermal record

- **Readiness = `ThermalReadinessSession.verdict`, never recomputed per surface** — DRIVE, THERMAL and `DriveRecorder`'s RTR
  marker read the one session verdict (composables via `thermalVerdict(vs)`, which voids it offline / on a demo epoch
  change); the holds, latches and resets are `ThermalReadinessEngine`'s (pure, clock-injected, tested). Reason text must
  say what a hold waits for — never a comparison that is no longer true. DRIVE's VEHICLE summary is never ALL NORMAL
  under a heat / forecast / recovery / CHECKING verdict; checklist limit and forecast rows are incomplete, not ticked,
  while a reading they cover is unavailable
- **Drive thermal judgements come from the drive's own record** — `thermalPeaksJson` v2 (`DriveThermalRecord`) saves
  per-sensor peaks with the warn / crit of the profile in effect, one segment per profile. TRIP's TEMP badge, the summary
  and the profile export read it through `DriveThermalReview` — never `prefs.*WarnC/CritC` for a recorded drive. Start →
  peak → end goes through `DriveThermalReview.progression()`: an endpoint missing or above the peak is null ("—") — never
  adjust the peak to fit. Older JSON arrays are LEGACY (no colours, no profile claimed); never rewrite them. The TEMP flag
  threshold is shown converted ("· flag threshold 220.1°F"), never as "95 % of" a °F value. The recorder feeds only
  `tempAvail`-fresh readings
- **Temperature freshness = `vs.tempAvail(value, tag)`** — RDU / AWD clutch / trans-oil temps (AWD poll, ~37 s cycle) are
  stale after 90 s, CAN temps after 60 s. Use it (or `tempStaleAfterMs(tag)`) wherever a temp's availability is shown or decided
- **Held readings after a drop are last-known** — `TempAvail.whenDisconnected()` turns a present reading STALE while
  `!vs.isConnected` (stamps stay "fresh" for 60 s after a drop); `VehicleState.liveOrRest()` returns per-frame dynamics at
  rest for composables that would draw the held frame. `DriveRecorder` skips samples while disconnected (like a pause) so
  held values are never stored as fresh — distance during the gap isn't counted
- **Thermal alerts: `SensorPrediction.reached`** — at/over critical show the current state, never "will reach … in <1 min".
  Readiness / alert temperatures render via `p.displayTemp` (user unit); comparisons stay in °C
- **TYRES panel reads one view per wheel (`TyreView`, #307)** — `tyreView()` decides current / last-known from the wheel's own
  `fieldLastUpdateMs["tirePress…"]` stamp (60 s) and the link; card, wheel tint and band, lead line, panel edge and chips all read
  it (`tyrePanel()`). A held pressure is never judged: it keeps its value, its age and "WAS LOW" / "WAS HIGH", never a live alarm,
  the panel level or the imbalance spread. Don't read `vs.anyTireLow` / `vs.maxTirePressSpread` there: they have no freshness
  (DRIVE's tyre alerts read `currentTyrePressures()`, the same rule). The band on the wheel is the cue that does not rely on colour
