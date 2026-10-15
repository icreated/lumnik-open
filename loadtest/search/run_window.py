"""Ingestion duration from the run reports: earliest startedAt to latest endedAt (seconds, 2 decimals).

Timed from the hub's own report, not around the HTTP calls that start the runs: the control plane
(apply, trigger, poll) is not ingestion, and under a search load it competes with the load.
Reads a JSON list of run reports on stdin.
"""
import json, re, sys
from datetime import datetime

def ts(s):
    # Instant.toString writes 0, 3, 6 or 9 fractional digits; fromisoformat before Python 3.11 accepts only 3 or 6 (and no
    # "Z"), so normalise to exactly 6 digits and an explicit offset. Sub-microsecond precision is irrelevant to a window of seconds.
    m = re.fullmatch(r"(\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2})(?:\.(\d+))?(Z|[+-]\d{2}:\d{2})", s)
    if not m:
        raise ValueError(f"unrecognised timestamp: {s!r}")
    base, frac, zone = m.groups()
    return datetime.fromisoformat(f"{base}.{(frac or '').ljust(6, '0')[:6]}{'+00:00' if zone == 'Z' else zone}")

reports = json.load(sys.stdin)
if not reports:
    sys.exit("no run reports")
if any(not r.get("startedAt") or not r.get("endedAt") for r in reports):
    sys.exit("a run report has no start or end: the run is not terminal")
start = min(ts(r["startedAt"]) for r in reports)
end = max(ts(r["endedAt"]) for r in reports)
print(f"{(end - start).total_seconds():.2f}")
