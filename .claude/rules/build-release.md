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
    release name; content by base version, never another version's notes). The build refuses `-PrcSuffix`.
  - Bump the versionCode for every distributed production build, stable included. Debug builds carry
    `-dev+<commit>[.dirty]` — dirty = tracked changes or non-ignored untracked files under `android/`.
  - CI `publish-release` runs `android/scripts/verify-release.sh`: tag = versionName = filename, versionCode above every
    earlier tag, release signer (CI's keystore secrets sign with the published APKs' certificate, `c99b6f26…`). It fails on
    an earlier tag whose versionCode it can't read (exception list `LEGACY_TAGS`, empty) or a checkout without tags, and it
    rejects APKs up to rc.6 (they embed `2.2.7`, the convention then) — expected, not a defect of those releases.
- **Release API keys (#235)** — `apiKey(name)` in `app/build.gradle.kts`: `local.properties`, else the
  environment; optional for every build. `publish-release` maps the repository secrets `MAPS_API_KEY` /
  `OPENWEATHER_API_KEY` and runs `scripts/check-release-config.sh` (Maps required, weather
  a notice); `verify-release.sh` step 5 (`check-apk-maps-key.sh`) fails an APK with an absent / empty / placeholder Maps
  key, so it rejects keyless local builds and the published rc.6 asset (blank TRIP map). Scripts print lengths only — never a
  key value. `OPENWEATHER_API_KEY` is not configured as a repository secret, so published builds have no weather —
  expected, not a bug
- **RC maintenance assets** — the rc.7 release carries `openrs-2.2.7-rc.7-build38.apk` beside the tag's own asset. Its
  name stays outside the updater's `startsWith("openRS_") && endsWith(".apk")` matcher (`VersionIdentityTest`), so the
  in-app updater never offers it, and What's New is once per release NAME. The one-shot publisher that added it was
  deleted: a future maintenance asset needs a new, reviewed publisher.
- **Every release build needs an on-device launch (R8)** — on 2026-10-29 R8 (AGP 8.7.3) emitted an unverifiable
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
