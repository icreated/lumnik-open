# The WOW, step by step

<section class="lm-hero" markdown>
<p class="lm-eyebrow">Show, don't explain</p>
<p class="lm-lead" markdown="span">A SOAP Siebel and a CSV become one canonical **Customer** — served over REST, then asked in natural language. Every file below is the *real* file the end-to-end test runs, so this walkthrough cannot drift away from what actually works.</p>
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

Sixty seconds of watching beats any feature list.
</div>
</div>
</section>

<div class="lm-pane lm-pane--accent" markdown>
<div class="lm-kicker">Read this one, run the other one</div>

This page is the **narrated** WOW: the manifests and the commands are here so you can see
what the flow is made of, but running them needs a hub, this repository on disk and a
logged-in `lm`. To actually do it, follow
[The WOW, hands-on](demo/wow-customer/README.md) — same files, on the deployed self-host
stack, with the prerequisites and the `fake-siebel.py` stub it needs.

Three parts of the flow are not in every build: the *ask* of §4 and the chat that answers §8's
lifecycle questions both go through `lumnik-rag`, a module the **open edition does not carry**
— nor `lumnik-llm-langchain`, where the model providers live. §5 has a foot in the same place:
`lm view generate` needs a chat model; with `lumnik-llm-langchain` pruned it **abstains** with
a named refusal ("no chat model is wired in this edition") rather than generating. §5's other half — writing the view YAML and rendering it with `lm view -f` —
does not touch a model at all. Every other step here ingests, fuses and serves on that build.
See [which edition you installed](editions.md#which-edition-am-i-running).
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
lm run list                         # both runs Completed before you fuse anything
```

Both CSV manifests on this page — this one and Act 3's — read `/demo/…`, a path **inside the
hub container**. The demo overlay (`docker-compose.wow.yml`) is what mounts this folder there.
Without it the run still reports `Completed`, with `IN=0 OUT=0`: the glob simply matched
nothing.

### 3. Fuse into one canonical customer

The entity manifest declares the `match` and merge rules that unify both sources into one governed truth:

```yaml
--8<-- "docs/demo/wow-customer/customer-entity.yaml"
```

```bash
lm entity apply -f docs/demo/wow-customer/customer-entity.yaml
```

### 4. Ask it

The first question proves the answer is computed over a fused truth that lived in neither source alone.

```bash
lm ask --scope clients "combien de clients à Lyon ?"
# → an answer computed over a fused truth that lived in neither Siebel nor the CSV alone,
#   with the SQL that ran shown beneath it.
```

## Act 2 — the data starts working for you

*(The doctrine behind this act — the chain, the channels, the named limits — is
[How lumnik breathes](respiration.md); this act runs it for real.)*

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

```bash
lm view -f docs/demo/wow-customer/clients-view.yaml --filter city=Lyon
```

Or skip writing the YAML — **describe the view and let the AI write it**, validated by the
same gate (an unknown column can never slip through, and an unanswerable ask is refused
with the reason):

```bash
lm view generate clients "les clients gold avec leur ville et segment" -o gold.yaml
cat gold.yaml            # the first line is the ready-to-run render command
lm view -f gold.yaml --filter 'segment=GOLD'
```

### 6. React to it (`kind: Process`)

When a new Lyon customer lands, emit a decision — delivery rides your existing webhook
subscriptions (n8n here):

```yaml
--8<-- "docs/demo/wow-customer/lyon-process.yaml"
```

```bash
lm process apply -f docs/demo/wow-customer/lyon-process.yaml
# or create it in the TUI: `:new` → Process — the same living example opens in $EDITOR
```

### 7. Decide (inbox → ack)

**A process fires only on a genuinely new row.** `data.row.changed` is born at the raw write
path and dedups on the row's content hash, so re-running the same CSV emits nothing — and
applying a process never fires retroactively over rows that already landed in step 2. Give it
something new, then re-run the connector:

```bash
echo 'C-400,Camille Roux,camille@x.fr,Lyon' >> docs/demo/wow-customer/customer.csv
lm connector run cust-csv
```

`customer.csv` is `code,cname,email,ville` — pick a `code` the file does not already carry
(`C-400` is free) and a `ville` of `Lyon`, which is what `lyon-process.yaml` filters on. The
process consumer ticks every 5s, so the decision lands a few seconds after the run.

Then the human disposes — and the ack is itself an event, idempotent by construction. The
listing below is an **illustration of the shape**, not a transcript: the ID and the key are
whatever your own run produced.

```bash
lm inbox
# ID    QUAND        PROCESS             MESSAGE                             ENTITY_KEY
# <id>  à l'instant  lyon-client-arrive  Nouveau client Camille Roux à Lyon  <row hash, 64 hex>
lm inbox ack <id>
# acked.
```

`QUAND` is a humanized age — under a minute it reads `à l'instant`. `ENTITY_KEY` here is the row's
**content hash**, not a customer code: the event is emitted at the raw ingest table, before any
entity key exists, and the inbox prints it whole — its 64 hex characters, unabridged.
`MESSAGE` is `lyon-process.yaml`'s `"Nouveau client {cname} à {ville}"` rendered from the row
that fired it. Re-acking the same ID answers the same 200: the ack event is written once, ever.

### 8. Declare the lifecycle (`kind: Workflow`)

The three rungs above act on rows. This one teaches the substrate what the rows *mean*:
the states a document moves through, the aliases twenty years of Excel produced for them,
and which are terminal.

A lifecycle is declared **over a column of real rows**, so this act brings its own source —
a commandes export whose `statut` column carries twenty years of drift:

```yaml
--8<-- "docs/demo/wow-customer/commandes-connector.yaml"
```

```bash
lm apply -f docs/demo/wow-customer/commandes-connector.yaml
lm connector run commandes-csv       # lands connector.t_commandes_csv — seven commandes
```

Declare it only once those rows are in. Declaring first is *allowed* — the apply returns 200
over a table that does not exist yet — but then `dataTruth` has nothing to report and the
whole point of the next paragraph disappears silently:

```yaml
--8<-- "docs/demo/wow-customer/commande-workflow.yaml"
```

```bash
lm workflow apply -f docs/demo/wow-customer/commande-workflow.yaml
# {"name":"commande-lifecycle","scope":"demo", … ,
#  "dataTruth":{"undeclared":[{"value":"EN LITIGE","rows":2}], … }}
```

Applying it doesn't hide the mess, it names it: `EN LITIGE` sits in the column and in no
declared state, so the response reports it — and still returns 200, because reality doesn't
ask the manifest's permission. The declared graph then lands in the scope's corpus, so the
chat answers a lifecycle question from it instead of inventing one.

The manifest also declares `spec.on.key` (`num`) and `spec.dormancy.after: 30d` — a scheduled
sweep watching every commande for the one thing the raw table itself cannot say: how long it
has sat, unmoved, in a non-terminal state. Cold start counts from observation — nothing can be dormant before
`30d` elapses from lumnik's own first observation, never from the source's unknown history —
and the alert never claims a source-absolute duration. To watch the sweep fire without the real
30-day wait, shorten the threshold in your own copy — `spec.dormancy.after` accepts minutes, so
`1m` is enough (the committed manifest still declares `30d`). The listing below is an
**illustration of the shape**, not a transcript: the ID is whatever your own run produced, and
`QUAND` is the humanized age of §7 — `à l'instant` if you look right after the sweep. The `…` in
`MESSAGE` is this page eliding for width, not the CLI abridging — `lm inbox` prints the alert in
full, and [Dormancy](apps/workflows.md#the-alert-verbatim) quotes the whole line:

```
lm inbox
# ID    QUAND        PROCESS                      MESSAGE                                                ENTITY_KEY
# <id>  à l'instant  workflow:commande-lifecycle  CMD-005 : observé immobile en « EN LITIGE » depuis …    CMD-005
```

`CMD-002` and `CMD-003` never alert — both resolve to the terminal `TERMINE`, which the sweep
excludes. `EN LITIGE` — undeclared, and therefore never terminal — is watchable too: `CMD-005`
and `CMD-007` sleep in it and alert exactly like any other stalled commande. See
[Dormancy](apps/workflows.md#dormancy-the-memory-of-the-breathing) for the full vocabulary.

## After the demo — your data

The WOW you just read runs on the shipped dataset, and says so. The question every
once-burned sponsor asks within a minute — *"and on OUR data?"* — has a road, and it is
shorter than the demo suggests:

1. **Point at the real base, interactively.** `lm source jdbc test` proves the connection,
   `discover` lists tables with row estimates and suggested watermark columns, `add`
   creates one connector per table with an encrypted credential — a `SELECT`-only account
   is all it ever needs ([the guided flow](connectors/jdbc.md#guided-flow-lm-source-jdbc)).
2. **Expect the mess — it is the point.** Fifteen years of mojibake, legacy dates and
   fantasy statuses don't block ingestion: [transformers](connectors/transformers.md)
   repair what should be repaired, and declaring a [workflow](apps/workflows.md) gets you
   a data-truth report at apply time that **names** the dirty statuses it found — a
   report, never a police. The freezer's frost is inventory, not failure.
3. **Promote to a scope, ask the first question.** Tag the connector with the scope,
   then run it (or `lm source rediscover`) — that is what writes the scope's schema
   card; from there the chat answers on *your* rows the same way it did on the demo's
   ([what makes a scope answerable](quickstart/installer.md#what-makes-a-scope-answerable)).

Boundaries: the mechanics above take hours, not days — what takes calendar time
is what sits outside lumnik: the `SELECT` account, the network path, your DBA's diary.
And two dependencies to have in place: the chat surfaces need lumnik Pro
([which edition includes what](editions.md#feature-by-feature)), and the models must be
[pulled](quickstart/prerequisites.md) first.

## Continue hands-on

Continue with [the full by-hand replay](demo/wow-customer/README.md) — on the **deployed**
self-host stack, real OIDC, `lm login` and Bearer-authenticated calls throughout (including
the `fake-siebel.py` SOAP stub and the demo credentials).
