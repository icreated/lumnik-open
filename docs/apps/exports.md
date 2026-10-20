# kind: Export — deliver an entity as CSV

> A `kind: Export` manifest is a stored delivery contract: **one canonical entity, the columns
> you pick (renamed and formatted for the receiver if you want), one CSV, one destination**, on
> demand or on a cron. Each execution is a **managed
> run**, recorded from claim to receipt. The file is published atomically and **never
> overwrites** anything.

| | |
|---|---|
| **Kind** | `Export` (`apiVersion: apps.lumnik.io/v1`, strictly checked) |
| **Source** | one [entity](../entities.md) |
| **Format** | CSV, UTF-8 |
| **Shaping** | header names, per-column formatters, a row filter ([below](#shape-the-file-for-a-partner)) |
| **Destinations** | `sftp`, `s3` |
| **Schedulable** | cron, UTC |
| **Role** | `lm_integrator` for everything, reads included |

## Quickstart — apply, run, read the receipt

```yaml
--8<-- "docs/apps/exports/10-sftp-daily.yaml"
```

Store the private key once, then apply and run. The key is a multi-line PEM, so it goes in
through stdin:

```
$ lm secret set PARTNER_SFTP_KEY - < partner_ed25519
Created secret "PARTNER_SFTP_KEY"
$ lm export apply -f docs/apps/exports/10-sftp-daily.yaml
Applied export "customers-daily" → entity AcmeCustomer, scope desk
$ lm export run customers-daily --wait
• export run 3f1c0a2b-9d4e-4b7a-a1c2-6e5f0d9b8c71 accepted — waiting for the hub to record its outcome
Run           3f1c0a2b-9d4e-4b7a-a1c2-6e5f0d9b8c71
Export        customers-daily
Entity        AcmeCustomer
Scope         desk
Destination   sftp
Status        Completed
Trigger       manual
Attempt       1
Started       2026-09-24 10:02:11
Ended         2026-09-24 10:02:12
Rows          14
Bytes         1187
SHA-256       9c1e5a0f3b7d2e48a6c4f19d0b3e7a25c8f61d4e0a9b72c35e8d1f6a4b0c9e27
Location      /inbound/customers-2026-09-24.csv
```

- **`--wait` exits 0 only on `Completed`.** Without it, `lm export run` returns as soon as the
  hub has *accepted* the run (`accepted`; the run is `Running`, or `Queued` with the run queue on), which does not mean delivered.
- The `SHA-256` is computed over the exact bytes sent. Compare it with what the partner received.
- Times are shown in your terminal's time zone. The hub stores them in UTC.

## The manifest, field by field

| Field | Required | Rule |
|---|---|---|
| `metadata.name` | yes | the export's name, a slug, stored lower-cased (`Customers-Daily` becomes `customers-daily`), one per tenant. Re-applying the same name updates it |
| `spec.scope` | yes | one of the entity's declared `scopes`, and one you hold ([below](#scope-and-tenant)) |
| `spec.source.entity` | yes | an existing [entity](../entities.md), case-insensitive |
| `spec.columns` | yes | canonical columns of the entity, no duplicates, in file order. Each one a bare name, or `{name, as, format}` ([below](#shape-the-file-for-a-partner)). `_entity_id`, `_conflicts`, `_resolution` and `_handle` are refused |
| `spec.filter` | no | the rows to keep: `column` plus `equals` or `in` ([below](#keep-only-some-rows)) |
| `spec.order` | yes | the sort, columns taken from `spec.columns` ([ordering](#ordering-and-the-receipt)) |
| `spec.format.kind` | yes | `csv`, the only format |
| `spec.format.delimiter` | no | one character, default `,`. Not `"`, a line break or NUL |
| `spec.format.encoding` | no | `UTF-8`, the only value accepted |
| `spec.format.header` | no | `true` (default) or `false` |
| `spec.destination` | yes | `kind: sftp` or `kind: s3`, [below](#destinations) |
| `spec.mode` | yes | `snapshot`, the only mode |
| `spec.schedule` | no | a 5-field UNIX cron, evaluated in UTC. Omit it for manual runs only |

Everything is checked at apply time: unknown keys, wrong types, a column the entity does not have,
a scope it does not declare, a bad cron or an unknown `${…}` token. The error names the field:

```
$ lm export apply -f customers-daily.yaml
Error: API error 400: spec.columns: 'phone' is not a canonical column of entity 'AcmeCustomer'
```

## Destinations

**Credentials are never inline.** `password`, `private_key`, `access_key` and `secret_key` are
refused and the error names the `*_env` field to use instead. A `*_env` field holds the **name**
of a secret stored with [`lm secret set`](../admin-console-guide.md), never the value:

```
$ lm export apply -f leaky.yaml
Error: API error 400: 'password' is not accepted — store the credential via `lm secret set` and reference it as `password_env`
```

### SFTP

```yaml
destination:
  kind: sftp
  host: sftp.partner.example
  port: 22                          # optional, default 22
  user: lumnik
  path: /inbound/customers-${date}.csv
  key_env: PARTNER_SFTP_KEY         # OR password_env, exactly one of the two
  host_key_fingerprint: "SHA256:M0Is0aG8ubOr0cLpQs6QcDwVSxMkvmlfuW4K9Q6CJuc"   # required
```

- **The host key is always verified.** There is no `strict_host_key: false` on an export. Get the
  fingerprint from a machine you trust:
  `ssh-keyscan -p 22 sftp.partner.example | ssh-keygen -lf -`.
- The directory in `path` must exist. The hub does not create it.
- The file is written to `customers-2026-09-24.csv.<uuid>.part` in the same directory, then
  renamed to the final name. The rename refuses to replace an existing file.

### S3

```yaml
--8<-- "docs/apps/exports/20-s3-hourly.yaml"
```

- The object key is `prefix` + `filename`. `customers` and `customers/` both give
  `customers/x.csv`. A leading `/` is refused.
- `region` defaults to `eu-west-3`. Set `endpoint` only for S3-compatible stores (MinIO, R2…).
- `access_key_env` / `secret_key_env` default to `AWS_ACCESS_KEY_ID` / `AWS_SECRET_ACCESS_KEY`.
- The object is committed with `If-None-Match: *`, so an existing key is never replaced. The store
  must support conditional writes. AWS S3 does.
- The upload is multipart above 8 MiB. Until it completes, nothing is visible under the key.
- A write-only bucket works: a `403` on the pre-check is treated as "absent".

### File-name tokens

`path` (SFTP), `prefix` and `filename` (S3) accept two tokens, resolved in **UTC** against the run's
*artifact time*: the moment you asked for a manual run, or the cron slot for a scheduled one.

| Token | Resolves to | Two runs the same day |
|---|---|---|
| `${date}` | `2026-09-24` | the second one collides |
| `${timestamp}` | `20260924T040000Z` | distinct names (one per second) |

A literal name (`/inbound/customers.csv`) succeeds **once**. Every later run ends in
`collision`. Pick the token that matches your cadence: `${date}` for daily, `${timestamp}` for
anything more frequent.

## Scope and tenant

- **An export belongs to its tenant.** Another tenant never sees it, even under the same name.
  Rows are read through RLS for that tenant only.
- **`spec.scope` binds it.** To apply, the scope must be one the entity declares. If you are bound
  to scopes, it must also be one of yours. To list, read or run an export, you need that scope too;
  otherwise you get the uniform `403 {"error":"scope not granted"}`. See [Scopes](../rag/scopes.md).
- **A scheduled run has no user.** Its tenant and scope come from the stored manifest.
- **Every run checks the definition again** before any byte moves. If the entity was dropped,
  the scope was removed from it or a column is gone, the run fails with `definition`. That is the
  same sentence the apply would give today.

## The CSV bytes

| | |
|---|---|
| Encoding | UTF-8, **no BOM** |
| Line ending | `CRLF` (`\r\n`), after every record including the last |
| Delimiter | `spec.format.delimiter`, default `,` |
| Quoting | **every non-null value is quoted**, header included. `"` inside a value is doubled |
| NULL | an empty, **unquoted** field |
| Empty string | `""` (quoted), so it stays distinct from NULL |
| Header | the `spec.columns` names in order, when `header: true` |
| No rows | a file with only the header, or a 0-byte file with `header: false`. It is still published |

With `delimiter: ";"`, two rows where the second has no city (each line ends in `\r\n`):

```
"code";"name";"city"
"P-1000";"HYDRAULIQUE RHONE";"Lyon"
"P-1005";"ATELIERS DU ""NORD""";
```

Values are the canonical text in the entity view, as-is, unless the column declares
[`format` steps](#shape-the-file-for-a-partner).

## Shape the file for a partner

A partner's file spec usually asks for its own header names, its own codes, its own number
shape, no readable personal data, and only some rows. Declare it in the manifest. The hub then
builds the partner's file itself, with the same receipt and never-overwrite guarantees:

```yaml
--8<-- "docs/apps/exports/40-partner-file.yaml"
```

```
$ lm export apply -f docs/apps/exports/40-partner-file.yaml
Applied export "partner-customers" → entity AcmeCustomer, scope desk
$ lm export run partner-customers --wait
…
Status        Completed
Rows          8
Bytes         587
SHA-256       9599dfa99244aebf1677c2b0251bd07b37688761e46cd481b1cba97432e73c01
Location      s3://acme-outbound/partner/customers-2026-10-15.csv
```

The file the partner receives (`;` delimiter, each line ends in `\r\n`):

```
"CUST_NO";"RAISON_SOCIALE";"CAT";"ENCOURS";"EMAIL"
"P-1000";"Hydraulique Lyon 1000";"A";"500,00";"co**********************.example"
"P-1001";"Roulements Bruxelles 1001";"B";"637,00";
"P-1003";"Pneumatique Porto 1003";"A";"911,00";"ge*********************.example"
"P-1004";"Étanchéité Kraków 1004";"B";"1048,00";
…
```

A column is written in one of two ways. A bare name exports it as the entity holds it. The
long form renames it, formats it, or both:

```yaml
columns:
  - code                      # as the entity holds it, header "code"
  - name: segment             # the entity's column
    as: CAT                   # the header in the file
    format:                   # steps, run top to bottom on each value
      - lookup: { table: { GOLD: A, SILVER: B } }
```

- Headers must be unique in the file.
- `order` and `filter` always use the entity's names (`segment`, not `CAT`), and sort or filter on
  the entity's values, before any formatting.
- An empty cell stays empty. No formatter touches it.
- A [date column](../entities.md#dates-across-sources) is written in UTC, `2026-07-01 00:00:00+00`
  or `2026-07-01`, whatever zone the hub runs in; `format-date` gives it the partner's shape. It sorts
  by date, and a `filter` on it with dates is equal as an instant: `equals: "2026-07-01"` keeps the
  rows at that day's midnight UTC, as in a view.
- The same data still gives the same file and the same SHA-256, so you can compare a retry. One
  exception: after a hub upgrade, a `format-date` with a `zone` can move an hour if that zone's
  daylight-saving rules changed.

### Keep only some rows

```yaml
filter:
  - column: segment
    in: [GOLD, SILVER]
  - column: city
    equals: Lyon
```

- Each entry is `column` plus `equals` (one value) or `in` (a list), never both. A row is kept only
  when **every** entry matches.
- `column` can be any column of the entity, even one you do not export.
- Values must match exactly what the entity holds: `Gold` is not `GOLD`. An empty cell never
  matches.
- Quote a value that looks like a number: `equals: "2024"`.

### Formatters

Four formatters exist. Each one below shows the manifest line, then what it writes.

#### `lookup`: your codes to the partner's codes

```yaml
format:
  - lookup: { table: { ACTIVE: A, CLOSED: C }, on-missing: error }
```

| Value | `on-missing` | Written |
|---|---|---|
| `ACTIVE` | any | `A` |
| `OTHER` | `keep` (default) | `OTHER` |
| `OTHER` | `UNKNOWN` (any other text) | `UNKNOWN` |
| `OTHER` | `error` | the run fails, nothing is published ([below](#what-is-checked-and-when)) |

`on-missing: ""` is refused when you apply: it would hand the partner empty cells without telling
anyone.

#### `format-date`: the partner's date shape

```yaml
format:
  - format-date: { pattern: dd/MM/yyyy HH:mm, zone: Europe/Paris }
```

| Value in the entity | `pattern` | Written |
|---|---|---|
| `2019-12-31` | `dd/MM/yyyy` | `31/12/2019` |
| `2004-10-18 23:42:39+00` | `dd/MM/yyyy HH:mm` | `18/10/2004 23:42` (UTC, the default) |
| `2004-10-18 23:42:39+00` | `dd/MM/yyyy HH:mm`, `zone: Europe/Paris` | `19/10/2004 01:42` |
| `2026-08-01 14:30:00` (no time zone in the value) | `HH:mm` | `14:30`: `zone` only converts a value that carries one |
| `2019-12-31` | `dd MMM yyyy` | `31 Dec 2019`: month names are in English |

Pattern letters: `dd` day, `MM` month, `yyyy` year, `HH` hour (00–23), `mm` minutes, `ss`
seconds. Capital **M** is the month, lowercase `mm` the minutes: mixing them up is the classic
mistake.

#### `format-number`: decimal comma, fixed decimals

```yaml
format:
  - format-number: { decimal-separator: ",", scale: 2 }
```

| Value | Parameters | Written |
|---|---|---|
| `1234.5600` | none | `1234.5600` |
| `1234.5600` | `decimal-separator: ","` | `1234,5600` |
| `1234.5650` | `decimal-separator: ","`, `scale: 2` | `1234,57` (rounded half up) |
| `-1234.5` | `scale: 0` | `-1235` |
| `42` | `scale: 2` | `42.00` |

No thousands separator is ever written.

#### `mask`: hide personal data

```yaml
format:
  - mask: { keep-first: 2, keep-last: 4 }      # mask-char: "*" by default
```

| Value | Parameters | Written |
|---|---|---|
| `4556123498761234` | `keep-last: 4` | `************1234` |
| `contact@example.com` | `keep-first: 2, keep-last: 4` | `co*************.com` |
| `1234` | `keep-last: 4` | `****` |

A value too short to keep anything is masked **entirely**.

### What is checked, and when

Every formatter and filter is checked when you apply. The error names the field:

```
$ lm export apply -f partner.yaml
Error: API error 400: spec.columns[3].format[0]: unknown formatter 'format-numbr' — expected one of [format-date, format-number, lookup, mask]
$ lm export apply -f partner.yaml
Error: API error 400: spec.filter[0].column: 'segmnt' is not a canonical column of entity 'AcmeCustomer'
```

A value a formatter cannot write **fails the run** (`generation`). No file is published: a
half-formatted column is worse than none. The reason names the column and the formatter, never
the value. Here, the same manifest without its filter meets a `BRONZE` customer, which the table
does not list:

```
$ lm export run partner-customers-all --wait
…
Status        Failed
Failure       generation
Reason        column 'segment', formatter 'lookup': no entry in table for this value
```

To find the value, look at the column as the entity holds it, then add the value to the table,
choose an `on-missing`, or filter the row out:

```
$ lm entity export AcmeCustomer -o - | cut -d, -f1,4 | grep -v -e GOLD -e SILVER
"code","segment"
"P-1002","BRONZE"
"P-1006","BRONZE"
"P-1009","BRONZE"
"P-1011",
"P-1012",
"P-1013",
```

`BRONZE` is the value the table lacks. The three customers with no segment are not a problem:
an empty cell never reaches the formatter, so it stays empty in the file.

`format-date` fails the same way on a value that is not a date, `2019-02-30` included: it is
never moved to a valid day. It also fails when `pattern` asks for a field the value lacks
(`HH:mm` on a date). `format-number` fails on a value that is not a number, or one with more
than 1 000 digits on either side of the separator.

`lm entity export` (the one-shot download) never formats or filters: it always gives every
column, as the entity holds it.

## Ordering and the receipt

- **The order is deterministic.** Rows are sorted by `spec.order`, then by the remaining
  `spec.columns` in their declared order. Each column is compared byte-wise (`COLLATE "C"`),
  with NULLs last.
- **The same data gives the same bytes and the same SHA-256.** A retry over unchanged data can be
  checked against the failed attempt's target, byte for byte.
- A completed run's receipt contains `rowCount`, `byteCount`, `sha256` and `location`. S3 adds
  `etag` and `versionId` (versioned bucket only).
- **The ETag is not a checksum.** It identifies the object and nothing more. Always compare the
  `sha256`.
- **There is no sidecar file.** The receipt lives in the run ledger (`lm export status`). A
  single-part S3 object (≤ 8 MiB) also carries the checksum as `x-amz-meta-lumnik-sha256`.

## Managed export vs one-shot download

| | `lm export run` (managed) | `lm entity export` (one-shot) |
|---|---|---|
| Manifest | a `kind: Export` | none |
| Columns, order, format | from the manifest | all canonical columns, in declared order, default format (`,`, header) |
| Goes to | the SFTP / S3 destination | your machine |
| Ledger, retry, schedule | yes | no. Audited as data egress (rows, bytes, SHA-256) |

Use the one-shot for a quick look or a one-off hand-over:

```
$ lm entity export AcmeCustomer -o customers.csv
AcmeCustomer: 1099 bytes written to customers.csv (sha256 afa5c455ceaf9a2c6e0fae54b2fbc9b671893f22220c2c2c6a685969c0deceb5)
$ lm entity export AcmeCustomer -o customers.csv
Error: customers.csv already exists — refusing to overwrite it (use --force to replace it)
$ lm entity export AcmeCustomer -o - | head -3
```

- The download goes to a hidden `.customers.csv.*.part` and gets its final name only once the
  whole body has arrived. An interrupted download leaves nothing behind.
- An existing file is never replaced without `--force`. The file is created `0600`.
- With `-o -`, bytes already written to stdout cannot be taken back. On failure the command
  still exits non-zero.

## Runs from `lm`

```
$ lm export list
NAME              ENTITY        SCOPE  DESTINATION  SCHEDULE
customers-daily   AcmeCustomer  desk   sftp         0 4 * * *
customers-hourly  AcmeCustomer  desk   s3           0 * * * *
$ lm export runs customers-daily
ID        STATUS     TRIGGER   ATTEMPT  ROWS  BYTES  STARTED           ENDED             FAILURE
c7d0e913  Failed     manual    1                   2026-09-24 11:30  2026-09-24 11:30  collision
3f1c0a2b  Completed  manual    1        14    1187   2026-09-24 10:02  2026-09-24 10:02
$ lm export status customers-daily           # the latest run in full; add a RUN_ID for another
$ lm export retry customers-daily 5e8a41f0 --wait
$ lm export get customers-daily              # the stored manifest
```

- `--timeout` (default 30m) and Ctrl-C only stop the **waiting**. The run carries on on the hub.
- `-o json|yaml` gives every command a stable machine-readable shape for scripts.
- Short ids from `lm export runs` are accepted everywhere.

### Stop a schedule

There is no `lm export rm` or `disable`. To stop the scheduled runs, re-apply the manifest
without `spec.schedule`:

```
$ lm export apply -f customers-daily.yaml        # the same manifest, schedule: line removed
Applied export "customers-daily" → entity AcmeCustomer, scope desk
$ lm export list
NAME             ENTITY        SCOPE  DESTINATION  SCHEDULE
customers-daily  AcmeCustomer  desk   sftp         
```

The export stays, for `lm export run` on demand, with its run history.

## Failures, and whether to retry

A run is `Running`, then exactly one of `Completed` or `Failed` (with the run queue on, it is `Queued` first). A failure carries a
`failureCode` and a `failureReason` written by the hub. The reason never contains a credential or a raw
third-party message.

| `failureCode` | What happened | What is on the remote side | Safe to retry? |
|---|---|---|---|
| `definition` | entity dropped, scope removed from the entity, column gone | nothing | after fixing the entity or the manifest |
| `generation` | reading or encoding the snapshot failed | nothing (temporary file removed, multipart aborted) | yes |
| `publication` | upload or commit refused (credentials, missing directory, network, host key) | nothing | yes, once the cause is fixed |
| `collision` | the final name already exists | the existing file, untouched | **no, look first** |
| `interrupted` | the hub stopped mid-run (crash, restart) | possibly a leftover, [below](#recovering-from-a-crash) | yes, and a `collision` then means it was delivered |

```
$ lm export status customers-daily c7d0e913
…
Status        Failed
Failure       collision
Reason        SFTP destination: /inbound/customers-2026-09-24.csv already exists — refusing to overwrite a previously delivered artifact
✖ export run c7d0e913-… failed (collision): … — retry with: lm export retry customers-daily c7d0e913
```

**What a retry does.** It creates a new run with `retryOf` set and `attempt` + 1. It regenerates the file
**from byte 0**, since there is no resume. It keeps the original artifact time, so it aims at the
**same file name**. So:

- A retry is always safe: it can never overwrite. At worst it ends in `collision`.
- **`collision` on a retry means the earlier attempt did publish**, even though the ledger says it failed.
  Compare the remote file's SHA-256 with a fresh one-shot (`lm entity export … -o - | sha256sum`,
  same columns and order). Then either leave it, or move it aside on the remote and retry.
- Only `Failed` runs can be retried. A scheduled slot that was delivered since answers
  `409 {"error":"already delivered"}`.

## Scheduling and concurrency

- The scheduler checks every minute and runs the export's **latest due slot, once**.
- **There is no back-fill.** After an outage, only the latest slot runs. An export applied after
  today's slot waits for the next one.
- **There is no automatic retry.** A failed slot stays `Failed` until someone runs `lm export retry`.
  The next slot runs normally.
- **Only one run is in flight per export.** A second trigger gets `409 … already has a run in flight`.
  A slot that is due while a manual run is going is picked up on a later tick, if it is still the latest.
- **With several hub replicas, a slot is still delivered only once.**
- When several slots are due, the one owed longest starts first. A due slot that finds no free
  place is picked up on a later tick, if it is still the latest.

| Property (environment variable) | Default | Bounds |
|---|---|---|
| `lumnik.export.max-concurrent-scheduled-runs` (`LUMNIK_EXPORT_MAX_CONCURRENT_SCHEDULED_RUNS`) | 2 | scheduled runs in flight per hub instance. With the run queue off, `lm export run` and `retry` do not count |
| `quarkus.datasource."admin".jdbc.max-size` | 20 | the `admin` pool; each run holds one connection for its whole life — raise it with the bound above |
| `lumnik.export.snapshot.timeout-seconds` | 1800 | the time a run has to generate its snapshot |

### Runs can queue

With `LUMNIK_RUN_QUEUE_ENABLED=true` (off by default; read
[Turning on the run queue](../operations/upgrade.md#turning-on-the-run-queue) first):

- `lm export run` and `lm export retry` leave a run in status `Queued`; the hub starts it as a
  slot frees, on the `LUMNIK_EXPORT_MAX_CONCURRENT_SCHEDULED_RUNS` budget.
- `lm export runs` lists it `Queued`, with no start time yet.
- `--wait` waits through `Queued` to the outcome.

- A queued manual run's `${date}` and `${timestamp}` file-name tokens are fixed when the hub accepts it, not when it
  starts: a run queued at 23:59 and started after midnight keeps the previous day's name.
- An export holds at most one queued-or-running run: a second request gets `409 … already has a run in flight`,
  naming the queued run.
- A run not started within `LUMNIK_RUN_QUEUE_MAX_AGE` (default `1h`) ends `Failed`, failure `interrupted`, "expired in
  queue". One whose manifest was re-applied while it waited ends `Failed`, failure `definition`.
- There is no export cancel door: a queued run cannot be cancelled and leaves the queue by starting, by failing on
  its definition, or by expiring.
- While a queue stands, it can win the permit race against a due scheduled slot. That slot is skipped, tried again
  on the next tick, and dropped when the next occurrence replaces it (there is no back-fill).

## Recovering from a crash

A run whose hub died is closed as `Failed / interrupted` within a minute (at startup, and on every
scheduler tick). Run `lm export retry`: it either delivers or ends in `collision`, and a
`collision` means the lost run had already published. The hub cannot clean up what the lost run
left on the remote side:

- **SFTP.** A `<name>.<uuid>.part` file stays next to the target. It never takes the final
  name. Remove it by hand, or with a housekeeping job that deletes `*.part` files older than a day.
- **S3.** An incomplete multipart upload stays invisible but its parts are **billed**. Add a
  lifecycle rule to the bucket once:

    ```json
    {
      "Rules": [{
        "ID": "abort-incomplete-exports",
        "Status": "Enabled",
        "Filter": {"Prefix": ""},
        "AbortIncompleteMultipartUpload": {"DaysAfterInitiation": 1}
      }]
    }
    ```

## Not in this version

- **Never overwrites** and never appends. A delivered name is final.
- **No resume.** A retry rewrites the whole file.
- **No delta or incremental export.** `mode: snapshot` means the full entity every time.
- **CSV only**, UTF-8 only. No JSON, Parquet or Excel, no compression, no encryption.
- **One entity per export.** No joins. The filter knows only `equals` and `in`: no ranges, no
  patterns, no `OR` between entries.
- **Four formatters.** No computed or constant columns, no fixed-width output, no inbound repair
  hooks (`fix-mojibake`, `parse-*`). Clean the data on the way in, and shape it on the way out.
- **No other destinations:** no local disk, email or HTTP push. No sidecar or `.done` marker file.
- **No delete or disable** of an export: [stop its schedule](#stop-a-schedule) by re-applying it
  without `spec.schedule`.
- No back-fill of missed slots and no automatic retry.

## See also

- [Entities](../entities.md): what an export reads.
- [Scopes](../rag/scopes.md): what `spec.scope` binds.
- [Secrets](../admin-console-guide.md): `lm secret set` for `*_env` names.
- [CSV & files (SFTP/S3)](../connectors/csv.md): the same transports, on the way in.
- [CLI reference](../cli/lm-reference.md): every `lm export` flag.
