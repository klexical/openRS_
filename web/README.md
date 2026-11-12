<p align="center">
  <img src="../assets/images/openrs-wordmark.svg" width="320" alt="openRS_"><br/>
  <sub>Sapphire — post-session analytics dashboard</sub>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/React-19-61DAFB?logo=react" alt="React">
  <img src="https://img.shields.io/badge/TypeScript-5.9-3178C6?logo=typescript" alt="TypeScript">
  <img src="https://img.shields.io/badge/Vite-8-646CFF?logo=vite" alt="Vite">
  <img src="https://img.shields.io/badge/Tailwind_CSS-4-06B6D4?logo=tailwindcss" alt="Tailwind">
</p>

> For adapter setup, telemetry references and architecture, see [Documentation and help](../README.md#documentation-and-help).

---

## What is Sapphire?

**Sapphire** is the openRS_ post-session analytics dashboard — a standalone web app that lets you explore trip and diagnostic data exported from the Android app.

Named after the **Ford Sierra Sapphire RS Cosworth** — one of the most legendary RS cars ever built — and the blue gemstone connecting to the Focus RS's iconic Nitrous Blue paint.

**[klexical.github.io/openRS_](https://klexical.github.io/openRS_/)**

### How it works

1. Record a session with the openRS_ Android app
2. Export via **Capture & Share Snapshot** on the DIAG tab
3. Drop the ZIP file into Sapphire
4. Explore your data — charts, tables, and diagnostics persist in your browser

No server required. Everything runs client-side. Your data stays in your browser (IndexedDB).

---

## Panels

| Panel | Description |
|-------|-------------|
| **Dashboard** | KPI cards (distance, duration, speed, RPM), session peaks (7 metrics), drive mode breakdown bar |
| **Trip** | 8 time-series charts: RPM, boost, speed, temperatures (4-line), lateral G, fuel %, wheel speeds (4-line), AWD torque (L/R) |
| **Diagnostics** | CAN frame inventory (sortable), session events (color-coded), DID probe results, decode trace (searchable) |
| **Sessions** | Persistent session library — select, delete, auto-navigate |
| **Import** | Drag-and-drop ZIP import + step-by-step export instructions |

---

## Development

### Prerequisites

- Node.js 20+ (tested with v25.8.2)
- npm 10+

### Quick start

```bash
cd web
npm install
npm run dev        # → http://localhost:5173/openRS_/
```

### Build

```bash
npm run build      # → dist/ (663 KB JS + 16 KB CSS)
npm run preview    # preview production build locally
```

### Tech stack

| Library | Version | Purpose |
|---------|---------|---------|
| React | 19 | UI framework |
| TypeScript | 5.9 | Type safety |
| Vite | 8 | Build tool + dev server |
| Tailwind CSS | 4 | Utility-first styling |
| Recharts | 3.8 | Time-series charts |
| Zustand | 5 | State management |
| JSZip | 3.10 | Client-side ZIP parsing |
| idb | 8 | IndexedDB wrapper |
| Leaflet + react-leaflet | 1.9 / 5.0 | GPS map (V2) |

---

## Data Contract

The Android app exports a ZIP containing:

| File | Format | Content |
|------|--------|---------|
| `trip_*.csv` | CSV | GPS waypoints + 20 telemetry fields per row |
| `diagnostic_detail_*.json` | JSON | CAN inventory, FPS timeline, events, decode trace |
| `diagnostic_summary_*.txt` | Text | Human-readable session summary |
| `did_probe_*.csv` | CSV | DID prober results (DID, status, response hex) |

Sapphire's `lib/import.ts` parses these via JSZip and stores sessions in IndexedDB.

---

## Deployment

Deployed to GitHub Pages via [`.github/workflows/deploy-pages.yml`](../.github/workflows/deploy-pages.yml).

| URL | Content |
|-----|---------|
| `klexical.github.io/openRS_/` | Sapphire dashboard |
| `klexical.github.io/openRS_/emulator/` | Browser emulator |

Triggers on push to `main` when `web/**` or `android/browser-emulator/**` changes.

---

## License

MIT — see [LICENSE](../LICENSE) for details.
