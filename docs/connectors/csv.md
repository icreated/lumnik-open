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

## Quickstart

```yaml
--8<-- "docs/connectors/csv/10-local-simple.yaml"
```

```bash
lm validate -f docs/connectors/csv/10-local-simple.yaml
lm apply -f docs/connectors/csv/10-local-simple.yaml
lm connector run csv-local-simple
```

## Transports

### Local

```yaml
transport:
  kind: local
  path: /imports/*.csv        # glob OK
```

Glob limits: `*` and `?` only, and only in the **final filename segment** — the directory
part must be literal (`/imports/*/x.csv` never matches; there is no `**` recursion). A
plain path (no glob characters) that doesn't exist simply yields an empty run. No name is
excluded implicitly — a `*.csv` glob is how you keep `.tmp` files out.

!!! warning "Drop-directory contract: names must grow lexically"
    On scheduled runs the local cursor resumes by the current file's position in the
    **lexically sorted** glob result. A file dropped later with a lexically *earlier*
    name sorts before the cursor and is treated as already ingested — it is never read.
    Name incoming files monotonically (date-prefixed: `2026-07-25-orders.csv`); if your
    producer cannot guarantee that, use an sftp/s3 transport, whose cursor tracks files
    by modification time instead.

*Older pre-manifest configs with a bare top-level `path:` still work; manifests always
declare `transport:`.*

### SFTP

```yaml
transport:
  kind: sftp
  host: sftp.acme.io
  port: 22                    # optional, default 22
  user: lumnik
  path: /export/*.csv         # glob OK
  key_env: ACME_SFTP_KEY      # OR password_env, at least one required
  # password_env: ACME_SFTP_PWD
  strict_host_key: true       # optional, default true — refuses unless the fingerprint below matches
  host_key_fingerprint: "SHA256:2Mc+KLpM…"   # required when strict_host_key is true
# spec level, next to transport:
after_process: move
archive_path: /archive/       # required when after_process: move; archive defaults to /archive/
```

!!! warning "Host-key verification actually verifies now — and existing connectors will stop"

    `strict_host_key` used to change nothing. Set to `false` it installed an accept-anything
    verifier; set to `true` — the default — it installed **nothing**, and Apache MINA SSHD's own
    default is `AcceptAllServerKeyVerifier`. Both settings accepted any key that answered, so an
    active attacker between the hub and your SFTP server could impersonate it, feed arbitrary
    rows into ingestion, and collect the password (`password_env` is offered to whoever picks up).

    It now refuses when `strict_host_key` is true and `host_key_fingerprint` is absent or does
    not match. **An SFTP connector that ran happily on the default will stop connecting until you
    declare the fingerprint** — which is the point: it was never verified, and the green said
    otherwise. The refusal prints the fingerprint the server presented, so confirm that value out
    of band and paste it in. `strict_host_key: false` remains available and now means what it
    says.

`key_env` and `password_env` reference an entry from [`lm secret`](../admin-console-guide.md)
or an OS env var (resolution: DB secret registry first, env var fallback).

### S3 (and S3-compatible: MinIO, Cloudflare R2, GCS-S3-compat)

```yaml
transport:
  kind: s3
  bucket: acme-exports         # required
  prefix: billing/             # optional key prefix filter, default ""
  region: eu-west-3            # optional, default eu-west-3
  endpoint: https://...        # optional, for non-AWS S3
  suffix: .csv                 # optional filename filter, default "" (no filter)
  access_key_env: AWS_ACCESS_KEY_ID   # optional, default AWS_ACCESS_KEY_ID
  secret_key_env: AWS_SECRET_ACCESS_KEY # optional, default AWS_SECRET_ACCESS_KEY
  archive_bucket: acme-archive # optional, for after_process=archive|move (default: source bucket)
  archive_prefix: archive/     # optional, default "archive/"
```

## Parser

```yaml
parser:
  delimiter: ","                # optional, auto-detected if absent
  encoding: UTF-8               # optional, auto-detected if absent
  has_header: true              # default true
  bom_strip: true               # default true
  null_values: ["", "NULL", "null", "#N/A"]  # default as shown
  columns: [code, name, email]  # REQUIRED when has_header: false (names the columns); ignored otherwise
```

Auto-detection mechanics (per file, when the key is absent): `encoding` is detected with a
fallback of UTF-8; `delimiter` by counting the candidates `,` `;` TAB `|` over the first
8 KB — most frequent wins, fallback `,`. An explicit key overrides only that aspect; the
other stays auto-detected.

!!! note "Chunking is fixed, not a knob"
    Local connector runs commit in fixed 500-row chunks; remote transports (sftp/s3) read
    **one whole file per transaction**. A `chunk_size` key is ignored (the validator warns).
    Very large remote files: split producer-side.

### Malformed rows — per-row DLQ

A malformed row never fails the file: it is isolated to the dead-letter queue (reason
`MalformedSource`) and the run continues. Inspect with `lm dlq list` / `lm dlq get ID`; the
cursor counts consumed events, so a resume never re-emits already-dead-lettered rows.

## Scheduling & lifecycle

- `schedule` — optional cron expression. 5 fields (UNIX crontab dialect) supported (`0 6 * * *` = daily at 06:00 UTC — the hub scheduler evaluates cron in UTC). Validated at apply time. Once applied, the hub's own scheduler fires it — no OS cron needed (absent `schedule` on a re-apply unschedules the run).
- `after_process` — required. One of:
  - `archive` — relocate processed files (sftp: `archive_path`, default `/archive/`; s3: `archive_bucket`/`archive_prefix`, defaults source bucket + `archive/`)
  - `delete` — remove the file after success
  - `move` — move to `archive_path` (required when this mode is selected). At runtime `archive` and `move` are the same relocation action — `move` just makes `archive_path` mandatory.
  - `none` — leave the file in place (useful for testing)

!!! warning "Local transport: only `none` is valid in a manifest"
    The local scheduled reader (row-level glob cursor) has no per-file completion tracking —
    `archive`/`delete`/`move` never execute there, so the validator **refuses** them on a
    local connector rather than let files silently stay in place. sftp/s3 honor all four
    values; the one-shot `lm csv ingest` path honors them on every transport.

**At-least-once: the file and its rows are never lost together.** Crash-safety timing
(sftp/s3): a file is archived/deleted only **after** a later chunk's committed cursor
proves its rows landed — never inside the read that ingests it. A crash in between leaves
the file in place; dedup skips re-ingestion and the action runs on the next read.

Zero-byte files are skipped by the sftp/s3 listings (and by the one-shot path) — they are
never ingested and never archived.

## Target table

`target.tags` enables RAG scope filtering — strongly recommended. The connector warns at
validate-time if missing. Tags are what make the ingested rows discoverable to scoped chat
— see [Scopes](../rag/scopes.md).

`target.indexes` declares Postgres indexes on the hub table — a list of
`{ columns: [...], unique: <bool> }` objects (`unique` optional, default false):

```yaml
target:
  tags: [billing]
  indexes:
    - { columns: [email], unique: true }
    - { columns: [bpartner_id, dateordered] }
```

Each entry needs a non-empty `columns` list of strings; malformed entries fail validation
with a per-entry error instead of being silently dropped.

Tables are created in the `connector` schema as `connector.t_<name>` (one per file target).
The hub table always carries seven reserved columns (`id`, `_row_hash`, `_ingested_at`,
`_source`, `_run_id`, `_mapping_version`, `tenant_id`) — a source column sharing any of
those names is refused. Rename it with a `map-columns` hook in the mapping before
ingesting (see [Transformers: legacy column names](transformers.md#legacy-column-names-cust_nm)).

Deleting the connector (`lm connector delete`) **purges** its `connector.t_*` tables,
registry rows and every RAG chunk it produced — the data goes with it.

## Transform at ingestion

An optional `mapping` block repairs legacy data as it flows in (dataset → row → cell hooks,
applied in that order on every connector run):

```yaml
mapping:
  cell:
    - fix-mojibake: { columns: [name, city] }      # "BÃ©nÃ©dict" → "Bénédict"
```

Full parameter reference: [Transformers](transformers.md). Parse hooks (`parse-date`,
`parse-number`, `parse-email`, `parse-phone`, `lookup`) fail **soft** — a value they can't
read becomes `null` and the row is still written, counted in the run's `cell_errors` (see
[Transformers § Reference — cell hooks](transformers.md#reference-cell-hooks)).

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

### What one-shot actually does

One transaction: sample → infer → write. The service samples the first rows (default 100),
infers a schema, creates a **dormant** connector + endpoint (`enabled: false`, no schedule
— inspect, promote, or delete before enabling anything), then writes all rows to a hybrid
`ext.<name>` table (full row as JSONB + typed promoted columns).

- **Type inference** (widest type across the sample; on conflict TEXT wins): integers →
  `BIGINT`, decimals → `DECIMAL`, true/false → `BOOLEAN`, everything else `TEXT`.
  Date-looking values deliberately stay `TEXT` in v1.
- **Reserved columns** are never promoted (they already exist in every `ext.*` table):
  `id`, `tenant_id`, `external_id`, `data`, `indexed_at`, `connector_id`, `endpoint_id`.
  A source column with one of these names survives inside the JSONB `data`, just not as a
  typed column.
- **Abort, not DLQ**: the first malformed row aborts the ingest and the transaction rolls
  back — nothing is half-written, and **no source file is archived**: an aborted run leaves
  every file where it was, so the whole batch can simply be re-run. Per-row DLQ isolation is
  a *connector-run* behavior.
- Delimiter and encoding are auto-detected per file (same detectors as the connector path).
- Remote (sftp/s3): each matching file is checked against `max_file_size` (default 500 MB,
  over-limit aborts), buffered to a temp file and run through the same pipeline.
  `after_process` (and `post_process`) run **after the transaction commits**, for every
  ingested file at once — the commit is the proof the rows landed, so a crash can never
  leave archived files whose rows were rolled back. If the archive step itself fails once
  the rows are committed, it is logged as a WARNING per file and the run still succeeds:
  the file simply stays on the source.

!!! warning "`--name` needs a single file"

    Each matched file registers **its own** dormant connector under the name you give, so a
    name over a glob that matches more than one file is **refused before anything is
    downloaded** — one name cannot label two connectors:

    ```
    API error 400: name 'clients' would ingest 3 files into one dataset, which the one-shot
    path cannot register: each file creates its own dormant connector under that name. Point
    at a single file, or drop the name to get one dataset per file. To fuse a folder into one
    dataset, declare a connector manifest with a glob path.
    ```

    Mind that `sftp://host/dir/` globs `*.csv` by default (`--pattern`), so the plain folder
    gesture *is* multi-file. Three ways forward:

    - **one dataset per file** — drop `--name`; each file gets `ext.<filename>` and its own
      dormant connector.
    - **one specific file** — point the URL at it (or narrow `--pattern` / `--suffix`), and
      `--name` works as documented.
    - **the whole folder as one dataset** — that is a *connector manifest* with a glob path:
      one connector, one table, and a schedule if you want it. See
      [Quickstart](#quickstart) and [Transports](#transports) above.

### Kotlin hooks (one-shot `lm csv ingest` only)

> **Boundary:** these hooks and `max_file_size` run only on the one-shot
> `lm csv ingest` path. A connector manifest carrying them is **refused at validate/apply**
> — scheduled connector runs would silently ignore them (the same validate-time refusal
> applies to `after_process: archive|delete|move` on a local transport — see
> [Scheduling & lifecycle](#scheduling-lifecycle)).

```yaml
transport:
  file_filter:  { kind: kotlin, script_ref: reference/skip-if-too-small.kt }
  pre_process:  { kind: kotlin, script_ref: reference/decompress-gzip.kt }
  post_process: { kind: kotlin, script_ref: reference/notify-webhook.kt }
```

3 reference hooks ship under `META-INF/hooks/connectors/csv-file/reference/`.

Signatures:
```kotlin
fun shouldProcess(file: FileDescriptor, source: Any?): Boolean   // file_filter
fun transform(file: FileDescriptor, input: InputStream): InputStream   // pre_process
fun postProcess(file: FileDescriptor, result: Any?)   // post_process
```

`after_process` accepts only `archive | delete | move | none` (validator). The Kotlin hooks
above run *in addition to* the declared action.

### CLI

```bash
lm csv ingest <local-file>
lm csv ingest sftp://user@host[:port]/path-or-glob --key-secret X | --password-secret X
lm csv ingest s3://bucket/prefix --region eu-west-3 [--endpoint X] [--suffix .csv]
```

## How to…

### Ingest an SFTP folder nightly

1. Set the secret the manifest references — `password_env: CSV_SFTP_PASSWORD` resolves via
   [`lm secret`](../admin-console-guide.md) (DB registry first, env var fallback; see
   [SFTP transport](#transports) above):

   ```bash
   lm secret set CSV_SFTP_PASSWORD -   # paste the value on stdin, Ctrl+D
   ```

2. Apply the manifest:

   ```yaml
   --8<-- "docs/connectors/csv/30-sftp-key.yaml"
   ```

   The example above leaves `after_process: none` for a first dry run. Once it looks right,
   add `schedule: "0 6 * * *"` (daily at 06:00) and switch `after_process` to `archive`
   with an `archive_path` (spec-level, next to `transport:` — see [SFTP transport](#transports)
   above) so processed files are moved out of the way between runs.

**Result:** `lm connector run csv-sftp` reads every file under `/upload/seed/*.csv`; on
each scheduled run the cursor picks up only files newer than the last one processed. Watch
it land in the TUI ([CLI & TUI](../cli/lm-reference.md)): `:runs` for the run's status,
`:dlq` for any rejected rows.

### Load one file for a quick look

```bash
lm csv ingest ./clients.csv
```

**Result:** a dormant connector (`enabled: false`, no schedule) plus a hybrid table
`ext.clients` — inspect the data, then either promote it into a scheduled manifest or run
`lm connector delete clients` to clean up.

### Ingest every file under an S3 prefix

```yaml
--8<-- "docs/connectors/csv/40-s3-prefix.yaml"
```

**Result:** `lm connector run csv-s3` reads every object matching `prefix:`/`suffix:` in
the bucket, writing all rows into `connector.t_csv_s3`.

### Ingest headerless files

```yaml
parser:
  has_header: false
  columns: [code, name, email]
```

**Result:** column 1 maps to `code`, column 2 to `name`, column 3 to `email` — no header
row is read or expected.

## Troubleshooting

| Error | Cause | Fix |
|---|---|---|
| `sftp connect failed: auth` | key/password missing or invalid | `lm secret list` then `lm secret set ACME_SFTP_KEY ...` |
| `SFTP host key refused` | `strict_host_key` is true (the default) and `host_key_fingerprint` is absent or does not match | the error prints the fingerprint the server presented — confirm it out of band with `ssh-keyscan -p 22 HOST \| ssh-keygen -lf -`, then paste it into `host_key_fingerprint`. `strict_host_key: false` skips the check entirely and accepts an impostor as readily as the real server. |
| `S3 403 Forbidden` | invalid S3 credentials | check the variables named by `access_key_env` / `secret_key_env` (defaults `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`) |
| `file exceeds max_file_size` | file > 500 MB | raise `max_file_size` or split producer-side |
| `Hook script not found` | script_ref points to a missing file | check `META-INF/hooks/connectors/csv-file/<script_ref>` |
| file never picked up on schedule | the [lexical-cursor contract](#transports) — a lexically earlier name sorts before the cursor | name incoming files monotonically (date-prefixed), or switch to sftp/s3 |
| rows missing after a run | malformed rows, or rows a `validate-row` hook rejected, land in the DLQ — not the target table | `lm dlq list` / `lm dlq get ID` |
| rows missing after a run, but the DLQ is empty | `filter-where` or `skip-empty-rows` excluded them by declared policy — counted in `records_skipped`, never DLQ'd (a declared filter isn't a quality failure) | check `SKIPPED` in `lm run get` (or the TUI's `:runs` RECORDS `in/out/skip`) — that's the count; see [transformers.md § Troubleshooting](transformers.md#troubleshooting) |
| a mapped column (date/number/email/phone) is blank for some rows, but the row landed | a `parse-*` or `lookup` cell hook in `spec.mapping` couldn't read that value and nulled it by design — the row was written, not dropped | check `cell_errors` on the run: `lm run get <id>` (stderr alert when > 0, or `-o json`/`-o yaml`), or the TUI describe panel (`:runs`, select the run, `d`) — see [Transformers § Reference — cell hooks](transformers.md#reference-cell-hooks) |
| ingestion fails on a column sharing a reserved hub-column name | collides with one of the seven columns the hub always adds to the table — see [Target table](#target-table) | rename it via a `map-columns` transform |

## See also

- [Overview](index.md)
- [Transformers](transformers.md)
- [Scopes](../rag/scopes.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
