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

It logs one line per request on stdout. That terminal is the surest place to see what actually
left the hub — the query string a pagination strategy composed, the watermark a run sent.

## The ten dialects

The first path segment picks the envelope and never changes the data — which is the point: the
manifest you write for your own ERP differs from the ones on these pages by a base URL, a
credential and a path.

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

Both misbehaving dialects are deliberate: `/liar` is what gives [the declared
total](../rest.md#the-declared-total-a-witness-on-the-count) something to catch, and `/down`
and `/flaky` are the difference between an outage and a bad minute.

## The eight SOAP services

SOAP is not an eleventh dialect: it is one POST door per service, the operation lives in the XML
body, and the cursor is a row offset woven into that body. Same partners, in XML, with `ns:`
prefixes on every payload element — so the connector's namespace strip is under test and not
assumed.

| Path | What it imitates | Used by |
|---|---|---|
| `POST /siebel/start.swe` | Siebel QueryPage — `LastPage`, and a `SOAPAction` it checks | [Quickstart](../soap.md#quickstart) |
| `POST /sap/start.swe` | SAP PI — a `MoreRecords` flag instead | [Where the loop stops](../soap.md#where-the-querypage-loop-stops) |
| `POST /mute/start.swe` | a service that announces nothing at all | [Where the loop stops](../soap.md#where-the-querypage-loop-stops) |
| `POST /tail/start.swe` | 201 rows, so the last page holds **one** element | [Where the loop stops](../soap.md#where-the-querypage-loop-stops) |
| `POST /fault/start.swe` | `<soap:Fault>` on HTTP 500 | [A Fault fails the run](../soap.md#a-fault-fails-the-run-and-is-never-retried) |
| `POST /fault200/start.swe` | the same Fault on HTTP **200** | [A Fault fails the run](../soap.md#a-fault-fails-the-run-and-is-never-retried) |
| `POST /flaky/start.swe` | two 500s **without** a Fault, then the page | [The same 500, retried](../soap.md#the-same-500-without-a-fault-is-retried) |
| `POST /session/start.swe` | 401 unless a token rides in `<soap:Header>` | [The credential the registry does not serve](../soap.md#the-credential-the-secret-registry-does-not-serve) |

`/tail` is the one worth remembering: XML has no arrays, so a page holding a single element
parses as an object. It is there to make that row observable — 201 ingested rows means the
force-list held, 200 means it did not.

## Its three credentials

The connector reads them out of the hub's secret registry rather than out of a manifest:

```bash
lm secret set ACME_BASIC_PW        # demo-pass   — /spring, /offset, /hdr, /down and every SOAP service
lm secret set ACME_BEARER_TOKEN    # demo-token  — /v1 and /drf
lm secret set ACME_API_KEY         # demo-key    — /link and /graphql
```

A fourth value, the SOAP session token, deliberately does **not** go through this registry: a
`{{env:NAME}}` in a body or header template resolves from the hub's own process environment.
`demo/soap-session.override.yml` is the one gesture that puts it there — see
[the credential the secret registry does not serve](../soap.md#the-credential-the-secret-registry-does-not-serve).

!!! note "`host.docker.internal`, not `localhost`"
    The manifests point at `http://host.docker.internal:8099`. The hub runs in a container, and
    `localhost` inside it is that container — the address above is how it reaches your machine.
    The demo API listens on every interface for the same reason; pass it an address
    (`acme-api.py 8099 127.0.0.1`) to keep it to your own shell.

## Checking it before you blame the manifest

```bash
python3 docs/connectors/rest/demo/selftest.py
```

It walks every dialect **and the SOAP services** to their last page, asserts the 2 400 ids
arrive exactly once, and checks the ones that misbehave — that `/flaky` answers `429`, `503`,
`200` in that order, that `/tail` yields 201 rows, and that both fault services carry a
`<soap:Fault>`.
It exits non-zero when something is off, so a run that surprises you can be blamed on the right
side of the wire.

!!! warning "Reset the flaky counters between retry tests"
    `/flaky` burns two attempts **per query string** and then serves that page for good. A
    second run therefore finds the pages already burnt in, retries nothing, and goes green for
    the wrong reason. `curl http://127.0.0.1:8099/admin/reset` puts it back.

## What it is not

- **It does not parse GraphQL.** The `/graphql` handler looks for a resource name in the query
  text and reads `first:` with a regular expression. What is under test here is the connector,
  not a GraphQL engine. Its Relay cursors are base64 on purpose: a manifest that tries to build
  a cursor from the data instead of echoing back what `pageInfo` handed it fails here exactly as
  it would against a real API.
- **It does not parse SOAP either.** The services read `<PageSize>` and `<StartRowNum>` out of
  the request body with a regular expression, and answer a hand-written envelope. What is under
  test is the connector's QueryPage loop, not an XML stack.
- **`/open/` has no auth wall.** It exists so a manifest can exercise `auth: none`. The hole is
  in this demo server, not in the connector.
- **It is a throwaway rig.** Its credentials are fixed test values, and it is meant for
  loopback or a developer machine — not for anything that outlives the page you are reading.

## The manifests that read it

Fourteen manifests live beside it, each transcluded into the page that explains it:

| File | Reads | Page |
|---|---|---|
| `10-quickstart.yaml` | `/spring` | [Quickstart](../rest.md#quickstart) |
| `20-two-endpoints.yaml` | `/v1` + `/link` | [One connector, one API](../rest.md#one-connector-one-api) |
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
| `94-soap-session.yaml` | `/session` | [The credential the registry does not serve](../soap.md#the-credential-the-secret-registry-does-not-serve) |
| `95-soap-watermark.yaml` | `/siebel` | [Incremental](../soap.md#incremental-the-mark-rides-the-url-not-the-body) |

## See also

- [REST](../rest.md) — the connector these manifests drive
- [Pagination](pagination.md)
- [GraphQL](../graphql.md)
- [SOAP](../soap.md)
