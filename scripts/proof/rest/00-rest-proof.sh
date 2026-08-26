#!/usr/bin/env bash
# REST proof — drive each docs/connectors/rest manifest through the real lm binary and assert
# the rows land in ext.rest_<kind>. Reuses the WOW lib (lm wrapper, counters, ro_sql).
#
# Prereqs:
#   - platform on :8080  (mvn quarkus:dev -pl lumnik-hub -am)
#   - wiremock on :8081  (docker compose up -d wiremock)
#   - scripts/wow/.env
#   - Platform env vars:
#       REST_DEMO_TOKEN=<any non-empty value>    (Bearer token for 30-cursor-bearer)
#       REST_DEMO_BASIC_PW=<any non-empty value> (Basic auth for 20-page-basic + 50-relay-basic)
#       REST_DEMO_APIKEY=<any non-empty value>   (X-API-Key for 40-link-header-apikey)
#     WireMock does NOT enforce auth (stubs match on path only), so any value works for demo.
#
# Best-effort: skips cleanly if :8081 (WireMock) is not reachable.
# ITs (RestEngineBreadthIT) are the quality gate; this script is the live vitrine.
source "$(cd "$(dirname "${BASH_SOURCE[0]}")/../../wow/tests" && pwd)/lib.sh"
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"

# ── Tenant-scoped admin helper ─────────────────────────────────────────────────
admin_sql() {
  PGPASSWORD="${WOW_ADMIN_PASSWORD}" psql \
    -h "${WOW_HUB_HOST:-localhost}" -p "${WOW_HUB_PORT:-5433}" \
    -U "${WOW_ADMIN_USER:-lumnik}" -d "${WOW_HUB_DB:-lumnik}" \
    -tAc "SET app.current_tenant='${WOW_TENANT_ID:-1}'; $1" 2>/dev/null
}

# ── Guard: platform up? ─────────────────────────────────────────────────────────
hdr "platform reachable?"
curl -s -m 5 "${WOW_SERVER}/q/openapi" >/dev/null 2>&1 \
  && ok "platform on ${WOW_SERVER}" \
  || { bad "platform down — mvn quarkus:dev -pl lumnik-hub -am"; summary; exit 1; }

# ── Guard: WireMock up? ──────────────────────────────────────────────────────────
hdr "wiremock reachable? (:8081)"
if ! command -v nc >/dev/null 2>&1 || ! nc -z localhost 8081 2>/dev/null; then
  echo "  WireMock is not reachable on :8081."
  echo "  Start it with:  docker compose up -d wiremock"
  echo "  Skipping REST proof (ITs are the gate)."
  summary
  exit 0
fi
ok "wiremock on :8081"

# ── Clean slate ─────────────────────────────────────────────────────────────────
# Drop demo ext.rest_* tables and their registry rows (tenant-scoped).
# ext.* tables are hybrid tables written by the REST engine; DROP TABLE resets the schema
# for all tenants (acceptable on a dedicated local dev hub).
hdr "clean slate (drop demo ext.rest_* tables and registry rows)"
T="${WOW_TENANT_ID:-1}"
for name in rest-offset rest-page rest-cursor rest-link-header rest-relay; do
  admin_sql "DELETE FROM connector.connector_endpoint ep USING connector.connector c
             WHERE ep.connector_id = c.id AND c.tenant_id = ${T} AND c.name = '${name}'" >/dev/null
  admin_sql "DELETE FROM connector.connector WHERE tenant_id=${T} AND name='${name}'" >/dev/null
done
for tbl in rest_offset rest_page rest_cursor rest_link_header rest_relay; do
  admin_sql "DROP TABLE IF EXISTS ext.${tbl} CASCADE" >/dev/null
done
ok "demo artifacts cleared (tenant ${T})"

# ── Run a manifest end-to-end ────────────────────────────────────────────────────
# run_case <manifest-path> <connector-name> <ext-table> <expected-min-rows>
run_case() {
  local manifest="$1" name="$2" table="$3" minrows="$4"
  hdr "$name"
  lm apply -f "$REPO/$manifest" >/dev/null 2>&1 \
    && ok "applied $name" \
    || { bad "apply $name"; return; }
  lm connector run "$name" >/dev/null 2>&1 \
    && ok "triggered $name" \
    || { bad "run $name"; return; }
  local n=0
  for i in $(seq 1 30); do
    n="$(ro_sql "SELECT count(*) FROM ${table}" 2>/dev/null | tr -d ' ')"
    [ "${n:-0}" -ge "$minrows" ] 2>/dev/null && break
    sleep 1
  done
  [ "${n:-0}" -ge "$minrows" ] \
    && ok "$name → ${n} row(s) in ${table}" \
    || bad "$name → ${n:-0} rows (<${minrows}) in ${table}"
}

# ── Run all 5 strategies ─────────────────────────────────────────────────────────
run_case docs/connectors/rest/10-offset-none.yaml       rest-offset      ext.rest_offset      3
run_case docs/connectors/rest/20-page-basic.yaml        rest-page        ext.rest_page        3
run_case docs/connectors/rest/30-cursor-bearer.yaml     rest-cursor      ext.rest_cursor      3
run_case docs/connectors/rest/40-link-header-apikey.yaml rest-link-header ext.rest_link_header 3
run_case docs/connectors/rest/50-relay-basic.yaml       rest-relay       ext.rest_relay       3

summary
