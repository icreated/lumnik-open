# REST

> Ingest any HTTP/JSON API: point at the base URL, declare the endpoints and how they page,
> apply. The rows land as typed columns plus the untouched JSON payload.

| | |
|---|---|
| **Type** | `rest-generic` |
| **Protocol** | HTTP/JSON (+ the GraphQL and SOAP request shapes) |
| **Target** | `ext.<connector>_<endpoint>` |
| **Schedulable** | cron |
| **Edition** | Open |

Every endpoint declares its own `method`, and `GET` is what a source usually needs; the `POST`
shape exists because GraphQL puts its query in a body. The engine sends what the manifest
declares and nothing else — it never discovers a write route on its own.

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
✓ docs/connectors/rest/demo/10-quickstart.yaml — valid
$ lm apply -f docs/connectors/rest/demo/10-quickstart.yaml
{"name":"acme-partners","connectorId":"c5a84b8e-2921-445e-ae1c-b65e897f80ba","endpointsApplied":1}
```

Before running anything, ask the source the first question a run would ask:

```
$ lm source rest test acme-partners
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  GET     200     500    ok
```

`ITEMS` is what the endpoint's own pagination strategy read out of that one page — 500, the
`size` this manifest asked for. Nothing was written. Now run it:

```
$ lm connector run acme-partners
run scheduled: 36661fb9-398f-4fae-be8c-fb9890072b2a
```

"Scheduled" is not "done" — the run ledger is where the result lands:

```
$ lm run list --connector acme-partners
ID        CONNECTOR      ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
36661fb9  acme-partners  partners  Completed  2400  2400  0        0             2026-09-10 09:54  2026-09-10 09:54
```

`STARTED` / `ENDED` print in **your terminal's** time zone (these screens: Paris, UTC+2). The
hub stores UTC, and `schedule` is evaluated in UTC.

What landed in `ext.acme_partners` — the promoted columns, beside the whole payload:

```
 external_id |           name            |   city    | credit_limit |       updated_at       
-------------+---------------------------+-----------+--------------+------------------------
 1000        | Hydraulique Lyon 1000     | Lyon      |          500 | 2026-01-01 00:00:00+00
 1001        | Roulements Bruxelles 1001 | Bruxelles |          637 | 2026-01-01 00:01:00+00
 1002        | Fixations Milano 1002     | Milano    |          774 | 2026-01-01 00:02:00+00
 1003        | Pneumatique Porto 1003    | Porto     |          911 | 2026-01-01 00:03:00+00
(4 rows)
```

- **`promote` is a projection, not a filter.** Every field the source returned is in `data`
  (JSONB); the promoted ones are copied out into typed columns you can index and join on.
- **`unique_key` is what makes a re-run safe.** It becomes `external_id`, unique per tenant, and
  every write is an upsert — see [Storage layout](#storage-layout).
- **The password is never in the manifest.** `password_env` names an entry the hub resolves at
  run time: the `lm secret` registry first, its own environment second.

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

The path-shaped fields (`cursor_path`, `response_path`, `unique_key`, `link_path`, …) are
JSONPath expressions read against the response body: `$` is the root, so `$.data[-1:].id` is
"the `id` of the last element of `data`".

Each `promote` entry may carry a `transform:`, applied as the value is written — the full list
is in [Transformers](transformers.md#rest-the-transform-enum). `parse_iso8601` is the one most
manifests need: it turns the timestamp the API prints into a real `timestamptz`.

Omit `schedule` to run only on demand with `lm connector run`; `window` fences the scheduled runs
into a quiet slot — a slot due outside it is skipped, never queued, and a manual run ignores the
fence. One more key exists and is not in the sample above: `defaults`, whose children are copied
verbatim into the connector's stored config, unvalidated.

**The connector level of `spec` is a closed list**, so a key that is none of the above comes back
named rather than accepted and stored:

```
! my-api.yaml — 0 error(s), 1 warning(s)

  WARN     spec.headerz
          unknown key — ignored by the runtime
          → Remove it, or check the spelling
```

A warning, not a refusal — the manifest still applies. Worth reading anyway: that is the
difference between finding the typo now and wondering later why the header never arrived.

## One connector, one API

One connector is one API, whatever number of endpoints it exposes — and two endpoints of the
same API may page by entirely different conventions:

```yaml
--8<-- "docs/connectors/rest/demo/20-two-endpoints.yaml"
```

```
$ lm run list --connector acme-saas
ID        CONNECTOR  ENDPOINT         STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
a9fd81d6  acme-saas  link-partners    Completed  2400  2400  0        0             2026-09-10 09:54  2026-09-10 09:54
dff877b2  acme-saas  cursor-partners  Completed  2400  2400  0        0             2026-09-10 09:54  2026-09-10 09:54
```

Two rows, because **the ledger records one run per endpoint**. Both read the same 2 400
partners through conventions that share nothing — `stripe` and `drf-page`, two of the
[presets](rest/pagination.md#presets) that name the framework behind an API.

## Auth

| Kind | Config |
|---|---|
| `bearer` | `token_env: VAR_NAME` (preferred), or `token_value: "..."` (dev only) |
| `basic` | `username` + `password_env: VAR_NAME` |
| `api-key` | `in: header\|query`, `name`, `value_env: VAR_NAME` |
| `none` | no credentials (the default when `auth:` is omitted) |

Every auth `*_env` field resolves an [`lm secret`](../admin-console-guide.md) **name first**,
then falls back to an environment variable / JVM system property of that name on the hub. Set
the value before `lm apply`:

```bash
lm secret set ACME_BASIC_PW     # prompts at the keyboard; what you type is not shown
```

- `basic` and `api-key` have **no inline field**: the value comes from `password_env` /
  `value_env`, or not at all.
- `bearer` accepts `token_value`, but it is **ignored whenever `token_env` is also set** — the
  env name wins outright, and if nothing resolves under it the run fails on the auth probe
  rather than falling back to the inline value. Declare one or the other, never both.

A credential that does not resolve is named, before any run:

```
$ lm source rest test acme-badauth
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  GET     -       0      Basic password unavailable (env=ACME_NOT_SET_PW)
Error: 1 of 1 enabled endpoint(s) did not answer with readable data
```

OAuth2 (client_credentials, authorization_code), HMAC and mTLS are dedicated future projects.

## Pagination

Every endpoint declares how the source hands back its next page. Most APIs follow a convention
that already has a name, so the usual answer is one line:

```yaml
pagination: { kind: spring-page }        # Spring Data Page — seven fields under one word
```

Eleven presets name the framework that shaped the API — Spring Data (`Page`, `Slice`,
HATEOAS), Micronaut, Django REST, FastAPI, NestJS, JSON:API, Sequelize, Stripe. When none of
them fits, six strategies are written out by hand: `cursor`, `page`, `offset`, `link-header`,
`body-link` and `relay`.

**→ [Pagination](rest/pagination.md)** — the catalogue, the fields of each strategy, where the
cursor is injected, and the two traps that cost the most time: page size on the strategies that
send none, and `kind: custom`, which applies cleanly and dies on the second page.

## Request headers

Connector-level only — `spec.headers[]` goes out on **every** request:

```yaml
spec:
  headers:
    - { name: X-Api-Version, value: "2026-01" }       # static
    - { name: X-Client-Id,   value_env: CLIENT_ID }   # from the environment
```

- Each entry needs `name` + **exactly one** of `value` / `value_env`.
- `value_env` resolves the OS environment first, then a JVM system property. It does **not**
  read the `lm secret` registry — unlike every auth `*_env` field above. A header credential has
  to be a real environment variable (or system property) where the hub runs.
- A `value_env` that resolves to nothing **drops the header silently**: no warning, no refusal
  at apply. The request goes out without it, and upstream reports that as a puzzling 400 or 401
  naming something else entirely.
- Headers are applied **before** auth — an auth-injected header wins a name clash.
- Endpoint-level `headers` are refused at apply:

```
$ lm apply -f bad-headers.yaml
✗ bad-headers.yaml — 1 error(s), 0 warning(s)

  ERROR    spec.endpoints[0].headers
          per-endpoint headers are not supported in this release; declare headers at spec.headers
          → Move the headers block up to spec.headers (connector level)

Fix errors above before running 'lm apply -f bad-headers.yaml'.
Error: validation failed: 1 error(s)
```

The declared headers are part of the pre-flight too — `lm source rest test` composes the
request a run composes. `/hdr/partners` answers 400 to anything without `X-Api-Version`, and
the `acme-witness` connector carries it in `spec.headers` — its manifest is under
[The declared total](#the-declared-total-a-witness-on-the-count):

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

> `mode: api_cursor` (API-native cursor persistence) is **deferred — not implemented**;
> declaring it today behaves like `full`.

Cursor state lives in `connector.connector_cursor`, one row per endpoint, and survives runs.

```yaml
--8<-- "docs/connectors/rest/demo/40-watermark.yaml"
```

Run it twice, and read the ledger:

```
$ lm run list --connector acme-incremental
ID        CONNECTOR         ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
f45d0235  acme-incremental  partners  Completed  1     1     0        0             2026-09-10 09:55  2026-09-10 09:55
85c35101  acme-incremental  partners  Completed  2400  2400  0        0             2026-09-10 09:55  2026-09-10 09:55
```

Run 1 had nothing stored, so no parameter went out: 2 400 rows. Run 2 sent the mark, which the
demo API prints in its own log — the surest place to see what actually left the hub:

```
10/Sep/2026 10:09:59  "GET /offset/partners?updated_since=2026-01-02T15%3A59%3A00Z&offset=0&limit=500 HTTP/1.1" 200 -
```

One row came back, the one sharing that instant, because the demo API filters inclusively as
most APIs do. It was re-upserted on its unique key; the table still holds 2 400.

The mark is **not** advanced page by page. A crash mid-pagination would otherwise leave it past
pages that were never fetched, and those rows would never be read again. The stored value keeps
its pre-run reading for the whole run while a candidate carries the running MAX, and only the
final chunk promotes it.

`lm endpoint reset-cursor acme-incremental/partners` clears it; the next run is a full read
again. The command prints nothing and exits 0.

A watermark is only as incremental as the column behind it: pointed at a column the source does
not touch on update, it re-reads everything on every run and reports it all as new.

## Storage layout

Nothing to set up: each endpoint's table is created on the first run. This section is reference,
for when you need it.

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

The promoted columns are the four in the middle — `name`, `city`, `credit_limit`, `updated_at`.

- `unique_key` from the manifest becomes `external_id`; `UNIQUE (tenant_id, external_id)` plus
  `ON CONFLICT DO UPDATE` is what makes a re-run idempotent.
- **A re-run rewrites only the rows that changed.** A row whose `row_hash` and promoted values
  came back identical is left as it is, and its `indexed_at` keeps the time it last changed. `OUT`
  still counts every row read and `SKIPPED` stays `0`, so the witness that nothing duplicated is
  the table's row count, not the skip column.
- `data` holds the whole payload, indexed with a GIN index for JSONB path queries.
- `row_hash` is the payload's hash: it decides whether a row read again has changed, and the same
  hash keys the RAG side's chunk dedup, so a row that came back unchanged is not embedded a
  second time.
- The row-level security policy is what keeps one tenant's rows invisible to another.

Adding a `promote` entry later and re-applying makes the next run do `ALTER TABLE ADD COLUMN`;
the column arrives empty and fills as rows are read again — see
[Add a typed column later](#add-a-typed-column-later).

!!! warning "Deleting a REST connector deletes its data"
    `lm connector delete` drops the connector's registered `ext.*` tables, and every RAG chunk
    the connector produced dies with it — the same erasure gesture as the other connector
    families. To empty one endpoint's rows **without** deleting the connector, use
    `lm endpoint purge <connector>/<endpoint>`, which asks you to type `purge` before it does.

## When the source misbehaves

Three ways a source can make a run lie, and what the engine does about each: it retries what is
worth retrying, it refuses to read an error body as data, and — where the source publishes a
count — it checks the run against that number.

### Retry, page cap, rate limit

- **Retry** — a fixed policy, not a manifest knob: up to **5 attempts** per request on HTTP
  `429, 500, 502, 503, 504, 408` and on network errors; exponential backoff `1s → 2s → 4s → …`
  capped at **60s**, ±10% jitter; a numeric `Retry-After` header overrides the computed delay.
  Any other status is returned as-is, no retry.
- **Page cap** — the pagination loop stops after **10 000 pages** per endpoint per run, a
  runaway-loop guard.
- **Rate limit** — `default_rate_limit` (default `100/s`) is a `count/window` string; the window
  is an optional integer + unit `s|m|h` (`100/s`, `100/5m`, `1000/h`). It is declared once at
  connector level — one token bucket per connector, shared by all its endpoints. With several
  endpoints on one connector, set it to the **strictest** endpoint's documented limit: a laxer
  value lets the busier endpoints starve it.

### An outage fails the run

What happens after the retries are spent is the part worth seeing. `/down/partners` answers 500
to everything; `/flaky/partners` answers 429 then 503 before serving each page:

```yaml
--8<-- "docs/connectors/rest/demo/60-source-down.yaml"
```

```
$ lm run list --connector acme-outage
ID        CONNECTOR    ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
ea13f93f  acme-outage  flaky     Completed  2400  2400  0        0             2026-09-10 09:56  2026-09-10 09:57
5fbf784f  acme-outage  down      Failed     0     0     0        0             2026-09-10 09:56  2026-09-10 09:57

$ lm run get 5fbf784f
✖ run failed: upstream returned HTTP 500 (transient) — refusing to ingest its body as data: {"error": "this endpoint is down and stays down"}
```

An outage **fails the run**. It is not reported as a successful empty sync: an error body is not
data, however well-formed it is, and a green run over zero rows is how a total outage gets
indexed as "nothing changed today". The guard refuses a **final** non-2xx, not a retried one —
which is why the flaky endpoint, on the same connector, completes with all 2 400 rows.

The pre-flight sends **one** request and does not retry, so a flaky source shows red there while
its run goes green:

```
$ lm source rest test acme-outage
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
down      GET     500     0      HTTP 500
flaky     GET     429     0      HTTP 429
Error: 2 of 2 enabled endpoint(s) did not answer with readable data
```

A refusal does not stop the walk — every enabled endpoint is tried, so one command gives the
whole picture — and the command exits non-zero if any of them did not answer.

### The declared total — a witness on the count

A run that loses rows ends exactly like a complete one: the last page simply says "no more".
It lands green, and the count it reports agrees with itself while being wrong. Where the source
publishes how many rows the extract holds, `total_path` turns that number into a check.

`/liar/partners` serves 2 300 of its 2 400 partners and still declares `"total": 2400`:

```yaml
--8<-- "docs/connectors/rest/demo/50-total-witness.yaml"
```

```
$ lm run list --connector acme-witness
ID        CONNECTOR     ENDPOINT    STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
58e98b1b  acme-witness  versioned   Completed  2400  2400  0        0             2026-09-10 09:56  2026-09-10 09:56
0da02bdc  acme-witness  incomplete  Failed     2000  2000  0        0             2026-09-10 09:56  2026-09-10 09:56

$ lm run get 0da02bdc
✖ run failed: Endpoint 564a9026-736c-4843-8be6-e029e6231cf4: the source declared 2400 rows but this run returned 2300 — refusing to report a partial extract as a complete one
```

The two numbers in that screen are both right and mean different things: `2300` is what
pagination returned, `IN 2000` is what had been written when the check fired at the end of the
walk. `ext.acme_witness_incomplete` holds exactly those 2 000 rows — **a failed run is not
rolled back**, so the table keeps what arrived before the refusal. The next successful run
upserts over it.

`total_path` is optional on `page`, `offset` and `body-link`, and it does two jobs: it lets
`offset` stop as soon as every row is in, and it checks the run when pagination ends.

Three cases go unchecked on purpose, because the two figures are not comparable:

- **no `total_path`** — nothing was published, so nothing is claimed;
- **incremental (`watermark`) endpoints** — the run filters by design and returns fewer rows
  than the source's unfiltered total;
- **a total that changes between pages** — rows were written or deleted while you were reading.
  A live source has lost nothing, and the run stands.

**You may already have it without typing it.** The `drf-limit-offset` and `sequelize` presets
expand to `total_path: $.count`, and an endpoint that set `total_path` purely to stop paging
early is now checked by it too. Both of those sources publish an exact count, so nothing should
change — but if a run of yours starts failing on this message after an upgrade, that is where it
came from, and removing `total_path` restores the old behaviour.

Two ways to point it at the wrong number. Neither fails predictably — they fail on the day the
two figures happen to differ, and the red says nothing about the manifest:

- **a page count.** `total_pages_path` counts pages, and a page that comes back short leaves the
  page count perfectly right while rows go missing. Point `total_path` at rows.
- **an estimate.** Some APIs publish an approximate count (a planner statistic rather than a
  `COUNT(*)`). The comparison is exact and reads both directions — fewer rows than declared *and*
  more — so an estimate belongs nowhere near `total_path`. Leave it unset instead.

A witness you cannot trust is worse than none: it spends the operator's attention on the
manifest instead of on the source.

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
- `:endpoints` — NAME/METHOD/PATH/SCHEDULE/LAST_RUN/STATUS; Enter → `:runs` filtered

Full reference: [CLI & TUI](../cli/lm-reference.md).

## How to…

### Check a connector before running it

```bash
lm source rest test <connector>
```

**Result:** for each enabled endpoint, the hub sends the one request a run would send first —
same method, same path, same declarative headers, same auth, same pagination — and reports what
came back. Nothing is written. A `200` with `0` items means the response arrived but nothing
matched `response_path`: the shape that otherwise lands as a run that completes having ingested
nothing. Disabled endpoints are skipped and counted on stderr, and `-o json` adds the URL that
was called and a bounded excerpt of what came back:

```
$ lm source rest test acme-saas -o json
note: skipped 1 disabled endpoint(s)
[
  {
    "endpoint": "cursor-partners",
    "method": "GET",
    "url": "http://host.docker.internal:8099/v1/partners?limit=500",
    "status": 200,
    "ok": true,
    "items": 500,
    "detail": "",
    "sample": "{\"data\": [{\"id\": 1000, \"name\": \"Hydraulique Lyon 1000\", \"city\": \"Lyon\", \"country\": \"FR\", \"credit_limit\": 500, \"updated_at\": \"2026-01-01T00:00:00Z\"}, {\"id\": 1001, \"name\": \"Roulements Bruxelles 1001\", \"…"
  }
]
```

The `url` is the request as it was composed — the place a wrong `path:` or a missing query
parameter shows up before a run does.

### Draft an endpoint from a live path

`probe` samples **one path on a connector you already have** and writes the endpoint block it
would take to ingest it. The connector supplies the base URL and the credential, so nothing
secret goes on the command line:

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

**Result:** an endpoint **fragment**, not a whole manifest — paste it under the `spec.endpoints:`
of the connector you probed, review it, then `lm apply`. The pagination kind is read off the
response envelope and is a guess: an envelope it does not recognise comes back as
`kind: unknown` for you to fill in. The promote list covers the scalar fields of the first
record only.

The refusals say what happened upstream rather than failing blank:

```
$ lm source rest probe acme-partners --endpoint /open/nope
Error: API error 502: upstream returned HTTP 404 (permanent) — refusing to ingest its body as data: {"error": "unknown resource", "known": ["down", "drf", "flaky", "hdr", "liar", "link", "offset", "open", "spring", "v1"], "resource": "/open/nope"}

$ lm source rest probe demo-invoices --endpoint /x
Error: "demo-invoices" is not a rest-generic connector (type: "jdbc-generic") — `lm source rest probe` samples a REST source
```

(`demo-invoices` is the [JDBC page](jdbc.md)'s connector — any non-REST one answers the same.)

### Generate a manifest from an OpenAPI spec

```bash
lm source rest from-openapi \
    https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.json \
    --name stripe --scope billing --output stripe.yaml
```

**Result:** a manifest scaffold with the endpoints the spec declares — review auth, pagination
and target before `lm apply`. A URL that holds no spec is refused rather than yielding an empty
scaffold:

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

### Add a typed column later

Add an entry to the endpoint's `target.promote` list and re-apply:

```yaml
promote:
  - { name: name, path: $.name, type: text }
  - { name: city, path: $.city, type: text }   # new column
```

**Result:** `lm apply` registers the new entry and changes nothing in the database; the **next
run** does the `ALTER TABLE ADD COLUMN`, and the column arrives empty. The values land as rows
are next read and upserted — a `full` endpoint fills all of them on that same run, a `watermark`
endpoint only the rows that move, unless you `lm endpoint reset-cursor` first.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| Not sure the credential is valid | — | `lm source rest test <connector>` — one request per enabled endpoint, nothing written; an unresolved secret is named in `VERDICT` |
| Run completed, `IN` is 0 | the response arrived but nothing matched `response_path` | `lm source rest test <connector>` shows `200` with `0` items; check `response_path` against the body |
| Run failed on `the source declared N rows` | `total_path` points at a number the run did not match | it is doing its job — check the source; if it points at a page count or an estimate, see [The declared total](#the-declared-total-a-witness-on-the-count) |
| `429 Too Many Requests` from the API | `default_rate_limit` exceeds what the API tolerates | lower it (`100/s` → `50/s`); it is one bucket for the whole connector |
| Sync stops picking up new rows | the stored watermark is stale or points at a column the source does not touch | `lm endpoint reset-cursor <connector>/<endpoint>` → the next run is a full read |
| A run reads far more pages than expected | `cursor`, `body-link` and `link-header` send no size parameter | put the size in the endpoint's `path:` — see [the page-size warning](rest/pagination.md#strategies) |
| `lm apply` refused with an unknown transform | a `promote` entry's `transform:` is not one of the supported values | see [Transformers](transformers.md) |
| Need to wipe an endpoint's rows | — | `lm endpoint purge <connector>/<endpoint>` — it asks you to type `purge` to confirm (`--force` skips that) |

## See also

- [Pagination](rest/pagination.md) — presets, strategies, cursor binding
- [The demo API](rest/demo-api.md) — the source every screen here reads
- [Overview](index.md)
- [GraphQL](graphql.md) — the GraphQL request shape on this engine
- [SOAP](soap.md) — the SOAP request shape on this engine
- [Transformers](transformers.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
