# shellcheck shell=bash
# The lumnik realm's user profile, on an install whose realm predates #1997 (a realm import is one-shot).
# Sourced by up.sh.
#
# declare_admin_only_attributes <admin access token> <keycloak base URL>
#   Declares tenant_id and user_id on the lumnik realm's user profile, seen and edited by the admin only, and turns an
#   ENABLED unmanaged-attribute policy into ADMIN_EDIT. Under ENABLED, a user rewrote their own tenant_id from the
#   account page, and their next token carried it. Users keep their values. Writes only when something changes; warns,
#   never aborts a healthy stack.
declare_admin_only_attributes() {
  local tok="$1" kc="$2" profile healed
  profile="$(curl -fsS --connect-timeout 3 --max-time 10 -H "Authorization: Bearer $tok" \
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
  printf '%s' "$healed" | curl -fsS --connect-timeout 3 --max-time 10 -X PUT -H "Authorization: Bearer $tok" \
      -H "Content-Type: application/json" -d @- "$kc/admin/realms/lumnik/users/profile" >/dev/null 2>&1 \
    && echo "  tenant_id and user_id: now admin-only (the user profile declares them)" \
    || echo "  !! could not declare tenant_id and user_id admin-only — do it in the console (docs/operations/upgrade.md#the-bundled-keycloak-realm-is-stricter)"
}
