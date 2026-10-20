# REST

> Ingest any HTTP/JSON API: point at the base URL, declare the endpoints and how they page,
> apply. The rows land as typed columns plus the untouched JSON payload.

| | |
|---|---|
| **Type** | `rest-generic` |
| **Protocol** | HTTP/JSON (+ the GraphQL and SOAP request shapes) |
| **Target** | the table each endpoint declares in `target.table` (`ext.*`) |
| **Schedulable** | cron |
| **Edition** | Open |

Every endpoint declares its own `method`; a source usually needs `GET`. The connector sends
only the requests the manifest declares.

## Quickstart

!!! note "Every screen on this page reads a demo API that ships with the repo"
    One Python file, no dependencies, 2 400 fictional ACME partners under ten path dialects.
    Start it in a terminal of its own, and put its three credentials in the hub's registry:

    ```bash
    python3 docs/connectors/rest/demo/acme-api.py
    lm secret set ACME_BASIC_PW        # demo-pass
    lm secret set ACME_BEARER_TOKEN    # demo-token
    lm secret set ACME_API_KEY         # demo-key
    ```

    The manifests point at `http://host.docker.internal:8099`, because the hub runs in a
    container and `localhost` inside it is that container. What each dialect imitates, and
    which ones misbehave on purpose: [The demo API](rest/demo-api.md).

```yaml
--8<-- "docs/connectors/rest/demo/10-quickstart.yaml"
```

```
$ lm validate -f docs/connectors/rest/demo/10-quickstart.yaml
✓ 10-quickstart.yaml — valid
$ lm apply -f docs/connectors/rest/demo/10-quickstart.yaml
{"name":"acme-partners","connectorId":"ff212939-378b-446c-adf5-d3356f85e9ff","endpointsApplied":1}
```

Before running anything, send the first request a run would send:

```
$ lm source rest test acme-partners
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  GET     200     500    ok
```

`ITEMS` is the number of records read from that one page (the manifest asks for 500). Nothing
is written. Now run it:

```
$ lm connector run acme-partners
run scheduled: 9deb9cb6-d9bc-427f-ba27-5af060647f19
$ lm run list --connector acme-partners
ID        CONNECTOR      ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
9deb9cb6  acme-partners  partners  Completed  2400  2400  0        0             2026-10-19 01:39  2026-10-19 01:39
```

`STARTED` / `ENDED` print in your terminal's time zone; `schedule` is evaluated in UTC.

What landed in `ext.acme_partners` — the promoted columns, beside the whole payload:

```
lumnik=> SELECT external_id, name, city, credit_limit, updated_at
lumnik->   FROM ext.acme_partners ORDER BY external_id LIMIT 4;
 external_id |           name            |   city    | credit_limit |       updated_at       
-------------+---------------------------+-----------+--------------+------------------------
 1000        | Hydraulique Lyon 1000     | Lyon      |          500 | 2026-01-01 00:00:00+00
 1001        | Roulements Bruxelles 1001 | Bruxelles |          637 | 2026-01-01 00:01:00+00
 1002        | Fixations Milano 1002     | Milano    |          774 | 2026-01-01 00:02:00+00
 1003        | Pneumatique Porto 1003    | Porto     |          911 | 2026-01-01 00:03:00+00
(4 rows)
```

- **`promote` is a projection, not a filter.** Every field the source returned is in `data`
  (JSONB); the promoted ones are also copied into typed columns.
- **`unique_key` makes a re-run safe.** It becomes `external_id`, unique per tenant, and every
  write is an upsert — see [Storage layout](#storage-layout).
- **The password is never in the manifest.** `password_env` names an `lm secret` entry (or, if
  none, an environment variable on the hub).

## The manifest

```yaml
spec:
  base_url: https://api.example.com
  auth: { kind: bearer, token_env: EXAMPLE_TOKEN }
  default_rate_limit: 100/s
  schedule: "0 6 * * *"          # optional cron, evaluated in UTC by the hub scheduler
  window: "22:00-06:00"          # optional fence, UTC, end exclusive, may cross midnight
  graphql_path: /graphql         # only for GraphQL endpoints; this is the default
  headers:
    - { name: X-Api-Version, value: "2026-01" }
  endpoints:
    - id: charges                # kebab-case, unique in the connector
      method: GET                # GET | POST | PUT | PATCH | DELETE
      path: /v1/charges
      pagination: { kind: stripe }
      incremental: { mode: full }          # optional, this is the default
      target:
        kind: hybrid
        table: ext.example_charges
        unique_key: $.id
        promote:
          - { name: amount, path: $.amount, type: bigint }
```

- The path-shaped fields (`cursor_path`, `response_path`, `unique_key`, `link_path`, …) are
  JSONPath read against the response body: `$.data[-1:].id` is "the `id` of the last element
  of `data`".
- A `promote` entry may carry a `transform:` — the list is in
  [Transformers](transformers.md#rest-the-transform-enum). `parse_iso8601` turns an API
  timestamp into a `timestamptz`.
- Without `schedule`, the connector runs only on `lm connector run`. `window` fences scheduled
  runs: a slot due outside it is skipped, never queued. A manual run ignores the window.

An unknown key at the connector level of `spec` comes back as a warning; the manifest still
applies:

```
$ lm validate -f my-api.yaml
! my-api.yaml — 0 error(s), 1 warning(s)

  WARN     spec.headerz
          unknown key — ignored by the runtime
          → Remove it, or check the spelling
```

## One connector, one API

One connector is one API, whatever number of endpoints it exposes. Two endpoints of the same
API may page by different conventions:

```yaml
--8<-- "docs/connectors/rest/demo/20-two-endpoints.yaml"
```

```
$ lm run list --connector acme-saas
ID        CONNECTOR  ENDPOINT         STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
9d7a4883  acme-saas  link-partners    Completed  2400  2400  0        0             2026-10-19 01:40  2026-10-19 01:40
3531807d  acme-saas  cursor-partners  Completed  2400  2400  0        0             2026-10-19 01:40  2026-10-19 01:40
```

The ledger records **one run per endpoint**. `stripe` and `drf-page` are two of the
[presets](rest/pagination.md#presets).

## Auth

| Kind | Config |
|---|---|
| `bearer` | `token_env: VAR_NAME` (preferred), or `token_value: "..."` (dev only) |
| `basic` | `username` + `password_env: VAR_NAME` |
| `api-key` | `in: header\|query`, `name`, `value_env: VAR_NAME` |
| `none` | no credentials (the default when `auth:` is omitted) |

Every auth `*_env` field names an [`lm secret`](../cli/lm-reference.md#data-runs-answers) entry
first, then an environment variable / JVM system property of that name on the hub. Set the
value before `lm apply`:

```bash
lm secret set ACME_BASIC_PW     # prompts at the keyboard; what you type is not shown
```

- `basic` and `api-key` have **no inline field**: the value comes from `password_env` /
  `value_env`, or not at all.
- `bearer`: when both are set, `token_env` wins and `token_value` is ignored, even if nothing
  resolves under `token_env`. Declare one or the other.

A credential that does not resolve is named before any run:

```
$ lm source rest test acme-badauth
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  GET     -       0      Basic password unavailable (env=ACME_NOT_SET_PW)
Error: 1 of 1 enabled endpoint(s) did not answer with readable data
```

## Pagination

Every endpoint declares how the source hands back its next page. Most APIs follow a named
convention, so the usual answer is one line:

```yaml
pagination: { kind: spring-page }        # Spring Data Page — seven fields under one word
```

Eleven presets name the framework behind the API (Spring Data, Micronaut, Django REST,
FastAPI, NestJS, JSON:API, Sequelize, Stripe). When none fits, write one of six strategies
by hand: `cursor`, `page`, `offset`, `link-header`, `body-link`, `relay`.

**→ [Pagination](rest/pagination.md)** — the catalogue, the fields of each strategy, where the
cursor is injected, page size on the strategies that send none, and `kind: custom`.

## Request headers

Connector-level only — `spec.headers[]` goes out on **every** request:

```yaml
spec:
  headers:
    - { name: X-Api-Version, value: "2026-01" }       # static
    - { name: X-Client-Id,   value_env: CLIENT_ID }   # from the environment
```

- Each entry needs `name` + **exactly one** of `value` / `value_env`.
- `value_env` reads the hub's OS environment, then a JVM system property. It does **not** read
  the `lm secret` registry.
- A `value_env` that resolves to nothing drops the header, without a warning.
- Headers are applied **before** auth — an auth-injected header wins a name clash.
- Endpoint-level `headers` are refused:

```
$ lm apply -f bad-headers.yaml
✗ bad-headers.yaml — 1 error(s), 0 warning(s)

  ERROR    spec.endpoints[0].headers
          per-endpoint headers are not supported in this release; declare headers at spec.headers
          → Move the headers block up to spec.headers (connector level)

Fix errors above before running 'lm apply -f bad-headers.yaml'.
Error: validation failed: 1 error(s)
```

`lm source rest test` sends the declared headers too. `/hdr/partners` answers 400 without
`X-Api-Version`; the `acme-witness` connector (manifest under
[The declared total](#the-declared-total-a-witness-on-the-count)) declares it:

```
$ lm source rest test acme-witness
ENDPOINT    METHOD  STATUS  ITEMS  VERDICT
incomplete  GET     200     500    ok
versioned   GET     200     500    ok
```

## Incremental sync

```yaml
incremental:
  mode: full              # re-fetch every page (default)
  # OR
  mode: watermark         # send the stored watermark as a query parameter,
  param: updated_since    #   e.g. ?updated_since=<watermark>
  watermark_path: $.updated_at   # new watermark = MAX of this JSONPath over the items read
```

> `mode: api_cursor` behaves like `full`; `lm validate` warns about it.

The watermark is stored per endpoint and survives runs.

```yaml
--8<-- "docs/connectors/rest/demo/40-watermark.yaml"
```

Run it twice, and read the ledger:

```
$ lm run list --connector acme-incremental
ID        CONNECTOR         ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
9e6d1ec6  acme-incremental  partners  Completed  1     1     0        0             2026-10-19 01:40  2026-10-19 01:40
041df9fd  acme-incremental  partners  Completed  2400  2400  0        0             2026-10-19 01:40  2026-10-19 01:40
```

Run 1 had no mark, so it read all 2 400 rows. Run 2 sent the mark, as the demo API's log
shows:

```
19/Oct/2026 01:40:35  "GET /offset/partners?limit=500&updated_since=2026-01-02T15%3A59%3A00Z&offset=0 HTTP/1.1" 200 -
```

The one row sharing that instant came back (the filter is inclusive) and was re-upserted on
its key; the table still holds 2 400 rows.

- The mark advances only when the run completes, never page by page.
- `lm endpoint reset-cursor acme-incremental/partners` clears it; the next run is a full read.
  The command prints nothing and exits 0.
- Point `watermark_path` at a column the source updates on every change. Otherwise every run
  re-reads everything.

## Storage layout

Each endpoint's table is created on the first run. This section is reference.

```
                                           Table "ext.acme_partners"
    Column    |           Type           | Collation | Nullable |                    Default                    
--------------+--------------------------+-----------+----------+-----------------------------------------------
 id           | bigint                   |           | not null | nextval('ext.acme_partners_id_seq'::regclass)
 tenant_id    | bigint                   |           | not null | 
 external_id  | text                     |           | not null | 
 name         | text                     |           |          | 
 city         | text                     |           |          | 
 credit_limit | bigint                   |           |          | 
 updated_at   | timestamp with time zone |           |          | 
 row_hash     | text                     |           |          | 
 data         | jsonb                    |           | not null | 
 indexed_at   | timestamp with time zone |           |          | now()
 connector_id | uuid                     |           | not null | 
 endpoint_id  | uuid                     |           | not null | 
Indexes:
    "acme_partners_pkey" PRIMARY KEY, btree (id)
    "acme_partners_tenant_id_external_id_key" UNIQUE CONSTRAINT, btree (tenant_id, external_id)
    "idx_acme_partners_data_gin" gin (data jsonb_path_ops)
Policies:
    POLICY "tenant_isolation"
      USING ((tenant_id = (NULLIF(current_setting('app.current_tenant'::text, true), ''::text))::bigint))
```

| Column | Holds |
|---|---|
| `name`, `city`, `credit_limit`, `updated_at` | the promoted values |
| `external_id` | the `unique_key`; unique per tenant, so a re-run upserts instead of duplicating |
| `data` | the whole payload, GIN-indexed for JSONB path queries |
| `row_hash` | the payload's hash: a row whose payload and promoted values are unchanged is not rewritten |
| `tenant_id` | the row-level security policy keeps each tenant's rows invisible to the others |

A re-run rewrites only the rows that changed, yet `OUT` still counts every row read and
`SKIPPED` stays `0`. To check that nothing duplicated, count the table's rows.

A new `promote` entry adds its column on the next run — see
[Add a typed column later](#add-a-typed-column-later).

!!! warning "Deleting a REST connector deletes its data"
    `lm connector delete` removes the connector's rows from its `ext.*` tables (a table goes
    with the last connector writing into it), and every RAG chunk it produced. To empty one
    endpoint's rows and keep the connector, see [Empty one endpoint's rows](#empty-one-endpoints-rows).

## When the source misbehaves

The engine retries what is worth retrying, refuses to read an error body as data, and — where
the source publishes a count — checks the run against it.

### Retry, page cap, rate limit

| Guard | Behaviour |
|---|---|
| Retry | fixed, not configurable: up to **5 attempts** on HTTP `429, 500, 502, 503, 504, 408` and network errors; backoff `1s → 2s → 4s → …` capped at **60s**, ±10% jitter; a numeric `Retry-After` overrides the delay. Any other status: no retry |
| Page cap | **10 000 pages** per endpoint per run |
| Rate limit | `default_rate_limit` (default `100/s`): `count/window`, window `s`, `m` or `h` with an optional integer (`100/s`, `100/5m`, `1000/h`). One bucket per connector, shared by all its endpoints — set it to the strictest endpoint's limit |

### An outage fails the run

`/down/partners` answers 500 to everything; `/flaky/partners` answers 429 then 503 before
serving each page:

```yaml
--8<-- "docs/connectors/rest/demo/60-source-down.yaml"
```

```
$ lm run list --connector acme-outage
ID        CONNECTOR    ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
b3e0c1db  acme-outage  flaky     Completed  2400  2400  0        0             2026-10-19 01:40  2026-10-19 01:40
a05e8be8  acme-outage  down      Failed     0     0     0        0             2026-10-19 01:40  2026-10-19 01:40

$ lm run get a05e8be8
ID        CONNECTOR    ENDPOINT  STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
a05e8be8  acme-outage  down      Failed  0   0    0        0             2026-10-19 01:40  2026-10-19 01:40
✖ run failed: upstream returned HTTP 500 (transient) — refusing to ingest its body as data: {"error": "this endpoint is down and stays down"}
```

A final non-2xx fails the run; it is never reported as an empty sync. A status that succeeds
on retry does not — the flaky endpoint completes with all 2 400 rows.

The pre-flight sends **one** request without retry, so a flaky source shows red there:

```
$ lm source rest test acme-outage
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
flaky     GET     429     0      HTTP 429
down      GET     500     0      HTTP 500
Error: 2 of 2 enabled endpoint(s) did not answer with readable data
```

Every enabled endpoint is tried; the command exits non-zero if any did not answer.

### The declared total — a witness on the count

A run that loses rows can still end green. Where the source publishes its row count,
`total_path` checks the run against it. `/liar/partners` serves 2 300 of its 2 400 partners and
still declares `"total": 2400`:

```yaml
--8<-- "docs/connectors/rest/demo/50-total-witness.yaml"
```

```
$ lm run list --connector acme-witness
ID        CONNECTOR     ENDPOINT    STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
03b05ee0  acme-witness  versioned   Completed  2400  2400  0        0             2026-10-19 01:40  2026-10-19 01:40
a368b49d  acme-witness  incomplete  Failed     2000  2000  0        0             2026-10-19 01:40  2026-10-19 01:40

$ lm run get a368b49d
ID        CONNECTOR     ENDPOINT    STATUS  IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
a368b49d  acme-witness  incomplete  Failed  2000  2000  0        0             2026-10-19 01:40  2026-10-19 01:40
✖ run failed: Endpoint 0fd37327-e34f-4e26-93aa-7c07d762d36c: the source declared 2400 rows but this run returned 2300 — refusing to report a partial extract as a complete one
```

- `2300` is what pagination returned; `IN 2000` is what had been written when the check fired.
- **A failed run is not rolled back**: `ext.acme_witness_incomplete` keeps those 2 000 rows.
  The next successful run upserts over them.
- The message names the endpoint by its internal id, not by its manifest `id`.

`total_path` is optional on every strategy; a `soap` endpoint ignores it. It also lets `offset` stop once every row is in.
The `drf-limit-offset` and `sequelize` presets set it for you (`total_path: $.count`).

The check is skipped when:

- there is no `total_path`;
- the endpoint is incremental (`watermark`) — it filters by design;
- the total changes between pages — the source was written to during the read.

Point `total_path` at an exact **row** count:

- not a page count (`total_pages_path`) — a short page keeps the page count right while rows
  go missing;
- not an estimate — the comparison is exact in both directions. Leave `total_path` unset
  instead.

## CLI

```bash
lm apply -f <file.yaml>
lm validate -f <file.yaml>
lm connector run <connector>
lm endpoint enable|disable|reset-cursor|purge <connector>/<endpoint>
lm source rest test <connector>                      # pre-flight every enabled endpoint, no write
lm source rest probe <connector> --endpoint <path>   # sample one path, draft its endpoint block
lm source rest from-openapi <url> --name N --scope S -o FILE
```

## TUI

- `:sources` — the `KIND` column shows rest/jdbc/csv; Enter on a REST connector drills into
  `:endpoints`
- `:endpoints` — NAME/METHOD/PATH/SCHEDULE/LAST_RUN/STATUS/ENABLED; Enter → `:runs` filtered

Full reference: [CLI & TUI](../cli/lm-reference.md).

## How to…

### Check a connector before running it

```bash
lm source rest test <connector>
```

**Result:** for each enabled endpoint, the hub sends the first request a run would send (same
method, path, headers, auth and pagination) and reports what came back. Nothing is written.

- `200` with `0` items: the response arrived but nothing matched `response_path`.
- Disabled endpoints are skipped and counted on stderr.
- `-o json` adds the URL called (host and path; the query string is not shown) and an excerpt
  of the body:

```
$ lm endpoint disable acme-saas/link-partners
$ lm source rest test acme-saas -o json
note: skipped 1 disabled endpoint(s)
[
  {
    "endpoint": "cursor-partners",
    "method": "GET",
    "url": "http://host.docker.internal:8099/v1/partners",
    "status": 200,
    "ok": true,
    "items": 500,
    "detail": "",
    "sample": "{\"data\": [{\"id\": 1000, \"name\": \"Hydraulique Lyon 1000\", \"city\": \"Lyon\", \"country\": \"FR\", \"credit_limit\": 500, \"updated_at\": \"2026-01-01T00:00:00Z\"}, {\"id\": 1001, \"name\": \"Roulements Bruxelles 1001\", \"…"
  }
]
$ lm endpoint enable acme-saas/link-partners
```

### Draft an endpoint from a live path

`probe` samples **one path on an existing connector** and prints the endpoint block that would
ingest it. The base URL and credential come from the connector:

```
$ lm source rest probe acme-partners --endpoint /spring/partners
# Auto-generated by RestProber (no LLM augmentation in v1)
- id: spring-partners
  method: GET
  path: /spring/partners
  pagination: { kind: page }
  target:
    kind: hybrid
    table: ext.acme_partners_spring_partners
    unique_key: $.id
    promote:
      - { name: id, path: $.id, type: bigint }
      - { name: name, path: $.name, type: text }
      - { name: city, path: $.city, type: text }
      - { name: country, path: $.country, type: text }
      - { name: credit_limit, path: $.credit_limit, type: bigint }
      - { name: updated_at, path: $.updated_at, type: text }
```

**Result:** an endpoint **fragment** — paste it under `spec.endpoints:`, review it, then
`lm apply`.

- The pagination kind is a guess from the response envelope; an unknown envelope gives
  `kind: unknown` for you to fill in.
- The promote list covers the scalar fields of the first record only.

An upstream error is reported instead of a draft:

```
$ lm source rest probe acme-partners --endpoint /open/nope
Error: API error 502: upstream returned HTTP 404 (permanent) — refusing to ingest its body as data: {"error": "unknown resource", "known": ["down", "drf", "flaky", "hdr", "liar", "link", "offset", "open", "spring", "v1"], "resource": "/open/nope"}
```

A connector that is not `rest-generic` is refused by name.

### Generate a manifest from an OpenAPI spec

```bash
lm source rest from-openapi \
    https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.json \
    --name stripe --scope billing --output stripe.yaml
```

**Result:** a manifest scaffold with the endpoints the spec declares — review auth, pagination
and target before `lm apply`. A URL that holds no spec is refused:

```
$ lm source rest from-openapi http://127.0.0.1:8099/spring/partners --name x --scope billing -o x.yaml
Error: API error 502: no OpenAPI spec could be read at http://127.0.0.1:8099/spring/partners — unreachable, not a spec, or not parseable
```

### Reset a stuck cursor

```bash
lm endpoint reset-cursor <connector>/<endpoint>
```

**Result:** the stored watermark is cleared and the next `lm connector run` is a full read.
The command prints nothing.

### Empty one endpoint's rows

`lm endpoint purge` deletes the rows one endpoint has landed and keeps the connector, its other
endpoints and its run history. `--dry-run` only counts:

```
$ lm endpoint purge acme-incremental/partners --dry-run
Would purge 2400 rows (dry run, nothing deleted)
$ lm endpoint purge acme-incremental/partners
Type 'purge' to delete 2400 rows: purge
Purged 2400 rows
$ lm endpoint purge acme-incremental/partners
Nothing to purge: this endpoint has no rows
```

- Every `rest-generic` endpoint can be purged. A `jdbc-generic`, `csv-file` or `kafka` endpoint
  is refused — see [Emptying one endpoint on its own](jdbc.md#emptying-one-endpoint-on-its-own).
- `--force` skips the question.
- Add `lm endpoint reset-cursor` to re-read the endpoint from scratch on the next run.
- In `lm ui`, `Del` on `:endpoints` does the same, with the same count and question.

### Add a typed column later

Add an entry to the endpoint's `target.promote` list in your copy of `10-quickstart.yaml`
(here `acme-partners.yaml`) and re-apply:

```yaml
promote:
  - { name: city,    path: $.city,    type: text }
  - { name: country, path: $.country, type: text }   # new column
```

**Result:** `lm apply` changes nothing in the database; the **next run** adds the column and
fills it as rows are read again.

```
$ lm apply -f acme-partners.yaml
{"name":"acme-partners","connectorId":"796fd517-f18f-4b76-b681-a0b235d19e64","endpointsApplied":1}
$ lm connector run acme-partners
run scheduled: b9a1bf1a-f9dc-403c-a2d4-6cdab8fb7f79
$ lm run list --connector acme-partners
ID        CONNECTOR      ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
b9a1bf1a  acme-partners  partners  Completed  2400  2400  0        0             2026-10-19 07:25  2026-10-19 07:25
9af1c00d  acme-partners  partners  Completed  2400  2400  0        0             2026-10-19 07:25  2026-10-19 07:25
```

```
lumnik=> SELECT count(*) FILTER (WHERE country IS NOT NULL) AS with_country, count(*)
lumnik->   FROM ext.acme_partners;
 with_country | count 
--------------+-------
         2400 |  2400
(1 row)
```

A `full` endpoint fills every row on that run; a `watermark` endpoint fills only the rows that
change, unless you `lm endpoint reset-cursor` first.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| Not sure the credential is valid | — | `lm source rest test <connector>` — one request per enabled endpoint, nothing written; an unresolved secret is named in `VERDICT` |
| Run completed, `IN` is 0 | the response arrived but nothing matched `response_path` | `lm source rest test <connector>` shows `200` with `0` items; check `response_path` against the body |
| Run failed on `the source declared N rows` | `total_path` points at a number the run did not match | check the source; if `total_path` points at a page count or an estimate, see [The declared total](#the-declared-total-a-witness-on-the-count) |
| `429 Too Many Requests` from the API | `default_rate_limit` exceeds what the API tolerates | lower it (`100/s` → `50/s`); it is one bucket for the whole connector |
| Sync stops picking up new rows | the stored watermark is stale or points at a column the source does not touch | `lm endpoint reset-cursor <connector>/<endpoint>` → the next run is a full read |
| A run reads far more pages than expected | `cursor`, `body-link` and `link-header` send no size parameter | put the size in the endpoint's `path:` — see [the page-size warning](rest/pagination.md#strategies) |
| `lm apply` refused with an unknown transform | a `promote` entry's `transform:` is not one of the supported values | see [Transformers](transformers.md) |
| Need to wipe an endpoint's rows | — | `lm endpoint purge <connector>/<endpoint>` — see [Empty one endpoint's rows](#empty-one-endpoints-rows) |

## See also

- [Pagination](rest/pagination.md) — presets, strategies, cursor binding
- [The demo API](rest/demo-api.md) — the source every screen here reads
- [Overview](index.md)
- [GraphQL](graphql.md) — the GraphQL request shape on this engine
- [SOAP](soap.md) — the SOAP request shape on this engine
- [Transformers](transformers.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
