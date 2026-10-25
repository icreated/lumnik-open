#!/usr/bin/env bash
# Executable specification of deploy/selfhost/volumes.sh (#1579): up.sh refuses to generate a fresh .env while the
# compose project's volumes survive from an earlier install — their passwords were frozen by the .env that created
# them, and new secrets would leave Keycloak's admin and the realm accounts locked behind a hint about .env.
#
# Hermetic: `docker` is a stub on PATH answering `volume ls` for one project; each case runs
# refuse_fresh_env_over_volumes from a throwaway directory. Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/selfhost-fresh-env.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin" "$TMP/My.Lumnik-Open"
cat > "$TMP/bin/docker" <<'STUB'
#!/bin/sh
[ -n "${STUB_DOWN:-}" ] && { echo "Cannot connect to the Docker daemon" >&2; exit 1; }
case "$*" in
  "volume ls -q --filter label=com.docker.compose.project=$STUB_PROJECT") printf '%b' "${STUB_VOLS:-}" ;;
  "volume ls -q --filter label=com.docker.compose.project="*) ;;
  *) echo "unexpected docker call: $*" >&2; exit 97 ;;
esac
STUB
chmod +x "$TMP/bin/docker"

fail=0
# check <name> <expected rc> <expected fragment of the output ("" = no output)> [VAR=value ...]
check() {
  local name="$1" want_rc="$2" want="$3"; shift 3
  local out rc=0
  out="$(cd "$TMP/My.Lumnik-Open" && env -u COMPOSE_PROJECT_NAME PATH="$TMP/bin:$PATH" STUB_PROJECT=none "$@" bash -c '
    set -euo pipefail
    ROOT="$PWD"
    source "'"$REPO"'/deploy/selfhost/volumes.sh"
    refuse_fresh_env_over_volumes
  ' 2>&1)" || rc=$?
  if [ "$rc" != "$want_rc" ] || { [ -z "$want" ] && [ -n "$out" ]; } || { [ -n "$want" ] && ! echo "$out" | grep -qF -- "$want"; }; then
    echo "FAIL $name: rc=$rc (want $want_rc)"; echo "    got:  $out"; echo "    want: $want"; fail=1
  else
    echo "ok   $name"
  fi
}

VOLS='mylumnik-open_selfhost_kc\nmylumnik-open_selfhost_pg\n'

check "no volumes: a fresh .env goes ahead"        0 ""
check "another project's volumes: goes ahead"      0 "" STUB_PROJECT=lumnik STUB_VOLS='lumnik_selfhost_pg\n'
check "surviving volumes: refused"                 1 "!! no .env, but this stack's volumes survive from an earlier install" \
  STUB_PROJECT=mylumnik-open STUB_VOLS="$VOLS"
check "the refusal names each volume"              1 "mylumnik-open_selfhost_pg" STUB_PROJECT=mylumnik-open STUB_VOLS="$VOLS"
check "the refusal offers the restore"             1 "put back the .env that created them" STUB_PROJECT=mylumnik-open STUB_VOLS="$VOLS"
check "the refusal offers the removal, by project" 1 "docker compose -p mylumnik-open -f docker-compose.selfhost.yml down -v" \
  STUB_PROJECT=mylumnik-open STUB_VOLS="$VOLS"
check "COMPOSE_PROJECT_NAME names the project"     1 "docker compose -p other -f docker-compose.selfhost.yml down -v" \
  COMPOSE_PROJECT_NAME=other STUB_PROJECT=other STUB_VOLS='other_selfhost_pg\n'
check "docker unreachable: refused, nothing written" 1 "!! could not list Docker volumes" STUB_DOWN=1
check "a volume outside the stack is ignored"      0 "" STUB_PROJECT=mylumnik-open STUB_VOLS='mylumnik-open_cache\n'

exit "$fail"
