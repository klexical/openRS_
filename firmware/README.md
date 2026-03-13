# openrs-fw

Custom firmware for MeatPi WiCAN adapters, purpose-built for the Ford Focus RS MK3. Part of the [openRS_ project](../README.md).

Forked from [`meatpiHQ/wican-fw`](https://github.com/meatpiHQ/wican-fw) — the proven WiFi/CAN/OTA stack is retained and a Focus RS module (`focusrs`) is layered on top.

**Current status:** v1.4 — release binaries in `firmware/release/`.

**Supported devices:** WiCAN USB-C3 (verified), WiCAN Pro (experimental).

---

## Features

### Core (inherited from wican-fw)
- ELM327 TCP passthrough on port 3333
- ATMA (CAN Monitor All) mode
- OTA firmware updates via web browser
- WiFi AP mode with configurable SSID/password
- REST API (`/settings`, `/status`)

### Focus RS additions (openrs-fw)
- **Drive mode write** — send N/S/T/D directly from the app
  - Immediately changes the physical drive mode (CAN 0x1B0 button simulation)
  - Persists selected mode to NVS — car boots in that mode next ignition on
- **ESC control** — On / Sport / Off via CAN
- **Launch Control enable/disable**
- **Auto Start/Stop kill**
- **BLE GATT transport** — exposes ELM327 stream over BLE 5.0
  - Fallback when the phone's WiFi radio is occupied (e.g. wireless projection)
  - Coexists with WiFi — both active simultaneously
- **Battery protection** — configurable voltage threshold (default 12.2V) for sleep mode
- **Battery voltage REST API** (`GET /api/frs` → includes `battery_mv`)
- Branded as `openRS_` (SSID: `openRS_XXXXXX`, BLE: `openRS_WiCAN`)

---

## Hardware Compatibility

### Primary Target — WiCAN-USB-C3

| Field | Value |
|-------|-------|
| Board | MeatPi WiCAN-USB-C3 |
| SoC | ESP32-C3 |
| Toolchain | ESP-IDF v5.x |
| CAN Driver | TWAI (ESP32 built-in) |
| CAN Speed | 500 kbps (HS-CAN) |
| WiFi | 2.4GHz 802.11 b/g/n |
| BLE | Bluetooth 5.0 LE |

### WiCAN Pro — Experimental Support

The **MeatPi WiCAN Pro** is a higher-end adapter with onboard GPS, MicroSD logging, and a raw TCP SLCAN interface (port 35000). The openRS_ Android app supports the Pro for **passive CAN + OBD polling** out of the box with stock firmware.

| Feature | Status |
|---------|--------|
| Raw TCP SLCAN connection (app) | ✅ Working |
| Passive CAN frame reception (app) | ✅ Working |
| OBD polling PCM/BCM/AWD/PSCM (app) | ✅ Working |
| DTC scan and clear (app) | ✅ Working |
| openrs-fw build (`build.sh --target pro`) | ⚠️ Compiles (pending hardware test) |
| openrs-fw `OPENRS?` probe (slcan.c) | ⚠️ Patched (pending hardware test) |
| CAN write (drive mode, ESC, LC, ASS) | ⚠️ Patched (pending hardware test) |
| GPS NMEA passthrough | ❌ Not yet implemented |
| MicroSD remote control | ❌ Not yet implemented |

| Field | Value |
|-------|-------|
| Board | MeatPi WiCAN Pro |
| SoC | ESP32-S3 (Xtensa) |
| Flash | 16MB |
| PSRAM | 8MB (octal, 80MHz) |
| Upstream tag | `v4.48p` |
| CAN Driver | TWAI |
| CAN Speed | 500 kbps (HS-CAN) |

> **Note:** The Pro build target patches onto wican-fw v4.48p (the latest Pro release), giving it all of MeatPi's latest fixes including improved AutoPID, WireGuard, and CAN filter support. All openrs-fw features (CAN read, CAN write, REST API, OPENRS? probe) are patched — pending hardware flash test. See [#76](https://github.com/klexical/openRS_/issues/76) for the test checklist.

---

## Build Instructions

A single script handles everything — ESP-IDF installation, cloning wican-fw, applying openrs-fw patches, and packaging the release binaries.

### One-command build

```bash
# Build for WiCAN USB-C3 (default):
cd "openRS_/firmware"
./build.sh

# Build for WiCAN Pro:
./build.sh --target pro
```

The `--target` flag selects the device profile:

| Target | Device | SoC | Upstream tag | Output binary |
|--------|--------|-----|-------------|---------------|
| `usb` (default) | WiCAN USB-C3 | ESP32-C3 | `v4.20u_beta-01` | `openrs-fw-usb_v140.bin` |
| `pro` | WiCAN Pro | ESP32-S3 | `v4.48p` | `openrs-fw-pro_v140.bin` |

The script will:
1. Install ESP-IDF v5.2.3 to `firmware/.build/esp-idf` if not already present (~5–15 min, one-time)
2. Clone `meatpiHQ/wican-fw` at the target's pinned tag into `firmware/.build/<target>/wican-fw/`
3. Copy the shared `focusrs` component into the wican-fw components directory
4. Apply target-specific source patches (SSID branding, CAN RX hook, REST endpoint, OPENRS? probe)
5. Build for the target SoC
6. Copy all flash-ready `.bin` files to `firmware/release/`

### Output

```
firmware/release/
  bootloader_usb.bin          ← flash at 0x0      (USB build)
  partition-table_usb.bin     ← flash at 0x8000
  ota_data_initial_usb.bin    ← flash at 0xd000
  openrs-fw-usb_v140.bin      ← flash at 0x10000

  bootloader_pro.bin          ← flash at 0x0      (Pro build)
  partition-table_pro.bin     ← flash at 0x8000
  ota_data_initial_pro.bin    ← flash at 0xd000
  openrs-fw-pro_v140.bin      ← flash at 0x10000
```

### Building both targets

```bash
./build.sh --target usb
./build.sh --target pro
```

Each target has its own build directory (`firmware/.build/usb/`, `firmware/.build/pro/`) so they don't interfere. ESP-IDF is shared.

### Re-running the build

The script is safe to re-run. It skips steps that are already complete (ESP-IDF install, wican-fw clone). To force a clean rebuild for a specific target:

```bash
rm -rf firmware/.build/usb   # or firmware/.build/pro
./build.sh --target usb
```

### If ESP-IDF is already installed

Set `IDF_PATH` to skip the install step:

```bash
IDF_PATH=~/your/esp-idf/path ./firmware/build.sh
```

---

## REST API

All endpoints inherit from wican-fw and extend it. Example responses below are illustrative — actual values will reflect your device's live state:

### `GET /status`
```json
{
  "version": "openrs-fw v1.4",
  "connected": true,
  "can_bitrate": 500000,
  "battery_mv": 13420,
  "drive_mode": 1,
  "ble_connected": false,
  "uptime_s": 3600
}
```

### `POST /api/frs`

All POST requests require `"token": "openrs"` for basic access control.

```json
{ "token": "openrs", "driveMode": 1 }      // 0=Normal, 1=Sport, 2=Drift, 3=Track
{ "token": "openrs", "escMode": 0 }         // 0=On, 1=Sport, 2=Off
{ "token": "openrs", "enableLC": true }     // Launch Control
{ "token": "openrs", "killASS": true }      // Auto Start/Stop kill
{ "token": "openrs", "sleepVoltage": 12.2 } // Battery sleep threshold (V)
```

### `GET /pids`
Returns current vehicle PID values (same format as Nutron RSdash).

---

## BLE GATT Service

**Service UUID:** `0000FFE0-0000-1000-8000-00805F9B34FB`

| Characteristic | UUID | Properties | Description |
|----------------|------|------------|-------------|
| ELM327 RX | `FFE1` | Write | Send AT commands / OBD requests |
| ELM327 TX | `FFE2` | Notify | Receive ELM327 responses |

The BLE interface is protocol-compatible with the WiFi TCP interface. The openRS_ Android app uses the same ELM327 parser for both transports.

---

## Drive Mode CAN Frames

### Reading current mode (HS-CAN, passive)
| CAN ID | Byte | Bits | Value mapping |
|--------|------|------|---------------|
| `0x1B0` | B6 | upper nibble | 0=Normal, 1=Sport/Track (ambiguous), 2=Drift |
| `0x420` | B6+B7 | B6: mode group, B7 bit 0: detail | B6=0x10 Normal, 0x11 Sport/Track, 0x12 Drift; B7 bit0: 0=Sport, 1=Track |

### Writing mode (button simulation)
| Action | CAN ID | Byte 1 | Notes |
|--------|--------|--------|-------|
| Button press | `0x1B0` | `0x5E` | Hold ~150ms |
| Button release | `0x1B0` | `0x5A` | Other bytes = template captured from car |

> **Note:** The full 8-byte frame template for 0x1B0 (the non-button bytes) must be captured from a live vehicle to populate the write frame correctly. Template values will be added after first car test. See `firmware-update.md` for capture instructions.

---

## NVS (Non-Volatile Storage) Keys

| Key | Type | Description |
|-----|------|-------------|
| `rs_bootmode` | uint8 | Persisted drive mode — set on next boot (0=Normal, 1=Sport, 2=Drift, 3=Track) |
| `rs_esc` | uint8 | ESC state |
| `rs_lc` | bool | Launch control enabled |
| `rs_ass_kill` | bool | Auto S/S kill enabled |
| `sleep_thresh_mv` | uint16 | Battery sleep threshold in millivolts |

---

## Directory Structure

```
firmware/
├── README.md                          ← this file
├── CHANGELOG.md                       ← firmware changelog
├── build.sh                           ← build script (--target usb|pro)
├── components/
│   ├── focusrs/                       ← Focus RS CAN module (shared, device-agnostic)
│   │   ├── CMakeLists.txt
│   │   ├── focusrs.h
│   │   ├── focusrs.c                  ← drive mode read/write, ESC, LC, ASS
│   │   ├── focusrs_nvs.c             ← NVS persistence
│   │   └── focusrs_nvs.h
│   └── ble_transport/                 ← BLE GATT ELM327 bridge
│       ├── CMakeLists.txt
│       └── ble_transport.h
├── patches/
│   ├── apply_patches.py               ← patch script (--target usb|pro)
│   ├── profiles/
│   │   ├── __init__.py
│   │   ├── usb.py                     ← USB-C3 profile (anchors, config)
│   │   └── pro.py                     ← Pro profile (anchors, config)
│   ├── sdkconfig.defaults.usb         ← ESP32-C3 build config
│   ├── sdkconfig.defaults.pro         ← ESP32-S3 build config
│   ├── partitions_openrs_usb.csv      ← 4MB flash, single OTA
│   └── partitions_openrs_pro.csv      ← 16MB flash, dual OTA
├── release/                           ← flash-ready binaries
│   ├── openrs-fw-usb_v140.bin
│   └── openrs-fw-pro_v140.bin
└── stock/                             ← stock wican-fw binaries (reference)
```

---

## Roadmap

For the full project roadmap (app + firmware), see the [root README](../README.md#roadmap).

### fw-v1.4.x — Verification and Stability

- **ESC write frame capture and implementation** — `frs_set_esc()` in `focusrs.c` is stubbed pending empirical CAN frame capture from a live vehicle
- **Drive mode 0x1B0 write template completion** — full 8-byte frame template needs capture from a live car (non-button bytes)
- **BLE stability improvements** — test coexistence under sustained high-throughput CAN + BLE + WiFi load

### fw-v2.x — Expanded Capability

- **Dual-CAN (MS-CAN)** — add MS-CAN at 125 kbps alongside HS-CAN at 500 kbps (if WiCAN Pro hardware supports dual TWAI)
- **On-device data logging** — log CAN frames to onboard flash/SD for post-session retrieval without a connected phone
- **GPS passthrough** — for MeatPi Pro, relay onboard GPS coordinates to the Android app over the existing WebSocket/TCP channel

---

## Contributing

openrs-fw is part of the openRS_ project. Issues and PRs welcome at:
`https://github.com/klexical/openRS_`

Focus RS-specific CAN frame data (0x1B0 write frame template, ESC frames) is actively being collected during car testing. If you have captured frames from your own RS, please open an issue with your data.

---

## License

Based on `meatpiHQ/wican-fw` (MIT License). openrs-fw additions are also MIT.
