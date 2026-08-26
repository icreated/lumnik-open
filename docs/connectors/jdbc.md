# JDBC

> Ingest tables from a live PostgreSQL database — full dumps or watermark-incremental,
> resumable mid-table, scheduled by cron.

| | |
|---|---|
| **Type** | `jdbc-generic` |
| **Protocol** | JDBC (postgres) |
| **Target** | `connector.t_<name>` |
| **Schedulable** | cron |
| **Edition** | Open |

## Quickstart

```yaml
apiVersion: connectors.lumnik.io/v1
kind: Connector
metadata:
  name: acme-erp-pg
  connector_type: jdbc-generic
  tags: [erp]
spec:
  connection:
    url: jdbc:postgresql://db.acme.io:5432/billing
    user: ro_user
    password_env: ACME_DB_PASSWORD
  source:
    tables:
      - name: invoices
        mode: watermark
        watermark_column: updated_at
        pk_columns: [invoice_id]
  target:
    tags: [erp, billing]
```

```bash
lm validate -f acme-erp-pg.yaml
lm apply -f acme-erp-pg.yaml
lm connector run acme-erp-pg
```

## Guided flow — `lm source jdbc`

The manifest above is one road in; `lm source jdbc` is the other, and it comes right after
Quickstart on purpose — for a non-developer installer, the interactive road is the one to
try first, with the YAML manifest reference below as what to reach for once you want
repeatable, version-controlled config. The password is always read from **stdin**, never
a flag:

```bash
printf '%s\n' "$DB_PASSWORD" | lm source jdbc test --url jdbc:postgresql://... --user ro_user
printf '%s\n' "$DB_PASSWORD" | lm source jdbc discover --url ... --user ... [--schema public] \
    [--skip-pattern 'pg_*,information_schema*,t_*'] [--include-views] [--output table|json]
printf '%s\n' "$DB_PASSWORD" | lm source jdbc add my-source --url ... --user ... \
    --tables invoices,customers [--template-mode llm-first|heuristic-only|dump-1-1] \
    [--schedule-cron '0 6 * * *'] [--interactive]
lm source jdbc list-tables my-source
```

- `test` — connection check: dialect, server version, latency, visible schemas.
- `discover` — lists tables with row estimates, columns, PK and a suggested watermark column.
- `add` — creates one connector per table plus a mapping. `--interactive` opens a TUI
  table picker (pre-filled with the discovered watermark suggestions); otherwise pass
  `--tables`. The password is stored as an **encrypted Basic credential**
  (`<source-name>--credential`), created and managed by this guided flow — it never lands
  in a config file.
- `--template-mode` picks how the mapping template is drafted: `llm-first` (default —
  the local LLM drafts it from sampled rows, falling back to the heuristic when no LLM is
  available or the draft is unusable), `heuristic-only`, or `dump-1-1` (no transformation).

## Connection

```yaml
connection:
  url: jdbc:postgresql://db.acme.io:5432/billing  # required
  user: ro_user                                    # required
  password_env: ACME_DB_PASSWORD                   # required — server env var (see below)
  dialect: postgres           # optional, inferred from url scheme; `postgresql` accepted as an alias
```

Supported dialects (v1): `postgres`. If `dialect` is declared and differs from the URL scheme, validation fails.

**The connection user only needs `SELECT`** on the declared tables — nothing more. Every
jdbc query, whether an ingestion run, `discover`, or `test`, only ever reads: ingestion issues
`SELECT * FROM <schema>.<table>` and discovery/introspection use the driver's read-only
metadata calls. Nothing in the jdbc connector inserts, updates, deletes or alters anything on
the source database.

### Password sources

At run time the password is resolved in priority order:

| Priority | Source | Notes |
|---|---|---|
| 1 | **Attached credential** | A Basic credential referenced by the connector (`connector.credential_id`), stored encrypted. The guided `lm source jdbc add` flow creates one automatically (named `<source-name>--credential`); manifests applied via `lm apply` don't set this. |
| 2 | `password` in config | Plain-text fallback, dev/test only. |
| 3 | `password_env` | The name of a variable in the **hub server's** environment (an OS env var, or a JVM system property). Unset at run time = a clear run failure. If you don't operate the hub yourself, ask whoever does to export it where the hub runs. Unlike the CSV transports' `key_env`/`password_env`, this does **not** consult the [`lm secret`](../admin-console-guide.md) registry — the value must exist in the server environment. |

"Stored encrypted" means pgcrypto **AES-256** under `LUMNIK_CRYPTO_SECRET_KEY` — the key,
its no-rotation posture and its caveats live in
[Security → Secrets at rest](../security/rls.md#secrets-at-rest-the-master-key).

!!! warning "Never put a real password in a manifest"
    Priority 2, plain `password` in config, is a dev/test fallback only.

## Source — multi-table

Each entry in `source.tables[]` becomes an endpoint of the connector — one runnable unit
per table (you'll see them in the TUI). The endpoint name = table name; the endpoint path
= `/<table-name>`. Two modes: `watermark` — incremental, tracked via a column that only
grows on change (e.g. `updated_at`) — or `full` — a complete, PK-ordered re-scan.

```yaml
source:
  tables:
    - name: invoices
      mode: watermark               # watermark | full
      watermark_column: updated_at  # required when mode=watermark
      pk_columns: [invoice_id]      # required, non-empty
      # any source column sharing a reserved hub-column name needs `rename:` (see the warning below)
      indexes:                      # optional — Postgres indexes on the hub table
        - { columns: [customer_id], unique: false }
    - name: customers
      mode: full
      pk_columns: [customer_id]
```

!!! warning "Seven column names are reserved on jdbc's hub tables"
    `id`, `_row_hash`, `_ingested_at`, `_source`, `_run_id`, `_mapping_version`, `tenant_id` —
    the hub adds these to every table `jdbc-generic`, `csv-file` and `kafka` create (they all
    write through the same `connector.t_<name>` machinery; REST/GraphQL/SOAP land in a
    differently-shaped table with its own reserved names — see [REST](rest.md#storage-layout)).
    A source column sharing one of the seven is refused at write time, naming the column and the
    fix — on a brand-new table and on one that already exists alike, so a later run never
    silently drops that column's values. `id` is the one you'll hit most (an ERP's own primary
    key almost always uses it), but it isn't special — any of the seven collide the same way.
    See [Target](#target) for the fix.

### Deletes at the source are never propagated

A row deleted at the source stays in the hub table — in both modes, forever, since neither
watermark nor PK-cursor reads ever notice an absence. To resync from scratch, delete and
re-create the connector: `lm connector delete` purges its `connector.t_*` tables (and its
RAG chunks), so the
next apply + run starts clean.

### Per-table `indexes`

Declarative indexes are **per-table** for jdbc (that is where the runtime reads them —
`spec.target.indexes` is not read by this connector type, unlike [CSV & files](csv.md#target-table),
where `target.indexes` at the connector level is exactly what's read). Each entry is
`{ columns: [non-empty strings], unique?: bool }` (`unique` defaults to false); malformed
entries fail validation with a per-entry error instead of being silently dropped.

### Watermark resume — composite cursor

If a run is interrupted, the next one resumes exactly where it left off — no duplicates,
no gaps.

**`mode: watermark`** — the cursor is composite: `{col: <watermark_column>, value: <last
watermark>, pk: {<first pk column>: <last value>}}`. Resume reads `WHERE col > value OR
(col = value AND pk > last_pk)` — ties in the watermark column paginate stably via the
**first** `pk_columns` entry as tiebreak. Scheduled runs are incremental for free: the
persisted cursor carries the last watermark forward, so only rows changed since then are
re-read.

**`mode: full`** — the cursor is PK-only (`pk > last_pk`), so full scans are chunked and
resumable. It captures inserts by ascending PK but, having no watermark, never re-reads
updated rows.

## Defaults

`defaults` provides per-table fallbacks. Each `tables[i]` field overrides the corresponding default. Shallow merge (no recursive merging).

```yaml
defaults:
  schema: public
source:
  tables:
    - name: invoices
      mode: watermark
      watermark_column: updated_at
      pk_columns: [invoice_id]
      # schema inherited from defaults
    - name: line_items
      schema: billing               # overrides defaults.schema for this table only
      mode: watermark
      watermark_column: ts
      pk_columns: [invoice_id, line_no]
```

!!! note "Chunking is fixed, not a knob"
    Reads page through the source in **500-row chunks** (keyset pagination on the primary key,
    so a full scan of millions of rows stays linear and memory-bounded). There is no
    `chunk_size` setting — a manifest that declares one is **refused at validation** rather
    than silently ignored, the same refuse-rather-than-ignore rule as the CSV parser's fixed chunking.

## Scheduling

`schedule` — optional cron expression for the connector as a whole. All tables ingest on the
same trigger. The hub scheduler evaluates cron times in **UTC**, not the server's local
timezone — write your expression accordingly.

If the source is a sensitive production database, schedule ingestion off-hours to keep its
read load away from business-hours traffic — see [Sizing & load tests](../operations/sizing.md).

## Target

`target.tags` enables RAG scope filtering — strongly recommended. The connector warns at
validate-time if missing. Tags are what make the ingested rows discoverable to scoped
chat — see [Scopes](../rag/scopes.md).

Tables are created in the `connector` schema as `connector.t_<name>` (one per declared source
table). A source column sharing one of the seven reserved names above (see the warning under
[Source](#source-multi-table); `id` is the one you'll hit most) is refused.

**Fix it with `rename:`** — a per-table map from the source column name to the hub column
name you want instead, declared right beside `pk_columns`:

```yaml
source:
  tables:
    - name: invoices
      pk_columns: [invoice_id]
      rename:
        id: source_id       # source column `id` lands in the hub table as `source_id`
```

`rename:` is an ordinary per-table key, so it can also live under `defaults:` when every
table needs the same rename (see [Defaults](#defaults) above). A table's own `rename:`
**replaces** the one from `defaults:` entirely rather than merging with it — `defaults:`
is a flat, per-key overlay, so declaring `rename:` on a table means that table's `rename:`
is exactly what you wrote there, nothing inherited.

The rename only changes the column name **in the hub table** — nothing is renamed,
altered, or written back on the source database, so the connection stays read-only.
`lm validate`'s refusal rules for `rename:` are in [Validation rules](#validation-rules)
below.

## Validation rules

| Rule | Severity | Path |
|---|---|---|
| `connection.url` matches `jdbc:<dialect>:...` | ERROR | `spec.connection.url` |
| Dialect in supported set (postgres) | ERROR | `spec.connection.url` |
| Declared `dialect` matches URL scheme | ERROR | `spec.connection.dialect` |
| `source.tables[]` non-empty | ERROR | `spec.source.tables` |
| Each `tables[i].mode` in {watermark, full} | ERROR | `spec.source.tables[i].mode` |
| `mode=watermark` requires `watermark_column` | ERROR | `spec.source.tables[i].watermark_column` |
| `pk_columns` is a non-empty list | ERROR | `spec.source.tables[i].pk_columns` |
| `chunk_size` is refused — not a knob, chunking is fixed at 500 | ERROR | `spec.source.tables[i].chunk_size` |
| `indexes` entries are `{ columns: [non-empty strings], unique?: bool }` | ERROR | `spec.source.tables[i].indexes[j]` |
| No duplicate table names | ERROR | `spec.source.tables[i].name` |
| `rename` target is a plain identifier | ERROR | `spec.source.tables[i].rename` |
| `rename` target is not one of the seven reserved names | ERROR | `spec.source.tables[i].rename` |
| No two `rename` sources target the same column | ERROR | `spec.source.tables[i].rename` |
| `schedule` (if present) is a valid cron | ERROR | `spec.schedule` |
| `window` (if present) is `HH:MM-HH:MM` | ERROR | `spec.window` |
| `target.tags` present | WARNING | `spec.target.tags` |

## How to…

### Keep ingestion out of the source's business hours

The DBA's condition for the `SELECT` account is usually a clock, not a row count. Declare
it:

```yaml
spec:
  schedule: "*/30 * * * *"
  window: "22:00-06:00"    # UTC — the scheduler's clock
```

**Result:** cron slots due **outside** the window are skipped, never queued — with the
pair above, the source is read every 30 minutes between 22:00 and 06:00 UTC and left
alone the rest of the day. Windows may cross midnight (that is the point). Three edges to
know: the **end is exclusive** — a cron slot landing exactly on the end time is skipped,
so a nightly `0 6 * * *` needs a window ending `06:01` or later (`22:00-07:00`); a manual `lm connector run` **bypasses** the window — it is an explicit operator
act; and re-applying a manifest **without** `window` lifts the fence (same
column-is-truth contract as `schedule`).



### Discover tables and pick interactively

```bash
printf '%s\n' "$DB_PASSWORD" | lm source jdbc discover --url jdbc:postgresql://db.acme.io:5432/billing --user ro_user
printf '%s\n' "$DB_PASSWORD" | lm source jdbc add my-source --url jdbc:postgresql://db.acme.io:5432/billing --user ro_user --interactive
```

**Result:** `discover` lists tables with row estimates and a suggested watermark column;
`add --interactive` opens the TUI table picker pre-filled with those suggestions. Watch
the new connector land in the TUI (`:runs`) — see [CLI & TUI](../cli/lm-reference.md).

### Sync a table incrementally on a watermark

```yaml
spec:
  source:
    tables:
      - name: invoices
        mode: watermark
        watermark_column: updated_at
        pk_columns: [invoice_id]
```

**Result:** each run reads only the rows whose `updated_at` (or, on a tie, primary key)
is past the persisted cursor — a full first run, then only what changed since. Watch it
land in the TUI (`:runs`) — see [CLI & TUI](../cli/lm-reference.md).

### Full-dump a reference table nightly

```yaml
spec:
  source:
    tables:
      - name: customers
        mode: full
        pk_columns: [customer_id]
  schedule: "0 6 * * *"   # daily at 06:00
  window: "22:00-07:00"   # optional — scheduled runs only fire inside it (UTC; end exclusive, so 07:00 keeps the 06:00 slot in)
```

**Result:** every run re-scans the table chunk by chunk in primary-key order, resumable
if interrupted — inserts are always picked up, but a row changed in place is only ever
re-read on `mode: watermark`. Watch it land in the TUI (`:runs`) — see
[CLI & TUI](../cli/lm-reference.md).

### Index the hub table for fast métier queries

```yaml
spec:
  source:
    tables:
      - name: invoices
        mode: watermark
        watermark_column: updated_at
        pk_columns: [invoice_id]
        indexes:
          - { columns: [customer_id], unique: false }
```

**Result:** the declared Postgres index is created on `connector.t_invoices` at apply
time. Watch it land in the TUI (`:runs`) — see [CLI & TUI](../cli/lm-reference.md).

### Rename a source column that collides with a hub system column

```yaml
spec:
  source:
    tables:
      - name: invoices
        mode: watermark
        watermark_column: updated_at
        pk_columns: [invoice_id]
        rename:
          id: source_id
```

**Result:** the source's `id` column lands in the hub table as `source_id` — same values,
different column name; nothing changes on the source database. Watch it land in the TUI
(`:runs`) — see [CLI & TUI](../cli/lm-reference.md).

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| run fails: `password_env ... unset` | the variable must exist in the **hub server's** environment, not the laptop's — jdbc does not consult `lm secret` | export the variable where the hub itself runs |
| watermark never advances / rows keep re-reading | `watermark_column` isn't actually updated by the source app on every change | pick a truly-monotonic column, using `discover`'s suggested watermark |
| updated rows never re-ingested in `mode: full` | by design — the PK-only cursor captures inserts, never re-reads on update | switch that table to `mode: watermark` |
| run fails: `source column 'id' collides with a hub system column` | a source column shares one of the seven reserved hub-column names (`id` is the common one) | add `rename: { id: source_id }` under that table (or `defaults:`) — see How to above |

## See also

- [Overview](index.md)
- [Scopes](../rag/scopes.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
