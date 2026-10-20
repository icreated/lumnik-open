# kind: Workflow — declare

> The fourth rung: a **declarative document lifecycle** over a status column ingestion already
> landed — states, aliases for the spellings the source really uses, the transition graph, and a
> threshold past which a document that has not moved is a decision.

| | |
|---|---|
| **Kind** | `Workflow` — `apps.lumnik.io/v1` |
| **Over** | one column of one table: a raw `connector.t_*`, or a fused `connector.v_*` (taxonomy only) |
| **Declares** | states with aliases, transitions, `provenance: declared`; optionally a business key and a dormancy threshold |
| **Produces** | a data-truth report at apply; one chunk in the scope's corpus; `decision.dormant.<name>` alerts and an observation tape when watched |
| **Commands** | `lm workflow apply` · `list` · `get` · `disable` · `enable` · `rm` — `lm_integrator`, scope-bound; one lifecycle per column |
| **Edition** | Open |

Legacy exports carry dirty statuses — `En cours`, `encours`, `fini`, `TERMINE ` with a trailing
space. A workflow declares what they mean, so the chat answers lifecycle questions from a
declared graph instead of inventing one.

## Features

- **[Dirty values resolve to declared states.](#alias-semantics)** Alias matching is trim and
  case-insensitive.
- **[A data-truth report that never blocks.](#the-data-truth-report-never-blocks)** Apply
  compares the declaration with the column — undeclared values, unobserved codes, blanks — and
  stores the manifest either way.
- **[Grounded answers.](#what-the-chat-answers-from-it)** The lifecycle is one chunk in the
  scope's corpus.
- **[Dormancy.](#dormancy-the-memory-of-the-breathing)** A key and a threshold turn the
  declaration into a watch: a document unmoved in a non-terminal state past the threshold is a
  `decision.dormant.*` in the inbox.
- **[A tape that outlives the alert.](#reading-the-tape)** Every watched document's state, since
  when, and whether it alerted.
- **[One column, one lifecycle.](#one-column-one-lifecycle)** A second lifecycle on the same
  column is refused, naming the first.

## Quickstart

!!! note "Every screen on this page reads the demo desk that ships with the repo"
    An order book whose `status` column carries seven spellings and one blank, under
    `docs/apps/demo/`. Land it first — [The demo desk](demo-desk.md#landing-the-sources) has the
    commands and the hand count. The screens below start on **day 1**.

```yaml
--8<-- "docs/apps/demo/workflows/10-order-lifecycle.yaml"
```

The same manifest, as the graph it declares:

```mermaid
stateDiagram-v2
  direction LR
  [*] --> BROUILLON
  BROUILLON --> EN_COURS
  BROUILLON --> ANNULE
  EN_COURS --> ANNULE
  EN_COURS --> TERMINE
  TERMINE --> [*]
  ANNULE --> [*]
  note right of EN_COURS
    «En préparation»
    aliases: "en cours", "encours"
    dormancy: unmoved 30d → alert
  end note
  note right of TERMINE
    alias: "fini" — terminal
  end note
```

```
$ lm workflow apply -f docs/apps/demo/workflows/10-order-lifecycle.yaml
{"name":"acme-order-lifecycle","scope":"desk","table":"connector.t_acme_desk_orders","column":"status","dataTruth":{"undeclared":[{"value":"EN LITIGE","rows":2},{"value":"Terminé","rows":1}],"unobserved":["ANNULE"],"nullOrBlank":1,"tableMissing":false,"keyColumnMissing":false,"keyNullOrBlank":0,"checkUnavailable":false,"unavailableReason":null}}
$ lm workflow list
NAME                  TABLE                         COLUMN  SCOPE  ENABLED
acme-order-lifecycle  connector.t_acme_desk_orders  status  desk   true
```

One `200`, stored and indexed, with what the declaration and the data disagree on:

- `EN LITIGE` sits on two orders and nobody declared it; `Terminé` (with its accent) on one;
- `ANNULE` is declared and no order has reached it;
- one order has no status at all.

`EN LITIGE` is deliberately **not** in the graph: it stays visible in the report, and the
[dormancy sweep](#an-undeclared-value-can-never-be-terminal) still watches it.

## The manifest

| Field | Required | Meaning |
|---|---|---|
| `metadata.name` | yes | kebab-case identifier (`[a-z0-9-]+`) — the name `apply` updates under, and the alert's `decision.dormant.<name>` |
| `spec.scope` | yes | the métier scope the lifecycle belongs to; must be a **discovered** scope — [below](#the-scope-must-exist-the-table-need-not) |
| `spec.provenance` | yes | **must be `declared`** — the only accepted value; the chat cites it |
| `spec.on.table` | yes | the table to watch — an ingest table (`connector.t_<table>`) or a fused entity view (`connector.v_<entity>`, always lower-case) |
| `spec.on.column` | yes | the column whose values this taxonomy explains — **whatever the source called it** |
| `spec.on.key` | no (required by `dormancy`) | the business key linking a document's successive versions |
| `spec.dormancy.after` | no (required by `dormancy`) | the sweep threshold, `[0-9]+[mhd]` (`30d`) — requires `spec.on.key`, and an ingest table, not a fused view |
| `spec.states[]` | yes (≥ 1) | the declared states |
| `spec.transitions[]` | no | the allowed edges of the graph |

**`states[]`** — `code` (required; unique, case- and trim-insensitive), `label`, `aliases[]` (the
dirty spellings that resolve to this code), `initial` (**exactly one** state must set it `true`),
`terminal` (a terminal state may carry no outgoing transition).

**`transitions[]`** — `from[]` (one edge can fan in from several states) and `to`.

An unknown key is refused, at every level:

```
$ lm workflow apply -f docs/apps/demo/workflows/67-refused-unknown-key.yaml
Error: API error 400: unknown key(s) in the manifest: spec.after — kind:Workflow honors root {apiVersion, kind, metadata, spec}, metadata {name}, spec {dormancy, on, provenance, scope, states, transitions}, spec.on {column, key, table}, spec.dormancy {after}, states[] {aliases, code, initial, label, terminal}, transitions[] {from, to}
```

### The scope must exist, the table need not

`spec.scope` must be a discovered scope; an unknown one is a `404`, as for a process:

```
$ lm workflow apply -f docs/apps/demo/workflows/65-refused-unknown-scope.yaml
Error: API error 404: no schema for scope 'dsk' — run discovery first
```

The table is **not** checked: you may declare a lifecycle before ingesting the data. The report
then says `tableMissing: true`.

### Names the source chose

`on.column` and `on.key` are the names **your source** uses, copied as they are — a Kafka
message's JSON keys become columns verbatim:

```yaml
on:
  table: connector.t_Commandes
  column: "Statut Commande"
  key: "N° Commande"
```

Quote them in the YAML if they contain spaces or punctuation; the hub adds the SQL quotes.

`on.table` must be a table ingestion owns — `connector.t_<table>`, or `connector.v_<entity>` —
spelled the way the hub created it (a connector's target keeps your capitals, a fused view is
lower case, neither contains a space). Anything else is refused, the hub's own tables included:

```
$ lm workflow apply -f docs/apps/demo/workflows/66-refused-foreign-table.yaml
Error: API error 400: spec.on.table is required and must name a table in the 'connector' schema that ingestion owns (connector.t_<table>) or a fused entity view (connector.v_<entity>, always lower-case), spelled the way the hub created it (letters, digits and underscores — a space is a typo): identity.app_user
```

A fused view can carry a **taxonomy** but not a `dormancy`: declare the dormancy on the ingest
table the entity fuses, or drop `spec.dormancy`.

### One column, one lifecycle

A *different* name declaring an already-declared `on.table` + `on.column` is refused, naming the
holder:

```
$ lm workflow apply -f docs/apps/demo/workflows/64-refused-same-column.yaml
Error: API error 400: column connector.t_acme_desk_orders.status already has a declared lifecycle: workflow 'acme-order-lifecycle' — a column has exactly one lifecycle; re-apply under the name 'acme-order-lifecycle' to update it
```

`apply` on the same name **is** the update. The rule is per tenant.

## Alias semantics

Alias and code matching is **trim + case-insensitive**: `"TERMINE "`, `"fini"`, `"En cours"` all
resolve. On the demo desk, day 1:

| Status as written | Rows | Resolves to |
|---|---|---|
| `En cours`, `encours` | 4 | `EN_COURS` |
| `fini`, `TERMINE ` | 2 | `TERMINE` |
| `BROUILLON` | 2 | `BROUILLON` |
| `EN LITIGE` | 2 | **undeclared** |
| `Terminé` | 1 | **undeclared** — an accent is not an alias anyone declared |
| *(empty)* | 1 | `nullOrBlank` |

Contradictions in the declaration are refused:

```
$ lm workflow apply -f docs/apps/demo/workflows/61-refused-alias-collision.yaml
Error: API error 400: alias 'en_cours' on state BROUILLON collides with state code
$ lm workflow apply -f docs/apps/demo/workflows/60-refused-two-initial.yaml
Error: API error 400: exactly one state must be initial: true (found 2)
$ lm workflow apply -f docs/apps/demo/workflows/62-refused-terminal-outgoing.yaml
Error: API error 400: terminal state 'TERMINE' has an outgoing transition
```

- an alias that **collides with a state code**;
- an alias **declared on two different states**;
- not **exactly one** `initial: true`;
- a `terminal: true` state with an outgoing transition.

## The data-truth report — never blocks

`apply` stores the manifest first, then compares it with what the column contains. The
comparison never becomes a refusal:

| Field | Meaning | On the demo desk (day 1) |
|---|---|---|
| `undeclared` | values in the data with no matching state or alias, each with its row count | `EN LITIGE` ×2, `Terminé` ×1 |
| `unobserved` | declared codes never seen in the column | `ANNULE` |
| `nullOrBlank` | rows with no status at all | `1` |
| `tableMissing` | the table does not exist (yet, or any more) | `false` |
| `keyColumnMissing` | `spec.on.key` names a column the table does not have — the only signal a misspelled key produces | `false` |
| `keyNullOrBlank` | rows whose key is `NULL` or blank: documents the dormancy sweep skips. `null` = not measured | `0` |
| `checkUnavailable` + `unavailableReason` | the comparison could not run (timeout, dropped column, revoked grant); the other fields are empty, **not** "the data agrees". Re-apply once fixed | `false` |

`keyNullOrBlank` counts presence only — a row with neither key nor status is in both counters,
and a present key is not proof it is unique or stable.

The comparison looks at the **200 most frequent distinct values**: on a high-cardinality column a
rare undeclared value can fall below the cut.

## What the chat answers from it

Applying a workflow indexes it as **one chunk** in the manifest's scope: states, aliases,
transitions, provenance. Two doors read differently:

| Door | Gesture | Answers about |
|---|---|---|
| semantic | `lm chat`, then `:scope desk` (or `lm scope set desk` first) | the **graph**: transitions, states, aliases |
| analytic | `lm ask --scope desk "…"`, the PWA's Chat tab | the **rows**: a document's current status |

The semantic door, asked over HTTP (the same chat `lm chat` reaches):

```bash
curl -s "$HUB/api/rag/chat" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"question":"Pourquoi je ne peux pas clôturer la commande SO-000004 ? Quelles sont les étapes avant TERMINE ?","scope":"desk"}'
```

Its `answer` field:

```text
Les étapes autorisées pour passer du statut BROUILLON sont : BROUILLON -> EN_COURS, BROUILLON -> ANNULE.
Les étapes autorisées pour passer du statut EN_COURS sont : EN_COURS -> ANNULE, EN_COURS -> TERMINE.
Aucune étape n'est autorisée pour passer directement de BROUILLON à TERMINE. […]
```

Every transition it names is declared. It does not know which state SO-000004 is in — the chunk
carries the graph, not the table. The analytic door does:

```
$ lm ask --scope desk "Dans quel statut est la commande SO-000004 ?"
La commande SO-000004 est en statut "BROUILLON".
  data as of: 2026-10-16T08:43:46.474408Z (last completed run)

  STATUS
  BROUILLON
  1 row returned

  SQL: SELECT status FROM "connector"."t_acme_desk_orders" WHERE order_no = 'SO-000004'
LIMIT 500
```

Asked *"why can't I close it"*, the analytic door abstains: it does not read the lifecycle chunk.

## Dormancy — the memory of the breathing

A legacy status is overwritten in place: the raw table says an order *is* `EN_COURS`, not since
when. A scheduled sweep records each document's state from the moment lumnik starts watching,
and alerts when one has sat unmoved, in a non-terminal state, past the declared threshold.

- Two fields switch it on: `spec.on.key` (`order_no` — links a document's successive rows) and
  `spec.dormancy.after` (`30d`, any `[0-9]+[mhd]`).
- The sweep runs every 15 minutes (`lumnik.workflow.sweep.every`,
  [Observability](../operations/observability.md#scheduled-background-work)).
- **The clock starts at lumnik's first sighting**, not at the real stall: declare `after: 14d`
  today over orders stuck for weeks, and the earliest alert is in fourteen days.

```
$ lm workflow apply -f docs/apps/demo/workflows/63-refused-dormancy-no-key.yaml
Error: API error 400: spec.dormancy requires spec.on.key (dormancy is per-document; the key links a document's versions)
```

The tape after the first sweep that saw the desk (day 1):

```
$ lm observations list
WORKFLOW              DOC        STATE      SINCE             ALERTED
acme-order-lifecycle  SO-000001  EN_COURS   2026-10-19 01:51  
acme-order-lifecycle  SO-000002  TERMINE    2026-10-19 01:51  
acme-order-lifecycle  SO-000003  TERMINE    2026-10-19 01:51  
acme-order-lifecycle  SO-000004  BROUILLON  2026-10-19 01:51  
acme-order-lifecycle  SO-000005  EN_COURS   2026-10-19 01:51  
acme-order-lifecycle  SO-000006  EN LITIGE  2026-10-19 01:51  
acme-order-lifecycle  SO-000007  EN_COURS   2026-10-19 01:51  
acme-order-lifecycle  SO-000008  Terminé    2026-10-19 01:51  
acme-order-lifecycle  SO-000010  EN LITIGE  2026-10-19 01:51  
acme-order-lifecycle  SO-000011  BROUILLON  2026-10-19 01:51  
acme-order-lifecycle  SO-000012  EN_COURS   2026-10-19 01:51  
$ lm observations stats
WORKFLOW              WATCHED  DORMANT  MEDIAN_DETECTION_S
acme-order-lifecycle  11       0        -
```

SO-000009 is absent: it has no status, so there is no state to watch. The states are the
**resolved** codes where an alias matched (`fini` reads `TERMINE`), the raw value where none did.

For the next screens the threshold was shortened to `after: 1m` and the manifest re-applied
under the same name (the shipped file keeps `30d`). Then **day 2** was loaded:

```bash
docker cp docs/apps/demo/data/acme-orders-day2.csv lumnik-hub-1:/tmp/acme-desk/acme-orders.csv
lm connector run acme-desk-orders
```

Two ticks later:

```
$ lm observations list
WORKFLOW              DOC        STATE      SINCE             ALERTED
acme-order-lifecycle  SO-000001  EN_COURS   2026-10-19 01:51  2026-10-19 02:06
acme-order-lifecycle  SO-000002  TERMINE    2026-10-19 01:51  
acme-order-lifecycle  SO-000003  TERMINE    2026-10-19 01:51  
acme-order-lifecycle  SO-000004  BROUILLON  2026-10-19 01:51  2026-10-19 02:06
acme-order-lifecycle  SO-000005  EN_COURS   2026-10-19 01:51  2026-10-19 02:06
acme-order-lifecycle  SO-000006  EN LITIGE  2026-10-19 01:51  2026-10-19 02:06
acme-order-lifecycle  SO-000007  EN_COURS   2026-10-19 01:51  2026-10-19 02:06
acme-order-lifecycle  SO-000008  Terminé    2026-10-19 01:51  2026-10-19 02:06
acme-order-lifecycle  SO-000010  EN LITIGE  2026-10-19 01:51  2026-10-19 02:06
acme-order-lifecycle  SO-000011  BROUILLON  2026-10-19 01:51  2026-10-19 02:06
acme-order-lifecycle  SO-000012  EN_COURS   2026-10-19 01:51  2026-10-19 02:06
acme-order-lifecycle  SO-000013  EN LITIGE  2026-10-19 02:06  2026-10-19 02:21
$ lm observations stats
WORKFLOW              WATCHED  DORMANT  MEDIAN_DETECTION_S
acme-order-lifecycle  12       10       900
```

- **Ten alerts**: nine at the first tick past the threshold, then `SO-000013` — new on day 2,
  first seen at 02:06 — one tick later.
- The two `TERMINE` orders never alert: a terminal state is excluded.
- `SO-000008` **does** alert: `Terminé`, with its accent, matched no alias, and [an undeclared
  value can never be terminal](#an-undeclared-value-can-never-be-terminal).
- The median detection delay is 900 s — one sweep interval, the floor.

What each alert says, in the inbox:

```
$ lm inbox --limit 3
ID  WHEN         PROCESS                        MESSAGE                                                                                                                                            ENTITY_KEY
47  à l'instant  workflow:acme-order-lifecycle  SO-000013 : observé immobile en « EN LITIGE » depuis le 2026-10-19 (première observation 2026-10-19) — seuil déclaré 1m dans acme-order-lifecycle  SO-000013
46  15min        workflow:acme-order-lifecycle  SO-000012 : observé immobile en « EN_COURS » depuis le 2026-10-18 (première observation 2026-10-18) — seuil déclaré 1m dans acme-order-lifecycle   SO-000012
45  15min        workflow:acme-order-lifecycle  SO-000011 : observé immobile en « BROUILLON » depuis le 2026-10-18 (première observation 2026-10-18) — seuil déclaré 1m dans acme-order-lifecycle  SO-000011
note: result hit the limit of 3 — there may be more
```

`ENTITY_KEY` is the business key. The wording names the date lumnik started watching, the date
it first saw the document and the declared threshold — never how long the order sat in the ERP.
The message's dates are UTC, while `lm observations list` shows your terminal's time zone: the
01:51 (Paris) sighting was 23:51 UTC the day before.

## Applying it

```bash
lm workflow apply -f docs/apps/demo/workflows/10-order-lifecycle.yaml
lm workflow list
lm workflow get acme-order-lifecycle      # the stored manifest, verbatim, comments included
lm observations list                      # the tape: what has not moved, stillest first
lm observations stats                     # per workflow: watched, dormant, median detection delay
lm inbox                                  # where decision.dormant.<name> lands
```

Turn it off, or remove it:

```
$ lm workflow disable acme-order-lifecycle
workflow acme-order-lifecycle enabled=false
$ lm workflow list
NAME                  TABLE                         COLUMN  SCOPE  ENABLED
acme-order-lifecycle  connector.t_acme_desk_orders  status  desk   false
$ lm workflow enable acme-order-lifecycle
workflow acme-order-lifecycle enabled=true
$ lm workflow rm acme-order-lifecycle
workflow acme-order-lifecycle deleted
$ lm workflow rm acme-order-lifecycle
Error: API error 404: unknown workflow: acme-order-lifecycle
```

`disable` stops the sweep (no new alerts) and removes the lifecycle from chat, keeping the
manifest and the tape; `enable` restores both. `rm` removes it for good, with no prompt — its
tape too (`lm observations stats` no longer lists it). Each gesture, and every apply, is recorded in the [audit trail](../security/index.md#the-audit-trail).

| Command | Role | Scope-bound |
|---|---|---|
| `lm workflow apply`, `list`, `get`, `disable`, `enable`, `rm` | `lm_integrator` | yes — outside your grants, `403 scope not granted`; `list` shows only your scopes |
| `lm observations list`, `stats` | `lm_integrator` **and** `lm_admin` (or `lm_superadmin`) | yes — they list only the workflows of your scopes |

## How to…

### Declare the lifecycle a legacy status column already has

```bash
lm workflow apply -f docs/apps/demo/workflows/10-order-lifecycle.yaml
lm workflow get acme-order-lifecycle
```

**Result:** the manifest is stored and indexed in one `200`, with the data-truth report. The
semantic chat in that scope now answers transition questions from the declared graph.

### Get told when a document stops moving

The demo manifest already carries `spec.on.key` and `spec.dormancy.after`: applying it switches
the sweep on. Alerts land in `lm inbox` (and the PWA's *Décisions* tab) for users holding the
workflow's scope and for users with no `scope:` role. To push them to an external system,
`lm integration add` ([Processes](processes.md#delivery-is-opt-in)).

**Don't treat the inbox as a register of everything asleep.** A document alerts once per stall,
and the alert ages out with the event log (30 days by default). The tape keeps it — see
[Re-alert semantics](#re-alert-semantics).

### Add the value the report says nobody declared

`dataTruth.undeclared` named `EN LITIGE`. Add it as a state (or an alias of one) and re-apply
under the same name. **Result:** the report no longer lists it, the chat knows what it means, and
the sweep watches it as a declared state.

## Checking a proposal dossier

A business person can describe a problem on lumnik.fr and download a **proposal dossier**. Before
turning it into manifests, ask the hub what it can establish on your data, against a workflow you
already applied. No `lm` command does this; the door is HTTP:

```bash
curl -X POST "$HUB/api/proposal/check?workflow=<name>" \
  -H "Authorization: Bearer $TOKEN" \
  --data-binary @dossier.md > dossier-checked.md
```

It needs `lm_integrator`; `$TOKEN` is the token `lm login` stored (see the
[HTTP API quickstart](../rag/http-api.md#quickstart-one-chat-call-with-curl)). The answer is the
same file, checked; nothing is applied, and the original answers are untouched.
Without `workflow=`, the dossier is only recomputed.

| Point | Confirmed when | Refuted when |
|---|---|---|
| *a status column exists* | the comparison read the column | the table is there and the column is not |
| *an identifier column exists* | the table has the `spec.on.key` column | the table is there and does not have it (judged only if the status column exists) |
| *the target is accepted by the watch validation* | the workflow is stored | never |

- What only a person can establish (*the column describes the lifecycle*, *the identifier is
  stable*) stays *to be established*.
- A table not ingested yet, a timeout or a missing grant leave a point unchanged.
- The proof is about your data **today**.
- A dossier for another catalogue version is returned recomputed, unverified.
- The answer is the same whether the workflow does not exist, is not yours, or no longer parses.
- A malformed dossier is a `400` with the reason (`duplicate-key`); a file that is not UTF-8 is a
  `400` `malformed-header`.

## Drafting a manifest from a dossier

`lm proposal draft` writes the **start** of the manifest a dossier proposes. It does not contact
the hub:

```bash
lm proposal draft dossier.md \
  --name orders-lifecycle --scope sales \
  --table connector.t_commandes --column statut --key n_commande --after 14d \
  -o orders-lifecycle.yaml
```

- Every flag is optional; a value you leave out is left out of the file, with a comment saying
  where it goes.
- States and transitions are never generated: you write them before `lm workflow apply`.
- `--after` without `--key` is accepted here and refused at apply.
- The header comments list each point, **refuted first**, then missing, unverified, confirmed;
  what no tool can check is marked **to establish yourself**.
- `-o` never overwrites a file; without it the draft goes to stdout. The dossier can come from
  stdin (`lm proposal draft -`).
- A dossier with no `stalled-documents@1` lead, or two, is refused; other leads are named on
  stderr.

The loop: draft → complete and `lm workflow apply -f` → check the dossier against the stored
workflow (the `curl` above) → draft again from the checked dossier, to a new file.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| apply refused: `unknown key(s) in the manifest: …` | a misspelled or misplaced field | the message lists what `kind:Workflow` honors, level by level |
| apply refused `404`: `no schema for scope '…' — run discovery first` | `spec.scope` is not a discovered scope | `lm source rediscover --scope <tag>` (`lm_integrator` + `lm_admin`, a scope you hold), then re-apply |
| apply refused: `column … already has a declared lifecycle: workflow '…'` | a *different* name declares this `on.table` + `on.column` | re-apply under the existing name, or point at a different column |
| apply refused: `column … already has a declared lifecycle, in a métier you do not hold` | the same, in a scope you do not hold | ask the integrator who owns that scope |
| apply refused: `spec.dormancy requires spec.on.key …` | `spec.dormancy.after` without `spec.on.key` | add `spec.on.key` |
| apply refused: `spec.dormancy cannot be declared on a fused entity view …` | `on.table` is a `connector.v_*` view with `spec.dormancy` | declare it on the ingest table, or drop it |
| apply refused: `spec.on.table … must name a table in the 'connector' schema that ingestion owns` | another schema, or one of the hub's own tables | `connector.t_<the connector's target>` |
| apply refused: `alias '…' … collides with state code` / `exactly one state must be initial` / `terminal state '…' has an outgoing transition` | a contradiction in the declaration | fix the graph |
| dormancy declared, but no alert ever arrives | `spec.on.key` names a column the table does not have | check `keyColumnMissing` in the apply response |
| a document stuck for weeks does not alert on day one | the threshold counts from lumnik's first sighting | wait `after` from the apply date; `lm observations list` shows `SINCE` |
| a document is missing from `lm observations list` | its key or its status is `NULL` or blank | fix it at the source; `keyNullOrBlank` and `nullOrBlank` count them |
| a status value never shows up in chat answers | undeclared | check `dataTruth.undeclared`, add it, re-apply |
| `lm ask` abstains on a "why can't I close it" question | the analytic door does not read the lifecycle | `lm chat` with the scope |
| `403 … scope not granted` | you do not hold the `spec.scope` role | ask for that `scope:` role |
| `API error 403 (forbidden): no details from the server` | you lack `lm_integrator`, or `lm_admin` for `lm observations` | ask your administrator; no `lm` command lists your roles (`GET /api/bff/v1/me` returns them under `roles`) |

## See also

- [The demo desk](demo-desk.md) — the order book every screen here reads
- [Processes](processes.md) — react to a row landing
- [Inbox](inbox.md) — where a `decision.dormant.<workflow>` alert surfaces
- [CLI & TUI reference](../cli/lm-reference.md) — `lm observations list` and the `:observations` screen
- [Ask & honesty](../transparency/ask-honesty.md) — the abstention contract a grounded answer serves

## Advanced: the sweep in detail

??? note "What the sweep skips, the alert's wording, re-alerts, the tape, and scale"

    ### What the sweep does not see

    A document with a `NULL` or blank **key**, or a `NULL` or blank **status**, is skipped —
    permanently: there is nothing to follow it by, or no state to watch (`SO-000009` on the demo
    desk). The apply report counts both: `keyNullOrBlank` and `nullOrBlank`.

    ### The alert, verbatim

    From the demo desk, with `dormancy.after` shortened to `1m`:

    ```
    SO-000004 : observé immobile en « BROUILLON » depuis le 2026-10-18 (première observation 2026-10-18) — seuil déclaré 1m dans acme-order-lifecycle
    ```

    The shape — `<key> : observé immobile en « <state> » depuis le <date> (première observation
    <date>) — seuil déclaré <after> dans <workflow>` — never asserts how long the document sat in
    the source. It is emitted as `decision.dormant.<workflow>` and appears in the
    [inbox](inbox.md) as `workflow:<workflow>`.

    ### Re-alert semantics

    A document alerts **once** per stall. It alerts again only after it moved (a new state resets
    its clock) and then sat unmoved past the threshold once more.

    | | |
    |---|---|
    | **A permanently stuck document** | alerts once; the alert leaves `lm inbox` with the event retention (`lumnik.events.retention-days`, 30 by default) |
    | **What to do** | ack the alert into your own tracker, or raise `lumnik.events.retention-days` beyond your review cadence |
    | **What stays** | the tape: `lm observations list` keeps `ALERTED` until the document moves |

    ### Reading the tape

    `lm observations list` — the watched documents of every workflow in your scopes, **stillest
    first** (`--limit`, default 200, at most 1000). `SINCE` is when lumnik started observing that
    state; `ALERTED` is empty until the sweep alerts. `lm observations stats` sums it up per workflow. The TUI's
    `:observations` screen shows the same, with humanized ages. Both need `lm_admin` (or
    `lm_superadmin`) on top of `lm_integrator`.

    ### Scale

    Each tick reads the latest state of every business key, in the database. On a table with
    millions of distinct documents, lengthen `lumnik.workflow.sweep.every`. That read spills to
    temporary files and counts against PostgreSQL's `temp_file_limit`; past it, the tick logs
    `dormancy sweep failed …`.

    The tape and its transition journal are never pruned; only `lm workflow rm` clears them.

    A workflow whose table, status column or key column is gone is skipped, logged once:
    `dormancy sweep standing down for tenant … workflow …`; it resumes by itself, with one log
    line, when the table is readable again. Any other failure (timeout, revoked grant) logs
    `dormancy sweep failed for tenant … workflow … — skipped` on every tick. Other workflows are
    never affected.

    ### An undeclared value can never be terminal

    `EN LITIGE` is not declared, so it cannot be `terminal: true`: a document sitting in it is
    watched like any non-terminal state. The apply report names the value; the sweep names the
    documents asleep in it.
