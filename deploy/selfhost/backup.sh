#!/usr/bin/env bash
# Back up the self-host stack into backups/<UTC-stamp>/:
#   lumnik.dump        — hot pg_dump -Fc of the hub database (no downtime)
#   keycloak-data.tgz  — cold copy of Keycloak's dev-file store (brief keycloak stop:
#                        H2 holds a file lock while running, and the admin API cannot
#                        export password hashes — a live export would lose credentials)
# The ~15s Keycloak stop only blocks NEW logins; already-issued tokens keep working.
# NOT covered here: .env (the master key!) — back it up separately and off-machine.
set -euo pipefail

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

echo "==> pg_dump (hot) -> $DIR/lumnik.dump"
"${COMPOSE[@]}" exec -T postgres sh -c 'pg_dump -U "${POSTGRES_USER:-lumnik}" -Fc "${POSTGRES_DB:-lumnik}"' > "$DIR/lumnik.dump"
[[ -s "$DIR/lumnik.dump" ]] || { echo "!! pg_dump produced an empty file" >&2; exit 1; }

if [[ "$SKIP_KC" -eq 0 ]]; then
  echo "==> keycloak store (brief stop: new logins fail ~15s, issued tokens keep working)"
  # Guarantee keycloak comes back even if the stop/copy fails mid-window (set -e
  # would otherwise exit with auth down until someone notices). Armed BEFORE the
  # stop so there is no unguarded window.
  trap '"${COMPOSE[@]}" start keycloak >/dev/null 2>&1 || true' EXIT
  "${COMPOSE[@]}" stop keycloak
  "${COMPOSE[@]}" cp keycloak:/opt/keycloak/data "$DIR/keycloak-data"
  # data/import is the repo's own realm-import bind mount (infra/keycloak, in git) —
  # reproducible, read-only in the container, and it breaks restore's write-back. Exclude it.
  rm -rf "$DIR/keycloak-data/import"
  "${COMPOSE[@]}" start keycloak
  trap - EXIT
  # Suppress macOS metadata at the SOURCE: a mac-made archive restored on a Linux
  # host would extract AppleDouble entries as literal ._* files (GNU tar does not
  # interpret them) and the re-stream would carry them into the store.
  # bash 3.2 (macOS default): plain string, not an array — do not "modernize".
  TAR_EXTRA=""
  if tar --version 2>/dev/null | grep -q bsdtar; then
    TAR_EXTRA="--no-xattrs --no-fflags --no-mac-metadata"
  fi
  # shellcheck disable=SC2086 — TAR_EXTRA is a space-separated flag list
  tar -czf "$DIR/keycloak-data.tgz" $TAR_EXTRA -C "$DIR" keycloak-data
  rm -rf "$DIR/keycloak-data"
fi

# Retention: keep the newest $KEEP backup dirs (portable — no GNU head -n -N).
COUNT="$(ls -1d backups/*/ 2>/dev/null | wc -l | tr -d ' ')"
if [[ "$COUNT" -gt "$KEEP" ]]; then
  ls -1d backups/*/ | sort | head -n "$((COUNT - KEEP))" | xargs rm -rf
  echo "==> retention: pruned to the newest $KEEP backups"
fi

echo "==> done: $DIR"
echo "    reminder: back up .env separately — losing LUMNIK_SECRET_MASTER_KEY makes stored secrets unrecoverable."
