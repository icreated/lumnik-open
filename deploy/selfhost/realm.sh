# shellcheck shell=bash
# The lumnik realm on an install whose realm predates #1997 (a realm import is one-shot): its user profile, its access
# token lifespan, its public clients' password grant.
# Sourced by up.sh, after deploy/selfhost/bearer.sh: it calls `bearer`.

# declare_admin_only_attributes <admin access token> <keycloak base URL>
#   Declares tenant_id and user_id on the lumnik realm's user profile, seen and edited by the admin only, and turns an
#   ENABLED unmanaged-attribute policy into ADMIN_EDIT. Under ENABLED, a user rewrote their own tenant_id from the
#   account page, and their next token carried it. Users keep their values. Writes only when something changes; warns,
#   never aborts a healthy stack.
declare_admin_only_attributes() {
  local tok="$1" kc="$2" profile healed
  profile="$(curl -fsS --connect-timeout 3 --max-time 10 -H @<(bearer "$tok") \
      "$kc/admin/realms/lumnik/users/profile" 2>/dev/null)" || {
    echo "  !! could not read the lumnik realm's user profile — tenant_id may still be editable by its user (docs/operations/upgrade.md#the-bundled-keycloak-realm-is-stricter)"
    return 0
  }
  healed="$(printf '%s' "$profile" | python3 -c '
import json, sys
p = json.load(sys.stdin)
admin_only = {"view": ["admin"], "edit": ["admin"]}
attributes = p.setdefault("attributes", [])
by_name = {a.get("name"): a for a in attributes}
changed = False
for name, label in (("tenant_id", "Tenant id"), ("user_id", "Hub user id")):
    a = by_name.get(name)
    if a is None:
        # permissions only: a validation added on a running install could refuse an older value it holds
        attributes.append({"name": name, "displayName": label, "permissions": admin_only, "multivalued": False})
        changed = True
    elif a.get("permissions") != admin_only:
        a["permissions"] = admin_only
        changed = True
if p.get("unmanagedAttributePolicy") == "ENABLED":
    p["unmanagedAttributePolicy"] = "ADMIN_EDIT"
    changed = True
print(json.dumps(p) if changed else "")
' 2>/dev/null)" || {
    echo "  !! Keycloak answered something that is not a user profile — tenant_id may still be editable by its user (docs/operations/upgrade.md#the-bundled-keycloak-realm-is-stricter)"
    return 0
  }
  if [[ -z "$healed" ]]; then
    echo "  tenant_id and user_id: admin-only"
    return 0
  fi
  printf '%s' "$healed" | curl -fsS --connect-timeout 3 --max-time 10 -X PUT -H @<(bearer "$tok") \
      -H "Content-Type: application/json" -d @- "$kc/admin/realms/lumnik/users/profile" >/dev/null 2>&1 \
    && echo "  tenant_id and user_id: now admin-only (the user profile declares them)" \
    || echo "  !! could not declare tenant_id and user_id admin-only — do it in the console (docs/operations/upgrade.md#the-bundled-keycloak-realm-is-stricter)"
}

# shorten_access_tokens <admin access token> <keycloak base URL>
#   An access token lives 5 minutes at most (#1997): a role, a scope or an account disabled in Keycloak stops within
#   5 minutes, even when the hub checks tokens locally. A realm imported before keeps its one-hour tokens (an import is
#   one-shot): this brings them to 5 minutes. A shorter lifespan is kept as chosen. Warns, never aborts a healthy stack.
shorten_access_tokens() {
  local tok="$1" kc="$2" life
  life="$(curl -fsS --connect-timeout 3 --max-time 10 -H @<(bearer "$tok") "$kc/admin/realms/lumnik" 2>/dev/null \
    | python3 -c 'import json,sys; print(int(json.load(sys.stdin).get("accessTokenLifespan") or 0))' 2>/dev/null)" || {
    echo "  !! could not read the lumnik realm — its access tokens may still live an hour (docs/operations/upgrade.md#an-access-token-lives-5-minutes)"
    return 0
  }
  if (( life > 0 && life <= 300 )); then
    echo "  access tokens: ${life} s"
    return 0
  fi
  printf '{"realm":"lumnik","accessTokenLifespan":300}' | curl -fsS --connect-timeout 3 --max-time 10 -X PUT \
      -H @<(bearer "$tok") -H "Content-Type: application/json" -d @- "$kc/admin/realms/lumnik" >/dev/null 2>&1 \
    && echo "  access tokens: now 5 minutes (were ${life} s)" \
    || echo "  !! could not bring access tokens to 5 minutes — set Realm settings → Tokens → Access Token Lifespan in the console (docs/operations/upgrade.md#an-access-token-lives-5-minutes)"
}

# close_password_grants <admin access token> <keycloak base URL>
#   The public clients take no password (#1997): the PWA signs in through its login page, lm through its device flow
#   (`lm token` for a script). A realm imported before keeps the password grant open on them (an import is one-shot):
#   this closes it, and keeps the rest of each client as it is. Warns, never aborts a healthy stack.
close_password_grants() {
  local tok="$1" kc="$2" client plan id
  for client in lumnik-spa lm-cli; do
    # one line: "closed" (nothing to do), "absent", or the client's id, then its representation with the grant closed
    plan="$(curl -fsS --connect-timeout 3 --max-time 10 -H @<(bearer "$tok") \
        "$kc/admin/realms/lumnik/clients?clientId=$client" 2>/dev/null | python3 -c '
import json, sys
cs = json.load(sys.stdin)
if not cs:
    print("absent")
elif cs[0].get("directAccessGrantsEnabled") is False:
    print("closed")
else:
    cs[0]["directAccessGrantsEnabled"] = False
    print(cs[0]["id"], json.dumps(cs[0]))
' 2>/dev/null)" || {
      echo "  !! could not read the $client client — its password grant may still be open (docs/operations/upgrade.md#the-public-clients-take-no-password)"
      continue
    }
    case "$plan" in
      closed) echo "  $client: no password grant" ;;
      absent) echo "  !! $client: not found in the lumnik realm" ;;
      *)
        id="${plan%% *}"
        printf '%s' "${plan#* }" | curl -fsS --connect-timeout 3 --max-time 10 -X PUT -H @<(bearer "$tok") \
            -H "Content-Type: application/json" -d @- "$kc/admin/realms/lumnik/clients/$id" >/dev/null 2>&1 \
          && echo "  $client: password grant now closed" \
          || echo "  !! could not close the password grant of $client — untick Direct access grants in the console (docs/operations/upgrade.md#the-public-clients-take-no-password)"
        ;;
    esac
  done
}
