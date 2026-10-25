# JDBC

> Read tables from a live PostgreSQL or Oracle database into hub tables — the whole table
> once, then only what changed, on a schedule.

| | |
|---|---|
| **Type** | `jdbc-generic` |
| **Dialect** | postgres — Open; [oracle](#oracle) — **Pro only** |
| **Target** | `connector.t_<table>`, or `connector.t_<target>` |
| **Schedulable** | cron |
| **Edition** | Open for PostgreSQL. Oracle ships only in lumnik Pro — see [Oracle](#oracle) |

The connection is read-only: every query this connector issues is a `SELECT`.

## Quickstart — one table, three commands, a look at the ledger

```yaml
--8<-- "docs/connectors/jdbc/10-full-dump.yaml"
```

The screens on this page read a demo source in the hub's **own** PostgreSQL — four tables and a
view — so they replay on the [self-host stack](../deploy.md) alone. Create it first:

```bash
docker compose -f docker-compose.selfhost.yml exec -T postgres psql -U lumnik -d lumnik < docs/connectors/jdbc/data/demo-source.sql
```

??? note "What that script creates"

    ```sql
    --8<-- "docs/connectors/jdbc/data/demo-source.sql"
    ```

Then, from the repository root:

```
$ lm validate -f docs/connectors/jdbc/10-full-dump.yaml
✓ 10-full-dump.yaml — valid
$ lm apply -f docs/connectors/jdbc/10-full-dump.yaml
Applied connector "demo-invoices" — 1 endpoint
$ lm connector run demo-invoices
run scheduled: d36a90a0-c658-4335-baa4-d63f685b59e0
```

"Scheduled" is not "done" — the run ledger is where the result lands:

```
$ lm run list --connector demo-invoices
ID        CONNECTOR      ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
d36a90a0  demo-invoices  invoices  Completed  4   4    0        0             2026-10-19 01:40  2026-10-19 01:40
```

`STARTED` / `ENDED` print in **your terminal's** time zone (these screens: Paris, UTC+2).
`schedule` is evaluated in UTC.

What landed in `connector.t_invoices` — every source column:

```
 invoice_id |   customer    |   total   | status |       updated_at
------------+---------------+-----------+--------+------------------------
       1001 | Northwind Ltd | 1250.0000 | paid   | 2026-09-01 09:15:00+00
       1002 | Contoso SA    |  480.5000 | open   | 2026-09-01 11:40:00+00
       1003 | Fabrikam Oy   | 2310.7500 | open   | 2026-09-02 08:05:00+00
       1004 | Adventure BV  |  199.9000 | draft  | 2026-09-02 16:20:00+00
```

- **`jdbc-generic` is a 1:1 dump.** There is no column filter; `rename:` is the only per-column
  knob. To read fewer columns or rows, [read a view](#reading-a-view).
- **There is no `mapping` block.** Transformers and Kotlin scripts run on a
  [csv-file](csv.md) connector. A `mapping:` here is refused, so a check you declared cannot be
  skipped without anyone noticing:
  `spec.mapping — jdbc-generic has no mapping block — rows land as the source holds them, so nothing declared here would run`.
- **`invoice_id` reads `1001`, `total` reads `1250.0000`** — see [Column types](#column-types).
- **The password never sits in the manifest.** `password_env` names a variable in the
  environment where the **hub** runs (the self-host stack exports the one used here), or an
  `lm secret` entry.

## Two modes — `full` and `watermark`

Both modes remember where the last run stopped:

- **`mode: full`** remembers the **last primary key** it read. The next run continues past it.
- **`mode: watermark`** remembers the **last value of a column that moves on every change**,
  plus that row's key. The next run reads everything past that value.

| | `mode: full` | `mode: watermark` |
|---|---|---|
| ordered by | primary key | watermark column, then primary key |
| a row **inserted** with a higher key | read | read |
| a row **inserted** with a lower key (a backfill) | **never read** | read — the insert stamps the column |
| a row **updated** in place | **never read again** | read again |
| a row **deleted** | never noticed | never noticed |
| a run with nothing changed | 0 rows | 0 rows |
| declares | `pk_columns` | `pk_columns` **and** `watermark_column` |

### The watermark manifest

The quickstart's connector, read incrementally. `mode` and `watermark_column` sit under
`defaults:`, which holds what every table of the connector shares:

```yaml
--8<-- "docs/connectors/jdbc/20-watermark.yaml"
```

### The same table, five runs

Runs 1 and 2 use the quickstart manifest; run 3 applies the one above over the same connector.

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
87b4931c  demo-invoices  invoices  Completed  0   0    0        0             2026-10-19 01:40  2026-10-19 01:40
5e3330f8  demo-invoices  invoices  Completed  1   1    0        0             2026-10-19 01:40  2026-10-19 01:40
772a43f1  demo-invoices  invoices  Completed  5   5    0        0             2026-10-19 01:40  2026-10-19 01:40
55bd41b4  demo-invoices  invoices  Completed  1   1    0        0             2026-10-19 01:40  2026-10-19 01:40
d36a90a0  demo-invoices  invoices  Completed  4   4    0        0             2026-10-19 01:40  2026-10-19 01:40
```

| run | mode | source change before it | `IN` | why |
|---|---|---|---|---|
| d36a90a0 | full | — | 4 | first read, no cursor |
| 55bd41b4 | full | 1 insert, 1 update | **1** | only the insert — its key sorts past the cursor. The update is invisible to a key cursor |
| 772a43f1 | watermark | the manifest changed mode | 5 | the table restarts — see [What restarts a table](#resume-restart-reset) |
| 5e3330f8 | watermark | 1 update | **1** | the changed row's `updated_at` is past the cursor |
| 87b4931c | watermark | — | 0 | nothing past the cursor |

After run 2 the hub still said invoice 1002 was `open` while the source said `paid`: `mode: full`
does not see updates.

### Which one to declare

- **`watermark`, whenever the source stamps a column that moves on every write.** ERPs
  usually have one: `updated_at`, `updated`, `modified_at`, `last_modified`.
- **`full`, for a table that only grows** — an append-only ledger, a reference table nobody
  edits in place.
- **`full` on a table that *is* edited in place will not see the edits.** Expose a timestamp
  column on the source, or re-read the table with
  [`lm endpoint reset-cursor`](#restarting-a-table-on-purpose) before each run.

!!! warning "A watermark column that does not move is worse than `full`"
    The hub cannot check that the column moves. Two ways to get it wrong, both silent:

    - **A column the application forgets to touch** on some writes — those changes never
      reach the hub.
    - **A numeric primary key as the watermark.** `lm source jdbc discover` suggests one when
      it finds no timestamp (see `price_list` [below](#discover)) — it behaves like `mode: full`.

## Connection

```yaml
connection:
  url: jdbc:postgresql://db.acme.io:5432/billing   # required
  user: ro_user                                    # required
  password_env: ACME_DB_PASSWORD                   # required — a hub variable, else an lm secret
  dialect: postgres                                # optional, inferred from the url
  zone: Europe/Paris                               # optional, UTC when absent
```

- **Dialect**: PostgreSQL (`postgres`, alias `postgresql`) ships with every edition; Oracle
  (`oracle`) only with lumnik Pro — see [Oracle](#oracle). A declared `dialect` that is not
  installed, or does not accept the URL, fails validation. An absent one is inferred when exactly
  one installed dialect accepts the URL.
- **Zone**: the time zone the database writes its dates-and-times in when it stores no zone
  with them — see [A time without a time zone](#a-time-without-a-time-zone). An unknown zone
  fails validation. A connector made by [`lm source jdbc add`](#guided-flow-lm-source-jdbc) has
  no zone option: it reads such times as UTC.
- **Grants**: `USAGE` on the schema and `SELECT` on the tables. Nothing more.
- **Reachability is the hub's, not yours.** The URL is resolved where the hub runs: a
  database on your Mac is `host.docker.internal` for a hub in Docker, not `localhost`.

### Where the password comes from

Resolved at run time, first match wins:

| | Source | Notes |
|---|---|---|
| 1 | **Attached credential** | Stored encrypted, created by [`lm source jdbc add`](#guided-flow-lm-source-jdbc) as `<source-name>--credential`. `lm apply` never sets one. |
| 2 | `password` in the manifest | Plain text, dev and test only. It **wins over** `password_env`; `lm validate` warns. |
| 3 | `password_env` | The name of a variable in the **hub's** environment, else of an [`lm secret`](../cli/lm-reference.md#data-runs-answers) entry. Neither at run time fails the run, naming it. |

- `password_env` is **required by validation** even when `password` is present.
- Set it with `lm secret set ACME_DB_PASSWORD`: no hub restart. A variable of the same name in
  the hub's environment wins.
- The attached credential is encrypted under `LUMNIK_CRYPTO_SECRET_KEY` — see
  [Security → Secrets at rest](../security/rls.md#secrets-at-rest-the-master-key).

The quickstart connector re-applied with `password_env: ACME_DB_PASSWORD`, a name neither the
hub's environment nor `lm secret` holds:

```
$ lm connector run demo-invoices
run scheduled: 3570bbf6-29f5-4b1c-99a9-5e6e0aec810e
$ lm run get 3570bbf6
ID        CONNECTOR      ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
3570bbf6  demo-invoices  invoices  Failed  0   0    0        0             2026-10-19 07:24  2026-10-19 07:24
✖ run failed: password_env 'ACME_DB_PASSWORD' is neither in the server environment nor in lm secret
```

## Source — one entry per table

```yaml
--8<-- "docs/connectors/jdbc/40-two-tables.yaml"
```

Each entry in `source.tables[]` is an **endpoint** of the connector:

- the endpoint name is the table name;
- one `lm connector run` runs all of them;
- **each endpoint writes its own row in the run ledger** — two rows per run here.

```
$ lm run list --connector demo-erp
ID        CONNECTOR  ENDPOINT    STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
122112a8  demo-erp   price_list  Completed  0   0    0        0             2026-10-19 01:41  2026-10-19 01:41
d2eb4159  demo-erp   warehouses  Completed  0   0    0        0             2026-10-19 01:41  2026-10-19 01:41
4013a639  demo-erp   price_list  Completed  3   3    0        0             2026-10-19 01:41  2026-10-19 01:41
664b8c9b  demo-erp   warehouses  Completed  2   2    0        0             2026-10-19 01:41  2026-10-19 01:41
```

The first run read `warehouses` (2 rows) and `price_list` (3); the second found nothing new.
`ENDPOINT` is also the second half of the endpoint commands' address:
`lm endpoint reset-cursor demo-erp/warehouses`.

### Per-table keys

| Key | Required | What it does |
|---|---|---|
| `name` | yes | The source table. Also names the hub table, `connector.t_<name>`, unless `target` does |
| `target` | no | Names the hub table `connector.t_<target>` instead |
| `schema` | yes (here or in `defaults`) | The source schema |
| `mode` | yes | `full` or `watermark` |
| `watermark_column` | when `mode: watermark` | The column that moves on every change |
| `pk_columns` | yes | The paging key and the hub table's key: one row per key. It must be unique **together** |
| `indexes` | no | Postgres indexes to create on the hub table |
| `rename` | no | Source column name → hub column name |

- **`chunk_size` is refused**; any other key gets a warning, `unknown key — ignored by the
  runtime` — see [Validation rules](#validation-rules).

!!! tip "`pk_columns` is the paging key, read whole"
    The reader orders by every entry and resumes on all of them at once. A composite key pages
    exactly like a single-column one. `demo.order_lines` is 700 lines over 100 orders, seven each:

    ```yaml
    --8<-- "docs/connectors/jdbc/60-composite-key.yaml"
    ```

    The full read, then a second run that finds nothing:

    ```
    $ lm run list --connector demo-lines --limit 2
    ID        CONNECTOR   ENDPOINT     STATUS     IN   OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
    a04d56c9  demo-lines  order_lines  Completed  0    0    0        0             2026-10-19 01:42  2026-10-19 01:42
    17ce005f  demo-lines  order_lines  Completed  700  700  0        0             2026-10-19 01:41  2026-10-19 01:41
    note: result hit the limit of 2 — there may be more
    ```

    - **The order of the entries is the paging order.** Reversing it restarts the table (see
      [What restarts a table](#resume-restart-reset)).
    - **The key must be unique together.** `lm source jdbc add` writes the discovered primary
      key; a hand-written manifest may not. A run that meets two rows under one key fails, and
      that read writes nothing. `demo.order_lines` declared on `order_id` alone:

    ```
    $ lm run get 0fdd0a87
    ID        CONNECTOR   ENDPOINT     STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
    0fdd0a87  demo-lines  order_lines  Failed  0   0    0        0             2026-10-19 01:42  2026-10-19 01:42
    ✖ run failed: pk_columns (order_id) does not identify one row of demo.order_lines: two rows share the key where a read ends, and paging past it would skip one — declare the columns of the source table's primary key
    ```

### `defaults`

`defaults:` holds fallbacks for every table; a table that declares the same key wins.

- The merge is **per key, not deep**: a table's `rename:` replaces the one in `defaults:`.
- `mode`, `watermark_column` and `schema` are the usual ones — see
  [the watermark manifest](#the-watermark-manifest).

There is no `chunk_size` setting: `lm validate` refuses one.

## The hub table

Rows land in the `connector` schema as `connector.t_<table-name>`, one table per source
table, created on the first run from the columns the source returned.

!!! warning "The hub table is named after the source table, not the connector"
    Two connectors of a tenant reading a table of the same name land in the same
    `connector.t_<name>`. The second one's run is refused; give it its own hub table with
    `target:` — see
    [Two connectors reading a table of the same name](#two-connectors-reading-a-table-of-the-same-name).

### Column types

The hub types each column from the source's declared type:

| Source type | Hub column |
|---|---|
| `smallint`, `integer`, `bigint` | `BIGINT` |
| `real`, `double precision`, `numeric`, `decimal` | `NUMERIC(18,4)` |
| `timestamp` (no time zone) | `TIMESTAMPTZ`, the time read in the connection's `zone`, UTC without one ([below](#a-time-without-a-time-zone)) |
| `timestamptz` | `TIMESTAMPTZ`, the same instant |
| `date` | `DATE` |
| `boolean`, `bit` | `BOOLEAN` |
| everything else | `TEXT` |

- **Oracle has its own list** and refuses a column outside it — see [Oracle](#oracle).
- **`NUMERIC(18,4)` holds at most 14 digits before the point.** A wider source column — a
  19-digit id declared `numeric(20,0)` — fails the run with `numeric field overflow`. Expose it
  as text through a view: `SELECT event_id::text AS event_id, ...`. A `bigint` id is not affected.
- <a id="a-time-without-a-time-zone"></a>**A time without a time zone is read in the
  connection's `zone`**, UTC when it declares none, wherever the hub runs. A `timestamptz` keeps
  its instant. `ordered_at` is a `timestamp` at the source, `paid_at` a `timestamptz` holding the
  same instants — read on a hub running in Paris, no `zone` declared:

    ```
     order_id |       ordered_at       |        paid_at
    ----------+------------------------+------------------------
            1 | 2026-08-02 23:30:00+00 | 2026-08-02 23:30:00+00
            2 | 2026-01-15 23:30:00+00 | 2026-01-15 23:30:00+00
    ```

    An ERP that writes Paris time declares it: `zone: Europe/Paris` under `connection`. Each time
    is then read at its own offset, UTC+2 in summer, UTC+1 in winter. A `date` keeps its day:

    ```
     order_id |       ordered_at       |   due_on
    ----------+------------------------+------------
            1 | 2026-08-02 21:30:00+00 | 2026-08-02
            2 | 2026-01-15 22:30:00+00 | 2026-01-15
    ```

    The zone applies to the rows a run reads. On a table already written, re-apply the
    connector, then read the table again:

    ```
    $ lm apply -f erp.yaml
    Applied connector "erp" — 1 endpoint
    $ lm endpoint reset-cursor erp/orders
    $ lm connector run erp
    run scheduled: a97e7633-c3d3-43f6-8aac-a3dd0711aed7
    $ lm run list
    ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
    a97e7633  erp        orders    Completed  2   2    0        0             2026-10-20 15:33  2026-10-20 15:33
    ```

    Each row it reads again is rewritten in place, at the right instant. Without
    `reset-cursor`, the run reads only what is new and the old rows keep their time. An unknown
    zone is refused before anything is written:

    ```
    $ lm apply -f erp.yaml
    ✗ erp.yaml — 1 error(s), 0 warning(s)

      ERROR    spec.connection.zone
              'zone' is not a time zone: "Europe/Pariss" (for example Europe/Paris or UTC)
    ```

- **A table the hub already created keeps the types it was created with.** To retype it, delete
  the connector (which drops its tables) and re-ingest, or `ALTER` the columns yourself.

### Seven reserved column names

`id`, `_row_hash`, `_ingested_at`, `_source`, `_run_id`, `_mapping_version`, `tenant_id` —
the hub adds these to every table it creates. A source column of one of those names fails the run:

```
$ lm run get ded8b2ac
ID        CONNECTOR        ENDPOINT    STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
ded8b2ac  demo-price-list  price_list  Failed  0   0    0        0             2026-10-19 01:41  2026-10-19 01:41
✖ run failed: source column 'id' collides with a hub system column (_ingested_at, _mapping_version, _row_hash, _run_id, _source, id, tenant_id); it must reach the hub under another name — jdbc: source.tables[].rename; csv: a map-columns row hook under spec.mapping; kafka: no rename exists, the field must be renamed in the topic payload
```

`rename:` is the fix:

```yaml
--8<-- "docs/connectors/jdbc/30-rename-reserved-column.yaml"
```

```
$ lm connector run demo-price-list
run scheduled: ee29d37e-843e-4cd3-8972-9422ea54b36e
$ lm run list --connector demo-price-list
ID        CONNECTOR        ENDPOINT    STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
ee29d37e  demo-price-list  price_list  Completed  3   3    0        0             2026-10-19 01:41  2026-10-19 01:41
ded8b2ac  demo-price-list  price_list  Failed     0   0    0        0             2026-10-19 01:41  2026-10-19 01:41
```

```
 price_list_id |   sku    |  price
---------------+----------+----------
             1 | PUMP-075 | 349.0000
             2 | HOSE-32M |  59.9000
             3 | FILT-A1  | 129.5000
```

- The rename applies **in the hub table only**. Nothing is renamed on the source.
- `lm validate` refuses a rename *into* one of the seven, and two source columns renamed onto the
  same hub column.

### One row per key — a changed row replaces its version

The hub table holds one row per `pk_columns` value:

- **A row read again unchanged is left as it is.**
- **A row changed at the source replaces its previous version.**

After the five runs above, `t_invoices` holds five rows for five invoices:

```
 invoice_id | status |       updated_at
------------+--------+------------------------
       1001 | paid   | 2026-09-01 09:15:00+00
       1002 | paid   | 2026-09-08 07:45:00+00
       1003 | paid   | 2026-09-08 09:10:00+00
       1004 | draft  | 2026-09-02 16:20:00+00
       1005 | open   | 2026-09-08 07:30:00+00
```

The hub table is the source's current state; it keeps no history.

!!! note "`OUT` counts rows delivered, not rows written"
    Rows skipped because they did not change are counted in `OUT` too, which is why a re-read
    that changes nothing still reports a non-zero `OUT`.

### Deletes are never propagated

A row deleted at the source stays in the hub table, in both modes. To resync from nothing,
delete the connector, then apply and run it again.

### Emptying one endpoint on its own

A `jdbc-generic` endpoint cannot be purged on its own. `lm endpoint purge` asks the hub first, so
the refusal comes with no prompt:

```
$ lm endpoint purge demo-invoices/invoices
Error: API error 400: This endpoint writes to a connector table whose rows are not tracked per endpoint, so it cannot be purged on its own. Deleting the connector removes its data, along with its run history.
```

Delete the connector instead. `--dry-run` states what goes:

```
$ lm connector delete demo-invoices --dry-run
Would delete connector demo-invoices (jdbc-generic) — 5 rows of connector.t_invoices, its run history and RAG chunks (dry run, nothing deleted)
$ lm connector delete demo-invoices --force
deleted: demo-invoices
```

- Without `--force`, `lm` asks you to type `delete` first.
- If an entity (here `invoice`) is built on the table, it blocks the delete, before any question:

    ```
    $ lm connector delete demo-invoices --dry-run
    Error: API error 409: table connector.t_invoices feeds entity invoice — remove the entity first (lm entity delete invoice)
    ```

- The connectors `lm source jdbc add` made share the source's stored credential
  (`<source>--credential`). It goes with the last of them, and that one's statement names it:
  `…, credential demo-src--credential, its run history and RAG chunks`.
- Re-apply the manifest and run it to start clean.

The same holds for `csv-file` and `kafka` endpoints.

### Two connectors reading a table of the same name

Two sites, one ERP: `erp-north` and `erp-south` differ only by `defaults.schema`, and both read
`customers`. `erp-north` ran first and writes `connector.t_customers`; the run of `erp-south` is
refused, naming the holder:

```
$ lm run list --connector erp-south
ID        CONNECTOR  ENDPOINT   STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
501528ee  erp-south  customers  Failed  0   0    0        0             2026-10-19 01:42  2026-10-19 01:42
$ lm run get 501528ee
ID        CONNECTOR  ENDPOINT   STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
501528ee  erp-south  customers  Failed  0   0    0        0             2026-10-19 01:42  2026-10-19 01:42
✖ run failed: connector.t_customers is already written by connector 'erp-north'; a second connector cannot write it — give this one its own hub table: jdbc: source.tables[].target; csv: the table is named after the connector, rename it; kafka: the endpoint's name
```

Name the second one's hub table with `target:`, apply, run:

```yaml
  source:
    tables:
      - name: customers
        target: customers_south
        mode: full
        pk_columns: [customer_id]
```

```
$ lm apply -f erp-south.yaml
Applied connector "erp-south" — 1 endpoint
$ lm connector run erp-south
run scheduled: bf0b74c8-6132-4df7-b789-3ac960d069ee
$ lm run list --connector erp-south
ID        CONNECTOR  ENDPOINT   STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
bf0b74c8  erp-south  customers  Completed  3   3    0        0             2026-10-19 01:42  2026-10-19 01:42
501528ee  erp-south  customers  Failed     0   0    0        0             2026-10-19 01:42  2026-10-19 01:42
$ lm data tables | grep -E '^(MAPPING|jdbc:)'
MAPPING                               SCHEMA     TABLE                      ROWS
jdbc:customers                        connector  t_customers                2
jdbc:customers_south                  connector  t_customers_south          3
```

- **The endpoint keeps the source table's name** — `customers` in `lm run list` and in
  `lm endpoint reset-cursor erp-south/customers`; only the hub table changes.
- **Another tenant may read a `customers` table too.** Neither sees the other's rows.
- **The rule holds across connector types**: a `csv-file` connector named `customers` writes
  `connector.t_customers` too.

### Indexes on the hub table

Index a column your views, exports or entities filter on. `indexes:` is declared **per table**:

```yaml
indexes:
  - { columns: [customer], unique: false }     # unique defaults to false
```

Each entry is `{ columns: [non-empty strings], unique?: bool }`; a malformed entry fails validation.

## Resume, restart, reset

- **An interrupted run continues at the next run**, with nothing doubled and nothing skipped.
- **A row inserted while a run reads belongs to the next run.**
- **A row whose watermark column is empty** is read once, by a first run or after
  `reset-cursor`, and never refreshed. Give the column a value, or read the table in `mode: full`.
- **Changing `mode`, `watermark_column` or `pk_columns` reads the table again once.** `IN` shows
  the full count; the table does not grow (run 772a43f1 above).

### Restarting a table on purpose

`lm endpoint reset-cursor <connector>/<endpoint>` drops the stored cursor; for jdbc the endpoint is
the table name. It prints nothing; the next run's `IN` is the proof:

```
$ lm endpoint reset-cursor demo-invoices/invoices
$ lm connector run demo-invoices
run scheduled: 22607df2-5515-425d-af55-46b9b6c26963
$ lm run list --connector demo-invoices --limit 2
ID        CONNECTOR      ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
22607df2  demo-invoices  invoices  Completed  5   5    0        0             2026-10-19 01:40  2026-10-19 01:40
87b4931c  demo-invoices  invoices  Completed  0   0    0        0             2026-10-19 01:40  2026-10-19 01:40
note: result hit the limit of 2 — there may be more
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
- **A manual `lm connector run` ignores the window.**
- **Re-applying without the key lifts it.** An absent `schedule` unschedules; an absent
  `window` removes the fence.

On a production database, a window is usually the DBA's condition for granting the `SELECT`
account — see [Sizing & load tests](../operations/sizing.md).

## Guided flow — `lm source jdbc`

Four commands, no YAML: `test` and `discover` explore, `add` creates the connectors, `list-tables`
lists them. They need the `lm_integrator` and `lm_admin` roles. The password is **never a flag**:

| You are | What happens |
|---|---|
| at a keyboard | `Password for <user>: ` is prompted, and what you type is not shown |
| piping, with `--password-stdin` | the first line of stdin is the password |
| piping, without the flag | same, plus a warning on stderr naming the flag |

Use it to explore a base you do not know, and to stand up a table quickly. Write a manifest
for `rename`, `indexes`, a view, a composite or undiscoverable key, or config kept in version
control.

### `test`

```
$ printf '%s\n' "$DB_PASSWORD" | lm source jdbc test --password-stdin --url jdbc:postgresql://postgres:5432/lumnik --user lumnik_readonly
OK       : true
Dialect  : postgres
Version  : 16.15 (Debian 16.15-1.pgdg12+2)
Latency  : 1 ms
Schemas  : connector, core, demo, ext, identity, information_schema, pg_catalog, platform, public, rag
```

`Schemas` lists every schema of the database, granted or not.

### `discover`

```
$ printf '%s\n' "$DB_PASSWORD" | lm source jdbc discover --password-stdin --url jdbc:postgresql://postgres:5432/lumnik --user lumnik_readonly --schema demo
SCHEMA     NAME                            ROWS  COLS PK                   WATERMARK       UNSUPPORTED
demo       audit_log                          1     3                      -               -
demo       invoices                           4     5 invoice_id           updated_at      -
demo       order_lines                      700     3 order_id,line_no     -               -
demo       price_list                         3     3 id                   id              -
demo       warehouses                         2     4 warehouse_id         updated_at      -
```

| Column / flag | Meaning |
|---|---|
| `--schema` | defaults to `public`; an ERP in its own schema names it here |
| `ROWS` | the planner's estimate, `-1` when the table was never analyzed |
| `PK` | empty: no primary key — that table needs a manifest or a view |
| `WATERMARK` | a **suggestion**. Check that the column moves on every write: a numeric key, as `price_list` suggests `id`, is not one ([above](#which-one-to-declare)) |
| `UNSUPPORTED` | the columns the dialect cannot read, as `COLUMN TYPE` (`-` when none). `add` refuses such a table by name; the interactive picker marks it `[!]`. Always `-` on PostgreSQL |
| `--include-views` | adds views to the listing |
| `--skip-pattern` | replaces the dialect's default exclusions (PostgreSQL: `pg_*,information_schema*,t_*`) |
| `-o json` / `-o yaml` | the whole descriptor, `unsupportedColumns` included |

### `add`

```
$ printf '%s\n' "$DB_PASSWORD" | lm source jdbc add --password-stdin demo-src --url jdbc:postgresql://postgres:5432/lumnik --user lumnik_readonly --schema demo --tables warehouses
Created 1 connectors:
  • demo-src--warehouses
$ lm source jdbc list-tables demo-src
TABLE                          CONNECTOR                                STATUS
warehouses                     demo-src--warehouses                     active
$ lm connector run demo-src--warehouses
run scheduled: b2e594a3-bd79-4944-b50b-1642b5edc066
$ lm run list --connector demo-src--warehouses
ID        CONNECTOR             ENDPOINT    STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
b2e594a3  demo-src--warehouses  warehouses  Completed  2   2    0        0             2026-10-19 01:43  2026-10-19 01:43
```

What it creates, per requested table:

- one connector named `<source-name>--<table>`;
- one encrypted credential per source, `<source-name>--credential` — the password never lands in
  a file;
- `mode: watermark` when a watermark column was found, `mode: full` otherwise. There is no `--mode`.

Flags:

- `--interactive` opens a TUI table picker instead of `--tables`, pre-filled with the discovered
  suggestions.
- `--schedule-cron` sets the schedule, in the 5-field form `schedule:` takes, e.g. `*/30 * * * *`.
  It is checked like the manifest's: anything else is refused with `invalid cron expression: …`
  and nothing is created.

What it refuses, by name and reason, before anything is created:

```
$ printf '%s\n' "$DB_PASSWORD" | lm source jdbc add --password-stdin demo-src ... --tables audit_log
Error: API error 400: table demo.audit_log has no primary key — jdbc-generic pages by primary key; declare pk_columns in a manifest instead
$ printf '%s\n' "$DB_PASSWORD" | lm source jdbc add --password-stdin demo-src ... --tables nope
Error: API error 400: table demo.nope not found among the base tables of that schema — check the schema and the connection user's SELECT grant; a view is declared in a manifest, not through the guided add
```

- a table it cannot find in that schema, and a **view**;
- a table without a primary key;
- a table whose name is not a plain identifier;

A table whose connector already exists is skipped, not re-created:

```
$ printf '%s\n' "$DB_PASSWORD" | lm source jdbc add --password-stdin demo-src ... --tables warehouses
Created 0 connectors:
Skipped 1 (already exist):
  • demo-src--warehouses
```

## Oracle

**lumnik Pro.** Oracle 12c and later, with its own names and types:
[JDBC — Oracle](jdbc/oracle.md).

## Reading a view

A view is how a table reaches the hub when the table itself cannot: no primary key, a
reserved column name, a value too wide for the hub's numeric type, or more rows than the
team needs.

```yaml
--8<-- "docs/connectors/jdbc/50-view.yaml"
```

```
$ lm run list --connector demo-open-invoices
ID        CONNECTOR           ENDPOINT         STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
22356ffd  demo-open-invoices  v_open_invoices  Completed  2   2    0        0             2026-10-19 01:42  2026-10-19 01:42
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

Fix errors above before running 'lm apply -f bad-chunk.yaml'.
Error: validation failed: 1 error(s)
```

| Rule | Severity | Path |
|---|---|---|
| `connection.url` matches `jdbc:<dialect>:...` | ERROR | `spec.connection.url` |
| Dialect is installed and accepts the URL | ERROR | `spec.connection.dialect` (declared) or `spec.connection.url` (inferred) |
| A declared `dialect` matches the URL scheme | ERROR | `spec.connection.dialect` |
| `connection.user` and `connection.password_env` present | ERROR | `spec.connection.*` |
| A plain-text `connection.password` | WARNING | `spec.connection.password` |
| A declared `connection.zone` is a time zone | ERROR | `spec.connection.zone` |
| `source.tables[]` non-empty | ERROR | `spec.source.tables` |
| `mode` present and in {watermark, full} | ERROR | `spec.source.tables[i].mode` |
| `schema` present, on the table or in `defaults` | ERROR | `spec.source.tables[i].schema` |
| `mode: watermark` declares `watermark_column` | ERROR | `spec.source.tables[i].watermark_column` |
| `pk_columns` is a non-empty list | ERROR | `spec.source.tables[i].pk_columns` |
| `chunk_size` is refused — chunking is fixed at 500 | ERROR | `spec.source.tables[i].chunk_size` |
| No unknown per-table key | WARNING | `spec.source.tables[i].<key>` |
| `indexes` entries are `{ columns: [...], unique?: bool }` | ERROR | `spec.source.tables[i].indexes[j]` |
| No duplicate table names | ERROR | `spec.source.tables[i].name` |
| `target` is a plain identifier | ERROR | `spec.source.tables[i].target` |
| No two tables of the connector land in one hub table | ERROR | `spec.source.tables[i].name` |
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
| `password_env '...' is neither in the server environment nor in lm secret` | neither the hub's environment nor the tenant's `lm secret` holds that name | `lm secret set <NAME>`, export it where the hub runs, or attach a credential through the guided flow |
| `lm validate`: `required field missing` at `spec.source.tables[i].schema` | no `schema` on the table nor in `defaults` | add `schema:` — see [Per-table keys](#per-table-keys) |
| `source column 'id' collides with a hub system column` | the source has a column named like one of the seven | add `rename: { id: source_id }` on that table |
| run `Failed`: `connector.t_… is already written by connector '…'` | another connector of the tenant already writes that hub table | add `target: <name>` on that table — see [Two connectors reading a table of the same name](#two-connectors-reading-a-table-of-the-same-name) |
| `numeric field overflow` | a numeric value wider than 14 digits before the point | expose the column as text through a view |
| the run reads 0 rows and nothing is wrong | the cursor is already past everything | expected — change a row at the source, or `lm endpoint reset-cursor` |
| updated rows never arrive | `mode: full`, or a watermark column the application does not move | switch to `watermark` on a column that moves |
| the hub log says the cursor was persisted by another configuration | `mode`, `watermark_column` or `pk_columns` changed | expected once; the table restarts and unchanged rows are skipped |
| a deleted source row is still in the hub table | deletes are never propagated | delete the connector, then re-apply and run |
| run `Failed`: `could not reach public.workorder (SQLSTATE 08001): host not resolved (UnknownHostException)` | the hub cannot open a connection to the source: its host is not known any more (`host not resolved`), nothing answers on the port (`connection failed`), or the network cuts the attempt. The run ends there: at its start nothing is written, after a chunk what was written stays | check the source from where the hub runs; for a source retired on purpose, `lm connector pause NAME`: what it wrote stays readable |
| a table is in `discover` but `add` refuses it | no primary key, or it is a view | declare `pk_columns` in a manifest |
| `discover` lists nothing in a schema `test` shows | the wrong `--schema` (default `public`), or no `SELECT` grant on its tables | pass `--schema <name>`; grant `USAGE` on the schema and `SELECT` on its tables |
| run `Failed`: `pk_columns (…) does not identify one row of …` | the declared `pk_columns` is not unique | declare the whole primary key — see [Per-table keys](#per-table-keys) |

## See also

- [Connectors overview](index.md)
- [CSV & files](csv.md) — the same hub table, fed by files
- [Scopes](../rag/scopes.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
