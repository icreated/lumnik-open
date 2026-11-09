# Glossary

Every lumnik word, one breath each, grouped by where it lives on
[the system map](index.md#the-system-in-one-glance). Each entry links to the page
that owns the full story.

## Ingest — sources into the hub

**Hub** — the lumnik application itself, on `:8080`. It runs the connectors and serves the API
and the PWA. The ingested data lands in its PostgreSQL database, which the stack starts beside it.
Sources are never written to. [Deploy it](deploy.md).

**Connector** — a declared ingestion unit: CSV, JDBC or REST, plus Kafka (Pro) for inbound
events. GraphQL and SOAP are REST endpoints of another shape.
[Overview](connectors/index.md).

**Manifest / `kind:`** — the YAML you apply. Eight kinds in three families: `Connector` and
`Entity` (ingestion and fusion); `View`, `App`, `Process`, `Workflow` and `Export` (the
[app ladder](apps/views.md)); `SearchIndex` ([Search](search/search-indexes.md)). Each kind has its
own command: `lm apply -f` for a connector, `lm <kind> apply -f` for the others (`lm entity`,
`view`, `app`, `process`, `workflow`, `export`, `search`). [How lumnik works](how-it-works.md).

**Endpoint** — one source inside a connector: a table, a file, a REST resource. A connector
run reads each enabled endpoint. `lm endpoint disable`, `reset-cursor` and `purge` act on one
endpoint alone. [CLI](cli/lm-reference.md).

**Run** — one execution of **one endpoint** of a connector. `lm connector run` over a
two-table source shows two lines in `lm run list`, each naming its endpoint. Each run counts
its records: in, out, skipped.
[`lm run list`](cli/lm-reference.md).

**IN = OUT + SKIPPED** — on every run, always. A row is never silently lost: it is written,
or skipped and counted. `DEAD-LETTERS` counts the skipped rows set aside in the DLQ with their
reason. A row a declared filter drops is skipped, with no DLQ entry.
[Seeing what was ingested](transparency/ingestion-honesty.md).

**DLQ (dead-letter queue)** — where a set-aside row waits, with its reason and its payload,
until you close it: `lm dlq discard`, or `lm dlq replayed --new-run-id` naming the run that
carried the row again. The hub never retries a dead letter on its own.
[`lm dlq`](cli/lm-reference.md).

**Raw table (`t_*`)** — the table a `csv-file`, `jdbc-generic` or `kafka` connector creates
in schema `connector`, one per endpoint. Everything else builds on it.

- A `rest-generic` connector (GraphQL and SOAP too) writes to the table each endpoint names,
  usually `ext.<name>`. The WOW's `connector.cust_siebel` is one.
- A one-shot `lm csv ingest` lands in `ext.<name>`.

**Transformer** — a repair applied at ingestion: broken accents, legacy date formats. You
declare it in a `csv-file` connector, or on a REST, GraphQL or SOAP endpoint over its promoted
columns. [Transformers](connectors/transformers.md).

**Hook** — a Kotlin script (`.kts`) for the cases transformers cannot express. Same places as
transformers. [Kotlin hooks](connectors/kotlin-hooks.md).

**Watermark** — `mode: watermark`, the incremental mode of JDBC and REST. The connector remembers
the last value of a field that moves on every change. The next run reads only what came after it.
[JDBC](connectors/jdbc.md).

**File source / transport** — where a CSV physically lives: `local`, `s3` or `sftp`. A
`csv-file` connector names it in `spec.transport`. [CSV & files](connectors/csv.md).

## Fuse — many sources, one truth

**Entity** — N legacy sources fused into one canonical, queryable thing (the WOW's
`Customer`). Declared by a `kind: Entity` manifest. [Entities](entities.md).

**`_conflicts` ledger** — where fused sources that disagree stay visible, instead of
one value silently winning. [`lm conflicts list`](cli/lm-reference.md).

## Ask — questions over your data

**Edition note** — everything in this section is lumnik Pro. On lumnik Open, `lm ask` and a
question in `lm chat` answer `this hub's build has no Ask`.
[Which edition am I on?](editions.md#which-edition-am-i-running)

**Analytic mode** — the chat translates your question to SQL and runs it read-only
over the live hub, fenced by the guards. [`lm ask`](cli/lm-reference.md), TUI `:ask`,
the PWA chat. [When Ask refuses](transparency/ask-honesty.md).

**Semantic mode** — retrieval-only chat: it cites ingested chunks and never generates
SQL. [HTTP API](rag/http-api.md).

**Chunk** — the retrieval unit: a slice of ingested data, indexed for the chat in one scope,
and cited as a source. [Models & providers](rag/models.md).

**Guard** — a check between your question and the answer. A guard refuses, and says what to do
next, rather than guess. An empty result is never phrased as a confident *"there are none"*. A
refusal costs you a retry; a confident wrong answer costs a business decision.
[When Ask refuses](transparency/ask-honesty.md).

## Act — the app ladder

**View (`kind: View`)** — a saved list for one scope: a table, its columns, its filters. It is
stored in the hub: an integrator applies it, and the users of that scope read it in the PWA or
with `lm view`. [Views](apps/views.md).

**App (`kind: App`)** — a business app's identity, declared in a manifest: its title, the scope
it reads through, an icon from the curated set, and its colours. Applied with `lm app apply`;
the PWA finds it at login. At most one app is the default, the one a bare `/app/` opens. Once
set, the default can only move to another app. [The PWA](apps/pwa.md).

**Process (`kind: Process`)** — a declared reaction: when a row matching a condition lands or
changes, it raises a `decision.*` event. [Processes](apps/processes.md).

**Workflow (`kind: Workflow`)** — a declared document lifecycle over a status column:
states, aliases for dirty legacy spellings, transitions. The chat (Pro) answers lifecycle
questions from the declared graph instead of inventing one. [Workflows](apps/workflows.md).

**Alias** — a dirty-data spelling (`en cours`, `encours`) declared to resolve to one
canonical state code. [Workflows](apps/workflows.md).

**Data-truth report** — what `workflow apply` prints: the values found in the data but
never declared, and the codes declared but never observed. [Workflows](apps/workflows.md).

**Dormancy** — a watched lifecycle's memory: a non-terminal document observed unmoved
past the declared threshold raises a `decision.dormant.<workflow>` alert.
[Dormancy](apps/workflows.md#dormancy-the-memory-of-the-breathing).

**Inbox** — where alerts and decisions surface for a human to see and acknowledge.
[Inbox](apps/inbox.md).

**Outputs / deliveries** — the journal of what the hub sent out (webhooks) and whether
it arrived — failures first, replayable. [`lm outputs list`](cli/lm-reference.md).

**PWA** — the business app served at `/app/`: installable on a phone, scope-bound,
discovering its views from the hub. [The PWA](apps/pwa.md).

## Operate — the integrator's side

**`lm`** — the integrator's CLI and TUI, one binary. Every hub surface — connectors,
runs, DLQ, ask, views, workflows — has its counterpart there.
[CLI & TUI reference](cli/lm-reference.md).

**Integrator** — the person who wields `lm`: applies manifests, runs connectors,
manages scopes. Role `lm_integrator`; business users carry `lm_user`.
[Roles & permissions](security/role-management.md).

**TLS proxy** — the optional Caddy overlay that puts hub, PWA and Keycloak behind one https
address, so every device signs in at the same address. [Deploy](deploy.md#one-https-origin-the-tls-proxy).

## Security & tenancy

**Tenant** — one customer space. A user belongs to one tenant and sees only its rows. The
database enforces it, on every query. [RLS & database roles](security/rls.md).

**RLS (Row-Level Security)** — the PostgreSQL feature that enforces the tenant boundary.
[RLS & database roles](security/rls.md).

**Scope** — an opt-in boundary inside a tenant, carried as Keycloak roles (`scope:<tag>`).
No scope role = tenant-wide access. One or more = bound to exactly those scopes. Chat, search,
views, entities, workflows, the inbox and the connectors all enforce it. `lm` prints the
refusal as `Error: access refused (403): scope not granted`.
[Scopes](rag/scopes.md).
