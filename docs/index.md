# lumnik — defrost the enterprise

<section class="lm-hero" markdown>
<p class="lm-eyebrow">Documentation</p>
<p class="lm-lead" markdown="span">lumnik reads the data in your ERP, LIMS and exports — **read-only** — and lets people ask it questions in plain language. The answer cites its sources, or declines and says why.</p>
<div class="lm-meta" markdown>
<div class="lm-meta-item" markdown>
<strong>Read-only first</strong>

Your source system is never written to. Ingest first, risk nothing.
</div>
<div class="lm-meta-item" markdown>
<strong>Fuse, conflicts kept</strong>

Conflicts are preserved in a `_conflicts` ledger, never silently resolved away.
</div>
<div class="lm-meta-item" markdown>
<strong>Ask with guards</strong>

Answers cite their sources — or decline. The guards refuse to invent.
</div>
<div class="lm-meta-item lm-meta-item--accent" markdown>
<strong>Operate for real</strong>

Deploy, connect, scope, and run day-2 operations from one docs set.
</div>
</div>
<div class="lm-cta-row" markdown>
[See the WOW](wow.md){ .lm-button .lm-button--primary } [Deploy lumnik](deploy.md){ .lm-button .lm-button--ghost } [Follow the guided path](parcours.md){ .lm-button .lm-button--ghost }
</div>
</section>

## Choose your starting path

<div class="lm-path-grid" markdown>
<article class="lm-card lm-card--accent" markdown>
<div class="lm-kicker">Start here</div>
<h3>See the WOW</h3>

Watch two legacy sources become one canonical customer, then ask it a question in French.

[Open the walkthrough](wow.md){ .lm-link }
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Install</div>
<h3>Deploy the hub</h3>

Run the self-host stack, wire OIDC, and get to a healthy local hub without guesswork.

[Open deploy docs](deploy.md){ .lm-link }
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">First value</div>
<h3>Quickstart in 60 seconds</h3>

Install `lm`, point it at the hub, and show value on CSV, REST, or SFTP data.

[Open quickstart](quickstart/installer.md){ .lm-link }
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Guided onboarding</div>
<h3>Walk the full path</h3>

Go from an empty machine to scoped métier apps, dormancy alerts, and day-2 operations.

[Open parcours](parcours.md){ .lm-link }
</article>
</div>

## The system in one glance

The system in one picture — data flows left to right, read-only at the door:

```mermaid
flowchart LR
  subgraph frozen["Frozen legacy systems"]
    direction TB
    erp[("ERP · JDBC")]
    files[("CSV · SFTP · S3")]
    apis[("REST · SOAP · GraphQL")]
  end
  subgraph hub["the lumnik hub — multi-tenant · RLS"]
    direction TB
    conn["Connectors — read-only ingestion"]
    tables[("Raw tables, per tenant")]
    ent["Entities — fused, conflicts kept"]
    ask["Ask — five guards (Pro)"]
    ev["Events → decisions"]
    conn --> tables --> ent --> ask
    tables -->|"new rows only ·<br/>REST/SOAP/GraphQL silent"| ev
  end
  subgraph metier["The métier"]
    direction TB
    chat["Chat"]
    pwa["PWA · views · inbox"]
  end
  out["The roads out —<br/>webhook · email · pull"]
  lm["lm CLI/TUI —<br/>the integrator operates the hub"]
  erp --> conn
  files --> conn
  apis --> conn
  ask --> chat
  ent --> pwa
  ev --> pwa
  ev --> out
  hub -.- lm
```

## The product surface

<div class="lm-grid" markdown>
<article class="lm-card" markdown>
<div class="lm-kicker">Ingest</div>
<h3>The hub</h3>

- **Sources:** JDBC, CSV and files, REST, GraphQL, SOAP — plus Kafka in [lumnik Pro](editions.md) — through [declarative](how-it-works.md) connectors.
- **Tenants:** each tenant sees only its own rows ([RLS](security/rls.md)).
- **Repair at the door:** [transformers](connectors/transformers.md) fix mojibake and legacy dates.

[Connector overview](connectors/index.md){ .lm-link }
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Fuse</div>
<h3>Entities</h3>

N legacy sources become one canonical, queryable thing. Disagreements stay visible in the `_conflicts` ledger.

[Open entities](entities.md){ .lm-link }
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Ask</div>
<h3>Answers that cite — or refuse</h3>

Per-métier chat and read-only text-to-SQL over the live hub, fenced by five guards and métier scopes (lumnik Pro).

[See the five guards](transparency/ask-honesty.md){ .lm-link }
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Act</div>
<h3>Apps, workflows, and the roads out</h3>

Views, processes, workflows, inbox, and the métier PWA turn the hub into operational surfaces — on a phone, where the work actually happens. And what changed [leaves again](respiration.md): a decision reaches your own tools by [signed webhook](outputs.md) or [email](email.md).

[Browse apps](apps/views.md){ .lm-link }
</article>
</div>

## First session map

<div class="lm-rail" markdown>
<article class="lm-step" markdown>
<div class="lm-step__num">1</div>
<div markdown>
<h3>Watch first</h3>

Run the [WOW](wow.md) before reading feature lists.

<p class="lm-check" markdown="span">**You know it landed when:** you understand what a connector, an entity, and a sourced answer look like in one run.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">2</div>
<div markdown>
<h3>Deploy and log in</h3>

[Run the hub locally](deploy.md), point `lm` at it, and verify your first authenticated call succeeds.

<p class="lm-check" markdown="span">**You know it worked when:** `lm connector list` answers — on a fresh hub, with headers only.</p>

```
$ lm connector list
NAME  TYPE  TENANT  SCHED  WINDOW  STATUS  LAST RUN
```
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">3</div>
<div markdown>
<h3>Choose your shelf</h3>

Once the system is alive, branch into Connectors, Apps, Security, or Operations depending on what you need next.

<p class="lm-check" markdown="span">**Best next shelves:** [Connectors](connectors/index.md), [Workflows](apps/workflows.md), [Security](security/role-management.md), [Operations](operations/backup-restore.md).</p>
</div>
</article>
</div>

## Reference shelves

<div class="lm-mini-grid" markdown>
<article class="lm-card" markdown>
<h3>Connectors</h3>

[Overview](connectors/index.md) · [transformers](connectors/transformers.md) · [CSV & files](connectors/csv.md) · [JDBC](connectors/jdbc.md) · [REST](connectors/rest.md) · [GraphQL](connectors/graphql.md) · [SOAP](connectors/soap.md) · [Kafka](connectors/kafka.md) (Pro)
</article>
<article class="lm-card" markdown>
<h3>Ask your data</h3>

[The five guards](transparency/ask-honesty.md) · [scopes](rag/scopes.md) · [models & providers](rag/models.md) · [HTTP API](rag/http-api.md)
</article>
<article class="lm-card" markdown>
<h3>Apps &amp; outputs</h3>

[Views](apps/views.md) · [processes](apps/processes.md) · [workflows](apps/workflows.md) · [inbox](apps/inbox.md) · [PWA](apps/pwa.md) · [the roads out](outputs.md) · [email](email.md) · [how lumnik breathes](respiration.md)
</article>
</div>

The full newcomer sequence: [Parcours](parcours.md).
