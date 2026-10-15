#!/usr/bin/env bash
# loadtest/search/test-stack-gate.sh — the Compose version gate of stack.sh, against fake `docker compose version` answers.
# `!override` (port override) needs Compose >= 2.24; a refusal must come from the VERSION, never from its spelling.
set -uo pipefail
cd "$(dirname "$0")"
eval "$(sed -n '/^need_compose() {/,/^}/p' stack.sh)"
ok=0; fail=0
gate() {  # <fake version> <expected: accept|refuse> — need_compose runs in a subshell: it exits on refusal
  local v="$1" want="$2" got
  docker() { echo "$FAKE"; }
  if FAKE="$v" bash -c "$(declare -f docker need_compose); FAKE='$v'; need_compose" >/dev/null 2>&1; then got=accept; else got=refuse; fi
  if [[ "$got" == "$want" ]]; then ok=$((ok+1)); else echo "FAIL: '$v' should $want, got $got"; fail=$((fail+1)); fi
}
gate "2.24.0"             accept
gate "5.5.0"              accept
gate "2.24.0-desktop.1"   accept
gate "v2.24.0"            accept
gate "v5.5.0"             accept
gate "2.23.9"             refuse
gate "v2.23.9"            refuse
gate "v1.29.2"            refuse
echo "gate tests: $ok ok, $fail failed"; [ "$fail" -eq 0 ]
