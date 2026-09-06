# CSV & files (SFTP/S3)

> Ingest CSV files — dropped locally, on an SFTP server, or in an S3 bucket — into a hub
> table on a schedule, with per-row error isolation.

| | |
|---|---|
| **Type** | `csv-file` |
| **Transports** | local, sftp, s3 |
| **Target** | `connector.t_<name>` |
| **Schedulable** | cron |
| **Edition** | Open |

## Quickstart — one local file, three commands and a look at the ledger

```yaml
--8<-- "docs/connectors/csv/10-local-simple.yaml"
```

From the repository root. The manifest's `path:` is read by the **hub**, so the sample file
must be where the hub can see it — on the self-host stack, copy it into the container first
(`docker cp docs/connectors/csv/data/customers.csv lumnik-hub-1:/tmp/exports/` and point
`path:` there; in dev mode the repo-relative path works as is):

```
$ lm validate -f docs/connectors/csv/10-local-simple.yaml
✓ docs/connectors/csv/10-local-simple.yaml — valid
$ lm apply -f docs/connectors/csv/10-local-simple.yaml
{"name":"csv-local-simple","connectorId":"8e01c813-7f57-4222-8d7b-e637dfcc94c3","endpointsApplied":1}
$ lm connector run csv-local-simple
run scheduled: e9cd433e-3a57-43ff-9ea7-737f2718be0a
```

"Scheduled" is not "done" — the run ledger is where the result lands, a second later:

```
$ lm run get e9cd433e
ID        CONNECTOR         STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
e9cd433e  csv-local-simple  Completed  2   2    0        0             2026-09-06 22:11  2026-09-06 22:11
```

`STARTED` / `ENDED` are printed in **your terminal's time zone** (these screens: Paris, UTC+2);
the hub stores UTC, and `schedule` is evaluated in UTC.

What landed in `connector.t_csv_local_simple` — the header row became the columns:

```
 row_id |    email    | signup_date
--------+-------------+-------------
 1      | alice@x.com | 02/06/2026
 2      | bob@x.com   | 15/01/2026
```

Run it again on the unchanged file, then once more after a third line is appended:

```
$ lm run list --connector csv-local-simple
ID        CONNECTOR         STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
f550a8ef  csv-local-simple  Completed  3   3    0        0             2026-09-06 22:12  2026-09-06 22:12
b469deba  csv-local-simple  Completed  2   2    0        0             2026-09-06 22:11  2026-09-06 22:11
e9cd433e  csv-local-simple  Completed  2   2    0        0             2026-09-06 22:11  2026-09-06 22:11
```

- **A small file is re-read every run** — `IN` counts what was read, not what was new.
- **The table deduplicates** by `_row_hash`: after three runs it holds 3 rows, not 7. The
  appended line is the only new one.
- Where the run counters diverge — `SKIPPED`, `DEAD-LETTERS` — is
  [below](#malformed-rows-the-per-row-dlq).

## The three transports

The manifest's `transport:` block picks one. What differs is **where the files are, how the
cursor remembers them, and what may happen to a file once its rows are in**:

| | `local` | `sftp` | `s3` |
|---|---|---|---|
| files are on | the **hub's** filesystem | a remote server | a bucket |
| selected by | path or glob | path or glob | `prefix` + `suffix` |
| cursor | row position in the lexically sorted glob result | file modification time | file modification time |
| `after_process` | `none` only | all four | all four |
| credentials | — | `key_env` or `password_env` | `access_key_env` / `secret_key_env` |

### Local — the hub's disk, not yours

```yaml
transport:
  kind: local
  path: /imports/*.csv        # glob OK
```

- **The path is the hub's filesystem.** The shipped self-host stack mounts nothing: a local
  path needs a volume, or use `sftp`/`s3`, or upload one file with
  [`lm csv ingest`](#connector-runs-vs-one-shot-lm-csv-ingest).
- **Glob rules**: `*` and `?` only, and only in the **final filename segment** — the
  directory part must be literal (`/imports/*/x.csv` never matches; there is no `**`).
- **No name is excluded implicitly** — a `*.csv` glob is how you keep `.tmp` files out.
- **A path that matches nothing is not an error**: the run completes with `IN 0`. A file that
  vanished from the hub's disk looks exactly like an empty folder — check the path first.
- Older pre-manifest configs with a bare top-level `path:` still work; manifests always
  declare `transport:`.

**How a drop folder behaves, run by run.** A 1 200-row export dropped as
`2026-09-01-orders.csv`, then two more files:

```
$ lm run list --connector orders-drop
ID        CONNECTOR    STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
c593f035  orders-drop  Completed  210   210   0        0             2026-09-06 22:14  2026-09-06 22:14
972ef27a  orders-drop  Completed  210   210   0        0             2026-09-06 22:13  2026-09-06 22:13
a13c89f4  orders-drop  Completed  200   200   0        0             2026-09-06 22:13  2026-09-06 22:13
c563f800  orders-drop  Completed  1200  1200  0        0             2026-09-06 22:13  2026-09-06 22:13
```

| run | the folder holds | `IN` | why |
|---|---|---|---|
| 1 | `2026-09-01` (1 200 rows) | 1 200 | first read |
| 2 | the same file | 200 | the cursor is saved after each chunk **that another chunk follows** — here at row 1 000; the run's last chunk saves nothing, so the tail is re-read, and deduplicated |
| 3 | plus `2026-09-02` (10 rows) | 210 | the tail, then the new file — it sorts **after** the cursor's file |
| 4 | plus `2026-08-31` (5 rows) | 210 | the new file sorts **before** the cursor's file — **never read** |

!!! warning "Drop-directory contract: names must grow lexically"
    - The cursor is a position in the **lexically sorted** glob result: the file it is on,
      and how many rows of it are consumed. The next run resumes there and continues with the
      files that sort after it.
    - A file dropped later with a lexically **earlier** name sorts before the cursor and is
      treated as already ingested — run 4 above. Name incoming files monotonically
      (date-prefixed: `2026-07-25-orders.csv`).
    - The cursor is saved after every 500-row chunk that another chunk follows; the run's
      **last chunk saves nothing**. So a file that fits in one chunk leaves no cursor (the
      quickstart's 2-row file) and a longer file's tail — whatever came after the last saved
      position — is re-read each run. Both are harmless — the table deduplicates — but `IN`
      is not "new rows".
    - If your producer cannot guarantee monotonic names, use an sftp/s3 transport: their
      cursor tracks files by modification time instead.

### SFTP — a remote folder, a secret, a fingerprint

```yaml
transport:
  kind: sftp
  host: sftp.example.com
  port: 22                    # optional, default 22
  user: lumnik
  path: /erp-export/*.csv     # glob OK
  password_env: SFTP_ERP_PASSWORD           # OR key_env (the PEM private key) — one is required
  host_key_fingerprint: "SHA256:M0Is0aG8ubOr0cLpQs6QcDwVSxMkvmlfuW4K9Q6CJuc"
  # strict_host_key: true     # the default — refuses unless the fingerprint above matches
# spec level, next to transport:
after_process: archive
archive_path: /archive/       # default /archive/; required when after_process: move
```

**Credentials are never inline.** `key_env` / `password_env` name a secret the platform holds —
[`lm secret`](../admin-console-guide.md) first, an OS env var of that name as fallback:

```
$ lm secret set SFTP_ERP_PASSWORD -      # paste the value on stdin, Ctrl+D
$ lm secret list
NAME                           ROTATED_AT                     DESCRIPTION
ACME_SFTP_KEY                  -                              SFTP key - erp-export test VPS
AWS_ACCESS_KEY_ID              -                              S3 test key - lumnik-exports
AWS_SECRET_ACCESS_KEY          -                              S3 test secret - lumnik-exports
SFTP_ERP_PASSWORD              -
```

A manifest with neither credential is refused before anything is applied:

```
$ lm validate -f sftp-nocred.yaml
✗ sftp-nocred.yaml — 1 error(s), 0 warning(s)

  ERROR    spec.transport
          SFTP transport requires key_env or password_env
          → Use `lm secret set <NAME> ...` then reference via key_env: <NAME>
```

**The host key is verified.** `strict_host_key` defaults to `true`, and then
`host_key_fingerprint` must match what the server presents. The refusal is a run failure that
teaches — here with a wrong fingerprint declared (host renamed):

```
$ lm run get b31b1efe
ID        CONNECTOR    STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
b31b1efe  sftp-badkey  Failed  0   0    0        0             2026-09-06 22:13  2026-09-06 22:13
✖ run failed: SFTP host key refused for sftp.example.com:22 — the server presented SHA256:M0Is0aG8ubOr0cLpQs6QcDwVSxMkvmlfuW4K9Q6CJuc, and it does not match the declared SHA256:0000000000000000000000000000000000000000000. Confirm that fingerprint out of band (ssh-keyscan -p 22 sftp.example.com | ssh-keygen -lf -), then set host_key_fingerprint: "SHA256:M0Is0aG8ubOr0cLpQs6QcDwVSxMkvmlfuW4K9Q6CJuc" in the transport block. Setting strict_host_key: false connects without checking at all — which accepts an impostor as readily as the real server.
```

- The message carries the fingerprint the server presented. **Confirm it out of band**
  (the `ssh-keyscan | ssh-keygen -lf -` line, run from a machine you trust), then paste it.
- `strict_host_key: false` skips the check entirely — an impostor between the hub and your
  server would then feed rows into ingestion and collect the password. Keep it for a
  throwaway container whose key changes at every restart, never for a real server.
- The docker-compose demo server is exactly that case: [`30-sftp-key.yaml`](#ingest-an-sftp-folder-nightly)
  sets `strict_host_key: false` and says why.

!!! warning "Existing connectors stopped when verification became real"
    `strict_host_key` used to change nothing: set to `false` it installed an accept-anything
    verifier; set to `true` — the default — it installed nothing, and the SSH library's own
    default accepted any key. An SFTP connector that ran happily on the default **stops
    connecting until you declare the fingerprint** — which is the point: it was never
    verified, and the green said otherwise.

A wrong password, or a user without read rights on the folder, fails the run the same way:

```
$ lm run get 22957eeb
ID        CONNECTOR                    STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
22957eeb  sftp-c-bpartner-credentials  Failed  0   0    0        0             2026-09-04 14:23  2026-09-04 14:23
✖ run failed: SFTP error (SSH_FX_PERMISSION_DENIED): Permission denied
```

**The cursor tracks files, not rows.** Each run lists the files matching `path`, keeps those
modified at or after the watermark, skips the ones the last batch already ingested (same
path and size), and reads them oldest first. A file is read once; a run that finds nothing
new reads nothing:

```
$ lm run list --connector sftp-c-bpartner-credentials
ID        CONNECTOR                    STATUS     IN     OUT    SKIPPED  DEAD-LETTERS  STARTED           ENDED
a9533cb5  sftp-c-bpartner-credentials  Completed  0      0      0        0             2026-09-06 22:09  2026-09-06 22:09
80032250  sftp-c-bpartner-credentials  Completed  6      5      1        1             2026-09-04 15:27  2026-09-04 15:27
8358fb91  sftp-c-bpartner-credentials  Completed  19009  19009  0        0             2026-09-04 14:40  2026-09-04 14:41
```

Zero-byte files are never listed — never ingested, never archived.

### S3 — a prefix in a bucket (AWS, MinIO, R2, GCS S3-compat)

```yaml
transport:
  kind: s3
  bucket: acme-exports         # required
  prefix: billing/             # optional key prefix filter, default ""
  suffix: .csv                 # optional filename filter, default "" (no filter)
  region: eu-west-3            # optional, default eu-west-3
  endpoint: http://minio:9000  # optional — only for non-AWS S3
  access_key_env: AWS_ACCESS_KEY_ID       # optional, default AWS_ACCESS_KEY_ID
  secret_key_env: AWS_SECRET_ACCESS_KEY   # optional, default AWS_SECRET_ACCESS_KEY
  archive_bucket: acme-archive # optional, for after_process archive|move (default: source bucket)
  archive_prefix: archive/     # optional, default archive/
```

- The listing is `prefix` then `suffix` — `billing/` + `.csv` reads `billing/2026-09-01.csv`
  and ignores `billing/2026-09-01.csv.tmp`.
- Same cursor as SFTP: modification time + the last batch (path, size, and the object's
  ETag), oldest first, zero-byte objects skipped.
- `access_key_env` / `secret_key_env` are secret **names** (`lm secret set AWS_ACCESS_KEY_ID -`),
  resolved like the SFTP ones.

**One export, archived after commit.** The manifest of the [S3 how-to](#ingest-a-headerless-export-under-an-s3-prefix-and-archive-it)
reads `billing/`, then moves each file to `lumnik-archive/archive/` once a later committed
cursor proves its rows landed:

```
$ lm run list --connector s3-c-bpartner
ID        CONNECTOR      STATUS     IN     OUT    SKIPPED  DEAD-LETTERS  STARTED           ENDED
ee860da1  s3-c-bpartner  Completed  10     5      5        5             2026-09-06 16:30  2026-09-06 16:30
dda81fcb  s3-c-bpartner  Completed  0      0      0        0             2026-09-06 16:21  2026-09-06 16:21
7329d11d  s3-c-bpartner  Completed  19009  19009  0        0             2026-09-06 16:03  2026-09-06 16:03
```

- **16:03** — the 19 009-row export, read and archived.
- **16:21** — nothing new under `billing/` (the file is in the archive bucket now): `IN 0`.
- **16:30** — a 10-record file dropped in the meantime, half of it malformed on purpose:
  5 rows in, 5 dead-lettered ([below](#malformed-rows-the-per-row-dlq)).

## Parser

```yaml
parser:
  delimiter: ","                # optional, auto-detected if absent
  encoding: UTF-8               # optional, auto-detected if absent
  has_header: true              # default true
  bom_strip: true               # default true
  null_values: ["", "NULL", "null", "#N/A"]  # default as shown
  columns: [code, name, email]  # REQUIRED when has_header: false; ignored otherwise
```

- **Auto-detection is per file**, when the key is absent. `encoding`: detected, fallback
  UTF-8. `delimiter`: the most frequent of `,` `;` TAB `|` over the first 8 KB, fallback `,`.
  An explicit key overrides only that aspect.
- **Headerless files need names.** With `has_header: false`, `columns` lists them in file
  order (`"\t"` is how a tab delimiter is written in YAML):

    ```yaml
    parser:
      delimiter: "\t"
      has_header: false
      columns: [c_bpartner_id, name, created, isactive]
    ```

    Forget `columns` and the manifest is refused: `columns is required when has_header:
    false — headerless rows need names`.
- **An empty value is a present column, not a missing one.** `1,` against two columns is a row
  with an empty second value — written, and read as `null` because `""` is in `null_values`
  by default (override the list and the column keeps `""`).

!!! note "Chunking is fixed, not a knob"
    Every connector run, local or remote (sftp/s3), commits in fixed **500-row chunks**; a file
    longer than that spans several chunks, and the cursor carries the in-progress file, so a run
    resumes mid-file. Chunk and file boundaries differ by transport: a remote chunk holds rows
    from one file only; a local glob run fills the chunk from the next file when the current one
    runs out. A `chunk_size` key is ignored (the validator warns).

## Malformed rows — the per-row DLQ

A malformed row never fails the file. It is set aside in the dead-letter queue (**DLQ**),
reason `MalformedSource`, and the run continues. A 6-line file with two bad rows:

```csv
"c_bpartner_id","name"
1004194,"DUPONT SARL"
1019809,"MARTIN"
BADROW,"trop de colonnes","erp legacy glitch"
1030001,"LAMBERT & FILS"
BADROW2
1030002,"NAUTILUS SAS"
```

```
$ lm run get c6fc75c9
ID        CONNECTOR                 STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
c6fc75c9  sftp-malformed-after-fix  Completed  6   4    2        2             2026-09-04 15:58  2026-09-04 15:58
⚠ 2 rows quarantined — see: lm dlq list --run c6fc75c9-4fde-4718-ba50-fe12d7d71598
$ lm dlq list --run c6fc75c9-4fde-4718-ba50-fe12d7d71598
ID        CONNECTOR                 RUN       REASON           ATTEMPTS  MESSAGE
f1cd674e  sftp-malformed-after-fix  c6fc75c9  MalformedSource  1         /erp-export/c_bpartner_malformed.csv:6 — Index for header …
5c6bee87  sftp-malformed-after-fix  c6fc75c9  MalformedSource  1         /erp-export/c_bpartner_malformed.csv:4 — Record has 3 valu…
```

**Malformed means the row does not match the column count its header declares — in either
direction.** The dead-letter entry keeps every raw value, with the file and line:

```
$ lm dlq get 5c6bee87
ID:        5c6bee87-beba-4dd7-9140-61de22c0cad4
Connector: sftp-malformed-after-fix (f9152c65-202f-4ad4-b494-37972b22ef57)
Run:       c6fc75c9-4fde-4718-ba50-fe12d7d71598
Reason:    MalformedSource
Attempts:  1
Message:   /erp-export/c_bpartner_malformed.csv:4 — Record has 3 values but only 2 columns are declared
Detail:    {"file":"/erp-export/c_bpartner_malformed.csv","line":4,"message":"Record has 3 values but only 2 columns are declared"}
Payload:   {"raw":["BADROW","trop de colonnes","erp legacy glitch"],"file":"/erp-export/c_bpartner_malformed.csv","line":4}
```

| the row | against 2 declared columns | result |
|---|---|---|
| `BADROW,"trop de colonnes","erp legacy glitch"` | 3 values | DLQ — `Record has 3 values but only 2 columns are declared` |
| `BADROW2` | 1 value | DLQ — `Index for header 'name' is 1 but CSVRecord only has 1 values!` |
| `1,` | 2 values, one empty | **written**, `name` is `null` |
| `1,2,` | 3 values — a trailing delimiter is one column too many | DLQ |

- **The extra value is never dropped to make the row fit** — a value the hub silently
  discards is a loss no count would show you.
- **A trailing delimiter on every line lands the whole file in the DLQ.** Strip it
  producer-side.
- **`SKIPPED` and `DEAD-LETTERS` move together** for malformed rows; the cursor counts consumed
  records, so a resume never re-emits a row already dead-lettered.
- **An unclosed quote swallows the rest of the file** into one record, which is then
  dead-lettered as an `IOException` — the lines after it are never seen as rows.
- Replay or discard entries with [`lm dlq`](../cli/lm-reference.md). Rows a `validate-row`
  hook drops land here too, reason `ValidationFailed`.
- **This is connector-run behaviour.** The one-shot `lm csv ingest` has no DLQ to route a row
  to — its first malformed row aborts the whole ingest ([below](#what-one-shot-actually-does)).

## Scheduling & lifecycle

- **`schedule`** — optional cron, 5 fields, evaluated in **UTC** (`0 6 * * *` = daily at
  06:00 UTC). Validated at apply time. The hub's own scheduler fires it — no OS cron. A
  re-apply without `schedule` unschedules the connector. Add `window: "22:00-06:00"` (UTC, end
  exclusive) to fence scheduled runs into a time window.
- **`after_process`** — required. What happens to a file once its rows are committed:

| | `none` | `archive` | `move` | `delete` |
|---|---|---|---|---|
| does | leaves the file in place | relocates it | relocates it — same action, `archive_path` mandatory | removes it |
| sftp | ✓ | → `archive_path` (default `/archive/`) | → `archive_path` | ✓ |
| s3 | ✓ | → `archive_bucket`/`archive_prefix` (defaults: source bucket, `archive/`) | same | ✓ |
| local | ✓ | **refused** | **refused** | **refused** |

The local reader has no per-file completion tracking — a relocation would never execute,
so the validator refuses it rather than let files silently stay in place:

```
$ lm validate -f local-archive.yaml
✗ local-archive.yaml — 1 error(s), 0 warning(s)

  ERROR    spec.after_process
          after_process: archive is not executed on local connector runs — files stay in place (honored by one-shot lm csv ingest and by sftp/s3)
          → Set after_process: none for a local connector, or move the files with an sftp/s3 transport
```

**At-least-once — the file and its rows are never lost together** (sftp/s3):

- a file is archived or deleted only **after a later chunk's committed cursor** proves its
  rows landed — never inside the read that ingests it;
- a crash in between leaves the file in place; the next run's dedup skips its rows and the
  action runs then;
- zero-byte files are never listed, so never archived.

## Target table

- Tables are created in the `connector` schema as `connector.t_<name>`, one per connector,
  columns from the header (or `parser.columns`).
- **Seven reserved columns** are always there: `id`, `_row_hash`, `_ingested_at`, `_source`,
  `_run_id`, `_mapping_version`, `tenant_id`. A source column sharing one of those names is
  refused — rename it with a [`map-columns`](transformers.md#map-columns-legacy-column-names-cust_nm)
  hook.
- **`target.indexes`** declares Postgres indexes — a list of `{ columns: [...], unique: <bool> }`
  (`unique` defaults to false); a malformed entry fails validation by name instead of being
  dropped:

    ```yaml
    target:
      indexes:
        - { columns: [email], unique: true }
        - { columns: [bpartner_id, dateordered] }
    ```

- **The métier scope is on the connector**, not the target: `metadata.scopes` (see
  [Scopes](../rag/scopes.md)); the validator warns when none is declared.
- **`lm connector delete` purges** the `connector.t_*` table, the registry rows and every RAG
  chunk the connector produced — the data goes with it.

## Transform at ingestion

An optional `mapping` block repairs legacy data as it flows in — dataset → row → cell hooks,
in that order, on every connector run:

```yaml
mapping:
  cell:
    - fix-mojibake: { columns: [name, city] }      # "BÃ©nÃ©dict" → "Bénédict"
```

Every hook, with an input → output example, and one real manifest that runs them all:
[Transformers](transformers.md). Parse hooks fail **soft** — a value they cannot read becomes
`null`, the row is still written, and the run's `cell_errors` counts it.

## Connector runs vs one-shot `lm csv ingest`

Two ingestion surfaces share the transports but not the features — the validator refuses
what a scheduled run would silently ignore:

| Capability | Connector manifest (scheduled runs) | One-shot `lm csv ingest` |
|---|---|---|
| Transports | local, sftp, s3 | local upload, sftp, s3 |
| `schedule` (cron) | yes | no (one shot, by definition) |
| `mapping` hooks (dataset/row/cell) | yes (`aggregator` refused) | no — never applied |
| Transport hooks (`file_filter`/`pre_process`/`post_process`) | **refused at validate/apply** | yes |
| `max_file_size` | **refused at validate/apply** | yes (default 500 MB, over-limit aborts) |
| `after_process` on sftp/s3 | yes (deferred until the cursor proves the commit) | yes (deferred until the ingest transaction commits) |
| `after_process` on local | only `none` — archive/delete/move **refused** | yes |
| Malformed rows | isolated per-row to the DLQ, run continues | **abort** on the first malformed row (whole ingest rolls back) |
| Target table | `connector.t_<name>` (auto-schema) | `ext.<name>` (hybrid JSONB + promoted columns) |
| Naming a multi-file source | that is what a manifest **is** — one connector over a glob | `name` + more than one matched file is **refused** (see below) |

```
$ lm csv ingest ./clients.csv --name quick-look --scope demo
Ingested ./clients.csv into ext.quick_look
  • rows written:    2
  • promoted cols:   3
      - row_id (bigint)
      - email (text)
      - signup_date (text)

  • askable:         scope 'demo' — try: lm ask --scope demo "how many rows are in ext.quick_look?"
A dormant connector is registered — see `lm connector list`.
```

### What one-shot actually does

One transaction: sample → infer → write. Then a **dormant** connector (`enabled: false`, no
schedule) sits in `lm connector list` under the slugged name (`quick-look` → `quick_look`) —
inspect, promote to a manifest, or `lm connector delete quick_look`.

- **Type inference**, widest type across the sample (default 100 rows): integers → `BIGINT`,
  decimals → `DECIMAL`, true/false → `BOOLEAN`, everything else `TEXT`. Whole and decimal
  values mixed → `DECIMAL`; empty cells contribute nothing; any other mix → `TEXT`.
  Date-looking values deliberately stay `TEXT` in v1 — `signup_date (text)` above.
- **Reserved columns** are never promoted (every `ext.*` table already has them): `id`,
  `tenant_id`, `external_id`, `data`, `indexed_at`, `connector_id`, `endpoint_id`. A source
  column with one of these names survives inside the JSONB `data`, just not as a typed column.
- **Askable immediately.** The scope you pass (`--scope`, default `system`) gets a schema card
  listing the table and its typed columns, plus one descriptor chunk in its corpus — `lm ask`
  and `lm chat` answer questions about the table with zero further configuration. Row-level
  semantic indexing remains a connector-run feature.
- **Re-ingesting the same name is a refresh, not an error**: rows deduplicate by key, new
  columns are added, the card and descriptor follow. A different `--scope` re-publishes the
  dataset under the new scope. Only a name belonging to a **configured** connector refuses
  (409) — pass `--name` or delete that connector.
- **Abort, not DLQ**: the first malformed row aborts the ingest, the transaction rolls back,
  nothing is half-written and **no source file is archived** — re-run the whole batch.
- **Remote (sftp/s3)**: each matching file is checked against `max_file_size` (default 500 MB,
  over-limit aborts), buffered to a temp file and run through the same pipeline.
  `after_process` (and `post_process`) run **after the transaction commits**, for every file
  at once. If the archive step fails once the rows are committed, it is logged as a WARNING per
  file and the run still succeeds — the file simply stays on the source.
- Delimiter and encoding are auto-detected per file, with the connector path's detectors.

!!! warning "`--name` needs a single file"
    Each matched file registers **its own** dormant connector under the name you give, so a
    name over a glob matching more than one file is refused before anything is downloaded:

    ```
    API error 400: name 'clients' would ingest 3 files into one dataset, which the one-shot
    path cannot register: each file creates its own dormant connector under that name. Point
    at a single file, or drop the name to get one dataset per file. To fuse a folder into one
    dataset, declare a connector manifest with a glob path.
    ```

    `sftp://host/dir/` globs `*.csv` by default (`--pattern`), so the plain folder gesture *is*
    multi-file. Three ways forward:

    - **one dataset per file** — drop `--name`; each file gets `ext.<filename>`.
    - **one specific file** — point the URL at it, or narrow `--pattern` / `--suffix`.
    - **the whole folder as one dataset** — a connector manifest with a glob path: one
      connector, one table, a schedule if you want it.

### Transport hooks (one-shot only)

```yaml
transport:
  file_filter:  { kind: kotlin, script_ref: reference/skip-if-too-small.kt }
  pre_process:  { kind: kotlin, script_ref: reference/decompress-gzip.kt }
  post_process: { kind: kotlin, script_ref: reference/notify-webhook.kt }
```

- Three reference hooks ship under `META-INF/hooks/connectors/csv-file/reference/`.
- Signatures: `shouldProcess(file, source): Boolean` (file_filter),
  `transform(file, input: InputStream): InputStream` (pre_process),
  `postProcess(file, result)` (post_process). They run *in addition to* `after_process`.
- A connector manifest carrying them, or `max_file_size`, is **refused at validate/apply** —
  scheduled runs would silently ignore them:

```
$ lm validate -f oneshot-keys.yaml
✗ oneshot-keys.yaml — 2 error(s), 2 warning(s)

  ERROR    spec.archive_path
          archive_path is required when after_process=move
          → Set archive_path: /path/to/archive/

  ERROR    spec.transport.max_file_size
          max_file_size runs only on one-shot lm csv ingest — scheduled connector runs ignore it
          → Drop it from the connector manifest, or ingest this source via lm csv ingest

  WARN     spec.transport.max_file_size
          unknown key — ignored by the runtime
          → Remove it, or check the spelling

  WARN     spec.transport.chunk_size
          unknown key — ignored by the runtime
          → Remove it, or check the spelling
```

### CLI

```bash
lm csv ingest <local-file>
lm csv ingest sftp://user@host[:port]/path-or-glob --key-secret X | --password-secret X [--host-key-fingerprint "SHA256:…"]
lm csv ingest s3://bucket/prefix --region eu-west-3 [--endpoint X] [--suffix .csv]
```

## How to…

### Ingest an SFTP folder nightly

1. Store the secret the manifest names:

    ```
    $ lm secret set CSV_SFTP_PASSWORD -   # paste the value on stdin, Ctrl+D
    ```

2. Apply the manifest — this one targets the docker-compose demo server:

    ```yaml
    --8<-- "docs/connectors/csv/30-sftp-key.yaml"
    ```

3. `lm connector run csv-sftp` reads every file under `/upload/seed/*.csv`. Once it looks
   right, add `schedule: "0 6 * * *"` and switch `after_process` to `archive` with an
   `archive_path`, so processed files move out of the way between runs — each later run
   picks up only files newer than the last one processed.

Watch it land in the TUI ([CLI & TUI](../cli/lm-reference.md)): `:runs` for the run's
status, `:dlq` for any rejected rows.

### Ingest a headerless export under an S3 prefix, and archive it

```yaml
--8<-- "docs/connectors/csv/45-s3-headerless-archive.yaml"
```

**Result:** the [run ledger above](#s3-a-prefix-in-a-bucket-aws-minio-r2-gcs-s3-compat) —
19 009 rows on the first run, `IN 0` on the next because the file is in `lumnik-archive`
now. Column 1 lands as `c_bpartner_id`, column 2 as `name`, and so on — no header row is
read or expected. For MinIO or another S3-compatible store, add `endpoint:` — see
`40-s3-prefix.yaml` in `docs/connectors/csv/`.

### Load one file for a quick look

```
$ lm csv ingest ./clients.csv
```

**Result:** a dormant connector plus a hybrid table `ext.clients`, askable in the `system`
scope — inspect, then promote it into a manifest or `lm connector delete clients`.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `SFTP host key refused for host:22 — the server presented SHA256:…` | `strict_host_key` is true (the default) and `host_key_fingerprint` is absent or does not match | confirm the presented fingerprint out of band (`ssh-keyscan -p 22 HOST \| ssh-keygen -lf -`), paste it into `host_key_fingerprint`. `strict_host_key: false` accepts an impostor as readily as the real server |
| `SFTP error (SSH_FX_PERMISSION_DENIED): Permission denied` | wrong password/key, or the user cannot read `path` | `lm secret list`, then `lm secret set <NAME> -`; check the folder's rights on the server |
| `SFTP transport requires key_env or password_env` | neither credential declared | `lm secret set <NAME> -`, then `password_env: <NAME>` or `key_env: <NAME>` |
| `S3 403 Forbidden` | invalid S3 credentials | check the secrets named by `access_key_env` / `secret_key_env` |
| `columns is required when has_header: false` | a headerless parser without names | `parser.columns: [a, b, …]` in file order |
| `after_process: archive is not executed on local connector runs` | a relocation on a local transport | `after_process: none`, or an sftp/s3 transport |
| `max_file_size runs only on one-shot lm csv ingest` | a one-shot key in a connector manifest | drop it, or ingest via `lm csv ingest` |
| run completes with `IN 0` | nothing new: the cursor already covers every listed file (sftp/s3, or a local file after its last full chunk) — or the local path matches nothing on the **hub's** disk | `lm connector get NAME` shows the path; on local, check it exists inside the hub container |
| a file dropped in a local folder is never read | the [lexical-cursor contract](#local-the-hubs-disk-not-yours) — its name sorts before the cursor's file | name incoming files monotonically (date-prefixed), or switch to sftp/s3 |
| `DEAD-LETTERS` > 0, reason `MalformedSource` | rows whose value count differs from the declared columns — a trailing delimiter, an unclosed quote | `lm dlq get ID` shows the raw values, file and line; fix the export or the `parser:` block |
| rows missing, DLQ empty | `filter-where` / `skip-empty-rows` excluded them by declared policy (counted in `SKIPPED`, never DLQ'd) | see [Transformers § Troubleshooting](transformers.md#troubleshooting) |
| a mapped column is blank for some rows, but the row landed | a `parse-*` or `lookup` hook nulled a value it could not read | `cell_errors` on `lm run get <id>` — see [Transformers](transformers.md#reference-cell-hooks) |
| ingestion refuses a column sharing a reserved hub-column name | one of the seven columns the hub adds — see [Target table](#target-table) | rename it with `map-columns` |

## See also

- [Overview](index.md) — the lifecycle every connector shares: validate → apply → run.
- [Transformers](transformers.md) — every hook, with input → output.
- [Scopes](../rag/scopes.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
