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
for tool in docker curl python3 openssl awk grep sed cut tr mv cp chmod sleep seq dirname basename; do
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
SEARCH_CLUSTER=0
SEARCH=0
for arg in "$@"; do
  case "$arg" in
    --build) BUILD=1 ;;
    --search) SEARCH=1 ;;
    --search-cluster) SEARCH_CLUSTER=1 ;;
    -h|--help) echo "Usage: $(basename "$0") [--build] [--search | --search-cluster]   (--build compiles the hub from source instead of pulling; --search is closed-edition only and builds in the PostgreSQL Search provider; --search-cluster, also closed-edition only, builds in the OpenSearch one; both imply --build and exclude each other; a running stack keeps the overlays it was started with, TLS façade included)"; exit 0 ;;
    *) echo "unknown argument: $arg" >&2; exit 2 ;;
  esac
done

if [[ "$SEARCH" -eq 1 && "$SEARCH_CLUSTER" -eq 1 ]]; then
  echo "!! --search and --search-cluster are alternatives (two Search providers on one classpath is refused) — pick one" >&2
  exit 2
fi

if [[ "$SEARCH" -eq 1 ]]; then
  if [[ ! -f docker-compose.search.yml ]]; then
    echo "!! --search is not available in this edition" >&2
    exit 2
  fi
  # The published hub image never carries a Search provider: the module is built in, from source.
  BUILD=1
  COMPOSE+=(-f docker-compose.search.yml)
fi

if [[ "$SEARCH_CLUSTER" -eq 1 ]]; then
  if [[ ! -f docker-compose.search-cluster.yml || ! -f deploy/selfhost/search-cluster.sh ]]; then
    echo "!! --search-cluster is not available in this edition" >&2
    exit 2
  fi
  # The published hub image never carries a Search provider: the module is built in, from source.
  BUILD=1
  COMPOSE+=(-f docker-compose.search-cluster.yml)
fi

# 1) ensure .env with secrets (never overwrite an existing one)
if [[ ! -f .env ]]; then
  # shellcheck source=deploy/selfhost/volumes.sh
  source deploy/selfhost/volumes.sh
  refuse_fresh_env_over_volumes
  echo "==> no .env found — generating one with fresh random secrets"
  [[ -f .env.example ]] || { echo "!! .env.example not found — run this from the lumnik repo (the template is at the root)" >&2; exit 1; }
  umask 077  # any file created below (.env.tmp holding the real secrets) is owner-only from the start
  cp .env.example .env
  for key in POSTGRES_PASSWORD KEYCLOAK_ADMIN_PASSWORD LUMNIK_SECRET_MASTER_KEY LUMNIK_CRYPTO_SECRET_KEY LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET HUBADMIN_PASSWORD INTEGRATOR_PASSWORD LUMNIK_APP_DB_PASSWORD LUMNIK_ADMIN_DB_PASSWORD LUMNIK_READONLY_DB_PASSWORD KEYCLOAK_DB_PASSWORD; do
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
  # 2026-08-11 to integrator) and the entity-resolution HMAC secret — grow them in place.
  for key in LUMNIK_ENTITY_RESOLUTION_HMAC_SECRET HUBADMIN_PASSWORD INTEGRATOR_PASSWORD LUMNIK_APP_DB_PASSWORD LUMNIK_ADMIN_DB_PASSWORD LUMNIK_READONLY_DB_PASSWORD KEYCLOAK_DB_PASSWORD; do
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

# A running stack keeps the overlays it was started with (TLS façade, Search, the WOW mount…): the base file
# alone would recreate hub and keycloak without them. Needs .env, so it runs here.
# shellcheck source=deploy/selfhost/overlays.sh
source deploy/selfhost/overlays.sh
keep_running_overlays

if [[ "$SEARCH_CLUSTER" -eq 1 ]]; then
  echo "==> preparing the search cluster (secrets and per-install TLS)"
  # shellcheck source=deploy/selfhost/search-cluster.sh
  source deploy/selfhost/search-cluster.sh
  search_cluster_prepare
fi

# 2) start the stack: PostgreSQL first, so Keycloak finds its database — and, on an install that kept Keycloak in an
# H2 file, its realm moved there (#1997) — before it starts.
if [[ "$BUILD" -eq 0 ]]; then
  echo "==> pulling images"
  "${COMPOSE[@]}" pull || { echo "!! image pull failed — if the ghcr image is private run 'docker login ghcr.io', or use --build to compile from source" >&2; exit 1; }
fi
echo "==> starting PostgreSQL"
"${COMPOSE[@]}" up -d postgres
pg_cid="$("${COMPOSE[@]}" ps -q postgres)"
env_val() { grep "^$1=" .env | cut -d= -f2-; }
# Superuser name: the VOLUME outranks the claim. The name is frozen into PGDATA at first
# init; .env can be silent (older installs) or WRONG (a regenerated .env stamps 'lumnik'
# over a pre-rename volume — deleting .env on a living stack did exactly that). So the
# claimed name is verified against the running database, and discovery runs whenever the
# claim is absent or false. pg_isready ignores auth, so it waits under any name. Over TCP: the server the image runs
# its init scripts on listens on its socket only, and goes away once they are done.
pg_super="$(env_val POSTGRES_USER)" || [ $? -eq 1 ]
for _ in $(seq 1 30); do
  docker exec "$pg_cid" pg_isready -h 127.0.0.1 -U "${pg_super:-lumnik}" -d lumnik >/dev/null 2>&1 && break
  sleep 2
done
super_works() { docker exec "$pg_cid" psql -U "$1" -d lumnik -tAc 'SELECT 1' >/dev/null 2>&1; }
if [[ -z "$pg_super" ]] || ! super_works "$pg_super"; then
  if super_works lumnik; then
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
docker exec -e POSTGRES_USER="$pg_super" "$pg_cid" bash /docker-entrypoint-initdb.d/03-keycloak.sh \
  || { echo "!! could not create Keycloak's database: Keycloak was not started; inspect: docker exec -it $pg_cid psql -U $pg_super lumnik" >&2; exit 1; }
# shellcheck source=deploy/selfhost/volumes.sh
source deploy/selfhost/volumes.sh
# shellcheck source=deploy/selfhost/keycloak-h2.sh
source deploy/selfhost/keycloak-h2.sh
move_keycloak_off_h2
if [[ "$BUILD" -eq 1 ]]; then
  echo "==> building the hub image from source and starting the stack"
  "${COMPOSE[@]}" up -d --build
else
  echo "==> starting the stack"
  "${COMPOSE[@]}" up -d
fi
# The TLS façade reads deploy/tls/Caddyfile at start only, and `up -d` leaves a running Caddy as it is when the file
# alone changed: reload it, without a cut (#1997).
if [[ " ${COMPOSE[*]} " == *"docker-compose.tls.yml "* ]]; then
  if reload_out="$("${COMPOSE[@]}" exec -T caddy caddy reload --config /etc/caddy/Caddyfile 2>&1)"; then
    echo "    TLS façade: Caddyfile reloaded"
  else
    # Caddy keeps serving the file it had: say why, and never restart it on a file it refused.
    echo "$reload_out" | tail -n 3 >&2
    echo "  !! the TLS façade kept its previous Caddyfile: fix deploy/tls/Caddyfile, then re-run this script" >&2
  fi
fi

# Align the live DB roles to .env on every run (P8): first boot's init script already
# used these values (no-op re-set); an older install rotates from the historic
# defaults the moment the keys land in .env. Idempotent — safe every run. Rotation
# is the same gesture: new value in .env, re-run up.sh. SQL rides stdin (never argv);
# values are openssl base64 (no quotes to escape).
echo "==> aligning database role passwords to .env"
{
  printf "ALTER ROLE lumnik_app PASSWORD '%s';\n"      "$(env_val LUMNIK_APP_DB_PASSWORD)"
  printf "ALTER ROLE lumnik_admin PASSWORD '%s';\n"    "$(env_val LUMNIK_ADMIN_DB_PASSWORD)"
  printf "ALTER ROLE lumnik_readonly PASSWORD '%s';\n" "$(env_val LUMNIK_READONLY_DB_PASSWORD)"
} | docker exec -i "$pg_cid" psql -q -v ON_ERROR_STOP=1 -U "$pg_super" -d lumnik \
  && echo "    roles aligned (lumnik_app / lumnik_admin / lumnik_readonly)" \
  || echo "  !! role-password alignment failed — the hub may not reach the DB; inspect: docker exec -it $pg_cid psql -U $pg_super lumnik" >&2

# Grants, schemas and extensions ride every run too (the whole file is idempotent):
# init scripts only run at FIRST volume init, so a volume older than 02-extensions.sql
# never received them — the first one-shot CSV ingest then fails its CREATE SCHEMA with
# "permission denied for database lumnik".
docker exec -i "$pg_cid" psql -q -v ON_ERROR_STOP=1 -U "$pg_super" -d lumnik \
  < infra/postgres/init/02-extensions.sql \
  && echo "    grants/schemas/extensions aligned (02-extensions.sql, idempotent)" \
  || echo "  !! grants alignment failed — ingestion may hit 'permission denied', and Ask stays closed until the readonly role is hardened (docs/security/rls.md#what-the-read-only-role-cannot-call); inspect: docker exec -it $pg_cid psql -U $pg_super lumnik" >&2

# The realm's user profile, token lifespan and password grants on an older install (#1997):
# declare_admin_only_attributes, shorten_access_tokens, close_password_grants.
# shellcheck source=deploy/selfhost/bearer.sh
source deploy/selfhost/bearer.sh
# shellcheck source=deploy/selfhost/realm.sh
source deploy/selfhost/realm.sh

# kc_admin_token [attempts] — a master-realm admin access token for the Keycloak admin API, or nothing (the caller
# warns). Five attempts by default, five seconds apart.
kc_admin_token() {
  local attempts="${1:-5}" admin_user admin_pw tok attempt
  # `|| [ $? -eq 1 ]` on every .env lookup: KEYCLOAK_ADMIN is a documented-optional key
  # that a fresh .env (copied from .env.example) legitimately lacks — under
  # `set -euo pipefail` an unguarded no-match grep kills the whole script before the
  # :-default or the warn below can run (bit on the first real cloud install,
  # 2026-08-18). Only grep's "no match" (rc 1) is tolerated; a real error (rc 2,
  # e.g. unreadable .env) still fails fast instead of masquerading as "missing".
  admin_user="$(grep '^KEYCLOAK_ADMIN=' .env | cut -d= -f2-)" || [ $? -eq 1 ]; admin_user="${admin_user:-admin}"
  admin_pw="$(grep '^KEYCLOAK_ADMIN_PASSWORD=' .env | cut -d= -f2-)" || [ $? -eq 1 ]
  # Password rides stdin (--data-urlencode password@-), never argv — no `ps` leak.
  # Retried: the hub can be healthy while Keycloak is still finishing its first
  # realm import (seen on slow first boots) — give it up to ~25s before warning.
  for attempt in $(seq 1 "$attempts"); do
    # --max-time bounds each attempt: a hung connect must not turn the ~25s retry
    # budget into an unbounded stall.
    tok="$(printf '%s' "$admin_pw" | curl -fsS --connect-timeout 3 --max-time 10 -X POST "http://localhost:8180/realms/master/protocol/openid-connect/token" \
        -d grant_type=password -d client_id=admin-cli \
        --data-urlencode "username=$admin_user" --data-urlencode password@- 2>/dev/null \
      | python3 -c 'import sys,json;print(json.load(sys.stdin).get("access_token",""))' 2>/dev/null)" || true
    [[ -n "${tok:-}" ]] && break
    [[ "$attempt" -lt "$attempts" ]] && sleep 5
  done
  printf '%s' "${tok:-}"
}

# with_kc_token <what stays undone> <command> [args…] — runs the command with a fresh admin token as its first argument
# (an admin-cli token lives 60 s, and each step makes several calls), or warns that the step stays undone. Only the
# first step waits for Keycloak; the next ones try once.
with_kc_token() {
  local undone="$1" tok
  shift
  tok="$(kc_admin_token "${KC_TOKEN_ATTEMPTS:-5}")"
  KC_TOKEN_ATTEMPTS=1
  if [[ -z "$tok" ]]; then
    echo "  !! could not obtain a Keycloak admin token (check KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD in .env) — $undone"
    return 0
  fi
  "$1" "$tok" "${@:2}"
}

# Neither hubadmin nor integrator ships in the realm WITH a credential — C1 posture
# (2026-07-25), extended 2026-08-11 to integrator (RealmAudienceContractTest): a known
# password must never survive an install, and infra/keycloak/*.json is on the
# open-edition allowlist so a static one would become world-readable at the flip. Set
# each one per install from .env via the Keycloak admin API. Failure is fail-closed
# (the user simply cannot log in) — warn, don't abort a healthy stack.
#   $1 = a Keycloak admin access token (kc_admin_token)
#   $2 = space-separated candidate usernames (aliases for the same logical account —
#        e.g. the pre-rename 'gardenadmin' — the loop heals older realm imports)
#   $3 = .env key holding the password to set
set_realm_user_password() {
  local tok="$1" candidates="$2" pw_key="$3"
  local pw uid name
  pw="$(grep "^${pw_key}=" .env | cut -d= -f2-)" || [ $? -eq 1 ]
  if [[ -z "$pw" ]]; then
    echo "  !! $pw_key missing in .env — $candidates stays locked (no password)"; return 0
  fi
  # Realms imported before the rename still carry the legacy 'gardenadmin' user (a realm
  # import is one-shot) — heal it under whichever name this install has.
  for name in $candidates; do
    # Non-fatal like the token call above — a lookup failure must keep the
    # warn-don't-abort contract (set -e would otherwise kill a healthy stack).
    uid="$(curl -fsS --connect-timeout 3 --max-time 10 -H @<(bearer "$tok") \
        "http://localhost:8180/admin/realms/lumnik/users?username=$name&exact=true" 2>/dev/null \
      | python3 -c 'import sys,json;u=json.load(sys.stdin);print(u[0]["id"] if u else "")' 2>/dev/null)" || true
    [[ -n "$uid" ]] && break
  done
  if [[ -z "$uid" ]]; then
    echo "  !! $candidates not found in the lumnik realm — skipped"; return 0
  fi
  PW="$pw" python3 -c 'import json,os;print(json.dumps({"type":"password","temporary":False,"value":os.environ["PW"]}))' \
    | curl -fsS --connect-timeout 3 --max-time 10 -X PUT "http://localhost:8180/admin/realms/lumnik/users/$uid/reset-password" \
        -H @<(bearer "$tok") -H "Content-Type: application/json" -d @- \
    && echo "  $name: per-install password set ($pw_key in .env)" \
    || echo "  !! $name password set failed — stays locked (no password). If Keycloak refused it, mind the realm's password policy (a new install asks for 12 characters at least, not the username nor the email: $pw_key in .env)"
}

# 3) wait for the hub to be healthy (Flyway runs at boot)
echo "==> waiting for the hub to become healthy (this can take a minute on first boot)"
hub_cid="$("${COMPOSE[@]}" ps -q hub)"
for _ in $(seq 1 60); do
  status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$hub_cid" 2>/dev/null || echo none)"
  if [[ "$status" == "healthy" ]]; then
    echo "==> hub is healthy"
    with_kc_token "hubadmin stays locked (no password)" set_realm_user_password "hubadmin gardenadmin" HUBADMIN_PASSWORD
    with_kc_token "integrator stays locked (no password)" set_realm_user_password "integrator" INTEGRATOR_PASSWORD
    with_kc_token "tenant_id unchecked (docs/operations/upgrade.md#the-bundled-keycloak-realm-is-stricter)" \
      declare_admin_only_attributes http://localhost:8180
    with_kc_token "access tokens unchecked (docs/operations/upgrade.md#an-access-token-lives-5-minutes)" \
      shorten_access_tokens http://localhost:8180
    with_kc_token "password grants unchecked (docs/operations/upgrade.md#the-public-clients-take-no-password)" \
      close_password_grants http://localhost:8180
    echo ""
    echo "  Hub:       http://localhost:8080   (health: ./deploy/selfhost/health.sh)"
    echo "  Keycloak:  http://localhost:8180/realms/lumnik"
    echo "  Next:      point lm at the hub — server http://localhost:8080, OIDC http://localhost:8180/realms/lumnik"
    exit 0
  fi
  sleep 3
done
echo "!! hub did not become healthy in time — inspect with: ${COMPOSE[*]} logs hub" >&2
exit 1
