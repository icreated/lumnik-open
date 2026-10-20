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

The manifest's `path:` is read by the **hub**. On the self-host stack, copy the sample file into
the container first (`docker cp docs/connectors/csv/data/customers.csv lumnik-hub-1:/tmp/exports/`)
and point `path:` there; a hub in dev mode reads the repo-relative path as is. From the repository root:

```
$ lm validate -f docs/connectors/csv/10-local-simple.yaml
✓ 10-local-simple.yaml — valid
$ lm apply -f docs/connectors/csv/10-local-simple.yaml
{"name":"csv-local-simple","connectorId":"f59d18ef-d308-4015-8e84-7fd8cb3a8250","endpointsApplied":1}
$ lm connector run csv-local-simple
run scheduled: 2e37cb12-0a66-4c4c-a4c2-b796b2d40540
```

"Scheduled" is not "done" — the result lands in the run ledger a second later:

```
$ lm run get 2e37cb12
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
2e37cb12  csv-local-simple  default   Completed  2   2    0        0             2026-10-19 01:39  2026-10-19 01:39
```

`STARTED` / `ENDED` are in your terminal's time zone (these screens: Paris, UTC+2); `schedule`
is evaluated in UTC.

What landed in `connector.t_csv_local_simple` — the header row became the columns:

```
lumnik=> select row_id, email, signup_date from connector.t_csv_local_simple;
 row_id |    email    | signup_date
--------+-------------+-------------
 1      | alice@x.com | 02/06/2026
 2      | bob@x.com   | 15/01/2026
(2 rows)
```

Run it again on the unchanged file, then once more after a third line is appended:

```
$ lm run list --connector csv-local-simple
ID        CONNECTOR         ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
2565ea5a  csv-local-simple  default   Completed  3   3    0        0             2026-10-19 01:39  2026-10-19 01:39
52d1ac0b  csv-local-simple  default   Completed  0   0    0        0             2026-10-19 01:39  2026-10-19 01:39
2e37cb12  csv-local-simple  default   Completed  2   2    0        0             2026-10-19 01:39  2026-10-19 01:39
```

- **A file already read is not read again** — the second run says `IN 0`. A file that changed
  (replaced in place, or grown) is read again in full: the third run says `IN 3`.
- **The table deduplicates**: after three runs it holds 3 rows, not 5.
- `SKIPPED` and `DEAD-LETTERS` are explained [below](#malformed-rows-the-per-row-dlq).

## The three transports

The manifest's `transport:` block picks one:

| | `local` | `sftp` | `s3` |
|---|---|---|---|
| files are on | the **hub's** filesystem | a remote server | a bucket |
| selected by | path or glob | path or glob | `prefix` + `suffix` |
| a new run reads | files whose name sorts after the last one read | files modified since the last run | files modified since the last run |
| `after_process` | `none` only | all four | all four |
| credentials | — | `key_env` or `password_env` | `access_key_env` / `secret_key_env` |

### Local — the hub's disk, not yours

```yaml
transport:
  kind: local
  path: /imports/*.csv        # glob OK
```

- **The path is on the hub's filesystem.** The self-host stack mounts nothing: mount a volume,
  use `sftp`/`s3`, or upload one file with [`lm csv ingest`](#connector-runs-vs-one-shot-lm-csv-ingest).
- **Glob**: `*` and `?` only, in the **final filename segment** — `/imports/*/x.csv` never
  matches, and there is no `**`.
- **No name is excluded implicitly** — use `*.csv` to keep `.tmp` files out.
- **A path that matches nothing is not an error**: the run completes with `IN 0`.
- **A path without `*` or `?` names one file**, and that file is the whole table — see
  [full refresh](#one-file-is-the-table-full-refresh).

**A drop folder, run by run.** A 1 200-row export `2026-09-01-orders.csv`, then two more files:

```
$ lm run list --connector orders-drop
ID        CONNECTOR    ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
b159e727  orders-drop  default   Completed  0     0     0        0             2026-10-19 01:39  2026-10-19 01:39
393a2729  orders-drop  default   Completed  10    10    0        0             2026-10-19 01:39  2026-10-19 01:39
165a4c6d  orders-drop  default   Completed  0     0     0        0             2026-10-19 01:39  2026-10-19 01:39
ff5b8f80  orders-drop  default   Completed  1200  1200  0        0             2026-10-19 01:39  2026-10-19 01:39
```

| run | the folder holds | `IN` | why |
|---|---|---|---|
| 1 | `2026-09-01` (1 200 rows) | 1 200 | first read |
| 2 | the same file | 0 | already read in full |
| 3 | plus `2026-09-02` (10 rows) | 10 | only the new file — it sorts **after** the last file read |
| 4 | plus `2026-08-31` (5 rows) | 0 | it sorts **before** the last file read — **never read** |

!!! warning "Drop-folder contract: names must grow lexically"
    A run resumes after the last file it read, in name order. Name incoming files
    monotonically (date-prefixed: `2026-07-25-orders.csv`), or use sftp/s3, which track
    modification time instead.

    | situation | what happens | what to do |
    |---|---|---|
    | a file arrives with a name sorting **before** the last file read | never read | name files monotonically |
    | a file arrives while a run is going | waits for the next run; read only if its name sorts after where that run ended | have the producer write under a name the glob does not match, then rename it in |
    | a listed file is removed before the run reaches it | the run fails, naming the file | run again |
    | a file is archived before its run is `Completed` (or after a run that failed midway) | if later files are waiting, the next run fails, naming that file | `lm endpoint reset-cursor <connector>/<endpoint>`: the next run reads from the first file, the table deduplicates |

    Files read by a `Completed` run may be archived safely.

### SFTP — a remote folder, a secret, a fingerprint

```yaml
transport:
  kind: sftp
  host: sftp.example.com
  port: 22                    # optional, default 22
  user: lumnik
  path: /erp-export/*.csv     # glob OK
  password_env: SFTP_ERP_PASSWORD           # OR key_env (the PEM private key) — one is required
  host_key_fingerprint: "SHA256:M0Is0aG8ubOr0cLpQs6QcDwVSxMkvmlfuW4K9Q6CJuc"  # required unless strict_host_key: false
  # strict_host_key: true     # the default — refuses unless the fingerprint above matches
# spec level, next to transport:
after_process: archive
archive_path: /archive/       # default /archive/; required when after_process: move
```

**Credentials are never inline.** `key_env` / `password_env` name a secret stored with
[`lm secret`](../cli/lm-reference.md#data-runs-answers); an OS env var of that name is the fallback:

```
$ lm secret set SFTP_ERP_PASSWORD
Value for SFTP_ERP_PASSWORD:
Created secret "SFTP_ERP_PASSWORD"
```

A manifest with neither credential is refused:

```
$ lm validate -f sftp-nocred.yaml
✗ sftp-nocred.yaml — 1 error(s), 0 warning(s)

  ERROR    spec.transport
          SFTP transport requires key_env or password_env
          → Use `lm secret set <NAME> ...` then reference via key_env: <NAME>

Fix errors above before running 'lm apply -f sftp-nocred.yaml'.
Error: validation failed: 1 error(s)
```

**One session per run.** Your server sees one connection per running endpoint, for the whole run:

- its idle timeout and its limit on concurrent sessions apply to that connection;
- a session lost between two chunks is reopened, and a silent one is detected within 15 seconds;
- a session lost in the middle of a chunk fails the run with the connection error.

**The host key is verified.** With `strict_host_key: true` (the default), `host_key_fingerprint`
must match what the server presents. Here a wrong fingerprint is declared:

```
$ lm run get b31b1efe
ID        CONNECTOR    ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
b31b1efe  sftp-badkey  default   Failed  0   0    0        0             2026-09-06 22:13  2026-09-06 22:13
✖ run failed: SFTP host key refused for sftp.example.com:22 — the server presented SHA256:M0Is0aG8ubOr0cLpQs6QcDwVSxMkvmlfuW4K9Q6CJuc, and it does not match the declared SHA256:0000000000000000000000000000000000000000000. Confirm that fingerprint out of band (ssh-keyscan -p 22 sftp.example.com | ssh-keygen -lf -), then set host_key_fingerprint: "SHA256:M0Is0aG8ubOr0cLpQs6QcDwVSxMkvmlfuW4K9Q6CJuc" in the transport block. Setting strict_host_key: false connects without checking at all — which accepts an impostor as readily as the real server.
```

- **Confirm the presented fingerprint out of band** (the `ssh-keyscan | ssh-keygen -lf -` line,
  from a machine you trust), then paste it.
- `strict_host_key: false` skips the check: an impostor would receive the password and feed
  rows. Use it only for a throwaway container whose key changes at every restart — as the demo
  manifest [`30-sftp-key.yaml`](#ingest-an-sftp-folder-nightly) does.

A wrong password, or a user who cannot read the folder:

```
$ lm run get 22957eeb
ID        CONNECTOR                    ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
22957eeb  sftp-c-bpartner-credentials  default   Failed  0   0    0        0             2026-09-04 14:23  2026-09-04 14:23
✖ run failed: SFTP error (SSH_FX_PERMISSION_DENIED): Permission denied
```

**A file is read once while it is unchanged**; one rewritten later (newer modification time) is
read again. Oldest first; a run that finds nothing new reads nothing.
Zero-byte files are never listed — never ingested, never archived:

```
$ lm run list --connector sftp-c-bpartner-credentials
ID        CONNECTOR                    ENDPOINT  STATUS     IN     OUT    SKIPPED  DEAD-LETTERS  STARTED           ENDED
a9533cb5  sftp-c-bpartner-credentials  default   Completed  0      0      0        0             2026-09-06 22:09  2026-09-06 22:09
80032250  sftp-c-bpartner-credentials  default   Completed  6      5      1        1             2026-09-04 15:27  2026-09-04 15:27
8358fb91  sftp-c-bpartner-credentials  default   Completed  19009  19009  0        0             2026-09-04 14:40  2026-09-04 14:41
```

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

- `billing/` + `.csv` reads `billing/2026-09-01.csv` and ignores `billing/2026-09-01.csv.tmp`.
- Same rule as SFTP: an object is read when new or rewritten, oldest first; zero-byte objects are skipped.
- `access_key_env` / `secret_key_env` are secret **names** (`lm secret set AWS_ACCESS_KEY_ID`).
  A rotated key takes effect at the next run.

**One export, archived after commit** — the manifest of the
[S3 how-to](#ingest-a-headerless-export-under-an-s3-prefix-and-archive-it):

```
$ lm run list --connector s3-c-bpartner
ID        CONNECTOR      ENDPOINT  STATUS     IN     OUT    SKIPPED  DEAD-LETTERS  STARTED           ENDED
ee860da1  s3-c-bpartner  default   Completed  10     5      5        5             2026-09-06 16:30  2026-09-06 16:30
dda81fcb  s3-c-bpartner  default   Completed  0      0      0        0             2026-09-06 16:21  2026-09-06 16:21
7329d11d  s3-c-bpartner  default   Completed  19009  19009  0        0             2026-09-06 16:03  2026-09-06 16:03
```

- **16:03** — the 19 009-row export, read and archived.
- **16:21** — nothing new under `billing/` (the file is in the archive bucket now): `IN 0`.
- **16:30** — a 10-record file, half malformed on purpose: 5 rows in, 5 dead-lettered.

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

- **Auto-detection is per file.** `encoding`: detected, fallback UTF-8. `delimiter`: the most
  frequent of `,` `;` TAB `|` over the first 8 KB, fallback `,`. An explicit key overrides it.
- **Headerless files need names**, in file order (`"\t"` is a tab in YAML):

    ```yaml
    parser:
      delimiter: "\t"
      has_header: false
      columns: [c_bpartner_id, name, created, isactive]
    ```

    Without `columns`:

    ```
    $ lm validate -f hl.yaml
    ✗ hl.yaml — 1 error(s), 0 warning(s)

      ERROR    spec.parser.columns
              columns is required when has_header: false — headerless rows need names
              → Add parser.columns: [code, name, ...] in file order

    Fix errors above before running 'lm apply -f hl.yaml'.
    Error: validation failed: 1 error(s)
    ```

- **An empty value is a present column.** `1,` against two columns is written, the second value
  `null` (because `""` is in `null_values`; override the list to keep `""`).
- Runs commit in fixed **500-row chunks**; a `chunk_size` key is ignored (the validator warns).

## Malformed rows — the per-row DLQ

A malformed row never fails the file. It goes to the dead-letter queue (**DLQ**), reason
`MalformedSource`, and the run continues. A 6-row file with two bad rows:

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
$ lm run get 0a4af97d
ID        CONNECTOR      ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
0a4af97d  csv-malformed  default   Completed  6   4    2        2             2026-10-19 01:40  2026-10-19 01:40
⚠ 2 rows quarantined — see: lm dlq list --run 0a4af97d-cac0-4de0-a299-1db4599b8b31
$ lm dlq list --run 0a4af97d-cac0-4de0-a299-1db4599b8b31
ID        CONNECTOR      RUN       REASON           ATTEMPTS  MESSAGE
556c4da1  csv-malformed  0a4af97d  MalformedSource  1         /tmp/exports/erp-export/c_bpartner_malformed.csv:6 — Index f…
a2867c28  csv-malformed  0a4af97d  MalformedSource  1         /tmp/exports/erp-export/c_bpartner_malformed.csv:4 — Record …
```

The entry keeps every raw value, with the file and line:

```
$ lm dlq get a2867c28
ID:        a2867c28-0424-44f7-86dd-77f3a0d86872
Connector: csv-malformed (434507c6-df49-4a09-a59f-dfb5df0589e9)
Run:       0a4af97d-cac0-4de0-a299-1db4599b8b31
Reason:    MalformedSource
Attempts:  1
Message:   /tmp/exports/erp-export/c_bpartner_malformed.csv:4 — Record has 3 values but only 2 columns are declared
Detail:    {"file":"/tmp/exports/erp-export/c_bpartner_malformed.csv","line":4,"message":"Record has 3 values but only 2 columns are declared"}
Payload:   {"raw":["BADROW","trop de colonnes","erp legacy glitch"],"file":"/tmp/exports/erp-export/c_bpartner_malformed.csv","line":4}
```

**Malformed = the value count differs from the declared columns, in either direction:**

| the row | against 2 declared columns | result |
|---|---|---|
| `BADROW,"trop de colonnes","erp legacy glitch"` | 3 values | DLQ — `Record has 3 values but only 2 columns are declared` |
| `BADROW2` | 1 value | DLQ — `Index for header 'name' is 1 but CSVRecord only has 1 values!` |
| `1,` | 2 values, one empty | **written**, `name` is `null` |
| `1,2,` | 3 values — a trailing delimiter is one column too many | DLQ |
| `3,"open` (unclosed quote) | swallows the rest of the file | DLQ — one entry; the lines after it are never rows |

- **An extra value is never dropped to make the row fit.**
- **A trailing delimiter on every line sends the whole file to the DLQ** — strip it producer-side.
- **`SKIPPED` and `DEAD-LETTERS` move together** for malformed rows.
- [`lm dlq`](../cli/lm-reference.md): `list`, `get`, then `discard`, or `replayed` once you
  re-sent the row yourself. The hub never re-reads a dead letter — fix the export and run again.
- Rows a `validate-row` hook drops land here too, reason `ValidationFailed`.
- The one-shot `lm csv ingest` has no DLQ: its first malformed row refuses the whole ingest
  with a `400` naming the line ([below](#what-one-shot-actually-does)).

## One file is the table — full refresh

A local `path` with no `*` or `?` names one file, and that file is the table's whole state. A
run that reads it to the end and succeeds removes the rows the file no longer holds.

```yaml
transport:
  kind: local
  path: /imports/articles.csv    # one file: full refresh
after_process: none
refresh:
  max_removed: 30%               # the default — off, or 100%, lifts the guard
```

Ten articles, then `A07` deleted from the file:

```
$ lm run get 2a1c346f
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
2a1c346f  articles   default   Completed  9   9    0        0             2026-10-19 01:41  2026-10-19 01:41
• 1 row(s) removed — the source no longer holds them
```

The removed count sits outside `IN = OUT + SKIPPED`: the run never read those rows.

**Four guards: the run fails and nothing is removed.** The next run reads the whole file again.

- **The table would shrink by more than `refresh.max_removed`** (30% unless declared). A truncated
  export looks exactly like a purge. Here the file is cut to 4 lines; the purge is real, so the
  threshold is raised:

```
$ lm run get d4fa0d53
ID        CONNECTOR  ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
d4fa0d53  articles   default   Failed  4   4    0        0             2026-10-19 01:41  2026-10-19 01:41
✖ run failed: 5 of 9 rows would be removed: the file holds 4 where the table held 9, more than refresh.max_removed (30%) allows — nothing was removed. If the file really lost them, raise refresh.max_removed (or set it to off) and run again.
$ tail -3 articles.yaml
  after_process: none
  refresh:
    max_removed: 60%
$ lm apply -f articles.yaml
{"name":"articles","connectorId":"96636ab2-3a72-4f9e-a351-eb9d497de081","endpointsApplied":1}
$ lm connector run articles
run scheduled: 54de3ded-1939-449a-bd99-73eb36813a1d
$ lm run get 54de3ded
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
54de3ded  articles   default   Completed  4   4    0        0             2026-10-19 01:41  2026-10-19 01:41
• 5 row(s) removed — the source no longer holds them
```

- **A row of the file went to the dead letters** while rows would be removed: its last good
  version stays. A row a `validate-row` hook drops counts the same way.

```
$ lm run get 08cb8fb5
ID        CONNECTOR  ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
08cb8fb5  articles   default   Failed  4   3    1        1             2026-10-19 01:41  2026-10-19 01:41
⚠ 1 rows quarantined — see: lm dlq list --run 08cb8fb5-286b-4fe0-84bd-eafec50d10f5
✖ run failed: 1 row(s) of the file went to the dead letters this run (lm dlq list --run 08cb8fb5-286b-4fe0-84bd-eafec50d10f5): a full refresh removes nothing while a row of the file is in the dead letters, whatever its reason (unreadable, dropped by validate-row…) — nothing was removed. Fix the cause each dead letter reports (lm dlq get <id>) and run again.
```

- **An empty file** (a header and no rows) never empties the table:

```
$ lm run get 4f61dcf3
ID        CONNECTOR  ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
4f61dcf3  articles   default   Failed  0   0    0        0             2026-10-19 01:41  2026-10-19 01:41
✖ run failed: the file is empty: a full refresh never empties a table from an empty file — nothing was removed. If the source really is empty, delete the connector's data instead (lm connector delete).
```

- **The file changed while the run read it** (rewritten between two chunks): the run fails with
  `the file changed while this run was reading it`.

The guards speak only when rows would be removed. A dead letter on a new row, with nothing to
remove, completes as usual (`IN 5  OUT 4  DEAD-LETTERS 1`).

**New values are not a loss.** Every price of the 4-line file changed: the 4 old versions go,
the table holds the 4 new rows, and the guard sees no shrink:

```
$ lm run get 16ad0bee
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
16ad0bee  articles   default   Completed  4   4    0        0             2026-10-19 01:41  2026-10-19 01:41
• 4 row(s) removed — the source no longer holds them
```

**What never removes a row:**

| the run | why |
|---|---|
| finds the file unchanged | it is not read again (size and modification time) |
| resumes a file a failed run left half-read | it did not read the whole file |
| reads a glob (`*.csv`) | a drop folder only adds |
| reads an sftp or s3 transport | each file is read when new or rewritten; nothing is compared |

A `refresh` block on a drop folder is flagged:

```
$ lm validate -f drop.yaml
! drop.yaml — 0 error(s), 1 warning(s)

  WARN     spec.refresh
          refresh is read only for one local file (transport kind: local, a path without * or ?): this source is a drop folder and never removes rows, so the block does nothing here
          → Remove the refresh block, or point transport.path at the one file that holds the table
```

Only this connector's rows are removed; a row another connector landed in the same table stays.
A removed row also leaves the entities fused from it, `lm ask`, and any Search index over them.

## Scheduling & lifecycle

- **`schedule`** — optional cron, 5 fields, in **UTC** (`0 6 * * *` = daily at 06:00 UTC),
  checked at apply. The hub fires it — no OS cron. A re-apply without `schedule` unschedules
  the connector. `window: "22:00-06:00"` (UTC, end exclusive) fences scheduled runs.
- **`after_process`** — required. What happens to a file once its rows are committed:

| | `none` | `archive` | `move` | `delete` |
|---|---|---|---|---|
| does | leaves the file in place | relocates it | relocates it — same action, `archive_path` mandatory | removes it |
| sftp | ✓ | → `archive_path` (default `/archive/`) | → `archive_path` | ✓ |
| s3 | ✓ | → `archive_bucket`/`archive_prefix` (defaults: source bucket, `archive/`) | same | ✓ |
| local | ✓ | **refused** | **refused** | **refused** |

```
$ lm validate -f local-archive.yaml
✗ local-archive.yaml — 1 error(s), 0 warning(s)

  ERROR    spec.after_process
          after_process: archive is not executed on local connector runs — files stay in place (honored by one-shot lm csv ingest and by sftp/s3)
          → Set after_process: none for a local connector, or move the files with an sftp/s3 transport

Fix errors above before running 'lm apply -f local-archive.yaml'.
Error: validation failed: 1 error(s)
```

`lm csv ingest` offers no `after_process`: the message's mention of it does not apply to `lm`.

**A file and its rows are never lost together** (sftp/s3):

- a file is archived or deleted only once a later commit proves its rows landed;
- after a crash in between, the file stays; the next run skips its rows and archives it then.

**A misspelled key is flagged** in every block except inside `mapping` — the manifest still applies:

```
$ lm validate -f typo.yaml
! typo.yaml — 0 error(s), 1 warning(s)

  WARN     spec.windo
          unknown key — ignored by the runtime
          → Remove it, or check the spelling
```

## Target table

- One table per connector, `connector.t_<name>`, columns from the header (or `parser.columns`).
- **Seven reserved columns** are always there: `id`, `_row_hash`, `_ingested_at`, `_source`,
  `_run_id`, `_mapping_version`, `tenant_id`. A table fed by one local file adds `_seen_run`,
  used by the [full refresh](#one-file-is-the-table-full-refresh). A source column with one of
  these names is refused — rename it with a [`map-columns`](transformers.md#map-columns-legacy-column-names-cust_nm) hook.
- **`target.indexes`** declares Postgres indexes — `{ columns: [...], unique: <bool> }`
  (`unique` defaults to false); a malformed entry fails validation:

    ```yaml
    target:
      indexes:
        - { columns: [email], unique: true }
        - { columns: [bpartner_id, dateordered] }
    ```

- **The métier scope is on the connector**: `metadata.scopes` (see [Scopes](../rag/scopes.md));
  the validator warns when none is declared.
- **`lm connector delete` removes the connector's rows**, its run history and its RAG chunks
  (the table goes with the last connector writing into it):

    ```
    $ lm connector delete csv-local-simple --dry-run
    Would delete connector csv-local-simple (csv-file) — 3 rows of connector.t_csv_local_simple, its run history and RAG chunks (dry run, nothing deleted)
    ```

- **`lm endpoint purge` refuses a `csv-file` endpoint** — delete the connector instead (see
  [Emptying one endpoint on its own](jdbc.md#emptying-one-endpoint-on-its-own)). The table of a
  one-shot `lm csv ingest` is the exception: `lm endpoint purge quick_look/quick_look` empties it.

    ```
    $ lm endpoint purge csv-local-simple/default --force
    Error: API error 400: This endpoint writes to a connector table whose rows are not tracked per endpoint, so it cannot be purged on its own. Deleting the connector removes its data, along with its run history.
    ```

## Transform at ingestion

An optional `mapping` block repairs legacy data as it flows in — dataset → row → cell hooks,
in that order, on every connector run:

```yaml
mapping:
  cell:
    - fix-mojibake: { columns: [name, city] }      # "BÃ©nÃ©dict" → "Bénédict"
```

Every hook, with an input → output example: [Transformers](transformers.md). Parse hooks fail
**soft** — an unreadable value becomes `null`, the row is still written, and the run's
`cell_errors` counts it.

## Connector runs vs one-shot `lm csv ingest`

| | Connector manifest (scheduled runs) | One-shot `lm csv ingest` |
|---|---|---|
| Source | local, sftp, s3 | a file on your machine (uploaded), `sftp://…`, `s3://…` |
| `schedule` (cron) | yes | no |
| `mapping` hooks (dataset/row/cell) | yes (`aggregator` refused) | no |
| `after_process` | sftp/s3: all four; local: `none` | not available — the source file stays in place |
| File size limit | none | an uploaded file: 10 MB (larger: `Error: API error 413`); a remote file: 500 MB |
| Malformed rows | per-row DLQ, run continues | the first one fails the whole ingest, nothing written |
| Target table | `connector.t_<name>` | `ext.<name>` (JSONB + typed columns) |
| Several files | one connector over a glob | one dataset per file; `--name` needs a single file |

A connector manifest carrying `file_filter`, `pre_process`, `post_process` or `max_file_size`
is refused:

```
$ lm validate -f oneshot-keys.yaml
✗ oneshot-keys.yaml — 2 error(s), 2 warning(s)

  ERROR    spec.transport.file_filter
          file_filter runs only on one-shot lm csv ingest — scheduled connector runs ignore it
          → Drop it from the connector manifest, or ingest this source via lm csv ingest

  ERROR    spec.transport.max_file_size
          max_file_size runs only on one-shot lm csv ingest — scheduled connector runs ignore it
          → Drop it from the connector manifest, or ingest this source via lm csv ingest

  WARN     spec.transport.max_file_size
          unknown key — ignored by the runtime
          → Remove it, or check the spelling

  WARN     spec.transport.file_filter
          unknown key — ignored by the runtime
          → Remove it, or check the spelling

Fix errors above before running 'lm apply -f oneshot-keys.yaml'.
Error: validation failed: 2 error(s)
```

`lm csv ingest` has no flag for these keys either: drop them.

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

With the AI switched off, the last lines read `A one-shot: no run recorded, nothing indexed for
chat.` and `The rows are in ext.quick_look, and a dormant connector is registered`.

### What one-shot actually does

One transaction: sample → infer → write. A **dormant** connector (`enabled: false`, no schedule)
then sits in `lm connector list` under the slugged name (`quick-look` → `quick_look`) — inspect
it, promote it to a manifest, or `lm connector delete quick_look`.

- **Type inference**, widest type over the first 100 rows: integers → `BIGINT`, decimals →
  `DECIMAL`, true/false → `BOOLEAN`, anything else `TEXT`. Dates stay `TEXT` — `signup_date (text)` above.
- **Reserved columns** are never typed columns: `id`, `tenant_id`, `external_id`, `data`,
  `indexed_at`, `connector_id`, `endpoint_id`. Their values stay in the JSONB `data`.
- **Askable at once** in the scope you pass (`--scope`, default `system`), with the AI on.
- **Re-ingesting the same name refreshes** the table: rows deduplicate by key, new columns are added:

    ```
    $ lm csv ingest ./clients.csv --name quick-look --scope demo
    Re-ingested ./clients.csv into ext.quick_look — one more breath (rows dedup by key)
      • rows written:    3
      • promoted cols:   3
          - row_id (bigint)
          - email (text)
          - signup_date (text)

    A one-shot: no run recorded, nothing indexed for chat.
    The rows are in ext.quick_look, and a dormant connector is registered — see `lm connector list`.
    ```

    (AI off.) A different `--scope` moves the dataset to that scope.

- **A name held by a configured connector is refused:**

    ```
    $ lm csv ingest ./clients.csv --name articles
    Error: API error 409: the name 'articles' belongs to a configured connector — pass a different --name, or delete that connector first (lm connector delete)
    ```

- **A malformed row fails the whole ingest** and nothing is written. `lm` names the line:
  `Error: API error 400: malformed CSV row at line 3 — Record has 3 values but only 2 columns are declared`.
- **An uploaded file over 10 MB is refused** before anything is read:
  `Error: API error 413 (request too large): the hub accepts a request body up to 10 MB — put a larger CSV on sftp:// or s3://`.
- **Remote files** (sftp/s3) are fetched one by one, each up to 500 MB, and left in place.
- Delimiter and encoding are auto-detected per file.

!!! warning "`--name` needs a single file"
    `sftp://host/dir/` reads `*.csv` by default (`--pattern`), so a folder is several files.
    With `--name`, the hub refuses before downloading anything: `name 'clients' would ingest 3
    files into one dataset, which the one-shot path cannot register: …`. Three ways forward:

    - **one dataset per file** — drop `--name`; each file gets `ext.<filename>`.
    - **one specific file** — point the URL at it, or narrow `--pattern` / `--suffix`.
    - **the whole folder as one dataset** — a connector manifest with a glob path.

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
    $ lm secret set CSV_SFTP_PASSWORD
    Value for CSV_SFTP_PASSWORD:
    Created secret "CSV_SFTP_PASSWORD"
    ```

2. Apply the manifest — this one targets the docker-compose demo server:

    ```yaml
    --8<-- "docs/connectors/csv/30-sftp-key.yaml"
    ```

3. `lm connector run csv-sftp` reads every file under `/upload/seed/*.csv`. Once it looks
   right, add `schedule: "0 6 * * *"`, and `after_process: archive` with an `archive_path`.

Watch it in the TUI ([CLI & TUI](../cli/lm-reference.md)): `:runs` for the run's status, `:dlq`
for rejected rows.

### Ingest a headerless export under an S3 prefix, and archive it

```yaml
--8<-- "docs/connectors/csv/45-s3-headerless-archive.yaml"
```

**Result:** the [run ledger above](#s3-a-prefix-in-a-bucket-aws-minio-r2-gcs-s3-compat) — 19 009
rows on the first run, `IN 0` on the next because the file is in `lumnik-archive` now. Column 1
lands as `c_bpartner_id`, column 2 as `name`, and so on. For MinIO or another S3-compatible store,
add `endpoint:` — see `40-s3-prefix.yaml` in `docs/connectors/csv/`.

### Load one file for a quick look

```
$ lm csv ingest ./clients.csv
```

**Result:** a dormant connector plus a table `ext.clients`, askable in the `system` scope (AI on) —
inspect, then promote it into a manifest or `lm connector delete clients`.

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `SFTP host key refused for host:22 — the server presented SHA256:…` | `host_key_fingerprint` absent or wrong, with `strict_host_key: true` (the default) | confirm the presented fingerprint out of band (`ssh-keyscan -p 22 HOST \| ssh-keygen -lf -`), paste it into `host_key_fingerprint` |
| `SFTP error (SSH_FX_PERMISSION_DENIED): Permission denied` | wrong password/key, or the user cannot read `path` | `lm secret list`, then `lm secret set <NAME>` (a private key is many lines — pipe it with `<NAME> -`); check the folder's rights |
| `SFTP transport requires key_env or password_env` | neither credential declared | `lm secret set <NAME>`, then `password_env: <NAME>` or `key_env: <NAME>` |
| `S3 403 Forbidden` | invalid S3 credentials | check the secrets named by `access_key_env` / `secret_key_env` |
| `columns is required when has_header: false` | a headerless parser without names | `parser.columns: [a, b, …]` in file order |
| `after_process: archive is not executed on local connector runs` | a relocation on a local transport | `after_process: none`, or an sftp/s3 transport |
| `… runs only on one-shot lm csv ingest` | `file_filter`, `pre_process`, `post_process` or `max_file_size` in a connector manifest | drop the key |
| run `Failed`: `connector.t_… is already written by connector '…'` | another connector of the tenant already writes the table named after this one | apply it under another `metadata.name`, then `lm connector delete` the refused one — see [JDBC](jdbc.md#two-connectors-reading-a-table-of-the-same-name) |
| run completes with `IN 0` | nothing new — or the local path matches nothing on the **hub's** disk | `lm connector get NAME` shows the path (`config.transport.path`); check it exists on the hub |
| a file dropped in a local folder is never read | its name sorts before the last file read ([drop-folder contract](#local-the-hubs-disk-not-yours)) | name incoming files monotonically, or switch to sftp/s3 |
| run `Failed`: `The cursor's file … is gone, but files sorting after it are waiting` | a file was archived before the run that read it completed | `lm endpoint reset-cursor <connector>/<endpoint>`; archive only after `Completed` |
| `DEAD-LETTERS` > 0, reason `MalformedSource` | value count differs from the declared columns — trailing delimiter, unclosed quote | `lm dlq get ID` shows the raw values, file and line; fix the export or `parser:` |
| rows missing, DLQ empty | `filter-where` / `skip-empty-rows` excluded them (counted in `SKIPPED`) | see [Transformers § Troubleshooting](transformers.md#troubleshooting) |
| a mapped column is blank for some rows | a `parse-*` or `lookup` hook nulled a value it could not read | `cell_errors` on `lm run get <id>` — see [Transformers](transformers.md#reference-cell-hooks) |
| run `Failed`: `N of M rows would be removed: the file holds …` | the one local file lost more rows than `refresh.max_removed` allows | check the file; for a real purge raise `refresh.max_removed` (or `off`), re-apply, run again — see [full refresh](#one-file-is-the-table-full-refresh) |
| run `Failed`: `the file is empty: a full refresh never empties a table` | the one local file holds a header and no row | fix the export; to empty the table, `lm connector delete` |
| run `Failed`: `N row(s) of the file went to the dead letters this run` | a line of the one local file could not be read | `lm dlq list --run <id>`, fix the export, run again |
| run `Failed`: `the file changed while this run was reading it` | the producer rewrote the file mid-run | run again; have the producer write elsewhere and rename the file in |
| rows gone from a local table after a run | the file no longer holds them | `lm run get <id>` says `N row(s) removed`; to keep history, ingest a folder (`*.csv`) |
| a source column shares a reserved column name | one of the seven [reserved columns](#target-table) | rename it with `map-columns` |
| `lm csv ingest` fails with `API error 400: malformed CSV row at line N — …` | line N does not have the header's column count | fix that line, ingest again |

## See also

- [Overview](index.md) — the lifecycle every connector shares: validate → apply → run.
- [Transformers](transformers.md) — every hook, with input → output.
- [Scopes](../rag/scopes.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
