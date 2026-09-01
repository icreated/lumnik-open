#!/usr/bin/env bash
# Stand up the self-host lumnik stack (hub + postgres + keycloak) with one command.
# On first run, generates .env with strong random secrets; pulls the published image
# (or --build compiles from source); waits for the hub to be healthy; prints access URLs.
set -euo pipefail

# repo root = two levels up from deploy/selfhost/up.sh
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$ROOT"

# Preflight: every tool this script needs, checked NOW — a missing one must fail
# loud here, not half-succeed at the end. (First real Windows install: a minimal
# WSL Ubuntu lacked curl/python3; the stack came up healthy but both realm
# accounts stayed locked, behind a warn nobody read.)
missing=""
# The full inventory of external commands this script calls (grep-audited) — the
# claim "checked up front" must be true, not approximate.
for tool in docker curl python3 openssl awk grep sed cut mv cp chmod sleep seq dirname basename; do
  command -v "$tool" >/dev/null 2>&1 || missing="$missing $tool"
done
if [[ -n "$missing" ]]; then
  {
    echo "!! up.sh needs these tools, not found on this machine:$missing"
    echo "   Debian/Ubuntu (incl. WSL): sudo apt update && sudo apt install -y curl python3 openssl gawk"
    echo "   Docker:                    https://docs.docker.com/engine/install/  (on Windows: Docker Desktop, with WSL integration enabled)"
  } >&2
  exit 1
fi
if ! docker compose version >/dev/null 2>&1; then
  echo "!! Docker is installed but 'docker compose' (v2 plugin) is not available — install the compose plugin, or on Windows enable Docker Desktop's WSL integration" >&2
  exit 1
fi

COMPOSE=(docker compose -f docker-compose.selfhost.yml)
BUILD=0
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    -h|--help) echo "Usage: $(basename "$0") [--build]   (--build compiles the hub from source instead of pulling)"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

# 1) ensure .env with secrets (never overwrite an existing one)
if [[ ! -f .env ]]; then
  echo "==> no .env found — generating one with fresh random secrets"
  [[ -f .env.example ]] || { echo "!! .env.example not found — run this from the lumnik repo (the template is at the root)" >&2; exit 1; }
  umask 077  # any file created below (.env.tmp holding the real secrets) is owner-only from the start
  cp .env.example .env
  for key in POSTGRES_PASSWORD KEYCLOAK_ADMIN_PASSWORD LUMNIK_SECRET_MASTER_KEY LUMNIK_CRYPTO_SECRET_KEY HUBADMIN_PASSWORD INTEGRATOR_PASSWORD LUMNIK_APP_DB_PASSWORD LUMNIK_ADMIN_DB_PASSWORD LUMNIK_READONLY_DB_PASSWORD; do
    secret="$(openssl rand -base64 32)"
    # fill the empty `KEY=` line; awk prints the secret literally (no shell/sed metachar issues)
    awk -v k="$key" -v v="$secret" 'BEGIN{FS=OFS="="} $1==k && $2=="" {print k"="v; next} {print}' .env > .env.tmp
    mv .env.tmp .env
  done
  # Stamp the bootstrap superuser explicitly: the volume freezes this name at first
  # init, and a .env that SAYS which name it froze never needs the probe below.
  printf 'POSTGRES_USER=lumnik\n' >> .env
  chmod 600 .env  # owner-only — these are secrets (cp + redirect would otherwise leave it world-readable)
  echo "    wrote .env (gitignored, mode 600). BACK IT UP — losing LUMNIK_SECRET_MASTER_KEY makes stored secrets unrecoverable."
else
  echo "==> using existing .env"
  # Older .env files predate the per-install hubadmin/integrator passwords (C1, extended
  # 2026-08-11 to integrator) — grow them in place.
  for key in HUBADMIN_PASSWORD INTEGRATOR_PASSWORD LUMNIK_APP_DB_PASSWORD LUMNIK_ADMIN_DB_PASSWORD LUMNIK_READONLY_DB_PASSWORD; do
    if ! grep -q "^${key}=..*" .env; then
      umask 077
      grep -q "^${key}=" .env || printf '%s=\n' "$key" >> .env
      secret="$(openssl rand -base64 32)"
      awk -v k="$key" -v v="$secret" 'BEGIN{FS=OFS="="} $1==k && $2=="" {print k"="v; next} {print}' .env > .env.tmp
      mv .env.tmp .env && chmod 600 .env
      echo "    added $key to .env"
    fi
  done
fi

# 2) start the stack
if [[ "$BUILD" -eq 1 ]]; then
  echo "==> building the hub image from source and starting the stack"
  "${COMPOSE[@]}" up -d --build
else
  echo "==> pulling images and starting the stack"
  "${COMPOSE[@]}" pull || { echo "!! image pull failed — if the ghcr image is private run 'docker login ghcr.io', or use --build to compile from source" >&2; exit 1; }
  "${COMPOSE[@]}" up -d
fi

# Align the live DB roles to .env on every run (P8): first boot's init script already
# used these values (no-op re-set); an older install rotates from the historic
# defaults the moment the keys land in .env. Idempotent — safe every run. Rotation
# is the same gesture: new value in .env, re-run up.sh. SQL rides stdin (never argv);
# values are openssl base64 (no quotes to escape).
echo "==> aligning database role passwords to .env"
pg_cid="$("${COMPOSE[@]}" ps -q postgres)"
env_val() { grep "^$1=" .env | cut -d= -f2-; }
# Superuser name: .env is believed when it speaks; when it is silent, ASK THE VOLUME —
# the name is frozen into PGDATA at first init, and guessing from the age of .env once
# pinned 'adempiere' onto fresh 'lumnik' volumes (an install's own second run broke it).
# pg_isready ignores auth, so it waits for the server regardless of the role name.
pg_super="$(env_val POSTGRES_USER)" || [ $? -eq 1 ]
for _ in $(seq 1 30); do
  docker exec "$pg_cid" pg_isready -U "${pg_super:-lumnik}" -d lumnik >/dev/null 2>&1 && break
  sleep 2
done
if [[ -z "$pg_super" ]]; then
  if docker exec "$pg_cid" psql -U lumnik -d lumnik -tAc 'SELECT 1' >/dev/null 2>&1; then
    pg_super=lumnik
  else
    pg_super=adempiere   # pre-rename volume — the only other name ever bootstrapped
  fi
  # Persist the discovery so compose interpolation and every later run agree with the volume.
  if grep -q '^POSTGRES_USER=' .env; then
    awk -v v="$pg_super" 'BEGIN{FS=OFS="="} $1=="POSTGRES_USER" {print "POSTGRES_USER="v; next} {print}' .env > .env.tmp
    mv .env.tmp .env
  else
    printf 'POSTGRES_USER=%s\n' "$pg_super" >> .env
  fi
  chmod 600 .env
  echo "    discovered bootstrap superuser '$pg_super' from the volume; recorded in .env"
fi
{
  printf "ALTER ROLE lumnik_app PASSWORD '%s';\n"      "$(env_val LUMNIK_APP_DB_PASSWORD)"
  printf "ALTER ROLE lumnik_admin PASSWORD '%s';\n"    "$(env_val LUMNIK_ADMIN_DB_PASSWORD)"
  printf "ALTER ROLE lumnik_readonly PASSWORD '%s';\n" "$(env_val LUMNIK_READONLY_DB_PASSWORD)"
} | docker exec -i "$pg_cid" psql -q -v ON_ERROR_STOP=1 -U "$pg_super" -d lumnik \
  && echo "    roles aligned (lumnik_app / lumnik_admin / lumnik_readonly)" \
  || echo "  !! role-password alignment failed — the hub may not reach the DB; inspect: docker exec -it $pg_cid psql -U $pg_super lumnik" >&2

# Neither hubadmin nor integrator ships in the realm WITH a credential — C1 posture
# (2026-07-25), extended 2026-08-11 to integrator (RealmAudienceContractTest): a known
# password must never survive an install, and infra/keycloak/*.json is on the
# open-edition allowlist so a static one would become world-readable at the flip. Set
# each one per install from .env via the Keycloak admin API. Failure is fail-closed
# (the user simply cannot log in) — warn, don't abort a healthy stack.
#   $1 = space-separated candidate usernames (aliases for the same logical account —
#        e.g. the pre-rename 'gardenadmin' — the loop heals older realm imports)
#   $2 = .env key holding the password to set
set_realm_user_password() {
  local candidates="$1" pw_key="$2"
  local admin_user admin_pw pw tok uid name
  # `|| [ $? -eq 1 ]` on every .env lookup: KEYCLOAK_ADMIN is a documented-optional key
  # that a fresh .env (copied from .env.example) legitimately lacks — under
  # `set -euo pipefail` an unguarded no-match grep kills the whole script before the
  # :-default or the warn below can run (bit on the first real cloud install,
  # 2026-08-18). Only grep's "no match" (rc 1) is tolerated; a real error (rc 2,
  # e.g. unreadable .env) still fails fast instead of masquerading as "missing".
  admin_user="$(grep '^KEYCLOAK_ADMIN=' .env | cut -d= -f2-)" || [ $? -eq 1 ]; admin_user="${admin_user:-admin}"
  admin_pw="$(grep '^KEYCLOAK_ADMIN_PASSWORD=' .env | cut -d= -f2-)" || [ $? -eq 1 ]
  pw="$(grep "^${pw_key}=" .env | cut -d= -f2-)" || [ $? -eq 1 ]
  if [[ -z "$pw" ]]; then
    echo "  !! $pw_key missing in .env — $candidates stays locked (no password)"; return 0
  fi
  # Password rides stdin (--data-urlencode password@-), never argv — no `ps` leak.
  # Retried: the hub can be healthy while Keycloak is still finishing its first
  # realm import (seen on slow first boots) — give it up to ~25s before warning.
  local attempt
  for attempt in 1 2 3 4 5; do
    # --max-time bounds each attempt: a hung connect must not turn the ~25s retry
    # budget into an unbounded stall.
    tok="$(printf '%s' "$admin_pw" | curl -fsS --connect-timeout 3 --max-time 10 -X POST "http://localhost:8180/realms/master/protocol/openid-connect/token" \
        -d grant_type=password -d client_id=admin-cli \
        --data-urlencode "username=$admin_user" --data-urlencode password@- 2>/dev/null \
      | python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null)" || true
    [[ -n "${tok:-}" ]] && break
    [[ "$attempt" -lt 5 ]] && sleep 5
  done
  if [[ -z "${tok:-}" ]]; then
    echo "  !! could not obtain a Keycloak admin token (check KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD in .env) — $candidates stays locked (no password)"; return 0
  fi
  # Realms imported before the rename still carry the legacy 'gardenadmin' user (a realm
  # import is one-shot) — heal it under whichever name this install has.
  for name in $candidates; do
    # Non-fatal like the token call above — a lookup failure must keep the
    # warn-don't-abort contract (set -e would otherwise kill a healthy stack).
    uid="$(curl -fsS --connect-timeout 3 --max-time 10 -H "Authorization: Bearer $tok" \
        "http://localhost:8180/admin/realms/lumnik/users?username=$name&exact=true" 2>/dev/null \
      | python3 -c 'import sys,json;u=json.load(sys.stdin);print(u[0]["id"] if u else "")' 2>/dev/null)" || true
    [[ -n "$uid" ]] && break
  done
  if [[ -z "$uid" ]]; then
    echo "  !! $candidates not found in the lumnik realm — skipped"; return 0
  fi
  PW="$pw" python3 -c 'import json,os;print(json.dumps({"type":"password","temporary":False,"value":os.environ["PW"]}))' \
    | curl -fsS --connect-timeout 3 --max-time 10 -X PUT "http://localhost:8180/admin/realms/lumnik/users/$uid/reset-password" \
        -H "Authorization: Bearer $tok" -H "Content-Type: application/json" -d @- \
    && echo "  $name: per-install password set ($pw_key in .env)" \
    || echo "  !! $name password set failed — stays locked (no password)"
}

# 3) wait for the hub to be healthy (Flyway runs at boot)
echo "==> waiting for the hub to become healthy (this can take a minute on first boot)"
hub_cid="$("${COMPOSE[@]}" ps -q hub)"
for _ in $(seq 1 60); do
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$hub_cid" 2>/dev/null || echo none)"
  if [[ "$status" == "healthy" ]]; then
    echo "==> hub is healthy"
    set_realm_user_password "hubadmin gardenadmin" HUBADMIN_PASSWORD
    set_realm_user_password "integrator" INTEGRATOR_PASSWORD
    echo ""
    echo "  Hub:       http://localhost:8080   (health: /q/health)"
    echo "  Keycloak:  http://localhost:8180/realms/lumnik"
    echo "  Next:      point lm at the hub — server http://localhost:8080, OIDC http://localhost:8180/realms/lumnik"
    exit 0
  fi
  sleep 3
done
echo "!! hub did not become healthy in time — inspect with: ${COMPOSE[*]} logs hub" >&2
exit 1
