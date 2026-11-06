"""Synthetic SLCAN grammar and framing checks; no captured data is used."""
import io
import unittest

from slcan import MAX_RECORD, ParseError, iter_records, parse_frame


class LexerTests(unittest.TestCase):
    def test_standard_and_extended(self):
        short = parse_frame(b"t7Ff1aB")
        self.assertEqual((short.arbitration_id, short.dlc, short.data), (0x7ff, 1, b"\xab"))
        self.assertFalse(short.extended or short.remote)
        long = parse_frame(b"T1FFFffff800112233445566770123", timestamps=True)
        self.assertEqual(long.arbitration_id, 0x1fffffff)
        self.assertTrue(long.extended)
        self.assertEqual(long.timestamp_ms, 0x0123)
        self.assertEqual(len(long.data), 8)

    def test_remote_and_empty(self):
        for line, dlc, remote in ((b"r3218", 8, True), (b"R001234560", 0, True),
                                  (b"t0000", 0, False), (b"T000000000", 0, False)):
            with self.subTest(line=line):
                frame = parse_frame(line)
                self.assertEqual((frame.dlc, frame.remote, frame.data), (dlc, remote, b""))
                self.assertIsNone(frame.timestamp_ms)
        self.assertEqual(parse_frame(b"r3218abcd", timestamps=True).timestamp_ms, 0xabcd)

    def test_timestamp_is_explicit_and_not_payload(self):
        for line, enabled in ((b"t1231AB0001", False), (b"t1231AB", True),
                              (b"r12381234", False), (b"t1230000", True)):
            with self.subTest(line=line, enabled=enabled), self.assertRaises(ParseError):
                parse_frame(line, timestamps=enabled)
        self.assertEqual(parse_frame(b"t1230ABCD", timestamps=True).data, b"")

    def test_malformed_and_unknown(self):
        cases = [(b"", "empty"), (b"F01", "not_frame"), (b"t123", "short_header"),
                 (b"t8000", "invalid_id"), (b"T200000000", "invalid_id"),
                 (b"t+010", "invalid_id"), (b"t0x10", "invalid_id"),
                 (b"t1239", "invalid_dlc"), (b"t123A", "invalid_dlc"),
                 (b"t1231", "length"), (b"r1231AB", "length"),
                 (b"t1230\r", "length"), (b"t1231 0", "invalid_data"),
                 (b"t1231\xff\x00", "non_ascii"), (b"t" * 31, "too_long")]
        for line, reason in cases:
            with self.subTest(line=line), self.assertRaises(ParseError) as caught:
                parse_frame(line)
            self.assertEqual(caught.exception.reason, reason)
            self.assertEqual(str(caught.exception), reason)
        with self.assertRaisesRegex(ParseError, "^invalid_timestamp$"):
            parse_frame(b"t111000+0", timestamps=True)

    def test_no_raw_record_in_repr_or_exception(self):
        self.assertNotIn("dead", repr(parse_frame(b"t1AA2DEAD")).lower())
        with self.assertRaises(ParseError) as caught:
            parse_frame(b"private-example")
        self.assertNotIn("private-example", str(caught.exception))

    def test_separators_even_at_chunk_boundaries(self):
        class ByteStream(io.BytesIO):
            def read(self, size=-1):
                return super().read(1)
        self.assertEqual(list(iter_records(ByteStream(b"a\r\nb\rc\n\r\nd"))),
                         [b"a", b"b", b"c", b"", b"d"])
        self.assertEqual(list(iter_records(ByteStream(b"\r\r\n"))), [b"", b""])
        self.assertEqual(list(iter_records(ByteStream(b""))), [])

    def test_oversized_line_recovers_at_next_separator(self):
        records = list(iter_records(io.BytesIO(b"z" * 200000 + b"\rt0010\n")))
        self.assertEqual(len(records), 2)
        self.assertGreater(len(records[0]), MAX_RECORD)
        self.assertLess(len(records[0]), 100)
        with self.assertRaisesRegex(ParseError, "^too_long$"):
            parse_frame(records[0])
        self.assertEqual(parse_frame(records[1]).dlc, 0)


if __name__ == "__main__":
    unittest.main()
