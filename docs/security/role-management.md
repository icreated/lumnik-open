# Roles & permissions

> Four `lm_*` realm roles open surfaces; `scope:<tag>` roles narrow which data those
> surfaces show. Matching is flat, no hierarchy: every role a user needs is granted explicitly.

```mermaid
flowchart LR
  u["lm_user"] --> s1["business reads —<br/>entities · views · chat"]
  i["lm_integrator"] --> s2["/api/platform/* —<br/>connectors · runs · views · exports"]
  a["lm_admin"] --> s3["entities · processes · secrets ·<br/>outputs · users"]
  sa["lm_superadmin"] --> s4["platform-wide —<br/>tenant list · impersonation"]
  sc["scope:achat · scope:stock …"] -.->|"limits WHICH data,<br/>inside what a role opened"| s1
```

| Role | Opens |
|---|---|
| `lm_user` | the app surface: entities, views, chat, inbox, the PWA |
| `lm_integrator` | the platform surface `lm` drives: connectors, runs, dead letters, views, apps, workflows, search indexes, exports |
| `lm_admin` | entities, processes, secrets (write), the guided JDBC flow, schema discovery, workflow observations, outputs, email rules, users, SCIM tokens |
| `lm_superadmin` | cross-tenant: tenant list, tenant CRUD, impersonation; on a hub with several tenants, entities, views, apps and the platform reindex ([below](#on-a-hub-with-several-tenants)); the only role with `wildcard: true` |
| `scope:<tag>` | not a surface: a fence. One or more binds the user to those scopes; none means tenant-wide. [Scopes](../rag/scopes.md#binding-users-to-scopes-opt-in) |

- **No hierarchy.** `lm_admin` does not imply `lm_user`. The bundled realm grants `integrator`
  three roles (integrator, admin, user) for that reason. **An integrator needs all three**:
  with `lm_integrator` alone, `lm entity apply` already answers `403`.
- **Roles live in the IdP.** Granting one is a Keycloak gesture:
  [Tenants, users & scopes](identity-admin.md#make-someone-admin-integrator-superadmin).
- **Three gates, three refusals.** Which one answered tells you what to grant; see
  [Troubleshooting](#troubleshooting).

## Which role for which gesture

| You run | Roles it needs |
|---|---|
| `lm connector …`, `lm run …`, `lm dlq …`, `lm endpoint …`, `lm csv ingest`, `lm apply -f` (connector) | `lm_integrator` |
| `lm view apply`, `lm app apply`, `lm workflow …`, `lm search …` (but `query`), `lm export …`, `lm hook scripts`, `lm secret list` | `lm_integrator` |
| `lm entity apply`, `lm entity delete`, `lm entity resolve`, `lm entity assert-same`, `lm entity assert-distinct` | `lm_admin` |
| `lm process …` (every subcommand, list included) | `lm_admin` |
| `lm secret set`, `lm secret scope`, `lm secret rm` | `lm_integrator` **and** `lm_admin` |
| `lm source jdbc …`, `lm source schema`, `lm source rediscover`, `lm rag status`, `lm hook list`, `lm hook get` | `lm_integrator` **and** `lm_admin` |
| `lm observations …` | `lm_integrator` **and** `lm_admin` |
| `lm integration …`, `lm outputs …`, `lm notify deliveries`, `lm notify replay` | `lm_integrator` **and** `lm_admin` |
| `lm notify add`, `list`, `rm`, user and SCIM token administration | `lm_admin` |
| `lm entity get`, `lm view …` (read), `lm inbox`, `lm ask`, `lm chat`, `lm search query`, the PWA | `lm_user` |

The bundled `integrator` account holds the three roles, so every line works with it. Check this
table when the client's own IdP grants the roles.

### On a hub with several tenants

Each tenant sees only the entities, views and apps applied for it. Those applied as `hubadmin` are shared with
every tenant. On a hub with several tenants, only `lm_superadmin` runs `lm entity apply` and `delete`, `lm view apply` and `delete`, `lm app apply` and `delete`, and
`lm rag reindex`. With one tenant, the table above holds.

- **Several tenants** means two tenants besides `hubadmin`. The bundled `default` tenant is one, so the first tenant
  you create turns the rule on, at the next write. An archived tenant still counts. A write running while a tenant
  is created finishes first, and the creation waits for it; a write arriving meanwhile waits, then counts the new
  tenant.
- **`LUMNIK_TENANCY=multi`** turns it on from the start, before a second tenant exists. The Helm chart sets it
  (`tenancy: multi`). The default is `auto`, the count above; any other value stops the hub at startup.
- **Who writes them:** the integrator who serves a tenant, with `lm_superadmin` added to their roles. The door's own
  role still applies: a view still needs `lm_integrator`, an entity `lm_admin`.
- **As `hubadmin`,** even with one tenant, these commands need `lm_superadmin`, `lm rag reindex` too: what they
  write is shared with every tenant
  ([Upgrade notes](../operations/upgrade.md#an-entity-a-view-or-an-app-belongs-to-the-tenant-that-applied-it)).
- **The refusal:**
  [Upgrade notes](../operations/upgrade.md#on-a-hub-with-several-tenants-lm-superadmin-writes-entities-views-and-apps).

## A hub with no login: dev-bypass

A hub in **dev-bypass** checks no token: every caller is `lm_superadmin`, `lm_admin` and `lm_user`,
and picks any tenant with the `X-Tenant-ID` header.

- The self-host stack (`up.sh`) never runs in dev-bypass.
- The Helm chart (cloud edition) defaults to `auth.mode: oidc`. It renders dev-bypass only with
  `auth.iUnderstandNoAuth=true` as well; `make k8s-up` sets both, on a local kind cluster. Never
  expose such a hub.
- To check a refusal, use a hub with a real login and present a real token. No header overrides
  roles.

## After each upgrade: the roles catalog

Read the roles catalog for your RBAC inventory. There is no `lm` command for it; it needs
`lm_admin` and `lm_integrator`. Mint `$TOKEN` as in
[Test the API](../recipes/test-the-api.md#mint-a-token), then:

```
$ curl -s https://your-hub.example.com/api/platform/roles -H "Authorization: Bearer $TOKEN" \
    | jq -c '.roles[] | {id, wildcard}'
{"id":"lm_superadmin","wildcard":true}
{"id":"lm_admin","wildcard":false}
{"id":"lm_user","wildcard":false}
```

Confirm:

- The roles listed match what the realm declares.
- Only `lm_superadmin` carries `wildcard: true`.

!!! warning "One item fails: `lm_integrator`"
    `lm_integrator` gates the whole `/api/platform/*` surface plus the view and workflow
    applies, but it has **no entry in the roles catalog**. An RBAC inventory taken from the
    catalog misses a role that grants real access.

## Audit logging

Every refusal leaves one `WARN` line naming roles and grants, never a row or a token. No
refusal is written to the audit table. [Refusals](index.md#refusals)

## A scope-bound integrator

**Administering a connector follows the writing rule.** A bound integrator acts only on a
connector whose scopes they hold **all** of.

- This covers every connector command: `get`, `apply`, `run`, `pause`, `resume`, `delete`,
  `scope`, and the endpoint commands.
- `lm connector list` shows only those connectors.
- Any other connector is simply not found: one outside your scopes, one spanning a scope you do
  not hold, a scopeless one.
- Moving a connector you hold into a scope you don't hold is refused:

```
$ lm connector list
NAME           TYPE         TENANT  SCHED  WINDOW  STATUS  LAST RUN
orders-ventes  hello-world  1
$ lm connector pause stock-magasin
Error: connector "stock-magasin" not found
$ lm connector scope add orders-ventes magasin
Error: access refused (403): scope not granted
$ lm connector scope ls orders-ventes
ventes
```

The same hub, listed by an integrator with no `scope:*` role, shows `stock-magasin` too:
unbound integrators keep administering the whole tenant.

**What a connector left behind follows the same rule.** A run carries counts, table names and
the error message. A dead letter carries the rejected row's values.

- A bound integrator's `lm run list` and `lm dlq list` show only the connectors whose scopes
  they hold all of.
- On a connector outside those scopes, these answer `403`: `lm run get`/`cancel`,
  `lm dlq get`/`discard`/`replayed`, and the `--connector`/`--run` filters.

The `lm source` doors create or reuse connectors, so they follow the writing rule:

- `lm csv ingest` refuses a `--scope` you do not hold. Without `--scope` the one-shot lands in
  `system`, which you need to hold too.
- Re-ingesting under the name of another scope's one-shot is refused rather than moving it
  into yours.
- `lm source jdbc add` creates its connectors without scopes, so a bound integrator is refused
  before the source database is contacted. Declare the connector in a manifest with `scopes:`
  and `lm apply` it instead.
- `lm source jdbc list-tables` lists only the connectors you hold.
- `lm source rest probe` answers only for a connector you hold.
- `lm source rest from-openapi` writes nothing to the hub. It drafts a manifest, and
  `lm apply` then judges its `scopes:`.

The capture below shows the same hub as above with two csv connectors, `commandes-ventes`
(`scopes: [ventes]`) and `stock-magasin` (`scopes: [magasin]`), each run once with one malformed
row. The caller is an integrator holding `scope:ventes`:

```
$ lm run list
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
0893cee7  commandes-ventes  default   Completed  2   1    1        1             2026-10-18 15:37  2026-10-18 15:37
$ lm dlq list
ID        CONNECTOR         RUN       REASON           ATTEMPTS  RESOLUTION  MESSAGE
f8b2118f  commandes-ventes  0893cee7  MalformedSource  1                     data/commandes.csv:3 — Record has 3 values but only 2 column…
$ lm dlq get 95b20b87-d992-43e2-b08b-f50806a4d131
Error: access refused (403): scope not granted
$ lm dlq discard 95b20b87-d992-43e2-b08b-f50806a4d131
Error: access refused (403): scope not granted
$ lm run get b93a9839-862e-49ea-a397-f13a46751c57
Error: access refused (403): scope not granted
$ lm csv ingest inventaire.csv --name inventaire --scope ventes
Error: access refused (403): scope not granted
$ lm csv ingest inventaire.csv --name inventaire-bis --scope magasin
Error: access refused (403): scope not granted
$ echo "$PGPASSWORD" | lm source jdbc add erp --url jdbc:postgresql://erp-db:5432/erp --user lumnik_reader --tables orders --password-stdin
Error: access refused (403): scope not granted
```

The two ids refused above are `stock-magasin`'s dead letter and run, which an unbound
integrator sees in the same lists. `inventaire` is a `magasin` one-shot. Re-ingesting it under
`--scope ventes` would have moved it into `ventes`, and its rows with it.

### What a scope-bound caller cannot do

Some doors act on the whole tenant, never on one scope. A caller holding any `scope:*` role is
refused all of them, whatever its other roles:

- secrets: `lm secret list`, `describe`, `set`, `scope`, `rm`;
- hooks: `lm hook list`, `get`, the toggle;
- outputs: `lm integration`, `lm notify`, `lm outputs`, the live event stream;
- the event dead letters, `/api/platform/events/dead-letters`;
- the platform reindex, `lm rag reindex`;
- users, roles and organisations, `/api/tenants/{tid}/…`, and SCIM tokens;
- tenants, `/api/tenants` (`lm_superadmin`).

`lm rag status` stays open: it counts only the chunks of your scopes.

A hub holding one secret, `ERP_PASSWORD`. The caller holds `lm_integrator`, `lm_admin` and
`scope:ventes`:

```
$ lm secret list
Error: access refused (403): scope not granted
$ printf 'x' | lm secret set ERP_PASSWORD -
Error: access refused (403): scope not granted
$ lm rag reindex
Error: access refused (403): scope not granted
$ lm hook list
Error: access refused (403): scope not granted
$ curl -s -w ' %{http_code}' -H "Authorization: Bearer $TOKEN" "$HUB/api/tenants/1/users"
{"error":"scope not granted"} 403
```

Without `scope:ventes`, the same account lists `ERP_PASSWORD` and starts the reindex. Do this
work from an account with no `scope:*` role.

A scope-bound caller can still write connectors in its own scopes. Such a connector reads a secret
only when it carries every scope of the secret ([secret scopes](../admin-console-guide.md#which-connectors-may-read-a-secret-its-scopes)).
Here `ERP_PASSWORD` serves `ventes,achats`, for a connector carrying both; the caller holds `lm_integrator` and `scope:ventes`, and its
`my-orders` connector (`scopes: [ventes]`) names `password_env: ERP_PASSWORD`:

```
$ lm apply -f my-orders.yaml
Applied connector "my-orders" — 1 endpoint
$ lm connector run my-orders
run scheduled: 37fa8e79-13d4-4dc9-8578-aaefa930317b
$ lm run get 37fa8e79-13d4-4dc9-8578-aaefa930317b
ID        CONNECTOR  ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
37fa8e79  my-orders  orders    Failed  0   0    0        0             2026-11-15 13:12  2026-11-15 13:12
✖ run failed: secret ERP_PASSWORD does not serve scope ventes: lm secret scope ERP_PASSWORD SCOPES replaces the scopes it serves
```

The password never left the hub, whatever host `base_url` named.

## Troubleshooting

| What `lm` prints | Gate | Fix |
|---|---|---|
| `Error: API error 403: missing role lm_integrator` | the platform gate, in front of every `/api/platform/*` path | Grant `lm_integrator` |
| `Error: API error 403 (forbidden): no details from the server — your account may lack the required role` | the endpoint's role gate (an empty-body `403`) | Grant the role the gesture needs: [the table above](#which-role-for-which-gesture) |
| `Error: access refused (403): scope not granted` | the scope gate: the caller holds `scope:*` roles and none matches the resource | Grant the matching `scope:<tag>`, or remove all scope roles to go tenant-wide |
| `scope not granted` on `lm integration`, `lm notify`, `lm outputs`, `lm secret`, `lm hook list`/`get`, `lm rag reindex`, or on `/api/tenants/…` | these doors are tenant-wide: the caller holds any `scope:*` role | Use an account with no `scope:*` role ([the list](#what-a-scope-bound-caller-cannot-do); outputs: [Outputs](../outputs.md)) |
| `Error: connector "orders" not found`, an empty `lm run list` or `lm dlq list`, or `scope not granted` on `lm connector scope`, `lm run …`, `lm dlq …` — though an unscoped account sees the connector | the caller holds `scope:*` roles, but not every scope that connector carries | Grant every scope the connector carries, or use an account with no scope role |
| `scope not granted` on `lm csv ingest` | the ingest asks for a scope the caller does not hold; without `--scope` it asks for `system` | Pass `--scope <tag>` with a granted tag |
| `scope not granted` on `lm source jdbc add` | the guided add creates a scopeless connector | Declare the connector in a manifest with `scopes:`, or use an account with no scope role |
| `Error: API error 404: no user record on this hub for user_id N` on `lm scope` | the token's `user_id` names no hub user row | [BYO IdP](byo-idp.md#troubleshooting) |
| No `403` at all, even for a wrong role or no token | the hub runs in [dev-bypass](#a-hub-with-no-login-dev-bypass) | Helm: install with `auth.mode=oidc` (the default) or `oidc-external` |

An integrator bound to `scope:desk`, on a hub whose connectors all carry `sales`:

```
$ lm connector list
NAME  TYPE  TENANT  SCHED  WINDOW  STATUS  LAST RUN
$ lm connector get orders
Error: connector "orders" not found
$ lm csv ingest /tmp/customers.csv
Error: access refused (403): scope not granted
$ lm run get 9eb29f11-f25c-40bb-808c-4aae3c444e2c
Error: access refused (403): scope not granted
```

## See also

- [Tenants, users & scopes](identity-admin.md): the Keycloak gestures behind each role.
- [RLS & database roles](rls.md): the database layer beneath RBAC.
- [Scopes](../rag/scopes.md): what a `scope:<tag>` fences.
