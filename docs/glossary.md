# Glossary

Every lumnik word, one breath each, grouped by where it lives on
[the system map](index.md#the-system-in-one-glance). Each entry links to the page
that owns the full story.

## Ingest — sources into the hub

**Hub** — the lumnik application itself: the service on `:8080` that runs the connectors,
serves the API and the PWA, and owns the multi-tenant PostgreSQL its ingested data lands in.
Hub and database are two components, not one — the stack starts them side by side. Sources
are never written to. [Deploy it](deploy.md).

**Connector** — a declared ingestion unit: CSV, JDBC, or REST — GraphQL and SOAP are
request shapes on the same REST engine — plus Kafka for inbound events.
[Overview](connectors/index.md).

**Manifest / `kind:`** — the YAML you `lm apply`. Seven kinds across two families:
`Connector` and `Entity` describe ingestion and fusion; `View`, `App`, `Process`,
`Workflow` and `Export` build the [app ladder](apps/views.md). [How lumnik works](how-it-works.md)
maps all seven in one place.

**Endpoint** — one queryable surface inside a connector (a table, a file, a REST
resource). A run fans out over the connector's enabled endpoints, and the scheduler,
cursor and purge all work per endpoint. [CLI](cli/lm-reference.md).

**Run** — one execution of **one endpoint** of a connector: `lm connector run` over a
two-table source writes two run rows, and each names the endpoint it read. Each run keeps
its arithmetic in a ledger: records in, records out, records skipped.
[`lm run list`](cli/lm-reference.md).

**Conservation invariant** — `records in = records out + records skipped`, on every
run, always. A row is never silently lost; it arrives, is skipped with a counted
reason, or lands in the DLQ (a declared filter is a counted skip, not a DLQ entry).
[Ingestion honesty](transparency/ingestion-honesty.md).

**DLQ (dead-letter queue)** — where a quarantined row waits, carrying its reason and the
offending payload, until you close it: `discard`, or `replayed` to record that you re-sent it
yourself. The hub never re-reads a dead letter. [`lm dlq`](cli/lm-reference.md).

**Raw table (`t_*`)** — the dynamic table a `csv-file`, `jdbc-generic` or `kafka`
connector creates in schema `connector`, one per endpoint, stamped with the tenant. The
untouched landing zone the rest of the system builds on. The `rest-generic` engine (so
GraphQL and SOAP too) is the exception: its hybrid tables take the name the manifest
declares, no `t_` prefix — the WOW demo's `connector.cust_siebel` is one. Same tenant
stamp and RLS either way; only the naming differs.

**Transformer** — a per-cell repair applied at ingestion — mojibake, legacy date
formats — declared in the manifest, at the door. [Transformers](connectors/transformers.md).

**Hook** — a Kotlin script (`.kts`) running inside the mapping pipeline at dataset,
row or cell level, for the cases declarative transformers can't express.
[Kotlin escape hatch](connectors/transformers.md#kotlin-escape-hatch).

**Watermark** — the incremental mode of the JDBC connector: a declared column whose
persisted high-water mark lets the next run fetch only what changed.
[JDBC](connectors/jdbc.md).

**File source / transport** — where a CSV physically lives: `local`, `s3` or `sftp`.
Orthogonal to connector types. [CSV & files](connectors/csv.md).

## Fuse — many sources, one truth

**Entity** — N legacy sources fused into one canonical, queryable thing (the WOW's
`Customer`). Declared by a `kind: Entity` manifest. [Entities](entities.md).

**`_conflicts` ledger** — where fused sources that disagree stay visible, instead of
one value silently winning. [`lm conflicts list`](cli/lm-reference.md).

## Ask — questions with honesty

**Edition note** — the ask pipeline and its five guards are native to the hub and ship in
every edition. The chat **doors** (`lumnik-rag`) and the model providers
(`lumnik-llm-langchain`) are not in the open edition, so on that build neither **mode** below
can be reached — and a chunk has no store there, `rag.chunk` being created by the rag module's
own migrations. The **guards** and the principle behind them are on the classpath either way;
what is missing is the door onto them. [Which edition am I on?](editions.md#which-edition-am-i-running)

**Analytic mode** — the chat translates your question to SQL and runs it read-only
over the live hub, fenced by the guards. [`lm ask`](cli/lm-reference.md), TUI `:ask`,
the PWA chat. [The five guards](transparency/ask-honesty.md).

**Semantic mode** — retrieval-only chat: it cites ingested chunks and never generates
SQL. [HTTP API](rag/http-api.md).

**Chunk** — the retrieval unit: a slice of ingested data embedded into the vector
store, scoped, and citable as a source. [Models & providers](rag/models.md).

**Guard** — one of five deterministic checks standing between your question and the
answer. Four refuse — with a what-to-do-next — rather than guess; the fifth reframes
an empty result instead of letting it become a confident *"there are none"*.
[The five guards](transparency/ask-honesty.md).

**Asymmetry of harm** — the principle behind the guards: a refusal costs you a retry;
a confident wrong answer — especially a confident *"there are none"* — costs you a
business decision. [Ask honesty](transparency/ask-honesty.md).

## Act — the app ladder

**View (`kind: View`)** — a saved, server-side description of what a métier sees: a
table, its columns, filters. The PWA and `lm` discover views from the hub — writes are
curated, reads are scope-bound. [Views](apps/views.md).

**App (`kind: App`)** — a métier application's own identity, declared instead of compiled: its
title, the scope it reads through, one identifier from the curated icon set, and its accent/background
theme. Applied by an integrator and discovered by the PWA at load, so a temporary sales-event or
crisis app can exist without a product build. Exactly one app carries the default flag — the one a
bare `/app/` opens.

**Process (`kind: Process`)** — a declarative reaction: when data matching a
condition arrives, notify. Refusable by design, like everything on the ladder.
[Processes](apps/processes.md).

**Workflow (`kind: Workflow`)** — a declared document lifecycle over a status column:
states, aliases for dirty legacy spellings, transitions. The chat answers lifecycle
questions from the declared graph instead of inventing one. [Workflows](apps/workflows.md).

**Alias** — a dirty-data spelling (`en cours`, `encours`) declared to resolve to one
canonical state code. Reality is never refused, just named. [Workflows](apps/workflows.md).

**Data-truth report** — what `workflow apply` prints: the values found in the data but
never declared, and the codes declared but never observed. [Workflows](apps/workflows.md).

**Dormancy** — a watched lifecycle's memory: a non-terminal document observed unmoved
past the declared threshold raises a `decision.dormant.<workflow>` alert.
[Dormancy](apps/workflows.md#dormancy-the-memory-of-the-breathing).

**Inbox** — where alerts and decisions surface for a human to see and acknowledge.
[Inbox](apps/inbox.md).

**Outputs / deliveries** — the journal of what the hub sent out (webhooks) and whether
it arrived — failures first, replayable. [`lm outputs list`](cli/lm-reference.md).

**PWA** — the métier app served at `/app/`: installable on a phone, scope-bound,
discovering its views from the hub. [The métier PWA](apps/pwa.md).

## Operate — the integrator's side

**`lm`** — the integrator's CLI and TUI, one binary. Every hub surface — connectors,
runs, DLQ, ask, views, workflows — has its counterpart there.
[CLI & TUI reference](cli/lm-reference.md).

**Integrator** — the person who wields `lm`: applies manifests, runs connectors,
manages scopes. Role `lm_integrator`; métier users carry `lm_user`.
[Roles & permissions](security/role-management.md).

**TLS façade** — the optional Caddy overlay putting hub, PWA and Keycloak behind one
https origin — one issuer for every device. [Deploy](deploy.md).

## Security & tenancy

**Tenant** — one customer space. Isolation lives in the database, not in the URL:
every query is filtered by the tenant the connection declares.
[RLS & database roles](security/rls.md).

**RLS (Row-Level Security)** — the PostgreSQL mechanism enforcing that filter on every
row of every tenant-stamped table. [RLS & database roles](security/rls.md).

**Métier** — the trade a scope serves — *magasinier*, *comptable* — and the register
the whole product speaks: per-métier chat, per-métier apps.

**Scope** — an opt-in boundary inside a tenant, carried as Keycloak roles
(`scope:<tag>`). No scope roles = tenant-wide access; one or more = bound to exactly
those. The uniform refusal is `403 {"error":"scope not granted"}`.
[Scopes](rag/scopes.md).
