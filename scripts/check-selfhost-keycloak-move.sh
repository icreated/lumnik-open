#!/usr/bin/env bash
# Executable specification of deploy/selfhost/keycloak-h2.sh's move_keycloak_off_h2 (#1997): an install whose Keycloak
# kept its realm in an H2 file (the volume <project>_selfhost_kc) gets it moved to PostgreSQL before the new Keycloak
# starts, and the volume marked; an install without that file, or whose file moved already, is left alone; a failed
# export or import stops up.sh before Keycloak starts, unmarked.
#
# Hermetic: `docker` is a stub on PATH that logs each call to $STUB_DIR/calls. STUB_VOL=1: the volume exists.
# STUB_H2=none|moved|new: the volume holds no H2 file, the file already moved, or one to move.
# STUB_EXPORT_FAIL / STUB_IMPORT_FAIL: that step fails. Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/selfhost-keycloak-move.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
cat > "$TMP/bin/docker" <<'STUB'
#!/bin/sh
echo "$*" >> "$STUB_DIR/calls"
case "$*" in
  "volume inspect "*) [ -n "${STUB_VOL:-}" ] || exit 1 ;;
  *"--entrypoint /bin/sh"*"exit 3"*)
    case "${STUB_H2:-none}" in none) exit 3 ;; moved) exit 4 ;; new) exit 0 ;; esac ;;
  *"--entrypoint /bin/sh"*"> /d/moved-to-postgresql"*) ;;
  "compose "*" stop keycloak") ;;
  "run --rm --network none "*" export "*) [ -z "${STUB_EXPORT_FAIL:-}" ] || exit 1 ;;
  "compose "*" run --rm --no-deps "*" import "*) [ -z "${STUB_IMPORT_FAIL:-}" ] || exit 1 ;;
  *) echo "unexpected docker call: $*" >&2; exit 97 ;;
esac
STUB
chmod +x "$TMP/bin/docker"

fail=0
# check <name> <expected rc> <expected output fragment> <expected steps, space-separated, or "none"> [VAR=value ...]
check() {
  local name="$1" want_rc="$2" want="$3" steps="$4"; shift 4
  local out rc=0 verdict=ok got
  : > "$TMP/calls"
  out="$(env PATH="$TMP/bin:$PATH" STUB_DIR="$TMP" COMPOSE_PROJECT_NAME=demo "$@" bash -c '
    set -euo pipefail
    ROOT="'"$REPO"'"; COMPOSE=(docker compose -f docker-compose.selfhost.yml)
    source "'"$REPO"'/deploy/selfhost/volumes.sh"
    source "'"$REPO"'/deploy/selfhost/keycloak-h2.sh"
    move_keycloak_off_h2
  ' 2>&1)" || rc=$?
  [ "$rc" = "$want_rc" ] || verdict="rc=$rc, want $want_rc: $out"
  # An empty fragment means no output at all.
  if [ -z "$want" ]; then [ -z "$out" ] || verdict="output: $out"; else echo "$out" | grep -qF -- "$want" || verdict="output: $out"; fi
  got="$(sed -n 's/.*stop keycloak$/stop/p; s/.* export .*/export/p; s/.* import .*/import/p; s/.*> \/d\/moved-to-postgresql.*/mark/p' "$TMP/calls" | tr '\n' ' ' | sed 's/ $//')"
  [ "${got:-none}" = "$steps" ] || verdict="steps: ${got:-none}, want $steps"
  grep -q "volume inspect demo_selfhost_kc" "$TMP/calls" || verdict="the volume looked up is not the project's: $(cat "$TMP/calls")"
  if [ "$verdict" = ok ]; then echo "ok   $name"; else echo "FAIL $name: $verdict"; fail=1; fi
}

check "no H2 volume: nothing to move"         0 ""                                    "none"
check "a volume without an H2 file: left alone" 0 ""                                    "none" STUB_VOL=1 STUB_H2=none
check "moved already: left alone"               0 ""                                    "none" STUB_VOL=1 STUB_H2=moved
check "an H2 install: moved, then marked"       0 "realm lumnik moved"                  "stop export import mark" STUB_VOL=1 STUB_H2=new
check "the export fails: stopped there"         1 "could not export the lumnik realm"   "stop export" STUB_VOL=1 STUB_H2=new STUB_EXPORT_FAIL=1
check "the import fails: not marked"            1 "could not import the exported realm" "stop export import" STUB_VOL=1 STUB_H2=new STUB_IMPORT_FAIL=1

exit "$fail"
