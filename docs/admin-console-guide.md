# Admin API — Operator & Integrator Guide (backend reference)

> **Audience**: Platform operators, system integrators, DevOps engineers  
> **Scope**: Platform management. Tenant listing and tenant impersonation (a JWT claim) are `lm_superadmin`; the
> Integration Hub and business lists are **`lm_admin`**; the `/api/platform/*` surface the `lm`
> CLI drives is `lm_integrator`. The roles are separate, not ranked — see
> [Who can call what](#who-can-call-what)  
> **Version**: 1.0

!!! warning "No admin UI ships in v1"
    The backend API documented here (`/api/platform/admin/*`,
    integrations, webhooks) is **real and verified**. The web console it was designed
    for is **not shipped** — no frontend service exists in the self-host deploy.
    Passages describing UI behavior (routes under `/admin`, `localStorage`, guards)
    describe the future console; read them as API semantics. Tenant impersonation is a
    **JWT claim**, not an endpoint — see
    [Act on behalf of a tenant](#act-on-behalf-of-a-tenant-impersonation). Drive
    everything below with `curl` or the `lm` CLI.

---

## How to…

Every call carries a Bearer JWT (`Authorization: Bearer $TOKEN`) — the same token
`lm login` obtains. Two values recur below and both come from one call:

```bash
curl -s http://localhost:8080/api/bff/v1/me \
     -H "Authorization: Bearer $TOKEN" | jq '{tenant: .tenant.id, roles}'
```

`tenant.id` is the `{tid}` every tenant-scoped path wants; `roles` is what you actually
hold, which is the first thing to check when a call answers `403`. (That endpoint itself
requires `lm_user`.)

### Register a webhook subscription so decisions leave the hub

Every `decision.*` event — a firing `kind:Process`, and equally a `kind:Workflow` dormancy
alert (`decision.dormant.<workflow>`) — always lands in the outbox and in `lm inbox`.
Reaching an external system — n8n, Make, Zapier, Teams, your own endpoint — additionally
needs a webhook subscription registered for the tenant. **None is seeded by default**, which
is why "my team gets told" is not a promise until someone performs this gesture.

!!! warning "A subscription is not retroactive"
    Only events fired **after** the subscription exists are delivered. When an event finds no
    matching subscription, the consumer records nothing at all — no delivery row is written
    and the outbox cursor moves on — so there is nothing left to replay afterwards. Decisions
    already sitting in `lm inbox` stay there; register the subscription, then triage the
    backlog by hand from the inbox.

It needs the **`lm_admin`** role. Roles are not hierarchical here — `lm_superadmin` alone
does *not* open this surface.

**The lm gesture** (the API calls below remain the reference):

```bash
printf '%s\n' "$WEBHOOK_SECRET" | lm integration add n8n-decisions \
    --url https://n8n.example.com/webhook/lumnik --events 'decision.*' --secret-stdin
lm integration list                 # ID, name, URL, events, enabled
lm integration test <ID>           # fires a catalog event, reports the delivery
lm integration rm <ID>             # delivery history is kept
```

The secret is read from **stdin** — never a flag, never argv. `lm outputs list` remains
the delivery journal for what was actually sent.

**1 — Create the subscription (API).**

```bash
curl -s -X POST "http://localhost:8080/api/tenants/$TID/integrations" \
     -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{
           "name": "n8n — decisions",
           "targetUrl": "https://n8n.example.com/webhook/lumnik",
           "platform": "n8n",
           "authMode": "HMAC",
           "secret": "a-long-random-string",
           "eventTypes": ["decision.*"],
           "enabled": true
         }' | jq
```

`201` returns the integration record, including its `id` and `hasSecret: true` — the
secret itself is never echoed back.

An entry of `eventTypes` is either an exact type (`org.created`) or a prefix pattern
ending in `.*` (`decision.*`), which matches every type starting with that prefix. Only
`name`, `targetUrl` and `eventTypes` are genuinely required; omitting `platform`,
`authMode` or `enabled` takes the defaults in
[The integration record](#the-integration-record).

**2 — Prove the wire, with an event the catalog knows.**

```bash
curl -s -X POST "http://localhost:8080/api/tenants/$TID/integrations/$ID/test" \
     -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"eventType": "org.created"}' | jq
```

This fires a real HTTP POST at `targetUrl` with a synthesised sample payload and returns
`{ok, httpStatus, responseBody, error, durationMs}`. It is the way to confirm that your
receiver is reachable and that its HMAC check agrees with ours.

!!! note "The test event cannot be a `decision.*` one"
    The sample payload is built from the platform event catalog, so `POST …/test` only
    accepts a type declared in a module's `META-INF/events.yaml` — an unknown one is
    refused with a body-less `400` (the log records `Unknown event type: …`). `decision.*`
    types are declared by `kind:Process` and `kind:Workflow` manifests, not by that catalog,
    so they are **not testable this way**.
    Subscribe with the pattern regardless: matching happens at fire time against the event
    that actually occurred, not against the catalog. Verify with a real decision, then read
    [what was sent](#see-what-was-sent-and-re-send-what-failed).

### See what was sent, and re-send what failed

**A different role from step 1.** This one reads `/api/platform/deliveries`, which sits
behind the integrator gate: it needs **`lm_integrator`** — plus `lm_admin` or
`lm_superadmin` on top. A token that could create the subscription is not necessarily a
token that can inspect the deliveries, and the refusal (`403 missing role lm_integrator`,
plain text) arrives at the worst moment: mid-diagnosis. Check both roles at once with
`/api/bff/v1/me` before you start.

Across every integration at once, failures first:

```bash
lm outputs list
```

or the endpoint behind it, `GET /api/platform/deliveries` — the tenant comes from your
token, never from the URL. In the TUI, the `:outputs` screen shows the same list with `^R`
to replay a failed delivery in place. To replay one by hand:

```bash
curl -s -X POST "http://localhost:8080/api/platform/deliveries/$DELIVERY_ID/replay" \
     -H "Authorization: Bearer $TOKEN" | jq
```

To look at **one** integration in isolation, including only its failures, use
[Delivery history](#delivery-history).

### Rotate an integration's secret

There is no separate rotate endpoint — update the integration with a new `secret`:

```bash
curl -s -X PUT "http://localhost:8080/api/tenants/$TID/integrations/$ID" \
     -H "Authorization: Bearer $TOKEN" \
     -H 'Content-Type: application/json' \
     -d '{"secret": "the-new-shared-secret"}' | jq
```

Every field of the update body is optional and only the ones present are applied; a
`secret` that is null or blank is ignored, so an update can never silently erase one.
Rotate the receiver's copy in the same window — deliveries signed with the new secret start
immediately.

### Find the event types you can subscribe to

Also behind the integrator gate — this one needs **`lm_integrator`** and nothing more.

```bash
curl -s http://localhost:8080/api/platform/events \
     -H "Authorization: Bearer $TOKEN" | jq '.[].type'
```

This reads the registry the running hub actually loaded, so it cannot drift the way a
written table can. See [What can be sent: the event catalog](#what-can-be-sent-the-event-catalog).

---

## Who can call what

### The role gate

Access to the SuperAdmin surface is gated on the `lm_superadmin` Keycloak role, read from
the bearer JWT's realm roles. The backend enforces it on all `/api/platform/admin/*`
endpoints.

Roles are matched literally, with **no hierarchy**: a resource requiring `lm_admin` is
closed to a caller holding only `lm_superadmin`, and vice versa. A refusal is a bare `403`
with an **empty body** — nothing on the wire says which role was missing. (The hub's log
records `Insufficient permissions`; the empty body is itself the signal, and it is what
tells this refusal apart from a métier-scope one, which answers
`{"error":"scope not granted"}`, and from the `/api/platform/*` gate, which answers the
plain text `missing role lm_integrator`.)

### Dev bypass mode

In development, the application can run with a bypass that activates all three roles simultaneously:

| Role | Purpose |
|---|---|
| `lm_superadmin` | Admin Console access, tenant impersonation |
| `lm_admin` | Tenant-level admin operations |
| `lm_user` | Standard authenticated user |

This allows a single dev user to exercise the full surface of the application without configuring Keycloak roles. The bypass is controlled by an environment/build flag and must never be enabled in production.

### The `/api/platform/*` gate

Everything under `/api/platform/*` is gated by `PlatformApiAuthFilter`: an unauthenticated
call gets `401` (with a `WWW-Authenticate: Bearer` challenge), an authenticated caller
without the `lm_integrator` role gets `403`. In dev the gate is bypassed via
`lumnik.security.dev-bypass=true`. These are the endpoints the `lm` CLI/TUI drives.

The filter is a *floor*, not the whole gate: a resource under that prefix may require more
on top. `/api/platform/admin/*` needs `lm_integrator` **and** `lm_superadmin`;
`/api/platform/deliveries` needs `lm_integrator` **and** either `lm_admin` or
`lm_superadmin`.

### SuperAdmin-only endpoints

| Method | Path | Permission |
|---|---|---|
| `GET` | `/api/platform/admin/tenants` | `lm_superadmin` |

---

## Act on behalf of a tenant (impersonation)

Impersonation lets a superadmin act on a specific tenant without logging in as one of its
users. It switches the **tenant**, never the roles: the roles in your own token are the
ones checked while you act.

**The mechanism is a JWT claim, minted by your IdP** — `acting_as_tenant: <tid>` on a
token whose realm roles include `lm_superadmin`. The hub refuses the claim on any lesser
token (`403`, pinned by an integration test under the no-bypass posture) and honours it on
a superadmin's: every request with that token reads and writes as the named tenant, RLS
included. There is no hub endpoint that mints impersonation tokens — an earlier
`/api/platform/admin/impersonate` endpoint returned an unsigned JSON object nothing in
production verified, and was removed rather than left as inert surface. In development
(`lumnik.security.dev-bypass=true` only) the `X-Impersonate-Tenant` header plays the same
role without an IdP.

---

## Send events out to another system

The Integration Hub is where a tenant's outbound webhook integrations are configured. Each integration subscribes to one or more event types and delivers signed HTTP payloads to an external URL.

The whole surface requires the **`lm_admin`** role, and the `{tid}` in the path must be the
tenant your token resolves to — any other value is refused with a body-less `403` (the log
records `Tenant mismatch`). That includes a SuperAdmin who has not impersonated that tenant
first; and impersonating it is not enough on its own, since impersonation switches the
tenant, not the roles.

### Endpoints

| Method | Path | Description |
|---|---|---|
| `GET` | `/api/tenants/{tid}/integrations` | List integrations for a tenant |
| `POST` | `/api/tenants/{tid}/integrations` | Create a new integration; `201` |
| `GET` | `/api/tenants/{tid}/integrations/{id}` | One integration by id. `404` only if the id does not exist at all — unlike the list, this lookup does not filter soft-deleted rows, so a deleted integration still answers `200` here |
| `PUT` | `/api/tenants/{tid}/integrations/{id}` | Update the fields present in the body; also how a secret is rotated |
| `DELETE` | `/api/tenants/{tid}/integrations/{id}` | Soft-delete (the delivery history is kept); `204`. Deletion stops **every** send: retries already in flight give up, and replay and test fire answer `404` |
| `POST` | `/api/tenants/{tid}/integrations/{id}/test` | Fire a test event |
| `GET` | `/api/tenants/{tid}/integrations/{id}/deliveries` | Delivery history |
| `POST` | `/api/tenants/{tid}/integrations/{id}/deliveries/{did}/replay` | Replay a delivery |
| `GET` | `/api/platform/deliveries` | Delivery history across **all** integrations, failures first (tenant from the token) (also requires `lm_integrator`, as all `/api/platform/*`) |
| `POST` | `/api/platform/deliveries/{id}/replay` | Replay one delivery, addressed by delivery id (also requires `lm_integrator`, as all `/api/platform/*`) |

### The integration record

```json
{
  "name": "My Zapier Hook",
  "targetUrl": "https://hooks.zapier.com/hooks/catch/...",
  "platform": "zapier",
  "authMode": "HMAC",
  "secret": "my-shared-secret",
  "eventTypes": ["org.created", "org.updated"],
  "enabled": true
}
```

| Field | Type | Required | Description |
|---|---|---|---|
| `name` | string | yes | Human-readable label, unique within the tenant |
| `targetUrl` | string | yes | Destination URL for webhook delivery |
| `platform` | string | no — defaults to `generic` | Platform preset: `generic`, `make`, `zapier`, `n8n` |
| `authMode` | enum | no — defaults to `HMAC` | One of `HMAC`, `BEARER`, `NONE` |
| `secret` | string | conditional | Required for `HMAC` and `BEARER` modes |
| `eventTypes` | string[] | yes | Exact types (`org.created`) or `.*`-suffixed prefix patterns (`decision.*`) to subscribe to |
| `enabled` | boolean | no — defaults to `true` | Whether delivery is active |

On `PUT`, every field is optional and only those present are applied. A null or blank
`secret` is ignored rather than stored, so an update cannot erase one by omission.

### Authentication modes

#### HMAC

The delivery adds a signature header computed from the raw payload and the stored secret:

```
X-Lumnik-Signature: sha256=<hex-encoded HMAC-SHA256>
```

The receiving service must verify this signature using the same shared secret. See
[The HMAC signature](#the-hmac-signature) for the exact algorithm.

#### BEARER

The delivery adds a standard Authorization header:

```
Authorization: Bearer <secret>
```

#### NONE

No authentication header is added. Use only for endpoints that enforce their own access control at the network layer.

Every delivery, whatever the mode, also carries `Content-Type: application/json` and
`X-Lumnik-Event: <event type>`.

### Where the secret lives

Secrets are **never stored in plaintext**. They are encrypted at rest using PostgreSQL's `pgcrypto` extension:

```sql
pgp_sym_encrypt(secret, app_key)
```

The API never returns the secret value. Instead, the integration record exposes a boolean:

```json
{ "hasSecret": true }
```

To rotate a secret, update the integration with a new `secret` value.

### Fire a test event

```http
POST /api/tenants/{tid}/integrations/{id}/test
Content-Type: application/json

{ "eventType": "org.created" }
```

This fires a **real delivery attempt** to the configured `targetUrl` using the configured `authMode`. The result is recorded in the delivery history just like a production delivery. Use this to verify connectivity and signature verification before enabling the integration for live events.

The payload is synthesised from the event type's declared schema in the catalog, so the
type must be one the catalog knows — an unknown one is refused with a body-less `400`
(the log records `Unknown event type: …`).

### Delivery history

```http
GET /api/tenants/{tid}/integrations/{id}/deliveries
GET /api/tenants/{tid}/integrations/{id}/deliveries?status=failed
```

Each delivery record includes:

| Field | Description |
|---|---|
| `http_status` | HTTP response code from the target |
| `response_body` | First N bytes of the response body |
| `error` | Error message if the attempt failed at the transport level |
| `duration_ms` | Round-trip time in milliseconds |
| `attempted_at` | ISO-8601 timestamp of the attempt |

Filter by `status=failed` to surface actionable failures. `?limit=` caps the list
(default 50). The stored response body is truncated at 2000 characters.

### Replay a delivery

```http
POST /api/tenants/{tid}/integrations/{id}/deliveries/{did}/replay
```

Re-sends the original payload of delivery `did` to the configured `targetUrl`. Useful for recovering from transient failures. The replay is recorded as a new delivery entry; the original entry is preserved.

### Platform presets

The UI offers platform-specific presets that pre-fill recommended settings:

| Preset | Notes |
|---|---|
| `generic` | No preset defaults; fully manual configuration |
| `make` | Make.com (formerly Integromat) webhook receiver |
| `zapier` | Zapier catch hooks |
| `n8n` | n8n Webhook node |

Presets affect the UI only — the backend treats all integrations identically.

---

## What can be sent: the event catalog

### Ask the running hub

```http
GET /api/platform/events
```

Returns the full list of event types registered on this platform instance, including their module, type identifier, human-readable description, and payload schema.

**Example response entry:**

```json
{
  "module": "core",
  "type": "org.created",
  "description": "A new organisation was created.",
  "payload": {
    "orgId": "long",
    "tenantId": "long",
    "name": "string"
  }
}
```

### Where event types come from

Events are defined in `META-INF/events.yaml` files, one per module. These files are scanned at startup by `EventRegistry`, which builds the in-memory catalog. Adding a new event requires:

1. Adding an entry to the module's `events.yaml`
2. Redeploying the service (no database migration needed)

!!! note "The catalog is not the whole event namespace"
    Only the types in these YAML files are catalogued. Events declared elsewhere — the
    `decision.*` taxonomy emitted by `kind:Process` manifests and by the workflow dormancy
    sweep — are published into the same outbox and delivered to any subscription that
    matches them, but they do not appear in `GET /api/platform/events` and cannot be used
    with the test endpoint.

### The core events

#### Module: `core`

| Type | Description |
|---|---|
| `data.row.changed` | A genuinely new or changed row landed in a watched ingest table (emitted in the ingestion transaction) |
| `annotation.decision.acked` | A human treated a decision in the inbox |
| `connector.run.started` / `completed` / `partial` / `cancelled` / `failed` | Run lifecycle; `completed` carries the conservation counters. **`partial` is a terminal ending like the other three** — the run stopped below the target it gave itself, having written everything it read. A subscription that only listens for `completed` will not hear those runs, which is why the family is worth subscribing to with the `connector.run.*` glob |
| `platform.secret.created` / `updated` / `deleted` / `read` | Secret lifecycle (`read` on cache misses only) |
| `org.created` / `updated` / `moved` / `archived` | Organisation lifecycle |

`decision.*` types are the one dynamic family outside this catalog: each `kind:Process`
and `kind:Workflow` manifest names its own (`decision.litige`,
`decision.dormant.commande-lifecycle`, …). The authoritative catalog is always
`GET /api/platform/events` — it reads the registry that was actually loaded, so it cannot
drift from the code the way a written table can.

---

## How a delivery actually happens

### The outbox

Events are not delivered inline with the originating transaction. Instead, each event is written to the `core.event_outbox` table atomically with the operation that triggered it. This guarantees at-least-once delivery even if downstream services are temporarily unavailable.

`WebhookConsumer` sweeps `core.event_outbox` from a cursor every 5 seconds. For each new event it:

1. Matches the event type against all active integrations subscribed to it
2. Fires one HTTP POST per matching integration
3. Records the outcome in `core.webhook_delivery`
4. Advances the cursor past the event

The cursor advances whether or not the delivery succeeded — a failed delivery is retried
from its own `core.webhook_delivery` row, never by re-reading the event, so an endpoint
that is down cannot block the queue for the others.

### The HMAC signature

For integrations using `authMode: HMAC`:

```
signature = HmacSHA256(secret, raw_payload_bytes)
header_value = "sha256=" + hex(signature)
```

The `X-Lumnik-Signature` header is set to `header_value`. Receivers must compute the same HMAC using the shared secret and compare it in constant time.

### Retries

A failed delivery is retried automatically, but not indefinitely. A separate sweep runs
every 30 seconds and claims rows that are still unsuccessful and have had fewer than
**3 attempts** — the first, inline attempt included. Each row is claimed under a 60-second
lease, delivered with no transaction held, and its outcome written back; a process that
dies mid-flight releases the lease and the next sweep picks the row up again.

After the third attempt the row stops being retried and must be replayed explicitly, via
[Replay a delivery](#replay-a-delivery) or `POST /api/platform/deliveries/{id}/replay`.

Two things also cut the retries short. If, between attempts, the integration stopped
matching that event — switched to `enabled: false`, or its `eventTypes` narrowed — the row
is closed with `endpoint gone, unsubscribed, or entity_filter no longer matches — retries
stopped`. And HTTP is bounded per attempt: 5 seconds to connect, 10 seconds for the
exchange.

For time-sensitive integrations, monitor the delivery history for failures and set up alerting on the `status=failed` query.

Since 2026-08-15 that monitoring has a first-class surface: `GET /api/platform/deliveries`
returns every integration's deliveries at once, **failures first**, with the tenant taken from
the token rather than the URL. `lm outputs list` prints it, and the `:outputs` screen in the TUI
shows it with `^R` to replay a failed delivery in place. The per-integration history in
[Delivery history](#delivery-history) remains the way to inspect one integration in isolation.

---

## Watch ingestion: runs, dead letters, live events

These are the endpoints the `lm` CLI/TUI drives; all of them sit behind the
[`/api/platform/*` gate](#the-apiplatform-gate).

### Live event stream (SSE)

```http
GET /api/platform/events/stream?topic=connector.run.*
Accept: text/event-stream
```

Served by `EventStreamResource`. Streams platform events as Server-Sent Events, each
element a JSON envelope. This is how the TUI `:runs` / `:dlq` / `:inbox` views live-update.

| Aspect | Behaviour |
|---|---|
| `topic` parameter | Repeatable (`?topic=a&topic=b`); `*` is a glob wildcard matched against the full event type (e.g. `connector.run.*`). No `topic` = all events. |
| Source | Polls `core.event_outbox` every second via the admin datasource (BYPASSRLS) — events across **all** tenants; batches up to 500 rows per tick. |
| Envelope | `{"topic": "<event_type>", "payload": "<JSON string>", "capturedAt": "<ISO-8601>"}` — note `payload` arrives as a JSON *string*, parse it client-side. |
| Permission | `lm_integrator` |

### Runs

Served by `RunAdminResource`. Read-only run transparency.

| Method | Path | Permission | Contract |
|---|---|---|---|
| `GET` | `/api/platform/runs?limit=` | `lm_integrator` | List runs (default 50, capped at 200) |
| `GET` | `/api/platform/runs/{id}` | `lm_integrator` | One run; `404` if unknown |

Each run record (`RunAdminDto`) carries the conservation fields:

| Field | Description |
|---|---|
| `id`, `connectorId` | Run and connector UUIDs |
| `status` | Run status |
| `startedAt`, `endedAt` | ISO-8601 timestamps |
| `recordsIn` | Rows read from the source |
| `recordsOut` | Rows written to the hub |
| `recordsSkipped` | Rows skipped for a counted reason — filters, validation rejects (conservation: in = out + skipped) |
| `deadLetterCount` | Rows captured in the DLQ for this run |
| `triggeredBy` | Who/what started the run |
| `chunkCount` | Number of transactional chunks |
| `errorMessage` | Failure detail, if any |

### Dead-letter queue

Served by `DlqAdminResource`. Rows a run could not ingest, with the offending payload.

| Method | Path | Permission | Contract |
|---|---|---|---|
| `GET` | `/api/platform/dlq?limit=&connector=&run=` | `lm_integrator` | List entries (default 100, capped at 200) |
| `GET` | `/api/platform/dlq/{id}` | `lm_integrator` | One entry incl. `payload` + `skipDetail`; `404` if unknown or erased |
| `POST` | `/api/platform/dlq/{id}/replayed?newRunId=` | `lm_integrator` | Mark an entry as replayed (optionally linking the new run); `204` |
| `POST` | `/api/platform/dlq/{id}/discard` | `lm_integrator` | Discard an entry (resolution recorded, not deleted); `204` |

List filter semantics:

- `?run=` — full history for that run (all reasons and resolutions). Wins over `?connector=` when both are passed.
- `?connector=` — **pending queue only** (unresolved entries for that connector).
- No filter — all active entries; GDPR-erased (soft-deleted) rows are excluded from every branch.

Entries expose `skipReason`, `errorMessage`, `attempts`, `resolution`, `capturedAt`,
`payload` and `skipDetail`.

---

## Keep a connector credential: the secret store

Served by `SecretResource`. Named per-tenant secrets referenced by connector configs
(`secret:NAME`, resolved by `SecretResolver`). **Values are never returned** — no read endpoint exposes
`value`; reads return metadata only (`name`, `description`, timestamps, `rotatedAt`).

| Method | Path | Permission | Contract |
|---|---|---|---|
| `GET` | `/api/platform/secrets` | `lm_integrator` | List secrets (metadata only) |
| `GET` | `/api/platform/secrets/{name}` | `lm_integrator` | Describe one secret (metadata only); `404` if unknown |
| `POST` | `/api/platform/secrets` | `lm_integrator` + `lm_admin` | Create `{name, value, description?}`; `201`, `409` on duplicate name |
| `PUT` | `/api/platform/secrets/{name}` | `lm_integrator` + `lm_admin` | Rotate the value (sets `rotatedAt`); `204` |
| `DELETE` | `/api/platform/secrets/{name}` | `lm_integrator` + `lm_admin` | Soft-delete; `204`, `404` if unknown |

Values are encrypted at rest in-database with pgcrypto (`pgp_sym_encrypt`, AES-256)
under `lumnik.secret.master-key`. Create/rotate/delete each emit a platform event.

---

## Trace a citation back to its source row

```http
GET /api/platform/data/row/{hash}
```

Served by `DataRowResource` — this is what `lm data row HASH` calls. Drills down from a
RAG citation's `row_hash` to the raw source row: chunk metadata → mapping →
dynamic table → `SELECT *` on the `_row_hash` match. Returns the full row as a flat
JSON object (all values stringified); `404` if the hash resolves to nothing in the
current tenant. Permission: `lm_integrator`; the dynamic-table lookup is tenant-scoped.

---

## Check migration drift

```http
GET /api/platform/migrations
```

Served by `MigrationAdminResource`. Read-only Flyway status, one entry per schema:

```json
{ "schema": "core", "currentVersion": "42", "pending": 0, "lastApplied": "..." }
```

There is **no apply endpoint** — migrations are applied at startup by
`PlatformFlywayRunner`; this endpoint only reports drift. Permission: `lm_integrator`.

---

## Manage tenants, orgs and users

Served by the `lumnik-identity` module (`TenantResource`, `OrgResource`, `UserResource`,
`RoleResource`, `UserRoleResource`, `RoleOrgAccessResource`). These endpoints live
**outside** `/api/platform/*`, but each carries a class-level `@RequiresPermission`:
`TenantResource` (`/api/tenants`) requires `lm_superadmin` — it enumerates and mutates
every tenant, so only a platform superadmin may call it. The tenant-scoped resources
(`OrgResource`, `UserResource`, `RoleResource`, `UserRoleResource`,
`RoleOrgAccessResource`) require `lm_admin`. Isolation within a tenant is additionally
enforced by tenant-scoped RLS plus explicit tenant checks in each resource. All deletes
are soft (archive), never hard.

### Tenants

| Method | Path | Contract |
|---|---|---|
| `GET` | `/api/tenants` | List active tenants |
| `GET` | `/api/tenants/{id}` | One tenant; `404` if unknown |
| `POST` | `/api/tenants` | Create `{code, name, description?}`; `201`, `409` on duplicate code |
| `PUT` | `/api/tenants/{id}` | Update name/description |
| `DELETE` | `/api/tenants/{id}` | Deactivate (soft delete); `204` |

### Organizations

| Method | Path | Contract |
|---|---|---|
| `GET` | `/api/tenants/{tid}/orgs` | List active orgs |
| `GET` | `/api/tenants/{tid}/orgs/{id}` | One org; `404` if unknown or wrong tenant |
| `GET` | `/api/tenants/{tid}/orgs/tree?root=&depth=` | Org tree (default depth 3), optionally rooted |
| `POST` | `/api/tenants/{tid}/orgs` | Create; `409` on duplicate code; emits `org.created` |
| `PUT` | `/api/tenants/{tid}/orgs/{id}` | Update; emits `org.updated` (+ `org.moved` on parent change) |
| `DELETE` | `/api/tenants/{tid}/orgs/{id}` | Deactivate; emits `org.archived`; `204` |

### Users and roles

| Method | Path | Contract |
|---|---|---|
| `GET` | `/api/tenants/{tid}/users` | List active users |
| `GET` | `/api/tenants/{tid}/users/{id}` | One user; `404` if unknown or wrong tenant |
| `POST` | `/api/tenants/{tid}/users` | Create `{login, name, email?, description?}`; `409` on duplicate login |
| `PUT` | `/api/tenants/{tid}/users/{id}` | Update profile (name, email, description) |
| `POST` | `/api/tenants/{tid}/users/{id}/unlock` | Unlock a locked account |
| `DELETE` | `/api/tenants/{tid}/users/{id}` | Deactivate (soft delete); `204` |

lumnik stores **no credentials**: authentication is Keycloak/OIDC, and users are
JIT-provisioned on `(idp_issuer, idp_subject)` at first login. There is no
login+password endpoint.

Role management follows the same pattern under
`/api/tenants/{tid}/roles`, `/api/tenants/{tid}/users/{uid}/roles/{roleId}` (assign/remove)
and `/api/tenants/{tid}/roles/{roleId}/orgs/{orgId}` (grant/revoke org access).

---

## Turn a hook on or off, and read declared workflows

In the designed console these were two sub-views behind a **Hooks** / **Workflows**
segmented selector at `/admin/:tid/hooks`. Both surfaces are backed by real endpoints.

### Hooks

Lists every hook the platform loaded (from all the `META-INF/hooks.yaml` files).
Columns: `id`, `module`, `target`, `events`, `priority`, `enabled`. A per-row toggle
records a per-tenant override in `core.hook_tenant_override`. The override cache has a
60-second TTL — for other threads the change takes effect after 60 seconds at worst.
Clicking the info icon opens `HookDetailDialog` with the hook's full details.

### Workflows

Lists the declared lifecycles, per tenant. A workflow today is a `kind:Workflow` manifest
(YAML), declared per tenant and applied through `POST /api/workflows/apply`.

The full reference — fields, alias semantics, `provenance`, the data-truth report — lives
in [Workflows — declare](apps/workflows.md), not here. There is no longer any
hook ↔ transition binding: `WorkflowRegistry` no longer consults `HookRegistry`.

> Until 2026-08-04 this section described a pre-pivot mechanism — a classpath scan for
> Java enums annotated `@DocumentWorkflow` (resolved to entities such as `Repair`, a module
> long since removed), a Mermaid.js diagram, a per-transition hook count — all of it
> removed (`WorkflowRegistry` scans nothing any more).

### Endpoints

| Method | Path | Permission |
|---|---|---|
| `GET` | `/api/platform/workflows` | `lm_admin` or `lm_superadmin` |
| `GET` | `/api/platform/workflows/{name}` | `lm_admin` or `lm_superadmin` |
| `POST` | `/api/platform/hooks/{hookId}/toggle?tenantId=` | `lm_admin` |

### Navigation

| Path | View |
|---|---|
| `/admin/:tid/hooks` | Hooks & Workflows tab (Hooks / Workflows selector) |

---

## Harden the deployment

| Concern | Mitigation |
|---|---|
| Secret confidentiality | Secrets encrypted at rest with pgcrypto `pgp_sym_encrypt`; never returned by API |
| Impersonation scope | `acting_as_tenant` claim only honoured for callers with `lm_superadmin` role; other callers get `403` |
| Tenant isolation | Row-Level Security (RLS) on `core.webhook_delivery` prevents cross-tenant data access |
| Replay safety | Replay creates a new delivery record; original is immutable |
| Dev bypass | Dev bypass mode must not be deployed to production; activates all roles unconditionally |

Production deployments should:

- Rotate the pgcrypto encryption key on a schedule and re-encrypt existing secrets
- Audit impersonation events in the platform audit log
- Restrict `/api/platform/admin/*` at the network layer (e.g., internal VPC only) if SuperAdmin access from public internet is not required

---

## The console that was designed but not shipped

The **Admin Console** was designed as a SuperAdmin-only section of the lumnik frontend providing two distinct capability areas:

1. **Tenant Management** — view all tenants registered on the platform, inspect their details, and impersonate a tenant to act on their behalf.
2. **Integration Hub** — configure outbound webhook integrations per tenant, inspect delivery history, and replay failed deliveries.

The console is accessible at `/admin` and is only rendered when the authenticated user holds the `lm_superadmin` role. Regular tenant admins and users do not see this section.

The Admin Console is intentionally separate from the normal tenant-scoped UI. When a SuperAdmin impersonates a tenant, they enter the Admin Shell for that tenant and can access the Integration Hub scoped to it. All actions remain audited and tenant-isolated at the backend.

!!! warning "That last part is not what the shipped API does"
    Impersonation switches the **tenant**, never the roles. The Integration Hub endpoints
    are gated on `lm_admin`, so a SuperAdmin who holds only `lm_superadmin` is refused there
    even while impersonating. Read the console's design as design; the API contract is
    [Send events out to another system](#send-events-out-to-another-system).

The frontend reads the role from the JWT claim `roles` (realm roles array). The relevant check in the frontend guard:

```ts
roles.includes('lm_superadmin')
```

Its routes would have been:

| Path | View |
|---|---|
| `/admin` | Tenant List — SuperAdmin landing page showing all registered tenants |
| `/admin/:tid` | Admin Shell — per-tenant tabbed view; entry point for all tenant-scoped admin actions |
| `/admin/:tid/integrations` | Integration Hub — list and manage integrations for tenant `tid` |
| `/admin/:tid/integrations/:id/deliveries` | Delivery History — delivery log for a specific integration |

Navigation to `/admin` while lacking the `lm_superadmin` role redirects to the normal authenticated home page.

When impersonating, the red badge is visible on all `/admin/:tid/*` routes and provides a consistent one-click exit point.

---

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| `401` with `WWW-Authenticate: Bearer` on any `/api/platform/*` call | no authenticated principal on the request | the token expired or was never sent — run `lm login` again, or re-read it from `~/.lm/config.yaml` |
| `403 missing role lm_integrator` on `/api/platform/*` | authenticated, but the token does not carry `lm_integrator` | `GET /api/bff/v1/me` returns your `roles`; ask whoever manages your Keycloak realm to grant it |
| `403` with an **empty body** on `/api/tenants/{tid}/integrations` | the role gate: that surface requires `lm_admin`, and roles are not hierarchical — `lm_superadmin` alone does not open it. Nothing on the wire names the missing role (the log records `Insufficient permissions`) | check `roles` from `/api/bff/v1/me`, then have `lm_admin` granted |
| `403` with an **empty body** on `/api/tenants/{tid}/…` while you *do* hold `lm_admin` | the tenant check, not the role gate: the `{tid}` in the path is not the tenant your token resolves to (the log records `Tenant mismatch`) | use `tenant.id` from `/api/bff/v1/me`. Another tenant needs impersonation (`lm_superadmin`) — and `lm_admin` still on top of it, since impersonation switches the tenant, not the roles |
| `POST …/test` answers `400` with an **empty body** | the test payload is built from the catalog, and this type is not in any `META-INF/events.yaml` — `decision.*` types never are (the log records `Unknown event type: …`) | subscribe with the pattern anyway (matching happens at fire time) and verify with a real decision instead |
| a delivery row shows `HMAC mode but no secret configured` and no HTTP status | `authMode` is `HMAC` — the default when the field is omitted — but no `secret` was ever stored | `PUT` the integration with a `secret`, then replay the delivery. `Bearer mode but no token configured` is the same fault in `BEARER` mode |
| a failed delivery stops being retried | the retry sweep only claims rows with `succeeded = false` **and** fewer than 3 attempts | replay it explicitly once the receiver is back: `POST /api/platform/deliveries/{id}/replay` |
| a delivery closes with `endpoint gone, unsubscribed, or entity_filter no longer matches — retries stopped` | between the first attempt and the retry, the integration was switched to `enabled: false` or its `eventTypes` stopped matching that event | re-enable or re-subscribe the integration, then replay the delivery |
| `POST /api/platform/deliveries/{id}/replay` answers `404` for an id you can see | either the delivery belongs to another tenant — existence is not confirmed across a tenant boundary — or **the integration it was sent to has been deleted**: a deleted integration receives nothing more, by any door | take the id from your own `GET /api/platform/deliveries`; if it is yours, check the integration still exists with `GET /api/tenants/$TID/integrations` |
| sending `X-Impersonate-Tenant` changes nothing | that header is only read when `lumnik.security.dev-bypass=true` | in production, impersonation rides the `acting_as_tenant` JWT claim instead |
| `403 {"error": "lm_superadmin required to impersonate a tenant"}` | the bearer carries an `acting_as_tenant` claim but not the `lm_superadmin` role | the claim is refused by design — have the role granted, or drop the claim |
| `403` with an **empty body** adding or deleting a list *value* | the list has `system_owned=true`; its membership is owned by the code that mirrors it (the log records `Cannot add values to system-owned list` / `Cannot delete values of a system-owned list`) | rename the labels instead — that is allowed — or create a business list of your own |

### If none of that is it

Two things this page cannot do for you, and what exists instead.

**Check the contract against the running hub, not against this page.** Your own deployment
serves its live OpenAPI spec at `/q/openapi` and a try-it console at `/q/swagger-ui/` — both
shipped in the packaged hub. Every path, body and status code above came from the source, but
the spec your hub emits is the one that is true *for your build*. The
[test-the-API recipe](recipes/test-the-api.md) walks it.

**Reaching a human.** There is no general support channel for this edition — no ticket queue,
no chat. The one published address, `contact@lumnik.fr`, is the **security disclosure**
address in `SECURITY.md`, with a 72-hour acknowledgment; it is the right route if what you
found is a vulnerability, and the wrong one for a configuration question. If you obtained
lumnik through an integrator, they are your escalation path.
