#!/usr/bin/env bash
# Executable specification of how the Helm chart renders hub.hooksConfigMap (#1402): the operator's Kotlin
# scripts, which a connector manifest names as `script: hooks:<name>.kts`.
#
# Unset renders no hooks directory at all; set, the ConfigMap is mounted READ-ONLY where LUMNIK_HOOKS_DIR
# points — the hub can never write a script — and next to the Search CA when both are on.
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
ok()   { echo "ok   $1"; }
nope() { echo "FAIL $1"; fail=1; }

unset_out="$(render)"
if grep -q 'LUMNIK_HOOKS_DIR\|name: hooks$' <<<"$unset_out"; then nope "unset renders no hooks directory"; else ok "unset renders no hooks directory"; fi

set_out="$(render --set hub.hooksConfigMap=lumnik-hooks)"
grep -A1 -- '- name: LUMNIK_HOOKS_DIR$' <<<"$set_out" | grep -q 'value: /etc/lumnik/hooks$' \
  && ok "LUMNIK_HOOKS_DIR points at the mount" || nope "LUMNIK_HOOKS_DIR points at the mount"
grep -A2 -- '- name: hooks$' <<<"$set_out" | grep -q 'mountPath: /etc/lumnik/hooks' \
  && grep -A2 -- '- name: hooks$' <<<"$set_out" | grep -q 'readOnly: true' \
  && ok "the directory is mounted read-only" || nope "the directory is mounted read-only"
grep -A2 -- '- name: hooks$' <<<"$set_out" | grep -q 'name: lumnik-hooks$' \
  && ok "the volume is the named ConfigMap" || nope "the volume is the named ConfigMap"

both="$(render --set hub.hooksConfigMap=lumnik-hooks --set search.cluster.enabled=true --set search.cluster.tlsSecretName=ca --set secrets.searchClusterPassword=x --set secrets.searchClusterAdminPassword=x)"
[ "$(grep -c -- '- name: hooks$' <<<"$both")" = 2 ] && [ "$(grep -c -- '- name: search-cluster-ca$' <<<"$both")" = 2 ] \
  && ok "hooks and the Search CA mount side by side" || nope "hooks and the Search CA mount side by side"

exit "$fail"
