#!/usr/bin/env bash
# Restore a backup.sh snapshot into the RUNNING self-host stack.
#   usage: restore.sh backups/<UTC-stamp>
# Stops the hub during the DB restore (no writes mid-restore), and Keycloak while its
# database is replaced, if the snapshot has one. For a FRESH machine: run up.sh first (init scripts
# create roles/DB), then this script, and restore your saved .env BEFORE up.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
COMPOSE=(docker compose -f docker-compose.selfhost.yml)

DIR="${1:?usage: restore.sh backups/<UTC-stamp>}"
[[ -s "$DIR/lumnik.dump" ]] || { echo "!! $DIR/lumnik.dump not found, or empty" >&2; exit 1; }
[[ ! -e "$DIR/keycloak.dump" || -s "$DIR/keycloak.dump" ]] || { echo "!! $DIR/keycloak.dump is empty: this snapshot cannot restore Keycloak" >&2; exit 1; }
# A snapshot taken before #1997 holds Keycloak's H2 store, which this stack no longer runs. Refused before anything
# changes.
if [[ -f "$DIR/keycloak-data.tgz" && ! -f "$DIR/keycloak.dump" ]]; then
  {
    echo "!! $DIR holds Keycloak's store from before it moved to PostgreSQL (keycloak-data.tgz)."
    echo "   Restore it with the scripts of the release that took it, then come back to this one and run"
    echo "   ./deploy/selfhost/up.sh: it moves the realm to PostgreSQL (docs/operations/backup-restore.md)."
  } >&2
  exit 1
fi

echo "!! This OVERWRITES the hub database (and Keycloak's, if present in the snapshot)"
echo "   with $DIR. Press Enter to continue, Ctrl-C to abort."
read -r

# Guarantee the stack comes back even if any step fails (set -e would otherwise
# leave the hub — and possibly keycloak — down until someone notices). Armed BEFORE
# the first stop so there is no unguarded window. `start` on an already-running
# service is a harmless no-op. Cleared after the normal start at the end.
trap '"${COMPOSE[@]}" start keycloak hub >/dev/null 2>&1 || true' EXIT

echo "==> stopping the hub"
"${COMPOSE[@]}" stop hub

# restore_db <database> [owner]: drop + create, then pg_restore $DIR/<database>.dump into it. Drop + create avoids the
# partitioned-table inherited-PK limitation of an in-place --clean, and truly removes objects created after the
# snapshot; --force ends the sessions still connected.
restore_db() {
  "${COMPOSE[@]}" exec -T postgres sh -c 'U="${POSTGRES_USER:-lumnik}"; \
    dropdb -U "$U" --if-exists --force "$1" && createdb -U "$U" ${2:+-O "$2"} "$1"' _ "$1" "${2:-}"
  "${COMPOSE[@]}" exec -T postgres sh -c 'pg_restore -U "${POSTGRES_USER:-lumnik}" -d "$1"' _ "$1" < "$DIR/$1.dump"
}

echo "==> recreating the hub database from the snapshot"
restore_db lumnik

if [[ -f "$DIR/keycloak.dump" ]]; then
  echo "==> recreating Keycloak's database from the snapshot"
  "${COMPOSE[@]}" stop keycloak
  restore_db keycloak keycloak
  # A new database opens to every role: 03-keycloak.sh closes it again.
  "${COMPOSE[@]}" exec -T postgres sh -c 'POSTGRES_USER="${POSTGRES_USER:-lumnik}" bash /docker-entrypoint-initdb.d/03-keycloak.sh'
  "${COMPOSE[@]}" start keycloak
fi

echo "==> starting the hub"
"${COMPOSE[@]}" start hub
trap - EXIT

echo "==> done."
echo "    scar reminder: if the public origin/issuer changed since this backup was taken,"
echo "    JIT identities in the hub DB reference the OLD issuer — restore to the same"
echo "    origin, or migrate identity.app_user.idp_issuer."
