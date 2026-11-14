# Changelog

All notable changes to Sapphire, the openRS_ web dashboard (`web/`), are documented here.
The Android app has its own changelog: [android/CHANGELOG.md](../android/CHANGELOG.md).

Sections, in this order: Added, Fixed, Changed, Removed. Every entry ends with its issue link.

---

## [Unreleased]

### Fixed
- **Charts, the peak speed gauge and the notes table follow the units setting** — Trip's Speed, Wheel Speeds, Temperatures, Tire Pressure and Tire Temperature charts, the Dashboard's Peak Speed gauge and the observer's-notes table now show their values in the selected units instead of km/h, °C and psi under a converted heading; a missing reading stays a gap (`lib/units.ts` `unitChartRows`). ([#381](https://github.com/klexical/openRS_/issues/381))
- **Diagnostics events coloured by type** — every event type the app writes now has a group colour, named on hover, matched whatever its letter case; an unknown type keeps the neutral style (`lib/eventTypes.ts`). ([#382](https://github.com/klexical/openRS_/issues/382))
