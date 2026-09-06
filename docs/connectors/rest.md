# REST

> Ingest any HTTP/JSON API into hybrid JSONB + typed columns: point at the base URL,
> declare endpoints and pagination, apply.

| | |
|---|---|
| **Type** | `rest-generic` |
| **Protocol** | HTTP/JSON (+ GraphQL & SOAP shapes) |
| **Target** | `ext.<connector>_<endpoint>` |
| **Schedulable** | cron |
| **Edition** | Open |

## Quickstart

> **Installer demo path?** See [`docs/quickstart/installer.md`](../quickstart/installer.md) for the 60-second sequence (Stripe / CSV / combined).

```bash
# 1. Generate manifest from an OpenAPI spec
lm source rest from-openapi \
    https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.json \
    --name stripe --scope billing --output stripe.yaml

# 2. Review and adjust (auth, pagination, schedule — the cron lands on the hub scheduler)
$EDITOR stripe.yaml

# 3. Apply
lm apply -f stripe.yaml

# 4. Run the connector
lm connector run stripe
```

## YAML manifest

```yaml
apiVersion: connectors.lumnik.io/v1
kind: Connector
metadata:
  name: stripe
  connector_type: rest-generic
  scopes: [billing]
spec:
  base_url: https://api.stripe.com
  auth:
    kind: bearer
    token_env: STRIPE_API_KEY
  default_rate_limit: 100/s
  schedule: "0 6 * * *"     # optional cron expression, daily at 06:00 UTC — the hub scheduler fires it
  endpoints:
    - id: charges
      method: GET
      path: /v1/charges
      pagination:
        kind: cursor
        cursor_param: starting_after
        cursor_path: $.data[-1:].id
        has_more_path: $.has_more
        response_path: $.data
      incremental: { mode: full }
      target:
        kind: hybrid
        table: ext.stripe_charges
        unique_key: $.id
        promote:
          - { name: amount,   path: $.amount,   type: bigint }
          - { name: currency, path: $.currency, type: text }
          - { name: status,   path: $.status,   type: text }
```

Each endpoint declares `id` (kebab-case), `method` (one of `GET | POST | PUT | PATCH | DELETE`),
`path`, a `pagination` block and a `target` block; `incremental` is optional (defaults to `full`).
`schedule` (spec-level, shown above) is optional too — omit it to run only on demand with
`lm connector run`.

The path-shaped fields (`cursor_path`, `has_more_path`, `response_path`, `unique_key`, …) are
JSONPath expressions read against the response body: `$` is the root, so `$.data[-1:].id`
reads "the `id` of the last element of `data`".

Each `target.promote` entry may also carry an optional `transform:`, applied as the value is
written — see [Transformers](transformers.md#rest-the-transform-enum) for the full list.

## Pagination strategies

| Strategy | Use for | Required fields | Optional fields (default) |
|---|---|---|---|
| `cursor` | Stripe, OpenAI, most modern APIs | cursor_param, cursor_path, has_more_path, response_path | cursor_binding (query) |
| `page` | Spring HATEOAS, Laravel | page_param, response_path | size_param, size (50), start_page (1), total_pages_path, has_more_path, last_path, empty_check (true) |
| `offset` | Legacy REST | offset_param, limit_param, response_path | limit (100), total_path |
| `link-header` | GitHub, JSON:API | response_path | rel (next) |
| `relay` | GraphQL Connections | cursor_path, has_more_path, response_path; plus cursor_param (query/header binding) **or** cursor_var (variable binding) | cursor_binding (query) |
| `body-link` | Django REST, JSON:API, NestJS, HATEOAS | link_path, response_path | — |
| `custom` | Anything weird | response_path | Kotlin script (deferred to a future release) |

`page` stops on whichever of `total_pages_path` / `has_more_path` / `last_path` is present —
and, with `empty_check: true` (the default), on the first empty page.

### Cursor binding

For `cursor` and `relay`, `cursor_binding` picks where the next-page cursor is injected:

| Binding | Cursor goes into | Needs |
|---|---|---|
| `query` (default) | a query parameter | cursor_param |
| `header` | an HTTP header | cursor_param (the header name) |
| `variable` | the POST body's `variables.<name>` | cursor_var — `relay` on a graphql endpoint only |

`variable` is refused outside `relay` on a graphql endpoint (the cursor rides a POST body that a
plain GET never sends); conversely, graphql endpoints require exactly `relay` + `variable`.

> **Pure GraphQL APIs** (POST a query, cursor in `variables`): see [GraphQL](graphql.md).

## Pagination presets

Modern APIs follow well-known conventions — declare one preset instead of the full block.
A preset expands to a real strategy on `lm apply`.

| Preset | For | Expands to |
|---|---|---|
| `spring-page` | Spring Data `Page` | `page` (`$.content`, `$.totalPages`, 0-indexed) |
| `spring-slice` | Spring Data `Slice` (no total) | `page` + `last_path: $.last` |
| `spring-hateoas` | Spring Data REST | `body-link` (`$._links.next.href`, `$._embedded.{items}` — needs `items`) |
| `micronaut` | Micronaut Data `Page` | `page` (`$.content`, `$.totalPages`) |
| `drf-page` | Django REST `PageNumberPagination` | `body-link` (`$.next`, `$.results`) |
| `drf-limit-offset` | Django REST `LimitOffsetPagination` | `offset` (`$.count`, `$.results`) |
| `fastapi` | fastapi-pagination | `page` (`$.items`, `$.pages`, 1-indexed) |
| `nestjs` | nestjs-paginate | `body-link` (`$.links.next`, `$.data`) |
| `json-api` | JSON:API | `body-link` (`$.links.next`, `$.data`) |
| `sequelize` | Node/Sequelize | `offset` (`$.count`, `$.rows`) |
| `stripe` | Stripe-style cursor | `cursor` (`$.has_more`, `$.data`) |

```yaml
pagination: { kind: spring-page }                       # 7 lines → 1
pagination: { kind: spring-page, size: 50 }             # override any field
pagination: { kind: spring-hateoas, items: products }   # {items} → $._embedded.products
```

> **`body-link`** is also a first-class strategy: `{ kind: body-link, link_path: $.next, response_path: $.results }`
> follows a next URL from the response body until it is null.

## Auth providers

| Kind | Config |
|---|---|
| `bearer` | `token_env: VAR_NAME` (preferred), or `token_value: "..."` (dev only) |
| `basic` | `username` + `password_env: VAR_NAME` |
| `api-key` | `in: header\|query`, `name`, `value_env: VAR_NAME` |
| `none` | no credentials (the default when `auth:` is omitted) |

Every **auth** `*_env` field (the fields of the providers in this section) resolves an
[`lm secret`](../admin-console-guide.md) **name first**, then
falls back to a server environment variable / JVM system property of that name. Set the value
before `lm apply`:

```bash
lm secret set STRIPE_API_KEY -   # paste the value on stdin, Ctrl+D
```

OAuth2 (client_credentials, authorization_code), HMAC, and mTLS are dedicated future projects.

## Request headers

Connector-level only — `spec.headers[]` is applied to **every** request:

```yaml
spec:
  headers:
    - { name: X-Api-Version, value: "2024-10" }       # static
    - { name: X-Client-Id,   value_env: CLIENT_ID }   # from the environment
```

- Each entry needs `name` + **exactly one** of `value` / `value_env`.
- `value_env` resolves the OS environment first, then a JVM system property.
- Headers are applied **before** auth — auth-injected headers win on a name clash.
- Endpoint-level `headers` are **refused** at apply ("per-endpoint headers are not supported
  in this release; declare headers at spec.headers").

## Reliability

- **Retry** — fixed policy, not a manifest knob: up to **5 attempts** per request on HTTP
  `429, 500, 502, 503, 504, 408` and on network errors; exponential backoff `1s → 2s → 4s → …`
  capped at **60s**, ±10% jitter; a numeric `Retry-After` header overrides the computed delay.
  Any other status is returned as-is, no retry.
- **Page cap** — the pagination loop stops after **10 000 pages** per endpoint per run
  (`MAX_PAGES`), a runaway-loop guard.
- **Rate limit** — `default_rate_limit` (default `100/s`) is a `count/window` string; the window
  is an optional integer + unit `s|m|h` (`100/s`, `100/5m`, `1000/h`). Declared once at connector
  level: one token bucket per connector, shared by all its endpoints — no per-endpoint override.
  In practice: with several endpoints on one connector, set it to the **strictest** endpoint's
  documented limit — a laxer value lets the busier endpoints starve it.

## Incremental sync

```yaml
incremental:
  mode: full              # always re-fetch all pages (default)
  # OR
  mode: watermark         # send the stored watermark as a query param,
  param: updated_since    #   e.g. ?updated_since=<watermark>
  watermark_path: $.updated   # new watermark = MAX of this JsonPath over the items
```

> `mode: api_cursor` (API-native cursor persistence) is **deferred — not implemented**;
> declaring it today behaves like `full`.

Cursor state is stored in `connector.connector_cursor` per endpoint and survives runs.

## Storage layout

Nothing to set up here — each endpoint's table is created automatically. Rows land as the
typed `promote`d columns plus the full JSONB payload, and re-syncs are safe to re-run (they
upsert, never duplicate). The rest of this section is reference, for when you need it.

Each endpoint writes to a dynamically created `ext.<connector>_<endpoint>` table:

```sql
CREATE TABLE ext.stripe_charges (
    id            BIGSERIAL PRIMARY KEY,
    tenant_id     BIGINT NOT NULL,
    external_id   TEXT NOT NULL,
    -- promoted typed columns:
    amount        BIGINT,
    currency      TEXT,
    status        TEXT,
    -- always:
    data          JSONB NOT NULL,
    indexed_at    TIMESTAMPTZ DEFAULT now(),
    connector_id  UUID NOT NULL,
    endpoint_id   UUID NOT NULL,
    UNIQUE (tenant_id, external_id)
);
CREATE INDEX ON ext.stripe_charges USING GIN (data jsonb_path_ops);
```

`UNIQUE (tenant_id, external_id)` + `ON CONFLICT DO UPDATE` is the mechanism behind that
upsert.

!!! warning "Deleting a REST connector deletes its data"
    `lm connector delete` drops the connector's registered `ext.*` hybrid tables along with
    any `connector.t_*` dynamic tables, and every RAG chunk the connector produced dies with
    it — the same erasure gesture as the other connector families. To empty one endpoint's
    rows **without** deleting the connector, use `lm endpoint purge <connector>/<endpoint>`.

## CLI

```bash
lm apply -f <file.yaml>
lm endpoint enable|disable|reset-cursor|purge <connector>/<endpoint>
lm connector run <connector>
lm source rest test <connector>                     # pre-flight: auth + 1 page, no write
lm source rest from-openapi <url> --name N --scope S -o FILE
lm source rest probe <connector> --endpoint <path>   # sample fetch + manifest draft
```

## TUI

- `:sources` — `KIND` column shows rest/jdbc/csv; Enter on a REST connector drills into `:endpoints`
- `:endpoints` — NAME/METHOD/PATH/SCHEDULE/LAST_RUN/STATUS; Enter → `:runs` filtered

Full reference: [CLI & TUI](../cli/lm-reference.md).

## How to…

### Generate a manifest from an OpenAPI spec

```bash
lm source rest from-openapi \
    https://raw.githubusercontent.com/stripe/openapi/master/openapi/spec3.json \
    --name stripe --scope billing --output stripe.yaml
```

**Result:** a manifest scaffold (`stripe.yaml`) with endpoints inferred from the OpenAPI spec —
review auth, pagination, and target before `lm apply`.

### Start from a pagination preset

```yaml
pagination: { kind: spring-page }                       # 7 lines → 1
pagination: { kind: spring-page, size: 50 }             # override any field
pagination: { kind: spring-hateoas, items: products }   # {items} → $._embedded.products
```

**Result:** the preset expands into a full pagination block at `lm apply` — see
[Pagination presets](#pagination-presets) for the full list.

### Reset a stuck cursor

```bash
lm endpoint reset-cursor <connector>/<endpoint>
```

**Result:** the next `lm connector run <connector>` is a full refresh — watch it land in the
TUI (`:runs`), or see [CLI & TUI](../cli/lm-reference.md).

### Add a typed column later

Add an entry to the endpoint's `target.promote` list, then re-apply:

```yaml
promote:
  - { name: amount,   path: $.amount,   type: bigint }
  - { name: currency, path: $.currency, type: text }   # new column
```

```bash
lm apply -f stripe.yaml
```

**Result:** the next `lm connector run` does `ALTER TABLE ADD COLUMN` — no existing data is
rewritten. Check the run in the TUI (`:runs`), or see [CLI & TUI](../cli/lm-reference.md).

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| `429 Too Many Requests` from the API | `default_rate_limit` exceeds what the API tolerates | lower it (e.g. `100/s` → `50/s`) |
| Cursor stuck — sync stops picking up new pages | the stored cursor is stale or invalid | `lm endpoint reset-cursor <connector>/<endpoint>` → next run is a full refresh |
| `lm apply` refused with an unknown transform | a `promote` entry's `transform:` isn't one of the supported values | see [Transformers](transformers.md) for the supported list |
| Need to wipe an endpoint's data | — | `lm endpoint purge <connector>/<endpoint>` (requires confirmation) |
| Not sure the token/auth is valid | — | `lm source rest test <connector>` — pre-flight check, fetches 1 page, writes nothing |

## See also

- [Overview](index.md)
- [GraphQL](graphql.md) — the GraphQL request shape on this engine
- [SOAP](soap.md) — the SOAP request shape on this engine
- [Transformers](transformers.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
