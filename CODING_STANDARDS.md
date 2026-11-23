# Coding standards for review

What a reviewer can check from a pull request's diff. Where a rule lives in full elsewhere, its line points there, and
the pointer wins when the two differ. Every pointer means the staging branch's file, CLAUDE.md included. When the file a
line points to is not in the checkout (a pull request to main, until the stable merge brings the rule files there), it is
not read: the line's own words are the rule. Lines with no pointer are this file's own team rules, and this file is their
source. A pointer names its file and its entry: a quoted title is the start of that entry's bold title in the rule file,
or a heading there, and the reviewer reads that entry.
Comment only on lines the diff adds or changes, once per finding, with the file and line and the rule broken.

## Telemetry and VehicleState (`android/`)

- `VehicleState` is immutable: changes go through `.copy(...)`, never a mutation. CLAUDE.md, "VehicleState Rules".
- Sentinels: `-99.0` = a temperature (and `sparkAdvance`) not yet received; `-1.0` / `-1L` = an OBD scalar not yet polled;
  `0L` = a timestamp not yet received; `null` = unknown for `Boolean?` fields. A sentinel is never shown, recorded or
  averaged as a reading, and a decoded 0 is a reading unless its area's rule file says otherwise (#223: battery voltage
  0x0304). CLAUDE.md, "VehicleState Rules".
- `CanDecoder.decode()` returns `null` for short data: every caller null-checks. CLAUDE.md, "VehicleState Rules".
- No new constructor parameter on `VehicleState` without grouping or moving fields: the class is near the JVM's 255-slot
  limit (`VehicleStateSlotsTest`). `.claude/rules/can-decoding.md`, "VehicleState slot ceiling".
- A Mode 22 decoder reads only the bytes the reply declares, starting at `data[4]`, and stamps freshness for its field.
  CLAUDE.md, "OBD Mode 22 Response Layout"; `.claude/rules/obd-polling.md`, "Freshness stamps", "Adding a polled DID".
- A CAN byte or bit mapping, scale or offset changed in a diff names its evidence (an issue, a capture, a reference) and
  comes with a decoder test. CLAUDE.md, "Active CAN IDs"; `.claude/rules/can-decoding.md`, the entry for that CAN ID
  when it has one.

## Boost (#207)

- Live boost readouts use `BoostSession.gauge(vs, nowMs)` (TIP only, or "—"); recorded values use `read()`. No fallback to
  CAN 0x0F8 in a TIP gap, no held value, freshness by wall clock. `.claude/rules/boost.md`, "Live pressure =",
  "Live gauge name vs recorded source labels".
- TIP is `raw / 128` kPa absolute; `/903.81` is a psi formula and is not reintroduced. `.claude/rules/boost.md`,
  "TIP is `raw / 128` kPa absolute".

## Freshness, recording and demo mode

- The drive recorder stores polled values only through `FreshnessGate.takeIfFresh()`, never a held value.
  `.claude/rules/recorder-trip.md`, "Recorder only stores fresh polled values".
- Temperature availability goes through `vs.tempAvail(value, tag)`. `.claude/rules/thermal.md`, "Temperature freshness".
- A new live-only readout goes blank when `!vs.isConnected`. `.claude/rules/ui-compose.md`, "Live-only readouts go
  blank".
- A new writer of live telemetry to Room or an export has a `DemoMode.isActive` guard; the simulator writes only fields a
  real decoder writes. `.claude/rules/demo-mode.md`, "Simulated data never persists", "Demo simulates only fields a
  real decoder writes".
- Colour marks state: a reading turns Warn / Orange only when it crosses an existing limit. `.claude/rules/ui-compose.md`,
  "Colour = state on DRIVE / THERMAL".

## Privacy in exports, logs and the repository

- Nothing identifying leaves the app unmasked: the VIN is masked in exports (three layers), the adapter's device ID is
  masked (#263), a MAC or adapter name never reaches a log line (`LogText.sanitize`, `SlcanTransport.kind`).
  `.claude/rules/diagnostics.md`, "VIN masking is export-only", "Text from outside the app";
  `.claude/rules/connection-link.md`, "No adapter reply text reaches a log".
- An added export section is additive; a field or file removed or renamed, or a type, unit, scale or meaning changed,
  bumps `EXPORT_SCHEMA_VERSION`. `.claude/rules/diagnostics.md`, "Export schema version". UI timing in the export is
  counts only (#421). `.claude/rules/diagnostics.md`, "UI timing in the export".
- Committed fixtures are synthetic: no real export, raw capture, VIN, MAC, location, absolute time or personal path. Export
  fixtures carry synthetic times only. `.claude/rules/diagnostics.md`, "Public export fixtures carry synthetic times
  only"; CLAUDE.md, "Issue tracking and field evidence".
- No secret, key value or keystore in the diff; keys come from `local.properties` or the environment. CLAUDE.md, "Build".

## Tests

- A fix comes with a regression test that fails before the fix and passes after, on the production path.
- Tests protect data integrity: byte and bit mappings, scales, sentinel edges, parsing, what a recorder stores. A test that
  only repeats a constant or a display string adds little; say so rather than ask for one.
- Emulator, mock or demo results are labelled as such. A claim about the car names its evidence class: owner report,
  captured, reproduced, or hypothesis.

## Code and comments

- A comment cites what a reader of the repository can open: an issue number or a file here.
  `.claude/rules/android-general.md`, "Comments cite what a reader of the repository can open".
- `MutableList.removeFirst()` / `removeLast()` crash below API 35: use `removeAt(0)` / `removeAt(lastIndex)`.
  `.claude/rules/android-general.md`, "`MutableList.removeFirst()/removeLast()` crash below API 35".
- A change that makes `CLAUDE.md`, a rule file or `GLOSSARY.md` wrong corrects it in the same branch. CLAUDE.md, "Branch /
  Release Workflow".

## Changelog (`android/CHANGELOG.md`)

- Sections only `Added`, `Fixed`, `Changed`, `Removed`, in that order; entries after rc.8 go under `(nightlies)` sections.
- Each entry is 1–3 sentences; when it has an issue, it ends with its link, `([#N](https://github.com/klexical/openRS_/issues/N))`
  or `(addresses [#N](https://github.com/klexical/openRS_/issues/N))`; never a bare `(#N)`.
- A shipped entry is never edited; every fix in a commit has an entry. `.claude/rules/build-release.md`, "Changelog Format".
- A new entry is a fragment, `android/changelog.d/<added|fixed|changed|removed>/<issue>-<slug>.md`, not a line added to a
  `(nightlies)` section of `android/CHANGELOG.md`. `.claude/rules/build-release.md`, "Changelog Format".

## Sapphire (`web/`)

- Chart values: a sentinel or NaN becomes a gap (null); a genuine zero is kept (`web/src/lib/readings.ts`).
- Tests that touch `localStorage` stub it per test. `.claude/rules/sapphire.md`, "Testing notes".

## Workflows (`.github/`)

- A new or changed `uses:` is in `.github/workflows/actions.lock` (`gh actions-lock --no-narrow`). `.claude/rules/build-release.md`,
  "Workflow lockfile".
- No `pull_request_target`, except `privacy-scan.yml` (main's code only, no checkout, nothing of the pull request runs);
  a job that holds a secret does not run code from a fork.
