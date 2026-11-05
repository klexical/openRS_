"""Read classic CAN SLCAN frame records. Does not open files or devices."""
from dataclasses import dataclass
import re
from typing import BinaryIO, Iterator, Optional


# Extended data frame: command (1), ID (8), DLC (1), data (16), timestamp (4).
MAX_RECORD = 30
_TOO_LONG = b"!" * 32
_HEX = re.compile(rb"[0-9A-Fa-f]+")


class ParseError(ValueError):
    """A fixed public reason code; raw record content is never in the message."""

    def __init__(self, reason: str):
        super().__init__(reason)
        self.reason = reason


@dataclass(frozen=True, repr=False)
class Frame:
    arbitration_id: int
    extended: bool
    remote: bool
    dlc: int
    data: bytes
    timestamp_ms: Optional[int]


def iter_records(stream: BinaryIO) -> Iterator[bytes]:
    """Split CR, CRLF or LF, retaining empty records and an unterminated tail.

    A record longer than MAX_RECORD yields one oversized sentinel. This keeps
    memory bounded even when a damaged input does not contain a separator.
    """
    buf = bytearray()
    oversized = False
    after_cr = False
    while True:
        chunk = stream.read(65536)
        if not chunk:
            break
        for byte in chunk:
            if after_cr and byte == 10:
                after_cr = False
                continue
            if byte in (10, 13):
                yield _TOO_LONG if oversized else bytes(buf)
                buf.clear()
                oversized = False
                after_cr = byte == 13
            else:
                after_cr = False
                if len(buf) < MAX_RECORD:
                    buf.append(byte)
                else:
                    oversized = True
    if buf or oversized:
        yield _TOO_LONG if oversized else bytes(buf)


def parse_frame(record: bytes, *, timestamps: bool = False) -> Frame:
    """Parse one record without a separator. Explicit timestamps are four hex digits.

    No timestamp wrap period, clock origin or capture date is inferred.
    """
    if len(record) > MAX_RECORD:
        raise ParseError("too_long")
    if not record:
        raise ParseError("empty")
    if not record.isascii():
        raise ParseError("non_ascii")
    kind = record[:1]
    if kind not in (b"t", b"T", b"r", b"R"):
        raise ParseError("not_frame")
    extended = kind in (b"T", b"R")
    remote = kind in (b"r", b"R")
    dlc_pos = 9 if extended else 4
    if len(record) <= dlc_pos:
        raise ParseError("short_header")
    id_field = record[1:dlc_pos]
    if not _HEX.fullmatch(id_field):
        raise ParseError("invalid_id")
    arbitration_id = int(id_field, 16)
    if arbitration_id > (0x1FFFFFFF if extended else 0x7FF):
        raise ParseError("invalid_id")
    dlc_char = record[dlc_pos]
    if not 48 <= dlc_char <= 56:
        raise ParseError("invalid_dlc")
    dlc = dlc_char - 48
    end = dlc_pos + 1 + (0 if remote else 2 * dlc)
    if len(record) != end + (4 if timestamps else 0):
        raise ParseError("length")
    payload = record[dlc_pos + 1:end]
    if payload and not _HEX.fullmatch(payload):
        raise ParseError("invalid_data")
    ts = record[end:]
    if ts and not _HEX.fullmatch(ts):
        raise ParseError("invalid_timestamp")
    return Frame(arbitration_id, extended, remote, dlc,
                 bytes.fromhex(payload.decode("ascii")), int(ts, 16) if ts else None)
