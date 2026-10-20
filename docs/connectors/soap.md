# SOAP

> Ingest a legacy SOAP service (Siebel, SAP PI) by putting the `<soap:Body>` in the manifest —
> a request shape on the REST engine (`connector_type` stays `rest-generic`), with QueryPage
> pagination.

| | |
|---|---|
| **Type** | `rest-generic` (shape `soap`) |
| **Protocol** | SOAP 1.1 over POST |
| **Target** | `ext.*` hybrid |
| **Schedulable** | cron |
| **Edition** | Open |

Auth, retry, rate limiting and the hybrid JSONB + typed storage are all shared with
[REST](rest.md). This page covers what is different: the body template, QueryPage pagination,
and the error channel that is not the HTTP status.

## Quickstart

!!! note "Every screen on this page reads a demo service that ships with the repo"
    The same Python file the REST page uses answers SOAP on `POST /<service>/start.swe` — eight
    services over the same 2 400 fictional ACME partners, in XML. Start it in a terminal of its
    own, and put the credential these manifests need in the hub's registry:

    ```bash
    python3 docs/connectors/rest/demo/acme-api.py
    lm secret set ACME_BASIC_PW        # demo-pass
    ```

    The manifests point at `http://host.docker.internal:8099`, because the hub runs in a
    container and `localhost` inside it is that container. What each service imitates, and
    which ones misbehave on purpose: [The demo API](rest/demo-api.md).

```yaml
--8<-- "docs/connectors/rest/demo/90-soap-querypage.yaml"
```

```
$ lm validate -f docs/connectors/rest/demo/90-soap-querypage.yaml
✓ 90-soap-querypage.yaml — valid
$ lm apply -f docs/connectors/rest/demo/90-soap-querypage.yaml
{"name":"acme-soap","connectorId":"d4f12957-40b8-4508-926d-32e4fb170ad5","endpointsApplied":1}
```

Before running anything, send the first request a run would send:

```
$ lm source rest test acme-soap
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  POST    200     500    ok
```

The pre-flight sends the request a run sends — envelope, body template, `SOAPAction` — for one
page. Nothing is written.

```
$ lm connector run acme-soap
run scheduled: 45b9d0b8-ffb9-4bbf-ab2a-95fe42e03d70
$ lm run list --connector acme-soap
ID        CONNECTOR  ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
45b9d0b8  acme-soap  partners  Completed  2400  2400  0        0             2026-10-19 01:46  2026-10-19 01:46
```

What landed in `ext.acme_soap_partners` — the promoted columns, beside the whole payload:

```
lumnik=> SELECT external_id, name, city, credit_limit, updated_at
lumnik->   FROM ext.acme_soap_partners ORDER BY external_id LIMIT 3;
 external_id |           name            |   city    | credit_limit |       updated_at       
-------------+---------------------------+-----------+--------------+------------------------
 1000        | Hydraulique Lyon 1000     | Lyon      |          500 | 2026-01-01 00:00:00+00
 1001        | Roulements Bruxelles 1001 | Bruxelles |          637 | 2026-01-01 00:01:00+00
 1002        | Fixations Milano 1002     | Milano    |          774 | 2026-01-01 00:02:00+00
```

The same partners as the [REST quickstart](rest.md#quickstart), in the same
[storage layout](rest.md#storage-layout). XML carries no types: `credit_limit` and
`updated_at` are typed by their `promote` entries.

## The manifest

| Field | Where | Required | Notes |
|---|---|---|---|
| `kind: soap` | endpoint | yes | Picks the request builder — and demands `pagination.kind: soap` |
| `path` | endpoint | yes | The POST target, appended to `base_url` (e.g. `/eai_enu/start.swe`) |
| `body` | endpoint | yes | The **inner** `<soap:Body>` XML — the hub wraps the SOAP 1.1 envelope |
| `soap_action` | endpoint | no | Sent as the `SOAPAction` header (an empty quoted string when absent) |
| `header` | endpoint | no | The inner `<soap:Header>` XML — same substitution as `body` |
| `pagination.kind: soap` | endpoint | yes | Picks the XML parser and the offset arithmetic |
| `page_size` | pagination | yes | Rows per page, injected as `{{page_size}}` — **explicit, no default** |
| `response_path` | pagination | yes | A **plain dotted path** to the repeated element |
| `last_page_path` / `has_more_path` | pagination | no | One stop signal — see [Where the QueryPage loop stops](#where-the-querypage-loop-stops) |
| `target` | endpoint | yes | The same hybrid target as REST: `table`, `unique_key`, `promote` |

- The request is always a `POST` with `Content-Type: text/xml; charset=utf-8`.
- SOAP **1.1** envelopes only; a service that requires SOAP 1.2 (`application/soap+xml`) is not
  supported.
- Set `page_size` to the number of rows the service actually returns per page.

## How it works

- The rendered body is wrapped in a `<soap:Envelope>` and POSTed to `base_url + path`.
- `{{cursor}}` is a **row offset**: `0` on the first request, `+page_size` per page.
- **Namespace prefixes are stripped** (`ns1:Customer` → `Customer`): write every path without
  prefixes.
- The last segment of `response_path` is always read as a list, even when a page holds a single
  element. A repeated *intermediate* segment is not.
- DTDs and external entities are disabled.

!!! warning "A SOAP endpoint reads all its pages in one pass, in memory — size it accordingly"
    The quickstart's 2 400 partners are five requests in a single fetch.

## Body templating

Four placeholders are resolved on every request — in `body:` **and** `header:` alike:

| Placeholder | Resolves to |
|---|---|
| `{{page_size}}` | `pagination.page_size`, verbatim |
| `{{cursor}}` | the row offset of the page being fetched |
| `{{secret:NAME}}` | the `lm secret` registry, then the hub's process environment — see below |
| `{{env:NAME}}` | the hub's process environment only |

An **unresolved placeholder fails the run before any request is sent**; the error names the
placeholder, never a value. Any other placeholder (`{{watermark}}`, a typo) fails the same way.

### A credential inside the envelope

`{{secret:NAME}}` reads the `lm secret` registry first, then the hub's process environment (an
OS variable, or a JVM system property), like `password_env`. `{{env:NAME}}` reads the hub's
process environment only.

A session token inside the envelope goes in `header:`:

```yaml
--8<-- "docs/connectors/rest/demo/94-soap-session.yaml"
```

Without the secret, nothing is sent:

```
$ lm source rest test acme-soap-session
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  POST    -       0      unresolved secret/env placeholder: ACME_SESSION_TOKEN
Error: 1 of 1 enabled endpoint(s) did not answer with readable data
```

`STATUS -`: no request was composed. When no request is composed, `METHOD` shows the endpoint's stored method — `POST` for a SOAP endpoint unless the manifest sets one.

Store it, and the next run sends it; the manifest does not change:

```bash
lm secret set ACME_SESSION_TOKEN        # demo-session-42
```

## Where the QueryPage loop stops

QueryPage has no total and no next link. It stops on exactly **one** signal, chosen by
configuration and never tried in sequence:

| Set | The loop continues |
|---|---|
| `last_page_path` | only while the flag reads explicitly `false` |
| else `has_more_path` | only while the flag reads explicitly `true` |
| neither | until a page comes back shorter than `page_size` |

A missing, null or unreadable flag **stops** the loop. `"true"`/`"false"` strings are read as
booleans.

The quickstart uses `last_page_path`; here are the other two, plus a single-element last page:

```yaml
--8<-- "docs/connectors/rest/demo/91-soap-stop-signals.yaml"
```

```
$ lm run list --connector acme-soap-stops
ID        CONNECTOR        ENDPOINT             STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
4ad8f592  acme-soap-stops  single-element-tail  Completed  201   201   0        0             2026-10-19 01:46  2026-10-19 01:46
9ace6ce2  acme-soap-stops  short-page           Completed  2400  2400  0        0             2026-10-19 01:46  2026-10-19 01:46
993bbd1a  acme-soap-stops  more-records         Completed  2400  2400  0        0             2026-10-19 01:46  2026-10-19 01:46
```

**201, not 200**: `/tail` holds 201 rows at a page size of 100, so its last page carries one
`<Customer>`, and it is ingested. On a new SOAP source, compare the row count with the source's.

## When the service misbehaves

### A Fault fails the run — and is never retried

A `<soap:Fault>` fails the run, whether it arrives on HTTP 500 (SOAP 1.1) or on HTTP 200 (some
gateways):

```yaml
--8<-- "docs/connectors/rest/demo/92-soap-fault.yaml"
```

```
$ lm run list --connector acme-soap-fault
ID        CONNECTOR        ENDPOINT      STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
0de9fa56  acme-soap-fault  fault-on-200  Failed  0   0    0        0             2026-10-19 01:46  2026-10-19 01:46
8d179cc0  acme-soap-fault  fault-on-500  Failed  0   0    0        0             2026-10-19 01:46  2026-10-19 01:46

$ lm run get 8d179cc0
ID        CONNECTOR        ENDPOINT      STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
8d179cc0  acme-soap-fault  fault-on-500  Failed  0   0    0        0             2026-10-19 01:46  2026-10-19 01:46
✖ run failed: SOAP Fault: SBL-DAT-00500: no such Business Component 'Customer'
```

- The message carries the service's `faultstring` verbatim.
- Nothing is ingested or dead-lettered; neither endpoint's `ext.*` table is created.
- A Fault is **never retried**: the demo API logs one request per endpoint.

### The same 500, without a Fault, is retried

```yaml
--8<-- "docs/connectors/rest/demo/93-soap-retry.yaml"
```

The `/flaky` service answers 500 **without** a Fault twice per page before serving it. The
[REST retry policy](rest.md#retry-page-cap-rate-limit) applies unchanged.

```
$ lm run list --connector acme-soap-retry
ID        CONNECTOR        ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
520b32f6  acme-soap-retry  flaky     Completed  2400  2400  0        0             2026-10-19 01:46  2026-10-19 01:46
```

The demo API's log shows three requests per page, with the backoff between them:

```
19/Oct/2026 01:46:32  "POST /flaky/start.swe HTTP/1.1" 500 -
19/Oct/2026 01:46:33  "POST /flaky/start.swe HTTP/1.1" 500 -
19/Oct/2026 01:46:34  "POST /flaky/start.swe HTTP/1.1" 200 -
19/Oct/2026 01:46:34  "POST /flaky/start.swe HTTP/1.1" 500 -
19/Oct/2026 01:46:35  "POST /flaky/start.swe HTTP/1.1" 500 -
19/Oct/2026 01:46:37  "POST /flaky/start.swe HTTP/1.1" 200 -
```

Same status code as the Fault above, opposite handling: a 500 with a Fault fails at once, a 500
without one is retried.

!!! warning "Reset the counters before re-running"
    `curl http://127.0.0.1:8099/admin/reset`. Otherwise a second run finds the counters spent
    and retries nothing.

### What the front door refuses

`kind: soap` and `pagination.kind: soap` require each other:

```
$ lm apply -f bad-soap.yaml
✗ bad-soap.yaml — 2 error(s), 0 warning(s)

  ERROR    spec.endpoints[0].pagination.kind
          kind=soap endpoints require pagination.kind=soap
          → Use: pagination: { kind: soap, response_path: ... }

  ERROR    spec.endpoints[1].pagination.kind
          pagination.kind=soap requires endpoints[].kind=soap
          → Set kind: soap on the endpoint

Fix errors above before running 'lm apply -f bad-soap.yaml'.
Error: validation failed: 2 error(s)
```

A SOAP `response_path` must be a plain dotted path (no `[*]`, no `..`), and `page_size` is
required:

```
$ lm apply -f bad-paths.yaml
✗ bad-paths.yaml — 2 error(s), 0 warning(s)

  ERROR    spec.endpoints[0].pagination.response_path
          SOAP response_path must be a plain dotted path (e.g. $.Body.Out.List.Item) — JsonPath operators ([*], ..) bypass the force-list and cause silent single-element data loss
          → Remove operators; use a plain dotted path like $.Body.Out.ListOfItems.Item

  ERROR    spec.endpoints[1].pagination.page_size
          page_size is required when pagination.kind = soap

Fix errors above before running 'lm apply -f bad-paths.yaml'.
Error: validation failed: 2 error(s)
```

## Incremental — the mark rides the URL, not the body

`incremental: { mode: watermark }` works on a SOAP endpoint: the stored mark goes out with the
run's first request; the new mark is the MAX of `watermark_path` over the rows read.

```yaml
--8<-- "docs/connectors/rest/demo/95-soap-watermark.yaml"
```

```
$ lm run list --connector acme-soap-incremental
ID        CONNECTOR              ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
7f2e0dbf  acme-soap-incremental  partners  Completed  1     1     0        0             2026-10-19 01:46  2026-10-19 01:46
e5a98a0f  acme-soap-incremental  partners  Completed  2400  2400  0        0             2026-10-19 01:46  2026-10-19 01:46
```

Run 1 reads 2 400 rows; run 2 sends the mark and reads the one row sharing that instant
(inclusive filter), re-upserted on its key.

!!! warning "Know this before you promise a customer an incremental SOAP feed"
    The mark goes in the URL query string, not in the body — there is no `{{watermark}}`
    placeholder:

    ```
    19/Oct/2026 01:46:44  "POST /siebel/start.swe?updated_since=2026-01-02T15%3A59%3A00Z HTTP/1.1" 200 -
    ```

    A service that expects the filter inside the body ignores it. Then filter in the body with a
    literal and leave `incremental` at `full`.

## Connector-level headers

[`spec.headers[]`](rest.md#request-headers) applies to every SOAP request, as for REST. Do not
redeclare `Content-Type` or `SOAPAction`: the engine sets them.

## How to…

### Point it at your own service

The TUI's `:new` picker hands you this manifest for `soap` — a Siebel EAI endpoint:

```yaml
--8<-- "docs/connectors/soap/10-siebel-querypage.yaml"
```

Change `base_url`, the `path` your EAI listener publishes, the operation names inside `body:`
and the `response_path`. Then:

```bash
lm apply -f siebel-customers.yaml
lm source rest test siebel-customers
```

### Read a second collection from the same service

Add a second endpoint with its own `body:`, `response_path` and table. The ledger records one
run per endpoint, as the [stop-signals connector](#where-the-querypage-loop-stops) shows.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| Run fails instantly with `unresolved secret/env placeholder` | `secret:` — the name is neither in `lm secret` nor in the hub's environment; `env:` — it is not in the hub's environment | `lm secret set NAME` for `secret:`; the hub's environment for `env:` |
| Run fails on HTTP 500 with no retry | a `<soap:Fault>` is never retried | read the `faultstring` in `lm run get`; fix the request or the permission it needs |
| Run completed with `IN` 0 | `response_path` does not match the answer | remember the namespace strip — write the path **without** prefixes; `lm source rest test` prints the item count from one page |
| A run reads one row fewer than the source holds | `response_path` carries `[*]` or `..`, or an intermediate segment repeats | use a plain dotted path; the last page holding a single element is where the loss shows |
| Pagination stops after one page | the flag is missing, null or unreadable, which stops the loop | check `last_page_path` / `has_more_path` against the real answer, or set neither and rely on the short page |
| Pre-flight prints `METHOD GET` on a SOAP endpoint | the endpoint was applied by a hub that stored `GET` when the manifest set no `method`; the run sends `POST` anyway | `lm apply` the manifest again to store `POST` |

## See also

- [REST](rest.md) — the engine underneath (auth providers, retry, rate limit, storage layout)
- [GraphQL](graphql.md) — the other request shape on that engine
- [The demo API](rest/demo-api.md) — the services these screens read
- [Overview](index.md)
- The WOW walkthrough ingests a fake Siebel over this connector — [see it end to end](../wow.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
