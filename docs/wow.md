# The WOW, step by step

<section class="lm-hero" markdown>
<p class="lm-eyebrow">Show, don't explain</p>
<p class="lm-lead" markdown="span">A SOAP Siebel and a CSV become one canonical **Customer** — served over REST, then asked in natural language. The files below are the demo's real files.</p>
<div class="lm-meta" markdown>
<div class="lm-meta-item" markdown>
<strong>What you will see</strong>

Connectors, entity fusion, sourced answers, and the first operational rungs above them.
</div>
<div class="lm-meta-item" markdown>
<strong>Best reader</strong>

Someone who wants value before deep reference.
</div>
<div class="lm-meta-item" markdown>
<strong>Then do this</strong>

Replay the same flow by hand on the deployed stack.
</div>
<div class="lm-meta-item lm-meta-item--accent" markdown>
<strong>Why it matters</strong>

One fused customer shows what the hub does.
</div>
</div>
</section>

<div class="lm-pane lm-pane--accent" markdown>
<div class="lm-kicker">Read this one, run the other one</div>

This page is the **narrated** WOW. To run it, follow
[The WOW, hands-on](demo/wow-customer/README.md) — same files, on the self-host stack, with the
`fake-siebel.py` stub.

Three steps need **lumnik Pro** with the AI on:

- §4, the ask;
- §5's `lm view generate` (elsewhere it answers *no chat model is available on this hub*);
- §8's lifecycle question to the chat.

Every other step runs on the open edition. See [which edition you installed](editions.md#which-edition-am-i-running).
</div>

## Act 1 — ingest and fuse

### 1. A SOAP source

The first source is a fake Siebel SOAP connector. The manifest:

```yaml
--8<-- "docs/demo/wow-customer/siebel-connector.yaml"
```

```bash
lm apply -f docs/demo/wow-customer/siebel-connector.yaml
lm connector run siebel-customers   # applying declares it; this is what reads the source
```

### 2. A CSV source

The second source is a CSV export carrying the same customer universe from another angle.

```yaml
--8<-- "docs/demo/wow-customer/csv-connector.yaml"
```

Its data:

```csv
--8<-- "docs/demo/wow-customer/customer.csv"
```

```bash
lm apply -f docs/demo/wow-customer/csv-connector.yaml
lm connector run cust-csv
```

Both runs must be `Completed` before you fuse:

```
$ lm run list
ID        CONNECTOR         ENDPOINT   STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
9ef96149  cust-csv          default    Completed  8   8    0        0             2026-10-19 01:38  2026-10-19 01:38
4197d406  siebel-customers  customers  Completed  2   2    0        0             2026-10-19 01:38  2026-10-19 01:38
```

The CSV manifests read `/demo/…`, a path **inside the hub container**, mounted by the overlay
`docs/demo/wow-customer/docker-compose.wow.yml`. Without it the run reports `Completed` with
`IN=0 OUT=0`.

### 3. Fuse into one canonical customer

The entity manifest declares how both sources match and merge:

```yaml
--8<-- "docs/demo/wow-customer/customer-entity.yaml"
```

```
$ lm entity apply -f docs/demo/wow-customer/customer-entity.yaml
Applied entity "Customer" → connector."v_customer"
$ lm entity get customer --filter customer_code=C-200
CUSTOMER_CODE  NAME   SEGMENT  EMAIL   CITY  _CONFLICTS
C-200          Marie  SILVER   m@x.fr  Lyon  {"name": [{"src": "cust_siebel", "value": "Marie"}, {"src": "t_cust_csv", "value": "MARIE A."}]}
```

Siebel and the CSV disagree on C-200's name: Siebel's value wins (it is listed first), and both
are kept in `_conflicts`.

### 4. Ask it (Pro)

```bash
lm ask --scope clients "combien de clients à Lyon ?"
# → the answer, computed over the fused Customer, with the SQL that ran beneath it
```

## Act 2 — the data starts working for you

*(The chain, the channels and the named limits: [How lumnik breathes](respiration.md).)*

<div class="lm-grid" markdown>
<article class="lm-card" markdown>
<div class="lm-kicker">See</div>
<h3>View</h3>

Render the fused customer as a declarative, read-only list — [reference](apps/views.md).
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">React</div>
<h3>Process</h3>

Emit a decision when a new Lyon customer lands — [reference](apps/processes.md).
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Decide</div>
<h3>Inbox</h3>

Let a human acknowledge the event with an idempotent action — [reference](apps/inbox.md).
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Remember</div>
<h3>Workflow</h3>

Teach the substrate lifecycle and dormancy so silence becomes a signal — [reference](apps/workflows.md).
</article>
</div>

### 5. See it (`kind: View`)

```yaml
--8<-- "docs/demo/wow-customer/clients-view.yaml"
```

```
$ lm view -f docs/demo/wow-customer/clients-view.yaml --filter city=Lyon
Clients · city=Lyon

Code client  Nom            Ville  Segment
C-200        Marie          Lyon   SILVER
C-500        Claire Martin  Lyon   
C-800        Albert Camus   Lyon   
C-900        Saint Exupery  Lyon   
```

Or **describe the view and let the AI write it** (Pro). The same checks apply: an unknown column
is refused, and so is a request the scope cannot answer:

```bash
lm view generate clients "les clients gold avec leur ville et segment" -o gold.yaml
cat gold.yaml            # the first line is the ready-to-run render command
lm view -f gold.yaml --filter 'segment=GOLD'
```

### 6. React to it (`kind: Process`)

When a new Lyon customer lands, emit a decision — your webhooks (n8n here) receive it:

```yaml
--8<-- "docs/demo/wow-customer/lyon-process.yaml"
```

```bash
lm process apply -f docs/demo/wow-customer/lyon-process.yaml
# or create it in the TUI: `:new` → Process — the same living example opens in $EDITOR
```

### 7. Decide (inbox → ack)

**A process fires only on a new row** — not on rows already ingested, not on a re-run of the
same file. Add a Lyon customer, then re-run the connector:

```bash
echo 'C-400,Camille Roux,camille@x.fr,Lyon' >> docs/demo/wow-customer/customer.csv
lm connector run cust-csv
```

Pick a `code` the file does not carry (`C-400` is free) and `ville` = `Lyon`. A few seconds
after the run, the decision is in the inbox; the human acks it:

```
$ lm inbox
ID  WHEN         PROCESS             MESSAGE                             ENTITY_KEY
8   à l'instant  lyon-client-arrive  Nouveau client Camille Roux à Lyon  ff1027320a1acbe2646ce1b2d1e4a42111868a75bb30825dc4609d785e6aaa7f
$ lm inbox ack 8
acked.
```

- `MESSAGE` is the process's `"Nouveau client {cname} à {ville}"`, filled from the row.
- `ENTITY_KEY` is the row's content hash, not a customer code.
- Acking twice answers `acked.` twice and records one ack.

### 8. Declare the lifecycle (`kind: Workflow`)

A workflow declares what a status column *means*: the states, their aliases, which are
terminal. It is declared over real rows, so this act brings its own source — a commandes
export with dirty statuses:

```yaml
--8<-- "docs/demo/wow-customer/commandes-connector.yaml"
```

```bash
lm apply -f docs/demo/wow-customer/commandes-connector.yaml
lm connector run commandes-csv       # lands connector.t_commandes_csv — seven commandes
```

Apply the workflow **after** the run: over a table that does not exist yet the apply still
succeeds, but reports nothing.

```yaml
--8<-- "docs/demo/wow-customer/commande-workflow.yaml"
```

```
$ lm workflow apply -f docs/demo/wow-customer/commande-workflow.yaml
{"name":"commande-lifecycle","scope":"demo","table":"connector.t_commandes_csv","column":"statut","dataTruth":{"undeclared":[{"value":"EN LITIGE","rows":2}],"unobserved":[],"nullOrBlank":0,"tableMissing":false,"keyColumnMissing":false,"keyNullOrBlank":0,"checkUnavailable":false,"unavailableReason":null}}
```

`EN LITIGE` is in the column and in no declared state: the apply names it, with its row count,
and succeeds. On Pro, the chat then answers lifecycle questions from the declared graph.

The manifest also declares `spec.on.key: num` and `spec.dormancy.after: 30d`: a sweep alerts on
every commande left unmoved in a non-terminal state for 30 days.

- The 30 days count from lumnik's **first observation** of the commande, not from the source's history.
- To see it fire without waiting, set `after: 1m` in your own copy; the sweep runs every 15 minutes.
- The alert lands in `lm inbox` under `workflow:commande-lifecycle` — full wording in
  [Dormancy](apps/workflows.md#the-alert-verbatim).

With `after: 1m`, two sweeps after the run:

```
$ lm inbox
ID  WHEN         PROCESS                      MESSAGE                                                                                                                                        ENTITY_KEY
23  à l'instant  workflow:commande-lifecycle  CMD-007 : observé immobile en « EN LITIGE » depuis le 2026-10-18 (première observation 2026-10-18) — seuil déclaré 1m dans commande-lifecycle  CMD-007
22  à l'instant  workflow:commande-lifecycle  CMD-006 : observé immobile en « EN_COURS » depuis le 2026-10-18 (première observation 2026-10-18) — seuil déclaré 1m dans commande-lifecycle   CMD-006
21  à l'instant  workflow:commande-lifecycle  CMD-005 : observé immobile en « EN LITIGE » depuis le 2026-10-18 (première observation 2026-10-18) — seuil déclaré 1m dans commande-lifecycle  CMD-005
20  à l'instant  workflow:commande-lifecycle  CMD-004 : observé immobile en « BROUILLON » depuis le 2026-10-18 (première observation 2026-10-18) — seuil déclaré 1m dans commande-lifecycle  CMD-004
19  à l'instant  workflow:commande-lifecycle  CMD-001 : observé immobile en « EN_COURS » depuis le 2026-10-18 (première observation 2026-10-18) — seuil déclaré 1m dans commande-lifecycle   CMD-001
```

`CMD-002` and `CMD-003` never alert: they are `TERMINE`, a terminal state. `CMD-005` and
`CMD-007` sit in the undeclared `EN LITIGE`, which is never terminal, and alert like the others.
See [Dormancy](apps/workflows.md#dormancy-the-memory-of-the-breathing).

## After the demo — your data

The same road on the customer's own data:

1. **Point at the real database.** `lm source jdbc test` checks the connection, `discover`
   lists tables with row estimates, `add` creates one connector per table. A `SELECT`-only
   account is enough ([the guided flow](connectors/jdbc.md#guided-flow-lm-source-jdbc)).
2. **Repair at the door.** [Transformers](connectors/transformers.md) fix mojibake and legacy
   dates; a [workflow](apps/workflows.md) apply names the dirty statuses it finds.
3. **Tag a scope, run, ask.** The run writes the scope's schema card; then the chat answers on
   the customer's rows ([what makes a scope answerable](quickstart/installer.md#what-makes-a-scope-answerable)).

What takes calendar time is outside lumnik: the `SELECT` account, the network path. The chat
needs lumnik Pro ([which edition includes what](editions.md#feature-by-feature)) and
[pulled models](quickstart/prerequisites.md).

## Continue hands-on

Continue with [the by-hand replay](demo/wow-customer/README.md) on the self-host stack,
with the `fake-siebel.py` stub and the demo credentials.
