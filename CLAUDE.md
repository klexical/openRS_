# openRS_

Native Android telemetry dashboard for the Ford Focus RS MK3.
Kotlin 2.0.21 + Jetpack Compose (Material3).

**Releases:** stable **v2.2.6** on main. Release candidates are tagged on staging; the current one is on the GitHub Releases
page and in `android/CHANGELOG.md`. This file holds durable guidance only: no release status and no work log.
Android app → `android/`  |  Firmware → `firmware/`  |  Sapphire (web dashboard) → `web/`  |  Docs → `docs/`  |  Feature pipeline → `docs/feature-roadmap.md`

## Source Layout

The per-file map of `android/app/src/main/java/com/openrs/dash/` is `.claude/rules/source-layout.md`; Claude Code loads it
when any file under `android/` is read or edited. It is not complete: list the folder for files it does not name.

## ECU Addresses

| ECU    | Request | Response | Session                         |
|--------|---------|----------|---------------------------------|
| PCM    | 0x7E0   | 0x7E8    | default                         |
| BCM    | 0x726   | 0x72E    | default                         |
| AWD    | 0x703   | 0x70B    | extended (0x10 0x03)            |
| ABS    | 0x760   | 0x768    | default                         |
| PSCM   | 0x730   | 0x738    | extended                        |
| FENG   | 0x727   | 0x72F    | extended                        |
| RSProt | 0x731   | 0x739    | extended                        |
| GFM    | 0x7D2   | 0x7DA    | default (DTC scanning only)     |
| IPC    | 0x720   | 0x728    | not polled                      |
| HVAC   | 0x733   | 0x73B    | not polled                      |

## Active CAN IDs (HS-CAN 500 kbps)

```
0x010  steering angle (Motorola 15-bit + sign)
0x040  SRS/airbag frame — byte2 bits 7:6 == 0xC0 → lamp ON (DigiCluster v1.0.73 reference)
0x070  torque at trans (Motorola 11-bit bits 37-47, offset −500 Nm) + RS suspension button (byte7 bit7)
0x076  throttle % (byte0 × 0.392) — ABSENT on some RS PCMs (the owner's car among them); use VehicleState.throttleOrPedalPct
0x080  accel pedal (byte0 bits 1–0 + byte1, Motorola 1|10, × 0.1 %), reverse (byte0 bit 5)
0x090  RPM (bytes 4-5) + baro (byte2 × 0.5 kPa)
0x0C8  gauge brightness (bits 0-4), e-brake (byte3 bit 6), ignition (byte2 bits 3-6)
0x0F8  engine oil temp (byte7−60°C), boost (byte5, gauge — see the boost gotcha), PTU temp (byte1−50°C)
       — one car, against ICM DID 0x6017 and PCM DID 0x070D (#229); the reverse of RS_HS.dbc's names; not validated on other
         cars. Recorded drives: can-decoding.md, "Oil / PTU byte mapping"
0x130  speed (bytes 6-7 BE × 0.01 kph)
0x138  clutch pedal % (17|10 × 0.1) — decoded for diagnostics only; NOT shown on DRIVE (#225)
0x160  longitudinal G (bits 48-57 LE)
0x180  lateral G + yaw rate + vertical G
0x190  wheel speeds FL/FR/RL/RR (4 × Motorola 15-bit × 0.011343 km/h)
0x1A4  ambient temp from MS-CAN bridge — byte4 **UNSIGNED** × 0.25°C (DigiCluster v1.0.46 fix); > 55°C rejected (#185)
0x1B0  drive mode nibble (byte6>>4); combine with 0x420 to distinguish Sport vs Track
0x1C0  ESC setting (2-bit at bit position 10): 0 Normal, 1 Off, 2 Sport; 3 unrecognized → unknown (#125, #288;
       can-decoding.md, "ESC names and raw 3"); while unknown the app sends no ESC command (owner decision, #288)
0x225  launch control (byte5 bit3) — MS-CAN per DigiCluster, never seen in an HS capture; diagnostics only, never on
       screen (#289)
0x230  gear (4-bit) — does NOT broadcast on this car
0x252  brake pressure (12-bit Motorola)
0x2C0  AWD actual left/right torque Nm — RS_HS.dbc AWDmsg02 19|12 / 51|12 Motorola (MSB-first 20 / 52); requested L/R at
       3|12 / 35|12 not decoded; drivetrain reference point undocumented (#226). The DBC excerpt in
       android/app/src/test/resources/dbc/ checks the extraction arithmetic only, not the physical meaning. Front/rear %
       is WITHDRAWN everywhere (not a physical split); saved dashboard cells read "WITHDRAWN …"
       (`DashLayout.WITHDRAWN_FIELDS`), never deleted
0x2F0  coolant + intake air temp (10-bit each)
0x300  battery/charging fault — reserved; bit mapping unknown
0x340  PCM ambient (byte7 signed × 0.25°C) — NOT TPMS
0x360  odometer (bytes [3:5] 24-bit BE) + engine state (byte7; one car, #328) — byte0 is NOT an engine status
       (can-decoding.md, "0x360 engine state is byte 7")
0x380  fuel level (Motorola 10-bit × 0.4%, clamped 0-100)
0x420  drive mode detail + launch control flag (byte6/7, ~600 ms broadcast; bit0=0→Sport, bit0=1→Track). Byte 6 bit 2
       (DBC LaunchControlStatus, unverified against the cluster) is the only source of "LC" (connection-link.md, "Launch
       control has one rule", #289)
0x435  per-zone airbag status — **INTENTIONALLY NOT DECODED**: crosstalks with passenger-occupancy and false-triggers SRS lamp (DigiCluster v1.0.73)
```

## VehicleState Rules

**Sentinel values:**
- `-99.0` = not yet received — temps (coolant, oil, tires, charge air, PTU, RDU, cabin, etc.)
- `-1.0` / `-1L` = not yet polled — OBD scalars (oilLife, tirePressures, odometer, batterySoc, etc.)
- `sparkAdvance` = `-99.0` until received — negative advance is valid (#205)
- `0L` = not yet received — timestamps (tpmsLastUpdate)
- `null` = unknown — Boolean? fields (rduEnabled, pdcEnabled, fengEnabled, lcArmed, assEnabled, escOffButtonPressed, IPC/HVAC warning lamps)

**Rules:**
- VehicleState is **immutable** — always `.copy(...)`, never mutate directly
- `CanDecoder.decode()` returns `null` if data is too short — callers must null-check
- `CanDecoder.resetSessionState()` MUST be called on every new connection (resets modeDetail420 + has420Arrived)
- `genericValues: Map<String, Double>` — populated by PidRegistry for catalog DIDs without a dedicated field
- HVAC/IPC fields in VehicleState are scaffolded and not reliably populated — treat them as unknown

## OBD Mode 22 Response Layout

```
data[0] = PCI  (0x0N, N = payload length)
data[1] = 0x62 (positive response SID)
data[2] = DID high byte
data[3] = DID low byte
data[4] = B4   ← first data byte
data[5] = B5,  data[6] = B6 …
```

## Known Gotchas

The gotchas live in `.claude/rules/` as path-scoped Claude Code rules, one file per area. In Claude Code each file loads on
demand when a file matching its `paths:` globs is read or edited; the globs start with `**/` so they also match from a
workspace that holds this repository in a subfolder. Whether they load by themselves depends on the session, so open
every file whose globs match a file you will touch before the first edit, even where it may already have loaded. Rules
for editing them:

- A new gotcha goes at the end of its area's file as `- **Title** — rule`; a cross-cutting one goes to
  `android-general.md`. An entry states the rule, its reason and the test or issue that holds the evidence. Dates, commit
  ids, measurements and the story of the fix go to the issue; status ("pending", "undecided", "follow-up") is written as
  the decision or a pointer to the issue that holds it.
- When an entry's facts change (a decision landed, a mapping was confirmed), correct the entry in a commit whose message
  names each corrected line. Restructuring an entry without changing its meaning (splitting it into sub-bullets, removing
  history) is a docs change of its own, read by QA against the old text line by line.
- A rule loads through the file tools (Read / Write / Edit, or `cat` / `head` of one file); a `git diff` or `git show` loads
  nothing — when reviewing a change that way, read the area's rule file explicitly first.
- Keep every rules file under 40,000 characters (`wc -m`): the Claude Code docs give that as the per-file limit for
  path-scoped rules and subdirectory CLAUDE.md files on a 200K context window (150,000 on 1M) — split an area into two
  files rather than trim it.
- The ECU / CAN ID tables, VehicleState rules and the Mode 22 layout above stay in this file: every session needs them.
- A rule file holds what no check enforces (the owner's decision, best-practices talk list item 3). A rule that a unit
  test or a CI check fails on when it is broken is not kept here: the test is the rule, and its name and KDoc carry the
  reason. An entry stays when its rule binds code not yet written ("any new writer …"), when it records evidence or an
  unverified point (hardware, emulator-only, a source read but not run), when an owner decision behind it is not in a
  test, or when `CODING_STANDARDS.md` points at it or at its file. A pointer that names a file alone, with no entry
  title, keeps every entry in that file.
- `CODING_STANDARDS.md` points at rule files, by file alone or by file and title, and the CI reviewer reads the rule file
  a standard points to. Renaming or removing an entry it reaches changes a review rule: change `CODING_STANDARDS.md` in
  the same round (it is a twin, see "Branch / Release Workflow").

| File | Area | Loads when a matching file is read / edited |
|---|---|---|
| `source-layout.md` | Source Layout (the android/ source map) | any file under `android/` |
| `android-general.md` | Android, any file | any file under `android/app/src/` |
| `can-decoding.md` | CAN decoding and VehicleState | `CanDecoder.kt`, `VehicleState.kt`, `DemoSimulator.kt`, `PullTracker.kt`, `Knock*.kt`, `data/Drive*.kt`, `OilPtuMapping*`, `DriveRecorder.kt`, their tests, `test/resources/dbc/` |
| `obd-polling.md` | OBD Mode 22 polling, decoders and freshness | `Obd*.kt`, `PcmPollSchedule.kt`, `PidRegistry.kt`, `NrcTracker.kt`, `CanDecoder.kt`, `SlcanConnection.kt`, `CanDataService.kt`, `ObdMerge.kt`, `FreshnessGate.kt`, `VehicleState.kt`, `*ModeProvenance*`, `PowerPage.kt`, `CustomDashPage.kt`, the PID assets, their tests |
| `boost.md` | Boost / TIP rule (#207) | any `*Boost*` file, `DrivePage.kt`, `CustomDashPage.kt`, `HudOverlayService.kt`, `DriveRecorder.kt`, `KnockScatter.kt`, `KnockCapture.kt`, `ui/trip/`, `web/src/lib/boost.ts` |
| `connection-link.md` | Connection, SLCAN link, service ownership, firmware commands | `Slcan*.kt`, transports, `AdapterProbe.kt`, `AdapterNetworkFinder.kt`, `ReceptionStall.kt`, `ObdResponseParser.kt`, `PidRegistry.kt`, `FirmwareApi.kt`, `DriveCommand.kt`, `EscCommand.kt`, `CanDataService.kt`, `Link*.kt`, `ConnectionSetup.kt`, `CanDecoder.kt`, `ObdMerge.kt`, `DemoSimulator.kt`, `DriveModeDock.kt`, `MorePage.kt`, `SettingsSheet.kt`, `BleDeviceDraft.kt`, `MainActivity.kt`, `ObdConstants.kt`, `ForegroundController.kt`, `firmware/`, their tests |
| `ble.md` | BLE GATT transport and picker | `Ble*.kt`, `BleDevicePickerDialog.kt`, `SlcanConnection.kt`, `*Ledger*`, `WriteOutcome.kt`, their tests |
| `recorder-trip.md` | Drive recorder, laps and the TRIP tab | `DriveRecorder.kt`, `data/Drive*.kt`, lap files, `ui/trip/` (incl. `TripPage.kt`), `TripPreview.kt`, `MainActivity.kt`, `DriveExportBuilder.kt`, their tests |
| `diagnostics.md` | DTC scans, DID prober, diagnostic export | `diagnostics/`, `DiagPage.kt`, `SlcanConnection.kt`, `*Ledger*`, `CanDataService.kt`, `DidProberSection.kt`, `PidBrowserSection.kt`, `data/Dtc*.kt`, their tests |
| `ui-compose.md` | Compose UI (layout, theme, navigation); theme colours, AppSettings defaults | any file under `ui/` or `res/`, `HudOverlayService.kt` |
| `thermal.md` | Thermal readiness, temperature freshness, drive thermal record | any `*Thermal*` file, `TempsPage.kt` (home of `TempAvail`), `ChassisPage.kt`, `DrivePage.kt`, `DriveRecorder.kt`, their tests |
| `demo-mode.md` | Demo mode (debug builds) | any file under `com/openrs/dash/` (its rules bind every writer of live telemetry) |
| `build-release.md` | Build, release identity, CI, private builds, test runtime; RCs, nightlies, stable, the release checklist, changelog format, release notes | Gradle files, `android/scripts/`, `android/CHANGELOG.md`, `robolectric.properties`, `update/`, `BuildInfo*`, `*Version*`, `.github/workflows/` |
| `sapphire.md` | Sapphire, the web dashboard | any file under `web/` |

## Assets

```
android/app/src/main/assets/pids/combined_catalog.json   — merged PID catalog
android/app/src/main/assets/pids/forscan_modules.json    — FORScan catalog
android/app/src/main/res/raw/dtc_database.json           — Ford DTC descriptions
android/app/src/main/res/raw/google_map_style_dark.json  — Google Maps dark style (openRS_ palette)
```

Regenerate the FORScan catalog: `python3 android/scripts/gen_forscan_catalog.py` (writes forscan_modules.json only)

## Build

```bash
cd android
./gradlew assembleDebug                    # → openRS_v{ver}-staging-debug.apk
./gradlew assembleRelease                  # → openRS_v{releaseName}.apk (e.g. openRS_v2.2.7-rc.7.apk; set in app/build.gradle.kts)
./gradlew test                             # unit tests, both variants
./gradlew lint                             # report: app/build/reports/lint-results-debug.txt
bash scripts/install-debug.sh              # quick build + ADB install + launch (extra args → am start)
```

### Emulator / demo-mode workflow

```bash
export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
export PATH="$PATH:$HOME/Library/Android/sdk/platform-tools"     # adb is not on PATH by default
adb devices -l                                                  # the running emulator's serial

cd android
bash scripts/install-debug.sh                     # build → install → cold-start (normal, auto-connects)
bash scripts/install-debug.sh --es demo driving   # same, but straight into demo mode
# APK: app/build/outputs/apk/debug/openRS_v<releaseName>-staging-debug.apk   App ID: com.openrs.dash.debug

# Control a running debug app (onNewIntent needs --activity-single-top):
adb shell am start --activity-single-top -n com.openrs.dash.debug/com.openrs.dash.ui.MainActivity --es demo warning
#   demo = idle | driving | warning | loss | cooldown | launch | pause | reset | off
#   demo = at:<seconds> — replay the current scenario and pause at that time (repeatable screenshot frames;
#          DRIVING at:9.5 = mid-pull 3rd gear, at:2.5 = idle on the brake; WARNING at:50 = all alerts)
#   demo = collapse | expand — fold the control strip to a 2 dp amber line (header DEMO pill toggles it too)
#   trippreview = history | selected | recording | paused | summary | compare | replay | export | off — TRIP with in-memory sample drives
#          summary:N — sample drive N's summary (thermal records: 1 Custom · 2 STREET → RACE · 3 TRACK · 4 legacy · 5 none ·
#          6 early record: START missing, END above the peak → "—")
#          (demo/TripPreview.kt; negative ids, every write path no-ops for them; nothing reaches Room)
# Smaller phone check: adb shell wm size 1080x2160 && adb shell wm density 480  (360×720 dp; `reset` both after)
# Cold start with an extra: am start -S -n … --es demo idle   (without -S the intent is only brought to front)
```

In demo mode the header pill always says DEMO (`DEMO · RCN` during LOSS) — never drop the simulation label.

On a pull request, the label `emulator-smoke` runs the same demo-mode walk on an API 36 emulator in GitHub Actions
(`.github/workflows/emulator-smoke.yml`, `android/scripts/demo-smoke.sh`; `.claude/rules/build-release.md`).

Keys: `android/local.properties` (`MAPS_API_KEY`, `OPENWEATHER_API_KEY`) or the same names in the environment — optional for
local builds (#235); `android/keystore.properties` for release signing.
Version display format: `openRS_ v<releaseName> · Build <versionCode>` (Settings) — the release name comes from
`app/build.gradle.kts` (see the Release identity gotcha).

## Tests

Unit tests live in `android/app/src/test/java/com/openrs/dash/` (JVM, plus Robolectric on SDK 35 for the Room-backed tests) and
in `web/src/` (`*.test.ts`, `*.test.tsx`) for Sapphire. Run one class with `./gradlew test --tests '*ClassName*'`. For the
per-class catalog, list the folder.

Counts are not kept here. To count: `./gradlew clean test lint --no-build-cache` on Temurin 17 (a plain run can restore
results from the Gradle build cache); one test is skipped by decision (`#251 outlier policy`). Sapphire:
`web/node_modules/.bin/vitest run` and `tsc -b`.

## Issue tracking and field evidence

- When a new defect, safety-sensitive control ambiguity, or unresolved telemetry discrepancy is found, search open *and closed* issues, then update the matching issue or file a narrowly scoped one. Do this at triage, even when it came up during an RC. A private evidence note alone does not complete triage.
- Record the build/source, transport, expected vs observed behavior, evidence class (owner report, captured, reproduced, or hypothesis), priority, component, verification owner and acceptance test. Use only sanitized facts on GitHub. Keep raw captures, human/device identifiers, routes and secrets local.
- If GitHub writes are unavailable or not yet approved, keep a private pending ledger; reconcile each entry to an actual issue URL and status as soon as access returns. Do not report drafts as filed. Link issues in fixes, regression evidence and changelog. Carry unresolved work into the next milestone; close only the shipped and verified scope.
- For vehicle controls, a matching broadcast after a request is an observation, not proof of causation when factory controls or another client could have set that state.

## Branch / Release Workflow

```
feature/* → staging → main          (code, tests, android/CHANGELOG.md)
docs/*    → main    (direct)        (README.md, assets/, docs/, firmware/README.md)
                                    CLAUDE.md, .claude/rules/ and GLOSSARY.md follow the code route (staging)
Release tags: android-v*  (triggers CI signing + GitHub Release)
RCs: one pre-release per RC tag (android-v<ver>-rc.N), tagged on staging; stable tags on main; rc.8 is the last RC
Nightlies (#363): android-v<ver>-nightly.YYYYMMDD, from staging's tip
```

**main** carries stable Android and Sapphire; nightlies build from staging's tip. Sapphire reaches main `web/`-only.

`CODING_STANDARDS.md` (the standards `.github/workflows/claude-review.yml` reviews against) is kept byte-identical on
staging and main: a change lands on both in one round (two PRs), or on staging alone with main taking it at stable.

Commits carry the committer's GitHub noreply e-mail (`<id>+<login>@users.noreply.github.com`). The owner's account blocks
pushes that would publish a private address (GH007), and fixing that afterwards means rewriting commits. The Commit
identity check (`.github/workflows/commit-identity.yml`) fails a pull request whose new commits are not the owner's
noreply identity or carry a `Claude-Session:` line.

Release procedures, the release checklist, the changelog format and release notes: `.claude/rules/build-release.md`.
Release facts live in `android/CHANGELOG.md` and on the Releases page, open work in the issue tracker; this file takes no
dated status or work-log entries.

Docs branches (root `README.md`, `assets/`, `docs/`) branch from main and PR to main, never through staging. `CLAUDE.md`,
`.claude/rules/` and `GLOSSARY.md` describe the code on staging, so they take the code route, and a change that makes one
of them wrong corrects it in the same branch. Documentation under `android/` that describes a code change travels with
that code (`android/README.md` and `android/CONTRIBUTING.md` included).

## Sapphire (`web/`)

The Sapphire web dashboard's guidance (data contract, design, scripts, testing notes, the staging ↔ main merge runbook) is in `.claude/rules/sapphire.md`.
