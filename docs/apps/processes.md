# kind: Process — react

> A **declarative watcher** on ingested rows. When a row lands that matches your conditions, the
> process emits a `decision.*` event. It always lands in the [inbox](inbox.md). A webhook
> subscription carries it on to n8n, Make, Zapier or your own endpoint.

| | |
|---|---|
| **Kind** | `Process` — `apps.lumnik.io/v1` |
| **Trigger** | `data.row.changed` on a raw `connector.t_*` table, or on a REST connector's `ext.*` table — the only trigger |
| **Conditions** | the [view filter grammar](views.md#filter-grammar), ANDed, over the table's own columns |
| **Emits** | one `decision.<name>` event per matching row, `{col}`-templated, fields included |
| **Commands** | `lm process apply` · `list` · `disable` · `enable` · `rm` — all need `lm_admin` |
| **Edition** | Open |

A process changes nothing and calls no one: it says, once, that a row worth a human's attention
just landed, in the row's own words. The complete event flow is in [How lumnik breathes](../respiration.md).

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
- **One broken process stops alone.** A process whose table vanished is skipped; the others keep
  firing.
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
Applied process "litige-ouvert" → table connector.t_acme_desk_orders, emits decision.litige-ouvert
$ lm process list
NAME           TABLE                         EMIT                    ENABLED
litige-ouvert  connector.t_acme_desk_orders  decision.litige-ouvert  true
$ lm inbox
ID  WHEN  PROCESS  MESSAGE  ENTITY_KEY
```

Nothing fired: the two orders already in `EN LITIGE` were there before the process. Now load
**day 2** — the next morning's export, with one new order in dispute:

```
$ docker compose -f docker-compose.selfhost.yml cp docs/apps/demo/data/acme-orders-day2.csv hub:/tmp/acme-desk/acme-orders.csv
$ lm connector run acme-desk-orders
run scheduled: dc0a38a3-3caf-40b8-9461-4fd202978dfb
$ lm run list --connector acme-desk-orders
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
dc0a38a3  acme-desk-orders  default   Completed  13  13   0        0             2026-10-19 01:42  2026-10-19 01:42
7b972d2b  acme-desk-orders  default   Completed  12  12   0        0             2026-10-19 01:38  2026-10-19 01:38
```

(`hub` is the hub's service in the self-host stack; the file goes on the hub's disk.)
Thirteen rows read, one of them new. A few seconds later:

```
$ lm inbox
ID  WHEN      PROCESS        MESSAGE                                                   ENTITY_KEY
18  just now  litige-ouvert  Commande SO-000013 du client P-1007 en litige — 860.00 €  96357a4a5750f97026f9ec5b8ce0f986517179f470e8a74b9fd013befe0e3622
```

One decision for the one new row. `ENTITY_KEY` points at exactly that row.

**The run's decisions, without guessing when.** A run ends a few seconds before its rows are
evaluated. `lm run get --wait-evaluated` waits until every enabled process has read them:

```
$ lm run get 5901a04b --wait-evaluated
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED             EVALUATED
5901a04b  acme-desk-orders  default   Completed  13  13   0        0             2026-10-24 23:28  2026-10-24 23:28  yes
$ lm inbox --process litige-ouvert
ID  WHEN      PROCESS        MESSAGE                                                   ENTITY_KEY
63  just now  litige-ouvert  Commande SO-000013 du client P-1007 en litige — 860.00 €  96357a4a5750f97026f9ec5b8ce0f986517179f470e8a74b9fd013befe0e3622
```

Once `EVALUATED` reads `yes`, an empty inbox means no decision, not "not yet". `--wait-evaluated`
exits `1` when the hub cannot tell (a process changed during the run): run it again.

**What a webhook receives.** The same decision, as JSON. `fields` carries the columns the message
names, as data: an automation reads values, not a sentence. Add `fields:` for a value the
automation needs and the reader does not:

```yaml
  emit:
    type: decision.litige-ouvert
    message: "Commande {order_no} du client {code} en litige — {amount} €"
    fields: [ordered_on]
```

```
[fake-n8n] POST /hook received:
{
  "entity": "connector.t_acme_desk_orders",
  "fields": {
    "code": "P-1007",
    "amount": "860.00",
    "order_no": "SO-000013",
    "ordered_on": "2026-08-14"
  },
  "message": "Commande SO-000013 du client P-1007 en litige — 860.00 €",
  "process": "litige-ouvert",
  "entity_key": "96357a4a5750…"
}
```

`ordered_on` is in the payload, not in the inbox sentence. Wiring the webhook:
[Outputs](../outputs.md#wire-a-webhook).

## The manifest

| Field | Required | Meaning |
|---|---|---|
| `metadata.name` | yes | kebab-case (`[a-z0-9-]+`), at most 112 characters; becomes the event's `process` |
| `spec.scope` | yes | the scope whose [schema card](../rag/http-api.md#schema-admin) declares `on.table` |
| `spec.on.event` | yes | **must be `data.row.changed`** |
| `spec.on.table` | yes | the table the connector fills (`connector.t_<name>`) — see [Watch the table the connector fills](#watch-the-table-the-connector-fills) |
| `spec.when[]` | yes (≥ 1) | conditions in the [view filter grammar](views.md#filter-grammar), ANDed. The right-hand side may name another column of the row: [`{column}`](#compare-two-columns-of-the-same-row) |
| `spec.emit.type` | yes | **must start with `decision.`** |
| `spec.emit.message` | yes | the human sentence, `{col}`-templated |
| `spec.emit.fields` | no | columns the payload's `fields` carries besides those the message names — what an automation needs but a reader should not see; each must be a column of `on.table` |
| `spec.emit.key` | no | the column whose value is the decision's `ENTITY_KEY` (`key: sample`): the document a reader recognises; a column of `on.table`. Absent, or empty on the row: the row hash — [below](#key-the-decision-by-the-document) |

`apply` refuses a key it does not know and names it — a misspelled `mesage:` is a `400`, not a
decision with no text.

A stored process is `enabled` by default (the `ENABLED` column of `lm process list`); only
enabled processes fire. See [Turn a process off](#turn-a-process-off).

### Watch the table the connector fills

`on.table` is the table a connector fills, and `when` columns are that table's own. A table the
scope's card does not carry yet is refused. So is a table no connector writes rows into: the process
could never fire.

### Watch an Entity

`on.table` can be the view of a `match:` Entity: `connector.v_<entity>`. A change in any of the
Entity's source tables re-evaluates the **fused row** it belongs to. So `when` may read columns that no
single source holds: a credit limit from the ERP and a segment from the CRM.

```yaml
spec:
  scope: desk
  on: { event: data.row.changed, table: connector.v_acmecustomer }
  when: [ city=Lyon ]
  emit: { type: decision.lyon, message: "Client {name} à Lyon" }
```

The full file is `docs/apps/demo/processes/30-lyon-customer.yaml`.

- **One decision per event and per fused row.** A change that still satisfies the rule decides
  again, as it does on a table. A replay of the same event does not.
- **Columns:** `when`, `{col}` in the message, `emit.fields` and `emit.key` are columns of the
  Entity. Without `emit.key`, the decision's key is the Entity's canonical key.
- **Isolation:** another tenant's rows with the same key are never fused in.
- **Refused at apply:** an Entity with `resolution:` (not supported yet), an Entity one of whose
  sources or lookups no connector fills, and a match or join key the hub cannot serve (below).
- **Speed:** the hub prepares an index on each source table by itself, in the background. You
  declare nothing. Until it is ready the process works, more slowly, and the apply warns:
  `managed index on connector.t_results is pending; Entity Process reads may be slower until it is ready`.
  The warning disappears once the index exists.
- **Match keys it can serve:** text, whole numbers, and timestamps with a time zone. A decimal key,
  or a date matched with a timestamp, is refused: at the process apply, and at an Entity re-apply
  while a process listens on it. Make the key a text or a whole number in the Entity.
  A process applied on such a key by an older hub keeps running, slowly: change the key, then apply the process again.
- **A change in a lookup:** see [below](#a-change-in-a-lookup).
- **Not covered:** a deleted row, the old key of a row whose match key changed, and the rows already
  there when the process is applied.

The first apply warns on stderr, one line per source table:

```
$ lm process apply -f docs/apps/demo/processes/30-lyon-customer.yaml
Applied process "lyon-customer" → table connector.v_acmecustomer, emits decision.lyon
warning: managed index on connector.t_acme_desk_erp is pending; Entity Process reads may be slower until it is ready
warning: managed index on connector.t_acme_desk_crm is pending; Entity Process reads may be slower until it is ready
```

A few seconds later, the same command says only the first line:

```
$ lm process apply -f docs/apps/demo/processes/30-lyon-customer.yaml
Applied process "lyon-customer" → table connector.v_acmecustomer, emits decision.lyon
```

An Entity that matches on a decimal applies. A process on it does not
(`docs/apps/demo/entities/66-partner-by-amount.yaml`, `docs/apps/demo/processes/63-refused-decimal-key.yaml`):

```
$ lm entity apply -f docs/apps/demo/entities/66-partner-by-amount.yaml
Applied entity "PartnerByAmount" → connector."v_partnerbyamount"
$ lm process apply -f docs/apps/demo/processes/63-refused-decimal-key.yaml
Error: API error 400: an index on connector.t_due_partner cannot serve this key: it is read through trim_scale, and under row-level security PostgreSQL does not let an index serve a call to it — use a text, a whole-number or a timestamptz match key
```

#### A change in a lookup

An Entity can enrich its rows from a lookup table (`joins:`): a register of instruments beside the
results, for instance. A change in the lookup re-evaluates **every fused row it reaches**. A
recalibration in the register re-evaluates every result of that instrument.

```yaml
spec:
  scope: lab
  on: { event: data.row.changed, table: connector.v_labresult }
  when: [ "measured_at>{calib_valid_until}" ]   # the date is the register's, the result is the other table's
  emit: { type: decision.out-of-calibration, message: "{result_id} was measured out of calibration", key: result_id }
```

- **How many rows:** as many as the lookup row reaches. Nothing caps it.
- **When the decisions come:** not at once. They appear in `lm inbox` as the background work
  progresses. The hub log says when it is over:
  `process fan-out done: process 'out-of-calibration', … 42317 keys reached`.
- **If the database is down** when the lookup row changes, the hub log says the change was not
  queued (`could not be queued — its fused rows are NOT re-evaluated`). Save the lookup row again.
- **A restart is safe.** The work resumes. A row is never decided twice for the same change.
- **The lookup key:** a lookup row without a key, or deleted, reaches nothing. A lookup row whose key
  changed reaches the rows of its new key only.
- **Speed:** the hub builds an index by itself first. Until it is ready the work waits. Nothing is lost.
- **Join keys it can serve:** text, numbers, and two dates or two timestamps. A date or a timestamp
  joined to a **text** column is refused at apply:
  `an index on connector.t_results cannot serve this key: … — join the two columns as text, as numbers, or as two dates or two timestamps`.
  Give both columns the same kind.
- **A process switched off** does not decide. Its work waits until you turn it on again.
- **Not covered:** the rows already there when the process is applied.

### Compare two columns of the same row

A condition can compare a column with another column of the same row. Write the other column in
braces: `{column}`. The braces tell it from a literal, so `operator=reviewer` still compares
`operator` with the text `reviewer`.

```yaml
spec:
  scope: lab
  on: { event: data.row.changed, table: ext.lab_results }
  when:
    - "measured_at>{calib_valid_until}"   # measured after the instrument's calibration ran out
  emit: { type: decision.out-of-calibration, message: "{sample} was measured out of calibration" }
```

Quote the entry: in a YAML list, a bare `{` opens a map.

- **Operators:** `=`, `>`, `>=`, `<`, `<=`. `~` takes a text, not a column.
- **Kinds:** both columns are numbers, both are dates, or both are texts. A number against a date is
  refused at apply. A type the hub cannot order (an interval, a boolean) takes `=` against its own type.
- **Texts:** `=` on two texts is exact: `01` is not `1`. `<` and `>` read a text holding a number or a date as one.
- **Names:** the name in braces must be a column of the table. `{limit-value}` works for a column called `limit-value`.
- **Nulls:** a null on either side never matches, as for a literal.
- **Dates:** a date and a timestamp compare as instants. A date is read as midnight UTC.
- **Two bounds:** conditions are ANDed. For "before the start **or** after the end", declare two processes.
- **Views:** a view filter stays literal-only. This is for processes.

## Message templating

`{col}` inserts the row's value — `"Commande {order_no} du client {code} en litige — {amount} €"`.

- A `NULL` renders as an empty string.
- A timestamp column renders as its UTC instant, `2026-07-01T00:00:00Z`, whatever zone the hub
  runs in. A date column renders as its day, `2026-07-01`. A text column renders as stored: a CSV
  date stays as the file wrote it until a
  [`parse-date`](../connectors/transformers.md#parse-date-dates-are-31122019) types the column.
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
ID  WHEN      PROCESS        MESSAGE                                                   ENTITY_KEY
18  just now  litige-ouvert  Commande SO-000013 du client P-1007 en litige — 860.00 €  96357a4a5750f97026f9ec5b8ce0f986517179f470e8a74b9fd013befe0e3622
```

**A row that matches two processes fires two decisions**, one per process —
[shown below](#watch-a-second-condition-on-the-same-table).

A Process does not evaluate rows that existed before it was applied. Only subsequent inserts and source changes can trigger it.

### Processes on REST tables

A REST connector's `ext.*` table can carry a process, like a `connector.t_*` table.
Apply the process after the first run completes: the table exists only then.
A new row fires the process. So does a row whose content changed at the source.
A re-read that changes nothing fires nothing.
`when` and `{col}` name the table's promoted columns.

A REST connector fills `ext.lab_results` (`unique_key: $.sample`). After its first run:

```yaml
--8<-- "docs/apps/demo/processes/40-rest-over-limit.yaml"
```

```
$ lm process apply -f docs/apps/demo/processes/40-rest-over-limit.yaml
Applied process "lab-over-limit-c" → table ext.lab_results, emits decision.lab-over-limit-c
```

Nothing changed at the source:

```
$ lm connector run lab-results
run scheduled: 2d3f054d-0e61-4053-b227-54066ab3cf35
$ lm inbox --process lab-over-limit-c
ID  WHEN  PROCESS  MESSAGE  ENTITY_KEY
```

Then plate `AR-22` is counted at 2400 colonies at the source, and the connector runs again:

```
$ lm connector run lab-results
run scheduled: fc7ff0f9-64f1-4735-8cfb-4ebf0ba9d2d7
$ lm inbox --process lab-over-limit-c
ID  WHEN      PROCESS           MESSAGE                                                                                  ENTITY_KEY
8   just now  lab-over-limit-c  AR-22 (Fleming): 2400 CFU/plate, a clean plate holds at most 100 — state to_be_verified  AR-22
```

### Key the decision by the document

By default a decision's `ENTITY_KEY` is the row hash. A reader does not recognise a hash.
`spec.emit.key` names the column that identifies the document. A value longer than 200
characters shows as a `sha256:` digest; the payload's `entity_key` keeps it whole.

```yaml
emit:
  type: decision.plate-over-limit-c
  key: sample
  message: "{sample} ({research_group}): {result} {unit}, a clean plate holds at most 100 — state {review_state}"
```

A SENAITE LIMS read over REST, after one plate is counted at 2400 colonies:

```
$ lm inbox --process scenario-senaite-over-limit-c
ID     WHEN      PROCESS                        MESSAGE                                                                                                     ENTITY_KEY
23570  just now  scenario-senaite-over-limit-c  analysisrequest-24 (Fleming group): 2400 CFU/plate, a clean plate holds at most 100 — state to_be_verified  analysisrequest-24
```

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
gesture, and the [WOW demo](../wow.md#7-react-to-it-kind-process) plays it against a stand-in n8n.

## Refusals

```
$ lm process apply -f docs/apps/demo/processes/60-refused-unknown-column.yaml
Error: API error 400: when: column 'montant' is not in connector.t_acme_desk_orders — fix the column name or rediscover the scope
$ lm process apply -f docs/apps/demo/processes/61-refused-placeholder.yaml
Error: API error 400: message references {customer_name} — not a column of connector.t_acme_desk_orders
$ lm process apply -f docs/apps/demo/processes/62-refused-emit-type.yaml
Error: API error 400: spec.emit.type is required and must start with 'decision.' — the governed taxonomy
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
*Inbox* tab), with the order's number, customer and amount.

### Watch a second condition on the same table

```yaml
--8<-- "docs/apps/demo/processes/20-grosse-commande.yaml"
```

Apply it, then load **day 3** — one more order, in dispute **and** above 1 000 €:

```
$ lm process apply -f docs/apps/demo/processes/20-grosse-commande.yaml
Applied process "grosse-commande" → table connector.t_acme_desk_orders, emits decision.grosse-commande
$ docker compose -f docker-compose.selfhost.yml cp docs/apps/demo/data/acme-orders-day3.csv hub:/tmp/acme-desk/acme-orders.csv
$ lm connector run acme-desk-orders
run scheduled: 36f56e82-40cf-4ec1-9604-b48e46a1a327
$ lm inbox
ID  WHEN      PROCESS          MESSAGE                                                    ENTITY_KEY
25  just now  grosse-commande  Grosse commande SO-000014 : 5000.00 € pour P-1003          3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
24  just now  litige-ouvert    Commande SO-000014 du client P-1003 en litige — 5000.00 €  3a47cea8e762663021d4c5bba36b5a410ad0731a1271e17706ce6976ee8c138b
```

**Result:** two processes, two decisions, the same `ENTITY_KEY`. `amount` is a text column, as
every CSV column is; `amount>1000` still compares it as a number, like a view filter.

A process compares numbers and dates like a [view filter](views.md#filter-grammar). A CSV amount
must be a plain decimal (`5000.00`); a date is read as UTC.

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

Each gesture is recorded in the [audit trail](../security/index.md#the-audit-trail).

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| apply refused: `unknown key(s) in the manifest: …` | a misspelled field (`mesage:`) | the message lists what `kind:Process` honors — fix the spelling |
| apply refused `404`: `no schema for scope '…' — run discovery first` | `spec.scope` is not a discovered scope | `lm source rediscover --scope <tag>` (`lm_integrator` + `lm_admin`, a scope you hold); `lm source schema --scope <tag>` shows what it holds |
| apply refused: `spec.on.table '…' is not in the '…' scope` | the scope's card does not carry that table: a misspelled name, or an `ext.*` table no run has created yet | `lm source schema --scope <tag>`, then fix the name. For an `ext.*` table, wait for the first run to complete (the hub refreshes the card itself); if the table is still missing, `lm source rediscover --scope <tag>` |
| apply refused: `spec.on.table '…' is a resolution: Entity …` | a process on an Entity that has a `resolution:` is not supported yet | declare it on a `match:` Entity, or on one of the raw tables the message names |
| apply refused: `spec.on.table '…' joins connector.t_…, which cannot fire` | no connector fills one of the Entity's lookup tables | run the connector that fills it, then apply again |
| apply refused: `an index on … cannot serve this key … join the two columns as text …` | a join compares a date or a timestamp with a text column | give both columns the same kind in the connector or the Entity |
| a lookup change decided nothing yet | the work runs in the background, and waits for its index | wait; the decisions arrive in `lm inbox`. A process turned off waits too |
| apply refused: `spec.on.table '…' is the view of an Entity whose stored definition cannot be read` | the Entity was applied by another hub version | apply the Entity again, then the process |
| apply refused: `spec.on.table '…' fuses connector.t_…, which cannot fire` | no connector fills one of the Entity's source tables | run the connector that fills it, then apply again |
| apply refused: `spec.on.table '…' cannot fire …` | the table is in the card but no connector writes rows into it | declare the process on a `connector.t_*` or an `ext.*` table |
| a process on an `ext.*` table never fired on the rows already there | it was applied after those rows landed | expected: see the limit above. Only new rows and source changes fire |
| apply refused: `when: column '…' is not in …`, or `'…' is not an allowed value for …` | an unknown column, or an `=` value outside its declared domain | fix the condition against the card |
| apply refused: `when: cannot compare '…' (…) with '…' (…)` | the two columns are not of one kind (a date against a number) | compare columns of the same kind |
| apply refused: `when: column '…' is not in …` on a `{column}` | the column in braces is not a column of the trigger table | fix the name |
| apply refused: `message references {…} — not a column of …` | a placeholder that is not a column of the trigger table | fix the placeholder, or drop it |
| apply refused: `spec.emit.type … must start with 'decision.'` | an emit type outside the taxonomy | `decision.<something>` |
| a process never fires on a re-run | the rows were already in the table | expected: only new rows fire |
| a declared, enabled process stopped firing | its table vanished | check the hub's log; the other processes keep firing |
| a process still fires after you meant to turn it off | it is still enabled | `lm process disable <name>`, or `lm process rm <name>` |
| decisions show in `lm inbox` but never reach an external system | no webhook subscription for the tenant | `lm integration add` (`lm_integrator` + `lm_admin`) |
| `Error: access refused (403): scope not granted` | you are scope-bound and `spec.scope` is not one of your grants | ask for that `scope:` role, or declare under a granted scope |
| `API error 403 (forbidden): no details from the server` | you do not hold `lm_admin` | ask your administrator; no `lm` command lists your roles (`GET /api/bff/v1/me` returns them under `roles`) |

## See also

- [The demo desk](demo-desk.md) — the order book every screen here reads
- [Inbox](inbox.md) — where a human sees and disposes of the emitted decisions
- [Admin API guide — Integration Hub](../admin-console-guide.md#send-events-out-to-another-system) — the webhook subscription delivery depends on
- [Workflows](workflows.md) — declares what a status column's values *mean*, and watches for documents that stop moving
- [Kafka connector](../connectors/kafka.md) — events *into* the hub
