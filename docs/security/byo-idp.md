# Bring your own IdP

> The hub validates any OIDC issuer generically — no Keycloak-specific code. Your
> IdP authenticates; lumnik provisions the user on first login (JIT) into a
> pre-seeded tenant.

Operator guide for plugging a customer's existing IdP (Keycloak, Okta, Entra ID, any
OIDC issuer) into lumnik — enterprise onboarding without deploying a second Keycloak.

## The model

The hub validates the Bearer token against your issuer (discovery + JWKS), reads the
mandatory `tenant_id` claim to scope every query through PostgreSQL Row-Level
Security, and — on a user's **first** login — creates the lumnik user, keyed on
`(idp_issuer, idp_subject)`. No password ever lives in lumnik; no user list is
synced up front. Departures ride [SCIM](scim.md) — JIT never reactivates a
deactivated identity.

> **The crucial rule: JIT creates users, never tenants.** The tenant row must
> pre-exist — seeded by the operator at onboarding. A token claiming a `tenant_id`
> with no matching tenant row fails the first login, by design: a tenant is an
> operator decision, not a side effect of someone signing in.

**Edition note.** JIT provisioning ships in the self-host and Helm builds this page
documents, so the walkthroughs below work as written. It is absent from the
separately-published [open edition](../editions.md#feature-by-feature), which fails
closed instead: a token without a `user_id` claim gets
`401 {"error":"no external identity resolution in this edition (user_id claim required)"}`.
Since a real customer IdP cannot know lumnik's internal user ids, BYO IdP in
practice needs a build that carries JIT.

## Prerequisites checklist

Before the first user logs in:

**1. Seed the tenant row.** A fresh install seeds tenant `1` (`code='default'`).
Every *other* tenant is yours to create, run as the owner role (BYPASSRLS):

```sql
INSERT INTO identity.tenant (id, code, name, created_at, updated_at)
VALUES (42, 'acme', 'ACME Corp', now(), now())
ON CONFLICT DO NOTHING;
```

(Or `POST /api/tenants` as `lm_superadmin`. Explicit ids below 1000 are safe — the
sequence starts at 1000.)

**2. Register a client in your IdP** for each surface: one for `lm` (device flow)
and — if you use the PWA — the SPA client (public + PKCE). See the
[SPA warning](#the-pwa-client-lumnik-spa) below.

**3. Map the claims** onto the *access* token:

| Claim | Required | What it must carry |
|---|---|---|
| `aud` | **yes** | Exactly `lumnik-backend` — the hub pins this audience; a token without it never gets past the OIDC layer (401). |
| `tenant_id` | **yes** | The seeded tenant id, as an integer. |
| `groups` | **yes** | The realm-role array: `lm_user`, `lm_integrator`, `lm_admin`, plus any `scope:<tag>` grants — literal lumnik role names, flat, no hierarchy ([Role management](role-management.md)). |
| `user_id` | no | Only if your IdP knows lumnik's internal user id (the bundled realm does; a customer IdP normally cannot). When absent, JIT resolves the user from `iss` + the subject claim — the normal external-IdP posture. |
| `name`, `email` | no | Profile fields, used only at first-login creation. |

The subject side of the identity key defaults to `sub`; override with
`OIDC_SUBJECT_CLAIM` when your IdP has a more stable claim (see the Entra note
below).

Malformed claims are rejected, never repaired — a non-integer `tenant_id`, an
oversized issuer or subject: each fails with its own explicit 401 in the
[troubleshooting table](#troubleshooting). A bad *profile* claim never blocks a
login: an over-long name is clamped, an invalid email is dropped.

## What JIT creates (and what it refuses)

On first login with no `user_id` claim, the hub creates one user row **in the
token's tenant**: login = subject, display name from `name` (fallback: subject),
optional email, and the `(idp_issuer, idp_subject)` key. Creation is race-safe with
replicas > 1 — two concurrent first logins resolve to one row.

Every later login resolves the same row. A **deactivated** identity is refused with
`403 {"error":"user is deactivated"}` — JIT never reactivates or recreates; only an
admin (or a SCIM re-activation flow you control) brings a user back.

## Keycloak walkthrough

The shipped realm (`infra/keycloak/lumnik-realm.json`) is the working reference —
copy its mapper shapes onto your client:

| Step | Configuration |
|---|---|
| **1. Realm roles** | Create `lm_user`, `lm_integrator`, `lm_admin` (and `scope:<tag>` roles if you bind métiers — [Scopes](../rag/scopes.md#binding-users-to-scopes-opt-in)). Assign them to users or groups; the hub matches names flat. |
| **2. `tenant_id` mapper** | *User Attribute* mapper, user attribute `tenant_id`, claim `tenant_id`, JSON type **long**, on the access token. Set the attribute per user (or per group) to the seeded tenant id. |
| **3. `groups` mapper** | *User Realm Role* mapper, claim name `groups`, multivalued, on the access token. |
| **4. Audience mapper** | *Audience* mapper adding `lumnik-backend` to the access token. Every client that calls the hub needs it — see the SPA scar below. |
| **5. `lm` client** | OIDC client with device flow enabled; point the CLI at your issuer (`lm config set-context ... --oidc-issuer https://idp.example.com/realms/corp --oidc-client-id <your-client>`). |

## Okta / Entra ID notes

One layer to re-own when you swap IdPs: the bundled realm ships **brute-force
detection on** (temporary lockout); with your own IdP, login throttling and lockout
are your IdP's policies — lumnik neither sees nor throttles password attempts.

The hub needs only discovery/JWKS at the issuer URL and the claims above, so Okta
and Entra work **on paper**. In practice, only Keycloak is exercised by the shipped
rehearsal (`make k8s-up-oidc-external`) — neither has been run against a live hub.
And because neither emits a `user_id` claim, first login for either rides the JIT
path — mind the [edition note](#the-model).

- **Okta**: custom claims (`tenant_id`, `groups`) and a custom audience require a
  *custom authorization server*; map `groups` to the lumnik role names, not Okta
  group ids.
- **Entra ID**: `sub` is pairwise **per app registration** — stable for one app, but
  re-registering the app mints new subjects and orphans every JIT identity. Prefer
  `OIDC_SUBJECT_CLAIM=oid` (the tenant-stable object id). Entra's default `groups`
  claim emits group GUIDs — you need claims-mapping policy (or app roles emitted
  into a `groups` claim) to carry the literal `lm_*` names.
- **SAML-only IdPs** (ADFS and friends): not supported by direct trust — front them
  with a broker (a Keycloak you or the customer operates) and point lumnik at the
  broker.

## Kubernetes: `auth.mode=oidc-external`

The Helm chart carries a first-class mode for this: trust the customer's issuer,
deploy **no** Keycloak.

```yaml
# deploy/helm/lumnik/values.yaml
auth:
  mode: oidc-external

oidc:
  external:
    issuerUrl: "https://idp.customer.com/realms/corp"   # discovery/JWKS
    tokenIssuer: ""   # token `iss` to validate; defaults to issuerUrl.
                      # Set ONLY when it differs (a port-forward split on kind).
```

The chart **fails fast**: a typo'd `auth.mode` aborts `helm install` (instead of
silently pointing the hub at a Keycloak that was never deployed), and
`oidc-external` with an empty `issuerUrl` refuses to render. Both `oidc` and
`oidc-external` run the hub on `%prod` (dev-bypass OFF); only where the issuer
points differs.

To rehearse the whole shape locally — a stand-in "customer IdP" in its own
namespace, the hub trusting it, the release provably Keycloak-free:

```bash
make k8s-up-oidc-external
```

A real customer IdP is then an `issuerUrl` swap — zero chart change, zero hub code
change. Leave `tokenIssuer` empty in real deployments (the split exists only for
the kind port-forward).

## The PWA client (`lumnik-spa`)

> **⚠️ The audience-mapper scar.** The hub pins `aud: lumnik-backend` on every
> access token — and a freshly-created SPA client emits **no** audience by default.
> The bundled `lumnik-spa` client learned this live: without the audience mapper,
> every PWA call is a 401 while `lm` works fine. When you register the SPA client in
> the customer IdP: public client + PKCE (S256), redirect URI
> `https://<hub-origin>/app/*`, **and the audience mapper on the access token**.
> Full PWA auth lifecycle: [the métier PWA](../apps/pwa.md).

## The issuer is part of the identity

> **⚠️ Changing the issuer URL orphans JIT identities.** Users are keyed
> `(idp_issuer, idp_subject)`. Move the origin — plain-http to TLS, hostname change,
> issuer path change — and the same human becomes a *new* identity, whose first
> login then collides with the old row's login (`uq_user_login`, surfaced
> as an explicit error). Pick the public origin **once, early**, before the first real login.
> If you must move: migrate `identity.app_user.idp_issuer` deliberately, or accept
> re-provisioning by clearing the old-issuer users. Details:
> [upgrade](../operations/upgrade.md#known-scar) and
> [backup/restore](../operations/backup-restore.md#known-scar-issuer-change-orphans-jit-identities).

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `401 {"error":"missing or invalid tenant_id claim"}` | No `tenant_id` on the access token, or a non-integer value (a fractional id is rejected, never truncated) | Add/fix the `tenant_id` mapper (JSON type long, access token) |
| 401 with no lumnik error body | Token rejected by the OIDC layer: wrong/missing `aud: lumnik-backend`, or `iss` ≠ the configured token issuer | Add the audience mapper; check `issuerUrl`/`tokenIssuer` match the token's `iss` |
| `401 {"error":"missing subject/issuer claim"}` | No `user_id` claim *and* the subject claim is absent | Check `OIDC_SUBJECT_CLAIM` names a claim the IdP actually emits |
| `401 {"error":"no external identity resolution in this edition (user_id claim required)"}` | No `user_id` claim, and the running build is the open-source edition (no JIT) | Deploy the default self-host/Helm build (JIT ships there) |
| `401 {"error":"subject/issuer claim too long"}` | `iss` or subject exceeds 255 chars — rejected, never truncated: truncating the key would change the identity | Use a shorter stable claim (e.g. Entra `oid`) |
| First login 500, logs show `fk_user_tenant` | Tenant row not seeded — JIT creates users, never tenants | Seed the tenant (checklist above), retry |
| `403 {"error":"user is deactivated"}` | Identity exists but was deactivated (e.g. SCIM departure) | Reactivate via admin — JIT never resurrects |
| 403 on every endpoint despite valid login | `groups` claim missing or carrying IdP-native names instead of `lm_*` | Map realm roles into `groups` as literal lumnik role names |
| Login error after moving to TLS / new hostname | Issuer change orphaned the JIT identity; new row collides on `uq_user_login` | Migrate `idp_issuer` on existing rows (scar box above) |

## See also

- [Role management](role-management.md) — the realm-role catalog and `roles.yaml`
- [Scopes](../rag/scopes.md#binding-users-to-scopes-opt-in) — `scope:<tag>` métier grants
- [Deploy](../deploy.md) — self-host and k8s paths; pointing `lm` at the hub
- [SCIM deprovisioning](scim.md) — the departure side of the identity lifecycle
- The filter that enforces all of this: `lumnik-hub/src/main/java/io/lumnik/hub/tenant/TenantContextFilter.java`
- The design background: `docs/superpowers/specs/2026-06-19-cloud-edition-external-idp-design.md`
