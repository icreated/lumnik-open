#!/usr/bin/env bash
# loadtest/search/test-cleanup.sh — the orchestrator's exit trap must stop background jobs without leaving artifacts in
# the results directory (an unmatched glob handed to `touch` creates a file literally named '*.maxlag.stop').
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
eval "$(sed -n '/^cleanup() {/,/^}/p' "$here/../search-sizing.sh")"
ok=0; fail=0
OUT="$(mktemp -d)"; K6NAME=""
cleanup
left="$(ls -A "$OUT")"
if [[ -z "$left" ]]; then ok=$((ok+1)); else echo "FAIL: cleanup left '$left' in the results directory"; fail=$((fail+1)); fi
# a running background job must be stopped by it
sleep 30 & bg=$!
cleanup
sleep 0.2
if kill -0 "$bg" 2>/dev/null; then echo "FAIL: cleanup left a background job running"; kill "$bg" 2>/dev/null; fail=$((fail+1)); else ok=$((ok+1)); fi
rm -rf "$OUT"
echo "cleanup tests: $ok ok, $fail failed"; [ "$fail" -eq 0 ]
