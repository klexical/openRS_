# openRS_

Native Android telemetry dashboard for the Ford Focus RS MK3.
Kotlin 2.0.21 + Jetpack Compose (Material3).

**Releases:** stable **v2.2.6** on main. Release candidates are tagged on staging; the current one is on the GitHub Releases
page and in `android/CHANGELOG.md`. This file holds durable guidance only: no release status and no work log.
Android app → `android/`  |  Firmware → `firmware/`  |  Sapphire (web dashboard) → `web/`  |  Docs → `docs/`  |  Feature pipeline → `docs/feature-roadmap.md`

## Source Layout

The per-file map of `android/app/src/main/java/com/openrs/dash/` is `.claude/rules/source-layout.md`; it loads when any file
under `android/` is read or edited.

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
0x080  accel pedal (bits 0-9 LE × 0.1%), reverse (bit 5)
0x090  RPM (bytes 4-5) + baro (byte2 × 0.5 kPa)
0x0C8  gauge brightness (bits 0-4), e-brake (byte3 bit 6), ignition (byte2 bits 3-6)
0x0F8  engine oil temp (byte7−60°C), boost (byte5, gauge — see the boost gotcha), PTU temp (byte1−50°C)
       — confirmed on one car against ICM DID 0x6017 and PCM DID 0x070D (#229); the reverse of RS_HS.dbc's names and of
         builds before rc.7 (they read oil = byte1, PTU = byte7). Drives carry `DriveEntity.oilPtuMapping` (null = recorded
         before the fix, never relabelled). Not validated on other cars
0x130  speed (bytes 6-7 BE × 0.01 kph)
0x138  clutch pedal % (17|10 × 0.1) — decoded for diagnostics only; NOT shown on DRIVE (#225)
0x160  longitudinal G (bits 48-57 LE)
0x180  lateral G + yaw rate + vertical G
0x190  wheel speeds FL/FR/RL/RR (4 × Motorola 15-bit × 0.011343 km/h)
0x1A4  ambient temp from MS-CAN bridge — byte4 **UNSIGNED** × 0.25°C (DigiCluster v1.0.46 fix); > 55°C rejected (#185)
0x1B0  drive mode nibble (byte6>>4); combine with 0x420 to distinguish Sport vs Track
0x1C0  ESC setting (2-bit at bit position 10): 0 Normal, 1 Off, 2 Sport — Ford's names (2018 RS supplement); the value → name
       mapping is CAN-mapped only (RS_HS.dbc VAL_ 448, #125). 3 = unrecognized (the name "Launch" rests only on the community
       DBC; never seen) → unknown, as is no frame yet; while unknown the app sends no ESC command (owner decision, #288)
0x225  launch control (byte5 bit3) — MS-CAN per DigiCluster, never seen in an HS capture; diagnostics only, never on
       screen (#289)
0x230  gear (4-bit) — does NOT broadcast on this car
0x252  brake pressure (12-bit Motorola)
0x2C0  AWD actual left/right torque Nm — RS_HS.dbc AWDmsg02 19|12 / 51|12 Motorola (MSB-first 20 / 52); requested L/R
       at 3|12 / 35|12 not decoded; drivetrain reference point undocumented (#226).
       DBC excerpt + source: android/app/src/test/resources/dbc/ — a second decoder checks the extraction arithmetic only,
       not the physical meaning. Front/rear % (rear ÷ 0x070 engine torque) is WITHDRAWN everywhere — not a physical split;
       saved dashboard cells read "WITHDRAWN …" (`DashLayout.WITHDRAWN_FIELDS`), never deleted
0x2F0  coolant + intake air temp (10-bit each)
0x300  battery/charging fault — reserved; bit mapping unknown
0x340  PCM ambient (byte7 signed × 0.25°C) — NOT TPMS
0x360  odometer (bytes [3:5] 24-bit BE) + engine status (byte0)
0x380  fuel level (Motorola 10-bit × 0.4%, clamped 0-100)
0x420  drive mode detail + launch control flag (byte6/7, ~600 ms broadcast; bit0=0→Sport, bit0=1→Track). The flag, byte 6
       bit 2 (DBC LaunchControlStatus, unverified against the cluster), is the only source of the one "LC" (header, DRIVE,
       GARAGE), shown while a documented 0x420 is ≤ 1.5 s old on this connection (`data/LaunchControl.kt`, #289)
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

The gotchas live in `.claude/rules/` as path-scoped Claude Code rules, one file per area. Each file loads on demand when a
file matching its `paths:` globs is read or edited; the globs start with `**/` so they also match from a workspace that
holds this repository in a subfolder. Rules for editing them:

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

| File | Area | Loads when a matching file is read / edited |
|---|---|---|
| `source-layout.md` | Source Layout (the android/ source map) | any file under `android/` |
| `android-general.md` | Android, any file | any file under `android/app/src/` |
| `can-decoding.md` | CAN decoding and VehicleState | `CanDecoder.kt`, `VehicleState.kt`, `DemoSimulator.kt`, `PullTracker.kt`, `Knock*.kt`, `data/Drive*.kt`, `OilPtuMapping*`, `DriveRecorder.kt`, their tests, `test/resources/dbc/` |
| `obd-polling.md` | OBD Mode 22 polling, decoders and freshness | `Obd*.kt`, `PcmPollSchedule.kt`, `PidRegistry.kt`, `NrcTracker.kt`, `CanDecoder.kt`, `SlcanConnection.kt`, `CanDataService.kt`, `ObdMerge.kt`, `FreshnessGate.kt`, `VehicleState.kt`, `*ModeProvenance*`, `PowerPage.kt`, `CustomDashPage.kt`, the PID assets, their tests |
| `boost.md` | Boost / TIP rule (#207) | any `*Boost*` file, `DrivePage.kt`, `CustomDashPage.kt`, `HudOverlayService.kt`, `DriveRecorder.kt`, `KnockScatter.kt`, `KnockCapture.kt`, `ui/trip/`, `web/src/lib/boost.ts` |
| `connection-link.md` | Connection, SLCAN link, service ownership, firmware commands | `Slcan*.kt`, transports, `ReceptionStall.kt`, `ObdResponseParser.kt`, `PidRegistry.kt`, `FirmwareApi.kt`, `DriveCommand.kt`, `EscCommand.kt`, `CanDataService.kt`, `Link*.kt`, `ConnectionSetup.kt`, `CanDecoder.kt`, `ObdMerge.kt`, `DemoSimulator.kt`, `DriveModeDock.kt`, `MorePage.kt`, `SettingsSheet.kt`, `BleDeviceDraft.kt`, `MainActivity.kt`, `ObdConstants.kt`, `ForegroundController.kt`, `firmware/`, their tests |
| `ble.md` | BLE GATT transport and picker | `Ble*.kt`, `BleDevicePickerDialog.kt`, `SlcanConnection.kt`, `*Ledger*`, `WriteOutcome.kt`, their tests |
| `recorder-trip.md` | Drive recorder, laps and the TRIP tab | `DriveRecorder.kt`, `data/Drive*.kt`, lap files, `ui/trip/` (incl. `TripPage.kt`), `TripPreview.kt`, `MainActivity.kt`, `DriveExportBuilder.kt`, their tests |
| `diagnostics.md` | DTC scans, DID prober, diagnostic export | `diagnostics/`, `DiagPage.kt`, `SlcanConnection.kt`, `*Ledger*`, `CanDataService.kt`, `DidProberSection.kt`, `PidBrowserSection.kt`, `data/Dtc*.kt`, their tests |
| `ui-compose.md` | Compose UI (layout, theme, navigation) | any file under `ui/` or `res/`, `HudOverlayService.kt` |
| `thermal.md` | Thermal readiness, temperature freshness, drive thermal record | any `*Thermal*` file, `TempsPage.kt` (home of `TempAvail`), `ChassisPage.kt`, `DrivePage.kt`, `DriveRecorder.kt`, their tests |
| `demo-mode.md` | Demo mode (debug builds) | any file under `com/openrs/dash/` (its rules bind every writer of live telemetry) |
| `build-release.md` | Build, release identity, CI, private builds, test runtime | Gradle files, `android/scripts/`, `android/CHANGELOG.md`, `robolectric.properties`, `update/`, `BuildInfo*`, `*Version*`, `.github/workflows/` |

## Theme Colors

v2.2.7 "Daylight" ships three discrete palettes selected via `setThemeMode(ThemeMode)`.
NIGHT is the default; DAY is legibility-tuned for bright ambient light; ULTRA is pure-black AMOLED.
AUTO resolves to DAY from 06:00 to 18:59 local (`HOUR_OF_DAY in 6..18`), NIGHT otherwise — it does not follow the
system dark/light setting. `Frost` is `Ink` — adaptive primary text.
Accents and semantic colors (Ok/Warn/Orange) shift per mode to preserve contrast.

```
NIGHT:  Bg #05070A  Surf #0A0D12  Surf2 #0F141C  Surf3 #141B26  Brd #162030  Dim #7A9AB8  Mid #B0D0E8  Ink #E8F4FF
DAY:    Bg #EFF3F8  Surf #FFFFFF  Surf2 #E4EAF2  Surf3 #D4DCE8  Brd #B8C4D4  Dim #556374  Mid #293748  Ink #080D16
ULTRA:  Bg #000000  Surf #050505  Surf2 #080808  Surf3 #0C0C0C  Brd #1A2230  (Dim/Mid/Ink share NIGHT values)
Accent  NIGHT #0091EA / DAY #0064B0    Orange  NIGHT #FF4D00 / DAY #D93900
Ok      NIGHT #00FF88 / DAY #00A85A    Warn    NIGHT #FFCC00 / DAY #E09500
```

Adaptive alpha helpers (`borderAlpha()`, `textMutedAlpha()`, `pillBgAlpha()`) scale dim-on-dark tunings
up on DAY so borders, muted labels, and status-pill fills stay above WCAG contrast on the white surface.

RS paint themes (`themeId`): `cyan`=Nitrous Blue, `red`=Race Red, `orange`=Deep Orange,
`grey`=Stealth Grey, `black`=Shadow Black, `white`=Frozen White

## AppSettings Defaults

```
Speed: MPH  |  Temp: °F  |  Boost: PSI  |  Tire: PSI  |  TireLowPsi: 30
MeatPi USB (C3): 192.168.80.1:80 (WebSocket)    MeatPi Pro (S3): 192.168.0.10:35000 (TCP)
ScreenOn: true  |  AutoReconnect: true  |  MaxDiagZips: 5
AdapterType: "MEATPI_USB"  (alt: "MEATPI_PRO")
ConnectionMethod: "WIFI"  (alt: "BLUETOOTH")
EdgeShiftLight: false  |  EdgeShiftColor: "accent"  |  EdgeShiftIntensity: "high"  |  EdgeShiftRpm: 6800
AutoRecordDrives: false  |  MaxSavedDrives: 50
UpdateChannel: "stable"  (alt: "beta")
ThemeMode: NIGHT  (alt: DAY / AUTO / ULTRA)
Prefs file: "openrs_settings"
```

**Adapter vs connection method:** `adapterType` identifies the hardware (determines protocol: WebSocket for USB, TCP for Pro). `connectionMethod` selects the transport (WiFi or BLE GATT). Both adapters support both WiFi and Bluetooth.

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
Nightlies (#363, once nightly.yml publishes): android-v<ver>-nightly.YYYYMMDD, from staging's tip
```

Android RCs ship from **staging**; **main** carries stable Android (v2.2.6) and Sapphire. Sapphire may reach main web/-only
without the Android RC.

Commits carry the committer's GitHub noreply e-mail (`<id>+<login>@users.noreply.github.com`). The owner's account blocks
pushes that would publish a private address (GH007), and fixing that afterwards means rewriting commits.

**RC publication:** merge the feature PR into staging → wait for CI on the staging merge commit →
`gh release create android-v<ver>-rc.N --target <full staging SHA> --prerelease --title "openRS_ v<ver>-rc.N"
--notes-file <notes>` (creates the lightweight tag; the tag push runs `publish-release`, which attaches the signed APK
to that release) → download the asset and check name, `apksigner verify --print-certs` (cert `c99b6f26…`), versionCode /
RC label, SHA-256, `gh attestation verify`. Never force-move a tag or delete an older RC release.

No further RC is cut after rc.8: builds after it are nightlies, which come once the nightly pipeline publishes (#363).

**Nightlies (#363).** `.github/workflows/nightly.yml` is scheduled for 08:17 UTC and can be run by hand ("Run
workflow"), but GitHub runs it only from the copy on main: once it is there, it builds staging's tip through the shared
recipe `release-apk.yml`. Its gate
builds only when files under `android/` changed since the newest published nightly (rc.8 for the first) and today's UTC
date has no nightly. It runs in dry-run mode while its `PUBLISH` constant is false: it stops after the attestation,
before any draft, and holds no token that can write contents. GitHub disables a schedule in a public repository after
60 days without activity: a run that stops arriving may need re-enabling on the Actions page.

**A bad nightly.** On the owner's word the integrator turns that release back into a draft (the tag and the file stay).
The fix goes into staging the normal way and the next night's build replaces it. This works only while the repository's
immutable-releases setting is off.

**Stable and the base after it.** Stable is cut by hand: staging merged into main, tag on main, with `releaseStage` empty
and a versionCode in slot 9 of its date. The same change sets the next base on staging, so the first nightly after stable
sorts above it.

**Release records.** Release facts live in `android/CHANGELOG.md` and on the GitHub Releases page; open work lives in the
issue tracker. This file takes no dated status or work-log entries.

Docs branches skip staging entirely — branch from main, PR to main.
Never route README.md, assets/, or docs/ through staging. `CLAUDE.md`, `.claude/rules/` and `GLOSSARY.md` are the exception:
they describe the code on staging, so a change that makes one of them wrong corrects it in the same branch. Documentation
under `android/` that describes a code change travels with that code, through staging. The "never" line above means the
repository's root `README.md`, `assets/` and `docs/`; `android/README.md` and `android/CONTRIBUTING.md` follow the clause
before it when they describe the code change.

**Release checklist (staging → main):**
0. Read the Sapphire staging ↔ main merge runbook first (see the Sapphire section: which side to take, the `ci.yml` hand-merge,
   the checks to run after the merge).
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

## Sapphire — Web Analytics Dashboard (`web/`)

Post-session analysis dashboard.
Named after the Ford Sierra Sapphire RS Cosworth + Nitrous Blue gemstone connection.

**Tech stack:** Vite + React 19 + TypeScript + Tailwind CSS + Recharts + Zustand.
Pure client-side (no server). Deployed as static files to GitHub Pages.

**Data contract:** Android app exports ZIP → user imports into Sapphire via drag-and-drop.
ZIP contains: `drive_*.csv` and `drive_summary_*.txt` (legacy `trip_*` names still import), `diagnostic_detail_*.json`, `diagnostic_summary_*.txt`, `did_probe_*.csv`.
Sapphire parses these and stores sessions in browser IndexedDB.

**Design:** Dark "Void" aesthetic matching the openRS_ in-app palette. Nav rail + panel layout.

```
web/
  src/
    components/
      layout/          — Shell, NavRail, Header
      panels/          — DashboardPanel, TripPanel, DiagnosticsPanel, ComparePanel,
                         SessionsPanel, ImportPanel, SettingsPanel
      charts/          — TimeSeriesChart, GpsMap, ComparisonChart, GaugeChart, Sparkline,
                         ModeTimeline (Recharts + Leaflet)
      ui/              — MetricCard, SectionLabel, SearchBar, ExportDropdown, DeltaCard,
                         TpmsSummaryCard, EmptyState
    store/
      index.ts         — Zustand store (sessions, panels, compare, nav); panel + session remembered (`sapphire_view`)
      settings.ts      — Settings store (units, theme) — localStorage persisted
    lib/
      import.ts        — ZIP import pipeline (drive_*.csv + drive_summary_*)
      db.ts            — IndexedDB (idb) CRUD
      format.ts        — Formatters + useUnitFormatters() hook
      units.ts         — Metric/imperial conversion (speed, distance, temp, boost, tire, fuel)
      export.ts        — CSV/JSON session export + browser download
      compare.ts       — Multi-session KPI deltas + time normalization
      mapColors.ts     — GPS map color mode thresholds (fixed cyan, never the paint accent)
      readings.ts      — Chart values: sentinel / NaN → null (gap), genuine zeros kept; per-corner latest TPMS
      gforce.ts        — G-force scatter downsampling (keeps the lat-G peaks)
    styles/            — Design tokens (openRS_ palette)
    types/             — Session, trip, diagnostic, vehicle state types
```

Scripts are in `web/package.json` (`dev`, `build`, `preview`, `test`, `lint`); CI installs with `npm ci`.

**Testing notes:**
- Sapphire tests touching `localStorage` (store / App): Node 25's own `localStorage` global (non-functional without
  `--localstorage-file`) shadows jsdom's — stub one per test (`vi.stubGlobal('localStorage', …)`, see `App.test.tsx`)
- Sapphire browser smoke (prod build, chart gaps / paints / remembered view): Playwright, same setup as
  `web/scripts/idb-check/check.mjs`; build with `--outDir` outside the repo; IndexedDB is per origin, so each run
  (random port) starts empty
- Sapphire import contract: `web/src/lib/import.androidExport.test.tsx` imports complete Android exports from
  `__fixtures__/android-export-rc6/`; regenerate with `OPENRS_EXPORT_SAMPLES=<dir> ./gradlew test --tests '*DriveExportThermalTest'`

**Release merge checklist (staging ↔ main):** a one-time runbook for the stable promotion (which side to take per file,
the `ci.yml` hand-merge, the checks to run after the merge). Read it before merging staging into main:
`git show 743230fb:CLAUDE.md`, section Sapphire.

*Separate follow-ups (noted when the merge checklist was written; not release blockers):*
- `DriveRecorder` stores points only from location updates, so a drive without GPS records no telemetry (the export
  fix for drive CSVs without GPS covers seeded / legacy data and any later recorder change).
- TRIP's map draws a marker at a no-GPS drive's stored coordinates — only reachable with seeded data (real no-GPS
  drives have no points).
- thermalRecord visualisation in Sapphire (the block imports and is ignored today).
- Thermal Soak is a stacked chart: while one layer is missing, the layers above it sit lower (stack total). Inherent to
  stacking (main's 0-floor shifts them the same way); a non-stacked view would be a design change.
