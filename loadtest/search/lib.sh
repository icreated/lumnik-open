#!/usr/bin/env bash
# Convergence of the Search projection, as ONE predicate (#750).
# lagSeconds == 0 alone proves nothing: between a run's terminal state and the asynchronous creation of the change
# marks, lag already reads 0 while nothing has been projected. So convergence also requires the indexed count to
# equal the expected one, and that count not to be stale — for every index, at once.

sz_status() {  # <base-url> <token> <entity>
  curl -fsS --max-time 10 -H "Authorization: Bearer $2" "$1/api/platform/search/index/$3/status"
}

SZ_CHECK_PY=$(cat <<'PYCHECK'
import json, os, sys
d = json.loads(os.environ["BODY"]); n = os.environ["NAME"]; exp = int(os.environ["EXPECTED"])
if d.get("lagSeconds") != 0: sys.exit(f"{n}: lagSeconds={d.get('lagSeconds')}")
if d.get("documentCountStale") is not False: sys.exit(f"{n}: documentCountStale={d.get('documentCountStale')}")
if d.get("documentCount") != exp: sys.exit(f"{n}: documentCount={d.get('documentCount')} expected {exp}")
PYCHECK
)

sz_converged() {  # <base-url> <token> <expected> <entity...>
  local base="$1" tok="$2" expected="$3"; shift 3
  local e body
  for e in "$@"; do
    body="$(sz_status "$base" "$tok" "$e")" || { echo "status of $e unavailable"; return 1; }
    BODY="$body" EXPECTED="$expected" NAME="$e" python3 -c "$SZ_CHECK_PY" 2>&1 || return 1
  done
}

# A wait can outlive an access token (the realm's lives one hour; loading a 50k base takes ~100 minutes). When
# SZ_TOKEN_CMD names a command that prints a fresh token, sz_wait_converged mints one at most every SZ_TOKEN_REFRESH_S
# seconds (default 1500: well inside an hour, and inside a stricter IdP's lifetime if you lower it) instead of polling with
# the stale one until the timeout. Without SZ_TOKEN_CMD the token argument is used as given.
_sz_refresh_token() {  # <fallback-token> — sets _SZ_CUR (a plain call, never $(...): the cache must outlive the call)
  _SZ_CUR="$1"
  [[ -n "${SZ_TOKEN_CMD:-}" ]] || return 0
  if [[ -z "${_SZ_TOK:-}" ]] || (( SECONDS - ${_SZ_TOK_AT:-0} >= ${SZ_TOKEN_REFRESH_S:-1500} )); then
    if _SZ_TOK="$($SZ_TOKEN_CMD)" && [[ -n "$_SZ_TOK" ]]; then _SZ_TOK_AT=$SECONDS; else _SZ_TOK=""; fi
  fi
  [[ -z "$_SZ_TOK" ]] || _SZ_CUR="$_SZ_TOK"
}

sz_wait_converged() {  # <timeout-s> <base-url> <token> <expected> <entity...>
  local timeout="$1" base="$2" tok="$3" expected="$4"; shift 4
  local deadline=$((SECONDS + timeout)) why=""
  _SZ_TOK=""   # a new wait starts from a fresh mint
  while (( SECONDS < deadline )); do
    _sz_refresh_token "$tok"
    why="$(sz_converged "$base" "$_SZ_CUR" "$expected" "$@" 2>&1)" && return 0
    sleep 2
  done
  echo "not converged after ${timeout}s: $why" >&2
  return 1
}

sz_max_lag() {  # <base-url> <token> <entity...>
  local base="$1" tok="$2"; shift 2
  local e max=0 lag
  for e in "$@"; do
    lag="$(sz_status "$base" "$tok" "$e" | python3 -c 'import sys,json;print(int(json.load(sys.stdin).get("lagSeconds",0)))')"
    (( lag > max )) && max=$lag
  done
  echo "$max"
}
