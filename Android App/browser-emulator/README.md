# openRS Browser Emulator

Simulates the **phone** and **Android Auto** UIs in the browser with live demo data (no device or WiCAN required).

## How to run

1. Open `index.html` in a browser (Chrome, Firefox, Safari, Edge).
   - **From the repo:** double-click `index.html`, or
   - **From terminal:** `open "Android App/browser-emulator/index.html"` (macOS) or `xdg-open index.html` (Linux).

2. Use the top toggle: **Phone** | **Android Auto** to switch between the two UIs.

3. **Phone:** Use the tabs (DASH, AWD, PERF, TEMPS, TUNE, TPMS) to switch pages. Values update every 200 ms with simulated data.

4. **Android Auto:** Main screen shows the DASH pane. Use the strip buttons (AWD, PERF, TEMPS, MENU). **MENU** opens a list of all 6 sections; tap one to go to that screen. **← Back** returns to the main DASH screen.

## What it demonstrates

- Same structure and labels as the real app (phone: `MainActivity` + 6 tabs; AA: `MainDashScreen`, `AwdDetailScreen`, `PerformanceScreen`, `TempsScreen`, `TuneScreen`, `TpmsScreen`, `MenuScreen`).
- Demo `VehicleState`-style data (RPM, boost, temps, AWD split, TPMS, etc.) so both UIs look live.
- No backend: everything runs in the browser.
