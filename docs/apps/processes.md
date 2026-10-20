# kind: Process — react

> The second rung: a **declarative watcher** on ingested rows. When a row lands that matches
> your conditions, the process emits a `decision.*` event. It always lands in the
> [inbox](inbox.md); a webhook subscription carries it on to n8n, Make, Zapier or your own
> endpoint.

| | |
|---|---|
| **Kind** | `Process` — `apps.lumnik.io/v1` |
| **Trigger** | `data.row.changed` on a raw `connector.t_*` table — the only trigger |
| **Conditions** | the [view filter grammar](views.md#filter-grammar), ANDed, over the table's own columns |
| **Emits** | one `decision.<name>` event per matching row, `{col}`-templated, fields included |
| **Commands** | `lm process apply` · `list` · `disable` · `enable` · `rm` — all need `lm_admin` |
| **Edition** | Open |

A process changes nothing and calls no one: it says, once, that a row worth a human's attention
just landed, in the row's own words. The métier story is [How lumnik breathes](../respiration.md).

## Features

- **[Fires on rows that land, not rows that exist.](#firing-it)** Re-running the same export
  emits nothing.
- **[Conditions in the view grammar.](#the-manifest)** `status=EN LITIGE`, `amount>1000`,
  `city~lyon` — validated at apply against the trigger table.
- **[The row's own words.](#message-templating)** `{col}` placeholders are validated at apply;
  every referenced column also rides in the event as data.
- **[Refused at apply, never silent at fire time.](#refusals)** Each refusal is a `400` that
  names the fix.
- **[Reversible, and removable.](#turn-a-process-off)** `lm process disable` stops it firing and
  keeps the manifest; `lm process rm` removes it.
- **[Fail-closed per process.](#failure-contract)** A process that breaks at runtime is skipped;
  its siblings keep firing.
- **[Delivery is a subscription.](#delivery-is-opt-in)** The inbox always has the decision;
  a webhook has it once one is registered.

## Quickstart

!!! note "Every screen on this page reads the demo desk that ships with the repo"
    An order book whose export arrives every morning, under `docs/apps/demo/`. Land it first —
    [The demo desk](demo-desk.md#landing-the-sources) has the commands.

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
ID  WHEN  PROCESS  MESSAGE  ENTITY_KEY
```

Nothing fired: the two orders already in `EN LITIGE` were there before the process. Now load
**day 2** — the next morning's export, with one new order in dispute:

```
$ docker cp docs/apps/demo/data/acme-orders-day2.csv lumnik-hub-1:/tmp/acme-desk/acme-orders.csv
$ lm connector run acme-desk-orders
run scheduled: dc0a38a3-3caf-40b8-9461-4fd202978dfb
$ lm run list --connector acme-desk-orders
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
dc0a38a3  acme-desk-orders  default   Completed  13  13   0        0             2026-10-19 01:42  2026-10-19 01:42
7b972d2b  acme-desk-orders  default   Completed  12  12   0        0             2026-10-19 01:38  2026-10-19 01:38
```

(`lumnik-hub-1` is the self-host stack's hub container; the file goes on the hub's disk.)
Thirteen rows read, one of them new. A few seconds later:

```
$ lm inbox
ID  WHEN         PROCESS        MESSAGE                                                   ENTITY_KEY
18  à l'instant  litige-ouvert  Commande SO-000013 du client P-1007 en litige — 860.00 €  96357a4a5750f97026f9ec5b8ce0f986517179f470e8a74b9fd013befe0e3622
```

One decision for the one new row. `ENTITY_KEY` is the row's content hash: it points at exactly
that row. The event itself, checked in the database:

```
$ echo "select payload from core.event_outbox where id = 18" | psql …
{"entity": "connector.t_acme_desk_orders", "fields": {"code": "P-1007", "amount": "860.00", "order_no": "SO-000013"}, "message": "Commande SO-000013 du client P-1007 en litige — 860.00 €", "process": "litige-ouvert", "entity_key": "96357a4a5750…"}
```

(`entity_key` abridged.) `fields` carries every column the message references, as data — an automation reads values,
not a sentence. A timestamp field is the text the message shows, its UTC instant,
`"2026-07-01T00:00:00Z"`.

## The manifest

| Field | Required | Meaning |
|---|---|---|
| `metadata.name` | yes | kebab-case (`[a-z0-9-]+`); becomes the event's `process` |
| `spec.scope` | yes | the scope whose [schema card](../rag/http-api.md#schema-admin) declares `on.table` |
| `spec.on.event` | yes | **must be `data.row.changed`** |
| `spec.on.table` | yes | the **raw ingest table** (`connector.t_<name>`) — see [Trigger at the raw write path](#trigger-at-the-raw-write-path) |
| `spec.when[]` | yes (≥ 1) | conditions in the [view filter grammar](views.md#filter-grammar), ANDed |
| `spec.emit.type` | yes | **must start with `decision.`** |
| `spec.emit.message` | yes | the human sentence, `{col}`-templated |

`apply` refuses a key it does not know and names it — a misspelled `mesage:` is a `400`, not a
decision with no text.

A stored process is `enabled` by default (the `ENABLED` column of `lm process list`); only
enabled processes fire. See [Turn a process off](#turn-a-process-off).

### Trigger at the raw write path

`on.table` is the **raw** table a connector fills, and `when` columns are that table's own. A
fused entity view (`connector.v_*`) and an `ext.*` table a [`kind: hybrid` target](../connectors/rest.md)
fills are refused: no connector writes rows into them. A view gets the better sentence:

```
$ lm process apply -f docs/apps/demo/processes/63-refused-fused-view.yaml
Error: API error 400: spec.on.table 'connector.v_acmecustomer' is a fused entity view — data.row.changed is born on the raw table a connector writes, never on a view, so this process could never fire — declare it on connector.t_acme_desk_erp or connector.t_acme_desk_crm
```

Declare one process per raw source, or move the condition to the source that carries it.

## Message templating

`{col}` inserts the row's value — `"Commande {order_no} du client {code} en litige — {amount} €"`.

- A `NULL` renders as an empty string.
- A timestamp column renders as its UTC instant, `2026-07-01T00:00:00Z`, whatever zone the hub
  runs in. A date column renders as its day, `2026-07-01`. A text column renders as stored: a CSV
  date stays as the file wrote it until a
  [`parse-date`](../connectors/transformers.md#parse-date-dates-are-31122019) types the column.
- A placeholder is exactly `{letters_digits_}`; anything else (`{}`, `{a-b}`, a lone brace)
  stays literal.
- Every `{col}` is checked at **apply** against the trigger table:

```
$ lm process apply -f docs/apps/demo/processes/61-refused-placeholder.yaml
Error: API error 400: message references {customer_name} — not a column of connector.t_acme_desk_orders
```

## Firing it

A decision appears a few seconds after the run that landed the row.

**Only new rows fire.** Re-running the unchanged day-2 file leaves the inbox as it was:

```
$ lm connector run acme-desk-orders
run scheduled: 20db972d-f540-4c0d-80b6-05ed4d4a534d
$ lm inbox
ID  WHEN         PROCESS        MESSAGE                                                   ENTITY_KEY
18  à l'instant  litige-ouvert  Commande SO-000013 du client P-1007 en litige — 860.00 €  96357a4a5750f97026f9ec5b8ce0f986517179f470e8a74b9fd013befe0e3622
```

**A row that matches two processes fires two decisions**, one per process —
[shown below](#watch-a-second-condition-on-the-same-table).

## Delivery is opt-in

A decision always lands in [the inbox](inbox.md). Reaching an external system also needs a
**webhook subscription** for the tenant; none exists by default. A human disposes of it:

```
$ lm inbox ack 18
acked.
```

The ack is itself an event (`annotation.decision.acked`), so a webhook subscribed to
`annotation.*` learns that someone handled it. Register a subscription with `lm integration add`
(needs `lm_integrator` **and** `lm_admin`) — the
[Admin API guide](../admin-console-guide.md#send-events-out-to-another-system) walks the whole
gesture, and the [WOW demo](../demo/wow-customer/README.md) plays it against a stand-in n8n.

## Failure contract

- **Refused at apply**: structural errors, a [trigger table that cannot
  fire](#trigger-at-the-raw-write-path), an unknown `when` column, an unknown placeholder, an emit
  type outside `decision.`, a quoted or out-of-domain value.
- **Fail-closed per process at runtime**: a process whose table vanished is skipped and logged;
  sibling processes keep firing.
- **Declarative only**: there is no script hook for processes.

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

The last one is the [view grammar](views.md#filter-grammar): a value is a **bare literal**.

| Who | What they can do |
|---|---|
| `lm_admin`, no `scope:` role | declare, list, disable, enable and remove processes for the whole tenant |
| `lm_admin` bound to scopes | the same, for the granted scopes only; anything else is `403 scope not granted` |
| without `lm_admin` | nothing — `API error 403 (forbidden)` |

In the TUI, `:processes` lists the declared rules; `e` re-opens a stored manifest in the `:new`
authoring loop.

## How to…

### Get told when an order lands in dispute

```bash
lm process apply -f docs/apps/demo/processes/10-litige.yaml
lm inbox
```

**Result:** every new `EN LITIGE` row lands as one decision in `lm inbox` (and the PWA's
*Décisions* tab), with the order's number, customer and amount.

### Watch a second condition on the same table

```yaml
--8<-- "docs/apps/demo/processes/20-grosse-commande.yaml"
```

Apply it, then load **day 3** — one more order, in dispute **and** above 1 000 €:

```
$ lm process apply -f docs/apps/demo/processes/20-grosse-commande.yaml
{"name":"grosse-commande","table":"connector.t_acme_desk_orders","emit":"decision.grosse-commande"}
$ docker cp docs/apps/demo/data/acme-orders-day3.csv lumnik-hub-1:/tmp/acme-desk/acme-orders.csv
$ lm connector run acme-desk-orders
run scheduled: 36f56e82-40cf-4ec1-9604-b48e46a1a327
$ lm inbox
ID  WHEN         PROCESS          MESSAGE                                                    ENTITY_KEY
25  à l'instant  grosse-commande  Grosse commande SO-000014 : 5000.00 € pour P-1003          3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
24  à l'instant  litige-ouvert    Commande SO-000014 du client P-1003 en litige — 5000.00 €  3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
```

**Result:** two processes, two decisions, the same `ENTITY_KEY`. `amount` is a `text` column (every
CSV column is); `amount>1000` compares it as a number, like a view filter.

How a number is compared:

| Value arrives as | Rule |
|---|---|
| text (every CSV cell) | must be a plain decimal: `1e5` never matches `amount>100`. `=` stays an exact text comparison |
| a number | compared by value: `0.00002` matches `x>1e-5`; `x=5` matches `5`, `5.0` and `5.00` |
| `NaN`, infinities | never match |

How a date is compared, against an operand written `YYYY-MM-DD` (midnight UTC) or as an ISO date-time:

| Value arrives as | Rule |
|---|---|
| a `DATE` column (a JDBC source) | the day it names, at midnight UTC: the same day whatever time zone the hub runs in |
| a timestamp column | to the microsecond |
| text (every CSV cell) | read as an ISO date or date-time, UTC when it carries no offset; anything else never matches |

`=` with a date against a `DATE` or timestamp column is equal as an instant, as in a view:
`at=2026-07-01T00:00:00Z` matches the row stamped at that instant. Against text, `=` stays an exact
text comparison.

### Turn a process off

```
$ lm process disable litige-ouvert
process litige-ouvert enabled=false
$ lm process list
NAME             TABLE                         EMIT                      ENABLED
grosse-commande  connector.t_acme_desk_orders  decision.grosse-commande  true
litige-ouvert    connector.t_acme_desk_orders  decision.litige-ouvert    false
$ lm process enable litige-ouvert
process litige-ouvert enabled=true
$ lm process rm litige-ouvert
process litige-ouvert deleted
$ lm process rm litige-ouvert
Error: API error 404: unknown process: litige-ouvert
```

**Result:** `disable` stops it firing and keeps the manifest; `enable` puts it back. `rm` removes
it for good, with no prompt. In the TUI's `:processes`, the same gestures are `^P` and `Del` on
the row under the cursor. There is no `lm process get`: `e` in the TUI re-opens the stored
manifest.

Each gesture is recorded in the [audit trail](../security/index.md#the-audit-trail), within
seconds; a refused one leaves no row:

```
$ echo "select operation, changes from core.audit_event where entity_key='litige-ouvert' order by id desc limit 4" | psql …
 operation |                                                                      changes
-----------+---------------------------------------------------------------------------------------------------------------------------------------------------
 DELETE    | {"scope": "desk"}
 UPDATE    | {"scope": "desk", "enabled": true}
 UPDATE    | {"scope": "desk", "enabled": false}
 APPLY     | {"scope": "desk", "table": "connector.t_acme_desk_orders", "manifest_sha256": "2ada066a02e114c10e8390a00254fb995d56d25dfac438db8cd97c81f3b362e9"}
(4 rows)
```

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| apply refused: `unknown key(s) in the manifest: …` | a misspelled field (`mesage:`) | the message lists what `kind:Process` honors — fix the spelling |
| apply refused `404`: `no schema for scope '…' — run discovery first` | `spec.scope` is not a discovered scope | `lm source rediscover --scope <tag>` (`lm_integrator` + `lm_admin`, a scope you hold); `lm source schema --scope <tag>` shows what it holds |
| apply refused: `spec.on.table '…' is not in the '…' scope` | the scope's card does not carry that table | `lm source schema --scope <tag>`, then fix the name |
| apply refused: `spec.on.table '…' is a fused entity view …` | the trigger is a `connector.v_*` view | declare the process on one of the raw tables the message names |
| apply refused: `spec.on.table '…' cannot fire …` | the table is in the card but no connector writes rows into it (an `ext.*` hybrid target) | declare the process on a `connector.t_*` table |
| apply refused: `when: column '…' is not in …`, or `'…' is not an allowed value for …` | an unknown column, or an `=` value outside its declared domain | fix the condition against the card |
| apply refused: `message references {…} — not a column of …` | a placeholder that is not a column of the trigger table | fix the placeholder, or drop it |
| apply refused: `spec.emit.type … must start with 'decision.'` | an emit type outside the taxonomy | `decision.<something>` |
| a process never fires on a re-run | the rows were already in the table | expected: only new rows fire |
| a declared, enabled process stopped firing | its trigger table vanished or broke at runtime | check the hub's log; sibling processes keep firing |
| a process still fires after you meant to turn it off | it is still enabled | `lm process disable <name>`, or `lm process rm <name>` |
| decisions show in `lm inbox` but never reach an external system | no webhook subscription for the tenant | `lm integration add` (`lm_integrator` + `lm_admin`) |
| `403 … scope not granted` | you are scope-bound and `spec.scope` is not one of your grants | ask for that `scope:` role, or declare under a granted scope |
| `API error 403 (forbidden): no details from the server` | you do not hold `lm_admin` | ask your administrator; no `lm` command lists your roles (`GET /api/bff/v1/me` returns them under `roles`) |

## See also

- [The demo desk](demo-desk.md) — the order book every screen here reads
- [Inbox](inbox.md) — where a human sees and disposes of the emitted decisions
- [Admin API guide — Integration Hub](../admin-console-guide.md#send-events-out-to-another-system) — the webhook subscription delivery depends on
- [Workflows](workflows.md) — declares what a status column's values *mean*, and watches for documents that stop moving
- [Kafka connector](../connectors/kafka.md) — events *into* the hub
