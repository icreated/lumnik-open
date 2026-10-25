#!/usr/bin/env bash
# Executable specification of deploy/selfhost/overlays.sh: up.sh re-applies the overlays the running hub was
# created with (TLS façade, Search, the WOW mount, any other -f file), read from the hub container's compose label,
# instead of silently recreating hub/keycloak from the base file alone.
#
# Hermetic: `docker` is a stub on PATH that prints canned inspect/ps output; each case runs keep_running_overlays in
# a throwaway directory and compares the resulting state. Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/selfhost-overlays.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/root/docs/demo/wow-customer" "$TMP/root/deploy/selfhost"
cat > "$TMP/bin/docker" <<'STUB'
#!/bin/sh
case "$*" in
  "compose -f docker-compose.selfhost.yml ps -aq hub") if [ -n "${STUB_HUB:-}" ]; then echo "$STUB_HUB"; fi ;;
  *config_files*)       echo "${STUB_FILES:-}" ;;
  *Config.Env*)         printf '%b' "${STUB_ENV:-}" ;;
  *'compose.project"'*) echo lumnik ;;
  "ps -aq --filter label=com.docker.compose.project=lumnik --filter label=com.docker.compose.service=caddy")
                        if [ -n "${STUB_CADDY:-}" ]; then echo "$STUB_CADDY"; fi ;;
  *PortBindings*)       echo "${STUB_BIND:-}" ;;
  *) echo "unexpected docker call: $*" >&2; exit 97 ;;
esac
STUB
chmod +x "$TMP/bin/docker"
cd "$TMP/root"
R="$PWD"   # the recorded paths are absolute, as compose writes them
touch docker-compose.selfhost.yml docker-compose.tls.yml docker-compose.search.yml docker-compose.search-cluster.yml \
      docs/demo/wow-customer/docker-compose.wow.yml deploy/selfhost/search-cluster.sh

fail=0
# check <name> <expected rc> <expected state (or a refusal fragment when rc != 0)> [VAR=value ...]
# Caller flags default to none; ENVFILE is written to .env.
check() {
  local name="$1" want_rc="$2" want="$3"; shift 3
  local out rc=0
  out="$(env -u LUMNIK_TLS_HOST -u LUMNIK_TLS_BIND PATH="$TMP/bin:$PATH" SEARCH=0 SEARCH_CLUSTER=0 BUILD=0 "$@" bash -c '
    set -euo pipefail
    printf "%b" "${ENVFILE:-}" > .env
    if [ -n "${NO_CLUSTER_SCRIPT:-}" ]; then mv deploy/selfhost/search-cluster.sh .cluster.bak; fi
    source "'"$REPO"'/deploy/selfhost/overlays.sh"
    COMPOSE=(docker compose -f docker-compose.selfhost.yml)
    # A refusal exits from inside the function, while its redirections still hold (bash 5 keeps them
    # for the EXIT trap, bash 3.2 does not): the trap writes to fd 3, the stdout saved before the call.
    exec 3>&1
    trap '"'"'rc=$?; [ "$rc" -ne 0 ] && cat .err >&3; exit "$rc"'"'"' EXIT
    keep_running_overlays >/dev/null 2>.err
    echo "${COMPOSE[*]:4} | search=$SEARCH cluster=$SEARCH_CLUSTER build=$BUILD | host=${LUMNIK_TLS_HOST:-} bind=${LUMNIK_TLS_BIND:-}"
  ' 2>&1)" || rc=$?
  if [ "$rc" != "$want_rc" ] || { [ "$rc" = 0 ] && [ "$out" != "$want" ]; } || { [ "$rc" != 0 ] && ! echo "$out" | grep -qF "$want"; }; then
    echo "FAIL $name: rc=$rc (want $want_rc)"; echo "    got:  $out"; echo "    want: $want"; fail=1
  else
    echo "ok   $name"
  fi
}

ISSUER='PATH=/usr/bin\nQUARKUS_OIDC_TOKEN_ISSUER=https://erp.example.com/realms/lumnik\n'

check "no hub container: nothing kept"      0 " | search=0 cluster=0 build=0 | host= bind="
check "base file only: nothing kept"        0 " | search=0 cluster=0 build=0 | host= bind=" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml"
check "TLS kept, host from the shell"       0 "-f docker-compose.tls.yml | search=0 cluster=0 build=0 | host=lan.example bind=" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.tls.yml" LUMNIK_TLS_HOST=lan.example STUB_ENV="$ISSUER"
check "TLS kept, host read back from hub"   0 "-f docker-compose.tls.yml | search=0 cluster=0 build=0 | host=erp.example.com bind=" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.tls.yml" STUB_ENV="$ISSUER"
check "TLS kept, host in .env wins"         0 "-f docker-compose.tls.yml | search=0 cluster=0 build=0 | host= bind=" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.tls.yml" STUB_ENV="$ISSUER" ENVFILE='LUMNIK_TLS_HOST=from-env-file\n'
check "TLS kept, host nowhere: refused"     2 "LUMNIK_TLS_HOST=<the façade's address> ./deploy/selfhost/up.sh" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.tls.yml" STUB_ENV='PATH=/usr/bin\n'
check "TLS kept, loopback bind read back"   0 "-f docker-compose.tls.yml | search=0 cluster=0 build=0 | host=erp.example.com bind=127.0.0.1" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.tls.yml" STUB_ENV="$ISSUER" STUB_CADDY=c1 STUB_BIND=127.0.0.1
check "TLS kept, default bind left default" 0 "-f docker-compose.tls.yml | search=0 cluster=0 build=0 | host=erp.example.com bind=" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.tls.yml" STUB_ENV="$ISSUER" STUB_CADDY=c1 STUB_BIND=0.0.0.0
check "TLS kept, bind from the shell wins"  0 "-f docker-compose.tls.yml | search=0 cluster=0 build=0 | host=erp.example.com bind=10.0.0.5" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.tls.yml" STUB_ENV="$ISSUER" STUB_CADDY=c1 STUB_BIND=127.0.0.1 LUMNIK_TLS_BIND=10.0.0.5
check "Search kept: provider rebuilt"       0 "-f docker-compose.search.yml | search=1 cluster=0 build=1 | host= bind=" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.search.yml"
check "Search cluster kept"                 0 "-f docker-compose.search-cluster.yml | search=0 cluster=1 build=1 | host= bind=" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.search-cluster.yml"
check "caller's provider flag wins"         0 " | search=1 cluster=0 build=0 | host= bind=" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.search-cluster.yml" SEARCH=1
check "WOW mount kept, path relative"       0 "-f docs/demo/wow-customer/docker-compose.wow.yml | search=0 cluster=0 build=0 | host= bind=" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docs/demo/wow-customer/docker-compose.wow.yml"
check "recorded order kept, TLS last"       0 "-f docker-compose.search.yml -f docs/demo/wow-customer/docker-compose.wow.yml -f docker-compose.tls.yml | search=1 cluster=0 build=1 | host=erp.example.com bind=" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.search.yml,$R/docs/demo/wow-customer/docker-compose.wow.yml,$R/docker-compose.tls.yml" STUB_ENV="$ISSUER"
check "Search cluster, edition lacks it"     2 "deploy/selfhost/search-cluster.sh is not in this edition" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.search-cluster.yml" NO_CLUSTER_SCRIPT=1
mv .cluster.bak deploy/selfhost/search-cluster.sh
check "empty label: nothing kept"           0 " | search=0 cluster=0 build=0 | host= bind=" \
  STUB_HUB=h1 STUB_FILES=""
check "recorded file gone: refused"         2 "this stack was started with debug.yml, which no longer exists" \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/debug.yml"

# The rebuild a recorded Search provider forces is said, not silent.
cd "$TMP/root"
out="$(env -u LUMNIK_TLS_HOST -u LUMNIK_TLS_BIND PATH="$TMP/bin:$PATH" SEARCH=0 SEARCH_CLUSTER=0 BUILD=0 \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.search.yml" bash -c '
    set -euo pipefail; : > .env
    source "'"$REPO"'/deploy/selfhost/overlays.sh"
    COMPOSE=(docker compose -f docker-compose.selfhost.yml)
    keep_running_overlays' 2>&1)"
if echo "$out" | grep -qF "the hub is rebuilt from source"; then
  echo "ok   a recorded Search provider says the hub is rebuilt from source"
else
  echo "FAIL a recorded Search provider rebuilds without saying so"; echo "    got: $out"; fail=1
fi
# Switching provider says the old one's containers stay.
out="$(env -u LUMNIK_TLS_HOST -u LUMNIK_TLS_BIND PATH="$TMP/bin:$PATH" SEARCH=1 SEARCH_CLUSTER=0 BUILD=0 \
  STUB_HUB=h1 STUB_FILES="$R/docker-compose.selfhost.yml,$R/docker-compose.search-cluster.yml" bash -c '
    set -euo pipefail; : > .env
    source "'"$REPO"'/deploy/selfhost/overlays.sh"
    COMPOSE=(docker compose -f docker-compose.selfhost.yml)
    keep_running_overlays' 2>&1)"
if echo "$out" | grep -qF "Its containers keep running until ./deploy/selfhost/down.sh"; then
  echo "ok   switching provider names the containers left running"
else
  echo "FAIL switching provider is silent about the old provider's containers"; echo "    got: $out"; fail=1
fi

# A function nobody calls proves nothing: up.sh must source it and call it before its first `up -d`.
cd "$REPO"
call_line="$(grep -n '^keep_running_overlays$' deploy/selfhost/up.sh | head -1 | cut -d: -f1)" || true
up_line="$(grep -n 'up -d' deploy/selfhost/up.sh | head -1 | cut -d: -f1)" || true
if grep -q '^source deploy/selfhost/overlays.sh$' deploy/selfhost/up.sh && [ -n "$call_line" ] && [ "$call_line" -lt "$up_line" ]; then
  echo "ok   up.sh keeps the overlays before it recreates anything"
else
  echo "FAIL up.sh does not source overlays.sh and call keep_running_overlays before its first 'up -d'"; fail=1
fi

exit "$fail"
