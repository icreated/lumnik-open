#!/usr/bin/env bash
# Snapshot/restore of the throwaway project so phases B and C start from the SAME state at N (#750).
set -euo pipefail
cd "$(dirname "$0")"; source ./lib.sh; source .run/env
HERE="$PWD"; REPO="$(cd ../.. && pwd)"   # stack.sh cd's to the repo root: hand it absolute file paths
ENT=(sz_exact sz_text sz_fuzzy sz_prefix)
counts() { for e in "${ENT[@]}"; do
  rows="$(bash ./stack.sh psql -tAc "select count(*) from connector.t_$e")"
  idx="$(sz_status "$SZ_BASE_URL" "$(bash ./stack.sh token)" "$e" | python3 -c 'import sys,json;print(json.load(sys.stdin)["documentCount"])')"
  echo "$e rows=$rows indexed=$idx"; done; }
case "${1:-}" in
  take) bash ./stack.sh pgdump "$HERE/.run/${2:?name}.dump"; counts > ".run/$2.counts"; echo "snapshot $2: $(tr '\n' ' ' < ".run/$2.counts")" ;;
  restore)
    n="${2:?name}"
    COMPOSE=(docker compose -p lmsizing --env-file "$HERE/.run/stack.env" -f "$REPO/docker-compose.selfhost.yml" -f "$HERE/compose.sizing.yml")
    # Stop the hub AND Keycloak around the restore, start both after: the hub must not hold connections on the database being replaced, and a token must be mintable before the counts proof asks for one
    SZ_DATA_DIR="$HERE/.run/data" "${COMPOSE[@]}" stop hub keycloak
    bash ./stack.sh pgrestore "$HERE/.run/$n.dump"
    SZ_DATA_DIR="$HERE/.run/data" "${COMPOSE[@]}" start keycloak hub
    cid="$(SZ_DATA_DIR="$HERE/.run/data" "${COMPOSE[@]}" ps -q hub)"
    for _ in $(seq 1 90); do [[ "$(docker inspect --format '{{.State.Health.Status}}' "$cid")" == healthy ]] && break; sleep 3; done
    # Keycloak was restarted too: the hub being healthy does not mean tokens can be minted yet
    for _ in $(seq 1 60); do [[ -n "$(bash ./stack.sh token 2>/dev/null)" ]] && break; sleep 3; done
    [[ -n "$(bash ./stack.sh token 2>/dev/null)" ]] || { echo "RESTORE: no token after restart (Keycloak not ready)" >&2; exit 1; }
    got="$(counts)"
    if [[ "$got" == "$(cat ".run/$n.counts")" ]]; then echo "restored $n: counts match ($(echo "$got" | tr '\n' ' '))"
    else echo "RESTORE MISMATCH for $n:"; diff <(cat ".run/$n.counts") <(echo "$got") || true; exit 1; fi ;;
  *) echo "usage: snapshot.sh take|restore NAME" >&2; exit 2 ;;
esac
