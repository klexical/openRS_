---
paths:
  - "**/web/**"
---
# openRS_ — Sapphire, the web analytics dashboard (`web/`)

Post-session analysis dashboard.
Named after the Ford Sierra Sapphire RS Cosworth + Nitrous Blue gemstone connection.

Pure client-side (no server). Deployed as static files to GitHub Pages.

**Data contract:** Android app exports ZIP → user imports into Sapphire via drag-and-drop.
ZIP contains: `drive_*.csv` and `drive_summary_*.txt` (legacy `trip_*` names still import), `diagnostic_detail_*.json`, `diagnostic_summary_*.txt`, `did_probe_*.csv`.
Sapphire parses the drive and diagnostic files (it does not read `did_probe_*.csv`) and stores sessions in browser IndexedDB.

**Design:** Dark "Void" aesthetic matching the openRS_ in-app palette.

Scripts are in `web/package.json` (`dev`, `build`, `preview`, `test`, `test:watch`, `lint`); CI installs with `npm ci`.

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
`git show 743230fb:CLAUDE.md`, section Sapphire. Two points it lacks: `web/` and main's rebuilt firmware release binaries
(`firmware/release/`: the two app images, the bootloaders and the build manifests) come from main; and
`.cursor/rules/can-architecture.mdc` stays deleted (main removed `.cursor/`; take the deletion in its modify/delete
conflict).
