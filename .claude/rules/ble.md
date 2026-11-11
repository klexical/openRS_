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
  - **Why:** the WiCAN Pro (upstream v4.48p) declares FFF2 with a 20-byte maximum but its stack (ESP-IDF 5.2.3) delivers a longer
    write and then answers 0x0D. Every 22-byte `t…` frame (PCM polls, DTC requests, probes) is one ATT write at MTU 247.
    Source-level only: no GATT trace from a real adapter. USB FEE1 allows 64 bytes.
  - **Rule:** POSSIBLY_DELIVERED keeps the request outstanding and waits for its reply; nothing is ever resent, and it is never
    a drive-mode / ESC confirmation (CAN only). Only a certainly-not-sent failure withdraws the request
    (`PcmRequestLedger.withdraw`). Other post-send failures are uncertain and kept. TCP / WebSocket unchanged.
  - The first GATT 13 on a connection logs one BLE_WRITE event. `OPENRS?` / `AT+FRS…` (≤ 19 bytes) are unaffected.
  - Don't chunk to ≤ 20 bytes: the firmware rebuilds a split line only if the pieces arrive within 100 ms (`slcan_parse_str`
    static state). The MTU-23 path (20 + 2) already depends on that window — pre-existing, hardware-unverified.
  - Throwing on GATT 13 caused the DID prober crash (#249) and never-attributed NRCs over BLE (#216).
- **BLE write timeout = link closed (#218)** — Android's `onCharacteristicWrite` names no write; after a 2 s timeout a late
  callback would complete the next write. `BleGattCore` invalidates + closes the link (reader EOF → SlcanConnection
  reconnects); later writes fail at once. `SlcanConnection` swallows write errors, so teardown must come from the reader.
- **BLE GATT layouts (#218)** — upstream WiCAN Pro v4.45p–v4.48p: FFF0, write FFF2, notify FFF1; USB v4.13u–v4.21u: FEE0, FEE1
  write + notify; both need an encrypted, MITM-authenticated (bonded) link (upstream source reading, not a capture).
  - FFE0/FFE1/FFE2 is declared only: `firmware/components/ble_transport/ble_transport.h` is a forward declaration;
    openrs-fw v1.x keeps upstream wican-fw's `ble.c` at the pinned tag (USB v4.20u_beta-01, PRO v4.48p —
    `firmware/patches/` doesn't touch it). The app keeps FFE0 as an unverified fallback (v2.2.6 scanned / connected FFE0 only).
  - Which layout an openrs-fw adapter advertises is unknown: there is no GATT trace, and a successful BLE session doesn't
    name the layout. Never infer a shipped layout from that header.
  - FFF0 / FFE0 are also used by unrelated cheap BLE peripherals; the scan filter passes them through. `BleDevicePickerDialog` shows an orange "not a known adapter" hint for unrecognized device names. `SlcanConnection` has a 3s SLCAN handshake that rejects non-SLCAN devices before declaring Connected
- **BLE auto-reconnect** — `autoConnect=true` on `connectGatt()` once setup has succeeded with that MAC in this process
  (`BleSlcanTransport.connectedBefore`; an instance flag was dead — every attempt builds a new transport). 15 s setup
  timeout; on timeout `SlcanConnection` retry logic takes over. Emulated only: not exercised on a phone with an adapter
- **BLE MTU** — `requestMtu(247)` requested after service discovery. If `requestMtu()` returns false (unsupported), proceeds with 23-byte default. SLCAN frames mostly fit; longer frames arrive as multiple BLE notifications and are reassembled by `lineBuffer`
- **BLE permissions must be requested at scan time** — `BleDevicePickerDialog` handles runtime permission requests (BLUETOOTH_SCAN + BLUETOOTH_CONNECT on API 31+). Do NOT rely solely on `MainActivity.onCreate()` permission requests — the user may switch to Bluetooth after app startup. `BleDeviceScanner.startScan()` also has a SecurityException safety net
- **BLE picker (#218)** — never cache `bluetoothLeScanner` (null while the PHONE radio is off); `BleScanBackend`
  seam, `BleScanState.PhoneBluetoothOff` → enable prompt (`ACTION_REQUEST_ENABLE`), retry on accept / `STATE_ON`; the
  emulator AVD has a virtual Bluetooth adapter (`adb shell cmd bluetooth_manager enable|disable`) for picker checks.
  Every scan has an id (`activeScan`): stop / restart / Bluetooth-off invalidate it and callbacks of an old scan are dropped;
  the enable result only starts a scan while still `PhoneBluetoothOff` (broadcast + result in either order = one scan). The
  picker Column scrolls — in landscape its content is taller than the window (CANCEL was unreachable)
