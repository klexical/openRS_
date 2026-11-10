---
paths:
  - "**/android/app/src/**"
---
# openRS_ Known Gotchas — Android, any file

Moved verbatim from the tracked `CLAUDE.md` (section Known Gotchas) on 2026-11-06. Add a new gotcha of this area at the end; never reword an existing one here.

- **`MutableList.removeFirst()/removeLast()` crash below API 35** — with compileSdk 35 they resolve to `java.util.List.removeFirst` (Android 15+) and throw `NoSuchMethodError` on Android 9–14 (minSdk 28). Use `removeAt(0)` / `removeAt(lastIndex)`. `ArrayDeque.removeFirst()` is fine
- **Location permission** — always request `ACCESS_COARSE_LOCATION` together with `ACCESS_FINE_LOCATION` (Android 12+ requirement); recording still needs FINE granted
- **FileProvider authority = `${applicationId}.provider`** (release `com.openrs.dash.provider`, debug
  `com.openrs.dash.debug.provider`); code derives it from `ctx.packageName`. Never hard-code it — a fixed authority made
  any second build variant fail to install (`INSTALL_FAILED_CONFLICTING_PROVIDER`). Provider stays `exported=false`
- **Robolectric runs a plain `Application`** — `robolectric.properties` sets `android.app.Application`, so a test that
  composes a page reading `OpenRSDashApp.instance` (DRIVE, GARAGE) needs `@Config(application = OpenRSDashApp::class)`.
  Without it the class fails when run alone (`lateinit property instance has not been initialized`) and passes in a full
  run only because an earlier class set the instance
- **Compose tests tear down fully** — every Robolectric test that composes destroys each activity it builds and then
  drains the main looper (`pause().stop().destroy()`, then 8 × `idleFor(16 ms)`; in an `@After` when a lambda outlives the
  helper). A class that left an activity alive, or work queued at exit, stopped every later Compose test in the same JVM
  from recomposing after a state write (`LaunchControlDisplayTest`'s age-out case failed after the header test classes).
  A new composing test class needs the same teardown
