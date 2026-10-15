# JDBC

> Read tables from a live PostgreSQL or Oracle database into hub tables — the whole table
> once, then only what changed, on a schedule.

| | |
|---|---|
| **Type** | `jdbc-generic` |
| **Dialect** | postgres — Open; [oracle](#oracle) — **Pro only** |
| **Target** | `connector.t_<table>` |
| **Schedulable** | cron |
| **Edition** | Open for PostgreSQL. Oracle ships only in lumnik Pro — see [Oracle](#oracle) |

The connection is read-only: every query this connector issues is a `SELECT`, and nothing in
it inserts, updates, deletes or alters anything on the source.

## Quickstart — one table, three commands, a look at the ledger

```yaml
--8<-- "docs/connectors/jdbc/10-full-dump.yaml"
```

Every screen on this page reads a source that lives in the hub's **own** PostgreSQL — four
tables and a view — so the page can be replayed with nothing but the
[self-host stack](../deploy.md). Create them first:

```bash
docker exec -i lumnik-postgres-1 psql -U lumnik -d lumnik < docs/connectors/jdbc/data/demo-source.sql
```

??? note "What that script creates"

    ```sql
    --8<-- "docs/connectors/jdbc/data/demo-source.sql"
    ```

Then, from the repository root:

```
$ lm validate -f docs/connectors/jdbc/10-full-dump.yaml
✓ docs/connectors/jdbc/10-full-dump.yaml — valid
$ lm apply -f docs/connectors/jdbc/10-full-dump.yaml
{"name":"demo-invoices","connectorId":"f0e7d07b-289b-47c1-874d-ecb167dc2e90","endpointsApplied":1}
$ lm connector run demo-invoices
run scheduled: 16b2bee8-c96a-4c27-8d74-39b3697f5a72
```

"Scheduled" is not "done" — the run ledger is where the result lands:

```
$ lm run list --connector demo-invoices
ID        CONNECTOR      ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
16b2bee8  demo-invoices  invoices  Completed  4   4    0        0             2026-09-08 01:18  2026-09-08 01:18
```

`STARTED` / `ENDED` print in **your terminal's** time zone (these screens: Paris, UTC+2). The
hub stores UTC, and `schedule` is evaluated in UTC.

What landed in `connector.t_invoices` — every source column, no column filter:

```
 invoice_id |   customer    |   total   | status |       updated_at
------------+---------------+-----------+--------+------------------------
       1001 | Northwind Ltd | 1250.0000 | paid   | 2026-09-01 09:15:00+00
       1002 | Contoso SA    |  480.5000 | open   | 2026-09-01 11:40:00+00
       1003 | Fabrikam Oy   | 2310.7500 | open   | 2026-09-02 08:05:00+00
       1004 | Adventure BV  |  199.9000 | draft  | 2026-09-02 16:20:00+00
```

- **`jdbc-generic` is a 1:1 dump.** `SELECT *` on the declared table; there is no column
  filter in the manifest. `rename:` is the only per-column knob.
- **`invoice_id` reads `1001`, `total` reads `1250.0000`.** A whole-number source type lands
  in `BIGINT`, a source type that carries a scale in `NUMERIC(18,4)` — see
  [Column types](#column-types).
- **The password never sits in the manifest.** `password_env` names a variable in the
  environment where the **hub** runs; the self-host stack already exports the one used here.

## Two modes — `full` and `watermark`

Both modes page through the table 500 rows at a time and remember where they stopped. What
they remember is the whole difference:

- **`mode: full`** remembers the **last primary key** it read. The next run continues past
  that key.
- **`mode: watermark`** remembers the **last value of a column that moves on every change**,
  plus that row's primary key. The next run reads everything past that value.

| | `mode: full` | `mode: watermark` |
|---|---|---|
| ordered by | primary key | watermark column, then primary key |
| cursor holds | the last primary key | the last watermark value **and** its row's key |
| a row **inserted** with a higher key | read | read |
| a row **inserted** with a lower key (a backfill) | **never read** | read — the insert stamps the column |
| a row **updated** in place | **never read again** | read again |
| a row **deleted** | never noticed | never noticed |
| a run with nothing changed | 0 rows | 0 rows |
| declares | `pk_columns` | `pk_columns` **and** `watermark_column` |

### The watermark manifest

The quickstart's connector, read incrementally instead. `mode` and `watermark_column` sit
under `defaults:` — the place for what every table of a connector shares:

```yaml
--8<-- "docs/connectors/jdbc/20-watermark.yaml"
```

### The same table, five runs

Runs 1 and 2 use the quickstart manifest; run 3 applies the one above over the same
connector, without touching the hub table.

```sql
-- between run 1 and run 2: one insert, one update
INSERT INTO demo.invoices VALUES (1005, 'Tailspin AG', 640.00, 'open', '2026-09-08 07:30:00');
UPDATE demo.invoices SET status = 'paid', updated_at = '2026-09-08 07:45:00' WHERE invoice_id = 1002;

-- between run 3 and run 4: one update
UPDATE demo.invoices SET status = 'paid', updated_at = '2026-09-08 09:10:00' WHERE invoice_id = 1003;
```

```
$ lm run list --connector demo-invoices
ID        CONNECTOR      ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
41823606  demo-invoices  invoices  Completed  0   0    0        0             2026-09-08 01:19  2026-09-08 01:19
8440fe3e  demo-invoices  invoices  Completed  1   1    0        0             2026-09-08 01:19  2026-09-08 01:19
69f31e57  demo-invoices  invoices  Completed  5   5    0        0             2026-09-08 01:18  2026-09-08 01:18
bdfb4d96  demo-invoices  invoices  Completed  1   1    0        0             2026-09-08 01:18  2026-09-08 01:18
16b2bee8  demo-invoices  invoices  Completed  4   4    0        0             2026-09-08 01:18  2026-09-08 01:18
```

| run | mode | source change before it | `IN` | why |
|---|---|---|---|---|
| 16b2bee8 | full | — | 4 | first read, no cursor |
| bdfb4d96 | full | 1 insert, 1 update | **1** | only the insert — its key sorts past the cursor. The update is invisible to a key cursor |
| 69f31e57 | watermark | the manifest changed mode | 5 | the full-mode cursor belongs to another configuration, so the table restarts |
| 8440fe3e | watermark | 1 update | **1** | the changed row's `updated_at` is past the cursor |
| 41823606 | watermark | — | 0 | nothing past the cursor |

After run 2 the hub still said invoice 1002 was `open` while the source said `paid`. That is
`mode: full` doing exactly what it declares.

### Which one to declare

- **`watermark`, whenever the source stamps a column that moves on every write.** ERPs
  usually have one: `updated_at`, `updated`, `modified_at`, `last_modified`.
- **`full`, for a table that only grows** — an append-only ledger, a reference table nobody
  edits in place.
- **`full` on a table that *is* edited in place will not see the edits.** Either expose a
  timestamp column on the source, or re-read the table on purpose with
  [`lm endpoint reset-cursor`](#restarting-a-table-on-purpose) before each run.

!!! warning "A watermark column that does not move is worse than `full`"
    The mode is a promise about the column, and the hub cannot check it. Two ways to get it
    wrong, both silent:

    - **A column the application forgets to touch** on some writes — those changes never
      reach the hub.
    - **A numeric primary key as the watermark.** `lm source jdbc discover` suggests one when
      it finds no timestamp (see the `price_list` line [below](#discover)) — accepting it
      gives you `mode: full` behaviour under another name.

## Connection

```yaml
connection:
  url: jdbc:postgresql://db.acme.io:5432/billing   # required
  user: ro_user                                    # required
  password_env: ACME_DB_PASSWORD                   # required — a variable where the HUB runs
  dialect: postgres                                # optional, inferred from the url
```

- **Dialect**: the providers installed in your hub — PostgreSQL (`postgres`, alias `postgresql`)
  ships with every edition; Oracle (`oracle`) ships only with lumnik Pro — see [Oracle](#oracle).
  A declared `dialect` that is not installed, or that does not accept the URL, fails validation;
  an absent one is inferred only when exactly one installed provider accepts the URL.
- **Grants**: `USAGE` on the schema and `SELECT` on the tables. Nothing more — ingestion
  issues `SELECT * FROM <schema>.<table>`, and `test` / `discover` use the driver's read-only
  metadata calls.
- **Reachability is the hub's, not yours.** The URL is resolved where the hub runs: a
  database on your Mac is `host.docker.internal` for a hub in Docker, not `localhost`.

### Where the password comes from

Resolved at run time, first match wins:

| | Source | Notes |
|---|---|---|
| 1 | **Attached credential** | A Basic credential on the connector, stored encrypted. Created by [`lm source jdbc add`](#guided-flow-lm-source-jdbc), named `<source-name>--credential`. A manifest applied with `lm apply` never sets one. |
| 2 | `password` in the manifest | Plain text. Dev and test only — it **wins over** `password_env`. |
| 3 | `password_env` | The name of a variable in the **hub server's** environment (OS variable or JVM system property). Unset at run time is a failed run with the variable named. |

- `password_env` is **required by validation** even when `password` is present.
- jdbc does **not** read the [`lm secret`](../admin-console-guide.md) registry — that is for
  the CSV transports. The value must exist where the hub process runs.
- "Stored encrypted" is pgcrypto **AES-256** under `LUMNIK_CRYPTO_SECRET_KEY` — see
  [Security → Secrets at rest](../security/rls.md#secrets-at-rest-the-master-key).

!!! warning "Never commit a real password"
    Line 2 exists for a local demo. Anything else uses `password_env` or the guided flow's
    credential.

## Source — one entry per table

```yaml
--8<-- "docs/connectors/jdbc/40-two-tables.yaml"
```

Each entry in `source.tables[]` is an **endpoint** of the connector:

- the endpoint name is the table name, its path is `/<table-name>`;
- one `lm connector run` fans out over all of them;
- **each endpoint writes its own row in the run ledger** — a two-table connector logs two
  rows per run.

```
$ lm run list --connector demo-erp
ID        CONNECTOR  ENDPOINT    STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
41b06fa9  demo-erp   price_list  Completed  0   0    0        0             2026-09-08 01:22  2026-09-08 01:22
18c3897f  demo-erp   warehouses  Completed  0   0    0        0             2026-09-08 01:22  2026-09-08 01:22
103bb6c9  demo-erp   price_list  Completed  3   3    0        0             2026-09-08 01:20  2026-09-08 01:20
8953b0c8  demo-erp   warehouses  Completed  2   2    0        0             2026-09-08 01:20  2026-09-08 01:20
```

The first run read `warehouses` (2 rows) and `price_list` (3); the second found nothing new
in either. `ENDPOINT` is the table each row read — and the second half of the address the
endpoint commands take, so a row that needs one reads straight into
`lm endpoint reset-cursor demo-erp/warehouses`.

### Per-table keys

| Key | Required | What it does |
|---|---|---|
| `name` | yes | The source table. Also names the hub table, `connector.t_<name>` |
| `schema` | yes (here or in `defaults`) | The source schema |
| `mode` | yes | `full` or `watermark` |
| `watermark_column` | when `mode: watermark` | The column that moves on every change |
| `pk_columns` | yes | The paging key, read whole and in order. It must be unique **together** |
| `indexes` | no | Postgres indexes to create on the hub table |
| `rename` | no | Source column name → hub column name |

- **Any other key is refused**, `chunk_size` first among them — see
  [Validation rules](#validation-rules).

!!! tip "`pk_columns` is the paging key, read whole"
    The reader orders by every entry and resumes on all of them at once —
    `WHERE (order_id, line_no) > (72, 3)`, one lexicographic comparison. A composite key pages
    exactly like a single-column one: no group is split by a chunk boundary.

    `demo.order_lines` is 700 lines over 100 orders, seven each:

    ```yaml
    --8<-- "docs/connectors/jdbc/60-composite-key.yaml"
    ```

    The full read, then a second run that finds nothing — and 700 rows in the hub table:

    ```
    $ lm run list --connector demo-lines --limit 2
    ID        CONNECTOR   ENDPOINT     STATUS     IN   OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
    b8b26cac  demo-lines  order_lines  Completed  0    0    0        0             2026-09-08 23:38  2026-09-08 23:38
    65dee4bd  demo-lines  order_lines  Completed  700  700  0        0             2026-09-08 23:37  2026-09-08 23:37
    note: result hit the limit of 2 — there may be more
    ```

    **The order of the entries matters** — it is the paging order, and it is part of what the
    cursor belongs to. Reversing it restarts the table (see
    [What restarts a table](#what-restarts-a-table)).

    What still has to hold: the key must be unique **together**. A real primary key is — that is
    what makes it one, and `lm source jdbc add` writes the discovered primary key, so the guided
    flow is safe by construction. A hand-written manifest is not: declaring part of a key, or a
    plain column that repeats, still drops the group a chunk boundary falls inside, with no skip,
    no dead letter and no failed run.

### `defaults`

`defaults:` is a flat overlay: every key it holds is a fallback for every table, and a table
that declares the same key wins.

- The merge is **per key, not deep**. A table's `rename:` replaces the one in `defaults:`
  entirely rather than merging into it.
- `mode`, `watermark_column`, `schema` in `defaults:` are the common case — see
  [the watermark manifest](#the-watermark-manifest), where both live there.

### Chunking is fixed

Reads page through the source in **500-row chunks**, keyset-paginated on the primary key, so
a table of millions of rows stays linear and memory-bounded. There is no `chunk_size` setting:
a manifest that declares one is refused rather than silently ignored.

## The hub table

Rows land in the `connector` schema as `connector.t_<table-name>`, one table per source
table, created on the first run from the columns the source returned.

!!! warning "The hub table is named after the source table, not the connector"
    Two connectors reading a table of the same name — the same table in two schemas, or the
    same table on two servers — write into the **same** `connector.t_<name>`. Give one of
    them a view under another name.

### Column types

The hub types each column from the source's declared JDBC type:

| Source type | Hub column |
|---|---|
| `smallint`, `integer`, `bigint` | `BIGINT` |
| `real`, `double precision`, `numeric`, `decimal` | `NUMERIC(18,4)` |
| `timestamp`, `timestamptz` | `TIMESTAMPTZ` |
| `date` | `DATE` |
| `boolean`, `bit` | `BOOLEAN` |
| everything else | `TEXT` |

- **`TEXT` is the generic fallback for a type this table does not name — it does not mean a
  dialect accepts every type it does not recognize.** The Oracle dialect has its own type table
  (see [Oracle → Types](#oracle)) and **refuses** a column outside it, at discovery and at
  ingestion; such a column never silently lands as `TEXT`.
- **`NUMERIC(18,4)` holds at most 14 digits before the point.** A source column wider than
  that — a 19-digit snowflake-style id declared `numeric(20,0)` — fails the run with `numeric
  field overflow`. Expose it as text through a view: `SELECT event_id::text AS event_id, ...`.
  An id declared `bigint` is not concerned: it lands in `BIGINT`, which holds everything the
  source column can.
- **A table the hub already created keeps the types it was created with.** There is no backfill:
  columns typed `numeric(18,4)` before this stay that way and keep printing four decimals. To
  retype them, drop the connector (which drops its tables) and re-ingest, or `ALTER` the columns
  yourself.
- **A column can still widen, once, in one direction.** Types are decided from the chunk in hand,
  so a column of whole numbers lands `bigint`; the first later chunk that carries a fractional
  value for it is widened to `numeric(18,4)` before the insert, and the run continues. The hub
  log names the columns. `ALTER COLUMN … TYPE` rewrites the table under an exclusive lock, so on
  a large table that chunk takes noticeably longer — once.

### Seven reserved column names

`id`, `_row_hash`, `_ingested_at`, `_source`, `_run_id`, `_mapping_version`, `tenant_id` —
the hub adds these to every table it creates. A source column of one of those names is
refused at write time, on a new table and an existing one alike, so no run ever drops a
column's values in silence.

```
$ lm run get 9165490f
ID        CONNECTOR        ENDPOINT    STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
9165490f  demo-price-list  price_list  Failed  0   0    0        0             2026-09-08 01:19  2026-09-08 01:19
✖ run failed: source column 'id' collides with a hub system column (_ingested_at, _mapping_version, _row_hash, _run_id, _source, id, tenant_id); it must reach the hub under another name — jdbc: source.tables[].rename; csv: a map-columns row hook under spec.mapping; kafka: no rename exists, the field must be renamed in the topic payload
```

`rename:` is the fix — `id` is the one you will meet, but the seven collide alike:

```yaml
--8<-- "docs/connectors/jdbc/30-rename-reserved-column.yaml"
```

```
$ lm connector run demo-price-list
run scheduled: 484aea15-ac55-44e6-ab9d-0e7e26189a8a
$ lm run list --connector demo-price-list
ID        CONNECTOR        ENDPOINT    STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
484aea15  demo-price-list  price_list  Completed  3   3    0        0             2026-09-08 01:19  2026-09-08 01:19
9165490f  demo-price-list  price_list  Failed     0   0    0        0             2026-09-08 01:19  2026-09-08 01:19
```

```
 price_list_id |   sku    |  price
---------------+----------+----------
             1 | PUMP-075 | 349.0000
             2 | HOSE-32M |  59.9000
             3 | FILT-A1  | 129.5000
```

- The rename applies **in the hub table only**. Nothing is renamed on the source.
- Renaming a column *into* one of the seven is refused at `lm validate` — it only moves the
  collision.
- Two source columns renamed onto the same hub column are refused for the same reason.

### One row per version, never an update

The hub table has a unique constraint on `_row_hash`, a hash of the row's content:

- **A row read again unchanged is skipped**, so re-reading costs nothing but the read.
- **A row changed at the source lands as a new row**, with its own `_ingested_at`. Both
  versions stay. That is the time axis — the hub table is a tape, not a mirror.

After the five runs above, `t_invoices` holds seven rows for five invoices:

```
 invoice_id | status |       updated_at       |         _ingested_at
------------+--------+------------------------+-------------------------------
       1001 | paid   | 2026-09-01 09:15:00+00 | 2026-09-07 23:18:34.339879+00
       1002 | open   | 2026-09-01 11:40:00+00 | 2026-09-07 23:18:34.339879+00
       1002 | paid   | 2026-09-08 07:45:00+00 | 2026-09-07 23:18:56.001046+00
       1003 | open   | 2026-09-02 08:05:00+00 | 2026-09-07 23:18:34.339879+00
       1003 | paid   | 2026-09-08 09:10:00+00 | 2026-09-07 23:19:00.384925+00
       1004 | draft  | 2026-09-02 16:20:00+00 | 2026-09-07 23:18:34.339879+00
       1005 | open   | 2026-09-08 07:30:00+00 | 2026-09-07 23:18:44.432353+00
```

`_ingested_at` is stored in UTC; the source's own `updated_at` is whatever the source wrote.

!!! note "`OUT` counts rows delivered, not rows inserted"
    `records_out` is the number of source rows handed to the target. Rows the hash skipped
    are counted there too, which is why a re-read that grows the table by nothing still
    reports a non-zero `OUT`.

### Deletes are never propagated

A row deleted at the source stays in the hub table, in both modes, forever: neither cursor
ever sees an absence. To resync from nothing, `lm connector delete` drops the connector's
`connector.t_*` tables and its RAG chunks, and the next apply plus run starts clean.

### Indexes on the hub table

`indexes:` is **per table** for jdbc — that is where the runtime reads it. (`spec.target.indexes`
at connector level is what [CSV & files](csv.md#target-table) reads. A jdbc manifest carrying a
`spec.target` block at all is answered with an `unknown key` warning: nothing on this kind reads
one, so an index declared there would be accepted and then silently dropped.)

```yaml
indexes:
  - { columns: [customer], unique: false }     # unique defaults to false
```

Each entry is `{ columns: [non-empty strings], unique?: bool }`. A malformed entry fails
validation with its own error rather than being dropped.

## Cursors — resume, restart, reset

A cursor is stored per endpoint, after every chunk. An interrupted run resumes exactly where
it stopped: no duplicates, no gaps.

A primary-key column of a type JSON cannot carry — `DATE`, `TIMESTAMP`, `TIME` or `UUID` — is stored as text
with its type recorded beside it (`pkTypes`) and read back as the same type, so it compares against its
column on the next chunk. A cursor written before that record existed reads as it always did.

- **`mode: watermark`** stores `{col, value, pk}` and resumes with
  `WHERE (col, pk...) > (value, last_pk...)` — one lexicographic comparison over the watermark
  followed by every key column. Rows sharing one watermark value cannot straddle a chunk boundary
  and be lost, or read twice — the key breaks the tie.
- **`mode: full`** stores the key alone and resumes with `WHERE (pk...) > (last_pk...)`.

### A run reads what the table held when it started

Both modes also carry a **ceiling**: the greatest key row the table held at the moment the run
started, measured once and added to the same comparison —
`AND (col, pk...) <= (ceiling...)`. So a row **inserted while the run is in flight** belongs to the
next run, not this one.

That is what makes a run end on a table that is still being written to. Without the ceiling the
query paged forward for as long as the inserts continued: on a busy table the run never reached a
quiet chunk, so it never completed, the run row stayed `Running`, and every scheduled slot behind
it was skipped by the per-endpoint lock. Nothing was lost — but nothing ever finished either, and
a `window` fence that only gates a run's *start* was walked straight through by a run that began
inside it.

Rows whose watermark is `NULL` are read by a **first** pass and never by a resume — the resume
comparison is unknown for them. That is not the ceiling's doing and the ceiling does not change it:
it carries `OR <watermark> IS NULL` for exactly that reason. If such rows must be kept current,
give the column a value or read the table in `mode: full`.

The ceiling costs **one query per run**, `ORDER BY <keys> DESC LIMIT 1` — a backward scan of the
same index the chunk loop already needs. On a table with no index over `(watermark, pk…)` it is a
full sort, but so is every chunk the run then reads: the index is the condition for ingesting that
table at all, not a new requirement this adds.

### What restarts a table

A cursor belongs to the configuration that wrote it. Changing `mode`, `watermark_column` or
`pk_columns` — its columns **or their order**, which is the paging order — makes the stored
cursor foreign: the hub ignores it, logs a WARN naming both, and the table restarts from the
beginning.

```
WARN  demo.invoices: ignoring cursor persisted by another configuration (col='', pk=[invoice_id];
      this reader writes col='updated_at', pk=[invoice_id]) — restarting from the beginning
```

A restart re-reads the table from the beginning: every row already in the hub table meets its
own hash and is skipped, so the table does not grow. `IN` and `OUT` count the re-read rows all
the same — run 69f31e57 above is exactly this.

### Restarting a table on purpose

`lm endpoint reset-cursor <connector>/<endpoint>` drops the stored cursor. For jdbc the
endpoint name is the table name. It prints nothing; the next run's `IN` is the proof:

```
$ lm endpoint reset-cursor demo-invoices/invoices
$ lm connector run demo-invoices
run scheduled: 0935e410-5216-4b0e-8d02-2f0adb1a6ac2
$ lm run list --connector demo-invoices
ID        CONNECTOR      ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
0935e410  demo-invoices  invoices  Completed  5   5    0        0             2026-09-08 01:20  2026-09-08 01:20
41823606  demo-invoices  invoices  Completed  0   0    0        0             2026-09-08 01:19  2026-09-08 01:19
```

## Scheduling

```yaml
spec:
  schedule: "*/30 * * * *"   # cron, UTC
  window: "22:00-06:00"      # optional fence, UTC
```

- **`schedule`** applies to the connector as a whole: all its tables ingest on the same
  trigger.
- **Both clocks are UTC**, not the server's local zone.
- **`window`** fences the schedule: a slot due outside it is skipped, never queued. A
  window may cross midnight.
- **The end is exclusive.** A nightly `0 6 * * *` needs a window ending later than `06:00`,
  for instance `22:00-07:00`.
- **A manual `lm connector run` ignores the window.** It is an operator's act.
- **Re-applying without the key lifts it.** An absent `schedule` unschedules; an absent
  `window` removes the fence.

If the source is a production database, a window is usually the DBA's condition for granting
the `SELECT` account — see [Sizing & load tests](../operations/sizing.md).

## Guided flow — `lm source jdbc`

Four commands that reach the same connectors without writing YAML. They need the `lm_admin`
role, and the password is **never a flag** — there is no `--password`, so it stays out of `ps`
and out of your shell history.

Where it comes from instead:

| You are | What happens |
|---|---|
| at a keyboard | `Password for <user>: ` is prompted, and what you type is not shown |
| piping, with `--password-stdin` | the first line of stdin is the password |
| piping, without the flag | same, plus a warning on stderr naming the flag |

`--password-stdin` is spelled the way `docker login` and `helm registry login` spell it. Declaring
the pipe matters: `lm source jdbc add … < list.txt` otherwise sends that file's first line to the
hub as a password, and nothing would say so.

Use it to explore a base you do not know, and to stand up a table quickly. Reach for a
manifest when you need what it cannot express: `rename`, `indexes`, a view, a composite or
undiscoverable key, or config you keep in version control.

### `test`

```
$ printf '%s\n' "$DB_PASSWORD" | lm source jdbc test --password-stdin --url jdbc:postgresql://postgres:5432/lumnik --user lumnik_readonly
OK       : true
Dialect  : postgres
Version  : 16.15 (Debian 16.15-1.pgdg12+2)
Latency  : 1 ms
Schemas  : connector, core, demo, ext, identity, information_schema, pg_catalog, platform, public, rag
```

`Schemas` lists what this user can see — a schema absent from that line is a missing `USAGE`
grant, before any manifest is written.

### `discover`

```
$ printf '%s\n' "$DB_PASSWORD" | lm source jdbc discover --password-stdin --url jdbc:postgresql://postgres:5432/lumnik --user lumnik_readonly --schema demo
SCHEMA     NAME                            ROWS  COLS PK                   WATERMARK       UNSUPPORTED
demo       audit_log                          1     3                      -               -
demo       invoices                           4     5 invoice_id           updated_at      -
demo       price_list                         3     3 id                   id              -
demo       warehouses                         2     4 warehouse_id         updated_at      -
```

- `--schema` defaults to `public`. An ERP in its own schema names it here.
- `ROWS` is the planner's estimate, `-1` when the table was never analyzed.
- `PK` empty means no primary key — that table needs a manifest or a view.
- `WATERMARK` is a **suggestion**, in this order: a column named `updated_at`, `updated`,
  `modified_at` or `last_modified`; else the first timestamp or date column; else a single
  numeric primary key. The last case is the trap named [above](#which-one-to-declare) —
  `price_list` suggests `id`.
- `UNSUPPORTED` names, as `COLUMN TYPE`, the columns the source's dialect cannot read (`-` when there is
  none). Such a table is listed, but `add` refuses it by name and the interactive picker marks it `[!]`
  and will not select it. PostgreSQL reads every column type it reports, so the column stays `-` there.
- `--include-views` adds views to the listing; `--skip-pattern` replaces the **source dialect's** default
  exclusions (PostgreSQL: `pg_*,information_schema*,t_*`) — with no flag the CLI sends none and the hub
  applies the dialect's own; `--output json` prints the whole descriptor, `unsupportedColumns` included.

### `add`

```
$ printf '%s\n' "$DB_PASSWORD" | lm source jdbc add --password-stdin demo-src --url jdbc:postgresql://postgres:5432/lumnik --user lumnik_readonly --schema demo --tables warehouses
Created 1 connectors:
  • demo-src--warehouses
$ lm source jdbc list-tables demo-src
TABLE                          CONNECTOR                                STATUS
warehouses                     demo-src--warehouses                     active
$ lm connector run demo-src--warehouses
run scheduled: 110866d1-9926-4c0b-a200-25fb9037f8f2
$ lm run list --connector demo-src--warehouses
ID        CONNECTOR             ENDPOINT    STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
110866d1  demo-src--warehouses  warehouses  Completed  2   2    0        0             2026-09-08 01:20  2026-09-08 01:20
```

What it creates, per requested table:

- one connector named `<source-name>--<table>`;
- one Basic credential per source, `<source-name>--credential`, stored encrypted — the
  password never lands in a file;
- `mode: watermark` when a watermark column was found or given, `mode: full` otherwise. The
  hub decides; there is no `--mode`.
- `--interactive` opens a TUI table picker instead of `--tables`, pre-filled with the
  discovered suggestions. `--schedule-cron` sets the schedule.

What it refuses, by name and reason, before anything is persisted:

```
$ printf '%s\n' "$DB_PASSWORD" | lm source jdbc add --password-stdin demo-src ... --tables audit_log
Error: API error 400: table demo.audit_log has no primary key — jdbc-generic pages by primary key; declare pk_columns in a manifest instead
$ printf '%s\n' "$DB_PASSWORD" | lm source jdbc add --password-stdin demo-src ... --tables nope
Error: API error 400: table demo.nope not found among the base tables of that schema — check the schema and the connection user's SELECT grant; a view is declared in a manifest, not through the guided add
```

- a table it cannot find in that schema, and a **view** — a view has no key to discover;
- a table without a primary key;
- a table whose name is not a plain identifier — the hub names its table after it;
- `mode: watermark` with no column to watermark on.

A table whose connector already exists is reported as skipped, not re-created.

## Oracle

> **lumnik Pro.** The Oracle provider ships in lumnik Pro with its JDBC driver — you drop no jar. The open
> edition has no Oracle provider: a manifest declaring `dialect: oracle` there is refused with
> `JDBC dialect 'oracle' is not installed. Available: postgres`.

```yaml
spec:
  connection:
    url: jdbc:oracle:thin:@//erp-db.acme.io:1521/ERP   # by service name
    user: ro_user
    password_env: ACME_DB_PASSWORD
    dialect: oracle                                    # optional, inferred from the url
  defaults:
    schema: ERP                                        # the OWNER, exactly as Oracle stores it
  source:
    tables:
      - name: ORDERS                                   # exactly as `lm source jdbc discover` prints it
        mode: watermark
        watermark_column: UPDATED_AT
        pk_columns: [ID]
        rename:
          ID: order_id                                 # optional: land a column under another name
```

- **Versions.** Oracle 12c and later (the paging uses `FETCH FIRST n ROWS ONLY`). It is tested on Oracle 23ai
  Free only; other versions are untested. Oracle 11g is not supported.
- **Connect by service name.** The URL goes to the driver untouched. `@//host:1521/SERVICE`,
  `@host:1521/SERVICE` and a full `@(DESCRIPTION=…)` descriptor were each checked against a live Oracle 23ai;
  quote a descriptor in YAML, it contains parentheses. The older **SID** form (`@host:1521:SID`) works on a
  non-multitenant database, but on a multitenant one it reaches the container root, where a pluggable
  database's user does not exist (`ORA-01017`) — use the service name there. TCPS and wallets are not tested.
- **`lm` needs `--schema`.** `lm source jdbc discover` and `add` default to `--schema public`, which is
  PostgreSQL's; for Oracle pass the owner, e.g. `--schema ERP`, or the listing is empty.
- **Names are exact.** Oracle folds an unquoted name to upper case; this provider never folds. Write the
  schema, table and column names exactly as `lm source jdbc discover` lists them (`ORDERS`, not `orders`);
  they are quoted as given. A mixed-case name created with quotes is written in that case.
- **On the hub side the labels keep that spelling.** The hub table's columns are the Oracle labels as the
  driver returns them — quoted, upper-case (`"ID"`, `"UPDATED_AT"`) — next to the hub's own lower-case
  system columns. Quote them in a view or a query, or use `rename:` to land a given column under another
  (for instance lower-case) name.
- **Types.** Supported: `NUMBER`, `FLOAT`, `BINARY_FLOAT`, `BINARY_DOUBLE`, `VARCHAR2`, `NVARCHAR2`,
  `CHAR`, `NCHAR`, `DATE` (which carries a time part), `TIMESTAMP`, `TIMESTAMP WITH [LOCAL] TIME ZONE`,
  `CLOB` and `NCLOB` — each lands in the hub column its own value decides, by the same
  [Column types](#column-types) table every dialect uses. Only `CLOB` and `NCLOB` are read as text; a
  `NUMBER`, a `DATE` or a `TIMESTAMP [WITH [LOCAL] TIME ZONE]` keeps its own typed JDBC value all the
  way to the hub column. Any other column type — `BLOB`, `RAW`, `LONG`, `LONG RAW`, `XMLTYPE`, `BFILE`,
  `ROWID`, object types — is **refused**: `discover` lists the table with the column and its type in
  the `UNSUPPORTED` column, and `add` refuses it by name. Nothing is ingested wrongly.
- **Zoned timestamps are read as instants** (`TIMESTAMP WITH [LOCAL] TIME ZONE` lands as `timestamptz`), as
  PostgreSQL's driver does — which is what lets such a column serve as a watermark.
- **Empty string is `NULL`.** Oracle stores `''` as `NULL`; the hub receives `NULL`.
- **Index `(watermark, key)` on every Oracle table you page.** Without an index starting with the
  watermark column, every page scans the whole table — the cost grows with the table and gets worse
  on every later page. One shape stays slower even with the index: a very large group of rows
  sharing one watermark value pages more slowly within that group than the rest of the table.
  Measured on Oracle 23ai Free only; other versions and data shapes are not measured.
- **Watermark ties and `NULL`s** behave exactly as on PostgreSQL: rows tied on the watermark are neither lost
  nor repeated across chunks, and a row with no watermark is read by a first pass that fits in one chunk
  (see [A run reads what the table held when it started](#a-run-reads-what-the-table-held-when-it-started)).

## Reading a view

A view is how a table reaches the hub when the table itself cannot: no primary key, a
reserved column name, a value too wide for the hub's numeric type, or more rows than the
métier needs.

```yaml
--8<-- "docs/connectors/jdbc/50-view.yaml"
```

```
$ lm run list --connector demo-open-invoices
ID        CONNECTOR           ENDPOINT         STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
eaee8eaa  demo-open-invoices  v_open_invoices  Completed  2   2    0        0             2026-09-08 01:22  2026-09-08 01:22
```

The columns named in `pk_columns` must be unique together and stable — they are what the
reader pages on.

## Validation rules

`lm validate` refuses before `lm apply` does, and both name the path:

```
$ lm validate -f bad-chunk.yaml
✗ bad-chunk.yaml — 1 error(s), 0 warning(s)

  ERROR    spec.source.tables[0].chunk_size
          chunk_size is not a knob — jdbc chunking is fixed at 500 rows per read; remove it
          → The engine reads its chunk size from the connector type, never from the manifest
```

| Rule | Severity | Path |
|---|---|---|
| `connection.url` matches `jdbc:<dialect>:...` | ERROR | `spec.connection.url` |
| Dialect is installed and accepts the URL (PostgreSQL ships by default) | ERROR | `spec.connection.dialect` (declared) or `spec.connection.url` (inferred) |
| A declared `dialect` matches the URL scheme | ERROR | `spec.connection.dialect` |
| `connection.user` and `connection.password_env` present | ERROR | `spec.connection.*` |
| `source.tables[]` non-empty | ERROR | `spec.source.tables` |
| `mode` present and in {watermark, full} | ERROR | `spec.source.tables[i].mode` |
| `mode: watermark` declares `watermark_column` | ERROR | `spec.source.tables[i].watermark_column` |
| `pk_columns` is a non-empty list | ERROR | `spec.source.tables[i].pk_columns` |
| `chunk_size` is refused — chunking is fixed at 500 | ERROR | `spec.source.tables[i].chunk_size` |
| No unknown per-table key | ERROR | `spec.source.tables[i]` |
| `indexes` entries are `{ columns: [...], unique?: bool }` | ERROR | `spec.source.tables[i].indexes[j]` |
| No duplicate table names | ERROR | `spec.source.tables[i].name` |
| `rename` targets are plain identifiers | ERROR | `spec.source.tables[i].rename` |
| `rename` targets none of the seven reserved names | ERROR | `spec.source.tables[i].rename` |
| No two `rename` sources hit the same target | ERROR | `spec.source.tables[i].rename` |
| `schedule` is a valid cron | ERROR | `spec.schedule` |
| `window` is `HH:MM-HH:MM` and not empty | ERROR | `spec.window` |
| No unknown key at the root of `spec` | WARNING | `spec.<key>` |
| `metadata.scopes` declared | WARNING | `metadata.scopes` |

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| `password_env '...' is not set in the server environment` | the variable must exist where the **hub** runs, not on your laptop; jdbc does not read `lm secret` | export it where the hub runs, or attach a credential through the guided flow |
| `source column 'id' collides with a hub system column` | the source has a column named like one of the seven | add `rename: { id: source_id }` on that table |
| `numeric field overflow` | a numeric value wider than 14 digits before the point | expose the column as text through a view |
| the run reads 0 rows and nothing is wrong | the cursor is already past everything | expected — change a row at the source, or `lm endpoint reset-cursor` |
| updated rows never arrive | `mode: full`, or a watermark column the application does not move | switch to `watermark` on a column that moves |
| a WARN says the cursor was persisted by another configuration | `mode`, `watermark_column` or `pk_columns` changed (its columns or their order) | expected once; the table restarts and the hashes deduplicate |
| a deleted source row is still in the hub table | by design, in both modes | `lm connector delete` then re-apply, or filter on `_ingested_at` |
| a table is in `discover` but `add` refuses it | no primary key, or it is a view | declare `pk_columns` in a manifest |
| the hub table holds fewer rows than the source, no error anywhere | the declared `pk_columns` is not unique — part of a key, or a plain column that repeats — so a chunk boundary dropped a group | declare the whole primary key — see [Per-table keys](#per-table-keys) |

## See also

- [Connectors overview](index.md)
- [CSV & files](csv.md) — the same hub table, fed by files
- [Scopes](../rag/scopes.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
