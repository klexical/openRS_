import contextlib
import io
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import replay


def report(*records, **kwargs):
    return replay.summarize(io.BytesIO(b"\r\n".join(records)), **kwargs)


RX = [(False, 0x222)]
FIXTURE = Path(__file__).parent / "fixtures" / "synthetic.slcan"


class ReplayTests(unittest.TestCase):
    def test_sample_accounting_and_variation(self):
        with FIXTURE.open("rb") as f:
            result = replay.summarize(f, timestamps=True, uds_rx=RX)
        counts = result["counts"]
        self.assertEqual([counts[k] for k in ("records_seen", "valid_frames", "malformed_records", "nonframe_records")],
                         [10, 8, 1, 1])
        self.assertIsNone(counts["adapter_or_bus_drop_count"])
        stream, rx, empty_ext, empty_std = result["streams"]
        self.assertEqual(stream["data_interarrival_ms"], {"intervals": 2, "min": 4, "max": 4, "mean": 4})
        self.assertEqual((stream["equal_to_previous_data"], stream["changed_from_previous_data"]), (1, 1))
        self.assertEqual(stream["requested_remote_dlc_counts"], {2: 1})
        self.assertEqual([b["distinct_value_count"] for b in stream["byte_positions"]], [1, 2])
        self.assertEqual(rx["uds_shapes"], {"positive_read_did_single_frames": 1, "unowned_negative_single_frames": 1})
        self.assertEqual(empty_ext["byte_positions"], empty_std["byte_positions"])
        self.assertEqual(empty_ext["data_dlc_counts"], {0: 1})

    def test_aggregate_and_private_disclosure(self):
        records = [b"t22270662F190DEADBE", b"T0000022270662FFFFDEADBE", b"untrusted-input",
                   b"r2222", b"t2228037F2231DEADBEEF"]
        default = report(*records, uds_rx=RX)
        private = report(*records, uds_rx=RX, private=True)
        for marker in ("std:222", "ext:00000222", "arbitration_address", "first_did_counts", "F190",
                       "FFFF", "DEAD", "untrusted-input"):
            self.assertNotIn(marker, json.dumps(default))
        text = json.dumps(private)
        for marker in ("DEAD", "FFFF", "untrusted-input"):
            self.assertNotIn(marker, text)
        self.assertIn("ext:00000222", text)
        self.assertEqual(private["streams"][0]["uds_shapes"]["first_did_counts"], {"0xF190": 1})
        self.assertNotIn("uds_shapes", private["streams"][1])
        self.assertNotIn("uds_shapes", report(*records, private=True)["streams"][0])

    def test_only_single_frames_with_enough_data(self):
        packets = [
            b"t2220", b"r2228", b"t222101", b"t222403621234",
            b"t2225047F223100", b"t222406621234", b"t2226200462123400",
            b"t2226100962123400", b"t22250062123400", b"t22250504621234",
        ]
        uds = report(*packets, uds_rx=RX, private=True)["streams"][0]["uds_shapes"]
        self.assertEqual((uds["positive_read_did_single_frames"], uds["unowned_negative_single_frames"]), (0, 0))
        self.assertEqual(uds["first_did_counts"], {})

    def test_backwards_clock_breaks_intervals_across_all_streams(self):
        stream = report(b"t22200064", b"t33300065", b"t33300001", b"t22200002",
                        b"t22200002", b"t22200008", timestamps=True)
        self.assertEqual(stream["counts"]["clock_regressions"], 1)
        self.assertEqual(stream["streams"][0]["data_interarrival_ms"],
                         {"intervals": 2, "min": 0, "max": 6, "mean": 3})
        self.assertEqual(stream["streams"][1]["data_interarrival_ms"]["intervals"], 0)

    def test_missing_timing_and_exact_dlc(self):
        r = report(b"t1230", b"t1230", b"t1231", b"t123200AA", b"t1231FF0001", b"", b"unknown", b"t1231BB")
        counts = r["counts"]
        self.assertEqual((counts["blank_records"], counts["nonframe_records"], counts["malformed_records"]), (1, 1, 2))
        p = r["streams"][0]
        self.assertEqual(p["data_interarrival_ms"], {"intervals": 0, "min": None, "max": None, "mean": None})
        self.assertEqual([b["observations"] for b in p["byte_positions"]], [2, 1])
        self.assertEqual((p["equal_to_previous_data"], p["changed_from_previous_data"]), (1, 2))

    def test_limits_count_known_losses_retain_existing_buckets(self):
        with patch.object(replay, "MAX_STREAMS", 1), patch.object(replay, "MAX_DID_BUCKETS", 1):
            result = report(b"t22250462123401", b"t2230", b"t22250462123502", b"t22250462123403",
                            uds_rx=RX, private=True)
        self.assertEqual(result["counts"]["valid_frames_skipped_stream_limit"], 1)
        self.assertEqual(result["counts"]["frames_profiled"], 3)
        p = result["streams"][0]["uds_shapes"]
        self.assertEqual(p["first_did_counts"], {"0x1234": 2})
        self.assertEqual(p["positive_events_without_did_bucket"], 1)

    def test_cli_and_sanitized_errors(self):
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            self.assertEqual(replay.main(["--timestamps", "--uds-rx", "std:222", str(FIXTURE)]), 0)
            self.assertEqual(replay.main(["missing-boundary-personal.txt"]), 2)
            with self.assertRaises(SystemExit) as exc:
                replay.main(["--uds-rx", "boundary-personal", str(FIXTURE)])
        self.assertEqual(exc.exception.code, 2)
        self.assertNotIn("personal", err.getvalue())
        self.assertEqual(json.loads(out.getvalue())["profile"], "aggregate")


if __name__ == "__main__":
    unittest.main()
