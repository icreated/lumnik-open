#!/usr/bin/env bash
# Shared helpers for the lm-based WOW test suites.
# Loads scripts/wow/.env, exposes lm wrapper, pass/fail counters, and an analytic-ask helper.
set -uo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
WOW_DIR="$(cd "$HERE/.." && pwd)"
REPO="$(cd "$WOW_DIR/../.." && pwd)"

# Load env (WOW_SERVER, WOW_SCOPE, WOW_SRC_URL, WOW_SRC_USER, WOW_SRC_PASSWORD, WOW_HUB_*)
set -a; source "$WOW_DIR/.env"; set +a
: "${WOW_SERVER:=http://localhost:8080}"
: "${WOW_SCOPE:=atelier}"
: "${WOW_TENANT_ID:=1}"

LM="${LM_BIN:-$REPO/lm/lm}"

PASS=0; FAIL=0
ok()   { echo "  ✅ $*"; PASS=$((PASS+1)); }
bad()  { echo "  ❌ $*"; FAIL=$((FAIL+1)); }
hdr()  { echo; echo "== $* =="; }

summary() {
  echo; echo "──────── $PASS passed, $FAIL failed ────────"
  [ "$FAIL" -eq 0 ]
}

# lm <args...> — thin wrapper (lm defaults to http://localhost:8080).
lm() { "$LM" "$@"; }

# analytic_ask "<question>" -> prints JSON to stdout; the analytic (text-to-SQL) acts have
# NO lm command (RagAsk is unwired), so we hit the REST endpoint directly.
analytic_ask() {
  local q="$1"
  curl -s -m 150 -XPOST -H "Content-Type: application/json" -H "X-Tenant-Id: ${WOW_TENANT_ID}" \
    -d "{\"question\":$(json_str "$q"),\"mode\":\"analytic\",\"scope\":\"${WOW_SCOPE}\"}" \
    "${WOW_SERVER}/api/platform/rag/chat"
}

# json_str <text> -> a JSON-quoted string (handles quotes/newlines)
json_str() { python3 -c 'import json,sys; print(json.dumps(sys.argv[1]))' "$1"; }

# Parse an analytic response on stdin and print:  "<rowcount> | <sql-one-line> | <answer-snippet>"
# No eval — plain json.load over the trusted local endpoint response.
parse_analytic() {
  python3 -c '
import json,sys
try:
    d=json.load(sys.stdin)
except Exception:
    print("PARSE_ERR|"); sys.exit(0)
rows=d.get("rows") or []
sql=(d.get("sql") or "").replace(chr(10)," ")
ans=(d.get("answer") or "")[:160].replace(chr(10)," ")
print(f"{len(rows)}|{sql}|{ans}")'
}

# ro_sql "<sql>" -> run read-only SQL on the hub as lumnik_readonly WITH the tenant GUC
# (so it works whether or not RLS is enabled on the t_* tables).
ro_sql() {
  PGPASSWORD="${WOW_RO_PASSWORD:-lumnik_readonly_dev_password}" \
    psql -h "${WOW_HUB_HOST:-localhost}" -p "${WOW_HUB_PORT:-5433}" -U "${WOW_RO_USER:-lumnik_readonly}" \
    -d "${WOW_HUB_DB:-lumnik}" -tAc "SET app.current_tenant='${WOW_TENANT_ID}'; $1"
}
