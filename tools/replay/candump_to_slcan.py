#!/usr/bin/env python3
"""Convert a candump-style log (the openRS_ app's session_slcan.log) into classic SLCAN records for replay.py.

Input lines: `(seconds) iface ID#DATA` (data frame) or `(seconds) iface ID#R[dlc]` (remote request); a 3-digit ID is
standard, an 8-digit ID extended. Output: one `t`/`T`/`r`/`R` record per frame, optionally with four hex millisecond
ticks (`--timestamps`) = round(seconds * 1000) mod 0x10000, which replay.py reads as a clock regression at each wrap.

Never copied to the output: comment lines (`# …` — the app's header holds the adapter host / port and the session's
epoch time), `# TX …` request lines (what the app sent is not observed bus traffic; counted separately), the interface
name and the absolute time base. Bad lines are counted by a fixed reason code and never echoed. Counts go to stderr as
JSON; frames go to stdout. No network, adapter or vehicle access.
"""
import argparse
from collections import Counter
import json
import re
import sys

MAX_LINE = 200
_LINE = re.compile(r"\(\s*(\d+(?:\.\d+)?)\)\s+\S+\s+([0-9A-Fa-f]+)#(.*)")
_HEX = re.compile(r"[0-9A-Fa-f]*")


class LineError(ValueError):
    """A fixed reason code; the line's content is never in the message."""

    def __init__(self, reason):
        super().__init__(reason)
        self.reason = reason


def convert_line(line, *, timestamps=False):
    """One candump line (no separator) → one SLCAN record, or LineError."""
    if len(line) > MAX_LINE:
        raise LineError("too_long")
    m = _LINE.fullmatch(line.strip())
    if not m:
        raise LineError("not_candump")
    seconds, ident, body = m.groups()
    if len(ident) == 3:
        extended, width = False, 3
    elif len(ident) == 8:
        extended, width = True, 8
    else:
        raise LineError("id_width")
    can_id = int(ident, 16)
    if can_id > (0x1FFFFFFF if extended else 0x7FF):
        raise LineError("id_range")
    if body.startswith("#"):
        raise LineError("can_fd")
    if body[:1] in ("R", "r"):
        dlc_text = body[1:]
        if dlc_text and not (len(dlc_text) == 1 and dlc_text in "012345678"):
            raise LineError("remote_dlc")
        record = ("R" if extended else "r") + f"{can_id:0{width}X}" + (dlc_text or "0")
    else:
        if not _HEX.fullmatch(body) or len(body) % 2:
            raise LineError("data_hex")
        if len(body) > 16:
            raise LineError("data_length")
        record = ("T" if extended else "t") + f"{can_id:0{width}X}" + str(len(body) // 2) + body.upper()
    if timestamps:
        record += f"{round(float(seconds) * 1000) % 0x10000:04X}"
    return record


def convert(lines, out, *, timestamps=False):
    counts = Counter()
    bad = Counter()
    for raw in lines:
        line = raw.rstrip("\r\n")
        counts["lines"] += 1
        if not line.strip():
            counts["blank"] += 1
            continue
        if line.startswith("# TX "):
            counts["tx_lines_excluded"] += 1
            continue
        if line.startswith("#"):
            counts["comment_lines_dropped"] += 1
            continue
        try:
            out.write(convert_line(line, timestamps=timestamps) + "\r")
            counts["frames"] += 1
        except LineError as err:
            bad[err.reason] += 1
    return {"schema_version": 1, "timestamp_mode": "four_hex_ms_mod_65536" if timestamps else "none",
            "counts": dict(sorted(counts.items())), "rejected_by_reason": dict(sorted(bad.items()))}


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("log", help="candump-style text log")
    parser.add_argument("--timestamps", action="store_true", help="append four hex ms ticks (mod 65536)")
    args = parser.parse_args(argv)
    try:
        with open(args.log, encoding="utf-8", errors="replace") as f:
            summary = convert(f, sys.stdout, timestamps=args.timestamps)
    except OSError:
        print("Cannot read the log.", file=sys.stderr)
        return 2
    print(json.dumps(summary), file=sys.stderr)
    return 0 if summary["counts"].get("frames") else 1


if __name__ == "__main__":
    raise SystemExit(main())
