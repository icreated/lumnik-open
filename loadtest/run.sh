#!/usr/bin/env bash
# Run a k6 scenario against a lumnik stack, handling auth for you.
#
#   ./loadtest/run.sh smoke.js
#   MAX_VUS=50 ./loadtest/run.sh read-mix.js
#
# Token: minted from the HOST via the lm-cli client's direct grant, so the
# token's `iss` matches the issuer the hub trusts (minting it from inside the
# k6 container via host.docker.internal would change the issuer and every
# call would 401 — the TLS-façade scar, avoided by construction).
# Access tokens from the bundled realm live 1 h (accessTokenLifespan: 3600) —
# ample for any scenario here; re-run to mint a fresh one if a run ever outlives it.
set -euo pipefail

SCRIPT="${1:?usage: run.sh <scenario.js> (smoke.js | read-mix.js)}"
DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$DIR/.." && pwd)"

KC_URL="${KC_URL:-http://localhost:8180}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
K6_USER="${K6_USER:-integrator}"

# Password: explicit env wins; else the selfhost .env at the repo root.
if [ -z "${K6_PASSWORD:-}" ] && [ -f "$REPO_ROOT/.env" ]; then
  # || [ $? -eq 1 ]: a .env without the key must fall through to the :? message
  # below, not die silently at the grep (set -euo pipefail) — but a real grep
  # error (rc 2, e.g. unreadable .env) still fails fast.
  K6_PASSWORD="$(grep -E '^INTEGRATOR_PASSWORD=' "$REPO_ROOT/.env" | cut -d= -f2-)" || [ $? -eq 1 ]
fi
: "${K6_PASSWORD:?K6_PASSWORD not set and INTEGRATOR_PASSWORD not found in .env}"

echo "→ minting token for '$K6_USER' at $KC_URL (client lm-cli, direct grant)"
K6_TOKEN="$(curl -sf "$KC_URL/realms/lumnik/protocol/openid-connect/token" \
  -d grant_type=password -d client_id=lm-cli \
  -d username="$K6_USER" --data-urlencode password="$K6_PASSWORD" \
  | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')"

# Inside the container, localhost is the container — rewrite to the host
# (both spellings), and map host.docker.internal for Linux hosts too
# (a no-op on Docker Desktop, required with the host-gateway on Linux).
K6_BASE="${BASE_URL/localhost/host.docker.internal}"
K6_BASE="${K6_BASE/127.0.0.1/host.docker.internal}"

echo "→ k6 $SCRIPT against $BASE_URL"
docker run --rm -i \
  --add-host=host.docker.internal:host-gateway \
  -v "$DIR/k6:/scripts:ro" \
  -e BASE_URL="$K6_BASE" \
  -e K6_TOKEN="$K6_TOKEN" \
  -e MAX_VUS="${MAX_VUS:-}" -e STAGE="${STAGE:-}" \
  -e P95_MS="${P95_MS:-}" -e THINK_MS="${THINK_MS:-}" \
  -e ONLY="${ONLY:-}" \
  grafana/k6 run "/scripts/$SCRIPT"
