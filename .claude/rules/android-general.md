---
paths:
  - "**/android/app/src/**"
---
# openRS_ Known Gotchas — Android, any file

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
- **A test that never composes must not write Compose state either** — a `mutableStateOf` written from a plain JVM /
  Robolectric test (a state holder driven without a composition) leaves work behind once any earlier class in the JVM
  has composed, and the next Compose class stops recomposing after a state write. Keep state holders that such tests
  drive free of snapshot state (`NoteFormState`, #253: its `isOpen` broke `ObserverNoteFormTest` only in a full run)
- **Comments cite what a reader of the repository can open** — an issue number or a file in this repository. A spec,
  review or design note that lives outside the repository is not a reference: say the rule itself in the comment
