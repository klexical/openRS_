---
paths:
  - "**/com/openrs/dash/diagnostics/**"
  - "**/com/openrs/dash/ui/DiagPage.kt"
  - "**/com/openrs/dash/can/SlcanConnection.kt"
  - "**/com/openrs/dash/can/*Ledger*"
  - "**/com/openrs/dash/service/CanDataService.kt"
  - "**/com/openrs/dash/ui/DidProberSection.kt"
  - "**/com/openrs/dash/ui/PidBrowserSection.kt"
  - "**/com/openrs/dash/data/Dtc*.kt"
  - "**/android/app/src/test/java/com/openrs/dash/*Dtc*"
  - "**/android/app/src/test/java/com/openrs/dash/*Probe*"
  - "**/android/app/src/test/java/com/openrs/dash/*Diagnostic*"
  - "**/android/app/src/test/java/com/openrs/dash/*Export*"
---
# openRS_ Known Gotchas — DTC scans, DID prober, diagnostic export

- **One DTC record per export** — `DiagnosticExporter.export` reads `DiagnosticLogger.lastDtcScan` once and passes it to
  the summary and the `dtc_scan_*` files; never read the ledger again inside one export
  (`DiagnosticExportDtcSnapshotTest`)
- **DTC scan truth (#215)** — a module is OK only for a valid positive `59 02 …` reply (`DtcReply.classify`). The export
  record is published only through `DiagnosticLogger.dtcScans` (`DtcScanLedger`): never write a DtcScanRecord directly,
  and DIAG's screen, export and header count publish only when the ledger accepts the ticket (`DtcScanView`). Emulated
  only: no real-module capture (#215). Freeze-frame sub-function numbering (app 04 / 05 vs ISO 03 / 04) is unverified,
  best-effort
- **Share intents set ClipData** (`DiagnosticExporter.shareClip`) beside `EXTRA_STREAM` — `createChooser` only passes the
  read grant to the share sheet through ClipData; without it the sheet's preview logs `Permission Denial … intentresolver`
- **Diagnostic-export adapter status (#233)** — `/check_status` belongs to the session (stale tokens are dropped) and an
  export builds text and JSON from one `AdapterStatusSnapshot`. The `Firmware :` line is the OPENRS? handshake — never
  derived from `/check_status` (`fw_version` there doesn't identify an openRS_ build)
- **Text from outside the app → `LogText.sanitize[Reason]`** (probe write failures #220, DTC write failures #215): printable,
  bounded, MAC / `WiC_…` / long hex masked. Probe events use `SlcanTransport.kind`, never `transport.label` (BLE label has
  name + MAC); non-frame adapter lines are counted, never logged
- **DTC scan writes and retries (#215)** — every scan write goes through `send(module, DtcStage, frame)` and a failed one
  reads its failure word, never `sent`; `DtcRawScan.statusWithoutReply` is the one rule for progress and result. A retry
  carries its own note (`DtcScanLedger.withNote`), and DIAG's NOT SCANNED row reads the displayed result
  (`DtcScanView.notScannedText`), never `DiagnosticLogger.lastDtcScan` (a newer scan would label an older row)
- **DID prober (#249)** — the scan runs in `diagnostics/DidProbeScan.kt`; `DidProberSection` only starts it and shows the end.
  - A failed query never reaches the UI scope: it ends the scan as `DidProbeEnd.Failed` with one sanitized `PROBE` event.
    The failed DID is never retransmitted; only a new scan started by the user sends again.
  - "Nothing was sent" rests on one shared message, `QUERY_NOT_CONNECTED`, not an exception subclass: R8 renames classes
    and `LogText.sanitizeReason` prints the simple name. A transport closed but not yet torn down, and a BLE refusal before
    the write, still read "may still have received it" (known limit, #248).
  - A probe reply must match the module's response ID + 0x62 + the probed DID; every other frame goes back to the poll path,
    and a `7F 22` is charged to the probe only when its ledger can attribute it (#250). A multi-frame positive shows only
    its first-frame bytes (no flow control sent). Over BLE, GATT 13 is "possibly delivered", so a scan does not stop at the
    first DID.
- **Export schema version (#253)** — `EXPORT_SCHEMA_VERSION` (`DiagnosticReportBuilder.kt`) is written at the root of every
  `manifest.json` (diagnostic, drive, batch) and of the diagnostic detail JSON, plus one summary header line. Absent = rc.7 or
  earlier. Bump it only for a change a reader could misread (a field or file removed or renamed; a type, unit, scale or meaning
  changed); additive changes keep the number, and readers detect an added section by its presence. `manifest.json`'s own
  `version` is a different number: always 1 on every export; don't bump or reuse it. Sapphire (`web/src/lib/exportSchema.ts`)
  never blocks on it and notes a higher number on the dashboard; a bump needs Sapphire's `KNOWN_EXPORT_SCHEMA_VERSION`
  raised when it learns the new contract.
- **Adapter lines and diagnostic logs are bounded, and the export says when a limit was reached (#253)** — every received
  line, WebSocket upgrade, session event list, inventory, trace, SLCAN log and probe list has a cap, and reaching one is
  counted in LOG LIMITS / `logLimits` (`BufferBoundsTest`). A new log or collection in `DiagnosticLogger` gets a cap and a
  count the same way.
- **Public export fixtures carry synthetic times only (#253)** — the Sapphire fixtures under
  `web/src/lib/__fixtures__/android-export-rc8/` use the stamp `…_20260101_000000` and `exportedAt` 1767225600000
  (2026-01-01T00:00:00Z, offset zero), written by the sample writer in `ExportSchemaVersionTest`, which fixes every
  `yyyyMMdd_HHmmss` stamp and `exportedAt` at write time (the exporter has no injectable clock); rc.6's fixtures use
  `…_20261026_120000`.
- **VIN masking is export-only and has three layers (#260)** — on a real car the VIN is on 0x40A (C1 pages) AND on 0x405
  (pages 10 / 11 / 12 by byte 0; one car), and a DID 0xF190 reply carries it too.
  - **Layers.** One choke point in `DiagnosticLogger` masks those pages and 0xF190; a content layer masks any frame with 4+
    consecutive bytes of the assembled VIN (`VIN_RUN_MIN = 4`), request lines included; stored DID-prober replies get the
    same run mask at export read time, but only once the VIN is known, so an export written before the VIN is decoded keeps
    a probe reply's bytes (cold start only; pinned by a limit test). Raw log bytes → `00` with ID / DLC / timing kept;
    inventory, trace and probe CSV → `**`.
  - **Limits.** DIAG still shows the VIN and the DID prober's on-screen list still shows the reply bytes — never claim the
    app hides it. Older ZIPs on the device are untouched: exports made before this masking can hold the VIN (#260). A
    test that scans an export for the VIN proves nothing unless the fake adapter sends 0x405.
- **The raw log holds the app's own requests (#209)** — every CAN frame the app writes is one `# TX` comment line in the
  SLCAN log (`# TX (relative_seconds) can0 ID#DATA <result>`), timed before the write and written when the result is known.
  - Order by the time field, never by line order: a reply can stand before its request's line (a Bluetooth write still
    returning).
  - The result is the link-integrity word from `SlcanTransport.write`, never `writeLine` (on Bluetooth `writeLine` drops a
    possibly delivered outcome); a failed write is a line too, never `sent`.
  - A new frame write site logs through `noteWrite` / `noteWriteFailure` (tracked requests), `writeLogged`, or the DTC
    scan's `send`; no header line starts with `# TX `.
  - Readers match frame lines anchored at the line's start (`check_export.py`, `tools/replay/candump_to_slcan.py`): keep
    that anchor. No build has been loaded in SavvyCAN, and Kayak was not read, so no text in the app names either as
    compatible until that load is done once (`RequestLogTest`, `RequestLogExportTest`, `SlcanLogTimeFieldTest`)
- **UI timing in the export is counts only, per session (#421)** — `uiTiming` / UI TIMING counts main-thread lateness,
  frame durations by settled tab, link-lock waits and Bluetooth delivery; never a clock time, reset at `sessionStart`,
  additive (no schema bump; Sapphire ignores it). The listener sees the activity's window only: a dialog window's frames
  (Settings, the BLE picker) are not counted (`UiTimingTest`, `UiTimingExportTest`)
- **Heap use in the export and the crash record (#510)** — FPS timeline points and the crash record carry heap numbers
  (`HeapSample.read`, KiB). Numbers only: no clock time, nothing from the car. Every build, not debug only (owner
  decision, #510). Additive: no schema bump; Sapphire's import ignores them (`HeapExportTest`)
- **Mode 22 replies per module in the export (#514)** — `ReplyCount` (`DiagnosticLogger`'s, cleared by `sessionStart`)
  counts every single-frame Mode 22 read from its `# TX` line (`logTxLine`, time before the write; certainly-not-sent
  writes skipped; prober reads included) and every module reply from `logObdFrame`, the one place each reply passes once.
  A new reply path that skips `logObdFrame`, or a request write that skips `logTxLine`, falls out of the count. It is a
  count, never attribution: the ledgers still decide which DID an NRC is charged to. A positive answers its DID's oldest
  waiting request; a reply that answers none, and every rejection and `7F 22 78`, is held up to `EARLY_KEEP_MS` because
  over Bluetooth a reply is often logged before its request's line (#209 T1), and the same exchange must count alike in
  both orders; a held rejection or `78` belongs to the newest request that began before it. No reply = nothing within
  `NO_REPLY_MS` (1 s, the issue's measure); a `78` makes its request wait for the final reply (pending only, never no
  reply). Per module the totals are exact; the miss run per DID and the answer times are a best match around rejections.
  Known gap: a probe of a module outside `ObdConstants.OBD_RESPONSE_IDS` (ABS 0x768, GFM 0x7DA) whose rejection its ledger
  cannot charge to the probe never reaches `logObdFrame`, so that probe counts as no reply. Counts only, additive (MODE 22 REPLIES / `mode22Replies`, no schema bump); Sapphire ignores it.
  Not checked against a real Bluetooth session yet: that is #514's acceptance drive (`ReplyCountTest`,
  `Mode22RepliesExportTest`)
- **TPMS sensor IDs and 0x398's text are masked like the VIN (#562, #527)** — at the same choke point in `DiagnosticLogger`
  (raw log data → `00`, inventory and trace → `**`, ID / DLC / time / PCI / DID kept), each with its own LOG LIMITS count.
  - **Page rule:** only on the BCM's response ID (0x72E), for DIDs 0x280F–0x2812 (single frame) and 0x280B (first frame and
    the consecutive frame that completes the ID). Another car's TPMS module at another address is not covered by the page
    rule, only by content; a DID prober reply to one of those DIDs on the BCM reads `00` at record time.
  - **Content:** a known ID (learned from those replies and from the app's state; at most `MAX_KNOWN_SENSOR_IDS`, 0 and
    FFFFFFFF never) in any frame, TX lines and stored probe replies included; the same cold-start limit as the VIN's
    content layer: a frame on another ID is masked when it is recorded, so one logged before any ID is learned keeps its
    bytes in the raw log, the inventory and the trace, and a probe reply exported before any ID is known keeps its bytes.
  - **0x398:** its last `TEXT_398_BYTES` (6) data bytes are masked in every frame, text or not; whether the text identifies
    the car is the owner's open question on #527.
  - **Limits:** the app keeps and maps the real IDs; DIAG's DID prober list on screen still shows the reply bytes. Exports
    made before this masking hold the IDs (`ExportTpmsIdMaskTest`)
- **The crash record is written minimal first (#511)** — `CrashTelemetryBuffer.flushToFile` writes the exception,
  message, stack, thread, build and heap, ending `"snapshotsWritten": false`, before it formats any snapshot; the full
  record is then built whole, written as `<name>.part` and renamed over it. So a crash on a full heap leaves at least
  that record, and readers tell the two apart by `snapshotsWritten` (absent on the full record, whose form is
  unchanged). Any new field or step that allocates much goes after the minimal write, and a temporary file must not
  end in `.json` (the crash list and the export take every `crash_telemetry_*.json`; "clear crash history" also removes
  a leftover `crash_telemetry_*.json.part`). Each crash claims its own file by creating it (`CrashReporter.crashFile`:
  `crash_telemetry_<ts>.json`, then `_2`, `_3` … in the same second), so threads failing together on a full heap never
  share one. A crash file that is empty or not valid JSON can mean the handler ran out of memory or was killed during
  the minimal write, not that the format changed. The out-of-memory case is simulated at the snapshot seam, never on a
  real full heap (`CrashRecordFullHeapTest`, `CrashFileNameTest`, `CrashHistoryClearTest`). The handler's own first step
  drops `CrashReporter`'s heap reserve (#552), so nothing in the handler may allocate before that line; a full heap is
  filled for real only in a child JVM (`CrashHandlerHeapReserveTest`), after the handler's classes are loaded
- **UI timing per FPS-timeline sample (#630)** — each `fpsTimeline` point carries `uiMainWakes`, `uiMainLateOver50`,
  `uiFrames`, `uiFramesOver32`, `uiFramesOver100` (counts since the previous sample, `UiSampler`), `uiTab` (the settled
  tab, `DiagnosticLogger.settledPage` from MainActivity) and `uiTripMapCreated` (TripPage); the summary rolls them up per
  minute, at most 60 rows (`uiRollUpLines`). Counts and offsets only, reset with the #421 counters at `sessionStart`;
  additive (no schema bump; Sapphire's import reads only `relMs` / `fps`). A new key here is a change to what is exported:
  the owner's call (`UiSampleExportTest`)
