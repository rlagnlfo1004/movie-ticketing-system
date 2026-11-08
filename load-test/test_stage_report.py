"""Worked examples for the outage-window counter calculation."""
import importlib.util
from pathlib import Path
import unittest

MODULE_PATH = Path(__file__).with_name("stage-report.py")
spec = importlib.util.spec_from_file_location("stage_report", MODULE_PATH)
report = importlib.util.module_from_spec(spec)
spec.loader.exec_module(report)


class CounterWindowTest(unittest.TestCase):
    def test_active_hold_success_counts_only_the_requested_run_and_window(self):
        raw = {
            "k6_seat_holds_created_total": {"data": {"result": [
                {"metric": {"testid": "fault"}, "values": [[0, "0"], [10, "8"], [20, "20"]]},
                {"metric": {"testid": "other"}, "values": [[0, "0"], [20, "200"]]},
            ]}},
            "k6_http_reqs_total": {"data": {"result": [
                {"metric": {"testid": "fault", "operation": "hold", "status": "201"},
                 "values": [[0, "0"], [10, "8"], [20, "20"]]},
                {"metric": {"testid": "fault", "operation": "hold", "status": "409"},
                 "values": [[0, "0"], [10, "4"], [20, "20"]]},
                {"metric": {"testid": "fault", "operation": "queue", "status": "200"},
                 "values": [[0, "0"], [20, "100"]]},
            ]}},
        }
        self.assertEqual(20, report.counter_delta(raw, "k6_seat_holds_created_total", "fault", 0, 20))
        self.assertEqual(40, report.counter_delta(raw, "k6_http_reqs_total", "fault", 0, 20, hold_only=True))
        self.assertEqual(12, report.counter_delta(raw, "k6_seat_holds_created_total", "fault", 10, 20))

    def test_missing_samples_do_not_create_a_success_rate(self):
        self.assertIsNone(report.counter_delta({}, "k6_seat_holds_created_total", "fault", 0, 20))


if __name__ == "__main__":
    unittest.main()
