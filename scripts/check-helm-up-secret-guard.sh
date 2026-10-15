#!/usr/bin/env bash
# Executable specification of deploy/helm/up.sh's re-run guard (#764): the script is a from-zero kind
# installer that draws fresh secrets on every run (DB role, Keycloak, master/crypto keys, HMAC), so on a
# cluster that already holds <release>-secret it must stop BEFORE any mutation unless
# LUMNIK_ALLOW_SECRET_REGENERATION is exactly "true". A kubectl failure (cluster unreachable, RBAC) is not
# "secret absent": it refuses too.
#
# Hermetic: kind/kubectl/docker/helm/... are stubs on PATH. The docker stub is the mutation boundary — the
# first thing up.sh does after the guard is `docker build`; the stub exits 99, so "99" reads "the guard let it
# through" and "1" reads "the guard refused". Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."

BIN="$(mktemp -d "${TMPDIR:-/tmp}/helm-up-guard.XXXXXX")"
trap 'rm -rf "$BIN"' EXIT
for t in helm openssl python3 kubeseal; do printf '#!/bin/sh\nexit 0\n' > "$BIN/$t"; done
cat > "$BIN/docker" <<'STUB'
#!/bin/sh
echo "docker $*" >> "$STUB_LOG"
exit 99
STUB
cat > "$BIN/kind" <<'STUB'
#!/bin/sh
[ "$1 $2" = "get clusters" ] && [ "${STUB_CLUSTER:-yes}" = yes ] && echo lumnik
exit 0
STUB
cat > "$BIN/kubectl" <<'STUB'
#!/bin/sh
echo "kubectl $*" >> "$STUB_LOG"
case "$*" in *"get secret lumnik-secret"*)
  [ "${STUB_SECRET:-yes}" = error ] && { echo "Unable to connect to the server" >&2; exit 1; }
  [ "${STUB_SECRET:-yes}" = yes ] && echo secret/lumnik-secret ;; esac
exit 0
STUB
chmod +x "$BIN"/*

fail=0
# run <name> <expected-rc> <expect-docker-called yes|no> [VAR=value ...]
run() {
  local name="$1" want_rc="$2" want_docker="$3"; shift 3
  export STUB_LOG="$BIN/log"; : > "$STUB_LOG"
  local out rc=0
  out="$(env PATH="$BIN:$PATH" "$@" bash deploy/helm/up.sh 2>&1)" || rc=$?
  local got_docker=no; grep -q '^docker ' "$STUB_LOG" && got_docker=yes
  if [ "$rc" != "$want_rc" ] || [ "$got_docker" != "$want_docker" ]; then
    echo "FAIL $name: rc=$rc (want $want_rc), docker called=$got_docker (want $want_docker)"; echo "$out" | sed 's/^/    /'; fail=1
  else
    echo "ok   $name"
  fi
  if [ "$want_rc" = 1 ] && ! echo "$out" | grep -q 'LUMNIK_ALLOW_SECRET_REGENERATION'; then
    echo "FAIL $name: refusal does not name the escape hatch"; fail=1
  fi
}

run "existing secret refuses"              1  no  STUB_SECRET=yes
run "explicit true bypasses"               99 yes STUB_SECRET=yes LUMNIK_ALLOW_SECRET_REGENERATION=true
run "1 is not true"                        1  no  STUB_SECRET=yes LUMNIK_ALLOW_SECRET_REGENERATION=1
run "TRUE is not true"                     1  no  STUB_SECRET=yes LUMNIK_ALLOW_SECRET_REGENERATION=TRUE
run "false refuses"                        1  no  STUB_SECRET=yes LUMNIK_ALLOW_SECRET_REGENERATION=false
run "absent secret proceeds"               99 yes STUB_SECRET=no
run "absent cluster proceeds"              99 yes STUB_CLUSTER=no STUB_SECRET=yes
run "kubectl error refuses"                1  no  STUB_SECRET=error
run "kubectl error + explicit true"        99 yes STUB_SECRET=error LUMNIK_ALLOW_SECRET_REGENERATION=true
run "sealed mode refuses too"              1  no  STUB_SECRET=yes LUMNIK_SECRETS=sealed

exit "$fail"
