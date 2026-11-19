#!/usr/bin/env bash
# Back up the self-host stack into backups/<UTC-stamp>/:
#   lumnik.dump        — hot pg_dump -Fc of the hub database (no downtime)
#   keycloak.dump      — hot pg_dump -Fc of Keycloak's database: realms, users, password
#                        hashes, signing keys (#1997: Keycloak keeps them in the stack's
#                        PostgreSQL; no downtime either)
# NOT covered here: .env (the master key!) — back it up separately and off-machine.
set -euo pipefail
umask 077  # #1998: a backup is every tenant's data and Keycloak's password hashes and signing keys

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
COMPOSE=(docker compose -f docker-compose.selfhost.yml)

KEEP=7
SKIP_KC=0
while [[ $# -gt 0 ]]; do
  case "$1" in
    --keep) KEEP="${2:?--keep needs a number}"
            # Positive integer only: a stray string would blow up the retention math,
            # and 0 would prune the snapshot this very run just created.
            [[ "$KEEP" =~ ^[1-9][0-9]*$ ]] || { echo "!! --keep must be a positive integer >= 1 (got: $KEEP)" >&2; exit 2; }
            shift 2 ;;
    --no-keycloak) SKIP_KC=1; shift ;;
    -h|--help) echo "Usage: $(basename "$0") [--keep N] [--no-keycloak]"; exit 0 ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

STAMP="$(date -u +%Y%m%dT%H%M%SZ)"
DIR="backups/$STAMP"
mkdir -p "$DIR"
chmod -R go= backups   # snapshots taken before #1998 were written under the caller's umask

# dump_db <database>: a hot pg_dump -Fc into $DIR/<database>.dump
dump_db() {
  echo "==> pg_dump (hot) -> $DIR/$1.dump"
  "${COMPOSE[@]}" exec -T postgres sh -c 'pg_dump -U "${POSTGRES_USER:-lumnik}" -Fc "$1"' _ "$1" > "$DIR/$1.dump"
  [[ -s "$DIR/$1.dump" ]] || { rm -f "$DIR/$1.dump"; echo "!! pg_dump produced an empty file" >&2; exit 1; }
}
dump_db lumnik
[[ "$SKIP_KC" -eq 1 ]] || dump_db keycloak

# Retention: keep the newest $KEEP backup dirs (portable — no GNU head -n -N).
COUNT="$(ls -1d backups/*/ 2>/dev/null | wc -l | tr -d ' ')"
if [[ "$COUNT" -gt "$KEEP" ]]; then
  ls -1d backups/*/ | sort | head -n "$((COUNT - KEEP))" | xargs rm -rf
  echo "==> retention: pruned to the newest $KEEP backups"
fi

echo "==> done: $DIR"
echo "    reminder: back up .env separately — losing LUMNIK_SECRET_MASTER_KEY makes stored secrets unrecoverable."
