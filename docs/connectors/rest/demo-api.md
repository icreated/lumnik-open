# The demo API

> One Python file, no dependencies, 2 400 fictional ACME partners served under ten path
> dialects, a GraphQL endpoint and eight SOAP services — the source every screen of the
> [REST](../rest.md), [GraphQL](../graphql.md) and [SOAP](../soap.md) pages reads.

It ships with the repo. Start it in a terminal of its own and leave it running:

```bash
python3 docs/connectors/rest/demo/acme-api.py
```

```
ACME Partners API on http://0.0.0.0:8099 — 2400 partners, dialects: down, drf, flaky, hdr, liar, link, offset, open, spring, v1 + POST /graphql
SOAP services (POST /<service>/start.swe): fault, fault200, flaky, mute, sap, session, siebel, tail
```

It logs one line per request on stdout: the query string a pagination strategy composed, the
watermark a run sent.

## The ten dialects

The first path segment picks the envelope; the data is the same under every dialect.

| Path | What it imitates | Used by |
|---|---|---|
| `/spring/partners` | Spring Data `Page` — `$.content` + `$.totalPages` | [Quickstart](../rest.md#quickstart) |
| `/v1/partners` | Stripe-style opaque cursor — `?starting_after=` | [Pagination](pagination.md) |
| `/drf/partners` | Django REST — `$.results` + a whole next URL in `$.next` | [Pagination](pagination.md) |
| `/link/partners` | an RFC 5988 `Link: …; rel="next"` header | [Pagination](pagination.md) |
| `/offset/partners` | offset + limit with a published `$.total` | [Incremental sync](../rest.md#incremental-sync) |
| `/open/partners` | the same, with no auth wall at all | `auth: none` |
| `/hdr/partners` | 400 to any request without `X-Api-Version` | [Request headers](../rest.md#request-headers) |
| `/liar/partners` | serves 2 300 rows while declaring 2 400 | [The declared total](../rest.md#the-declared-total-a-witness-on-the-count) |
| `/down/partners` | 500, forever | [When the source misbehaves](../rest.md#when-the-source-misbehaves) |
| `/flaky/partners` | 429 then 503 before each page, then 200 | [When the source misbehaves](../rest.md#when-the-source-misbehaves) |
| `POST /graphql` | Relay `edges` / `pageInfo` | [GraphQL](../graphql.md) |

## The eight SOAP services

One POST path per service; the operation and the row offset are in the XML body. Same
partners, in XML, with `ns:` prefixes on every payload element.

| Path | What it imitates | Used by |
|---|---|---|
| `POST /siebel/start.swe` | Siebel QueryPage — `LastPage`, and a `SOAPAction` it checks | [Quickstart](../soap.md#quickstart) |
| `POST /sap/start.swe` | SAP PI — a `MoreRecords` flag instead | [Where the loop stops](../soap.md#where-the-querypage-loop-stops) |
| `POST /mute/start.swe` | a service that announces nothing at all | [Where the loop stops](../soap.md#where-the-querypage-loop-stops) |
| `POST /tail/start.swe` | 201 rows, so the last page holds **one** element | [Where the loop stops](../soap.md#where-the-querypage-loop-stops) |
| `POST /fault/start.swe` | `<soap:Fault>` on HTTP 500 | [A Fault fails the run](../soap.md#a-fault-fails-the-run-and-is-never-retried) |
| `POST /fault200/start.swe` | the same Fault on HTTP **200** | [A Fault fails the run](../soap.md#a-fault-fails-the-run-and-is-never-retried) |
| `POST /flaky/start.swe` | two 500s **without** a Fault, then the page | [The same 500, retried](../soap.md#the-same-500-without-a-fault-is-retried) |
| `POST /session/start.swe` | 401 unless a token rides in `<soap:Header>` | [A credential inside the envelope](../soap.md#a-credential-inside-the-envelope) |

`/tail` ends on a page holding a single element: a correct run ingests 201 rows, not 200.

## Its three credentials

Put them in the hub's secret registry; the manifests name them:

```bash
lm secret set ACME_BASIC_PW        # demo-pass   — /spring, /offset, /hdr, /liar, /down, /flaky and every SOAP service
lm secret set ACME_BEARER_TOKEN    # demo-token  — /v1 and /drf
lm secret set ACME_API_KEY         # demo-key    — /link and /graphql
lm secret set ACME_SESSION_TOKEN   # demo-session-42 — /session, inside the SOAP envelope
```

!!! note "`host.docker.internal`, not `localhost`"
    The manifests point at `http://host.docker.internal:8099`. The hub runs in a container, and
    `localhost` inside it is that container — the address above is how it reaches your machine.
    The demo API listens on every interface for the same reason; pass it an address
    (`acme-api.py 8099 127.0.0.1`) to keep it to your own shell.

## Checking it before you blame the manifest

```bash
python3 docs/connectors/rest/demo/selftest.py
```

It walks every dialect and SOAP service to its last page and checks that the 2 400 ids arrive
exactly once, `/flaky` answers `429`, `503`, `200`, `/tail` yields 201 rows and both fault
services carry a `<soap:Fault>`. It exits non-zero when something is off.

!!! warning "Reset the flaky counters between retry tests"
    `/flaky` fails twice **per query string**, then serves that page for good: a second run
    retries nothing. `curl http://127.0.0.1:8099/admin/reset` puts it back.

## What it is not

- **Not a GraphQL or SOAP engine.** It answers fixed shapes; what is under test is the
  connector.
- **A throwaway rig.** `/open/` has no auth wall, and the credentials are fixed test values:
  run it on loopback or a developer machine only.

## The manifests that read it

Fourteen manifests live beside it, each transcluded into the page that explains it:

| File | Reads | Page |
|---|---|---|
| `10-quickstart.yaml` | `/spring` | [Quickstart](../rest.md#quickstart) |
| `20-two-endpoints.yaml` | `/v1` + `/drf` | [One connector, one API](../rest.md#one-connector-one-api) |
| `30-link-header.yaml` | `/link` | [Pagination](pagination.md) |
| `40-watermark.yaml` | `/offset` | [Incremental sync](../rest.md#incremental-sync) |
| `50-total-witness.yaml` | `/liar` + `/hdr` | [The declared total](../rest.md#the-declared-total-a-witness-on-the-count) |
| `60-source-down.yaml` | `/down` + `/flaky` | [When the source misbehaves](../rest.md#when-the-source-misbehaves) |
| `70-graphql-relay.yaml` | `POST /graphql` | [GraphQL](../graphql.md#quickstart) |
| `80-graphql-errors.yaml` | `POST /graphql` | [GraphQL](../graphql.md#the-two-ways-a-graphql-server-says-no-on-http-200) |
| `90-soap-querypage.yaml` | `/siebel` | [SOAP](../soap.md#quickstart) |
| `91-soap-stop-signals.yaml` | `/sap` + `/mute` + `/tail` | [Where the loop stops](../soap.md#where-the-querypage-loop-stops) |
| `92-soap-fault.yaml` | `/fault` + `/fault200` | [A Fault fails the run](../soap.md#a-fault-fails-the-run-and-is-never-retried) |
| `93-soap-retry.yaml` | `/flaky` | [The same 500, retried](../soap.md#the-same-500-without-a-fault-is-retried) |
| `94-soap-session.yaml` | `/session` | [A credential inside the envelope](../soap.md#a-credential-inside-the-envelope) |
| `95-soap-watermark.yaml` | `/siebel` | [Incremental](../soap.md#incremental-the-mark-rides-the-url-not-the-body) |

## See also

- [REST](../rest.md) — the connector these manifests drive
- [Pagination](pagination.md)
- [GraphQL](../graphql.md)
- [SOAP](../soap.md)
