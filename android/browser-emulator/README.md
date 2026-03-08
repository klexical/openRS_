# openRS_ Browser Emulator

Simulates the openRS_ phone UI in the browser with live demo data (no device or WiCAN required).

## How to run

1. Open `index.html` in a browser (Chrome, Firefox, Safari, Edge).
   - **From the repo:** double-click `android/browser-emulator/index.html`, or
   - **From terminal:** `open "android/browser-emulator/index.html"` (macOS) or `xdg-open index.html` (Linux).

2. Use the tabs (DASH, POWER, CHASSIS, TEMPS, DIAG, MORE) to switch pages. Values update every 200 ms with simulated data.

## What it demonstrates

- Same structure and labels as the real app (`MainActivity` + 6 tabs).
- Demo `VehicleState`-style data (RPM, boost, temps, AWD split, TPMS, etc.) so the UI looks live.
- No backend: everything runs in the browser.
