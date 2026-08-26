# scripts/wow/lib.sh — shared helpers for the WOW Plan 3 scripts. Source, don't execute.

WOW_PASS=${WOW_PASS:-0}
WOW_FAIL=${WOW_FAIL:-0}

# die MSG — print to stderr and exit non-zero.
die() { echo "ERROR: $*" >&2; exit 1; }

# pass DESC — record + print a passing check.
pass() { WOW_PASS=$((WOW_PASS+1)); echo "  ✓ $*"; }

# fail DESC — record + print a failing check (does not exit; summary decides).
fail() { WOW_FAIL=$((WOW_FAIL+1)); echo "  ✗ $*" >&2; }

# summary — print totals; exit 1 if any check failed.
summary() {
  echo "----"
  echo "PASS=$WOW_PASS  FAIL=$WOW_FAIL"
  [ "$WOW_FAIL" -eq 0 ] || exit 1
}

# load_env — source scripts/wow/.env if present, then assert required vars are set.
# Usage: load_env VAR1 VAR2 ...
load_env() {
  local here; here="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  # shellcheck disable=SC1091
  [ -f "$here/.env" ] && source "$here/.env"
  local v missing=0
  for v in "$@"; do
    if [ -z "${!v:-}" ]; then echo "ERROR: required env var $v is not set" >&2; missing=1; fi
  done
  [ "$missing" -eq 0 ] || die "set the missing vars (see scripts/wow/.env.example)"
}

# within_tol ACTUAL EXPECTED TOL_ABS TOL_PCT — echo PASS if |actual-expected| within the
# larger of the absolute or percentage allowance, else FAIL. Float-safe via awk.
within_tol() {
  awk -v a="$1" -v e="$2" -v ta="${3:-0}" -v tp="${4:-0}" 'BEGIN{
    d = a - e; if (d < 0) d = -d;
    ea = (e < 0) ? -e : e;
    allow = ta; p = (tp/100.0) * ea; if (p > allow) allow = p;
    print (d <= allow) ? "PASS" : "FAIL";
  }'
}

# json_scalar JSON JQ_EXPR — extract one scalar; empty string if null/absent/parse-error.
json_scalar() { printf '%s' "$1" | jq -r "$2 // empty" 2>/dev/null; }

# first_nonempty_jq JSON EXPR... — return the first jq expr that yields a non-empty value.
# Returns 1 (and empty output) if none match.
first_nonempty_jq() {
  local json="$1"; shift
  local expr v
  for expr in "$@"; do
    v="$(printf '%s' "$json" | jq -r "$expr // empty" 2>/dev/null)"
    [ -n "$v" ] && { printf '%s' "$v"; return 0; }
  done
  return 1
}

# acts_assert_no_tbd ACTS_JSON — return 0 if no expect value is still the "TBD" sentinel,
# else print the offending act ids and return 1. (Guards verify-acts against uncalibrated runs.)
acts_assert_no_tbd() {
  local offenders
  offenders="$(printf '%s' "$1" | jq -r '
    .acts[] | select([.expect[].value] | any(. == "TBD")) | .id' 2>/dev/null)"
  if [ -n "$offenders" ]; then
    echo "uncalibrated acts (expect.value still TBD): $(echo "$offenders" | tr "\n" " ")" >&2
    return 1
  fi
  return 0
}

# verify_one_act ACT_JSON REF_JSON AGENT_JSON — compare the agent's answer to the reference
# for every key in act.expect. Returns 0 if all keys pass, 1 otherwise. Prints per-key verdicts.
# REF_JSON is the live reference object (e.g. {"n":47,"total":312480}); ACT_JSON.expect[k].value
# is the calibration-frozen number used as an integrity guard against reference drift.
verify_one_act() {
  local act="$1" ref="$2" agent="$3"
  local id; id="$(printf '%s' "$act" | jq -r '.id')"

  # Hard fail if the agent degraded (SqlGuard reject / no rows where data is expected).
  local ans
  ans="$(json_scalar "$agent" '.answer')"
  case "$ans" in
    *"could not translate"*|*"couldn't translate"*|*"n'ai pas pu"*)
      fail "[$id] agent degraded: $ans"; return 1;;
  esac

  local keys k rc_overall=0
  keys="$(printf '%s' "$act" | jq -r '.expect | keys[]')"
  for k in $keys; do
    local exp_val tol_abs tol_pct ref_val agent_val
    exp_val="$(printf '%s' "$act"  | jq -r ".expect[\"$k\"].value")"
    tol_abs="$(printf '%s' "$act"  | jq -r ".expect[\"$k\"].tol_abs // 0")"
    tol_pct="$(printf '%s' "$act"  | jq -r ".expect[\"$k\"].tol_pct // 0")"
    ref_val="$(json_scalar "$ref" ".$k")"

    # Integrity: the live reference must still match the frozen calibration value.
    if [ -z "$ref_val" ]; then fail "[$id.$k] reference query returned no '$k'"; rc_overall=1; continue; fi
    if [ "$(within_tol "$ref_val" "$exp_val" "$tol_abs" "$tol_pct")" != "PASS" ]; then
      fail "[$id.$k] reference drift: live=$ref_val frozen=$exp_val — recalibrate"; rc_overall=1; continue
    fi

    # The actual truth-check: agent's scalar vs the live reference.
    local jqs; jqs="$(printf '%s' "$act" | jq -r ".expect[\"$k\"].agent_jq[]")"
    # shellcheck disable=SC2086
    agent_val="$(first_nonempty_jq "$agent" $jqs)" || agent_val=""
    if [ -z "$agent_val" ]; then fail "[$id.$k] could not extract '$k' from agent rows"; rc_overall=1; continue; fi
    if [ "$(within_tol "$agent_val" "$ref_val" "$tol_abs" "$tol_pct")" = "PASS" ]; then
      pass "[$id.$k] agent=$agent_val ref=$ref_val"
    else
      fail "[$id.$k] agent=$agent_val ref=$ref_val (out of tolerance)"; rc_overall=1
    fi
  done
  return $rc_overall
}
