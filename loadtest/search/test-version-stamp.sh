#!/usr/bin/env bash
# loadtest/search/test-version-stamp.sh — the commit stamp of search-sizing.sh must never abort a run: outside a git
# checkout (a source tarball, the extracted open edition) the stamp says "unknown", under `set -e`.
set -uo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
eval "$(sed -n '/^commit_stamp() {/,/^}/p' "$here/../search-sizing.sh")"
ok=0; fail=0
check() { if [[ "$2" == "$3" ]]; then ok=$((ok+1)); else echo "FAIL ($1): got '$2' want '$3'"; fail=$((fail+1)); fi; }
nogit="$(mktemp -d)"
out="$(cd "$nogit" && set -e && commit_stamp; echo "reached")"
check "outside a git checkout, set -e" "$out" "unknown (not a git checkout)
reached"
out="$(cd "$here/../.." && commit_stamp)"
case "$out" in [0-9a-f][0-9a-f][0-9a-f][0-9a-f]*) ok=$((ok+1)) ;; *) echo "FAIL (inside the repo): '$out'"; fail=$((fail+1)) ;; esac
rm -rf "$nogit"
echo "stamp tests: $ok ok, $fail failed"; [ "$fail" -eq 0 ]
