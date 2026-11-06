#!/usr/bin/env python3
"""Summarize an existing SLCAN file. No network, adapter, or vehicle access."""
import argparse
from collections import Counter
import json
from pathlib import Path
import re
import sys

from slcan import ParseError, iter_records, parse_frame

MAX_STREAMS = 1024
MAX_DID_BUCKETS = 4096


def address_text(key):
    extended, number = key
    return f"ext:{number:08X}" if extended else f"std:{number:03X}"


def address_arg(s):
    match = re.fullmatch(r"(std):([0-9A-Fa-f]{3})|(ext):([0-9A-Fa-f]{8})", s)
    if not match:
        raise argparse.ArgumentTypeError("invalid CAN address")
    extended = bool(match[3])
    num = int(match[4] if extended else match[2], 16)
    if num > (0x1FFFFFFF if extended else 0x7FF):
        raise argparse.ArgumentTypeError("CAN address out of range")
    return extended, num


class Profile:
    def __init__(self):
        self.data = self.remote = self.equal = self.changed = 0
        self.last_data = None
        self.data_dlcs = Counter()
        self.remote_dlcs = Counter()
        self.masks = [0] * 8
        self.coverage = [0] * 8
        self.epoch = self.tick = None
        self.n = self.total_ms = 0
        self.min_ms = self.max_ms = None
        self.positive = self.nrc = self.unbucketed = 0
        self.dids = Counter()

    def observe(self, frame, epoch):
        if frame.remote:
            self.remote += 1
            self.remote_dlcs[frame.dlc] += 1
            return
        self.data += 1
        self.data_dlcs[frame.dlc] += 1
        data = frame.data
        if self.last_data is not None:
            if self.last_data == data:
                self.equal += 1
            else:
                self.changed += 1
        self.last_data = data
        for i, value in enumerate(data):
            self.coverage[i] += 1
            self.masks[i] |= 1 << value
        t = frame.timestamp_ms
        if t is not None:
            if self.epoch == epoch:
                dt = t - self.tick
                self.n += 1
                self.total_ms += dt
                self.min_ms = dt if self.min_ms is None else min(self.min_ms, dt)
                self.max_ms = dt if self.max_ms is None else max(self.max_ms, dt)
            self.epoch, self.tick = epoch, t

    def report(self):
        return {
            "data_frames": self.data, "remote_frames": self.remote,
            "data_dlc_counts": dict(sorted(self.data_dlcs.items())),
            "requested_remote_dlc_counts": dict(sorted(self.remote_dlcs.items())),
            "equal_to_previous_data": self.equal, "changed_from_previous_data": self.changed,
            "byte_positions": [
                {"offset": i, "observations": n, "distinct_value_count": bin(self.masks[i]).count("1")}
                for i, n in enumerate(self.coverage) if n
            ],
            "data_interarrival_ms": {
                "intervals": self.n, "min": self.min_ms, "max": self.max_ms,
                "mean": round(self.total_ms / self.n, 3) if self.n else None,
            },
        }


def single_frame_shape(data):
    # Normal addressing, classical CAN, single-frame PCI at byte zero only.
    if not data or not 1 <= data[0] <= 7 or data[0] > len(data) - 1:
        return None, None
    length = data[0]
    if data[1] == 0x62 and length >= 4:
        return "positive", int.from_bytes(data[2:4], "big")
    if data[1] == 0x7F and length == 3:
        return "unowned_negative", None
    return None, None


def summarize(stream, *, timestamps=False, uds_rx=(), private=False):
    profiles = {}
    rx = set(uds_rx)
    failures = Counter()
    seen = parsed = over_cap = blank = nonframe = 0
    epoch, last_tick = 0, None
    did_buckets = 0
    for record in iter_records(stream):
        seen += 1
        try:
            frame = parse_frame(record, timestamps=timestamps)
        except ParseError as err:
            if err.reason == "empty":
                blank += 1
            elif err.reason == "not_frame":
                nonframe += 1
            else:
                failures[err.reason] += 1
            continue
        parsed += 1
        if frame.timestamp_ms is not None:
            if last_tick is not None and frame.timestamp_ms < last_tick:
                epoch += 1  # Exclude every cross-boundary interval on every stream.
            last_tick = frame.timestamp_ms
        key = frame.extended, frame.arbitration_id
        if key not in profiles:
            if len(profiles) >= MAX_STREAMS:
                over_cap += 1
                continue
            profiles[key] = Profile()
        p = profiles[key]
        p.observe(frame, epoch)
        if key in rx and not frame.remote:
            kind, did = single_frame_shape(frame.data)
            if kind == "positive":
                p.positive += 1
                if private:
                    if did not in p.dids and did_buckets >= MAX_DID_BUCKETS:
                        p.unbucketed += 1
                    else:
                        if did not in p.dids:
                            did_buckets += 1
                        p.dids[did] += 1
            elif kind == "unowned_negative":
                p.nrc += 1

    streams = []
    for i, (key, p) in enumerate(profiles.items(), start=1):
        row = {"stream": f"stream_{i:03d}", **p.report()}
        if private:
            row["arbitration_address"] = address_text(key)
        if key in rx:
            uds = {"positive_read_did_single_frames": p.positive,
                   "unowned_negative_single_frames": p.nrc}
            if private:
                uds["first_did_counts"] = {f"0x{did:04X}": n for did, n in sorted(p.dids.items())}
                uds["positive_events_without_did_bucket"] = p.unbucketed
            row["uds_shapes"] = uds
        streams.append(row)
    return {
        "schema_version": 1, "profile": "private_protocol_ids" if private else "aggregate",
        "timestamp_mode": "four_hex_ms" if timestamps else "none",
        "uds_configured": bool(rx), "counts": {
            "records_seen": seen, "valid_frames": parsed, "frames_profiled": parsed - over_cap,
            "valid_frames_skipped_stream_limit": over_cap, "blank_records": blank,
            "nonframe_records": nonframe, "malformed_records": sum(failures.values()),
            "malformed_by_reason": dict(sorted(failures.items())),
            "adapter_or_bus_drop_count": None,
            "clock_regressions": epoch,
        },
        "streams": streams,
    }


class Parser(argparse.ArgumentParser):
    def error(self, message):
        self.exit(2, "Invalid arguments. See --help.\n")


def main(argv=None):
    parser = Parser(description=__doc__)
    parser.add_argument("capture", type=Path, help="regular file of classic SLCAN frame records")
    parser.add_argument("--timestamps", action="store_true", help="require four trailing hex timestamp digits on each frame")
    parser.add_argument("--uds-rx", action="append", type=address_arg, default=[], metavar="ADDRESS",
                        help="scan single-frame shapes on this known receive address (std:123 or ext:00000123); repeatable")
    parser.add_argument("--private-protocol-ids", action="store_true",
                        help="include observed CAN addresses and first DID per qualifying positive response")
    args = parser.parse_args(argv)
    try:
        if not args.capture.is_file():
            raise OSError()
        with args.capture.open("rb") as f:
            report = summarize(f, timestamps=args.timestamps, uds_rx=args.uds_rx, private=args.private_protocol_ids)
    except OSError:
        print("Cannot read capture as a regular file.", file=sys.stderr)
        return 2
    print(json.dumps(report, indent=2))
    return 0 if report["counts"]["valid_frames"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
