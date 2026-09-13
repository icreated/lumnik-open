# kind: Process — react

> The second rung: a **declarative watcher** on ingested rows. When a row lands that matches
> your conditions, the process emits a `decision.*` event — and the effect *is* the event. It
> lands in the outbox and in the inbox every time; reaching n8n, Make, Zapier or your own
> endpoint rides the hub's webhook subscriptions. Zero new code paths.

| | |
|---|---|
| **Kind** | `Process` — `apps.lumnik.io/v1` |
| **Trigger** | `data.row.changed` on a raw `connector.t_*` table — the only v1 trigger |
| **Conditions** | the [view filter grammar](views.md#filter-grammar), ANDed, over the table's own columns |
| **Emits** | one `decision.<name>` event per matching row, `{col}`-templated, fields included |
| **Apply** | `lm process apply` — `lm_admin`; the whole `/api/processes` surface is the admin door |
| **Edition** | Open |

A process is how the substrate breathes out: it does not update anything, it does not call
anyone — it says, once, that something worth a human's attention just landed, with the row's own
words. Who gets told, and how, is a subscription question answered elsewhere. The story for the
métier reader is [How lumnik breathes](../respiration.md); this page is the mechanics of one link.

## Features

- **[Fires on rows that land, not rows that exist.](#firing-it)** A process reacts to
  `data.row.changed`, which the write path emits for genuinely new rows only — re-running the
  same export emits nothing.
- **[Conditions in the view grammar.](#the-manifest)** `status=EN LITIGE`, `amount>1000`,
  `city~lyon` — the same forms a view filters by, validated at apply against the trigger table.
- **[The row's own words.](#message-templating)** `{col}` placeholders are validated at apply and
  rendered at fire time; every referenced column also rides in the event as data.
- **[Refused at apply, never silent at fire time.](#refusals)** A trigger table that cannot fire,
  an unknown column, a quoted condition value, an unknown placeholder, an emit type outside
  `decision.` — each is a `400` that names the fix.
- **[Reversible, and removable.](#turn-a-process-off)** `lm process disable` stops it firing and
  keeps the manifest; `lm process rm` removes it.
- **[Fail-closed per process.](#failure-contract)** A process that breaks at runtime logs and
  skips; it never blocks the event cursor or its siblings.
- **[Delivery is a subscription.](#delivery-is-opt-in)** The inbox always has the decision;
  a webhook has it once one is registered for the tenant.

## Quickstart

!!! note "Every screen on this page reads the demo desk that ships with the repo"
    An order book whose export arrives every morning, under `docs/apps/demo/`. Land it first —
    [The demo desk](demo-desk.md) has the three commands.

```yaml
--8<-- "docs/apps/demo/processes/10-litige.yaml"
```

```
$ lm process apply -f docs/apps/demo/processes/10-litige.yaml
{"name":"litige-ouvert","table":"connector.t_acme_desk_orders","emit":"decision.litige-ouvert"}
$ lm process list
NAME           TABLE                         EMIT                    ENABLED
litige-ouvert  connector.t_acme_desk_orders  decision.litige-ouvert  true
$ lm inbox
ID  QUAND  PROCESS  MESSAGE  ENTITY_KEY
```

Nothing fired: two orders already sat in `EN LITIGE` when the process was declared, and a
process reacts to rows that **land**, not to rows that exist. Now the next morning's export
arrives, with one new order in dispute:

```
$ docker cp docs/apps/demo/data/acme-orders-day2.csv lumnik-hub-1:/tmp/acme-desk/acme-orders.csv
$ lm connector run acme-desk-orders
run scheduled: 70e54370-f53f-4811-8deb-86a7c5d15ad6
$ lm run list --connector acme-desk-orders
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
70e54370  acme-desk-orders  default   Completed  13  13   0        0             2026-09-13 02:39  2026-09-13 02:39
a5f820af  acme-desk-orders  default   Completed  12  12   0        0             2026-09-13 02:38  2026-09-13 02:38
```

Thirteen rows read; twelve of them the table already held and deduplicated by content; **one**
genuinely new row, and it is in dispute. A few seconds later:

```
$ lm inbox
ID    QUAND        PROCESS        MESSAGE                                                   ENTITY_KEY
1929  à l'instant  litige-ouvert  Commande SO-000013 du client P-1007 en litige — 860.00 €  96357a4a5750f97026f9ec5b8ce0f986517179f470e8a74b9fd013befe0e3622
```

One decision, for the one row, in the row's own words. `ENTITY_KEY` is the row's content hash —
the same `_row_hash` the table deduplicates by, so the decision points at exactly one row for as
long as that row exists. The event behind it, in the outbox:

```json
{"process": "litige-ouvert",
 "entity": "connector.t_acme_desk_orders",
 "entity_key": "96357a4a…e3622",
 "message": "Commande SO-000013 du client P-1007 en litige — 860.00 €",
 "fields": {"code": "P-1007", "amount": "860.00", "order_no": "SO-000013"}}
```

`fields` carries every column the message referenced, as data — an automation consumes values,
not a sentence to parse. (`entity_key` is abridged here; the inbox above prints it whole.)

## The manifest

| Field | Required | Meaning |
|---|---|---|
| `metadata.name` | yes | kebab-case (`[a-z0-9-]+`); becomes the event's `process` and, by convention, its type |
| `spec.scope` | yes | the scope whose [schema card](../rag/http-api.md#schema-admin) declares `on.table` |
| `spec.on.event` | yes | **must be `data.row.changed`** — the only v1 trigger |
| `spec.on.table` | yes | the **raw ingest table** (`connector.t_<name>`) — see [Trigger at the raw write path](#trigger-at-the-raw-write-path) |
| `spec.when[]` | yes (≥ 1) | conditions in the [view filter grammar](views.md#filter-grammar), ANDed |
| `spec.emit.type` | yes | **must start with `decision.`** — the governed taxonomy the inbox and the webhook filters are built on |
| `spec.emit.message` | yes | the human sentence, `{col}`-templated |

That table is the whole surface: `apply` refuses a key it does not honour and names it, so a
misspelled `mesage:` costs a `400` rather than a decision that fires with no text. The
`apiVersion:` envelope is accepted and ignored.

Every stored process carries an `enabled` flag (default `true`, the `ENABLED` column of
`lm process list`), and only enabled processes fire. There is **no API or CLI gesture to toggle
or delete** a process yet — both are a database-level edit, so if you do not operate the hub
yourself, this one goes to whoever does.

### Trigger at the raw write path

`data.row.changed` is born where rows are written — the connector's write path — so `on.table`
is the **raw** table a connector fills, and `when` columns are that table's own. The scope must be
the one whose card carries that raw table. A fused entity view (`connector.v_*`) receives no
such event: nothing writes rows *into* a view.

Being in the card is not enough, and apply checks the stronger thing: that the table is one a
connector's row writer actually fills. A card also advertises the fused entity views and the
`ext.*` tables a [`kind: hybrid` target](../connectors/rest.md) fills — no row is written *into*
either — so both are refused. A view gets the better sentence, because the entity knows the raw
tables it fuses:

```
$ lm process apply -f docs/apps/demo/processes/63-refused-fused-view.yaml
Error: API error 400: spec.on.table 'connector.v_acmecustomer' is a fused entity view — data.row.changed is born on the raw table a connector writes, never on a view, so this process could never fire — declare it on connector.t_acme_desk_erp or connector.t_acme_desk_crm
```

Declare one process per raw source, or move the condition to the source that carries it.

Emission is **gated**, and cheaply: the write path asks, per chunk, whether *any* subscriber on
the tenant wants `data.row.changed` for this table — a webhook endpoint, a notification rule or a
process — and stages events only when one does. A table nobody watches costs nothing at
ingestion; declaring a process is what turns the events on for its table.

## Message templating

`{col}` interpolates the row's value — `"Commande {order_no} du client {code} en litige — {amount} €"`.
A `NULL` renders as an empty string. There is no escape syntax: a placeholder is exactly
`{letters_digits_}`, anything else (`{}`, `{a-b}`, a lone brace) passes through literally.

Every `{col}` is validated at **apply time** against the trigger table:

```
$ lm process apply -f docs/apps/demo/processes/61-refused-placeholder.yaml
Error: API error 400: message references {customer_name} — not a column of connector.t_acme_desk_orders
```

— never discovered silently at fire time. Every referenced column also rides in the event's
`fields`, so the receiving automation gets the data, not just the sentence.

## Firing it

The process consumer reads the event log a few seconds behind the write. For each
`data.row.changed` on a watched table it loads the row, evaluates every enabled process on that
table, and stages one `decision.*` per process whose conditions all hold. Two consequences worth
knowing:

- **Only genuinely new rows fire.** The write path emits `data.row.changed` for rows the table
  accepted; a row it refused as a duplicate (same `_row_hash`) emits nothing. Re-running an
  unchanged export is silent — the quickstart's second run read 13 and fired once.
- **A row that matches two processes fires two decisions**, one per process, each under its own
  type — [measured below](#watch-a-second-condition-on-the-same-table).

## Delivery is opt-in

A firing process's decision lands in the outbox — and in [the inbox](inbox.md) — every time,
regardless of subscriptions. Reaching an external system additionally needs a **webhook
subscription** registered for the tenant; none is seeded by default. With none registered,
nothing is pushed anywhere — the inbox is still where the decision is.

```
$ lm inbox ack 1929
acked.
$ lm inbox --all
ID    QUAND  PROCESS        MESSAGE                                                            ENTITY_KEY
1929  2min   litige-ouvert  Commande SO-000013 du client P-1007 en litige — 860.00 € [traité]  96357a4a5750f97026f9ec5b8ce0f986517179f470e8a74b9fd013befe0e3622
```

The human disposes; the ack is itself an event (`annotation.decision.acked`), so a webhook
subscribed to `annotation.*` reacts when someone marks it done — and app state stays a fold over
the log, never a state row. Registering a subscription is `lm integration add` — it posts to
`/api/platform/integrations`, so it needs `lm_integrator` for the platform door **and** `lm_admin`
for the endpoint; the raw admin form is `POST /api/tenants/{tid}/integrations`, `lm_admin` alone. The
[Admin API guide](../admin-console-guide.md#send-events-out-to-another-system) walks the whole
gesture, and the [WOW demo](../demo/wow-customer/README.md) plays it against a stand-in n8n.

## Failure contract

- **Refusable at apply**: structural errors, a [trigger table that cannot
  fire](#trigger-at-the-raw-write-path), an unknown `when` column, an unknown placeholder, an emit
  type outside `decision.`, an out-of-domain value — each is refused with the card's hint. A
  process that could never fire is never accepted silently.
- **Fail-closed per process at runtime**: a process that breaks (its table vanished) logs and
  skips — it never blocks the event cursor or its sibling processes.
- **Declarative only, by doctrine**: there is no Kotlin escape hatch for processes. If a real
  métier process cannot be expressed declaratively, that limitation should surface — not be
  hidden inside a script. The substrate breathes; it does not mutate.

## Refusals

```
$ lm process apply -f docs/apps/demo/processes/60-refused-unknown-column.yaml
Error: API error 400: when: column 'montant' is not in connector.t_acme_desk_orders — fix the column name or rediscover the scope
$ lm process apply -f docs/apps/demo/processes/61-refused-placeholder.yaml
Error: API error 400: message references {customer_name} — not a column of connector.t_acme_desk_orders
$ lm process apply -f docs/apps/demo/processes/62-refused-emit-type.yaml
Error: API error 400: spec.emit.type is required and must start with 'decision.' — the governed taxonomy
$ lm process apply -f docs/apps/demo/processes/63-refused-fused-view.yaml
Error: API error 400: spec.on.table 'connector.v_acmecustomer' is a fused entity view — data.row.changed is born on the raw table a connector writes, never on a view, so this process could never fire — declare it on connector.t_acme_desk_erp or connector.t_acme_desk_crm
$ lm process apply -f docs/apps/demo/processes/64-refused-quoted-value.yaml
Error: API error 400: filter value 'EN LITIGE' is quoted; a value here is a bare literal, so the quotes would be compared to status too — write status=EN LITIGE
```

The last one is the [view grammar](views.md#filter-grammar) speaking, not a rule of its own: a
value here is a **bare literal**, and quotes are ordinary characters. `status='EN LITIGE'` used to
be accepted and then compared to the column quotes and all — a condition that can never be true,
sitting enabled and silent for ever.

The whole `/api/processes` surface (apply, list, `GET /api/processes/{name}/manifest`,
`PUT /api/processes/{name}/enabled`, `DELETE /api/processes/{name}`) is the **admin door** —
`lm_admin`: declaring an attention rule is tenant administration, not a métier gesture. Inside
that door the [métier-scope boundary](../rag/scopes.md) applies with its opt-in semantics, the
same as every app-ladder door: an admin with no `scope:` role sees and declares
for the whole tenant; a scope-bound admin declares only for granted scopes, lists only those, and
gets the uniform `403 {"error":"scope not granted"}` outside them. In the TUI, `:processes` lists
the declared rules and `e` re-opens a stored manifest through the `:new` authoring loop.

## How to…

### Get told when an order lands in dispute

```bash
lm process apply -f docs/apps/demo/processes/10-litige.yaml
lm inbox
```

**Result:** every genuinely new `EN LITIGE` row lands as one decision in `lm inbox` (and the
PWA's *Décisions* tab), with the order's own number, customer and amount. To push it further,
register a webhook subscription on `decision.*`.

### Watch a second condition on the same table

```yaml
--8<-- "docs/apps/demo/processes/20-grosse-commande.yaml"
```

```
$ lm process apply -f docs/apps/demo/processes/20-grosse-commande.yaml
{"name":"grosse-commande","table":"connector.t_acme_desk_orders","emit":"decision.grosse-commande"}
$ docker cp docs/apps/demo/data/acme-orders-day3.csv lumnik-hub-1:/tmp/acme-desk/acme-orders.csv
$ lm connector run acme-desk-orders
$ lm inbox
ID    QUAND        PROCESS          MESSAGE                                                    ENTITY_KEY
1935  à l'instant  grosse-commande  Grosse commande SO-000014 : 5000.00 € pour P-1003          3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
1934  à l'instant  litige-ouvert    Commande SO-000014 du client P-1003 en litige — 5000.00 €  3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
```

**Result:** the third morning's export carries one new order, in dispute and above 1 000 €. Two
processes, two decisions, the same `ENTITY_KEY` — each triaged on its own. `amount` is a `text`
column, as every CSV column is; `amount>1000` casts it for the comparison exactly as a view
filter does.

### Turn a process off

Two gestures, one reversible and one not.

```
$ lm process disable litige-ouvert
process litige-ouvert enabled=false
$ lm process list
NAME             TABLE                         EMIT                      ENABLED
grosse-commande  connector.t_acme_desk_orders  decision.grosse-commande  true
litige-ouvert    connector.t_acme_desk_orders  decision.litige-ouvert    false
$ lm process rm litige-ouvert
process litige-ouvert deleted
$ lm process rm litige-ouvert
Error: API error 404: unknown process: litige-ouvert
```

**Result:** `disable` stops it firing and keeps everything else — the manifest is still stored,
still re-appliable, and `lm process enable` puts it back. `rm` removes it for good, with no prompt;
a second `rm` answers `404`. In the TUI's `:processes` page the same two gestures are `^P` and
`Del` on the row under the cursor, and `e` re-opens the stored manifest in the authoring loop.

Disabling is not cosmetic: the hub only stages a `data.row.changed` when *some* subscription wants
it, and a disabled process stops counting as one. If nothing else listens to that table, the event
stops being written at all.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| apply refused: `unknown key(s) in the manifest: …` | a misspelled field (`mesage:`) | the message lists what `kind:Process` honors — fix the spelling |
| apply refused `404`: `no schema for scope '…' — run discovery first` | `spec.scope` is not a discovered scope | `lm source rediscover --scope <tag>` — needs **both** `lm_integrator` and `lm_admin`, and the scope must be one the caller holds; `lm source schema --scope <tag>` shows what it holds |
| apply refused: `spec.on.table '…' is not in the '…' scope` | the scope's card does not carry that table | `lm source schema --scope <tag>`, then fix the name |
| apply refused: `spec.on.table '…' is a fused entity view …` | the trigger is a `connector.v_*` view, which no connector writes into | declare the process on one of the raw tables the message names |
| apply refused: `spec.on.table '…' cannot fire …` | the trigger is in the card but no row writer fills it (an `ext.*` hybrid target) | declare the process on a `connector.t_*` table |
| apply refused: `when: column '…' is not in …`, or `'…' is not an allowed value for …` | a condition names an unknown column, or an `=` value outside its declared domain | fix the condition against the card |
| apply refused: `message references {…} — not a column of …` | a placeholder that is not a column of the trigger table | fix the placeholder, or drop it |
| apply refused: `spec.emit.type … must start with 'decision.'` | an emit type outside the taxonomy | `decision.<something>` |
| a process applied cleanly and never fires | `on.table` is a fused view (`connector.v_*`) — no row is ever written into a view | point it at the raw `connector.t_*` table the view is built from |
| a process never fires on a re-run | the rows were already in the table — the write path emits nothing for a duplicate | it is doing its job; only new rows fire |
| a declared, enabled process stopped firing | its trigger table vanished or broke at runtime — fail-closed per process | check the hub's log; sibling processes keep firing |
| a process still fires after you meant to turn it off | it is still enabled | `lm process disable <name>` (reversible), or `lm process rm <name>` to remove it |
| decisions show in `lm inbox` but never reach an external system | delivery needs a webhook subscription registered for the tenant — none is seeded | `lm integration add` (`lm_integrator` + `lm_admin`), or keep triaging from `lm inbox` |
| `403 {"error":"scope not granted"}` | you are scope-bound and `spec.scope` is not one of your grants | ask for that `scope:` role, or declare under a granted scope |
| `403` with an **empty body** | you do not hold `lm_admin` — the whole `/api/processes` surface requires it | `GET /api/bff/v1/me` returns your roles under `roles` |

## See also

- [The demo desk](demo-desk.md) — the order book every screen here reads
- [Inbox](inbox.md) — where a human sees and disposes of the emitted decisions
- [Admin API guide — Integration Hub](../admin-console-guide.md#send-events-out-to-another-system) — the webhook subscription delivery depends on
- [Workflows](workflows.md) — declares what a status column's values *mean*, so a `when: status=…` condition matches a state someone actually declared; a process fires on a row landing, but silence — nothing landing — is the workflow's dormancy sweep to see
- [Kafka connector](../connectors/kafka.md) — events *into* the hub
