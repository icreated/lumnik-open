#!/usr/bin/env bash
# Stop the self-host lumnik stack. Pass --volumes to ALSO delete the postgres data volume, which holds Keycloak's
# database too (a full reset — you lose all ingested data and Keycloak state).
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
# Without a .env, compose can't interpolate the required (:?) vars — but that also means the stack was
# never started from here, so there is nothing to stop.
if [[ ! -f .env ]]; then
  echo "==> nothing to stop — no .env (start the stack first: ./deploy/selfhost/up.sh)"
  exit 0
fi
docker compose -f docker-compose.selfhost.yml down "$@"
# Keycloak's H2 volume from before #1997 is no longer in the compose file: a full reset removes it by name, or the next
# up.sh would move its realm back.
for arg in "$@"; do
  case "$arg" in
    -v|--volumes)
      # shellcheck source=deploy/selfhost/volumes.sh
      source deploy/selfhost/volumes.sh
      docker volume rm "$(compose_project)_selfhost_kc" >/dev/null 2>&1 && echo "    removed $(compose_project)_selfhost_kc" || true ;;
  esac
done
