#!/usr/bin/env bash
# Unit tests for lib.sh — pure helpers, no DB, no network.
set -uo pipefail
cd "$(dirname "$0")"
# shellcheck source=lib.sh
source ./lib.sh

FAILS=0
expect_eq() { # desc expected actual
  if [ "$2" = "$3" ]; then echo "ok   - $1"; else echo "FAIL - $1: expected '$2' got '$3'"; FAILS=$((FAILS+1)); fi
}

# --- counters + summary ---
WOW_PASS=0; WOW_FAIL=0
pass "demo"; expect_eq "pass increments" 1 "$WOW_PASS"
fail "demo"; expect_eq "fail increments" 1 "$WOW_FAIL"

# --- within_tol ---
expect_eq "exact match"          PASS "$(within_tol 47 47 0 0)"
expect_eq "off by one, tol 0"    FAIL "$(within_tol 48 47 0 0)"
expect_eq "abs tol covers"       PASS "$(within_tol 48 47 1 0)"
expect_eq "pct tol covers"       PASS "$(within_tol 314000 312480 0 1)"
expect_eq "pct tol exceeded"     FAIL "$(within_tol 350000 312480 0 1)"
expect_eq "negative diff abs"    PASS "$(within_tol 46 47 1 0)"

# --- json_scalar ---
J='{"rows":[{"n":47,"total":312480}],"rowCount":1}'
expect_eq "scalar n"     47     "$(json_scalar "$J" '.rows[0].n')"
expect_eq "scalar total" 312480 "$(json_scalar "$J" '.rows[0].total')"
expect_eq "scalar miss"  ""     "$(json_scalar "$J" '.rows[0].nope')"

# --- first_nonempty_jq ---
expect_eq "first hit"    47 "$(first_nonempty_jq "$J" '.rows[0].n' '.rowCount')"
expect_eq "fallback hit" 1  "$(first_nonempty_jq "$J" '.rows[0].nope' '.rowCount')"
expect_eq "all miss"     "" "$(first_nonempty_jq "$J" '.a' '.b' || true)"

# --- acts_assert_no_tbd ---
TBD_JSON='{"acts":[{"id":"A","expect":{"n":{"value":"TBD"}}}]}'
OK_JSON='{"acts":[{"id":"A","expect":{"n":{"value":47}}}]}'
expect_eq "tbd present → 1"  1 "$(acts_assert_no_tbd "$TBD_JSON" >/dev/null 2>&1; echo $?)"
expect_eq "no tbd → 0"      0 "$(acts_assert_no_tbd "$OK_JSON"  >/dev/null 2>&1; echo $?)"

# --- verify_one_act: agent matches reference within tol ---
ACT='{"id":"A","expect":{"n":{"value":47,"tol_abs":0,"agent_jq":[".rows[0].n",".rowCount"]}}}'
REF='{"n":47}'
AG_OK='{"rows":[{"n":47}],"rowCount":1,"answer":"47 orders"}'
AG_BAD='{"rows":[{"n":40}],"rowCount":1,"answer":"40 orders"}'
AG_REJ='{"rows":[],"rowCount":0,"answer":"I could not translate that into a safe query."}'
expect_eq "act ok"       0 "$(verify_one_act "$ACT" "$REF" "$AG_OK"  >/dev/null 2>&1; echo $?)"
expect_eq "act wrong"    1 "$(verify_one_act "$ACT" "$REF" "$AG_BAD" >/dev/null 2>&1; echo $?)"
expect_eq "act rejected" 1 "$(verify_one_act "$ACT" "$REF" "$AG_REJ" >/dev/null 2>&1; echo $?)"

echo "----"
[ "$FAILS" -eq 0 ] && echo "ALL OK" || { echo "$FAILS test(s) failed"; exit 1; }
