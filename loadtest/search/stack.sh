#!/usr/bin/env bash
# Throwaway stack for the Search sizing run (#750). Never touches the real self-host stack: own compose project,
# own env file (generated secrets), own host ports, own volumes.
set -euo pipefail
cd "$(cd "$(dirname "$0")/../.." && pwd)"
RUN_DIR="loadtest/search/.run"; ENV_FILE="$RUN_DIR/stack.env"; PROJECT=lmsizing
SZ_DATA_DIR="$PWD/$RUN_DIR/data"; export SZ_DATA_DIR
COMPOSE=(docker compose -p "$PROJECT" --env-file "$ENV_FILE" -f docker-compose.selfhost.yml -f loadtest/search/compose.sizing.yml)

need_compose() {
  # Strip an optional leading "v" first: with it, awk compares "v2" to 2 as STRINGS and accepts any v-prefixed version.
  docker compose version --short | sed 's/^v//' | awk -F. '{exit !($1+0>2 || ($1+0==2 && $2+0>=24))}' \
    || { echo "docker compose >= 2.24 is required (port override); refusing to risk the real ports" >&2; exit 1; }
}
gen_env() {
  mkdir -p "$RUN_DIR/data"; umask 077
  [[ -f "$ENV_FILE" ]] && return 0
  for k in POSTGRES_PASSWORD KEYCLOAK_ADMIN_PASSWORD LUMNIK_SECRET_MASTER_KEY LUMNIK_CRYPTO_SECRET_KEY LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET INTEGRATOR_PASSWORD \
           LUMNIK_APP_DB_PASSWORD LUMNIK_ADMIN_DB_PASSWORD LUMNIK_READONLY_DB_PASSWORD; do
    case "$k" in
      LUMNIK_SECRET_MASTER_KEY|LUMNIK_CRYPTO_SECRET_KEY|LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET) v="$(openssl rand -base64 32)" ;;   # the keys' documented format
      *) v="$(openssl rand -hex 24)" ;;
    esac
    printf '%s=%s\n' "$k" "$v" >> "$ENV_FILE"
  done
  printf 'POSTGRES_USER=lumnik\n' >> "$ENV_FILE"
}
val() { grep "^$1=" "$ENV_FILE" | cut -d= -f2-; }

cmd_up() {
  need_compose; gen_env
  # Every rebuild leaves a 557 MB orphan image behind (the build context is the whole repo): build only when the sizing
  # image is missing, or when REBUILD=1 asks for it.
  local build=(); if [[ -n "${REBUILD:-}" ]] || [[ -z "$(docker images -q lumnik-hub:sizing)" ]]; then build=(--build); fi
  "${COMPOSE[@]}" up -d ${build[@]+"${build[@]}"}
  local cid; cid="$("${COMPOSE[@]}" ps -q hub)"
  for _ in $(seq 1 90); do
    [[ "$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{end}}' "$cid")" == healthy ]] && break; sleep 3
  done
  [[ "$(docker inspect --format '{{.State.Health.Status}}' "$cid")" == healthy ]] || { echo "hub never became healthy" >&2; exit 1; }
  # the hub being healthy says nothing about Keycloak: wait until the lumnik realm answers before any admin call
  for _ in $(seq 1 60); do curl -fsS -o /dev/null http://localhost:18180/realms/lumnik && break; sleep 3; done
  curl -fsS -o /dev/null http://localhost:18180/realms/lumnik || { echo "Keycloak never served the lumnik realm" >&2; exit 1; }
  # the realm ships the integrator WITHOUT a credential: set it through the Keycloak admin API (as up.sh does)
  local tok uid
  tok="$(curl -fsS -X POST http://localhost:18180/realms/master/protocol/openid-connect/token -d grant_type=password \
        -d client_id=admin-cli -d username=admin --data-urlencode "password=$(val KEYCLOAK_ADMIN_PASSWORD)" \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])')"
  uid="$(curl -fsS -H "Authorization: Bearer $tok" 'http://localhost:18180/admin/realms/lumnik/users?username=integrator&exact=true' \
        | python3 -c 'import sys,json;print(json.load(sys.stdin)[0]["id"])')"
  PW="$(val INTEGRATOR_PASSWORD)" python3 -c 'import json,os;print(json.dumps({"type":"password","temporary":False,"value":os.environ["PW"]}))' \
    | curl -fsS -X PUT "http://localhost:18180/admin/realms/lumnik/users/$uid/reset-password" \
        -H "Authorization: Bearer $tok" -H 'Content-Type: application/json' -d @-
  # A load harness mints many tokens: Keycloak's brute-force protection locked the integrator out mid-run
  # ("user_temporarily_disabled", 401 on a correct password). Off for this THROWAWAY realm only.
  curl -fsS -X PUT "http://localhost:18180/admin/realms/lumnik" -H "Authorization: Bearer $tok" \
    -H 'Content-Type: application/json' -d '{"realm":"lumnik","bruteForceProtected":false}'
  printf 'SZ_BASE_URL=http://localhost:18080\nSZ_KC_URL=http://localhost:18180\nSZ_PROJECT=%s\nSZ_DATA_DIR=%s\n' "$PROJECT" "$SZ_DATA_DIR" > "$RUN_DIR/env"
}
cmd_token() {
  curl -fsS http://localhost:18180/realms/lumnik/protocol/openid-connect/token -d grant_type=password -d client_id=lm-cli \
    -d username=integrator --data-urlencode "password=$(val INTEGRATOR_PASSWORD)" \
    | python3 -c 'import sys,json;print(json.load(sys.stdin)["access_token"])'
}
pg() { "${COMPOSE[@]}" exec -T postgres "$@"; }
# pg_restore --clean cannot drop the inherited primary key of core.audit_event_default (a partition): one known,
# harmless error. Any OTHER error fails the restore; the snapshot's counts proof is the final gate either way.
cmd_pgrestore() {
  local err rc=0
  err="$(pg pg_restore -U lumnik -d lumnik --clean --if-exists < "$1" 2>&1 >/dev/null)" || rc=$?
  if (( rc != 0 )); then
    # Tolerated only when pg_restore itself reported errors and every one is the known benign one. A failure with no
    # "pg_restore: error:" line at all (container down, docker error, empty output) is NOT a partial restore: it fails.
    local errs other
    errs="$(echo "$err" | grep -E '^pg_restore: error:' || true)"
    other="$(echo "$errs" | grep -v 'cannot drop inherited constraint "audit_event_default_pkey"' || true)"
    [[ -n "$errs" && -z "$other" ]] || { echo "$err" >&2; return 1; }
  fi
}
case "${1:-}" in
  up) cmd_up ;;
  token) cmd_token ;;
  down) "${COMPOSE[@]}" down -v --remove-orphans; rm -rf "$RUN_DIR" ;;
  psql) shift; pg psql -U lumnik -d lumnik -v ON_ERROR_STOP=1 "$@" ;;
  pgdump) pg pg_dump -U lumnik -d lumnik -Fc > "${2:?file}" ;;
  pgrestore) cmd_pgrestore "${2:?file}" ;;
  free-space-mb) docker run --rm alpine df -m / | awk 'NR==2{print $4}' ;;
  db-size-mb) pg psql -U lumnik -d lumnik -tAc "select (pg_database_size('lumnik')/1048576)::int" ;;
  *) echo "usage: stack.sh up|token|down|psql|pgdump FILE|pgrestore FILE|free-space-mb|db-size-mb" >&2; exit 2 ;;
esac
