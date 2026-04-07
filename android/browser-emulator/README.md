# openRS_ Browser Emulator

Simulates the openRS_ phone UI in the browser (no device or MeatPi adapter required). Mirrors the v2.2.6 stable feature set: 7-tab layout including the new MAP tab, Tier 1 PIDs (STFT/LTFT, AWD clutch hydraulics, battery current), button-input feedback, and the in-app update channel.

## How to run

1. Open `index.html` in a browser (Chrome, Firefox, Safari, Edge).
   - **From the repo:** double-click `android/browser-emulator/index.html`, or
   - **From terminal:** `open "android/browser-emulator/index.html"` (macOS) or `xdg-open index.html` (Linux).
   - **Local server (recommended):** `cd android/browser-emulator && python3 -m http.server 8765` → `http://127.0.0.1:8765/`

## Live data

- **Synthetic simulation** runs by default (~12 updates/s) so gauges and lists look live.
- **Trip CSV replay:** on the **MORE** tab, use **LOAD TRIP CSV** and pick `trip_*.csv` from a trip ZIP export. Columns must match the app export (`timestamp_ms`, `speed_kph`, `rpm`, `gear`, `boost_psi`, temps, wheel speeds, `lateral_g`, `drive_mode`, `race_ready`, …). The file loops continuously.
- Raw **SLCAN `.log`** (candump) from diagnostic ZIPs is **not** replayed in the emulator — use the **trip CSV** for time-series telemetry.

## Settings

- Defaults match the Android app (`AppSettings` / `UserPrefs`): keep screen on **on**, auto-reconnect **on**, retry interval **10 s** (plus host/port, units, etc.). Values persist in `localStorage` (`openrs_emulator_settings_v1`).

## What it demonstrates

- Same structure and labels as the real app (`MainActivity` + 7 tabs).
- Demo data driven by the live engine above (RPM, boost, temps, AWD, TPMS, etc.).
- MAP tab placeholder: stylised SVG preview of the live drive route with HUD overlay, color-mode picker, and zoom/recenter controls (the real app uses Google Maps Compose with 6 colour modes, peak markers, and weather card).
- No backend: everything runs in the browser.
