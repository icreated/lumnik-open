#!/usr/bin/env bash
# Truth-check each act: run its reference SQL (read-only) and the analytic agent, compare key-aggregates.
# Refuses to run while any expect.value is still the TBD sentinel (calibration-first).
set -uo pipefail
cd "$(dirname "$0")"
source ./lib.sh
load_env WOW_SERVER WOW_TENANT_ID WOW_SCOPE WOW_HUB_HOST WOW_HUB_PORT WOW_HUB_DB WOW_RO_USER WOW_RO_PASSWORD

ACTS_FILE="${1:-./acts.json}"
ACTS="$(cat "$ACTS_FILE")" || die "cannot read $ACTS_FILE"
jq -e . >/dev/null 2>&1 <<<"$ACTS" || die "$ACTS_FILE is not valid JSON"
acts_assert_no_tbd "$ACTS" || die "calibrate acts.json first (freeze the TBD values via the runbook Step 0)"

psql_ro() { PGPASSWORD="$WOW_RO_PASSWORD" psql -h "$WOW_HUB_HOST" -p "$WOW_HUB_PORT" \
  -U "$WOW_RO_USER" -d "$WOW_HUB_DB" -tA -c "$1"; }

# ask_agent QUESTION — call the analytic endpoint, echo the JSON response.
ask_agent() {
  local q_json; q_json="$(jq -Rn --arg q "$1" '$q')"
  curl -s -X POST "$WOW_SERVER/api/platform/rag/chat" \
    -H "Content-Type: application/json" -H "X-Tenant-Id: $WOW_TENANT_ID" \
    -d "{\"question\":$q_json,\"scope\":\"$WOW_SCOPE\",\"mode\":\"analytic\"}"
}

N="$(jq '.acts | length' <<<"$ACTS")"
for i in $(seq 0 $((N-1))); do
  ACT="$(jq -c ".acts[$i]" <<<"$ACTS")"
  ID="$(jq -r '.id' <<<"$ACT")"
  Q="$(jq -r '.question' <<<"$ACT")"
  REF_SQL="$(jq -r '.reference_sql' <<<"$ACT")"
  echo "== Act $ID =="
  REF_JSON="$(psql_ro "$REF_SQL")" || { fail "[$ID] reference SQL failed"; continue; }
  AGENT_JSON="$(ask_agent "$Q")" || { fail "[$ID] agent call failed"; continue; }
  verify_one_act "$ACT" "$REF_JSON" "$AGENT_JSON" || true   # per-key verdicts already recorded
done

summary
