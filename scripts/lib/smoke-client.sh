# shellcheck shell=bash
# add_smoke_client <admin access token> <keycloak base URL>
#   On a THROWAWAY realm only (a kind cluster's, a sizing stack's): the client `lumnik-smoke`, a copy of `lm-cli` with
#   its mappers and scopes, so its tokens carry the same claims, that takes the password grant. Scripts mint their
#   test tokens with it. The shipped clients take no password (#1997); a real install has no lumnik-smoke, and a script
#   against it uses `lm token`. Idempotent. Needs `bearer` (deploy/selfhost/bearer.sh).
add_smoke_client() {
  local tok="$1" kc="$2" found rep
  found="$(curl -fsS -H @<(bearer "$tok") "$kc/admin/realms/lumnik/clients?clientId=lumnik-smoke")" \
    || { echo "add_smoke_client: could not list the realm's clients at $kc" >&2; return 1; }
  found="$(printf '%s' "$found" | python3 -c 'import json,sys; print(len(json.load(sys.stdin)))' 2>/dev/null)" \
    || { echo "add_smoke_client: the realm's client list at $kc is not JSON" >&2; return 1; }
  [ "$found" != 0 ] && return 0
  rep="$(curl -fsS -H @<(bearer "$tok") "$kc/admin/realms/lumnik/clients?clientId=lm-cli" | python3 -c '
import json, sys
cs = json.load(sys.stdin)
if not cs:
    sys.exit("add_smoke_client: no lm-cli client to copy")
c = cs[0]
for k in ("id", "secret", "registrationAccessToken"):
    c.pop(k, None)
for m in c.get("protocolMappers", []):
    m.pop("id", None)
c.update(clientId="lumnik-smoke", name="Smoke tests (throwaway realms only)",
         directAccessGrantsEnabled=True, standardFlowEnabled=False)
c.setdefault("attributes", {})["oauth2.device.authorization.grant.enabled"] = "false"
print(json.dumps(c))')" || { echo "add_smoke_client: could not read lm-cli at $kc" >&2; return 1; }
  printf '%s' "$rep" | curl -fsS -X POST -H @<(bearer "$tok") -H 'Content-Type: application/json' -d @- \
    "$kc/admin/realms/lumnik/clients" >/dev/null || { echo "add_smoke_client: could not create lumnik-smoke at $kc" >&2; return 1; }
}
