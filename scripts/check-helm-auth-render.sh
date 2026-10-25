#!/usr/bin/env bash
# Executable specification of the chart's auth default (#1511): a chart left at its defaults runs a hub that
# checks tokens. dev-bypass (no token checked, every caller superadmin, any tenant) renders only when it is
# asked for AND acknowledged with auth.iUnderstandNoAuth=true; asked for alone, the render fails with a message
# naming the acknowledgement. up.sh, the kind-only dev installer, passes the acknowledgement in dev-bypass only.
#
# Needs the helm binary (preinstalled on GitHub's runners). The secrets below are throwaway placeholders
# the chart insists on. Run from the repo root.

set -euo pipefail
cd "$(dirname "$0")/.."

REQUIRED=(--set secrets.postgresSuperuserPassword=x --set secrets.masterKey=x --set secrets.cryptoKey=x
          --set secrets.entityResolutionHmacSecret=x --set secrets.appDbPassword=x
          --set secrets.adminDbPassword=x --set secrets.readonlyDbPassword=x --set secrets.keycloakAdminPassword=x)

render() { helm template t deploy/helm/lumnik "${REQUIRED[@]}" "$@" 2>&1; }

fail=0
ok()   { echo "ok   $1"; }
nope() { echo "FAIL $1"; fail=1; }

defaults="$(render)" || true
grep -q 'QUARKUS_PROFILE: "prod"' <<<"$defaults" && ! grep -q 'k8sdev' <<<"$defaults" \
  && ok "defaults run the hub with token checks" || nope "defaults run the hub with token checks"
grep -q 'name: t-keycloak$' <<<"$defaults" \
  && ok "defaults deploy the bundled Keycloak" || nope "defaults deploy the bundled Keycloak"

if bypass_alone="$(render --set auth.mode=dev-bypass)"; then
  nope "dev-bypass without the acknowledgement refuses to render"
else
  grep -q 'auth.iUnderstandNoAuth=true' <<<"$bypass_alone" \
    && ok "dev-bypass without the acknowledgement refuses to render" \
    || nope "dev-bypass without the acknowledgement refuses to render (message names the acknowledgement)"
fi

render --set auth.mode=dev-bypass --set auth.iUnderstandNoAuth=yes >/dev/null \
  && nope "an acknowledgement other than true refuses" || ok "an acknowledgement other than true refuses"

acked="$(render --set auth.mode=dev-bypass --set auth.iUnderstandNoAuth=true)" || true
grep -q 'QUARKUS_PROFILE: "k8sdev"' <<<"$acked" \
  && ok "dev-bypass with the acknowledgement renders" || nope "dev-bypass with the acknowledgement renders"
acked_string="$(render --set auth.mode=dev-bypass --set-string auth.iUnderstandNoAuth=true)" || true
grep -q 'QUARKUS_PROFILE: "k8sdev"' <<<"$acked_string" \
  && ok "the acknowledgement may be a string" || nope "the acknowledgement may be a string"

oidc_acked="$(render --set auth.iUnderstandNoAuth=true)" || true
grep -q 'QUARKUS_PROFILE: "prod"' <<<"$oidc_acked" \
  && ok "the acknowledgement alone changes nothing" || nope "the acknowledgement alone changes nothing"

grep -qF -- '--set auth.iUnderstandNoAuth="$([ "$AUTH_MODE" = "dev-bypass" ] && echo true || echo false)"' deploy/helm/up.sh \
  && ok "up.sh acknowledges dev-bypass only in dev-bypass" || nope "up.sh acknowledges dev-bypass only in dev-bypass"

exit "$fail"
