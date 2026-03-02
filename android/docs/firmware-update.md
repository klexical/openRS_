# openrs-fw Firmware Update Guide

> **Status: openrs-fw is in active development — no release binary is available yet.**
> The source code is published at `https://github.com/klexical/openRS_/tree/main/firmware` and the first compiled release will be published at `https://github.com/klexical/openRS_/releases` when v1.0 is ready.
> This guide is written in advance so you know exactly what to do the moment the release drops.

This guide covers flashing `openrs-fw` — the custom Focus RS firmware — onto your MeatPi WiCAN-USB-C3.

> **Important:** Firmware is loaded via the **Mini USB port** on the device while connected to a computer on your desk. You cannot flash firmware through the OBD-II port — that port is only used for power and CAN data when the device is in use in the car.

---

## Stock firmware files (v4.20u beta)

The flash-ready stock firmware package is included in this repo for reference and offline use:

```
firmware/stock/
  bootloader.bin                              ← address 0x0
  partition-table.bin                         ← address 0x8000
  ota_data_initial.bin                        ← address 0xd000
  wican-fw_usb_v420u_beta-01.bin              ← address 0x10000

  wican-fw_usb_v420u_beta-01_flash-package.zip  ← all 4 files zipped
```

Latest official releases are always at:
```
https://github.com/meatpiHQ/wican-fw/releases/latest
```
Download the `wican-fw_vXXX_usb.zip` package and extract — it contains the same 4 files.

---

## What you need

**Hardware**
- MeatPi WiCAN-USB-C3 (on the desk, not in the car)
- Mini USB data cable — must carry data, not charge-only
- Windows PC (recommended for the GUI flash tool)

**Software**
- **ESP32C3 Download Tool V3.9.0** (or later) — the official Espressif GUI flash tool
  → `https://www.espressif.com/en/support/download/other-tools` → "Flash Download Tools"
- No installation needed — extract and run the `.exe`

---

## Step 1 — Enter bootloader mode

The ESP32-C3 must be in bootloader mode before the flash tool can communicate with it.

```
WiCAN-USB-C3 — PCB layout (top view):

  ┌────────────────────────────────────────┐
  │                                        │
  │   [CANH] [CANL] [TR] [GND]            │
  │                                        │
  │                          ┌──────────┐  │
  │                          │  [BOOT]  │  │ ← hold this
  │                          └──────────┘  │
  │                               [Mini USB]
  └────────────────────────────────────────┘
```

1. Do **not** plug in the USB cable yet
2. Press and **hold** the BOOT button on the WiCAN PCB
3. While holding BOOT, plug the Mini USB cable into the WiCAN and into your computer
4. Release the BOOT button
5. The **orange LED** lights up — bootloader mode is active ✓

> If the orange LED does not appear: unplug, wait 3 seconds, and repeat firmly — the button must be held at the exact moment USB power is applied.

---

## Step 2 — Configure the ESP32C3 Download Tool

Open the ESP32C3 Download Tool and set it up exactly as shown below.

### Chip and mode selection (startup dialog)

```
┌─────────────────────────────────┐
│  ChipType:  ESP32-C3            │
│  WorkMode:  develop             │
│  LoadMode:  USB                 │
│  [ OK ]                         │
└─────────────────────────────────┘
```

### Add the 4 binary files

In the main window, load all 4 files with their exact flash addresses.
**Tick the checkbox on the left of each row** — unticked files are skipped.

```
ESP32C3 Download Tool V3.9.0 — SPIDownload tab:
┌──────────────────────────────────────────────────────────────┐
│  ☑  bootloader.bin                          @ 0x0           │
│  ☑  wican-fw_usb_v420u_beta-01.bin          @ 0x10000       │
│  ☑  partition-table.bin                     @ 0x8000        │
│  ☑  ota_data_initial.bin                    @ 0xd000        │
└──────────────────────────────────────────────────────────────┘
```

> Order does not matter — the addresses tell the tool where each file goes.

### SPI Flash configuration

```
SpiFlashConfig:
┌──────────────────────────────────────────────────────────────┐
│  SPI SPEED:  ● 40MHz   (not 80MHz)                          │
│  SPI MODE:   ● DIO     (not QIO)                            │
│  ☑ DoNotChgBin                                              │
└──────────────────────────────────────────────────────────────┘
```

> **These settings matter.** 40MHz + DIO + DoNotChgBin are the confirmed working values for the WiCAN-USB-C3. Using QIO or 80MHz may cause a failed or corrupt flash.

### COM port and baud rate

```
Download Panel:
┌──────────────────────────────────────────────────────────────┐
│  COM:   COMxx  ← select your device port                    │
│  BAUD:  115200                                               │
└──────────────────────────────────────────────────────────────┘
```

> **Baud: 115200** — this is lower than some online guides suggest (1152000) but is the confirmed working rate for this hardware. Use 115200.

To find your COM port: open **Device Manager** → expand **Ports (COM & LPT)** → look for `USB-Serial` or `CH340` — note the number (e.g. `COM5`, `COM111`).

---

## Step 3 — Flash

1. Click **START**
2. A green progress bar fills from left to right — this takes about 30–60 seconds at 115200 baud
3. `FINISH` message appears when complete

> If you are recovering a bricked device: click **ERASE** first, wait for completion, then click **START**. This clears bad config data before reflashing.

---

## Step 4 — Verify

1. Unplug the Mini USB cable
2. Plug the WiCAN into the Focus RS OBD-II port (ignition ON)
3. Wait for the solid LED (10–20 seconds)
4. On your phone: **Settings → Wi-Fi** → connect to `WiCAN_XXXXXX`
   - Password: `@meatpi#`
5. Open `http://192.168.80.1` in a browser
6. The WiCAN web interface loads — confirm the firmware version shown

```
WiCAN web interface — what you will see:
┌─────────────────────────────────────────────────────┐
│  WiCAN  [Status] [CAN] [Settings] [Firmware]        │
│ ─────────────────────────────────────────────────── │
│  Device: WiCAN-USB-C3                               │
│  Firmware: v4.20u                                   │
│  IP: 192.168.80.1                                   │
└─────────────────────────────────────────────────────┘
```

---

## Configuring WiCAN for openRS_ (after flash)

After flashing, verify these settings in the **Settings** tab at `http://192.168.80.1`:

| Setting | Required Value |
|---------|----------------|
| Protocol | `elm327` |
| Port Type | `TCP` |
| Port | `3333` |
| WiFi Mode | `AP` (Access Point) |
| CAN Speed | `500 kbps` |
| BLE | `Disabled` (AP mode — enable only if using BLE transport) |

If anything differs, update and press **Submit changes** — the device reboots automatically.

> **Sleep mode:** The WiCAN can be left permanently plugged in. Enable Sleep Mode in Settings and set the voltage threshold to ~13.1V — when the engine is off and voltage drops below this for 3 minutes, it sleeps and draws < 1mA. It wakes instantly when the engine starts.

---

## Installing openrs-fw (when available)

When openrs-fw v1.0 is released, the process is identical to the above — just use the openrs-fw files instead:

| File | Address |
|------|---------|
| `bootloader.bin` | `0x0` |
| `partition-table.bin` | `0x8000` |
| `ota_data_initial.bin` | `0xd000` |
| `openrs-fw-usb_v100.bin` | `0x10000` |

Same tool, same settings (40MHz, DIO, DoNotChgBin, 115200 baud).

After flashing openrs-fw, the WiFi SSID changes from `WiCAN_XXXXXX` to `openRS_XXXXXX` and the password changes to `openrs2024`.

### Subsequent openrs-fw updates (OTA)

Once openrs-fw is running, all future updates use **OTA via the web UI** — no cables, no bootloader mode needed.

1. Download the new `openrs-fw-usb_vXXX.bin` from `https://github.com/klexical/openRS_/releases`
2. Connect phone to `openRS_XXXXXX` WiFi
3. Open `http://192.168.80.1` → **Settings** → scroll to **Firmware Update**
4. Tap **Choose File** → select the `.bin` → tap **Upload & Update**
5. Wait 30–60 seconds — **do not turn off the car during this**
6. Reconnect and verify the new version number

---

## What openrs-fw adds

| Feature | Stock WiCAN | openrs-fw v1.0 |
|---------|-------------|----------------|
| ELM327 TCP passthrough | ✅ | ✅ |
| CAN ATMA monitor mode | ✅ | ✅ |
| OBD AutoPID (MQTT/HTTP) | ✅ | ✅ |
| Sleep mode (voltage threshold) | ✅ | ✅ |
| Focus RS drive mode read | ✅ (via app) | ✅ |
| **Drive mode write** (N/S/T/D) | ❌ | ✅ |
| **Boot mode persistence** (NVS) | ❌ | ✅ |
| **ESC write** (On/Sport/Off) | ❌ | ✅ |
| **Launch Control enable** | ❌ | ✅ |
| **Auto Start/Stop kill** | ❌ | ✅ |
| **BLE GATT data transport** | ❌ | ✅ |
| **Auto-discovery** (app finds device) | ❌ | ✅ |
| openRS_ branding (SSID, web UI) | ❌ | ✅ |

---

## Troubleshooting

### Device not detected — no COM port appears
- Ensure the Mini USB cable carries data (charge-only cables have no data wires)
- The orange LED must be lit — if not, bootloader mode was not entered correctly
- Try a different USB port on your computer
- Windows: install CH340 drivers from `https://www.wch-ic.com/downloads/CH341SER_EXE.html`

### Orange LED does not light up
- The BOOT button was not held at the exact moment USB was plugged in
- Unplug, hold BOOT firmly, plug in while holding, then release

### Flash fails or device unresponsive after flash
- Click **ERASE** first, wait for it to complete, then click **START** again
- Confirm SPI Speed = 40MHz, SPI Mode = DIO, Baud = 115200

### "Verify failed" or garbled output
- Wrong SPI mode — ensure DIO is selected, not QIO

---

## CAN Data Capture (for drive mode write frame identification)

To complete openrs-fw drive mode write support, the full `0x1B0` frame bytes need to be captured from your specific car while physically pressing the drive mode button.

1. Connect the openRS_ app to the WiCAN (stock firmware works for this)
2. Enable ATMA (Monitor All) mode in the app
3. Note the `0x1B0` frame at idle — byte 1 will be `5A` (released)
4. Press the drive mode button physically on the console — byte 1 changes to `5E` (pressed)
5. Record all 8 bytes for both states
6. Submit via GitHub issue at `https://github.com/klexical/openRS_/issues`

**Expected 0x1B0 format:**
```
Released: XX 5A XX XX XX XX XX XX
Pressed:  XX 5E XX XX XX XX XX XX
```
