# Changelog

All notable changes to Sapphire, the openRS_ web dashboard (`web/`), are documented here.
The Android app has its own changelog: [android/CHANGELOG.md](../android/CHANGELOG.md).

Sections, in this order: Added, Fixed, Changed, Removed. Every entry ends with its issue link.

---

## Storage and the list of drives (slice 2)

### Changed
- **Drives stored one record each, with a safe upgrade** — Sapphire's database moves to version 2: a small record per drive (the list reads only these), its points and its diagnostic log stored apart and read only when that drive is opened. Drives stored before are converted in place with every field and id kept; if one cannot be converted nothing changes and Sapphire says so, and a second open tab no longer stalls the update. A drive already stored is recognised by its content and not stored twice ("already imported"), and each drive records whether it has warnings (fault codes active or pending, recording gaps) and which causes its import let Sapphire check (`lib/storage.ts`). ([#384](https://github.com/klexical/openRS_/issues/384))

## Repairs (slice 1)

### Fixed
- **Charts, the peak speed gauge and the notes table follow the units setting** — Trip's Speed, Wheel Speeds, Temperatures, Tire Pressure and Tire Temperature charts, the Dashboard's Peak Speed gauge, the observer's-notes table and the map's note popup now show their values in the selected units instead of km/h, °C and psi under a converted heading; a missing reading stays a gap (`lib/units.ts` `unitChartRows`). ([#381](https://github.com/klexical/openRS_/issues/381))
- **Diagnostics events coloured by type** — every event type the app writes now has a group colour, named on hover, matched whatever its letter case; a recording gap shows the warning colour; an unknown type keeps the neutral style (`lib/eventTypes.ts`). ([#382](https://github.com/klexical/openRS_/issues/382))
