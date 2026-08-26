#!/usr/bin/env bash
# Stop the self-host lumnik stack. Pass --volumes to ALSO delete the postgres + keycloak
# data volumes (a full reset — you lose all ingested data and Keycloak state).
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
