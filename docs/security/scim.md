# SCIM deprovisioning

> **Not in the open edition.** The entire `/scim/v2/*` surface and its token-management
> endpoints live under `io.lumnik.hub.pro.scim`, excluded from the published open-source
> edition; the self-host and Helm builds this page documents carry it by default.

Operator guide for wiring departures from your IdP (Okta, Microsoft Entra ID, Keycloak)
into lumnik over SCIM 2.0.

## Why

Arrival is automatic: a user who logs in through your IdP is JIT-provisioned on first
login (see [BYO IdP](byo-idp.md)). Departure is not — the IdP will happily keep issuing
tokens until the account is disabled there, and lumnik would keep honouring them. SCIM is
the departure side: your IdP pushes `active: false` the moment an account is deactivated,
lumnik archives the user, and the very next request is denied — even if a still-valid
token is in flight. That closed loop (arrival ↔ departure) is what an access-review auditor
asks to see.

The **integrator's** departure is a different checklist —
[Offboarding the integrator](offboarding.md).

## The auth model

SCIM requests do **not** carry user JWTs. Each tenant mints one or more opaque bearer
tokens; your IdP presents such a token on every `/scim/v2/*` call:

- **Minting requires `lm_admin`** (normal user-JWT auth), and only for your *own* tenant —
  the `{tenantId}` in the path must match the caller's authenticated tenant, otherwise 403.
- The plaintext token (`lmscim_<43 url-safe chars>`) is returned **once** at mint. Only its
  SHA-256 hash is stored; it is never logged and never shown again. Lose it → revoke and
  mint a new one.
- On an inbound `/scim/v2/*` request, `ScimAuthFilter` hashes the presented bearer and looks
  it up. A match resolves the **tenant** and the **issuer** the token was minted for; all
  database work in that request then runs under that tenant's Row-Level Security policy.
  The regular JWT filter (`TenantContextFilter`) recognises the `/scim/v2/*` path and steps
  aside — SCIM is the only auth path for those routes. Missing, unknown, or revoked token
  → `401` with a SCIM-shaped error body.
- A token is also scoped to one **issuer**: it can only see and touch users whose identity
  was keyed to that issuer. It cannot reach local users, another IdP's users, or another
  tenant's users — such lookups return a SCIM `404`, not a leak.

Revocation is immediate: `DELETE` on the token sets `revoked_at`, and the very next lookup
rejects it.

## Wiring your IdP

1. **Mint a token** (as an `lm_admin` of the tenant). The `issuer` must be the *exact*
   `iss` value of the JWTs your IdP sends to the hub — SCIM (`idp_issuer`) and JIT login
   (token `iss`) must key the same `(idp_issuer, idp_subject)` row, or SCIM will manage a
   parallel set of users that logins never hit.

   ```bash
   curl -X POST "https://<hub>/api/tenants/<tid>/scim-tokens" \
     -H "Authorization: Bearer <your lm_admin JWT>" \
     -H "Content-Type: application/json" \
     -d '{"issuer": "https://idp.example.com/realms/acme", "label": "okta-prod"}'
   # → 201 {"id":1,"issuer":"…","label":"okta-prod","token":"lmscim_…"}   ← save the token NOW
   ```

2. **Configure the IdP's SCIM provisioning app**:
   - Base URL: `https://<hub>/scim/v2`
   - Authentication: OAuth Bearer Token (a.k.a. "HTTP Header" / "Bearer") with the
     `lmscim_…` value.
   - Content type: `application/scim+json` (PATCH also accepts `application/json`).

3. **Map attributes**: `externalId` must be the same value as the JWT subject claim
   (`sub` by default) — it is the join key. `userName`, `displayName` / `name`, and the
   primary `emails` entry are synced as profile data.

4. **Verify**: most IdPs run a connectivity test against
   `GET /scim/v2/ServiceProviderConfig` (no auth surprises there — it declares patch and
   filter supported; bulk, sort, etag, and changePassword unsupported), then a dedup
   lookup like `GET /scim/v2/Users?filter=userName eq "jdoe"` before creating.

The end-to-end proof lives in the Helm smoke test (`deploy/helm/up.sh`, the
"SCIM deprovisioning loop"): provision → login 200 → `PATCH active:false` → login 403.

## The `/scim/v2/Users` contract

| Verb | Path | What it does |
|---|---|---|
| `POST` | `/scim/v2/Users` | Find-or-create by `(issuer, externalId)`, then apply `active` + profile from the body. `201` with the representation. Authoritative: a re-POST **revives** a soft-deleted user and may re-activate an archived one. `externalId` is required. |
| `GET` | `/scim/v2/Users/{id}` | One user by lumnik id. `404` if absent, deleted, or keyed to a different issuer. |
| `GET` | `/scim/v2/Users?filter=…` | **Only** `externalId eq "x"` and `userName eq "x"` (the dedup-before-create forms Okta/Entra use). Returns 0 or 1 result. Any other filter expression returns an **empty list**, not an error. Soft-deleted users are excluded; deactivated ones still appear (re-provisionable). |
| `PUT` | `/scim/v2/Users/{id}` | Full replace: applies `active`, display name, and primary email from the body. |
| `PATCH` | `/scim/v2/Users/{id}` | The deprovisioning operation: `replace` of the top-level `active` attribute only (path is case-insensitive). A non-boolean `active`, or any op other than `replace` targeting it, is rejected with `400` — a malformed value is never coerced into a deactivation. Operations on **other paths are silently ignored**. |
| `DELETE` | `/scim/v2/Users/{id}` | **Soft**-delete, `204`. The user drops out of SCIM listings; the row itself remains, keeping the `(issuer, externalId)` key occupied so a later login resolves to the deleted row and is refused — see [GDPR & personal data](gdpr.md). |

**Deactivate vs erase — what each does:**

- **`active: false` archives the user** — the operation your IdP should send on departure.
    - **The row and its history stay intact**: `archived_at` is stamped (and `updated_at`
      bumped, like any update); `created_at` never changes.
    - **Denial is immediate, whatever the token's shape**: the request filter checks the
      archived state on every call — a `user_id`-claim token (what the bundled realm
      emits) and an external `(issuer, subject)` identity are both refused with the same
      `403 {"error":"user is deactivated"}`, still-valid token or not.
    - **JIT never reactivates** — only SCIM, being authoritative, can push the user back
      into service with `active: true`.
    - **The SCIM write itself leaves no audit trail**: an admin-driven archive lands in
      `core.audit_event` (`identity.app_user` is `@Auditable`), but a SCIM-driven write
      does not — the SCIM filter never sets the request context the audit listener
      reads, so it skips the very deprovisioning event an access reviewer most wants to
      see. See [GDPR & personal data → the audit trail](gdpr.md#how-the-audit-trail-is-written).
- `DELETE` soft-deletes. It is reversible by a SCIM re-POST of the same
  `(issuer, externalId)`; nothing hard-deletes the row automatically.
- Neither is a GDPR erasure. No erase endpoint exists today — removing a soft-deleted
  row for good is an operator action, documented in
  [GDPR & personal data](gdpr.md).

## What is NOT supported

Deliberately scoped to deprovisioning; the hub does not pretend otherwise:

- **Groups** — no `/scim/v2/Groups` resource. Roles are managed in the IdP realm
  ([role management](role-management.md)), not pushed over SCIM.
- **Bulk operations, sorting, etags, changePassword** — declared unsupported in
  `ServiceProviderConfig`, and genuinely absent.
- **Pagination** — no `startIndex`/`count`; the only list forms are the two `eq` filters
  above, which return at most one result.
- **The full SCIM PATCH path grammar** — e.g. `emails[type eq "work"].value` is ignored.
  Full profile replacement goes through `PUT`.
- **`/scim/v2/Schemas` and `/scim/v2/ResourceTypes`** — not implemented. IdPs that
  hard-require schema discovery need their attribute mappings configured by hand.
- **Unfiltered `GET /scim/v2/Users`** returns an empty list, not the tenant's user
  population.

## Token lifecycle

All three endpoints require `lm_admin` and your own tenant id in the path:

```bash
# Mint — plaintext token in the response ONCE, never retrievable again
POST   /api/tenants/{tid}/scim-tokens          {"issuer": "<idp iss>", "label": "okta-prod"}

# List active tokens — id, issuer, label, createdAt; never the hash, never the plaintext
GET    /api/tenants/{tid}/scim-tokens

# Revoke — effective immediately, 204
DELETE /api/tenants/{tid}/scim-tokens/{id}
```

Rotation is mint-then-revoke: mint the new token, switch the IdP over, revoke the old one.
Several tokens can be active at once (use `label` to tell them apart), so rotation needs
no provisioning outage.

## Troubleshooting

| Symptom | Likely cause |
|---|---|
| Plain `404`/no route on every `/scim/v2/*` call — not the SCIM-shaped error body above | Running the open-source edition: the SCIM resource classes are excluded from that build (see the edition note at the top of this page). Deploy the default self-host/Helm build instead. |
| `401 invalid SCIM token` on `/scim/v2/*` | Missing `Authorization: Bearer` header, a token that was never minted for this hub, or a **revoked** token. The response is identical in all three cases by design. |
| `403` when minting/listing/revoking tokens | Your JWT lacks `lm_admin`, or the `{tenantId}` in the path is not your own tenant (an `lm_admin` cannot manage another tenant's SCIM credentials). |
| `404` on `GET/PUT/PATCH/DELETE /scim/v2/Users/{id}` | No such user in this tenant, the user was soft-deleted, **or the user's `idp_issuer` differs from the token's issuer** — the most common wiring mistake. Check that the token's `issuer` equals the exact `iss` of your IdP's JWTs, byte for byte (scheme, host, port, path). |
| IdP dedup search finds nothing, then `POST` returns an existing user | Normal: the filter forms exclude soft-deleted rows, and `POST` is find-or-create — it revives/updates rather than duplicating. |
| Deactivated user can still call the API | A request already executing when the archive lands finishes; every request *after* it is denied at the filter, still-valid token or not. If later calls still succeed, verify the PATCH actually landed (`GET /scim/v2/Users/{id}` → `"active": false`), and that the issuer matches (see the `404` row). |

## Relationship to JIT arrival

Arrival and departure are two halves of one identity key. On first login through your IdP,
`JitUserProvisioner` creates the lumnik user keyed on `(idp_issuer, idp_subject)`; SCIM
addresses the same row through `(issuer, externalId)`. JIT is deliberately passive: it
creates, but never reactivates or recreates — once SCIM (or an admin) has archived or
deleted a user, only SCIM can bring them back. For the login side — brokered IdPs,
issuer configuration, the subject claim — see [BYO IdP](byo-idp.md).

## See also

- Token endpoints: `lumnik-hub/src/main/java/io/lumnik/hub/pro/scim/ScimTokenResource.java`
- The SCIM surface: `lumnik-hub/src/main/java/io/lumnik/hub/pro/scim/ScimUserResource.java`
- The live proof: `deploy/helm/up.sh` — the `oidc-external` smoke test's SCIM loop
