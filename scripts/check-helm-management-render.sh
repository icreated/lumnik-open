#!/usr/bin/env bash
# #1993: health, metrics and the OpenAPI spec answer on the hub's management port, which the probes read and nothing
# routes. The chart renders the port on the container, named, with QUARKUS_MANAGEMENT_PORT to match; the three probes
# use it; no Service and no HTTPRoute names it.
#
# Needs the helm binary (preinstalled on GitHub's runners). The secrets below are throwaway placeholders the chart
# insists on. Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."

REQUIRED=(--set secrets.postgresSuperuserPassword=x --set secrets.masterKey=x --set secrets.cryptoKey=x
          --set secrets.entityResolutionHmacSecret=x --set secrets.appDbPassword=x
          --set secrets.adminDbPassword=x --set secrets.readonlyDbPassword=x --set secrets.keycloakAdminPassword=x --set secrets.keycloakDbPassword=x)

fail=0
expect() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1: got [$2], want [$3]"; fail=1; fi; }

for port in 9000 9100; do
  rendered="$(helm template t deploy/helm/lumnik "${REQUIRED[@]}" --set hub.managementPort=$port)"
  deployment="$(printf '%s\n' "$rendered" | awk '/^# Source: lumnik\/templates\/hub-deployment.yaml/{on=1;next} /^# Source:/{on=0} on')"
  expect "port $port: the container names it management" \
    "$(printf '%s\n' "$deployment" | grep -A1 -- '- name: management' | sed -n 's/.*containerPort: //p')" "$port"
  expect "port $port: the hub listens on it" \
    "$(printf '%s\n' "$deployment" | grep -A1 -- '- name: QUARKUS_MANAGEMENT_PORT' | sed -n 's/.*value: //p')" "\"$port\""
  expect "port $port: the three probes read it" "$(printf '%s\n' "$deployment" | grep -c 'port: management')" 3
  routed="$(printf '%s\n' "$rendered" | awk '/^# Source: lumnik\/templates\/(.*service.*|httproute.*)\.yaml/{on=1;next} /^# Source:/{on=0} on' \
            | grep -cE "(port|targetPort): ($port|management)\$" || true)"
  expect "port $port: no Service or route names it" "$routed" 0
done

# #2090: the NetworkPolicy opens the management port to the pods the operator names (hub.managementFrom) and to no
# other pod. Read as YAML: for each ingress rule of the hub's policy, its ports and whether it names peers.
rules() {
  helm template t deploy/helm/lumnik "${REQUIRED[@]}" --show-only templates/networkpolicy.yaml "$@" | python3 -c '
import sys, yaml
for d in yaml.safe_load_all(sys.stdin):
    if d and d["metadata"]["name"] == "t-hub":
        for r in d["spec"].get("ingress", []):
            print(",".join(str(p["port"]) for p in r["ports"]), "from" if r.get("from") else "anyone")'
}
for port in 9000 9100; do
  expect "port $port, by default: the API to anyone, the management port to no pod" \
    "$(rules --set hub.managementPort=$port | tr '\n' ';')" "8080 anyone;"
  expect "port $port, scrapers named: the management port to them only" \
    "$(rules --set hub.managementPort=$port \
        --set 'hub.managementFrom[0].namespaceSelector.matchLabels.kubernetes\.io/metadata\.name=monitoring' | tr '\n' ';')" \
    "8080 anyone;$port from;"
done
expect "a managementFrom that is not a list is refused" \
  "$(helm template t deploy/helm/lumnik "${REQUIRED[@]}" --set hub.managementFrom.podSelector.x=y >/dev/null 2>&1 && echo rendered || echo refused)" refused

exit "$fail"
