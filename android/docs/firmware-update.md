# WiCAN Firmware Update Guide

This guide covers two scenarios:

| Scenario | When to use |
|----------|-------------|
| **A — Official WiCAN firmware** | First-time setup, factory reset, or rolling back |
| **B — openrs-fw** | Installing the custom Focus RS firmware for drive mode control, BLE transport, and advanced features |

---

## Prerequisites

**Hardware required**
- MeatPi WiCAN-USB-C3 (plugged into the Focus RS OBD-II port)
- Ignition ON or ACC (WiCAN must be powered)
- Android phone OR laptop connected to WiCAN's WiFi hotspot

**Software required** *(no installation needed — web-based)*
- Any modern browser (Chrome recommended)
- WiCAN web interface at `http://192.168.80.1`
- Firmware `.bin` file downloaded to your device

---

## Part A — Official WiCAN Firmware (OTA via Web UI)

This is the recommended method for day-to-day updates. No tools, no cables, no command line.

### Step 1 — Download the latest firmware

1. Open a browser and go to:
   ```
   https://github.com/meatpiHQ/wican-fw/releases/latest
   ```
2. Under **Assets**, look for the file matching your hardware variant:
   - **WiCAN-USB-C3** → download `wican-fw-usb_vXXX.bin`
   - The version number will be in the filename (e.g., `wican-fw-usb_v310.bin`)
3. Save the `.bin` file somewhere you can find it (Downloads folder is fine)

```
GitHub Releases page — what you will see:
┌─────────────────────────────────────────────────────┐
│ meatpiHQ/wican-fw                             v3.10 │
│ ─────────────────────────────────────────────────── │
│ Assets                                          ▼   │
│   wican-fw-usb_v310.bin              ← download this│
│   wican-fw-obd_v310.bin                             │
│   Source code (zip)                                 │
│   Source code (tar.gz)                              │
└─────────────────────────────────────────────────────┘
```

### Step 2 — Connect your phone to the WiCAN hotspot

1. Plug the WiCAN into the OBD-II port with ignition ON
2. Wait for the LED to go solid (10–15 seconds)
3. On your phone: **Settings → Wi-Fi**
4. Connect to the network: `WiCAN_XXXXXX` (last 6 chars of MAC address, printed on the device label)
5. Default password: `bla2020blabla`

```
Wi-Fi networks — what you will see:
┌─────────────────────────────────────┐
│ 📶 WiCAN_A3F91C          🔒 ← tap  │
│     Password: bla2020blabla         │
│     [ Connected ]                   │
└─────────────────────────────────────┘
```

> **Note:** Your phone will warn "No internet connection" — this is expected. The WiCAN creates a local-only hotspot.

### Step 3 — Open the WiCAN web interface

1. Open **Chrome** on your phone (Safari works too)
2. Navigate to: `http://192.168.80.1`
3. The WiCAN web interface loads — it shows a dashboard with CAN status and settings

```
WiCAN web interface — what you will see:
┌─────────────────────────────────────────────────┐
│  WiCAN  [Status] [CAN] [Settings] [Firmware]    │
│ ─────────────────────────────────────────────── │
│  Device: WiCAN-USB-C3                           │
│  Firmware: v2.92                                │
│  MAC: AA:BB:CC:DD:EE:FF                         │
│  IP: 192.168.80.1                               │
│  CAN Status: Idle                               │
└─────────────────────────────────────────────────┘
```

### Step 4 — Navigate to the Firmware Update page

1. Tap **Settings** in the top navigation
2. Scroll down to the bottom of the Settings page
3. Look for the **Firmware Update** section (sometimes labeled **OTA Update**)

```
Settings page — Firmware Update section:
┌─────────────────────────────────────────────────┐
│  Firmware Update                                │
│  ─────────────────────────────────────────────  │
│  Current version: v2.92                        │
│                                                 │
│  [ Choose File ]  No file selected             │
│                                                 │
│  [ Upload & Update ]                           │
└─────────────────────────────────────────────────┘
```

### Step 5 — Upload the new firmware

1. Tap **Choose File**
2. Navigate to your Downloads folder and select the `.bin` file you downloaded in Step 1
3. The filename will appear next to the button confirming selection
4. Tap **Upload & Update**

```
After selecting the file:
┌─────────────────────────────────────────────────┐
│  Firmware Update                                │
│  ─────────────────────────────────────────────  │
│  [ Choose File ]  wican-fw-usb_v310.bin ✓       │
│                                                 │
│  [ Upload & Update ]   ← tap this              │
└─────────────────────────────────────────────────┘
```

### Step 6 — Wait for the update to complete

1. A progress bar appears — **do not close the browser or turn off the car during this step**
2. Upload takes approximately 30–60 seconds depending on file size (~1.5 MB)
3. When complete, the WiCAN automatically reboots
4. The LED will flash rapidly, go dark for 2–3 seconds, then come back solid

```
Update progress — what you will see:
┌─────────────────────────────────────────────────┐
│  Uploading firmware...                          │
│  ████████████████░░░░░░  72%                   │
│                                                 │
│  Do not turn off ignition.                      │
└─────────────────────────────────────────────────┘

After reboot:
┌─────────────────────────────────────────────────┐
│  Update successful!                             │
│  Firmware: v3.10                               │
│  Rebooting...                                   │
└─────────────────────────────────────────────────┘
```

### Step 7 — Reconnect and verify

1. Your phone will lose WiFi connection while the WiCAN reboots
2. Wait 15 seconds, then reconnect to `WiCAN_XXXXXX`
3. Navigate back to `http://192.168.80.1`
4. Confirm the **Firmware** version now shows the new version number

---

## Part B — openrs-fw (Custom Focus RS Firmware)

> **Status:** openrs-fw v1.0 is in active development. This section will be updated with the download link and verified `.bin` file when the first release is published at:
> ```
> https://github.com/klexical/openRS_/releases
> ```

The flashing process for openrs-fw is **identical** to Part A — it uses the same WiCAN OTA web interface. The only difference is which `.bin` file you select.

### What openrs-fw adds over stock firmware

| Feature | Stock WiCAN | openrs-fw v1.0 |
|---------|-------------|----------------|
| ELM327 TCP passthrough | ✅ | ✅ |
| CAN ATMA monitor mode | ✅ | ✅ |
| Focus RS drive mode read | ✅ (via app) | ✅ |
| **Drive mode write** (N/S/T/D) | ❌ | ✅ |
| **Boot mode persistence** (NVS) | ❌ | ✅ |
| **ESC write** (On/Sport/Off) | ❌ | ✅ |
| **Launch Control enable** | ❌ | ✅ |
| **Auto Start/Stop kill** | ❌ | ✅ |
| **BLE GATT data transport** | ❌ | ✅ |
| **Auto-discovery** (app finds device) | ❌ | ✅ |
| Battery sleep (12V threshold) | ❌ | ✅ |
| Battery voltage API | ❌ | ✅ |
| OTA updates via web UI | ✅ | ✅ |
| openrs-fw branding | ❌ | ✅ |

### openrs-fw default configuration

When first flashed, openrs-fw uses these defaults:

| Setting | Default value |
|---------|---------------|
| WiFi SSID | `openRS_XXXXXX` |
| WiFi Password | `openrs2024` |
| ELM327 TCP Port | `3333` |
| CAN Bus Speed | `500 kbps` (HS-CAN) |
| BLE Device Name | `openRS_WiCAN` |
| Battery sleep threshold | `12.2V` |
| Boot mode | Last used drive mode |

> **First flash note:** After flashing openrs-fw, the WiFi SSID changes from `WiCAN_XXXXXX` to `openRS_XXXXXX`. Update the WiFi network on your phone and in the app's settings screen.

---

## Troubleshooting

### WiCAN web interface not loading
- Confirm you are connected to the WiCAN WiFi network, not your home or phone hotspot
- Check that the LED is solid (not flashing) — if flashing, wait longer
- Try `http://192.168.80.1` with a different browser
- Power cycle: unplug WiCAN, wait 5 seconds, replug with ignition ON

### Update progress bar stalls
- Wait up to 3 minutes before concluding it has failed
- If the LED is flashing rapidly, the device is still writing — do not interrupt
- If no change after 3 minutes, power cycle and retry

### WiCAN no longer accessible after update
- The device may have entered safe mode — connect a laptop via USB-C
- Use the [esptool recovery method](https://github.com/meatpiHQ/wican-fw#recovery) to re-flash

### "Wrong firmware type" error
- You selected the wrong `.bin` variant — ensure you downloaded the `usb_` variant for WiCAN-USB-C3, not `obd_`

---

## Advanced: Flashing via esptool (Recovery)

For cases where OTA is not possible (device unresponsive, failed update), use `esptool.py` over USB-C.

**Required software:**
```bash
pip install esptool
```

**Flash command:**
```bash
esptool.py --chip esp32c3 --port /dev/ttyUSB0 \
  --baud 460800 write_flash \
  0x0 bootloader.bin \
  0x8000 partition-table.bin \
  0x10000 wican-fw-usb_vXXX.bin
```

> Replace `/dev/ttyUSB0` with `COMx` on Windows or `/dev/tty.usbserial-*` on macOS.

The WiCAN-USB-C3 will appear as a USB serial device when plugged into a computer with ignition ON.

---

## CAN Data Capture (for drive mode write frame identification)

When testing with the car, capture the full `0x1B0` frame while pressing the drive mode button physically to identify the write frame template for openrs-fw.

### Using the stock WiCAN ELM327 mode:
1. Connect the openRS_ app to the WiCAN
2. Enable ATMA (Monitor All) mode in the app
3. Note `0x1B0` frames at idle: `[byte0] 5A [byte2] [byte3] [byte4] [byte5] [byte6] [byte7]`
4. Press drive mode button physically — note frame change at byte 1: `5A` → `5E`
5. Record all 8 bytes for both released and pressed states
6. Submit the captured bytes via GitHub issue for inclusion in openrs-fw

**Expected 0x1B0 format (byte 1 = drive mode button):**
```
Released: XX 5A XX XX XX XX XX XX
Pressed:  XX 5E XX XX XX XX XX XX
```
