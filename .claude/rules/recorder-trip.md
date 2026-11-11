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

- **TRIP tab camera state is hoisted** — `cameraPositionState` lives in `DrivePage` (trip/), passed to `DriveMap` as a parameter. This allows DrivePage to control zoom and recenter. Google Maps native My Location button is disabled; custom `◎` button replaces it
- **TRIP map: created on TRIP, then retained** — `HorizontalPager(beyondViewportPageCount = 1)` composes neighbours and
  prefetches the next page, so TRIP is composed from PERF / THERMAL / GARAGE. `TripPage(settled, visible)` creates
  `DriveMap` only once TRIP is the settled page, then pins itself (`LocalPinnableContainer.pin()`) so the pager keeps the
  page — one MapView for the activity's life, released with the composition. Off screen (`visible` = TRIP in
  `layoutInfo.visiblePagesInfo`) the subtree's `LocalLifecycleOwner` is capped at CREATED (AndroidView passes it to the
  MapView → onStop, EGL surface released) and its vehicle / drive inputs are held. Anything infinite-animated or
  modal inside TRIP must gate on `visible` (rec pulse, lap timer, post-drive summary sheet)
- **Navigation swipe + TRIP tab isolation** — `HorizontalPager` with swipe between all 5 tabs. TRIP tab (page 3): pager swipe is dynamically disabled while the user touches the map area via `onMapTouched` callback from `DrivePage` → `mapTouched` state → `userScrollEnabled = !(selectedTab == 3 && mapTouched)`. `nestedScroll` does NOT work for this — Google Maps is an `AndroidView` that doesn't dispatch Compose nested scroll events. The `pointerInput` on the map Box monitors (does NOT consume) touches. Google logo cannot be removed (Maps Platform ToS requirement)
- **Recorder only stores fresh polled values** — `DriveRecorder` writes TIP/target/WGDC/KR/STFT/LTFT only when `FreshnessGate.takeIfFresh()` passes; otherwise sentinels (`tipActualKpa`/`boostTargetKpa` 0.0, `wgdcPct` −1.0). Never record held VehicleState values for slow DIDs — they repeat one reading many times
- **A drive's state belongs to its `DriveRun` (#236)** — `startDrive` creates a new `DriveRun` (points buffer, recent
  points, start temps, freshness gate, trims, pulls, thermal tracker); that drive's loop and STOP hold it, and the loop's
  live-state writes are gated on its own drive id. Never move per-drive state back into recorder fields: auto-record's
  finalize + start and START right after STOP overlap the old drive's finalization. STOP re-reads the row inside one Room
  transaction and writes only recorder-owned columns (the user's name / tags win; a deleted row gets no laps, thumbnail or
  summary); a new drive starts with empty oil / coolant peaks. `stopDrive()` takes its STOP time (= `endTime`), end
  readings, fuel economy and `takeLaps(driveId)` at the call; the reverse-geocode name lookup runs before, outside the
  transaction.
- **The loop feeds `LapTimer` every recorded fix (#237)**
  - A lap = a gate crossing between two fresh (≤ 2 s, monotonic), ordered fixes ≤ 5 s apart, time interpolated; 30 m
    departure before re-arming; a missing bearing uses the segment course (never 0°). The 2 s fix age is unmeasured on
    phones (late / batched fixes → no laps; read `STALE=` at STOP).
  - The timer belongs to one drive: `takeLaps(driveId)` closes it and refuses that drive's late fixes. A paused or
    disconnected fix is never fed to the timer — the loop calls `LapTimer.breakTrack(driveId)` instead. LapTimer's
    mutating methods are `@Synchronized` (the overlay getters `currentLapElapsedMs` / `deltaVsBestMs` are not — #246).
  - One timer per recorder (#238): TRIP takes `remember(recorder) { recorder.lapTimerForUi() }` and never installs its
    own — `remember { LapTimer() }` + assignment lets a recreated activity (rotation) put a new, unarmed timer on the
    recorder mid-drive, so the line vanishes and STOP loses the laps before it (`LapRecreationTest`).
  - One `LapTimer` logcat line per fix while a line is set (fix time, age, x / y metres from S/F, `b=`, verdict — no
    coordinates) + counts at STOP; it ships in release builds.
  - Lap `peakBoostPsi` is a mixed-source `read()` value — never display or export it.
- **Recorder tests and seams** — tests use the real Room database through Robolectric (`src/test/resources/
  robolectric.properties`; the first run downloads android-all). Seams: `DriveRecorder(…, dao, geocode, locationSource,
  onSummary)` — constructor defaults are production (`DriveFinalizeTest`); `stopDrive()` returns the finalization `Job`
  (tests join it); `recorder.lapTimer` stays assignable for tests.
- **STOP waits for the drive's out-of-loop point writes (#241)** — an active export (`snapshotActiveDrive`) or pause flush
  takes points from `DriveRun.pointsBuffer` and inserts them outside the loop. Those writers hold the run's
  `outsideLoopWrites` Mutex around take + insert; the finalize takes the stopped run's lock after the loop join, so the
  thumbnail input is complete (`generateAndCache` never rewrites a cached PNG). Loop flushes stay unlocked. Never wait on
  another drive's lock. `ThumbnailInputRegressionTest`.
  - Failed inserts count in `failedPointWrites` and are reported at STOP. Only a real failure counts: the insert's outcome
    is recorded inside `NonCancellable` and cancellation is rethrown (`StopFlushOutcomeTest`) — a `catch (Exception)`
    around `withContext(NonCancellable + IO)` in a cancelled `finally` counts the rethrown cancellation as a failure. The
    final flush keeps its `isRecording` update in a `finally`; `snapshotActiveDrive` rethrows cancellation. The knock
    insert has the same catch shape (false debug log only, not counted).
  - The buffer has one monitor (#238): every access goes through `DriveRun.addPoint` / `bufferedCount` / `takePoints`
    (copy + clear in one step); `outsideLoopWrites` does not cover the loop's GPS add or its batch / final drain. Inserts
    stay outside the monitor; never suspend inside it. `PointBufferRaceTest` swaps in a test-only `HookedPointBuffer` by
    reflection — keep `pointsBuffer` an `ArrayList`.
  - `DiagnosticExporter.share` ships a ZIP without drive files on a null snapshot, silently.
- **`lap_sessions` has no FK to `drives`** — after any drive deletion call `deleteOrphanLapSessions()` (laps cascade from
  lap_sessions). Sessions older than 30 days are pruned at each connection start (by design)
- **A position belongs to one drive (#311)** — `DriveState.currentLocation` is the recording drive's own last fix:
  `startDrive` starts without one, STOP and the loop end clear it (only for their own drive id). Anything that records or
  places a position reads `DriveRecorder.freshFix()` (that fix, at most `LapTimer.MAX_FIX_AGE_MS` old): a note / MARK is
  saved without a position and SET S/F is refused (the chip reads NO GPS for 2 s, `SfCue`; no snackbar, it would cover PAUSE / STOP) before the drive's first fix and in a
  GPS gap. The session knock log reads `freshFix()` too (none outside a drive or in a gap). TRIP's dot is `tripDot()`:
  while recording only the drive's fix; idle, only an idle fetch asked for after the last drive started (`idleFetch`
  stamps before the call). Never carry a fix from one drive into the next, and never
  record the platform's last-known fix as a drive position
- **A drive's job exists before it runs (CI failure on `3a8c572`)** — `startDrive` creates the drive's job lazily,
  assigns `recorderJob` (`@Volatile`) on the caller's thread, then starts it; the job prunes, inserts the row
  (`NonCancellable`, `DriveRun.driveId`), checks for cancellation, publishes the state and runs the loop. Never assign
  the job from inside its own coroutine or after a launch: a STOP that reads it first neither cancels nor joins the
  loop (the drive keeps recording, its final flush is lost unreported), and a second START starts a second drive.
  A STOP before the drive's state is published only cancels the start and deletes any row it wrote (`cancelStart`), never
  the previous drive's; a STOP with nothing under way does nothing; `startDrive` / `stopDrive` are `@Synchronized`.
  `DriveStartStopRaceTest` holds one DAO call per window
