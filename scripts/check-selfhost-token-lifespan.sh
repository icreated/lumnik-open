#!/usr/bin/env bash
# Executable specification of deploy/selfhost/realm.sh's shorten_access_tokens (#1997): on an install whose realm was
# imported with one-hour access tokens, up.sh brings them to 5 minutes. A shorter lifespan is kept as chosen.
#
# Hermetic: `curl` is a stub on PATH. A GET of the realm answers $STUB_REALM (or fails with STUB_GET_FAIL); a PUT
# records its body in $TMP/put.json (or fails with STUB_PUT_FAIL). The admin token must come as a header file
# (-H @<fd>), never on argv. Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/selfhost-token-lifespan.XXXXXX")"
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
case "$*" in
  *"-X PUT"*"/admin/realms/lumnik") [ -n "${STUB_PUT_FAIL:-}" ] && exit 22; cat > "$STUB_DIR/put.json" ;;
  *"-X PUT"*) echo "a PUT to another resource: $*" >&2; exit 96 ;;
  *"/admin/realms/lumnik"*) [ -n "${STUB_GET_FAIL:-}" ] && exit 22; printf '%s' "$STUB_REALM" ;;
  *) echo "unexpected curl call: $*" >&2; exit 97 ;;
esac
STUB
chmod +x "$TMP/bin/curl"

fail=0
# check <name> <expected output fragment> <python assertion on the PUT body, or "no PUT"> [VAR=value ...]
check() {
  local name="$1" want="$2" put="$3"; shift 3
  local out rc=0
  rm -f "$TMP/put.json"
  out="$(env PATH="$TMP/bin:$PATH" STUB_DIR="$TMP" "$@" bash -c '
    set -euo pipefail
    source "'"$REPO"'/deploy/selfhost/bearer.sh"
    source "'"$REPO"'/deploy/selfhost/realm.sh"
    shorten_access_tokens tok http://kc.test
  ' 2>&1)" || rc=$?
  local verdict=ok
  [ "$rc" = 0 ] || verdict="rc=$rc"
  echo "$out" | grep -qF -- "$want" || verdict="output: $out"
  if [ "$put" = "no PUT" ]; then
    [ ! -e "$TMP/put.json" ] || verdict="unexpected PUT: $(cat "$TMP/put.json")"
  elif [ ! -e "$TMP/put.json" ]; then
    verdict="no PUT"
  elif ! python3 -c "import json,sys; p=json.load(open(sys.argv[1])); sys.exit(0 if ($put) else 1)" "$TMP/put.json"; then
    verdict="PUT body: $(cat "$TMP/put.json")"
  fi
  if [ "$verdict" = ok ]; then echo "ok   $name"; else echo "FAIL $name: $verdict"; fail=1; fi
}

check "an hour becomes 5 minutes"          "access tokens: now 5 minutes (were 3600 s)" \
  "p == {'realm': 'lumnik', 'accessTokenLifespan': 300}" STUB_REALM='{"realm":"lumnik","accessTokenLifespan":3600}'
check "5 minutes: nothing written"          "access tokens: 300 s" "no PUT" \
  STUB_REALM='{"realm":"lumnik","accessTokenLifespan":300}'
check "shorter is kept as chosen"           "access tokens: 120 s" "no PUT" \
  STUB_REALM='{"realm":"lumnik","accessTokenLifespan":120}'
check "the realm cannot be read: warned"    "!! could not read the lumnik realm" "no PUT" \
  STUB_GET_FAIL=1 STUB_REALM='{}'
check "an answer that is not a realm: warned" "!! could not read the lumnik realm" "no PUT" \
  STUB_REALM='<html>proxy error</html>'
check "the write fails: warned, not aborted" "!! could not bring access tokens to 5 minutes" "no PUT" \
  STUB_PUT_FAIL=1 STUB_REALM='{"realm":"lumnik","accessTokenLifespan":3600}'

exit "$fail"
