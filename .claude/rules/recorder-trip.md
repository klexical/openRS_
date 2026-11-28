---
paths:
  - "**/com/openrs/dash/service/DriveRecorder.kt"
  - "**/com/openrs/dash/data/Drive*.kt"
  - "**/com/openrs/dash/**/*Lap*"
  - "**/com/openrs/dash/data/PendingSummary.kt"
  - "**/com/openrs/dash/data/TripState.kt"
  - "**/com/openrs/dash/data/SessionDatabase.kt"
  - "**/com/openrs/dash/demo/TripPreview.kt"
  - "**/com/openrs/dash/ui/trip/**"
  - "**/com/openrs/dash/ui/MainActivity.kt"
  - "**/com/openrs/dash/diagnostics/DriveExportBuilder.kt"
  - "**/android/app/src/test/java/com/openrs/dash/*Drive*"
  - "**/android/app/src/test/java/com/openrs/dash/*Thumbnail*"
  - "**/android/app/src/test/java/com/openrs/dash/*PointBuffer*"
  - "**/android/app/src/test/java/com/openrs/dash/*StopFlush*"
  - "**/android/app/src/test/java/com/openrs/dash/*Recorder*"
---
# openRS_ Known Gotchas — Drive recorder, laps and the TRIP tab

- **TRIP map: created on TRIP, then retained** — the pager composes neighbouring pages, so `TripPage` creates `DriveMap`
  only once TRIP is the settled page and then pins itself: one MapView for the activity's life. Off screen its lifecycle
  is capped at CREATED and its inputs are held, so anything infinite-animated or modal inside TRIP must gate on `visible`
  (rec pulse, lap timer, post-drive summary sheet, every 1 s `wallClockMs()` clock: `TripHiddenRecompositionTest`, #629)
- **Navigation swipe + TRIP map isolation** — pager swipe is disabled while a finger is on the map (`onMapTouched` →
  `mapTouched` in MainActivity). `nestedScroll` does NOT work for this: Google Maps is an `AndroidView` that dispatches no
  Compose nested-scroll events, so the map Box's `pointerInput` monitors (does NOT consume) touches. The Google logo cannot
  be removed (Maps Platform ToS)
- **Recorder only stores fresh polled values** — `DriveRecorder` writes TIP/target/WGDC/KR/STFT/LTFT only when `FreshnessGate.takeIfFresh()` passes; otherwise sentinels (`tipActualKpa`/`boostTargetKpa` 0.0, `wgdcPct` −1.0). Never record held VehicleState values for slow DIDs — they repeat one reading many times
- **A drive's state belongs to its `DriveRun` (#236)** — never move per-drive state back into recorder fields:
  auto-record's finalize + start and START right after STOP overlap the old drive's finalization. STOP writes only
  recorder-owned columns (the user's name / tags win; a deleted row gets no laps, thumbnail or summary)
  (`DriveFinalizeTest`)
- **Laps (#237)** — one `LapTimer` per recorder: TRIP takes `recorder.lapTimerForUi()` and never installs its own (a
  recreated activity would put an unarmed timer on the recorder mid-drive and STOP would lose the laps,
  `LapRecreationTest`). The 2 s fix age a lap needs is unmeasured on phones (late / batched fixes → no laps; read
  `STALE=` at STOP). The `LapTimer` logcat line ships in release builds and carries no coordinates. Lap `peakBoostPsi`
  is a mixed-source `read()` value — never display or export it
- **STOP waits for the drive's out-of-loop point writes (#241)** — writers outside the loop hold the run's
  `outsideLoopWrites` lock around take + insert; never wait on another drive's lock (`ThumbnailInputRegressionTest`).
  - Only a real failure counts in `failedPointWrites`: a `catch (Exception)` around `withContext(NonCancellable + IO)` in
    a cancelled `finally` counts the rethrown cancellation as a failure (`StopFlushOutcomeTest`).
  - The point buffer has one monitor (`DriveRun.addPoint` / `bufferedCount` / `takePoints`); never suspend inside it, and
    keep `pointsBuffer` an `ArrayList` (`PointBufferRaceTest` swaps it by reflection).
  - Known gap: `DiagnosticExporter.share` ships a ZIP without drive files on a null snapshot, silently.
- **`lap_sessions` has no FK to `drives`** — after any drive deletion call `deleteOrphanLapSessions()` (laps cascade from
  lap_sessions). Sessions older than 30 days are pruned at each connection start (by design)
- **A position belongs to one drive (#311)** — anything that records or places a position reads
  `DriveRecorder.freshFix()` (the recording drive's own fix, at most `LapTimer.MAX_FIX_AGE_MS` old): before the drive's
  first fix and in a GPS gap a note / MARK has no position and SET S/F is refused. Never carry a fix from one drive into
  the next, and never record the platform's last-known fix as a drive position
- **A drive's job exists before it runs (#314)** — `startDrive` assigns `recorderJob` on the caller's thread before
  starting it. Never assign the job from inside its own coroutine or after a launch: a STOP that reads it first neither
  cancels nor joins the loop (`DriveStartStopRaceTest`)
- **Two kinds of pause, one owner (#316)** — the recorder owns the drive's phase and takes commands and events; nothing
  else sets it. A hand pause ends only by RESUME or STOP; a link pause follows `AutoRecordPolicy` and `STALE_LINK_PAUSE_MS` (15 min). Every surface
  shows the drive through `DriveLabel.of`. Terms: `GLOSSARY.md`
- **Every running save is tracked (#329)** — `isFinalizing` and `durabilityBarrier()` read the set of save jobs still
  running. Never back them with one "latest job" slot: a later drive's quicker save, or a start cancelled by STOP, would
  hide an earlier save that is still writing, and the foreground service could be released under it
- **A parked drive adds no distance (#335)** — the loop drops a GPS step only while `DriveState.carParked` (fresh 0x130
  speed of 0); a car with no known speed keeps its steps (`ParkedDistanceTest`, `NewConnectionServiceTest`)
- **A drive's summary is built one way (#321)** — STOP, the export of a drive still recording and a drive closed at the
  next launch all go through `DriveRecorder.summarize`. The aggression score covers every stored point of the drive (the
  owner, on #321). Never read the run's accumulators from outside the loop while it runs: an export reads the loop's
  published `SummaryParts` (`DriveSummaryOneRuleTest`)
