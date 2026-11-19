#!/usr/bin/env bash
# Executable specification of deploy/selfhost/realm.sh's close_password_grants (#1997): on an install whose realm was
# imported with the password grant open on its public clients (lumnik-spa, lm-cli), up.sh closes it. A client already
# closed is not written.
#
# Hermetic: `curl` is a stub on PATH. A GET of clients?clientId=X answers $STUB_SPA or $STUB_CLI (or fails with
# STUB_GET_FAIL); a PUT to /clients/<id> records its body in $TMP/put-<id>.json (or fails with STUB_PUT_FAIL). The admin
# token must come as a header file (-H @<fd>), never on argv. Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/selfhost-password-grants.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
cat > "$TMP/bin/curl" <<'STUB'
#!/bin/sh
case "$*" in *Bearer*) echo "the token is on curl's argv: $*" >&2; exit 98 ;; esac
auth=""; prev=""
for a in "$@"; do
  case "$prev:$a" in -H:@*) auth="$(cat "${a#@}")" ;; esac
  prev="$a"
done
[ "$auth" = "Authorization: Bearer tok" ] || { echo "no bearer header from a file: '$auth'" >&2; exit 99; }
url="${*##* }"
case "$*" in
  *"-X PUT"*"/admin/realms/lumnik/clients/"*) [ -n "${STUB_PUT_FAIL:-}" ] && exit 22; cat > "$STUB_DIR/put-${url##*/}.json" ;;
  *"-X PUT"*) echo "a PUT to another resource: $*" >&2; exit 96 ;;
  *"clientId=lumnik-spa"*) [ -n "${STUB_GET_FAIL:-}" ] && exit 22; printf '%s' "$STUB_SPA" ;;
  *"clientId=lm-cli"*) [ -n "${STUB_GET_FAIL:-}" ] && exit 22; printf '%s' "$STUB_CLI" ;;
  *) echo "unexpected curl call: $*" >&2; exit 97 ;;
esac
STUB
chmod +x "$TMP/bin/curl"

OPEN_SPA='[{"id":"s1","clientId":"lumnik-spa","directAccessGrantsEnabled":true,"publicClient":true}]'
OPEN_CLI='[{"id":"c1","clientId":"lm-cli","directAccessGrantsEnabled":true,"publicClient":true}]'
SHUT_SPA='[{"id":"s1","clientId":"lumnik-spa","directAccessGrantsEnabled":false}]'
SHUT_CLI='[{"id":"c1","clientId":"lm-cli","directAccessGrantsEnabled":false}]'

fail=0
# check <name> <expected output fragment> <expected PUT files, space-separated, or "none"> [VAR=value ...]
check() {
  local name="$1" want="$2" puts="$3"; shift 3
  local out rc=0 verdict=ok got
  rm -f "$TMP"/put-*.json
  out="$(env PATH="$TMP/bin:$PATH" STUB_DIR="$TMP" "$@" bash -c '
    set -euo pipefail
    source "'"$REPO"'/deploy/selfhost/bearer.sh"
    source "'"$REPO"'/deploy/selfhost/realm.sh"
    close_password_grants tok http://kc.test
  ' 2>&1)" || rc=$?
  [ "$rc" = 0 ] || verdict="rc=$rc"
  echo "$out" | grep -qF -- "$want" || verdict="output: $out"
  got="$(cd "$TMP" && { ls put-*.json 2>/dev/null || true; } | sed 's/put-//; s/\.json//' | tr '\n' ' ' | sed 's/ $//')"
  [ "${got:-none}" = "$puts" ] || verdict="PUTs: ${got:-none}, want $puts"
  for f in "$TMP"/put-*.json; do
    [ -e "$f" ] || continue
    python3 -c "import json,sys; c=json.load(open(sys.argv[1])); sys.exit(0 if c['directAccessGrantsEnabled'] is False and c.get('publicClient') is True else 1)" "$f" \
      || verdict="PUT body: $(cat "$f")"
  done
  if [ "$verdict" = ok ]; then echo "ok   $name"; else echo "FAIL $name: $verdict"; fail=1; fi
}

check "both open: both closed, the rest of each client kept" "lm-cli: password grant now closed" "c1 s1" \
  STUB_SPA="$OPEN_SPA" STUB_CLI="$OPEN_CLI"
check "both closed: nothing written"     "lm-cli: no password grant" "none" STUB_SPA="$SHUT_SPA" STUB_CLI="$SHUT_CLI"
check "one open: that one only"          "lumnik-spa: password grant now closed" "s1" STUB_SPA="$OPEN_SPA" STUB_CLI="$SHUT_CLI"
check "a client absent: said, not aborted" "!! lm-cli: not found" "s1" STUB_SPA="$OPEN_SPA" STUB_CLI='[]'
check "unreadable: warned, not aborted"  "!! could not read the lumnik-spa client" "none" STUB_GET_FAIL=1
check "the write fails: warned"          "!! could not close the password grant of lm-cli" "none" \
  STUB_PUT_FAIL=1 STUB_SPA="$SHUT_SPA" STUB_CLI="$OPEN_CLI"

exit "$fail"
