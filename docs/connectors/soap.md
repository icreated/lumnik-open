# SOAP

> Ingest legacy SOAP services (Siebel, SAP PI) with a templated `<soap:Body>` and
> QueryPage-style pagination — a request shape on the REST engine.

| | |
|---|---|
| **Type** | `rest-generic` (shape `soap`) |
| **Protocol** | SOAP 1.1 over POST |
| **Target** | `ext.*` hybrid |
| **Schedulable** | cron |
| **Edition** | Open |

Auth, rate-limiting, retry, and storage are all shared with [REST](rest.md) — this page
covers only what's different: the body template, QueryPage pagination, and fault handling.

## Full example — Siebel QueryPage

The real, validator-true manifest (the same file the tests exercise):

```yaml
--8<-- "docs/connectors/soap/10-siebel-querypage.yaml"
```

## Endpoint fields (`kind: soap`)

| Field | Required | Meaning |
|---|---|---|
| `id` | yes | endpoint identifier (kebab-case) |
| `path` | yes | POST target, relative to `base_url` |
| `body` | yes | the **inner** `<soap:Body>` XML template (lumnik wraps the SOAP 1.1 envelope) |
| `soap_action` | no | sent as the `SOAPAction` header |
| `header` | no | inner `<soap:Header>` XML template |
| `pagination.kind: soap` | yes | see below — `kind: soap` endpoints require it, and vice-versa |

Method is implicitly `POST` with `Content-Type: text/xml`. The engine sends SOAP 1.1
envelopes only — a service that requires SOAP 1.2 (`application/soap+xml`) is not supported
in this release.

## Body templating

Placeholders resolved on every request — in `body:` **and** `header:` alike, same substitution:

- `{{page_size}}` / `{{cursor}}` — pagination state. `cursor` is the row offset, starting at
  **0**: the QueryPage example's `{{cursor}}` therefore sends `0` (`StartRowNum`) on the first
  request, then advances by `page_size` each page.
- `{{secret:NAME}}` / `{{env:NAME}}` — both resolve from the hub's **process environment**
  (OS env var first, JVM system property as dev/test fallback). The DB-backed `lm secret`
  registry is *not* consulted here — export the variable where the hub runs.

An **unresolved placeholder fails the run fast** — a template is never sent with a literal
`{{...}}` left inside. `header:` is optional — use it when the service expects auth or
session elements inside `<soap:Header>` rather than in the body.

## Connector-level headers

[`spec.headers[]`](rest.md#request-headers) applies to SOAP endpoints too — the entries
ride every page of the QueryPage loop. Applied **before** auth (auth-injected headers win on a
name clash); `Content-Type` and `SOAPAction` are set by the engine, so don't redeclare them.
Endpoint-level `headers` are refused at apply, exactly as for REST endpoints.

## Pagination (`kind: soap`)

Offset arithmetic in the QueryPage style:

| Field | Required | Meaning |
|---|---|---|
| `page_size` | yes | rows per page; injected as `{{page_size}}` — **explicit, no default** |
| `response_path` | yes | **plain dotted path** to the repeated element (e.g. `$.Body.Out.ListOf.Customer`) |
| `last_page_path` | no | path to a boolean "this is the last page" |
| `has_more_path` | no | path to a boolean "there are more pages" |

**One stop signal applies — chosen by configuration, not tried in sequence:**

- `last_page_path` set → it is authoritative: pagination continues **only while the flag is
  explicitly `false`**; a `true`, missing, or null flag stops (never loops forever).
- else `has_more_path` set → continues only while explicitly `true`; missing/null stops.
- else → stop on a page shorter than `page_size`.

(Flags arrive as XML strings — `"true"`/`"false"` are accepted as booleans.)

`page_size` is deliberately defaultless: a silent default that mismatched the service's real
page size would skip or duplicate rows wherever the body template uses `{{page_size}}`. The
validator requires the key; a non-positive or non-numeric value **fails the run fast**, before
any request is sent.

!!! warning "response_path must be a plain dotted path"
    JsonPath operators (`[*]`, `..`) are **rejected by the validator** for SOAP pagination —
    with the XML-to-tree conversion they would silently match a single element and lose data.

## Response handling

- **XXE-hardened**: DTDs and external entities are disabled in the XML parser.
- Namespace prefixes are stripped (`ns1:Customer` → `Customer`) before path matching.
- The terminal segment of `response_path` is force-listed — one `<Customer>` or fifty, you always
  get a list. (v1 limit: *intermediate* repeated segments are not coerced.)
- A **SOAP Fault fails the run** (no partial ingestion) — and is **never retried**: SOAP 1.1
  faults arrive with HTTP 500, but the fault check runs before status-based retry, so a fault is
  treated as a business error, not a transient one.
- Plain HTTP failures retry as transient on **exactly** `429, 500, 502, 503, 504, 408` (plus
  network errors) — the shared REST policy. Every other status (`501`, `505`, any other
  `4xx`, …) is permanent.

## How to…

### Connect a Siebel QueryPage service

Point `path` at the SOAP endpoint, template the inner `<soap:Body>` with `{{page_size}}` /
`{{cursor}}`, and set `pagination.kind: soap` with a `response_path` that names the repeated
element:

```yaml
--8<-- "docs/connectors/soap/10-siebel-querypage.yaml"
```

**Result:** `lm apply -f siebel-customers.yaml` registers the connector; `lm connector run
siebel-customers` walks the QueryPage loop offset by offset, writing rows into
`ext.siebel_customers`. Watch it land in the TUI (`:runs`), or see
[CLI & TUI](../cli/lm-reference.md).

### Pass a secret into the body template

Any field in `body:` (or `header:`) can carry `{{secret:NAME}}` or `{{env:NAME}}` alongside
the pagination placeholders — for example, an API token woven into the Siebel body above:

```yaml
body: |
  <CustomerQueryPage_Input>
    <PageSize>{{page_size}}</PageSize>
    <StartRowNum>{{cursor}}</StartRowNum>
    <ApiToken>{{env:NAME}}</ApiToken>
    <ListOfCustomer><Customer><Id query="*"/><Name query="*"/></Customer></ListOfCustomer>
  </CustomerQueryPage_Input>
```

`{{env:NAME}}` (and `{{secret:NAME}}`) resolve from the hub's **process environment** — an OS
env var, or a JVM system property as a dev/test fallback. This is **not** the `lm secret`
registry: export the variable where the hub process itself runs, e.g.:

```bash
export NAME=...
```

**Result:** the placeholder is substituted into the request body on every call; if the
variable isn't set where the hub runs, the run fails fast rather than sending a literal
`{{env:NAME}}`. Check the run in the TUI (`:runs`), or see
[CLI & TUI](../cli/lm-reference.md).

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| Run fails immediately with an unresolved `{{...}}` | the placeholder name isn't found in the hub's process environment | export it where the hub runs (OS env var, or a JVM system property for dev/test) |
| Run fails on HTTP 500 with no retry | a SOAP Fault is a business error, not a transient one — it is never retried | fix the request; faults are not retried by design |
| Pagination loops or stops early | the wrong stop signal is set (or two conflicting ones) | set exactly one of `last_page_path` / `has_more_path` — or rely on the short-page fallback by setting neither |
| `response_path` rejected at validate | JsonPath operators (`[*]`, `..`) are refused for SOAP pagination | use a plain dotted path only (e.g. `$.Body.Out.ListOf.Customer`) |

## See also

- [REST](rest.md) — the engine underneath (auth providers, retry, rate limit, storage layout)
- [Overview](index.md)
- The WOW walkthrough ingests a fake Siebel over this connector — [see it end to end](../wow.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
