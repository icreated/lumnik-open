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
    own, and put the one credential these manifests need in the hub's registry:

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
✓ docs/connectors/rest/demo/90-soap-querypage.yaml — valid
$ lm apply -f docs/connectors/rest/demo/90-soap-querypage.yaml
{"name":"acme-soap","connectorId":"b0a39e2f-29b3-43ed-be20-2526d23bc06c","endpointsApplied":1}
```

Before running anything, ask the service the first question a run would ask:

```
$ lm source rest test acme-soap
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  POST    200     500    ok
```

`METHOD` reads `POST`: the pre-flight composes the request a run composes — envelope, body
template, `SOAPAction` and all — so a SOAP endpoint is knocked on the way a run knocks. One
page, 500 customers, nothing written.

```
$ lm connector run acme-soap
run scheduled: df8459f5-ec4c-4269-bd87-ea8f046c3f08
$ lm run list --connector acme-soap
ID        CONNECTOR  ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
df8459f5  acme-soap  partners  Completed  2400  2400  0        0             2026-09-10 17:54  2026-09-10 17:54
```

What landed in `ext.acme_soap_partners` — the promoted columns, beside the whole payload:

```
 external_id |           name            |   city    | credit_limit |       updated_at       
-------------+---------------------------+-----------+--------------+------------------------
 1000        | Hydraulique Lyon 1000     | Lyon      |          500 | 2026-01-01 00:00:00+00
 1001        | Roulements Bruxelles 1001 | Bruxelles |          637 | 2026-01-01 00:01:00+00
 1002        | Fixations Milano 1002     | Milano    |          774 | 2026-01-01 00:02:00+00
```

Those are the same three partners the [REST quickstart](rest.md#quickstart) read out of JSON,
and that is the point: what changed is the envelope, not the table. `credit_limit` is a numeric
column and `updated_at` a `timestamptz` because the `promote` ladder said so — **XML carries no
types**, and every leaf arrived as a string. The table itself is the shared
[storage layout](rest.md#storage-layout).

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

`method` is implicit: always `POST`, with `Content-Type: text/xml; charset=utf-8`. The engine
sends SOAP **1.1** envelopes only — a service that requires SOAP 1.2
(`application/soap+xml`) is not supported in this release.

`page_size` is deliberately defaultless. It is not just the loop's stride: it is substituted
into the body as `{{page_size}}`. A default that did not match what the service actually
returns would skip or duplicate rows — the arithmetic and the request would disagree about the
same number.

## How it works

- The rendered body is wrapped in a `<soap:Envelope>` and POSTed to `base_url + path`.
- `{{cursor}}` is a **row offset**: `0` on the first request, `+page_size` per page. There is no
  next-page link and no total to read — QueryPage advances by arithmetic.
- The XML answer is parsed into a tree, and **namespace prefixes are stripped**
  (`ns1:Customer` → `Customer`) before any path is matched. That is why every path in the
  manifest above is written without a prefix while the service answers with them.
- The terminal segment of `response_path` is **force-listed**: one `<Customer>` or fifty, the
  run reads a list. XML has no arrays, and without that coercion a page holding a single element
  parses as an object and is lost without a word. (Limit: *intermediate* repeated segments are
  not coerced.)
- The parser is **XXE-hardened** — DTDs and external entities are disabled.

!!! warning "A SOAP endpoint does not chunk — size it accordingly"
    Every other reader in this family follows the pagination chain one chunk at a time. SOAP
    cannot: its resume state lives inside the strategy and cannot be rebuilt from a cursor, so
    a run fetches **all** its pages in one pass and holds the whole payload in memory. The
    quickstart's 2 400 partners cost five requests inside a single fetch. `lm run get <id> -o
    json` still reports `"chunkCount": 2` — the fetch, then the chunk that recognises its own
    run on the cursor and ends the stream without asking for anything.

## Body templating

Four placeholders are resolved on every request — in `body:` **and** `header:` alike:

| Placeholder | Resolves to |
|---|---|
| `{{page_size}}` | `pagination.page_size`, verbatim |
| `{{cursor}}` | the row offset of the page being fetched |
| `{{secret:NAME}}` / `{{env:NAME}}` | the hub's **process environment** — see below |

An **unresolved placeholder fails the run before the request is sent**: a template is never put
on the wire with a literal `{{...}}` inside it, and the value is never echoed in the error.
Anything else — `{{watermark}}`, a typo — is refused the same way.

### The credential the secret registry does not serve

This is the one thing on this page that will surprise you. `{{secret:NAME}}` and `{{env:NAME}}`
are synonyms, and **both** resolve from the hub's own process environment (an OS variable, or a
JVM system property as a dev/test fallback). The DB-backed `lm secret` registry is *not*
consulted — unlike `password_env` and `value_env`, which read that registry first.

Legacy EAI services often want their session token inside the envelope, where no auth provider
can put it. That is what `header:` is for:

```yaml
--8<-- "docs/connectors/rest/demo/94-soap-session.yaml"
```

Applied without the variable, the refusal happens **in the hub** — the service is never asked:

```
$ lm source rest test acme-soap-session
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  GET     -       0      unresolved secret/env placeholder: ACME_SESSION_TOKEN
Error: 1 of 1 enabled endpoint(s) did not answer with readable data
```

`STATUS -` is the column that matters: no request was composed at all, so nothing was sent. The
`METHOD` column falls back to the endpoint row's stored default and prints `GET` on an endpoint
this command only ever POSTs to — read `STATUS -` first.

`lm secret set ACME_SESSION_TOKEN …` would change nothing here. The variable has to exist where
the hub process runs, which for the container stack means one override file — it adds that
single variable to the hub service and changes nothing else:

```bash
docker compose -f docker-compose.selfhost.yml \
               -f docs/connectors/rest/demo/soap-session.override.yml up -d hub
```

Nothing about the manifest changes. The same endpoint, before and after, in one ledger:

```
$ lm run list --connector acme-soap-session
ID        CONNECTOR          ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
61e70fbd  acme-soap-session  partners  Completed  2400  2400  0        0             2026-09-10 18:12  2026-09-10 18:12
eaefa10b  acme-soap-session  partners  Failed     0     0     0        0             2026-09-10 17:57  2026-09-10 17:57
```

## Where the QueryPage loop stops

QueryPage has no total and no next link. It stops on exactly **one** signal, chosen by
configuration and never tried in sequence:

| Set | The loop continues |
|---|---|
| `last_page_path` | only while the flag reads explicitly `false` |
| else `has_more_path` | only while the flag reads explicitly `true` |
| neither | until a page comes back shorter than `page_size` |

Both flag rules are asymmetric about absence on purpose: a missing, null or unreadable flag
**stops**. A loop that kept going on a flag it could not read would hammer a legacy service
forever — offset arithmetic gives it no natural end. (Flags arrive as XML strings;
`"true"`/`"false"` are read as booleans.)

The quickstart covers `last_page_path`. Here are the other two, plus the trap underneath all of
them:

```yaml
--8<-- "docs/connectors/rest/demo/91-soap-stop-signals.yaml"
```

```
$ lm run list --connector acme-soap-stops
ID        CONNECTOR        ENDPOINT             STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
00ea7a5c  acme-soap-stops  single-element-tail  Completed  201   201   0        0             2026-09-10 17:54  2026-09-10 17:54
f5011cb7  acme-soap-stops  short-page           Completed  2400  2400  0        0             2026-09-10 17:54  2026-09-10 17:54
507ad6e8  acme-soap-stops  more-records         Completed  2400  2400  0        0             2026-09-10 17:54  2026-09-10 17:54
```

**201, not 200.** The `/tail` service serves a truncated 201-row dataset against a page size of
100, so its last page carries exactly one `<Customer>`. That row is the force-list's witness: it
is the one an engine that iterates blindly drops, and no error is raised when it does. Whenever
you meet a SOAP source, the count of a truncated read is the check worth running.

## When the service misbehaves

### A Fault fails the run — and is never retried

SOAP, like GraphQL, has an error channel that is not the HTTP status, and stacks use it two
ways: `<soap:Fault>` on HTTP 500, which is what SOAP 1.1 prescribes, and the same Fault on HTTP
200, which plenty of gateways send. Both must fail the run — the second is the dangerous one,
because an engine that trusts the status reads a body with no rows in it and reports a clean,
empty, successful sync.

```yaml
--8<-- "docs/connectors/rest/demo/92-soap-fault.yaml"
```

```
$ lm run list --connector acme-soap-fault
ID        CONNECTOR        ENDPOINT      STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
a4b52877  acme-soap-fault  fault-on-500  Failed  0   0    0        0             2026-09-10 17:55  2026-09-10 17:55
2e52e76a  acme-soap-fault  fault-on-200  Failed  0   0    0        0             2026-09-10 17:55  2026-09-10 17:55

$ lm run get a4b52877
ID        CONNECTOR        ENDPOINT      STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
a4b52877  acme-soap-fault  fault-on-500  Failed  0   0    0        0             2026-09-10 17:55  2026-09-10 17:55
✖ run failed: SOAP Fault: SBL-DAT-00500: no such Business Component 'Customer'
```

The message carries the service's own `faultstring`, verbatim. The refusal is total: no partial
ingestion, nothing dead-lettered — a query the service would not answer produced no rows to
quarantine — and neither endpoint's `ext.*` table was created at all.

A Fault is a **business** error: the service answered, and its answer is "no". Retrying it is
waiting for a different answer to the same wrong question, so the fault check runs *inside* the
retry boundary and refuses first. In the demo API's log, that run is one request per endpoint —
not five.

### The same 500, without a Fault, is retried

```yaml
--8<-- "docs/connectors/rest/demo/93-soap-retry.yaml"
```

The `/flaky` service answers 500 twice per page window before serving the page, and the body of
those 500s carries no Fault — an infrastructure hiccup, not an answer. The shared REST policy
applies unchanged: five attempts, transient on exactly `429, 500, 502, 503, 504, 408` (plus
network errors); every other status is permanent.

```
$ lm run list --connector acme-soap-retry
ID        CONNECTOR        ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
bcbf2d82  acme-soap-retry  flaky     Completed  2400  2400  0        0             2026-09-10 17:55  2026-09-10 17:55
```

Completed, all 2 400 rows — and the demo API's own terminal is where you see what it cost:

```
10/Sep/2026 17:55:34  "POST /flaky/start.swe HTTP/1.1" 500 -
10/Sep/2026 17:55:35  "POST /flaky/start.swe HTTP/1.1" 500 -
10/Sep/2026 17:55:37  "POST /flaky/start.swe HTTP/1.1" 200 -
10/Sep/2026 17:55:37  "POST /flaky/start.swe HTTP/1.1" 500 -
10/Sep/2026 17:55:38  "POST /flaky/start.swe HTTP/1.1" 500 -
10/Sep/2026 17:55:40  "POST /flaky/start.swe HTTP/1.1" 200 -
```

Three requests per window, with the backoff visible between them. Read those two runs together:
same server, same status code, opposite handling — **the body is the only thing that differs**.
The status says something went wrong; the body says whose fault it is.

!!! warning "Reset the counters before re-running"
    `curl http://127.0.0.1:8099/admin/reset`. The demo API's failure counters are per page
    window and they persist: a second run finds them already burnt in, retries nothing, and goes
    green for the wrong reason.

### What the front door refuses

`kind: soap` and `pagination.kind: soap` require each other, in both directions. `kind` picks
the request builder; `pagination.kind` picks the XML parser and the offset arithmetic. Either
half alone pairs a builder with a parser that cannot read it — not a crash, an empty run that
looks successful, which is why this is refused at the front door:

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

And a SOAP `response_path` cannot be a projection. Everywhere else in this family `[*]` is the
normal way to write one; here the parser has to *walk* those segments to reach the repeated
element and coerce it into a list. An operator makes that walk impossible — the path would still
match, and the run would still succeed, minus every page whose last element stood alone:

```
$ lm apply -f bad-paths.yaml
✗ bad-paths.yaml — 2 error(s), 0 warning(s)

  ERROR    spec.endpoints[0].pagination.response_path
          SOAP response_path must be a plain dotted path (e.g. $.Body.Out.List.Item) — JsonPath operators ([*], ..) bypass the force-list and cause silent single-element data loss
          → Remove operators; use a plain dotted path like $.Body.Out.ListOfItems.Item

  ERROR    spec.endpoints[1].pagination.page_size
          page_size is required when pagination.kind = soap
```

## Incremental — the mark rides the URL, not the body

`incremental: { mode: watermark }` works on a SOAP endpoint: the stored mark goes out on the
first request of a run and the new one is the MAX of `watermark_path` over everything the run
read.

```yaml
--8<-- "docs/connectors/rest/demo/95-soap-watermark.yaml"
```

```
$ lm run list --connector acme-soap-incremental
ID        CONNECTOR              ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
94bd6b1a  acme-soap-incremental  partners  Completed  1     1     0        0             2026-09-10 17:56  2026-09-10 17:56
fa948c13  acme-soap-incremental  partners  Completed  2400  2400  0        0             2026-09-10 17:56  2026-09-10 17:56
```

Run 1 reads 2 400 and stores the maximal `UpdatedAt`; run 2 sends it and reads the single row
sharing that instant — inclusive, like most sources, and re-upserted on its unique key.

!!! warning "Know this before you promise a customer an incremental SOAP feed"
    The mark is **not** woven into the body. There is no `{{watermark}}` placeholder — the
    table above is the whole list, and anything else fails the run. The mark rides in the URL
    query string, bolted onto the POST, which is what the demo service reads:

    ```
    10/Sep/2026 17:56:49  "POST /siebel/start.swe?updated_since=2026-01-02T15%3A59%3A00Z HTTP/1.1" 200 -
    ```

    A real SOAP service will almost certainly want that filter as an element inside the body
    instead, and this release gives no way to put it there. When it does, filter in the body
    with a literal and leave `incremental` at `full`.

## Connector-level headers

[`spec.headers[]`](rest.md#request-headers) applies to SOAP endpoints too — the entries ride
every page of the QueryPage loop. They are applied **before** auth (an auth-injected header wins
a name clash), and `Content-Type` and `SOAPAction` are set by the engine, so don't redeclare
them. Endpoint-level `headers` are refused at apply, exactly as for REST.

## How to…

### Point it at your own service

The manifest the TUI's `:new` picker hands you for `soap` is a Siebel EAI endpoint with nothing
demo about it:

```yaml
--8<-- "docs/connectors/soap/10-siebel-querypage.yaml"
```

**Result:** `lm apply -f siebel-customers.yaml` registers it; `lm source rest test
siebel-customers` knocks once before you commit to a run. Change four things and it is yours:
`base_url`, the `path` your EAI listener publishes, the operation names inside `body:` and the
`response_path` that names its repeated element.

### Read a second collection from the same service

Add a second endpoint with its own `body:` (a different QueryPage operation), its own
`response_path` and its own table. One connector, one service, one credential; the ledger
records one run per endpoint, exactly as the
[stop-signals connector](#where-the-querypage-loop-stops) shows with three.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| Run fails instantly with `unresolved secret/env placeholder` | the name is not in the **hub's** process environment | export it where the hub runs — the `lm secret` registry is not consulted for templates |
| Run fails on HTTP 500 with no retry | a `<soap:Fault>` is a business error, and is never retried | read the `faultstring` in `lm run get`; fix the request or the permission it needs |
| Run completed with `IN` 0 | `response_path` does not match the answer | remember the namespace strip — write the path **without** prefixes; `lm source rest test` prints the item count from one page |
| A run reads one row fewer than the source holds | `response_path` carries `[*]` or `..`, or an intermediate segment repeats | use a plain dotted path; the last page holding a single element is where the loss shows |
| Pagination stops after one page | the flag is missing, null or unreadable — which stops, by design | check `last_page_path` / `has_more_path` against the real answer, or set neither and rely on the short page |
| Pre-flight prints `METHOD GET` on a SOAP endpoint | no request was composed — read `STATUS -` | the verdict column carries the real reason |

## See also

- [REST](rest.md) — the engine underneath (auth providers, retry, rate limit, storage layout)
- [GraphQL](graphql.md) — the other request shape on that engine
- [The demo API](rest/demo-api.md) — the services these screens read
- [Overview](index.md)
- The WOW walkthrough ingests a fake Siebel over this connector — [see it end to end](../wow.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
