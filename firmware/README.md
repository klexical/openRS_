# openrs-fw

Custom ESP32-C3 firmware for the MeatPi WiCAN-USB-C3, purpose-built for the Ford Focus RS MK3.

Forked from [`meatpiHQ/wican-fw`](https://github.com/meatpiHQ/wican-fw) — the proven WiFi/CAN/OTA stack is retained and a Focus RS module is layered on top.

**Current status:** v1.0 in active development.

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
  - Auto-selected by the openRS_ app when WiFi is occupied (AA Wireless)
  - Coexists with WiFi — both active simultaneously
- **Battery protection** — configurable voltage threshold (default 12.2V) for sleep mode
- **Battery voltage REST API** (`GET /status` → includes `battery_mv`)
- Branded as `openRS_` (SSID: `openRS_XXXXXX`, BLE: `openRS_WiCAN`)

---

## Hardware Target

| Field | Value |
|-------|-------|
| Board | MeatPi WiCAN-USB-C3 |
| SoC | ESP32-C3 |
| Toolchain | ESP-IDF v5.x |
| CAN Driver | TWAI (ESP32 built-in) |
| CAN Speed | 500 kbps (HS-CAN) |
| WiFi | 2.4GHz 802.11 b/g/n |
| BLE | Bluetooth 5.0 LE |

---

## Build Instructions

### Prerequisites

```bash
# Install ESP-IDF v5.x
git clone --recursive https://github.com/espressif/esp-idf.git
cd esp-idf && ./install.sh esp32c3
source ./export.sh
```

### Clone and configure

```bash
git clone https://github.com/klexical/openRS_
cd "openRS_/firmware"

# Copy base wican-fw source (see BUILDING.md for full setup)
idf.py set-target esp32c3
idf.py menuconfig   # Optional: adjust WiFi defaults in Component config → openrs-fw
```

### Build and flash

```bash
idf.py build
idf.py -p /dev/ttyUSB0 flash monitor
```

> For OTA updates (WiCAN already running), build only and upload via web UI:
> ```bash
> idf.py build
> # Then upload build/openrs-fw.bin via http://192.168.80.1 Settings → Firmware Update
> ```

---

## REST API

All endpoints inherit from wican-fw and extend it:

### `GET /status`
```json
{
  "version": "openrs-fw v1.0",
  "connected": true,
  "can_bitrate": 500000,
  "battery_mv": 13420,
  "drive_mode": 1,
  "ble_connected": false,
  "uptime_s": 3600
}
```

### `POST /settings`
```json
{ "driveMode": 1 }      // 0=Normal, 1=Sport, 2=Track, 3=Drift
{ "escMode": 0 }         // 0=On, 1=Sport, 2=Off
{ "enableLC": true }     // Launch Control
{ "killASS": true }      // Auto Start/Stop kill
{ "sleepVoltage": 12.2 } // Battery sleep threshold (V)
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
| `0x1B0` | — | 55–58 (4-bit Motorola) | 0=Normal, 1=Sport, 2=Track, 3=Drift |

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
| `rs_drivemode` | uint8 | Last selected drive mode (0–3) |
| `rs_bootmode` | uint8 | Mode to set on next boot |
| `rs_esc` | uint8 | ESC state |
| `rs_lc` | bool | Launch control enabled |
| `rs_ass_kill` | bool | Auto S/S kill enabled |
| `sleep_thresh_mv` | uint16 | Battery sleep threshold in millivolts |

---

## Directory Structure

```
firmware/
├── README.md                    ← this file
├── CMakeLists.txt
├── sdkconfig.defaults
├── main/
│   ├── CMakeLists.txt
│   ├── main.c                   ← app_main, init sequence
│   └── Kconfig.projbuild        ← menuconfig options
├── components/
│   ├── focusrs/                 ← Focus RS CAN module
│   │   ├── CMakeLists.txt
│   │   ├── focusrs.h
│   │   ├── focusrs.c            ← drive mode read/write, ESC, LC, ASS
│   │   └── focusrs_nvs.c        ← NVS persistence
│   └── ble_transport/           ← BLE GATT ELM327 bridge
│       ├── CMakeLists.txt
│       ├── ble_transport.h
│       └── ble_transport.c      ← GATT server, ELM327 notify characteristic
└── docs/
    └── BUILDING.md              ← detailed build environment setup
```

---

## Contributing

openrs-fw is part of the openRS_ project. Issues and PRs welcome at:
`https://github.com/klexical/openRS_`

Focus RS-specific CAN frame data (0x1B0 write frame template, ESC frames) is actively being collected during car testing. If you have captured frames from your own RS, please open an issue with your data.

---

## License

Based on `meatpiHQ/wican-fw` (MIT License). openrs-fw additions are also MIT.
