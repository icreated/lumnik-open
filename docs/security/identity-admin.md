# Tenants, users & scopes — the admin gestures

Identity lives in **Keycloak**, not in lumnik: creating a human, giving them roles,
granting a métier scope are Keycloak-console gestures, and the hub follows the JWT.
This page walks each gesture in the bundled console — no Keycloak manual required
(the [official docs](https://www.keycloak.org/documentation) are for the hyper-curious).

**The model in four lines.** The hub trusts three JWT claims: `tenant_id` (which tenant),
`user_id` (which row), and the realm roles (`lm_*` for permissions, `scope:*` for métier
fences). The bundled realm `lumnik` already carries the protocol mappers that turn the
user attributes `tenant_id` / `user_id` into those claims — you only set attributes and
roles on users. A user *with* a `user_id` is bound to that row directly and never
provisioned — the bundled `integrator` (`user_id=1`) has its row seeded at install, and a
`user_id` that names no row still authenticates but the doors that need the row (default
scope) answer 404. Outside the open edition, a user *without* one is **JIT-provisioned** at
first login; SCIM deprovisions it ([SCIM](scim.md)).

Console: `http://localhost:8180` → realm **lumnik** (admin credentials from your `.env`).
The realm ships with **brute-force detection on** (temporary lockout at Keycloak's
defaults — tune it under *Realm settings → Security defenses*).
The console is **local-only by design** — the compose pins Keycloak to `127.0.0.1` and the
[TLS façade](../deploy.md#one-https-origin-the-tls-facade) proxies only `/realms/*` (the
OIDC endpoints), never the admin console. On a remote host, reach it through an SSH
tunnel ([reach the hub from another machine](../deploy.md#reach-the-hub-from-another-machine)).

## How to…

### Add a user

1. **Users → Add user** — username, email; **Create**.
2. **Credentials** tab → set a password (or let the IdP federation handle it).
3. **Attributes** tab → add `tenant_id` = the tenant's numeric id.
4. **Role mapping** tab → **Assign role** → `lm_user` (plus any `scope:*` roles — see below).

**Result (outside the open edition):** the first login JIT-creates the hub row in that tenant —
nothing to do on the lumnik side. **Open edition:** there is no JIT — also add a
`user_id` attribute pointing at an existing hub user row
(`POST /api/tenants/{tid}/users`, `lm_admin`); a JWT without it is refused 401.

### Grant (or revoke) a métier scope

Scope roles are **created on demand** — the shipped realm has none, because zero
`scope:*` roles means *unscoped, tenant-wide*.

1. **Realm roles → Create role** — name it `scope:ventes` (the tag your manifests use
   in `spec.scope`).
2. **Users →** the user → **Role mapping → Assign role** → `scope:ventes`.

**Result:** from their next token, the user is **bound**: chat, search, views, entity
reads answer only inside granted scopes — anything else refuses with
`403 {"error":"scope not granted"}` ([Scopes](../rag/scopes.md)). Revoking works the
same gesture in reverse; removing their *last* scope role makes them tenant-wide again.

### Make someone admin, integrator, superadmin

Same **Role mapping** gesture; what each unlocks:

| Role | Unlocks |
|---|---|
| `lm_user` | the app surface — chat, views, inbox, PWA |
| `lm_admin` | tenant administration — integrations, notification rules, business lists |
| `lm_integrator` | the `/api/platform/*` surface `lm` drives — connectors, manifests, runs |
| `lm_superadmin` | cross-tenant: tenant CRUD and impersonation |

Roles are separate, not ranked — an integrator is not automatically an admin
([role management](role-management.md)).

### Onboard a new tenant

1. Create the tenant row (superadmin):
   ```bash
   TOKEN=$(awk '/^ *access:/ {print $2; exit}' ~/.lm/config.yaml)
   curl -s -X POST https://your-hub.example.com/api/tenants \
     -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
     -d '{"code": "ACME", "name": "Acme Corp"}'    # both required; note the returned id
   ```
2. In Keycloak, create its first admin (gesture above) with `tenant_id` = the returned
   id and roles `lm_user` + `lm_admin`.

**Result:** that admin's first login lands in the new tenant; RLS fences every read and
write to it from the first request.

### Offboard someone

`active: false` from your IdP via [SCIM](scim.md) (not on the open edition) archives the user —
tokens are refused from the next request. Departure of the *integrator* has its own
checklist: [offboarding](offboarding.md).

## One surface to not misread

`POST /api/tenants/{tid}/users` creates a **hub row, not a login** — it carries no IdP
identity, so no one can authenticate as it (outside the open edition JIT creates rows by
itself; on the open edition the row is what a `user_id` attribute points at). Treat the
users API as the mirror it is, not as an onboarding path.

## See also

- [Role management](role-management.md) — the permission model behind `lm_*`
- [Bring your own IdP](byo-idp.md) — the same claims from your enterprise SSO
- [SCIM](scim.md) · [Offboarding](offboarding.md) · [Scopes](../rag/scopes.md)
