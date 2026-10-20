#!/usr/bin/env bash
# Executable specification of how the Helm chart renders hub.extraEnv (#1240): the hub settings the
# chart has no value of its own for (LUMNIK_RATELIMIT_*, LUMNIK_WORKFLOW_SWEEP_EVERY, the OTel exporter).
#
# Unset renders nothing extra; each entry is rendered as written into the hub container's env.
#
# Needs the helm binary (preinstalled on GitHub's runners). The secrets below are throwaway placeholders
# the chart insists on. Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."

REQUIRED=(--set secrets.postgresSuperuserPassword=x --set secrets.masterKey=x --set secrets.cryptoKey=x
          --set secrets.entityResolutionHmacSecret=x --set secrets.appDbPassword=x
          --set secrets.adminDbPassword=x --set secrets.readonlyDbPassword=x --set secrets.keycloakAdminPassword=x)

render() { helm template t deploy/helm/lumnik "${REQUIRED[@]}" "$@" -s templates/hub-deployment.yaml; }

fail=0
# check <name> <expected: "absent" or the value> <env var> [helm args ...]
check() {
  local name="$1" want="$2" var="$3"; shift 3
  local got
  got="$(render "$@" | grep -A1 -- "- name: $var\$" | sed -n 's/^ *value: //p' || true)"
  [ -n "$got" ] || got=absent
  if [ "$got" = "$want" ]; then echo "ok   $name"; else echo "FAIL $name: got [$got], want [$want]"; fail=1; fi
}

check "unset renders nothing" absent LUMNIK_RATELIMIT_DEFAULT_RPM
check "one entry is rendered" '"120"' LUMNIK_RATELIMIT_DEFAULT_RPM \
  --set hub.extraEnv[0].name=LUMNIK_RATELIMIT_DEFAULT_RPM --set-string hub.extraEnv[0].value=120
check "a second entry is rendered too" '15m' LUMNIK_WORKFLOW_SWEEP_EVERY \
  --set hub.extraEnv[0].name=LUMNIK_RATELIMIT_DEFAULT_RPM --set-string hub.extraEnv[0].value=120 \
  --set hub.extraEnv[1].name=LUMNIK_WORKFLOW_SWEEP_EVERY --set-string hub.extraEnv[1].value=15m
# extraEnv appends to the env block, it does not replace the secret refs.
if ! render --set hub.extraEnv[0].name=X --set-string hub.extraEnv[0].value=1 | grep -q -- "- name: LUMNIK_SECRET_MASTER_KEY$"; then
  echo "FAIL the secret refs stay next to extraEnv"; fail=1
else
  echo "ok   the secret refs stay next to extraEnv"
fi

# OTel is off by default, like the selfhost stack (#1282): no collector ships with the chart. The
# default lives in the hub ConfigMap (envFrom); an extraEnv entry wins, since env beats envFrom.
otel="$(helm template t deploy/helm/lumnik "${REQUIRED[@]}" -s templates/hub-configmap.yaml \
  | sed -n 's/^ *QUARKUS_OTEL_SDK_DISABLED: //p')"
if [ "$otel" = '"true"' ]; then echo "ok   the ConfigMap turns OTel off"; else echo "FAIL the ConfigMap turns OTel off: got [$otel]"; fail=1; fi
check "extraEnv can turn OTel back on" '"false"' QUARKUS_OTEL_SDK_DISABLED \
  --set hub.extraEnv[0].name=QUARKUS_OTEL_SDK_DISABLED --set-string hub.extraEnv[0].value=false

exit "$fail"
