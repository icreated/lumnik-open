#!/usr/bin/env bash
# Run a k6 scenario against a lumnik stack, handling auth for you.
#
#   ./loadtest/run.sh smoke.js
#   MAX_VUS=50 ./loadtest/run.sh read-mix.js
#
# Token: K6_TOKEN (a real install: "$(lm token)"), else minted from the HOST via the lumnik-smoke client's direct
# grant on a throwaway realm, so the token's `iss` matches the issuer the hub trusts (minting it from inside the
# k6 container via host.docker.internal would change the issuer and every
# call would 401 — the TLS-façade scar, avoided by construction).
# Access tokens from the bundled realm live 5 minutes (accessTokenLifespan: 300), and k6 holds the one minted here
# for the whole run: read-mix's default stages take under 3 minutes. A longer run (a larger STAGE) ends in 401s.
set -euo pipefail

SCRIPT="${1:?usage: run.sh <scenario.js> (smoke.js | read-mix.js)}"
DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$DIR/.." && pwd)"

KC_URL="${KC_URL:-http://localhost:8180}"
BASE_URL="${BASE_URL:-http://localhost:8080}"
K6_USER="${K6_USER:-integrator}"

# Pull k6 first: a first pull must not eat into the 5 minutes the token lives.
docker pull -q grafana/k6 >/dev/null

# The token, taken after the pull so the run gets its whole lifespan (5 minutes on the bundled realm):
# - K6_TOKEN, when you pass one;
# - else K6_PASSWORD: the password grant on lumnik-smoke, a client only the throwaway realms carry (the Search sizing
#   stack, a kind cluster);
# - else `lm token --fresh`: your `lm login` session, renewed now (#1997: the shipped clients take no password).
if [ -z "${K6_TOKEN:-}" ] && [ -n "${K6_PASSWORD:-}" ]; then
  echo "→ minting token for '$K6_USER' at $KC_URL (client lumnik-smoke, direct grant)"
  K6_TOKEN="$(printf '%s' "$K6_PASSWORD" | curl -sf "$KC_URL/realms/lumnik/protocol/openid-connect/token" \
    -d grant_type=password -d client_id=lumnik-smoke \
    -d username="$K6_USER" --data-urlencode password@- \
    | python3 -c 'import sys,json; print(json.load(sys.stdin)["access_token"])')" \
    || { echo "no token from $KC_URL: only a throwaway realm has lumnik-smoke — on a real install, lm login, then re-run without K6_PASSWORD" >&2; exit 1; }
elif [ -z "${K6_TOKEN:-}" ]; then
  echo "→ token from lm (lm token --fresh)"
  K6_TOKEN="$(lm token --fresh)" || { echo "no token: run 'lm login' first (or set K6_TOKEN)" >&2; exit 1; }
fi

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
  -e RATE="${RATE:-}" -e DURATION="${DURATION:-}" -e PHASE="${PHASE:-}" -e ENTITIES="${ENTITIES:-}" \
  -e MIN_HIT_SHARE="${MIN_HIT_SHARE:-}" -e SUMMARY_OUT="${SUMMARY_OUT:-}" \
  ${K6_DATA_DIR:+-v "$K6_DATA_DIR:/data:ro"} ${K6_OUT_DIR:+-v "$K6_OUT_DIR:/out"} \
  ${K6_CONTAINER:+--name "$K6_CONTAINER"} \
  grafana/k6 run "/scripts/$SCRIPT"
