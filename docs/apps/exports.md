# kind: Export — deliver an entity as CSV

> A `kind: Export` manifest is a stored delivery contract: **one canonical entity, the columns
> you pick, one CSV, one destination**, on demand or on a cron. Each execution is a **managed
> run**, recorded from claim to receipt. The file is published atomically and **never
> overwrites** anything.

| | |
|---|---|
| **Kind** | `Export` (`apiVersion: apps.lumnik.io/v1`, strictly checked) |
| **Source** | one [entity](../entities.md) |
| **Format** | CSV, UTF-8 |
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
| `spec.columns` | yes | canonical columns of the entity, no duplicates, in file order. `_entity_id`, `_conflicts`, `_resolution` and `_handle` are refused |
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

Values are the canonical text in the entity view, as-is. There is no type formatting and no
date conversion.

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
AcmeCustomer: 1402 bytes written to customers.csv (sha256 4b0e…)
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

The same actions are available over HTTP (`lm_integrator`):

| Method and path | Answer |
|---|---|
| `POST /api/exports/apply` | apply a manifest (YAML body) |
| `POST /api/exports/{name}/runs` | `202 {"runId": "…"}` once claimed. `409` if a run is in flight |
| `GET /api/exports/{name}/runs?limit=50` | runs, newest first (limit 1..200) |
| `GET /api/exports/{name}/runs/{id}` | one run |
| `POST /api/exports/{name}/runs/{id}/retry` | `202` for a `Failed` run. `409` otherwise |
| `GET /api/exports/entities/{entity}/download` | the one-shot CSV |

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
- **At most 2 scheduled runs are in flight per hub instance** (`lumnik.export.max-concurrent-scheduled-runs`,
  environment variable `LUMNIK_EXPORT_MAX_CONCURRENT_SCHEDULED_RUNS`). A due slot that finds both taken
  writes nothing and is picked up on a later tick, if it is still the latest. Each run holds one connection of
  the hub's `admin` pool (20 by default) for its whole life: raise the pool
  (`quarkus.datasource."admin".jdbc.max-size`) along with the bound.
  With the run queue off (the default), `lm export run` and `lm export retry` do not count against it and
  never wait for it. When several slots are due, the one owed longest starts first.
- A run has 30 minutes to generate its snapshot (`lumnik.export.snapshot.timeout-seconds`).

### Runs can queue

With `LUMNIK_RUN_QUEUE_ENABLED=true` (off by default; read
[Turning on the run queue](../operations/upgrade.md#turning-on-the-run-queue) first), `lm export run`,
`lm export retry` and their doors (`POST /api/exports/{name}/runs` and `POST /api/exports/{name}/runs/{id}/retry`) answer 202 with a run in status `Queued`, and the
hub starts it as a slot frees — on the same `LUMNIK_EXPORT_MAX_CONCURRENT_SCHEDULED_RUNS` budget as scheduled
exports. `lm export runs` lists it `Queued`, with no start time yet; only `--wait` (on `lm export run` and `lm export retry`) waits for its
outcome, polling through `Queued`.

- A queued manual run's `${date}` and `${timestamp}` file-name tokens are fixed when the hub accepts it, not when it
  starts: a run queued at 23:59 and started after midnight keeps the previous day's name.
- An export holds at most one queued-or-running run: a second request gets `409 … already has a run in flight`,
  naming the queued run.
- A run not started within `LUMNIK_RUN_QUEUE_MAX_AGE` (default `1h`) ends `Failed`, failure `interrupted`, "expired in
  queue". One whose export was deleted, or whose manifest was re-applied while it waited, ends `Failed`, failure
  `definition`, rather than running a definition nobody asked for.
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
- **One entity per export.** No joins and no row filter; `spec.columns` is the only projection.
- **No other destinations:** no local disk, email or HTTP push. No sidecar or `.done` marker file.
- No back-fill of missed slots and no automatic retry.

## See also

- [Entities](../entities.md): what an export reads.
- [Scopes](../rag/scopes.md): what `spec.scope` binds.
- [Secrets](../admin-console-guide.md): `lm secret set` for `*_env` names.
- [CSV & files (SFTP/S3)](../connectors/csv.md): the same transports, on the way in.
- [CLI reference](../cli/lm-reference.md): every `lm export` flag.
