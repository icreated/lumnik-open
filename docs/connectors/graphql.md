# GraphQL

> Ingest any GraphQL API by putting the query in the manifest — a request shape on the
> REST engine (`connector_type` stays `rest-generic`), Relay pagination required.

| | |
|---|---|
| **Type** | `rest-generic` (shape `graphql`) |
| **Protocol** | GraphQL over POST |
| **Target** | `ext.*` hybrid |
| **Schedulable** | cron |
| **Edition** | Open |

Auth, rate-limiting, retry, and the hybrid JSONB+typed storage are all shared with
[REST](rest.md) — this page covers only what's different: the query, Relay pagination, and
error handling.

## YAML manifest

```yaml
--8<-- "docs/connectors/graphql/10-relay-basic.yaml"
```

## Fields

| Field | Where | Required | Notes |
|---|---|---|---|
| `graphql_path` | `spec` | no | The single POST endpoint; requests go to `base_url + graphql_path` (default `/graphql`) |
| `kind: graphql` | endpoint | no | Optional; an endpoint carrying a `query` is treated as GraphQL either way — the examples declare it for readability |
| `query` | endpoint | yes | The GraphQL query document; declare the cursor variable it expects (e.g. `$after`) |
| `variables` | endpoint | no | Static variables sent with every request; the relay cursor is injected on top |
| `pagination` | endpoint | yes | **Hard rule:** `kind: relay` with `cursor_binding: variable` — anything else is refused at apply |
| `cursor_var` | pagination | yes | The variable that receives `endCursor` (e.g. `after`) |
| `cursor_path`, `has_more_path`, `response_path` | pagination | yes | JSONPaths to `endCursor`, `hasNextPage`, and the node list |
| `target` | endpoint | yes | Same hybrid target as REST: `table`, `unique_key`, `promote` |
| `incremental` | endpoint | no | Optional, defaults to `full` (re-fetch all pages every run); same modes as REST — see [Incremental sync](rest.md#incremental-sync) |

`path` and `method` are implicit — the request is always a POST to `graphql_path` — and not
required. Conversely, `cursor_binding: variable` is only valid here: on a non-graphql endpoint
the validator refuses it (the cursor would ride a POST body that a plain GET never sends).

In the query document the variable carries its GraphQL sigil, e.g. `$after`; `cursor_var: after`
names that same variable without the `$` — that's where the engine injects `endCursor` for the
next page.

There is no separate page-size field for GraphQL endpoints — page size lives in the query
itself (e.g. `first: 100` in the example above), unlike SOAP's explicit `page_size`.

## How it works

- The query is POSTed as `{ "query": ..., "variables": ... }` to `base_url + graphql_path`.
- Relay pagination reads `pageInfo { hasNextPage endCursor }`; the `endCursor` is re-injected
  into `variables.<cursor_var>` for the next page (the Relay Connections pagination standard).
- `response_path: ...edges[*].node` extracts the clean node list (Jayway JSONPath projection).
- GraphQL errors arrive as HTTP 200 with an `errors[]` array — any error fails the run and
  dead-letters the chunk (strict; no partial ingestion in this release). A 200 envelope with
  `data: null` and no errors is refused the same way — success MUST carry data.

## Headers

`spec.headers[]` applies to every request: `{ name, value }` (static) or `{ name, value_env }`
(resolved from the hub server's environment / a JVM system property — **not** the `lm secret`
registry; an unset variable silently drops the header). Auth-injected headers win on a name
clash. Same engine and same rule as [REST request headers](rest.md).

## Not yet supported (future)

Mutations, subscriptions, schema-introspection discovery, persisted queries, query batching,
per-endpoint header overrides, and `on_error: warn` (partial-success ingestion).

## How to…

### Connect a Relay API

Point `graphql_path` at the API's single POST endpoint, put the query (with its cursor
variable) in the endpoint, and pair `pagination: { kind: relay, cursor_binding: variable }`
with a `cursor_var` matching that variable's name:

```yaml
--8<-- "docs/connectors/graphql/10-relay-basic.yaml"
```

**Result:** `lm apply -f shop.yaml` registers the connector; `lm connector run shop` pages
through `orders` via Relay's `pageInfo`, writing rows into `ext.shop_orders`. Watch it land in
the TUI (`:runs`), or see [CLI & TUI](../cli/lm-reference.md).

### Send static variables with every request

```yaml
endpoints:
  - id: orders
    kind: graphql
    variables: { someFlag: true }   # merged into every request
```

**Result:** every request carries `someFlag: true` in its GraphQL `variables`, alongside the
relay cursor — useful for a fixed filter the query itself doesn't parameterize. Check it in
the TUI (`:runs`), or see [CLI & TUI](../cli/lm-reference.md).

## Troubleshooting

| Symptom | Cause | Gesture |
|---|---|---|
| Run fails though the HTTP status is 200 | a GraphQL `errors[]` array (or `data: null` with no errors) fails the run — strict by design, no partial ingestion | fix the query or the upstream permissions it needs |
| `cursor_binding: variable` refused at apply | that binding is only valid on `relay` pagination on a graphql-shaped endpoint | use `query` or `header` binding on a plain REST endpoint instead |

## See also

- [REST](rest.md) — the engine underneath (auth providers, retry, rate limit, storage layout)
- [Overview](index.md)
- [Transformers](transformers.md)
- [Diagnosing a failed run](../recipes/diagnose-a-run.md)
