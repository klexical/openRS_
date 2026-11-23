---
paths:
  - "**/com/openrs/dash/can/Ble*.kt"
  - "**/com/openrs/dash/ui/*Ble*"
  - "**/com/openrs/dash/can/SlcanConnection.kt"
  - "**/com/openrs/dash/can/*Ledger*"
  - "**/com/openrs/dash/can/WriteOutcome.kt"
  - "**/android/app/src/test/java/com/openrs/dash/*Ble*"
---
# openRS_ Known Gotchas — BLE GATT transport and picker

- **BLE writes have three outcomes (#248)** — `BleGattCore.write` returns a `WriteOutcome`: SENT, POSSIBLY_DELIVERED
  (GATT 13) or a `WriteFailure` (not sent / uncertain).
  - **Why:** the WiCAN Pro (upstream v4.48p) declares FFF2 with a 20-byte maximum but its stack (ESP-IDF 5.2.3) delivers a
    longer write and then answers 0x0D, and every 22-byte `t…` frame is one ATT write at MTU 247. Source-level only: no
    GATT trace from a real adapter. USB FEE1 allows 64 bytes.
  - **Rule:** POSSIBLY_DELIVERED keeps the request outstanding until its own reply; it is never resent and never a
    drive-mode / ESC confirmation (CAN only). Only a certainly-not-sent failure withdraws the request
    (`PcmRequestLedger.withdraw`); other post-send failures are uncertain and kept. Never throw on GATT 13 (`BleWriteOutcomeTest`).
  - Don't chunk to ≤ 20 bytes: the firmware rebuilds a split line only if the pieces arrive within 100 ms (`slcan_parse_str`
    static state). The MTU-23 path (20 + 2) already depends on that window — pre-existing, hardware-unverified.
- **BLE GATT layouts (#218)** — upstream WiCAN Pro v4.45p–v4.48p: FFF0, write FFF2, notify FFF1; USB v4.13u–v4.21u: FEE0,
  FEE1 write + notify; both need a bonded link (upstream source reading, not a capture). FFE0/FFE1/FFE2 is declared only
  (`firmware/components/ble_transport/ble_transport.h`); the app keeps FFE0 as an unverified fallback. Which layout an
  openrs-fw adapter advertises is unknown: never infer a shipped layout from that header.
  - FFF0 / FFE0 are also used by unrelated cheap BLE peripherals; the scan filter passes them through. `BleDevicePickerDialog` shows an orange "not a known adapter" hint for unrecognized device names. `SlcanConnection` has a 3s SLCAN handshake that rejects non-SLCAN devices before declaring Connected
- **BLE auto-reconnect** — `autoConnect=true` on `connectGatt()` once setup has succeeded with that MAC in this process
  (`BleSlcanTransport.connectedBefore`, companion state: every attempt builds a new transport, so an instance flag is
  dead). Emulated only: not exercised on a phone with an adapter
- **BLE connection priority (#513)** — setup asks for `CONNECTION_PRIORITY_HIGH` once, after the MTU exchange; a refusal
  does not fail setup. Unverified on hardware: whether the stack renegotiates the link and the rate rises is #513's drive
  check (`BleTransportTest`)
- **BLE permissions must be requested at scan time** — `BleDevicePickerDialog` handles runtime permission requests (BLUETOOTH_SCAN + BLUETOOTH_CONNECT on API 31+). Do NOT rely solely on `MainActivity.onCreate()` permission requests — the user may switch to Bluetooth after app startup. `BleDeviceScanner.startScan()` also has a SecurityException safety net
- **BLE picker (#218)** — never cache `bluetoothLeScanner` (null while the phone's radio is off). For picker checks the
  emulator AVD has a virtual Bluetooth adapter: `adb shell cmd bluetooth_manager enable|disable`
