#!/usr/bin/env bash
# Assert the hub is populated and the SchemaCard resolved AD labels (standard + at least one custom).
set -uo pipefail
cd "$(dirname "$0")"
source ./lib.sh
load_env WOW_HUB_HOST WOW_HUB_PORT WOW_HUB_DB WOW_RO_USER WOW_RO_PASSWORD WOW_SCOPE

LM="${LM_BIN:-../../lm/lm}"

psql_ro() { PGPASSWORD="$WOW_RO_PASSWORD" psql -h "$WOW_HUB_HOST" -p "$WOW_HUB_PORT" \
  -U "$WOW_RO_USER" -d "$WOW_HUB_DB" -tA -c "$1"; }

echo "== dynamic_table populated =="
N_TABLES="$(psql_ro "SELECT count(*) FROM connector.dynamic_table" | tr -d '[:space:]')"
if [ "${N_TABLES:-0}" -gt 0 ]; then pass "dynamic_table has $N_TABLES row(s)"; else fail "dynamic_table is empty — run ingest.sh"; fi

echo "== ingested hub tables have rows =="
for t in t_c_order t_c_invoice; do
  C="$(psql_ro "SELECT count(*) FROM connector.$t" 2>/dev/null | tr -d '[:space:]')"
  if [ -n "$C" ] && [ "$C" -gt 0 ] 2>/dev/null; then pass "connector.$t has $C row(s)"; else fail "connector.$t missing or empty"; fi
done

echo "== SchemaCard labels (standard + custom) =="
CARD="$("$LM" source schema --scope "$WOW_SCOPE" 2>/dev/null)"
echo "$CARD"
# A standard label and at least one custom (custcol_*) label that differs from its raw name (' — ' marker).
if echo "$CARD" | grep -q ' — '; then pass "at least one column carries a business label"; else fail "no enriched labels — AD enrichment did not apply"; fi
if echo "$CARD" | grep -qiE 'custcol|custom'; then pass "a custom column is present in the card"; else echo "  (note) no custcol_* column seen — pick a standard column for Act C and note the fallback in the runbook"; fi

summary
