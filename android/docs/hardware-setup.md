# Hardware Setup Guide

## WiCAN-USB-C3 Adapter

openRS_ connects to a [MeatPi WiCAN-USB-C3](https://www.mouser.ca/ProductDetail/MeatPi/WICAN-USB-C3?qs=rQFj71Wb1eVDX2eEy0FC7A%3D%3D) USB-CAN adapter via Wi-Fi (primary) or BLE (fallback when WiFi is unavailable).

### Hardware Specifications

| Attribute | Value |
|-----------|-------|
| **Manufacturer** | MeatPi Electronics |
| **Part Number** | WICAN-USB-C3 |
| **Mouser #** | 392-WICAN-USB-C3 |
| **Microcontroller** | ESP32-C3 |
| **Interfaces** | CAN bus, USB, Wi-Fi 2.4GHz, BLE 5.0 |
| **Operating Voltage** | 7.5V – 36V (OBD-II pin 16, always-on) |
| **Weight** | 40g |
| **Country of Origin** | Australia |
| **RoHS** | Compliant |
| **Price (CAD)** | ~$64.47 (qty 1) |

> **BLE fallback note:** If your phone's WiFi radio is occupied (e.g. by a wireless projection dongle), the openRS_ firmware exposes a BLE GATT data service as a fallback — the app can connect over BLE when WiFi isn't available. No manual switching required.

### Configuration

openRS_ uses the WiCAN's **WebSocket endpoint** — no mode change required in the WiCAN web UI. Stock firmware exposes `ws://192.168.80.1:80/ws` by default.

| Setting | Value |
|---------|-------|
| Wi-Fi SSID | `WiCAN_XXXXXX` |
| IP Address | `192.168.80.1` |
| WebSocket Port | `80` |
| WebSocket Path | `/ws` |
| Protocol | SLCAN (app sends `C` / `S6` / `O` on connect) |
| CAN Speed | 500 kbps |

> **Note:** The old ELM327 TCP port (3333) is no longer used. If you upgraded from an earlier version of openRS_, go to Settings and verify the port shows **80**, not 3333.

### First-Time Setup

**Step 1 — Physical install**
1. Locate the OBD-II port: under the steering column, left of the hood release lever
2. Plug in the WiCAN — the angled connector faces down to avoid knee contact
3. Turn the car to ACC or RUN — the WiCAN LED will flash then go solid

**Step 2 — Connect your phone to the WiCAN hotspot**
1. On your phone, open WiFi settings
2. Connect to the network named `WiCAN_XXXXXX` (device ID printed on device label)
3. Default password: `@meatpi#`

**Step 3 — Verify WiCAN configuration**
1. Open a browser on your phone → go to `http://192.168.80.1`
2. The WiCAN web interface loads
3. Confirm these settings under **CAN Settings / Device Settings**:

| Setting | Required Value |
|---------|----------------|
| WiFi Mode | `AP` |
| CAN Speed | `500 kbps` |
| BLE | `Disabled` (unless using BLE transport) |

> openRS_ connects via **WebSocket on port 80** — no ELM327 TCP configuration is required. The mode dropdown in the WiCAN web UI does not matter for openRS_.

4. If CAN speed differs, update and press **Save** — the device reboots

**Step 4 — Connect openRS_**
1. Open the openRS_ app
2. Tap **● OFFLINE** in the top-right header → it changes to **● CONNECTED**
3. The app connects to `ws://192.168.80.1:80/ws`, initialises SLCAN at 500 kbps, and begins streaming the full CAN bus at ~2100 fps

> **Tip:** Once connected you can also access the web interface at `http://192.168.80.1`. It is recommended to change the AP password from the default in the Settings tab — anyone nearby while the car is running could otherwise connect.

> **Sleep mode:** The WiCAN can be left permanently plugged in without draining the battery. In the Settings tab, enable **Sleep Mode** and set the voltage threshold to ~13.1V. When the engine is off and voltage stays below the threshold for 3 minutes, the WiCAN sleeps and draws less than 1mA. It wakes instantly when the engine starts.

### Changing the WiCAN IP / Port

If your WiCAN is configured in **Client mode** (joining your phone's hotspot), the IP address will be different from `192.168.80.1`. You can update the connection target inside the app:

**Settings → WiCAN Host / Port**

The app saves your last-used values and reconnects automatically.

## Firmware

The WiCAN ships with the official MeatPi firmware. openRS_ works with the stock firmware for all PID telemetry.

For drive mode control, ESC write, Launch Control, BLE transport, and Auto-discovery, flash **openrs-fw**:

| Firmware | Use case | Download |
|----------|----------|----------|
| Official WiCAN | Basic PID telemetry (first test) | [GitHub Releases](https://github.com/meatpiHQ/wican-fw/releases/latest) |
| **openrs-fw v1.4** | Full openRS_ feature set | [openRS_ Releases](https://github.com/klexical/openRS_/releases) |

See the [Firmware Update Guide](firmware-update.md) for step-by-step flashing instructions with screenshots.

---

## WiCAN Pro Adapter

openRS_ also supports the [MeatPi WiCAN Pro](https://www.meatpi.com/) via raw TCP SLCAN. The Pro adds GPS, MicroSD logging, and an ESP32-S3 with more memory, but requires a one-time configuration change before openRS_ can connect.

### Hardware Specifications

| Attribute | Value |
|-----------|-------|
| **Manufacturer** | MeatPi Electronics |
| **Microcontroller** | ESP32-S3 (dual-core, 16 MB flash, 8 MB PSRAM) |
| **Interfaces** | CAN bus, USB-C, Wi-Fi 2.4 GHz, BLE 5.0, GPS, MicroSD |
| **Operating Voltage** | 7.5V – 36V (OBD-II pin 16, always-on) |
| **Extras** | GPS passthrough, on-device MicroSD CAN logging |

### First-Time Setup

**Step 1 — Physical install**
1. Plug the WiCAN Pro into the OBD-II port (same location as USB-C3)
2. Turn the car to ACC or RUN — the Pro LED will flash then go solid

**Step 2 — Connect to the Pro's hotspot**
1. Connect your phone to `WiCAN_XXXXXX` (default password `@meatpi#`)
2. Default AP address: `192.168.0.10`

**Step 3 — Set protocol to SLCAN (required)**

> **This is the critical step.** The WiCAN Pro does **not** default to SLCAN mode. Without this change, openRS_ will connect but receive zero CAN frames.

1. Open a browser → go to `http://192.168.0.10`
2. In the **CAN** or **Protocol** settings, change the mode to **SLCAN**
3. Set CAN speed to **500 kbps** (if not already)
4. Confirm the TCP port is **35000**
5. Press **Save** — the device reboots

| Setting | Required Value |
|---------|----------------|
| Protocol / Mode | **SLCAN** |
| CAN Speed | `500 kbps` |
| TCP Port | `35000` |

**Step 4 — Configure openRS_**
1. Open the app → Settings (gear icon)
2. Change **Adapter** to **MeatPi Pro**
3. Host auto-fills to `192.168.0.10`, port to `35000` — verify these match your Pro's config
4. Tap **Save**, then tap the connection dot to disconnect and reconnect

**Step 5 — Verify connection**
1. Go to **DIAG** tab → check the Live CAN Output section
2. You should see `Connecting to 192.168.0.10:35000` → `SLCAN init sent` → CAN frames flowing
3. FPS should climb to ~2000+ within seconds

> **Symptom if SLCAN is not set:** The app will show `SLCAN init sent (C / S6 / O)` repeating in a loop with 0 frames and 0 FPS. The TCP connection succeeds but no CAN data flows because the Pro is in a different protocol mode (e.g. ELM327).

---

## OBD-II Port Pinout (Focus RS MK3)

| Pin | Signal | Notes |
|-----|--------|-------|
| 4 | Chassis Ground | |
| 5 | Signal Ground | |
| 6 | HS-CAN High | 500 kbps — primary bus |
| 14 | HS-CAN Low | 500 kbps — primary bus |
| 3 | MS-CAN High | 125 kbps — secondary bus (TPMS broadcast) |
| 11 | MS-CAN Low | 125 kbps — secondary bus |
| 16 | Battery +12V | Always on |

openRS uses the HS-CAN bus (pins 6/14) for all data. The standard WiCAN connects to HS-CAN automatically.

## OBD-II Port Location

The Focus RS MK3 OBD-II port is under the steering column, to the left of the hood release lever. The WiCAN's angled connector faces down to avoid knee contact.
