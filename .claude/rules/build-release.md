---
paths:
  - "**/android/*.gradle.kts"
  - "**/android/app/build.gradle.kts"
  - "**/android/gradle.properties"
  - "**/android/gradle/**"
  - "**/android/app/proguard*"
  - "**/android/scripts/**"
  - "**/android/CHANGELOG.md"
  - "**/android/changelog.d/**"
  - "**/android/app/src/test/resources/robolectric.properties"
  - "**/com/openrs/dash/update/**"
  - "**/com/openrs/dash/**/BuildInfo*"
  - "**/com/openrs/dash/**/*Version*"
  - "**/.github/workflows/**"
---
# openRS_ Known Gotchas — Build, release identity, CI, private builds, test runtime

- **Lint version is pinned above AGP** — `android/gradle.properties` sets `android.experimental.lint.version=8.13.0` because AGP 8.7.3's bundled lint crashes (`IncompatibleClassChangeError`) on the lint checks shipped in Compose BOM 2025.11 / Lifecycle. Don't remove it unless AGP is upgraded to ≥ 8.13. `abortOnError = false`, so lint findings never fail CI — only crashes do; check `app/build/reports/lint-results-debug.txt` for errors ([#196](https://github.com/klexical/openRS_/issues/196))
- **Release identity (#214)** — one definition at the top of `android/app/build.gradle.kts` (base / stage / candidate /
  versionCode) drives every version string: versionName, the Settings label, the APK name, the expected tag,
  `BuildConfig`, export and crash metadata, the updater and the What's New gate. Change a version there only.
  - Bump the versionCode for every distributed production build, stable included. A nightly's name and code come from
    `-PnightlyDate=YYYYMMDD` (#362); a stable above 39 takes slot 9 of a calendar date (#362). The rules are in the build
    file and `verify-release.sh`, pinned by `scripts/test-release-identity.sh`, `scripts/test-verify-release.sh` and
    `VersionIdentityTest`.
  - `verify-release.sh` rejects APKs up to rc.6 (they embed `2.2.7`, the convention then): expected, not a defect of
    those releases.
  - The release recipe (`.github/workflows/release-apk.yml`, `scripts/release-apk.sh`) and the nightly
    (`.github/workflows/nightly.yml` and its scripts) are described by each script's header; `scripts/test-release-workflows.rb`
    pins the workflows' shape and CI's build job runs every script test. Read those before changing either.
  - GitHub drops a job output that contains any secret's value, so the recipe's one output is the APK's SHA-256 and the
    caller builds the artifact and file names itself. "Re-run failed jobs" keeps the run id, so a re-run finds the
    one-day artifact while it lasts; past that, re-run the whole workflow.
- **Release API keys (#235)** — `apiKey(name)` in `app/build.gradle.kts`: `local.properties`, else the environment;
  optional for every build. `verify-release.sh` (`check-apk-maps-key.sh`) fails an APK with an absent / empty /
  placeholder Maps key, so it rejects keyless local builds and the published rc.6 asset (blank TRIP map). Scripts print
  lengths only — never a key value. `OPENWEATHER_API_KEY` empty means published builds have no weather — expected, not a
  bug
- **Release secrets live in two environments (Q182)** — the six signing and key secrets are held, the same six in each,
  by `release` (deployment refs: the branch main only, no reviewer: the nightly and main pushes) and `release-tag`
  (deployment refs: tags `android-v*` only, the owner as required reviewer: RC and stable). A tag run uses the tagged
  commit's own workflow files, so it waits for the owner's approval; a ruleset blocks creating, moving and deleting
  `android-v*` tags except nightly tags, with no bypass (Q191: a personal repository cannot list GitHub Actions as a
  bypass, so nightly tags are excluded and the nightly tags itself).
  - **Cutting an RC or stable tag:** the owner switches that ruleset off (Settings → Rules → Rulesets, Enforcement
    Disabled) for about a minute, pushes the tag, and switches it back to Active; nobody else cuts these tags. The owner
    approves a `release-tag` deployment only for a tag the owner just cut, never for a `-nightly.` tag (ci.yml skips
    nightly tags; an approval request for one is a red flag).
  - A called workflow sees a secret only when the caller passes its name (an unpassed environment secret is empty, with
    no error); while a repository copy exists, a name missing from the environment falls back to it, so a green run
    proves the environments only once the repository copies are gone. Never `inherit`: it would also hand over the
    review token. GitHub checks the caller's run ref, so a nightly "Run workflow" on another ref fails at the release job
    rather than dry-running. No job but the two release callers declares either environment or names these secrets
    (`test-release-workflows.rb`)
- **RC maintenance assets** — the rc.7 release carries an extra APK whose name is not the release's exact
  `openRS_v<name>.apk`, so the updater never offers it (#364). Its one-shot publisher was deleted: a future maintenance
  asset needs a new, reviewed publisher.
- **Every release build needs an on-device launch (R8)** — R8 (AGP 8.7.3) once emitted an unverifiable class after a
  harmless edit: ART threw `VerifyError … register has type Conflict` at the first DRIVE frame while debug and every JVM
  test were fine. Keep very large composable lambdas split; smoke the minified APK (launch + every tab) and grep logcat
  for `Verifier rejected` before calling a release build good — clear the buffer *before* the cold start, or the grep
  doesn't cover it (the rendered DRIVE page is the real evidence). Tracked in #227
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
- **Robolectric needs only Java 17 at SDK 35** — `sdk=35` in `app/src/test/resources/robolectric.properties`; SDK 36
  would need Java 21. CI runs Temurin 17; the Android Studio JBR is a different JDK.
- **Emulators and adb in scripts (#363)** — `avdmanager` and the emulator look for AVDs in different folders on GitHub's
  runner, and adb calls can hang or fail silently once a device is gone. `scripts/start-emulator.sh` and
  `scripts/launch-check.sh` handle both; their headers say how (checked locally, not documented), and
  `scripts/test-start-emulator.sh` / `scripts/test-launch-check.sh` pin it. A new script that drives an emulator copies
  their handling.
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
  used.
- **Never open two pull requests from one head** — GitHub attaches check runs to the head commit, not to a pull
  request, and a required check takes any run of its name on that commit, so a second pull request from the same head
  into another base can pass on the first's runs (Sapphire's skip run decided against staging counted for main). One
  head, one pull request: a twin gets a branch of its own, and a retargeted pull request is decided again (`web-ci.yml`
  runs on `edited`; the review of #473 / #474).
- **The emulator smoke on a pull request (Q139)** — `.github/workflows/emulator-smoke.yml` runs `scripts/demo-smoke.sh`
  (what it checks and the device quirks it works around: its header; `scripts/test-demo-smoke.sh` pins it) only for the
  label `emulator-smoke`. Read its result from the Actions runs of `emulator-smoke.yml` for the PR's head SHA, the newest
  run whose job did not skip: adding any other label starts a run whose job is skipped, under the same check name, and
  the PR's check list can show that "skipped" over a red smoke. It uploads nothing; screenshots as run artifacts are the
  owner's call (what leaves the runner). It reads the UI text tree, not pixels, so a covered node still reads as shown.
  In a dump, a tab's "<NAME> tab" description is on the fake first child Compose adds to a merging node
  (`SemanticsNode.emitFakeNodes`), and the tab's `selected` state is on that child's parent (#472's runs on GitHub's API 36
  image). CI's build job does not run its test
- **Unit-test heap is Gradle's default 512 MB (#505)** — `android/app/build.gradle.kts` sets no `maxHeapSize` for the
  unit-test JVM, and the suite ends well under it. A run that slows to tens of minutes is the heap, not a hung test: read
  a `jcmd <pid> GC.class_histogram` before raising it. A Compose test in a tall window (`h1800dp`) kept its themes and
  layout nodes live for the rest of the suite and ran CI's release run out of heap (#505): compose a long section in a
  vertical scroll in the usual 720 dp window instead (`UnitsFitTest`)

## Releases: RCs, nightlies, stable and the release checklist

No further RC is cut after rc.8: builds after it are nightlies (#363). After any release tag's run: download the asset
and check its name, `apksigner verify --print-certs` (cert `c99b6f26…`), versionCode, SHA-256 and `gh attestation verify`.
Never force-move a tag or delete an older RC release.

**Nightlies (#363).** `nightly.yml` runs only from the copy on main (GitHub keeps schedules and "Run workflow" to the
default branch) and builds staging's tip with staging's scripts. Its `PUBLISH` constant is `'true'`, the same on main and
staging (owner Q127), so while it is true a staging change to `nightly-publish.sh`, `nightly-cleanup.sh` or
`nightly-gate.sh` changes how main publishes: such a change is owner-only. A dry run (another ref, or `PUBLISH` false)
that builds still leaves a one-day release-signed artifact and a public attestation. The launch check's one retry, only
for a vanished emulator, is the owner's decision, and its diagnostics never print a key value, a raw address, a
register or a dump (the owner's rule). GitHub disables a schedule in a public repository after 60 days without
activity: a run that stops arriving may need re-enabling on the Actions page.

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
7. The stable change on staging folds the changelog fragments first, before it dates the heading:
   `python3 android/scripts/changelog.py fold`, which leaves only `android/changelog.d/README.md`. The change that sets
   the next base puts the pointer line (it starts `> Entries not yet folded in`) back under the new version's heading

## Changelog Format (`android/CHANGELOG.md`)

Every release follows the same structure.

**Fragments, not the file.** A pull request writes each new entry as its own file under `android/changelog.d/`, never
as a line in `CHANGELOG.md`, so two pull requests never touch the same lines. The name is
`android/changelog.d/<added|fixed|changed|removed>/<issue>-<slug>.md`: the folder is the `(nightlies)` section, the
number is the issue the entry links. The file holds one or more entry lines in the template below and the rules below
apply to each. `python3 android/scripts/changelog.py print` shows the changelog as stable will fold it. Stable's change
folds them in (release checklist, step 7), and a fold gives the same bytes as the same lines appended to the end of
their sections by hand, in issue order. CI's "Changelog fragments" job runs `changelog.py`: it refuses a malformed
fragment, and on a pull request into staging an entry typed straight into a `(nightlies)` section of `CHANGELOG.md`
(only a warning for a pull request whose merge base predates `changelog.d/README.md`).
Lines outside those sections, such as a stable known-limitations section, are written in `CHANGELOG.md` as before. A
nightly's "Full changelog" link names `CHANGELOG.md` at its commit, and the pointer line under the version heading
leads from there to `changelog.d/` at the same commit; a fold drops that line.

**Entry template:**
```
- **Short title** — Description of what changed and why. ([#N](https://github.com/klexical/openRS_/issues/N))
```

**Rules:**
1. **Sections** — use only these four, in this order: `Added`, `Fixed`, `Changed`, `Removed`. No synonyms (not "Improved", "Refactored", "Stability", "Performance").
2. **Sub-sections** — entries after rc.8 go, as fragments, under the version's heading in sections labelled `(nightlies)`, above the rc.8 sections: `### Added (nightlies)`, `### Fixed (nightlies)`. Up to rc.8 a version with several RCs appended `(rc.N)` to the section header (`### Fixed (rc.3)`, with a short label for context: `### Added (rc.4 — visual polish)`); those sections stay as written.
3. **Issue links are mandatory** — every entry that originated from or addresses a GitHub issue MUST end with the issue link: `([#N](url))`. If it addresses a sub-item in an umbrella issue, use: `(addresses [#N](url))`. Bugs found and fixed in the same RC or nightly also get a tracking issue and link.
4. **One format for links** — always `[#N](https://github.com/klexical/openRS_/issues/N)`. Never bare `(#N)`, never `Fixes #N` or `Addresses sub-item in #N` inline.
5. **Entry length** — 1-3 sentences. Lead with what changed, then why. Include the file/class name when it helps locate the change. Do not write paragraphs — the commit message has the full detail.
6. **Never shorten or rewrite a shipped entry** — an entry that has shipped in a published build (a release candidate or a nightly) is not edited, condensed or rephrased, in `CHANGELOG.md` or in a fragment. Add new entries; do not change the ones already published.
7. **Every fix shipped in a commit gets a changelog entry** — if a commit touches code that fixes something, it goes in the changelog, even if the commit's primary purpose was a feature.

## GitHub Release Notes

Release notes are a **condensed highlight reel**, not a copy of the changelog.

**Format:**
- "What's new in rc.N" — bullet list of user-facing highlights (1 line each, no implementation details)
- "Previous RCs" — one-liner per prior RC summarizing its theme, with issue links
- "Testing notes" — quick checklist of what to verify
- Link back to `android/CHANGELOG.md` for full detail

**Rule:** `CHANGELOG.md` is the authoritative record. Release notes summarize it. Never put detail in release notes that isn't in the changelog.
