# lumnik — defrost the enterprise

<section class="lm-hero" markdown>
<p class="lm-eyebrow">Documentation</p>
<p class="lm-lead" markdown="span">lumnik reads the data in your ERP, LIMS and exports — **read-only**. It fuses the data, shows it to the right team, and answers questions in plain language (Pro). An answer shows its sources, or declines.</p>
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
<strong>Not a migration, not BI</strong>

The ERP stays. lumnik reads it, and puts its data to work beside it.
</div>
</div>
<div class="lm-cta-row" markdown>
[Deploy lumnik](deploy.md){ .lm-button .lm-button--primary } [See the WOW](wow.md){ .lm-button .lm-button--ghost } [Follow the guided path](parcours.md){ .lm-button .lm-button--ghost }
</div>
</section>

## Your first day, in order

<div class="lm-rail" markdown>
<article class="lm-step" markdown>
<div class="lm-step__num">1</div>
<div markdown>
<h3>Prepare the machine</h3>

[Prerequisites](quickstart/prerequisites.md): Docker, a few tools, and Ollama for Pro.
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">2</div>
<div markdown>
<h3>Deploy the hub</h3>

[Deploy](deploy.md): one command starts the hub, its database and its login server.
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">3</div>
<div markdown>
<h3>Load a first export</h3>

[Quickstart](quickstart/installer.md): install `lm`, log in, load a CSV, see the rows.

<p class="lm-check" markdown="span">**You know it worked when:** `lm connector list` answers. On a fresh hub, with headers only.</p>
</div>
</article>
<article class="lm-step" markdown>
<div class="lm-step__num">4</div>
<div markdown>
<h3>Run the WOW</h3>

[The WOW](wow.md): two legacy systems become one customer, then a view, a decision, a lifecycle.
</div>
</article>
</div>

The full path, day by day: [Guided path](parcours.md).

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
  subgraph hub["the lumnik hub"]
    direction TB
    conn["Connectors — read-only ingestion"]
    tables[("Raw tables, per tenant")]
    ent["Entities — fused, conflicts kept"]
    ask["Ask (Pro)"]
    ev["Events → decisions"]
    conn --> tables --> ent --> ask
    tables -->|"new rows"| ev
  end
  subgraph metier["Business users"]
    direction TB
    chat["Chat (Pro)"]
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

Scoped chat and read-only text-to-SQL over the live hub, protected by query guards (lumnik Pro).

[When Ask refuses](transparency/ask-honesty.md){ .lm-link }
</article>
<article class="lm-card" markdown>
<div class="lm-kicker">Act</div>
<h3>Apps, workflows, and the roads out</h3>

Views, processes, workflows, inbox, and the PWA turn the hub into operational surfaces — on a phone, where the work actually happens. And what changed [leaves again](respiration.md): a decision reaches your own tools by [signed webhook](outputs.md) or [email](email.md).

[Browse apps](apps/views.md){ .lm-link }
</article>
</div>
