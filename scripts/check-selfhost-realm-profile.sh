#!/usr/bin/env bash
# Executable specification of deploy/selfhost/realm.sh (#1997): on an install whose realm predates the declared user
# profile, up.sh declares tenant_id and user_id admin-only and turns an ENABLED unmanaged-attribute policy into
# ADMIN_EDIT. Under ENABLED, a user rewrote their own tenant_id from the account page.
#
# Hermetic: `curl` is a stub on PATH; it refuses a call whose admin token is not in a `-H @<fd>` header file. A GET of the profile answers $STUB_PROFILE (or fails with STUB_GET_FAIL);
# a PUT records its body in $TMP/put.json (or fails with STUB_PUT_FAIL). Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."
REPO="$PWD"

TMP="$(mktemp -d "${TMPDIR:-/tmp}/selfhost-realm-profile.XXXXXX")"
trap 'rm -rf "$TMP"' EXIT
mkdir -p "$TMP/bin"
cat > "$TMP/bin/curl" <<'STUB'
#!/bin/sh
# The admin token comes as a header file (-H @<fd>), never on argv (#1998): no token there, no call.
case "$*" in *Bearer*) echo "the token is on curl's argv: $*" >&2; exit 98 ;; esac
auth=""; prev=""
for a in "$@"; do
  case "$prev:$a" in -H:@*) auth="$(cat "${a#@}")" ;; esac
  prev="$a"
done
[ "$auth" = "Authorization: Bearer tok" ] || { echo "no bearer header from a file: '$auth'" >&2; exit 99; }
case "$*" in
  *"-X PUT"*) [ -n "${STUB_PUT_FAIL:-}" ] && exit 22; cat > "$STUB_DIR/put.json" ;;
  *"/admin/realms/lumnik/users/profile"*) [ -n "${STUB_GET_FAIL:-}" ] && exit 22; printf '%s' "$STUB_PROFILE" ;;
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
    declare_admin_only_attributes tok http://kc.test
  ' 2>&1)" || rc=$?
  local verdict=ok
  [ "$rc" = 0 ] || verdict="rc=$rc"
  echo "$out" | grep -qF -- "$want" || verdict="output: $out"
  if [ "$put" = "no PUT" ]; then
    [ ! -e "$TMP/put.json" ] || verdict="unexpected PUT: $(cat "$TMP/put.json")"
  elif [ ! -e "$TMP/put.json" ]; then
    verdict="no PUT"
  elif ! python3 -c "import json,sys; p=json.load(open(sys.argv[1])); a={x['name']:x for x in p['attributes']}; sys.exit(0 if ($put) else 1)" "$TMP/put.json"; then
    verdict="PUT body: $(cat "$TMP/put.json")"
  fi
  if [ "$verdict" = ok ]; then echo "ok   $name"; else echo "FAIL $name: $verdict"; fail=1; fi
}

DEFAULT='{"attributes":[{"name":"username","permissions":{"view":["admin","user"],"edit":["admin","user"]}},{"name":"email"}],"groups":[]}'
ADMIN_ONLY='{"view":["admin"],"edit":["admin"]}'
DECLARED='{"attributes":[{"name":"username"},{"name":"tenant_id","permissions":'"$ADMIN_ONLY"'},{"name":"user_id","permissions":'"$ADMIN_ONLY"'}]}'
OPEN='{"unmanagedAttributePolicy":"ENABLED","attributes":[{"name":"username"},{"name":"tenant_id","permissions":{"view":["admin","user"],"edit":["admin","user"]}}]}'
ADMIN_ONLY_PY="{'view': ['admin'], 'edit': ['admin']}"

check "a default profile: both declared admin-only"     "now admin-only" \
  "a['tenant_id']['permissions'] == $ADMIN_ONLY_PY and a['user_id']['permissions'] == $ADMIN_ONLY_PY" STUB_PROFILE="$DEFAULT"
check "the other attributes are kept as they were"      "now admin-only" \
  "a['username']['permissions']['edit'] == ['admin', 'user'] and 'email' in a and 'groups' in p" STUB_PROFILE="$DEFAULT"
check "ENABLED becomes ADMIN_EDIT"                       "now admin-only" \
  "p['unmanagedAttributePolicy'] == 'ADMIN_EDIT'" STUB_PROFILE="$OPEN"
check "a tenant_id the user may edit becomes admin-only" "now admin-only" \
  "a['tenant_id']['permissions'] == $ADMIN_ONLY_PY" STUB_PROFILE="$OPEN"
check "another policy is left as chosen"                 "now admin-only" \
  "p['unmanagedAttributePolicy'] == 'ADMIN_VIEW'" STUB_PROFILE='{"unmanagedAttributePolicy":"ADMIN_VIEW","attributes":[]}'
check "already declared: nothing written"                "tenant_id and user_id: admin-only" "no PUT" STUB_PROFILE="$DECLARED"
check "the profile cannot be read: warned, not aborted"  "!! could not read the lumnik realm's user profile" "no PUT" \
  STUB_GET_FAIL=1 STUB_PROFILE="$DEFAULT"
check "an answer that is not a profile: warned, not aborted" "!! Keycloak answered something that is not a user profile" "no PUT" \
  STUB_PROFILE='<html>proxy error</html>'
check "the write fails: warned, not aborted"             "!! could not declare tenant_id and user_id admin-only" "no PUT" \
  STUB_PUT_FAIL=1 STUB_PROFILE="$DEFAULT"

exit "$fail"
