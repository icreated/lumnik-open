#!/usr/bin/env bash
# Off-stage: ingest the live Adempiere DB into the hub, then (re)build the SchemaCard.
# Idempotent: re-running reuses existing connectors. Tables come from $1 (comma list) or a default set.
set -uo pipefail
cd "$(dirname "$0")"
source ./lib.sh
load_env WOW_SRC_URL WOW_SRC_USER WOW_SRC_PASSWORD WOW_SCOPE

LM="${LM_BIN:-../../lm/lm}"
TABLES="${1:-c_order,c_invoice,c_bpartner,c_payment}"
SOURCE_NAME="${WOW_SOURCE_NAME:-wow-atelier}"

echo "== Ingest '$SOURCE_NAME' tables: $TABLES =="
# Password is piped via stdin (lm reads it with readPasswordStdin); never appears in argv.
printf '%s\n' "$WOW_SRC_PASSWORD" | "$LM" source jdbc add "$SOURCE_NAME" \
  --url "$WOW_SRC_URL" --user "$WOW_SRC_USER" --tables "$TABLES" \
  || die "lm source jdbc add failed"

echo "== Tag connectors with scope '$WOW_SCOPE' (so discovery resolves them) =="
echo "   NOTE: confirm the created connectors carry the '$WOW_SCOPE' tag — set via 'lm connector tag' if needed."

echo "== Rediscover SchemaCard for scope '$WOW_SCOPE' =="
"$LM" source rediscover --scope "$WOW_SCOPE" || die "lm source rediscover failed"

echo "Done. Next: scripts/wow/schemacard-sanity.sh"
