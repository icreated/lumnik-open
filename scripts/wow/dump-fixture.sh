#!/usr/bin/env bash
# Capture an anonymized, targeted subset of the live Adempiere DB into a committable .sql fixture.
# Strategy: dump chosen tables to a throwaway scratch DB, anonymize PII there, re-dump the scratch
# DB → fixtures/adempiere-subset.sql. The transient dump (with raw PII) is removed; the committed
# file is anonymized by construction. Aggregates (amounts/dates/FKs/statuses) are left untouched.
set -uo pipefail
cd "$(dirname "$0")"
source ./lib.sh
load_env WOW_SRC_URL WOW_SRC_USER WOW_SRC_PASSWORD WOW_HUB_HOST WOW_HUB_PORT WOW_RO_USER WOW_RO_PASSWORD

# Parse host/port/db out of the JDBC URL (jdbc:postgresql://host:port/db).
SRC_HP="${WOW_SRC_URL#jdbc:postgresql://}"
SRC_HOST="${SRC_HP%%:*}"
SRC_PORT="$(echo "$SRC_HP" | sed -E 's#^[^:]+:([0-9]+)/.*#\1#')"
SRC_DB="${SRC_HP##*/}"
[ -n "$SRC_HOST" ] && [ -n "$SRC_PORT" ] && [ -n "$SRC_DB" ] || die "could not parse WOW_SRC_URL=$WOW_SRC_URL"

BIZ_TABLES="${WOW_FIXTURE_TABLES:-c_order c_invoice c_bpartner c_payment}"
AD_TABLES="ad_table ad_column ad_element"
SCRATCH_DB="wow_fixture_scratch"
TMP_DUMP="$(mktemp -t wow_src_dump.XXXXXX.sql)"
OUT="fixtures/adempiere-subset.sql"
trap 'rm -f "$TMP_DUMP"' EXIT   # raw-PII transient dump never persists

src_psql() { PGPASSWORD="$WOW_SRC_PASSWORD" psql -h "$SRC_HOST" -p "$SRC_PORT" -U "$WOW_SRC_USER" -d "$SRC_DB" "$@"; }
hub_admin_psql() { PGPASSWORD="$WOW_RO_PASSWORD" psql -h "$WOW_HUB_HOST" -p "$WOW_HUB_PORT" -U "$WOW_RO_USER" "$@"; }

# Build --table args (business + AD).
T_ARGS=()
for t in $BIZ_TABLES $AD_TABLES; do T_ARGS+=(--table="$t"); done

echo "== 1. Dump targeted tables from live source (transient) =="
PGPASSWORD="$WOW_SRC_PASSWORD" pg_dump -h "$SRC_HOST" -p "$SRC_PORT" -U "$WOW_SRC_USER" -d "$SRC_DB" \
  --no-owner --no-privileges "${T_ARGS[@]}" -f "$TMP_DUMP" || die "pg_dump failed"

echo "== 2. Load into scratch DB =="
hub_admin_psql -d postgres -c "DROP DATABASE IF EXISTS $SCRATCH_DB" || true
hub_admin_psql -d postgres -c "CREATE DATABASE $SCRATCH_DB" || die "cannot create scratch DB (needs CREATEDB; use a privileged role via PGUSER/PGPASSWORD overrides)"
PGPASSWORD="$WOW_RO_PASSWORD" psql -h "$WOW_HUB_HOST" -p "$WOW_HUB_PORT" -U "$WOW_RO_USER" -d "$SCRATCH_DB" -f "$TMP_DUMP" >/dev/null

echo "== 3. Anonymize PII in scratch (aggregates preserved) =="
PGPASSWORD="$WOW_RO_PASSWORD" psql -h "$WOW_HUB_HOST" -p "$WOW_HUB_PORT" -U "$WOW_RO_USER" -d "$SCRATCH_DB" <<'SQL'
DO $$
BEGIN
  IF to_regclass('public.c_bpartner') IS NOT NULL THEN
    EXECUTE $u$UPDATE c_bpartner SET name = 'Client ' || c_bpartner_id$u$;
    BEGIN EXECUTE $u$UPDATE c_bpartner SET name2 = NULL$u$; EXCEPTION WHEN undefined_column THEN NULL; END;
  END IF;
END $$;
SQL

echo "== 4. Re-dump scratch → $OUT (anonymized, committable) =="
PGPASSWORD="$WOW_RO_PASSWORD" pg_dump -h "$WOW_HUB_HOST" -p "$WOW_HUB_PORT" -U "$WOW_RO_USER" -d "$SCRATCH_DB" \
  --no-owner --no-privileges -f "$OUT" || die "scratch re-dump failed"

echo "== 5. Drop scratch =="
hub_admin_psql -d postgres -c "DROP DATABASE IF EXISTS $SCRATCH_DB" || true

echo "Wrote $OUT. Review it for residual PII before committing."
