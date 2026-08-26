#!/usr/bin/env bash
# Restore a backup.sh snapshot into the RUNNING self-host stack.
#   usage: restore.sh backups/<UTC-stamp>
# Stops the hub during the DB restore (no writes mid-restore), swaps the Keycloak
# store if the snapshot has one. For a FRESH machine: run up.sh first (init scripts
# create roles/DB), then this script, and restore your saved .env BEFORE up.sh.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
COMPOSE=(docker compose -f docker-compose.selfhost.yml)

DIR="${1:?usage: restore.sh backups/<UTC-stamp>}"
[[ -f "$DIR/lumnik.dump" ]] || { echo "!! $DIR/lumnik.dump not found" >&2; exit 1; }

echo "!! This OVERWRITES the hub database (and Keycloak store, if present in the snapshot)"
echo "   with $DIR. Press Enter to continue, Ctrl-C to abort."
read -r

# Guarantee the stack comes back even if any step fails (set -e would otherwise
# leave the hub — and possibly keycloak — down until someone notices). Armed BEFORE
# the first stop so there is no unguarded window. `start` on an already-running
# service is a harmless no-op. Cleared after the normal start at the end.
trap '"${COMPOSE[@]}" start keycloak hub >/dev/null 2>&1 || true' EXIT

echo "==> stopping the hub"
"${COMPOSE[@]}" stop hub

echo "==> recreating the database (drop + create — avoids the partitioned-table"
echo "    inherited-PK limitation of an in-place --clean, and truly removes"
echo "    objects created after the snapshot)"
"${COMPOSE[@]}" exec -T postgres sh -c 'DB="${POSTGRES_DB:-lumnik}"; U="${POSTGRES_USER:-lumnik}"; \
  psql -U "$U" -d postgres -v ON_ERROR_STOP=1 \
    -c "SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE datname = '\''$DB'\'' AND pid <> pg_backend_pid();" \
  && dropdb -U "$U" --if-exists "$DB" \
  && createdb -U "$U" "$DB"'

echo "==> pg_restore into the fresh database"
"${COMPOSE[@]}" exec -T postgres sh -c 'pg_restore -U "${POSTGRES_USER:-lumnik}" -d "${POSTGRES_DB:-lumnik}"' < "$DIR/lumnik.dump"

if [[ -f "$DIR/keycloak-data.tgz" ]]; then
  echo "==> restoring keycloak store"
  "${COMPOSE[@]}" stop keycloak
  TMP="$(mktemp -d)"
  tar -xzf "$DIR/keycloak-data.tgz" -C "$TMP"
  rm -rf "$TMP/keycloak-data/import"   # belt: older backups may still carry it
  rm -f "$TMP/keycloak-data"/._* "$TMP/keycloak-data"/*/._* 2>/dev/null || true  # belt: AppleDouble relics in pre-fix snapshots
  # MERGE-copy the store contents into /opt/keycloak/data (a bare source dir would
  # NEST — data/h2/h2 — a silent no-op restore). Stream as a tar with ownership
  # rewritten to the container's keycloak user (uid 1000, gid 0): docker cp
  # preserves the tar's ownership, and host-uid-owned files leave the H2 store
  # READ-ONLY for Keycloak — it boots and serves reads, but every login/write
  # fails (H2 90097). Base flags verified portable: GNU tar 1.34 + bsdtar 3.5.3.
  # bsdtar (macOS) needs two extra flags GNU tar doesn't have (exits 64 on them):
  # --no-xattrs alone does NOT stop bsdtar smuggling com.apple.* metadata as
  # AppleDouble "._*" entries, which land as real junk files in the store.
  TAR_EXTRA=""
  if tar --version 2>/dev/null | grep -q bsdtar; then
    TAR_EXTRA="--no-fflags --no-mac-metadata"
  fi
  # shellcheck disable=SC2086 — TAR_EXTRA is a space-separated flag list
  tar -cf - --owner=1000 --group=0 --no-xattrs --no-acls $TAR_EXTRA \
      -C "$TMP/keycloak-data" . \
    | docker cp - "$("${COMPOSE[@]}" ps -aq keycloak)":/opt/keycloak/data   # -a: keycloak is stopped here
  rm -rf "$TMP"
  "${COMPOSE[@]}" start keycloak
fi

echo "==> starting the hub"
"${COMPOSE[@]}" start hub
trap - EXIT

echo "==> done."
echo "    scar reminder: if the public origin/issuer changed since this backup was taken,"
echo "    JIT identities in the hub DB reference the OLD issuer — restore to the same"
echo "    origin, or migrate identity.app_user.idp_issuer."
