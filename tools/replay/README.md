# Offline CAN replay for openRS_

This Python 3.8+ tool reads an existing **classic SLCAN text file** and prints an aggregate JSON report. Use it to identify which traffic deserves a controlled follow-up: for example, a changing byte offset on a periodic stream, or a DID actually returned by a configured diagnostic address. It does not connect to a vehicle, issue requests, or assign signal names, units or ECU identities. Only synthetic data is included.

## Try it

Run these commands inside the `replay/` directory. No installation or third-party packages are required.

```sh
python3 -B -m unittest discover -v
python3 replay.py fixtures/synthetic.slcan --timestamps --uds-rx std:222
python3 replay.py fixtures/synthetic.slcan --timestamps --uds-rx std:222 --private-protocol-ids
```

The example has 10 records: eight valid frames, one malformed payload and one nonframe record. In either report, `stream_001` has three data frames and one remote request. Its two observed data intervals are both 4 ms. One payload repeats and one changes; offset 0 stays constant and offset 1 takes two distinct values. `stream_002` has one positive read-DID shape and one unowned negative-response shape. In private mode the first DID in the positive response is `0x1234`. These invented values demonstrate the analysis, not discoveries about a vehicle.

For another existing file, substitute its path, add `--timestamps` only for a timestamped file, and **omit `--uds-rx` unless the response address and addressing convention are independently known**. Repeat the option for multiple known response addresses. Redirect stdout to save the report. Exit status: 0 = at least one valid frame, 1 = report produced but no valid frames, 2 = argument or input error. Check malformed and skipped counts even on status 0.

## Record format

| Record | ID field | DLC | Data field |
| --- | --- | --- | --- |
| `t` data / `r` remote | 3 hex digits, max `7FF` | one digit `0`–`8` | exactly 2 hex digits per data byte for `t`; none for `r` |
| `T` data / `R` remote | 8 hex digits, max `1FFFFFFF` | one digit `0`–`8` | exactly 2 hex digits per data byte for `T`; none for `R` |

Fields are adjacent ASCII without whitespace. With `--timestamps`, every valid frame must end in exactly four more hex digits interpreted as millisecond ticks. Without it, a timestamp suffix is extra data and fails the length check. There is no mode guessing or payload truncation. CR, LF and CRLF terminate records; a complete final frame can end at EOF. Blank lines (including possible adapter acknowledgments) and nonframe commands are counted separately. Non-ASCII data and oversized records count as malformed; oversized input is discarded through the next separator. A zero-DLC data frame is valid and contributes timing but no byte observations. A remote frame's DLC represents requested length, never data.

This reader does not accept CAN FD, candump, CSV, JSON, BLE notifications with wrappers, or arbitrary app exports. If the source has different framing, build and test a separate format adapter before using this reader. Renaming an export does not convert it.

## Reading and sharing a report

- A stream is a pair of ID format (standard/extended) and arbitration ID. The default report uses first-seen labels scoped to one input; `stream_001` in different files need not be the same address.
- `data_dlc_counts` and `requested_remote_dlc_counts` separate data and remote frames. Each observed byte offset reports sample count and distinct-value count; a missing byte does not count as zero.
- `equal_to_previous_data` and `changed_from_previous_data` compare exact payloads on consecutive observed **data** frames on the same stream, ignoring interleaved remote traffic. The first data frame counts as neither. A change in length counts as a change. These counts span clock regressions. A repeated payload can be a new transmission of the same value; neither counter proves sensor freshness or adapter replay.
- `data_interarrival_ms` uses successive observed data frames on the same stream. Without timestamp suffixes, or with too few comparable samples, the number of intervals is zero and min/mean/max are null. A backward tick anywhere in the file starts a new segment across all streams; an interval crossing it is excluded. Ties are valid zero-ms intervals. No wrap modulus, wall time, multiwrap gap, network latency, or true transmit period is inferred.
- `records_seen` = valid + malformed + blank + nonframe records. Parser rejects have fixed reason codes. Up to 1,024 streams are profiled: after the limit, newly encountered streams' frames count as valid but skipped. `adapter_or_bus_drop_count` is null because frames dropped before logging leave no reliable evidence here.
- UDS counts are opt-in and limited to **normal-addressed, classical ISO-TP single frames** at a specified response address. The PCI must be at byte zero; its declared length must fit the DLC; trailing padding is ignored. A declared payload containing `62`, two DID bytes and at least one value byte counts as a positive read-DID shape. Only the first DID field is observed: later bytes cannot be split without a known data layout. A three-byte `7F` shape counts as an unowned negative. It is never attached to a DID/request, even next to a positive. There is no request matching, ownership, transport reassembly, extended/mixed addressing, PID decoding or signal inference. A byte pattern alone cannot verify a diagnostic exchange.
- **Default report:** no actual arbitration addresses, DID numbers, raw bytes, decoded values, source filename or absolute timestamps. **Private engineering report:** `--private-protocol-ids` adds observed arbitration addresses and first-DID event counts on explicitly configured UDS response addresses; it still omits actual signal/identifier values and raw payloads. After 4,096 distinct address/DID buckets, events for additional combinations are counted as unbucketed. Keep this output local if protocol fingerprints are sensitive.

This is **not a sanitizer for arbitrary exports**. It generates a new, tightly scoped summary of supported frame records; it does not clean the original file. Even aggregate timing and traffic patterns can fingerprint a capture. Review the actual report before sharing it. Use a separate input per bus, capture session and test condition; neither stream labels nor timestamps establish cross-file or cross-bus identity.
