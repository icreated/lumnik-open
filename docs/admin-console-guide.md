# Admin tasks — `lm` and the API behind it

Day-to-day administration of a running hub: webhooks, deliveries, runs, secrets, migrations,
modules, hooks, tenants. Every task below starts from an `lm` command or a TUI screen
(`lm ui`); raw HTTP appears only where no `lm` command exists.

No admin web console ships with lumnik.

## Who can call what

Roles are matched literally, with no hierarchy: `lm_superadmin` alone does not open an
`lm_admin` door, and the reverse.

| Task | Gesture | Roles needed |
|---|---|---|
| Wire webhooks | `lm integration` · `:integrations` | `lm_integrator` + `lm_admin`, no `scope:*` role (or `lm_admin` alone over `/api/tenants/{tid}/integrations`) |
| Read and replay deliveries | `lm outputs` · `:outputs` | `lm_integrator` + `lm_admin` (or `lm_superadmin`), no `scope:*` role |
| Runs and dead letters | `lm run`, `lm dlq` · `:runs`, `:dlq` | `lm_integrator` |
| Read secrets (metadata) | `lm secret list`, `describe` | `lm_integrator` |
| Create, rotate, delete secrets | `lm secret set`, `rm` | `lm_integrator` + `lm_admin` |
| Migrations, modules, hooks list, event catalog | `lm migration`, `lm module`, `lm hook list`, `GET /api/platform/events` | `lm_integrator` |
| Declared workflows | `lm workflow` | `lm_integrator` |
| Live event stream | the TUI, or `GET /api/platform/events/stream` | `lm_integrator`, no `scope:*` role |
| Toggle a hook for a tenant, read one hook | `POST /api/platform/hooks/{id}/toggle`, `lm hook get` | `lm_integrator` + `lm_admin` |
| Tenants | `/api/tenants` | `lm_superadmin` |
| Orgs, users, tenant roles | `/api/tenants/{tid}/…` | `lm_admin`, own tenant only |

A scope-bound integrator sees only the runs and dead letters of connectors whose scopes it holds
([Scopes](rag/scopes.md#binding-users-to-scopes-opt-in)).

No `lm` command prints your roles. Ask the hub (needs `lm_user`):

```
$ curl -s http://localhost:8080/api/bff/v1/me -H "Authorization: Bearer $TOKEN" | jq '{tenant: .tenant.id, roles}'
{
  "tenant": 1,
  "roles": [
    "lm_integrator",
    "lm_admin",
    "lm_user"
  ]
}
```

`tenant` is the `{tid}` the `/api/tenants/{tid}/…` paths want. Getting a token for `curl`:
[Test the API](recipes/test-the-api.md#mint-a-token).

## Send events out to another system

Register a webhook target (here a test receiver on port 18955). Always pass the signing
secret with `--secret-stdin`:

```
$ printf '%s' "$WEBHOOK_SECRET" | lm integration add ops-hook \
    --url http://localhost:18955/hook --events 'connector.run.*' --secret-stdin
registered: ops-hook (id 1001) — forwards subscribed events from now on

$ lm integration list
ID    NAME      URL                     EVENTS           ENABLED
1001  ops-hook  http://localhost:18955  connector.run.*  true

$ lm integration test 1001
delivered in 11ms (HTTP 200)
```

- `--events` takes exact types or a prefix ending in `.*`; the default is `decision.*`.
- `test` fires a catalog event (`--event`, default `org.created`). `decision.*` types are not in
  the catalog and cannot be tested this way: verify them with a real decision.
- Only events fired after registration are delivered.
- Delivery is at-least-once: a hub stop mid-send can deliver an event twice. Deduplicate on
  `X-Lumnik-Event-Id`.
- `lm integration describe ID` shows one registration; `lm integration rm ID` removes it
  (the delivery history is kept, pending retries stop).
- In `lm ui`, `:integrations`: `n` registers, `T` tests, `Del` removes, `d` shows recent deliveries.

Without `--secret-stdin`, the hub refuses the registration and stores nothing:

```
$ lm integration add erp-out --url https://erp.example.com/hook --events org.created
Error: API error 400: HMAC mode needs a secret (lm integration add: --secret-stdin)
```

What the receiver gets (headers, the HMAC signature, retries, retention):
[Outputs](outputs.md#wire-a-webhook).

### Settings `lm` does not expose

`lm integration add` always registers an enabled target in `HMAC` mode. For another mode, or to
change a registration, use the HTTP door `/api/platform/integrations`:

| Field | Values | Default |
|---|---|---|
| `name` | unique within the tenant | required |
| `targetUrl` | the receiver's URL | required |
| `eventTypes` | exact types or `.*` prefixes | required |
| `authMode` | `HMAC` (`X-Lumnik-Signature: sha256=…`), `BEARER` (`Authorization: Bearer <secret>`), `NONE` | `HMAC` |
| `secret` | the HMAC key or the bearer token; never returned (`hasSecret: true`) | none |
| `platform` | `generic`, `make`, `zapier`, `n8n` — a label only (`lm integration add --platform`) | `generic` |
| `enabled` | `true` / `false` | `true` |

Rotate a target's secret (a `PUT` applies only the fields present; a blank `secret` is ignored):

```
$ curl -s -X PUT http://localhost:8080/api/platform/integrations/1001 \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d '{"secret":"the-new-shared-secret"}' | jq
{
  "id": 1001,
  "name": "ops-hook",
  "targetUrl": "http://localhost:18955/hook",
  "platform": "generic",
  "authMode": "HMAC",
  "eventTypes": [
    "connector.run.*"
  ],
  "enabled": true,
  "hasSecret": true
}
```

Change the receiver's copy in the same window: the next delivery is signed with the new secret.

## Watch deliveries and replay a failure

```
$ lm outputs list
ID    INTEGRATION  EVENT                    STATUS       WHEN              ERROR
1006  ops-hook     connector.run.completed  no response  2026-10-19 01:46  connection failed (ConnectException)
1005  ops-hook     connector.run.started    no response  2026-10-19 01:46  connection failed (ConnectException)
1001  (deleted)    connector.run.completed  no response  2026-10-19 01:44  endpoint gone, unsubscribed, or entity_filter no longer matches — retries stopped
1000  (deleted)    connector.run.started    no response  2026-10-19 01:44  endpoint gone, unsubscribed, or entity_filter no longer matches — retries stopped
1003  ops-hook     connector.run.completed  ok           2026-10-19 01:45
1002  ops-hook     connector.run.started    ok           2026-10-19 01:45
1004  ops-hook     connector.run.completed  ok           2026-10-19 01:44
```

Failures come first. A failed delivery is retried automatically, 3 attempts in all, about 30 s
apart. After the third, replay it once the receiver is back:

```
$ lm outputs replay 1006
replayed: delivery 1006 — a new attempt is on record

$ lm outputs list
ID    INTEGRATION  EVENT                    STATUS       WHEN              ERROR
1006  ops-hook     connector.run.completed  no response  2026-10-19 01:46  connection failed (ConnectException)
…
1007  ops-hook     connector.run.completed  ok           2026-10-19 01:46
```

The replay is a new row (`1007`); the original row stays as it was, still listed as failed.
In `lm ui`, `:outputs` shows the same list; `^R` replays the selected delivery.

| `ERROR` column | Meaning | Gesture |
|---|---|---|
| `connection failed (ConnectException)` | the receiver did not answer | bring it back, then `lm outputs replay ID` |
| `HMAC mode but no secret configured` | the target was registered without a secret, before the hub refused that (`Bearer mode but no token configured`: same, in `BEARER` mode) | set a secret (`PUT` above) or re-register with `--secret-stdin`, then replay |
| `endpoint gone, unsubscribed, or entity_filter no longer matches — retries stopped` | the target was removed, disabled, or no longer subscribes to that event | re-enable or re-subscribe, then replay; a removed target accepts no replay (`404`) |

## Find the event types you can subscribe to

No `lm` command lists the catalog; the running hub serves it:

```
$ curl -s http://localhost:8080/api/platform/events -H "Authorization: Bearer $TOKEN" | jq -r '.[].type'
org.created
org.updated
org.moved
org.archived
connector.run.started
connector.run.completed
connector.run.partial
connector.run.cancelled
connector.run.failed
platform.secret.created
platform.secret.updated
platform.secret.deleted
platform.secret.read
data.row.changed
annotation.decision.acked
```

Each entry also carries its module, a description and the payload fields. `decision.*` types
are not in this list: each `kind:Process` and `kind:Workflow` names its own, and a `decision.*`
subscription matches them when they fire. What each type means:
[the event registry](outputs.md#the-event-registry).

## Watch ingestion: runs, dead letters, live events

| Gesture | What it does |
|---|---|
| `lm run list [--connector NAME]` | recent runs, newest first: IN, OUT, SKIPPED, DEAD-LETTERS |
| `lm run get ID` | one run, with its failure reason or quarantine warning |
| `lm run cancel ID` | asks an in-flight run to stop |
| `lm dlq list --run ID` / `--connector NAME` | the quarantined rows, with their reason and RESOLUTION (`REPLAYED`/`DISCARDED`, empty while open) |
| `lm dlq get ID` | one entry, with the row as the source sent it |
| `lm dlq replayed ID --new-run-id RUN` · `lm dlq discard ID` | mark an entry handled |

`--connector` lists only the entries still pending; `--run` lists the run's full history, handled
entries included.
Every run id in these gestures (`--run`, `--new-run-id`) takes the 8 characters `lm run list` shows.

The full walk, with captures: [Diagnosing a failed run](recipes/diagnose-a-run.md).

In `lm ui`, `:runs`, `:dlq`, `:sources` and `:endpoints` update by themselves while runs happen.
They follow the live event stream, which your own tooling can read too:

| | |
|---|---|
| Door | `GET /api/platform/events/stream?topic=connector.run.*` (`Accept: text/event-stream`) |
| `topic` | repeatable; `*` is a wildcard over the event type; none = every event |
| Each element | `{"topic": "<type>", "payload": "<JSON as a string>", "capturedAt": "<ISO-8601>"}` |
| Scope-bound caller | `403 {"error":"scope not granted"}`; in `lm ui` the footer says `live events: …` and views refresh only when opened |

## Keep a connector credential: the secret store

Connector manifests name a secret (`*_env` fields); `lm secret` stores its value, encrypted.
No command returns a value.

```
$ lm secret set ERP_DB_PASSWORD --description "ERP read account"
Value for ERP_DB_PASSWORD:
Created secret "ERP_DB_PASSWORD"

$ lm secret list
NAME                           ROTATED_AT                     DESCRIPTION
ERP_DB_PASSWORD                -                              ERP read account

$ lm secret rm ERP_DB_PASSWORD
Type 'delete' to confirm removal of "ERP_DB_PASSWORD": delete
Deleted secret "ERP_DB_PASSWORD"
```

- The value is typed at the prompt and not shown. From a script: `lm secret set NAME -` reads stdin.
- `set` on an existing name rotates the value (`Updated secret "…"`, `ROTATED_AT` filled).
  Without `--description`, the description stays; `--description ""` clears it.
- `lm secret describe NAME` shows metadata only; `lm secret rm --force` skips the prompt.
- Which connectors read the store, and which read the hub's environment instead:
  [REST](connectors/rest.md), [CSV](connectors/csv.md), [JDBC](connectors/jdbc.md).

## Trace a citation back to its source row

```bash
lm data row <HASH>
```

Takes the `row_hash` of a chat citation and prints the raw source row (all values as text).
It answers `404` when the hash resolves to nothing in your tenant. A scope-bound caller reads the
row only if every connector writing its table carries one of its scopes.

## Check migration drift

```
$ lm migration status
SCHEMA    VERSION  PENDING  LAST APPLIED
core      75       0        2026-10-19 01:36
identity  18       0        2026-10-19 01:36
rag       6        0        2026-10-19 01:36
```

Migrations apply when the hub starts; there is nothing to run by hand. `PENDING 0` on every schema
means the database matches this hub build. After an upgrade: [Upgrade](operations/upgrade.md).

## See the installed modules

```
$ lm module list
NAME      VERSION  SCHEMA              STATUS    REASON
core      0.1.0    Platform            ACTIVE
identity  0.1.0    Identity & Tenancy  ACTIVE
llm       0.1.0    Model provider      INACTIVE  disabled:lumnik.ai.enabled
rag       0.1.0    Platform RAG        INACTIVE  disabled:lumnik.ai.enabled
```

This hub is a Pro build with the AI switched off ([Switching the AI off](rag/models.md#switching-the-ai-off)).
An Open build lists no `llm` or `rag` line.

## Turn a hook on or off, and read declared workflows

| Gesture | What it does |
|---|---|
| `lm hook list` · `lm hook get NAME` | the hooks this hub loaded |
| `lm workflow list` · `lm workflow get NAME` | the tenant's declared `kind:Workflow` manifests ([Workflows](apps/workflows.md)) |
| `POST /api/platform/hooks/{hookId}/toggle?tenantId=…` | turns one hook on or off for one tenant; other hub threads see it within 60 s |

## Manage tenants, orgs and users

No `lm` command manages identity; use the HTTP doors. Deletes archive, never erase. The gestures
in context (onboarding, offboarding): [Identity administration](security/identity-admin.md).

| Method | Path | Contract |
|---|---|---|
| `GET` | `/api/tenants` | active tenants (`lm_superadmin`) |
| `POST` | `/api/tenants` | create `{code, name, description?}`; `201`, `409` on a duplicate code |
| `PUT` · `DELETE` | `/api/tenants/{id}` | update name/description · deactivate (`204`) |
| `GET` · `POST` | `/api/tenants/{tid}/orgs` | list · create (`409` on a duplicate code) |
| `GET` | `/api/tenants/{tid}/orgs/tree?root=&depth=` | the org tree (default depth 3) |
| `PUT` · `DELETE` | `/api/tenants/{tid}/orgs/{id}` | update · deactivate |
| `GET` · `POST` | `/api/tenants/{tid}/users` | list · create `{login, name, email?, description?}` (`409` on a duplicate login) |
| `PUT` · `DELETE` | `/api/tenants/{tid}/users/{id}` | update profile · deactivate (`204`) |
| `POST` | `/api/tenants/{tid}/users/{id}/unlock` | unlock a locked account |
| | `/api/tenants/{tid}/roles`, `…/users/{uid}/roles/{roleId}`, `…/roles/{roleId}/orgs/{orgId}` | tenant roles, their assignment, their org access |

Org changes emit `org.created`, `org.updated`, `org.moved`, `org.archived`. lumnik stores no
passwords: users sign in through Keycloak or your IdP. On Pro, a user's hub record is created at
first login.

## Act on behalf of a tenant (impersonation)

- Impersonation is a JWT claim minted by your IdP: `acting_as_tenant: <tid>`, on a token that
  carries `lm_superadmin`. There is no hub endpoint that mints it.
- It switches the tenant, never the roles: the roles in the token are the ones checked.
- A token carrying the claim without `lm_superadmin` is refused:
  `403 {"error": "lm_superadmin required to impersonate a tenant"}`.
- The `X-Impersonate-Tenant` header works only with `lumnik.security.dev-bypass=true`.

## Harden the deployment

| Concern | What the hub does | What you do |
|---|---|---|
| Secrets | encrypted at rest, never returned by any door | protect the `.env` that holds the keys; key rotation is not supported ([RLS & credentials](security/rls.md)) |
| Impersonation | honoured only for `lm_superadmin` | grant `lm_superadmin` to as few accounts as possible; an impersonated write is audited in the target tenant under the superadmin's user id, with nothing marking it as impersonation; the impersonation itself shows only in log lines |
| Dev bypass | grants every role to every caller | never set `lumnik.security.dev-bypass=true` outside development |
| Platform doors | `/api/platform/*` requires `lm_integrator` | restrict `/api/platform/*` and `/q/*` at your proxy if the hub faces the internet |

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| `401` with `WWW-Authenticate: Bearer` on `/api/platform/*` | the token expired or was not sent | `lm login` again |
| `API error 403: missing role lm_integrator` | the token lacks `lm_integrator` | read your `roles` (above), then have it granted |
| `API error 403 (forbidden): no details from the server — your account may lack the required role` (raw HTTP: `403`, empty body) | a role the door needs is missing — often `lm_admin` | read your `roles`, then have it granted |
| `access refused (403): scope not granted` on `lm integration`, `lm outputs` (raw HTTP on the event stream: `403 {"error":"scope not granted"}`) | your account holds a `scope:*` role; these doors are tenant-wide | use an account with no `scope:*` role |
| `403` on `/api/tenants/{tid}/…` while you hold `lm_admin` | `{tid}` is not your token's tenant | use `tenant` from `/api/bff/v1/me` |
| `400 {"error": "Unknown event type: …"}` on `lm integration test --event …` | the type is not in the catalog — `decision.*` never is | test with a catalog type; verify `decision.*` with a real decision |
| `API error 400: HMAC mode needs a secret …` on `lm integration add` | no `--secret-stdin` | pass the secret on stdin — see [Send events out](#send-events-out-to-another-system) |
| A failed delivery is no longer retried | it used its 3 attempts | `lm outputs replay ID` |
| `lm outputs replay ID` answers `404` for an id in your list | the target was removed with `lm integration rm` | register the target again; new events reach it |
| `X-Impersonate-Tenant` changes nothing | the header works only with dev-bypass | use the `acting_as_tenant` claim |

## If none of that is it

- Your hub serves its live API contract at `/q/openapi` and a try-it console at `/q/swagger-ui/`:
  [Test the API](recipes/test-the-api.md).
- Security reports: `contact@lumnik.fr` (acknowledged within 72 hours). It is not a support
  channel; for configuration questions, ask your integrator.
