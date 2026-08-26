#!/usr/bin/env bash
# Tail the self-host stack logs. Pass a service name (hub | postgres | keycloak) to filter.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
if [[ ! -f .env ]]; then
  echo "==> no stack yet — no .env (start it first: ./deploy/selfhost/up.sh)" >&2
  exit 1
fi
docker compose -f docker-compose.selfhost.yml logs -f "$@"
