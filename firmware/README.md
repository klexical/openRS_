# openrs-fw

Custom firmware for MeatPi WiCAN adapters, purpose-built for the Ford Focus RS MK3. Part of the [openRS_ project](../README.md).

Forked from [`meatpiHQ/wican-fw`](https://github.com/meatpiHQ/wican-fw) — the proven WiFi/CAN/OTA stack is retained and a Focus RS module (`focusrs`) is layered on top.

**Current versions:** USB v1.61-rc.1, PRO v1.2-rc.1 — release binaries in `firmware/release/`.

**Supported devices:** WiCAN USB-C3 (verified), WiCAN Pro (verified).

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
  - Simulates the physical drive mode button on CAN ID 0x305 (byte 4, bit 2)
  - Uses 0x420 to disambiguate Sport from Track (0x1B0 alone is ambiguous)
  - Persists selected mode to NVS — car boots in that mode next ignition on
- **ESC control** — On / Sport / Off via CAN button simulation (0x260 byte 5, bit 4)
- **Launch Control enable/disable**
- **Auto Start/Stop kill** — simulates ASS button on CAN 0x260 (byte 1, bit 0)
- **Boot apply** — on startup, automatically applies persisted drive mode, ESC mode, and ASS kill
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

### WiCAN Pro — Verified

The **MeatPi WiCAN Pro** is a higher-end adapter with onboard GPS, MicroSD logging, and a raw TCP SLCAN interface (port 35000). openRS_ firmware PRO v1.0 is the first stable release for this hardware.

| Feature | Status |
|---------|--------|
| Raw TCP SLCAN connection (app) | ✅ Working |
| Passive CAN frame reception (app) | ✅ Working |
| OBD polling PCM/BCM/AWD/PSCM (app) | ✅ Working |
| DTC scan and clear (app) | ✅ Working |
| openrs-fw build (`build.sh --target pro`) | ✅ Working |
| openrs-fw `OPENRS?` probe (slcan.c) | ✅ Working |
| openrs-fw `AT+FRS` command channel (slcan.c) | ✅ Implemented — pending flash test |
| CAN write (drive mode) | ✅ Working |
| CAN write (ESC) | ⚠️ Under investigation ([#125](https://github.com/klexical/openRS_/issues/125)) |
| CAN write (LC, ASS) | ✅ Working |
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

> **Note:** The Pro build target patches onto wican-fw v4.48p (the latest Pro release), giving it all of MeatPi's latest fixes including improved AutoPID, WireGuard, and CAN filter support. All openrs-fw features (CAN read, CAN write, REST API, OPENRS? probe) are verified working. Drive mode control and boot apply confirmed on real hardware. ESC write is under investigation ([#125](https://github.com/klexical/openRS_/issues/125)).

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
| `usb` (default) | WiCAN USB-C3 | ESP32-C3 | `v4.20u_beta-01` | `openrs-fw-usb_v1.5.bin` |
| `pro` | WiCAN Pro | ESP32-S3 | `v4.48p` | `openrs-fw-pro_v1.0.bin` |

The script will:
1. Install ESP-IDF v5.2.3 to `firmware/.build/esp-idf` if not already present (~5–15 min, one-time)
2. Clone `meatpiHQ/wican-fw` at the target's pinned tag into `firmware/.build/<target>/wican-fw/`
3. Copy the shared `focusrs` component into the wican-fw components directory
4. Apply target-specific source patches (SSID branding, CAN RX hook, REST endpoint, OPENRS? probe, AT+FRS command channel)
5. Build for the target SoC
6. Copy all flash-ready `.bin` files to `firmware/release/`

### Output

```
firmware/release/
  bootloader_usb.bin          ← flash at 0x0      (USB build)
  partition-table_usb.bin     ← flash at 0x8000
  ota_data_initial_usb.bin    ← flash at 0xd000
  openrs-fw-usb_v1.5.bin      ← flash at 0x10000

  bootloader_pro.bin          ← flash at 0x0      (Pro build)
  partition-table_pro.bin     ← flash at 0x8000
  ota_data_initial_pro.bin    ← flash at 0xd000
  openrs-fw-pro_v1.0.bin      ← flash at 0x10000
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
  "version": "openrs-fw USB v1.5",
  "connected": true,
  "can_bitrate": 500000,
  "battery_mv": 13420,
  "drive_mode": 1,
  "ble_connected": false,
  "uptime_s": 3600
}
```

### `GET /api/frs`
```json
{
  "driveMode": 0,
  "bootMode": 0,
  "escMode": 0,
  "bootEsc": 0,
  "lcEnabled": false,
  "assKill": false,
  "battMv": 12000,
  "sleepMv": 12200
}
```

### `POST /api/frs`

All POST requests require `"token": "openrs"` for basic access control.

```json
{ "token": "openrs", "driveMode": 1 }      // 0=Normal, 1=Sport, 2=Drift, 3=Track
{ "token": "openrs", "escMode": 0 }         // 0=On, 1=Sport, 2=Off
{ "token": "openrs", "enableLC": true }     // Launch Control
{ "token": "openrs", "killASS": true }      // Auto Start/Stop kill
{ "token": "openrs", "sleepVoltage": 12200 } // Battery sleep threshold (mV)
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

The BLE interface is protocol-compatible with the WiFi TCP interface. The openRS_ Android app uses the same SLCAN parser for both transports.

### AT+FRS Command Channel (BLE + TCP + WebSocket)

The `AT+FRS` command protocol provides a second entry point for firmware commands (drive mode, ESC, LC, ASS, sleep voltage) alongside the REST `/api/frs` endpoint. Commands are intercepted in `slcan_parse_str()` before the SLCAN parser, so they work over **any** transport — BLE GATT, TCP, or WebSocket.

**Set command:** `AT+FRS=key,value\r`

| Key | Value | Equivalent REST | Handler |
|-----|-------|-----------------|---------|
| `driveMode` | `0`-`3` (Normal/Sport/Drift/Track) | `POST /api/frs {"driveMode":N}` | `frs_set_drive_mode()` |
| `escMode` | `0`-`2` (On/Sport/Off) | `POST /api/frs {"escMode":N}` | `frs_set_esc()` |
| `enableLC` | `true`/`false`/`1`/`0` | `POST /api/frs {"enableLC":bool}` | `frs_set_lc()` |
| `killASS` | `true`/`false`/`1`/`0` | `POST /api/frs {"killASS":bool}` | `frs_set_ass_kill()` |
| `sleepVoltage` | `10000`-`15000` (millivolts) | `POST /api/frs {"sleepVoltage":N}` | `frs_set_sleep_threshold()` |

**Query command:** `AT+FRS?\r`

**Responses (firmware → app):**

| Response | Meaning |
|----------|---------|
| `+FRS:OK\r` | Command accepted |
| `+FRS:BUSY\r` | Drive mode change already in progress |
| `+FRS:ERROR,message\r` | Invalid syntax, value, or unknown key |
| `+FRS:driveMode=N,escMode=N,lcEnabled=bool,assKill=bool,battMv=N,sleepMv=N\r` | State query response |

**Examples:**
```
→  AT+FRS=driveMode,1\r          ←  +FRS:OK\r
→  AT+FRS=escMode,2\r            ←  +FRS:OK\r
→  AT+FRS=driveMode,1\r          ←  +FRS:BUSY\r         (change in progress)
→  AT+FRS?\r                     ←  +FRS:driveMode=1,escMode=0,lcEnabled=false,assKill=false,battMv=12600,sleepMv=12200\r
→  AT+FRS=driveMode,9\r          ←  +FRS:ERROR,invalid mode\r
```

> **Note:** No authentication token is required for AT+FRS commands (unlike the REST endpoint which requires `{"token":"openrs"}`). BLE pairing provides the access control layer.

---

## Drive Mode CAN Frames

### Reading current mode (HS-CAN, passive)
| CAN ID | Byte | Bits | Value mapping |
|--------|------|------|---------------|
| `0x1B0` | B6 | upper nibble | 0=Normal, 1=Sport/Track (ambiguous), 2=Drift |
| `0x420` | B6+B7 | B6: mode group, B7 bit 0: detail | B6=0x10 Normal, 0x11 Sport/Track, 0x12 Drift; B7 bit0: 0=Sport, 1=Track |

### Writing mode (button simulation on 0x305)
| Action | CAN ID | Byte | Bit | Notes |
|--------|--------|------|-----|-------|
| Button press | `0x305` | B4 (data[4]) | bit 2 | Set `\|= 0x04`, inject at 100 Hz for 300ms |
| Button release | `0x305` | B4 (data[4]) | bit 2 | Car's own next frame clears the bit |
| GUI detection | `0x305` | B4 (data[4]) | bit 4 | `0x10` = mode selector GUI visible on cluster |

Confirmed via SLCAN diagnostic 2026-03-21: bit 2 (`0x04`) is the button input, bit 4 (`0x10`) is the BCM GUI indicator. Template captured from live CAN bus at runtime. Each press cycles N→S→T→D→N. Firmware detects GUI-open state and skips activation press when already visible.

### ESC button simulation (0x260)
| Action | CAN ID | Byte | Bit | Notes |
|--------|--------|------|-----|-------|
| ESC Off button | `0x260` | B6 (data[5]) | bit 4 | Set `\|= 0x10`, same 3-frame pattern |
| ASS button | `0x260` | B1 (data[0]) | bit 0 | Set `\|= 0x01`, same 3-frame pattern |

ESC cycles On→Sport→Off→On (3 states). ASS is a toggle (press to disable).

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
│   │   ├── focusrs_nvs.h
│   │   ├── focusrs_uds.c             ← UDS ABS probe
│   │   └── focusrs_uds.h
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
│   ├── openrs-fw-usb_v1.5.bin       ← current (USB v1.5)
│   ├── openrs-fw-pro_v1.0.bin       ← current (PRO v1.0)
│   ├── bootloader_usb.bin / bootloader_pro.bin
│   ├── partition-table_usb.bin / partition-table_pro.bin
│   ├── ota_data_initial_usb.bin / ota_data_initial_pro.bin
│   └── BUILD_MANIFEST_usb.json / BUILD_MANIFEST_pro.json
└── stock/                             ← stock wican-fw binaries (reference)
```

---

## Roadmap

For the full project roadmap (app + firmware), see the [root README](../README.md#roadmap).

### Next — ESC Fix and Polish

- **ESC write fix** ([#125](https://github.com/klexical/openRS_/issues/125)) — app commands reach the firmware but the ABS module does not respond. Requires investigation of 0x260 injection timing and byte offsets.
- **BLE stability improvements** — test coexistence under sustained high-throughput CAN + BLE + WiFi load
- **Drive mode boot-apply edge cases** — test cold start, warm restart, and rapid mode switching scenarios

### fw-v2.x — Expanded Capability

- **Dual-CAN (MS-CAN)** — add MS-CAN at 125 kbps alongside HS-CAN at 500 kbps (if WiCAN Pro hardware supports dual TWAI)
- **On-device data logging** — log CAN frames to onboard flash/SD for post-session retrieval without a connected phone
- **GPS passthrough** — for MeatPi Pro, relay onboard GPS coordinates to the Android app over the existing WebSocket/TCP channel

---

## Contributing

openrs-fw is part of the openRS_ project. Issues and PRs welcome at:
`https://github.com/klexical/openRS_`

CAN frame data was validated on a 2018 Focus RS MK3. If you have captured frames from a different model year, please open an issue with your data.

---

## License

Based on `meatpiHQ/wican-fw` (MIT License). openrs-fw additions are also MIT.
