#!/usr/bin/env bash
# Executable specification of how the Helm chart renders the outbound knob (#2002): LUMNIK_OUTBOUND_ALLOW_PRIVATE.
#
# The chart serves several tenants, so it refuses private ranges unless told otherwise: the default renders
# "false". An explicit bool false must still be rendered (`with` would drop it); true is passed through; an empty
# value leaves the application default (true), which only an operator who asked for it gets.
#
# Needs the helm binary (preinstalled on GitHub's runners). The secrets below are throwaway placeholders
# the chart insists on. Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."

REQUIRED=(--set secrets.postgresSuperuserPassword=x --set secrets.masterKey=x --set secrets.cryptoKey=x
          --set secrets.entityResolutionHmacSecret=x --set secrets.appDbPassword=x
          --set secrets.adminDbPassword=x --set secrets.readonlyDbPassword=x --set secrets.keycloakAdminPassword=x --set secrets.keycloakDbPassword=x)
VAR=LUMNIK_OUTBOUND_ALLOW_PRIVATE

fail=0
# check <name> <expected value, or "absent"> [helm args ...]
check() {
  local name="$1" want="$2"; shift 2
  local got
  got="$(helm template t deploy/helm/lumnik "${REQUIRED[@]}" "$@" -s templates/hub-configmap.yaml \
         | sed -n "s/^ *$VAR: //p" || true)"
  [ -n "$got" ] || got=absent
  if [ "$got" = "$want" ]; then echo "ok   $name"; else echo "FAIL $name: got [$got], want [$want]"; fail=1; fi
}

check "the chart default refuses private ranges" '"false"'
check "bool false is rendered"                   '"false"' --set outbound.allowPrivate=false
check "bool true is passed through"              '"true"'  --set outbound.allowPrivate=true
check "string true is passed through"            '"true"'  --set-string outbound.allowPrivate=true
check "empty leaves the application default"     absent    --set-string outbound.allowPrivate=

exit "$fail"
