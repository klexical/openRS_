# Hardware Setup Guide

## WiCAN OBD-II Adapter

openRS connects to a [MeatPi WiCAN](https://www.mouser.com/ProductDetail/MeatPi/WICAN-USB-C3?qs=rQFj71Wb1eVDX2eEy0FC7A%3D%3D) adapter via Wi-Fi TCP.

### Configuration

The WiCAN should be in its default configuration:

| Setting | Value |
|---------|-------|
| Mode | ELM327 TCP |
| Wi-Fi SSID | `WiCAN_XXXXXX` |
| IP Address | `192.168.80.1` |
| Port | `3333` |
| Protocol | CAN 500 kbps (auto-detected via `ATSP6`) |

### Installation

1. **Locate the OBD-II port**: Under the steering column, to the left of the hood release
2. **Plug in the WiCAN**: Angled connector works best to avoid knee contact
3. **Power**: The WiCAN powers on with the ignition (ACC or RUN)
4. **Wi-Fi**: Connect your phone to the WiCAN's Wi-Fi network

### Tips

- The WiCAN creates its own Wi-Fi network — you don't need internet connectivity
- If using Android Auto with a wired connection, the phone can still connect to WiCAN Wi-Fi
- For wireless AA, you may need a USB Wi-Fi adapter or dual-band configuration
- The WiCAN draws minimal power but will drain the battery if left plugged in with ACC on

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

## Second WiCAN (Optional)

For direct MS-CAN access (TPMS broadcast on 0x340 at 125 kbps):

1. You need a second WiCAN configured for MS-CAN (125 kbps)
2. Connect to pins 3/11 via a Y-splitter or custom harness
3. This provides raw tire pressures without BCM Mode 22 queries
4. Not required — the BCM method works well and is simpler

## Tested Head Units

| Unit | Type | AA Connection | Status |
|------|------|---------------|--------|
| Ford Sync 3 | OEM | USB | ✅ Works |
| Kenwood DMX907S | Aftermarket | USB + Wireless | ✅ Works |
| Pioneer DMH-WT76NEX | Aftermarket | USB | ✅ Works |
| Android Auto DHU | Desktop emulator | ADB | ✅ Works (dev only) |
