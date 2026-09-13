# REST connector — the assets under this directory

Two sets of files live here, with different jobs. Neither is a page: `connectors/rest/README.md`
is excluded from the published site.

## `demo/` — the bench the documentation reads

`demo/acme-api.py` is a single-file HTTP source, standard library only, serving the same 2 400
fictional ACME partners under ten conventions (Spring page numbers, Stripe cursors, DRF links,
an RFC 5988 `Link` header, offset/limit, a Relay GraphQL endpoint, and four that misbehave on
purpose) plus eight SOAP services on `POST /<service>/start.swe`. Every screen on
[rest.md](../rest.md), [graphql.md](../graphql.md) and [soap.md](../soap.md) was captured
against it, and the manifests beside it are the ones those pages transclude. The published
description of the bench — dialect by dialect, with what each one imitates and which ones lie —
is [demo-api.md](demo-api.md); keep that page and this section from drifting apart.

```bash
python3 docs/connectors/rest/demo/acme-api.py     # http://0.0.0.0:8099, one log line per request
python3 docs/connectors/rest/demo/selftest.py     # walks every dialect to its last page

lm secret set ACME_BASIC_PW       # demo-pass
lm secret set ACME_BEARER_TOKEN   # demo-token
lm secret set ACME_API_KEY        # demo-key

lm apply -f docs/connectors/rest/demo/10-quickstart.yaml
lm connector run acme-partners
```

| Manifest | Covers |
|---|---|
| `10-quickstart.yaml` | preset `spring-page`, Basic auth, typed promote + `parse_iso8601` |
| `20-two-endpoints.yaml` | one connector, two conventions: preset `stripe` and preset `drf-page`, Bearer |
| `30-link-header.yaml` | `link-header` pagination, API key in a header |
| `40-watermark.yaml` | `incremental: watermark`, staged promotion, `reset-cursor` |
| `50-total-witness.yaml` | `total_path` failing a run that lost rows; `spec.headers` |
| `60-source-down.yaml` | a permanent 500 fails the run; a flaky endpoint completes |
| `70-graphql-relay.yaml` | Relay pagination, `cursor_binding: variable` |
| `80-graphql-errors.yaml` | HTTP 200 carrying `errors[]`, and `data: null` — both must fail |
| `90-soap-querypage.yaml` | `kind: soap`, the two body placeholders, typed promote from XML strings |
| `91-soap-stop-signals.yaml` | `has_more_path`, the short-page fallback, and the single-element last page |
| `92-soap-fault.yaml` | a `<soap:Fault>` on 500 **and** on 200 — both must fail, neither is retried |
| `93-soap-retry.yaml` | a 500 with no Fault: the same status, retried to completion |
| `94-soap-session.yaml` | `header:` + `{{env:NAME}}` — the credential the `lm secret` registry does not serve |
| `95-soap-watermark.yaml` | `incremental: watermark` over SOAP, and the URL-query seam it lands on |

`94-soap-session.yaml` is the one manifest that needs more than a secret: its
`{{env:ACME_SESSION_TOKEN}}` resolves from the hub's own process environment, which is what
`demo/soap-session.override.yml` puts there.

The manifests point at `http://host.docker.internal:8099`, which is how the hub's container
reaches your machine. Reset the flaky counters between runs with
`curl http://127.0.0.1:8099/admin/reset`, and clean up with
`lm connector delete <name>` — it drops the connector's `ext.*` tables with it.

## The five top-level manifests — the proof corpus

`10-offset-none.yaml` … `50-relay-basic.yaml` point at WireMock on port 8081 (the `wiremock`
service of the repo's **development** compose file, whose stubs live in
`scripts/proof/rest/wiremock/mappings/`). They are driven by `scripts/proof/rest/00-rest-proof.sh`,
and `20-page-basic.yaml` is also the template `lm new` copies for a `rest-generic` source. They
are developer terrain: on a self-hosted install, `docker compose up` without a file argument
picks up that development compose and replaces the running stack.
