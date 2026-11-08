<div align="center">
  <img src="assets/images/openrs-wordmark.svg" width="320" alt="openRS_"><br/>
  <b>Android telemetry for the Ford Focus RS Mk3.</b>
</div>

<p align="center">
  <a href="#choose-a-release"><b>Download</b></a> •
  <a href="android/docs/hardware-setup.md"><b>Setup guide</b></a> •
  <a href="https://klexical.github.io/openRS_/emulator/"><b>Browser demo · v2.2.6</b></a>
</p>

<p align="center">
  <a href="https://github.com/klexical/openRS_/releases/tag/android-v2.2.6"><img src="https://img.shields.io/badge/stable-2.2.6-blue" alt="Stable 2.2.6"></a>
  <img src="https://img.shields.io/badge/Android-9%2B-brightgreen?logo=android" alt="Android 9 or newer">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-green" alt="License: MIT"></a>
</p>

<p align="center"><sub>Stable v2.2.6 · Testing v2.2.7-rc.7 (Build 38) · Preview RC8 Build 39, unreleased</sub></p>

**openRS_** is a native Android app that turns your phone into a telemetry dashboard for the Ford Focus RS Mk3. It
connects to a MeatPi USB (C3) or MeatPi Pro (S3) adapter over **Wi-Fi or Bluetooth Low Energy**, reads supported signals
from the car's high-speed CAN bus (HS-CAN) and polls Ford modules for more. Unlike a generic OBD app it is built for
the RS: the GKN Twinster AWD system, TPMS pressures and temperatures from the BCM, and a dark, glanceable layout tuned
for track days.

## Next release preview

<div align="center">
  <a href="assets/screenshots/v2.2.7-preview/drive-night.png"><img src="assets/screenshots/v2.2.7-preview/drive-night.png" width="240" alt="DRIVE tab, RC8 Build 39 preview, simulated data"></a>
  <a href="assets/screenshots/v2.2.7-preview/thermal-night.png"><img src="assets/screenshots/v2.2.7-preview/thermal-night.png" width="240" alt="THERMAL tab, RC8 Build 39 preview, simulated data"></a>
  <a href="assets/screenshots/v2.2.7-preview/trip-summary-night.png"><img src="assets/screenshots/v2.2.7-preview/trip-summary-night.png" width="240" alt="TRIP drive summary, RC8 Build 39 preview, simulated data"></a>
</div>

<p align="center">
  <sub><b>RC8 · Build 39 · Unreleased · Simulated data</b><br/>
  DRIVE: the essentials at a glance &nbsp;·&nbsp; THERMAL: is the car warm enough to push? &nbsp;·&nbsp; TRIP: review a drive afterwards</sub>
</p>

These images come from **RC8 Build 39, which is not released** and has no download. They were captured from a debug
build in its demo mode on an emulator: the **DEMO** label in the header marks simulated data, and the TRIP summary
carries its own "PREVIEW · SAMPLE DATA — NOT SAVED" banner. Features may still change before release.

<details>
<summary><b>More preview screens</b> (DAY theme, PERF, GARAGE)</summary>
<br/>
<div align="center">
  <a href="assets/screenshots/v2.2.7-preview/drive-day.png"><img src="assets/screenshots/v2.2.7-preview/drive-day.png" width="240" alt="DRIVE tab, DAY theme, RC8 Build 39 preview, simulated data"></a>
  <a href="assets/screenshots/v2.2.7-preview/perf-night.png"><img src="assets/screenshots/v2.2.7-preview/perf-night.png" width="240" alt="PERF tab, RC8 Build 39 preview, simulated data"></a>
  <a href="assets/screenshots/v2.2.7-preview/garage-night.png"><img src="assets/screenshots/v2.2.7-preview/garage-night.png" width="240" alt="GARAGE tab, RC8 Build 39 preview, simulated data"></a>
</div>
<p align="center"><sub><b>RC8 · Build 39 · Unreleased · Simulated data</b><br/>DRIVE in the DAY theme &nbsp;·&nbsp; PERF &nbsp;·&nbsp; GARAGE</sub></p>

The GARAGE screen shows the drive-mode and ESC controls. These need openRS_ adapter firmware, and ESC control from the
app is **not yet confirmed on a car** ([#125](https://github.com/klexical/openRS_/issues/125)).
</details>

The stable v2.2.6 screens are in the [app guide](docs/app-guide.md#screens-v226).

## Choose a release

| Channel | Version | Download | For |
|---|---|---|---|
| **Stable** | v2.2.6 | [`openRS_v2.2.6.apk`](https://github.com/klexical/openRS_/releases/tag/android-v2.2.6) | Most owners |
| **Testing** | v2.2.7-rc.7 · Build 38 | [`openrs-2.2.7-rc.7-build38.apk`](https://github.com/klexical/openRS_/releases/tag/android-v2.2.7-rc.7) | Testers who accept prerelease software |
| **Preview** | RC8 · Build 39 | None: not released | Shown above only |

Build 38 is a manual install: it upgrades rc.7 Build 37 in place (same version name and signing certificate), and the
in-app updater does not offer it. Release notes and checksums are on each release page. The channel says how a build
is distributed, not that every feature in it is verified on a car.

## What you can do

The tab names below are those of the v2.2.7 release candidates; the v2.2.6 names are in brackets.

- **Understand the drive.** RPM, boost, speed, pedals, G-forces and AWD rear torque on DRIVE (DASH), with a shift light
  and alerts.
- **Monitor temperatures.** Oil, coolant, PTU, RDU, clutch and transmission temperatures with a Street / Track / Race
  readiness verdict on THERMAL (TEMPS), plus TPMS pressures and temperatures.
- **Review engine and performance data.** AFR, boost target against actual with wastegate duty, per-cylinder knock
  correction and fuel trims on PERF (POWER). The knock logger, the boost sweep and the fuel-trim drift tracker are new
  in the v2.2.7 release candidates.
- **Record and revisit drives.** GPS drive recording with a map, peaks and a drive summary on TRIP (MAP), and export
  to GPX, CSV and a text summary. Lap timing is in the release candidates and so far verified with synthetic GPS only
  ([#237](https://github.com/klexical/openRS_/issues/237)).
- **Investigate diagnostics.** DTC scan and clear across the main modules, a DID prober and a diagnostic export on
  GARAGE (DIAG). The bundled FORScan PID catalog (1,148 entries across 20 modules) is a searchable reference list,
  not a count of signals confirmed on the RS.
- **Explore exported sessions.** Open an export in [Sapphire](#companion-tools) for charts and diagnostics in your
  browser.

Full detail: the [app guide](docs/app-guide.md) (v2.2.6) and the
[changelog on staging](https://github.com/klexical/openRS_/blob/staging/android/CHANGELOG.md) (release candidates).

## Compatibility

| | Supported |
|---|---|
| **Vehicle** | Ford Focus RS Mk3, 2016–2018 (LZ platform, 2.3L EcoBoost) |
| **Phone** | Android 9 or newer, with Wi-Fi or Bluetooth Low Energy |
| **Adapter** | [MeatPi USB (C3)](https://www.mouser.com/ProductDetail/MeatPi/WICAN-USB-C3?qs=rQFj71Wb1eVDX2eEy0FC7A%3D%3D) (Wi-Fi + BLE) or [MeatPi Pro (S3)](https://www.meatpi.com/) (Wi-Fi + BLE, GPS, MicroSD) |
| **Adapter firmware** | Stock MeatPi firmware for monitoring, or **openrs-fw** (v1.61 USB, v1.2 Pro) for adapter commands |

Stock firmware works out of the box for passive monitoring. openrs-fw adds the commands behind drive-mode change,
Launch Control, Auto Start-Stop kill and ESC control. What is available, and what has been verified on a car, varies
by feature: ESC control from the app, for example, is not yet confirmed on a car
([#125](https://github.com/klexical/openRS_/issues/125)). See the
[firmware update guide](android/docs/firmware-update.md).

## Get started

1. **Check your setup** against the [compatibility table](#compatibility).
2. **Choose a release** from the [table above](#choose-a-release). Stable is the right choice for most owners.
3. **Install the app.** Download the APK from the release page on your phone and open it; Android asks you to allow
   installs from your browser or file manager.
4. **Set up the adapter** with the [hardware setup guide](android/docs/hardware-setup.md): plug it into the OBD-II port
   under the steering column, then pick the adapter and the connection method (Wi-Fi or Bluetooth LE) in Settings.
   A MeatPi Pro must be switched to SLCAN first ([Pro setup](android/docs/hardware-setup.md#meatpi-pro-s3-adapter)).
5. **Connect.** Tap the connection indicator in the header. Once CAN frames arrive the link shows as connected and the
   gauges fill in; the setup guide lists common causes if they do not.

## Companion tools

**Browser demo · v2.2.6** — [klexical.github.io/openRS_/emulator](https://klexical.github.io/openRS_/emulator/). The
v2.2.6 interface with animated simulated data, in any browser and with no hardware.

**Sapphire — Session analysis** — [klexical.github.io/openRS_](https://klexical.github.io/openRS_/). After a drive,
export a ZIP from the app and drop it into Sapphire to explore the session: key figures and peaks, time-series charts,
the CAN frame inventory, DID probe results and the decode trace. It runs in your browser and keeps its session library
there (IndexedDB). Source and details: [web/README.md](web/README.md).

## Documentation and help

| I want to… | Go to |
|---|---|
| Set up an adapter (Wi-Fi, Bluetooth LE, MeatPi Pro) | [Hardware setup guide](android/docs/hardware-setup.md) |
| Flash openrs-fw and see what it adds | [Firmware update guide](android/docs/firmware-update.md) |
| Learn the screens, thresholds and settings | [App guide (v2.2.6)](docs/app-guide.md) |
| Look up CAN frames, Mode 22 PIDs and formulas | [Telemetry reference](android/docs/pid-reference.md) |
| Analyse an exported session | [Sapphire](https://klexical.github.io/openRS_/) · [web/README.md](web/README.md) |
| Fix a problem or report one | [Firmware troubleshooting](android/docs/firmware-update.md#troubleshooting) · [Issues](https://github.com/klexical/openRS_/issues) |
| Build the app or contribute | [Contributing](CONTRIBUTING.md) |
| Understand the code and the design | [Architecture](docs/architecture.md) · [Brand tokens](docs/brand.md) |
| Follow releases and plans | [Releases](https://github.com/klexical/openRS_/releases) · [Changelog (staging)](https://github.com/klexical/openRS_/blob/staging/android/CHANGELOG.md) · [Feature roadmap](docs/feature-roadmap.md) |

## Development and direction

**Current focus.** The v2.2.7 release candidates: the "Daylight" redesign (five tabs, DAY / NIGHT / AUTO / ULTRA
themes), the knock logger, boost target against actual, the fuel-trim drift tracker, lap timing, corrected oil / PTU
temperature mapping and Bluetooth LE fixes. RC8 adds diagnostics, reliability and ESC-confirmation fixes; it is on
staging and not yet released.

**Next priorities.**
- More PCM data: MAP, barometric pressure, engine load, oil pressure and knock counters.
- Track days: geofenced lap timing, track map overlays and better drive comparison.
- Hardware: the MeatPi Pro's own GPS, and MS-CAN through a second adapter.
- High-frequency telemetry through a UDS fast-rate session (about 100 Hz).

**Exploring.** Android Auto, streaming data to lap-timing apps such as RaceChrono or Harry's LapTimer, and video
overlay export. None of these is a commitment; the [feature roadmap](docs/feature-roadmap.md) has the full list and
the history of completed phases.

Pull requests are welcome: start with [CONTRIBUTING.md](CONTRIBUTING.md). Owners with FORScan or an OBDLink can help
verify signals on a car; the [Android contributing guide](android/CONTRIBUTING.md#help-verifying-on-a-car) lists what
is needed.

## Credits and license

Thanks to **FORScan** (Ford enhanced PID discovery), **MeatPi** (WiCAN hardware) and the **Focus RS community**
(testing and feedback).

MIT — see [LICENSE](LICENSE).
