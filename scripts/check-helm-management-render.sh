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
          --set secrets.adminDbPassword=x --set secrets.readonlyDbPassword=x --set secrets.keycloakAdminPassword=x)

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

exit "$fail"
