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

Moved verbatim from the tracked `CLAUDE.md` (section Known Gotchas) on 2026-11-06. Add a new gotcha of this area at the end; never reword an existing one here.

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
  summary); a new drive starts with empty oil / coolant peaks. The loop feeds `LapTimer` every recorded fix (#237): a lap
  = a gate crossing between two fresh (≤ 2 s, monotonic), ordered fixes ≤ 5 s apart, time interpolated; 30 m departure
  before re-arming; a missing bearing uses the segment course (never 0°); the timer belongs to one drive —
  `takeLaps(driveId)` closes it and refuses that drive's late fixes. One `LapTimer` logcat line per fix while a line is set
  (fix time, age, x / y metres from S/F, `b=`, verdict — no coordinates) + counts at STOP: ships in release for rc.7, make
  it debug-only before stable (reviewer N-a). Unmeasured on phones: the 2 s fix age (late / batched fixes → no laps; read
  `STALE=` at STOP). Lap `peakBoostPsi` is a mixed-source `read()` value — never display or export it. Tests use the real Room database through
  test-only Robolectric 4.14.1 on SDK 35 (`src/test/resources/robolectric.properties`; the first run downloads android-all)
  **One timer per recorder (#238 review F3, `71bd7eb`):** TRIP takes `remember(recorder) { recorder.lapTimerForUi() }` and never
  installs its own — `remember { LapTimer() }` + assignment let a recreated activity (rotation) put a new, unarmed timer on the
  recorder mid-drive, so the line vanished and STOP lost the laps before it. `recorder.lapTimer` stays assignable for tests
  (`LapRecreationTest`)
  `stopDrive()` takes its STOP time (= `endTime`), end readings, fuel economy and `takeLaps(driveId)` at the call and
  returns the finalization `Job` (tests join it); the reverse-geocode name lookup runs before, outside the transaction. A
  paused or disconnected fix is never fed to the timer — the loop calls `LapTimer.breakTrack(driveId)` instead. LapTimer's
  mutating methods are `@Synchronized` (the overlay getters `currentLapElapsedMs` / `deltaVsBestMs` are not — #246).
  Test seams: `DriveRecorder(…, dao, geocode, locationSource, onSummary)`; constructor defaults are production
  (`DriveFinalizeTest`)
- **STOP waits for the drive's out-of-loop point writes (#241, fix branch `d3264c8`)** — an active export
  (`snapshotActiveDrive`) or pause flush takes points from `DriveRun.pointsBuffer` and inserts them outside the loop; STOP
  read the thumbnail input while that insert ran (3 / 23 points, partial PNG cached for good — `generateAndCache` never
  rewrites). Those writers hold the run's `outsideLoopWrites` Mutex around take + insert; the finalize takes the stopped
  run's lock after the loop join. Loop flushes stay unlocked. Failed inserts count in `failedPointWrites` and are reported
  at STOP. Never wait on another drive's lock. `ThumbnailInputRegressionTest` (6). `DiagnosticExporter.share` still ships
  a ZIP without drive files on a null snapshot, silently (pre-existing). **False "not saved" at STOP (QA 2026-11-02; FIXED
  `20df014`, in #238 `2033fda`, QA-ACCEPTED — the insert's outcome is recorded inside `NonCancellable`, only a real
  failure counts, cancellation rethrown; `StopFlushOutcomeTest`):** the loop's final `flushBuffer` in the cancelled finally — `withContext(NonCancellable + IO)` completes the
  insert, then rethrows the cancellation, and `catch (Exception)` counts it, so nearly every STOP logs "Point flush failed …
  JobCancellationException" + "Drive N ended with 1 failed point write(s) — those points are not saved" (logcat + a
  DiagnosticLogger REC event in diagnostic ZIPs) while Room holds every point (`QaStopFlushWarningTest`, evidence
  `rc7-229-oil-ptu-2026-11-02/qa/partB/`) — that was the bug. The loop's batch flush now runs at the end of the point, the final flush
  keeps its `isRecording` update in a `finally`, and `snapshotActiveDrive` rethrows cancellation. The knock insert has the same
  catch shape (false debug log only, not counted) and was left unchanged. **The buffer itself has one monitor (#238
  review F2, `06fe4c7`):** every access goes through `DriveRun.addPoint` / `bufferedCount` / `takePoints` (copy + clear
  in one step). `outsideLoopWrites` never covered the loop's GPS add or batch / final drain, so an export's take could
  lose a fix added between copy and clear, or store a batch twice (inherited from rc.6). Inserts stay outside the
  monitor; never suspend inside it. `PointBufferRaceTest` (test-only `HookedPointBuffer` swapped in by reflection — keep
  `pointsBuffer` an `ArrayList`)
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
