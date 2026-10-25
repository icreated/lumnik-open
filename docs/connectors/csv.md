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

The **hub** reads the manifest's `path:`, on its own disk. On the self-host stack, copy the sample
file into the hub's container, then set `path: /tmp/customers.csv` in your copy of the manifest:

```bash
docker compose -f docker-compose.selfhost.yml cp docs/connectors/csv/data/customers.csv hub:/tmp/customers.csv
```

A hub in dev mode reads the repository path as it is. From the repository root:

```
$ lm validate -f docs/connectors/csv/10-local-simple.yaml
✓ 10-local-simple.yaml — valid
$ lm apply -f docs/connectors/csv/10-local-simple.yaml
Applied connector "csv-local-simple" — 1 endpoint
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

- **A file already read is not read again** — the second run says `IN 0`, and `lm run get` adds
  `• 1 file(s) unchanged since they were last read — not read again`. A file that changed
  (replaced in place, or grown) is read again in full: the third run says `IN 3`, even when the copy
  kept the file's size and date.
- **The table deduplicates**: after three runs it holds 3 rows, not 5.
- `SKIPPED` and `DEAD-LETTERS` are explained [below](#malformed-rows-the-per-row-dlq).

## The three transports

The manifest's `transport:` block picks one:

| | `local` | `sftp` | `s3` |
|---|---|---|---|
| files are on | the **hub's** filesystem | a remote server | a bucket |
| selected by | path or glob | path or glob | `prefix` + `suffix` |
| a new run reads | one file: when it changed. A glob: new names sorting after the last one read | files modified since the last run | files modified since the last run |
| `after_process` | `none` only | all four | all four |
| credentials | — | `key_env` or `password_env` | `access_key_env` / `secret_key_env` |

### Local — the hub's disk, not yours

```yaml
transport:
  kind: local
  path: /imports/*.csv        # glob OK
```

- **The path is on the hub's filesystem.** The self-host stack mounts nothing: mount a volume,
  use `sftp`/`s3`, or upload one file with [`lm csv ingest`](csv-quick-look.md).
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

    - Have the producer write under a name the glob does not match (`.tmp`), then rename it.
    - Archive a file only after its run is `Completed`.

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

**One session per run.** Your server sees one SSH connection per running endpoint, for the whole
run. Mind its idle timeout and its limit on concurrent sessions.

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
read again, even under the same name and at the same size. Oldest first; a run that finds nothing new reads nothing.
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
  lines: false                  # true: one line = one row, one column `line` (fixed width, logs)
```

- **The encoding and the delimiter are guessed per file.** Declare `delimiter` or `encoding`
  when the guess is wrong.
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

- **A fixed-width file** (positions, not separators: mainframe extracts, bank or payroll files)
  is read one line per row with `lines: true`. Each line lands in one column, `line`. Then
  [`regex-extract`](transformers.md#regex-extract-pull-a-piece-into-its-own-column) cuts it by
  position. The file:

    ```
    --8<-- "docs/connectors/csv/data/payroll-fixed.txt"
    ```

    The manifest's parser and mapping:

    ```yaml
      parser:
        lines: true
      mapping:
        cell:
          - regex-extract:                     # employee 6, name 30, salary 10
              column: line
              pattern: "^(.{6})(.{30})(.{10})"
              groups: { 1: employee, 2: name, 3: salary }
          - clean: { columns: [employee, name, salary], strip: true }
          - parse-number: { column: salary, decimal-separator: "." }
    ```

    What lands:

    ```
    lumnik=> select employee, name, salary from connector.t_payroll;
     employee |     name      |  salary
    ----------+---------------+-----------
     E00001   | Martin Claire | 3250.0000
     E00002   | Dupont Jean   | 2980.5000
     E00003   | Nguyen Linh   | 4100.0000
    (3 rows)
    ```

    `lines: true` refuses `delimiter` and `has_header`. Full example:
    `docs/connectors/csv/73-lines-fixed-width.yaml`.

- **An empty value is a present column.** `1,` against two columns is written, the second value
  `null` (because `""` is in `null_values`; override the list to keep `""`).

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
ID        CONNECTOR      RUN       REASON           ATTEMPTS  RESOLUTION  MESSAGE
556c4da1  csv-malformed  0a4af97d  MalformedSource  1                     /tmp/exports/erp-export/c_bpartner_malformed.csv:6 — Record …
a2867c28  csv-malformed  0a4af97d  MalformedSource  1                     /tmp/exports/erp-export/c_bpartner_malformed.csv:4 — Record …
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
Resolution: open
```

**Malformed = the value count differs from the declared columns, in either direction:**

| the row | against 2 declared columns | result |
|---|---|---|
| `BADROW,"trop de colonnes","erp legacy glitch"` | 3 values | DLQ — `Record has 3 values but only 2 columns are declared` |
| `BADROW2` | 1 value | DLQ — `Record has 1 value but 2 columns are declared` |
| `1,` | 2 values, one empty | **written**, `name` is `null` |
| `1,2,` | 3 values — a trailing delimiter is one column too many | DLQ |
| `3,"open` (unclosed quote) | swallows the rest of the file | DLQ — one entry; the lines after it are never rows |

- **An extra value is never dropped to make the row fit.**
- **A trailing delimiter on every line sends the whole file to the DLQ** — strip it producer-side.
- **`SKIPPED` and `DEAD-LETTERS` move together** for malformed rows.
- Fix the export, run again, then close each entry:
  [Diagnosing a failed run](../recipes/diagnose-a-run.md#3-fix-re-run-close-the-loop).
- Rows a `validate-row` hook drops land here too, reason `ValidationFailed`.

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
Applied connector "articles" — 1 endpoint
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

The guards speak only when rows would be removed. A dead letter on a new row, with nothing to
remove, completes as usual (`IN 5  OUT 4  DEAD-LETTERS 1`).

**A changed row is not a removed row.** The run writes its new version and drops the old one.
The file still holds the row, so the run does not count it as removed, and the guard sees no
shrink. Ten articles, then the price of `A03` changed:

```
$ lm run get dd0a52c4
ID        CONNECTOR  ENDPOINT  STATUS     IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED             EVALUATED
dd0a52c4  articles   default   Completed  10  10   0        0             2026-10-25 11:17  2026-10-25 11:17  yes
```

The removed count is how much the table shrank. The rows have no key, so a row lost while
another is added looks like a changed row, and is not counted.

**What never removes a row:**

| the run | why |
|---|---|
| finds the file unchanged | it is not read again |
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

- **The archived name carries the file's modification time:** `livraisons.csv` read with its mtime at
  16:35:44 UTC on 22 October 2026 is archived as `livraisons.20261022T163544Z.csv`. An export that
  drops the same name every night keeps one archive per night; none replaces another. A source with
  sub-second times keeps them: on s3, `livraisons.20261022T163544.047Z.csv`.
- **Only the version that was read is archived.** Have the producer write under a name the glob
  does not match, then rename it in.
- **A file that cannot be archived is said on the run** — an archive folder the user cannot write,
  say. The file was read; it stays in the source folder, and `lm run get` says so:

```text
• 1 file(s) read but not archived — still in the source folder (after_process failed; see the hub log)
```

On a `local` transport, only `after_process: none` is accepted.

**A file is archived only after its rows are in the table** (sftp/s3).

**A misspelled key is flagged**, and the manifest still applies. Inside `mapping`, a misspelt tier is [refused](transformers.md#how-a-mapping-reads).

```
$ lm validate -f typo.yaml
! typo.yaml — 0 error(s), 1 warning(s)

  WARN     spec.windo
          unknown key — ignored by the runtime
          → Remove it, or check the spelling
```

## Target table

- One table per connector, `connector.t_<name>`, columns from the header (or `parser.columns`).
- **Reserved column names:** `id`, `_row_hash`, `_ingested_at`, `_source`, `_run_id`,
  `_mapping_version`, `tenant_id`, and `_seen_run` on a one-file table. A source column with one
  of these names is refused: rename it with
  [`map-columns`](transformers.md#map-columns-legacy-column-names-cust_nm).
- **Two identical lines are one row.** `OUT` counts the lines; the table holds distinct rows.
- **`target.indexes`** declares Postgres indexes — `{ columns: [...], unique: <bool> }`
  (`unique` defaults to false); a malformed entry fails validation:

    ```yaml
    target:
      indexes:
        - { columns: [email], unique: true }
        - { columns: [bpartner_id, dateordered] }
    ```

- **The business scope is on the connector**: `metadata.scopes` (see [Scopes](../rag/scopes.md));
  the validator warns when none is declared.
- **`lm connector delete` removes the connector's rows**, its run history and its RAG chunks
  (the table goes with the last connector writing into it):

    ```
    $ lm connector delete csv-local-simple --dry-run
    Would delete connector csv-local-simple (csv-file) — 3 rows of connector.t_csv_local_simple, its run history and RAG chunks (dry run, nothing deleted)
    ```

- **`lm endpoint purge` refuses a `csv-file` endpoint.** Delete the connector instead.

## Transform at ingestion

An optional `mapping` block repairs legacy data as it flows in — dataset → row → cell hooks,
in that order, on every connector run:

```yaml
mapping:
  cell:
    - fix-mojibake: { columns: [name, city] }      # "BÃ©nÃ©dict" → "Bénédict"
```

Every hook, by business problem: [Transformers](transformers.md). A value a hook cannot read is
emptied, the row is still written, and the run counts it in `cell_errors`.

## One file, one command: `lm csv ingest`

To look at one export before writing a manifest, `lm csv ingest` loads it in one command. It
does not schedule, transform or set rows aside: [One file, one command](csv-quick-look.md).

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

## Troubleshooting

| Symptom | Cause | Fix |
|---|---|---|
| `SFTP host key refused for host:22 — the server presented SHA256:…` | `host_key_fingerprint` absent or wrong, with `strict_host_key: true` (the default) | confirm the presented fingerprint out of band (`ssh-keyscan -p 22 HOST \| ssh-keygen -lf -`), paste it into `host_key_fingerprint` |
| `SFTP error (SSH_FX_PERMISSION_DENIED): Permission denied` | wrong password/key, or the user cannot read `path` | `lm secret list`, then `lm secret set <NAME>` (a private key is many lines — pipe it with `<NAME> -`); check the folder's rights |
| `SFTP transport requires key_env or password_env` | neither credential declared | `lm secret set <NAME>`, then `password_env: <NAME>` or `key_env: <NAME>` |
| `S3 403 Forbidden` | invalid S3 credentials | check the secrets named by `access_key_env` / `secret_key_env` |
| `columns is required when has_header: false` | a headerless parser without names | `parser.columns: [a, b, …]` in file order |
| `after_process: archive is not executed on local connector runs` | a relocation on a local transport | `after_process: none`, or an sftp/s3 transport |
| `… is not read by connector runs` | `file_filter`, `pre_process`, `post_process` or `max_file_size` in a connector manifest | drop the key |
| run `Failed`: `connector.t_… is already written by connector '…'` | another connector of the tenant already writes the table named after this one | apply it under another `metadata.name`, then `lm connector delete` the refused one — see [JDBC](jdbc.md#two-connectors-reading-a-table-of-the-same-name) |
| run completes with `IN 0` | nothing new — or the local path matches nothing on the **hub's** disk | `lm connector get NAME` shows the path (`config.transport.path`); check it exists on the hub |
| a file dropped in a local folder is never read | its name sorts before the last file read ([drop-folder contract](#local-the-hubs-disk-not-yours)) | name incoming files monotonically, or switch to sftp/s3 |
| run `Failed`: `The cursor's file … is gone, but files sorting after it are waiting` | a file was archived before the run that read it completed | `lm endpoint reset-cursor <connector>/<endpoint>`; archive only after `Completed` |
| `DEAD-LETTERS` > 0, reason `MalformedSource` | value count differs from the declared columns — trailing delimiter, unclosed quote | `lm dlq get ID` shows the raw values, file and line; fix the export or `parser:` |
| rows missing, DLQ empty | `filter-where` / `skip-empty-rows` excluded them (counted in `SKIPPED`) | see [Transformers § Troubleshooting](transformers.md#troubleshooting) |
| a mapped column is blank for some rows | a `parse-*` or `lookup` hook nulled a value it could not read | `cell_errors` on `lm run get <id>` — see [the reference](transformers-reference.md#when-a-hook-cannot-deliver-cell_errors-and-on-error) |
| run `Failed`: `N of M rows would be removed: the file holds …` | the one local file lost more rows than `refresh.max_removed` allows | check the file; for a real purge raise `refresh.max_removed` (or `off`), re-apply, run again — see [full refresh](#one-file-is-the-table-full-refresh) |
| run `Failed`: `the file is empty: a full refresh never empties a table` | the one local file holds a header and no row | fix the export; to empty the table, `lm connector delete` |
| run `Failed`: `N row(s) of the file went to the dead letters this run` | a line of the one local file could not be read | `lm dlq list --run <id>`, fix the export, run again |
| run `Failed`: `the file changed while this run was reading it` | the producer rewrote the file mid-run | run again; have the producer write elsewhere and rename the file in |
| rows gone from a local table after a run | the file no longer holds them | `lm run get <id>` says `N row(s) removed`; to keep history, ingest a folder (`*.csv`) |
| a source column shares a reserved column name | one of the [reserved columns](#target-table) | rename it with `map-columns` |

## See also

- [Overview](index.md) — the lifecycle every connector shares: validate → apply → run.
- [Transformers](transformers.md) — every hook, with input → output.
- [Scopes](../rag/scopes.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
