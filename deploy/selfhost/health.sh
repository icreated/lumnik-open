#!/usr/bin/env bash
# The hub's health. It answers on the hub's management port, 9000, which the stack does not publish (#1993): asked
# from inside the hub's container. Pass live or ready for one probe.
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"
if [[ ! -f .env ]]; then
  echo "==> no stack yet — no .env (start it first: ./deploy/selfhost/up.sh)" >&2
  exit 1
fi
# --fail-with-body: a DOWN hub (503) exits non-zero, and still prints which check failed.
docker compose -f docker-compose.selfhost.yml exec -T hub curl -sS --fail-with-body "http://localhost:9000/q/health${1:+/$1}"
echo
