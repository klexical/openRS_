---
paths:
  - "**/android/*.gradle.kts"
  - "**/android/app/build.gradle.kts"
  - "**/android/gradle.properties"
  - "**/android/gradle/**"
  - "**/android/app/proguard*"
  - "**/android/scripts/**"
  - "**/android/CHANGELOG.md"
  - "**/android/app/src/test/resources/robolectric.properties"
  - "**/com/openrs/dash/update/**"
  - "**/com/openrs/dash/**/BuildInfo*"
  - "**/com/openrs/dash/**/*Version*"
  - "**/.github/workflows/**"
---
# openRS_ Known Gotchas — Build, release identity, CI, private builds, test runtime

- **Lint version is pinned above AGP** — `android/gradle.properties` sets `android.experimental.lint.version=8.13.0` because AGP 8.7.3's bundled lint crashes (`IncompatibleClassChangeError`) on the lint checks shipped in Compose BOM 2025.11 / Lifecycle. Don't remove it unless AGP is upgraded to ≥ 8.13. `abortOnError = false`, so lint findings never fail CI — only crashes do; check `app/build/reports/lint-results-debug.txt` for errors ([#196](https://github.com/klexical/openRS_/issues/196))
- **Release identity (#214)**
  - One definition at the top of `android/app/build.gradle.kts` (base / stage / candidate / versionCode) drives versionName,
    the Settings label (`<versionName> · Build <versionCode>`), the APK name, the expected tag, `BuildConfig.RELEASE_NAME` /
    `SOURCE_COMMIT`, export and crash metadata, the updater (`AppVersion.current()`) and the What's New gate (once per
    release name, never by itself on a nightly (#322); content by base version, never another version's notes). The build refuses `-PrcSuffix`.
  - A nightly (#362): `-PnightlyDate=YYYYMMDD` builds `<base>-nightly.YYYYMMDD` with versionCode `(YYYYMMDD − 20000000) × 10`
    (20261201 → 2612010); an empty date, one that is not a calendar date from 2000, or one after the build's UTC date is
    refused with the reason. Without the property a build is the literal release above; `releaseStage` is `"rc"` or `""`
    only (`"nightly"` in the file is refused), so a plain build is never refused. `scripts/test-release-identity.sh` checks
    both paths; `VersionIdentityTest` accepts either stage.
  - Bump the versionCode for every distributed production build, stable included. Debug builds carry
    `-dev+<commit>[.dirty]` — dirty = tracked changes or non-ignored untracked files under `android/`.
  - **The shared release recipe (#363):** `.github/workflows/release-apk.yml` (`workflow_call` only; inputs `sha`, `name`,
    `tag`, `nightly_date`) checks out the given commit, decodes the keystore, checks the API keys and runs
    `scripts/release-apk.sh`: `sha` a full 40-character SHA equal to the checked-out commit, tag = `android-v<name>`,
    `-PnightlyDate` passed only for a nightly (empty for an RC or stable: the build refuses an empty date), exactly one APK
    in the output, `openRS_v<name>.apk`, `verify-release.sh`. It has read access only and refuses a pull-request event; it
    hands the APK over as a one-day artifact `release-apk-<run id>` (one per run, overwritten by a full re-run), with its
    SHA-256 as the only output:
    because GitHub drops a job output that contains any secret's value, the caller builds the artifact and file names
    itself. CI's tag path for an `android-v` RC or stable (not `-nightly.`)
    calls it, and `publish-release` re-checks the digest, attests the file and attaches it. "Re-run failed jobs" keeps
    the run id, so a re-run caller finds the artifact while it lasts (one day); past that, re-run the whole workflow.
    `scripts/test-release-apk.sh` checks the build step; `scripts/test-release-workflows.rb` checks the workflows' shape
    (each tag job's exact condition and permissions, the recipe's one output, the digest check before attest and attach,
    no expression in any `run` line); CI's build job runs both, and the identity and verify-release tests.
  - **The nightly (#363):** `.github/workflows/nightly.yml` is scheduled for 08:17 UTC and runs from main's copy only
    (schedule and "Run workflow" exist only on the default branch), so every script and Gradle step runs in a checkout of staging's tip, the SHA the gate records,
    never the run's own commit or ref. Jobs: `mode` (the publish output from the file's `PUBLISH`, with no checkout and
    no repository code), `gate` (`scripts/nightly-gate.sh`: build only when `android/` changed since
    the newest published nightly, rc.8's for the first, and today has no nightly or tag; a read-only token is shown no
    draft releases), `checks` (ci.yml's build and lint commands and the release script tests), `release` (the recipe),
    `launch` (`scripts/start-emulator.sh` starts an API 36 emulator with `-gpu swangle` (under `swiftshader_indirect` it
    crashed on its host render thread, #363) under a wrapper that writes its exit status to
    `emulator.exit`, prints its version (from `emulator/source.properties`) and process id, writes `emulator.pid` beside
    `emulator.log`, records its first start time and image (`emulator.started`, `emulator.args`), asks the kernel to
    print fatal signals, prints the GLES renderer the log's first `gles_mode_selected:` names (on a failed boot too) and,
    after the boot, stops (the emulator with it) unless it is swangle; then `scripts/launch-check.sh`: Play Store disabled, a `free -m` baseline, log
    cleared before the cold start, same process after 10 s, no verifier rejection, fatal exception or ANR; every adb
    call named and limited to 60 s; a failure prints `adb devices`, the emulator processes, whether the `emulator.pid`
    process is alive and named by the kernel's kill lines, the device log's process and crash lines and its events
    buffer's kills and deaths (a line naming a key left out, the Maps and weather key values redacted by their shape),
    the host's memory and kernel kill lines, `df -h`, the emulator's exit messages from the whole `emulator.log` and its
    tail; the device-log, events, kernel and signature sections say "(no matching lines)", "(read cut short: …)" or
    "(could not read: …)" rather than nothing, and the output ends with `--- end of diagnostics`; also the emulator's exit
    status, its helper processes (only the emulator's own, `emulator/crashpad_handler` and `emulator/netsimd` by their
    place in the SDK, never another program's crash handler, which the dump decode does not wait for either; process id
    and name only: BSD pgrep's -l / -a print whole command lines), the host's
    load, the kernel's fault and fatal-signal lines (kind and thread name, a fatal signal's number; no error code,
    address, register or object name), and through `scripts/crash-facts.py` the crash database the emulator's log names
    (a count, and per file its kind, size and time after the cold start, no names) and the newest dump written after
    that attempt's cold start (its age, then signal, thread name, module + offset, fault address class: never a raw
    address, a register or the dump, the owner's rule), and a liveness trace every 2 s (the emulator's CPU % over each interval from `/proc`, and its resident kB); the check is repeated once in the same step only for a vanished emulator (a lost adb connection, the
    `emulator.pid` process gone, no emulator process left, the app's process unchanged at the trace's last answer; fail
    closed without a trace, and only on the decision's explicit OK) when the job's 15 minutes leave a second boot (`JOB_LIMIT_S`, pinned against the job's
    `timeout-minutes`), with each attempt headed and a summary line for any end but a plain pass, an argument
    failure included),
    `attest`, then `publish` and `cleanup` (#363), the only jobs with `contents: write`, each only when the mode's
    publish output is `'true'`, the run is on main and the gate said build. `publish` (`scripts/nightly-publish.sh`)
    stops quietly when today's tag already has a release in any state (its token is shown drafts) or a tag; else notes
    from the generate-notes API from the gate's `base_tag` (the list, the SHA-256 line, the changelog link), a draft
    pre-release at the commit whose notes end with a line naming the run and attempt (`GITHUB_RUN_ID`, `GITHUB_RUN_ATTEMPT`;
    the published notes do not carry it), one upload, exactly one asset whose bytes match the digest, then a pre-release with Latest
    off, its tag at the commit. `cleanup` runs after `publish` failed or was cancelled (`scripts/nightly-cleanup.sh`): it
    deletes only this run attempt's draft (at the recorded commit, its notes holding that line) and a tag only at the
    recorded commit, checked before anything is deleted, then requires the ref's 404; any other draft, and a published
    release, is never deleted (the step fails). Both scripts refuse any tag but a nightly's or the throwaway trial's
    (`test-nightly-cleanup-<date>`). A dry run (`PUBLISH` is `'false'`): the run stops after the attestation and nothing
    is published, so every night builds and leaves a one-day release-signed artifact and a public attestation.
    `scripts/test-nightly-gate.sh`, `scripts/test-launch-check.sh` (it also runs `scripts/test-crash-facts.py`),
    `scripts/test-start-emulator.sh`, `scripts/test-nightly-publish.sh` and `test-release-workflows.rb` pin it; CI's
    build job runs them.
  - `verify-release.sh` checks tag = versionName = filename, versionCode above every
    earlier tag, release signer (CI's keystore secrets sign with the published APKs' certificate, `c99b6f26…`). A nightly
    tag's code is the rule applied to the date in its name, for the tag under check and for earlier tags (their build file
    holds the literal they were built from); a nightly is `<X.Y.Z>-nightly.YYYYMMDD`. A stable above 39 (rc.8, the last
    literal code) takes slot 9 of a calendar date, whichever tags still exist; no such date lies after today (UTC)
    (`scripts/test-verify-release.sh`, #362). It fails on
    an earlier tag whose versionCode it can't read (exception list `LEGACY_TAGS`, empty) or a checkout without tags, and it
    rejects APKs up to rc.6 (they embed `2.2.7`, the convention then) — expected, not a defect of those releases.
- **Release API keys (#235)** — `apiKey(name)` in `app/build.gradle.kts`: `local.properties`, else the
  environment; optional for every build. The release recipe (`release-apk.yml`) maps the repository secrets `MAPS_API_KEY` /
  `OPENWEATHER_API_KEY` and runs `scripts/check-release-config.sh` (Maps required, weather
  a notice); `verify-release.sh` step 5 (`check-apk-maps-key.sh`) fails an APK with an absent / empty / placeholder Maps
  key, so it rejects keyless local builds and the published rc.6 asset (blank TRIP map). Scripts print lengths only — never a
  key value. `OPENWEATHER_API_KEY` is not configured as a repository secret, so published builds have no weather —
  expected, not a bug
- **RC maintenance assets** — the rc.7 release carries `openrs-2.2.7-rc.7-build38.apk` beside the tag's own asset. Its
  name is not the release's exact `openRS_v<name>.apk`, the only asset the updater takes (#364, `VersionIdentityTest`), so
  the in-app updater never offers it, and What's New is once per release NAME. The one-shot publisher that added it was
  deleted: a future maintenance asset needs a new, reviewed publisher.
- **Every release build needs an on-device launch (R8)** — R8 (AGP 8.7.3) once emitted an unverifiable
  `DrivePageKt$DrivePage$7` after a harmless edit (removing the CLUTCH row): ART threw `VerifyError … register has type
  Conflict` at the first DRIVE frame; debug and every JVM test were fine. Worked around by moving ECONOMY into
  `DriveEconomy` (625c43b). Keep very large composable lambdas split; smoke the minified APK (launch + every tab) and grep
  logcat for `Verifier rejected` before calling a release build good — clear the buffer *before* the cold start, or the
  grep doesn't cover it (the rendered DRIVE page is the real evidence). Tracked in #227
- **Fresh Play-services AVDs kill the app mid-test** — the Maps SDK's Dynamite loader SIGKILLs its own process when GMS is
  replaced during first-boot updates; `pm disable-user com.android.vending` on the throwaway AVD before smoke tests
- **Private builds: the safety rules**
  - Private minified smoke: `assembleRelease` with AGP's injected signing pointed at the debug keystore. Never copy signing
    files. The result is NOT distributable; use it only on a NEW disposable AVD. Trust an R8 mapping for a published trace
    only after the rebuilt dex is byte-identical to the published APK's.
  - Private release-signed build (owner approval per build): symlink the signing files, never copy them; remove the links
    after the build and delete the build outputs (they embed the keys); scrub logs by count only. The Maps "Authorization
    failure" logcat block prints the API key — redact it before filing.
  - Locally built release-signed APKs, and evidence archives that contain them, stay private: they embed the Maps key. The
    public APK is the one CI builds from the tag.
- **Robolectric tests:** test-only and pinned (4.14.1 + androidx.test:core 1.6.1), `sdk=35` + a plain `Application` in
  `app/src/test/resources/robolectric.properties`. SDK 35 needs only Java 17; Java 21 is needed from SDK 36. CI runs
  Temurin 17; the Android Studio JBR is a different JDK.
- **Give avdmanager and the emulator one existing AVD folder (#363)** — the emulator looks only in `$ANDROID_AVD_HOME`,
  `$ANDROID_SDK_HOME/avd` and `$HOME/.android/avd` ("Unknown AVD name" otherwise). Checked locally with
  `XDG_CONFIG_HOME` set, as GitHub's Ubuntu runner image sets it, `avdmanager` (cmdline-tools 12.0 and 20.0) resolves its
  AVD folder to `$XDG_CONFIG_HOME/.android/avd`, and uses `ANDROID_AVD_HOME` only when that folder already exists. So
  create the folder before the create and export it for both tools. `scripts/start-emulator.sh` does, checks
  `nightly.ini` is there before the start, and ends the boot wait when the emulator process is gone
  (`scripts/test-start-emulator.sh`).
- **adb in a script: read pidof's status, bound every call (#363)** — Android's `pidof` exits 1 when nothing matches and
  `adb shell` passes the status on, so `PID=$(adb shell pidof …)` under `set -e` ends the script silently when the
  process is missing; run `pidof … || true` on the device, so adb's own status speaks only for the connection. Checked
  locally (API 36.1 arm64 emulator): with the device gone `adb logcat -d` waits for it without end, and a call in
  flight when the emulator dies exits 255 with no message. `scripts/launch-check.sh` runs every adb call under
  `timeout 60` with a step name and prints what a failed night needs on any failure; the device log's lines only through
  a process / crash filter that drops any line naming a key and redacts the APK's two key values by their shape (Maps
  `AIza…`; weather `appid=…` or a standalone run of exactly 32 hex, longer hex runs kept), since a word list cannot be
  shown complete (`scripts/test-launch-check.sh`).
