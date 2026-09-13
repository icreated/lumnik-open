# GraphQL

> Ingest a GraphQL API by putting the query in the manifest — a request shape on the REST
> engine (`connector_type` stays `rest-generic`), with Relay pagination required.

| | |
|---|---|
| **Type** | `rest-generic` (shape `graphql`) |
| **Protocol** | GraphQL over POST |
| **Target** | `ext.*` hybrid |
| **Schedulable** | cron |
| **Edition** | Open |

Auth, retry, rate limiting and the hybrid JSONB + typed storage are all shared with
[REST](rest.md). This page covers what is different: the query, Relay pagination, and what
happens when the server answers `200` and still says no.

## Quickstart

The screens below read [the demo API](rest/demo-api.md) that ships with the repo — the same one
the REST page uses, under its `POST /graphql` shape. Start it, and put its key in the registry:

```bash
python3 docs/connectors/rest/demo/acme-api.py
lm secret set ACME_API_KEY        # demo-key
```

```yaml
--8<-- "docs/connectors/rest/demo/70-graphql-relay.yaml"
```

```
$ lm apply -f docs/connectors/rest/demo/70-graphql-relay.yaml
{"name":"acme-graphql","connectorId":"3d47bfdd-ce56-463b-9669-f528eb9c56af","endpointsApplied":1}
$ lm source rest test acme-graphql
ENDPOINT  METHOD  STATUS  ITEMS  VERDICT
partners  POST    200     500    ok
```

`METHOD` reads `POST`: the pre-flight composes the request the run composes, so a graphql
endpoint is knocked on the way a run knocks — one page, 500 nodes, nothing written.

```
$ lm connector run acme-graphql
run scheduled: dc16e1a2-594c-4609-8116-08d43d1b63ff
$ lm run list --connector acme-graphql
ID        CONNECTOR     ENDPOINT  STATUS     IN    OUT   SKIPPED  DEAD-LETTERS  STARTED           ENDED
dc16e1a2  acme-graphql  partners  Completed  2400  2400  0        0             2026-09-10 09:57  2026-09-10 09:57
```

## The manifest

| Field | Where | Required | Notes |
|---|---|---|---|
| `graphql_path` | `spec` | no | The single POST endpoint; requests go to `base_url + graphql_path` (default `/graphql`) |
| `kind: graphql` | endpoint | no | An endpoint carrying a `query` is treated as GraphQL either way — the examples declare it for readability |
| `query` | endpoint | yes | The query document; declare the cursor variable it expects (e.g. `$after`) |
| `variables` | endpoint | no | Static variables sent with every request; the relay cursor is injected on top |
| `pagination` | endpoint | yes | **Hard rule:** `kind: relay` with `cursor_binding: variable` — anything else is refused at apply |
| `cursor_var` | pagination | yes | The variable that receives `endCursor` (e.g. `after`) |
| `cursor_path`, `has_more_path`, `response_path` | pagination | yes | JSONPaths to `endCursor`, `hasNextPage` and the node list |
| `target` | endpoint | yes | The same hybrid target as REST: `table`, `unique_key`, `promote` |
| `incremental` | endpoint | no | Leave it at `full` — see [Incremental does not reach a GraphQL server](#incremental-does-not-reach-a-graphql-server) |

`path` and `method` are implicit — the request is always a POST to `graphql_path`.

In the query document the variable carries its GraphQL sigil (`$after`); `cursor_var: after`
names that same variable without it. That is where the engine writes `endCursor` for the next
page.

There is no page-size field: page size lives in the query itself (`first: 500` above), unlike
SOAP's explicit `page_size`.

## How it works

- The query is POSTed as `{ "query": ..., "variables": ... }` to `base_url + graphql_path`.
- Relay pagination reads `pageInfo { hasNextPage endCursor }`, and re-injects `endCursor` into
  `variables.<cursor_var>` for the next request — the Relay Connections convention.
- `response_path: ...edges[*].node` extracts the node list (a JSONPath projection), so the rows
  that land are the nodes, without the `edges` wrapper.
- A relay cursor is **opaque**: read it out of `pageInfo` and hand it back untouched. It is not
  a row id, and a manifest that tries to build one from the data pages wrongly the day the
  server changes its encoding.

Note the asymmetry with the `cursor` strategy, which unwraps a one-element list from
`$.data[-1:].id`: `relay` does not — `cursor_path` must resolve to a scalar directly.

## The hard rule, and its two refusals

A graphql endpoint must declare `kind: relay` with `cursor_binding: variable`, and that binding
is valid nowhere else. The reason is mechanical: the binding writes the cursor into the POST
body's `variables.<cursor_var>`, and a plain GET has no body to write it into.

```
$ lm apply -f bad-gql.yaml
✗ bad-gql.yaml — 1 error(s), 0 warning(s)

  ERROR    spec.endpoints[0].pagination
          graphql endpoints require pagination.kind=relay with cursor_binding=variable
          → Use pagination: { kind: relay, cursor_binding: variable, cursor_var: <name>, cursor_path: ..., has_more_path: ..., response_path: ... }

Fix errors above before running 'lm apply -f bad-gql.yaml'.
Error: validation failed: 1 error(s)
```

The other half — `cursor_binding: variable` on a non-graphql endpoint — is refused the same way;
see [Cursor binding](rest/pagination.md#cursor-binding).

## The two ways a GraphQL server says no on HTTP 200

GraphQL does not report a failed query with a status code. A server that cannot resolve a field
answers `200` with an `errors` array; one whose resolver returned nothing answers `200` with
`"data": null`. Read as transport, both are successes — which is how a run reading them as data
would complete having ingested nothing.

Both are refused. `lm source rest test` refuses them before any run is started:

```
$ lm source rest test acme-graphql-errors
ENDPOINT      METHOD  STATUS  ITEMS  VERDICT
errors-array  POST    200     0      GraphQL response carried 1 error(s); first: Cannot query field 'boom' on type 'Query'
null-data     POST    200     0      GraphQL response has no errors and null/absent data (malformed envelope): {"data": null}
Error: 2 of 2 enabled endpoint(s) did not answer with readable data
```

and a run that meets them fails, carrying the same sentence:

```yaml
--8<-- "docs/connectors/rest/demo/80-graphql-errors.yaml"
```

```
$ lm run list --connector acme-graphql-errors
ID        CONNECTOR            ENDPOINT      STATUS  IN  OUT  SKIPPED  DEAD-LETTERS  STARTED           ENDED
ef56f596  acme-graphql-errors  null-data     Failed  0   0    0        0             2026-09-10 09:57  2026-09-10 09:57
2408ee9a  acme-graphql-errors  errors-array  Failed  0   0    0        0             2026-09-10 09:57  2026-09-10 09:57

$ lm run get 2408ee9a
✖ run failed: GraphQL response carried 1 error(s); first: Cannot query field 'boom' on type 'Query'
```

The refusal is total: no partial ingestion, and nothing is dead-lettered — a query the server
would not answer produced no rows to quarantine. Fix the query, or the permission it needs, and
run again.

## Headers

`spec.headers[]` applies to every request: `{ name, value }` or `{ name, value_env }`, the
latter resolved from the hub's environment or a JVM system property — **not** from the
`lm secret` registry, and an unset variable drops the header silently. Auth-injected headers win
a name clash. Same engine, same rule as [REST request headers](rest.md#request-headers).

## Incremental does not reach a GraphQL server

`incremental: { mode: watermark }` is accepted on a graphql endpoint and does nothing useful.
The stored mark travels as a **query parameter**, which is where a REST source expects its
filter and where a GraphQL server never looks — its arguments are in the body:

```
10/Sep/2026 10:11:09  "POST /graphql?updated_since=2026-01-02T15%3A59%3A00Z HTTP/1.1" 200 -
```

The server answers with everything, the run reads every node again and recomputes the same
mark. Nothing is lost and nothing is gained: two successive runs of such an endpoint both
report 2 400 rows. Until a variable-bound watermark exists, filter in the query itself — a
static `variables:` entry, or a literal argument — and leave `incremental` at `full`.

## Not yet supported

Mutations, subscriptions, schema-introspection discovery, persisted queries, query batching,
per-endpoint header overrides, and `on_error: warn` (partial-success ingestion).

## How to…

### Send static variables with every request

```yaml
endpoints:
  - id: partners
    kind: graphql
    variables: { locale: fr-FR }   # merged into every request
```

**Result:** every request carries `locale: fr-FR` in its GraphQL `variables`, alongside the
relay cursor the engine injects — a fixed filter the query itself does not parameterize.

### Read a second collection from the same API

Add a second endpoint with its own query, its own `pagination` paths (they name the collection:
`$.data.<collection>.pageInfo.endCursor`) and its own table. One connector, one API, one
credential; the ledger records one run per endpoint.

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| Run fails though the HTTP status was 200 | an `errors[]` array, or `data: null` with no errors | read the message in `lm run get` — it carries the server's first error verbatim |
| Run completed with `IN` 0 | `response_path` does not match the envelope | `lm source rest test <connector>` prints the item count it read from one page |
| `cursor_binding: variable` refused at apply | that binding is only valid on `relay` on a graphql endpoint | use `query` or `header` binding on a plain REST endpoint |
| Pagination never ends | `cursor_path` resolves to a value that does not change | it must point at `pageInfo.endCursor`, the opaque cursor the server hands back |

## See also

- [REST](rest.md) — the engine underneath (auth, retry, rate limit, storage layout)
- [Pagination](rest/pagination.md) — `relay`, and the binding rule above
- [The demo API](rest/demo-api.md) — the source these screens read
- [Overview](index.md)
- [Transformers](transformers.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
