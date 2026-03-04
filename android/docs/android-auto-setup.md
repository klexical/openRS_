# Android Auto Setup Guide

openRS_ projects a full custom UI onto your SYNC3 head unit via Android Auto Wireless.
It uses the unofficial aauto-sdk (`CarActivity`) to render the exact same Compose interface
as the phone app — all 6 tabs, live CAN data, real-time gauges.

---

## Requirements

| Item | Details |
|------|---------|
| Android phone | Android 9+ recommended |
| Android Auto | Latest version from Play Store |
| AA Wireless adapter | Or built-in wireless AA on supported head units |
| Ford SYNC 3 | Version 3.0+ with Android Auto enabled |
| openRS_ | Sideloaded APK with "Unknown sources" enabled |

---

## One-Time Phone Setup

### 1 — Enable Unknown Sources in Android Auto

openRS_ uses the unofficial aauto-sdk so it isn't signed through the Play Store AA review process.
Android Auto blocks these apps by default — you need to unlock them once.

1. Open the **Android Auto** app on your phone
2. Tap the **hamburger menu** (top-right) → **Settings**
3. Scroll to the bottom, tap the **version number** 10 times rapidly
4. A prompt appears: *"Allow development settings?"* → tap **OK**
5. Scroll down to **Unknown sources** → toggle **ON**

This is a one-time step. Android Auto remembers it.

### 2 — Install openRS_

Sideload the APK via Android Studio (Run → Run on device) or `adb install`.
The app appears in your launcher as **openRS_**.

---

## Connecting to the Car

### Wired (USB)

1. Plug your phone into the SYNC3 USB port
2. SYNC3 launches Android Auto automatically
3. The AA launcher shows **openRS_** — tap it
4. `CarDashActivity` fills the SYNC3 screen with the full dashboard

### Wireless (AA Wireless adapter)

1. Pair your phone with the AA Wireless adapter (one-time via USB, then automatic)
2. Get in the car — phone connects automatically when in range
3. SYNC3 shows Android Auto within ~15 seconds
4. Tap **openRS_** in the AA launcher

---

## Using the App in Android Auto

The head unit displays the same layout as the phone:

| Element | Location | Description |
|---------|----------|-------------|
| openRS_ wordmark | Top-left | Brand |
| Mode badge | Top-centre | NORMAL / SPORT / DRIFT |
| Gear | Top-centre | Current gear (1–6 or N/R) |
| ESC status | Top-centre | ON / SPORT / OFF |
| Connection dot | Top-right | ● CONNECTED or ○ OFFLINE |
| FPS counter | Top-right | CAN frames per second |
| Tab bar | Below header | DASH / POWER / CHASSIS / TEMPS / DIAG |

Tap tabs to switch. All content is touch-interactive on the SYNC3 screen.

---

## Connecting the WiCAN in AA Wireless Mode

When using AA Wireless (e.g. an AAWireless dongle), your phone's WiFi radio is occupied by the AA connection. openrs-fw solves this transparently with dual transport:

| Situation | Transport | What the app does |
|-----------|-----------|-------------------|
| Phone-only (no AA Wireless) | WiFi WebSocket | Connects to `ws://192.168.80.1:80/ws` (SLCAN) as normal |
| AA Wireless active | BLE GATT | App auto-discovers the WiCAN by BLE, connects on `0xFFE1/FFE2` |
| USB AA cable | WiFi WebSocket | WiFi is free — normal WebSocket connection |

**With openrs-fw (BLE transport, recommended):**
1. Flash openrs-fw to the WiCAN (see [Firmware Update Guide](firmware-update.md))
2. Launch openRS_ — it automatically finds the WiCAN by BLE (`openRS_WiCAN`) when WiFi isn't available
3. Data streams over BLE while AA projects via AA Wireless — no manual switching

**With stock firmware (WiFi only — workaround):**
1. Configure the WiCAN in **Client mode** so it connects to your phone's personal hotspot
2. Android can then reach the WiCAN over the hotspot even while AA Wireless is active
3. Update the IP in **app settings → WiCAN Host** to match the IP your hotspot assigned

> **System Drawer (☰):** The System Drawer shows live drive mode and ESC status from CAN. Drive mode write controls and feature buttons (Launch Control, Auto S/S Kill) become active after flashing openrs-fw.

---

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| openRS_ doesn't appear in AA launcher | Confirm Unknown Sources is enabled; reinstall APK |
| App appears but crashes on launch | Reboot phone; check logcat for errors |
| Data shows all zeros / dashes | WiCAN not connected — tap OFFLINE badge to connect |
| AA disconnects when WiCAN connects | Phone can't hold two WiFi connections — switch WiCAN to Client mode |
| Head unit shows black screen | CarDashActivity failed to start — check Android Studio Build Output |
