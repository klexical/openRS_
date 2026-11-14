# Changelog

All notable changes to Sapphire, the openRS_ web dashboard (`web/`), are documented here.
The Android app has its own changelog: [android/CHANGELOG.md](../android/CHANGELOG.md).

Sections, in this order: Added, Fixed, Changed, Removed. Every entry ends with its issue link.

---

## Storage and the list of drives (slice 2)

### Added
- **Home is the list of drives, with addresses** — Sapphire opens on Drives: every stored drive and session-only entry, newest first by when it started, grouped by month, each row with its date, name, duration, distance, top speed and peak boost named by its measurement, in the chosen units. A row opens the drive at its own address with today's Dashboard, Trip and Diagnostics views; the two places, Drives and Settings, are a bar at the bottom on a phone and a rail on a desktop, so reload and back / forward work. The drive views load only when a drive is opened (`lib/address.ts`, `components/drives/`). ([#384](https://github.com/klexical/openRS_/issues/384))
- **Back up all drives, and restore** — Settings writes one backup file holding every drive with its points and diagnostic log, and reads one back; after a failed storage update Sapphire offers a backup of the old drives first. Restore only adds: a drive already here, by id or as the same drive, is skipped and counted, and a file from a newer Sapphire or a damaged one is refused with nothing restored. The file is built in a background worker so the page stays usable (`lib/backup.ts`). ([#384](https://github.com/klexical/openRS_/issues/384))

### Fixed
- **Leaving a route map at once no longer throws a page error** — the map's zoom-to-fit no longer animates, so a drive closed within moments of opening leaves no Leaflet "_leaflet_pos" error behind; the map opens at the fitted view at once (`GpsMap.tsx`). (issue link waiting: ledger L108)

### Changed
- **Drives stored one record each, with a safe upgrade** — Sapphire's database moves to version 2: a small record per drive (the list reads only these), its points and its diagnostic log stored apart and read only when that drive is opened. Drives stored before are converted in place with every field and id kept; if one cannot be converted nothing changes and Sapphire says so, and a second open tab no longer stalls the update. A drive already stored is recognised by its content and not stored twice ("already imported"), and each drive records whether it has warnings (fault codes active or pending, recording gaps) and which causes its import let Sapphire check (`lib/storage.ts`). ([#384](https://github.com/klexical/openRS_/issues/384))

### Removed
- **The remembered view and the unused router** — the address is the memory now: the old remembered-view key is ignored, and the `react-router-dom` dependency, which nothing used, is removed (`package.json`). ([#384](https://github.com/klexical/openRS_/issues/384))

## Repairs (slice 1)

### Fixed
- **Charts, the peak speed gauge and the notes table follow the units setting** — Trip's Speed, Wheel Speeds, Temperatures, Tire Pressure and Tire Temperature charts, the Dashboard's Peak Speed gauge, the observer's-notes table and the map's note popup now show their values in the selected units instead of km/h, °C and psi under a converted heading; a missing reading stays a gap (`lib/units.ts` `unitChartRows`). ([#381](https://github.com/klexical/openRS_/issues/381))
- **Diagnostics events coloured by type** — every event type the app writes now has a group colour, named on hover, matched whatever its letter case; a recording gap shows the warning colour; an unknown type keeps the neutral style (`lib/eventTypes.ts`). ([#382](https://github.com/klexical/openRS_/issues/382))
