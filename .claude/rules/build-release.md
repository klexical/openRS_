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
    the newest published nightly, rc.8's for the first, and today has no nightly or tag; a day that has one is a quiet
    skip only when it is a clean published nightly (the publish step's checks of a published one, the digest from the
    release's own notes, the tag where its notes' changelog link points, which need not be the run's commit: a run
    after a same-day staging merge skips, Q115; today's one nightly is judged under its own name, so a stable cut's new
    base skips too, Q119), and fails the run otherwise, a second nightly that day included; a read-only token is
    shown no draft releases), `checks` (ci.yml's build and lint commands and the release script tests), `release` (the recipe),
    `launch` (`scripts/start-emulator.sh`, then `scripts/launch-check.sh`: an API 36 emulator under `-gpu swangle` (it
    crashed under `swiftshader_indirect`, #363), then a cold start checked for a verifier rejection, fatal exception or
    ANR, with one retry only for a vanished emulator (the owner's decision). Each script's header says what it checks
    and prints, and its tests pin that (`scripts/test-start-emulator.sh`, `scripts/test-launch-check.sh`,
    `scripts/test-crash-facts.py`; `JOB_LIMIT_S` against the job's `timeout-minutes` in `test-release-workflows.rb`).
    The diagnostics redact key values by their shape and never print a raw address, a register or a dump (the owner's
    rule)),
    `attest`, then `publish` and `cleanup` (#363), the only jobs with `contents: write`, each only when the mode's
    publish output is `'true'`, the run is on main and the gate said build. `publish` (`scripts/nightly-publish.sh`)
    stops quietly only when today's tag is already published and checks out as its last step checks it; a draft for
    today's tag (its token is shown drafts), a release that does not check out, or a tag with no release fails the step
    and deletes nothing, so "Re-run failed jobs" stays red ("Re-run all jobs" stays red too: the gate
    fails unless today's nightly is a clean published one, and a draft it cannot see fails this step again, Q114); else notes
    from the generate-notes API from the gate's `base_tag` (the list, the SHA-256 line, the changelog link), a draft
    pre-release at the commit whose notes end with a line naming the run and attempt (`GITHUB_RUN_ID`, `GITHUB_RUN_ATTEMPT`;
    the published notes do not carry it), one upload, exactly one asset whose bytes match the digest, then a pre-release with Latest
    off, its tag at the commit. `cleanup` runs after `publish` failed or was cancelled (`scripts/nightly-cleanup.sh`): it
    deletes only this run attempt's draft (at the recorded commit, its notes holding that line) and a tag only at the
    recorded commit, checked before anything is deleted, then requires the ref's 404; any other draft, and a published
    release, is never deleted (the step fails). Both scripts refuse any tag but a nightly's or the throwaway trial's
    (`test-nightly-cleanup-<date>`). `PUBLISH` is `'true'`, the same on main and staging (owner Q127). A dry run (a run
    on another ref, or any run while `PUBLISH` is `'false'`) stops after the attestation and publishes nothing: one that
    builds leaves a one-day release-signed artifact and a public attestation.
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
  environment; optional for every build. The release recipe (`release-apk.yml`) maps the release environments' secrets `MAPS_API_KEY` /
  `OPENWEATHER_API_KEY` and runs `scripts/check-release-config.sh` (Maps required, weather
  a notice); `verify-release.sh` step 5 (`check-apk-maps-key.sh`) fails an APK with an absent / empty / placeholder Maps
  key, so it rejects keyless local builds and the published rc.6 asset (blank TRIP map). Scripts print lengths only — never a
  key value. `OPENWEATHER_API_KEY` empty means published builds have no weather — expected, not a bug
- **Release secrets live in two environments (Q182)** — `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`,
  `KEY_PASSWORD`, `MAPS_API_KEY` and `OPENWEATHER_API_KEY` are held, the same six in each, by `release` (deployment refs:
  the branch main only, no reviewer: the nightly and main pushes) and `release-tag` (deployment refs: tags `android-v*`
  only, the owner as required reviewer: RC and stable). A run on a tag uses the tagged commit's own workflow files, so a
  tag run waits for the owner's approval; a ruleset blocks creating, moving and deleting `android-v*` tags except nightly
  tags (exclude pattern `android-v*-nightly.*`), with no bypass (Q191: a personal repository cannot list GitHub Actions
  as a bypass, so nightly tags are excluded and the nightly still tags itself; the daily audit (A3, being built) is to
  report any nightly-tag creation, move or deletion not made by `github-actions[bot]` and any nightly tag whose commit is
  not its release's). **Cutting an RC or stable tag:** the owner
  switches that ruleset off (Settings → Rules → Rulesets, Enforcement Disabled) for about a minute, pushes the tag, and
  switches it back to Active; nobody else cuts these tags. The owner approves a `release-tag` deployment only for a tag
  the owner just cut, never for a `-nightly.` tag (ci.yml skips nightly tags; an approval request for one is a red
  flag). `release-apk.yml` takes the environment name as a required input
  and its one job declares it; nightly.yml's `release` passes `release`, ci.yml's tag-path `release-apk` passes
  `release-tag`, and both pass exactly the six secrets by name. A called workflow sees a secret only when the caller
  passes its name (an unpassed environment secret is empty, with no error), and the environment's copy then wins; while
  a repository copy exists, a name missing from the environment falls back to it, so a green run proves the
  environments only once the repository copies are gone. Never `inherit`: it would also hand over the review token. A
  missing keystore fails the recipe (never an unsigned APK). GitHub checks the caller's run ref: the nightly runs under
  main's ref (it builds staging's tip, but the ref is main's), so a nightly "Run workflow" on another ref fails at the
  release job rather than dry-running. No other job declares either environment or names these secrets
  (`test-release-workflows.rb`)
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
- **Workflow lockfile (Q117, #363)** — `.github/workflows/actions.lock` pins every action of every workflow (direct and
  transitive, from composite actions) to a commit; GitHub runs that commit, and a job whose action has no entry fails at
  run time (the docs). A lockfile GitHub cannot read is not enforced: the run goes on unlocked and green
  (gh-actions-lock #125), unless the owner's Require lockfile policy is on. It is generated by
  `gh actions-lock --no-narrow` (the `github/gh-actions-lock` extension, v0.4.1) over the whole folder and never edited
  by hand: any `uses:` change (or a new workflow) is regenerated in the same commit. `--no-narrow` keeps `uses:` lines
  as written (`@v4`), which `test-release-workflows.rb` pins; without it the extension rewrites them to full tags. A
  called workflow is locked under its own key (`release-apk.yml`'s actions under `release-apk.yml`), and a run reads
  (inferred until the first locked runs show it) the lockfile of the workflow's own commit, so main and staging each
  carry their own file, generated on that branch. `test-release-workflows.rb` checks every remote action of every
  workflow is listed under that workflow's key at the ref it names, and that every pin a composite action itself uses
  has its own entry. A job that ran locked logs "Using locked action versions from the workflow's lockfile"; reading
  that line is how a run shows the lockfile was used, and its absence in a job that runs an action means it was not
- **Dependency verification (talk-list item 5)** — `android/gradle/verification-metadata.xml` holds a SHA-256 for every
  plugin, library and POM / module file the build resolves; Gradle fails a build whose download has no entry or another
  hash ("Dependency verification failed"). Any change to a plugin or dependency version (a Dependabot proposal included:
  Dependabot does not update this file) regenerates it in the same commit, from `android/`:
  `./gradlew --write-verification-metadata sha256 assembleDebug test lint assembleRelease --no-daemon --continue`
  (`assembleRelease` fails at `packageRelease` without a keystore; everything is resolved before that). Read the diff:
  a hash that changed for a version that did not is a tampered or re-published artifact, never accepted by
  regenerating. `aapt2` is per platform and a Linux run lists only `-linux.jar`; the `-osx.jar` entry is added by hand
  from Google Maven's `.sha256` file for each new AGP version. Cloud threads' Gradle reads a Maven Central mirror before
  `google()` (an init script in `GRADLE_USER_HOME`); CI reads `google()` first. Where the two publish different bytes
  under one coordinate (AGP's `transform-api`, #482), the entry carries Google's hash with Central's as `also-trust`;
  regenerating in a cloud thread records Central's alone and turns CI red. Sources and javadoc jars are trusted (an IDE fetches them;
  the build never does). Robolectric's android-all jars are downloaded by Robolectric itself, outside this file.
- **Dependabot (talk-list item 5, owner decision)** — `.github/dependabot.yml` (on main, #481) is held until after stable
  2.2.7: no Gradle or Actions proposal reaches staging while the outing build is tested. A Dependabot pull request is a
  proposal: its commits fail the commit-identity check, so a thread re-commits each update as the owner. The owner's
  rules for that: each Actions update regenerates `actions.lock` (keep Dependabot's own entries; when its PR does not
  change the lockfile, the round waits for the owner's Mac, where `gh actions-lock --no-narrow` runs); each Gradle update
  regenerates `verification-metadata.xml` in the same commit (above); a Gradle wrapper update changes
  `gradle-wrapper.jar`, a ZIP, so it waits for the privacy scan's OK label and the owner's words naming the PR.

## Releases: RCs, nightlies, stable and the release checklist

**RC publication:** merge the feature PR into staging → wait for CI on the staging merge commit →
`gh release create android-v<ver>-rc.N --target <full staging SHA> --prerelease --title "openRS_ v<ver>-rc.N"
--notes-file <notes>` (creates the lightweight tag; the tag push runs `publish-release`, which attaches the signed APK
to that release) → download the asset and check name, `apksigner verify --print-certs` (cert `c99b6f26…`), versionCode /
RC label, SHA-256, `gh attestation verify`. Never force-move a tag or delete an older RC release.

No further RC is cut after rc.8: builds after it are nightlies, published by the nightly pipeline (#363).

**Nightlies (#363).** `.github/workflows/nightly.yml` is scheduled for 08:17 UTC and can be run by hand ("Run
workflow"), but GitHub runs it only from the copy on main: it builds staging's tip through the shared
recipe `release-apk.yml`. Its gate
builds only when files under `android/` changed since the newest published nightly (rc.8 for the first) and today's UTC
date has no nightly. Its `PUBLISH` constant is true, the same on main and staging (owner Q127), so a run on main that
builds publishes; a run on another ref is a dry run and stops after the attestation, as is every run while `PUBLISH` is
false. Only two jobs can write contents, the publish (`scripts/nightly-publish.sh`: draft, one asset checked, then a pre-release, never
Latest) and the deletion of a failed publish's draft (`scripts/nightly-cleanup.sh`), and they run only when `PUBLISH` is
true and the run is on main. They run the scripts of the gate's commit, staging's tip (main has no copy), so while
`PUBLISH` is true a staging change to `nightly-publish.sh`, `nightly-cleanup.sh` or `nightly-gate.sh` changes how main
publishes: such a change is owner-only. GitHub disables a schedule in a public repository after
60 days without activity: a run that stops arriving may need re-enabling on the Actions page.

**A bad nightly.** On the owner's word (a deletion, owner-only) the integrator turns that release back into a draft and
then deletes its APK file from it; the draft, its tag and its notes stay as the record (Q122). A draft alone is not
enough: in the Q116 trial (run 44005524480) the old public download URL still answered HTTP 200 without a login after
the draft-back. The commands: `gh release edit <tag> -R klexical/openRS_ --draft=true`, then
`gh release delete-asset <tag> openRS_v<name>.apk -R klexical/openRS_ --yes`; then read back
`gh release view <tag> -R klexical/openRS_ --json isDraft,assets --jq '{isDraft, assets: [.assets[].name]}'`, which must
print `{"assets":[],"isDraft":true}`, and record what the old download URL answers without a login. The fix goes into
staging the normal way and the next night's build replaces it. This works only while the repository's
immutable-releases setting is off.

**Stable and the base after it.** Stable is cut by hand: staging merged into main, tag on main, with `releaseStage` empty
and a versionCode in slot 9 of its date. The same change sets the next base on staging, so the first nightly after stable
sorts above it.

**Release checklist (staging → main):**
0. Read the Sapphire staging ↔ main merge runbook first (`.claude/rules/sapphire.md`, "Release merge checklist": which side to
   take, the `ci.yml` hand-merge, the checks to run after the merge).
1. Scan open GitHub issues for any fixed by commits in the release (`gh issue list --state open` vs commit history)
2. Close standalone issues that shipped — comment with commit SHA
3. Update umbrella issue checklists — check off shipped sub-items with version annotation
4. Close umbrella issues only when ALL sub-items are checked off
5. Add/update the highlights entry in `WhatsNewDialog.kt` for the new base version — since #214 there is no fallback: a base
   version without highlights shows nothing (`shouldShowWhatsNew`). It auto-opens once per release name
   (`BuildInfo.releaseName`, e.g. rc.6 → rc.7 shows it), with content per base version — extend the entry per RC
   (Settings also opens it)
6. Keep Latest on the newest Android stable (`gh release edit android-vX.Y.Z --latest`; firmware never Latest): the Stable
   updater trusts `/releases/latest` only for an Android stable (#364)

## Changelog Format (`android/CHANGELOG.md`)

Every release follows the same structure.

**Entry template:**
```
- **Short title** — Description of what changed and why. ([#N](https://github.com/klexical/openRS_/issues/N))
```

**Rules:**
1. **Sections** — use only these four, in this order: `Added`, `Fixed`, `Changed`, `Removed`. No synonyms (not "Improved", "Refactored", "Stability", "Performance").
2. **Sub-sections** — entries after rc.8 go under the version's heading in sections labelled `(nightlies)`, above the rc.8 sections: `### Added (nightlies)`, `### Fixed (nightlies)`. Up to rc.8 a version with several RCs appended `(rc.N)` to the section header (`### Fixed (rc.3)`, with a short label for context: `### Added (rc.4 — visual polish)`); those sections stay as written.
3. **Issue links are mandatory** — every entry that originated from or addresses a GitHub issue MUST end with the issue link: `([#N](url))`. If it addresses a sub-item in an umbrella issue, use: `(addresses [#N](url))`. Bugs found and fixed in the same RC or nightly also get a tracking issue and link.
4. **One format for links** — always `[#N](https://github.com/klexical/openRS_/issues/N)`. Never bare `(#N)`, never `Fixes #N` or `Addresses sub-item in #N` inline.
5. **Entry length** — 1-3 sentences. Lead with what changed, then why. Include the file/class name when it helps locate the change. Do not write paragraphs — the commit message has the full detail.
6. **Never shorten or rewrite a shipped entry** — an entry that has shipped in a published build (a release candidate or a nightly) is not edited, condensed or rephrased. Add new entries; do not change the ones already published.
7. **Every fix shipped in a commit gets a changelog entry** — if a commit touches code that fixes something, it goes in the changelog, even if the commit's primary purpose was a feature.

## GitHub Release Notes

Release notes are a **condensed highlight reel**, not a copy of the changelog.

**Format:**
- "What's new in rc.N" — bullet list of user-facing highlights (1 line each, no implementation details)
- "Previous RCs" — one-liner per prior RC summarizing its theme, with issue links
- "Testing notes" — quick checklist of what to verify
- Link back to `android/CHANGELOG.md` for full detail

**Rule:** `CHANGELOG.md` is the authoritative record. Release notes summarize it. Never put detail in release notes that isn't in the changelog.
