#!/usr/bin/env bash
# Executable specification of how the Helm chart renders the two retention windows that take "0" = keep
# forever: LUMNIK_WEBHOOKS_DELIVERY_RETENTION_DAYS (#940) and LUMNIK_AUDIT_RETENTION_DAYS.
#
# The hub reads "<= 0" as "keep forever" and an ABSENT variable as the default window (90 / 365 days),
# so a render that drops a `0` turns "keep" into "delete". Helm parses `--set x=0` (and a values-file
# `x: 0`) as the integer 0, which a bare `with` treats as empty and omits; `--set-string x=0` arrives as
# "0" and survives. The template must tell empty from zero whatever the spelling.
#
# Needs the helm binary (preinstalled on GitHub's runners). The secrets below are throwaway placeholders
# the chart insists on. Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."

REQUIRED=(--set secrets.postgresSuperuserPassword=x --set secrets.masterKey=x --set secrets.cryptoKey=x
          --set secrets.entityResolutionHmacSecret=x --set secrets.appDbPassword=x
          --set secrets.adminDbPassword=x --set secrets.readonlyDbPassword=x --set secrets.keycloakAdminPassword=x)

fail=0
# check <name> <env var> <expected value, or "absent"> [helm args ...]
check() {
  local name="$1" var="$2" want="$3"; shift 3
  local got
  got="$(helm template t deploy/helm/lumnik "${REQUIRED[@]}" "$@" -s templates/hub-configmap.yaml \
         | sed -n "s/^ *$var: //p" || true)"
  [ -n "$got" ] || got=absent
  if [ "$got" = "$want" ]; then echo "ok   $name"; else echo "FAIL $name: got [$got], want [$want]"; fail=1; fi
}

# <label> <env var> <helm value path>
for spec in "delivery|LUMNIK_WEBHOOKS_DELIVERY_RETENTION_DAYS|webhooks.deliveryRetentionDays" \
            "audit|LUMNIK_AUDIT_RETENTION_DAYS|audit.retentionDays"; do
  IFS='|' read -r label var path <<< "$spec"
  check "$label: unset leaves the application default" "$var" absent
  check "$label: empty leaves the application default" "$var" absent   --set-string "$path="
  check "$label: string 0 keeps forever"               "$var" '"0"'    --set-string "$path=0"
  check "$label: numeric 0 keeps forever"              "$var" '"0"'    --set "$path=0"
  check "$label: numeric positive is passed through"   "$var" '"30"'   --set "$path=30"
  check "$label: string positive is passed through"    "$var" '"30"'   --set-string "$path=30"
done

exit "$fail"
