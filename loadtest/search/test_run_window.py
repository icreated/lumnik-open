import json, subprocess, sys, unittest, pathlib

SCRIPT = pathlib.Path(__file__).parent / "run_window.py"

def window(reports):
    out = subprocess.run([sys.executable, str(SCRIPT)], input=json.dumps(reports), capture_output=True, text=True)
    return out

class RunWindow(unittest.TestCase):
    def test_window_spans_the_earliest_start_to_the_latest_end(self):
        reports = [
            {"startedAt": "2026-09-30T20:00:00.500000Z", "endedAt": "2026-09-30T20:00:02.000000Z", "recordsOut": 1000},
            {"startedAt": "2026-09-30T20:00:00.100000Z", "endedAt": "2026-09-30T20:00:01.000000Z", "recordsOut": 1000},
            {"startedAt": "2026-09-30T20:00:00.300000Z", "endedAt": "2026-09-30T20:00:02.600000Z", "recordsOut": 1000},
        ]
        r = window(reports)
        self.assertEqual(r.returncode, 0, r.stderr)
        self.assertEqual(r.stdout.strip(), "2.50")   # 20:00:02.6 - 20:00:00.1

    def test_any_fraction_length_the_hub_may_write_is_accepted(self):
        # java.time.Instant prints 0, 3, 6 or 9 fractional digits; Python < 3.11 parses only 3 or 6 (stock macOS python3 is 3.9)
        for start, end, want in [
            ("2026-09-30T17:16:33Z",              "2026-09-30T17:16:35.123456789Z", "2.12"),
            ("2026-09-30T17:16:33.5Z",            "2026-09-30T17:16:35.25Z",        "1.75"),
            ("2026-09-30T17:16:33.123Z",          "2026-09-30T17:16:34.123456Z",    "1.00"),
            ("2026-09-30T17:16:33.000000001Z",    "2026-09-30T17:16:34.000000001Z", "1.00"),
        ]:
            r = window([{"startedAt": start, "endedAt": end}])
            self.assertEqual(r.returncode, 0, f"{start} -> {end}: {r.stderr}")
            self.assertEqual(r.stdout.strip(), want, f"{start} -> {end}")

    def test_a_run_that_has_not_ended_is_refused(self):
        r = window([{"startedAt": "2026-09-30T20:00:00.100000Z", "endedAt": None}])
        self.assertNotEqual(r.returncode, 0)

    def test_no_reports_is_refused(self):
        self.assertNotEqual(window([]).returncode, 0)

if __name__ == "__main__":
    unittest.main()
