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
