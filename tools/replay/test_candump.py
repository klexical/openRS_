import io
import unittest
from pathlib import Path

from candump_to_slcan import LineError, convert, convert_line
from replay import summarize

FIXTURE = Path(__file__).with_name("fixtures") / "synthetic.candump.log"


def run(text, **kw):
    out = io.StringIO()
    summary = convert(io.StringIO(text), out, **kw)
    return out.getvalue(), summary


class CandumpToSlcanTest(unittest.TestCase):
    def test_app_line_format_converts(self):
        # The app writes "(%10.3f) can0 %03X#%s" (DiagnosticLogger.buildSlcanLine)
        self.assertEqual(convert_line("(     1.250) can0 0F8#0102030405060708"), "t0F880102030405060708")
        self.assertEqual(convert_line("(     1.250) can0 0F8#0102", timestamps=True), "t0F82010204E2")  # 1,250 ms = 0x04E2

    def test_extended_remote_and_zero_length(self):
        self.assertEqual(convert_line("(0.1) can0 00000321#a1"), "T000003211A1")
        self.assertEqual(convert_line("(0.1) can0 321#R2"), "r3212")
        self.assertEqual(convert_line("(0.1) can0 321#R"), "r3210")
        self.assertEqual(convert_line("(0.1) can0 321#"), "t3210")

    def test_rejects_with_fixed_reasons(self):
        for line, reason in [("(0.1) can0 321#0A0", "data_hex"), ("(0.1) can0 321#0G", "data_hex"),
                             ("(0.1) can0 321#000102030405060708", "data_length"), ("(0.1) can0 321##1AB", "can_fd"),
                             ("(0.1) can0 3210#00", "id_width"), ("(0.1) can0 800#00", "id_range"),
                             ("(0.1) can0 321#R9", "remote_dlc"), ("garbage", "not_candump"),
                             ("(0.1) can0 321#" + "0" * 300, "too_long")]:
            with self.subTest(line=line[:30]):
                with self.assertRaises(LineError) as ctx:
                    convert_line(line)
                self.assertEqual(ctx.exception.reason, reason)

    def test_header_and_tx_never_reach_the_output(self):
        text = FIXTURE.read_text(encoding="utf-8")
        out, summary = run(text, timestamps=True)
        self.assertNotIn("198.51.100.7", out)
        self.assertNotIn("1000000000000", out)
        self.assertNotIn("7E0", out)                       # the TX request is not observed traffic
        self.assertEqual(summary["counts"]["tx_lines_excluded"], 1)
        self.assertEqual(summary["counts"]["comment_lines_dropped"], 5)
        self.assertEqual(summary["counts"]["frames"], 7)
        self.assertEqual(summary["rejected_by_reason"], {"data_hex": 1})
        self.assertNotIn("1GG2", repr(summary))           # rejected content is never echoed

    def test_round_trip_through_replay(self):
        out, _ = run(FIXTURE.read_text(encoding="utf-8"), timestamps=True)
        report = summarize(io.BytesIO(out.encode("ascii")), timestamps=True, uds_rx=[(False, 0x222)])
        c = report["counts"]
        self.assertEqual(c["valid_frames"], 7)
        self.assertEqual(c["malformed_records"], 0)
        # 65.540 s wraps to tick 0x0004 (< the previous 0x000E): one clock regression, no interval across it
        self.assertEqual(c["clock_regressions"], 1)
        s1 = report["streams"][0]
        self.assertEqual((s1["data_frames"], s1["remote_frames"]), (4, 1))
        uds = next(s for s in report["streams"] if "uds_shapes" in s)["uds_shapes"]
        self.assertEqual(uds["positive_read_did_single_frames"], 1)

    def test_without_timestamps_replay_reads_records_without_ticks(self):
        out, _ = run("(0.000) can0 321#0A0B\n(0.004) can0 321#0A0B\n")
        report = summarize(io.BytesIO(out.encode("ascii")))
        self.assertEqual(report["counts"]["valid_frames"], 2)
        self.assertEqual(report["streams"][0]["data_interarrival_ms"]["intervals"], 0)


if __name__ == "__main__":
    unittest.main()
