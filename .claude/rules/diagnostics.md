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

Moved verbatim from the tracked `CLAUDE.md` (section Known Gotchas) on 2026-11-06. Add a new gotcha of this area at the end; never reword an existing one here.

- **One DTC record per export (#238 review F4, `d6ef14e`)** — `DiagnosticExporter.export` reads
  `DiagnosticLogger.lastDtcScan` once and passes it to `buildSummaryAndDetail(ts, dtcScan = …)` and the `dtc_scan_*`
  files; never read the ledger again inside one export. Ledger records are immutable, so one read is a snapshot.
  `DiagnosticExportDtcSnapshotTest` (Context seam between the summary and the DTC files)
- **DTC scan truth (#215)** — a module is OK only for a valid positive `59 02 …` reply (`DtcReply.classify`); 7F 19 NRC →
  REJECTED, malformed → MALFORMED, 7F 19 78 waits (P2* 5 s, cap 12 s) → else PENDING, none → TIMEOUT. The export record
  is published only through `DiagnosticLogger.dtcScans` (`DtcScanLedger`, session-scoped); never write a DtcScanRecord
  directly. Records are ISO 14229 4-byte (DTC high, mid, failure type, status — python-udsoncan), a reply must be whole
  records; only replies to the operation's service are diverted from the frame loop (poll / `50 03` replies take the normal
  path). DIAG goes through `DtcScanView` + ledger together — screen, export and the header count publish only when the
  ledger accepts the ticket (the auto-scan uses `DtcScanView.autoScan`, same gate). Fault identity = module + code +
  failure type (`displayCode` "P0123-1C"; saved v13; older rows match by base code, said on screen); NEW / "not found" only
  over modules read OK in *both* scans (the previous scan's own statuses). No real-module capture
  yet. Freeze-frame sub-function numbering (app 04 / 05 vs ISO 03 / 04) is unverified, best-effort
- **Share intents set ClipData** (`DiagnosticExporter.shareClip`) beside `EXTRA_STREAM` — `createChooser` only passes the
  read grant to the share sheet through ClipData; without it the sheet's preview logs `Permission Denial … intentresolver`
- **Diagnostic-export adapter status (#233, fix branch)** — `/check_status` belongs to the session: `sessionStart` returns a
  token + takes the initial reason (Bluetooth = not requested); `adapterStatusRequested/Fetched/Failed(token, …)` drop stale
  tokens; exports build text + JSON from ONE `AdapterStatusSnapshot` (`buildSummaryAndDetail`). The `Firmware :` line is
  the OPENRS? handshake — never derived from `/check_status` (`fw_version` there doesn't identify an openRS_ build: the
  owner's openRS_ PRO v1.2-rc.1 unit reported 4.45 over Wi-Fi)
- **Text from outside the app → `LogText.sanitize[Reason]`** (probe write failures #220, DTC write failures #215): printable,
  bounded, MAC / `WiC_…` / long hex masked. Probe events use `SlcanTransport.kind`, never `transport.label` (BLE label has
  name + MAC); non-frame adapter lines are counted, never logged. Probe 1 is logged when written / failed, before the
  handshake (a failed attempt still leaves it); a handshake timeout logs "No probe verdict". Replies stay "after probe N"
  (identical probes — no request id)
- **DTC scan writes (#215, fix branch)** — every scan write goes through `send(module, DtcStage, frame)`: failure kept as
  `DtcSendFailure` (stage + reason; one per module — request > flow control > extended session, `DtcRawScan.keeps`) →
  `moduleNotes` in export / DTC files / DIAG; `DtcRawScan.statusWithoutReply` is the one rule for progress AND result
  (request / FC failure → ERROR **before** PENDING; no session-write note beside PENDING); FC marked sent only on success;
  diverted replies are logged once before the scanner gets them; requests / FC are `# TX` comment lines
  (`DiagnosticLogger.logTxLine`)
- **DTC retries carry their own note (#215, fix branch)** — `scanSingleModule` / `retryScanModule` return `DtcModuleRetry`
  (codes, status, note); the ledger record and DIAG's result apply it with ONE rule (`DtcScanLedger.withNote`: replaced on
  failure, cleared on OK / genuine timeout). DIAG's NOT SCANNED row reads the DISPLAYED result
  (`DtcScanView.notScannedText`) — never `DiagnosticLogger.lastDtcScan` (a newer auto-scan / session would label an older row)
- **DID prober (rc.8, #249)** — the scan runs in `diagnostics/DidProbeScan.kt` (`runDidProbeScan`); `DidProberSection`
  only starts it and shows the end.
  - A failed query never reaches the UI scope: any exception except cancellation (rethrown) ends the scan as
    `DidProbeEnd.Failed(did, reason, sent)` with one sanitized `PROBE` event (DID, n / m probed, reason) and no result for
    that DID. The failed DID is never retransmitted; only a new scan started by the user sends again.
  - `probing` clears only in `finally`, so a second scan can't run beside the first. STOP only requests the stop (checked
    before each send and after each reply).
  - `SlcanConnection` / `CanDataService.sendRawQuery` throw `IOException(QUERY_NOT_CONNECTED)` when there is no
    connection / transport (`_transport` is cleared in the connection loop's finally); they used to return null, which
    recorded a fake TIMEOUT. Only that message gives `sent = false` → DIAG "Nothing was sent for this DID."; every other
    failure reads "The adapter reported the write as failed. The module may still have received it." A shared message,
    not an exception subclass: R8 renames classes and `LogText.sanitizeReason` prints the simple name. The DID in flight
    when the link drops still records TIMEOUT; after a reconnect the scan just continues; a transport closed but not yet
    torn down, and a BLE refusal before the write, get the "may still" line (known limit, #248).
  - Partial scans are kept and labelled: `ProbeSession.outcome` (COMPLETE / STOPPED / FAILED) + `planned` → text
    "STOPPED — n of m probed", JSON `outcome` / `planned` (`totalProbed` unchanged), CSV first line
    `# outcome=… probed=… planned=…`; a complete scan exports exactly as before. A FAILED session logs no extra "scan …"
    event (the failure event already names the DID and reason).
  - Reply matching (#250, rc.8): a probe reply must match the module's response ID + 0x62 + the probed DID (single or first
    frame); every other frame goes back to the normal poll path (parser + ledger). The probe request sits in its module's
    request ledger (`PcmRequestLedger` for PCM; per-response-ID ledgers for other modules, ownership only), so a `7F 22` is
    charged to the probe only when the ledger can attribute it — otherwise to nobody, and the probe reads `NRC_AMBIGUOUS`.
    No time release (#216): after a scan in which a probed DID never answered, PCM NRC suppression stays off until the next
    connection. A multi-frame positive shows only its first-frame bytes (no flow control sent).
  - Over BLE, GATT 13 is "possibly delivered" (see the BLE writes gotcha), so a scan no longer stops at the first DID.
- **Export schema version (#253)** — `EXPORT_SCHEMA_VERSION` (`DiagnosticReportBuilder.kt`) is written at the root of every
  `manifest.json` (diagnostic, drive, batch) and of the diagnostic detail JSON, plus one summary header line. Absent = rc.7 or
  earlier. Bump it only for a change a reader could misread (a field or file removed or renamed; a type, unit, scale or meaning
  changed); additive changes keep the number, and readers detect an added section by its presence. `manifest.json`'s own
  `version` is a different number: always 1 on every export, so it can't tell legacy from current; don't bump or reuse it.
  Sapphire (`web/src/lib/exportSchema.ts`) never blocks on it: absent or malformed (only a positive safe integer counts) records
  nothing, and a higher number is recorded in `session.meta` and noted on the dashboard. A bump needs Sapphire's
  `KNOWN_EXPORT_SCHEMA_VERSION` raised when it learns the new contract; until then the note is the correct behaviour.
