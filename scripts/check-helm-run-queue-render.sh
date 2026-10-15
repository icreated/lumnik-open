#!/usr/bin/env bash
# Executable specification of how the Helm chart renders the run-queue knobs (#952):
# LUMNIK_RUN_QUEUE_ENABLED, LUMNIK_RUN_QUEUE_MAX_AGE, LUMNIK_RUN_QUEUE_POLL_INTERVAL.
#
# Unset or empty leaves the application default (queue off, 1h, 5s). An explicit `false` must still be
# rendered: `with` would drop a bare boolean false, and "off" written down is not an absent knob.
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
for spec in "enabled|LUMNIK_RUN_QUEUE_ENABLED|runQueue.enabled" \
            "max-age|LUMNIK_RUN_QUEUE_MAX_AGE|runQueue.maxAge" \
            "poll|LUMNIK_RUN_QUEUE_POLL_INTERVAL|runQueue.pollInterval"; do
  IFS='|' read -r label var path <<< "$spec"
  check "$label: unset leaves the application default" "$var" absent
  check "$label: empty leaves the application default" "$var" absent --set-string "$path="
done
check "enabled: string true is passed through"  LUMNIK_RUN_QUEUE_ENABLED '"true"'  --set-string runQueue.enabled=true
check "enabled: bool true is passed through"    LUMNIK_RUN_QUEUE_ENABLED '"true"'  --set runQueue.enabled=true
check "enabled: bool false is passed through"   LUMNIK_RUN_QUEUE_ENABLED '"false"' --set runQueue.enabled=false
check "max-age is passed through"               LUMNIK_RUN_QUEUE_MAX_AGE '"2h"'    --set-string runQueue.maxAge=2h
check "poll interval is passed through"         LUMNIK_RUN_QUEUE_POLL_INTERVAL '"10s"' --set-string runQueue.pollInterval=10s

exit "$fail"
